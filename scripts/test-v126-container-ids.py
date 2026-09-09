#!/usr/bin/env python3
"""Canonical inventory regressions using sourced production collectors/comparator.

The Docker executable is the only fixture boundary, including real argv parsing.
No SSH, daemon, cutover state, stage, receipt or application is used here.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parent
BASE = "c67c364eb1c7d48a1919aabbc65f7ce7caa1d3e7"
ID = "a" * 64
COLLISION = "a" * 12 + "b" * 52  # Synthetic distinct object, never a resolved prefix.
OTHER = "c" * 64
IMAGE = "sha256:" + "1" * 64
OTHER_IMAGE = "sha256:" + "2" * 64
CANARY = "HT12Z_PRIVATE_synthetic_only"

MOCK = r'''
import json
from pathlib import Path
import sys
root = Path(__file__).parent
doc = json.loads((root / "fixture.json").read_text())
args = sys.argv[1:]
with (root / "calls.jsonl").open("a") as log:
    log.write(json.dumps(args) + "\n")
kind = ""
if args[:5] == ["compose", "--env-file", ".env", "--file", "docker-compose.yml"]:
    args = args[5:]
    full = "--no-trunc" in args
    args = [arg for arg in args if arg != "--no-trunc"]
    if args == ["ps", "--status", "running", "-q", "backend"]:
        kind = "compose-running"
    elif args == ["ps", "-aq", "backend"]:
        kind = "compose-all"
    full = full or not doc.get("compose_truncates", False)
elif args[:2] == ["ps", "-q"]:
    full = "--no-trunc" in args
    rest = [arg for arg in args[2:] if arg != "--no-trunc"]
    if rest in ([], ["--filter", "label=com.docker.compose.project=fixture",
                    "--filter", "label=com.docker.compose.service=backend"]):
        kind = "docker"
elif args[:3] == ["inspect", "--format", "{{.Image}}"] and len(args) == 4:
    kind = "inspect"
if not kind:
    sys.exit(97)  # Includes attempts to expand the metadata contract.
if kind == "inspect":
    # Exact known aliases only; no startswith resolution or first-match selection.
    aliases = {key: value for key, value in doc["images"].items()}
    aliases.update({key[:12]: value for key, value in doc["images"].items()
                    if sum(other[:12] == key[:12] for other in doc["images"]) == 1})
    if args[3] not in aliases:
        sys.stderr.write(doc["canary"])
        sys.exit(96)  # Container vanished between ps and inspect.
    output = aliases[args[3]] + "\n"
else:
    output = "".join((value if full else value[:12]) + "\n" for value in doc[kind])
fault = doc.get("fault", {})
if fault.get("kind") == kind:
    output = fault.get("stdout", output)
    sys.stderr.write(doc["canary"])
sys.stdout.write(output)
sys.exit(fault.get("exit", 0) if fault.get("kind") == kind else 0)
'''

DRIVER = r'''
source "$1"
set +u # Bash 3.2 regards an explicitly empty indexed array as unset.
REMOTE_BACKEND_IMAGE=fixture:inert
fixture_check() {
  case "$2" in
    unique)
      remote_capture_compose_ids running backend
      (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || die 'fixture bound count is not one'
      local bound="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
      remote_capture_compose_ids all backend
      (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || die 'fixture total count is not one'
      [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${bound}" ]] || die 'fixture bound identity changed'
      remote_require_unique_global_image_container "$3" "${bound}"
      ;;
    count) remote_require_global_image_count "$3" "$4" ;;
    outside)
      remote_capture_compose_ids all backend
      (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || die 'fixture bound count is not one'
      remote_require_unique_global_image_container "$3" "${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
      ;;
    running|all)
      remote_capture_compose_ids "$2" backend
      (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == $4 )) || die 'fixture Compose count mismatch'
      ;;
    filtered)
      remote_capture_docker_running_ids --filter label=com.docker.compose.project=fixture \
        --filter label=com.docker.compose.service=backend
      (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == $4 )) || die 'fixture filtered count mismatch'
      ;;
    *) exit 98 ;;
  esac
}
# Disable incidental errexit so explicit production failure propagation is exercised.
if fixture_check "$@"; then printf 'ACCEPTED\n'; else exit "$?"; fi
'''


class ContainerIds(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ht12z-ids-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        executable = self.root / "docker"
        executable.write_text(f"#!{sys.executable}\n" + MOCK)
        executable.chmod(0o700)
        self.doc = {"compose-running": [ID], "compose-all": [ID], "docker": [ID],
                    "images": {ID: IMAGE}, "canary": CANARY}

    def run_fixture(self, action="unique", count=1, source=None, private=True):
        (self.root / "fixture.json").write_text(json.dumps(self.doc))
        (self.root / "calls.jsonl").write_text("")
        result = subprocess.run(
            ["bash", "-c", DRIVER, "fixture", str(source or ROOT / "v126-cutover.sh"),
             action, IMAGE, str(count)], cwd=self.root,
            env={"PATH": str(self.root) + os.pathsep + os.environ["PATH"],
                 "HOME": str(self.root), "LC_ALL": "C"},
            capture_output=True, text=True, timeout=30)
        if private:
            for value in (CANARY, ID, COLLISION, OTHER, OTHER_IMAGE):
                self.assertFalse(value in result.stdout + result.stderr, "inventory/secret leaked")
        self.calls = [json.loads(line) for line in (self.root / "calls.jsonl").read_text().splitlines()]
        return result

    def accepted(self, result):
        self.assertEqual(result.returncode, 0, "production fixture refused; output withheld")
        self.assertEqual(result.stdout, "ACCEPTED\n")

    def refused(self, result, reason=None):
        self.assertEqual(result.returncode, 4, "expected production fail-closed exit4; output withheld")
        self.assertNotIn("ACCEPTED", result.stdout)
        if reason:
            self.assertTrue(reason in result.stderr, "wrong refusal boundary; output withheld")

    def test_before_unchanged_64_12_refuses_same_container(self):
        old = subprocess.run(["git", "show", f"{BASE}:scripts/v126-cutover.sh"],
                             cwd=ROOT, capture_output=True, check=True).stdout
        path = self.root / "before.sh"
        path.write_bytes(old)
        self.refused(self.run_fixture(source=path), "outside the bound Compose backend")
        self.assertIn(["ps", "-q"], self.calls)
        self.assertIn(["inspect", "--format", "{{.Image}}", ID[:12]], self.calls)
        print("before: immutable c67 Compose64 / Docker12 -> count1, identity refusal exit4", flush=True)

    def test_after_both_collectors_request_full_ids(self):
        for truncates in (False, True):
            with self.subTest(compose_truncates=truncates):
                self.doc["compose_truncates"] = truncates
                self.accepted(self.run_fixture())
                inventories = [call for call in self.calls if call[0] != "inspect"]
                self.assertEqual(len(inventories), 3)
                self.assertTrue(all("--no-trunc" in call for call in inventories))
                self.assertIn(["inspect", "--format", "{{.Image}}", ID], self.calls)

    def test_distinct_full_ids_with_same_first_12_are_not_equal(self):
        self.doc.update(docker=[COLLISION], images={COLLISION: IMAGE})
        self.refused(self.run_fixture(), "outside the bound Compose backend")
        self.doc.update(docker=[ID, COLLISION], images={ID: IMAGE, COLLISION: IMAGE})
        self.refused(self.run_fixture(), "global running-container count mismatch")

    def test_unique_image_outside_bound_compose_is_refused(self):
        self.doc.update(docker=[ID, OTHER], images={ID: OTHER_IMAGE, OTHER: IMAGE})
        self.refused(self.run_fixture(), "outside the bound Compose backend")
        self.doc.update(docker=[ID], images={ID: OTHER_IMAGE})
        self.refused(self.run_fixture(), "global running-container count mismatch")
        self.doc.update({"compose-running": [], "docker": [OTHER], "images": {OTHER: IMAGE}})
        self.refused(self.run_fixture("outside"), "outside the bound Compose backend")

    def test_count_zero_one_many_for_every_inventory(self):
        for count in (0, 1, 2):
            ids = [ID, OTHER][:count]
            self.doc.update({"compose-running": ids, "compose-all": ids, "docker": ids,
                             "images": {value: IMAGE for value in ids}})
            for action in ("count", "running", "all", "filtered"):
                with self.subTest(count=count, action=action):
                    self.accepted(self.run_fixture(action, count))
                    self.refused(self.run_fixture(action, (count + 1) % 3))
            result = self.run_fixture()
            (self.accepted if count == 1 else self.refused)(result)

    def test_compose_running_and_all_must_be_same_unique_object(self):
        for ids in ([], [OTHER], [ID, OTHER]):
            with self.subTest(size=len(ids)):
                self.doc["compose-all"] = ids
                self.refused(self.run_fixture())

    def test_inventory_duplicates_and_invalid_ids(self):
        bad_outputs = [ID + "\n" + ID + "\n", ID[:12] + "\n", ID[:63] + "\n",
                       ID + "0\n", ID.upper() + "\n", CANARY + "\n", ID + " \n",
                       ID + "\r\n", ID[:20] + "\x00" + ID[20:] + "\n",
                       ID + "\n\n" + OTHER + "\n"]
        for kind, action in (("docker", "count"), ("compose-running", "running"),
                             ("compose-all", "all")):
            for output in bad_outputs:
                with self.subTest(kind=kind, fixture=bad_outputs.index(output)):
                    self.doc["fault"] = {"kind": kind, "stdout": output}
                    self.refused(self.run_fixture(action))
                    self.assertFalse(any(call[0] == "inspect" for call in self.calls))

    def test_ps_error_with_empty_or_plausible_stdout_is_not_success(self):
        for kind, action in (("docker", "count"), ("compose-running", "running"),
                             ("compose-all", "all")):
            for output, count in (("", 0), (ID + "\n", 1)):
                with self.subTest(kind=kind, count=count):
                    self.doc["fault"] = {"kind": kind, "stdout": output, "exit": 91}
                    self.refused(self.run_fixture(action, count), "inventory failed")

    def test_inventory_filter_failure_cannot_hide_behind_valid_stdout(self):
        filter_command = self.root / "tr"
        filter_command.write_text(f"#!/bin/sh\nprintf '%s\\n' '{ID}'\nexit 93\n")
        filter_command.chmod(0o700)
        for action in ("running", "all", "count"):
            with self.subTest(action=action):
                self.refused(self.run_fixture(action), "inventory failed")

    def test_inspect_errors_malformed_and_disappearance_are_not_zero(self):
        for output, status in (("", 92), (IMAGE + "\n", 92), ("", 0), (CANARY, 0),
                               (OTHER_IMAGE[:19], 0), (OTHER_IMAGE + "\n" + IMAGE, 0),
                               (OTHER_IMAGE + "\x00", 0)):
            for count in (0, 1):
                with self.subTest(status=status, count=count, size=len(output)):
                    self.doc["fault"] = {"kind": "inspect", "stdout": output, "exit": status}
                    self.refused(self.run_fixture("count", count))
        del self.doc["fault"]
        self.doc["images"] = {}
        self.refused(self.run_fixture("count", 0), "became unobservable")


class RealCli(unittest.TestCase):
    """Only the existing compose job's disposable GitHub-hosted Linux daemon."""

    def cli(self, *args):
        result = subprocess.run(["docker", *args], cwd=self.root, env=self.env,
                                capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, "real Docker command failed; output withheld")
        return result.stdout.strip()

    def production(self, action="unique", count=1, source=None):
        result = subprocess.run(["bash", "-c", DRIVER, "fixture",
                                 str(source or ROOT / "v126-cutover.sh"), action,
                                 self.image_id, str(count)], cwd=self.root, env=self.env,
                                capture_output=True, text=True, timeout=30)
        self.assertNotIn(CANARY, result.stdout + result.stderr)
        return result

    def check(self, action, count, accepted=True):
        result = self.production(action, count)
        self.assertEqual(result.returncode, 0 if accepted else 4, "real production guard mismatch")
        self.assertEqual("ACCEPTED" in result.stdout, accepted)

    def cleanup_containers(self):
        # Prearmed before create; only these exact test-owned names/project may be removed.
        self.cli("compose", "--env-file", ".env", "--file", "docker-compose.yml", "down")
        ids = self.cli("ps", "-aq", "--no-trunc", "--filter", f"name=^/{self.outside}$")
        if ids:
            self.assertTrue(len(ids) == 64 and all(c in "0123456789abcdef" for c in ids))
            self.cli("rm", "--force", ids)
        self.assertTrue(self.cli("ps", "-aq", "--no-trunc") == "", "test daemon cleanup incomplete")
        print("real CLI: owned containers removed; empty daemon verified", flush=True)

    def test_real_cli_identity_and_counts(self):
        self.assertTrue(sys.platform == "linux" and os.environ.get("GITHUB_ACTIONS") == "true"
                        and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted",
                        "real CLI requires the isolated GitHub-hosted Linux runner; no local fallback")
        self.assertFalse(any(os.environ.get(key) for key in
                             ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")),
                         "external Docker binding refused")
        temp = tempfile.TemporaryDirectory(prefix="ht12z-real-cli-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.env = {"PATH": os.environ["PATH"], "HOME": os.environ["HOME"], "LC_ALL": "C"}
        self.assertEqual(self.cli("context", "show"), "default")
        # IDs only; refuse any pre-existing container before creating or inspecting anything.
        self.assertTrue(self.cli("ps", "-aq", "--no-trunc") == "", "isolated empty daemon required")
        print("real CLI: " + self.cli("version", "--format", "client={{.Client.Version}} server={{.Server.Version}}"), flush=True)
        print("real CLI: " + self.cli("compose", "version"), flush=True)
        project = "ht12z-" + uuid.uuid4().hex
        self.outside = project + "-outside"
        (self.root / ".env").write_text("")
        (self.root / "docker-compose.yml").write_text(f'''name: {project}
services:
  backend:
    image: busybox:1.37.0
    entrypoint: ["sleep", "300"]
    network_mode: none
    read_only: true
    restart: "no"
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
''')
        self.addCleanup(self.cleanup_containers)
        self.image_id = IMAGE
        self.check("count", 0)
        self.cli("compose", "--env-file", ".env", "--file", "docker-compose.yml", "up", "-d", "backend")
        full = self.cli("compose", "--env-file", ".env", "--file", "docker-compose.yml",
                        "ps", "--status", "running", "-q", "backend")
        short = self.cli("ps", "-q")
        self.assertEqual((len(full), len(short)), (64, 12))
        self.assertEqual(short, full[:12])  # Representation evidence only, never production authority.
        self.image_id = self.cli("inspect", "--format", "{{.Image}}", full)
        old = self.root / "before.sh"
        old.write_bytes(subprocess.run(["git", "show", f"{BASE}:scripts/v126-cutover.sh"],
                                       cwd=ROOT, capture_output=True, check=True).stdout)
        before = self.production(source=old)
        self.assertEqual(before.returncode, 4)
        self.assertIn("outside the bound Compose backend", before.stderr)
        self.check("unique", 1)
        print("real CLI: unchanged before64/12 refused; canonical after64/64 accepted", flush=True)
        for count in (2, 1):
            self.cli("compose", "--env-file", ".env", "--file", "docker-compose.yml",
                     "up", "-d", "--scale", f"backend={count}", "backend")
            for action in ("running", "all", "count"):
                self.check(action, count)
            self.check("unique", count, accepted=count == 1)
        self.cli("compose", "--env-file", ".env", "--file", "docker-compose.yml", "stop", "backend")
        self.check("running", 0)
        self.check("all", 1)
        self.check("count", 0)
        self.cli("run", "--detach", "--name", self.outside, "--network", "none", "--read-only",
                 "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                 "--entrypoint", "sleep", "busybox:1.37.0", "300")
        self.check("count", 1)
        outside = self.production("outside")
        self.assertEqual(outside.returncode, 4)
        self.assertIn("outside the bound Compose backend", outside.stderr)
        print("real CLI: counts0/1/2, stopped Compose scope and outside-bound image refusal PASS", flush=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["--before-only"]:
        suite = unittest.TestSuite([ContainerIds("test_before_unchanged_64_12_refuses_same_container")])
    elif sys.argv[1:] == ["--real-cli"]:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(RealCli)
    elif not sys.argv[1:]:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(ContainerIds)
    else:
        raise SystemExit("usage: test-v126-container-ids.py [--before-only|--real-cli]")
    sys.exit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
