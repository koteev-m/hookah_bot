#!/usr/bin/env python3
"""One authenticated SSH operation; closed framing, no portable PASS documents.

Authority acquisition stays on the attended verifier. This module never constructs
Trust from a wire message and never treats a path/environment as authentication.
The server-selected launcher supplies the already authenticated Session.
"""
from dataclasses import dataclass
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import select
import struct
import stat
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
MAX_FRAME = 64 * 1024
MAX_SOURCE = 2 * 1024 * 1024
ROUND_SECONDS = 300
RESULT_SECONDS = 5
PREPARE_SECONDS = 30
EXPIRY_KEYS = frozenset(('checkpoint', 'ongoing_observed', 'monitor', 'authorization',
    'response_authority', 'cadence', 'age_state', 'custody_retrieval', 'rpo', 'anchor',
    'clock', 'source_observations'))
GENESIS_EXPIRY_KEYS = frozenset(('anchor', 'clock', 'catalogue', 'authority', 'inventory', 'disposition'))
GENESIS_TUPLE = ('TARGET_BIND', 'LEGACY_GENESIS', 'bind-legacy-target')


def is_genesis(value):
    return tuple(value.get(k) for k in ('kind', 'name', 'action')) == GENESIS_TUPLE


IDENTITY_KEYS = frozenset(('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'))
OPEN_KEYS = frozenset(('version', 'type', 'session_id', 'anchor_sha256', 'anchor_generation',
    'principal_fingerprint', 'host_fingerprint', 'identity', 'target', 'request_sha256',
    'manifest_sha256', 'manifest_size', 'source_tree', 'tooling_sha256', 'runtime', 'worker_args',
    'environment', 'init_request', 'init_completion', 'payload', 'mode'))
CHALLENGE_KEYS = frozenset(('version', 'type', 'session_id', 'nonce', 'sequence', 'phase',
    'operation_id', 'identity', 'target', 'timeout', 'history_sha256', 'anchor_sha256',
    'anchor_generation', 'manifest_sha256', 'source_tree', 'tooling_sha256', 'runtime',
    'principal_fingerprint', 'host_fingerprint', 'native_history'))
ECHO_KEYS = frozenset(('version', 'type', 'session_id', 'nonce', 'sequence', 'phase', 'challenge_sha256'))
PASS_KEYS = ECHO_KEYS | frozenset(('decision', 'barrier', 'qualification_sha256',
    'catalogue_head_sha256', 'catalogue_sequence', 'revocation_generation', 'pins_sha256',
    'native_stage7_sha256', 'native_manifest_sha256', 'expiry'))
REFUSE_KEYS = ECHO_KEYS | frozenset(('decision', 'reason'))

ENV_KEYS = frozenset('V126_INTERNAL_REMOTE_' + name for name in (
    'ACTION', 'AUTHORIZATION_GATE', 'AUTHORIZATION_HASH', 'BASELINE_ADMISSION_SOURCE_SHA256',
    'BASELINE_CADDY_SHA256', 'BASELINE_COMPOSE_SOURCE_SHA256', 'BASELINE_DATABASE_URL_SHA256',
    'BASELINE_ENV_SHA256', 'BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256', 'BASELINE_MAINTENANCE_IDENTITIES_SHA256',
    'CADDY_ACTIVATION_SHA256', 'CADDY_CANDIDATE_SHA256', 'CADDY_DIFF_SHA256', 'CADDY_ORIGINAL_SHA256',
    'DATABASE_TARGET_IDENTITY_SHA256', 'ENVELOPE_VALIDATED', 'INTENT_HASH', 'MAINTENANCE_OFF_SHA256',
    'MAINTENANCE_SMOKE_SHA256', 'MODE', 'OPERATION_KIND', 'OPERATION_NAME', 'PREDECESSOR_HASH',
    'PREDECESSOR_STAGE', 'RELEASE_SHA', 'RUN_ID', 'SCRIPT_SHA256', 'STAGING_PATH', 'V126_IMAGE_ID'))
WRITE_INIT_KEYS = frozenset(('version', 'type', 'session_id', 'nonce', 'sequence', 'operation_id',
    'challenge_sha256', 'request_sha256', 'manifest_sha256', 'manifest_size', 'request'))


class ProtocolError(ValueError):
    pass


def refuse(code):
    raise ProtocolError(code)


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def exact(value, keys):
    if type(value) is not dict or set(value) != set(keys):
        refuse('POLICY_B_FRAME_SCHEMA')


def sha(value, size=64):
    if type(value) is not str or not re.fullmatch('[0-9a-f]{' + str(size) + '}', value):
        refuse('POLICY_B_DIGEST')


def uint(value):
    if type(value) is not int or value < 0:
        refuse('POLICY_B_INTEGER')


def fingerprint(value):
    if type(value) is not str or not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', value):
        refuse('POLICY_B_FINGERPRINT')


def identity(value):
    exact(value, IDENTITY_KEYS)
    if not re.fullmatch(r'[a-z0-9][a-z0-9._-]{5,63}', value['run_id']):
        refuse('POLICY_B_RUN_ID')
    sha(value['release_sha'], 40)
    sha(value['script_sha256'])
    sha(value['intent_sha256'])
    if any(type(value[key]) is not str or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', value[key])
           for key in ('kind', 'name', 'action')):
        refuse('POLICY_B_OPERATION_IDENTITY')


