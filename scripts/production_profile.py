"""Check a pilot configuration, or admit an immutable release to a private Compose file."""
import argparse
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

from simple_staging import IMAGE_RE, REPO, SOURCE, Refused, history_for, manifest, require

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "deploy/production"
SECRET_KEYS = {
    "POSTGRES_ADMIN_PASSWORD", "DB_PASSWORD", "API_SESSION_JWT_SECRET", "BILLING_WEBHOOK_SECRET",
    "TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER", "VENUE_STAFF_INVITE_SECRET_PEPPER",
}
KEYS = SECRET_KEYS | {
    "POSTGRES_IMAGE", "TELEGRAM_BOT_ENABLED", "TELEGRAM_BOT_TOKEN",
    "PLATFORM_OWNER_TELEGRAM_ID", "TELEGRAM_MAIN_MINI_APP_CONFIGURED",
}


def runtime(path):
    mode = path.lstat()
    require(stat.S_ISREG(mode.st_mode) and mode.st_uid == os.geteuid() and not mode.st_mode & 0o077,
            "runtime must be an owner-private regular file")
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(sep and key in KEYS and key not in values, "unknown, malformed or duplicate runtime setting")
        values[key] = value
    require(values.keys() == KEYS, "incomplete runtime settings")
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


def render(values, release):
    # Compose interpolates the invoking shell before an env file. Pass only reviewed inputs.
    env = {k: os.environ[k] for k in ("PATH", "HOME", "DOCKER_CONFIG", "DOCKER_HOST") if k in os.environ}
    env.update(values, RELEASE_SHA=release["revision"], BACKEND_IMAGE=release["image"])
    return subprocess.check_output(
        ["docker", "compose", "--env-file", "/dev/null", "-p", "hookah_production",
         "-f", str(PROFILE / "compose.yaml"), "config", "--format", "json"], env=env)


def source_manifest():
    files = ROOT / "backend/app/src/main/resources/db/migration/postgresql"
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as jar:
        for sql in files.glob("V*__*.sql"):
            jar.write(sql, "db/migration/postgresql/" + sql.name)
    rows = manifest(archive.getvalue())
    require(len(rows) == 143, "pilot requires approved V1–V143 schema")
    return rows


def verify_published(release):
    run_id = str(release["ci_run"])
    data = json.loads(subprocess.check_output(["gh", "api", f"repos/{REPO}/actions/runs/{run_id}"]))
    require(data["id"] == release["ci_run"] and data["repository"]["full_name"] == REPO
            and data["head_repository"]["full_name"] == REPO and data["event"] == "push"
            and data["head_branch"] == "main" and data["head_sha"] == release["revision"]
            and data["path"] == ".github/workflows/ci.yml" and data["status"] == "completed"
            and data["conclusion"] == "success", "successful exact main CI required")
    name = f"ghcr-candidate-{release['revision']}-{data['run_attempt']}"
    with tempfile.TemporaryDirectory() as folder:
        subprocess.run(["gh", "run", "download", run_id, "--repo", REPO, "-n", name, "-D", folder], check=True)
        require(candidate(Path(folder) / "candidate.json") == release, "published candidate differs")


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


def verify_images(values, release):
    for ref in (values["POSTGRES_IMAGE"], release["image"]):
        inspect = ["docker", "image", "inspect", "--platform", "linux/amd64", ref]
        cached = subprocess.run(inspect, capture_output=True)
        if cached.returncode:
            subprocess.run(["docker", "pull", "--quiet", "--platform", "linux/amd64", ref], check=True)
            raw = subprocess.check_output(inspect)
        else:
            raw = cached.stdout
        info = json.loads(raw)[0]
        digests = [s.removeprefix("docker.io/library/") for s in info["RepoDigests"]]
        require(ref in digests and (info["Os"], info["Architecture"]) == ("linux", "amd64"), "image identity mismatch")
        if ref == release["image"]:
            labels = info["Config"]["Labels"]
            require(labels.get("org.opencontainers.image.source") == SOURCE
                    and labels.get("org.opencontainers.image.revision") == release["revision"]
                    and not info["Config"].get("Volumes"), "unexpected backend image")
    probe = ["docker", "run", "--rm", "--network", "none", "--read-only", "--platform", "linux/amd64"]
    # Override the official image's data volume with disposable tmpfs.
    pg_version = subprocess.check_output(probe + ["--tmpfs", "/var/lib/postgresql/data", "--entrypoint", "postgres",
                                                   values["POSTGRES_IMAGE"], "--version"], text=True)
    require(re.fullmatch(r"postgres \(PostgreSQL\) 17\.\d+[^\n]*\n?", pg_version), "PostgreSQL 17 required")
    jar = subprocess.check_output(probe + ["--entrypoint", "cat", release["image"], "/app/app/lib/app-0.1.0.jar"])
    with zipfile.ZipFile(io.BytesIO(jar)) as package:
        require("com/hookah/platform/backend/ProductionTrafficGate.class" in package.namelist(),
                "backend does not support process-bound production admission")
    require(manifest(jar) == source_manifest(), "packaged migrations differ from approved V1–V143")


