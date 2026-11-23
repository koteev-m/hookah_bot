"""Offline first-install and compatible rollback rehearsal, using disposable local Docker resources."""
import argparse
import hashlib
import hmac
import io
import json
import os
import re
from pathlib import Path
import subprocess
import sys
from contextlib import ExitStack
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from unittest.mock import patch

import production_profile as profile
import production_traffic as traffic
import media_backup
from smoke import NoRedirect


def command(*args, **options):
    return subprocess.check_output(args, **options)


def require_isolated_daemon(project=None):
    if os.getenv("DOCKER_HOST") != "unix:///var/run/docker.sock" or os.getenv("DOCKER_CONTEXT"):
        raise ValueError("rehearsal needs an explicitly selected local system Docker daemon")
    if os.path.lexists(profile.host.GATE.parent):
        raise ValueError("production decision directory present; use an isolated rehearsal host")
    for cmd in (("ps", "-aq"), ("volume", "ls", "-q"), ("network", "ls", "--filter", "type=custom", "-q")):
        ids = set(command("docker", *cmd).split())
        owned = set(command("docker", *cmd, "--filter", "label=com.docker.compose.project=" + project).split()) if project else set()
        if ids != owned:
            raise ValueError("rehearsal needs an isolated daemon without other containers, volumes or custom networks")


def derived_image(base, tag, revision, *, broken_worker=False):
    jar = profile.ROOT / "backend/app/build/install/app/lib/app-0.1.0.jar"
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w") as context:
        dockerfile = (f"FROM {base}\nCOPY app.jar /app/app/lib/app-0.1.0.jar\n"
                      f"LABEL org.opencontainers.image.source={profile.SOURCE} org.opencontainers.image.revision={revision}\n")
        if broken_worker:
            dockerfile += "COPY --chmod=0555 failed-worker /app/app/bin/pdf-worker\n"
            script = b'#!/bin/sh\nif [ "$1" = probe ]; then exit 1; fi\nexec sleep 600\n'
            member = tarfile.TarInfo("failed-worker")
            member.size = len(script)
            context.addfile(member, io.BytesIO(script))
        dockerfile = dockerfile.encode()
        member = tarfile.TarInfo("Dockerfile")
        member.size = len(dockerfile)
        context.addfile(member, io.BytesIO(dockerfile))
        context.add(jar, arcname="app.jar")
    command("docker", "build", "--platform", "linux/amd64", "--network", "none", "--pull=false",
            "-q", "-t", tag, "-", input=archive.getvalue())


def check_fixture_edge(root, port):
    raw = command("curl", "-sS", "--max-time", "6", "--noproxy", "*", "--cacert", str(root / "fixture-ca.pem"),
                  "--connect-to", "hookahtootah.club:443:127.0.0.1:" + port, "-D", "-", "-o", "/dev/null",
                  "-w", "%{http_code}", "https://hookahtootah.club/miniapp/", text=True)
    assert raw.endswith("503") and "x-hookah-maintenance: closed" in raw.lower().splitlines()


def fixture_operator(project, root, port, argv):
    # Relocate only the fixture namespace, decision directory and local TLS socket.
    # The real CLI takes its normal lock and verifies the real plans, digests, DB and containers.
    if (not re.fullmatch(r"ht-pilot-fixture-[0-9a-f]{12}", project) or root.parent != Path(tempfile.gettempdir()).resolve()
            or not root.name.startswith(project) or root.stat().st_uid != os.geteuid() or root.stat().st_mode & 0o077
            or any(Path(arg).resolve().parent != root for arg in argv[1:])):
        raise ValueError("operator rehearsal requires its disposable project and private fixture plans")
    require_isolated_daemon(project)
    profile.PRODUCTION_PROJECT = traffic.PRODUCTION_PROJECT = project
    profile.GATE = traffic.GATE = root / "gate" / "open"
    traffic.check_edge_closed = lambda: check_fixture_edge(root, port)
    sys.argv = ["production_traffic.py", *argv]
    traffic.main()


