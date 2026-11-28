"""Rehearse the production maintenance phases on a disposable local Docker project."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import re
import shutil
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

import production_update as release
import production_profile as profile
import production_traffic as traffic
from test_participation_cutover import SEED
import restore_test

PG = "postgres@sha256:223efeb196329b55b3b9a13db144956411dbc18e727e59f681c3e0aede6475cf"
TARGET = os.environ.get("HT_REHEARSAL_IMAGE", "ht-production-reliability:rehearsal")
MEDIA_KEY = "venue-info/12345678-0000-0000-0000-000000000001.jpg"
FIXTURE = """
INSERT INTO venue_subscriptions(venue_id,status) VALUES(17001,'ACTIVE')
ON CONFLICT (venue_id) DO UPDATE SET status='ACTIVE';
INSERT INTO shift_extension_requests(id,venue_id,table_session_id,table_id,tab_id,order_id,requested_by_user_id,
status,duration_minutes,price_minor,currency,current_orderable_until,requested_until,idempotency_key)
OVERRIDING SYSTEM VALUE VALUES(17001,17001,17001,17001,17001,17001,17001,'APPROVED',60,5000,'RUB',
now(),now()+interval '1 hour','fixture-extension');
INSERT INTO order_service_charges(order_id,venue_id,table_session_id,tab_id,source,source_request_id,label,
unit_price_minor,total_minor) VALUES(17001,17001,17001,17001,'SHIFT_EXTENSION',17001,'fixture',5000,5000);
INSERT INTO telegram_outbox(chat_id,method,payload_json,status) VALUES(17001,'sendMessage','{}','NEW');
INSERT INTO venue_info_sections(id,venue_id,title,section_type) OVERRIDING SYSTEM VALUE VALUES(17001,17001,'fixture','text');
"""
SNAPSHOT = """SELECT jsonb_build_array(
(SELECT to_jsonb(v) FROM venues v WHERE id=17001), (SELECT to_jsonb(s) FROM table_sessions s WHERE id=17001),
(SELECT to_jsonb(o) FROM orders o WHERE id=17001), (SELECT to_jsonb(b) FROM order_batches b WHERE id=17001),
(SELECT to_jsonb(t) FROM tab t WHERE id=17001), (SELECT to_jsonb(m) FROM tab_member m WHERE tab_id=17001),
(SELECT to_jsonb(i)-'rejected_table_id' FROM guest_batch_idempotency i WHERE batch_id=17001),
(SELECT to_jsonb(e) FROM shift_extension_requests e WHERE id=17001),
(SELECT to_jsonb(c) FROM order_service_charges c WHERE source_request_id=17001),
(SELECT to_jsonb(x) FROM telegram_outbox x WHERE chat_id=17001),
(SELECT to_jsonb(m) FROM venue_info_section_media m WHERE section_id=17001))"""


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


class CutoverRehearsal(unittest.TestCase):
    def test_closed_cutover_and_restore(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.ExitStack() as stack:
            self.exercise(Path(tmp).resolve(), stack)

    def exercise(self, root, stack):
        project = "ht-prod-rehearse-" + uuid.uuid4().hex[:12]
        gate_dir = root / "gate"
        gate_dir.mkdir()
        gate = gate_dir / "open"
        old, new = root / "old.json", root / "new.json"
        source = profile.PARTICIPATION_SOURCE
        if TARGET == "ht-production-reliability:rehearsal":
            target_ref = docker("image", "inspect", TARGET, "--format", "{{index .RepoDigests 0}}")
            revision = secrets.token_hex(20)  # The uncommitted build has no published revision.
        else:
            self.assertRegex(TARGET, "^" + profile.IMAGE_RE + "$")
            target_ref = TARGET
            revision = os.environ["HT_REHEARSAL_REVISION"]
            self.assertTrue(re.fullmatch("[0-9a-f]{40}", revision))
            label = '{{index .Config.Labels "org.opencontainers.image.revision"}}'
            self.assertEqual(docker("image", "inspect", "--platform", "linux/amd64", "--format", label, TARGET), revision)
        target = dict(revision=revision, image=target_ref)
        values = {key: secrets.token_hex(32) for key in profile.SECRET_KEYS}
        values.update(POSTGRES_IMAGE=PG, TELEGRAM_BOT_ENABLED="false", TELEGRAM_BOT_TOKEN="",
                      PLATFORM_OWNER_TELEGRAM_ID="", TELEGRAM_MAIN_MINI_APP_CONFIGURED="false",
                      PRODUCTION_MEDIA_ENABLED="true", STAFF_PHOTOS_R2_BUCKET=profile.PRODUCTION_BUCKET,
                      STAFF_PHOTOS_R2_ENDPOINT="https://" + "0"*32 + ".eu.r2.cloudflarestorage.com",
                      STAFF_PHOTOS_R2_REGION="auto", STAFF_PHOTOS_R2_JURISDICTION="eu",
                      STAFF_PHOTOS_R2_ACCESS_KEY="0"*32, STAFF_PHOTOS_R2_SECRET_KEY="1"*64)
        for path, candidate, version in ((old, source, 143), (new, target, 147)):
            doc = json.loads(profile.render(values, candidate))
            doc.update(name=project)
            doc["x-hookah-schema"] = version
            for key, item in doc["networks"].items():
                item.update(name=project + "_" + key, internal=True)
            for key, item in doc["volumes"].items():
                item["name"] = project + "_" + key
            backend = doc["services"]["backend"]
            backend.pop("ports")
            for mount in backend["volumes"]:
                if mount["target"] == "/run/hookah-ingress":
                    mount["source"] = str(gate_dir)
            profile.publish_plan(path, json.dumps(doc).encode())
            # Docker Desktop has no production systemd slice. Keep the verified limits in
            # the controller's plan and omit only these two host settings in the fixture.
            for service in doc["services"].values():
                service.pop("cgroup_parent", None)
                service.pop("annotations", None)
            path.with_suffix(".desktop.json").write_text(json.dumps(doc))

        def compose(plan, *args):
            return docker("compose", "--env-file", "/dev/null", "-p", project, "-f",
                          str(plan.with_suffix(".desktop.json")), *args)

        def info(cid):
            return json.loads(docker("inspect", cid))[0]

        def get(path, *, public=False):
            args = ["-H", "X-Hookah-Pilot-Ingress: public"] if public else []
            reply = subprocess.run(["docker", "exec", project + "-backend-1", "curl", "-s", "--max-time", "3",
                                    "--noproxy", "*", "-w", "\n%{http_code}", *args,
                                    "http://127.0.0.1:8080" + path], capture_output=True)
            if reply.returncode:
                raise OSError("fixture loopback unavailable")
            body, _, status = reply.stdout.rpartition(b"\n")
            return int(status), body

        def ready(plan):
            expected = traffic.backend(plan)["environment"]["APP_VERSION"]
            deadline = time.monotonic() + 150
            while time.monotonic() < deadline:
                try:
                    ver = json.loads(get("/version")[1])
                    if ver["version"] == expected and ver["env"] == "prod" and get("/db/health")[0] == 200:
                        return ver["instance"]
                except (OSError, ValueError, KeyError):
                    pass
                time.sleep(.3)
            self.fail("production-mode fixture did not start")

        def service(name):
            return compose(new, "ps", "--no-trunc", "-aq", name)

        def state(a, b, cid=None):
            observed = info(cid or service("backend"))
            ref = observed["Config"]["Image"]
            image = json.loads(docker("image", "inspect", "--platform", "linux/amd64", ref))[0]
            self.assertTrue(profile.matches_running_image(observed, ref, image))
            for label, plan in (("OLD", a), ("NEW", b)):
                if observed["Config"]["Image"] == traffic.backend(plan)["image"]:
                    return label + (" running" if observed["State"]["Running"] else " stopped")
            self.fail("foreign fixture backend")

        def worker(a, b):
            cid = service("pdf-worker")
            return cid, next(plan for plan in (a, b) if info(cid)["Config"]["Image"] == traffic.backend(plan)["image"])

        stack.callback(compose, new, "down", "--volumes", "--timeout", "5")
        compose(old, "up", "-d", "--pull", "never", "--wait", "postgres", "pdf-worker")
        compose(old, "up", "-d", "--no-deps", "--pull", "never", "backend")
        instance = ready(old)
        cid = service("postgres")
        def sql(query):
            return profile.pg_query(cid, "postgres", query)
        def bound(plan, *args, transition_from=None):
            self.assertEqual(service("postgres"), cid)
            history = sql((profile.PROFILE / "history.sql").read_text())
            profile.check_history(history, range(143, 148) if transition_from else [profile.plan_schema(json.loads(plan.read_text()))])
            return cid
        bound(old)
        data = MEDIA_KEY.encode()
        key_hash = hashlib.sha256(data).hexdigest()
        sql("SET ROLE hookah_app; " + SEED + FIXTURE +
            "INSERT INTO venue_info_section_media(section_id,media_type,telegram_file_id,public_id,source_type,"
            "object_key,status,size_bytes,checksum_sha256) VALUES(17001,'image','','" + Path(MEDIA_KEY).stem +
            "','OBJECT_STORAGE','" + MEDIA_KEY + "','READY'," + str(len(data)) + ",'" + key_hash + "')")
        sql("INSERT INTO venue_info_media_cleanup(object_key,public_id,actor_id,venue_id,section_id) "
            f"VALUES('{MEDIA_KEY}','{Path(MEDIA_KEY).stem}',17001,17001,17001)")
        before = sql(SNAPSHOT)
        self.assertNotIn(None, json.loads(before))
        identity = root / "fixture-key"
        subprocess.run(["age-keygen", "-o", identity], check=True, stderr=subprocess.DEVNULL)
        recipient = root / "recipient"
        recipient.write_text(subprocess.check_output(["age-keygen", "-y", identity], text=True))
        spool = root / "spool"
        spool.mkdir()
        def restore(point, schema):
            offsite = root / ("offsite-" + str(schema))
            shutil.copytree(point, offsite)
            pin = release.points.verified(offsite)
            attempt = uuid.uuid4().hex
            name = "ht-restore-" + attempt
            assertions = root / "assertions.sql"
            assertions.write_text("DO $$BEGIN IF (" + SNAPSHOT + ") IS DISTINCT FROM $json$" + before +
                                  "$json$::jsonb THEN RAISE EXCEPTION 'fixture drift'; END IF; END$$;")
            try:
                counts = restore_test.restore(offsite, identity, PG, assertions, attempt)
                self.assertEqual(counts["selected"], 1)
            finally:
                if docker("ps", "-aq", "--filter", "name=^/" + name + "$"):
                    self.assertEqual(info(name)["Config"]["Labels"][restore_test.LABEL], attempt)
                    docker("rm", "-f", name)
                vol = name + "-data"
                if docker("volume", "ls", "-q", "--filter", "name=^" + vol + "$"):
                    self.assertEqual(json.loads(docker("volume", "inspect", vol))[0]["Labels"][restore_test.LABEL], attempt)
                    docker("volume", "rm", vol)
            return dict(point=point.name, pin=pin, schema=str(schema), postgres=PG,
                        tools=release.media_backup.recovery_tools(profile.ROOT), result="PASS",
                        source=restore_test.coordinate(point)[0])

        for module, attr, value in (
            (profile, "GATE", gate), (profile, "PRODUCTION_PROJECT", project),
            (profile, "verified_database", bound), (profile.host, "GATE", gate),
            (profile.host, "lock_args", lambda: {}), (profile.host, "database_space", lambda _: (root, 0, 0)),
            (profile.host, "image_space", Mock()),
            (release.media_backup, "backup_space", lambda *args: (100*1024**2, 1024**2)),
            (release.media_backup, "verified_database", bound),
            (release.media_backup, "storage_client", lambda _: SimpleNamespace(
                get_object=lambda **k: {"Body": io.BytesIO(data), "ETag": '"fixture"'},
                head_object=lambda **k: {"ContentLength": len(data), "ETag": k["IfMatch"]})),
            (traffic, "compose", compose), (traffic, "state", state), (traffic, "rollback_worker", worker),
            (traffic, "check_edge_closed", lambda: self.assertFalse(gate.exists())),
            (profile, "check_target_image", lambda plan: docker("image", "inspect", traffic.backend(plan)["image"])),
            (release, "closed_smoke", lambda plan: (ready(plan), self.assertEqual(get("/miniapp/", public=True)[0], 503))),
        ):
            stack.enter_context(patch.object(module, attr, value))
        decision = source["revision"] + " " + instance
        gate.write_text(decision)
        gate.with_name("activate").write_text(decision)
        self.assertEqual(get("/miniapp/", public=True)[0], 200)
        def close(action, plan):
            self.assertEqual(action, "close")
            gate.unlink(missing_ok=True)
            gate.with_name("activate").unlink(missing_ok=True)
            compose(plan, "stop", "backend")
        stack.enter_context(patch.object(release, "native", close))
        stack.enter_context(patch.object(release, "schedule"))
        op = release.ParticipationCutover.__new__(release.ParticipationCutover)
        op.old, op.new, op.journal = old, new, root / "journal"
        op.config = {"BACKUP_SPOOL": spool, "AGE_RECIPIENT_FILE": recipient}
        op.record = {"point": release.points.datetime.now(release.points.timezone.utc).strftime(release.points.STAMP),
                     "writers": op.old_writers()}
        # The local harness replaces systemd stop only. Docker stop, removal and restricted network are real.
        real_run = subprocess.run
        def run(cmd, *args, **kwargs):
            if cmd == ["systemctl", "stop", release.TIMER]:
                return subprocess.CompletedProcess(cmd, 0)
            return real_run(cmd, *args, **kwargs)
        started = time.monotonic()
        with patch.object(subprocess, "run", run):
            op.fence()
        self.assertEqual(op.record["phase"], "cold-backup")
        with patch.object(release.media_backup, "capture", side_effect=OSError("disk full")), self.assertRaises(OSError):
            op.backup()
        self.assertFalse(op.point().exists())
        self.assertEqual(state(old, new), "OLD stopped")
        with patch.object(release, "ask", return_value={"result": "FAIL"}), self.assertRaises(profile.Refused):
            op.backup()
        bound(old)
        self.assertEqual(op.record["phase"], "cold-backup")
        def resume():
            resumed = release.ParticipationCutover.__new__(release.ParticipationCutover)
            resumed.__dict__.update(op.__dict__, record=json.loads(op.journal.read_text()))
            return resumed
        op = resume()
        with patch.object(release, "ask", side_effect=lambda *a, **k:restore(op.point(), 143)):
            op.backup()
        op = resume()
        op.replace()
        self.assertEqual(state(old, new), "NEW stopped")
        bound(old)
        op = resume()
        # Terminate the controller after Flyway and technical smoke, before publishing acceptance.
        with patch.object(op, "phase", side_effect=InterruptedError()), self.assertRaises(InterruptedError):
            op.migrate()
        bound(new)
        with self.assertRaises(profile.Refused):
            bound(old)
        db_state = sql("SELECT jsonb_agg(to_jsonb(f) ORDER BY installed_rank) FROM flyway_schema_history f")
        compose(new, "stop", "backend")
        op = resume()
        op.migrate()
        self.assertEqual(op.record["phase"], "acceptance")
        self.assertEqual(sql(SNAPSHOT), before)
        self.assertEqual(sql("SELECT jsonb_agg(to_jsonb(f) ORDER BY installed_rank) FROM flyway_schema_history f"), db_state)
        self.assertEqual(sql("SELECT count(*) FROM shift_extension_request_keys"), "0")
        print(f"Cold fence to closed acceptance with injected failures: {time.monotonic() - started:.1f}s", flush=True)
        # Model the owner's explicit approval on this isolated instance. Exercise the
        # real production JVM tickets and the scheduled V147 backup producer.
        self.assertFalse(gate.exists())
        target_ticket = revision + " " + ready(new)
        gate.with_name("activate").write_text(target_ticket)
        gate.write_text(target_ticket)
        self.assertEqual(get("/miniapp/", public=True)[0], 200)
        self.assertEqual(get("/api/guest/catalog", public=True)[0], 401)
        with patch.object(profile.host, "operator_lock", contextlib.nullcontext):
            release.points.create(new, recipient, spool, root / "qualified.sha256")
        point = spool / release.points.newest(spool)
        restore(point, 147)
        self.assertEqual(info(service("pdf-worker"))["State"]["Health"]["Status"], "healthy")



if __name__ == "__main__":
    unittest.main()
