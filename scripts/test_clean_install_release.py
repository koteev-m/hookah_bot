"""Synthetic authority/runtime tests, never infrastructure retirement evidence."""

import copy
from pathlib import Path
import tempfile
import time
import unittest
from unittest import mock

import clean_install_policy as clean
import staging_release as release


def fixture():
    scope = {"project_id": "Hookah_Tootah", "origin": clean.ORIGIN, "installation": "a" * 32,
             "incarnation": "b" * 64, "ancestry": "c" * 64,
             "tooling": {"source": "d" * 40, "tree": "e" * 40, "closure": "f" * 64, "runtime": "0" * 64}}
    image = {"id": "sha256:" + "1" * 64, "revision": "2" * 40, "source": release.SOURCE,
             "archive": "3" * 64, "schema": "4" * 64, "platform": "linux/amd64", "manifest": [
                 {"version": n, "script": f"V{n}__fixture.sql", "sha256": "4" * 64, "checksum": n}
                 for n in range(1, 127)]}
    return scope, image


class SyntheticAuthority(clean.Authority):
    """In-memory witness for tests ONLY: does not acquire independent facts.

    Its automatic approvals are deliberate fixtures, unavailable to CLI/worker.
    Production must never instantiate this implementation.
    """

    def __init__(self, scope):
        self.scope = scope
        self.pointer = {"state": None, "pending": None}
        self.events = []
        self.override = {}
        self.fault = None
        self.approved = None

    def head(self, installation, *, timeout):
        release.require(installation == self.scope["installation"], "INSTALLATION_NOT_ENROLLED")
        assert timeout > 0
        return copy.deepcopy(self.pointer)

    def authorize(self, request, *, timeout):
        release.require(request["scope"] == self.scope, "TARGET_NOT_ENROLLED")
        proof = {"request": release.fingerprint(request), "expires": time.time() + 60,
                 "tooling": self.scope["tooling"], "ancestry": self.scope["ancestry"],
                 "historical_run724": "UNKNOWN", "pending_effects": [], "external_writers": [],
                 "facets": {key: {"verdict": "EXCLUDED", "receipt": "5" * 64} for key in clean.FACETS},
                 **{key: "6" * 64 for key in ("owner_approval", "reset", "empty", "loss_scope",
                      "recovery", "ci_receipt", "compatibility", "smoke", "public_handoff")},
                 "ci_revision": request["plan"].get("image", {}).get("revision")}
        proof["compatibility_verdict"] = ("COMPATIBLE_FORWARD" if request["plan"]["kind"] == "FORWARD"
                                           else "CODE_ONLY_SAME_SCHEMA")
        proof.update(copy.deepcopy(self.override))
        self.approved = release.fingerprint(request), release.fingerprint(proof)
        return proof

    def begin(self, request, evidence, expected_head, *, timeout):
        release.require(self.approved == (release.fingerprint(request), release.fingerprint(evidence)),
                        "UNENROLLED_REQUEST")
        release.require(self.pointer == {"state": expected_head, "pending": None}, "CAS_CONFLICT")
        token = release.fingerprint({"request": request, "sequence": len(self.events)})
        self.pointer["pending"] = token
        self.events.append({"token": token, "request": copy.deepcopy(request),
                            "evidence": copy.deepcopy(evidence), "outcome": "PENDING"})
        self.approved = None
        if self.fault == "begin_ack":
            raise TimeoutError()
        return token

    def finish(self, token, terminal_state, *, timeout):
        assert self.pointer["pending"] == token
        self.events[-1]["outcome"] = terminal_state["status"]
        self.events[-1]["terminal"] = copy.deepcopy(terminal_state)
        self.pointer = {"state": release.fingerprint(terminal_state), "pending": None}
        if self.fault == "finish_ack":
            raise TimeoutError()
        return "COMPLETED"


