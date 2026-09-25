#!/usr/bin/env python3
"""Linux create-once genesis history; no SSH/daemon/provider or external network.

The transcript is an explicitly synthetic V outcome, not operational authority.
Owned-SSH tests separately exercise caller/launcher/independent evidence. These
checks use real Linux flock/inodes, subprocess contention and durable files.
"""
import copy
import errno
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

b = load('genesis_bindings', ROOT / 'v126-operation-bindings.py')
i = load('genesis_init_fixture', ROOT / 'test-v126-policy-b-init.py')

def digest(doc):
    return hashlib.sha256(b.binding_canonical(doc)).hexdigest()


def fixture(target):
    info = target.stat()
    selected = dict(path=str(target), device=info.st_dev, inode=info.st_ino, uid=info.st_uid,
                    host_fingerprint='SHA256:' + 'A' * 43)
    index = dict(schema_version=1, kind='legacy-target-inventory', target=selected, runs=[
        dict(run_id='synthetic-old-run724', historical_outcome='UNKNOWN',
             disposition=dict(kind='LEGACY_UNKNOWN_FENCED', proof_sha256='7' * 64)),
        dict(run_id='synthetic-old-runf782', historical_outcome='FAILED_PRE_ACTIVE_CONFIG_MUTATION_EXTERNALLY_FENCED',
             disposition=dict(kind='LEGACY_UNKNOWN_FENCED', proof_sha256='8' * 64))])
    owner = dict(run_id='synthetic-new-cutover', release_sha='a' * 40, script_sha256='b' * 64)
    init_identity, init_request = i.seed_request(target, owner)
    request = dict(schema_version=1, kind='legacy-target-genesis-request', run_id=owner['run_id'],
                   source_sha=owner['release_sha'], source_tree='c' * 40, script_sha256=owner['script_sha256'],
                   tooling_sha256='d' * 64, python_version='3.12.0', target=selected,
                   next_init_manifest_sha256=init_identity['intent_sha256'], inventory_sha256=digest(index),
                   created_at='2026-09-23T00:00:00Z', expires_at='2026-09-24T00:00:00Z', nonce='f' * 64)
    return request, index, owner, init_identity, init_request


class Transcript:
    def __init__(self, request, index, fail=None, on_check=None, readback=False):
        self.request, self.index, self.fail, self.on_check = request, index, fail, on_check
        self.calls, self.dispatches, self.writes = 0, 0, 0
        self.readback = readback
    def check(self, identity, target, fd, timeout):
        self.calls += 1
        assert identity == b.binding_genesis_identity(self.request)
        assert timeout == 300
        if fd is None:
            assert not (target / '.v126-target-operations').exists()
        else:
            b.binding_lock_held(target / '.v126-target-operations', fd)
        if self.on_check:
            self.on_check(self.calls, fd)
        if self.calls == self.fail:
            raise b.BindingError('synthetic_observation_refused')
        return dict(barrier='LEGACY_GENESIS_COPY' if self.readback else 'LEGACY_GENESIS', operational=True, qualification_sha256=digest(self.index),
                    pins_sha256='1' * 64, catalogue_head_sha256='2' * 64, revocation_generation=1,
                    legacy_inventory=copy.deepcopy(self.index))
    def before_create(self, identity, target, fd, timeout):
        assert fd is None and self.calls == 1
        if self.fail == 'create':
            raise b.BindingError('synthetic_expired_before_create')
    def before_dispatch(self, identity, target, fd, timeout):
        assert self.calls == 2
        b.binding_lock_held(target / '.v126-target-operations', fd)
        self.dispatches += 1
        if self.fail == 'dispatch':
            raise b.BindingError('synthetic_expired_before_dispatch')
    def before_write(self, identity, target, fd, timeout):
        assert self.dispatches == 1
        b.binding_lock_held(target / '.v126-target-operations', fd)
        self.writes += 1
        if self.fail == 'write-' + str(self.writes):
            raise b.BindingError('synthetic_expired_before_write')


