"""Read-only Hookah staging resource selection; NOT CR-1 authority or an executor.

Snapshots are inventory evidence, never proof of writer cessation. Shared-host
enrollment is unavailable; this module cannot dispatch Docker/SSH or authorize it.
"""
import copy
from pathlib import PurePosixPath
import re

from staging_release import fingerprint, require

PROJECT = 'hookah-bot'
ROOT = '/opt/hookah-bot'
HOST = 'staging.hookahtootah.club'
PROJECT_LABEL = 'com.docker.compose.project'


def index(items, key):
    require(isinstance(items, list), 'INCOMPLETE_INVENTORY')
    result = {item[key]: item for item in items}
    require(len(result) == len(items), 'DUPLICATE_RESOURCE_IDENTITY')
    return result


def overlap(a, b):
    a, b = PurePosixPath(a), PurePosixPath(b)
    return a == b or a in b.parents or b in a.parents


def replacement_scope(inventory, routes):
    require(inventory.get('schema') == 'HT35_STRUCTURAL_INVENTORY_V1'
            and inventory.get('errors') == [] and routes.get('errors') == [], 'INCOMPLETE_INVENTORY')
    require(inventory['host']['hostname'] == 'afraid-amber.ptr.network'
            and inventory['engine']['Name'] == inventory['host']['hostname'], 'HOST_IDENTITY_MISMATCH')
    projects = index(inventory['compose_projects'], 'Name')
    containers = index(inventory['containers'], 'id')
    networks = index(inventory['networks'], 'Name')
    volumes = index(inventory['volumes'], 'Name')
    require(PROJECT in projects and projects[PROJECT]['ConfigFiles'] == ROOT+'/docker-compose.yml', 'TARGET_PROJECT_MISMATCH')
    require(set(projects) == {c['labels'].get(PROJECT_LABEL) for c in containers.values()
                              if c['labels'].get(PROJECT_LABEL)}, 'PROJECT_INVENTORY_MISMATCH')
    require(any(m.get('path') == ROOT and m.get('realpath') == ROOT and m.get('directory')
                and m.get('uid') == 0 for m in inventory['hookah_metadata']), 'TARGET_PATH_UNPROVEN')
    selected = [c for c in containers.values() if c['labels'].get(PROJECT_LABEL) == PROJECT]
    require(len(selected) == 2 and {c['labels'].get('com.docker.compose.service') for c in selected}
            == {'backend','postgres'}, 'TARGET_SERVICES_CHANGED')
    own = {c['id'] for c in selected}
    for c in selected:
        labels = c['labels']
        require(re.fullmatch('[0-9a-f]{64}',c['id']) and labels.get('com.docker.compose.project.working_dir') == ROOT
                and labels.get('com.docker.compose.project.config_files') == ROOT+'/docker-compose.yml'
                and labels.get('com.docker.compose.oneoff') == 'False', 'CONTAINER_OWNERSHIP_AMBIGUOUS')
        require(not c['privileged'] and not c['docker_socket_mount'] and not c['cap_add']
                and c['pid_mode'] == '' and c['network_mode'] == PROJECT+'_default', 'TARGET_HOST_AUTHORITY')
        require(set(c['networks']) == {PROJECT+'_default'}, 'SHARED_NETWORK')
        service = labels['com.docker.compose.service']
        expected_port = '8080' if service == 'backend' else '5432'
        require(c['ports'] == {expected_port+'/tcp':[{'HostIp':'127.0.0.1','HostPort':expected_port}]}, 'TARGET_PORTS_CHANGED')
        require(c['mounts'] == [] if service == 'backend' else
                len(c['mounts']) == 1 and c['mounts'][0]['Type'] == 'volume'
                and c['mounts'][0]['Name'] == PROJECT+'_pgdata'
                and c['mounts'][0]['Destination'] == '/var/lib/postgresql/data', 'TARGET_MOUNTS_CHANGED')
    net = networks.get(PROJECT+'_default'); vol = volumes.get(PROJECT+'_pgdata')
    require(net is not None and vol is not None and net['labels'].get(PROJECT_LABEL) == PROJECT
            and vol['labels'].get(PROJECT_LABEL) == PROJECT and net['Driver'] == 'bridge'
            and vol['Driver'] == 'local' and not vol['options_present'], 'RESOURCE_OWNERSHIP_AMBIGUOUS')
    require(vol['Mountpoint'] == '/var/lib/docker/volumes/'+vol['Name']+'/_data'
            and all(m['Source'] == vol['Mountpoint'] and m['RW'] is True for c in selected for m in c['mounts']),
            'TARGET_STORAGE_PATH_CHANGED')
    require(set(net['containers']) == own and all(c['networks'][net['Name']]['id'] == net['Id'] for c in selected), 'NETWORK_ENDPOINT_MISMATCH')
    require({n['Name'] for n in networks.values() if n['labels'].get(PROJECT_LABEL) == PROJECT} == {net['Name']}
            and {v['Name'] for v in volumes.values() if v['labels'].get(PROJECT_LABEL) == PROJECT} == {vol['Name']},
            'UNCLASSIFIED_TARGET_RESOURCE')
    protected = [c for c in containers.values() if c['id'] not in own]
    for c in protected:
        require(net['Name'] not in c['networks'], 'SHARED_NETWORK')
        for mount in c['mounts']:
            require(mount.get('Name') != vol['Name'] and not
                    (mount.get('Source') and (overlap(mount['Source'],vol['Mountpoint']) or overlap(mount['Source'],ROOT))),
                    'SHARED_STORAGE')
        for bindings in c['ports'].values():
            require(all(b['HostPort'] not in ('8080','5432') for b in bindings or []), 'SHARED_PORT')
    active = routes['caddy_active_routes']
    own_routes = [r for r in active if HOST in r['hosts']]
    require(own_routes == [{'hosts':[HOST],'paths':[],'upstreams':['127.0.0.1:8080']}], 'TARGET_ROUTE_AMBIGUOUS')
    require(all('127.0.0.1:8080' not in r['upstreams'] for r in active if r not in own_routes), 'SHARED_UPSTREAM')
    successors = {'project':'hookah-staging-v126','directory':'/opt/hookah-bot-v126',
                  'network':'hookah-staging-v126_default','volume':'hookah-staging-v126_pgdata',
                  'initial_ports':[], 'public_port_after_separate_admission':'127.0.0.1:8080'}
    require(successors['project'] not in projects and successors['network'] not in networks
            and successors['volume'] not in volumes and PurePosixPath(successors['directory']).name not in inventory['opt_directories'],
            'SUCCESSOR_RESOURCE_COLLISION')
    plan = {'status':'PROPOSAL_ONLY','execution_authorized':False,'project_id':'Hookah_Tootah',
            'inventory_digest':fingerprint(inventory),'route_inventory_digest':fingerprint(routes),
            'target':{'project':PROJECT,'directory':ROOT,'host':inventory['host'],'engine_id':inventory['engine']['ID']},
            'selection':{'containers':sorted(own),'networks':[net['Name']],'volumes':[vol['Name']]},
            'protected':{'containers':sorted(c['id'] for c in protected),
                         'projects':sorted(set(projects)-{PROJECT}),
                         'networks':sorted(set(networks)-{net['Name']}),'volumes':sorted(set(volumes)-{vol['Name']}),
                         'routes':[r for r in active if r not in own_routes]},
            'shared_never_global':['OS','provider','Docker daemon/socket/image store','Caddy service/config/TLS','SSH/root identities','host firewall/DNS'],
            'preserve_paths':[ROOT,'/etc/hookah-bot'],
            'successor_proposal':successors,'policy_required':'SHARED_HOST_EXTERNAL_ENROLLMENT_REQUIRED'}
    plan['binding'] = fingerprint(plan)
    return plan


