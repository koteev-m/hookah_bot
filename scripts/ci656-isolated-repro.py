#!/usr/bin/env python3
"""One create-event attempt; no publication or operational recovery.

The unchanged fixture owns resource cleanup. The 60-minute workflow job is the
outer bound, as in compose-runtime; no new lifecycle timeout/retry/kill policy is
inserted. Cancellation or missing completion never attests resource quiescence.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
BASE = 'eab6a22025b5b532e852a92414d2d9bbcd451f57'
BRANCH = 'codex/ht-ci656-isolated-github-repro-09'
REPOSITORY = 'koteev-m/hookah_bot'
WORKFLOW = '.github/workflows/ci656-isolated-repro-09.yml'
SELF = 'scripts/ci656-isolated-repro.py'
PREFIX = 'HT_CI656_REPRO_RESULT '
REVIEWED = {
    'PROJECT_STATUS.md': '0f3854edf1837b67928505b90399f2464aedce8b09784da674e3a105a7542c81',
    'docs/TESTING_QA_SMOKE_STRATEGY.md': 'c4f0d1ec98c77dd8c9936fc6ae834443372b57a3ce05a36a729322c6d0c8043f',
    'scripts/test-v126-linux-runtime.py': '16625d0a050a5728fb31714611dafddcbe28678b43302300ee74632638190226',
    'scripts/test-v126-ordinary-deploy.py': '88499900722a1c251cbf95f936cb9e9ff221449d30a3dc2d54c3132eb7066bef',
    'scripts/v126-ordinary-deploy.py': 'a13890ae38f6a77c47e512d84495b92b95161f2b1831cf18f046da5a8dbc03ee',
}
ADDITIONS = {WORKFLOW, SELF, 'scripts/test-ci656-isolated-repro.py', 'docs/CI656_ISOLATED_REPRO_09.md'}
REQUIRED_TOOLS = ('sudo', 'systemctl', 'caddy', 'curl', 'ssh', 'bash', 'git', 'docker',
                  'java', 'javac', 'keytool', 'openssl', 'postgres', 'psql', 'pg_dump', 'pg_restore',
                  'dpkg-query')
CADDY_DIRECTORY = 'v126-caddy-package/runtime/usr/bin'
LOOPBACK = (
    'test_real_tcp_bytes_and_per_connection_resolution',
    'test_real_tcp_connection_cap_and_public_target_refusal',
    'test_real_tcp_idle_deadline_and_active_cleanup_are_bounded',
    'test_actual_readiness_consumer_503_to_200_through_real_tcp_relay',
)
EVENT_KEYS = ('EVENT_NAME', 'EVENT_SHA', 'WORKFLOW_SHA', 'REF', 'EVENT_REF', 'REF_TYPE',
              'REPOSITORY', 'RUN_ATTEMPT', 'RUN_ID', 'WORKFLOW_REF')
REASONS = {
    'EVENT_IDENTITY', 'SOURCE_IDENTITY', 'SOURCE_HASH', 'HOST_IDENTITY', 'ROOT_REQUIRED',
    'SYSTEMD_REQUIRED', 'TOOLS_REQUIRED', 'TOOL_VERSION', 'DOCKER_ENDPOINT',
    'DOCKER_NOT_EMPTY', 'INHERITED_AUTHORITY', 'PRIVATE_DIRECTORY', 'LOOPBACK_FAILED',
    'LOOPBACK_DEADLINE', 'COMMAND_FAILED', 'COMMAND_DEADLINE', 'RESULT_UNVERIFIED',
    'ALREADY_INVOKED', 'INTERRUPTED', 'INTERNAL_CHECK_FAILED', 'DEPENDENCY_SETUP_FAILED',
}


class Blocked(Exception):
    pass


class ToolsUnavailable(Blocked):
    def __init__(self, names, category='NOT_ON_PATH'):
        super().__init__('TOOLS_REQUIRED')
        require(bool(names) and all(name in REQUIRED_TOOLS for name in names), 'RESULT_UNVERIFIED')
        require(category in {'NOT_ON_PATH', 'EXPECTED_LOCATION_UNAVAILABLE'}, 'RESULT_UNVERIFIED')
        self.diagnostics = [dict(stage='PREFLIGHT_TOOLS', tool=name, reason_category=category)
                            for name in REQUIRED_TOOLS if name in names]


def require_tools():
    missing = [name for name in REQUIRED_TOOLS if shutil.which(name) is None]
    if missing:
        raise ToolsUnavailable(missing)


def fixture_path(env, parent):
    # Keep env -i isolation while explicitly selecting the setup-java JDK and
    # extracted Caddy package. Never forward the runner's arbitrary PATH.
    paths = [str(parent / CADDY_DIRECTORY), '/usr/lib/postgresql/17/bin',
             '/usr/local/sbin', '/usr/local/bin', '/usr/sbin', '/usr/bin', '/sbin', '/bin']
    java = env.get('JAVA_HOME')
    if not java or not Path(java).is_absolute() or not Path(java).is_dir():
        raise ToolsUnavailable(('java', 'javac', 'keytool'), 'EXPECTED_LOCATION_UNAVAILABLE')
    paths.insert(0, str(Path(java) / 'bin'))
    return os.pathsep.join(paths)


def require(value, reason):
    if not value:
        raise Blocked(reason)


def emit(status, reason=None, **fields):
    require(status in {'REPRO_BLOCKED', 'REPRO_FAILED', 'REPRO_PASSED', 'REPRO_UNKNOWN'}, 'RESULT_UNVERIFIED')
    require(reason is None or reason in REASONS, 'RESULT_UNVERIFIED')
    record = dict(status=status, root_cause_CI656='UNKNOWN', **fields)
    if reason:
        record['reason'] = reason
    print(PREFIX + json.dumps(record, sort_keys=True, ensure_ascii=True).replace('::', r'\u003a\u003a'), flush=True)


def command(argv, *, timeout=20, env=None):
    try:
        result = subprocess.run([str(x) for x in argv], cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Blocked('COMMAND_DEADLINE') from None
    except OSError:
        raise Blocked('COMMAND_FAILED') from None
    require(result.returncode == 0 and len(result.stdout) + len(result.stderr) <= 1024 * 1024, 'COMMAND_FAILED')
    return result.stdout.decode('utf-8'), result.stderr.decode('utf-8')


def git(*args):
    return command(['git', '--no-optional-locks', '-c', 'safe.directory=' + str(ROOT), *args])[0].strip()


def validate_event(env):
    require(env.get('REPRO_EVENT_NAME') == 'create' and env.get('REPRO_REF_TYPE') == 'branch'
            and env.get('REPRO_REPOSITORY') == REPOSITORY and env.get('REPRO_EVENT_REF') == BRANCH
            and env.get('REPRO_REF') == 'refs/heads/' + BRANCH and env.get('REPRO_RUN_ATTEMPT') == '1'
            and re.fullmatch(r'[1-9][0-9]*', env.get('REPRO_RUN_ID', ''))
            and re.fullmatch(r'[0-9a-f]{40}', env.get('REPRO_EVENT_SHA', ''))
            and env.get('REPRO_WORKFLOW_SHA') == env.get('REPRO_EVENT_SHA')
            and env.get('REPRO_WORKFLOW_REF') == REPOSITORY + '/' + WORKFLOW + '@refs/heads/' + BRANCH,
            'EVENT_IDENTITY')


def verify_source(env):
    validate_event(env)
    require(git('rev-parse', 'HEAD') == env['REPRO_EVENT_SHA'], 'SOURCE_IDENTITY')
    require(git('rev-list', '--parents', '-n', '1', 'HEAD').split() == [env['REPRO_EVENT_SHA'], BASE],
            'SOURCE_IDENTITY')
    require('[skip ci]' in git('show', '-s', '--format=%B', 'HEAD'), 'SOURCE_IDENTITY')
    require(not git('status', '--porcelain', '--untracked-files=all'), 'SOURCE_IDENTITY')
    changed = set(git('diff', '--name-only', BASE, 'HEAD').splitlines())
    require(changed == set(REVIEWED) | ADDITIONS, 'SOURCE_IDENTITY')
    for name, digest in REVIEWED.items():
        path = ROOT / name
        require(not path.is_symlink() and hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'SOURCE_HASH')
    # Historical source must already be present; no network fetch or reconstruction.
    require(git('rev-parse', '--verify', '919abbb813cbbb724d628bdabeef8e905e6a1c42^{commit}')
            == '919abbb813cbbb724d628bdabeef8e905e6a1c42', 'SOURCE_IDENTITY')


def validate_host(env, system, os_release):
    require(system == 'linux' and env.get('GITHUB_ACTIONS') == 'true'
            and env.get('RUNNER_ENVIRONMENT') == 'github-hosted' and env.get('RUNNER_OS') == 'Linux',
            'HOST_IDENTITY')
    require(os_release.get('ID') == 'ubuntu' and os_release.get('VERSION_ID') == '26.04'
            and os_release.get('PRETTY_NAME') == 'Ubuntu 26.04.1 LTS', 'HOST_IDENTITY')


def os_release():
    if sys.platform != 'linux':
        return {}
    return dict((key, shlex.split(value)[0]) for key, value in
                (line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines()
                 if '=' in line and not line.startswith('#')))


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def root_preflight():
    validate_host(os.environ, sys.platform, os_release())
    require(os.geteuid() == 0, 'ROOT_REQUIRED')
    require(Path('/proc/1/comm').read_text().strip() == 'systemd'
            and Path('/run/systemd/system').is_dir()
            and Path('/sys/fs/cgroup/cgroup.controllers').is_file()
            and hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal'), 'SYSTEMD_REQUIRED')
    require_tools()
    require(command(['sudo', '-n', 'id', '-u'])[0].strip() == '0', 'ROOT_REQUIRED')
    command(['systemctl', 'show', '--property=Version'])
    require(command(['javac', '-version'])[0].strip().startswith('javac 21.'), 'TOOL_VERSION')
    java = command(['java', '-version'])
    require(re.search(r'version "21(?:\.|\")', ''.join(java)), 'TOOL_VERSION')
    for name in ('postgres', 'psql', 'pg_dump', 'pg_restore'):
        require(re.search(r'\(PostgreSQL\) 17\.', command([name, '--version'])[0]), 'TOOL_VERSION')
    libpq = command(['dpkg-query', '-W', '-f=${Version}', 'libpq5'])[0].strip()
    require(re.match(r'^(?:[0-9]+:)?18\.', libpq), 'TOOL_VERSION')
    require(re.match(r'v[0-9]+\.[0-9]+\.', command(['caddy', 'version'])[0]), 'TOOL_VERSION')
    endpoint = Path('/var/run/docker.sock')
    require(endpoint.resolve() == Path('/run/docker.sock') and stat.S_ISSOCK(endpoint.stat().st_mode)
            and endpoint.stat().st_uid == 0, 'DOCKER_ENDPOINT')
    version = json.loads(command(['docker', 'version', '--format', '{{json .}}'])[0])
    compose = command(['docker', 'compose', 'version', '--short'])[0].strip().removeprefix('v')
    validate_docker(version, compose)
    # Read-only admission: never clean an occupied daemon to make it pass.
    for args in (['ps', '-aq'], ['volume', 'ls', '-q'], ['image', 'ls', '-aq']):
        require(not command(['docker', *args])[0].strip(), 'DOCKER_NOT_EMPTY')
    networks = command(['docker', 'network', 'ls', '--format', '{{.Name}}'])[0].splitlines()
    require(set(networks) == {'bridge', 'host', 'none'} and len(networks) == 3, 'DOCKER_NOT_EMPTY')
    rows = json.loads(command(['docker', 'network', 'inspect', 'bridge', 'host', 'none'])[0])
    require(len(rows) == 3 and all(not row.get('Containers') and not row.get('Labels') for row in rows),
            'DOCKER_NOT_EMPTY')
    disk = command(['docker', 'system', 'df', '--format', '{{json .}}'])[0]
    require(all(str(json.loads(line)['TotalCount']) == '0' for line in disk.splitlines()), 'DOCKER_NOT_EMPTY')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 8080))


def validate_docker(version, compose):
    require(version.get('Client', {}).get('Version') == '29.4.2'
            and version.get('Server', {}).get('Version') == '29.4.2'
            and version.get('Server', {}).get('Os') == 'linux' and compose == '5.1.3', 'TOOL_VERSION')


def loopback_child():
    runtime = load('ci656_loopback_runtime', 'test-v126-linux-runtime.py')
    suite = unittest.TestSuite(runtime.TransportTest(name) for name in LOOPBACK)
    result = unittest.TextTestRunner(stream=sys.stderr, verbosity=2).run(suite)
    return 0 if result.wasSuccessful() and result.testsRun == 4 else 1


def private_json(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as handle:
        info = os.fstat(handle.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1
                and info.st_size <= 8 * 1024 * 1024 and not info.st_mode & 0o022, 'RESULT_UNVERIFIED')
        return json.load(handle)


def safe_result(result, guard, exit_code):
    require(type(result) is dict and result.get('status') in {'PASSED', 'FAILED'}
            and type(result.get('cleanup_quiescent')) is bool
            and result.get('source_sha256') == REVIEWED['scripts/v126-ordinary-deploy.py'], 'RESULT_UNVERIFIED')
    require(guard.get('status') == 'NO_UNEXPECTED_ENDPOINT' and guard.get('unexpected_calls') == 0
            and guard.get('child_exit') == exit_code
            and guard.get('guard_sha256') == hashlib.sha256(
                (ROOT / 'scripts/v126-test-endpoint-guard.py').read_bytes()).hexdigest(), 'RESULT_UNVERIFIED')
    summary = dict(fixture_status=result['status'], cleanup_quiescent=result['cleanup_quiescent'],
                   endpoint_guard='NO_UNEXPECTED_ENDPOINT', loopback_tests_passed=4,
                   lifecycle_invocations=1, first_failure=None)
    failure = result.get('first_failure')
    if failure is not None:
        ordinary = load('ci656_summary_ordinary', 'v126-ordinary-deploy.py')
        summary['first_failure'] = ordinary.validate_failure(failure['diagnostic'])
        build = failure.get('build_diagnostic')
        if build is not None:
            # Copy only the record produced by the SHA-pinned reviewed diagnostic
            # writer. Never derive a public excerpt from private.log or traceback.
            require(build.get('category') == 'build' and build.get('consumer') == 'docker'
                    and build.get('harness_sha256') == REVIEWED['scripts/test-v126-linux-runtime.py'],
                    'RESULT_UNVERIFIED')
            for key in ('stdout', 'stderr'):
                stream = build[key]
                require(set(stream) == {'sha256', 'bytes', 'excerpt', 'truncated', 'excerpt_format', 'reason_codes'}
                        and re.fullmatch(r'[0-9a-f]{64}', stream['sha256'])
                        and type(stream['bytes']) is int and stream['bytes'] >= 0
                        and stream['excerpt_format'] == 'safe-diagnostic-summary'
                        and type(stream['truncated']) is bool
                        and set(stream['reason_codes']) <= {'UNRESOLVED_REFERENCE', 'ENOSPC', 'CONNECTION_RESET'}
                        and len(json.dumps(stream['excerpt'], ensure_ascii=True).encode()) <= 4096,
                        'RESULT_UNVERIFIED')
            summary['build_diagnostic'] = {key: build[key] for key in ('stdout', 'stderr')}
    require((result['status'] == 'PASSED') == (exit_code == 0 and failure is None
                                             and result['cleanup_quiescent']), 'RESULT_UNVERIFIED')
    require(result['status'] != 'FAILED' or exit_code != 0, 'RESULT_UNVERIFIED')
    return summary


def guarded_command(directory):
    return [sys.executable, '-B', str(ROOT / 'scripts/v126-test-endpoint-guard.py'),
            '--run', str(directory / 'endpoints'), '--', sys.executable, '-B',
            str(ROOT / 'scripts/test-v126-ordinary-deploy.py'), '--require-linux-integration',
            '--evidence', str(directory / 'ordinary')]


def root_run(directory):
    validate_host(os.environ, sys.platform, os_release())
    require(os.geteuid() == 0, 'ROOT_REQUIRED')
    verify_source(os.environ)
    require(directory.is_absolute() and directory.resolve() == directory
            and directory.name.startswith('ht-ci656-repro-09-') and directory.is_dir()
            and directory == Path(os.environ['RUNNER_TEMP'])
            and stat.S_IMODE(directory.stat().st_mode) == 0o700, 'PRIVATE_DIRECTORY')
    marker = directory / 'ordinary-invoked'
    require(not marker.exists(), 'ALREADY_INVOKED')
    os.chown(directory, 0, 0)
    root_preflight()
    log = directory / 'private.log'
    with log.open('xb') as capture:
        os.chmod(log, 0o600)
        try:
            loopback = subprocess.run([sys.executable, '-B', str(ROOT / SELF), '--loopback'],
                                      stdout=capture, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, timeout=120)
        except subprocess.TimeoutExpired:
            raise Blocked('LOOPBACK_DEADLINE') from None
        require(loopback.returncode == 0, 'LOOPBACK_FAILED')
        with marker.open('x') as handle:
            handle.write(os.environ['REPRO_EVENT_SHA'] + '\n')
        # Exactly one invocation. The original coordinator owns deadlines/signals
        # and decides whether resources may be cleaned or must be retained.
        result = subprocess.run(guarded_command(directory), stdout=capture, stderr=subprocess.STDOUT,
                                stdin=subprocess.DEVNULL)
    summary = safe_result(private_json(directory / 'ordinary/result.json'),
                          private_json(directory / 'endpoints/result.json'), result.returncode)
    emit('REPRO_PASSED' if result.returncode == 0 else 'REPRO_FAILED',
         event_sha=os.environ['REPRO_EVENT_SHA'], **summary)
    return 0 if result.returncode == 0 else 1


def preparation_check():
    # Also run before dependency installation. Refuse Mac/local hosts before
    # Git, sudo or Docker can be invoked.
    validate_host(os.environ, sys.platform, os_release())
    verify_source(os.environ)
    forbidden = ('DATABASE_URL', 'DB_PASSWORD', 'TELEGRAM_BOT_TOKEN', 'GH_TOKEN', 'GITHUB_TOKEN',
                 'SSH_AUTH_SOCK', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG')
    require(not any(os.environ.get(key) for key in forbidden)
            and not any(os.environ.get(key) for key in os.environ if key.startswith(('STAGING_', 'PRODUCTION_'))),
            'INHERITED_AUTHORITY')


def runner():
    preparation_check()
    parent = Path(os.environ['RUNNER_TEMP'])
    require(parent.is_absolute() and parent.resolve() == parent and parent.is_dir(), 'PRIVATE_DIRECTORY')
    paths = fixture_path(os.environ, parent)
    directory = Path(tempfile.mkdtemp(prefix='ht-ci656-repro-09-', dir=parent))
    home = directory / 'home'
    home.mkdir(mode=0o700)
    # Only this disposable HOME is configured; checkout/global runner Git state is untouched.
    (home / '.gitconfig').write_text('[safe]\n\tdirectory = ' + str(ROOT) + '\n')
    clean = dict(PATH=paths, HOME=str(home), RUNNER_TEMP=str(directory), TMPDIR=str(directory),
                 GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux',
                 PYTHONDONTWRITEBYTECODE='1', GIT_OPTIONAL_LOCKS='0')
    clean.update({'REPRO_' + key: os.environ['REPRO_' + key] for key in EVENT_KEYS})
    # No project/GitHub credentials, proxy configuration, agent or remote Docker context.
    argv = ['sudo', '-n', 'env', '-i', *(key + '=' + value for key, value in sorted(clean.items())),
            sys.executable, '-B', str(ROOT / SELF), '--root-run', str(directory)]
    try:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        # The root driver emits one closed summary. Raw sudo/Python stderr is never printed.
        lines = result.stdout.decode('utf-8').splitlines()
        require(len(lines) == 1 and lines[0].startswith(PREFIX), 'RESULT_UNVERIFIED')
        record = json.loads(lines[0][len(PREFIX):])
        require(record.get('status') in {'REPRO_BLOCKED', 'REPRO_FAILED', 'REPRO_PASSED', 'REPRO_UNKNOWN'}
                and record.get('root_cause_CI656') == 'UNKNOWN', 'RESULT_UNVERIFIED')
    except Exception:
        # A lost root-driver result cannot prove that the lifecycle never began.
        emit('REPRO_UNKNOWN', 'RESULT_UNVERIFIED', lifecycle_invocations=None, cleanup_quiescent=None)
        return 75
    print(lines[0], flush=True)
    return result.returncode


def main():
    try:
        if sys.argv[1:] == ['--check-preparation']:
            preparation_check()
            return 0
        if sys.argv[1:] == ['--loopback']:
            return loopback_child()
        if len(sys.argv) == 3 and sys.argv[1] == '--root-run':
            return root_run(Path(sys.argv[2]))
        require(not sys.argv[1:], 'EVENT_IDENTITY')
        return runner()
    except Blocked as error:
        reason = str(error) if str(error) in REASONS else 'INTERNAL_CHECK_FAILED'
        invoked = len(sys.argv) == 3 and sys.argv[1] == '--root-run' and (Path(sys.argv[2]) / 'ordinary-invoked').exists()
        diagnostics = {'tool_diagnostics': error.diagnostics} if isinstance(error, ToolsUnavailable) else {}
        emit('REPRO_UNKNOWN' if invoked else 'REPRO_BLOCKED', reason,
             lifecycle_invocations=1 if invoked else 0, cleanup_quiescent=None, **diagnostics)
        return 75 if invoked else 2
    except (KeyboardInterrupt, SystemExit):
        emit('REPRO_UNKNOWN', 'INTERRUPTED', cleanup_quiescent=None)
        return 75
    except Exception:
        invoked = len(sys.argv) == 3 and sys.argv[1] == '--root-run' and (Path(sys.argv[2]) / 'ordinary-invoked').exists()
        emit('REPRO_UNKNOWN' if invoked else 'REPRO_BLOCKED', 'INTERNAL_CHECK_FAILED',
             lifecycle_invocations=1 if invoked else 0, cleanup_quiescent=None)
        return 75 if invoked else 2


if __name__ == '__main__':
    raise SystemExit(main())