def exercise(root, project, images, pg, caddy):
    token = "98765:" + "x"*40
    account = "d"*32 + ".eu.r2.cloudflarestorage.com"
    values = {key: hashlib.sha256(key.encode()).hexdigest() for key in profile.SECRET_KEYS}
    values.update(POSTGRES_IMAGE=pg, TELEGRAM_BOT_ENABLED="true", TELEGRAM_BOT_TOKEN=token,
                  PLATFORM_OWNER_TELEGRAM_ID="42", TELEGRAM_MAIN_MINI_APP_CONFIGURED="false",
                  PRODUCTION_MEDIA_ENABLED="true", STAFF_PHOTOS_R2_BUCKET=profile.PRODUCTION_BUCKET,
                  STAFF_PHOTOS_R2_ENDPOINT="https://" + account, STAFF_PHOTOS_R2_REGION="auto",
                  STAFF_PHOTOS_R2_JURISDICTION="eu", STAFF_PHOTOS_R2_ACCESS_KEY="a"*32, STAFF_PHOTOS_R2_SECRET_KEY="b"*64)
    gate = root / "gate"
    gate.mkdir(mode=0o755)
    conf = root / "Caddyfile"
    conf.write_text((profile.PROFILE / "Caddyfile").read_text().replace("hookahtootah.club {",
                    "hookahtootah.club {\n\ttls internal").replace("reverse_proxy 127.0.0.1:18080", "reverse_proxy backend:8080"))
    plans = []
    def compose(plan, *args):
        return command("docker", "compose", "--env-file", "/dev/null", "-p", project, "-f", str(plan), *args, text=True).strip()
    for revision, image in images.items():
        doc = json.loads(profile.render(values, {"revision": revision, "image": image}))
        doc["name"] = project
        for collection in ("volumes", "networks"):
            for name, resource in doc[collection].items():
                resource["name"] = project + "_" + name
        backend = doc["services"]["backend"]
        backend["extra_hosts"] = ["api.telegram.org=127.0.0.1", account + "=127.0.0.1"]
        doc["x-hookah-schema"] = 143
        doc["x-hookah-release-evidence"] = {"candidate": {"revision": revision, "image": image, "ci_run": 1}}
        for mount in backend["volumes"]:
            if mount["target"] == "/run/hookah-ingress":
                mount["source"] = str(gate)
        path = root / (revision + ".json")
        path.write_text(json.dumps(doc))
        path.chmod(0o600)
        plans.append(path)
    old, new, failed = plans
    previous, base, _ = images.values()
    predecessor = profile.release_identity(json.loads(old.read_bytes()))
    for plan in (new, failed):
        doc = json.loads(plan.read_bytes())
        doc["x-hookah-transition"] = {"from": predecessor, "to": profile.release_identity(doc)}
        plan.write_text(json.dumps(doc))
    bootstrap = json.loads(old.read_bytes())
    bootstrap["services"]["edge"] = {"image": caddy, "networks": ["outbound"], "ports": ["127.0.0.1::443"],
                                  "volumes": [str(conf)+":/etc/caddy/Caddyfile:ro", str(gate)+":/var/lib/hookah-production/ingress:ro"]}
    bootstrap_plan = root / "bootstrap.json"
    bootstrap_plan.write_text(json.dumps(bootstrap))
    bootstrap_plan.chmod(0o600)
    def check_pair(plan, rev, image):
        manifest = json.loads(command("docker", "image", "inspect", "--platform", "linux/amd64", image))[0]
        index_id = command("docker", "image", "inspect", "--format", "{{.Id}}", image, text=True).strip()
        admission = profile.ProductionWorker(plan)
        admission.project = project
        assert command("docker", "info", "--format", "{{.CgroupVersion}}", text=True).strip() == "2"
        for service in ("postgres", "backend", "pdf-worker"):
            data = json.loads(command("docker", "inspect", compose(plan, "ps", "-q", service)))[0]
            profile.host.verify_container(service, data)
            if service == "postgres":
                assert data["State"]["Health"]["Status"] == "healthy"
                continue
            assert data["Config"]["Image"] == image and data["State"]["Running"]
            assert data["Image"] in (manifest["Id"], index_id)
            if data["Image"] != manifest["Id"]:
                assert data["ImageManifestDescriptor"]["digest"] == manifest["Descriptor"]["digest"]
            if service == "pdf-worker":
                admission.observe_pdf_worker(data, {"id": data["Image"],
                    "pdf_worker_env_hash": profile.digest(sorted(manifest["Config"].get("Env", [])))})
            else:
                admission.verify_backend_socket(data)
        origin = "http://" + compose(plan, "port", "backend", "8080")
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        until = time.monotonic() + 120
        while True:
            try:
                with opener.open(origin + "/db/health", timeout=3) as response:
                    assert json.load(response)["status"] == "ok"
                with opener.open(origin + "/version", timeout=3) as response:
                    version = json.load(response)
                assert version["version"] == rev and version["polling"] == "standby"
                return origin
            except (OSError, ValueError):
                if time.monotonic() >= until:
                    raise
                time.sleep(.4)
    try:
        compose(bootstrap_plan, "up", "-d", "--pull", "never", "--wait", "--wait-timeout", "180", "postgres", "edge")
        pg_id = compose(old, "ps", "-q", "postgres")
        edge_id = compose(bootstrap_plan, "ps", "-q", "edge")
        ca = root / "fixture-ca.pem"
        deadline = time.monotonic() + 30
        while True:
            cert = subprocess.run(["docker", "exec", edge_id, "cat", "/data/caddy/pki/authorities/local/root.crt"], capture_output=True)
            if cert.returncode == 0:
                ca.write_bytes(cert.stdout)
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("fixture Caddy CA unavailable")
            time.sleep(.2)
        port = compose(bootstrap_plan, "port", "edge", "443").split(":")[-1]
        def closed():
            check_fixture_edge(root, port)

        def operator(*args, success=True):
            cmd = [sys.executable, "-B", str(Path(__file__).resolve()), "--operator", project, str(root), port, *args]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
            if (result.returncode == 0) != success:
                raise RuntimeError(f"fixture operator {args[0]} failed expectation: {result.stderr[-1600:]}")
            return result

        closed()
        key = root / "synthetic.agekey"
        subprocess.run(["age-keygen", "-o", key], check=True, stderr=subprocess.DEVNULL)
        recipient = command("age-keygen", "-y", key, text=True).strip()
        with patch.object(profile, "PRODUCTION_PROJECT", project), patch.object(profile, "GATE", gate / "open"), \
                patch.object(traffic, "compose", side_effect=compose):
            traffic.start_release(old)
            origin = check_pair(old, "a"*40, previous)
            profile.enroll_database(old, recipient, first_install=True)
            fields = {"auth_date": str(int(time.time())), "user": json.dumps({"id": 42, "first_name": "Fixture"})}
            signing_key = hmac.digest(b"WebAppData", token.encode(), "sha256")
            fields["hash"] = hmac.new(signing_key, "\n".join(f"{k}={v}" for k, v in sorted(fields.items())).encode(), "sha256").hexdigest()
            body = json.dumps({"initData": urllib.parse.urlencode(fields)}).encode()
            request = urllib.request.Request(origin + "/api/auth/telegram", body, {"Content-Type": "application/json"})
            with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect()).open(request, timeout=10) as response:
                login = json.load(response)
                assert login["user"]["telegramUserId"] == 42 and login["token"]
            closed()
            print("PASS first private HTTPS maintenance, healthy PDF worker and signed owner login", flush=True)
            backend_id = compose(old, "ps", "--no-trunc", "-q", "backend")
            compose(old, "stop", "backend")
            closed()
            point = root / "cold-point"
            point.mkdir(mode=0o700)
            with patch.object(media_backup, "storage_client", return_value=object()):
                # No media rows exist on this first installation. Any S3 operation would fail the fixture.
                media_backup.capture(pg_id, {}, point, recipient, True, False)
            assert command("age", "-d", "-i", key, point / "database.dump.age").startswith(b"PGDMP")
            assert compose(old, "ps", "--no-trunc", "-aq", "backend") == backend_id
            command("docker", "start", backend_id)
            check_pair(old, "a"*40, previous)
            assert compose(old, "ps", "--no-trunc", "-q", "backend") == backend_id
            closed()
            print("PASS cold encrypted dump and restart of the very same backend with a healthy worker", flush=True)
            operator("close", old)
            operator("stage", old, new)
            check_pair(new, "b"*40, base)
            operator("close", new)
            operator("rollback", old, new)
            check_pair(old, "a"*40, previous)
            old_id = compose(old, "ps", "--no-trunc", "-q", "backend")
            operator("stage", old, failed, success=False)
            assert compose(failed, "ps", "--no-trunc", "-aq", "backend") == old_id
            assert command("docker", "inspect", "--format", "{{.State.Status}}", old_id).strip() == b"exited"
            worker_id = compose(failed, "ps", "--no-trunc", "-aq", "pdf-worker")
            worker = json.loads(command("docker", "inspect", worker_id))[0]
            assert worker["Config"]["Image"] == images["c"*40] and worker["State"]["Health"]["Status"] == "unhealthy"
            closed()
            wrong_pair = operator("rollback", old, new, success=False)
            assert "does not belong to either admitted release" in wrong_pair.stderr
            incompatible = root / "previous-schema.json"
            history = json.loads(old.read_bytes())
            history["x-hookah-schema"] = 142
            incompatible.write_text(json.dumps(history))
            incompatible.chmod(0o600)
            schema_refusal = operator("rollback", incompatible, failed, success=False)
            assert "different schemas require forward-fix" in schema_refusal.stderr
            assert compose(failed, "ps", "--no-trunc", "-q", "pdf-worker") == worker_id
            assert command("docker", "inspect", "--format", "{{.State.Status}}", old_id).strip() == b"exited"
            closed()
            operator("rollback", old, failed)
            assert compose(old, "ps", "--no-trunc", "-q", "backend") == old_id
            check_pair(old, "a"*40, previous)
            assert compose(old, "ps", "-q", "postgres") == pg_id
            assert not (gate / "activate").exists() and not (gate / "open").exists()
            closed()
            print("PASS operator stage/rollback after replacement and after unhealthy NEW worker with stopped OLD backend", flush=True)
    finally:
        compose(bootstrap_plan, "down", "-v", "--timeout", "5")


