"""CR-1 signed-boundary tests and opt-in real installer/ordinary CLI exercise.

All keys and provider/CI/backup assertions below are explicit local fixtures.
No external provider, trusted human identity or cloud custody is claimed.
"""
import argparse
import base64
import copy
import hashlib
import itertools
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
import zipfile

import staging_release as release
from clean_install_policy import FACETS
from clean_install_runtime import DockerRuntime, archive_jar, local_runtime_identity, migration_manifest, tool_closure
from clean_install_trust import LocalSignedAuthority, ROLES, canonical
from test_clean_install_release import fixture


def write_json(path, value):
    path.write_bytes(canonical(value)); path.chmod(0o600)


def sign(payload, private):
    result = subprocess.run(['/usr/bin/openssl', 'dgst', '-sha256', '-sign', str(private)], input=canonical(payload),
                            capture_output=True, check=True, timeout=10)
    return {'payload': payload, 'signature': base64.b64encode(result.stdout).decode()}


def keys_at(root):
    keys, private = {}, {}
    for role in sorted(ROLES):
        private[role] = root / (role + '.private')
        pub = root / (role + '.public')
        subprocess.run(['/usr/bin/openssl', 'genrsa', '-out', str(private[role]), '2048'], capture_output=True, check=True, timeout=30)
        private[role].chmod(0o600)
        subprocess.run(['/usr/bin/openssl', 'rsa', '-in', str(private[role]), '-pubout', '-out', str(pub)],
                       capture_output=True, check=True, timeout=10)
        pub.chmod(0o600)
        keys[role] = {'path': str(pub), 'sha256': hashlib.sha256(pub.read_bytes()).hexdigest()}
    return keys, private


def initialize_custody(e):
    store = release.Store(e['custody'])
    with store.locked():
        store.write({'scope': release.fingerprint(e['scope']), 'target': e['target_binding'],
                     'state': None, 'pending': None, 'tail': None, 'events': []})


def issue(e, request, private, overrides=None):
    """Test issuer, intentionally absent from installer/worker modules."""
    expires = time.time() + 240
    payloads = {role: {'role': role, 'audience': 'CR1_DISPOSABLE_LOCAL', 'scope': release.fingerprint(e['scope']),
        'target': e['target_binding'], 'request': release.fingerprint(request), 'epoch': e['epoch'], 'expires': expires}
        for role in ROLES}
    payloads['owner']['approved'] = True
    payloads['provider'].update(reset_complete=True, pending_actions=[], incarnation=e['scope']['incarnation'])
    if e['scope']['origin'] == 'SHARED_HOST_HOOKAH_SCOPED_REPLACEMENT':
        binding=e['scope']['shared_host']
        payloads['owner']['shared_host_trust']=release.fingerprint(binding)
        payloads['provider'].pop('reset_complete')
        payloads['provider'].update(mode='RETAINED_SHARED_HOST',host=binding['host'],admins=release.fingerprint(binding['retained_admins']))
    payloads['custodian']['custody'] = release.fingerprint({'path': e['custody'], 'target': e['target_binding']})
    facts = {'request': release.fingerprint(request), 'expires': expires, 'tooling': e['scope']['tooling'],
             'ancestry': e['scope']['ancestry'], 'historical_run724': 'UNKNOWN', 'pending_effects': [], 'external_writers': [],
             'facets': {key: {'verdict': 'EXCLUDED', 'receipt': '5'*64} for key in FACETS},
             **{key: '6'*64 for key in ('reset', 'empty', 'loss_scope', 'recovery', 'ci_receipt', 'compatibility', 'smoke', 'public_handoff')},
             'owner_approval': release.fingerprint(payloads['owner']),
             'ci_revision': request['plan'].get('image', {}).get('revision'),
             'compatibility_verdict': 'COMPATIBLE_FORWARD' if request['plan']['kind'] == 'FORWARD' else 'CODE_ONLY_SAME_SCHEMA'}
    facts['facets']['provider']['receipt'] = release.fingerprint(payloads['provider'])
    facts['facets']['custody']['receipt'] = release.fingerprint(payloads['custodian'])
    if e['scope']['origin'] == 'SHARED_HOST_HOOKAH_SCOPED_REPLACEMENT':
        from test_shared_host_scope import scoped_proof
        facts.pop('reset')
        facts['facets']['provider']['verdict']='RETAINED_DEPENDENCY'
        facts['scoped']=scoped_proof(e['scope'],request,release.fingerprint(payloads['owner']),release.fingerprint(payloads['custodian']))
    payloads['verifier']['facts'] = facts
    if overrides:
        overrides(payloads)
    bundle = {role: sign(payloads[role], private[role]) for role in ROLES}
    write_json(Path(e['evidence']), bundle)
    revocations = dict(payloads['custodian'], request='REVOCATIONS', active=True, revoked=[])
    write_json(Path(e['revocations']), sign(revocations, private['custodian']))
    return bundle


class SignedBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys_temp = tempfile.TemporaryDirectory()
        cls.keys, cls.private = keys_at(Path(cls.keys_temp.name).resolve())

    @classmethod
    def tearDownClass(cls):
        cls.keys_temp.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name).resolve()
        (root / 'target').mkdir(mode=0o700)
        (root / 'custody').mkdir(mode=0o700)
        scope, image = fixture()
        self.e = {'profile': 'DISPOSABLE_LOCAL', 'scope': scope, 'directory': str(root / 'target'),
                  'custody': str(root / 'custody'), 'keys': copy.deepcopy(self.keys), 'epoch': 'fixture-1',
                  'target_binding': 'a'*64, 'evidence': str(root / 'bundle.json'), 'revocations': str(root / 'revocations.json')}
        initialize_custody(self.e)
        self.request = {'scope': scope, 'plan': {'kind': 'BOOTSTRAP', 'image': image}, 'before': {}, 'previous': None}
        self.bundle = issue(self.e, self.request, self.private)
        self.authority = LocalSignedAuthority(self.e, observe=lambda **kw: {'fixture': 'known'})

    def refused(self, reason, fn):
        with self.assertRaisesRegex(release.Refused, '^'+reason+'$'): fn()

    def test_signed_exact_request_and_durable_cross_process_cas(self):
        proof = self.authority.authorize(self.request, timeout=10)
        token = self.authority.begin(self.request, proof, None, timeout=10)
        other = LocalSignedAuthority(self.e, observe=lambda **kw: {'fixture': 'known'})
        self.assertEqual(other.head(self.e['scope']['installation'], timeout=5)['pending'], token)
        other_proof = other.authorize(self.request, timeout=10)
        self.refused('CUSTODY_CAS_CONFLICT', lambda: other.begin(self.request, other_proof, None, timeout=10))
        terminal = {'status': 'CLOSED_V126', 'observed': {'fixture': 'known'}}
        self.authority.finish(token, terminal, timeout=10)
        self.assertEqual(other.head(self.e['scope']['installation'], timeout=5)['state'], release.fingerprint(terminal))

    def test_missing_provider_verifier_or_custodian(self):
        for role in ('provider', 'verifier', 'custodian'):
            value = copy.deepcopy(self.bundle); del value[role]
            write_json(Path(self.e['evidence']), value)
            self.refused('INDEPENDENT_ROLES_REQUIRED', lambda: self.authority.authorize(self.request, timeout=10))

    def test_tampered_provider(self):
        self.bundle['provider']['payload']['reset_complete'] = False
        write_json(Path(self.e['evidence']), self.bundle)
        self.refused('EVIDENCE_SIGNATURE_INVALID', lambda: self.authority.authorize(self.request, timeout=10))

    def test_signed_incomplete_provider_reset(self):
        issue(self.e, self.request, self.private, lambda p: p['provider'].update(pending_actions=['unknown']))
        self.refused('PROVIDER_RESET_NOT_PROVEN', lambda: self.authority.authorize(self.request, timeout=10))

    def test_request_or_incarnation_substitution(self):
        changed = copy.deepcopy(self.request); changed['plan']['kind'] = 'PUBLIC'
        self.refused('AUTHORITY_REQUEST_MISMATCH', lambda: self.authority.authorize(changed, timeout=10))

    def test_one_key_cannot_be_all_roles(self):
        self.e['keys'] = {role: self.keys['owner'] for role in ROLES}
        self.refused('INDEPENDENT_ROLES_REQUIRED', lambda: LocalSignedAuthority(self.e, observe=lambda **kw: {}))

    def test_same_key_with_different_pem_encoding_is_not_independent(self):
        original = Path(self.keys['owner']['path']).read_bytes()
        for n, role in enumerate(sorted(ROLES)):
            path = Path(self.temp.name).resolve()/(role+'.pem')
            path.write_bytes(original+b'\n'*n); path.chmod(0o600)
            self.e['keys'][role] = {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        self.refused('INDEPENDENT_ROLES_REQUIRED', lambda: LocalSignedAuthority(self.e, observe=lambda **kw: {}))

    def test_revocation_cannot_be_undone_by_old_signed_active_file(self):
        previous = Path(self.e['revocations']).read_bytes()
        value = release.read_json(self.e['revocations'])['payload']; value['active'] = False
        write_json(Path(self.e['revocations']), sign(value,self.private['custodian']))
        self.refused('AUTHORITY_REVOKED',lambda:self.authority.authorize(self.request,timeout=10))
        Path(self.e['revocations']).write_bytes(previous)
        other = LocalSignedAuthority(self.e,observe=lambda **kw:{})
        self.refused('AUTHORITY_REVOKED',lambda:other.authorize(self.request,timeout=10))

    def test_orphan_durable_intent_blocks_restart(self):
        proof = self.authority.authorize(self.request,timeout=10)
        with patch.object(self.authority.store,'write',side_effect=OSError('fixture fsync loss')):
            with self.assertRaises(OSError): self.authority.begin(self.request,proof,None,timeout=10)
        other = LocalSignedAuthority(self.e,observe=lambda **kw:{})
        self.refused('CUSTODY_ORPHAN_OBJECT',lambda:other.head(self.e['scope']['installation'],timeout=5))
        from clean_install_runtime import local_action
        registration=Path(self.temp.name).resolve()/'registration.json';write_json(registration,self.e)
        with patch('clean_install_runtime.DockerRuntime'):
            result=local_action(argparse.Namespace(local_registration=str(registration),action='status'))
        self.assertEqual(result['status'],'UNKNOWN')
        self.assertEqual(result['reason'],'CUSTODY_ORPHAN_OBJECT')

    def test_revocation_after_effects_preserves_pending(self):
        proof=self.authority.authorize(self.request,timeout=10)
        token=self.authority.begin(self.request,proof,None,timeout=10)
        value=release.read_json(self.e['revocations'])['payload'];value['active']=False
        write_json(Path(self.e['revocations']),sign(value,self.private['custodian']))
        self.refused('AUTHORITY_REVOKED',lambda:self.authority.finish(token,{'observed':{'fixture':'known'}},timeout=10))
        self.assertEqual(self.authority.head(self.e['scope']['installation'],timeout=5)['pending'],token)

    def revoke_after_next_authorization(self):
        authorize=self.authority.authorize
        def authorize_then_revoke(*args,**kwargs):
            proof=authorize(*args,**kwargs)
            value=release.read_json(self.e['revocations'])['payload'];value['active']=False
            write_json(Path(self.e['revocations']),sign(value,self.private['custodian']))
            other=LocalSignedAuthority(self.e,observe=lambda **kw:{})
            self.refused('AUTHORITY_REVOKED',lambda:other.authorize(self.request,timeout=10))
            return proof
        self.authority.authorize=authorize_then_revoke

    def test_begin_revocation_race_under_cas_lock(self):
        proof=self.authority.authorize(self.request,timeout=10)
        self.revoke_after_next_authorization()
        self.refused('AUTHORITY_REVOKED',lambda:self.authority.begin(self.request,proof,None,timeout=10))
        self.assertIsNone(self.authority.head(self.e['scope']['installation'],timeout=5)['pending'])

    def test_finish_revocation_race_under_cas_lock(self):
        proof=self.authority.authorize(self.request,timeout=10)
        token=self.authority.begin(self.request,proof,None,timeout=10)
        self.revoke_after_next_authorization()
        self.refused('AUTHORITY_REVOKED',lambda:self.authority.finish(token,{'observed':{'fixture':'known'}},timeout=10))
        self.assertEqual(self.authority.head(self.e['scope']['installation'],timeout=5)['pending'],token)

    def test_unobserved_health_raises_instead_of_known_failed(self):
        runtime=object.__new__(DockerRuntime)
        with patch.object(runtime,'request',side_effect=subprocess.TimeoutExpired('probe',1)):
            self.refused('HEALTH_OUTCOME_UNKNOWN',lambda:runtime.clean_ready({},timeout=.1))

    def test_known_unhealthy_http_is_failed(self):
        runtime=object.__new__(DockerRuntime)
        with patch.object(runtime,'request',return_value={'status':503,'body':'{}'}):
            self.assertFalse(runtime.clean_ready({},timeout=.1))

    def test_expired_and_revoked_authority(self):
        issue(self.e, self.request, self.private, lambda p: p['owner'].update(expires=time.time()-1))
        self.refused('AUTHORITY_EXPIRED', lambda: self.authority.authorize(self.request, timeout=10))
        issue(self.e, self.request, self.private)
        value = release.read_json(self.e['revocations'])['payload']; value['active'] = False
        write_json(Path(self.e['revocations']), sign(value, self.private['custodian']))
        self.refused('AUTHORITY_REVOKED', lambda: self.authority.authorize(self.request, timeout=10))

    def test_revocation_between_authorize_and_begin(self):
        proof = self.authority.authorize(self.request, timeout=10)
        value = release.read_json(self.e['revocations'])['payload']; value['revoked'] = ['fixture-owner']
        write_json(Path(self.e['revocations']), sign(value, self.private['custodian']))
        self.refused('AUTHORITY_REVOKED', lambda: self.authority.begin(self.request, proof, None, timeout=10))

    def test_lost_intent_ack_retains_reservation(self):
        proof = self.authority.authorize(self.request, timeout=10)
        write = self.authority.write_event
        def lost(data, event):
            write(data, event); raise TimeoutError()
        self.authority.write_event = lost
        with self.assertRaises(TimeoutError): self.authority.begin(self.request, proof, None, timeout=10)
        self.assertIsNotNone(LocalSignedAuthority(self.e, observe=lambda **kw: {}).head(self.e['scope']['installation'], timeout=5)['pending'])

    def test_deleted_or_mutated_custody_fails_closed(self):
        proof = self.authority.authorize(self.request, timeout=10)
        self.authority.begin(self.request, proof, None, timeout=10)
        path = Path(self.e['custody'])/'state.json'; data = release.read_json(path); data['pending'] = None
        write_json(path, data)
        self.refused('CUSTODY_HISTORY_INVALID', lambda: self.authority.head(self.e['scope']['installation'], timeout=5))
        path.unlink()
        self.refused('CUSTODY_HISTORY_MISSING', lambda: LocalSignedAuthority(self.e, observe=lambda **kw: {}))

    def test_production_profile_has_no_adapter(self):
        self.e['profile'] = 'PRODUCTION'
        self.refused('BLOCKED_EXTERNAL_ENROLLMENT', lambda: LocalSignedAuthority(self.e, observe=lambda **kw: {}))

    def test_install_without_local_registration_never_uses_ssh(self):
        process = subprocess.run(['bash', str(Path(__file__).with_name('deploy-staging.sh')), 'install', '--target', 'fixture'], capture_output=True)
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stderr)['reason'], 'BLOCKED_EXTERNAL_ENROLLMENT')


