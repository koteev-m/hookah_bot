#!/usr/bin/env python3
"""Disposable Compose swap proof. systemd reload is opt-in on an empty, dedicated Linux VM only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import uuid

import simple_staging as s
from test_simple_staging import config


def command(argv, *, payload=None):
    result = subprocess.run(argv, input=payload, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError('SWAP_PROOF_COMMAND_FAILED: ' + ' '.join(argv[:3]))
    return result.stdout


def isolated_systemd_host(info, compose_version, container_ids):
    # Deliberately refuse Docker Desktop, remote daemons and operational hosts.
    # A caller must provision a dedicated VM with this hostname prefix first.
    s.require(sys.platform == 'linux' and os.geteuid() == 0, 'ISOLATED_LINUX_ROOT_REQUIRED')
    s.require(socket.gethostname().startswith('ht-pdf-swap-proof-')
              and info.get('Name') == socket.gethostname(), 'DEDICATED_VM_HOSTNAME_REQUIRED')
    s.require(not container_ids and not Path('/opt/hookah-bot').exists(), 'EMPTY_DISPOSABLE_DAEMON_REQUIRED')
    s.require(Path('/proc/1/comm').read_text().strip() == 'systemd', 'SYSTEMD_PID1_REQUIRED')
    s.require(info.get('CgroupDriver') == 'systemd' and str(info.get('CgroupVersion')) == '2',
              'SYSTEMD_CGROUP_V2_REQUIRED')
    s.require(info.get('DefaultRuntime') == 'runc', 'DEFAULT_RUNC_REQUIRED')
    s.require(info.get('ServerVersion') == '29.9.2' and compose_version == '2.40.3', 'TARGET_DOCKER_COMPOSE_REQUIRED')
    version = command(['runc', '--version']).decode()
    s.require('runc version 1.5.2\n' in version, 'TARGET_RUNC_REQUIRED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, help='Existing local Hookah worker-capable image; never pulled')
    parser.add_argument('--docker-context', required=True)
    parser.add_argument('--evidence-dir', required=True, type=Path)
    parser.add_argument('--systemd-reload', action='store_true', help='Dedicated empty VM only; NEVER the shared VPS')
    args = parser.parse_args()
    source_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in (Path(__file__), Path(s.__file__))}
    s.require(not any(k.startswith(('DOCKER_', 'COMPOSE_')) for k in os.environ), 'DOCKER_ENV_OVERRIDE_REFUSED')
    docker = ['docker', '--context', args.docker_context]
    endpoint = command(['docker', 'context', 'inspect', args.docker_context,
                        '--format', '{{.Endpoints.docker.Host}}']).decode().strip()
    s.require(endpoint.startswith('unix:///'), 'LOCAL_UNIX_DAEMON_REQUIRED')
    info = json.loads(command(docker + ['info', '--format', '{{json .}}']))
    compose_version = command(docker + ['compose', 'version', '--short']).decode().strip().removeprefix('v')
    original_ids = command(docker + ['ps', '-aq', '--no-trunc']).decode().split()
    if args.systemd_reload:
        isolated_systemd_host(info, compose_version, original_ids)
    s.require(str(info.get('CgroupVersion')) == '2', 'CGROUP_V2_REQUIRED')
    image = json.loads(command(docker + ['image', 'inspect', args.image]))[0]
    s.require(image['Config'].get('Labels', {}).get('org.opencontainers.image.source') == s.SOURCE,
              'HOOKAH_FIXTURE_IMAGE_REQUIRED')
    evidence = args.evidence_dir.resolve()
    evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    project = 'ht-pdf-swap-' + uuid.uuid4().hex[:12]
    snapshots = []

    def inspect(cid):
        return json.loads(command(docker + ['inspect', cid]))[0]

    def untouched():
        # Inspect in memory; no environment/credentials are written to evidence.
        result = {}
        for cid in original_ids:
            c = inspect(cid)
            result[cid] = s.digest({k: c[k] for k in ('Id', 'Image', 'Config', 'HostConfig', 'Mounts')})
        return result

    original = untouched()
    with tempfile.TemporaryDirectory(prefix=project + '-') as directory:
        root = Path(directory).resolve()
        cfg = config(); cfg['runtime'][s.PDF_FLAG] = 'true'
        s.atomic(root / 'config.json', cfg)
        runtime = s.Staging(root)
        runtime.project = project
        # Exercise the actual HT70 observer against this proof's selected local daemon.
        runtime.run = lambda argv, **kwargs: command(docker + argv[1:] if argv[0] == 'docker' else argv)
        doc = runtime.document({'image': image['Id'], 'revision': 'fixture'})
        worker = doc['services']['pdf-worker']; worker.pop('platform')
        canary = {'image': image['Id'], 'entrypoint': ['/bin/sleep'], 'command': ['600'],
                  'network_mode': 'none', 'user': '10001:10001', 'read_only': True,
                  'cap_drop': ['ALL'], 'security_opt': ['no-new-privileges:true'],
                  'mem_limit': 32 * 1024 * 1024, 'memswap_limit': 64 * 1024 * 1024}
        doc = {'services': {'pdf-worker': worker, 'neighbor': canary}, 'volumes': {'pdf_ipc': doc['volumes']['pdf_ipc']}}
        s.atomic(runtime.compose_file, doc)
        compose = docker + ['compose', '--project-name', project, '--project-directory', str(root),
                            '--env-file', '/dev/null', '-f', str(runtime.compose_file)]
        normalized = json.loads(command(compose + ['config', '--format', 'json']))
        assert normalized['services']['pdf-worker']['annotations'] == s.PDF_SYSTEMD_ANNOTATIONS
        assert not normalized['services']['neighbor'].get('annotations')
        (evidence / 'compose.json').write_text(json.dumps(doc, indent=2))

        def owned():
            ids = command(docker + ['ps', '-aq', '--no-trunc', '--filter', 'label=com.docker.compose.project=' + project]).decode().split()
            items = [inspect(cid) for cid in ids]
            assert all(c['Config']['Labels']['com.docker.compose.project'] == project for c in items)
            return {c['Config']['Labels']['com.docker.compose.service']: c for c in items}

        def snapshot(stage):
            items = owned(); assert set(items) == {'pdf-worker', 'neighbor'}
            result = {'stage': stage}
            for role, c in items.items():
                assert re.fullmatch(r'[0-9a-f]{64}', c['Id']) and c['State']['Running']
                values = command(docker + ['exec', c['Id'], '/bin/cat', '/sys/fs/cgroup/memory.max',
                                           '/sys/fs/cgroup/memory.swap.max', '/sys/fs/cgroup/pids.max',
                                           '/sys/fs/cgroup/cpu.max']).decode().splitlines()
                values = dict(zip(('memory.max', 'memory.swap.max', 'pids.max', 'cpu.max'), values))
                observed = {'id': c['Id'], 'values': values, 'annotations': c['HostConfig'].get('Annotations') or {},
                            'health': c['State'].get('Health', {}).get('Status')}
                expected = s.PDF_SYSTEMD_ANNOTATIONS if role == 'pdf-worker' else {}
                assert observed['annotations'] == expected
                if role == 'pdf-worker':
                    assert observed['health'] == 'healthy'
                    assert values == {'memory.max': str(s.PDF_MEMORY), 'memory.swap.max': '0',
                                      'pids.max': '64', 'cpu.max': '100000 100000'}
                    assert c['State']['Health']['Log'][-1]['ExitCode'] == 0
                    runtime.observe_pdf_worker(c, {'id': image['Id'],
                        'pdf_worker_env_hash': s.digest(sorted(image['Config'].get('Env', [])))})
                    observed['native_ht70_observer'] = 'PASS'
                    observed['isolation'] = {'user': c['Config']['User'], **{
                        k: c['HostConfig'].get(k) for k in ('Memory', 'MemorySwap', 'NanoCpus', 'PidsLimit',
                            'ReadonlyRootfs', 'NetworkMode', 'CapDrop', 'SecurityOpt', 'Init', 'Ulimits')}}
                else:
                    assert values['memory.swap.max'] == str(32 * 1024 * 1024)
                if args.systemd_reload:
                    unit = 'docker-' + c['Id'] + '.scope'
                    observed['MemorySwapMax'] = command(['systemctl', 'show', unit,
                                                        '--property=MemorySwapMax', '--value']).decode().strip()
                    assert observed['MemorySwapMax'] == values['memory.swap.max']
                    paths = [Path('/run/docker/runtime-runc/moby') / c['Id'] / 'config.json',
                             Path('/run/containerd/io.containerd.runtime.v2.task/moby') / c['Id'] / 'config.json']
                    path = next((p for p in paths if p.is_file()), None)
                    assert path is not None, 'OCI_SPEC_NOT_OBSERVABLE'
                    spec = json.loads(path.read_text())
                    actual = {k: v for k, v in spec.get('annotations', {}).items() if k.startswith('org.systemd.property.')}
                    assert actual == expected
                    observed['oci_annotations'] = actual
                    observed['oci_spec_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
                result[role] = observed
            assert untouched() == original
            snapshots.append(result)
            (evidence / 'snapshots.json').write_text(json.dumps(snapshots, indent=2))
            return result

        def reload():
            # Recheck scope just before host-wide action; never available in partial mode.
            assert args.systemd_reload and socket.gethostname().startswith('ht-pdf-swap-proof-')
            ids = set(command(docker + ['ps', '-aq', '--no-trunc']).decode().split())
            assert ids == {c['Id'] for c in owned().values()} and not original_ids
            command(['systemctl', 'daemon-reload'])

        try:
            command(compose + ['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '30'])
            first = snapshot('created')
            if args.systemd_reload:
                reload(); snapshot('after-daemon-reload')
            command(compose + ['up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--force-recreate',
                               '--wait', '--wait-timeout', '30', 'pdf-worker'])
            recreated = snapshot('recreated')
            assert recreated['pdf-worker']['id'] != first['pdf-worker']['id']
            assert recreated['neighbor'] == first['neighbor']
            if args.systemd_reload:
                reload(); snapshot('recreated-after-daemon-reload')
        finally:
            # Delete only labelled resources created by this proof, never a daemon-wide cleanup.
            for c in owned().values(): command(docker + ['rm', '-f', c['Id']])
            names = command(docker + ['volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + project]).decode().split()
            for name in names:
                assert name == project + '_pdf_ipc'
                v = json.loads(command(docker + ['volume', 'inspect', name]))[0]
                assert v['Labels']['com.docker.compose.project'] == project
                command(docker + ['volume', 'rm', name])
            assert untouched() == original
    result = {'status': 'SYSTEMD_SWAP_PROOF_PASS' if args.systemd_reload else 'DOCKER_PIPELINE_PARTIAL',
              'docker': info['ServerVersion'], 'compose': compose_version, 'driver': info['CgroupDriver'],
              'architecture': info['Architecture'], 'kernel': info['KernelVersion'],
              'image': image['Id'], 'recreate': 'PASS', 'neighbor_unchanged': True,
              'systemd_reload': 'PASS' if args.systemd_reload else 'NOT_TESTED',
              'oci_runtime_spec': 'PASS' if args.systemd_reload else 'NOT_OBSERVED',
              'source_sha256': source_hashes,
              'shared_vps': 'NOT_ACCESSED', 'fixture_resources_removed': True}
    if args.systemd_reload:
        result['runc'] = command(['runc', '--version']).decode().strip()
        result['systemd'] = command(['systemctl', '--version']).decode().splitlines()[0]
    (evidence / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result))


if __name__ == '__main__':
    main()
