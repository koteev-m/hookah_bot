"""Probe the private production backend via loopback. Run on its Docker host."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from html.parser import HTMLParser
import re
import time
import urllib.error
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class EntryAssets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.assets = {}
        self.references = set()

    def handle_starttag(self, tag, attrs):
        fields = dict(attrs)
        for attr in ("src", "href"):
            ref = fields.get(attr) or ""
            if ref.startswith("/miniapp/assets/"):
                if not re.fullmatch(r"/miniapp/assets/[A-Za-z0-9_-][A-Za-z0-9_.-]*", ref):
                    raise ValueError("invalid Mini App asset URL")
                self.references.add(ref)
        if tag == "script" and fields.get("type") == "module":
            url, extension = fields.get("src"), "js"
        elif tag == "link" and "stylesheet" in (fields.get("rel") or "").lower().split():
            url, extension = fields.get("href"), "css"
        elif tag == "base":
            raise ValueError("unexpected Mini App base URL")
        else:
            return
        if not url or not re.fullmatch(r"/miniapp/assets/[A-Za-z0-9_-][A-Za-z0-9_.-]*\." + extension, url):
            raise ValueError("invalid Mini App entry asset URL")
        self.assets[url] = extension


def entry_assets(html):
    entry = EntryAssets()
    entry.feed(html.decode("utf-8-sig"))
    entry.close()
    if set(entry.assets.values()) != {"js", "css"}:
        raise ValueError("/miniapp/: module or stylesheet missing")
    return entry


def fingerprint(data):
    return len(data), hashlib.sha256(data).hexdigest()


def image_assets(plan):
    """Read the reference bytes from the admitted immutable image, independently of HTTP."""
    app = json.loads(plan.read_bytes())["services"]["backend"]
    ref = app["image"]
    if not re.fullmatch(r"ghcr\.io/koteev-m/hookah_bot/backend@sha256:[0-9a-f]{64}", ref):
        raise ValueError("expected a pinned production backend")
    run = ["docker", "run", "--rm", "--network", "none", "--pull", "never", "--platform", "linux/amd64",
           "--read-only", "--entrypoint", "cat", ref]
    html = subprocess.check_output(run + ["/app/miniapp/index.html"])
    manifest = {"/miniapp/": fingerprint(html)}
    for url in entry_assets(html).assets:
        manifest[url] = fingerprint(subprocess.check_output(run + ["/app" + url]))
    return manifest


def probe(sha, manifest):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def get(path, status=200):
        try:
            response = opener.open("http://127.0.0.1:18080" + path, timeout=4)
        except urllib.error.HTTPError as exc:
            response = exc
        with response:
            if response.status != status:
                raise ValueError(f"{path}: HTTP {response.status}")
            expected = manifest.get(path)
            body = response.read(expected[0] + 1 if expected else 2 * 1024 * 1024)
            length = response.headers.get("Content-Length")
            if expected and (response.headers.get("Content-Range") is not None
                             or (length is not None and length != str(expected[0])) or fingerprint(body) != expected):
                raise ValueError(f"{path}: incomplete or different from admitted image")
            return body, response.headers.get_content_type()
    for path in ("/health", "/db/health"):
        if json.loads(get(path)[0]).get("status") != "ok":
            raise ValueError(f"{path}: unhealthy")
    ver = json.loads(get("/version")[0])
    if (ver.get("service"), ver.get("env"), ver.get("version")) != ("backend", "prod", sha):
        raise ValueError("/version: identity mismatch")
    html, kind = get("/miniapp/")
    entry = entry_assets(html)
    if kind != "text/html" or set(manifest) != set(entry.assets) | {"/miniapp/"}:
        raise ValueError("entry assets differ from admitted image")
    for path in sorted(entry.references):
        data, kind = get(path)
        if len(data) < 32 or kind == "text/html":
            raise ValueError(f"{path}: Mini App asset unavailable")
        extension = entry.assets.get(path)
        if extension:
            allowed = {"application/javascript", "text/javascript"} if extension == "js" else {"text/css"}
            body = data.removeprefix(b"\xef\xbb\xbf").lstrip()
            if kind not in allowed or not body or body.startswith(b"<"):
                raise ValueError(f"{path}: invalid {extension} response")
    get("/api/guest/catalog", status=401)
    return ver


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan", type=Path, help="admitted Compose JSON")
    args = parser.parse_args()
    sha = json.loads(args.plan.read_bytes())["services"]["backend"]["environment"]["APP_VERSION"]
    if not re.fullmatch("[0-9a-f]{40}", sha):
        parser.error("expected full release SHA")
    manifest = image_assets(args.plan)
    deadline = time.monotonic() + 120
    while True:
        try:
            probe(sha, manifest)
            print("OK health, DB, version, Mini App assets, unauthenticated API")
            break
        except (ValueError, OSError) as exc:
            if time.monotonic() >= deadline:
                raise SystemExit(f"Readiness failed: {exc}") from None
            time.sleep(2)