class RuntimeGuardTests(unittest.TestCase):
    def setUp(self):
        self.runtime=object.__new__(DockerRuntime);self.runtime.name='fixture'
        self.item={'NetworkSettings':{'Networks':{'fixture':{}},'Ports':{'5432/tcp':None}},
                   'HostConfig':{'NetworkMode':'fixture','RestartPolicy':{'Name':'no'},'IpcMode':'private'},
                   'RestartCount':0,'State':{},'Mounts':[],
                   'Config':{'Env':['PATH=/bin'],'Entrypoint':None,'Cmd':['server'],'User':'','WorkingDir':''}}
        self.image={'Config':copy.deepcopy(self.item['Config'])}

    def test_additional_network_is_rejected(self):
        self.item['NetworkSettings']['Networks']['external']={}
        with self.assertRaisesRegex(release.Refused,'CONTAINER_ISOLATION_CHANGED'):self.runtime.isolation(self.item)

    def test_pg_published_port_and_publish_all_are_rejected(self):
        self.runtime.isolation(self.item)
        self.item['NetworkSettings']['Ports']['5432/tcp']=[{'HostIp':'0.0.0.0','HostPort':'5432'}]
        with self.assertRaisesRegex(release.Refused,'CONTAINER_ISOLATION_CHANGED'):self.runtime.isolation(self.item)
        self.item['NetworkSettings']['Ports']['5432/tcp']=None
        self.item['HostConfig']['PublishAllPorts']=True
        with self.assertRaisesRegex(release.Refused,'CONTAINER_ISOLATION_CHANGED'):self.runtime.isolation(self.item)

    def test_privilege_and_host_namespace_rejected(self):
        for key,value in (('Privileged',True),('CapAdd',['SYS_ADMIN']),('PidMode','host'),('Binds',['/etc:/etc'])):
            item=copy.deepcopy(self.item);item['HostConfig'][key]=value
            with self.assertRaisesRegex(release.Refused,'CONTAINER_ISOLATION_CHANGED'):self.runtime.isolation(item)

    def test_same_image_different_entrypoint_or_env_rejected(self):
        with patch.object(self.runtime,'json',return_value=[self.image]):
            self.runtime.execution(self.item,'fixture-image',{})
            self.item['Config']['Entrypoint']=['sh','-c','server & writer']
            with self.assertRaisesRegex(release.Refused,'EXECUTION_ENTRYPOINT_CHANGED'):self.runtime.execution(self.item,'fixture-image',{})
            self.item['Config']['Entrypoint']=None
            self.item['Config']['Env'].append('JAVA_TOOL_OPTIONS=-agentlib:fixture')
            with self.assertRaisesRegex(release.Refused,'EXECUTION_ENV_CHANGED'):self.runtime.execution(self.item,'fixture-image',{})


