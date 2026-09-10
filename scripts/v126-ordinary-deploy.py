#!/usr/bin/env python3
"""One source-bound ordinary deployment inside the existing target supervisor.

No bootstrap, environment rewrite, database recreate, image fallback or retry is
provided. The approved descriptor must be named by a prior immutable transfer.
Protocol lock/history/result semantics come only from v126-operation-bindings.py.
"""
import argparse
import base64
import contextlib
import datetime
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import time


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'https://github.com/koteev-m/hookah_bot'
UPLOADS = {
    'docker-compose.yml': 0o644,
    'backend/Dockerfile': 0o644,
    'scripts/validate-staging-admission.sh': 0o755,
    'scripts/seed-staging.sh': 0o755,
    'scripts/check-staging-maintenance-config.sh': 0o755,
    'scripts/check-staging-image-identity.sh': 0o755,
    'docs/env/staging.env.example': 0o644,
    'docs/STAGING_DEPLOYMENT.md': 0o644,
}
SOURCES = (
    'scripts/v126-operation-bindings.py', 'scripts/v126-ordinary-deploy.py',
    'scripts/check-staging-operational-handoff.py', 'scripts/v126-database-evidence.py',
    'scripts/v126-cutover.sh', 'scripts/validate-staging-admission.sh',
    'scripts/check-staging-maintenance-config.sh', 'scripts/check-staging-image-identity.sh',
)
DESCRIPTOR_FIELDS = {
    'format_version', 'owner', 'target_sha256', 'operational_version',
    'backend_image', 'image_id', 'image_source', 'platform', 'environment_sha256',
    'compose_before_sha256', 'compose_sha256', 'caddy_config_sha256',
    'caddy_runtime_sha256', 'database_url_file', 'database_url_sha256',
    'database_identity_sha256', 'config_owner', 'restart_policy',
    'handoff_approved_and_applied', 'approval_id', 'observed_at', 'files',
    'public_url', 'public_checks',
}
MAX_BUNDLE = 4 * 1024 * 1024
MAX_CONTROL = 128 * 1024
MAX_IMAGE = 8 * 1024 * 1024 * 1024


class Refused(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise Refused(reason)


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def structured(raw):
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, 'duplicate structured field')
            result[key] = value
        return result
    value = json.loads(raw, object_pairs_hook=pairs)
    require(canonical(value) == raw, 'noncanonical structured input')
    return value


def digest(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def protected(path, mode, *, directory=False, root=True):
    info = path.lstat()
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), 'unsafe file type')
    require(info.st_uid == (0 if root else os.geteuid()) and
            (not root or info.st_gid == 0) and stat.S_IMODE(info.st_mode) == mode and
            (directory or info.st_nlink == 1), 'protected metadata differs')
    return info


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def create(path, raw, mode=0o400):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())
    sync_directory(path.parent)


def validate_descriptor(doc, target):
    require(isinstance(doc, dict) and set(doc) == DESCRIPTOR_FIELDS and
            type(doc['format_version']) is int and doc['format_version'] == 1, 'descriptor schema')
    owner = doc['owner']
    require(isinstance(owner, dict) and set(owner) == {'run_id', 'release_sha', 'script_sha256'} and
            isinstance(owner['run_id'], str) and re.fullmatch('[a-z0-9][a-z0-9._-]{5,63}', owner['run_id']) and
            isinstance(owner['release_sha'], str) and re.fullmatch('[0-9a-f]{40}', owner['release_sha']) and
            digest(owner['script_sha256']), 'descriptor owner')
    require(target.is_absolute() and str(target) == str(target.resolve(strict=True)) and
            sha(str(target).encode()) == doc['target_sha256'], 'descriptor target')
    require(doc['operational_version'] in ('V125', 'V126') and
            doc['config_owner'] == 'root:root' and doc['restart_policy'] == 'unless-stopped' and
            doc['handoff_approved_and_applied'] is True, 'approved operational handoff required')
    require(isinstance(doc['approval_id'], str) and re.fullmatch('[A-Za-z0-9][A-Za-z0-9._-]{5,127}', doc['approval_id']),
            'approval identity')
    datetime.datetime.strptime(doc['observed_at'], '%Y-%m-%dT%H:%M:%SZ')
    require(type(doc['public_checks']) is bool and isinstance(doc['public_url'], str) and
            re.fullmatch(r'https://[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?', doc['public_url']),
            'approved public endpoint')
    require(isinstance(doc['backend_image'], str) and
            re.fullmatch('[a-z0-9][a-z0-9._/-]*:[0-9a-f]{40}', doc['backend_image']) and
            doc['backend_image'].rsplit(':', 1)[1] == owner['release_sha'] and
            isinstance(doc['image_id'], str) and re.fullmatch('sha256:[0-9a-f]{64}', doc['image_id']) and
            doc['image_source'] == SOURCE and doc['platform'] == 'linux/amd64', 'exact accepted image required')
    for key in DESCRIPTOR_FIELDS:
        if key.endswith('_sha256'):
            require(digest(doc[key]), 'descriptor digest')
    require(isinstance(doc['files'], dict) and set(doc['files']) == set(UPLOADS) and
            all(digest(value) for value in doc['files'].values()) and
            doc['files']['docker-compose.yml'] == doc['compose_sha256'], 'upload inventory')
    uri = Path(doc['database_url_file'])
    require(uri.is_absolute() and str(uri.resolve(strict=True)) == str(uri), 'database authority path')
    return doc


