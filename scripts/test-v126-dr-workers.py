#!/usr/bin/env python3
"""Offline one-shot worker security tests. All fixture data/credentials are synthetic.

Production has no fixture switch, arbitrary script argument or injectable source
loader. Tests copy sources to a temporary directory and instrument that copy only.
"""
import copy
from datetime import datetime, timedelta, timezone
import errno
import hashlib
import importlib.util
import io
import json
import logging
import os
from pathlib import Path
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent


def module(path, name='worker_test_parent'):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


s3 = module(ROOT / 'scripts/v126-dr-s3.py')
ipc, dr = s3.ipc, s3.dr
H = lambda s: hashlib.sha256(s.encode()).hexdigest()
TARGET = dr.canonical(dict(schema_version=1, kind='target', protocol='s3-compatible',
    endpoint='https://objects.example.invalid', bucket='synthetic-ap00', region='ru-central1',
    storage_class='STANDARD', account_ref=H('synthetic-account'), failure_domain_ref=H('synthetic-domain'),
    versioning='ENABLED', object_lock='ENABLED', lock_mode='COMPLIANCE', retention_seconds=604800))
TARGET_HASH = dr.sha(TARGET)
KEY = 'bundles/' + H('synthetic-manifest') + '.enc'
CUSTODY = H('synthetic-custody-reference')
UNTIL = (datetime.now(timezone.utc) + timedelta(days=8)).replace(microsecond=0)
CANARIES = ('HT31_SYNTHETIC_CREDENTIAL_5fb2', 'HT31_RAW_HEADER_66c1',
            'HT31_SYNTHETIC_PLAINTEXT_ffa1', 'https://sensitive.example.invalid/HT31_URL_c8b7')
CREDENTIALS = dict(access_key='SYNTHETIC_ACCESS', secret_key=CANARIES[0], session_token=None)
header = dr.canonical(dict(target_sha256=TARGET_HASH, manifest_sha256=H('synthetic-manifest'),
    object_key=KEY, custody_version_sha256=CUSTODY, format_version=1, algorithm='AES-256-GCM',
    ciphertext_size=17, nonce_hex='00'*12))
# Syntactic encrypted envelope, no key material. Authenticated crypto vectors live
# in test-v126-dr-adapters.py; the transport never authenticates/decrypts this body.
BODY = b'HTDR' + len(header).to_bytes(4, 'big') + header + b'\xff\x00'*8 + b'\x80'


def request(op='write'):
    args = {'write': dict(key=KEY, custody_version_sha256=CUSTODY, retain_until=ipc.date(UNTIL)),
            'read': dict(key=KEY, version_id='version-001'),
            'observe_version': dict(key=KEY, version_id='version-001', required_until=ipc.date(UNTIL)),
            'observe_bucket': {}}[op]
    return dict(version=1, kind='request', operation=op, target=json.loads(TARGET),
                target_sha256=TARGET_HASH, credentials=CREDENTIALS, arguments=args)


def response(op='write', **changes):
    value = {'write': {'version_id': 'version-001'},
             'read': {'version_id': 'version-001', 'size': len(BODY)},
             'observe_version': dict(version_id='version-001', size=len(BODY), mode='COMPLIANCE', retained_until=ipc.date(UNTIL)),
             'observe_bucket': dict(versioning='ENABLED', object_lock='ENABLED', mode='COMPLIANCE', default_retention_days=7)}[op]
    return dict(version=1, kind='response', operation=op, result='SUCCESS', code='OK', value=value,
                runtime=dr.platform.python_version()) | changes