class SharedDaemonGuardTests(unittest.TestCase):
    def setUp(self):
        from test_shared_host_scope import scoped_fixture
        scope,_=fixture();scoped_fixture(scope)
        self.r=object.__new__(DockerRuntime);self.r.name='ht-cr1-local-123456789abc';self.r.scope=scope;self.r.root=Path('/private/tmp')/self.r.name
        self.r.e={'images':{},'shared_guard':{'retired':{'containers':['a'*64],'networks':['hookah-bot_default'],
                                           'volumes':['hookah-bot_pgdata']}}}
        self.items=[{'Id':'b'*64,'Name':'/neighbor','Image':'sha256:'+'c'*64,'Config':{'Env':['SECRET=fixture'],
                     'Labels':{'com.docker.compose.project':'hookah-production'}},'HostConfig':{},'Mounts':[],
                     'NetworkSettings':{'Networks':{}},'State':{'Running':True}}]
        self.nets=[{'Id':'d'*64,'Name':'neighbor_net','Containers':{}}]
        self.vols=[{'Name':'neighbor_data'}]
        self.r.command=lambda *a,**kw:b'fixture'
        self.r.json=lambda *a:self.items if a[0]=='inspect' else self.nets if a[0]=='network' else self.vols
        self.r.e['shared_guard']['protected_daemon']=self.r.protected_daemon()

    def mounted_baseline(self):
        self.items[0]['Mounts'] = [
            {'Type':'volume','Name':'neighbor_data','Source':'/srv/neighbor/data',
             'Destination':'/data','Driver':'local','Mode':'z','RW':True,'Propagation':''},
            {'Type':'bind','Source':'/srv/neighbor/config','Destination':'/config',
             'Mode':'ro','RW':False,'Propagation':'rprivate',
             'FutureField':{'options':['one','two'],'enabled':True}},
        ]
        self.r.e['shared_guard']['protected_daemon']=self.r.protected_daemon()

    def test_mount_order_and_object_key_order_do_not_change_digest_or_input(self):
        self.mounted_baseline()
        mounts=self.items[0]['Mounts']
        before=copy.deepcopy(mounts)
        mounts.reverse()
        mounts[:]=[dict(reversed(list(m.items()))) for m in mounts]
        reordered=copy.deepcopy(mounts)
        self.r.shared_guard()
        self.assertEqual(mounts,reordered)
        self.assertEqual(list(mounts[0]),list(reordered[0]))
        self.assertEqual(mounts,list(reversed(before)))

    def test_every_mount_field_and_unknown_nested_fields_remain_protected(self):
        self.mounted_baseline()
        original=copy.deepcopy(self.items[0]['Mounts'])
        for index,mount in enumerate(original):
            for key,value in mount.items():
                with self.subTest(index=index,key=key):
                    self.items[0]['Mounts']=copy.deepcopy(original)
                    changed = not value if isinstance(value,bool) else value+'-changed' if isinstance(value,str) else {'options':['two','one'],'enabled':True}
                    self.items[0]['Mounts'][index][key]=changed
                    with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()
                    self.items[0]['Mounts']=copy.deepcopy(original)
                    del self.items[0]['Mounts'][index][key]
                    with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()
        self.items[0]['Mounts']=copy.deepcopy(original)
        self.items[0]['Mounts'][0]['NewField']=None
        with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()

    def test_mount_add_remove_and_duplicate_multiplicity_are_protected(self):
        self.mounted_baseline()
        original=copy.deepcopy(self.items[0]['Mounts'])
        for mounts in (original[:1],original+[copy.deepcopy(original[0])]):
            self.items[0]['Mounts']=mounts
            with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()
        # Duplicates in the baseline also survive normalization.
        self.r.e['shared_guard']['protected_daemon']=self.r.protected_daemon()
        self.items[0]['Mounts'].reverse()
        self.r.shared_guard()
        self.items[0]['Mounts'].pop(0)
        with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()

    def test_container_and_mount_permutations_are_independent(self):
        self.mounted_baseline()
        other=copy.deepcopy(self.items[0]);other.update(Id='e'*64,Name='/second-neighbor')
        self.items.append(other)
        self.r.e['shared_guard']['protected_daemon']=self.r.protected_daemon()
        original=copy.deepcopy(self.items)
        for order in itertools.permutations(original):
            for first,second in itertools.product((False,True),repeat=2):
                self.items[:]=copy.deepcopy(order)
                for item,reverse in zip(self.items,(first,second)):
                    if reverse:item['Mounts'].reverse()
                self.r.shared_guard()

    def test_malformed_mount_collections_fail_closed_without_dispatch(self):
        self.r.command=DockerRuntime.command.__get__(self.r)
        self.r.docker='docker';self.r.environment={}
        for mounts in (None,{},'',{'unexpected':{}},[None],[[]],['mount'],[1]):
            with self.subTest(mounts=mounts):
                self.items[0]['Mounts']=mounts
                with patch('clean_install_runtime.subprocess.run') as run:
                    run.return_value=argparse.Namespace(returncode=0,stdout=b'fixture')
                    with self.assertRaisesRegex(release.Refused,'PROTECTED_MOUNTS_INVALID'):
                        self.r.command('volume','create',*self.r.labels(),self.r.volume_name)
                    self.assertTrue(run.call_args_list)
                    self.assertTrue(all(call.args[0][1:3] in (['ps','-aq'],['volume','ls'])
                                        for call in run.call_args_list))

    def test_neighbor_container_volume_and_network_drift_block_dispatch(self):
        self.mounted_baseline()
        self.r.command=DockerRuntime.command.__get__(self.r)
        self.r.docker='docker';self.r.environment={}
        for collection,key,value in ((self.items,'Image','changed'),(self.vols,'Driver','changed'),
                                      (self.nets,'Internal',True)):
            original=copy.deepcopy(collection)
            for remove in (False,True):
                with self.subTest(collection=key,remove=remove):
                    collection[:]=copy.deepcopy(original)
                    if remove:collection.clear()
                    else:collection[0][key]=value
                    with patch('clean_install_runtime.subprocess.run') as run:
                        run.return_value=argparse.Namespace(returncode=0,stdout=b'fixture')
                        with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):
                            self.r.command('volume','create',*self.r.labels(),self.r.volume_name)
                        self.assertTrue(all(call.args[0][1:3] in (['ps','-aq'],['volume','ls'],['network','ls'])
                                            for call in run.call_args_list))
            collection[:]=original

    def test_all_neighbors_and_unlabelled_resources_are_protected(self):
        self.r.shared_guard()
        self.assertNotIn('SECRET',self.r.protected_daemon())
        for collection,item in [(self.items,{'Id':'e'*64,'Name':'/unknown','Image':'x','Config':{'Labels':{}},
                                 'HostConfig':{},'Mounts':[],'NetworkSettings':{'Networks':{}},'State':{'Running':False}}),
                                (self.nets,{'Id':'e'*64,'Name':'unknown','Containers':{}}),
                                (self.vols,{'Name':'unknown'})]:
            collection.append(item)
            with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()
            collection.pop()

    def test_neighbor_config_mount_port_or_running_state_drift(self):
        original=copy.deepcopy(self.items[0])
        for key,value in [('Config',{'Env':['SECRET=changed']}),('Mounts',[{'Name':'other_data'}]),
                          ('HostConfig',{'PortBindings':{'8080/tcp':[]}}),('State',{'Running':False})]:
            self.items[0][key]=value
            with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()
            self.items[0]=copy.deepcopy(original)

    def test_old_compose_writer_with_new_container_id_refused(self):
        self.items[0]['Config']['Labels']['com.docker.compose.project']='hookah-bot'
        with self.assertRaisesRegex(release.Refused,'OLD_EXECUTOR_PRESENT'):self.r.shared_guard()

    def test_old_or_new_volume_shared_consumer_refused(self):
        for volume in ('hookah-bot_pgdata',self.r.name):
            self.items[0]['Mounts']=[{'Name':volume}]
            with self.assertRaisesRegex(release.Refused,'OLD_OR_FOREIGN_VOLUME_CONSUMER'):self.r.shared_guard()

    def test_stopped_bind_alias_and_parent_paths_refused_before_baseline(self):
        for source in ('/var/lib/docker/volumes/hookah-bot_pgdata/_data', '/var/lib/docker',
                       '/opt/hookah-bot', '/opt', '/private/tmp', '/private/tmp/'+self.r.name):
            self.items[0]['State']['Running']=False
            self.items[0]['Mounts']=[{'Type':'bind','Source':source,'RW':True}]
            with self.subTest(source=source),self.assertRaisesRegex(release.Refused,'OLD_OR_FOREIGN_VOLUME_CONSUMER'):
                self.r.protected_daemon()

    def test_flag_alias_duplicate_name_and_driver_override_refused(self):
        for extra in [('--mount=type=bind,src=/opt/foreign,dst=/data',),('--privileged=true',),
                      ('--name','foreign'),('--network','foreign'),('--publish','8080:8080')]:
            argv=('run','--name',self.r.name+'-pg','--network',self.r.name,*extra,'image')
            with self.subTest(extra=extra),self.assertRaises(release.Refused):self.r.scoped_command(argv)
        for argv in [('volume','create','--driver','local','--opt','device=/opt/foreign',self.r.name),
                     ('network','create','--driver','host',self.r.name)]:
            with self.assertRaises(release.Refused):self.r.scoped_command(argv)

    def test_target_prefix_is_not_an_ownership_filter(self):
        self.items[0]['Name']='/'+self.r.name+'-rogue'
        with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.shared_guard()

    def test_global_daemon_systemd_caddy_and_foreign_volume_commands_rejected(self):
        for argv in [('system','prune'),('compose','down'),('network','prune'),('volume','rm','neighbor_data'),
                     ('restart','docker'),('systemctl','stop','docker'),('caddy','reload'),
                     ('volume','create','neighbor_data'),('network','create','hookah-production_default'),
                     ('exec','hookah-production-postgres-1','psql'),
                     ('run','--name',self.r.name+'-pg','--network',self.r.name,'--mount',
                      'type=volume,src=neighbor_data,dst=/var/lib/postgresql/data','image')]:
            with self.subTest(argv=argv),self.assertRaises(release.Refused):self.r.scoped_command(argv)

    def test_neighbor_drift_before_each_mutation_has_no_dispatch(self):
        self.r.command=DockerRuntime.command.__get__(self.r)
        with patch.object(self.r,'shared_guard',side_effect=release.Refused('PROTECTED_RESOURCE_DRIFT')), \
             patch('clean_install_runtime.subprocess.run') as run:
            with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):
                self.r.command('volume','create',*self.r.labels(),self.r.name)
            run.assert_not_called()