class GenesisDurability(unittest.TestCase):
    """Portable exact production writer/readers; synthetic preceding records only."""
    def seed(self, target):
        request, index, owner, init_identity, _ = fixture(target)
        identity = b.binding_genesis_identity(request)
        root = target / '.v126-target-operations'; root.mkdir(mode=0o700)
        lock = root / 'lock'
        lock.write_bytes(b.GENESIS_LOCK_PREFIX + digest(request).encode() + b'\n'); lock.chmod(0o600)
        stamp = '2026-09-23T01:00:00+00:00'
        intent = dict(format_version=1, identity=identity, operation_id=digest(identity),
                      request_sha256=digest(request), started_at=stamp)
        record = dict(format_version=2, kind='GENESIS_CUTOVER_OWNER', owner=owner,
                      request_sha256=digest(request), inventory_sha256=digest(index))
        for name, value in [('genesis.intent.json', intent), ('genesis.request.json', request),
                            ('legacy-inventory.json', index), ('run.json', record)]:
            b.binding_create(root / name, value)
        admission = dict(qualification_sha256=digest(index), pins_sha256='1'*64,
                         catalogue_head_sha256='2'*64, revocation_generation=1)
        result = dict(format_version=1, identity=identity, operation_id=digest(identity), exit=0,
                      outcome='SUCCEEDED', completion='LEGACY_DISPOSITION_BOUND', request_sha256=digest(request),
                      intent_sha256=digest(intent), inventory_sha256=digest(index), owner_sha256=digest(record),
                      admissions=[admission, admission], completed_at=stamp)
        return root, request, index, owner, init_identity, result

    def readers(self, root, target, request, index, owner, init_identity):
        return {
            'result': lambda: b.binding_read(root / 'genesis.result.json'),
            'completion': lambda: b.binding_genesis_completion(root, target),
            'chain': lambda: b.binding_chain(root, target),
            'inventory': lambda: b.binding_inventory(root, owner, target),
            'history': lambda: b.binding_history(root, target),
            'policy': lambda: b.binding_active_policy(root, target),
            'snapshot': lambda: b.binding_history_snapshot(root),
            'copy': lambda: b.binding_genesis_readback(target, b.binding_genesis_identity(request), digest(request),
                                  policy_b_gate=Transcript(request, index, readback=True)),
            'init-copy': lambda: b.binding_init_readback(target, init_identity, init_identity['intent_sha256']),
            'reconcile': lambda: b.binding_reconcile(target, owner, 'RECOVERY', 'pre-v126', '3'*64,
                                  owner['script_sha256'], '4'*64, ['recover-pre-v126'], lambda *_: self.fail('observer called')),
            'deploy-retirement': lambda: b.binding_retire_deploy(target, owner, '3'*64,
                                  target / 'absent-handoff', target / 'absent-next-request'),
        }

    def test_file_and_root_fsync_eio_full_result_rejected_by_all_readers(self):
        for barrier in ('file', 'root'):
            with self.subTest(barrier=barrier), tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp).resolve(); target.chmod(0o700)
                root, request, index, owner, init_identity, result = self.seed(target)
                rootfd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                real = os.fsync; calls = []
                def fail(fd):
                    selected = 'root' if fd == rootfd else 'file'
                    calls.append(selected)
                    self.assertEqual((root / 'genesis.result.json').read_bytes(), b.binding_canonical(result))
                    if selected == barrier:
                        raise OSError(errno.EIO, 'synthetic full-result barrier EIO')
                    real(fd)
                try:
                    with patch.object(os, 'fsync', fail), self.assertRaises(OSError) as error:
                        b.binding_genesis_create_at(rootfd, 'genesis.result.json', result)
                    self.assertEqual(error.exception.errno, errno.EIO)
                    self.assertEqual(calls, ['file'] if barrier == 'file' else ['file', 'root'])
                    self.assertEqual(stat.S_IMODE((root / 'genesis.result.json').stat().st_mode), 0o600)
                    before = {p.name: (p.stat().st_ino, p.stat().st_mode, p.read_bytes()) for p in root.iterdir()}
                    for name, read in self.readers(root, target, request, index, owner, init_identity).items():
                        with self.subTest(reader=name), self.assertRaisesRegex(b.BindingError, 'record_metadata'):
                            read()
                    with self.assertRaises(FileExistsError):
                        b.binding_genesis_create_at(rootfd, 'genesis.result.json', result)
                    self.assertEqual({p.name: (p.stat().st_ino, p.stat().st_mode, p.read_bytes()) for p in root.iterdir()}, before)
                finally:
                    os.close(rootfd)

    def test_publication_follows_both_barriers_and_positive_consumers(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp).resolve(); target.chmod(0o700)
            root, request, index, owner, init_identity, result = self.seed(target)
            rootfd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            sync, chmod = os.fsync, os.fchmod; events = []
            def barrier(fd):
                events.append('root' if fd == rootfd else 'file')
                self.assertEqual(stat.S_IMODE((root / 'genesis.result.json').stat().st_mode), 0o600)
                sync(fd)
            def publish(fd, mode):
                self.assertEqual(events, ['file', 'root']); events.append('publish')
                chmod(fd, mode)
            try:
                with patch.object(os, 'fsync', barrier), patch.object(os, 'fchmod', publish):
                    b.binding_genesis_create_at(rootfd, 'genesis.result.json', result)
            finally:
                os.close(rootfd)
            self.assertEqual(events, ['file', 'root', 'publish'])
            self.assertEqual(b.binding_history(root, target)[0], owner)
            copy = b.binding_genesis_readback(target, b.binding_genesis_identity(request), digest(request),
                                            policy_b_gate=Transcript(request, index, readback=True))
            self.assertEqual(copy['result'], result)

    def test_umask_cannot_publish_pending_result_before_barriers(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp).resolve(); target.chmod(0o700)
            root, request, index, owner, init_identity, result = self.seed(target)
            rootfd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            previous = os.umask(0o200)
            try:
                with patch.object(os, 'fsync', side_effect=OSError(errno.EIO, 'must not reach barrier')) as sync:
                    with self.assertRaisesRegex(b.BindingError, 'genesis_write_metadata'):
                        b.binding_genesis_create_at(rootfd, 'genesis.result.json', result)
                    sync.assert_not_called()
            finally:
                os.umask(previous); os.close(rootfd)
            self.assertEqual((root / 'genesis.result.json').read_bytes(), b'')
            for name, read in self.readers(root, target, request, index, owner, init_identity).items():
                if name == 'snapshot': continue  # Structural snapshot is not completion admission.
                with self.subTest(reader=name), self.assertRaises((b.BindingError, ValueError)): read()


class NamespaceObservation(unittest.TestCase):
    """Portable event-parser controls only; full syscall races are Linux Genesis tests."""
    def event(self, mask, name=b'.v126-target-operations', wd=7):
        raw = name + b'\0' if name else b''
        return struct.pack('iIII', wd, mask, 0, len(raw)) + raw
    def check(self, events):
        with patch.object(os, 'read', side_effect=[events, BlockingIOError()]):
            b.binding_genesis_created(99, 7)
    def test_exclusive_create_and_unrelated_names(self):
        self.check(self.event(0x40000100) + self.event(0x40000080, b'unrelated'))
    def test_replacements_queue_loss_and_missing_create_refused(self):
        for events in (self.event(0x40000100) + self.event(0x40000040),
                       self.event(0x40000100) + self.event(0x40000080),
                       self.event(0x40000100) + self.event(0x40000200),
                       self.event(0x40000100) * 2, self.event(0x4000, b'', -1),
                       self.event(0x8000, b''), self.event(0x2000, b''),
                       self.event(0x400), self.event(0x800), b'bad'):
            with self.subTest(events=events), self.assertRaises(b.BindingError): self.check(events)
        with patch.object(os, 'read', side_effect=BlockingIOError()), self.assertRaises(b.BindingError):
            b.binding_genesis_created(99, 7)


