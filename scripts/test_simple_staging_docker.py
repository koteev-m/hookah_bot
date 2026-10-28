"""Opt-in disposable real backend + PostgreSQL + Compose integration.

HT_SIMPLE_STAGING_DOCKER=1 python3 -B -m unittest discover -s scripts -p test_simple_staging_docker.py -v
CI and registry are transport fixtures; actual packaged V1..V126, Flyway, HTTP,
pg_dump/pg_restore, app replacement and PostgreSQL persistence run in Docker.
No SSH, registry access, live project names, Caddy or global cleanup.
"""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
import zipfile

import simple_staging as s
from test_simple_staging import config, record, r2_config

BASE = 'sha256:5716515f536a7cb7733808819e70094081f95fb92ff8def3f5697e7b4a0972e3'
REVISION = '7fc3ed0b7de556363bf2b72569b0c2dd98cfb81e'
PG = 'postgres@sha256:69dddb030ab69d669d8d7c6abf67aeb448178e5270d5f123a21f4f7ac8b46a24'


def command(argv, *, timeout=120, payload=None):
    p = subprocess.run(argv, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if p.returncode:
        # Private test logs only; never emit inspect env or credentials.
        raise s.Refused('FIXTURE_COMMAND_FAILED_' + '_'.join(argv[:2]))
    return p.stdout


def inventory():
    ids = command(['docker', 'ps', '-aq', '--no-trunc']).decode().split()
    items = json.loads(command(['docker', 'inspect', *ids])) if ids else []
    return {i['Id']: s.digest({k: i[k] for k in ('Id', 'Image', 'Config', 'HostConfig', 'Mounts', 'NetworkSettings', 'RestartCount', 'State')}) for i in items}


class FixtureStaging(s.Staging):
    def __init__(self, root, project, mapping):
        self.project = project; self.mapping = mapping
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); self.port = sock.getsockname()[1]
        super().__init__(root, self.transport)

    def host_check(self):
        command(['docker', 'info', '--format', '{{.OSType}}'])
        command(['docker', 'compose', 'version'])

    def transport(self, argv, **kwargs):
        return command([self.mapping.get(arg, arg) for arg in argv], **kwargs)

    def candidate(self, record):
        # CI is a transport fixture; production now uses anonymous HTTPS, not gh.
        # Exact public API validation has its own portable regression suite.
        def fixture_ci(revision, run_id):
            s.require(revision == self.ci_revision, 'FIXTURE_CI_REVISION_MISMATCH')
            return {'id': run_id, 'sha': revision, 'url': 'fixture://ci'}
        with patch.object(s, 'verify_ci', side_effect=fixture_ci):
            return super().candidate(record)

    def inspect_image(self, ref, **kwargs):
        actual = self.mapping.get(ref, ref)
        result = json.loads(command(['docker', 'image', 'inspect', '--platform', 'linux/amd64', actual]))[0]
        s.require(result['Architecture'] == 'amd64' and result['Os'] == 'linux', 'FIXTURE_PLATFORM')
        return result

    def document(self, image):
        doc = super().document(image)
        doc['services']['backend']['image'] = self.mapping[image['image']]
        return doc


