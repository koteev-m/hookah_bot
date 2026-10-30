"""Focused portable tests: exact CI, migrations, boundaries, intent/recovery and locks."""
import copy
import contextlib
from email.message import Message
import http.client
import io
import json
import os
from pathlib import Path
import ssl
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.response
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


def real_manifest(historical=False):
    root = Path(__file__).resolve().parents[1]
    location = 'backend/app/src/main/resources/db/migration/postgresql'
    raw = io.BytesIO()
    if historical:
        revision = 'ecb09601975678a41d89e5c824cc7812c7876481'
        names = subprocess.check_output(['git', '--no-optional-locks', '-C', str(root),
                                         'ls-tree', '--name-only', revision + ':' + location], text=True).splitlines()
    else:
        names = [p.name for p in (root / location).glob('*.sql')]
    with zipfile.ZipFile(raw, 'w') as out:
        for name in names:
            data = (subprocess.check_output(['git', '--no-optional-locks', '-C', str(root),
                                            'show', revision + ':' + location + '/' + name])
                    if historical else (root / location / name).read_bytes())
            out.writestr('db/migration/postgresql/' + name, data)
    return s.manifest(raw.getvalue())


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


def r2_config(jurisdiction='eu'):
    # Deliberately synthetic; no provider credentials or network calls.
    suffix = 'eu.' if jurisdiction == 'eu' else ''
    return {'STAFF_PHOTOS_R2_ENABLED': 'true',
            'STAFF_PHOTOS_R2_ENDPOINT': 'https://' + 'a' * 32 + '.' + suffix + 'r2.cloudflarestorage.com',
            'STAFF_PHOTOS_R2_BUCKET': 'hookah-tootah-media-staging-eu',
            'STAFF_PHOTOS_R2_ACCESS_KEY': 'fixture-access-key-only',
            'STAFF_PHOTOS_R2_SECRET_KEY': 'fixture-secret-key-only-' * 2,
            'STAFF_PHOTOS_R2_REGION': 'auto', 'STAFF_PHOTOS_R2_JURISDICTION': jurisdiction,
            'STAFF_PHOTOS_MAX_INPUT_BYTES': '5242880'}


