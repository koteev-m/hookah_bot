"""One-shot AP-00 S3 executable. Production callers execute, never import it.

No custody/key acquisition, daemon, pool, reconciliation or operational CLI.
"""
import os
import sys

# Keep the original pipe private; raw Python/C/library stdout and stderr are sinks.
# This runs before local modules, site-packages or SDK imports. The duplicate is
# non-inheritable and is never passed to SDK clients.
if __name__ == '__main__':
    _protocol_output = os.fdopen(os.dup(1), 'wb', buffering=0)
    os.set_inheritable(_protocol_output.fileno(), False)
    with open(os.devnull, 'wb') as _sink:
        os.dup2(_sink.fileno(), 1)
        os.dup2(_sink.fileno(), 2)

import base64
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import logging
from pathlib import Path
import time
from urllib.parse import urlsplit

_spec = importlib.util.spec_from_file_location('dr_provisioning', Path(__file__).with_name('v126-dr-provisioning.py'))
provisioning = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(provisioning)
dr = provisioning.dr
S = dr.schema
crypto = dr.module('dr_crypto', Path(__file__).with_name('v126-dr-crypto.py'))
READ_ATTEMPTS = 3


ipc = dr.module('dr_s3_ipc', Path(__file__).with_name('v126-dr-s3-ipc.py'))
TransportError = ipc.TransportError
WriteUnknown = ipc.WriteUnknown


class _HTTPFailure(Exception):
    def __init__(self, status):
        self.status = status


def sdk():
    qualify_runtime()
    try:
        import boto3
        import urllib3
        import botocore.session
        from botocore.config import Config
        from botocore import exceptions
        return boto3, botocore.session, Config, exceptions
    except ImportError:
        raise TransportError('AP00_S3_DEPENDENCY_UNAVAILABLE') from None


def _stop_redirects(response=None, **kwargs):
    # The S3 region redirector can retry outside the normal retry budget and
    # perform HeadBucket. Stop all HTTP errors before that SDK handler runs.
    if response is not None:
        status = response[0].status_code
        if status >= 300:
            raise _HTTPFailure(status)


def make_client(target_raw, target_sha256, *, access_key, secret_key, session_token=None):
    """Explicit caller credentials only. No environment/profile/IMDS resolver.

    The SDK retains explicit credentials only for this short-lived process.
    All clients have SDK retries disabled; only the read wrappers retry safely.
    """
    qualify_runtime()
    destination = provisioning.target(target_raw, target_sha256)
    boto3, session_module, Config, _ = sdk()
    try:
        if (type(access_key) is not str or not access_key or type(secret_key) is not str or not secret_key
                or (session_token is not None and (type(session_token) is not str or not session_token))):
            raise ValueError
        session = session_module.Session(session_vars={'profile': (None, None, None, None)})
        session.set_config_variable('config_file', os.devnull)
        session.set_config_variable('credentials_file', os.devnull)
        session.set_config_variable('profile', None)
        session.set_credentials(access_key, secret_key, session_token)
        client = boto3.Session(botocore_session=session).client(
            's3', endpoint_url=destination['endpoint'], region_name=destination['region'],
            aws_access_key_id=access_key, aws_secret_access_key=secret_key,
            aws_session_token=session_token, use_ssl=True, verify=True,
            config=Config(signature_version='s3v4', connect_timeout=5, read_timeout=15,
                          retries={'mode': 'standard', 'total_max_attempts': 1},
                          proxies={}, ignore_configured_endpoint_urls=True,
                          request_checksum_calculation='when_required',
                          response_checksum_validation='when_required',
                          s3={'addressing_style': 'path', 'use_accelerate_endpoint': False},
                          use_dualstack_endpoint=False, use_fips_endpoint=False))
        client.meta.events.register_first('needs-retry.s3', _stop_redirects)
        # Guard the signed destination too; an SDK endpoint/region override must
        # not send credentials to any target other than the bound descriptor.
        def check_destination(request, region_name, **kwargs):
            url = urlsplit(request.url)
            if (url.scheme + '://' + url.netloc != destination['endpoint']
                    or url.username is not None or region_name != destination['region']
                    or not url.path.startswith('/' + destination['bucket'] + '/')
                    and url.path != '/' + destination['bucket']):
                raise TransportError('AP00_S3_TARGET_MISMATCH')
        client.meta.events.register_first('before-sign.s3', check_destination)
        client._ap00_target_sha256 = target_sha256
        return client
    except Exception:
        raise TransportError('AP00_S3_CLIENT_INVALID') from None


