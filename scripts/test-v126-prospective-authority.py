#!/usr/bin/env python3
"""Synthetic enrolled sources; real attended prospective authority consumers.

Fixture identities and observations are test-only. No key, operational authority,
network request, producer action or target operation is created by this suite.
"""
from contextlib import contextmanager
import copy
from datetime import datetime, timezone
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    module = importlib.util.module_from_spec(spec); sys.modules[name] = module
    spec.loader.exec_module(module); return module

a = load('prospective_attended_authority_tested', 'v126-policy-b-authority.py')
e = a.epoch
H = lambda value: e.digest(value.encode('ascii'))


class Console:
    def __init__(self, approved):
        self.approved = approved; self.prompts = []; self.on_confirm = None
    def ask(self, prompt, deadline):
        self.prompts.append(prompt)
        if prompt.startswith('U:'): return self.approved
        if self.on_confirm: self.on_confirm()
        return prompt.rsplit('nonce ', 1)[1].split(':')[0]


class AnchorFixture:
    def __init__(self, root):
        self.root = Path(root).resolve(); self.evidence = self.root/'objects'; self.evidence.mkdir()
        self.sources = self.root/'readers'; self.sources.mkdir(); self.target = self.root/'target'; self.target.mkdir()
        self.anchor = dict(schema_version=2, kind='ap06-prospective-epoch-anchor',
            decision_id='synthetic-owner-decision', decision_provenance_sha256=H('owner-decision'),
            epoch_id=H('epoch'), epoch_descriptor_sha256=H('epoch'), domain_identity_sha256=H('domain'),
            predecessor_index_sha256=H('immutable-unknown-predecessors'), generation=1, revocation_generation=0,
            valid_from='2026-09-25T00:00:00Z', valid_until='2026-09-26T00:00:00Z',
            custodian_identity=H('custodian'), verifier_identity=H('verifier'),
            principal_fingerprint='SHA256:'+'N'*43, host_fingerprint='SHA256:'+'H'*43,
            deployment_target=str(self.target), source_sha='a'*40, source_tree='b'*40, tooling_sha256=H('tools'),
            python_version='3.12.3', bootstrap_binding_sha256=H('binding'), enrollment_spec_sha256=H('enrollment-spec'),
            isolation_method_sha256=H('isolation-method'), protected_selection_policy_sha256=H('protected-policy'),
            sources={}, evidence_root=str(self.evidence), scopes=list(e.SCOPES),
            initial_checkpoint=dict(control_sequence=3, control_head_sha256=H('retained-pre-U-head'), catalogue_generation=1,revocation_generation=0),
            clock_id=H('verifier-boot-bound-clock'),clock_method_sha256=H('clock-method'))
        for name in ('catalogue','highwater','producer','clock'):
            path = self.sources/(name+'.json'); path.write_bytes(b'{}\n'); path.chmod(0o600)
            self.anchor['sources'][name] = dict(identity=H('independent-'+name),owner_uid=os.geteuid(),path=str(path))
        self.path = self.root/'anchor.json'
        self.console = Console(e.digest(self.anchor)); self.save()
    def save(self):
        self.path.write_bytes(e.canonical(self.anchor)); self.path.chmod(0o600)
    def load(self): return a.load_enrolled_authority(self.path, self.console)
    def verifier(self): return a.VVerifier(self.load(), None, self.console)
    def challenge(self):
        identity = dict(run_id='synthetic-new-run',release_sha=self.anchor['source_sha'],script_sha256=H('cutover'),
            intent_sha256=H('proposal'),kind='TARGET_BIND',name='PROSPECTIVE_ISOLATED_GENESIS',action='bind-isolated-target',
            epoch_id=self.anchor['epoch_id'],domain_identity_sha256=self.anchor['domain_identity_sha256'])
        return dict(version=2,type='CHALLENGE',session_id=H('session'),nonce=H('nonce'),sequence=1,phase='EARLY',
            operation_id=e.digest(identity),identity=identity,target=str(self.target),timeout=300,history_sha256=H('history'),
            anchor_sha256=e.digest(self.anchor),anchor_generation=1,manifest_sha256=identity['intent_sha256'],
            source_tree=self.anchor['source_tree'],tooling_sha256=self.anchor['tooling_sha256'],runtime='3.12.3',
            principal_fingerprint=self.anchor['principal_fingerprint'],host_fingerprint=self.anchor['host_fingerprint'],
            native_history=[],genesis_mode='EXECUTE',genesis_completion_sha256=None,
            epoch={k:self.anchor[k] for k in ('epoch_id','domain_identity_sha256','predecessor_index_sha256')})


class AnchorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='prospective-authority-',dir='/private/tmp' if Path('/private/tmp').exists() else None)
        self.addCleanup(self.temp.cleanup); self.fx = AnchorFixture(self.temp.name)
    def test_existing_retained_digest_is_required_and_never_prompted_as_candidate(self):
        result = self.fx.load(); self.assertEqual(result.sha256,e.digest(self.fx.anchor))
        self.assertNotIn(result.sha256,self.fx.console.prompts[0])
        self.fx.console.approved=H('another-prior-decision')
        with self.assertRaises(ValueError): self.fx.load()
    def test_unknown_field_and_legacy_rewrap_refuse(self):
        for field,value in (('binding_sha256',H('invented-DR')),('approved',True),('legacy_genesis',{})):
            with self.subTest(field=field):
                changed=copy.deepcopy(self.fx.anchor); changed[field]=value
                with self.assertRaises(ValueError): a.validate_anchor(changed)
    def test_bootstrap_cannot_grant_dispatch_or_producer_scope(self):
        for scope in ('DISPATCH','COPY_ONLY','AP-02','LEGACY_GENESIS'):
            with self.subTest(scope=scope):
                changed=copy.deepcopy(self.fx.anchor); changed['scopes']=[scope]
                with self.assertRaises(ValueError): a.validate_anchor(changed)
    def test_wrong_python_and_epoch_descriptor_refuse(self):
        for key,value in (('python_version','3.13.2'),('epoch_descriptor_sha256',H('other'))):
            changed=copy.deepcopy(self.fx.anchor); changed[key]=value
            with self.subTest(key=key), self.assertRaises(ValueError): a.validate_anchor(changed)
    def test_same_reader_or_evidence_cache_cannot_be_independent_source(self):
        for mutated in ('path','identity'):
            changed=copy.deepcopy(self.fx.anchor)
            changed['sources']['producer'][mutated]=changed['sources']['catalogue'][mutated]
            with self.subTest(mutated=mutated), self.assertRaises(ValueError): a.validate_anchor(changed)
        path=self.fx.evidence/'not-an-independent-reader';path.write_bytes(b'{}\n')
        changed=copy.deepcopy(self.fx.anchor);changed['sources']['producer']['path']=str(path)
        with self.assertRaises(ValueError): a.validate_anchor(changed)
    def test_bootstrap_never_enters_full_copy_authorization(self):
        verifier=self.fx.verifier();identity=self.fx.challenge()['identity']
        with self.assertRaises(ValueError): verifier.authorize_action(identity,str(self.fx.target))
    def test_wrong_epoch_or_v1_transport_is_refused_before_reading_authority(self):
        for mutate in (lambda x:x.update(version=1),lambda x:x['epoch'].update(epoch_id=H('old')),
                lambda x:x['identity'].update(domain_identity_sha256=H('shared')),
                lambda x:x['identity'].pop('epoch_id'),lambda x:x.pop('epoch')):
            verifier=self.fx.verifier();challenge=self.fx.challenge();mutate(challenge)
            with self.subTest(mutate=mutate),self.assertRaises(ValueError):verifier.challenge(challenge)
    def test_bootstrap_cannot_be_used_for_init_stage_or_legacy_target(self):
        for kind,name,action in (('INIT','RUN_INITIALIZED','initialize-run'),('STAGE','BASELINE_VERIFIED','verify-baseline'),
                ('TARGET_BIND','LEGACY_GENESIS','bind-legacy-target')):
            verifier=self.fx.verifier();challenge=self.fx.challenge()
            challenge['identity'].update(kind=kind,name=name,action=action);challenge['operation_id']=e.digest(challenge['identity'])
            with self.subTest(kind=kind,name=name),self.assertRaises(ValueError):verifier.challenge(challenge)
    def test_zero_clock_error_is_refused_without_renewing_measurement(self):
        verifier=self.fx.verifier();wall=1790298000;mono=1000
        stamp=datetime.fromtimestamp(wall,timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        clock=dict(schema_version=1,kind='ap06-clock-observation',source_identity=self.fx.anchor['sources']['clock']['identity'],
            clock_id=self.fx.anchor['clock_id'],measured=dict(utc=stamp,error_seconds=0,monotonic_seconds=mono,clock_id=self.fx.anchor['clock_id']),
            expires_at='2026-09-26T00:00:00Z',method_sha256=self.fx.anchor['clock_method_sha256'])
        Path(self.fx.anchor['sources']['clock']['path']).write_bytes(e.canonical(clock))
        with mock.patch.object(a.time,'time',return_value=wall),mock.patch.object(a.time,'monotonic',return_value=mono),self.assertRaises(ValueError):verifier.now()


core_fixture = load('prospective_real_core_fixture', 'fixtures/v126-authority-epoch-fixture.py')


@contextmanager
def synthetic_git_observations():
    """Only explicit Git identity observations are synthetic; tool bytes stay real.

    The isolated Linux harness mounts public source without .git or credentials.
    Exercise production checkout validation against this test-owned observation
    boundary on every platform, with no conditional fallback or skipped check.
    """
    root_prefix=['git','--no-replace-objects','-C',str(ROOT)]
    clean_prefix=['git','--no-replace-objects','-c','core.fsmonitor=false','-C',str(ROOT)]
    observations={
        tuple(root_prefix+['rev-parse','HEAD','HEAD^{tree}']):('a'*40+'\n'+'b'*40+'\n').encode(),
        tuple(clean_prefix+['rev-parse','HEAD']):('a'*40+'\n').encode(),
        tuple(clean_prefix+['rev-parse','HEAD^{tree}']):('b'*40+'\n').encode(),
        tuple(clean_prefix+['status','--porcelain','--untracked-files=all']):b'',
    }
    def run(argv,*args,**kwargs):
        if type(argv) is not list or tuple(argv) not in observations:
            raise AssertionError('only enumerated synthetic source-control observations are allowed')
        return subprocess.CompletedProcess(argv,0,observations[tuple(argv)],b'')
    with mock.patch.object(subprocess,'run',side_effect=run):yield


class ProductionFixture:
    @synthetic_git_observations()
    def __init__(self, root, changes=None):
        self.root = Path(root).resolve(); self.target = self.root/'target'; self.target.mkdir()
        info = self.target.stat()
        source = subprocess.check_output(['git','--no-replace-objects','-C',str(ROOT),'rev-parse','HEAD','HEAD^{tree}']).decode().splitlines()
        tools = a.dr.tooling(ROOT)
        mutations = dict(changes or {})
        user_request = mutations.get('request')
        def request(doc):
            doc['script_sha256'] = next(x['sha256'] for x in tools if x['path']=='scripts/v126-cutover.sh')
            if user_request: user_request(doc)
        mutations['request'] = request
        def runtime(doc):
            path=Path(sys.executable).resolve(strict=True)
            doc.update(executable_path=str(path),executable_sha256=e.digest(path.read_bytes()))
        mutations.setdefault('runtime_v',runtime);mutations.setdefault('runtime_s',runtime)
        self.fx = core_fixture.Fixture(root=self.root,
            target=dict(path=str(self.target),device=info.st_dev,inode=info.st_ino,uid=info.st_uid,host_fingerprint='SHA256:'+'N'*43),
            source=dict(commit=source[0],tree=source[1],tooling_sha256=''), tools=tools, changes=mutations)
        self.anchor = self.fx.anchor; self.request = self.fx.request
        self.wall = a.S.timestamp(self.fx.now['utc']); self.mono = self.fx.now['monotonic_seconds']
        self.path = self.root/'anchor.json'; self.console = Console(e.digest(self.anchor))
        self.materialize()
        self.verifier = a.VVerifier(a.load_enrolled_authority(self.path,self.console),None,self.console)
        self.verifier.set_genesis_request(e.canonical(self.request))
    def materialize(self):
        for ref, raw in self.fx.documents.items():
            path=Path(self.anchor['evidence_root'])/ref;path.parent.mkdir(exist_ok=True)
            path.write_bytes(raw);path.chmod(0o600)
        self.path.write_bytes(e.canonical(self.anchor));self.path.chmod(0o600)
        for name in ('catalogue','highwater','producer','clock'):self.save(name)
    def save(self,name):
        value=getattr(self.fx,{'producer':'observations','clock':'clock_record'}.get(name,name))
        path=Path(self.anchor['sources'][name]['path']);path.parent.mkdir(exist_ok=True)
        path.write_bytes(e.canonical(value));path.chmod(0o600)
    def approve_copy(self):
        fx=self.fx; completion=H('independently-pinned-durable-completion')
        approval=fx.rec('htqr-prospective-action-approval',epoch_id=fx.epoch_id,domain_identity_sha256=fx.domain_ref,
            anchor_sha256=e.digest(fx.anchor),request_sha256=e.digest(fx.request),scope=e.SCOPES[1],
            approver_identity_sha256=fx.anchor['custodian_identity'],valid_from=core_fixture.timestamp(-120),
            valid_until=core_fixture.timestamp(3600),observed=fx.observed)
        fx.event('ACTION_AUTHORIZED',request_sha256=e.digest(fx.request),scope=e.SCOPES[1],approval_record_sha256=fx.add(approval))
        inventory=e.strict(fx.documents[fx.inventory_ref]);inventory.update(root_state='DURABLE_COMPLETION',completion_sha256=completion,
            operation_ids=[fx.request['run_id']]);fx.inventory_ref=fx.add(inventory)
        fx.finish();fx.catalogue['completion_sha256']=completion
        fx.catalogue['actions']=[dict(identity=e.request_identity(fx.request),scope=e.SCOPES[1],
            valid_from=core_fixture.timestamp(-120),valid_until=core_fixture.timestamp(3600))]
        self.materialize()

    def challenge(self,sequence=1,session='session',mode='EXECUTE'):
        identity=e.request_identity(self.request)
        return dict(version=2,type='CHALLENGE',session_id=H(session),nonce=H('nonce-'+str(sequence)),sequence=sequence,
            phase='EARLY' if sequence==1 else 'LATE',operation_id=e.digest(identity),identity=identity,target=str(self.target),
            timeout=300,history_sha256=H('history-'+str(sequence)),anchor_sha256=e.digest(self.anchor),anchor_generation=self.anchor['generation'],
            manifest_sha256=identity['intent_sha256'],source_tree=self.anchor['source_tree'],tooling_sha256=self.anchor['tooling_sha256'],
            runtime='3.12.3',principal_fingerprint=self.anchor['principal_fingerprint'],host_fingerprint=self.anchor['host_fingerprint'],native_history=[],
            genesis_mode=mode,genesis_completion_sha256=self.fx.catalogue['completion_sha256'],epoch=e.epoch_context(self.anchor))
    @contextmanager
    def observations(self):
        with synthetic_git_observations(),mock.patch.object(a.time,'time',side_effect=lambda:self.wall),\
                mock.patch.object(a.time,'monotonic',side_effect=lambda:self.mono),\
                mock.patch.object(a.platform,'python_version',return_value='3.12.3'):
            yield
    def verify(self,sequence=1,mode='EXECUTE'):
        with self.observations():return self.verifier.verify(self.challenge(sequence,mode=mode))


class FullFixture(ProductionFixture):
    """Synthetic genuine-shape DR/AP07 chain joined to the real control consumer."""
    @synthetic_git_observations()
    def __init__(self, root, stage='RUN_INITIALIZED', wrong_native_epoch=False):
        legacy = load('prospective_existing_dr_fixture','test-v126-policy-b-authority.py')
        with mock.patch.object(core_fixture,'timestamp',side_effect=lambda offset=0:legacy.fmod.UTC(legacy.fmod.NOW+offset)):
            super().__init__(root)
            fx=self.fx
            with mock.patch.object(a.platform,'python_version',return_value='3.12.3'):
                provisional=legacy.fmod.Fixture()
            fields=('database_semantics_sha256','data_runtime','restore_runtime')
            source_identity=fx.rec('prospective-dr-source-identity',
                **e.epoch_context(fx.anchor),bootstrap_binding_sha256=fx.binding_ref,
                protected_selection_policy_sha256=fx.selection_ref,
                source_sha=fx.anchor['source_sha'],source_tree=fx.anchor['source_tree'],tooling_sha256=fx.anchor['tooling_sha256'],
                **{key:provisional.binding[key] for key in fields})
            source_ref=fx.add(source_identity)
            actual_hash=legacy.fmod.H;actual_clock=legacy.fmod.clock
            def fixture_hash(text):return source_ref if text=='source-identity' else actual_hash(text)
            def fixture_clock(seconds,error=0,identity='source-clock',monotonic=None):
                value=actual_clock(seconds,error=error,identity=identity,monotonic=monotonic)
                if identity=='verifier-clock':
                    value.update(error_seconds=1,clock_id=fx.now['clock_id'],monotonic_seconds=10000+seconds-legacy.fmod.NOW)
                return value
            drroot=self.root/'dr-builder';drroot.mkdir()
            original_canonical=legacy.fmod.dr.canonical
            def native_fixture_bytes(value):
                if (type(value) is dict and value.get('format_version')==1
                        and value.get('stage')=='QUIESCED_BACKUP_REHEARSED' and 'result_category' in value):
                    native_epoch=e.epoch_context(fx.anchor)
                    if wrong_native_epoch:native_epoch['epoch_id']=H('old-epoch')
                    value=dict(value,epoch=native_epoch)
                return original_canonical(value)
            with mock.patch.object(legacy.fmod,'H',side_effect=fixture_hash),mock.patch.object(legacy.fmod,'clock',side_effect=fixture_clock),\
                    mock.patch.object(a.platform,'python_version',return_value='3.12.3'),\
                    mock.patch.object(legacy.fmod.dr,'canonical',side_effect=native_fixture_bytes):
                self.drfx=legacy.Fixture(drroot,stage=stage)
            full=copy.deepcopy(self.drfx.anchor)
            for key in ('epoch_id','epoch_descriptor_sha256','domain_identity_sha256','predecessor_index_sha256',
                    'bootstrap_binding_sha256','enrollment_spec_sha256','isolation_method_sha256','protected_selection_policy_sha256',
                    'decision_id','decision_provenance_sha256','custodian_identity','verifier_identity','principal_fingerprint','host_fingerprint',
                    'deployment_target','sources','evidence_root','clock_id','clock_method_sha256','valid_from','valid_until'):
                full[key]=copy.deepcopy(fx.anchor[key])
            full.update(schema_version=2,kind='ap06-prospective-full-anchor',generation=2,bootstrap_anchor_sha256=e.digest(fx.anchor),
                control_checkpoint=dict(control_sequence=len(fx.events),control_head_sha256=fx.events[-1],catalogue_generation=1,revocation_generation=0))
            identity=dict(self.drfx.identity,epoch_id=fx.epoch_id,domain_identity_sha256=fx.domain_ref)
            self.full_identity=identity
            dr_documents={path.name:path.read_bytes() for path in self.drfx.objects.iterdir()}
            fx.promote_full(full,self.drfx.f.binding,dr_documents,identity)
            self.anchor=full;self.console.approved=e.digest(full);self.materialize()
            self.native_calls=[]
            def native(context, history):
                self.native_calls.append((context,history))
                return a.adapter.NativeStage7(self.drfx.f.native,self.drfx.f.cutover['native_manifest_sha256'])
            self.verifier=a.VVerifier(a.load_enrolled_authority(self.path,self.console),native if stage!='RUN_INITIALIZED' else None,self.console)
    def save(self,name):
        if hasattr(self,'full_identity') and name in ('catalogue','highwater','producer'):
            dr_value=copy.deepcopy(getattr(self.drfx,{'highwater':'high','producer':'producer'}.get(name,name)))
            dr_value['source_identity']=self.anchor['sources'][name]['identity']
            if name=='catalogue':
                dr_value.update(generation=self.fx.catalogue['generation'],revocation_generation=self.fx.catalogue['revocation_generation'],
                    revoked=self.fx.catalogue['revoked'],actions=self.fx.catalogue['actions'])
            elif name=='highwater':
                dr_value.update(catalogue_generation=self.fx.catalogue['generation'],revocation_generation=self.fx.catalogue['revocation_generation'])
            control=getattr(self.fx,{'producer':'observations'}.get(name,name))
            value=dict(schema_version=2,kind='ap06-prospective-full-'+name,source_identity=self.anchor['sources'][name]['identity'],dr=dr_value,control=control)
            path=Path(self.anchor['sources'][name]['path']);path.write_bytes(e.canonical(value));path.chmod(0o600)
        else:super().save(name)
    def challenge(self,sequence=1,session='session',mode='EXECUTE'):
        identity=self.full_identity
        return dict(version=2,type='CHALLENGE',session_id=H(session),nonce=H('nonce-'+str(sequence)),sequence=sequence,
            phase='EARLY' if sequence==1 else 'LATE',operation_id=e.digest(identity),identity=identity,target=str(self.target),timeout=300,
            history_sha256=H('new-native-history-'+str(sequence)),anchor_sha256=e.digest(self.anchor),anchor_generation=self.anchor['generation'],
            manifest_sha256=H('prepared-new-epoch-native-manifest') if identity['kind']=='INIT' else None,source_tree=self.anchor['source_tree'],tooling_sha256=self.anchor['tooling_sha256'],
            runtime='3.12.3',principal_fingerprint=self.anchor['principal_fingerprint'],host_fingerprint=self.anchor['host_fingerprint'],
            native_history=[] if identity['kind']=='INIT' else [dict(identity=identity,request_sha256=H('prior-request'),result_sha256=H('prior-result'),
                log_sha256=H('prior-log'),completed_at=datetime.fromtimestamp(self.wall-1,timezone.utc).isoformat(),artifacts=[])],epoch=e.epoch_context(self.anchor))


class FullTransitionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='prospective-full-transition-',dir='/private/tmp' if Path('/private/tmp').exists() else None)
        self.addCleanup(self.temp.cleanup);self.fx=FullFixture(self.temp.name)
    def test_full_U_runs_both_complete_DR_and_current_epoch_consumers(self):
        with mock.patch.object(a.dr,'consume_barrier',wraps=a.dr.consume_barrier) as dr_consumer,\
                mock.patch.object(e,'validate_current',wraps=e.validate_current) as control_consumer:
            for sequence in (1,2):
                result=self.fx.verify(sequence);self.assertEqual(result['decision'],'PASS',result)
                self.assertEqual(result['barrier'],'R0');self.assertEqual(result['epoch'],e.epoch_context(self.fx.anchor))
        self.assertEqual(dr_consumer.call_count,2);self.assertEqual(control_consumer.call_count,2)
    def test_wrong_attached_DR_head_is_rejected_even_with_rehashed_control_events(self):
        original=core_fixture.Fixture.event
        def wrong_head(fixture, typ, **body):
            if typ=='DR_BINDING_ATTACHED':
                ledger=e.strict(fixture.documents[body['dr_ledger_sha256']])
                body['dr_head_sha256']=ledger['events'][0]
            return original(fixture,typ,**body)
        with tempfile.TemporaryDirectory(dir=self.temp.name) as root,mock.patch.object(core_fixture.Fixture,'event',new=wrong_head):
            fx=FullFixture(root)
            self.assertEqual(fx.verify()['decision'],'REFUSE')
    def test_current_protected_observation_is_required_even_with_full_DR_PASS(self):
        ref=self.fx.fx.observations['protected_observation_sha256'];(Path(self.fx.anchor['evidence_root'])/ref).unlink()
        with mock.patch.object(a.dr,'consume_barrier',wraps=a.dr.consume_barrier) as consumer:
            self.assertEqual(self.fx.verify()['decision'],'REFUSE')
        self.assertEqual(consumer.call_count,0)

    def test_prospective_Q_uses_own_epoch_bound_native_stage7_and_real_DR_consumer(self):
        with tempfile.TemporaryDirectory(dir=self.temp.name) as root:
            fx=FullFixture(root,stage='V126_BACKEND_STARTED')
            with mock.patch.object(a.dr,'validate_native_stage7',wraps=a.dr.validate_native_stage7) as native_consumer:
                for sequence in (1,2):
                    result=fx.verify(sequence);self.assertEqual(result['decision'],'PASS',result)
                    self.assertEqual(result['barrier'],'Q');self.assertEqual(result['native_stage7_sha256'],e.digest(fx.drfx.f.native))
            self.assertEqual(len(fx.native_calls),2);self.assertEqual(native_consumer.call_count,2)
    def test_wrong_epoch_native_stage7_cannot_qualify_Q(self):
        with tempfile.TemporaryDirectory(dir=self.temp.name) as root:
            fx=FullFixture(root,stage='V126_BACKEND_STARTED',wrong_native_epoch=True)
            with mock.patch.object(a.dr,'validate_native_stage7',wraps=a.dr.validate_native_stage7) as consumer:
                self.assertEqual(fx.verify()['decision'],'REFUSE')
            self.assertEqual(consumer.call_count,1)

    def test_full_anchor_without_genuine_DR_binding_attachment_refuses(self):
        ref=self.fx.anchor['binding_sha256'];(Path(self.fx.anchor['evidence_root'])/ref).unlink()
        self.assertEqual(self.fx.verify()['decision'],'REFUSE')
    def test_full_DR_readiness_never_substitutes_stale_control_producer(self):
        self.fx.fx.observations['source_identity']=H('different-producer');self.fx.save('producer')
        self.assertEqual(self.fx.verify()['decision'],'REFUSE')


class AcquisitionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='prospective-acquisition-',dir='/private/tmp' if Path('/private/tmp').exists() else None)
        self.addCleanup(self.temp.cleanup);self.fx=ProductionFixture(self.temp.name)
    def test_two_rounds_execute_real_core_and_separate_current_console(self):
        with mock.patch.object(e,'validate_basis',wraps=e.validate_basis) as real_consumer:
            for sequence in (1,2):
                result=self.fx.verify(sequence);self.assertEqual(result['decision'],'PASS',result)
                self.assertEqual(result['version'],2);self.assertEqual(result['epoch'],e.epoch_context(self.fx.anchor))
                self.assertEqual(result['barrier'],'PROSPECTIVE_ISOLATED_GENESIS')
                self.assertEqual(result['qualification_sha256'],self.fx.request['isolation_proof_sha256'])
                self.assertEqual(e.validate_durable_basis(result['prospective_basis'],self.fx.request),result['prospective_basis'])
                self.assertTrue(all(value>0 for value in result['expiry'].values()))
        self.assertEqual(real_consumer.call_count,2);self.assertEqual(len(self.fx.console.prompts),3)
    def test_enrollment_locator_is_read_from_independently_pinned_binding(self):
        with self.fx.observations():self.assertEqual(self.fx.verifier.transport_enrollment(),self.fx.fx.enrollment)
        path=Path(self.fx.anchor['evidence_root'])/self.fx.fx.enrollment_ref
        changed=copy.deepcopy(self.fx.fx.enrollment);changed['endpoint']['host']='192.0.2.99';path.write_bytes(e.canonical(changed))
        with self.assertRaises(ValueError):self.fx.verifier.transport_enrollment()
    def test_missing_producer_is_not_created_by_console_confirmation(self):
        Path(self.fx.anchor['sources']['producer']['path']).unlink()
        self.assertEqual(self.fx.verify()['decision'],'REFUSE')
    def test_late_rechecks_current_revocation_without_retry(self):
        self.assertEqual(self.fx.verify()['decision'],'PASS')
        self.fx.fx.catalogue['revoked']=[e.digest(self.fx.request)];self.fx.save('catalogue')
        self.assertEqual(self.fx.verify(2)['decision'],'REFUSE')
    def test_changed_catalogue_during_confirmation_refuses(self):
        def change():
            self.fx.fx.catalogue['generation']+=1;self.fx.save('catalogue')
        self.fx.console.on_confirm=change
        self.assertEqual(self.fx.verify()['decision'],'REFUSE')
    def test_producer_cannot_substitute_origin_or_expired_proof(self):
        for key,value in (('source_identity',H('caller')),('isolation_proof_sha256',H('fabricated-proof')),('source_tree','e'*40)):
            original=copy.deepcopy(self.fx.fx.observations);self.fx.fx.observations[key]=value;self.fx.save('producer')
            self.assertEqual(self.fx.verify()['decision'],'REFUSE')
            self.fx.fx.observations=original;self.fx.save('producer');self.fx.verifier.sessions.clear();self.fx.verifier.seen.clear()
    def test_no_stored_early_pass_can_authorize_replay(self):
        self.assertEqual(self.fx.verify()['decision'],'PASS')
        self.assertEqual(self.fx.verify()['decision'],'REFUSE')
    def test_runtime_binary_pin_is_checked_against_actual_interpreter(self):
        with tempfile.TemporaryDirectory(dir=self.temp.name) as root:
            fx=ProductionFixture(root,changes={'runtime_v':lambda doc:doc.update(executable_path=str(Path(sys.executable).resolve()),executable_sha256=H('other-binary'))})
            with fx.observations(),self.assertRaises(ValueError):fx.verifier.transport_enrollment()
            self.assertEqual(fx.verify()['decision'],'REFUSE')
    def test_revoked_durable_completion_cannot_be_copied(self):
        self.fx.approve_copy();self.fx.fx.catalogue['revoked'].append(self.fx.fx.catalogue['completion_sha256']);self.fx.save('catalogue')
        self.assertEqual(self.fx.verify(mode='GENESIS_READBACK')['decision'],'REFUSE')

    def test_wrong_runtime_refuses_even_when_U_declares_matching_version(self):
        with self.fx.observations(),mock.patch.object(a.platform,'python_version',return_value='3.13.2'):
            self.assertEqual(self.fx.verifier.verify(self.fx.challenge())['decision'],'REFUSE')
    def test_stale_clock_and_machine_observations_refuse(self):
        self.fx.wall+=301;self.fx.mono+=301
        self.assertEqual(self.fx.verify()['decision'],'REFUSE')
    def test_copy_has_own_approval_and_preserves_genesis_request_after_expiry(self):
        with tempfile.TemporaryDirectory(dir=self.temp.name) as root:
            fx=ProductionFixture(root,changes={'request':lambda doc:doc.update(expires_at=core_fixture.timestamp(-1))})
            fx.approve_copy()
            before={path.relative_to(fx.root).as_posix():path.read_bytes() for path in fx.root.rglob('*') if path.is_file()}
            for sequence in (1,2):
                result=fx.verify(sequence,mode='GENESIS_READBACK');self.assertEqual(result['decision'],'PASS',result)
                self.assertEqual(result['barrier'],'PROSPECTIVE_ISOLATED_GENESIS_COPY')
            after={path.relative_to(fx.root).as_posix():path.read_bytes() for path in fx.root.rglob('*') if path.is_file()}
            self.assertEqual(before,after)
    def test_copy_without_separate_scope_or_completion_pin_refuses(self):
        self.fx.approve_copy();self.fx.fx.catalogue['actions'][0]['scope']=e.SCOPES[0];self.fx.save('catalogue')
        self.assertEqual(self.fx.verify(mode='GENESIS_READBACK')['decision'],'REFUSE')
    def test_late_fork_must_extend_exact_early_control_head(self):
        self.assertEqual(self.fx.verify()['decision'],'PASS')
        self.fx.verifier.control_checkpoint['control_head_sha256']=H('independently-retained-other-head')
        self.assertEqual(self.fx.verify(2)['decision'],'REFUSE')

    def test_source_tool_drift_refuses_despite_matching_Git_identity(self):
        with self.fx.observations(),mock.patch.object(a.dr,'tooling',return_value=[]):
            self.assertEqual(self.fx.verifier.verify(self.fx.challenge())['decision'],'REFUSE')


