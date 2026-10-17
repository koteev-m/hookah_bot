"""Create-only disposable Docker implementation of the existing CR-1 controller.

No SSH, production enrollment, provider calls, public ports, real bot, pull/build,
database delete/restore/repair, or cleanup endpoint. PUBLIC means only the local
app admission profile; the internal Docker network stays disconnected throughout.
"""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
import zlib
from functools import wraps

from staging_release import Controller, ProtectedFiles, Refused, Store, decode_json, fingerprint, read_json, require
from clean_install_trust import LocalSignedAuthority
from clean_install_policy import SCOPED_ORIGIN


TOOL_FILES = ("staging_release.py", "clean_install_policy.py", "clean_install_trust.py",
              "clean_install_runtime.py", "shared_host_scope.py", "docker-save-archive.py", "deploy-staging.sh")
PG_IMAGE = "sha256:1eada89ec2521ae0f61125f6bdaf83bbbf88a43fecda48d112a83bb92df75c36"
PROBE_IMAGE = "sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4"
JAR_PATH = "app/app/lib/app-0.1.0.jar"
HTTP_PROBE = "import urllib.request,urllib.error,json,sys\ntry:r=urllib.request.urlopen(sys.argv[1],timeout=3)\nexcept urllib.error.HTTPError as e:r=e\nprint(json.dumps({'status':r.status,'body':r.read(131072).decode()}))"


def bounded(method):
    @wraps(method)
    def call(self, *args, timeout, **kwargs):
        previous = getattr(self, 'deadline', float('inf'))
        self.deadline = min(previous, time.monotonic() + timeout)
        try:
            result = method(self, *args, timeout=timeout, **kwargs)
            require(time.monotonic() < self.deadline, 'RUNTIME_TIMEOUT')
            return result
        finally:
            self.deadline = previous
    return call


def tool_closure():
    root = Path(__file__).resolve().parent
    return fingerprint({name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in TOOL_FILES})


def local_runtime_identity():
    return fingerprint({"python": hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest(),
                        "version": sys.version,
                        "openssl": hashlib.sha256(Path('/usr/bin/openssl').read_bytes()).hexdigest(),
                        "docker": hashlib.sha256(Path(shutil.which('docker')).resolve().read_bytes()).hexdigest()})


def migration_manifest(jar_bytes):
    result = []
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        names = [name for name in jar.namelist() if name.startswith("db/migration/postgresql/") and name.endswith(".sql")]
        require(len(names) == len(set(names)), "DUPLICATE_SQL_RESOURCE")
        for name in names:
            raw = jar.read(name)
            filename = name.split('/')[-1]
            checksum = zlib.crc32(''.join(raw.decode('utf-8-sig').splitlines()).encode())
            result.append({"version": int(filename.split('__')[0][1:]), "script": filename,
                           "sha256": hashlib.sha256(raw).hexdigest(),
                           "checksum": checksum if checksum < 2**31 else checksum - 2**32})
    return sorted(result, key=lambda item: item['version'])


def archive_jar(path):
    found = None
    with tarfile.open(path) as archive:
        manifest = json.load(archive.extractfile('manifest.json'))
        require(len(manifest) == 1, "ARCHIVE_SINGLE_IMAGE_REQUIRED")
        for layer in manifest[0]['Layers']:
            with tarfile.open(fileobj=archive.extractfile(layer), mode='r|*') as content:
                for member in content:
                    name = member.name.removeprefix('./')
                    require(not (name.startswith('app/') and '/.wh.' in name), "APP_WHITEOUT_UNSUPPORTED")
                    if name == JAR_PATH:
                        require(member.isfile() and 0 < member.size < 64*1024*1024, "APP_JAR_INVALID")
                        found = content.extractfile(member).read()
    require(found is not None, "APP_JAR_MISSING")
    return found