def validate_open(value):
    genesis = is_genesis(value.get('identity', {}))
    exact(value, OPEN_KEYS | ({'genesis_request'} if genesis else set()))
    if value['version'] != 1 or type(value['version']) is not int or value['type'] != 'OPEN':
        refuse('POLICY_B_PROTOCOL_VERSION')
    if (value['mode'] not in ('EXECUTE', 'INIT_READBACK', 'GENESIS_READBACK')
            or (value['mode'] == 'INIT_READBACK' and value['identity'].get('kind') != 'INIT')
            or (value['mode'] == 'GENESIS_READBACK' and not genesis)):
        refuse('POLICY_B_OPERATION_MODE')
    identity(value['identity'])
    for key in ('session_id', 'anchor_sha256', 'request_sha256', 'tooling_sha256'):
        sha(value[key])
    sha(value['source_tree'], 40)
    uint(value['anchor_generation'])
    uint(value['manifest_size'])
    if value['manifest_sha256'] is not None:
        sha(value['manifest_sha256'])
    if ((value['identity']['kind'] == 'INIT' or genesis) != (value['manifest_sha256'] is not None)
            or value['manifest_size'] > MAX_FRAME):
        refuse('POLICY_B_MANIFEST_BINDING')
    if value['manifest_sha256'] is None and value['manifest_size'] != 0:
        refuse('POLICY_B_MANIFEST_BINDING')
    fingerprint(value['principal_fingerprint'])
    fingerprint(value['host_fingerprint'])
    if (type(value['target']) is not str or not value['target'].startswith('/')
            or '\x00' in value['target'] or str(Path(value['target'])) != value['target']
            or '..' in Path(value['target']).parts):
        refuse('POLICY_B_TARGET_BINDING')
    if type(value['runtime']) is not str or not re.fullmatch(r'\d+\.\d+\.\d+', value['runtime']):
        refuse('POLICY_B_RUNTIME_BINDING')
    if (type(value['worker_args']) is not list or len(value['worker_args']) > 64
            or any(type(item) is not str or len(item.encode()) > 8192 or '\x00' in item for item in value['worker_args'])):
        refuse('POLICY_B_WORKER_ARGUMENTS')
    if (type(value['environment']) is not dict or not set(value['environment']) <= ENV_KEYS
            or any(type(item) is not str or len(item.encode()) > 8192 or '\x00' in item for item in value['environment'].values())):
        refuse('POLICY_B_ENVIRONMENT_SCOPE')
    for key in ('init_request', 'init_completion'):
        if value[key] is not None and type(value[key]) is not dict:
            refuse('POLICY_B_INIT_SCHEMA')
    if value['identity']['kind'] == 'INIT':
        if value['init_request'] is None or value['init_completion'] is not None or value['payload'] is not None:
            refuse('POLICY_B_INIT_SCHEMA')
    elif value['init_request'] is not None:
        refuse('POLICY_B_INIT_SCHEMA')
    if genesis:
        proposal = value['genesis_request']
        if (type(proposal) is not dict or digest(proposal) != value['request_sha256']
                or value['request_sha256'] != value['identity']['intent_sha256']
                or value['manifest_sha256'] != value['request_sha256']
                or value['manifest_size'] != len(canonical(proposal))
                or value['init_completion'] is not None or value['worker_args'] or value['environment']):
            refuse('POLICY_B_GENESIS_REQUEST_BINDING')
    upload_action = value['identity']['action'] in ('image-upload', 'preflight-upload')
    if (upload_action != (value['payload'] is not None)
            or (upload_action and value['identity']['kind'] != 'STAGE')):
        refuse('POLICY_B_PAYLOAD_BINDING')
    if value['payload'] is not None:
        exact(value['payload'], ('size', 'sha256'))
        uint(value['payload']['size'])
        sha(value['payload']['sha256'])
        maximum = 8*1024**3 if value['identity']['action'] == 'image-upload' else 1024**2
        if value['identity']['action'] not in ('image-upload', 'preflight-upload') or not 0 < value['payload']['size'] <= maximum:
            refuse('POLICY_B_PAYLOAD_BINDING')
    return value


def validate_challenge(value):
    genesis = is_genesis(value.get('identity', {}))
    exact(value, CHALLENGE_KEYS | ({'genesis_mode', 'genesis_completion_sha256'} if genesis else set()))
    if type(value['version']) is not int or value['version'] != 1 or value['type'] != 'CHALLENGE':
        refuse('POLICY_B_PROTOCOL_VERSION')
    identity(value['identity'])
    if genesis:
        if value['genesis_mode'] == 'GENESIS_READBACK':
            sha(value['genesis_completion_sha256'])
        elif value['genesis_mode'] != 'EXECUTE' or value['genesis_completion_sha256'] is not None:
            refuse('POLICY_B_GENESIS_COMPLETION_BINDING')
    for key in ('session_id', 'nonce', 'operation_id', 'history_sha256', 'anchor_sha256', 'tooling_sha256'):
        sha(value[key])
    if value['operation_id'] != digest(value['identity']):
        refuse('POLICY_B_OPERATION_BINDING')
    if (type(value['sequence']) is not int or value['sequence'] not in (1, 2)
            or value['phase'] != ('EARLY' if value['sequence'] == 1 else 'LATE')):
        refuse('POLICY_B_ROUND')
    if type(value['timeout']) is not int or value['timeout'] not in (300, 600):
        refuse('POLICY_B_ACTION_BOUND')
    # Reuse closed scalar checks without attributing any authority to them.
    scalar_open = dict(version=1, type='OPEN', identity=value['identity'], target=value['target'],
        session_id=value['session_id'], anchor_sha256=value['anchor_sha256'], anchor_generation=value['anchor_generation'],
        principal_fingerprint=value['principal_fingerprint'], host_fingerprint=value['host_fingerprint'],
        request_sha256='0'*64, manifest_sha256=value['manifest_sha256'],
        manifest_size=1 if value['manifest_sha256'] is not None else 0, source_tree=value['source_tree'],
        tooling_sha256=value['tooling_sha256'], runtime=value['runtime'], worker_args=[], environment={},
        init_request={} if value['identity']['kind'] == 'INIT' else None, init_completion=None,
        payload={'size': 1, 'sha256': '0'*64} if value['identity']['action'] in ('image-upload', 'preflight-upload') else None,
        mode=value['genesis_mode'] if genesis else 'EXECUTE')
    if genesis:
        # Proposal bytes are checked at OPEN and independently on V; the
        # challenge carries their exact intent hash, never a second proposal.
        scalar_open['genesis_request'] = {}
        scalar_open['manifest_sha256'] = scalar_open['request_sha256'] = digest({})
        scalar_open['manifest_size'] = len(canonical({}))
        scalar_open['identity'] = dict(value['identity'], intent_sha256=digest({}))
    validate_open(scalar_open)
    history = value['native_history']
    r0 = genesis or value['identity']['kind'] == 'INIT' or value['identity']['name'] == 'BASELINE_VERIFIED'
    if type(history) is not list or len(history) > 32 or (r0 and history) or (not r0 and not history):
        refuse('POLICY_B_NATIVE_HISTORY')
    for row in history:
        exact(row, ('identity', 'request_sha256', 'result_sha256', 'log_sha256', 'completed_at', 'artifacts'))
        identity(row['identity'])
        for key in ('request_sha256', 'result_sha256', 'log_sha256'):
            sha(row[key])
        if type(row['completed_at']) is not str or len(row['completed_at']) > 40 or type(row['artifacts']) is not list:
            refuse('POLICY_B_NATIVE_HISTORY')
        for artifact in row['artifacts']:
            exact(artifact, ('name', 'sha256'))
            sha(artifact['sha256'])
            if type(artifact['name']) is not str or not re.fullmatch('[a-z0-9-]{1,128}', artifact['name']):
                refuse('POLICY_B_NATIVE_HISTORY')
    return value