class ActiveAuthorityRevocationTests(unittest.TestCase):
    """Coherently retained revocations must invalidate current roles in real V."""
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='prospective-role-revocation-',dir='/private/tmp' if Path('/private/tmp').exists() else None)
        self.addCleanup(self.temp.cleanup)
    @staticmethod
    def references(fx):
        policy=fx.fx.policy
        refs={key:policy[key] for key in ('writer_identity_sha256','writer_method_sha256',
            'producer_identity_sha256','producer_method_sha256','controller_method_sha256',
            'verifier_identity_sha256','custodian_identity_sha256')}
        refs.update({'controller-'+str(i):value for i,value in enumerate(policy['controller_identities'])})
        for route in policy['retention_routes']:
            for key in ('controller_identity_sha256','observer_identity_sha256','failure_domain_sha256','method_sha256'):
                refs['retention-'+route['route']+'-'+key]=route[key]
        for label,offset,field in (('anchor-approval',3,'approval_record_sha256'),
                ('enrollment-activation',4,'activation_record_sha256'),('action-approval',5,'approval_record_sha256')):
            event_ref=fx.fx.events[offset];event=e.strict(fx.fx.documents[event_ref])
            payload=e.strict(fx.fx.documents[event['payload_sha256']])
            refs[label+'-event']=event_ref
            refs[label+'-payload']=event['payload_sha256']
            refs[label+'-record']=payload[field]
        return refs
    @staticmethod
    def revoke(fx,ref):
        fx.fx.event('REVOCATION_ADVANCED',revocation_generation=1,revoked=[ref])
        fx.fx.finish(revocation=1,revoked=[ref]);fx.materialize()
    def check_role(self,label,late):
        with tempfile.TemporaryDirectory(dir=self.temp.name) as root:
            fx=ProductionFixture(root)
            if late:self.assertEqual(fx.verify()['decision'],'PASS')
            self.revoke(fx,self.references(fx)[label])
            self.assertEqual(fx.verify(2 if late else 1)['decision'],'REFUSE')
    def test_unrelated_revocation_does_not_turn_valid_new_epoch_into_false_refusal(self):
        with tempfile.TemporaryDirectory(dir=self.temp.name) as root:
            fx=ProductionFixture(root);self.assertEqual(fx.verify()['decision'],'PASS')
            self.revoke(fx,H('inactive-unrelated-object'))
            self.assertEqual(fx.verify(2)['decision'],'PASS')


