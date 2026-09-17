"""Private, versioned AP-00 pipe protocol; stdlib only, no operational SDK.

One header/control/body followed by EOF, in each direction. Never format rejected
input in exceptions. The caller owns the deadline for blocking worker-side reads.
"""
from datetime import datetime, timedelta, timezone
import importlib.util
import io
from pathlib import Path
import struct

_spec = importlib.util.spec_from_file_location('ipc_provisioning', Path(__file__).with_name('v126-dr-provisioning.py'))
provisioning = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(provisioning)
dr = provisioning.dr
S = dr.schema
crypto = dr.module('ipc_crypto', Path(__file__).with_name('v126-dr-crypto.py'))
HEADER = struct.Struct('!4sIQ')
MAGIC = b'HS3W'
VERSION = 2
MAX_CONTROL = 16384
MAX_BODY = crypto.MAX_ENVELOPE_BYTES
OPERATIONS = ('write', 'read', 'observe_version', 'observe_bucket')
PRE_CODES = frozenset(('AP00_WORKER_RUNTIME_UNSUPPORTED', 'AP00_WORKER_PROTOCOL_INVALID',
                       'AP00_S3_DEPENDENCY_UNAVAILABLE', 'AP00_S3_CLIENT_INVALID'))
REJECTION_CODES = frozenset('AP00_S3_' + suffix for suffix in (
    'FORBIDDEN', 'EXACT_VERSION_UNAVAILABLE', 'CONDITIONAL_RACE', 'CREATE_ONLY_PRECONDITION',
    'TARGET_MISMATCH', 'REQUEST_REJECTED', 'TARGET_OR_CLIENT_MISMATCH', 'ADDRESS_INVALID',
    'RETENTION_INVALID', 'RETENTION_TOO_SHORT', 'RESPONSE_INVALID', 'READ_UNAVAILABLE',
    'TLS_FAILURE', 'STREAM_CLOSE_FAILED', 'EXACT_RESPONSE_INVALID', 'RETENTION_MISMATCH',
    'BUCKET_CONFIGURATION_MISMATCH'))
UNKNOWN_CODES = frozenset('AP00_WRITE_UNKNOWN_' + suffix for suffix in (
    'HTTP', 'NETWORK', 'TLS', 'RESPONSE', 'REGION_MISMATCH', 'VERSION_ID', 'WORKER'))


class TransportError(ValueError):
    """Fixed safe code only."""


class WriteUnknown(TransportError):
    """Commit cannot be disproved; no retry/reconciliation is authorized."""
    outcome = 'UNKNOWN'


def invalid():
    raise TransportError('AP00_WORKER_PROTOCOL_INVALID') from None


def date(value):
    if (not isinstance(value, datetime) or value.tzinfo is None
            or value.utcoffset() != timedelta(0) or value.microsecond):
        raise TransportError('AP00_S3_RETENTION_INVALID')
    return value.strftime('%Y-%m-%dT%H:%M:%SZ')


def parse_date(value):
    return datetime.fromtimestamp(S.timestamp(value), timezone.utc)


def fields(value, names):
    if type(value) is not dict or set(value) != set(names):
        invalid()


def credentials(value):
    fields(value, ('access_key', 'secret_key', 'session_token'))
    for name, val in value.items():
        if name == 'session_token' and val is None:
            continue
        if type(val) is not str or not 1 <= len(val) <= 4096 or not val.isascii() or any(ord(c) < 33 or ord(c) > 126 for c in val):
            invalid()


def request_control(control):
    try:
        fields(control, ('version', 'kind', 'operation', 'target', 'target_sha256', 'credentials', 'arguments'))
        if type(control['version']) is not int or control['version'] != VERSION or control['kind'] != 'request':
            invalid()
        op = control['operation']
        if type(op) is not str or op not in OPERATIONS:
            invalid()
        provisioning.target(dr.canonical(control['target']), control['target_sha256'])
        credentials(control['credentials'])
        shape = {'write': {'key': 'key', 'custody_version_sha256': 'sha', 'retain_until': 'time'},
                 'read': {'key': 'key', 'version_id': 'object-version'},
                 'observe_version': {'key': 'key', 'version_id': 'object-version', 'required_until': 'time'},
                 'observe_bucket': {}}[op]
        S.check(control['arguments'], shape)
        return control
    except Exception:
        invalid()


