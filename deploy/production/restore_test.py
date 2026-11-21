"""Restore an encrypted pilot backup into one journaled, network-disabled Docker attempt."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "scripts"))
from production_profile import Refused, check_history, require

LABEL = "club.hookahtootah.production.restore-attempt"
PG_REF = r"postgres@sha256:[a-f0-9]{64}"


def docker(*args, payload=None):
    return subprocess.check_output(["docker", *args], input=payload, stderr=subprocess.DEVNULL).decode().strip()


def synced(directory):
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def prepare_state_directory(path):
    if not path.parent.is_dir():
        prepare_state_directory(path.parent)
    path.mkdir(mode=0o700, exist_ok=True)
    # The intent also depends on this directory entry surviving a host failure.
    synced(path.parent)


def cleanup(journal, daemon):
    record = json.loads(journal.read_bytes())
    attempt, image = record["attempt"], record["image"]
    require(re.fullmatch("[a-f0-9]{32}", attempt) and re.fullmatch(PG_REF, image)
            and record["daemon"] == daemon, "restore journal identity mismatch")
    name = "ht-restore-" + attempt
    volume = name + "-data"
    cid = docker("ps", "-aq", "--no-trunc", "--filter", "name=^/" + name + "$")
    present = docker("volume", "ls", "-q", "--filter", "name=^" + volume + "$")
    if present:
        vol = json.loads(docker("volume", "inspect", volume))[0]
        require(present == volume == vol["Name"] and vol["Driver"] == "local" and not vol.get("Options")
                and (vol.get("Labels") or {}).get(LABEL) == attempt, "unrecognized restore volume")
        users = docker("ps", "-aq", "--no-trunc", "--filter", "volume=" + volume)
        require(users in ("", cid), "restore volume attached elsewhere")
    if cid:
        c = json.loads(docker("inspect", cid))[0]
        mounts = c["Mounts"]
        require(c["Id"] == cid and c["Name"] == "/" + name and c["Config"]["Image"] == image
                and (c["Config"].get("Labels") or {}).get(LABEL) == attempt
                and c["HostConfig"]["NetworkMode"] == "none" and not c["HostConfig"]["PortBindings"]
                and len(mounts) == 1 and mounts[0]["Type"] == "volume" and mounts[0]["Name"] == volume
                and mounts[0]["Destination"] == "/var/lib/postgresql/data" and present == volume,
                "unrecognized restore container")
        docker("rm", "-f", cid)
    if present:
        docker("volume", "rm", volume)
    require(not docker("ps", "-aq", "--filter", "name=^/" + name + "$")
            and not docker("volume", "ls", "-q", "--filter", "name=^" + volume + "$"), "restore cleanup incomplete")
    journal.unlink()
    synced(journal.parent)


def verify_archive(point, image):
    require(re.fullmatch(PG_REF, image) and (point / "postgres-image").read_text().strip() == image,
            "backup PostgreSQL reference mismatch")
    sums = []
    members = ["database.dump.age", "postgres-image"]
    if (point / "schema-version").exists():
        require((point / "schema-version").read_text().strip() in ("142", "143"), "unknown backup schema")
        members.append("schema-version")
    for name in members:
        digest = hashlib.sha256()
        with (point / name).open("rb") as member:
            for chunk in iter(lambda: member.read(1024 * 1024), b""):
                digest.update(chunk)
        sums.append(f"{digest.hexdigest()}  {name}\n")
    require((point / "SHA256SUMS").read_text() == "".join(sums), "backup integrity mismatch")


def restore(point, identity, image, assertions, attempt):
    name = "ht-restore-" + attempt
    volume = name + "-data"
    label = LABEL + "=" + attempt
    # Both names were verified absent before writing the intent. All mutations carry its label.
    docker("volume", "create", "--label", label, volume)
    vol = json.loads(docker("volume", "inspect", volume))[0]
    require(vol["Name"] == volume and vol["Driver"] == "local" and not vol.get("Options")
            and (vol.get("Labels") or {}).get(LABEL) == attempt, "restore volume was not created by this attempt")
    cid = docker("create", "--pull", "never", "--platform", "linux/amd64", "--name", name, "--label", label,
                 "--network", "none", "--log-driver", "none", "--mount",
                 "type=volume,src=" + volume + ",dst=/var/lib/postgresql/data", "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
                 "-e", "POSTGRES_DB=hookah_prod", image, "-c", "listen_addresses=")
    docker("start", cid)
    deadline = time.monotonic() + 90
    while True:
        ready = subprocess.run(["docker", "exec", cid, "sh", "-c",
                                'test "$(head -1 "$PGDATA/postmaster.pid" 2>/dev/null)" = 1 && '
                                'pg_isready -q -U postgres -d hookah_prod'], capture_output=True)
        if ready.returncode == 0:
            break
        require(time.monotonic() < deadline, "restore database startup timeout")
        time.sleep(0.5)
    sql = ["exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "hookah_prod"]
    docker(*sql, payload=b"CREATE ROLE hookah_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;\n"
                         b"ALTER DATABASE hookah_prod OWNER TO hookah_app;")
    with subprocess.Popen(["age", "-d", "-i", identity, point / "database.dump.age"], stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as decrypt:
        try:
            subprocess.run(["docker", "exec", "-i", cid, "pg_restore", "-w", "--single-transaction", "--exit-on-error",
                            "-U", "postgres", "-d", "hookah_prod"], stdin=decrypt.stdout, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=True)
        finally:
            decrypt.stdout.close()
        require(decrypt.wait() == 0, "age authentication failed")
    schema = point / "schema-version"
    check_history(docker(*sql, payload=(HERE / "history.sql").read_bytes()),
                  [int(schema.read_text()) if schema.exists() else 142])
    docker("exec", cid, "pg_amcheck", "--install-missing", "-U", "postgres", "-d", "hookah_prod")
    if assertions:
        with open(assertions, "rb") as f:
            subprocess.run(["docker", *sql], stdin=f, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)


def main():
    require(len(sys.argv) in (4, 5), "expected backup directory, recovery identity, approved PG digest and optional SQL")
    point, identity, image = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    state = Path(os.getenv("XDG_STATE_HOME", Path.home() / ".local/state")) / "hookah-production-restore"
    prepare_state_directory(state)
    mode = state.lstat()
    require(stat.S_ISDIR(mode.st_mode) and mode.st_uid == os.geteuid() and not mode.st_mode & 0o077,
            "private restore state directory required")
    with (state / "lock").open("ab") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        daemon = docker("info", "--format", "{{.ID}}")
        require(bool(daemon), "unknown Docker daemon")
        journal = state / "attempt.json"
        if journal.exists():
            cleanup(journal, daemon)
        verify_archive(point, image)
        attempt = uuid.uuid4().hex
        name = "ht-restore-" + attempt
        require(not docker("ps", "-aq", "--filter", "name=^/" + name + "$") and
                not docker("volume", "ls", "-q", "--filter", "name=^" + name + "-data$"), "restore name occupied")
        with tempfile.NamedTemporaryFile(dir=state) as f:
            f.write(json.dumps(dict(attempt=attempt, image=image, daemon=daemon)).encode())
            f.flush()
            os.fsync(f.fileno())
            os.link(f.name, journal)
            synced(state)
        try:
            restore(point, identity, image, sys.argv[4] if len(sys.argv) == 5 else None, attempt)
        finally:
            cleanup(journal, daemon)
    print("Isolated restore, integrity and cleanup passed")


if __name__ == "__main__":
    try:
        main()
    except (Refused, OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        # SQL, filenames, connection parameters and restored values are private.
        reason = str(exc) if isinstance(exc, Refused) else type(exc).__name__
        sys.exit("Restore refused: " + reason)
