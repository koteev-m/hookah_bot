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
        with tempfile.TemporaryDirectory(prefix='ht-ci656-repro-07-') as temporary:
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
            environment = dict(self.event(), RUNNER_TEMP=str(parent), PRIVATE_TOKEN='not-forwarded')
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
        with tempfile.TemporaryDirectory(prefix='ht-ci656-repro-07-') as temporary:
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
        self.assertEqual(len(job['steps']), 2)
        checkout = job['steps'][0]
        self.assertEqual(checkout['with'], {'ref': '${{ github.sha }}', 'fetch-depth': 0, 'persist-credentials': False})
        self.assertRegex(checkout['uses'], r'^actions/checkout@[0-9a-f]{40}$')
        self.assertIn("github.event.ref_type == 'branch'", job['if'])
        self.assertIn("github.event.ref == '" + repro.BRANCH + "'", job['if'])
        self.assertIn("github.run_attempt == '1'", job['if'])
        for forbidden in ('secrets.', 'id-token', 'upload-artifact', 'workflow_dispatch', 'repository_dispatch',
                          'docker/login', 'docker push', 'continue-on-error', 'retry', 'apt-get'):
            self.assertNotIn(forbidden, raw)
        self.assertIn('python3 -B ' + repro.SELF, job['steps'][1]['run'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
