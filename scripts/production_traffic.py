"""Operate the durable production traffic gate on the production Docker/Caddy host."""
import argparse
from contextlib import ExitStack, contextmanager
import hashlib
import io
import json
import os
import re
import secrets
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
import zipfile

import production_host as host
import production_profile as profile
from production_profile import PRODUCTION_PROJECT, ProductionWorker, GATE, IMAGE_RE, PROFILE, SOURCE, Refused, check_target_image, cluster_identity, confirm_transition, matches_running_image, pg_query, plan_schema, require, verified_database, verify_running

sys.path.insert(0, str(PROFILE))
from smoke import image_assets, probe

def compose(plan, *args):
    return subprocess.check_output(["docker", "compose", "--env-file", "/dev/null", "-p", PRODUCTION_PROJECT,
                                    "-f", str(plan), *args], text=True,
                                   **({} if args[0] in ("ps", "config") else host.lock_args())).strip()


def start_release(plan):
    if "pdf-worker" in json.loads(plan.read_bytes())["services"]:
        compose(plan, "up", "-d", "--pull", "never", "--no-deps", "--wait", "--wait-timeout", "120", "pdf-worker")
    compose(plan, "up", "-d", "--pull", "never", "--no-deps", "backend")


def backend(plan):
    return json.loads(plan.read_bytes())["services"]["backend"]


def flush_dir():
    fd = os.open(GATE.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def state(old, new, cid=None):
    verified_database(new, transition_from=old)
    cid = cid or compose(new, "ps", "-aq", "backend")
    if not cid:
        return "absent"
    c = json.loads(subprocess.check_output(["docker", "inspect", cid]))[0]
    for label, plan in (("OLD", old), ("NEW", new)):
        b = backend(plan)
        ver = "APP_VERSION=" + b["environment"]["APP_VERSION"]
        if c["Config"]["Image"] != b["image"] or ver not in c["Config"]["Env"]:
            continue
        image = json.loads(subprocess.check_output(["docker", "image", "inspect", "--platform", "linux/amd64", b["image"]]))[0]
        labels = c["Config"]["Labels"]
        expected = dict(line.split() for line in compose(plan, "config", "--hash", "backend").splitlines())
        image_labels = image["Config"].get("Labels") or {}
        if not (re.fullmatch(IMAGE_RE, b["image"]) and image_labels.get("org.opencontainers.image.source") == SOURCE
                and image_labels.get("org.opencontainers.image.revision") == b["environment"]["APP_VERSION"]):
            return "unknown"
        if (c["Name"] != f"/{PRODUCTION_PROJECT}-backend-1" or cid not in (c["Id"], c["Id"][:12])
                or labels.get("com.docker.compose.container-number") != "1"
                or labels.get("com.docker.compose.oneoff", "").lower() != "false"):
            return "unknown"
        binds = {(m["Source"], m["Destination"], m["RW"]) for m in c["Mounts"] if m["Type"] == "bind"}
        expected_binds = {(m["source"], m["target"], not m.get("read_only", False))
                          for m in b.get("volumes", []) if m["type"] == "bind"}
        volumes = [(m.get("Name"), m.get("Destination"), m.get("RW"), m.get("SubPath", ""))
                   for m in c["Mounts"] if m["Type"] == "volume"]
        expected_volumes = [(f"{PRODUCTION_PROJECT}_pdf_ipc", "/run/hookah-pdf", False, "")] if b["environment"].get("VENUE_INFO_PDF_WORKER_ENABLED") == "true" else []
        if binds != expected_binds or volumes != expected_volumes:
            return "unknown"
        if (matches_running_image(c, b["image"], image) and labels.get("com.docker.compose.project") == PRODUCTION_PROJECT
                and labels.get("com.docker.compose.service") == "backend"
                and labels.get("com.docker.compose.project.working_dir") == str(plan.resolve().parent)
                and labels.get("com.docker.compose.config-hash") == expected["backend"]):
            nets = {f"{PRODUCTION_PROJECT}_database", f"{PRODUCTION_PROJECT}_outbound"}
            if set(c["NetworkSettings"]["Networks"]) != nets:
                return "unknown"
            if c["HostConfig"]["PortBindings"] != {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]}:
                return "unknown"
            status = c["State"]["Status"]
            if status in ("created", "exited", "running"):
                return label + (" running" if status == "running" else " stopped")
    return "unknown"


def stopped_backend(old, new):
    cid = compose(new, "ps", "--no-trunc", "-aq", "backend")
    require(re.fullmatch("[0-9a-f]{64}", cid) and state(old, new, cid) in ("OLD stopped", "NEW stopped"),
            "rollback requires the identified stopped OLD or NEW backend")
    return cid


def rollback_worker(old, new):
    cid = compose(new, "ps", "--no-trunc", "-aq", "pdf-worker")
    require(re.fullmatch("[0-9a-f]{64}", cid), "expected exactly one installed PDF worker")
    data = json.loads(subprocess.check_output(["docker", "inspect", cid]))[0]
    labels = data["Config"]["Labels"]
    require(data["Id"] == cid and data["Name"] == f"/{PRODUCTION_PROJECT}-pdf-worker-1"
            and labels.get("com.docker.compose.project") == PRODUCTION_PROJECT
            and labels.get("com.docker.compose.service") == "pdf-worker"
            and labels.get("com.docker.compose.container-number") == "1"
            and labels.get("com.docker.compose.oneoff", "").lower() == "false", "foreign PDF worker")
    ref = data["Config"]["Image"]
    for plan in (old, new):
        worker = json.loads(plan.read_bytes())["services"]["pdf-worker"]
        if worker["image"] != ref or backend(plan)["image"] != ref:
            continue
        image = check_target_image(plan)
        hashes = dict(line.split() for line in compose(plan, "config", "--hash", "pdf-worker").splitlines())
        require(matches_running_image(data, ref, image) and labels.get("com.docker.compose.config-hash") == hashes["pdf-worker"]
                and labels.get("com.docker.compose.project.working_dir") == str(plan.resolve().parent)
                and data["State"]["Status"] in ("created", "exited", "running", "restarting"), "PDF worker differs from plan")
        admission = ProductionWorker(plan)
        admission.pdf_mount(data["Mounts"], writable=True)
        admission.pdf_host_mount(data["HostConfig"], writable=True)
        return cid, plan
    raise Refused("installed PDF worker does not belong to either admitted release")


