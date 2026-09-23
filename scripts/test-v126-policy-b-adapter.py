#!/usr/bin/env python3
"""Pure synthetic adapter tests; no operational source/acquisition qualification.

The existing AP-01 chain fixture supplies observations independently of readiness.
Its native callback is a test double, NOT a native receipt-chain integration test.
Only exact read-only Git subprocesses are allowed; sockets are disabled.
"""
import copy
from dataclasses import replace
import importlib.util
import io
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


adapter = load('policy_b_adapter_fixture_module', 'v126-policy-b-dispatch.py')
fixtures = load('policy_b_existing_chain_fixtures', 'test-v126-dr-evidence.py')
fixtures.dr = adapter.dr
REAL_REQUIRE_LOCK = adapter.require_lock
fixtures.S = adapter.dr.schema
dr = adapter.dr
REAL_REQUIRE_LOCK = adapter.require_lock


def operational_shape_fixture(purpose):
    """Synthetic observations in the operational schema, never qualification.

    The dispatch harness separately supplies synthetic Git clean-status/source
    observations and inert action processes. The actual checkout verifier and
    consumer still execute, including their mode/source/tool checks. No producer,
    real-auth or custody operation happened to create these test-only claims.
    """
    def shape(kind, doc):
        if kind == 'authorization':
            doc['mode'] = 'operational'
            doc['packages'] = ['AP-02', 'AP-03', 'AP-04', 'AP-05', 'AP-06']
        elif kind == 'offhost':
            doc['encryption']['mode'] = 'client-side'
        elif kind in ('restore', 'functional'):
            doc['auth_scope'] = 'real-custody'
        elif kind == 'response':
            doc['mode'] = 'operational'
        elif kind == 'readiness':
            doc['result'] = 'DR_POINT_PASS'
    fixture = fixtures.Fixture(purpose=purpose, mutate=shape)
    fixture.trust = replace(fixture.trust, operational=True)
    return fixture


class Observer:
    """Synthetic authority is constructed separately from later evidence mutations."""
    def __init__(self, stage, target, fixture=None):
        self.fixture = fixture or fixtures.Fixture(purpose='preparation' if stage == 'BASELINE_VERIFIED' else 'cutover-Q')
        f = self.fixture
        b = f.binding
        self.pins = adapter.Pins('future-synthetic-run', str(Path(target).resolve()), b['source_sha'],
            b['source_tree'], b['target_sha256'], b['source_identity_sha256'], b['database_semantics_sha256'],
            copy.deepcopy(b['data_runtime']), copy.deepcopy(b['restore_runtime']), 'attempt-2', fixtures.H('post-v126-recipe'))
        self.observations = adapter.Observations(f.trust, f.evidence, self.pins, f.readiness_ref, f.ongoing_ref)
        self.calls = []
        self.times = []

    def now(self):
        self.calls.append('clock')
        return self.times.pop(0) if self.times else copy.deepcopy(self.fixture.now)

    def acquire(self, context):
        self.calls.append(('acquire', context))
        return self.observations

    def verify_native_stage7(self, context):
        # Native full-chain replay belongs to the production caller and its own
        # harness; this explicit test double only exercises the adapter contract.
        self.calls.append(('native', context))
        return adapter.NativeStage7(self.fixture.native, self.fixture.cutover['native_manifest_sha256'])