def result_echo(challenge):
    return dict(version=1, type='RESULT', session_id=challenge['session_id'], nonce=challenge['nonce'],
                sequence=challenge['sequence'], phase=challenge['phase'], challenge_sha256=digest(challenge))


def refusal_result(challenge):
    return dict(result_echo(challenge), decision='REFUSE', reason='POLICY_B_REFUSED')


def validate_result(value, challenge, *, minimum_revocation=0):
    genesis = is_genesis(challenge['identity'])
    exact(value, REFUSE_KEYS if value.get('decision') == 'REFUSE' else PASS_KEYS | ({'legacy_inventory'} if genesis else set()))
    expected = result_echo(challenge)
    if any(value[key] != expected[key] or type(value[key]) is not type(expected[key]) for key in ECHO_KEYS):
        refuse('POLICY_B_RESULT_SESSION_OR_REPLAY')
    if value['decision'] == 'REFUSE':
        if value['reason'] != 'POLICY_B_REFUSED':
            refuse('POLICY_B_REFUSAL_SCHEMA')
        refuse('POLICY_B_VERIFIER_REFUSED')
    if value['decision'] != 'PASS':
        refuse('POLICY_B_RESULT_DECISION')
    r0 = challenge['identity']['kind'] == 'INIT' or challenge['identity']['name'] == 'BASELINE_VERIFIED'
    if value['barrier'] != (('LEGACY_GENESIS_COPY' if challenge['genesis_mode'] == 'GENESIS_READBACK' else 'LEGACY_GENESIS') if genesis else 'R0' if r0 else 'Q'):
        refuse('POLICY_B_RESULT_PURPOSE')
    for key in ('qualification_sha256', 'catalogue_head_sha256', 'pins_sha256'):
        sha(value[key])
    for key in ('catalogue_sequence', 'revocation_generation'):
        uint(value[key])
    uint(minimum_revocation)
    if value['revocation_generation'] < minimum_revocation:
        refuse('POLICY_B_REVOCATION_ROLLBACK')
    for key in ('native_stage7_sha256', 'native_manifest_sha256'):
        if r0 or genesis:
            if value[key] is not None:
                refuse('POLICY_B_R0_IS_NOT_Q')
        else:
            sha(value[key])
    if genesis:
        if (type(value['legacy_inventory']) is not dict
                or len(canonical(value['legacy_inventory'])) > 48 * 1024
                or digest(value['legacy_inventory']) != value['qualification_sha256']):
            refuse('POLICY_B_GENESIS_INVENTORY_BINDING')
    exact(value['expiry'], GENESIS_EXPIRY_KEYS if genesis else EXPIRY_KEYS)
    if any(type(number) not in (float, int) or not math.isfinite(number) or number <= 0
           for number in value['expiry'].values()):
        refuse('POLICY_B_EXPIRY_VECTOR')
    return value


def _pairs(items):
    value = {}
    for key, item in items:
        if key in value:
            refuse('POLICY_B_DUPLICATE_KEY')
        value[key] = item
    return value


