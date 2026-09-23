#!/usr/bin/env python3
"""Local INIT metadata/completion and exact native history: synthetic data only.

External execution is denied except the exact local native fixture writer/verifier
commands with operational executables replaced by deny scripts in a child-only PATH.
No fixture is operational authority and no producer or SSH access is performed.
"""
import copy
from contextlib import nullcontext
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('policy_b_client_cases', ROOT / 'v126-policy-b-client.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)


class Client(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='policy-b-client-synthetic-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.state = self.base / 'state'
        self.target = self.base / 'target'; self.target.mkdir(mode=0o700)
        self.document = dict(created_at='2026-09-01T00:00:00Z', format_version=1,
            database_url_file=str(self.base / 'unused-database'), maintenance_identities_file=str(self.base / 'unused-identities'),
            main_actions_run_id=123456, release_parents=['a'*40], release_sha='a'*40,
            release_tree='b'*40, release_worktree=str(ROOT.parent), remote='synthetic-only',
            run_id='synthetic-client-run', script_sha256=c.digest(c.SOURCE.read_bytes()), staging_path=str(self.target),
            v125_image_tag='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',
            v126_image_id='sha256:'+'d'*64, v126_image_tag='synthetic:'+'a'*40)
        self.manifest = c.canonical(self.document)
        self.request = c.init_request(self.state, self.manifest)
        self.real_run, self.real_popen = subprocess.run, subprocess.Popen
        self.real_check_output = subprocess.check_output
        self.allowed = set()
        guard = self.base / 'deny-operational'; guard.mkdir(mode=0o700)
        for name in ('ssh', 'scp', 'rsync', 'curl', 'gh', 'docker', 'psql', 'pg_dump', 'pg_restore', 'sudo', 'systemctl', 'aws', 'yc'):
            path = guard / name; path.write_text('#!/bin/sh\nexit 97\n'); path.chmod(0o500)
        self.safe_env = dict(PATH=str(guard)+os.pathsep+os.environ['PATH'], HOME=str(self.base), LC_ALL='C', PYTHONDONTWRITEBYTECODE='1')
        self.patches = [patch.object(socket, 'socket', side_effect=AssertionError('no sockets')),
                        patch.object(os, 'system', side_effect=AssertionError('no shell')),
                        patch.object(subprocess, 'Popen', self.checked_popen), patch.object(subprocess, 'run', self.checked_run)]
        for item in self.patches: item.start(); self.addCleanup(item.stop)

    def checked_popen(self, command, *args, **kwargs):
        if not isinstance(command, list) or tuple(command) not in self.allowed:
            raise AssertionError('external execution denied: '+repr(command))
        kwargs['env'] = self.safe_env
        return self.real_popen(command, *args, **kwargs)

    def checked_run(self, command, *args, **kwargs):
        if not isinstance(command, list) or tuple(command) not in self.allowed:
            raise AssertionError('external execution denied: '+repr(command))
        kwargs['env'] = self.safe_env
        return self.real_run(command, *args, **kwargs)

    def source_observation(self, argv, *, dirty=False, **kwargs):
        expected = ['git', '--no-optional-locks', '-C', str(ROOT.parent)]
        self.assertEqual(argv[:4], expected)
        query = argv[4:]
        if query == ['rev-parse', 'HEAD']: return (self.document['release_sha']+'\n').encode()
        if query == ['rev-parse', 'HEAD^{tree}']: return (self.document['release_tree']+'\n').encode()
        if query == ['status', '--porcelain', '--untracked-files=all']: return b' M synthetic.py\n' if dirty else b''
        if query == ['show', self.document['release_sha']+':scripts/v126-cutover.sh']: return c.SOURCE.read_bytes()
        raise AssertionError('unlisted Git observation')

    def prepare_proposal(self, *, manifest=None, path=None, dirty=False):
        path = path or self.base/'proposal.json'
        with patch.object(subprocess, 'check_output', lambda args, **kwargs: self.source_observation(args, dirty=dirty, **kwargs)):
            summary = c.prepare_init_proposal(self.state, path, manifest or self.manifest)
        return path, summary

    def test_proposal_preparation_is_create_only_exact_and_has_no_state_authority(self):
        path, summary = self.prepare_proposal()
        raw = path.read_bytes()
        self.assertFalse(self.state.exists())
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o400)
        self.assertEqual(c.strict(raw), dict(format_version=1, state_dir=str(self.state), manifest=self.document))
        self.assertEqual(summary, dict(proposal_sha256=c.digest(raw), manifest_sha256=c.digest(self.manifest),
            request_sha256=c.digest(c.canonical(self.request)), identity=self.request['identity']))
        with patch.object(c.time, 'time', return_value=9999999999):
            self.assertEqual(c.read_init_proposal(self.state, path, summary['proposal_sha256']), self.manifest)
        self.assertEqual(path.read_bytes(), raw)
        before = self.snapshot()
        with self.assertRaises(FileExistsError): self.prepare_proposal()
        self.assertEqual(before, self.snapshot())
        with self.assertRaises(FileNotFoundError): c.require_completion(self.state)

    def test_proposal_drift_and_different_state_are_refused_before_dispatch(self):
        path, summary = self.prepare_proposal()
        changed = c.strict(path.read_bytes()); changed['manifest']['created_at'] = '2026-09-01T00:00:01Z'
        path.chmod(0o600); path.write_bytes(c.canonical(changed)); path.chmod(0o400)
        with self.assertRaisesRegex(ValueError, 'INIT_PROPOSAL_CHANGED'):
            c.read_init_proposal(self.state, path, summary['proposal_sha256'])
        with self.assertRaisesRegex(ValueError, 'INIT_PROPOSAL_BINDING'):
            c.read_init_proposal(self.base/'other-state', path, c.digest(path.read_bytes()))
        changed['manifest']['unexpected'] = True
        path.chmod(0o600); path.write_bytes(c.canonical(changed)); path.chmod(0o400)
        with self.assertRaisesRegex(ValueError, 'INIT_MANIFEST_SCHEMA'):
            c.read_init_proposal(self.state, path, c.digest(path.read_bytes()))
        self.assertFalse(self.state.exists())

    def test_proposal_schema_timestamp_and_source_fail_before_file_creation(self):
        cases = [('created_at', '2026-02-30T00:00:00Z'), ('created_at', '2026-09-01T00:00:00+00:00'),
                 ('format_version', True), ('main_actions_run_id', True), ('release_sha', 'A'*40),
                 ('release_parents', []), ('staging_path', '/some/../target'), ('run_id', 'short')]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                doc = dict(self.document); doc[key] = value
                with self.assertRaises(ValueError): self.prepare_proposal(manifest=c.canonical(doc))
                self.assertFalse((self.base/'proposal.json').exists())
                self.assertFalse(self.state.exists())
        with self.assertRaisesRegex(ValueError, 'EXACT_CLEAN_SOURCE_REQUIRED'): self.prepare_proposal(dirty=True)
        self.assertFalse((self.base/'proposal.json').exists())

    def test_rehashed_proposal_never_replaces_independent_exact_action_authority(self):
        path, summary = self.prepare_proposal()
        fixture = c.module('proposal_authority_fixture', 'test-v126-policy-b-authority.py')
        def observed_git(argv, **kwargs):
            if argv[:1] == ['git'] and argv[-3:] == ['rev-parse', 'HEAD', 'HEAD^{tree}']:
                return (self.document['release_sha']+'\n'+self.document['release_tree']+'\n').encode()
            raise AssertionError('unapproved source observation')
        with patch.object(subprocess, 'check_output', side_effect=observed_git):
            fx = fixture.Fixture(self.base/'independent-authority', 'RUN_INITIALIZED')
        fx.catalogue['actions'][0]['identity'] = self.request['identity']
        fx.save('catalogue')
        with fx.isolated_observations():
            unchanged = c.read_init_proposal(self.state, path, summary['proposal_sha256'])
            fx.verifier.acquire_catalogue('1'*64, c.init_request(self.state, unchanged)['identity'], 'DISPATCH')
            prompts = len(fx.console.prompts)
            # Even a newly hashed internally consistent proposal is not an approval.
            envelope = c.strict(path.read_bytes())
            envelope['manifest']['created_at'] = '2026-09-01T00:00:01Z'
            path.chmod(0o600); path.write_bytes(c.canonical(envelope)); path.chmod(0o400)
            changed = c.read_init_proposal(self.state, path, c.digest(path.read_bytes()))
            with self.assertRaises(ValueError):
                fx.verifier.acquire_catalogue('2'*64, c.init_request(self.state, changed)['identity'], 'DISPATCH')
            self.assertEqual(len(fx.console.prompts), prompts)
        self.assertFalse(self.state.exists())

    def test_proposal_cannot_be_canonical_state_symlink_or_writable_file(self):
        with self.assertRaisesRegex(ValueError, 'INIT_PROPOSAL_PATH'): self.prepare_proposal(path=self.state)
        with self.assertRaisesRegex(ValueError, 'INIT_STATE_PATH'):
            c.init_request(ROOT.parent/'forbidden-state', self.manifest)
        real = self.base/'unrelated'; real.write_bytes(b'untouched'); real.chmod(0o400)
        linked = self.base/'proposal-link'; linked.symlink_to(real)
        with self.assertRaises(FileExistsError): self.prepare_proposal(path=linked)
        self.assertEqual(real.read_bytes(), b'untouched')
        path, summary = self.prepare_proposal()
        path.chmod(0o600)
        with self.assertRaisesRegex(ValueError, 'METADATA_PROTECTION'):
            c.read_init_proposal(self.state, path, summary['proposal_sha256'])
        self.assertFalse(self.state.exists())

    def metadata(self):
        return c.write_metadata(self.state, self.manifest, self.request)

    def completed(self):
        answer = self.metadata()
        result = dict(format_version=1, identity=self.request['identity'], operation_id=answer['operation_id'],
                      exit=0, outcome='SUCCEEDED', completion='LOCAL_METADATA_ATTESTED',
                      request_sha256=answer['request_sha256'], attestation=answer, completed_at='2026-09-22T00:00:00+00:00')
        proof = c.complete_proof(self.request, result)
        c.save_completion(self.state, proof)
        return proof

    def snapshot(self):
        return {str(p.relative_to(self.base)): (stat.S_IMODE(p.lstat().st_mode), p.read_bytes())
                for p in self.base.rglob('*') if p.is_file() and not p.is_symlink()}

    def test_metadata_is_create_only_exact_durable_and_provisional(self):
        attested = self.metadata()
        self.assertEqual(attested['writer'], 'ATTENDED_VERIFIER')
        self.assertTrue(attested['durable'])
        self.assertEqual((self.state/'run.json').read_bytes(), self.manifest)
        self.assertEqual((self.state/'run.json.sha256').read_bytes(), (c.digest(self.manifest)+'\n').encode())
        self.assertEqual({p.name for p in self.state.iterdir()}, set(c.STATE_DIRECTORIES)|{'run.json','run.json.sha256'})
        for directory in (self.state, *(self.state/name for name in c.STATE_DIRECTORIES)):
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((self.state/'run.json').stat().st_mode), 0o400)
        with self.assertRaises(FileNotFoundError): c.require_completion(self.state)
        before = self.snapshot()
        with self.assertRaises(FileExistsError): self.metadata()
        self.assertEqual(before, self.snapshot())

    def test_existing_state_or_symlink_never_adopted(self):
        destination = self.base/'existing'; destination.mkdir(mode=0o700)
        (destination/'untouched').write_bytes(b'owned existing fixture')
        self.state.symlink_to(destination, target_is_directory=True)
        before = self.snapshot()
        with self.assertRaises(FileExistsError): self.metadata()
        self.assertEqual(before, self.snapshot())
        self.assertTrue(self.state.is_symlink())

    def test_request_substitution_refuses_before_canonical_state(self):
        wrong = copy.deepcopy(self.request); wrong['manifest_sha256'] = 'f'*64
        with self.assertRaisesRegex(ValueError,'INIT_REQUEST_MISMATCH'):
            c.write_metadata(self.state, self.manifest, wrong)
        self.assertFalse(self.state.exists())

    def test_partial_fsync_failure_preserves_unconfirmed_state_and_denies_retry(self):
        original = os.fsync; calls = []
        def fsync(fd):
            calls.append(fd)
            if len(calls) == 3: raise OSError('synthetic fsync failure')
            return original(fd)
        with patch.object(os,'fsync',fsync):
            with self.assertRaises(OSError): self.metadata()
        self.assertTrue(self.state.exists())
        self.assertFalse((self.state/c.COMPLETION_FILE).exists())
        with self.assertRaises((OSError,ValueError)): c.require_completion(self.state)
        before = self.snapshot()
        with self.assertRaises(FileExistsError): self.metadata()
        self.assertEqual(before,self.snapshot())

    def test_short_writes_are_completed_but_zero_write_refuses(self):
        real_write = os.write
        with patch.object(os,'write',lambda fd,raw: real_write(fd,raw[:7])): self.metadata()
        self.assertEqual((self.state/'run.json').read_bytes(),self.manifest)
        other = self.base/'zero-write'
        request = c.init_request(other,self.manifest)
        with patch.object(os,'write',return_value=0):
            with self.assertRaisesRegex(ValueError,'INIT_SHORT_WRITE'): c.write_metadata(other,self.manifest,request)
        self.assertTrue(other.exists()); self.assertFalse((other/c.COMPLETION_FILE).exists())

    def test_completion_save_is_create_only_and_exact(self):
        proof = self.completed()
        self.assertEqual(c.require_completion(self.state),proof)
        before = self.snapshot()
        with self.assertRaises(FileExistsError): c.save_completion(self.state,proof)
        self.assertEqual(before,self.snapshot())

    def test_forged_or_rehashed_completion_does_not_rebind_manifest(self):
        proof = self.completed()
        for field in ('request_sha256','result_sha256'):
            changed = copy.deepcopy(proof); changed[field]='0'*64
            with self.assertRaisesRegex(ValueError,'INIT_COMPLETION_DIGEST'): c.validate_completion(self.state,changed)
        changed=copy.deepcopy(proof);changed['request']['local_state_sha256']='0'*64
        changed['request_sha256']=c.digest(c.canonical(changed['request']))
        with self.assertRaisesRegex(ValueError,'INIT_COMPLETION_BINDING'):c.validate_completion(self.state,changed)
        changed=copy.deepcopy(proof);changed['result']['attestation']['durable']=False
        changed['result_sha256']=c.digest(c.canonical(changed['result']))
        with self.assertRaises(ValueError):c.validate_completion(self.state,changed)

    def test_completion_does_not_survive_missing_or_corrupt_manifest_checksum(self):
        self.completed(); path=self.state/'run.json.sha256'; raw=path.read_bytes()
        path.unlink()
        with self.assertRaises((OSError,ValueError)):c.require_completion(self.state)
        path.write_bytes(('0'*64+'\n').encode());path.chmod(0o400)
        with self.assertRaises((OSError,ValueError)):c.require_completion(self.state)
        path.unlink();path.write_bytes(raw);path.chmod(0o400)
        self.assertTrue(c.require_completion(self.state))

    def test_completion_requires_exact_local_directory_surface(self):
        self.completed()
        for name in c.STATE_DIRECTORIES:
            path=self.state/name; path.rmdir()
            with self.subTest(missing=name),self.assertRaises((OSError,ValueError)):c.require_completion(self.state)
            path.mkdir(mode=0o700)
        self.state.chmod(0o755)
        with self.assertRaises((OSError,ValueError)):c.require_completion(self.state)

    def test_protected_metadata_rejects_hardlink_symlink_mode_and_noncanonical_json(self):
        self.completed();path=self.state/c.COMPLETION_FILE
        link=self.base/'hardlink';os.link(path,link)
        with self.assertRaisesRegex(ValueError,'METADATA_PROTECTION'):c.require_completion(self.state)
        link.unlink();path.chmod(0o600)
        with self.assertRaisesRegex(ValueError,'METADATA_PROTECTION'):c.require_completion(self.state)
        path.chmod(0o400)
        for raw in [b'{"x":1,"x":2}\n',b'{"x":NaN}\n',b'{ "x":1}\n',b'']:
            with self.assertRaises(ValueError):c.strict(raw)


    def native_fixture(self):
        self.completed()
        command = ['bash','-c',
            'set -Eeuo pipefail; source "$1"; '
            'eval "$(declare -f stage_artifacts_oracle | sed s/stage_artifacts_oracle/original_stage_artifacts_oracle/)"; '
            'stage_artifacts_oracle() { if [[ "$1" == BASELINE_VERIFIED ]]; then '
            'printf "%s\\n" "local-baseline,main-actions,baseline-caddy,baseline-env,database-target-identity,database-url-binding,maintenance-identities,remote-compose-source,remote-maintenance-check-source,remote-admission-source,staging-baseline"; '
            'else original_stage_artifacts_oracle "$1"; fi; }; seed_chain "$2" 7',
            'synthetic-client-native-seed',str(ROOT/'test-v126-cutover.sh'),str(self.state)]
        self.allowed.add(tuple(command))
        subprocess.run(command,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=30)
        self.context = SimpleNamespace(run_id=self.document['run_id'],release_sha=self.document['release_sha'],
            script_sha256=self.document['script_sha256'],target=str(self.target))
        stages = ('BASELINE_VERIFIED','PRE_DRAIN_BACKUP_REHEARSED','CADDY_CANDIDATE_INSTALLED_AND_RELOADED',
                  'PUBLIC_DRAIN_ACTIVE','V125_BACKEND_STOPPED','ZERO_WRITER_GATE_PASSED','QUIESCED_BACKUP_REHEARSED')
        actions = ('baseline','backup-rehearsal','caddy-activate','public-drain-on','stop-backend','zero-writer','backup-rehearsal')
        root=self.target/'.v126-target-operations';root.mkdir(mode=0o700)
        bindings=c.module('v126_client_bindings','v126-operation-bindings.py')
        self.native_paths=[]
        for index,(stage,action) in enumerate(zip(stages,actions),1):
            path=self.state/'receipts'/f'{index:02d}-{stage}.receipt.json'
            native=c.strict(path.read_bytes()); self.native_paths.append(path)
            identity=dict(run_id=self.context.run_id,release_sha=self.context.release_sha,
                script_sha256=self.context.script_sha256,intent_sha256=native['intent_sha256'],kind='STAGE',name=stage,action=action)
            operation=c.digest(c.canonical(identity))
            log=(self.state/'artifacts'/f'{index}-{stage}.operation.log').read_bytes()
            if index==1:
                self.assertTrue(log.startswith(b'ARTIFACT\tlocal-baseline\t'))
                log=b'\n'.join(log.split(b'\n')[2:])
            request=dict(format_version=1,identity=identity,target_sha256=c.digest(str(self.target).encode()),
                args=[str(self.target),self.context.run_id,self.context.release_sha],environment={})
            result=dict(identity=identity,operation_id=operation,exit=0,outcome='SUCCEEDED',children='REAPED',
                log_sha256=c.digest(log),completed_at='2026-09-01T00:00:00+00:00')
            start=dict(identity=identity,operation_id=operation,started_at='2026-09-01T00:00:00+00:00',boot_id='synthetic')
            for suffix,document in [('start',start),('request',request),('result',result)]:
                bindings.binding_create(root/(operation+'.'+suffix+'.json'),document)
            bindings.binding_create_raw(root/(operation+'.log'),log)
        owner={key:getattr(self.context,key) for key in ('run_id','release_sha','script_sha256')}
        self.history=c.native_history_snapshot(self.target,owner)
        command=['bash','-c','source "$1"; load_state "$2"; verify_receipt QUIESCED_BACKUP_REHEARSED >/dev/null',
                 'v126-native-verifier',str(c.SOURCE),str(self.state)]
        self.allowed.add(tuple(command))
        return self.history

    def test_actual_native_verifier_and_remote_inventory_bind_all_seven_stages(self):
        history=self.native_fixture()
        native=c.verify_native_stage7(self.state,self.context,history)
        self.assertEqual(native.receipt,self.native_paths[-1].read_bytes())
        self.assertEqual(native.manifest_sha256,c.digest(self.manifest))
        self.assertEqual(len(history),7)

    def test_native_incomplete_chain_refuses_before_remote_comparison(self):
        history=self.native_fixture(); self.native_paths[-2].unlink()
        with self.assertRaises(subprocess.CalledProcessError):c.verify_native_stage7(self.state,self.context,history)

    def test_native_missing_dump_inventory_or_rehearsal_is_not_a_complete_q(self):
        history=self.native_fixture()
        for name in ('quiesced-backup-dump','quiesced-backup-inventory','quiesced-backup-rehearsal'):
            changed=copy.deepcopy(history)
            changed[-1]['artifacts']=[item for item in changed[-1]['artifacts'] if item['name']!=name]
            with self.subTest(missing=name),self.assertRaisesRegex(ValueError,'NATIVE_REMOTE_ARTIFACT_SET'):
                c.verify_native_stage7(self.state,self.context,changed)

    def test_native_remote_run_source_intent_artifact_and_log_binding(self):
        history=self.native_fixture()
        for key,value in [('run_id','another-run'),('release_sha','f'*40),('script_sha256','f'*64),
                          ('intent_sha256','f'*64),('action','forged-action')]:
            changed=copy.deepcopy(history);changed[-1]['identity'][key]=value
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'NATIVE_REMOTE_IDENTITY'):
                c.compare_native_history(self.state,self.context,changed)
        changed=copy.deepcopy(history);changed[-1]['log_sha256']='f'*64
        with self.assertRaisesRegex(ValueError,'NATIVE_REMOTE_LOG_BINDING'):c.compare_native_history(self.state,self.context,changed)
        changed=copy.deepcopy(history);changed[-1]['artifacts'].append(changed[-1]['artifacts'][0])
        with self.assertRaisesRegex(ValueError,'NATIVE_REMOTE_ARTIFACT_MISMATCH'):c.compare_native_history(self.state,self.context,changed)
        changed=copy.deepcopy(history);changed[-1]['completed_at']='2026-09-01T00:00:03+00:00'
        with self.assertRaisesRegex(ValueError,'NATIVE_REMOTE_CHRONOLOGY'):c.compare_native_history(self.state,self.context,changed)

    def test_native_rehashed_local_receipt_cannot_skip_existing_verifier(self):
        history=self.native_fixture()
        path=self.native_paths[-1];document=c.strict(path.read_bytes());document['predecessor_receipt_sha256']='f'*64
        raw=c.canonical(document);path.chmod(0o600);path.write_bytes(raw);path.chmod(0o400)
        checksum=Path(str(path)+'.sha256');checksum.chmod(0o600);checksum.write_text(c.digest(raw)+'\n');checksum.chmod(0o400)
        with self.assertRaises(subprocess.CalledProcessError):c.verify_native_stage7(self.state,self.context,history)


    def client_flow(self, command, invoke, *, authorization=None, refuse_authority=False, dirty=False, lock=True):
        """Synthetic external enrollment/SSH replies; actual source and writer consumers."""
        calls=[]
        lock_command=['bash','-c','source "$1"; validate_state_lock_surface "$2"','v126-local-lock',str(c.SOURCE),str(self.state/'.exclusive-lock')]
        self.allowed.add(tuple(lock_command))
        if command in ('complete-init-copy','operation') and lock and not (self.state/'.exclusive-lock').exists():
            owned=self.state/'.exclusive-lock';owned.mkdir(mode=0o700);(owned/'children').mkdir(mode=0o700)
            (owned/'pid').write_text(str(os.getpid())+'\n');(owned/'pid').chmod(0o400)
        enrollment=SimpleNamespace(sha256='a'*64,document=dict(generation=1,principal_fingerprint='synthetic-principal',
            host_fingerprint='synthetic-host',source_tree=self.document['release_tree'],tooling_sha256='b'*64,python_version=sys.version.split()[0]))
        manifest=self.manifest
        class Verifier:
            def now(self):
                calls.append(('clock_recheck',))
                return dict(utc='2026-09-22T00:00:00Z',error_seconds=1,monotonic_seconds=100,clock_id='synthetic')
            def recheck_sources(self):
                calls.append(('source_recheck',))
                c.verify_source(manifest)
            def __init__(self,authority,native):
                self.authority=authority;self.native=native
            def authorize_action(self,identity,target,scope):
                calls.append(('authorize',scope,identity,target))
                if refuse_authority:raise ValueError('synthetic independent authority refusal')
                return dict(expiry=120)
        authority=SimpleNamespace(load_enrolled_authority=lambda path:enrollment,VVerifier=Verifier)
        transport=SimpleNamespace(ssh_command=lambda options: nullcontext(['synthetic-unused']),invoke_operation=invoke)
        config=self.base/'transport.json';config.write_bytes(c.canonical(dict(remote_alias=self.document['remote'],ssh=dict(host_fingerprint='synthetic-host'))));config.chmod(0o600)
        real_module=c.module
        def modules(name,filename):
            if filename=='v126-policy-b-authority.py':return authority
            if filename=='v126-policy-b-transport.py':return transport
            return real_module(name,filename)
        def git(argv,**kwargs):
            if argv==lock_command:return self.real_check_output(argv,**kwargs)
            return self.source_observation(argv, dirty=dirty, **kwargs)
        args=SimpleNamespace(command=command,state_dir=str(self.state),policy_b_anchor=str(self.base/'enrolled-anchor'),
                             policy_b_transport=str(config),authorization=authorization,stream_file=str(self.base/'unused-stream'))
        with patch.object(c,'module',modules),patch.object(subprocess,'check_output',git),patch.object(sys,'stdin',SimpleNamespace(buffer=io.BytesIO(b'not a regenerated manifest'))):
            if command == 'init':
                proposal = self.base/'client-proposal.json'
                if not proposal.exists(): c.prepare_init_proposal(self.state, proposal, self.manifest)
                args.proposal_file = str(proposal)
                args.proposal_sha256 = c.digest(proposal.read_bytes())
            result=c.run_client(args)
        return result,calls

    def test_actual_init_client_writes_only_once_after_protocol_dispatch_and_ack(self):
        observed=[]
        def invoke(options,opened,source,verifier,**callbacks):
            self.assertFalse(self.state.exists());observed.append(opened)
            answer=callbacks['init_writer'](dict(request=self.request))
            result=dict(format_version=1,identity=self.request['identity'],operation_id=answer['operation_id'],exit=0,
                outcome='SUCCEEDED',completion='LOCAL_METADATA_ATTESTED',request_sha256=answer['request_sha256'],
                attestation=answer,completed_at='2026-09-22T00:00:00+00:00')
            callbacks['completion_writer'](dict(result=result))
            return dict(status=0,result=result)
        result,calls=self.client_flow('init',invoke)
        self.assertEqual(result,0);self.assertEqual(calls[0][1],'DISPATCH')
        self.assertEqual(observed[0]['mode'],'EXECUTE');self.assertEqual(c.require_completion(self.state)['request'],self.request)

    def test_actual_init_lost_ack_is_provisional_without_retry_or_false_completion(self):
        def invoke(options,opened,source,verifier,**callbacks):
            callbacks['init_writer'](dict(request=self.request))
            return dict(status=0,result=None)
        with self.assertRaisesRegex(ValueError,'INIT_COMPLETION_UNCONFIRMED'):self.client_flow('init',invoke)
        self.assertTrue((self.state/'run.json').exists());self.assertFalse((self.state/c.COMPLETION_FILE).exists())
        before=self.snapshot()
        with self.assertRaisesRegex(ValueError,'INIT_STATE_EXISTS'):self.client_flow('init',invoke)
        self.assertEqual(before,self.snapshot())

    def test_actual_init_refused_authority_and_dirty_source_never_reach_transport(self):
        def forbidden(*args,**kwargs):raise AssertionError('unauthorized transport dispatch')
        with self.assertRaisesRegex(ValueError,'authority refusal'):self.client_flow('init',forbidden,refuse_authority=True)
        self.assertFalse(self.state.exists())
        with self.assertRaisesRegex(ValueError,'EXACT_CLEAN_SOURCE_REQUIRED'):self.client_flow('init',forbidden,dirty=True)
        self.assertFalse(self.state.exists())

    def test_actual_init_duplicate_or_substituted_write_cannot_create_success(self):
        def duplicate(options,opened,source,verifier,**callbacks):
            callbacks['init_writer'](dict(request=self.request))
            callbacks['init_writer'](dict(request=self.request))
            raise AssertionError('duplicate writer accepted')
        with self.assertRaises(FileExistsError):self.client_flow('init',duplicate)
        self.assertFalse((self.state/c.COMPLETION_FILE).exists())

    def lost_ack_proof(self):
        answer=self.metadata()
        result=dict(format_version=1,identity=self.request['identity'],operation_id=answer['operation_id'],exit=0,
                    outcome='SUCCEEDED',completion='LOCAL_METADATA_ATTESTED',request_sha256=answer['request_sha256'],
                    attestation=answer,completed_at='2026-09-22T00:00:00+00:00')
        return c.complete_proof(self.request,result)

    def test_copy_only_lost_ack_requires_exact_existing_result_and_separate_authority(self):
        proof=self.lost_ack_proof();before=(self.state/'run.json').read_bytes();observed=[]
        def invoke(options,opened,source,verifier,**callbacks):
            self.assertEqual(callbacks,{})
            self.assertEqual(opened['mode'],'INIT_READBACK');observed.append(opened)
            return dict(status=0,result=proof)
        with patch.object(c,'write_metadata',side_effect=AssertionError('copy attempted metadata writer')):
            result,calls=self.client_flow('complete-init-copy',invoke,authorization=c.COPY_TOKEN)
        self.assertEqual(result,0);self.assertEqual(calls[0][1],'COPY_ONLY');self.assertEqual(len(observed),1)
        self.assertEqual((self.state/'run.json').read_bytes(),before);self.assertEqual(c.require_completion(self.state),proof)

    def test_copy_only_wrong_authority_or_unknown_remote_preserves_provisional(self):
        self.lost_ack_proof()
        def forbidden(*args,**kwargs):raise AssertionError('copy transport without separate authorization')
        with self.assertRaisesRegex(ValueError,'INIT_COPY_AUTHORITY_REQUIRED'):
            self.client_flow('complete-init-copy',forbidden,authorization='WRONG')
        def unknown(*args,**kwargs):return dict(status=75,result=None)
        with self.assertRaisesRegex(ValueError,'INIT_READBACK_UNCONFIRMED'):
            self.client_flow('complete-init-copy',unknown,authorization=c.COPY_TOKEN)
        self.assertFalse((self.state/c.COMPLETION_FILE).exists())

    def test_operation_without_completion_never_reaches_transport(self):
        self.metadata()
        def forbidden(*args,**kwargs):raise AssertionError('provisional state used for stage')
        with self.assertRaises(FileNotFoundError):self.client_flow('operation',forbidden)
        self.assertFalse((self.state/c.COMPLETION_FILE).exists())


    def test_copy_requires_existing_owned_native_state_lock(self):
        self.lost_ack_proof()
        def forbidden(*args,**kwargs):raise AssertionError('copy without existing local lock reached transport')
        with self.assertRaises(subprocess.CalledProcessError):
            self.client_flow('complete-init-copy',forbidden,authorization=c.COPY_TOKEN,lock=False)
        self.assertFalse((self.state/c.COMPLETION_FILE).exists())

    def test_full_native_verifier_removal_is_detected_by_real_chain_regression(self):
        import ast
        source=(ROOT/'v126-policy-b-client.py').read_text()
        function=next(node for node in ast.parse(source).body if isinstance(node,ast.FunctionDef) and node.name=='verify_native_stage7')
        calls=[node for node in ast.walk(function) if isinstance(node,ast.Expr) and isinstance(node.value,ast.Call)
               and isinstance(node.value.func,ast.Attribute) and isinstance(node.value.func.value,ast.Name)
               and node.value.func.value.id=='subprocess' and node.value.func.attr=='run']
        self.assertEqual(len(calls),1)
        call=calls[0];lines=source.splitlines(keepends=True)
        lines[call.lineno-1:call.end_lineno]=[' '*call.col_offset+'pass  # isolated negative control\n']
        path=self.base/'client-native-mutant.py';path.write_text(''.join(lines))
        spec=importlib.util.spec_from_file_location('client_native_mutant',path)
        mutant=importlib.util.module_from_spec(spec);spec.loader.exec_module(mutant)
        mutant.ROOT=c.ROOT;mutant.SOURCE=c.SOURCE
        original=globals()['c'];result=unittest.TestResult()
        try:
            globals()['c']=mutant
            Client('test_native_rehashed_local_receipt_cannot_skip_existing_verifier').run(result)
        finally:globals()['c']=original
        self.assertEqual(result.testsRun,1)
        self.assertEqual(len(result.failures),1,'skipping actual verify_receipt did not fail the expected regression')
        self.assertEqual(result.errors,[])


    def test_copy_late_authority_expiry_does_not_create_completion(self):
        import time
        proof=self.lost_ack_proof();now=[100.0]
        def invoke(*args,**kwargs):
            now[0]=240.0
            return dict(status=0,result=proof)
        with patch.object(time,'monotonic',lambda:now[0]):
            with self.assertRaisesRegex(ValueError,'INIT_COPY_AUTHORITY_EXPIRED'):
                self.client_flow('complete-init-copy',invoke,authorization=c.COPY_TOKEN)
        self.assertFalse((self.state/c.COMPLETION_FILE).exists())


if __name__=='__main__':unittest.main(verbosity=2)
