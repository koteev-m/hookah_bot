#!/usr/bin/env python3
"""Custodian adapter for an already isolated, pinned loopback restore VM. No key transfer."""
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile

FILES = ("SHA256SUMS", "database.dump.age", "media.tar.age", "postgres-image", "schema-version", "source-coordinate")


def receive(name, pin, postgres):
    # This half is installed in the approved recovery checkout inside the disposable VM.
    import restore_test
    from production_profile import require
    require(not os.path.lexists(restore_test.GATE.parent), "production ingress present on restore host")
    require(os.readlink("/proc/self/ns/net") != os.readlink("/proc/1/ns/net"),
            "a private network namespace is required")
    key = Path("/run/hookah-restore/identity")
    require(key.resolve() == key and key.stat().st_uid == os.geteuid() and key.is_file()
            and not key.stat().st_mode & 0o077 and len(Path("/proc/swaps").read_text().splitlines()) == 1
            and subprocess.check_output(["findmnt", "-no", "FSTYPE", "-T", key], text=True).strip() in ("tmpfs", "ramfs"),
            "isolated volatile custody key required")
    with tempfile.TemporaryDirectory(dir="/var/tmp", prefix="hookah-restore-") as work:
        point = Path(work) / name
        point.mkdir(mode=0o700)
        seen = set()
        with tarfile.open(fileobj=sys.stdin.buffer, mode="r|") as archive:
            for entry in archive:
                require(entry.isfile() and entry.name in FILES and entry.name not in seen, "unexpected encrypted member")
                seen.add(entry.name)
                with (point / entry.name).open("xb") as f:
                    shutil.copyfileobj(archive.extractfile(entry), f)
        require(seen == set(FILES), "incomplete encrypted point")
        subprocess.run(["bash", str(Path(__file__).resolve().with_name("restore-test.sh")), point, key, postgres,
                        "--point-sha256", pin, "--release-json"], check=True)


def send(point, pin, postgres):
    # This named profile supplies the non-default port, pinned known_hosts and operator key.
    # Force a local guest and prevent multiplexing/forwarding through another machine.
    ssh = ["ssh", "-T", "-o", "HostName=127.0.0.1", "-o", "HostKeyAlias=ht-production-restore",
           "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no",
           "-o", "HostKeyAlgorithms=ssh-ed25519", "-o", "ForwardAgent=no", "-o", "ClearAllForwardings=yes",
           "-o", "IdentitiesOnly=yes", "-o", "ControlMaster=no", "-o", "ControlPath=none",
           "-o", "ProxyCommand=none", "-o", "ProxyJump=none", "-o", "ConnectTimeout=10", "ht-production-restore"]
    remote = ["env", "-i", "PATH=/usr/sbin:/usr/bin:/sbin:/bin", "HOME=/root", "DOCKER_HOST=unix:///var/run/docker.sock",
              "unshare", "--net", "--", "/usr/bin/python3", "-EBs", "/opt/hookah-restore/deploy/production/restore-scheduled.py",
              "--receive", point.name, pin, postgres]
    with subprocess.Popen(ssh + [shlex.join(remote)], stdin=subprocess.PIPE) as proc:
        try:
            with tarfile.open(fileobj=proc.stdin, mode="w|") as archive:
                for name in FILES:
                    archive.add(point / name, arcname=name, recursive=False)
        finally:
            proc.stdin.close()
        if proc.wait():
            sys.exit("isolated restore did not complete")


if __name__ == "__main__":
    os.umask(0o077)
    try:
        receiving = sys.argv[1:2] == ["--receive"]
        source, pin, postgres = sys.argv[2:] if receiving else sys.argv[1:]
        point = Path(source)
        if not (re.fullmatch(r"full-\d{8}T\d{6}Z", point.name) and re.fullmatch("[a-f0-9]{64}", pin)
                and re.fullmatch("postgres@sha256:[a-f0-9]{64}", postgres)):
            sys.exit("invalid restore arguments")
        if receiving and source == point.name:
            receive(source, pin, postgres)
        elif not receiving and point.is_absolute():
            send(point, pin, postgres)
        else:
            sys.exit("unexpected restore path")
    except Exception as error:
        sys.exit("Isolated rehearsal failed: " + type(error).__name__)
