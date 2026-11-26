from argparse import Namespace
import hashlib
import io
import json
import os
import signal
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

import production_profile as profile
import production_traffic as traffic

sys.path.insert(0, str(profile.PROFILE))
import restore_test


class PilotOperationsTest(unittest.TestCase):
    def setUp(self):
        # These tests call operate directly with mocked Docker; CLI lock lifetime has process tests.
        lease = patch.object(traffic.host, "lock_args", return_value={})
        lease.start()
        self.addCleanup(lease.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        fence = patch.object(traffic, "check_gate_binary")
        fence.start()
        self.addCleanup(fence.stop)
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
                    self.assertFalse(self.marker.exists() or active.exists())
                else:
                    traffic.operate(args)
                    self.assertEqual(observations, [False, True, True])
                    self.assertEqual(self.marker.read_text().strip(), self.sha + " " + instance)
                    self.assertEqual(db.call_count, 2)

    def test_deferred_telegram_open_preserves_backlog_and_checks_real_poller_and_edge(self):
        accepted = self.folder / "recovery-accepted"
        hold = self.folder / "recovery-hold"
        enabled_plan = self.plan.read_bytes()
        plan = json.loads(enabled_plan)
        plan["services"]["backend"]["environment"]["TELEGRAM_BOT_ENABLED"] = "false"
        self.plan.write_text(json.dumps(plan))
        hold.write_text(json.dumps({"target": {"container": "pg"}, "challenge": "a"*64, "first_open_lsn": "0/12",
                                    "plan_sha256": hashlib.sha256(self.plan.read_bytes()).hexdigest()}))
        clearance = self.folder / "telegram-recovery-cleared"
        active = self.folder / "activate"
        args = Namespace(plan=self.plan, action="open")
        fault = "backlog"
        def ready(sha, assets, instance=None, polling=None, *, recovery=False):
            if recovery:
                self.assertFalse(clearance.exists() or self.marker.exists())
                self.assertEqual(polling, "empty")
                if fault == "backlog":
                    raise profile.Refused("Telegram backlog")
            else:
                self.assertEqual(polling, "running")
                self.assertTrue(clearance.exists() and self.marker.exists())
                if fault == "poller":
                    raise profile.Refused("poller stopped")
            return "instance"
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "cluster_identity", return_value={"container": "pg"}), \
                patch.object(traffic, "verified_database"), patch.object(traffic, "verify_running"), \
                patch.object(traffic, "image_assets"), patch.object(traffic, "check_edge_closed"), \
                patch.object(traffic, "await_readiness", side_effect=ready), patch.object(traffic, "check_edge_open") as edge:
            with patch.object(traffic, "pg_query", return_value="0/12"), \
                    patch.object(traffic, "await_readiness", return_value="first-jvm") as disabled:
                traffic.operate(args)
                disabled.assert_called_with(self.sha, unittest.mock.ANY, "first-jvm", "disabled")
                edge.assert_called_with(self.sha, "first-jvm", "disabled")
            self.assertFalse(hold.exists() or clearance.exists())
            self.assertTrue(accepted.exists())
            # Simulate the closed configuration change to a new JVM with the same image.
            active.unlink()
            self.marker.unlink()
            self.plan.write_bytes(enabled_plan)
            for _ in range(2):
                with self.assertRaisesRegex(profile.Refused, "backlog"):
                    traffic.operate(args)
                self.assertFalse(clearance.exists() or active.exists() or self.marker.exists())
            for fault in ("poller", "edge", None):
                clearance.unlink(missing_ok=True)
                edge.side_effect = ValueError("closed") if fault == "edge" else None
                if fault:
                    with self.assertRaisesRegex(profile.Refused, "decision committed"):
                        traffic.operate(args)
                    self.assertTrue(accepted.exists() and clearance.exists())
                    self.assertFalse(active.exists() or self.marker.exists())
                else:
                    traffic.operate(args)
                    edge.assert_called_with(self.sha, "instance", "running")

    def test_recorded_telegram_backlog_requires_owner_review_even_after_expiry(self):
        db = {"container": "pg", "cluster_and_db": "100|20"}
        source = "a"*64
        (self.folder / "recovery-accepted").write_text(json.dumps({"target": db, "source_manifest": source}))
        latch = self.folder / "telegram-backlog"
        args = Namespace(action="open", plan=self.plan)
        review = self.folder / "review"
        op = Namespace(action="recovery-telegram-reviewed", plan=self.plan, review=review, review_sha256="")
        instance = "00000000-0000-4000-8000-000000000001"
        def sign(challenge):
            review.write_text(json.dumps(dict(decision="TELEGRAM_BACKLOG_RECONCILED", challenge=challenge,
                source_manifest=source, cluster_and_db=db["cluster_and_db"], evidence_sha256="b"*64)))
            review.chmod(0o600)
            op.review_sha256 = hashlib.sha256(review.read_bytes()).hexdigest()
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "cluster_identity", return_value=db), \
                patch.object(traffic, "check_edge_closed"), patch.object(traffic, "verified_database"), \
                patch.object(traffic, "verify_running"), patch.object(traffic, "image_assets"), \
                patch.object(traffic, "probe") as probe, \
                patch.object(traffic, "check_edge_open"):
            def observed(*args, on_backlog, **kwargs):
                on_backlog()
                return {"polling": "backlog", "instance": instance}
            probe.side_effect = observed
            with self.assertRaisesRegex(profile.Refused, "Telegram backlog"):
                traffic.operate(args)
            recorded = latch.read_text().strip()
            probe.reset_mock()
            probe.side_effect = lambda *args, recovery=False, on_backlog=None: {"polling": "empty" if recovery else "running", "instance": instance}
            with self.assertRaisesRegex(profile.Refused, "owner reconciliation"):
                traffic.operate(args)
            probe.assert_not_called()
            sign("f"*64)
            with self.assertRaises(profile.Refused):
                traffic.operate(op)
            sign(recorded)
            traffic.operate(op)
            with self.assertRaises(FileNotFoundError):
                traffic.operate(op)
            self.assertFalse(self.marker.exists() or self.marker.with_name("telegram-recovery-cleared").exists())
            traffic.operate(args)
            self.assertTrue(self.marker.exists())

    def test_killed_normal_open_leaves_only_a_probe_permission(self):
        args = Namespace(plan=self.plan, action="open")
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "check_edge_closed"), \
                patch.object(traffic, "verified_database"), patch.object(traffic, "verify_running"), \
                patch.object(traffic, "image_assets"), patch.object(traffic, "await_readiness", return_value="instance"):
            publish = traffic.publish
            pid = os.fork()
            if pid == 0:
                def die(path, *args):
                    publish(path, *args)
                    os.kill(os.getpid(), signal.SIGKILL)
                with patch.object(traffic, "publish", side_effect=die):
                    try:
                        traffic.operate(args)
                    finally:
                        os._exit(4)
            self.assertEqual(os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]), -signal.SIGKILL)
            self.assertFalse(self.marker.exists())
            self.assertTrue(self.marker.with_name("activate").exists())
            traffic.operate(args)
            self.assertEqual(self.marker.read_bytes(), self.marker.with_name("activate").read_bytes())

    def test_disaster_acceptance_is_bound_to_reviewed_database_and_first_open(self):
        hold = self.folder / "recovery-hold"
        accepted = self.folder / "recovery-accepted"
        db = {"container": "pg", "cluster_and_db": "100|20"}
        source = "e"*64
        plan = json.loads(self.plan.read_bytes())
        plan["services"]["backend"]["image"] = "fixture"
        self.plan.write_text(json.dumps(plan))
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as z:
            z.writestr("com/hookah/platform/backend/ProductionTrafficGate.class", "recovery-hold recoveryProbeAllowed")
        begin = Namespace(action="recovery-begin", plan=self.plan, point_sha256=source)
        review = self.folder / "review"
        accept = Namespace(action="recovery-accept", plan=self.plan, review=review, review_sha256="")
        evidence = {scope: hashlib.sha256(scope.encode()).hexdigest() for scope in traffic.RECOVERY_SCOPES}
        approval = dict(source_manifest=source, cluster_and_db=db["cluster_and_db"], wal_lsn="0/23",
                        plan_sha256=hashlib.sha256(self.plan.read_bytes()).hexdigest(),
                        decision="ACCEPT_RECONCILED_REPLACEMENT", evidence=evidence)
        def approve(document):
            review.write_text(json.dumps(document))
            review.chmod(0o600)
            accept.review_sha256 = hashlib.sha256(review.read_bytes()).hexdigest()
            traffic.operate(accept)

        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "cluster_identity", return_value=db) as target, \
                patch.object(traffic, "pg_query", return_value="t") as query, patch.object(traffic, "compose", return_value=""), \
                patch.object(traffic, "check_target_image"), patch.object(subprocess, "check_output", return_value=jar.getvalue()), \
                patch.object(traffic, "state", return_value="OLD running"), patch.object(traffic, "verified_database", return_value="pg"), \
                patch.object(traffic, "verify_running"), patch.object(traffic, "image_assets"), patch.object(traffic, "check_edge_closed"), \
                patch.object(traffic, "check_edge_open"), patch.object(traffic, "await_readiness", return_value="instance"):
            query.return_value = "f"
            with self.assertRaises(profile.Refused):
                traffic.operate(begin)
            self.assertEqual(hold.stat().st_mode & 0o777, 0o644)
            query.return_value = "t"
            traffic.operate(begin)
            approval["challenge"] = json.loads(hold.read_bytes())["challenge"]
            for action in ("open", "resume", "stage"):
                with self.assertRaises(profile.Refused):
                    traffic.operate(Namespace(action=action, plan=self.plan))
            query.return_value = "0/23"
            for bad in (dict(evidence={}), dict(cluster_and_db="99|20"), dict(source_manifest="f"*64), dict(wal_lsn="0/22")):
                with self.assertRaises(profile.Refused):
                    approve(approval | bad)
                self.assertTrue(hold.exists())
            query.return_value = ""
            with self.assertRaises(profile.Refused):
                approve(approval)
            query.return_value = "0/23"
            approve(approval)
            self.assertTrue(hold.exists())
            self.assertEqual(hold.stat().st_mode & 0o777, 0o644)
            self.assertFalse(accepted.exists() or self.marker.exists())
            op = Namespace(action="open", plan=self.plan)
            query.return_value = "0/24"
            with self.assertRaises(profile.Refused):
                traffic.operate(op)
            self.assertTrue(hold.exists())
            self.assertFalse(self.marker.exists() or self.marker.with_name("activate").exists())
            with self.assertRaises(profile.Refused):
                approve(approval | {"wal_lsn": "0/24"})
            approval["wal_lsn"] = "0/24"
            activation = self.marker.with_name("activate")
            for crash_after in (hold, activation, self.marker, accepted):
                approval["challenge"] = json.loads(hold.read_bytes())["challenge"]
                approve(approval)
                # Crash the actual child after each durable preparation, leaving disk state as-is.
                pid = os.fork()
                if pid == 0:
                    publish = traffic.publish
                    def crash_at_boundary(path, *args):
                        publish(path, *args)
                        if path == crash_after:
                            os.kill(os.getpid(), signal.SIGKILL)
                    with patch.object(traffic, "publish", side_effect=crash_at_boundary):
                        try:
                            traffic.operate(op)
                        finally:
                            os._exit(3)
                self.assertEqual(os.waitstatus_to_exitcode(os.waitpid(pid, 0)[1]), -signal.SIGKILL)
                self.assertTrue(hold.exists())
                self.assertEqual(activation.exists(), crash_after != hold)
                self.assertEqual(self.marker.exists(), crash_after in (self.marker, accepted))
                self.assertEqual(accepted.exists(), crash_after == accepted)
                with self.assertRaises(profile.Refused):
                    traffic.operate(op)
                with patch.object(traffic, "compose", return_value="backend"), \
                        patch.object(traffic, "state", return_value="OLD stopped"), patch.object(subprocess, "run"):
                    traffic.operate(Namespace(action="close", plan=self.plan))
                with self.assertRaises(profile.Refused):
                    approve(approval)
            approve(approval | {"challenge": json.loads(hold.read_bytes())["challenge"]})
            traffic.operate(op)
            self.assertFalse(hold.exists())
            self.assertTrue(self.marker.exists())
            self.assertNotIn("first_open_lsn", json.loads(accepted.read_bytes()))
            self.assertEqual(accepted.stat().st_mode & 0o777, 0o600)
            target.return_value = dict(db, cluster_and_db="999|20")
            with self.assertRaisesRegex(profile.Refused, "different database"):
                traffic.operate(op)

    def test_cold_backup_starts_same_container_without_a_rollout(self):
        runbook = (profile.ROOT / "docs/PRODUCTION_PILOT.md").read_text()
        section = runbook.split("### Копия и восстановление production media", 1)[1]
        example = section.split("```bash\n", 1)[1].split("```", 1)[0]
        self.assertEqual([line for line in example.splitlines() if line and not line.startswith("#")], [
            'backup_backend=$("${dc[@]}" ps --no-trunc -q backend)',
            '"${traffic[@]}" close "$plan"', 'deploy/production/backup.sh "$plan" "$POINT" "$RECIPIENT"',
            '"${traffic[@]}" start-closed "$plan" "$backup_backend"', '"${traffic[@]}" open "$plan"',
        ])
        args = Namespace(action="start-closed", plan=self.plan, backend_id="c"*64)
        for initial, cid in (("unknown", args.backend_id), ("OLD running", args.backend_id),
                             ("OLD stopped", "f"*64), ("OLD stopped", args.backend_id)):
            with self.subTest(initial=initial), patch.object(traffic, "GATE", self.marker), \
                    patch.object(traffic, "compose", return_value=cid), patch.object(traffic, "state", side_effect=[initial, "OLD running"]) as state, \
                    patch.object(traffic, "check_edge_closed"), patch.object(traffic, "verified_database") as db, \
                    patch.object(traffic, "verify_running") as running, patch.object(traffic, "image_assets"), \
                    patch.object(traffic, "await_readiness") as ready, patch.object(subprocess, "run") as start:
                if initial != "OLD stopped" or cid != args.backend_id:
                    with self.assertRaises(profile.Refused):
                        traffic.operate(args)
                    start.assert_not_called()
                else:
                    traffic.operate(args)
                    self.assertEqual(start.call_args.args[0], ["docker", "start", cid])
                    self.assertTrue(all(c.args[-1] == cid for c in state.call_args_list))
                    self.assertTrue(db.called and running.called and ready.called)
                self.assertFalse(self.marker.exists() or self.marker.with_name("activate").exists())

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
        for changed in ("nothing", "digest", "revision", "project", "network", "foreign name", "foreign mount", "directory"):
            labels = {"com.docker.compose.project": "wrong" if changed == "project" else "hookah_production",
                      "com.docker.compose.project.working_dir": "other" if changed == "directory" else str(self.folder.resolve()),
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

    def test_public_recovery_confirmation_requires_serving_instance_poller_and_authenticated_api(self):
        ok = {"service": "backend", "env": "prod", "version": self.sha, "instance": "jvm", "polling": "running"}
        for change, status, allowed in (({}, "401", True), ({"instance": "old"}, "401", False),
                                        ({"polling": "standby"}, "401", False), ({}, "503", False), ({}, "200", False)):
            with self.subTest(change=change, status=status), patch.object(subprocess, "check_output",
                    side_effect=[json.dumps(ok | change) + "\n200", status]) as curl:
                if allowed:
                    traffic.check_edge_open(self.sha, "jvm", "running")
                    self.assertTrue(all("--resolve" in call.args[0] for call in curl.call_args_list))
                else:
                    with self.assertRaises(profile.Refused):
                        traffic.check_edge_open(self.sha, "jvm", "running")
        with patch.object(subprocess, "check_output", return_value=json.dumps(ok) + "\n302"), self.assertRaises(profile.Refused):
            traffic.check_edge_open(self.sha, "jvm", "running")

    def test_failed_open_preserves_another_instances_activation(self):
        active = self.marker.with_name("activate")
        active.write_text("different jvm")
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "check_edge_closed"), \
                patch.object(traffic, "verified_database"), patch.object(traffic, "verify_running"), \
                patch.object(traffic, "await_readiness", return_value="instance"), patch.object(traffic, "image_assets"), \
                self.assertRaisesRegex(profile.Refused, "another JVM"):
            traffic.operate(Namespace(action="open", plan=self.plan))
        self.assertEqual(active.read_text(), "different jvm")
        self.assertFalse(self.marker.exists())

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

    def test_media_close_pins_backend_with_failed_worker_and_open_stays_closed(self):
        plan = json.loads(self.plan.read_bytes())
        plan["services"]["backend"]["environment"]["VENUE_INFO_PDF_WORKER_ENABLED"] = "true"
        self.plan.write_text(json.dumps(plan))
        active = self.marker.with_name("activate")
        for recognized in (True, False):
            self.marker.write_text("old")
            active.write_text("old")
            states = ["OLD running", "OLD stopped"] if recognized else ["unknown"]
            with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "compose", return_value="cid"), \
                    patch.object(traffic, "state", side_effect=states) as state, patch.object(traffic, "verified_database"), \
                    patch.object(traffic, "verify_running", side_effect=profile.Refused("unhealthy PDF worker")) as worker, \
                    patch.object(traffic, "check_edge_closed") as edge, patch.object(subprocess, "run") as stop:
                args = Namespace(plan=self.plan, action="close")
                if recognized:
                    traffic.operate(args)
                    stop.assert_called_once_with(["docker", "stop", "cid"], check=True, stdout=subprocess.DEVNULL)
                    edge.assert_called_once()
                else:
                    with self.assertRaises(profile.Refused):
                        traffic.operate(args)
                    stop.assert_not_called()
                self.assertTrue(all(c.args == (self.plan, self.plan, "cid") for c in state.call_args_list))
                worker.assert_not_called()
                self.assertFalse(active.exists() or self.marker.exists())
                with self.assertRaisesRegex(profile.Refused, "unhealthy PDF worker"):
                    traffic.operate(Namespace(plan=self.plan, action="open"))
                self.assertFalse(active.exists() or self.marker.exists())

    def test_media_update_replaces_worker_before_starting_new_backend(self):
        plan = json.loads(self.plan.read_bytes())
        plan["services"]["pdf-worker"] = {"image": "synthetic"}
        self.plan.write_text(json.dumps(plan))
        args = Namespace(action="stage", previous=self.folder / "old", plan=self.plan)
        with patch.object(traffic, "GATE", self.marker), patch.object(traffic, "confirm_transition"), \
                patch.object(traffic, "state", return_value="OLD stopped"), patch.object(traffic, "verified_database"), \
                patch.object(traffic, "check_target_image"), patch.object(traffic, "check_edge_closed"), \
                patch.object(traffic, "compose") as compose:
            traffic.operate(args)
        self.assertEqual([call.args[-1] for call in compose.call_args_list], ["pdf-worker", "backend"])

    def test_rollback_waits_for_previous_worker_and_requires_compatible_database(self):
        old = self.folder / "old"
        def document(char, schema):
            service = {"image": "ghcr.io/koteev-m/hookah_bot/backend@sha256:" + char * 64,
                       "environment": {"APP_VERSION": char * 40}}
            return {"services": {"backend": service, "pdf-worker": {"image": service["image"]}},
                    "x-hookah-schema": schema,
                    "x-hookah-release-evidence": {"candidate": {"image": service["image"], "revision": char*40}}}
        before, after = document("a", 143), document("b", 143)
        after["x-hookah-transition"] = {"from": profile.release_identity(before), "to": profile.release_identity(after)}
        self.plan.write_text(json.dumps(after))
        args = Namespace(action="rollback", previous=old, plan=self.plan)
        for schema, worker_ok in ((142, True), (143, False), (143, True)):
            before["x-hookah-schema"] = schema
            old.write_text(json.dumps(before))
            with self.subTest(schema=schema, worker_ok=worker_ok), patch.object(traffic, "GATE", self.marker), \
                    patch.object(traffic, "check_edge_closed"), patch.object(traffic, "check_target_image"), \
                    patch.object(traffic, "stopped_backend", return_value="a"*64), \
                    patch.object(traffic, "rollback_worker", return_value=("b"*64, old)), \
                    patch.object(traffic, "state", return_value="OLD running"), \
                    patch.object(traffic, "verified_database"), patch.object(traffic, "verify_running") as running, \
                    patch.object(traffic, "image_assets"), patch.object(traffic, "await_readiness"), \
                    patch.object(traffic, "compose", side_effect=None if worker_ok else profile.Refused("worker failed")) as compose:
                if schema == 142 or not worker_ok:
                    with self.assertRaises(profile.Refused):
                        traffic.operate(args)
                    self.assertEqual([c.args[-1] for c in compose.call_args_list], [] if schema == 142 else ["pdf-worker"])
                else:
                    traffic.operate(args)
                    self.assertEqual([c.args[-1] for c in compose.call_args_list], ["pdf-worker", "backend"])
                    self.assertIn("--wait", compose.call_args_list[0].args)
                    running.assert_called_once_with(old)
                self.assertFalse(self.marker.exists())

    def test_rollback_refuses_foreign_or_unrecognized_worker_before_stopping_any_container(self):
        old = self.folder / "previous"
        ref = "ghcr.io/koteev-m/hookah_bot/backend@sha256:"
        for plan, suffix in ((old, "a"), (self.plan, "b")):
            plan.write_text(json.dumps({"services": {s: {"image": ref + suffix*64} for s in ("pdf-worker", "backend")}}))
        cid = "c"*64
        labels = {"com.docker.compose." + k: v for k, v in {
            "project": "hookah_production", "service": "pdf-worker", "container-number": "1", "oneoff": "False",
            "project.working_dir": str(self.folder.resolve()), "config-hash": "hash"}.items()}
        data = {"Id": cid, "Name": "/hookah_production-pdf-worker-1",
                "Config": {"Image": ref+"b"*64, "Labels": labels}, "Image": "sha256:"+"d"*64, "State": {"Status":"running"}}
        image = {"Id": data["Image"], "Os": "linux", "Architecture": "amd64"}
        for fault in ("container", "digest", "image id", "project", "compose hash", "duplicate"):
            observed = json.loads(json.dumps(data))
            if fault == "container":
                observed["Id"] = "e"*64
            elif fault == "digest":
                observed["Config"]["Image"] = ref+"f"*64
            elif fault == "image id":
                observed["Image"] = "wrong"
            elif fault == "project":
                observed["Config"]["Labels"]["com.docker.compose.project"] = "other"
            elif fault == "compose hash":
                observed["Config"]["Labels"]["com.docker.compose.config-hash"] = "other"
            selected = cid if fault != "duplicate" else cid + "\n" + "d"*64
            with self.subTest(fault=fault), patch.object(traffic, "compose", side_effect=[selected, "pdf-worker hash"]), \
                    patch.object(subprocess, "check_output", return_value=json.dumps([observed])), \
                    patch.object(traffic, "check_target_image", return_value=image), self.assertRaises(profile.Refused):
                traffic.rollback_worker(old, self.plan)

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

        history = json.dumps([dict(row, success=True) for row in profile.history_for(profile.source_manifest(historical=True))])
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

    def test_ordinary_first_bind_refuses_unmarked_restored_data_or_authority(self):
        recipient = "age1" + "a"*58
        with patch.object(profile, "GATE", self.marker), patch.object(subprocess, "run"), patch.object(profile, "database_identity", return_value={"container": "pg"}), \
                patch.object(profile, "pg_query") as query:
            with self.assertRaises(profile.Refused):
                profile.enroll_database(self.plan, recipient)
            for rows in (["f"], ["t", ""]):
                query.side_effect = rows
                with self.assertRaises(profile.Refused):
                    profile.enroll_database(self.plan, recipient, first_install=True)
                self.assertFalse((self.plan.parent / "postgres-binding.json").exists())
            hold = self.marker.with_name("recovery-hold")
            hold.write_text(json.dumps({"target": {"container": "wrong"}, "plan_sha256": "wrong"}))
            with self.assertRaises(profile.Refused):
                profile.enroll_database(self.plan, recipient, first_install=True)
            with self.assertRaises(profile.Refused):
                profile.enroll_database(self.plan, recipient)
            hold.write_text(json.dumps({"target": {"container": "pg"}, "plan_sha256": hashlib.sha256(self.plan.read_bytes()).hexdigest()}))
            query.side_effect = ["t", "0/20"]
            profile.enroll_database(self.plan, recipient)
            self.assertTrue((self.plan.parent / "postgres-binding.json").is_file())

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
            docker.side_effect = ["", name, "", "", name, json.dumps([dict(Name=name, Driver="local", Labels={})])]
            with self.assertRaises(profile.Refused):
                restore_test.cleanup(journal, "test-daemon")
            self.assertFalse(any("rm" in c.args for c in docker.call_args_list))
            self.assertTrue(journal.exists())

    def test_quarantine_handover_only_after_authenticated_restore_of_both_pinned_points(self):
        data = self.folder / "bytes"
        metadata = self.folder / "metadata"
        image = "postgres@sha256:" + "c" * 64
        byte_pin, metadata_pin = "a" * 64, "b" * 64
        args = ["restore", str(data), "key", image, "--keep-quarantine", "--point-sha256", byte_pin,
                "--metadata-point", str(metadata), "--metadata-sha256", metadata_pin]
        state = self.folder / "hookah-production-restore"
        for fault in (profile.Refused("incomplete age ciphertext"), None):
            with patch.dict(os.environ, XDG_STATE_HOME=str(self.folder)), patch.object(sys, "argv", args), \
                    patch.object(restore_test, "docker", side_effect=lambda *a: "daemon" if a[0] == "info" else ""), \
                    patch.object(restore_test, "verify_archive") as verify, patch.object(restore_test, "check_pair") as pair, \
                    patch.object(restore_test, "restore", side_effect=fault, return_value={"selected": 1}) as load, \
                    patch.object(restore_test, "cleanup", side_effect=lambda journal, _: journal.unlink()) as cleanup, \
                    patch("sys.stdout", new_callable=io.StringIO):
                if fault:
                    with self.assertRaises(profile.Refused):
                        restore_test.main()
                    cleanup.assert_called_once()
                    self.assertFalse(list(state.glob("*-quarantine.json")))
                else:
                    restore_test.main()
                    cleanup.assert_not_called()
                    receipt, = state.glob("*-quarantine.json")
                    record = json.loads(receipt.read_bytes())
                    self.assertEqual((record["point_sha256"], record["metadata_sha256"]), (byte_pin, metadata_pin))
                self.assertEqual([call.args for call in verify.call_args_list],
                                 [(data, image, byte_pin), (metadata, image, metadata_pin)])
                pair.assert_called_once_with(data, metadata)
                self.assertEqual(load.call_args.args[-2:], (metadata, True))
                self.assertFalse((state / "attempt.json").exists())

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
                patch.object(restore_test, "docker", side_effect=lambda *a: "daemon" if a[0] == "info" else ""), \
                patch.object(restore_test, "verify_archive"), patch.object(restore_test, "cleanup", side_effect=cleanup), \
                patch.object(restore_test, "restore", side_effect=lambda *args: events.append("restore")), \
                patch("sys.stdout", new_callable=io.StringIO) as output, self.assertRaises(profile.Refused):
            restore_test.main()
        self.assertEqual(events, ["recover", "restore", "cleanup"])
        self.assertTrue(journal.exists())
        self.assertNotIn("passed", output.getvalue())
