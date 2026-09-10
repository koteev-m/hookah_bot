#!/usr/bin/env python3
"""Own second dockerd and real binding supervisor; synthetic create-only image.

Native mode is restricted to an authorized disposable GitHub-hosted Linux VM.
No default Docker socket/service, running container, pull, TCP endpoint or reboot.
"""
import argparse
import hashlib
import http.client
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import socketserver
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import traceback
import unittest
from unittest.mock import Mock, patch
from urllib.parse import parse_qs, quote, urlsplit
import uuid

sys.dont_write_bytecode = True
SELF = Path(__file__).resolve()
SCRIPTS = SELF.parent
PREFIX = 'ht-v126-daemon-'
LABEL = 'v126.daemon.fixture'
CASES = ('positive', 'before-forward', 'dispatched-no-reply', 'after-created', 'missing-result')


def require(value, reason):
    if not value:
        raise AssertionError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def hosted_only():
    require(sys.platform == 'linux' and os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted' and os.environ.get('RUNNER_OS') == 'Linux',
            'requires explicitly authorized disposable GitHub-hosted Linux')
    require(os.geteuid() == 0, 'own second daemon requires the authorized root coordinator')
    require(Path('/proc/1/comm').read_text().strip() == 'systemd' and Path('/run/systemd/system').is_dir(),
            'actual host systemd required; no runtime skip')
    require(Path('/sys/fs/cgroup/cgroup.controllers').is_file(), 'unified own-unit cgroup inspection required')
    require(os.environ.get('RUNNER_TEMP') and Path(os.environ['RUNNER_TEMP']).is_dir(), 'explicit runner temp required')
    for key in ('DATABASE_URL', 'DB_PASSWORD', 'TELEGRAM_BOT_TOKEN', 'GH_TOKEN', 'GITHUB_TOKEN',
                'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'):
        require(not os.environ.get(key), 'inherited credential or Docker target input refused')


def private_root(spec):
    root = Path(spec['root'])
    require(root.is_absolute() and root.resolve(strict=True) == root and not root.is_symlink(), 'daemon root not canonical')
    require(re.fullmatch(PREFIX + '[0-9a-f]{32}', root.name), 'daemon root identity differs')
    require(root.stat().st_uid == os.geteuid() and stat.S_IMODE(root.stat().st_mode) == 0o700, 'daemon root ownership differs')
    for key, name in (('socket', 'd.sock'), ('proxy_socket', 'p.sock'), ('data_root', 'data'),
                      ('exec_root', 'exec'), ('pidfile', 'dockerd.pid'), ('config', 'daemon.json'), ('cli_config', 'cli')):
        path = Path(spec[key])
        require(path == root / name and not path.is_symlink(), 'daemon path escaped owned namespace')
        if key in ('socket', 'proxy_socket'):
            require(len(os.fsencode(path)) < 100, 'owned Unix socket path too long')
            if path.exists():
                require(stat.S_ISSOCK(path.stat().st_mode) and path.stat().st_uid == os.geteuid(), 'owned endpoint is not its socket')
    require(spec['unit'] == root.name + '.service', 'daemon unit identity differs')
    require(spec['namespace'] == root.name, 'containerd namespace differs')
    require(spec['plugin_namespace'] == root.name + '-plugins', 'containerd plugin namespace differs')
    return root


def daemon_argv(spec):
    private_root(spec)
    return [spec['dockerd'], '--host=unix://' + spec['socket'], '--data-root=' + spec['data_root'],
            '--exec-root=' + spec['exec_root'], '--pidfile=' + spec['pidfile'], '--config-file=' + spec['config'],
            '--storage-driver=vfs', '--bridge=none', '--iptables=false', '--ip6tables=false',
            '--ip-forward=false', '--ip-masq=false', '--userland-proxy=false',
            '--containerd-namespace=' + spec['namespace'], '--containerd-plugins-namespace=' + spec['plugin_namespace']]


def docker_argv(spec, args, *, proxy=False):
    private_root(spec)
    require(isinstance(args, list) and all(isinstance(value, str) and '\x00' not in value for value in args), 'Docker arguments invalid')
    require(not any(value in ('--host', '-H', '--context', '--config') or value.startswith(('--host=', '--context=', '--config='))
                    for value in args), 'Docker target override refused')
    endpoint = spec['proxy_socket'] if proxy else spec['socket']
    return [spec['docker'], '--config', spec['cli_config'], '--host', 'unix://' + endpoint, *args]


def create_body(spec):
    require(re.fullmatch('sha256:[0-9a-f]{64}', spec['image_id']), 'synthetic image identity missing')
    require(re.fullmatch(PREFIX + '[0-9a-f]{32}-(?:' + '|'.join(CASES) + ')', spec['name']), 'container name outside fixture')
    return {'Image': spec['image_id'], 'Cmd': ['/synthetic-never-started'], 'Labels': {LABEL: spec['name']},
            'HostConfig': {'NetworkMode': 'none', 'RestartPolicy': {'Name': 'no'}}}


def api_plan(spec, method, target, body):
    parsed = urlsplit(target)
    require(not parsed.scheme and not parsed.netloc and not parsed.fragment, 'API target authority refused')
    path = re.sub(r'^/v[0-9]+\.[0-9]+(?=/)', '', parsed.path)
    if method in ('HEAD', 'GET') and path in ('/_ping', '/version') and not parsed.query and not body:
        return 'read-only'
    require(method == 'POST' and path == '/containers/create', 'unexpected Docker API operation')
    require(parse_qs(parsed.query, strict_parsing=True) == {'name': [spec['name']]}, 'create name differs')
    require(0 < len(body) <= 65536, 'create request body bound differs')
    require(body == canonical(create_body(spec)), 'create payload differs from the exact synthetic metadata request')
    return 'create'


def excerpt(raw, limit=8192):
    text = raw.decode('utf-8', errors='replace')
    secrets = {value for key, value in os.environ.items() if value and any(part in key.upper() for part in ('PASSWORD', 'TOKEN', 'SECRET', 'PEPPER'))}
    variants = {variant for value in secrets for variant in (value, quote(value, safe=''), json.dumps(value)[1:-1])}
    for value in sorted(variants, key=len, reverse=True):
        text = text.replace(value, '[REDACTED]')
    return text.encode()[:limit].decode('utf-8', errors='ignore')


def expected_refusal(case, variant):
    if variant == 'other-run':
        return 'target_bound_to_another_run'
    if case == 'positive':
        return 'operation_already_dispatched'
    return 'prior_outcome_unknown' if case == 'missing-result' else 'prior_daemon_outcome_unknown'


def failure_record(phase, error):
    return {'phase': phase, 'type': type(error).__name__,
            'reason': excerpt(str(error).encode(), 1000) if isinstance(error, AssertionError) else 'non-assertion failure',
            'message_sha256': sha(str(error).encode()),
            'locations': [{'file': Path(frame.filename).name, 'line': frame.lineno, 'function': frame.name}
                          for frame in traceback.extract_tb(error.__traceback__) if Path(frame.filename) == SELF]}


def require_peer(raw, expected_pid):
    pid, uid, _gid = struct.unpack('3i', raw)
    require(pid == expected_pid and uid == 0, 'private socket daemon peer differs')


def unit_text(spec):
    command = ' '.join(shlex.quote(value) for value in daemon_argv(spec))
    return ('[Unit]\nDescription=Owned V126 private Docker daemon fixture\n[Service]\nType=simple\nRestart=no\n'
            'KillMode=control-group\nTimeoutStartSec=45\nTimeoutStopSec=5\n'
            'Environment=HOME=' + spec['root'] + '\nExecStart=' + command + '\nStandardOutput=null\nStandardError=journal\n')