def _date(value):
    if (not isinstance(value, datetime) or value.tzinfo is None
            or value.utcoffset() != timedelta(0) or value.microsecond):
        raise TransportError('AP00_S3_RETENTION_INVALID')
    return value


def _utc_now():
    # Local precondition only, not an AP-06 independently trusted clock.
    return datetime.now(timezone.utc)


def _status_error(status, writing):
    known = {403: 'FORBIDDEN', 404: 'EXACT_VERSION_UNAVAILABLE',
             409: 'CONDITIONAL_RACE', 412: 'CREATE_ONLY_PRECONDITION'}
    if status in known:
        raise TransportError('AP00_S3_' + known[status]) from None
    if status in (301, 302, 307, 308):
        raise TransportError('AP00_S3_TARGET_MISMATCH') from None
    if writing and (200 <= status < 300 or status >= 500 or status in (408, 429)):
        raise WriteUnknown('AP00_WRITE_UNKNOWN_HTTP') from None
    raise TransportError('AP00_S3_REQUEST_REJECTED') from None


class _Bound:
    def __init__(self, client, target_raw, target_sha256):
        self._target = provisioning.target(target_raw, target_sha256)
        self._target_sha256 = target_sha256
        self._client = client
        self._check_client()

    def _check_client(self):
        try:
            config = self._client.meta.config
            if (self._client._ap00_target_sha256 != self._target_sha256
                    or self._client.meta.endpoint_url != self._target['endpoint']
                    or self._client.meta.region_name != self._target['region']
                    or config.retries != {'mode': 'standard', 'total_max_attempts': 1}
                    or config.signature_version != 's3v4'
                    or config.s3['addressing_style'] != 'path'):
                raise ValueError
        except Exception:
            raise TransportError('AP00_S3_TARGET_OR_CLIENT_MISMATCH') from None

    def _address(self, target_sha256, key, version_id=None):
        try:
            self._check_client()
            S.check(target_sha256, 'sha')
            S.check(key, 'key')
            if target_sha256 != self._target_sha256:
                raise ValueError
            result = {'Bucket': self._target['bucket'], 'Key': key}
            if version_id is not None:
                S.check(version_id, 'object-version')
                result['VersionId'] = version_id
            return result
        except Exception:
            raise TransportError('AP00_S3_ADDRESS_INVALID') from None

    def _request(self, operation, params, *, writing=False, consume=None):
        _, _, _, errors = sdk()
        transient = (errors.ConnectionClosedError, errors.ConnectTimeoutError,
                     errors.ReadTimeoutError, errors.EndpointConnectionError,
                     errors.ResponseStreamingError, errors.IncompleteReadError)
        for attempt in range(1 if writing else READ_ATTEMPTS):
            status = None
            response = None
            try:
                self._check_client()
                response = getattr(self._client, operation)(**params)
                if (type(response) is not dict or type(response.get('ResponseMetadata')) is not dict
                        or type(response['ResponseMetadata'].get('HTTPStatusCode')) is not int):
                    raise ValueError
                headers = response['ResponseMetadata'].get('HTTPHeaders', {})
                if type(headers) is not dict:
                    raise ValueError
                if headers.get('x-amz-bucket-region', self._target['region']) != self._target['region']:
                    if writing:
                        raise WriteUnknown('AP00_WRITE_UNKNOWN_REGION_MISMATCH')
                    raise TransportError('AP00_S3_TARGET_MISMATCH')
                status = response['ResponseMetadata']['HTTPStatusCode']
                if status != 200:
                    raise _HTTPFailure(status)
                return consume(response) if consume else response
            except (_HTTPFailure, errors.ClientError) as exc:
                if isinstance(exc, _HTTPFailure):
                    status = exc.status
                else:
                    # Never expose the provider's Error/Message/RequestId fields.
                    status = exc.response.get('ResponseMetadata', {}).get('HTTPStatusCode')
                if type(status) is not int:
                    if writing:
                        raise WriteUnknown('AP00_WRITE_UNKNOWN_RESPONSE') from None
                    raise TransportError('AP00_S3_RESPONSE_INVALID') from None
                if writing or status not in (408, 429, 500, 502, 503, 504) or attempt == READ_ATTEMPTS - 1:
                    _status_error(status, writing)
            except transient:
                if writing:
                    raise WriteUnknown('AP00_WRITE_UNKNOWN_NETWORK') from None
                if attempt == READ_ATTEMPTS - 1:
                    raise TransportError('AP00_S3_READ_UNAVAILABLE') from None
            except errors.SSLError:
                if writing:
                    raise WriteUnknown('AP00_WRITE_UNKNOWN_TLS') from None
                raise TransportError('AP00_S3_TLS_FAILURE') from None
            except TransportError:
                raise
            except Exception:
                if writing:
                    raise WriteUnknown('AP00_WRITE_UNKNOWN_RESPONSE') from None
                raise TransportError('AP00_S3_RESPONSE_INVALID') from None
            finally:
                # Also close a streaming response rejected before the consumer.
                if not writing and type(response) is dict and response.get('Body') is not None:
                    try:
                        response['Body'].close()
                    except Exception:
                        raise TransportError('AP00_S3_STREAM_CLOSE_FAILED') from None
            time.sleep(0.1 * (2 ** attempt))