def check_history(raw, allowed=(143,)):
    rows = json.loads(raw)
    require(isinstance(rows, list) and all(r.get("success") is True for r in rows), "unsuccessful Flyway history")
    actual = [{k: row[k] for k in ("version", "script", "checksum")} for row in rows]
    expected = history_for(source_manifest())
    require(len(actual) in allowed and actual == expected[:len(actual)], "Flyway versions, scripts or checksums differ")


def compare_update(previous, desired):
    old = json.loads(previous.read_bytes())
    new = json.loads(desired)
    previous_sha = old["services"]["backend"]["environment"]["APP_VERSION"]
    require(isinstance(previous_sha, str) and re.fullmatch("[0-9a-f]{40}", previous_sha), "invalid previous revision")
    for doc in (old, new):
        doc.pop("x-hookah-transition", None)
        doc.pop("x-hookah-schema", None)
        backend = doc["services"]["backend"]
        del backend["image"], backend["environment"]["APP_VERSION"]
    require(old == new, "update permits only the backend digest and revision to change")
    return previous_sha


def release_identity(doc):
    service = doc["services"]["backend"]
    sha, image = service["environment"]["APP_VERSION"], service["image"]
    require(re.fullmatch("[0-9a-f]{40}", sha) and re.fullmatch(IMAGE_RE, image), "invalid release identity")
    return {"revision": sha, "image": image}


def confirm_transition(previous, desired):
    old, new = json.loads(previous.read_bytes()), json.loads(desired)
    compare_update(previous, desired)
    require(plan_schema(old) <= plan_schema(new) == 143, "unapproved schema transition")
    predecessor, target = release_identity(old), release_identity(new)
    require(predecessor["revision"] != target["revision"] and predecessor["image"] != target["image"]
            and new.get("x-hookah-transition") == {"from": predecessor, "to": target}, "update lacks this admitted forward transition")


def check_target_image(plan):
    identity = release_identity(json.loads(plan.read_bytes()))
    image = json.loads(subprocess.check_output(
        ["docker", "image", "inspect", "--platform", "linux/amd64", identity["image"]]))[0]
    labels = image["Config"].get("Labels") or {}
    require(identity["image"] in image["RepoDigests"] and (image["Os"], image["Architecture"]) == ("linux", "amd64")
            and labels.get("org.opencontainers.image.source") == SOURCE
            and labels.get("org.opencontainers.image.revision") == identity["revision"],
            "target image no longer matches the admitted revision and digest")


def verify_forward(previous, desired):
    compare = json.loads(subprocess.check_output(["gh", "api", f"repos/{REPO}/compare/{previous}...{desired}"]))
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


def verify_running(plan, *, database_only=False):
    doc = json.loads(plan.read_bytes())
    compose = ["docker", "compose", "-p", "hookah_production", "--env-file", "/dev/null", "-f", str(plan)]
    hashes = dict(line.split() for line in subprocess.check_output(compose + ["config", "--hash", "*"],
                                                                 text=True).splitlines())
    require(doc["name"] == "hookah_production" and set(hashes) == {"postgres", "backend"}, "wrong topology")
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
        nets = set(data["NetworkSettings"]["Networks"])
        if service == "postgres":
            require(re.fullmatch(r"postgres@sha256:[0-9a-f]{64}", ref) and
                    data["State"].get("Health", {}).get("Status") == "healthy", "PostgreSQL is not healthy or pinned")
            postgres_id = data["Id"]
            volumes = [m for m in data["Mounts"] if m["Destination"] == "/var/lib/postgresql/data"]
            require(len(volumes) == 1 and volumes[0]["Type"] == "volume"
                    and volumes[0]["Name"] == "hookah_production_pgdata" and volumes[0]["RW"]
                    and nets == {"hookah_production_database"} and not data["HostConfig"]["PortBindings"],
                    "unexpected database binding")
        else:
            require(data["State"].get("Health", {}).get("Status", "healthy") == "healthy"
                    and nets == {"hookah_production_database", "hookah_production_outbound"}
                    and data["HostConfig"]["PortBindings"] == {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]},
                    "unexpected backend network binding")

    return postgres_id


def pg_query(cid, user, sql):
    return subprocess.check_output(
        ["docker", "exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", "hookah_prod"],
        input=sql, text=True).strip()