def check_gate_binary(plan):
    jar = subprocess.check_output(["docker", "run", "--rm", "--cgroup-parent", host.SLICE, "--pull", "never", "--platform", "linux/amd64",
                                   "--network", "none", "--read-only", "--entrypoint", "cat", backend(plan)["image"],
                                   "/app/app/lib/app-0.1.0.jar"], **host.lock_args())
    with zipfile.ZipFile(io.BytesIO(jar)) as package:
        gate_class = package.read("com/hookah/platform/backend/ProductionTrafficGate.class")
        require(b"recovery-hold" in gate_class and b"telegramDispatchAllowed" in gate_class,
                "backend lacks the current Telegram admission fence")


def check_edge_closed():
    headers = subprocess.check_output(
        ["curl", "--silent", "--show-error", "--max-time", "5", "--noproxy", "*", "--resolve",
         "hookahtootah.club:443:127.0.0.1", "-D", "-", "-o", "/dev/null", "-w", "%{http_code}",
         "https://hookahtootah.club/miniapp/"], text=True)
    require(headers.endswith("503") and "x-hookah-maintenance: closed" in headers.lower().splitlines(),
            "local HTTPS edge did not confirm maintenance")


def check_edge_open(sha, instance, polling):
    base = ["curl", "--silent", "--show-error", "--max-time", "5", "--noproxy", "*", "--resolve",
            "hookahtootah.club:443:127.0.0.1"]
    raw = subprocess.check_output(base + ["-w", "\n%{http_code}", "https://hookahtootah.club/version"], text=True)
    body, _, status = raw.rpartition("\n")
    require(status == "200", "public version endpoint unavailable")
    version = json.loads(body)
    require((version.get("service"), version.get("env"), version.get("version"), version.get("instance"),
             version.get("polling")) == ("backend", "prod", sha, instance, polling), "unexpected public backend")
    status = subprocess.check_output(base + ["-o", "/dev/null", "-w", "%{http_code}",
                                            "https://hookahtootah.club/api/guest/catalog"], text=True)
    require(status == "401", "public guest API did not enforce authentication")


def recovery_scratch(*, create=False):
    directory = GATE.with_name(".recovery-write")
    if create:
        directory.mkdir(mode=0o700, exist_ok=True)
    st = directory.lstat()
    require(stat.S_ISDIR(st.st_mode) and st.st_uid == os.geteuid() and stat.S_IMODE(st.st_mode) == 0o700
            and st.st_dev == GATE.parent.stat().st_dev, "untrusted recovery write directory")
    return directory


def publish(path, value, mode=0o644):
    directory = GATE.parent
    if path == GATE.with_name("recovery-hold"):
        host.lock_args()  # Validate both leases before publishing or retrying a recovery decision.
        # A killed writer can leave its unique file here. Each retry recomputes the
        # decision and exclusively creates a fresh file; no abandoned bytes are adopted.
        directory = recovery_scratch(create=True)
    with tempfile.NamedTemporaryFile(dir=directory, delete=False) as f:
        tmp = Path(f.name)
        try:
            f.write((value + "\n").encode())
            f.flush()
            os.fchmod(f.fileno(), mode)
            os.fsync(f.fileno())
            os.replace(tmp, path)
            flush_dir()
        finally:
            tmp.unlink(missing_ok=True)


def record_backlog():
    latch = GATE.with_name("telegram-backlog")
    try:
        if not os.path.lexists(latch):
            publish(latch, secrets.token_hex(32), 0o600)
    except OSError as error:
        raise Refused("cannot persist Telegram backlog; stop admission and reconcile with owner") from error


def await_readiness(sha, assets, instance=None, polling=None, *, recovery=False):
    deadline = time.monotonic() + 120
    while True:
        try:
            ver = probe(sha, assets, recovery=True, on_backlog=record_backlog) if recovery else probe(sha, assets)
            if recovery and ver.get("polling") == "backlog":
                raise Refused("Telegram backlog requires an owner decision; pending updates were not acknowledged")
            current = ver.get("instance")
            require(isinstance(current, str) and re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", current),
                    "backend lacks a production JVM identity")
            require(instance is None or current == instance, "serving JVM changed during admission")
            if polling is None or ver.get("polling") == polling:
                return current
        except (ValueError, OSError):
            if recovery and os.path.lexists(GATE.with_name("telegram-backlog")):
                raise Refused("Telegram backlog requires an owner decision; readiness failed") from None
        require(time.monotonic() < deadline, "readiness failed; traffic remains closed")
        time.sleep(.2)


RECOVERY_SCOPES = {"owners_and_grants", "users", "orders", "bookings", "billing", "pending_effects"}


def begin_recovery(args, hold):
    require(re.fullmatch("[0-9a-f]{64}", args.point_sha256), "custodian manifest digest required")
    require(not os.path.lexists(GATE) and not os.path.lexists(GATE.with_name("activate")), "close ingress first")
    intent = GATE.with_name(".install-intent")
    require(not os.path.lexists(intent) or read_install(intent)["mode"] != "install-successor",
            "clean successor cannot be converted into a recovery attempt")
    require(not compose(args.plan, "ps", "-aq", "backend"), "begin before creating the replacement backend")
    # Record the hold before the first database probe. A failed or interrupted probe leaves ingress closed.
    record = {"source_manifest": args.point_sha256}
    if os.path.lexists(hold):
        require(json.loads(hold.read_bytes()) == record, "different or already enrolled recovery")
    else:
        publish(hold, json.dumps(record))
    GATE.with_name("telegram-recovery-cleared").unlink(missing_ok=True)
    flush_dir()
    check_target_image(args.plan)
    check_gate_binary(args.plan)
    db = cluster_identity(args.plan)
    cid = db["container"]
    require(pg_query(cid, "postgres", "SELECT NOT EXISTS (SELECT FROM pg_class c JOIN pg_namespace n "
                     "ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','pg_toast','information_schema')) "
                     "AND NOT EXISTS (SELECT FROM pg_largeobject_metadata)") == "t",
            "replacement requires a freshly initialized database before migrations")
    record["plan_sha256"] = hashlib.sha256(args.plan.read_bytes()).hexdigest()
    record["target"] = db
    record["challenge"] = secrets.token_hex(32)
    publish(hold, json.dumps(record, sort_keys=True))
    print("Replacement enrolled. Ingress held; DB identity", db["cluster_and_db"], "review challenge", record["challenge"])


