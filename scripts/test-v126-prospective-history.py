#!/usr/bin/env python3
"""Production history consumers with explicitly SYNTHETIC accepted-V transcripts.

These fixtures establish no operational isolation/provenance. Portable checks use
real protected files and durability barriers; --linux additionally executes the
same genesis/INIT supervisor, namespace watch and permanent flock on Linux.
"""
import copy
import errno
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

b = load('prospective_history_bindings', 'v126-operation-bindings.py')
i = load('prospective_history_init_fixture', 'test-v126-policy-b-init.py')
H = lambda value: hashlib.sha256(value.encode()).hexdigest()
D = lambda value: hashlib.sha256(b.binding_canonical(value)).hexdigest()
CLOCK = dict(utc='2026-09-25T00:00:00Z', error_seconds=1, monotonic_seconds=1, clock_id=H('synthetic-clock'))


def fixture(target):
    info = target.stat()
    selected = dict(path=str(target), device=info.st_dev, inode=info.st_ino, uid=info.st_uid,
                    host_fingerprint='SHA256:' + 'B' * 43)
    predecessor = dict(schema_version=2, kind='authority-predecessor-index', legacy_domain_identity_sha256=H('synthetic-legacy-domain'),
        legacy_target=dict(path='/synthetic-old-target', device=1, inode=2, uid=0, host_fingerprint='SHA256:' + 'A'*43),
        legacy_control_roots=[H('synthetic-old-control')], legacy_resource_roots=[H('synthetic-old-resource')], runs=[
            dict(run_id='v126-cutover-20260909t025024z-724dbe93', historical_outcome='UNKNOWN',
                 retained_references=[H('synthetic-retained724')], missing=['primary-not-available'], intent_sha256=H('synthetic-old-intent')),
            dict(run_id='v126-cutover-20260909t113822z-f7828e09', historical_outcome='FAILED_PRE_ACTIVE_CONFIG_MUTATION_EXTERNALLY_FENCED',
                 retained_references=[H('synthetic-retainedf782')], missing=['primary-not-available'], intent_sha256=None)])
    epoch = dict(schema_version=2, kind='authority-epoch-descriptor', nonce=H('synthetic-new-epoch'), created=CLOCK,
        bootstrap_source=dict(commit='a'*40, tree='c'*40, tooling_sha256='d'*64),
        domain_identity_sha256=H('synthetic-new-domain'), predecessor_index_sha256=D(predecessor),
        owner_scope_decision_sha256=H('synthetic-owner-decision'), control_policy_sha256=H('synthetic-control-policy'))
    pair = dict(epoch_id=D(epoch), domain_identity_sha256=epoch['domain_identity_sha256'])
    owner = dict(run_id='synthetic-new-cutover', release_sha='a'*40, script_sha256='b'*64, **pair)
    init_identity, init_request = i.seed_request(target, owner)
    request = dict(schema_version=2, kind='prospective-isolated-genesis-request', run_id=owner['run_id'],
        source_sha=owner['release_sha'], source_tree='c'*40, script_sha256=owner['script_sha256'], tooling_sha256='d'*64,
        python_version='3.12.3', target=selected, **pair, predecessor_index_sha256=D(predecessor),
        isolation_proof_sha256=H('synthetic-V-accepted-isolation'), next_init_manifest_sha256=init_identity['intent_sha256'],
        created_at='2026-09-25T00:00:00Z', expires_at='2026-09-25T01:00:00Z', nonce=H('synthetic-proposal'))
    basis = dict(schema_version=2, kind='prospective-genesis-basis', epoch=dict(pair, predecessor_index_sha256=D(predecessor)),
                 isolation_proof_sha256=request['isolation_proof_sha256'], predecessor_index=predecessor, epoch_descriptor=epoch)
    return request, basis, owner, init_identity, init_request


