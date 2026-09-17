"""AP-00 stdlib parent transport. Each operation executes one dedicated child.

This module never imports the operational SDK/worker or owns application logging.
No credentials, requests or child diagnostics are written to evidence or logs.
"""
import importlib.util
import math
import os
from pathlib import Path
import selectors
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

_spec = importlib.util.spec_from_file_location('dr_s3_ipc', Path(__file__).with_name('v126-dr-s3-ipc.py'))
ipc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ipc)
provisioning, crypto, dr, S = ipc.provisioning, ipc.crypto, ipc.dr, ipc.S
TransportError, WriteUnknown = ipc.TransportError, ipc.WriteUnknown
WORKER = Path(__file__).resolve().with_name('v126-dr-s3-worker.py')
# Local guards only: no operational RTO or AP-02/AP-03 budget is implied.
DEFAULT_TIMEOUT = 120.0
MAX_TIMEOUT = 300.0
TERMINATE_GRACE = 0.2
KILL_GRACE = 2.0


def _runtime_version():
    return dr.platform.python_version()


def _reap(child):
    if child.poll() is None:
        try:
            child.terminate()
        except ProcessLookupError:
            pass
        try:
            child.wait(timeout=TERMINATE_GRACE)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=KILL_GRACE)
    else:
        child.wait(timeout=KILL_GRACE)


def _exchange(child, data, max_body, deadline):
    """Concurrent bounded pipe I/O avoids pipe deadlock and communicate buffering."""
    cap = ipc.HEADER.size + ipc.MAX_CONTROL + max_body
    output = bytearray()
    sent = 0
    with selectors.DefaultSelector() as selector:
        for pipe, event in ((child.stdin, selectors.EVENT_WRITE), (child.stdout, selectors.EVENT_READ)):
            os.set_blocking(pipe.fileno(), False)
            selector.register(pipe, event)
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError
            events = selector.select(remaining)
            if not events:
                raise TimeoutError
            for key, event in events:
                if event == selectors.EVENT_WRITE:
                    try:
                        count = os.write(key.fd, memoryview(data)[sent:sent + 65536])
                        sent += count
                    except BrokenPipeError:
                        # A pre-dispatch runtime rejection may close input early.
                        sent = len(data)
                    if sent == len(data):
                        selector.unregister(key.fileobj)
                        child.stdin.close()
                else:
                    part = os.read(key.fd, min(65536, cap + 1 - len(output)))
                    if not part:
                        selector.unregister(key.fileobj)
                    else:
                        output.extend(part)
                        if len(output) > cap:
                            ipc.invalid()
                        if len(output) >= ipc.HEADER.size:
                            control_size, body_size = ipc.header(output[:ipc.HEADER.size], max_body)
                            if len(output) > ipc.HEADER.size + control_size + body_size:
                                ipc.invalid()
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError
        if child.wait(timeout=remaining) != 0:
            raise TransportError('AP00_WORKER_EXIT_INVALID')
    return bytes(output)