class RuntimeStaging(MemoryStaging):
    """Capture the launched environment; exercise the real previous-backend observer."""
    def compose(self, *args, **kwargs):
        result = super().compose(*args, **kwargs)
        if args[0] == 'up' and args[-1] == 'backend':
            self.app_env = copy.deepcopy(s.private_json(self.compose_file)['services']['backend']['environment'])
        return result

    def observe(self, target, binding):
        app = {'Image': self.app, 'State': {'Running': True}, 'Mounts': [],
               'Config': {'Labels': {'com.docker.compose.service': 'backend'},
                          'Env': [k + '=' + v for k, v in self.app_env.items()]},
               'HostConfig': {'PortBindings': {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8080'}]}},
               'NetworkSettings': {'Networks': {'hookah-bot_default': {}}}}
        with patch.object(self, 'containers', return_value=[app]):
            s.Staging.observe(self, target, binding)


class R2ConfigTests(unittest.TestCase):
    def test_old_runtime_is_unchanged_and_r2_defaults_off(self):
        c = config(); original = copy.deepcopy(c)
        s.validate_config(c)
        self.assertEqual(c, original)
        with tempfile.TemporaryDirectory() as tmp:
            r = MemoryStaging(Path(tmp).resolve())
            env = r.document(image())['services']['backend']['environment']
            self.assertFalse(s.PHOTO_RUNTIME_KEYS & set(env))
        for optional in ({'STAFF_PHOTOS_R2_ENABLED': 'false'}, {'STAFF_PHOTOS_MAX_INPUT_BYTES': '1024'}):
            c = config(); c['runtime'].update(optional); s.validate_config(c)
            self.assertNotEqual(c['runtime'].get('STAFF_PHOTOS_R2_ENABLED'), 'true')

    def test_complete_config_reaches_only_backend_for_each_jurisdiction(self):
        for jurisdiction in ('default', 'eu'):
            with self.subTest(jurisdiction=jurisdiction), tempfile.TemporaryDirectory() as tmp:
                r = MemoryStaging(Path(tmp).resolve()); r.config['runtime'].update(r2_config(jurisdiction))
                s.validate_config(r.config)
                doc = r.document(image())
                self.assertEqual({k: v for k, v in doc['services']['backend']['environment'].items()
                                  if k in s.PHOTO_RUNTIME_KEYS}, r2_config(jurisdiction))
                self.assertFalse(s.PHOTO_RUNTIME_KEYS & set(doc['services']['postgres']['environment']))
                self.assertEqual(set(doc['services']), {'postgres', 'backend'})
                self.assertEqual(doc['services']['backend']['ports'], ['127.0.0.1:8080:8080'])

    def test_required_fields_and_explicit_flag_even_for_disabled_connection_block(self):
        for flag in ('true', 'false'):
            for missing in s.R2_CONNECTION_KEYS | {'STAFF_PHOTOS_R2_ENABLED'}:
                c = config(); c['runtime'].update(r2_config()); c['runtime']['STAFF_PHOTOS_R2_ENABLED'] = flag
                del c['runtime'][missing]
                with self.subTest(flag=flag, missing=missing), self.assertRaisesRegex(s.Refused, 'R2_CONFIG_INCOMPLETE'):
                    s.validate_config(c)
        c = config(); c['runtime']['STAFF_PHOTOS_R2_ENABLED'] = 'true'
        with self.assertRaisesRegex(s.Refused, 'R2_CONFIG_INCOMPLETE'): s.validate_config(c)
        c = config(); c['runtime'].update(r2_config()); del c['runtime']['STAFF_PHOTOS_MAX_INPUT_BYTES']
        s.validate_config(c)

    def test_invalid_fields_fail_closed_without_secret_values(self):
        endpoint = r2_config()['STAFF_PHOTOS_R2_ENDPOINT']
        bad = {
            'STAFF_PHOTOS_R2_ENABLED': ['TRUE', '', True, None],
            'STAFF_PHOTOS_R2_ENDPOINT': ['http://' + endpoint[8:], endpoint + '/', endpoint + '?x=1',
                endpoint + '#x', endpoint + ':443', endpoint.replace('https://', 'https://user@'),
                endpoint.replace('.eu.', '.'), 'https://example.com',
                endpoint.replace('a' * 32, 'a' * 31), endpoint + '.evil.invalid'],
            'STAFF_PHOTOS_R2_JURISDICTION': ['default', 'EU', '', 'unknown'],
            'STAFF_PHOTOS_R2_BUCKET': ['foreign-bucket', '', 'hookah-tootah-media-staging-eu/'],
            'STAFF_PHOTOS_R2_REGION': ['', 'eu', 'us-east-1'],
            'STAFF_PHOTOS_R2_ACCESS_KEY': ['', 'short', 'a' * 129, 'bad key' * 8, 'x' * 32 + '\x00'],
            'STAFF_PHOTOS_R2_SECRET_KEY': ['', 'short', 'x' * 257, 'x' * 32 + '\n',
                'x' * 32 + '\r', 'x' * 32 + '$value', 'x' * 32 + ' ', 'x' * 32 + '\t'],
            'STAFF_PHOTOS_MAX_INPUT_BYTES': ['0', '1023', '5242881', '-1', '1.5', '1e6', ' 1024',
                '01024', '9' * 10000, 1024],
            'STAFF_PHOTOS_R2_UNKNOWN': ['value'], 'AWS_SECRET_ACCESS_KEY': ['value'],
        }
        for key, values in bad.items():
            for index, value in enumerate(values):
                c = config(); c['runtime'].update(r2_config()); c['runtime'][key] = value
                with self.subTest(key=key, case=index):
                    with self.assertRaises(s.Refused) as raised: s.validate_config(c)
                    self.assertRegex(str(raised.exception), r'^[A-Z0-9_]+$')
                    for secret in r2_config().values():
                        if secret.startswith('fixture-'): self.assertNotIn(secret, str(raised.exception))

    def test_cli_errors_are_redacted(self):
        c = config(); c['runtime'].update(r2_config()); c['runtime']['STAFF_PHOTOS_R2_SECRET_KEY'] += ' bad'
        out = io.StringIO()
        with patch.object(sys, 'argv', ['simple_staging', 'deploy', '--directory', '/synthetic']), \
                patch.object(s.sys, 'platform', 'linux'), patch.object(s.os, 'geteuid', return_value=0), \
                patch.object(s, 'shared_lock', return_value=contextlib.nullcontext()), \
                patch.object(s, 'Staging', side_effect=lambda _: s.validate_config(c)), contextlib.redirect_stdout(out):
            self.assertEqual(s.main(), 1)
        self.assertEqual(json.loads(out.getvalue())['reason'], 'R2_CREDENTIALS_INVALID')
        self.assertNotIn('fixture-access', out.getvalue()); self.assertNotIn('fixture-secret', out.getvalue())


class R2TransitionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.r = RuntimeStaging(Path(self.temp.name).resolve())
        self.r.apply('install', record(image()))

    def enable(self):
        self.r.config['runtime'].update(r2_config()); s.validate_config(self.r.config)

    def test_old_to_r2_rotation_disable_and_removal_observe_actual_previous_backend(self):
        pg, neighbor = copy.deepcopy(self.r.pg), copy.deepcopy(self.r.neighbor)
        for change in ('enable', 'rotate', 'disable', 'remove'):
            with self.subTest(change=change):
                old_env = copy.deepcopy(self.r.app_env)
                if change == 'enable': self.enable()
                elif change == 'rotate': self.r.config['runtime']['STAFF_PHOTOS_R2_SECRET_KEY'] = 'rotated-fixture-secret-' * 3
                elif change == 'disable': self.r.config['runtime']['STAFF_PHOTOS_R2_ENABLED'] = 'false'
                else: self.r.config['runtime'] = config()['runtime']
                s.validate_config(self.r.config)
                calls = len(self.r.calls)
                with patch.object(self.r, 'observe', wraps=self.r.observe) as observer:
                    result = self.r.apply('deploy', record(image('b')), apply_config=True)
                self.assertEqual(result['status'], 'SUCCESS'); self.assertEqual(observer.call_count, 2)
                self.assertEqual(self.r.app_env, self.r.document(image('b'))['services']['backend']['environment'])
                self.assertEqual(self.r.state()['runtime_hash'], s.digest(self.r.config['runtime']))
                self.assertEqual(self.r.calls[calls:], [['up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--force-recreate', 'backend']])
                self.assertNotEqual(self.r.app_env, old_env)
                self.assertEqual(self.r.pg, pg); self.assertEqual(self.r.neighbor, neighbor)
                for name in ('result.json', 'operation-' + self.r.state()['operation'] + '.json'):
                    text = (self.r.root / name).read_text()
                    self.assertNotIn('fixture-secret', text); self.assertNotIn('fixture-access', text)
                self.r.apply('deploy', record(image('b')))  # Unchanged runtime needs no flag.

    def test_without_apply_config_refuses_before_writes(self):
        before = self.r.state(); doc = self.r.compose_file.read_bytes(); calls = len(self.r.calls)
        self.enable()
        with self.assertRaisesRegex(s.Refused, 'RUNTIME_CONFIG_CHANGED_USE_APPLY_CONFIG'):
            self.r.apply('deploy', record(image('b')))
        self.assertEqual(self.r.state(), before); self.assertEqual(self.r.compose_file.read_bytes(), doc)
        self.assertEqual(len(self.r.calls), calls)

    def test_prior_hash_compose_binding_and_actual_runtime_tampering_block(self):
        for kind in ('runtime_hash', 'compose', 'db', 'legacy_env', 'unexpected_r2', 'r2_env'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                r = RuntimeStaging(Path(tmp).resolve()); r.apply('install', record(image()))
                if kind == 'r2_env':
                    r.config['runtime'].update(r2_config()); r.apply('deploy', record(image()), apply_config=True)
                    r.app_env['STAFF_PHOTOS_R2_SECRET_KEY'] = 'wrong-fixture-secret-' * 3
                elif kind == 'runtime_hash':
                    state = r.state(); state['runtime_hash'] = '0' * 64; s.atomic(r.state_file, state)
                elif kind == 'compose':
                    doc = s.private_json(r.compose_file); doc['services']['backend']['environment']['API_SESSION_JWT_SECRET'] = 'x' * 40
                    s.atomic(r.compose_file, doc)
                elif kind == 'db': r.pg['container'] = 'replacement'
                elif kind == 'legacy_env': r.app_env['API_SESSION_JWT_SECRET'] = 'wrong'
                else: r.app_env.update(r2_config())
                before = r.state(); doc = r.compose_file.read_bytes(); calls = len(r.calls)
                r.config['runtime'].update(r2_config())
                with self.assertRaises(s.Refused): r.apply('deploy', record(image('b')), apply_config=True)
                self.assertEqual(r.state(), before); self.assertEqual(r.compose_file.read_bytes(), doc)
                self.assertEqual(len(r.calls), calls)
                self.assertEqual(r.config['runtime']['STAFF_PHOTOS_R2_ENABLED'], 'true')

    def test_forward_migration_and_config_change_backup_unknown_and_no_schema_rollback(self):
        self.enable(); target = image('b', 127); self.r.images[target['image']] = target
        review = dict(from_manifest=s.digest(image()['manifest']), to_manifest=s.digest(target['manifest']),
                      backward_compatible=True, reason='Synthetic additive test review, not a staging approval.')
        with self.assertRaisesRegex(s.Refused, 'REVIEW'):
            self.r.apply('deploy', record(target), apply_config=True)
        with patch.object(self.r, 'ready', side_effect=s.Refused('READINESS_FAILED')):
            with self.assertRaisesRegex(s.Refused, 'READINESS_FAILED'):
                self.r.apply('deploy', record(target), review, apply_config=True)
        self.assertEqual(self.r.state()['status'], 'UNKNOWN'); self.assertEqual(len(self.r.backups), 1)
        self.assertLess(self.r.calls.index(['backup']), len(self.r.calls) - 1)
        with self.assertRaisesRegex(s.Refused, 'UNFINISHED'):
            self.r.apply('deploy', record(target), review, apply_config=True)
        self.r.apply('reconcile')
        with self.assertRaisesRegex(s.Refused, 'SCHEMA_ROLLBACK'): self.r.apply('rollback')
        self.assertEqual(self.r.neighbor['data'], ['sentinel'])


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
    def test_info_media_r2_opt_in_reuses_validated_staging_connection_only(self):
        old = config(); before = copy.deepcopy(old); s.validate_config(old); self.assertEqual(old, before)
        c = config(); c['runtime']['VENUE_INFO_MEDIA_R2_ENABLED'] = 'true'
        with self.assertRaisesRegex(s.Refused, 'R2_CONFIG_INCOMPLETE'): s.validate_config(c)
        c['runtime'].update({'STAFF_PHOTOS_R2_ENABLED': 'false', 'STAFF_PHOTOS_R2_ENDPOINT': 'https://' + 'a'*32 + '.eu.r2.cloudflarestorage.com',
                             'STAFF_PHOTOS_R2_BUCKET': 'hookah-tootah-media-staging-eu', 'STAFF_PHOTOS_R2_ACCESS_KEY': 'a'*32,
                             'STAFF_PHOTOS_R2_SECRET_KEY': 'b'*64, 'STAFF_PHOTOS_R2_REGION': 'auto', 'STAFF_PHOTOS_R2_JURISDICTION': 'eu'})
        s.validate_config(c)
        c['runtime']['VENUE_INFO_MEDIA_R2_ENABLED'] = 'maybe'
        with self.assertRaisesRegex(s.Refused, 'INFO_MEDIA_FLAG_INVALID'): s.validate_config(c)

    def test_bad_configs_refused(self):
        for key, value in [('postgres_image', 'postgres:17'), ('postgres_password', 'change-me'), ('architecture', 'unknown')]:
            c = config(); c[key] = value
            with self.subTest(key=key), self.assertRaises(s.Refused): s.validate_config(c)
        c = config(); c['runtime']['DB_JDBC_URL'] = 'foreign'
        with self.assertRaises(s.Refused): s.validate_config(c)
    def test_real_migration_manifest_v1_to_v126(self):
        rows = real_manifest(historical=True)
        self.assertEqual([r['version'] for r in rows], list(range(1, 127)))
        self.assertEqual(rows[-1]['script'], 'V126__support_thread_read_message_cursor.sql')

    def test_current_manifest_is_exact_append_only_v128(self):
        historical, current = real_manifest(True), real_manifest()
        self.assertEqual(current[:126], historical)
        self.assertEqual([r['version'] for r in current], list(range(1, 129)))
        self.assertEqual(current[-2]['script'], 'V127__staff_profile_photos.sql')
        self.assertEqual(current[-1]['script'], 'V128__venue_info_media_objects.sql')
        self.assertEqual(current[-2]['sha256'], '2034cdaf16b62e2f0eeacfc07bfd0d1941cad327cb5e6e7785af5e50ee514846')
        review = dict(from_manifest=s.digest(historical), to_manifest=s.digest(current),
                      backward_compatible=True, reason='Synthetic test review of additive staff photo tables; not staging approval.')
        with self.assertRaisesRegex(s.Refused, 'EXACT_COMPATIBILITY_REVIEW'): s.compatibility(historical, current)
        s.compatibility(historical, current, review)
        for field in ('sha256', 'checksum', 'script'):
            drift = copy.deepcopy(current); drift[0][field] = 'changed'
            with self.subTest(field=field), self.assertRaisesRegex(s.Refused, 'APPEND_ONLY'):
                s.compatibility(historical, drift, review)
        with tempfile.TemporaryDirectory() as tmp:
            runtime = MemoryStaging(Path(tmp).resolve())
            bootstrap, target = image(), image('b', 127)
            bootstrap['manifest'], target['manifest'] = historical, current
            runtime.images.update({bootstrap['image']: bootstrap, target['image']: target})
            with self.assertRaisesRegex(s.Refused, 'EXACT_V126'): runtime.apply('install', record(target))
            self.assertEqual(runtime.calls, [])
            self.assertEqual(runtime.apply('install', record(bootstrap))['schema'], 126)
            with self.assertRaisesRegex(s.Refused, 'REVIEW'): runtime.apply('deploy', record(target))
            self.assertEqual(runtime.history(), s.history_for(historical))
            self.assertEqual(runtime.apply('deploy', record(target), review)['schema'], 128)
            self.assertEqual(runtime.history(), s.history_for(current))
            self.assertEqual(len(runtime.backups), 1)
            with self.assertRaisesRegex(s.Refused, 'SCHEMA_ROLLBACK'): runtime.apply('rollback')

    def test_bad_image_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve(); s.atomic(root / 'config.json', config())
            calls = []
            runtime = s.Staging(root, runner=lambda *a, **k: calls.append(a))
            with self.assertRaisesRegex(s.Refused, 'IMMUTABLE'): runtime.candidate(dict(image='clubs:latest', revision='a' * 40, ci_run=1))
            self.assertEqual(calls, [])


class PublicCiTests(unittest.TestCase):
    def setUp(self):
        self.info = {'id': 1, 'run_attempt': 1, 'head_sha': 'a' * 40, 'event': 'push',
                     'path': '.github/workflows/ci.yml', 'repository': {'full_name': s.REPO},
                     'head_repository': {'full_name': s.REPO}, 'status': 'completed',
                     'conclusion': 'success', 'html_url': s.SOURCE + '/actions/runs/1'}
        self.jobs = [self.job(n, name + ' (21)') for n, name in enumerate(sorted(s.REQUIRED_JOBS), 1)]
        self.base = 'https://api.github.com/repos/' + s.REPO + '/actions/runs/1'
        self.requests = []
        self.transport = patch.object(s.urllib.request.HTTPSHandler, 'https_open', side_effect=self.respond)
        self.http = self.transport.start()
        self.addCleanup(self.transport.stop)
        self.subprocess = patch.object(s.subprocess, 'run', side_effect=AssertionError('CI must not invoke gh'))
        self.subprocess.start()
        self.addCleanup(self.subprocess.stop)

    def job(self, number, name='extra'):
        return {'id': number, 'run_id': 1, 'head_sha': 'a' * 40, 'name': name,
                'status': 'completed', 'conclusion': 'success'}

    def response(self, request, value, *, raw=None, code=200, headers=None):
        raw = json.dumps(value).encode() if raw is None else raw
        metadata = Message()
        for key, value in (headers if headers is not None else {'Content-Length': str(len(raw))}).items():
            metadata[key] = value
        response = urllib.response.addinfourl(io.BytesIO(raw), metadata, request.full_url, code)
        response.msg = 'fixture'
        return response

    def respond(self, request):
        self.requests.append(request)
        self.assertEqual(request.get_method(), 'GET')
        self.assertEqual(request.host, 'api.github.com')
        self.assertFalse(request.has_proxy())
        self.assertGreater(request.timeout, 0)
        self.assertLessEqual(request.timeout, 10)
        headers = {k.lower(): v for k, v in request.header_items()}
        self.assertNotIn('authorization', headers)
        self.assertNotIn('proxy-authorization', headers)
        self.assertEqual(headers['accept'], 'application/vnd.github+json')
        self.assertEqual(headers['x-github-api-version'], '2026-03-10')
        self.assertTrue(headers['user-agent'])
        if request.full_url == self.base:
            return self.response(request, self.info)
        page = int(request.full_url.rsplit('=', 1)[1])
        self.assertEqual(request.full_url, self.base + f'/jobs?filter=latest&per_page=100&page={page}')
        return self.response(request, {'total_count': len(self.jobs), 'jobs': self.jobs[(page - 1) * 100:page * 100]})

    def verify(self):
        return s.verify_ci('a' * 40, '1')

    def test_anonymous_exact_ci_ignores_tokens_proxy_and_gh(self):
        with patch.dict(os.environ, {'GH_TOKEN': 'unused', 'GITHUB_TOKEN': 'unused',
                                   'HTTPS_PROXY': 'http://user:password@invalid:9999'}):
            self.assertEqual(self.verify(), {'id': 1, 'sha': 'a' * 40, 'url': self.info['html_url']})
        self.assertEqual([r.full_url for r in self.requests],
                         [self.base, self.base + '/jobs?filter=latest&per_page=100&page=1', self.base])

    def test_invalid_input_never_requests_network(self):
        for revision, run_id in [('bad', 1), (None, 1)] + [
                ('a' * 40, v) for v in ('', 0, -1, True, '1?x=1', '١', '9' * 100)]:
            with self.subTest(revision=revision, run_id=run_id), self.assertRaisesRegex(s.Refused, 'CI_INPUT'):
                s.verify_ci(revision, run_id)
        self.http.assert_not_called()

    def test_wrong_or_missing_run_identity_and_non_green_run_refused(self):
        bad = [('id', 2), ('id', True), ('id', '1'), ('head_sha', 'b' * 40), ('event', 'pull_request'),
               ('path', '.github/workflows/other.yml'), ('repository', {'full_name': 'other/repo'}),
               ('head_repository', {'full_name': 'other/repo'}), ('repository', None),
               ('html_url', 'https://example.com/run/1'), ('run_attempt', 0), ('run_attempt', True)]
        bad += [('status', v) for v in ('queued', 'in_progress', None)]
        bad += [('conclusion', v) for v in ('failure', 'skipped', 'cancelled', 'timed_out', None)]
        for key, value in bad:
            with self.subTest(key=key, value=value), patch.dict(self.info, {key: value}):
                with self.assertRaisesRegex(s.Refused, 'EXACT_CI'): self.verify()
        for key in list(self.info):
            value = self.info.pop(key)
            with self.subTest(missing=key), self.assertRaisesRegex(s.Refused, 'EXACT_CI'): self.verify()
            self.info[key] = value

    def test_every_required_job_must_be_present(self):
        for job in self.jobs:
            with self.subTest(missing=job['name']), patch.dict(job, {'name': 'unrelated'}):
                with self.assertRaisesRegex(s.Refused, 'JOB_NOT_GREEN'): self.verify()

    def test_non_green_or_incomplete_jobs_including_extra_jobs_refused(self):
        self.jobs.append(self.job(999, 'publish-ghcr'))
        for job in (self.jobs[0], self.jobs[-1]):
            for key, values in [('conclusion', ('failure', 'skipped', 'cancelled', 'timed_out', 'neutral', None)),
                                ('status', ('queued', 'in_progress', None))]:
                for value in values:
                    with self.subTest(job=job['name'], key=key, value=value), patch.dict(job, {key: value}):
                        with self.assertRaisesRegex(s.Refused, 'JOB_NOT_GREEN'): self.verify()
                old = job.pop(key)
                with self.assertRaisesRegex(s.Refused, 'JOB_NOT_GREEN'): self.verify()
                job[key] = old

    def test_job_identity_malformed_or_duplicate_refused(self):
        for key, value in [('id', self.jobs[1]['id']), ('id', True), ('run_id', 2), ('run_id', True),
                           ('head_sha', 'b' * 40), ('name', None), ('name', '')]:
            with self.subTest(key=key, value=value), patch.dict(self.jobs[0], {key: value}):
                with self.assertRaisesRegex(s.Refused, 'JOB_IDENTITY'): self.verify()
        for key in ('id', 'run_id', 'head_sha', 'name'):
            value = self.jobs[0].pop(key)
            with self.subTest(missing=key), self.assertRaisesRegex(s.Refused, 'JOB_IDENTITY'): self.verify()
            self.jobs[0][key] = value
        self.jobs[0] = None
        with self.assertRaisesRegex(s.Refused, 'JOB_IDENTITY'): self.verify()

    def test_pagination_collects_required_jobs_on_last_page(self):
        required = self.jobs[:]
        for count in (100, 101, 200):
            self.jobs = [self.job(1000 + n) for n in range(count - len(required))] + required
            self.requests.clear()
            self.assertEqual(self.verify()['id'], 1)
            self.assertEqual(len(self.requests), 2 + (count + 99) // 100)

    def test_missing_later_page_changed_count_and_duplicate_page_refused(self):
        self.jobs += [self.job(1000 + n) for n in range(86)]  # All required names are on page 1.
        for page in ({'total_count': 101, 'jobs': []}, {'total_count': 102, 'jobs': [self.jobs[-1]]},
                     {'total_count': 101, 'jobs': [self.jobs[0]]}):
            def respond(request):
                return self.response(request, page) if request.full_url.endswith('page=2') else self.respond(request)
            self.http.side_effect = respond
            with self.subTest(page=page), self.assertRaises(s.Refused): self.verify()

    def test_malformed_jobs_response_and_bounded_page_count(self):
        values = [{}, {'jobs': self.jobs}, {'total_count': 15}, {'total_count': 15, 'jobs': {}},
                  {'total_count': 16, 'jobs': self.jobs}]
        values += [{'total_count': v, 'jobs': self.jobs} for v in (0, -1, True, '15', 1001)]
        for value in values:
            self.http.side_effect = lambda req: self.response(req, value) if '/jobs?' in req.full_url else self.respond(req)
            with self.subTest(value=value), self.assertRaisesRegex(s.Refused, 'JOBS_RESPONSE'): self.verify()

    def test_failed_later_page_is_not_ignored(self):
        self.jobs += [self.job(1000 + n) for n in range(86)]
        self.jobs[-1]['conclusion'] = 'failure'
        with self.assertRaisesRegex(s.Refused, 'JOB_NOT_GREEN'): self.verify()

    def test_rerun_or_non_green_run_after_jobs_refused(self):
        for change in ({'run_attempt': 2}, {'status': 'in_progress'}, {'conclusion': 'failure'}, {'id': 2}):
            self.requests.clear()
            def respond(request):
                if request.full_url == self.base and self.requests:
                    return self.response(request, dict(self.info, **change))
                return self.respond(request)
            self.http.side_effect = respond
            with self.subTest(change=change), self.assertRaises(s.Refused): self.verify()

    def test_http_errors_including_rate_limits_fail_without_retry(self):
        required = self.jobs[:]
        for target in ('run', 'jobs', 'last-page', 'final-run'):
            self.jobs = [self.job(1000 + n) for n in range(86)] + required
            for code in (401, 403, 404, 429, 500, 503):
                self.requests.clear()
                self.http.reset_mock()
                def respond(request):
                    fail = (target == 'run' or target == 'jobs' and '/jobs?' in request.full_url
                            or target == 'last-page' and request.full_url.endswith('page=2')
                            or target == 'final-run' and request.full_url == self.base and self.requests)
                    if fail: return self.response(request, {'message': 'private fixture'}, code=code)
                    return self.respond(request)
                self.http.side_effect = respond
                with self.subTest(target=target, code=code), self.assertRaisesRegex(s.Refused, f'CI_API_HTTP_{code}'):
                    self.verify()
                self.assertEqual(self.http.call_count, {'run': 1, 'jobs': 2, 'last-page': 3, 'final-run': 4}[target])

    def test_redirects_and_partial_http_status_refused(self):
        for code in (301, 302, 307, 308, 206):
            self.http.reset_mock()
            self.http.side_effect = lambda req: self.response(req, self.info, code=code,
                                                              headers={'Location': 'https://example.com/ci'})
            with self.subTest(code=code), self.assertRaisesRegex(s.Refused, 'CI_API_'): self.verify()
            self.assertEqual(self.http.call_count, 1)

    def test_network_timeout_tls_and_incomplete_read_refused(self):
        for error in (urllib.error.URLError('private fixture'), TimeoutError(),
                      urllib.error.URLError(ssl.SSLCertVerificationError()),
                      ConnectionResetError(), http.client.IncompleteRead(b'{', 20)):
            self.http.side_effect = error
            with self.subTest(error=type(error).__name__), self.assertRaisesRegex(s.Refused, 'CI_API_UNAVAILABLE'):
                self.verify()
        response = self.response(type('Request', (), {'full_url': self.base})(), self.info)
        response.read1 = lambda size: (_ for _ in ()).throw(http.client.IncompleteRead(b'{', 20))
        self.http.side_effect = lambda req: response
        with self.assertRaisesRegex(s.Refused, 'CI_API_UNAVAILABLE'): self.verify()

    def test_invalid_json_truncation_and_oversized_response_refused(self):
        for raw in (b'{', b'[]', b'null', b'\xff', b'[' * 2000 + b']' * 2000, b' ' * (4 * 1024 * 1024 + 1)):
            self.http.side_effect = lambda req: self.response(req, None, raw=raw, headers={})
            with self.subTest(size=len(raw)), self.assertRaisesRegex(s.Refused, 'CI_API_RESPONSE'): self.verify()
        for length in ('99999', '-1', 'invalid', str(4 * 1024 * 1024 + 1)):
            self.http.side_effect = lambda req: self.response(req, self.info, headers={'Content-Length': length})
            with self.subTest(length=length), self.assertRaisesRegex(s.Refused, 'CI_API_RESPONSE'): self.verify()

    def test_elapsed_deadline_refused(self):
        now = [0]
        def respond(request):
            now[0] = 61
            return self.respond(request)
        self.http.side_effect = respond
        with patch.object(s.time, 'monotonic', side_effect=lambda: now[0]):
            with self.assertRaisesRegex(s.Refused, 'CI_API_TIMEOUT'): self.verify()

    def test_ci_failure_blocks_candidate_before_docker(self):
        self.http.side_effect = TimeoutError()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve(); s.atomic(root / 'config.json', config())
            runtime = s.Staging(root)
            with self.assertRaisesRegex(s.Refused, 'CI_API_UNAVAILABLE'): runtime.candidate(record(image()))

    def test_cli_reports_stop_without_response_or_credentials(self):
        self.http.side_effect = urllib.error.URLError('private fixture')
        output = io.StringIO()
        with patch.object(sys, 'argv', ['simple_staging.py', 'verify-ci', '--revision', 'a' * 40, '--ci-run', '1']), \
                contextlib.redirect_stdout(output):
            self.assertEqual(s.main(), 1)
        result = json.loads(output.getvalue())
        self.assertEqual((result['status'], result['reason']), ('STOP', 'CI_API_UNAVAILABLE'))
        self.assertNotIn('private fixture', output.getvalue())


class SharedLockTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name).resolve() / 'shared.lock'
        self.path.touch()
        self.path.chmod(0o660)
        self.fstat = os.fstat
        if not hasattr(os, 'listxattr'):
            # This macOS Python lacks xattr APIs; Linux runs use real FD metadata.
            self.xattrs = patch.object(s.os, 'listxattr', return_value=[], create=True)
            self.xattrs.start()
            self.addCleanup(self.xattrs.stop)
        if os.geteuid() != 0:
            # Portable tests simulate only root ownership; Linux/root runs use real metadata.
            self.root_owner = patch.object(s.os, 'fstat', side_effect=lambda fd: self.with_uid(self.fstat(fd), 0))
            self.root_owner.start()
            self.addCleanup(self.root_owner.stop)

    @staticmethod
    def with_uid(info, uid):
        values = list(info); values[4] = uid
        return os.stat_result(values)

    def probe(self):
        code = '''import fcntl, os, sys
fd = os.open(sys.argv[1], os.O_RDWR | os.O_NOFOLLOW)
try:
    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    sys.exit(75)
finally:
    os.close(fd)
'''
        return subprocess.run([sys.executable, '-B', '-c', code, str(self.path)],
                              capture_output=True, timeout=5).returncode

    def replace(self):
        self.path.rename(self.path.with_suffix('.old'))
        self.path.touch()
        self.path.chmod(0o660)

    def test_existing_0660_lock_conflict_entire_body_and_unlock(self):
        before = self.path.stat()
        with s.shared_lock(self.path, 0):
            self.assertEqual(self.probe(), 75)
            self.assertEqual(self.probe(), 75)
        self.assertEqual(self.probe(), 0)
        after = self.path.stat()
        self.assertEqual((before.st_dev, before.st_ino), (after.st_dev, after.st_ino))
        self.assertEqual(stat.S_IMODE(after.st_mode), 0o660)

    def test_exception_releases_lock(self):
        with self.assertRaisesRegex(RuntimeError, 'operation failed'):
            with s.shared_lock(self.path, 0):
                raise RuntimeError('operation failed')
        self.assertEqual(self.probe(), 0)

    def test_inode_swap_inside_body_refused_and_descriptor_closed(self):
        real_open = os.open
        descriptors = []
        def opened(*args):
            fd = real_open(*args); descriptors.append(fd); return fd
        with patch.object(s.os, 'open', side_effect=opened):
            with self.assertRaisesRegex(s.Refused, 'LOCK_INODE_CHANGED'):
                with s.shared_lock(self.path, 0):
                    self.replace()
        with self.assertRaises(OSError): self.fstat(descriptors[0])
        self.assertEqual(self.probe(), 0)
        self.path.unlink()
        self.path.with_suffix('.old').rename(self.path)
        self.assertEqual(self.probe(), 0)

    def test_hard_links_refused_without_repair(self):
        alias = self.path.with_suffix('.alias')
        os.link(self.path, alias)
        with self.assertRaisesRegex(s.Refused, 'LOCK_LINK_COUNT_INVALID'):
            with s.shared_lock(self.path, 0): self.fail('operation admitted')
        self.assertEqual(self.path.stat().st_nlink, 2)
        self.assertEqual(alias.stat().st_ino, self.path.stat().st_ino)
        self.assertEqual(self.probe(), 0)

    def test_extended_attributes_refused_without_removal(self):
        if hasattr(os, 'setxattr'):
            os.setxattr(self.path, 'user.ht_flock_test', b'fixture')
            with self.assertRaisesRegex(s.Refused, 'LOCK_XATTR_PRESENT'):
                with s.shared_lock(self.path, 0): self.fail('operation admitted')
            self.assertEqual(os.getxattr(self.path, 'user.ht_flock_test'), b'fixture')
        else:
            with patch.object(s.os, 'listxattr', return_value=['user.ht_flock_test']) as attrs:
                with self.assertRaisesRegex(s.Refused, 'LOCK_XATTR_PRESENT'):
                    with s.shared_lock(self.path, 0): self.fail('operation admitted')
                self.assertIsInstance(attrs.call_args.args[0], int)
        self.assertEqual(self.probe(), 0)

    def test_unavailable_or_failed_xattr_check_refused_and_descriptor_closed(self):
        with patch.object(s.os, 'listxattr', None):
            with self.assertRaisesRegex(s.Refused, 'LOCK_XATTR_CHECK_UNAVAILABLE'):
                with s.shared_lock(self.path, 0): self.fail('operation admitted')
        with patch.object(s.os, 'listxattr', side_effect=OSError('xattr check failed')) as attrs:
            with self.assertRaisesRegex(OSError, 'xattr check failed'):
                with s.shared_lock(self.path, 0): self.fail('operation admitted')
            with self.assertRaises(OSError): self.fstat(attrs.call_args.args[0])
        self.assertEqual(self.probe(), 0)

    def test_hard_links_or_xattrs_added_inside_body_refused(self):
        alias = self.path.with_suffix('.alias')
        with self.assertRaisesRegex(s.Refused, 'LOCK_LINK_COUNT_INVALID'):
            with s.shared_lock(self.path, 0): os.link(self.path, alias)
        alias.unlink()
        with patch.object(s.os, 'listxattr', return_value=[]) as attrs:
            with self.assertRaisesRegex(s.Refused, 'LOCK_XATTR_PRESENT'):
                with s.shared_lock(self.path, 0): attrs.return_value = ['user.ht_flock_test']
        self.assertEqual(self.probe(), 0)

    def test_missing_lock_is_not_created(self):
        self.path.unlink()
        with self.assertRaises(FileNotFoundError):
            with s.shared_lock(self.path, 0): self.fail('operation admitted')
        self.assertFalse(self.path.exists())

    def test_exact_mode_required_without_repair(self):
        modes = (0o600, 0o640, 0o664, 0o666, 0o770, 0o1660)
        if os.geteuid() == 0:
            modes += (0o2660, 0o4660)
        for mode in modes:
            self.path.chmod(mode)
            with self.subTest(mode=oct(mode)), self.assertRaisesRegex(s.Refused, 'LOCK_OWNER_OR_MODE_INVALID'):
                with s.shared_lock(self.path, 0): self.fail('operation admitted')
            self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), mode)

    def test_special_mode_bits_refused(self):
        # Non-root macOS can silently strip setgid/setuid on chmod.
        for mode in (0o2660, 0o4660):
            info = list(self.path.stat()); info[0] = stat.S_IFREG | mode; info[4] = 0
            with self.subTest(mode=oct(mode)), patch.object(s.os, 'fstat', return_value=os.stat_result(info)):
                with self.assertRaisesRegex(s.Refused, 'LOCK_OWNER_OR_MODE_INVALID'):
                    with s.shared_lock(self.path, 0): self.fail('operation admitted')

    def test_owner_must_be_root_even_if_caller_matches(self):
        with patch.object(s.os, 'fstat', side_effect=lambda fd: self.with_uid(self.fstat(fd), 1234)), \
                patch.object(s.os, 'geteuid', return_value=1234):
            with self.assertRaisesRegex(s.Refused, 'LOCK_OWNER_OR_MODE_INVALID'):
                with s.shared_lock(self.path, 0): self.fail('operation admitted')

    def test_symlink_refused(self):
        target = self.path.with_suffix('.target')
        self.path.rename(target)
        self.path.symlink_to(target)
        with self.assertRaises(OSError):
            with s.shared_lock(self.path, 0): self.fail('operation admitted')

    def test_fifo_refused_without_blocking(self):
        self.path.unlink(); os.mkfifo(self.path, 0o660); self.path.chmod(0o660)
        with self.assertRaisesRegex(s.Refused, 'LOCK_OWNER_OR_MODE_INVALID'):
            with s.shared_lock(self.path, 0): self.fail('operation admitted')

    def test_inode_swap_between_lstat_and_open_refused(self):
        real_open = os.open
        def swapped_open(*args):
            if not args[1] & os.O_CREAT:
                self.replace()
            return real_open(*args)
        with patch.object(s.os, 'open', side_effect=swapped_open):
            with self.assertRaisesRegex(s.Refused, 'LOCK_INODE_CHANGED'):
                with s.shared_lock(self.path, 0): self.fail('operation admitted')

    def test_inode_swap_after_flock_refused_and_descriptor_closed(self):
        real_flock = s.fcntl.flock
        descriptors = []
        def swapped_flock(fd, flags):
            real_flock(fd, flags); descriptors.append(fd); self.replace()
        with patch.object(s.fcntl, 'flock', side_effect=swapped_flock):
            with self.assertRaisesRegex(s.Refused, 'LOCK_INODE_CHANGED'):
                with s.shared_lock(self.path, 0): self.fail('operation admitted')
        with self.assertRaises(OSError): self.fstat(descriptors[0])
        self.assertEqual(self.probe(), 0)

    def test_busy_timeout_and_wait_for_release(self):
        fd = os.open(self.path, os.O_RDWR)
        try:
            s.fcntl.flock(fd, s.fcntl.LOCK_EX | s.fcntl.LOCK_NB)
            start = time.monotonic()
            with self.assertRaisesRegex(s.Refused, 'SHARED_DOCKER_LOCK_BUSY'):
                with s.shared_lock(self.path, 0.15): self.fail('operation admitted')
            self.assertGreaterEqual(time.monotonic() - start, 0.15)
            with patch.object(s.time, 'sleep', side_effect=lambda _: s.fcntl.flock(fd, s.fcntl.LOCK_UN)):
                with s.shared_lock(self.path, 1): self.assertEqual(self.probe(), 75)
        finally:
            os.close(fd)
        self.assertEqual(self.probe(), 0)

    def test_inode_or_permissions_changed_while_waiting_refused(self):
        for change, reason in ((self.replace, 'LOCK_INODE_CHANGED'),
                               (lambda: self.path.chmod(0o666), 'LOCK_OWNER_OR_MODE_INVALID')):
            fd = os.open(self.path, os.O_RDWR)
            try:
                s.fcntl.flock(fd, s.fcntl.LOCK_EX | s.fcntl.LOCK_NB)
                with self.subTest(reason=reason), patch.object(s.time, 'sleep', side_effect=lambda _: change()):
                    with self.assertRaisesRegex(s.Refused, reason):
                        with s.shared_lock(self.path, 1): self.fail('operation admitted')
            finally:
                os.close(fd)

    def test_default_wait_30_and_invalid_wait(self):
        for wait in (-1, 61):
            with self.subTest(wait=wait), self.assertRaisesRegex(s.Refused, 'LOCK_WAIT_INVALID'):
                with s.shared_lock(self.path, wait): self.fail('operation admitted')
        with patch.object(s.fcntl, 'flock', side_effect=BlockingIOError), \
                patch.object(s.time, 'monotonic', side_effect=[0, 29, 30]), patch.object(s.time, 'sleep') as sleep:
            with self.assertRaisesRegex(s.Refused, 'SHARED_DOCKER_LOCK_BUSY'):
                with s.shared_lock(self.path): self.fail('operation admitted')
            sleep.assert_called_once_with(0.1)


if __name__ == '__main__': unittest.main()
