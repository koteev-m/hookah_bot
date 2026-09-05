#!/usr/bin/env python3
"""Exercise the exact embedded header verifier; all HTTP traffic stays on loopback."""
import http.server
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import threading
import time
import unittest


ROOT = Path(__file__).resolve().parent
SOURCE = (ROOT / "v126-staging-prerequisite-sync.sh").read_text()
FUNCTION = SOURCE.split("# HT12U_HEALTH_HEADERS_BEGIN\n", 1)[1].split(
    "# HT12U_HEALTH_HEADERS_END", 1
)[0]
CURL = shutil.which("curl")
GOOD = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\nHT12U_STATUS=200"
SENTINEL = "PRIVATE_BODY_OR_HEADER_SENTINEL"


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.server.calls.append(("HEAD", self.path))
        self.send_response(405)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self.server.calls.append(("GET", self.path))
        if self.path != "/health":
            self.send_error(404)
            return
        scenario = self.server.scenario
        if scenario == "timeout":
            time.sleep(0.5)
        status = int(scenario) if scenario in ("405", "500", "302", "204") else 200
        body = b'{"status":"ok"}'
        if scenario == "private-body":
            body = SENTINEL.encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body) + (10 if scenario == "partial" else 0)))
        self.send_header("Set-Cookie", SENTINEL)
        self.send_header("Authorization", SENTINEL)
        if scenario == "302":
            self.send_header("Location", "/unexpected")
        if scenario in ("Alt-Svc", "aLt-SvC", "alt-svc"):
            self.send_header(scenario, SENTINEL)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass
        self.close_connection = True


class HealthHeadersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.daemon_threads = True
        cls.server.calls = []
        cls.server.scenario = "ok"
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.server.calls.clear()
        self.server.scenario = "ok"

    def probe(self, scenario="ok", *, policy="absent", raw=None, exit_status=0, entry=None):
        self.server.scenario = scenario
        # Validate the production argv before changing only transport to loopback.
        expected = "--disable --silent --fail --connect-timeout 5 --max-time 15 --proto =https --suppress-connect-headers --request GET --dump-header - --output /dev/null --write-out HT12U_STATUS=%{http_code} https://staging.hookahtootah.club/health"
        if raw is not None:
            producer = f"printf '%s' {shlex.quote(raw)}; return {exit_status}"
        else:
            port = self.server.server_port
            if scenario == "connection":
                # Reserve an unlistening port while curl runs: no accidental external service.
                reserved = socket.socket()
                reserved.bind(("127.0.0.1", 0))
                port = reserved.getsockname()[1]
            producer = f"""
  local args=() arg
  for arg in "$@"; do
    case "$arg" in
      '=https') args+=('=http') ;;
      'https://staging.hookahtootah.club/health') args+=('http://127.0.0.1:{port}/health') ;;
      *) args+=("$arg") ;;
    esac
  done
  command {shlex.quote(CURL)} "${{args[@]}}" --max-time 0.2 --noproxy '*'
"""
        wrapper = f"""
curl() {{
  [[ "$*" == {shlex.quote(expected)} ]] || return 99
  {producer}
}}
remote_die() {{ printf '%s\\n' "$1" >&2; return 1; }}
REMOTE_DOMAIN=staging.hookahtootah.club
"""
        checks = "\n".join(re.findall(r"^check_(?:32|34)\(\).*", SOURCE, re.M))
        script = "set -euo pipefail\n" + wrapper + FUNCTION + checks + "\n"
        # Conditional invocation intentionally disables errexit: producer status must propagate.
        script += f"if {entry or ('require_public_health_headers ' + policy)}; then printf 'PROBE_PASS'; else exit 1; fi\n"
        result = subprocess.run(["bash", "-c", script], capture_output=True, timeout=5)
        if scenario == "connection":
            reserved.close()
        self.assertNotIn(SENTINEL.encode(), result.stdout + result.stderr)
        if result.returncode:
            self.assertNotIn(b"PROBE_PASS", result.stdout)
            self.assertNotIn(b"PUBLIC_HEADERS=CAPTURED", result.stdout)
            self.assertNotIn(b"ALT_SVC=ABSENT", result.stdout)
        return result

    def test_real_server_before_after(self):
        url = f"http://127.0.0.1:{self.server.server_port}/health"
        old = subprocess.run([CURL, "--disable", "-fsSI", "--noproxy", "*", url], capture_output=True)
        self.assertEqual(old.returncode, 22)
        self.assertIn(b"405", old.stdout + old.stderr)
        self.assertEqual(self.probe().returncode, 0)
        self.assertEqual(self.server.calls, [("HEAD", "/health"), ("GET", "/health")])

    def test_alt_svc_case_insensitive(self):
        for name in ("Alt-Svc", "aLt-SvC", "alt-svc"):
            with self.subTest(name=name):
                self.assertNotEqual(self.probe(name).returncode, 0)

    def test_http_and_transport_failures(self):
        for scenario in ("405", "500", "302", "204", "partial", "timeout", "connection"):
            with self.subTest(scenario=scenario):
                self.assertNotEqual(self.probe(scenario).returncode, 0)
        self.assertNotIn(("GET", "/unexpected"), self.server.calls)

    def test_nonzero_producer_with_exact_looking_output(self):
        for status in (7, 18, 22, 28, 60):
            for entry in (None, "check_32", "check_34"):
                with self.subTest(status=status, entry=entry):
                    self.assertNotEqual(self.probe(raw=GOOD, exit_status=status, entry=entry).returncode, 0)

    def test_empty_or_malformed_headers(self):
        cases = ["", "HT12U_STATUS=200", "HTTP/2 200\r\n\r\nHT12U_STATUS=200",
                 GOOD.replace("\r\n", "\n"), GOOD.replace("\r\n\r\n", "\r\n"),
                 GOOD.replace("200 OK", "405 Not Allowed"), GOOD.replace("STATUS=200", "STATUS=302"),
                 GOOD.replace("Content-Type:", " Content-Type:"), GOOD.replace("Content-Type:", "Content-Type"),
                 GOOD.replace("Content-Type:", "Alt-Svc :"), GOOD.replace("application/json", "bad\x01value"),
                 GOOD + "garbage", "HTTP/2 200\r\nX-Large: " + "a" * 66000 + "\r\n\r\nHT12U_STATUS=200"]
        for raw in cases:
            with self.subTest(raw=raw[:70]):
                self.assertNotEqual(self.probe(raw=raw).returncode, 0)

    def test_interim_response_and_final_status(self):
        self.assertEqual(self.probe(raw="HTTP/1.1 100 Continue\r\n\r\n" + GOOD).returncode, 0)
        self.assertNotEqual(self.probe(raw="HTTP/1.1 302 Found\r\n\r\n" + GOOD).returncode, 0)

    def test_privacy_and_named_checks(self):
        for entry, token in (("check_32", b"PUBLIC_HEADERS=CAPTURED"), ("check_34", b"ALT_SVC=ABSENT")):
            result = self.probe("private-body", entry=entry)
            self.assertEqual(result.returncode, 0)
            self.assertIn(token, result.stdout)

    def test_callsite_parity_and_scope(self):
        baseline = SOURCE.split("baseline_full() {", 1)[1].split("\n}\n", 1)[0]
        self.assertIn("require_public_health_headers absent || return $?", baseline)
        recovery = SOURCE.split("baseline_full || return $?")
        self.assertGreaterEqual(len(recovery), 3)
        self.assertIn("require_health_body", baseline)
        self.assertIn("require_tls_profile", baseline)
        self.assertIn("ss -H -lun", baseline)
        self.assertNotIn('curl -fsSI "https://${REMOTE_DOMAIN}/health"', SOURCE)
        cutover = (ROOT / "v126-cutover.sh").read_text()
        self.assertNotRegex(cutover, r"curl[^\n]*(?:-fsSI|--head)[^\n]*/health")


if __name__ == "__main__":
    unittest.main(verbosity=2)
