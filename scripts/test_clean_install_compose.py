"""Executor-only tests; synthetic inventory is not AEZA authority evidence."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from clean_install_compose import ComposeRuntime, successor_layout
from staging_release import ProtectedFiles, Refused
from test_clean_install_release import fixture
from test_shared_host_scope import scoped_fixture


class ComposeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.r = object.__new__(ComposeRuntime)
        self.r.root = Path(self.temp.name).resolve()
        self.r.name = 'ht-cr1-local-123456789abc'
        self.r.scope, self.image = fixture()
        scoped_fixture(self.r.scope)
        self.r.e = {'images': {self.image['id']: {'record': self.image}}}
        self.r.files = ProtectedFiles(owner=os.geteuid())
        self.r.base = {'DB_PASSWORD': 'test-${HOME}-$UNKNOWN', 'TELEGRAM_BOT_ENABLED': 'false'}
        self.r.docker = '/fixture/docker'
        self.r.compose = '/fixture/docker-compose'
        self.r.environment = {'PATH': '/usr/bin:/bin', 'HOME': str(self.r.root), 'DOCKER_HOST': 'unix:///fixture'}
        self.r.compose_identity = 'a'*64
        self.r.compose_tooling = lambda: 'a'*64
        self.r.shared_guard = lambda: None

    def argv(self, role='backend', traffic='CLOSED'):
        env = (dict(POSTGRES_DB='fixture', POSTGRES_USER='fixture', POSTGRES_PASSWORD=self.r.base['DB_PASSWORD'])
               if role == 'pg' else self.r.configuration(traffic) | {'APP_VERSION': self.image['revision']})
        path = self.r.root / '.env-test'
        path.write_text(''.join(k+'='+v+'\n' for k,v in env.items())); path.chmod(0o600)
        args = ('run','-d','--pull','never','--name',self.r.name+'-'+role,'--network',self.r.network_name,
                '--restart','no',*self.r.labels(),'--env-file',str(path))
        if role == 'pg':
            return (*args,'--platform',self.r.pg_platform,'--network-alias','postgres','--mount',
                    'type=volume,src='+self.r.volume_name+',dst=/var/lib/postgresql/data',self.r.pg_image)
        return (*args,'--platform','linux/amd64','--cap-drop','ALL','--security-opt','no-new-privileges',
                '--label','ht.cr1.traffic='+traffic,self.image['id'])

    def test_external_network_volume_exact_single_service_and_no_publication(self):
        for role in ('pg','backend'):
            _, name, spec = self.r.specification(self.argv(role))
            service = next(iter(spec['services'].values()))
            self.assertEqual(len(spec['services']),1)
            self.assertEqual(spec['networks'],{'closed':{'external':True,'name':self.r.network_name}})
            self.assertNotIn('ports',service)
            self.assertNotIn('build',service)
            self.assertNotIn('privileged',service)
            self.assertEqual(service['pull_policy'],'never')
            self.assertEqual(service['restart'],'no')
            if role == 'pg':
                self.assertEqual(spec['volumes'],{'database':{'external':True,'name':self.r.volume_name}})
            else:
                self.assertNotIn('volumes',spec)
                self.assertEqual(service['environment']['TELEGRAM_BOT_ENABLED'],'false')

    def test_compose_interpolation_cannot_substitute_secret_bytes(self):
        _,_,spec = self.r.specification(self.argv('pg'))
        self.assertEqual(spec['services']['postgres']['environment']['POSTGRES_PASSWORD'],'test-$${HOME}-$$UNKNOWN')

    def test_foreign_project_mount_port_and_arbitrary_options_refused(self):
        args = self.argv()
        for changed in (args+('--privileged',), args+('--publish','8080:8080'),
                        tuple('hookah-production-backend' if a==self.r.name+'-backend' else a for a in args),
                        tuple('hookah-production_default' if a==self.r.network_name else a for a in args)):
            with self.subTest(changed=changed), self.assertRaises(Refused): self.r.specification(changed)
        pg = self.argv('pg')
        foreign = tuple(a.replace(self.r.volume_name,'hookah-production_pgdata') for a in pg)
        with self.assertRaises(Refused): self.r.specification(foreign)

    def test_arbitrary_compose_and_global_actions_cannot_dispatch(self):
        for args in (('compose','down'),('compose','up'),('system','prune'),('volume','rm',self.r.volume_name),
                     ('exec','hookah-production-postgres-1','psql'),('systemctl','restart','docker'),('caddy','reload')):
            with self.subTest(args=args), patch('clean_install_compose.subprocess.run') as run:
                with self.assertRaises(Refused): self.r.command(*args)
                run.assert_not_called()

    def test_environment_tampering_rejected_before_dispatch(self):
        args=self.argv()
        path=self.r.root/'.env-test'
        path.write_text(path.read_text().replace('TELEGRAM_BOT_ENABLED=false','TELEGRAM_BOT_ENABLED=true'))
        with patch('clean_install_compose.subprocess.run') as run:
            with self.assertRaisesRegex(Refused,'RUNTIME_CONFIG_CHANGED'):self.r.command(*args)
            run.assert_not_called()

    def test_unknown_image_rejected_before_dispatch(self):
        args=(*self.argv()[:-1],'sha256:'+'f'*64)
        with patch('clean_install_compose.subprocess.run') as run:
            with self.assertRaises(Refused):self.r.command(*args)
            run.assert_not_called()

    def test_lost_ack_is_not_retried_or_translated_to_success(self):
        args=self.argv()
        with patch('clean_install_compose.subprocess.run',side_effect=subprocess.TimeoutExpired('compose',1)) as run:
            with self.assertRaises(subprocess.TimeoutExpired):self.r.command(*args)
            self.assertEqual(run.call_count,1)

    def test_compose_timeout_is_durable_unknown_and_controller_blocks_replay(self):
        from staging_release import Controller, Store
        from test_clean_install_release import SyntheticAuthority, SyntheticRuntime
        scope,image=fixture()
        runtime=SyntheticRuntime(scope,image)
        authority=SyntheticAuthority(scope)
        store=Store(self.r.root/'state',migration_target=self.r.root)
        args=self.argv()
        runtime.clean_execute=lambda plan,before,timeout:self.r.command(*args)
        controller=Controller.clean_installation(scope,store,runtime,authority)
        with patch('clean_install_compose.subprocess.run',side_effect=subprocess.TimeoutExpired('compose',1)) as run:
            with self.assertRaisesRegex(Refused,'CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED'):
                controller.clean_action('BOOTSTRAP',candidate=image)
            self.assertEqual(store.read()['status'],'UNKNOWN')
            with self.assertRaisesRegex(Refused,'OPERATOR_RECONCILIATION_REQUIRED'):
                controller.clean_action('BOOTSTRAP',candidate=image)
            self.assertEqual(run.call_count,1)

    def test_protected_inventory_and_tooling_drift_block_before_dispatch(self):
        args=self.argv()
        with patch.object(self.r,'shared_guard',side_effect=Refused('PROTECTED_RESOURCE_DRIFT')), \
             patch('clean_install_compose.subprocess.run') as run:
            with self.assertRaisesRegex(Refused,'PROTECTED_RESOURCE_DRIFT'):self.r.command(*args)
            run.assert_not_called()
        self.r.compose_tooling=lambda:'b'*64
        with patch('clean_install_compose.subprocess.run') as run:
            with self.assertRaisesRegex(Refused,'COMPOSE_TOOLING_CHANGED'):self.r.command(*args)
            run.assert_not_called()

    def test_post_dispatch_drift_is_uncertain_not_success(self):
        args=self.argv()
        with patch.object(self.r,'shared_guard',side_effect=[None,Refused('PROTECTED_RESOURCE_DRIFT')]), \
             patch('clean_install_compose.subprocess.run',return_value=subprocess.CompletedProcess([],0,b'id',b'')) as run:
            with self.assertRaises(Refused):self.r.command(*args)
            self.assertEqual(run.call_count,1)

    def test_compose_command_uses_stdin_without_env_file_or_arbitrary_project(self):
        args=self.argv('pg')
        with patch('clean_install_compose.subprocess.run',return_value=subprocess.CompletedProcess([],0,b'id',b'')) as run:
            self.r.command(*args)
        command=run.call_args.args[0]
        self.assertEqual(command[-9:],['run','--no-deps','--pull','never','--use-aliases','-d','--name',self.r.name+'-pg','postgres'])
        self.assertEqual(command[command.index('--env-file')+1],'/dev/null')
        self.assertEqual(command[command.index('--file')+1],'-')
        self.assertEqual(command[command.index('--project-name')+1],self.r.name)
        self.assertEqual(json.loads(run.call_args.kwargs['input'])['volumes']['database']['name'],self.r.volume_name)

    def test_compose_labels_do_not_grant_ownership(self):
        labels={'ht.cr1.local':self.r.name,'ht.cr1.incarnation':self.r.scope['incarnation'],
                'com.docker.compose.project':self.r.name,'com.docker.compose.service':'backend',
                'com.docker.compose.oneoff':'True','com.docker.compose.project.working_dir':str(self.r.root)}
        item={'Name':'/'+self.r.name+'-backend','Config':{'Labels':labels}}
        self.r.owned(item)
        for key,value in [('com.docker.compose.project','hookah-production'),('com.docker.compose.service','postgres'),
                          ('com.docker.compose.oneoff','False'),('com.docker.compose.project.working_dir','/opt/foreign')]:
            changed=copy.deepcopy(item);changed['Config']['Labels'][key]=value
            with self.assertRaisesRegex(Refused,'COMPOSE_IDENTITY_CHANGED'):self.r.owned(changed)

    def test_server_dispatch_closed_before_io_even_with_owner_flag_and_fake_signatures(self):
        with patch('clean_install_compose.subprocess.run') as run:
            for profile in ('PRODUCTION','SHARED_HOST_HOOKAH_SCOPED_REPLACEMENT',None):
                with self.assertRaisesRegex(Refused,'BLOCKED_EXTERNAL_ENROLLMENT'):
                    ComposeRuntime({'profile':profile,'runtime':'SCOPED_COMPOSE','owner_approved':True,'signature':'fixture'})
            run.assert_not_called()

    def test_successor_layout_matches_existing_selector(self):
        from shared_host_scope import replacement_scope
        scope,_=fixture();inv,routes=scoped_fixture(scope)
        plan=replacement_scope(inv,routes)
        # Check the existing selector rather than introduce a second naming policy.
        expected=successor_layout()
        self.assertTrue(any(isinstance(v,dict) and all(v.get(k)==val for k,val in expected.items()) for v in plan.values()))

    def test_compose_geometry_protects_new_volume_and_complete_neighbor_inventory(self):
        import test_clean_install_runtime as guards
        fixture=guards.SharedDaemonGuardTests()
        fixture.setUp()
        fixture.r.__class__=ComposeRuntime
        fixture.r.shared_guard()
        fixture.items[0]['Mounts']=[{'Name':fixture.r.volume_name}]
        with self.assertRaisesRegex(Refused,'OLD_OR_FOREIGN_VOLUME_CONSUMER'):fixture.r.shared_guard()
        fixture.items[0]['Mounts']=[]
        fixture.items[0]['Config']['Labels']['com.docker.compose.project']='hookah-bot'
        with self.assertRaisesRegex(Refused,'OLD_EXECUTOR_PRESENT'):fixture.r.shared_guard()
        fixture.items[0]['Config']['Labels']['com.docker.compose.project']='hookah-production'
        fixture.vols.append({'Name':'unknown-volume'})
        with self.assertRaisesRegex(Refused,'PROTECTED_RESOURCE_DRIFT'):fixture.r.shared_guard()
        fixture.vols.pop()
        fixture.r.shared_guard()


if __name__ == '__main__':
    unittest.main()
