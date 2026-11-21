"""Operate the durable production traffic gate on the dedicated Docker/Caddy host."""
import argparse
import fcntl
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from production_profile import IMAGE_RE, PROFILE, SOURCE, Refused, check_target_image, confirm_transition, matches_running_image, require, verified_database, verify_running

sys.path.insert(0, str(PROFILE))
from smoke import image_assets, probe

GATE = Path("/var/lib/hookah-production/ingress/open")


def compose(plan, *args):
    return subprocess.check_output(["docker", "compose", "--env-file", "/dev/null", "-p", "hookah_production",
                                    "-f", str(plan), *args], text=True).strip()


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
        if (c["Name"] != "/hookah_production-backend-1" or cid not in (c["Id"], c["Id"][:12])
                or labels.get("com.docker.compose.container-number") != "1"
                or labels.get("com.docker.compose.oneoff", "").lower() != "false"):
            return "unknown"
        binds = {(m["Source"], m["Destination"], m["RW"]) for m in c["Mounts"] if m["Type"] == "bind"}
        expected_binds = {(m["source"], m["target"], not m.get("read_only", False))
                          for m in b.get("volumes", []) if m["type"] == "bind"}
        if binds != expected_binds or any(m["Type"] == "volume" for m in c["Mounts"]):
            return "unknown"
        if (matches_running_image(c, b["image"], image) and labels.get("com.docker.compose.project") == "hookah_production"
                and labels.get("com.docker.compose.service") == "backend"
                and labels.get("com.docker.compose.config-hash") == expected["backend"]):
            nets = {"hookah_production_database", "hookah_production_outbound"}
            if set(c["NetworkSettings"]["Networks"]) != nets:
                return "unknown"
            if c["HostConfig"]["PortBindings"] != {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]}:
                return "unknown"
            status = c["State"]["Status"]
            if status in ("created", "exited", "running"):
                return label + (" running" if status == "running" else " stopped")
    return "unknown"


def check_edge_closed():
    headers = subprocess.check_output(
        ["curl", "--silent", "--show-error", "--max-time", "5", "--noproxy", "*", "--resolve",
         "hookahtootah.club:443:127.0.0.1", "-D", "-", "-o", "/dev/null", "-w", "%{http_code}",
         "https://hookahtootah.club/miniapp/"], text=True)
    require(headers.endswith("503") and "x-hookah-maintenance: closed" in headers.lower().splitlines(),
            "local HTTPS edge did not confirm maintenance")


def publish(path, value):
    with tempfile.NamedTemporaryFile(dir=GATE.parent, delete=False) as f:
        tmp = Path(f.name)
        try:
            f.write((value + "\n").encode())
            f.flush()
            os.fchmod(f.fileno(), 0o644)
            os.fsync(f.fileno())
            os.replace(tmp, path)
            flush_dir()
        finally:
            tmp.unlink(missing_ok=True)


def await_readiness(sha, assets, instance=None, polling=None):
    deadline = time.monotonic() + 120
    while True:
        try:
            ver = probe(sha, assets)
            current = ver.get("instance")
            require(isinstance(current, str) and re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", current),
                    "backend lacks a production JVM identity")
            require(instance is None or current == instance, "serving JVM changed during admission")
            if polling is None or ver.get("polling") == polling:
                return current
        except (ValueError, OSError):
            pass
        require(time.monotonic() < deadline, "readiness failed; traffic remains closed")
        time.sleep(.2)


def operate(args):
    sha = backend(args.plan)["environment"]["APP_VERSION"]
    gate = GATE.read_text().strip() if GATE.exists() else None
    activation = GATE.with_name("activate")
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
        compose(args.plan, "up", "-d", "--pull", "never", "--no-deps", "backend")
        return
    if args.action == "close":
        # Close ingress even if later runtime/DB inspection fails. A missing file survives reboot.
        GATE.unlink(missing_ok=True)
        flush_dir()
        activation.unlink(missing_ok=True)
        flush_dir()
        verified_database(args.plan)
        verify_running(args.plan)
        compose(args.plan, "stop", "backend")
        require(not compose(args.plan, "ps", "-q", "backend"), "backend did not drain")
        check_edge_closed()
        # During backup/qualification OLD still serves loopback checks with its poller disabled.
        compose(args.plan, "start", "backend")
        await_readiness(sha, image_assets(args.plan))
        return
    require(gate is None, "already opened: inspect the actual runtime before any further action")
    check_edge_closed()
    verified_database(args.plan)
    verify_running(args.plan)
    assets = image_assets(args.plan)
    instance = await_readiness(sha, assets)
    decision = sha + " " + instance
    polling = "running" if backend(args.plan)["environment"]["TELEGRAM_BOT_ENABLED"] == "true" else "disabled"
    publish(activation, decision)
    await_readiness(sha, assets, instance, polling)
    verified_database(args.plan)
    verify_running(args.plan)
    await_readiness(sha, assets, instance, polling)
    publish(GATE, decision)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("close", "stage", "resume", "inspect", "open"):
        cmd = sub.add_parser(name)
        if name in ("stage", "resume", "inspect"):
            cmd.add_argument("previous", type=Path)
        cmd.add_argument("plan", type=Path)
    args = parser.parse_args()
    with (GATE.parent / ".operator.lock").open("ab") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        operate(args)
    print("OK", args.action)


if __name__ == "__main__":
    try:
        main()
    except (OSError, Refused, KeyError, ValueError, subprocess.SubprocessError) as exc:
        sys.exit(f"Traffic operation stopped: {exc}")
