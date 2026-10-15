#!/usr/bin/env python3
"""Check the real isolated harness's source closure using only disposable fixtures.

The Docker shim reads the actual copied mount, never the checkout. This portable
regression is not a Linux integration result; Linux runs also execute Genesis.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
GENESIS_CASES = (
    'test_existing_ordinary_writer_blocks_genesis_before_registry_creation',
    'test_genesis_holds_ordinary_lock_before_mkdir_through_completion',
    'test_ordinary_state_change_during_genesis_retains_partial_registry',
)


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture_cases(directory):
    scripts = directory / 'scripts'
    release = load(scripts / 'staging_release.py', 'isolated_ordinary_release')
    history = load(scripts / 'test-v126-genesis-history.py', 'isolated_genesis_history')
    assert Path(release.__file__).resolve().parent == scripts.resolve()
    assert Path(history.__file__).resolve().parent == scripts.resolve()
    loader = unittest.TestLoader()
    names = loader.getTestCaseNames(history.Genesis)
    assert all(name in names for name in GENESIS_CASES)
    suite = loader.loadTestsFromTestCase(history.OrdinaryExclusion)
    ordinary_count = suite.countTestCases()
    assert ordinary_count > 0
    if sys.platform == 'linux':
        suite.addTests(history.Genesis(name) for name in GENESIS_CASES)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful() or result.skipped:
        raise SystemExit('isolated source cases failed or skipped')
    print(json.dumps(dict(inventory=sorted(p.name for p in scripts.iterdir()),
                          ordinary_cases=ordinary_count, genesis_cases=list(GENESIS_CASES),
                          executed=result.testsRun, linux_genesis=sys.platform == 'linux')))


# Only this disposable command replaces Docker; no daemon or remote endpoint is used.
DOCKER_SHIM = r'''
import os
from pathlib import Path
import subprocess
import sys

args = sys.argv[1:]
assert args[0] == 'run'
assert args[args.index('--network') + 1] == 'none'
assert '--read-only' in args
assert args[args.index('--workdir') + 1] == '/work'
mount = args[args.index('--mount') + 1]
assert mount.startswith('type=bind,src=') and mount.endswith(',dst=/work,readonly')
fixture = Path(mount[len('type=bind,src='):-len(',dst=/work,readonly')])
assert not (fixture / '.git').exists() and not (fixture / '.env').exists()
assert args[-4:-1] == ['synthetic-source-inventory:local', 'bash', '-ceu']
command = args[-1]
assert '    python3 scripts/test-v126-genesis-history.py\n' in command
assert '    python3 scripts/test-v126-isolated-inventory.py\n' in command
if os.environ.get('REMOVE_ORDINARY_MODULE') == '1':
    (fixture / 'scripts/staging_release.py').unlink()
    # The real test consumer must fail, not just our inventory assertion.
    completed = subprocess.run([sys.executable, '-I',
        str(fixture / 'scripts/test-v126-genesis-history.py'), '--portable-safety'],
        cwd=fixture, env=os.environ)
else:
    completed = subprocess.run([sys.executable, '-I',
        str(fixture / 'scripts/test-v126-isolated-inventory.py'), '--fixture-cases', str(fixture)],
        cwd=fixture, env=os.environ)
raise SystemExit(completed.returncode)
'''


class IsolatedSourceInventory(unittest.TestCase):
    def run_harness(self, missing=False):
        with tempfile.TemporaryDirectory(prefix='v126-source-closure-') as tmp:
            base = Path(tmp)
            shim = base / 'docker'
            shim.write_text('#!' + sys.executable + '\n' + DOCKER_SHIM)
            shim.chmod(0o700)
            # Do not inherit credentials, application configuration or Python paths.
            env = dict(PATH=str(base) + os.pathsep + os.defpath, HOME=str(base),
                       TMPDIR=str(base), PYTHONDONTWRITEBYTECODE='1',
                       REMOVE_ORDINARY_MODULE='1' if missing else '0')
            return subprocess.run(['/bin/bash', str(ROOT / 'test-v126-policy-b-isolated.sh'),
                                   'synthetic-source-inventory:local'],
                                  env=env, cwd=base, capture_output=True, text=True, timeout=60)

    def test_actual_inventory_imports_and_runs_new_consumers(self):
        result = self.run_harness()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertIn('staging_release.py', report['inventory'])
        self.assertGreater(report['ordinary_cases'], 0)
        self.assertEqual(report['genesis_cases'], list(GENESIS_CASES))
        self.assertEqual(report['executed'], report['ordinary_cases'] +
                         (len(GENESIS_CASES) if sys.platform == 'linux' else 0))
        self.assertEqual(report['linux_genesis'], sys.platform == 'linux')
        self.assertNotIn('skipped', result.stderr)

    def test_missing_module_is_an_error_in_the_actual_test_consumer(self):
        result = self.run_harness(missing=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FileNotFoundError', result.stderr)
        self.assertIn('staging_release.py', result.stderr)
        self.assertIn('FAILED (errors=', result.stderr)
        self.assertNotIn('OK', result.stderr)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--fixture-cases':
        fixture_cases(Path(sys.argv[2]))
    else:
        unittest.main()
