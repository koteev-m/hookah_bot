"""Exact HT70 PDF topology admission; historical document/hash and adversarial inspect checks."""
import copy
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import simple_staging as s
import test_pdf_worker_docker as docker_proof
import test_pdf_worker_swap_docker as swap_proof
from test_simple_staging import MemoryStaging, config, image, record


class SystemdProofScopeTests(unittest.TestCase):
    def test_reload_proof_refuses_mac_shared_host_and_nonempty_daemon(self):
        info = {'Name': 'ht-pdf-swap-proof-fixture', 'ServerVersion': '29.9.2',
                'CgroupDriver': 'systemd', 'CgroupVersion': '2', 'DefaultRuntime': 'runc'}
        with patch.object(swap_proof.sys, 'platform', 'darwin'):
            with self.assertRaisesRegex(s.Refused, 'LINUX_ROOT'):
                swap_proof.isolated_systemd_host(info, '2.40.3', [])
        with patch.object(swap_proof.sys, 'platform', 'linux'), patch.object(os, 'geteuid', return_value=0):
            with patch.object(swap_proof.socket, 'gethostname', return_value='operational-vps'):
                with self.assertRaisesRegex(s.Refused, 'HOSTNAME'):
                    swap_proof.isolated_systemd_host(info, '2.40.3', [])
            with patch.object(swap_proof.socket, 'gethostname', return_value=info['Name']):
                with self.assertRaisesRegex(s.Refused, 'EMPTY_DISPOSABLE'):
                    swap_proof.isolated_systemd_host(info, '2.40.3', ['existing-container'])

    def test_reload_proof_requires_matching_runtime_and_systemd(self):
        info = {'Name': 'ht-pdf-swap-proof-fixture', 'ServerVersion': '29.9.2',
                'CgroupDriver': 'systemd', 'CgroupVersion': '2', 'DefaultRuntime': 'runc'}
        with patch.object(swap_proof.sys, 'platform', 'linux'), patch.object(os, 'geteuid', return_value=0), \
             patch.object(swap_proof.socket, 'gethostname', return_value=info['Name']), \
             patch.object(Path, 'exists', return_value=False), patch.object(Path, 'read_text', return_value='systemd\n'), \
             patch.object(swap_proof, 'command', return_value=b'runc version 1.5.2\n') as command:
            swap_proof.isolated_systemd_host(info, '2.40.3', [])
            self.assertEqual(command.call_args.args[0], ['runc', '--version'])
            for key, value in [('ServerVersion', '29.8.1'), ('CgroupDriver', 'cgroupfs'),
                               ('CgroupVersion', '1'), ('DefaultRuntime', 'other')]:
                with self.subTest(key=key), self.assertRaises(s.Refused):
                    swap_proof.isolated_systemd_host(dict(info, **{key: value}), '2.40.3', [])
            with self.assertRaisesRegex(s.Refused, 'TARGET_DOCKER_COMPOSE'):
                swap_proof.isolated_systemd_host(info, '5.5.1', [])
            command.return_value = b'runc version 1.4.0\n'
            with self.assertRaisesRegex(s.Refused, 'TARGET_RUNC'):
                swap_proof.isolated_systemd_host(info, '2.40.3', [])