def make_case(stage, target, source_digest=None, fixture=None):
    observer = Observer(stage, target, fixture=fixture)
    f = observer.fixture
    script = next(x['sha256'] for x in f.binding['tools'] if x['path'] == 'scripts/v126-cutover.sh')
    identity = dict(run_id=observer.pins.run_id, release_sha=f.binding['source_sha'],
                    script_sha256=source_digest or script, intent_sha256=fixtures.H('actual-intent'),
                    kind='STAGE', name=stage, action=adapter.STAGE_ACTIONS[stage][0])
    return adapter.Gate(observer, operational=observer.fixture.trust.operational), observer, identity


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='v126-policy-b-adapter-')
        self.addCleanup(self.tmp.cleanup)
        self.target = Path(self.tmp.name).resolve()
        self.gate, self.observer, self.identity = make_case('BASELINE_VERIFIED', self.target)
        self.lock = mock.patch.object(adapter, 'require_lock')
        self.lock.start()
        self.addCleanup(self.lock.stop)
        real_popen = subprocess.Popen
        def only_git(args, *positional, **kwargs):
            allowed = (isinstance(args, list) and args and args[0] == 'git'
                       and '--no-replace-objects' in args and '-C' in args
                       and any(x in args for x in ('rev-parse', 'status')))
            if not allowed:
                raise AssertionError('external command forbidden in pure adapter tests')
            return real_popen(args, *positional, **kwargs)
        patcher = mock.patch.object(subprocess, 'Popen', side_effect=only_git)
        patcher.start()
        self.addCleanup(patcher.stop)
        sockets = mock.patch.object(socket, 'socket', side_effect=AssertionError('network forbidden'))
        sockets.start()
        self.addCleanup(sockets.stop)

    def check(self, timeout=None):
        return self.gate.check(self.identity, self.target, 123,
                              timeout if timeout is not None else adapter.ACTION_SECONDS.get(self.identity['action'], 300))

    def reject(self, pattern=None):
        with self.assertRaisesRegex(adapter.GateError, pattern or 'POLICY_B_'):
            self.check()

    def q(self, stage='V126_BACKEND_STARTED'):
        self.gate, self.observer, self.identity = make_case(stage, self.target)

    def test_all_stage_action_mappings_consume_actual_chain(self):
        for stage, actions in adapter.STAGE_ACTIONS.items():
            for action in actions:
                with self.subTest(stage=stage, action=action):
                    self.gate, self.observer, self.identity = make_case(stage, self.target)
                    self.identity['action'] = action
                    with mock.patch.object(dr, 'consume_barrier', wraps=dr.consume_barrier) as consumer:
                        result = self.check()
                    self.assertEqual(result['barrier'], 'R0' if stage == 'BASELINE_VERIFIED' else 'Q')
                    self.assertFalse(result['operational'])
                    self.assertEqual(consumer.call_count, 1)
                    self.assertEqual(consumer.call_args.kwargs['action_seconds'],
                                     adapter.ACTION_SECONDS.get(action, 300) + 300)
                    contexts = [x[1] for x in self.observer.calls if isinstance(x, tuple)]
                    self.assertTrue(all(x.stage == stage and x.action == action for x in contexts))
                    self.assertEqual(sum(x[0] == 'native' for x in self.observer.calls if isinstance(x, tuple)),
                                     0 if stage == 'BASELINE_VERIFIED' else 1)

    def test_lock_inspector_binds_actual_fd_path_owner_mode_and_linux_lock(self):
        root = self.target / '.v126-target-operations'
        root.mkdir(mode=0o700)
        lock = root / 'lock'
        fd = os.open(lock, os.O_CREAT | os.O_RDWR, 0o600)
        self.addCleanup(os.close, fd)
        info = os.fstat(fd)
        device_inode = f'{os.major(info.st_dev):02x}:{os.minor(info.st_dev):02x}:{info.st_ino}'
        valid = f'pos: 0\nlock: 1: FLOCK ADVISORY WRITE {os.getpid()} {device_inode} 0 EOF\n'
        for raw, allowed in [(valid, True), ('pos: 0\n', False),
                             (valid.replace('WRITE', 'READ'), False),
                             (valid.replace(str(os.getpid()) + ' ', '999999 '), False),
                             (valid.replace(device_inode, '00:00:0'), False),
                             (valid + 'x'*16384, False)]:
            with self.subTest(fdinfo=raw[:80]), mock.patch.object(adapter.sys, 'platform', 'linux'), \
                    mock.patch('builtins.open', return_value=io.StringIO(raw)) as opened:
                if allowed:
                    REAL_REQUIRE_LOCK(self.target, fd)
                    self.assertEqual(opened.call_args.args[0], '/proc/self/fdinfo/' + str(fd))
                else:
                    with self.assertRaises(adapter.GateError):
                        REAL_REQUIRE_LOCK(self.target, fd)
        lock.chmod(0o640)
        with mock.patch.object(adapter.sys, 'platform', 'linux'), self.assertRaises(adapter.GateError):
            REAL_REQUIRE_LOCK(self.target, fd)
        lock.chmod(0o600)
        other = os.open(root / 'other', os.O_CREAT | os.O_RDWR, 0o600)
        try:
            with mock.patch.object(adapter.sys, 'platform', 'linux'), self.assertRaises(adapter.GateError):
                REAL_REQUIRE_LOCK(self.target, other)
        finally:
            os.close(other)
        with self.assertRaises(adapter.GateError):
            REAL_REQUIRE_LOCK(self.target, -1)

    def test_evidence_snapshot_is_bounded_before_copying(self):
        self.observer.observations.evidence.documents['a'*64] = b'x'*1024
        with mock.patch.object(adapter, 'MAX_EVIDENCE_BYTES', 1024):
            self.reject('EVIDENCE_SIZE')

    def test_elapsed_monotonic_bound_cannot_be_hidden_by_static_observation_clock(self):
        with mock.patch.object(adapter.time, 'monotonic', side_effect=[0, 301]):
            self.reject('OBSERVATION_TIMEOUT')
        with mock.patch.object(adapter.time, 'monotonic', side_effect=[1, 0]):
            self.reject('OBSERVATION_TIMEOUT')

    def test_default_observer_does_not_bootstrap_authority(self):
        self.gate = adapter.Gate()
        self.reject('INDEPENDENT_OBSERVER_NOT_IMPLEMENTED')

    def test_synthetic_observations_cannot_be_operational(self):
        self.gate = adapter.Gate(self.observer)
        self.reject('OPERATIONAL_MODE')

    def test_wrong_action_or_budget_refused(self):
        self.identity['action'] = 'recover-post-v126-stop'
        self.reject('DISPATCH_IDENTITY')
        self.identity['action'] = 'baseline'
        for timeout in (None, 0, 1, 299, 301, True, float('nan')):
            with self.subTest(timeout=timeout), self.assertRaises(adapter.GateError):
                self.gate.check(self.identity, self.target, 123, timeout)

    def test_missing_lock_refuses_before_observations(self):
        with mock.patch.object(adapter, 'require_lock', side_effect=adapter.GateError('POLICY_B_SHARED_LOCK_REQUIRED')):
            self.reject('SHARED_LOCK_REQUIRED')
        self.assertEqual(self.observer.calls, [])

    def test_changed_lock_before_return_refuses(self):
        with mock.patch.object(adapter, 'require_lock', side_effect=[None, None, adapter.GateError('POLICY_B_SHARED_LOCK_REQUIRED')]):
            self.reject('SHARED_LOCK_REQUIRED')

    def test_pins_do_not_follow_source_run_target_runtime_claims(self):
        for field, value in [('run_id', 'other-run'), ('source_sha', 'b'*40),
                             ('source_tree', 'c'*40), ('deployment_target', '/other'),
                             ('target_sha256', 'c'*64), ('database_semantics_sha256', 'c'*64),
                             ('data_runtime', self.observer.pins.data_runtime | {'config_epoch': 'c'*64})]:
            with self.subTest(field=field):
                original = self.observer.observations
                self.observer.observations = replace(original, pins=replace(original.pins, **{field: value}))
                self.reject('INDEPENDENT_BINDING_MISMATCH')
                self.observer.observations = original

    def test_script_identity_is_actual_dispatch_identity(self):
        self.identity['script_sha256'] = 'c'*64
        self.reject('INDEPENDENT_BINDING_MISMATCH')

    def test_missing_stale_unavailable_independent_inputs_refuse(self):
        original = self.observer.observations
        f = self.observer.fixture
        for trust in [replace(f.trust, observed=fixtures.clock(fixtures.NOW-301, identity='verifier-clock')),
                      replace(f.trust, ledger_sha256='c'*64), replace(f.trust, ongoing_sha256=None),
                      replace(f.trust, custody_versions=frozenset()), replace(f.trust, observed_evidence=frozenset()),
                      replace(f.trust, available_qualifications=frozenset())]:
            with self.subTest(trust=trust):
                self.observer.observations = replace(original, trust=trust)
                self.reject()
        self.observer.observations = original

    def test_forged_ongoing_and_readiness_rehashed_cannot_repin_authority(self):
        f = self.observer.fixture
        ongoing = copy.deepcopy(f.evidence.get(f.ongoing_ref, 'ongoing'))
        ongoing['mechanism_sha256'] = fixtures.H('forged-mechanism')
        forged = f.put(ongoing)
        readiness = copy.deepcopy(f.evidence.get(f.readiness_ref, 'readiness'))
        readiness['ongoing_sha256'] = forged
        forged_readiness = f.put(readiness)
        self.observer.observations = replace(self.observer.observations,
            evidence=dr.Evidence(f.documents), ongoing_ref=forged, readiness_ref=forged_readiness)
        self.reject()

    def test_native_missing_wrong_receipt_manifest_or_run_refuses(self):
        self.q()
        for value in [None, adapter.NativeStage7(b'{}', 'c'*64),
                      adapter.NativeStage7(self.observer.fixture.native, 'c'*64)]:
            with self.subTest(native=value), mock.patch.object(self.observer, 'verify_native_stage7', return_value=value):
                self.reject()
        self.identity['run_id'] = 'another-synthetic-run'
        self.reject()

    def test_native_chain_error_precedes_consumer(self):
        self.q()
        with mock.patch.object(self.observer, 'verify_native_stage7', side_effect=ValueError('incomplete chain')), \
             mock.patch.object(dr, 'consume_barrier', wraps=dr.consume_barrier) as consumer:
            self.reject()
        consumer.assert_not_called()

    def test_r0_cannot_replace_q(self):
        self.identity['name'] = 'V126_BACKEND_STARTED'
        self.identity['action'] = 'start-v126'
        self.reject()

    def test_clock_rollback_future_and_stale_checkpoint(self):
        for observed in [fixtures.clock(fixtures.NOW-1, identity='verifier-clock'),
                         fixtures.clock(fixtures.NOW+301, identity='verifier-clock'),
                         fixtures.clock(fixtures.NOW, identity='verifier-clock', monotonic=0)]:
            with self.subTest(clock=observed):
                self.observer.times = [copy.deepcopy(self.observer.fixture.now), observed]
                self.reject()
                self.observer.times = []

    def test_late_ongoing_failure_at_final_stages(self):
        for stage in ('FINAL_V126_BACKEND_STARTED', 'ORDINARY_CADDY_RESTORED'):
            with self.subTest(stage=stage):
                self.q(stage)
                f = self.observer.fixture
                self.observer.times = [f.now, f.now, fixtures.clock(fixtures.NOW+301, identity='verifier-clock')]
                self.reject()

    def advance_clock_after_final_replay(self, seconds):
        original = dr.evaluate_ongoing
        calls = []
        def evaluate(*args, **kwargs):
            result = original(*args, **kwargs)
            calls.append(1)
            if len(calls) == 2:
                self.observer.times.append(fixtures.clock(fixtures.NOW + seconds, identity='verifier-clock'))
            return result
        return mock.patch.object(dr, 'evaluate_ongoing', side_effect=evaluate)

    def test_final_replay_cannot_return_expired_checkpoint_or_monitor(self):
        for point in ('checkpoint', 'observed', 'last_monitor'):
            with self.subTest(point=point):
                def age_observation(kind, doc):
                    if kind == 'ongoing' and point != 'checkpoint':
                        doc[point] = fixtures.clock(fixtures.NOW - 299, identity='verifier-clock')
                fixture = fixtures.Fixture(mutate=age_observation)
                if point == 'checkpoint':
                    fixture.trust = replace(fixture.trust,
                        observed=fixtures.clock(fixtures.NOW - 299, identity='verifier-clock'))
                self.gate, self.observer, self.identity = make_case('BASELINE_VERIFIED', self.target, fixture=fixture)
                with self.advance_clock_after_final_replay(2):
                    self.reject('OBSERVATION_EXPIRED_AT_RETURN')

    def test_final_replay_cannot_cross_authority_expiry_or_cadence_grace(self):
        def expiring(kind, doc):
            if kind == 'authorization':
                doc['valid_until'] = fixtures.UTC(fixtures.NOW + 1)
        fixture = fixtures.Fixture(mutate=expiring)
        self.gate, self.observer, self.identity = make_case('BASELINE_VERIFIED', self.target, fixture=fixture)
        with self.advance_clock_after_final_replay(2):
            self.reject()
        self.gate, self.observer, self.identity = make_case('BASELINE_VERIFIED', self.target)
        with self.advance_clock_after_final_replay(61):
            self.reject('CADENCE_BOUNDARY_DURING_CHECK')

    def test_recipe_substitution_is_not_reviewed(self):
        self.q('ORDINARY_CADDY_RESTORED')
        self.observer.observations = replace(self.observer.observations,
            pins=replace(self.observer.pins, post_v126_recipe_sha256=fixtures.H('other-recipe')))
        self.reject('POST_V126_RECIPE_MISMATCH')

    def test_observer_timeout_and_exception_do_not_return_pass(self):
        with mock.patch.object(adapter, 'MAX_OBSERVATION_SECONDS', 0.02), \
                mock.patch.object(self.observer, 'acquire', side_effect=lambda context: time.sleep(1)):
            self.reject('OBSERVATION_TIMEOUT')
        def catches_ordinary_errors(context):
            try:
                time.sleep(1)
            except Exception:
                return self.observer.observations
        with mock.patch.object(adapter, 'MAX_OBSERVATION_SECONDS', 0.02), \
                mock.patch.object(self.observer, 'acquire', side_effect=catches_ordinary_errors):
            self.reject('OBSERVATION_TIMEOUT')
        with mock.patch.object(self.observer, 'acquire', side_effect=RuntimeError('sensitive provider detail')):
            self.reject('OBSERVATIONS_OR_CONSUMER_REFUSED')

    def test_error_in_consumer_is_closed_and_does_not_log_raw_details(self):
        with mock.patch.object(dr, 'consume_barrier', side_effect=RuntimeError('secret values')):
            self.reject('OBSERVATIONS_OR_CONSUMER_REFUSED')

    def test_each_check_reacquires_and_cannot_reuse_cached_pass(self):
        self.check()
        self.observer.observations = replace(self.observer.observations, ongoing_ref='c'*64)
        self.reject()
        self.assertEqual(sum(x[0] == 'acquire' for x in self.observer.calls if isinstance(x, tuple)), 2)

    def test_existing_alarm_and_other_thread_fail_without_replacing_timer(self):
        signal.setitimer(signal.ITIMER_REAL, 30)
        try:
            self.reject('BOUND_UNAVAILABLE')
            self.assertGreater(signal.getitimer(signal.ITIMER_REAL)[0], 0)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
        errors = []
        def invoke():
            try:
                self.check()
            except adapter.GateError as error:
                errors.append(str(error))
        worker = threading.Thread(target=invoke)
        worker.start()
        worker.join(timeout=2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, ['POLICY_B_OBSERVATION_BOUND_UNAVAILABLE'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
