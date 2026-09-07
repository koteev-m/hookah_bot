#!/usr/bin/env python3
"""Production guard fixtures and synchronized, local-only real process regression."""
import contextlib
import os
from pathlib import Path
import re
import select
import signal
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent
SOURCE = (ROOT / 'v126-staging-prerequisite-sync.sh').read_text()
BEGIN, END = '# HT12V_PROCESS_GUARD_BEGIN\n', '# HT12V_PROCESS_GUARD_END'
assert SOURCE.count(BEGIN) == SOURCE.count(END) == 1
GUARD = SOURCE.split(BEGIN)[1].split(END)[0]
SECRET = 'PRIVATE_PROCESS_ARG_SENTINEL'
PS = shutil.which('ps')
AWK = shutil.which('awk')
BASH = shutil.which('bash')

# This produces a structured inventory, never unconditional permission. PIDs of the
# actual observer and collector anchor each fixture; competitors/errors are explicit.
PS_FIXTURE = r'''import os, sys
assert sys.argv[1:] == ['-ww', '-eo', 'pid=,ppid=,args=']
mode = os.environ.get('V126_FIXTURE_PROCESS_MODE', 'valid')
observer, collector = os.getppid(), os.getpid()
rows = f'{observer} 1 python3 -\n{collector} {observer} ps -ww -eo pid=,ppid=,args=\n'
if mode == 'empty': rows = ''
elif mode == 'malformed': rows += 'PRIVATE_PROCESS_ARG_SENTINEL\n'
elif mode == 'nul': rows += '777 1 invalid\x00PRIVATE_PROCESS_ARG_SENTINEL\n'
elif mode == 'cr': rows += '777 1 bad\r\n'
elif mode == 'no-newline': rows = rows[:-1]
elif mode == 'duplicate': rows += f'{collector} {observer} duplicate\n'
elif mode == 'missing-observer': rows = f'{collector} 1 ps\n'
elif mode == 'missing-collector': rows = f'{observer} 1 python3 -\n'
elif mode == 'wrong-parent': rows = f'{observer} 1 python3 -\n{collector} 0 ps\n'
elif mode == 'blank-command': rows += '777 1   \n'
elif mode == 'pid-only': rows = '123\n'
elif mode == 'header': rows = 'PID PPID COMMAND\n' + rows
elif mode == 'ps-error': rows = ''
elif mode == 'observer-match': rows = f'{observer} 1 python3 pg_dump PRIVATE_PROCESS_ARG_SENTINEL\n{collector} {observer} ps\n'
elif mode == 'conflict': rows += f'2000000000 {observer} python3 inert pg_dump PRIVATE_PROCESS_ARG_SENTINEL\n'
elif mode == 'command': rows += f'2000000000 {observer} ' + os.environ['FIXTURE_COMMAND'] + '\n'
elif mode not in ('valid', 'valid-error'): raise SystemExit(99)
sys.stdout.write(rows)
sys.stderr.write('PRIVATE_PROCESS_ARG_SENTINEL')
raise SystemExit(23 if mode in ('ps-error', 'valid-error') else 0)
'''


def ready(fd):
    if not select.select([fd], [], [], 10)[0]:
        raise AssertionError('owned process did not reach readiness barrier')
    if os.read(fd, 1024).strip() != b'READY':
        raise AssertionError('invalid owned-process readiness marker')


class ProcessGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ht12v-process-')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)

    def probe(self, mode=None, command=None, parser=None, own_child=False):
        env = dict(os.environ, LC_ALL='C')
        if mode is not None:
            ps = self.path / 'ps'
            ps.write_text('#!' + sys.executable + '\n' + PS_FIXTURE)
            ps.chmod(0o700)
            env.update(PATH=str(self.path) + os.pathsep + os.environ['PATH'],
                       V126_FIXTURE_PROCESS_MODE=mode, FIXTURE_COMMAND=command or '')
        wrapper = 'remote_die() { printf "%s\\n" "$1" >&2; return 1; }\n'
        if parser is not None:
            wrapper += 'python3() { cat >/dev/null; ' + parser + '; }\n'
        child_setup = ''
        if own_child:
            fifo = self.path / 'child-ready'
            os.mkfifo(fifo)
            child_setup = f"""
{shlex.quote(sys.executable)} -c 'import signal; print("READY", flush=True); signal.pause()' pg_dump {SECRET} >{shlex.quote(str(fifo))} &
owned=$!
trap 'kill "$owned" 2>/dev/null || :; wait "$owned" 2>/dev/null || :' EXIT
printf '%s' "$owned" >{shlex.quote(str(self.path / 'child-pid'))}
read -r marker <{shlex.quote(str(fifo))}
[[ "$marker" == READY ]] || exit 99
"""
        script = 'set -euo pipefail\n' + wrapper + GUARD + child_setup + '''
# Conditional invocation also checks explicit status propagation with errexit disabled.
if require_no_competing_processes; then
  printf 'CONTINUATION_ALLOWED\n'
else
  exit 1
fi
'''
        with subprocess.Popen([BASH, '-s'], env=env, stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              start_new_session=True) as process:
            try:
                stdout, stderr = process.communicate(script.encode(), timeout=20)
            except subprocess.TimeoutExpired:
                # This session contains only this test's shell and owned children.
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                self.fail('owned guard session exceeded its deadline')
            result = subprocess.CompletedProcess([BASH, '-s'], process.returncode, stdout, stderr)
        output = result.stdout + result.stderr
        self.assertFalse(SECRET.encode() in output, 'sensitive process marker escaped')
        if result.returncode:
            self.assertNotIn(b'CONTINUATION_ALLOWED', output)
            self.assertRegex(result.stderr.decode(), r'PROCESS_GUARD pid=\d+ category=[A-Z]+ reason=[a-z_]+')
        else:
            self.assertEqual(result.stdout, b'CONTINUATION_ALLOWED\n')
        return result

    def test_valid_inventory_and_all_contract_categories(self):
        self.assertEqual(self.probe('valid').returncode, 0)
        self.assertEqual(self.probe('observer-match').returncode, 0)
        categories = {
            'CUTOVER': ['v126-cutover.sh'],
            'PREREQUISITE': ['v126-staging-prerequisite-sync.sh'],
            'BACKUP': ['pg_dump', 'pg_dumpall'], 'RESTORE': ['pg_restore'],
            'CADDY': ['caddy ' + action for action in ('reload', 'stop', 'start')],
            'COMPOSE': ['docker compose ' + action for action in
                        ('up', 'down', 'stop', 'start', 'restart', 'create', 'run')],
        }
        for category, commands in categories.items():
            for command in commands:
                for interpreter in ('bash', 'python3', 'awk'):
                    with self.subTest(category=category, command=command, interpreter=interpreter):
                        result = self.probe('command', f'{interpreter} inert {command} {SECRET}')
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn(f'pid=2000000000 category={category} reason=competing_operation'.encode(), result.stderr)
        for command in ('bash idle', 'python3 idle', 'awk idle', 'caddy version', 'docker compose ps'):
            self.assertEqual(self.probe('command', command).returncode, 0)

    def test_invalid_and_incomplete_inventory_is_not_absence(self):
        for mode in ('empty', 'malformed', 'nul', 'cr', 'no-newline', 'duplicate',
                     'missing-observer', 'missing-collector', 'wrong-parent',
                     'blank-command', 'pid-only', 'header', 'ps-error', 'valid-error'):
            with self.subTest(mode=mode):
                result = self.probe(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b'category=INVENTORY', result.stderr)
        self.assertIn(b'reason=producer_failed', self.probe('valid-error').stderr)

    def test_parser_errors_and_success_looking_nonzero_are_closed_and_private(self):
        for parser in ('return 9', "printf 'PROCESS_INVENTORY=PASS'; return 9",
                       "printf 'PROCESS_INVENTORY=PASS'; return 0",
                       f"printf '{SECRET}'; printf '{SECRET}' >&2; return 2",
                       "printf '0'; return 0", 'return 0'):
            # Exact successful parser protocol is the only allowed success.
            with self.subTest(case=parser.split(";")[-1]):
                expected = parser == "printf 'PROCESS_INVENTORY=PASS'; return 0"
                self.assertEqual(self.probe(parser=parser).returncode == 0, expected)

    def test_unexpected_parser_exception_is_sanitized(self):
        # Inject a failing stdlib dependency, leaving the production program unchanged.
        (self.path / 'subprocess.py').write_text(
            'def Popen(*args, **kwargs):\n    raise ValueError("' + SECRET + '")\n')
        with mock.patch.dict(os.environ, PYTHONPATH=str(self.path)):
            result = self.probe()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'category=PARSER reason=parser_failed', result.stderr)

    def test_real_old_observer_self_match_with_barrier(self):
        old = subprocess.run(['git', '-C', str(ROOT), 'show',
                              '55721216febf00111db26ad6e37cdd02728424a5:scripts/v126-staging-prerequisite-sync.sh'],
                             capture_output=True, check=True).stdout.decode()
        line = next(line for line in old.splitlines() if 'competing="$(ps -eo' in line)
        program = re.search(r"parent=\"\$\{PPID\}\" '(.+)'\)", line)[1]
        ready_path, gate_path = self.path / 'ready', self.path / 'gate'
        os.mkfifo(ready_path)
        os.mkfifo(gate_path)
        read_ready = os.open(ready_path, os.O_RDWR | os.O_NONBLOCK)
        write_gate = os.open(gate_path, os.O_RDWR | os.O_NONBLOCK)
        try:
            barrier = (f'BEGIN {{ print "READY" > "{ready_path}"; '
                       f'close("{ready_path}"); '
                       f'getline gate < "{gate_path}"; close("{gate_path}") }} ')
            awk_args = [AWK, '-v', f'self={os.getpid()}', '-v', f'parent={os.getppid()}', barrier + program]
            if sys.platform == 'darwin':
                # BSD awk overwrites -v argv while parsing. Set the same exclusion
                # variables in BEGIN so the synchronized predicate stays ps-visible.
                bindings = f'BEGIN {{ self={os.getpid()}; parent={os.getppid()} }} '
                awk_args = [AWK, bindings + barrier + program]
            with subprocess.Popen(awk_args,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as child:
                try:
                    ready(read_ready)
                    # BSD ps truncates redirected argv unless full width is explicit.
                    ps_args = [PS, *(['-ww'] if sys.platform == 'darwin' else []), '-eo', 'pid=,args=']
                    snapshot = subprocess.run(ps_args, capture_output=True, check=True).stdout
                    owned = [row for row in snapshot.splitlines() if row.split(None, 1)[0] == str(child.pid).encode()]
                    self.assertEqual(len(owned), 1)
                    self.assertTrue(b'v126-staging-prerequisite-sync' in owned[0], 'old observer argv predicate was not visible')
                    os.write(write_gate, b'RELEASE\n')
                    stdout, stderr = child.communicate(snapshot, timeout=10)
                    self.assertEqual(child.returncode, 0)
                    self.assertGreaterEqual(int(stdout), 1)
                    # The exact old predicate counts its own observer independently of other rows.
                    count = subprocess.run([AWK, '-v', f'self={os.getpid()}', '-v', f'parent={os.getppid()}', program],
                                           input=owned[0] + b'\n', capture_output=True, check=True).stdout
                    self.assertEqual(count.strip(), b'1')
                finally:
                    if child.poll() is None:
                        child.kill()
                        child.communicate()
        finally:
            for fd in (read_ready, write_gate):
                os.close(fd)

    @contextlib.contextmanager
    def inert(self, interpreter, arguments):
        if interpreter == 'python':
            argv = [sys.executable, '-c', 'import sys; print("READY", flush=True); sys.stdin.read()', *arguments]
        elif interpreter == 'bash':
            argv = [BASH, '-c', 'printf "READY\\n"; read -r release', 'inert', *arguments]
        else:
            argv = [AWK, 'BEGIN { print "READY"; fflush(); getline release < "/dev/stdin" }', *arguments]
        child = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            ready(child.stdout.fileno())
            yield child
        finally:
            if child.poll() is None:
                child.terminate()
            try:
                child.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.communicate()

    def test_real_child_of_guard_shell_remains_a_competitor(self):
        result = self.probe(own_child=True)
        pid = int((self.path / 'child-pid').read_text())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f'pid={pid} category=BACKUP'.encode(), result.stderr)
        self.assertEqual(self.probe().returncode, 0)

    def test_real_production_guard_and_inert_interpreters(self):
        self.assertEqual(self.probe().returncode, 0)
        for interpreter in ('python', 'bash', 'awk'):
            for command, category in ((['pg_dump'], 'BACKUP'),
                                      (['docker', 'compose', 'restart'], 'COMPOSE')):
                with self.subTest(interpreter=interpreter, category=category):
                    with self.inert(interpreter, [*command, SECRET]) as child:
                        result = self.probe()
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn(f'pid={child.pid} category={category}'.encode(), result.stderr)
                        self.assertIsNone(child.poll())
                    self.assertEqual(self.probe().returncode, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