def owner_review_bytes(args):
    mode = args.review.lstat()
    require(stat.S_ISREG(mode.st_mode) and mode.st_uid == os.geteuid() and not mode.st_mode & 0o077,
            "private operator review required")
    raw = args.review.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == args.review_sha256, "review differs from owner's approved digest")
    return raw


def owner_review(args):
    review = json.loads(owner_review_bytes(args))
    require(isinstance(review, dict), "expected an owner decision document")
    return review


def reconcile_backlog(args, hold):
    require(not os.path.lexists(GATE) and not os.path.lexists(GATE.with_name("activate")), "close ingress first")
    check_edge_closed()
    record = json.loads((hold if hold.exists() else GATE.with_name("recovery-accepted")).read_bytes())
    require(cluster_identity(args.plan) == record["target"], "different replacement database")
    latch = GATE.with_name("telegram-backlog")
    challenge = latch.read_text().strip()
    review = owner_review(args)
    require(re.fullmatch("[0-9a-f]{64}", challenge) and review.get("decision") == "TELEGRAM_BACKLOG_RECONCILED"
            and review.get("challenge") == challenge and review.get("source_manifest") == record["source_manifest"]
            and review.get("cluster_and_db") == record["target"]["cluster_and_db"]
            and isinstance(review.get("evidence_sha256"), str) and re.fullmatch("[0-9a-f]{64}", review["evidence_sha256"]), "stale or incomplete backlog review")
    # Only release the recorded incident. No clearance, Telegram request or offset is issued here.
    latch.unlink()
    flush_dir()


def accept_recovery(args, hold):
    require(not os.path.lexists(GATE) and not os.path.lexists(GATE.with_name("activate")), "recovery requires closed ingress")
    check_edge_closed()
    record = json.loads(hold.read_bytes())
    if "challenge" not in record and "target" in record:
        record.pop("first_open_lsn", None)
        record["challenge"] = secrets.token_hex(32)
        publish(hold, json.dumps(record, sort_keys=True))
        raise Refused("renew the owner decision using the challenge in recovery-hold")
    require(hashlib.sha256(args.plan.read_bytes()).hexdigest() == record.get("plan_sha256"),
            "use the enrolled replacement plan")
    require(state(args.plan, args.plan) == "OLD running", "start the exact replacement behind the recovery hold")
    verify_running(args.plan)
    cid = verified_database(args.plan)
    require(cluster_identity(args.plan) == record["target"], "replacement database changed")
    review = owner_review(args)
    lsn = pg_query(cid, "postgres", (PROFILE / "recovery-admission.sql").read_text())
    require(re.fullmatch(r"[0-9A-F]{1,8}/[0-9A-F]{1,8}", lsn), "existing media, publication, grants or tokens block recovery")
    require(isinstance(review, dict) and review.get("decision") == "ACCEPT_RECONCILED_REPLACEMENT"
            and review.get("challenge") == record["challenge"]
            and review.get("source_manifest") == record["source_manifest"] and review.get("plan_sha256") == record["plan_sha256"]
            and review.get("cluster_and_db") == record["target"]["cluster_and_db"] and review.get("wal_lsn") == lsn,
            "review is incomplete, stale or belongs to a different recovery")
    evidence = review.get("evidence")
    require(isinstance(evidence, dict) and set(evidence) == RECOVERY_SCOPES
            and all(isinstance(v, str) and re.fullmatch("[0-9a-f]{64}", v) for v in evidence.values()),
            "business and authority reconciliation evidence required")
    # Caddy's file matcher needs a readable marker; only binding metadata and digests go here.
    # Recording approval alone never makes even loopback application requests available.
    record["review_sha256"] = args.review_sha256
    record["first_open_lsn"] = lsn
    publish(hold, json.dumps(record, sort_keys=True))


