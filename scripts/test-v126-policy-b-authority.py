#!/usr/bin/env python3
"""Synthetic enrolled sources, real acquisition/consumer. No operational proof.

Only external clock/Git-clean observations and human console I/O are fixture
sources; source/tool verification, AP01/AP07 validators and consumer execute.
No network or producer actions are available in this harness.
"""
from contextlib import contextmanager
import copy
from datetime import datetime, timezone
import importlib.util
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    module=importlib.util.module_from_spec(spec);sys.modules[name]=module;spec.loader.exec_module(module);return module

a=load('v126_policy_b_authority_tested','v126-policy-b-authority.py')
fmod=load('authority_ap01_fixture','test-v126-dr-evidence.py');fmod.dr=a.dr;fmod.S=a.S
cmod=load('authority_ap07_fixture','test-v126-dr-custody.py');cmod.c=a.custody;cmod.dr=a.dr
H=fmod.H

class Console:
    def __init__(self):self.approved=None;self.prompts=[];self.answer=None;self.on_confirm=None
    def ask(self,prompt,deadline):
        self.prompts.append(prompt)
        if prompt.startswith('U:'):return self.approved
        if self.on_confirm:self.on_confirm()
        return self.answer if self.answer is not None else prompt.rsplit('nonce ',1)[1].split(':')[0]

