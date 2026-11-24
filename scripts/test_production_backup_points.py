"""Scheduled full points: exercise real rsync, retention, custody and publication without a server."""
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from production_profile import PROFILE, Refused
sys.path.insert(0, str(PROFILE))
import backup_points as bp
import media_backup as media


def point(root, name):
    dest = root / name
    dest.mkdir()
    for file, data in [("database.dump.age", b"encrypted dump fixture"), ("postgres-image", b"postgres@sha256:" + b"a"*64),
                       ("schema-version", b"143"), ("media.tar.age", b"encrypted media fixture"),
                       ("source-coordinate", b"123|456|1|A/FF")]:
        (dest / file).write_bytes(data)
    files = ("database.dump.age", "postgres-image", "schema-version", "media.tar.age", "source-coordinate")
    manifest = "".join(hashlib.sha256((dest / f).read_bytes()).hexdigest() + "  " + f + "\n" for f in files)
    (dest / "SHA256SUMS").write_text(manifest)
    return dest


class ScheduledPointsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name).resolve()
        self.source, self.dest, self.state = (self.root / x for x in ("source", "toshiba", "private"))
        for p in (self.source, self.dest, self.state):
            p.mkdir(mode=0o700)
        self.today = datetime.now(timezone.utc).strftime(bp.STAMP)

    def pull(self):
        bp.pull_points(str(self.source) + "/", self.dest, self.state)

    def test_transfer_checks_ciphertext_and_recovers_interrupted_receipt(self):
        src = point(self.source, self.today)
        # An unpublished server attempt must never enter the catalog.
        (self.source / ".writing-partial").mkdir()
        self.pull()
        final = self.dest / self.today
        receipt = self.state / (self.today + ".sha256")
        self.assertEqual(receipt.read_text(), bp.verified(src) + "  " + self.today + "\n")
        self.assertEqual(bp.verified(src), bp.verified(final))
        receipt.unlink()
        self.pull()
        self.assertTrue(receipt.exists())
        (final / "media.tar.age").write_bytes(b"corrupt")
        with self.assertRaisesRegex(Refused, "integrity mismatch"):
            self.pull()
        self.assertEqual(len(list(self.dest.iterdir())), 1)

    def test_failed_transfer_and_full_disk_do_not_publish(self):
        src = point(self.source, self.today)
        with patch.object(media.host, "check_space", side_effect=Refused("insufficient space")), \
                self.assertRaisesRegex(Refused, "insufficient space"):
            self.pull()
        (src / "media.tar.age").write_bytes(b"changed")
        with self.assertRaisesRegex(Refused, "integrity mismatch"):
            self.pull()
        self.assertFalse(any(self.dest.iterdir()))
        self.assertFalse(any(self.state.iterdir()))

    def test_orphan_toshiba_copy_cannot_establish_a_different_custody_pin(self):
        point(self.source, self.today)
        orphan = point(self.dest, self.today)
        original = (orphan / "media.tar.age").read_bytes()
        (orphan / "media.tar.age").write_bytes(b"other")
        manifest = orphan / "SHA256SUMS"
        manifest.write_text(manifest.read_text().replace(hashlib.sha256(original).hexdigest(),
                                                          hashlib.sha256(b"other").hexdigest()))
        with self.assertRaisesRegex(Refused, "custody receipt"):
            self.pull()
        self.assertFalse(any(self.state.iterdir()))

    def test_wal_change_at_snapshot_acquisition_refuses_before_dump(self):
        with patch.object(subprocess, "Popen") as process:
            session = MagicMock()
            process.return_value.__enter__.return_value = session
            session.stdout = io.StringIO("A/FE\n00000001-0000000A-1\nA/FF\n")
            with self.assertRaisesRegex(Refused, "WAL advanced"):
                with media.snapshot("fixture", online=True):
                    self.fail("unstable snapshot admitted")
            self.assertEqual(process.call_count, 1)

    def test_directory_lock_blocks_competitor_and_releases(self):
        with bp.exclusive(self.dest):
            res = subprocess.run([sys.executable, "-B", "-c",
                "import sys;sys.path.insert(0,sys.argv[1]);import backup_points as b;"
                "lock=b.exclusive(b.Path(sys.argv[2]));lock.__enter__()", str(PROFILE), str(self.dest)], capture_output=True)
            self.assertNotEqual(res.returncode, 0)
            self.assertIn(b"BlockingIOError", res.stderr)
        with bp.exclusive(self.dest):
            pass

    def test_same_slot_retries_do_not_take_a_second_snapshot(self):
        (self.state / "recipient").write_text("synthetic")
        def capture(plan, dest, recipient, metadata_only, *, online):
            self.assertTrue(online)
            self.assertFalse(metadata_only)
            point(dest.parent, dest.name)
        with patch.object(media, "backup", side_effect=capture) as capturer:
            for _ in range(2):
                bp.create(self.root / "plan", self.state / "recipient", self.dest, self.state / "qualified")
            self.assertEqual(capturer.call_count, 1)
        self.assertEqual(len(list(self.dest.iterdir())), 1)

    def test_monitor_detects_a_missing_slot_after_grace(self):
        now = datetime(2026, 1, 14, 14, tzinfo=timezone.utc)
        for stamp in ("20260113T180000", "20260114T000000", "20260114T060000"):
            point(self.dest, "full-" + stamp + "Z")
        with self.assertRaisesRegex(Refused, "missing scheduled UTC slots: full-20260114T120000Z"):
            bp.status(self.dest, now)
        point(self.dest, "full-20260114T121022Z")
        bp.status(self.dest, now)

    def test_retention_requires_two_verified_pins_and_preserves_foreign_points(self):
        points = [point(self.dest, "full-202601%02dT000000Z" % n) for n in (1, 2, 3, 4, 12, 13)]
        foreign = point(self.dest, "manual-first")
        pins = self.state / "qualified.sha256"
        now = datetime(2026, 1, 14, tzinfo=timezone.utc)
        with self.assertRaisesRegex(Refused, "two qualified pins"):
            bp.expire(self.dest, pins, now)
        lines = [bp.verified(p) + "  " + p.name + "\n" for p in (points[1], points[3])]
        pins.write_text(lines[0] + "0"*64 + lines[1][64:])
        pins.chmod(0o600)
        with self.assertRaisesRegex(Refused, "custody receipt"):
            bp.expire(self.dest, pins, now)
        self.assertTrue(points[0].exists())
        pins.write_text("".join(lines))
        bp.expire(self.dest, pins, now)
        self.assertEqual([p.exists() for p in points], [False, True, False, True, True, True])
        self.assertTrue(foreign.exists())

    def test_running_target_only_with_online_and_receipt(self):
        import production_profile as profile
        import production_traffic as traffic
        env = dict.fromkeys(("VENUE_INFO_MEDIA_R2_ENABLED", "STAFF_PHOTOS_R2_ENABLED", "VENUE_INFO_PDF_WORKER_ENABLED"), "true")
        plan = self.root / "plan"
        plan.write_text(json.dumps({"services":{"backend":{"environment":env}}}))
        with patch.object(profile, "verify_plan_pin") as pin, patch.object(media, "verified_database", return_value="pg"), \
                patch.object(traffic, "compose", return_value="backend"), patch.object(media.host, "GATE", self.root / "open"), \
                patch.object(traffic, "state", return_value="OLD running") as state, \
                patch.object(subprocess, "check_output", return_value=json.dumps([{"Id":"backend","Config":{"Env":[k+"=true" for k in env]}}])):
            with self.assertRaisesRegex(Refused, "stopped admitted"):
                media.backup_target(plan, "age")
            self.assertEqual(media.backup_target(plan, "age", online=True), ("pg","backend","true"))
            pin.assert_called_once_with(plan)
            (self.root / "recovery-hold").touch()
            with self.assertRaisesRegex(Refused, "during recovery"):
                media.backup_target(plan, "age", online=True)
            state.return_value = "OLD stopped"
            with self.assertRaisesRegex(Refused, "running admitted"):
                media.backup_target(plan, "age", online=True)

    def test_calendar_and_launch_agent_only_pull(self):
        timer = (PROFILE / "hookah-production-backup.timer").read_text()
        self.assertIn("OnCalendar=*-*-* 00,06,12,18:00:00 UTC", timer)
        self.assertIn("Persistent=true", timer)
        plist = plistlib.loads((PROFILE / "com.hookahtootah.production-db-backup.plist.example").read_bytes())
        self.assertEqual(plist["Label"], "com.hookahtootah.production-db-backup")
        self.assertIn("pull", plist["ProgramArguments"])
        self.assertEqual(plist["StartCalendarInterval"], [{"Hour": h, "Minute": 0} for h in (0,6,12,18)])