def source_bundle(root=ROOT):
    return canonical({name: base64.b64encode((root / name).read_bytes()).decode() for name in SOURCES})


def decode_bundle(raw, expected):
    require(len(raw) <= MAX_BUNDLE and sha(raw) == expected, 'source bundle identity')
    values = structured(raw)
    require(isinstance(values, dict) and set(values) == set(SOURCES), 'source bundle inventory')
    return {name: base64.b64decode(values[name], validate=True) for name in SOURCES}


def read_exact(stream, size):
    chunks = []
    while size:
        part = stream.read(min(size, 1024 * 1024))
        require(part, 'truncated transport')
        chunks.append(part)
        size -= len(part)
    return b''.join(chunks)


def frame(stream, raw):
    stream.write(struct.pack('!Q', len(raw)))
    stream.write(raw)


def read_frame(stream, maximum):
    length = struct.unpack('!Q', read_exact(stream, 8))[0]
    require(0 < length <= maximum, 'transport frame bounds')
    return read_exact(stream, length)


def clean_environment():
    return {name: os.environ[name] for name in ('PATH', 'HOME') if name in os.environ}


def command(argv, *, cwd=None, payload=None, stdin=None, output=None, output_limit=8 * 1024 * 1024, timeout=20, pass_fds=()):
    require(payload is None or stdin is None, 'ambiguous command input')
    require(payload is None or len(payload) <= 4096, 'bounded command input')
    process = subprocess.Popen(argv, cwd=cwd, env=clean_environment(), stdin=stdin if stdin is not None else subprocess.PIPE,
                               stdout=subprocess.PIPE if output is None else output, stderr=subprocess.PIPE, start_new_session=True, pass_fds=pass_fds)
    destination = output
    initial_size = os.fstat(destination.fileno()).st_size if destination is not None else 0
    output = bytearray()
    error = bytearray()
    deadline = time.monotonic() + timeout
    try:
        if stdin is None:
            if payload:
                process.stdin.write(payload)
            process.stdin.close()
        with selectors.DefaultSelector() as selector:
            for handle, buffer in ((process.stdout, output), (process.stderr, error)):
                if handle is not None:
                    selector.register(handle, selectors.EVENT_READ, buffer)
            while selector.get_map() or process.poll() is None:
                remaining = deadline - time.monotonic()
                if destination is not None:
                    require(os.fstat(destination.fileno()).st_size - initial_size <= output_limit,
                            'bounded file consumer output exceeded')
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(argv, timeout)
                for key, _ in selector.select(min(remaining, 0.1)):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        key.fileobj.close()
                    else:
                        key.data.extend(chunk)
                        require(len(key.data) <= 4 * 1024 * 1024, 'bounded consumer output exceeded')
        process.wait(timeout=max(0.001, deadline - time.monotonic()))
        if destination is not None:
            require(os.fstat(destination.fileno()).st_size - initial_size <= output_limit,
                    'bounded file consumer output exceeded')
    except BaseException:
        # poll()/wait() may already have reaped a completed producer. Its PID no
        # longer reserves the group; the outer supervisor owns any descendants.
        if process.returncode is None:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
        for handle in (process.stdout, process.stderr):
            if handle is not None:
                handle.close()
        raise
    require(process.returncode == 0, 'bounded consumer failed; output remains private')
    return bytes(output)


def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def shell_consumer(source, body, args=(), *, cwd=None, timeout=20, pass_fds=()):
    return command(['bash', '-c', 'set -euo pipefail; source "$1"; shift; ' + body,
                    'ordinary-deploy', str(source), *map(str, args)], cwd=cwd, timeout=timeout, pass_fds=pass_fds)