class Genesis(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='genesis-history-synthetic-')
        self.base = Path(self.tmp.name).resolve()
        self.target = self.base / 'target'
        self.target.mkdir(mode=0o700)
        self.root = self.target / '.v126-target-operations'
        self.request, self.index, self.owner, self.init_identity, self.init_request = fixture(self.target)
        self.identity = b.binding_genesis_identity(self.request)
        self.gate = Transcript(self.request, self.index)
    def tearDown(self):
        self.tmp.cleanup()
    def run_genesis(self, **kwargs):
        return b.binding_genesis(self.target, self.identity, self.request, policy_b_gate=self.gate, **kwargs)
    def snapshot(self):
        return {str(p.relative_to(self.root)): (p.stat().st_ino, p.stat().st_mode, p.read_bytes())
                for p in self.root.rglob('*') if p.is_file()}
    def readback(self):
        return b.binding_genesis_readback(self.target, self.identity, digest(self.request),
                                         policy_b_gate=Transcript(self.request, self.index, readback=True))
    def test_success_exact_immutable_records_and_no_stage_authority(self):
        result = self.run_genesis()['result']
        self.assertEqual(result['completion'], 'LEGACY_DISPOSITION_BOUND')
        self.assertEqual(self.gate.calls, 2)
        self.assertEqual(self.gate.dispatches, 1)
        self.assertEqual(self.gate.writes, 5)
        owner, owners, current, recovery = b.binding_history(self.root, self.target)
        self.assertEqual((owner, owners, current, recovery), (self.owner, [self.owner], [], False))
        self.assertEqual(set(self.snapshot()), b.GENESIS_FILES | {'run.json', 'lock'})
        for name, (_, mode, _) in self.snapshot().items():
            self.assertEqual(mode & 0o777, 0o600 if name == 'lock' else 0o400)
        stage = dict(self.owner, intent_sha256='1' * 64, kind='STAGE', name='BASELINE_VERIFIED', action='baseline')
        with self.assertRaisesRegex(b.BindingError, 'completed_init'):
            b.binding_admit_operation(self.root, self.target, stage, [])
    def test_early_refusal_and_stale_create_do_not_create_registry(self):
        for fail in (1, 'create'):
            self.gate = Transcript(self.request, self.index, fail=fail)
            with self.assertRaises(b.BindingError): self.run_genesis()
            self.assertFalse(self.root.exists())
    def test_missing_observer_or_wrong_target_is_prewrite(self):
        with self.assertRaisesRegex(b.BindingError, 'observer_required'):
            b.binding_genesis(self.target, self.identity, self.request, policy_b_gate=None)
        changed = copy.deepcopy(self.request); changed['target']['inode'] += 1
        with self.assertRaisesRegex(b.BindingError, 'target_changed'):
            b.binding_genesis(self.target, b.binding_genesis_identity(changed), changed, policy_b_gate=self.gate)
        self.assertFalse(self.root.exists())
    def test_existing_or_partial_root_never_adopted(self):
        self.root.mkdir(mode=0o700)
        with self.assertRaisesRegex(b.BindingError, 'already_exists'): self.run_genesis()
        self.assertEqual(list(self.root.iterdir()), [])
        self.assertEqual(self.gate.calls, 0)
    def test_failure_after_mkdir_retains_partial_and_refuses_second_genesis(self):
        real = os.open
        def broken(path, flags, *args, **kwargs):
            if str(path) == 'lock' and kwargs.get('dir_fd') is not None: raise OSError('synthetic crash before lock')
            return real(path, flags, *args, **kwargs)
        with patch.object(os, 'open', broken):
            with self.assertRaises(OSError): self.run_genesis()
        self.assertTrue(self.root.is_dir())
        self.assertEqual(list(self.root.iterdir()), [])
        with self.assertRaisesRegex(b.BindingError, 'already_exists'): self.run_genesis()
    def test_failure_after_lock_create_retains_permanent_inode(self):
        with patch.object(fcntl, 'flock', side_effect=OSError('synthetic lock crash')):
            with self.assertRaises(OSError): self.run_genesis()
        info = (self.root / 'lock').stat()
        self.assertFalse((self.root / 'run.json').exists())
        with self.assertRaisesRegex(b.BindingError, 'already_exists'): self.run_genesis()
        self.assertEqual((self.root / 'lock').stat().st_ino, info.st_ino)
    def test_late_and_each_write_failure_never_accept_owner(self):
        for fail in (2, 'dispatch', 'write-1', 'write-2', 'write-3', 'write-4', 'write-5'):
            with self.subTest(fail=fail), tempfile.TemporaryDirectory(dir=self.base) as tmp:
                target = Path(tmp); target.chmod(0o700)
                request, index, _, _, _ = fixture(target)
                gate = Transcript(request, index, fail=fail)
                with self.assertRaises(b.BindingError):
                    b.binding_genesis(target, b.binding_genesis_identity(request), request, policy_b_gate=gate)
                root = target / '.v126-target-operations'
                self.assertTrue(root.is_dir())
                self.assertFalse((root / 'genesis.result.json').exists())
                with self.assertRaises((b.BindingError, OSError)):
                    b.binding_history(root, target)
                old = {p.name: p.read_bytes() for p in root.iterdir() if p.is_file()}
                with self.assertRaisesRegex(b.BindingError, 'already_exists'):
                    b.binding_genesis(target, b.binding_genesis_identity(request), request, policy_b_gate=gate)
                self.assertEqual({p.name: p.read_bytes() for p in root.iterdir() if p.is_file()}, old)
    def test_truncated_result_stays_unknown(self):
        create = b.binding_genesis_create_at
        def fault(rootfd, name, value):
            if name == 'genesis.result.json':
                fd=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o400,dir_fd=rootfd)
                os.write(fd,b'{"partial":');os.fsync(fd);os.close(fd)
                raise OSError('synthetic crash in result')
            create(rootfd,name,value)
        with patch.object(b, 'binding_genesis_create_at', fault):
            with self.assertRaises(OSError): self.run_genesis()
        with self.assertRaises((b.BindingError, ValueError)): b.binding_history(self.root, self.target)
        with self.assertRaisesRegex(b.BindingError, 'already_exists'): self.run_genesis()
    def test_cancel_before_first_write_and_unlock_before_dispatch(self):
        def cancel(count, fd):
            if count == 1: signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
        self.gate.on_check = cancel
        with self.assertRaisesRegex(b.BindingError, 'cancelled'): self.run_genesis()
        self.assertFalse(self.root.exists())
        self.gate = Transcript(self.request, self.index,
            on_check=lambda count, fd: fcntl.flock(fd, fcntl.LOCK_UN) if count == 2 else None)
        with self.assertRaisesRegex(b.BindingError, 'not_held'): self.run_genesis()
        self.assertFalse((self.root / 'genesis.intent.json').exists())
    def test_changed_history_or_replaced_inode_fails_without_success(self):
        def inject(count, fd):
            if count == 2: b.binding_create(self.root / 'foreign.json', {})
        self.gate.on_check = inject
        with self.assertRaisesRegex(b.BindingError, 'history_changed'): self.run_genesis()
        self.assertFalse((self.root / 'genesis.intent.json').exists())
    def test_lost_ack_copy_only_preserves_exact_state(self):
        def lost(_): raise EOFError('synthetic lost ACK')
        with self.assertRaises(EOFError): self.run_genesis(ack_genesis=lost)
        before = self.snapshot()
        value = self.readback()
        self.assertEqual(value['result']['outcome'], 'SUCCEEDED')
        self.assertEqual(value['legacy_inventory'], self.index)
        b.binding_validate_genesis_completion(value['request'], value['result'], value['legacy_inventory'], self.target)
        self.assertEqual(self.snapshot(), before)
        with self.assertRaisesRegex(b.BindingError, 'already_exists'): self.run_genesis()
        with self.assertRaisesRegex(b.BindingError, 'readback_authority'):
            b.binding_genesis_readback(self.target, self.identity, digest(self.request), policy_b_gate=None)
        self.assertEqual(self.snapshot(), before)
    def test_marker_deletion_or_owner_downgrade_does_not_admit_legacy(self):
        self.run_genesis()
        result = self.root / 'genesis.result.json'; raw = result.read_bytes(); result.unlink()
        with self.assertRaisesRegex(b.BindingError, 'completion_required'): b.binding_chain(self.root, self.target)
        b.binding_create_raw(result, raw)
        owner = self.root / 'run.json'; owner.unlink(); b.binding_create(owner, self.owner)
        with self.assertRaisesRegex(b.BindingError, 'ancestry_required'): b.binding_chain(self.root, self.target)
        for name in b.GENESIS_FILES: (self.root / name).unlink()
        with self.assertRaisesRegex(b.BindingError, 'ancestry_required'): b.binding_chain(self.root, self.target)
    def test_exact_initial_proposal_then_real_init_then_first_baseline_admission(self):
        self.run_genesis()
        wrong = dict(self.init_identity, intent_sha256='0' * 64)
        with self.assertRaisesRegex(b.BindingError, 'exact_init_proposal'):
            b.binding_admit_operation(self.root, self.target, wrong, [])
        gate = i.Transcript(self)
        result = b.binding_initialize(self.target, self.init_identity, self.init_request,
                   policy_b_gate=gate, write_init=lambda identity, request, late: i.attestation(identity, request))
        self.assertEqual(result['completion'], 'LOCAL_METADATA_ATTESTED')
        _, _, current, _ = b.binding_history(self.root, self.target)
        self.assertEqual(current, [self.init_identity])
        completion = b.binding_init_readback(self.target, self.init_identity, digest(self.init_request))
        b.binding_require_init_completion(self.root, self.target, current, completion)
        baseline = dict(self.owner, intent_sha256='1' * 64, kind='STAGE', name='BASELINE_VERIFIED', action='baseline')
        b.binding_admit_operation(self.root, self.target, baseline, current)
        stage2 = dict(baseline, name='PRE_DRAIN_BACKUP_REHEARSED', action='backup-rehearsal')
        with self.assertRaisesRegex(b.BindingError, 'fresh_baseline'):
            b.binding_admit_operation(self.root, self.target, stage2, current)
    def test_partial_readonly_inspection_needs_no_readiness_or_lock(self):
        self.root.mkdir(mode=0o700)
        with patch.object(sys, 'argv', ['fixture', str(self.target)]):
            b.binding_entry('inspect')
        self.assertEqual(list(self.root.iterdir()), [])
    def test_real_two_process_creators_one_inode_one_owner(self):
        fixture_path = self.base / 'request.json'
        fixture_path.write_text(json.dumps(dict(request=self.request,index=self.index)))
        ready = self.base / 'ready'; ready.mkdir()
        children = [subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--creator', str(fixture_path),
                                      str(ready), str(number)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                    for number in range(2)]
        results = [child.communicate(timeout=30) for child in children]
        self.assertEqual(sorted(child.returncode for child in children), [0, 75], results)
        self.assertEqual(sum('GENESIS_DONE' in out for out, _ in results), 1)
        loser = next(err for child, (_, err) in zip(children, results) if child.returncode == 75)
        self.assertEqual(loser, 'GENESIS_REFUSED reason=genesis_registry_already_exists\n')
        self.assertEqual(b.binding_history(self.root, self.target)[0], self.owner)
        inode = (self.root / 'lock').stat().st_ino
        self.readback()
        self.assertEqual((self.root / 'lock').stat().st_ino, inode)

    def test_changed_inventory_or_legacy_owner_reuse_is_prewrite(self):
        self.gate.index = dict(self.index, runs=self.index['runs'][:1])
        with self.assertRaisesRegex(b.BindingError, 'admission_result'): self.run_genesis()
        self.assertFalse(self.root.exists())
        request = dict(self.request, run_id=self.index['runs'][0]['run_id'])
        with self.assertRaisesRegex(b.BindingError, 'legacy_run_reuse'):
            b.binding_genesis(self.target, b.binding_genesis_identity(request), request,
                              policy_b_gate=Transcript(request, self.index))
        self.assertFalse(self.root.exists())
    def test_signal_or_lock_replacement_after_late_never_creates_intent(self):
        original = self.gate.before_write
        def cancel(*args):
            original(*args)
            signal.getsignal(signal.SIGINT)(signal.SIGINT, None)
        self.gate.before_write = cancel
        with self.assertRaisesRegex(b.BindingError, 'cancelled'): self.run_genesis()
        self.assertFalse((self.root / 'genesis.intent.json').exists())
    def test_replaced_lock_inode_after_late_never_creates_intent(self):
        original = self.gate.before_write
        def replace(*args):
            original(*args)
            path = self.root / 'lock'; path.rename(self.root / 'displaced-lock')
            path.touch(mode=0o600)
        self.gate.before_write = replace
        with self.assertRaisesRegex(b.BindingError, 'lock_replaced'): self.run_genesis()
        self.assertFalse((self.root / 'genesis.intent.json').exists())
    def test_target_swap_immediately_before_mkdir_never_writes_replacement(self):
        real_mkdir=os.mkdir
        moved=self.base/'original-target'
        def swap(path,mode=0o777,*,dir_fd=None):
            if path=='.v126-target-operations' or Path(path)==self.root:
                self.target.rename(moved)
                real_mkdir(self.target,0o700)
            return real_mkdir(path,mode,dir_fd=dir_fd)
        with patch.object(os,'mkdir',swap):
            with self.assertRaisesRegex(b.BindingError,'target_changed'): self.run_genesis()
        self.assertEqual(list(self.target.iterdir()),[])
        retained=moved/'.v126-target-operations'
        self.assertTrue(retained.is_dir())
        self.assertEqual(list(retained.iterdir()),[])
    def test_registry_swap_at_metadata_open_never_writes_replacement(self):
        real_open=os.open
        moved=self.target/'original-registry'
        def swap(path,flags,*args,**kwargs):
            if path=='genesis.intent.json' and kwargs.get('dir_fd') is not None and flags & os.O_CREAT:
                self.root.rename(moved)
                self.root.mkdir(mode=0o700)
            return real_open(path,flags,*args,**kwargs)
        with patch.object(os,'open',swap):
            with self.assertRaisesRegex(b.BindingError,'registry_replaced'): self.run_genesis()
        self.assertEqual(list(self.root.iterdir()),[])
        self.assertTrue((moved/'genesis.intent.json').exists())
        self.assertFalse((moved/'genesis.result.json').exists())
    def test_registry_replacement_between_mkdir_and_first_open_is_refused(self):
        real_mkdir = os.mkdir
        replacement = self.target / 'replacement'; replacement.mkdir(mode=0o700)
        displaced = self.target / 'created-root'
        def swap(path, mode=0o777, *, dir_fd=None):
            result = real_mkdir(path, mode, dir_fd=dir_fd)
            if path == '.v126-target-operations':
                self.root.rename(displaced); replacement.rename(self.root)
            return result
        with patch.object(os, 'mkdir', swap), self.assertRaisesRegex(b.BindingError, 'registry_replaced'):
            self.run_genesis()
        self.assertEqual(list(self.root.iterdir()), [])
        self.assertEqual(list(displaced.iterdir()), [])
        self.assertEqual(self.gate.calls, 1)
        with self.assertRaisesRegex(b.BindingError, 'already_exists'): self.run_genesis()
    def test_registry_rename_away_and_back_before_first_open_is_refused(self):
        real_mkdir = os.mkdir
        moved = self.target / 'moved-root'
        def swap(path, mode=0o777, *, dir_fd=None):
            result = real_mkdir(path, mode, dir_fd=dir_fd)
            if path == '.v126-target-operations':
                self.root.rename(moved); moved.rename(self.root)
            return result
        with patch.object(os, 'mkdir', swap), self.assertRaisesRegex(b.BindingError, 'registry_replaced'):
            self.run_genesis()
        self.assertEqual(list(self.root.iterdir()), [])
    def test_namespace_watch_unavailable_is_prewrite(self):
        with patch.object(b, 'binding_genesis_watch', side_effect=OSError(errno.ENOSYS, 'watch unavailable')):
            with self.assertRaises(OSError): self.run_genesis()
        self.assertFalse(self.root.exists())
    def test_full_genesis_result_barrier_failure_never_acknowledges_or_retries(self):
        real = os.fsync
        for barrier in ('file', 'root'):
            with self.subTest(barrier=barrier), tempfile.TemporaryDirectory(dir=self.base) as tmp:
                target = Path(tmp); target.chmod(0o700)
                request, index, owner, init_identity, _ = fixture(target)
                identity = b.binding_genesis_identity(request); root = target / '.v126-target-operations'
                def fail(fd):
                    result = root / 'genesis.result.json'
                    if result.exists() and result.stat().st_size:
                        info = os.fstat(fd)
                        selected = 'root' if stat.S_ISDIR(info.st_mode) else 'file'
                        if selected == barrier: raise OSError(errno.EIO, 'full-result fsync')
                    real(fd)
                with patch.object(os, 'fsync', fail), self.assertRaises(OSError) as error:
                    b.binding_genesis(target, identity, request, policy_b_gate=Transcript(request, index),
                                      ack_genesis=lambda _: self.fail('completion acknowledged'))
                self.assertEqual(error.exception.errno, errno.EIO)
                for name, read in GenesisDurability().readers(root, target, request, index, owner, init_identity).items():
                    with self.subTest(reader=name), self.assertRaisesRegex(b.BindingError, 'record_metadata'): read()
                with self.assertRaisesRegex(b.BindingError, 'already_exists'):
                    b.binding_genesis(target, identity, request, policy_b_gate=Transcript(request, index))
    def test_replaced_registry_directory_with_same_lock_is_rejected(self):
        original = self.gate.before_write
        def replace(*args):
            original(*args)
            displaced = self.target / 'displaced-registry'
            self.root.rename(displaced)
            self.root.mkdir(mode=0o700)
            (displaced / 'lock').rename(self.root / 'lock')
        self.gate.before_write = replace
        with self.assertRaisesRegex(b.BindingError, 'registry_replaced'): self.run_genesis()
        self.assertFalse((self.root / 'genesis.intent.json').exists())
    def test_full_history_validation_preserves_genesis_across_transfer(self):
        self.run_genesis()
        immutable = self.snapshot()
        gate = i.Transcript(self)
        b.binding_initialize(self.target, self.init_identity, self.init_request, policy_b_gate=gate,
                             write_init=lambda identity, request, late: i.attestation(identity, request))
        completion = b.binding_init_readback(self.target, self.init_identity, digest(self.init_request))
        class StageGate:
            def check(self, identity, target, fd, timeout):
                b.binding_lock_held(target / '.v126-target-operations', fd)
                return dict(barrier='R0', operational=True, qualification_sha256='4' * 64)
            def before_dispatch(self, identity, target, fd, timeout):
                b.binding_lock_held(target / '.v126-target-operations', fd)
        baseline = dict(self.owner, intent_sha256='1' * 64, kind='STAGE', name='BASELINE_VERIFIED', action='baseline')
        leaf = self.base / 'capture'
        command = [sys.executable, '-c', 'import pathlib,sys;pathlib.Path(sys.argv[1]).write_text("captured")', str(leaf)]
        status = b.binding_supervise(self.target, baseline, command, policy_b_gate=StageGate(), init_completion=completion)
        self.assertEqual(status, 0)
        self.assertEqual(leaf.read_text(), 'captured')
        recovery = dict(self.owner, intent_sha256='2' * 64, kind='RECOVERY', name='pre-v126', action='recover-pre-v126')
        self.assertEqual(b.binding_supervise(self.target, recovery, [sys.executable, '-c', 'print("capture-only-recovery")']), 0)
        next_owner = dict(run_id='synthetic-next-cutover', release_sha='c' * 40, script_sha256='d' * 64)
        handoff = dict(format_version=1, owner=self.owner, next_owner=next_owner,
                       terminal_receipt_sha256='3' * 64, target_sha256=hashlib.sha256(str(self.target).encode()).hexdigest(),
                       operational_version='V125', backend_image='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',
                       image_id='sha256:' + '4' * 64, environment_sha256='5' * 64, compose_sha256='6' * 64,
                       caddy_runtime_sha256='7' * 64, config_owner='root:root', restart_policy='unless-stopped',
                       handoff_approved_and_applied=True, approval_id='synthetic-handoff', observed_at='2026-09-23T00:00:00Z')
        path = self.base / 'handoff.json'; b.binding_create(path, handoff)
        argv = ['fixture',str(self.target),*self.owner.values(),handoff['terminal_receipt_sha256'],str(path),
                *next_owner.values(),'V125',handoff['image_id']]
        with patch.object(sys, 'argv', argv): b.binding_entry('retire')
        current, owners, operations, recovery_seen = b.binding_history(self.root, self.target)
        self.assertEqual((current,owners,operations,recovery_seen),(next_owner,[self.owner,next_owner],[],False))
        for name, value in immutable.items(): self.assertEqual(self.snapshot()[name], value)
        transfer = b.binding_read(self.root/'transfers'/(b.binding_owner_id(self.owner)+'.json'))
        self.assertTrue(b.GENESIS_FILES.issubset(transfer['inventory']))
        next_identity, request = i.seed_request(self.target,next_owner)
        b.binding_admit_operation(self.root,self.target,next_identity,[])
        (self.root/'genesis.result.json').unlink()
        for reader in (b.binding_chain,b.binding_history,b.binding_active_policy):
            with self.assertRaisesRegex(b.BindingError, 'completion_required'): reader(self.root,self.target)
    def test_corrupt_index_cannot_be_hidden_from_history(self):
        self.run_genesis()
        path=self.root/'legacy-inventory.json'; value=json.loads(path.read_text())
        value['runs']=value['runs'][:1];path.unlink();b.binding_create(path,value)
        with self.assertRaisesRegex(b.BindingError, 'inventory_binding'): b.binding_history(self.root,self.target)

    def recovered_genesis(self):
        self.run_genesis()
        gate = i.Transcript(self)
        b.binding_initialize(self.target, self.init_identity, self.init_request, policy_b_gate=gate,
                             write_init=lambda identity, request, late: i.attestation(identity, request))
        completion = b.binding_init_readback(self.target, self.init_identity, digest(self.init_request))
        class StageGate:
            def check(self, identity, target, fd, timeout):
                b.binding_lock_held(target / '.v126-target-operations', fd)
                return dict(barrier='R0', operational=True, qualification_sha256='4' * 64)
            def before_dispatch(self, identity, target, fd, timeout):
                b.binding_lock_held(target / '.v126-target-operations', fd)
        baseline = dict(self.owner, intent_sha256='1' * 64, kind='STAGE', name='BASELINE_VERIFIED', action='baseline')
        context = dict(args=[str(self.target), self.owner['run_id'], self.owner['release_sha']], environment={})
        self.assertEqual(b.binding_supervise(self.target,baseline,[sys.executable,'-c','print("capture-baseline")'],
            policy_b_gate=StageGate(),init_completion=completion,request_context=context),0)
        recovery = dict(self.owner,intent_sha256='2'*64,kind='RECOVERY',name='pre-v126',action='recover-pre-v126')
        marker=self.base/'recovery-capture'
        command=[sys.executable,'-c','import pathlib,sys;pathlib.Path(sys.argv[1]).write_bytes(b"captured recovery")',str(marker)]
        self.assertEqual(b.binding_supervise(self.target,recovery,command,request_context=context),0)
        return recovery,marker

    def handoff_to(self, owner, next_owner, receipt):
        return dict(format_version=1,owner=owner,next_owner=next_owner,terminal_receipt_sha256=receipt,
            target_sha256=hashlib.sha256(str(self.target).encode()).hexdigest(),operational_version='V125',
            backend_image='synthetic:f577934691a1a7a79ba327c54e2055425142b7be',image_id='sha256:'+'4'*64,
            environment_sha256='5'*64,compose_sha256='6'*64,caddy_runtime_sha256='7'*64,
            config_owner='root:root',restart_policy='unless-stopped',handoff_approved_and_applied=True,
            approval_id='synthetic-handoff',observed_at='2026-09-23T00:00:00Z')

    def ordinary_request(self, owner, handoff):
        names=('target_sha256','operational_version','backend_image','image_id','environment_sha256',
               'caddy_runtime_sha256','config_owner','restart_policy','handoff_approved_and_applied')
        return dict(owner=owner,compose_before_sha256=handoff['compose_sha256'],**{k:handoff[k] for k in names})

    def test_genesis_ancestry_actual_reconcile_append_export_and_copy(self):
        recovery,marker=self.recovered_genesis()
        immutable={name:(self.root/name).read_bytes() for name in b.GENESIS_FILES|{'run.json','lock'}}
        observations=[]
        def observe(identity,request,operations):
            self.assertEqual(identity,recovery)
            self.assertEqual(request['args'][:3],[str(self.target),self.owner['run_id'],self.owner['release_sha']])
            self.assertEqual(marker.read_bytes(),b'captured recovery')
            observations.append(identity)
            return dict(outcome='EXACT_COMPLETED_EFFECT',capture_sha256=hashlib.sha256(marker.read_bytes()).hexdigest())
        args=(self.target,self.owner,'RECOVERY','pre-v126',recovery['intent_sha256'],self.owner['script_sha256'],
              '8'*64,['recover-pre-v126'],observe)
        record=b.binding_reconcile(*args)
        self.assertEqual(record['kind'],'RECONCILED_EFFECT')
        self.assertFalse(record['retry_allowed'])
        before=self.snapshot()
        self.assertEqual(b.binding_reconcile(*args),record)
        self.assertEqual(self.snapshot(),before)
        self.assertEqual(len(observations),1,'copy-only replay cannot repeat poststate acquisition')
        bundle=self.base/'reconciliation-copy.json'
        b.binding_create(bundle,b.binding_export_reconciliation(self.root,record))
        checked,logs=b.binding_verify_reconciliation_bundle(bundle,self.target,self.owner['script_sha256'],
                    'RECOVERY','pre-v126',recovery['intent_sha256'],'8'*64)
        self.assertEqual(checked,record)
        self.assertEqual(len(logs),1)
        self.assertEqual(b.binding_history(self.root,self.target)[0],self.owner)
        for name,raw in immutable.items(): self.assertEqual((self.root/name).read_bytes(),raw)
        (self.root/'genesis.result.json').unlink()
        with self.assertRaisesRegex(b.BindingError,'completion_required'): b.binding_reconcile(*args)
        self.assertEqual(len(observations),1)

    def test_genesis_ancestry_ordinary_transfer_supervisor_and_retire_deploy(self):
        self.recovered_genesis()
        immutable={name:(self.root/name).read_bytes() for name in b.GENESIS_FILES|{'run.json','lock'}}
        deploy_owner=dict(run_id='synthetic-first-deploy',release_sha='c'*40,script_sha256='d'*64)
        handoff=self.handoff_to(self.owner,deploy_owner,'3'*64)
        request=self.ordinary_request(deploy_owner,handoff)
        handoff_path=self.base/'handoff-first.json';request_path=self.base/'request-first.json'
        b.binding_create(handoff_path,handoff);b.binding_create(request_path,request)
        argv=['fixture',str(self.target),*self.owner.values(),handoff['terminal_receipt_sha256'],str(handoff_path),
              *deploy_owner.values(),'V125',handoff['image_id'],'ORDINARY_DEPLOY',str(request_path),'NATIVE_RECEIPT']
        with patch.object(sys,'argv',argv): b.binding_entry('retire')
        self.assertEqual(b.binding_active_policy(self.root,self.target),
                         dict(next_kind='ORDINARY_DEPLOY',request_sha256=digest(request)))
        identity=dict(deploy_owner,intent_sha256=digest(request),kind='DEPLOY',name='ORDINARY_DEPLOY',action='ordinary-deploy')
        operation=digest(identity)
        proof=dict(format_version=1,identity=identity,target_sha256=request['target_sha256'],request_sha256=digest(request),
                   environment_sha256=handoff['environment_sha256'],compose_sha256=handoff['compose_sha256'],
                   image_id=handoff['image_id'],backend_container_id='1'*64,database_identity_sha256='2'*64,
                   caddy_runtime_sha256=handoff['caddy_runtime_sha256'],result='ORDINARY_DEPLOY_COMPLETED',
                   completed_at='2026-09-23T00:00:00Z')
        proof_path=self.root/(operation+'.deploy-proof.json')
        # Capture-only privileged leaf; the actual supervisor must admit it only
        # after reading the exact v2 transfer and all completed genesis ancestry.
        command=[sys.executable,'-c','import os,sys;fd=os.open(sys.argv[1],os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o400);os.write(fd,sys.argv[2].encode());os.fsync(fd);os.close(fd)',str(proof_path),b.binding_canonical(proof).decode()]
        self.assertEqual(b.binding_supervise(self.target,identity,command,request_sha256=digest(request)),0)
        next_owner=dict(run_id='synthetic-next-deploy',release_sha='e'*40,script_sha256='f'*64)
        next_handoff=self.handoff_to(deploy_owner,next_owner,digest(proof))
        next_request=self.ordinary_request(next_owner,next_handoff)
        applied=self.base/'handoff-next.json';selected=self.base/'request-next.json'
        b.binding_create(applied,next_handoff);b.binding_create(selected,next_request)
        b.binding_retire_deploy(self.target,deploy_owner,digest(proof),applied,selected)
        current,owners,operations,recovery_seen=b.binding_history(self.root,self.target)
        self.assertEqual((current,owners,operations,recovery_seen),(next_owner,[self.owner,deploy_owner,next_owner],[],False))
        for name,raw in immutable.items(): self.assertEqual((self.root/name).read_bytes(),raw)
        first=b.binding_read(self.root/'transfers'/(b.binding_owner_id(self.owner)+'.json'))
        self.assertTrue(b.GENESIS_FILES.issubset(first['inventory']))
        self.assertEqual(b.binding_active_policy(self.root,self.target),
                         dict(next_kind='ORDINARY_DEPLOY',request_sha256=digest(next_request)))
        before=self.snapshot()
        with self.assertRaises(b.BindingError): b.binding_retire_deploy(self.target,deploy_owner,digest(proof),applied,selected)
        self.assertEqual(self.snapshot(),before)
        (self.root/'genesis.result.json').unlink()
        next_identity=dict(next_owner,intent_sha256=digest(next_request),kind='DEPLOY',name='ORDINARY_DEPLOY',action='ordinary-deploy')
        marker=self.base/'forbidden-deploy'
        forbidden=[sys.executable,'-c','import pathlib,sys;pathlib.Path(sys.argv[1]).touch()',str(marker)]
        with self.assertRaisesRegex(b.BindingError,'completion_required'):
            b.binding_supervise(self.target,next_identity,forbidden,request_sha256=digest(next_request))
        self.assertFalse(marker.exists())