class ScopedSignedTests(SignedBoundaryTests):
    def setUp(self):
        super().setUp()
        from test_shared_host_scope import scoped_fixture
        scoped_fixture(self.e['scope'])
        self.request['before']={'database':{'system_id':'UNCREATED'}}
        initialize_custody(self.e)
        self.bundle=issue(self.e,self.request,self.private)
        self.authority=LocalSignedAuthority(self.e,observe=lambda **kw:{'fixture':'known'})

    def test_signed_incomplete_provider_reset(self):
        # Separate origin: reset_complete cannot stand in for retained-host control.
        issue(self.e,self.request,self.private,lambda p:p['provider'].update(mode='RESET',reset_complete=True))
        self.refused('RETAINED_HOST_NOT_PROVEN',lambda:self.authority.authorize(self.request,timeout=5))

    def test_retained_admin_set_or_owner_trust_missing(self):
        for role,values,reason in [('provider',{'admins':'f'*64},'RETAINED_HOST_NOT_PROVEN'),
                                   ('provider',{'host':'f'*64},'RETAINED_HOST_NOT_PROVEN'),
                                   ('provider',{'pending_actions':['unknown']},'RETAINED_HOST_NOT_PROVEN'),
                                   ('owner',{'shared_host_trust':None},'HOST_ADMIN_TRUST_UNPROVEN')]:
            issue(self.e,self.request,self.private,lambda p:p[role].update(values))
            self.refused(reason,lambda:self.authority.authorize(self.request,timeout=5))

    def test_custody_and_admin_approval_chain_cannot_be_replaced(self):
        for key in ('custody_receipt','admin_trust_approval'):
            issue(self.e,self.request,self.private,lambda p:p['verifier']['facts']['scoped'].update({key:'f'*64}))
            self.refused('EVIDENCE_CHAIN_MISMATCH',lambda:self.authority.authorize(self.request,timeout=5))