class FramedChannel:
    """Bounded duplex descriptors; never inherited by a protected child."""
    def __init__(self, read_fd, write_fd, *, clock=time.monotonic):
        self.read_fd, self.write_fd, self.clock = read_fd, write_fd, clock
        for fd in set((read_fd, write_fd)):
            os.set_inheritable(fd, False)
            os.set_blocking(fd, False)

    def _io(self, fd, size=None, data=None, *, deadline):
        output = bytearray()
        pending = memoryview(data) if data is not None else None
        while pending if pending is not None else len(output) < size:
            remaining = deadline - self.clock()
            if remaining <= 0:
                refuse('POLICY_B_CHANNEL_TIMEOUT')
            readers, writers = ([] if pending is not None else [fd]), ([fd] if pending is not None else [])
            ready_r, ready_w, _ = select.select(readers, writers, [], remaining)
            if not ready_r and not ready_w:
                refuse('POLICY_B_CHANNEL_TIMEOUT')
            if pending is not None:
                try:
                    written = os.write(fd, pending[:65536])
                except (BlockingIOError, InterruptedError):
                    continue
                if written <= 0:
                    refuse('POLICY_B_CHANNEL_EOF')
                pending = pending[written:]
            else:
                try:
                    block = os.read(fd, min(size - len(output), 65536))
                except (BlockingIOError, InterruptedError):
                    continue
                if not block:
                    refuse('POLICY_B_CHANNEL_EOF')
                output.extend(block)
        return bytes(output)

    def require_idle_peer(self):
        # A known disconnect or unsolicited input before dispatch is a refusal.
        # Once a leaf starts, the supervisor retains its existing reaping rules.
        if select.select([self.read_fd], [], [], 0)[0]:
            try:
                pending = os.read(self.read_fd, 1)
            except BlockingIOError:
                return
            refuse('POLICY_B_UNSOLICITED_FRAME' if pending else 'POLICY_B_CHANNEL_EOF')

    def send_bytes(self, raw, *, deadline, maximum=MAX_FRAME):
        if type(raw) is not bytes or not 0 < len(raw) <= maximum:
            refuse('POLICY_B_FRAME_SIZE')
        self._io(self.write_fd, data=struct.pack('!I', len(raw)) + raw, deadline=deadline)

    def recv_bytes(self, *, deadline, maximum=MAX_FRAME):
        size, = struct.unpack('!I', self._io(self.read_fd, size=4, deadline=deadline))
        if not 0 < size <= maximum:
            refuse('POLICY_B_FRAME_SIZE')
        return self._io(self.read_fd, size=size, deadline=deadline)

    def send(self, value, *, deadline):
        self.send_bytes(canonical(value), deadline=deadline)

    def recv(self, *, deadline):
        raw = self.recv_bytes(deadline=deadline)
        try:
            value = json.loads(raw, object_pairs_hook=_pairs,
                               parse_constant=lambda _: refuse('POLICY_B_NONFINITE_JSON'))
        except (UnicodeError, json.JSONDecodeError):
            refuse('POLICY_B_FRAME_JSON')
        if type(value) is not dict or raw != canonical(value):
            refuse('POLICY_B_FRAME_CANONICAL')
        return value


@dataclass
class Session:
    channel: FramedChannel
    request: dict
    source: bytes
    principal: dict
    source_validator: object
    payload_file: object = None

    def __post_init__(self):
        self._request_binding = digest(self.request)
        self._principal_binding = digest(self.principal)

    def verify(self):
        if digest(self.request) != self._request_binding or digest(self.principal) != self._principal_binding:
            refuse('POLICY_B_SESSION_BINDINGS_CHANGED')
        validate_open(self.request)
        uint(self.principal.get('revocation_generation'))
        if hashlib.sha256(self.source).hexdigest() != self.request['identity']['script_sha256']:
            refuse('POLICY_B_SOURCE_BINDING')
        if any(self.request[key] != self.principal[key] for key in
               ('principal_fingerprint', 'host_fingerprint', 'anchor_sha256', 'anchor_generation')):
            refuse('POLICY_B_AUTHENTICATED_BINDING')
        self.source_validator()


    def receive_payload(self, operation, target, lockfd, require_lock,
                        history_snapshot, expected_history, *, timeout=900, check_current=None):
        """Called by the supervisor only after its durable intent, under its lock.

        The launcher deliberately leaves binary input unread. A failed transfer
        therefore belongs to the existing in-flight operation and stays UNKNOWN.
        """
        if (operation != self.request['identity'] or str(target) != self.request['target']
                or self.request['payload'] is None or getattr(self, '_payload_started', False)
                or type(timeout) is not int or timeout != 900):
            refuse('POLICY_B_UPLOAD_DISPATCH_BINDING')
        def live():
            require_lock(target, lockfd)
            if check_current is not None:
                check_current()
        def guard():
            self.verify()
            live()
            if history_snapshot() != expected_history:
                refuse('POLICY_B_UPLOAD_HISTORY_CHANGED')
        guard()
        self._payload_started = True
        payload = self.request['payload']
        deadline = time.monotonic() + timeout
        self.channel.send(dict(version=1, type='PAYLOAD_REQUEST', session_id=self.request['session_id'],
            operation_id=digest(operation), **payload), deadline=min(deadline, time.monotonic() + PREPARE_SECONDS))
        output = tempfile.TemporaryFile()
        received, checksum = 0, hashlib.sha256()
        try:
            while received < payload['size']:
                block = self.channel.recv_bytes(deadline=deadline)
                received += len(block)
                if received > payload['size']:
                    refuse('POLICY_B_UPLOAD_SIZE')
                # Check the continuous lock/cancellation cheaply per block;
                # complete source/history replay brackets the bounded transfer.
                live()
                checksum.update(block)
                output.write(block)
            if checksum.hexdigest() != payload['sha256']:
                refuse('POLICY_B_UPLOAD_HASH')
            output.flush()
            output.seek(0)
            guard()
            self.payload_file = output
            return output
        except BaseException:
            output.close()
            raise


