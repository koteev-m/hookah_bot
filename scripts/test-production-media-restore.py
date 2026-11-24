"""Rehearse production recovery from two PostgreSQL 17 points and from a stale point alone."""
import argparse
from contextlib import contextmanager, nullcontext
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from unittest.mock import patch
from uuid import UUID

from production_profile import PROFILE, ROOT, Refused, require, require_empty_enrollment

sys.path.insert(0, str(PROFILE))
import media_backup
import production_traffic as traffic
import restore_test as recovery


def sha(data):
    return hashlib.sha256(data).hexdigest()


def wait_postgres(cid, *, tcp=False):
    args = ["docker", "exec", cid, "pg_isready", "-q", "-U", "postgres"]
    deadline = time.monotonic() + 60
    while subprocess.run(args + (["-h", "127.0.0.1"] if tcp else []), capture_output=True).returncode:
        require(time.monotonic() < deadline, "fixture PostgreSQL startup timeout")
        time.sleep(0.3)


def sql(cid, query):
    return recovery.docker("exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1",
                           "-U", "postgres", "-d", "hookah_prod", payload=query.encode())


@contextmanager
def migrated_source(root, image):
    libs = ROOT / "backend/app/build/install/app/lib"
    require(any(libs.glob("flyway-core-*.jar")), "run :backend:app:installDist first")
    cid = recovery.docker("run", "--pull", "never", "-d", "--platform", "linux/amd64",
                          "--tmpfs", "/var/lib/postgresql/data", "-e", "POSTGRES_PASSWORD=synthetic-fixture",
                          "-e", "POSTGRES_DB=hookah_prod", "-p", "127.0.0.1::5432", image)
    try:
        wait_postgres(cid, tcp=True)
        sql(cid, "CREATE ROLE hookah_app LOGIN PASSWORD 'synthetic-app'; ALTER DATABASE hookah_prod OWNER TO hookah_app")
        traffic.check_successor_database(cid, pristine=True)
        sql(cid, "CREATE ROLE obsolete_custodian NOLOGIN")
        try:
            traffic.check_successor_database(cid, pristine=True)
        except Refused:
            pass
        else:
            raise AssertionError("successor accepted an inherited role")
        sql(cid, "DROP ROLE obsolete_custodian")
        port = recovery.docker("port", cid, "5432/tcp").removeprefix("127.0.0.1:")
        require(port.isdecimal(), "source fixture must bind to loopback")
        java = root / "Migrate.java"
        java.write_text("""import org.flywaydb.core.Flyway;
class Migrate {
    public static void main(String[] args) {
        Flyway.configure().dataSource(args[0], "hookah_app", "synthetic-app")
            .locations("classpath:db/migration/postgresql").load().migrate();
    }
}""")
        subprocess.run(["java", "-cp", str(libs / "*"), java, f"jdbc:postgresql://127.0.0.1:{port}/hookah_prod"],
                       check=True, stdout=subprocess.DEVNULL)
        traffic.check_successor_database(cid, pristine=False)
        with traffic.host.operator_lock(root / "open"), traffic.successor_binding_window(cid):
            writer = subprocess.run(["docker", "exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1",
                                     "-v", "VERBOSITY=verbose", "-U", "hookah_app", "-d", "hookah_prod"],
                                    input="SET lock_timeout='250ms'; UPDATE loyalty_payment_policy SET first_item_id=2;",
                                    capture_output=True, text=True, timeout=10)
            require(writer.returncode and "55P03" in writer.stderr and "lock timeout" in writer.stderr,
                    "concurrent writer was not fenced by successor registration")
        sql(cid, "UPDATE loyalty_payment_policy SET first_item_id=2")
        try:
            traffic.check_successor_database(cid, pristine=False)
        except Refused:
            pass
        else:
            raise AssertionError("successor accepted a non-bootstrap loyalty policy")
        sql(cid, "UPDATE loyalty_payment_policy SET first_item_id=1")
        yield cid
    finally:
        recovery.docker("rm", "-f", cid)


