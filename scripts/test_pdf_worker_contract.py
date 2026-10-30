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
from test_simple_staging import MemoryStaging, config, image, record


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
        worker = {'Image': target['id'], 'State': {'Running': True, 'Health': {'Status': 'healthy'}},
                  'Config': {'User': '10001:10001', 'Entrypoint': ['/app/app/bin/pdf-worker'], 'Cmd': [],
                             'Env': ['PATH=/safe'], 'Labels': dict(labels, **{'com.docker.compose.service': 'pdf-worker'})},
                  'HostConfig': {'Memory': s.PDF_MEMORY, 'MemorySwap': s.PDF_MEMORY, 'NanoCpus': 1000000000,
                                 'PidsLimit': 64, 'RestartPolicy': {'Name': 'unless-stopped', 'MaximumRetryCount': 0}, 'Ulimits': [{'Name': 'core', 'Soft': 0, 'Hard': 0}], 'NetworkMode': 'none', 'ReadonlyRootfs': True, 'Privileged': False,
                                 'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'], 'Init': True, 'IpcMode': 'private', 'Mounts': [{'Type': 'volume', 'Source': r.project + '_pdf_ipc', 'Target': s.PDF_IPC, 'ReadOnly': False}]},
                  'NetworkSettings': {'Networks': {'none': {}}}, 'Mounts': [mount]}
        volume = {'Driver': 'local', 'Options': s.PDF_VOLUME_OPTIONS,
                  'Labels': {'com.docker.compose.project': r.project, 'com.docker.compose.volume': 'pdf_ipc'}}
        r.run = lambda argv, **kwargs: json.dumps([volume]).encode()
        return target, worker, volume

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