def install_file(target, relative, raw):
    require(relative in UPLOADS, 'upload path not allowed')
    destination = target / relative
    for parent in reversed(destination.parents):
        if parent == target or target in parent.parents:
            if not parent.exists():
                parent.mkdir(mode=0o755)
                sync_directory(parent.parent)
            info = parent.lstat()
            require(stat.S_ISDIR(info.st_mode) and info.st_uid == info.st_gid == 0 and not info.st_mode & 0o022,
                    'upload parent metadata differs')
    if destination.exists() or destination.is_symlink():
        info = destination.lstat()
        require(stat.S_ISREG(info.st_mode) and info.st_uid == info.st_gid == 0 and
                info.st_nlink == 1 and not info.st_mode & 0o022, 'destination metadata differs')
    fd, temporary = tempfile.mkstemp(prefix='.ordinary-install-', dir=destination.parent)
    try:
        os.fchmod(fd, UPLOADS[relative])
        with os.fdopen(fd, 'wb') as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, destination)
        sync_directory(destination.parent)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temporary)
    protected(destination, UPLOADS[relative])
    require(destination.read_bytes() == raw, 'installed file differs')


class Deployment:
    def __init__(self, target, descriptor, sources):
        self.target, self.doc, self.sources = target, descriptor, sources
        self.sequencer = sources / 'scripts/v126-cutover.sh'
        self.guard = load_module(sources / 'scripts/check-staging-operational-handoff.py', 'ordinary_guard')

    def bounded(self, argv, *, deadline=None, **kwargs):
        if deadline is not None:
            remaining = deadline - time.monotonic()
            require(remaining > 0, 'readiness deadline; operation outcome UNKNOWN')
            kwargs['timeout'] = min(kwargs.get('timeout', 20), remaining)
        return command(argv, **kwargs)

    def compose(self, *args, deadline=None):
        return self.bounded(['docker', 'compose', '--env-file', str(self.target / '.env'),
                        '--file', str(self.target / 'docker-compose.yml'), *args], cwd=self.target, deadline=deadline)

    def ids(self, service, *, deadline=None):
        values = self.compose('ps', '-aq', '--no-trunc', service, deadline=deadline).decode().splitlines()
        require(len(values) == 1 and re.fullmatch('[0-9a-f]{64}', values[0]), 'unique canonical container required')
        return values[0]

    def inspected(self, cid, *, deadline=None):
        values = json.loads(self.bounded(['docker', 'inspect', cid], deadline=deadline))
        require(isinstance(values, list) and len(values) == 1 and values[0].get('Id') == cid, 'container identity')
        return values[0]

    @staticmethod
    def stable_container(value):
        return {name: value[name] for name in ('Id', 'Image', 'Config', 'HostConfig', 'RestartCount')} | {
            'started_at': value['State']['StartedAt'], 'running': value['State']['Running'],
            'networks': value['NetworkSettings']['Networks'],
        }

    def image(self):
        values = json.loads(command(['docker', 'image', 'inspect', self.doc['backend_image']]))
        require(isinstance(values, list) and len(values) == 1, 'image inventory')
        value = values[0]
        require(value['Id'] == self.doc['image_id'] and value['Os'] + '/' + value['Architecture'] == self.doc['platform'] and
                value['Config']['Labels']['org.opencontainers.image.revision'] == self.doc['owner']['release_sha'] and
                value['Config']['Labels']['org.opencontainers.image.source'] == self.doc['image_source'], 'image authority differs')

    def fixed(self, compose_hash):
        backend = self.guard.check_authority(self.target, self.doc['backend_image'])
        effective = backend.get('environment', {})
        require(effective.get('APP_ENV') == 'staging' and effective.get('TELEGRAM_TRAFFIC_POLICY') == 'PRODUCT' and
                effective.get('STAGING_MAINTENANCE_MODE', 'OFF') == 'OFF' and
                all(not effective.get(key, '') for key in ('TELEGRAM_ALLOWED_USER_IDS', 'TELEGRAM_ALLOWED_CHAT_IDS',
                    'STAGING_MAINTENANCE_ALLOWED_USER_IDS', 'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS')),
                'ordinary deploy requires applied PRODUCT/OFF authority')
        require(sha((self.target / '.env').read_bytes()) == self.doc['environment_sha256'] and
                sha((self.target / 'docker-compose.yml').read_bytes()) == compose_hash, 'fixed configuration changed')
        protected(Path(self.doc['database_url_file']), 0o600)
        require(sha(Path(self.doc['database_url_file']).read_bytes()) == self.doc['database_url_sha256'], 'database authority changed')
        command(['bash', str(self.sources / 'scripts/validate-staging-admission.sh'), '--profile', 'public-pilot',
                 '--env-file', str(self.target / '.env'), '--compose-file', str(self.target / 'docker-compose.yml')], cwd=self.target)
        command(['bash', str(self.sources / 'scripts/check-staging-maintenance-config.sh'), str(self.target / '.env')], cwd=self.target)

    def caddy(self):
        config = Path('/etc/caddy/Caddyfile')
        protected(config, 0o644)
        require(sha(config.read_bytes()) == self.doc['caddy_config_sha256'], 'Caddy disk authority changed')
        shell_consumer(self.sequencer, 'remote_assert_caddy_config_active "$1"; remote_assert_caddy_service_active', [config], cwd=self.target)
        active = command(['curl', '--disable', '--noproxy', '*', '--proto', '=http',
                          '--connect-timeout', '3', '--max-time', '10', '-fsS',
                          'http://127.0.0.1:2019/config/'])
        # The sequencer compares full semantic JSON; use the same canonical bytes
        # for the approved digest, never Caddy's formatting or service state alone.
        require(sha(canonical(json.loads(active))) == self.doc['caddy_runtime_sha256'], 'Caddy runtime authority changed')

    def database(self):
        output = command(['python3', str(self.sources / 'scripts/v126-database-evidence.py'), 'live-target',
                          self.doc['database_url_file'], self.doc['backend_image'], self.doc['database_url_sha256']],
                         cwd=self.target, timeout=90)
        require(output == ('DATABASE_TARGET_EQUALITY=' + self.doc['database_identity_sha256'] + '\n').encode(),
                'database semantic identity differs')
        sql = ("BEGIN READ ONLY; SET LOCAL statement_timeout='5s'; SET LOCAL lock_timeout='2s'; "
               "SELECT concat(max(version::integer),':',count(*) FILTER (WHERE version='126'),':',"
               "count(*) FILTER (WHERE NOT success)) FROM flyway_schema_history; ROLLBACK;")
        output = command(['docker', 'exec', '-i', self.ids('postgres'), 'sh', '-c',
                          ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; env -i PATH="$PATH" PGCONNECT_TIMEOUT=5 '
                          'psql -XqAtw -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1'], payload=sql.encode())
        require(output == (b'125:0:0\n' if self.doc['operational_version'] == 'V125' else b'126:1:0\n'),
                'operational schema differs')

    def backend(self, cid, *, allow_starting=False, fresh=True, deadline=None):
        require(self.ids('backend', deadline=deadline) == cid, 'backend replaced during observation')
        value = self.inspected(cid, deadline=deadline)
        require(value['Image'] == self.doc['image_id'] and
                value['HostConfig']['RestartPolicy']['Name'] == 'unless-stopped' and
                type(value['RestartCount']) is int and value['RestartCount'] >= 0 and
                (not fresh or value['RestartCount'] == 0) and not value['State'].get('OOMKilled') and
                value['State'].get('Status') in (('created', 'running') if allow_starting else ('running',)),
                'backend image/restart/state differs')
        labels = value['Config']['Labels']
        require(labels.get('com.docker.compose.service') == 'backend' and
                isinstance(labels.get('com.docker.compose.project'), str) and labels['com.docker.compose.project'] and
                labels.get('com.docker.compose.project.working_dir') == str(self.target) and
                labels.get('com.docker.compose.project.config_files') == str(self.target / 'docker-compose.yml'),
                'backend Compose source differs')
        global_ids = self.bounded(['docker', 'ps', '--no-trunc', '-q', '--filter',
                              'label=com.docker.compose.project=' + labels['com.docker.compose.project'],
                              '--filter', 'label=com.docker.compose.service=backend'], deadline=deadline).decode().splitlines()
        require(global_ids == ([cid] if value['State'].get('Running') else []), 'concurrent backend/poller exists')
        effective = json.loads(self.compose('config', '--format', 'json', deadline=deadline))['services']['backend']['environment']
        actual = {}
        for item in value['Config']['Env']:
            key, separator, entry = item.partition('=')
            require(separator and key not in actual, 'backend environment duplicated')
            actual[key] = entry
        require(all(actual.get(key) == str(entry) for key, entry in effective.items()) and
                actual.get('STAGING_MAINTENANCE_MODE', 'OFF') == 'OFF' and
                actual.get('TELEGRAM_TRAFFIC_POLICY') == 'PRODUCT' and
                actual.get('TELEGRAM_BOT_ENABLED') == 'true' and
                actual.get('TELEGRAM_BOT_MODE') == 'long_polling', 'backend effective environment differs')
        return value['State'].get('Running') is True

    def readiness(self, cid, seconds=120):
        deadline = time.monotonic() + seconds
        while True:
            running = self.backend(cid, allow_starting=True, deadline=deadline)
            ready = running
            for endpoint, kind in (('/health', 'health'), ('/db/health', 'health'), ('/version', 'version'), ('/miniapp/', 'static')):
                remaining = deadline - time.monotonic()
                require(remaining > 0, 'readiness deadline; operation outcome UNKNOWN')
                try:
                    raw = command(['curl', '--disable', '--connect-timeout', '2', '--max-time', str(min(3, remaining)),
                                   '-sS', '-w', '\n%{http_code}', 'http://127.0.0.1:8080' + endpoint],
                                  timeout=min(4, remaining))
                except (Refused, subprocess.TimeoutExpired):
                    ready = False
                    break
                body, separator, code = raw.rpartition(b'\n')
                require(separator, 'HTTP status unavailable')
                if code == b'503':
                    ready = False
                    break
                require(code == b'200', 'HTTP permanent refusal')
                if kind == 'health':
                    require(json.loads(body) == {'status': 'ok'}, 'health identity differs')
                elif kind == 'version':
                    value = json.loads(body)
                    require(value.get('service') == 'backend' and value.get('env') == 'staging' and
                            value.get('version') == self.doc['owner']['release_sha'], 'version identity differs')
                else:
                    require(b'<html' in body.lower(), 'Mini App response differs')
            if ready:
                self.backend(cid, deadline=deadline)
                return
            require(time.monotonic() < deadline, 'readiness deadline; operation outcome UNKNOWN')
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))

    def public(self):
        if not self.doc['public_checks']:
            return
        shell_consumer(self.sequencer,
                       'remote_assert_health_json "$1/health"; remote_assert_health_json "$1/db/health"; '
                       'curl --disable --connect-timeout 3 --max-time 10 -fsSI "$1/miniapp/" >/dev/null',
                       [self.doc['public_url']], cwd=self.target, timeout=35)