# Injected only into temporary source copies. Socket audit hook is installed before
# site/SDK acquisition. Even a failed interception cannot contact a provider.
FIXTURE = r'''
import json as _json
import socket as _socket

def _audit(event, args):
    if event in ('socket.connect', 'socket.getaddrinfo', 'socket.sendto'):
        raise RuntimeError('NETWORK_FORBIDDEN')
sys.addaudithook(_audit)

def _event(event, **values):
    with open(AUDIT, 'a') as stream:
        stream.write(_json.dumps(dict(event=event, pid=os.getpid(), **values)) + '\n')
def _fd_open(fd):
    try: os.fstat(fd); return True
    except OSError: return False
_event('START', inherited_fd_open=_fd_open(199), sdk_present=any(n.split('.')[0] in ('boto3','botocore','urllib3') for n in sys.modules),
       site_present='site' in sys.modules,
       environment=sorted(os.environ))
_real_sdk = sdk
_installed = False
_request_count = 0

def sdk():
    global _installed
    _event('SDK')
    if MODE == 'dependency':
        raise ImportError('HT31_SYNTHETIC_CREDENTIAL_5fb2')
    value = _real_sdk()
    if not _installed:
        _installed = True
        import io
        from botocore.awsrequest import AWSResponse, AWSHTTPSConnection
        from botocore.httpsession import URLLib3Session
        from botocore.credentials import CredentialResolver
        def no_credentials(*a, **k):
            _event('AMBIENT_CREDENTIAL_ATTEMPT')
            raise RuntimeError('AMBIENT_FORBIDDEN')
        CredentialResolver.load_credentials = no_credentials
        class Raw(io.BytesIO):
            def stream(self, amt=None, decode_content=False):
                yield self.read()
        def send(session, req):
            global _request_count
            _request_count += 1
            _event('REQUEST', method=req.method, exact_query=('versionId=version-001' in req.url),
                   conditional=(req.headers.get('If-None-Match') == b'*'))
            if MODE in ('timeout', 'crash', 'malformed_after_dispatch'):
                if MODE == 'timeout':
                    import signal
                    signal.signal(signal.SIGTERM, signal.SIG_IGN)
                    time.sleep(20)
                if MODE == 'crash':
                    os.write(2, b'HT31_SYNTHETIC_CREDENTIAL_5fb2')
                    os._exit(9)
                _protocol_output.write(b'HT31_SYNTHETIC_CREDENTIAL_5fb2')
                os._exit(0)
            if MODE == 'retry_read' and _request_count < 3:
                return AWSResponse(req.url, 503, {}, Raw(b'<Error/>'))
            headers = {'x-amz-version-id': 'version-001'}
            raw = b''
            if STATUS >= 300:
                raw = b'<Error><Code>Denied</Code><Message>HT31_SYNTHETIC_CREDENTIAL_5fb2</Message></Error>'
            if req.method == 'HEAD':
                headers['content-length'] = str(len(BINARY_BODY))
            elif 'retention&' in req.url:
                raw = ('<Retention><Mode>COMPLIANCE</Mode><RetainUntilDate>' + RETAIN_UNTIL + '</RetainUntilDate></Retention>').encode()
            elif '?versioning' in req.url:
                raw = b'<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>'
            elif '?object-lock' in req.url:
                raw = b'<ObjectLockConfiguration><ObjectLockEnabled>Enabled</ObjectLockEnabled><Rule><DefaultRetention><Mode>COMPLIANCE</Mode><Days>7</Days></DefaultRetention></Rule></ObjectLockConfiguration>'
            elif req.method == 'GET':
                raw = BINARY_BODY
                headers['content-length'] = str(len(raw))
            os.write(1, b'HT31_RAW_HEADER_66c1')
            os.write(2, b'HT31_SYNTHETIC_CREDENTIAL_5fb2')
            return AWSResponse(req.url, STATUS, headers, Raw(raw))
        if MODE == 'malformed_header':
            import urllib3.connection
            import urllib3.util
            urllib3.util.wait_for_read = lambda *a, **k: False
            warning = urllib3.connection.log.warning
            def saw_warning(*a, **k):
                _event('MALFORMED_HEADER_WARNING')
                return warning(*a, **k)
            urllib3.connection.log.warning = saw_warning
            class Socket:
                def sendall(self, raw):
                    if bytes(raw).startswith(b'PUT '):
                        _event('REQUEST', method='PUT', exact_query=False, conditional=True)
                def makefile(self, *a, **k):
                    return io.BytesIO(b'HTTP/1.1 200 OK\r\nx-amz-version-id: version-001\r\nContent-Length: 0\r\nConnection: close\r\nBROKEN '
                        + ' '.join(MARKERS).encode() + b'\r\n\r\n')
                def settimeout(self, *a): pass
                def close(self): pass
            def connect(connection):
                connection.sock = Socket()
                connection.is_verified = True
                os.write(2, MARKERS[0].encode())
            AWSHTTPSConnection.connect = connect
        else:
            URLLib3Session.send = send
    return value
if MODE in ('non_cpython', 'gil_disabled', 'free_threaded', 'missing_gil_api'):
    import types, sysconfig
    if MODE == 'non_cpython':
        sys.implementation = types.SimpleNamespace(**(vars(sys.implementation) | {'name': 'pypy'}))
    elif MODE == 'gil_disabled':
        sys._is_gil_enabled = lambda: False
    elif MODE == 'missing_gil_api':
        del sys._is_gil_enabled
    else:
        _config = sysconfig.get_config_var
        sysconfig.get_config_var = lambda k: 1 if k == 'Py_GIL_DISABLED' else _config(k)
'''


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ht31-worker-test-')
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name).resolve()
        self.audit = self.home / 'audit.jsonl'
        self.children = []
        self.real_popen = subprocess.Popen
        def spawn(*args, **kwargs):
            child = self.real_popen(*args, **kwargs)
            self.children.append((child, args, kwargs))
            return child
        self.addCleanup(patch.stopall)
        patch.object(s3.subprocess, 'Popen', side_effect=spawn).start()
        patch.object(socket.socket, 'connect', side_effect=AssertionError('NETWORK_FORBIDDEN')).start()
        patch.object(socket, 'getaddrinfo', side_effect=AssertionError('NETWORK_FORBIDDEN')).start()

    def instrumented(self, mode='sdk', status=200):
        dest = self.home / ('copy-' + str(len(list(self.home.glob('copy-*'))))) / 'scripts'
        dest.mkdir(parents=True)
        for path in (ROOT / 'scripts').glob('v126-*.py'):
            shutil.copy2(path, dest / path.name)
        worker = dest / 'v126-dr-s3-worker.py'
        source = worker.read_text()
        marker = "if __name__ == '__main__':\n    try:\n        main"
        inject = '\n'.join((f'MODE = {mode!r}', f'STATUS = {status!r}', f'AUDIT = {str(self.audit)!r}',
                            f'MARKERS = {CANARIES!r}', f'BINARY_BODY = {BODY!r}', f'RETAIN_UNTIL = {ipc.date(UNTIL)!r}', FIXTURE)) + '\n\n'
        self.assertIn(marker, source)
        worker.write_text(source.replace(marker, inject + marker))
        return worker

    def fake(self, code):
        worker = self.home / 'v126-dr-s3-worker.py'
        worker.write_text('import os, sys, time, signal\n'
            + f"with open({str(self.audit)!r}, 'a') as f: f.write(str(os.getpid()) + '\\n')\n"
            + code)
        return worker

    def call(self, worker, op='write', timeout=5):
        with patch.object(s3, 'WORKER', worker):
            client = s3.make_client(TARGET, TARGET_HASH, **CREDENTIALS, timeout=timeout)
            try:
                if op == 'write':
                    return s3.Writer(client, TARGET, TARGET_HASH).create_once(TARGET_HASH, KEY, BODY,
                        custody_version_sha256=CUSTODY, retain_until=UNTIL)
                if op == 'read':
                    return s3.ExactReader(client, TARGET, TARGET_HASH).read_exact(TARGET_HASH, KEY, 'version-001')
                observer = s3.MetadataObserver(client, TARGET, TARGET_HASH)
                return observer.observe_bucket() if op == 'observe_bucket' else observer.observe_version(
                    TARGET_HASH, KEY, 'version-001', required_until=UNTIL)
            finally:
                client.close()

    def safe_error(self, fn, code, kind=s3.TransportError):
        with self.assertRaises(kind) as caught:
            fn()
        self.assertEqual(str(caught.exception), code)
        rendered = ''.join(traceback.format_exception(caught.exception))
        for marker in CANARIES:
            self.assertNotIn(marker, rendered)
        return caught.exception

    def events(self):
        return [json.loads(line) for line in self.audit.read_text().splitlines()]

    def reaped(self):
        for child, args, kwargs in self.children:
            self.assertIsNotNone(child.returncode)
            with self.assertRaises(ChildProcessError):
                os.waitpid(child.pid, os.WNOHANG)
            try:
                os.kill(child.pid, 0)
            except ProcessLookupError:
                pass
            else:
                self.fail('child remained alive')
            self.assertTrue(child.stdin.closed)
            self.assertTrue(child.stdout.closed)

    def direct(self, worker, data, python=sys.executable):
        result = subprocess.run([python, '-I', '-S', '-B', str(worker)], input=data,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5,
            env={'LANG': 'C', 'LC_ALL': 'C', 'AWS_EC2_METADATA_DISABLED': 'true'})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, b'')
        control, body = ipc.decode(result.stdout, ipc.MAX_BODY)
        for marker in CANARIES:
            self.assertNotIn(marker.encode(), result.stdout)
        return control, body