def install_inventory(plan, doc, allowed, images):
    """Identify even stopped containers and allocated resources before adopting an interrupted up."""
    project = PRODUCTION_PROJECT
    label = "label=com.docker.compose.project=" + project

    def lines(*args):
        return subprocess.check_output(["docker", *args], text=True).splitlines()

    def inspect(*args):
        data, = json.loads(subprocess.check_output(["docker", *args]))
        return data

    hashes = dict(line.split() for line in compose(plan, "config", "--hash", "*").splitlines())
    found = {}
    running = set()
    owned_ids = set()
    attached_networks = {}
    ids = set(lines("ps", "-aq", "--no-trunc", "--filter", label))
    # A reserved name with foreign labels must not be handed to Compose.
    for line in lines("ps", "-a", "--no-trunc", "--format", "{{.ID}} {{.Names}}"):
        cid, name = line.split()
        if name.startswith(project + "-") or name.startswith(project + "_"):
            require(cid in ids, "foreign container occupies the production namespace")
    for cid in ids:
        c = inspect("inspect", cid)
        labels = c["Config"]["Labels"]
        service = labels.get("com.docker.compose.service")
        require(service in allowed and service not in found and re.fullmatch("[0-9a-f]{64}", cid) and
                c["Id"] == cid and c["Name"] == f"/{project}-{service}-1" and
                labels.get("com.docker.compose.project") == project and
                labels.get("com.docker.compose.container-number") == "1" and
                labels.get("com.docker.compose.oneoff", "").lower() == "false" and
                labels.get("com.docker.compose.project.working_dir") == str(plan.resolve().parent) and
                labels.get("com.docker.compose.project.config_files") == str(plan.resolve()) and
                labels.get("com.docker.compose.config-hash") == hashes[service],
                "unrecognized partial installation container")
        config = doc["services"][service]
        require(matches_running_image(c, config["image"], images[config["image"]]), "partial container image mismatch")
        actual = {(m["Type"], m["Destination"], m["Source"] if m["Type"] == "bind" else m.get("Name"), m["RW"])
                  for m in c["Mounts"] if m["Type"] in ("bind", "volume")}
        expected = {(m["type"], m["target"], m["source"] if m["type"] == "bind" else doc["volumes"][m["source"]]["name"],
                     not m.get("read_only", False)) for m in config.get("volumes", []) if m["type"] in ("bind", "volume")}
        require(actual == expected, "partial container storage differs from plan")
        limits = c["HostConfig"]
        memory, cpu = host.LIMITS[service]
        ports = {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]} if service == "backend" else {}
        require((limits.get("PortBindings") or {}) == ports and limits.get("CgroupParent") == host.SLICE and
                limits.get("Memory") == limits.get("MemorySwap") == memory * host.MIB and
                limits.get("NanoCpus") == cpu and (service != "pdf-worker" or limits.get("NetworkMode") == "none"),
                "partial container ports or isolation differ from plan")
        networks = c["NetworkSettings"]["Networks"]
        expected_networks = ({"none"} if service == "pdf-worker" else
                             {doc["networks"][key]["name"] for key in config["networks"]})
        require(isinstance(networks, dict) and set(networks) == expected_networks
                and limits.get("NetworkMode") in expected_networks,
                "partial container network attachments differ from plan: " + service)
        for name, endpoint in networks.items():
            if name != "none":
                attached_networks.setdefault(name, set()).add(endpoint.get("NetworkID") or "")
        found[service] = cid
        owned_ids.add(cid)
        if c["State"]["Running"]:
            running.add(service)
    for kind in ("volume", "network"):
        declared = {v["name"]: key for key, v in doc[kind + "s"].items()}
        tagged = set(lines(kind, "ls", "--filter", label, "--format", "{{.Name}}"))
        named = set(lines(kind, "ls", "--format", "{{.Name}}"))
        require(tagged == named.intersection(declared) and (allowed or not tagged), "unowned production " + kind)
        if kind == "network":
            require(attached_networks.keys() <= tagged, "container uses a network outside the production project")
        for name in tagged:
            data = inspect(kind, "inspect", name)
            key = declared[name]
            labels = data.get("Labels") or {}
            require(data["Name"] == name and labels.get("com.docker.compose.project") == project and
                    labels.get("com.docker.compose." + kind) == key and
                    (data.get("Options") or {}) == doc[kind + "s"][key].get("driver_opts", {}),
                    "unexpected production " + kind)
            if kind == "network":
                require(data["Driver"] == "bridge" and data["Internal"] == doc["networks"][key].get("internal", False)
                        and set(data.get("Containers") or {}) <= owned_ids and
                        attached_networks.get(name, set()) <= {"", data["Id"]}, "unexpected network attachment")
                identity = data["Id"]
            else:
                require(data["Driver"] == "local" and set(lines("ps", "-aq", "--no-trunc", "--filter", "volume=" + name))
                        <= owned_ids, "unexpected volume attachment")
                identity = [data["CreatedAt"], data["Mountpoint"]]
            found[kind + "/" + key] = identity
    return found, running


def save_install(record):
    # Fixed scratch name makes a kill before rename safely retryable under both locks.
    path = GATE.with_name(".install-intent")
    tmp = path.with_suffix(".pending")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    with os.fdopen(fd, "w") as f:
        st = os.fstat(fd)
        require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() and st.st_nlink == 1 and
                not st.st_mode & 0o077, "invalid installation scratch file")
        f.truncate(0)
        json.dump(record, f)
        f.flush()
        os.fsync(fd)
    os.replace(tmp, path)
    flush_dir()


def read_install(path):
    st = path.lstat()
    require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() and st.st_nlink == 1 and not st.st_mode & 0o077,
            "invalid installation record")
    return json.loads(path.read_bytes())


def install_target(plan):
    daemon = subprocess.check_output(["docker", "info", "--format", "{{.ID}}"], text=True).strip()
    require(daemon, "unknown Docker daemon")
    return {"daemon": daemon, "path": str(plan.resolve()), "sha256": hashlib.sha256(plan.read_bytes()).hexdigest()}


def check_retired_project():
    # A stopped archive can remain on this daemon if Docker cannot restart it.
    candidates = set()
    for selector in ("label=com.docker.compose.project=hookah-production", "name=^/hookah-production([-_].*|$)"):
        candidates.update(subprocess.check_output(["docker", "ps", "-aq", "--no-trunc", "--filter", selector],
                                                  text=True).splitlines())
    for cid in candidates:
        require(re.fullmatch("[0-9a-f]{64}", cid), "unknown predecessor container")
        state = subprocess.check_output(["docker", "inspect", "--format",
                                         "{{.State.Status}} {{.HostConfig.RestartPolicy.Name}}", cid], text=True)
        require(state.strip() == "exited no", "historical production is active or can restart")


def successor_database_sql(pristine):
    return (r"\set pristine " + ("true" if pristine else "false") + "\n" +
            (PROFILE / "clean-successor.sql").read_text())


def check_successor_database(cid, *, pristine):
    sql = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n" + successor_database_sql(pristine)
    require(pg_query(cid, "postgres", sql + "\nCOMMIT;") == "t",
            "successor database contains unexpected data, objects or authority")


