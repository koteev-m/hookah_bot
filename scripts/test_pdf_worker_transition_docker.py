#!/usr/bin/env python3
"""Disposable real two-service -> PDF topology HT70 proof. Seeds only a synthetic historical state."""
import argparse
import copy
import json
from pathlib import Path
import socket
import tempfile
import uuid
from unittest.mock import patch

import simple_staging as s
from test_simple_staging import config
from test_pdf_worker_docker import run


class LocalTransition(s.Staging):
    def __init__(self, root, image_id, postgres_id):
        self.project = 'ht-pdf-transition-' + uuid.uuid4().hex[:10]
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); self.port = sock.getsockname()[1]
        self.app_id, self.postgres_id = image_id, postgres_id
        self.ref = 'ghcr.io/koteev-m/hookah_bot/backend@sha256:' + 'a' * 64
        self.pg_ref = 'postgres@sha256:' + '1' * 64
        self.ids = {self.ref: image_id, self.pg_ref: postgres_id}
        cfg = config(); cfg['postgres_image'] = self.pg_ref
        cfg['architecture'] = json.loads(run(['docker', 'image', 'inspect', image_id]).stdout)[0]['Architecture']
        s.atomic(root / 'config.json', cfg)
        super().__init__(root, self.transport)

    def host_check(self): pass  # Only transport adaptation; not operational Linux/root admission evidence.

    def transport(self, argv, **kwargs):
        if 'payload' in kwargs: kwargs['data'] = kwargs.pop('payload')
        return run([self.ids.get(a, a) for a in argv], **kwargs).stdout

    def inspect_image(self, ref, **kwargs):
        result = json.loads(run(['docker', 'image', 'inspect', self.ids[ref]]).stdout)[0]
        s.require(result['Os'] == 'linux' and result['Architecture'] == self.config['architecture'], 'FIXTURE_IMAGE_PLATFORM')
        return result

    def document(self, target):
        doc = super().document(target)
        for service in doc['services'].values(): service['image'] = self.ids[service['image']]
        return doc

    def candidate(self, rec):
        with patch.object(s, 'verify_ci', return_value={'fixture': True}):
            return super().candidate(rec)

    def cleanup(self):
        containers = self.containers()
        for c in containers:
            assert c['Config']['Labels'].get('com.docker.compose.project') == self.project
            run(['docker', 'rm', '-f', c['Id']])
        for suffix in ('pgdata', 'pdf_ipc'):
            name = self.project + '_' + suffix
            result = run(['docker', 'volume', 'inspect', name], check=False)
            if result.returncode == 0:
                assert json.loads(result.stdout)[0]['Labels'].get('com.docker.compose.project') == self.project
                run(['docker', 'volume', 'rm', name])
        name = self.project + '_default'
        result = run(['docker', 'network', 'inspect', name], check=False)
        if result.returncode == 0:
            assert json.loads(result.stdout)[0]['Labels'].get('com.docker.compose.project') == self.project
            run(['docker', 'network', 'rm', name])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--postgres', required=True)
    parser.add_argument('--evidence-dir', required=True, type=Path)
    args = parser.parse_args(); evidence = args.evidence_dir.resolve(); evidence.mkdir(parents=True, exist_ok=True)
    image = json.loads(run(['docker', 'image', 'inspect', args.image]).stdout)[0]
    pg = json.loads(run(['docker', 'image', 'inspect', args.postgres]).stdout)[0]
    # Do not reinterpret an image from another project as fixture authority.
    assert image['Config']['Labels'].get('org.opencontainers.image.source') == s.SOURCE
    with tempfile.TemporaryDirectory(prefix='ht-pdf-transition-') as directory:
        runtime = LocalTransition(Path(directory).resolve(), image['Id'], pg['Id'])
        try:
            rec = {'image': runtime.ref, 'revision': image['Config']['Labels']['org.opencontainers.image.revision'], 'ci_run': 1}
            target = runtime.candidate(rec)
            s.atomic(runtime.compose_file, runtime.document(target))
            runtime.compose('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '60', 'postgres')
            runtime.compose('up', '-d', '--no-build', '--pull', 'never', '--no-deps', 'backend')
            runtime.ready(target); binding = runtime.binding(); runtime.observe(target, binding)
            runtime.db("CREATE TABLE pdf_transition_sentinel(value TEXT); INSERT INTO pdf_transition_sentinel VALUES ('retained')")
            state = {'status': 'SUCCESS', 'operation': uuid.uuid4().hex, 'action': 'fixture-historical-baseline',
                     'target': target, 'previous': None, 'binding': binding, 'config_binding': runtime.config_binding(),
                     'runtime_hash': s.digest(runtime.config['runtime']), 'compose_hash': s.digest(s.private_json(runtime.compose_file))}
            runtime.save(state)
            old_doc = runtime.compose_file.read_bytes(); old_state = copy.deepcopy(runtime.state())
            runtime.config['runtime'][s.PDF_FLAG] = 'true'
            try: runtime.apply('deploy', rec)
            except s.Refused as failure: assert str(failure) == 'RUNTIME_CONFIG_CHANGED_USE_APPLY_CONFIG'
            else: raise AssertionError('Implicit topology transition was admitted')
            assert old_doc == runtime.compose_file.read_bytes() and old_state == runtime.state()
            result = runtime.apply('deploy', rec, apply_config=True)
            assert result['status'] == 'SUCCESS' and runtime.binding() == binding
            assert runtime.db('SELECT value FROM pdf_transition_sentinel') == 'retained'
            new_target = runtime.state()['target']; runtime.observe(new_target, binding)
            apps = runtime.containers()
            assert {c['Config']['Labels']['com.docker.compose.service'] for c in apps} == {'backend','postgres','pdf-worker'}
            worker = next(c for c in apps if c['Config']['Labels']['com.docker.compose.service'] == 'pdf-worker')
            # Operational drift refuses exact local state; restore only this fixture's original bound quota.
            run(['docker', 'update', '--cpus', '2', worker['Id']])
            try:
                try: runtime.observe(new_target, binding)
                except s.Refused: pass
                else: raise AssertionError('Worker CPU drift was accepted')
            finally: run(['docker', 'update', '--cpus', '1', worker['Id']])
            runtime.observe(new_target, binding)
            result = {'status': 'PASS', 'image': image['Id'], 'historical_services': ['backend','postgres'],
                      'new_services': ['backend','postgres','pdf-worker'], 'binding_unchanged': True,
                      'implicit_transition_refused': True, 'worker_drift_refused': True,
                      'historical_baseline': 'synthetic existing current-schema state; not a V126 bootstrap test',
                      'live_ci': False, 'live_server': False}
            (evidence / 'result.json').write_text(json.dumps(result, indent=2))
            print(json.dumps(result))
        finally:
            for item in runtime.containers():
                if item['Config']['Labels'].get('com.docker.compose.service') == 'pdf-worker':
                    keys = ('NetworkMode','PortBindings','PublishAllPorts','ReadonlyRootfs','Privileged','CapDrop',
                            'CapAdd','Devices','SecurityOpt','Init','Memory','MemorySwap','NanoCpus','PidsLimit',
                            'PidMode','IpcMode','Binds','Tmpfs','Mounts')
                    (evidence / 'worker-observation.json').write_text(json.dumps({
                        'host': {key: item['HostConfig'].get(key) for key in keys},
                        'networks': list(item['NetworkSettings']['Networks']), 'mounts': item['Mounts']}, indent=2))
            runtime.cleanup()


if __name__ == '__main__': main()
