#!/usr/bin/env python3
"""Production builder -> local pipe -> real bash -s -> production dispatch validation.

Receipt lookups, the SSH/process boundary, Linux remote supervisor and leaf actions
are fixtures. Uploads retain the actual receiver; only its environmental
preconditions are explicit fixtures. The supervisor captures the leaf log and emits a canonical
identity-bound acknowledgement; the production acknowledgement validator remains
real. Separate Linux tests own subreaper/lock/crash coverage. No network, database,
Docker, Caddy or cutover state is used. Never print child output.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
BASE = "67fbfd4d587244712b15e974f485e08316ffd486"
MAGIC = b"V126_INTERNAL_REMOTE_ENVELOPE_V1\n"
BASH = shutil.which("bash")
CANARY = "HT12Y_PRIVATE_synthetic_only"
HASH = "b" * 64
FIELDS = [
    "ENVELOPE_VALIDATED", "ACTION", "RUN_ID", "RELEASE_SHA", "STAGING_PATH",
    "SCRIPT_SHA256", "V126_IMAGE_ID", "OPERATION_KIND", "OPERATION_NAME",
    "PREDECESSOR_STAGE", "PREDECESSOR_HASH", "AUTHORIZATION_GATE",
    "AUTHORIZATION_HASH", "INTENT_HASH", "BASELINE_DATABASE_URL_SHA256", "DATABASE_TARGET_IDENTITY_SHA256",
    "BASELINE_MAINTENANCE_IDENTITIES_SHA256", "BASELINE_COMPOSE_SOURCE_SHA256",
    "BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256", "BASELINE_ADMISSION_SOURCE_SHA256",
    "BASELINE_CADDY_SHA256", "BASELINE_ENV_SHA256", "CADDY_ORIGINAL_SHA256",
    "CADDY_CANDIDATE_SHA256", "CADDY_DIFF_SHA256", "CADDY_ACTIVATION_SHA256",
    "MAINTENANCE_SMOKE_SHA256", "MAINTENANCE_OFF_SHA256",
]
# The immutable HT12Y before source predates the DB target identity field.
LEGACY_FIELDS = [name for name in FIELDS if name != "DATABASE_TARGET_IDENTITY_SHA256"]


def run(argv, **kwargs):
    try:
        return subprocess.run(argv, capture_output=True, timeout=30, **kwargs)
    except (OSError, subprocess.TimeoutExpired):
        raise AssertionError("fixture process unavailable or timed out; output withheld") from None


def transport(stream_path, command):
    """Replace SSH only: child stdin is a real pipe, never a sourced-function stdin."""
    if command != ["ssh", "fixture-no-network", "bash", "-s"]:
        return 98
    root = Path(os.environ["FRAME_ROOT"])
    payload = Path(stream_path).read_bytes()
    (root / "stream").write_bytes(payload)
    if os.environ["FRAME_FAULT"] == "transport":
        print("PASS\nARTIFACT\tfixture\t" + HASH)
        return 79
    result = run([os.environ["FRAME_BASH"], "-s"], input=payload, env=os.environ.copy())
    output = result.stdout
    if os.environ["FRAME_FAULT"].startswith("ack-"):
        prefix = b"\nREMOTE_OPERATION_ACK\t"
        if output.count(prefix) != 1:
            return 97
        log, encoded = output.split(prefix)
        acknowledgement = json.loads(encoded)
        fault = os.environ["FRAME_FAULT"]
        if fault == "ack-identity":
            acknowledgement["identity"]["intent_sha256"] = "c" * 64
        elif fault == "ack-children":
            acknowledgement["children"] = "UNKNOWN"
        elif fault == "ack-log":
            log += b"changed log\n"
        encoded = (json.dumps(acknowledgement, sort_keys=True, separators=(",", ":")) + "\n").encode()
        output = log + prefix + encoded
        if fault == "ack-missing":
            output = log
        elif fault == "ack-duplicate":
            output += prefix + encoded
    sys.stdout.buffer.write(output)
    sys.stderr.buffer.write(result.stderr)
    return result.returncode


DRIVER = r'''source "$1"
STATE_DIR="$FRAME_ROOT"
SCRIPT_PATH="$FRAME_ROOT/body.sh"
SCRIPT_SHA256="$FRAME_BODY_SHA"
RUN_ID=fixture-run
RELEASE_SHA=67fbfd4d587244712b15e974f485e08316ffd486
STAGING_PATH=/fixture/staging
V126_IMAGE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
REMOTE=fixture-no-network
ACTIVE_OPERATION_KIND=STAGE
ACTIVE_OPERATION_NAME=BASELINE_VERIFIED
ACTIVE_PREDECESSOR_STAGE=NONE
ACTIVE_PREDECESSOR_HASH=NONE
ACTIVE_AUTHORIZATION_GATE=NONE
ACTIVE_AUTHORIZATION_HASH=NONE
ACTIVE_INTENT_HASH=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
case "$FRAME_OPERATION" in
  baseline) ;;
  preflight-upload | image-upload)
    ACTIVE_OPERATION_NAME=FINAL_V125_PREFLIGHT_PASSED
    ACTIVE_PREDECESSOR_STAGE=QUIESCED_BACKUP_REHEARSED
    if [[ "$FRAME_OPERATION" == image-upload ]]; then
      ACTIVE_OPERATION_NAME=V126_IMAGE_TRANSFERRED_AND_VERIFIED
      ACTIVE_PREDECESSOR_STAGE=V126_MAINTENANCE_CONFIG_PREPARED
    fi
    ACTIVE_PREDECESSOR_HASH="$ACTIVE_INTENT_HASH"
    ACTIVE_AUTHORIZATION_GATE=A
    ACTIVE_AUTHORIZATION_HASH="$ACTIVE_INTENT_HASH"
    V126_LOCAL_UPLOAD_FD=9
    exec 9<"$FRAME_ROOT/upload"
    rm "$FRAME_ROOT/upload"
    command printf '%s\n' 'PATH_REPLACEMENT_MUST_NOT_UPLOAD' > "$FRAME_ROOT/upload"
    ;;
  recovery)
    ACTIVE_OPERATION_KIND=RECOVERY
    ACTIVE_OPERATION_NAME=pre-v126
    ACTIVE_PREDECESSOR_STAGE=BASELINE_VERIFIED
    ACTIVE_PREDECESSOR_HASH="$ACTIVE_INTENT_HASH"
    ACTIVE_AUTHORIZATION_GATE=RECOVERY
    ACTIVE_AUTHORIZATION_HASH="$ACTIVE_INTENT_HASH"
    ;;
  late-recovery | full-dr)
    ACTIVE_OPERATION_KIND=RECOVERY
    ACTIVE_OPERATION_NAME=post-v126-stop
    ACTIVE_PREDECESSOR_STAGE=V126_SCHEMA_RUNTIME_GATE_PASSED
    ACTIVE_PREDECESSOR_HASH="$ACTIVE_INTENT_HASH"
    ACTIVE_AUTHORIZATION_GATE=RECOVERY
    ACTIVE_AUTHORIZATION_HASH="$ACTIVE_INTENT_HASH"
    if [[ "$FRAME_OPERATION" == full-dr ]]; then
      ACTIVE_OPERATION_NAME=verify-full-dr
      ACTIVE_PREDECESSOR_STAGE=RECOVERY_POST_V126_STOP
    fi
    ;;
esac
receipt_path() {
  if [[ "$FRAME_OPERATION" == preflight-upload || "$FRAME_OPERATION" == image-upload ]]; then
    if [[ "$1" == CADDY_CANDIDATE_INSTALLED_AND_RELOADED ||
          ( "$FRAME_OPERATION" == image-upload && "$1" == V126_MAINTENANCE_CONFIG_PREPARED ) ]]; then
      command printf '%s/present\n' "$FRAME_ROOT"
    else
      command printf '%s/absent\n' "$FRAME_ROOT"
    fi
  elif [[ "$FRAME_OPERATION" == late-recovery || "$FRAME_OPERATION" == full-dr ]]; then
    command printf '%s/present\n' "$FRAME_ROOT"
  else
    command printf '%s/absent\n' "$FRAME_ROOT"
  fi
}
verify_receipt() { return 0; }
receipt_artifact_hash() { command printf '%s\n' "$ACTIVE_INTENT_HASH"; }
run_tracked_command_with_input() {
  [[ "$1" == remote-ssh ]] || return 98
  shift
  python3 "$FRAME_TEST" --transport "$@"
}
cat() {
  command cat "$@" || return $?
  case "$FRAME_FAULT:$#" in
    loader-producer:0 | body-producer:1) return 71 ;;
  esac
}
chmod() {
  [[ "$FRAME_FAULT" != chmod ]] || return 72
  command chmod "$@"
}
printf() {
  builtin printf "$@" || return $?
  if [[ "$FRAME_FAULT" == fields-producer && $# -eq $((FRAME_FIELD_COUNT + 2)) ]]; then return 73; fi
  if [[ "$FRAME_FAULT" == args-producer && $# == 4 ]]; then return 74; fi
}
shift
# Conditional invocation deliberately disables implicit errexit in run_remote.
if run_remote "$@"; then
  command printf 'ACCEPTED\n' > "$FRAME_ROOT/accepted"
else
  exit "$?"
fi
'''


class RemoteStdin(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (ROOT / "v126-cutover.sh").read_bytes()
        result = run(["git", "show", f"{BASE}:scripts/v126-cutover.sh"], cwd=ROOT)
        if result.returncode:
            raise AssertionError("immutable before source unavailable; full Git history required")
        cls.old = result.stdout
        version = run([BASH, "--version"])
        if version.returncode:
            raise AssertionError("Bash version unavailable")
        print("HT12Y real stdin: " + version.stdout.decode().splitlines()[0], flush=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ht12y-remote-stdin-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "tmp").mkdir(mode=0o700)
        (self.root / "present").touch(mode=0o600)
        self.env = {"PATH": os.environ["PATH"], "HOME": str(self.root), "LC_ALL": "C",
                    "FRAME_ROOT": str(self.root), "FRAME_TEST": str(Path(__file__).resolve()),
                    "FRAME_BASH": BASH, "FRAME_OPERATION": "baseline", "FRAME_FAULT": "none"}
        # Defence in depth: an accidental external leaf call must fail locally.
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        for name in ("ssh", "docker", "caddy", "systemctl", "psql", "rsync", "curl", "sudo"):
            stub = bin_dir / name
            stub.write_text('#!/bin/sh\nprintf EXTERNAL >> "$FRAME_ROOT/external"\nexit 99\n')
            stub.chmod(0o700)
        self.env["PATH"] = str(bin_dir) + os.pathsep + self.env["PATH"]

    def body(self, source):
        # Keep the complete production dispatcher/validators. Stub only its leaf actions.
        dispatch = source.split(b"remote_dispatch_enveloped() {\n", 1)[1]
        leaves = re.findall(rb"\) (remote_[a-z0-9_]+) \"\$@\" ;;", dispatch)
        self.assertEqual(len(leaves), 19)
        recorder = r'''
printf '%s' "$remote_body_content" > "$FRAME_ROOT/verified-body"
frame_record() {
  python3 - "$@" <<'PY'
import json, os, sys
from pathlib import Path
data = {"args": sys.argv[1:], "envelope": {k: v for k, v in os.environ.items()
        if k.startswith("V126_INTERNAL_REMOTE_")}}
with (Path(os.environ["FRAME_ROOT"]) / "dispatch").open("a") as out:
    out.write(json.dumps(data) + "\n")
PY
}
'''.encode()
        for leaf in leaves:
            recorder += leaf + b'() { frame_record ' + leaf + b' "$@"; }\n'
        if b"remote_receive_upload() {" in source:
            recorder += r'''
# The actual receiver and its hash/size/O_EXCL/framing checks remain intact.
# Only independently exercised target/daemon preconditions are synthetic.
remote_initialize_compose() { :; }
remote_require_run_root() { printf '%s\n' "$FRAME_RUN_ROOT"; }
remote_assert_public_drain() { [[ "$FRAME_FAULT" != upload-drain ]]; }
remote_assert_zero_writer() { [[ "$1" == '125:0:0' && "$FRAME_FAULT" != upload-writer ]]; }
remote_verify_proof() { [[ "$FRAME_FAULT" != upload-proof ]]; }
remote_verify_maintenance_env_binding() { [[ "$FRAME_FAULT" != upload-environment ]]; }
'''.encode()
        if b"remote_supervise_action() {" in source:
            recorder += r'''
# Only the OS supervisor is synthetic; call the real leaf dispatcher and retain
# its exact output bytes for the real parent-side acknowledgement consumer.
remote_supervise_action() {
  local fixture_log="$FRAME_ROOT/supervisor.log"
  local fixture_status
  if [[ "$1" == image-upload || "$1" == preflight-upload ]]; then
    exec 8<&0
  fi
  if remote_dispatch_action "$@" > "$fixture_log"; then
    fixture_status=0
  else
    fixture_status=$?
  fi
  python3 - "$fixture_log" "$fixture_status" <<'PY'
import hashlib, json, os, sys
from pathlib import Path
fields = {
    "run_id": "RUN_ID", "release_sha": "RELEASE_SHA", "script_sha256": "SCRIPT_SHA256",
    "intent_sha256": "INTENT_HASH", "kind": "OPERATION_KIND", "name": "OPERATION_NAME",
    "action": "ACTION",
}
identity = {key: os.environ["V126_INTERNAL_REMOTE_" + env] for key, env in fields.items()}
canonical = lambda data: (json.dumps(data, sort_keys=True, separators=(",", ":")) + "\n").encode()
log = Path(sys.argv[1]).read_bytes()
status = int(sys.argv[2])
acknowledgement = dict(identity=identity, operation_id=hashlib.sha256(canonical(identity)).hexdigest(),
                      exit=status, outcome="SUCCEEDED" if status == 0 else "UNKNOWN",
                      children="REAPED", log_sha256=hashlib.sha256(log).hexdigest(),
                      completed_at="2026-09-09T00:00:00+00:00")
sys.stdout.buffer.write(log + b"\nREMOTE_OPERATION_ACK\t" + canonical(acknowledgement))
PY
  return "$fixture_status"
}
'''.encode()
        return source + recorder + b"# trailing body bytes: \\ ' \" $() ; \t\n\n\n"

    def build(self, *, old=False, operation="baseline", args=None, fault="none", payload=None):
        source = self.old if old else self.source
        self.fields = LEGACY_FIELDS if old else FIELDS
        body = self.body(source)
        (self.root / "source.sh").write_bytes(source)
        (self.root / "body.sh").write_bytes(body)
        (self.root / "driver.sh").write_text(DRIVER)
        self.env.update(FRAME_BODY_SHA=hashlib.sha256(body).hexdigest(),
                        FRAME_OPERATION=operation, FRAME_FAULT=fault,
                        FRAME_FIELD_COUNT=str(len(self.fields)))
        action = {"baseline": "baseline", "recovery": "recover-pre-v126",
                  "late-recovery": "recover-post-v126-stop", "full-dr": "verify-full-dr",
                  "preflight-upload": "preflight-upload", "image-upload": "image-upload"}[operation]
        if operation in ("preflight-upload", "image-upload"):
            payload = payload if payload is not None else b"synthetic upload\0bytes\xff\n"
            (self.root / "upload").write_bytes(payload)
            (self.root / "upload").chmod(0o400)
            self.run_root = self.root / ("remote-run-" + str(len(list(self.root.glob("remote-run-*")))))
            self.run_root.mkdir(mode=0o700)
            self.env["FRAME_RUN_ROOT"] = str(self.run_root)
            args = args if args is not None else ["fixture:" + BASE, hashlib.sha256(payload).hexdigest(), str(len(payload))]
        arguments = [action, "/fixture/staging", "fixture-run", BASE] + (args or [])
        existing = {file.name: hashlib.sha256(file.read_bytes()).hexdigest()
                    for file in (self.root / "tmp").iterdir()}
        result = run([BASH, str(self.root / "driver.sh"), str(self.root / "source.sh"), *arguments],
                     env=self.env)
        self.private(result)
        remaining = {file.name: file for file in (self.root / "tmp").iterdir()}
        for name, digest in existing.items():
            self.assertIn(name, remaining, "previous attempt evidence was removed")
            self.assertEqual(hashlib.sha256(remaining[name].read_bytes()).hexdigest(), digest,
                             "previous attempt evidence changed")
        for name in set(remaining) - set(existing):
            file = remaining[name]
            metadata = file.lstat()
            self.assertTrue(name.startswith("remote-stream-") and name.endswith(".sh.output"),
                            "producer did not clean its executable stream")
            self.assertTrue(stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1 and
                            stat.S_IMODE(metadata.st_mode) == 0o400 and metadata.st_uid == os.getuid(),
                            "retained failed transport evidence is not protected")
            self.assertNotEqual(result.returncode, 0, "successful transport retained failure evidence")
        self.assertFalse((self.root / "external").exists(), "external tool reached")
        return result, body

    def private(self, result):
        self.assertFalse(CANARY.encode() in result.stdout + result.stderr,
                         "secret canary reached diagnostics (output withheld)")

    def reset_markers(self):
        for name in ("dispatch", "verified-body", "accepted"):
            (self.root / name).unlink(missing_ok=True)

    def execute(self, payload):
        self.reset_markers()
        result = run([BASH, "-s"], input=payload, env=self.env)
        self.private(result)
        self.assertFalse((self.root / "external").exists(), "external tool reached")
        return result

    def stream_parts(self):
        payload = (self.root / "stream").read_bytes()
        source = (self.root / "source.sh").read_bytes()
        loader = source.split(b"    cat <<'REMOTE_LOADER'\n", 1)[1].split(b"\nREMOTE_LOADER\n", 1)[0] + b"\n"
        self.assertTrue(payload.startswith(loader), "builder changed the production loader bytes")
        field_count = len(self.fields)
        lines = payload[len(loader):].split(b"\n", field_count + 1)
        count = int(lines[field_count])
        args_body = lines[field_count + 1].split(b"\n", count)
        return loader, lines[:field_count + 1], args_body[:count], args_body[count]

    def denied(self, result, *, before_source=False):
        self.assertNotEqual(result.returncode, 0, "failure was accepted")
        self.assertFalse((self.root / "dispatch").exists(), "rejection reached a leaf dispatcher")
        self.assertFalse((self.root / "accepted").exists(), "failure accepted by caller")
        if before_source:
            self.assertFalse((self.root / "verified-body").exists(), "unverified body sourced")

    def test_01_immutable_before_and_separate_instrumentation(self):
        result, _ = self.build(old=True)
        self.denied(result, before_source=True)
        self.assertEqual(result.returncode, 4)
        self.assertIn(b"invalid internal remote envelope magic", result.stderr)
        raw = (self.root / "stream").read_bytes()
        needle = b'  IFS= read -r "$1" || loader_die "truncated internal remote envelope: $1"\n'
        loader, _, _, _ = self.stream_parts()
        self.assertEqual(loader.count(needle), 1)
        probe = raw.replace(needle, needle + b'  if [[ "$1" == magic ]]; then printf "FIRST_READ=<%s>\\n" "$magic" >&2; fi\n', 1)
        result = self.execute(probe)
        self.assertEqual(result.returncode, 4)
        self.assertIn(b"FIRST_READ=<loader_read action>", result.stderr)
        self.denied(result, before_source=True)
        print("HT12Y immutable before=exit4; separate probe first read=loader_read action", flush=True)

    def test_02_exact_fields_body_arguments_and_once(self):
        args = ["", " space value ", "quotes'\"\\backslash", "кириллица", "-n", "*?[]",
                CANARY, '$(touch "$FRAME_ROOT/external")', '`touch "$FRAME_ROOT/external"`',
                '; touch "$FRAME_ROOT/external"; #']
        result, body = self.build(args=args)
        self.assertEqual(result.returncode, 0, "production stdin path failed (output withheld)")
        self.assertTrue((self.root / "verified-body").read_bytes() == body, "body byte mismatch")
        rows = (self.root / "dispatch").read_text().splitlines()
        self.assertEqual(len(rows), 1)
        row = json.loads(rows[0])
        self.assertTrue(row["args"] == ["remote_baseline", "/fixture/staging", "fixture-run", BASE, *args],
                        "argument bytes differ (values withheld)")
        _, fields, wire_args, wire_body = self.stream_parts()
        expected = [MAGIC.rstrip(b"\n").decode(), "baseline", "fixture-run", BASE, "/fixture/staging",
                    hashlib.sha256(body).hexdigest(), "sha256:" + "a" * 64, "STAGE", "BASELINE_VERIFIED",
                    "NONE", "NONE", "NONE", "NONE", HASH]
        expected += ["NONE"] * (len(FIELDS) - len(expected))
        self.assertEqual(len(fields), len(FIELDS) + 1)
        self.assertTrue(fields[:len(FIELDS)] == [v.encode() for v in expected], "production envelope bytes differ")
        self.assertTrue(wire_args == [v.encode() for v in row["args"][1:]], "wire argument bytes differ")
        self.assertTrue(wire_body == body, "wire body bytes differ")
        for name, value in zip(FIELDS, expected):
            self.assertTrue(row["envelope"]["V126_INTERNAL_REMOTE_" + name] == value,
                            "received field differs: " + name)

    def test_03_recovery_same_transport(self):
        for operation, leaf in [("recovery", "remote_recover_pre_v126"),
                                ("late-recovery", "remote_recover_post_v126_stop"),
                                ("full-dr", "remote_verify_full_dr")]:
            with self.subTest(operation=operation):
                self.reset_markers()
                result, body = self.build(operation=operation)
                self.assertEqual(result.returncode, 0, "recovery framing failed")
                self.assertTrue((self.root / "verified-body").read_bytes() == body)
                rows = (self.root / "dispatch").read_text().splitlines()
                self.assertEqual(len(rows), 1)
                row = json.loads(rows[0])
                self.assertEqual(row["args"][0], leaf)
                _, fields, _, _ = self.stream_parts()
                for name, value in zip(FIELDS, fields):
                    self.assertTrue(row["envelope"]["V126_INTERNAL_REMOTE_" + name].encode() == value,
                                    "recovery field differs: " + name)
                baseline_start = FIELDS.index("BASELINE_DATABASE_URL_SHA256")
                caddy_start = FIELDS.index("CADDY_ORIGINAL_SHA256")
                self.assertTrue(all(v == HASH.encode() for v in fields[baseline_start:caddy_start]))
                self.assertTrue(all(v == ("NONE" if operation == "recovery" else HASH).encode()
                                    for v in fields[caddy_start:len(FIELDS)]))

    def test_07_loader_consumer_failure_is_not_hidden_by_valid_hash(self):
        self.build()
        payload = (self.root / "stream").read_bytes()
        hasher = self.root / "bin" / "sha256sum"
        hasher.write_text('#!/bin/sh\ncat >/dev/null\nprintf "%s  -\\n" "$FRAME_BODY_SHA"\nexit 76\n')
        hasher.chmod(0o700)
        self.denied(self.execute(payload), before_source=True)

    def test_08_nul_bytes_are_rejected_before_normalization(self):
        self.build()
        loader, fields, arguments, body = self.stream_parts()
        for index in range(len(fields)):
            with self.subTest(nul_field=index):
                changed = fields.copy()
                changed[index] += b"\0"
                self.denied(self.execute(loader + b"\n".join(changed + arguments) + b"\n" + body),
                            before_source=True)
        for index in range(len(arguments)):
            with self.subTest(nul_argument=index):
                changed = arguments.copy()
                changed[index] += b"\0"
                self.denied(self.execute(loader + b"\n".join(fields + changed) + b"\n" + body),
                            before_source=True)
        for index in (0, 100, len(body)):
            with self.subTest(nul_body=index):
                changed = body[:index] + b"\0" + body[index:]
                self.denied(self.execute(loader + b"\n".join(fields + arguments) + b"\n" + changed),
                            before_source=True)

    def test_09_eof_and_trailing_body_newlines_are_exact(self):
        self.build()
        loader, fields, arguments, body = self.stream_parts()
        for newlines in (0, 1, 5):
            with self.subTest(newlines=newlines):
                changed = body.rstrip(b"\n") + b"\n" * newlines
                envelope = fields.copy()
                envelope[5] = hashlib.sha256(changed).hexdigest().encode()
                result = self.execute(loader + b"\n".join(envelope + arguments) + b"\n" + changed)
                self.assertEqual(result.returncode, 0, "newline-preserving EOF read failed")
                self.assertTrue((self.root / "verified-body").read_bytes() == changed)
                self.assertEqual(len((self.root / "dispatch").read_text().splitlines()), 1)

    def test_04_wire_mutations_fail_closed(self):
        self.build()
        loader, fields, arguments, body = self.stream_parts()
        for name, index, value in [
            ("magic", 0, b"WRONG"), ("run", 2, b"other-run"), ("release", 3, b"c" * 40),
            ("path", 4, b"/other/staging"), ("hash", 5, b"0" * 64), ("image", 6, b"bad"),
            ("kind", 7, b"INVALID"), ("stage", 8, b"V125_BACKEND_STOPPED"),
            ("predecessor", 9, b"BASELINE_VERIFIED"), ("predecessor-hash", 10, HASH.encode()),
            ("gate", 11, b"A"), ("authorization", 12, HASH.encode()), ("intent", 13, b"bad"),
            ("baseline-authority", FIELDS.index("BASELINE_DATABASE_URL_SHA256"), HASH.encode()),
            ("database-target-identity", FIELDS.index("DATABASE_TARGET_IDENTITY_SHA256"), HASH.encode()),
            ("caddy-authority", FIELDS.index("CADDY_ORIGINAL_SHA256"), HASH.encode()),
            ("maintenance-authority", FIELDS.index("MAINTENANCE_SMOKE_SHA256"), HASH.encode()),
            ("arg-count", len(FIELDS), b"33"),
            ("action", 1, b"stop-backend"),
            ("action-code", 1, ('$(touch "$FRAME_ROOT/external")' + CANARY).encode()),
        ]:
            with self.subTest(mutation=name):
                changed = fields.copy()
                changed[index] = value
                payload = loader + b"\n".join(changed + arguments) + b"\n" + body
                self.denied(self.execute(payload), before_source=name in ("magic", "hash", "arg-count"))
        for index in range(len(fields)):
            with self.subTest(truncated_field=index):
                self.denied(self.execute(loader + b"\n".join(fields[:index]) + b"\n"), before_source=True)
        for index in range(len(arguments)):
            with self.subTest(truncated_argument=index):
                self.denied(self.execute(loader + b"\n".join(fields + arguments[:index]) + b"\n"),
                            before_source=True)
        for suffix in (b"", body[:-1], body + b'\ntouch "$FRAME_ROOT/external"\n'):
            with self.subTest(body_size=len(suffix)):
                self.denied(self.execute(loader + b"\n".join(fields + arguments) + b"\n" + suffix),
                            before_source=True)

    def test_05_control_arguments_are_not_executed(self):
        for control in ("\t", "\r", "\n"):
            with self.subTest(control=ord(control)):
                self.reset_markers()
                result, _ = self.build(args=[CANARY + control + 'touch "$FRAME_ROOT/external"'])
                self.denied(result, before_source=True)

    def test_06_producer_transport_and_mode_failures(self):
        for fault in ("loader-producer", "fields-producer", "args-producer", "body-producer",
                      "chmod", "transport"):
            with self.subTest(fault=fault):
                self.reset_markers()
                (self.root / "stream").unlink(missing_ok=True)
                result, _ = self.build(fault=fault)
                self.denied(result, before_source=True)
                if fault == "transport":
                    self.assertEqual(result.returncode, 79)
                    self.assertNotIn(b"PASS\nARTIFACT", result.stdout,
                                     "failed transport output reached artifact consumers")
                    self.assertTrue(any(b"PASS\nARTIFACT" in file.read_bytes()
                                        for file in (self.root / "tmp").iterdir()),
                                    "failed transport output was not retained as evidence")
                else:
                    self.assertFalse((self.root / "stream").exists(), "failed producer reached transport")

    def test_10_actual_acknowledgement_consumer_rejects_tampering(self):
        for fault in ("ack-missing", "ack-duplicate", "ack-identity", "ack-children", "ack-log"):
            with self.subTest(fault=fault):
                self.reset_markers()
                result, _ = self.build(fault=fault)
                self.assertNotEqual(result.returncode, 0, "invalid acknowledgement was accepted")
                self.assertFalse((self.root / "accepted").exists())
                self.assertEqual(len((self.root / "dispatch").read_text().splitlines()), 1,
                                 "acknowledgement failure repeated or suppressed the leaf operation")

    def test_11_actual_receiver_gets_exact_binary_anonymous_fd_stream(self):
        payload = b"synthetic\0binary\xff\n" * 8192
        for operation, filename in (("preflight-upload", "final-v125-preflight.sh.partial"),
                                    ("image-upload", "v126-image.tar.partial")):
            with self.subTest(operation=operation):
                self.reset_markers()
                result, body = self.build(operation=operation, payload=payload)
                self.assertEqual(result.returncode, 0, "actual receiver refused valid framed upload")
                self.assertEqual((self.run_root / filename).read_bytes(), payload)
                self.assertEqual(stat.S_IMODE((self.run_root / filename).stat().st_mode), 0o600)
                self.assertEqual((self.root / "upload").read_bytes(), b"PATH_REPLACEMENT_MUST_NOT_UPLOAD\n")
                self.assertEqual((self.root / "verified-body").read_bytes(), body)
                _, _, _, wire = self.stream_parts()
                self.assertEqual(wire, body + b"\0" + payload)
                self.assertNotIn(payload[:32], result.stdout + result.stderr)
                self.assertFalse((self.root / "dispatch").exists(), "an unrelated synthetic leaf was dispatched")

    def test_12_actual_upload_receiver_refuses_bad_hash_size_tail_and_preconditions(self):
        payload = b"owned exact payload\0" * 8
        digest = hashlib.sha256(payload).hexdigest()
        for case, args, fault in [
            ("wrong-hash", ["fixture:" + BASE, "0" * 64, str(len(payload))], "none"),
            ("short", ["fixture:" + BASE, digest, str(len(payload) + 1)], "none"),
            ("tail", ["fixture:" + BASE, digest, str(len(payload) - 1)], "none"),
            ("zero-size", ["fixture:" + BASE, digest, "0"], "none"),
            ("preflight-limit", ["fixture:" + BASE, digest, str(1024**2 + 1)], "none"),
            ("drain", None, "upload-drain"), ("writer", None, "upload-writer"),
        ]:
            with self.subTest(case=case):
                self.reset_markers()
                result, _ = self.build(operation="preflight-upload", payload=payload, args=args, fault=fault)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / "accepted").exists())
                self.assertNotIn(b"UPLOAD_COMPLETED", result.stdout)
                if case in ("zero-size", "preflight-limit", "drain", "writer"):
                    self.assertEqual(list(self.run_root.iterdir()), [], "refusal allocated an upload pathname")
        for fault in ("upload-proof", "upload-environment"):
            with self.subTest(fault=fault):
                self.reset_markers()
                result, _ = self.build(operation="image-upload", payload=payload, fault=fault)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(list(self.run_root.iterdir()), [])

    def test_13_upload_delimiter_and_verified_source_are_required(self):
        _, body = self.build(operation="image-upload")
        loader, fields, arguments, wire = self.stream_parts()
        content, delimiter, payload = wire.partition(b"\0")
        self.assertEqual(content, body)
        self.assertEqual(delimiter, b"\0")
        for changed in (body + payload, body[:-1] + b"\0" + payload):
            with self.subTest(size=len(changed)):
                self.denied(self.execute(loader + b"\n".join(fields + arguments) + b"\n" + changed), before_source=True)

    def test_14_existing_partial_and_symlink_are_never_overwritten(self):
        self.build(operation="preflight-upload")
        wire = (self.root / "stream").read_bytes()
        original = self.run_root / "final-v125-preflight.sh.partial"
        snapshot = original.read_bytes()
        result = self.execute(wire)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(original.read_bytes(), snapshot)
        # A fresh isolated target tests refusal of an attacker-owned alias;
        # failed/successful historical upload bytes above are never removed.
        fresh = self.root / "symlink-target"
        fresh.mkdir(mode=0o700)
        sentinel = self.root / "unchanged-sentinel"
        sentinel.write_bytes(b"unchanged own fixture")
        alias = fresh / original.name
        alias.symlink_to(sentinel)
        self.env["FRAME_RUN_ROOT"] = str(fresh)
        result = self.execute(wire)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(alias.is_symlink())
        self.assertEqual(sentinel.read_bytes(), b"unchanged own fixture")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--transport":
        raise SystemExit(transport(sys.argv[2], sys.argv[3:]))
    unittest.main(verbosity=2)
