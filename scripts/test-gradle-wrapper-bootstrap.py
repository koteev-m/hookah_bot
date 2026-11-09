#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parent.parent / "backend/gradle-wrapper-bootstrap.sh"


class GradleWrapperBootstrapTest(unittest.TestCase):
    def run_case(self, responses, *, sleep_fails=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradlew").write_text('''#!/bin/sh
set -eu
[ "$*" = "--version --no-daemon" ] || exit 97
count=0
[ ! -f count ] || count=$(cat count)
count=$((count + 1))
echo "$count" > count
response=$(sed -n "${count}p" responses)
case "$response" in
  OK) echo "Gradle 8.14.3"; exit 0 ;;
  504|500|404) echo "Exception in thread \\"main\\" java.io.IOException: Server returned HTTP response code: $response for URL: https://example.invalid" >&2; exit 1 ;;
  FAKE) echo "unrelated HTTP response code: 504" >&2; exit 7 ;;
  *) echo "wrapper failed" >&2; exit 9 ;;
esac
''')
            (root / "gradlew").chmod(0o755)
            (root / "responses").write_text("\n".join(responses) + "\n")
            (root / "sleep").write_text(
                '#!/bin/sh\necho "$1" >> delays\n' + ('exit 8\n' if sleep_fails else 'exit 0\n')
            )
            (root / "sleep").chmod(0o755)
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", TMPDIR=directory)
            result = subprocess.run(["sh", str(SCRIPT)], cwd=root, env=env, capture_output=True, text=True)
            count = int((root / "count").read_text())
            delays = (root / "delays").read_text().splitlines() if (root / "delays").exists() else []
            self.assertFalse(list(root.glob("tmp.*")), "temporary log must be removed")
            return result, count, delays

    def test_504_then_success(self):
        result, count, delays = self.run_case(["504", "OK"])
        self.assertEqual((result.returncode, count, delays), (0, 2, ["2"]))
        self.assertIn("Gradle 8.14.3", result.stdout)

    def test_persistent_5xx_fails_closed(self):
        result, count, delays = self.run_case(["504", "500", "504"])
        self.assertEqual((result.returncode, count, delays), (1, 3, ["2", "4"]))

    def test_non_5xx_and_unrelated_errors_do_not_retry(self):
        for failure, code in [("404", 1), ("OTHER", 9), ("FAKE", 7)]:
            with self.subTest(failure=failure):
                result, count, delays = self.run_case([failure, "OK"])
                self.assertEqual((result.returncode, count, delays), (code, 1, []))

    def test_failed_backoff_fails_closed(self):
        result, count, delays = self.run_case(["504", "OK"], sleep_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((count, delays), (1, ["2"]))


if __name__ == "__main__":
    unittest.main()