def clean_env(home):
    keep = ('PATH', 'RUNNER_TEMP', 'GITHUB_ACTIONS', 'RUNNER_ENVIRONMENT', 'RUNNER_OS', 'LANG', 'LC_ALL')
    return {key: os.environ[key] for key in keep if key in os.environ} | {'HOME': str(home)}


def spec_read(path):
    path = Path(path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o400
            and info.st_nlink == 1, 'fixture specification is not sealed')
    raw = path.read_bytes()
    spec = json.loads(raw)
    require(raw == canonical(spec), 'fixture specification not canonical')
    require(spec['test_sha256'] == sha(SELF.read_bytes()), 'fixture source changed')
    require(spec['binding_sha256'] == sha((SCRIPTS / 'v126-operation-bindings.py').read_bytes()), 'binding source changed')
    private_root(spec)
    return spec


def identity(spec, variant='original'):
    value = dict(run_id=spec['run_id'], release_sha='a' * 40, script_sha256=spec['test_sha256'],
                 intent_sha256=sha(('synthetic-intent-' + variant).encode()), kind='STAGE', name='BASELINE_VERIFIED', action='baseline')
    if variant == 'other-run':
        value['run_id'] += '-other'
    elif variant == 'recovery':
        value.update(kind='RECOVERY', name='pre-v126', action='recover-pre-v126')
    return value


def child_mode(mode, path, variant):
    hosted_only()
    spec = spec_read(path)
    bindings = load_module('daemon_gate_bindings_child', SCRIPTS / 'v126-operation-bindings.py')
    owner = identity(spec, variant)
    try:
        if mode == 'supervise':
            return bindings.binding_supervise(spec['target'], owner, [sys.executable, str(SELF), '--docker-create', str(path)],
                input_data=b'', env=clean_env(spec['root']), timeout=20,
                request_context={'args': [spec['target'], owner['run_id'], owner['release_sha'], 'synthetic-docker-create', str(path)],
                                 'environment': {}})
        if mode == 'docker-create':
            # This synthetic leaf uses the actual daemon API; no Docker CLI default
            # payload fields are authorized implicitly by the transport adapter.
            connection = ProxyConnection(spec)
            try:
                connection.request('GET', '/version')
                version_response = connection.getresponse()
                version = json.loads(version_response.read(65537))
                require(version_response.status == 200 and re.fullmatch(r'[0-9]+\.[0-9]+', version.get('ApiVersion', '')),
                        'actual own Docker API version unavailable')
                connection.close()
                connection.request('POST', '/v' + version['ApiVersion'] + '/containers/create?name=' + spec['name'],
                    body=canonical(create_body(spec)), headers={'Content-Type': 'application/json', 'Connection': 'close'})
                response = connection.getresponse()
                raw = response.read(65537)
                require(len(raw) <= 65536 and response.status == 201, 'actual synthetic API create failed')
                container_id = json.loads(raw).get('Id', '')
                require(re.fullmatch('[0-9a-f]{64}', container_id), 'actual API create identity unavailable')
                print(container_id, flush=True)
                return 0
            except (OSError, http.client.HTTPException):
                print('OWN_DOCKER_API_TRANSPORT_UNKNOWN', file=sys.stderr)
                return 42
            finally:
                connection.close()
        if mode == 'inspect':
            sys.argv = ['inspect', spec['target']]
            bindings.binding_entry('inspect')
            return 0
        if mode == 'retire':
            next_owner = identity(spec, 'other-run')
            sys.argv = ['retire', spec['target'], *[owner[key] for key in ('run_id', 'release_sha', 'script_sha256')],
                        'b' * 64, str(Path(spec['target']) / 'no-approved-handoff'),
                        *[next_owner[key] for key in ('run_id', 'release_sha', 'script_sha256')],
                        'V126', spec['image_id']]
            bindings.binding_entry('retire')
            return 0
    except bindings.BindingError as error:
        print('DAEMON_BINDING_REFUSED reason=' + str(error), file=sys.stderr)
        return 75
    raise AssertionError('unsupported own child mode')


class UnixConnection(http.client.HTTPConnection):
    def __init__(self, fixture, timeout=4):
        super().__init__('owned-docker', timeout=timeout)
        self.fixture = fixture

    def connect(self):
        private_root(self.fixture.spec)
        require(self.fixture.daemon_pid is not None, 'owned daemon identity unavailable')
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.fixture.spec['socket'])
        require_peer(self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12), self.fixture.daemon_pid)


class ProxyConnection(http.client.HTTPConnection):
    def __init__(self, spec):
        super().__init__('owned-proxy', timeout=12)
        self.spec = spec

    def connect(self):
        private_root(self.spec)
        require(type(self.spec['proxy_pid']) is int and self.spec['proxy_pid'] > 0, 'own proxy identity unavailable')
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(12)
        self.sock.connect(self.spec['proxy_socket'])
        require_peer(self.sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12), self.spec['proxy_pid'])


class ApiServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = False
    block_on_close = False

    def process_request(self, request, address):
        thread = threading.Thread(target=self.process_request_thread, args=(request, address))
        with self.proxy.lock:
            self.proxy.workers.append(thread)
        thread.start()


class ApiHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'

    def log_message(self, *_args):
        pass

    def send_error(self, code, message=None, explain=None):
        self.server.proxy.errors.append({'type': 'UnexpectedOrMalformedApiRequest', 'status': code})
        self.close_connection = True

    def setup(self):
        self.request.settimeout(5)
        super().setup()

    def handle(self):
        # Aggregate request lifetime also bounds slow headers and successive reads.
        def expire():
            for connection in (self.request, getattr(self, 'upstream_socket', None)):
                if connection is not None:
                    try:
                        connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
        timer = threading.Timer(10, expire)
        with self.server.proxy.lock:
            self.server.proxy.timers.append(timer)
        timer.start()
        try:
            super().handle()
        finally:
            timer.cancel()
            timer.join(timeout=1)
            if timer.is_alive():
                self.server.proxy.errors.append({'type': 'RequestTimerLifetimeUnproven'})

    def do_HEAD(self):
        self.forward()

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        proxy = self.server.proxy
        upstream = None
        try:
            require(not self.headers.get('Transfer-Encoding'), 'chunked fixture request refused')
            lengths = self.headers.get_all('Content-Length', [])
            require(len(lengths) <= 1, 'duplicate content length')
            length = int(lengths[0]) if lengths else 0
            require(0 <= length <= 65536, 'request too large')
            body = self.rfile.read(length)
            require(len(body) == length, 'request body incomplete')
            plan = api_plan(proxy.spec, self.command, self.path, body)
            if plan == 'create':
                with proxy.lock:
                    proxy.creates += 1
                require(proxy.creates == 1, 'duplicate create dispatch refused')
                proxy.note('CREATE_RECEIVED', request_sha256=sha(body), request_bytes=len(body))
                proxy.contend()
                if proxy.spec['case'] == 'before-forward':
                    proxy.note('NOT_FORWARDED')
                    self.close_connection = True
                    return
            upstream = UnixConnection(proxy.fixture)
            upstream.request(self.command, self.path, body=body or None,
                             headers={'Content-Type': 'application/json', 'Connection': 'close'})
            self.upstream_socket = upstream.sock
            if plan == 'create':
                proxy.note('REQUEST_SENT_REPLY_UNOBSERVED')
                if proxy.spec['case'] == 'dispatched-no-reply':
                    proxy.fixture.kill_daemon()
                    self.close_connection = True
                    return
            response = upstream.getresponse()
            raw = response.read(1048577)
            require(len(raw) <= 1048576, 'private API response too large')
            proxy.note('ACTUAL_API_RESPONSE', status=response.status, response_sha256=sha(raw), response_bytes=len(raw))
            if plan == 'create':
                require(response.status == 201, 'actual private daemon did not create its synthetic container')
                created = json.loads(raw)
                require(re.fullmatch('[0-9a-f]{64}', created.get('Id', '')), 'actual created container identity absent')
                proxy.created_id = created['Id']
                proxy.note('ACTUAL_CREATED_201', container_id=created['Id'], response_sha256=sha(raw))
                if proxy.spec['case'] in ('after-created', 'missing-result'):
                    if proxy.spec['case'] == 'missing-result':
                        proxy.fixture.kill_supervisor()
                    proxy.fixture.kill_daemon()
                    self.close_connection = True
                    return
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in ('connection', 'transfer-encoding', 'content-length'):
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(raw)
        except BaseException as error:
            proxy.errors.append({'type': type(error).__name__, 'reason_sha256': sha(str(error).encode()),
                                 'reason': excerpt(str(error).encode(), 500) if isinstance(error, AssertionError) else None})
            proxy.note('API_HANDLER_REFUSED')
            self.close_connection = True
        finally:
            if upstream is not None:
                upstream.close()


