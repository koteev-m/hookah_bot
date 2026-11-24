"""Offline release policy and interruption tests. External boundaries use synthetic fixtures."""
from datetime import datetime, timedelta, timezone
import io
from pathlib import Path
import json
import os
import subprocess
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import production_update as u
from test_production_backup_points import point

p, bp = u.profile, u.points
OLD, NEW = "a" * 40, "b" * 40


class Fixture(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name).resolve()

    def replace(self, obj, name, *args, **kwargs):
        mocked = patch.object(obj, name, *args, **kwargs)
        self.addCleanup(mocked.stop)
        return mocked.start()


class OrdinaryUpdateTest(Fixture):
    def setUp(self):
        super().setUp()
        operator = self.root / "operator"
        operator.mkdir(mode=0o700)
        self.replace(u, "OPERATOR", operator)
        self.env = self.root / "backup.env"
        self.replace(u, "BACKUP_ENV", self.env)
        self.gate = self.root / "open"
        self.replace(p, "GATE", self.gate)
        self.gate.write_text(OLD)
        self.args = SimpleNamespace(approved_sha=NEW, compatible_from=OLD, run=212, attempt=2, digest="sha256:"+"2"*64)
        self.release = u.flags(self.args)
        self.old = operator / "previous.json"
        self.new = operator / ("plan-" + NEW + ".json")
        self.old_doc = {"services": {"backend": {"image": self.release["image"].replace("2"*64, "1"*64),
                        "environment": {"APP_VERSION": OLD}}, "postgres": {"image": "postgres@sha256:"+"3"*64}},
                        "x-hookah-schema": 143}
        self.old_doc["x-hookah-release-evidence"] = {"candidate": p.release_identity(self.old_doc)}
        p.publish_plan(self.old, json.dumps(self.old_doc).encode())
        new_doc = json.loads(json.dumps(self.old_doc))
        new_doc["services"]["backend"].update(image=self.release["image"], environment={"APP_VERSION": NEW})
        new_doc["x-hookah-release-evidence"] = dict(candidate=self.release, attempt=2)
        new_doc["x-hookah-transition"] = {"from": p.release_identity(self.old_doc), "to": p.release_identity(new_doc)}
        p.publish_plan(self.new, json.dumps(new_doc).encode())
        self.recipient = operator / "recipient.txt"
        self.recipient.write_text("synthetic-public-recipient")
        self.env.write_text(f"# operator settings\nADMITTED_PLAN={self.old}\nBACKUP_TOOL={p.PROFILE}/backup_points.py\n"
                            f"BACKUP_SPOOL={self.root}/scheduled\nQUALIFIED_POINTS={operator}/qualified.sha256\n"
                            f"AGE_RECIPIENT_FILE={self.recipient}\n")
        self.env.chmod(0o600)
        self.initial_env = self.env.read_text()
        self.backend = "OLD running"
        self.timer = True
        self.actions = []
        self.replace(u, "check_open", side_effect=self.check_open)
        self.schedule = self.replace(u, "schedule", side_effect=self.check_schedule)
        self.replace(u, "ask", return_value="eGlw")
        self.staging = self.replace(u, "staging")
        self.publish = self.replace(p, "verify_published")
        self.backup = self.replace(u, "verify_backup")
        self.smoke = self.replace(u, "closed_smoke", side_effect=self.closed)
        self.replace(u.traffic, "check_edge_closed", side_effect=lambda: self.assertFalse(self.gate.exists()))
        self.replace(u.traffic, "state", side_effect=lambda *args: self.backend)
        self.replace(u.media_backup, "backup_target")
        self.native = self.replace(u, "native", side_effect=self.operate)
        self.systemctl = self.replace(subprocess, "run", side_effect=self.system)

    def check_schedule(self, active, **opts):
        if active:
            self.assertTrue(self.timer)

    def system(self, cmd, **opts):
        self.assertEqual([cmd[0], cmd[2]], ["systemctl", u.TIMER])
        self.timer = cmd[1] == "start"

    def check_open(self, plan):
        self.assertEqual(self.gate.read_text(), u.traffic.backend(plan)["environment"]["APP_VERSION"])
        self.assertEqual(self.backend, ("OLD" if plan == self.old else "NEW") + " running")

    def closed(self, plan):
        self.assertFalse(self.gate.exists())
        self.assertFalse(self.timer)
        self.assertEqual(self.backend, ("OLD" if plan == self.old else "NEW") + " running")

    def operate(self, action, plan, previous=None):
        self.actions.append(action)
        ident = "OLD" if plan == self.old else "NEW"
        if action == "close":
            self.gate.unlink(missing_ok=True)
            self.backend = ident + " stopped"
        elif action in ("stage", "resume", "rollback"):
            self.assertFalse(self.gate.exists())
            self.assertFalse(self.timer)
            self.backend = ("OLD" if action == "rollback" else "NEW") + " running"
        elif action == "open":
            self.closed(plan)
            self.gate.write_text(OLD if plan == self.old else NEW)

    def update(self):
        return u.Update(self.args, self.release).run()

    def check_catchup(self, admitted):
        cfg = u.settings()
        spool = cfg["BACKUP_SPOOL"]
        spool.mkdir(exist_ok=True)
        def capture(plan, dest, recipient, metadata_only, *, online):
            self.assertTrue(online)
            self.assertEqual(plan, admitted)
            point(dest.parent, dest.name)
        with patch.object(u.media_backup, "backup", side_effect=capture) as backup:
            for plan in (self.old if admitted == self.new else self.new, admitted):
                bp.create(plan, self.recipient, spool, cfg["QUALIFIED_POINTS"])
            backup.assert_called_once()

    def test_success_and_idempotent_replay(self):
        self.assertEqual(self.update(), "done")
        self.assertEqual(self.update(), "done")
        self.assertEqual(self.actions, ["close", "stage", "inspect", "open"])
        self.assertEqual(self.env.read_text(), self.initial_env.replace(str(self.old), str(self.new)))
        self.assertEqual(self.env.stat().st_mode & 0o777, 0o600)
        self.assertTrue(self.timer)
        self.publish.assert_called_once_with(self.release, archive=b"xip", expected_attempt=2)

    def test_expired_backup_or_wrong_staging_stops_before_close(self):
        for check in (self.staging, self.backup):
            check.side_effect = u.Refused("unverified")
            with self.assertRaisesRegex(u.Refused, "unverified"):
                self.update()
            check.side_effect = None
        self.assertTrue(self.timer)
        self.assertFalse(self.actions)
        self.systemctl.assert_not_called()

    def test_failure_while_closed_restores_predecessor_and_timer(self):
        self.smoke.side_effect = [u.Refused("new failed"), None]
        def catchup(cmd, **kwargs):
            self.system(cmd)
            if cmd[1] == "start":
                self.check_catchup(self.old)
        self.systemctl.side_effect = catchup
        self.assertEqual(self.update(), "rolled-back")
        self.assertEqual(self.actions, ["close", "stage", "inspect", "close", "rollback", "open"])
        self.assertEqual(self.update(), "rolled-back")
        self.assertEqual(self.env.read_text(), self.initial_env)
        self.assertTrue(self.timer)

    def test_resume_after_close_after_replacement_or_after_open(self):
        # Each interruption happens after the external effect, before the journal checkpoint.
        for action in ("close", "stage", "open"):
            with self.subTest(action=action):
                def crash(step, *args):
                    self.operate(step, *args)
                    if step == action:
                        raise KeyboardInterrupt()
                self.native.side_effect = crash
                with self.assertRaises(KeyboardInterrupt):
                    self.update()
                self.native.side_effect = self.operate
                self.assertEqual(self.update(), "done")
                self.assertEqual(self.actions.count("open"), 1)
                self.assertEqual(self.actions.count("stage"), 1)
                self.assertTrue(self.timer)
                self.assertEqual(u.settings()["ADMITTED_PLAN"], self.new)
                (u.OPERATOR / "ordinary-update.json").unlink()
                self.env.write_text(self.initial_env)
                self.gate.write_text(OLD)
                self.backend = "OLD running"
                self.actions.clear()

    def test_interrupted_after_timer_stop_and_stale_backup_restarts_old_schedule(self):
        def stop_then_interrupt(cmd, **kwargs):
            self.system(cmd)
            raise KeyboardInterrupt()
        self.systemctl.side_effect = stop_then_interrupt
        with self.assertRaises(KeyboardInterrupt):
            self.update()
        self.assertFalse(self.timer)
        self.assertEqual(self.gate.read_text(), OLD)
        self.systemctl.side_effect = self.system
        self.backup.side_effect = u.Refused("external disk unavailable")
        with self.assertRaisesRegex(u.Refused, "external disk"):
            self.update()
        self.assertTrue(self.timer)
        self.assertEqual(self.env.read_text(), self.initial_env)
        self.assertFalse(self.actions)
        self.assertFalse((u.OPERATOR / "ordinary-update.json").exists())
        self.backup.side_effect = None
        self.assertEqual(self.update(), "done")

    def test_timer_catchup_uses_switched_plan_before_final_checkpoint(self):
        def catchup(cmd, **kwargs):
            self.system(cmd)
            if cmd[1] == "start":
                self.assertEqual(u.settings()["ADMITTED_PLAN"], self.new)
                self.check_catchup(self.new)
        self.systemctl.side_effect = catchup
        self.assertEqual(self.update(), "done")

    def test_interrupted_backup_switch_or_timer_start_finishes_without_reopening(self):
        for after_start in (False, True):
            with self.subTest(after_start=after_start):
                def system(cmd, **kwargs):
                    if cmd[1] == "start":
                        if after_start:
                            self.system(cmd)
                        raise KeyboardInterrupt()
                    self.system(cmd)
                self.systemctl.side_effect = system
                with self.assertRaises(KeyboardInterrupt):
                    self.update()
                self.assertEqual(u.settings()["ADMITTED_PLAN"], self.new)
                with self.assertRaisesRegex(u.Refused, "another update"):
                    u.Update(self.args, dict(self.release, ci_run=55))
                self.systemctl.side_effect = self.system
                self.assertEqual(self.update(), "done")
                self.assertEqual(self.actions.count("open"), 1)
                self.assertTrue(self.timer)
                (u.OPERATOR / "ordinary-update.json").unlink()
                self.env.write_text(self.initial_env)
                self.gate.write_text(OLD)
                self.backend = "OLD running"
                self.actions.clear()

    def test_unknown_runtime_or_polling_ticket_blocks_automatic_rollback(self):
        def unsafe(plan):
            self.gate.with_name("activate").touch()
            raise u.Refused("unexpected activation")
        self.smoke.side_effect = unsafe
        with self.assertRaisesRegex(u.Refused, "polling possible"):
            self.update()
        self.assertNotIn("rollback", self.actions)
        self.gate.with_name("activate").unlink()
        self.backend = "UNKNOWN"
        with self.assertRaisesRegex(u.Refused, "unknown deployment"):
            self.update()
        self.assertNotIn("open", self.actions)

    def test_drifted_plan_pin_and_third_backup_target_fail_closed(self):
        self.env.write_text(self.initial_env.replace(str(self.old), str(u.OPERATOR / "unrelated")))
        with self.assertRaisesRegex(u.Refused, "independently"):
            u.switch_backup(self.old, self.new)
        self.env.write_text(self.initial_env)
        self.new.write_bytes(self.new.read_bytes() + b" ")
        with self.assertRaisesRegex(u.Refused, "receipt"):
            self.update()
        self.assertFalse(self.actions)