class RuntimeTests(WorkerTest):
    def test_supported_runtime_gate_and_real_sdk_in_fresh_worker(self):
        self.assertEqual(self.call(self.instrumented()), 'version-001')
        events = self.events()
        self.assertTrue(any(e['event'] == 'SDK' for e in events))
        self.assertFalse(events[0]['sdk_present'])
        self.assertFalse(events[0]['site_present'])
        self.assertFalse(any(e['event'] == 'AMBIENT_CREDENTIAL_ATTEMPT' for e in events))
        self.reaped()

    def test_actual_python311_rejects_before_site_sdk_or_provider(self):
        python = '/opt/homebrew/bin/python3.11'
        if not Path(python).is_file():
            self.skipTest('actual CPython 3.11 interpreter unavailable')
        control, body = self.direct(self.instrumented(), ipc.encode(request(), BODY), python)
        self.assertEqual(control['code'], 'AP00_WORKER_RUNTIME_UNSUPPORTED')
        self.assertIsNone(control['runtime'])
        self.assertEqual([e['event'] for e in self.events()], ['START'])

    def test_non_cpython_free_threaded_disabled_and_missing_gil_refuse(self):
        for mode in ('non_cpython', 'gil_disabled', 'free_threaded', 'missing_gil_api'):
            with self.subTest(mode=mode):
                self.audit.unlink(missing_ok=True)
                self.safe_error(lambda: self.call(self.instrumented(mode)), 'AP00_WORKER_RUNTIME_UNSUPPORTED')
                self.assertEqual([e['event'] for e in self.events()], ['START'])
        self.reaped()

    def test_dependency_failure_before_provider_is_fixed_rejection(self):
        self.safe_error(lambda: self.call(self.instrumented('dependency')), 'AP00_S3_DEPENDENCY_UNAVAILABLE')
        self.assertFalse(any(e['event'] == 'REQUEST' for e in self.events()))
        self.reaped()