@contextmanager
def successor_binding_window(cid):
    # Wait out earlier writes, then take the validation snapshot. Keep every application
    # table write-locked until the binding is durable; connection loss releases the locks.
    locks = r"""BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL search_path=pg_catalog;
SELECT format('LOCK TABLE %s IN SHARE MODE', oid::regclass) FROM pg_catalog.pg_class
WHERE relnamespace='public'::regnamespace AND relkind IN ('r','p') ORDER BY oid \gexec
"""
    with subprocess.Popen(["docker", "exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1",
                           "-U", "postgres", "-d", "hookah_prod"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          text=True, **host.lock_args()) as session:
        try:
            session.stdin.write(locks + successor_database_sql(False) + "\n")
            session.stdin.flush()
            require(session.stdout.readline().strip() == "t", "successor no longer clean at binding")
            yield
        finally:
            session.stdin.close()  # EOF rolls back the lock-only transaction.
        require(session.wait() == 0, "successor validation session failed")


def verify_retirement(args, target):
    def unique_fields(pairs):
        fields = dict(pairs)
        require(len(fields) == len(pairs), "duplicate field in successor decision")
        return fields

    raw = owner_review_bytes(args)
    review = json.loads(raw, object_pairs_hook=unique_fields)
    fields = {"target", "decision", "predecessor", "data_disposition", "owner", "custodian",
              "history", "custodian_key", "retirement_evidence_sha256"}
    require(isinstance(review, dict) and set(review) == fields, "incomplete retirement approval")
    require(review["target"] == target, "different successor target")
    evidence = review["retirement_evidence_sha256"]
    require(review["decision"] == "RETIRE_AND_ENROLL_CLEAN_SUCCESSOR" and review["predecessor"] == "hookah-production" and
            review["data_disposition"] == "RETAIN_ARCHIVES_AND_RECEIPTS_WITHOUT_RESTORE" and
            all(isinstance(review[k], str) and review[k].strip() for k in ("owner", "custodian", "history")) and
            isinstance(evidence, str) and re.fullmatch("[0-9a-f]{64}", evidence), "incomplete retirement approval")
    key = review["custodian_key"]
    require(isinstance(key, str) and re.fullmatch(r"ssh-ed25519 [A-Za-z0-9+/]{68}", key),
            "owner must pin the custodian's Ed25519 public signing key")
    require(args.signature.is_file() and not args.signature.is_symlink(), "custodian signature file required")
    with tempfile.NamedTemporaryFile(mode="w") as signers:
        signers.write("custodian " + key + "\n")
        signers.flush()
        subprocess.run(["ssh-keygen", "-Y", "verify", "-f", signers.name, "-I", "custodian",
                        "-n", "hookah-tootah-clean-successor-v1", "-s", str(args.signature)], input=raw, check=True)


def successor_enrollment(args):
    plan = args.plan
    profile.verify_plan_pin(plan)
    pending = GATE.with_name(".install-intent")
    binding = plan.parent / "postgres-binding.json"
    require({p.name for p in GATE.parent.iterdir()} <=
            {".operator.lock", pending.name, pending.with_suffix(".pending").name}, "conflicting production decisions")
    record = read_install(pending)
    require("registered" not in record, "database already enrolled; retain the successor receipt")
    finish = os.path.lexists(binding)
    if finish:
        require(args.action == "bind-successor" and record.get("retirement_review_sha256") == args.review_sha256,
                "database already enrolled")
    installation = install_target(plan)
    require(record["mode"] == "install-successor" and record["target"] == installation,
            "different installation or daemon")
    check_edge_closed()
    check_retired_project()
    doc = json.loads(plan.read_bytes())
    images = {doc["services"]["backend"]["image"]: check_target_image(plan)}
    pg_image = doc["services"]["postgres"]["image"]
    images[pg_image] = profile.check_postgres_image(pg_image)
    resources, running = install_inventory(plan, doc, record["allowed"], images)
    require(resources == record["resources"] and running == set(doc["services"]), "successor installation changed")
    profile.check_recipient(args.recipient)
    pg = verify_running(plan)
    db = profile.database_identity(plan)
    require(record.get("pristine") == db and pg == db["container"] == record["resources"]["postgres"],
            "not the freshly initialized successor cluster")
    profile.require_empty_enrollment(pg)
    target = {"installation": installation, "release": profile.release_identity(doc),
              "database": db, "recipient": args.recipient}
    if args.action == "successor-target":
        check_successor_database(pg, pristine=False)
        print(json.dumps(target, sort_keys=True))
        return

    verify_retirement(args, target)
    # Seal the install intent before the no-replace binding. After interruption only this
    # exact countersigned decision can finish. Retain the intent as the host's receipt.
    require(record.get("retirement_review_sha256", args.review_sha256) == args.review_sha256,
            "different successor decision already recorded")
    record["retirement_review_sha256"] = args.review_sha256
    record["recipient"] = args.recipient
    expected_binding = dict(db, recipient=args.recipient, successor_review_sha256=args.review_sha256)
    with successor_binding_window(pg):
        require(profile.database_identity(plan) == db, "successor changed before binding publication")
        check_edge_closed()
        if finish:
            st = binding.lstat()
            require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() and not st.st_mode & 0o077 and
                    json.loads(binding.read_bytes()) == expected_binding,
                    "interrupted successor binding differs; refusing replacement")
            profile.sync_directory(plan.parent)
        else:
            save_install(record)
            profile.publish_binding(plan, expected_binding)
        record["registered"] = True
        save_install(record)