@unittest.skipUnless(os.environ.get('HT_CR1_RUNTIME_TEST') == '1', 'explicit disposable Docker installer opt-in required')
class RealInstallerTests(unittest.TestCase):
    def test_real_cli_install_accept_app_forward_and_denials(self):
        Runtime = DockerRuntime
        if os.environ.get('HT_CR1_COMPOSE_TEST') == '1':
            from clean_install_compose import ComposeRuntime
            Runtime = ComposeRuntime
        from test_clean_install_postgres import BACKEND, POSTGRES, REVISION, ARCHIVE, JAVA, PostgreSQLRuntime, command
        from clean_install_runtime import PROBE_IMAGE
        POSTGRES = PG_IMAGE = Runtime.pg_image
        name = 'ht-cr1-local-' + uuid.uuid4().hex[:12]
        parent = Path('/private/tmp') if Path('/private/tmp').exists() else Path('/tmp')
        target = parent / name; target.mkdir(mode=0o700)
        self.addCleanup(shutil.rmtree, target)
        resources, tags = [], []
        docker = lambda *args, **kw: command(['docker', *args], **kw)
        with tempfile.TemporaryDirectory(prefix='ht-cr1-proof-', dir=parent) as temp:
            outside = Path(temp).resolve()
            keys, private = keys_at(outside)
            custody = outside / 'custody'; custody.mkdir(mode=0o700)
            qualification = outside / 'qualification'; qualification.mkdir(mode=0o755)
            outside.chmod(0o755)  # Only qualification is mounted; private keys remain mode 0600.
            qname = 'ht-cr1-qual-' + uuid.uuid4().hex[:12]
            try:
                for image in (BACKEND, PG_IMAGE, PROBE_IMAGE): docker('image', 'inspect', image)
                jar = archive_jar(Path('/Users/maksimmartynov/HookahReleaseArtifacts/HT-V126-EXACT-RELEASE-IMAGE-30/'
                                   'hookah_bot_ant-backend-' + REVISION + '.docker-save.tar'))
                migrations = qualification / 'migrations'; migrations.mkdir(mode=0o755)
                with zipfile.ZipFile(__import__('io').BytesIO(jar)) as z:
                    for item in migration_manifest(jar):
                        (migrations/item['script']).write_bytes(z.read('db/migration/postgresql/'+item['script']))
                (qualification/'CleanFlywayFixture.java').write_text(JAVA)
                command(['javac','--release','17',str(qualification/'CleanFlywayFixture.java')])
                password = secrets.token_hex(24)
                (qualification/'fixture.env').write_text('POSTGRES_DB=fixture\nPOSTGRES_USER=fixture\nPOSTGRES_PASSWORD='+password+'\nFIXTURE_PASSWORD='+password+'\n')
                (qualification/'fixture.env').chmod(0o600)
                docker('network','create','--internal','--label','ht.cr1.test='+name,qname); resources.append(('network',qname))
                docker('volume','create','--label','ht.cr1.test='+name,qname); resources.append(('volume',qname))
                docker('run','-d','--pull','never','--platform',Runtime.pg_platform,'--name',qname,'--network',qname,'--network-alias','postgres',
                       '--label','ht.cr1.test='+name,'--env-file',str(qualification/'fixture.env'),
                       '--mount','type=volume,src='+qname+',dst=/var/lib/postgresql/data',POSTGRES)
                resources.append(('container',qname))
                for _ in range(100):
                    try: docker('exec',qname,'pg_isready','-h','127.0.0.1','-U','fixture','-d','fixture'); break
                    except RuntimeError: time.sleep(.1)
                scope, record = fixture()
                scope.update(installation=uuid.uuid4().hex, incarnation=hashlib.sha256(name.encode()).hexdigest())
                scope['tooling'] = {'source': REVISION, 'tree': subprocess.check_output(['git','--no-optional-locks','rev-parse','HEAD^{tree}'],text=True).strip(),
                                    'closure': tool_closure(), 'runtime': local_runtime_identity()}
                record.update(id=BACKEND,revision=REVISION,archive=ARCHIVE,manifest=migration_manifest(jar))
                qualifier = PostgreSQLRuntime(scope,record,qualification,qname)
                qualifier.migrate('fixture'); record['schema'] = qualifier.schema()
                base_env = {'APP_ENV':'staging','APP_HTTP_PORT':'8080','DB_JDBC_URL':'jdbc:postgresql://postgres:5432/fixture',
                    'DB_USER':'fixture','DB_PASSWORD':secrets.token_hex(24)+'-$literal-${HOME}','API_SESSION_JWT_SECRET':secrets.token_hex(32),
                    'TELEGRAM_BOT_ENABLED':'false','TELEGRAM_BOT_TOKEN':'340000001:'+secrets.token_urlsafe(32),
                    'TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER':secrets.token_hex(32),'VENUE_STAFF_INVITE_SECRET_PEPPER':secrets.token_hex(32),
                    'MINIAPP_ENTRY_ENABLED':'true','MINIAPP_STATIC_DIR':'/app/miniapp','MINIAPP_DEV_SERVER_URL':'',
                    'AI_ASSISTANT_ENABLED':'false','YANDEX_MAPS_GEODATA_ENABLED':'false','BOOKING_EXPIRY_WORKER_ENABLED':'false',
                    'BOOKING_REMINDER_WORKER_ENABLED':'false','VISIT_FEEDBACK_WORKER_ENABLED':'false','BILLING_PROVIDER':'fake',
                    'BILLING_WEBHOOK_SECRET':secrets.token_hex(32)}
                raw = ''.join(k+'='+v+'\n' for k,v in base_env.items()).encode()
                (target/'runtime.env').write_bytes(raw); (target/'runtime.env').chmod(0o600)
                context = json.loads(docker('context','inspect'))[0]['Endpoints']['docker']['Host']
                self.assertTrue(context.startswith('unix://'))
                engine = json.loads(docker('info','--format','{{json .}}'))['ID']
                e = {'profile':'DISPOSABLE_LOCAL','scope':scope,'directory':str(target),'name':name,
                    'docker_socket':context.removeprefix('unix://'),'engine_id':engine,
                    'target_binding':release.fingerprint({'engine':engine,'name':name,'incarnation':scope['incarnation']}),
                    'environment_sha256':hashlib.sha256(raw).hexdigest(),'custody':str(custody),'keys':keys,
                    'epoch':'test-epoch-1','evidence':str(outside/'bundle.json'),'revocations':str(outside/'revocations.json'),
                    'images':{record['id']:{'record':record,'archive':'/Users/maksimmartynov/HookahReleaseArtifacts/HT-V126-EXACT-RELEASE-IMAGE-30/hookah_bot_ant-backend-'+REVISION+'.docker-save.tar',
                                           'tag':'hookah_bot_ant-backend:'+REVISION}}}
                from test_shared_host_scope import scoped_fixture
                from shared_host_scope import replacement_scope
                inv,routes=scoped_fixture(scope)
                e['shared_inventory'],e['shared_routes']=inv,routes
                e['shared_guard']={'target':e['target_binding'],'protected_daemon':'0'*64,
                                   'retired':replacement_scope(inv,routes)['selection']}
                scope['shared_host']['runtime_guard']=release.fingerprint(e['shared_guard'])
                neighbor_volumes=[name+'-neighbor-data',name+'-neighbor-config']
                for volume in neighbor_volumes:
                    docker('volume','create','--label','ht.cr1.test='+name,volume)
                    resources.append(('volume',volume))
                for project in ('hookah-production','markettap-stg','clubs-bot-stage','ai-bot-stage'):
                    neighbor='ht-cr1-neighbor-'+name[-12:]+'-'+project
                    docker('create','--pull','never','--platform','linux/amd64','--name',neighbor,'--network','none',
                           '--label','ht.cr1.test='+name,'--label','com.docker.compose.project='+project,
                           '--mount','type=volume,src='+neighbor_volumes[0]+',dst=/neighbor-data,volume-nocopy',
                           '--mount','type=volume,src='+neighbor_volumes[1]+',dst=/neighbor-config,readonly,volume-nocopy',
                           PROBE_IMAGE,'python','-c','pass')
                    resources.append(('container',neighbor))
                if Runtime is not DockerRuntime:
                    e['runtime'] = 'SCOPED_COMPOSE'
                    scope['tooling'] = Runtime(e).clean_tooling(timeout=10)
                e['shared_guard']['protected_daemon']=Runtime(e).protected_daemon()
                scope['shared_host']['runtime_guard']=release.fingerprint(e['shared_guard'])
                # Real daemon observations, with only inspect array ordering varied
                # deterministically (Docker itself need not happen to reorder them).
                guard_runtime=Runtime(e)
                inspect_json=guard_runtime.json
                def reordered_inspect(*args, **kwargs):
                    value=inspect_json(*args, **kwargs)
                    if args[0]=='inspect':
                        value.reverse()
                        for container in value:container['Mounts'].reverse()
                    return value
                with patch.object(guard_runtime,'json',side_effect=reordered_inspect):
                    guard_runtime.shared_guard()
                def changed_mount(*args, **kwargs):
                    value=reordered_inspect(*args, **kwargs)
                    if args[0]=='inspect':
                        neighbor_item=next(c for c in value if c['Name']=='/'+neighbor)
                        self.assertEqual(len(neighbor_item['Mounts']),2)
                        neighbor_item['Mounts'][0]['RW']=not neighbor_item['Mounts'][0]['RW']
                    return value
                with patch.object(guard_runtime,'json',side_effect=changed_mount):
                    with self.assertRaisesRegex(release.Refused,'PROTECTED_RESOURCE_DRIFT'):
                        guard_runtime.shared_guard()
                print('CR1 mounted neighbors: inspect reorder PASS; real-field drift BLOCKED',flush=True)
                initialize_custody(e)
                registration = target/'registration.json'; write_json(registration,e)
                script = str(Path(__file__).with_name('deploy-staging.sh'))
                def cli(action, candidate=None, expected=None, configuration=None, prepare=False):
                    argv = ['bash',script,'preflight' if prepare else action,'--local-registration',str(registration)]
                    if prepare: argv += ['--operation',action]
                    if candidate:
                        path=outside/'candidate.json';write_json(path,candidate);argv += ['--candidate',str(path)]
                    if expected: argv += ['--expected-current',expected]
                    if configuration: argv += ['--configuration',configuration]
                    if not prepare: argv += ['--smoke-passed' if action=='accept' else '--owner-approved']
                    p=subprocess.run(argv,capture_output=True,timeout=360)
                    value=json.loads(p.stdout or p.stderr)
                    return value
                def approved(action, candidate=None, expected=None, configuration=None):
                    pre=cli(action,candidate,expected,configuration,True)
                    self.assertEqual(pre['status'],'SUCCESS',pre)
                    issue(e,pre['request'],private)
                    result=cli(action,candidate,expected,configuration)
                    diagnostic = release.read_json(target/'.staging-release/state.json').get('diagnostic') if (target/'.staging-release/state.json').exists() else None
                    if diagnostic == 'NETWORK_CONFIGURATION_CHANGED':
                        net=json.loads(docker('network','inspect',Runtime(e).network_name))[0]
                        diagnostic=(diagnostic,net['Internal'],net['Driver'],net.get('Options'))
                    if diagnostic == 'EXECUTION_ENV_CHANGED':
                        diffs=[]
                        runtime=Runtime(e)
                        for suffix in ('pg','backend','probe'):
                            container=json.loads(docker('inspect',name+'-'+suffix))[0]
                            image_config=json.loads(docker('image','inspect',container['Image']))[0]['Config']
                            expected_env=dict(v.split('=',1) for v in image_config.get('Env',[]))
                            if suffix=='pg':expected_env.update(POSTGRES_DB='fixture',POSTGRES_USER='fixture',POSTGRES_PASSWORD=base_env['DB_PASSWORD'])
                            if suffix=='backend':expected_env.update(runtime.configuration(container['Config']['Labels']['ht.cr1.traffic'])|{'APP_VERSION':record['revision']})
                            actual_env=dict(v.split('=',1) for v in container['Config']['Env'])
                            diffs.append((suffix,sorted(set(expected_env)-set(actual_env)),sorted(set(actual_env)-set(expected_env)),
                                          sorted(k for k in set(actual_env)&set(expected_env) if actual_env[k]!=expected_env[k]),
                                          len(container['Config']['Env']),len(expected_env)))
                        diagnostic=(diagnostic,diffs)
                    self.assertEqual(result['status'],'SUCCESS',(result,diagnostic))
                    print('CR1 CLI:',action,result['state'],flush=True)
                    return result
                pre=cli('install',record,prepare=True);self.assertEqual(pre['status'],'SUCCESS',pre)
                bundle=issue(e,pre['request'],private);del bundle['provider'];write_json(Path(e['evidence']),bundle)
                self.assertEqual(cli('install',record)['reason'],'INDEPENDENT_ROLES_REQUIRED')
                self.assertNotIn(Runtime(e).volume_name,docker('volume','ls','--format','{{.Name}}').decode().splitlines())
                self.assertEqual(approved('install',record)['state'],'CLOSED_V126')
                runtime=Runtime(e)
                first=runtime.clean_observe(timeout=10)
                pg_id=runtime.inspect(name+'-pg')['Id']
                protected_before=runtime.protected_daemon()
                self.assertEqual(sum(not row['success'] for row in first['history']),0)
                self.assertEqual(len(first['history']),126)
                with release.Store(target/'.staging-release',migration_target=target).locked():
                    self.assertEqual(cli('status')['reason'],'DEPLOYMENT_BUSY')
                self.assertEqual(cli('install',record)['reason'],'INSTALLATION_ALREADY_EXISTS')
                self.assertEqual(cli('public-admit',configuration=release.fingerprint(runtime.configuration('PUBLIC')))['status'],'FAILED')
                self.assertEqual(approved('test-admit',configuration=release.fingerprint(runtime.configuration('SMOKE')))['state'],'TEST_ADMITTED')
                self.assertEqual(approved('accept')['state'],'AWAITING_PUBLIC_HANDOFF')
                public_config=release.fingerprint(runtime.configuration('PUBLIC'))
                request=cli('public-admit',configuration=public_config,prepare=True)['request']
                issue(e,request,private,lambda p:p['owner'].update(approved=False))
                self.assertEqual(cli('public-admit',configuration=public_config)['reason'],'OWNER_APPROVAL_REQUIRED')
                self.assertEqual(Runtime(e).clean_observe(timeout=10)['traffic'],'SMOKE')
                self.assertEqual(approved('public-admit',configuration=release.fingerprint(runtime.configuration('PUBLIC')))['state'],'ACCEPTED')
                runtime=Runtime(e)
                runtime.sql("insert into users(telegram_user_id,first_name) values(340000001,'HT34 retained user')")
                retained=runtime.sql('select row_to_json(u) from users u where telegram_user_id=340000001')
                # Fixture-derived immutable images, not publishable release artifacts:
                # app code/source remain the pinned base; SQL overlay is explicit test data.
                build=outside/'build';build.mkdir()
                base_tag=name+'-base:'+REVISION;docker('tag',BACKEND,base_tag);tags.append(base_tag)
                def build_image(variant, parent):
                    tag=name+'-'+variant+':'+REVISION
                    text='FROM '+base_tag+'\nLABEL ht.cr1.fixture.variant="'+variant+'"\n'
                    if variant=='forward':
                        patched=build/'app.jar'
                        with zipfile.ZipFile(__import__('io').BytesIO(jar)) as source, zipfile.ZipFile(patched,'w') as dest:
                            for entry in source.infolist(): dest.writestr(entry,source.read(entry.filename))
                            dest.writestr('db/migration/postgresql/V127__cr1_runtime_fixture.sql','ALTER TABLE users ADD COLUMN cr1_fixture_note text;\n')
                        text+='COPY app.jar /app/app/lib/app-0.1.0.jar\n'
                        (migrations/'V127__cr1_runtime_fixture.sql').write_text('ALTER TABLE users ADD COLUMN cr1_fixture_note text;\n')
                        qualifier.migrate('fixture')
                    (build/'Dockerfile').write_text(text)
                    docker('build','--pull=false','--network=none','--platform','linux/amd64',
                           '--provenance=false','--sbom=false','-t',tag,str(build),timeout=180)
                    tags.append(tag)
                    identity=json.loads(docker('image','inspect',tag))[0]['Id']
                    archive=outside/(variant+'.tar');docker('image','save','-o',str(archive),tag)
                    archive.chmod(0o600)
                    verified=subprocess.run([sys.executable,'-B',str(Path(__file__).with_name('docker-save-archive.py')),
                        'verify',str(archive),'--expected-tag',tag,'--expected-image-id',identity,'--expected-revision',REVISION],
                        capture_output=True,text=True,timeout=30)
                    self.assertEqual(verified.returncode,0,verified.stderr)
                    candidate=copy.deepcopy(parent)
                    candidate.update(id=identity,archive=hashlib.sha256(archive.read_bytes()).hexdigest(),
                                     manifest=migration_manifest(archive_jar(archive)),schema=qualifier.schema())
                    e['images'][identity]={'record':candidate,'archive':str(archive),'tag':tag}
                    write_json(registration,e)
                    return candidate
                app=build_image('app',record)
                self.assertEqual(approved('deploy',app,record['id'])['state'],'READY_FOR_SMOKE')
                approved('accept')
                runtime=Runtime(e);self.assertEqual(runtime.clean_observe(timeout=10)['history'],first['history'])
                forward=build_image('forward',app)
                self.assertEqual(approved('deploy',forward,app['id'])['state'],'READY_FOR_SMOKE')
                approved('accept')
                runtime=Runtime(e);self.assertEqual(len(runtime.clean_observe(timeout=10)['history']),127)
                self.assertEqual(json.loads(retained),json.loads(runtime.sql("select row_to_json(u)::jsonb-'cr1_fixture_note' from users u where telegram_user_id=340000001")))
                self.assertEqual(runtime.inspect(name+'-pg')['Id'],pg_id)
                self.assertEqual(runtime.clean_observe(timeout=10)['database'],first['database'])
                self.assertEqual(runtime.protected_daemon(),protected_before)
                self.assertEqual(cli('rollback')['reason'],'SCHEMA_INCOMPATIBLE_USE_FORWARD_FIX')
                self.assertEqual(cli('status')['state'],'ACCEPTED')
                for suffix in ('pg','probe'):
                    docker('network','connect',qname,name+'-'+suffix)
                    self.assertIn(cli('status')['reason'],('CONTAINER_ISOLATION_CHANGED','PROTECTED_RESOURCE_DRIFT'))
                    docker('network','disconnect',qname,name+'-'+suffix)
                # Unexpected network writer is detected by a fresh worker process.
                rogue=name+'-rogue'
                docker('run','-d','--pull','never','--platform','linux/amd64','--name',rogue,'--network',Runtime(e).network_name,
                       '--label','ht.cr1.test='+name,PROBE_IMAGE,'python','-c','import time;time.sleep(600)')
                resources.append(('container',rogue))
                self.assertEqual(cli('status')['reason'],'UNEXCLUDED_WRITER')
            finally:
                # Only random test-owned labels/tags, never generic prune/reset.
                ids=docker('ps','-aq','--filter','label=ht.cr1.local='+name).decode().split()
                for identity in ids:
                    item=json.loads(docker('inspect',identity))[0]
                    self.assertEqual(item['Config']['Labels']['ht.cr1.local'],name)
                    docker('rm','--force',identity)
                # Extra-writer fixtures must be stopped before removing the
                # installation network they deliberately joined.
                for kind,identity in resources:
                    if kind=='container':
                        item=json.loads(docker('inspect',identity))[0]
                        self.assertEqual(item['Config']['Labels'].get('ht.cr1.test'),name)
                        docker('container','rm','--force',identity)
                for kind in ('volume','network'):
                    matches=docker(kind,'ls','--filter','label=ht.cr1.local='+name,'--format','{{.Name}}').decode().split()
                    for identity in matches: docker(kind,'rm',identity)
                for kind,identity in sorted(resources,key=lambda x:{'container':0,'volume':1,'network':2}[x[0]]):
                    if kind=='container': continue
                    item=json.loads(docker(kind,'inspect',identity))[0]
                    labels=item['Config']['Labels'] if kind=='container' else item['Labels']
                    self.assertEqual(labels.get('ht.cr1.test'),name)
                    docker(kind,'rm',*(['--force'] if kind=='container' else []),identity)
                for tag in tags: docker('image','rm',tag)


if __name__ == '__main__':
    unittest.main()
