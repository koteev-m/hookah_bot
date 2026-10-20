"""Focused portable tests: exact CI, migrations, boundaries, intent/recovery and locks."""
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import simple_staging as s


def config():
    return {'host': 'fixture', 'architecture': 'amd64', 'postgres_image': 'postgres@sha256:' + '1' * 64,
            'postgres_password': 'p' * 40, 'runtime': {
                'API_SESSION_JWT_SECRET': 's' * 40, 'TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER': 't' * 40,
                'VENUE_STAFF_INVITE_SECRET_PEPPER': 'v' * 40, 'TELEGRAM_BOT_ENABLED': 'false',
                'TELEGRAM_BOT_TOKEN': '', 'TELEGRAM_BOT_USERNAME': '', 'PLATFORM_OWNER_TELEGRAM_ID': ''}}


def image(letter='a', count=126):
    return {'image': 'ghcr.io/koteev-m/hookah_bot/backend@sha256:' + letter * 64,
            'id': 'sha256:' + letter * 64, 'revision': letter * 40, 'ci_run': 1,
            'manifest': [{'version': n, 'script': f'V{n}__fixture.sql', 'sha256': str(n), 'checksum': n} for n in range(1, count + 1)],
            'ci': {'id': 1}}


def record(img):
    return {k: img[k] for k in ('image', 'revision', 'ci_run')}


