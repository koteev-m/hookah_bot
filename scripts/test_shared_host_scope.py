"""Offline shared-host selection tests; named neighbours are protected fixtures."""
import copy
import unittest

import clean_install_policy as policy
import staging_release as release
from shared_host_scope import PROJECT, ROOT, HOST, PROJECT_LABEL, replacement_scope, check_selection, check_caddy_delta
from test_clean_install_release import fixture


def inventory_fixture():
    projects=[PROJECT,'hookah-production','markettap-stg','clubs-bot-stage','ai-bot-stage']
    value={'schema':'HT35_STRUCTURAL_INVENTORY_V1','errors':[],
           'host':{'hostname':'afraid-amber.ptr.network'},'engine':{'Name':'afraid-amber.ptr.network','ID':'fixture-engine'},
           'compose_projects':[],'containers':[],'networks':[],'volumes':[],
           'hookah_metadata':[{'path':ROOT,'realpath':ROOT,'directory':True,'uid':0}],
           'opt_directories':['hookah-bot','hookah-bot-production','markettap-stg','clubs-bot-stage','ai-bot']}
    for i,project in enumerate(projects):
        root=ROOT if project==PROJECT else '/opt/'+project
        net=project+'_default';vol=project+'_pgdata';mountpoint='/var/lib/docker/volumes/'+vol+'/_data'
        value['compose_projects'].append({'Name':project,'ConfigFiles':root+'/docker-compose.yml'})
        ids=[]
        for n,service in enumerate(('backend','postgres')):
            identity=format(i*2+n+1,'064x');ids.append(identity)
            port='8080' if service=='backend' else '5432'
            value['containers'].append({'id':identity,'name':project+'-'+service+'-1','state':'running',
                'labels':{PROJECT_LABEL:project,'com.docker.compose.service':service,'com.docker.compose.project.working_dir':root,
                          'com.docker.compose.project.config_files':root+'/docker-compose.yml','com.docker.compose.oneoff':'False'},
                'networks':{net:{'id':format(i+100,'064x')}},'network_mode':net,'privileged':False,'docker_socket_mount':False,
                'cap_add':None,'pid_mode':'','ports':{port+'/tcp':[{'HostIp':'127.0.0.1','HostPort':port}]} if project==PROJECT else {},
                'mounts':[] if service=='backend' else [{'Type':'volume','Name':vol,'Source':mountpoint,
                 'Destination':'/var/lib/postgresql/data','RW':True}]})
        value['networks'].append({'Name':net,'Id':format(i+100,'064x'),'Driver':'bridge','labels':{PROJECT_LABEL:project},'containers':ids})
        value['volumes'].append({'Name':vol,'Mountpoint':mountpoint,'Driver':'local','options_present':False,'labels':{PROJECT_LABEL:project}})
    routes={'errors':[],'caddy_active_routes':[{'hosts':[HOST],'paths':[],'upstreams':['127.0.0.1:8080']},
              {'hosts':['neighbour.invalid'],'paths':[],'upstreams':['127.0.0.1:18082']}]}
    return value,routes