class Proxy:
    def __init__(self, fixture, spec, spec_path, row):
        self.fixture, self.spec, self.spec_path = fixture, spec, spec_path
        self.events, self.errors, self.contenders, self.workers, self.timers = [], [], [], [], []
        self.row = row
        row.update(api_events=self.events, api_errors=self.errors, active_contenders=self.contenders)
        self.creates, self.created_id = 0, None
        self.lock = threading.Lock()
        path = Path(spec['proxy_socket'])
        require(not path.exists() and not path.is_symlink(), 'proxy socket path already allocated')
        self.server = ApiServer(str(path), ApiHandler)
        self.server.proxy = self
        path.chmod(0o600)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .05})
        self.thread.start()

    def note(self, boundary, **values):
        self.events.append({'boundary': boundary, 'monotonic_seconds': round(time.monotonic(), 6), **values})
        self.row.update(create_requests=self.creates, observed_container_id=self.created_id)
        self.fixture.progress(self.row)

    def contend(self):
        # This child remains inside the surrounding owned command scope. The real
        # supervisor must refuse its nonblocking target lock before any worker.
        result = subprocess.run([sys.executable, str(SELF), '--supervise', str(self.spec_path), '--variant', 'recovery'],
            cwd=self.spec['state_b'], env=clean_env(self.spec['root']), capture_output=True, timeout=5)
        self.contenders.append({'context': 'different-local-state-while-active', 'exit': result.returncode,
                                'expected_refusal': 'target_busy', 'stderr_sha256': sha(result.stderr)})
        self.note('ACTIVE_CONTENDER_OBSERVED')
        require(result.returncode == 75 and b'DAEMON_BINDING_REFUSED reason=target_busy\n' == result.stderr,
                'concurrent real recovery did not refuse its target lock')

    def close(self):
        self.server.shutdown()
        self.thread.join(timeout=2)
        require(not self.thread.is_alive(), 'proxy listener lifetime unproven')
        # Explicitly tracked handlers have aggregate and per-socket bounds.
        deadline = time.monotonic() + 12
        with self.lock:
            workers = list(self.workers)
        for thread in workers:
            thread.join(timeout=max(0, deadline - time.monotonic()))
        require(all(not thread.is_alive() for thread in workers), 'proxy handler lifetime unproven')
        with self.lock:
            timers = list(self.timers)
        for timer in timers:
            timer.cancel()
            timer.join(timeout=max(0, deadline - time.monotonic()))
        require(all(not timer.is_alive() for timer in timers), 'proxy timer lifetime unproven')
        self.server.server_close()
        Path(self.spec['proxy_socket']).unlink()