class Writer(_Bound):
    """Current-key conditional create; never proof of all-history uniqueness.

    Delete markers/other writers/admin actions can permit reuse of a historical
    key. Exact VersionId and ciphertext identity remain the evidence authority.
    """
    def create_once(self, target_sha256, key, ciphertext, *, custody_version_sha256, retain_until):
        params = self._address(target_sha256, key)
        retain_until = _date(retain_until)
        if retain_until < _utc_now() + timedelta(seconds=self._target['retention_seconds']):
            raise TransportError('AP00_S3_RETENTION_TOO_SHORT')
        crypto.inspect_envelope(ciphertext, {
            'target_sha256': target_sha256, 'manifest_sha256': key[8:-4],
            'object_key': key, 'custody_version_sha256': custody_version_sha256})
        params.update(Body=ciphertext, ContentLength=len(ciphertext),
                      ContentMD5=base64.b64encode(hashlib.md5(ciphertext, usedforsecurity=False).digest()).decode('ascii'),
                      ContentType='application/octet-stream', StorageClass='STANDARD', IfNoneMatch='*',
                      ObjectLockMode='COMPLIANCE', ObjectLockRetainUntilDate=retain_until)
        def version(response):
            try:
                S.check(response.get('VersionId'), 'object-version')
            except Exception:
                raise WriteUnknown('AP00_WRITE_UNKNOWN_VERSION_ID') from None
            return response['VersionId']
        return self._request('put_object', params, writing=True, consume=version)


class ExactReader(_Bound):
    def read_exact(self, target_sha256, key, version_id):
        if version_id is None:
            raise TransportError('AP00_S3_ADDRESS_INVALID')
        params = self._address(target_sha256, key, version_id)
        def read(response):
            body = response.get('Body')
            try:
                if (response.get('VersionId') != version_id or response.get('DeleteMarker', False) is not False
                        or type(response.get('ContentLength')) is not int
                        or not 0 < response['ContentLength'] <= crypto.MAX_ENVELOPE_BYTES):
                    raise TransportError('AP00_S3_EXACT_RESPONSE_INVALID')
                raw = body.read(response['ContentLength'] + 1)
                if type(raw) is not bytes or len(raw) != response['ContentLength']:
                    raise TransportError('AP00_S3_EXACT_RESPONSE_INVALID')
                return raw
            finally:
                if body is not None:
                    body.close()
        # Hash/size against evidence stays in AP-01 independent_readback.
        return self._request('get_object', params, consume=read)


class MetadataObserver(_Bound):
    def observe_version(self, target_sha256, key, version_id, *, required_until):
        if version_id is None:
            raise TransportError('AP00_S3_ADDRESS_INVALID')
        params = self._address(target_sha256, key, version_id)
        _date(required_until)
        response = self._request('get_object_retention', params)
        try:
            retention = response['Retention']
            until = _date(retention['RetainUntilDate'])
            if retention['Mode'] != 'COMPLIANCE' or until < required_until:
                raise ValueError
        except Exception:
            raise TransportError('AP00_S3_RETENTION_MISMATCH') from None
        # Retention has no response VersionId: this is request correlation only.
        return {'requested_version_id': version_id, 'mode': 'COMPLIANCE', 'retained_until': until}

    def observe_bucket(self):
        self._check_client()
        params = {'Bucket': self._target['bucket']}
        versioning = self._request('get_bucket_versioning', params)
        lock = self._request('get_object_lock_configuration', params)
        try:
            config = lock['ObjectLockConfiguration']
            retention = config['Rule']['DefaultRetention']
            if (versioning['Status'] != 'Enabled' or config['ObjectLockEnabled'] != 'Enabled'
                    or retention['Mode'] != 'COMPLIANCE' or set(retention) != {'Mode', 'Days'}
                    or type(retention['Days']) is not int
                    or retention['Days'] * 86400 < self._target['retention_seconds']):
                raise ValueError
        except Exception:
            raise TransportError('AP00_S3_BUCKET_CONFIGURATION_MISMATCH') from None
        return {'versioning': 'ENABLED', 'object_lock': 'ENABLED', 'mode': 'COMPLIANCE',
                'default_retention_days': retention['Days']}


