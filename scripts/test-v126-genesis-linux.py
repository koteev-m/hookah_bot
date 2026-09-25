#!/usr/bin/env python3
"""Actual outer CLI/owned sshd/launcher/authority/genesis in a network-denied Linux fixture.

Only test-owned sources, credentials, observations and captured leaves are used.
No operational readers or external egress are accessible. Never a staging proof.
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
import subprocess
import sys
import time
import unittest
from unittest import mock

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parent
if '--require-linux-ssh' not in sys.argv:raise SystemExit('explicit --require-linux-ssh required')
sys.argv.remove('--require-linux-ssh')
if sys.platform!='linux' or os.geteuid()!=0:raise SystemExit('owned Linux root runtime required')

def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec)
    sys.modules[name]=value;spec.loader.exec_module(value);return value

saved=list(sys.argv);sys.argv=['owned-genesis','--require-linux-ssh']
linux=load('genesis_existing_linux',ROOT/'test-v126-policy-b-linux.py');sys.argv=saved
T=linux.T


class GenesisSSH(linux.LinuxCaller):
    def setUp(self):
        if self._testMethodName=='test_genesis_first_write_has_no_pre_admission_output_spool':
            original_copy=shutil.copyfile
            def copy_public(source,destination,*args,**kwargs):
                result=original_copy(source,destination,*args,**kwargs)
                if Path(destination).name=='v126-policy-b-launcher.py':
                    path=Path(destination);text=path.read_text()
                    # Test-owned enrolled source: tripwire for any diagnostic
                    # tempfile allocation. Actual gate/auth/dispatch stay intact.
                    text=text.replace('def tempfile_output():',
                        "def tempfile_output():\n    raise AssertionError('genesis used a pre-admission spool')",1)
                    path.write_text(text)
                return result
            with mock.patch.object(shutil,'copyfile',side_effect=copy_public):super().setUp()
        else:super().setUp()
        self.original={name:copy.deepcopy(getattr(self.fx,name)) for name in ('anchor','catalogue','producer')}
        # Remove only the synthetic preaccepted root created by parent fixture.
        # The production caller has no equivalent cleanup or adopt operation.
        shutil.rmtree(self.history)
        self.cases=load('genesis_independent_fixture',self.source_root/'scripts/test-v126-legacy-genesis.py')
        self.cases.f=self.authority_cases;self.cases.a=self.authority_cases.a;self.cases.g=self.authority_cases.a.genesis
        self.fx.genesis_owner_run_id=self.owner['run_id']
        self.configure_evidence=self.cases.configure
        self.configure_evidence(self.fx)
        request=dict(self.fx.request,run_id=self.owner['run_id'],next_init_manifest_sha256=hashlib.sha256(self.manifest).hexdigest())
        self.cases.configure(self.fx,request)
        self.configure(self.fx)
        self.proposal=self.root/'genesis-proposal.json'
        self.completion=self.root/'genesis-completion.json'

    def outer(self):
        script=self.outer_cli_fixture()
        driver=self.root/'outer-bin/python3'
        value=driver.read_text()
        marker="authority.VVerifier=ObservedVerifier\n"
        value=value.replace(marker,marker+"\nfrom datetime import datetime,timezone\nclass FixtureDate(datetime):\n    @classmethod\n    def now(cls,tz=None):return datetime.fromtimestamp("+repr(self.fx.wall-10)+",timezone.utc)\nclient.datetime=FixtureDate\n")
        driver.write_text(value)
        return script

    def prepare(self):
        script=self.outer()
        self.init_proposal=self.root/'init-proposal.json'
        with linux.git_observations():
            self.client.prepare_init_proposal(self.state,self.init_proposal,self.manifest)
        info=self.fx.target.stat()
        command=['bash',str(script),'prepare-target-binding','--state-dir',str(self.state),
            '--init-proposal-file',str(self.init_proposal),'--init-proposal-sha256',hashlib.sha256(self.init_proposal.read_bytes()).hexdigest(),
            '--proposal-file',str(self.proposal),'--inventory-sha256',self.fx.request['inventory_sha256'],
            '--target-device',str(info.st_dev),'--target-inode',str(info.st_ino),'--target-uid',str(info.st_uid),
            '--host-fingerprint',self.ssh.host_fingerprint,'--expires-at',self.fx.request['expires_at']]
        result=subprocess.run(command,env=self.outer_env,capture_output=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr.decode())
        self.summary=json.loads(result.stdout);raw=self.proposal.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),self.summary['proposal_sha256'])
        self.assertFalse(self.history.exists());self.assertFalse(self.state.exists())
        time.sleep(.02)  # Explicit pause between proposal and independent approval, not a runtime fix.
        self.configure_evidence(self.fx,json.loads(raw));self.configure(self.fx)
        self.assertEqual(raw,self.proposal.read_bytes())
        self.assertEqual(self.summary['identity'],self.fx.identity)
        return raw

    def invoke_cli(self,*,copy_only=False,success=True):
        command=['bash',str(self.source_root/'scripts/v126-cutover.sh'),
            'copy-target-binding-completion' if copy_only else 'bind-legacy-target',
            '--proposal-file',str(self.proposal),'--proposal-sha256',self.summary['proposal_sha256'],
            '--policy-b-anchor',str(self.fx.anchorpath),'--policy-b-transport',str(self.locator),
            '--remote','owned-fixture-only','--completion-file',str(self.completion)]
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
                        os.write(terminal,(self.fx.enrollment.sha256+'\n').encode());answered.add('U')
                    for match in re.finditer(rb'type nonce ([0-9a-f]{64}): ',captured):
                        nonce=match.group(1)
                        if nonce not in answered:os.write(terminal,nonce+b'\n');answered.add(nonce)
                done,status=os.waitpid(pid,os.WNOHANG)
                if done:break
            else:
                os.kill(pid,9);os.waitpid(pid,0);self.fail('owned genesis caller exceeded deadline')
        finally:os.close(terminal)
        code=os.waitstatus_to_exitcode(status)
        self.assertEqual(code,0 if success else 75,captured.decode(errors='replace'))
        if success:self.assertEqual(len(answered),3,'U and fresh C EARLY/LATE required')
        return captured

    def test_genesis_outer_prepare_approval_completion_init_baseline(self):
        raw=self.prepare();self.invoke_cli()
        proof=json.loads(self.completion.read_bytes())
        self.assertEqual(T.canonical(proof['request']),raw)
        self.assertEqual(proof['result']['completion'],'LEGACY_DISPOSITION_BOUND')
        self.assertFalse(self.state.exists())
        self.assertEqual(self.bindings.binding_history(self.history,self.fx.target)[0],self.owner)
        for name,value in self.original.items():setattr(self.fx,name,value)
        for name in ('catalogue','producer'):self.fx.save(name)
        self.fx.write(self.fx.anchorpath,self.fx.anchor);self.fx.console.approved=self.authority_cases.a.dr.digest(self.fx.anchor)
        self.fx.enrollment=self.authority_cases.a.load_enrolled_authority(self.fx.anchorpath,self.fx.console)
        self.fx.identity=self.request['identity'];self.configure(self.fx)
        self.initialize();self.baseline()
        self.assertEqual([x['action'] for x in self.transfer_events() if x['event']=='LEAF'],['baseline'])
        self.assertEqual(raw,(self.history/'genesis.request.json').read_bytes())

    def test_genesis_first_write_has_no_pre_admission_output_spool(self):
        self.prepare();observations=copy.deepcopy(self.fx.producer)
        self.fx.producer['cessations']=[];self.fx.save('producer')
        self.invoke_cli(success=False)
        self.assertFalse(self.history.exists());self.assertFalse(self.state.exists())
        self.fx.producer=observations;self.fx.save('producer')
        self.invoke_cli()
        self.assertTrue((self.history/'genesis.result.json').exists())
        self.assertFalse(self.state.exists())
        self.assertFalse(any(x['event']=='LEAF' for x in self.transfer_events()))

    def test_genesis_native_terminal_full_verifier_through_outer_cli(self):
        self.configure_evidence=self.cases.configure_native
        self.configure_evidence(self.fx)
        self.configure(self.fx)
        self.prepare();self.invoke_cli()
        proof=json.loads(self.completion.read_bytes())
        kinds={row['disposition']['kind'] for row in proof['legacy_inventory']['runs']}
        self.assertEqual(kinds,{'NATIVE_TERMINAL','LEGACY_UNKNOWN_FENCED'})
        self.assertEqual(self.bindings.binding_history(self.history,self.fx.target)[0],self.owner)
        self.assertFalse(self.state.exists())
        self.assertFalse(any(x['event']=='LEAF' for x in self.transfer_events()))

    def test_genesis_early_report_only_refuses_without_registry(self):
        self.prepare();self.fx.producer['cessations']=[];self.fx.save('producer')
        self.invoke_cli(success=False)
        self.assertFalse(self.history.exists());self.assertFalse(self.completion.exists());self.assertFalse(self.state.exists())
        self.assertFalse(any(x['event']=='LEAF' for x in self.transfer_events()))

    def test_genesis_exact_proposal_drift_duplicate_and_existing_root(self):
        self.prepare();raw=self.proposal.read_bytes()
        self.proposal.chmod(0o600);self.proposal.write_bytes(raw+b' ');self.proposal.chmod(0o400)
        self.invoke_cli(success=False);self.assertFalse(self.history.exists())
        self.proposal.chmod(0o600);self.proposal.write_bytes(raw);self.proposal.chmod(0o400)
        self.invoke_cli();before={p.name:p.read_bytes() for p in self.history.iterdir()}
        self.completion=self.root/'duplicate-completion.json'
        self.invoke_cli(success=False)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.history.iterdir()})

    def test_genesis_late_source_loss_retains_only_partial_root_and_blocks_reentry(self):
        self.prepare()
        driver=self.root/'outer-bin/python3';text=driver.read_text()
        text=text.replace('            return super().verify(challenge)',
            "            result=super().verify(challenge)\n            if challenge['phase']=='EARLY':Path("+repr(self.fx.anchor['sources']['producer']['path'])+").unlink()\n            return result",1)
        driver.write_text(text);self.invoke_cli(success=False)
        self.assertEqual({p.name for p in self.history.iterdir()},{'lock'})
        before={p.name:p.read_bytes() for p in self.history.iterdir()}
        self.outer();self.invoke_cli(success=False)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.history.iterdir()})
        self.assertFalse(self.state.exists());self.assertFalse(self.completion.exists())
        self.assertFalse(any(x['event']=='LEAF' for x in self.transfer_events()))

    def test_genesis_existing_partial_root_refuses_before_challenge(self):
        self.prepare();self.history.mkdir(mode=0o700)
        self.invoke_cli(success=False)
        self.assertEqual(list(self.history.iterdir()),[])
        self.assertEqual(self.transfer_events(),[])

    def test_genesis_wrong_principal_host_source_and_target_refuse_without_registry(self):
        self.prepare()
        locator=self.locator.read_bytes();request=self.proposal.read_bytes()
        original_summary=copy.deepcopy(self.summary)
        for failure in ('principal','host','source','target'):
            with self.subTest(failure=failure):
                self.locator.write_bytes(locator)
                self.proposal.chmod(0o600);self.proposal.write_bytes(request);self.proposal.chmod(0o400)
                self.summary=copy.deepcopy(original_summary)
                if failure in ('principal','host'):
                    options=json.loads(locator)
                    if failure=='principal':options['ssh']['identity_file']=str(self.ssh.root/'other')
                    else:options['ssh']['host_fingerprint']='SHA256:'+'Z'*43
                    self.locator.write_bytes(T.canonical(options))
                else:
                    changed=json.loads(request)
                    if failure=='source':changed['source_sha']='f'*40
                    else:changed['target']['inode']+=1
                    raw=T.canonical(changed)
                    self.proposal.chmod(0o600);self.proposal.write_bytes(raw);self.proposal.chmod(0o400)
                    self.summary['proposal_sha256']=hashlib.sha256(raw).hexdigest()
                self.invoke_cli(success=False)
                self.assertFalse(self.history.exists());self.assertFalse(self.state.exists())
                self.assertFalse(self.completion.exists())
        self.assertFalse(any(x['event']=='LEAF' for x in self.transfer_events()))

    def test_genesis_raw_ssh_cannot_bypass_server_selected_launcher(self):
        self.prepare()
        with T.ssh_command(self.ssh.options()) as argv:
            result=subprocess.run(argv+['python3 -c "raise SystemExit(0)"'],input=b'raw target-bind bypass\n',capture_output=True,timeout=10)
        self.assertEqual(result.returncode,75,result.stderr.decode(errors='replace'))
        self.assertFalse(self.history.exists());self.assertFalse(self.state.exists())
        self.assertFalse(self.completion.exists());self.assertEqual(self.transfer_events(),[])

    def test_genesis_revoked_authority_rejects_before_first_write(self):
        self.prepare();self.fx.catalogue['revoked']=[self.fx.anchor['verifier_identity']];self.fx.save('catalogue')
        self.invoke_cli(success=False);self.assertFalse(self.history.exists())
        self.assertFalse(self.state.exists())

    def test_genesis_copy_only_after_lost_local_completion(self):
        self.prepare()
        # Client completion storage fails after authenticated durable remote success.
        driver=self.root/'outer-bin/python3';text=driver.read_text()
        marker="sys.argv=arguments\n"
        text=text.replace(marker,"original_store=client.new_protected_metadata\ndef unavailable(path,raw):\n    if str(path).endswith('genesis-completion.json'):raise OSError('owned lost local ACK')\n    return original_store(path,raw)\nclient.new_protected_metadata=unavailable\n"+marker)
        driver.write_text(text);self.invoke_cli(success=False)
        self.assertTrue((self.history/'genesis.result.json').exists());self.assertFalse(self.completion.exists())
        before={p.name:p.read_bytes() for p in self.history.iterdir()}
        self.fx.catalogue['actions'][0]['scope']='LEGACY_GENESIS_COPY'
        self.fx.catalogue['legacy_completion_sha256']=hashlib.sha256((self.history/'genesis.result.json').read_bytes()).hexdigest()
        self.fx.save('catalogue');self.outer()
        self.invoke_cli(copy_only=True)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.history.iterdir()})
        self.assertEqual(json.loads(self.completion.read_bytes())['result_sha256'],self.fx.catalogue['legacy_completion_sha256'])


def load_tests(loader,tests,pattern):
    # Parent scenarios run in their own complete suite, not silently counted twice.
    names=[name for name in loader.getTestCaseNames(GenesisSSH) if name.startswith('test_genesis_')]
    return unittest.TestSuite(GenesisSSH(name) for name in names)

if __name__=='__main__':unittest.main()
