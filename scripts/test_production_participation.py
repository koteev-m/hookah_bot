"""Production maintenance policy and approval regression tests; external boundaries are isolated."""
import contextlib
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

import production_update as release
import production_profile as profile
import production_traffic as traffic


class CutoverPolicy(unittest.TestCase):
    def test_offline_only_exact_pair_and_history(self):
        rows = [dict(r, success=True) for r in profile.history_for(profile.source_manifest())]
        for version in (142, 143, 147):
            profile.check_history(json.dumps(rows[:version]), [version])
        with self.assertRaises(profile.Refused):
            profile.check_history(json.dumps(rows), [143])
        rows[143]["checksum"] += 1
        with self.assertRaises(profile.Refused):
            profile.check_history(json.dumps(rows), [147])
        with tempfile.TemporaryDirectory() as tmp:
            old = Path(tmp) / "old"
            def plan(schema, revision, image):
                return {"services": {"backend": {"image": image, "environment": {"APP_VERSION": revision}}},
                        "x-hookah-schema": schema}
            source = profile.PARTICIPATION_SOURCE
            target = dict(revision="b"*40, image=source["image"].split("@")[0] + "@sha256:" + "b"*64)
            old.write_text(json.dumps(plan(143, **source)))
            new = plan(147, **target)
            new["x-hookah-transition"] = {"from": source, "to": target}
            raw = json.dumps(new).encode()
            profile.confirm_transition(old, raw, offline=True)
            with self.assertRaises(profile.Refused):
                profile.confirm_transition(old, raw)
            old.write_text(json.dumps(plan(143, "a"*40, source["image"])))
            with self.assertRaises(profile.Refused):
                profile.confirm_transition(old, raw, offline=True)

    def test_admit_pins_the_schema_checked_in_the_packaged_image(self):
        with contextlib.ExitStack() as stack, tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "plan"
            def stub(name, result=None):
                return stack.enter_context(patch.object(profile, name, return_value=result))
            stub("runtime", {})
            stub("candidate", profile.PARTICIPATION_SOURCE)
            stub("render", b'{"services":{}}')
            stub("verify_published", {"candidate": profile.PARTICIPATION_SOURCE})
            verify = stub("verify_images")
            stub("verify_bot")
            for version in (143, 147):
                plan = output.with_suffix("." + str(version))
                args = SimpleNamespace(action="admit", schema=version, runtime=None, candidate=None,
                                       output=plan, previous=None, evidence=None, evidence_sha256=None, image_sizes=None)
                profile.execute(args)
                verify.assert_called_with({}, profile.PARTICIPATION_SOURCE, None, [], schema=version)
                profile.verify_plan_pin(plan)
                self.assertEqual(profile.plan_schema(json.loads(plan.read_bytes())), version)

    def test_acceptance_requires_exact_reviewed_jvm_and_finish_can_resume_after_open(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.ExitStack() as stack:
            root = Path(tmp)
            gate = root / "open"
            op = release.ParticipationCutover.__new__(release.ParticipationCutover)
            op.old, op.new, op.journal = root / "old", root / "new", root / "journal"
            op.args = SimpleNamespace(accept_jvm=None)
            op.record = {"phase": "acceptance", "target_id": "pinned-container"}
            op.release = {"revision": "b" * 40}
            instance = str(uuid.uuid4())
            decision = op.release["revision"] + " " + instance
            op.plans = Mock()
            op.closed = Mock()
            def open_target(args):
                self.assertEqual((args.action, args.plan, args.expected_instance), ("open", op.new, instance))
                gate.write_text(decision)
                raise InterruptedError("lost open response")
            opened = stack.enter_context(patch.object(traffic, "dispatch_locked", side_effect=open_target))
            stack.enter_context(patch.object(profile, "GATE", gate))
            stack.enter_context(patch.object(profile.host, "lock_args", return_value={}))
            stack.enter_context(patch.object(subprocess, "run"))
            stack.enter_context(patch.object(traffic, "state", return_value="NEW running"))
            stack.enter_context(patch.object(traffic, "image_assets"))
            stack.enter_context(patch.object(traffic, "await_readiness", return_value=instance))
            stack.enter_context(patch.object(profile, "verified_database"))
            stack.enter_context(patch.object(release, "closed_smoke"))
            stack.enter_context(patch.object(release, "check_open"))
            stack.enter_context(patch.object(release, "schedule"))
            stack.enter_context(patch.object(release, "settings", return_value={"ADMITTED_PLAN": op.new, "BACKUP_TOOL": profile.PROFILE / "backup_points.py"}))
            self.assertEqual(op.run(), "acceptance")
            op.args.accept_jvm = str(uuid.uuid4())
            with self.assertRaisesRegex(profile.Refused, "another JVM"):
                op.run()
            opened.assert_not_called()
            op.args.accept_jvm = instance
            op.arm_backups = Mock()
            with self.assertRaises(InterruptedError):
                op.run()
            op.record = json.loads(op.journal.read_text())
            self.assertEqual(op.record, dict(phase="opening", target_id="pinned-container",
                                             accepted_jvm=instance, backup_plan=str(op.new)))
            self.assertEqual(op.run(), "done")
            opened.assert_called_once()
            op.arm_backups.assert_called_once()


if __name__ == "__main__":
    unittest.main()
