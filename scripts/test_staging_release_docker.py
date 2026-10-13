"""Opt-in disposable real Docker/Compose fixture; never SSH or real application data.

HT_STAGING_DOCKER_FIXTURE=1 python3 -B -m unittest discover -s scripts -p test_staging_release_docker.py -v
Only fixture-owned projects/volumes/tags are created or removed. Existing containers
are inspected privately and compared before/after. Requires Docker, Compose, openssl.
"""

import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

import staging_release as release


SERVER = '''import http.server,json,os,ssl,threading,time
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_GET(self):
  if self.path == '/version': body=json.dumps(dict(service='backend',env='staging',version=os.environ['APP_VERSION'])).encode()
  elif self.path == '/miniapp/': body=b'<html>fixture</html>'
  else: body=b'{"status":"ok"}'
  if os.environ.get('FIXTURE_READY') == 'bad': time.sleep(1)
  self.send_response(503 if os.environ.get('FIXTURE_READY') == 'bad' else 200)
  self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
a=http.server.ThreadingHTTPServer(('0.0.0.0',8080),Handler)
b=http.server.ThreadingHTTPServer(('0.0.0.0',8443),Handler)
c=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); c.load_cert_chain('/fixture/cert.pem','/fixture/key.pem')
b.socket=c.wrap_socket(b.socket,server_side=True)
threading.Thread(target=b.serve_forever,daemon=True).start(); a.serve_forever()
'''


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def assert_restart_configuration(test, before, after):
    for key in ('Id', 'Image', 'Config', 'HostConfig'):
        test.assertTrue(before[key] == after[key], 'fixture production ' + key + ' changed')
    test.assertTrue(release.stable_container(before)['Mounts'] == release.stable_container(after)['Mounts'],
                    'fixture production Mounts changed')


class RestartConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.before = {'Id': 'container', 'Image': 'image', 'Config': {}, 'HostConfig': {},
                       'State': {'Running': True}, 'NetworkSettings': {'Networks': {}},
                       'Mounts': [{'Type': 'bind', 'Source': '/fixture/' + name,
                                   'Destination': '/' + name, 'RW': False, 'Mode': 'ro',
                                   'Propagation': 'rprivate'} for name in ('data', 'config', 'logs')]}

    def test_independent_mount_reordering_preserves_input(self):
        after = copy.deepcopy(self.before)
        after['Mounts'].reverse()
        original = copy.deepcopy(after)
        assert_restart_configuration(self, self.before, after)
        self.assertEqual(after, original)

    def test_identity_mount_fields_counts_and_overlap_remain_strict(self):
        for key in ('Id', 'Image', 'Config', 'HostConfig'):
            with self.subTest(key=key):
                after = copy.deepcopy(self.before)
                after[key] = 'changed'
                with self.assertRaises(AssertionError):
                    assert_restart_configuration(self, self.before, after)
        for key, value in (('Source', '/other'), ('Destination', '/other'), ('RW', True),
                           ('Mode', 'rw'), ('Propagation', 'rshared'), ('Type', 'volume'),
                           ('Options', {'added': True})):
            with self.subTest(mount_field=key):
                after = copy.deepcopy(self.before)
                after['Mounts'][0][key] = value
                with self.assertRaises(AssertionError):
                    assert_restart_configuration(self, self.before, after)
        for count in (0, 2, 4):
            with self.subTest(count=count):
                after = copy.deepcopy(self.before)
                after['Mounts'] = (after['Mounts'] * 2)[:count]
                with self.assertRaises(AssertionError):
                    assert_restart_configuration(self, self.before, after)
        for destination in ('/data/nested', '/data', None, 'relative'):
            with self.subTest(destination=destination):
                before = copy.deepcopy(self.before)
                before['Mounts'][1]['Destination'] = destination
                after = copy.deepcopy(before)
                after['Mounts'].reverse()
                with self.assertRaises(AssertionError):
                    assert_restart_configuration(self, before, after)