class SyntheticStorage:
    def get_object(self, *, Bucket, Key):
        require(Bucket == media_backup.PRODUCTION_BUCKET, "unexpected fixture bucket")
        return {"Body": io.BytesIO(Key.encode())}


def rehearse_online(root, cid, recipient, identity, image, key):
    """Exercise publication and restore with real writers, pg_dump and age, using a synthetic object store."""
    sql(cid, "CREATE TABLE online_backup_probe(n int); GRANT SELECT ON online_backup_probe TO hookah_app")
    plan = root / "online-plan"
    plan.write_text(json.dumps({"x-hookah-schema": 143, "services": {"backend": {"environment": {}}}}))
    export = media_backup.snapshot

    @contextmanager
    def concurrent(target, **kwargs):
        with export(target, **kwargs) as frozen:
            sql(cid, "INSERT INTO online_backup_probe VALUES (1)")
            yield frozen

    class Storage:
        fault = ""

        def get_object(self, *, Bucket, Key):
            data = Key.encode()
            if Key == key and self.fault == "corrupt":
                data = b"!" * len(data)
            return {"Body": io.BytesIO(data), "ETag": '"original"'}

        def head_object(self, *, Bucket, Key, IfMatch):
            require(IfMatch == '"original"' and Bucket == media_backup.PRODUCTION_BUCKET, "conditional R2 check missing")
            if Key == key:
                if self.fault == "removed":
                    raise FileNotFoundError("deleted after GET")
                if self.fault == "replaced":
                    raise OSError("precondition failed after replacement")
                if self.fault == "hidden":
                    sql(cid, f"UPDATE venue_info_section_media SET is_visible=false WHERE object_key='{key}'")
            return {"ETag": IfMatch, "ContentLength": len(Key)}

    client = Storage()
    cli_output = subprocess.check_output

    def output(cmd, **kwargs):
        if cmd[:3] == ["docker", "inspect", "--format"]:
            return (image + "\n").encode()
        return cli_output(cmd, **kwargs)

    with patch.object(media_backup.host, "operator_lock", return_value=nullcontext()), \
            patch.object(media_backup.host, "lock_args", return_value={}), \
            patch.object(media_backup.host, "database_space", return_value=(root, 16*1024**2, 0)), \
            patch.object(media_backup, "backup_target", return_value=(cid, "running", "true")), \
            patch.object(media_backup, "snapshot", concurrent), patch.object(media_backup, "storage_client", return_value=client), \
            patch.object(subprocess, "check_output", side_effect=output):
        point = root / "online-ok"
        media_backup.backup(plan, point, recipient, False, online=True)
        recovery.verify_archive(point, image)
        recovery.coordinate(point)
        sql(cid, "CREATE DATABASE online_restored OWNER hookah_app")
        restore_sql = ["exec", "-i", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "online_restored"]
        recovery.load_database(point, identity, cid, "online_restored", restore_sql)
        require(recovery.docker(*restore_sql, payload=b"SELECT count(*) FROM online_backup_probe") == "0"
                and sql(cid, "SELECT count(*) FROM online_backup_probe") == "1", "dump includes a post-snapshot write")
        rows = media_backup.inventory(recovery.docker(*restore_sql, payload=media_backup.INVENTORY.read_bytes()))
        require(key in rows, "missing fixture media")
        counts = media_backup.restore(point, identity, rows, rows)
        require(counts["selected"] + counts["excluded"] == len(rows), "incomplete online archive")
        sql(cid, "DROP DATABASE online_restored")
        for fault, cause in [("corrupt", "checksum mismatch"), ("removed", "deleted after GET"),
                             ("replaced", "precondition failed"), ("hidden", "required media changed")]:
            client.fault = fault
            failed = root / ("online-" + fault)
            try:
                media_backup.backup(plan, failed, recipient, False, online=True)
            except (OSError, Refused) as exc:
                require(cause in str(exc), "unexpected online failure boundary")
            else:
                raise AssertionError("unsafe online point accepted")
            require(not (failed / "SHA256SUMS").exists(), "failed online point was published")
    sql(cid, f"UPDATE venue_info_section_media SET is_visible=true WHERE object_key='{key}'; DROP TABLE online_backup_probe")
    print("PASS online snapshot + independent writer, encrypted PG and media restore, four refusal cases")


