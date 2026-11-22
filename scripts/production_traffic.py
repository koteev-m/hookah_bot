"""Operate the durable production traffic gate on the dedicated Docker/Caddy host."""
import argparse
import fcntl
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

from production_profile import PRODUCTION_PROJECT, ProductionWorker, GATE, IMAGE_RE, PROFILE, SOURCE, Refused, check_target_image, cluster_identity, confirm_transition, matches_running_image, pg_query, plan_schema, require, verified_database, verify_running

sys.path.insert(0, str(PROFILE))
from smoke import image_assets, probe

def compose(plan, *args):
    return subprocess.check_output(["docker", "compose", "--env-file", "/dev/null", "-p", PRODUCTION_PROJECT,
                                    "-f", str(plan), *args], text=True).strip()


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
    jar = subprocess.check_output(["docker", "run", "--rm", "--pull", "never", "--platform", "linux/amd64",
                                   "--network", "none", "--read-only", "--entrypoint", "cat", backend(plan)["image"],
                                   "/app/app/lib/app-0.1.0.jar"])
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


def publish(path, value, mode=0o644):
    with tempfile.NamedTemporaryFile(dir=GATE.parent, delete=False) as f:
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


def owner_review(args):
    mode = args.review.lstat()
    require(stat.S_ISREG(mode.st_mode) and mode.st_uid == os.geteuid() and not mode.st_mode & 0o077,
            "private operator review required")
    raw = args.review.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == args.review_sha256, "review differs from owner's approved digest")
    review = json.loads(raw)
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
        subprocess.run(["docker", "start", cid], check=True, stdout=subprocess.DEVNULL)
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
                subprocess.run(["docker", "stop", worker_id], check=True, stdout=subprocess.DEVNULL)
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
            subprocess.run(["docker", "start", cid], check=True, stdout=subprocess.DEVNULL)
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
        subprocess.run(["docker", "stop", cid], check=True, stdout=subprocess.DEVNULL)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("close", "stage", "resume", "inspect", "rollback", "open", "start-closed"):
        cmd = sub.add_parser(name)
        if name in ("stage", "resume", "inspect", "rollback"):
            cmd.add_argument("previous", type=Path)
        cmd.add_argument("plan", type=Path)
        if name == "start-closed":
            cmd.add_argument("backend_id", help="full backend container ID recorded before close")
    begin = sub.add_parser("recovery-begin")
    begin.add_argument("plan", type=Path)
    begin.add_argument("point_sha256")
    for name in ("recovery-accept", "recovery-telegram-reviewed"):
        accept = sub.add_parser(name)
        accept.add_argument("plan", type=Path)
        accept.add_argument("review", type=Path)
        accept.add_argument("review_sha256")
    args = parser.parse_args()
    with (GATE.parent / ".operator.lock").open("ab") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        operate(args)
    print("OK", args.action)


if __name__ == "__main__":
    try:
        main()
    except (OSError, Refused, KeyError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
        sys.exit(f"Traffic operation stopped: {exc}")
