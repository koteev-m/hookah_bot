#!/usr/bin/env python3
"""Server-selected, enrolled SSH entrypoint. No client-chosen code/configuration.

Installation/enrollment is a separate operational action. This entrypoint proves
its sshd provenance under the reviewed honest-kernel/sshd assumption; it is not
remote attestation and cannot constrain a malicious target root.
"""
import base64
import contextlib
import hashlib
import importlib.util
import io
import platform
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import stat
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def protected(path, *, maximum=2*1024*1024):
    path = Path(path)
    if not path.is_absolute() or path.resolve(strict=True) != path:
        raise ValueError('LAUNCHER_PROTECTED_PATH')
    for item in (path, *path.parents):
        info = item.lstat()
        if info.st_uid != 0 or (info.st_mode & 0o022 and not (stat.S_ISDIR(info.st_mode) and info.st_mode & stat.S_ISVTX)):
            raise ValueError('LAUNCHER_PROTECTED_OWNER')
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > maximum:
        raise ValueError('LAUNCHER_PROTECTED_FILE')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(fd)
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ValueError('LAUNCHER_FILE_CHANGED')
        raw = os.read(fd, maximum + 1)
        after = os.fstat(fd)
        if len(raw) != opened.st_size or (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError('LAUNCHER_FILE_CHANGED')
        return raw
    finally:
        os.close(fd)


def process(pid):
    root = Path('/proc') / str(pid)
    stat_text = (root / 'stat').read_text()
    fields = stat_text[stat_text.rfind(')') + 2:].split()
    status = (root / 'status').read_text().splitlines()
    uid_line = next(line for line in status if line.startswith('Uid:'))
    return dict(pid=pid, ppid=int(fields[1]), started_ticks=int(fields[19]),
        executable=os.readlink(root / 'exe'), uid=int(uid_line.split()[2]),
        argv=(root / 'cmdline').read_bytes().split(b'\0')[:-1])


def configuration(path):
    raw = protected(path, maximum=65536)
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('LAUNCHER_DUPLICATE_CONFIG')
            result[key] = value
        return result
    cfg = json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('LAUNCHER_CONFIG_NUMBER')))
    expected = {'version', 'target', 'anchor_sha256', 'anchor_generation', 'principal_fingerprint',
        'host_fingerprint', 'source_sha', 'source_tree', 'tooling_sha256', 'runtime', 'source_path',
        'source_sha256', 'modules', 'sshd_path', 'sshd_config_path', 'sshd_config_sha256', 'launcher_command',
        'authorized_keys_path', 'authorized_keys_sha256', 'revocation_generation'}
    prospective = type(cfg) is dict and cfg.get('version') == 2
    if prospective:
        expected |= {'epoch', 'authority_scope', 'enrollment_facts', 'server_runtime'}
    if type(cfg) is not dict or set(cfg) != expected or cfg['version'] not in (1, 2) or type(cfg['version']) is not int:
        raise ValueError('LAUNCHER_CONFIG_SCHEMA')
    for field in ('anchor_generation', 'revocation_generation'):
        if type(cfg[field]) is not int or cfg[field] < 0:
            raise ValueError('LAUNCHER_COUNTER_PIN')
    for field in ('anchor_sha256', 'tooling_sha256', 'source_sha256', 'sshd_config_sha256', 'authorized_keys_sha256'):
        if type(cfg[field]) is not str or not re.fullmatch('[0-9a-f]{64}', cfg[field]):
            raise ValueError('LAUNCHER_HASH_PIN')
    for field in ('source_sha', 'source_tree'):
        if type(cfg[field]) is not str or not re.fullmatch('[0-9a-f]{40}', cfg[field]):
            raise ValueError('LAUNCHER_SOURCE_PIN')
    for field in ('principal_fingerprint', 'host_fingerprint'):
        if type(cfg[field]) is not str or not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', cfg[field]):
            raise ValueError('LAUNCHER_KEY_PIN')
    if type(cfg['runtime']) is not str or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', cfg['runtime']):
        raise ValueError('LAUNCHER_RUNTIME_PIN')
    for field in ('target', 'source_path', 'sshd_path', 'sshd_config_path', 'authorized_keys_path'):
        if type(cfg[field]) is not str or not Path(cfg[field]).is_absolute() or str(Path(cfg[field]).resolve(strict=True)) != cfg[field]:
            raise ValueError('LAUNCHER_PATH_PIN')
    if type(cfg['launcher_command']) is not str or not 0 < len(cfg['launcher_command']) <= 4096:
        raise ValueError('LAUNCHER_COMMAND_PIN')
    if type(cfg['revocation_generation']) is not int or cfg['revocation_generation'] < 0:
        raise ValueError('LAUNCHER_REVOCATION_PIN')
    if type(cfg['modules']) is not dict or not 3 <= len(cfg['modules']) <= 64:
        raise ValueError('LAUNCHER_MODULES')
    source = Path(cfg['source_path'])
    if source.parent != ROOT or source.name != 'v126-cutover.sh':
        raise ValueError('LAUNCHER_INSTALLED_SOURCE')
    required = {'v126-cutover.sh', 'v126-operation-bindings.py', 'v126-policy-b-transport.py',
                'v126-policy-b-launcher.py', 'v126-policy-b-dispatch.py',
                'v126-policy-b-client.py', 'v126-policy-b-authority.py', 'v126-legacy-genesis.py'}
    if prospective:
        required.add('v126-authority-epoch.py')
    installed = {path.name for path in ROOT.glob('v126-*') if path.is_file() or path.is_symlink()}
    if not required <= cfg['modules'].keys() or set(cfg['modules']) != installed:
        raise ValueError('LAUNCHER_MODULE_INVENTORY')
    for name, checksum in cfg['modules'].items():
        if '/' in name or not name.startswith('v126-') or name in ('.', '..'):
            raise ValueError('LAUNCHER_MODULE_NAME')
        if hashlib.sha256(protected(ROOT / name)).hexdigest() != checksum:
            raise ValueError('LAUNCHER_MODULE_DRIFT')
    for path_key, hash_key in (('authorized_keys_path', 'authorized_keys_sha256'),
                               ('sshd_config_path', 'sshd_config_sha256')):
        if hashlib.sha256(protected(cfg[path_key], maximum=65536)).hexdigest() != cfg[hash_key]:
            raise ValueError('LAUNCHER_ENROLLED_SOURCE_CHANGED')
    if cfg['modules']['v126-cutover.sh'] != cfg['source_sha256']:
        raise ValueError('LAUNCHER_SOURCE_PIN')
    if prospective:
        validate_prospective_configuration(cfg)
    return cfg


