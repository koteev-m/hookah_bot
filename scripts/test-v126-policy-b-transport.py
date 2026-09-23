#!/usr/bin/env python3
"""Transport unit cases and real isolated OpenSSH authentication cases.

--require-linux-ssh runs only in the separately provisioned network-denied owned
Linux runtime. SSH identity tests use an inert enrolled source: they establish
transport provenance, not R0/Q qualification or end-to-end cutover readiness.
"""
import base64
from contextlib import contextmanager, nullcontext
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
REQUIRE = '--require-linux-ssh' in sys.argv
if REQUIRE:
    sys.argv.remove('--require-linux-ssh')
    if sys.platform != 'linux' or os.geteuid() != 0 or not Path('/usr/sbin/sshd').is_file():
        raise SystemExit('owned Linux root/OpenSSH runtime required; no skipped PASS')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


T = load('policy_b_transport_tests', ROOT / 'v126-policy-b-transport.py')
L = load('policy_b_launcher_tests', ROOT / 'v126-policy-b-launcher.py')


def opening(source=b'synthetic-source', **overrides):
    result = dict(version=1, type='OPEN', mode='EXECUTE', session_id='1'*64, anchor_sha256='2'*64,
        anchor_generation=1, principal_fingerprint='SHA256:'+'A'*43, host_fingerprint='SHA256:'+'B'*43,
        identity=dict(run_id='synthetic-transport-run', release_sha='a'*40,
            script_sha256=hashlib.sha256(source).hexdigest(), intent_sha256='3'*64,
            kind='STAGE', name='BASELINE_VERIFIED', action='baseline'),
        target='/synthetic/target', request_sha256='4'*64, manifest_sha256=None, manifest_size=0,
        source_tree='b'*40, tooling_sha256='5'*64, runtime='3.12.3', worker_args=[], environment={},
        init_request=None, init_completion=None, payload=None)
    result.update(overrides)
    return result


def passing(challenge):
    return dict(T.result_echo(challenge), decision='PASS', barrier='R0', qualification_sha256='6'*64,
        catalogue_head_sha256='7'*64, catalogue_sequence=2, revocation_generation=1, pins_sha256='8'*64,
        native_stage7_sha256=None, native_manifest_sha256=None, expiry={key: 1000 for key in T.EXPIRY_KEYS})