class MemoryStaging(s.Staging):
    """Fake Docker transport; all install/update orchestration is production code."""
    def __init__(self, root):
        s.atomic(root / 'config.json', config())
        super().__init__(root, runner=self.command)
        self.calls = []
        self.images = {image(c)['image']: image(c) for c in 'abc'}
        self.items = []
        self.pg = None
        self.rows = []
        self.app = None
        self.payload = ['guest-existing-order']
        self.bad = False
        self.backups = []
        self.neighbor = {'container': 'clubs', 'volume': 'clubs_pg', 'data': ['sentinel']}

    def host_check(self): pass
    def command(self, argv, **kwargs):
        self.calls.append(argv)
        return b''
    def candidate(self, rec): return copy.deepcopy(self.images[rec['image']])
    def inspect_image(self, ref, **kwargs): return {'Config': {'Env': ['PG_MAJOR=17']}}
    def containers(self): return self.items
    def binding(self):
        s.require(self.pg is not None, 'POSTGRES_IDENTITY_UNKNOWN')
        return copy.deepcopy(self.pg)
    def history(self): return copy.deepcopy(self.rows)
    def observe(self, target, binding):
        s.require(self.binding() == binding, 'POSTGRES_BINDING_CHANGED')
        s.require(self.history() == s.history_for(target['manifest']), 'DATABASE_HISTORY_MISMATCH')
        s.require(self.app == target['id'], 'BACKEND_IMAGE_NOT_RUNNING')
    def ready(self, target): s.require(not self.bad, 'READINESS_FAILED')
    def compose(self, *args, **kwargs):
        self.calls.append(list(args))
        if args[-1] == 'postgres':
            self.pg = {'container': 'db-original', 'system_id': 'system-original', 'volume': 'pg-original'}
            self.items = [{'postgres': True}]
        elif args[0] == 'up' and args[-1] == 'backend':
            target = self.state()['target']
            self.app = target['id']; self.rows = s.history_for(target['manifest'])
        return b''
    def backup(self, binding):
        self.calls.append(['backup'])
        self.backups.append(copy.deepcopy(self.payload))
        return {'path': 'fixture.dump', 'sha256': 'b' * 64}


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.r = MemoryStaging(self.root)
    def tearDown(self): self.temp.cleanup()
    def install(self): return self.r.apply('install', record(image()))
    def test_empty_to_v126_and_repeated_install_preserves_data(self):
        self.assertEqual(self.install()['schema'], 126)
        self.r.payload.append('retained-order')
        before = copy.deepcopy(self.r.pg); calls = len(self.r.calls)
        self.assertTrue(self.install()['unchanged'])
        self.assertEqual(len(self.r.calls), calls)
        self.assertEqual(self.r.pg, before)
        self.assertEqual(self.r.payload[-1], 'retained-order')
    def test_ordinary_update_only_backend_and_no_neighbor_mutations(self):
        self.install(); start = len(self.r.calls)
        pg = copy.deepcopy(self.r.pg); neighbor = copy.deepcopy(self.r.neighbor)
        self.assertEqual(self.r.apply('deploy', record(image('b')))['status'], 'SUCCESS')
        self.assertEqual(self.r.pg, pg); self.assertEqual(self.r.neighbor, neighbor)
        self.assertEqual(self.r.calls[start:], [['up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--force-recreate', 'backend']])
        self.assertEqual(self.r.payload, ['guest-existing-order'])
    def test_forward_requires_review_then_backup_and_forbids_rollback(self):
        self.install(); target = image('b', 127); self.r.images[target['image']] = target
        with self.assertRaisesRegex(s.Refused, 'REVIEW'): self.r.apply('deploy', record(target))
        review = {'from_manifest': s.digest(image()['manifest']), 'to_manifest': s.digest(target['manifest']),
                  'backward_compatible': True, 'reason': 'Reviewed additive nullable column, old app remains compatible.'}
        self.r.apply('deploy', record(target), review)
        self.assertEqual(self.r.backups, [['guest-existing-order']])
        self.assertLess(self.r.calls.index(['stop', '--timeout', '30', 'backend']), self.r.calls.index(['backup']))
        self.assertEqual(self.r.calls[-1][-1], 'backend')
        with self.assertRaisesRegex(s.Refused, 'SCHEMA_ROLLBACK'): self.r.apply('rollback')
    def test_changed_historical_sql_rejected(self):
        self.install(); target = self.r.images[image('b')['image']]; target['manifest'][0]['sha256'] = 'changed'
        with self.assertRaisesRegex(s.Refused, 'APPEND_ONLY'): self.r.apply('deploy', record(target))
        self.assertEqual(self.r.app, image()['id'])
    def test_failure_blocks_retry_reconcile_verifies_then_accepts(self):
        self.install(); self.r.bad = True
        with self.assertRaisesRegex(s.Refused, 'READINESS'): self.r.apply('deploy', record(image('b')))
        self.assertEqual(self.r.state()['status'], 'UNKNOWN')
        with self.assertRaisesRegex(s.Refused, 'UNFINISHED'): self.r.apply('deploy', record(image('c')))
        with self.assertRaisesRegex(s.Refused, 'READINESS'): self.r.apply('reconcile')
        self.r.bad = False
        self.assertEqual(self.r.apply('reconcile')['status'], 'SUCCESS')
    def test_failed_update_can_explicitly_rollback_unchanged_schema(self):
        self.install(); self.r.bad = True
        with self.assertRaises(s.Refused): self.r.apply('deploy', record(image('b')))
        self.r.bad = False
        self.r.apply('rollback'); self.assertEqual(self.r.app, image()['id'])
        self.assertEqual(self.r.payload, ['guest-existing-order'])
    def test_unknown_after_command_never_loses_intent(self):
        self.install()
        with patch.object(self.r, 'compose', side_effect=s.Refused('COMMAND_TIMEOUT_OUTCOME_UNKNOWN')):
            with self.assertRaises(s.Refused): self.r.apply('deploy', record(image('b')))
        self.assertEqual(self.r.state()['status'], 'UNKNOWN')
        with self.assertRaisesRegex(s.Refused, 'BACKEND'): self.r.apply('reconcile')
        self.r.apply('rollback')
    def test_db_drift_and_config_drift_refused(self):
        self.install(); self.r.pg['container'] = 'replacement'
        with self.assertRaisesRegex(s.Refused, 'BINDING'): self.r.apply('deploy', record(image('b')))
        self.r.config['postgres_password'] = 'z' * 40
        with self.assertRaisesRegex(s.Refused, 'CONFIG'): self.r.apply('backup')
    def test_initial_enabled_telegram_and_non_v126_refused(self):
        self.r.config['runtime']['TELEGRAM_BOT_ENABLED'] = 'true'
        with self.assertRaisesRegex(s.Refused, 'TELEGRAM'): self.install()
        self.r.config['runtime']['TELEGRAM_BOT_ENABLED'] = 'false'
        self.r.images[image()['image']] = image('a', 127)
        with self.assertRaisesRegex(s.Refused, 'EXACT_V126'): self.install()
    def test_backup_failure_prevents_schema_change(self):
        self.install(); target = image('b', 127); self.r.images[target['image']] = target
        review = dict(from_manifest=s.digest(image()['manifest']), to_manifest=s.digest(target['manifest']),
                      backward_compatible=True, reason='Additive reviewed migration with compatibility retained')
        with patch.object(self.r, 'backup', side_effect=s.Refused('BACKUP_FAILED')):
            with self.assertRaisesRegex(s.Refused, 'BACKUP_FAILED'): self.r.apply('deploy', record(target), review)
        self.assertEqual(len(self.r.rows), 126); self.assertEqual(self.r.app, image()['id'])
    def test_tampered_compose_refused(self):
        self.install(); s.atomic(self.r.compose_file, {'services': {'clubs': {}}})
        with self.assertRaisesRegex(s.Refused, 'COMPOSE_CHANGED'): self.r.apply('deploy', record(image('b')))


