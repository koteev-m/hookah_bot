#!/usr/bin/env python3
"""Synthetic prospective transport/launcher/client joins; no keys or network.

The real authority consumer is exercised by the separate authority suite.
Here only framed I/O and public OS/runtime observations are fixture boundaries.
"""
import copy
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    module = importlib.util.module_from_spec(spec); sys.modules[name] = module
    spec.loader.exec_module(module); return module

T = load('prospective_transport', 'v126-policy-b-transport.py')
L = load('prospective_launcher', 'v126-policy-b-launcher.py')
C = load('prospective_client', 'v126-policy-b-client.py')
F = load('prospective_transport_fixture', 'fixtures/v126-authority-epoch-fixture.py')
E = F.E

class ProspectiveTransport(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='prospective-wire-'); self.addCleanup(temp.cleanup)
        self.target = Path(temp.name).resolve(); self.target.chmod(0o700); info = self.target.stat()
        self.fixture = F.Fixture(target=dict(path=str(self.target), device=info.st_dev, inode=info.st_ino,
            uid=info.st_uid, host_fingerprint='SHA256:'+'N'*43))
        self.request = copy.deepcopy(self.fixture.request)
        self.request['script_sha256'] = hashlib.sha256(b'fixture-source').hexdigest()
        self.epoch = E.epoch_context(self.request)
        basis = self.fixture.validate()
        self.basis = E.durable_basis(self.fixture.request, basis)
        self.identity = E.request_identity(self.request)
        self.opened = dict(version=2, type='OPEN', mode='EXECUTE', session_id='1'*64,
            anchor_sha256='2'*64, anchor_generation=1, principal_fingerprint='SHA256:'+'P'*43,
            host_fingerprint='SHA256:'+'N'*43, identity=self.identity, target=str(self.target),
            request_sha256=T.digest(self.request), manifest_sha256=T.digest(self.request),
            manifest_size=len(T.canonical(self.request)), source_tree=self.request['source_tree'],
            tooling_sha256=self.request['tooling_sha256'], runtime='3.12.3', worker_args=[], environment={},
            init_request=None, init_completion=None, payload=None, genesis_request=self.request, epoch=self.epoch)
        self.principal = {k:self.opened[k] for k in ('anchor_sha256','anchor_generation','principal_fingerprint','host_fingerprint','epoch')}
        self.principal.update(revocation_generation=0, authority_scope='BOOTSTRAP_ONLY')
        self.frames = []; self.mutate = lambda v:v; outer = self
        class Channel:
            def send(self, value, **kw): outer.frames.append(value)
            def recv(self, **kw):
                challenge = outer.frames[-1]
                return outer.mutate(dict(T.result_echo(challenge), decision='PASS',
                    barrier='PROSPECTIVE_ISOLATED_GENESIS' + ('_COPY' if challenge['genesis_mode']=='GENESIS_READBACK' else ''),
                    qualification_sha256=outer.request['isolation_proof_sha256'], catalogue_head_sha256='3'*64,
                    catalogue_sequence=6, revocation_generation=0, pins_sha256='4'*64,
                    native_stage7_sha256=None,native_manifest_sha256=None,
                    expiry={k:1000 for k in T.GENESIS_EXPIRY_KEYS}, prospective_basis=outer.basis))
            def require_idle_peer(self): pass
        self.session = T.Session(Channel(),self.opened,b'fixture-source',self.principal,lambda:None)
        self.gate = T.RemoteGate(self.session,lambda:'a'*64,lambda target,fd:None)

    def early(self): return self.gate.check(self.identity,self.target,None,300)

    def test_two_rounds_preserve_exact_epoch_and_proof(self):
        early=self.early();self.gate.before_create(self.identity,self.target,None,300)
        late=self.gate.check(self.identity,self.target,7,300)
        self.gate.before_dispatch(self.identity,self.target,7,300)
        self.gate.before_write(self.identity,self.target,7,300)
        self.assertEqual(early['epoch'],self.epoch);self.assertEqual(late['prospective_basis'],self.basis)
        self.assertEqual([f['version'] for f in self.frames],[2,2])
        self.assertEqual({f['operation_id'] for f in self.frames},{T.digest(self.identity)})
        self.assertFalse((self.target/'.v126-target-operations').exists())

    def test_epoch_version_downgrade_and_partial_identity_refuse(self):
        for field in ('version','epoch','epoch_id','domain_identity_sha256'):
            bad=copy.deepcopy(self.opened)
            if field=='version':bad[field]=1
            elif field=='epoch':del bad[field]
            else:del bad['identity'][field]
            with self.subTest(field=field),self.assertRaises((T.ProtocolError,KeyError)):T.validate_open(bad)

    def test_wrong_epoch_on_each_wire_surface_refuses(self):
        for key in self.epoch:
            bad=copy.deepcopy(self.opened);bad['epoch'][key]='f'*64
            if key!='predecessor_index_sha256':
                with self.assertRaises(T.ProtocolError):T.validate_open(bad)
            with self.assertRaises(T.ProtocolError):
                T.Session(self.session.channel,bad,b'fixture-source',self.principal,lambda:None).verify()
        self.mutate=lambda v:dict(v,epoch=dict(v['epoch'],predecessor_index_sha256='e'*64))
        with self.assertRaises(T.ProtocolError):self.early()

    def test_legacy_inventory_cannot_replace_prospective_proof(self):
        def substitute(v):v.pop('prospective_basis');v['legacy_inventory']={};return v
        self.mutate=substitute
        with self.assertRaises(T.ProtocolError):self.early()

    def test_r0_and_wrong_proof_digest_are_not_genesis(self):
        for fields in ({'barrier':'R0'},{'barrier':'LEGACY_GENESIS'},{'qualification_sha256':'d'*64}):
            self.setUp();self.mutate=lambda v:dict(v,**fields)
            with self.assertRaises(T.ProtocolError):self.early()

    def test_missing_result_epoch_and_replay_refuse(self):
        self.mutate=lambda v:{k:x for k,x in v.items() if k!='epoch'}
        with self.assertRaises(T.ProtocolError):self.early()
        self.setUp();self.early();old=self.gate.pending['result'];self.mutate=lambda v:old
        with self.assertRaises(T.ProtocolError):self.gate.check(self.identity,self.target,7,300)

    def test_legacy_authenticated_principal_cannot_enter_epoch(self):
        principal={k:v for k,v in self.principal.items() if k not in ('epoch','authority_scope')}
        with self.assertRaises(T.ProtocolError):T.Session(self.session.channel,self.opened,b'fixture-source',principal,lambda:None).verify()

    def test_bootstrap_principal_cannot_dispatch_stage(self):
        opened=copy.deepcopy(self.opened);opened['identity'].update(kind='STAGE',name='BASELINE_VERIFIED',action='baseline')
        del opened['genesis_request'];opened.update(manifest_sha256=None,manifest_size=0)
        with self.assertRaisesRegex(T.ProtocolError,'BOOTSTRAP_SCOPE'):
            T.Session(self.session.channel,opened,b'fixture-source',self.principal,lambda:None).verify()

    def test_prospective_same_version_runtime_is_mandatory(self):
        for runtime in ('3.12.2','3.13.2'):
            with self.assertRaises(T.ProtocolError):T.validate_open(dict(self.opened,runtime=runtime))

    def test_copy_reads_same_durable_result_name_without_new_genesis(self):
        root=self.target/'.v126-target-operations';root.mkdir();(root/'genesis.result.json').write_bytes(b'synthetic-durable-bytes')
        self.opened['mode']='GENESIS_READBACK';self.session.__post_init__()
        self.gate.check(self.identity,self.target,7,300)
        self.assertEqual(self.frames[-1]['genesis_completion_sha256'],hashlib.sha256(b'synthetic-durable-bytes').hexdigest())
        self.assertEqual(self.gate.pending['result']['barrier'],'PROSPECTIVE_ISOLATED_GENESIS_COPY')

    def configuration(self):
        facts=copy.deepcopy(self.fixture.enrollment)
        runtime=dict(schema_version=2,kind='ap06-python-runtime',role='S',python_version='3.12.3',
            executable_path=str(Path(sys.executable).resolve()),executable_sha256=hashlib.sha256(Path(sys.executable).resolve().read_bytes()).hexdigest(),
            closure_sha256=F.h('synthetic-runtime-closure'),origin_sha256=F.h('synthetic-runtime-origin'))
        facts['server_runtime_sha256']=E.digest(runtime)
        return dict(version=2,epoch=self.epoch,authority_scope='BOOTSTRAP_ONLY',enrollment_facts=facts,server_runtime=runtime,
            source_sha=facts['source']['commit'],source_tree=facts['source']['tree'],tooling_sha256=facts['source']['tooling_sha256'],
            principal_fingerprint=facts['principal_fingerprint'],host_fingerprint=facts['host_fingerprint'],runtime='3.12.3')

    def config_check(self,cfg):
        # Portable schema join: only runtime/root OS observation is substituted;
        # owned-sshd execution is a separate mandatory Linux qualification.
        with patch.object(L.platform,'python_version',return_value='3.12.3'),patch.object(L,'protected',lambda path,**kw:Path(path).read_bytes()):
            L.validate_prospective_configuration(cfg)

    def test_launcher_config_exact_join(self):self.config_check(self.configuration())

    def test_launcher_rejects_admin_reuse_wrong_domain_host_and_runtime(self):
        for change in ('reuse','domain','host','runtime','interpreter','scope'):
            cfg=self.configuration()
            if change=='reuse':cfg['enrollment_facts']['excluded_principal_fingerprints']=[cfg['principal_fingerprint']]
            if change=='domain':cfg['epoch']['domain_identity_sha256']='f'*64
            if change=='host':cfg['host_fingerprint']='SHA256:'+'X'*43
            if change=='runtime':cfg['runtime']='3.13.2'
            if change=='interpreter':cfg['enrollment_facts']['server_runtime_sha256']='f'*64
            if change=='scope':cfg['authority_scope']='ANYTHING'
            with self.subTest(change=change),self.assertRaises(ValueError):self.config_check(cfg)

    def test_ssh_locator_must_equal_independently_enrolled_endpoint(self):
        facts=self.fixture.enrollment
        class Verifier:
            def transport_enrollment(self):return facts
        options=dict(facts['endpoint']);options.pop('peer');options['host_fingerprint']=facts['host_fingerprint']
        C.validate_prospective_transport(Verifier(),options)
        for key,value in [('host','178.20.209.5'),('port',22),('user','admin'),('host_fingerprint','SHA256:'+'A'*43)]:
            with self.subTest(key=key),self.assertRaises(ValueError):C.validate_prospective_transport(Verifier(),dict(options,**{key:value}))

if __name__=='__main__':unittest.main()