class ReleaseBackupTest(Fixture):
    def setUp(self):
        super().setUp()
        self.disk, self.state = self.root / "disk", self.root / "receipts"
        for d in (self.disk, self.state):
            d.mkdir(mode=0o700)
        self.now = datetime.now(timezone.utc).replace(microsecond=0)
        self.old = self.add_point(self.now - timedelta(days=7, hours=1))
        self.fresh = self.add_point(self.now - timedelta(hours=8))
        self.proof = self.state / "release-restore.json"
        self.tested = dict(bp.external_point(self.disk, self.state, self.old), result="PASS", tools="tools",
                           completed=(self.now-timedelta(days=7)).timestamp())
        u.atomic(self.proof, self.tested)

    def add_point(self, at):
        name = at.strftime(bp.STAMP)
        dest = point(self.disk, name)
        bp.record(self.state, name, bp.verified(dest))
        return name

    def check(self):
        return bp.release_backup(self.disk, self.state, self.now.timestamp(), "tools")

    def test_distinct_points_inclusive_8h_7d_and_expiry(self):
        self.assertEqual(self.check(), {"fresh": bp.external_point(self.disk, self.state, self.fresh), "tested": self.old})
        self.now += timedelta(seconds=1)
        with self.assertRaisesRegex(u.Refused, "eight-hour"):
            self.check()
        self.add_point(self.now)
        with self.assertRaisesRegex(u.Refused, "rehearsal required"):
            self.check()

    def test_failed_future_incompatible_and_unresolved_rehearsals(self):
        for change in (dict(result="FAIL"), dict(tools="previous implementation"), dict(pin="0"*64),
                       dict(completed=self.now.timestamp()+1), dict(schema="142"), dict(source="other")):
            with self.subTest(change=change):
                u.atomic(self.proof, dict(self.tested, **change))
                with self.assertRaises(u.Refused):
                    self.check()
        u.atomic(self.proof, self.tested)
        for name in ("release-hold", "restore-pending"):
            (self.state / name).touch()
            with self.assertRaisesRegex(u.Refused, "unresolved"):
                self.check()
            (self.state / name).unlink()
        (self.disk / self.fresh / "media.tar.age").write_bytes(b"corrupt")
        with self.assertRaisesRegex(u.Refused, "integrity mismatch"):
            self.check()

    def test_periodic_failure_stays_pending_and_retries_only_after_real_success(self):
        self.fresh = self.add_point(self.now)
        runner = self.root / "runner"
        runner.touch(mode=0o700)
        self.replace(u.media_backup, "recovery_tools", return_value="tools")
        expected = dict(bp.external_point(self.disk, self.state, self.fresh), tools="tools", result="PASS")
        run = self.replace(subprocess, "run", side_effect=[subprocess.CalledProcessError(1, "test"),
                           SimpleNamespace(stdout=b"{}"), SimpleNamespace(stdout=json.dumps(expected).encode())])
        for error in (subprocess.CalledProcessError, u.Refused):
            with self.assertRaises(error):
                bp.rehearse_if_due(self.disk, self.state, runner)
            self.assertTrue((self.state / "restore-pending").exists())
            with self.assertRaisesRegex(u.Refused, "unresolved"):
                self.check()
        bp.rehearse_if_due(self.disk, self.state, runner)
        bp.rehearse_if_due(self.disk, self.state, runner)
        self.assertEqual(run.call_count, 3)
        self.assertFalse((self.state / "restore-pending").exists())
        self.assertEqual(json.loads(self.proof.read_bytes())["point"], self.fresh)
        (self.state / "release-hold").touch()
        run.side_effect = None
        run.return_value = SimpleNamespace(stdout=json.dumps(expected).encode())
        bp.rehearse_if_due(self.disk, self.state, runner, force=True)
        with self.assertRaisesRegex(u.Refused, "unresolved"):
            self.check()

    def test_current_producer_and_matching_live_cluster_required(self):
        spool = self.root / "spool"
        shutil.copytree(self.disk, spool)
        point(spool, self.now.strftime(bp.STAMP))  # Encrypted locally, still in transit to the external disk.
        cfg = {"BACKUP_SPOOL": spool, "AGE_RECIPIENT_FILE": self.state / "recipient",
               "QUALIFIED_POINTS": self.state / "qualified", "BACKUP_TOOL": p.PROFILE / "backup_points.py"}
        cfg["AGE_RECIPIENT_FILE"].write_text("custodian")
        producer = bp.producer(cfg["AGE_RECIPIENT_FILE"])
        self.tested.update(tools=producer["tools"])
        u.atomic(self.proof, self.tested)
        plan = self.state / "plan"
        plan.write_text(json.dumps({"services":{"postgres":{"image":self.tested["postgres"]}}, "x-hookah-schema":143}))
        self.replace(bp, "status")
        self.replace(p, "verified_database", return_value="synthetic-db")
        self.replace(u.time, "time", return_value=self.now.timestamp())
        query = self.replace(p, "pg_query", return_value=self.tested["source"])
        self.replace(u, "ask", side_effect=lambda _, tools: bp.release_backup(self.disk, self.state, self.now.timestamp(), tools))
        marker = self.state / "scheduled-producer.json"
        u.atomic(marker, dict(version=producer, first=self.old))
        u.verify_backup(plan, cfg)
        query.return_value = "another-cluster"
        with self.assertRaisesRegex(u.Refused, "current production"):
            u.verify_backup(plan, cfg)
        u.atomic(marker, dict(version=producer, first=self.fresh))
        with self.assertRaisesRegex(u.Refused, "current backup producer"):
            u.verify_backup(plan, cfg)
        cfg["AGE_RECIPIENT_FILE"].write_text("new-public-recipient")
        with self.assertRaisesRegex(u.Refused, "enrolled backup implementation"):
            u.verify_backup(plan, cfg)

    def test_persistent_timer_after_reboot_defers_without_relabeling_old_point(self):
        u.atomic(self.state / "ordinary-update.json", {"phase":"staging"})
        with patch.object(u.media_backup, "backup") as backup:
            bp.create(self.state / "plan", self.state / "recipient", self.disk, self.state / "qualified")
            backup.assert_not_called()
        self.assertFalse((self.state / "scheduled-producer.json").exists())