class DockerRuntime:
    def __init__(self, enrollment):
        require(enrollment.get('profile') == 'DISPOSABLE_LOCAL', 'BLOCKED_EXTERNAL_ENROLLMENT')
        self.e = copy.deepcopy(enrollment)
        self.root = Path(enrollment['directory'])
        self.files = ProtectedFiles(owner=os.geteuid())
        self.files.check(self.root, private=True, directory=True)
        require(re.fullmatch(r'ht-cr1-local-[0-9a-f]{12}', self.root.name)
                and self.root.parent in (Path('/private/tmp'), Path('/tmp')), 'DISPOSABLE_TARGET_REQUIRED')
        self.name = self.root.name
        require(enrollment['name'] == self.name, 'TARGET_BINDING_MISMATCH')
        self.scope = enrollment['scope']
        socket = Path(enrollment['docker_socket'])
        require(socket.is_absolute() and socket.is_socket(), 'LOCAL_DOCKER_SOCKET_REQUIRED')
        self.docker = shutil.which('docker')
        self.environment = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'HOME': str(self.root),
                            'DOCKER_HOST': 'unix://' + str(socket)}
        info = self.json('info', '--format', '{{json .}}')
        require(info['ID'] == enrollment['engine_id'] and info['OSType'] == 'linux', 'DOCKER_ENGINE_CHANGED')
        require(enrollment['target_binding'] == fingerprint({'engine': info['ID'], 'name': self.name,
                    'incarnation': self.scope['incarnation']}), 'TARGET_BINDING_MISMATCH')
        raw = self.files.read(self.root / 'runtime.env')
        require(hashlib.sha256(raw).hexdigest() == enrollment['environment_sha256'], 'RUNTIME_CONFIG_CHANGED')
        self.base = dict(line.split('=', 1) for line in raw.decode().splitlines())
        require(self.base['DB_JDBC_URL'] == 'jdbc:postgresql://postgres:5432/fixture'
                and self.base['DB_USER'] == 'fixture' and self.base['APP_ENV'] == 'staging'
                and self.base['TELEGRAM_BOT_ENABLED'] == 'false'
                and self.base['TELEGRAM_BOT_TOKEN'].startswith('340000001:')
                and all(self.base.get(key) == 'false' for key in ('AI_ASSISTANT_ENABLED', 'YANDEX_MAPS_GEODATA_ENABLED',
                    'BOOKING_EXPIRY_WORKER_ENABLED', 'BOOKING_REMINDER_WORKER_ENABLED', 'VISIT_FEEDBACK_WORKER_ENABLED')),
                'DISPOSABLE_CONFIGURATION_REQUIRED')
        self.verified = {}
        if self.scope['origin'] == SCOPED_ORIGIN:
            from shared_host_scope import scoped_binding, replacement_scope
            guard = enrollment['shared_guard']
            require(set(guard) == {'target', 'protected_daemon', 'retired'}
                    and guard['target'] == enrollment['target_binding'], 'RESOURCE_GUARD_INVALID')
            plan = replacement_scope(enrollment['shared_inventory'], enrollment['shared_routes'])
            require(guard['retired'] == plan['selection'], 'RETIREMENT_SELECTION_CHANGED')
            require(self.scope['shared_host'] == scoped_binding(enrollment['shared_inventory'], enrollment['shared_routes'],
                    self.scope['shared_host']['retained_admins'], fingerprint(guard)), 'RESOURCE_PLAN_CHANGED')

    def protected_daemon(self):
        """Local shared-daemon guard. Return only an aggregate digest, never Env.

        No label/prefix search grants ownership. All non-target objects, including
        unlabelled resources, are protected. This does not fence daemon admins.
        """
        ids = self.command('ps', '-aq', '--no-trunc').decode().split()
        items = self.json('inspect', *ids) if ids else []
        names = self.command('volume', 'ls', '-q').decode().split()
        volumes = self.json('volume', 'inspect', *names) if names else []
        from shared_host_scope import overlap, ROOT
        paths = [ROOT, str(self.root), '/var/lib/docker/volumes/hookah-bot_pgdata/_data']
        for vol in volumes:
            if vol['Name'] in (self.name, 'hookah-bot_pgdata'):
                require(isinstance(vol.get('Mountpoint'), str) and vol['Mountpoint'].startswith('/'), 'VOLUME_PATH_UNPROVEN')
                paths.append(vol['Mountpoint'])
        protected = []
        target_ids = set()
        retired = self.e['shared_guard']['retired']
        for item in items:
            name = item['Name'].lstrip('/')
            target = name in {self.name + suffix for suffix in ('-pg', '-backend', '-probe')}
            # Only controller-created, stopped exact old backend IDs are retained.
            old_id = name.removeprefix(self.name + '-retained-')
            retained = (name == self.name + '-retained-' + item['Id'][:12]
                        and re.fullmatch('[0-9a-f]{12}', old_id) is not None)
            if target or retained:
                self.owned(item)
                self.isolation(item, volume=name == self.name + '-pg')
                require(not retained or not item['State']['Running'], 'RETIRED_BACKEND_RUNNING')
                target_ids.add(item['Id'])
                continue
            require(item['Id'] not in retired['containers']
                    and (item['Config'].get('Labels') or {}).get('com.docker.compose.project') != 'hookah-bot',
                    'OLD_EXECUTOR_PRESENT')
            require(all(m.get('Name') not in retired['volumes'] and m.get('Name') != self.name
                        and not (m.get('Source') and any(overlap(m['Source'], path) for path in paths))
                        for m in item['Mounts']), 'OLD_OR_FOREIGN_VOLUME_CONSUMER')
            protected.append({k: item[k] for k in ('Id', 'Name', 'Image', 'Config', 'HostConfig', 'Mounts')}
                             | {'networks': item['NetworkSettings']['Networks'], 'running': item['State']['Running']})
        nets = self.command('network', 'ls', '-q', '--no-trunc').decode().split()
        networks = self.json('network', 'inspect', *nets) if nets else []
        remaining = []
        for net in networks:
            if net['Name'] == self.name:
                self.owned(net)
                require(set(net['Containers']) <= target_ids, 'UNEXCLUDED_WRITER')
            else:
                require(net['Name'] not in retired['networks'] or not net['Containers'], 'OLD_EXECUTOR_PRESENT')
                remaining.append(net)
        foreign_volumes = []
        for vol in volumes:
            if vol['Name'] == self.name:
                self.owned(vol)
            else:
                foreign_volumes.append(vol)
        return fingerprint({'containers': sorted(protected, key=lambda i:i['Id']),
                            'networks': sorted(remaining, key=lambda i:i['Id']),
                            'volumes': sorted(foreign_volumes, key=lambda i:i['Name'])})

    def shared_guard(self):
        if self.scope['origin'] == SCOPED_ORIGIN:
            require(self.protected_daemon() == self.e['shared_guard']['protected_daemon'], 'PROTECTED_RESOURCE_DRIFT')

    def scoped_command(self, argv):
        """Exact local templates, not a Docker option denylist or command passthrough."""
        if self.scope['origin'] != SCOPED_ORIGIN:
            return
        read = argv[0] in ('info', 'ps', 'inspect') or argv[:2] in (
            ('image','inspect'), ('network','ls'), ('network','inspect'), ('volume','ls'), ('volume','inspect'))
        if read:
            return
        labels = self.labels()
        templates = [
            ('network','create','--internal',*labels,self.name),
            ('volume','create',*labels,self.name),
            ('exec','-i',self.name+'-pg','psql','-XqAtw','-U','fixture','-d','fixture','--set=ON_ERROR_STOP=1'),
            ('exec',self.name+'-pg','pg_isready','-h','127.0.0.1','-U','fixture','-d','fixture'),
            ('exec',self.name+'-pg','pg_dump','-U','fixture','-d','fixture','--schema-only',
             '--no-owner','--no-privileges','--no-comments','--no-tablespaces',
             '--exclude-table=public.flyway_schema_history'),
            ('run','--rm','--pull','never','--name',self.name+'-empty-check',*labels,
             '--network','none','--read-only','--cap-drop','ALL','--mount',
             'type=volume,src='+self.name+',dst=/empty,readonly,volume-nocopy','--entrypoint','/bin/sh',
             PG_IMAGE,'-c','test -z "$(ls -A /empty)"'),
            ('run','-d','--pull','never','--platform','linux/amd64','--name',self.name+'-probe',
             '--network',self.name,'--restart','no',*labels,PROBE_IMAGE,
             'python','-c','import time;time.sleep(86400)')]
        for path in ('/health','/db/health','/version','/miniapp/','/api/guest/catalog'):
            templates.append(('exec',self.name+'-probe','python','-c',HTTP_PROBE,
                              'http://'+self.name+'-backend:8080'+path))
        if argv[0] in ('stop','rename'):
            identity = argv[-1] if argv[0] == 'stop' else argv[1]
            item = self.inspect(identity); self.owned(item)
            require(item['Name'] == '/'+self.name+'-backend' and identity == item['Id'], 'FOREIGN_RESOURCE_SELECTED')
            templates += [('stop','--time','15',identity),
                          ('rename',identity,self.name+'-retained-'+identity[:12])]
        if argv[0] == 'run' and '--env-file' in argv:
            envfile = argv[argv.index('--env-file')+1]
            require(Path(envfile).parent == self.root and Path(envfile).name.startswith('.env-'), 'RUNTIME_CONFIG_CHANGED')
            self.files.check(envfile, private=True)
            base = ('run','-d','--pull','never','--name')
            tail = ('--network',self.name,'--restart','no',*labels,'--env-file',envfile)
            templates.append((*base,self.name+'-pg',*tail,'--network-alias','postgres','--mount',
                              'type=volume,src='+self.name+',dst=/var/lib/postgresql/data',PG_IMAGE))
            for identity in self.e['images']:
                for traffic in ('CLOSED','SMOKE','PUBLIC'):
                    templates.append((*base,self.name+'-backend',*tail,'--platform','linux/amd64',
                                      '--cap-drop','ALL','--security-opt','no-new-privileges',
                                      '--label','ht.cr1.traffic='+traffic,identity))
        require(argv in templates, 'SHARED_HOST_COMMAND_FORBIDDEN')

    def command(self, *argv, payload=None, timeout=30):
        self.scoped_command(argv)
        if argv[0] in ('run', 'stop', 'rename') or argv[:2] in (('network', 'create'), ('volume', 'create')):
            self.shared_guard()
        timeout = min(timeout, getattr(self, 'deadline', float('inf')) - time.monotonic())
        require(timeout > 0, 'RUNTIME_TIMEOUT')
        result = subprocess.run([self.docker, *argv], input=payload, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, env=self.environment, timeout=timeout)
        require(result.returncode == 0 and len(result.stdout) <= 16*1024*1024, 'DOCKER_COMMAND_FAILED')
        return result.stdout

    def json(self, *argv, **kwargs):
        return json.loads(self.command(*argv, **kwargs))

    def inspect(self, name):
        return self.json('inspect', name)[0]

    def owned(self, item):
        labels = item.get('Config', {}).get('Labels') if 'Config' in item else item.get('Labels')
        require(labels and labels.get('ht.cr1.local') == self.name
                and labels.get('ht.cr1.incarnation') == self.scope['incarnation'], 'FOREIGN_RESOURCE')

    def labels(self):
        return ['--label', 'ht.cr1.local=' + self.name, '--label', 'ht.cr1.incarnation=' + self.scope['incarnation']]

    def isolation(self, item, *, volume=False):
        host = item['HostConfig']
        require(set(item['NetworkSettings']['Networks']) == {self.name}
                and all(not bindings for bindings in (item['NetworkSettings'].get('Ports') or {}).values())
                and not host.get('PublishAllPorts')
                and not host.get('Privileged') and not host.get('PortBindings')
                and host.get('NetworkMode') == self.name and host['RestartPolicy']['Name'] == 'no'
                and item['RestartCount'] == 0 and not item['State'].get('Paused')
                and not item['State'].get('Restarting')
                and all(not host.get(key) for key in ('CapAdd','Devices','DeviceRequests','Binds','VolumesFrom',
                    'Links','ExtraHosts','Dns','DnsSearch','DnsOptions','Tmpfs','Sysctls','GroupAdd',
                    'DeviceCgroupRules','StorageOpt','Init'))
                and host.get('PidMode','') == ''
                and host.get('UTSMode','') == ''
                and all(host.get(key,'') in ('','private') for key in ('IpcMode','UsernsMode','CgroupnsMode')),
                'CONTAINER_ISOLATION_CHANGED')
        require((len(item['Mounts']) == 1 and item['Mounts'][0].get('Name') == self.name
                 and item['Mounts'][0]['Destination'] == '/var/lib/postgresql/data') if volume else not item['Mounts'],
                'CONTAINER_MOUNTS_CHANGED')

    def execution(self, item, image, env, *, probe=False, backend=False):
        # A locally present multi-platform index may expose an empty default
        # Config; compare the config of the exact platform actually requested.
        config = self.json('image', 'inspect', '--platform', 'linux/amd64' if probe or backend else 'linux/arm64', image)[0]['Config']
        expected_env = dict(value.split('=', 1) for value in config.get('Env', [])) | env
        actual_env = item['Config']['Env']
        require(len(actual_env) == len(expected_env)
                and dict(value.split('=', 1) for value in actual_env) == expected_env, 'EXECUTION_ENV_CHANGED')
        require(item['Config']['Entrypoint'] == config.get('Entrypoint'), 'EXECUTION_ENTRYPOINT_CHANGED')
        require(item['Config']['Cmd'] == (['python', '-c', 'import time;time.sleep(86400)'] if probe else config.get('Cmd')),
                'EXECUTION_COMMAND_CHANGED')
        for key in ('User','WorkingDir','Healthcheck','Shell'):
            actual, expected = item['Config'].get(key), config.get(key)
            if key in ('User','WorkingDir'):
                actual, expected = actual or '', expected or ''
            require(actual == expected, 'EXECUTION_' + key.upper() + '_CHANGED')
        require((item['HostConfig'].get('CapDrop') or []) == (['ALL'] if backend else []), 'EXECUTION_CAPABILITIES_CHANGED')
        require((item['HostConfig'].get('SecurityOpt') or []) == (['no-new-privileges'] if backend else []),
                'EXECUTION_SECURITY_OPTIONS_CHANGED')
        require(not item['HostConfig'].get('ReadonlyRootfs'), 'EXECUTION_READONLY_CHANGED')

    def configuration(self, traffic):
        env = dict(self.base)
        env.update(TELEGRAM_TRAFFIC_POLICY='PRODUCT', STAGING_MAINTENANCE_MODE='OFF' if traffic == 'PUBLIC' else 'V126_SMOKE',
                   STAGING_MAINTENANCE_ALLOWED_USER_IDS='' if traffic == 'PUBLIC' else ('340000001' if traffic == 'SMOKE' else '340000000'),
                   STAGING_MAINTENANCE_ALLOWED_CHAT_IDS='' if traffic == 'PUBLIC' else ('340000001' if traffic == 'SMOKE' else '340000000'))
        return env

    def clean_tooling(self, *, timeout):
        result = dict(self.scope['tooling'])
        result.update(closure=tool_closure(), runtime=local_runtime_identity())
        return result

    @bounded
    def clean_image(self, record, *, timeout):
        require(record == self.e['images'].get(record['id'], {}).get('record'), 'IMAGE_NOT_ENROLLED')
        if record['id'] not in self.verified:
            artifact = self.e['images'][record['id']]
            self.files.check(artifact['archive'])
            # Verification and SQL extraction consume one private immutable copy.
            with tempfile.TemporaryDirectory(prefix='ht-cr1-artifact-') as tmp:
                snapshot = Path(tmp) / 'image.tar'
                shutil.copyfile(artifact['archive'], snapshot)
                snapshot.chmod(0o600)
                with snapshot.open('rb') as stream:
                    require(hashlib.file_digest(stream, 'sha256').hexdigest() == record['archive'], 'ARCHIVE_CHANGED')
                check = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('docker-save-archive.py')),
                    'verify', str(snapshot), '--expected-tag', artifact['tag'],
                    '--expected-image-id', record['id'], '--expected-revision', record['revision']],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=max(.001, self.deadline-time.monotonic()))
                require(check.returncode == 0, 'ARCHIVE_IDENTITY_INVALID')
                require(migration_manifest(archive_jar(snapshot)) == record['manifest'], 'EMBEDDED_SQL_CHANGED')
            self.verified[record['id']] = True
        inspected = self.json('image', 'inspect', record['id'])[0]
        require(inspected['Id'] == record['id'] and inspected['Os'] + '/' + inspected['Architecture'] == record['platform']
                and inspected['Config']['Labels'].get('org.opencontainers.image.source') == record['source']
                and inspected['Config']['Labels'].get('org.opencontainers.image.revision') == record['revision'], 'LOADED_IMAGE_CHANGED')
        return copy.deepcopy(record)

    def sql(self, statement):
        return self.command('exec', '-i', self.name + '-pg', 'psql', '-XqAtw', '-U', 'fixture', '-d', 'fixture',
                            '--set=ON_ERROR_STOP=1', payload=statement.encode()).decode().strip()

    def schema(self):
        raw = self.command('exec', self.name + '-pg', 'pg_dump', '-U', 'fixture', '-d', 'fixture', '--schema-only',
                           '--no-owner', '--no-privileges', '--no-comments', '--no-tablespaces',
                           '--exclude-table=public.flyway_schema_history').decode()
        return hashlib.sha256('\n'.join(line for line in raw.splitlines()
                            if not line.startswith(('\\restrict ', '\\unrestrict '))).encode()).hexdigest()

    @bounded
    def clean_observe(self, *, timeout):
        deadline = time.monotonic() + timeout
        self.shared_guard()
        # Exact name collisions are checked even if a foreign resource lacks labels.
        containers = self.command('ps', '-a', '--format', '{{.Names}}').decode().splitlines()
        networks = self.command('network', 'ls', '--format', '{{.Name}}').decode().splitlines()
        volumes = self.command('volume', 'ls', '--format', '{{.Name}}').decode().splitlines()
        has_pg = self.name + '-pg' in containers
        base = {'installation': self.scope['installation'], 'incarnation': self.scope['incarnation'],
                'database': {'system_id': 'UNCREATED', 'database': 'fixture', 'role': 'fixture', 'volume': self.name, 'network': self.name},
                'image': None, 'backend_count': 0, 'traffic': 'CLOSED',
                'configuration': fingerprint(self.configuration('CLOSED')), 'history': [],
                'user_objects': [], 'unidentified_writers': [], 'schema': fingerprint({'database': 'UNCREATED'})}
        if self.scope['origin'] == SCOPED_ORIGIN:
            base['resource_guard'] = fingerprint(self.e['shared_guard'])
        if not has_pg:
            require(self.name not in networks and self.name not in volumes
                    and not any(n.startswith(self.name + '-') for n in containers), 'PARTIAL_INSTALLATION_PRESENT')
            return base
        require(self.name in networks and self.name in volumes, 'PARTIAL_INSTALLATION_PRESENT')
        pg = self.inspect(self.name + '-pg'); self.owned(pg)
        self.isolation(pg, volume=True)
        self.execution(pg, PG_IMAGE, {'POSTGRES_DB':'fixture','POSTGRES_USER':'fixture','POSTGRES_PASSWORD':self.base['DB_PASSWORD']})
        net = self.json('network', 'inspect', self.name)[0]; self.owned(net)
        volume = self.json('volume', 'inspect', self.name)[0]; self.owned(volume)
        require(volume['Driver'] == 'local' and not volume.get('Options'), 'VOLUME_CONFIGURATION_CHANGED')
        require(net['Internal'] is True and net['Driver'] == 'bridge'
                and (net.get('Options') or {}) in ({}, {'com.docker.network.enable_ipv4':'true',
                                                       'com.docker.network.enable_ipv6':'false'}), 'NETWORK_CONFIGURATION_CHANGED')
        require(pg['Image'] == PG_IMAGE, 'DATABASE_IMAGE_CHANGED')
        require(pg['State']['Running'], 'DATABASE_NOT_RUNNING')
        base['database']['system_id'] = self.sql('select system_identifier from pg_control_system()')
        backend = self.inspect(self.name + '-backend'); self.owned(backend)
        self.isolation(backend)
        traffic = backend['Config']['Labels'].get('ht.cr1.traffic')
        require(traffic in ('CLOSED', 'SMOKE', 'PUBLIC'), 'TRAFFIC_PROFILE_INVALID')
        expected = self.configuration(traffic)
        actual = dict(item.split('=', 1) for item in backend['Config']['Env'])
        record = self.e['images'].get(backend['Image'], {}).get('record')
        require(record is not None and actual.get('APP_VERSION') == record['revision']
                and all(actual.get(k) == v for k, v in expected.items()), 'EFFECTIVE_CONFIGURATION_CHANGED')
        self.execution(backend, record['id'], expected | {'APP_VERSION':record['revision']}, backend=True)
        require(backend['State']['Running'] and not backend['State'].get('Restarting')
                and backend['RestartCount'] == 0 and not backend['Mounts']
                and not backend['HostConfig'].get('Privileged') and not backend['HostConfig'].get('PortBindings')
                and backend['HostConfig']['RestartPolicy']['Name'] == 'no'
                and set(backend['NetworkSettings']['Networks']) == {self.name}, 'BACKEND_ISOLATION_CHANGED')
        probe = self.inspect(self.name + '-probe'); self.owned(probe)
        self.isolation(probe)
        self.execution(probe, PROBE_IMAGE, {}, probe=True)
        require(probe['Image'] == PROBE_IMAGE and probe['State']['Running'] and not probe['Mounts']
                and set(net['Containers']) == {pg['Id'], backend['Id'], probe['Id']}, 'UNEXCLUDED_WRITER')
        backend_ip = backend['NetworkSettings']['Networks'][self.name]['IPAddress']
        clients = json.loads(self.sql("select coalesce(json_agg(host(client_addr)),'[]') from pg_stat_activity where "
                            "datname=current_database() and backend_type='client backend' and pid<>pg_backend_pid()"))
        require(all(addr == backend_ip for addr in clients), 'UNEXCLUDED_WRITER')
        require(self.sql('select (select count(*) from pg_prepared_xacts)+(select count(*) from pg_replication_slots)'
                         '+(select count(*) from pg_subscription)') == '0', 'UNEXCLUDED_WRITER')
        base.update(image=backend['Image'], backend_count=1, traffic=traffic,
                    configuration=fingerprint(expected), schema=self.schema())
        base['history'] = json.loads(self.sql("select coalesce(json_agg(t order by rank),'[]') from "
            "(select installed_rank as rank,version::integer,script,checksum,success from flyway_schema_history) t"))
        base['user_objects'] = json.loads(self.sql("select coalesce(json_agg(nspname||'.'||relname order by nspname,relname),'[]') "
            "from pg_class join pg_namespace on pg_namespace.oid=relnamespace "
            "where nspname not in ('pg_catalog','information_schema') and nspname not like 'pg_toast%'"))
        require(time.monotonic() < deadline, 'OBSERVATION_TIMEOUT')
        return base

    def request(self, path):
        program = HTTP_PROBE
        return json.loads(self.command('exec', self.name + '-probe', 'python', '-c', program,
                                      'http://' + self.name + '-backend:8080' + path, timeout=6))

    @bounded
    def clean_ready(self, image, *, timeout):
        deadline = time.monotonic() + timeout
        known = False
        while time.monotonic() < deadline:
            try:
                checks = [self.request(p) for p in ('/health', '/db/health', '/version', '/miniapp/', '/api/guest/catalog')]
                known = True
                if ([r['status'] for r in checks[:4]] == [200]*4 and checks[4]['status'] in (401, 503)
                        and json.loads(checks[0]['body']) == {'status': 'ok'}
                        and json.loads(checks[1]['body']) == {'status': 'ok'}
                        and json.loads(checks[2]['body'])['version'] == image['revision']
                        and '<html' in checks[3]['body'].lower()):
                    return True
            except (Refused, ValueError, KeyError, subprocess.TimeoutExpired):
                known = False
            if deadline - time.monotonic() < .3:
                require(known, 'HEALTH_OUTCOME_UNKNOWN')
                return False
            time.sleep(0.25)
        require(False, 'HEALTH_OUTCOME_UNKNOWN')

    @bounded
    def clean_execute(self, plan, before, *, timeout):
        require(self.clean_observe(timeout=10) == before, 'PRE_EXECUTION_DRIFT')
        kind = plan['kind']
        if kind == 'ACCEPT':
            return 'COMPLETED'
        record = plan.get('image') or self.e['images'][before['image']]['record']
        traffic = {'TEST': 'SMOKE', 'PUBLIC': 'PUBLIC'}.get(kind, before['traffic'])
        require(plan.get('configuration', fingerprint(self.configuration(traffic))) == fingerprint(self.configuration(traffic)),
                'CONFIGURATION_NOT_ENROLLED')
        if kind == 'BOOTSTRAP':
            require(before['database']['system_id'] == 'UNCREATED', 'CREATE_ONLY_INSTALL_REQUIRED')
            self.command('network', 'create', '--internal', *self.labels(), self.name)
            self.command('volume', 'create', *self.labels(), self.name)
            volume = self.json('volume','inspect',self.name)[0]; self.owned(volume)
            require(volume['Driver'] == 'local' and not volume.get('Options'), 'VOLUME_CONFIGURATION_CHANGED')
            self.command('run','--rm','--pull','never','--name',self.name+'-empty-check',*self.labels(),
                         '--network','none','--read-only','--cap-drop','ALL',
                         '--mount','type=volume,src='+self.name+',dst=/empty,readonly,volume-nocopy',
                         '--entrypoint','/bin/sh',PG_IMAGE,'-c','test -z "$(ls -A /empty)"')
            pg_env = ('POSTGRES_DB=fixture\nPOSTGRES_USER=fixture\nPOSTGRES_PASSWORD=' + self.base['DB_PASSWORD'] + '\n').encode()
            self.run_env(self.name + '-pg', PG_IMAGE, pg_env,
                         ['--network-alias', 'postgres', '--mount', 'type=volume,src=' + self.name + ',dst=/var/lib/postgresql/data'])
            deadline = time.monotonic() + 30
            while True:
                try:
                    self.command('exec', self.name + '-pg', 'pg_isready', '-h', '127.0.0.1', '-U', 'fixture', '-d', 'fixture')
                    break
                except Refused:
                    require(time.monotonic() < deadline, 'POSTGRES_START_TIMEOUT'); time.sleep(0.25)
            require(self.sql("select count(*) from pg_class join pg_namespace on pg_namespace.oid=relnamespace "
                             "where nspname not in ('pg_catalog','information_schema') and nspname not like 'pg_toast%'") == '0',
                    'DATABASE_NOT_EMPTY')
            self.command('run', '-d', '--pull', 'never', '--platform', 'linux/amd64', '--name', self.name + '-probe',
                         '--network', self.name, '--restart', 'no', *self.labels(), PROBE_IMAGE,
                         'python', '-c', 'import time;time.sleep(86400)')
        else:
            backend = self.inspect(self.name + '-backend'); self.owned(backend)
            require(backend['Image'] == before['image'], 'BACKEND_IDENTITY_CHANGED')
            self.command('stop', '--time', '15', backend['Id'])
            require(not self.inspect(backend['Id'])['State']['Running'], 'WRITER_STOP_NOT_PROVEN')
            require(self.sql("select count(*) from pg_stat_activity where datname=current_database() "
                             "and backend_type='client backend' and pid<>pg_backend_pid()") == '0', 'UNEXCLUDED_WRITER')
            self.command('rename', backend['Id'], self.name + '-retained-' + backend['Id'][:12])
        env = self.configuration(traffic) | {'APP_VERSION': record['revision']}
        self.run_env(self.name + '-backend', record['id'], ''.join(k + '=' + v + '\n' for k, v in env.items()).encode(),
                     ['--platform', 'linux/amd64', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                      '--label', 'ht.cr1.traffic=' + traffic])
        require(self.clean_ready(record, timeout=120), 'BACKEND_START_NOT_PROVEN')
        return 'COMPLETED'

    def run_env(self, name, image, raw, extra):
        with tempfile.NamedTemporaryFile(dir=self.root, prefix='.env-') as stream:
            stream.write(raw); stream.flush()
            self.command('run', '-d', '--pull', 'never', '--name', name, '--network', self.name, '--restart', 'no',
                         *self.labels(), '--env-file', stream.name, *extra, image)


