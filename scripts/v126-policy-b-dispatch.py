#!/usr/bin/env python3
"""Shared Policy B consumer adapter and Linux lock inspection.

The attended authority module independently acquires Trust and current bindings.
This shared module never derives them from readiness or environment variables.
The default refuses admission. Passing synthetic observations does not make a
release operational. Existing native history/authorization remain caller gates.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import copy
import importlib.util
import math
import os
from pathlib import Path
import re
import signal
import stat
import sys
import threading
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('v126_policy_b_evidence', ROOT / 'scripts/v126-dr-evidence.py')
dr = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = dr
_spec.loader.exec_module(dr)

# These are actual operation identities, never selected by readiness JSON.
STAGE_ACTIONS = {
    'BASELINE_VERIFIED': ('baseline',),
    'FINAL_V125_PREFLIGHT_PASSED': ('preflight-upload', 'final-v125-preflight'),
    'V126_MAINTENANCE_CONFIG_PREPARED': ('transform-maintenance',),
    'V126_BACKEND_STARTED': ('start-v126',),
    'MANUAL_SMOKE_AUTHORIZED': ('open-manual-smoke',),
    'FINAL_V126_BACKEND_STARTED': ('start-v126',),
    'ORDINARY_CADDY_RESTORED': ('restore-caddy',),
}
ACTION_SECONDS = {'final-v125-preflight': 600}
MAX_OBSERVATION_SECONDS = 300
MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
# A dispatch reads one bounded evidence snapshot, never an unbounded catalogue.
MAX_EVIDENCE_BYTES = MAX_DOCUMENT_BYTES


class GateError(ValueError):
    pass


class _ObservationTimeout(BaseException):
    pass


def refuse(code):
    raise GateError(code)


@dataclass(frozen=True)
class Context:
    run_id: str
    release_sha: str
    script_sha256: str
    stage: str
    action: str
    target: str
    action_seconds: int


@dataclass(frozen=True)
class Pins:
    """Independent caller observations, not a serializable authority document."""
    run_id: str
    deployment_target: str
    source_sha: str
    source_tree: str
    target_sha256: str
    source_identity_sha256: str
    database_semantics_sha256: str
    data_runtime: dict
    restore_runtime: dict
    requested_attempt: str
    post_v126_recipe_sha256: str


@dataclass(frozen=True)
class Observations:
    trust: dr.Trust
    evidence: dr.Evidence
    pins: Pins
    readiness_ref: str
    ongoing_ref: str


@dataclass(frozen=True)
class NativeStage7:
    """Only returned AFTER the existing verify_receipt replays the full chain.

    The caller independently pins exact receipt bytes and manifest digest. This
    value is not proof by itself; an acquisition implementation is still required.
    """
    receipt: bytes
    manifest_sha256: str


def require_lock(target, lockfd):
    """Inspect the existing Linux lock; never acquire, create or release a lock."""
    if type(lockfd) is not int or lockfd < 0 or sys.platform != 'linux':
        refuse('POLICY_B_SHARED_LOCK_REQUIRED')
    path = Path(target) / '.v126-target-operations' / 'lock'
    if path.resolve(strict=True) != path:
        refuse('POLICY_B_SHARED_LOCK_REQUIRED')
    info, opened = path.lstat(), os.fstat(lockfd)
    if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_nlink != 1 or info.st_uid != os.geteuid()
            or (info.st_dev, info.st_ino) != (opened.st_dev, opened.st_ino)):
        refuse('POLICY_B_SHARED_LOCK_REQUIRED')
    with open('/proc/self/fdinfo/' + str(lockfd), encoding='ascii') as handle:
        raw = handle.read(16385)
    device_inode = f'{os.major(info.st_dev):02x}:{os.minor(info.st_dev):02x}:{info.st_ino}'
    expected = re.compile(r'^lock:\s+\d+: FLOCK\s+ADVISORY\s+WRITE\s+' + str(os.getpid())
                          + r'\s+' + re.escape(device_inode) + r'\s+0 EOF$', re.M)
    if len(raw) > 16384 or not expected.search(raw):
        refuse('POLICY_B_SHARED_LOCK_REQUIRED')


@contextmanager
def bounded(seconds):
    if type(seconds) not in (int, float) or not math.isfinite(seconds) or not 0 < seconds <= 300:
        refuse('POLICY_B_OBSERVATION_TIMEOUT')
    # Do not replace another caller's timer or leave a timed-out background task.
    if (threading.current_thread() is not threading.main_thread()
            or signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0)):
        refuse('POLICY_B_OBSERVATION_BOUND_UNAVAILABLE')
    previous = signal.getsignal(signal.SIGALRM)
    def expired(signum, frame):
        # Observation/consumer error handlers must not swallow this deadline.
        raise _ObservationTimeout()
    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    except _ObservationTimeout:
        refuse('POLICY_B_OBSERVATION_TIMEOUT')
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


class Gate:
    """observer.acquire(context), now(), verify_native_stage7(context) are trusted
    caller code, not configurable plugins. The attended authority reader supplies these inputs independently of readiness.
    Synthetic test callers use explicit fixture observations; operational mode
    retains the exact clean-checkout and source/tool checks.
    """
    def __init__(self, observer=None, *, operational=True):
        self.observer = observer
        self.operational = operational

    def check(self, identity, target, lockfd, timeout):
        try:
            return self._check(identity, target, lockfd, timeout)
        except GateError:
            raise
        except Exception:
            # Observation/provider/consumer errors are not raw diagnostic authority.
            refuse('POLICY_B_OBSERVATIONS_OR_CONSUMER_REFUSED')

    def _check(self, identity, target, lockfd, timeout):
        if (identity.get('kind') != 'STAGE' or identity.get('name') not in STAGE_ACTIONS
                or identity.get('action') not in STAGE_ACTIONS[identity['name']]):
            refuse('POLICY_B_DISPATCH_IDENTITY')
        bound = ACTION_SECONDS.get(identity['action'], 300)
        if type(timeout) is not int or timeout != bound or type(self.operational) is not bool:
            refuse('POLICY_B_ACTION_BOUND')
        path = Path(target)
        if not path.is_absolute() or str(path.resolve(strict=True)) != str(path):
            refuse('POLICY_B_TARGET_BINDING')
        context = Context(identity['run_id'], identity['release_sha'], identity['script_sha256'],
                          identity['name'], identity['action'], str(path), bound)
        if self.observer is None:
            refuse('POLICY_B_INDEPENDENT_OBSERVER_NOT_IMPLEMENTED')
        return self.verify_context(context, checkpoint=lambda: require_lock(path, lockfd))

    def verify_context(self, context, *, checkpoint, observation_seconds=None):
        """Same consumer on V; authenticated transport owns S's physical lock.

        This method does not grant transport authority. The production caller
        verifies its enrolled challenge before entry; the ordinary in-process
        check above continues to inspect the actual Linux target lock.
        """
        if type(context) is not Context:
            refuse('POLICY_B_DISPATCH_IDENTITY')
        actions = STAGE_ACTIONS.get(context.stage, ())
        if context.stage == 'RUN_INITIALIZED':
            actions = ('initialize-run',)
        if context.action not in actions:
            refuse('POLICY_B_DISPATCH_IDENTITY')
        bound = ACTION_SECONDS.get(context.action, 300)
        if type(context.action_seconds) is not int or context.action_seconds != bound:
            refuse('POLICY_B_ACTION_BOUND')
        if self.observer is None:
            refuse('POLICY_B_INDEPENDENT_OBSERVER_NOT_IMPLEMENTED')
        self.last_validation = None
        budget = min(MAX_OBSERVATION_SECONDS, bound) if observation_seconds is None else observation_seconds
        if type(budget) not in (int, float) or not 0 < budget <= min(MAX_OBSERVATION_SECONDS, bound):
            refuse('POLICY_B_OBSERVATION_TIMEOUT')
        checkpoint()
        with bounded(budget):
            started_at = time.monotonic()
            start = copy.deepcopy(self.observer.now())
            observed = self.observer.acquire(context)
            if type(observed) is not Observations or type(observed.trust) is not dr.Trust or type(observed.pins) is not Pins:
                refuse('POLICY_B_TYPED_OBSERVATIONS_REQUIRED')
            if type(observed.evidence) is not dr.Evidence or observed.trust.operational is not self.operational:
                refuse('POLICY_B_OPERATIONAL_MODE')
            # Snapshot caller data. Later mutation of evidence containers cannot
            # substitute different bytes during this check.
            if type(observed.evidence.documents) is not dict:
                refuse('POLICY_B_EVIDENCE_SIZE')
            total = 0
            for raw in observed.evidence.documents.values():
                if type(raw) is not bytes or not 0 < len(raw) <= MAX_DOCUMENT_BYTES:
                    refuse('POLICY_B_EVIDENCE_SIZE')
                total += len(raw)
                if total > MAX_EVIDENCE_BYTES:
                    refuse('POLICY_B_EVIDENCE_SIZE')
            documents = dict(observed.evidence.documents)
            evidence = dr.Evidence(documents)
            observed = copy.deepcopy(observed)
            pins = observed.pins
            binding = evidence.get(observed.trust.binding_sha256, 'binding')
            if (pins.run_id != context.run_id or pins.deployment_target != context.target
                    or pins.source_sha != context.release_sha
                    or any(binding[key] != getattr(pins, key) for key in
                           ('source_sha', 'source_tree', 'target_sha256', 'source_identity_sha256',
                            'database_semantics_sha256', 'data_runtime', 'restore_runtime'))
                    or next(x['sha256'] for x in binding['tools'] if x['path'] == 'scripts/v126-cutover.sh') != context.script_sha256):
                refuse('POLICY_B_INDEPENDENT_BINDING_MISMATCH')
            dr.schema.check(pins.requested_attempt, 'id')
            ongoing = evidence.get(observed.ongoing_ref, 'ongoing')
            dr.schema.check(pins.post_v126_recipe_sha256, 'sha')
            if ongoing['post_v126_recipe_sha256'] != pins.post_v126_recipe_sha256:
                refuse('POLICY_B_POST_V126_RECIPE_MISMATCH')
            purpose = 'preparation' if context.stage in ('BASELINE_VERIFIED', 'RUN_INITIALIZED') else 'cutover-Q'
            native, cutover = None, None
            if purpose == 'cutover-Q':
                native = self.observer.verify_native_stage7(context)
                if type(native) is not NativeStage7 or type(native.receipt) is not bytes or len(native.receipt) > MAX_DOCUMENT_BYTES:
                    refuse('POLICY_B_NATIVE_CHAIN_REQUIRED')
                cutover = dict(run_id=context.run_id, stage7_sha256=dr.sha(native.receipt),
                               native_manifest_sha256=native.manifest_sha256)
            now = self.observer.now()
            dr.chronology(start, now)
            checkpoint()
            result = dr.consume_barrier(evidence, observed.trust, observed.readiness_ref,
                observed.ongoing_ref, now, purpose=purpose, requested_attempt=pins.requested_attempt,
                action_seconds=bound + min(MAX_OBSERVATION_SECONDS, bound), cutover=cutover,
                native_stage7=None if native is None else native.receipt)
            # Reserve the entire bounded adapter duration in freshness, rather
            # than pretending a PASS before verification remains current forever.
            finish = self.observer.now()
            dr.chronology(now, finish)
            elapsed = time.monotonic() - started_at
            if elapsed < 0 or elapsed > budget or dr.age(start, finish) > min(MAX_OBSERVATION_SECONDS, bound):
                refuse('POLICY_B_OBSERVATION_TIMEOUT')
            proof = evidence.get(observed.readiness_ref, 'readiness')
            dr.verify_consumer_authority(evidence, observed.trust, proof['authorization_sha256'],
                                         finish, purpose, 'AP-06')
            if dr.age(observed.trust.observed, finish) > MAX_OBSERVATION_SECONDS:
                refuse('POLICY_B_CHECKPOINT_EXPIRED_DURING_CHECK')
            ongoing_result = dr.evaluate_ongoing(evidence, observed.trust, observed.ongoing_ref, finish)
            if not ongoing_result['ready']:
                refuse('POLICY_B_ONGOING_EXPIRED_DURING_CHECK')
            # Ledger/tool replay above may itself consume the last observation
            # seconds. Recheck the temporal validity of that same immutable
            # evaluation after expensive work; crossing a cadence/grace boundary
            # requires a fresh full check by a future separately permitted call.
            current = self.observer.now()
            dr.chronology(finish, current)
            if any(dr.age(point, current) > MAX_OBSERVATION_SECONDS for point in
                   (observed.trust.observed, ongoing['observed'], ongoing['last_monitor'])):
                refuse('POLICY_B_OBSERVATION_EXPIRED_AT_RETURN')
            dr.verify_consumer_authority(evidence, observed.trust, proof['authorization_sha256'],
                                         current, purpose, 'AP-06')
            dr.verify_consumer_authority(evidence, observed.trust, ongoing['authorization_sha256'],
                                         current, 'product-periodic', 'AP-05')
            if not dr.validate_response(evidence, observed.trust, binding,
                                        ongoing['response_authority_sha256'], current):
                refuse('POLICY_B_RESPONSE_AUTHORITY_EXPIRED_AT_RETURN')
            def cadence_phase(clock):
                lower = dr.schema.timestamp(clock['utc']) - clock['error_seconds']
                slot, offset = divmod(lower, dr.schema.POLICY['snapshot_seconds'])
                return slot, offset >= dr.schema.POLICY['qualification_seconds']
            if cadence_phase(finish) != cadence_phase(current):
                refuse('POLICY_B_CADENCE_BOUNDARY_DURING_CHECK')
            if (ongoing_result['age_seconds'] + dr.age(finish, current)
                    >= dr.schema.POLICY['warning_seconds']):
                refuse('POLICY_B_ONGOING_AGE_BOUNDARY_DURING_CHECK')
            elapsed = time.monotonic() - started_at
            if elapsed < 0 or elapsed > budget:
                refuse('POLICY_B_OBSERVATION_TIMEOUT')
            checkpoint()
            self.last_validation = dict(observations=observed, evidence=evidence, binding=binding,
                ongoing=ongoing, proof=proof, result=result, clock=current, ongoing_result=ongoing_result,
                native=native, cutover=cutover, context=context)
            return result