def validate_prospective_configuration(cfg):
    # Root-owned installed config is the server's enrollment authority. These
    # facts must also be independently pinned and acquired on V for each action.
    epoch = load('v126_launcher_epoch', ROOT / 'v126-authority-epoch.py')
    epoch.check(cfg['epoch'], {k: 'sha' for k in ('epoch_id', 'domain_identity_sha256', 'predecessor_index_sha256')})
    facts = cfg['enrollment_facts']
    epoch.check(facts, epoch.ENROLLMENT)
    if (cfg['authority_scope'] not in ('BOOTSTRAP_ONLY', 'FULL_DR')
            or any(facts[k] != cfg['epoch'][k] for k in ('epoch_id', 'domain_identity_sha256'))
            or any(facts[k] != cfg[k] for k in ('principal_fingerprint', 'host_fingerprint'))
            or facts['source'] != dict(commit=cfg['source_sha'], tree=cfg['source_tree'], tooling_sha256=cfg['tooling_sha256'])
            or not facts['excluded_principal_fingerprints']
            or cfg['principal_fingerprint'] in facts['excluded_principal_fingerprints']
            or cfg['runtime'] != '3.12.3' or platform.python_version() != '3.12.3'
            or facts['python_version'] != '3.12.3'
            or facts['endpoint']['host'] == '178.20.209.5'
            or facts['endpoint']['host'] == facts['endpoint']['peer']):
        raise ValueError('LAUNCHER_PROSPECTIVE_ENROLLMENT')
    runtime = cfg['server_runtime']
    epoch.check(runtime, epoch.RUNTIME)
    executable = str(Path(sys.executable).resolve(strict=True))
    if (epoch.digest(runtime) != facts['server_runtime_sha256'] or runtime['role'] != 'S'
            or runtime['executable_path'] != executable
            or hashlib.sha256(protected(executable, maximum=64*1024*1024)).hexdigest() != runtime['executable_sha256']):
        raise ValueError('LAUNCHER_PROSPECTIVE_RUNTIME_IDENTITY')