def check_selection(inventory, routes, plan, selection):
    """Recompute from whole fresh inventory. No broad names/prefixes or filesystem ops."""
    require(plan == replacement_scope(inventory,routes), 'SCOPE_DRIFT_OR_TAMPERING')
    require(isinstance(selection,dict) and set(selection) == set(plan['selection']), 'RESOURCE_KIND_NOT_ALLOWED')
    for kind, values in selection.items():
        require(isinstance(values,list) and len(set(values)) == len(values)
                and set(values) <= set(plan['selection'][kind]), 'FOREIGN_RESOURCE_SELECTED')
    return {'status':'PROPOSAL_ONLY','execution_authorized':False,'binding':plan['binding']}


def check_caddy_delta(before, after, *, new_port, occupied_ports):
    """Pure full-config comparison: only one exact Hookah upstream dial may change.

Not a reload/approval API. Full configs stay with the protected caller; no config
content, credentials, or header values are logged or returned.
    """
    require(type(new_port) is int and 1024 <= new_port <= 65535
            and (new_port == 8080 or new_port not in occupied_ports), 'FOREIGN_OR_INVALID_PORT')
    expected = copy.deepcopy(before)
    found = []
    def walk(value, hosts=()):
        if isinstance(value, dict):
            match_hosts = [h for m in value.get('match',[]) for h in m.get('host',[])]
            scope = tuple(match_hosts) if match_hosts else hosts
            if value.get('handler') == 'reverse_proxy' and HOST in scope:
                require(scope == (HOST,) and value.get('upstreams') == [{'dial':'127.0.0.1:8080'}], 'TARGET_ROUTE_AMBIGUOUS')
                value['upstreams'] = [{'dial':'127.0.0.1:'+str(new_port)}]
                found.append(True)
            for key, item in value.items():
                if key not in ('match','upstreams'): walk(item,scope)
        elif isinstance(value,list):
            for item in value:walk(item,hosts)
    walk(expected)
    require(len(found) == 1 and expected == after, 'FOREIGN_CADDY_CHANGE')
    return {'status':'PROPOSAL_ONLY','execution_authorized':False,'before':fingerprint(before),'after':fingerprint(after)}


