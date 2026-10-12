"""Synthetic proposal tests. No infrastructure, credentials or operational receipts."""

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from v125_lifecycle_validator import validate_json


def example(baseline=False):
    """Public executable example; hashes and identities are synthetic placeholders."""
    target = {
        "target_id": "ht-v125-isolated-example", "resource_id": "new-example-resource",
        "control_domain_id": "new-example-domain", "epoch_id": "a" * 64,
        "frontend_origin": "https://new.example.invalid",
    }
    release = {
        "source_commit": "b" * 40, "postgresql_schema_version": 125,
        "backend": {"source_commit": "b" * 40, "postgresql_schema_version": 125, "digest": "c" * 64},
        "frontend": {"source_commit": "b" * 40, "postgresql_schema_version": 125, "digest": "d" * 64,
                     "origin": target["frontend_origin"]},
        "migration_manifest_sha256": "e" * 64, "config_sha256": "f" * 64,
    }
    scope = {
        "format_version": 1, "project_id": "Hookah_Tootah", "lifecycle": "V125_ISOLATED_TEST_LIFECYCLE",
        "target": target, "release": release, "predecessor_inventory_sha256": "1" * 64,
        "used_operation_ids": ["ht-v125-proposal-used"],
        "excluded_targets": {
            "old_staging": {"target_id": "old-staging", "resource_id": "old-staging-resource",
                            "control_domain_id": "old-staging-domain", "epoch_id": "2" * 64,
                            "frontend_origin": "https://old.example.invalid"},
            "production": {"target_id": "production", "resource_id": "production-resource",
                           "control_domain_id": "production-domain", "epoch_id": "3" * 64,
                           "frontend_origin": "https://production.example.invalid"},
        },
    }
    proposal = copy.deepcopy({key: scope[key] for key in (
        "format_version", "project_id", "lifecycle", "target", "release", "predecessor_inventory_sha256"
    )})
    recovery = "4" * 64 if baseline else None
    names = ["isolation", "predecessor_review", "source_review", "configuration_review"]
    names += (["baseline_installation", "policy_b", "recovery_custody", "testing_approval"] if baseline
              else ["empty_target", "installation_approval"])
    proposal.update({
        "mode": "PROPOSAL_ONLY", "execution": "NOT_EXECUTED", "operation_id": "ht-v125-proposal-example",
        "current": {
            "state": "V125_BASELINE_INSTALLED" if baseline else "EMPTY_CONFIRMED",
            "database": "PRESENT" if baseline else "ABSENT", "postgresql_schema_version": 125 if baseline else None,
            "recovery_point_sha256": recovery, "state_evidence_sha256": "5" * 64,
        },
        "proposed_state": "TEST_READY_CONFIRMED" if baseline else "V125_BASELINE_INSTALLED",
        "configuration": {"frontend_origin": target["frontend_origin"], "telegram": "DISCONNECTED",
                          "user_access": "CLOSED", "control_import": "NONE", "data_import": "NONE"},
        "prerequisites": {
            name: {"status": "EVIDENCE_REFERENCED", "target_id": target["target_id"],
                   "epoch_id": target["epoch_id"], "source_commit": release["source_commit"],
                   "config_sha256": release["config_sha256"], "recovery_point_sha256": recovery,
                   "evidence_sha256": "1" * 64 if name == "predecessor_review" else "5" * 64}
            for name in names
        },
    })
    return proposal, scope


