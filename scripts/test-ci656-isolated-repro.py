#!/usr/bin/env python3
"""Preparation checks only: no Docker daemon, root actions or network listeners."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('ci656_repro', ROOT / 'scripts/ci656-isolated-repro.py')
repro = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = repro
SPEC.loader.exec_module(repro)


class PreparationTest(unittest.TestCase):
    def event(self):
        return dict(REPRO_EVENT_NAME='create', REPRO_EVENT_SHA='a' * 40, REPRO_WORKFLOW_SHA='a' * 40,
                    REPRO_REF='refs/heads/' + repro.BRANCH, REPRO_EVENT_REF=repro.BRANCH,
                    REPRO_REF_TYPE='branch', REPRO_REPOSITORY=repro.REPOSITORY,
                    REPRO_RUN_ATTEMPT='1', REPRO_RUN_ID='123',
                    REPRO_WORKFLOW_REF=repro.REPOSITORY + '/' + repro.WORKFLOW + '@refs/heads/' + repro.BRANCH)

    def host(self):
        return dict(GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux')

    def fixture_result(self):
        return dict(status='PASSED', first_failure=None, cleanup_quiescent=True,
                    source_sha256=repro.REVIEWED['scripts/v126-ordinary-deploy.py'],
                    events=[{'private': 'do-not-publish'}])

    def guard_result(self, status=0):
        return dict(status='NO_UNEXPECTED_ENDPOINT', unexpected_calls=0, child_exit=status,
                    guard_sha256=hashlib.sha256((ROOT / 'scripts/v126-test-endpoint-guard.py').read_bytes()).hexdigest())

    def source_git(self, *args):
        return {('rev-parse', 'HEAD'): 'a' * 40,
                ('rev-list', '--parents', '-n', '1', 'HEAD'): 'a' * 40 + ' ' + repro.BASE,
                ('show', '-s', '--format=%B', 'HEAD'): 'isolated repro [skip ci]',
                ('status', '--porcelain', '--untracked-files=all'): '',
                ('diff', '--name-only', repro.BASE, 'HEAD'): '\n'.join(sorted(set(repro.REVIEWED) | repro.ADDITIONS)),
                ('rev-parse', '--verify', '919abbb813cbbb724d628bdabeef8e905e6a1c42^{commit}'):
                    '919abbb813cbbb724d628bdabeef8e905e6a1c42'}[args]

    def test_create_identity_rejects_every_mismatched_field_and_rerun(self):
        repro.validate_event(self.event())
        for key in self.event():
            env = self.event(); env[key] = 'wrong'
            with self.subTest(key=key), self.assertRaises(repro.Blocked):
                repro.validate_event(env)
        for attempt in ('2', '0', ''):
            with self.assertRaises(repro.Blocked):
                repro.validate_event(dict(self.event(), REPRO_RUN_ATTEMPT=attempt))

    def test_source_accepts_only_single_child_event_commit_and_exact_file_set(self):
        with patch.object(repro, 'git', side_effect=self.source_git):
            repro.verify_source(self.event())
        for command, value in [
                (('rev-parse', 'HEAD'), 'b' * 40),
                (('rev-list', '--parents', '-n', '1', 'HEAD'), 'a' * 40 + ' ' + repro.BASE + ' ' + 'b' * 40),
                (('show', '-s', '--format=%B', 'HEAD'), 'missing skip marker'),
                (('status', '--porcelain', '--untracked-files=all'), '?? injected.py'),
                (('diff', '--name-only', repro.BASE, 'HEAD'), self.source_git('diff', '--name-only', repro.BASE, 'HEAD') + '\n.github/workflows/ci.yml')]:
            def changed(*args):
                return value if args == command else self.source_git(*args)
            with self.subTest(command=command), patch.object(repro, 'git', side_effect=changed), self.assertRaises(repro.Blocked):
                repro.verify_source(self.event())

    def test_reviewed_hash_tamper_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            for name in repro.REVIEWED:
                (target / name).parent.mkdir(parents=True, exist_ok=True)
                (target / name).write_bytes((ROOT / name).read_bytes())
            (target / 'PROJECT_STATUS.md').write_text('changed')
            with patch.object(repro, 'ROOT', target), patch.object(repro, 'git', side_effect=self.source_git), self.assertRaisesRegex(repro.Blocked, 'SOURCE_HASH'):
                repro.verify_source(self.event())

    def test_host_requires_exact_ubuntu_point_release_and_hosted_linux(self):
        release = dict(ID='ubuntu', VERSION_ID='26.04', PRETTY_NAME='Ubuntu 26.04.1 LTS')
        repro.validate_host(self.host(), 'linux', release)
        for key in release:
            with self.subTest(key=key), self.assertRaises(repro.Blocked):
                repro.validate_host(self.host(), 'linux', dict(release, **{key: 'wrong'}))
        for key in self.host():
            with self.subTest(key=key), self.assertRaises(repro.Blocked):
                repro.validate_host(dict(self.host(), **{key: 'wrong'}), 'linux', release)
        with self.assertRaises(repro.Blocked):
            repro.validate_host(self.host(), 'darwin', release)

    def test_docker_client_server_compose_must_all_match(self):
        version = {'Client': {'Version': '29.4.2'}, 'Server': {'Version': '29.4.2', 'Os': 'linux'}}
        repro.validate_docker(version, '5.1.3')
        for section, key, value in [('Client', 'Version', '29.8.1'), ('Server', 'Version', '29.8.1'), ('Server', 'Os', 'windows')]:
            changed = copy.deepcopy(version); changed[section][key] = value
            with self.subTest(section=section, key=key), self.assertRaises(repro.Blocked):
                repro.validate_docker(changed, '5.1.3')
        with self.assertRaises(repro.Blocked):
            repro.validate_docker(version, '5.5.1')

    def test_mac_refuses_before_any_subprocess(self):
        output = io.StringIO()
        with patch.object(sys, 'argv', [str(ROOT / repro.SELF)]), patch.object(sys, 'platform', 'darwin'), \
                patch.object(repro.subprocess, 'run') as run, contextlib.redirect_stdout(output):
            self.assertEqual(repro.main(), 2)
        run.assert_not_called()
        record = json.loads(output.getvalue().removeprefix(repro.PREFIX))
        self.assertEqual((record['status'], record['reason'], record['lifecycle_invocations']),
                         ('REPRO_BLOCKED', 'HOST_IDENTITY', 0))

    def test_exact_four_existing_loopback_tests_are_selected_without_running_them(self):
        runtime = repro.load('ci656_test_selection', 'test-v126-linux-runtime.py')
        self.assertEqual(len(set(repro.LOOPBACK)), 4)
        self.assertTrue(all(callable(getattr(runtime.TransportTest, name)) for name in repro.LOOPBACK))

    def test_guarded_invocation_is_existing_root_ordinary_fixture(self):
        target = Path('/private/owned-fixture')
        self.assertEqual(repro.guarded_command(target), [sys.executable, '-B',
                         str(ROOT / 'scripts/v126-test-endpoint-guard.py'), '--run', str(target / 'endpoints'),
                         '--', sys.executable, '-B', str(ROOT / 'scripts/test-v126-ordinary-deploy.py'),
                         '--require-linux-integration', '--evidence', str(target / 'ordinary')])

    def test_summary_excludes_events_and_requires_completion_and_bound_guard(self):
        summary = repro.safe_result(self.fixture_result(), self.guard_result(), 0)
        self.assertNotIn('do-not-publish', json.dumps(summary))
        for field, value in [('cleanup_quiescent', False), ('source_sha256', '0' * 64), ('status', 'UNKNOWN')]:
            with self.subTest(field=field), self.assertRaises(repro.Blocked):
                repro.safe_result(dict(self.fixture_result(), **{field: value}), self.guard_result(), 0)
        for field, value in [('status', 'UNEXPECTED_ENDPOINT'), ('unexpected_calls', 1), ('child_exit', 75), ('guard_sha256', '0' * 64)]:
            with self.subTest(field=field), self.assertRaises(repro.Blocked):
                repro.safe_result(self.fixture_result(), dict(self.guard_result(), **{field: value}), 0)

    def test_reviewed_public_writer_survives_projection_without_raw_capture(self):
        runtime = repro.load('ci656_summary_runtime', 'test-v126-linux-runtime.py')
        with tempfile.TemporaryDirectory() as directory:
            diag = runtime.Diagnostics(Path(directory), 'b' * 64, lambda: {})
            payload = b'Could not parse POM https://private.invalid/artifact.xml private:dependency:1'
            diag.command('build', 'docker', 1, 1, 900, b'', payload)
            failure = dict(stage='BUILD', consumer='docker', reason_category='METADATA_INDICATOR',
                           exit_code=1, timed_out=False, children='UNKNOWN', supervisor_exit_code=None)
            result = dict(self.fixture_result(), status='FAILED',
                          first_failure={'diagnostic': failure, 'build_diagnostic': diag.records[0], 'private': 'do-not-publish'})
            summary = repro.safe_result(result, self.guard_result(1), 1)
            self.assertEqual(summary['build_diagnostic']['stderr'], diag.records[0]['stderr'])
            self.assertEqual(summary['build_diagnostic']['stderr']['sha256'], hashlib.sha256(payload).hexdigest())
            for private in ('private.invalid', 'private:dependency:1', 'do-not-publish'):
                self.assertNotIn(private, json.dumps(summary))
            with self.assertRaises(repro.Blocked):
                repro.safe_result(result, self.guard_result(), 0)

    def test_one_invocation_marker_prevents_second_launch(self):
        with tempfile.TemporaryDirectory(prefix='ht-ci656-repro-10-') as temporary:
            directory = Path(temporary).resolve()
            (directory / 'ordinary-invoked').write_text('a' * 40)
            with patch.object(repro, 'validate_host'), patch.object(os, 'geteuid', return_value=0), \
                    patch.object(repro, 'verify_source'), patch.dict(os.environ, RUNNER_TEMP=str(directory)), \
                    patch.object(repro.subprocess, 'run') as run, self.assertRaisesRegex(repro.Blocked, 'ALREADY_INVOKED'):
                repro.root_run(directory)
            run.assert_not_called()

    def test_lost_root_result_is_unknown_and_never_retried_or_printed(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            environment = dict(self.event(), RUNNER_TEMP=str(parent), JAVA_HOME=str(parent), PRIVATE_TOKEN='not-forwarded')
            output = io.StringIO()
            missing = subprocess.CompletedProcess([], 75, b'private-stdout', b'private-stderr')
            with patch.dict(os.environ, environment, clear=True), patch.object(repro, 'validate_host'), \
                    patch.object(repro, 'verify_source'), patch.object(repro.subprocess, 'run', return_value=missing) as run, \
                    contextlib.redirect_stdout(output):
                self.assertEqual(repro.runner(), 75)
            self.assertEqual(run.call_count, 1)
            argv = run.call_args.args[0]
            self.assertEqual(argv[:4], ['sudo', '-n', 'env', '-i'])
            self.assertFalse(any('PRIVATE_TOKEN' in arg or 'not-forwarded' in arg for arg in argv))
            record = json.loads(output.getvalue().removeprefix(repro.PREFIX))
            self.assertEqual(record['status'], 'REPRO_UNKNOWN')
            self.assertIsNone(record['cleanup_quiescent'])
            self.assertIsNone(record['lifecycle_invocations'])
            self.assertNotIn('private-stdout', output.getvalue())
            self.assertNotIn('private-stderr', output.getvalue())

    def test_post_invocation_exception_does_not_become_blocked_zero_attempts(self):
        with tempfile.TemporaryDirectory(prefix='ht-ci656-repro-10-') as temporary:
            directory = Path(temporary).resolve()
            (directory / 'ordinary-invoked').write_text('a' * 40)
            output = io.StringIO()
            with patch.object(sys, 'argv', [str(ROOT / repro.SELF), '--root-run', str(directory)]), \
                    patch.object(repro, 'root_run', side_effect=ValueError('private-error')), contextlib.redirect_stdout(output):
                self.assertEqual(repro.main(), 75)
            record = json.loads(output.getvalue().removeprefix(repro.PREFIX))
            self.assertEqual((record['status'], record['lifecycle_invocations']), ('REPRO_UNKNOWN', 1))
            self.assertIsNone(record['cleanup_quiescent'])
            self.assertNotIn('private-error', output.getvalue())

    def test_yaml_single_create_job_no_publish_permissions_or_secrets(self):
        raw = (ROOT / repro.WORKFLOW).read_text()
        parsed = json.loads(subprocess.check_output(['ruby', '-rpsych', '-rjson', '-e',
                            'puts JSON.generate(Psych.safe_load(File.read(ARGV[0])))', str(ROOT / repro.WORKFLOW)], text=True))
        self.assertEqual(parsed['on'], {'create': None})
        self.assertEqual(parsed['permissions'], {'contents': 'read'})
        self.assertEqual(set(parsed['jobs']), {'repro'})
        job = parsed['jobs']['repro']
        self.assertEqual(job['runs-on'], 'ubuntu-26.04')
        self.assertEqual(job['timeout-minutes'], 60)
        self.assertNotIn('environment', job)
        self.assertNotIn('permissions', job)
        self.assertNotIn('strategy', job)
        self.assertEqual(len(job['steps']), 5)
        checkout = job['steps'][0]
        self.assertEqual(checkout['with'], {'ref': '${{ github.sha }}', 'fetch-depth': 0, 'persist-credentials': False})
        self.assertRegex(checkout['uses'], r'^actions/checkout@[0-9a-f]{40}$')
        self.assertIn("github.event.ref_type == 'branch'", job['if'])
        self.assertIn("github.event.ref == '" + repro.BRANCH + "'", job['if'])
        self.assertIn("github.ref == 'refs/heads/" + repro.BRANCH + "'", job['if'])
        self.assertEqual(repro.BRANCH, 'codex/ht-ci656-tool-version-diag-10')
        self.assertNotIn('repro-07', raw)
        self.assertIn("github.run_attempt == '1'", job['if'])
        for forbidden in ('secrets.', 'id-token', 'upload-artifact', 'workflow_dispatch', 'repository_dispatch',
                          'docker/login', 'docker push', 'continue-on-error', 'retry'):
            self.assertNotIn(forbidden, raw)
        self.assertEqual(job['steps'][1]['run'], 'python3 -B ' + repro.SELF + ' --check-preparation')
        java = job['steps'][2]
        self.assertEqual(java['uses'], 'actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9')
        self.assertEqual(java['with'], {'distribution': 'temurin', 'java-version': '21', 'overwrite-settings': False})
        install = job['steps'][3]
        self.assertEqual(install['timeout-minutes'], 12)
        self.assertIn("postgresql-17 postgresql-client-17 'libpq5=18.*'", install['run'])
        self.assertIn('apt-get download caddy', install['run'])
        self.assertIn('dpkg-deb -x ./caddy_*.deb runtime', install['run'])
        self.assertNotIn('docker', install['run'].lower())
        self.assertNotIn('compose', install['run'].lower())
        self.assertIn('python3 -B ' + repro.SELF, job['steps'][4]['run'])
        for step in job['steps']:
            self.assertNotIn('if', step)
            if 'run' in step:
                subprocess.run(['bash', '-n'], input=step['run'], text=True, check=True)

    def test_missing_tools_report_only_allowlisted_names_and_not_paths(self):
        missing = {'caddy', 'postgres', 'pg_dump'}
        output = io.StringIO()
        def lookup(name):
            return None if name in missing else '/private/credential-canary/' + name
        with patch.object(repro.shutil, 'which', side_effect=lookup) as which, \
                patch.object(repro, 'runner', side_effect=repro.require_tools), \
                patch.object(sys, 'argv', ['repro']), contextlib.redirect_stdout(output):
            self.assertEqual(repro.main(), 2)
        self.assertEqual(which.call_count, len(repro.REQUIRED_TOOLS))
        record = json.loads(output.getvalue().removeprefix(repro.PREFIX))
        self.assertEqual(record['reason'], 'TOOLS_REQUIRED')
        self.assertEqual(record['status'], 'REPRO_BLOCKED')
        self.assertEqual(record['lifecycle_invocations'], 0)
        self.assertEqual(record['tool_diagnostics'], [
            {'stage': 'PREFLIGHT_TOOLS', 'tool': name, 'reason_category': 'NOT_ON_PATH'}
            for name in repro.REQUIRED_TOOLS if name in missing])
        self.assertNotIn('credential-canary', output.getvalue())
        with patch.object(repro.shutil, 'which', return_value='/private/available'):
            repro.require_tools()
        for names, category in [(('arbitrary-secret',), 'NOT_ON_PATH'), (('caddy',), 'raw-error')]:
            with self.assertRaises(repro.Blocked):
                repro.ToolsUnavailable(names, category)

    def test_path_preserves_prepared_caddy_and_java_without_inherited_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            java = parent / 'jdk21'
            (java / 'bin').mkdir(parents=True)
            caddy = parent / repro.CADDY_DIRECTORY
            caddy.mkdir(parents=True)
            for directory, names in [(java / 'bin', ('java', 'javac', 'keytool')), (caddy, ('caddy',))]:
                for name in names:
                    (directory / name).write_text('#!/bin/sh\nexit 0\n')
                    (directory / name).chmod(0o700)
            env = dict(JAVA_HOME=str(java), JAVA_HOME_21_X64='/stale/jdk', PATH='/untrusted/bin')
            selected = repro.fixture_path(env, parent)
            self.assertEqual(selected.split(os.pathsep)[:3],
                             [str(java / 'bin'), str(caddy), '/usr/lib/postgresql/17/bin'])
            for tool in ('java', 'javac', 'keytool'):
                self.assertEqual(repro.shutil.which(tool, path=selected), str(java / 'bin' / tool))
            self.assertEqual(repro.shutil.which('caddy', path=selected), str(caddy / 'caddy'))
            self.assertIsNone(repro.shutil.which('caddy', path=str(java / 'bin')))
            self.assertNotIn('/untrusted', selected)
            self.assertNotIn('/stale', selected)
            for value in ('', 'relative', str(parent / 'absent')):
                with self.assertRaises(repro.ToolsUnavailable) as error:
                    repro.fixture_path({'JAVA_HOME': value}, parent)
                self.assertTrue(all(item['reason_category'] == 'EXPECTED_LOCATION_UNAVAILABLE'
                                    for item in error.exception.diagnostics))

    def test_preparation_gate_refuses_inherited_authority_and_old_branch(self):
        with patch.object(repro, 'validate_host'), patch.object(repro, 'verify_source'), \
                patch.dict(os.environ, {'DOCKER_HOST': 'private-host'}, clear=True), \
                self.assertRaisesRegex(repro.Blocked, 'INHERITED_AUTHORITY'):
            repro.preparation_check()
        for key, value in [('REPRO_EVENT_REF', 'codex/ht-ci656-isolated-github-repro-07'),
                           ('REPRO_REF', 'refs/heads/codex/ht-ci656-isolated-github-repro-07'),
                           ('REPRO_EVENT_REF', 'codex/ht-ci656-isolated-github-repro-09'),
                           ('REPRO_REF', 'refs/heads/codex/ht-ci656-isolated-github-repro-09')]:
            with self.assertRaisesRegex(repro.Blocked, 'EVENT_IDENTITY'):
                repro.validate_event(dict(self.event(), **{key: value}))

    def test_preflight_failure_cannot_invoke_loopback_or_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='ht-ci656-repro-10-') as temporary:
            directory = Path(temporary).resolve()
            with patch.object(repro, 'validate_host'), patch.object(os, 'geteuid', return_value=0), \
                    patch.object(repro, 'verify_source'), patch.object(os, 'chown'), \
                    patch.dict(os.environ, RUNNER_TEMP=str(directory)), \
                    patch.object(repro, 'root_preflight', side_effect=repro.ToolsUnavailable(('caddy',))), \
                    patch.object(repro.subprocess, 'run') as run, self.assertRaises(repro.ToolsUnavailable):
                repro.root_run(directory)
            run.assert_not_called()
            self.assertFalse((directory / 'ordinary-invoked').exists())

    def test_libpq18_mismatch_refuses_before_docker_or_fixture(self):
        for version in ('17.8-1', '19.0-1', 'unexpected'):
            called = []
            def command(argv, **kwargs):
                called.append(argv)
                if argv == ['sudo', '-n', 'id', '-u']: return '0\n', ''
                if argv[0] == 'systemctl': return 'Version=1\n', ''
                if argv[0] == 'javac': return 'javac 21.0.1\n', ''
                if argv[0] == 'java': return '', 'openjdk version "21.0.1"'
                if argv[0] == 'dpkg-query': return version, ''
                if argv[0] in ('postgres', 'psql', 'pg_dump', 'pg_restore'):
                    return argv[0] + ' (PostgreSQL) 17.1\n', ''
                self.fail('unexpected command')
            with self.subTest(version=version), patch.object(repro, 'validate_host'), \
                    patch.object(repro, 'os_release', return_value={}), patch.object(os, 'geteuid', return_value=0), \
                    patch.object(Path, 'read_text', return_value='systemd'), \
                    patch.object(Path, 'is_dir', return_value=True), patch.object(Path, 'is_file', return_value=True), \
                    patch.object(os, 'pidfd_open', create=True), patch.object(repro.signal, 'pidfd_send_signal', create=True), \
                    patch.object(repro, 'require_tools'), patch.object(repro, 'command', side_effect=command), \
                    self.assertRaisesRegex(repro.Blocked, 'TOOL_VERSION'):
                repro.root_preflight()
            self.assertEqual(called[-1], ['dpkg-query', '-W', '-f=${Version}', 'libpq5'])

    def test_dependency_setup_stops_at_first_failure_and_never_prints_capture(self):
        parsed = json.loads(subprocess.check_output(['ruby', '-rpsych', '-rjson', '-e',
                            'puts JSON.generate(Psych.safe_load(File.read(ARGV[0])))', str(ROOT / repro.WORKFLOW)], text=True))
        script = parsed['jobs']['repro']['steps'][3]['run']
        for fail_at in range(7):
            with self.subTest(fail_at=fail_at), tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary).resolve()
                binary = parent / 'fake-bin'; binary.mkdir()
                # No actual apt, sudo, PGDG script or dpkg command is executed.
                stub = '#!' + sys.executable + '\n' + '''import os, pathlib, sys
p = pathlib.Path(os.environ['TRACE'])
rows = p.read_text().splitlines() if p.exists() else []
args = sys.argv[1:]
if args[:1] == ['-n']: args = args[1:]
label = ' '.join(args)
p.write_text('\\n'.join([*rows, label]) + '\\n')
print('private-stdout https://secret.invalid dependency:coordinate:1')
print('private-stderr token-canary', file=sys.stderr)
if len(rows) == int(os.environ['FAIL_AT']): sys.exit(1)
'''
                for name in ('sudo', 'apt-get', 'dpkg-deb'):
                    (binary / name).write_text(stub); (binary / name).chmod(0o700)
                trace = parent / 'trace'
                env = dict(os.environ, RUNNER_TEMP=str(parent), TRACE=str(trace), FAIL_AT=str(fail_at),
                           PATH=str(binary) + os.pathsep + os.environ['PATH'])
                result = subprocess.run(['bash', '-euo', 'pipefail'], input=script, text=True,
                                        env=env, capture_output=True, timeout=10)
                self.assertEqual(len(trace.read_text().splitlines()), min(fail_at + 1, 6))
                if fail_at == 6:
                    self.assertEqual(result.returncode, 0)
                    self.assertEqual(result.stdout + result.stderr, '')
                    continue
                self.assertEqual(result.returncode, 2)
                record = json.loads(result.stdout.strip().removeprefix(repro.PREFIX))
                self.assertEqual(record['status'], 'REPRO_BLOCKED')
                self.assertEqual(record['stage'], 'DEPENDENCY_SETUP')
                self.assertIn(record['reason'], repro.REASONS)
                self.assertEqual(record['lifecycle_invocations'], 0)
                self.assertEqual(result.stderr, '')
                self.assertNotIn('private-', result.stdout)
                self.assertNotIn('secret.invalid', result.stdout)
                self.assertNotIn('coordinate', result.stdout)
                self.assertNotIn(str(parent), result.stdout)
                self.assertEqual((parent / 'ci656-dependencies-private.log').stat().st_mode & 0o777, 0o600)


class VersionDiagnosticTest(unittest.TestCase):
    GOOD = {
        'java': 'openjdk version "21.0.13" 2026-10-20\nOpenJDK Runtime Environment Temurin-21.0.13+9',
        'javac': 'javac 21.0.13', 'postgres': 'postgres (PostgreSQL) 17.6 (Ubuntu)',
        'psql': 'psql (PostgreSQL) 17.6 (Ubuntu)', 'pg_dump': 'pg_dump (PostgreSQL) 17.6',
        'pg_restore': 'pg_restore (PostgreSQL) 17.6', 'libpq': '18.1-1.pgdg26.04+1',
        'caddy': 'v2.10.2 h1:private-build-metadata', 'docker_client': '29.4.2',
        'docker_server': '29.4.2', 'docker_os': 'linux', 'docker_compose': '5.1.3',
    }
    BAD = {
        'java': ('openjdk version "22.0.1"', '22.0.1'), 'javac': ('javac 22.0.1', '22.0.1'),
        'postgres': ('postgres (PostgreSQL) 18.1', '18.1'), 'psql': ('psql (PostgreSQL) 18.1', '18.1'),
        'pg_dump': ('pg_dump (PostgreSQL) 18.1', '18.1'), 'pg_restore': ('pg_restore (PostgreSQL) 18.1', '18.1'),
        'libpq': ('1:19.0-1.pgdg26.04+1', '19.0'), 'caddy': ('2.10.2', '2.10.2'),
        'docker_client': ('29.5.0', '29.5.0'), 'docker_server': ('29.5.0', '29.5.0'),
        'docker_os': ('windows', 'windows'), 'docker_compose': ('5.2.0', '5.2.0'),
    }

    def test_all_twelve_good_versions_and_caddy_any_valid_version(self):
        self.assertEqual(set(self.GOOD), set(repro.VERSION_EXPECTATIONS))
        for component, output in self.GOOD.items():
            with self.subTest(component=component):
                self.assertNotEqual(repro.check_version(component, output), 'UNKNOWN')
        # Caddy had a format requirement, never an exact major/minor pin.
        self.assertEqual(repro.check_version('caddy', 'v3.1.0'), '3.1.0')

    def test_each_mismatch_has_closed_public_component_expected_actual_and_zero_invocations(self):
        for component, (value, actual) in self.BAD.items():
            output = io.StringIO()
            with self.subTest(component=component), patch.object(sys, 'argv', ['repro']), \
                    patch.object(repro, 'runner', side_effect=lambda: repro.check_version(component, value)), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(repro.main(), 2)
            record = json.loads(output.getvalue().removeprefix(repro.PREFIX))
            self.assertEqual(record, dict(status='REPRO_BLOCKED', root_cause_CI656='UNKNOWN',
                             reason='TOOL_VERSION', stage='PREFLIGHT_VERSION', component=component,
                             expected_version=repro.VERSION_EXPECTATIONS[component], actual_version=actual,
                             lifecycle_invocations=0, cleanup_quiescent=None))

    def test_unexpected_unknown_or_sensitive_output_is_never_published(self):
        for component in self.GOOD:
            for value in ('', None, {}, 'private-token /private/file https://private.invalid',
                          '::error::private-token', '1.' * 5000):
                with self.subTest(component=component, kind=type(value).__name__), self.assertRaises(repro.VersionMismatch) as error:
                    repro.check_version(component, value)
                self.assertEqual(error.exception.fields['actual_version'], 'UNKNOWN')
                self.assertNotIn('private', json.dumps(error.exception.fields))
        for value in ('29.4.2\n', ' 29.4.2', '29.4.2-private-token'):
            with self.assertRaises(repro.VersionMismatch):
                repro.check_version('docker_client', value)
        for component in ('private-component', {}, None):
            with self.assertRaises(repro.Blocked):
                repro.VersionMismatch(component, '1.2.3')
        self.assertEqual(repro.VersionMismatch('java', {'private': 1}).fields['actual_version'], 'UNKNOWN')

    def test_command_failure_deadline_and_undecodable_version_are_unknown_for_each_component(self):
        for component in self.GOOD:
            for failure in (repro.Blocked('COMMAND_FAILED'), repro.Blocked('COMMAND_DEADLINE'),
                            UnicodeError('private-output')):
                with self.subTest(component=component), patch.object(repro, 'command', side_effect=failure), \
                        self.assertRaises(repro.VersionMismatch) as error:
                    repro.tool_version(component, ['unused'])
                self.assertEqual(error.exception.fields['actual_version'], 'UNKNOWN')
                self.assertEqual(error.exception.fields['component'], component)

    def test_each_missing_executable_remains_tools_required(self):
        for component in self.GOOD:
            executable = ('docker' if component.startswith('docker_') else
                          'dpkg-query' if component == 'libpq' else component)
            with self.subTest(component=component), patch.object(repro.shutil, 'which',
                    side_effect=lambda name: None if name == executable else '/available/' + name), \
                    self.assertRaises(repro.ToolsUnavailable) as error:
                repro.require_tools()
            self.assertEqual(error.exception.diagnostics[0]['tool'], executable)

    def test_each_version_refusal_blocks_loopback_and_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='ht-ci656-repro-10-') as temporary:
            directory = Path(temporary).resolve()
            for component, (value, _) in self.BAD.items():
                with self.subTest(component=component), patch.object(repro, 'validate_host'), \
                        patch.object(os, 'geteuid', return_value=0), patch.object(repro, 'verify_source'), \
                        patch.object(os, 'chown'), patch.dict(os.environ, RUNNER_TEMP=str(directory)), \
                        patch.object(repro, 'root_preflight', side_effect=lambda: repro.check_version(component, value)), \
                        patch.object(repro.subprocess, 'run') as run, self.assertRaises(repro.VersionMismatch):
                    repro.root_run(directory)
                run.assert_not_called()
                self.assertFalse((directory / 'ordinary-invoked').exists())

    def test_early_check_is_after_identity_before_setup_and_mac_never_dispatches(self):
        calls = []
        with patch.object(sys, 'argv', ['repro', '--check-preparation']), \
                patch.object(repro, 'preparation_check', side_effect=lambda: calls.append('identity')), \
                patch.object(repro, 'early_docker_preflight', side_effect=lambda: calls.append('docker')):
            self.assertEqual(repro.main(), 0)
        self.assertEqual(calls, ['identity', 'docker'])
        with patch.object(sys, 'argv', ['repro', '--check-preparation']), patch.object(sys, 'platform', 'darwin'), \
                patch.object(repro.subprocess, 'run') as run, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(repro.main(), 2)
        run.assert_not_called()

    def test_early_docker_uses_only_local_endpoint_fixed_path_and_empty_config(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict(os.environ, {'RUNNER_TEMP': temporary, 'PRIVATE_TOKEN': 'secret',
                                         'DOCKER_HOST': 'private-host', 'PATH': '/untrusted'}, clear=True), \
                    patch.object(repro.shutil, 'which', return_value='/usr/bin/docker'), \
                    patch.object(repro, 'docker_preflight') as check:
                repro.early_docker_preflight()
            kwargs = check.call_args.kwargs
            self.assertEqual(set(kwargs['env']), {'PATH', 'HOME'})
            self.assertEqual(kwargs['env']['PATH'], repro.SYSTEM_PATH)
            self.assertEqual(kwargs['options'], ('--host', 'unix:///run/docker.sock', '--config', kwargs['env']['HOME']))
            self.assertFalse(Path(kwargs['env']['HOME']).exists())
        with patch.object(repro.shutil, 'which', return_value=None), patch.object(repro, 'command') as command, \
                self.assertRaises(repro.ToolsUnavailable):
            repro.early_docker_preflight()
        command.assert_not_called()

    def test_docker_query_failures_and_missing_json_fields_are_component_specific(self):
        from types import SimpleNamespace
        import stat
        good = {'Client': {'Version': '29.4.2'}, 'Server': {'Version': '29.4.2', 'Os': 'linux'}}
        for payload, compose, expected in [('not json', '5.1.3', 'docker_client'),
                ('{}', '5.1.3', 'docker_client'),
                (json.dumps({'Client': good['Client']}), '5.1.3', 'docker_server'),
                (json.dumps({'Client': good['Client'], 'Server': {'Version': '29.4.2'}}), '5.1.3', 'docker_os'),
                (json.dumps(good), 'private-output', 'docker_compose')]:
            with self.subTest(expected=expected), patch.object(Path, 'resolve', return_value=Path('/run/docker.sock')), \
                    patch.object(Path, 'stat', return_value=SimpleNamespace(st_mode=stat.S_IFSOCK, st_uid=0)), \
                    patch.object(repro, 'command', side_effect=[(payload, ''), (compose, '')]), \
                    self.assertRaises(repro.VersionMismatch) as error:
                repro.docker_preflight()
            self.assertEqual(error.exception.fields['component'], expected)
            self.assertEqual(error.exception.fields['actual_version'], 'UNKNOWN')



if __name__ == '__main__':
    unittest.main(verbosity=2)