def install_closed(action, plan):
    profile.verify_plan_pin(plan)
    doc = json.loads(plan.read_bytes())
    release = doc["x-hookah-release-evidence"]["candidate"]
    identity = profile.release_identity(doc)
    require(all(release.get(k) == v for k, v in identity.items()), "plan differs from admitted candidate")
    pg = doc["services"]["postgres"]
    env = doc["services"]["backend"]["environment"]
    values = {k: env[k] for k in (profile.KEYS | profile.R2_CONNECTION_KEYS) if k in env}
    values.update(POSTGRES_IMAGE=pg["image"], POSTGRES_ADMIN_PASSWORD=pg["environment"]["POSTGRES_PASSWORD"],
                  PRODUCTION_MEDIA_ENABLED=env["VENUE_INFO_MEDIA_R2_ENABLED"])
    profile.check_media(values)
    require(json.loads(profile.render(values, release)) == {k: v for k, v in doc.items() if not k.startswith("x-hookah-")},
            "plan differs from the production Compose profile")
    if action != "close":
        require(not any(os.path.lexists(GATE.with_name(n)) for n in ("open", "activate")), "close production ingress first")
        check_edge_closed()
    if action == "install-successor":
        require("x-hookah-transition" not in doc and plan_schema(doc) == 143, "new admitted V143 plan required")
        check_retired_project()
    images = {identity["image"]: check_target_image(plan), pg["image"]: profile.check_postgres_image(pg["image"])}
    pending = GATE.with_name(".install-intent")
    hold = GATE.with_name("recovery-hold")
    binding = plan.parent / "postgres-binding.json"
    require(not os.path.lexists(binding), "database already enrolled; use normal production commands")
    target = install_target(plan)
    if os.path.lexists(pending):
        record = read_install(pending)
        require("registered" not in record or action == "close", "successor already registered; investigate the binding")
        require(record["target"] == target and (record["mode"] == action or action == "close" or
                action == "recovery-start" and record["mode"] == "install-db"), "different pending installation")
    else:
        require(action in ("install", "install-db", "install-successor") and set(p.name for p in GATE.parent.iterdir()) <=
                {".operator.lock", pending.with_suffix(".pending").name}, "existing production decisions")
        record = {"target": target, "mode": action, "allowed": [], "resources": {}}

    if action != "close":
        permitted = {".operator.lock", pending.name, pending.with_suffix(".pending").name}
        if record["mode"] == "install-db":
            permitted.add("recovery-hold")
            if os.path.lexists(GATE.with_name(".recovery-write")):
                permitted.add(recovery_scratch().name)
        require(all(p.name in permitted for p in GATE.parent.iterdir()), "conflicting production decisions")

    def observe():
        found, running = install_inventory(plan, doc, record["allowed"], images)
        require(all(found.get(k) == v for k, v in record["resources"].items()), "partial installation resource replaced")
        record["resources"] = found
        return running

    running = observe()
    if action == "close":
        GATE.unlink(missing_ok=True)
        GATE.with_name("activate").unlink(missing_ok=True)
        flush_dir()
        save_install(record)
        for service in ("backend", "pdf-worker", "postgres"):
            if service in running:
                cid = record["resources"][service]
                subprocess.run(["docker", "stop", cid], check=True, stdout=subprocess.DEVNULL, **host.lock_args())
        require(not observe(), "partial production installation did not stop")
        check_edge_closed()
        return
    require(action != "install" or not os.path.lexists(hold), "recovery enrollment required")
    require("backend" in running or not subprocess.check_output(["ss", "-Hlt", "sport = :18080"]).strip(),
            "production port already in use")
    host.verify_slice()
    allocation = host.bootstrap_space(plan, doc, record["resources"].get("volume/pgdata"))
    host.image_space(images, allocations=[allocation], reserve_release=True)
    if action == "recovery-start":
        require(json.loads(hold.read_bytes()).get("plan_sha256") == target["sha256"], "enroll the replacement database first")
    services = ["postgres"]
    if action != "install-db":
        if "pdf-worker" in doc["services"]:
            services.append("pdf-worker")
        services.append("backend")
    for service in services:
        profile.verify_plan_pin(plan)
        if action == "recovery-start" and service != "postgres":
            incident = json.loads(hold.read_bytes())
            require(incident.get("plan_sha256") == target["sha256"] and incident.get("target") == cluster_identity(plan),
                    "enroll the replacement database first")
        if service not in record["allowed"]:
            record["allowed"].append(service)
        save_install(record)
        wait = ["--wait", "--wait-timeout", "180" if service == "postgres" else "120"] if service != "backend" else []
        compose(plan, "up", "-d", "--no-recreate", "--pull", "never", "--no-deps", *wait, service)
        observe()
        require(service in record["resources"], "expected production container after up")
        save_install(record)
        if service == "postgres":
            cid = verify_running(plan, database_only=True)
            require(cid == record["resources"][service], "unexpected PostgreSQL container")
            host.verify_container(service, json.loads(subprocess.check_output(["docker", "inspect", cid]))[0])
            if action == "install-successor":
                db = cluster_identity(plan)
                require(db["container"] == cid, "different successor PostgreSQL container")
                if "pristine" not in record:
                    require(record["allowed"] == ["postgres"], "cannot establish pre-migration identity")
                    check_successor_database(cid, pristine=True)
                    record["pristine"] = db
                    save_install(record)
                require(record["pristine"] == db, "successor PostgreSQL identity changed")
    if action != "install-db":
        verify_running(plan)
    check_edge_closed()