def local_action(args):
    store = authority = None
    try:
        files = ProtectedFiles(owner=os.geteuid())
        files.check(args.local_registration, private=True)
        enrollment = decode_json(files.read(args.local_registration))
        require(enrollment.get('profile') == 'DISPOSABLE_LOCAL', 'BLOCKED_EXTERNAL_ENROLLMENT')
        runtime = DockerRuntime(enrollment)
        # A second observer instance represents the verifier process boundary in
        # this local harness only; it is not an independent administrative domain.
        authority = LocalSignedAuthority(enrollment, observe=DockerRuntime(enrollment).clean_observe)
        store = Store(Path(enrollment['directory']) / '.staging-release', migration_target=enrollment['directory'])
        controller = Controller.clean_installation(enrollment['scope'], store, runtime, authority)
        if args.action == 'status':
            with store.locked():
                head = authority.head(enrollment['scope']['installation'], timeout=5)
                require(head['pending'] is None, 'OPERATOR_RECONCILIATION_REQUIRED')
                state = store.read()
                require(state is not None and state['status'] not in ('FAILED','UNKNOWN','APPLYING'), 'OPERATOR_RECONCILIATION_REQUIRED')
                require(head['state'] == fingerprint(state), 'INDEPENDENT_HISTORY_MISMATCH')
                require(runtime.clean_observe(timeout=10) == state['observed'], 'ACCEPTED_STATE_DRIFT')
                return {'status': 'SUCCESS', 'state': state['status'], 'profile': 'DISPOSABLE_LOCAL'}
        action = args.operation if args.action == 'preflight' else args.action
        require(action in ('install', 'test-admit', 'public-admit', 'deploy', 'accept', 'rollback'), 'CLEAN_ACTION_INVALID')
        candidate = read_json(args.candidate) if args.candidate else None
        expected = args.expected_current
        if action == 'rollback':
            state = store.read(); candidate, expected = state['previous_image'], state['image']['id']
        kind = {'install': 'BOOTSTRAP', 'test-admit': 'TEST', 'public-admit': 'PUBLIC', 'accept': 'ACCEPT', 'rollback': 'ROLLBACK'}.get(action)
        if action == 'deploy':
            kind = 'APP' if candidate['manifest'] == store.read()['image']['manifest'] else 'FORWARD'
        if args.action != 'preflight':
            require(args.smoke_passed if action == 'accept' else args.owner_approved, 'EXPLICIT_APPROVAL_REQUIRED')
        phase = controller.clean_action(kind, candidate=candidate, configuration=args.configuration,
                                         expected_current=expected, prepare_only=args.action == 'preflight')
        return {'status': 'SUCCESS', 'profile': 'DISPOSABLE_LOCAL',
                **({'request': phase} if args.action == 'preflight' else {'state': phase})}
    except (Exception, KeyboardInterrupt):
        error = sys.exc_info()[1]
        reason = str(error) if isinstance(error, Refused) else 'CR1_INPUT_OR_RUNTIME_INVALID'
        uncertain = reason in ('AUTHORITY_INTENT_OUTCOME_UNKNOWN', 'CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED')
        try:
            head = authority.head(authority.scope['installation'], timeout=5) if authority else None
            state = store.read() if store and store.path.exists() else None
            uncertain |= bool(head and head['pending']) or bool(state and state['status'] in ('APPLYING', 'UNKNOWN'))
            uncertain |= bool(head and state and head['state'] != fingerprint(state))
        except Exception:
            uncertain = store is not None
        return {'status': 'UNKNOWN' if uncertain else 'FAILED', 'reason': reason, 'profile': 'DISPOSABLE_LOCAL'}