class ScriptedChannel:
    def __init__(self):
        self.sent = []
        self.change = lambda value: value

    def require_idle_peer(self):
        pass

    def send(self, value, *, deadline):
        self.sent.append(value)

    def recv(self, *, deadline):
        return self.change(passing(self.sent[-1]))


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.channel = ScriptedChannel()
        self.open = opening()
        principal = {key: self.open[key] for key in ('principal_fingerprint', 'host_fingerprint', 'anchor_sha256', 'anchor_generation')}
        principal['revocation_generation'] = 1
        self.source_calls = []
        self.session = T.Session(self.channel, self.open, b'synthetic-source', principal, lambda: self.source_calls.append(True))
        self.history = '9'*64
        self.locks = []
        self.gate = T.RemoteGate(self.session, lambda: self.history, lambda target, fd: self.locks.append((target, fd)))

    def check(self):
        return self.gate.check(self.open['identity'], self.open['target'], 7, 300)

    def dispatch(self):
        self.gate.before_dispatch(self.open['identity'], self.open['target'], 7, 300)

    def test_two_distinct_rounds_and_single_consumption(self):
        self.check()
        self.history = 'a'*64
        self.check()
        self.dispatch()
        self.assertEqual([frame['phase'] for frame in self.channel.sent], ['EARLY', 'LATE'])
        self.assertNotEqual(self.channel.sent[0]['nonce'], self.channel.sent[1]['nonce'])
        self.assertGreaterEqual(len(self.locks), 3)
        with self.assertRaises(T.ProtocolError):
            self.dispatch()
        with self.assertRaises(T.ProtocolError):
            self.check()

    def test_unmatched_duplicate_cross_session_and_extra_result_refuse(self):
        for field, wrong in [('version', 3), ('nonce', 'f'*64), ('session_id', 'f'*64), ('sequence', 2),
                             ('challenge_sha256', 'f'*64), ('phase', 'LATE')]:
            with self.subTest(field=field):
                self.setUp()
                self.channel.change = lambda value: dict(value, **{field: wrong})
                with self.assertRaises(T.ProtocolError):
                    self.check()
        self.setUp()
        self.channel.change = lambda value: dict(value, operational=True)
        with self.assertRaises(T.ProtocolError):
            self.check()

    def test_expiry_vector_is_closed_and_finite(self):
        for bad in ({}, {'checkpoint': 10}, {key: float('nan') for key in T.EXPIRY_KEYS},
                    {key: 0 for key in T.EXPIRY_KEYS}, {key: True for key in T.EXPIRY_KEYS}):
            with self.subTest(bad=str(bad)[:30]):
                self.setUp()
                self.channel.change = lambda value: dict(value, expiry=bad)
                with self.assertRaises(T.ProtocolError):
                    self.check()

    def test_revocation_floor_is_independent_from_anchor_version_and_monotonic(self):
        self.open['anchor_generation']=2
        self.session.principal['anchor_generation']=2
        self.session.__post_init__()
        self.check(); self.check(); self.dispatch()
        self.setUp()
        self.gate.revocation_floor=2
        with self.assertRaises(T.ProtocolError): self.check()
        self.setUp()
        self.channel.change=lambda value:dict(value,revocation_generation=2)
        self.check()
        self.channel.change=lambda value:value
        with self.assertRaises(T.ProtocolError): self.check()

    def test_dispatch_rtt_and_five_seconds_are_independent(self):
        self.check(); self.check()
        self.gate.pending['sent'] = time.monotonic() - 1001
        with self.assertRaises(T.ProtocolError):
            self.dispatch()
        self.setUp(); self.check(); self.check()
        self.gate.pending['received'] = time.monotonic() - 6
        with self.assertRaises(T.ProtocolError):
            self.dispatch()

    def test_changed_history_source_or_lock_refuses_dispatch(self):
        for changed in ('history', 'source', 'lock'):
            with self.subTest(changed=changed):
                self.setUp(); self.check(); self.check()
                if changed == 'history':
                    self.history = 'f'*64
                elif changed == 'source':
                    self.session.source = b'changed'
                else:
                    self.gate.require_lock = lambda target, fd: T.refuse('LOST_LOCK')
                with self.assertRaises(T.ProtocolError):
                    self.dispatch()

    def test_known_eof_or_unsolicited_data_after_late_refuses_dispatch(self):
        for data in (b'', b'x'):
            with self.subTest(data=data):
                self.setUp(); self.check(); self.check()
                read, write = os.pipe()
                try:
                    if data: os.write(write, data)
                    os.close(write); write = None
                    self.session.channel = T.FramedChannel(read, read)
                    with self.assertRaises(T.ProtocolError): self.dispatch()
                    self.assertFalse(self.gate.consumed)
                finally:
                    os.close(read)
                    if write is not None: os.close(write)

    def test_open_rejects_client_only_authority_and_unknown_environment(self):
        for change in ({'version': 3}, {'version': True}, {'operational': True}, {'environment': {'PYTHONPATH': '/untrusted'}},
                       {'mode': 'RESET_HISTORY'}, {'init_request': {}}):
            with self.subTest(change=change):
                with self.assertRaises(T.ProtocolError):
                    T.validate_open(dict(self.open, **change))

    def test_framing_rejects_duplicate_nan_oversize_partial_eof(self):
        documents = [b'{"x":1,"x":2}\n', b'{"x":NaN}\n']
        samples = [struct.pack('!I', len(raw)) + raw for raw in documents]
        samples += [struct.pack('!I', T.MAX_FRAME+1), b'\x00\x00']
        for raw in samples:
            read, write = os.pipe()
            try:
                os.write(write, raw); os.close(write); write = None
                channel = T.FramedChannel(read, read)
                with self.assertRaises(T.ProtocolError):
                    channel.recv(deadline=time.monotonic()+.2)
            finally:
                os.close(read)
                if write is not None: os.close(write)

    def test_real_pipe_roundtrip_and_backpressure_deadline(self):
        read, write = os.pipe()
        errors = []
        try:
            sender, receiver = T.FramedChannel(write, write), T.FramedChannel(read, read)
            value = {'text': 'x'*60000}
            def send():
                try: sender.send(value, deadline=time.monotonic()+1)
                except BaseException as exc: errors.append(exc)
            thread = threading.Thread(target=send)
            thread.start()
            self.assertEqual(receiver.recv(deadline=time.monotonic()+1), value)
            thread.join(timeout=1)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])
            for _ in range(64):
                try: os.write(write, b'x'*65536)
                except BlockingIOError: break
            else: self.fail('owned pipe did not reach bounded backpressure fixture')
            with self.assertRaises(T.ProtocolError):
                sender.send(value, deadline=time.monotonic()+.03)
        finally:
            os.close(read); os.close(write)

    def test_result_send_backpressure_does_not_renew_init_authority(self):
        source=b'synthetic-source'
        opened=opening(source,manifest_sha256='c'*64,manifest_size=2,init_request={'synthetic':'init'})
        opened['identity']=dict(opened['identity'],kind='INIT',name='RUN_INITIALIZED',action='initialize-run')
        opened['request_sha256']=T.digest(opened['init_request'])
        principal={key:opened[key] for key in ('principal_fingerprint','host_fingerprint','anchor_sha256','anchor_generation')}
        principal['revocation_generation']=1
        server=ScriptedChannel()
        gate=T.RemoteGate(T.Session(server,opened,source,principal,lambda:None),lambda:'9'*64,lambda *args:None)
        gate.check(opened['identity'],opened['target'],7,300);gate.check(opened['identity'],opened['target'],7,300)
        challenges=server.sent
        late=challenges[-1]
        write=dict(version=1,type='WRITE_INIT',session_id=late['session_id'],nonce=late['nonce'],sequence=2,
            operation_id=late['operation_id'],challenge_sha256=T.digest(late),request_sha256=opened['request_sha256'],
            manifest_sha256=opened['manifest_sha256'],manifest_size=opened['manifest_size'],request=opened['init_request'])
        clock=[100.0]
        class ClientChannel:
            def __init__(self,*args):self.frames=iter([*challenges,write])
            def send(self,value,**kwargs):
                if value.get('type')=='RESULT' and value['sequence']==2:clock[0]+=6
            def send_bytes(self,*args,**kwargs):pass
            def recv(self,**kwargs):return next(self.frames)
        with tempfile.TemporaryFile() as stdin,tempfile.TemporaryFile() as stdout:
            process=mock.Mock(stdin=stdin,stdout=stdout)
            process.poll.return_value=0
            verifier=mock.Mock();verifier.verify.side_effect=passing
            with mock.patch.object(T,'ssh_command',return_value=nullcontext(['synthetic-no-process'])), \
                 mock.patch.object(T.subprocess,'Popen',return_value=process), \
                 mock.patch.object(T,'FramedChannel',ClientChannel), \
                 mock.patch.object(T.time,'monotonic',side_effect=lambda:clock[0]):
                with self.assertRaisesRegex(T.ProtocolError,'WRITER_EXPIRED'):
                    T.invoke_operation({'host_fingerprint':opened['host_fingerprint']},opened,source,verifier,
                        init_writer=lambda _:self.fail('blocked RESULT send renewed INIT authority'))

    def test_terminal_frame_rejects_trailing_bytes_and_action_budget_is_preserved(self):
        for action,budget in [('baseline',960),('backup-rehearsal',2460),('final-v125-preflight',1260)]:
            for trailing in (b'',b'unsolicited-extra-result'):
                with self.subTest(action=action,trailing=bool(trailing)):
                    opened=opening();opened['identity']=dict(opened['identity'],action=action)
                    class ClientChannel:
                        def __init__(inner,read,write):inner.read_fd=read
                        def send(inner,*args,**kwargs):pass
                        def send_bytes(inner,*args,**kwargs):pass
                        def recv(inner,*,deadline):
                            self.assertEqual(deadline,100+budget)
                            return dict(version=1,type='OPERATION_RESULT',session_id=opened['session_id'],
                                operation_id=T.digest(opened['identity']),status=75,result=None)
                    with tempfile.TemporaryFile() as stdin,tempfile.TemporaryFile() as stdout:
                        stdout.write(trailing);stdout.seek(0)
                        process=mock.Mock(stdin=stdin,stdout=stdout);process.poll.return_value=75;process.wait.return_value=75
                        with mock.patch.object(T,'ssh_command',return_value=nullcontext(['synthetic-no-process'])), \
                             mock.patch.object(T.subprocess,'Popen',return_value=process), \
                             mock.patch.object(T,'FramedChannel',ClientChannel), \
                             mock.patch.object(T.time,'monotonic',return_value=100):
                            if trailing:
                                with self.assertRaisesRegex(T.ProtocolError,'TRAILING_FRAME'):
                                    T.invoke_operation({'host_fingerprint':opened['host_fingerprint']},opened,b'synthetic-source',None)
                            else:self.assertEqual(T.invoke_operation({'host_fingerprint':opened['host_fingerprint']},opened,b'synthetic-source',None)['status'],75)

    def test_mutable_session_binding_regression_detects_removed_guard(self):
        def reject(module):
            opened=opening()
            principal={key:opened[key] for key in ('principal_fingerprint','host_fingerprint','anchor_sha256','anchor_generation')}
            principal['revocation_generation']=1
            session=module.Session(ScriptedChannel(),opened,b'synthetic-source',principal,lambda:None)
            opened['target']='/synthetic/substituted-target'
            with self.assertRaises(module.ProtocolError):session.verify()
        reject(T)
        source=(ROOT/'v126-policy-b-transport.py').read_text()
        guard="        if digest(self.request) != self._request_binding or digest(self.principal) != self._principal_binding:\n            refuse('POLICY_B_SESSION_BINDINGS_CHANGED')\n"
        self.assertEqual(source.count(guard),1)
        with tempfile.TemporaryDirectory(prefix='transport-negative-control-') as directory:
            path=Path(directory)/'synthetic-mutant.py'
            path.write_text(source.replace(guard,'',1))
            mutant=load('transport_negative_session',path)
            with self.assertRaises(AssertionError):reject(mutant)

    def test_expiry_regression_detects_send_timestamp_guard_removed(self):
        source=(ROOT/'v126-policy-b-transport.py').read_text()
        needle='latest = (message, result, verified_at)'
        self.assertEqual(source.count(needle),1)
        with tempfile.TemporaryDirectory(prefix='transport-negative-control-') as directory:
            path=Path(directory)/'synthetic-mutant.py'
            path.write_text(source.replace(needle,'latest = (message, result, time.monotonic())',1))
            mutant=load('transport_negative_expiry',path)
            with mock.patch.dict(globals(),{'T':mutant}):
                with self.assertRaises(AssertionError):self.test_result_send_backpressure_does_not_renew_init_authority()

    def test_private_output_uses_single_total_deadline_and_never_control_parsing(self):
        opened=opening();raw=b'{"type":"RESULT","decision":"PASS"}\n'+b'x'*70000
        clock=[100.0];calls=[];output=[]
        header=dict(version=1,type='OPERATION_OUTPUT',session_id=opened['session_id'],operation_id=T.digest(opened['identity']),
                    size=len(raw),sha256=hashlib.sha256(raw).hexdigest())
        terminal=dict(version=1,type='OPERATION_RESULT',session_id=opened['session_id'],operation_id=T.digest(opened['identity']),status=0,result=None)
        class ClientChannel:
            def __init__(self,read,write):self.read_fd=read;self.frames=iter([header,terminal]);self.blocks=iter([raw[:65536],raw[65536:]])
            def send(self,*args,**kwargs):pass
            def send_bytes(self,*args,**kwargs):pass
            def recv(self,**kwargs):return next(self.frames)
            def recv_bytes(self,*,deadline):
                calls.append(deadline);clock[0]+=10;return next(self.blocks)
        with tempfile.TemporaryFile() as stdin,tempfile.TemporaryFile() as stdout:
            process=mock.Mock(stdin=stdin,stdout=stdout);process.poll.return_value=0;process.wait.return_value=0
            with mock.patch.object(T,'ssh_command',return_value=nullcontext(['synthetic-no-process'])), \
                 mock.patch.object(T.subprocess,'Popen',return_value=process), \
                 mock.patch.object(T,'FramedChannel',ClientChannel), \
                 mock.patch.object(T.time,'monotonic',side_effect=lambda:clock[0]):
                reply=T.invoke_operation({'host_fingerprint':opened['host_fingerprint']},opened,b'synthetic-source',None,output_sink=output.append)
        self.assertEqual(reply,terminal);self.assertEqual(b''.join(output),raw);self.assertEqual(calls,[130,130])

    def test_pinned_host_copy_excludes_all_additional_trust_and_survives_source_change(self):
        def public(material):
            kind=b'ssh-ed25519'
            return base64.b64encode(struct.pack('!I',len(kind))+kind+struct.pack('!I',32)+material*32).decode()
        key_a,key_b=public(b'a'),public(b'b')
        pin='SHA256:'+base64.b64encode(hashlib.sha256(base64.b64decode(key_a)).digest()).decode().rstrip('=')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);identity=root/'synthetic-key';identity.write_bytes(b'not used for authentication')
            known=root/'known_hosts'
            forms=[f'unit ssh-ed25519 {key_b}',f'unit ssh-ed25519 {key_b} trailing comment',
                   f'* ssh-ed25519 {key_b}',f'unit,other ssh-ed25519 {key_b}',f'@cert-authority unit ssh-ed25519 {key_b}']
            options=dict(host='unit',port=22,user='synthetic',identity_file=str(identity),known_hosts_file=str(known),host_fingerprint=pin)
            for extra in forms:
                with self.subTest(form=extra.split()[0]):
                    known.write_text(f'unit ssh-ed25519 {key_a} approved comment\n{extra}\n')
                    with T.ssh_command(options) as args:
                        frozen=Path(next(item.split('=',1)[1] for item in args if item.startswith('UserKnownHostsFile=')))
                        self.assertNotEqual(frozen.resolve(),known.resolve())
                        self.assertEqual(frozen.read_text(),f'unit ssh-ed25519 {key_a}\n')
                        self.assertEqual(frozen.stat().st_mode&0o777,0o400)
                        self.assertEqual(frozen.parent.stat().st_mode&0o777,0o700)
                        known.write_text(extra+'\n')
                        self.assertEqual(frozen.read_text(),f'unit ssh-ed25519 {key_a}\n')
                        self.assertIn('StrictHostKeyChecking=yes',args)
                        self.assertIn('HostKeyAlgorithms=ssh-ed25519',args)
                        self.assertIn('KnownHostsCommand=none',args)
                    self.assertFalse(frozen.exists())

    def test_source_receiver_never_requests_or_spools_pending_upload(self):
        source=b'synthetic-source';payload=b'synthetic-payload'
        opened=opening(source,payload=dict(size=len(payload),sha256=hashlib.sha256(payload).hexdigest()))
        opened['identity']=dict(opened['identity'],action='image-upload')
        class InitialChannel:
            def __init__(self):
                self.frames=iter([opened,dict(version=1,type='SOURCE',session_id=opened['session_id'],size=len(source),sha256=hashlib.sha256(source).hexdigest())])
                self.sent=[];self.bytes=[]
            def recv(self,**kwargs):return next(self.frames)
            def recv_bytes(self,**kwargs):self.bytes.append(source);return source
            def send(self,value,**kwargs):self.sent.append(value)
        channel=InitialChannel();principal={key:opened[key] for key in ('principal_fingerprint','host_fingerprint','anchor_sha256','anchor_generation')};principal['revocation_generation']=1
        with mock.patch.object(T.tempfile,'TemporaryFile',side_effect=AssertionError('premature spool')):
            session=T.receive_session(channel,principal,lambda:None)
        self.assertEqual(channel.sent,[]);self.assertEqual(channel.bytes,[source]);self.assertIsNone(session.payload_file)

    def test_supervised_payload_checks_lock_history_before_request_and_each_write(self):
        payload=b'synthetic-payload';opened=opening(payload=dict(size=len(payload),sha256=hashlib.sha256(payload).hexdigest()))
        opened['identity']=dict(opened['identity'],action='image-upload')
        principal={key:opened[key] for key in ('principal_fingerprint','host_fingerprint','anchor_sha256','anchor_generation')};principal['revocation_generation']=1
        for rejected in ('lock','history','cancel',None):
            with self.subTest(rejected=rejected):
                channel=ScriptedChannel();channel.recv_bytes=lambda **kwargs:payload
                session=T.Session(channel,opened,b'synthetic-source',principal,lambda:None)
                checks=[]
                def lock(target,fd):
                    checks.append((target,fd))
                    if rejected=='lock':T.refuse('BUSY_LOCK')
                def current():
                    if rejected=='cancel':T.refuse('CANCELLED')
                call=lambda:session.receive_payload(opened['identity'],opened['target'],7,lock,
                    lambda:'changed' if rejected=='history' else 'intent-bound','intent-bound',check_current=current)
                if rejected:
                    with mock.patch.object(T.tempfile,'TemporaryFile',side_effect=AssertionError('premature spool')):
                        with self.assertRaises(T.ProtocolError):call()
                    self.assertEqual(channel.sent,[])
                else:
                    with call() as spool:self.assertEqual(spool.read(),payload)
                    self.assertEqual([x['type'] for x in channel.sent],['PAYLOAD_REQUEST']);self.assertGreaterEqual(len(checks),3)
                    with self.assertRaises(T.ProtocolError):call()

    def test_client_upload_round_matches_action_and_rejects_early_or_late_transfer(self):
        payload=b'synthetic-upload'
        for action,rounds,allowed in [('preflight-upload',0,False),('preflight-upload',1,True),
                                     ('preflight-upload',2,False),('image-upload',0,True),('image-upload',1,False)]:
            with self.subTest(action=action,rounds=rounds):
                opened=opening(payload=dict(size=len(payload),sha256=hashlib.sha256(payload).hexdigest()))
                opened['identity']=dict(opened['identity'],action=action)
                principal={key:opened[key] for key in ('principal_fingerprint','host_fingerprint','anchor_sha256','anchor_generation')};principal['revocation_generation']=1
                server=ScriptedChannel();gate=T.RemoteGate(T.Session(server,opened,b'synthetic-source',principal,lambda:None),lambda:'9'*64,lambda *args:None)
                for _ in range(rounds):gate.check(opened['identity'],opened['target'],7,300)
                requested=dict(version=1,type='PAYLOAD_REQUEST',session_id=opened['session_id'],operation_id=T.digest(opened['identity']),**opened['payload'])
                terminal=dict(version=1,type='OPERATION_RESULT',session_id=opened['session_id'],operation_id=T.digest(opened['identity']),status=0,result=None)
                transferred=[]
                class ClientChannel:
                    def __init__(self,read,write):self.read_fd=read;self.frames=iter([*server.sent,requested,terminal])
                    def send(self,*args,**kwargs):pass
                    def send_bytes(self,raw,**kwargs):transferred.append(raw)
                    def recv(self,**kwargs):return next(self.frames)
                with tempfile.TemporaryFile() as stdin,tempfile.TemporaryFile() as stdout,tempfile.TemporaryFile() as upload:
                    upload.write(payload);upload.seek(0)
                    process=mock.Mock(stdin=stdin,stdout=stdout);process.poll.return_value=0;process.wait.return_value=0
                    with mock.patch.object(T,'ssh_command',return_value=nullcontext(['synthetic-no-process'])), mock.patch.object(T.subprocess,'Popen',return_value=process), mock.patch.object(T,'FramedChannel',ClientChannel):
                        call=lambda:T.invoke_operation({'host_fingerprint':opened['host_fingerprint']},opened,b'synthetic-source',mock.Mock(verify=passing),payload=upload.fileno())
                        if allowed:self.assertEqual(call(),terminal)
                        else:
                            with self.assertRaises(T.ProtocolError):call()
                self.assertEqual(transferred,[b'synthetic-source',payload] if allowed else [b'synthetic-source'])

    def test_direct_launcher_cannot_derive_origin_from_environment(self):
        cfg = {'sshd_path': '/usr/sbin/sshd'}
        with mock.patch.dict(os.environ, {'SSH_CONNECTION': '127.0.0.1 1234 127.0.0.1 22',
                                         'SSH_USER_AUTH': '/tmp/forged'}, clear=True):
            with self.assertRaises((ValueError, FileNotFoundError)):
                L.authenticate(cfg, '/tmp/forged-config')