class RemoteGate:
    """Only the authenticated launcher constructs this gate for a real operation."""
    def __init__(self, session, history_snapshot, require_lock, native_history=None):
        if type(session) is not Session:
            refuse('POLICY_B_AUTHENTICATED_SESSION_REQUIRED')
        self.session, self.history_snapshot, self.require_lock = session, history_snapshot, require_lock
        self.native_history = native_history
        self.round = 0
        self.pending = None
        self.consumed = False
        self.nonces = set()
        self.revocation_floor = session.principal.get('revocation_generation')
        session.verify()

    def _bindings(self, operation, target, lockfd, timeout):
        request = self.session.request
        if operation != request['identity'] or str(target) != request['target']:
            refuse('POLICY_B_DISPATCH_BINDING')
        expected = 600 if operation['action'] == 'final-v125-preflight' else 300
        if type(timeout) is not int or timeout != expected:
            refuse('POLICY_B_ACTION_BOUND')
        self.session.verify()
        if is_genesis(operation) and request['mode'] == 'EXECUTE' and lockfd is None:
            if self.round != 0:
                refuse('POLICY_B_GENESIS_LOCK_REQUIRED')
            self._absent_genesis_target(target)
        else:
            self.require_lock(target, lockfd)

    def _absent_genesis_target(self, target):
        expected = self.session.request['genesis_request']['target']
        path = Path(target)
        info = path.lstat()
        if (not stat.S_ISDIR(info.st_mode) or path.resolve(strict=True) != path
                or info.st_mode & 0o022 or info.st_uid != os.geteuid()
                or (info.st_dev, info.st_ino, info.st_uid) !=
                   (expected['device'], expected['inode'], expected['uid'])
                or (path / '.v126-target-operations').exists()
                or (path / '.v126-target-operations').is_symlink()):
            refuse('POLICY_B_GENESIS_TARGET_NOT_ABSENT')

    def check(self, operation, target, lockfd, timeout):
        self._bindings(operation, target, lockfd, timeout)
        if self.round >= 2 or self.consumed:
            refuse('POLICY_B_CHALLENGE_REPLAY')
        self.round += 1
        request = self.session.request
        nonce = os.urandom(32).hex()
        if nonce in self.nonces:
            refuse('POLICY_B_NONCE_REUSE')
        self.nonces.add(nonce)
        challenge = {key: request[key] for key in ('session_id', 'anchor_sha256', 'anchor_generation',
            'principal_fingerprint', 'host_fingerprint', 'identity', 'target', 'manifest_sha256',
            'source_tree', 'tooling_sha256', 'runtime')}
        challenge.update(version=1, type='CHALLENGE', nonce=nonce, sequence=self.round,
            phase='EARLY' if self.round == 1 else 'LATE', operation_id=digest(operation), timeout=timeout,
            history_sha256=self.history_snapshot(),
            native_history=[] if is_genesis(operation) or operation['kind'] == 'INIT' or operation['name'] == 'BASELINE_VERIFIED'
                else (self.native_history() if self.native_history is not None else []))
        if is_genesis(operation):
            challenge['genesis_mode'] = request['mode']
            challenge['genesis_completion_sha256'] = (hashlib.sha256((Path(target) / '.v126-target-operations/genesis.result.json').read_bytes()).hexdigest() if request['mode'] == 'GENESIS_READBACK' else None)
        validate_challenge(challenge)
        sent = time.monotonic()
        self.session.channel.send(challenge, deadline=sent + PREPARE_SECONDS)
        answer = self.session.channel.recv(deadline=sent + ROUND_SECONDS)
        received = time.monotonic()
        validate_result(answer, challenge, minimum_revocation=self.revocation_floor)
        self.revocation_floor = answer['revocation_generation']
        self.pending = dict(challenge=challenge, result=answer, sent=sent, received=received)
        self._fresh()
        admitted = dict(barrier=answer['barrier'], qualification_sha256=answer['qualification_sha256'], operational=True)
        if is_genesis(operation):
            if answer['qualification_sha256'] != request['genesis_request']['inventory_sha256']:
                refuse('POLICY_B_GENESIS_INVENTORY_CHANGED')
            admitted.update({key: answer[key] for key in ('legacy_inventory', 'pins_sha256', 'catalogue_head_sha256', 'revocation_generation')})
        return admitted

    def _fresh(self, *, consumed=False):
        if self.pending is None or self.consumed != consumed:
            refuse('POLICY_B_RESULT_ALREADY_CONSUMED')
        now = time.monotonic()
        elapsed = now - self.pending['sent']
        received_age = now - self.pending['received']
        if (elapsed < 0 or received_age < 0 or received_age > RESULT_SECONDS
                or min(self.pending['result']['expiry'].values()) - elapsed <= 0):
            refuse('POLICY_B_DISPATCH_EXPIRED')

    def before_dispatch(self, operation, target, lockfd, timeout):
        self._bindings(operation, target, lockfd, timeout)
        if self.round != 2 or self.pending['challenge']['phase'] != 'LATE':
            refuse('POLICY_B_LATE_REQUIRED')
        if self.history_snapshot() != self.pending['challenge']['history_sha256']:
            refuse('POLICY_B_HISTORY_CHANGED')
        self.require_lock(target, lockfd)
        self._fresh()
        self.session.channel.require_idle_peer()
        self.consumed = True

    def before_create(self, operation, target, lockfd, timeout):
        if (not is_genesis(operation) or lockfd is not None or self.round != 1
                or self.session.request['mode'] != 'EXECUTE'
                or operation != self.session.request['identity'] or timeout != 300):
            refuse('POLICY_B_GENESIS_EARLY_REQUIRED')
        self.session.verify()
        self._absent_genesis_target(target)
        self._fresh()
        self.session.channel.require_idle_peer()

    def before_write(self, operation, target, lockfd, timeout):
        if not is_genesis(operation) or self.round != 2 or not self.consumed:
            refuse('POLICY_B_GENESIS_LATE_REQUIRED')
        self._bindings(operation, target, lockfd, timeout)
        self._fresh(consumed=True)
        self.session.channel.require_idle_peer()

    def write_init(self, request):
        if not self.consumed or self.session.request['identity']['kind'] != 'INIT' or getattr(self, '_write_sent', False):
            refuse('POLICY_B_INIT_WRITE_NOT_AUTHORIZED')
        if request != self.session.request['init_request'] or digest(request) != self.session.request['request_sha256']:
            refuse('POLICY_B_INIT_REQUEST_CHANGED')
        self._write_sent = True
        challenge = self.pending['challenge']
        message = dict(version=1, type='WRITE_INIT', session_id=challenge['session_id'], nonce=challenge['nonce'],
            sequence=2, operation_id=challenge['operation_id'], challenge_sha256=digest(challenge),
            request_sha256=digest(request), manifest_sha256=self.session.request['manifest_sha256'],
            manifest_size=self.session.request['manifest_size'], request=request)
        self.session.channel.send(message, deadline=time.monotonic() + RESULT_SECONDS)
        answer = self.session.channel.recv(deadline=time.monotonic() + 300)
        exact(answer, ('version', 'type', 'session_id', 'nonce', 'operation_id', 'request_sha256', 'attestation'))
        if (type(answer['version']) is not int or answer['version'] != 1 or answer['type'] != 'LOCAL_WRITTEN'
                or any(answer[key] != message[key] for key in ('session_id', 'nonce', 'operation_id', 'request_sha256'))
                or type(answer['attestation']) is not dict):
            refuse('POLICY_B_LOCAL_WRITTEN_BINDING')
        return answer['attestation']

    def ack_init(self, result):
        if not getattr(self, '_write_sent', False) or getattr(self, '_acked', False):
            refuse('POLICY_B_INIT_ACK_NOT_AUTHORIZED')
        self._acked = True
        challenge = self.pending['challenge']
        message = dict(version=1, type='INIT_ACK', session_id=challenge['session_id'], nonce=challenge['nonce'],
            operation_id=challenge['operation_id'], request_sha256=self.session.request['request_sha256'],
            result_sha256=digest(result), result=result)
        self.session.channel.send(message, deadline=time.monotonic() + 30)