class SharedHostScopeTests(unittest.TestCase):
    def setUp(self):self.inv,self.routes=inventory_fixture()
    def refused(self,reason):
        with self.assertRaisesRegex(release.Refused,reason):replacement_scope(self.inv,self.routes)

    def test_exact_staging_only_and_production_protected(self):
        before=copy.deepcopy(self.inv);plan=replacement_scope(self.inv,self.routes)
        self.assertEqual(len(plan['selection']['containers']),2)
        self.assertEqual(plan['selection']['volumes'],['hookah-bot_pgdata'])
        self.assertIn('hookah-production',plan['protected']['projects'])
        self.assertEqual(len(plan['protected']['containers']),8)
        self.assertEqual(self.inv,before)
        self.assertFalse(check_selection(self.inv,self.routes,plan,plan['selection'])['execution_authorized'])

    def test_unknown_project_is_protected_not_implicitly_owned(self):
        self.inv['compose_projects'].append({'Name':'unlisted','ConfigFiles':'/opt/unlisted/compose.yml'})
        c=copy.deepcopy(self.inv['containers'][-1]);c['id']='f'*64;c['labels'][PROJECT_LABEL]='unlisted';self.inv['containers'].append(c)
        self.assertIn('unlisted',replacement_scope(self.inv,self.routes)['protected']['projects'])

    def test_prefix_label_is_not_ownership(self):
        self.inv['containers'][0]['labels'][PROJECT_LABEL]='hookah-bot-extra'
        self.refused('PROJECT_INVENTORY_MISMATCH')

    def test_foreign_workdir_or_config_refused(self):
        for key in ('com.docker.compose.project.working_dir','com.docker.compose.project.config_files'):
            with self.subTest(key=key):
                inv=copy.deepcopy(self.inv);inv['containers'][0]['labels'][key]='/opt/hookah-bot-production'
                with self.assertRaisesRegex(release.Refused,'CONTAINER_OWNERSHIP_AMBIGUOUS'):replacement_scope(inv,self.routes)

    def test_shared_volume_stopped_foreign_container_refused(self):
        self.inv['containers'][3]['state']='exited'
        self.inv['containers'][3]['mounts']=copy.deepcopy(self.inv['containers'][1]['mounts'])
        self.refused('SHARED_STORAGE')

    def test_bind_alias_of_volume_or_parent_refused(self):
        for source in ['/var/lib/docker','/opt','/opt/hookah-bot/.staging-release']:
            with self.subTest(source=source):
                inv=copy.deepcopy(self.inv);inv['containers'][3]['mounts']=[{'Type':'bind','Source':source}]
                with self.assertRaisesRegex(release.Refused,'SHARED_STORAGE'):replacement_scope(inv,self.routes)

    def test_shared_network_refused(self):
        self.inv['containers'][2]['networks'][PROJECT+'_default']={'id':self.inv['networks'][0]['Id']}
        self.refused('SHARED_NETWORK')

    def test_unknown_network_endpoint_refused(self):
        self.inv['networks'][0]['containers'].append('e'*64)
        self.refused('NETWORK_ENDPOINT_MISMATCH')

    def test_foreign_port_collision_refused(self):
        self.inv['containers'][2]['ports']={'80/tcp':[{'HostIp':'0.0.0.0','HostPort':'8080'}]}
        self.refused('SHARED_PORT')

    def test_privileged_or_socket_target_refused(self):
        for key in ('privileged','docker_socket_mount'):
            inv=copy.deepcopy(self.inv);inv['containers'][0][key]=True
            with self.assertRaisesRegex(release.Refused,'TARGET_HOST_AUTHORITY'):replacement_scope(inv,self.routes)

    def test_volume_driver_options_or_path_refused(self):
        self.inv['volumes'][0]['options_present']=True;self.refused('RESOURCE_OWNERSHIP_AMBIGUOUS')
        self.inv['volumes'][0]['options_present']=False;self.inv['volumes'][0]['Mountpoint']='/opt/neighbour/data'
        self.refused('TARGET_STORAGE_PATH_CHANGED')

    def test_extra_owned_volume_requires_classification(self):
        v=copy.deepcopy(self.inv['volumes'][0]);v['Name']='hookah-bot_old';self.inv['volumes'].append(v)
        self.refused('UNCLASSIFIED_TARGET_RESOURCE')

    def test_incomplete_inventory_duplicate_or_host_drift_refused(self):
        self.inv['errors']=['failed read'];self.refused('INCOMPLETE_INVENTORY')
        self.inv['errors']=[];self.inv['containers'].append(copy.deepcopy(self.inv['containers'][0]));self.refused('DUPLICATE_RESOURCE_IDENTITY')
        self.inv['containers'].pop();self.inv['host']['hostname']='other';self.refused('HOST_IDENTITY_MISMATCH')

    def test_new_resource_collision_refused(self):
        self.inv['opt_directories'].append('hookah-bot-v126');self.refused('SUCCESSOR_RESOURCE_COLLISION')

    def test_shared_upstream_refused(self):
        self.routes['caddy_active_routes'][1]['upstreams']=['127.0.0.1:8080'];self.refused('SHARED_UPSTREAM')

    def test_multi_host_route_refused(self):
        self.routes['caddy_active_routes'][0]['hosts'].append('neighbour.invalid');self.refused('TARGET_ROUTE_AMBIGUOUS')

    def test_foreign_selection_and_global_actions_refused(self):
        plan=replacement_scope(self.inv,self.routes)
        for kind,entry in [('containers',self.inv['containers'][2]['id']),('volumes','hookah-production_pgdata'),('networks','bridge')]:
            selection=copy.deepcopy(plan['selection']);selection[kind].append(entry)
            with self.assertRaisesRegex(release.Refused,'FOREIGN_RESOURCE_SELECTED'):check_selection(self.inv,self.routes,plan,selection)
        for key in ('prune','files','images','systemd','compose_down'):
            selection=copy.deepcopy(plan['selection']);selection[key]=[]
            with self.assertRaisesRegex(release.Refused,'RESOURCE_KIND_NOT_ALLOWED'):check_selection(self.inv,self.routes,plan,selection)

    def test_fresh_inventory_drift_and_plan_tamper_refused(self):
        plan=replacement_scope(self.inv,self.routes)
        self.inv['containers'][2]['state']='exited'
        with self.assertRaisesRegex(release.Refused,'SCOPE_DRIFT_OR_TAMPERING'):check_selection(self.inv,self.routes,plan,plan['selection'])
        self.inv['containers'][2]['state']='running';plan['execution_authorized']=True
        with self.assertRaisesRegex(release.Refused,'SCOPE_DRIFT_OR_TAMPERING'):check_selection(self.inv,self.routes,plan,plan['selection'])

    def test_inventory_plan_cannot_be_cr1_authority_or_scope(self):
        plan=replacement_scope(self.inv,self.routes);scope,_=fixture()
        with self.assertRaisesRegex(release.Refused,'CLEAN_SCOPE_INVALID'):policy.scope(plan,release.require,release.digest)
        with self.assertRaisesRegex(release.Refused,'AUTHORITY_REQUEST_MISMATCH'):
            policy.evidence(plan,{'scope':scope},scope,release.require,release.digest,release.fingerprint)


