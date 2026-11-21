from argparse import Namespace
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import production_profile as profile
import production_traffic as traffic

sys.path.insert(0, str(profile.PROFILE))
import restore_test


class PilotOperationsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name)
        self.marker = self.folder / "open"
        self.sha = "a" * 40
        self.plan = self.folder / "plan.json"
        self.plan.write_text(json.dumps({"services": {"backend": {"environment": {"APP_VERSION": self.sha, "TELEGRAM_BOT_ENABLED": "true"}}}}))

    def test_restart_waits_behind_stale_open_marker_and_preserves_single_process(self):
        log = self.folder / "modes"
        self.marker.write_text("previous instance")
        child = ["sh", "-c", 'echo "$$:$HOOKAH_PILOT_GATE_DIR" >> "$1"; exec sleep 15', "sh", str(log)]
        p = subprocess.Popen(["sh", profile.PROFILE / "traffic-gate.sh", self.marker, *child])
        try:
            time.sleep(.15)
            self.assertFalse(log.exists())
            self.marker.unlink()
            deadline = time.monotonic() + 4
            while not log.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            self.marker.with_name("activate").write_text("new instance")
            self.marker.write_text("new instance")
            self.assertEqual(log.read_text().splitlines(), [f"{p.pid}:{self.folder}"])
        finally:
            p.terminate()
            p.wait(timeout=5)

    def test_open_requires_same_final_process_ready_and_polling_before_public_marker(self):
        instance = "00000000-0000-4000-8000-000000000001"
        ver = dict(instance=instance, polling="running")
        other = dict(ver, instance=instance[:-1] + "2")
        for fault in ("initial readiness", "polling", "new JVM", "database", "interrupted", None):
            with self.subTest(fault=fault), patch.object(traffic, "GATE", self.marker), \
                    patch.object(traffic, "verified_database") as db, patch.object(traffic, "verify_running"), \
                    patch.object(traffic, "check_edge_closed"), patch.object(traffic, "image_assets", return_value={}), \
                    patch.object(traffic, "probe") as probe, patch.object(traffic.time, "monotonic") as clock:
                clock.side_effect = range(0, 5000, 121)
                active = self.marker.with_name("activate")
                active.unlink(missing_ok=True)
                observations = []
                def response(*args):
                    self.assertFalse(self.marker.exists())
                    observations.append(active.exists())
                    if fault == "initial readiness":
                        raise ValueError("bad assets")
                    if active.exists():
                        if fault == "interrupted":
                            raise KeyboardInterrupt
                        if fault == "new JVM":
                            return other
                        if fault == "polling":
                            return dict(ver, polling="standby")
                        return ver
                    return dict(ver, polling="standby")
                probe.side_effect = response
                if fault == "database":
                    db.side_effect = ["cid", profile.Refused("DB changed")]
                args = Namespace(plan=self.plan, action="open")
                if fault:
                    with self.assertRaises((profile.Refused, KeyboardInterrupt)):
                        traffic.operate(args)
                    self.assertFalse(self.marker.exists())
                else:
                    traffic.operate(args)
                    self.assertEqual(observations, [False, True, True])
                    self.assertEqual(self.marker.read_text().strip(), self.sha + " " + instance)
                    self.assertEqual(db.call_count, 2)

    def test_resume_only_starts_verified_stopped_new_by_id(self):
        args = Namespace(action="resume", previous=self.plan, plan=self.plan)
        for state in ("unknown", "OLD stopped", "NEW running", "NEW stopped"):
            with self.subTest(state=state), patch.object(traffic, "GATE", self.marker), \
                    patch.object(traffic, "confirm_transition"), patch.object(traffic, "state", return_value=state), \
                    patch.object(traffic, "compose", return_value="exact-cid"), patch.object(traffic, "check_edge_closed"), \
                    patch.object(traffic, "verify_running"), patch.object(traffic, "verified_database"), \
                    patch.object(traffic, "await_readiness"), patch.object(traffic, "image_assets"), \
                    patch.object(subprocess, "run") as start:
                if state == "NEW stopped":
                    traffic.operate(args)
                    self.assertEqual(start.call_args.args[0], ["docker", "start", "exact-cid"])
                else:
                    with self.assertRaises(profile.Refused):
                        traffic.operate(args)
                    start.assert_not_called()
                self.assertFalse(self.marker.exists())

    def test_resume_stays_closed_on_replacement_db_mismatch_or_interruption(self):
        args = Namespace(action="resume", previous=self.plan, plan=self.plan)
        for states, fault in ((["NEW stopped", "unknown"], "replaced"), ([profile.Refused("wrong volume")], "db"),
                              (["NEW stopped", "NEW stopped"], "killed"), (["NEW stopped", "NEW stopped"], "not ready")):
            with self.subTest(fault=fault), patch.object(traffic, "GATE", self.marker), patch.object(traffic, "confirm_transition"), \
                    patch.object(traffic, "state", side_effect=states), patch.object(traffic, "compose", return_value="cid"), \
                    patch.object(traffic, "check_edge_closed"), patch.object(traffic, "verified_database"), \
                    patch.object(traffic, "verify_running"), patch.object(traffic, "image_assets"), \
                    patch.object(traffic, "await_readiness", side_effect=profile.Refused("not ready")), \
                    patch.object(subprocess, "run", side_effect=KeyboardInterrupt if fault == "killed" else None) as start:
                with self.assertRaises((profile.Refused, KeyboardInterrupt)):
                    traffic.operate(args)
                self.assertEqual(start.called, fault in ("killed", "not ready"))
                self.assertFalse(self.marker.exists())
                self.assertFalse(self.marker.with_name("activate").exists())

    def test_stopped_identity_checks_image_revision_project_and_network(self):
        ref = "ghcr.io/koteev-m/hookah_bot/backend@sha256:" + "b" * 64
        def plan(name, rev):
            path = self.folder / name
            path.write_text(json.dumps({"services": {"backend": {"image": ref, "environment": {"APP_VERSION": rev}}}}))
            return path
        old, new = plan("old", "f" * 40), plan("new", self.sha)
        for changed in ("nothing", "digest", "revision", "project", "network", "foreign name", "foreign mount"):
            labels = {"com.docker.compose.project": "wrong" if changed == "project" else "hookah_production",
                      "com.docker.compose.service": "backend", "com.docker.compose.config-hash": "hash"}
            labels.update({"com.docker.compose.container-number": "1", "com.docker.compose.oneoff": "False"})
            network = {"hookah_production_database": {}, "hookah_production_outbound": {}}
            if changed == "network":
                del network["hookah_production_database"]
            config = {"Image": ref, "Env": ["APP_VERSION=" + self.sha], "Labels": labels}
            container = {"Config": config, "Image": "foreign" if changed == "digest" else "img",
                         "NetworkSettings": {"Networks": network}, "State": {"Status": "exited"},
                         "HostConfig": {"PortBindings": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]}}}
            container.update(Id="cid", Name="/other" if changed == "foreign name" else "/hookah_production-backend-1",
                             Mounts=[{"Type": "volume"}] if changed == "foreign mount" else [])
            image = {"Os": "linux", "Architecture": "amd64", "Id": "img", "Config": {"Labels": {
                "org.opencontainers.image.source": profile.SOURCE,
                "org.opencontainers.image.revision": "e" * 40 if changed == "revision" else self.sha}}}
            with self.subTest(changed=changed), patch.object(traffic, "verified_database"), \
                    patch.object(traffic, "compose", return_value="backend hash"), \
                    patch.object(subprocess, "check_output", side_effect=[json.dumps([container]), json.dumps([image])]):
                self.assertEqual(traffic.state(old, new, "cid"), "NEW stopped" if changed == "nothing" else "unknown")

    def test_loopback_https_requires_maintenance_header_and_503(self):
        for status, header in ((200, "closed"), (503, "unexpected"), (502, "closed")):
            reply = f"HTTP/2 {status}\r\nX-Hookah-Maintenance: {header}\r\n\r\n{status}"
            with self.subTest(status=status, header=header), patch.object(subprocess, "check_output", return_value=reply):
                with self.assertRaises(profile.Refused):
                    traffic.check_edge_closed()

    def test_stage_requires_confirmed_old_and_closed_https(self):
        from argparse import Namespace
        args = Namespace(action="stage", plan=self.plan, previous=self.plan)
        for state, edge_ok in (("NEW running", True), ("unknown", True), ("OLD running", False), ("OLD stopped", False)):
            with self.subTest(state=state, edge_ok=edge_ok), patch.object(traffic, "GATE", self.marker), \
                    patch.object(traffic, "state", return_value=state), patch.object(traffic, "confirm_transition"), \
                    patch.object(traffic, "check_edge_closed", side_effect=None if edge_ok else profile.Refused("HTTP 200")), \
                    patch.object(traffic, "compose") as compose, self.assertRaises(profile.Refused):
                traffic.operate(args)
            compose.assert_not_called()

    def test_stage_stops_probe_before_replacement(self):
        from argparse import Namespace
        args = Namespace(action="stage", previous=self.folder / "old", plan=self.plan)
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "confirm_transition"), \
                patch.object(traffic, "state", side_effect=["OLD running", "OLD stopped"]), patch.object(traffic, "verified_database"), \
                patch.object(traffic, "check_target_image"), \
                patch.object(traffic, "check_edge_closed"), patch.object(traffic, "verify_running"), \
                patch.object(traffic, "compose") as compose:
            traffic.operate(args)
        self.assertEqual([c.args for c in compose.call_args_list],
                         [(args.previous, "stop", "backend"), (args.plan, "up", "-d", "--pull", "never", "--no-deps", "backend")])
        self.assertFalse(self.marker.exists())

    def test_reversed_plans_never_stop_the_backend(self):
        def service(char):
            return {"image": "ghcr.io/koteev-m/hookah_bot/backend@sha256:" + char * 64,
                    "environment": {"APP_VERSION": char * 40}}
        old = {"services": {"backend": service("a")}}
        new = {"x-hookah-schema": 143, "services": {"backend": service("b")}}
        new["x-hookah-transition"] = {"from": profile.release_identity(old), "to": profile.release_identity(new)}
        prev = self.folder / "previous"
        prev.write_text(json.dumps(old))
        self.plan.write_text(json.dumps(new))
        profile.confirm_transition(prev, self.plan.read_bytes())
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "compose") as compose:
            with self.assertRaises(profile.Refused):
                traffic.operate(Namespace(action="stage", previous=self.plan, plan=prev))
            compose.assert_not_called()

        modified = json.loads(self.plan.read_bytes())
        modified["services"]["backend"]["image"] = service("c")["image"]
        with self.assertRaises(profile.Refused):
            profile.confirm_transition(prev, json.dumps(modified))

    def test_stage_requires_cached_target_identity_before_stopping_old(self):
        args = Namespace(action="stage", previous=self.plan, plan=self.plan)
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "confirm_transition"), \
                patch.object(traffic, "state", return_value="OLD running"), patch.object(traffic, "check_edge_closed"), \
                patch.object(traffic, "check_target_image", side_effect=profile.Refused("unavailable target")), \
                patch.object(traffic, "compose") as compose, self.assertRaises(profile.Refused):
            traffic.operate(args)
        compose.assert_not_called()

    def test_inspect_refuses_absent_or_unknown(self):
        for result in ("absent", "unknown", "OLD running", "NEW stopped"):
            with self.subTest(result=result), patch.object(traffic, "GATE", self.marker), \
                    patch.object(traffic, "confirm_transition"), patch.object(traffic, "state", return_value=result):
                args = Namespace(action="inspect", previous=self.plan, plan=self.plan)
                if result in ("absent", "unknown"):
                    with self.assertRaises(profile.Refused):
                        traffic.operate(args)
                else:
                    traffic.operate(args)

    def test_backup_database_probe_is_independent_of_backend_health(self):
        pg = "b" * 64
        ref = "postgres@sha256:" + "c" * 64
        plan = {"x-hookah-schema": 143, "name": "hookah_production", "services": {"postgres": {"image": ref}, "backend": {"image": "unused"}}}
        self.plan.write_text(json.dumps(plan))
        network = dict(Internal=True, Id="net", Containers={pg: {}}, Labels={"com.docker.compose.project": plan["name"]})
        volume = dict(Name="hookah_production_pgdata", Driver="local", CreatedAt="epoch", Labels=network["Labels"])
        container = {
            "Id": pg, "Image": "id", "State": {"Running": True, "Health": {"Status": "healthy"}},
            "Config": {"Image": ref, "Labels": {**network["Labels"], "com.docker.compose.service": "postgres",
                                               "com.docker.compose.config-hash": "pg-hash"}},
            "Mounts": [dict(Type="volume", Name=volume["Name"], Destination="/var/lib/postgresql/data", RW=True)],
            "NetworkSettings": {"Networks": {"hookah_production_database": {}}},
            "HostConfig": {"PortBindings": {}},
        }

        def docker(args, **kwargs):
            if "config" in args:
                return "backend backend-hash\npostgres pg-hash"
            if "ps" in args:
                if args[-1] == "backend":
                    self.fail("backup consulted backend")
                return pg
            if args[1] == "network":
                return json.dumps([network])
            if args[1] == "volume":
                return json.dumps([volume])
            if args[1] == "image":
                return json.dumps([dict(Id="id", Os="linux", Architecture="amd64")])
            self.assertEqual(args[1:], ["inspect", pg])
            return json.dumps([container])

        history = json.dumps([dict(row, success=True) for row in profile.history_for(profile.source_manifest())])
        with patch.object(subprocess, "check_output", side_effect=docker), \
                patch.object(profile, "pg_query", side_effect=["123|42", history]):
            self.assertEqual(profile.database_identity(self.plan)["container"], pg)
            container["State"]["Health"]["Status"] = "unhealthy"
            with self.assertRaises(profile.Refused):
                profile.database_identity(self.plan)
            container["State"]["Health"]["Status"] = "healthy"
            container["Mounts"][0]["Name"] = "foreign"
            with self.assertRaises(profile.Refused):
                profile.database_identity(self.plan)

    def test_backup_requires_enrolled_cluster_volume_container_and_recipient(self):
        record = dict(container="cid", cluster_and_db="123|42", network="net", volume="vol", volume_created="created")
        (self.folder / "postgres-binding.json").write_text(json.dumps(dict(record, recipient="trusted")))
        with patch.object(profile, "database_identity", return_value=record) as identity:
            self.assertEqual(profile.verified_database(self.plan, "trusted"), "cid")
            with self.assertRaises(profile.Refused):
                profile.verified_database(self.plan, "other-key")
            for key in record:
                identity.return_value = dict(record, **{key: "foreign"})
                with self.subTest(key=key), self.assertRaises(profile.Refused):
                    profile.verified_database(self.plan, "trusted")
            identity.side_effect = profile.Refused("PG down")
            with self.assertRaises(profile.Refused):
                profile.verified_database(self.plan, "trusted")

    def test_cleanup_refuses_foreign_volume_or_daemon_and_preserves_intent(self):
        attempt = "b" * 32
        name = "ht-restore-" + attempt + "-data"
        journal = self.folder / "attempt.json"
        journal.write_text(json.dumps(dict(daemon="test-daemon", attempt=attempt, image="postgres@sha256:" + "c" * 64)))
        with patch.object(restore_test, "docker") as docker:
            with self.assertRaises(profile.Refused):
                restore_test.cleanup(journal, "foreign-daemon")
            docker.assert_not_called()
            docker.side_effect = ["", name, json.dumps([dict(Name=name, Driver="local", Labels={})])]
            with self.assertRaises(profile.Refused):
                restore_test.cleanup(journal, "test-daemon")
            self.assertFalse(any("rm" in c.args for c in docker.call_args_list))
            self.assertTrue(journal.exists())

    def test_interrupted_restore_recovers_before_new_attempt_and_cleanup_failure_propagates(self):
        state = self.folder / "hookah-production-restore"
        state.mkdir(mode=0o700)
        journal = state / "attempt.json"
        journal.write_text("previous")
        events = []

        def cleanup(path, daemon):
            self.assertEqual((path, daemon), (journal, "daemon"))
            if path.read_text() == "previous":
                events.append("recover")
                path.unlink()
            else:
                events.append("cleanup")
                raise profile.Refused("volume still in use")

        with patch.dict(os.environ, XDG_STATE_HOME=str(self.folder)), \
                patch.object(sys, "argv", ["restore", "point", "identity", "image"]), \
                patch.object(restore_test, "docker", side_effect=["daemon", "", ""]), \
                patch.object(restore_test, "verify_archive"), patch.object(restore_test, "cleanup", side_effect=cleanup), \
                patch.object(restore_test, "restore", side_effect=lambda *args: events.append("restore")), \
                patch("sys.stdout", new_callable=io.StringIO) as output, self.assertRaises(profile.Refused):
            restore_test.main()
        self.assertEqual(events, ["recover", "restore", "cleanup"])
        self.assertTrue(journal.exists())
        self.assertNotIn("passed", output.getvalue())