class Transcript:
    """Authenticated V outcome test boundary; production verifier tested separately."""
    def __init__(self, request, basis, copy_only=False):
        self.request, self.basis, self.copy_only = request, basis, copy_only
        self.calls = self.writes = 0
    def check(self, identity, target, fd, timeout):
        self.calls += 1
        assert identity == b.binding_genesis_identity(self.request)
        if fd is not None: b.binding_lock_held(Path(target)/'.v126-target-operations', fd)
        return dict(barrier='PROSPECTIVE_ISOLATED_GENESIS' + ('_COPY' if self.copy_only else ''), operational=True,
                    qualification_sha256=self.request['isolation_proof_sha256'], pins_sha256=H('synthetic-pins'),
                    catalogue_head_sha256=H('synthetic-head'), revocation_generation=1,
                    epoch=copy.deepcopy(self.basis['epoch']), prospective_basis=copy.deepcopy(self.basis))
    def before_create(self, identity, target, fd, timeout):
        assert self.calls == 1 and fd is None
    def before_dispatch(self, identity, target, fd, timeout):
        assert self.calls == 2
        b.binding_lock_held(Path(target)/'.v126-target-operations', fd)
    def before_write(self, identity, target, fd, timeout):
        self.writes += 1
        b.binding_lock_held(Path(target)/'.v126-target-operations', fd)