def rehearse_repair(root, cid, recipient, live_key, denied_keys):
    objects = {}
    class PrivateStorage:
        def put_object(self, *, Bucket, Key, IfNoneMatch, Body, ContentType, CacheControl):
            require(Bucket == media_backup.PRODUCTION_BUCKET and IfNoneMatch == "*" and Key not in objects,
                    "cannot overwrite a private object")
            require(CacheControl == "no-store" and ContentType == "image/jpeg", "wrong object metadata")
            objects[Key] = Body
        def get_object(self, *, Bucket, Key):
            require(Bucket == media_backup.PRODUCTION_BUCKET, "wrong bucket")
            return {"Body": io.BytesIO(objects[Key])}

    plan, source = root / "repair-plan", root / "repair-bytes"
    plan.write_text(json.dumps({"services": {"backend": {"environment": {}}}}))
    with patch.object(traffic, "GATE", root / "open"), patch.object(traffic, "check_edge_closed"), \
            patch.object(media_backup, "backup_target", return_value=(cid, "stopped", "true")), \
            patch.object(media_backup, "storage_client", return_value=PrivateStorage()):
        for key, data, valid in [(live_key, b"corrupt", False), (live_key, live_key.encode(), True),
                                 (live_key, live_key.encode(), False), *((k, k.encode(), False) for k in denied_keys)]:
            source.write_bytes(data)
            try:
                media_backup.repair_object(plan, recipient, key, source)
            except Refused:
                require(not valid, "current object repair failed")
            else:
                require(valid, "unsafe private repair accepted")
        require(objects == {live_key: live_key.encode()}, "repair changed other objects")
    print("PASS authoritative PostgreSQL private repair, checksum, conditional create, tombstones and hidden media")


def capture(point, cid, recipient, image, *, include_bytes):
    point.mkdir()
    members = ["database.dump.age", "postgres-image", "schema-version"]
    with patch.object(media_backup, "storage_client", return_value=SyntheticStorage()):
        media_backup.capture(cid, {}, point, recipient, True, not include_bytes)
    (point / "postgres-image").write_text(image + "\n")
    (point / "schema-version").write_text("143\n")
    if include_bytes:
        members.append("media.tar.age")
    coordinate = sql(cid, "SELECT system_identifier,oid,timeline_id,pg_current_wal_insert_lsn() "
                          "FROM pg_control_system(),pg_control_checkpoint(),pg_database WHERE datname=current_database()")
    (point / "source-coordinate").write_text(coordinate + "\n")
    members.append("source-coordinate")
    manifest = "".join(f"{sha((point / member).read_bytes())}  {member}\n" for member in members).encode()
    (point / "SHA256SUMS").write_bytes(manifest)
    return sha(manifest)