class OwnedSSH:
    """Reusable *test-owned* root/OpenSSH fixture in a network-denied container.

    No user HOME, ssh-agent, Docker socket or operational filesystem is used.
    Callers own Docker provisioning; this helper never launches a container.
    """
    def __init__(self, directory, source_root=ROOT, source=None, authorized=('client',)):
        if sys.platform != 'linux' or os.geteuid() != 0:
            raise RuntimeError('isolated Linux root runtime required')
        self.root = Path(directory).resolve()
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.scripts = self.root/'installed/scripts'
        self.scripts.mkdir(parents=True, mode=0o700)
        for path in Path(source_root).glob('v126-*'):
            if path.is_file(): shutil.copyfile(path, self.scripts/path.name)
        if source is not None:
            (self.scripts/'v126-cutover.sh').write_bytes(source)
        self.source = (self.scripts/'v126-cutover.sh').read_bytes()
        for name in ('host', 'client', 'other'):
            subprocess.run(['/usr/bin/ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(self.root/name)],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        self.host_fingerprint = self.fp(self.root/'host.pub')
        self.principal_fingerprint = self.fp(self.root/'client.pub')
        # StrictModes validates every ancestor; /tmp is deliberately writable.
        # Keep only this disposable authorized-key file below the owned /run tmpfs.
        self.auth_directory = tempfile.TemporaryDirectory(prefix='owned-policy-b-auth-', dir='/run')
        authorized_names = authorized
        authorized = Path(self.auth_directory.name)/'authorized_keys'
        authorized.write_bytes(b''.join((self.root/(name+'.pub')).read_bytes() for name in authorized_names))
        authorized.chmod(0o600)
        with socket.socket() as reserve:
            reserve.bind(('127.0.0.1',0)); self.port = reserve.getsockname()[1]
        self.config_path = self.root/'launcher.json'
        self.sshd_config = self.root/'sshd_config'
        self.command = 'exec ' + shlex.join([sys.executable, str(self.scripts/'v126-policy-b-launcher.py'), '--config', str(self.config_path)])
        self.sshd_config.write_text(f'''Port {self.port}
ListenAddress 127.0.0.1
HostKey {self.root/'host'}
PidFile {self.root/'sshd.pid'}
AuthorizedKeysFile {authorized}
PermitRootLogin prohibit-password
PasswordAuthentication no
AuthenticationMethods publickey
KbdInteractiveAuthentication no
UsePAM no
StrictModes yes
AllowUsers root
ExposeAuthInfo yes
ForceCommand {self.command}
DisableForwarding yes
PermitTTY no
PermitUserEnvironment no
PermitUserRC no
X11Forwarding no
AllowAgentForwarding no
AllowTcpForwarding no
AllowStreamLocalForwarding no
PermitTunnel no
''')
        self.target = self.root/'target'; self.target.mkdir(mode=0o700)
        self.request = opening(self.source, target=str(self.target), principal_fingerprint=self.principal_fingerprint,
                               host_fingerprint=self.host_fingerprint)
        self.config = dict(version=1, target=str(self.target), anchor_sha256=self.request['anchor_sha256'],
            anchor_generation=self.request['anchor_generation'], revocation_generation=1, principal_fingerprint=self.principal_fingerprint,
            host_fingerprint=self.host_fingerprint, source_sha=self.request['identity']['release_sha'],
            source_tree=self.request['source_tree'], tooling_sha256=self.request['tooling_sha256'],
            runtime=self.request['runtime'], source_path=str(self.scripts/'v126-cutover.sh'),
            source_sha256=hashlib.sha256(self.source).hexdigest(),
            modules={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in self.scripts.glob('v126-*') if path.is_file()},
            sshd_path='/usr/sbin/sshd', sshd_config_path=str(self.sshd_config),
            sshd_config_sha256=hashlib.sha256(self.sshd_config.read_bytes()).hexdigest(), launcher_command=self.command,
            authorized_keys_path=str(authorized), authorized_keys_sha256=hashlib.sha256(authorized.read_bytes()).hexdigest())
        self.config_path.write_bytes(T.canonical(self.config)); self.config_path.chmod(0o400)
        self.known = self.root/'known_hosts'
        self.known.write_text(f'[127.0.0.1]:{self.port} ' + ' '.join((self.root/'host.pub').read_text().split()[:2])+'\n')
        self.log = tempfile.TemporaryFile()
        Path('/run/sshd').mkdir(mode=0o755, exist_ok=True)
        self.process = subprocess.Popen(['/usr/sbin/sshd','-D','-e','-f',str(self.sshd_config)],
            stdin=subprocess.DEVNULL, stdout=self.log, stderr=self.log,
            env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin'}, start_new_session=True)
        deadline = time.monotonic()+5
        while time.monotonic()<deadline:
            if self.process.poll() is not None:
                raise RuntimeError('owned sshd setup failed')
            try:
                with socket.create_connection(('127.0.0.1',self.port),timeout=.1): break
            except OSError: time.sleep(.03)
        else: raise RuntimeError('owned sshd readiness timeout')

    @staticmethod
    def fp(path):
        key = base64.b64decode(path.read_text().split()[1], validate=True)
        return 'SHA256:'+base64.b64encode(hashlib.sha256(key).digest()).decode().rstrip('=')

    def options(self, key='client'):
        return dict(host='127.0.0.1',port=self.port,user='root',identity_file=str(self.root/key),
            known_hosts_file=str(self.known),host_fingerprint=self.host_fingerprint)

    def close(self):
        self.process.terminate()
        self.process.wait(timeout=5)
        self.log.close()
        self.auth_directory.cleanup()


INERT_SOURCE = b'''remote_operation_python() {
cat <<'PROGRAM'
session=POLICY_B_LAUNCH
transport=POLICY_B_TRANSPORT
session.channel.send(dict(version=1,type='OPERATION_RESULT',session_id=session.request['session_id'],operation_id=transport.digest(session.request['identity']),status=0,result={'authenticated':True}),deadline=__import__('time').monotonic()+5)
PROGRAM
}
'''


@unittest.skipUnless(REQUIRE, 'owned Linux/SSH authentication runtime not requested; not PASS evidence')
class OwnedSSHTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='owned-policy-b-ssh-')
        self.fixture = OwnedSSH(self.temp.name, source=INERT_SOURCE)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.fixture.close)

    def test_real_sshd_origin_and_pinned_transport(self):
        result = T.invoke_operation(self.fixture.options(), self.fixture.request, self.fixture.source, None)
        self.assertEqual(result['status'], 0)
        self.assertEqual(result['result'], {'authenticated':True})

    def test_wrong_single_principal_and_ambiguous_keyset_refuse(self):
        for keys in (('other',), ('client', 'other')):
            with self.subTest(keys=keys), tempfile.TemporaryDirectory() as directory:
                fixture = OwnedSSH(directory, source=INERT_SOURCE, authorized=keys)
                try:
                    with self.assertRaises(T.ProtocolError):
                        T.invoke_operation(fixture.options(keys[0]), fixture.request, fixture.source, None)
                finally:
                    fixture.close()

    def test_wrong_host_pin_and_client_source_drift_refuse(self):
        options = self.fixture.options(); options['host_fingerprint']='SHA256:'+'Z'*43
        with self.assertRaises(T.ProtocolError):
            T.invoke_operation(options,self.fixture.request,self.fixture.source,None)
        source = self.fixture.source+b'\n# drift\n'
        request = opening(source, target=str(self.fixture.target), principal_fingerprint=self.fixture.principal_fingerprint,
                          host_fingerprint=self.fixture.host_fingerprint)
        with self.assertRaises(T.ProtocolError):
            T.invoke_operation(self.fixture.options(),request,source,None)

    def _record_launcher_start(self):
        marker=self.fixture.root/'launcher-started'
        launcher=self.fixture.scripts/'v126-policy-b-launcher.py'
        source=launcher.read_text();needle='def main():\n'
        self.assertEqual(source.count(needle),1)
        launcher.write_text(source.replace(needle,needle+'    Path('+repr(str(marker))+').write_text("started")\n',1))
        self.fixture.config['modules'][launcher.name]=hashlib.sha256(launcher.read_bytes()).hexdigest()
        self.fixture.config_path.chmod(0o600)
        self.fixture.config_path.write_bytes(T.canonical(self.fixture.config));self.fixture.config_path.chmod(0o400)
        return marker

    def _restart_with_host_certificate(self):
        fixture=self.fixture
        fixture.process.terminate();fixture.process.wait(timeout=5)
        subprocess.run(['/usr/bin/ssh-keygen','-q','-s',str(fixture.root/'other'),'-I','synthetic-host',
            '-h','-n','127.0.0.1',str(fixture.root/'host.pub')],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10)
        with fixture.sshd_config.open('a') as output:output.write('HostCertificate '+str(fixture.root/'host-cert.pub')+'\n')
        fixture.config['sshd_config_sha256']=hashlib.sha256(fixture.sshd_config.read_bytes()).hexdigest()
        fixture.config_path.chmod(0o600);fixture.config_path.write_bytes(T.canonical(fixture.config));fixture.config_path.chmod(0o400)
        fixture.process=subprocess.Popen(['/usr/sbin/sshd','-D','-e','-f',str(fixture.sshd_config)],
            stdin=subprocess.DEVNULL,stdout=fixture.log,stderr=fixture.log,env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin'},start_new_session=True)
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            if fixture.process.poll() is not None:self.fail('owned certificate sshd failed')
            try:
                with socket.create_connection(('127.0.0.1',fixture.port),timeout=.1):return
            except OSError:time.sleep(.03)
        self.fail('owned certificate sshd readiness timeout')

    def test_unapproved_extra_host_and_ca_never_reach_launcher_open_or_payload(self):
        marker=self._record_launcher_start()
        fixture=self.fixture;host=f'[127.0.0.1]:{fixture.port}'
        approved=' '.join((fixture.root/'client.pub').read_text().split()[:2])
        server=' '.join((fixture.root/'host.pub').read_text().split()[:2])
        ca=' '.join((fixture.root/'other.pub').read_text().split()[:2])
        options=fixture.options();options['host_fingerprint']=fixture.fp(fixture.root/'client.pub')
        request=dict(fixture.request,host_fingerprint=options['host_fingerprint'])
        forms=[host+' '+server,host+' '+server+' trailing comment','* '+server,host+',other '+server,
               '@cert-authority '+host+' '+ca]
        for index,extra in enumerate(forms):
            with self.subTest(form=extra.split()[0]):
                if index==len(forms)-1:self._restart_with_host_certificate()
                fixture.known.write_text(host+' '+approved+'\n'+extra+'\n')
                with self.assertRaises(T.ProtocolError):T.invoke_operation(options,request,fixture.source,None)
                self.assertFalse(marker.exists(),'unapproved handshake reached the launcher before OPEN/SOURCE checks')

    def test_known_hosts_change_after_selection_cannot_change_real_handshake(self):
        fixture=self.fixture;actual=T.ssh_command
        wrong=' '.join((fixture.root/'other.pub').read_text().split()[:2])
        original=fixture.known.read_text();host=f'[127.0.0.1]:{fixture.port}'
        @contextmanager
        def replace_after_selection(options):
            with actual(options) as args:
                fixture.known.write_text(host+' '+wrong+'\n')
                yield args
        with mock.patch.object(T,'ssh_command',replace_after_selection):
            reply=T.invoke_operation(fixture.options(),fixture.request,fixture.source,None)
        self.assertEqual(reply['result'],{'authenticated':True})
        # The inverse mutation must not turn an unapproved server into a PASS.
        options=fixture.options();options['host_fingerprint']=fixture.fp(fixture.root/'other.pub')
        request=dict(fixture.request,host_fingerprint=options['host_fingerprint'])
        @contextmanager
        def add_server_after_selection(options):
            with actual(options) as args:
                fixture.known.write_text(original)
                yield args
        with mock.patch.object(T,'ssh_command',add_server_after_selection):
            with self.assertRaises(T.ProtocolError):T.invoke_operation(options,request,fixture.source,None)

    def test_direct_forged_environment_cannot_invoke_launcher(self):
        auth = self.fixture.root/'sshauth.fake'
        auth.write_text('publickey '+' '.join((self.fixture.root/'client.pub').read_text().split()[:2])+'\n')
        auth.chmod(0o600)
        result = subprocess.run([sys.executable,str(self.fixture.scripts/'v126-policy-b-launcher.py'),
            '--config',str(self.fixture.config_path)],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            env={'PATH':'/usr/bin:/bin','SSH_CONNECTION':f'127.0.0.1 1234 127.0.0.1 {self.fixture.port}',
                 'SSH_USER_AUTH':str(auth)},timeout=5)
        self.assertEqual(result.returncode,75)
        self.assertEqual(result.stdout,b'')

    def test_remote_forwarding_denied_by_sshd_before_forced_command(self):
        with T.ssh_command(self.fixture.options()) as args:
            index=args.index('ClearAllForwardings=yes');del args[index-1:index+1]
            result=subprocess.run(args[:-1]+['-N','-o','ExitOnForwardFailure=yes','-R','127.0.0.1:0:127.0.0.1:1',args[-1]],
                stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=5)
            self.assertNotEqual(result.returncode,0)
            self.assertIn(b'forwarding failed',result.stderr)

    def test_direct_tcpip_forwarding_cannot_reach_loopback_canary(self):
        with socket.socket() as canary, socket.socket() as reserve:
            canary.bind(('127.0.0.1',0)); canary.listen(); canary.settimeout(.3)
            reserve.bind(('127.0.0.1',0)); port=reserve.getsockname()[1]
            reserve.close()
            command=T.ssh_command(self.fixture.options())
            args=command.__enter__()
            self.addCleanup(command.__exit__,None,None,None)
            index=args.index('ClearAllForwardings=yes');del args[index-1:index+1]
            process=subprocess.Popen(args[:-1]+['-N','-L',f'127.0.0.1:{port}:127.0.0.1:{canary.getsockname()[1]}',args[-1]],
                stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            try:
                deadline=time.monotonic()+5
                while True:
                    try:
                        channel=socket.create_connection(('127.0.0.1',port),timeout=.2);break
                    except OSError:
                        if time.monotonic()>deadline: self.fail('owned forward request did not establish local listener')
                        time.sleep(.03)
                with channel:
                    channel.settimeout(1)
                    self.assertEqual(channel.recv(1),b'')
                with self.assertRaises(socket.timeout): canary.accept()
            finally:
                process.terminate();process.wait(timeout=5)


if __name__=='__main__':
    unittest.main()
