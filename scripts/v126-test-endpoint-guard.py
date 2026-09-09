#!/usr/bin/env python3
"""Latch unexpected gh/ssh/curl endpoint calls during isolated release tests.

--run NEW_EVIDENCE_DIR -- COMMAND installs child-only PATH wrappers. Fixture mocks
may prepend their own PATH as before. Dependencies/image builds are outside this
wrapper's scope. This is an accidental-escape guard, not an OS network sandbox:
absolute binaries, deliberate PATH removal, other clients and remote shell children
are not intercepted. Tests must still own their loopback listeners/resources.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from urllib.parse import urlsplit

EXIT_REFUSED = 97
LIMIT = 65536
CURL_FLAGS = {'disable', 'silent', 'show-error', 'fail', 'head', 'include', 'insecure',
              'suppress-connect-headers', 'globoff'}
CURL_VALUES = {'url', 'output', 'dump-header', 'write-out', 'request', 'header',
               'connect-timeout', 'max-time', 'max-filesize', 'noproxy', 'proto',
               'proto-redir', 'cacert', 'data', 'data-raw', 'data-binary'}
CURL_SHORT = {'q': 'disable', 's': 'silent', 'S': 'show-error', 'f': 'fail',
              'I': 'head', 'i': 'include', 'k': 'insecure', 'g': 'globoff',
              'o': 'output', 'D': 'dump-header', 'w': 'write-out', 'X': 'request',
              'H': 'header', 'd': 'data', 'K': 'config'}


class Refused(ValueError):
    pass


def require(value, reason):
    if not value:
        raise Refused(reason)


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def write_new(path, payload, mode=0o400):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(payload)
        handle.flush()
        os.fsync(handle.fileno())


def regular_bytes(path, *, limit=LIMIT, private=False):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as handle:
        meta = os.fstat(handle.fileno())
        require(stat.S_ISREG(meta.st_mode) and meta.st_uid == os.getuid()
                and meta.st_nlink == 1 and not meta.st_mode & 0o022,
                'indirect_input_metadata')
        if private:
            require(not meta.st_mode & 0o077, 'private_identity_metadata')
        require(meta.st_size <= limit, 'indirect_input_too_large')
        result = handle.read(limit + 1)
        require(len(result) <= limit, 'indirect_input_too_large')
        return result


def loopback_url(value):
    require(not any(ord(ch) < 32 or ord(ch) == 127 for ch in value)
            and '\\' not in value and '{' not in value and '}' not in value,
            'url_control_or_expansion')
    try:
        parsed = urlsplit(value)
        port = parsed.port
        host = parsed.hostname
    except ValueError:
        raise Refused('url_malformed') from None
    require(parsed.scheme in ('http', 'https') and host in ('127.0.0.1', '::1')
            and parsed.username is None and parsed.password is None
            and port is not None and 1 <= port <= 65535 and not parsed.fragment,
            'url_not_explicit_literal_loopback')
    return value


def curl_config(payload):
    require(b'\x00' not in payload and len(payload) <= LIMIT, 'curl_config_invalid')
    try:
        text = payload.decode('utf-8')
    except UnicodeError:
        raise Refused('curl_config_encoding') from None
    tokens = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        found = re.fullmatch(r'(?:--)?([a-z][a-z0-9-]*)(?:\s*(?:=|:)\s*|\s+)?(.*)', line)
        require(found is not None, 'curl_config_syntax')
        name, value = found.groups()
        require(name in CURL_FLAGS | CURL_VALUES, 'curl_config_option_refused')
        if name in CURL_FLAGS:
            require(not value, 'curl_config_flag_value')
            tokens.append('--' + name)
            continue
        require(bool(value), 'curl_config_value_missing')
        if value.startswith('"'):
            # A narrow unambiguous config subset. Flatten to argv, never reopen the
            # original config or give its grammar/indirections to the real consumer.
            try:
                value = json.loads(value)
            except ValueError:
                raise Refused('curl_config_quoted_value') from None
            require(isinstance(value, str), 'curl_config_quoted_value')
        else:
            require(not any(ch.isspace() for ch in value) and not value.startswith("'"),
                    'curl_config_unquoted_value')
        tokens.extend(['--' + name, value])
    return tokens


def curl_args(argv, stdin=None):
    if argv in (['--version'], ['-V']):
        return ['--disable', '--version']
    pending = list(argv)
    normalized, urls = [], []
    index, configurations = 0, 0
    while index < len(pending):
        token = pending[index]
        index += 1
        if token == '--':
            for value in pending[index:]:
                normalized.append(loopback_url(value)); urls.append(value)
            break
        if not token.startswith('-'):
            normalized.append(loopback_url(token)); urls.append(token)
            continue
        name, value = None, None
        if token.startswith('--'):
            name, separator, value = token[2:].partition('=')
            if not separator:
                value = None
        else:
            require(len(token) > 1, 'curl_option_refused')
            cluster = token[1:]
            while cluster:
                short, cluster = cluster[0], cluster[1:]
                require(short in CURL_SHORT, 'curl_short_option_refused')
                name = CURL_SHORT[short]
                if name in CURL_FLAGS:
                    normalized.append('--' + name)
                    name = None
                else:
                    value = cluster or None
                    break
            if name is None:
                continue
        require(name in CURL_FLAGS | CURL_VALUES | {'config'}, 'curl_option_refused')
        if name in CURL_FLAGS:
            require(value is None, 'curl_flag_value')
            normalized.append('--' + name)
            continue
        if value is None:
            require(index < len(pending), 'curl_option_value_missing')
            value = pending[index]; index += 1
        if name == 'write-out':
            # Production readiness separates the body/status with a literal newline.
            # Permit only bounded output text and the one status placeholder used by
            # these consumers; curl's %output{file} and other directives stay refused.
            require(len(value) <= 4096
                    and not any((ord(ch) < 32 and ch not in '\n\r\t') or ord(ch) == 127 for ch in value)
                    and '%' not in re.sub(r'%%|%\{http_code\}', '', value), 'curl_write_out_format_refused')
        else:
            require(not any(ord(ch) < 32 or ord(ch) == 127 for ch in value), 'curl_value_control')
        if name == 'config':
            configurations += 1
            require(configurations == 1, 'curl_multiple_or_nested_config')
            payload = stdin.read(LIMIT + 1) if value == '-' else regular_bytes(value)
            pending[index:index] = curl_config(payload)
            continue
        if name == 'url':
            loopback_url(value); urls.append(value)
        elif name in ('connect-timeout', 'max-time'):
            require(re.fullmatch(r'(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)', value)
                    and 0 < float(value) <= 120, 'curl_timeout_not_bounded')
        elif name == 'max-filesize':
            require(value.isdigit() and 0 < int(value) <= 16777216, 'curl_size_not_bounded')
        elif name == 'noproxy':
            require(value == '*', 'curl_proxy_override')
        elif name in ('proto', 'proto-redir'):
            require(value.startswith('=') and set(value[1:].split(',')) <= {'http', 'https'}
                    and bool(value[1:]), 'curl_protocol_override')
        elif name == 'request':
            require(value in ('GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'), 'curl_request_refused')
        elif name in ('data', 'data-binary', 'data-raw', 'header', 'write-out'):
            require(not value.startswith('@'), 'curl_file_indirection_refused')
            if name == 'header' and value.lower().startswith('host:'):
                require(value.split(':', 1)[1].strip() in ('127.0.0.1', '[::1]'), 'curl_host_override')
        normalized.extend(['--' + name, value])
    require(urls, 'curl_url_missing')
    return ['--disable', '--proxy', '', '--noproxy', '*', '--globoff', '--proto', '=http,https',
            '--proto-redir', '=http,https', '--connect-timeout', '3', '--max-time', '20', *normalized]


def ssh_args(argv):
    if argv == ['-V']:
        return argv
    allowed = {'batchmode': 'yes', 'identitiesonly': 'yes', 'stricthostkeychecking': 'yes'}
    options, identity, port, config = {}, None, None, None
    index = 0
    while index < len(argv) and argv[index].startswith('-'):
        value = argv[index]; index += 1
        if value in ('-T', '-q', '-4', '-6'):
            continue
        require(value[:2] in ('-F', '-i', '-p', '-o'), 'ssh_option_refused')
        name, argument = value[:2], value[2:]
        if not argument:
            require(index < len(argv), 'ssh_option_value_missing')
            argument = argv[index]; index += 1
        require(not any(ord(ch) < 32 or ord(ch) == 127 for ch in argument), 'ssh_option_control')
        if name == '-F':
            require(config is None and argument == '/dev/null', 'ssh_config_refused')
            config = argument
        elif name == '-i':
            require(identity is None, 'ssh_duplicate_identity')
            identity = Path(argument)
        elif name == '-p':
            require(port is None and argument.isdigit() and 1024 <= int(argument) <= 65535,
                    'ssh_port_not_explicit_test_port')
            port = int(argument)
        else:
            key, separator, content = argument.partition('=')
            key = key.lower()
            require(separator and key not in options, 'ssh_option_syntax_or_duplicate')
            require(key in {*allowed, 'userknownhostsfile', 'connecttimeout'}, 'ssh_option_refused')
            options[key] = content
    require(index < len(argv), 'ssh_destination_missing')
    destination = argv[index]
    require(index + 1 < len(argv) and not argv[index + 1].startswith('-'), 'ssh_remote_command_required')
    host = destination
    if '@' in host:
        user, host = host.split('@', 1)
        require(re.fullmatch('[A-Za-z_][A-Za-z0-9_-]*', user), 'ssh_user_refused')
    require(host in ('127.0.0.1', '::1'), 'ssh_destination_not_literal_loopback')
    require(config == '/dev/null' and port is not None and identity is not None
            and identity.is_absolute(), 'ssh_requires_private_explicit_endpoint')
    require(all(options.get(key, '').lower() == value for key, value in allowed.items()),
            'ssh_identity_or_hostkey_policy')
    timeout = options.get('connecttimeout', '')
    require(timeout.isdigit() and 0 < int(timeout) <= 10, 'ssh_timeout_not_bounded')
    known = Path(options.get('userknownhostsfile', ''))
    require(known.is_absolute() and known.parent == identity.parent
            and identity.parent.resolve() == identity.parent
            and identity.parent.is_relative_to(Path(tempfile.gettempdir()).resolve()),
            'ssh_identity_not_in_owned_fixture')
    parent = identity.parent.stat()
    require(parent.st_uid == os.getuid() and stat.S_IMODE(parent.st_mode) == 0o700,
            'ssh_fixture_directory_metadata')
    regular_bytes(identity, private=True)
    regular_bytes(known)
    return ['-F', '/dev/null', '-oProxyCommand=none', '-oProxyJump=none',
            '-oPermitLocalCommand=no', '-oCanonicalizeHostname=no', '-oIdentityAgent=none',
            '-oForwardAgent=no', '-oClearAllForwardings=yes', '-oControlMaster=no',
            '-oControlPath=none', *argv[:index], '--', *argv[index:]]


def stripped_environment(environment):
    result = dict(environment)
    for key in list(result):
        if key.upper() in {'GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN',
                           'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'FTP_PROXY', 'NO_PROXY',
                           'CURL_HOME', 'SSLKEYLOGFILE', 'SSH_AUTH_SOCK'}:
            result.pop(key, None)
    return result


def refusal_event(directory, creator_uid, event):
    # The immutable launcher supplies this path and UID independently of config
    # readability. A sudo child may record a refusal, but may not run real tools.
    directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        metadata = os.fstat(directory_fd)
        require(metadata.st_uid == creator_uid and stat.S_IMODE(metadata.st_mode) == 0o700,
                'guard_latch_metadata')
        fd = os.open(uuid.uuid4().hex + '.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                     0o400, dir_fd=directory_fd)
        with os.fdopen(fd, 'wb') as handle:
            handle.write(canonical(event))
            handle.flush()
            if os.fstat(handle.fileno()).st_uid != creator_uid:
                require(os.geteuid() == 0, 'guard_latch_owner')
                os.fchown(handle.fileno(), creator_uid, -1)
            os.fsync(handle.fileno())
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def worker(config_path, violations, creator_uid, tool, argv):
    try:
        require(os.getuid() == creator_uid and os.geteuid() == creator_uid,
                'guard_worker_uid_mismatch')
        config = json.loads(regular_bytes(config_path))
        require(config['violations'] == violations, 'guard_latch_binding')
        require(tool in ('gh', 'curl', 'ssh'), 'unknown_guarded_tool')
        require(tool != 'gh', 'gh_is_not_a_test_endpoint')
        arguments = curl_args(argv, sys.stdin.buffer) if tool == 'curl' else ssh_args(argv)
        os.execve(config['tools'][tool], [config['tools'][tool], *arguments], stripped_environment(os.environ))
    except (Refused, OSError, ValueError, AttributeError, KeyError, TypeError) as error:
        reason = str(error) if isinstance(error, Refused) else 'endpoint_input_unavailable_or_invalid'
        tool_name = tool if tool in ('gh', 'curl', 'ssh') else 'unknown'
        event = {'tool': tool_name, 'decision': 'REFUSED', 'reason': reason, 'time_ns': time.time_ns()}
        refusal_event(violations, creator_uid, event)
        print('V126_TEST_ENDPOINT_REFUSED tool=' + tool_name + ' reason=' + reason, file=sys.stderr)
        return EXIT_REFUSED


def install(root, tools):
    root = root.absolute()
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    (root / 'bin').mkdir(mode=0o700)
    (root / 'violations').mkdir(mode=0o700)
    code = root / 'guard.py'
    write_new(code, Path(__file__).read_bytes(), 0o500)
    config = root / 'config.json'
    write_new(config, canonical({'tools': tools, 'violations': str(root / 'violations')}))
    for tool in ('gh', 'ssh', 'curl'):
        launcher = '#!' + sys.executable + '\nimport os,sys\nos.execv(' + repr(sys.executable) + ', ['
        launcher += repr(sys.executable) + ',' + repr(str(code)) + ",'--worker'," + repr(str(config))
        launcher += ',' + repr(str(root / 'violations')) + ',' + repr(str(os.getuid())) + ',' + repr(tool)
        launcher += '] + sys.argv[1:])\n'
        write_new(root / 'bin' / tool, launcher.encode(), 0o500)
    return root, {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in [code, config, *(root / 'bin').iterdir()]}


def wrapped_run(root, argv, *, tools=None, environment=None):
    environment = stripped_environment(os.environ if environment is None else environment)
    if tools is None:
        tools = {name: shutil.which(name) for name in ('curl', 'ssh')}
        require(all(tools.values()), 'real_curl_and_ssh_are_required')
        tools = {name: str(Path(path).resolve(strict=True)) for name, path in tools.items()}
    root, fingerprints = install(Path(root), tools)
    environment['PATH'] = str(root / 'bin') + os.pathsep + environment.get('PATH', os.defpath)
    process = subprocess.run(argv, env=environment)
    invalid = False
    try:
        require((root / 'violations').is_dir() and not (root / 'violations').is_symlink(), 'guard_latch_missing')
        for name, digest in fingerprints.items():
            require(hashlib.sha256(regular_bytes(root / name)).hexdigest() == digest, 'guard_source_changed')
        violations = []
        for path in sorted((root / 'violations').iterdir()):
            value = json.loads(regular_bytes(path))
            require(canonical(value) == path.read_bytes() and value.get('decision') == 'REFUSED', 'guard_latch_invalid')
            violations.append(value)
    except (OSError, Refused, ValueError):
        invalid, violations = True, []
    result = {'child_exit': process.returncode, 'unexpected_calls': len(violations),
              'status': 'INVALID_EVIDENCE' if invalid else ('UNEXPECTED_ENDPOINT' if violations else 'NO_UNEXPECTED_ENDPOINT'),
              'guard_sha256': fingerprints['guard.py']}
    write_new(root / 'result.json', canonical(result))
    return EXIT_REFUSED if invalid or violations else process.returncode


class SelfTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-endpoint-guard-test-')
        self.root = Path(self.temp.name).resolve()
        self.calls = self.root / 'calls.jsonl'
        tool = self.root / 'fake-real-tool'
        tool.write_text('#!' + sys.executable + '\nimport json,os,sys\nwith open(' + repr(str(self.calls)) +
                        ',"a") as out: out.write(json.dumps({"argv":sys.argv[1:],"token":os.getenv("GH_TOKEN"),"proxy":os.getenv("HTTP_PROXY")})+"\\n")\n')
        tool.chmod(0o700)
        self.tools = {'ssh': str(tool), 'curl': str(tool)}
        self.environ = dict(os.environ, GH_TOKEN='SYNTHETIC_MUST_NOT_LEAK', GITHUB_TOKEN='SYNTHETIC_MUST_NOT_LEAK',
                            HTTP_PROXY='http://external.invalid:8080')

    def tearDown(self):
        self.temp.cleanup()

    def child(self, code, *args):
        directory = self.root / ('run-' + uuid.uuid4().hex)
        status = wrapped_run(directory, [sys.executable, '-c', code, *map(str, args)],
                             tools=self.tools, environment=self.environ)
        result = json.loads((directory / 'result.json').read_text())
        if (directory / 'violations').exists():
            for path in (directory / 'violations').iterdir():
                self.assertNotIn('SYNTHETIC_MUST_NOT_LEAK', path.read_text())
        return status, result, directory

    def command(self, argv):
        return self.child('import subprocess,sys; subprocess.run(sys.argv[1:],check=False)', *argv)

    def test_gh_latch_survives_expected_child_failure_and_env_i(self):
        for argv in [['gh', 'api', 'SYNTHETIC_MUST_NOT_LEAK'],
                     ['env', '-i', 'PATH={PATH}', 'gh', '--version']]:
            status, result, _ = self.child('import os,subprocess,sys; subprocess.run([x.replace("{PATH}",os.environ["PATH"]) for x in sys.argv[1:]],check=False)', *argv)
            self.assertEqual((status, result['child_exit'], result['unexpected_calls']), (97, 0, 1))
        self.assertFalse(self.calls.exists())

    def test_root_or_foreign_uid_refusal_is_latched_without_config_read(self):
        code = '''import os,pathlib,runpy
from unittest.mock import patch
root=pathlib.Path(os.environ['PATH'].split(os.pathsep)[0]).parent
scope=runpy.run_path(str(root/'guard.py'))
creator=os.getuid()
foreign=0 if creator != 0 else 65534
with patch.object(scope['os'],'getuid',return_value=foreign):
    scope['worker'](str(root/'config.json'),str(root/'violations'),creator,'gh',['synthetic'])
'''
        status, result, directory = self.child(code)
        self.assertEqual((status, result['child_exit'], result['unexpected_calls']), (97, 0, 1))
        event = json.loads(next((directory / 'violations').iterdir()).read_text())
        self.assertEqual(event['reason'], 'guard_worker_uid_mismatch')
        self.assertFalse(self.calls.exists())

    def test_actual_sudo_root_refusal_on_owned_github_hosted_linux(self):
        if not (sys.platform.startswith('linux') and os.environ.get('GITHUB_ACTIONS') == 'true'
                and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
                and os.environ.get('RUNNER_OS') == 'Linux'):
            self.skipTest('actual sudo UID transition requires the owned GitHub-hosted Linux runner')
        self.assertNotEqual(os.getuid(), 0, 'the outer guard must belong to the runner user')
        sudo = shutil.which('sudo')
        self.assertIsNotNone(sudo)
        code = '''import os,subprocess,sys
subprocess.run([sys.argv[1],'-n','env','PATH='+os.environ['PATH'],sys.executable,'-c',
    'import subprocess; subprocess.run(["gh","synthetic-refusal"],check=False)'],check=False)
'''
        status, result, directory = self.child(code, sudo)
        self.assertEqual((status, result['child_exit'], result['unexpected_calls']), (97, 0, 1))
        event = next((directory / 'violations').iterdir())
        self.assertEqual(event.stat().st_uid, os.getuid())
        self.assertEqual(stat.S_IMODE(event.stat().st_mode), 0o400)
        self.assertEqual(json.loads(event.read_text())['reason'], 'guard_worker_uid_mismatch')
        self.assertFalse(self.calls.exists())

    def test_initial_config_errors_are_latched_through_direct_cli(self):
        for mode in ('absent', 'invalid-json', 'symlink'):
            code = '''import os,pathlib,subprocess,sys
root=pathlib.Path(os.environ['PATH'].split(os.pathsep)[0]).parent
config=root/'invalid-config'
mode=sys.argv[1]
if mode == 'invalid-json':
    config.write_text('{');config.chmod(0o400)
elif mode == 'symlink':
    config.symlink_to(root/'config.json')
subprocess.run([sys.executable,str(root/'guard.py'),'--worker',str(config),str(root/'violations'),str(os.getuid()),'gh','synthetic'],check=False)
'''
            status, result, _ = self.child(code, mode)
            self.assertEqual((status, result['child_exit'], result['unexpected_calls']), (97, 0, 1), mode)
        self.assertFalse(self.calls.exists())

    def test_loopback_curl_and_sanitized_environment(self):
        for url in ['http://127.0.0.1:8080/health', 'https://[::1]:4443/health']:
            self.assertEqual(self.command(['curl', '-fsSI', '--max-time', '.2', url])[0], 0)
        for row in map(json.loads, self.calls.read_text().splitlines()):
            self.assertEqual(row['argv'][0], '--disable')
            self.assertIsNone(row['token']); self.assertIsNone(row['proxy'])

    def test_readiness_write_out_literal_newline_preserves_real_argv(self):
        argv = ['curl', '--disable', '--silent', '--noproxy', '*', '--proto', '=http',
                '--connect-timeout', '2', '--max-time', '5.0', '--max-filesize', '16384',
                '--write-out', '\n%{http_code}', 'http://127.0.0.1:8080/health']
        self.assertEqual(self.command(argv)[0], 0)
        actual = json.loads(self.calls.read_text())['argv']
        self.assertEqual(actual[-len(argv) + 1:], argv[1:])
        config = self.root / 'write-out.conf'
        config.write_text('url="http://127.0.0.1:8080/health"\nwrite-out="\\n%{http_code}"\n')
        config.chmod(0o600)
        self.assertEqual(self.command(['curl', '--config', str(config)])[0], 0)

    def test_write_out_exception_cannot_change_routing_or_write_files(self):
        for value in ['\x1b%{http_code}', 'a' * 4097, '@private-file', '%output{/tmp/forbidden}',
                      '%{url_effective}', '%{stderr}', '%{http_code}%output{forbidden}']:
            self.assertEqual(self.command(['curl', '--write-out', value, 'http://127.0.0.1:8080/x'])[0], 97)
        for argv in [['--url', 'http://127.0.0.1:8080/\nx'], ['--header', 'X-Test: x\ny']]:
            self.assertEqual(self.command(['curl', *argv, 'http://127.0.0.1:8080/x'])[0], 97)
        self.assertFalse(self.calls.exists())

    def test_curl_external_expansion_and_routing_options_latch(self):
        cases = [['https://external.invalid/SYNTHETIC_MUST_NOT_LEAK'], ['http://localhost:8080/x'],
                 ['http://127.0.0.1/x'], ['file:///etc/passwd'],
                 ['-L', 'http://127.0.0.1:8080/x'], ['--resolve', 'x:443:1.2.3.4'],
                 ['--connect-to', '::external.invalid:'], ['--proxy', 'http://external.invalid:8'],
                 ['--url-query', 'x=1'], ['--expand-url', '{{endpoint}}'], ['--config', '-']]
        for args in cases:
            self.assertEqual(self.command(['curl', *args])[0], 97, args)
        self.assertFalse(self.calls.exists())

    def test_curl_config_flattened_and_stdin_supported(self):
        config = self.root / 'curl.conf'
        config.write_text('silent\nshow-error\nurl = "http://127.0.0.1:8080/health"\n'); config.chmod(0o600)
        self.assertEqual(self.command(['curl', '--config', str(config), '--output', '/dev/null'])[0], 0)
        self.assertEqual(self.child('import subprocess; subprocess.run(["curl","--config","-"],input=b\'url="http://127.0.0.1:8080/health"\\n\')')[0], 0)
        for row in map(json.loads, self.calls.read_text().splitlines()):
            self.assertNotIn('--config', row['argv']); self.assertNotIn(str(config), row['argv'])

    def test_curl_nested_external_and_symlink_configs_refuse(self):
        config = self.root / 'curl.conf'
        for body in ['config="other.conf"\n', 'url="https://api.telegram.org/botSYNTHETIC_MUST_NOT_LEAK/getMe"\n',
                     'url="http://127.0.0.1:8080/x"\nproxy="http://external.invalid:8"\n']:
            config.write_text(body); config.chmod(0o600)
            self.assertEqual(self.command(['curl', '-K' + str(config)])[0], 97)
        link = self.root / 'linked.conf'; link.symlink_to(config)
        self.assertEqual(self.command(['curl', '--config', str(link)])[0], 97)
        self.assertFalse(self.calls.exists())

    def ssh(self):
        directory = self.root / 'ssh'; directory.mkdir(mode=0o700, exist_ok=True)
        key, known = directory / 'key', directory / 'known'
        for path in (key, known):
            path.write_text('synthetic'); path.chmod(0o600)
        return ['ssh', '-F', '/dev/null', '-T', '-p', '32123', '-i', str(key),
                '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'UserKnownHostsFile=' + str(known), '-o', 'ConnectTimeout=3', '127.0.0.1', 'synthetic-command']

    def test_ssh_explicit_private_loopback_is_preserved(self):
        argv = self.ssh()
        self.assertEqual(self.command(argv)[0], 0)
        actual = json.loads(self.calls.read_text())['argv']
        self.assertIn('-oIdentityAgent=none', actual)
        self.assertEqual(actual[-len(argv):], [*argv[1:-2], '--', *argv[-2:]])

    def test_ssh_proxy_forward_config_and_external_refuse(self):
        for extra in [['-J', 'outside'], ['-oProxyCommand=touch /tmp/forbidden'], ['-o', 'LocalCommand=bad'],
                      ['-o', 'HostName=outside'], ['-L', '8080:outside:80'], ['-F', '/tmp/config']]:
            argv = self.ssh(); argv[1:1] = extra
            self.assertEqual(self.command(argv)[0], 97)
        argv = self.ssh(); argv[-1] = '-oProxyCommand=bad'
        self.assertEqual(self.command(argv)[0], 97)
        argv = self.ssh(); argv[-2] = 'staging.invalid'
        self.assertEqual(self.command(argv)[0], 97)
        self.assertFalse(self.calls.exists())

    def test_fixture_path_mocks_remain_in_front(self):
        mock = self.root / 'mock'; mock.mkdir()
        marker = self.root / 'mock-used'
        (mock / 'curl').write_text('#!/bin/sh\nprintf used > ' + str(marker) + '\n'); (mock / 'curl').chmod(0o700)
        status, result, _ = self.child('import os,subprocess,sys; os.environ["PATH"]=sys.argv[1]+os.pathsep+os.environ["PATH"]; subprocess.run(["curl","https://fixture.invalid"])', mock)
        self.assertEqual((status, result['unexpected_calls']), (0, 0)); self.assertTrue(marker.exists())
        self.assertFalse(self.calls.exists())

    def test_latch_removal_is_invalid_evidence(self):
        status, result, _ = self.child('import os,pathlib; (pathlib.Path(os.environ["PATH"].split(os.pathsep)[0]).parent/"violations").rmdir()')
        self.assertEqual((status, result['status']), (97, 'INVALID_EVIDENCE'))


def main():
    if sys.argv[1:2] == ['--worker']:
        return worker(sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5], sys.argv[6:])
    if sys.argv[1:] == ['--self-test']:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(SelfTest))
        return 0 if result.wasSuccessful() else 1
    require(len(sys.argv) >= 5 and sys.argv[1] == '--run' and sys.argv[3] == '--',
            'usage: --run NEW_EVIDENCE_DIR -- COMMAND | --self-test')
    return wrapped_run(Path(sys.argv[2]), sys.argv[4:])


if __name__ == '__main__':
    raise SystemExit(main())
