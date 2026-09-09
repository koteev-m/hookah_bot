#!/usr/bin/env python3
"""Actual binding verifier/CLI, synthetic immutable records and receipt fixtures.

No daemon/SSH/network. The Linux supervisor suite independently checks transfer
consumption with actual child processes. Applied handoff fields are operator
attestation fixtures, not a fresh runtime observation.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'v126-cutover.sh'
HELPER = ROOT / 'v126-operation-bindings.py'
spec = importlib.util.spec_from_file_location('bindings', HELPER)
bindings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bindings)
EXPECTED = {'baseline-caddy', 'baseline-env', 'database-target-identity', 'database-url-binding',
            'local-baseline', 'main-actions', 'maintenance-identities', 'remote-admission-source',
            'remote-compose-source', 'remote-maintenance-check-source', 'staging-baseline'}


def write(path, value, mode=0o400):
    path.write_bytes(bindings.binding_canonical(value))
    path.chmod(mode)


class Bindings(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='v126-binding-')
        self.base = Path(self.tmp.name).resolve()
        self.target = self.base / 'remote-staging'
        self.target.mkdir(mode=0o700)
        (self.base / 'release-worktree').mkdir()
        self.root = self.target / '.v126-target-operations'
        self.root.mkdir(mode=0o700)
        (self.root / 'lock').write_text('')
        (self.root / 'lock').chmod(0o600)
        self.owner = dict(run_id='repair-current', release_sha='a' * 40,
                          script_sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest())
        self.next = dict(run_id='repair-next-run', release_sha='b' * 40, script_sha256='c' * 64)
        self.receipt_sha = 'd' * 64
        self.image = 'sha256:' + 'e' * 64
        write(self.root / 'run.json', self.owner)
        self.handoff = self.base / 'handoff.json'

    def tearDown(self):
        self.tmp.cleanup()

    def operation(self, name='FINAL_PUBLIC_GATES_PASSED', kind='STAGE', code=0, incomplete=False):
        identity = dict(self.owner, intent_sha256='f' * 64, kind=kind, name=name, action='synthetic')
        op = hashlib.sha256(bindings.binding_canonical(identity)).hexdigest()
        write(self.root / (op + '.start.json'), dict(identity=identity, operation_id=op,
              started_at='2026-09-09T00:00:00+00:00', boot_id='synthetic-boot'))
        log = self.root / (op + '.log')
        log.write_text('synthetic operation\n');log.chmod(0o400)
        if not incomplete:
            write(self.root / (op + '.result.json'), dict(identity=identity, operation_id=op, exit=code,
                  outcome='SUCCEEDED' if code == 0 else 'UNKNOWN', children='REAPED',
                  log_sha256=bindings.binding_hash(log), completed_at='2026-09-09T00:00:01+00:00'))

    def approval(self, version='V126', **changes):
        release = self.owner['release_sha'] if version == 'V126' else 'f577934691a1a7a79ba327c54e2055425142b7be'
        value = dict(format_version=1, owner=self.owner, next_owner=self.next,
                     terminal_receipt_sha256=self.receipt_sha,
                     target_sha256=hashlib.sha256(str(self.target).encode()).hexdigest(),
                     operational_version=version, backend_image='synthetic:' + release, image_id=self.image,
                     environment_sha256='1'*64, compose_sha256='2'*64, caddy_runtime_sha256='3'*64,
                     config_owner='root:root', restart_policy='unless-stopped',
                     handoff_approved_and_applied=True, approval_id='synthetic-approval',
                     observed_at='2026-09-09T00:00:02Z')
        value.update(changes)
        if self.handoff.exists():self.handoff.chmod(0o600)
        write(self.handoff, value)

    def consume(self, mode='retire', version='V126'):
        args = ['consumer', str(self.target), *self.owner.values(), self.receipt_sha, str(self.handoff),
                *self.next.values(), version, self.image, mode]
        with patch.object(sys, 'argv', args):
            bindings.binding_entry(mode)

    def snapshot(self):
        return {p: p.read_bytes() for p in self.root.rglob('*') if p.is_file()}

    def shell(self, code, *args):
        return subprocess.run(['bash', '-c', code, 'binding-fixture', *map(str,args)],
                              capture_output=True, text=True, timeout=40)

    def seed(self, count):
        result = self.shell('source "$1"; TEST_ROOT="$2"; new_state "$CUTOVER_SCRIPT" binding; seed_chain "$NEW_STATE" "$3"',
                            ROOT/'test-v126-cutover.sh', self.base, count)
        self.assertEqual(result.returncode,0,result.stderr)
        state=self.base/'state-binding'
        manifest=json.loads((state/'run.json').read_text())
        self.owner={key:manifest[key] for key in self.owner}
        (self.root/'run.json').chmod(0o600)
        write(self.root/'run.json',self.owner)
        self.image=manifest['v126_image_id']
        return state

    def seed_v125_recovery(self):
        state = self.seed(1)
        result = self.shell('''
source "$1"
load_state "$2"
predecessor_hash="$(verify_receipt BASELINE_VERIFIED)" || exit $?
token_hash="$(hash_text "${PRE_V126_ROLLBACK_TOKEN}")" || exit $?
write_recovery_intent_and_terminal pre-v126 "${token_hash}" BASELINE_VERIFIED "${predecessor_hash}" || exit $?
log="${STATE_DIR}/recovery/pre-v126.operation.log"
printf 'ARTIFACT\\trecovery-pre-v126\\t%s\\n' "$(hash_text synthetic-v125-recovery-proof)" > "${log}"
chmod 0400 "${log}"
write_recovery_receipt pre-v126 BASELINE_VERIFIED "${predecessor_hash}" "${token_hash}" "${log}" || exit $?
verify_recovery_receipt pre-v126 || exit $?
printf '%s\\n' "${V125_IMAGE_ID}"
''', SOURCE, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.receipt_sha = bindings.binding_hash(state / 'recovery/pre-v126.receipt.json')
        self.image = result.stdout.strip().splitlines()[-1]
        self.operation(name='pre-v126', kind='RECOVERY')
        self.approval('V125')
        return state

    def retire_cli(self, state, version):
        return subprocess.run(
            ['bash', str(SOURCE), 'retire-target', '--target', str(self.target), '--state-dir', str(state),
             '--handoff-file', str(self.handoff), '--operational-version', version,
             '--next-run-id', self.next['run_id'], '--next-release-sha', self.next['release_sha'],
             '--next-script-sha256', self.next['script_sha256'],
             '--authorization', 'AUTHORIZE_V126_TARGET_BINDING_RETIREMENT'],
            capture_output=True, text=True, timeout=40,
        )

    def assert_cli_refuses_without_writes(self, state, version, reason):
        before = {p: p.read_bytes() for p in self.base.rglob('*') if p.is_file()}
        result = self.retire_cli(state, version)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertRegex(result.stderr, reason)
        self.assertNotIn('TARGET_BINDING_RETIRED', result.stdout)
        self.assertEqual(bindings.binding_chain(self.root, self.target)[0], self.owner)
        self.assertEqual({p: p.read_bytes() for p in self.base.rglob('*') if p.is_file()}, before)

    def rewrite_document_and_checksum(self, path, value):
        path.chmod(0o600)
        write(path, value)
        checksum = Path(str(path) + '.sha256')
        checksum.chmod(0o600)
        checksum.write_text(bindings.binding_hash(path) + '\n')
        checksum.chmod(0o400)

    def test_failed_binding_source_producer_is_not_masked(self):
        result=self.shell('source "$1"; remote_operation_bindings_python() { printf partial; return 42; }; if payload="$(remote_operation_python)"; then echo ACCEPTED; else exit 1; fi',SOURCE)
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('ACCEPTED',result.stdout)

    def test_embedded_source_is_exact(self):
        result=self.shell('source "$1"; remote_operation_bindings_python',SOURCE)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.encode(),HELPER.read_bytes())

    def test_baseline_has_eleven_named_plus_operation_log_and_rejects_legacy(self):
        result=self.shell('source "$1"; stage_expected_artifacts BASELINE_VERIFIED',SOURCE)
        self.assertEqual(set(result.stdout.strip().split(',')),EXPECTED)
        self.assertEqual(len(EXPECTED),11)
        row=next(line for line in (ROOT.parent/'docs/V126_STAGING_CUTOVER_CONTRACT.md').read_text().splitlines() if line.startswith('| 1 | `BASELINE_VERIFIED`'))
        self.assertEqual({part.split('`')[1] for part in row.split('|')[4].split(',')},EXPECTED)
        state=self.seed(1)
        receipt=state/'receipts/01-BASELINE_VERIFIED.receipt.json'
        doc=json.loads(receipt.read_text())
        self.assertEqual({item['name'] for item in doc['artifacts']},EXPECTED|{'operation-log'})
        self.assertEqual(len(doc['artifacts']),12)
        doc['artifacts']=[x for x in doc['artifacts'] if x['name']!='database-target-identity']
        receipt.chmod(0o600);write(receipt,doc)
        checksum=Path(str(receipt)+'.sha256');checksum.chmod(0o600)
        checksum.write_text(bindings.binding_hash(receipt)+'\n');checksum.chmod(0o400)
        result=self.shell('source "$1"; load_state "$2"; verify_receipt BASELINE_VERIFIED',SOURCE,state)
        self.assertNotEqual(result.returncode,0)

    def test_successful_transfer_preserves_history_and_rejects_reuse(self):
        self.operation();self.approval();saved=self.snapshot()
        self.consume()
        current,history=bindings.binding_chain(self.root,self.target)
        self.assertEqual(current,self.next);self.assertEqual(history,[self.owner,self.next])
        for path,raw in saved.items():self.assertEqual(path.read_bytes(),raw)
        with self.assertRaises(bindings.BindingError):self.consume()

    def test_unknown_missing_result_and_nonzero_cannot_retire(self):
        for incomplete in (True,False):
            with self.subTest(incomplete=incomplete):
                self.operation(code=42,incomplete=incomplete);self.approval();saved=self.snapshot()
                with self.assertRaises(bindings.BindingError):self.consume()
                self.consume('inspect')
                self.assertEqual(self.snapshot(),saved)
                for p in self.root.glob('*.start.json'):p.unlink()
                for p in self.root.glob('*.result.json'):p.unlink()
                for p in self.root.glob('*.log'):p.unlink()

    def test_terminal_and_explicit_handoff_required(self):
        self.operation(name='BASELINE_VERIFIED');self.approval()
        with self.assertRaises(bindings.BindingError):self.consume()
        self.operation()
        for change in ({'handoff_approved_and_applied':False},{'restart_policy':'no'},
                       {'config_owner':'mac-user:staff'},{'image_id':'sha256:'+'0'*64},
                       {'target_sha256':'0'*64}):
            with self.subTest(change=change):
                self.handoff.chmod(0o600);self.approval(**change)
                with self.assertRaises(bindings.BindingError):self.consume()
        self.handoff.chmod(0o600);self.approval('V125')
        with self.assertRaises(bindings.BindingError):self.consume(version='V126')

    def test_v125_handoff_does_not_require_v126_manual_stage(self):
        self.operation(name='pre-v126',kind='RECOVERY');self.approval('V125')
        self.consume(version='V125')
        self.assertEqual(bindings.binding_chain(self.root,self.target)[0],self.next)

    def test_transfer_corruption_or_new_closed_run_record_blocks(self):
        self.operation();self.approval();self.consume()
        self.operation(name='AFTER_RETIREMENT')
        with self.assertRaises(bindings.BindingError):bindings.binding_chain(self.root,self.target)

    def test_actual_cli_requires_terminal_chain_before_append(self):
        state=self.seed(20)
        receipt=state/'receipts/20-FINAL_PUBLIC_GATES_PASSED.receipt.json'
        self.receipt_sha=bindings.binding_hash(receipt)
        self.operation();self.approval()
        result = self.retire_cli(state, 'V126')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('TARGET_BINDING_RETIRED',result.stdout)
        self.assertEqual(bindings.binding_chain(self.root,self.target)[0],self.next)

    def test_actual_cli_v125_terminal_recovery_retires_and_preserves_native_records(self):
        state = self.seed_v125_recovery()
        before = {p: p.read_bytes() for p in self.base.rglob('*') if p.is_file()}
        result = self.retire_cli(state, 'V125')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('TARGET_BINDING_RETIRED', result.stdout)
        self.assertEqual(bindings.binding_chain(self.root, self.target), (self.next, [self.owner, self.next]))
        for path, raw in before.items():
            self.assertEqual(path.read_bytes(), raw)
        self.assertFalse((state / 'receipts/20-FINAL_PUBLIC_GATES_PASSED.receipt.json').exists())

    def test_actual_cli_v125_missing_predecessor_refuses_retirement(self):
        state = self.seed_v125_recovery()
        receipt = state / 'receipts/01-BASELINE_VERIFIED.receipt.json'
        receipt.unlink()
        Path(str(receipt) + '.sha256').unlink()
        self.assert_cli_refuses_without_writes(state, 'V125', 'predecessor chain is invalid')

    def test_actual_cli_v125_corrupt_predecessor_refuses_retirement(self):
        state = self.seed_v125_recovery()
        receipt = state / 'receipts/01-BASELINE_VERIFIED.receipt.json'
        receipt.chmod(0o600)
        receipt.write_text('{}\n')
        receipt.chmod(0o400)
        self.assert_cli_refuses_without_writes(state, 'V125', 'invalid run evidence')

    def test_actual_cli_v125_valid_but_replaced_predecessor_hash_refuses_retirement(self):
        state = self.seed_v125_recovery()
        receipt = state / 'receipts/01-BASELINE_VERIFIED.receipt.json'
        value = json.loads(receipt.read_text())
        value['completed_at'] = '2026-09-01T00:00:02Z'
        self.rewrite_document_and_checksum(receipt, value)
        valid = self.shell('source "$1"; load_state "$2"; verify_receipt BASELINE_VERIFIED', SOURCE, state)
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.assert_cli_refuses_without_writes(state, 'V125', 'predecessor hash mismatch')

    def test_actual_cli_v125_native_recovery_without_terminal_refuses_retirement(self):
        state = self.seed_v125_recovery()
        (state / 'run-terminal.json').unlink()
        (state / 'run-terminal.json.sha256').unlink()
        valid = self.shell('source "$1"; load_state "$2"; verify_recovery_receipt pre-v126', SOURCE, state)
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.assert_cli_refuses_without_writes(state, 'V125', 'canonical recovery terminal marker')

    def test_actual_cli_v126_complete_receipts_do_not_override_unresolved_read_attempt(self):
        state = self.seed(20)
        self.receipt_sha = bindings.binding_hash(state / 'receipts/20-FINAL_PUBLIC_GATES_PASSED.receipt.json')
        self.operation()
        self.approval()
        attempts = state / 'attempts'
        attempts.mkdir(mode=0o700)
        attempt = attempts / 'baseline.interrupted'
        attempt.mkdir(mode=0o700)
        (attempt / 'started.proof').write_text('phase=READ_ONLY_PRECHECK\ndispatch=NOT_DISPATCHED\n')
        (attempt / 'started.proof').chmod(0o600)
        status = self.shell('bash "$1" status --state-dir "$2"', SOURCE, state)
        self.assertEqual(status.returncode, 0, status.stderr)
        self.assertIn('FINAL_PUBLIC_GATES_PASSED=PASS', status.stdout)
        self.assertIn('read_attempts=RECONCILIATION_REQUIRED', status.stdout)
        self.assertIn('canonical_execution=RECONCILIATION_REQUIRED', status.stdout)
        self.assert_cli_refuses_without_writes(state, 'V126', 'read attempt|unresolved')


if __name__=='__main__':
    unittest.main()
