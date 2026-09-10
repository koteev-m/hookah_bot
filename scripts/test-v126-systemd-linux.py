#!/usr/bin/env python3
"""Owned Caddy/systemd consumers on a disposable GitHub-hosted Linux runner.

Production Bash callers, partial-evidence/metadata/hash guards, install, Caddy,
systemctl and curl execute for real. The fixture adapter maps only the canonical
Caddy namespace/unit and three public HTTP paths to its own files/unit/loopback.
At the Caddy input boundary two literal replacements map the canonical site and
marker to that namespace; validation/adaptation and service run/reload share it.
Faults are explicit invalid configuration, removed input, a stopped real process,
or interruption after an actual operation, never fabricated success responses.

--self-test has no daemon/privileged/network operations. Applicable Linux runtime
requires --require-hosted-systemd; missing prerequisites fail, never silently skip.
No existing service, host reboot, production endpoint or release action is used.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import traceback
import unittest
from unittest.mock import Mock, patch
import uuid

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / 'scripts/v126-cutover.sh'
SELF = Path(__file__).resolve()
SITE = 'staging.hookahtootah.club {'
MARKER = '/etc/caddy/v126-drain.enabled'
ADMIN = 'http://127.0.0.1:2019/config/'
PREFIX = 'ht-v126-systemd-'


class CoordinatorInterrupted(BaseException):
    def __init__(self, signum):
        self.signum = signum
        super().__init__('fixture coordinator interrupted by signal ' + str(signum))


def coordinator_signal(signum, _frame):
    raise CoordinatorInterrupted(signum)


def proc_identity(pid):
    try:
        raw = Path('/proc/' + str(pid) + '/stat').read_text()
        return raw[raw.rindex(')') + 2:].split()[19]
    except (FileNotFoundError, ProcessLookupError):
        return None


def child_pids(pid):
    try:
        return {int(value) for value in Path('/proc/' + str(pid) + '/task/' + str(pid) + '/children').read_text().split()}
    except (FileNotFoundError, ProcessLookupError):
        return set()


class OwnedCommandScope:
    """Linux subreaper scope; only this coordinator's new descendants are owned.

    Individual pidfds prevent PID/group reuse races. A root descendant is signalled
    through a bounded actual sudo leaf which opens its own pidfd and verifies the
    same birth identity. External systemd jobs are a separate cleanup domain.
    """
    def __init__(self, sudo):
        require(sys.platform == 'linux' and hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal'),
                'Linux pidfd/subreaper process lifetime verification is mandatory')
        libc = ctypes.CDLL(None, use_errno=True)
        require(libc.prctl(36, 1, 0, 0, 0) == 0, 'cannot enable own child subreaper')
        self.sudo = sudo
        self.baseline = child_pids(os.getpid())
        require(not self.baseline, 'prior child lifetime is unresolved')
        self.known = {}
        self.quiescent = True
        self.cleanup_failure = None

    def inventory(self):
        pending = list(child_pids(os.getpid()) - self.baseline)
        seen = set()
        while pending:
            pid = pending.pop()
            if pid in seen:
                continue
            seen.add(pid)
            birth = proc_identity(pid)
            if birth is not None:
                require(pid not in self.known or self.known[pid] == birth, 'owned descendant PID identity changed')
                self.known[pid] = birth
                pending.extend(child_pids(pid))
        return {pid: birth for pid, birth in self.known.items() if proc_identity(pid) == birth}

    def signal_owned(self, pid, birth):
        try:
            descriptor = os.pidfd_open(pid)
        except ProcessLookupError:
            return
        try:
            if proc_identity(pid) != birth:
                return
            try:
                signal.pidfd_send_signal(descriptor, signal.SIGKILL)
                return
            except ProcessLookupError:
                return
            except PermissionError:
                pass
        finally:
            os.close(descriptor)
        # Never pass a process group or an unchecked PID to a privileged kill.
        program = r'''import os,signal,sys
signal.alarm(2)
pid=int(sys.argv[1]); expected=sys.argv[2]
try:
 fd=os.pidfd_open(pid)
except ProcessLookupError:
 raise SystemExit(0)
try:
 try:
  raw=open('/proc/'+str(pid)+'/stat').read()
 except FileNotFoundError:
  raise SystemExit(0)
 if raw[raw.rindex(')')+2:].split()[19] != expected:
  raise SystemExit(4)
 try:
  signal.pidfd_send_signal(fd,signal.SIGKILL)
 except ProcessLookupError:
  pass
finally:
 os.close(fd)
'''
        result = subprocess.run([self.sudo, '-n', sys.executable, '-c', program, str(pid), birth],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3)
        require(result.returncode == 0, 'owned privileged descendant could not be stopped')

    def reap(self):
        while True:
            try:
                pid, _status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                return
            if pid == 0:
                return

    def close(self):
        self.quiescent = False
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            inventory = self.inventory()
            for pid, birth in inventory.items():
                if time.monotonic() >= deadline:
                    break
                self.signal_owned(pid, birth)
            self.reap()
            if not self.inventory() and not child_pids(os.getpid()):
                self.quiescent = True
                return
            time.sleep(.02)
        raise AssertionError('owned command descendants are not proven quiescent; retain fixture files')

    def execute(self, argv, **kwargs):
        child = None
        self.quiescent = False
        try:
            child = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     start_new_session=True, env=kwargs['env'], cwd=kwargs['cwd'])
            birth = proc_identity(child.pid)
            if birth is not None:
                self.known[child.pid] = birth
            out, err = child.communicate(timeout=kwargs['timeout'])
            result = subprocess.CompletedProcess(argv, child.returncode, out, err)
            require(not self.inventory(), 'owned command left descendants; outcome UNKNOWN')
            self.quiescent = not child_pids(os.getpid())
            require(self.quiescent, 'owned command lifetime remains UNKNOWN')
            return result
        finally:
            original_error = sys.exc_info()[1]
            # Block repeat coordinator signals during bounded descendant cleanup.
            previous = {sig: signal.signal(sig, signal.SIG_IGN) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)}
            try:
                try:
                    self.close()
                except BaseException as error:
                    self.cleanup_failure = error
                    if original_error is None:
                        raise
                if child is not None and child.returncode is None:
                    child.returncode = -signal.SIGKILL
            finally:
                for sig, handler in previous.items():
                    signal.signal(sig, handler)


def require(value, reason):
    if not value:
        raise AssertionError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def emit(value):
    print('V126_SYSTEMD_EVIDENCE ' + json.dumps(value, sort_keys=True).replace('::', r'\u003a\u003a'), flush=True)


def regular(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'fixture regular file required')
    return path


def spec_read(path):
    path = regular(path)
    spec = json.loads(path.read_text())
    root = Path(spec['root'])
    require(root.is_absolute() and root.name.startswith(PREFIX) and root.resolve() == root,
            'fixture root is not canonical and owned')
    require(path.parent == root and path.name == 'spec.json', 'fixture specification is outside its root')
    require(path.stat().st_uid == spec['uid'] and path.stat().st_mode & 0o777 == 0o600,
            'fixture specification creator or mode differs')
    require(os.geteuid() in (0, spec['uid']), 'fixture specification belongs to another user')
    require(re.fullmatch(PREFIX + r'[0-9a-f]{32}\.service', spec['unit']), 'fixture unit identity invalid')
    require(spec['unit'] == root.name + '.service', 'fixture unit/root identity differs')
    require(all(type(spec[key]) is int and 1024 < spec[key] < 65536 for key in ('admin_port', 'http_port')),
            'fixture loopback port invalid')
    return spec


def own_path(spec, value):
    root = Path(spec['root']) / 'caddy'
    path = Path(value)
    if str(path).startswith('/etc/caddy/'):
        path = root / path.relative_to('/etc/caddy')
    require(path.is_absolute() and root in path.parents and '..' not in path.parts,
            'fixture refuses path outside its Caddy namespace')
    # The ordinary adapter cannot traverse root:root0700 evidence. Root-owned
    # descendants are checked by the real privileged leaf; the fixture coordinator
    # cannot replace them. Always verify the accessible namespace root itself.
    if root.exists() and root.stat().st_uid == 0 and os.geteuid() != 0:
        require(not root.is_symlink(), 'fixture refuses symlink namespace')
        return path
    # No symlink component may redirect a privileged leaf operation.
    for component in [path, *path.parents]:
        if component == root.parent:
            break
        require(not component.is_symlink(), 'fixture refuses symlink component')
    return path


def transformed(spec, path):
    path = regular(own_path(spec, path))
    raw = path.read_text()
    require(raw.count(SITE) == 1 and raw.count(MARKER) in (0, 1), 'fixture Caddy topology differs')
    require('http://' not in raw and 'https://' not in raw and 'reverse_proxy' not in raw,
            'fixture Caddy configuration may not route to another endpoint')
    return raw.replace(SITE, 'http://127.0.0.1:' + str(spec['http_port']) + ' {').replace(
        MARKER, str(Path(spec['root']) / 'caddy/v126-drain.enabled'))


def caddy_command(spec, verb, path, invalid=False):
    # This private transformed input is consumed by the actual Caddy binary. It
    # changes topology only; production sealed source files remain byte-identical.
    fd, name = tempfile.mkstemp(prefix='mapped-', suffix='.Caddyfile', dir=Path(spec['root']) / 'mapped')
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(('invalid_global_fixture_directive\n' if invalid else '') + transformed(spec, path))
        argv = [spec['caddy'], verb, '--config', name, '--adapter', 'caddyfile']
        if verb == 'reload':
            argv += ['--address', '127.0.0.1:' + str(spec['admin_port'])]
        if verb == 'run':
            # Make the actual Caddy process the systemd MainPID: SIGSTOP/CONT must
            # affect the daemon, not a Python parent. Cleanup removes this input.
            os.execv(spec['caddy'], argv)
        return subprocess.call(argv)
    finally:
        Path(name).unlink(missing_ok=True)


def adapter_plan(spec, kind, args, payload=None):
    """Only topology is adapted; returned commands always execute real consumers."""
    if kind == 'curl':
        admin_flags = ['--disable', '--noproxy', '*', '--proto', '=http', '--connect-timeout', '3', '--max-time', '10', '-fsS']
        base = ['--disable', '--connect-timeout', '3', '--max-time', '10']
        if args == [*admin_flags, ADMIN]:
            return [spec['curl'], *admin_flags, 'http://127.0.0.1:' + str(spec['admin_port']) + '/config/']
        for path in ('health', 'db/health', 'miniapp/'):
            endpoint = 'https://staging.hookahtootah.club/' + path
            if args == [*base, '-fsSI' if path == 'miniapp/' else '-fsS', endpoint]:
                return [spec['curl'], *base, '--noproxy', '*', args[-2],
                        'http://127.0.0.1:' + str(spec['http_port']) + '/' + path]
        if (len(args) == 11 and args[:6] == [*base, '-sS'] and args[6] == '-o'
                and args[8:] == ['-w', '%{http_code}', 'https://staging.hookahtootah.club/health']):
            output = Path(args[7])
            require(output.parent == Path(spec['root']) / 'proofs' and not output.is_symlink(),
                    'fixture drain output escaped private proof directory')
            return [spec['curl'], *args[:-1], '--noproxy', '*',
                    'http://127.0.0.1:' + str(spec['http_port']) + '/health']
        raise AssertionError('fixture refuses unexpected curl endpoint or options')
    require(kind == 'sudo', 'fixture adapter kind invalid')
    if len(args) == 3 and args[:2] == ['systemctl', 'reload'] and args[2] == 'caddy':
        return [spec['sudo'], '-n', spec['systemctl'], 'reload', spec['unit']]
    if args == ['systemctl', 'is-active', 'caddy']:
        return [spec['sudo'], '-n', spec['systemctl'], 'is-active', spec['unit']]
    if len(args) == 6 and args[:2] == ['caddy', 'validate'] and args[2] == '--config' and args[4:] == ['--adapter', 'caddyfile']:
        own_path(spec, args[3])
        return ['CADDY', 'validate', args[3]]
    if len(args) == 6 and args[:3] == ['caddy', 'adapt', '--config'] and args[4:] == ['--adapter', 'caddyfile']:
        own_path(spec, args[3])
        return ['CADDY', 'adapt', args[3]]
    allowed = ((['test', '-f'], 3), (['test', '-e'], 3), (['test', '!', '-e'], 4), (['test', '!', '-L'], 4),
               (['stat', '-c', '%a:%U:%G'], 4), (['sha256sum'], 2), (['cat'], 2), (['wc', '-l'], 3))
    for prefix, length in allowed:
        if len(args) == length and args[:-1] == prefix:
            return [spec['sudo'], '-n', *args[:-1], str(own_path(spec, args[-1]))]
    if args == ['rm', '-f', '--', MARKER]:
        return [spec['sudo'], '-n', 'rm', '-f', '--', str(own_path(spec, args[-1]))]
    if len(args) == 9 and args[:7] == ['install', '-o', 'root', '-g', 'root', '-m', '0644']:
        require(args[-1] == '/etc/caddy/Caddyfile', 'fixture install destination differs')
        return [spec['sudo'], '-n', *args[:7], str(own_path(spec, args[-2])), str(own_path(spec, args[-1]))]
    if args == ['install', '-o', 'root', '-g', 'root', '-m', '0600', '/dev/null', MARKER]:
        return [spec['sudo'], '-n', *args[:-1], str(own_path(spec, MARKER))]
    if len(args) == 4 and args[:2] == ['python3', '-']:
        require(payload is not None and sha(payload) == spec['derived_sha256'], 'fixture refuses unexpected privileged Python')
        return [spec['sudo'], '-n', sys.executable, '-', *(str(own_path(spec, value)) for value in args[2:])]
    raise AssertionError('fixture refuses unexpected privileged command')


def adapter(spec_path, kind, args):
    spec = spec_read(spec_path)
    payload = sys.stdin.buffer.read(32769) if args[:2] == ['python3', '-'] else None
    command = adapter_plan(spec, kind, args, payload)
    fault = os.environ.get('V126_SYSTEMD_FAULT', 'none')
    before_install = kind == 'sudo' and args[:1] == ['install'] and args[-1:] == ['/etc/caddy/Caddyfile']
    if command[:1] == ['CADDY']:
        # Root-owned sealed files are read by this narrowly scoped helper as root.
        command = [spec['sudo'], '-n', sys.executable, str(SELF), '--caddy-leaf', str(spec_path),
                   command[1], command[2], 'invalid' if fault == 'validate-invalid' else 'normal']
    result = subprocess.run(command, input=payload)
    if result.returncode == 0 and fault == 'install-missing-source' and args[:2] == ['caddy', 'validate']:
        subprocess.run([spec['sudo'], '-n', 'rm', '--', str(own_path(spec, args[3]))], check=True)
    after_reload = kind == 'sudo' and args == ['systemctl', 'reload', 'caddy']
    if result.returncode == 0 and ((before_install and fault == 'interrupt-after-install')
                                 or (after_reload and fault == 'interrupt-after-reload')):
        # Our direct parent is the actual source bounded-command supervisor. Signal
        # only that verified parent; never search for unrelated process identities.
        parent = os.getppid()
        parent_args = Path('/proc/' + str(parent) + '/cmdline').read_bytes()
        require(b'IO_OUTCOME=UNKNOWN reason=deadline' in parent_args,
                'fixture parent is not its bounded consumer')
        (Path(spec['root']) / 'interruption.json').write_text(json.dumps({'boundary': fault, 'parent_pid': parent}))
        os.kill(parent, signal.SIGTERM)
        time.sleep(5)
        return 99
    return result.returncode


DRIVER = r'''set -Eeuo pipefail
source "$1"
fixture_root="$2"; action="$3"; context="$4"; original_sha="$5"
remote_caddy_evidence_root() { printf '%s\n' "$fixture_root/caddy/evidence"; }
V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256=NONE
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="$original_sha"
probe() {
  case "$action" in
    restore) remote_recovery_restore_original_caddy aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa fixture-systemd ;;
    drain) remote_recovery_ensure_pre_v126_drain aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa fixture-systemd ;;
    active) remote_assert_caddy_config_active /etc/caddy/Caddyfile ;;
    service) remote_assert_caddy_service_active ;;
    *) exit 98 ;;
  esac
}
case "$context" in
  capture) captured="$(probe)" || exit "$?" ;;
  conditional) if probe; then :; else exit "$?"; fi ;;
  *) exit 98 ;;
esac
printf 'OWNED_CADDY_COMPLETE\n'
'''


def hosted_only():
    require(sys.platform == 'linux' and os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted' and os.environ.get('RUNNER_OS') == 'Linux',
            'requires explicitly authorized disposable GitHub-hosted Linux')
    require(os.geteuid() != 0, 'fixture coordinator must run as the ordinary hosted runner user')
    require(Path('/proc/1/comm').read_text().strip() == 'systemd' and Path('/run/systemd/system').is_dir(),
            'actual host systemd is required; no platform skip')
    require(Path('/sys/fs/cgroup/cgroup.controllers').is_file(), 'actual unified cgroup lifetime inspection is required')
    for key in ('DATABASE_URL', 'DB_PASSWORD', 'TELEGRAM_BOT_TOKEN', 'GH_TOKEN', 'GITHUB_TOKEN'):
        require(not os.environ.get(key), 'inherited credential input refused')


class Fixture:
    def __init__(self, evidence):
        self.evidence = evidence
        self.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.root = Path(tempfile.mkdtemp(prefix=PREFIX, dir=os.environ['RUNNER_TEMP'])).resolve()
        # Replace tempfile's suffix with a fixed unguessable unit/root identity.
        renamed = self.root.with_name(PREFIX + uuid.uuid4().hex)
        self.root.rename(renamed)
        self.root = renamed
        self.events, self.cases, self.failures = [], [], []
        self.callers_quiescent = True
        self.unit_installed = False
        self.source_sha = sha(SOURCE.read_bytes())
        self.tools = {key: shutil.which(key) for key in ('sudo', 'systemctl', 'caddy', 'curl', 'bash')}
        require(all(self.tools.values()), 'real sudo/systemctl/Caddy/curl/Bash are mandatory')
        self.admin_port, self.http_port = self.port(), self.port()
        require(self.admin_port != self.http_port, 'fixture ports collided')
        self.unit = self.root.name + '.service'
        self.unit_path = Path('/run/systemd/system') / self.unit
        self.caddy_root = self.root / 'caddy'
        self.evidence_root = self.caddy_root / 'evidence'
        self.proofs = self.root / 'proofs'
        self.proofs.mkdir(mode=0o700)
        (self.root / 'mapped').mkdir(mode=0o700)
        (self.root / 'bin').mkdir(mode=0o700)
        function = SOURCE.read_text().split('remote_assert_caddy_candidate_derived() {\n', 1)[1].split('\n}\n', 1)[0]
        derived = function.split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0] + '\n'
        self.spec = {'root': str(self.root), 'unit': self.unit, 'uid': os.geteuid(),
                     'admin_port': self.admin_port, 'http_port': self.http_port,
                     'derived_sha256': sha(derived.encode()), **self.tools}
        self.spec_path = self.root / 'spec.json'
        self.spec_path.write_text(json.dumps(self.spec))
        self.spec_path.chmod(0o600)
        for kind in ('sudo', 'curl'):
            launcher = self.root / 'bin' / kind
            launcher.write_text('#!/bin/sh\nexec ' + ' '.join(map(shlex.quote,
                [sys.executable, str(SELF), '--adapter', str(self.spec_path), kind])) + ' "$@"\n')
            launcher.chmod(0o700)
        self.env = {key: value for key, value in os.environ.items() if key.lower() not in
                    ('http_proxy', 'https_proxy', 'all_proxy', 'no_proxy')}
        self.env.update(PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'], TMPDIR=str(self.proofs),
                        HOME=str(self.root), XDG_CONFIG_HOME=str(self.root / 'config'),
                        XDG_DATA_HOME=str(self.root / 'data'))

    @staticmethod
    def port():
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            return reservation.getsockname()[1]

    def run(self, argv, *, check=True, timeout=40, env=None):
        require(self.callers_quiescent, 'prior command is not quiescent; retain fixture state')
        before = time.monotonic()
        scope = OwnedCommandScope(self.tools['sudo'])
        self.callers_quiescent = False
        try:
            result = scope.execute([str(arg) for arg in argv], env=env or self.env, cwd=self.root, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            self.events.append({'consumer': Path(argv[0]).name, 'exit': 124, 'timed_out': True,
                                'seconds': round(time.monotonic() - before, 3), 'deadline_seconds': timeout,
                                'stdout_sha256': sha(error.stdout or b''), 'stdout_bytes': len(error.stdout or b''),
                                'stderr_sha256': sha(error.stderr or b''), 'stderr_bytes': len(error.stderr or b'')})
            raise AssertionError('owned fixture command exceeded deadline; outcome UNKNOWN') from None
        finally:
            self.callers_quiescent = scope.quiescent
            if scope.cleanup_failure is not None:
                self.events.append({'command_cleanup': 'UNPROVEN', 'reason': type(scope.cleanup_failure).__name__,
                                    'files_retained': True})
        self.events.append({'consumer': Path(argv[0]).name, 'exit': result.returncode,
                            'seconds': round(time.monotonic() - before, 3), 'deadline_seconds': timeout,
                            'stdout_sha256': sha(result.stdout), 'stdout_bytes': len(result.stdout),
                            'stderr_sha256': sha(result.stderr), 'stderr_bytes': len(result.stderr)})
        if check:
            require(result.returncode == 0, 'owned consumer failed: ' + Path(argv[0]).name + ' exit ' + str(result.returncode))
        return result

    def privileged(self, *args, **kwargs):
        return self.run([self.tools['sudo'], '-n', *args], **kwargs)

    def manager(self, *args, **kwargs):
        return self.privileged(self.tools['systemctl'], *args, **kwargs)

    def write_root(self, path, content, mode='0600'):
        own_path(self.spec, str(path))
        temporary = self.root / ('input-' + uuid.uuid4().hex)
        temporary.write_bytes(content if isinstance(content, bytes) else content.encode())
        temporary.chmod(0o600)
        try:
            self.privileged('install', '-o', 'root', '-g', 'root', '-m', mode, temporary, path)
        finally:
            temporary.unlink()

    def prepare(self, *, run_lifetime_regressions=True):
        require(all(callable(signal.getsignal(sig)) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)),
                'fixture coordinator must install interruption handlers before creating its owned unit')
        self.privileged('true')
        # Reusers may omit duplicate test cases after the separate mandatory suite;
        # command lifetime enforcement remains unconditional in every run().
        if run_lifetime_regressions:
            self.process_lifetime_checks()
        for argv in ([self.tools['caddy'], 'version'], [self.tools['systemctl'], '--version']):
            version = self.run(argv)
            self.events.append({'version': version.stdout.decode().strip()})
        self.privileged('install', '-d', '-o', 'root', '-g', 'root', '-m', '0700', self.caddy_root, self.evidence_root)
        self.original = ('{\n    admin 127.0.0.1:' + str(self.admin_port) + '\n    auto_https off\n}\n' + SITE + '\n'
                         '    bind 127.0.0.1\n    respond "{\\"status\\":\\"ok\\"}" 200\n}\n')
        block = ('    @v126_staging_drain file {\n        root /\n        try_files ' + MARKER + '\n'
                 '    }\n    respond @v126_staging_drain "Service temporarily unavailable" 503\n')
        self.candidate = self.original.replace(SITE + '\n', SITE + '\n' + block)
        self.original_sha = sha(self.original.encode())
        self.reset_files()
        unit = ('[Unit]\nDescription=Owned V126 Caddy systemd fixture\n[Service]\nType=simple\nRestart=no\n'
                'KillMode=control-group\nTimeoutStartSec=35\nTimeoutStopSec=5\n'
                'Environment=HOME=' + str(self.root) + '\nEnvironment=XDG_CONFIG_HOME=' + str(self.root / 'config') + '\n'
                'Environment=XDG_DATA_HOME=' + str(self.root / 'data') + '\n'
                'ExecStart=' + sys.executable + ' ' + str(SELF) + ' --unit-run ' + str(self.spec_path) + '\n'
                'ExecReload=' + sys.executable + ' ' + str(SELF) + ' --unit-reload ' + str(self.spec_path) + '\n'
                'StandardOutput=null\nStandardError=journal\n')
        temporary = self.root / 'unit'
        temporary.write_text(unit)
        self.install_unit(temporary)
        self.manager('daemon-reload')
        self.manager('start', self.unit)
        self.wait_active()
        identity = self.manager('show', self.unit, '--property=MainPID,ControlGroup')
        properties = dict(line.split('=', 1) for line in identity.stdout.decode().splitlines())
        require(properties['ControlGroup'] == '/system.slice/' + self.unit, 'owned unit cgroup differs')
        executable = self.privileged('readlink', '/proc/' + properties['MainPID'] + '/exe')
        require(executable.stdout.decode().strip() == str(Path(self.tools['caddy']).resolve()),
                'actual Caddy is not the owned systemd MainPID')
        self.events.append({'systemd_main_executable': 'actual-caddy', 'unit_cgroup': properties['ControlGroup']})

    def install_unit(self, temporary):
        self.privileged('test', '!', '-e', self.unit_path)
        self.privileged('test', '!', '-L', self.unit_path)
        # Dispatch can install the file before its caller observes success. Record
        # the owned allocation first so interruption cannot skip unit cleanup.
        self.unit_installed = True
        self.privileged('install', '-o', 'root', '-g', 'root', '-m', '0644', temporary, self.unit_path)

    def process_lifetime_checks(self):
        # No systemd job exists yet. These real Linux processes verify the separate
        # command scope, including root descendants and setsid escape from a group.
        orphan = ('import os,time\nchild=os.fork()\n'
                  'if child==0:\n os.setsid(); os.close(1); os.close(2); time.sleep(30); os._exit(0)\n'
                  'os._exit(0)\n')
        hanging = ('import os,time\nchild=os.fork()\n'
                   'if child==0:\n os.setsid(); os.close(1); os.close(2); time.sleep(30); os._exit(0)\n'
                   'time.sleep(30)\n')
        interrupted = 'import os,signal,time\nos.kill(os.getppid(),signal.SIGTERM)\ntime.sleep(30)\n'
        cases = [('detached-after-parent-exit', [sys.executable, '-c', orphan], AssertionError),
                 ('hung-detached-descendant', [sys.executable, '-c', hanging], subprocess.TimeoutExpired),
                 ('hung-root-descendant', [self.tools['sudo'], '-n', sys.executable, '-c', hanging], subprocess.TimeoutExpired),
                 ('coordinator-term', [sys.executable, '-c', interrupted], CoordinatorInterrupted)]
        for name, command, expected in cases:
            scope = OwnedCommandScope(self.tools['sudo'])
            self.callers_quiescent = False
            refused = False
            try:
                scope.execute(command, env=self.env, cwd=self.root, timeout=.3)
            except expected:
                refused = True
            finally:
                self.callers_quiescent = scope.quiescent
            require(scope.quiescent and not child_pids(os.getpid()), 'owned process self-test left descendants: ' + name)
            require(refused, 'owned lifetime failure was accepted: ' + name)
            self.events.append({'process_case': name, 'outcome': 'EXPECTED_REFUSAL', 'descendants': 'REAPED'})

    def reset_files(self):
        for name, content in [('Caddyfile.original', self.original), ('Caddyfile.drain', self.candidate)]:
            self.write_root(self.evidence_root / name, content)
            self.write_root(self.evidence_root / (name + '.sha256'), sha(content.encode()) + '\n')
        self.write_root(self.caddy_root / 'Caddyfile', self.candidate, '0644')
        self.write_root(self.caddy_root / 'v126-drain.enabled', b'')

    def wait_active(self):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            result = self.probe('active', check=False)
            if result.returncode == 0:
                self.probe('service')
                return
            time.sleep(.1)
        raise AssertionError('owned service startup did not establish actual active configuration')

    def probe(self, action, context='capture', fault='none', check=True):
        result = self.run([self.tools['bash'], '-c', DRIVER, 'fixture', SOURCE, self.root, action, context,
                           self.original_sha], check=False, timeout=45, env=self.env | {'V126_SYSTEMD_FAULT': fault})
        if check:
            require(result.returncode == 0 and b'OWNED_CADDY_COMPLETE' in result.stdout,
                    'actual Caddy caller failed: ' + action + '/' + context)
        else:
            if result.returncode:
                require(b'OWNED_CADDY_COMPLETE' not in result.stdout, 'failed consumer emitted completion')
        require(not list(self.proofs.iterdir()), 'source caller left temporary proof files')
        return result

    def state(self, active=True):
        properties = self.manager('show', self.unit, '--property=ActiveState,SubState,MainPID,ControlPID,Result,ReloadResult', check=False)
        parsed = dict(line.split('=', 1) for line in properties.stdout.decode().splitlines() if '=' in line)
        disk = self.privileged('sha256sum', self.caddy_root / 'Caddyfile', check=False)
        marker = self.privileged('test', '-f', self.caddy_root / 'v126-drain.enabled', check=False).returncode == 0
        proof = self.probe('active', check=False) if active else None
        return {'systemd': parsed, 'systemd_exit': properties.returncode,
                'disk_sha256': disk.stdout.decode().split()[0] if disk.returncode == 0 else None,
                'marker_present': marker, 'active_equality_exit': proof.returncode if proof else None}

    def record_case(self, name, result, expected, *, active=True):
        require((result.returncode == 0) == expected, 'Caddy case outcome differs: ' + name)
        row = {'case': name, 'exit': result.returncode, 'expected_success': expected,
               'completion': b'OWNED_CADDY_COMPLETE' in result.stdout,
               'io_unknown': b'IO_OUTCOME=UNKNOWN' in result.stderr, 'post_state': self.state(active=active)}
        self.cases.append(row)
        return row

    def restart_fixture(self):
        self.manager('kill', '--kill-whom=main', '--signal=SIGCONT', self.unit, check=False)
        self.manager('stop', self.unit, check=False)
        (self.root / 'reload-invalid').unlink(missing_ok=True)
        self.reset_files()
        self.manager('reset-failed', self.unit, check=False)
        self.manager('start', self.unit)
        self.wait_active()

    def exercise(self):
        for context in ('capture', 'conditional'):
            restored = self.record_case('restore-' + context, self.probe('restore', context), True)
            require(restored['post_state']['disk_sha256'] == self.original_sha
                    and not restored['post_state']['marker_present'], 'positive restoration post-state differs')
            self.record_case('drain-' + context, self.probe('drain', context), True)
        for fault in ('validate-invalid', 'install-missing-source', 'reload-invalid'):
            if fault == 'reload-invalid':
                (self.root / fault).touch(mode=0o600)
            result = self.probe('restore', fault=fault, check=False)
            row = self.record_case(fault, result, False)
            require(row['post_state']['marker_present'], 'failure removed the drain marker')
            self.restart_fixture()
        self.write_root(self.caddy_root / 'Caddyfile', self.original, '0644')
        mismatch = self.record_case('disk-runtime-mismatch', self.probe('active', check=False), False)
        require(mismatch['post_state']['systemd']['ActiveState'] == 'active', 'mismatch did not retain real active service')
        self.restart_fixture()
        for fault in ('interrupt-after-install', 'interrupt-after-reload'):
            row = self.record_case(fault, self.probe('restore', fault=fault, check=False), False)
            require(row['io_unknown'] and row['post_state']['marker_present'], 'interrupted mutation was not retained as UNKNOWN')
            require(row['post_state']['disk_sha256'] == self.original_sha, 'actual install did not precede interruption')
            require((row['post_state']['active_equality_exit'] == 0) == (fault == 'interrupt-after-reload'),
                    'actual runtime state does not distinguish the interruption boundary')
            self.restart_fixture()
        self.manager('kill', '--kill-whom=main', '--signal=SIGSTOP', self.unit)
        before = time.monotonic()
        row = self.record_case('hung-real-admin', self.probe('active', check=False), False, active=False)
        row['elapsed_seconds'] = round(time.monotonic() - before, 3)
        require(8 <= row['elapsed_seconds'] < 15, 'actual curl admin bound differs')
        self.manager('kill', '--kill-whom=main', '--signal=SIGCONT', self.unit)
        self.restart_fixture()
        self.manager('kill', '--kill-whom=main', '--signal=SIGSTOP', self.unit)
        before = time.monotonic()
        row = self.record_case('hung-real-systemd-reload', self.probe('restore', check=False), False, active=False)
        row['elapsed_seconds'] = round(time.monotonic() - before, 3)
        require(row['io_unknown'] and 19 <= row['elapsed_seconds'] < 28, 'actual reload did not remain bounded UNKNOWN')
        require(row['post_state']['systemd']['ControlPID'] != '0', 'fixture failed to expose continuing external reload job')
        self.manager('kill', '--kill-whom=main', '--signal=SIGCONT', self.unit)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            after = self.state()
            if after['systemd']['ControlPID'] == '0' and after['active_equality_exit'] == 0:
                row['late_post_state_after_explicit_fixture_resume'] = after
                break
            time.sleep(.1)
        require('late_post_state_after_explicit_fixture_resume' in row,
                'external reload continuation after local timeout was not observed')
        require(after['marker_present'], 'late daemon completion incorrectly removed marker')
        self.manager('stop', self.unit)
        self.record_case('stopped-service', self.probe('service', check=False), False, active=False)

    def cleanup(self):
        require(self.callers_quiescent, 'caller lifetime is unproven; unit and fixture files retained')
        failures = []
        if self.unit_installed:
            self.manager('kill', '--kill-whom=all', '--signal=SIGCONT', self.unit, check=False)
            require(self.manager('stop', self.unit, check=False).returncode == 0,
                    'owned unit stop failed; unit and fixture files retained')
            state = self.manager('show', self.unit, '--property=MainPID,ControlPID', check=False)
            require(state.returncode == 0 and set(state.stdout.decode().splitlines()) == {'MainPID=0', 'ControlPID=0'},
                    'own systemd processes did not stop')
            cgroup = Path('/sys/fs/cgroup/system.slice') / self.unit
            if cgroup.exists():
                require(not cgroup.is_symlink(), 'owned unit cgroup path is a symlink')
                inventories = [cgroup / 'cgroup.procs', *cgroup.glob('**/cgroup.procs')]
                require(all(not path.read_text().strip() for path in inventories),
                        'own systemd cgroup is not empty; unit and fixture files retained')
            self.events.append({'caller_descendants': 'REAPED', 'owned_unit_cgroup': 'EMPTY',
                                'main_pid': 0, 'control_pid': 0})
            self.manager('reset-failed', self.unit, check=False)
            self.privileged('rm', '--', self.unit_path)
            self.manager('daemon-reload')
            missing = self.manager('show', self.unit, '--property=LoadState', check=False)
            require(missing.stdout.strip() == b'LoadState=not-found', 'owned unit remained loaded after cleanup')
        if self.caddy_root.exists():
            self.privileged('rm', '-rf', '--', self.caddy_root)
        # Root Caddy storage/mapped files belong exclusively to this random fixture.
        for name in ('config', 'data', 'mapped'):
            path = self.root / name
            if path.exists():
                self.privileged('rm', '-rf', '--', path)
        require(not failures, 'owned cleanup failed: ' + ', '.join(failures))
        shutil.rmtree(self.root)


class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=PREFIX)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / 'caddy').mkdir()
        self.spec = {'root': str(self.root), 'http_port': 23451, 'admin_port': 23452,
                     'sudo': '/no-real-sudo', 'systemctl': '/no-real-systemctl', 'curl': '/no-real-curl',
                     'unit': PREFIX + 'a' * 32 + '.service', 'derived_sha256': sha(b'actual source fixture')}

    def test_refuses_unrelated_privileged_commands_and_paths(self):
        for args in (['systemctl', 'restart', 'docker'], ['systemctl', 'reload', 'another'],
                     ['cat', '/etc/shadow'], ['rm', '-rf', '/'], ['python3', '-', '/etc/a', '/etc/b']):
            with self.subTest(args=args), self.assertRaises(AssertionError):
                adapter_plan(self.spec, 'sudo', args, b'wrong')

    def test_refuses_external_url_headers_and_indirection(self):
        for args in ([ADMIN], ['--config', '-'], ['--resolve', 'staging.hookahtootah.club:443:127.0.0.1'],
                     ['https://api.telegram.org/'], ['--header', 'Host: another']):
            with self.subTest(args=args), self.assertRaises(AssertionError):
                adapter_plan(self.spec, 'curl', args)

    def test_exact_systemctl_and_install_delegate_to_owned_targets(self):
        self.assertEqual(adapter_plan(self.spec, 'sudo', ['systemctl', 'reload', 'caddy']),
                         ['/no-real-sudo', '-n', '/no-real-systemctl', 'reload', self.spec['unit']])
        self.assertEqual(adapter_plan(self.spec, 'sudo', ['install', '-o', 'root', '-g', 'root', '-m', '0644',
                         '/etc/caddy/evidence/Caddyfile.original', '/etc/caddy/Caddyfile'])[-2:],
                         [str(self.root / 'caddy/evidence/Caddyfile.original'), str(self.root / 'caddy/Caddyfile')])
        self.assertEqual(adapter_plan(self.spec, 'sudo', ['test', '!', '-e', MARKER]),
                         ['/no-real-sudo', '-n', 'test', '!', '-e', str(self.root / 'caddy/v126-drain.enabled')])
        with self.assertRaises(AssertionError):
            adapter_plan(self.spec, 'sudo', ['test', '!', '-e', '/etc/unrelated'])

    def test_actual_public_and_admin_argv_map_to_only_owned_loopback(self):
        base = ['--disable', '--connect-timeout', '3', '--max-time', '10']
        for path, flag in [('health', '-fsS'), ('db/health', '-fsS'), ('miniapp/', '-fsSI')]:
            actual = adapter_plan(self.spec, 'curl', [*base, flag, 'https://staging.hookahtootah.club/' + path])
            self.assertEqual(actual[-1], 'http://127.0.0.1:23451/' + path)
            self.assertEqual(actual[0], '/no-real-curl')
        target = self.root / 'proofs/v126-public-drain.synthetic'
        argv = [*base, '-sS', '-o', str(target), '-w', '%{http_code}', 'https://staging.hookahtootah.club/health']
        actual = adapter_plan(self.spec, 'curl', argv)
        self.assertIn(str(target), actual)
        self.assertEqual(actual[-1], 'http://127.0.0.1:23451/health')
        flags = ['--disable', '--noproxy', '*', '--proto', '=http', '--connect-timeout', '3', '--max-time', '10', '-fsS']
        self.assertEqual(adapter_plan(self.spec, 'curl', [*flags, ADMIN]),
                         ['/no-real-curl', *flags, 'http://127.0.0.1:23452/config/'])

    def test_privileged_derivation_requires_exact_source_payload(self):
        argv = ['python3', '-', '/etc/caddy/evidence/Caddyfile.original', '/etc/caddy/evidence/Caddyfile.drain']
        actual = adapter_plan(self.spec, 'sudo', argv, b'actual source fixture')
        self.assertEqual(actual[-2:], [str(self.root / 'caddy/evidence/Caddyfile.original'),
                                      str(self.root / 'caddy/evidence/Caddyfile.drain')])
        with self.assertRaises(AssertionError):
            adapter_plan(self.spec, 'sudo', argv, b'actual source fixture\n')

    def test_topology_mapping_preserves_unrelated_bytes_and_refuses_symlinks(self):
        path = self.root / 'caddy/Caddyfile'
        original = '{\n admin 127.0.0.1:23452\n}\n' + SITE + '\n # synthetic comment\n respond "ok"\n}\n'
        path.write_text(original)
        self.assertEqual(transformed(self.spec, path), original.replace(SITE, 'http://127.0.0.1:23451 {'))
        path.unlink()
        path.symlink_to('/dev/null')
        with self.assertRaises(AssertionError):
            transformed(self.spec, path)

    def test_driver_keeps_actual_guards_and_bounded_source_consumers(self):
        self.assertIn('remote_recovery_restore_original_caddy', DRIVER)
        self.assertNotIn('remote_verify_partial_caddy_evidence()', DRIVER)
        self.assertNotIn('cutover_bounded_command()', DRIVER)
        source = SOURCE.read_text()
        self.assertIn('cutover_bounded_command 20 sudo systemctl reload caddy', source)
        self.assertIn('cutover_bounded_command 15 sudo install -o root -g root -m 0644', source)

    def test_unknown_caller_lifetime_prevents_unit_and_file_cleanup(self):
        fixture = Fixture.__new__(Fixture)
        fixture.callers_quiescent = False
        fixture.manager = Mock()
        with self.assertRaisesRegex(AssertionError, 'caller lifetime is unproven'):
            fixture.cleanup()
        fixture.manager.assert_not_called()

    def test_unit_install_interruption_after_effect_cannot_skip_cleanup(self):
        fixture = Fixture.__new__(Fixture)
        fixture.unit_installed = False
        fixture.unit_path = self.root / 'owned.service'
        fixture.unit = fixture.unit_path.name
        fixture.callers_quiescent = True
        fixture.events = []
        payload = self.root / 'unit-input'
        payload.write_text('owned synthetic unit bytes')
        def privileged(*argv, **kwargs):
            if argv[0] == 'test':
                self.assertFalse(fixture.unit_path.exists())
                return subprocess.CompletedProcess(argv, 0)
            self.assertEqual(argv[0], 'install')
            fixture.unit_path.write_bytes(payload.read_bytes())
            raise CoordinatorInterrupted(signal.SIGTERM)
        fixture.privileged = Mock(side_effect=privileged)
        with self.assertRaises(CoordinatorInterrupted):
            fixture.install_unit(payload)
        self.assertTrue(fixture.unit_installed)
        self.assertTrue(fixture.unit_path.exists())
        # The interrupted side effect enters actual cleanup's systemd domain.
        # An unproven stop retains its unit and referenced files.
        fixture.manager = Mock(return_value=subprocess.CompletedProcess([], 1))
        with self.assertRaisesRegex(AssertionError, 'owned unit stop failed'):
            fixture.cleanup()
        self.assertTrue(fixture.unit_path.exists())
        self.assertIn(('stop', fixture.unit), [call.args for call in fixture.manager.call_args_list])

    def test_scope_keeps_original_failure_when_cleanup_is_unproven(self):
        scope = OwnedCommandScope.__new__(OwnedCommandScope)
        scope.known, scope.cleanup_failure, scope.quiescent = {}, None, False
        scope.close = Mock(side_effect=AssertionError('cleanup remains unproven'))
        child = Mock(pid=42, returncode=None)
        original = subprocess.TimeoutExpired(['synthetic-own-command'], .1)
        child.communicate.side_effect = original
        with patch(__name__ + '.subprocess.Popen', return_value=child), patch(__name__ + '.proc_identity', return_value='birth'):
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                scope.execute(['synthetic-own-command'], env={}, cwd=self.root, timeout=.1)
        self.assertIs(raised.exception, original)
        self.assertIsInstance(scope.cleanup_failure, AssertionError)
        self.assertFalse(scope.quiescent)

    def test_coordinator_signal_reaches_finally_and_preserves_first_failure(self):
        evidence = self.root / 'evidence'
        evidence.mkdir()
        sentinel = self.root / 'retained-files'
        sentinel.write_text('owned diagnostic fixture')
        fixture = Mock(root=self.root, evidence=evidence, source_sha='a' * 64,
                       unit=PREFIX + 'a' * 32 + '.service', events=[], cases=[], failures=[], callers_quiescent=False)
        fixture.prepare.side_effect = CoordinatorInterrupted(signal.SIGTERM)
        def cleanup():
            self.assertEqual(signal.getsignal(signal.SIGTERM), signal.SIG_IGN)
            raise AssertionError('caller lifetime is unproven; retain own files')
        fixture.cleanup.side_effect = cleanup
        before = signal.getsignal(signal.SIGTERM)
        with patch.object(sys, 'argv', [str(SELF), '--require-hosted-systemd', '--evidence-dir', str(evidence)]), \
                patch(__name__ + '.hosted_only'), patch(__name__ + '.Fixture', return_value=fixture), patch(__name__ + '.emit'):
            self.assertEqual(main(), 1)
        doc = json.loads((evidence / 'result.json').read_text())
        self.assertEqual(doc['first_failure']['type'], 'CoordinatorInterrupted')
        self.assertEqual(len(doc['failures']), 2)
        self.assertEqual(doc['status'], 'FAILED_CLEANUP')
        self.assertFalse(doc['cleanup_completed'])
        self.assertTrue(sentinel.exists())
        self.assertEqual(signal.getsignal(signal.SIGTERM), before)


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--self-test', action='store_true')
    mode.add_argument('--require-hosted-systemd', action='store_true')
    parser.add_argument('--evidence-dir', type=Path)
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(AdapterTest)
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1
    hosted_only()
    require(args.evidence_dir is not None, 'fresh evidence directory required')
    fixture = Fixture(args.evidence_dir)
    original_signals = {sig: signal.signal(sig, coordinator_signal) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)}
    status = 'FAILED'
    def failure(phase, error):
        return {'phase': phase, 'type': type(error).__name__,
                'reason': str(error)[:2000] if isinstance(error, (AssertionError, CoordinatorInterrupted)) else 'non-assertion failure',
                'message_sha256': sha(str(error).encode()),
                'locations': [{'file': Path(frame.filename).name, 'line': frame.lineno, 'function': frame.name}
                              for frame in traceback.extract_tb(error.__traceback__) if Path(frame.filename) == SELF]}
    try:
        fixture.prepare()
        fixture.exercise()
        require(sha(SOURCE.read_bytes()) == fixture.source_sha, 'production source changed during fixture')
        status = 'PASSED'
    except BaseException as error:
        fixture.failures.append(failure('integration', error))
    finally:
        # A second signal cannot bypass the bounded caller/unit cleanup domain.
        for sig in original_signals:
            signal.signal(sig, signal.SIG_IGN)
        try:
            fixture.cleanup()
        except BaseException as error:
            status = 'FAILED_CLEANUP'
            fixture.failures.append(failure('cleanup', error))
    result = {'status': status, 'source_sha256': fixture.source_sha, 'unit': fixture.unit,
              'cases': fixture.cases, 'events': fixture.events, 'first_failure': fixture.failures[0] if fixture.failures else None,
              'failures': fixture.failures, 'cleanup_completed': status != 'FAILED_CLEANUP',
              'caller_descendants_quiescent': fixture.callers_quiescent,
              'retained_fixture_root': str(fixture.root) if status == 'FAILED_CLEANUP' else None,
              'live_actions': 'NONE', 'host_reboot': 'NOT_EXECUTED', 'existing_units_modified': False,
              'scope': 'actual owned Caddy/systemd consumers with explicit topology and fault adapters; not complete cutover'}
    (fixture.evidence / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    emit(result)
    for sig, handler in original_signals.items():
        signal.signal(sig, handler)
    return 0 if status == 'PASSED' else 1


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--adapter':
        raise SystemExit(adapter(Path(sys.argv[2]), sys.argv[3], sys.argv[4:]))
    if len(sys.argv) > 1 and sys.argv[1] in ('--unit-run', '--unit-reload', '--caddy-leaf'):
        spec = spec_read(sys.argv[2])
        require(os.geteuid() == 0, 'owned unit/leaf requires actual root executor')
        if sys.argv[1] == '--caddy-leaf':
            require(sys.argv[3] in ('validate', 'adapt') and sys.argv[5] in ('normal', 'invalid'), 'invalid fixture Caddy leaf')
            raise SystemExit(caddy_command(spec, sys.argv[3], sys.argv[4], invalid=sys.argv[5] == 'invalid'))
        raise SystemExit(caddy_command(spec, 'run' if sys.argv[1] == '--unit-run' else 'reload',
                                      '/etc/caddy/Caddyfile', invalid=(Path(spec['root']) / 'reload-invalid').exists()))
    raise SystemExit(main())