def operate(args):
    hold = GATE.with_name("recovery-hold")
    if args.action == "recovery-begin":
        return begin_recovery(args, hold)
    if args.action == "recovery-telegram-reviewed":
        return reconcile_backlog(args, hold)
    if args.action == "recovery-accept":
        return accept_recovery(args, hold)
    approval = None
    if args.action in ("open", "stage", "rollback", "resume", "start-closed"):
        accepted = GATE.with_name("recovery-accepted")
        receipt = hold if os.path.lexists(hold) else accepted
        if os.path.lexists(receipt):
            approval = json.loads(receipt.read_bytes())
            if "first_open_lsn" in approval:
                require(receipt == hold and re.fullmatch("[0-9a-f]{64}", approval.get("challenge", "")),
                        "unfinished legacy admission: keep closed and renew recovery enrollment")
            if receipt == hold:
                require(args.action == "start-closed" or args.action == "open" and "first_open_lsn" in approval,
                        "recovery hold: complete independent reconciliation first")
            require(cluster_identity(args.plan) == approval["target"],
                    "recovery acceptance belongs to a different database")
            if "first_open_lsn" in approval and args.action != "start-closed":
                require(args.action == "open" and hashlib.sha256(args.plan.read_bytes()).hexdigest() == approval["plan_sha256"],
                        "first open requires the reconciled release plan")
    sha = backend(args.plan)["environment"]["APP_VERSION"]
    gate = GATE.read_text().strip() if GATE.exists() else None
    activation = GATE.with_name("activate")
    if args.action == "start-closed":
        require(gate is None and not os.path.lexists(activation), "closed ingress and inactive workers required")
        check_edge_closed()
        cid = compose(args.plan, "ps", "--no-trunc", "-aq", "backend")
        require(re.fullmatch(r"[0-9a-f]{64}", cid) and cid == args.backend_id and
                state(args.plan, args.plan, cid) == "OLD stopped", "expected the stopped backend pinned before close")
        subprocess.run(["docker", "start", cid], check=True, stdout=subprocess.DEVNULL, **host.lock_args())
        require(state(args.plan, args.plan, cid) == "OLD running", "started backend changed identity")
        verify_running(args.plan)
        verified_database(args.plan)
        await_readiness(sha, image_assets(args.plan), recovery=os.path.lexists(hold))
        return
    if args.action == "rollback":
        # Only reverse the very transition that was admitted. Both binaries must support the live schema.
        old, new = args.previous, args.plan
        old_doc, new_doc = json.loads(old.read_bytes()), json.loads(new.read_bytes())
        confirm_transition(old, new.read_bytes())
        require(plan_schema(old_doc) == plan_schema(new_doc), "different schemas require forward-fix or isolated restore")
        require(gate is None and not os.path.lexists(activation), "close the identified backend first")
        check_edge_closed()
        verified_database(old)
        for plan, doc in ((old, old_doc), (new, new_doc)):
            check_target_image(plan)
            evidence = doc.get("x-hookah-release-evidence", {}).get("candidate", {})
            service = doc["services"]["backend"]
            require(evidence.get("image") == service["image"] and evidence.get("revision") ==
                    service["environment"]["APP_VERSION"], "release needs matching admission evidence")
        check_gate_binary(old)
        cid = stopped_backend(old, new)
        if "pdf-worker" in new_doc["services"]:
            worker_id, installed = rollback_worker(old, new)
            if installed == new:
                subprocess.run(["docker", "stop", worker_id], check=True, stdout=subprocess.DEVNULL, **host.lock_args())
                require(rollback_worker(old, new) == (worker_id, new) and
                        subprocess.check_output(["docker", "inspect", "--format", "{{.State.Status}}", worker_id],
                                                text=True).strip() in ("created", "exited"),
                        "replacement PDF worker did not stop")
        require(stopped_backend(old, new) == cid, "backend changed during rollback")
        start_release(old)
        require(state(old, new) == "OLD running", "previous backend identity mismatch")
        verify_running(old)
        verified_database(old)
        await_readiness(backend(old)["environment"]["APP_VERSION"], image_assets(old))
        check_edge_closed()
        return
    if args.action in ("inspect", "stage", "resume"):
        confirm_transition(args.previous, args.plan.read_bytes())
        which = state(args.previous, args.plan)
        if args.action == "inspect":
            print("traffic decision", gate or "CLOSED", "activation", activation.read_text().strip() if activation.exists() else "none", "backend", which, "PostgreSQL binding verified")
            require(which in ("OLD running", "OLD stopped", "NEW running", "NEW stopped"), "unqualified backend: " + which)
            return
        if args.action == "resume":
            require(gate is None and which == "NEW stopped", "resume requires closed ingress and exact stopped NEW")
            check_edge_closed()
            cid = compose(args.plan, "ps", "-aq", "backend")
            require(cid and state(args.previous, args.plan, cid) == "NEW stopped", "NEW identity changed")
            activation.unlink(missing_ok=True)
            flush_dir()
            subprocess.run(["docker", "start", cid], check=True, stdout=subprocess.DEVNULL, **host.lock_args())
            await_readiness(sha, image_assets(args.plan))
            verify_running(args.plan)
            verified_database(args.plan)
            return
        require(gate is None and which in ("OLD running", "OLD stopped"), "stage requires closed traffic and known OLD")
        check_edge_closed()
        check_target_image(args.plan)
        verified_database(args.previous)
        if which == "OLD running":
            verify_running(args.previous)
            compose(args.previous, "stop", "backend")
        require(state(args.previous, args.plan) == "OLD stopped", "OLD did not drain")
        start_release(args.plan)
        return
    if args.action == "close":
        # Close ingress even if later runtime/DB inspection fails. A missing file survives reboot.
        GATE.unlink(missing_ok=True)
        flush_dir()
        activation.unlink(missing_ok=True)
        flush_dir()
        cid = compose(args.plan, "ps", "-aq", "backend")
        require(cid and state(args.plan, args.plan, cid) in ("OLD running", "OLD stopped"),
                "cannot identify backend to close")
        subprocess.run(["docker", "stop", cid], check=True, stdout=subprocess.DEVNULL, **host.lock_args())
        require(compose(args.plan, "ps", "-aq", "backend") == cid and
                state(args.plan, args.plan, cid) == "OLD stopped", "backend did not drain")
        check_edge_closed()
        if os.path.lexists(hold) or backend(args.plan)["environment"].get("VENUE_INFO_PDF_WORKER_ENABLED") == "true":
            return
        # During backup/qualification OLD still serves loopback checks with its poller disabled.
        compose(args.plan, "start", "backend")
        await_readiness(sha, image_assets(args.plan))
        return
    require(gate is None, "already opened: inspect the actual runtime before any further action")
    first = os.path.lexists(hold)
    clearance = GATE.with_name("telegram-recovery-cleared")
    decision = None
    committed_recovery = False
    if first:
        # Consume the decision durably before probing or preparing either ticket. Even SIGKILL
        # requires a new owner decision for a different challenge on the next attempt.
        approved_lsn = approval.pop("first_open_lsn")
        approval["challenge"] = secrets.token_hex(32)
        publish(hold, json.dumps(approval, sort_keys=True))
    try:
        check_edge_closed()
        check_gate_binary(args.plan)
        verified_database(args.plan)
        verify_running(args.plan)
        assets = image_assets(args.plan)
        enabled = backend(args.plan)["environment"]["TELEGRAM_BOT_ENABLED"] == "true"
        deferred = not first and approval is not None and enabled and not clearance.is_file()
        polling = "running" if enabled else "disabled"
        if first or deferred:
            require(not enabled or not os.path.lexists(GATE.with_name("telegram-backlog")),
                    "recorded Telegram backlog requires owner reconciliation before another probe")
            require(not os.path.lexists(activation), "close incomplete recovery before retrying")
            inventory_sql = (PROFILE / "recovery-admission.sql").read_text()
            require(not first or pg_query(approval["target"]["container"], "postgres", inventory_sql) == approved_lsn,
                    "candidate changed after review; renew the recovery decision")
            expected = "empty" if enabled else "disabled"
            instance = await_readiness(sha, assets, polling=expected, recovery=True)
            decision = sha + " " + instance
            publish(activation, decision)
            if first:
                publish(GATE, decision)
            # Both tickets are inert while hold exists. Verify this at the real edge as well.
            check_edge_closed()
            verified_database(args.plan)
            verify_running(args.plan)
            await_readiness(sha, assets, instance, expected, recovery=True)
            if first:
                current = pg_query(approval["target"]["container"], "postgres", inventory_sql)
                require(current == approved_lsn, "candidate changed after review; renew the recovery decision")
                publish(accepted, json.dumps(approval, sort_keys=True), 0o600)
                if enabled:
                    publish(clearance, decision)
                else:
                    clearance.unlink(missing_ok=True)
                    flush_dir()
                hold.unlink()
            else:
                # The JVM denies all Telegram effects and public requests until clearance exists.
                publish(GATE, decision)
                publish(clearance, decision)
            committed_recovery = True
            flush_dir()
            await_readiness(sha, assets, instance, polling)
            check_edge_open(sha, instance, polling)
            return
        instance = await_readiness(sha, assets)
        decision = sha + " " + instance
        if os.path.lexists(activation) and activation.read_text().strip() != decision:
            decision = None
            raise Refused("activation belongs to another JVM; close the identified backend first")
        publish(activation, decision)
        await_readiness(sha, assets, instance, polling)
        verified_database(args.plan)
        verify_running(args.plan)
        await_readiness(sha, assets, instance, polling)
        publish(GATE, decision)
    except BaseException as error:
        # flock serializes operators. Remove only this JVM's tickets, including on post-commit failure.
        for ticket in (GATE, activation):
            if decision and ticket.is_file() and ticket.read_text().strip() == decision:
                ticket.unlink()
        flush_dir()
        if committed_recovery:
            raise Refused("recovery decision committed; ingress reclosed; inspect Telegram effects and runtime before reopening") from error
        raise


