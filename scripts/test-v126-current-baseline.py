#!/usr/bin/env python3
"""Current accepted V125 baseline guards; synthetic offline inputs, never authority."""
import ast
import base64
import copy
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import shlex
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from test_staging_release import fixture


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT/filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def fixture_method(filename, classname, method, namespace):
    # Execute only fixture preparation/serialization, never import or relax the
    # Linux/root/owned-SSH entrypoint guards for a portable test.
    tree = ast.parse((ROOT/filename).read_text())
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == classname)
    node = next(node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == method)
    exec(compile(ast.Module(body=[node], type_ignores=[]), filename, 'exec'), namespace)
    return namespace[method]


b = module('baseline_bindings', 'v126-operation-bindings.py')
c = module('baseline_client', 'v126-policy-b-client.py')
d = module('baseline_database_evidence', 'v126-database-evidence.py')


def accepted_documents(target):
    data = fixture()
    scope = data['scope']
    scope.update(directory=str(target), compose_file=str(Path(target)/'docker-compose.yml'))
    registered = dict(target='synthetic-only', transition_approved=True, scope=scope)
    state = dict(scope=hashlib.sha256(json.dumps(scope, sort_keys=True).encode()).hexdigest(),
                 status='ACCEPTED', accepted=data['current'], operation=None, reason=None)
    return registered, state


