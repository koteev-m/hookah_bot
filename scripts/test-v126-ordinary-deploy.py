#!/usr/bin/env python3
"""Ordinary worker regressions: real framing/processes/guards, explicit Docker data.

Portable cases never contact Docker daemons, SSH, Caddy, PostgreSQL or Telegram.
The fixture YAML check uses real daemon-free Compose config. Docker inspection
and HTTP responses are explicit dependency fixtures; the actual
descriptor, image, runtime, readiness and producer-status consumers are retained.
The bootstrap framing case has a synthetic receiver only to observe exact remaining
stdin bytes. It does not claim target-lock or daemon verification.
"""
import copy
import contextlib
import ast
import fcntl
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import threading
import unittest
from unittest import mock


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ordinary_deployment', ROOT / 'scripts/v126-ordinary-deploy.py')
ordinary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ordinary)
IMAGE = 'fixture/backend:' + '1' * 40
IMAGE_ID = 'sha256:' + '2' * 64
CID = '3' * 64
SECRET = 'synthetic-only-secret-never-output'


def owned_bootstrap(config_path):
    """Only the canonical Caddy Path maps; supervisor/worker bytes stay real."""
    mapping = "\noriginal_path=scope['Path']\ndef FixturePath(*args,**kwargs):\n if args == ('/etc/caddy/Caddyfile',): args=(" + repr(str(config_path)) + ",)\n return original_path(*args,**kwargs)\nscope['Path']=FixturePath\n"
    worker = ordinary.WORKER_BOOTSTRAP.replace(" scope['worker'](", ''.join(' ' + line + '\n' for line in mapping.strip().splitlines()) + " scope['worker'](")
    remote = ordinary.REMOTE_BOOTSTRAP.replace("raise SystemExit(scope['remote']", 'scope["WORKER_BOOTSTRAP"]=' + repr(worker) + '\nraise SystemExit(scope[\'remote\']')
    return worker, remote


def fixture_compose_yaml(document, indent=0):
    """Deterministic YAML for this fixture; retain the real textual env guard."""
    def scalar(value):
        return './.env' if value == './.env' else json.dumps(value, ensure_ascii=True)
    prefix = ' ' * indent
    lines = []
    if isinstance(document, dict):
        for key, value in document.items():
            ordinary.require(re.fullmatch('[A-Za-z0-9_.-]+', key), 'fixture YAML key')
            if isinstance(value, (dict, list)) and value:
                lines.append(prefix + key + ':')
                lines.extend(fixture_compose_yaml(value, indent + 2).splitlines())
            else:
                lines.append(prefix + key + ': ' + scalar(value))
    elif isinstance(document, list):
        for value in document:
            ordinary.require(not isinstance(value, (dict, list)), 'fixture YAML scalar list')
            lines.append(prefix + '- ' + scalar(value))
    else:
        raise ordinary.Refused('fixture YAML container')
    return '\n'.join(lines) + '\n'


class DeferredFixtureDirectory:
    """Retain referenced files until existing resource cleanup proves success."""
    def __init__(self, temporary):
        self.temporary = temporary
        self.name = temporary.name
        self._finalizer = temporary._finalizer
        self._finalizer.detach()
        self.requested = False

    def cleanup(self):
        # The borrowed runtime cleanup requests directory removal before its
        # final resource check. Defer that filesystem-only action to our caller.
        self.requested = True

    def remove_after_quiescence(self):
        ordinary.require(self.requested, 'runtime cleanup must complete before file removal')
        self.temporary.cleanup()


class OrdinaryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='v126-ordinary-deploy-')
        self.addCleanup(self.temporary.cleanup)
        self.target = Path(self.temporary.name).resolve()
        self.uri = self.target / 'database-uri'
        self.uri.write_text('postgresql://fixture:synthetic@127.0.0.1/db\n')
        self.doc = dict(format_version=1,
                        owner=dict(run_id='ordinary-fixture', release_sha='1' * 40, script_sha256='4' * 64),
                        target_sha256=ordinary.sha(str(self.target).encode()), operational_version='V126',
                        backend_image=IMAGE, image_id=IMAGE_ID, image_source=ordinary.SOURCE, platform='linux/amd64',
                        environment_sha256='5' * 64, compose_before_sha256='6' * 64, compose_sha256='7' * 64,
                        caddy_config_sha256='8' * 64, caddy_runtime_sha256='9' * 64,
                        database_url_file=str(self.uri), database_url_sha256=ordinary.sha(self.uri.read_bytes()),
                        database_identity_sha256='a' * 64, config_owner='root:root', restart_policy='unless-stopped',
                        handoff_approved_and_applied=True, approval_id='synthetic-approved', observed_at='2026-09-10T00:00:00Z',
                        files={name: '7' * 64 for name in ordinary.UPLOADS}, public_url='https://fixture.invalid', public_checks=True)
        self.deploy = ordinary.Deployment(self.target, self.doc, ROOT)

    def test_provider_accepts_exact_source_command_menu_and_latches_other_requests(self):
        # Execute the real HTTP parser/handler with in-memory wire transport;
        # only the TLS/listener bootstrap is excluded. No socket is opened.
        provider = ROOT / 'scripts/fixtures/v126-linux-runtime/telegram-provider.py'
        nodes = []
        for node in ast.parse(provider.read_text()).body:
            if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == 'server'
                                                   for target in node.targets):
                break
            nodes.append(node)
        scope = {'__name__': 'ordinary_synthetic_provider'}
        with mock.patch.dict(os.environ, {'FIXTURE_TELEGRAM_TOKEN': SECRET}):
            exec(compile(ast.Module(body=nodes, type_ignores=[]), str(provider), 'exec'), scope)

        # Bind the wire fixture to the actual Kotlin producer, without replacing
        # that producer or reproducing its dispatch/maintenance algorithms.
        client = (ROOT / 'backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramApiClient.kt').read_text()
        method = client.split('suspend fun setCommandsMenuButton(): TelegramCallResult {', 1)[1].split(
            'private suspend fun callMethod(', 1)[0]
        call = re.search(r'return callMethod\(\s*"([A-Za-z]+)",\s*buildJsonObject\s*\{\s*put\(\s*"([a-z_]+)",\s*'
                         r'buildJsonObject\s*\{\s*put\("([a-z_]+)",\s*"([a-z_]+)"\)\s*}\s*,?\s*\)\s*,?\s*}\s*,?\s*\)', method)
        self.assertIsNotNone(call, 'actual command-menu producer schema changed; review synthetic endpoint contract')
        api_method, outer, key, value = call.groups()
        self.assertEqual((api_method, outer, key, value), ('setChatMenuButton', 'menu_button', 'type', 'commands'))
        actual_body = json.dumps({outer: {key: value}}, separators=(',', ':')).encode()

        class Wire:
            def __init__(self, request):
                self.input, self.output = io.BytesIO(request), bytearray()
            def makefile(self, *args):
                return self.input
            def sendall(self, data):
                self.output.extend(data)

        def request(body=actual_body, *, host='api.telegram.org', token=SECRET, method=api_method,
                    verb='POST', suffix='', content_type='application/json'):
            wire = Wire((verb + ' /bot' + token + '/' + method + suffix + ' HTTP/1.1\r\nHost: ' + host +
                         '\r\nContent-Type: ' + content_type + '\r\nContent-Length: ' + str(len(body)) +
                         '\r\nConnection: close\r\n\r\n').encode() + body)
            scope['Handler'](wire, ('127.0.0.1', 41001), object())
            return int(bytes(wire.output).split(b' ', 2)[1])

        for body in (actual_body, b'{ "menu_button" : { "type" : "commands" } }'):
            self.assertEqual(request(body), 200)
        self.assertEqual(scope['STATE']['setChatMenuButton'], 2)
        self.assertEqual(scope['STATE']['unexpected'], 0)
        self.assertEqual(scope['STATE']['outbound'], 0)
        cases = [dict(host='127.0.0.1'), dict(host='external.invalid'), dict(token='wrong'),
                 dict(method='sendMessage'), dict(method='getWebhookInfo'), dict(verb='GET'),
                 dict(suffix='?chat_id=1'), dict(content_type='text/plain')]
        cases.extend(dict(body=body) for body in (
            b'{"chat_id":1,"menu_button":{"type":"commands"}}',
            b'{"menu_button":{"type":"web_app","web_app":{"url":"https://external.invalid"}}}',
            b'{"menu_button":{"type":"commands","extra":true}}', b'{"menu_button":{"type":"commands"},"extra":true}',
            b'{"menu_button":{"type":"commands","type":"commands"}}',
            b'{"menu_button":{"type":"commands"},"menu_button":{"type":"commands"}}',
            b'[["menu_button",[["type","commands"]]]]', b'null', b'{', b'\xff'))
        expected_outbound = 0
        for index, case in enumerate(cases, 1):
            with self.subTest(case=index):
                self.assertEqual(request(**case), 403)
                expected_outbound += case.get('verb') != 'GET'
                self.assertEqual(scope['STATE']['unexpected'], index)
                self.assertEqual(scope['STATE']['outbound'], expected_outbound)
                self.assertEqual(scope['STATE']['setChatMenuButton'], 2)
        # A later valid request cannot erase earlier refused operations.
        self.assertEqual(request(), 200)
        self.assertEqual(scope['STATE']['unexpected'], len(cases))
        self.assertEqual(scope['STATE']['outbound'], expected_outbound)

    def test_descriptor_rejects_stale_wrong_and_implicit_authority(self):
        ordinary.validate_descriptor(self.doc, self.target)
        faults = {'format_version': True, 'handoff_approved_and_applied': False, 'restart_policy': 'no',
                  'config_owner': '501:20', 'target_sha256': 'b' * 64, 'backend_image': 'fixture/backend:latest',
                  'image_id': 'sha256:wrong', 'image_source': 'https://unexpected.invalid/repo', 'platform': 'linux/arm64',
                  'environment_sha256': 'wrong', 'database_url_file': 'relative', 'public_checks': 'true',
                  'public_url': 'http://unexpected.invalid', 'observed_at': 'yesterday'}
        for key, value in faults.items():
            with self.subTest(key=key), self.assertRaises((ordinary.Refused, ValueError)):
                ordinary.validate_descriptor(dict(self.doc, **{key: value}), self.target)
        for edit in ('extra', 'missing', 'environment-upload', 'wrong-compose-hash'):
            value = copy.deepcopy(self.doc)
            if edit == 'extra': value['unexpected'] = 1
            elif edit == 'missing': del value['approval_id']
            elif edit == 'environment-upload': value['files']['.env'] = 'a' * 64
            else: value['files']['docker-compose.yml'] = 'a' * 64
            with self.subTest(edit=edit), self.assertRaises(ordinary.Refused):
                ordinary.validate_descriptor(value, self.target)

    def test_canonical_request_and_source_binding(self):
        raw = ordinary.canonical(self.doc)
        self.assertEqual(ordinary.structured(raw), self.doc)
        for value in (b'{"a":1,"a":1}\n', b'{ "a": 1 }\n', b'{}', b'{}\nextra'):
            with self.subTest(value=value), self.assertRaises((ordinary.Refused, ValueError)):
                ordinary.structured(value)
        bundle = ordinary.source_bundle()
        decoded = ordinary.decode_bundle(bundle, ordinary.sha(bundle))
        self.assertEqual(set(decoded), set(ordinary.SOURCES))
        self.assertEqual(decoded['scripts/v126-cutover.sh'], (ROOT / 'scripts/v126-cutover.sh').read_bytes())
        with self.assertRaises(ordinary.Refused): ordinary.decode_bundle(bundle + b' ', ordinary.sha(bundle))
        with self.assertRaises(ordinary.Refused): ordinary.decode_bundle(b'{}\n', ordinary.sha(b'{}\n'))

    def test_transport_frames_preserve_bytes_and_reject_incomplete_payloads(self):
        stream = io.BytesIO()
        ordinary.frame(stream, b'abc\x00def')
        ordinary.frame(stream, b'next')
        stream.seek(0)
        self.assertEqual(ordinary.read_frame(stream, 32), b'abc\x00def')
        self.assertEqual(ordinary.read_frame(stream, 32), b'next')
        for raw in (b'', b'\x00' * 8, b'\x00' * 7 + b'\x10short', b'\xff' * 8):
            with self.subTest(raw=raw), self.assertRaises(ordinary.Refused):
                ordinary.read_frame(io.BytesIO(raw), 32)

    def test_bootstrap_does_not_lose_prefetched_payload_before_supervisor(self):
        receiver = b"from pathlib import Path\ndef remote(target,bundle,request,stream):\n import sys\n sys.stdout.buffer.write(stream.read())\n return 0\n"
        bundle = ordinary.canonical({'scripts/v126-ordinary-deploy.py': ordinary.base64.b64encode(receiver).decode()})
        request = ordinary.canonical({'owner': {'script_sha256': ordinary.sha(bundle)}})
        stream = io.BytesIO()
        ordinary.frame(stream, bundle)
        ordinary.frame(stream, request)
        payload = b'owned-payload\x00' * 1000
        stream.write(payload)
        result = subprocess.run([sys.executable, '-c', ordinary.REMOTE_BOOTSTRAP, str(self.target)],
                                input=stream.getvalue(), capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, payload)

    def test_real_producer_exit_and_output_bounds(self):
        self.assertEqual(ordinary.command([sys.executable, '-c', 'print("actual output")']), b'actual output\n')
        with self.assertRaises(ordinary.Refused):
            ordinary.command([sys.executable, '-c', 'print("valid-looking");raise SystemExit(42)'])
        with self.assertRaisesRegex(ordinary.Refused, 'output exceeded'):
            ordinary.command([sys.executable, '-c', 'import os;os.write(1,b"x"*5000000)'])

    def test_bound_file_output_and_stderr_keep_real_process_status(self):
        with tempfile.TemporaryFile() as output:
            ordinary.command([sys.executable, '-c', 'print("actual file output")'], output=output)
            output.seek(0)
            self.assertEqual(output.read(), b'actual file output\n')
        for program, message in [('import os;os.write(1,b"x"*8192)', 'file consumer output'),
                                 ('import os;os.write(2,b"x"*5000000)', 'consumer output')]:
            with self.subTest(program=program), tempfile.TemporaryFile() as output, self.assertRaisesRegex(ordinary.Refused, message):
                ordinary.command([sys.executable, '-c', program], output=output, output_limit=4096)
        with tempfile.TemporaryFile() as output, self.assertRaises(ordinary.Refused):
            ordinary.command([sys.executable, '-c', 'print("valid-looking");raise SystemExit(42)'], output=output)

    def test_final_file_bound_refuses_without_signalling_reaped_process_group(self):
        # Only scheduling and the group-signal syscall are instrumented. The
        # producer, selector I/O, kernel wait and final production guard are real.
        permit = self.target / 'producer-permit'
        program = ('import os,pathlib,time; p=pathlib.Path(' + repr(str(permit)) + '); '
                   '\nwhile not p.exists(): time.sleep(.001)'
                   '\nos.write(1,b"x"*8192)')
        real_popen = subprocess.Popen
        real_killpg = os.killpg
        process = None
        signals = []
        selector = ordinary.selectors.DefaultSelector()
        real_select = selector.select

        def launch(*args, **kwargs):
            nonlocal process
            process = real_popen(*args, **kwargs)
            return process

        def finish_before_final_bound(timeout):
            permit.touch()
            self.assertEqual(process.wait(timeout=3), 0)
            return real_select(timeout)

        def observe_group_signal(pid, signum):
            signals.append((pid, signum, process.returncode))
            # Never issue the unsafe stale-group syscall while reproducing it.
            if process.returncode is None:
                return real_killpg(pid, signum)

        with tempfile.TemporaryFile() as output, \
                mock.patch.object(ordinary.subprocess, 'Popen', side_effect=launch), \
                mock.patch.object(ordinary.selectors, 'DefaultSelector', return_value=selector), \
                mock.patch.object(selector, 'select', side_effect=finish_before_final_bound), \
                mock.patch.object(ordinary.os, 'killpg', side_effect=observe_group_signal):
            with self.assertRaisesRegex(ordinary.Refused, 'bounded file consumer output exceeded'):
                ordinary.command([sys.executable, '-c', program], output=output, output_limit=4096)
            self.assertEqual(os.fstat(output.fileno()).st_size, 8192)
        self.assertEqual(process.returncode, 0, 'the actual leader must already be reaped')
        self.assertEqual(signals, [], 'a reaped leader no longer reserves its process group')

    def test_owned_caddy_bootstrap_compiles_and_maps_only_exact_path(self):
        worker, remote = owned_bootstrap(self.target / 'own-caddy')
        compile(worker, '<owned-worker>', 'exec')
        compile(remote, '<owned-remote>', 'exec')
        # Execute only the added mapping with real Path. This is an explicit
        # fixture mapping test, independent of real supervisor coverage below.
        scope = {'Path': Path}
        added = worker[worker.index(" original_path=scope['Path']"):worker.index(" scope['worker'](")]
        exec('\n'.join(line[1:] for line in added.splitlines()), {'scope': scope})
        self.assertEqual(scope['Path']('/etc/caddy/Caddyfile'), self.target / 'own-caddy')
        self.assertEqual(scope['Path']('/etc/caddy/other'), Path('/etc/caddy/other'))
        self.assertIn("scope['worker'](", worker)
        self.assertIn("scope['remote'](", remote)

    def test_real_hung_consumer_and_owned_child_are_bounded(self):
        marker = self.target / 'child-effect'
        child = 'import pathlib,time;time.sleep(1.1);pathlib.Path(' + repr(str(marker)) + ').write_text("late")'
        parent = 'import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",' + repr(child) + ']);time.sleep(4)'
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired):
            ordinary.command([sys.executable, '-c', parent], timeout=0.2)
        self.assertLess(time.monotonic() - started, 1.5)
        time.sleep(1.2)
        self.assertFalse(marker.exists(), 'own descendant continued after command deadline')

    def test_real_create_only_and_metadata_refusal(self):
        record = self.target / 'record.json'
        ordinary.create(record, b'{}\n')
        ordinary.protected(record, 0o400, root=False)
        with self.assertRaises(FileExistsError): ordinary.create(record, b'changed')
        self.assertEqual(record.read_bytes(), b'{}\n')
        link = self.target / 'link'
        link.symlink_to(record)
        with self.assertRaises(ordinary.Refused): ordinary.protected(link, 0o400, root=False)
        os.link(record, self.target / 'hardlink')
        with self.assertRaises(ordinary.Refused): ordinary.protected(record, 0o400, root=False)

    def test_fixture_files_survive_failed_resource_cleanup(self):
        directory = DeferredFixtureDirectory(tempfile.TemporaryDirectory(prefix='v126-retained-ordinary-'))
        path = Path(directory.name)
        try:
            with self.assertRaises(ordinary.Refused): directory.remove_after_quiescence()
            directory.cleanup()
            self.assertTrue(path.is_dir(), 'cleanup request removed referenced files before proof')
            directory.remove_after_quiescence()
            self.assertFalse(path.exists())
        finally:
            directory.temporary.cleanup()

    def test_actual_image_consumer_requires_full_authority(self):
        valid = {'Id': IMAGE_ID, 'Os': 'linux', 'Architecture': 'amd64', 'Config': {'Labels': {
            'org.opencontainers.image.source': ordinary.SOURCE, 'org.opencontainers.image.revision': '1' * 40}}}
        with mock.patch.object(ordinary, 'command', return_value=json.dumps([valid]).encode()) as process:
            self.deploy.image()
            self.assertEqual(process.call_args.args[0], ['docker', 'image', 'inspect', IMAGE])
        for key, value in [('Id', 'sha256:' + 'a' * 64), ('Architecture', 'arm64'), ('Os', 'windows')]:
            changed = dict(valid, **{key: value})
            with self.subTest(key=key), mock.patch.object(ordinary, 'command', return_value=json.dumps([changed]).encode()), self.assertRaises(ordinary.Refused):
                self.deploy.image()
        changed = copy.deepcopy(valid)
        changed['Config']['Labels']['org.opencontainers.image.source'] = 'unapproved'
        with mock.patch.object(ordinary, 'command', return_value=json.dumps([changed]).encode()), self.assertRaises(ordinary.Refused):
            self.deploy.image()

    def backend_fixture(self):
        environment = {'APP_VERSION': '1' * 40, 'TELEGRAM_TRAFFIC_POLICY': 'PRODUCT',
                       'STAGING_MAINTENANCE_MODE': 'OFF', 'TELEGRAM_BOT_ENABLED': 'true',
                       'TELEGRAM_BOT_MODE': 'long_polling', 'DB_PASSWORD': SECRET}
        value = {'Id': CID, 'Image': IMAGE_ID, 'RestartCount': 0,
                 'State': {'Status': 'running', 'Running': True, 'OOMKilled': False, 'StartedAt': 'fixture'},
                 'HostConfig': {'RestartPolicy': {'Name': 'unless-stopped'}},
                 'NetworkSettings': {'Networks': {}}, 'Config': {
                     'Env': [key + '=' + item for key, item in environment.items()], 'Labels': {
                         'com.docker.compose.service': 'backend',
                         'com.docker.compose.project': 'ordinary-fixture',
                         'com.docker.compose.project.working_dir': str(self.target),
                         'com.docker.compose.project.config_files': str(self.target / 'docker-compose.yml')}}}
        return value, environment

    def test_actual_runtime_consumer_rejects_config_restart_and_identity_drift(self):
        value, environment = self.backend_fixture()
        def process(argv, **kwargs):
            if argv[:2] == ['docker', 'inspect']: return json.dumps([value]).encode()
            if 'config' in argv: return json.dumps({'services': {'backend': {'environment': environment}}}).encode()
            return (CID + '\n').encode()
        with mock.patch.object(ordinary, 'command', side_effect=process):
            self.assertTrue(self.deploy.backend(CID))
            for key, changed in [('Image', 'sha256:' + 'f' * 64), ('RestartCount', 1)]:
                old = value[key]
                value[key] = changed
                with self.subTest(key=key), self.assertRaises(ordinary.Refused): self.deploy.backend(CID)
                value[key] = old
            value['Config']['Env'].append('DB_PASSWORD=other')
            with self.assertRaises(ordinary.Refused): self.deploy.backend(CID)
            value['Config']['Env'].pop()
            value['HostConfig']['RestartPolicy']['Name'] = 'no'
            with self.assertRaises(ordinary.Refused): self.deploy.backend(CID)

    def test_readiness_503_then200_and_wrong_identity_without_mutation(self):
        value, environment = self.backend_fixture()
        http_calls, all_calls = [], []
        def process(argv, **kwargs):
            all_calls.append(argv)
            if argv[0] == 'curl':
                http_calls.append(argv[-1])
                if len(http_calls) == 1: return b'starting\n503'
                if argv[-1].endswith('/version'):
                    return json.dumps({'service': 'backend', 'env': 'staging', 'version': self.doc['owner']['release_sha']}).encode() + b'\n200'
                if argv[-1].endswith('/miniapp/'): return b'<html>owned fixture</html>\n200'
                return b'{"status":"ok"}\n200'
            if argv[:2] == ['docker', 'inspect']: return json.dumps([value]).encode()
            if 'config' in argv: return json.dumps({'services': {'backend': {'environment': environment}}}).encode()
            return (CID + '\n').encode()
        with mock.patch.object(ordinary, 'command', side_effect=process):
            self.deploy.readiness(CID, seconds=2)
        self.assertEqual(len(http_calls), 5)
        self.assertFalse(any(token in call for call in all_calls for token in ('up', 'start', 'restart', 'load')))
        self.doc['owner']['release_sha'] = 'f' * 40
        environment['APP_VERSION'] = '1' * 40
        def wrong(argv, **kwargs):
            if argv[0] == 'curl' and argv[-1].endswith('/version'):
                return b'{"service":"backend","env":"staging","version":"wrong"}\n200'
            return process(argv, **kwargs)
        with mock.patch.object(ordinary, 'command', side_effect=wrong), self.assertRaisesRegex(ordinary.Refused, 'version identity'):
            self.deploy.readiness(CID, seconds=2)

    def test_readiness_deadline_bounds_real_hung_metadata_dependency(self):
        actual_process = ordinary.command
        bounds = []
        def hung(argv, **kwargs):
            self.assertEqual(argv[0:2], ['docker', 'compose'])
            bounds.append(kwargs['timeout'])
            # Only the external Docker metadata process is substituted. The
            # actual runtime/readiness/deadline consumer and process adapter run.
            return actual_process([sys.executable, '-c', 'import time;time.sleep(3)'],
                                  timeout=kwargs['timeout'])
        started = time.monotonic()
        with mock.patch.object(ordinary, 'command', side_effect=hung), self.assertRaises(subprocess.TimeoutExpired):
            self.deploy.readiness(CID, seconds=0.2)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual(len(bounds), 1)
        self.assertLessEqual(bounds[0], 0.2)

    def test_postgres_stability_excludes_only_volatile_health_log(self):
        value, _ = self.backend_fixture()
        value['State']['Health'] = {'Log': ['first']}
        expected = self.deploy.stable_container(value)
        value['State']['Health']['Log'] = ['later']
        self.assertEqual(self.deploy.stable_container(value), expected)
        value['State']['StartedAt'] = 'changed'
        self.assertNotEqual(self.deploy.stable_container(value), expected)

    def test_fixture_yaml_preserves_real_compose_and_fixed_env_guard(self):
        (self.target / '.env').write_text('BACKEND_IMAGE=' + IMAGE + '\nAPP_ENV=staging\n')
        doc = {'name': 'ordinary-yaml-fixture', 'services': {
            'backend': {'image': '${BACKEND_IMAGE}', 'restart': 'no', 'env_file': ['./.env'],
                        'command': ['/bin/sh', '-c', 'sleep 3; exec app']},
            'postgres': {'image': 'postgres:17', 'environment': {'POSTGRES_DB': 'synthetic'},
                         'volumes': ['pgdata:/var/lib/postgresql/data']}},
               'networks': {'default': {'internal': True}}, 'volumes': {'pgdata': {}}}
        path = self.target / 'docker-compose.yml'
        outputs = []
        for raw in (json.dumps(doc), fixture_compose_yaml(doc)):
            path.write_text(raw)
            result = subprocess.run(['docker', 'compose', '--env-file', str(self.target / '.env'),
                                     '--file', str(path), 'config', '--format', 'json'],
                                    cwd=self.target, env=ordinary.clean_environment(), capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, 'real daemon-free Compose config failed')
            outputs.append(json.loads(result.stdout))
        self.assertEqual(outputs[0], outputs[1])
        guard_source = (ROOT / 'scripts/validate-staging-admission.sh').read_text()
        self.assertTrue(guard_source.endswith('main "$@"\n'))
        functions = self.target / 'actual-admission-functions.sh'
        functions.write_text(guard_source.removesuffix('main "$@"\n'))
        guard = subprocess.run(['bash', '-c', 'source "$1"; require_env_file_only_admission "$2"',
                                'actual-env-file-guard', str(functions), str(path)],
                               capture_output=True, timeout=5)
        self.assertEqual(guard.returncode, 0, guard.stderr)
        path.write_text(json.dumps(doc))
        before = subprocess.run(guard.args, capture_output=True, timeout=5)
        self.assertNotEqual(before.returncode, 0, 'before JSON fixture unexpectedly met exact textual guard')

    def test_source_bound_single_dispatch_and_actual_image_order_validator(self):
        source = (ROOT / 'scripts/deploy-staging.sh').read_text()
        self.assertEqual(source.count('"${SCRIPT_DIR}/v126-ordinary-deploy.py" client'), 1)
        self.assertNotRegex(source, r'(?m)^\s*(ssh|rsync)\s')
        self.assertNotIn('--env-file .env', source[source.index('## This comparison must stay'):])
        result = subprocess.run(['bash', str(ROOT / 'scripts/check-staging-image-identity.sh'), '--self-test',
                                 str(ROOT / 'scripts/deploy-staging.sh'), str(ROOT / 'scripts/deploy-staging-controlmaster.sh')],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        wrong = self.target / 'outside-lock.sh'
        wrong.write_text(source + '\nssh "${REMOTE}" "mkdir /somewhere"\n')
        result = subprocess.run(['bash', str(ROOT / 'scripts/check-staging-image-identity.sh'), '--self-test', str(wrong)],
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)

    def test_shared_v2_retirement_preserves_history_and_authorizes_only_next_request(self):
        bindings = ordinary.load_module(ROOT / 'scripts/v126-operation-bindings.py', 'ordinary_binding_fixture')
        registry = self.target / '.v126-target-operations'
        registry.mkdir(mode=0o700)
        (registry / 'transfers').mkdir(mode=0o700)
        ordinary.create(registry / 'lock', b'', 0o600)
        lock_inode = (registry / 'lock').stat().st_ino
        previous = dict(run_id='native-completed-fixture', release_sha='1' * 40, script_sha256='c' * 64)
        bindings.binding_create(registry / 'run.json', previous)

        def completed(identity, proof=None, outcome=0):
            op = ordinary.sha(ordinary.canonical(identity))
            log = b'synthetic remote leaf result\n'
            if proof:
                bindings.binding_create(registry / (op + '.deploy-proof.json'), proof)
                log += b'ARTIFACT\tordinary-deploy\t' + ordinary.sha(ordinary.canonical(proof)).encode() + b'\n'
            bindings.binding_create(registry / (op + '.start.json'), dict(identity=identity, operation_id=op,
                                    started_at='2026-09-10T00:00:00Z', boot_id='synthetic-boot'))
            ordinary.create(registry / (op + '.log'), log)
            bindings.binding_create(registry / (op + '.result.json'), dict(identity=identity, operation_id=op,
                                    exit=outcome, outcome='SUCCEEDED' if outcome == 0 else 'UNKNOWN', children='REAPED',
                                    log_sha256=ordinary.sha(log), completed_at='2026-09-10T00:00:01Z'))
            return op

        native = dict(previous, intent_sha256='d' * 64, kind='STAGE', name='FINAL_PUBLIC_GATES_PASSED', action='final-public-gates')
        completed(native)

        def handoff(owner, next_owner, proof_hash, compose_hash):
            return dict(format_version=1, owner=owner, next_owner=next_owner, terminal_receipt_sha256=proof_hash,
                        target_sha256=self.doc['target_sha256'], operational_version='V126', backend_image=IMAGE,
                        image_id=IMAGE_ID, environment_sha256=self.doc['environment_sha256'], compose_sha256=compose_hash,
                        caddy_runtime_sha256=self.doc['caddy_runtime_sha256'], config_owner='root:root',
                        restart_policy='unless-stopped', handoff_approved_and_applied=True,
                        approval_id='synthetic-approved', observed_at='2026-09-10T00:00:00Z')

        request_sha = ordinary.sha(ordinary.canonical(self.doc))
        first_handoff = handoff(previous, self.doc['owner'], 'e' * 64, self.doc['compose_before_sha256'])
        files, unknown, _ = bindings.binding_inventory(registry, previous)
        self.assertFalse(unknown)
        first_transfer = dict(format_version=2, previous_owner=previous, next_owner=self.doc['owner'], inventory=files,
                              handoff=first_handoff, next_kind='ORDINARY_DEPLOY', request_sha256=request_sha,
                              terminal_kind='NATIVE_RECEIPT')
        bindings.binding_create(registry / 'transfers' / (bindings.binding_owner_id(previous) + '.json'), first_transfer)
        self.assertEqual(bindings.binding_active_policy(registry, self.target),
                         {'next_kind': 'ORDINARY_DEPLOY', 'request_sha256': request_sha})
        identity = dict(self.doc['owner'], intent_sha256=request_sha, kind='DEPLOY', name='ORDINARY_DEPLOY', action='ordinary-deploy')
        proof = dict(format_version=1, identity=identity, target_sha256=self.doc['target_sha256'], request_sha256=request_sha,
                     environment_sha256=self.doc['environment_sha256'], compose_sha256=self.doc['compose_sha256'],
                     image_id=IMAGE_ID, backend_container_id=CID, database_identity_sha256=self.doc['database_identity_sha256'],
                     caddy_runtime_sha256=self.doc['caddy_runtime_sha256'], result='ORDINARY_DEPLOY_COMPLETED', completed_at='2026-09-10T00:00:01Z')
        completed(identity, proof)
        before = {str(path.relative_to(registry)): path.read_bytes() for path in registry.rglob('*') if path.is_file()}
        next_doc = copy.deepcopy(self.doc)
        next_doc['owner']['run_id'] = 'ordinary-next-fixture'
        next_doc['compose_before_sha256'] = self.doc['compose_sha256']
        next_file = self.target / 'next-approved.json'
        bindings.binding_create(next_file, next_doc)
        proof_sha = ordinary.sha(ordinary.canonical(proof))
        applied = self.target / 'next-applied-handoff.json'
        bindings.binding_create(applied, handoff(self.doc['owner'], next_doc['owner'], proof_sha, self.doc['compose_sha256']))
        # Real shared retirement consumer reads synthetic canonical records; no
        # runtime completion is manufactured or claimed by this parser fixture.
        bindings.binding_retire_deploy(self.target, self.doc['owner'], proof_sha, applied, next_file)
        current, owners = bindings.binding_chain(registry, self.target)
        self.assertEqual(current, next_doc['owner'])
        self.assertEqual(len(owners), 3)
        self.assertEqual((registry / 'lock').stat().st_ino, lock_inode)
        self.assertEqual(before, {name: (registry / name).read_bytes() for name in before})
        policy = bindings.binding_active_policy(registry, self.target)
        self.assertEqual(policy['request_sha256'], ordinary.sha(ordinary.canonical(next_doc)))
        self.assertNotEqual(policy['request_sha256'], request_sha)
        with self.assertRaises(bindings.BindingError):
            bindings.binding_retire_deploy(self.target, self.doc['owner'], proof_sha, applied, next_file)
        # Historical corruption still blocks the new owner even though it has
        # not dispatched anything yet.
        old_result = next(registry.glob('*.result.json'))
        old_result.chmod(0o600)
        old_result.write_bytes(b'{"tampered":true}\n')
        old_result.chmod(0o400)
        with self.assertRaises(bindings.BindingError): bindings.binding_chain(registry, self.target)


def linux_integration(evidence):
    """Real root/JVM/PG/Caddy, terminal reconciliation and ordinary deployment."""
    ordinary.require(sys.platform == 'linux' and os.geteuid() == 0 and os.environ.get('GITHUB_ACTIONS') == 'true' and
                     os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted' and os.environ.get('RUNNER_OS') == 'Linux',
                     'requires root-owned disposable GitHub Linux endpoint guard')
    runtime = ordinary.load_module(ROOT / 'scripts/test-v126-linux-runtime.py', 'ordinary_owned_runtime')
    systemd = ordinary.load_module(ROOT / 'scripts/test-v126-systemd-linux.py', 'ordinary_owned_systemd')
    bindings = ordinary.load_module(ROOT / 'scripts/v126-operation-bindings.py', 'ordinary_real_bindings')
    evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    events, first_failure = [], None
    accepted_pre_mutation_unknown = set()
    active, writer = None, None
    fixture = caddy = None
    saved_path = os.environ['PATH']
    saved_signals = {sig: signal.getsignal(sig) for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP)}
    def interrupted(signum, frame):
        raise ordinary.Refused('owned integration interrupted; cleanup must prove quiescence')
    for sig in saved_signals:
        signal.signal(sig, interrupted)
    try:
        caddy = systemd.Fixture(evidence / 'caddy')
        caddy.prepare(run_lifetime_regressions=False)
        caddy.probe('restore')
        # Existing systemd fixture translates the actual Caddy/site/unit only.
        # A composite curl adapter also admits our real owned backend loopback.
        adapter_dir = caddy.root / 'ordinary-bin'
        adapter_dir.mkdir(mode=0o700)
        launcher = adapter_dir / 'curl'
        launcher.write_text('#!' + sys.executable + '\nimport os,sys\n'
                            'args=sys.argv[1:]\n'
                            'if args and args[-1] in ' + repr(tuple('http://127.0.0.1:8080' + path for path in ('/health', '/db/health', '/version', '/miniapp/'))) + ':\n'
                            ' os.execv(' + repr(caddy.spec['curl']) + ',[' + repr(caddy.spec['curl']) + ',*args])\n'
                            'os.execv(' + repr(str(caddy.root / 'bin/curl')) + ',[' + repr(str(caddy.root / 'bin/curl')) + ',*args])\n')
        launcher.chmod(0o700)
        os.environ['PATH'] = str(adapter_dir) + os.pathsep + caddy.env['PATH']

        class ActualRuntime(runtime.Integration):
            def write_inputs(self):
                self.compose_doc['services']['backend']['env_file'] = ['./.env']
                super().write_inputs()
                (self.root / 'docker-compose.yml').write_text(fixture_compose_yaml(self.compose_doc))
                (self.root / 'docker-compose.yml').chmod(0o644)

            def run(self, argv, **kwargs):
                argv = list(argv)
                if argv[:2] == ['docker', 'build']:
                    argv[2:2] = ['--label', 'org.opencontainers.image.source=' + ordinary.SOURCE,
                                  '--label', 'org.opencontainers.image.revision=' + self.release]
                return super().run(argv, **kwargs)

        fixture = ActualRuntime(evidence / 'runtime')
        fixture.temp = DeferredFixtureDirectory(fixture.temp)
        fixture.image = 'ht-v126-ordinary-fixture-' + fixture.token + ':' + fixture.release
        fixture.values['BACKEND_IMAGE'] = fixture.image
        fixture.write_inputs()
        fixture.prepare()
        target = fixture.root.resolve()
        (target / 'docker-compose.yml').chmod(0o644)
        fixture.compose('create', '--no-build', '--pull', 'never', 'backend')
        initial_backend = fixture.cid('backend')
        fixture.start_once(initial_backend)
        observed, _, _ = fixture.observe(initial_backend)
        ordinary.require(observed.returncode == 0, 'actual migration JVM did not become ready')
        fixture.check_starts(initial_backend)
        ordinary.require(fixture.sql("SELECT max(version::int)||':'||count(*) FILTER (WHERE version='126') FROM flyway_schema_history;") == b'126:1\n',
                         'actual JVM did not establish V126')
        events.append({'checkpoint': 'ACTUAL_MIGRATION_READY', 'restart': 'no', 'image_id': fixture.image_id})
        guard = ordinary.load_module(ROOT / 'scripts/check-staging-operational-handoff.py', 'ordinary_linux_guard')
        try:
            guard.check_authority(target, fixture.image)
        except ValueError:
            events.append({'checkpoint': 'PRE_HANDOFF_ORDINARY_GUARD_REFUSED', 'actual_compose_restart': 'no'})
        else:
            raise ordinary.Refused('migration restart=no accepted as operational handoff')
        # This approval is a fixture authorization, explicitly distinct from a
        # real stage20/manual receipt or a historical run. Apply only our target.
        approval = target / 'synthetic-handoff-approval.json'
        ordinary.create(approval, ordinary.canonical({'kind': 'SYNTHETIC_OWNED_HANDOFF_APPROVAL',
                        'target_sha256': ordinary.sha(str(target).encode()), 'image_id': fixture.image_id,
                        'restart_before': 'no', 'restart_after': 'unless-stopped', 'approved': True}))
        ordinary.protected(approval, 0o400)
        ordinary.require(ordinary.structured(approval.read_bytes())['approved'] is True, 'fixture approval required before application')
        fixture.values.update(STAGING_MAINTENANCE_MODE='OFF', STAGING_MAINTENANCE_ALLOWED_USER_IDS='',
                              STAGING_MAINTENANCE_ALLOWED_CHAT_IDS='')
        # Establish OFF with the migration restart policy still disabled. The
        # actual terminal/lost-ACK reconciliation must precede handoff policy.
        fixture.write_inputs()
        (target / 'docker-compose.yml').chmod(0o644)
        fixture.compose('up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--force-recreate', 'backend')
        def resolve_backend():
            fixture.backend_relay_cid = fixture.cid('backend')
            return fixture.owned_endpoint(fixture.backend_relay_cid, 8080)
        fixture.relays[-1].resolve_target = resolve_backend
        applied_backend = fixture.cid('backend')
        fixture.backend_relay_cid = applied_backend
        ready = fixture.shell('remote_wait_backend_ready "$@"',
                              [applied_backend, fixture.image_id, fixture.release, 'final', fixture.env_sha], timeout=135)
        ordinary.require(ready.returncode == 0 and fixture.inspect(applied_backend)['HostConfig']['RestartPolicy']['Name'] == 'no',
                         'OFF operational gates require migration restart=no')
        reconciliation = ordinary.load_module(ROOT / 'scripts/test-v126-reconciliation-linux.py', 'ordinary_connected_reconciliation')
        try:
            terminal = reconciliation.run_connected(fixture, caddy, evidence / 'reconciliation')
        except ValueError as error:
            raise ordinary.Refused(str(error)) from None
        events.extend(terminal['events'])
        # Apply the protected own-fixture policy only after terminal evidence;
        # subsequent ordinary deployment leaves these accepted .env bytes fixed.
        fixture.compose_doc['services']['backend']['restart'] = 'unless-stopped'
        fixture.write_inputs()
        fixture.run(['docker', 'update', '--restart=unless-stopped', applied_backend])
        fixture.compose('up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--force-recreate', 'backend')
        applied_backend = fixture.cid('backend')
        fixture.backend_relay_cid = applied_backend
        # Fill the descriptor from actual accepted bytes and read-only consumers.
        uri_file = target / 'database-uri'
        ordinary.create(uri_file, (fixture.uri + '\n').encode(), 0o600)
        payload = {name: (ROOT / name).read_bytes() for name in ordinary.UPLOADS}
        payload['docker-compose.yml'] = (target / 'docker-compose.yml').read_bytes()
        for name, raw in payload.items():
            if name != 'docker-compose.yml': ordinary.install_file(target, name, raw)
        config_path = caddy.caddy_root / 'Caddyfile'
        active_config = fixture.run([caddy.spec['curl'], '--disable', '--noproxy', '*', '--proto', '=http',
                                    '--connect-timeout', '3', '--max-time', '10', '-fsS',
                                    'http://127.0.0.1:' + str(caddy.admin_port) + '/config/']).stdout
        identity = fixture.run(['python3', ROOT / 'scripts/v126-database-evidence.py', 'live-target', uri_file,
                                fixture.image, ordinary.sha(uri_file.read_bytes())], timeout=90).stdout
        identity_sha = identity.decode().strip().removeprefix('DATABASE_TARGET_EQUALITY=')
        ordinary.require(ordinary.digest(identity_sha), 'actual database identity digest missing')
        bundle = ordinary.source_bundle()
        doc = dict(format_version=1, owner=dict(run_id='ordinary-owned-' + fixture.token[:16], release_sha=fixture.release,
                    script_sha256=ordinary.sha(bundle)), target_sha256=ordinary.sha(str(target).encode()), operational_version='V126',
                   backend_image=fixture.image, image_id=fixture.image_id, image_source=ordinary.SOURCE, platform='linux/amd64',
                   environment_sha256=ordinary.sha((target / '.env').read_bytes()), compose_before_sha256=ordinary.sha(payload['docker-compose.yml']),
                   compose_sha256=ordinary.sha(payload['docker-compose.yml']), caddy_config_sha256=ordinary.sha(config_path.read_bytes()),
                   caddy_runtime_sha256=ordinary.sha(ordinary.canonical(json.loads(active_config))), database_url_file=str(uri_file),
                   database_url_sha256=ordinary.sha(uri_file.read_bytes()), database_identity_sha256=identity_sha,
                   config_owner='root:root', restart_policy='unless-stopped', handoff_approved_and_applied=True,
                   approval_id='synthetic-approved-' + fixture.token, observed_at='2026-09-10T00:00:00Z',
                   files={name: ordinary.sha(raw) for name, raw in payload.items()},
                   public_url='https://staging.hookahtootah.club', public_checks=False)
        ordinary.validate_descriptor(doc, target)
        observer = ordinary.Deployment(target, doc, ROOT)
        observer.readiness(applied_backend)
        observer.fixed(doc['compose_sha256'])
        observer.image()
        observer.database()
        events.append({'checkpoint': 'APPROVED_HANDOFF_APPLIED', 'approval_sha256': ordinary.sha(approval.read_bytes()),
                       'restart': fixture.inspect(applied_backend)['HostConfig']['RestartPolicy']['Name'],
                       'environment_sha256': doc['environment_sha256'], 'image_id': fixture.image_id})
        # Keep the actual terminal reconciliation registry. Retirement appends
        # a v2 transfer; it never manufactures or replaces the predecessor.
        registry = target / '.v126-target-operations'
        ordinary.require(str(registry) == terminal['registry'] and terminal['terminal_kind'] == 'RECONCILED_EFFECT',
                         'connected terminal registry differs')
        inode = (registry / 'lock').stat().st_ino
        native_owner = terminal['previous_owner']
        def handoff(owner, next_owner, terminal_hash):
            return dict(format_version=1, owner=owner, next_owner=next_owner, terminal_receipt_sha256=terminal_hash,
                        target_sha256=doc['target_sha256'], operational_version='V126', backend_image=doc['backend_image'], image_id=doc['image_id'],
                        environment_sha256=doc['environment_sha256'], compose_sha256=doc['compose_sha256'], caddy_runtime_sha256=doc['caddy_runtime_sha256'],
                        config_owner='root:root', restart_policy='unless-stopped', handoff_approved_and_applied=True,
                        approval_id=doc['approval_id'], observed_at=doc['observed_at'])
        first_request = target / 'first-approved-ordinary-request.json'
        first_handoff = target / 'first-applied-ordinary-handoff.json'
        bindings.binding_create(first_request, doc)
        bindings.binding_create(first_handoff, handoff(native_owner, doc['owner'], terminal['terminal_receipt_sha256']))
        retire_code = ('import importlib.util,sys;'
                       'spec=importlib.util.spec_from_file_location("actual_bindings",sys.argv.pop(1));'
                       'module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);'
                       'module.binding_entry("retire")')
        fixture.run([sys.executable, '-c', retire_code, ROOT / 'scripts/v126-operation-bindings.py', target,
                     *[native_owner[key] for key in ('run_id', 'release_sha', 'script_sha256')],
                     terminal['terminal_receipt_sha256'], first_handoff,
                     *[doc['owner'][key] for key in ('run_id', 'release_sha', 'script_sha256')],
                     'V126', doc['image_id'], 'ORDINARY_DEPLOY', first_request, 'RECONCILED_EFFECT'])
        ordinary.require(bindings.binding_chain(registry, target)[0] == doc['owner'], 'actual terminal retirement did not authorize exact ordinary owner')
        preserved = {str(path.relative_to(registry)): path.read_bytes() for path in registry.rglob('*') if path.is_file()}
        with tempfile.TemporaryFile(dir=target) as archive:
            save = subprocess.run(['docker', 'save', fixture.image], stdout=archive, stderr=subprocess.PIPE, timeout=180)
            ordinary.require(save.returncode == 0, 'owned exact image archive save failed')
            archive_size = archive.tell()
            # Exact direct Path alias, solely for the owned Caddy file. All real
            # mode/owner/hash/config/runtime consumers remain in the worker.
            _, bootstrap = owned_bootstrap(config_path)
            for number in (1, 2):
                request = ordinary.canonical(doc)
                identity = dict(doc['owner'], intent_sha256=ordinary.sha(request), kind='DEPLOY', name='ORDINARY_DEPLOY', action='ordinary-deploy')
                operation = ordinary.sha(ordinary.canonical(identity))
                capture = target / ('ordinary-' + str(number) + '.capture')
                gate = threading.Event()
                writer_errors = []
                with capture.open('xb') as output:
                    output.flush()
                    active = subprocess.Popen([sys.executable, '-c', bootstrap, str(target)], stdin=subprocess.PIPE,
                                              stdout=output, stderr=subprocess.STDOUT, start_new_session=True,
                                              env=ordinary.clean_environment())
                    def upload():
                        try:
                            ordinary.frame(active.stdin, bundle)
                            ordinary.frame(active.stdin, request)
                            active.stdin.flush()
                            ordinary.require(gate.wait(15), 'owned upload release deadline')
                            for name in sorted(payload): ordinary.frame(active.stdin, payload[name])
                            active.stdin.write(ordinary.struct.pack('!Q', archive_size))
                            archive.seek(0)
                            while True:
                                part = archive.read(1024 * 1024)
                                if not part: break
                                active.stdin.write(part)
                            active.stdin.close()
                        except BaseException as error:
                            writer_errors.append(type(error).__name__)
                    writer = threading.Thread(target=upload)
                    writer.start()
                    deadline = time.monotonic() + 10
                    while not (registry / (operation + '.start.json')).exists():
                        ordinary.require(active.poll() is None and time.monotonic() < deadline, 'real supervisor did not start expected ordinary operation')
                        time.sleep(0.05)
                    with (registry / 'lock').open('rb') as lock:
                        try:
                            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        except BlockingIOError:
                            pass
                        else:
                            raise ordinary.Refused('ordinary upload did not retain persistent target lock')
                    gate.set()
                    status = active.wait(timeout=930)
                    writer.join(timeout=5)
                    ordinary.require(not writer.is_alive() and not writer_errors, 'owned streaming writer did not finish')
                    active = writer = None
                capture.chmod(0o400)
                ordinary.require(status == 0, 'real ordinary worker failed; private capture retained')
                ack = ordinary.shell_consumer(ROOT / 'scripts/v126-cutover.sh',
                    'capture="$1"; RUN_ID="$2"; RELEASE_SHA="$3"; SCRIPT_SHA256="$4"; ACTIVE_INTENT_HASH="$5"; '
                    'ACTIVE_OPERATION_KIND=DEPLOY; ACTIVE_OPERATION_NAME=ORDINARY_DEPLOY; verify_remote_operation_ack "$capture" ordinary-deploy',
                    [capture, doc['owner']['run_id'], doc['owner']['release_sha'], doc['owner']['script_sha256'], ordinary.sha(request)])
                proof_path = registry / (operation + '.deploy-proof.json')
                proof = bindings.binding_deploy_proof(proof_path, identity, target)
                ordinary.require(b'ARTIFACT\tordinary-deploy\t' + ordinary.sha(proof_path.read_bytes()).encode() in ack, 'actual ACK/proof join failed')
                ordinary.require((registry / 'lock').stat().st_ino == inode and
                                 all((registry / name).read_bytes() == raw for name, raw in preserved.items()), 'history or lock inode changed')
                events.append({'checkpoint': 'ACTUAL_ORDINARY_DEPLOY_COMPLETED', 'number': number, 'operation_id': operation,
                               'backend_container_id': proof['backend_container_id'], 'proof_sha256': ordinary.sha(proof_path.read_bytes()),
                               'image_id': proof['image_id'], 'request_sha256': proof['request_sha256'], 'lock_retained_during_upload': True})
                if number == 1:
                    previous_owner = doc['owner']
                    doc = copy.deepcopy(doc)
                    doc['owner']['run_id'] += '-next'
                    next_request = target / 'next-approved-request.json'
                    next_handoff = target / 'next-applied-handoff.json'
                    bindings.binding_create(next_request, doc)
                    bindings.binding_create(next_handoff, handoff(previous_owner, doc['owner'], ordinary.sha(proof_path.read_bytes())))
                    bindings.binding_retire_deploy(target, previous_owner, ordinary.sha(proof_path.read_bytes()), next_handoff, next_request)
        # Current completed binding cannot deploy again without another retirement.
        try:
            bindings.binding_retire_deploy(target, previous_owner, ordinary.sha(proof_path.read_bytes()), next_handoff, next_request)
        except bindings.BindingError:
            events.append({'checkpoint': 'STALE_OWNER_RETIREMENT_REFUSED'})
        else:
            raise ordinary.Refused('stale owner retirement accepted')
        observer = ordinary.Deployment(target, doc, ROOT)
        completed_backend = observer.ids('backend')
        before_restart = observer.inspected(completed_backend)['State']['StartedAt']
        fixture.run(['docker', 'restart', '--time', '1', completed_backend], timeout=30)
        observer.readiness(completed_backend)
        ordinary.require(observer.inspected(completed_backend)['State']['StartedAt'] != before_restart,
                         'owned Docker restart did not establish a new process lifetime')
        observer.fixed(doc['compose_sha256'])
        observer.database()
        events.append({'checkpoint': 'ACTUAL_BACKEND_RESTART_SURVIVED', 'backend_container_id': completed_backend,
                       'restart': 'unless-stopped', 'host_reboot_claim': False})

        def header_attempt(request_doc, label):
            nonlocal active
            header = io.BytesIO()
            ordinary.frame(header, bundle)
            ordinary.frame(header, ordinary.canonical(request_doc))
            before_backend = observer.stable_container(observer.inspected(observer.ids('backend')))
            active = subprocess.Popen([sys.executable, '-c', bootstrap, str(target)],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      start_new_session=True, env=ordinary.clean_environment())
            out, err = active.communicate(header.getvalue(), timeout=30)
            attempt = subprocess.CompletedProcess(active.args, active.returncode, out, err)
            active = None
            ordinary.require(attempt.returncode != 0, label + ' unexpectedly accepted')
            ordinary.require(before_backend == observer.stable_container(observer.inspected(observer.ids('backend'))),
                             label + ' changed actual backend')
            events.append({'checkpoint': label, 'exit': attempt.returncode, 'backend_unchanged': True})
            return attempt

        completed_records = {str(path.relative_to(registry)): path.read_bytes()
                             for path in registry.rglob('*') if path.is_file()}
        header_attempt(doc, 'COMPLETED_REQUEST_REPLAY_REFUSED')
        ordinary.require(completed_records == {str(path.relative_to(registry)): path.read_bytes()
                                              for path in registry.rglob('*') if path.is_file()},
                         'refused replay changed immutable operation history')
        # A correctly retired third owner reaches the actual worker. Bad root
        # metadata yields a durable UNKNOWN; neither replay nor retirement may
        # convert this result into a successful operation.
        second_owner = doc['owner']
        completed_proof_hash = ordinary.sha(proof_path.read_bytes())
        doc = copy.deepcopy(doc)
        doc['owner']['run_id'] += '-unknown'
        unknown_request = target / 'unknown-approved-request.json'
        unknown_handoff = target / 'unknown-applied-handoff.json'
        bindings.binding_create(unknown_request, doc)
        bindings.binding_create(unknown_handoff, handoff(second_owner, doc['owner'], completed_proof_hash))
        bindings.binding_retire_deploy(target, second_owner, completed_proof_hash, unknown_handoff, unknown_request)
        unknown_identity = dict(doc['owner'], intent_sha256=ordinary.sha(ordinary.canonical(doc)),
                                kind='DEPLOY', name='ORDINARY_DEPLOY', action='ordinary-deploy')
        unknown_operation = ordinary.sha(ordinary.canonical(unknown_identity))
        (target / '.env').chmod(0o644)
        try:
            header_attempt(doc, 'ACTUAL_GUARD_FAILURE_RECORDED_UNKNOWN')
        finally:
            (target / '.env').chmod(0o600)
        outcome = bindings.binding_read(registry / (unknown_operation + '.result.json'))
        ordinary.require(outcome['outcome'] == 'UNKNOWN' and outcome['exit'] != 0 and outcome['children'] == 'REAPED' and
                         not (registry / (unknown_operation + '.deploy-proof.json')).exists(),
                         'failed real metadata guard produced an ordinary completion proof')
        accepted_pre_mutation_unknown.add(unknown_operation)
        unknown_records = {str(path.relative_to(registry)): path.read_bytes()
                           for path in registry.rglob('*') if path.is_file()}
        header_attempt(doc, 'UNKNOWN_REQUEST_REPLAY_REFUSED')
        try:
            bindings.binding_retire_deploy(target, doc['owner'], completed_proof_hash, unknown_handoff, unknown_request)
        except bindings.BindingError:
            events.append({'checkpoint': 'UNKNOWN_RETIREMENT_REFUSED'})
        else:
            raise ordinary.Refused('UNKNOWN ordinary operation was retired')
        ordinary.require(unknown_records == {str(path.relative_to(registry)): path.read_bytes()
                                            for path in registry.rglob('*') if path.is_file()},
                         'UNKNOWN refusal changed operation history')
        observer = ordinary.Deployment(target, doc, ROOT)
        for fault in ('stale-image', 'wrong-config', 'wrong-owner'):
            if fault == 'stale-image':
                old = observer.doc['image_id']; observer.doc['image_id'] = 'sha256:' + '0' * 64
                action = observer.image
            elif fault == 'wrong-config':
                old = (target / 'docker-compose.yml').read_bytes()
                (target / 'docker-compose.yml').write_bytes(old + b'\n')
                action = lambda: observer.fixed(doc['compose_sha256'])
            else:
                os.chown(target / '.env', 65534, 65534)
                action = lambda: observer.fixed(doc['compose_sha256'])
            try:
                action()
            except (ordinary.Refused, ValueError):
                events.append({'checkpoint': 'ACTUAL_AUTHORITY_REFUSAL', 'case': fault})
            else:
                raise ordinary.Refused('wrong operational authority was accepted')
            finally:
                if fault == 'stale-image': observer.doc['image_id'] = old
                elif fault == 'wrong-config': (target / 'docker-compose.yml').write_bytes(old)
                else: os.chown(target / '.env', 0, 0)
        provider_stats = fixture.stats()
        events.append({'checkpoint': 'SYNTHETIC_PROVIDER_COUNTERS', **{key: provider_stats[key] for key in
                       ('getUpdates', 'getWebhookInfo', 'setChatMenuButton', 'unexpected', 'outbound')}})
        ordinary.require(provider_stats['unexpected'] == 0 and provider_stats['outbound'] == 0,
                         'synthetic provider saw unexpected request')
        ordinary.require(provider_stats['setChatMenuButton'] > 0, 'actual OFF startup command-menu request was not observed')
        events.append({'checkpoint': 'SYNTHETIC_PROVIDER_ONLY', 'unexpected': 0})
    except BaseException as error:
        first_failure = {'type': type(error).__name__, 'reason': str(error) if isinstance(error, (ordinary.Refused, runtime.FixtureAssertionError)) else 'owned integration failed'}
    finally:
        quiescent = not getattr(fixture, 'reconciliation_cleanup_unproven', False)
        if active is not None and active.poll() is None:
            active.terminate()
            try:
                active.wait(timeout=20)
            except subprocess.TimeoutExpired:
                quiescent = False
        if writer is not None:
            writer.join(timeout=5)
            quiescent = quiescent and not writer.is_alive()
        if quiescent and fixture is not None:
            history = fixture.root / '.v126-target-operations'
            for start in history.glob('*.start.json'):
                operation_id = start.name.removesuffix('.start.json')
                try:
                    result = bindings.binding_read(history / (operation_id + '.result.json'))
                    if result['children'] != 'REAPED' or (result['exit'] != 0 and operation_id not in accepted_pre_mutation_unknown):
                        quiescent = False
                except (OSError, ValueError, KeyError, bindings.BindingError):
                    quiescent = False
            # The only admitted nonzero result comes from the exact root .env
            # metadata guard before its first command. All other UNKNOWN daemon
            # outcomes retain resources, even after the local process exits.
        if quiescent and fixture is not None:
            try:
                for relay in fixture.relays:
                    relay.close()
                fixture.relays = []
            except BaseException as error:
                quiescent = False
                first_failure = first_failure or {'type': type(error).__name__, 'reason': 'owned relay cleanup failed'}
        if quiescent:
            try:
                child_pid, _ = os.waitpid(-1, os.WNOHANG)
                # Caddy setup made this coordinator a subreaper. Every expected
                # child was explicitly waited; any remaining child is unknown.
                quiescent = False
            except ChildProcessError:
                pass
        if quiescent and fixture is not None:
            history = fixture.root / '.v126-target-operations'
            if history.is_dir():
                exported = evidence / 'operation-history'
                exported.mkdir(mode=0o700)
                inventory = {}
                for record in sorted(history.rglob('*')):
                    if record.is_file() and not record.is_symlink():
                        relative = str(record.relative_to(history))
                        inventory[relative] = ordinary.sha(record.read_bytes())
                        if relative.endswith('.json'):
                            destination = exported / relative
                            destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                            ordinary.create(destination, record.read_bytes())
                ordinary.create(exported / 'inventory.json', ordinary.canonical(inventory))
        if quiescent:
            try:
                if fixture is not None:
                    fixture.cleanup()
                    fixture.temp.remove_after_quiescence()
            except BaseException as error:
                first_failure = first_failure or {'type': type(error).__name__, 'reason': 'owned runtime cleanup failed'}
                quiescent = False
        if quiescent:
            try:
                if caddy is not None: caddy.cleanup()
            except BaseException as error:
                first_failure = first_failure or {'type': type(error).__name__, 'reason': 'owned Caddy cleanup failed'}
                quiescent = False
        else:
            if fixture is not None:
                fixture.temp._finalizer.detach()
            first_failure = first_failure or {'type': 'UnknownChildren', 'reason': 'own child or daemon completion unproved; fixture retained'}
        if not quiescent and fixture is not None:
            fixture.temp._finalizer.detach()
        os.environ['PATH'] = saved_path
        for sig, handler in saved_signals.items(): signal.signal(sig, handler)
        result = dict(status='FAILED' if first_failure else 'PASSED', first_failure=first_failure, events=events,
                      source_sha256=ordinary.sha((ROOT / 'scripts/v126-ordinary-deploy.py').read_bytes()),
                      boundary='Real root/Docker/JVM/PG/systemd/Caddy terminal+collector+worker; earlier prerequisites/approval synthetic; direct owned stream, no SSH/live/manual/reboot claim',
                      public_checks=False, cleanup_quiescent=quiescent)
        ordinary.create(evidence / 'result.json', ordinary.canonical(result))
        print(json.dumps(result, sort_keys=True))
    ordinary.require(first_failure is None, 'real ordinary integration failed; first result retained')


if __name__ == '__main__':
    if '--require-linux-integration' in sys.argv:
        import argparse
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('--require-linux-integration', action='store_true', required=True)
        parser.add_argument('--evidence', type=Path, required=True)
        args = parser.parse_args()
        linux_integration(args.evidence)
    else:
        unittest.main(verbosity=2)