ADMIN_CAPABILITIES = {'PROVIDER', 'ROOT', 'DOCKER', 'SSH', 'CADDY', 'CI'}


def validate_binding(binding):
    """Typed binding; truth must come from enrolled external readers, not this object."""
    from staging_release import digest
    require(isinstance(binding, dict) and set(binding) == {
        'resource_plan', 'host', 'retained_admins', 'runtime_guard'}, 'SCOPED_BINDING_INVALID')
    require(all(digest(binding[k]) for k in ('resource_plan', 'host', 'runtime_guard')), 'SCOPED_BINDING_INVALID')
    admins = binding['retained_admins']
    require(isinstance(admins, list) and admins, 'HOST_ADMIN_ENROLLMENT_REQUIRED')
    identities = []
    for admin in admins:
        require(isinstance(admin, dict) and set(admin) == {'identity', 'owner', 'capabilities'}
                and digest(admin['identity']) and digest(admin['owner'])
                and isinstance(admin['capabilities'], list) and admin['capabilities']
                and admin['capabilities'] == sorted(set(admin['capabilities']))
                and set(admin['capabilities']) <= ADMIN_CAPABILITIES, 'HOST_ADMIN_ENROLLMENT_REQUIRED')
        identities.append(admin['identity'])
    require(identities == sorted(set(identities)), 'HOST_ADMIN_ENROLLMENT_REQUIRED')
    require({'PROVIDER', 'ROOT', 'DOCKER', 'SSH', 'CADDY'} <=
            {c for a in admins for c in a['capabilities']}, 'HOST_ADMIN_COVERAGE_INCOMPLETE')


def scoped_binding(inventory, routes, admins, runtime_guard):
    plan = replacement_scope(inventory, routes)
    binding = {'resource_plan': plan['binding'], 'host': fingerprint(plan['target']),
               'retained_admins': copy.deepcopy(admins), 'runtime_guard': runtime_guard}
    validate_binding(binding)
    return binding


def retirement_evidence(value, scope, request):
    """Validate separately acquired, signed verifier facts for every operation.

    Receipts are required relationships, never invented/reset legacy outcomes.
    Historical inventory is not a current cessation or host-admin trust proof.
    """
    from staging_release import digest
    require(isinstance(value, dict) and set(value) == {
        'binding', 'installation', 'incarnation', 'inventory_complete', 'host_admins',
        'admin_trust_approval', 'retirement', 'resources', 'custody_receipt'}, 'SCOPED_EVIDENCE_REQUIRED')
    require(value['binding'] == fingerprint(scope['shared_host'])
            and value['installation'] == scope['installation']
            and value['incarnation'] == scope['incarnation'], 'SCOPED_EVIDENCE_BINDING_MISMATCH')
    require(value['inventory_complete'] is True and value['host_admins'] == scope['shared_host']['retained_admins']
            and digest(value['admin_trust_approval']), 'HOST_ADMIN_TRUST_UNPROVEN')
    retired = value['retirement']
    require(isinstance(retired, dict) and set(retired) == {
        'state', 'plan', 'receipt', 'writers', 'executors', 'delayed_effects', 'historical_run724', 'inherited_authority'},
        'SCOPED_RETIREMENT_INCOMPLETE')
    require(retired['state'] == 'OLD_HOOKAH_RETIRED'
            and retired['plan'] == scope['shared_host']['resource_plan'] and digest(retired['receipt'])
            and retired['writers'] == [] and retired['executors'] == [] and retired['delayed_effects'] == []
            and retired['historical_run724'] == 'UNKNOWN' and retired['inherited_authority'] == [],
            'SCOPED_RETIREMENT_INCOMPLETE')
    resources = value['resources']
    require(isinstance(resources, dict) and set(resources) == {'state', 'retirement', 'guard', 'database', 'receipt'}
            and resources['state'] == 'NEW_HOOKAH_RESOURCES_VERIFIED'
            and resources['retirement'] == retired['receipt'] and digest(resources['receipt'])
            and resources['guard'] == scope['shared_host']['runtime_guard']
            and resources['database'] == fingerprint(request['before']['database'])
            and digest(value['custody_receipt']), 'NEW_RESOURCES_UNPROVEN')
