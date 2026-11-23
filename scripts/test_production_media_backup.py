"""Media backup contracts with synthetic objects and no provider access."""
from contextlib import nullcontext
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from production_profile import PROFILE, Refused
sys.path.insert(0, str(PROFILE))
import media_backup as media
import restore_test as recovery


class ProductionMediaBackupTest(unittest.TestCase):
    def setUp(self):
        self.key = "venue-info/12345678-0000-0000-0000-000000000001.pdf"
        self.data = b"%PDF-1.4 synthetic recovery fixture"
        self.raw = json.dumps([[self.key, len(self.data), hashlib.sha256(self.data).hexdigest(), "a"*64, True]])
        self.rows = media.inventory(self.raw)
        self.client = Mock()
        self.client.get_object.return_value = {"Body": io.BytesIO(self.data)}

    def packed(self):
        stream = io.BytesIO()
        media.archive(self.rows, self.client, stream)
        self.client.get_object.assert_called_once_with(Bucket=media.PRODUCTION_BUCKET, Key=self.key)
        stream.seek(0)
        return stream

    def test_backup_refuses_stale_plan_and_uses_installed_backend_mode(self):
        import production_traffic as traffic
        flags = ("STAFF_PHOTOS_R2_ENABLED", "VENUE_INFO_MEDIA_R2_ENABLED", "VENUE_INFO_PDF_WORKER_ENABLED")
        with tempfile.TemporaryDirectory() as tmp, patch.object(media, "verified_database", return_value="pg"), \
                patch.object(traffic, "compose", return_value="backend"), patch.object(traffic, "state") as state, \
                patch.object(subprocess, "check_output") as inspect:
            plan = Path(tmp) / "plan"
            point = Path(tmp) / "point"
            state.return_value = "OLD stopped"
            for wanted, actual in (("false", "true"), ("true", "false"), ("true", "true"), ("false", "false")):
                plan.write_text(json.dumps({"services": {"backend": {"environment": dict.fromkeys(flags, wanted)}}}))
                inspect.return_value = json.dumps([{"Id": "backend", "Config": {"Env": [f"{k}={actual}" for k in flags]}}])
                if wanted != actual:
                    with patch.object(sys, "argv", ["media_backup.py", "backup", str(plan), str(point), "recipient"]), \
                            patch.object(media.host, "operator_lock", return_value=nullcontext()), patch.object(media.os, "umask"), \
                            patch.object(media, "capture") as dump, \
                            self.assertRaisesRegex(Refused, "^installed media mode differs from backup plan$"):
                        media.main()
                    inspect.assert_called_with(["docker", "inspect", "backend"])
                    dump.assert_not_called()
                    self.assertFalse((point / "SHA256SUMS").exists())
                else:
                    self.assertEqual(media.backup_target(plan, "recipient"), ("pg", "backend", actual))
            state.return_value = "unknown"
            with self.assertRaises(Refused):
                media.backup_target(plan, "recipient")

    def test_changed_inventory_restores_only_matching_private_objects(self):
        row = self.rows[self.key]
        keys = [self.key.replace("0001.pdf", f"000{i}.pdf") for i in range(2, 7)]
        deleted, hidden, permissions_changed, replaced, new = keys
        archived = dict.fromkeys([self.key, deleted, hidden, permissions_changed, replaced], row)
        metadata = {self.key: row, hidden: (*row[:3], False), permissions_changed: (*row[:2], "b"*64, True), new: row}
        self.client.get_object.side_effect = lambda **kw: {"Body": io.BytesIO(self.data)}
        data = io.BytesIO()
        media.archive(archived, self.client, data)
        selected = {}
        counts = media.unpack(io.BytesIO(data.getvalue()), archived, metadata, selected.__setitem__)
        self.assertEqual(counts, {"selected": 1, "excluded": 4, "newer_without_bytes": 1})
        self.assertEqual(selected, {self.key: self.data})
        with self.assertRaises(Refused):
            media.unpack(io.BytesIO(data.getvalue()), archived, {new: row, self.key: (row[0], "0"*64, *row[2:])})

    def test_quarantine_requires_custody_and_orders_same_cluster_points(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = "postgres@sha256:" + "a"*64
            (root / "postgres-image").write_text(image)
            (root / "database.dump.age").write_bytes(b"fixture")
            manifest = "".join(hashlib.sha256((root / name).read_bytes()).hexdigest() + "  " + name + "\n"
                               for name in ("database.dump.age", "postgres-image"))
            (root / "SHA256SUMS").write_text(manifest)
            recovery.verify_archive(root, image, hashlib.sha256(manifest.encode()).hexdigest())
            with self.assertRaises(Refused):
                recovery.verify_archive(root, image, "f"*64)
            with patch.dict(recovery.os.environ, XDG_STATE_HOME=folder), patch.object(recovery, "docker", side_effect=lambda *cmd: "daemon" if cmd[0] == "info" else ""), \
                    patch.object(sys, "argv", ["restore", folder, "key", image, "--keep-quarantine"]), \
                    patch.object(recovery, "restore") as run, self.assertRaisesRegex(Refused, "custody checksum required"):
                recovery.main()
            run.assert_not_called()
            later = root / "later"
            later.mkdir()
            (root / "source-coordinate").write_text("1|42|2|A/FF")
            for value in ("1|42|2|A/FF", "1|42|2|A/01", "1|42|3|B/00", "2|42|2|B/00", "incomplete"):
                (later / "source-coordinate").write_text(value)
                with self.subTest(value=value), self.assertRaises(Refused):
                    recovery.check_pair(root, later)
            (later / "source-coordinate").write_text("1|42|2|B/00")
            recovery.check_pair(root, later)

    def test_stale_local_inventory_cannot_enable_export(self):
        result = subprocess.run([sys.executable, "-B", recovery.__file__, "point", "key", "image",
                                 "--current-media-inventory", "stale.json", "--export-media", "objects"],
                                capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b"unrecognized arguments", result.stderr)
        for raw in ("{}", "null", '[[]]', self.raw.replace("true", "null")):
            with self.subTest(raw=raw), self.assertRaises(Refused):
                media.inventory(raw)

    def test_missing_extra_or_corrupt_objects_fail(self):
        raw = self.packed().getvalue()
        for rows, payload in (({}, raw), (self.rows, bytes(10240)),
                              (self.rows, raw.replace(self.data, b"X"*len(self.data)))):
            with self.assertRaises(Refused):
                media.unpack(io.BytesIO(payload), rows, rows, None)
        self.client.get_object.return_value = {"Body": io.BytesIO(b"wrong")}
        with self.assertRaises(Refused):
            media.archive(self.rows, self.client, io.BytesIO())
        self.client.get_object.side_effect = OSError("synthetic unavailable")
        with self.assertRaises(OSError):
            media.archive(self.rows, self.client, io.BytesIO())

    def test_authenticated_recovery_and_incomplete_ciphertext(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            identity = root / "fixture.key"
            subprocess.run(["age-keygen", "-o", identity], check=True, capture_output=True)
            recipient = subprocess.check_output(["age-keygen", "-y", identity], text=True).strip()
            ciphertext = root / "media.tar.age"
            subprocess.run(["age", "-r", recipient, "-o", ciphertext], input=self.packed().read(), check=True)
            stored = {}
            counts = media.restore(root, identity, self.rows, self.rows, stored.__setitem__)
            self.assertEqual((counts["selected"], stored), (1, {self.key: self.data}))
            ciphertext.write_bytes(ciphertext.read_bytes()[:-4])
            with self.assertRaises((Refused, media.tarfile.TarError)):
                media.restore(root, identity, self.rows, self.rows)
