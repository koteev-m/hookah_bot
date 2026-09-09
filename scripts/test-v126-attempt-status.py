#!/usr/bin/env python3
"""Real controller/status; only Git, GitHub and remote effects are synthetic."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
SCRIPT = Path(os.environ.get("V126_STATUS_TEST_SOURCE", ROOT / "v126-cutover.sh"))
HARNESS = ROOT / "test-v126-cutover.sh"


class Attempts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="v126-attempts-")
        self.root = Path(self.temp.name)
        (self.root / "release-worktree").mkdir()
        self.env = dict(os.environ, DOCKER_HOST="unix://" + str(self.root / "absent.sock"))
        self.env.pop("DOCKER_CONTEXT", None)

    def tearDown(self):
        self.temp.cleanup()

    def run_shell(self, body, *args):
        return subprocess.run(["bash", "-c", body, "repair-fixture", *map(str, args)],
                              env=self.env, capture_output=True, text=True, timeout=45)

    def seed(self, count=0):
        result = self.run_shell('source "$1"; TEST_ROOT="$2"; new_state "$4" repair; '
                                'seed_chain "$NEW_STATE" "$3"', HARNESS, self.root, count, SCRIPT)
        self.assertEqual(result.returncode, 0, result.stderr)
        return self.root / "state-repair"

    def status(self, state):
        return self.run_shell('bash "$1" status --state-dir "$2"', SCRIPT, state)

    def test_before_dispatch_retry_keeps_each_attempt(self):
        state = self.seed()
        manifest = json.loads((state / "run.json").read_text())
        fakebin = self.root / "bin"
        fakebin.mkdir()
        self.env.update(PATH=str(fakebin) + ":" + self.env["PATH"],
                        FIXTURE_MANIFEST=str(state / "run.json"), FIXTURE_SOURCE=str(SCRIPT))
        programs = {
            "git": '''#!/usr/bin/env python3
import json,os,pathlib,sys
m=json.loads(pathlib.Path(os.environ['FIXTURE_MANIFEST']).read_text())
a=' '.join(sys.argv[1:])
if 'fetch --no-tags' in a or 'status --porcelain' in a: pass
elif 'cat-file blob' in a: sys.stdout.buffer.write(pathlib.Path(os.environ['FIXTURE_SOURCE']).read_bytes())
elif 'show -s' in a: print(' '.join(m['release_parents']))
elif '^{tree}' in a: print(m['release_tree'])
elif 'rev-parse' in a: print(m['release_sha'])
else: raise SystemExit(97)
''',
            "gh": '#!/bin/sh\nprintf \'{"status":"completed","conclusion":"success"}\\n\'\nexit 77\n',
            "docker": '#!/bin/sh\necho UNEXPECTED_DOCKER\nexit 99\n',
            "ssh": '#!/bin/sh\necho UNEXPECTED_SSH\nexit 99\n',
        }
        for name, source in programs.items():
            path = fakebin / name
            path.write_text(source)
            path.chmod(0o700)
        body = 'source "$1"; stage_command stage --state-dir "$2" BASELINE_VERIFIED'
        first = self.run_shell(body, SCRIPT, state)
        self.assertNotEqual(first.returncode, 0)
        self.assertFalse(list((state / "intents").glob("*.json")))
        saved = {p: hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in (state / "attempts").rglob("*") if p.is_file()}
        self.assertTrue(saved)
        log = next((state / "attempts").rglob("operation.log")).read_text()
        self.assertIn("main Actions evidence command failed", log)
        self.assertNotIn("UNEXPECTED_", log)
        second = self.run_shell(body, SCRIPT, state)
        self.assertNotEqual(second.returncode, 0)
        self.assertNotIn("prior intent", second.stderr)
        for path, digest in saved.items():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest)
        outcomes = list((state / "attempts").rglob("result.json"))
        self.assertEqual(len(outcomes), 2)
        self.assertTrue(all('"dispatch":"NOT_DISPATCHED"' in p.read_text() for p in outcomes))
        self.assertIn("BASELINE_VERIFIED=NOT_STARTED", self.status(state).stdout)

    def test_clean_status(self):
        state = self.seed()
        result = self.status(state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("BASELINE_VERIFIED=NOT_STARTED", result.stdout)
        self.assertIn("next_action=EXECUTE_NEXT_AUTHORIZED_STAGE", result.stdout)
        self.assertIn("availability=NOT_OBSERVED", result.stdout)

    def test_intent_unknown_and_no_replay(self):
        state = self.seed()
        result = self.run_shell('source "$1"; load_state "$2"; write_stage_intent BASELINE_VERIFIED',
                                SCRIPT, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        before = {p: p.read_bytes() for p in state.rglob("*") if p.is_file()}
        result = self.status(state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("BASELINE_VERIFIED=RECONCILIATION_REQUIRED", result.stdout)
        self.assertIn("retry_allowed=false", result.stdout)
        self.assertIn("next_stage=NONE", result.stdout)
        for p, raw in before.items():
            self.assertEqual(p.read_bytes(), raw)
        retry = self.run_shell('bash "$1" stage --state-dir "$2" BASELINE_VERIFIED', SCRIPT, state)
        self.assertEqual(retry.returncode, 4)
        self.assertIn("prior intent", retry.stderr)

    def test_corrupt_receipt_is_invalid_evidence(self):
        state = self.seed(1)
        receipt = next((state / "receipts").glob("*.json"))
        receipt.chmod(0o600)
        receipt.write_text("{}\n")
        receipt.chmod(0o400)
        result = self.status(state)
        self.assertIn("BASELINE_VERIFIED=INVALID_EVIDENCE", result.stdout)
        self.assertIn("retry_allowed=false", result.stdout)
        self.assertIn("next_stage=NONE", result.stdout)

    def test_incomplete_attempt_requires_reconciliation(self):
        state = self.seed()
        attempt = state / 'attempts' / 'baseline.interrupted'
        attempt.mkdir(parents=True, mode=0o700)
        attempt.parent.chmod(0o700)
        (attempt / 'started.proof').write_text('phase=READ_ONLY_PRECHECK\ndispatch=NOT_DISPATCHED\n')
        result = self.status(state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('BASELINE_VERIFIED=RECONCILIATION_REQUIRED', result.stdout)
        self.assertIn('next_stage=NONE', result.stdout)
        self.assertNotIn('EXECUTE_NEXT', result.stdout)

    def test_corrupt_attempt_is_invalid(self):
        state = self.seed()
        attempt = state / 'attempts' / 'baseline.corrupt'
        attempt.mkdir(parents=True, mode=0o700)
        attempt.parent.chmod(0o700)
        result_file = attempt / 'result.json'
        result_file.write_text('{}\n')
        result_file.chmod(0o400)
        result = self.status(state)
        self.assertIn('BASELINE_VERIFIED=INVALID_EVIDENCE', result.stdout)
        self.assertIn('next_stage=NONE', result.stdout)
        self.assertNotIn('EXECUTE_NEXT', result.stdout)

    def test_corrupt_intent_is_invalid(self):
        state = self.seed()
        intent = state / 'intents' / '01-BASELINE_VERIFIED.json'
        result = self.run_shell('source "$1"; load_state "$2"; write_stage_intent BASELINE_VERIFIED', SCRIPT, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        intent = next((state / 'intents').glob('*.json'))
        intent.chmod(0o600)
        intent.write_text('{}\n')
        intent.chmod(0o400)
        result = self.status(state)
        self.assertIn('BASELINE_VERIFIED=INVALID_EVIDENCE', result.stdout)
        self.assertNotIn('EXECUTE_NEXT', result.stdout)

    def test_orphan_terminal_checksum_is_invalid_before_next_action(self):
        state = self.seed()
        checksum = state / 'run-terminal.json.sha256'
        checksum.write_text('a' * 64 + '\n')
        checksum.chmod(0o400)
        result = self.status(state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('canonical_execution=INVALID_EVIDENCE', result.stdout)
        self.assertIn('next_stage=NONE', result.stdout)
        self.assertIn('retry_allowed=false', result.stdout)
        self.assertNotIn('EXECUTE_NEXT', result.stdout)

    def test_recovery_intent_before_terminal_requires_reconciliation(self):
        state = self.seed()
        result = self.run_shell('source "$1"; load_state "$2"; write_recovery_intent_and_terminal pre-v126 "$(hash_text token)" NONE NONE', SCRIPT, state)
        self.assertEqual(result.returncode, 0, result.stderr)
        # Reconstruct the immutable prefix present after the first create-only write.
        (state / 'run-terminal.json').unlink()
        (state / 'run-terminal.json.sha256').unlink()
        (state / 'recovery' / 'pre-v126.intent.json.sha256').unlink()
        result = self.status(state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('canonical_execution=RECONCILIATION_REQUIRED', result.stdout)
        self.assertIn('next_stage=NONE', result.stdout)
        self.assertNotIn('EXECUTE_NEXT', result.stdout)


if __name__ == "__main__":
    unittest.main()
