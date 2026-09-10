#!/usr/bin/env python3
"""Actual embedded supervisor; synthetic leaf effects only. No application/live target.

Linux cases require a test-owned sshd endpoint and exercise real process lifetime.
On macOS only the fail-before-allocation platform refusal runs; this is an explicit
runtime gap. CI passes --require-linux-ssh, so unavailable runtime is a failure.
"""
import hashlib
import ast
import json
import os
from pathlib import Path
import pwd
import re
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
BEFORE_SOURCE = None
BEFORE_SOURCE_SHA256 = '5911db8d4a0fc43a7ddb75b0cdaf2c0ef2c29720142715899463813226b8b98f'
BEFORE_PROGRAM_SHA256 = '5f82928591eb40803c7400208e415f76e30c9eadacf5a20cb7416aa0028ddfdb'
if '--before-supervisor-source' in sys.argv:
    index = sys.argv.index('--before-supervisor-source')
    if index + 1 >= len(sys.argv):
        raise SystemExit('--before-supervisor-source requires the exact 09e source file')
    BEFORE_SOURCE = Path(sys.argv[index + 1]).resolve(strict=True)
    del sys.argv[index:index + 2]
    if hashlib.sha256(BEFORE_SOURCE.read_bytes()).hexdigest() != BEFORE_SOURCE_SHA256:
        raise SystemExit('before supervisor source differs from exact candidate09e bytes')
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
        self.retain = False

    def tearDown(self):
        for process in self.children:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=15)
        if self.retain:
            self.temp._finalizer.detach()
        else:
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

    def test_production_create_arguments_use_the_real_compose_parser(self):
        if not shutil.which('docker'):
            if REQUIRE: self.fail('real Compose parser is required on the mandatory runner')
            self.skipTest('Docker Compose CLI unavailable')
        compose = self.root / 'docker-compose.yml'
        compose.write_text('services:\n  backend:\n    image: synthetic-parser-only:unreachable\n')
        (self.root / '.env').write_text('')
        docker_host = 'unix://' + str(self.root / 'absent-owned-daemon.sock')
        clean = {key: os.environ[key] for key in ('PATH', 'HOME')}
        source = SOURCE.read_text()
        for function in ('remote_start_v126', 'remote_recover_pre_v126'):
            body = re.search(r'(?ms)^' + function + r'\(\) \{\n(.*?)^\}', source)
            self.assertIsNotNone(body, function)
            commands = re.findall(r'(?m)^  remote_compose create[^\n]*\n    die [^\n]*', body.group(1))
            self.assertEqual(len(commands), 1, function)
            capture = self.root / (function + '.argv')
            # Only remote_compose's external invocation is captured. Execute the
            # exact production command plus its real nonzero failure consumer.
            script = 'source "$1"; CAPTURE="$2"; remote_compose() { printf "%s\\0" "$@" > "$CAPTURE"; return 87; };\n' + commands[0]
            result = subprocess.run(['bash', '-c', script, 'argv-fixture', str(SOURCE), str(capture)],
                                    capture_output=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b'backend create failed', result.stderr)
            argv = [value.decode() for value in capture.read_bytes().split(b'\0') if value]
            self.assertEqual(argv[0], 'create')
            self.assertEqual(argv[-1], 'backend')
            parser = ['docker', '--host', docker_host, 'compose', '--env-file', str(self.root / '.env'),
                      '--file', str(compose), *argv]
            help_result = subprocess.run([*parser, '--help'], capture_output=True, timeout=10, env=clean)
            self.assertEqual(help_result.returncode, 0, help_result.stderr)
            result = subprocess.run(parser, capture_output=True, timeout=10, env=clean)
            self.assertNotEqual(result.returncode, 0, 'parser fixture must not reach any daemon')
            self.assertIn(str(self.root / 'absent-owned-daemon.sock').encode(), result.stderr)
            self.assertNotIn(b'unknown flag', result.stderr)
            self.assertFalse((self.root / 'absent-owned-daemon.sock').exists())

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


