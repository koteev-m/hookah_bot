#!/usr/bin/env python3
"""Real owned Linux SSH, actual caller/launcher/supervisor/consumer with captured leaves.

All authority, clock and Git observations are explicitly synthetic fixtures. The
copied enrolled source only replaces remote_dispatch_action, the privileged leaf;
source/envelope validation, admission, flock, native history and INIT execute.
No operational target, app, provider, DB, Docker socket or user HOME is available.
"""
import copy
import fcntl
import shlex
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
import pty
import re
import select
import signal
import time
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
SHA = 'e20bfeb008b1c7a52b3f3bb88a81fe7228da45d3'
TREE = 'b454a3898395febcb18641943585860efe8c8769'
if '--require-linux-ssh' not in sys.argv:
    raise SystemExit('explicit --require-linux-ssh required; no skipped PASS')
sys.argv.remove('--require-linux-ssh')
if sys.platform != 'linux' or os.geteuid() != 0:
    raise SystemExit('owned Linux root runtime required')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


transport_tests = load('linux_transport_fixture', ROOT/'test-v126-policy-b-transport.py')
T = transport_tests.T


@contextmanager
def git_observations():
    """Only source-control metadata is synthetic; actual tool hashing stays real."""
    real_run, real_output = subprocess.run, subprocess.check_output
    def result(argv):
        if not isinstance(argv, list) or argv[:1] != ['git']:
            return None
        if argv[-3:] == ['rev-parse', 'HEAD', 'HEAD^{tree}']:
            return (SHA+'\n'+TREE+'\n').encode()
        if argv[-2:] == ['rev-parse', 'HEAD']:
            return (SHA+'\n').encode()
        if argv[-2:] == ['rev-parse', 'HEAD^{tree}']:
            return (TREE+'\n').encode()
        if argv[-3:] == ['status', '--porcelain', '--untracked-files=all']:
            return b''
        if len(argv)>2 and argv[-2]=='show' and argv[-1]==SHA+':scripts/v126-cutover.sh':
            return (Path(argv[argv.index('-C')+1])/'scripts/v126-cutover.sh').read_bytes()
        return None
    def run(argv, *args, **kwargs):
        raw = result(argv)
        return subprocess.CompletedProcess(argv, 0, raw, b'') if raw is not None else real_run(argv,*args,**kwargs)
    def output(argv, *args, **kwargs):
        raw = result(argv)
        return raw if raw is not None else real_output(argv,*args,**kwargs)
    with mock.patch.object(subprocess,'run',side_effect=run), mock.patch.object(subprocess,'check_output',side_effect=output):
        yield