def receive_session(channel, principal, source_validator):
    deadline = time.monotonic() + PREPARE_SECONDS
    request = validate_open(channel.recv(deadline=deadline))
    header = channel.recv(deadline=deadline)
    exact(header, ('version', 'type', 'session_id', 'size', 'sha256'))
    if (type(header['version']) is not int or header['version'] != 1 or header['type'] != 'SOURCE' or header['session_id'] != request['session_id']
            or type(header['size']) is not int or not 0 < header['size'] <= MAX_SOURCE
            or header['sha256'] != request['identity']['script_sha256']):
        refuse('POLICY_B_SOURCE_FRAME')
    source = channel.recv_bytes(deadline=deadline, maximum=MAX_SOURCE)
    if len(source) != header['size']:
        refuse('POLICY_B_SOURCE_FRAME')
    session = Session(channel, request, source, principal, source_validator)
    session.verify()
    return session


@contextmanager
def ssh_command(options):
    """Freeze the single approved plain key; never give SSH the source trust file."""
    exact(options, ('host', 'port', 'user', 'identity_file', 'known_hosts_file', 'host_fingerprint'))
    if (type(options['host']) is not str or not re.fullmatch(r'[A-Za-z0-9_.:-]+', options['host'])
            or type(options['user']) is not str or not re.fullmatch(r'[a-z_][a-z0-9_-]*', options['user'])
            or type(options['port']) is not int or not 1 <= options['port'] <= 65535):
        refuse('POLICY_B_SSH_ENDPOINT')
    fingerprint(options['host_fingerprint'])
    import base64
    fd = os.open(options['known_hosts_file'], os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= 1024 * 1024:
            refuse('POLICY_B_HOST_KEY')
        raw = os.read(fd, 1024 * 1024 + 1)
        if len(raw) != info.st_size:
            refuse('POLICY_B_HOST_KEY')
    finally:
        os.close(fd)
    host = options['host'] if options['port'] == 22 else '[' + options['host'] + ']:' + str(options['port'])
    host_names = {options['host'], '[' + options['host'] + ']:' + str(options['port'])}
    plain_types = {'ssh-ed25519', 'ssh-rsa', 'ecdsa-sha2-nistp256', 'ecdsa-sha2-nistp384', 'ecdsa-sha2-nistp521'}
    selected = set()
    for entry in raw.decode('ascii').splitlines():
        parts = entry.split()
        # This is selection, not a known_hosts parser: wildcard/list/CA/hash and
        # all unpinned keys are excluded from the material OpenSSH will receive.
        if len(parts) < 3 or parts[0] not in host_names or parts[1] not in plain_types:
            continue
        try:
            key = base64.b64decode(parts[2], validate=True)
            fp = 'SHA256:' + base64.b64encode(hashlib.sha256(key).digest()).decode().rstrip('=')
        except ValueError:
            continue
        if fp == options['host_fingerprint']:
            if len(key) < 4 or key[4:4 + int.from_bytes(key[:4], 'big')] != parts[1].encode():
                refuse('POLICY_B_HOST_KEY')
            selected.add((parts[1], base64.b64encode(key).decode()))
    if len(selected) != 1:
        refuse('POLICY_B_HOST_KEY')
    key_type, key_text = selected.pop()
    host_algorithms = 'rsa-sha2-512,rsa-sha2-256' if key_type == 'ssh-rsa' else key_type
    with tempfile.TemporaryDirectory(prefix='policy-b-pinned-host-') as directory:
        known = Path(directory) / 'known_hosts'
        with known.open('xb') as output:
            output.write((host + ' ' + key_type + ' ' + key_text + '\n').encode('ascii'))
            output.flush()
            os.fsync(output.fileno())
        known.chmod(0o400)
        yield ['/usr/bin/ssh', '-F', '/dev/null', '-T', '-a', '-x', '-p', str(options['port']),
            '-i', str(Path(options['identity_file']).resolve(strict=True)),
            '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
            '-o', 'UserKnownHostsFile=' + str(known), '-o', 'HostKeyAlgorithms=' + host_algorithms,
            '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'KnownHostsCommand=none', '-o', 'VerifyHostKeyDNS=no',
            '-o', 'UpdateHostKeys=no', '-o', 'ForwardAgent=no', '-o', 'CanonicalizeHostname=no',
            '-o', 'ClearAllForwardings=yes', '-o', 'ControlMaster=no', '-o', 'ControlPath=none',
            '-o', 'ControlPersist=no', '-o', 'RequestTTY=no', '-o', 'PermitLocalCommand=no',
            '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none', '-o', 'ConnectTimeout=10',
            options['user'] + '@' + options['host']]


def invoke_operation(ssh_options, open_request, source, verifier, *, init_writer=None,
                     completion_writer=None, payload=None, output_sink=None):
    """Foreground attended client; SSH/evidence stdin never answers console prompts."""
    validate_open(open_request)
    if (payload is None) != (open_request['payload'] is None):
        refuse('POLICY_B_UPLOAD_CHANNEL_NOT_BOUND')
    if (type(source) is not bytes or not 0 < len(source) <= MAX_SOURCE
            or hashlib.sha256(source).hexdigest() != open_request['identity']['script_sha256']
            or ssh_options['host_fingerprint'] != open_request['host_fingerprint']):
        refuse('POLICY_B_SOURCE_OR_HOST_BINDING')
    action = open_request['identity']['action']
    action_seconds = {'backup-rehearsal': 1800, 'image-load': 900, 'image-upload': 900,
                      'final-v125-preflight': 600}.get(action, 300)
    upload_seconds = 900 if open_request['payload'] is not None else 0
    # Preserve the existing stage/action cap in addition to independent
    # preparation, two admission rounds and terminal transfer budgets.
    total_seconds = (930 if open_request['identity']['kind'] == 'INIT' else
                     PREPARE_SECONDS + upload_seconds + 2*ROUND_SECONDS + action_seconds + 30)
    deadline = time.monotonic() + total_seconds
    with ssh_command(ssh_options) as command, tempfile.TemporaryFile() as stderr:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=stderr, env={key: os.environ[key] for key in ('PATH',) if key in os.environ}, close_fds=True)
        channel = FramedChannel(process.stdout.fileno(), process.stdin.fileno())
        used = set()
        written = False
        acked = False
        payload_sent = False
        output_received = False
        latest = None
        try:
            channel.send(open_request, deadline=time.monotonic() + PREPARE_SECONDS)
            channel.send(dict(version=1, type='SOURCE', session_id=open_request['session_id'],
                size=len(source), sha256=hashlib.sha256(source).hexdigest()), deadline=time.monotonic() + PREPARE_SECONDS)
            channel.send_bytes(source, deadline=time.monotonic() + PREPARE_SECONDS, maximum=MAX_SOURCE)
            while True:
                message = channel.recv(deadline=deadline)
                if message.get('type') == 'CHALLENGE':
                    validate_challenge(message)
                    for key in ('session_id', 'identity', 'target', 'anchor_sha256', 'anchor_generation',
                                'manifest_sha256', 'source_tree', 'tooling_sha256', 'runtime',
                                'principal_fingerprint', 'host_fingerprint'):
                        if message[key] != open_request[key]:
                            refuse('POLICY_B_SERVER_CHALLENGE_BINDING')
                    if is_genesis(open_request['identity']) and message['genesis_mode'] != open_request['mode']:
                        refuse('POLICY_B_SERVER_GENESIS_MODE')
                    if message['nonce'] in used or message['sequence'] != len(used) + 1:
                        refuse('POLICY_B_SERVER_CHALLENGE_REPLAY')
                    used.add(message['nonce'])
                    try:
                        result = verifier.verify(message)
                        verified_at = time.monotonic()
                        validate_result(result, message)
                    except Exception:
                        channel.send(refusal_result(message), deadline=time.monotonic() + PREPARE_SECONDS)
                        raise
                    channel.send(result, deadline=time.monotonic() + PREPARE_SECONDS)
                    latest = (message, result, verified_at)
                elif message.get('type') == 'PAYLOAD_REQUEST':
                    exact(message, ('version', 'type', 'session_id', 'operation_id', 'size', 'sha256'))
                    if (payload_sent or payload is None
                            or len(used) != (1 if action == 'preflight-upload' else 0)
                            or (latest is not None and latest[0]['phase'] != 'EARLY')
                            or type(message['version']) is not int or message['version'] != 1
                            or message['session_id'] != open_request['session_id']
                            or message['operation_id'] != digest(open_request['identity'])
                            or {key: message[key] for key in ('size', 'sha256')} != open_request['payload']):
                        refuse('POLICY_B_UPLOAD_REQUEST')
                    payload_sent = True
                    fd = os.dup(payload) if type(payload) is int else os.open(payload, os.O_RDONLY | os.O_NOFOLLOW)
                    try:
                        checksum, total = hashlib.sha256(), 0
                        payload_deadline = time.monotonic() + 900
                        while total < message['size']:
                            block = os.read(fd, min(MAX_FRAME, message['size'] - total))
                            if not block:
                                refuse('POLICY_B_UPLOAD_EOF')
                            total += len(block)
                            checksum.update(block)
                            channel.send_bytes(block, deadline=payload_deadline)
                        if os.read(fd, 1) or checksum.hexdigest() != message['sha256']:
                            refuse('POLICY_B_UPLOAD_HASH')
                    finally:
                        os.close(fd)
                elif message.get('type') == 'WRITE_INIT':
                    if open_request['identity']['kind'] != 'INIT' or init_writer is None or written or len(used) != 2:
                        refuse('POLICY_B_LOCAL_WRITER_NOT_AUTHORIZED')
                    exact(message, WRITE_INIT_KEYS)
                    challenge, answer, sent = latest
                    elapsed = time.monotonic() - sent
                    if (type(message['version']) is not int or message['version'] != 1 or type(message['sequence']) is not int or message['sequence'] != 2 or challenge['phase'] != 'LATE'
                            or any(message[key] != challenge[key] for key in ('session_id', 'nonce', 'operation_id'))
                            or message['challenge_sha256'] != digest(challenge)
                            or any(message[key] != open_request[key] for key in ('request_sha256', 'manifest_sha256', 'manifest_size'))
                            or message['request'] != open_request['init_request']
                            or elapsed < 0 or elapsed > RESULT_SECONDS or min(answer['expiry'].values()) - elapsed <= 0):
                        refuse('POLICY_B_LOCAL_WRITER_EXPIRED_OR_CHANGED')
                    written = True
                    attestation = init_writer(message)
                    channel.send(dict(version=1, type='LOCAL_WRITTEN', session_id=message['session_id'],
                        nonce=message['nonce'], operation_id=message['operation_id'], request_sha256=message['request_sha256'],
                        attestation=attestation), deadline=min(deadline, time.monotonic() + PREPARE_SECONDS))
                elif message.get('type') == 'INIT_ACK':
                    if not written or completion_writer is None or acked:
                        refuse('POLICY_B_INIT_COMPLETION_NOT_AUTHORIZED')
                    exact(message, ('version', 'type', 'session_id', 'nonce', 'operation_id', 'request_sha256', 'result_sha256', 'result'))
                    challenge = latest[0]
                    if (type(message['version']) is not int or message['version'] != 1 or any(message[key] != challenge[key] for key in ('session_id', 'nonce', 'operation_id'))
                            or message['request_sha256'] != open_request['request_sha256']
                            or digest(message['result']) != message['result_sha256']):
                        refuse('POLICY_B_INIT_ACK_BINDING')
                    completion_writer(message)
                    acked = True
                elif message.get('type') == 'OPERATION_OUTPUT':
                    exact(message, ('version', 'type', 'session_id', 'operation_id', 'size', 'sha256'))
                    if (output_received or output_sink is None or type(message['version']) is not int or message['version'] != 1
                            or message['session_id'] != open_request['session_id']
                            or message['operation_id'] != digest(open_request['identity'])
                            or type(message['size']) is not int or not 0 < message['size'] <= 16*1024*1024):
                        refuse('POLICY_B_PRIVATE_OUTPUT_BOUND')
                    sha(message['sha256'])
                    output_received = True
                    received, checksum = 0, hashlib.sha256()
                    output_deadline = min(deadline, time.monotonic() + 30)
                    while received < message['size']:
                        block = channel.recv_bytes(deadline=output_deadline)
                        received += len(block)
                        if received > message['size']:
                            refuse('POLICY_B_PRIVATE_OUTPUT_SIZE')
                        checksum.update(block)
                        output_sink(block)
                    if checksum.hexdigest() != message['sha256']:
                        refuse('POLICY_B_PRIVATE_OUTPUT_HASH')
                elif message.get('type') == 'OPERATION_RESULT':
                    exact(message, ('version', 'type', 'session_id', 'operation_id', 'status', 'result'))
                    if (type(message['version']) is not int or message['version'] != 1 or message['session_id'] != open_request['session_id']
                            or message['operation_id'] != digest(open_request['identity'])
                            or type(message['status']) is not int or not 0 <= message['status'] <= 255):
                        refuse('POLICY_B_OPERATION_COMPLETION')
                    if is_genesis(open_request['identity']) and message['status'] == 0 and len(used) != 2:
                        refuse('POLICY_B_GENESIS_ADMISSION_MISSING')
                    if open_request['identity']['kind'] == 'INIT' and open_request['mode'] == 'EXECUTE' and message['status'] == 0 and not acked:
                        refuse('POLICY_B_INIT_COMPLETION_MISSING')
                    if message['status'] == 0 and open_request['payload'] is not None and not payload_sent:
                        refuse('POLICY_B_UPLOAD_COMPLETION_MISSING')
                    process.stdin.close()
                    status = process.wait(timeout=min(30, max(0.01, deadline-time.monotonic())))
                    if status != message['status']:
                        refuse('POLICY_B_TRANSPORT_OUTCOME_UNKNOWN')
                    # An exited transport must have exactly one terminal frame.
                    # Buffered extra control/output bytes cannot be accepted as a
                    # second operation or silently ignored after a valid result.
                    if os.read(channel.read_fd, 1):
                        refuse('POLICY_B_TRAILING_FRAME')
                    return message
                else:
                    refuse('POLICY_B_UNSOLICITED_FRAME')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            for stream in (process.stdin, process.stdout):
                if not stream.closed:
                    stream.close()


def send_operation_output(session, raw):
    """Existing private operation log/ACK bytes, never parsed as control frames."""
    if type(raw) is not bytes or len(raw) > 16*1024*1024:
        refuse('POLICY_B_PRIVATE_OUTPUT_BOUND')
    if not raw:
        return
    deadline = time.monotonic() + 30
    session.channel.send(dict(version=1, type='OPERATION_OUTPUT', session_id=session.request['session_id'],
        operation_id=digest(session.request['identity']), size=len(raw), sha256=hashlib.sha256(raw).hexdigest()), deadline=deadline)
    for index in range(0, len(raw), MAX_FRAME):
        session.channel.send_bytes(raw[index:index+MAX_FRAME], deadline=deadline)