def request(control, body):
    try:
        request_control(control)
        if type(body) is not bytes:
            invalid()
        if control['operation'] == 'write':
            arg = control['arguments']
            crypto.inspect_envelope(body, dict(target_sha256=control['target_sha256'],
                manifest_sha256=arg['key'][8:-4], object_key=arg['key'],
                custody_version_sha256=arg['custody_version_sha256']))
        elif body:
            invalid()
        return control
    except Exception:
        invalid()


def response(control, body, operation, runtime_version):
    try:
        fields(control, ('version', 'kind', 'operation', 'result', 'code', 'value', 'runtime'))
        if type(control['version']) is not int or control['version'] != VERSION or control['kind'] != 'response':
            invalid()
        op, result, code = control['operation'], control['result'], control['code']
        if op != operation and not (op is None and result == 'REJECTED' and code in PRE_CODES):
            invalid()
        if control['runtime'] != runtime_version:
            # Unsupported runtimes return no arbitrary implementation/version text.
            if not (control['runtime'] is None and result == 'REJECTED'
                    and code in ('AP00_WORKER_RUNTIME_UNSUPPORTED', 'AP00_WORKER_PROTOCOL_INVALID')):
                invalid()
        if type(body) is not bytes:
            invalid()
        value = control['value']
        if result == 'REJECTED':
            if code not in PRE_CODES | REJECTION_CODES or value is not None or body:
                invalid()
        elif result == 'UNKNOWN':
            if op != 'write' or code not in UNKNOWN_CODES or value is not None or body:
                invalid()
        elif result == 'SUCCESS':
            if code != 'OK' or op != operation or control['runtime'] != runtime_version:
                invalid()
            if op == 'write':
                S.check(value, {'version_id': 'object-version'})
            elif op == 'read':
                S.check(value, {'version_id': 'object-version', 'size': 'positive'})
                if value['size'] != len(body) or not 0 < len(body) <= MAX_BODY:
                    invalid()
            elif op == 'observe_version':
                S.check(value, {'requested_version_id': 'object-version',
                                'mode': S.enum('COMPLIANCE'), 'retained_until': 'time'})
            elif op == 'observe_bucket':
                S.check(value, {'versioning': S.enum('ENABLED'), 'object_lock': S.enum('ENABLED'),
                                'mode': S.enum('COMPLIANCE'), 'default_retention_days': 'positive'})
            else:
                invalid()
            if op != 'read' and body:
                invalid()
        else:
            invalid()
        return control
    except Exception:
        invalid()


def header(raw, max_body):
    try:
        magic, control_size, body_size = HEADER.unpack(raw)
        if magic != MAGIC or not 0 < control_size <= MAX_CONTROL or not 0 <= body_size <= max_body:
            invalid()
        return control_size, body_size
    except Exception:
        invalid()


def read_exact(stream, size):
    # Never call an unlimited read, including while detecting trailing data.
    chunks = bytearray()
    while len(chunks) < size:
        part = stream.read(min(65536, size - len(chunks)))
        if not part:
            invalid()
        chunks.extend(part)
    return bytes(chunks)


def read_frame(stream, max_body, *, request_input=False):
    control_size, body_size = header(read_exact(stream, HEADER.size), max_body)
    raw = read_exact(stream, control_size)
    try:
        control = dr.database.strict_json(raw)
        if dr.canonical(control) != raw:
            invalid()
    except Exception:
        invalid()
    if request_input:
        request_control(control)
        if control['operation'] != 'write' and body_size != 0:
            invalid()
    body = read_exact(stream, body_size)
    if stream.read(1):
        invalid()
    return control, body


def decode(raw, max_body):
    return read_frame(io.BytesIO(raw), max_body)


def encode(control, body=b''):
    try:
        raw = dr.canonical(control)
        if not 0 < len(raw) <= MAX_CONTROL or type(body) is not bytes or len(body) > MAX_BODY:
            invalid()
        return HEADER.pack(MAGIC, len(raw), len(body)) + raw + body
    except Exception:
        invalid()