class ProspectiveHistory(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='prospective-history-SYNTHETIC-')
        self.target = Path(self.tmp.name).resolve()/'new-target'
        self.target.mkdir(mode=0o700)
        self.request, self.basis, self.owner, self.init_identity, self.init_request = fixture(self.target)
        self.identity = b.binding_genesis_identity(self.request)
        self.root = self.target/'.v126-target-operations'
    def tearDown(self): self.tmp.cleanup()
    def seed(self, publish=True):
        self.root.mkdir(mode=0o700)
        lock = self.root/'lock'; lock.write_bytes(b.PROSPECTIVE_LOCK_PREFIX+D(self.request).encode()+b'\n'); lock.chmod(0o600)
        intent = dict(format_version=1, identity=self.identity, operation_id=D(self.identity), request_sha256=D(self.request), started_at='2026-09-25T00:00:10+00:00')
        record = b.binding_genesis_owner_record(self.request, self.owner, D(self.basis))
        for name, value in [('genesis.intent.json',intent), ('genesis.request.json',self.request), ('prospective-basis.json',self.basis), ('run.json',record)]:
            b.binding_create(self.root/name, value)
        admission = b.binding_genesis_admission(Transcript(self.request,self.basis).check(self.identity,self.target,None,300),self.request)
        self.result = dict(format_version=2, identity=self.identity, operation_id=D(self.identity), exit=0, outcome='SUCCEEDED',
            completion='PROSPECTIVE_ISOLATED_DOMAIN_BOUND', request_sha256=D(self.request), intent_sha256=D(intent), basis_sha256=D(self.basis),
            owner_sha256=D(record), admissions=[admission,admission], completed_at='2026-09-25T00:00:11+00:00')
        if publish:
            fd=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY)
            try: b.binding_genesis_create_at(fd,'genesis.result.json',self.result)
            finally: os.close(fd)
    def operation(self, *, kind='STAGE', name='BASELINE_VERIFIED', action='baseline', owner=None, intent=None):
        identity=dict(owner or self.owner,intent_sha256=intent or H(kind+name+action),kind=kind,name=name,action=action)
        operation=D(identity)
        b.binding_create(self.root/(operation+'.start.json'),dict(identity=identity,operation_id=operation,started_at='2026-09-25T00:00:20+00:00',boot_id='synthetic-boot'))
        request=dict(format_version=1,identity=identity,target_sha256=H(str(self.target)),args=[str(self.target),identity['run_id'],identity['release_sha']],environment={})
        b.binding_create(self.root/(operation+'.request.json'),request)
        log=self.root/(operation+'.log');log.write_bytes(b'SYNTHETIC test-only effect\n');log.chmod(0o400)
        b.binding_create(self.root/(operation+'.result.json'),dict(identity=identity,operation_id=operation,exit=0,outcome='SUCCEEDED',children='REAPED',log_sha256=b.binding_hash(log),completed_at='2026-09-25T00:00:21+00:00'))
        return identity
    def readers(self):
        return {
            'completion':lambda:b.binding_genesis_completion(self.root,self.target),
            'history':lambda:b.binding_history(self.root,self.target),
            'chain':lambda:b.binding_chain(self.root,self.target),
            'inventory':lambda:b.binding_inventory(self.root,self.owner,self.target),
            'policy':lambda:b.binding_active_policy(self.root,self.target),
            'copy':lambda:b.binding_genesis_readback(self.target,self.identity,D(self.request),policy_b_gate=Transcript(self.request,self.basis,True)),
            'admission':lambda:b.binding_admit_operation(self.root,self.target,self.init_identity,[]),
            'init_copy':lambda:b.binding_init_readback(self.target,self.init_identity,D(self.init_request)),
            'reconcile':lambda:b.binding_reconcile(self.target,self.owner,'RECOVERY','pre-v126','3'*64,self.owner['script_sha256'],'4'*64,['recover-pre-v126'],lambda *_:self.fail('observer must not execute')),
            'retire':lambda:b.binding_retire_deploy(self.target,self.owner,'3'*64,self.target/'absent',self.target/'absent2'),
        }
    def assertReadersRefuse(self):
        for name,reader in self.readers().items():
            with self.subTest(reader=name),self.assertRaises((b.BindingError,FileNotFoundError,KeyError)): reader()
    def test_valid_published_history_preserves_exact_epoch_and_legacy_unknown(self):
        self.seed(); owner,owners,current,recovered=b.binding_history(self.root,self.target)
        self.assertEqual(owner,self.owner);self.assertEqual(owners,[self.owner]);self.assertEqual(current,[]);self.assertFalse(recovered)
        self.assertEqual(b.binding_read(self.root/'prospective-basis.json'),self.basis)
        self.assertEqual(self.basis['predecessor_index']['runs'][0]['historical_outcome'],'UNKNOWN')
        self.assertFalse((self.root/'legacy-inventory.json').exists())
        b.binding_admit_operation(self.root,self.target,self.init_identity,[])
    def test_durable_basis_matches_independent_full_schema_consumer(self):
        epoch=load('prospective_history_core_schema','v126-authority-epoch.py')
        self.assertEqual(epoch.validate_durable_basis(self.basis,self.request),self.basis)
        self.assertEqual(epoch.request_identity(self.request),self.identity)
    def test_init_readback_requires_request_digest_not_manifest_digest(self):
        self.seed();completion=i.seed_completion(self.target,self.owner)
        before={p.name:p.read_bytes() for p in self.root.iterdir()}
        self.assertNotEqual(D(self.init_request),self.init_identity['intent_sha256'])
        self.assertEqual(b.binding_init_readback(self.target,self.init_identity,D(self.init_request)),completion)
        with self.assertRaisesRegex(b.BindingError,'init_readback_request'):
            b.binding_init_readback(self.target,self.init_identity,self.init_identity['intent_sha256'])
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.root.iterdir()})
    def test_wrong_epoch_and_downgrade_admission_refuse(self):
        self.seed()
        for changes in ({'epoch_id':'9'*64},{'domain_identity_sha256':'9'*64},None):
            identity=dict(self.init_identity)
            if changes: identity.update(changes)
            else:
                for key in b.EPOCH_FIELDS:identity.pop(key)
            with self.subTest(changes=changes),self.assertRaises(b.BindingError):b.binding_admit_operation(self.root,self.target,identity,[])
    def test_legacy_intent_refused_inside_new_epoch(self):
        self.seed();i.seed_completion(self.target,self.owner);self.operation()
        identity=dict(self.owner,kind='RECOVERY',name='pre-v126',action='recover-pre-v126',intent_sha256=self.basis['predecessor_index']['runs'][0]['intent_sha256'])
        current=b.binding_history(self.root,self.target)[2]
        with self.assertRaisesRegex(b.BindingError,'prospective_legacy_identity_replay'):b.binding_admit_operation(self.root,self.target,identity,current)
    def test_completion_deletion_and_owner_downgrade_refuse(self):
        self.seed();(self.root/'genesis.result.json').unlink();self.assertReadersRefuse()
        p=self.root/'run.json';p.chmod(0o600);p.write_bytes(b.binding_canonical({k:self.owner[k] for k in b.OWNER_FIELDS}));p.chmod(0o400)
        self.assertReadersRefuse()
    def test_wrong_lock_marker_refuses(self):
        self.seed();(self.root/'lock').write_bytes(b.GENESIS_LOCK_PREFIX+D(self.request).encode()+b'\n');self.assertReadersRefuse()
    def test_basis_or_predecessor_omission_refuses(self):
        for change in ('omit','upgrade','same-target'):
            basis=copy.deepcopy(self.basis)
            if change=='omit':basis['predecessor_index']['runs'].pop()
            if change=='upgrade':basis['predecessor_index']['runs'][0]['historical_outcome']='PASS'
            if change=='same-target':basis['predecessor_index']['legacy_target']=self.request['target']
            with self.subTest(change=change),self.assertRaises(b.BindingError):b.binding_prospective_basis(basis,self.request)
    def fsync_failure(self, which):
        self.seed(False);fd=os.open(self.root,os.O_RDONLY|os.O_DIRECTORY);real=os.fsync
        def fail(current):
            isroot=current==fd
            if isroot==(which=='root'):
                self.assertEqual((self.root/'genesis.result.json').read_bytes(),b.binding_canonical(self.result))
                raise OSError(errno.EIO,'SYNTHETIC durability failure')
            return real(current)
        try:
            with mock.patch.object(os,'fsync',side_effect=fail),self.assertRaises(OSError):b.binding_genesis_create_at(fd,'genesis.result.json',self.result)
            self.assertEqual((self.root/'genesis.result.json').stat().st_mode&0o777,0o600)
            self.assertReadersRefuse()
            with self.assertRaises(FileExistsError):b.binding_genesis_create_at(fd,'genesis.result.json',self.result)
        finally:os.close(fd)
    def test_full_result_file_fsync_failure_refused_by_all_readers(self):self.fsync_failure('file')
    def test_full_result_root_fsync_failure_refused_by_all_readers(self):self.fsync_failure('root')
    def test_recovery_within_epoch_and_reconciliation_are_supported(self):
        self.seed();i.seed_completion(self.target,self.owner);self.operation()
        identity=dict(self.owner,intent_sha256=H('recovery-intent'),kind='RECOVERY',name='pre-v126',action='recover-pre-v126')
        _,_,current,_=b.binding_history(self.root,self.target);b.binding_admit_operation(self.root,self.target,identity,current)
        identity=self.operation(kind='RECOVERY',name='pre-v126',action='recover-pre-v126')
        record=b.binding_reconcile(self.target,self.owner,'RECOVERY','pre-v126',identity['intent_sha256'],self.owner['script_sha256'],'4'*64,['recover-pre-v126'],lambda *_:dict(outcome='EXACT_COMPLETED_EFFECT',fixture='SYNTHETIC'))
        self.assertEqual(b.binding_epoch(record['identity']),b.binding_epoch(self.owner));self.assertTrue(b.binding_history(self.root,self.target)[3])
    def handoff(self,next_owner):
        return dict(format_version=1,owner=self.owner,next_owner=next_owner,terminal_receipt_sha256='d'*64,target_sha256=H(str(self.target)),
            operational_version='V125',backend_image='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',image_id='sha256:'+'e'*64,
            environment_sha256='1'*64,compose_sha256='2'*64,caddy_runtime_sha256='3'*64,config_owner='root:root',restart_policy='unless-stopped',
            handoff_approved_and_applied=True,approval_id='synthetic-approval',observed_at='2026-09-25T00:01:00Z')
    def test_transfer_inside_epoch_supported_wrong_epoch_refused(self):
        self.seed();self.operation(kind='RECOVERY',name='pre-v126',action='recover-pre-v126')
        next_owner=dict(self.owner,run_id='synthetic-next-owner',release_sha='e'*40)
        for wrong in (dict(next_owner,epoch_id='9'*64),{k:next_owner[k] for k in b.OWNER_FIELDS}):
            with self.assertRaises(b.BindingError):b.binding_handoff(self.handoff(wrong),self.owner,wrong,'d'*64,self.target)
        handoff=self.target/'handoff.json';b.binding_create(handoff,self.handoff(next_owner))
        args=['synthetic',str(self.target),*[self.owner[k] for k in ('run_id','release_sha','script_sha256')],'d'*64,str(handoff),
              *[next_owner[k] for k in ('run_id','release_sha','script_sha256')],'V125','sha256:'+'e'*64]
        with mock.patch.object(sys,'argv',args):b.binding_entry('retire')
        self.assertEqual(b.binding_chain(self.root,self.target)[0],next_owner)
    def test_wrong_epoch_recovery_record_is_never_current_history(self):
        self.seed();self.operation(kind='RECOVERY',name='pre-v126',action='recover-pre-v126',owner=dict(self.owner,epoch_id='9'*64))
        with self.assertRaises(b.BindingError):b.binding_history(self.root,self.target)


