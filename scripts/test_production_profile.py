import contextlib
import copy
import hashlib
import io
import json
import subprocess
import os
from pathlib import Path
import tempfile
import textwrap
import unittest
from unittest.mock import patch

import production_profile as p
import test_production_backend as ci


class ProductionProfileTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name)
        self.path = self.folder / "runtime"
        self.values = {k: hashlib.sha256(("fixture-" + k).encode()).hexdigest() for k in p.SECRET_KEYS}
        self.values.update(POSTGRES_IMAGE="postgres@sha256:" + "a" * 64, TELEGRAM_BOT_ENABLED="false",
                           TELEGRAM_BOT_TOKEN="", PLATFORM_OWNER_TELEGRAM_ID="", TELEGRAM_MAIN_MINI_APP_CONFIGURED="false")
        self.release = {"image": "ghcr.io/koteev-m/hookah_bot/backend@sha256:" + "b" * 64,
                        "revision": "c" * 40, "ci_run": 10}
        self.write_values()

    def write_values(self, **overrides):
        self.path.write_text("".join(f"{k}={v}\n" for k, v in (self.values | overrides).items()))
        self.path.chmod(0o600)

    def test_backend_ci_requires_all_pilot_classes_and_successful_case_xml(self):
        required = {"ProductionTrafficGateTest", "billing.SubscriptionBillingEngineTest",
                    "billing.subscription.SubscriptionBillingConfigTest", "platform.PlatformBillingRoutesTest",
                    "billing.ProductionBillingPostgresTest", "billing.SubscriptionBillingPostgresTest"}
        self.assertTrue(required.issubset(ci.CLASSES))
        workflow = (ci.ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("run: python3 -B scripts/test_production_backend.py", workflow)
        name = "com.hookah.platform.backend.ProductionTrafficGateTest"
        xml = self.folder / "report.xml"
        template = ('<testsuite name="%s" tests="1" failures="0" errors="0" skipped="0">'
                    '<testcase classname="%s" name="probe">%s</testcase></testsuite>')
        xml.write_text(template % (name, name, ""))
        with self.assertRaises(SystemExit):
            ci.check_report(xml, name)
        def report(names, result=""):
            cases = "".join(f'<testcase classname="{name}" name="{case}()">{result}</testcase>' for case in names)
            xml.write_text(f'<testsuite name="{name}" tests="{len(names)}" failures="0" errors="0" skipped="0">{cases}</testsuite>')
        report(ci.REQUIRED_GATE_CASES)
        self.assertEqual(len(ci.REQUIRED_GATE_CASES), ci.check_report(xml, name))
        for missing in ci.REQUIRED_GATE_CASES:
            report([case for case in ci.REQUIRED_GATE_CASES if case != missing])
            with self.assertRaises(SystemExit):
                ci.check_report(xml, name)
        for result in ("<skipped/>", "<failure/>", "<error/>"):
            report(ci.REQUIRED_GATE_CASES, result)
            with self.subTest(result=result), self.assertRaises(SystemExit):
                ci.check_report(xml, name)
        xml.unlink()
        with self.assertRaises(FileNotFoundError):
            ci.check_report(xml, name)

    def test_production_python_gate_rejects_missing_or_skipped_first_open(self):
        workflow = (p.ROOT / '.github/workflows/ci.yml').read_text()
        step = workflow.split('      - name: Test production pilot contracts\n', 1)[1].split('      - name:', 1)[0]
        code = textwrap.dedent(step.split("python3 -B - <<'PY'\n", 1)[1].split('          PY\n', 1)[0])
        first = 'test_production_operations.PilotOperationsTest.test_disaster_acceptance_is_bound_to_reviewed_database_and_first_open'
        encrypted = 'test_production_media_backup.ProductionMediaBackupTest.test_authenticated_recovery_and_incomplete_ciphertext'
        scheduled = (
            'test_production_backup_points.ScheduledPointsTest.test_same_slot_retries_do_not_take_a_second_snapshot',
            'test_production_backup_points.ScheduledPointsTest.test_transfer_checks_ciphertext_and_recovers_interrupted_receipt',
        )
        def synthetic(name, skip=False):
            def run():
                if skip:
                    raise unittest.SkipTest('synthetic skip')
            case = unittest.FunctionTestCase(run)
            case.id = lambda: name
            return case
        required = (first, encrypted, *scheduled, *('test_production_operations.PilotOperationsTest.' + name for name in (
            'test_open_requires_same_final_process_ready_and_polling_before_public_marker',
            'test_killed_normal_open_leaves_only_a_probe_permission',
            'test_recorded_telegram_backlog_requires_owner_review_even_after_expiry',
            'test_deferred_telegram_open_preserves_backlog_and_checks_real_poller_and_edge')))
        for case in required:
            for fault in ('missing', 'skipped', None):
                cases = [synthetic(name, name == case and fault == 'skipped') for name in required
                         if name != case or fault != 'missing']
                with self.subTest(case=case, fault=fault), patch.object(unittest.defaultTestLoader, 'discover', return_value=unittest.TestSuite(cases)), contextlib.redirect_stderr(io.StringIO()):
                    if fault:
                        with self.assertRaises(SystemExit):
                            exec(code, {})
                    else:
                        exec(code, {})

    def test_runtime_template_is_inert_and_schema_exact(self):
        template = (p.PROFILE / "runtime.env.example").read_text()
        self.assertEqual({s.partition("=")[0] for s in template.splitlines() if s and not s.startswith("#")}, p.KEYS | p.MEDIA_KEYS)
        self.path.write_text(template)
        with self.assertRaises(p.Refused):
            p.runtime(self.path)

    def test_flags_credentials_private_file_and_image(self):
        self.assertEqual(p.runtime(self.path), self.values)
        for bad in [dict(POSTGRES_IMAGE="postgres:17"), dict(API_SESSION_JWT_SECRET="local-dev-jwt-secret"),
                    dict(DB_PASSWORD=self.values["API_SESSION_JWT_SECRET"]), dict(TELEGRAM_BOT_TOKEN="staging"),
                    dict(TELEGRAM_BOT_ENABLED="yes"), dict(TELEGRAM_BOT_ENABLED="true"),
                    dict(TELEGRAM_MAIN_MINI_APP_CONFIGURED="true"), dict(STAFF_PHOTOS_R2_ENABLED="true"),
                    dict(DB_JDBC_URL="jdbc:postgresql://staging/db")]:
            with self.subTest(keys=list(bad)), self.assertRaises(p.Refused):
                self.write_values(**bad)
                p.runtime(self.path)
        self.write_values(TELEGRAM_BOT_ENABLED="true", TELEGRAM_BOT_TOKEN="123:" + "x"*35, PLATFORM_OWNER_TELEGRAM_ID="42")
        self.assertEqual(p.runtime(self.path)["TELEGRAM_BOT_ENABLED"], "true")
        self.path.chmod(0o644)
        with self.assertRaises(p.Refused):
            p.runtime(self.path)

    def test_compose_output_is_isolated_from_shell_and_staging(self):
        with patch.dict(os.environ, {"DB_PASSWORD": "ambient", "BACKEND_IMAGE": "other:latest",
                                    "BILLING_PROVIDER": "generic", "BILLING_SUBSCRIPTION_INTERVAL_SECONDS": "1",
                                    "COMPOSE_PROJECT_NAME": "staging"}):
            doc = json.loads(p.render(self.values, self.release))
        self.assertEqual(doc["name"], "hookah_production")
        self.assertEqual(set(doc["services"]), {"backend", "postgres"})
        pg, app = doc["services"]["postgres"], doc["services"]["backend"]
        env = app["environment"]
        self.assertNotIn("ports", pg)
        self.assertEqual(set(pg["networks"]), {"database"})
        self.assertTrue(doc["networks"]["database"]["internal"])
        self.assertEqual(doc["volumes"]["pgdata"]["name"], "hookah_production_pgdata")
        self.assertEqual(app["ports"][0]["host_ip"], "127.0.0.1")
        self.assertEqual(app["image"], self.release["image"])
        self.assertFalse("build" in app or "env_file" in app)
        self.assertTrue(app["read_only"])
        self.assertEqual(app["entrypoint"], ["/bin/sh", "/opt/hookah/traffic-gate.sh", "/run/hookah-ingress/open"])
        mounts = {mount["target"]: mount for mount in app["volumes"]}
        gate = mounts["/run/hookah-ingress"]
        self.assertTrue(gate["read_only"] and mounts["/opt/hookah/traffic-gate.sh"]["read_only"])
        self.assertEqual(gate["source"], "/var/lib/hookah-production/ingress")
        self.assertEqual(env["APP_ENV"], "prod")
        self.assertEqual(env["TELEGRAM_TRAFFIC_POLICY"], "PRODUCT")
        self.assertEqual(env["DB_USER"], "hookah_app")
        self.assertEqual(env["DB_PASSWORD"], self.values["DB_PASSWORD"])
        self.assertNotIn(self.values["POSTGRES_ADMIN_PASSWORD"], env.values())
        self.assertEqual(env["TELEGRAM_WEBAPP_PUBLIC_URL"], "https://hookahtootah.club/miniapp/")
        self.assertEqual(env["TELEGRAM_BOT_USERNAME"], "hookah_tootah_bot")
        self.assertEqual(env["BILLING_PROVIDER"], "fake")
        self.assertEqual(env.get("BILLING_SUBSCRIPTION_INTERVAL_SECONDS"), "0")
        for k in ("VENUE_INFO_MEDIA_R2_ENABLED", "STAFF_PHOTOS_R2_ENABLED", "VENUE_INFO_PDF_WORKER_ENABLED"):
            self.assertEqual(env[k], "false")

    def test_media_plan_admits_only_private_production_namespace_and_same_image_worker(self):
        values = dict(PRODUCTION_MEDIA_ENABLED="true", STAFF_PHOTOS_R2_BUCKET=p.PRODUCTION_BUCKET,
                      STAFF_PHOTOS_R2_REGION="auto", STAFF_PHOTOS_R2_JURISDICTION="eu",
                      STAFF_PHOTOS_R2_ENDPOINT="https://" + "d"*32 + ".eu.r2.cloudflarestorage.com",
                      STAFF_PHOTOS_R2_ACCESS_KEY="a"*32, STAFF_PHOTOS_R2_SECRET_KEY="b"*64)
        for override in (dict(STAFF_PHOTOS_R2_BUCKET="hookah-tootah-media-staging-eu"),
                         dict(STAFF_PHOTOS_R2_ENDPOINT="https://example.r2.dev"),
                         dict(STAFF_PHOTOS_R2_ENDPOINT=values["STAFF_PHOTOS_R2_ENDPOINT"] + "/bucket"),
                         dict(STAFF_PHOTOS_R2_SECRET_KEY=""), dict(STAFF_PHOTOS_R2_JURISDICTION="default"),
                         dict(PRODUCTION_MEDIA_ENABLED="false")):
            self.write_values(**(values | override))
            with self.assertRaises(p.Refused):
                p.runtime(self.path)
        self.write_values(**values)
        doc = json.loads(p.render(p.runtime(self.path), self.release))
        backend, worker = doc["services"]["backend"], doc["services"]["pdf-worker"]
        self.assertEqual(set(doc["services"]), {"backend", "postgres", "pdf-worker"})
        self.assertEqual(worker["image"], backend["image"])
        self.assertEqual(worker["ulimits"]["core"], {"soft": 0, "hard": 0})
        self.path.write_text(json.dumps(doc))
        subprocess.run(["docker", "compose", "--env-file", "/dev/null", "-f", str(self.path), "config", "--quiet"], check=True)
        self.assertEqual(worker["network_mode"], "none")
        self.assertNotIn("environment", worker)
        self.assertTrue(worker["read_only"])
        self.assertEqual(worker["user"], "10001:10001")
        self.assertEqual(int(worker["mem_limit"]), 384*1024**2)
        self.assertEqual(worker["mem_limit"], worker["memswap_limit"])
        self.assertEqual(worker["pids_limit"], 64)
        self.assertEqual(float(worker["cpus"]), 1)
        self.assertNotIn("pdf-worker", backend["depends_on"])
        self.assertTrue(next(v["read_only"] for v in backend["volumes"] if v["target"] == p.PDF_IPC))
        self.assertEqual(backend["environment"]["STAFF_PHOTOS_R2_BUCKET"], p.PRODUCTION_BUCKET)
        self.assertEqual(backend["environment"][p.PDF_FLAG], "true")
        self.assertEqual(doc["volumes"]["pdf_ipc"]["name"], "hookah_production_pdf_ipc")
        self.path.write_text(json.dumps(doc))
        new = copy.deepcopy(doc)
        new["services"]["backend"]["image"] = "ghcr.io/koteev-m/hookah_bot/backend@sha256:" + "e"*64
        with self.assertRaises(p.Refused):
            p.compare_update(self.path, json.dumps(new))
        new["services"]["pdf-worker"]["image"] = new["services"]["backend"]["image"]
        p.compare_update(self.path, json.dumps(new))

    def test_disabled_telegram_is_offline_and_enabled_token_must_match_bot(self):
        with patch("urllib.request.build_opener") as build:
            p.verify_bot(self.values)
            build.assert_not_called()
            enabled = self.values | {"TELEGRAM_BOT_ENABLED": "true", "TELEGRAM_BOT_TOKEN": "123:fixture"}
            for username, valid in (("h_t_stage_bot", False), ("hookah_tootah_bot", True)):
                reply = {"ok": True, "result": {"id": 123, "is_bot": True, "username": username}}
                build.return_value.open.return_value = io.BytesIO(json.dumps(reply).encode())
                if valid:
                    p.verify_bot(enabled)
                else:
                    with self.assertRaises(p.Refused):
                        p.verify_bot(enabled)

    def test_candidate_rejects_tags_and_wrong_repository(self):
        for image in ("ghcr.io/koteev-m/hookah_bot/backend:main", "other@sha256:" + "b" * 64):
            self.path.write_text(json.dumps(dict(self.release, image=image)))
            with self.assertRaises(p.Refused):
                p.candidate(self.path)

    def test_update_preserves_postgres_and_runtime(self):
        doc = json.loads(p.render(self.values, self.release))
        self.path.write_text(json.dumps(doc))
        new = copy.deepcopy(doc)
        new["services"]["backend"]["image"] += "1"
        new["services"]["backend"]["environment"]["APP_VERSION"] = "d"*40
        p.compare_update(self.path, json.dumps(new))
        new["services"]["postgres"]["volumes"] = []
        with self.assertRaises(p.Refused):
            p.compare_update(self.path, json.dumps(new))

    def test_rejects_green_ci_for_other_branch_without_downloading(self):
        run = {"id": 10, "repository": {"full_name": p.REPO}, "head_repository": {"full_name": p.REPO},
               "event": "push", "head_branch": "other", "head_sha": self.release["revision"],
               "status": "completed", "conclusion": "success", "path": ".github/workflows/ci.yml"}
        with patch("subprocess.check_output", return_value=json.dumps(run)), patch("subprocess.run") as download:
            with self.assertRaises(p.Refused):
                p.verify_published(self.release)
            download.assert_not_called()

    def test_release_evidence_binds_live_artifact_to_custody_and_exact_attempt(self):
        run = dict(id=10, repository={"full_name": p.REPO}, head_repository={"full_name": p.REPO},
                   event="push", head_branch="main", head_sha=self.release["revision"], run_attempt=2,
                   status="completed", conclusion="success", path=".github/workflows/ci.yml")
        def download(args, check):
            self.assertIn("ghcr-candidate-" + self.release["revision"] + "-2", args)
            (Path(args[-1]) / "candidate.json").write_text(json.dumps(self.release))
        job = (p.ROOT / ".github/workflows/ci.yml").read_text().split("  publish-ghcr:", 1)[1]
        self.assertLess(job.index("Verify registry image and create candidate"), job.index("Upload immutable candidate"))
        self.assertLess(job.index("Upload immutable candidate"), job.index("Record verified image ${{ steps.build.outputs.digest }}"))
        publisher = {"name": "publish-ghcr", "head_sha": self.release["revision"], "conclusion": "success",
                     "run_id": 10, "run_attempt": 2, "steps": [{"name": "Record verified image sha256:" + "b"*64, "conclusion": "success"}]}
        def api(args):
            return json.dumps([{"jobs": [publisher]}] if "--paginate" in args else run)
        with patch("subprocess.check_output", side_effect=api), patch("subprocess.run", side_effect=download) as fetch:
            receipt = p.verify_published(self.release)
            fetch.assert_called_once()
            fetch.side_effect = AssertionError("expired artifact must not be downloaded")
            self.path.write_text(json.dumps(receipt))
            pin = hashlib.sha256(self.path.read_bytes()).hexdigest()
            self.assertEqual(p.verify_published(self.release, self.path, pin), receipt)
            for changed in (dict(self.release, image=self.release["image"][:-1]+"0"), dict(self.release, ci_run=9)):
                with self.assertRaises(p.Refused):
                    p.verify_published(changed, self.path, pin)
            publisher["steps"][0]["conclusion"] = "skipped"
            with self.assertRaises(p.Refused):
                p.verify_published(self.release, self.path, pin)
            publisher["steps"][0]["conclusion"] = "success"
            run["run_attempt"] += 1
            with self.assertRaises(p.Refused):
                p.verify_published(self.release, self.path, pin)
            run["run_attempt"] -= 1
            self.path.write_text(json.dumps(dict(receipt, attempt=1)))
            with self.assertRaises(p.Refused):
                p.verify_published(self.release, self.path, pin)

    def test_update_rejects_downgrade_and_divergence(self):
        for status, base in (("behind", "a"), ("identical", "a"), ("ahead", "b")):
            record = {"status": status, "merge_base_commit": {"sha": base * 40}}
            with self.subTest(status=status), patch("subprocess.check_output", return_value=json.dumps(record)):
                with self.assertRaises(p.Refused):
                    p.verify_forward("a" * 40, "c" * 40)

    def test_running_platform_manifest_must_belong_to_admitted_index(self):
        ref = self.values["POSTGRES_IMAGE"]
        child = "sha256:" + "d" * 64
        image = {"Id": child, "Descriptor": {"digest": child}, "Os": "linux", "Architecture": "amd64"}
        container = {"Config": {"Image": ref}, "Image": ref.split("@")[1],
                     "ImageManifestDescriptor": {"digest": child}}
        self.assertTrue(p.matches_running_image(container, ref, image))
        container["ImageManifestDescriptor"]["digest"] = "sha256:" + "f" * 64
        self.assertFalse(p.matches_running_image(container, ref, image))

    def test_history_rejects_missing_failed_or_changed_migration(self):
        rows = [dict(row, success=True) for row in p.history_for(p.source_manifest())]
        p.check_history(json.dumps(rows))
        for wrong in (rows[:-1], rows[:-1] + [dict(rows[-1], success=False)],
                      rows[:-1] + [dict(rows[-1], checksum=0)]):
            with self.assertRaises(p.Refused):
                p.check_history(json.dumps(wrong))


if __name__ == "__main__":
    unittest.main()