def rehearse(root, image):
    identity = root / "fixture.agekey"
    subprocess.run(["age-keygen", "-o", identity], check=True, stderr=subprocess.DEVNULL)
    recipient = subprocess.check_output(["age-keygen", "-y", identity], text=True).strip()
    byte_point, metadata = root / "bytes", root / "metadata"
    keys = [f"venue-info/{UUID(int=n)}.jpg" for n in range(1, 6)]
    with migrated_source(root, image) as cid:
        venue = sql(cid, "INSERT INTO venues(name,status) VALUES('Fixture','PUBLISHED') RETURNING id")
        sql(cid, f"INSERT INTO venue_subscriptions(venue_id,status) VALUES ({venue},'ACTIVE') "
                 "ON CONFLICT (venue_id) DO UPDATE SET status='ACTIVE'")
        section = sql(cid, f"INSERT INTO venue_info_sections(venue_id,title,section_type) VALUES ({venue},'Fixture','text') RETURNING id")

        def upload(key):
            public = Path(key).stem
            sql(cid, "INSERT INTO venue_info_section_media(section_id,media_type,telegram_file_id,public_id,source_type,"
                     f"object_key,status,size_bytes,checksum_sha256) VALUES ({section},'image','','{public}','OBJECT_STORAGE',"
                     f"'{key}','READY',{len(key)},'{sha(key.encode())}'); "
                     "INSERT INTO venue_info_media_cleanup(object_key,public_id,actor_id,venue_id,section_id) "
                     f"VALUES('{key}','{public}',42,{venue},{section})")

        for key in keys[:4]:
            upload(key)
        rehearse_online(root, cid, recipient, identity, image, keys[0])
        sql(cid, f"INSERT INTO users(telegram_user_id) VALUES(42); INSERT INTO venue_members(venue_id,user_id,role) "
                 f"VALUES ({venue},42,'OWNER')")
        table = sql(cid, f"INSERT INTO venue_tables(venue_id,table_number) VALUES({venue},1) RETURNING id")
        session = sql(cid, "INSERT INTO table_sessions(venue_id,table_id,started_at,last_activity_at,expires_at,status) "
                           f"VALUES({venue},{table},now(),now(),now()+interval '1 hour','ACTIVE') RETURNING id")
        shared = sql(cid, f"INSERT INTO tab(venue_id,table_session_id,type) VALUES({venue},{session},'SHARED') RETURNING id")
        sql(cid, f"INSERT INTO tab_member(tab_id,user_id,role) VALUES({shared},42,'MEMBER')")
        try:
            require_empty_enrollment(cid)
        except Refused:
            pass
        else:
            raise AssertionError("ordinary bind accepted an unmarked populated database")
        export_snapshot = media_backup.snapshot
        @contextmanager
        def concurrent_change(target, *, online, pass_fds):
            with export_snapshot(target, online=online, pass_fds=pass_fds) as frozen:
                sql(target, f"UPDATE venue_info_section_media SET is_visible=false WHERE object_key='{keys[0]}'")
                require(sql(target, f"SELECT is_visible FROM venue_info_section_media WHERE object_key='{keys[0]}'") == "f",
                        "concurrent transaction did not commit the visibility change")
                yield frozen
        with patch.object(media_backup, "snapshot", concurrent_change):
            old_pin = capture(byte_point, cid, recipient, image, include_bytes=True)
        sql(cid, f"UPDATE venue_info_section_media SET is_visible=true WHERE object_key='{keys[0]}'; "
                 f"DELETE FROM tab_member WHERE tab_id={shared}; UPDATE tab SET status='CLOSED' WHERE id={shared}; "
                 f"UPDATE table_sessions SET status='ENDED',ended_at=now() WHERE id={session}")
        removed = f"object_key IN ('{keys[1]}','{keys[3]}')"
        sql(cid, f"UPDATE venue_info_section_media SET status='DELETED',deleted_at=now() WHERE {removed}; "
                 f"UPDATE venue_info_media_cleanup SET due_at=now() WHERE {removed}; "
                 f"UPDATE venue_info_section_media SET is_visible=FALSE WHERE object_key='{keys[2]}'")
        sql(cid, f"DELETE FROM venue_members WHERE venue_id={venue} AND user_id=42")
        upload(keys[4])
        rehearse_repair(root, cid, recipient, keys[0], keys[1:4])
        new_pin = capture(metadata, cid, recipient, image, include_bytes=False)

    # The source container and its database no longer exist. Recover solely from the two points.
    state = root / "hookah-production-restore"
    try:
        subprocess.run(["bash", PROFILE / "restore-test.sh", byte_point, identity, image, "--point-sha256", old_pin,
                        "--metadata-point", metadata, "--metadata-sha256", new_pin, "--keep-quarantine"],
                       env=dict(os.environ, XDG_STATE_HOME=str(root)), check=True)
        receipt, = state.glob("*-quarantine.json")
        record = json.loads(receipt.read_bytes())
        require((record["point_sha256"], record["metadata_sha256"]) == (old_pin, new_pin), "wrong recovery receipt")
        recovered = "ht-restore-" + record["attempt"]
        recovery.docker("restart", recovered)
        wait_postgres(recovered)
        require(recovery.docker("inspect", "--format", "{{.HostConfig.NetworkMode}}", recovered) == "none", "recovery has a network")
        require(sql(recovered, "SELECT object_key,encode(sha256(content),'hex') FROM hookah_recovery.objects") ==
                keys[0] + "|" + sha(keys[0].encode()), "incorrect recovered bytes")
        require(sql(recovered, "SELECT count(*) FROM venue_info_media_cleanup WHERE due_at IS NOT NULL") == "2" and
                sql(recovered, "SELECT count(*) FROM venue_info_section_media WHERE status='READY' AND is_visible") == "2",
                "lost latest metadata or tombstones")
        denied = subprocess.run(["docker", "exec", recovered, "psql", "-Xqw", "-U", "hookah_app", "-d", "hookah_prod",
                                 "-c", "SELECT 1"], capture_output=True)
        require(denied.returncode != 0 and b"not permitted to log in" in denied.stderr, "unexpected application login result")
        recovery.cleanup(receipt, recovery.docker("info", "--format", "{{.ID}}"))

        # Recover only the pre-revocation point. It claims that the deleted file and former owner are current.
        subprocess.run(["bash", PROFILE / "restore-test.sh", byte_point, identity, image, "--point-sha256", old_pin,
                        "--keep-quarantine"], env=dict(os.environ, XDG_STATE_HOME=str(root)), check=True)
        receipt, = state.glob("*-quarantine.json")
        archive = "ht-restore-" + json.loads(receipt.read_bytes())["attempt"]
        recovery.docker("restart", archive)
        wait_postgres(archive)
        require(sql(archive, "SELECT count(*) FROM venue_members WHERE role='OWNER' AND user_id=42") == "1",
                "fixture lost the stale grant")
        require(sql(archive, "SELECT count(*) FROM tab_member m JOIN tab t ON m.tab_id=t.id "
                             "WHERE m.user_id=42 AND t.type='SHARED' AND t.status='ACTIVE'") == "1",
                "fixture did not restore the former shared bill participant")
        require(sql(archive, f"SELECT is_visible FROM venue_info_section_media WHERE object_key='{keys[0]}'") == "t",
                "pg_dump and R2 inventory did not observe the same snapshot")
        require(sql(archive, f"SELECT count(*) FROM hookah_recovery.objects WHERE object_key='{keys[1]}'") == "1",
                "fixture lost the stale archived object")
        require(recovery.docker("inspect", "--format", "{{.HostConfig.NetworkMode}}", archive) == "none"
                and sql(archive, "SELECT to_regnamespace('hookah_recovery') IS NULL") == "f"
                and sql(archive, "SELECT rolcanlogin FROM pg_roles WHERE rolname='hookah_app'") == "f",
                "archive became a serving target")
        refused = subprocess.run(["docker", "exec", archive, "psql", "-Xqw", "-U", "hookah_app", "-d", "hookah_prod",
                                  "-c", "SELECT content FROM hookah_recovery.objects LIMIT 1"], capture_output=True)
        require(refused.returncode and b"not permitted to log in" in refused.stderr and not refused.stdout,
                "archive app login or byte disclosure")
        admission = (PROFILE / "recovery-admission.sql").read_text()
        require(sql(archive, admission) == "", "pre-revocation snapshot passed admission")
        with migrated_source(root, image) as replacement:
            require_empty_enrollment(replacement)
            require(bool(re.fullmatch(r"[0-9A-F]+/[0-9A-F]+", sql(replacement, admission))), "fresh admission failed")
            sql(replacement, "INSERT INTO venues(name,status) VALUES('Reconciled venue','DRAFT'); "
                             "INSERT INTO users(telegram_user_id) VALUES(42);")
            venue = sql(replacement, "SELECT id FROM venues WHERE name='Reconciled venue'")
            sql(replacement, f"INSERT INTO venue_members(venue_id,user_id,role) VALUES({venue},42,'OWNER')")
            require(sql(replacement, admission) == "", "copied venue authority passed admission")
            sql(replacement, "DELETE FROM venue_members")
            require(bool(sql(replacement, admission)), "reconciled users cannot be retained without historical grants")
            table = sql(replacement, f"INSERT INTO venue_tables(venue_id,table_number) VALUES({venue},1) RETURNING id")
            session = sql(replacement, "INSERT INTO table_sessions(venue_id,table_id,started_at,last_activity_at,expires_at,status) "
                                       f"VALUES({venue},{table},now(),now(),now()+interval '1 hour','ACTIVE') RETURNING id")
            require(not sql(replacement, admission), "active guest session can recreate a personal tab")
            for kind in ("PERSONAL", "SHARED"):
                tab = sql(replacement, "INSERT INTO tab(venue_id,table_session_id,type,owner_user_id) "
                                       f"VALUES({venue},{session},'{kind}',42) RETURNING id")
                sql(replacement, f"INSERT INTO tab_member(tab_id,user_id,role) VALUES({tab},42,'MEMBER')")
            sql(replacement, "UPDATE table_sessions SET status='ENDED',ended_at=now()")
            require(not sql(replacement, admission), "active historical tab still grants member access")
            sql(replacement, "UPDATE tab SET status='CLOSED',owner_user_id=NULL")
            order = sql(replacement, "INSERT INTO orders(venue_id,table_id,table_session_id,status,closed_at) "
                                     f"VALUES({venue},{table},{session},'CLOSED',now()) RETURNING id")
            sql(replacement, "INSERT INTO order_batches(order_id,source,status,items_snapshot,tab_id) "
                             f"""VALUES({order},'MINIAPP','DELIVERED','[{{"price":350}}]',{tab})""")
            require(not sql(replacement, admission), "historical membership still grants guest support order context")
            sql(replacement, "DELETE FROM tab_member")
            sql(replacement, "UPDATE tab SET owner_user_id=42 WHERE type='PERSONAL'")
            require(not sql(replacement, admission), "old personal owner can still confer guest visit attribution")
            sql(replacement, "UPDATE tab SET owner_user_id=NULL")
            require(bool(sql(replacement, admission)) and sql(replacement, "SELECT count(*) FROM tab WHERE status='CLOSED'") == "2"
                    and sql(replacement, f"SELECT items_snapshot->0->>'price' FROM order_batches WHERE order_id={order}") == "350",
                    "closed financial history must survive admission without granting access")
    finally:
        for receipt in state.glob("*.json"):
            recovery.cleanup(receipt, recovery.docker("info", "--format", "{{.ID}}"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("postgres_image", help="locally cached official PostgreSQL 17 linux/amd64 digest")
    image = parser.parse_args().postgres_image
    require(re.fullmatch(recovery.PG_REF, image), "immutable PostgreSQL fixture image required")
    with tempfile.TemporaryDirectory(prefix="ht-media-recovery-") as temp:
        rehearse(Path(temp), image)
    print("Two-point and stale-only recovery, permanent archive isolation, replacement SQL admission and restart passed")
