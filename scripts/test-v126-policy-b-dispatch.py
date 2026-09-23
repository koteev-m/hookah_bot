#!/usr/bin/env python3
"""Real target supervisor/Policy B consumer, with inert action processes.

No SSH, provider, Docker, database, migration, backup or restore commands run.
The OS subreaper and /proc observations are synthetic; flock and immutable
operation/history validation are real. This is not Linux supervision evidence.
"""
import ast
import builtins
from contextlib import ExitStack, contextmanager
from dataclasses import replace
import ctypes
import fcntl
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'v126-operation-bindings.py'
CUTOVER = ROOT / 'v126-cutover.sh'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


bindings = load('policy_b_dispatch_bindings', SOURCE)
init_cases = load('policy_b_init_cases', ROOT / 'test-v126-policy-b-init.py')
cases = load('policy_b_dispatch_cases', ROOT / 'test-v126-policy-b-adapter.py')
adapter = cases.adapter
dr = adapter.dr


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def write(path, value):
    path.write_bytes(canonical(value))
    path.chmod(0o400)


class InertRuntime:
    """Allow only the synthetic leaf and explicit local read-only Git queries.

Fake Popen never launches the leaf; it records the exact protected action.
Real subprocesses are limited to the consumer's exact read-only Git calls and
the exact native receipt verifier command. Real network clients are unreachable.
"""
    def __init__(self, target, observations):
        self.target = target
        self.observations = observations
        self.calls = []
        self.children = []
        self.native_commands = set()
        # External Git status is a deliberately synthetic observation for the
        # operational-shaped evidence fixture, never a claim about this checkout.
        self.git_status = b''
        self.git_status_reads = 0
        self.real_popen = subprocess.Popen
        self.real_run = subprocess.run
        self.real_waitpid = os.waitpid
        self.real_open = builtins.open
        self.real_read_text = Path.read_text

    def locked(self):
        path = self.target / '.v126-target-operations/lock'
        fd = os.open(path, os.O_RDONLY)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return True
            fcntl.flock(fd, fcntl.LOCK_UN)
            return False
        finally:
            os.close(fd)

    def open(self, path, *args, **kwargs):
        if isinstance(path, str) and path.startswith('/proc/self/fdinfo/'):
            fd = int(path.rsplit('/', 1)[1])
            info = os.fstat(fd)
            lock = self.target / '.v126-target-operations/lock'
            if (info.st_dev, info.st_ino) != (lock.stat().st_dev, lock.stat().st_ino):
                raise AssertionError('fixture was asked to attest an unrelated descriptor')
            content = ('lock:\t1: FLOCK ADVISORY WRITE ' + str(os.getpid()) +
                       ' ' + format(os.major(info.st_dev), '02x') + ':' + format(os.minor(info.st_dev), '02x') +
                       ':' + str(info.st_ino) + ' 0 EOF\n') if self.locked() else ''
            return io.StringIO(content)
        return self.real_open(path, *args, **kwargs)

    def read_text(self, path, *args, **kwargs):
        if str(path) == '/proc/sys/kernel/random/boot_id':
            return 'synthetic-policy-b-boot\n'
        return self.real_read_text(path, *args, **kwargs)

    def popen(self, argv, *args, **kwargs):
        argv = list(argv)
        expected_git = ['git', '--no-replace-objects', '-c', 'core.fsmonitor=false',
                        '-C', str(ROOT.parent)]
        if (argv[:6] == expected_git and argv[6:] in
                [['rev-parse', 'HEAD'], ['rev-parse', 'HEAD^{tree}']]):
            return self.real_popen(argv, *args, **kwargs)
        if tuple(argv) in self.native_commands:
            return self.real_popen(argv, *args, **kwargs)
        if argv[:1] != ['synthetic-policy-b-leaf']:
            raise AssertionError('non-fixture subprocess was technically refused: ' + repr(argv[:2]))
        if not self.locked():
            raise AssertionError('protected dispatch lost the real target flock')
        self.calls.append(dict(argv=argv, observations=self.observations(), lock=True))
        child = SimpleNamespace(pid=900000 + len(self.calls), stdin=tempfile.TemporaryFile(), returncode=None)
        self.children.append(child)
        os.write(kwargs['stdout'], b'SYNTHETIC_ACTION_COMPLETED\n')
        return child

    def run(self, argv, *args, **kwargs):
        expected = ['git', '--no-replace-objects', '-c', 'core.fsmonitor=false',
                    '-C', str(ROOT.parent), 'status', '--porcelain', '--untracked-files=all']
        if argv == expected:
            self.git_status_reads += 1
            return subprocess.CompletedProcess(argv, 0, self.git_status, b'')
        return self.real_run(argv, *args, **kwargs)

    def waitpid(self, pid, options):
        if pid != -1:
            return self.real_waitpid(pid, options)
        for child in self.children:
            if child.returncode is None:
                child.returncode = 0
                return child.pid, 0
        raise ChildProcessError

    @contextmanager
    def active(self):
        output = io.TextIOWrapper(io.BytesIO(), encoding='utf-8')
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(sys, 'platform', 'linux'))
            stack.enter_context(mock.patch.object(ctypes, 'CDLL', return_value=SimpleNamespace(prctl=lambda *args: 0)))
            stack.enter_context(mock.patch.object(builtins, 'open', self.open))
            stack.enter_context(mock.patch.object(Path, 'read_text', lambda path, *args, **kwargs: self.read_text(path, *args, **kwargs)))
            stack.enter_context(mock.patch.object(subprocess, 'Popen', self.popen))
            stack.enter_context(mock.patch.object(subprocess, 'run', self.run))
            stack.enter_context(mock.patch.object(os, 'waitpid', self.waitpid))
            stack.enter_context(mock.patch.object(socket, 'socket', side_effect=AssertionError('network forbidden')))
            stack.enter_context(mock.patch.object(socket, 'create_connection', side_effect=AssertionError('network forbidden')))
            stack.enter_context(mock.patch.object(sys, 'stdout', output))
            try:
                yield
            finally:
                for child in self.children:
                    child.stdin.close()


class DispatchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='v126-policy-b-supervisor-')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name).resolve()
        self.counter = 0

    def prepare(self, stage='BASELINE_VERIFIED', action=None, *, seed=True, fixture=None):
        self.counter += 1
        self.target = self.base / ('target-' + str(self.counter))
        self.target.mkdir(mode=0o700)
        if fixture is None:
            fixture = cases.operational_shape_fixture(
                purpose='preparation' if stage == 'BASELINE_VERIFIED' else 'cutover-Q')
        self.gate, self.observer, self.identity = cases.make_case(stage, self.target, fixture=fixture)
        if action is not None:
            self.identity['action'] = action
        self.root = self.target / '.v126-target-operations'
        self.runtime = InertRuntime(self.target, self.acquisitions)
        self.init_completion = None
        if seed:
            self.root.mkdir(mode=0o700)
            (self.root / 'lock').touch(mode=0o600)
            owner = {key: self.identity[key] for key in ('run_id', 'release_sha', 'script_sha256')}
            write(self.root / 'run.json', owner)
            self.init_completion = init_cases.seed_completion(self.target, owner)
            if stage != 'BASELINE_VERIFIED':
                previous = dict(owner, intent_sha256='1' * 64, kind='STAGE',
                                name='BASELINE_VERIFIED', action='baseline')
                self.seed_operation(previous)

    def acquisitions(self):
        return len([value for value in self.observer.calls if isinstance(value, tuple) and value[0] == 'acquire'])

    def seed_operation(self, identity, *, unknown=False, result=True):
        op = hashlib.sha256(canonical(identity)).hexdigest()
        write(self.root / (op + '.start.json'), dict(identity=identity, operation_id=op,
              started_at='2026-09-11T00:00:00+00:00', boot_id='synthetic-boot'))
        log = self.root / (op + '.log')
        log.write_bytes(b'SYNTHETIC_PRIOR_ACTION\n'); log.chmod(0o400)
        if result:
            write(self.root / (op + '.result.json'), dict(identity=identity, operation_id=op,
                  exit=42 if unknown else 0, outcome='UNKNOWN' if unknown else 'SUCCEEDED', children='REAPED',
                  log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(), completed_at='2026-09-11T00:00:01+00:00'))

    def execute(self, module=bindings, gate=True, timeout=None):
        worker = ['synthetic-policy-b-leaf', self.identity['name'], self.identity['action']]
        prepare = None
        if self.identity['action'] in ('preflight-upload', 'image-upload'):
            def prepare(identity, target, lockfd, history, current):
                current()
                spool = tempfile.TemporaryFile()
                self.addCleanup(spool.close)
                spool.write(b'SYNTHETIC_SUPERVISED_PAYLOAD')
                spool.seek(0)
                return worker, (spool.fileno(),)
        with self.runtime.active():
            return module.binding_supervise(self.target, self.identity, worker,
                input_data=b'', timeout=(adapter.ACTION_SECONDS.get(self.identity['action'], 300)
                                         if timeout is None else timeout),
                policy_b_gate=self.gate if gate else None, init_completion=self.init_completion,
                prepare_payload=prepare)

    def op(self):
        return hashlib.sha256(canonical(self.identity)).hexdigest()

    def snapshot(self):
        return {str(path.relative_to(self.target)): path.read_bytes()
                for path in self.target.rglob('*') if path.is_file()}

    def reject(self, module=bindings, *, gate=True, late=False):
        before = self.snapshot()
        with self.assertRaises(module.BindingError):
            self.execute(module, gate=gate)
        self.assertEqual(self.runtime.calls, [], 'protected action was dispatched')
        self.assertFalse((self.root / (self.op() + '.result.json')).exists(), 'refusal produced success authority')
        for path, data in before.items():
            self.assertEqual((self.target / path).read_bytes(), data, 'prior immutable history changed')
        if late:
            self.assertTrue((self.root / (self.op() + '.start.json')).exists())
        else:
            self.assertFalse((self.root / (self.op() + '.start.json')).exists(), 'early refusal wrote an operation intent')

    def corrupt_readiness(self):
        f = self.observer.fixture
        doc = f.evidence.get(f.readiness_ref, 'readiness')
        doc['ledger_sha256'] = 'f' * 64
        reference = f.put(doc)
        self.observer.observations = replace(self.observer.observations,
            evidence=dr.Evidence(f.documents), readiness_ref=reference)

    def late_failure(self):
        original = self.observer.acquire
        def acquire(context):
            if self.acquisitions() == 1:
                self.corrupt_readiness()
            return original(context)
        self.observer.acquire = acquire

    def test_mapping_matches_actual_dispatch_and_cannot_accept_arbitrary_action(self):
        stages = CUTOVER.read_text().split('readonly -a V126_STAGES=(\n', 1)[1].split('\n)', 1)[0].split()
        required = {(stage, action) for stage in stages for action in bindings.binding_action_sequence('STAGE', stage)
                    if bindings.binding_policy_b_required(dict(kind='STAGE', name=stage, action=action))}
        self.assertEqual(required, {(stage, action) for stage, actions in adapter.STAGE_ACTIONS.items() for action in actions})
        for stage, actions in adapter.STAGE_ACTIONS.items():
            for action in actions:
                self.assertTrue(bindings.binding_policy_b_required(dict(kind='STAGE', name=stage, action=action)))
            with self.assertRaises(bindings.BindingError):
                bindings.binding_policy_b_required(dict(kind='STAGE', name=stage, action='forged-action'))
        for name, action in [('PRE_DRAIN_BACKUP_REHEARSED', 'backup-rehearsal'),
                             ('FINAL_PUBLIC_GATES_PASSED', 'final-public-gates')]:
            self.assertFalse(bindings.binding_policy_b_required(dict(kind='STAGE', name=name, action=action)))

    def test_missing_gate_refuses_all_required_dispatches_before_target_writes(self):
        for stage, actions in adapter.STAGE_ACTIONS.items():
            for action in actions:
                with self.subTest(stage=stage, action=action):
                    self.prepare(stage, action, seed=False)
                    self.reject(gate=False)
                    self.assertEqual(list(self.target.iterdir()), [])

    def test_all_required_dispatches_consume_twice_under_same_lock_and_capture_exact_action(self):
        for stage, actions in adapter.STAGE_ACTIONS.items():
            for action in actions:
                with self.subTest(stage=stage, action=action):
                    self.prepare(stage, action)
                    self.assertEqual(self.execute(), 0)
                    self.assertEqual(self.runtime.calls, [dict(argv=['synthetic-policy-b-leaf', stage, action],
                                                            observations=2, lock=True)])
                    result = json.loads((self.root / (self.op() + '.result.json')).read_bytes())
                    self.assertEqual(result['identity'], self.identity)
                    self.assertEqual((result['exit'], result['outcome']), (0, 'SUCCEEDED'))
                    self.assertEqual(self.acquisitions(), 2)
                    self.assertGreaterEqual(self.runtime.git_status_reads, 2)
                    self.assertFalse(self.runtime.locked(), 'target lock leaked after completed dispatch')

    def test_rehashed_forged_readiness_cannot_reach_any_required_dispatch(self):
        for stage, actions in adapter.STAGE_ACTIONS.items():
            for action in actions:
                with self.subTest(stage=stage, action=action):
                    self.prepare(stage, action)
                    self.corrupt_readiness()
                    self.reject()

    def test_changed_inputs_between_checks_refuse_all_required_dispatches_and_replay(self):
        for stage, actions in adapter.STAGE_ACTIONS.items():
            for action in actions:
                with self.subTest(stage=stage, action=action):
                    self.prepare(stage, action)
                    self.late_failure()
                    self.reject(late=True)
                    count = self.acquisitions()
                    self.reject(late=True)
                    self.assertEqual(self.acquisitions(), count, 'UNKNOWN created a new admission/retry')

    def test_missing_r0_q_g_and_stale_observations_refuse_actual_dispatch(self):
        for stage in ('BASELINE_VERIFIED', 'V126_BACKEND_STARTED', 'FINAL_V126_BACKEND_STARTED', 'ORDINARY_CADDY_RESTORED'):
            for variant in ('readiness', 'ongoing', 'stale', 'future', 'rollback'):
                with self.subTest(stage=stage, variant=variant):
                    self.prepare(stage)
                    f = self.observer.fixture
                    if variant in ('readiness', 'ongoing'):
                        documents = dict(f.documents)
                        del documents[f.readiness_ref if variant == 'readiness' else f.ongoing_ref]
                        self.observer.observations = replace(self.observer.observations, evidence=dr.Evidence(documents))
                    else:
                        seconds = cases.fixtures.S.timestamp(f.now['utc'])
                        delta = 301 if variant == 'stale' else -1
                        new_clock = cases.fixtures.clock(seconds + delta, identity='verifier-clock')
                        if variant == 'rollback':
                            new_clock['monotonic_seconds'] -= 10
                        self.observer.times = [f.now, new_clock]
                    self.reject()

    def test_observation_source_failure_timeout_and_invalid_budget_block_dispatch(self):
        for failure in (OSError('synthetic unavailable'), TimeoutError('synthetic acquisition timeout')):
            with self.subTest(failure=type(failure).__name__):
                self.prepare()
                def fail(context):
                    raise failure
                self.observer.acquire = fail
                self.reject()
        self.prepare()
        with self.assertRaises(bindings.BindingError):
            self.execute(timeout=301)
        self.assertEqual(self.runtime.calls, [])

    def test_independent_run_source_and_target_pins_refuse_actual_dispatch(self):
        for field, value in [('run_id', 'unrelated-synthetic-run'), ('source_sha', 'f' * 40),
                             ('deployment_target', str(self.base / 'unrelated-target'))]:
            with self.subTest(field=field):
                self.prepare('V126_BACKEND_STARTED')
                observations = self.observer.observations
                self.observer.observations = replace(observations,
                    pins=replace(observations.pins, **{field: value}))
                self.reject()

    def test_synthetic_consumer_result_and_dirty_source_cannot_dispatch(self):
        self.prepare(fixture=cases.fixtures.Fixture())
        self.reject()
        self.assertEqual(self.acquisitions(), 1, 'synthetic result should be rejected at real supervisor boundary')
        self.prepare()
        self.runtime.git_status = b' M synthetic-dirty-source\n'
        self.reject()
        self.assertGreater(self.runtime.git_status_reads, 0, 'real clean-source consumer check did not run')

    def test_final_stages_recheck_current_ongoing_and_reviewed_recipe_after_early_pass(self):
        for stage in ('FINAL_V126_BACKEND_STARTED', 'ORDINARY_CADDY_RESTORED'):
            for variant in ('ongoing-unavailable', 'recipe-changed'):
                with self.subTest(stage=stage, variant=variant):
                    self.prepare(stage)
                    original = self.observer.acquire
                    def acquire(context):
                        if self.acquisitions() == 1:
                            observations = self.observer.observations
                            if variant == 'ongoing-unavailable':
                                self.observer.observations = replace(observations,
                                    trust=replace(observations.trust, ongoing_sha256=None))
                            else:
                                self.observer.observations = replace(observations,
                                    pins=replace(observations.pins, post_v126_recipe_sha256='f' * 64))
                        return original(context)
                    self.observer.acquire = acquire
                    self.reject(late=True)

    def test_unknown_history_blocks_before_observation_and_preserves_records(self):
        for failed in (False, True):
            with self.subTest(failed=failed):
                self.prepare('V126_BACKEND_STARTED')
                prior = dict(self.identity, intent_sha256='2' * 64, name='PUBLIC_DRAIN_ACTIVE', action='public-drain-on')
                self.seed_operation(prior, unknown=failed, result=failed)
                self.reject()
                self.assertEqual(self.acquisitions(), 0)

    def test_read_only_inspection_and_recovery_keep_existing_authority(self):
        self.prepare('V126_BACKEND_STARTED')
        before = self.snapshot()
        with mock.patch.object(sys, 'argv', ['fixture', str(self.target)]), mock.patch('sys.stdout', io.StringIO()) as output:
            bindings.binding_entry('inspect')
        self.assertIn('COMMAND_RESULTS_VERIFIED', output.getvalue())
        self.assertEqual(self.snapshot(), before)
        self.identity.update(kind='RECOVERY', name='post-v126-stop', action='recover-post-v126-stop')
        self.assertEqual(self.execute(gate=False), 0)
        self.assertEqual(self.acquisitions(), 0)
        self.assertEqual(len(self.runtime.calls), 1)

    def test_forged_recovery_deploy_or_unknown_kind_cannot_bypass_policy_dispatch(self):
        for kind, name, action in [('RECOVERY', 'post-v126-stop', 'start-v126'),
                                   ('DEPLOY', 'ORDINARY_DEPLOY', 'start-v126'),
                                   ('FORGED_KIND', 'V126_BACKEND_STARTED', 'start-v126')]:
            with self.subTest(kind=kind):
                self.prepare('V126_BACKEND_STARTED')
                self.identity.update(kind=kind, name=name, action=action)
                self.reject(gate=False)
                self.assertEqual(self.acquisitions(), 0)

    def test_missing_real_lock_is_refused_by_adapter_before_consumer(self):
        self.prepare(seed=False)
        self.root.mkdir(mode=0o700)
        path = self.root / 'lock'
        path.touch(mode=0o600)
        fd = os.open(path, os.O_RDONLY)
        try:
            with self.runtime.active(), self.assertRaises(adapter.GateError):
                self.gate.check(self.identity, self.target, fd, 300)
        finally:
            os.close(fd)
        self.assertEqual(self.acquisitions(), 0)
        self.assertEqual(self.runtime.calls, [])

    def test_cancellation_during_late_gate_cannot_dispatch_after_consumer_pass(self):
        self.prepare()
        original = self.observer.acquire
        def acquire(context):
            if self.acquisitions() == 1:
                signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
            return original(context)
        self.observer.acquire = acquire
        self.reject(late=True)
        self.assertEqual(self.acquisitions(), 2)

    def native_chain(self, stage='V126_BACKEND_STARTED'):
        """Reuse the real harness writer and full production receipt verifier.

        Every retained byte is synthetic. The only Bash commands reachable are
        explicit fixture creation and verify_receipt; operational executables
        are denied in their child-only PATH and no user environment is passed.
        """
        self.prepare(stage)
        f = self.observer.fixture
        state = self.base / ('native-state-' + str(self.counter))
        state.mkdir(mode=0o700)
        for name in ('artifacts', 'authorizations', 'intents', 'receipts', 'recovery', 'tmp'):
            (state / name).mkdir(mode=0o700)
        manifest = dict(created_at='2026-09-01T00:00:00Z', format_version=1,
            database_url_file=str(self.base / 'unused-synthetic-database'),
            maintenance_identities_file=str(self.base / 'unused-synthetic-identities'),
            main_actions_run_id=123456, release_parents=['a' * 40], release_sha=f.binding['source_sha'],
            release_tree=f.binding['source_tree'], release_worktree=str(ROOT.parent), remote='fixture-only',
            run_id=self.identity['run_id'], script_sha256=self.identity['script_sha256'], staging_path=str(self.target),
            v125_image_tag='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',
            v126_image_id='sha256:' + 'a' * 64, v126_image_tag='synthetic:' + f.binding['source_sha'])
        write(state / 'run.json', manifest)
        checksum = state / 'run.json.sha256'
        checksum.write_text(hashlib.sha256((state / 'run.json').read_bytes()).hexdigest() + '\n')
        checksum.chmod(0o400)
        guard = self.base / ('deny-operational-' + str(self.counter))
        guard.mkdir(mode=0o700)
        for name in ('ssh', 'scp', 'rsync', 'curl', 'gh', 'docker', 'psql', 'pg_dump', 'pg_restore',
                     'sudo', 'systemctl', 'aws', 'yc'):
            path = guard / name
            path.write_text('#!/bin/sh\nprintf "operational command refused by fixture\\n" >&2\nexit 97\n')
            path.chmod(0o500)
        env = dict(PATH=str(guard) + os.pathsep + os.environ['PATH'], HOME=str(self.base),
                   LC_ALL='C', PYTHONDONTWRITEBYTECODE='1')
        seed = ['bash', '-c', 'set -Eeuo pipefail; source "$1"; seed_chain "$2" 7',
                'synthetic-native-fixture', str(ROOT / 'test-v126-cutover.sh'), str(state)]
        result = subprocess.run(seed, capture_output=True, env=env, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        path = state / 'receipts/07-QUIESCED_BACKUP_REHEARSED.receipt.json'
        seeded = json.loads(path.read_bytes())
        native = json.loads(f.native)
        for key in ('authorization_receipt_sha256', 'intent_sha256', 'predecessor_receipt_sha256'):
            native[key] = seeded[key]
        log = state / 'artifacts/7-QUIESCED_BACKUP_REHEARSED.operation.log'
        pairs = {item['name']: item['sha256'] for item in native['artifacts'] if item['name'] != 'operation-log'}
        log.chmod(0o600)
        log.write_bytes(b''.join(('ARTIFACT\t' + key + '\t' + value + '\n').encode()
                                for key, value in sorted(pairs.items())))
        log.chmod(0o400)
        log_sha = hashlib.sha256(log.read_bytes()).hexdigest()
        for item in native['artifacts']:
            if item['name'] == 'operation-log':
                item['sha256'] = log_sha
        path.chmod(0o600); write(path, native)
        receipt_checksum = Path(str(path) + '.sha256')
        receipt_checksum.chmod(0o600)
        receipt_checksum.write_text(hashlib.sha256(path.read_bytes()).hexdigest() + '\n')
        receipt_checksum.chmod(0o400)
        mapping = {'native-auth': native['authorization_receipt_sha256'], 'native-intent': native['intent_sha256'],
                   'stage6': native['predecessor_receipt_sha256'], 'log': log_sha,
                   'native-manifest': hashlib.sha256((state / 'run.json').read_bytes()).hexdigest()}
        original_hash = cases.fixtures.H
        with mock.patch.object(cases.fixtures, 'H', lambda text: mapping.get(text, original_hash(text))):
            bound = cases.operational_shape_fixture(purpose='cutover-Q')
        self.assertEqual(bound.native, path.read_bytes())
        self.gate, self.observer, self.identity = cases.make_case(stage, self.target, fixture=bound)
        command = ['bash', '-c', 'set -Eeuo pipefail; source "$1"; load_state "$2"; verify_receipt QUIESCED_BACKUP_REHEARSED',
                   'synthetic-native-verifier', str(CUTOVER), str(state)]
        self.runtime.native_commands.add(tuple(command))
        def verify(context):
            self.observer.calls.append(('native', context))
            result = subprocess.run(command, capture_output=True, env=env, timeout=30)
            if result.returncode != 0:
                raise ValueError('synthetic native chain refused')
            receipt = path.read_bytes()
            if result.stdout.strip().decode() != hashlib.sha256(receipt).hexdigest():
                raise ValueError('native receipt changed after verifier')
            return adapter.NativeStage7(receipt, hashlib.sha256((state / 'run.json').read_bytes()).hexdigest())
        self.observer.verify_native_stage7 = verify
        return state

    def test_actual_native_chain_replayed_twice_before_positive_dispatch(self):
        self.native_chain()
        self.assertEqual(self.execute(), 0)
        self.assertEqual(self.acquisitions(), 2)
        self.assertEqual(len([call for call in self.observer.calls if isinstance(call, tuple) and call[0] == 'native']), 2)
        self.assertEqual(len(self.runtime.calls), 1)

    def test_incomplete_native_chain_and_wrong_run_block_real_dispatch(self):
        for variant in ('missing-predecessor', 'wrong-run', 'reconciled'):
            with self.subTest(variant=variant):
                state = self.native_chain()
                if variant == 'missing-predecessor':
                    (state / 'receipts/06-ZERO_WRITER_GATE_PASSED.receipt.json').unlink()
                else:
                    path = state / 'receipts/07-QUIESCED_BACKUP_REHEARSED.receipt.json'
                    doc = json.loads(path.read_bytes())
                    if variant == 'wrong-run':
                        doc['run_id'] = 'different-synthetic-run'
                    else:
                        doc.update(format_version=2, result_category='RECONCILED_EFFECT')
                    path.chmod(0o600); write(path, doc)
                    checksum = Path(str(path) + '.sha256')
                    checksum.chmod(0o600)
                    checksum.write_text(hashlib.sha256(path.read_bytes()).hexdigest() + '\n'); checksum.chmod(0o400)
                self.reject()

    def test_native_chain_change_after_early_pass_prevents_late_dispatch(self):
        state = self.native_chain('ORDINARY_CADDY_RESTORED')
        original = self.observer.acquire
        def acquire(context):
            if self.acquisitions() == 1:
                (state / 'receipts/06-ZERO_WRITER_GATE_PASSED.receipt.json').unlink()
            return original(context)
        self.observer.acquire = acquire
        self.reject(late=True)

    def test_gate_removal_negative_controls_fail_the_corresponding_regression(self):
        tree = ast.parse(SOURCE.read_text())
        supervisor = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'binding_supervise')
        calls = [node for node in ast.walk(supervisor) if isinstance(node, ast.Expr) and
                 isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name) and
                 node.value.func.id == 'binding_policy_b_check']
        calls.sort(key=lambda node: node.lineno)
        self.assertEqual(len(calls), 2, 'both production dispatch barriers must be uniquely identifiable')
        for index, call in enumerate(calls):
            with self.subTest(removed_gate=index + 1):
                lines = SOURCE.read_text().splitlines(keepends=True)
                indent = lines[call.lineno - 1][:call.col_offset]
                lines[call.lineno - 1:call.end_lineno] = [indent + 'pass  # temporary negative control only\n']
                path = self.base / ('mutant-' + str(index) + '.py')
                path.write_text(''.join(lines))
                mutant = load('policy_b_negative_control_' + str(index), path)
                self.prepare()
                if index == 0:
                    self.corrupt_readiness()
                else:
                    self.late_failure()
                with self.assertRaises(AssertionError):
                    self.reject(mutant)

    def test_each_protected_stage_mapping_removal_is_caught_by_dispatch_regression(self):
        source = SOURCE.read_text()
        begin = source.index('def binding_policy_b_required(')
        end = source.index('\n\ndef binding_policy_b_check(', begin)
        for stage, actions in adapter.STAGE_ACTIONS.items():
            with self.subTest(removed_stage=stage):
                section = source[begin:end]
                self.assertEqual(section.count(repr(stage)), 1)
                section = section.replace(repr(stage), repr('MUTATED_SKIP_' + stage), 1)
                path = self.base / ('mapping-mutant-' + stage + '.py')
                path.write_text(source[:begin] + section + source[end:])
                mutant = load('policy_b_mapping_negative_' + stage, path)
                for action in actions:
                    self.prepare(stage, action)
                    self.corrupt_readiness()
                    with self.assertRaises(AssertionError):
                        self.reject(mutant)


    def late_history_change(self):
        original = self.observer.acquire
        def acquire(context):
            if self.acquisitions() == 1:
                write(self.root / 'unexpected-history.json', dict(synthetic='changed during LATE observation'))
            return original(context)
        self.observer.acquire = acquire

    def test_missing_or_changed_init_completion_cannot_reach_actual_dispatch(self):
        self.prepare()
        self.init_completion = None
        self.reject()
        self.assertEqual(self.acquisitions(), 0)
        self.prepare()
        self.init_completion['result_sha256'] = 'f' * 64
        self.reject()
        self.assertEqual(self.acquisitions(), 0)

    def test_history_change_during_late_check_cannot_reach_actual_dispatch(self):
        self.prepare()
        self.late_history_change()
        self.reject(late=True)
        self.assertEqual(self.acquisitions(), 2)

    def test_completion_history_and_lock_mutants_fail_actual_dispatch_regressions(self):
        source = SOURCE.read_text()
        supervisor = next(node for node in ast.parse(source).body
                          if isinstance(node, ast.FunctionDef) and node.name == 'binding_supervise')
        def calls(name):
            return sorted([node for node in ast.walk(supervisor) if isinstance(node, ast.Expr) and
                isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name) and
                node.value.func.id == name], key=lambda node: node.lineno)
        completion = calls('binding_require_init_completion')
        history = calls('binding_history_unchanged')
        self.assertEqual(len(completion), 1)
        self.assertEqual(len(history), 3)
        popen = [node for node in ast.walk(supervisor) if isinstance(node, ast.Assign) and
                 isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute) and
                 isinstance(node.value.func.value, ast.Name) and node.value.func.value.id == 'subprocess' and
                 node.value.func.attr == 'Popen']
        self.assertEqual(len(popen), 1)
        for label, node in [('completion', completion[0]), ('late-history', history[-1]), ('unlock', popen[0])]:
            with self.subTest(mutant=label):
                lines = source.splitlines(keepends=True)
                indent = ' ' * node.col_offset
                if label == 'unlock':
                    lines.insert(node.lineno - 1, indent + 'fcntl.flock(lockfd, fcntl.LOCK_UN)  # disposable negative control\n')
                else:
                    lines[node.lineno - 1:node.end_lineno] = [indent + 'pass  # disposable negative control\n']
                path = self.base / ('boundary-mutant-' + label + '.py')
                path.write_text(''.join(lines))
                mutant = load('policy_b_boundary_negative_' + label.replace('-', '_'), path)
                self.prepare()
                if label == 'completion':
                    self.init_completion = None
                    with self.assertRaises(AssertionError): self.reject(mutant)
                elif label == 'late-history':
                    self.late_history_change()
                    with self.assertRaises(AssertionError): self.reject(mutant, late=True)
                else:
                    with self.assertRaisesRegex(AssertionError, 'protected dispatch lost the real target flock'):
                        self.execute(mutant)
                    self.assertEqual(self.runtime.calls, [])
                    self.assertFalse((self.root / (self.op() + '.result.json')).exists())


if __name__ == '__main__':
    unittest.main()