def dispatch_locked(args):
    """Operate on an existing production installation under its native locks."""
    if args.action in ("stage", "resume", "rollback", "start-closed"):
        host.verify_slice()
        base = getattr(args, "previous", args.plan)
        path, db, wal = host.database_space(verified_database(base, transition_from=args.plan))
        target = base if args.action == "rollback" else args.plan
        services = json.loads(target.read_bytes())["services"]
        release = args.action in ("stage", "rollback")
        host.image_space(host.plan_images(services), allocations=[(path, db + wal if release else 0)],
                         reserve_release=release)
    operate(args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("install", "install-db", "install-successor", "recovery-start", "close", "stage", "resume", "inspect", "rollback", "open", "start-closed"):
        cmd = sub.add_parser(name)
        if name in ("stage", "resume", "inspect", "rollback"):
            cmd.add_argument("previous", type=Path)
        cmd.add_argument("plan", type=Path)
        if name == "start-closed":
            cmd.add_argument("backend_id", help="full backend container ID recorded before close")
    begin = sub.add_parser("recovery-begin")
    begin.add_argument("plan", type=Path)
    begin.add_argument("point_sha256")
    for name in ("successor-target", "bind-successor"):
        cmd = sub.add_parser(name)
        cmd.add_argument("plan", type=Path)
        cmd.add_argument("recipient")
        if name == "bind-successor":
            cmd.add_argument("review", type=Path)
            cmd.add_argument("review_sha256", help="digest independently received from the owner")
            cmd.add_argument("signature", type=Path, help="custodian SSH signature of the same review bytes")
    for name in ("recovery-accept", "recovery-telegram-reviewed"):
        accept = sub.add_parser(name)
        accept.add_argument("plan", type=Path)
        accept.add_argument("review", type=Path)
        accept.add_argument("review_sha256")
    args = parser.parse_args()
    with ExitStack() as locks:
        try:
            locks.enter_context(host.operator_lock(GATE))
        except (Refused, OSError) as error:
            if args.action == "close":
                raise Refused(f"close could not acquire operator locks ({error}); traffic may still be open. "
                              "Retry close after lock release and confirm HTTPS 503.") from error
            raise
        pending_close = (args.action == "close" and os.path.lexists(GATE.with_name(".install-intent")) and
                         not os.path.lexists(args.plan.parent / "postgres-binding.json"))
        if args.action in ("successor-target", "bind-successor"):
            successor_enrollment(args)
            if args.action == "successor-target":
                return
        elif pending_close or args.action in ("install", "install-db", "install-successor", "recovery-start"):
            install_closed(args.action, args.plan)
        else:
            dispatch_locked(args)
    print("OK", args.action)


if __name__ == "__main__":
    try:
        main()
    except (OSError, Refused, KeyError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
        sys.exit(f"Traffic operation stopped: {exc}")