def worker(target, request_raw, bundle_raw, stream):
    require(os.geteuid() == 0, 'root deployment identity required')
    doc = validate_descriptor(structured(request_raw), target)
    decoded = decode_bundle(bundle_raw, doc['owner']['script_sha256'])
    identity = dict(doc['owner'], intent_sha256=sha(request_raw), kind='DEPLOY', name='ORDINARY_DEPLOY', action='ordinary-deploy')
    operation = sha(canonical(identity))
    # This child runs only after binding_supervise has acquired/validated the
    # target lock. Its private workspace is separate from permanent history.
    parent = target / '.v126-deploy-work'
    if not parent.exists():
        parent.mkdir(mode=0o700)
        sync_directory(target)
    protected(parent, 0o700, directory=True)
    work = parent / operation
    work.mkdir(mode=0o700)
    sync_directory(parent)
    for name, raw in decoded.items():
        file = work / 'source' / name
        file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        create(file, raw)
    request_file = work / 'approved-request.json'
    create(request_file, request_raw)
    protected(request_file, 0o400)
    deploy = Deployment(target, doc, work / 'source')
    deploy.fixed(doc['compose_before_sha256'])
    deploy.image()
    deploy.caddy()
    deploy.database()
    deploy.backend(deploy.ids('backend'), fresh=False)
    postgres = deploy.ids('postgres')
    postgres_value = deploy.inspected(postgres)
    require(postgres_value['State']['Running'] is True, 'existing PostgreSQL must already run')
    original_postgres = deploy.stable_container(postgres_value)
    payload = {}
    for name in sorted(UPLOADS):
        raw = read_frame(stream, 4 * 1024 * 1024)
        require(sha(raw) == doc['files'][name], 'uploaded source bytes differ')
        if name in decoded:
            require(raw == decoded[name], 'uploaded guard differs from bound executing source')
        payload[name] = raw
    archive_size = struct.unpack('!Q', read_exact(stream, 8))[0]
    require(0 < archive_size <= MAX_IMAGE, 'image payload bound')
    with tempfile.TemporaryFile(dir=work) as archive:
        remaining = archive_size
        while remaining:
            chunk = read_exact(stream, min(remaining, 1024 * 1024))
            archive.write(chunk)
            remaining -= len(chunk)
        require(stream.read(1) == b'', 'unexpected payload tail')
        archive.flush()
        os.fsync(archive.fileno())
        os.fchmod(archive.fileno(), 0o400)
        shell_consumer(deploy.sequencer, 'verify_saved_image_archive_fd "$1" "$2" "$3"',
                       [archive.fileno(), doc['backend_image'], doc['image_id']], timeout=180,
                       pass_fds=(archive.fileno(),))
        # Validate the future Compose from a private fixed .env before changing
        # any protected target file. Neither candidate nor descriptor includes secrets.
        candidate = work / 'candidate'
        candidate.mkdir(mode=0o700)
        create(candidate / '.env', (target / '.env').read_bytes(), 0o600)
        create(candidate / 'docker-compose.yml', payload['docker-compose.yml'], 0o644)
        deploy.guard.check_authority(candidate, doc['backend_image'])
        current_compose = json.loads(deploy.compose('config', '--format', 'json'))
        candidate_compose = json.loads(command(['docker', 'compose', '--project-name', current_compose['name'],
                                                '--env-file', str(candidate / '.env'), '--file',
                                                str(candidate / 'docker-compose.yml'), 'config', '--format', 'json'], cwd=candidate))
        require(candidate_compose['services']['postgres'] == current_compose['services']['postgres'] and
                candidate_compose.get('volumes') == current_compose.get('volumes') and
                candidate_compose.get('networks') == current_compose.get('networks'),
                'ordinary upload cannot change PostgreSQL/network/volume authority')
        for name in ('validate-staging-admission.sh', 'check-staging-maintenance-config.sh'):
            arguments = ['--profile', 'public-pilot', '--env-file', str(candidate / '.env'), '--compose-file', str(candidate / 'docker-compose.yml')] if name.startswith('validate') else [str(candidate / '.env')]
            command(['bash', str(work / 'source/scripts' / name), *arguments], cwd=candidate)
        deploy.fixed(doc['compose_before_sha256'])
        for name in sorted(payload):
            install_file(target, name, payload[name])
        deploy.fixed(doc['compose_sha256'])
        archive.seek(0)
        command(['docker', 'load'], stdin=archive, timeout=180)
    deploy.image()
    deploy.fixed(doc['compose_sha256'])
    deploy.caddy()
    # PostgreSQL is an existing authority, never an ordinary-deploy mutation.
    require(deploy.ids('postgres') == postgres and deploy.stable_container(deploy.inspected(postgres)) == original_postgres,
            'PostgreSQL changed before backend recreate')
    command(['docker', 'compose', '--env-file', str(target / '.env'), '--file', str(target / 'docker-compose.yml'),
             'up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--force-recreate', 'backend'],
            cwd=target, timeout=90)
    backend = deploy.ids('backend')
    deploy.readiness(backend)
    deploy.image()
    deploy.fixed(doc['compose_sha256'])
    deploy.database()
    deploy.caddy()
    deploy.public()
    require(deploy.ids('postgres') == postgres and deploy.stable_container(deploy.inspected(postgres)) == original_postgres,
            'PostgreSQL changed during ordinary deploy')
    deploy.backend(backend)
    proof = dict(format_version=1, identity=identity, target_sha256=doc['target_sha256'],
                 request_sha256=sha(request_raw), environment_sha256=doc['environment_sha256'],
                 compose_sha256=doc['compose_sha256'], image_id=doc['image_id'], backend_container_id=backend,
                 database_identity_sha256=doc['database_identity_sha256'], caddy_runtime_sha256=doc['caddy_runtime_sha256'],
                 result='ORDINARY_DEPLOY_COMPLETED', completed_at=datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
    encoded = canonical(proof)
    create(target / '.v126-target-operations' / (operation + '.deploy-proof.json'), encoded)
    print('ARTIFACT\tordinary-deploy\t' + sha(encoded), flush=True)
    # Payload and private source/approval evidence are intentionally retained.
    # Failed or interrupted work is never cleaned while its outcome is unknown.


WORKER_BOOTSTRAP = """import os,json,base64,sys
try:
 with os.fdopen(int(sys.argv[1]),'rb',closefd=False) as context_file:
  raw=context_file.read(8388609)
 if len(raw)>8388608:raise ValueError('context bound')
 context=json.loads(raw)
 scope={'__name__':'ordinary_worker','__file__':context['file']}
 exec(base64.b64decode(context['source']),scope)
 scope['worker'](scope['Path'](context['target']),base64.b64decode(context['request']),base64.b64decode(context['bundle']),sys.stdin.buffer)
except BaseException:
 print('ORDINARY_DEPLOY=UNKNOWN retry_allowed=false; private evidence retained',file=sys.stderr)
 raise SystemExit(75) from None
"""


def remote(target, bundle_raw, request_raw, stream):
    require(sys.platform == 'linux' and os.geteuid() == 0, 'Linux root remote identity required')
    doc = validate_descriptor(structured(request_raw), target)
    sources = decode_bundle(bundle_raw, doc['owner']['script_sha256'])
    scope = {'__name__': 'ordinary_bindings'}
    exec(compile(sources['scripts/v126-operation-bindings.py'], '<bound-operation-helper>', 'exec'), scope)
    identity = dict(doc['owner'], intent_sha256=sha(request_raw), kind='DEPLOY', name='ORDINARY_DEPLOY', action='ordinary-deploy')
    context = canonical(dict(target=str(target), file=str(target / 'scripts/v126-ordinary-deploy.py'),
                             source=base64.b64encode(sources['scripts/v126-ordinary-deploy.py']).decode(),
                             bundle=base64.b64encode(bundle_raw).decode(), request=base64.b64encode(request_raw).decode()))
    fd = os.memfd_create('ordinary-deploy-context', os.MFD_CLOEXEC)
    try:
        require(len(context) <= 8388608, 'worker context bound')
        remaining = memoryview(context)
        while remaining:
            written = os.write(fd, remaining)
            require(written > 0, 'worker context write failed')
            remaining = remaining[written:]
        os.lseek(fd, 0, os.SEEK_SET)
        return scope['binding_supervise'](target, identity, ['python3', '-c', WORKER_BOOTSTRAP, str(fd)],
                                         input_fd=stream.fileno(), env=clean_environment(), timeout=900,
                                         request_sha256=sha(request_raw), pass_fds=(fd,))
    finally:
        os.close(fd)


REMOTE_BOOTSTRAP = """import sys,struct,json,base64,hashlib
stream=open(0,'rb',buffering=0,closefd=False)
def read(n):
 result=b''
 while len(result)<n:
  part=stream.read(n-len(result))
  if not part:raise SystemExit(75)
  result+=part
 return result
def frame(limit):
 size=struct.unpack('!Q',read(8))[0]
 if not 0<size<=limit:raise SystemExit(75)
 return read(size)
bundle=frame(4194304);request=frame(131072)
doc=json.loads(request)
if hashlib.sha256(bundle).hexdigest()!=doc['owner']['script_sha256']:raise SystemExit(75)
files=json.loads(bundle)
scope={'__name__':'ordinary_remote','__file__':sys.argv[1]+'/scripts/v126-ordinary-deploy.py'}
exec(base64.b64decode(files['scripts/v126-ordinary-deploy.py'],validate=True),scope)
raise SystemExit(scope['remote'](scope['Path'](sys.argv[1]),bundle,request,stream))
"""


def client(remote_name, target, request_file, state, *, expected_image, expected_image_id, public_url, public_checks):
    require(remote_name and not remote_name.startswith('-') and not any(ord(c) < 33 for c in remote_name), 'SSH endpoint')
    protected(request_file, 0o400, root=False)
    request_raw = request_file.read_bytes()
    # Local target need not exist; remote validates canonical path under its own filesystem.
    doc = structured(request_raw)
    require(isinstance(doc, dict) and set(doc) == DESCRIPTOR_FIELDS and sha(str(target).encode()) == doc['target_sha256'], 'local descriptor target')
    require(doc['backend_image'] == expected_image and doc['image_id'] == expected_image_id and
            doc['public_url'] == public_url and doc['public_checks'] is public_checks,
            'approved deployment differs from locally checked arguments')
    bundle_raw = source_bundle()
    decode_bundle(bundle_raw, doc['owner']['script_sha256'])
    require(not state.exists() and not state.is_symlink(), 'local attempt exists; inspect outcome, never retry')
    state.mkdir(mode=0o700)
    create(state / 'request.json', request_raw)
    create(state / 'source-bundle.json', bundle_raw)
    payload = state / 'payload'
    with payload.open('xb') as output:
        os.fchmod(output.fileno(), 0o400)
        frame(output, bundle_raw)
        frame(output, request_raw)
        for name in sorted(UPLOADS):
            raw = (ROOT / name).read_bytes()
            require(sha(raw) == doc['files'][name], 'local upload hash differs')
            frame(output, raw)
        with tempfile.TemporaryFile(dir=state) as archive:
            command(['docker', 'save', doc['backend_image']], output=archive, output_limit=MAX_IMAGE, timeout=180)
            size = archive.tell()
            require(0 < size <= MAX_IMAGE, 'local image size bound')
            os.fchmod(archive.fileno(), 0o400)
            shell_consumer(ROOT / 'scripts/v126-cutover.sh', 'verify_saved_image_archive_fd "$1" "$2" "$3"',
                           [archive.fileno(), doc['backend_image'], doc['image_id']], timeout=180,
                           pass_fds=(archive.fileno(),))
            output.write(struct.pack('!Q', size))
            archive.seek(0)
            while True:
                chunk = archive.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
        output.flush()
        os.fsync(output.fileno())
    create(state / 'intent.json', canonical(dict(request_sha256=sha(request_raw), source_sha256=sha(bundle_raw),
                                                outcome='DISPATCH_MAY_OCCUR', retry_allowed=False)))
    capture = state / 'transport.log'
    with payload.open('rb') as incoming, capture.open('xb') as output:
        os.fchmod(output.fileno(), 0o400)
        argv = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=5',
                '-o', 'ServerAliveCountMax=2', remote_name,
                'python3 -c ' + shlex.quote(REMOTE_BOOTSTRAP) + ' ' + shlex.quote(str(target))]
        try:
            command(argv, stdin=incoming, output=output, timeout=930)
        except BaseException:
            raise Refused('transport outcome UNKNOWN; inspect target, retry forbidden') from None
        output.flush()
        os.fsync(output.fileno())
    identity = dict(doc['owner'], intent_sha256=sha(request_raw), kind='DEPLOY', name='ORDINARY_DEPLOY', action='ordinary-deploy')
    args = [capture, *[identity[key] for key in ('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action')]]
    log = shell_consumer(ROOT / 'scripts/v126-cutover.sh',
                         'capture="$1"; RUN_ID="$2"; RELEASE_SHA="$3"; SCRIPT_SHA256="$4"; ACTIVE_INTENT_HASH="$5"; '
                         'ACTIVE_OPERATION_KIND="$6"; ACTIVE_OPERATION_NAME="$7"; verify_remote_operation_ack "$capture" "$8"', args)
    require(len(re.findall(rb'^ARTIFACT\tordinary-deploy\t[0-9a-f]{64}$', log, re.M)) == 1, 'completed deploy proof acknowledgement missing')
    create(state / 'result.json', canonical(dict(request_sha256=sha(request_raw), transport_sha256=sha(capture.read_bytes()),
                                                outcome='ACKNOWLEDGED_COMPLETION', retry_allowed=False)))
    print('ORDINARY_DEPLOY=ACKNOWLEDGED_COMPLETION retry_allowed=false')