if __name__ == "__main__":
    if sys.argv[1:2] == ["--operator"]:
        fixture_operator(sys.argv[2], Path(sys.argv[3]), sys.argv[4], sys.argv[5:])
        sys.exit(0)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backend", help="cached local amd64 worker-capable production fixture")
    parser.add_argument("postgres", help="cached PostgreSQL 17 digest")
    parser.add_argument("caddy", help="cached Caddy 2 digest")
    args = parser.parse_args()
    if not __debug__:
        parser.error("rehearsal requires Python assertions")
    # Fail before creating fixture images on a daemon that cannot exercise the real contract.
    require_isolated_daemon()
    profile.host.verify_slice()
    for image in (args.backend, args.postgres, args.caddy):
        command("docker", "image", "inspect", image)
    name = "ht-pilot-fixture-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix=name) as tmp, ExitStack() as cleanup:
        images = {}
        for char in ("a", "b", "c"):
            tag = f"ghcr.io/koteev-m/hookah_bot/backend:{name}-{char}"
            derived_image(args.backend, tag, char*40, broken_worker=(char == "c"))
            cleanup.callback(command, "docker", "image", "rm", tag)
            refs = json.loads(command("docker", "image", "inspect", tag))[0]["RepoDigests"]
            images[char*40], = [ref for ref in refs if ref.startswith("ghcr.io/koteev-m/hookah_bot/backend@sha256:")]
        exercise(Path(tmp).resolve(), name, images, args.postgres, args.caddy)