class PdfProbeFixtureTests(unittest.TestCase):
    def test_both_compiled_probes_are_readable_under_strict_umask(self):
        previous_umask = os.umask(0o077)
        try:
            for name in ('PdfIpcProbe', 'PdfResourceProbe'):
                with self.subTest(probe=name), docker_proof.probe_fixture(name) as temp:
                    directory = Path(temp)
                    self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o755)
                    compiled = directory / (name + '.class')
                    self.assertEqual(list(directory.iterdir()), [compiled])
                    self.assertEqual(stat.S_IMODE(compiled.stat().st_mode), 0o644)
                    self.assertEqual(compiled.read_bytes()[:4], b'\xca\xfe\xba\xbe')
                    private = directory / 'private-sentinel'
                    private.write_text('test-only')
                    self.assertEqual(stat.S_IMODE(private.stat().st_mode), 0o600)
                    self.assertEqual(os.umask(0o077), 0o077)
                self.assertFalse(directory.exists())
        finally:
            os.umask(previous_umask)

    def test_only_expected_compiled_file_permissions_are_changed(self):
        original_run = docker_proof.run
        previous_umask = os.umask(0o077)
        def compile_with_private_file(args):
            result = original_run(args)
            directory = Path(args[args.index('-d') + 1])
            (directory / 'private-sentinel').write_text('test-only')
            return result
        try:
            with patch.object(docker_proof, 'run', side_effect=compile_with_private_file):
                for name in ('PdfIpcProbe', 'PdfResourceProbe'):
                    source = Path(docker_proof.__file__).parent / 'fixtures' / (name + '.java')
                    source_mode = source.stat().st_mode
                    with self.subTest(probe=name), docker_proof.probe_fixture(name) as temp:
                        self.assertEqual(stat.S_IMODE(Path(temp, 'private-sentinel').stat().st_mode), 0o600)
                        self.assertEqual(stat.S_IMODE(Path(temp, name + '.class').stat().st_mode), 0o644)
                        self.assertEqual(source.stat().st_mode, source_mode)
        finally:
            os.umask(previous_umask)


class PdfTopologyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.runtime = MemoryStaging(self.root)

    def enable(self):
        self.runtime.config['runtime'][s.PDF_FLAG] = 'true'
        target = self.runtime.images[image('b')['image']]
        target.update(pdf_worker=1, pdf_worker_env_hash=s.digest(['PATH=/safe']))
        return target

    def test_historical_document_is_byte_identical_when_flag_absent(self):
        before = self.runtime.document(image())
        self.assertEqual(s.digest(before), 'c2660a9a8c42d40d42e7dcbe90a4dda93e5bda4354442708d182d452b55bfc22')
        self.assertEqual(set(before['services']), {'backend', 'postgres'})
        self.assertEqual(before['volumes'], {'pgdata': {}})
        self.assertNotIn('volumes', before['services']['backend'])
        original = copy.deepcopy(self.runtime.config)
        s.validate_config(self.runtime.config)
        self.assertEqual(original, self.runtime.config)

    def test_exact_opt_in_document_has_one_shared_tmpfs_and_no_worker_network_or_secrets(self):
        self.enable(); doc = self.runtime.document(image('b'))
        self.assertEqual(set(doc['services']), {'backend', 'postgres', 'pdf-worker'})
        worker, backend = doc['services']['pdf-worker'], doc['services']['backend']
        self.assertEqual(worker['image'], backend['image'])
        self.assertEqual(worker['network_mode'], 'none')
        self.assertEqual(worker['mem_limit'], 384 * 1024 * 1024)
        self.assertEqual(worker['memswap_limit'], worker['mem_limit'])
        self.assertEqual(worker['annotations'], s.PDF_SYSTEMD_ANNOTATIONS)
        self.assertNotIn('annotations', backend)
        self.assertNotIn('annotations', doc['services']['postgres'])
        old = copy.deepcopy(doc)
        del old['services']['pdf-worker']['annotations']
        self.assertEqual(s.digest(old), '2fd1bd47d22456417568bf966f508b005c5961889dac9507a7166c6fc999bffc')
        self.assertEqual(worker['cpus'], 1); self.assertEqual(worker['pids_limit'], 64)
        self.assertTrue(worker['read_only']); self.assertEqual(worker['user'], '10001:10001')
        self.assertEqual(worker['cap_drop'], ['ALL']); self.assertTrue(worker['init'])
        self.assertNotIn('environment', worker); self.assertNotIn('ports', worker)
        self.assertEqual(worker['volumes'][0]['target'], s.PDF_IPC)
        self.assertTrue(backend['volumes'][0]['read_only'])
        self.assertEqual(doc['volumes']['pdf_ipc']['driver_opts'], s.PDF_VOLUME_OPTIONS)
        self.assertEqual(doc['services']['postgres'], self.runtime.document(image())['services']['postgres'])

    def test_transition_requires_apply_config_capability_and_preserves_pg_neighbor(self):
        self.runtime.apply('install', record(image()))
        pg, neighbor = copy.deepcopy(self.runtime.pg), copy.deepcopy(self.runtime.neighbor)
        old = self.runtime.compose_file.read_bytes(); old_state = self.runtime.state()
        target = self.enable()
        with self.assertRaisesRegex(s.Refused, 'RUNTIME_CONFIG_CHANGED_USE_APPLY_CONFIG'):
            self.runtime.apply('deploy', record(target))
        self.assertEqual(old, self.runtime.compose_file.read_bytes()); self.assertEqual(old_state, self.runtime.state())
        target.pop('pdf_worker')
        with self.assertRaisesRegex(s.Refused, 'CAPABILITY'):
            self.runtime.apply('deploy', record(target), apply_config=True)
        target['pdf_worker'] = 1
        result = self.runtime.apply('deploy', record(target), apply_config=True)
        self.assertEqual(result['status'], 'SUCCESS')
        worker = next(i for i, c in enumerate(self.runtime.calls) if c[-1:] == ['pdf-worker'])
        backend = max(i for i, c in enumerate(self.runtime.calls) if c[-1:] == ['backend'])
        self.assertLess(worker, backend)
        self.assertEqual(pg, self.runtime.pg); self.assertEqual(neighbor, self.runtime.neighbor)
        self.runtime.config['runtime'].pop(s.PDF_FLAG)
        with self.assertRaisesRegex(s.Refused, 'DOWNGRADE'):
            self.runtime.apply('deploy', record(target), apply_config=True)

    def inspect_fixture(self):
        self.enable(); r = self.runtime; target = r.images[image('b')['image']]
        labels = {'com.docker.compose.project': r.project, 'com.docker.compose.project.working_dir': str(r.root)}
        mount = {'Type': 'volume', 'Name': r.project + '_pdf_ipc', 'Destination': s.PDF_IPC, 'RW': True}
        worker = {'Id': 'd' * 64, 'Image': target['id'], 'State': {'Running': True, 'Health': {'Status': 'healthy'}},
                  'Config': {'User': '10001:10001', 'Entrypoint': ['/app/app/bin/pdf-worker'], 'Cmd': [],
                             'Env': ['PATH=/safe'], 'Labels': dict(labels, **{'com.docker.compose.service': 'pdf-worker'})},
                  'HostConfig': {'Annotations': dict(s.PDF_SYSTEMD_ANNOTATIONS),
                                 'Memory': s.PDF_MEMORY, 'MemorySwap': s.PDF_MEMORY, 'NanoCpus': 1000000000,
                                 'PidsLimit': 64, 'RestartPolicy': {'Name': 'unless-stopped', 'MaximumRetryCount': 0}, 'Ulimits': [{'Name': 'core', 'Soft': 0, 'Hard': 0}], 'NetworkMode': 'none', 'ReadonlyRootfs': True, 'Privileged': False,
                                 'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'], 'Init': True, 'IpcMode': 'private', 'Mounts': [{'Type': 'volume', 'Source': r.project + '_pdf_ipc', 'Target': s.PDF_IPC, 'ReadOnly': False}]},
                  'NetworkSettings': {'Networks': {'none': {}}}, 'Mounts': [mount]}
        volume = {'Driver': 'local', 'Options': s.PDF_VOLUME_OPTIONS,
                  'Labels': {'com.docker.compose.project': r.project, 'com.docker.compose.volume': 'pdf_ipc'}}
        def run(argv, **kwargs):
            if argv[:3] == ['docker', 'volume', 'inspect']: return json.dumps([volume]).encode()
            if argv == ['docker', 'info', '--format', '{{.CgroupDriver}}']: return b'systemd\n'
            if argv == ['docker', 'exec', worker['Id'], '/bin/cat', '/sys/fs/cgroup/memory.swap.max']: return b'0\n'
            if argv == ['systemctl', 'show', 'docker-' + worker['Id'] + '.scope',
                        '--property=MemorySwapMax', '--value']: return b'0\n'
            raise AssertionError('Unexpected command ' + repr(argv))
        r.run = run
        return target, worker, volume

    def test_annotation_drift_refused_before_kernel_read(self):
        target, worker, _ = self.inspect_fixture()
        for value in (None, {}, {'org.systemd.property.MemorySwapMax': '0'},
                      {'org.systemd.property.MemorySwapMax': 'uint64 1'},
                      dict(s.PDF_SYSTEMD_ANNOTATIONS, **{'org.systemd.property.MemoryMax': 'uint64 1'})):
            with self.subTest(value=value):
                bad = copy.deepcopy(worker); bad['HostConfig']['Annotations'] = value
                with patch.object(self.runtime, 'pdf_swap_state') as limits:
                    with self.assertRaisesRegex(s.Refused, 'ANNOTATIONS_MISMATCH'):
                        s.Staging.observe_pdf_worker(self.runtime, bad, target)
                    limits.assert_not_called()

    def test_cached_healthy_does_not_admit_lost_kernel_or_systemd_limit(self):
        target, worker, _ = self.inspect_fixture()
        for actual, systemd, reason in [('max', 'infinity', 'SWAP_NOT_ZERO'),
                                        ('1', '0', 'SWAP_NOT_ZERO'),
                                        ('0', 'infinity', 'SYSTEMD_SWAP_NOT_ZERO')]:
            with self.subTest(actual=actual, systemd=systemd), patch.object(
                    self.runtime, 'pdf_swap_state', return_value={
                        'driver': 'systemd', 'cgroup': actual, 'systemd': systemd}):
                with self.assertRaisesRegex(s.Refused, reason):
                    s.Staging.observe_pdf_worker(self.runtime, worker, target)

    def test_old_managed_document_and_hash_remain_observable_without_rewrite(self):
        target, worker, _ = self.inspect_fixture()
        old = self.runtime.document(target)
        del old['services']['pdf-worker']['annotations']
        worker['HostConfig'].pop('Annotations')
        s.atomic(self.runtime.compose_file, old)
        before = self.runtime.compose_file.read_bytes()
        runtime_hash = s.digest(self.runtime.config['runtime'])
        legacy = {'driver': 'systemd', 'cgroup': '0', 'systemd': 'infinity'}
        with patch.object(self.runtime, 'pdf_swap_state', return_value=legacy):
            s.Staging.observe_pdf_worker(self.runtime, worker, target)
            legacy['cgroup'] = 'max'
            with self.assertRaisesRegex(s.Refused, 'SWAP_NOT_ZERO'):
                s.Staging.observe_pdf_worker(self.runtime, worker, target)
        self.assertEqual(before, self.runtime.compose_file.read_bytes())
        self.assertEqual(runtime_hash, s.digest(self.runtime.config['runtime']))

    def test_old_pdf_state_can_adopt_annotation_without_runtime_or_schema_change(self):
        self.runtime.apply('install', record(image()))
        target = self.enable()
        self.runtime.apply('deploy', record(target), apply_config=True)
        old = self.runtime.document(target); del old['services']['pdf-worker']['annotations']
        s.atomic(self.runtime.compose_file, old)
        state = self.runtime.state(); state['compose_hash'] = s.digest(old); self.runtime.save(state)
        pg, neighbor = copy.deepcopy(self.runtime.pg), copy.deepcopy(self.runtime.neighbor)
        self.assertEqual(self.runtime.apply('deploy', record(target))['status'], 'SUCCESS')
        after = s.private_json(self.runtime.compose_file)
        self.assertEqual(after['services']['pdf-worker'].pop('annotations'), s.PDF_SYSTEMD_ANNOTATIONS)
        self.assertEqual(after, old)
        for key in ('runtime_hash', 'config_binding', 'binding', 'target'):
            self.assertEqual(self.runtime.state()[key], state[key])
        self.assertEqual((self.runtime.pg, self.runtime.neighbor), (pg, neighbor))

    def test_unhealthy_old_worker_is_not_silently_repaired_by_deploy(self):
        self.runtime.apply('install', record(image()))
        target = self.enable()
        self.runtime.apply('deploy', record(target), apply_config=True)
        before, state, calls = self.runtime.compose_file.read_bytes(), self.runtime.state(), len(self.runtime.calls)
        with patch.object(self.runtime, 'observe', side_effect=s.Refused('PDF_WORKER_NOT_HEALTHY')):
            with self.assertRaisesRegex(s.Refused, 'PDF_WORKER_NOT_HEALTHY'):
                self.runtime.apply('deploy', record(target))
        self.assertEqual(before, self.runtime.compose_file.read_bytes())
        self.assertEqual(state, self.runtime.state()); self.assertEqual(calls, len(self.runtime.calls))

    def test_swap_reader_is_scoped_to_exact_worker_and_no_shell(self):
        target, worker, _ = self.inspect_fixture()
        original = self.runtime.run
        with patch.object(self.runtime, 'run', wraps=original) as runner:
            self.assertEqual(self.runtime.pdf_swap_state(worker),
                             {'driver': 'systemd', 'cgroup': '0', 'systemd': '0'})
            self.assertEqual([c.args[0][0] for c in runner.call_args_list], ['docker', 'docker', 'systemctl'])
            self.assertEqual(runner.call_args_list[1].args[0],
                             ['docker', 'exec', worker['Id'], '/bin/cat', '/sys/fs/cgroup/memory.swap.max'])
            for invalid in ('', '../other', 'd' * 63, 'neighbor;cmd', None):
                bad = dict(worker, Id=invalid); runner.reset_mock()
                with self.assertRaisesRegex(s.Refused, 'ID_INVALID'): self.runtime.pdf_swap_state(bad)
                runner.assert_not_called()

    def test_swap_reader_failures_do_not_pass_or_leak_output(self):
        _, worker, _ = self.inspect_fixture()
        for driver in (b'', b'unknown\n'):
            with patch.object(self.runtime, 'run', return_value=driver):
                with self.assertRaisesRegex(s.Refused, 'DRIVER_UNKNOWN'): self.runtime.pdf_swap_state(worker)
        with patch.object(self.runtime, 'run', side_effect=[b'systemd\n', b'0\n', b'\n']):
            with self.assertRaisesRegex(s.Refused, 'SCOPE_UNKNOWN'): self.runtime.pdf_swap_state(worker)
        with patch.object(self.runtime, 'run', side_effect=[b'cgroupfs\n', b'0\n']) as runner:
            self.assertEqual(self.runtime.pdf_swap_state(worker),
                             {'driver': 'cgroupfs', 'cgroup': '0', 'systemd': None})
            self.assertEqual(runner.call_count, 2)  # No systemd claim on cgroupfs.

    def test_actual_inspect_contract_accepts_only_exact_isolation(self):
        target, worker, volume = self.inspect_fixture()
        s.Staging.observe_pdf_worker(self.runtime, worker, target)
        changes = [('Memory', 0), ('MemorySwap', -1), ('NanoCpus', 0), ('PidsLimit', 0),
                   ('ReadonlyRootfs', False), ('Privileged', True), ('CapDrop', []), ('CapAdd', ['SYS_ADMIN']),
                   ('RestartPolicy', {'Name': 'no', 'MaximumRetryCount': 0}), ('Ulimits', []), ('Init', False), ('SecurityOpt', []), ('NetworkMode', 'host'), ('PidMode', 'host'),
                   ('IpcMode', 'host'), ('PortBindings', {'8080/tcp': []}), ('Binds', ['/var/run/docker.sock:/x'])]
        for key, value in changes:
            bad = copy.deepcopy(worker); bad['HostConfig'][key] = value
            with self.subTest(key=key), self.assertRaises(s.Refused):
                s.Staging.observe_pdf_worker(self.runtime, bad, target)
        for key, value in [('Env', ['DB_PASSWORD=forbidden']), ('User', 'root'), ('Entrypoint', ['/bin/sh'])]:
            bad = copy.deepcopy(worker); bad['Config'][key] = value
            with self.subTest(key=key), self.assertRaises(s.Refused):
                s.Staging.observe_pdf_worker(self.runtime, bad, target)
        for key, value in [('Name', 'neighbor_pdf_ipc'), ('RW', False), ('Destination', '/other')]:
            bad = copy.deepcopy(worker); bad['Mounts'][0][key] = value
            with self.subTest(key=key), self.assertRaises(s.Refused):
                s.Staging.observe_pdf_worker(self.runtime, bad, target)
        compose = copy.deepcopy(worker)
        compose['HostConfig'].pop('Mounts')
        compose['HostConfig']['Binds'] = [self.runtime.project + '_pdf_ipc:' + s.PDF_IPC + ':rw']
        s.Staging.observe_pdf_worker(self.runtime, compose, target)
        for bind in ('/var/run/docker.sock:' + s.PDF_IPC + ':rw', 'neighbor_pdf_ipc:' + s.PDF_IPC + ':rw',
                     self.runtime.project + '_pdf_ipc:' + s.PDF_IPC + ':ro'):
            compose['HostConfig']['Binds'] = [bind]
            with self.assertRaises(s.Refused): s.Staging.observe_pdf_worker(self.runtime, compose, target)
        bad = copy.deepcopy(worker)
        bad['HostConfig']['Mounts'][0]['VolumeOptions'] = {'Subpath': 'other'}
        with self.assertRaises(s.Refused): s.Staging.observe_pdf_worker(self.runtime, bad, target)
        volume['Options'] = {}
        with self.assertRaisesRegex(s.Refused, 'VOLUME'):
            s.Staging.observe_pdf_worker(self.runtime, worker, target)

    def test_structural_parser_is_reachable_only_in_admitted_worker_child(self):
        main = Path(__file__).resolve().parents[1] / 'backend/app/src/main/kotlin'
        sources = {p.name: p.read_text() for p in main.rglob('*.kt')}
        self.assertEqual([name for name, text in sources.items() if 'PdfReader(' in text], ['PdfStructureValidator.kt'])
        self.assertEqual([name for name, text in sources.items() if 'PdfStructureValidator.validate(' in text], ['PdfParserMain.kt'])
        validator = sources['PdfStructureValidator.kt']
        self.assertLess(validator.index('PdfWorkerIsolation.requireEnforced()'), validator.index('PdfReader('))
        self.assertIn('PdfWorkerClient()::validate', sources['VenueInfoMediaValidation.kt'])
        self.assertNotIn('PdfStructureValidator', sources['VenueInfoMediaValidation.kt'])
        self.assertNotIn('PdfReader', sources['VenueInfoMediaService.kt'])

    def test_service_allowlist_rejects_duplicate_or_foreign_worker_even_with_valid_pg(self):
        target, worker, _ = self.inspect_fixture()
        # The service guard runs before PG inspection and cannot select a foreign resource.
        for items in ([worker, worker], [dict(worker, Config={'Labels': {'com.docker.compose.service': 'other'}})]):
            self.runtime.items = items
            with self.assertRaisesRegex(s.Refused, 'UNEXPECTED_PROJECT_SERVICE'):
                s.Staging.binding(self.runtime)
        self.runtime.config['runtime'].pop(s.PDF_FLAG)
        self.runtime.items = [worker]
        with self.assertRaisesRegex(s.Refused, 'UNEXPECTED_PROJECT_SERVICE'):
            s.Staging.binding(self.runtime)


if __name__ == '__main__': unittest.main()