class ProspectiveLinux(ProspectiveHistory):
    def seed(self,publish=True):
        if not publish:return super().seed(False)
        result=b.binding_genesis(self.target,self.identity,self.request,policy_b_gate=Transcript(self.request,self.basis))
        self.result=result['result']
    def test_real_linux_create_then_copy_preserves_inode_and_bytes(self):
        self.seed();before={p.name:p.read_bytes() for p in self.root.iterdir()};inode=(self.root/'lock').stat().st_ino
        reply=b.binding_genesis_readback(self.target,self.identity,D(self.request),policy_b_gate=Transcript(self.request,self.basis,True))
        self.assertEqual(reply['prospective_basis'],self.basis);self.assertEqual((self.root/'lock').stat().st_ino,inode)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.root.iterdir()})
        with self.assertRaises(b.BindingError):b.binding_genesis(self.target,self.identity,self.request,policy_b_gate=Transcript(self.request,self.basis))
    def test_real_linux_init_uses_epoch_bound_r0(self):
        self.seed();test=self
        class InitGate:
            def check(self,identity,target,fd,timeout):
                b.binding_lock_held(test.root,fd)
                return dict(barrier='R0',operational=True,qualification_sha256='d'*64,epoch=test.basis['epoch'])
            def before_dispatch(self,*args):pass
        result=b.binding_initialize(self.target,self.init_identity,self.init_request,policy_b_gate=InitGate(),write_init=lambda identity,request,admitted:i.attestation(identity,request))
        self.assertEqual(result['identity'],self.init_identity)
        self.assertEqual(b.binding_init_readback(self.target,self.init_identity,D(self.init_request))['result'],result)
        with self.assertRaisesRegex(b.BindingError,'init_readback_request'):
            b.binding_init_readback(self.target,self.init_identity,self.init_identity['intent_sha256'])