def retire(target, completed_request, proof_sha, handoff_file, next_request_file, authorization):
    require(authorization == 'AUTHORIZE_ORDINARY_DEPLOY_TARGET_BINDING_RETIREMENT', 'explicit retirement authorization required')
    require(os.geteuid() == 0, 'root retirement identity required')
    for path in (completed_request, handoff_file, next_request_file):
        protected(path, 0o400)
    current = validate_descriptor(structured(completed_request.read_bytes()), target)
    validate_descriptor(structured(next_request_file.read_bytes()), target)
    require(current['owner']['script_sha256'] == sha(source_bundle()) and digest(proof_sha), 'retirement source/proof identity')
    bindings = load_module(ROOT / 'scripts/v126-operation-bindings.py', 'ordinary_retirement_bindings')
    bindings.binding_retire_deploy(target, current['owner'], proof_sha, handoff_file, next_request_file)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    sub.add_parser('source-identity')
    run = sub.add_parser('client')
    for name in ('remote', 'target', 'request-file', 'state-dir', 'expected-image', 'expected-image-id', 'public-url'):
        run.add_argument('--' + name, required=True)
    run.add_argument('--public-checks', required=True, choices=('true', 'false'))
    retirement = sub.add_parser('retire')
    for name in ('target', 'completed-request-file', 'proof-sha256', 'handoff-file', 'next-request-file', 'authorization'):
        retirement.add_argument('--' + name, required=True)
    args = parser.parse_args()
    if args.mode == 'source-identity':
        print(sha(source_bundle()))
    elif args.mode == 'client':
        client(args.remote, Path(args.target), Path(args.request_file), Path(args.state_dir),
               expected_image=args.expected_image, expected_image_id=args.expected_image_id,
               public_url=args.public_url, public_checks=args.public_checks == 'true')
    else:
        retire(Path(args.target), Path(args.completed_request_file), args.proof_sha256,
               Path(args.handoff_file), Path(args.next_request_file), args.authorization)


if __name__ == '__main__':
    try:
        main()
    except (Refused, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print('ORDINARY_DEPLOY=RECONCILIATION_REQUIRED retry_allowed=false; inspect retained operation evidence', file=sys.stderr)
        raise SystemExit(75) from None