class Fixture:
    def __init__(self, evidence):
        self.tools = {name: shutil.which(name) for name in ('dockerd', 'docker', 'systemctl', 'sudo', 'journalctl')}
        require(all(self.tools.values()), 'existing dockerd Docker systemctl sudo journalctl binaries required')
        self.scope_module = load_module('daemon_owned_scope', SCRIPTS / 'test-v126-systemd-linux.py')
        self.evidence = evidence.resolve()
        self.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
        runner = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
        self.root = runner / (PREFIX + uuid.uuid4().hex)
        self.root.mkdir(mode=0o700)
        self.spec = {'root': str(self.root), 'socket': str(self.root / 'd.sock'), 'proxy_socket': str(self.root / 'p.sock'),
            'data_root': str(self.root / 'data'), 'exec_root': str(self.root / 'exec'), 'pidfile': str(self.root / 'dockerd.pid'),
            'config': str(self.root / 'daemon.json'), 'cli_config': str(self.root / 'cli'),
            'unit': self.root.name + '.service', 'namespace': self.root.name, 'plugin_namespace': self.root.name + '-plugins', **self.tools,
            'test_sha256': sha(SELF.read_bytes()), 'binding_sha256': sha((SCRIPTS / 'v126-operation-bindings.py').read_bytes())}
        self.unit_path = Path('/run/systemd/system') / self.spec['unit']
        self.events, self.cases, self.failures = [], [], []
        self.unit_allocated = False
        self.unit_identity = None
        self.callers_quiescent = True
        self.proxy = self.active_scope = None
        self.daemon_fd = self.daemon_pid = self.daemon_birth = None
        self.engine_id = None
        self.source_sha = sha((SCRIPTS / 'v126-cutover.sh').read_bytes())
        self.env = clean_env(self.root)

    def progress(self, row, **values):
        row.update(values)
        row['last_observed_monotonic_seconds'] = round(time.monotonic(), 6)
        # Every append retains the previously observed boundary. Registry records
        # remain separately immutable under the same evidence root.
        with (self.evidence / row['case'] / 'progress.jsonl').open('ab') as output:
            output.write(canonical(row)); output.flush(); os.fsync(output.fileno())

    def failure_diagnostics(self):
        if not self.callers_quiescent or not self.unit_allocated:
            self.events.append({'own_unit_diagnostics': 'UNAVAILABLE', 'reason': 'unit not allocated or caller lifetime unproven'})
            return
        try:
            self.assert_unit_file()
            props = self.properties()
            result = self.run([self.tools['journalctl'], '--unit=' + self.spec['unit'], '--no-pager', '--lines=80', '--output=cat'],
                              check=False, timeout=5)
            self.events.append({'own_unit_diagnostics': 'OBSERVED', 'unit_properties': props, 'journal_exit': result.returncode,
                'journal_stdout_sha256': sha(result.stdout), 'journal_stdout_bytes': len(result.stdout),
                'journal_excerpt': excerpt(result.stdout), 'journal_stderr_sha256': sha(result.stderr)})
        except BaseException as error:
            self.events.append({'own_unit_diagnostics': 'UNAVAILABLE', 'type': type(error).__name__,
                                'reason_sha256': sha(str(error).encode())})

    def record_failure(self, error):
        # Latch the primary before any fallible read/stat/diagnostic operation.
        self.failures.append(failure_record('integration', error))
        try:
            if self.cases and self.callers_quiescent:
                row = self.cases[-1]
                files = self.snapshot(self.evidence / row['case'] / 'target')
                self.progress(row, phase='FAILED_AFTER_OBSERVATION', original_registry_files_at_failure=files)
            self.failure_diagnostics()
        except BaseException as secondary:
            self.events.append({'secondary_diagnostic_failure': type(secondary).__name__,
                                'message_sha256': sha(str(secondary).encode())})

    def assert_unit_file(self):
        info = self.unit_path.lstat()
        require(self.unit_identity == (info.st_dev, info.st_ino) and stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
                and info.st_nlink == 1, 'allocated own unit file identity differs')

    def run(self, argv, *, check=True, timeout=12, cwd=None, allow_refused=False):
        require(self.callers_quiescent, 'prior command lifetime unproven')
        scope = self.scope_module.OwnedCommandScope(self.tools['sudo'])
        self.active_scope = scope
        self.callers_quiescent = False
        before = time.monotonic()
        try:
            try:
                result = scope.execute([str(value) for value in argv], env=self.env, cwd=cwd or self.root, timeout=timeout)
            except self.scope_module.OwnedDescendantRefusal as error:
                if not (allow_refused and error.result.returncode != 0 and scope.quiescent):
                    raise
                result = error.result
                self.events.append({'caller_outcome': 'UNKNOWN', 'descendants': error.descendant_count, 'cleanup': 'REAPED'})
        finally:
            self.callers_quiescent = scope.quiescent
            self.active_scope = None
        event = {'command': Path(argv[0]).name, 'exit': result.returncode, 'seconds': round(time.monotonic()-before, 3),
            'deadline_seconds': timeout, 'stdout_bytes': len(result.stdout), 'stdout_sha256': sha(result.stdout),
            'stderr_bytes': len(result.stderr), 'stderr_sha256': sha(result.stderr)}
        self.events.append(event)
        if check:
            require(result.returncode == 0, 'owned command failed: ' + Path(argv[0]).name + ' exit ' + str(result.returncode))
        return result

    def manager(self, *args, **kwargs):
        return self.run([self.tools['systemctl'], *args], **kwargs)

    def properties(self, *, timeout=12):
        result = self.manager('show', self.spec['unit'], '--property=MainPID,ControlPID,ControlGroup,FragmentPath,ActiveState,SubState', timeout=timeout)
        return dict(line.split('=', 1) for line in result.stdout.decode().splitlines() if '=' in line)

    def prepare(self):
        require(all(callable(signal.getsignal(sig)) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)),
                'signal handlers required before own daemon allocation')
        for tool in ('dockerd', 'docker', 'systemctl'):
            version = self.run([self.tools[tool], '--version'])
            self.events.append({'version_tool': tool, 'version': version.stdout.decode().strip()})
        bindings = load_module('daemon_gate_binding_source', SCRIPTS / 'v126-operation-bindings.py')
        require(bindings.binding_embedded_source((SCRIPTS / 'v126-cutover.sh').read_bytes(), 'remote_operation_bindings_python')
                == (SCRIPTS / 'v126-operation-bindings.py').read_bytes(), 'shared binding embedding differs')
        Path(self.spec['config']).write_bytes(b'{}\n')
        Path(self.spec['config']).chmod(0o400)
        Path(self.spec['cli_config']).mkdir(mode=0o700)
        require(not self.unit_path.exists() and not self.unit_path.is_symlink(), 'own unit path already exists')
        # Block interruption only across exclusive allocation/identity recording.
        # No existing path is claimed if exclusive creation fails.
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGHUP, signal.SIGINT, signal.SIGTERM})
        try:
            with self.unit_path.open('x') as output:
                info = os.fstat(output.fileno())
                self.unit_identity = (info.st_dev, info.st_ino)
                self.unit_allocated = True
                output.write(unit_text(self.spec)); output.flush(); os.fsync(output.fileno())
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        self.unit_path.chmod(0o644)
        self.manager('daemon-reload')
        self.start_daemon()
        archive = self.root / 'synthetic-rootfs.tar'
        with tarfile.open(archive, 'w') as tar:
            data = b'synthetic create-only rootfs; no executable\n'
            entry = tarfile.TarInfo('v126-synthetic-marker')
            entry.size, entry.mode, entry.mtime = len(data), 0o444, 0
            tar.addfile(entry, io.BytesIO(data))
        result = self.run(docker_argv(self.spec, ['image', 'import', str(archive), self.root.name + ':synthetic']), timeout=20)
        image_id = result.stdout.decode().strip()
        require(re.fullmatch('sha256:[0-9a-f]{64}', image_id), 'private imported image ID missing')
        self.spec['image_id'] = image_id
        self.events.append({'synthetic_image_id': image_id, 'rootfs_sha256': sha(archive.read_bytes()), 'containers_started': 0})

    def start_daemon(self):
        self.assert_unit_file()
        self.manager('start', self.spec['unit'], timeout=50)
        deadline = time.monotonic() + 35
        def remaining(bound):
            budget = deadline - time.monotonic()
            require(budget > 0, 'own daemon readiness deadline expired; outcome UNKNOWN')
            return min(bound, budget)
        info = None
        while info is None:
            props = self.properties(timeout=remaining(5))
            self.events.append({'daemon_readiness_unit': props})
            require(props.get('FragmentPath') == str(self.unit_path) and props.get('ControlGroup') in ('', '/system.slice/' + self.spec['unit']),
                    'own daemon systemd identity differs')
            require(props.get('ActiveState') in ('active', 'activating'), 'own daemon reached terminal state before readiness')
            result = self.run(docker_argv(self.spec, ['info', '--format', '{{json .}}']), check=False, timeout=remaining(5))
            try:
                observed = json.loads(result.stdout) if result.stdout else {}
            except (ValueError, UnicodeError):
                raise AssertionError('own Docker info JSON invalid') from None
            require(isinstance(observed, dict), 'own Docker info schema invalid')
            fields = {key: observed.get(key) or '' for key in ('DockerRootDir', 'Driver', 'ID', 'ServerVersion')}
            require(all(isinstance(value, str) for value in fields.values()), 'own Docker info identity fields invalid')
            errors = observed.get('ServerErrors') or []
            require(isinstance(errors, list) and all(isinstance(value, str) for value in errors), 'own Docker info server errors invalid')
            self.events.append({'daemon_readiness_info': {'exit': result.returncode, 'root_sha256': sha(fields['DockerRootDir'].encode()),
                'root_matches': fields['DockerRootDir'] == self.spec['data_root'], 'driver': fields['Driver'],
                'id_present': bool(fields['ID']), 'server_version': fields['ServerVersion'], 'server_error_count': len(errors)}})
            # A formatted client/error placeholder may exit zero. Empty server
            # fields are starting; an observed foreign identity always refuses.
            require(fields['DockerRootDir'] in ('', self.spec['data_root']) and fields['Driver'] in ('', 'vfs'),
                    'Docker daemon root/driver differs')
            require(not fields['ID'] or self.engine_id is None or fields['ID'] == self.engine_id,
                    'private engine identity changed across same-data restart')
            if result.returncode == 0 and not errors and all(fields.values()) and props['ActiveState'] == 'active':
                info = observed
                break
            time.sleep(remaining(.1))
        require(info.get('DockerRootDir') == self.spec['data_root'] and info.get('Driver') == 'vfs', 'Docker daemon root/driver differs')
        require(isinstance(info.get('ID'), str) and info['ID'], 'actual private daemon engine identity unavailable')
        require(self.engine_id is None or info['ID'] == self.engine_id, 'private engine identity changed across same-data restart')
        self.engine_id = info['ID']
        props = self.properties(timeout=remaining(5))
        require(props.get('FragmentPath') == str(self.unit_path) and props.get('ControlGroup') == '/system.slice/' + self.spec['unit'],
                'own daemon systemd identity differs')
        require(props.get('ActiveState') == 'active', 'own daemon reached terminal state before readiness')
        require(props.get('MainPID', '').isdigit() and int(props['MainPID']) > 0, 'own daemon MainPID missing')
        pid = int(props['MainPID'])
        require(Path('/proc/' + str(pid) + '/exe').resolve(strict=True) == Path(self.tools['dockerd']).resolve(), 'own MainPID is not actual dockerd')
        require(Path('/proc/' + str(pid) + '/cgroup').read_text().strip() == '0::/system.slice/' + self.spec['unit'], 'own daemon cgroup differs')
        self.daemon_fd = os.pidfd_open(pid)
        self.daemon_pid, self.daemon_birth = pid, self.scope_module.proc_identity(pid)
        require(self.daemon_birth is not None, 'own daemon birth identity unavailable')
        connection = UnixConnection(self, timeout=remaining(4))
        try:
            connection.request('GET', '/_ping')
            require(connection.getresponse().read(1024) == b'OK', 'private daemon peer ping differs')
        finally:
            connection.close()
        remaining(1)
        self.events.append({'daemon_started': True, 'main_pid': pid, 'birth': self.daemon_birth,
                            'engine_id': info.get('ID'), 'version': info.get('ServerVersion'), 'driver': info['Driver'],
                            'private_root_sha256': sha(self.spec['data_root'].encode())})

    def kill_daemon(self):
        require(self.daemon_fd is not None and self.scope_module.proc_identity(self.daemon_pid) == self.daemon_birth,
                'own daemon identity changed before fault')
        require(Path('/proc/' + str(self.daemon_pid) + '/cgroup').read_text().strip() == '0::/system.slice/' + self.spec['unit'],
                'own daemon left its unit before fault')
        signal.pidfd_send_signal(self.daemon_fd, signal.SIGKILL)
        self.events.append({'fault': 'OWN_DOCKERD_SIGKILL', 'main_pid': self.daemon_pid, 'birth': self.daemon_birth})

    def kill_supervisor(self):
        require(self.active_scope is not None and len(self.active_scope.known) == 1, 'own active supervisor identity unavailable')
        pid, birth = next(iter(self.active_scope.known.items()))
        descriptor = os.pidfd_open(pid)
        try:
            require(self.scope_module.proc_identity(pid) == birth, 'own supervisor identity changed')
            signal.pidfd_send_signal(descriptor, signal.SIGKILL)
        finally:
            os.close(descriptor)
        self.events.append({'fault': 'OWN_SUPERVISOR_SIGKILL_AFTER_CREATE', 'pid': pid, 'birth': birth})

    def stop_daemon(self):
        require(self.callers_quiescent, 'caller cleanup unproven; retain unit/data')
        self.assert_unit_file()
        self.manager('stop', self.spec['unit'], timeout=15)
        props = self.properties()
        require(props.get('MainPID') == '0' and props.get('ControlPID') == '0', 'own daemon processes did not stop')
        cgroup = Path('/sys/fs/cgroup/system.slice') / self.spec['unit']
        if cgroup.exists():
            require(not cgroup.is_symlink(), 'own unit cgroup symlink refused')
            require(all(not path.read_text().strip() for path in [cgroup/'cgroup.procs', *cgroup.glob('**/cgroup.procs')]),
                    'own daemon/containerd cgroup remains populated')
        if self.daemon_fd is not None:
            os.close(self.daemon_fd)
        self.daemon_fd = self.daemon_pid = self.daemon_birth = None
        self.events.append({'own_daemon_cgroup': 'EMPTY', 'caller_descendants': 'REAPED'})

    def snapshot(self, target):
        root = Path(target) / '.v126-target-operations'
        return {str(path.relative_to(root)): {'sha256': sha(path.read_bytes()), 'mode': stat.S_IMODE(path.stat().st_mode)}
                for path in sorted(root.rglob('*')) if path.is_file()}

    def poststate(self, spec):
        listed = self.run(docker_argv(self.spec, ['container', 'ls', '--all', '--quiet', '--no-trunc', '--filter', 'label=' + LABEL + '=' + spec['name']]))
        ids = listed.stdout.decode().splitlines()
        require(len(ids) <= 1 and all(re.fullmatch('[0-9a-f]{64}', value) for value in ids), 'duplicate or invalid actual Docker create effects')
        for container_id in ids:
            result = self.run(docker_argv(self.spec, ['container', 'inspect', container_id]))
            row = json.loads(result.stdout)[0]
            require(row['Id'] == container_id and row['Image'] == spec['image_id'] and row['Config']['Labels'] == {LABEL: spec['name']}
                    and row['State']['Status'] == 'created' and row['State']['Running'] is False
                    and row['HostConfig']['NetworkMode'] == 'none', 'actual create-only resource identity differs')
        return ids

    def exercise(self):
        for case in CASES:
            directory = self.evidence / case
            directory.mkdir(mode=0o700)
            target = directory / 'target'
            target.mkdir(mode=0o700)
            for name in ('state-a', 'state-b'):
                (directory / name).mkdir(mode=0o700)
            spec = self.spec | {'case': case, 'name': self.root.name + '-' + case, 'target': str(target),
                'run_id': 'daemon-' + case, 'state_a': str(directory/'state-a'), 'state_b': str(directory/'state-b'),
                'proxy_pid': os.getpid()}
            spec_path = directory / 'case.json'
            spec_path.write_bytes(canonical(spec)); spec_path.chmod(0o400)
            row = {'case': case, 'phase': 'PRE_DISPATCH', 'later_refusals': [], 'registry_preserved': False,
                   'target_sha256': sha(str(target).encode()), 'request_spec_sha256': sha(spec_path.read_bytes())}
            self.cases.append(row)
            self.progress(row)
            self.proxy = Proxy(self, spec, spec_path, row)
            result = self.run([sys.executable, str(SELF), '--supervise', str(spec_path)], check=False, timeout=35,
                              cwd=spec['state_a'], allow_refused=case == 'missing-result')
            proxy = self.proxy
            self.progress(row, phase='CALLER_RETURNED', caller_exit=result.returncode,
                          caller_descendants_quiescent=self.callers_quiescent)
            proxy.close(); self.proxy = None
            require(not proxy.errors and proxy.creates == 1 and len(proxy.contenders) == 1, 'proxy boundary or concurrent exclusion failed')
            require((result.returncode == 0) == (case == 'positive'), 'actual supervisor outcome differs')
            snapshot = self.snapshot(target)
            original_results = list((target / '.v126-target-operations').glob('*.result.json'))
            self.progress(row, phase='ORIGINAL_RECORDS_OBSERVED', terminal_result_present=bool(original_results),
                          original_registry_files=snapshot)
            if case == 'missing-result':
                require(not original_results, 'killed supervisor unexpectedly wrote terminal result')
            else:
                require(len(original_results) == 1, 'actual supervisor terminal result missing')
                terminal = json.loads(original_results[0].read_text())
                self.progress(row, original_terminal_result=terminal)
                require(terminal['children'] == 'REAPED' and terminal['outcome'] == ('SUCCEEDED' if case == 'positive' else 'UNKNOWN'),
                        'actual durable result classification differs')
            if case in ('positive', 'before-forward'):
                self.kill_daemon()
            self.stop_daemon()
            self.manager('reset-failed', self.spec['unit'], check=False)
            self.start_daemon()
            actual = self.poststate(spec)
            self.progress(row, phase='POST_RESTART_OBSERVED', post_restart_container_ids=actual, container_count=len(actual))
            if case == 'before-forward':
                require(not actual, 'before-forward fault created a Docker resource')
            elif case != 'dispatched-no-reply':
                require(actual == [proxy.created_id], 'actual acknowledged resource did not persist after daemon kill')
            inspection = self.run([sys.executable, str(SELF), '--inspect', str(spec_path)])
            state = json.loads(inspection.stdout)
            self.progress(row, binding_outcome=state)
            require(state['outcome'] == ('COMMAND_RESULTS_VERIFIED' if case == 'positive' else 'UNKNOWN')
                    and state['retry_allowed'] is False, 'actual binding inspection misclassified daemon outcome')
            refusals = row['later_refusals']
            variants = ('original', 'other-run') if case == 'positive' else ('original', 'retry', 'other-run', 'recovery')
            for variant in variants:
                contender = self.run([sys.executable, str(SELF), '--supervise', str(spec_path), '--variant', variant],
                                     cwd=spec['state_b'], check=False)
                reason = expected_refusal(case, variant)
                refusals.append({'variant': variant, 'exit': contender.returncode, 'expected_refusal': reason,
                                 'stderr_sha256': sha(contender.stderr)})
                self.progress(row, phase='LATER_CONTENDER_OBSERVED')
                require(contender.returncode == 75 and contender.stderr == ('DAEMON_BINDING_REFUSED reason=' + reason + '\n').encode(),
                        'same physical target refused for an unexpected reason or allowed mutation replay')
            if case != 'positive':
                retire = self.run([sys.executable, str(SELF), '--retire', str(spec_path)], check=False)
                reason = 'retirement_requires_known_completed_current_run'
                refusals.append({'variant': 'retire', 'exit': retire.returncode, 'stderr_sha256': sha(retire.stderr),
                                 'expected_refusal': reason, 'proof_scope': 'earliest UNKNOWN retirement guard; not terminal/handoff validation'})
                self.progress(row, phase='UNKNOWN_RETIREMENT_OBSERVED')
                require(retire.returncode == 75 and retire.stderr == ('DAEMON_BINDING_REFUSED reason=' + reason + '\n').encode(),
                        'unknown daemon outcome allowed retirement or refused for an unexpected reason')
            require(self.snapshot(target) == snapshot and self.poststate(spec) == actual, 'original records or actual effect changed during refusals')
            self.progress(row, phase='VERIFIED', registry_preserved=True)
            with (directory / 'observed.json').open('xb') as output:
                output.write(canonical(row))

    def cleanup(self):
        if self.proxy is not None:
            self.proxy.close()
            self.proxy = None
        require(self.callers_quiescent, 'caller lifetime unknown; retain own daemon root/unit')
        if self.unit_allocated:
            self.stop_daemon()
            self.unit_path.unlink(missing_ok=True)
            self.manager('daemon-reload')
            missing = self.manager('show', self.spec['unit'], '--property=LoadState', check=False)
            require(missing.stdout.strip() == b'LoadState=not-found', 'own daemon unit still loaded')
        shutil.rmtree(self.root)
        # Canonical targets/operation records live under evidence, never data-root.