def plan_schema(document):
    version = document.get("x-hookah-schema", 142)
    require(type(version) is int and version in (142, 143), "unapproved plan schema")
    return version


def database_identity(plan, transition_from=None):
    cid = verify_running(plan, database_only=True)
    network = json.loads(subprocess.check_output(["docker", "network", "inspect", "hookah_production_database"]))[0]
    volume = json.loads(subprocess.check_output(["docker", "volume", "inspect", "hookah_production_pgdata"]))[0]
    require(network["Internal"] and cid in network["Containers"] and volume["Driver"] == "local" and not volume.get("Options")
            and all((r.get("Labels") or {}).get("com.docker.compose.project") == "hookah_production"
                    for r in (network, volume)), "database network or volume is not project-owned")
    identity = pg_query(cid, "postgres", "SELECT system_identifier, oid FROM pg_control_system(), pg_database "
                        "WHERE datname=current_database() AND current_database()='hookah_prod' "
                        "AND pg_get_userbyid(datdba)='hookah_app' AND current_setting('server_version_num')::int/10000=17;")
    require(re.fullmatch(r"[1-9][0-9]*\|[1-9][0-9]*", identity), "unexpected PostgreSQL database identity")
    allowed = [plan_schema(json.loads(plan.read_bytes()))]
    if transition_from is not None:
        allowed.append(plan_schema(json.loads(transition_from.read_bytes())))
    check_history(pg_query(cid, "hookah_app", (PROFILE / "history.sql").read_text()), allowed)
    return {"container": cid, "cluster_and_db": identity, "network": network["Id"],
            "volume": volume["Name"], "volume_created": volume["CreatedAt"]}


def verified_database(plan, recipient=None, transition_from=None):
    record = json.loads((plan.parent / "postgres-binding.json").read_bytes())
    expected_recipient = record.pop("recipient")
    require(recipient is None or recipient == expected_recipient, "backup recipient differs from enrolled recovery key")
    observed = database_identity(plan, transition_from)
    require(observed == record, "PostgreSQL differs from installation binding")
    return observed["container"]


def enroll_database(plan, recipient):
    require(re.fullmatch(r"age1[ac-hj-np-z02-9]{58}", recipient), "a native public age recipient is required")
    subprocess.run(["age", "-r", recipient], input=b"", stdout=subprocess.DEVNULL, check=True)
    record = dict(database_identity(plan), recipient=recipient)
    with tempfile.NamedTemporaryFile(dir=plan.parent) as f:
        f.write(json.dumps(record).encode())
        f.flush()
        os.fsync(f.fileno())
        os.link(f.name, plan.parent / "postgres-binding.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    actions.add_parser("history", help="verify ordered Flyway JSON on stdin")
    actions.add_parser("running", help="check live image, configuration and bindings").add_argument("plan", type=Path)
    for name in ("bind-db", "backup-db"):
        cmd = actions.add_parser(name)
        cmd.add_argument("plan", type=Path)
        cmd.add_argument("recipient")
    for name in ("check", "admit"):
        cmd = actions.add_parser(name)
        cmd.add_argument("runtime", type=Path)
        cmd.add_argument("candidate", type=Path)
        if name == "admit":
            cmd.add_argument("output", type=Path)
            cmd.add_argument("--previous", type=Path, help="current admitted Compose file for an update")
    args = parser.parse_args()
    if args.action == "history":
        check_history(sys.stdin.buffer.read())
    elif args.action == "running":
        verify_running(args.plan)
    elif args.action == "bind-db":
        enroll_database(args.plan, args.recipient)
    elif args.action == "backup-db":
        print(verified_database(args.plan, args.recipient))
        return
    else:
        values = runtime(args.runtime)
        release = candidate(args.candidate)
        document = render(values, release)
        if args.action == "admit":
            plan = json.loads(document)
            plan["x-hookah-schema"] = 143
            document = json.dumps(plan).encode()
            if args.previous:
                previous_sha = compare_update(args.previous, document)
                verify_forward(previous_sha, release["revision"])
                plan = json.loads(document)
                plan["x-hookah-transition"] = {"from": release_identity(json.loads(args.previous.read_bytes())),
                                              "to": release_identity(plan)}
                document = json.dumps(plan).encode()
                confirm_transition(args.previous, document)
            verify_published(release)
            verify_images(values, release)
            verify_bot(values)
            # Publish a complete private plan once, after all checks; never overwrite a running plan.
            with tempfile.NamedTemporaryFile(dir=args.output.parent) as tmp:
                tmp.write(document)
                tmp.flush()
                os.fsync(tmp.fileno())
                os.link(tmp.name, args.output)
    print("OK", args.action)


if __name__ == "__main__":
    try:
        main()
    except (Refused, ValueError, KeyError, OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit(f"production profile refused: {exc}") from None