def authenticate(cfg, config_path):
    if sys.platform != 'linux' or os.getuid() != os.geteuid():
        raise ValueError('LAUNCHER_LINUX_SESSION_REQUIRED')
    if os.environ.get('SSH_TTY') or os.environ.get('SSH_AUTH_SOCK') or os.environ.get('SSH_ORIGINAL_COMMAND'):
        raise ValueError('LAUNCHER_UNEXPECTED_CLIENT_CHANNEL')
    connection = os.environ.get('SSH_CONNECTION', '').split()
    if len(connection) != 4 or not all(part.isdigit() for part in (connection[1], connection[3])):
        raise ValueError('LAUNCHER_SSH_CONNECTION_REQUIRED')
    if cfg.get('version') == 2:
        endpoint = cfg['enrollment_facts']['endpoint']
        if (connection[0] != endpoint['peer'] or connection[2] != endpoint['host']
                or int(connection[3]) != endpoint['port']
                or pwd.getpwuid(os.getuid()).pw_name != endpoint['user']):
            raise ValueError('LAUNCHER_PROSPECTIVE_PEER_OR_ENDPOINT')
    parent = process(os.getppid())
    executable = str(Path(cfg['sshd_path']).resolve(strict=True))
    protected(executable, maximum=16*1024*1024)
    if parent['executable'] != executable or parent['uid'] not in (0, os.geteuid()):
        raise ValueError('LAUNCHER_NOT_SERVER_SELECTED')
    # Find the same daemon's master, whose startup argv selects the enrolled
    # configuration. Client environment cannot manufacture this process lineage.
    current, master = parent, None
    for _ in range(8):
        args = [part for item in current['argv'] for part in shlex.split(item.decode('utf-8', 'strict'))]
        if '-f' in args and args.index('-f') + 1 < len(args):
            if args[args.index('-f') + 1] == cfg['sshd_config_path']:
                master = current
                break
        if current['ppid'] <= 1:
            break
        candidate = process(current['ppid'])
        if candidate['executable'] != executable or candidate['uid'] != 0:
            break
        current = candidate
    if master is None:
        raise ValueError('LAUNCHER_DAEMON_ORIGIN_UNPROVEN')
    server_config = protected(cfg['sshd_config_path'], maximum=65536)
    if hashlib.sha256(server_config).hexdigest() != cfg['sshd_config_sha256']:
        raise ValueError('LAUNCHER_DAEMON_CONFIG_CHANGED')
    if any(line.strip().lower().startswith('include ') for line in server_config.decode().splitlines()):
        raise ValueError('LAUNCHER_UNBOUND_SSHD_INCLUDE')
    # SSH_USER_AUTH is account-owned, so it is only a corroborating session
    # record. Identity originates in the independently enrolled root-owned
    # single-key authorization surface, never in that mutable file alone.
    authorized_path = Path(cfg['authorized_keys_path'])
    authorized = protected(authorized_path, maximum=8192)
    if (stat.S_IMODE(authorized_path.stat().st_mode) not in (0o400, 0o600)
            or hashlib.sha256(authorized).hexdigest() != cfg['authorized_keys_sha256']):
        raise ValueError('LAUNCHER_AUTHORIZED_KEYS_PIN')
    lines = authorized.decode('ascii').splitlines()
    key_fields = lines[0].split() if len(lines) == 1 else []
    if (len(key_fields) not in (2, 3) or not key_fields[0].startswith(('ssh-', 'ecdsa-'))
            or '-cert-' in key_fields[0]):
        raise ValueError('LAUNCHER_EXCLUSIVE_AUTHORIZED_KEY')
    if cfg['version'] == 2 and key_fields[0] != 'ssh-ed25519':
        raise ValueError('LAUNCHER_PROSPECTIVE_PRINCIPAL_TYPE')
    authorized_key = base64.b64decode(key_fields[1], validate=True)
    authorized_fingerprint = 'SHA256:' + base64.b64encode(hashlib.sha256(authorized_key).digest()).decode().rstrip('=')
    if authorized_fingerprint != cfg['principal_fingerprint']:
        raise ValueError('LAUNCHER_AUTHORIZED_PRINCIPAL')
    user = pwd.getpwuid(os.getuid()).pw_name
    checked = subprocess.run([executable, '-T', '-f', cfg['sshd_config_path'], '-C',
        'user=' + user + ',host=' + connection[2] + ',addr=' + connection[0]],
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin'}, timeout=10, check=True)
    if len(checked.stdout) > 65536:
        raise ValueError('LAUNCHER_DAEMON_CONFIG_SIZE')
    effective = {}
    for line in checked.stdout.decode().splitlines():
        key, _, value = line.partition(' ')
        effective.setdefault(key, []).append(value)
    required = {'forcecommand': cfg['launcher_command'], 'exposeauthinfo': 'yes',
        'disableforwarding': 'yes', 'permittty': 'no', 'permituserenvironment': 'no',
        'permituserrc': 'no', 'x11forwarding': 'no', 'allowagentforwarding': 'no',
        'allowtcpforwarding': 'no', 'allowstreamlocalforwarding': 'no', 'permittunnel': 'no',
        'passwordauthentication': 'no', 'kbdinteractiveauthentication': 'no',
        'authenticationmethods': 'publickey', 'pubkeyauthentication': 'yes',
        'authorizedkeysfile': str(authorized_path), 'authorizedkeyscommand': 'none',
        'trustedusercakeys': 'none', 'authorizedprincipalsfile': 'none',
        'authorizedprincipalscommand': 'none', 'hostbasedauthentication': 'no'}
    if cfg.get('version') == 2:
        endpoint = cfg['enrollment_facts']['endpoint']
        required.update(port=str(endpoint['port']),
                        listenaddress=endpoint['host'] + ':' + str(endpoint['port']),
                        allowusers=endpoint['user'] + '@' + endpoint['peer'])
    if any(effective.get(key) != [value] for key, value in required.items()) or effective.get('acceptenv'):
        raise ValueError('LAUNCHER_SSHD_SESSION_POLICY')
    expected_command = 'exec ' + shlex.join([sys.executable, str(Path(__file__).resolve()), '--config', str(config_path)])
    if cfg['launcher_command'] != expected_command:
        raise ValueError('LAUNCHER_ENTRYPOINT_BINDING')
    auth_path = Path(os.environ.get('SSH_USER_AUTH', ''))
    if not auth_path.is_absolute() or auth_path.resolve(strict=True) != auth_path or not auth_path.name.startswith('sshauth.'):
        raise ValueError('LAUNCHER_AUTHINFO_ORIGIN')
    info = auth_path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_nlink != 1 or not 0 < info.st_size <= 8192):
        raise ValueError('LAUNCHER_AUTHINFO_PROTECTION')
    boot_time = next(float(line.split()[1]) for line in Path('/proc/stat').read_text().splitlines() if line.startswith('btime '))
    parent_started = boot_time + parent['started_ticks'] / os.sysconf('SC_CLK_TCK')
    if info.st_ctime < parent_started - 1 or info.st_mtime > time.time() + 1:
        raise ValueError('LAUNCHER_AUTHINFO_LIFETIME')
    fd = os.open(auth_path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(fd)
        if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
            raise ValueError('LAUNCHER_AUTHINFO_CHANGED')
        authentication = os.read(fd, 8193).decode('ascii').splitlines()
    finally:
        os.close(fd)
    if len(authentication) != 1:
        raise ValueError('LAUNCHER_AUTH_METHOD')
    method = authentication[0].split()
    if len(method) != 3 or method[0] != 'publickey':
        raise ValueError('LAUNCHER_AUTH_METHOD')
    key = base64.b64decode(method[2], validate=True)
    fingerprint = 'SHA256:' + base64.b64encode(hashlib.sha256(key).digest()).decode().rstrip('=')
    if fingerprint != cfg['principal_fingerprint']:
        raise ValueError('LAUNCHER_PRINCIPAL_MISMATCH')
    principal = {key: cfg[key] for key in ('principal_fingerprint', 'host_fingerprint', 'anchor_sha256', 'anchor_generation', 'revocation_generation')}
    if cfg.get('version') == 2:
        principal.update(epoch=cfg['epoch'], authority_scope=cfg['authority_scope'])
    return principal


def main():
    if len(sys.argv) != 3 or sys.argv[1] != '--config':
        raise ValueError('LAUNCHER_SERVER_CONFIG_REQUIRED')
    config_path = Path(sys.argv[2])
    if not config_path.is_absolute() or config_path.resolve(strict=True) != config_path:
        raise ValueError('LAUNCHER_CONFIG_CANONICAL')
    cfg = configuration(config_path)
    principal = authenticate(cfg, config_path)
    transport = load('v126_authenticated_transport', ROOT / 'v126-policy-b-transport.py')
    channel = transport.FramedChannel(os.dup(0), os.dup(1))
    def verify_source():
        if configuration(config_path) != cfg:
            raise ValueError('LAUNCHER_ENROLLMENT_CHANGED')
    session = transport.receive_session(channel, principal, verify_source)
    wanted = {'target': cfg['target'], 'source_tree': cfg['source_tree'], 'tooling_sha256': cfg['tooling_sha256'], 'runtime': cfg['runtime']}
    if (any(session.request[key] != value for key, value in wanted.items())
            or session.request['identity']['release_sha'] != cfg['source_sha']
            or session.request['identity']['script_sha256'] != cfg['source_sha256']
            or session.source != protected(cfg['source_path'])):
        raise ValueError('LAUNCHER_APPROVED_SOURCE_OR_TARGET')
    # Only independently enrolled installed source is evaluated. Client SOURCE is
    # checked for equality; it cannot supply a different Python program.
    genesis = transport.is_genesis(session.request['identity'])
    if genesis:
        # Genesis has no leaf or private output spool before first admission.
        # Extract only the fixed enrolled heredocs; do not execute a shell whose
        # heredoc implementation may create temporary files before EARLY.
        bindings = load('v126_launcher_bindings', ROOT / 'v126-operation-bindings.py')
        prefix = b"remote_operation_python() {\n  remote_operation_bindings_python || { printf 'binding source unavailable\\n' >&2; return 75; }\n"
        if session.source.count(prefix) != 1:
            raise ValueError('LAUNCHER_GENESIS_SOURCE_SHAPE')
        plain = session.source.replace(prefix, b"remote_operation_python() {\n", 1)
        extracted = (bindings.binding_embedded_source(session.source, 'remote_operation_bindings_python')
                     + bindings.binding_embedded_source(plain, 'remote_operation_python'))
    else:
        extracted = subprocess.run(['/bin/bash', '-c', 'source "$1"; remote_operation_python',
            'policy-b-source-extract', cfg['source_path']], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin'}, timeout=10, check=True).stdout
    if not 0 < len(extracted) <= transport.MAX_SOURCE:
        raise ValueError('LAUNCHER_EXTRACTED_SOURCE_SIZE')
    # Keep raw operation logs/ACK away from the duplex control stream. The
    # reviewed caller emits only explicit framed messages via POLICY_B_LAUNCH.
    with (MetadataOutput() if genesis else tempfile_output()) as output:
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            scope = dict(__name__='__main__', POLICY_B_LAUNCH=session,
                         POLICY_B_TRANSPORT=transport, POLICY_B_SERVER_ROOT=str(ROOT),
                         POLICY_B_OUTPUT_CAPTURE=output)
            exec(compile(extracted, '<enrolled-v126-remote-operation>', 'exec'), scope)


class MetadataOutput(io.StringIO):
    """Bounded diagnostics only; metadata operations never emit leaf output."""
    def __init__(self):
        super().__init__()
        self.byte_count = 0
    def write(self, value):
        self.byte_count += len(value.encode('utf-8'))
        if self.byte_count > 64 * 1024:
            raise ValueError('LAUNCHER_METADATA_OUTPUT_BOUND')
        return super().write(value)


@contextlib.contextmanager
def tempfile_output():
    import tempfile
    with tempfile.TemporaryFile() as raw:
        output = io.TextIOWrapper(raw, encoding='utf-8', write_through=True)
        try:
            yield output
        finally:
            output.flush()
            output.detach()


if __name__ == '__main__':
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        # No raw sshd metadata, environment, source, evidence or provider errors.
        print('POLICY_B_AUTHENTICATED_OPERATION_REFUSED', file=sys.stderr)
        raise SystemExit(75)
