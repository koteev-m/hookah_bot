#!/usr/bin/env python3
"""Exact production observers, real loopback HTTP/curl, synthetic Docker inventory only."""
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
import unittest


ROOT = Path(__file__).resolve().parent
SOURCE = (ROOT / "v126-cutover.sh").read_text()
CURL = shutil.which("curl")
PYTHON = shutil.which("python3")
CONTAINER = "a" * 64
IMAGE = "sha256:" + "b" * 64
RELEASE = "c" * 40
SECRET = "SYNTHETIC_PRIVATE_PAYLOAD_MUST_NOT_LEAK"


def function(name):
    start = SOURCE.index(name + "() {\n")
    body_start = SOURCE.index("\n", start) + 1
    match = re.search(r"^\w+\(\) \{", SOURCE[body_start:], re.M)
    return SOURCE[start:body_start + match.start()] if match else SOURCE[start:]


FUNCTIONS = function("cutover_bounded_command") + function("remote_wait_backend_ready")
FUNCTIONS_SHA256 = hashlib.sha256(FUNCTIONS.encode()).hexdigest()
# Shorten only timing constants, never a production predicate, guard or consumer.
for old, new in (("TOTAL_SECONDS = 120.0", "TOTAL_SECONDS = 5.0"),
                 ("REQUEST_SECONDS = 5.0", "REQUEST_SECONDS = 1.0"),
                 ("POLL_SECONDS = 1.0", "POLL_SECONDS = 0.03")):
    assert FUNCTIONS.count(old) == 1, old
    FUNCTIONS = FUNCTIONS.replace(old, new)


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.server.calls.append(self.path)
        case = self.server.scenario
        if case == "hung":
            self.server.release.wait(3)
            return
        if case == "delayed" and len(self.server.calls) <= 2:
            status, body = 503, SECRET
        elif case == "never":
            status, body = 503, SECRET
        elif case == "redirect":
            status, body = 302, SECRET
        elif case == "large":
            status, body = 200, SECRET * 1000
        elif case == "malformed":
            status, body = 200, SECRET
        elif self.path == "/version":
            status, body = 200, json.dumps({"service": "backend", "env": "staging",
                                           "version": "d" * 40 if case == "wrong_version" else RELEASE})
        elif case == "wrong_health":
            status, body = 200, json.dumps({"status": SECRET})
        else:
            status, body = 200, '{"status":"ok"}'
        self.send_response(status)
        self.send_header("Content-Length", str(len(body.encode())))
        self.end_headers()
        try:
            self.wfile.write(body.encode())
        except (BrokenPipeError, ConnectionResetError):
            pass


class ReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="v126-readiness-")
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler, bind_and_activate=False)
        self.server.server_bind()
        self.server.daemon_threads = True
        self.server.scenario = "ready"
        self.server.release = threading.Event()
        self.server.calls = []
        self.thread = None
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        FIXTURE_ROOT=str(self.root), REAL_CURL=CURL, TMPDIR=str(self.root),
                        FIXTURE_PORT=str(self.server.server_address[1]))
        self.write_executable("docker", r'''
import json, os, pathlib, sys, time
root = pathlib.Path(os.environ["FIXTURE_ROOT"])
with (root / "commands").open("a") as handle:
    handle.write(json.dumps(sys.argv[1:]) + "\n")
fixture = json.loads((root / "docker.json").read_text())
args = sys.argv[1:]
if args == ["start", "a" * 64]:
    with (root / "starts").open("a") as handle:
        handle.write("start\n")
    raise SystemExit(fixture.get("start_exit", 0))
if args == ["inspect", "a" * 64]:
    count_path = root / "inspections"
    count = int(count_path.read_text()) + 1 if count_path.exists() else 1
    count_path.write_text(str(count))
    if fixture.get("inspect_hang"):
        time.sleep(10)
    if fixture.get("inspect_exit"):
        print(json.dumps([fixture["row"]]))
        raise SystemExit(42)
    row = fixture["row"]
    if fixture.get("exit_after") and count >= fixture["exit_after"]:
        row["State"].update(Status="exited", Running=False)
    if fixture.get("drift_after") and count >= fixture["drift_after"]:
        row["Image"] = "sha256:" + "e" * 64
    if fixture.get("env_drift_after") and count >= fixture["env_drift_after"]:
        row["Config"]["Env"].append("TELEGRAM_BOT_ENABLED=false")
    print(json.dumps([row]))
elif args[:4] == ["ps", "--all", "--quiet", "--no-trunc"]:
    if fixture.get("inventory_exit"):
        print("a" * 64)
        raise SystemExit(42)
    assert args[4:] == ["--filter", "label=com.docker.compose.project=owned-test",
                        "--filter", "label=com.docker.compose.service=backend"], args
    print("\n".join(fixture.get("ids", ["a" * 64])))
else:
    raise SystemExit(98)
''')
        self.write_executable("curl", r'''
import os, pathlib, subprocess, sys
args = sys.argv[1:]
assert args[:6] == ["--disable", "--silent", "--noproxy", "*", "--proto", "=http"]
assert "--connect-timeout" in args and "--max-time" in args and "--max-filesize" in args
assert args[-1].startswith("http://127.0.0.1:8080/")
(pathlib.Path(os.environ["FIXTURE_ROOT"]) / "curl-invoked").touch()
args[-1] = args[-1].replace(":8080/", ":" + os.environ["FIXTURE_PORT"] + "/", 1)
if os.environ.get("FIXTURE_CURL_EXIT"):
    result = subprocess.run([os.environ["REAL_CURL"]] + args)
    (pathlib.Path(os.environ["FIXTURE_ROOT"]) / "curl-completed").touch()
    raise SystemExit(int(os.environ.get("FIXTURE_CURL_EXIT", result.returncode)))
os.execv(os.environ["REAL_CURL"], [os.environ["REAL_CURL"]] + args)
''')

    def tearDown(self):
        self.server.release.set()
        if self.thread:
            self.server.shutdown()
            self.thread.join()
        self.server.server_close()
        self.temp.cleanup()

    def write_executable(self, name, code):
        target = self.bin / name
        target.write_text("#!" + PYTHON + "\n" + code)
        target.chmod(0o700)

    def serve(self, delay=0):
        def run():
            if delay:
                until = time.monotonic() + 5
                while not (self.root / "curl-invoked").exists() and time.monotonic() < until:
                    time.sleep(0.01)
                time.sleep(delay)
            self.server.server_activate()
            self.server.serve_forever(poll_interval=0.02)
        self.thread = threading.Thread(target=run, daemon=True)
        self.thread.start()

    def prepare(self, phase="first", **changes):
        values = {"TELEGRAM_BOT_ENABLED": "true", "TELEGRAM_BOT_MODE": "long_polling",
                  "TELEGRAM_TRAFFIC_POLICY": "PRODUCT", "TELEGRAM_ALLOWED_USER_IDS": "",
                  "TELEGRAM_ALLOWED_CHAT_IDS": "", "STAGING_MAINTENANCE_MODE": "OFF",
                  "STAGING_MAINTENANCE_ALLOWED_USER_IDS": "", "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS": ""}
        if phase == "first":
            values.update(STAGING_MAINTENANCE_MODE="V126_SMOKE",
                          STAGING_MAINTENANCE_ALLOWED_USER_IDS="1", STAGING_MAINTENANCE_ALLOWED_CHAT_IDS="1")
        payload = "".join(key + "=" + value + "\n" for key, value in values.items())
        (self.root / ".env").write_text(payload)
        self.env_sha = hashlib.sha256(payload.encode()).hexdigest()
        self.fixture = {"row": {"Id": CONTAINER, "Image": IMAGE, "RestartCount": 0,
                              "HostConfig": {"RestartPolicy": {"Name": "no"}},
                              "Config": {"Labels": {"com.docker.compose.project": "owned-test",
                                                    "com.docker.compose.service": "backend"},
                                         "Env": [key + "=" + value for key, value in values.items()]},
                              "State": {"Status": "running", "Running": True, "Paused": False,
                                        "OOMKilled": False}}}
        self.fixture.update(changes)
        self.phase = phase

    def observe(self, *, start=False):
        (self.root / "docker.json").write_text(json.dumps(self.fixture))
        call = "remote_wait_backend_ready " + " ".join(map(shlex.quote,
                   (CONTAINER, IMAGE, RELEASE, self.phase, self.env_sha)))
        prefix = f'docker start {CONTAINER} >/dev/null || exit $?\n' if start else ""
        # Command substitution + conditional deliberately disable errexit in the caller.
        script = "set -euo pipefail\n" + FUNCTIONS + "\n" + prefix
        script += 'if output="$(' + call + ')"; then printf "OBSERVER_PASS\\n"; else exit $?; fi\n'
        before = time.monotonic()
        result = subprocess.run(["bash", "-c", script], cwd=self.root, env=self.env,
                                capture_output=True, text=True, timeout=10)
        result.elapsed = time.monotonic() - before
        self.assertNotIn(SECRET, result.stdout + result.stderr)
        if result.returncode:
            self.assertNotIn("OBSERVER_PASS", result.stdout)
        return result

    def test_ready_positive_all_three_call_phases(self):
        self.serve()
        for phase in ("first", "final", "pre-v126"):
            with self.subTest(phase=phase):
                self.prepare(phase)
                result = self.observe()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("READINESS=READY", result.stderr)
        self.assertEqual(self.server.calls, ["/health", "/db/health", "/version"] * 3)

    def test_503_then_200_same_container_single_start(self):
        self.server.scenario = "delayed"
        self.serve()
        self.prepare()
        result = self.observe(start=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("READINESS=STARTING", result.stderr)
        self.assertEqual((self.root / "starts").read_text().splitlines(), ["start"])
        self.assertGreaterEqual(self.server.calls.count("/health"), 3)

    def test_delayed_listener_single_start(self):
        self.serve(delay=1.3)
        self.prepare("pre-v126")
        result = self.observe(start=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("READINESS=STARTING", result.stderr)
        self.assertEqual((self.root / "starts").read_text().splitlines(), ["start"])

    def test_never_ready_is_unknown_and_nonretryable(self):
        self.server.scenario = "never"
        self.serve()
        self.prepare()
        result = self.observe(start=True)
        self.assertEqual(result.returncode, 75, result.stderr)
        self.assertIn("READINESS=UNKNOWN", result.stderr)
        self.assertIn("retry_allowed=false", result.stderr)
        self.assertLess(result.elapsed, 7.0)
        self.assertEqual((self.root / "starts").read_text().splitlines(), ["start"])

    def test_hung_http_is_bounded_and_unknown(self):
        self.server.scenario = "hung"
        self.serve()
        self.prepare()
        result = self.observe()
        self.assertEqual(result.returncode, 75, result.stderr)
        self.assertTrue(self.server.calls)
        self.assertLess(result.elapsed, 7.0)

    def test_wrong_health_version_status_or_body_is_final_failure(self):
        self.serve()
        for scenario in ("wrong_health", "wrong_version", "redirect", "large", "malformed"):
            with self.subTest(scenario=scenario):
                self.prepare()
                self.server.scenario = scenario
                result = self.observe()
                self.assertEqual(result.returncode, 4, result.stderr)
                self.assertIn("READINESS=FAILED", result.stderr)

    def test_wrong_identity_restart_and_scope_fail_before_http(self):
        self.serve()
        for scenario in ("image", "restart", "count", "env", "exited"):
            with self.subTest(scenario=scenario):
                self.prepare()
                if scenario == "image":
                    self.fixture["row"]["Image"] = "sha256:" + "d" * 64
                elif scenario == "restart":
                    self.fixture["row"]["RestartCount"] = 1
                elif scenario == "count":
                    self.fixture["ids"] = [CONTAINER, "e" * 64]
                elif scenario == "env":
                    self.fixture["row"]["Config"]["Env"].append("TELEGRAM_BOT_ENABLED=false")
                else:
                    self.fixture["row"]["State"].update(Status="exited", Running=False)
                result = self.observe()
                self.assertEqual(result.returncode, 4, result.stderr)
        self.assertFalse(self.server.calls)

    def test_bound_file_drift_fails_before_http(self):
        self.serve()
        self.prepare()
        with (self.root / ".env").open("a") as handle:
            handle.write("# changed\n")
        result = self.observe()
        self.assertEqual(result.returncode, 4)
        self.assertIn("bound_environment_changed", result.stderr)
        self.assertFalse(self.server.calls)

    def test_exit_and_identity_drift_after_http_reject_completion(self):
        self.serve()
        for change in ("exit_after", "drift_after", "env_drift_after"):
            with self.subTest(change=change):
                (self.root / "inspections").unlink(missing_ok=True)
                self.prepare(**{change: 2})
                result = self.observe()
                self.assertEqual(result.returncode, 4, result.stderr)

    def test_docker_hang_and_nonzero_are_unknown(self):
        self.serve()
        for change in ("inspect_hang", "inspect_exit", "inventory_exit"):
            with self.subTest(change=change):
                self.prepare(**{change: True})
                result = self.observe()
                self.assertEqual(result.returncode, 75, result.stderr)
                self.assertLess(result.elapsed, 2.5)
        self.assertFalse(self.server.calls)

    def test_nonzero_start_cannot_reach_observer(self):
        self.serve()
        self.prepare(start_exit=42)
        result = self.observe(start=True)
        self.assertEqual(result.returncode, 42)
        self.assertFalse(self.server.calls)
        self.assertEqual((self.root / "starts").read_text().splitlines(), ["start"])

    def test_health_looking_stdout_with_nonzero_consumer_is_rejected(self):
        self.serve()
        self.prepare()
        self.env["FIXTURE_CURL_EXIT"] = "42"
        result = self.observe()
        self.assertEqual(result.returncode, 4, result.stderr)
        self.assertEqual(self.server.calls, ["/health"])

    def test_bounded_command_stdin_and_nonzero_under_substitution(self):
        script = function("cutover_bounded_command") + "\n"
        script += "printf synthetic-input | cutover_bounded_command 1 cat\n"
        script += 'if result="$(cutover_bounded_command 1 sh -c \'printf plausible-PASS; exit 42\')"; then exit 99; else exit $?; fi\n'
        result = subprocess.run(["bash", "-c", script], cwd=self.root, env=self.env,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 42, result.stderr)
        self.assertEqual(result.stdout, "synthetic-input")

    def test_bounded_mutation_timeout_preserves_unknown_side_effect(self):
        script = function("cutover_bounded_command") + "\n"
        script += 'if result="$(cutover_bounded_command 0.15 sh -c \'touch side-effect; sleep 10 & wait\')"; then exit 99; else exit $?; fi\n'
        before = time.monotonic()
        result = subprocess.run(["bash", "-c", script], cwd=self.root, env=self.env,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertLess(time.monotonic() - before, 1.5)
        self.assertTrue((self.root / "side-effect").exists())
        self.assertIn("IO_OUTCOME=UNKNOWN", result.stderr)
        self.assertIn("retry_allowed=false", result.stderr)
        self.assertNotIn("NOT_DISPATCHED", result.stderr)

    def test_completed_parent_cannot_leave_pipe_holding_child(self):
        script = function("cutover_bounded_command") + "\n"
        script += 'if result="$(cutover_bounded_command 1 sh -c \'sleep 10 & exit 0\')"; then exit 99; else exit $?; fi\n'
        before = time.monotonic()
        result = subprocess.run(["bash", "-c", script], cwd=self.root, env=self.env,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertLess(time.monotonic() - before, 1.5)
        self.assertIn("IO_OUTCOME=UNKNOWN reason=child_survived", result.stderr)


if __name__ == "__main__":
    print("READINESS_TEST_SOURCE_SHA256=" + hashlib.sha256(SOURCE.encode()).hexdigest(), flush=True)
    print("READINESS_TEST_FUNCTIONS_SHA256=" + FUNCTIONS_SHA256, flush=True)
    unittest.main()