class LinuxCaller(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='policy-b-linux-caller-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source_root = self.root/'source'
        (self.source_root/'scripts').mkdir(parents=True)
        (self.source_root/'docs').mkdir()
        for path in ROOT.glob('*'):
            if path.is_file() and (path.name.startswith('v126-') or path.name.startswith('test-v126-')):
                shutil.copyfile(path,self.source_root/'scripts'/path.name)
        shutil.copyfile(ROOT.parent/'docs/V126_DATABASE_RECOVERY_REHEARSAL.md',self.source_root/'docs/V126_DATABASE_RECOVERY_REHEARSAL.md')
        # The generic native fixture orders artifacts for set checks. Real
        # baseline output has the two local lines first, before remote bytes.
        native_seed=self.source_root/'scripts/test-v126-cutover.sh'
        seed_text=native_seed.read_text()
        seed_text=seed_text.replace('    operation_payload = "".join(',
            '    if stage == "BASELINE_VERIFIED":\n'
            '        artifact_pairs.sort(key=lambda x: (0 if x[0] == "local-baseline" else 1 if x[0] == "main-actions" else 2, x[0]))\n'
            '    operation_payload = "".join(',1)
        native_seed.write_text(seed_text)
        self.audit_path = self.root/'transfer-audit.jsonl'
        self.audit_helper = self.root/'audit-helper.py'
        self.audit_helper.write_text('''import fcntl,json,os,sys,time
from pathlib import Path
LOG=Path(AUDIT_PATH)
TARGET=Path(TARGET_PATH)
def event(kind,**fields):
    root=TARGET/'.v126-target-operations';lock=root/'lock'
    held=False;inode=None
    try:
        fd=os.open(lock,os.O_RDONLY|os.O_NOFOLLOW)
        try:
            inode=os.fstat(fd).st_ino
            try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB);fcntl.flock(fd,fcntl.LOCK_UN)
            except BlockingIOError:held=True
        finally:os.close(fd)
    except FileNotFoundError:pass
    value=dict(event=kind,pid=os.getpid(),time=time.monotonic(),lock_held=held,inode=inode,
               starts=sorted(p.stem.removesuffix('.start') for p in root.glob('*.start.json')),
               results=sorted(p.stem.removesuffix('.result') for p in root.glob('*.result.json')),**fields)
    with LOG.open('a') as out:out.write(json.dumps(value,sort_keys=True)+'\\n')
if __name__=='__main__':event('LEAF',action=sys.argv[1])
'''.replace('AUDIT_PATH',repr(str(self.audit_path))).replace('TARGET_PATH',repr(str(self.root/'authority/target'))))
        # Passive instrumentation only in this disposable enrolled source.
        # Actual authentication, history, gates and transfer code remain intact.
        audit_transport=self.source_root/'scripts/v126-policy-b-transport.py'
        with audit_transport.open('a') as output:
            output.write("\n# Test-only passive transfer observation.\n"+
                "_audit_namespace={'__name__':'passive_transfer_observer'}\n"+
                "exec(compile(Path("+repr(str(self.audit_helper))+").read_bytes(),'owned-audit','exec'),_audit_namespace)\n"+
                "_audit_event=_audit_namespace['event']\n"+'''
_audit_receiving={'active':False,'received':0,'size':0}
_audit_send=FramedChannel.send
_audit_recv=FramedChannel.recv_bytes
_audit_tempfile=tempfile.TemporaryFile
def _observed_send(self,value,**kwargs):
    if value.get('type')=='PAYLOAD_REQUEST':
        _audit_receiving.update(active=True,received=0,size=value['size'])
        _audit_event('PAYLOAD_REQUEST',operation=value['operation_id'])
    elif value.get('type')=='CHALLENGE':_audit_event('CHALLENGE',phase=value['phase'],operation=value['operation_id'])
    return _audit_send(self,value,**kwargs)
def _observed_recv(self,**kwargs):
    value=_audit_recv(self,**kwargs)
    if _audit_receiving['active']:
        _audit_receiving['received']+=len(value)
        _audit_event('PAYLOAD_BYTES',size=len(value))
    return value
class _ObservedSpool:
    def __init__(self,wrapped):self.wrapped=wrapped
    def write(self,value):
        _audit_event('SPOOL_WRITE',size=len(value))
        result=self.wrapped.write(value)
        if _audit_receiving['received']>=_audit_receiving['size']:_audit_receiving['active']=False
        return result
    def __getattr__(self,name):return getattr(self.wrapped,name)
def _observed_tempfile(*args,**kwargs):
    output=_audit_tempfile(*args,**kwargs)
    if _audit_receiving['active']:
        _audit_event('SPOOL_OPEN')
        return _ObservedSpool(output)
    return output
FramedChannel.send=_observed_send
FramedChannel.recv_bytes=_observed_recv
tempfile.TemporaryFile=_observed_tempfile
''')
        cutover = self.source_root/'scripts/v126-cutover.sh'
        with cutover.open('a') as output:
            output.write("\nremote_dispatch_action() {\n  python3 "+shlex.quote(str(self.audit_helper))+" \"$1\"\n"+
                "  printf 'CAPTURED_ACTION\\t%s\\n' \"$1\"\n"+
                "  case \"$1\" in preflight-upload|image-upload) printf 'CAPTURED_PAYLOAD\\t'; sha256sum <&8 ;; esac\n"+
                "  printf 'ARTIFACT\\tsynthetic-leaf\\t%s\\n' 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'\n}\n")
        self.ssh = transport_tests.OwnedSSH(self.root/'ssh',source_root=self.source_root/'scripts')
        self.addCleanup(self.ssh.close)
        for name in list(sys.modules):
            if name.startswith('v126_'):sys.modules.pop(name)
        self.authority_cases = load('linux_authority_cases',self.source_root/'scripts/test-v126-policy-b-authority.py')
        with git_observations():
            self.fx = self.authority_cases.Fixture(self.root/'authority',stage='RUN_INITIALIZED')
        self.client = load('linux_real_client',self.source_root/'scripts/v126-policy-b-client.py')
        self.state = self.root/'local-state'
        self.manifest = T.canonical(dict(created_at='2026-09-01T00:00:00Z',format_version=1,
            database_url_file=str(self.root/'unused-database'),maintenance_identities_file=str(self.root/'unused-identities'),
            main_actions_run_id=123456,release_parents=['a'*40],release_sha=SHA,release_tree=TREE,
            release_worktree=str(self.source_root),remote='owned-fixture-only',run_id='future-synthetic-run',
            script_sha256=hashlib.sha256(cutover.read_bytes()).hexdigest(),staging_path=str(self.fx.target),
            v125_image_tag='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',
            v126_image_id='sha256:'+'a'*64,v126_image_tag='synthetic:'+SHA))
        self.request = self.client.init_request(self.state,self.manifest)
        self.fx.identity = self.request['identity']
        self.fx.anchor.update(principal_fingerprint=self.ssh.principal_fingerprint,host_fingerprint=self.ssh.host_fingerprint)
        self.fx.write(self.fx.anchorpath,self.fx.anchor)
        self.fx.console.approved=self.authority_cases.a.dr.digest(self.fx.anchor)
        self.fx.enrollment=self.authority_cases.a.load_enrolled_authority(self.fx.anchorpath,self.fx.console)
        self.fx.catalogue['actions'][0]['identity']=self.fx.identity
        self.fx.save('catalogue')
        self.bindings = load('linux_actual_bindings',self.source_root/'scripts/v126-operation-bindings.py')
        # An already accepted synthetic target anchor. INIT cannot create/adopt it.
        self.history=self.fx.target/'.v126-target-operations'
        self.history.mkdir(mode=0o700);(self.history/'lock').touch(mode=0o600)
        self.owner={key:self.fx.identity[key] for key in ('run_id','release_sha','script_sha256')}
        self.bindings.binding_create(self.history/'run.json',self.owner)
        self.configure(self.fx)
        self.answers=[]; self.writes=[]; self.acks=[]; self.native_calls=[]

    def configure(self,fx):
        self.ssh.config.update(target=str(fx.target),anchor_sha256=fx.enrollment.sha256,
            source_sha=fx.anchor['source_sha'],source_tree=fx.anchor['source_tree'],revocation_generation=fx.anchor['revocation_generation'],
            tooling_sha256=fx.anchor['tooling_sha256'],runtime=fx.anchor['python_version'])
        self.ssh.config_path.chmod(0o600)
        self.ssh.config_path.write_bytes(T.canonical(self.ssh.config));self.ssh.config_path.chmod(0o400)

    def verifier(self,fx=None):
        fx=fx or self.fx
        authority=self.authority_cases.a
        def native(context,history):
            self.native_calls.append(context)
            return self.client.verify_native_stage7(self.state,context,history)
        actual=authority.VVerifier(fx.enrollment,native,fx.console)
        outer=self
        class AttendedSyntheticSources:
            def verify(self,challenge):
                # Keep transport/sshd clocks and sockets real, isolate only the V
                # external observations. The actual consumer still runs twice.
                with git_observations(), mock.patch.object(authority.time,'time',return_value=fx.wall), \
                        mock.patch.object(authority.time,'monotonic',return_value=fx.mono):
                    answer=actual.verify(challenge)
                outer.answers.append((challenge,answer))
                return answer
        return AttendedSyntheticSources()

    def initialize(self):
        def writer(message):
            answer=self.client.write_metadata(self.state,self.manifest,message['request'])
            self.writes.append(answer);return answer
        def completion(message):
            proof=self.client.complete_proof(self.request,message['result'])
            self.client.save_completion(self.state,proof);self.acks.append(proof)
        opened=self.client.make_open(self.fx.enrollment,self.manifest,self.fx.identity,self.request)
        result=T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(),
            init_writer=writer,completion_writer=completion)
        self.assertEqual(result['status'],0)
        self.assertEqual([value[1]['decision'] for value in self.answers],['PASS','PASS'])
        self.assertEqual(len(self.writes),1);self.assertEqual(len(self.acks),1)
        self.assertEqual(self.client.require_completion(self.state),self.acks[0])
        return result

    def baseline(self):
        identity=dict(self.owner,intent_sha256='d'*64,kind='STAGE',name='BASELINE_VERIFIED',action='baseline')
        self.fx.catalogue['actions'][0]['identity']=identity;self.fx.save('catalogue')
        environment={'V126_INTERNAL_REMOTE_'+name:'NONE' for name in self.client.ENVELOPE_NAMES}
        values=dict(ACTION='baseline',RUN_ID=identity['run_id'],RELEASE_SHA=identity['release_sha'],
            STAGING_PATH=str(self.fx.target),SCRIPT_SHA256=identity['script_sha256'],INTENT_HASH=identity['intent_sha256'],
            OPERATION_KIND='STAGE',OPERATION_NAME='BASELINE_VERIFIED',V126_IMAGE_ID='sha256:'+'a'*64,
            MODE='true',ENVELOPE_VALIDATED='V126_INTERNAL_REMOTE_ENVELOPE_V1')
        environment.update({'V126_INTERNAL_REMOTE_'+key:value for key,value in values.items()})
        args=[str(self.fx.target),identity['run_id'],identity['release_sha']]
        request=dict(args=args,environment=environment)
        opened=self.client.make_open(self.fx.enrollment,self.manifest,identity,request,arguments=args,
            environment=environment,completion=self.acks[0])
        output=[]
        result=T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(),output_sink=output.append)
        self.assertEqual(result['status'],0)
        raw=b''.join(output)
        self.assertIn(b'CAPTURED_ACTION\tbaseline\n',raw)
        self.assertIn(b'REMOTE_OPERATION_ACK',raw)
        return result,raw

    def native_fixture(self,stage,action=None):
        self.initialize()
        seed=['bash','-c','set -Eeuo pipefail; source "$1"; seed_chain "$2" 7',
              'owned-native-fixture',str(self.source_root/'scripts/test-v126-cutover.sh'),str(self.state)]
        done=subprocess.run(seed,capture_output=True,timeout=30)
        self.assertEqual(done.returncode,0,done.stderr.decode())
        fmod=self.authority_cases.fmod
        with git_observations():provisional=fmod.Fixture(purpose='cutover-Q')
        receipt=self.state/'receipts/07-QUIESCED_BACKUP_REHEARSED.receipt.json'
        seeded=json.loads(receipt.read_bytes());native=json.loads(provisional.native)
        for key in ('authorization_receipt_sha256','intent_sha256','predecessor_receipt_sha256'):
            native[key]=seeded[key]
        log=self.state/'artifacts/7-QUIESCED_BACKUP_REHEARSED.operation.log'
        pairs={x['name']:x['sha256'] for x in native['artifacts'] if x['name']!='operation-log'}
        log.chmod(0o600);log.write_bytes(b''.join(('ARTIFACT\t'+k+'\t'+v+'\n').encode() for k,v in sorted(pairs.items())));log.chmod(0o400)
        log_sha=hashlib.sha256(log.read_bytes()).hexdigest()
        for item in native['artifacts']:
            if item['name']=='operation-log':item['sha256']=log_sha
        receipt.chmod(0o600);receipt.write_bytes(T.canonical(native));receipt.chmod(0o400)
        checksum=Path(str(receipt)+'.sha256');checksum.chmod(0o600)
        checksum.write_text(hashlib.sha256(receipt.read_bytes()).hexdigest()+'\n');checksum.chmod(0o400)
        mapping={'native-auth':native['authorization_receipt_sha256'],'native-intent':native['intent_sha256'],
            'stage6':native['predecessor_receipt_sha256'],'log':log_sha,
            'native-manifest':hashlib.sha256(self.manifest).hexdigest()}
        original_hash=fmod.H
        with git_observations(),mock.patch.object(fmod,'H',lambda text:mapping.get(text,original_hash(text))):
            q=self.authority_cases.Fixture(self.root/'q-authority',stage=stage,action=action)
        self.assertEqual(q.f.native,receipt.read_bytes())
        q.target=self.fx.target
        q.anchor.update(deployment_target=str(q.target),principal_fingerprint=self.ssh.principal_fingerprint,
                        host_fingerprint=self.ssh.host_fingerprint)
        q.write(q.anchorpath,q.anchor);q.console.approved=self.authority_cases.a.dr.digest(q.anchor)
        q.enrollment=self.authority_cases.a.load_enrolled_authority(q.anchorpath,q.console)
        self.configure(q)
        stages=self.bindings.BINDING_STAGES if hasattr(self.bindings,'BINDING_STAGES') else None
        if stages is None:
            stages=(self.source_root/'scripts/v126-cutover.sh').read_text().split('readonly -a V126_STAGES=(\n',1)[1].split('\n)',1)[0].split()
        index=stages.index(stage)+1
        for number,name in enumerate(stages[:index-1],1):
            local=self.state/'receipts'/f'{number:02d}-{name}.receipt.json'
            document=json.loads(local.read_bytes()) if local.exists() else None
            intent=document['intent_sha256'] if document else hashlib.sha256(name.encode()).hexdigest()
            payload=(self.state/'artifacts'/f'{number}-{name}.operation.log').read_bytes() if document else b'CAPTURED_SYNTHETIC_PREVIOUS_STAGE\n'
            if name=='BASELINE_VERIFIED':
                payload=b''.join(line for line in payload.splitlines(keepends=True)
                                 if not line.startswith((b'ARTIFACT\tlocal-baseline\t',b'ARTIFACT\tmain-actions\t')))
            for previous_action in self.bindings.binding_action_sequence('STAGE',name):
                identity=dict(self.owner,kind='STAGE',name=name,action=previous_action,intent_sha256=intent)
                operation=T.digest(identity)
                self.bindings.binding_create(self.history/(operation+'.start.json'),dict(identity=identity,operation_id=operation,
                    started_at='2026-09-01T00:00:00+00:00',boot_id='synthetic-prior-accepted'))
                self.bindings.binding_create(self.history/(operation+'.request.json'),dict(format_version=1,identity=identity,
                    target_sha256=hashlib.sha256(str(q.target).encode()).hexdigest(),
                    args=[str(q.target),identity['run_id'],identity['release_sha']],environment={}))
                path=self.history/(operation+'.log');path.write_bytes(payload);path.chmod(0o400)
                self.bindings.binding_create(self.history/(operation+'.result.json'),dict(identity=identity,operation_id=operation,
                    exit=0,outcome='SUCCEEDED',children='REAPED',log_sha256=hashlib.sha256(payload).hexdigest(),
                    completed_at='2026-09-01T00:00:01+00:00'))
        # A later action of the same stage requires its already accepted earlier
        # action; use the same independently bound intent, as the real sequencer.
        for previous_action in self.bindings.binding_action_sequence('STAGE',stage):
            if previous_action==q.identity['action']:break
            identity=dict(q.identity,action=previous_action);operation=T.digest(identity)
            self.bindings.binding_create(self.history/(operation+'.start.json'),dict(identity=identity,operation_id=operation,
                started_at='2026-09-01T00:00:00+00:00',boot_id='synthetic-prior-accepted'))
            self.bindings.binding_create(self.history/(operation+'.request.json'),dict(format_version=1,identity=identity,
                target_sha256=hashlib.sha256(str(q.target).encode()).hexdigest(),args=[str(q.target),identity['run_id'],identity['release_sha']],environment={}))
            payload=b'CAPTURED_SYNTHETIC_PREVIOUS_ACTION\n'
            path=self.history/(operation+'.log');path.write_bytes(payload);path.chmod(0o400)
            self.bindings.binding_create(self.history/(operation+'.result.json'),dict(identity=identity,operation_id=operation,
                exit=0,outcome='SUCCEEDED',children='REAPED',log_sha256=hashlib.sha256(payload).hexdigest(),completed_at='2026-09-01T00:00:01+00:00'))
        self.answers=[]
        return q,stages,index

    def q_request(self,q,stages,index):
        identity=q.identity
        environment={'V126_INTERNAL_REMOTE_'+name:'f'*64 for name in self.client.ENVELOPE_NAMES}
        values=dict(ACTION=identity['action'],RUN_ID=identity['run_id'],RELEASE_SHA=identity['release_sha'],
            STAGING_PATH=str(q.target),SCRIPT_SHA256=identity['script_sha256'],INTENT_HASH=identity['intent_sha256'],
            OPERATION_KIND='STAGE',OPERATION_NAME=identity['name'],V126_IMAGE_ID='sha256:'+'a'*64,
            MODE='true',ENVELOPE_VALIDATED='V126_INTERNAL_REMOTE_ENVELOPE_V1',
            PREDECESSOR_STAGE=stages[index-2],AUTHORIZATION_GATE='A' if index<=12 else ('B' if index<=14 else 'C'),
            MAINTENANCE_SMOKE_SHA256='f'*64 if index>=10 else 'NONE',
            MAINTENANCE_OFF_SHA256='f'*64 if index>=18 else 'NONE')
        environment.update({'V126_INTERNAL_REMOTE_'+key:value for key,value in values.items()})
        args=[str(q.target),identity['run_id'],identity['release_sha']]
        request=dict(args=args,environment=environment)
        payload=None;metadata=None
        if identity['action'] in ('preflight-upload','image-upload'):
            payload=self.root/'synthetic-payload';payload.write_bytes(b'synthetic-payload'*8192)
            metadata=dict(size=payload.stat().st_size,sha256=hashlib.sha256(payload.read_bytes()).hexdigest())
        opened=self.client.make_open(q.enrollment,self.manifest,identity,request,arguments=args,
            environment=environment,completion=self.acks[0],payload=metadata)
        return opened,payload

    def execute_q(self,q,stages,index):
        identity=q.identity
        opened,payload=self.q_request(q,stages,index)
        output=[]
        result=T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(q),payload=payload,output_sink=output.append)
        self.assertEqual(result['status'],0)
        self.assertIn(('CAPTURED_ACTION\t'+identity['action']+'\n').encode(),b''.join(output))
        self.assertEqual([x[1]['decision'] for x in self.answers],['PASS','PASS'])
        self.assertEqual([x[1]['barrier'] for x in self.answers],['Q','Q'])
        self.assertEqual(len(self.native_calls),2)

    def transfer_events(self):
        return [json.loads(line) for line in self.audit_path.read_text().splitlines()] if self.audit_path.exists() else []

    def reset_transfer_events(self):
        self.audit_path.write_text('')

    def test_upload_refusals_precede_request_spool_and_received_bytes(self):
        failures=('busy','missing-registry','wrong-target','wrong-history','EARLY-refusal')
        for number,failure in enumerate(failures):
            with self.subTest(failure=failure):
                if number:self.tearDown();self.doCleanups();self.setUp()
                q,stages,index=self.native_fixture('FINAL_V125_PREFLIGHT_PASSED','preflight-upload')
                opened,payload=self.q_request(q,stages,index)
                operation=T.digest(q.identity)
                held=None
                if failure=='busy':
                    held=os.open(self.history/'lock',os.O_RDONLY)
                    fcntl.flock(held,fcntl.LOCK_EX|fcntl.LOCK_NB)
                elif failure=='missing-registry':self.history.rename(self.history.with_name('unavailable-history'))
                elif failure=='wrong-target':opened['target']=str(self.root/'untrusted-target')
                elif failure=='wrong-history':
                    path=self.history/'run.json';path.chmod(0o600)
                    value=json.loads(path.read_bytes());value['run_id']='another-unrelated-run'
                    path.write_bytes(T.canonical(value));path.chmod(0o400)
                elif failure=='EARLY-refusal':q.catalogue['readiness_sha256']='f'*64;q.save('catalogue')
                self.reset_transfer_events()
                try:
                    with self.assertRaises(T.ProtocolError):
                        T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(q),payload=payload)
                finally:
                    if held is not None:os.close(held)
                events=self.transfer_events()
                self.assertEqual([x for x in events if x['event'] in ('PAYLOAD_REQUEST','PAYLOAD_BYTES','SPOOL_OPEN','SPOOL_WRITE','LEAF')],[],events)
                self.assertFalse((self.history/(operation+'.start.json')).exists())
                self.assertFalse((self.history/(operation+'.result.json')).exists())
                if failure=='EARLY-refusal':self.assertEqual([x[1]['decision'] for x in self.answers],['REFUSE'])
                else:self.assertEqual(self.answers,[])

    def test_nonempty_uploads_follow_intent_and_keep_same_lock_through_leaf(self):
        cases=(('FINAL_V125_PREFLIGHT_PASSED','preflight-upload',True),
               ('V126_IMAGE_TRANSFERRED_AND_VERIFIED','image-upload',False))
        for number,(stage,action,protected) in enumerate(cases):
            with self.subTest(action=action):
                if number:self.tearDown();self.doCleanups();self.setUp()
                q,stages,index=self.native_fixture(stage,action)
                opened,payload=self.q_request(q,stages,index)
                operation=T.digest(q.identity);inode=(self.history/'lock').stat().st_ino
                self.reset_transfer_events();output=[]
                result=T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(q),payload=payload,output_sink=output.append)
                self.assertEqual(result['status'],0)
                expected=hashlib.sha256(payload.read_bytes()).hexdigest().encode()
                self.assertIn(b'CAPTURED_PAYLOAD\t'+expected,b''.join(output))
                events=self.transfer_events();names=[x['event'] for x in events]
                self.assertEqual(names.count('PAYLOAD_REQUEST'),1);self.assertEqual(names.count('SPOOL_OPEN'),1)
                self.assertGreater(names.count('SPOOL_WRITE'),1)
                self.assertEqual(sum(x['size'] for x in events if x['event']=='PAYLOAD_BYTES'),payload.stat().st_size)
                self.assertEqual(sum(x['size'] for x in events if x['event']=='SPOOL_WRITE'),payload.stat().st_size)
                for event in events:
                    self.assertTrue(event['lock_held'],event);self.assertEqual(event['inode'],inode,event)
                    if event['event']!='CHALLENGE' or event.get('phase')=='LATE':
                        self.assertIn(operation,event['starts'],event);self.assertNotIn(operation,event['results'],event)
                self.assertEqual(names[-1],'LEAF',events)
                if protected:
                    self.assertEqual([(x['event'],x.get('phase')) for x in events if x['event'] in ('CHALLENGE','PAYLOAD_REQUEST','LEAF')],
                        [('CHALLENGE','EARLY'),('PAYLOAD_REQUEST',None),('CHALLENGE','LATE'),('LEAF',None)])
                    self.assertEqual([x[1]['decision'] for x in self.answers],['PASS','PASS'])
                else:
                    self.assertNotIn('CHALLENGE',names);self.assertEqual(self.answers,[])
                saved=json.loads((self.history/(operation+'.result.json')).read_bytes())
                self.assertEqual((saved['outcome'],saved['exit']),('SUCCEEDED',0))

    def test_upload_disconnect_keeps_unknown_and_cannot_request_again(self):
        q,stages,index=self.native_fixture('FINAL_V125_PREFLIGHT_PASSED','preflight-upload')
        opened,payload=self.q_request(q,stages,index)
        operation=T.digest(q.identity);self.reset_transfer_events()
        original=T.FramedChannel.send_bytes
        payload_bytes=payload.read_bytes()
        payload_blocks={payload_bytes[offset:offset+T.MAX_FRAME] for offset in range(0,len(payload_bytes),T.MAX_FRAME)}
        sent=[False]
        def disconnect(channel,block,**kwargs):
            # FramedChannel.send also uses send_bytes for OPEN/RESULT control.
            # Interrupt only the second real binary chunk after PAYLOAD_REQUEST.
            if block in payload_blocks:
                if sent[0]:raise OSError('owned synthetic upload disconnect')
                sent[0]=True
            return original(channel,block,**kwargs)
        with mock.patch.object(T.FramedChannel,'send_bytes',disconnect):
            with self.assertRaises((OSError,T.ProtocolError)):
                T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(q),payload=payload)
        self.assertTrue(sent[0])
        self.assertTrue((self.history/(operation+'.start.json')).exists())
        self.assertFalse((self.history/(operation+'.result.json')).exists())
        self.assertFalse(any(x['event']=='LEAF' for x in self.transfer_events()))
        # Wait only for the owned SSH invocation to release its lock, never repair
        # or remove its durable intent. The next invocation must see UNKNOWN.
        deadline=time.monotonic()+5
        while True:
            fd=os.open(self.history/'lock',os.O_RDONLY)
            try:
                fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB);break
            except BlockingIOError:
                if time.monotonic()>=deadline:self.fail('owned interrupted upload did not release lock')
                time.sleep(.02)
            finally:os.close(fd)
        before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
        self.reset_transfer_events();self.answers=[]
        with self.assertRaises(T.ProtocolError):
            T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(q),payload=payload)
        self.assertEqual(self.transfer_events(),[])
        self.assertEqual(before,{x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()})

    def test_actual_q_all_protected_actions_replay_full_native_chain(self):
        actions=[(stage,action) for stage,items in self.authority_cases.a.adapter.STAGE_ACTIONS.items()
                 if stage!='BASELINE_VERIFIED' for action in items]
        # Each own target has independently accepted synthetic previous history.
        # No shortcut bypasses the actual stage/action history admission.
        for number,(stage,action) in enumerate(actions):
            with self.subTest(stage=stage,action=action):
                if number:self.tearDown();self.doCleanups();self.setUp()
                q,stages,index=self.native_fixture(stage,action)
                self.execute_q(q,stages,index)

    def outer_cli_fixture(self, *, lose_completion=False):
        """Actual Bash CLI with only external Git/clock observations isolated."""
        bin_dir=self.root/'outer-bin';bin_dir.mkdir(exist_ok=True)
        driver=bin_dir/'python3'
        driver.write_text('#!'+sys.executable+'\n'+r'''
import importlib.util,os,sys
from pathlib import Path
if len(sys.argv)<2 or not sys.argv[1].endswith('/v126-policy-b-client.py'):
    os.execv(sys.executable,[sys.executable,*sys.argv[1:]])
arguments=sys.argv[1:]
sys.argv=['owned-fixture','--require-linux-ssh']
spec=importlib.util.spec_from_file_location('owned_outer_fixture',FIXTURE_SOURCE)
fixture=importlib.util.module_from_spec(spec);sys.modules[spec.name]=fixture;spec.loader.exec_module(fixture)
client=fixture.load('owned_outer_client',Path(SOURCE_ROOT)/'scripts/v126-policy-b-client.py')
authority=client.module('v126_policy_b_authority','v126-policy-b-authority.py')
original=authority.VVerifier
class ObservedVerifier(original):
    def verify(self,challenge):
        with fixture.git_observations(),fixture.mock.patch.object(authority.time,'time',return_value=WALL),fixture.mock.patch.object(authority.time,'monotonic',return_value=MONO):
            return super().verify(challenge)
    def authorize_action(self,*args,**kwargs):
        with fixture.git_observations(),fixture.mock.patch.object(authority.time,'time',return_value=WALL),fixture.mock.patch.object(authority.time,'monotonic',return_value=MONO):
            return super().authorize_action(*args,**kwargs)
authority.VVerifier=ObservedVerifier
if LOSE_COMPLETION:
    def unavailable(*args,**kwargs):raise OSError('owned injected completion storage failure')
    client.save_completion=unavailable
sys.argv=arguments
with fixture.git_observations():raise SystemExit(client.main())
'''.replace('FIXTURE_SOURCE',repr(str(Path(__file__).resolve())))
        .replace('SOURCE_ROOT',repr(str(self.source_root))).replace('WALL',repr(self.fx.wall))
        .replace('MONO',repr(self.fx.mono)).replace('LOSE_COMPLETION',repr(lose_completion)))
        driver.chmod(0o700)
        self.locator=self.root/'transport.json'
        self.locator.write_bytes(T.canonical(dict(remote_alias='owned-fixture-only',ssh=self.ssh.options())));self.locator.chmod(0o600)
        self.outer_env=dict(os.environ,PATH=str(bin_dir)+':/usr/sbin:/usr/bin:/sbin:/bin',PYTHONDONTWRITEBYTECODE='1')
        return self.source_root/'scripts/v126-cutover.sh'

    def outer_prepare(self):
        script=self.outer_cli_fixture()
        self.proposal=self.root/'reviewed-init-proposal.json'
        doc=json.loads(self.manifest)
        args=['bash',str(script),'prepare-init','--proposal-file',str(self.proposal),'--state-dir',str(self.state)]
        for key in ('run_id','release_sha','release_tree','release_parents','main_actions_run_id','release_worktree',
                    'remote','staging_path','database_url_file','maintenance_identities_file','v126_image_tag','v126_image_id','v125_image_tag'):
            value=doc[key]
            args.extend(['--'+key.replace('_','-'),','.join(value) if isinstance(value,list) else str(value)])
        before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
        result=subprocess.run(args,env=self.outer_env,capture_output=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr.decode(errors='replace'))
        summary=json.loads(result.stdout)
        self.proposal_summary=summary
        self.assertFalse(self.state.exists(),'prepare must not create canonical state')
        self.assertEqual(before,{x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()})
        self.assertEqual(self.transfer_events(),[])
        self.assertEqual(summary['proposal_sha256'],hashlib.sha256(self.proposal.read_bytes()).hexdigest())
        self.assertEqual(self.proposal.stat().st_mode&0o777,0o400)
        return summary

    def approve_outer_proposal(self):
        self.manifest=T.canonical(json.loads(self.proposal.read_bytes())['manifest'])
        self.request=self.client.init_request(self.state,self.manifest)
        self.fx.identity=self.request['identity']
        self.assertEqual(self.proposal_summary['identity'],self.fx.identity)
        self.fx.catalogue['actions'][0]['identity']=self.fx.identity
        self.fx.save('catalogue')

    def invoke_outer_init(self, *, expect_success, expect_prompts=None, state_must_not_exist=True):
        arguments=['bash',str(self.source_root/'scripts/v126-cutover.sh'),'init','--state-dir',str(self.state),
            '--proposal-file',str(self.proposal),'--proposal-sha256',self.proposal_summary['proposal_sha256'],
            '--policy-b-anchor',str(self.fx.anchorpath),'--policy-b-transport',str(self.locator)]
        pid,terminal=pty.fork()
        if pid==0:os.execvpe(arguments[0],arguments,self.outer_env)
        deadline=time.monotonic()+35;captured=b'';answered=set();status=None
        try:
            while time.monotonic()<deadline:
                if select.select([terminal],[],[],.05)[0]:
                    try:captured+=os.read(terminal,65536)
                    except OSError:pass
                    if b'U:' in captured and 'U' not in answered:
                        if state_must_not_exist:self.assertFalse(self.state.exists(),'canonical metadata created before admission')
                        os.write(terminal,(self.fx.enrollment.sha256+'\n').encode());answered.add('U')
                    for match in re.finditer(rb'type nonce ([0-9a-f]{64}): ',captured):
                        nonce=match.group(1)
                        if nonce not in answered:
                            if state_must_not_exist:self.assertFalse(self.state.exists(),'canonical metadata created before LATE/WRITE_INIT')
                            os.write(terminal,nonce+b'\n');answered.add(nonce)
                waited,raw=os.waitpid(pid,os.WNOHANG)
                if waited:status=os.waitstatus_to_exitcode(raw);break
            self.assertIsNotNone(status,'outer INIT exceeded owned test deadline')
            if expect_success:self.assertEqual(status,0,captured.decode(errors='replace')[-2000:])
            else:self.assertNotEqual(status,0,captured.decode(errors='replace')[-2000:])
            if expect_prompts is not None:self.assertEqual(len(answered),expect_prompts,captured.decode(errors='replace')[-2000:])
            return captured
        finally:
            if status is None:
                os.kill(pid,signal.SIGKILL);os.waitpid(pid,0)
            os.close(terminal)

    def test_outer_prepare_pause_independent_approval_init_preserves_exact_proposal(self):
        self.outer_prepare();prepared=self.proposal.read_bytes()
        # An actual elapsed second made old create_state produce a new intent.
        time.sleep(1.05)
        self.approve_outer_proposal()
        self.invoke_outer_init(expect_success=True,expect_prompts=4)
        self.assertEqual(self.proposal.read_bytes(),prepared)
        self.assertEqual((self.state/'run.json').read_bytes(),self.manifest)
        completion=self.client.require_completion(self.state)
        self.assertEqual(completion['request'],self.request)
        self.assertEqual(len(list(self.history.glob('*.start.json'))),1)
        self.assertEqual(len(list(self.history.glob('*.result.json'))),1)
        before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
        self.invoke_outer_init(expect_success=False,expect_prompts=0,state_must_not_exist=False)
        self.assertEqual(before,{x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()})

    def test_outer_proposal_drift_existing_state_and_unapproved_identity_refuse(self):
        for number,failure in enumerate(('drift','existing-state','unapproved')):
            with self.subTest(failure=failure):
                if number:self.tearDown();self.doCleanups();self.setUp()
                self.outer_prepare()
                if failure!='unapproved':self.approve_outer_proposal()
                if failure=='drift':
                    doc=json.loads(self.proposal.read_bytes());doc['manifest']['created_at']='2026-09-01T00:00:01Z'
                    self.proposal.chmod(0o600);self.proposal.write_bytes(T.canonical(doc));self.proposal.chmod(0o400)
                elif failure=='existing-state':self.state.mkdir(mode=0o700)
                before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
                self.invoke_outer_init(expect_success=False,expect_prompts=1 if failure=='unapproved' else 0,
                                       state_must_not_exist=failure!='existing-state')
                self.assertEqual(before,{x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()})
                self.assertEqual(self.transfer_events(),[])
                self.assertFalse((self.state/'run.json').exists())
                self.assertFalse((self.state/'init-completion.json').exists())

    def test_outer_lost_completion_cannot_reuse_proposal_or_repeat_init(self):
        self.outer_prepare();self.approve_outer_proposal();self.outer_cli_fixture(lose_completion=True)
        self.invoke_outer_init(expect_success=False,expect_prompts=4)
        self.assertEqual((self.state/'run.json').read_bytes(),self.manifest)
        self.assertFalse((self.state/'init-completion.json').exists())
        self.assertEqual(len(list(self.history.glob('*.result.json'))),1)
        before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
        self.outer_cli_fixture()
        self.invoke_outer_init(expect_success=False,expect_prompts=0,state_must_not_exist=False)
        self.assertEqual(before,{x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()})
        self.assertFalse((self.state/'init-completion.json').exists())

    def test_internal_client_init_retains_independent_foreground_tty(self):
        # The client reads its manifest from a pipe, and U/C answers exclusively
        # from the independently controlled foreground PTY. No Console stub.
        locator=self.root/'transport.json'
        locator.write_bytes(T.canonical(dict(remote_alias='owned-fixture-only',ssh=self.ssh.options())));locator.chmod(0o600)
        read_fd,write_fd=os.pipe()
        pid,terminal=pty.fork()
        if pid==0:
            try:
                os.close(write_fd);os.dup2(read_fd,0);os.close(read_fd)
                authority=self.authority_cases.a
                original=authority.VVerifier
                fx=self.fx
                class ObservedVerifier(original):
                    def verify(self,challenge):
                        with git_observations(),mock.patch.object(authority.time,'time',return_value=fx.wall),mock.patch.object(authority.time,'monotonic',return_value=fx.mono):
                            return super().verify(challenge)
                    def authorize_action(self,*args,**kwargs):
                        with git_observations(),mock.patch.object(authority.time,'time',return_value=fx.wall),mock.patch.object(authority.time,'monotonic',return_value=fx.mono):
                            return super().authorize_action(*args,**kwargs)
                authority.VVerifier=ObservedVerifier
                sys.modules['v126_policy_b_authority']=authority
                sys.argv=['v126-policy-b-client.py','init','--state-dir',str(self.state),
                    '--policy-b-anchor',str(fx.anchorpath),'--policy-b-transport',str(locator)]
                with git_observations():status=self.client.main()
                os._exit(status)
            except BaseException:os._exit(98)
        os.close(read_fd);os.write(write_fd,self.manifest);os.close(write_fd)
        deadline=time.monotonic()+30;captured=b'';answered=set();status=None
        try:
            while time.monotonic()<deadline:
                if select.select([terminal],[],[],.1)[0]:
                    try:chunk=os.read(terminal,65536)
                    except OSError:chunk=b''
                    captured+=chunk
                    if b'U:' in captured and 'U' not in answered:
                        os.write(terminal,(self.fx.enrollment.sha256+'\n').encode());answered.add('U')
                    for match in re.finditer(rb'type nonce ([0-9a-f]{64}): ',captured):
                        nonce=match.group(1)
                        if nonce not in answered:os.write(terminal,nonce+b'\n');answered.add(nonce)
                waited,raw=os.waitpid(pid,os.WNOHANG)
                if waited:status=os.waitstatus_to_exitcode(raw);break
            self.assertIsNotNone(status,'owned foreground CLI exceeded bounded test deadline')
            self.assertEqual(status,0,captured.decode(errors='replace')[-1000:])
            self.assertEqual(len(answered),4,'U root plus DISPATCH/EARLY/LATE C confirmations')
            self.client.require_completion(self.state)
        finally:
            if status is None:
                os.kill(pid,signal.SIGKILL);os.waitpid(pid,0)
            os.close(terminal)

    def test_q_missing_native_checksum_refuses_without_dispatch(self):
        q,stages,index=self.native_fixture('V126_BACKEND_STARTED')
        (self.state/'receipts/06-ZERO_WRITER_GATE_PASSED.receipt.json.sha256').unlink()
        before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
        with self.assertRaises(T.ProtocolError):self.execute_q(q,stages,index)
        self.assertEqual(before,{x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()})
        self.assertEqual(len(self.native_calls),1)
        self.assertEqual(self.answers[-1][1]['decision'],'REFUSE')

    def test_q_revocation_between_rounds_refuses_preserving_prior_history(self):
        q,stages,index=self.native_fixture('ORDINARY_CADDY_RESTORED')
        calls=[]
        def revoke():
            calls.append(True)
            if len(calls)==2:
                q.catalogue['revoked'].append(q.anchor['verifier_identity']);q.save('catalogue')
        q.console.on_confirm=revoke
        before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
        with self.assertRaises(T.ProtocolError):self.execute_q(q,stages,index)
        for name,raw in before.items():self.assertEqual((self.history/name).read_bytes(),raw)
        self.assertEqual([x[1]['decision'] for x in self.answers],['PASS','REFUSE'])
        self.assertFalse((self.history/(T.digest(q.identity)+'.result.json')).exists())

    def test_init_lost_ack_copies_only_durable_completion_without_reexecution(self):
        opened=self.client.make_open(self.fx.enrollment,self.manifest,self.fx.identity,self.request)
        writes=[]
        def writer(message):
            writes.append(True);return self.client.write_metadata(self.state,self.manifest,message['request'])
        def lose_ack(message):raise OSError('synthetic local completion write loss')
        with self.assertRaises(OSError):
            T.invoke_operation(self.ssh.options(),opened,self.ssh.source,self.verifier(),init_writer=writer,completion_writer=lose_ack)
        self.assertEqual(writes,[True]);self.assertFalse((self.state/'init-completion.json').exists())
        before={x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()}
        self.fx.catalogue['actions'].append(dict(self.fx.catalogue['actions'][0],scope='COPY_ONLY'));self.fx.save('catalogue')
        authority=self.authority_cases.a
        verifier=authority.VVerifier(self.fx.enrollment,None,self.fx.console)
        with git_observations(),mock.patch.object(authority.time,'time',return_value=self.fx.wall),mock.patch.object(authority.time,'monotonic',return_value=self.fx.mono):
            verifier.authorize_action(self.fx.identity,str(self.fx.target),scope='COPY_ONLY')
        request=self.client.make_open(self.fx.enrollment,self.manifest,self.fx.identity,self.request,mode='INIT_READBACK')
        result=T.invoke_operation(self.ssh.options(),request,self.ssh.source,verifier)
        self.assertEqual(result['status'],0)
        self.client.save_completion(self.state,result['result']);self.client.require_completion(self.state)
        self.assertEqual(before,{x.name:x.read_bytes() for x in self.history.iterdir() if x.is_file()})
        self.assertEqual(writes,[True])

    def test_actual_init_then_baseline_with_real_sshd_lock_and_consumer(self):
        self.initialize();self.baseline()
        self.assertEqual(len(self.answers),4)
        self.assertEqual([value[0]['phase'] for value in self.answers],['EARLY','LATE']*2)
        results=list(self.history.glob('*.result.json'))
        self.assertEqual(len(results),2)
        self.assertTrue(all(json.loads(path.read_bytes())['exit']==0 for path in results))

    def test_missing_independent_evidence_cannot_initialize_or_write_local_state(self):
        self.fx.catalogue['readiness_sha256']='f'*64;self.fx.save('catalogue')
        with self.assertRaises(T.ProtocolError):self.initialize()
        self.assertFalse(self.state.exists());self.assertEqual(self.writes,[])
        self.assertEqual(list(self.history.glob('*.start.json')),[])

    def test_lost_late_result_does_not_start_init_writer(self):
        verifier=self.verifier();actual=verifier.verify
        def stale(challenge):
            result=actual(challenge)
            if challenge['phase']=='LATE':result['expiry']={key:.000001 for key in T.EXPIRY_KEYS}
            return result
        verifier.verify=stale
        opened=self.client.make_open(self.fx.enrollment,self.manifest,self.fx.identity,self.request)
        with self.assertRaises(T.ProtocolError):
            T.invoke_operation(self.ssh.options(),opened,self.ssh.source,verifier,
                init_writer=lambda message:self.fail('expired INIT reached local writer'))
        self.assertFalse(self.state.exists())
        self.assertFalse(list(self.history.glob('*.result.json')))