class ValidatorTest(unittest.TestCase):
    def outcome(self, proposal, scope, valid=False, error=None):
        output = validate_json(json.dumps(proposal), json.dumps(scope))
        self.assertEqual(output["valid"], valid, output)
        self.assertEqual(output["status"], "PROPOSAL_ONLY")
        self.assertIs(output["execution_authorized"], False)
        self.assertIs(output["operationally_qualified"], False)
        self.assertEqual(set(output), {"valid", "status", "execution_authorized", "operationally_qualified", "errors"})
        if error:
            self.assertEqual(output["errors"], [error])
        return output

    def test_empty_target_proposal_and_no_input_mutation(self):
        proposal, scope = example()
        before = copy.deepcopy((proposal, scope))
        self.outcome(proposal, scope, valid=True)
        self.assertEqual((proposal, scope), before)

    def test_baseline_to_test_ready_remains_only_proposal(self):
        self.outcome(*example(baseline=True), valid=True)

    def test_every_old_target_facet_is_excluded_even_if_proposal_matches_scope(self):
        for name in ("old_staging", "production"):
            for key in example()[1]["target"]:
                with self.subTest(name=name, key=key):
                    proposal, scope = example()
                    value = scope["excluded_targets"][name][key]
                    proposal["target"][key] = scope["target"][key] = value
                    if key == "frontend_origin":
                        for document in (proposal, scope):
                            document["release"]["frontend"]["origin"] = value
                    self.outcome(proposal, scope)

    def test_wrong_target_epoch_and_artifact_pins(self):
        for path, value in [
            (("target", "target_id"), "ht-v125-isolated-other"),
            (("target", "epoch_id"), "6" * 64),
            (("target", "resource_id"), "another-resource"),
            (("release", "backend", "digest"), "6" * 64),
            (("release", "frontend", "digest"), "7" * 64),
            (("release", "migration_manifest_sha256"), "8" * 64),
            (("release", "config_sha256"), "9" * 64),
        ]:
            with self.subTest(path=path):
                proposal, scope = example()
                node = proposal
                for key in path[:-1]:
                    node = node[key]
                node[path[-1]] = value
                self.outcome(proposal, scope, error="SCOPE_BINDING_MISMATCH")

    def test_invalid_versions_even_when_scope_is_changed(self):
        for version in (126, 124, "125", True, 125.0, None):
            for image in (None, "backend", "frontend"):
                with self.subTest(version=version, image=image):
                    proposal, scope = example()
                    for document in (proposal, scope):
                        node = document["release"] if image is None else document["release"][image]
                        node["postgresql_schema_version"] = version
                    self.outcome(proposal, scope)

    def test_image_source_and_frontend_origin_mismatch(self):
        for role, key, value in [("backend", "source_commit", "6" * 40),
                                 ("frontend", "source_commit", "6" * 40),
                                 ("frontend", "origin", "https://old.example.invalid")]:
            proposal, scope = example()
            proposal["release"][role][key] = value
            self.outcome(proposal, scope)

    def test_empty_database_cannot_have_r0_or_schema(self):
        for key, value in [("recovery_point_sha256", "6" * 64), ("postgresql_schema_version", 125),
                           ("database", "PRESENT")]:
            proposal, scope = example()
            proposal["current"][key] = value
            self.outcome(proposal, scope, error="EMPTY_DATABASE_CONFLICT")

    def test_no_r0_substitution_through_prerequisites(self):
        proposal, scope = example()
        proposal["prerequisites"]["isolation"]["recovery_point_sha256"] = "6" * 64
        self.outcome(proposal, scope, error="PREREQUISITE_BINDING_MISMATCH")

    def test_ready_requires_real_shaped_recovery_reference_and_v125_database(self):
        for key, value in [("recovery_point_sha256", None), ("recovery_point_sha256", "R0"),
                           ("postgresql_schema_version", 126), ("database", "ABSENT")]:
            proposal, scope = example(True)
            proposal["current"][key] = value
            self.outcome(proposal, scope)

    def test_each_prerequisite_required_and_only_reference_status_allowed(self):
        for baseline in (False, True):
            for name in example(baseline)[0]["prerequisites"]:
                for change in ("missing", "pending", "approved", "no_evidence"):
                    with self.subTest(baseline=baseline, name=name, change=change):
                        proposal, scope = example(baseline)
                        reference = proposal["prerequisites"][name]
                        if change == "missing":
                            del proposal["prerequisites"][name]
                        elif change == "pending":
                            reference["status"] = "PENDING"
                        elif change == "approved":
                            reference["approved"] = True
                        else:
                            reference["evidence_sha256"] = None
                        self.outcome(proposal, scope)

    def test_evidence_is_bound_to_target_epoch_source_config_and_recovery(self):
        for key, value in [("target_id", "old-staging"), ("epoch_id", "6" * 64),
                           ("source_commit", "7" * 40), ("config_sha256", "8" * 64),
                           ("recovery_point_sha256", "9" * 64)]:
            proposal, scope = example(True)
            proposal["prerequisites"]["policy_b"][key] = value
            self.outcome(proposal, scope, error="PREREQUISITE_BINDING_MISMATCH")

    def test_unknown_or_mismatched_predecessor(self):
        for value in (None, "UNKNOWN", "", "0" * 64, "6" * 64):
            proposal, scope = example()
            proposal["predecessor_inventory_sha256"] = value
            self.outcome(proposal, scope)
        proposal, scope = example()
        proposal["prerequisites"]["predecessor_review"]["evidence_sha256"] = "6" * 64
        self.outcome(proposal, scope, error="PREDECESSOR_EVIDENCE_MISMATCH")

    def test_wrong_state_evidence(self):
        proposal, scope = example()
        proposal["current"]["state_evidence_sha256"] = "6" * 64
        self.outcome(proposal, scope, error="STATE_EVIDENCE_MISMATCH")

    def test_skip_reverse_repeat_unknown_and_terminal_transitions(self):
        for current, proposed in [("EMPTY_CONFIRMED", "TEST_READY_CONFIRMED"),
                                  ("V125_BASELINE_INSTALLED", "EMPTY_CONFIRMED"),
                                  ("V125_BASELINE_INSTALLED", "V125_BASELINE_INSTALLED"),
                                  ("UNKNOWN", "V125_BASELINE_INSTALLED"),
                                  ("TEST_READY_CONFIRMED", "TEST_READY_CONFIRMED")]:
            proposal, scope = example()
            proposal["current"]["state"] = current
            proposal["proposed_state"] = proposed
            self.outcome(proposal, scope, error="INVALID_TRANSITION")

    def test_reused_operation_and_invalid_history(self):
        proposal, scope = example()
        scope["used_operation_ids"].append(proposal["operation_id"])
        self.outcome(proposal, scope, error="OPERATION_ID_REUSED")
        for history in (None, {}, ["ht-v125-proposal-used"] * 2, [True], ["unknown"]):
            proposal, scope = example()
            scope["used_operation_ids"] = history
            self.outcome(proposal, scope)

    def test_unknown_and_missing_fields_at_every_object(self):
        def paths(node, prefix=()):
            if isinstance(node, dict):
                yield prefix
                for key, value in node.items():
                    yield from paths(value, prefix + (key,))

        for document in (0, 1):
            for path in paths(example()[document]):
                for change in ("unknown", "missing"):
                    with self.subTest(document=document, path=path, change=change):
                        documents = example()
                        node = documents[document]
                        for key in path:
                            node = node[key]
                        if change == "unknown":
                            node["execute"] = True
                        else:
                            del node[next(iter(node))]
                        self.outcome(*documents)

    def test_no_authority_through_json_or_claimed_deploy(self):
        for key, value in [("mode", "EXECUTE"), ("execution", "DEPLOYED"),
                           ("approved", True), ("execution_authorized", True),
                           ("operationally_qualified", True), ("receipt", {"deploy": "PASS"})]:
            proposal, scope = example()
            proposal[key] = value
            self.outcome(proposal, scope)
        for key in ("telegram", "user_access", "control_import", "data_import"):
            proposal, scope = example()
            proposal["configuration"][key] = "ENABLED"
            self.outcome(proposal, scope, error="DISCONNECTED_CONFIGURATION_REQUIRED")

    def test_bad_json_shapes_types_duplicates_limits_and_nonfinite(self):
        scope = json.dumps(example()[1])
        for raw in ("null", "[]", "true", "1", '"str"', "{", '{"x":1,"x":2}',
                    '{"x":NaN}', '{"x":Infinity}', "[" * 1500 + "]" * 1500,
                    " " * 65537, '"\ud800"'):
            with self.subTest(raw_prefix=repr(raw[:30])):
                output = validate_json(raw, scope)
                self.assertFalse(output["valid"])
                self.assertEqual(output["status"], "PROPOSAL_ONLY")
                self.assertFalse(output["execution_authorized"])
        proposal, scope = example()
        raw = json.dumps(proposal).replace('"mode": "PROPOSAL_ONLY"',
                                           '"mode": "EXECUTE", "m\\u006fde": "PROPOSAL_ONLY"')
        self.assertEqual(validate_json(raw, json.dumps(scope))["errors"], ["DUPLICATE_JSON_KEY"])
        for value in (None, [], True, 1, "UNKNOWN"):
            for key in proposal:
                if type(value) is type(proposal[key]) and value == proposal[key]:
                    continue  # An unchanged format_version=1 is a valid control, not a mutation.
                candidate = copy.deepcopy(proposal)
                candidate[key] = value
                self.outcome(candidate, scope)

    def test_errors_do_not_echo_untrusted_input(self):
        proposal, scope = example()
        proposal["target"]["target_id"] = "secret-sentinel; $(touch /tmp/never)"
        self.assertNotIn("secret-sentinel", json.dumps(self.outcome(proposal, scope)))

    def test_cli_success_failure_and_read_only_files(self):
        proposal, scope = example()
        script = Path(__file__).with_name("v125_lifecycle_validator.py")
        with tempfile.TemporaryDirectory() as directory:
            scope_path = Path(directory) / "scope.json"
            scope_path.write_text(json.dumps(scope), encoding="utf-8")
            original = scope_path.read_bytes()
            for raw, expected_code in [(json.dumps(proposal), 0), ('{"execute":true}', 1)]:
                run = subprocess.run([sys.executable, "-B", str(script), "--scope", str(scope_path)],
                                     input=raw, text=True, capture_output=True, check=False)
                self.assertEqual(run.returncode, expected_code, run.stderr)
                output = json.loads(run.stdout)
                self.assertEqual(output["status"], "PROPOSAL_ONLY")
                self.assertFalse(output["execution_authorized"])
                self.assertEqual(run.stderr, "")
            self.assertEqual(scope_path.read_bytes(), original)
            self.assertEqual(list(Path(directory).iterdir()), [scope_path])


if __name__ == "__main__":
    unittest.main()
