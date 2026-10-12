"""Offline synthetic assessment tests; no target, Docker, SQL or authority producer."""

import copy
import hashlib
import importlib.util
import json
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_v125_lifecycle_validator import example
from v125_lifecycle_validator import inspect_initial_install


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("install_archive_fixture", ROOT / "scripts/docker-save-archive.py")
ARCHIVE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ARCHIVE)


class InitialInstallTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.archive = Path(self.directory.name) / "backend.tar"
        self.proposal, self.scope = example()
        self.scope["release"]["source_commit"] = "a" * 40
        for role in ("backend", "frontend"):
            self.scope["release"][role]["source_commit"] = "a" * 40
        self.tag = "fixture.invalid/backend:" + self.scope["release"]["source_commit"]
        self.migrations = {f"V{i}__fixture.sql": b"-- synthetic migration\n" for i in range(1, 126)}
        self.config = json.dumps(self.proposal["configuration"], sort_keys=True).encode()
        self.inputs = {
            "evidence_kind": "EMPTY_TARGET", "database": "ABSENT", "schema_version": None,
            "outcome": "NOT_STARTED", "credential_import": "NONE", "postgresql_version": "17.10",
            "postgresql_image": "docker.io/library/postgres@sha256:" + "8" * 64,
            "expected_postgresql_image": "docker.io/library/postgres@sha256:" + "8" * 64,
            "postgresql_platform": "linux/amd64", "migration_files": self.migrations,
            "configuration_bytes": self.config, "backend_archive": self.archive, "backend_tag": self.tag,
        }
        self.pin_migrations()
        self.set_image("valid")
        self.scope["release"]["config_sha256"] = hashlib.sha256(self.config).hexdigest()
        self.sync_proposal()

    def pin_migrations(self):
        inventory = {name: hashlib.sha256(raw).hexdigest() for name, raw in self.migrations.items()}
        raw = (json.dumps(inventory, sort_keys=True, separators=(",", ":")) + "\n").encode()
        self.scope["release"]["migration_manifest_sha256"] = hashlib.sha256(raw).hexdigest()

    def set_image(self, mode):
        _, image_id = ARCHIVE.build_fixture(self.archive, mode, tag=self.tag,
                                          revision=self.scope["release"]["source_commit"],
                                          source=ARCHIVE.EXPECTED_SOURCE)
        self.scope["release"]["backend"]["digest"] = image_id.removeprefix("sha256:")

    def sync_proposal(self):
        self.proposal["release"] = copy.deepcopy(self.scope["release"])
        for ref in self.proposal["prerequisites"].values():
            ref["config_sha256"] = self.scope["release"]["config_sha256"]
            ref["source_commit"] = self.scope["release"]["source_commit"]

    def assess(self, valid=False, state="UNKNOWN", error=None):
        before = copy.deepcopy((self.proposal, self.scope, self.inputs))
        result = inspect_initial_install(json.dumps(self.proposal), json.dumps(self.scope), **self.inputs)
        self.assertEqual(result["valid"], valid, result)
        self.assertEqual(result["candidate_state"], state)
        self.assertEqual(result["verified_state"], "UNKNOWN")
        self.assertEqual(result["status"], "PROPOSAL_ONLY")
        self.assertIs(result["execution_authorized"], False)
        self.assertIs(result["operationally_qualified"], False)
        self.assertEqual(result["execution_blockers"], ["INDEPENDENT_INITIAL_INSTALL_AUTHORITY_UNAVAILABLE"])
        self.assertEqual((self.proposal, self.scope, self.inputs), before)
        if error:
            self.assertEqual(result["errors"], [error])
        return result

    def test_empty_prepared_and_installed_are_distinct_unqualified_candidates(self):
        for kind, database, version, outcome, state in [
            ("EMPTY_TARGET", "ABSENT", None, "NOT_STARTED", "EMPTY"),
            ("DATABASE", "PRESENT", 0, "PREPARED", "PREPARED_EMPTY_POSTGRESQL"),
            ("DATABASE", "PRESENT", 125, "INSTALLED", "INSTALLED_CLOSED_V125"),
        ]:
            with self.subTest(state=state):
                self.inputs.update(evidence_kind=kind, database=database, schema_version=version, outcome=outcome)
                self.assess(valid=True, state=state)

    def test_existing_database_or_wrong_empty_evidence_is_unknown(self):
        for change in [dict(evidence_kind="DATABASE"), dict(database="PRESENT"),
                       dict(schema_version=125), dict(evidence_kind="R0"), dict(evidence_kind=None)]:
            original = self.inputs.copy()
            self.inputs.update(change)
            self.assess(error="UNKNOWN_OR_CONTRADICTORY_INSTALLATION")
            self.inputs = original

    def test_imported_database_and_credentials_refuse(self):
        self.proposal["configuration"]["data_import"] = "OLD_STAGING"
        self.assess(error="DISCONNECTED_CONFIGURATION_REQUIRED")
        self.proposal["configuration"]["data_import"] = "NONE"
        for source in ("OLD_STAGING", "PRODUCTION", "IMPORTED", None):
            self.inputs["credential_import"] = source
            self.assess(error="CREDENTIAL_IMPORT_FORBIDDEN")

    def test_old_target_and_epoch_even_with_matching_scope_refuse(self):
        for key in ("resource_id", "control_domain_id", "epoch_id"):
            original = copy.deepcopy((self.proposal, self.scope))
            for doc in (self.proposal, self.scope):
                doc["target"][key] = self.scope["excluded_targets"]["old_staging"][key]
            self.assess(error="EXCLUDED_TARGET_REUSE")
            self.proposal, self.scope = original

    def test_missing_and_self_asserted_authority_cannot_enable_execution(self):
        self.assess(valid=True, state="EMPTY")
        self.proposal["prerequisites"]["installation_approval"]["approved"] = True
        self.assess(error="INVALID_FIELDS")
        del self.proposal["prerequisites"]["installation_approval"]
        self.assess(error="INVALID_FIELDS")

    def test_baseline_readiness_proposal_is_not_initial_install(self):
        self.proposal, self.scope = example(True)
        self.assess(error="INITIAL_INSTALL_PROPOSAL_REQUIRED")

    def test_fake_r0_cannot_bootstrap_empty(self):
        self.proposal["current"]["recovery_point_sha256"] = "6" * 64
        self.assess(error="EMPTY_DATABASE_CONFLICT")

    def test_postgresql_identity_version_and_platform_mismatches(self):
        for key, value, error in [
            ("postgresql_image", "docker.io/library/postgres@sha256:" + "9" * 64, "POSTGRESQL_IMAGE_MISMATCH"),
            ("expected_postgresql_image", "postgres:17.10", "EXACT_POSTGRESQL_IMAGE_REQUIRED"),
            ("expected_postgresql_image", "sha256:" + "8" * 64, "EXACT_POSTGRESQL_IMAGE_REQUIRED"),
            ("postgresql_platform", "linux/arm64", "POSTGRESQL_PLATFORM_MISMATCH"),
            ("postgresql_version", "17.9", "POSTGRESQL_17_10_REQUIRED"),
        ]:
            with self.subTest(key=key):
                original = self.inputs[key]
                self.inputs[key] = value
                self.assess(error=error)
                self.inputs[key] = original

    def test_archive_bytes_platform_source_and_layers_are_checked(self):
        for mode in ("wrong-platform", "wrong-revision", "wrong-source", "diffid-mismatch"):
            with self.subTest(mode=mode):
                self.set_image(mode)
                self.sync_proposal()
                self.assess(error="BACKEND_ARCHIVE_MISMATCH")

    def test_image_digest_mismatch_and_missing_archive_refuse(self):
        self.scope["release"]["backend"]["digest"] = "9" * 64
        self.sync_proposal()
        self.assess(error="BACKEND_ARCHIVE_MISMATCH")
        self.archive.unlink()
        self.assess(error="BACKEND_ARCHIVE_MISMATCH")

    def test_malformed_backend_tag_is_a_fixed_refusal(self):
        for tag in (None, True, 123, {}, [], "backend:latest", "secret-sentinel"):
            with self.subTest(tag=tag):
                self.inputs["backend_tag"] = tag
                result = self.assess(error="INVALID_BACKEND_TAG")
                self.assertNotIn("secret-sentinel", json.dumps(result))

    def test_changed_configuration_bytes_refuse(self):
        self.inputs["configuration_bytes"] += b" "
        self.assess(error="CONFIGURATION_BYTES_MISMATCH")

    def test_repinned_connected_open_imported_or_malformed_config_refuses(self):
        for key, value in [("telegram", "CONNECTED"), ("user_access", "OPEN"),
                           ("data_import", "OLD_STAGING"), ("control_import", "OLD_STAGING"),
                           ("approved", True)]:
            with self.subTest(key=key):
                config = dict(self.proposal["configuration"], **{key: value})
                self.inputs["configuration_bytes"] = json.dumps(config).encode()
                self.scope["release"]["config_sha256"] = hashlib.sha256(self.inputs["configuration_bytes"]).hexdigest()
                self.sync_proposal()
                self.assess(error="CONFIGURATION_CONTENT_MISMATCH")
        for raw in (b"{", b"null", b'{"telegram":"CONNECTED","telegram":"DISCONNECTED"}'):
            self.inputs["configuration_bytes"] = raw
            self.scope["release"]["config_sha256"] = hashlib.sha256(raw).hexdigest()
            self.sync_proposal()
            self.assess()

    def test_migration_content_is_hashed_not_just_declared(self):
        self.migrations["V125__fixture.sql"] += b"-- changed\n"
        self.assess(error="MIGRATION_BYTES_MISMATCH")

    def test_v126_missing_duplicate_and_non_sql_inventory_refuse_even_if_repinned(self):
        for remove, name in [(None, "V126__forbidden.sql"), ("V125__fixture.sql", None),
                             ("V124__fixture.sql", "V125__duplicate.sql"),
                             ("V124__fixture.sql", "../V124__escape.sql")]:
            with self.subTest(name=name):
                original = self.migrations.copy()
                if remove:
                    del self.migrations[remove]
                if name:
                    self.migrations[name] = b"-- synthetic\n"
                self.pin_migrations()
                self.sync_proposal()
                self.assess()
                self.migrations.clear()
                self.migrations.update(original)

    def test_real_local_postgresql_inventory_v125_and_v126_boundary(self):
        root = ROOT / "backend/app/src/main/resources/db/migration/postgresql"
        self.migrations.clear()
        self.migrations.update({p.name: p.read_bytes() for p in root.glob("*.sql")
                                if int(p.name.split("__")[0][1:]) <= 125})
        self.pin_migrations()
        self.sync_proposal()
        self.assess(valid=True, state="EMPTY")
        v126 = root / "V126__support_thread_read_message_cursor.sql"
        self.migrations[v126.name] = v126.read_bytes()
        self.pin_migrations()
        self.sync_proposal()
        self.assess(error="V125_MIGRATION_INVENTORY_REQUIRED")

    def test_reused_operation_and_inconsistent_predecessor_refuse(self):
        self.scope["used_operation_ids"].append(self.proposal["operation_id"])
        self.assess(error="OPERATION_ID_REUSED")
        self.scope["used_operation_ids"].pop()
        self.proposal["prerequisites"]["predecessor_review"]["evidence_sha256"] = "6" * 64
        self.assess(error="PREDECESSOR_EVIDENCE_MISMATCH")

    def test_partial_unknown_timeout_and_schema_contradictions_stay_unknown(self):
        for outcome in ("PARTIAL", "UNKNOWN", "STARTED", "FAILED", "TIMEOUT", "INSTALLED", None):
            self.inputs["outcome"] = outcome
            self.assess(error="UNKNOWN_OR_CONTRADICTORY_INSTALLATION")
        self.inputs.update(evidence_kind="DATABASE", database="PRESENT", outcome="INSTALLED")
        for version in (126, 124, 125.0, True, "125", None):
            self.inputs["schema_version"] = version
            self.assess(error="UNKNOWN_OR_CONTRADICTORY_INSTALLATION")

    def test_telegram_cannot_open_before_or_after_claimed_installation(self):
        for outcome in ("NOT_STARTED", "INSTALLED"):
            self.inputs["outcome"] = outcome
            self.proposal["configuration"]["telegram"] = "CONNECTED"
            self.assess(error="DISCONNECTED_CONFIGURATION_REQUIRED")

    def test_no_process_network_or_file_mutation_and_no_secret_diagnostics(self):
        before = self.archive.read_bytes()
        with patch.object(subprocess, "Popen", side_effect=AssertionError("process forbidden")), \
                patch.object(socket, "socket", side_effect=AssertionError("network forbidden")):
            self.assess(valid=True, state="EMPTY")
        self.assertEqual(self.archive.read_bytes(), before)
        self.assertEqual(list(self.archive.parent.iterdir()), [self.archive])
        self.inputs["postgresql_image"] = "secret-sentinel"
        self.assertNotIn("secret-sentinel", json.dumps(self.assess()))

    def test_cli_has_no_execute_mode(self):
        result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/v125_lifecycle_validator.py"),
                                 "--scope", "unused", "--execute"], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
