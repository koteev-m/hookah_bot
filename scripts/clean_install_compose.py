"""Scoped Compose execution transport for the existing CR-1 runtime/controller.

The compiler and executor are exercised against disposable Docker. Real server
dispatch is deliberately unavailable until an external Authority is enrolled;
DISPOSABLE_LOCAL signatures are never server authority. No SSH or teardown path.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time

from clean_install_runtime import DockerRuntime, PROBE_IMAGE, local_runtime_identity
from clean_install_policy import SCOPED_ORIGIN
from staging_release import fingerprint, require


def successor_layout():
    # Must agree with shared_host_scope.replacement_scope, not caller-selected names.
    return {'project': 'hookah-staging-v126', 'directory': '/opt/hookah-bot-v126',
            'network': 'hookah-staging-v126_default', 'volume': 'hookah-staging-v126_pgdata'}


class ComposeRuntime(DockerRuntime):
    # docker.io/library/postgres:17.8, immutable OCI index + explicit linux/amd64. No runtime pull.
    pg_image = "sha256:69dddb030ab69d669d8d7c6abf67aeb448178e5270d5f123a21f4f7ac8b46a24"
    pg_platform = "linux/amd64"

    def __init__(self, enrollment):
        # Check before looking at files, sockets, or any caller-provided credentials.
        require(enrollment.get('profile') == 'DISPOSABLE_LOCAL', 'BLOCKED_EXTERNAL_ENROLLMENT')
        require(enrollment.get('runtime') == 'SCOPED_COMPOSE'
                and enrollment.get('scope', {}).get('origin') == SCOPED_ORIGIN, 'SCOPED_COMPOSE_REQUIRED')
        super().__init__(enrollment)
        executable = shutil.which('docker-compose')
        require(executable is not None, 'COMPOSE_TOOLING_UNPROVEN')
        self.compose = str(Path(executable).resolve(strict=True))
        self.compose_identity = self.compose_tooling()

    @property
    def network_name(self):
        return self.name + '_default'

    @property
    def volume_name(self):
        return self.name + '_pgdata'

    def compose_tooling(self):
        return hashlib.sha256(Path(self.compose).read_bytes()).hexdigest()

    def clean_tooling(self, *, timeout):
        value = super().clean_tooling(timeout=timeout)
        value['runtime'] = fingerprint({'runtime': local_runtime_identity(), 'compose': self.compose_tooling()})
        return value

    def owned(self, item):
        super().owned(item)
        if 'Config' not in item:
            return
        labels = item['Config']['Labels']
        suffix = item['Name'].removeprefix('/' + self.name + '-')
        service = 'postgres' if suffix == 'pg' else 'backend' if suffix.startswith('retained-') else suffix
        require(service in ('postgres', 'backend', 'probe')
                and labels.get('com.docker.compose.project') == self.name
                and labels.get('com.docker.compose.service') == service
                and labels.get('com.docker.compose.oneoff') == 'True'
                and labels.get('com.docker.compose.project.working_dir') == str(self.root),
                'COMPOSE_IDENTITY_CHANGED')

    def specification(self, argv):
        """Compile one exact allowed run template. Never ingest a Compose file."""
        self.scoped_command(argv)
        require(argv[:2] == ('run', '-d'), 'COMPOSE_RUN_REQUIRED')
        name = argv[argv.index('--name') + 1]
        labels = {'ht.cr1.local': self.name, 'ht.cr1.incarnation': self.scope['incarnation']}
        service = {'image': PROBE_IMAGE, 'platform': 'linux/amd64', 'restart': 'no',
                   'pull_policy': 'never', 'labels': labels, 'networks': ['closed']}
        volumes = {}
        if name == self.name + '-probe':
            role = 'probe'
            service['command'] = ['python', '-c', 'import time;time.sleep(86400)']
        else:
            raw = self.files.read(argv[argv.index('--env-file') + 1])
            lines = raw.decode().splitlines()
            env = dict(line.split('=', 1) for line in lines)
            require(len(lines) == len(env), 'RUNTIME_CONFIG_CHANGED')
            if name == self.name + '-pg':
                role = 'postgres'
                require(env == {'POSTGRES_DB': 'fixture', 'POSTGRES_USER': 'fixture',
                                'POSTGRES_PASSWORD': self.base['DB_PASSWORD']}, 'RUNTIME_CONFIG_CHANGED')
                service.update(image=self.pg_image, platform=self.pg_platform,
                               networks={'closed': {'aliases': ['postgres']}},
                               volumes=[{'type': 'volume', 'source': 'database',
                                         'target': '/var/lib/postgresql/data', 'volume': {'nocopy': True}}])
                volumes['database'] = {'external': True, 'name': self.volume_name}
            else:
                require(name == self.name + '-backend', 'FOREIGN_RESOURCE_SELECTED')
                role = 'backend'
                identity = argv[-1]
                record = self.e['images'][identity]['record']
                traffic = next(v.split('=', 1)[1] for v in argv if v.startswith('ht.cr1.traffic='))
                require(env == self.configuration(traffic) | {'APP_VERSION': record['revision']}, 'RUNTIME_CONFIG_CHANGED')
                labels['ht.cr1.traffic'] = traffic
                service.update(image=identity, cap_drop=['ALL'], security_opt=['no-new-privileges'])
            # Compose interpolates dollar expressions even in JSON. Escape bytes,
            # then verify actual process env via the inherited independent observer.
            service['environment'] = {k: v.replace('$', '$$') for k, v in env.items()}
        spec = {'services': {role: service},
                'networks': {'closed': {'external': True, 'name': self.network_name}}}
        if volumes:
            spec['volumes'] = volumes
        return role, name, spec

    def command(self, *argv, payload=None, timeout=30):
        if argv[:2] != ('run', '-d'):
            return super().command(*argv, payload=payload, timeout=timeout)
        require(payload is None, 'COMPOSE_INPUT_FORBIDDEN')
        role, name, spec = self.specification(argv)
        self.shared_guard()
        require(self.compose_tooling() == self.compose_identity, 'COMPOSE_TOOLING_CHANGED')
        timeout = min(timeout, getattr(self, 'deadline', float('inf')) - time.monotonic())
        require(timeout > 0, 'RUNTIME_TIMEOUT')
        # No up/down/build/pull, orphan cleanup, arbitrary file, or service selection.
        # One-off containers retain their exact IDs across backend stop+rename.
        # Network/volume must already exist and are never managed by Compose.
        result = subprocess.run([self.compose, '--ansi', 'never', '--env-file', '/dev/null',
            '--project-name', self.name, '--project-directory', str(self.root), '--file', '-',
            'run', '--no-deps', '--pull', 'never', '--use-aliases', '-d', '--name', name, role],
            input=json.dumps(spec, sort_keys=True).encode(), stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, env=self.environment, timeout=timeout)
        require(result.returncode == 0 and len(result.stdout) <= 16*1024*1024, 'COMPOSE_COMMAND_FAILED')
        self.shared_guard()
        return result.stdout