class ProtocolTests(WorkerTest):
    def test_binary_roundtrip_with_nuls_and_non_utf8(self):
        raw = ipc.encode(request(), BODY)
        control, body = ipc.decode(raw, ipc.MAX_BODY)
        ipc.request(control, body)
        self.assertEqual(body, BODY)
        self.assertEqual(len(raw), ipc.HEADER.size + len(dr.canonical(control)) + len(BODY))

    def test_request_adversarial_matrix_refuses_before_sdk(self):
        good = request()
        mutations = []
        for field, value in [('version', 2), ('version', True), ('operation', 'delete'),
                             ('operation', 'list_latest'), ('kind', 'response')]:
            mutated = copy.deepcopy(good); mutated[field] = value
            mutations.append(ipc.encode(mutated, BODY))
        for mutant in (dict(good, unknown=1), {k:v for k,v in good.items() if k != 'arguments'},
                       dict(good, arguments=dict(good['arguments'], unexpected=1))):
            mutations.append(ipc.encode(mutant, BODY))
        canonical = dr.canonical(good)
        for control in (b'{', canonical.replace(b'"version":1', b'"version":1,"version":1'),
                        b' ' + canonical, b'[]\n'):
            mutations.append(ipc.HEADER.pack(ipc.MAGIC, len(control), len(BODY)) + control + BODY)
        valid = ipc.encode(good, BODY)
        mutations += [b'', b'junk', valid[:-1], valid+b'x', valid+valid,
            ipc.HEADER.pack(ipc.MAGIC, 0, 0), ipc.HEADER.pack(ipc.MAGIC, ipc.MAX_CONTROL+1, 0),
            ipc.HEADER.pack(ipc.MAGIC, 1, ipc.MAX_BODY+1),
            b'HS3W' + b'\xff'*12,  # signed negative lengths become forbidden unsigned maxima
            ipc.encode(request('read'), b'x')]
        worker = self.instrumented()
        for i, raw in enumerate(mutations):
            with self.subTest(case=i):
                control, body = self.direct(worker, raw)
                self.assertEqual(control['result'], 'REJECTED')
                self.assertEqual(control['code'], 'AP00_WORKER_PROTOCOL_INVALID')
        self.assertTrue(all(e['event'] == 'START' for e in self.events()))
        print('IPC_REQUEST_ADVERSARIAL_CASES', len(mutations))

    def test_response_adversarial_matrix_is_unknown_for_write(self):
        valid = ipc.encode(response())
        cases = [b'', b'junk '+CANARIES[0].encode(), valid[:-1], valid+b'x', valid+valid,
            ipc.HEADER.pack(ipc.MAGIC, ipc.MAX_CONTROL+1, 0),
            ipc.HEADER.pack(ipc.MAGIC, 1, ipc.MAX_BODY+1), b'HS3W'+b'\xff'*12,
            ipc.encode(response(version=2)), ipc.encode(response(version=True)),
            ipc.encode(response(operation='read')), ipc.encode(response(result='RETRY')),
            ipc.encode(response(unknown=1)), ipc.encode(response(value={'version_id':'latest'})),
            ipc.encode(response(runtime='3.13.999')), ipc.encode(response(), b'x'),
            ipc.encode(response(result='REJECTED', code=CANARIES[0], value=None))]
        raw = dr.canonical(response())
        for control in (b'{', raw.replace(b'"version":1', b'"version":1,"version":1'), b' '+raw):
            cases.append(ipc.HEADER.pack(ipc.MAGIC, len(control), 0)+control)
        for i, raw in enumerate(cases):
            with self.subTest(case=i):
                worker = self.fake(f"sys.stdin.buffer.read({len(ipc.encode(request(), BODY))+1})\nos.write(1, {raw!r})\n")
                previous = len(self.children)
                self.safe_error(lambda: self.call(worker), 'AP00_WRITE_UNKNOWN_WORKER', s3.WriteUnknown)
                self.assertEqual(len(self.children), previous+1)
        self.reaped()
        print('IPC_RESPONSE_ADVERSARIAL_CASES', len(cases))

    def test_read_and_metadata_responses_fail_closed(self):
        for op in ('read', 'observe_version', 'observe_bucket'):
            worker = self.fake("os.write(1, b'HT31_SYNTHETIC_CREDENTIAL_5fb2')\n")
            self.safe_error(lambda: self.call(worker, op), 'AP00_WORKER_UNAVAILABLE')
        for op in ('read', 'observe_version'):
            value = response(op)['value'] | {'version_id':'different-version'}
            frame = ipc.encode(response(op, value=value), BODY if op == 'read' else b'')
            worker = self.fake(f'os.write(1, {frame!r})\n')
            self.safe_error(lambda: self.call(worker, op), 'AP00_WORKER_UNAVAILABLE')
        self.reaped()

    def test_valid_result_with_nonzero_exit_or_signal_remains_unknown(self):
        for ending in ('sys.exit(7)', 'os.kill(os.getpid(), signal.SIGTERM)',
                       "raise RuntimeError('HT31_SYNTHETIC_CREDENTIAL_5fb2')"):
            worker = self.fake(f'os.write(1, {ipc.encode(response())!r})\n'+ending+'\n')
            self.safe_error(lambda: self.call(worker), 'AP00_WRITE_UNKNOWN_WORKER', s3.WriteUnknown)
        self.reaped()

    def test_output_early_close_and_hang_killed_with_bounded_cleanup(self):
        for code in ("os.close(1)\ntime.sleep(20)\n",
                     "signal.signal(signal.SIGTERM, signal.SIG_IGN)\ntime.sleep(20)\n"):
            start = time.monotonic()
            self.safe_error(lambda: self.call(self.fake(code), timeout=0.3), 'AP00_WRITE_UNKNOWN_WORKER', s3.WriteUnknown)
            self.assertLess(time.monotonic()-start, 3)
        self.reaped()

    def test_stderr_canary_is_discarded_on_valid_and_invalid_output(self):
        for output in (ipc.encode(response()), CANARIES[1].encode()):
            worker = self.fake(f'os.write(2, {CANARIES[0].encode()!r})\nos.write(1, {output!r})\n')
            if output.startswith(ipc.MAGIC):
                self.assertEqual(self.call(worker), 'version-001')
            else:
                self.safe_error(lambda: self.call(worker), 'AP00_WRITE_UNKNOWN_WORKER', s3.WriteUnknown)
        self.reaped()