def qualify_runtime():
    # -I -S startup permits this gate BEFORE site-packages, SDK acquisition or I/O.
    import sysconfig
    if (sys.implementation.name != 'cpython' or sys.version_info[:2] != (3, 13)
            or not callable(getattr(sys, '_is_gil_enabled', None))
            or sys._is_gil_enabled() is not True
            or sysconfig.get_config_var('Py_GIL_DISABLED') not in (None, 0)):
        raise TransportError('AP00_WORKER_RUNTIME_UNSUPPORTED')


def _silence_libraries():
    # This child has no application logging responsibility. No setter patching.
    logging.disable(sys.maxsize)
    logging.getLogger().handlers[:] = [logging.NullHandler()]


def _execute(control, body):
    args = control['arguments']
    op = control['operation']
    client = make_client(dr.canonical(control['target']), control['target_sha256'], **control['credentials'])
    try:
        parameters = (client, dr.canonical(control['target']), control['target_sha256'])
        address = (control['target_sha256'], args.get('key'))
        if op == 'write':
            value = Writer(*parameters).create_once(*address, body,
                custody_version_sha256=args['custody_version_sha256'],
                retain_until=ipc.parse_date(args['retain_until']))
            return {'version_id': value}, b''
        if op == 'read':
            raw = ExactReader(*parameters).read_exact(*address, args['version_id'])
            return {'version_id': args['version_id'], 'size': len(raw)}, raw
        if op == 'observe_version':
            value = MetadataObserver(*parameters).observe_version(*address, args['version_id'],
                required_until=ipc.parse_date(args['required_until']))
            return {**value, 'retained_until': ipc.date(value['retained_until'])}, b''
        return MetadataObserver(*parameters).observe_bucket(), b''
    finally:
        client.close()


def main(source, output):
    """Read once, dispatch once, write once; EOF rejects appended operations."""
    op, runtime, dispatched = None, None, False
    reply = dict(version=ipc.VERSION, kind='response', operation=None,
                 result='REJECTED', code='AP00_WORKER_PROTOCOL_INVALID', value=None, runtime=None)
    returned_body = b''
    try:
        qualify_runtime()
        runtime = dr.platform.python_version()
        control, body = ipc.read_frame(source, ipc.MAX_BODY, request_input=True)
        ipc.request(control, body)
        op = control['operation']
        _silence_libraries()
        # No startup site/.pth code was executed before runtime/protocol admission.
        try:
            import site
            site.main()
            sdk()
        except Exception:
            raise TransportError('AP00_S3_DEPENDENCY_UNAVAILABLE') from None
        _silence_libraries()
        dispatched = True
        value, returned_body = _execute(control, body)
        reply.update(result='SUCCESS', code='OK', value=value)
    except BaseException as exc:
        # Provider exception objects/HTTP bytes/traceback are never protocol data.
        code = str(exc) if isinstance(exc, TransportError) else None
        if isinstance(exc, WriteUnknown) and code in ipc.UNKNOWN_CODES:
            reply.update(result='UNKNOWN', code=code)
        elif isinstance(exc, TransportError) and code in ipc.PRE_CODES | ipc.REJECTION_CODES:
            reply.update(code=code)
        elif op == 'write' and dispatched:
            reply.update(result='UNKNOWN', code='AP00_WRITE_UNKNOWN_WORKER')
        else:
            reply.update(code='AP00_S3_RESPONSE_INVALID' if dispatched else 'AP00_WORKER_PROTOCOL_INVALID')
        returned_body = b''
    reply.update(operation=op, runtime=runtime)
    try:
        # Bound and close the provider-derived success values before serialization.
        ipc.response(reply, returned_body, op, runtime)
        framed = ipc.encode(reply, returned_body)
    except BaseException:
        reply.update(result='UNKNOWN' if op == 'write' and dispatched else 'REJECTED',
                     code='AP00_WRITE_UNKNOWN_WORKER' if op == 'write' and dispatched else 'AP00_S3_RESPONSE_INVALID',
                     value=None)
        framed = ipc.encode(reply)
    output.write(framed)
    output.flush()


if __name__ == '__main__':
    try:
        main(sys.stdin.buffer, _protocol_output)
    finally:
        _protocol_output.close()
