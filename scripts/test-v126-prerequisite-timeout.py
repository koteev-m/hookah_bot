#!/usr/bin/env python3
"""Lightweight regressions for exact prerequisite fixture programs.

Extracts the actual fixture generator, timeout program and cleanup traps. Only
private files and owned processes are used; no controller suite, daemon, SSH or
network is invoked. Native GNU metadata/hash equivalence and a small comparative
timing sample run on Linux; macOS verifies the unchanged real-input adapters.
This proves fixture process bounds, not remote-operation or abort quiescence.
"""
import grp
import hashlib
import json
import os
from pathlib import Path
import pwd
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest


SOURCE_PATH = Path(os.environ.get('V126_PREREQ_TEST_SOURCE', Path(__file__).with_name('test-v126-staging-prerequisite-sync.sh')))
SOURCE = SOURCE_PATH.read_text()


def embedded(name):
    marker = 'cat > "${mock}/' + name + '" <<\'MOCK\'\n'
    if SOURCE.count(marker) != 1:
        raise AssertionError('fixture program boundary is ambiguous: ' + name)
    return SOURCE.split(marker, 1)[1].split('\nMOCK\n', 1)[0] + '\n'


class FixtureProgramTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='v126-prerequisite-programs-')
        cls.root = Path(cls.temp.name).resolve()
        cls.mocks = cls.root / 'mocks'
        generator = SOURCE[SOURCE.index('write_mock_git() {'):SOURCE.index('make_case() {')]
        generated = subprocess.run(['bash', '-c', 'set -euo pipefail\n' + generator + '\nwrite_remote_mocks "$1"\n',
                                    'fixture', str(cls.mocks)], text=True, capture_output=True, timeout=10)
        if generated.returncode:
            raise AssertionError(generated.stderr)
        for name in ('stat', 'sha256sum', 'timeout'):
            path = cls.mocks / name
            if path.is_symlink() or not stat.S_ISREG(path.lstat().st_mode):
                raise AssertionError('generated wrapper must be a regular file')
        cls.fallbacks = {}
        for name in ('stat', 'sha256sum'):
            path = cls.root / ('fallback-' + name)
            path.write_text(embedded(name))
            path.chmod(0o500)
            cls.fallbacks[name] = path
        print('FIXTURE_SOURCE_SHA256=' + hashlib.sha256(SOURCE_PATH.read_bytes()).hexdigest(), flush=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.case = Path(tempfile.mkdtemp(prefix='case-', dir=self.root))
        self.pidfile = self.case / 'owned-pids.json'

    def running(self, pid):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        if sys.platform == 'linux':
            try:
                # Only this test's recorded child, never a process inventory.
                state = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[0]
                return state != 'Z'
            except FileNotFoundError:
                return False
        return True

    def owned_records(self):
        return json.loads(self.pidfile.read_text()) if self.pidfile.exists() else []

    def tearDown(self):
        # These identities came only from this test's own child program.
        for pid, pgid in self.owned_records():
            if pid > 1 and pgid > 1 and self.running(pid):
                try:
                    os.killpg(pgid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def bounded(self, code, *, duration='1s', signal_to_forward=None):
        env = {key: value for key, value in os.environ.items() if key != 'V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE'}
        command = [sys.executable, str(self.mocks / 'timeout'), '--signal=TERM', '--kill-after=1s', duration,
                   sys.executable, '-c', code, str(self.pidfile)]
        start = time.monotonic()
        process = subprocess.Popen(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env=env, start_new_session=True)
        try:
            if signal_to_forward is not None:
                ready_deadline = time.monotonic() + 3
                while not self.pidfile.exists() and time.monotonic() < ready_deadline:
                    time.sleep(0.02)
                self.assertTrue(self.pidfile.exists(), 'owned child readiness missing')
                os.kill(process.pid, signal_to_forward)
            stdout, stderr = process.communicate(timeout=6)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=2)
            self.fail('fixture program exceeded the independent six-second bound')
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
        return process.returncode, stdout, stderr, time.monotonic() - start

    def assert_owned_inactive(self):
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            active = [pid for pid, _ in self.owned_records() if self.running(pid)]
            if not active:
                return
            time.sleep(0.02)
        self.fail('own fixture descendants remained active: ' + str(active))

    def test_non_signal_timeout_is_real(self):
        status, _, _, elapsed = self.bounded('import time; time.sleep(3)')
        self.assertEqual(status, 124)
        self.assertGreaterEqual(elapsed, 0.8)
        self.assertLess(elapsed, 2.5)

    def test_non_timeout_producer_status_and_output_are_preserved(self):
        for status in (0, 42):
            with self.subTest(status=status):
                actual, output, _, _ = self.bounded(f'print("actual-child-output"); raise SystemExit({status})')
                self.assertEqual(actual, status)
                self.assertEqual(output, 'actual-child-output\n')

    def test_timeout_does_not_accept_successful_term_handler(self):
        status, _, _, _ = self.bounded('import signal,time; signal.signal(signal.SIGTERM, lambda *_: exit(0)); time.sleep(3)')
        self.assertEqual(status, 124)

    def test_kill_after_bounds_term_ignoring_children(self):
        code = '''import json,os,signal,subprocess,sys,time
signal.signal(signal.SIGTERM, signal.SIG_IGN)
child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'])
open(sys.argv[1],'w').write(json.dumps([[os.getpid(),os.getpgrp()],[child.pid,os.getpgrp()]]))
time.sleep(30)
'''
        status, _, _, elapsed = self.bounded(code)
        self.assertEqual(status, 137)
        self.assertLess(elapsed, 3.5)
        self.assert_owned_inactive()

    def test_forwarded_int_and_term_preserve_signal_status(self):
        code = '''import json,os,sys,time
open(sys.argv[1],'w').write(json.dumps([[os.getpid(),os.getpgrp()]]))
time.sleep(30)
'''
        for signum in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signum):
                self.pidfile.unlink(missing_ok=True)
                status, _, _, elapsed = self.bounded(code, duration='5s', signal_to_forward=signum)
                self.assertEqual(status, 128 + signum)
                self.assertLess(elapsed, 2)
                self.assert_owned_inactive()

    def test_forwarded_signal_cannot_be_converted_to_success(self):
        code = '''import json,os,signal,sys,time
signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
open(sys.argv[1],'w').write(json.dumps([[os.getpid(),os.getpgrp()]]))
time.sleep(30)
'''
        for signum in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signum):
                self.pidfile.unlink(missing_ok=True)
                status, _, _, elapsed = self.bounded(code, duration='5s', signal_to_forward=signum)
                self.assertEqual(status, 128 + signum)
                self.assertLess(elapsed, 2)
                self.assert_owned_inactive()

    def test_early_parent_exit_cleans_owned_descendant(self):
        code = '''import json,os,subprocess,sys
child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
open(sys.argv[1],'w').write(json.dumps([[child.pid,os.getpgrp()]]))
raise SystemExit(42)
'''
        status, _, _, _ = self.bounded(code)
        self.assertEqual(status, 42)
        self.assert_owned_inactive()

    def test_actual_term_cleanup_keeps_mock_path_for_exiting_child(self):
        fixture = self.case / 'ht12r-prerequisite-fixtures.own'
        fixture.mkdir()
        (fixture / 'mock').write_text('own mock stays available')
        done = self.case / 'child-observation'
        ready = self.case / 'cleanup-ready'
        cleanup = SOURCE[SOURCE.index('cleanup() {'):SOURCE.index('fail() {')]
        child_code = 'import pathlib,sys,time; time.sleep(.4); pathlib.Path(sys.argv[2]).write_text(str(pathlib.Path(sys.argv[1]).exists()))'
        shell = 'set -euo pipefail\nfixture_root="$1"\n' + cleanup + '''
python3 -c "$2" "$fixture_root/mock" "$3" &
owned_child=$!
printf 'ready\\n' > "$4"
wait "$owned_child"
'''
        process = subprocess.Popen(['bash', '-c', shell, 'fixture', str(fixture), child_code, str(done), str(ready)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        try:
            deadline = time.monotonic() + 2
            while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists(), 'actual cleanup trap fixture did not become ready')
            os.kill(process.pid, signal.SIGTERM)
            _, stderr = process.communicate(timeout=3)
            self.assertEqual(process.returncode, 143)
            self.assertIn('fixtures retained', stderr)
            self.assertEqual(done.read_text(), 'True')
            self.assertTrue((fixture / 'mock').exists())
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=2)

    def test_exit_cleanup_preserves_failure_status_and_removes_success_only(self):
        cleanup = SOURCE[SOURCE.index('cleanup() {'):SOURCE.index('fail() {')]
        for status in (0, 42):
            with self.subTest(status=status):
                fixture = self.case / f'ht12r-prerequisite-fixtures.exit-{status}'
                fixture.mkdir()
                (fixture / 'mock').write_text('private fixture mock')
                env = {key: value for key, value in os.environ.items() if key != 'V126_PREREQ_KEEP_FIXTURES'}
                result = subprocess.run(['bash', '-c', 'fixture_root="$1"\n' + cleanup + '\nexit "$2"\n',
                                         'fixture', str(fixture), str(status)],
                                        capture_output=True, text=True, timeout=3, env=env)
                self.assertEqual(result.returncode, status)
                self.assertEqual(fixture.exists(), status != 0)

    def test_metadata_native_or_fallback_equivalence(self):
        item = self.case / 'metadata-file'
        item.write_bytes(b'actual bytes\n')
        item.chmod(0o640)
        link = self.case / 'metadata-link'
        link.symlink_to(item)
        for path in (item, link):
            meta = path.lstat()
            expected = f'{stat.S_IMODE(meta.st_mode):o}:{meta.st_uid}:{meta.st_gid}:{meta.st_size}:{pwd.getpwuid(meta.st_uid).pw_name}:{grp.getgrgid(meta.st_gid).gr_name}\n'
            for program in (self.mocks / 'stat', self.fallbacks['stat']):
                result = subprocess.run([str(program), '-c', '%a:%u:%g:%s:%U:%G', str(path)],
                                        text=True, capture_output=True, timeout=3)
                self.assertEqual((result.returncode, result.stdout), (0, expected))
        if sys.platform == 'linux':
            self.assertIn('exec /usr/bin/stat "$@"', (self.mocks / 'stat').read_text())

    def test_digest_files_and_stdin_equivalence(self):
        payload = b'synthetic digest fixture\x00\xff\n'
        item = self.case / 'hash-file'
        item.write_bytes(payload)
        link = self.case / 'hash-link'
        link.symlink_to(item)
        digest = hashlib.sha256(payload).hexdigest()
        for path in (item, link, '-'):
            for program in (self.mocks / 'sha256sum', self.fallbacks['sha256sum']):
                result = subprocess.run([str(program), str(path)], input=payload if path == '-' else None,
                                        capture_output=True, timeout=3)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.decode(), f'{digest}  {path}\n')
        if sys.platform == 'linux':
            self.assertIn('exec /usr/bin/sha256sum "$@"', (self.mocks / 'sha256sum').read_text())

    @unittest.skipUnless(sys.platform == 'linux', 'native GNU before/after timings require Linux')
    def test_native_gnu_equivalent_call_microbenchmark(self):
        item = self.case / 'timing-input'
        item.write_bytes(b'actual unchanged fixture bytes\n')
        result = {'samples_per_program': 20, 'source': 'actual generated and extracted fallback programs'}
        for name, arguments in (('stat', ['-c', '%a:%u:%g:%s', str(item)]), ('sha256sum', [str(item)])):
            for label, program in (('fallback', self.fallbacks[name]), ('native', self.mocks / name)):
                start = time.monotonic()
                for _ in range(20):
                    called = subprocess.run([str(program), *arguments], capture_output=True, timeout=3)
                    self.assertEqual(called.returncode, 0)
                result[name + '_' + label + '_seconds'] = round(time.monotonic() - start, 6)
        print('NATIVE_GNU_EQUIVALENCE_TIMING=' + json.dumps(result, sort_keys=True), flush=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