def creator(request_path, ready, number):
    value = json.loads(Path(request_path).read_text()); request, index = value['request'], value['index']
    def barrier(count, fd):
        if count != 1: return
        (Path(ready) / number).touch()
        deadline = time.monotonic() + 15
        while len(list(Path(ready).iterdir())) != 2:
            if time.monotonic() >= deadline: raise RuntimeError('creator rendezvous timeout')
            time.sleep(0.01)
    gate = Transcript(request,index,on_check=barrier)
    try:
        b.binding_genesis(Path(request['target']['path']), b.binding_genesis_identity(request), request,policy_b_gate=gate)
    except b.BindingError as error:
        print('GENESIS_REFUSED reason=' + str(error),file=sys.stderr); return 75
    print('GENESIS_DONE'); return 0

def negative_controls():
    """Disposable source mutations must break their corresponding real regression."""
    original = (ROOT / 'v126-operation-bindings.py').read_text()
    start = original.index('def binding_lock_held(')
    end = original.index('\ndef binding_history(', start)
    unlocked = original[:start] + 'def binding_lock_held(root, fd):\n    pass\n\n' + original[end:]
    controls = [
        ('unpinned-first-mkdir', original.replace(
            "os.mkdir('.v126-target-operations', mode=0o700, dir_fd=targetfd)", "os.mkdir(root, mode=0o700)"),
         'test_target_swap_immediately_before_mkdir_never_writes_replacement'),
        ('unlock-check-removed', unlocked, 'test_cancel_before_first_write_and_unlock_before_dispatch'),
        ('completion-removed', original.replace('    owner, _ = binding_genesis_completion(root, target)',
            "    doc = binding_read(root / 'run.json')\n    owner = doc.get('owner', doc)"),
         'test_marker_deletion_or_owner_downgrade_does_not_admit_legacy'),
        ('inventory-binding-removed', original.replace(
            "            hashlib.sha256(binding_canonical(index)).hexdigest() != request['inventory_sha256'] or\n", ''),
         'test_corrupt_index_cannot_be_hidden_from_history'),
        ('creation-watch-removed', original.replace('        binding_genesis_created(watchfd, watch)\n', ''),
         'test_registry_replacement_between_mkdir_and_first_open_is_refused'),
    ]
    for name, source, test in controls:
        assert source != original
        with tempfile.TemporaryDirectory(prefix='genesis-disposable-mutation-') as tmp:
            directory = Path(tmp)
            for item in ('test-v126-genesis-history.py', 'test-v126-policy-b-init.py'):
                shutil.copyfile(ROOT / item, directory / item)
            (directory / 'v126-operation-bindings.py').write_text(source)
            result = subprocess.run([sys.executable,str(directory / 'test-v126-genesis-history.py'),
                                     'Genesis.' + test],capture_output=True,text=True,timeout=30)
            if result.returncode != 1 or 'FAILED (failures=1)' not in result.stderr:
                raise AssertionError(name + ' did not fail its assertion: ' + result.stderr)
            print('NEGATIVE_CONTROL ' + name + ' rejected exit=1 test=' + test)


if __name__ == '__main__':
    if sys.argv[1:] == ['--portable-safety']:
        unittest.main(argv=[sys.argv[0], 'GenesisDurability', 'NamespaceObservation'])
        raise SystemExit(0)
    if sys.platform != 'linux':
        raise SystemExit('Linux real flock/process validation required; no skipped PASS')
    if sys.argv[1:] == ['--negative-controls']:
        negative_controls(); raise SystemExit(0)
    if len(sys.argv) > 1 and sys.argv[1] == '--creator':
        raise SystemExit(creator(*sys.argv[2:]))
    unittest.main()
