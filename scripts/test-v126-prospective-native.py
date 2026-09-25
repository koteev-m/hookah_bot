#!/usr/bin/env python3
"""Real native sequencer writers/readers over explicitly SYNTHETIC v2 history.

Only accepted INIT and external effect/CI facts are synthetic fixture inputs.
No admission, isolation, custody or live operational result is claimed here.
The manifest/intent/receipt/recovery/authorization/handoff consumers are real.
"""
import copy
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'v126-cutover.sh'


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


client = load('prospective_native_client', 'v126-policy-b-client.py')
history = load('prospective_native_history_fixture', 'test-v126-prospective-history.py')
ci = load('prospective_native_ci_fixture', 'test-v126-release-ci.py')
b = history.b
H = lambda value: hashlib.sha256(value.encode()).hexdigest()
D = lambda value: hashlib.sha256(client.canonical(value)).hexdigest()


class ProspectiveNative(unittest.TestCase):
    def shell(self, body, *args):
        return subprocess.run(['bash', '-c', 'set -Eeuo pipefail\n' + body, 'prospective-native-fixture',
                               *map(str, args)], capture_output=True, text=True, timeout=60,
                              env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))

    def ok(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def native(self, body, *args):
        return self.shell('source "$1"\nload_state "$2"\n' + body, SOURCE, self.state, *args)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='prospective-native-SYNTHETIC-')
        self.base = Path(self.tmp.name).resolve()
        (self.base / 'release-worktree').mkdir()
        self.target = self.base / 'remote-staging'
        self.target.mkdir(mode=0o700)
        self.ok(self.shell('source "$1"; TEST_ROOT="$2"; new_state "$CUTOVER_SCRIPT" seed',
                           ROOT / 'test-v126-cutover.sh', self.base))
        self.manifest = json.loads((self.base / 'state-seed/run.json').read_bytes())
        self.genesis_request, self.basis, _, _, _ = history.fixture(self.target)
        self.manifest.update(format_version=2, epoch=copy.deepcopy(self.basis['epoch']),
                             run_id='synthetic-native-epoch')
        self.state = self.base / 'state-prospective'
        raw = client.canonical(self.manifest)
        request = client.init_request(self.state, raw)
        attestation = client.write_metadata(self.state, raw, request)
        result = dict(format_version=1, identity=request['identity'], operation_id=attestation['operation_id'],
                      exit=0, outcome='SUCCEEDED', completion='LOCAL_METADATA_ATTESTED',
                      request_sha256=attestation['request_sha256'], attestation=attestation,
                      completed_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
        client.save_completion(self.state, dict(request=request, result=result,
            request_sha256=D(request), result_sha256=D(result)))
        self.init_identity = request['identity']
        self.owner = {key:self.init_identity[key] for key in b.OWNER_FIELDS | b.EPOCH_FIELDS}
        self.root = self.target / '.v126-target-operations'

    def tearDown(self):
        self.tmp.cleanup()

    def baseline(self):
        hashes = ci.seed_ci(self.state)
        names = self.ok(self.native('stage_expected_artifacts BASELINE_VERIFIED')).strip().split(',')
        hashes.update({name:H('SYNTHETIC baseline observation ' + name) for name in names if name not in hashes})
        log = self.state / 'artifacts/1-BASELINE_VERIFIED.operation.log'
        log.write_text(''.join('ARTIFACT\t%s\t%s\n' % pair for pair in sorted(hashes.items())))
        log.chmod(0o400)
        hashes['operation-log'] = client.digest(log.read_bytes())
        artifacts = self.base / 'baseline-artifacts.tsv'
        artifacts.write_text(''.join('%s\t%s\n' % pair for pair in sorted(hashes.items())))
        self.ok(self.native('intent="$(write_stage_intent BASELINE_VERIFIED)"\n'
                            'write_stage_receipt BASELINE_VERIFIED "$3" "$intent"\n'
                            'verify_receipt BASELINE_VERIFIED', artifacts))

    def recovery(self):
        self.baseline()
        self.ok(self.native('''
predecessor="$(verify_receipt BASELINE_VERIFIED)"
token="$(hash_text "${PRE_V126_ROLLBACK_TOKEN}")"
write_recovery_intent_and_terminal pre-v126 "$token" BASELINE_VERIFIED "$predecessor"
log="${STATE_DIR}/recovery/pre-v126.operation.log"
printf 'ARTIFACT\\trecovery-pre-v126\\t%s\\n' "$(hash_text SYNTHETIC-v125-recovery-proof)" > "$log"
chmod 0400 "$log"
write_recovery_receipt pre-v126 BASELINE_VERIFIED "$predecessor" "$token" "$log"
verify_recovery_receipt pre-v126
verify_reconciliation_recovery_intent pre-v126
'''))

    def rewrite(self, path, doc):
        """Rehash test-owned tampering to exercise semantics beyond checksums."""
        path.chmod(0o600)
        path.write_bytes(client.canonical(doc))
        path.chmod(0o400)
        checksum = Path(str(path) + '.sha256')
        checksum.chmod(0o600)
        checksum.write_text(client.digest(path.read_bytes()) + '\n')
        checksum.chmod(0o400)

    def read(self, relative):
        return json.loads((self.state / relative).read_bytes())

    def seed_target_genesis_and_recovery(self):
        # Test-only accepted V/root history; all subsequent readers are production.
        request = self.genesis_request
        request.update(run_id=self.owner['run_id'], source_sha=self.owner['release_sha'],
                       script_sha256=self.owner['script_sha256'],
                       next_init_manifest_sha256=self.init_identity['intent_sha256'])
        fixture = history.ProspectiveHistory()
        fixture.target, fixture.root = self.target, self.root
        fixture.owner, fixture.request, fixture.basis = self.owner, request, self.basis
        fixture.identity = b.binding_genesis_identity(request)
        fixture.seed()
        fixture.operation(kind='RECOVERY', name='pre-v126', action='recover-pre-v126',
                          intent=client.digest((self.state/'recovery/pre-v126.intent.json').read_bytes()))

    def handoff(self):
        self.next_owner = dict(self.owner, run_id='synthetic-native-next', release_sha='e'*40)
        self.handoff_path = self.base / 'handoff.json'
        doc = dict(format_version=1, owner=self.owner, next_owner=self.next_owner,
            terminal_receipt_sha256=client.digest((self.state/'recovery/pre-v126.receipt.json').read_bytes()),
            target_sha256=H(str(self.target)), operational_version='V125',
            backend_image=self.manifest['v125_image_tag'], image_id=self.ok(self.native('printf \"%s\\n\" \"$V125_IMAGE_ID\"')).strip(),
            environment_sha256='1'*64, compose_sha256='2'*64, caddy_runtime_sha256='3'*64,
            config_owner='root:root', restart_policy='unless-stopped', handoff_approved_and_applied=True,
            approval_id='SYNTHETIC-native-handoff', observed_at='2026-09-25T00:01:00Z')
        b.binding_create(self.handoff_path, doc)
        return doc

    def retire(self):
        return subprocess.run(['bash', str(SOURCE), 'retire-target', '--target', str(self.target),
            '--state-dir', str(self.state), '--handoff-file', str(self.handoff_path), '--operational-version', 'V125',
            '--next-run-id', self.next_owner['run_id'], '--next-release-sha', self.next_owner['release_sha'],
            '--next-script-sha256', self.next_owner['script_sha256'],
            '--authorization', 'AUTHORIZE_V126_TARGET_BINDING_RETIREMENT'], capture_output=True, text=True, timeout=60,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))

    def test_real_manifest_baseline_and_gate_authorization_roundtrip(self):
        self.baseline()
        self.assertEqual(client.require_completion(self.state)['request']['identity'], self.init_identity)
        self.assertEqual(self.read('intents/01-BASELINE_VERIFIED.intent.json')['epoch'], self.basis['epoch'])
        self.assertEqual(self.read('receipts/01-BASELINE_VERIFIED.receipt.json')['epoch'], self.basis['epoch'])
        self.ok(self.native('write_authorization A "$(gate_token A)"\nauthorization_hash_for_stage PRE_DRAIN_BACKUP_REHEARSED'))
        self.assertEqual(self.read('authorizations/GATE_A.authorization.json')['epoch'], self.basis['epoch'])

    def test_receipt_epoch_tamper_and_downgrade_refuse_after_rehash(self):
        self.baseline()
        path = self.state/'receipts/01-BASELINE_VERIFIED.receipt.json'
        original = json.loads(path.read_bytes())
        for change in ('epoch_id','domain_identity_sha256','predecessor_index_sha256','omit'):
            doc = copy.deepcopy(original)
            if change == 'omit':doc.pop('epoch')
            else:doc['epoch'][change]='9'*64
            self.rewrite(path,doc)
            result=self.native('verify_receipt BASELINE_VERIFIED')
            with self.subTest(change=change):
                self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
                self.assertRegex(result.stderr,'epoch|schema')

    def test_intent_epoch_tamper_refuses_even_when_receipt_hash_is_updated(self):
        self.baseline()
        path=self.state/'intents/01-BASELINE_VERIFIED.intent.json'
        doc=json.loads(path.read_bytes());doc['epoch']['epoch_id']='9'*64;self.rewrite(path,doc)
        receipt=self.state/'receipts/01-BASELINE_VERIFIED.receipt.json'
        doc=json.loads(receipt.read_bytes());doc['intent_sha256']=client.digest(path.read_bytes());self.rewrite(receipt,doc)
        result=self.native('verify_receipt BASELINE_VERIFIED')
        self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertRegex(result.stderr,'epoch|intent')

    def test_authorization_cross_epoch_refuses_after_rehash(self):
        self.baseline()
        self.ok(self.native('write_authorization A "$(gate_token A)"'))
        path=self.state/'authorizations/GATE_A.authorization.json'
        doc=json.loads(path.read_bytes());doc['epoch']['domain_identity_sha256']='9'*64;self.rewrite(path,doc)
        result=self.native('authorization_hash_for_stage PRE_DRAIN_BACKUP_REHEARSED')
        self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertRegex(result.stderr,'epoch')

    def test_real_recovery_and_terminal_roundtrip(self):
        self.recovery()
        for relative in ('recovery/pre-v126.intent.json','recovery/pre-v126.receipt.json','run-terminal.json'):
            self.assertEqual(self.read(relative)['epoch'],self.basis['epoch'])
        result=self.native('status_command status --state-dir "$2"')
        self.ok(result)
        self.assertIn('recovery_records=COMPLETED',result.stdout)
        self.assertIn('terminal=true',result.stdout)
        self.assertNotIn('INVALID_EVIDENCE',result.stdout)

    def test_recovery_intent_epoch_tamper_blocks_native_consumers(self):
        self.recovery()
        path=self.state/'recovery/pre-v126.intent.json'
        doc=json.loads(path.read_bytes());doc['epoch']['epoch_id']='9'*64;self.rewrite(path,doc)
        receipt=self.state/'recovery/pre-v126.receipt.json'
        doc=json.loads(receipt.read_bytes());doc['intent_sha256']=client.digest(path.read_bytes());self.rewrite(receipt,doc)
        for consumer in ('verify_recovery_receipt pre-v126','verify_reconciliation_recovery_intent pre-v126'):
            result=self.native(consumer)
            with self.subTest(consumer=consumer):
                self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
                self.assertRegex(result.stderr,'epoch|recovery evidence inventory is invalid')
        self.assertEqual(self.ok(self.native('read_recovery_state')).strip(),'INVALID_EVIDENCE')

    def test_native_recovery_retirement_cli_supports_same_epoch_handoff(self):
        self.recovery();self.seed_target_genesis_and_recovery();self.handoff()
        result=self.retire();self.ok(result)
        self.assertIn('TARGET_BINDING_RETIRED',result.stdout)
        self.assertEqual(b.binding_chain(self.root,self.target)[0],self.next_owner)

    def test_native_recovery_retirement_cli_refuses_cross_epoch_without_writes(self):
        self.recovery();self.seed_target_genesis_and_recovery();doc=self.handoff()
        doc['next_owner']['epoch_id']='9'*64
        self.handoff_path.chmod(0o600);self.handoff_path.write_bytes(client.canonical(doc));self.handoff_path.chmod(0o400)
        before={path:path.read_bytes() for path in self.base.rglob('*') if path.is_file()}
        result=self.retire()
        self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertNotIn('TARGET_BINDING_RETIRED',result.stdout)
        self.assertEqual(b.binding_chain(self.root,self.target)[0],self.owner)
        self.assertEqual(before,{path:path.read_bytes() for path in self.base.rglob('*') if path.is_file()})


if __name__ == '__main__':
    unittest.main()