class SyntheticRuntime:
    def __init__(self, scope, image):
        self.scope = scope
        self.images = {image["id"]: copy.deepcopy(image)}
        self.snapshot = {"installation": scope["installation"], "incarnation": scope["incarnation"],
                         "database": {"system_id": "123", "database": "fixture", "role": "fixture",
                                      "volume": "fixture", "network": "fixture"},
                         "configuration": "7" * 64, "traffic": "CLOSED", "image": None,
                         "history": [], "schema": "0" * 64, "user_objects": [],
                         "unidentified_writers": [], "backend_count": 0}
        self.plans = []
        self.ready = True
        self.fault = None

    def clean_tooling(self, *, timeout):
        return copy.deepcopy(self.scope["tooling"])

    def clean_image(self, candidate, *, timeout):
        return copy.deepcopy(self.images[candidate["id"]])

    def clean_observe(self, *, timeout):
        return copy.deepcopy(self.snapshot)

    def clean_execute(self, plan, before, *, timeout):
        self.plans.append(copy.deepcopy(plan))
        if "image" in plan:
            self.snapshot.update(image=plan["image"]["id"], backend_count=1,
                                 schema=plan["image"]["schema"],
                                 history=clean.history(plan["image"]["manifest"]), user_objects=["fixture"])
        if "configuration" in plan:
            self.snapshot["configuration"] = plan["configuration"]
            self.snapshot["traffic"] = {"TEST": "SMOKE", "PUBLIC": "PUBLIC"}[plan["kind"]]
        if self.fault == "lost_ack":
            raise TimeoutError()
        if self.fault == "failed_history":
            self.snapshot["history"][-1]["success"] = False
        return "COMPLETED"

    def clean_ready(self, image, *, timeout):
        return self.ready


class CleanReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.scope, self.image = fixture()
        self.runtime = SyntheticRuntime(self.scope, self.image)
        self.authority = SyntheticAuthority(self.scope)
        self.store = release.Store(Path(self.temp.name) / "state", migration_target=self.temp.name)
        self.controller = release.Controller.clean_installation(self.scope, self.store, self.runtime, self.authority)

    def bootstrap(self):
        return self.controller.clean_action("BOOTSTRAP", candidate=self.image)

    def admitted(self):
        self.assertEqual(self.bootstrap(), "CLOSED_V126")
        self.assertEqual(self.controller.clean_action("TEST", configuration="8" * 64), "TEST_ADMITTED")
        self.assertEqual(self.controller.accept(smoke_passed=True), "AWAITING_PUBLIC_HANDOFF")
        self.assertEqual(self.controller.clean_action("PUBLIC", configuration="9" * 64), "ACCEPTED")

    def next_image(self, forward=False):
        image = copy.deepcopy(self.image)
        image.update(id="sha256:" + "a" * 64, revision="b" * 40, archive="c" * 64)
        if forward:
            image["manifest"].append({"version": 127, "script": "V127__fixture.sql", "sha256": "d" * 64,
                                      "checksum": -123})
        self.runtime.images[image["id"]] = copy.deepcopy(image)
        return image

    def deploy(self, image):
        return self.controller.deploy(image, expected_current=self.image["id"], owner_approved=True)

    def refused(self, code, action):
        with self.assertRaisesRegex(release.Refused, "^" + code + "$"):
            action()

    def test_empty_bootstrap_and_repeat_never_dispatches(self):
        self.assertEqual(self.bootstrap(), "CLOSED_V126")
        self.assertEqual(len(self.runtime.snapshot["history"]), 126)
        self.assertEqual(self.runtime.snapshot["traffic"], "CLOSED")
        self.refused("INSTALLATION_ALREADY_EXISTS", self.bootstrap)
        self.assertEqual(len(self.runtime.plans), 1)
        self.assertEqual(self.authority.events[0]["outcome"], "CLOSED_V126")

    def test_state_deletion_cannot_rebootstrap(self):
        self.bootstrap()
        self.store.path.unlink()
        self.refused("INDEPENDENT_HISTORY_MISMATCH", self.bootstrap)
        self.assertEqual(len(self.runtime.plans), 1)

    def test_app_release_accept_and_same_schema_rollback(self):
        self.admitted()
        candidate = self.next_image()
        self.assertEqual(self.deploy(candidate), "READY_FOR_SMOKE")
        self.assertEqual(self.controller.accept(smoke_passed=True), "ACCEPTED")
        self.assertEqual(self.controller.rollback(owner_approved=True), "READY_FOR_SMOKE")
        self.assertEqual(self.runtime.snapshot["image"], self.image["id"])

    def test_forward_then_unsafe_rollback_refused(self):
        self.admitted()
        self.assertEqual(self.deploy(self.next_image(True)), "READY_FOR_SMOKE")
        self.controller.accept(smoke_passed=True)
        self.refused("SCHEMA_INCOMPATIBLE_USE_FORWARD_FIX", lambda: self.controller.rollback(owner_approved=True))
        self.assertEqual(len(self.runtime.snapshot["history"]), 127)

    def test_no_ordinary_before_public_handoff(self):
        self.bootstrap()
        self.refused("INSTALLATION_NOT_ACCEPTED", lambda: self.deploy(self.next_image()))

    def test_public_requires_exact_separate_authority(self):
        self.bootstrap()
        self.controller.clean_action("TEST", configuration="8" * 64)
        self.controller.accept(smoke_passed=True)
        self.authority.override["public_handoff"] = None
        self.refused("PUBLIC_HANDOFF_REQUIRED", lambda: self.controller.clean_action("PUBLIC", configuration="9" * 64))
        self.assertEqual(self.runtime.snapshot["traffic"], "SMOKE")

    def test_incomplete_reset_and_external_effects_refuse_before_dispatch(self):
        for override, reason in [({"reset": None}, "RESET_EMPTY_AUTHORITY_REQUIRED"),
                                 ({"facets": {}}, "CESSATION_EVIDENCE_INCOMPLETE"),
                                 ({"pending_effects": ["unknown"]}, "LEGACY_EFFECTS_UNRESOLVED"),
                                 ({"external_writers": ["old_root"]}, "LEGACY_EFFECTS_UNRESOLVED"),
                                 ({"historical_run724": "SUCCESS"}, "AUTHORITY_IDENTITY_MISMATCH"),
                                 ({"expires": 0}, "AUTHORITY_EXPIRED")]:
            with self.subTest(reason=reason):
                self.authority.override = override
                self.refused(reason, self.bootstrap)
        self.assertEqual(self.runtime.plans, [])

    def test_nonempty_database_refused(self):
        self.runtime.snapshot["user_objects"] = ["old_table"]
        self.refused("EMPTY_DATABASE_REQUIRED", self.bootstrap)

    def test_external_writer_refused(self):
        self.runtime.snapshot["unidentified_writers"] = ["external"]
        self.refused("UNEXCLUDED_WRITER", self.bootstrap)

    def test_concurrent_controller_refused(self):
        other = release.Store(self.store.directory, migration_target=self.temp.name)
        with other.locked():
            self.refused("DEPLOYMENT_BUSY", self.bootstrap)

    def test_old_registry_is_not_clean_authority(self):
        (Path(self.temp.name) / ".v126-target-operations").mkdir()
        self.refused("MIGRATION_TARGET_REQUIRES_SEQUENCER", self.bootstrap)

    def test_image_source_archive_and_manifest_substitution(self):
        for field, value in [("id", "sha256:" + "f" * 64), ("revision", "f" * 40),
                             ("archive", "f" * 64), ("source", "https://example.invalid")]:
            with self.subTest(field=field):
                original = self.runtime.images[self.image["id"]]
                altered = copy.deepcopy(original)
                altered[field] = value
                self.runtime.images[self.image["id"]] = altered
                self.refused("IMAGE_IDENTITY_CHANGED", self.bootstrap)
                self.runtime.images[self.image["id"]] = original
        self.image["manifest"][0]["checksum"] += 1
        self.refused("IMAGE_IDENTITY_CHANGED", self.bootstrap)

    def test_tool_substitution_refused(self):
        self.runtime.scope = copy.deepcopy(self.scope)
        self.runtime.scope["tooling"]["closure"] = "1" * 64
        self.refused("TOOLING_IDENTITY_CHANGED", self.bootstrap)

    def test_ddl_drift_without_history_change_is_refused(self):
        self.admitted()
        self.runtime.snapshot["schema"] = "1" * 64
        self.refused("ACCEPTED_STATE_DRIFT", lambda: self.deploy(self.next_image()))

    def test_terminal_evidence_is_retained_off_target(self):
        self.bootstrap()
        retained = copy.deepcopy(self.authority.events[-1]["terminal"])
        self.assertEqual(retained, self.store.read())
        self.store.path.unlink()
        self.assertEqual(retained["observed"]["schema"], self.image["schema"])
        self.assertEqual(set(self.authority.events[-1]["evidence"]["facets"]), clean.FACETS)
        self.refused("INDEPENDENT_HISTORY_MISMATCH", self.bootstrap)

    def test_new_installation_uuid_cannot_reuse_target_authority(self):
        self.bootstrap()
        changed = copy.deepcopy(self.scope)
        changed["installation"] = "b" * 32
        controller = release.Controller.clean_installation(changed, self.store, self.runtime, self.authority)
        self.refused("INSTALLATION_NOT_ENROLLED", lambda: controller.clean_action("BOOTSTRAP", candidate=self.image))

    def test_local_intent_write_failure_leaves_external_reservation(self):
        with mock.patch.object(self.store, "write", side_effect=OSError("synthetic fsync failure")):
            self.refused("CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED", self.bootstrap)
        self.assertEqual(self.runtime.plans, [])
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", self.bootstrap)

    def test_writer_appears_during_evidence_acquisition(self):
        authorize = self.authority.authorize
        def racing_authority(request, *, timeout):
            result = authorize(request, timeout=timeout)
            self.runtime.snapshot["unidentified_writers"] = ["late_writer"]
            return result
        self.authority.authorize = racing_authority
        self.refused("PRE_DISPATCH_DRIFT", self.bootstrap)
        self.assertEqual(self.runtime.plans, [])
        self.assertEqual(self.authority.events, [])

    def test_public_partial_result_blocks_ordinary(self):
        self.bootstrap()
        self.controller.clean_action("TEST", configuration="8" * 64)
        self.controller.accept(smoke_passed=True)
        self.runtime.fault = "lost_ack"
        self.refused("CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED",
                     lambda: self.controller.clean_action("PUBLIC", configuration="9" * 64))
        self.assertEqual(self.store.read()["status"], "UNKNOWN")
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", lambda: self.deploy(self.next_image()))

    def test_existing_standalone_worker_has_no_new_import_dependency(self):
        import subprocess
        import sys
        standalone = Path(self.temp.name) / "staging_release.py"
        standalone.write_bytes(Path(release.__file__).read_bytes())
        result = subprocess.run([sys.executable, "-I", "-B", str(standalone), "--help"], capture_output=True)
        self.assertEqual(result.returncode, 0)

    def test_backup_missing_and_incompatible_review_refuse(self):
        self.admitted()
        candidate = self.next_image(True)
        self.authority.override["recovery"] = None
        self.refused("RECOVERY_READINESS_REQUIRED", lambda: self.deploy(candidate))
        self.authority.override = {"compatibility": None}
        self.refused("REVIEWED_COMPATIBILITY_REQUIRED", lambda: self.deploy(candidate))
        self.authority.override = {"compatibility_verdict": "DESTRUCTIVE"}
        self.refused("REVIEWED_COMPATIBILITY_REQUIRED", lambda: self.deploy(candidate))
        self.authority.override = {"ci_revision": "f" * 40}
        self.refused("EXACT_IMAGE_CI_REQUIRED", lambda: self.deploy(candidate))
        self.assertEqual(len(self.runtime.plans), 4)

    def test_historical_sql_rewrite_refused(self):
        self.admitted()
        candidate = self.next_image(True)
        candidate["manifest"][0]["sha256"] = "f" * 64
        self.runtime.images[candidate["id"]] = copy.deepcopy(candidate)
        self.refused("APPEND_ONLY_MIGRATIONS_REQUIRED", lambda: self.deploy(candidate))

    def test_schema_history_tampering_refused(self):
        self.admitted()
        self.runtime.snapshot["history"][0]["checksum"] += 1
        self.refused("ACCEPTED_STATE_DRIFT", lambda: self.deploy(self.next_image()))

    def test_lost_execution_ack_is_unknown_and_retry_blocked(self):
        self.runtime.fault = "lost_ack"
        self.refused("CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED", self.bootstrap)
        self.assertEqual(self.store.read()["status"], "UNKNOWN")
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", self.bootstrap)
        self.assertEqual(len(self.runtime.plans), 1)

    def test_failed_flyway_poststate_is_unknown(self):
        self.runtime.fault = "failed_history"
        self.refused("CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED", self.bootstrap)
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", self.bootstrap)

    def test_known_readiness_failure_is_failed_and_retry_blocked(self):
        self.runtime.ready = False
        self.refused("READINESS_FAILED_RECONCILIATION_REQUIRED", self.bootstrap)
        self.assertEqual(self.store.read()["status"], "FAILED")
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", self.bootstrap)

    def test_lost_begin_ack_reserves_installation_without_runtime_effect(self):
        self.authority.fault = "begin_ack"
        self.refused("AUTHORITY_INTENT_OUTCOME_UNKNOWN", self.bootstrap)
        self.assertEqual(self.runtime.plans, [])
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", self.bootstrap)

    def test_lost_finish_ack_cannot_be_success_or_replay(self):
        self.authority.fault = "finish_ack"
        self.refused("CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED", self.bootstrap)
        self.refused("INDEPENDENT_HISTORY_MISMATCH", self.bootstrap)

    def test_legacy_v126_boolean_does_not_unlock_live_worker(self):
        from test_staging_release import fixture as legacy_fixture
        scope = legacy_fixture()["scope"]
        scope.update(postgresql_head=126, origin=clean.ORIGIN, transition_approved=True)
        self.refused("V126_ORDINARY_AUTHORITY_TRANSFER_REQUIRED", lambda: release.validate_scope(scope))


if __name__ == "__main__":
    unittest.main()