@unittest.skipUnless(os.environ.get('HT_STAGING_DOCKER_FIXTURE') == '1', 'explicit disposable Docker fixture opt-in required')
class DockerReleaseTests(unittest.TestCase):
    def test_real_release_persistence_rollback_and_unknown(self):
        physical_tmp = '/private/tmp' if sys.platform == 'darwin' else None
        with tempfile.TemporaryDirectory(prefix='ht-staging-simple-02-', dir=physical_tmp) as temp:
            directory = Path(temp).resolve()
            socket_url = os.environ.get('HT_STAGING_FIXTURE_DOCKER_SOCKET', 'unix:///var/run/docker.sock')
            runner = release.ProcessRunner(docker_host=socket_url, environment={'CURL_CA_BUNDLE': str(directory / 'cert.pem')})
            files = release.ProtectedFiles(owner=os.geteuid())
            prefix = 'hts02-' + uuid.uuid4().hex[:12]
            stage, prod = prefix + '-staging', prefix + '-production'
            stage_dir, prod_dir = directory / 'staging', directory / 'production'
            stage_dir.mkdir(mode=0o700)
            prod_dir.mkdir(mode=0o700)
            (stage_dir / '.staging-release').mkdir(mode=0o700)
            tags = []
            checks_completed = False

            def command(argv, payload=None, timeout=120):
                code, raw = runner(argv, timeout=timeout, payload=payload)
                self.assertEqual(code, 0, 'fixture command failed (private output suppressed): ' + ' '.join(argv[:3]))
                return raw

            def inventory():
                ids = command(['docker', 'ps', '-aq', '--no-trunc']).decode().splitlines()
                if not ids:
                    return {}
                # Foreign containers must remain untouched, including runtime fields.
                return {item['Id']: release.fingerprint(release.stable_container(item) | {
                            'RestartCount': item['RestartCount'], 'started_at': item['State']['StartedAt'],
                            'networks': item['NetworkSettings']['Networks']})
                        for item in json.loads(command(['docker', 'inspect', *ids]))}

            before = inventory()
            command([shutil.which('openssl'), 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                     '-keyout', str(directory / 'key.pem'), '-out', str(directory / 'cert.pem'),
                     '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'])
            (directory / 'server.py').write_text(SERVER)
            try:
                # Existing native PostgreSQL image is reused by ID; no user tag is changed.
                code, raw = runner(['docker', 'image', 'inspect', 'postgres:17'], timeout=10)
                if code:
                    command(['docker', 'pull', 'postgres:17'], timeout=180)
                    raw = command(['docker', 'image', 'inspect', 'postgres:17'])
                pg_image = json.loads(raw)[0]['Id']
                code, raw = runner(['docker', 'image', 'inspect', 'python:3.12-alpine'], timeout=10)
                if code:
                    command(['docker', 'pull', '--platform', 'linux/amd64', 'python:3.12-alpine'], timeout=180)
                    raw = command(['docker', 'image', 'inspect', 'python:3.12-alpine'])
                # Classic stores return a platform image directly. A containerd index
                # needs explicit selection to verify the requested architecture.
                if not json.loads(raw)[0].get('Architecture'):
                    raw = command(['docker', 'image', 'inspect', 'python:3.12-alpine', '--platform', 'linux/amd64'])
                python_base = json.loads(raw)[0]
                self.assertEqual(python_base['Os'], 'linux')
                self.assertEqual(python_base['Architecture'], 'amd64', 'fixture needs amd64 Python base; do not overwrite another platform tag')
                (directory / 'Dockerfile').write_text(
                    'FROM ' + python_base['RepoDigests'][0] + '\n'
                    'ARG REVISION\nARG READY=good\n'
                    'LABEL org.opencontainers.image.source="' + release.SOURCE + '"\n'
                    'LABEL org.opencontainers.image.revision=$REVISION\n'
                    'ENV FIXTURE_READY=$READY\nCOPY server.py cert.pem key.pem /fixture/\n'
                    'CMD ["python3","/fixture/server.py"]\n')
                images = []
                for letter, ready in (('a', 'good'), ('b', 'good'), ('c', 'bad')):
                    tag = prefix + ':' + letter
                    tags.append(tag)
                    command(['docker', 'build', '--platform', 'linux/amd64', '--provenance=false', '--network', 'none', '--pull=false',
                             '--build-arg', 'REVISION=' + letter * 40, '--build-arg', 'READY=' + ready,
                             '-t', tag, str(directory)], timeout=180)
                    identity = json.loads(command(['docker', 'image', 'inspect', tag]))[0]['Id']
                    images.append({'id': identity, 'source': release.SOURCE, 'revision': letter * 40,
                                   'platform': 'linux/amd64', 'ci_sha': letter * 40, 'ci_status': 'success',
                                   'approved': True, 'available': True, 'postgresql_head': 125,
                                   'migration_fingerprint': '3' * 64})
                current, candidate, bad = images
                http_port, tls_port = free_port(), free_port()
                stage_env = ('POSTGRES_DB=staging\nPOSTGRES_USER=staging\nPOSTGRES_PASSWORD=fixture-private-secret\n'
                             'BACKEND_IMAGE=' + current['id'] + '\nAPP_VERSION=' + current['revision'] + '\n')
                (stage_dir / '.env').write_text(stage_env)
                (stage_dir / '.env').chmod(0o600)
                pg_service = {'image': pg_image, 'environment': {'POSTGRES_DB': '${POSTGRES_DB}',
                              'POSTGRES_USER': '${POSTGRES_USER}', 'POSTGRES_PASSWORD': '${POSTGRES_PASSWORD}'},
                              'volumes': ['pgdata:/var/lib/postgresql/data'], 'mem_limit': '256m', 'cpus': 0.5}
                compose = {'services': {'postgres': pg_service, 'backend': {
                    'image': '${BACKEND_IMAGE}', 'platform': 'linux/amd64', 'env_file': ['./.env'],
                    'environment': {'APP_ENV': 'staging', 'APP_VERSION': '${APP_VERSION}',
                                    'TELEGRAM_TRAFFIC_POLICY': 'PRODUCT', 'STAGING_MAINTENANCE_MODE': 'OFF',
                                    'DB_JDBC_URL': 'jdbc:postgresql://postgres:5432/${POSTGRES_DB}',
                                    'DB_USER': '${POSTGRES_USER}', 'DB_PASSWORD': '${POSTGRES_PASSWORD}'},
                    'ports': ['127.0.0.1:' + str(http_port) + ':8080', '127.0.0.1:' + str(tls_port) + ':8443'],
                    'mem_limit': '64m', 'cpus': 0.5}}, 'volumes': {'pgdata': {}}}
                (stage_dir / 'docker-compose.yml').write_text(json.dumps(compose))
                (prod_dir / '.env').write_text('POSTGRES_DB=production\nPOSTGRES_USER=production\nPOSTGRES_PASSWORD=fixture-private-secret\n')
                (prod_dir / '.env').chmod(0o600)
                prod_pg_service = copy.deepcopy(pg_service)
                prod_pg_service['volumes'] += [str(directory / name) + ':/ht-fixture/' + name + ':ro'
                                               for name in ('cert.pem', 'key.pem')]
                (prod_dir / 'docker-compose.yml').write_text(json.dumps({'services': {'postgres': prod_pg_service}, 'volumes': {'pgdata': {}}}))

                def compose_argv(project, folder):
                    return ['docker', 'compose', '--project-name', project, '--project-directory', str(folder),
                            '--env-file', str(folder / '.env'), '--file', str(folder / 'docker-compose.yml')]

                command(compose_argv(prod, prod_dir) + ['up', '-d', '--no-build', '--pull', 'never'])
                command(compose_argv(stage, stage_dir) + ['up', '-d', '--no-build', '--pull', 'never'])
                pg_id = command(compose_argv(stage, stage_dir) + ['ps', '-q', 'postgres']).decode().strip()
                prod_id = command(compose_argv(prod, prod_dir) + ['ps', '-q', 'postgres']).decode().strip()
                for database_id in (pg_id, prod_id):
                    deadline = time.monotonic() + 40
                    while True:
                        # The temporary init server accepts sockets before final TCP startup.
                        code, _ = runner(['docker', 'exec', database_id, 'pg_isready', '-h', '127.0.0.1',
                                          '-U', 'staging', '-d', 'staging'], timeout=5)
                        if code == 0:
                            break
                        self.assertLess(time.monotonic(), deadline, 'fixture PostgreSQL startup timeout')
                        time.sleep(0.2)
                command(['docker', 'exec', '-i', pg_id, 'psql', '-XqAtw', '-U', 'staging', '-d', 'staging',
                         '--set=ON_ERROR_STOP=1'], payload=b'CREATE TABLE flyway_schema_history(version text,success boolean); INSERT INTO flyway_schema_history VALUES (\'125\',true);')
                scope = {'project_id': 'Hookah_Tootah', 'project': stage, 'directory': str(stage_dir),
                         'compose_file': str(stage_dir / 'docker-compose.yml'),
                         'local_url': 'http://127.0.0.1:' + str(http_port), 'public_url': 'https://localhost:' + str(tls_port),
                         'compose_fingerprint': '1' * 64, 'configuration_fingerprint': '2' * 64,
                         'migration_fingerprint': '3' * 64,
                         'protected': {'postgres': {'container_id': pg_id, 'system_id': 'fixture', 'database': 'staging',
                                      'user': 'staging', 'volume': stage + '_pgdata', 'network': stage + '_default', 'fingerprint': '4' * 64},
                                      'production': {'project': prod, 'database_container_id': prod_id,
                                      'volumes': [prod + '_pgdata'], 'networks': [prod + '_default'], 'fingerprint': '5' * 64},
                                      'shared_fingerprint': '6' * 64}}
                runtime = release.CommandRuntime(scope, {r['id']: r for r in images}, runner=runner, file_reader=files.read,
                                                 configuration=release.ReleaseConfiguration(stage_dir, files))
                snapshot = runtime.observe(timeout=5)
                for key in ('compose_fingerprint', 'configuration_fingerprint', 'protected'):
                    scope[key] = snapshot[key]
                registered = {'target': 'fixture-staging', 'transition_approved': True, 'scope': scope}
                registration_path = directory / 'registration.json'
                registration_path.write_text(json.dumps(registered))
                registration_path.chmod(0o600)
                registered = release.registration('fixture-staging', path=registration_path, files=files)
                state_path = stage_dir / '.staging-release' / 'state.json'

                def action(name, **kwargs):
                    return release.registered_action({'target': 'fixture-staging', 'action': name, **kwargs},
                                                     registered, runner=runner, files=files, readiness_seconds=5)

                deadline = time.monotonic() + 20
                while not runtime.ready(current, timeout=5):
                    self.assertLess(time.monotonic(), deadline, 'fixture HTTP/TLS startup timeout')
                    time.sleep(0.2)
                self.assertEqual(action('initialize', candidate=current, owner_approved=True)['state'], 'ACCEPTED')
                # Restart only this fixture's protected production container, retaining the pinned scope.
                prod_before = json.loads(command(['docker', 'inspect', prod_id]))[0]
                config_before = command(compose_argv(prod, prod_dir) + ['config', '--format', 'json'])
                self.assertFalse('mac_address' in config_before.decode(), 'fixture must use dynamic MAC')
                command(compose_argv(prod, prod_dir) + ['restart', 'postgres'])
                prod_after = json.loads(command(['docker', 'inspect', prod_id]))[0]
                self.assertTrue(config_before == command(compose_argv(prod, prod_dir) + ['config', '--format', 'json']),
                                'fixture production Compose configuration changed')
                assert_restart_configuration(self, prod_before, prod_after)
                changed_endpoints = sorted({key for network, endpoint in prod_before['NetworkSettings']['Networks'].items()
                                            for key in endpoint if endpoint[key] != prod_after['NetworkSettings']['Networks'].get(network, {}).get(key)})
                print(json.dumps({'restart_changed_network_settings': sorted(key for key in prod_before['NetworkSettings']
                                  if prod_before['NetworkSettings'][key] != prod_after['NetworkSettings'].get(key)),
                                  'restart_changed_endpoint_fields': changed_endpoints,
                                  'compose_identity_image_mounts_unchanged': True, 'static_mac_configured': False}))
                self.assertEqual(prod_before['Id'], prod_after['Id'])
                self.assertNotEqual(prod_before['State']['StartedAt'], prod_after['State']['StartedAt'])
                pinned_before = release.stable_container(prod_before)
                pinned_after = release.stable_container(prod_after)
                changed_network_fields = sorted({key for network, endpoint in pinned_before['networks'].items()
                                                for key in endpoint if endpoint[key] != pinned_after['networks'].get(network, {}).get(key)})
                self.assertTrue(release.fingerprint(pinned_before) == release.fingerprint(pinned_after),
                                'fixture restart changed pinned network fields: ' + ','.join(changed_network_fields[:16]))
                release.validate_snapshot(runtime.observe(timeout=5), scope, current['id'])
                # Replay only the presentation order of real inspect mount records.
                self.assertEqual(len(prod_after['Mounts']), 3)
                def reordered_mounts(argv, **kwargs):
                    code, raw = runner(argv, **kwargs)
                    if code == 0 and argv[:2] == ['docker', 'inspect']:
                        inspected = json.loads(raw)
                        for item in inspected:
                            if item['Id'] == prod_id:
                                item['Mounts'].reverse()
                        raw = json.dumps(inspected).encode()
                    return code, raw

                reordered_runtime = release.CommandRuntime(scope, {r['id']: r for r in images},
                                                           runner=reordered_mounts, file_reader=files.read,
                                                           configuration=runtime.configuration)
                release.validate_snapshot(reordered_runtime.observe(timeout=5), scope, current['id'])
                changed_mount = copy.deepcopy(prod_after)
                changed_mount['Mounts'][0]['RW'] = not changed_mount['Mounts'][0]['RW']
                self.assertNotEqual(release.fingerprint(release.stable_container(prod_after)),
                                    release.fingerprint(release.stable_container(changed_mount)))
                # Wrong DB/target fails before persistent configuration or Docker mutation.
                denied = copy.deepcopy(registered)
                denied['scope']['protected']['postgres']['system_id'] = 'wrong'
                denied_runtime = release.CommandRuntime(denied['scope'], {r['id']: r for r in images}, runner=runner,
                                                        file_reader=files.read, configuration=runtime.configuration)
                with self.assertRaisesRegex(release.Refused, '^PROTECTED_RESOURCES_CHANGED$'):
                    release.validate_snapshot(denied_runtime.observe(timeout=5), denied['scope'], current['id'])
                self.assertEqual((stage_dir / '.env').read_text(), stage_env)
                self.assertEqual(action('deploy', candidate=candidate, expected_current=current['id'], owner_approved=True),
                                 {'status': 'SUCCESS', 'state': 'READY_FOR_SMOKE'})
                self.assertEqual(action('accept', smoke_passed=True)['state'], 'ACCEPTED')
                self.assertEqual(action('deploy', candidate=candidate, expected_current=candidate['id'], owner_approved=True),
                                 {'status': 'FAILED', 'reason': 'IMAGE_ALREADY_ACCEPTED'})
                with release.Store(stage_dir / '.staging-release').locked():
                    self.assertEqual(action('deploy', candidate=bad, expected_current=candidate['id'], owner_approved=True),
                                     {'status': 'FAILED', 'reason': 'DEPLOYMENT_BUSY'})
                self.assertEqual(action('deploy', candidate=None, expected_current=candidate['id'], owner_approved=True),
                                 {'status': 'FAILED', 'reason': 'IMAGE_MISSING'})
                # No special env overrides, a fresh default Compose call still selects B.
                rendered = json.loads(command(compose_argv(stage, stage_dir) + ['config', '--format', 'json']))
                self.assertEqual(rendered['services']['backend']['image'], candidate['id'])
                self.assertEqual(rendered['services']['backend']['environment']['APP_VERSION'], candidate['revision'])
                command(compose_argv(stage, stage_dir) + ['up', '-d', '--no-build', '--pull', 'never', '--no-deps', 'backend'])
                command(compose_argv(stage, stage_dir) + ['restart', 'backend'])
                fresh_runtime = release.CommandRuntime(scope, {r['id']: r for r in images}, runner=runner,
                                   file_reader=files.read, configuration=runtime.configuration)
                deadline = time.monotonic() + 20
                while not fresh_runtime.ready(candidate, timeout=5):
                    self.assertLess(time.monotonic(), deadline, 'fixture restarted backend readiness timeout')
                    time.sleep(0.2)
                release.validate_snapshot(fresh_runtime.observe(timeout=5), scope, candidate['id'])
                self.assertEqual(action('status')['current_image'], candidate['id'])
                failed = action('deploy', candidate=bad, expected_current=candidate['id'], owner_approved=True)
                self.assertEqual(failed['status'], 'FAILED', json.loads(state_path.read_text()).get('diagnostic'))
                self.assertEqual(action('status')['state'], 'READINESS_FAILED')
                self.assertEqual(action('rollback', owner_approved=True)['state'], 'READY_FOR_SMOKE')
                self.assertEqual(action('accept', smoke_passed=True)['state'], 'ACCEPTED')
                self.assertIn('BACKEND_IMAGE=' + candidate['id'], (stage_dir / '.env').read_text())
                # Real Docker succeeds, but its result is deliberately lost at the adapter boundary.
                mutations = []

                def lost_result(argv, **kwargs):
                    result = runner(argv, **kwargs)
                    if argv[:2] == ['docker', 'compose'] and 'up' in argv:
                        mutations.append(argv)
                        raise TimeoutError()
                    return result

                unknown = release.registered_action({'target': 'fixture-staging', 'action': 'deploy', 'candidate': current,
                             'expected_current': candidate['id'], 'owner_approved': True}, registered,
                             runner=lost_result, files=files, readiness_seconds=1)
                self.assertEqual(unknown['status'], 'UNKNOWN')
                self.assertEqual(action('rollback', owner_approved=True)['status'], 'UNKNOWN')
                self.assertEqual(action('deploy', candidate=bad, expected_current=candidate['id'], owner_approved=True)['status'], 'UNKNOWN')
                self.assertEqual(len(mutations), 1)
                self.assertEqual(json.loads(state_path.read_text())['status'], 'UNKNOWN')
                self.assertNotIn('fixture-private-secret', state_path.read_text())
                final_runtime = release.CommandRuntime(scope, {r['id']: r for r in images}, runner=runner,
                                                      file_reader=files.read, configuration=runtime.configuration)
                release.validate_snapshot(final_runtime.observe(timeout=5), scope, current['id'])
                checks_completed = True
            finally:
                # Exact fixture-owned resources only; never daemon-wide prune or user containers.
                for project, folder in ((stage, stage_dir), (prod, prod_dir)):
                    if (folder / 'docker-compose.yml').exists():
                        command(['docker', 'compose', '--project-name', project, '--project-directory', str(folder),
                                 '--env-file', str(folder / '.env'), '--file', str(folder / 'docker-compose.yml'),
                                 'down', '--volumes'], timeout=60)
                for tag in tags:
                    runner(['docker', 'image', 'rm', tag], timeout=30)
                after = inventory()
                self.assertEqual(before, after, 'pre-existing container identities changed during fixture')
                print(json.dumps({'fixture': 'DISPOSABLE_REAL_DOCKER_COMPOSE', 'existing_containers_unchanged': len(before),
                                  'checks_completed': checks_completed, 'fixture_resources_removed': True}))


if __name__ == '__main__':
    unittest.main()