def negative_controls():
    """Each isolated source mutation must cause an assertion failure, not a skip."""
    original=(ROOT/'v126-operation-bindings.py').read_text()
    controls=[
        ('handoff-epoch-check', 'if binding_epoch(left) != binding_epoch(right):', 'if False:',
         'test_transfer_inside_epoch_supported_wrong_epoch_refused'),
        ('predecessor-intent-denial', '    binding_predecessor_denied(root, identity)\n', '',
         'test_legacy_intent_refused_inside_new_epoch'),
        ('durable-result-pending-mode', "pending_result = name == 'genesis.result.json'", 'pending_result = False',
         'test_full_result_file_fsync_failure_refused_by_all_readers')]
    for name,needle,replacement,test in controls:
        if original.count(needle)!=1:raise AssertionError('negative-control source shape: '+name)
        with tempfile.TemporaryDirectory(prefix='prospective-history-mutant-') as directory:
            directory=Path(directory)
            for filename in ('test-v126-prospective-history.py','test-v126-policy-b-init.py'):
                shutil.copyfile(ROOT/filename,directory/filename)
            (directory/'v126-operation-bindings.py').write_text(original.replace(needle,replacement,1))
            result=subprocess.run([sys.executable,'-B',str(directory/'test-v126-prospective-history.py'),'ProspectiveHistory.'+test],capture_output=True,text=True,timeout=30)
            if result.returncode!=1 or 'FAILED (failures=1)' not in result.stderr:
                raise AssertionError(name+' did not trigger assertion failure: '+result.stderr)
            print('NEGATIVE_CONTROL '+name+' detected exit=1 assertion_failure=1')


if __name__=='__main__':
    args=sys.argv[1:]
    if args==['--negative-controls']:negative_controls()
    elif args==['--portable']:unittest.main(argv=[sys.argv[0],'ProspectiveHistory'])
    elif args==['--linux']:
        if sys.platform!='linux':raise SystemExit('Linux real namespace/flock validation required; no skipped PASS')
        unittest.main(argv=[sys.argv[0],'ProspectiveLinux'])
    else:unittest.main()