def role_revocation_test(label,late):
    def test(self):self.check_role(label,late)
    return test

for role in ('writer_identity_sha256','writer_method_sha256','producer_identity_sha256','producer_method_sha256',
        'controller_method_sha256','verifier_identity_sha256','custodian_identity_sha256',
        *('controller-'+str(i) for i in range(7)),
        *('retention-'+route+'-'+key for route in ('A','B') for key in
            ('controller_identity_sha256','observer_identity_sha256','failure_domain_sha256','method_sha256')),
        *(label+'-'+kind for label in ('anchor-approval','enrollment-activation','action-approval')
            for kind in ('event','payload','record'))):
    for late in (False,True):
        name='test_'+('late_' if late else 'early_')+role.replace('-','_')+'_revocation_refuses'
        setattr(ActiveAuthorityRevocationTests,name,role_revocation_test(role,late))


class NegativeControlTests(unittest.TestCase):
    def test_removing_clock_guard_breaks_the_actual_regression(self):
        source=(ROOT/'scripts/v126-policy-b-authority.py').read_text()
        guard='        if self.prospective and not 0 < error <= 59: refuse()'
        self.assertEqual(source.count(guard),1)
        mutant=types.ModuleType('prospective_authority_removed_clock_guard')
        mutant.__file__=str(ROOT/'scripts/v126-policy-b-authority.py');sys.modules[mutant.__name__]=mutant
        exec(compile(source.replace(guard,'        # negative control: removed measured clock bound'),mutant.__file__,'exec'),mutant.__dict__)
        with mock.patch.dict(globals(),{'a':mutant}):
            test=AnchorTests('test_zero_clock_error_is_refused_without_renewing_measurement')
            result=unittest.TestResult();test.run(result)
        self.assertEqual(len(result.errors),0,result.errors);self.assertEqual(len(result.failures),1)


if __name__ == '__main__': unittest.main(verbosity=2)
