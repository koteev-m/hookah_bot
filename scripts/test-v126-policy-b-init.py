#!/usr/bin/env python3
"""Actual S-side INIT/history decisions; synthetic authenticated-verifier transcripts.

No SSH/provider/daemon: Popen, sockets and os.system are technically denied. These
unit tests exercise the durable distributed completion boundary, not AP-06 acquisition;
the transport/consumer and Linux caller suites cover that independent trust boundary.
"""
import copy
import builtins
import io
import fcntl
import hashlib
import importlib.util
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('init_bindings', ROOT / 'v126-operation-bindings.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def digest(value):
    return hashlib.sha256(b.binding_canonical(value)).hexdigest()


def seed_request(target, owner, manifest=b'{"synthetic":"INIT manifest"}\n'):
    sha = hashlib.sha256(manifest).hexdigest()
    identity = dict(owner, intent_sha256=sha, kind='INIT', name='RUN_INITIALIZED', action='initialize-run')
    request = dict(format_version=1, identity=identity, target_sha256=hashlib.sha256(str(target).encode()).hexdigest(),
                   manifest_sha256=sha, manifest_size=len(manifest), local_state_sha256='e' * 64,
                   metadata=dict(directories=['artifacts', 'authorizations', 'intents', 'receipts', 'recovery', 'tmp'],
                                 files=[dict(path='run.json', sha256=sha, size=len(manifest), mode=0o400),
                                        dict(path='run.json.sha256', sha256=hashlib.sha256((sha + '\n').encode()).hexdigest(),
                                             size=65, mode=0o400)]))
    return identity, request


def attestation(identity, request):
    return dict(format_version=1, operation_id=digest(identity), request_sha256=digest(request),
                manifest_sha256=request['manifest_sha256'], metadata_sha256=digest(request['metadata']),
                writer='ATTENDED_VERIFIER', durable=True)


def seed_completion(target, owner):
    """Synthetic already-accepted immutable history for existing dispatcher fixtures."""
    identity, request = seed_request(target, owner)
    operation = digest(identity)
    root = Path(target) / '.v126-target-operations'
    start = dict(identity=identity, operation_id=operation, started_at='2026-09-22T00:00:00+00:00', boot_id='synthetic')
    result = dict(format_version=1, identity=identity, operation_id=operation, exit=0, outcome='SUCCEEDED',
                  completion='LOCAL_METADATA_ATTESTED', request_sha256=digest(request),
                  attestation=attestation(identity, request), completed_at='2026-09-22T00:00:01+00:00')
    for suffix, doc in [('start', start), ('request', request), ('result', result)]:
        b.binding_create(root / (operation + '.' + suffix + '.json'), doc)
    return dict(request=request, result=result, request_sha256=digest(request), result_sha256=digest(result))


class Transcript:
    """External V protocol outcome fixture; never a replacement consumer in production."""
    def __init__(self, test, fail=None, on_check=None):
        self.test, self.fail, self.on_check = test, fail, on_check
        self.calls = 0
        self.dispatches = 0

    def check(self, identity, target, fd, timeout):
        self.calls += 1
        b.binding_lock_held(Path(target) / '.v126-target-operations', fd)
        self.test.assertEqual(timeout, 300)
        self.test.assertEqual(identity['kind'], 'INIT')
        if self.on_check:
            self.on_check(self.calls)
        if self.calls == self.fail:
            raise ValueError('synthetic verifier refusal')
        return dict(barrier='R0', operational=True, qualification_sha256='d' * 64)

    def before_dispatch(self, identity, target, fd, timeout):
        b.binding_lock_held(Path(target) / '.v126-target-operations', fd)
        self.test.assertEqual(self.calls, 2)
        self.dispatches += 1
        if self.fail == 'dispatch':
            raise ValueError('synthetic expired nonce')


class Init(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='policy-b-init-synthetic-')
        self.target = Path(self.tmp.name).resolve() / 'synthetic-target'
        self.target.mkdir(mode=0o700)
        self.root = self.target / '.v126-target-operations'
        self.root.mkdir(mode=0o700)
        (self.root / 'lock').touch(mode=0o600)
        self.owner = dict(run_id='synthetic-init-run', release_sha='a' * 40, script_sha256='b' * 64)
        b.binding_create(self.root / 'run.json', self.owner)
        self.identity, self.request = seed_request(self.target, self.owner)
        self.writes = []
        self.gate = Transcript(self)
        self.patches = [patch.object(sys, 'platform', 'linux'),
                        patch.object(subprocess, 'Popen', side_effect=AssertionError('no process allowed')),
                        patch.object(socket, 'socket', side_effect=AssertionError('no network allowed')),
                        patch.object(os, 'system', side_effect=AssertionError('no shell allowed'))]
        real_open = builtins.open
        def fdinfo(path, *args, **kwargs):
            if isinstance(path, str) and path.startswith('/proc/self/fdinfo/'):
                fd = int(path.rsplit('/', 1)[1]); info = os.fstat(fd)
                probe = os.open(self.root / 'lock', os.O_RDWR)
                held = False
                try:
                    try: fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError: held = True
                finally: os.close(probe)
                raw = (f'lock:\t1: FLOCK ADVISORY WRITE {os.getpid()} '
                       f'{os.major(info.st_dev):02x}:{os.minor(info.st_dev):02x}:{info.st_ino} 0 EOF\n') if held else ''
                return io.StringIO(raw)
            return real_open(path, *args, **kwargs)
        self.patches.append(patch.object(builtins, 'open', fdinfo))
        original = Path.read_text
        self.patches.append(patch.object(Path, 'read_text', lambda path, *a, **kw:
            'synthetic-boot\n' if str(path) == '/proc/sys/kernel/random/boot_id' else original(path, *a, **kw)))
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.tmp.cleanup()

    def writer(self, identity, request, late):
        self.assertEqual(self.gate.dispatches, 1)
        self.assertEqual(late['barrier'], 'R0')
        self.writes.append(digest(request))
        return attestation(identity, request)

    def run_init(self, **kwargs):
        return b.binding_initialize(self.target, self.identity, self.request,
                                    policy_b_gate=self.gate, write_init=kwargs.pop('write_init', self.writer), **kwargs)

    def names(self):
        return {path.name for path in self.root.iterdir()}

    def unknown(self):
        self.assertTrue((self.root / (digest(self.identity) + '.start.json')).exists())
        self.assertFalse((self.root / (digest(self.identity) + '.result.json')).exists())
        with self.assertRaisesRegex(b.BindingError, 'prior_outcome_unknown'):
            b.binding_history(self.root, self.target)

    def test_init_positive_exact_history_attestation_and_no_reaped_claim(self):
        result = self.run_init()
        self.assertEqual(self.gate.calls, 2)
        self.assertEqual(self.writes, [digest(self.request)])
        self.assertNotIn('children', result)
        self.assertEqual(result['completion'], 'LOCAL_METADATA_ATTESTED')
        readback = b.binding_init_readback(self.target, self.identity, digest(self.request))
        self.assertEqual(readback['result'], result)
        self.assertEqual(readback['result_sha256'], digest(result))
        b.binding_validate_init_completion(self.identity, self.request, result, self.target)
        inventory, unknown, identities = b.binding_inventory(self.root, self.owner)
        self.assertEqual(len(inventory), 3)
        self.assertFalse(unknown)
        self.assertEqual(identities, [self.identity])

    def test_missing_registry_is_not_created(self):
        (self.root / 'run.json').unlink(); (self.root / 'lock').unlink(); self.root.rmdir()
        with self.assertRaises(OSError):
            self.run_init()
        self.assertFalse(self.root.exists())
        self.assertEqual(self.writes, [])

    def test_missing_lock_or_owner_no_bootstrap(self):
        for name in ['lock', 'run.json']:
            with self.subTest(name=name):
                path = self.root / name; raw = path.read_bytes(); mode = path.stat().st_mode & 0o777
                path.unlink()
                with self.assertRaises(OSError): self.run_init()
                self.assertFalse(path.exists())
                path.write_bytes(raw); path.chmod(mode)
        self.assertEqual(self.writes, [])

    def test_busy_shared_lock_denies_without_intent(self):
        fd = os.open(self.root / 'lock', os.O_RDWR)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError): self.run_init()
        finally: os.close(fd)
        self.assertEqual(self.names(), {'lock', 'run.json'})
        self.assertEqual(self.gate.calls, 0)

    def test_early_refusal_has_no_canonical_local_or_remote_intent(self):
        self.gate.fail = 1
        with self.assertRaisesRegex(b.BindingError, 'policy_b_admission_refused'): self.run_init()
        self.assertEqual(self.names(), {'lock', 'run.json'})
        self.assertEqual(self.writes, [])

    def test_late_refusal_preserves_unknown_and_no_writer(self):
        self.gate.fail = 2
        with self.assertRaisesRegex(b.BindingError, 'policy_b_admission_refused'): self.run_init()
        self.unknown(); self.assertEqual(self.writes, [])

    def test_late_expiry_no_writer(self):
        self.gate.fail = 'dispatch'
        with self.assertRaisesRegex(ValueError, 'expired nonce'): self.run_init()
        self.unknown(); self.assertEqual(self.writes, [])

    def test_signal_during_late_observation_no_writer(self):
        self.gate.on_check = lambda count: signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None) if count == 2 else None
        with self.assertRaisesRegex(b.BindingError, 'cancelled_before_dispatch'): self.run_init()
        self.unknown(); self.assertEqual(self.writes, [])

    def test_history_changed_during_early_observation_no_intent(self):
        self.gate.on_check = lambda count: b.binding_create(self.root / 'foreign.json', {})
        with self.assertRaisesRegex(b.BindingError, 'history_changed'): self.run_init()
        self.assertFalse(any(self.root.glob('*.start.json'))); self.assertEqual(self.writes, [])

    def test_history_changed_during_late_observation_no_writer(self):
        def change(count):
            if count == 2: b.binding_create(self.root / 'foreign.json', {})
        self.gate.on_check = change
        with self.assertRaisesRegex(b.BindingError, 'history_changed'): self.run_init()
        self.assertEqual(self.writes, [])
        self.assertFalse(any(self.root.glob('*.result.json')))

    def test_own_exact_inflight_intent_cannot_be_adopted_after_restart(self):
        self.gate.fail = 2
        with self.assertRaises(b.BindingError): self.run_init()
        self.gate = Transcript(self)
        with self.assertRaisesRegex(b.BindingError, 'prior_outcome_unknown'): self.run_init()
        self.assertEqual(self.gate.calls, 0); self.assertEqual(self.writes, [])

    def test_partial_writer_failure_has_no_success(self):
        def partial(*args):
            self.writes.append('partial')
            raise OSError('synthetic local fsync failed')
        with self.assertRaises(OSError): self.run_init(write_init=partial)
        self.unknown(); self.assertEqual(self.writes, ['partial'])

    def test_malformed_attestation_has_no_success(self):
        for key, value in [('durable', False), ('writer', 'REMOTE_REAPED'), ('manifest_sha256', 'f' * 64)]:
            with self.subTest(key=key):
                self.gate = Transcript(self)
                def malformed(identity, request, late):
                    result = attestation(identity, request); result[key] = value; return result
                with self.assertRaisesRegex(b.BindingError, 'attestation'): self.run_init(write_init=malformed)
                self.unknown()
                for path in self.root.glob(digest(self.identity) + '.*'): path.unlink()

    def test_lost_ack_copy_readback_changes_no_remote_bytes_and_no_reexecution(self):
        self.run_init()
        before = b.binding_history_snapshot(self.root)
        result = b.binding_init_readback(self.target, self.identity, digest(self.request))
        self.assertEqual(result['request'], self.request)
        self.assertEqual(before, b.binding_history_snapshot(self.root))
        self.assertEqual(len(self.writes), 1)
        with self.assertRaisesRegex(b.BindingError, 'init_requires_empty_owner_history'): self.run_init()
        self.assertEqual(len(self.writes), 1)

    def test_second_init_different_manifest_denied(self):
        self.run_init()
        self.identity, self.request = seed_request(self.target, self.owner, b'{"different":true}\n')
        with self.assertRaisesRegex(b.BindingError, 'init_requires_empty_owner_history'): self.run_init()
        self.assertEqual(len(self.writes), 1)

    def test_init_does_not_satisfy_baseline_and_completion_must_match_remote(self):
        self.run_init()
        completion = b.binding_init_readback(self.target, self.identity, digest(self.request))
        _, _, current, _ = b.binding_history(self.root, self.target)
        baseline = dict(self.owner, intent_sha256='f' * 64, kind='STAGE', name='BASELINE_VERIFIED', action='baseline')
        b.binding_admit_operation(self.root, self.target, baseline, current)
        b.binding_require_init_completion(self.root, self.target, current, completion)
        for name in ['PRE_DRAIN_BACKUP_REHEARSED', 'FINAL_V125_PREFLIGHT_PASSED']:
            later = dict(baseline, name=name)
            with self.assertRaisesRegex(b.BindingError, 'fresh_baseline'):
                b.binding_admit_operation(self.root, self.target, later, current)
        altered = copy.deepcopy(completion); altered['result_sha256'] = '0' * 64
        with self.assertRaisesRegex(b.BindingError, 'completion_mismatch'):
            b.binding_require_init_completion(self.root, self.target, current, altered)

    def test_legacy_stage_denied_but_recovery_authority_not_replaced(self):
        stage = dict(self.owner, intent_sha256='f' * 64, kind='STAGE', name='BASELINE_VERIFIED', action='baseline')
        with self.assertRaisesRegex(b.BindingError, 'completed_init'):
            b.binding_admit_operation(self.root, self.target, stage, [])
        recovery = dict(stage, kind='RECOVERY', name='post-v126-stop', action='recover-post-v126-stop')
        with self.assertRaisesRegex(b.BindingError, 'fresh_baseline'):
            b.binding_admit_operation(self.root, self.target, recovery, [])
        b.binding_admit_operation(self.root, self.target, recovery, [stage])
        self.assertFalse(b.binding_policy_b_required(recovery))

    def test_closed_request_schema_and_exact_metadata(self):
        for alter in [lambda x: x.update(extra=True), lambda x: x.update(manifest_size=True),
                      lambda x: x['metadata']['files'][0].update(path='../outside'),
                      lambda x: x['metadata']['directories'].append('secrets')]:
            request = copy.deepcopy(self.request); alter(request)
            with self.assertRaises(b.BindingError): b.binding_init_request(request, self.identity, self.target)
        self.assertEqual(self.names(), {'lock', 'run.json'})

    def test_result_missing_or_forged_cannot_be_copied_from_exact_local_hash(self):
        self.run_init()
        result_path = self.root / (digest(self.identity) + '.result.json')
        result_path.unlink()
        with self.assertRaisesRegex(b.BindingError, 'prior_outcome_unknown'):
            b.binding_init_readback(self.target, self.identity, digest(self.request))
        self.assertEqual(len(self.writes), 1)

    def test_ack_loss_preserves_success_and_copy_only_readback_under_same_lock(self):
        def lost_ack(result):
            probe = os.open(self.root / 'lock', os.O_RDWR)
            try:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            finally:
                os.close(probe)
            self.assertTrue((self.root / (digest(self.identity) + '.result.json')).exists())
            raise BrokenPipeError('synthetic ACK channel loss')
        with self.assertRaises(BrokenPipeError): self.run_init(ack_init=lost_ack)
        result = b.binding_init_readback(self.target, self.identity, digest(self.request))
        self.assertEqual(result['result']['outcome'], 'SUCCEEDED')
        self.assertEqual(len(self.writes), 1)

    def test_round_and_writer_time_bounds_never_produce_success(self):
        import time
        for boundary in ('early', 'late', 'writer'):
            with self.subTest(boundary=boundary):
                now = [100.0]
                self.gate = Transcript(self)
                def advance(count):
                    if (boundary, count) in [('early', 1), ('late', 2)]: now[0] += 300
                self.gate.on_check = advance
                def slow_writer(identity, request, late):
                    now[0] += 300
                    return attestation(identity, request)
                with patch.object(time, 'monotonic', lambda: now[0]):
                    with self.assertRaises(b.BindingError):
                        self.run_init(write_init=slow_writer if boundary == 'writer' else self.writer)
                self.assertFalse(any(self.root.glob('*.result.json')))
                self.assertEqual(bool(list(self.root.glob('*.start.json'))), boundary != 'early')
                for path in self.root.glob(digest(self.identity) + '.*'): path.unlink()

    def test_extra_own_record_during_durable_intent_is_not_adopted(self):
        original = b.binding_create
        def corrupt(path, value):
            original(path, value)
            if str(path).endswith('.request.json'):
                original(self.root / 'unexpected.start.json', {})
        with patch.object(b, 'binding_create', corrupt):
            with self.assertRaisesRegex(b.BindingError, 'unexpected_inflight_history_delta'): self.run_init()
        self.assertEqual(self.writes, [])
        self.assertFalse(any(self.root.glob('*.result.json')))

    def test_init_request_cannot_enter_process_worker(self):
        import ctypes
        class Lib:
            @staticmethod
            def prctl(*args): return 0
        with patch.object(ctypes, 'CDLL', return_value=Lib()):
            with self.assertRaisesRegex(b.BindingError, 'init_requires_metadata_handshake'):
                b.binding_supervise(self.target, self.identity, ['not-a-real-command'], policy_b_gate=self.gate)
        self.assertEqual(self.names(), {'lock', 'run.json'})
        self.assertEqual(self.writes, [])

    def test_init_gate_removal_negative_controls_fail_refusal_regressions(self):
        import ast
        module_path = ROOT / 'v126-operation-bindings.py'
        source = module_path.read_text()
        function = next(node for node in ast.parse(source).body
                        if isinstance(node, ast.FunctionDef) and node.name == 'binding_initialize')
        calls = [node for node in ast.walk(function) if isinstance(node, (ast.Expr, ast.Assign)) and
                 isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name) and
                 node.value.func.id == 'binding_policy_b_check']
        calls.sort(key=lambda item: item.lineno)
        self.assertEqual(len(calls), 2)
        original = globals()['b']
        for index, call in enumerate(calls):
            with self.subTest(removed=index):
                lines = source.splitlines(keepends=True)
                replacement = 'pass' if index == 0 else "late = dict(barrier='R0',operational=True,qualification_sha256='d'*64)"
                lines[call.lineno - 1:call.end_lineno] = [' ' * call.col_offset + replacement + '\n']
                mutant_path = Path(self.tmp.name) / ('init-mutant-' + str(index) + '.py')
                mutant_path.write_text(''.join(lines))
                spec = importlib.util.spec_from_file_location('init_mutant_' + str(index), mutant_path)
                mutant = importlib.util.module_from_spec(spec); spec.loader.exec_module(mutant)
                method = ('test_early_refusal_has_no_canonical_local_or_remote_intent' if index == 0 else
                          'test_late_refusal_preserves_unknown_and_no_writer')
                result = unittest.TestResult()
                try:
                    globals()['b'] = mutant
                    Init(method).run(result)
                finally:
                    globals()['b'] = original
                self.assertFalse(result.wasSuccessful(), 'removed INIT gate escaped its regression')
                self.assertEqual(result.testsRun, 1)

    def test_late_history_gate_removal_reaches_writer_and_fails_its_regression(self):
        import ast
        source=(ROOT/'v126-operation-bindings.py').read_text()
        function=next(node for node in ast.parse(source).body if isinstance(node,ast.FunctionDef) and node.name=='binding_initialize')
        calls=[node for node in ast.walk(function) if isinstance(node,ast.Expr) and isinstance(node.value,ast.Call)
               and isinstance(node.value.func,ast.Name) and node.value.func.id=='binding_history_unchanged'
               and isinstance(node.value.args[-1],ast.Name) and node.value.args[-1].id=='live']
        calls.sort(key=lambda item:item.lineno)
        self.assertEqual(len(calls),2)
        call=calls[0];lines=source.splitlines(keepends=True)
        lines[call.lineno-1:call.end_lineno]=[' '*call.col_offset+'pass  # temporary history-gate control\n']
        path=Path(self.tmp.name)/'init-late-history-mutant.py';path.write_text(''.join(lines))
        spec=importlib.util.spec_from_file_location('init_late_history_mutant',path)
        mutant=importlib.util.module_from_spec(spec);spec.loader.exec_module(mutant)
        original=globals()['b'];result=unittest.TestResult()
        try:
            globals()['b']=mutant
            Init('test_history_changed_during_late_observation_no_writer').run(result)
        finally:globals()['b']=original
        self.assertEqual(result.testsRun,1)
        self.assertEqual(len(result.failures),1,'removing pre-WRITE_INIT history replay escaped regression')
        self.assertEqual(result.errors,[])

    def test_lock_release_or_inode_replacement_prevents_dispatch(self):
        original = b.binding_history_unchanged
        def check(root, fd, expected):
            if self.gate.calls == 2: fcntl.flock(fd, fcntl.LOCK_UN)
            return original(root, fd, expected)
        with patch.object(b, 'binding_history_unchanged', check):
            with self.assertRaisesRegex(b.BindingError, 'lock_not_held'): self.run_init()
        self.assertEqual(self.writes, []); self.unknown()


if __name__ == '__main__':
    unittest.main(verbosity=2)