class CurrentBaseline(unittest.TestCase):
    def test_genesis_prepares_current_init_before_snapshotting_authority(self):
        namespace = dict(json=json, hashlib=hashlib, T=SimpleNamespace(canonical=c.canonical))
        builder = fixture_method('test-v126-policy-b-linux.py', 'LinuxCaller', 'current_baseline_fixture', namespace)
        prepare = fixture_method('test-v126-genesis-linux.py', 'GenesisSSH', 'prepare_current_init', namespace)
        setup = ast.parse((ROOT/'test-v126-genesis-linux.py').read_text())
        cls = next(n for n in setup.body if isinstance(n, ast.ClassDef) and n.name == 'GenesisSSH')
        setup = next(n for n in cls.body if isinstance(n, ast.FunctionDef) and n.name == 'setUp')
        call = next(n for n in ast.walk(setup) if isinstance(n, ast.Call)
                    and isinstance(n.func, ast.Attribute) and n.func.attr == 'prepare_current_init')
        snapshot = next(n for n in setup.body if isinstance(n, ast.Assign)
                        and any(isinstance(t, ast.Attribute) and t.attr == 'original' for t in n.targets))
        self.assertLess(call.lineno, snapshot.lineno)
        runtime = dict(source_sha='a'*40, image_sha256='a'*64)
        anchor = dict(data_runtime=runtime, restore_runtime=copy.deepcopy(runtime))
        saved = []
        fx = SimpleNamespace(target=self.target, anchor=anchor,
                             catalogue={'actions':[{'identity':{'obsolete':True}}]}, save=saved.append)
        legacy = dict(self.manifest, format_version=1,
                      v125_image_tag='synthetic:f577934691a1a7a79ba327c54e2055425142b7be')
        legacy.pop('v125_baseline')
        fixture = SimpleNamespace(root=self.root, fx=fx, bindings=b, client=c,
                                  state=self.root/'genesis-state', manifest=c.canonical(legacy))
        fixture.current_baseline_fixture = lambda: builder(fixture)
        before = copy.deepcopy(anchor)
        prepare(fixture)
        document = c.validate_init_manifest(fixture.manifest)
        self.assertEqual(document['format_version'], 3)
        self.assertEqual(document['v125_image_tag'], document['v125_baseline']['image_id'])
        self.assertEqual(fixture.request, c.init_request(fixture.state, fixture.manifest))
        self.assertEqual(fixture.request['manifest_sha256'], c.digest(fixture.manifest))
        self.assertEqual(fx.identity, fixture.request['identity'])
        self.assertEqual(fx.catalogue['actions'][0]['identity'], fx.identity)
        self.assertEqual(saved, ['catalogue'])
        self.assertEqual(anchor, before)
        c.validate_baseline_recovery_runtime(fixture.manifest, SimpleNamespace(document=anchor))
        with patch.object(c, 'verify_source'):
            c.prepare_init_proposal(fixture.state, self.root/'genesis-proposal', fixture.manifest)
            with self.assertRaisesRegex(ValueError, 'CURRENT_ACCEPTED_V125_BASELINE_REQUIRED'):
                c.prepare_init_proposal(fixture.state, self.root/'legacy-proposal', c.canonical(legacy))
        self.assertFalse(fixture.state.exists())

    def test_linux_baseline_carries_locked_init_baseline(self):
        captured = []
        def opened(*args, **kwargs):
            captured.append(kwargs['environment'])
            return {}
        def invoke(*args, **kwargs):
            kwargs['output_sink'](b'CAPTURED_ACTION\tbaseline\nREMOTE_OPERATION_ACK')
            return {'status':0}
        namespace = dict(json=json, base64=base64,
                         T=SimpleNamespace(canonical=c.canonical, invoke_operation=invoke))
        baseline = fixture_method('test-v126-policy-b-linux.py', 'LinuxCaller', 'baseline', namespace)
        fixture = SimpleNamespace(owner=dict(run_id='fixture',release_sha='c'*40,script_sha256='d'*64),
            fx=SimpleNamespace(target=self.target,catalogue={'actions':[{}]},save=lambda name:None,enrollment=None),
            client=SimpleNamespace(ENVELOPE_NAMES=c.ENVELOPE_NAMES,make_open=opened),
            ssh=SimpleNamespace(options=lambda:{},source=b''), state=self.root/'state',
            acks=[{}],verifier=lambda:None,assertEqual=self.assertEqual,assertIn=self.assertIn)
        for current in (True, False):
            document = dict(self.manifest)
            if not current: document.pop('v125_baseline')
            fixture.manifest = c.canonical(document)
            baseline(fixture)
            env = captured[-1]
            self.assertEqual(env.get('V126_INTERNAL_REMOTE_V125_BASELINE_B64'),self.encoded if current else None)
            # Direct STAGE receives the normalized internal environment, not wire magic.
            args = [str(self.target), fixture.owner['run_id'], fixture.owner['release_sha']]
            source = c.SOURCE.read_bytes()
            lines = ['V126_INTERNAL_REMOTE_ENVELOPE_V3' if current else 'V126_INTERNAL_REMOTE_ENVELOPE_V1',
                     *[env['V126_INTERNAL_REMOTE_'+key] for key in c.ENVELOPE_NAMES]]
            if current: lines.append(self.encoded)
            stream = self.root/'baseline-stream'
            stream.write_bytes(b'ignored loader\n'+('\n'.join(lines+['3', *args])+'\n').encode()+source)
            _, decoded_args, normalized, _ = c.read_local_stream(stream, source)
            self.assertEqual(decoded_args, args)
            self.assertEqual(env, normalized)
            command = ['bash', '-c', 'source "$1"; shift; remote_validate_envelope "$@"',
                       'baseline-envelope-test', str(c.SOURCE), 'baseline', *args]
            def validate(environment):
                return subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True, timeout=10,
                    env=dict(PATH=os.environ['PATH'], LC_ALL='C', PYTHONDONTWRITEBYTECODE='1', **environment))
            result = validate(env)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            # The real source must continue rejecting wire V3 as an internal marker.
            wrong = dict(env, V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED='V126_INTERNAL_REMOTE_ENVELOPE_V3')
            result = validate(wrong)
            self.assertEqual(result.returncode, 4)
            self.assertIn(b'invalid internal remote envelope', result.stderr)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='v126-baseline-synthetic-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.target = self.root/'target'
        self.target.mkdir(mode=0o700)
        self.registered, self.state = accepted_documents(self.target)
        self.baseline = self.select()
        self.encoded = base64.b64encode(b.binding_canonical(self.baseline)).decode()
        self.manifest = dict(created_at='2026-10-15T00:00:00Z', format_version=3,
            database_url_file=str(self.root/'unused-db'), maintenance_identities_file=str(self.root/'unused-identities'),
            main_actions_run_id=565, release_parents=['a'*40], release_sha='c'*40, release_tree='d'*40,
            release_worktree=str(ROOT.parent), remote='synthetic-only', run_id='synthetic-baseline',
            script_sha256=c.digest(c.SOURCE.read_bytes()), staging_path=str(self.target),
            v125_image_tag=self.baseline['image_id'], v125_baseline=self.baseline,
            v126_image_id='sha256:'+'c'*64, v126_image_tag='synthetic:'+'c'*40)

    def select(self, target=None):
        return b.binding_v125_baseline_documents(json.dumps(self.registered).encode(),
            json.dumps(self.state).encode(), target or self.target)

    def shell(self, body, *, baseline=True):
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
        if baseline: env['V126_INTERNAL_REMOTE_V125_BASELINE_B64'] = self.encoded
        else: env.pop('V126_INTERNAL_REMOTE_V125_BASELINE_B64', None)
        return subprocess.run(['bash', '-c', 'source "$1"; '+body, 'baseline-test', str(c.SOURCE)],
            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)

    def test_accepted_current_source_and_image_are_exact_and_change_with_state(self):
        self.assertEqual(self.baseline['source_sha'], 'a'*40)
        self.assertEqual(self.baseline['image_id'], 'sha256:'+'a'*64)
        self.assertEqual(self.baseline['state_sha256'], hashlib.sha256(json.dumps(self.state).encode()).hexdigest())
        self.state['accepted'].update(revision='b'*40, ci_sha='b'*40, id='sha256:'+'b'*64)
        self.assertNotEqual(self.select(), self.baseline)

    def test_unaccepted_unknown_stale_revoked_foreign_or_wrong_schema_refuse(self):
        cases = [lambda: self.state.update(status='UNKNOWN'), lambda: self.state.update(status='APPLYING'),
            lambda: self.state.update(status='READY_FOR_SMOKE'), lambda: self.state.update(reason='UNKNOWN'),
            lambda: self.state.update(scope='0'*64), lambda: self.registered.update(transition_approved=False),
            lambda: self.registered['scope'].update(postgresql_head=126),
            lambda: self.state['accepted'].update(approved=False),
            lambda: self.state['accepted'].update(ci_sha='d'*40),
            lambda: self.state['accepted'].update(migration_fingerprint='f'*64),
            lambda: self.state['accepted'].update(postgresql_head=126),
            lambda: self.state['accepted'].update(available=False),
            lambda: self.state.update(operation=dict(to=self.state['accepted'] | {'id':'sha256:'+'b'*64}))]
        for mutate in cases:
            with self.subTest(mutate=mutate):
                self.registered, self.state = accepted_documents(self.target)
                mutate()
                with self.assertRaises(b.BindingError): self.select()
        self.registered, self.state = accepted_documents(self.target)
        with self.assertRaises(b.BindingError): self.select(self.root/'different-target')
        raw = json.dumps(self.state).encode().replace(b'"status": "ACCEPTED"', b'"status":"UNKNOWN","status":"ACCEPTED"')
        with self.assertRaises(b.BindingError):
            b.binding_v125_baseline_documents(json.dumps(self.registered).encode(), raw, self.target)

    def test_runtime_image_source_platform_and_database_must_match(self):
        image = dict(Id=self.baseline['image_id'], Os='linux', Architecture='amd64',
                     Config=dict(Labels={'org.opencontainers.image.revision':'a'*40,
                        'org.opencontainers.image.source':'https://github.com/koteev-m/hookah_bot'}))
        db = self.baseline['database']
        postgres = dict(Id=db['container_id'], State=dict(Running=True),
                        NetworkSettings=dict(Networks={db['network']:{}}))
        database = [db['system_id'], db['database'], db['user']]
        b.binding_v125_runtime(self.baseline, image, postgres, database)
        for changed in [image | {'Id':'sha256:'+'b'*64}, image | {'Architecture':'arm64'},
                        image | {'Config':{'Labels':{'org.opencontainers.image.revision':'f'*40}}}]:
            with self.assertRaises(b.BindingError): b.binding_v125_runtime(self.baseline, changed, postgres, database)
        for changed in [postgres | {'Id':'e'*64}, postgres | {'State':{'Running':False}},
                        postgres | {'NetworkSettings':{'Networks':{}}}]:
            with self.assertRaises(b.BindingError): b.binding_v125_runtime(self.baseline, image, changed, database)
        for index in range(3):
            changed = list(database); changed[index] += '-foreign'
            with self.assertRaises(b.BindingError): b.binding_v125_runtime(self.baseline, image, postgres, changed)

    def test_legacy_manifest_readable_but_new_proposal_and_direct_init_reject_it(self):
        legacy = dict(self.manifest, format_version=1,
                      v125_image_tag='synthetic:f577934691a1a7a79ba327c54e2055425142b7be')
        legacy.pop('v125_baseline')
        raw = c.canonical(legacy)
        c.validate_init_manifest(raw)
        with self.assertRaisesRegex(ValueError, 'CURRENT_ACCEPTED_V125_BASELINE_REQUIRED'):
            c.prepare_init_proposal(self.root/'legacy-state', self.root/'proposal', raw)
        run = subprocess.run([sys.executable, '-B', str(ROOT/'v126-policy-b-client.py'), 'init',
            '--state-dir', str(self.root/'legacy-state'), '--policy-b-anchor', str(self.root/'no-anchor'),
            '--policy-b-transport', str(self.root/'no-transport')], input=raw, capture_output=True, timeout=10)
        self.assertNotEqual(run.returncode, 0)
        self.assertEqual(run.stderr, b'POLICY_B_CLIENT_REFUSED outcome_unconfirmed=true retry_allowed=false\n')
        self.assertFalse((self.root/'legacy-state').exists())

    def test_manifest_cannot_substitute_unapproved_rollback_image(self):
        c.validate_init_manifest(c.canonical(self.manifest))
        for changed in [self.manifest | {'v125_image_tag':'sha256:'+'b'*64},
                        self.manifest | {'v126_image_id':self.baseline['image_id']},
                        self.manifest | {'format_version':1}]:
            with self.assertRaises(ValueError): c.validate_init_manifest(c.canonical(changed))

    def test_independent_r0_q_data_and_restore_runtime_match_current_baseline(self):
        runtime=dict(source_sha=self.baseline['source_sha'],image_sha256=self.baseline['image_id'][7:])
        enrollment=SimpleNamespace(document=dict(data_runtime=copy.deepcopy(runtime),restore_runtime=copy.deepcopy(runtime)))
        c.validate_baseline_recovery_runtime(c.canonical(self.manifest),enrollment)
        for key in ('data_runtime','restore_runtime'):
            for field,value in [('source_sha','f577934691a1a7a79ba327c54e2055425142b7be'),
                                ('image_sha256','b'*64)]:
                changed=copy.deepcopy(enrollment);changed.document[key][field]=value
                self.assertEqual(changed.document['restore_runtime' if key=='data_runtime' else 'data_runtime'],runtime)
                with self.subTest(runtime=key,field=field),self.assertRaisesRegex(ValueError,'ACCEPTED_V125_RECOVERY_RUNTIME_MISMATCH'):
                    c.validate_baseline_recovery_runtime(c.canonical(self.manifest),changed)

    def test_legacy_recovery_without_init_retains_its_separate_authority(self):
        identity=dict(run_id='historical-only',release_sha='a'*40,script_sha256='b'*64,
                      intent_sha256='c'*64,kind='RECOVERY',name='pre-v126',action='recover-pre-v126')
        b.binding_operation_baseline(self.target,self.target,identity,[],None)
        with self.assertRaisesRegex(b.BindingError,'operation_baseline_requires_exact_init'):
            b.binding_operation_baseline(self.target,self.target,identity,[],
                dict(environment={'V126_INTERNAL_REMOTE_V125_BASELINE_B64':self.encoded}))

    def test_locked_init_baseline_blocks_omission_substitution_and_legacy_downgrade(self):
        request = c.init_request(self.root/'run', c.canonical(self.manifest))
        self.assertEqual(request['format_version'], 2)
        identity = request['identity']
        directory = self.target/'.v126-target-operations'; directory.mkdir(mode=0o700)
        b.binding_create(directory/(c.digest(c.canonical(identity))+'.request.json'), request)
        operation = dict(identity, kind='RECOVERY', name='pre-v126', action='recover-pre-v126')
        context = dict(environment={'V126_INTERNAL_REMOTE_V125_BASELINE_B64':self.encoded})
        b.binding_operation_baseline(directory, self.target, operation, [identity], context)
        for wrong in [None, {}, dict(environment={}), dict(environment={'V126_INTERNAL_REMOTE_V125_BASELINE_B64':''}),
                      dict(environment={'V126_INTERNAL_REMOTE_V125_BASELINE_B64':base64.b64encode(c.canonical(self.baseline | {'image_id':'sha256:'+'b'*64})).decode()})]:
            with self.assertRaisesRegex(b.BindingError, 'operation_v125_baseline_differs_from_init'):
                b.binding_operation_baseline(directory, self.target, operation, [identity], wrong)

    def test_v4_stream_keeps_epoch_and_baseline_distinct(self):
        values = {name:'NONE' for name in c.ENVELOPE_NAMES}
        values.update(ACTION='baseline', RUN_ID='synthetic-baseline', RELEASE_SHA='c'*40,
            SCRIPT_SHA256='d'*64, OPERATION_KIND='STAGE', OPERATION_NAME='BASELINE_VERIFIED', INTENT_HASH='e'*64)
        source = b'synthetic verified source\n'
        lines = ['V126_INTERNAL_REMOTE_ENVELOPE_V4', *[values[key] for key in c.ENVELOPE_NAMES],
                 '1'*64, '2'*64, self.encoded, '3', str(self.target), 'synthetic-baseline', 'c'*40]
        path=self.root/'stream'; path.write_bytes(b'ignored loader\n'+('\n'.join(lines)+'\n').encode()+source)
        identity, _, env, _ = c.read_local_stream(path, source)
        self.assertEqual(identity['epoch_id'], '1'*64)
        self.assertEqual(identity['domain_identity_sha256'], '2'*64)
        self.assertEqual(env['V126_INTERNAL_REMOTE_V125_BASELINE_B64'], self.encoded)

    def test_remote_selected_identity_rejects_historical_and_unapproved_reference(self):
        result=self.shell('remote_select_v125_baseline; remote_require_v125_reference "$V125_IMAGE_ID"; '
                          'printf "%s %s\\n" "$V125_SOURCE_SHA" "$V125_IMAGE_ID"')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.strip(), 'a'*40+' sha256:'+'a'*64)
        for reference in ['synthetic:f577934691a1a7a79ba327c54e2055425142b7be', 'sha256:'+'b'*64]:
            result=self.shell('remote_select_v125_baseline; remote_require_v125_reference '+reference)
            self.assertNotEqual(result.returncode,0)
        result=self.shell('remote_select_v125_baseline; unset V126_INTERNAL_REMOTE_V125_BASELINE_B64; '
                          'remote_select_v125_baseline; printf "%s" "$V125_SOURCE_SHA"')
        self.assertEqual(result.stdout,'f577934691a1a7a79ba327c54e2055425142b7be')

    def test_compose_runtime_version_follows_exact_selected_image(self):
        body='remote_select_v125_baseline; cutover_bounded_command() { printf "%s\\n" "$@"; }; '
        result=self.shell(body+'REMOTE_BACKEND_IMAGE="$V125_IMAGE_ID"; remote_compose config')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('APP_VERSION='+'a'*40, result.stdout)
        result=self.shell(body+'V126_INTERNAL_REMOTE_RELEASE_SHA='+('c'*40)+'; REMOTE_BACKEND_IMAGE=synthetic:'+('c'*40)+'; remote_compose config')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('APP_VERSION='+'c'*40,result.stdout)
        result=self.shell(body+'V126_INTERNAL_REMOTE_RELEASE_SHA='+('c'*40)+'; REMOTE_BACKEND_IMAGE=synthetic:'+('d'*40)+'; remote_compose config')
        self.assertNotEqual(result.returncode,0)

    def test_legacy_compose_with_no_baseline_preserves_environment(self):
        reference='synthetic:f577934691a1a7a79ba327c54e2055425142b7be'
        result=self.shell('set -u; cutover_bounded_command() { printf "%s\\n" "$@"; }; '
            'REMOTE_BACKEND_IMAGE='+reference+'; remote_compose config',baseline=False)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('BACKEND_IMAGE='+reference,result.stdout.splitlines())
        self.assertNotIn('APP_VERSION=',result.stdout)
        self.assertEqual(result.stdout.splitlines()[-7:],
                         ['docker','compose','--env-file','.env','--file','docker-compose.yml','config'])

    def test_baseline_database_probe_refuses_missing_or_empty_target_before_psql(self):
        source=c.SOURCE.read_text().split("<<'V125_BASELINE_PY'\n",1)[1].split('\nV125_BASELINE_PY',1)[0]
        commands=[node.value for node in ast.walk(ast.parse(source))
                  if isinstance(node,ast.Constant) and isinstance(node.value,str)
                  and 'psql -X -w -U ' in node.value]
        self.assertEqual(len(commands),1)
        fake=self.root/'psql'; fake.write_text('#!/bin/sh\nprintf "invoked\\n"\nprintf "%s\\n" "$@"\n')
        fake.chmod(0o700)
        env={'PATH':str(self.root),'POSTGRES_USER':'synthetic-user','POSTGRES_DB':'synthetic-db'}
        def probe(values):
            return subprocess.run(['/bin/sh','-c',commands[0],'v125-baseline','SELECT synthetic_probe'],
                env=values,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=5)
        good=probe(env)
        self.assertEqual(good.returncode,0,good.stderr)
        self.assertEqual(good.stdout.splitlines(),['invoked','-X','-w','-U','synthetic-user',
            '-d','synthetic-db','-At','--set=ON_ERROR_STOP=1','-c','SELECT synthetic_probe'])
        for field in ['POSTGRES_USER','POSTGRES_DB']:
            for value in [None,'']:
                bad=dict(env)
                if value is None: bad.pop(field)
                else: bad[field]=value
                result=probe(bad)
                self.assertNotEqual(result.returncode,0)
                self.assertEqual(result.stdout,'')

    def test_safe_stop_and_dr_do_not_add_baseline_runtime_prerequisite(self):
        for action, function in [('recover-post-v126-stop','remote_recover_post_v126_stop'),
                                 ('verify-full-dr','remote_verify_full_dr')]:
            result=self.shell('remote_assert_accepted_v125_baseline() { return 97; }; '
                +function+'() { printf "called\\n"; }; remote_dispatch_action '+action+' '+str(self.target)+' synthetic-run '+('c'*40))
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(result.stdout,'called\n')
        result=self.shell('remote_assert_accepted_v125_baseline() { return 97; }; '
            'remote_baseline() { printf "unsafe\\n"; }; remote_dispatch_action baseline '+str(self.target)+' synthetic-run '+('c'*40))
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('unsafe',result.stdout)

    def test_loading_v3_then_legacy_restores_historical_identity(self):
        paths=[]
        legacy=dict(self.manifest,format_version=1,v125_image_tag='synthetic:f577934691a1a7a79ba327c54e2055425142b7be')
        legacy.pop('v125_baseline')
        for name, manifest in [('current',self.manifest),('legacy',legacy)]:
            path=self.root/name; raw=c.canonical(manifest)
            c.write_metadata(path,raw,c.init_request(path,raw)); paths.append(path)
        result=self.shell('load_state '+str(paths[0])+'; [[ "$V125_SOURCE_SHA" == '+('a'*40)+' ]]; '
            'load_state '+str(paths[1])+'; printf "%s %s" "$V125_SOURCE_SHA" "$V125_BASELINE_B64"',baseline=False)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout,'f577934691a1a7a79ba327c54e2055425142b7be ')