class OperationTests(WorkerTest):
    def test_write_status_matrix_one_process_one_put_no_reconciliation(self):
        cases = [(200, None), *[(s, 'AP00_WRITE_UNKNOWN_HTTP') for s in (201,202,204,206)],
                 (403,'AP00_S3_FORBIDDEN'), (404,'AP00_S3_EXACT_VERSION_UNAVAILABLE'),
                 (409,'AP00_S3_CONDITIONAL_RACE'), (412,'AP00_S3_CREATE_ONLY_PRECONDITION')]
        for status, code in cases:
            with self.subTest(status=status):
                self.audit.unlink(missing_ok=True)
                before = len(self.children)
                worker = self.instrumented(status=status)
                if code is None:
                    self.assertEqual(self.call(worker), 'version-001')
                else:
                    self.safe_error(lambda: self.call(worker), code,
                        s3.WriteUnknown if status < 300 else s3.TransportError)
                self.assertEqual(len(self.children), before+1)
                req = [e for e in self.events() if e['event'] == 'REQUEST']
                self.assertEqual([e['method'] for e in req], ['PUT'])
                self.assertTrue(req[0]['conditional'])
        self.reaped()
        print('WRITE_STATUS_MATRIX: 9 cases; exactly one process/PUT each; no HEAD/LIST')

    def test_timeout_crash_and_malformed_after_dispatch_never_retry(self):
        for mode in ('timeout', 'crash', 'malformed_after_dispatch'):
            with self.subTest(mode=mode):
                self.audit.unlink(missing_ok=True)
                before = len(self.children)
                self.safe_error(lambda: self.call(self.instrumented(mode), timeout=2),
                                'AP00_WRITE_UNKNOWN_WORKER', s3.WriteUnknown)
                self.assertEqual(len(self.children), before+1)
                self.assertEqual([e['method'] for e in self.events() if e['event']=='REQUEST'], ['PUT'])
        self.reaped()
        print('WRITE_AMBIGUITY: timeout/crash/malformed after PUT => UNKNOWN; process=1, PUT=1 each')

    def test_exact_read_body_and_new_pid_on_every_operation(self):
        worker = self.instrumented()
        self.assertEqual(self.call(worker), 'version-001')
        self.assertEqual(self.call(worker, 'read'), BODY)
        starts = [e for e in self.events() if e['event']=='START']
        req = [e for e in self.events() if e['event']=='REQUEST']
        self.assertEqual(len(starts), 2)
        self.assertEqual(len({e['pid'] for e in starts}), 2)
        self.assertEqual([e['method'] for e in req], ['PUT','GET'])
        self.assertTrue(req[1]['exact_query'])
        self.reaped()

    def test_read_retries_stay_in_one_child_and_exact_version(self):
        self.assertEqual(self.call(self.instrumented('retry_read'), 'read'), BODY)
        self.assertEqual(len(self.children), 1)
        requests = [e for e in self.events() if e['event']=='REQUEST']
        self.assertEqual([e['method'] for e in requests], ['GET','GET','GET'])
        self.assertTrue(all(e['exact_query'] for e in requests))
        self.reaped()

    def test_metadata_operations_keep_two_reads_inside_one_child(self):
        worker = self.instrumented()
        value = self.call(worker, 'observe_version')
        self.assertEqual(value, dict(version_id='version-001', size=len(BODY), mode='COMPLIANCE', retained_until=UNTIL))
        self.assertEqual(len(self.children), 1)
        self.assertEqual([e['method'] for e in self.events() if e['event']=='REQUEST'], ['HEAD','GET'])
        self.audit.unlink()
        self.assertEqual(self.call(worker, 'observe_bucket'), response('observe_bucket')['value'])
        self.assertEqual(len(self.children), 2)
        self.assertEqual([e['method'] for e in self.events() if e['event']=='REQUEST'], ['GET','GET'])
        self.reaped()

    def test_launch_contract_controlled_environment_argv_and_fds(self):
        fd = os.open(self.home/'synthetic-inherited', os.O_CREAT | os.O_RDWR, 0o600)
        self.addCleanup(os.close, fd)
        os.dup2(fd, 199, inheritable=True)
        self.addCleanup(os.close, 199)
        with patch.dict(os.environ, {'AWS_ACCESS_KEY_ID':CANARIES[0], 'AWS_PROFILE':CANARIES[1],
            'PYTHONPATH':'/forbidden', 'HTTPS_PROXY':'https://forbidden.invalid'}):
            self.assertEqual(self.call(self.instrumented()), 'version-001')
        child, args, kwargs = self.children[0]
        self.assertEqual(args[0][:4], [sys.executable,'-I','-S','-B'])
        self.assertEqual(len(args[0]), 5)
        self.assertTrue(kwargs['close_fds'])
        self.assertFalse(kwargs['shell'])
        self.assertEqual(kwargs['stderr'], subprocess.DEVNULL)
        self.assertEqual(kwargs['env'], {'LANG':'C','LC_ALL':'C','AWS_EC2_METADATA_DISABLED':'true'})
        for marker in CANARIES:
            self.assertNotIn(marker, repr(args)+repr(kwargs))
        self.assertFalse(self.events()[0]['inherited_fd_open'])
        self.assertLessEqual(set(self.events()[0]['environment']),
                             {'AWS_EC2_METADATA_DISABLED','LANG','LC_ALL','__CF_USER_TEXT_ENCODING'})
        self.reaped()

    def test_concurrent_operations_use_distinct_children_and_reap(self):
        worker = self.instrumented()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _:self.call(worker), range(2)))
        self.assertEqual(results, ['version-001','version-001'])
        self.assertEqual(len(self.children), 2)
        self.assertEqual(len({c.pid for c,_,_ in self.children}), 2)
        self.reaped()

    def test_invalid_timeout_and_spawn_failure_do_not_dispatch(self):
        for timeout in (0, -1, True, float('inf'), float('nan'), 301):
            self.safe_error(lambda: s3.make_client(TARGET,TARGET_HASH,**CREDENTIALS,timeout=timeout), 'AP00_S3_CLIENT_INVALID')
        with patch.object(s3.subprocess, 'Popen', side_effect=OSError(CANARIES[0])):
            self.safe_error(lambda:self.call(ROOT/'scripts/v126-dr-s3-worker.py'), 'AP00_WORKER_UNAVAILABLE')
        self.assertEqual(self.children, [])


