import importlib.util
import io
import json
from email.message import Message
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("pilot_smoke", Path(__file__).resolve().parents[1] / "deploy/production/smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeTest(unittest.TestCase):
    js = '<script type="module" src="/miniapp/assets/app.js"></script>'
    css = '<link rel="stylesheet" href="/miniapp/assets/app.css">'
    sha = "a" * 40

    def run_probe(self, tags, js_type="text/javascript", js_data=b"export default 1;" * 4, trusted_js=None, trusted_css=None,
                  content_range=None, recovery=False, api_status=401, maintenance=None, polling=None,
                  asset_status=200, readiness=False, before_asset=None):
        routes = {
            "/health": (200, "application/json", b'{"status":"ok"}'),
            "/db/health": (200, "application/json", b'{"status":"ok"}'),
            "/version": (200, "application/json", json.dumps(dict(service="backend", env="prod", version=self.sha, polling=polling,
                        instance="00000000-0000-4000-8000-000000000001")).encode()),
            "/miniapp/": (200, "text/html", ("<html><head>" + tags + "</head></html>").encode()),
            "/miniapp/assets/app.js": (asset_status, js_type, js_data),
            "/miniapp/assets/app.css": (200, "text/css", b".test {color:blue}" * 4),
            "/api/guest/catalog": (api_status, "application/json", b"{}"),
        }

        def reply(url, timeout):
            if recovery:
                self.assertEqual(url.get_header("X-hookah-recovery-probe"), "1")
                url = url.full_url
            status, kind, body = routes[url.removeprefix("http://127.0.0.1:18080")]
            if before_asset and url.endswith("app.js"):
                before_asset()
            result = io.BytesIO(body)
            result.status = status
            result.headers = Message()
            result.headers["Content-Type"] = kind
            if maintenance:
                result.headers["X-Hookah-Maintenance"] = maintenance
            if content_range and url.endswith(".js"):
                result.headers["Content-Range"] = content_range
            return result

        with patch.object(smoke.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = reply
            expected = {path: smoke.fingerprint(data) for path, (_, _, data) in routes.items() if path.startswith("/miniapp/")}
            if trusted_js is not None:
                expected["/miniapp/assets/app.js"] = smoke.fingerprint(trusted_js)
            if trusted_css is not None:
                expected["/miniapp/assets/app.css"] = smoke.fingerprint(trusted_css)
            if readiness:
                import production_traffic as traffic
                return traffic.await_readiness(self.sha, expected, polling="empty", recovery=True)
            return smoke.probe(self.sha, expected, recovery=recovery)

    def test_confirmed_backlog_survives_js_failure_and_later_empty_read(self):
        import tempfile
        import production_traffic as traffic
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as folder, patch.object(traffic, "GATE", Path(folder) / "open"):
            latch = Path(folder) / "telegram-backlog"
            with self.assertRaisesRegex(traffic.Refused, "Telegram backlog"):
                self.run_probe(self.js+self.css, recovery=True, readiness=True, polling="backlog", asset_status=503,
                               before_asset=lambda: self.assertTrue(latch.is_file()))
            challenge = latch.read_bytes()
            self.assertEqual(latch.stat().st_mode & 0o777, 0o600)
            empty = self.run_probe(self.js+self.css, recovery=True, polling="empty", api_status=503, maintenance="closed")
            self.assertEqual(empty["polling"], "empty")
            self.assertEqual(challenge, latch.read_bytes())
            db = {"container": "pg"}
            (Path(folder) / "recovery-accepted").write_text(json.dumps({"target": db}))
            with patch.object(traffic, "cluster_identity", return_value=db), patch.object(traffic, "check_edge_closed"), \
                    patch.object(traffic, "check_gate_binary"), patch.object(traffic, "verified_database"), \
                    patch.object(traffic, "verify_running"), patch.object(traffic, "image_assets"), \
                    patch.object(traffic, "backend", return_value={"environment": {"APP_VERSION": self.sha, "TELEGRAM_BOT_ENABLED": "true"}}), \
                    self.assertRaisesRegex(traffic.Refused, "owner reconciliation"):
                traffic.operate(Namespace(action="open", plan=Path(folder) / "plan"))
            self.assertFalse(traffic.GATE.exists() or traffic.GATE.with_name("activate").exists())

    def test_recovery_probe_requires_explicitly_closed_application_api(self):
        self.run_probe(self.js + self.css, recovery=True, api_status=503, maintenance="closed")
        for status in (200, 401, 503):
            with self.subTest(status=status), self.assertRaises(ValueError):
                self.run_probe(self.js + self.css, recovery=True, api_status=status)

    def test_first_install_waits_for_tls_and_worker_before_signed_owner_smoke(self):
        import tempfile
        import production_traffic as traffic

        doc = (traffic.PROFILE.parents[1] / "docs/PRODUCTION_PILOT.md").read_text().split("## Первая установка и проверка", 1)[1]
        steps = ["curl --noproxy", "--wait --wait-timeout", 'running "$plan"', 'bind-db "$plan"',
                 '--owner-init-data "$P/owner-init-data"', 'open "$plan"']
        self.assertEqual(sorted(doc.index(step) for step in steps), [doc.index(step) for step in steps])
        with tempfile.TemporaryDirectory() as folder, patch.object(traffic, "check_edge_closed") as edge, \
                patch.object(smoke.urllib.request, "build_opener") as build:
            plan, source = Path(folder) / "plan", Path(folder) / "credential"
            plan.write_text(json.dumps({"services": {"backend": {"environment": {
                "TELEGRAM_BOT_ENABLED": "true", "PLATFORM_OWNER_TELEGRAM_ID": "42"}}}}))
            source.write_text("synthetic-signed-launch")
            source.chmod(0o600)
            def response(req, timeout):
                self.assertEqual(req.full_url, "http://127.0.0.1:18080/api/auth/telegram")
                self.assertEqual(json.loads(req.data), {"initData": "synthetic-signed-launch"})
                body = io.BytesIO(json.dumps({"token": "fixture", "user": {"telegramUserId": owner}}).encode())
                body.status = 200
                return body
            build.return_value.open.side_effect = response
            owner = 43
            with self.assertRaises(ValueError):
                smoke.signed_auth(plan, source)
            owner = 42
            edge.reset_mock()
            smoke.signed_auth(plan, source)
            self.assertEqual(edge.call_count, 2)

    def test_browser_loadable_assets(self):
        self.run_probe(self.js + self.css)

    def test_truncated_module(self):
        with self.assertRaises(ValueError):
            self.run_probe(self.js + self.css, js_data=b"x")

    def test_missing_css(self):
        with self.assertRaises(ValueError):
            self.run_probe(self.js)

    def test_plain_text_module(self):
        with self.assertRaises(ValueError):
            self.run_probe(self.js + self.css, "text/plain")

    def test_html_masquerading_as_js(self):
        with self.assertRaises(ValueError):
            self.run_probe(self.js + self.css, js_data=b" <!DOCTYPE html><html>upstream fallback page</html>")

    def test_external_entry_is_rejected_even_alongside_valid_module(self):
        with self.assertRaises(ValueError):
            self.run_probe(self.js + self.css + '<script type="module" src="//other.invalid/app.js"></script>')


    def test_partial_module_with_valid_mime_and_plausible_length(self):
        full = b"export default '" + b"x" * 1120000 + b"';"
        self.run_probe(self.js + self.css, js_data=full, trusted_js=full)
        with self.assertRaisesRegex(ValueError, "admitted image"):
            self.run_probe(self.js + self.css, js_data=full[:4096], trusted_js=full)

    def test_wrong_hash_at_correct_length(self):
        with self.assertRaisesRegex(ValueError, "admitted image"):
            self.run_probe(self.js + self.css, js_data=b"a" * 6000, trusted_js=b"b" * 6000)

    def test_css_must_match_the_admitted_stylesheet(self):
        with self.assertRaisesRegex(ValueError, "admitted image"):
            self.run_probe(self.js + self.css, trusted_css=b".other {color:red}" * 4)

    def test_range_response_even_with_200_and_correct_bytes_is_refused(self):
        with self.assertRaisesRegex(ValueError, "admitted image"):
            self.run_probe(self.js + self.css, content_range="bytes 0-67/2048")

    def test_complete_module_larger_than_two_megabytes(self):
        full = b"export default '" + b"x" * (2 * 1024 * 1024) + b"';"
        self.run_probe(self.js + self.css, js_data=full, trusted_js=full)
