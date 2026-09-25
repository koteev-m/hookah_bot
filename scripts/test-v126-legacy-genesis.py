#!/usr/bin/env python3
"""Synthetic independent legacy observations; no operational sources or effects."""
import copy
import importlib.util
from pathlib import Path
import sys
import json
import subprocess
from datetime import datetime, timedelta, timezone
import tempfile
import unittest
import types
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
def load(name, filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    module=importlib.util.module_from_spec(spec);sys.modules[name]=module;spec.loader.exec_module(module);return module

f = load('legacy_authority_fixture', 'test-v126-policy-b-authority.py')
a, g, H = f.a, f.a.genesis, f.H


def configure(fx, request=None, *, native=None):
    """Extend the existing AP06 fixture through its independently enrolled files.

    Only test code writes these sources. Production has no flag for this builder.
    Call again with exact prepared request bytes/document to independently approve
    the resulting action; creating a proposal does not itself update authority.
    """
    now=copy.deepcopy(fx.f.now)
    future=f.fmod.UTC(fx.wall+3600)
    if request is None:
        info=fx.target.stat()
        target=dict(path=str(fx.target),device=info.st_dev,inode=info.st_ino,uid=info.st_uid,host_fingerprint=fx.anchor['host_fingerprint'])
    else:
        target=copy.deepcopy(request['target'])
    pins={key:H('fixture-'+key) for key in g.PINS}
    runs=[]
    for index, run_id in enumerate(('v126-cutover-20260909t025024z-724dbe93',g.F782)):
        report=fx.put(b'Synthetic attributed historical report. No primary proof.\n')
        runs.append(dict(run_id=run_id,source_sha=str(index+1)*40,source_tree=str(index+3)*40,script_sha256=H('historical-'+str(index)),
            historical_outcome=g.HISTORICAL_F782 if index else 'UNKNOWN',primary=[],projections=[],
            reports=[dict(name='synthetic-recovered-report',sha256=report,claimed_disposition=g.HISTORICAL_F782 if index else 'UNKNOWN')],
            missing=['original-operation-result'],effects=[dict(effect_id='operator-session',kind='OPERATOR_SESSION',identity_sha256=H(run_id+'-session')),
                dict(effect_id='owned-effect',kind='DOCKER_REQUEST' if not index else 'FILESYSTEM_WRITER',identity_sha256=H(run_id+'-effect'))],
            disposition=dict(kind='LEGACY_UNKNOWN_FENCED',proof_sha256=H('pending'))))
    if native is not None:
        runs[0] = copy.deepcopy(native['run'])
    all_effects=[dict(run_id=row['run_id'],**effect) for row in runs for effect in row['effects']]
    def put(doc):return fx.put(a.dr.canonical(doc))
    def authorization(scope, ids, effects):
        return put(dict(schema_version=1,kind='legacy-disposition-authorization',authority_identity=pins['disposition_authority_identity'],target=target,
            run_ids=ids,effect_scope_sha256=g.digest(effects),scope=scope,source_sha=fx.anchor['source_sha'],valid_from=f.fmod.UTC(fx.wall-100),valid_until=future))
    run_ids=[row['run_id'] for row in runs]
    accept=authorization('ACCEPT_POSTSTATE',run_ids,all_effects)
    poststate=put(dict(schema_version=1,kind='legacy-accepted-poststate',target=target,runtime_sha='a'*40,image_id='sha256:'+H('synthetic-image'),database_version=125,
        admission_sha256=H('admission'),compose_sha256=H('compose'),maintenance_sha256=H('maintenance'),acceptance_method_sha256=pins['acceptance_method_sha256'],
        authorization_sha256=accept,observed=now,expires_at=future))
    source_documents=set()
    exclusion_evidence=put({'synthetic_observed_writer_exclusion':'not-an-operational-proof'})
    exclusion=put(dict(schema_version=1,kind='legacy-writer-exclusion',target=target,run_ids=run_ids,effect_scope_sha256=g.digest(all_effects),
        method_sha256=pins['writer_exclusion_method_sha256'],authorization_sha256=authorization('EXCLUDE_LEGACY_WRITERS',run_ids,all_effects),
        observed=now,expires_at=future,evidence=[exclusion_evidence]))
    observations=[]
    for run in runs:
        if run['disposition']['kind']=='NATIVE_TERMINAL': continue
        cessations=[]
        for effect in run['effects']:
            ref=put(dict(synthetic_observed_effect=effect['identity_sha256'],run_id=run['run_id'],effect_id=effect['effect_id']))
            row=dict(effect_id=effect['effect_id'],identity_sha256=effect['identity_sha256'],evidence_sha256=ref)
            cessations.append(row);observations.append(dict(run_id=run['run_id'],**row,observed=now))
        run['disposition']['proof_sha256']=put(dict(schema_version=1,kind='legacy-fenced-disposition',
            **{key:run[key] for key in ('run_id','source_sha','source_tree','script_sha256')},target=target,effect_scope_sha256=g.digest(run['effects']),
            fencing_method_sha256=pins['fencing_method_sha256'],fence_authorization_sha256=authorization('FENCE',[run['run_id']],run['effects']),
            acceptance_authorization_sha256=accept,accepted_poststate_sha256=poststate,observed=now,expires_at=future,cessations=cessations))
    inventory=dict(schema_version=1,kind='legacy-target-inventory',source_identity=fx.anchor['sources']['producer']['identity'],
        discovery_method_sha256=pins['discovery_method_sha256'],target=target,observed=now,expires_at=future,
        writer_exclusion_sha256=exclusion,current_poststate_sha256=poststate,runs=runs)
    inventory_ref=put(inventory)
    if request is None:
        request=dict(schema_version=1,kind='legacy-target-genesis-request',run_id=getattr(fx,'genesis_owner_run_id','new-synthetic-genesis-owner'),source_sha=fx.anchor['source_sha'],source_tree=fx.anchor['source_tree'],
            script_sha256=fx.identity['script_sha256'],tooling_sha256=fx.anchor['tooling_sha256'],python_version=fx.anchor['python_version'],target=target,
            next_init_manifest_sha256=H('prepared-init-proposal'),inventory_sha256=inventory_ref,created_at=f.fmod.UTC(fx.wall-10),expires_at=future,nonce=H('proposal-nonce'))
    else:
        if request['inventory_sha256']!=inventory_ref:raise ValueError('fixture proposal refers to another inventory')
    fx.request=copy.deepcopy(request);fx.inventory=inventory
    fx.identity=g.request_identity(request)
    fx.catalogue.update(kind='ap06-legacy-genesis-catalogue',legacy_inventory_sha256=inventory_ref,legacy_completion_sha256=None,
        documents=sorted(path.name for path in fx.objects.iterdir()),
        actions=[dict(identity=fx.identity,scope='LEGACY_GENESIS',valid_from=f.fmod.UTC(fx.wall-100),valid_until=future)])
    fx.producer=dict(schema_version=1,kind='ap06-legacy-observations',source_identity=fx.anchor['sources']['producer']['identity'],
        binding_sha256=fx.anchor['binding_sha256'],source_sha=fx.anchor['source_sha'],source_tree=fx.anchor['source_tree'],tools_sha256=fx.anchor['tooling_sha256'],
        python_version=fx.anchor['python_version'],target_sha256=fx.anchor['target_sha256'],observed=now,expires_at=future,inventory_sha256=inventory_ref,
        observed_documents=sorted(path.name for path in fx.objects.iterdir()),writer_exclusion_sha256=exclusion,current_poststate_sha256=poststate,
        cessations=observations,accepted_poststates=[poststate])
    fx.anchor['legacy_genesis']=pins
    fx.anchor['scopes']=['DISPATCH','COPY_ONLY','LEGACY_GENESIS','LEGACY_GENESIS_COPY']
    for name in ('catalogue','producer'):fx.save(name)
    fx.write(fx.anchorpath,fx.anchor);fx.console.approved=a.dr.digest(fx.anchor)
    fx.enrollment=a.load_enrolled_authority(fx.anchorpath,fx.console)
    fx.verifier=a.VVerifier(fx.enrollment,None,fx.console)
    fx.verifier.set_genesis_request(a.dr.canonical(fx.request))
    return fx


def configure_native(fx, request=None):
    """Actual supported native producer, protected receipts and remote history.

    Seed functions only create synthetic metadata. Both the portable validator
    and owned-SSH positive use the production full verifier on these bytes.
    Cache the exact fixture so independent approval after prepare changes no
    historical timestamp, manifest or evidence hash.
    """
    native=getattr(fx,'_native_genesis_fixture',None)
    if native is None:
        from datetime import datetime, timedelta
        seed=fx.root/'native-history-fixture';seed.mkdir(mode=0o700)
        # Redirect only a test builder's staging argument, before it writes any
        # manifest. No production admission/runtime check is overridden.
        command=r'''source "$1"
TEST_ROOT="$2"
eval "$(declare -f set_init_args | sed '1s/set_init_args/original_set_init_args/')"
set_init_args() {
  original_set_init_args "$@"
  local i
  for ((i=0;i<${#INIT_ARGS[@]};i++)); do
    if [[ "${INIT_ARGS[$i]}" == --staging-path ]]; then INIT_ARGS[$((i+1))]="$GENESIS_TEST_TARGET"; fi
  done
}
export GENESIS_TEST_TARGET="$3"
mkdir -p "$TEST_ROOT/release-worktree"
new_state "$CUTOVER_SCRIPT" legacy-native
'''
        result=subprocess.run(['bash','-ceu',command,'synthetic-native-genesis',str(ROOT/'scripts/test-v126-cutover.sh'),str(seed),str(fx.target)],capture_output=True,timeout=120)
        if result.returncode:raise AssertionError('native seed refused: '+result.stderr.decode())
        state=seed/'state-legacy-native'
        client=load('genesis_native_fixture_client','v126-policy-b-client.py')
        manifest=json.loads((state/'run.json').read_bytes());manifest['created_at']='2026-08-31T23:58:00Z'
        raw=g.canonical(manifest);req=client.init_request(state,raw);operation=g.digest(req['identity'])
        attest=dict(format_version=1,operation_id=operation,request_sha256=g.digest(req),manifest_sha256=g.digest(raw),metadata_sha256=g.digest(req['metadata']),writer='ATTENDED_VERIFIER',durable=True)
        completed=dict(format_version=1,identity=req['identity'],operation_id=operation,exit=0,outcome='SUCCEEDED',completion='LOCAL_METADATA_ATTESTED',request_sha256=g.digest(req),attestation=attest,completed_at='2026-08-31T23:59:00+00:00')
        complete=dict(request=req,result=completed,request_sha256=g.digest(req),result_sha256=g.digest(completed))
        for name,data in (('run.json',raw),('run.json.sha256',(g.digest(raw)+'\n').encode()),('init-completion.json',g.canonical(complete))):
            path=state/name;path.chmod(0o600);path.write_bytes(data);path.chmod(0o400)
        result=subprocess.run(['bash','-ceu','source "$1"; TEST_ROOT="$2"; seed_chain "$3" 1','synthetic-native-receipts',str(ROOT/'scripts/test-v126-cutover.sh'),str(seed),str(state)],capture_output=True,timeout=120)
        if result.returncode:raise AssertionError('native receipts refused: '+result.stderr.decode())
        baseline_path=state/'receipts/01-BASELINE_VERIFIED.receipt.json'
        baseline=json.loads(baseline_path.read_bytes())
        artifact_map={x['name']:x['sha256'] for x in baseline['artifacts']}
        blog_path=state/'artifacts/1-BASELINE_VERIFIED.operation.log'
        original=blog_path.read_bytes()
        prefix=b''.join(('ARTIFACT\t'+name+'\t'+artifact_map[name]+'\n').encode() for name in ('local-baseline','main-actions'))
        body=original
        for line in prefix.splitlines(keepends=True):body=body.replace(line,b'')
        reordered=prefix+body
        blog_path.chmod(0o600);blog_path.write_bytes(reordered);blog_path.chmod(0o400)
        for item in baseline['artifacts']:
            if item['name']=='operation-log':item['sha256']=g.digest(reordered)
        baseline_path.chmod(0o600);baseline_path.write_bytes(g.canonical(baseline));baseline_path.chmod(0o400)
        checksum=Path(str(baseline_path)+'.sha256');checksum.chmod(0o600);checksum.write_text(g.digest(baseline)+'\n');checksum.chmod(0o400)
        command=r'''source "$1"
load_state "$2"
predecessor_hash="$(verify_receipt BASELINE_VERIFIED)"
token_hash="$(hash_text "$PRE_V126_ROLLBACK_TOKEN")"
write_recovery_intent_and_terminal pre-v126 "$token_hash" BASELINE_VERIFIED "$predecessor_hash"
log="$STATE_DIR/recovery/pre-v126.operation.log"
printf 'ARTIFACT\trecovery-pre-v126\t%s\n' "$(hash_text synthetic-native-recovery)" > "$log"
chmod 0400 "$log"
write_recovery_receipt pre-v126 BASELINE_VERIFIED "$predecessor_hash" "$token_hash" "$log"
verify_recovery_receipt pre-v126 >/dev/null
'''
        result=subprocess.run(['bash','-ceu',command,'synthetic-native-genesis',str(ROOT/'scripts/v126-cutover.sh'),str(seed/'state-legacy-native')],capture_output=True,timeout=120)
        if result.returncode:raise AssertionError('native seed refused: '+result.stderr.decode())
        state=seed/'state-legacy-native';manifest=json.loads((state/'run.json').read_bytes())
        target=dict(path=str(fx.target),device=fx.target.stat().st_dev,inode=fx.target.stat().st_ino,uid=fx.target.stat().st_uid,host_fingerprint=fx.anchor['host_fingerprint'])
        owner=dict(run_id=manifest['run_id'],release_sha=manifest['release_sha'],script_sha256=manifest['script_sha256'])
        next_owner=dict(run_id=getattr(fx,'genesis_owner_run_id','new-synthetic-genesis-owner') if request is None else request['run_id'],release_sha=fx.anchor['source_sha'],script_sha256=fx.identity['script_sha256'])
        remote=seed/'remote';remote.mkdir(mode=0o700)
        def save(path,value):path.write_bytes(g.canonical(value));path.chmod(0o400)
        save(remote/'run.json',owner)
        completion=json.loads((state/'init-completion.json').read_bytes());initial=completion['request']['identity'];initial_op=g.digest(initial)
        save(remote/(initial_op+'.start.json'),dict(identity=initial,operation_id=initial_op,started_at='2026-08-31T23:58:10+00:00',boot_id='synthetic-legacy-boot'))
        save(remote/(initial_op+'.request.json'),completion['request']);save(remote/(initial_op+'.result.json'),completion['result'])

        baseline=json.loads((state/'receipts/01-BASELINE_VERIFIED.receipt.json').read_bytes())
        terminal=json.loads((state/'recovery/pre-v126.receipt.json').read_bytes())
        def operation(receipt,kind,name,action,raw):
            ident=dict(owner,intent_sha256=receipt['intent_sha256'],kind=kind,name=name,action=action)
            op=g.digest(ident);completed=datetime.fromisoformat(receipt['completed_at'].replace('Z','+00:00'))-timedelta(seconds=1)
            save(remote/(op+'.start.json'),dict(identity=ident,operation_id=op,started_at=(completed-timedelta(seconds=1)).isoformat(),boot_id='synthetic-legacy-boot'))
            log=remote/(op+'.log');log.write_bytes(raw);log.chmod(0o400)
            save(remote/(op+'.result.json'),dict(identity=ident,operation_id=op,exit=0,outcome='SUCCEEDED',children='REAPED',log_sha256=g.digest(raw),completed_at=completed.isoformat()))
        blog=(state/'artifacts/1-BASELINE_VERIFIED.operation.log').read_bytes()
        artifacts={x['name']:x['sha256'] for x in baseline['artifacts']}
        prefix=b''.join(('ARTIFACT\t'+name+'\t'+artifacts[name]+'\n').encode() for name in ('local-baseline','main-actions'))
        if not blog.startswith(prefix):raise AssertionError('native baseline prefix')
        operation(baseline,'STAGE','BASELINE_VERIFIED','baseline',blog[len(prefix):])
        operation(terminal,'RECOVERY','pre-v126','recover-pre-v126',(state/'recovery/pre-v126.operation.log').read_bytes())
        terminal_sha=g.digest((state/'recovery/pre-v126.receipt.json').read_bytes())
        handoff=dict(format_version=1,owner=owner,next_owner=next_owner,terminal_receipt_sha256=terminal_sha,target_sha256=g.digest(str(fx.target).encode()),
            operational_version='V125',backend_image='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',image_id='sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8',
            environment_sha256=H('synthetic-environment-reference'),compose_sha256=H('synthetic-compose'),caddy_runtime_sha256=H('synthetic-caddy'),config_owner='root:root',
            restart_policy='unless-stopped',handoff_approved_and_applied=True,approval_id='synthetic-applied-handoff',
            observed_at=(datetime.fromisoformat(terminal['completed_at'].replace('Z','+00:00'))+timedelta(seconds=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))
        files=[]
        for prefix,directory in (('state',state),('remote',remote)):
            for path in sorted(directory.rglob('*')):
                if not path.is_file():continue
                raw=path.read_bytes();ref=fx.put(raw)
                files.append(dict(path=prefix+'/'+str(path.relative_to(directory)),sha256=ref,bytes=len(raw)))
        history=fx.put(g.canonical(dict(format_version=1,inventory={x['path'].removeprefix('remote/'):x['sha256'] for x in files if x['path'].startswith('remote/')})))
        handoff_ref=fx.put(g.canonical(handoff))
        proof=dict(schema_version=1,kind='legacy-native-terminal-proof',run_id=owner['run_id'],source_sha=owner['release_sha'],source_tree=manifest['release_tree'],
            script_sha256=owner['script_sha256'],target=target,terminal_kind='NATIVE_PRE_V126',terminal_sha256=terminal_sha,handoff_sha256=handoff_ref,remote_history_sha256=history,files=files)
        proof_ref=fx.put(g.canonical(proof))
        run=dict(run_id=owner['run_id'],source_sha=owner['release_sha'],source_tree=manifest['release_tree'],script_sha256=owner['script_sha256'],historical_outcome='NATIVE_TERMINAL_PROVEN',
            primary=[dict(name='native-terminal',sha256=terminal_sha,bytes=len((state/'recovery/pre-v126.receipt.json').read_bytes()))],projections=[],reports=[],missing=[],
            effects=[dict(effect_id='native-session',kind='OPERATOR_SESSION',identity_sha256=H('synthetic-native-session'))],disposition=dict(kind='NATIVE_TERMINAL',proof_sha256=proof_ref))
        native=dict(run=run,proof=proof,documents={path.name:path.read_bytes() for path in fx.objects.iterdir()},next_owner=next_owner)
        fx._native_genesis_fixture=native
    return configure(fx,request,native=native)


def challenge(fx, sequence=1, session='genesis-session', *, mode='EXECUTE', completion=None):
    result=fx.challenge(sequence,session)
    result.update(manifest_sha256=g.digest(fx.request),native_history=[],genesis_mode=mode,genesis_completion_sha256=completion)
    return result


class GenesisAuthority(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='legacy-genesis-authority-',dir='/private/tmp' if Path('/private/tmp').exists() else None)
        self.addCleanup(self.tmp.cleanup);self.fx=configure(f.Fixture(self.tmp.name))
    def verify(self,sequence=1,**kwargs):
        with self.fx.isolated_observations():return self.fx.verifier.verify(challenge(self.fx,sequence,**kwargs))
    def test_actual_full_native_terminal_validator_then_authority(self):
        self.fx=configure_native(self.fx)
        native=self.fx._native_genesis_fixture
        result=g.verify_native_terminal(native['run'],native['proof'],native['documents'],next_owner=native['next_owner'])
        self.assertIs(type(result),g.NativeTerminalProof)
        real_run=subprocess.run
        def run(argv,*args,**kwargs):
            if isinstance(argv,list) and argv[:1]==['git'] and argv[-3:]==['status','--porcelain','--untracked-files=all']:
                return subprocess.CompletedProcess(argv,0,b'',b'')
            return real_run(argv,*args,**kwargs)
        with mock.patch.object(a.time,'time',side_effect=lambda:self.fx.wall),mock.patch.object(a.time,'monotonic',side_effect=lambda:self.fx.mono),mock.patch.object(subprocess,'run',side_effect=run):
            outcome=self.fx.verifier.verify(challenge(self.fx))
        self.assertEqual(outcome['decision'],'PASS',outcome)

    def test_native_remote_subsecond_completion_matches_existing_native_precision(self):
        self.fx=configure_native(self.fx);native=self.fx._native_genesis_fixture
        proof=copy.deepcopy(native['proof']);documents=dict(native['documents'])
        terminal=json.loads(documents[proof['terminal_sha256']])
        for entry in proof['files']:
            if entry['path'].startswith('remote/') and entry['path'].endswith('.result.json'):
                result=json.loads(documents[entry['sha256']])
                if result['identity']['kind']=='RECOVERY':
                    result['completed_at']=(datetime.fromisoformat(terminal['completed_at'].replace('Z','+00:00'))+timedelta(microseconds=500000)).isoformat()
                    raw=g.canonical(result);ref=g.digest(raw);documents[ref]=raw;entry.update(sha256=ref,bytes=len(raw))
        raw=g.canonical(dict(format_version=1,inventory={x['path'].removeprefix('remote/'):x['sha256'] for x in proof['files'] if x['path'].startswith('remote/')}))
        ref=g.digest(raw);documents[ref]=raw;proof['remote_history_sha256']=ref
        self.assertIs(type(g.verify_native_terminal(native['run'],proof,documents,next_owner=native['next_owner'])),g.NativeTerminalProof)

    def test_native_extra_valid_recovery_outside_chain_refuses(self):
        self.fx=configure_native(self.fx);native=self.fx._native_genesis_fixture
        proof=copy.deepcopy(native['proof']);documents=dict(native['documents'])
        handoff=json.loads(documents[proof['handoff_sha256']])
        after=datetime.fromisoformat(handoff['observed_at'].replace('Z','+00:00'))+timedelta(seconds=60)
        identity=dict(run_id=native['run']['run_id'],release_sha=native['run']['source_sha'],script_sha256=native['run']['script_sha256'],
            intent_sha256=H('extra-native-recovery'),kind='RECOVERY',name='post-v126-stop',action='recover-post-v126-stop')
        op=g.digest(identity);log=b'synthetic completed recovery outside the native terminal chain\n'
        records={op+'.start.json':g.canonical(dict(identity=identity,operation_id=op,started_at=after.isoformat(),boot_id='synthetic-legacy-boot')),
            op+'.log':log,op+'.result.json':g.canonical(dict(identity=identity,operation_id=op,exit=0,outcome='SUCCEEDED',children='REAPED',
                log_sha256=g.digest(log),completed_at=(after+timedelta(seconds=1)).isoformat()))}
        for name,raw in records.items():
            ref=g.digest(raw);documents[ref]=raw;proof['files'].append(dict(path='remote/'+name,sha256=ref,bytes=len(raw)))
        raw=g.canonical(dict(format_version=1,inventory={x['path'].removeprefix('remote/'):x['sha256'] for x in proof['files'] if x['path'].startswith('remote/')}))
        ref=g.digest(raw);documents[ref]=raw;proof['remote_history_sha256']=ref
        with self.assertRaises(ValueError):
            g.verify_native_terminal(native['run'],proof,documents,next_owner=native['next_owner'])
        # The independently selected catalogue/producer contains the complete
        # malformed snapshot. The production attended verifier must also refuse.
        malformed=copy.deepcopy(native);malformed.update(proof=proof,documents=documents)
        for raw in documents.values():self.fx.put(raw)
        malformed['run']['disposition']['proof_sha256']=self.fx.put(g.canonical(proof))
        configure(self.fx,native=malformed)
        real_run=subprocess.run
        def run(argv,*args,**kwargs):
            if isinstance(argv,list) and argv[:1]==['git'] and argv[-3:]==['status','--porcelain','--untracked-files=all']:
                return subprocess.CompletedProcess(argv,0,b'',b'')
            return real_run(argv,*args,**kwargs)
        with mock.patch.object(a.time,'time',side_effect=lambda:self.fx.wall),mock.patch.object(a.time,'monotonic',side_effect=lambda:self.fx.mono),mock.patch.object(subprocess,'run',side_effect=run):
            outcome=self.fx.verifier.verify(challenge(self.fx))
        self.assertEqual(outcome['decision'],'REFUSE',outcome)

    def test_native_recovery_chronology_refuses(self):
        self.fx=configure_native(self.fx);native=self.fx._native_genesis_fixture
        baseline=json.loads(native['documents'][next(x['sha256'] for x in native['proof']['files'] if x['path']=='state/receipts/01-BASELINE_VERIFIED.receipt.json')])
        terminal=json.loads(native['documents'][native['proof']['terminal_sha256']])
        handoff=json.loads(native['documents'][native['proof']['handoff_sha256']])
        variants={
            'start-after-result-and-handoff':('start','started_at',(datetime.fromisoformat(handoff['observed_at'].replace('Z','+00:00'))+timedelta(seconds=1)).isoformat()),
            'start-before-predecessor':('start','started_at',(datetime.fromisoformat(baseline['completed_at'].replace('Z','+00:00'))-timedelta(seconds=1)).isoformat()),
            'start-without-timezone':('start','started_at','2026-09-01T00:00:00'),
            'start-non-utc':('start','started_at','2026-09-01T00:00:00+01:00'),
            'result-after-native-and-handoff':('result','completed_at',(datetime.fromisoformat(handoff['observed_at'].replace('Z','+00:00'))+timedelta(seconds=1)).isoformat()),
        }
        for name,(suffix,field,value) in variants.items():
            with self.subTest(variant=name):
                proof=copy.deepcopy(native['proof']);documents=dict(native['documents'])
                for entry in proof['files']:
                    if entry['path'].startswith('remote/') and entry['path'].endswith('.'+suffix+'.json'):
                        doc=json.loads(documents[entry['sha256']])
                        if doc['identity']['kind']=='RECOVERY':
                            doc[field]=value;raw=g.canonical(doc);ref=g.digest(raw);documents[ref]=raw;entry.update(sha256=ref,bytes=len(raw))
                raw=g.canonical(dict(format_version=1,inventory={x['path'].removeprefix('remote/'):x['sha256'] for x in proof['files'] if x['path'].startswith('remote/')}))
                ref=g.digest(raw);documents[ref]=raw;proof['remote_history_sha256']=ref
                with self.assertRaises(ValueError):
                    g.verify_native_terminal(native['run'],proof,documents,next_owner=native['next_owner'])

    def test_native_missing_init_or_different_sidecar_refuses(self):
        self.fx=configure_native(self.fx);native=self.fx._native_genesis_fixture
        for variant in ('missing-init','different-sidecar','invalid-recovery','unresolved-read-attempt'):
            with self.subTest(variant=variant):
                proof=copy.deepcopy(native['proof']);documents=dict(native['documents'])
                if variant=='missing-init':
                    drop=[]
                    for entry in proof['files']:
                        if entry['path'].startswith('remote/') and entry['path'].endswith('.start.json'):
                            start=json.loads(documents[entry['sha256']])
                            if start['identity']['kind']=='INIT':drop.append(entry['path'].removesuffix('.start.json'))
                    proof['files']=[x for x in proof['files'] if not any(x['path'].startswith(y+'.') for y in drop)]
                elif variant=='different-sidecar':
                    entry=next(x for x in proof['files'] if x['path']=='state/init-completion.json')
                    value=json.loads(documents[entry['sha256']]);value['result']['completed_at']='2026-08-31T23:59:01+00:00'
                    raw=g.canonical(value);ref=g.digest(raw);documents[ref]=raw;entry.update(sha256=ref,bytes=len(raw))
                else:
                    path='state/recovery/unexpected.json' if variant=='invalid-recovery' else 'state/attempts/baseline.synthetic/started.proof'
                    raw=g.canonical({'synthetic':'incomplete'});ref=g.digest(raw);documents[ref]=raw
                    proof['files'].append(dict(path=path,sha256=ref,bytes=len(raw)))
                history=dict(format_version=1,inventory={x['path'].removeprefix('remote/'):x['sha256'] for x in proof['files'] if x['path'].startswith('remote/')})
                raw=g.canonical(history);ref=g.digest(raw);documents[ref]=raw;proof['remote_history_sha256']=ref
                with self.assertRaises((ValueError,RuntimeError)):
                    g.verify_native_terminal(native['run'],proof,documents,next_owner=native['next_owner'])

    def test_every_enrolled_disposition_authority_and_method_revocation_refuses(self):
        for value in self.fx.anchor['legacy_genesis'].values():
            with self.subTest(identity=value):
                self.fx.catalogue['revoked']=[value];self.fx.save('catalogue')
                self.assertEqual(self.verify()['decision'],'REFUSE')
                self.fx.verifier.seen.clear();self.fx.verifier.sessions.clear()

    def test_short_action_authority_and_anchor_do_not_cover_dispatch(self):
        self.fx.catalogue['actions'][0]['valid_until']=f.fmod.UTC(self.fx.wall+899);self.fx.save('catalogue')
        self.assertEqual(self.verify()['decision'],'REFUSE')
        self.fx.verifier.seen.clear();self.fx.verifier.sessions.clear()
        self.fx.catalogue['actions'][0]['valid_until']=f.fmod.UTC(self.fx.wall+3600);self.fx.save('catalogue')
        self.fx.anchor['valid_until']=f.fmod.UTC(self.fx.wall+899)
        self.fx.write(self.fx.anchorpath,self.fx.anchor);self.fx.console.approved=a.dr.digest(self.fx.anchor)
        self.fx.enrollment=a.load_enrolled_authority(self.fx.anchorpath,self.fx.console)
        self.fx.verifier=a.VVerifier(self.fx.enrollment,None,self.fx.console);self.fx.verifier.set_genesis_request(g.canonical(self.fx.request))
        self.assertEqual(self.verify()['decision'],'REFUSE')

    def test_method_substitution_and_self_asserted_fence_are_closed(self):
        inv=copy.deepcopy(self.fx.inventory);inv['fenced']=True
        with self.assertRaises(ValueError):g.validate_inventory(inv)
        self.fx.producer['source_identity']=H('not-enrolled');self.fx.save('producer')
        self.assertEqual(self.verify()['decision'],'REFUSE')

    def test_late_phase_reacquires_missing_machine_observation(self):
        self.assertEqual(self.verify()['decision'],'PASS')
        self.fx.producer['cessations'].pop();self.fx.save('producer')
        self.assertEqual(self.verify(2)['decision'],'REFUSE')

    def test_negative_control_omitting_disposition_gate_breaks_regression(self):
        source=(ROOT/'scripts/v126-policy-b-authority.py').read_text()
        old="validation = genesis.validate_basis(inventory, producer, documents, self.doc['legacy_genesis'], now, native_verifier)"
        self.assertEqual(source.count(old),1)
        mutant=types.ModuleType('genesis_disposable_authority');mutant.__file__=str(ROOT/'scripts/v126-policy-b-authority.py');sys.modules[mutant.__name__]=mutant
        exec(compile(source.replace(old,"validation = dict(headroom=200)"),'<disposable-genesis-gate-copy>','exec'),mutant.__dict__)
        outcome=unittest.TestResult()
        with mock.patch.dict(globals(),a=mutant,g=mutant.genesis),mock.patch.object(f,'a',mutant):
            GenesisAuthority('test_report_only_does_not_replace_observed_cessation').run(outcome)
        sys.modules.pop(mutant.__name__,None)
        self.assertEqual(outcome.errors,[],outcome.errors)
        self.assertEqual(len(outcome.failures),1,'skipping the gate must fail its refusal regression')

    def test_independent_fence_basis_fresh_both_rounds(self):
        count=len(self.fx.console.prompts)
        for seq in (1,2):
            result=self.verify(seq)
            self.assertEqual(result['decision'],'PASS',result)
            self.assertEqual(result['barrier'],'LEGACY_GENESIS')
            self.assertEqual(result['legacy_inventory'],self.fx.inventory)
            self.assertEqual(set(result['expiry']),g.EXPIRY_KEYS)
        self.assertEqual(len(self.fx.console.prompts),count+2)
    def test_report_only_does_not_replace_observed_cessation(self):
        self.fx.producer['cessations']=[];self.fx.save('producer')
        self.assertEqual(self.verify()['decision'],'REFUSE')
    def test_cannot_omit_724_or_rehash_request_inventory(self):
        reduced=copy.deepcopy(self.fx.inventory);reduced['runs'].pop(0)
        self.fx.request['inventory_sha256']=self.fx.put(g.canonical(reduced))
        self.fx.identity=g.request_identity(self.fx.request)
        self.fx.verifier.set_genesis_request(g.canonical(self.fx.request))
        self.assertEqual(self.verify()['decision'],'REFUSE')
    def test_f782_scope_cannot_be_attributed_to_724(self):
        reduced=copy.deepcopy(self.fx.inventory);reduced['runs'][0]['historical_outcome']=g.HISTORICAL_F782
        with self.assertRaises(ValueError):g.validate_inventory(reduced)
    def test_inventory_or_source_bytes_change_after_c_refuses(self):
        def mutate():self.fx.producer['inventory_sha256']=H('forged');self.fx.save('producer')
        self.fx.console.on_confirm=mutate
        self.assertEqual(self.verify()['decision'],'REFUSE')
    def test_revocation_and_catalogue_fork_refuse(self):
        self.fx.catalogue['revoked']=[self.fx.request['inventory_sha256']];self.fx.save('catalogue')
        self.assertEqual(self.verify()['decision'],'REFUSE')
    def test_independent_source_failure_refuses(self):
        Path(self.fx.anchor['sources']['producer']['path']).unlink()
        self.assertEqual(self.verify()['decision'],'REFUSE')
    def test_replay_and_cross_session_late_refuse(self):
        self.assertEqual(self.verify()['decision'],'PASS')
        self.assertEqual(self.verify()['decision'],'REFUSE')
        self.assertEqual(self.verify(2,session='another-session')['decision'],'REFUSE')
    def test_poststate_provenance_and_effect_scope_are_bound(self):
        self.fx.producer['accepted_poststates']=[H('forged-poststate')];self.fx.save('producer')
        self.assertEqual(self.verify()['decision'],'REFUSE')
    def test_copy_only_requires_current_exact_completion_authority_without_refence(self):
        completion=H('durable-success')
        self.fx.catalogue['actions'][0]['scope']='LEGACY_GENESIS_COPY'
        self.fx.catalogue['legacy_completion_sha256']=completion;self.fx.save('catalogue')
        Path(self.fx.anchor['sources']['producer']['path']).unlink()
        result=self.verify(mode='GENESIS_READBACK',completion=completion)
        self.assertEqual(result['decision'],'PASS',result)
        self.assertEqual(result['barrier'],'LEGACY_GENESIS_COPY')
    def test_copy_revoked_current_action_refuses_without_requiring_old_fence(self):
        completion=H('durable-success')
        self.fx.catalogue['actions'][0]['scope']='LEGACY_GENESIS_COPY'
        self.fx.catalogue['legacy_completion_sha256']=completion
        refs=(self.fx.identity['intent_sha256'],a.dr.digest(self.fx.identity),a.dr.digest(self.fx.catalogue['actions'][0]))
        Path(self.fx.anchor['sources']['producer']['path']).unlink()
        for ref in refs:
            with self.subTest(revoked=ref):
                self.fx.catalogue['revoked']=[ref];self.fx.save('catalogue')
                self.assertEqual(self.verify(mode='GENESIS_READBACK',completion=completion)['decision'],'REFUSE')
                self.fx.verifier.seen.clear();self.fx.verifier.sessions.clear()

    def test_prepare_pause_same_bytes_and_exact_action_approval(self):
        before=g.canonical(self.fx.request)
        self.fx.wall+=1;self.fx.mono+=1
        self.assertEqual(self.verify()['decision'],'PASS')
        self.assertEqual(g.canonical(self.fx.request),before)
    def test_future_expired_and_action_budget_refuse(self):
        self.fx.wall+=301;self.fx.mono+=301
        self.assertEqual(self.verify()['decision'],'REFUSE')

if __name__=='__main__':unittest.main()
