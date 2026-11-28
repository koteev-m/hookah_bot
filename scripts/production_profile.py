"""Check a pilot configuration, or admit an immutable release to a private Compose file."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

import production_host as host
from simple_staging import (IMAGE_RE, PDF_FLAG, PDF_IPC, R2_CONNECTION_KEYS, REPO, SOURCE,
                            NoCiRedirect, REQUIRED_JOBS, PdfWorkerAdmission, Refused, public_ci_json, add_pdf_worker, digest, history_for, manifest, require)

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "deploy/production"
PRODUCTION_PROJECT = "hookah_production"
GATE = host.GATE
SECRET_KEYS = {
    "POSTGRES_ADMIN_PASSWORD", "DB_PASSWORD", "API_SESSION_JWT_SECRET", "BILLING_WEBHOOK_SECRET",
    "TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER", "VENUE_STAFF_INVITE_SECRET_PEPPER",
}
KEYS = SECRET_KEYS | {
    "POSTGRES_IMAGE", "TELEGRAM_BOT_ENABLED", "TELEGRAM_BOT_TOKEN",
    "PLATFORM_OWNER_TELEGRAM_ID", "TELEGRAM_MAIN_MINI_APP_CONFIGURED",
}
MEDIA_FLAG = "PRODUCTION_MEDIA_ENABLED"
MEDIA_KEYS = {MEDIA_FLAG} | R2_CONNECTION_KEYS
PRODUCTION_BUCKET = "hookah-tootah-media-production-eu"


def check_media(values):
    require(values.get(MEDIA_FLAG, "false") in ("true", "false"), "invalid production media flag")
    if values.get(MEDIA_FLAG) != "true":
        require(not any(values.get(key) for key in R2_CONNECTION_KEYS), "disabled media requires empty R2 settings")
        return
    require(values.get("STAFF_PHOTOS_R2_BUCKET") == PRODUCTION_BUCKET and
            values.get("STAFF_PHOTOS_R2_REGION") == "auto" and values.get("STAFF_PHOTOS_R2_JURISDICTION") == "eu",
            "production EU bucket and region required")
    require(re.fullmatch(r"https://[a-f0-9]{32}\.eu\.r2\.cloudflarestorage\.com", values.get("STAFF_PHOTOS_R2_ENDPOINT", "")),
            "invalid production EU R2 endpoint")
    require(re.fullmatch("[a-f0-9]{32}", values.get("STAFF_PHOTOS_R2_ACCESS_KEY", "")) and
            re.fullmatch("[a-f0-9]{64}", values.get("STAFF_PHOTOS_R2_SECRET_KEY", "")) and
            values["STAFF_PHOTOS_R2_SECRET_KEY"] not in (values.get(key) for key in SECRET_KEYS),
            "invalid or reused production R2 credentials")


def runtime(path):
    mode = path.lstat()
    require(stat.S_ISREG(mode.st_mode) and mode.st_uid == os.geteuid() and not mode.st_mode & 0o077,
            "runtime must be an owner-private regular file")
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(sep and key in KEYS | MEDIA_KEYS and key not in values, "unknown, malformed or duplicate runtime setting")
        values[key] = value
    require(KEYS <= values.keys(), "incomplete runtime settings")
    check_media(values)
    require(re.fullmatch(r"postgres@sha256:[a-f0-9]{64}", values["POSTGRES_IMAGE"]), "official PG digest required")
    for key in SECRET_KEYS:
        require(re.fullmatch("[a-f0-9]{64}", values[key]) and len(set(values[key])) >= 8,
                f"{key} requires an independently generated 32-byte hex secret")
    require(len({values[key] for key in SECRET_KEYS}) == len(SECRET_KEYS), "secret reuse across purposes")
    for key in ("TELEGRAM_BOT_ENABLED", "TELEGRAM_MAIN_MINI_APP_CONFIGURED"):
        require(values[key] in ("true", "false"), f"invalid {key}")
    if values["TELEGRAM_BOT_ENABLED"] == "true":
        require(re.fullmatch(r"[1-9][0-9]*:[A-Za-z0-9_-]{30,}", values["TELEGRAM_BOT_TOKEN"]),
                "enabled bot requires its production token")
        owner = values["PLATFORM_OWNER_TELEGRAM_ID"]
        require(re.fullmatch("[1-9][0-9]{0,18}", owner) and int(owner) < 2**63, "invalid platform owner")
    else:
        require(not values["TELEGRAM_BOT_TOKEN"] and not values["PLATFORM_OWNER_TELEGRAM_ID"]
                and values["TELEGRAM_MAIN_MINI_APP_CONFIGURED"] == "false", "disabled bot requires empty credentials")
    return values


def candidate(path):
    value = json.loads(path.read_bytes())
    require(isinstance(value, dict) and set(value) == {"image", "revision", "ci_run"}, "invalid CI candidate")
    require(isinstance(value["image"], str) and re.fullmatch(IMAGE_RE, value["image"]), "Hookah GHCR digest required")
    require(isinstance(value["revision"], str) and re.fullmatch("[a-f0-9]{40}", value["revision"]), "full SHA required")
    require(type(value["ci_run"]) is int and value["ci_run"] > 0, "CI run ID required")
    return value


def render(values, release, *, bind_directory=None):
    # Compose interpolates the invoking shell before an env file. Pass only reviewed inputs.
    env = {k: os.environ[k] for k in ("PATH", "HOME", "DOCKER_CONFIG", "DOCKER_HOST") if k in os.environ}
    env.update(values, RELEASE_SHA=release["revision"], BACKEND_IMAGE=release["image"])
    args = ["docker", "compose", "--env-file", "/dev/null", "-p", PRODUCTION_PROJECT, "-f", str(PROFILE / "compose.yaml")]
    if bind_directory is not None:
        args.extend(["--project-directory", str(bind_directory)])
    overlay = None
    if values.get(MEDIA_FLAG) == "true":
        media = {k: values[k] for k in R2_CONNECTION_KEYS}
        media.update(STAFF_PHOTOS_R2_ENABLED="true", VENUE_INFO_MEDIA_R2_ENABLED="true", VENUE_INFO_PDF_WORKER_ENABLED="true")
        doc = {"services": {"backend": {"environment": media}}, "volumes": {}}
        add_pdf_worker(doc, release["image"], "linux/amd64")
        doc["services"]["pdf-worker"]["cgroup_parent"] = host.SLICE
        overlay = json.dumps(doc).encode()
        args.extend(["-f", "-"])
    raw = subprocess.check_output(args + ["config", "--format", "json"], input=overlay, env=env)
    if overlay is not None:
        doc = json.loads(raw)
        limits = doc["services"]["pdf-worker"]["ulimits"]
        # Compose omits both zero fields in its JSON output. Persist an executable plan.
        require(limits.get("core") in ({}, {"soft": 0, "hard": 0}, 0), "unexpected core dump limit")
        limits["core"] = {"soft": 0, "hard": 0}
        return json.dumps(doc).encode()
    return raw


PILOT_MANIFEST = "d1474a3b83ce086761a058edf124cecb3ca6d5687462a2bc868c2118e1f87b39"
PARTICIPATION_MANIFEST = "111a982031d430f7528f0c87c1b8d150680b5e307188430a630d4439271ec97a"
PARTICIPATION_SOURCE = {
    "revision": "59bde4074cbb190582e783ea9c1879cc74bb14c7",
    "image": "ghcr.io/koteev-m/hookah_bot/backend@sha256:842c7d4d0d7ab0af9ae162146c2da7083b748c0520491d098db949bded3c696f",
}


def source_manifest(*, historical=False):
    files = ROOT / "backend/app/src/main/resources/db/migration/postgresql"
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as jar:
        for sql in files.glob("V*__*.sql"):
            jar.write(sql, "db/migration/postgresql/" + sql.name)
    rows = manifest(archive.getvalue())
    require(len(rows) >= 143 and digest(rows[:143]) == PILOT_MANIFEST, "historical pilot migrations changed")
    if historical:
        return rows[:143]
    require(len(rows) == 147 and digest(rows) == PARTICIPATION_MANIFEST, "expected reviewed V1–V147 manifest")
    return rows


def github_get(path):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoCiRedirect())
    return public_ci_json(opener, f"https://api.github.com/repos/{REPO}/{path}", time.monotonic() + 15)


def github_pages(path, field):
    rows = []
    total = None
    for page in range(1, 11):
        result = github_get(f"{path}?per_page=100&page={page}")
        if total is None:
            total = result["total_count"]
        batch = result[field]
        require(type(total) is int and 0 <= total <= 1000 and result["total_count"] == total
                and isinstance(batch, list) and len(batch) == min(100, total - len(rows)), "incomplete GitHub listing")
        rows.extend(batch)
        if len(rows) == total:
            require(len({r["id"] for r in rows}) == total, "duplicate GitHub result")
            return rows
    raise Refused("GitHub listing exceeds limit")


def candidate_artifact(release, attempt):
    run = release["ci_run"]
    name = f"ghcr-candidate-{release['revision']}-{attempt}"
    found = [a for a in github_pages(f"actions/runs/{run}/artifacts", "artifacts") if a["name"] == name]
    require(len(found) == 1, "unique candidate artifact required")
    artifact = found[0]
    require(artifact.get("expired") is False and artifact["workflow_run"]["id"] == run
            and artifact["workflow_run"]["head_sha"] == release["revision"]
            and type(artifact["id"]) is int and artifact["id"] > 0
            and 0 < artifact["size_in_bytes"] <= 4 * 1024**2
            and re.fullmatch(r"sha256:[0-9a-f]{64}", artifact.get("digest") or ""), "unusable candidate artifact")
    return artifact


def check_archive(raw, artifact, release):
    require(len(raw) == artifact["size_in_bytes"] and
            "sha256:" + hashlib.sha256(raw).hexdigest() == artifact["digest"], "GitHub ZIP digest mismatch")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = [m for m in archive.infolist() if m.filename == "candidate.json"]
        require(len(entries) == 1 and 0 < entries[0].file_size <= 4096
                and json.loads(archive.read(entries[0])) == release, "published candidate differs")


def verify_published(release, evidence=None, pinned=None, *, archive=None, expected_attempt=None):
    run_id = str(release["ci_run"])
    endpoint = f"actions/runs/{run_id}"
    data = github_get(endpoint)
    require(data["id"] == release["ci_run"] and data["repository"]["full_name"] == REPO
            and data["head_repository"]["full_name"] == REPO and data["event"] == "push"
            and data["head_branch"] == "main" and data["head_sha"] == release["revision"]
            and data["path"] == ".github/workflows/ci.yml" and data["status"] == "completed"
            and data["conclusion"] == "success", "successful exact main CI required")
    attempt = data["run_attempt"]
    require(type(attempt) is int and attempt > 0, "invalid CI attempt")
    receipt = {"repository": REPO, "workflow": data["path"], "attempt": attempt, "candidate": release}
    # Match the verified publisher's retained step, independent of both the candidate and custodian file.
    require(expected_attempt is None or expected_attempt == attempt, "CI attempt changed")
    jobs = github_pages(f"{endpoint}/attempts/{attempt}/jobs", "jobs")
    if archive is not None:
        require(REQUIRED_JOBS <= {job["name"].split(" (")[0] for job in jobs}
                and all(job.get("head_sha") == release["revision"] and job.get("run_id") == release["ci_run"]
                        and job.get("run_attempt", attempt) == attempt and job.get("conclusion") == "success"
                        and job.get("status") == "completed" for job in jobs), "incomplete green CI attempt")
    publishers = [job for job in jobs if job["name"] == "publish-ghcr"]
    step = "Record verified image " + release["image"].split("@")[1]
    require(len(publishers) == 1 and publishers[0].get("conclusion") == "success" and
            publishers[0].get("head_sha") == release["revision"] and publishers[0].get("run_id") == release["ci_run"] and
            publishers[0].get("run_attempt", attempt) == attempt and
            sum(s.get("name") == step and s.get("conclusion") == "success" for s in publishers[0]["steps"]) == 1,
            "no independently verified image digest in this publisher attempt")
    if archive is not None:
        require(evidence is None and pinned is None and expected_attempt == attempt, "ambiguous archive admission")
        check_archive(archive, candidate_artifact(release, attempt), release)
        final = github_get(endpoint)
        require(all(final.get(k) == data.get(k) for k in ("id", "head_sha", "run_attempt", "status", "conclusion",
                                                          "head_branch", "event", "path")), "GitHub run changed during admission")
        return receipt
    if evidence is not None or pinned is not None:
        require(evidence is not None and isinstance(pinned, str) and re.fullmatch("[a-f0-9]{64}", pinned),
                "release evidence requires its independently held SHA-256")
        raw = evidence.read_bytes()
        require(hashlib.sha256(raw).hexdigest() == pinned and json.loads(raw) == receipt,
                "release evidence differs from custodian pin or successful CI attempt")
        return receipt
    raise Refused("supply the original GitHub candidate ZIP from the operator Mac and its attempt, or held custody evidence")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify_bot(values):
    if values["TELEGRAM_BOT_ENABLED"] == "false":
        return
    token = values["TELEGRAM_BOT_TOKEN"]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(f"https://api.telegram.org/bot{token}/getMe", timeout=15) as reply:
            result = json.load(reply)
    except (OSError, ValueError):
        # Telegram uses the secret in the URL. Never propagate a transport exception.
        raise Refused("Telegram identity check unavailable") from None
    bot = result.get("result") or {}
    require(result.get("ok") is True and bot.get("is_bot") is True and str(bot.get("id")) == token.split(":")[0]
            and bot.get("username", "").lower() == "hookah_tootah_bot", "token does not belong to production bot")


def verify_images(values, release, measurements=None, allocations=(), *, schema=147):
    host.verify_slice()
    refs = (values["POSTGRES_IMAGE"], release["image"])
    missing = host.image_space(refs, measurements, allocations, reserve_release=True)
    for ref in refs:
        inspect = ["docker", "image", "inspect", "--platform", "linux/amd64", ref]
        if ref in missing:
            subprocess.run(["docker", "pull", "--quiet", "--platform", "linux/amd64", ref], check=True, **host.lock_args())
            missing = host.image_space(refs, measurements, allocations, reserve_release=True)
            require(ref not in missing, "pulled image not fully cached/unpacked")
        info = json.loads(subprocess.check_output(inspect))[0]
        digests = [s.removeprefix("docker.io/library/") for s in info["RepoDigests"]]
        require(ref in digests and (info["Os"], info["Architecture"]) == ("linux", "amd64"), "image identity mismatch")
        if ref == release["image"]:
            labels = info["Config"]["Labels"]
            require(labels.get("org.opencontainers.image.source") == SOURCE
                    and labels.get("org.opencontainers.image.revision") == release["revision"]
                    and not info["Config"].get("Volumes"), "unexpected backend image")
    probe = ["docker", "run", "--rm", "--pull", "never", "--cgroup-parent", host.SLICE, "--network", "none", "--read-only", "--platform", "linux/amd64"]
    # Override the official image's data volume with disposable tmpfs.
    pg_version = subprocess.check_output(probe + ["--tmpfs", "/var/lib/postgresql/data", "--entrypoint", "postgres",
                                                   values["POSTGRES_IMAGE"], "--version"], text=True, **host.lock_args())
    require(re.fullmatch(r"postgres \(PostgreSQL\) 17\.\d+[^\n]*\n?", pg_version), "PostgreSQL 17 required")
    jar = subprocess.check_output(probe + ["--entrypoint", "cat", release["image"], "/app/app/lib/app-0.1.0.jar"], **host.lock_args())
    with zipfile.ZipFile(io.BytesIO(jar)) as package:
        require(b"telegramDispatchAllowed" in package.read("com/hookah/platform/backend/ProductionTrafficGate.class"),
                "backend does not support process-bound production admission")
        if values.get(MEDIA_FLAG) == "true":
            require(package.read("pdf-worker-protocol.txt") == b"HTPDF1\n" and
                    PRODUCTION_BUCKET.encode() in package.read(
                        "com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoStorageKt.class"),
                    "image lacks the production R2 or PDF worker contract")
    require(manifest(jar) == source_manifest(historical=schema == 143), "packaged migration manifest differs")


def check_history(raw, allowed=(143,)):
    rows = json.loads(raw)
    require(isinstance(rows, list) and all(r.get("success") is True for r in rows), "unsuccessful Flyway history")
    actual = [{k: row[k] for k in ("version", "script", "checksum")} for row in rows]
    expected = history_for(source_manifest(historical=max(allowed) <= 143))
    require(len(actual) in allowed and actual == expected[:len(actual)], "Flyway versions, scripts or checksums differ")


def compare_update(previous, desired):
    old = json.loads(previous.read_bytes())
    new = json.loads(desired)
    previous_sha = old["services"]["backend"]["environment"]["APP_VERSION"]
    require(isinstance(previous_sha, str) and re.fullmatch("[0-9a-f]{40}", previous_sha), "invalid previous revision")
    for doc in (old, new):
        doc.pop("x-hookah-transition", None)
        doc.pop("x-hookah-schema", None)
        doc.pop("x-hookah-release-evidence", None)
        backend = doc["services"]["backend"]
        if "pdf-worker" in doc["services"]:
            worker = doc["services"]["pdf-worker"]
            require(worker.pop("image") == backend["image"], "worker must use the admitted backend digest")
        del backend["image"], backend["environment"]["APP_VERSION"]
    require(old == new, "update permits only the backend digest and revision to change")
    return previous_sha


def release_identity(doc):
    service = doc["services"]["backend"]
    sha, image = service["environment"]["APP_VERSION"], service["image"]
    require(re.fullmatch("[0-9a-f]{40}", sha) and re.fullmatch(IMAGE_RE, image), "invalid release identity")
    return {"revision": sha, "image": image}


def confirm_transition(previous, desired, *, offline=False):
    old, new = json.loads(previous.read_bytes()), json.loads(desired)
    compare_update(previous, desired)
    versions = plan_schema(old), plan_schema(new)
    if offline:
        require(versions == (143, 147) and release_identity(old) == PARTICIPATION_SOURCE,
                "offline cutover requires the approved V143 predecessor and V147 target")
    else:
        require(versions in ((142, 143), (143, 143), (147, 147)), "production offline cutover required")
    predecessor, target = release_identity(old), release_identity(new)
    require(predecessor["revision"] != target["revision"] and predecessor["image"] != target["image"]
            and new.get("x-hookah-transition") == {"from": predecessor, "to": target}, "update lacks this admitted forward transition")


def admission_pin(plan):
    return plan.with_name(plan.name + ".admitted-sha256")


def verify_plan_pin(plan):
    pin = admission_pin(plan)
    for path in (plan, pin):
        st = path.lstat()
        require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() and not st.st_mode & 0o077,
                "owner-private admitted plan and receipt required")
    require(pin.read_text() == hashlib.sha256(plan.read_bytes()).hexdigest() + "\n",
            "plan differs from its independent admission receipt")


def sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def publish_plan(plan, document):
    directory = plan.parent.stat()
    require(directory.st_uid == os.geteuid() and not directory.st_mode & 0o077, "private plan directory required")
    require(not os.path.lexists(plan), "refusing to replace an admitted plan")
    pin = admission_pin(plan)
    checksum = (hashlib.sha256(document).hexdigest() + "\n").encode()
    # An interruption after publishing the receipt may be retried with the same bytes.
    for path, content in ((pin, checksum), (plan, document)):
        with tempfile.NamedTemporaryFile(dir=plan.parent) as tmp:
            tmp.write(content)
            tmp.flush()
            os.fsync(tmp.fileno())
            try:
                os.link(tmp.name, path)
            except FileExistsError:
                require(path == pin and stat.S_ISREG(pin.lstat().st_mode) and pin.read_bytes() == checksum,
                        "conflicting admission receipt")
        sync_directory(plan.parent)
    verify_plan_pin(plan)


def check_postgres_image(ref):
    require(re.fullmatch(r"postgres@sha256:[0-9a-f]{64}", ref), "approved official PostgreSQL digest required")
    image, = json.loads(subprocess.check_output(["docker", "image", "inspect", "--platform", "linux/amd64", ref]))
    require(ref in [name.removeprefix("docker.io/library/") for name in image["RepoDigests"]] and
            (image["Os"], image["Architecture"]) == ("linux", "amd64"), "admitted PostgreSQL image identity changed")
    return image


def check_target_image(plan):
    identity = release_identity(json.loads(plan.read_bytes()))
    image = json.loads(subprocess.check_output(
        ["docker", "image", "inspect", "--platform", "linux/amd64", identity["image"]]))[0]
    labels = image["Config"].get("Labels") or {}
    require(identity["image"] in image["RepoDigests"] and (image["Os"], image["Architecture"]) == ("linux", "amd64")
            and labels.get("org.opencontainers.image.source") == SOURCE
            and labels.get("org.opencontainers.image.revision") == identity["revision"],
            "target image no longer matches the admitted revision and digest")
    return image


def verify_forward(previous, desired):
    compare = github_get(f"compare/{previous}...{desired}?per_page=1")
    require(compare["status"] == "ahead" and compare["merge_base_commit"]["sha"] == previous,
            "ordinary update requires a descendant of the current revision")


def matches_running_image(data, ref, image):
    if data["Config"]["Image"] != ref or image["Os"] != "linux" or image["Architecture"] != "amd64":
        return False
    # Containerd may record the index in Image and its selected child in ImageManifestDescriptor.
    if data["Image"] == image["Id"]:
        return True
    child = (image.get("Descriptor") or {}).get("digest")
    return (bool(child) and data["Image"] == ref.split("@")[1]
            and (data.get("ImageManifestDescriptor") or {}).get("digest") == child)


class ProductionWorker(PdfWorkerAdmission):
    run = staticmethod(subprocess.check_output)

    def __init__(self, plan):
        self.project = PRODUCTION_PROJECT
        self.compose_file = plan
        self.root = plan.resolve().parent

    def verify_backend_socket(self, data):
        self.pdf_mount([m for m in data["Mounts"] if m["Type"] == "volume"], writable=False)
        host = data["HostConfig"]
        self.pdf_host_mount({
            "Mounts": [m for m in host.get("Mounts") or [] if m.get("Target") == PDF_IPC],
            "Binds": [b for b in host.get("Binds") or [] if PDF_IPC in b.split(":")[1:2]],
        }, writable=False)


def verify_running(plan, *, database_only=False):
    doc = json.loads(plan.read_bytes())
    compose = ["docker", "compose", "-p", PRODUCTION_PROJECT, "--env-file", "/dev/null", "-f", str(plan)]
    hashes = dict(line.split() for line in subprocess.check_output(compose + ["config", "--hash", "*"],
                                                                 text=True).splitlines())
    media = doc["services"]["backend"].get("environment", {}).get(PDF_FLAG) == "true"
    services = {"postgres", "backend", "pdf-worker"} if media else {"postgres", "backend"}
    require(doc["name"] == PRODUCTION_PROJECT and set(hashes) == services, "wrong topology")
    if not database_only:
        host.verify_slice()
    postgres_id = None
    for service, config_hash in hashes.items():
        if database_only and service != "postgres":
            continue
        cid = subprocess.check_output(compose + ["ps", "-q", service], text=True).strip()
        require(re.fullmatch("[0-9a-f]{12,64}", cid), f"missing or duplicate {service}")
        data = json.loads(subprocess.check_output(["docker", "inspect", cid]))[0]
        ref = doc["services"][service]["image"]
        image = json.loads(subprocess.check_output(["docker", "image", "inspect", "--platform", "linux/amd64", ref]))[0]
        labels = data["Config"]["Labels"]
        require(data["State"]["Running"] and matches_running_image(data, ref, image)
                and labels.get("com.docker.compose.config-hash") == config_hash
                and labels.get("com.docker.compose.service") == service
                and labels.get("com.docker.compose.project") == doc["name"], f"{service} differs from release")
        if not database_only:
            host.verify_container(service, data)
        nets = set(data["NetworkSettings"]["Networks"])
        if service == "postgres":
            require(re.fullmatch(r"postgres@sha256:[0-9a-f]{64}", ref) and
                    data["State"].get("Health", {}).get("Status") == "healthy", "PostgreSQL is not healthy or pinned")
            postgres_id = data["Id"]
            volumes = [m for m in data["Mounts"] if m["Destination"] == "/var/lib/postgresql/data"]
            require(len(volumes) == 1 and volumes[0]["Type"] == "volume"
                    and volumes[0]["Name"] == f"{PRODUCTION_PROJECT}_pgdata" and volumes[0]["RW"]
                    and nets == {f"{PRODUCTION_PROJECT}_database"} and not data["HostConfig"]["PortBindings"],
                    "unexpected database binding")
        elif service == "pdf-worker":
            require(ref == doc["services"]["backend"]["image"], "unexpected PDF image")
            require(subprocess.check_output(["docker", "info", "--format", "{{.CgroupVersion}}"]).strip() == b"2",
                    "PDF worker requires cgroup v2")
            ProductionWorker(plan).observe_pdf_worker(data, {
                "id": data["Image"], "pdf_worker_env_hash": digest(sorted(image["Config"].get("Env", [])))})
        else:
            if media:
                ProductionWorker(plan).verify_backend_socket(data)
            require(data["State"].get("Health", {}).get("Status", "healthy") == "healthy"
                    and nets == {f"{PRODUCTION_PROJECT}_database", f"{PRODUCTION_PROJECT}_outbound"}
                    and data["HostConfig"]["PortBindings"] == {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]},
                    "unexpected backend network binding")

    return postgres_id


def pg_query(cid, user, sql):
    return subprocess.check_output(
        ["docker", "exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", "hookah_prod"],
        input=sql, text=True).strip()


def plan_schema(document):
    version = document.get("x-hookah-schema", 142)
    require(type(version) is int and version in (142, 143, 147), "unapproved plan schema")
    return version


def cluster_identity(plan):
    cid = verify_running(plan, database_only=True)
    network = json.loads(subprocess.check_output(["docker", "network", "inspect", f"{PRODUCTION_PROJECT}_database"]))[0]
    volume = json.loads(subprocess.check_output(["docker", "volume", "inspect", f"{PRODUCTION_PROJECT}_pgdata"]))[0]
    require(network["Internal"] and cid in network["Containers"] and volume["Driver"] == "local" and not volume.get("Options")
            and all((r.get("Labels") or {}).get("com.docker.compose.project") == PRODUCTION_PROJECT
                    for r in (network, volume)), "database network or volume is not project-owned")
    identity = pg_query(cid, "postgres", "SELECT system_identifier, oid FROM pg_control_system(), pg_database "
                        "WHERE datname=current_database() AND current_database()='hookah_prod' "
                        "AND to_regnamespace('hookah_recovery') IS NULL "
                        "AND (SELECT rolcanlogin FROM pg_roles WHERE rolname='hookah_app') "
                        "AND pg_get_userbyid(datdba)='hookah_app' AND current_setting('server_version_num')::int/10000=17;")
    require(re.fullmatch(r"[1-9][0-9]*\|[1-9][0-9]*", identity), "unexpected PostgreSQL database identity")
    return {"container": cid, "cluster_and_db": identity, "network": network["Id"],
            "volume": volume["Name"], "volume_created": volume["CreatedAt"]}


def database_identity(plan, transition_from=None):
    identity = cluster_identity(plan)
    allowed = [plan_schema(json.loads(plan.read_bytes()))]
    if transition_from is not None:
        predecessor = plan_schema(json.loads(transition_from.read_bytes()))
        if (predecessor, allowed[0]) == (143, 147):
            # Observe an interrupted Flyway run only for the exact admitted maintenance pair.
            # A single-plan check (including open, rollback and backup) still requires its final version.
            confirm_transition(transition_from, plan.read_bytes(), offline=True)
            allowed = range(143, 148)
        else:
            allowed.append(predecessor)
    check_history(pg_query(identity["container"], "hookah_app", (PROFILE / "history.sql").read_text()), allowed)
    return identity


def verified_database(plan, recipient=None, transition_from=None):
    record = json.loads((plan.parent / "postgres-binding.json").read_bytes())
    expected_recipient = record.pop("recipient")
    pending = GATE.with_name(".install-intent")
    decision = record.pop("successor_review_sha256", None)
    require(decision is None or os.path.lexists(pending), "missing successor enrollment receipt")
    if os.path.lexists(pending):
        intent = json.loads(pending.read_bytes())
        if decision is not None or intent["mode"] == "install-successor":
            require(intent["mode"] == "install-successor" and intent.get("registered") is True and
                    isinstance(decision, str) and re.fullmatch("[0-9a-f]{64}", decision) and
                    intent["retirement_review_sha256"] == decision and intent["pristine"] == record and
                    intent["recipient"] == expected_recipient and Path(intent["target"]["path"]).parent == plan.resolve().parent,
                    "database binding differs from completed successor enrollment")
    require(recipient is None or recipient == expected_recipient, "backup recipient differs from enrolled recovery key")
    observed = database_identity(plan, transition_from)
    require(observed == record, "PostgreSQL differs from installation binding")
    return observed["container"]


def require_empty_enrollment(cid):
    # First enrollment is documented before customer data. An ordinary bind must never
    # legitimize a raw pg_restore that predates the recovery schema marker.
    require(pg_query(cid, "postgres", "SELECT NOT EXISTS (SELECT FROM users) "
                     "AND NOT EXISTS (SELECT FROM venues) AND NOT EXISTS (SELECT FROM orders) "
                     "AND NOT EXISTS (SELECT FROM bookings) AND NOT EXISTS (SELECT FROM invoices)") == "t" and
            pg_query(cid, "postgres", (PROFILE / "recovery-admission.sql").read_text()),
            "first enrollment requires a clean installation before business data or grants")


def check_recipient(recipient):
    require(re.fullmatch(r"age1[ac-hj-np-z02-9]{58}", recipient), "a native public age recipient is required")
    subprocess.run(["age", "-r", recipient], input=b"", stdout=subprocess.DEVNULL, check=True)


def publish_binding(plan, record):
    with tempfile.NamedTemporaryFile(dir=plan.parent) as f:
        f.write(json.dumps(record).encode())
        f.flush()
        os.fsync(f.fileno())
        os.link(f.name, plan.parent / "postgres-binding.json")
    sync_directory(plan.parent)


def enroll_database(plan, recipient, *, first_install=False):
    check_recipient(recipient)
    target = database_identity(plan)
    hold = GATE.with_name("recovery-hold")
    if os.path.lexists(hold):
        incident = json.loads(hold.read_bytes())
        require(not first_install and incident.get("target") == target and
                incident.get("plan_sha256") == hashlib.sha256(plan.read_bytes()).hexdigest(),
                "first binding must match the enrolled recovery target")
    else:
        require(first_install and not os.path.lexists(GATE.with_name("recovery-accepted")),
                "confirm a genuine first installation or enroll the replacement through recovery-begin")
    pending = GATE.with_name(".install-intent")
    if os.path.lexists(pending):
        verify_plan_pin(plan)
        intent = json.loads(pending.read_bytes())
        require(intent.get("mode") != "install-successor", "clean successor requires its signed enrollment")
        installed = intent["target"]
        require(installed["path"] == str(plan.resolve()) and installed["sha256"] == hashlib.sha256(plan.read_bytes()).hexdigest(),
                "cannot enroll a different pending installation")
    require_empty_enrollment(target["container"])
    publish_binding(plan, dict(target, recipient=recipient))
    if os.path.lexists(pending):
        pending.unlink()
        sync_directory(GATE.parent)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("history", help="verify ordered Flyway JSON on stdin").add_argument(
        "--schema", type=int, choices=(142, 143, 147), default=143)
    actions.add_parser("running", help="check live image, configuration and bindings").add_argument("plan", type=Path)
    for name in ("bind-db", "backup-db"):
        cmd = actions.add_parser(name)
        cmd.add_argument("plan", type=Path)
        cmd.add_argument("recipient")
        if name == "bind-db":
            cmd.add_argument("--first-install", action="store_true", help="operator confirmed this is the first production installation")
    for name in ("check", "admit"):
        cmd = actions.add_parser(name)
        cmd.add_argument("runtime", type=Path)
        cmd.add_argument("candidate", type=Path)
        if name == "admit":
            cmd.add_argument("output", type=Path)
            cmd.add_argument("--schema", type=int, choices=(143, 147), default=147,
                             help="select 143 only for the pinned historical pilot image")
            cmd.add_argument("--image-sizes", type=Path, help="qualified packed and unpacked bounds per missing linux/amd64 digest and storage driver")
            cmd.add_argument("--evidence", type=Path, help="previously qualified release receipt from custody")
            cmd.add_argument("--evidence-sha256", help="independent pin recorded at live admission")
            cmd.add_argument("--candidate-zip", type=Path, help="original GitHub Actions ZIP obtained on the operator Mac")
            cmd.add_argument("--attempt", type=int, help="owner-approved CI run attempt")
            cmd.add_argument("--previous", type=Path, help="current admitted Compose file for an update")
    args = parser.parse_args()
    if args.action in ("admit", "bind-db"):
        with host.operator_lock():
            return execute(args)
    return execute(args)


def execute(args):
    if args.action == "history":
        check_history(sys.stdin.buffer.read(), [args.schema])
    elif args.action == "running":
        verify_running(args.plan)
    elif args.action == "bind-db":
        enroll_database(args.plan, args.recipient, first_install=args.first_install)
    elif args.action == "backup-db":
        print(verified_database(args.plan, args.recipient))
        return
    else:
        values = runtime(args.runtime)
        release = candidate(args.candidate)
        document = render(values, release, bind_directory=getattr(args, "bind_directory", None))
        if args.action == "admit":
            plan = json.loads(document)
            schema = getattr(args, "schema", 147)
            require(schema in (143, 147), "unapproved admission schema")
            plan["x-hookah-schema"] = schema
            document = json.dumps(plan).encode()
            if args.previous:
                previous_sha = compare_update(args.previous, document)
                verify_forward(previous_sha, release["revision"])
                plan = json.loads(document)
                plan["x-hookah-transition"] = {"from": release_identity(json.loads(args.previous.read_bytes())),
                                              "to": release_identity(plan)}
                document = json.dumps(plan).encode()
                confirm_transition(args.previous, document, offline=getattr(args, "offline", False))
            archive = getattr(args, "archive", None)
            if getattr(args, "candidate_zip", None):
                require(args.candidate_zip.stat().st_size <= 4 * 1024**2, "candidate ZIP exceeds limit")
                archive = args.candidate_zip.read_bytes()
            receipt = verify_published(release, args.evidence, args.evidence_sha256, archive=archive,
                                       expected_attempt=getattr(args, "attempt", None))
            allocations = []
            if args.previous:
                host.image_space(host.plan_images(json.loads(args.previous.read_bytes())["services"]))
                path, db, wal = host.database_space(verified_database(args.previous))
                allocations.append((path, db + wal))
            verify_images(values, release, args.image_sizes, allocations, schema=schema)
            if args.previous:
                host.image_space(host.plan_images(json.loads(args.previous.read_bytes())["services"]))
            verify_bot(values)
            # Bind provenance to the private plan without placing runtime secrets in custody evidence.
            plan = json.loads(document)
            plan["x-hookah-release-evidence"] = receipt
            document = json.dumps(plan).encode()
            # Publish a complete private plan once, after all checks; never overwrite a running plan.
            publish_plan(args.output, document)
    print("OK", args.action)


if __name__ == "__main__":
    try:
        main()
    except (Refused, ValueError, KeyError, OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit(f"production profile refused: {exc}") from None