class CaddyDeltaTests(unittest.TestCase):
    def setUp(self):
        self.config={'admin':{'listen':'localhost:2019'},'apps':{'http':{'servers':{'s':{'routes':[
          {'match':[{'host':[HOST]}],'handle':[{'handler':'reverse_proxy','upstreams':[{'dial':'127.0.0.1:8080'}]}]},
          {'match':[{'host':['neighbour.invalid']}],'handle':[{'handler':'reverse_proxy','upstreams':[{'dial':'127.0.0.1:18082'}]}]}
        ]}}}}}
    def test_only_exact_hookah_dial_allowed(self):
        after=copy.deepcopy(self.config);after['apps']['http']['servers']['s']['routes'][0]['handle'][0]['upstreams'][0]['dial']='127.0.0.1:18090'
        self.assertFalse(check_caddy_delta(self.config,after,new_port=18090,occupied_ports={18082})['execution_authorized'])
    def test_neighbour_admin_or_header_change_refused(self):
        for variant in ('neighbour','admin','header'):
            after=copy.deepcopy(self.config)
            if variant=='neighbour':after['apps']['http']['servers']['s']['routes'][1]['handle'][0]['upstreams'][0]['dial']='127.0.0.1:8080'
            elif variant=='admin':after['admin']['listen']='0.0.0.0:2019'
            else:after['apps']['http']['servers']['s']['routes'][0]['handle'].append({'handler':'headers'})
            with self.assertRaisesRegex(release.Refused,'FOREIGN_CADDY_CHANGE'):check_caddy_delta(self.config,after,new_port=8080,occupied_ports={18082})
    def test_foreign_port_and_shared_host_match_refused(self):
        with self.assertRaisesRegex(release.Refused,'FOREIGN_OR_INVALID_PORT'):check_caddy_delta(self.config,self.config,new_port=18082,occupied_ports={18082})
        self.config['apps']['http']['servers']['s']['routes'][0]['match'][0]['host'].append('neighbour.invalid')
        with self.assertRaisesRegex(release.Refused,'TARGET_ROUTE_AMBIGUOUS'):check_caddy_delta(self.config,self.config,new_port=8080,occupied_ports=set())


# Explicit local fixture authors; never imported by production modules.
def scoped_fixture(scope, runtime_guard='a'*64):
    from shared_host_scope import scoped_binding
    inv, routes = inventory_fixture()
    admins = [{'identity':'1'*64, 'owner':'2'*64,
               'capabilities':['CADDY','DOCKER','PROVIDER','ROOT','SSH']}]
    scope['origin'] = policy.SCOPED_ORIGIN
    scope['shared_host'] = scoped_binding(inv, routes, admins, runtime_guard)
    return inv, routes


def scoped_proof(scope, request, owner='6'*64, custody='7'*64):
    return {'binding': release.fingerprint(scope['shared_host']), 'installation':scope['installation'],
            'incarnation':scope['incarnation'], 'inventory_complete':True,
            'host_admins':copy.deepcopy(scope['shared_host']['retained_admins']), 'admin_trust_approval':owner,
            'retirement':{'state':'OLD_HOOKAH_RETIRED','plan':scope['shared_host']['resource_plan'],
                          'receipt':'8'*64,'writers':[],'executors':[],'delayed_effects':[],
                          'historical_run724':'UNKNOWN','inherited_authority':[]},
            'resources':{'state':'NEW_HOOKAH_RESOURCES_VERIFIED','retirement':'8'*64,
                         'guard':scope['shared_host']['runtime_guard'],
                         'database':release.fingerprint(request['before']['database']),'receipt':'9'*64},
            'custody_receipt':custody}


