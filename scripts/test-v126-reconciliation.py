#!/usr/bin/env python3
"""Actual immutable reconciliation protocol, synthetic records/post-state observer.

Only the action-specific observer is a fixture here. No SSH, daemon or availability
claim; hosted integration separately exercises the production read-only observer.
"""
import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fixture', ROOT / 'test-v126-bindings.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
b = fixture.bindings


class Reconciliation(fixture.Bindings):
    def prepare(self, **changes):
        changes.setdefault('action', 'final-public-gates')
        changes.setdefault('log_text', 'ARTIFACT\tfinal-public-gates\t' + '6'*64 + '\n')
        self.operation(**changes)
        start = next(self.root.glob('*.start.json'))
        identity = b.binding_read(start)['identity']
        self.op = identity
        opid = hashlib.sha256(b.binding_canonical(identity)).hexdigest()
        fixture.write(self.root / (opid + '.request.json'), dict(
            format_version=1, identity=identity,
            target_sha256=hashlib.sha256(str(self.target).encode()).hexdigest(),
            args=[str(self.target), self.owner['run_id'], self.owner['release_sha']], environment={}))
        self.observed = 0

    def observer(self, identity, request, operations):
        self.observed += 1
        self.assertEqual(identity, self.op)
        self.assertEqual(request['identity'], identity)
        self.assertEqual(len(operations), 1)
        return dict(outcome='EXACT_COMPLETED_EFFECT', fixture='synthetic-poststate-only')

    def reconcile(self, **changes):
        args = dict(target=self.target, owner=self.owner, kind=self.op['kind'], name=self.op['name'],
                    intent_sha=self.op['intent_sha256'], source_sha=self.owner['script_sha256'],
                    checker_sha=hashlib.sha256(b.binding_embedded_source(fixture.SOURCE.read_bytes(), 'remote_reconciliation_poststate_python')).hexdigest(),
                    actions=[self.op['action']], observe=self.observer)
        args.update(changes)
        return b.binding_reconcile(**args)

    def test_lost_ack_appends_distinct_evidence_without_replaying_or_rewriting(self):
        self.prepare(); before = self.snapshot()
        doc = self.reconcile()
        self.assertEqual(doc['kind'], 'RECONCILED_EFFECT')
        self.assertIs(doc['retry_allowed'], False)
        for path, raw in before.items(): self.assertEqual(path.read_bytes(), raw)
        self.assertEqual(self.observed, 1)
        self.assertEqual(self.reconcile(), doc)
        self.assertEqual(self.observed, 1)
        inventory, unknown, _ = b.binding_inventory(self.root, self.owner)
        self.assertFalse(unknown)
        self.assertEqual(sum(name.startswith('reconciliations/') for name in inventory), 1)
        self.consume('inspect')
        self.approval(); self.consume()
        self.assertEqual(b.binding_chain(self.root, self.target)[0], self.next)

    def test_missing_result_does_not_mean_not_dispatched(self):
        self.prepare(incomplete=True); before = self.snapshot()
        with self.assertRaises((b.BindingError, FileNotFoundError)): self.reconcile()
        self.assertEqual(self.observed, 0); self.assertEqual(self.snapshot(), before)

    def test_nonzero_result_cannot_be_reconciled_by_matching_current_state(self):
        self.prepare(code=124); before = self.snapshot()
        with self.assertRaises(b.BindingError): self.reconcile()
        self.assertEqual(self.observed, 0); self.assertEqual(self.snapshot(), before)

    def test_wrong_source_intent_action_or_owner_refuses_before_observation(self):
        self.prepare(); before = self.snapshot()
        for change in (dict(source_sha='0'*64), dict(intent_sha='0'*64), dict(actions=['other']),
                       dict(owner=self.next, source_sha=self.next['script_sha256'])):
            with self.subTest(change=change), self.assertRaises(b.BindingError): self.reconcile(**change)
        self.assertEqual(self.observed, 0); self.assertEqual(self.snapshot(), before)

    def test_original_request_missing_or_mutated_is_not_reconstructible(self):
        self.prepare(); request = next(self.root.glob('*.request.json'))
        doc = b.binding_read(request); doc['args'][0] = '/wrong-target'
        request.chmod(0o600); fixture.write(request, doc)
        with self.assertRaises(b.BindingError): self.reconcile()
        request.unlink()
        with self.assertRaises(FileNotFoundError): self.reconcile()
        self.assertEqual(self.observed, 0)

    def test_mixed_poststate_does_not_append_completion(self):
        self.prepare(); before = self.snapshot()
        with self.assertRaises(b.BindingError):
            self.reconcile(observe=lambda *_: dict(outcome='INSUFFICIENT_EVIDENCE'))
        self.assertEqual(self.snapshot(), before)

    def test_reconciliation_hash_and_metadata_corruption_blocks_retirement(self):
        self.prepare(); doc = self.reconcile(); self.approval()
        path = self.root / 'reconciliations' / (doc['operation_id'] + '.json')
        doc['operations'][0]['files'][doc['operation_id'] + '.log'] = '0' * 64
        path.chmod(0o600); fixture.write(path, doc)
        with self.assertRaises(b.BindingError): self.consume()

    def test_target_lock_blocks_separate_reconciliation(self):
        import fcntl
        self.prepare(); before = self.snapshot()
        with (self.root / 'lock').open() as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError): self.reconcile()
        self.assertEqual(self.snapshot(), before); self.assertEqual(self.observed, 0)

    def test_typed_terminal_completion_uses_real_canonical_verifier_and_retirement(self):
        state = self.seed(19)
        stage = 'FINAL_PUBLIC_GATES_PASSED'
        result = self.shell('source "$1"; load_state "$2"; write_stage_intent FINAL_PUBLIC_GATES_PASSED', fixture.SOURCE, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        intent_sha = result.stdout.strip()
        original = state / 'artifacts' / ('20-' + stage + '.failed.log')
        original.write_text('lost SSH acknowledgement\n'); original.chmod(0o400)
        self.prepare(intent_sha=intent_sha)
        record = self.reconcile(); before = self.snapshot()
        bundle = self.base / 'bundle.json'
        fixture.write(bundle, b.binding_export_reconciliation(self.root, record))
        result = self.shell('source "$1"; load_state "$2"; write_reconciled_stage_completion FINAL_PUBLIC_GATES_PASSED "$3"; status_command status --state-dir "$2"',
                            fixture.SOURCE, state, bundle)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('FINAL_PUBLIC_GATES_PASSED=RECONCILED_EFFECT', result.stdout)
        self.assertIn('canonical_execution=COMPLETE', result.stdout)
        self.assertFalse((state / 'receipts' / ('20-' + stage + '.receipt.json')).exists())
        self.receipt_sha = b.binding_hash(state / 'receipts' / ('20-' + stage + '.reconciliation.json'))
        self.approval()
        retired = self.retire_cli(state, 'V126')
        self.assertEqual(retired.returncode, 0, retired.stderr)
        transfer = b.binding_read(next((self.root / 'transfers').iterdir()))
        self.assertEqual(transfer['terminal_kind'], 'RECONCILED_EFFECT')
        self.assertEqual(transfer['next_kind'], 'CUTOVER')
        for path, raw in before.items(): self.assertEqual(path.read_bytes(), raw)
        self.assertEqual(original.read_text(), 'lost SSH acknowledgement\n')

    def test_typed_nonterminal_completion_binds_next_intent_without_replay(self):
        import json
        state = self.seed(3)
        stage = 'PUBLIC_DRAIN_ACTIVE'
        result = self.shell('source "$1"; load_state "$2"; write_stage_intent PUBLIC_DRAIN_ACTIVE', fixture.SOURCE, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        original = state / 'artifacts/4-PUBLIC_DRAIN_ACTIVE.failed.log'
        original.write_text('lost drain acknowledgement\n'); original.chmod(0o400)
        self.prepare(name=stage, action='public-drain-on', intent_sha=result.stdout.strip(),
                     log_text='ARTIFACT\tpublic-drain-active\t'+'6'*64+'\n')
        bundle = self.base / 'drain-bundle.json'
        fixture.write(bundle, b.binding_export_reconciliation(self.root, self.reconcile()))
        result = self.shell('source "$1"; load_state "$2"; write_reconciled_stage_completion PUBLIC_DRAIN_ACTIVE "$3"; write_stage_intent V125_BACKEND_STOPPED',
                            fixture.SOURCE, state, bundle)
        self.assertEqual(result.returncode, 0, result.stderr)
        completed = state / 'receipts/04-PUBLIC_DRAIN_ACTIVE.reconciliation.json'
        next_intent = json.loads((state / 'intents/05-V125_BACKEND_STOPPED.intent.json').read_bytes())
        self.assertEqual(next_intent['predecessor_receipt_sha256'], b.binding_hash(completed))
        self.assertEqual(self.observed, 1)
        self.assertFalse((state / 'receipts/04-PUBLIC_DRAIN_ACTIVE.receipt.json').exists())

    def test_missing_or_changed_original_manual_bytes_refuse(self):
        state = self.base / 'local'; (state / 'artifacts').mkdir(parents=True)
        manifest = dict(run_id='synthetic-run', release_sha='a'*40)
        hashes = {'manual-smoke-evidence': '0'*64}
        with self.assertRaises(FileNotFoundError): b.binding_retained_local_artifacts(state, manifest, hashes)
        path = state / 'artifacts' / 'manual-smoke-evidence.json'
        fixture.write(path, dict(manifest, assertions=['synthetic-fixture-only']))
        with self.assertRaises(b.BindingError): b.binding_retained_local_artifacts(state, manifest, hashes)
        hashes['manual-smoke-evidence'] = b.binding_hash(path)
        b.binding_retained_local_artifacts(state, manifest, hashes)

    def test_typed_v125_recovery_uses_terminal_validator_and_own_handoff(self):
        state = self.seed(1)
        result = self.shell('''
source "$1"
load_state "$2"
predecessor_hash="$(verify_receipt BASELINE_VERIFIED)" || exit $?
token_hash="$(hash_text "${PRE_V126_ROLLBACK_TOKEN}")" || exit $?
write_recovery_intent_and_terminal pre-v126 "${token_hash}" BASELINE_VERIFIED "${predecessor_hash}" || exit $?
printf '%s\\n' "${V125_IMAGE_ID}"
''', fixture.SOURCE, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.image = result.stdout.strip().splitlines()[-1]
        intent = state / 'recovery/pre-v126.intent.json'
        self.prepare(kind='RECOVERY', name='pre-v126', action='recover-pre-v126',
                     intent_sha=b.binding_hash(intent),
                     log_text='ARTIFACT\trecovery-pre-v126\t' + '6'*64 + '\n')
        original = state / 'recovery/pre-v126.operation.log'
        original.write_text('original recovery transport loss\n'); original.chmod(0o400)
        before = {p: p.read_bytes() for p in state.rglob('*') if p.is_file()}
        bundle = self.base / 'v125-bundle.json'
        fixture.write(bundle, b.binding_export_reconciliation(self.root, self.reconcile()))
        result = self.shell('source "$1"; load_state "$2"; write_reconciled_recovery_completion pre-v126 "$3"; status_command status --state-dir "$2"',
                            fixture.SOURCE, state, bundle)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('canonical_execution=TERMINAL_RECOVERY_RECONCILED', result.stdout)
        self.assertNotIn('V126_MAINTENANCE_CONFIG_PREPARED=PASS', result.stdout)
        self.assertFalse((state / 'recovery/pre-v126.receipt.json').exists())
        self.receipt_sha = b.binding_hash(state / 'recovery/pre-v126.reconciliation.json')
        self.approval('V125')
        retired = self.retire_cli(state, 'V125')
        self.assertEqual(retired.returncode, 0, retired.stderr)
        transfer = b.binding_read(next((self.root / 'transfers').iterdir()))
        self.assertEqual(transfer['terminal_kind'], 'RECONCILED_EFFECT')
        for path, raw in before.items(): self.assertEqual(path.read_bytes(), raw)

    def test_empty_original_artifact_set_cannot_create_typed_completion(self):
        state = self.seed(19)
        result = self.shell('source "$1"; load_state "$2"; write_stage_intent FINAL_PUBLIC_GATES_PASSED', fixture.SOURCE, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.prepare(intent_sha=result.stdout.strip(), log_text='no artifact was produced\n')
        original = state / 'artifacts' / '20-FINAL_PUBLIC_GATES_PASSED.failed.log'
        original.write_text('lost ACK\n'); original.chmod(0o400)
        bundle = self.base / 'empty-bundle.json'
        fixture.write(bundle, b.binding_export_reconciliation(self.root, self.reconcile()))
        before = {p: p.read_bytes() for p in state.rglob('*') if p.is_file()}
        with self.assertRaises(b.BindingError):
            b.binding_write_completion(state, 'STAGE', 'FINAL_PUBLIC_GATES_PASSED', 20, bundle, fixture.SOURCE, 'final-public-gates')
        self.assertEqual({p: p.read_bytes() for p in state.rglob('*') if p.is_file()}, before)

    def test_full_dr_requires_original_local_boundary_before_and_after_completion(self):
        import json
        state = self.seed(2)
        manifest = json.loads((state / 'run.json').read_bytes())
        source_boundary = self.base / 'operator-boundary.json'
        fixture.write(source_boundary, dict(format_version=1, run_id=manifest['run_id'], release_sha=manifest['release_sha'],
            backup_phase='pre-drain', result_category='DR_PREREQUISITES_ACCEPTED',
            accepted_data_loss_boundary='ALL_WRITES_AFTER_PRE_DRAIN_BACKUP_MAY_BE_LOST',
            accepted_recovery_point_utc='2026-09-09T00:00:00Z'))
        result = self.shell('''
source "$1"; load_state "$2"
validate_dr_boundary "$3" "${STATE_DIR}/recovery/dr-boundary.json" pre-drain || exit $?
previous="$(verify_receipt PRE_DRAIN_BACKUP_REHEARSED)" || exit $?
token="$(hash_text "${FULL_DR_VERIFY_TOKEN}")" || exit $?
write_recovery_intent_and_terminal verify-full-dr "${token}" PRE_DRAIN_BACKUP_REHEARSED "${previous}"
''', fixture.SOURCE, state, source_boundary)
        self.assertEqual(result.returncode, 0, result.stderr)
        boundary = state / 'recovery/dr-boundary.json'
        boundary_raw = boundary.read_bytes()
        artifacts = {'dr-boundary': b.binding_hash(boundary), 'dr-selected-backup': '7'*64,
                     'dr-selected-inventory': '8'*64, 'recovery-full-dr-prerequisites': '9'*64}
        self.prepare(kind='RECOVERY', name='verify-full-dr', action='verify-full-dr',
                     intent_sha=b.binding_hash(state / 'recovery/verify-full-dr.intent.json'),
                     log_text=''.join('ARTIFACT\t'+key+'\t'+value+'\n' for key,value in artifacts.items()))
        request_path = next(self.root.glob('*.request.json'))
        request = b.binding_read(request_path)
        request['args'] += ['synthetic:'+'a'*40, 'pre-drain', artifacts['dr-selected-backup'],
                            artifacts['dr-selected-inventory'], artifacts['dr-boundary'], 'NONE']
        request_path.chmod(0o600); fixture.write(request_path, request)
        original = state / 'recovery/verify-full-dr.operation.log'
        original.write_text('original full-DR verification lost ACK\n'); original.chmod(0o400)
        bundle = self.base / 'dr-bundle.json'
        fixture.write(bundle, b.binding_export_reconciliation(self.root, self.reconcile()))
        command = 'source "$1"; load_state "$2"; write_reconciled_recovery_completion verify-full-dr "$3"'
        boundary.unlink()
        missing = self.shell(command, fixture.SOURCE, state, bundle)
        self.assertNotEqual(missing.returncode, 0)
        self.assertFalse((state / 'recovery/verify-full-dr.reconciliation.json').exists())
        b.binding_create_raw(boundary, boundary_raw)
        success = self.shell(command, fixture.SOURCE, state, bundle)
        self.assertEqual(success.returncode, 0, success.stderr)
        for corruption in ('changed', 'missing'):
            if corruption == 'changed':
                boundary.chmod(0o600)
                changed = json.loads(boundary_raw); changed['accepted_recovery_point_utc'] = '2026-09-08T00:00:00Z'
                fixture.write(boundary, changed)
            else:
                boundary.unlink()
            invalid = self.shell('source "$1"; load_state "$2"; verify_recovery_receipt verify-full-dr', fixture.SOURCE, state)
            self.assertNotEqual(invalid.returncode, 0, corruption)


if __name__ == '__main__':
    # Base fixture methods remain independently covered by test-v126-bindings.py.
    suite = unittest.TestSuite(Reconciliation(name) for name in Reconciliation.__dict__ if name.startswith('test_'))
    raise SystemExit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
