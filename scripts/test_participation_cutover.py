"""Exact-image HT70 rehearsal. Uses a unique Compose project, synthetic DB, no Telegram token."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
import urllib.error
import urllib.request

import simple_staging as ht
from test_simple_staging import config
from test_simple_staging_docker import command

PG = 'postgres@sha256:1eada89ec2521ae0f61125f6bdaf83bbbf88a43fecda48d112a83bb92df75c36'


def candidate(identity, run):
    return dict(image='ghcr.io/koteev-m/hookah_bot/backend@' + identity[1], revision=identity[0], ci_run=run)


def local_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Rehearsal(ht.Staging):
    def host_check(self):
        assert self.project.startswith('ht70-rehearse-') and self.port != 8080
        assert self.config['runtime']['TELEGRAM_BOT_ENABLED'] == 'false'
        assert not self.config['runtime']['TELEGRAM_BOT_TOKEN']


SEED = """
BEGIN;
INSERT INTO users (telegram_user_id) VALUES (17001);
INSERT INTO venues (id,name,city,address,status) OVERRIDING SYSTEM VALUE VALUES (17001,'Local','Local','Local','PUBLISHED');
INSERT INTO venue_tables (id,venue_id,table_number) OVERRIDING SYSTEM VALUE VALUES (17001,17001,1);
INSERT INTO table_sessions (id,venue_id,table_id,started_at,last_activity_at,expires_at,status)
VALUES (17001,17001,17001,now(),now(),now()+interval '1 day','ACTIVE');
INSERT INTO tab (id,venue_id,table_session_id,type,owner_user_id,status)
VALUES (17001,17001,17001,'PERSONAL',17001,'ACTIVE');
INSERT INTO tab_member (tab_id,user_id,role) VALUES (17001,17001,'OWNER');
INSERT INTO orders (id,venue_id,table_id,table_session_id,status)
OVERRIDING SYSTEM VALUE VALUES (17001,17001,17001,17001,'ACTIVE');
INSERT INTO order_batches (id,order_id,author_user_id,tab_id,source,status)
OVERRIDING SYSTEM VALUE VALUES (17001,17001,17001,17001,'MINIAPP','NEW');
INSERT INTO guest_batch_idempotency (venue_id,table_session_id,user_id,idempotency_key,order_id,batch_id)
VALUES (17001,17001,17001,'rehearsal',17001,17001);
COMMIT;
"""


@unittest.skipUnless(os.getenv('HT_PARTICIPATION_REHEARSAL') == '1', 'requires disposable local Docker')
class ParticipationRehearsal(unittest.TestCase):
    def test_uninterrupted(self):
        self.rehearse(False)

    def test_published_images_and_interrupted_handoffs(self):
        self.rehearse(True)

    def rehearse(self, faults):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            c = config(); c['postgres_image'] = PG; c['runtime'][ht.PDF_FLAG] = 'true'
            ht.atomic(root / 'config.json', c)
            app = Rehearsal(root, command)
            app.project = 'ht70-rehearse-' + uuid.uuid4().hex[:12]
            app.port, smoke_port = local_port(), local_port()
            self.assertNotEqual(app.port, smoke_port)
            source = app.candidate(candidate(ht.PARTICIPATION_SOURCE, 45629461560))
            target = candidate(ht.PARTICIPATION_TARGET, 45891729670)
            doc = app.document(source)
            ht.atomic(app.compose_file, doc)
            try:
                app.compose('up', '-d', '--pull', 'never', '--wait', 'postgres', 'pdf-worker')
                app.compose('up', '-d', '--pull', 'never', '--no-deps', 'backend')
                app.ready(source)
                app.save(dict(status='APPLYING', operation=uuid.uuid4().hex, target=source))
                binding = app.binding()
                app.save(dict(status='SUCCESS', operation=uuid.uuid4().hex, target=source,
                              binding=binding, runtime_hash=ht.digest(c['runtime']),
                              config_binding=app.config_binding(), compose_hash=ht.digest(doc)))
                app.observe(source, binding)
                app.db(SEED)
                snapshot_sql = """SELECT jsonb_build_array(
                    (SELECT to_jsonb(s) FROM table_sessions s WHERE id=17001),
                    (SELECT to_jsonb(t) FROM tab t WHERE id=17001),
                    (SELECT to_jsonb(m) FROM tab_member m WHERE tab_id=17001),
                    (SELECT to_jsonb(o) FROM orders o WHERE id=17001),
                    (SELECT to_jsonb(b) FROM order_batches b WHERE id=17001),
                    (SELECT to_jsonb(k)-'rejected_table_id' FROM guest_batch_idempotency k WHERE batch_id=17001))"""
                before = app.db(snapshot_sql)
                self.assertNotIn(None, json.loads(before))
                start = time.monotonic()
                if not faults:
                    result = app.participation('participation-cutover', target, '17001', None, smoke_port=smoke_port)
                    self.assertEqual(result['phase'], 'PREPARE')
                    result = app.participation('participation-cutover', None, None, result['operation'])
                    self.assertEqual(result['phase'], 'ACCEPTANCE')
                    self.assertEqual(result['smoke_port'], smoke_port)
                    self.assertFalse(socket_is_open(app.port))
                    self.assertEqual(app.db(snapshot_sql), before)
                    self.assertEqual(catalog_status(app, smoke_port, 17001), 200)
                    self.assertEqual(catalog_status(app, smoke_port, 17002), 503)
                    smoke_time = time.monotonic()-start
                    result = app.participation('participation-open', None, None, result['operation'])
                    self.assertEqual(result['status'], 'SUCCESS')
                    self.assertEqual(app.db(snapshot_sql), before)
                    print(f'Uninterrupted: gated ready {smoke_time:.1f}s; product ready {time.monotonic()-start:.1f}s',
                          flush=True)
                    return

                state_before = app.state_file.read_bytes()
                compose_before = app.compose_file.read_bytes()
                old_app = app.participation_backends()[0]
                with socket.socket() as neighbor:
                    neighbor.bind(('127.0.0.1', smoke_port))
                    neighbor.listen()
                    with self.assertRaisesRegex(ht.Refused, 'CUTOVER_SMOKE_PORT_UNAVAILABLE'):
                        app.participation('participation-cutover', target, '17001', None, smoke_port=smoke_port)
                    self.assertTrue(socket_is_open(smoke_port))
                    self.assertEqual(app.state_file.read_bytes(), state_before)
                    self.assertEqual(app.compose_file.read_bytes(), compose_before)
                    self.assertEqual(app.participation_backends()[0]['Id'], old_app['Id'])
                    app.observe(source, binding)
                    self.assertFalse(list(root.glob('backup-*.dump')))

                with patch.object(app, 'backup', side_effect=ht.Refused('injected backup failure')), self.assertRaisesRegex(
                        ht.Refused, 'injected backup failure'):
                    app.participation('participation-cutover', target, '17001', None, smoke_port=smoke_port)
                op = app.state()['operation']
                self.assertEqual(app.state()['phase'], 'FENCE')
                self.assertEqual(app.participation_backends(), [])
                self.assertNotIn('backend', ht.private_json(app.compose_file)['services'])
                self.assertEqual(app.history(), ht.history_for(source['manifest']))
                with self.assertRaisesRegex(ht.Refused, 'PARTICIPATION_CUTOVER_IN_PROGRESS'):
                    app.apply('reconcile')

                def resume(action='participation-cutover'):
                    return app.participation(action, None, None, op)

                original_save = app.save
                def interrupt_after_prepare(st):
                    original_save(st)
                    if st['phase'] == 'PREPARE':
                        raise InterruptedError()
                with patch.object(app, 'save', side_effect=interrupt_after_prepare), self.assertRaises(InterruptedError):
                    resume()
                self.assertEqual(len(app.history()), 143)
                self.assertEqual(app.apply('status')['smoke_port'], smoke_port)
                with self.assertRaisesRegex(ht.Refused, 'CUTOVER_RESUME_ID_REQUIRED'):
                    app.participation('participation-cutover', None, None, op, smoke_port=smoke_port + 1)
                with socket.socket() as neighbor:
                    neighbor.bind(('127.0.0.1', smoke_port))
                    neighbor.listen()
                    with self.assertRaisesRegex(ht.Refused, 'CUTOVER_SMOKE_PORT_UNAVAILABLE'):
                        resume()
                    self.assertEqual(app.state()['phase'], 'PREPARE')
                    self.assertEqual(len(app.history()), 143)
                    self.assertEqual(app.participation_backends(), [])
                backup = app.state()['backup']
                self.assertEqual(hashlib.sha256(Path(backup['path']).read_bytes()).hexdigest(), backup['sha256'])
                with patch.dict(backup, sha256='0' * 64), self.assertRaisesRegex(ht.Refused, 'CUTOVER_BACKUP_CHANGED'):
                    app.verify_participation_backup(dict(app.state(), backup=backup))
                def interrupt_after_migrate(st):
                    if st['phase'] == 'ACCEPTANCE':
                        raise InterruptedError()
                    original_save(st)
                with patch.object(app, 'save', side_effect=interrupt_after_migrate), self.assertRaises(InterruptedError):
                    resume()
                self.assertEqual(len(app.history()), 147)
                app_id = app.participation_backends()[0]['Id']
                self.assertEqual(resume()['phase'], 'ACCEPTANCE')
                self.assertEqual(app_id, app.participation_backends()[0]['Id'])
                self.assertEqual(app.state()['backup'], backup)
                self.assertEqual(before, app.db(snapshot_sql))
                self.assertFalse(socket_is_open(app.port))
                self.assertTrue(socket_is_open(smoke_port))
                with self.assertRaisesRegex(ht.Refused, 'CUTOVER_RESUME_ID_REQUIRED'):
                    app.participation('participation-open', None, None, 'wrong')
                def interrupt_before_success(st):
                    if st['status'] == 'SUCCESS':
                        raise InterruptedError()
                    original_save(st)
                with patch.object(app, 'save', side_effect=interrupt_before_success), self.assertRaises(InterruptedError):
                    resume('participation-open')
                product_id = app.participation_backends()[0]['Id']
                self.assertEqual(app.state()['phase'], 'OPENING')
                self.assertEqual(resume('participation-open')['status'], 'SUCCESS')
                self.assertEqual(resume('participation-open')['status'], 'SUCCESS')
                self.assertEqual(product_id, app.participation_backends()[0]['Id'])
                app.observe(app.state()['target'], binding)
                self.assertEqual(before, app.db(snapshot_sql))
                self.assertEqual(len(list(root.glob('backup-*.dump'))), 1)
                with self.assertRaisesRegex(ht.Refused, 'SCHEMA_ROLLBACK_FORBIDDEN'):
                    app.apply('rollback')
                elapsed = time.monotonic()-start
                # Restore the exact cutover backup into a different disposable database.
                app.db('CREATE DATABASE rehearsal_restore')
                app.compose('exec', '-T', 'postgres', 'pg_restore', '--exit-on-error', '-U', 'hookah', '-d',
                            'rehearsal_restore', payload=Path(backup['path']).read_bytes())
                def restored(sql):
                    return app.compose('exec', '-T', 'postgres', 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U',
                                       'hookah', '-d', 'rehearsal_restore', '-c', sql).decode().strip()
                self.assertEqual(restored(snapshot_sql), before)
                self.assertEqual(restored('SELECT max(version::int) FROM flyway_schema_history'), '143')
                print(f'Published V143 to V147, including fault recovery: {elapsed:.1f}s', flush=True)
            finally:
                app.compose('down', '--volumes', '--timeout', '15')


def catalog_status(app, smoke_port, actor):
    # Exercise the real Ktor session/maintenance guard with this disposable database's test key.
    def encode(data):
        return base64.urlsafe_b64encode(data).rstrip(b'=')
    claims = dict(sub=str(actor), iss='hookah-platform', aud='hookah-miniapp', exp=int(time.time())+60)
    signed = encode(b'{"alg":"HS256","typ":"JWT"}') + b'.' + encode(json.dumps(claims).encode())
    key = app.config['runtime']['API_SESSION_JWT_SECRET'].encode()
    token = signed + b'.' + encode(hmac.digest(key, signed, 'sha256'))
    request = urllib.request.Request(f'http://127.0.0.1:{smoke_port}/api/guest/catalog',
                                     headers={'Authorization': 'Bearer ' + token.decode()})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code


def socket_is_open(port):
    with socket.socket() as sock:
        return sock.connect_ex(('127.0.0.1', port)) == 0
