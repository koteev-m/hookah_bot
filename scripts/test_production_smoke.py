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

    def run_probe(self, tags, js_type="text/javascript", js_data=b"export default 1;" * 4, trusted_js=None, trusted_css=None, content_range=None):
        routes = {
            "/health": (200, "application/json", b'{"status":"ok"}'),
            "/db/health": (200, "application/json", b'{"status":"ok"}'),
            "/version": (200, "application/json", json.dumps(dict(service="backend", env="prod", version=self.sha)).encode()),
            "/miniapp/": (200, "text/html", ("<html><head>" + tags + "</head></html>").encode()),
            "/miniapp/assets/app.js": (200, js_type, js_data),
            "/miniapp/assets/app.css": (200, "text/css", b".test {color:blue}" * 4),
            "/api/guest/catalog": (401, "application/json", b"{}"),
        }

        def reply(url, timeout):
            status, kind, body = routes[url.removeprefix("http://127.0.0.1:18080")]
            result = io.BytesIO(body)
            result.status = status
            result.headers = Message()
            result.headers["Content-Type"] = kind
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
            smoke.probe(self.sha, expected)

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