class Guards(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-dd-', dir='/private/tmp' if sys.platform == 'darwin' else None)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / (PREFIX + 'a'*32)
        self.root.mkdir(mode=0o700)
        self.spec = {'root': str(self.root), 'unit': self.root.name+'.service', 'namespace': self.root.name,
                     'plugin_namespace': self.root.name+'-plugins',
                     'dockerd': '/actual/dockerd', 'docker': '/actual/docker', 'name': self.root.name+'-positive',
                     'image_id': 'sha256:'+'b'*64}
        for key, name in (('socket','d.sock'),('proxy_socket','p.sock'),('data_root','data'),('exec_root','exec'),
                          ('pidfile','dockerd.pid'),('config','daemon.json'),('cli_config','cli')):
            self.spec[key] = str(self.root/name)

    def body(self):
        return canonical({'Image': self.spec['image_id'], 'Cmd':['/synthetic-never-started'],
            'Labels':{LABEL:self.spec['name']}, 'HostConfig':{'NetworkMode':'none','RestartPolicy':{'Name':'no'}}})

    def test_hosted_guard_refuses_local_platform_before_allocation(self):
        with patch.object(sys, 'platform', 'darwin'), self.assertRaisesRegex(AssertionError, 'GitHub-hosted'):
            hosted_only()

    def test_hosted_guard_refuses_nonroot_actual_coordinator(self):
        env = {'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':'github-hosted','RUNNER_OS':'Linux'}
        with patch.dict(os.environ, env, clear=True), patch.object(sys,'platform','linux'), patch.object(os,'geteuid',return_value=501):
            with self.assertRaisesRegex(AssertionError, 'root coordinator'):
                hosted_only()

    def test_daemon_arguments_never_use_shared_network_or_containerd(self):
        argv = daemon_argv(self.spec)
        for flag in ('--bridge=none','--iptables=false','--ip6tables=false','--ip-forward=false','--ip-masq=false','--storage-driver=vfs'):
            self.assertIn(flag,argv)
        self.assertFalse(any(value.startswith('--containerd=') for value in argv))
        self.assertIn('--containerd-namespace='+self.root.name,argv)
        self.assertNotIn('/var/run/docker.sock',' '.join(argv))

    def test_actual_daemon_arguments_use_distinct_owned_containerd_namespaces(self):
        options=dict(value[2:].split('=',1) for value in daemon_argv(self.spec)[1:])
        self.assertNotEqual(options['containerd-namespace'],options['containerd-plugins-namespace'])
        self.assertEqual(options['containerd-namespace'],self.root.name)
        self.assertEqual(options['containerd-plugins-namespace'],self.root.name+'-plugins')
        with self.assertRaisesRegex(AssertionError,'plugin namespace'):
            daemon_argv(self.spec|{'plugin_namespace':'plugins.moby'})

    def readiness_fixture(self, observations, active_state='active'):
        fixture=Fixture.__new__(Fixture)
        fixture.spec=self.spec;fixture.tools={'dockerd':self.spec['dockerd']};fixture.events=[]
        fixture.unit_path=self.root/'own.service';fixture.assert_unit_file=Mock();fixture.manager=Mock()
        fixture.run=Mock(side_effect=[subprocess.CompletedProcess(['own-docker-info'],code,canonical(body),b'') for code,body in observations])
        fixture.properties=Mock(return_value={'FragmentPath':str(fixture.unit_path),'ControlGroup':'/system.slice/'+self.spec['unit'],
                                              'MainPID':'12345','ControlPID':'0','ActiveState':active_state,'SubState':'running'})
        fixture.scope_module=Mock();fixture.scope_module.proc_identity.return_value='synthetic-birth'
        fixture.engine_id=None;fixture.daemon_pid=fixture.daemon_fd=fixture.daemon_birth=None
        return fixture

    def complete_info(self):
        return {'DockerRootDir':self.spec['data_root'],'Driver':'vfs','ID':'synthetic-private-engine','ServerVersion':'28.0.4'}

    def observe_readiness(self, fixture):
        original_resolve=Path.resolve;original_read=Path.read_text
        def resolve(path,*args,**kwargs):
            if str(path)=='/proc/12345/exe':return Path(self.spec['dockerd'])
            return original_resolve(path,*args,**kwargs)
        def read(path,*args,**kwargs):
            if str(path)=='/proc/12345/cgroup':return '0::/system.slice/'+self.spec['unit']+'\n'
            return original_read(path,*args,**kwargs)
        connection=Mock();connection.getresponse.return_value.read.return_value=b'OK'
        with patch.object(Path,'resolve',resolve),patch.object(Path,'read_text',read),patch.object(os,'pidfd_open',return_value=123,create=True),\
                patch.dict(Fixture.start_daemon.__globals__,{'UnixConnection':Mock(return_value=connection)}),patch.object(time,'sleep'):
            fixture.start_daemon()

    def test_exit_zero_empty_server_placeholder_waits_once_then_real_identity(self):
        # CI28 returned exit0 during failed startup. These are explicit synthetic
        # observations, not an assertion that local CLI29 reproduces its status.
        for extra in ({},{'ServerErrors':['own socket not ready']}):
            with self.subTest(server_errors=bool(extra)):
                fixture=self.readiness_fixture([(0,{'DockerRootDir':'','Driver':'','ID':''}|extra),(0,self.complete_info())])
                self.observe_readiness(fixture)
                self.assertEqual(fixture.run.call_count,2)
                fixture.manager.assert_called_once_with('start',self.spec['unit'],timeout=50)
                self.assertEqual(fixture.engine_id,'synthetic-private-engine')
                self.assertEqual(len([event for event in fixture.events if event.get('daemon_started')]),1)

    def test_terminal_failed_own_unit_refuses_before_docker_info(self):
        fixture=self.readiness_fixture([(0,self.complete_info())],active_state='failed')
        with self.assertRaisesRegex(AssertionError,'terminal state'):
            self.observe_readiness(fixture)
        fixture.run.assert_not_called()

    def test_foreign_server_identity_refuses_without_wait_or_second_start(self):
        for extra in ({},{'ServerErrors':['synthetic error']}):
            with self.subTest(server_errors=bool(extra)):
                fixture=self.readiness_fixture([(0,self.complete_info()|{'DockerRootDir':'/foreign/data'}|extra)])
                with self.assertRaisesRegex(AssertionError,'root/driver differs'):
                    self.observe_readiness(fixture)
                self.assertEqual(fixture.run.call_count,1)
                fixture.manager.assert_called_once_with('start',self.spec['unit'],timeout=50)

    def test_server_errors_with_full_identity_cannot_be_ready(self):
        fixture=self.readiness_fixture([(0,self.complete_info()|{'ServerErrors':['synthetic not-ready']}),
                                        (0,self.complete_info())])
        self.observe_readiness(fixture)
        self.assertEqual(fixture.run.call_count,2)

    def test_same_data_restart_refuses_changed_engine_identity_without_wait(self):
        fixture=self.readiness_fixture([(0,self.complete_info())])
        fixture.engine_id='different-original-private-engine'
        with self.assertRaisesRegex(AssertionError,'engine identity changed across same-data restart'):
            self.observe_readiness(fixture)
        self.assertEqual(fixture.run.call_count,1)
        fixture.manager.assert_called_once_with('start',self.spec['unit'],timeout=50)
        self.assertFalse(any(event.get('daemon_started') for event in fixture.events))

    def test_daemon_readiness_deadline_is_unknown_and_does_not_repeat_start(self):
        fixture=self.readiness_fixture([(0,{'DockerRootDir':'','Driver':'','ID':''})])
        with patch.object(time,'monotonic',side_effect=[0,10,20,30,40]),self.assertRaisesRegex(AssertionError,'deadline expired; outcome UNKNOWN'):
            self.observe_readiness(fixture)
        fixture.manager.assert_called_once_with('start',self.spec['unit'],timeout=50)
        self.assertEqual(fixture.run.call_count,1)
        self.assertLessEqual(fixture.run.call_args.kwargs['timeout'],5)
        self.assertFalse(any(event.get('daemon_started') for event in fixture.events))

    def test_private_root_and_socket_reject_escape_and_symlink(self):
        with self.assertRaises(AssertionError):
            daemon_argv(self.spec | {'socket':'/var/run/docker.sock'})
        Path(self.spec['socket']).symlink_to('/var/run/docker.sock')
        with self.assertRaises(AssertionError):
            daemon_argv(self.spec)

    def test_client_cannot_override_own_socket_or_context(self):
        for argv in (['--host','unix:///var/run/docker.sock'],['--context=default'],['--config','/unrelated']):
            with self.subTest(argv=argv), self.assertRaises(AssertionError):
                docker_argv(self.spec,argv)
        argv=docker_argv(self.spec,['info'],proxy=True)
        self.assertIn('unix://'+self.spec['proxy_socket'],argv)
        self.assertEqual(create_body(self.spec)['Cmd'], ['/synthetic-never-started'])

    def test_api_allows_only_exact_synthetic_create_and_negotiation(self):
        self.assertEqual(api_plan(self.spec,'HEAD','/_ping',b''),'read-only')
        self.assertEqual(api_plan(self.spec,'GET','/v1.47/version',b''),'read-only')
        self.assertEqual(api_plan(self.spec,'POST','/v1.47/containers/create?name='+self.spec['name'],self.body()),'create')
        for method,path in [('POST','/containers/start'),('GET','http://unexpected/'),('GET','/images/json'),
                            ('POST','/containers/create?name=unrelated'),('GET','/_ping?extra=1')]:
            with self.subTest(path=path), self.assertRaises((AssertionError,ValueError)):
                api_plan(self.spec,method,path,self.body() if method=='POST' else b'')

    def test_api_refuses_changed_image_label_or_host_capability(self):
        for change in ('image','label','network','privileged'):
            body=json.loads(self.body())
            if change=='image':body['Image']='external:latest'
            elif change=='label':body['Labels']={}
            elif change=='network':body['HostConfig']['NetworkMode']='host'
            else:body['HostConfig']['Privileged']=True
            with self.subTest(change=change), self.assertRaises(AssertionError):
                api_plan(self.spec,'POST','/containers/create?name='+self.spec['name'],canonical(body))

    def test_api_refuses_every_extra_top_level_or_nested_field(self):
        for parent, key, value in ((None,'Volumes',{'/unexpected':{}}), (None,'UnknownFutureCapability',True),
                                   ('HostConfig','VolumeDriver','external'), ('HostConfig','NetworkLinks',['external']),
                                   ('HostConfig','Mounts',[]), ('HostConfig','Privileged',False),
                                   ('RestartPolicy','MaximumRetryCount',1)):
            body=json.loads(self.body())
            destination=body if parent is None else body['HostConfig'] if parent=='HostConfig' else body['HostConfig']['RestartPolicy']
            destination[key]=value
            with self.subTest(key=key),self.assertRaisesRegex(AssertionError,'exact synthetic'):
                api_plan(self.spec,'POST','/containers/create?name='+self.spec['name'],canonical(body))

    def test_proxy_tracks_and_joins_actual_registered_threads_before_socket_unlink(self):
        proxy=Proxy.__new__(Proxy)
        proxy.server=Mock(); proxy.thread=Mock(); proxy.thread.is_alive.return_value=False
        proxy.lock=threading.Lock(); proxy.workers=[Mock()]; proxy.timers=[Mock()]
        proxy.workers[0].is_alive.return_value=True
        proxy.spec=self.spec
        with patch.object(Path,'unlink') as unlink, self.assertRaisesRegex(AssertionError,'handler lifetime'):
            proxy.close()
        unlink.assert_not_called(); proxy.server.server_close.assert_not_called()
        proxy.workers[0].is_alive.return_value=False; proxy.timers[0].is_alive.return_value=True
        with patch.object(Path,'unlink') as unlink,self.assertRaisesRegex(AssertionError,'timer lifetime'):
            proxy.close()
        unlink.assert_not_called()
        proxy.timers[0].is_alive.return_value=False
        with patch.object(Path,'unlink') as unlink:
            proxy.close()
        unlink.assert_called_once_with()

    def test_in_progress_boundaries_survive_failure_before_final_case(self):
        fixture=Fixture.__new__(Fixture)
        fixture.evidence=self.root; fixture.cases=[]; fixture.events=[]; fixture.failures=[]; fixture.callers_quiescent=True
        (self.root/'positive').mkdir()
        row={'case':'positive','phase':'PRE_DISPATCH'}; fixture.cases.append(row)
        fixture.progress(row)
        row['api_events']=[{'boundary':'ACTUAL_CREATED_201','container_id':'a'*64}]
        fixture.progress(row,phase='CALLER_RETURNED',caller_exit=42)
        fixture.snapshot=Mock(return_value={'original.result.json':{'sha256':'b'*64}})
        fixture.failure_diagnostics=Mock(side_effect=OSError('diagnostic unavailable'))
        fixture.record_failure(AssertionError('actual primary refusal'))
        self.assertEqual(fixture.failures[0]['reason'],'actual primary refusal')
        self.assertEqual(fixture.cases[0]['api_events'][0]['container_id'],'a'*64)
        records=[json.loads(line) for line in (self.root/'positive'/'progress.jsonl').read_bytes().splitlines()]
        self.assertEqual(records[0]['phase'],'PRE_DISPATCH')
        self.assertEqual(records[1]['caller_exit'],42)
        self.assertIn('original.result.json',records[-1]['original_registry_files_at_failure'])
        self.assertEqual(fixture.events[-1]['secondary_diagnostic_failure'],'OSError')

    def test_diagnostic_write_failure_does_not_replace_primary(self):
        fixture=Fixture.__new__(Fixture)
        fixture.evidence=self.root; fixture.cases=[{'case':'positive'}]; fixture.events=[]; fixture.failures=[]; fixture.callers_quiescent=True
        fixture.snapshot=Mock(return_value={}); fixture.progress=Mock(side_effect=OSError('evidence full'))
        fixture.record_failure(AssertionError('first failure'))
        self.assertEqual(fixture.failures[0]['reason'],'first failure')
        self.assertEqual(fixture.events[-1]['secondary_diagnostic_failure'],'OSError')

    def test_diagnostics_redact_before_bounding_and_neutralize_runner_commands(self):
        secret='synthetic secret/long'
        with patch.dict(os.environ,{'FIXTURE_TOKEN':secret}):
            raw=(quote(secret,safe='')+' '+secret+' ::error::unsafe\n').encode()
            safe=excerpt(raw,50)
            self.assertNotIn(secret,safe);self.assertNotIn(quote(secret,safe=''),safe)
            serialized=json.dumps({'excerpt':safe}).replace('::',r'\u003a\u003a')
            self.assertNotIn('::',serialized);self.assertEqual(json.loads(serialized)['excerpt'],safe)

    def test_refusal_oracles_distinguish_actual_protected_boundaries(self):
        self.assertEqual(expected_refusal('positive','original'),'operation_already_dispatched')
        self.assertEqual(expected_refusal('missing-result','recovery'),'prior_outcome_unknown')
        self.assertEqual(expected_refusal('after-created','retry'),'prior_daemon_outcome_unknown')
        self.assertEqual(expected_refusal('missing-result','other-run'),'target_bound_to_another_run')

    def test_unix_peer_must_match_exact_owned_root_daemon(self):
        require_peer(struct.pack('3i',123,0,0),123)
        for raw in (struct.pack('3i',124,0,0),struct.pack('3i',123,501,0)):
            with self.assertRaises(AssertionError):require_peer(raw,123)

    def test_owned_unit_keeps_daemon_and_containerd_cleanup_domain(self):
        unit=unit_text(self.spec)
        self.assertIn('KillMode=control-group\n',unit)
        self.assertIn('Restart=no\n',unit)
        self.assertIn('TimeoutStopSec=5\n',unit)
        self.assertNotIn('docker.service',unit)

    def test_unknown_caller_prevents_daemon_or_record_cleanup(self):
        fixture=Fixture.__new__(Fixture)
        fixture.proxy=None; fixture.callers_quiescent=False
        fixture.stop_daemon=Mock()
        with self.assertRaisesRegex(AssertionError,'caller lifetime unknown'):
            fixture.cleanup()
        fixture.stop_daemon.assert_not_called()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument('--self-test', action='store_true')
    modes.add_argument('--require-hosted-daemon', action='store_true')
    for mode in ('supervise','docker-create','inspect','retire'):
        modes.add_argument('--'+mode, type=Path)
    parser.add_argument('--variant', choices=('original','retry','other-run','recovery'), default='original')
    parser.add_argument('--evidence-dir', type=Path)
    args = parser.parse_args()
    if args.self_test:
        return 0 if unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Guards)).wasSuccessful() else 1
    for mode in ('supervise','docker-create','inspect','retire'):
        path=getattr(args,mode.replace('-','_'))
        if path is not None:return child_mode(mode,path,args.variant)
    hosted_only()
    require(args.evidence_dir is not None,'fresh evidence directory required')
    fixture=Fixture(args.evidence_dir)
    previous={sig:signal.signal(sig,fixture.scope_module.coordinator_signal) for sig in (signal.SIGHUP,signal.SIGINT,signal.SIGTERM)}
    cleaned=False
    try:
        fixture.prepare(); fixture.exercise()
        require(sha(SELF.read_bytes())==fixture.spec['test_sha256'] and sha((SCRIPTS/'v126-cutover.sh').read_bytes())==fixture.source_sha,
                'source changed during owned daemon test')
    except BaseException as error:
        fixture.record_failure(error)
    finally:
        for sig in previous:signal.signal(sig,signal.SIG_IGN)
        try:
            fixture.cleanup(); cleaned=True
        except BaseException as error:
            fixture.failures.append(failure_record('cleanup',error))
    result={'status':'PASSED' if not fixture.failures else 'FAILED','source_sha256':fixture.source_sha,
        'test_sha256':fixture.spec['test_sha256'],'binding_sha256':fixture.spec['binding_sha256'],
        'cases':fixture.cases,'events':fixture.events,'first_failure':fixture.failures[0] if fixture.failures else None,
        'failures':fixture.failures,'cleanup_completed':cleaned,'caller_descendants_quiescent':fixture.callers_quiescent,
        'retained_runtime_root':None if cleaned else str(fixture.root),'operation_records_retained':True,
        'containers_started':0,'host_reboot':'NOT_EXECUTED','default_daemon_modified':False,'live_actions':'NONE',
        'scope':'actual private dockerd metadata persistence and shared supervisor; synthetic create-only image/API transport faults; not JVM restart or full cutover'}
    try:
        with (fixture.evidence/'result.json').open('xb') as output:
            output.write(canonical(result))
    except BaseException as error:
        fixture.failures.append(failure_record('result-evidence',error))
        result.update(status='FAILED',first_failure=fixture.failures[0],failures=fixture.failures)
    print('V126_DOCKER_DAEMON_EVIDENCE '+json.dumps(result,sort_keys=True).replace('::',r'\u003a\u003a'),flush=True)
    for sig,handler in previous.items():signal.signal(sig,handler)
    return 0 if not fixture.failures else 1


if __name__=='__main__':
    raise SystemExit(main())