def isolation_report():
    interfaces={path.name:int((path/'flags').read_text().strip(),16) for path in Path('/sys/class/net').iterdir() if (path/'flags').is_file()}
    routes=Path('/proc/net/route').read_text().splitlines()[1:]
    ipv6=Path('/proc/net/if_inet6').read_text().splitlines()
    if ('lo' not in interfaces or any(flags & 1 for name,flags in interfaces.items() if name!='lo')
            or routes or any(line.split()[-1]!='lo' for line in ipv6)
            or os.environ.get('SSH_AUTH_SOCK') or Path('/var/run/docker.sock').exists() or Path('/root/.ssh').exists()):
        raise SystemExit('owned network/auth isolation missing')
    mounts={line.split()[4]:line.split()[5] for line in Path('/proc/self/mountinfo').read_text().splitlines()}
    if 'ro' not in mounts.get('/','').split(',') or any(path not in mounts for path in ('/tmp','/run')):
        raise SystemExit('owned readonly-root/tmpfs runtime required')
    caps=int(next(line.split()[1] for line in Path('/proc/self/status').read_text().splitlines() if line.startswith('CapEff:')),16)
    if caps & ((1<<12)|(1<<13)|(1<<21)|(1<<19)):
        raise SystemExit('unexpected network/admin/ptrace capability')
    print('OWNED_RUNTIME '+json.dumps(dict(network_interface_flags=interfaces,nonloopback_routes=0,root_readonly=True,
        capability_mask=hex(caps),python=sys.version.split()[0],kernel=os.uname().release,
        openssh=subprocess.run(['/usr/bin/ssh','-V'],capture_output=True,check=True).stderr.decode().strip(),
        source_hashes={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(ROOT.glob('v126-*')) if path.is_file()}),sort_keys=True),flush=True)


if __name__=='__main__':
    isolation_report()
    unittest.main()