class ActualDatabaseBinding(unittest.TestCase):
    """Exercise the real semantic consumer; replace only Docker/libpq observations."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='v126-actual-db-synthetic-')
        self.addCleanup(self.tmp.cleanup)
        self.target = Path(self.tmp.name).resolve()
        registration, state = accepted_documents(self.target)
        self.baseline = b.binding_v125_baseline_documents(json.dumps(registration).encode(),
            json.dumps(state).encode(), self.target)
        self.actual = copy.deepcopy(self.baseline['database'])
        self.network_id = 'e'*64
        self.fault = None
        self.uri = self.target/'synthetic.uri'
        self.uri.write_text('postgresql://fixture:SECRET_CANARY@invalid.invalid/fixture\n')
        self.uri_sha = hashlib.sha256(self.uri.read_bytes()).hexdigest()
        self.encoded = base64.b64encode(b.binding_canonical(self.baseline)).decode()

    def observe(self):
        db = self.actual
        backend_id = 'b'*64
        labels = {'com.docker.compose.project':'fixture',
                  'com.docker.compose.project.working_dir':str(Path.cwd().resolve()),
                  'com.docker.compose.project.config_files':str(Path.cwd().resolve()/'docker-compose.yml')}
        network = dict(NetworkID=self.network_id, IPAddress='172.29.0.2', Aliases=['postgres'])
        source = dict(Id=db['container_id'], RestartCount=0,
            State=dict(Running=True, StartedAt='synthetic'), HostConfig=dict(NetworkMode=db['network']),
            Config=dict(Labels=labels | {'com.docker.compose.service':'postgres'}, Env=[
                'POSTGRES_DB='+db['database'], 'POSTGRES_USER='+db['user'], 'POSTGRES_PASSWORD=SECRET_CANARY']),
            NetworkSettings=dict(Networks={db['network']:network}))
        required = dict(DB_JDBC_URL='jdbc:postgresql://postgres:5432/'+db['database'],
                        DB_USER=db['user'], DB_PASSWORD='SECRET_CANARY')
        backend = copy.deepcopy(source)
        backend.update(Id=backend_id, Config=dict(Labels=labels | {'com.docker.compose.service':'backend'},
            Env=[key+'='+value for key,value in required.items()]))
        backend['NetworkSettings']['Networks'][db['network']]['IPAddress'] = '172.29.0.3'
        config = dict(services=dict(backend=dict(environment=required), postgres={}),
                      networks=dict(default=dict(name=db['network'])))
        planned = dict(Name=db['network'], Id=self.network_id, Labels={'com.docker.compose.project':'fixture'},
                       Containers={db['container_id']:{}, backend_id:{}})
        identity = dict(version=1, system_identifier=db['system_id'], postmaster_epoch='123.0',
            database=db['database'], database_oid='1', schema='public', schema_oid='2',
            search_path=['public'], current_role=db['user'], session_role=db['user'], role_oid='3', read_only='on')
        def dispatch(argv, **kwargs):
            if argv[:2] == ['docker','compose']:
                value = config if 'config' in argv else None
                if value is None:
                    output = ((db['container_id'] if argv[-1]=='postgres' else backend_id)+'\n').encode()
                    if self.fault == 'missing-container': output = b''
                    return subprocess.CompletedProcess(argv,0,output,b'')
            elif argv[:3] == ['docker','network','inspect']:
                value = [] if self.fault == 'missing-network' else [planned]
            elif argv[:2] == ['docker','inspect']:
                value = [source if argv[-1]==db['container_id'] else backend]
                if self.fault == 'backend-target' and argv[-1]==backend_id:
                    value = copy.deepcopy(value); value[0]['Config']['Env'][0] += '_other'
            elif argv[:3] == ['docker','exec','-i']:
                value = identity
            elif 'getent' in argv:
                return subprocess.CompletedProcess(argv,0,b'172.29.0.2 STREAM postgres\n',b'')
            elif argv[:2] == ['python3','-c']:
                value = identity | ({'database':'other'} if self.fault == 'uri-target' else {})
                if self.fault == 'missing-identity': value = {}
            else:
                raise AssertionError('unexpected external command in offline fixture')
            return subprocess.CompletedProcess(argv,0,json.dumps(value).encode(),b'')
        with patch.object(d.subprocess, 'run', dispatch), contextlib.redirect_stdout(io.StringIO()) as output:
            d.main(['live-target',str(self.uri),self.baseline['image_id'],self.uri_sha,self.encoded])
        result = output.getvalue()
        self.assertNotIn('SECRET_CANARY',result)
        self.assertRegex(result,r'^DATABASE_TARGET_EQUALITY=[0-9a-f]{64}\n$')
        return result.strip().split('=')[1]

    def consumer(self, action, observed, expected='NONE', late_drift=False):
        scripts = self.target/'scripts'; scripts.mkdir(exist_ok=True)
        for name in ['check-staging-maintenance-config.sh','validate-staging-admission.sh']:
            path = scripts/name; path.write_text('#!/bin/sh\nexit 0\n'); path.chmod(0o700)
        h = 'd'*64; v126 = 'sha256:'+'c'*64
        body = '''remote_select_v125_baseline
remote_require_operator_file() { :; }
remote_hash_file() { printf '%s' "$fixture_hash"; }
remote_verify_baseline_authority() { :; }
remote_assert_compose_backend_image() { :; }
remote_verify_proof() { :; }
remote_require_run_root() { printf '%s' "$fixture_target"; }
remote_create_run_root() { printf 'proof\\n' >> "$fixture_target/dangerous"; exit 91; }
docker() { printf 'docker\\n' >> "$fixture_target/dangerous"; exit 91; }
sudo() { printf 'sudo\\n' >> "$fixture_target/dangerous"; exit 91; }
cutover_bounded_command() {
  [[ "${@: -1}" == "$V126_INTERNAL_REMOTE_V125_BASELINE_B64" ]] || exit 92
  [[ "$fixture_observed" != REFUSED ]] || return 93
  printf 'DATABASE_TARGET_EQUALITY=%s\\n' "$fixture_observed"
}
'''
        if late_drift:
            # Accepted A remains healthy while the actual consumer changes after
            # its first equality check. Only observations are synthetic here.
            body += '''
remote_capture_compose_ids() { REMOTE_CAPTURED_CONTAINER_IDS=("$fixture_hash"); }
docker() {
  case "$1 $2" in
    'inspect --format'|'image inspect') printf '%s' "$V125_IMAGE_ID" ;;
    'exec '*) : ;;
    *) printf 'unexpected docker\\n' >> "$fixture_target/dangerous"; exit 91 ;;
  esac
}
remote_assert_bound_container_environment() { :; }
remote_require_unique_global_image_container() { :; }
remote_require_global_image_count() { :; }
remote_assert_health_json() { :; }
remote_assert_version() { :; }
remote_assert_telegram_idle() { :; }
remote_sudo_require_root_file() { :; }
curl() { :; }
remote_compose() {
  local query; query="$(cat)"
  case "$query" in *flyway_schema_history*) printf '125:0:0' ;; *) printf '0:0' ;; esac
}
sudo() {
  case "$1" in
    test|caddy) : ;;
    systemctl) printf 'active' ;;
    sha256sum) printf '%s  fixture\\n' "$fixture_hash" ;;
    *) printf 'unexpected sudo\\n' >> "$fixture_target/dangerous"; exit 91 ;;
  esac
}
remote_assert_public_live() { fixture_observed=REFUSED; }
remote_assert_accepted_v125_baseline() { :; }
'''
        args = {'baseline':['remote_baseline',str(self.target),'fixture-run','c'*40,self.baseline['image_id'],
                            str(self.uri),str(self.target/'identities'),h,h,h],
                'backup':['remote_backup_rehearsal',str(self.target),'fixture-run','c'*40,'pre-drain',self.baseline['image_id']],
                'preflight':['remote_final_v125_preflight',str(self.target),'fixture-run','c'*40,self.baseline['image_id'],
                             str(self.uri),str(self.target/'unused'),h,h],
                'migration':['remote_start_v126',str(self.target),'fixture-run','c'*40,'fixture:'+'c'*40,v126,'first']}
        env = dict(PATH=os.environ['PATH'], HOME=str(self.target), TMPDIR=str(self.target),
            PYTHONDONTWRITEBYTECODE='1', fixture_hash=h, fixture_target=str(self.target),
            fixture_observed=observed, REMOTE_DATABASE_URL_PATH=str(self.uri), REMOTE_DATABASE_URL_SHA256=h,
            V126_INTERNAL_REMOTE_V125_BASELINE_B64=self.encoded, V126_INTERNAL_REMOTE_V126_IMAGE_ID=v126,
            V126_INTERNAL_REMOTE_DATABASE_TARGET_IDENTITY_SHA256=expected)
        result = subprocess.run(['bash','-c','source "$1"; '+body+shlex.join(args[action]),'fixture',str(c.SOURCE)],
            env=env,capture_output=True,text=True,timeout=15)
        self.assertFalse((self.target/'dangerous').exists(), 'operation ran before target refusal')
        self.assertNotIn('SECRET_CANARY',result.stdout+result.stderr)
        return result

    def test_accepted_a_actual_a_passes_semantic_consumer(self):
        self.observe()

    def test_accepted_a_actual_b_refuses_before_baseline_proof(self):
        for field,value in [('container_id','f'*64),('system_id','999'),('database','other'),
                            ('user','other'),('network','other_network')]:
            with self.subTest(field=field):
                self.actual = copy.deepcopy(self.baseline['database']); self.actual[field] = value
                with self.assertRaisesRegex(ValueError,'differs from the accepted V125 baseline'): self.observe()
                result = self.consumer('baseline','REFUSED')
                self.assertNotEqual(result.returncode,0)
                self.assertIn('actual server/database/schema/intended-role target equality failed',result.stderr)
                self.assertNotIn('DANGEROUS',result.stdout)

    def test_missing_or_contradictory_target_observations_refuse(self):
        for fault in ['missing-container','missing-network','backend-target','uri-target','missing-identity']:
            with self.subTest(fault=fault):
                self.fault = fault
                with self.assertRaises(ValueError): self.observe()
        self.fault = None
        for missing in self.baseline['database']:
            changed = copy.deepcopy(self.baseline); changed['database'].pop(missing)
            self.encoded = base64.b64encode(b.binding_canonical(changed)).decode()
            with self.subTest(missing=missing), self.assertRaises(ValueError): self.observe()

    def test_target_drift_during_baseline_refuses_before_proof_creation(self):
        result = self.consumer('baseline',self.observe(),late_drift=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('actual server/database/schema/intended-role target equality failed',result.stderr)

    def test_same_name_network_replacement_changes_persisted_binding(self):
        before = self.observe(); self.network_id = 'f'*64
        after = self.observe()
        self.assertNotEqual(before,after)
        for action in ['backup','preflight','migration']:
            with self.subTest(action=action):
                result = self.consumer(action,after,before)
                self.assertNotEqual(result.returncode,0)
                self.assertIn('actual database identity changed',result.stderr)
                self.assertNotIn('DANGEROUS',result.stdout)

    def test_after_baseline_target_drift_refuses_dependent_actions(self):
        before = self.observe(); self.actual['database'] = 'other'
        with self.assertRaises(ValueError): self.observe()
        for action in ['backup','preflight','migration']:
            with self.subTest(action=action):
                result = self.consumer(action,'REFUSED',before)
                self.assertNotEqual(result.returncode,0)
                self.assertIn('target equality failed',result.stderr)
                self.assertNotIn('DANGEROUS',result.stdout)


if __name__ == '__main__': unittest.main(verbosity=2)