class IsolationTests(WorkerTest):
    def test_parent_sdk_import_isolation_construct_success_reload_and_logging(self):
        self.assertFalse(any(n.split('.')[0] in ('boto3','botocore','urllib3') for n in sys.modules))
        client = s3.make_client(TARGET,TARGET_HASH,**CREDENTIALS)
        self.assertFalse(any(n.split('.')[0] in ('boto3','botocore','urllib3') for n in sys.modules))
        client.close()
        seen, formatted = [], []
        saved_setter, saved_code = logging.setLogRecordFactory, logging.setLogRecordFactory.__code__
        previous = logging.getLogRecordFactory()
        def factory(*a, **k):
            seen.append((a,k)); return logging.LogRecord(*a, **k)
        class Handler(logging.Handler):
            def emit(self, record): formatted.append(self.format(record))
        handler = Handler(); logging.root.addHandler(handler)
        self.addCleanup(logging.root.removeHandler, handler)
        self.addCleanup(saved_setter, previous)
        saved_setter(factory)
        before = (logging.root.level, tuple(logging.root.handlers), tuple(logging.root.filters), logging.root.manager.disable)
        worker = self.instrumented('malformed_header')
        self.assertEqual(self.call(worker), 'version-001')
        self.assertTrue(any(e['event']=='MALFORMED_HEADER_WARNING' for e in self.events()))
        self.assertEqual(seen, [])
        self.assertEqual(formatted, [])
        self.assertIs(logging.getLogRecordFactory(), factory)
        self.assertIs(logging.setLogRecordFactory, saved_setter)
        self.assertIs(saved_setter.__code__, saved_code)
        self.assertEqual(before,(logging.root.level,tuple(logging.root.handlers),tuple(logging.root.filters),logging.root.manager.disable))
        saved_setter(factory=previous); saved_setter(factory)
        # Normal importlib reload plus duplicate fixed-file loading; no shared owner.
        sys.path.insert(0, str(ROOT/'scripts'))
        try:
            reloaded = importlib.import_module('v126-dr-s3')
            importlib.reload(reloaded)
        finally:
            sys.path.pop(0)
        duplicate = module(ROOT/'scripts/v126-dr-s3.py', 'parent_duplicate')
        self.assertFalse(hasattr(duplicate, '_record_factory'))
        with patch.object(reloaded, 'WORKER', worker):
            launcher = reloaded.make_client(TARGET, TARGET_HASH, **CREDENTIALS)
            self.assertEqual(reloaded.Writer(launcher, TARGET, TARGET_HASH).create_once(TARGET_HASH, KEY, BODY,
                custody_version_sha256=CUSTODY, retain_until=UNTIL), 'version-001')
            launcher.close()
        self.assertIs(logging.getLogRecordFactory(), factory)
        logging.getLogger('application').warning('APPLICATION_SENTINEL')
        self.assertEqual(formatted, ['APPLICATION_SENTINEL'])
        self.assertFalse(any(n.split('.')[0] in ('boto3','botocore','urllib3') for n in sys.modules))
        self.reaped()
        print('PARENT_ISOLATION: import/construct/operation/reload SDK absent; factory/setter/handlers unchanged; canaries=0')

    def test_logging_module_reload_has_no_worker_ownership_dependency(self):
        # Isolate stdlib reload so other tests' logging objects stay coherent.
        code = f'''import runpy, logging, importlib, sys
ns=runpy.run_path({str(Path(__file__).resolve())!r}, run_name='fixture_only')
t=ns['WorkerTest'](); t.setUp()
try:
 worker=t.instrumented('malformed_header')
 assert t.call(worker)=='version-001'
 importlib.reload(logging)
 assert t.call(worker)=='version-001'
 assert not any(n.split('.')[0] in ('boto3','botocore','urllib3') for n in sys.modules)
 t.reaped()
finally: t.doCleanups()
print('LOGGING_RELOAD_INDEPENDENT_PASS')
'''
        result = subprocess.run([sys.executable,'-I','-B','-c',code], capture_output=True, timeout=15)
        self.assertEqual(result.returncode,0,result.stderr.decode())
        self.assertIn(b'LOGGING_RELOAD_INDEPENDENT_PASS',result.stdout)