class _Launcher:
    def __init__(self, target_raw, target_sha256, credentials, timeout):
        try:
            if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= MAX_TIMEOUT:
                raise ValueError
            self._target = provisioning.target(target_raw, target_sha256)
            ipc.credentials(credentials)
        except Exception:
            raise TransportError('AP00_S3_CLIENT_INVALID') from None
        self._target_sha256 = target_sha256
        self._credentials = dict(credentials)
        self._timeout = timeout
        self._closed = False

    def close(self):
        # No process persists between calls. No secure memory erasure is claimed.
        self._closed = True
        self._credentials = None

    def operation(self, operation, arguments, body=b''):
        if self._closed:
            raise TransportError('AP00_S3_CLIENT_INVALID')
        control = dict(version=ipc.VERSION, kind='request', operation=operation,
                       target=self._target, target_sha256=self._target_sha256,
                       credentials=self._credentials, arguments=arguments)
        ipc.request(control, body)
        data = ipc.encode(control, body)
        child = None
        failure = None
        raw = None
        try:
            deadline = time.monotonic() + self._timeout
            # Same executable preserves Policy B's exact Python patch binding.
            # -S defers all site/.pth acquisition until AFTER the worker gate.
            child = subprocess.Popen([sys.executable, '-I', '-S', '-B', str(WORKER)],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                shell=False, close_fds=True, cwd=str(WORKER.parent),
                env={'LANG': 'C', 'LC_ALL': 'C', 'AWS_EC2_METADATA_DISABLED': 'true'})
            raw = _exchange(child, data, ipc.MAX_BODY if operation == 'read' else 0, deadline)
        except BaseException:
            failure = 'AP00_WORKER_UNAVAILABLE'
        finally:
            if child is not None:
                try:
                    _reap(child)
                except Exception:
                    failure = 'AP00_WORKER_UNAVAILABLE'
                finally:
                    for pipe in (child.stdin, child.stdout):
                        try:
                            pipe.close()
                        except OSError:
                            failure = 'AP00_WORKER_UNAVAILABLE'
        if failure is not None:
            if operation == 'write' and child is not None:
                raise WriteUnknown('AP00_WRITE_UNKNOWN_WORKER') from None
            raise TransportError(failure) from None
        try:
            reply, returned_body = ipc.decode(raw, ipc.MAX_BODY if operation == 'read' else 0)
            ipc.response(reply, returned_body, operation, _runtime_version())
            if reply['result'] == 'SUCCESS':
                value = reply['value']
                if operation == 'read' and value['version_id'] != arguments['version_id']:
                    ipc.invalid()
                if operation == 'observe_version':
                    # Correlation with this child request, not a provider ID echo.
                    if (value['requested_version_id'] != arguments['version_id']
                            or S.timestamp(value['retained_until']) < S.timestamp(arguments['required_until'])):
                        ipc.invalid()
                if operation == 'observe_bucket' and value['default_retention_days'] * 86400 < self._target['retention_seconds']:
                    ipc.invalid()
        except Exception:
            if operation == 'write':
                raise WriteUnknown('AP00_WRITE_UNKNOWN_WORKER') from None
            raise TransportError('AP00_WORKER_UNAVAILABLE') from None
        if reply['result'] == 'UNKNOWN':
            raise WriteUnknown(reply['code'])
        if reply['result'] == 'REJECTED':
            raise TransportError(reply['code'])
        return reply['value'], returned_body


def make_client(target_raw, target_sha256, *, access_key, secret_key, session_token=None,
                timeout=DEFAULT_TIMEOUT):
    """Construct a parent launcher with explicit credentials; no SDK or process yet."""
    return _Launcher(target_raw, target_sha256,
                     dict(access_key=access_key, secret_key=secret_key, session_token=session_token), timeout)


class _Bound:
    def __init__(self, client, target_raw, target_sha256):
        self._target = provisioning.target(target_raw, target_sha256)
        self._target_sha256 = target_sha256
        self._client = client
        # Shape/target checks permit ordinary parent module reloads; no global owner.
        try:
            if client._target_sha256 != target_sha256 or client._target != self._target:
                raise ValueError
        except Exception:
            raise TransportError('AP00_S3_TARGET_OR_CLIENT_MISMATCH') from None

    def _address(self, target_sha256, key, version_id=None):
        try:
            S.check(target_sha256, 'sha')
            S.check(key, 'key')
            if target_sha256 != self._target_sha256:
                raise ValueError
            if version_id is not None:
                S.check(version_id, 'object-version')
        except Exception:
            raise TransportError('AP00_S3_ADDRESS_INVALID') from None


class Writer(_Bound):
    """Current-key conditional create, never all-history uniqueness or retry authority."""
    def create_once(self, target_sha256, key, ciphertext, *, custody_version_sha256, retain_until):
        self._address(target_sha256, key)
        deadline = ipc.date(retain_until)
        if retain_until < datetime.now(timezone.utc) + timedelta(seconds=self._target['retention_seconds']):
            raise TransportError('AP00_S3_RETENTION_TOO_SHORT')
        crypto.inspect_envelope(ciphertext, dict(target_sha256=target_sha256,
            manifest_sha256=key[8:-4], object_key=key, custody_version_sha256=custody_version_sha256))
        result, _ = self._client.operation('write', dict(key=key,
            custody_version_sha256=custody_version_sha256, retain_until=deadline), ciphertext)
        return result['version_id']


class ExactReader(_Bound):
    def read_exact(self, target_sha256, key, version_id):
        if version_id is None:
            raise TransportError('AP00_S3_ADDRESS_INVALID')
        self._address(target_sha256, key, version_id)
        _, body = self._client.operation('read', dict(key=key, version_id=version_id))
        return body


class MetadataObserver(_Bound):
    def observe_version(self, target_sha256, key, version_id, *, required_until):
        if version_id is None:
            raise TransportError('AP00_S3_ADDRESS_INVALID')
        self._address(target_sha256, key, version_id)
        value, _ = self._client.operation('observe_version', dict(key=key,
            version_id=version_id, required_until=ipc.date(required_until)))
        return {**value, 'retained_until': ipc.parse_date(value['retained_until'])}

    def observe_bucket(self):
        value, _ = self._client.operation('observe_bucket', {})
        return value
