"""Cutover handoff contracts shared by unit tests and the offline systemd rehearsal."""
import contextlib
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import uuid

import production_update as release

profile, traffic = release.profile, release.traffic


class BackupHandoff(unittest.TestCase):
    def setUp(self):
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        self.root = Path(stack.enter_context(tempfile.TemporaryDirectory())).resolve()
        operator = self.root / "operator"
        operator.mkdir(mode=0o700)
        self.gate = self.root / "open"
        self.old = operator / "old.json"
        self.sha = "b" * 40
        self.new = operator / ("plan-" + self.sha + ".json")
        self.jvm = str(uuid.uuid4())
        self.decision = self.sha + " " + self.jvm
        self.polling = "running"
        self.observed_jvm = self.jvm
        self.has_binding = True
        self.open_count = 0
        self.bound_at_open = None
        self.timer = False
        self.start_failure = False
        self.web_reads = []

        def mock(obj, name, **kwargs):
            return stack.enter_context(patch.object(obj, name, **kwargs))
        self.mock = mock
        for unit in (release.TIMER, release.SERVICE):
            (self.root / unit).write_bytes((profile.PROFILE / unit).read_bytes())
        stack.enter_context(patch.object(profile, "PROFILE", self.root))
        stack.enter_context(patch.object(profile, "GATE", self.gate))
        stack.enter_context(patch.object(release, "OPERATOR", operator))
        self.env = self.environment_file()
        stack.enter_context(patch.object(release, "BACKUP_ENV", self.env))
        self.journal = operator / "ordinary-update.json"
        self.args = SimpleNamespace(approved_sha=self.sha, compatible_from=profile.PARTICIPATION_SOURCE["revision"],
                                    digest="sha256:" + "b"*64, run=3, attempt=1, accept_jvm=self.jvm,
                                    participation_cutover=True)
        self.target = release.flags(self.args)
        pins = {}
        for path, ver, candidate in ((self.old, 143, profile.PARTICIPATION_SOURCE), (self.new, 147, self.target)):
            doc = {"services": {"backend": {"image": candidate["image"], "environment": {
                "APP_VERSION": candidate["revision"], "TELEGRAM_BOT_ENABLED": "true"}}}, "x-hookah-schema": ver,
                "x-hookah-release-evidence": {"candidate": candidate, "attempt": 1}}
            if ver == 147:
                doc["x-hookah-transition"] = {"from": profile.PARTICIPATION_SOURCE, "to": profile.release_identity(doc)}
            profile.publish_plan(path, json.dumps(doc).encode())
            pins[path] = profile.admission_pin(path).read_text()
        binding = operator / "postgres-binding.json"
        binding.write_bytes(b"synthetic bound database")
        recipient = operator / "recipient.txt"
        recipient.write_text("fixture")
        self.env.write_text(f"ADMITTED_PLAN={self.old}\nBACKUP_TOOL={self.root}/backup_points.py\n"
                            f"BACKUP_SPOOL={self.root}/scheduled\nAGE_RECIPIENT_FILE={recipient}\n"
                            f"QUALIFIED_POINTS={operator}/qualified.sha256\n")
        self.env.chmod(0o600)
        self.record = dict(key=dict(candidate=self.target, attempt=1, predecessor=self.args.compatible_from,
                                    maintenance="participation-143-147"), old=str(self.old), old_pin=pins[self.old],
                           new_pin=pins[self.new], binding=hashlib.sha256(binding.read_bytes()).hexdigest(),
                           target_id="new-container", phase="acceptance")
        self.record_db = mock(profile, "verified_database", side_effect=self.database)
        mock(profile, "verify_running")
        mock(profile.host, "lock_args", return_value={})
        mock(traffic, "state", return_value="NEW running")
        mock(traffic, "image_assets")
        mock(traffic, "await_readiness", return_value=self.jvm)
        mock(traffic, "check_edge_closed", side_effect=lambda: self.assertFalse(self.gate.exists()))
        mock(release, "closed_smoke")
        mock(release.media_backup, "backup_target", side_effect=self.backup_target)
        mock(traffic, "dispatch_locked", side_effect=self.dispatch)
        self.migrate = mock(release.ParticipationCutover, "migrate", side_effect=AssertionError("second migration"))
        release.atomic(self.journal, self.record)
        self.install_timer()
        real_run, real_output = subprocess.run, subprocess.check_output
        def run(cmd, *args, **kwargs):
            if cmd[:2] == ["docker", "update"]:
                return subprocess.CompletedProcess(cmd, 0)
            if cmd == ["systemctl", "start", release.TIMER]:
                return self.start_timer(real_run, cmd, kwargs)
            return real_run(cmd, *args, **kwargs)
        def output(cmd, *args, **kwargs):
            if cmd[0] == "curl":
                self.web_reads.append(cmd[-1])
                if cmd[-1].endswith("/version"):
                    return json.dumps(dict(service="backend", env="prod", version=self.sha, instance=self.observed_jvm,
                                           polling=self.polling)) + "\n200"
                return "401"
            return real_output(cmd, *args, **kwargs)
        mock(subprocess, "run", side_effect=run)
        mock(subprocess, "check_output", side_effect=output)

    def environment_file(self):
        return self.root / "backup.env"

    def install_timer(self):
        def schedule(active, **_):
            if active and not self.timer_active():
                raise profile.Refused("backup timer not scheduled")
        self.mock(release, "schedule", side_effect=schedule)

    def start_timer(self, real_run, cmd, kwargs):
        if self.start_failure:
            raise subprocess.CalledProcessError(1, cmd)
        self.timer = True

    def timer_active(self):
        return self.timer

    def change_loaded_unit(self):
        self.mock(release, "schedule", side_effect=profile.Refused("backup unit drift"))

    def database(self, *args, **kwargs):
        if not self.has_binding:
            raise profile.Refused("wrong database binding")
        return "pinned-postgres"

    def backup_target(self, plan, recipient, *, online):
        self.assertEqual((plan, recipient, online), (self.new, "fixture", True))
        return self.database(), "new-container", "true"

    def dispatch(self, args):
        if args.action == "close":
            self.gate.unlink(missing_ok=True)
            self.gate.with_name("activate").unlink(missing_ok=True)
            return
        self.assertEqual((args.action, args.expected_instance, self.polling), ("open", self.jvm, "running"))
        self.open_count += 1
        self.bound_at_open = (self.timer_active(), release.settings()["ADMITTED_PLAN"])
        self.gate.with_name("activate").write_text(self.decision)
        self.gate.write_text(self.decision)
        self.polling = "degraded"

    def resume(self):
        return release.ParticipationCutover(self.args, self.target).run()

    def previously_opened(self):
        self.record.update(phase="opening", accepted_jvm=self.jvm)
        release.atomic(self.journal, self.record)
        self.gate.with_name("activate").write_text(self.decision)
        self.gate.write_text(self.decision)
        self.polling = "degraded"

    def assert_protected(self):
        self.assertTrue(self.timer_active())
        self.assertEqual(release.settings()["ADMITTED_PLAN"], self.new)
        self.assertEqual(profile.plan_schema(json.loads(self.new.read_bytes())), 147)
        self.assertEqual(release.private_json(self.journal)["phase"], "done")
        self.assertEqual(self.gate.read_text(), self.decision)
        self.assertTrue(any(url.endswith("/api/guest/catalog") for url in self.web_reads))

    def test_timer_protects_first_open_and_survives_immediate_degradation(self):
        self.assertEqual(self.resume(), "done")
        self.assertEqual(self.bound_at_open, (True, self.new))
        self.assert_protected()
        self.assertEqual(self.resume(), "done")
        self.assertEqual(self.open_count, 1)

    def test_interrupted_open_recovers_old_unarmed_journal_without_reopening(self):
        self.previously_opened()
        self.assertFalse(self.timer_active())
        self.assertEqual(self.resume(), "done")
        self.assert_protected()
        self.assertEqual(self.open_count, 0)

    def test_lost_open_reply_leaves_a_working_timer_before_any_resume(self):
        def lost_reply(args):
            self.dispatch(args)
            raise InterruptedError("lost response after ingress opened")
        with patch.object(traffic, "dispatch_locked", side_effect=lost_reply), self.assertRaises(InterruptedError):
            self.resume()
        self.assertTrue(self.timer_active())
        self.assertEqual(release.settings()["ADMITTED_PLAN"], self.new)
        saved = release.private_json(self.journal)
        self.assertEqual((saved["phase"], saved["backup_plan"]), ("opening", str(self.new)))
        self.args.accept_jvm = None
        self.assertEqual(self.resume(), "done")
        self.assert_protected()
        self.assertEqual(self.open_count, 1)

    def test_initial_edge_check_still_requires_running(self):
        self.previously_opened()
        with self.assertRaisesRegex(profile.Refused, "unexpected public backend"):
            release.check_open(self.new)

    def test_stale_v143_producer_never_protects_a_v147_open(self):
        self.env.write_text(self.env.read_text().replace(str(self.root / "backup_points.py"),
                                                         str(self.root / "previous" / "backup_points.py")))
        with self.assertRaisesRegex(profile.Refused, "enrolled V147 backup producer"):
            self.resume()
        self.assertFalse(self.gate.exists())
        self.previously_opened()
        with self.assertRaisesRegex(profile.Refused, "enrolled V147 backup producer"):
            self.resume()
        self.assertFalse(self.gate.exists())
        self.assertNotEqual(release.private_json(self.journal)["phase"], "done")

    def test_changed_loaded_unit_refuses_before_switching_or_starting(self):
        self.change_loaded_unit()
        with patch.object(self, "start_timer", wraps=self.start_timer) as start:
            with self.assertRaisesRegex(profile.Refused, "backup unit drift"):
                self.resume()
            start.assert_not_called()
        self.assertEqual(release.settings()["ADMITTED_PLAN"], self.old)
        self.assertFalse(self.timer_active())
        self.assertFalse(self.gate.exists())

    def test_timer_failure_before_open_keeps_ingress_closed(self):
        self.start_failure = True
        with self.assertRaisesRegex(profile.Refused, "backup"):
            self.resume()
        self.assertFalse(self.gate.exists())
        self.assertFalse(self.timer_active())
        self.assertEqual(self.open_count, 0)
        self.assertEqual(release.private_json(self.journal)["phase"], "acceptance")

    def test_timer_failure_after_interrupted_open_recloses_ingress(self):
        self.previously_opened()
        self.start_failure = True
        with self.assertRaisesRegex(profile.Refused, "backup"):
            self.resume()
        self.assertFalse(self.gate.exists())
        self.assertFalse(self.gate.with_name("activate").exists())
        self.assertNotEqual(release.private_json(self.journal)["phase"], "done")
        self.assertEqual(self.open_count, 0)
        with self.assertRaisesRegex(profile.Refused, "fresh manual acceptance"):
            self.resume()

    def test_foreign_jvm_revoked_binding_hold_and_operator_close_deny_completion(self):
        self.previously_opened()
        for case in ("foreign", "revoked", "db", "hold", "closed"):
            with self.subTest(case=case):
                self.observed_jvm = str(uuid.uuid4()) if case == "foreign" else self.jvm
                self.polling = "revoked" if case == "revoked" else "degraded"
                self.has_binding = case != "db"
                hold = self.gate.with_name("recovery-hold")
                if case == "hold":
                    hold.touch()
                if case == "closed":
                    self.gate.unlink()
                    self.gate.with_name("activate").unlink()
                with self.assertRaises(profile.Refused):
                    self.resume()
                self.assertNotEqual(release.private_json(self.journal)["phase"], "done")
                self.assertEqual(self.open_count, 0)
                hold.unlink(missing_ok=True)