class BindingTests(WorkerTest):
    def test_worker_and_protocol_bytes_change_policy_b_tool_identity(self):
        fixture_module = module(ROOT/'scripts/test-v126-dr-evidence.py', 'binding_fixture')
        fixture = fixture_module.Fixture()
        binding = fixture.binding
        self.assertEqual(dr.tooling(ROOT), binding['tools'])
        dr.verify_checkout(binding, ROOT)
        checkout = self.home / 'binding-copy'
        checkout.mkdir()
        # Read-only pointer to the isolated candidate gitdir; never the primary git.
        (checkout / '.git').write_bytes((ROOT / '.git').read_bytes())
        for entry in binding['tools']:
            dest = checkout / entry['path']
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / entry['path'], dest)
        dr.verify_checkout(binding, checkout)
        for changed in ('scripts/v126-dr-s3-worker.py', 'scripts/v126-dr-s3-ipc.py'):
            dest = checkout / changed
            original = dest.read_bytes()
            self.assertIn(changed, [v['path'] for v in binding['tools']])
            dest.write_bytes(original + b'\n# synthetic source mutation\n')
            self.assertNotEqual(dr.tooling(checkout), binding['tools'])
            with self.assertRaisesRegex(ValueError, '^SOURCE_TOOL_BINDING_MISMATCH$'):
                dr.verify_checkout(binding, checkout)
            dest.write_bytes(original)
        print('SOURCE_BINDING: worker+IPC glob entries; byte change rejects existing binding; schema unchanged')


if __name__ == '__main__':
    unittest.main(verbosity=2)
