#!/usr/bin/env python3
"""Ordinary-deploy fence regressions; no daemon, SSH, deployment or image build.

The production guard and real `docker compose config` are retained. On a normal
local run, only geteuid and lstat uid/gid are adapted for private fixture paths;
mode/type/link checks use actual filesystem objects. Metadata rejection tests
also call the real guard with explicitly synthetic stat records. The optional
--require-linux-root-compose mode needs a disposable GitHub Ubuntu runner and
root, uses real root metadata, and performs only daemon-free Compose rendering.

Deploy ordering is source-bound and executes the actual image preflight shell
segment with an explicit image-inspect fixture. The separate ordinary-deploy suite
covers shared-protocol integration. This guard-only suite claims no deployment,
retirement, reboot or operational DR.
"""
import argparse
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


sys.dont_write_bytecode = True

REPO = Path(__file__).resolve().parents[1]
GUARD = REPO / 'scripts/check-staging-operational-handoff.py'
DEPLOY = Path(os.environ.get('V126_HANDOFF_DEPLOY_SOURCE', REPO / 'scripts/deploy-staging.sh'))
IMAGE = 'fixture/backend:' + '1' * 40
IMAGE_ID = 'sha256:' + '2' * 64
SECRET = 'synthetic-handoff-secret-do-not-print'
REAL_ROOT = False
spec = importlib.util.spec_from_file_location('operational_handoff_guard', GUARD)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class RootMetadataPath(type(Path())):
    """Adapt only ownership; all mode/type/link bytes remain from own files."""

    def lstat(self, *args, **kwargs):
        fields = list(super().lstat(*args, **kwargs))
        fields[4:6] = [0, 0]
        return os.stat_result(fields)


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-operational-handoff-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.target = self.root / 'target'
        self.target.mkdir(mode=0o755)
        self.envfile = self.target / '.env'
        self.envfile.write_text(f'BACKEND_IMAGE={IMAGE}\nSYNTHETIC_SECRET={SECRET}\n')
        self.envfile.chmod(0o600)
        self.compose = self.target / 'docker-compose.yml'
        self.compose.write_text('''services:
  backend:
    image: ${BACKEND_IMAGE:?fixed exact image required}
    restart: unless-stopped
    env_file: .env
''')
        self.compose.chmod(0o644)

    def check(self, target=None):
        selected = target or self.target
        output = io.StringIO()
        identity = contextlib.nullcontext() if REAL_ROOT else mock.patch.object(guard.os, 'geteuid', return_value=0)
        path = selected if REAL_ROOT else RootMetadataPath(selected)
        with identity, contextlib.redirect_stdout(output):
            guard.check(path, IMAGE)
        return output.getvalue()

    def test_raw_environment_contract(self):
        guard.validate_environment(f'# preserved comment\nBACKEND_IMAGE={IMAGE}\nOTHER=untouched\n', IMAGE)
        for raw in ('', f'BACKEND_IMAGE={IMAGE}\nBACKEND_IMAGE={IMAGE}\n',
                    f'BACKEND_IMAGE="{IMAGE}"\n', f'export BACKEND_IMAGE={IMAGE}\n',
                    f'BACKEND_IMAGE= {IMAGE}\n', f'BACKEND_IMAGE={IMAGE} \n',
                    'BACKEND_IMAGE=fixture/backend:latest\n', f'BACKEND_IMAGE={IMAGE}\r\n'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                guard.validate_environment(raw, IMAGE)
        for expected in ('fixture:latest', 'fixture:' + 'a' * 39, 'Fixture:' + 'a' * 40,
                         'fixture:' + 'A' * 40, 'fixture:' + 'a' * 41):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                guard.validate_environment('BACKEND_IMAGE=' + expected, expected)

    def test_metadata_guard_fields(self):
        def record(mode=stat.S_IFREG | 0o600, uid=0, gid=0, links=1):
            data = SimpleNamespace(st_mode=mode, st_uid=uid, st_gid=gid, st_nlink=links)
            return SimpleNamespace(lstat=lambda: data)
        guard.protected(record(), environment=True)
        guard.protected(record(mode=stat.S_IFREG | 0o644))
        guard.protected(record(mode=stat.S_IFDIR | 0o755), directory=True)
        for changes in ({'uid': 501}, {'gid': 20}, {'links': 2},
                        {'mode': stat.S_IFREG | 0o644}, {'mode': stat.S_IFREG | 0o660},
                        {'mode': stat.S_IFLNK | 0o777}, {'mode': stat.S_IFDIR | 0o700}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                guard.protected(record(**changes), environment=True)
        for mode in (stat.S_IFDIR | 0o775, stat.S_IFDIR | 0o777, stat.S_IFREG | 0o755):
            with self.subTest(directory_mode=mode), self.assertRaises(ValueError):
                guard.protected(record(mode=mode), directory=True)
        with mock.patch.object(guard.os, 'geteuid', return_value=501), self.assertRaisesRegex(ValueError, 'deployment identity'):
            guard.check(self.target, IMAGE)

    def test_real_compose_fixed_image_restart_and_ambient_override(self):
        before = {path: (path.read_bytes(), path.stat().st_mode, path.stat().st_uid,
                         path.stat().st_gid, path.stat().st_mtime_ns) for path in (self.envfile, self.compose)}
        with mock.patch.dict(os.environ, {'BACKEND_IMAGE': 'ambient:wrong', 'COMPOSE_FILE': '/no/such/file',
                                         'COMPOSE_PROJECT_NAME': 'unrelated-ambient-project'}):
            self.assertEqual(self.check(), 'OPERATIONAL_DEPLOY_PREFLIGHT=PASS migration_target=false\n')
        self.assertEqual(before, {path: (path.read_bytes(), path.stat().st_mode, path.stat().st_uid,
                                        path.stat().st_gid, path.stat().st_mtime_ns) for path in before})
        for value in ('no', 'always'):
            self.compose.write_text(self.compose.read_text().replace('unless-stopped', value))
            with self.subTest(restart=value), self.assertRaises(ValueError):
                self.check()
            self.compose.write_text(self.compose.read_text().replace('restart: ' + value, 'restart: unless-stopped'))
        self.compose.write_text(self.compose.read_text().replace('${BACKEND_IMAGE:?fixed exact image required}', 'fixture:wrong'))
        with self.assertRaisesRegex(ValueError, 'effective operational'):
            self.check()

    def test_all_registry_records_refuse_before_compose(self):
        registry = self.target / '.v126-target-operations'
        for state in ('empty', 'active', 'retired', 'unknown', 'regular-file', 'dangling-symlink'):
            with self.subTest(state=state):
                if state == 'regular-file':
                    registry.write_text('retired')
                elif state == 'dangling-symlink':
                    registry.symlink_to(self.target / 'absent-registry')
                else:
                    registry.mkdir()
                    if state != 'empty':
                        (registry / 'operation.json').write_text(json.dumps({'state': state}))
                with mock.patch.object(guard.subprocess, 'run', side_effect=AssertionError('Compose reached after registry refusal')):
                    with self.assertRaisesRegex(ValueError, 'protocol-aware'):
                        self.check()
                if registry.is_dir():
                    shutil.rmtree(registry)
                else:
                    registry.unlink()

    def test_actual_filesystem_type_mode_links_and_canonical_target(self):
        self.envfile.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'private'):
            self.check()
        self.envfile.chmod(0o600)
        second_link = self.target / 'environment-hardlink'
        os.link(self.envfile, second_link)
        with self.assertRaisesRegex(ValueError, 'one link'):
            self.check()
        second_link.unlink()
        saved_env = self.target / 'saved-env'
        self.envfile.rename(saved_env)
        self.envfile.symlink_to(saved_env)
        with self.assertRaisesRegex(ValueError, 'nonsymlink'):
            self.check()
        self.envfile.unlink()
        saved_env.rename(self.envfile)
        saved_compose = self.target / 'saved-compose'
        self.compose.rename(saved_compose)
        self.compose.symlink_to(saved_compose)
        with self.assertRaisesRegex(ValueError, 'nonsymlink'):
            self.check()
        alias = self.root / 'target-alias'
        alias.symlink_to(self.target, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'canonical target'):
            self.check(alias)

    def test_compose_producer_faults_refuse_and_do_not_print_secrets(self):
        valid = json.dumps({'services': {'backend': {'image': IMAGE, 'restart': 'unless-stopped'}}}).encode()
        faults = (subprocess.CompletedProcess([], 42, valid, SECRET.encode()),
                  subprocess.CompletedProcess([], 0, b'not-json', SECRET.encode()),
                  subprocess.CompletedProcess([], 0, b'{}', SECRET.encode()))
        for result in faults:
            with self.subTest(result=result.returncode), mock.patch.object(guard.subprocess, 'run', return_value=result) as process:
                output = io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output), self.assertRaises((ValueError, KeyError)):
                    self.check()
                self.assertNotIn(SECRET, output.getvalue())
                argv = process.call_args.args[0]
                self.assertEqual(argv, ['docker', 'compose', '--env-file', str(self.envfile), '-f', str(self.compose), 'config', '--format', 'json'])
                self.assertEqual(process.call_args.kwargs['timeout'], 20)
                self.assertTrue(process.call_args.kwargs['capture_output'])
                self.assertLessEqual(set(process.call_args.kwargs['env']), {'PATH', 'HOME'})
        with mock.patch.object(guard.subprocess, 'run', side_effect=subprocess.TimeoutExpired('docker compose config', 20)):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.check()

    def test_deploy_source_order_and_actual_refusal_segment(self):
        source = DEPLOY.read_text()
        transport = 'python3 "${SCRIPT_DIR}/v126-ordinary-deploy.py" client'
        self.assertEqual(source.count(transport), 1, 'one supervised lifecycle is required')
        before, after = source.split(transport, 1)
        self.assertNotRegex(source, r'(?m)^\s*(?:ssh|rsync)\s')
        self.assertIn('set -euo pipefail', before)
        self.assertIn('check-staging-image-identity.sh', before)
        self.assertIn('APPROVED_DEPLOYMENT_FILE', before)
        self.assertIn('DEPLOY_STATE_DIR', before)
        self.assertIn('--request-file "${APPROVED_DEPLOYMENT_FILE}"', after)
        self.assertIn('--expected-image-id "${EXPECTED_BACKEND_IMAGE_ID}"', after)
        # Execute the real local image consumer in the actual caller segment.
        # A valid-looking wrong Docker ID must stop before client or SSH dispatch.
        segment = source[source.index('## This comparison must stay'):source.index('# One remote invocation')]
        marker = self.root / 'mutation-reached'
        shell = """set -euo pipefail
SCRIPT_DIR="$1"; BACKEND_IMAGE="$2"; EXPECTED_BACKEND_IMAGE_ID="$3"
STAGING_ARTIFACT_PREFLIGHT_ONLY=false
docker() { [[ "$*" == "image inspect --format {{.Id}} $BACKEND_IMAGE" ]] || return 91; printf '%s\\n' 'sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'; }
"""
        result = subprocess.run(['bash', '-c', shell + segment + '\ntouch "$4"\n', 'fixture',
                                 str(REPO / 'scripts'), IMAGE, IMAGE_ID, str(marker)],
                                text=True, capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 4, result.stdout + result.stderr)
        self.assertIn('does not match', result.stderr)
        self.assertNotIn(SECRET, result.stdout + result.stderr)
        self.assertFalse(marker.exists(), 'deploy continued after real image guard refusal')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--require-linux-root-compose', action='store_true')
    args = parser.parse_args()
    REAL_ROOT = args.require_linux_root_compose
    if REAL_ROOT and not (sys.platform == 'linux' and os.geteuid() == 0 and
                          os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('RUNNER_OS') == 'Linux'):
        parser.error('real root metadata mode is restricted to the owned disposable GitHub Linux runner')
    if not shutil.which('docker'):
        parser.error('real Docker Compose CLI is required; this test never connects to a daemon')
    version = subprocess.run(['docker', 'compose', 'version'], capture_output=True, text=True, timeout=20)
    if version.returncode:
        parser.error('real Docker Compose config is required')
    print(version.stdout.strip(), flush=True)
    print('OWNERSHIP=' + ('REAL_ROOT' if REAL_ROOT else 'EXPLICIT_LOCAL_UID_GID_ADAPTER'), flush=True)
    unittest.main(argv=[sys.argv[0]], verbosity=2)