import test_clean_install_release as core


class ScopedAuthority(core.SyntheticAuthority):
    def authorize(self, request, *, timeout):
        proof = super().authorize(request, timeout=timeout)
        proof['facets']['provider']['verdict'] = 'RETAINED_DEPENDENCY'
        proof.pop('reset', None)
        proof['scoped'] = scoped_proof(self.scope, request)
        proof.update(copy.deepcopy(self.override))
        self.approved = release.fingerprint(request), release.fingerprint(proof)
        return proof


class ScopedReleaseTests(core.CleanReleaseTests):
    """Run the same ordinary controller regression contract under the new origin."""
    def setUp(self):
        super().setUp()
        scoped_fixture(self.scope)
        self.runtime.snapshot['resource_guard'] = self.scope['shared_host']['runtime_guard']
        self.authority = ScopedAuthority(self.scope)
        self.controller = release.Controller.clean_installation(self.scope, self.store, self.runtime, self.authority)

    def proof(self):
        request = self.controller.clean_action('BOOTSTRAP', candidate=self.image, prepare_only=True)
        return scoped_proof(self.scope, request)

    def test_incomplete_reset_and_external_effects_refuse_before_dispatch(self):
        # Full-reset origin keeps its old test. A reset cannot substitute for scoped retirement.
        proof = self.proof(); proof['retirement']['state'] = 'UNKNOWN'
        self.authority.override = {'reset':'a'*64, 'scoped':proof}
        self.refused('SCOPED_RETIREMENT_INCOMPLETE', self.bootstrap)
        self.assertEqual(self.runtime.plans, [])

    def test_partial_retirement_delayed_effects_and_inherited_receipts(self):
        for key, value in [('state','PARTIAL'),('state','UNKNOWN'),('writers',['old']),('executors',['old']),
                           ('delayed_effects',['pending']),('historical_run724','SUCCESS'),
                           ('inherited_authority',['run724'])]:
            with self.subTest(key=key, value=value):
                proof=self.proof();proof['retirement'][key]=value
                self.authority.override={'scoped':proof}
                self.refused('SCOPED_RETIREMENT_INCOMPLETE', self.bootstrap)
        self.assertEqual(self.runtime.plans, [])

    def test_unknown_admin_or_inventory_never_dispatches(self):
        for key,value in [('inventory_complete',False),('host_admins',[]),('admin_trust_approval',None)]:
            proof=self.proof();proof[key]=value;self.authority.override={'scoped':proof}
            self.refused('HOST_ADMIN_TRUST_UNPROVEN',self.bootstrap)
        self.assertEqual(self.authority.events,[])

    def test_resource_or_cessation_binding_substitution(self):
        for key in ('guard','retirement','database','receipt'):
            proof=self.proof();proof['resources'][key]=None;self.authority.override={'scoped':proof}
            self.refused('NEW_RESOURCES_UNPROVEN',self.bootstrap)
        for key in ('binding','installation','incarnation'):
            proof=self.proof();proof[key]='f'*64;self.authority.override={'scoped':proof}
            self.refused('SCOPED_EVIDENCE_BINDING_MISMATCH',self.bootstrap)
        self.assertEqual(self.runtime.plans, [])

    def test_scope_rejects_admin_boolean_or_missing_capability(self):
        for admins in (True, [], [{'identity':'1'*64,'owner':'2'*64,'capabilities':['DOCKER']}]):
            scope=copy.deepcopy(self.scope);scope['shared_host']['retained_admins']=admins
            with self.assertRaises(release.Refused):
                release.Controller.clean_installation(scope,self.store,self.runtime,self.authority)

    def test_lost_shared_guard_blocks_bootstrap(self):
        self.runtime.snapshot['resource_guard']='f'*64
        self.refused('RESOURCE_GUARD_CHANGED',self.bootstrap)
        self.assertEqual(self.runtime.plans,[])

    def test_external_effects_and_expired_authority(self):
        for override,reason in [({'pending_effects':['pending']},'LEGACY_EFFECTS_UNRESOLVED'),
                                ({'external_writers':['root']},'LEGACY_EFFECTS_UNRESOLVED'),
                                ({'expires':0},'AUTHORITY_EXPIRED'),
                                ({'historical_run724':'SUCCESS'},'AUTHORITY_IDENTITY_MISMATCH')]:
            self.authority.override=override;self.refused(reason,self.bootstrap)
        self.assertEqual(self.runtime.plans,[])


if __name__ == '__main__':unittest.main()