SHARED_RUNNER = r'''import hashlib,importlib.util,json,os,signal,sys,time
from pathlib import Path
target=Path(sys.argv[2]);mode=sys.argv[3];kind=sys.argv[4]
spec=importlib.util.spec_from_file_location('actual_operation_bindings',sys.argv[1])
bindings=importlib.util.module_from_spec(spec);spec.loader.exec_module(bindings)
observations=[]
real_waitpid=os.waitpid
def observed_waitpid(pid,options):
 result=real_waitpid(pid,options)
 if result[0]:
  observations.append({'event':'actual_reap','pid':result[0],'exit':os.waitstatus_to_exitcode(result[1])})
  if mode=='cancel-on-zero-reap' and os.waitstatus_to_exitcode(result[1])==0:
   # Deterministic timing injection only: retain the real waitpid result and
   # deliver a real SIGTERM immediately before its consumer receives it.
   os.kill(os.getpid(),signal.SIGTERM)
   observations.append({'event':'actual_sigterm_after_zero_reap'})
 return result
os.waitpid=observed_waitpid
def audit(event,args):
 if event in ('os.killpg','os.kill'):
  observations.append({'event':event,'pid':args[0],'signal':args[1]})
sys.addaudithook(audit)
if mode=='input-backpressure':
 worker='import time;time.sleep(3)'
elif mode=='cancel-on-zero-reap':
 worker='raise SystemExit(0)'
elif mode=='detached-after-leader':
 child='import pathlib,sys,time;pathlib.Path(sys.argv[1]).write_text(str(__import__("os").getpid()));time.sleep(2);pathlib.Path(sys.argv[2]).write_text("late")'
 worker=('import os,pathlib,subprocess,sys,time;'
         'pathlib.Path(sys.argv[1]).write_text(str(os.getpid()));'
         'subprocess.Popen([sys.executable,"-c",'+repr(child)+',sys.argv[2],sys.argv[3]],start_new_session=True,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);'
         'deadline=time.monotonic()+1;'
         '\nwhile not pathlib.Path(sys.argv[2]).exists() and time.monotonic()<deadline:time.sleep(.01)\n')
else:
 raise ValueError('unknown explicit synthetic leaf')
identity=dict(run_id='actual-common-helper-fixture',release_sha='a'*40,
 script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),intent_sha256=('c' if kind=='RECOVERY' else 'b')*64,
 kind=kind,name='pre-v126' if kind=='RECOVERY' else 'BASELINE_VERIFIED',
 action='recover-pre-v126' if kind=='RECOVERY' else 'baseline')
try:
 status=bindings.binding_supervise(target,identity,[sys.executable,'-c',worker,str(target/'leader.pid'),str(target/'descendant.pid'),str(target/'late-effect')],
  input_data=b'x'*(2*1024*1024) if mode=='input-backpressure' else b'',
  env={key:os.environ[key] for key in ('PATH','HOME')},timeout=.5)
 raise SystemExit(status)
finally:
 Path(sys.argv[5]).write_text(json.dumps(observations,sort_keys=True)+'\n')
'''


