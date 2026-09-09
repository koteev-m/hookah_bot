#!/usr/bin/env python3
"""Actual embedded supervisor; synthetic leaf effects only. No application/live target.

Linux cases require a test-owned sshd endpoint and exercise real process lifetime.
On macOS only the fail-before-allocation platform refusal runs; this is an explicit
runtime gap. CI passes --require-linux-ssh, so unavailable runtime is a failure.
"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'v126-cutover.sh'
REQUIRE = '--require-linux-ssh' in sys.argv
if REQUIRE:
    sys.argv.remove('--require-linux-ssh')
    if sys.platform != 'linux' or not Path('/usr/sbin/sshd').is_file():
        raise SystemExit('Linux with test-owned OpenSSH endpoint is required; runtime gap OPEN')
PROGRAM = subprocess.check_output(['bash', '-c', 'source "$1"; remote_operation_python',
                                   'repair-extract', str(SOURCE)], text=True)
WORKER = b'''remote_dispatch_action() {
  case "$5" in
    success) printf 'synthetic operation complete\\n'; printf x >> "$2/effects" ;;
    failure) printf x >> "$2/effects"; return 42 ;;
    delayed) printf ready > "$2/ready"; sleep 2; printf x >> "$2/effects" ;;
    after-effect) printf x >> "$2/effects"; printf ready > "$2/ready"; sleep 2 ;;
    detached)
      python3 -c 'import os,pathlib,subprocess,sys; subprocess.Popen([sys.executable,"-c","import pathlib,time,sys; time.sleep(2); pathlib.Path(sys.argv[1]).write_text(chr(120))",sys.argv[1]],start_new_session=True,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)' "$2/effects"
      ;;
    failed-child) sh -c 'sleep .1; exit 42' & ;;
    *) return 98 ;;
  esac
}
'''


class Supervisor(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-owned-operation-')
        self.root = Path(self.temp.name).resolve()
        self.target = self.root / 'target'
        self.target.mkdir(mode=0o700)
        self.program = self.root / 'supervisor.py'
        self.program.write_text(PROGRAM)
        self.children = []

    def tearDown(self):
        for process in self.children:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=15)
        self.temp.cleanup()

    def args(self, mode='success', intent='b', run='repair-owned-run', kind='STAGE', name=None):
        name = name or ('BASELINE_VERIFIED' if kind == 'STAGE' else 'pre-v126')
        action = 'baseline' if kind == 'STAGE' else 'recover-pre-v126'
        return [str(self.target), run, 'a' * 40, hashlib.sha256(WORKER).hexdigest(),
                intent * 64, kind, name, action, str(self.target), run, 'a' * 40, mode]

    def run_operation(self, **kwargs):
        return subprocess.run([sys.executable, str(self.program), *self.args(**kwargs)],
                              input=WORKER, capture_output=True, timeout=15)

    def start(self, **kwargs):
        process = subprocess.Popen([sys.executable, str(self.program), *self.args(**kwargs)],
                                   stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
        self.children.append(process)
        process.stdin.write(WORKER)
        process.stdin.close()
        return process

    def wait_file(self, path):
        deadline = time.monotonic() + 8
        while not path.exists() and time.monotonic() < deadline:
            time.sleep(.05)
        self.assertTrue(path.exists())

    @unittest.skipIf(sys.platform == 'linux', 'non-Linux refusal only')
    def test_unsupported_platform_refuses_before_allocation(self):
        result = self.run_operation()
        self.assertEqual(result.returncode, 75)
        self.assertIn(b'linux_subreaper_required', result.stderr)
        self.assertEqual(list(self.target.iterdir()), [])

    def test_detached_fixture_executes_the_exact_nested_payload(self):
        # Run the same leaf submitted to the Linux supervisor. This portability
        # check catches nested quoting errors without simulating subreaper proof.
        result = subprocess.run(['bash', '-c', WORKER.decode() +
                                 '\nremote_dispatch_action unused "$1" unused unused detached\n',
                                 'detached-fixture', str(self.target)], capture_output=True, timeout=6)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.wait_file(self.target / 'effects')
        self.assertEqual((self.target / 'effects').read_text(), 'x')

    @unittest.skipUnless(sys.platform == 'linux', 'Linux subreaper runtime unavailable')
    def test_exact_ack_and_no_repeat_or_second_run(self):
        result = self.run_operation()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(b'REMOTE_OPERATION_ACK', result.stdout)
        snapshot = {p: p.read_bytes() for p in self.target.rglob('*') if p.is_file()}
        self.assertEqual(self.run_operation().returncode, 75)
        self.assertEqual(self.run_operation(run='other-local-state-directory').returncode, 75)
        for path, raw in snapshot.items():
            self.assertEqual(path.read_bytes(), raw)
        self.assertEqual((self.target / 'effects').read_text(), 'x')

    @unittest.skipUnless(sys.platform == 'linux', 'Linux subreaper runtime unavailable')
    def test_failed_mutation_remains_unknown_and_blocks_recovery(self):
        self.assertEqual(self.run_operation(mode='failure').returncode, 42)
        self.assertEqual(self.run_operation(intent='c').returncode, 75)
        self.assertEqual(self.run_operation(intent='c', kind='RECOVERY').returncode, 75)
        self.assertEqual(self.run_operation(intent='d').returncode, 75)
        self.assertEqual((self.target / 'effects').read_text(), 'x')
        result = next((self.target / '.v126-target-operations').glob('*.result.json'))
        self.assertEqual(json.loads(result.read_text())['outcome'], 'UNKNOWN')

    @unittest.skipUnless(sys.platform == 'linux', 'Linux subreaper runtime unavailable')
    def test_detached_child_keeps_lock_and_defers_completion(self):
        process = self.start(mode='detached')
        self.wait_file(next(iter([self.target / '.v126-target-operations'])))
        time.sleep(.25)
        self.assertEqual(self.run_operation(intent='c', kind='RECOVERY').returncode, 75)
        self.assertEqual(process.wait(timeout=10), 0)
        self.assertEqual((self.target / 'effects').read_text(), 'x')
        result = next((self.target / '.v126-target-operations').glob('*.result.json'))
        self.assertEqual(json.loads(result.read_text())['children'], 'REAPED')

    @unittest.skipUnless(sys.platform == 'linux', 'Linux subreaper runtime unavailable')
    def test_crash_never_becomes_completion_when_child_exits(self):
        process = self.start(mode='delayed')
        self.wait_file(self.target / 'ready')
        process.kill()
        process.wait(timeout=5)
        self.assertEqual(self.run_operation(intent='c', kind='RECOVERY').returncode, 75)
        self.wait_file(self.target / 'effects')
        self.assertEqual(self.run_operation(intent='c', kind='RECOVERY').returncode, 75)
        self.assertFalse(list((self.target / '.v126-target-operations').glob('*.result.json')))

    @unittest.skipUnless(sys.platform == 'linux', 'Linux subreaper runtime unavailable')
    def test_completed_run_retirement_then_next_baseline_preserves_all_history(self):
        # Real supervisor records and real binding consumer. The terminal action
        # is a synthetic leaf; terminal receipt/handoff are independently covered
        # by the actual CLI tests, not claimed as full cutover E2E here.
        self.assertEqual(self.run_operation().returncode, 0)
        self.assertEqual(self.run_operation(intent='c', name='FINAL_PUBLIC_GATES_PASSED').returncode, 0)
        root = self.target / '.v126-target-operations'
        snapshot = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
        import importlib.util
        spec = importlib.util.spec_from_file_location('bindings', ROOT / 'v126-operation-bindings.py')
        bindings = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bindings)
        owner = json.loads((root / 'run.json').read_text())
        next_owner = dict(owner, run_id='repair-next-owned-run')
        handoff = dict(format_version=1, owner=owner, next_owner=next_owner,
            terminal_receipt_sha256='d'*64, target_sha256=hashlib.sha256(str(self.target).encode()).hexdigest(),
            operational_version='V126', backend_image='synthetic:' + owner['release_sha'],
            image_id='sha256:'+'e'*64, environment_sha256='1'*64, compose_sha256='2'*64,
            caddy_runtime_sha256='3'*64, config_owner='root:root', restart_policy='unless-stopped',
            handoff_approved_and_applied=True, approval_id='synthetic-approval', observed_at='2026-09-09T00:00:00Z')
        path = self.root / 'handoff.json'
        path.write_bytes(bindings.binding_canonical(handoff)); path.chmod(0o400)
        from unittest.mock import patch
        with patch.object(sys, 'argv', ['retire', str(self.target), *[owner[key] for key in ('run_id', 'release_sha', 'script_sha256')], 'd'*64, str(path),
                                      *[next_owner[key] for key in ('run_id', 'release_sha', 'script_sha256')], 'V126', handoff['image_id'], 'retire']):
            bindings.binding_entry('retire')
        self.assertEqual(self.run_operation(intent='d').returncode, 75)
        self.assertEqual(self.run_operation(intent='e', run=next_owner['run_id'], kind='RECOVERY').returncode, 75)
        self.assertEqual(self.run_operation(intent='f', run=next_owner['run_id']).returncode, 0)
        self.assertEqual(self.run_operation(intent='f', run=next_owner['run_id']).returncode, 75)
        self.assertEqual((self.target / 'effects').read_text(), 'xxx')
        for path, raw in snapshot.items():
            self.assertEqual(path.read_bytes(), raw)
        self.assertEqual(bindings.binding_chain(root, self.target)[0], next_owner)

    def own_ssh_endpoint(self):
        sshd = Path('/usr/sbin/sshd')
        self.assertTrue(sshd.is_file(), 'OpenSSH server required on isolated Linux runner')
        for name in ('host', 'client'):
            subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(self.root / name)],
                           check=True, capture_output=True, timeout=15)
        authorized = self.root / 'authorized_keys'
        authorized.write_bytes((self.root / 'client.pub').read_bytes())
        authorized.chmod(0o600)
        with socket.socket() as reserve:
            reserve.bind(('127.0.0.1', 0))
            port = reserve.getsockname()[1]
        config = self.root / 'sshd_config'
        config.write_text(f'''Port {port}
ListenAddress 127.0.0.1
HostKey {self.root / 'host'}
PidFile {self.root / 'sshd.pid'}
AuthorizedKeysFile {authorized}
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
# Synthetic endpoint only: its exact key file lives under the owned /tmp fixture.
StrictModes no
AllowUsers {pwd.getpwuid(os.getuid()).pw_name}
''')
        server_log = open(self.root / 'sshd.log', 'wb')
        server = subprocess.Popen([str(sshd), '-D', '-e', '-f', str(config)], stdout=server_log,
                                  stderr=server_log)
        self.children.append(server)
        hostkey = (self.root / 'host.pub').read_text().split()
        known = self.root / 'known_hosts'
        known.write_text(f'[127.0.0.1]:{port} {hostkey[0]} {hostkey[1]}\n')
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if server.poll() is not None:
                self.fail('test-owned sshd failed; isolated runner setup required')
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=.2):
                    break
            except OSError:
                time.sleep(.05)
        self.addCleanup(server_log.close)
        return port

    def disconnect_case(self, mode):
        port = self.own_ssh_endpoint()
        known = self.root / 'known_hosts'
        import shlex
        remote = shlex.join([sys.executable, str(self.program), *self.args(mode=mode)])
        transport = subprocess.Popen(['ssh', '-F', '/dev/null', '-T', '-p', str(port),
            '-i', str(self.root / 'client'), '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + str(known),
            '-o', 'ConnectTimeout=3', '127.0.0.1', remote], stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.children.append(transport)
        transport.stdin.write(WORKER)
        transport.stdin.close()
        self.wait_file(self.target / 'ready')
        transport.kill()
        transport.wait(timeout=5)
        self.assertEqual(self.run_operation(intent='c', kind='RECOVERY').returncode, 75)
        self.wait_file(self.target / 'effects')
        deadline = time.monotonic() + 5
        while not list((self.target / '.v126-target-operations').glob('*.result.json')) and time.monotonic() < deadline:
            time.sleep(.05)
        self.assertEqual(len(list((self.target / '.v126-target-operations').glob('*.result.json'))), 1)
        self.assertEqual((self.target / 'effects').read_text(), 'x')
        self.assertEqual(self.run_operation().returncode, 75)

    @unittest.skipUnless(sys.platform == 'linux', 'Linux own SSH endpoint unavailable')
    def test_ssh_disconnect_during_side_effect_blocks_concurrent_recovery(self):
        self.disconnect_case('delayed')

    @unittest.skipUnless(sys.platform == 'linux', 'Linux own SSH endpoint unavailable')
    def test_ssh_disconnect_after_side_effect_never_authorizes_replay(self):
        self.disconnect_case('after-effect')

    @unittest.skipUnless(sys.platform == 'linux', 'Linux own SSH endpoint unavailable')
    def test_transport_refusal_before_dispatch_allocates_no_operation(self):
        port = self.own_ssh_endpoint()
        wrong_hosts = self.root / 'untrusted_hosts'
        wrong_hosts.write_text('')
        import shlex
        remote = shlex.join([sys.executable, str(self.program), *self.args()])
        result = subprocess.run(['ssh', '-F', '/dev/null', '-T', '-p', str(port),
            '-i', str(self.root / 'client'), '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + str(wrong_hosts),
            '-o', 'ConnectTimeout=3', '127.0.0.1', remote], input=WORKER,
            capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(self.target.iterdir()), [])

    @unittest.skipUnless(sys.platform == 'linux', 'Linux subreaper runtime unavailable')
    def test_failed_child_remains_unknown_and_blocks_recovery(self):
        self.assertEqual(self.run_operation(mode='failed-child').returncode, 75)
        self.assertEqual(self.run_operation(intent='c', kind='RECOVERY').returncode, 75)
        result = next((self.target / '.v126-target-operations').glob('*.result.json'))
        self.assertEqual(json.loads(result.read_text())['outcome'], 'UNKNOWN')


if __name__ == '__main__':
    unittest.main()