@unittest.skipUnless(os.environ.get('HT_SIMPLE_STAGING_DOCKER') == '1', 'explicit disposable Docker opt-in required')
class DockerTests(unittest.TestCase):
    def test_real_empty_v126_update_backup_forward_failure_and_recovery(self):
        prefix = 'ht70-' + uuid.uuid4().hex[:12]
        before = inventory()
        images_before = set(command(['docker', 'image', 'ls', '-q', '--no-trunc']).decode().split())
        owned_ids, owned_tags, projects = [], [], []
        with tempfile.TemporaryDirectory(prefix=prefix + '-', dir='/private/tmp' if os.sys.platform == 'darwin' else None) as tmp:
            root = Path(tmp).resolve()
            cfg = config(); cfg['postgres_image'] = PG
            s.atomic(root / 'config.json', cfg)
            a_ref = 'ghcr.io/koteev-m/hookah_bot/backend@sha256:' + 'a' * 64
            b_ref = 'ghcr.io/koteev-m/hookah_bot/backend@sha256:' + 'b' * 64
            c_ref = 'ghcr.io/koteev-m/hookah_bot/backend@sha256:' + 'c' * 64
            mapping = {a_ref: BASE}
            r = FixtureStaging(root, prefix + '-hookah', mapping)
            # Compose cleanup is deliberately absent from production. Fixture cleanup
            # selects exact project names created here and verifies labels before rm.
            projects.append((r.project, root))
            try:
                # A separate disposable neighbor simulates Clubs resources without using Clubs source or names.
                peer_dir = root / 'peer'; peer_dir.mkdir(mode=0o700)
                peer_name = prefix + '-neighbor'; projects.append((peer_name, peer_dir))
                peer_doc = {'services': {'postgres': {'image': PG, 'platform': 'linux/amd64',
                            'environment': {'POSTGRES_PASSWORD': 'fixture-only-password'},
                            'volumes': ['pgdata:/var/lib/postgresql/data']}}, 'volumes': {'pgdata': {}}}
                s.atomic(peer_dir / 'compose.json', peer_doc)
                command(['docker', 'compose', '-p', peer_name, '-f', str(peer_dir / 'compose.json'), 'up', '-d', '--pull', 'never'])
                peer_ids = command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + peer_name]).decode().split()
                for attempt in range(30):
                    try:
                        command(['docker', 'exec', peer_ids[0], 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c',
                                 "CREATE TABLE neighbor_sentinel (value text); INSERT INTO neighbor_sentinel VALUES ('retained-neighbor')"])
                        break
                    except s.Refused:
                        if attempt == 29: raise
                        time.sleep(1)
                peer_initial = json.loads(command(['docker', 'inspect', *peer_ids]))
                a = {'image': a_ref, 'revision': REVISION, 'ci_run': 1}
                r.ci_revision = REVISION
                result = r.apply('install', a)
                self.assertEqual(result['schema'], 126)
                print('PASS real Flyway EMPTY -> V126, health/db/version/API/Mini App', flush=True)
                initial_binding = r.binding()
                r.db('CREATE TABLE ht70_sentinel (id integer primary key, value text); INSERT INTO ht70_sentinel VALUES (1,\'retained\')')
                self.assertTrue(r.apply('install', a)['unchanged'])
                self.assertEqual(r.db('SELECT value FROM ht70_sentinel WHERE id=1'), 'retained')
                print('PASS repeated install and existing data preservation', flush=True)
                # Clone a never-started base container for app-only successor; no build/network work.
                build = root / 'build'; build.mkdir()

                b_tag = prefix + ':app'; owned_tags.append(b_tag)
                cid = command(['docker', 'create', '--name', prefix + '-clone-b', '--network', 'none', BASE]).decode().strip()
                try:
                    command(['docker', 'commit', '--change', 'LABEL org.opencontainers.image.revision=' + 'b' * 40, cid, b_tag])
                finally: command(['docker', 'rm', cid])
                b_id = command(['docker', 'image', 'inspect', '--format', '{{.Id}}', b_tag]).decode().strip()
                owned_ids.append(b_id); mapping[b_ref] = b_id
                b = {'image': b_ref, 'revision': 'b' * 40, 'ci_run': 2}; r.ci_revision = b['revision']
                r.apply('deploy', b)
                self.assertEqual(r.binding(), initial_binding)
                self.assertEqual(r.db('SELECT value FROM ht70_sentinel WHERE id=1'), 'retained')
                print('PASS ordinary app update, same PostgreSQL container/volume/system ID', flush=True)
                backup = r.apply('backup')
                self.assertTrue(Path(backup['path']).is_file())
                # Verify pg_dump restores sentinel and Flyway into a separate disposable DB,
                # never overwrite the Hookah fixture DB or any existing resource.
                r.db('CREATE DATABASE ht70_restore')
                r.compose('exec', '-T', 'postgres', 'pg_restore', '-U', 'hookah', '-d', 'ht70_restore', '--exit-on-error', payload=Path(backup['path']).read_bytes())
                restored = r.compose('exec', '-T', 'postgres', 'psql', '-U', 'hookah', '-d', 'ht70_restore', '-Atc', 'SELECT value FROM ht70_sentinel').decode().strip()
                self.assertEqual(restored, 'retained')
                print('PASS backup and actual isolated restore', flush=True)
                # Simulate lost result after Docker replacement. No automatic retry.
                r.ci_revision = REVISION
                with patch.object(r, 'ready', side_effect=s.Refused('READINESS_FAILED')):
                    with self.assertRaises(s.Refused): r.apply('deploy', a)
                self.assertEqual(r.state()['status'], 'UNKNOWN')
                with self.assertRaisesRegex(s.Refused, 'UNFINISHED'): r.apply('deploy', b)
                r.apply('reconcile')
                r.ci_revision = b['revision']; r.apply('rollback')
                self.assertEqual(r.binding(), initial_binding)
                print('PASS failed deploy UNKNOWN guard, verified reconcile and explicit app rollback', flush=True)
                # A real crashing backend keeps the correct SQL/source labels but exits
                # before HTTP startup. Readiness must fail and preserve PostgreSQL.
                bad_tag = prefix + ':crashing'; owned_tags.append(bad_tag)
                cid = command(['docker', 'create', '--name', prefix + '-clone-bad', '--network', 'none', BASE]).decode().strip()
                try:
                    command(['docker', 'commit', '--change', 'LABEL org.opencontainers.image.revision=' + 'd' * 40,
                             '--change', 'CMD ["/bin/false"]', cid, bad_tag])
                finally: command(['docker', 'rm', cid])
                bad_id = command(['docker', 'image', 'inspect', '--format', '{{.Id}}', bad_tag]).decode().strip()
                owned_ids.append(bad_id)
                bad_ref = 'ghcr.io/koteev-m/hookah_bot/backend@sha256:' + 'd' * 64
                mapping[bad_ref] = bad_id; r.ci_revision = 'd' * 40
                r.readiness_seconds = 5
                try:
                    with self.assertRaisesRegex(s.Refused, 'READINESS_FAILED'):
                        r.apply('deploy', {'image': bad_ref, 'revision': 'd' * 40, 'ci_run': 4})
                finally: r.readiness_seconds = 120
                self.assertEqual(r.state()['status'], 'UNKNOWN')
                r.ci_revision = b['revision']; r.apply('rollback')
                self.assertEqual(r.binding(), initial_binding)
                self.assertEqual(r.db('SELECT value FROM ht70_sentinel WHERE id=1'), 'retained')
                print('PASS actual crashing backend refusal and safe application rollback', flush=True)
                # Real additive V127 in the packaged JAR, executed by actual Flyway.
                cid = command(['docker', 'create', '--name', prefix + '-jar', '--network', 'none', BASE]).decode().strip()
                try:
                    raw = command(['docker', 'cp', cid + ':/app/app/lib/app-0.1.0.jar', '-'])
                finally: command(['docker', 'rm', cid])
                with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
                    jar = tar.extractfile(next(m for m in tar if m.isfile())).read()
                out = io.BytesIO(jar)
                with zipfile.ZipFile(out, 'a') as z:
                    z.writestr('db/migration/postgresql/V127__ht70_compatible_probe.sql', 'CREATE TABLE ht70_forward (id integer PRIMARY KEY);\n')
                (build / 'app.jar').write_bytes(out.getvalue())

                c_tag = prefix + ':forward'; owned_tags.append(c_tag)
                cid = command(['docker', 'create', '--name', prefix + '-clone-c', '--network', 'none', BASE]).decode().strip()
                try:
                    command(['docker', 'cp', str(build / 'app.jar'), cid + ':/app/app/lib/app-0.1.0.jar'])
                    command(['docker', 'commit', '--change', 'LABEL org.opencontainers.image.revision=' + 'c' * 40, cid, c_tag])
                finally: command(['docker', 'rm', cid])
                c_id = command(['docker', 'image', 'inspect', '--format', '{{.Id}}', c_tag]).decode().strip()
                owned_ids.append(c_id); mapping[c_ref] = c_id
                c = {'image': c_ref, 'revision': 'c' * 40, 'ci_run': 3}; r.ci_revision = c['revision']
                target = r.candidate(c)
                review = {'from_manifest': s.digest(r.state()['target']['manifest']), 'to_manifest': s.digest(target['manifest']),
                          'backward_compatible': True, 'reason': 'Fixture: adds unrelated empty table; previous app remains compatible.'}
                # Exercise a pre-R2 installed runtime with the new config keys and
                # forward migration in one operation. Keep synthetic R2 disabled:
                # this fixture proves plumbing, never provider connectivity.
                photos = r2_config(); photos['STAFF_PHOTOS_R2_ENABLED'] = 'false'
                r.config['runtime'].update(photos); s.validate_config(r.config)
                s.atomic(root / 'config.json', r.config)
                with self.assertRaisesRegex(s.Refused, 'RUNTIME_CONFIG_CHANGED_USE_APPLY_CONFIG'):
                    r.apply('deploy', c, review)
                r.apply('deploy', c, review, apply_config=True)
                backend = next(i for i in r.containers() if i['Config']['Labels']['com.docker.compose.service'] == 'backend')
                actual = dict(item.split('=', 1) for item in backend['Config']['Env'])
                self.assertTrue(all(actual.get(k) == v for k, v in photos.items()), 'synthetic photo environment mismatch')
                self.assertEqual(r.state()['runtime_hash'], s.digest(r.config['runtime']))
                self.assertEqual(len(r.history()), 127); self.assertIn('backup', r.state())
                self.assertEqual(r.binding(), initial_binding)
                self.assertEqual(r.db('SELECT value FROM ht70_sentinel WHERE id=1'), 'retained')
                with self.assertRaisesRegex(s.Refused, 'SCHEMA_ROLLBACK'): r.apply('rollback')
                print('PASS real compatible V127 + backup, data retained, schema rollback refused', flush=True)
                # Reload private config, then recreate only backend with unchanged
                # runtime; prove durability without a new deployment mechanism.
                r.config = s.private_json(root / 'config.json')
                r.apply('deploy', c)
                self.assertEqual(r.binding(), initial_binding)
                r.config['runtime'] = config()['runtime']
                s.atomic(root / 'config.json', r.config)
                r.apply('deploy', c, apply_config=True)
                backend = next(i for i in r.containers() if i['Config']['Labels']['com.docker.compose.service'] == 'backend')
                self.assertFalse(any(item.split('=', 1)[0] in s.PHOTO_RUNTIME_KEYS for item in backend['Config']['Env']))
                print('PASS old runtime -> synthetic R2 config, reload/recreate, removal and unchanged DB binding', flush=True)
                peer_after = json.loads(command(['docker', 'inspect', *peer_ids]))
                self.assertEqual(command(['docker', 'exec', peer_ids[0], 'psql', '-U', 'postgres', '-Atc',
                                          'SELECT value FROM neighbor_sentinel']).decode().strip(), 'retained-neighbor')
                for initial, after in zip(peer_initial, peer_after):
                    for k in ('Id', 'Image', 'Config', 'HostConfig', 'Mounts', 'NetworkSettings'):
                        self.assertEqual(initial[k], after[k])
                    self.assertEqual(initial['State']['StartedAt'], after['State']['StartedAt'])
                print('PASS disposable neighbor unchanged', flush=True)
            finally:
                for project, directory in reversed(projects):
                    ids = command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]).decode().split()
                    for cid in ids:
                        item = json.loads(command(['docker', 'inspect', cid]))[0]
                        self.assertEqual(item['Config']['Labels']['com.docker.compose.project'], project)
                        self.assertTrue(project.startswith(prefix + '-'))
                        command(['docker', 'rm', '-f', cid])
                    for kind, name in [('volume', project + '_pgdata'), ('network', project + '_default')]:
                        names = command(['docker', kind, 'ls', '--format', '{{.Name}}']).decode().splitlines()
                        if name in names:
                            item = json.loads(command(['docker', kind, 'inspect', name]))[0]
                            self.assertEqual(item['Labels']['com.docker.compose.project'], project)
                            command(['docker', kind, 'rm', name])
                for tag in owned_tags:
                    existing_tags = command(['docker', 'image', 'ls', '--format', '{{.Repository}}:{{.Tag}}']).decode().splitlines()
                    if tag in existing_tags: command(['docker', 'image', 'rm', tag])
                for identity in owned_ids:
                    if identity not in images_before:
                        remaining = set(command(['docker', 'image', 'ls', '-q', '--no-trunc']).decode().split())
                        if identity in remaining: command(['docker', 'image', 'rm', identity])
                self.assertEqual(inventory(), before, 'pre-existing container inventory changed')
                print('PASS owned cleanup and pre-existing containers unchanged', flush=True)


if __name__ == '__main__': unittest.main()