class ContractTests(unittest.TestCase):
    def test_wrong_digest_architecture_and_source_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve(); s.atomic(root / 'config.json', config())
            ref = image()['image']
            data = {'Os': 'linux', 'Architecture': 'amd64', 'RepoDigests': [ref], 'Id': image()['id'],
                    'Config': {'Labels': {'org.opencontainers.image.source': s.SOURCE,
                                          'org.opencontainers.image.revision': 'a' * 40}}}
            calls = []
            def runner(argv, **kwargs):
                calls.append(argv)
                return json.dumps([data]).encode() if 'inspect' in argv else b''
            runtime = s.Staging(root, runner)
            self.assertEqual(runtime.inspect_image(ref)['Id'], image()['id'])
            data['RepoDigests'] = ['foreign@sha256:' + 'f' * 64]
            with self.assertRaisesRegex(s.Refused, 'DIGEST'): runtime.inspect_image(ref)
            data['RepoDigests'] = [ref]; data['Architecture'] = 'arm64'
            with self.assertRaisesRegex(s.Refused, 'ARCHITECTURE'): runtime.inspect_image(ref)
            data['Architecture'] = 'amd64'; data['Config']['Labels']['org.opencontainers.image.source'] = 'foreign'
            with patch.object(s, 'verify_ci', return_value={'id': 1}):
                with self.assertRaisesRegex(s.Refused, 'SOURCE_REVISION'): runtime.candidate(record(image()))
            self.assertFalse(any('create' in call for call in calls))

    def test_ci_wrong_repository_and_incomplete_jobs_refused(self):
        def runner(argv, **kwargs):
            return json.dumps({'head_sha': 'a' * 40, 'event': 'push', 'path': '.github/workflows/ci.yml',
                               'repository': {'full_name': 'other/repository'}, 'status': 'completed',
                               'conclusion': 'success'}).encode()
        with self.assertRaisesRegex(s.Refused, 'EXACT_CI'): s.verify_ci('a' * 40, 1, runner)

    def test_wrong_host_refused_before_docker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve(); s.atomic(root / 'config.json', config())
            calls = []
            runtime = s.Staging(root, lambda *a, **kw: calls.append(a))
            with patch.object(s.sys, 'platform', 'linux'), patch.object(s.os, 'geteuid', return_value=0), \
                    patch.object(s.socket, 'gethostname', return_value='wrong-host'):
                with self.assertRaisesRegex(s.Refused, 'WRONG_HOST'): runtime.host_check()
            self.assertEqual(calls, [])

    def test_missing_migrations_refused(self):
        for count in (0, 125):
            raw = io.BytesIO()
            with zipfile.ZipFile(raw, 'w') as jar:
                for n in range(1, count + 1):
                    jar.writestr(f'db/migration/postgresql/V{n}__fixture.sql', 'SELECT 1;\n')
            with self.assertRaisesRegex(s.Refused, 'V126'): s.manifest(raw.getvalue())

    def test_compose_isolation_and_secrets_not_interpolated(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = MemoryStaging(Path(tmp).resolve()); doc = r.document(image())
            self.assertEqual(set(doc['services']), {'postgres', 'backend'})
            self.assertNotIn('ports', doc['services']['postgres'])
            self.assertEqual(doc['services']['backend']['ports'], ['127.0.0.1:8080:8080'])
            self.assertNotIn('external', json.dumps(doc)); self.assertNotIn('8081', json.dumps(doc))
            self.assertEqual(doc['services']['backend']['environment']['TELEGRAM_BOT_ENABLED'], 'false')
    def test_bad_configs_refused(self):
        for key, value in [('postgres_image', 'postgres:17'), ('postgres_password', 'change-me'), ('architecture', 'unknown')]:
            c = config(); c[key] = value
            with self.subTest(key=key), self.assertRaises(s.Refused): s.validate_config(c)
        c = config(); c['runtime']['DB_JDBC_URL'] = 'foreign'
        with self.assertRaises(s.Refused): s.validate_config(c)
    def test_real_migration_manifest_v1_to_v126(self):
        raw = io.BytesIO()
        root = Path(__file__).resolve().parents[1] / 'backend/app/src/main/resources/db/migration/postgresql'
        with zipfile.ZipFile(raw, 'w') as out:
            for p in root.glob('*.sql'): out.writestr('db/migration/postgresql/' + p.name, p.read_bytes())
        rows = s.manifest(raw.getvalue()); self.assertEqual(len(rows), 126)
        self.assertEqual(rows[-1]['version'], 126)
    def test_lock_conflict_bounded_and_unlock(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp).resolve() / 'shared.lock'
            with s.shared_lock(path, 0):
                code = 'import simple_staging as s; from pathlib import Path\nwith s.shared_lock(Path(__import__("sys").argv[1]),0): pass'
                p = subprocess.run([sys.executable, '-B', '-c', code, str(path)], cwd=Path(__file__).parent, capture_output=True)
                self.assertNotEqual(p.returncode, 0); self.assertIn(b'SHARED_DOCKER_LOCK_BUSY', p.stderr)
            with s.shared_lock(path, 0): pass
    def test_ci_exact_sha_completed_success_and_all_jobs(self):
        info = {'head_sha': 'a' * 40, 'event': 'push', 'path': '.github/workflows/ci.yml',
                'repository': {'full_name': s.REPO}, 'head_repository': {'full_name': s.REPO},
                'status': 'completed', 'conclusion': 'success', 'html_url': 'https://github.com/' + s.REPO + '/actions/runs/1'}
        jobs = [{'name': k + ' (21)', 'conclusion': 'success'} for k in s.REQUIRED_JOBS]
        def runner(argv, **kw): return json.dumps([{'jobs': jobs}] if '--paginate' in argv else info).encode()
        self.assertEqual(s.verify_ci('a' * 40, 1, runner)['sha'], 'a' * 40)
        for key, value in [('head_sha', 'b' * 40), ('event', 'pull_request'), ('status', 'queued'), ('conclusion', 'failure')]:
            old = info[key]; info[key] = value
            with self.subTest(key=key), self.assertRaises(s.Refused): s.verify_ci('a' * 40, 1, runner)
            info[key] = old
        jobs.pop()
        with self.assertRaisesRegex(s.Refused, 'JOB'): s.verify_ci('a' * 40, 1, runner)
    def test_bad_image_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve(); s.atomic(root / 'config.json', config())
            calls = []
            runtime = s.Staging(root, runner=lambda *a, **k: calls.append(a))
            with self.assertRaisesRegex(s.Refused, 'IMMUTABLE'): runtime.candidate(dict(image='clubs:latest', revision='a' * 40, ci_run=1))
            self.assertEqual(calls, [])


if __name__ == '__main__': unittest.main()