class SharedSupervisor(unittest.TestCase):
    """Real Linux common helper, real children; explicit scheduling observation.

    The waitpid wrapper returns every actual kernel result unchanged. Only the
    cancellation race case injects a real signal at that observation point. The
    syscall audit hook observes actual signals without replacing their consumer.
    """
    tearDown = Supervisor.tearDown
    args = Supervisor.args

    def setUp(self):
        Supervisor.setUp(self)
        self.shared_runner = self.root / 'shared-runner.py'
        self.shared_runner.write_text(SHARED_RUNNER)

    def shared(self, mode, kind='STAGE'):
        observations = self.root / (mode + '-' + kind + '.observations.json')
        process = subprocess.Popen([sys.executable, str(self.shared_runner), str(ROOT / 'v126-operation-bindings.py'),
                                    str(self.target), mode, kind, str(observations)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        self.children.append(process)
        started = time.monotonic()
        try:
            out, err = process.communicate(timeout=6)
        except subprocess.TimeoutExpired:
            # Do not delete paths while a failed supervisor might still own
            # descendants. All explicit synthetic children have a 3s lifetime.
            self.retain = True
            process.send_signal(signal.SIGTERM)
            try:
                process.communicate(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate(timeout=5)
            self.fail('shared supervisor exceeded the outer bound; fixture retained')
        return subprocess.CompletedProcess(process.args, process.returncode, out, err), time.monotonic() - started, json.loads(observations.read_text())

    def assert_unknown_without_retry(self, result, elapsed, mode):
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertLess(elapsed, 3)
        registry = self.target / '.v126-target-operations'
        records = list(registry.glob('*.result.json'))
        self.assertEqual(len(records), 1)
        outcome = json.loads(records[0].read_text())
        self.assertEqual((outcome['exit'], outcome['outcome'], outcome['children']), (124, 'UNKNOWN', 'REAPED'))
        self.assertNotIn(b'ARTIFACT\t', result.stdout)
        self.assertFalse(list(self.target.rglob('*.proof')))
        self.assertFalse(list(self.target.rglob('*.receipt.json')))
        snapshot = {path: path.read_bytes() for path in registry.rglob('*') if path.is_file()}
        recovery, _, _ = self.shared(mode, kind='RECOVERY')
        self.assertEqual(recovery.returncode, 75)
        for path, raw in snapshot.items():
            self.assertEqual(path.read_bytes(), raw)

    def test_synthetic_shared_leaf_programs_compile(self):
        # Construct and compile only fixture strings. No common-helper runtime
        # branch or Linux platform identity is simulated on this host.
        parsed = ast.parse(SHARED_RUNNER)
        branch = next(node for node in parsed.body if isinstance(node, ast.If))
        for mode in ('input-backpressure', 'cancel-on-zero-reap', 'detached-after-leader'):
            scope = {'mode': mode}
            exec(compile(ast.Module(body=[branch], type_ignores=[]), '<fixture-selection>', 'exec'), scope)
            compile(scope['worker'], '<synthetic-worker>', 'exec')
            if 'child' in scope:
                compile(scope['child'], '<synthetic-detached-child>', 'exec')

    @unittest.skipUnless(sys.platform == 'linux', 'real Linux subreaper required; never simulated on macOS')
    def test_input_backpressure_does_not_block_real_deadline(self):
        result, elapsed, observations = self.shared('input-backpressure')
        self.assert_unknown_without_retry(result, elapsed, 'input-backpressure')
        self.assertTrue(any(row['event'] == 'actual_reap' for row in observations))

    @unittest.skipUnless(sys.platform == 'linux', 'real Linux subreaper required; never simulated on macOS')
    def test_real_zero_exit_cannot_erase_latched_cancellation(self):
        result, elapsed, observations = self.shared('cancel-on-zero-reap')
        self.assert_unknown_without_retry(result, elapsed, 'cancel-on-zero-reap')
        self.assertTrue(any(row['event'] == 'actual_reap' and row['exit'] == 0 for row in observations))
        self.assertEqual(sum(row['event'] == 'actual_sigterm_after_zero_reap' for row in observations), 1)

    @unittest.skipUnless(sys.platform == 'linux', 'real Linux subreaper required; never simulated on macOS')
    def test_reaped_leader_is_never_signalled_as_a_process_group(self):
        result, elapsed, observations = self.shared('detached-after-leader')
        self.assert_unknown_without_retry(result, elapsed, 'detached-after-leader')
        leader = int((self.target / 'leader.pid').read_text())
        descendant = int((self.target / 'descendant.pid').read_text())
        zero = next(index for index, row in enumerate(observations)
                    if row['event'] == 'actual_reap' and row['pid'] == leader and row['exit'] == 0)
        self.assertFalse(any(row['event'] == 'os.killpg' and row['pid'] == leader for row in observations[zero + 1:]))
        self.assertTrue(any(row['event'] == 'os.kill' and row['pid'] == descendant and row['signal'] == signal.SIGKILL
                            for row in observations[zero + 1:]))
        self.assertTrue(any(row['event'] == 'actual_reap' and row['pid'] == descendant for row in observations))
        self.assertFalse((self.target / 'late-effect').exists())

    @unittest.skipUnless(sys.platform == 'linux' and BEFORE_SOURCE is not None,
                         'exact09e before requires an explicitly supplied source on Linux')
    def test_exact_before_timeout_exit_is_overwritten_by_real_sigkill_status(self):
        before = subprocess.check_output(['bash', '-c', 'source "$1"; remote_operation_python',
                                          'exact-before-extract', str(BEFORE_SOURCE)])
        self.assertEqual(hashlib.sha256(before).hexdigest(), BEFORE_PROGRAM_SHA256)
        program = self.root / 'exact-before.py'
        program.write_bytes(before)
        # Clock-only mock compresses the original300s deadline to0.3s. No
        # supervisor algorithm, worker process, waitpid or signal is replaced.
        wrapper = ('import sys,time;real=time.monotonic;origin=real();'
                   'time.monotonic=lambda:origin+(real()-origin)*1000;'
                   'path=sys.argv.pop(1);exec(compile(open(path).read(),path,"exec"))')
        started = time.monotonic()
        result = subprocess.run([sys.executable, '-c', wrapper, str(program), *self.args(mode='delayed')],
                                input=WORKER, capture_output=True, timeout=6)
        self.assertLess(time.monotonic() - started, 3)
        self.assertEqual(result.returncode, 137, result.stderr)
        outcome = json.loads(next((self.target / '.v126-target-operations').glob('*.result.json')).read_text())
        self.assertEqual((outcome['exit'], outcome['outcome']), (137, 'UNKNOWN'))
        self.assertFalse((self.target / 'effects').exists())
        print('EXACT09E_BEFORE timeout_status_overwritten=137 outcome=UNKNOWN clock_scale=1000 '
              'source_sha256=' + BEFORE_SOURCE_SHA256, flush=True)


if __name__ == '__main__':
    unittest.main()
