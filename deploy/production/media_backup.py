"""Encrypted private media backup and isolated recovery for the production profile."""
from contextlib import closing, contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import re
import resource
import signal
import subprocess
import sys
import tarfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "scripts"))
import production_host as host
from production_profile import MEDIA_FLAG, PRODUCTION_BUCKET, Refused, check_media, require, verified_database

INVENTORY = HERE / "media-inventory.sql"
UUID = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
KEY = re.compile(rf"(?:staff-photos/{UUID}\.jpg|venue-info/{UUID}\.(?:jpg|png|pdf))")


def recovery_tools(root):
    """Bind a rehearsal to the backup/restore implementation that produced and consumed the point."""
    files = ["deploy/production/" + n for n in (
        "backup.sh", "backup_points.py", "media_backup.py", "restore-test.sh", "restore_test.py", "restore-scheduled.py",
        "history.sql", "media-inventory.sql")]
    files += ["scripts/" + n for n in ("production_profile.py", "production_host.py", "production_traffic.py",
                                      "simple_staging.py")]
    migr = root / "backend/app/src/main/resources/db/migration/postgresql"
    migrations = sorted(migr.glob("V*__*.sql"))
    require(bool(migrations), "missing approved migration sources")
    files.extend(str(f.relative_to(root)) for f in migrations)
    h = hashlib.sha256()
    for name in files:
        h.update(name.encode() + b"\0" + hashlib.sha256((root / name).read_bytes()).digest())
    return h.hexdigest()


def inventory(raw):
    rows = {}
    decoded = json.loads(raw)
    require(isinstance(decoded, list), "invalid media inventory")
    for record in decoded:
        require(isinstance(record, list) and len(record) == 5, "invalid media inventory row")
        key, size, sha, policy, selectable = record
        require(type(selectable) is bool and isinstance(key, str) and KEY.fullmatch(key) and key not in rows and type(size) is int and
                0 < size <= (2 if key.startswith("staff-photos/") else 10) * 1024**2 and
                all(isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) for value in (sha, policy)), "invalid media inventory")
        rows[key] = (size, sha, policy, selectable)
    return rows


def verified_bytes(stream, row):
    size, checksum = row[:2]
    data = stream.read(size + 1)
    require(len(data) == size and hashlib.sha256(data).hexdigest() == checksum, "media size or checksum mismatch")
    return data


def archive(rows, client, output, *, etags=None):
    with tarfile.open(fileobj=output, mode="w|") as tar:
        for key, row in rows.items():
            result = client.get_object(Bucket=PRODUCTION_BUCKET, Key=key)
            with closing(result["Body"]) as body:
                data = verified_bytes(body, row)
            if etags is not None:
                tag = result.get("ETag")
                require(isinstance(tag, str) and re.fullmatch(r'"[^"\r\n]+"', tag), "missing object validator")
                etags[key] = tag
            member = tarfile.TarInfo(key)
            member.size = len(data)
            tar.addfile(member, io.BytesIO(data))


def unpack(source, archived, metadata, store=None):
    """Verify every archived byte. A matching snapshot is only a private recovery candidate."""
    remaining = dict(archived)
    selected = 0
    with tarfile.open(fileobj=source, mode="r|") as tar:
        for member in tar:
            row = remaining.pop(member.name, None)
            require(row is not None and member.type == tarfile.REGTYPE and member.size == row[0],
                    "unexpected media archive member")
            data = verified_bytes(tar.extractfile(member), row)
            target = metadata.get(member.name)
            require(target is None or row[:2] == target[:2], "immutable media key has conflicting content")
            if target == row and row[3]:
                if store is not None:
                    store(member.name, data)
                selected += 1
    require(not remaining, "media archive incomplete")
    return {"selected": selected, "excluded": len(archived) - selected,
            "newer_without_bytes": len(metadata.keys() - archived.keys())}