class BoundariesTest(Fixture):
    def test_need_daemon_reload_compares_effective_schedule_before_continuing(self):
        cmd = "/usr/bin/python3 -B ${BACKUP_TOOL} create ${ADMITTED_PLAN} ${AGE_RECIPIENT_FILE} ${BACKUP_SPOOL} ${QUALIFIED_POINTS}"
        service = dict(Environment="DOCKER_HOST=unix:///var/run/docker.sock", EnvironmentFiles=str(u.BACKUP_ENV)+" (ignore_errors=no)",
                       ExecStart="{ path=/usr/bin/python3 ; argv[]="+cmd+" ; }", Type="oneshot", ActiveState="inactive",
                       Result="success", User="", ExecStartPre="", ExecStartPost="", ExecCondition="", ExecStop="", ExecStopPost="")
        timer = dict(UnitFileState="enabled", Unit=u.SERVICE, TimersMonotonic="", Persistent="yes",
                     TimersCalendar="{ OnCalendar=*-*-* 00,06,12,18:00:00 UTC ; }", ActiveState="active",
                     NextElapseUSecRealtime="tomorrow")
        def properties(unit, *keys):
            values = dict(FragmentPath="/etc/systemd/system/"+unit, DropInPaths="", NeedDaemonReload="yes")
            values.update(timer if unit == u.TIMER else service)
            return {k:values[k] for k in keys}
        self.replace(u, "unit_properties", side_effect=properties)
        self.replace(Path, "read_bytes", return_value=b"same unit")
        u.schedule(True)
        for values, key, value in ((service, "ExecStartPre", "other"), (timer, "TimersMonotonic", "other"),
                                   (timer, "TimersCalendar", "changed"), (service, "ActiveState", "activating"),
                                   (service, "Result", "exit-code"), (timer, "NextElapseUSecRealtime", "n/a")):
            original = values[key]
            values[key] = value
            with self.assertRaises(u.Refused):
                u.schedule(True)
            values[key] = original

    def test_local_child_refuses_foreign_cwd_and_unreviewed_paths(self):
        with patch.object(Path, "cwd", return_value=self.root), self.assertRaisesRegex(u.Refused, "STOP_PROJECT_CONTEXT"):
            u.project_identity("HT-LOCAL-FIXTURE")
        with patch.object(subprocess, "run") as ancestor, patch.object(subprocess, "check_output") as changes:
            changes.return_value = b"miniapp/src/screen.ts\0"
            u.compatible_diff(OLD, NEW)
            self.assertEqual(ancestor.call_args.kwargs["cwd"], u.ROOT)
            self.assertEqual(changes.call_args.kwargs["cwd"], u.ROOT)
            changes.return_value += b"backend/app/src/main/resources/db/migration/postgresql/V144__next.sql\0"
            with self.assertRaisesRegex(u.Refused, "strict"):
                u.compatible_diff(OLD, NEW)

    def test_only_exact_running_staging_release(self):
        release = {"revision": NEW, "image": "backend@sha256:"+"b"*64, "ci_run": 3}
        self.replace(u, "private_json", return_value={"status":"SUCCESS", "target":release})
        for version, image, ok in ((NEW, release["image"], True), (OLD, release["image"], False), (NEW,"wrong",False)):
            with patch.object(u, "output", side_effect=["container", image, json.dumps(dict(env="staging", version=version))]):
                if ok:
                    u.staging(release)
                else:
                    with self.assertRaises(u.Refused):
                        u.staging(release)
        with self.assertRaisesRegex(u.Refused, "differs from successful"):
            u.staging(dict(release, ci_run=4))

    def test_public_pages_reject_duplicates_truncation_and_mutating_result(self):
        rows = [{"id":i} for i in range(101)]
        for last in ([rows[-1]], [rows[0]], []):
            with patch.object(p, "github_get", side_effect=[dict(total_count=101, jobs=rows[:100]), dict(total_count=101, jobs=last)]):
                if last == [rows[-1]]:
                    self.assertEqual(p.github_pages("test", "jobs"), rows)
                else:
                    with self.assertRaises(u.Refused):
                        p.github_pages("test", "jobs")

    def test_release_receipt_requires_completed_restore_and_cleanup(self):
        import contextlib
        import io
        import sys
        import restore_test
        name = "full-20260312T000000Z"
        archive = point(self.root, name)
        pin = bp.verified(archive)
        image = (archive / "postgres-image").read_text()
        args = ["restore", str(archive), "volatile-key", image, "--point-sha256", pin, "--release-json"]
        self.replace(restore_test, "docker", side_effect=lambda *cmd: "daemon" if cmd[0] == "info" else "")
        restore = self.replace(restore_test, "restore")
        cleanup = self.replace(restore_test, "cleanup", side_effect=lambda journal, daemon: journal.unlink())
        with patch.dict(os.environ, XDG_STATE_HOME=str(self.root)):
            with patch.object(sys, "argv", args + ["--keep-quarantine"]), self.assertRaises(u.Refused):
                restore_test.main()
            restore.assert_not_called()
            with patch.object(sys, "argv", args), contextlib.redirect_stdout(io.StringIO()) as stdout:
                restore_test.main()
                self.assertEqual(json.loads(stdout.getvalue()), dict(point=name, pin=pin, postgres=image, source="123|456|1",
                                 schema="143", tools=u.media_backup.recovery_tools(p.ROOT), result="PASS"))
                cleanup.assert_called_once()
            cleanup.side_effect = u.Refused("cleanup failed")
            with patch.object(sys, "argv", args), contextlib.redirect_stdout(io.StringIO()) as stdout:
                with self.assertRaisesRegex(u.Refused, "cleanup failed"):
                    restore_test.main()
                self.assertEqual(stdout.getvalue(), "")

    def test_mac_session_binds_context_keeps_credentials_local_and_reports_custody_failure(self):
        config = self.root / "update.json"
        key, hosts = self.root / "ssh-key", self.root / "hosts"
        key.touch(mode=0o600)
        hosts.touch(mode=0o600)
        checkout = "/opt/hookah-production/releases/" + OLD
        u.atomic(config, dict(host="fixture.invalid", machine_sha256="e"*64, checkout=checkout,
                              known_hosts=str(hosts), ssh_identity=str(key)))
        args = SimpleNamespace(approved_sha=NEW, compatible_from=OLD, run=1, attempt=1, digest="sha256:"+"2"*64,
                               config=config, task_id="HT-LOCAL-SESSION")
        gate = self.replace(u, "project_identity")
        self.replace(u, "compatible_diff")
        request = self.replace(u, "handle_request", side_effect=[u.Refused("TOSHIBA unavailable"), True])
        # Preserve the replies that would have been sent over the single operator connection.
        class Replies(io.StringIO):
            def close(self):
                self.sent = self.getvalue().splitlines()
                super().close()
        replies = Replies()
        wire = 'HT-UPDATE {"kind":"backup"}\nHT-UPDATE {"kind":"browser"}\nHT-RESULT rolled-back\n'
        proc = SimpleNamespace(stdin=replies, stdout=io.StringIO(wire), wait=lambda:2)
        start = self.replace(subprocess, "Popen", return_value=proc)
        self.replace(u, "public_smoke")
        from unittest.mock import MagicMock
        sock = MagicMock()
        sock.__enter__.return_value.getsockname.return_value = ("127.0.0.1", 32000)
        self.replace(u.socket, "socket", return_value=sock)
        with patch.dict(os.environ, GH_TOKEN="synthetic-marker"), self.assertRaisesRegex(u.Refused, "rolled-back"):
            u.mac(args, u.flags(args))
        self.assertEqual([json.loads(s) for s in replies.sent], [{"refused":"TOSHIBA unavailable"}, True])
        self.assertEqual(gate.call_count, 2)
        self.assertEqual(request.call_count, 2)
        command = start.call_args.args[0]
        self.assertEqual(start.call_args.kwargs["cwd"], u.ROOT)
        self.assertIn("ForwardAgent=no", command)
        self.assertIn("BatchMode=yes", command)
        self.assertIn("cd " + checkout + " && exec env -i ", command[-1])
        self.assertNotIn("synthetic-marker", " ".join(command))
        self.assertNotIn("GH_TOKEN", " ".join(command))