class Fixture:
    def __init__(self,root,stage='BASELINE_VERIFIED',action=None):
        self.root=Path(root).resolve();self.root.mkdir(exist_ok=True)
        self.target=self.root/'target';self.target.mkdir()
        self.objects=self.root/'evidence';self.objects.mkdir()
        self.sources=self.root/'independent-enrolled-sources';self.sources.mkdir()
        purpose='preparation' if stage in ('BASELINE_VERIFIED','RUN_INITIALIZED') else 'cutover-Q'
        provisional=fmod.Fixture(purpose=purpose)
        self.cset=cmod.descriptor()
        cb=self.cset['binding'];b=provisional.binding
        cb.update(source_sha=b['source_sha'],source_tree=b['source_tree'],tools_sha256=a.dr.digest(b['tools']),
            python_version=b['python_version'],runtime=b['restore_runtime'],source_identity_sha256=b['source_identity_sha256'],
            database_semantics_sha256=b['database_semantics_sha256'],restore_recipe_sha256=b['restore_recipe_sha256'],target_sha256=b['target_sha256'])
        self.cset['created_at']=fmod.UTC(fmod.DAY-1000)
        for asset in self.cset['assets']:
            asset['binding']=copy.deepcopy(cb);asset['identity']['created_at']=fmod.UTC(fmod.DAY-1100)
        for cp in self.cset['copies']:cp['created_at']=fmod.UTC(fmod.DAY-1100)
        cmod.synchronize_members(self.cset)
        groups=a.custody.group_versions(self.cset)
        def shape(kind,doc):
            if kind=='custody':doc['versions']=groups
            elif kind=='authorization':doc.update(mode='operational',packages=['AP-02','AP-03','AP-04','AP-05','AP-06'])
            elif kind=='offhost':doc['encryption'].update(mode='client-side',custody_version_sha256=groups['decryption'])
            elif kind in ('restore','functional'):doc['auth_scope']='real-custody'
            elif kind=='response':doc['mode']='operational'
            elif kind=='readiness':doc['result']='DR_POINT_PASS'
        self.f=fmod.Fixture(purpose=purpose,mutate=shape)
        f=self.f;b=f.binding;now=f.now
        self.wall=a.S.timestamp(now['utc']);self.mono=now['monotonic_seconds']
        script=next(x['sha256'] for x in b['tools'] if x['path']=='scripts/v126-cutover.sh')
        self.identity=dict(run_id='future-synthetic-run',release_sha=b['source_sha'],script_sha256=script,intent_sha256=H('dispatch-intent'),
            kind='INIT' if stage=='RUN_INITIALIZED' else 'STAGE',name=stage,
            action=action or ('initialize-run' if stage=='RUN_INITIALIZED' else a.adapter.STAGE_ACTIONS[stage][0]))
        cp=a.custody.CustodyPins(a.dr.digest(self.cset),self.cset['set_id'],1,a.dr.digest(cb),
            tuple(sorted((x['identity']['logical_id'],a.custody.asset_version(x)) for x in self.cset['assets'])),frozenset(),now)
        self.cproof=cmod.proof(self.cset,cp)
        self.cproof['observed']=copy.deepcopy(now);self.cproof['expires_at']=fmod.UTC(self.wall+300)
        robs=cmod.observations(self.cset,self.cproof)
        versions=sorted(x[1] for x in cp.current_versions)
        self.catalogue=dict(schema_version=1,kind='ap06-current-catalogue',source_identity=H('C-catalogue'),generation=1,revocation_generation=1,
            observed=copy.deepcopy(now),expires_at=fmod.UTC(self.wall+300),ledger_sha256=f.ledger_ref,ledger_sequence=len(f.events),ledger_head_sha256=f.events[-1],latest_attempt_outcome='QUALIFIED',
            authorizations=sorted(f.auths),responses=[f.response],revoked=[],ongoing_sha256=f.ongoing_ref,readiness_sha256=f.readiness_ref,
            custody_set_sha256=cp.set_sha256,custody_set_id=cp.set_id,custody_set_version=1,custody_binding_sha256=cp.binding_sha256,
            custody_current_versions=[dict(logical_id=k,sha256=v) for k,v in cp.current_versions],custody_required_versions=versions,
            documents=[],actions=[dict(identity=self.identity,scope='DISPATCH',valid_from=fmod.UTC(fmod.DAY-1),valid_until=fmod.UTC(self.wall+3600))])
        self.high=dict(schema_version=1,kind='ap06-high-water',source_identity=H('C-highwater'),catalogue_generation=1,revocation_generation=1,
            ledger_sequence=len(f.events),ledger_head_sha256=f.events[-1],observed=copy.deepcopy(now),expires_at=fmod.UTC(self.wall+300))
        self.producer=dict(schema_version=1,kind='ap06-producer-observations',source_identity=H('P-observer'),binding_sha256=f.binding_ref,
            source_sha=b['source_sha'],source_tree=b['source_tree'],tools_sha256=a.dr.digest(b['tools']),python_version=b['python_version'],target_sha256=b['target_sha256'],
            data_runtime=b['data_runtime'],restore_runtime=b['restore_runtime'],observed=copy.deepcopy(now),expires_at=fmod.UTC(self.wall+300),
            observed_evidence=sorted(f.observed),ongoing_sha256=f.ongoing_ref,availability=[],retrieval=dict(proof_sha256=robs.proof_sha256,
                environment_sha256=robs.environment_sha256,topology_sha256=robs.topology_sha256,
                packages=[dict(copy_id=k,sha256=v,size=n) for k,v,n in sorted(robs.packages)],
                completed=copy.deepcopy(self.cproof['retrievals']),observed=copy.deepcopy(now)))
        for point in f.points:
            q=f.evidence.get(point['qref'],'qualification');off=f.evidence.get(q['offhost_sha256'],'offhost')
            self.producer['availability'].append(dict(qualification_sha256=point['qref'],offhost_sha256=q['offhost_sha256'],
                **{k:copy.deepcopy(off[k]) for k in ('version_id','ciphertext','readback','downloaded_archive')},asset_versions=versions))
        self.clock=dict(schema_version=1,kind='ap06-clock-observation',source_identity=H('clock-reader'),clock_id=now['clock_id'],
            measured=copy.deepcopy(now),expires_at=fmod.UTC(self.wall+300),method_sha256=H('independent-clock-method'))
        self.anchor=dict(schema_version=1,kind='ap06-previously-approved-anchor',decision_id='test-only-prior-u-decision',decision_provenance_sha256=H('prior-external-decision'),
            generation=1,valid_from=fmod.UTC(fmod.DAY-1),valid_until=fmod.UTC(self.wall+3600),revocation_generation=1,custodian_identity=H('enrolled-c'),
            verifier_identity=H('enrolled-v'),principal_fingerprint='SHA256:'+'A'*43,host_fingerprint='SHA256:'+'B'*43,deployment_target=str(self.target),
            source_sha=b['source_sha'],source_tree=b['source_tree'],tooling_sha256=a.dr.digest(b['tools']),python_version=b['python_version'],binding_sha256=f.binding_ref,
            target_sha256=b['target_sha256'],source_identity_sha256=b['source_identity_sha256'],database_semantics_sha256=b['database_semantics_sha256'],
            data_runtime=b['data_runtime'],restore_runtime=b['restore_runtime'],post_v126_recipe_sha256=H('post-v126-recipe'),sources={},evidence_root=str(self.objects),
            scopes=['DISPATCH','COPY_ONLY'],initial_checkpoint=dict(catalogue_generation=1,ledger_sequence=len(f.events),ledger_head_sha256=f.events[-1],revocation_generation=1),
            clock_id=now['clock_id'],clock_method_sha256=self.clock['method_sha256'])
        for name,value in (('catalogue',self.catalogue),('highwater',self.high),('producer',self.producer),('clock',self.clock)):
            path=self.sources/(name+'.json');self.write(path,value)
            self.anchor['sources'][name]=dict(identity=value['source_identity'],owner_uid=os.geteuid(),path=str(path))
        for raw in [*f.documents.values(),a.dr.canonical(self.cset),a.dr.canonical(self.cproof)]:self.put(raw)
        self.catalogue['documents']=sorted(x.name for x in self.objects.iterdir())
        self.save('catalogue')
        self.anchorpath=self.root/'anchor.json';self.write(self.anchorpath,self.anchor)
        self.console=Console();self.console.approved=a.dr.digest(self.anchor)
        self.enrollment=a.load_enrolled_authority(self.anchorpath,self.console)
        self.native_calls=[]
        def native(context,history):
            self.native_calls.append(context)
            return a.adapter.NativeStage7(f.native,f.cutover['native_manifest_sha256'])
        self.verifier=a.VVerifier(self.enrollment,native,self.console)

    def write(self,path,value):
        path.write_bytes(a.dr.canonical(value));path.chmod(0o600)
    def save(self,name):self.write(Path(self.anchor['sources'][name]['path']),getattr(self,'high' if name=='highwater' else name))
    def put(self,raw):
        ref=a.dr.sha(raw);(self.objects/ref).write_bytes(raw);return ref
    def challenge(self,sequence=1,session='session',nonce=None):
        return dict(version=1,type='CHALLENGE',session_id=H(session),nonce=nonce or H('nonce-'+str(sequence)),sequence=sequence,phase='EARLY' if sequence==1 else 'LATE',
            operation_id=a.dr.digest(self.identity),identity=copy.deepcopy(self.identity),target=str(self.target),timeout=a.adapter.ACTION_SECONDS.get(self.identity['action'],300),
            history_sha256=H('history-'+str(sequence)),anchor_sha256=self.enrollment.sha256,anchor_generation=self.anchor['generation'],
            manifest_sha256=H('native-manifest') if self.identity['kind']=='INIT' else None,source_tree=self.anchor['source_tree'],tooling_sha256=self.anchor['tooling_sha256'],
            runtime=self.anchor['python_version'],principal_fingerprint=self.anchor['principal_fingerprint'],host_fingerprint=self.anchor['host_fingerprint'],
            native_history=[] if self.identity['name'] in ('BASELINE_VERIFIED','RUN_INITIALIZED') else [dict(identity=self.identity,request_sha256=H('request'),result_sha256=H('result'),log_sha256=H('log'),completed_at=fmod.UTC(self.wall-1),artifacts=[])])

    @contextmanager
    def isolated_observations(self):
        actual_run=subprocess.run;actual_popen=subprocess.Popen
        def run(argv,*args,**kwargs):
            if isinstance(argv,list) and argv[:1]==['git'] and argv[-3:]==['status','--porcelain','--untracked-files=all']:
                return subprocess.CompletedProcess(argv,0,b'',b'')
            return actual_run(argv,*args,**kwargs)
        def popen(argv,*args,**kwargs):
            if not isinstance(argv,list) or argv[:1]!=['git'] or 'rev-parse' not in argv:raise AssertionError('external execution forbidden')
            return actual_popen(argv,*args,**kwargs)
        with mock.patch.object(a.time,'time',side_effect=lambda:self.wall),mock.patch.object(a.time,'monotonic',side_effect=lambda:self.mono),\
                mock.patch.object(subprocess,'run',side_effect=run),mock.patch.object(subprocess,'Popen',side_effect=popen),\
                mock.patch.object(socket,'socket',side_effect=AssertionError('network forbidden')):
            yield

class AuthorityTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='policy-b-authority-',dir='/private/tmp' if Path('/private/tmp').exists() else None)
        self.addCleanup(self.tmp.cleanup);self.fx=Fixture(self.tmp.name)
    def verify(self,sequence=1):
        with self.fx.isolated_observations():return self.fx.verifier.verify(self.fx.challenge(sequence))
    def deny(self):self.assertEqual(self.verify()['decision'],'REFUSE')
    def test_positive_both_rounds_acquire_full_native_consumer_and_fresh_console(self):
        with mock.patch.object(a.dr,'consume_barrier',wraps=a.dr.consume_barrier) as consume:
            for sequence in (1,2):
                result=self.verify(sequence);self.assertEqual(result['decision'],'PASS',result)
                self.assertEqual(result['barrier'],'R0');self.assertEqual(set(result['expiry']),a.EXPIRY_KEYS)
                self.assertTrue(all(x>0 for x in result['expiry'].values()))
        self.assertEqual(consume.call_count,2);self.assertEqual(len(self.fx.console.prompts),3)
    def test_root_digest_is_external_and_candidate_digest_not_prompted(self):
        self.assertNotIn(self.fx.enrollment.sha256,self.fx.console.prompts[0])
        self.fx.console.approved=H('wrong')
        with self.assertRaises(ValueError):a.load_enrolled_authority(self.fx.anchorpath,self.fx.console)
    def test_q_and_init_use_same_real_consumer(self):
        for stage in ('V126_BACKEND_STARTED','RUN_INITIALIZED'):
            with self.subTest(stage=stage),tempfile.TemporaryDirectory(dir=self.tmp.name) as root:
                self.fx=Fixture(root,stage)
                self.assertEqual(self.verify()['decision'],'PASS')
                self.assertEqual(len(self.fx.native_calls),int(stage!='RUN_INITIALIZED'))
    def test_missing_producer_does_not_become_available_by_console(self):
        Path(self.fx.anchor['sources']['producer']['path']).unlink();self.deny()
    def test_retrieval_missing_route_record_or_renamed_operation_refuses(self):
        for key in ('packages','completed'):
            with self.subTest(key=key):
                original=copy.deepcopy(self.fx.producer['retrieval'][key]);self.fx.producer['retrieval'][key].pop();self.fx.save('producer')
                self.deny();self.fx.producer['retrieval'][key]=original;self.fx.save('producer');self.fx.verifier.seen.clear();self.fx.verifier.sessions.clear()
    def test_readiness_rehashed_without_independent_catalogue_cannot_replace(self):
        proof=copy.deepcopy(self.fx.f.evidence.get(self.fx.f.readiness_ref,'readiness'));proof['age_seconds']=0
        self.fx.put(a.dr.canonical(proof));(self.fx.objects/self.fx.f.readiness_ref).write_bytes(a.dr.canonical(proof));self.deny()
    def test_forged_producer_origin_and_binding_refuse(self):
        self.fx.producer['source_identity']=H('attacker');self.fx.save('producer');self.deny()
    def test_old_head_fork_and_revocation_refuse(self):
        self.fx.high['ledger_head_sha256']=H('fork');self.fx.save('highwater');self.deny()
    def test_revoked_verifier_refuses(self):
        self.fx.catalogue['revoked']=[self.fx.anchor['verifier_identity']];self.fx.save('catalogue');self.deny()
    def test_c_confirmation_cannot_renew_stale_machine_retrieval(self):
        self.fx.wall+=301;self.fx.mono+=301;self.deny()
    def test_source_changed_during_c_confirmation_refuses(self):
        def change():self.fx.catalogue['revocation_generation']=2;self.fx.save('catalogue')
        self.fx.console.on_confirm=change;self.deny()
    def test_wrong_nonce_from_nonconsole_stream_refuses(self):
        self.fx.console.answer=H('old-nonce');self.deny()
    def test_replayed_challenge_refuses_after_pass(self):
        self.assertEqual(self.verify()['decision'],'PASS');self.deny()
    def test_wrong_actual_action_or_scope_refuses_before_consumer(self):
        self.fx.identity['action']='start-v126';self.deny()
    def test_clock_method_future_rollback_and_ambiguous_error_refuse(self):
        self.fx.clock['method_sha256']=H('attacker-clock');self.fx.save('clock');self.deny()
    def test_source_changed_after_consumer_replay_refuses(self):
        actual=a.dr.consume_barrier
        def mutate(*args,**kwargs):
            result=actual(*args,**kwargs);self.fx.producer['ongoing_sha256']=H('changed');self.fx.save('producer');return result
        with mock.patch.object(a.dr,'consume_barrier',side_effect=mutate):self.deny()
    def test_copy_only_no_readiness_or_producer_but_exact_source_and_authority(self):
        identity=copy.deepcopy(self.fx.identity);identity.update(kind='COPY_ONLY',name='INIT_COMPLETION',action='copy-init-completion')
        self.fx.catalogue['actions'].append(dict(identity=identity,scope='COPY_ONLY',valid_from=fmod.UTC(fmod.DAY-1),valid_until=fmod.UTC(self.fx.wall+600)))
        self.fx.save('catalogue');(self.fx.objects/self.fx.f.readiness_ref).unlink();Path(self.fx.anchor['sources']['producer']['path']).unlink()
        with self.fx.isolated_observations(),mock.patch.object(a.dr,'consume_barrier',side_effect=AssertionError('copy must not consume readiness')):
            result=self.fx.verifier.authorize_action(identity,str(self.fx.target))
        self.assertGreater(result['expiry'],0)
    def test_source_record_symlink_or_writable_refuses(self):
        path=Path(self.fx.anchor['sources']['producer']['path']);path.chmod(0o666);self.deny()
    def test_expiry_has_action_reserve_and_uncertainty(self):
        result=self.verify();self.assertEqual(result['decision'],'PASS')
        self.assertLessEqual(result['expiry']['checkpoint'],299)
        self.assertEqual(result['expiry']['rpo'],86400-a.dr.age(self.fx.f.points[-1]['manifest']['point'],self.fx.verifier.last_validation['clock'])-900)
    def test_failed_early_round_cannot_be_followed_by_late_pass(self):
        self.fx.console.answer=H('wrong-nonce');self.deny();self.fx.console.answer=None
        self.assertEqual(self.verify(2)['decision'],'REFUSE')
    def test_late_round_reacquires_revocation(self):
        self.assertEqual(self.verify()['decision'],'PASS')
        self.fx.catalogue['revoked']=[self.fx.anchor['sources']['producer']['identity']];self.fx.save('catalogue')
        self.assertEqual(self.verify(2)['decision'],'REFUSE')
    def test_clock_future_rollback_and_uncertainty_are_actual_denials(self):
        for mutate in (lambda: setattr(self.fx,'wall',self.fx.wall-2),
                       lambda: setattr(self.fx,'mono',self.fx.mono-1),
                       lambda: self.fx.clock['measured'].update(error_seconds=60)):
            with self.subTest(mutate=mutate),tempfile.TemporaryDirectory(dir=self.tmp.name) as root:
                self.fx=Fixture(root);mutate();self.fx.save('clock');self.deny()
    def test_machine_availability_metadata_cannot_skip_full_readback_identity(self):
        self.fx.producer['availability'][0]['readback']['sha256']=H('head-only-observation');self.fx.save('producer');self.deny()
    def test_latest_unresolved_attempt_cannot_be_hidden_by_prior_qualified_point(self):
        self.fx.catalogue['latest_attempt_outcome']='UNKNOWN';self.fx.save('catalogue');self.deny()
    def test_timeout_during_currentness_does_not_call_consumer(self):
        def expire():raise a.adapter._ObservationTimeout()
        self.fx.console.on_confirm=expire
        with mock.patch.object(a.dr,'consume_barrier',side_effect=AssertionError('consumer must not run')):self.deny()
    def test_acquisition_and_consumer_share_single_three_hundred_second_budget(self):
        def delay():self.fx.mono+=200;self.fx.wall+=200
        self.fx.console.on_confirm=delay
        actual=a.adapter.Gate.verify_context
        def consume_delayed(gate,context,**kwargs):
            self.assertEqual(kwargs['observation_seconds'],100)
            self.fx.mono+=101;self.fx.wall+=101
            return actual(gate,context,**kwargs)
        with mock.patch.object(a.adapter.Gate,'verify_context',autospec=True,side_effect=consume_delayed):self.deny()
    def test_final_temporal_headroom_refuses_consumer_expired_during_replay(self):
        actual=a.dr.consume_barrier
        def expire(*args,**kwargs):
            result=actual(*args,**kwargs);self.fx.wall+=300;self.fx.mono+=300;return result
        with mock.patch.object(a.dr,'consume_barrier',side_effect=expire):self.deny()
    def test_root_revocation_and_source_drift_no_automatic_reenrollment(self):
        self.fx.anchor['generation']=2;self.fx.write(self.fx.anchorpath,self.fx.anchor);self.deny()
    def test_foreground_console_rejects_background_and_oversized_answers(self):
        for foreground,answer in ((False,b'x\n'),(True,b'x'*257)):
            with self.subTest(foreground=foreground),mock.patch.object(a.os,'open',return_value=5),mock.patch.object(a.os,'close'),\
                    mock.patch.object(a.os,'isatty',return_value=True),mock.patch.object(a.os,'tcgetpgrp',return_value=100),\
                    mock.patch.object(a.os,'getpgrp',return_value=100 if foreground else 101),mock.patch.object(a.os,'write'),\
                    mock.patch.object(a.os,'read',return_value=answer),mock.patch.object(a.select,'select',return_value=([5],[],[])),\
                    mock.patch.object(a.time,'monotonic',return_value=0),self.assertRaises(ValueError):
                a.ForegroundConsole().ask('prompt',1)
    def test_negative_controls_break_regressions_in_disposable_source_copies(self):
        source=(ROOT/'scripts/v126-policy-b-authority.py').read_text()
        mutants=[("        if parsed['source_identity'] != pin['identity']: refuse()",'        pass',
                  'test_forged_producer_origin_and_binding_refuse'),
                 ("    if answer != dr.sha(raw): refuse()",'    pass',
                  'test_root_digest_is_external_and_candidate_digest_not_prompted')]
        for number,(old,new,testname) in enumerate(mutants):
            self.assertEqual(source.count(old),1)
            module=types.ModuleType('authority_disposable_mutant_'+str(number))
            module.__file__=str(ROOT/'scripts/v126-policy-b-authority.py')
            sys.modules[module.__name__]=module
            exec(compile(source.replace(old,new),'<disposable-authority-copy>','exec'),module.__dict__)
            outcome=unittest.TestResult()
            with mock.patch.dict(globals(),a=module):AuthorityTests(testname).run(outcome)
            self.assertEqual(len(outcome.errors),0,outcome.errors)
            self.assertEqual(len(outcome.failures),1,'omitting the check must fail the corresponding regression')
            sys.modules.pop(module.__name__)
    def test_refusal_does_not_echo_unvalidated_transport_fields(self):
        challenge=self.fx.challenge();challenge['nonce']='SYNTHETIC_SECRET_CANARY'
        with self.assertRaises(ValueError) as caught:self.fx.verifier.verify(challenge)
        self.assertNotIn('SYNTHETIC_SECRET_CANARY',str(caught.exception))
    def test_copy_only_late_checkpoint_expiry_refuses(self):
        identity=copy.deepcopy(self.fx.identity);identity.update(kind='COPY_ONLY',name='INIT_COMPLETION',action='copy-init-completion')
        self.fx.catalogue['actions'].append(dict(identity=identity,scope='COPY_ONLY',valid_from=fmod.UTC(fmod.DAY-1),valid_until=fmod.UTC(self.fx.wall+600)))
        self.fx.save('catalogue')
        actual=self.fx.verifier.source_binding
        def delayed():
            result=actual();self.fx.wall+=299;self.fx.mono+=299;return result
        with self.fx.isolated_observations(),mock.patch.object(self.fx.verifier,'source_binding',side_effect=delayed),self.assertRaises(ValueError):
            self.fx.verifier.authorize_action(identity,str(self.fx.target))
    def test_native_history_accepts_exact_existing_isoformat_utc(self):
        with tempfile.TemporaryDirectory(dir=self.tmp.name) as root:
            self.fx=Fixture(root,'V126_BACKEND_STARTED');challenge=self.fx.challenge()
            challenge['native_history'][0]['completed_at']=datetime.fromtimestamp(self.fx.wall-1,timezone.utc).isoformat(timespec='microseconds')
            with self.fx.isolated_observations():self.assertEqual(self.fx.verifier.verify(challenge)['decision'],'PASS')
    def test_copy_scope_does_not_authorize_a_different_script(self):
        identity=copy.deepcopy(self.fx.identity);identity['script_sha256']=H('another-worker')
        self.fx.catalogue['actions'].append(dict(identity=identity,scope='COPY_ONLY',valid_from=fmod.UTC(fmod.DAY-1),valid_until=fmod.UTC(self.fx.wall+600)))
        self.fx.save('catalogue')
        with self.fx.isolated_observations(),self.assertRaises(ValueError):self.fx.verifier.authorize_action(identity,str(self.fx.target))
    def test_final_authority_return_does_not_renew_crossed_cadence_boundary(self):
        actual=a.adapter.Gate.verify_context
        def previously_validated(gate,context,**kwargs):
            result=actual(gate,context,**kwargs)
            # Model expensive source reread crossing the already validated grace
            # boundary. Only temporal observation is substituted, not consumer.
            self.fx.wall=fmod.DAY+28802;self.fx.mono=fmod.DAY+28802
            return result
        with mock.patch.object(a.adapter.Gate,'verify_context',autospec=True,side_effect=previously_validated):self.deny()
    def test_anchor_version_catalogue_and_revocation_are_independent_counters(self):
        self.fx.anchor['generation']=2
        self.fx.write(self.fx.anchorpath,self.fx.anchor)
        self.fx.console.approved=a.dr.digest(self.fx.anchor)
        self.fx.enrollment=a.load_enrolled_authority(self.fx.anchorpath,self.fx.console)
        self.fx.verifier=a.VVerifier(self.fx.enrollment,None,self.fx.console)
        self.assertEqual(self.verify()['decision'],'PASS')
    def test_copy_only_honors_retained_revocation_highwater(self):
        identity=copy.deepcopy(self.fx.identity)
        self.fx.catalogue['actions'].append(dict(identity=identity,scope='COPY_ONLY',valid_from=fmod.UTC(fmod.DAY-1),valid_until=fmod.UTC(self.fx.wall+600)))
        self.fx.save('catalogue')
        self.fx.anchor['initial_checkpoint']['revocation_generation']=2
        self.fx.write(self.fx.anchorpath,self.fx.anchor)
        self.fx.console.approved=a.dr.digest(self.fx.anchor)
        self.fx.enrollment=a.load_enrolled_authority(self.fx.anchorpath,self.fx.console)
        self.fx.verifier=a.VVerifier(self.fx.enrollment,None,self.fx.console)
        with self.fx.isolated_observations(),self.assertRaises(ValueError):self.fx.verifier.authorize_action(identity,str(self.fx.target))
    def test_foreground_console_has_no_stdin_fallback(self):
        with mock.patch.object(a.os,'open',side_effect=OSError('no tty')),self.assertRaises(OSError):a.ForegroundConsole().ask('test',1)

if __name__=='__main__':unittest.main(verbosity=2)