def restore(point, identity, archived, metadata, store=None):
    with subprocess.Popen(["age", "-d", "-i", identity, point / "media.tar.age"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as decrypt:
        try:
            result = unpack(decrypt.stdout, archived, metadata, store)
            while decrypt.stdout.read(65536):
                pass
        finally:
            decrypt.stdout.close()
        require(decrypt.wait() == 0, "media archive authentication failed")
    return result


def storage_client(env):
    check_media(dict(env, **{MEDIA_FLAG: "true"}))
    import boto3
    from botocore.config import Config
    return boto3.client("s3", region_name="auto", endpoint_url=env["STAFF_PHOTOS_R2_ENDPOINT"],
                          aws_access_key_id=env["STAFF_PHOTOS_R2_ACCESS_KEY"],
                          aws_secret_access_key=env["STAFF_PHOTOS_R2_SECRET_KEY"],
                          config=Config(signature_version="s3v4", proxies={}, s3={"addressing_style": "path"}))


@contextmanager
def snapshot(cid, *, repair=False, online=False, pass_fds=()):
    # Keep the exporting transaction alive until pg_dump finishes importing its MVCC snapshot.
    with subprocess.Popen(["docker", "exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1",
                           "-U", "hookah_app", "-d", "hookah_prod"], stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE, text=True, pass_fds=pass_fds) as session:
        try:
            if online:
                session.stdin.write("SELECT pg_current_wal_insert_lsn();\n")
            if repair:
                session.stdin.write("BEGIN; SET LOCAL lock_timeout='5s'; LOCK TABLE venues, venue_settings, "
                                    "venue_subscriptions, staff_shifts, staff_profile_photos, staff_profile_photo_state, "
                                    "staff_profiles, venue_info_section_media, venue_info_sections, venue_info_media_cleanup, "
                                    "hall_plan_media, venue_halls, venue_seating_rules IN SHARE MODE; ")
            else:
                session.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; ")
            session.stdin.write("SELECT pg_export_snapshot();\n")
            if online:
                session.stdin.write("SELECT pg_current_wal_insert_lsn();\n")
            session.stdin.flush()
            wal = session.stdout.readline().strip() if online else None
            token = session.stdout.readline().strip()
            require(re.fullmatch(r"[0-9A-Fa-f]+-[0-9A-Fa-f]+-[0-9]+", token), "cannot export PostgreSQL snapshot")
            if online:
                require(re.fullmatch(r"[0-9A-F]{1,8}/[0-9A-F]{1,8}", wal) and session.stdout.readline().strip() == wal,
                        "WAL advanced while acquiring the online snapshot")
            query = INVENTORY.read_text().strip().removesuffix(";")
            session.stdin.write("SELECT rows::jsonb::text FROM (" + query + ") AS captured(rows);\n")
            session.stdin.flush()
            yield token, inventory(session.stdout.readline()), wal
        finally:
            session.stdin.close()
        require(session.wait() == 0, "snapshot transaction failed")


def backup_space(point, storage, rows):
    pgdata, db, wal = storage
    # One short USTAR name per object, each body padded to 512, two end blocks.
    tar_bytes = 0
    if rows is not None:
        blocks = 1024 + sum(512 + ((row[0] + 511) // 512) * 512 for row in rows.values())
        tar_bytes = ((blocks + 10239) // 10240) * 10240
    # pg_database_size is a sizing input, not a hard limit on a logical dump.
    # Budget double DB size plus framing overhead for the streamed age files.
    def encrypted_size(size):
        return size + (size // 65536 + 1) * 16 + 4096

    dump_limit = encrypted_size(2 * db)
    media_limit = encrypted_size(tar_bytes) if rows is not None else 0
    host.check_space([(pgdata, wal), (point, dump_limit + media_limit)])
    return dump_limit, media_limit


def file_limit(size):
    # Applied only in the age child. An underestimated dump must fail before consuming the neighbours' reserve.
    if size is None:
        return None

    def apply():
        signal.signal(signal.SIGXFSZ, signal.SIG_IGN)
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        soft, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
        limit = min(n for n in (size, soft, hard) if n != resource.RLIM_INFINITY)
        resource.setrlimit(resource.RLIMIT_FSIZE, (limit, limit))

    return apply


def capture(cid, env, point, recipient, media, metadata_only, *, storage=None, pass_fds=(), online=False):
    with snapshot(cid, online=online, pass_fds=pass_fds) as (token, rows, wal):
        require(media or not rows, "database snapshot contains media without admitted R2")
        dump_limit = media_limit = None
        if storage is not None:
            dump_limit, media_limit = backup_space(point, storage, rows if media and not metadata_only else None)
        with subprocess.Popen(["docker", "exec", cid, "pg_dump", "-w", "-U", "hookah_app", "-d", "hookah_prod",
                               "-Fc", "--snapshot", token], stdout=subprocess.PIPE, pass_fds=pass_fds) as dump:
            try:
                subprocess.run(["age", "-r", recipient, "-o", point / "database.dump.age"], stdin=dump.stdout, check=True,
                               preexec_fn=file_limit(dump_limit), pass_fds=pass_fds)
            finally:
                dump.stdout.close()
            require(dump.wait() == 0, "database dump failed")
    if not media or metadata_only:
        return
    client = storage_client(env)
    etags = {} if online else None
    with subprocess.Popen(["age", "-r", recipient, "-o", point / "media.tar.age"], stdin=subprocess.PIPE,
                          preexec_fn=file_limit(media_limit), pass_fds=pass_fds) as encrypt:
        try:
            archive(rows, client, encrypt.stdin, etags=etags)
        finally:
            encrypt.stdin.close()
        require(encrypt.wait() == 0, "media encryption failed")
    if online:
        return wal, rows, etags


def verify_online_media(cid, env, rows, etags):
    from production_profile import pg_query
    client = storage_client(env)
    for key, tag in etags.items():
        current = client.head_object(Bucket=PRODUCTION_BUCKET, Key=key, IfMatch=tag)
        require(current.get("ETag") == tag and current.get("ContentLength") == rows[key][0],
                "R2 object changed during backup")
    latest = inventory(pg_query(cid, "hookah_app", INVENTORY.read_text()))
    require(all(latest.get(key) == row for key, row in rows.items()), "required media changed during backup")


def backup_target(plan, recipient, *, online=False):
    from production_traffic import compose, state
    from production_profile import verify_plan_pin

    if online:
        verify_plan_pin(plan)
    cid = verified_database(plan, recipient)
    backend_id = compose(plan, "ps", "-aq", "backend")
    require(backend_id, "cannot establish installed media mode without the admitted backend")
    doc = json.loads(plan.read_bytes())
    configured = doc["services"]["backend"]["environment"]
    flags = ("VENUE_INFO_MEDIA_R2_ENABLED", "STAFF_PHOTOS_R2_ENABLED", "VENUE_INFO_PDF_WORKER_ENABLED")
    mode = configured[flags[0]]
    require(mode in ("true", "false") and all(configured.get(k) == mode for k in flags), "inconsistent plan media mode")
    observed = json.loads(subprocess.check_output(["docker", "inspect", backend_id]))[0]
    runtime = dict(item.split("=", 1) for item in observed["Config"]["Env"] if "=" in item)
    require(all(runtime.get(k) == mode for k in flags), "installed media mode differs from backup plan")
    status = state(plan, plan, backend_id)
    require(status in ("OLD stopped", "OLD running"), "backend differs from backup plan")
    if online:
        require(mode == "true" and status == "OLD running", "online backup requires the running admitted media backend")
        require(not os.path.lexists(host.GATE.with_name("recovery-hold")), "online backup refused during recovery")
    elif mode == "true":
        require(status == "OLD stopped", "media backup requires the stopped admitted backend")
    return cid, observed["Id"], mode


def repair_object(plan, recipient, key, source):
    from production_traffic import GATE, check_edge_closed
    require(KEY.fullmatch(key), "invalid object key")
    with host.operator_lock(GATE):
        require(not any(os.path.lexists(GATE.with_name(name)) for name in ("open", "activate", "recovery-hold")),
                "private repair requires closed ingress and the authoritative database")
        check_edge_closed()
        cid, _, mode = backup_target(plan, recipient)
        require(mode == "true", "private media required")
        with snapshot(cid, repair=True, **host.lock_args()) as (_token, rows, _wal):
            row = rows.get(key)
            require(row and row[3], "key no longer current or eligible for repair")
            with source.open("rb") as original:
                data = verified_bytes(original, row)
            client = storage_client(json.loads(plan.read_bytes())["services"]["backend"]["environment"])
            client.put_object(Bucket=PRODUCTION_BUCKET, Key=key, IfNoneMatch="*", Body=data, CacheControl="no-store",
                              ContentType={"jpg": "image/jpeg", "png": "image/png", "pdf": "application/pdf"}[key.rsplit(".", 1)[1]])
            with closing(client.get_object(Bucket=PRODUCTION_BUCKET, Key=key)["Body"]) as actual:
                verified_bytes(actual, row)


def backup(plan, point, recipient, metadata_only, *, online=False):
    from production_profile import PRODUCTION_PROJECT, pg_query, plan_schema

    def cold():
        require(not any(os.path.lexists(host.GATE.with_name(n)) for n in ("open", "activate")) and
                not subprocess.check_output(["docker", "ps", "-q", "--filter", "label=com.docker.compose.project=" +
                                             PRODUCTION_PROJECT, "--filter", "label=com.docker.compose.service=backend"]).strip(),
                "close traffic and stop the production backend before media backup")

    with host.operator_lock():
        require(not (online and metadata_only), "online backup must include media")
        binding = backup_target(plan, recipient, online=online)
        cid, _, mode = binding
        media = mode == "true"
        require(media or not metadata_only, "metadata-only requires media mode")
        if media and not online:
            cold()
        storage = host.database_space(cid)
        point.mkdir(mode=0o700)
        doc = json.loads(plan.read_bytes())
        (point / "schema-version").write_text(str(plan_schema(doc)) + "\n")
        (point / "postgres-image").write_bytes(subprocess.check_output(["docker", "inspect", "--format", "{{.Config.Image}}", cid]))
        require(backup_target(plan, recipient, online=online) == binding, "backup target changed before snapshot")
        source = (" FROM pg_control_system(), pg_control_checkpoint(), pg_database "
                  "WHERE datname=current_database()")
        lineage_sql = "SELECT system_identifier, oid, timeline_id"
        if online:
            lineage = pg_query(cid, "postgres", lineage_sql + source)
        env = doc["services"]["backend"]["environment"]
        captured = capture(cid, env, point, recipient, media, metadata_only, storage=storage, online=online, **host.lock_args())
        members = ["database.dump.age", "postgres-image", "schema-version"]
        if media:
            if not metadata_only:
                members.append("media.tar.age")
            if online:
                # A later WAL position would misdate the exported MVCC snapshot for two-point recovery.
                wal, rows, etags = captured
                require(pg_query(cid, "postgres", lineage_sql + source) == lineage, "online source lineage changed")
                coordinate = lineage + "|" + wal
            else:
                coordinate = pg_query(cid, "postgres", lineage_sql + ", pg_current_wal_insert_lsn()" + source)
            (point / "source-coordinate").write_text(coordinate + "\n")
            members.append("source-coordinate")
            if not online:
                cold()
        partial = point / "SHA256SUMS.partial"
        with partial.open("xb") as output:
            subprocess.run(["shasum", "-a", "256", *members], cwd=point, stdout=output, check=True)
        require(backup_target(plan, recipient, online=online) == binding, "backup target changed during capture")
        host.check_space([(point, 0), (storage[0], 0)])
        if online:
            verify_online_media(cid, env, rows, etags)
        partial.rename(point / "SHA256SUMS")
        print("Encrypted backup written")


def main():
    args = sys.argv[1:]
    if len(args) == 5 and args[0] == "repair-object":
        return repair_object(Path(args[1]), args[2], args[3], Path(args[4]))
    require(len(args) in (4, 5) and args[0] == "backup" and (len(args) == 4 or args[4] in ("--metadata-only", "--online")),
            "expected admitted compose file, new directory, recipient and optional --metadata-only or --online")
    os.umask(0o077)
    backup(Path(args[1]), Path(args[2]), args[3], args[4:] == ["--metadata-only"], online=args[4:] == ["--online"])


if __name__ == "__main__":
    try:
        main()
    except Refused as error:
        sys.exit(f"Media operation refused: {error}")
    except Exception as error:
        sys.exit("Media operation failed: " + type(error).__name__)
