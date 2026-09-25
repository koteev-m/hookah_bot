#!/usr/bin/env python3
"""Network-denied Linux only: actual outer CLI, sshd, launcher and attended V.

All P/C/controller/key facts are SYNTHETIC TEST ONLY, acquired at the same fixture
I/O boundary as the existing owned-SSH suites. No operational enrollment is made.
The production consumers are unchanged; only external Git/time observations are
substituted. The test stops at metadata genesis/copy, never INIT or a deploy leaf.
"""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pty
import re
import select
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import types
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
if '--require-linux-ssh' not in sys.argv:
    raise SystemExit('explicit --require-linux-ssh required; unavailable is not PASS')
sys.argv.remove('--require-linux-ssh')
if sys.platform != 'linux' or os.geteuid() != 0 or sys.version_info[:3] != (3, 12, 3):
    raise SystemExit('isolated Linux root and exact Python 3.12.3 required')


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); sys.modules[name] = module
    spec.loader.exec_module(module); return module


saved = list(sys.argv); sys.argv = ['prospective-owned-fixture', '--require-linux-ssh']
linux = load('prospective_owned_linux_base', ROOT/'test-v126-policy-b-linux.py'); sys.argv = saved
T = linux.T


class ProspectiveSSH(linux.LinuxCaller):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='prospective-owned-ssh-'); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.source_root = self.root/'source'
        (self.source_root/'scripts').mkdir(parents=True); (self.source_root/'docs').mkdir()
        for path in ROOT.iterdir():
            if path.is_file() and path.name.startswith(('v126-', 'test-v126-')):
                shutil.copyfile(path, self.source_root/'scripts'/path.name)
        (self.source_root/'scripts/fixtures').mkdir()
        shutil.copyfile(ROOT/'fixtures/v126-authority-epoch-fixture.py', self.source_root/'scripts/fixtures/v126-authority-epoch-fixture.py')
        shutil.copyfile(ROOT.parent/'docs/V126_DATABASE_RECOVERY_REHEARSAL.md', self.source_root/'docs/V126_DATABASE_RECOVERY_REHEARSAL.md')
        self.ssh = linux.transport_tests.OwnedSSH(self.root/'ssh', source_root=self.source_root/'scripts')
        self.addCleanup(self.ssh.close)
        # Adapt only the test-owned listener to the production prospective policy.
        self.ssh.process.terminate(); self.ssh.process.wait(timeout=5)
        cfg = self.ssh.sshd_config.read_text()
        cfg = cfg.replace('Port '+str(self.ssh.port)+'\n', 'Port 2226\n').replace('ListenAddress 127.0.0.1\n', 'ListenAddress 127.0.0.2\n')
        cfg = cfg.replace('AllowUsers root\n', 'AllowUsers root@127.0.0.1\n')
        self.ssh.sshd_config.write_text(cfg); self.ssh.port = 2226
        self.ssh.known.write_text('[127.0.0.2]:2226 ' + ' '.join((self.ssh.root/'host.pub').read_text().split()[:2])+'\n')
        self.ssh.options = lambda key='client': dict(host='127.0.0.2',port=2226,user='root',identity_file=str(self.ssh.root/key),
            known_hosts_file=str(self.ssh.known),host_fingerprint=self.ssh.host_fingerprint)
        self.ssh.process = subprocess.Popen(['/usr/sbin/sshd','-D','-e','-f',str(self.ssh.sshd_config)],
            stdin=subprocess.DEVNULL,stdout=self.ssh.log,stderr=self.ssh.log,env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin'},start_new_session=True)
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            if self.ssh.process.poll() is not None: self.fail('owned prospective listener setup failed')
            try:
                with socket.create_connection(('127.0.0.2',2226),timeout=.1): break
            except OSError: time.sleep(.03)
        else: self.fail('owned prospective listener readiness timeout')
        for name in list(sys.modules):
            if name.startswith('v126_'): sys.modules.pop(name)
        self.client = load('prospective_linux_client', self.source_root/'scripts/v126-policy-b-client.py')
        self.authority = self.client.module('v126_policy_b_authority','v126-policy-b-authority.py')
        self.e = self.authority.epoch
        self.fixture_module = load('prospective_linux_public_fixture', self.source_root/'scripts/fixtures/v126-authority-epoch-fixture.py')
        self.bindings = load('prospective_linux_bindings', self.source_root/'scripts/v126-operation-bindings.py')
        self.state = self.root/'local-state'; self.history = self.ssh.target/'.v126-target-operations'
        self.proposal = self.root/'isolated-proposal.json'; self.completion = self.root/'isolated-completion.json'
        self.build_authority()
        self.manifest = T.canonical(dict(created_at='2026-09-01T00:00:00Z',format_version=2,epoch=self.e.epoch_context(self.fx.anchor),
            database_url_file=str(self.root/'unused-database'),maintenance_identities_file=str(self.root/'unused-identities'),
            main_actions_run_id=123456,release_parents=['a'*40],release_sha=linux.SHA,release_tree=linux.TREE,
            release_worktree=str(self.source_root),remote='owned-fixture-only',run_id='test-prospective-genesis',
            script_sha256=hashlib.sha256(self.ssh.source).hexdigest(),staging_path=str(self.ssh.target),
            v125_image_tag='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',v126_image_id='sha256:'+'a'*64,v126_image_tag='synthetic:'+linux.SHA))

    def build_authority(self, request=None):
        target=self.ssh.target; info=target.stat(); runtime=Path(sys.executable).resolve()
        def runtime_record(doc):
            doc.update(executable_path=str(runtime),executable_sha256=hashlib.sha256(runtime.read_bytes()).hexdigest())
        def enrollment(doc):
            doc.update(principal_fingerprint=self.ssh.principal_fingerprint,host_fingerprint=self.ssh.host_fingerprint,
                excluded_principal_fingerprints=[self.ssh.fp(self.ssh.root/'other.pub')],endpoint=dict(host='127.0.0.2',port=2226,user='root',peer='127.0.0.1'))
        def anchor(doc): doc.update(principal_fingerprint=self.ssh.principal_fingerprint,host_fingerprint=self.ssh.host_fingerprint)
        def proposal(doc):
            doc['script_sha256']=hashlib.sha256(self.ssh.source).hexdigest()
            if request is not None: doc.clear(); doc.update(copy.deepcopy(request))
        source=dict(commit=linux.SHA,tree=linux.TREE,tooling_sha256='')
        fx=self.fixture_module.Fixture(root=self.root/'authority',target=dict(path=str(target),device=info.st_dev,inode=info.st_ino,uid=info.st_uid,
            host_fingerprint=self.ssh.host_fingerprint),source=source,tools=self.authority.dr.tooling(self.source_root),
            changes={'runtime_v':runtime_record,'runtime_s':runtime_record,'enrollment':enrollment,'anchor':anchor,'request':proposal})
        fx.wall=self.e.S.timestamp(fx.now['utc']);fx.mono=fx.now['monotonic_seconds'];fx.anchorpath=self.root/'authority/anchor.json'
        fx.target=target; self.fx=fx
        fx.enrollment=types.SimpleNamespace(sha256=self.e.digest(fx.anchor))
        self.materialize()
        self.ssh.config.update(version=2,target=str(target),anchor_sha256=self.e.digest(fx.anchor),anchor_generation=fx.anchor['generation'],revocation_generation=0,
            source_sha=linux.SHA,source_tree=linux.TREE,tooling_sha256=fx.anchor['tooling_sha256'],runtime='3.12.3',epoch=self.e.epoch_context(fx.anchor),
            authority_scope='BOOTSTRAP_ONLY',enrollment_facts=fx.enrollment if isinstance(fx.enrollment,dict) else self.e.strict(fx.documents[fx.enrollment_ref]),
            server_runtime=self.e.strict(fx.documents[self.e.strict(fx.documents[fx.enrollment_ref])['server_runtime_sha256']]),
            sshd_config_sha256=hashlib.sha256(self.ssh.sshd_config.read_bytes()).hexdigest())
        self.save_config()

    def materialize(self):
        fx=self.fx
        for ref,raw in fx.documents.items():
            path=Path(fx.anchor['evidence_root'])/ref;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw);path.chmod(0o600)
        fx.anchorpath.parent.mkdir(parents=True,exist_ok=True);fx.anchorpath.write_bytes(self.e.canonical(fx.anchor));fx.anchorpath.chmod(0o600)
        for name,value in (('catalogue',fx.catalogue),('highwater',fx.highwater),('producer',fx.observations),('clock',fx.clock_record)):
            path=Path(fx.anchor['sources'][name]['path']);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(self.e.canonical(value));path.chmod(0o600)

    def save_config(self):
        self.ssh.config_path.chmod(0o600);self.ssh.config_path.write_bytes(T.canonical(self.ssh.config));self.ssh.config_path.chmod(0o400)

    def outer(self):
        script=self.outer_cli_fixture();driver=self.root/'outer-bin/python3';text=driver.read_text()
        marker='authority.VVerifier=ObservedVerifier\n'
        text=text.replace(marker,marker+'\nfrom datetime import datetime,timezone\nclass FixtureDate(datetime):\n    @classmethod\n    def now(cls,tz=None):return datetime.fromtimestamp('+repr(self.fx.wall-120)+',timezone.utc)\nclient.datetime=FixtureDate\n')
        driver.write_text(text);return script

    def prepare(self):
        script=self.outer(); init_proposal=self.root/'init-proposal.json'
        with linux.git_observations(): self.client.prepare_init_proposal(self.state,init_proposal,self.manifest)
        info=self.ssh.target.stat()
        command=['bash',str(script),'prepare-isolated-target-binding','--state-dir',str(self.state),
            '--init-proposal-file',str(init_proposal),'--init-proposal-sha256',hashlib.sha256(init_proposal.read_bytes()).hexdigest(),
            '--proposal-file',str(self.proposal),'--isolation-proof-sha256',self.fx.proof_ref,'--target-device',str(info.st_dev),
            '--target-inode',str(info.st_ino),'--target-uid',str(info.st_uid),'--host-fingerprint',self.ssh.host_fingerprint,'--expires-at',self.fx.request['expires_at']]
        result=subprocess.run(command,env=self.outer_env,capture_output=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr.decode());self.summary=json.loads(result.stdout);raw=self.proposal.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),self.summary['proposal_sha256'])
        self.assertFalse(self.history.exists());self.assertFalse(self.state.exists())
        # Approval is rebuilt only after immutable outer proposal bytes exist.
        self.build_authority(json.loads(raw));self.assertEqual(raw,self.proposal.read_bytes())
        self.assertEqual(self.e.request_identity(self.fx.request),self.summary['identity']);return raw

    def invoke_cli(self, copy_only=False, success=True):
        command=['bash',str(self.source_root/'scripts/v126-cutover.sh'),'copy-isolated-target-binding-completion' if copy_only else 'bind-isolated-target',
            '--proposal-file',str(self.proposal),'--proposal-sha256',self.summary['proposal_sha256'],'--policy-b-anchor',str(self.fx.anchorpath),
            '--policy-b-transport',str(self.locator),'--remote','owned-fixture-only','--completion-file',str(self.completion)]
        if copy_only:command += ['--authorization','AUTHORIZE_V126_GENESIS_COMPLETION_COPY']
        pid,terminal=pty.fork()
        if pid==0:os.execvpe(command[0],command,self.outer_env)
        deadline=time.monotonic()+40;captured=b'';answered=set();status=None
        try:
            while time.monotonic()<deadline:
                if select.select([terminal],[],[],.05)[0]:
                    try:captured+=os.read(terminal,65536)
                    except OSError:pass
                    if b'U:' in captured and 'U' not in answered:
                        os.write(terminal,(self.e.digest(self.fx.anchor)+'\n').encode());answered.add('U')
                    for match in re.finditer(rb'type nonce ([0-9a-f]{64}): ',captured):
                        nonce=match.group(1)
                        if nonce not in answered:os.write(terminal,nonce+b'\n');answered.add(nonce)
                done,status=os.waitpid(pid,os.WNOHANG)
                if done:break
            else:os.kill(pid,9);os.waitpid(pid,0);self.fail('bounded prospective CLI deadline')
        finally:os.close(terminal)
        self.assertEqual(os.waitstatus_to_exitcode(status),0 if success else 75,captured.decode(errors='replace'))
        if success:self.assertEqual(len(answered),3,'U plus independent EARLY/LATE C confirmations')
        return captured

    def approve_copy(self):
        fx=self.fx; completion=hashlib.sha256((self.history/'genesis.result.json').read_bytes()).hexdigest()
        approval=fx.rec('htqr-prospective-action-approval',epoch_id=fx.epoch_id,domain_identity_sha256=fx.domain_ref,
            anchor_sha256=self.e.digest(fx.anchor),request_sha256=self.e.digest(fx.request),scope=self.e.SCOPES[1],
            approver_identity_sha256=fx.anchor['custodian_identity'],valid_from=self.fixture_module.timestamp(-120),
            valid_until=self.fixture_module.timestamp(3600),observed=fx.observed)
        fx.event('ACTION_AUTHORIZED',request_sha256=self.e.digest(fx.request),scope=self.e.SCOPES[1],approval_record_sha256=fx.add(approval))
        inventory=self.e.strict(fx.documents[fx.inventory_ref]);inventory.update(root_state='DURABLE_COMPLETION',completion_sha256=completion,
            operation_ids=[fx.request['run_id']]);fx.inventory_ref=fx.add(inventory);fx.finish()
        fx.catalogue['completion_sha256']=completion;fx.catalogue['actions']=[dict(identity=self.e.request_identity(fx.request),scope=self.e.SCOPES[1],
            valid_from=self.fixture_module.timestamp(-120),valid_until=self.fixture_module.timestamp(3600))];self.materialize()

    def test_outer_prepare_independent_approval_genesis_and_copy(self):
        raw=self.prepare();self.invoke_cli();completion=json.loads(self.completion.read_bytes())
        self.assertEqual(T.canonical(completion['request']),raw)
        self.assertEqual(completion['result']['completion'],'PROSPECTIVE_ISOLATED_DOMAIN_BOUND')
        self.assertFalse(self.state.exists());self.assertFalse(list(self.history.glob('*.start.json')))
        self.bindings.binding_history(self.history,self.ssh.target)
        before={p.name:(p.read_bytes(),p.stat().st_ino,p.stat().st_mode) for p in self.history.iterdir()}
        self.approve_copy();self.completion=self.root/'copied-completion.json';self.invoke_cli(copy_only=True)
        self.assertEqual(before,{p.name:(p.read_bytes(),p.stat().st_ino,p.stat().st_mode) for p in self.history.iterdir()})
        self.assertFalse(self.state.exists())

    def test_raw_caller_cannot_bypass_server_selected_launcher(self):
        self.prepare()
        with T.ssh_command(self.ssh.options()) as argv:
            result=subprocess.run(argv+['python3 -c "raise SystemExit(0)"'],input=b'raw caller',capture_output=True,timeout=10)
        self.assertEqual(result.returncode,75);self.assertFalse(self.history.exists())

    def test_launcher_principal_peer_runtime_and_epoch_mismatch(self):
        self.prepare(); original=copy.deepcopy(self.ssh.config)
        cases={'principal':lambda c:c.update(principal_fingerprint=self.ssh.fp(self.ssh.root/'other.pub')),
            'peer':lambda c:c['enrollment_facts']['endpoint'].update(peer='127.0.0.3'),
            'runtime':lambda c:c['server_runtime'].update(executable_sha256='f'*64),
            'epoch':lambda c:c['epoch'].update(epoch_id='f'*64)}
        for name,change in cases.items():
            with self.subTest(name=name):
                self.ssh.config=copy.deepcopy(original);change(self.ssh.config);self.save_config();self.invoke_cli(success=False)
                self.assertFalse(self.history.exists());self.assertFalse(self.completion.exists());self.assertFalse(self.state.exists())

    def test_bootstrap_listener_refuses_actual_init_before_verifier_or_writer(self):
        self.prepare();self.invoke_cli()
        before={p.name:(p.read_bytes(),p.stat().st_ino,p.stat().st_mode) for p in self.history.iterdir()}
        request=self.client.init_request(self.state,self.manifest)
        enrollment=types.SimpleNamespace(document=self.fx.anchor,sha256=self.e.digest(self.fx.anchor))
        opened=self.client.make_open(enrollment,self.manifest,request['identity'],request)
        T.validate_open(opened)
        challenges=[];writes=[]
        class UnexpectedVerifier:
            def verify(self,challenge):
                challenges.append(challenge)
                raise AssertionError('BOOTSTRAP_ONLY listener admitted a full INIT challenge')
        def unexpected_writer(message):
            writes.append(message)
            raise AssertionError('BOOTSTRAP_ONLY listener admitted INIT metadata write')
        with self.assertRaises(T.ProtocolError):
            T.invoke_operation(self.ssh.options(),opened,self.ssh.source,UnexpectedVerifier(),init_writer=unexpected_writer)
        self.assertEqual(challenges,[]);self.assertEqual(writes,[])
        self.assertFalse(self.state.exists())
        self.assertEqual(before,{p.name:(p.read_bytes(),p.stat().st_ino,p.stat().st_mode) for p in self.history.iterdir()})

    def test_missing_isolation_proof_refuses_before_first_write(self):
        self.prepare();original=copy.deepcopy(self.fx.observations)
        self.fx.observations['isolation_proof_sha256']='f'*64;self.materialize();self.invoke_cli(success=False)
        self.assertFalse(self.history.exists());self.fx.observations=original

    def test_duplicate_genesis_and_legacy_downgrade_refuse(self):
        self.prepare();self.invoke_cli();before={p.name:p.read_bytes() for p in self.history.iterdir()}
        self.completion=self.root/'retry-completion.json';self.invoke_cli(success=False)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.history.iterdir()})
        self.ssh.config={k:v for k,v in self.ssh.config.items() if k not in ('epoch','authority_scope','enrollment_facts','server_runtime')}
        self.ssh.config['version']=1;self.save_config();self.invoke_cli(success=False)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.history.iterdir()})


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(ProspectiveSSH(name) for name in sorted(ProspectiveSSH.__dict__) if name.startswith('test_'))


if __name__=='__main__':
    linux.isolation_report()
    unittest.main()
