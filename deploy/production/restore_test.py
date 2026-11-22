"""Restore an encrypted pilot backup into one journaled, network-disabled Docker attempt."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "scripts"))
from production_profile import Refused, check_history, require
import media_backup

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


def verify_archive(point, image, pin=None):
    require(re.fullmatch(PG_REF, image) and (point / "postgres-image").read_text().strip() == image,
            "backup PostgreSQL reference mismatch")
    sums = []
    members = ["database.dump.age", "postgres-image"]
    if (point / "schema-version").exists():
        require((point / "schema-version").read_text().strip() in ("142", "143"), "unknown backup schema")
        members.append("schema-version")
    if (point / "media.tar.age").exists():
        members.append("media.tar.age")
    if (point / "source-coordinate").exists():
        members.append("source-coordinate")
    for name in members:
        digest = hashlib.sha256()
        with (point / name).open("rb") as member:
            for chunk in iter(lambda: member.read(1024 * 1024), b""):
                digest.update(chunk)
        sums.append(f"{digest.hexdigest()}  {name}\n")
    manifest = (point / "SHA256SUMS").read_bytes()
    require(manifest == "".join(sums).encode(), "backup integrity mismatch")
    if pin is not None:
        require(hashlib.sha256(manifest).hexdigest() == pin, "backup differs from custody receipt")


def coordinate(point):
    raw = (point / "source-coordinate").read_text().strip()
    match = re.fullmatch(r"([1-9][0-9]*\|[1-9][0-9]*\|[1-9][0-9]*)\|([0-9A-F]{1,8})/([0-9A-F]{1,8})", raw)
    require(match, "invalid backup source coordinate")
    return match[1], (int(match[2], 16) << 32) + int(match[3], 16)


def check_pair(point, metadata):
    source, start = coordinate(point)
    target, end = coordinate(metadata)
    require(source == target and start < end, "recovery points are unordered or belong to different sources")


def restore(point, identity, image, assertions, attempt, metadata=None, keep=False):
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
    docker(*sql, payload=b"CREATE ROLE hookah_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;\n"
                         b"ALTER DATABASE hookah_prod OWNER TO hookah_app;")
    require(docker(*sql, payload=b"SELECT current_setting('server_version_num')::int / 10000") == "17",
            "PostgreSQL 17 required")
    if metadata:
        docker(*sql, payload=b"CREATE DATABASE archive_index OWNER hookah_app")
        source_sql = [*sql[:-1], "archive_index"]
        load_database(point, identity, cid, "archive_index", source_sql)
        archived = media_backup.inventory(docker(*source_sql, payload=media_backup.INVENTORY.read_bytes()))
        docker(*sql, payload=b"DROP DATABASE archive_index")
    load_database(metadata or point, identity, cid, "hookah_prod", sql)
    if assertions:
        with open(assertions, "rb") as f:
            subprocess.run(["docker", *sql], stdin=f, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    rows = media_backup.inventory(docker(*sql, payload=media_backup.INVENTORY.read_bytes()))
    if not metadata:
        archived = rows
    store = None
    if keep:
        docker(*sql, payload=b"CREATE SCHEMA hookah_recovery AUTHORIZATION postgres; "
               b"REVOKE ALL ON SCHEMA hookah_recovery FROM PUBLIC, hookah_app; "
               b"CREATE TABLE hookah_recovery.objects(object_key text PRIMARY KEY, content bytea NOT NULL);")

        def store(key, data):
            # Keys came from the fixed-format inventory, never from a caller-supplied path.
            copy_input = (b"COPY hookah_recovery.objects FROM STDIN;\n" + key.encode("ascii") + b"\t" +
                          b"\\\\x" + data.hex().encode("ascii") + b"\n\\.\n")
            # Validate PostgreSQL's stored bytea before handing over the retained volume.
            verify = f"SELECT encode(sha256(content),'hex') FROM hookah_recovery.objects WHERE object_key='{key}'"
            require(docker(*sql, payload=copy_input + verify.encode("ascii")) == rows[key][1],
                    "quarantine stored checksum mismatch")
    if (point / "media.tar.age").exists():
        counts = media_backup.restore(point, identity, archived, rows, store)
    else:
        require(not archived, "missing media recovery archive")
        counts = {"selected": 0, "excluded": 0, "newer_without_bytes": len(rows)}
    if keep:
        result = docker(*sql, payload=b"SELECT count(*), current_setting('listen_addresses') = '', "
                        b"NOT (SELECT rolcanlogin FROM pg_roles WHERE rolname='hookah_app'), "
                        b"NOT has_schema_privilege('hookah_app','hookah_recovery','USAGE') FROM hookah_recovery.objects")
        require(result == f"{counts['selected']}|t|t|t", "recovered database isolation or object count mismatch")
    return counts


def load_database(point, identity, cid, database, sql):
    with subprocess.Popen(["age", "-d", "-i", identity, point / "database.dump.age"], stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as decrypt:
        try:
            subprocess.run(["docker", "exec", "-i", cid, "pg_restore", "-w", "--single-transaction", "--exit-on-error",
                            "-U", "postgres", "-d", database], stdin=decrypt.stdout, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=True)
        finally:
            decrypt.stdout.close()
        require(decrypt.wait() == 0, "age authentication failed")
    schema = point / "schema-version"
    check_history(docker(*sql, payload=(HERE / "history.sql").read_bytes()),
                  [int(schema.read_text()) if schema.exists() else 142])
    docker("exec", cid, "pg_amcheck", "--install-missing", "-U", "postgres", "-d", database)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("point", type=Path)
    parser.add_argument("identity", type=Path)
    parser.add_argument("image")
    parser.add_argument("assertions", nargs="?")
    parser.add_argument("--metadata-point", type=Path)
    parser.add_argument("--point-sha256")
    parser.add_argument("--metadata-sha256")
    parser.add_argument("--keep-quarantine", action="store_true")
    args = parser.parse_args()
    point, identity, image = args.point, args.identity, args.image
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
        if args.keep_quarantine or args.metadata_point:
            require(args.point_sha256 and not args.assertions, "custody checksum required; custom SQL disallowed")
        require(bool(args.metadata_sha256) == bool(args.metadata_point), "metadata point and custody checksum required together")
        verify_archive(point, image, args.point_sha256)
        if args.metadata_point:
            verify_archive(args.metadata_point, image, args.metadata_sha256)
            check_pair(point, args.metadata_point)
        attempt = uuid.uuid4().hex
        name = "ht-restore-" + attempt
        require(not docker("ps", "-aq", "--filter", "name=^/" + name + "$") and
                not docker("volume", "ls", "-q", "--filter", "name=^" + name + "-data$"), "restore name occupied")
        with tempfile.NamedTemporaryFile(dir=state) as f:
            record = dict(attempt=attempt, image=image, daemon=daemon)
            if args.keep_quarantine:
                record.update(point_sha256=args.point_sha256, metadata_sha256=args.metadata_sha256 or args.point_sha256)
            f.write(json.dumps(record).encode())
            f.flush()
            os.fsync(f.fileno())
            os.link(f.name, journal)
            synced(state)
        try:
            counts = restore(point, identity, image, args.assertions, attempt, args.metadata_point, args.keep_quarantine)
            if args.keep_quarantine:
                receipt = state / (attempt + "-quarantine.json")
                journal.rename(receipt)
                synced(state)
                print("Private PostgreSQL retained:", name, "database hookah_prod;", counts, "receipt:", receipt)
                return
        finally:
            if journal.exists():
                cleanup(journal, daemon)
    print("Isolated restore, integrity and cleanup passed")


if __name__ == "__main__":
    try:
        main()
    except (Refused, OSError, ValueError, KeyError, tarfile.TarError, subprocess.SubprocessError) as exc:
        # SQL, filenames, connection parameters and restored values are private.
        reason = str(exc) if isinstance(exc, Refused) else type(exc).__name__
        sys.exit("Restore refused: " + reason)
