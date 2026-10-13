"""Offline release transitions and fault injection; no Docker daemon or VPS."""

import copy
import hashlib
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import staging_release as release


def fixture():
    db = {"container_id": "d" * 64, "system_id": "123456789", "database": "staging",
          "user": "staging", "volume": "staging_pgdata", "network": "staging_default",
          "fingerprint": "4" * 64}
    scope = {
        "project_id": "Hookah_Tootah", "project": "hookah-staging",
        "directory": "/fixture/staging", "compose_file": "/fixture/staging/docker-compose.yml",
        "local_url": "http://127.0.0.1:8080", "public_url": "https://staging.example.invalid",
        "compose_fingerprint": "1" * 64, "configuration_fingerprint": "2" * 64,
        "migration_fingerprint": "3" * 64,
        "protected": {"postgres": db,
                      "production": {"project": "hookah-production", "database_container_id": "e" * 64,
                                     "volumes": ["production_pgdata"], "networks": ["production_default"],
                                     "fingerprint": "5" * 64}, "shared_fingerprint": "6" * 64},
    }

    def image(character):
        return {"id": "sha256:" + character * 64, "revision": character * 40,
                "source": release.SOURCE, "platform": "linux/amd64", "postgresql_head": 125,
                "migration_fingerprint": scope["migration_fingerprint"], "ci_sha": character * 40,
                "ci_status": "success", "approved": True, "available": True}

    current, candidate = image("a"), image("b")
    snapshot = {key: copy.deepcopy(scope[key]) for key in
                ("project", "directory", "compose_file", "compose_fingerprint",
                 "configuration_fingerprint", "protected")}
    snapshot.update({
        "backend": {"project": scope["project"], "service": "backend", "count": 1, "running": True,
                    "image_id": current["id"], "app_env": "staging", "policy_verified": True,
                    "db_binding_verified": True,
                    "database": {key: db[key] for key in
                                 ("container_id", "system_id", "database", "user", "network")}},
        "schema": {"head": 125, "v126_count": 0, "failed_count": 0},
        "responses": {
            origin: {"health": {"status": 200, "body": {"status": "ok"}},
                     "db_health": {"status": 200, "body": {"status": "ok"}},
                     "miniapp": {"status": 200, "body": "<!doctype html><html></html>"},
                     "version": {"status": 200, "body": {"service": "backend", "env": "staging",
                                                           "version": current["revision"],
                                                           "time": "2026-10-12T00:00:00Z"}}}
            for origin in ("local", "public")
        },
    })
    return {"scope": scope, "current": current, "candidate": candidate, "snapshot": snapshot}


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.data = fixture()
        self.scope, self.current, self.candidate = (self.data[key] for key in ("scope", "current", "candidate"))
        self.store = release.Store(Path(self.temporary.name) / "state")
        with self.store.locked():
            self.store.write({"scope": release.fingerprint(self.scope), "status": "ACCEPTED",
                              "accepted": self.current, "operation": None, "reason": None})
        self.runtime = release.FixtureRuntime(self.data["snapshot"],
                                              {r["id"]: r for r in (self.current, self.candidate)})
        self.controller = release.Controller(self.scope, self.store, self.runtime, readiness_seconds=0.01)

    def deploy(self):
        return self.controller.deploy(self.candidate, expected_current=self.current["id"], owner_approved=True)

    def refused(self, reason, action=None):
        with self.assertRaisesRegex(release.Refused, "^" + reason + "$"):
            (action or self.deploy)()

    def test_different_images_then_next_release_preserves_exact_previous(self):
        self.assertEqual(self.deploy(), "READY_FOR_SMOKE")
        state = self.store.read()
        self.assertEqual(state["accepted"], self.current)
        self.assertEqual(state["operation"]["from"], self.current)
        self.assertEqual(state["operation"]["to"], self.candidate)
        self.assertEqual(self.controller.accept(smoke_passed=True), "ACCEPTED")
        third = copy.deepcopy(self.candidate)
        third.update(id="sha256:" + "c" * 64, revision="c" * 40, ci_sha="c" * 40)
        self.runtime.images[third["id"]] = third
        self.controller.deploy(third, expected_current=self.candidate["id"], owner_approved=True)
        self.assertEqual(self.store.read()["operation"]["from"], self.candidate)
        self.assertEqual(len(self.runtime.commands), 2)

    def test_manual_smoke_is_required(self):
        self.deploy()
        self.refused("MANUAL_SMOKE_REQUIRED", self.controller.accept)
        self.assertEqual(self.store.read()["status"], "READY_FOR_SMOKE")

    def test_smoke_cannot_override_failed_automatic_checks(self):
        self.refused("AUTOMATIC_CHECKS_NOT_PASSED", lambda: self.controller.accept(smoke_passed=True))

    def test_controlled_rollback_before_smoke(self):
        self.deploy()
        self.assertEqual(self.controller.rollback(owner_approved=True), "READY_FOR_SMOKE")
        self.assertEqual(self.runtime.commands[-1]["environment"],
                         {"BACKEND_IMAGE": self.current["id"], "APP_VERSION": self.current["revision"]})
        self.controller.accept(smoke_passed=True)
        self.assertEqual(self.store.read()["accepted"], self.current)
        self.refused("ROLLBACK_IMAGE_MISSING", lambda: self.controller.rollback(owner_approved=True))

    def test_controlled_rollback_after_acceptance(self):
        self.deploy()
        self.controller.accept(smoke_passed=True)
        self.controller.rollback(owner_approved=True)
        self.controller.accept(smoke_passed=True)
        self.assertEqual(self.store.read()["accepted"], self.current)

    def test_candidate_missing(self):
        self.candidate.clear()
        self.refused("IMAGE_MISSING")
        self.assertEqual(self.runtime.commands, [])

    def test_candidate_unavailable(self):
        del self.runtime.images[self.candidate["id"]]
        self.refused("IMAGE_MISSING")
        self.assertEqual(self.runtime.commands, [])

    def test_rollback_missing(self):
        self.refused("ROLLBACK_IMAGE_MISSING", lambda: self.controller.rollback(owner_approved=True))

    def test_previous_image_must_remain_available(self):
        self.deploy()
        del self.runtime.images[self.current["id"]]
        self.refused("IMAGE_MISSING", lambda: self.controller.rollback(owner_approved=True))
        self.assertEqual(len(self.runtime.commands), 1)

    def test_exact_image_identity_and_provenance(self):
        for key, value, reason in (
            ("source", "https://example.invalid/foreign", "IMAGE_PROVENANCE_MISMATCH"),
            ("platform", "linux/arm64", "IMAGE_PROVENANCE_MISMATCH"),
            ("revision", "shortsha", "IMAGE_PROVENANCE_MISMATCH"),
            ("ci_sha", "c" * 40, "IMAGE_NOT_APPROVED"),
            ("ci_status", "pending", "IMAGE_NOT_APPROVED"),
            ("approved", False, "IMAGE_NOT_APPROVED"),
            ("available", False, "IMAGE_UNAVAILABLE"),
            ("postgresql_head", 126, "MIGRATION_RELEASE_REQUIRED"),
            ("migration_fingerprint", "7" * 64, "MIGRATION_RELEASE_REQUIRED"),
        ):
            with self.subTest(key=key):
                original = self.candidate[key]
                self.candidate[key] = value
                self.refused(reason)
                self.candidate[key] = original
        self.assertEqual(self.runtime.commands, [])

    def test_observed_image_record_cannot_change(self):
        self.runtime.images[self.candidate["id"]]["revision"] = "c" * 40
        self.runtime.images[self.candidate["id"]]["ci_sha"] = "c" * 40
        self.refused("IMAGE_IDENTITY_CHANGED")

    def test_owner_approval_required_for_deploy_and_rollback(self):
        self.refused("OWNER_APPROVAL_REQUIRED",
                     lambda: self.controller.deploy(self.candidate, expected_current=self.current["id"]))
        self.deploy()
        self.refused("OWNER_APPROVAL_REQUIRED", self.controller.rollback)

    def test_staging_compose_identity(self):
        for key in ("project", "directory", "compose_file", "compose_fingerprint", "configuration_fingerprint"):
            with self.subTest(key=key):
                original = self.runtime.snapshot[key]
                self.runtime.snapshot[key] = "different"
                self.refused("STAGING_IDENTITY_MISMATCH")
                self.runtime.snapshot[key] = original
        self.assertEqual(self.runtime.commands, [])

    def test_database_binding_mismatch(self):
        for key in ("container_id", "system_id", "database", "user", "network"):
            with self.subTest(key=key):
                binding = self.runtime.snapshot["backend"]["database"]
                original = binding[key]
                binding[key] = "other"
                self.refused("DATABASE_BINDING_MISMATCH")
                binding[key] = original
        self.runtime.snapshot["backend"]["db_binding_verified"] = False
        self.refused("DATABASE_BINDING_MISMATCH")
        self.assertEqual(self.runtime.commands, [])

    def test_production_postgres_and_shared_resources_are_protected(self):
        for key in ("production", "postgres", "shared_fingerprint"):
            with self.subTest(key=key):
                original = self.runtime.snapshot["protected"][key]
                self.runtime.snapshot["protected"][key] = "changed"
                self.refused("PROTECTED_RESOURCES_CHANGED")
                self.runtime.snapshot["protected"][key] = original
        self.assertEqual(self.runtime.commands, [])

    def test_production_scope_and_resource_reuse_refused(self):
        for change in ("project", "container_id", "volume", "network"):
            scope = copy.deepcopy(self.scope)
            db, prod = scope["protected"]["postgres"], scope["protected"]["production"]
            if change == "project":
                scope["project"] = prod["project"]
            elif change == "container_id":
                db[change] = prod["database_container_id"]
            else:
                db[change] = prod[change + "s"][0]
            with self.subTest(change=change):
                self.refused("PRODUCTION_RESOURCE_OVERLAP", lambda: release.replacement(scope, self.candidate))

    def test_schema_head_v126_and_failed_migration_refused(self):
        for key, value in (("head", 126), ("v126_count", 1), ("failed_count", 1)):
            with self.subTest(key=key):
                original = self.runtime.snapshot["schema"][key]
                self.runtime.snapshot["schema"][key] = value
                self.refused("V125_SCHEMA_REQUIRED")
                self.runtime.snapshot["schema"][key] = original

    def test_duplicate_backend_or_poller_refused(self):
        self.runtime.snapshot["backend"]["count"] = 2
        self.refused("BACKEND_IDENTITY_MISMATCH")

    def test_policy_and_environment_refused(self):
        for key, value in (("policy_verified", False), ("app_env", "production")):
            with self.subTest(key=key):
                original = self.runtime.snapshot["backend"][key]
                self.runtime.snapshot["backend"][key] = value
                self.refused("STAGING_POLICY_MISMATCH")
                self.runtime.snapshot["backend"][key] = original

    def test_mutation_command_is_backend_only_without_build_pull_or_dependencies(self):
        self.deploy()
        command = self.runtime.commands[0]
        self.assertEqual(command, release.replacement(self.scope, self.candidate))
        argv = command["argv"]
        self.assertEqual(argv[-1], "backend")
        for word in ("postgres", "down", "build", "--remove-orphans", "--renew-anon-volumes"):
            self.assertNotIn(word, argv)
        for option in ("--no-deps", "--no-build", "--force-recreate"):
            self.assertIn(option, argv)
        self.assertEqual(argv[argv.index("--pull") + 1], "never")
        self.assertEqual(argv[argv.index("--project-name") + 1], self.scope["project"])

    def test_repeated_deploy_before_smoke_and_after_acceptance_refused(self):
        self.deploy()
        self.refused("PREVIOUS_RELEASE_UNFINISHED")
        self.controller.accept(smoke_passed=True)
        self.refused("IMAGE_ALREADY_ACCEPTED",
                     lambda: self.controller.deploy(self.candidate, expected_current=self.candidate["id"],
                                                    owner_approved=True))
        self.refused("ACCEPTED_IMAGE_CHANGED")
        self.assertEqual(len(self.runtime.commands), 1)

    def test_parallel_process_refused_without_mutation(self):
        code = ("import sys; from pathlib import Path; from staging_release import Store; "
                "s=Store(Path(sys.argv[1])); "
                "\nwith s.locked():\n print('locked', flush=True)\n sys.stdin.readline()\n")
        process = subprocess.Popen([sys.executable, "-B", "-c", code, str(self.store.directory)],
                                   cwd=Path(__file__).parent, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual(process.stdout.readline().strip(), "locked")
            self.refused("DEPLOYMENT_BUSY")
            self.assertEqual(self.runtime.commands, [])
        finally:
            process.communicate("release\n", timeout=5)
        self.assertEqual(process.returncode, 0)
        self.assertEqual(self.deploy(), "READY_FOR_SMOKE")

    def test_pending_intent_after_restart_cannot_retry_or_rollback(self):
        state = self.store.read()
        state["status"] = "APPLYING"
        with self.store.locked():
            self.store.write(state)
        before = self.store.path.read_bytes()
        self.refused("OPERATOR_RECONCILIATION_REQUIRED")
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", lambda: self.controller.rollback(owner_approved=True))
        self.assertEqual(self.store.path.read_bytes(), before)
        self.assertEqual(self.runtime.commands, [])

    def test_readiness_timeout_retains_known_candidate_and_allows_explicit_rollback(self):
        self.runtime.ready = lambda image, *, timeout: image["id"] == self.current["id"]
        self.refused("READINESS_TIMEOUT_MANUAL_DECISION_REQUIRED")
        self.assertEqual(self.store.read()["status"], "READINESS_FAILED")
        self.assertEqual(self.runtime.snapshot["backend"]["image_id"], self.candidate["id"])
        self.assertEqual(len(self.runtime.commands), 1)
        self.controller.rollback(owner_approved=True)
        self.controller.accept(smoke_passed=True)
        self.assertEqual(self.store.read()["accepted"], self.current)

    def test_docker_failure_even_after_effect_is_unknown(self):
        self.runtime.docker_outcome = "FAILED"
        self.refused("DOCKER_OUTCOME_UNDEFINED_OPERATOR_REQUIRED")
        state = self.store.read()
        self.assertEqual(state["status"], "UNKNOWN")
        self.assertEqual(state["operation"]["from"], self.current)
        self.assertEqual(state["operation"]["to"], self.candidate)
        self.refused("OPERATOR_RECONCILIATION_REQUIRED")
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", lambda: self.controller.rollback(owner_approved=True))
        self.assertEqual(len(self.runtime.commands), 1)

    def test_docker_timeout_and_interrupt_are_unknown_without_raw_logs(self):
        def failed(command, *, timeout):
            self.assertEqual(self.store.read()["status"], "APPLYING")
            raise TimeoutError("SECRET_FROM_DOCKER")

        self.runtime.replace = failed
        self.refused("DOCKER_OUTCOME_UNDEFINED_OPERATOR_REQUIRED")
        self.assertNotIn("SECRET_FROM_DOCKER", self.store.path.read_text())
        self.assertEqual(self.store.read()["diagnostic"], {"phase": "RECREATE", "reason": "COMMAND_TIMEOUT"})

    def test_docker_interrupt_preserves_unknown_intent(self):
        def interrupted(command, *, timeout):
            raise KeyboardInterrupt()

        self.runtime.replace = interrupted
        self.refused("DOCKER_OUTCOME_UNDEFINED_OPERATOR_REQUIRED")
        self.assertEqual(self.store.read()["status"], "UNKNOWN")

    def test_postoperation_production_change_blocks_rollback(self):
        original = self.runtime.replace

        def changed(command, *, timeout):
            result = original(command, timeout=timeout)
            self.runtime.snapshot["protected"]["production"]["fingerprint"] = "9" * 64
            return result

        self.runtime.replace = changed
        self.refused("DOCKER_OUTCOME_UNDEFINED_OPERATOR_REQUIRED")
        self.assertEqual(self.store.read()["diagnostic"],
                         {"phase": "POSTSTATE", "reason": "PROTECTED_RESOURCES_CHANGED"})
        self.refused("OPERATOR_RECONCILIATION_REQUIRED", lambda: self.controller.rollback(owner_approved=True))
        self.assertEqual(len(self.runtime.commands), 1)

    def test_http_unavailability_with_known_poststate_allows_controlled_rollback(self):
        original = self.runtime.ready

        def failed(image, *, timeout):
            if image["id"] == self.candidate["id"]:
                raise OSError("private raw output")
            return original(image, timeout=timeout)

        self.runtime.ready = failed
        self.refused("READINESS_TIMEOUT_MANUAL_DECISION_REQUIRED")
        self.assertEqual(self.store.read()["status"], "READINESS_FAILED")
        self.assertEqual(self.store.read()["diagnostic"],
                         {"phase": "HTTP_READY", "reason": "HTTP_PROBE_UNAVAILABLE"})
        self.controller.rollback(owner_approved=True)

    def test_lost_observation_after_recreate_is_unknown(self):
        original = self.runtime.observe

        def failed(*, timeout):
            if self.runtime.snapshot["backend"]["image_id"] == self.candidate["id"]:
                raise TimeoutError("private raw output")
            return original(timeout=timeout)

        self.runtime.observe = failed
        self.refused("DOCKER_OUTCOME_UNDEFINED_OPERATOR_REQUIRED")
        self.assertEqual(self.store.read()["status"], "UNKNOWN")
        self.assertEqual(self.store.read()["diagnostic"], {"phase": "POSTSTATE", "reason": "COMMAND_TIMEOUT"})

    def test_shared_readiness_budget_and_separate_final_confirmation(self):
        clock = {"now": 0.0}
        observed_deadlines = []
        original_observe = self.runtime.observe

        def observed(*, timeout):
            if self.runtime.snapshot["backend"]["image_id"] == self.candidate["id"]:
                observed_deadlines.append((clock["now"], timeout))
                clock["now"] += timeout
            return original_observe(timeout=timeout)

        def ready(image, *, timeout):
            if image["id"] == self.current["id"]:
                return True
            observed_deadlines.append((clock["now"], timeout))
            clock["now"] += timeout
            return False

        self.runtime.observe, self.runtime.ready = observed, ready
        self.controller.seconds = 120
        with mock.patch.object(release.time, "monotonic", lambda: clock["now"]), \
                mock.patch.object(release.time, "sleep", lambda seconds: clock.__setitem__("now", clock["now"] + seconds)):
            self.refused("READINESS_TIMEOUT_MANUAL_DECISION_REQUIRED")
        # Initial poststate 5s + shared readiness 120s + final poststate 5s.
        self.assertAlmostEqual(clock["now"], 130)
        for start, timeout in observed_deadlines[1:-1]:
            self.assertLessEqual(start + timeout, 125)

    def test_readiness_bounds(self):
        for seconds in (0, -1, 121):
            with self.subTest(seconds=seconds):
                self.refused("READINESS_BOUND_INVALID",
                             lambda: release.Controller(self.scope, self.store, self.runtime,
                                                        readiness_seconds=seconds))

    def test_health_db_static_public_and_version_responses(self):
        baseline = self.runtime.snapshot["responses"]
        self.assertTrue(release.responses_ready(baseline, self.current["revision"]))
        for origin in ("local", "public"):
            for endpoint in ("health", "db_health", "miniapp"):
                for field, value in (("status", 503), ("status", 302), ("body", "wrong response")):
                    with self.subTest(origin=origin, endpoint=endpoint, field=field, value=value):
                        responses = copy.deepcopy(baseline)
                        responses[origin][endpoint][field] = value
                        self.assertFalse(release.responses_ready(responses, self.current["revision"]))
        for key, value in (("env", "production"), ("version", "c" * 40), ("service", "other")):
            with self.subTest(key=key):
                responses = copy.deepcopy(baseline)
                responses["local"]["version"]["body"][key] = value
                self.assertFalse(release.responses_ready(responses, self.current["revision"]))

    def test_current_readiness_failure_does_not_mutate(self):
        self.runtime.snapshot["responses"]["public"]["miniapp"]["status"] = 503
        self.refused("CURRENT_IMAGE_NOT_READY")
        self.assertEqual(self.runtime.commands, [])

    def test_stopped_current_or_candidate_cannot_be_accepted_by_http_alone(self):
        self.runtime.snapshot["backend"]["running"] = False
        self.refused("CURRENT_IMAGE_NOT_READY")
        self.runtime.snapshot["backend"]["running"] = True
        self.deploy()
        self.runtime.snapshot["backend"]["running"] = False
        self.refused("READINESS_LOST", lambda: self.controller.accept(smoke_passed=True))

    def test_known_stopped_candidate_reaches_readiness_failure_with_explicit_rollback(self):
        original = self.runtime.replace

        def stopped(command, *, timeout):
            result = original(command, timeout=timeout)
            self.runtime.snapshot["backend"]["running"] = False
            return result

        self.runtime.replace = stopped
        self.refused("READINESS_TIMEOUT_MANUAL_DECISION_REQUIRED")
        self.assertEqual(self.store.read()["status"], "READINESS_FAILED")
        self.runtime.replace = original
        self.runtime.snapshot["backend"]["running"] = True
        self.controller.rollback(owner_approved=True)

    def test_wrong_target_state_refused(self):
        state = self.store.read()
        state["scope"] = "f" * 64
        with self.store.locked():
            self.store.write(state)
        self.refused("STATE_TARGET_MISMATCH")


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "fixture.json"
        self.path.write_text(json.dumps(fixture()))
        self.script = Path(__file__).with_name("staging_release.py")

    def run_cli(self, *arguments):
        return subprocess.run([sys.executable, "-B", str(self.script), *arguments],
                              capture_output=True, text=True, timeout=10)

    def test_plan_never_invokes_docker_or_creates_state(self):
        result = self.run_cli("plan", "--fixture", str(self.path))
        self.assertEqual(result.returncode, 0, result.stderr)
        outcome = json.loads(result.stdout)
        self.assertEqual(outcome["status"], "OFFLINE_PLAN")
        self.assertFalse(outcome["live_execution"])
        self.assertEqual(list(Path(self.temporary.name).iterdir()), [self.path])

    def test_simulation_is_labelled_and_replay_refused(self):
        state = str(Path(self.temporary.name) / "state")
        args = ("simulate", "--fixture", str(self.path), "--state-dir", state)
        result = self.run_cli(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["status"], "SIMULATED_ACCEPTED")
        result = self.run_cli(*args)
        self.assertEqual(json.loads(result.stderr)["reason"], "SIMULATION_ALREADY_EXISTS")

    def test_all_live_commands_refused_before_input_or_state_io(self):
        for action in ("deploy", "rollback", "accept"):
            with self.subTest(action=action):
                directory = Path(self.temporary.name) / action
                result = self.run_cli(action, "--fixture", "/missing/private/input",
                                      "--state-dir", str(directory))
                self.assertEqual(result.returncode, 2)
                self.assertEqual(json.loads(result.stderr)["reason"], release.LIVE_REFUSAL)

                self.assertFalse(directory.exists())

    def test_duplicate_keys_and_malformed_input_do_not_expose_values(self):
        for raw in ('{"secret":"SECRET","secret":"SECRET"}', '{"SECRET":'):
            with self.subTest(raw=raw):
                self.path.write_text(raw)
                result = self.run_cli("plan", "--fixture", str(self.path))
                self.assertEqual(result.returncode, 2)
                self.assertNotIn("SECRET", result.stdout + result.stderr)

    def test_shell_entrypoint_is_closed(self):
        result = subprocess.run(["bash", str(self.script.with_name("deploy-staging.sh")), "deploy"],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stderr)["reason"], release.LIVE_REFUSAL)

    def test_unenrolled_worker_returns_failed_in_stdout_without_reading_payload(self):
        result = self.run_cli("worker", "--target", "fixture-staging")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout), {"status": "FAILED", "reason": release.LIVE_REFUSAL})

    def test_legacy_alias_and_controlmaster_invocations_are_closed(self):
        for name in ("deploy-staging.sh", "deploy-staging-controlmaster.sh"):
            with self.subTest(entrypoint=name):
                result = subprocess.run(["bash", str(self.script.with_name(name)), "hookah-staging"],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(json.loads(result.stderr)["reason"], release.LIVE_REFUSAL)


class CommandRuntimeTests(unittest.TestCase):
    """Execute adapter source against fake raw CLI output, never a real runner."""

    def setUp(self):
        self.data = fixture()
        self.scope, self.current, self.candidate = (self.data[key] for key in ("scope", "current", "candidate"))
        self.images = {record["id"]: record for record in (self.current, self.candidate)}
        self.env = {"APP_ENV": "staging", "APP_VERSION": self.current["revision"],
                    "TELEGRAM_TRAFFIC_POLICY": "PRODUCT", "STAGING_MAINTENANCE_MODE": "OFF",
                    "DB_JDBC_URL": "jdbc:postgresql://postgres:5432/staging", "DB_USER": "staging",
                    "DB_PASSWORD": "fake-secret"}
        self.pg_env = {"POSTGRES_DB": "staging", "POSTGRES_USER": "staging", "POSTGRES_PASSWORD": "fake-secret"}
        self.composed = {"name": self.scope["project"], "services": {
            "backend": {"image": self.current["id"], "environment": self.env},
            "postgres": {"image": "postgres:17", "environment": self.pg_env}},
            "volumes": {"pgdata": {"name": "staging_pgdata"}}}

        def container(cid, project, service, *, env, network, volume=None):
            return {"Id": cid, "Image": self.current["id"] if service == "backend" else "sha256:" + "9" * 64,
                    "Config": {"Env": [key + "=" + value for key, value in env.items()],
                               "Labels": {"com.docker.compose.project": project,
                                          "com.docker.compose.service": service,
                                          "com.docker.compose.project.working_dir": self.scope["directory"],
                                          "com.docker.compose.project.config_files": self.scope["compose_file"]}},
                    "HostConfig": {"Privileged": False, "NetworkMode": network},
                    "Mounts": [{"Type": "volume", "Name": volume}] if volume else [],
                    "RestartCount": 0, "State": {"StartedAt": "fixture-start", "Running": True},
                    "NetworkSettings": {"Networks": {network: {"NetworkID": network + "-id",
                                                               "IPAddress": "fixture-ip-" + cid,
                                                               "EndpointID": "fixture-endpoint-" + cid,
                                                               "MacAddress": "02:42:ac:11:00:02",
                                                               "Aliases": [service]}}}}

        self.backend = container("f" * 64, self.scope["project"], "backend", env=self.env, network="staging_default")
        self.pg = container("d" * 64, self.scope["project"], "postgres", env=self.pg_env,
                            network="staging_default", volume="staging_pgdata")
        self.prod = container("e" * 64, "hookah-production", "postgres", env={},
                              network="production_default", volume="production_pgdata")
        self.shared = container("6" * 64, "shared", "proxy", env={}, network="proxy_default")
        self.containers = [self.backend, self.pg, self.prod, self.shared]
        self.db = {"system_id": "123456789", "database": "staging", "user": "staging",
                   "head": 125, "v126_count": 0, "failed_count": 0}
        self.files = b"fixture compose bytes"
        protected = self.scope["protected"]
        protected["postgres"]["fingerprint"] = release.fingerprint(release.stable_container(self.pg))
        protected["production"]["fingerprint"] = release.fingerprint([release.stable_container(self.prod)])
        protected["shared_fingerprint"] = release.fingerprint([release.stable_container(self.shared)])
        normalized = copy.deepcopy(self.composed)
        normalized["services"]["backend"].pop("image")
        normalized["services"]["backend"]["environment"].pop("APP_VERSION")
        self.scope["configuration_fingerprint"] = release.fingerprint({"compose": normalized,
                                                                       "backend_host_config": self.backend["HostConfig"]})
        self.scope["compose_fingerprint"] = hashlib.sha256(self.files).hexdigest()
        self.calls, self.http_status, self.fail_replacement, self.label_source = [], 200, False, release.SOURCE
        configuration = mock.Mock()
        configuration.select.side_effect = self.select
        self.runtime = release.CommandRuntime(self.scope, self.images, runner=self.fake_command,
                                              file_reader=lambda path: self.files, configuration=configuration)

    def select(self, record):
        self.composed["services"]["backend"]["image"] = record["id"]
        self.composed["services"]["backend"]["environment"]["APP_VERSION"] = record["revision"]

    def fake_command(self, argv, *, timeout, payload=None, environment=None):
        self.assertGreater(timeout, 0)
        self.calls.append((copy.deepcopy(argv), copy.deepcopy(environment), payload, timeout))
        if argv[:3] == ["docker", "ps", "-aq"]:
            return 0, ("\n".join(item["Id"] for item in self.containers) + "\n").encode()
        if argv[:2] == ["docker", "inspect"]:
            return 0, json.dumps(self.containers).encode()
        if argv[:3] == ["docker", "image", "inspect"]:
            self.assertEqual(len(argv), 4, 'Docker 28.0/API 1.48 has no inspect --platform option')
            record = self.images[argv[3]]
            return 0, json.dumps([{"Id": record["id"], "Os": "linux", "Architecture": "amd64",
                                  "Config": {"Labels": {"org.opencontainers.image.revision": record["revision"],
                                                        "org.opencontainers.image.source": self.label_source}}}]).encode()
        if argv[:2] == ["docker", "exec"]:
            self.assertEqual(argv[3], self.pg["Id"])
            self.assertIn(b"BEGIN READ ONLY", payload)
            return 0, json.dumps(self.db).encode()
        if argv[:2] == ["docker", "compose"]:
            if "config" in argv:
                self.assertIsNone(environment)
                return 0, json.dumps(self.composed).encode()
            self.assertEqual(argv[-1], "backend")
            self.assertIsNone(environment)
            selected = self.composed["services"]["backend"]
            self.backend["Image"] = selected["image"]
            self.backend["Config"]["Env"] = [key + "=" + (selected["environment"]["APP_VERSION"] if key == "APP_VERSION" else value)
                                              for key, value in self.env.items()]
            return (1 if self.fail_replacement else 0), b""
        if argv[0] == "curl":
            url = argv[-1]
            if url.endswith("/miniapp/"):
                body = b"<html></html>"
            elif url.endswith("/version"):
                body = json.dumps({"service": "backend", "env": "staging", "time": "fixture-time",
                                   "version": release.CommandRuntime.environment(self.backend)["APP_VERSION"]}).encode()
            else:
                body = b'{"status":"ok"}'
            return 0, body + b"\n" + str(self.http_status).encode()
        self.fail("unexpected command")

    def test_raw_observations_match_scope_without_retaining_secrets(self):
        snapshot = self.runtime.observe(timeout=5)
        release.validate_snapshot(snapshot, self.scope, self.current["id"])
        self.assertNotIn("fake-secret", json.dumps(snapshot))
        self.assertTrue(self.runtime.ready(self.current, timeout=5))

    def test_raw_adapter_different_images_then_rollback(self):
        self.release_and_rollback()

    def test_independent_mount_permutations_preserve_protected_fingerprint_and_input(self):
        mounts = [{"Type": "bind", "Source": "/fixture/source-" + str(index),
                   "Destination": destination, "Mode": "ro", "RW": False,
                   "Propagation": "rprivate"}
                  for index, destination in enumerate(("/data", "/database", "/etc/app", "/logs"))]
        for item in (self.pg, self.prod, self.shared):
            for mount in item["Mounts"]:
                mount["Destination"] = "/pgdata"
            item["Mounts"] += copy.deepcopy(mounts)
        self.scope["protected"] = self.runtime.observe(timeout=5)["protected"]
        for order in itertools.permutations(mounts):
            for item in (self.pg, self.prod, self.shared):
                item["Mounts"][-4:] = copy.deepcopy(order)
                raw = copy.deepcopy(item)
                expected = sorted(raw["Mounts"], key=lambda mount: json.dumps(mount, sort_keys=True))
                self.assertEqual(release.stable_container(raw)["Mounts"], expected)
                self.assertEqual(raw, item)
            before = copy.deepcopy(self.containers)
            release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
            self.assertEqual(self.containers, before)
        self.release_and_rollback()

    def test_all_mount_fields_and_multiplicity_remain_protected(self):
        mount = {"Type": "bind", "Source": "/fixture/data", "Destination": "/data",
                 "Mode": "ro,z", "RW": False, "Propagation": "rprivate",
                 "Options": {"nested": ["first", "second"]}}
        changes = {key: lambda mounts, key=key, value=value: mounts[-1].update({key: value})
                   for key, value in (("Type", "tmpfs"), ("Source", "/fixture/other"),
                                      ("Destination", "/other"), ("Mode", "rw,z"), ("RW", True),
                                      ("Propagation", "rshared"), ("Name", "added-name"),
                                      ("Driver", "other-driver"), ("Options", {"nested": ["second", "first"]}),
                                      ("FutureField", {"value": True}))}
        changes.update(add=lambda mounts: mounts.append(dict(mount, Destination="/extra")),
                       remove=lambda mounts: mounts.pop(),
                       duplicate=lambda mounts: mounts.append(copy.deepcopy(mount)),
                       remove_field=lambda mounts: mounts[-1].pop("RW"))
        for item in (self.pg, self.prod, self.shared):
            for existing in item["Mounts"]:
                existing["Destination"] = "/pgdata"
            item["Mounts"].append(copy.deepcopy(mount))
        baseline = self.runtime.observe(timeout=5)["protected"]
        self.scope["protected"] = baseline
        for item in (self.pg, self.prod, self.shared):
            original = copy.deepcopy(item["Mounts"])
            for field, change in changes.items():
                with self.subTest(container=item["Id"], field=field):
                    change(item["Mounts"])
                    with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
                        release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
                    item["Mounts"] = copy.deepcopy(original)
            # A baseline containing duplicates must also detect a reduced count.
            item["Mounts"].append(copy.deepcopy(mount))
            self.scope["protected"] = self.runtime.observe(timeout=5)["protected"]
            self.assertEqual(release.stable_container(item)["Mounts"].count(mount), 2)
            item["Mounts"].pop()
            with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
                release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
            self.scope["protected"] = baseline

    def test_overlapping_or_uncertain_mount_destinations_preserve_order(self):
        for left, right in (("/data", "/data/nested"), ("/data/nested", "/data"),
                            ("/data", "/data"), ("/", "/data"),
                            ("/data/", "/data/nested"), ("/data/../data", "/data/nested"),
                            ("/data//nested", "/data"), ("/data/./nested", "/data"),
                            (None, "/data"), ("relative", "/data"), ("", "/data")):
            with self.subTest(left=left, right=right):
                self.shared["Mounts"] = [{"Type": "bind", "Source": "/fixture/one", "Destination": left},
                                         {"Type": "bind", "Source": "/fixture/two", "Destination": right}]
                if left is None:
                    self.shared["Mounts"][0].pop("Destination")
                self.scope["protected"] = self.runtime.observe(timeout=5)["protected"]
                self.shared["Mounts"].reverse()
                with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
                    release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])

    def test_postgres_structural_drift_remains_blocked(self):
        changes = {
            "container_id": lambda: self.pg.update(Id="7" * 64),
            "image": lambda: self.pg.update(Image=self.candidate["id"]),
            "environment": lambda: self.pg["Config"]["Env"].append("CHANGED=value"),
            "host_config": lambda: self.pg["HostConfig"].update(Privileged=True),
        }
        original = copy.deepcopy(self.pg)
        for field, change in changes.items():
            with self.subTest(field=field):
                change()
                with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
                    release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
                self.pg.clear()
                self.pg.update(copy.deepcopy(original))

    def release_and_rollback(self):
        with tempfile.TemporaryDirectory() as temp:
            store = release.Store(Path(temp) / "state")
            with store.locked():
                store.write({"scope": release.fingerprint(self.scope), "status": "ACCEPTED", "accepted": self.current,
                             "operation": None, "reason": None})
            controller = release.Controller(self.scope, store, self.runtime)
            self.assertEqual(controller.deploy(self.candidate, expected_current=self.current["id"], owner_approved=True),
                             "READY_FOR_SMOKE")
            controller.accept(smoke_passed=True)
            self.assertEqual(controller.rollback(owner_approved=True), "READY_FOR_SMOKE")
            controller.accept(smoke_passed=True)
            self.assertEqual(store.read()["accepted"], self.current)

    def test_completed_protected_restarts_allow_release_and_rollback(self):
        for item in (self.prod, self.shared, self.pg):
            item["State"]["StartedAt"] = "later-start"
            item["RestartCount"] += 1
            for endpoint in item["NetworkSettings"]["Networks"].values():
                endpoint.update(EndpointID="new-runtime-endpoint", MacAddress="02:42:ac:11:00:03")
        release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
        self.release_and_rollback()

    def test_production_and_shared_structural_drift_remains_blocked(self):
        changes = {
            "container_id": lambda item: item.update(Id="7" * 64),
            "image": lambda item: item.update(Image=self.candidate["id"]),
            "environment": lambda item: item["Config"].update(Env=["CHANGED=value"]),
            "labels": lambda item: item["Config"]["Labels"].update(changed="value"),
            "host_config": lambda item: item["HostConfig"].update(PortBindings={"80/tcp": [{"HostPort": "9999"}]}),
            "mounts": lambda item: item.update(Mounts=[{"Type": "volume", "Name": "other-volume"}]),
            "network_membership": lambda item: item["NetworkSettings"]["Networks"].update(other={"NetworkID": "other-id"}),
            "network_removed": lambda item: item["NetworkSettings"]["Networks"].clear(),
        }
        for key, value in (("NetworkID", "other-id"), ("IPAddress", "other-ip"), ("Aliases", ["other"]),
                           ("DNSNames", ["other"]), ("IPAMConfig", {"IPv4Address": "static-other-ip"}),
                           ("DriverOpts", {"changed": "value"})):
            changes[key] = lambda item, key=key, value=value: next(iter(item["NetworkSettings"]["Networks"].values())).update({key: value})
        for index in (2, 3):
            original = self.containers[index]
            for field, change in changes.items():
                with self.subTest(container=index, field=field):
                    changed = copy.deepcopy(original)
                    changed["State"]["StartedAt"] = "later-start"
                    change(changed)
                    self.containers[index] = changed
                    try:
                        with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
                            release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
                    finally:
                        self.containers[index] = original

    def test_legacy_explicit_mac_remains_pinned_when_network_is_unambiguous(self):
        for item in (self.prod, self.shared):
            item["Config"]["MacAddress"] = "02:42:ac:11:00:02"
        protected = self.runtime.observe(timeout=5)["protected"]
        self.scope["protected"] = protected
        for item in (self.prod, self.shared):
            endpoint = next(iter(item["NetworkSettings"]["Networks"].values()))
            with self.subTest(container=item["Id"]):
                endpoint["MacAddress"] = "02:42:ac:11:00:04"
                with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
                    release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
                endpoint["MacAddress"] = "02:42:ac:11:00:02"

    def test_explicit_staging_compose_mac_is_checked_against_actual_endpoint(self):
        for item, service_name in ((self.backend, "backend"), (self.pg, "postgres")):
            for level in ("service", "network"):
                with self.subTest(service=service_name, level=level):
                    original_composed = copy.deepcopy(self.composed)
                    self.composed["networks"] = {"default": {"name": "staging_default"}}
                    service = self.composed["services"][service_name]
                    if level == "service":
                        service["mac_address"] = "02:42:AC:11:00:02"
                    else:
                        service["networks"] = {"default": {"mac_address": "02:42:AC:11:00:02"}}
                    self.scope["configuration_fingerprint"] = release.fingerprint({
                        "compose": release.normalized_configuration(self.composed),
                        "backend_host_config": self.backend["HostConfig"]})
                    endpoint = item["NetworkSettings"]["Networks"]["staging_default"]
                    try:
                        release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
                        endpoint["MacAddress"] = "02:42:ac:11:00:04"
                        with self.assertRaisesRegex(release.Refused, "^STAGING_IDENTITY_MISMATCH$"):
                            self.runtime.observe(timeout=5)
                    finally:
                        endpoint["MacAddress"] = "02:42:ac:11:00:02"
                        self.composed = original_composed

    def test_unavailable_or_missing_protected_containers_remain_blocked(self):
        for index in (1, 2, 3):
            original = self.containers[index]
            for state in ({"Running": False}, {"Restarting": True}, {"Paused": True}, None):
                with self.subTest(container=index, state=state):
                    if state is None:
                        self.containers.pop(index)
                    else:
                        changed = copy.deepcopy(original)
                        changed["State"].update(state)
                        self.containers[index] = changed
                    try:
                        with self.assertRaises(release.Refused):
                            release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
                    finally:
                        if state is None:
                            self.containers.insert(index, original)
                        else:
                            self.containers[index] = original

    def test_postgres_volume_and_network_identity_drift_remain_blocked(self):
        original_pg, original_backend = copy.deepcopy(self.pg), copy.deepcopy(self.backend)
        for field in ("volume", "network"):
            with self.subTest(field=field):
                self.pg["State"]["StartedAt"] = "later-start"
                if field == "volume":
                    self.pg["Mounts"][0]["Name"] = "other-volume"
                else:
                    for item in (self.pg, self.backend):
                        item["NetworkSettings"]["Networks"]["staging_default"]["NetworkID"] = "replacement-network-id"
                with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
                    release.validate_snapshot(self.runtime.observe(timeout=5), self.scope, self.current["id"])
                self.pg.clear()
                self.pg.update(copy.deepcopy(original_pg))
                self.backend.clear()
                self.backend.update(copy.deepcopy(original_backend))

    def test_completed_restart_does_not_bypass_unknown_or_staging_identity(self):
        self.prod["State"]["StartedAt"] = "later-start"
        self.prod["RestartCount"] += 1
        next(iter(self.prod["NetworkSettings"]["Networks"].values())).update(
            EndpointID="new-runtime-endpoint", MacAddress="02:42:ac:11:00:03")
        with tempfile.TemporaryDirectory() as temp:
            store = release.Store(Path(temp) / "state")
            with store.locked():
                store.write({"scope": release.fingerprint(self.scope), "status": "UNKNOWN", "accepted": self.current,
                             "operation": None, "reason": "DOCKER_OUTCOME_UNDEFINED"})
            controller = release.Controller(self.scope, store, self.runtime)
            with self.assertRaisesRegex(release.Refused, "^OPERATOR_RECONCILIATION_REQUIRED$"):
                controller.deploy(self.candidate, expected_current=self.current["id"], owner_approved=True)
            with self.assertRaisesRegex(release.Refused, "^OPERATOR_RECONCILIATION_REQUIRED$"):
                controller.rollback(owner_approved=True)
            self.assertEqual(store.read()["status"], "UNKNOWN")
            with store.locked():
                state = store.read()
                state["status"], state["reason"] = "ACCEPTED", None
                store.write(state)
            self.backend["Config"]["Labels"]["com.docker.compose.project.working_dir"] = "/other-target"
            with self.assertRaisesRegex(release.Refused, "^STAGING_IDENTITY_MISMATCH$"):
                controller.deploy(self.candidate, expected_current=self.current["id"], owner_approved=True)
            self.runtime.configuration.select.assert_not_called()
            self.assertFalse(any(argv[:2] == ["docker", "compose"] and "up" in argv for argv, *_ in self.calls))

    def test_db_credentials_and_url_are_compared_without_raw_error(self):
        self.env["DB_PASSWORD"] = "other-secret"
        self.backend["Config"]["Env"] = [key + "=" + value for key, value in self.env.items()]
        with self.assertRaisesRegex(release.Refused, "^DATABASE_BINDING_MISMATCH$"):
            self.runtime.observe(timeout=5)

    def test_db_system_id_drift_is_not_trusted_from_scope(self):
        self.db["system_id"] = "different"
        snapshot = self.runtime.observe(timeout=5)
        with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
            release.validate_snapshot(snapshot, self.scope, self.current["id"])

    def test_image_labels_are_compared_to_preverified_artifact_record(self):
        self.assertEqual(self.runtime.image(self.candidate["id"], timeout=5), self.candidate)
        self.label_source = "https://example.invalid/foreign"
        with self.assertRaisesRegex(release.Refused, "^IMAGE_PROVENANCE_MISMATCH$"):
            self.runtime.image(self.candidate["id"], timeout=5)

    def test_inspected_image_id_os_and_architecture_must_match(self):
        _, raw = self.fake_command(['docker', 'image', 'inspect', self.candidate['id']], timeout=5)
        original = json.loads(raw)
        for key, wrong in [('Id', self.current['id']), ('Os', 'windows'), ('Architecture', 'arm64')]:
            with self.subTest(key=key):
                inspected = copy.deepcopy(original)
                inspected[0][key] = wrong
                with mock.patch.object(self.runtime, 'runner', return_value=(0, json.dumps(inspected).encode())):
                    with self.assertRaisesRegex(release.Refused, '^IMAGE_PROVENANCE_MISMATCH$'):
                        self.runtime.image(self.candidate['id'], timeout=5)

    def test_postgres_and_production_changes_are_measured(self):
        self.prod["State"]["Running"] = False
        snapshot = self.runtime.observe(timeout=5)
        with self.assertRaisesRegex(release.Refused, "^PROTECTED_RESOURCES_CHANGED$"):
            release.validate_snapshot(snapshot, self.scope, self.current["id"])

    def test_duplicate_container_and_unsafe_backend_privileges_refused(self):
        self.backend["HostConfig"]["Privileged"] = True
        with self.assertRaisesRegex(release.Refused, "^BACKEND_PRIVILEGES_UNSAFE$"):
            self.runtime.observe(timeout=5)

    def test_backend_host_and_dns_overrides_cannot_redirect_database(self):
        for key, value in (("ExtraHosts", ["postgres:production-ip"]), ("Dns", ["foreign-dns"]),
                           ("DnsSearch", ["foreign-domain"]), ("DnsOptions", ["ndots:5"])):
            with self.subTest(key=key):
                self.backend["HostConfig"][key] = value
                with self.assertRaisesRegex(release.Refused, "^BACKEND_PRIVILEGES_UNSAFE$"):
                    self.runtime.observe(timeout=5)
                del self.backend["HostConfig"][key]

    def test_added_capabilities_devices_and_host_namespaces_are_forbidden(self):
        for key, value in (("CapAdd", ["SYS_ADMIN"]), ("Devices", [{}]), ("DeviceRequests", [{}]),
                           ("VolumesFrom", ["production"]), ("PidMode", "host"), ("IpcMode", "host"),
                           ("UsernsMode", "host"), ("CgroupnsMode", "host"), ("SecurityOpt", ["seccomp=unconfined"])):
            with self.subTest(key=key):
                self.backend["HostConfig"][key] = value
                with self.assertRaisesRegex(release.Refused, "^BACKEND_PRIVILEGES_UNSAFE$"):
                    self.runtime.observe(timeout=5)
                del self.backend["HostConfig"][key]

    def test_stopped_or_restarting_backend_is_not_a_running_observation(self):
        self.backend["State"]["Running"] = False
        self.assertFalse(self.runtime.observe(timeout=5)["backend"]["running"])
        self.backend["State"].update(Running=True, Restarting=True)
        self.assertFalse(self.runtime.observe(timeout=5)["backend"]["running"])

    def test_competing_postgres_alias_cannot_redirect_database(self):
        self.shared["NetworkSettings"]["Networks"]["staging_default"] = {
            "NetworkID": "staging_default-id", "IPAddress": "foreign-ip", "Aliases": ["postgres"]}
        with self.assertRaisesRegex(release.Refused, "^DATABASE_BINDING_MISMATCH$"):
            self.runtime.observe(timeout=5)

    def test_competing_dns_name_or_container_name_cannot_redirect_database(self):
        for field in ("DNSNames", "Name", "Hostname"):
            with self.subTest(field=field):
                shared = copy.deepcopy(self.shared)
                shared["NetworkSettings"]["Networks"]["staging_default"] = {
                    "NetworkID": "staging_default-id", "IPAddress": "foreign-ip", "Aliases": []}
                if field == "DNSNames":
                    shared["NetworkSettings"]["Networks"]["staging_default"][field] = ["POSTGRES"]
                elif field == "Name":
                    shared[field] = "/postgres"
                else:
                    shared["Config"][field] = "postgres"
                self.containers[-1] = shared
                with self.assertRaisesRegex(release.Refused, "^DATABASE_BINDING_MISMATCH$"):
                    self.runtime.observe(timeout=5)
        self.containers[-1] = self.shared

    def test_actual_backend_host_config_drift_is_measured(self):
        self.backend["HostConfig"]["PortBindings"] = {"8080/tcp": [{"HostPort": "9999"}]}
        snapshot = self.runtime.observe(timeout=5)
        with self.assertRaisesRegex(release.Refused, "^STAGING_IDENTITY_MISMATCH$"):
            release.validate_snapshot(snapshot, self.scope, self.current["id"])

    def test_postgres_alias_or_network_id_mismatch_refused(self):
        endpoint = self.pg["NetworkSettings"]["Networks"]["staging_default"]
        endpoint["Aliases"] = []
        with self.assertRaisesRegex(release.Refused, "^DATABASE_BINDING_MISMATCH$"):
            self.runtime.observe(timeout=5)
        endpoint["Aliases"] = ["postgres"]
        endpoint["NetworkID"] = "foreign-network"
        with self.assertRaisesRegex(release.Refused, "^DATABASE_BINDING_MISMATCH$"):
            self.runtime.observe(timeout=5)

    def test_arbitrary_production_or_postgres_command_refused_before_runner(self):
        command = release.replacement(self.scope, self.candidate)
        for service in ("postgres", "production"):
            with self.subTest(service=service):
                command["argv"][-1] = service
                with self.assertRaisesRegex(release.Refused, "^UNSAFE_REPLACEMENT_COMMAND$"):
                    self.runtime.replace(command, timeout=120)
        self.assertEqual(self.calls, [])

    def test_nonzero_docker_status_is_not_success(self):
        self.runtime.observe(timeout=5)
        self.fail_replacement = True
        with self.assertRaisesRegex(release.Refused, "^COMMAND_FAILED$"):
            self.runtime.replace(release.replacement(self.scope, self.candidate), timeout=120)

    def test_persisted_image_drift_is_not_masked_by_command_environment(self):
        self.composed["services"]["backend"]["image"] = self.candidate["id"]
        with self.assertRaisesRegex(release.Refused, "^RELEASE_CONFIGURATION_MISMATCH$"):
            self.runtime.observe(timeout=5)

    def test_release_variable_cannot_change_postgres_configuration(self):
        self.runtime.observe(timeout=5)
        original = self.runtime.configuration.select.side_effect

        def corrupt(record):
            original(record)
            self.composed["services"]["postgres"]["image"] = "foreign:postgres"

        self.runtime.configuration.select.side_effect = corrupt
        with self.assertRaisesRegex(release.Refused, "^STAGING_IDENTITY_MISMATCH$"):
            self.runtime.replace(release.replacement(self.scope, self.candidate), timeout=120)
        self.assertFalse(any("up" in call[0] for call in self.calls))

    def test_http_probes_are_get_without_redirects_or_raw_output(self):
        self.http_status = 302
        self.assertFalse(self.runtime.ready(self.current, timeout=5))
        for argv, environment, payload, timeout in self.calls:
            self.assertEqual(argv[0], "curl")
            self.assertNotIn("-L", argv)
            self.assertNotIn("-I", argv)
            self.assertIn("--disable", argv)
            self.assertIn("--max-time", argv)
            self.assertLessEqual(timeout, 5)

    def test_stale_remaining_budget_is_not_reused_between_commands(self):
        clock, timeouts = {"now": 0.0}, []
        original = self.fake_command

        def delayed(argv, **kwargs):
            timeouts.append(kwargs["timeout"])
            clock["now"] += 1
            return original(argv, **kwargs)

        self.runtime.runner = delayed
        with mock.patch.object(release.time, "monotonic", lambda: clock["now"]):
            self.runtime.observe(timeout=5)
        self.assertEqual(timeouts, [5, 4, 3, 2])


class ExecutorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir="/private/tmp" if sys.platform == "darwin" else None)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.files = release.ProtectedFiles(owner=os.geteuid())
        self.runner = release.ProcessRunner()

    def python(self, source, **kwargs):
        return self.runner([sys.executable, "-c", source], timeout=kwargs.pop("timeout", 3), **kwargs)

    def test_real_process_stdin_stdout_stderr_and_clean_environment(self):
        source = "import os,sys; sys.stderr.write('PRIVATE_SECRET'); sys.stdout.buffer.write(sys.stdin.buffer.read()); assert 'BACKEND_IMAGE' not in os.environ"
        with mock.patch.dict(os.environ, {"BACKEND_IMAGE": "wrong", "DOCKER_HOST": "ssh://wrong"}):
            code, output = self.python(source, payload=b"a" * 200000)
        self.assertEqual((code, output), (0, b"a" * 200000))
        self.assertNotIn(b"PRIVATE_SECRET", output)

    def test_real_process_deadline_and_output_limit(self):
        with self.assertRaises(TimeoutError):
            self.python("import time; time.sleep(10)", timeout=0.03)
        with self.assertRaisesRegex(release.Refused, "^COMMAND_OUTPUT_LIMIT$"):
            self.python("import sys; sys.stdout.buffer.write(b'x' * (9 * 1024 * 1024))")

    def test_descendant_holding_pipe_cannot_escape_deadline(self):
        source = "import subprocess,sys; subprocess.Popen([sys.executable,'-c','import time; time.sleep(10)'])"
        with self.assertRaises(TimeoutError):
            self.python(source, timeout=0.05)

    def test_no_remote_docker_or_arbitrary_environment_override(self):
        with self.assertRaisesRegex(release.Refused, "^LOCAL_DOCKER_REQUIRED$"):
            release.ProcessRunner(docker_host="ssh://foreign")
        with self.assertRaisesRegex(release.Refused, "^COMMAND_ENVIRONMENT_INVALID$"):
            self.python("pass", environment={"BACKEND_IMAGE": "wrong"})

    def test_configuration_roundtrip_preserves_secret_bytes_and_modes(self):
        path = self.directory / ".env"
        original = b"# config\nPOSTGRES_PASSWORD='private value'\nBACKEND_IMAGE=old\nexport APP_VERSION = old\nOTHER=unchanged\n"
        path.write_bytes(original)
        path.chmod(0o600)
        data = fixture()
        configuration = release.ReleaseConfiguration(self.directory, self.files)
        configuration.select(data["candidate"])
        selected = path.read_bytes()
        self.assertIn(b"POSTGRES_PASSWORD='private value'\n", selected)
        self.assertIn(b"BACKEND_IMAGE=" + data["candidate"]["id"].encode() + b"\n", selected)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        configuration.select(data["current"])
        self.assertIn(b"BACKEND_IMAGE=" + data["current"]["id"].encode() + b"\n", path.read_bytes())

    def test_duplicate_assignments_and_symlinks_refused(self):
        path = self.directory / ".env"
        path.write_bytes(b"BACKEND_IMAGE=x\nBACKEND_IMAGE=y\n")
        path.chmod(0o600)
        configuration = release.ReleaseConfiguration(self.directory, self.files)
        with self.assertRaisesRegex(release.Refused, "^ENVIRONMENT_INVALID$"):
            configuration.select(fixture()["candidate"])
        original = b"SECRET='multiline\nBACKEND_IMAGE=secret\n'\n"
        path.write_bytes(original)
        with self.assertRaisesRegex(release.Refused, "^ENVIRONMENT_INVALID$"):
            configuration.select(fixture()["candidate"])
        self.assertEqual(path.read_bytes(), original)
        saved = self.directory / "saved"
        path.rename(saved)
        path.symlink_to(saved)
        with self.assertRaisesRegex(release.Refused, "^UNSAFE_TARGET_PATH$"):
            configuration.select(fixture()["candidate"])

    def test_no_enrollment_no_execution_and_target_must_match(self):
        path = self.directory / "registration.json"
        with self.assertRaisesRegex(release.Refused, "^LEGACY_TRANSITION_REQUIRED$"):
            release.registration("staging", path=path, files=self.files)
        path.write_text(json.dumps({"target": "staging", "transition_approved": False}))
        path.chmod(0o600)
        with self.assertRaisesRegex(release.Refused, "^LEGACY_TRANSITION_REQUIRED$"):
            release.registration("staging", path=path, files=self.files)
        path.write_text(json.dumps({"target": "staging", "transition_approved": True}))
        with self.assertRaisesRegex(release.Refused, "^REGISTERED_TARGET_MISMATCH$"):
            release.registration("production", path=path, files=self.files)

    def test_ssh_fixed_command_transport_loss_and_private_output(self):
        calls = []

        def transport(argv, **kwargs):
            calls.append((argv, kwargs))
            return 255, b"PRIVATE_SECRET"

        result = release.ssh_action("staging", {"action": "deploy"}, runner=transport)
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertNotIn("PRIVATE_SECRET", json.dumps(result))
        self.assertEqual(len(calls), 1)
        self.assertIn("StrictHostKeyChecking=yes", calls[0][0])
        self.assertEqual(calls[0][0][-1], "/usr/bin/python3 -B /opt/hookah-bot/staging_release.py worker --target staging")
        with self.assertRaisesRegex(release.Refused, "^TARGET_INVALID$"):
            release.ssh_action("staging; production", {}, runner=transport)

    def test_remote_admission_failure_remains_failed_not_unknown(self):
        for reason in (release.LIVE_REFUSAL, "REGISTERED_TARGET_MISMATCH", "V125_SCHEMA_REQUIRED"):
            outcome = {"status": "FAILED", "reason": reason}
            result = release.ssh_action("staging", {}, runner=lambda *a, **kw: (2, json.dumps(outcome).encode()))
            self.assertEqual(result, outcome)
        result = release.ssh_action("staging", {}, runner=lambda *a, **kw: (255, b'{"status":"SUCCESS"}'))
        self.assertEqual(result["status"], "UNKNOWN")

    def test_interrupted_ssh_has_unknown_result_without_retry(self):
        runner = mock.Mock(side_effect=KeyboardInterrupt)
        result = release.ssh_action("staging", {}, runner=runner)
        self.assertEqual(result, {"status": "UNKNOWN", "reason": "SSH_OUTCOME_UNDEFINED_OPERATOR_REQUIRED"})
        self.assertEqual(runner.call_count, 1)

    def test_registered_parallel_attempt_reports_busy_without_claiming_unknown(self):
        data = fixture()
        scope = data["scope"]
        scope.update(directory=str(self.directory), compose_file=str(self.directory / "docker-compose.yml"))
        store = release.Store(self.directory / ".staging-release")
        with store.locked():
            store.write({"scope": release.fingerprint(scope), "status": "APPLYING", "accepted": data["current"],
                         "operation": {"kind": "deploy", "from": data["current"], "to": data["candidate"]}})
            outcome = release.registered_action({"target": "staging", "action": "status"},
                        {"target": "staging", "transition_approved": True, "scope": scope}, files=self.files)
            self.assertEqual(outcome, {"status": "FAILED", "reason": "DEPLOYMENT_BUSY"})
        self.assertEqual(store.read()["status"], "APPLYING")

    def test_initialize_does_not_accept_backend_stopped_after_health(self):
        data = fixture()
        scope = data["scope"]
        scope.update(directory=str(self.directory), compose_file=str(self.directory / "docker-compose.yml"))
        data["snapshot"].update(directory=scope["directory"], compose_file=scope["compose_file"])
        runtime = release.FixtureRuntime(data["snapshot"], {data["current"]["id"]: data["current"]})

        def ready(*args, **kwargs):
            runtime.snapshot["backend"]["running"] = False
            return True

        runtime.ready = ready
        with mock.patch.object(release, "CommandRuntime", return_value=runtime):
            outcome = release.registered_action({"target": "staging", "action": "initialize",
                        "candidate": data["current"], "owner_approved": True},
                        {"target": "staging", "transition_approved": True, "scope": scope}, files=self.files)
        self.assertEqual(outcome, {"status": "FAILED", "reason": "CURRENT_IMAGE_NOT_READY"})
        self.assertFalse((self.directory / ".staging-release" / "state.json").exists())

    def test_unknown_status_reports_intent_without_inventing_current_image(self):
        data = fixture()
        scope = data["scope"]
        scope.update(directory=str(self.directory), compose_file=str(self.directory / "docker-compose.yml"))
        store = release.Store(self.directory / ".staging-release")
        with store.locked():
            store.write({"scope": release.fingerprint(scope), "status": "UNKNOWN", "accepted": data["current"],
                         "operation": {"kind": "deploy", "from": data["current"], "to": data["candidate"]}})
        with mock.patch.object(release, "CommandRuntime") as constructor:
            outcome = release.registered_action({"target": "staging", "action": "status"},
                        {"target": "staging", "transition_approved": True, "scope": scope}, files=self.files)
            constructor.assert_not_called()
        self.assertEqual(outcome["status"], "UNKNOWN")
        self.assertEqual(outcome["intended_image"], data["candidate"]["id"])
        self.assertNotIn("current_image", outcome)

    def test_known_status_does_not_hide_actual_production_drift(self):
        data = fixture()
        scope = data["scope"]
        scope.update(directory=str(self.directory), compose_file=str(self.directory / "docker-compose.yml"))
        data["snapshot"].update(directory=scope["directory"], compose_file=scope["compose_file"])
        data["snapshot"]["protected"]["production"]["fingerprint"] = "9" * 64
        store = release.Store(self.directory / ".staging-release")
        with store.locked():
            store.write({"scope": release.fingerprint(scope), "status": "ACCEPTED", "accepted": data["current"], "operation": None})
        runtime = release.FixtureRuntime(data["snapshot"], {data["current"]["id"]: data["current"]})
        with mock.patch.object(release, "CommandRuntime", return_value=runtime):
            outcome = release.registered_action({"target": "staging", "action": "status"},
                        {"target": "staging", "transition_approved": True, "scope": scope}, files=self.files)
        self.assertEqual(outcome, {"status": "FAILED", "reason": "PROTECTED_RESOURCES_CHANGED"})


if __name__ == "__main__":
    # Optional fixture generation for the documented offline demo.
    if len(sys.argv) == 3 and sys.argv[1] == "--write-fixture":
        Path(sys.argv[2]).write_text(json.dumps(fixture(), indent=2) + "\n")
    else:
        unittest.main()
