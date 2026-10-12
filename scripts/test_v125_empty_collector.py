"""Synthetic filesystem/syscall observations only; never target qualification."""
import ast
from contextlib import ExitStack
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import v125_empty_collector as C

SECRET = 'SECRET_password_token_customer_example'


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.dev = f'{os.major(self.root.stat().st_dev)}:{os.minor(self.root.stat().st_dev)}'
        self.denied = set()
        self.read_paths = []
        self.put('/proc/sys/kernel/random/boot_id', '11111111-2222-3333-4444-555555555555\n')
        self.put('/proc/77/mountinfo', f'1 0 {self.dev} / / rw - ext4 /dev/sda1 rw\n')
        self.put('/proc/1/comm', 'systemd\n')
        self.put('/proc/77/comm', 'python3\n')
        for who in ('1', '77'):
            for kind in ('pid', 'mnt', 'user'):
                p = self.root / f'proc/{who}/ns/{kind}'
                p.parent.mkdir(parents=True, exist_ok=True)
                p.symlink_to(kind + ':[100]')
        for path in ['/target', '/history', '/sys/class/block', *[p for _, p in C.DIRECTORIES]]:
            (self.root / path.lstrip('/')).mkdir(parents=True, exist_ok=True)

    def put(self, path, text):
        p = self.root / path.lstrip('/')
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(text.encode() if isinstance(text, str) else text)
        return p

    def run_collector(self, **kwargs):
        original_open, original_read = os.open, os.read
        fds = {}
        def opened(path, flags, *args, **kw):
            self.assertFalse(flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND))
            logical = '/' if path == '/' else fds[kw['dir_fd']].rstrip('/') + '/' + path
            if logical in self.denied:
                raise PermissionError(SECRET)
            fd = original_open(str(self.root) if path == '/' else path, flags, *args, **kw)
            fds[fd] = logical
            return fd
        def read(fd, limit):
            self.read_paths.append(fds[fd])
            self.assertRegex(fds[fd], r'^/proc/(sys/kernel/random/boot_id|77/mountinfo|[0-9]+/comm)$')
            return original_read(fd, limit)
        with ExitStack() as stack:
            stack.enter_context(patch.object(C.sys, 'platform', 'linux'))
            stack.enter_context(patch.object(C.os, 'getpid', return_value=77))
            stack.enter_context(patch.object(C.os, 'open', side_effect=opened))
            stack.enter_context(patch.object(C.os, 'read', side_effect=read))
            for module, name in [(subprocess, 'Popen'), (socket, 'socket'), (os, 'system'),
                                 (os, 'mkdir'), (os, 'unlink'), (os, 'rename'), (os, 'chmod'),
                                 (os, 'kill'), (os, 'execve'), (os, 'posix_spawn')]:
                stack.enter_context(patch.object(module, name, side_effect=AssertionError('forbidden capability')))
            result = C.collect(kwargs.get('target', '/target'), kwargs.get('history', '/history'))
        serialized = json.dumps(result)
        self.assertNotIn(SECRET, serialized)
        self.assertNotIn('VERIFIED_EMPTY', serialized)
        self.assertEqual(result['verified_state'], 'UNKNOWN')
        self.assertEqual(result['coverage'], 'INCOMPLETE')
        self.assertIs(result['execution_authorized'], False)
        self.assertIs(result['operationally_qualified'], False)
        self.assertLess(len(serialized), 20000)
        for row in result['observations']:
            self.assertTrue(row['scope'])
            self.assertTrue(row['source'])
        return {r['scope']: r for r in result['observations']}

    def test_accessible_observations_never_qualify_empty(self):
        rows = self.run_collector()
        self.assertEqual(rows['host_visible_to_collector']['facts']['boot_id'], '11111111-2222-3333-4444-555555555555')
        self.assertEqual(rows['target_metadata']['facts']['counts']['entries'], 0)
        self.assertEqual(rows['history_metadata']['facts']['counts']['entries'], 0)
        self.assertEqual(rows['visible_process_names_only']['facts']['comm_read'], 2)
        self.assertTrue(rows['collector_and_visible_pid1_namespaces']['facts']['pid']['same'])

    def test_existing_postgres_data_services_and_engine_signals(self):
        self.put('/proc/12/comm', 'postgres\n')
        self.put('/proc/13/comm', 'dockerd\n')
        self.put('/target/db/PG_VERSION', SECRET)
        self.put('/var/lib/postgresql/17/PG_VERSION', SECRET)
        self.put('/etc/systemd/system/postgresql.service', SECRET)
        self.put('/history/operation/' + SECRET, SECRET)
        self.put('/var/lib/docker/volumes/' + SECRET, SECRET)
        rows = self.run_collector()
        self.assertEqual(rows['visible_process_names_only']['facts']['postgres_names'], 1)
        self.assertEqual(rows['visible_process_names_only']['facts']['engine_names'], 1)
        self.assertEqual(rows['target_metadata']['facts']['counts']['pg_markers'], 1)
        self.assertEqual(rows['postgres_debian']['facts']['counts']['pg_markers'], 1)
        self.assertEqual(rows['systemd_etc']['facts']['counts']['pg_service_names'], 1)
        self.assertGreater(rows['history_metadata']['facts']['counts']['entries'], 0)
        self.assertGreater(rows['docker_storage']['facts']['counts']['entries'], 0)

    def test_permission_denied_and_namespace_unavailable(self):
        self.denied.update(['/proc/1/comm', '/proc/1/ns', '/history'])
        rows = self.run_collector()
        self.assertIn('PERMISSION_DENIED', rows['visible_process_names_only']['facts']['gaps'])
        self.assertEqual(rows['collector_and_visible_pid1_namespaces']['reason'], 'PERMISSION_DENIED')
        self.assertEqual(rows['history_metadata']['reason'], 'PERMISSION_DENIED')

    def test_different_namespace_is_only_observed_not_host_proof(self):
        p = self.root / 'proc/77/ns/pid'
        p.unlink()
        p.symlink_to('pid:[200]')
        rows = self.run_collector()
        self.assertFalse(rows['collector_and_visible_pid1_namespaces']['facts']['pid']['same'])

    def test_process_disappearance_and_inventory_limit(self):
        (self.root / 'proc/88').mkdir()
        rows = self.run_collector()
        self.assertIn('MISSING_OR_DISAPPEARED', rows['visible_process_names_only']['facts']['gaps'])
        with patch.object(C, 'MAX_ENTRIES', 2):
            rows = self.run_collector()
        self.assertEqual(rows['visible_process_names_only']['reason'], 'ENTRY_LIMIT')

    def test_unknown_remote_storage_does_not_get_opened(self):
        for fs in ('nfs', 'autofs', 'fuse.unknown', 'zfs'):
            with self.subTest(fs=fs):
                self.put('/proc/77/mountinfo', f'1 0 0:1 / / rw - {fs} {SECRET} rw\n')
                rows = self.run_collector()
                self.assertEqual(rows['target_metadata']['reason'], 'UNKNOWN_OR_REMOTE_STORAGE')

    def test_nested_mount_and_unknown_engine_inventory_are_incomplete(self):
        self.put('/proc/77/mountinfo', f'1 0 {self.dev} / / rw - ext4 /dev/sda1 rw\n2 1 {self.dev} /other /target/nested rw - ext4 /dev/sda1 rw\n')
        self.put('/target/nested/PG_VERSION', SECRET)
        self.put('/proc/14/comm', 'unsupported-engine\n')
        rows = self.run_collector()
        self.assertIn('NESTED_MOUNT_NOT_TRAVERSED', rows['target_metadata']['facts']['gaps'])
        self.assertEqual(rows['target_metadata']['facts']['counts']['pg_markers'], 0)

    def test_malformed_and_oversized_proc_inputs(self):
        cases = [('/proc/77/mountinfo', SECRET), ('/proc/77/mountinfo', 'x' * (C.MAX_BYTES + 1)),
                 ('/proc/sys/kernel/random/boot_id', SECRET), ('/proc/1/comm', b'\xff'),
                 ('/proc/1/comm', 'x' * 65)]
        for path, content in cases:
            with self.subTest(path=path, size=len(content)):
                p = self.root / path.lstrip('/')
                previous = p.read_bytes()
                self.put(path, content)
                rows = self.run_collector()
                self.assertTrue(any(r['status'] in ('PARTIAL', 'UNKNOWN') for r in rows.values()))
                p.write_bytes(previous)

    def test_symlinks_are_not_followed_in_target_or_proc(self):
        self.put('/outside/PG_VERSION', SECRET)
        (self.root / 'target/escape').symlink_to(self.root / 'outside')
        rows = self.run_collector()
        self.assertIn('SYMLINK_NOT_FOLLOWED', rows['target_metadata']['facts']['gaps'])
        self.assertEqual(rows['target_metadata']['facts']['counts']['pg_markers'], 0)
        p = self.root / 'proc/1/comm'
        p.unlink()
        p.symlink_to(self.root / 'outside/PG_VERSION')
        rows = self.run_collector()
        self.assertIn('SYMLINK_OR_NOT_DIRECTORY', rows['visible_process_names_only']['facts']['gaps'])

    def test_parent_symlink_and_regular_target_refuse(self):
        (self.root / 'alias').symlink_to(self.root / 'target')
        rows = self.run_collector(target='/alias/subdir')
        self.assertEqual(rows['target_metadata']['status'], 'UNKNOWN')
        self.put('/regular', SECRET)
        rows = self.run_collector(target='/regular')
        self.assertEqual(rows['target_metadata']['status'], 'UNKNOWN')

    def test_fifos_and_secret_files_are_metadata_only(self):
        os.mkfifo(self.root / 'target/pipe')
        for name in ('.env', 'credentials', 'passwords', SECRET):
            self.put('/target/' + name, SECRET)
        with (self.root / 'target/large').open('wb') as f:
            f.truncate(2**30)
        rows = self.run_collector()
        self.assertIn('SPECIAL_FILE_NOT_OPENED', rows['target_metadata']['facts']['gaps'])
        self.assertEqual(rows['target_metadata']['facts']['counts']['regular'], 5)

    def test_proc_fifo_does_not_block(self):
        p = self.root / 'proc/1/comm'
        p.unlink()
        os.mkfifo(p)
        rows = self.run_collector()
        self.assertIn('UNEXPECTED_FILE', rows['visible_process_names_only']['facts']['gaps'])

    def test_depth_and_node_limits_preserve_incomplete(self):
        self.put('/target/a/b/c/d/PG_VERSION', SECRET)
        rows = self.run_collector()
        self.assertIn('DEPTH_LIMIT', rows['target_metadata']['facts']['gaps'])
        with patch.object(C, 'MAX_NODES', 1):
            rows = self.run_collector()
        self.assertIn('NODE_LIMIT', rows['target_metadata']['facts']['gaps'])

    def test_mount_device_mismatch_and_stacked_mounts(self):
        self.put('/proc/77/mountinfo', '1 0 999:999 / / rw - ext4 /dev/example rw\n')
        rows = self.run_collector()
        self.assertEqual(rows['target_metadata']['reason'], 'MOUNT_DEVICE_MISMATCH')
        self.put('/proc/77/mountinfo', f'1 0 {self.dev} / / rw - ext4 /dev/example rw\n2 1 {self.dev} / / rw - ext4 /dev/example rw\n')
        rows = self.run_collector()
        self.assertEqual(rows['target_metadata']['reason'], 'AMBIGUOUS_MOUNT')

    def test_partial_directory_preserves_found_pg_marker(self):
        self.put('/target/PG_VERSION', SECRET)
        (self.root / 'target/zzz-denied').mkdir()
        self.denied.add('/target/zzz-denied')
        row = self.run_collector()['target_metadata']
        self.assertEqual(row['status'], 'PARTIAL')
        self.assertEqual(row['facts']['counts']['pg_markers'], 1)
        self.assertIn('PERMISSION_DENIED', row['facts']['gaps'])

    def test_process_set_changes_during_observation(self):
        original = C.names
        calls = 0
        def changing(fd):
            nonlocal calls
            found = original(fd)
            if '77' in found:
                calls += 1
                if calls == 2:
                    return [*found, '999']
            return found
        with patch.object(C, 'names', side_effect=changing):
            row = self.run_collector()['visible_process_names_only']
        self.assertIn('PROCESS_SET_CHANGED', row['facts']['gaps'])

    def test_changed_directory_detected_without_real_mutation(self):
        original = C.stable
        calls = 0
        def changed(s):
            nonlocal calls
            calls += 1
            return (*original(s), calls)
        with patch.object(C, 'stable', side_effect=changed):
            rows = self.run_collector()
        self.assertIn('DIRECTORY_CHANGED', rows['target_metadata']['facts']['gaps'])

    def test_proc_hardlink_is_rejected(self):
        os.link(self.root / 'proc/1/comm', self.root / 'hardlink')
        rows = self.run_collector()
        self.assertIn('UNEXPECTED_FILE', rows['visible_process_names_only']['facts']['gaps'])

    def test_malformed_namespace_no_arbitrary_readlink_output(self):
        p = self.root / 'proc/1/ns/pid'
        p.unlink()
        p.symlink_to(SECRET)
        rows = self.run_collector()
        self.assertEqual(rows['collector_and_visible_pid1_namespaces']['reason'], 'MALFORMED_NAMESPACE')

    def test_no_observation_writes_or_process_network_capabilities(self):
        self.put('/target/PG_VERSION', SECRET)
        before = {str(p): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.run_collector()
        after = {str(p): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.assertEqual(before, after)
        code = ast.parse(Path(C.__file__).read_text())
        imports = {n.names[0].name for n in ast.walk(code) if isinstance(n, ast.Import)}
        self.assertEqual(imports, {'argparse', 'errno', 'json', 'os', 're', 'stat', 'sys'})
        forbidden = {'exec', 'eval', 'compile', '__import__', 'open', 'write', 'system', 'popen',
                     'Popen', 'run', 'spawn', 'fork', 'kill', 'mkdir', 'unlink', 'rename', 'chmod',
                     'chown', 'truncate', 'socket', 'connect', 'execve'}
        calls = {n.func.id for n in ast.walk(code) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)}
        self.assertFalse(calls & forbidden)
        attributes = {n.func.attr for n in ast.walk(code) if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute)}
        self.assertFalse(attributes & (forbidden - {'open'}))

    def test_invalid_paths_never_allow_traversal(self):
        for path in ('/', 'relative', '/target/../secret', '/target//x', '/target/.', '/target\n' + SECRET):
            with self.subTest(path=path), self.assertRaises(C.Gap):
                C.components(path)

    def test_cli_unknown_options_do_not_echo_secrets_or_enable_authority(self):
        with patch('sys.stdout', new_callable=io.StringIO) as output:
            self.assertEqual(C.main(['--target-path', '/target', '--history-path', '/history',
                                     '--approved', SECRET]), 2)
            self.assertNotIn(SECRET, output.getvalue())
            self.assertIs(json.loads(output.getvalue())['execution_authorized'], False)

    def test_cli_returns_nonqualification_and_redacts_errors(self):
        with patch.object(C.sys, 'platform', 'darwin'), patch('sys.stdout', new_callable=io.StringIO) as output:
            self.assertEqual(C.main(['--target-path', '/target', '--history-path', '/history']), 2)
            self.assertIn('UNSUPPORTED_PLATFORM', output.getvalue())
        with patch.object(C.sys, 'platform', 'linux'), patch('sys.stdout', new_callable=io.StringIO) as output:
            self.assertEqual(C.main(['--target-path', SECRET, '--history-path', '/history']), 2)
            self.assertNotIn(SECRET, output.getvalue())
            self.assertIn('INVALID_INPUT', output.getvalue())


if __name__ == '__main__':
    unittest.main()
