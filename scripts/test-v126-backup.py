#!/usr/bin/env python3
"""Real PG17 backup regression; only synthetic, task-labelled local Docker resources.

Sources the production function (including the immutable before revision). Authority
preconditions (including semantic database identity, tested separately) and host paths
are fixture bindings, not a cutover authorization. The
Compose boundary forwards the original sh argv to one exact source container. Docker
inventories are restricted to this test's label; positive PostgreSQL tools are real.
"""
import atexit
import hashlib
import io
import json
import os
from pathlib import Path
import re
import select
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import uuid

ROOT = Path(__file__).resolve().parent
BASE = "724dbe931c0af969b761cc174eb289a3e46b17ca"
LABEL = "com.hookah.ht12aa.fixture"
CANARY = "HT12AA_synthetic_password_canary"
USER = "ht12aa_source"
ODD_USER = "ht12aa role ' \" $x"
DATABASE = "ht12aa db ' \" ; $(touch /tmp/ht12aa-injected) `id`"

# Sourced by the real image entrypoint after its socket-only server is ready.
# Two bounded FIFO handshakes expose the init/shutdown transition without sleeps
# that guess how long PostgreSQL initialization takes.
INIT_TRANSITION = r'''
mkfifo /tmp/ht12aa-init-gate
exec 9<> /tmp/ht12aa-init-gate
eval "$(declare -f docker_temp_server_stop | sed '1s/docker_temp_server_stop/ht12aa_temp_stop/')"
docker_temp_server_stop() {
  ht12aa_temp_stop
  touch /tmp/ht12aa-init-stopped
  IFS= read -r -t 45 release <&9
  exec 9>&-
}
touch /tmp/ht12aa-init-ready
IFS= read -r -t 45 release <&9
'''

DIAGNOSTIC_STAGES = frozenset((
    "driver_started", "globals_command", "remote_backup_rehearsal",
    "remote_backup_rehearsal_success", "trap_postcondition",
    "cleanup_container_postcondition", "cleanup_volume_postcondition", "complete",
))
FIXTURE_EVENTS = frozenset((
    "baseline", "pre-drain-gate", "quiesced-gate", "user", "psql", "pg_dump",
    "pg_restore", "pg_dumpall", "checksum-write", "checksum-check", "volume-create",
    "container-create", "copy-dump", "rehearsal-pg_isready", "rehearsal-createdb",
    "rehearsal-pg_restore", "rehearsal-psql", "data-verified-globals-not-applied",
    "container-remove", "volume-remove",
))


PRODUCTION_STAGES = (
    "prerequisites", "source-inspection", "dump", "restore-list", "dump-checksum",
    "globals-export", "globals-checksum", "capacity", "resource-creation", "readiness",
    "copy-dump", "database-create", "restore", "restored-version", "restored-data",
    "production-verified", "post-validation", "proof-generation", "complete",
)
CLEANUP_EVENTS = frozenset((
    "cleanup-start", "container-cleanup", "container-failed", "volume-cleanup",
    "volume-failed", "cleanup-ok", "cleanup-failed", "data-globals-check", "data-globals-ok",
))


def causal_diagnostic(trace, phase, code):
    # A new private pipe per invocation; no sidecar, old proof, or raw child output
    # is accepted as causal evidence. Validate the entire bounded stream first.
    unknown = "production=UNKNOWN_REDACTED root_cause=UNKNOWN_REDACTED cleanup=UNKNOWN_REDACTED cleanup_event=UNKNOWN_REDACTED cleanup_failure=UNKNOWN_REDACTED fixture_check=UNKNOWN_REDACTED failure=unknown"
    if (phase not in ("pre-drain", "quiesced") or not isinstance(trace, bytes) or
            not 0 < len(trace) <= 2048 or not trace.endswith(b"\n")):
        return unknown
    records = trace[:-1].split(b"\n")
    if records.pop(0) != ("begin-" + phase).encode() or len(records) > 40:
        return unknown
    allowed = {event.encode(): event for event in (*PRODUCTION_STAGES, *CLEANUP_EVENTS,
                 *("failed-" + stage for stage in PRODUCTION_STAGES))}
    if not records or any(record not in allowed for record in records):
        return unknown
    sequence = tuple(stage for stage in PRODUCTION_STAGES
                     if phase == "pre-drain" or not stage.startswith("globals-"))
    position = -1
    production = "NONE"
    root = "NONE"
    cleanup = "NONE"
    cleanup_failure = "NONE"
    cleanup_event = "NONE"
    fixture_check = "NONE"
    cleaning = False
    closed = False
    for record in records:
        event = allowed[record]
        if event.startswith("failed-"):
            failed_stage = event.removeprefix("failed-")
            if (root != "NONE" or failed_stage != production or cleaning or
                    production in ("NONE", "production-verified", "complete") or
                    (cleanup != "NONE" and production not in ("post-validation", "proof-generation"))):
                return unknown
            root = failed_stage
            closed = True
        elif event in PRODUCTION_STAGES:
            if cleaning or closed or position + 1 >= len(sequence) or event != sequence[position + 1]:
                return unknown
            if event == "post-validation" and cleanup != "cleanup-ok":
                return unknown
            position += 1
            production = event
        elif event == "cleanup-start":
            if cleaning or production == "NONE" or cleanup != "NONE":
                return unknown
            cleaning = True
        elif not cleaning:
            return unknown
        elif event in ("data-globals-check", "data-globals-ok"):
            if cleanup != "container-cleanup" or fixture_check != ("NONE" if event == "data-globals-check" else "data-globals-check"):
                return unknown
            fixture_check = event
        elif event == "container-cleanup":
            if cleanup != "NONE":
                return unknown
            cleanup = event
            cleanup_event = event
        elif event == "container-failed":
            if cleanup != "container-cleanup" or cleanup_failure != "NONE":
                return unknown
            cleanup_failure = "container-cleanup"
        elif event == "volume-cleanup":
            if cleanup not in ("NONE", "container-cleanup"):
                return unknown
            cleanup = event
            cleanup_event = event
        elif event == "volume-failed":
            if cleanup != "volume-cleanup":
                return unknown
            if cleanup_failure == "NONE":
                cleanup_failure = "volume-cleanup"
        else:
            if event == "cleanup-ok" and fixture_check == "data-globals-check":
                return unknown
            if (event == "cleanup-ok") != (cleanup_failure == "NONE"):
                return unknown
            cleanup = event
            cleaning = False
            closed = root != "NONE" or event == "cleanup-failed"
    # Entries prove reachability only. A failure witness names the in-process
    # active stage, never the last successfully delivered stage. Without one or
    # an explicit completed-production/failed-cleanup boundary, fail attribution
    # closed even when every delivered record is syntactically valid.
    if code == 0:
        if production != "complete" or root != "NONE" or cleanup != "cleanup-ok":
            return unknown
        failure = "none"
    elif root != "NONE":
        failure = "postcondition" if root in ("post-validation", "proof-generation") else "production"
        if cleaning:
            cleanup = "UNKNOWN_REDACTED"
    elif production == "complete" and cleanup == "cleanup-ok":
        failure = "postcondition"
    elif production == "production-verified" and cleanup == "cleanup-failed":
        failure = "cleanup"
    else:
        return unknown
    return (f"production={production} root_cause={root} cleanup={cleanup} "
            f"cleanup_event={cleanup_event} cleanup_failure={cleanup_failure} fixture_check={fixture_check} failure={failure}")


def canonical_pass_proof(path, backup, run_id, release_sha):
    # Exact writer: remote_backup_rehearsal -> remote_write_proof. Value and
    # artifact authority: remote_verify_proof + backup_checks/reconcile_backup_archive.
    # This fixture prerequisite accepts only that complete quiesced artifact;
    # neither a result-line search nor a writable checksum alone proves PASS.
    keys = ("run_id", "release_sha", "phase", "dump_sha256", "inventory_sha256", "rehearsal_sha256",
            "rehearsal_container", "rehearsal_volume", "rehearsal_owner", "rehearsal_cleanup", "result")

    def read_proof_file(target, limit):
        info = target.lstat()
        if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or
                info.st_uid != os.getuid() or info.st_gid != os.getgid()):
            raise ValueError
        with target.open("rb") as stream:
            return stream.read(limit + 1)

    try:
        proof = read_proof_file(path, 4096)
        checksum = read_proof_file(Path(str(path) + ".sha256"), 65)
        if not 0 < len(proof) <= 4096 or not proof.endswith(b"\n"):
            return False
        rows = proof[:-1].decode("ascii").split("\n")
        if len(rows) != len(keys) or any(row.count("=") != 1 for row in rows):
            return False
        fields = [row.split("=") for row in rows]
        if tuple(key for key, _ in fields) != keys:
            return False
        values = dict(fields)
        if checksum != hashlib.sha256(proof).hexdigest().encode() + b"\n":
            return False
        expected = dict(run_id=run_id, release_sha=release_sha, phase="quiesced",
                        rehearsal_cleanup="COMPLETE", result="PASS")
        if any(values[key] != value for key, value in expected.items()):
            return False
        for key, suffix in (("dump_sha256", ".dump"), ("inventory_sha256", ".dump.pg_restore.list"),
                            ("rehearsal_sha256", ".dump.rehearsal.txt")):
            if not re.fullmatch("[0-9a-f]{64}", values[key]):
                return False
            digest = hashlib.sha256()
            with (backup / ("quiesced" + suffix)).open("rb") as stream:
                for chunk in iter(lambda: stream.read(65536), b""):
                    digest.update(chunk)
            if values[key] != digest.hexdigest():
                return False
        if not re.fullmatch("[0-9a-f]{64}", values["rehearsal_container"]):
            return False
        safe_run = re.sub("[^a-z0-9-]", "-", run_id)
        owner = re.fullmatch("v126:" + re.escape(release_sha + ":" + safe_run) + r":quiesced:([0-9]+)",
                             values["rehearsal_owner"])
        return bool(owner and values["rehearsal_volume"] ==
                    "hookah-v126-" + safe_run + "-quiesced-" + owner[1])
    except (OSError, ValueError, UnicodeError):
        return False


def diagnostic_value(path, allowed, offset=0):
    # Bound the read as well as the output; never interpolate untrusted file bytes.
    try:
        with path.open("rb") as stream:
            size = stream.seek(0, os.SEEK_END)
            if size <= offset:
                return "NONE"
            start = max(offset, size - 256)
            stream.seek(start)
            tail = stream.read(256)
    except OSError:
        return "UNAVAILABLE"
    lines = tail.splitlines()
    if not tail.endswith(b"\n") or not lines or (start > offset and len(lines) == 1):
        return "UNKNOWN_REDACTED"
    value = lines[-1]
    return next((item for item in allowed if value == item.encode()), "UNKNOWN_REDACTED")


def driver_diagnostic(result, phase, root, event_offset, trace=None, initialized=True, mode=None, causal_trace=None):
    phase = phase if phase in ("pre-drain", "quiesced") else "UNKNOWN_REDACTED"
    stage = diagnostic_value(root / "driver-stage", DIAGNOSTIC_STAGES)
    event = diagnostic_value(root / "events", FIXTURE_EVENTS, event_offset)
    sequence = ("remote_backup_rehearsal", "remote_backup_rehearsal_success", "trap_postcondition",
                "cleanup_container_postcondition", "cleanup_volume_postcondition", "complete")
    records = trace.splitlines() if trace and len(trace) <= 256 and trace.endswith(b"\n") else []
    expected = ("globals_command",) if mode == "globals" else sequence
    trusted = bool(records) and len(records) <= len(expected) and all(
        record in (name.encode() + b" ok", name.encode() + b" failed")
        for record, name in zip(records, expected))
    metadata = "unavailable"
    if trusted:
        observed = expected[len(records) - 1]
        metadata = "ok" if initialized and stage == observed else "unavailable"
        if not initialized or any(record.endswith(b" failed") for record in records):
            metadata = "failed"
        stage = observed
    if mode == "backup" and result.returncode == 0:
        # Only a completed child proves success when all metadata is lost.
        rehearsal = "success"
        if not trusted or stage != "complete":
            metadata = "unavailable"
    elif trusted and stage in sequence[1:]:
        rehearsal = "success"
    elif trusted and stage == sequence[0] and 0 < result.returncode <= 127:
        # A successful production call must create this proof before returning.
        # Its absence can rule out a postcondition exit, even if a leftover EXIT
        # trap changes that exit code and the pipe suffix is lost. Presence alone
        # cannot prove success: production can still fail while emitting artifacts.
        rehearsal = "unknown"
        try:
            (root / "run" / (phase + "-backup-rehearsed.proof")).lstat()
        except FileNotFoundError:
            if phase != "UNKNOWN_REDACTED":
                rehearsal = "nonzero"
        except OSError:
            pass
        if rehearsal == "unknown":
            metadata = "unavailable"
    else:
        rehearsal = "unknown"
        metadata = "unavailable"
    causal = causal_diagnostic(causal_trace, phase, result.returncode)
    suffix = "" if metadata == "ok" else f" metadata={metadata}"
    return (f"HT12AA_DIAG phase={phase} stage={stage} rehearsal={rehearsal} "
            f"exit={result.returncode:d} last_event={event} {causal}{suffix}")


def run(args, **kwargs):
    if kwargs.pop("group", False):
        timeout = kwargs.pop("timeout", 180)
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              start_new_session=True, **kwargs) as process:
            try:
                stdout, stderr = process.communicate(timeout=timeout)
                return subprocess.CompletedProcess(args, process.returncode, stdout, stderr)
            except BaseException:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.communicate()
                raise
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=kwargs.pop("timeout", 180), **kwargs)


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def ident(value):
    return '"' + value.replace('"', '""') + '"'


def literal(value):
    return "'" + value.replace("'", "''") + "'"


DRIVER = r'''
source "$1"
fixture="$2"
phase="$3"
mode="$4"
boundary() { python3 "$HT12AA_HELPER" --boundary "$fixture/config.json" "$@"; }
remote_require_run_root() { printf '%s\n' "$fixture/run"; }
remote_verify_baseline_authority() { boundary event baseline; }
remote_assert_database_target() { REMOTE_DATABASE_TARGET_IDENTITY_SHA256="$(hash_text fixture-semantic-database-target)"; }
remote_assert_compose_backend_image() { [[ "$1" == "fixture:$V125_SOURCE_SHA" ]]; }
remote_verify_proof() { [[ "$1" == "$fixture/run/baseline-authority.proof" ]]; boundary event pre-drain-gate; }
remote_assert_zero_writer() { [[ "$1" == 125:0:0 ]]; boundary event quiesced-gate; }
remote_backup_root() { printf '%s\n' "$fixture/backups/v126/$HT12AA_RELEASE/$HT12AA_RUN"; }
remote_capture_compose_ids() {
  [[ "$*" == 'running postgres' ]]
  REMOTE_CAPTURED_CONTAINER_IDS=("$HT12AA_SOURCE")
}
remote_compose() { boundary compose "$@"; }
docker() { boundary docker "$@"; }
sudo() { boundary sudo "$@"; }
stat() { boundary stat "$@"; }
df() { boundary df "$@"; }
sha256sum() { boundary checksum "$@"; }
causal_write() {
  local value
  case "$1" in
    __HT12AA_CAUSAL_ALLOWLIST__) value="$1" ;;
    *) value=UNKNOWN_REDACTED ;;
  esac
  # Only the writer subshell ignores PIPE. The production shell keeps its
  # signal dispositions, errexit and EXIT cleanup; even a writer signal/status
  # is contained by the conditional subshell command. Redirect its entire
  # stdout lifetime so Bash cannot flush a failed printf after restoring stdout.
  ( trap '' PIPE; printf '%s\n' "$value" ) 2>/dev/null >&"$HT12AA_CAUSAL_FD" || :
  return 0
}
remote_backup_failure() {
  if [[ "${HT12AA_CAUSAL_FAILED:-no}" == no ]]; then
    case "${HT12AA_CAUSAL_STAGE:-}" in
      __HT12AA_FAILURE_STAGES__)
        HT12AA_CAUSAL_FAILED=yes
        causal_write "failed-$HT12AA_CAUSAL_STAGE"
        ;;
    esac
  fi
  return 0
}
remote_backup_diagnostic() {
  case "$1" in
    begin-pre-drain|begin-quiesced) HT12AA_CAUSAL_STAGE=; HT12AA_CAUSAL_FAILED=no ;;
    __HT12AA_PRODUCTION_STAGES__) HT12AA_CAUSAL_STAGE="$1" ;;
    cleanup-start)
      # The unchanged production EXIT trap saved this status before cleanup.
      if [[ "${v126_cleanup_exit_status:-0}" != 0 ]]; then remote_backup_failure; fi
      ;;
  esac
  causal_write "$1"
  return 0
}
# Fixture-only ERR observation: no raw command/status text, no production trap
# replacement. Ignore command-substitution ERRs; the unhandled parent failure
# is the authority. A successful observer does not change Bash's saved exit.
trap 'if (( BASH_SUBSHELL == 0 )); then remote_backup_failure; fi' ERR
# Explicit production denials use exit inside die, which does not raise ERR.
# Alias the exact sourced implementation and forward its arguments unchanged;
# only the fixture's parent-shell failure boundary adds a fixed witness.
eval "$(declare -f die | sed '1s/^die /ht12aa_production_die /')"
die() {
  if (( BASH_SUBSHELL == 0 )); then remote_backup_failure; fi
  ht12aa_production_die "$@"
}
diagnostic_stage() {
  local metadata=ok
  { printf '%s\n' "$1" >| "$fixture/driver-stage"; } 2>/dev/null || metadata=failed
  ( trap '' PIPE; printf '%s %s\n' "$1" "$metadata" ) 2>/dev/null >&"$HT12AA_DIAGNOSTIC_FD" || :
  return 0
}
if [[ "$mode" == globals ]]; then
  diagnostic_stage globals_command
  source "$fixture/globals-command.sh"
else
  case "$phase" in
    pre-drain) remote_backup_diagnostic begin-pre-drain ;;
    quiesced) remote_backup_diagnostic begin-quiesced ;;
  esac
  diagnostic_stage remote_backup_rehearsal
  # Keep this a simple command: conditional callers would disable production errexit.
  remote_backup_rehearsal "$fixture" "$HT12AA_RUN" "$HT12AA_RELEASE" "$phase" "fixture:$V125_SOURCE_SHA"
  remote_backup_diagnostic complete
  diagnostic_stage remote_backup_rehearsal_success
  diagnostic_stage trap_postcondition
  # Explicit exits prevent later markers from masking failed postconditions,
  # including Bash 3's [[ ]] / errexit behavior and negated commands.
  [[ -z "$(trap -p EXIT INT TERM HUP)" ]] || exit 1
  diagnostic_stage cleanup_container_postcondition
  if declare -p V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER >/dev/null 2>&1; then exit 1; fi
  diagnostic_stage cleanup_volume_postcondition
  if declare -p V126_REMOTE_REHEARSAL_CLEANUP_VOLUME >/dev/null 2>&1; then exit 1; fi
  diagnostic_stage complete
fi
'''.replace("__HT12AA_CAUSAL_ALLOWLIST__", "|".join(
    (*PRODUCTION_STAGES, *sorted(CLEANUP_EVENTS), "begin-pre-drain", "begin-quiesced",
     *("failed-" + stage for stage in PRODUCTION_STAGES)))).replace(
         "__HT12AA_PRODUCTION_STAGES__", "|".join(PRODUCTION_STAGES)).replace(
         "__HT12AA_FAILURE_STAGES__", "|".join(stage for stage in PRODUCTION_STAGES
                                               if stage not in ("production-verified", "complete")))


def init_transition_diagnostic(root):
    # Fixture observations only: never consumed by production or accepted as proof.
    unknown = " HT12AA_INIT_TRANSITION boundary=UNKNOWN_REDACTED outcome=unavailable exit=UNAVAILABLE"
    try:
        with (root / "init-transition.json").open("rb") as stream:
            data = json.loads(stream.read(2049))
        record = data["transition"]
        stage, outcome, code = (record[key] for key in ("boundary", "outcome", "exit"))
        if (stage not in ("init-ready", "probes", "init-release", "init-stopped", "final-release", "complete")
                or outcome not in ("pending", "nonzero", "timeout", "deadline", "unavailable", "invalid-identity", "held", "ok")
                or not (code is None or type(code) is int and -255 <= code <= 255)):
            return unknown
    except (OSError, ValueError, KeyError, TypeError):
        return unknown
    return f" HT12AA_INIT_TRANSITION boundary={stage} outcome={outcome} exit={code if code is not None else 'UNAVAILABLE'}"


def exercise_init_transition(cfg, args, witness):
    name = args[1]
    state = {"handoffs": {}}
    stage = "init-ready"

    def record(outcome, code=None):
        state["transition"] = dict(boundary=stage, outcome=outcome, exit=code)
        witness.write_text(json.dumps(state))

    def call(command, **kwargs):
        try:
            return run(command, **kwargs)
        except subprocess.TimeoutExpired:
            record("timeout")  # No exit status was observed; do not invent one.
            raise
        except OSError:
            record("unavailable")
            raise

    def wait_marker(marker):
        record("pending")
        deadline = time.monotonic() + 30
        code = None
        while time.monotonic() < deadline:
            code = call([cfg["docker"], "exec", name, "test", "-e", marker], timeout=5).returncode
            if code == 0:
                return
            time.sleep(0.05)
        record("deadline", code)
        raise AssertionError("owned init transition barrier deadline")

    def release():
        record("pending")
        # postgres is the image's OS user, not cfg['user'] (the database role).
        # Observe identity in the same shell that writes each FIFO release.
        result = call([cfg["docker"], "exec", "--user", "postgres", name, "sh", "-c",
                       "set -e; writer=$(id -u); owner=$(stat -c %u /tmp/ht12aa-init-gate); "
                       "printf '%s:%s\\n' \"$writer\" \"$owner\"; "
                       "printf 'go\\n' > /tmp/ht12aa-init-gate"])
        identity = re.fullmatch(rb"([0-9]{1,10}):([0-9]{1,10})\n", result.stdout)
        state["handoffs"][stage] = dict(exit=result.returncode)
        if identity:
            state["handoffs"][stage].update(writer_uid=int(identity[1]), owner_uid=int(identity[2]))
        if result.returncode != 0:
            record("nonzero", result.returncode)
            raise AssertionError("owned init transition handoff failed")
        if not identity:
            record("invalid-identity", result.returncode)
            raise AssertionError("owned init transition identity unavailable")

    wait_marker("/tmp/ht12aa-init-ready")
    stage = "probes"
    record("pending")
    probe = [cfg["docker"], "exec", name, "pg_isready", "-U", cfg["user"], "-d", "postgres"]
    state["socket"] = call(probe).returncode
    state["tcp"] = call([*probe, "-h", "127.0.0.1"]).returncode
    state["actual"] = call([cfg["docker"], *args]).returncode
    stage = "init-release"
    release()
    stage = "init-stopped"
    wait_marker("/tmp/ht12aa-init-stopped")
    stage = "final-release"
    # A false-ready probe must still reach createdb with the init server stopped.
    if state["actual"] == 0:
        record("held")
    else:
        release()
        stage = "complete"
        record("ok", 0)
    return state["actual"]


def boundary(config, kind, args):
    cfg = json.loads(Path(config).read_text())
    root = Path(config).parent

    def event(value):
        with (root / "events").open("a") as log:
            log.write(value + "\n")

    def diagnostic_event(value):
        value = value if value in ("data-globals-check", "data-globals-ok") else "UNKNOWN_REDACTED"
        try:
            os.write(int(os.environ["HT12AA_CAUSAL_FD"]), (value + "\n").encode())
        except (KeyError, ValueError, OSError):
            pass

    def forward(command, payload=None):
        result = run(command, input=payload)
        # Capture raw diagnostics in the private harness process, never ordinary logs.
        if CANARY.encode() in result.stdout + result.stderr or b"SCRAM-SHA-256$" in result.stdout:
            raise AssertionError("credential appeared in client output")
        sys.stdout.buffer.write(result.stdout)
        if result.returncode:
            if b'missing "=" after' in result.stderr:
                sys.stderr.write("LIBPQ_CONNINFO_MISSING_EQUALS\n")
            else:
                sys.stderr.write("synthetic client command failed\n")
        return result.returncode

    def docker(args, payload=None):
        return forward([cfg["docker"], *args], payload)

    def own(name, volume=False):
        require(name in (cfg["source"], cfg["sentinel"]) or re.fullmatch(
            "hookah-v126-" + cfg["run"] + r"-(pre-drain|quiesced)-[0-9]+", name),
            "Docker target escaped the test namespace")
        result = run([cfg["docker"], *( ["volume"] if volume else ["container"] ),
                      "inspect", "--format", '{{ index .Labels "' + LABEL + '" }}'
                      if volume else '{{ index .Config.Labels "' + LABEL + '" }}', name])
        require(result.returncode == 0 and result.stdout.decode().strip() == cfg["token"],
                "Docker boundary refused a resource outside this test")

    def mapped(value):
        if value.startswith("/var/backups/hookah-bot"):
            value = str(root / "backups") + value[len("/var/backups/hookah-bot"):]
        require(Path(value).is_relative_to(root), "host path escaped fixture")
        return Path(value)

    fault = cfg.get("fault", "")
    if kind == "event":
        event(args[0])
        return 0
    if kind == "compose":
        require(args[:5] == ["exec", "-T", "postgres", "sh", "-c"] and len(args) == 6,
                "unexpected Compose argv")
        command = args[-1]
        utility = next((name for name in ("pg_dumpall", "pg_dump", "pg_restore", "psql")
                        if re.search(r"\b" + name + r"\b", command)), "user")
        event(utility)
        payload = sys.stdin.buffer.read() if utility == "pg_restore" else None
        if fault == utility + "-empty":
            return 0
        if fault == utility + "-partial":
            sys.stdout.write("PGDMP truncated" if utility == "pg_dump" else "-- partial globals\n")
            return 0 if utility == "pg_dump" else 73
        if fault == utility + "-fail":
            return 71
        env = ["--env", "POSTGRES_USER=" + cfg["user"]]
        if cfg["database"] is not None:
            env += ["--env", "POSTGRES_DB=" + cfg["database"]]
        unset = ["env", "-u", "POSTGRES_DB"] if cfg["database"] is None else []
        status = docker(["exec", "-i", *env, cfg["source"], *unset, *args[3:5], command], payload)
        return 72 if fault == utility + "-nonzero" and status == 0 else status
    if kind == "sudo":
        if args[0] == "test":
            negate = args[1] == "!"
            option, value = args[2:] if negate else args[1:]
            path = mapped(value)
            result = {"-e": path.exists, "-L": path.is_symlink, "-d": path.is_dir}[option]()
            return int(result if negate else not result)
        if args[0] == "install":
            require(args[1:2] == ["-d"] and args[-2] == "0700", "unexpected install")
            mapped(args[-1]).mkdir(mode=0o700, parents=True)
            return 0
        if args[0] == "stat":
            args = args[1:]
            kind = "stat"
        else:
            raise AssertionError("unexpected sudo action")
    if kind == "stat":
        require(args[0] == "-c", "unexpected stat")
        info = mapped(args[2]).stat()
        formats = {"%a": f"{stat.S_IMODE(info.st_mode):o}", "%s": str(info.st_size),
                   "%a:%U:%G": f"{stat.S_IMODE(info.st_mode):o}:" +
                   subprocess.check_output(["id", "-un"]).decode().strip() + ":" +
                   subprocess.check_output(["id", "-gn"]).decode().strip()}
        print(formats[args[1]])
        return 0
    if kind == "df":
        require(args[:2] == ["--output=avail", "-B1"], "unexpected df")
        print("Avail\n" + str(shutil.disk_usage(mapped(args[2])).free))
        return 0
    if kind == "checksum":
        if not args:
            # Production metadata hashing uses stdin; preserve the real utility's
            # bytes and exit without recording an artifact-file checksum event.
            return forward([cfg["sha256sum"]], sys.stdin.buffer.read())
        check = args[0] == "-c"
        path = mapped(args[-1])
        event("checksum-check" if check else "checksum-write")
        if check and fault == ("globals-checksum" if "globals" in path.name else "dump-checksum"):
            target = Path(path.read_text().split("  ", 1)[1].strip())
            with mapped(str(target)).open("ab") as stream:
                stream.write(b"corruption")
        return forward([cfg["sha256sum"], *args])
    require(kind == "docker", "unexpected boundary")
    if args == ["info", "--format", "{{.DockerRootDir}}"]:
        print(root)
        return 0
    if args == ["ps", "--all", "--format", "{{.Names}}"]:
        return docker([*args, "--filter", "label=" + LABEL + "=" + cfg["token"]])
    if args == ["volume", "ls", "--format", "{{.Name}}"]:
        return docker([*args, "--filter", "label=" + LABEL + "=" + cfg["token"]])
    if args[0] in ("run",) or args[:2] == ["volume", "create"]:
        name = args[args.index("--name") + 1] if args[0] == "run" else args[-1]
        require(re.fullmatch("hookah-v126-" + cfg["run"] + r"-(pre-drain|quiesced)-[0-9]+", name),
                "unexpected rehearsal name")
        event("container-create" if args[0] == "run" else "volume-create")
        if fault == "wrong-owner-volume" and args[0] == "volume":
            args[args.index("--label") + 1] = "hookah.v126.rehearsal-owner=foreign-fixture"
        index = 1 if args[0] == "run" else 2
        if fault == "init-transition" and args[0] == "run":
            bootstrap = ("printf '%s' " + shlex.quote(INIT_TRANSITION) +
                         " > /docker-entrypoint-initdb.d/00-ht12aa.sh; "
                         "chmod 0644 /docker-entrypoint-initdb.d/00-ht12aa.sh; "
                         "exec /usr/local/bin/docker-entrypoint.sh postgres")
            args = [args[0], "--entrypoint", "bash", *args[1:], "-c", bootstrap]
        return docker([*args[:index], "--label", LABEL + "=" + cfg["token"], *args[index:]])
    if args[0] == "cp":
        own(args[2].split(":")[0])
        mapped(args[1])
        event("copy-dump")
    elif args[0] == "exec":
        own(args[1])
        event("rehearsal-" + args[2])
        witness = root / "init-transition.json"
        if fault == "init-transition" and args[2] == "pg_isready" and not witness.exists():
            return exercise_init_transition(cfg, args, witness)
        if fault == "restore-fail" and args[2] == "pg_restore":
            return 74
    elif args[0] == "rm":
        own(args[-1])
        require(args[-1] != cfg["source"], "production attempted source cleanup")
        if not fault:
            # Additional test oracle before production deletes its restored database.
            diagnostic_event("data-globals-check")
            query = ("SELECT (SELECT count(*) FROM ht12aa_payload)=2 AND "
                     "(SELECT sum(value) FROM ht12aa_payload)=42 AND "
                     "(SELECT last_value FROM ht12aa_seq)=9 AND "
                     "NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ht12aa_global');")
            result = run([cfg["docker"], "exec", args[-1], "psql", "-X", "-U", cfg["user"],
                          "-d", "v126_restore_rehearsal", "-Atqc", query])
            require(result.returncode == 0 and result.stdout == b"t\n", "restored data/global separation failed")
            diagnostic_event("data-globals-ok")
            event("data-verified-globals-not-applied")
        event("container-remove")
    elif args[:2] == ["volume", "rm"]:
        own(args[-1], True)
        require(args[-1] != cfg["sentinel"], "production attempted foreign cleanup")
        event("volume-remove")
    elif args[:2] == ["volume", "inspect"]:
        own(args[-1], True)
    elif args[0] == "inspect" or args[:2] == ["container", "inspect"]:
        own(args[-1])
    else:
        raise AssertionError("unexpected Docker action")
    return docker(args)


class BoundaryAdapterTest(unittest.TestCase):
    """Exercise the actual fixture boundary without requiring a Docker daemon."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ht12aa-boundary-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.native_hash = shutil.which("sha256sum")
        require(self.native_hash, "real sha256sum is required")
        self.config = self.root / "config.json"
        self.config.write_text(json.dumps({"sha256sum": self.native_hash}))

    def checksum(self, data, *args):
        return run([sys.executable, str(Path(__file__).resolve()), "--boundary", str(self.config),
                    "checksum", *args], input=data)

    def target_digest(self):
        # Exact fixture functions and production hash_text; no copied hash algorithm
        # or replacement of the backup/psql consumer under test.
        prefix = DRIVER.split('if [[ "$mode" == globals ]]; then', 1)[0]
        script = prefix + '\nremote_assert_database_target || die "backup database equality failed"\n'
        script += 'printf "%s\\n" "$REMOTE_DATABASE_TARGET_IDENTITY_SHA256"\n'
        env = {key: os.environ[key] for key in ("PATH", "HOME") if key in os.environ}
        env["HT12AA_HELPER"] = str(Path(__file__).resolve())
        return run(["bash", "-c", script, "boundary-regression", str(ROOT / "v126-cutover.sh"),
                    str(self.root), "pre-drain", "backup"], env=env)

    def test_exact_target_fixture_supports_native_stdin_hash(self):
        result = self.target_digest()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(result.stdout.strip(), hashlib.sha256(b"fixture-semantic-database-target").hexdigest().encode())
        self.assertFalse((self.root / "events").exists(), "stdin metadata hash is not a dump checksum event")

    def test_actual_stdin_hash_accepts_empty_and_binary_input(self):
        for payload in (b"", b"fixture\x00binary\xff\n"):
            with self.subTest(size=len(payload)):
                result = self.checksum(payload)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                self.assertEqual(result.stdout, hashlib.sha256(payload).hexdigest().encode() + b"  -\n")

    def test_valid_hash_stdout_followed_by_nonzero_remains_failure(self):
        import shlex
        wrapper = self.root / "hash-then-fail"
        wrapper.write_text("#!/bin/sh\n" + shlex.quote(self.native_hash) + ' "$@" || exit $?\nexit 73\n')
        wrapper.chmod(0o500)
        self.config.write_text(json.dumps({"sha256sum": str(wrapper)}))
        result = self.checksum(b"fixture")
        self.assertEqual(result.returncode, 73)
        self.assertEqual(result.stdout, hashlib.sha256(b"fixture").hexdigest().encode() + b"  -\n")
        target = self.target_digest()
        self.assertEqual(target.returncode, 4)
        self.assertIn(b"backup database equality failed", target.stderr)
        self.assertNotIn(b"ARTIFACT\t", target.stdout)

    def test_real_file_checksum_and_corruption_refusal_remain(self):
        payload = self.root / "dump"
        payload.write_bytes(b"synthetic dump bytes")
        produced = self.checksum(None, str(payload))
        self.assertEqual(produced.returncode, 0)
        inventory = self.root / "dump.sha256"
        inventory.write_bytes(produced.stdout)
        self.assertEqual(self.checksum(None, "-c", str(inventory)).returncode, 0)
        payload.write_bytes(b"different bytes")
        self.assertNotEqual(self.checksum(None, "-c", str(inventory)).returncode, 0)


class InitTransitionTest(unittest.TestCase):
    """Fault injection at the fixture boundary, not a protected_fifos kernel test."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ht12aa-transition-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = self.root / "config.json"
        self.config.write_text(json.dumps(dict(docker="fixture-docker", token="owned",
                                               source="owned-source", sentinel="owned-sentinel",
                                               user="database-role-not-os-user", fault="init-transition")))
        self.args = ["exec", "owned-source", "pg_isready", "-h", "127.0.0.1", "-U", USER, "-d", "postgres"]

    def test_first_handoff_failure_survives_later_readiness_failure(self):
        for fail_at, status in ((1, 73), (2, 74)):
            with self.subTest(handoff=fail_at):
                witness = self.root / "init-transition.json"
                witness.unlink(missing_ok=True)
                releases = []

                def docker_call(argv, **kwargs):
                    output, code = b"", 0
                    if argv[1:3] == ["container", "inspect"]:
                        output = b"owned\n"
                    elif "pg_isready" in argv:
                        code = 2 if "-h" in argv else 0
                    elif "sh" in argv:
                        releases.append(argv)
                        code = status if len(releases) == fail_at else 0
                        output = CANARY.encode() if code else b"1234:1234\n"
                    stderr = CANARY.encode() if "sh" in argv and code else b""
                    return subprocess.CompletedProcess(argv, code, output, stderr)

                with mock.patch.dict(globals(), run=docker_call):
                    with self.assertRaisesRegex(AssertionError, "handoff failed"):
                        boundary(self.config, "docker", self.args)
                    first = witness.read_bytes()
                    # The next ordinary readiness poll must not retry a handoff,
                    # overwrite its first failure, or call that transition complete.
                    self.assertEqual(boundary(self.config, "docker", self.args), 2)
                self.assertEqual(witness.read_bytes(), first)
                self.assertEqual(len(releases), fail_at)
                failed = "init-release" if fail_at == 1 else "final-release"
                expected = f"HT12AA_INIT_TRANSITION boundary={failed} outcome=nonzero exit={status}"
                self.assertEqual(init_transition_diagnostic(self.root).strip(), expected)
                self.assertNotIn(CANARY, first.decode())

                # Exercise the real parent result/AssertionError path with a terminal
                # driver exit, without spending the production readiness deadline.
                fixture = BackupTest()
                fixture.root, fixture.token = self.root, "owned"
                fixture.docker, fixture.sha256sum = "fixture-docker", "fixture-sha256sum"
                fixture.source, fixture.sentinel = "owned-source", "owned-sentinel"
                fixture.driver = self.root / "driver.sh"
                fixture.driver.write_text("exit 4\n")
                result, _, _ = fixture.invoke(root=self.root, fault="init-transition")
                message = fixture.failure_text(lambda: fixture.assert_driver_success(result))
                self.assertIn(expected, message)
                self.assertNotIn(CANARY, message)
                self.assertNotIn("EACCES", message)

    def test_timeout_has_no_invented_exit_status(self):
        def timed_out(argv, **kwargs):
            raise subprocess.TimeoutExpired(argv, 5, output=CANARY.encode(), stderr=CANARY.encode())

        with mock.patch.dict(globals(), run=timed_out):
            with self.assertRaises(subprocess.TimeoutExpired):
                exercise_init_transition(json.loads(self.config.read_text()), self.args,
                                         self.root / "init-transition.json")
        self.assertEqual(init_transition_diagnostic(self.root).strip(),
                         "HT12AA_INIT_TRANSITION boundary=init-ready outcome=timeout exit=UNAVAILABLE")
        self.assertNotIn(CANARY, (self.root / "init-transition.json").read_text())

    def test_probe_only_and_untrusted_metadata_cannot_report_completion(self):
        witness = self.root / "init-transition.json"
        for data in ({"socket": 0, "tcp": 2, "actual": 2},
                     {"transition": dict(boundary=CANARY, outcome="nonzero", exit=73)},
                     {"transition": dict(boundary="init-release", outcome="nonzero", exit=CANARY)},
                     [], None):
            with self.subTest(data_type=type(data).__name__):
                witness.write_text(json.dumps(data))
                message = init_transition_diagnostic(self.root)
                self.assertIn("outcome=unavailable exit=UNAVAILABLE", message)
                self.assertNotIn("boundary=complete", message)
                self.assertNotIn(CANARY, message)


class CausalDiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ht-ops-18-causal-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def trace(self, stage="restore", phase="quiesced", cleanup=(), witness=True):
        sequence = [item for item in PRODUCTION_STAGES
                    if phase == "pre-drain" or not item.startswith("globals-")]
        failure = ["failed-" + stage] if witness and stage not in ("production-verified", "complete") else []
        return ("\n".join(["begin-" + phase, *sequence[:sequence.index(stage) + 1], *failure, *cleanup]) + "\n").encode()

    def fixture(self, stage="none", cleanup_failure=False, caller="direct", writer_failure=False,
                lost_after="", closed_reader=False, restore_failure=False, driver_failure=False, command_failure=""):
        # Reuse the cutover fake-Docker boundary and the actual diagnostic writer;
        # execute the production body/traps unchanged. A pipe handshake makes
        # reader loss precede the next write deterministically, without sleeps.
        import shlex
        root = self.root / uuid.uuid4().hex
        root.mkdir()
        observer = DRIVER.split("causal_write() {", 1)[1].split("diagnostic_stage() {", 1)[0]
        observer = ("causal_write() {" + observer).replace(
            "remote_backup_diagnostic() {", "observed_diagnostic() {")
        wrapper = root / "source.sh"
        wrapper.write_text("source " + shlex.quote(str(ROOT / "v126-cutover.sh")) + "\n" + observer + r'''
remote_backup_diagnostic() {
  observed_diagnostic "$@"
  if [[ "$1" == "$HT18_STAGE" ]]; then remote_backup_failure; exit 77; fi
  if [[ "$HT18_CLEANUP" == yes && "$1" == production-verified ]]; then
    remote_cleanup_owned_rehearsal_volume() {
      remote_backup_diagnostic volume-cleanup || :
      return 1
    }
  fi
  if [[ "$1" == "$HT20_LOST_AFTER" ]]; then
    if [[ "$HT20_READER" == yes ]]; then
      IFS= read -r -u "$HT20_ACK_FD" acknowledgement
      [[ "$acknowledgement" == closed ]] || exit 98
    else
      eval "exec ${HT12AA_CAUSAL_FD}>&-"
    fi
  fi
  [[ "$HT18_WRITER" != yes ]] || return 93
  return 0
}
remote_backup_diagnostic begin-quiesced || :
''')
        harness = (ROOT / "test-v126-cutover.sh").read_text()
        fixture = harness.split("run_real_backup_rehearsal_cleanup_fixture() {", 1)[1].split(
            "\nassert_real_backup_rehearsal_lifecycle()", 1)[0]
        fixture = "run_real_backup_rehearsal_cleanup_fixture() {" + fixture
        if restore_failure:
            fixture = fixture.replace('pg_restore) [[ "${fixture_mode}" != post-restore ]] || return 91 ;;',
                                      'pg_restore) return 72 ;;')
        if driver_failure:
            fixture = fixture.replace("printf '%s\\n' SUCCESS_CLEANUP_GLOBALS_RESET",
                                      "exit 79\nprintf '%s\\n' SUCCESS_CLEANUP_GLOBALS_RESET")
        if command_failure:
            injected = r'''
eval "$(declare -f remote_compose | sed '1s/^remote_compose /ht20_compose /')"
remote_compose() {
  if [[ "$HT20_COMMAND_FAILURE" == restore-list && "$*" == *'pg_restore --list'* ]]; then return 73; fi
  if [[ "$HT20_COMMAND_FAILURE" == dump-empty && "$*" == *'pg_dump '* ]]; then return 0; fi
  ht20_compose "$@"
}
eval "$(declare -f remote_write_proof | sed '1s/^remote_write_proof /ht20_write_proof /')"
remote_write_proof() {
  [[ "$HT20_COMMAND_FAILURE" != proof-create ]] || return 76
  ht20_write_proof "$@"
}
eval "$(declare -f remote_emit_artifact | sed '1s/^remote_emit_artifact /ht20_emit_artifact /')"
remote_emit_artifact() {
  if [[ "$HT20_COMMAND_FAILURE" == proof-emit && "$1" == quiesced-backup-proof ]]; then return 78; fi
  if [[ "$HT20_COMMAND_FAILURE" == metadata-emit && "$1" == quiesced-backup-rehearsal ]]; then return 79; fi
  ht20_emit_artifact "$@"
}
'''
            fixture = fixture.replace('case "${fixture_caller}" in', injected + '\ncase "${fixture_caller}" in', 1)
        adapted = root / "fixture.sh"
        adapted.write_text(fixture)
        read_fd, write_fd = os.pipe()
        ack_read, ack_write = os.pipe()
        env = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
        env.update(HT12AA_CAUSAL_FD=str(write_fd), HT20_ACK_FD=str(ack_read), HT18_STAGE=stage,
                   HT18_CLEANUP="yes" if cleanup_failure else "no", HT18_WRITER="yes" if writer_failure else "no",
                   HT20_LOST_AFTER=lost_after, HT20_READER="yes" if closed_reader else "no",
                   HT20_COMMAND_FAILURE=command_failure)
        script = 'source "$1"\nsource "$2"\nCUTOVER_SCRIPT="$3"\nrun_real_backup_rehearsal_cleanup_fixture success "$4" "$5"\n'
        data = b""
        try:
            with subprocess.Popen(["bash", "-c", script, "causal-fixture", str(ROOT / "test-v126-cutover.sh"),
                                   str(adapted), str(wrapper), str(root), caller], env=env,
                                  pass_fds=(write_fd, ack_read), stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE) as process:
                try:
                    os.close(write_fd)
                    write_fd = None
                    if closed_reader:
                        deadline = time.monotonic() + 15
                        while ("\n" + lost_after + "\n").encode() not in data:
                            require(time.monotonic() < deadline, "closed-reader handshake timed out")
                            if select.select([read_fd], [], [], 0.1)[0]:
                                chunk = os.read(read_fd, 2049)
                                require(bool(chunk), "closed-reader handshake lost producer")
                                data += chunk
                        os.close(read_fd)
                        read_fd = None
                        os.write(ack_write, b"closed\n")
                    stdout, stderr = process.communicate(timeout=20)
                except BaseException:
                    process.kill()
                    process.communicate()
                    raise
                result = subprocess.CompletedProcess([], process.returncode, stdout, stderr)
            if read_fd is not None:
                data += os.read(read_fd, 2049)
        finally:
            for fd in (read_fd, write_fd, ack_read, ack_write):
                if fd is not None:
                    os.close(fd)
        data += b"complete\n" if result.returncode == 0 and not lost_after else b""
        result.causal_trace = data
        summary = causal_diagnostic(data, "quiesced", result.returncode)
        return result, root, summary

    def test_closed_reader_preserves_failure_exit_and_exit_cleanup(self):
        result, root, summary = self.fixture(lost_after="restore", closed_reader=True, restore_failure=True)
        self.assertEqual(result.returncode, 72, summary)
        self.assertIn("failure=unknown", summary)
        self.assertIn(b"rehearsal restore failed (exit=72)", result.stderr)
        self.assert_cleaned(root)

    def test_closed_reader_preserves_success_and_proof(self):
        result, root, summary = self.fixture(lost_after="restore", closed_reader=True)
        self.assertEqual(result.returncode, 0, summary)
        self.assertIn("failure=unknown", summary)
        self.assertIn(b"SUCCESS_CLEANUP_GLOBALS_RESET", result.stdout)
        self.assertTrue((root / "staging/.v126-runs/fixture-rehearsal/quiesced-backup-rehearsed.proof").is_file())
        self.assert_cleaned(root)

    def assert_cleaned(self, root):
        for name in ("container.state", "container.owner", "volume.state", "volume.owner"):
            self.assertFalse((root / name).exists(), name)
        lifecycle = (root / "lifecycle.log").read_text().splitlines()
        self.assertIn("CONTAINER_RM", lifecycle)
        self.assertIn("VOLUME_RM", lifecycle)
        self.assertEqual((root / "sentinel.guard").read_text(), "SENTINEL_UNTOUCHED\n")

    def test_lost_suffix_after_successful_stages_never_fabricates_root(self):
        for last, stage, restore, driver in (("readiness", "none", True, False),
                                            ("database-create", "none", True, False),
                                            ("production-verified", "proof-generation", False, False),
                                            ("volume-cleanup", "none", False, True),
                                            ("cleanup-ok", "none", False, True)):
            with self.subTest(last_delivered=last):
                result, root, summary = self.fixture(stage, lost_after=last, restore_failure=restore,
                                                     driver_failure=driver)
                self.assertEqual(result.returncode, 72 if restore else 79 if driver else 77)
                self.assertIn("root_cause=UNKNOWN_REDACTED", summary)
                self.assertIn("failure=unknown", summary)
                self.assertTrue(result.causal_trace.endswith((last + "\n").encode()))
                self.assert_cleaned(root)

    def test_intact_unhandled_command_failure_witness_preserves_exact_exit(self):
        for fault, code, stage, proof in (("dump-empty", 4, "dump", False),
                                           ("restore-list", 73, "restore-list", False),
                                           ("proof-create", 76, "proof-generation", False),
                                           ("proof-emit", 78, "proof-generation", True),
                                           ("metadata-emit", 79, "post-validation", False)):
            with self.subTest(command=fault):
                result, root, summary = self.fixture(command_failure=fault)
                self.assertEqual(result.returncode, code, summary)
                self.assertIn("root_cause=" + stage + " ", summary)
                self.assertEqual((root / "staging/.v126-runs/fixture-rehearsal/quiesced-backup-rehearsed.proof").exists(), proof)
                if fault not in ("dump-empty", "restore-list"):
                    self.assert_cleaned(root)

    def test_writer_preserves_parent_traps_and_nonzero_status(self):
        prefix = DRIVER.split('if [[ "$mode" == globals ]]; then', 1)[0]
        for signal_handler in ("trap - PIPE", "trap ':' PIPE"):
            read_fd, write_fd = os.pipe()
            ack_read, ack_write = os.pipe()
            try:
                script = 'IFS= read -r -u "$HT20_ACK_FD" acknowledgement\n' + prefix + r'''
trap ':' INT TERM HUP
trap 'saved=$?; printf "cleanup=%s\n" "$saved"; exit "$saved"' EXIT
''' + signal_handler + r'''
before_traps="$(trap -p EXIT INT TERM HUP PIPE ERR)"
before_options="$-"
remote_backup_diagnostic begin-quiesced
remote_backup_diagnostic dump
diagnostic_stage remote_backup_rehearsal
[[ "$(trap -p EXIT INT TERM HUP PIPE ERR)" == "$before_traps" && "$-" == "$before_options" ]] || exit 98
unhandled_failure() { return 73; }
unhandled_failure
'''
                with subprocess.Popen(["bash", "-c", script, "writer-traps", str(ROOT / "v126-cutover.sh"),
                                       str(self.root), "quiesced", "backup"],
                                      env=dict(PATH=os.environ["PATH"], HT12AA_CAUSAL_FD=str(write_fd),
                                               HT12AA_DIAGNOSTIC_FD=str(write_fd), HT20_ACK_FD=str(ack_read)),
                                      pass_fds=(write_fd, ack_read), stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE) as process:
                    # Close after spawn so subprocess FD remapping cannot reuse
                    # a pre-closed reader descriptor for captured stdout/stderr.
                    os.close(read_fd)
                    os.write(ack_write, b"closed\n")
                    stdout, stderr = process.communicate(timeout=10)
                    result = subprocess.CompletedProcess([], process.returncode, stdout, stderr)
            finally:
                for fd in (write_fd, ack_read, ack_write):
                    os.close(fd)
            self.assertEqual(result.returncode, 73)
            self.assertEqual(result.stdout, b"cleanup=73\n")
            self.assertEqual(result.stderr, b"")

    def test_prefix_without_failure_witness_is_unknown_at_every_stage(self):
        for phase in ("pre-drain", "quiesced"):
            for stage in PRODUCTION_STAGES[:-1]:
                if phase == "quiesced" and stage.startswith("globals-"):
                    continue
                self.assertIn("failure=unknown", causal_diagnostic(
                    self.trace(stage, phase, witness=False), phase, 72))
        prefix = self.trace("database-create", witness=False)
        # A recovered channel cannot bind a later failure to an earlier entry.
        self.assertIn("failure=unknown", causal_diagnostic(prefix + b"failed-restore\n", "quiesced", 72))
        # An explicit intact failure witness remains authoritative if cleanup's suffix is lost.
        self.assertIn("root_cause=restore", causal_diagnostic(self.trace(), "quiesced", 72))

    def test_actual_early_failure_survives_exit_cleanup(self):
        for caller in ("direct", "conditional", "capture"):
            with self.subTest(caller=caller):
                result, root, summary = self.fixture("restore", caller=caller)
                self.assertEqual(result.returncode, 77, summary)
                self.assertIn("root_cause=restore cleanup=cleanup-ok cleanup_event=volume-cleanup", summary)
                self.assertIn("failure=production", summary)
                self.assertIn("CONTAINER_RM\n", (root / "lifecycle.log").read_text())
                self.assertIn("VOLUME_RM\n", (root / "lifecycle.log").read_text())
                self.assertFalse((root / "container.state").exists())
                self.assertFalse((root / "volume.state").exists())

    def test_actual_substages_and_post_validation(self):
        for stage in ("restore-list", "readiness", "database-create", "restored-version", "restored-data",
                      "post-validation", "proof-generation"):
            with self.subTest(stage=stage):
                result, root, summary = self.fixture(stage)
                self.assertEqual(result.returncode, 77, summary)
                self.assertIn("root_cause=" + stage + " ", summary)
                self.assertIn("failure=" + ("postcondition" if stage in ("post-validation", "proof-generation")
                                          else "production"), summary)
                self.assertFalse((root / "staging/.v126-runs/fixture-rehearsal/quiesced-backup-rehearsed.proof").exists())

    def test_actual_success_and_cleanup_failure_are_distinct(self):
        for fail in (False, True):
            with self.subTest(cleanup_failure=fail):
                result, root, summary = self.fixture(cleanup_failure=fail)
                self.assertEqual(result.returncode, 4 if fail else 0, summary)
                self.assertIn("root_cause=NONE", summary)
                self.assertIn("cleanup=" + ("cleanup-failed" if fail else "cleanup-ok"), summary)
                self.assertIn("failure=" + ("cleanup" if fail else "none"), summary)
                self.assertIn("cleanup_failure=" + ("volume-cleanup" if fail else "NONE"), summary)
                proof = root / "staging/.v126-runs/fixture-rehearsal/quiesced-backup-rehearsed.proof"
                self.assertEqual(proof.exists(), not fail)
                if not fail:
                    self.assertIn(b"result=PASS\n", proof.read_bytes())

    def test_observer_nonzero_does_not_change_production_predicates(self):
        for stage, code in (("none", 0), ("restore", 77)):
            with self.subTest(stage=stage):
                result, _, summary = self.fixture(stage, writer_failure=True)
                self.assertEqual(result.returncode, code, summary)

    def test_all_fixed_categories_and_cleanup_failure_preserve_first_root(self):
        for phase in ("pre-drain", "quiesced"):
            for stage in PRODUCTION_STAGES[:PRODUCTION_STAGES.index("production-verified")]:
                if phase == "quiesced" and stage.startswith("globals-"):
                    continue
                for end in (("cleanup-start", "container-cleanup", "volume-cleanup", "cleanup-ok"),
                            ("cleanup-start", "container-cleanup", "container-failed", "volume-cleanup",
                             "volume-failed", "cleanup-failed")):
                    summary = causal_diagnostic(self.trace(stage, phase, end), phase, 74)
                    self.assertIn("root_cause=" + stage + " ", summary)
                    self.assertIn("failure=production", summary)
                    self.assertIn("cleanup_event=volume-cleanup", summary)
                    self.assertIn("cleanup_failure=" + ("NONE" if end[-1] == "cleanup-ok" else "container-cleanup"), summary)

    def test_combined_fixture_data_globals_check_remains_attributable(self):
        prefix = self.trace("production-verified", cleanup=("cleanup-start", "container-cleanup", "data-globals-check"))
        failed = prefix + b"container-failed\nvolume-cleanup\nvolume-failed\ncleanup-failed\n"
        summary = causal_diagnostic(failed, "quiesced", 4)
        self.assertIn("root_cause=NONE", summary)
        self.assertIn("fixture_check=data-globals-check failure=cleanup", summary)
        self.assertIn("cleanup_failure=container-cleanup", summary)
        # A check that started but never passed cannot supply successful cleanup evidence.
        self.assertIn("failure=unknown", causal_diagnostic(prefix + b"volume-cleanup\ncleanup-ok\n", "quiesced", 4))

    def test_adversarial_streams_are_fully_redacted(self):
        payloads = ["unknown", CANARY, "SCRAM-SHA-256$4096:salt$stored:server",
                    "postgresql://fixture:password@invalid/db", "host=invalid password=fixture",
                    "restore\nunknown", "restore\t", " restore", "restore ", "restore=bad", "$(id);`id`|&<>",
                    "\x1b[31mrestore", "\u202erestore", "restоre", "restore\u200b", "x" * 8192]
        valid = self.trace()
        for number, payload in enumerate(payloads):
            for trace in (payload.encode(), valid + payload.encode() + b"\n",
                          valid.replace(b"restore\n", payload.encode() + b"\n")):
                with self.subTest(payload=number):
                    summary = causal_diagnostic(trace, "quiesced", 1)
                    self.assertEqual(set(part.split("=", 1)[1] for part in summary.split()),
                                     {"UNKNOWN_REDACTED", "unknown"})
                    self.assertLess(len(summary), 256)
                    self.assertNotIn(CANARY, summary)
                    self.assertNotIn("SCRAM", summary)
                    self.assertNotIn("postgresql", summary)
        for trace in (None, b"", valid[:-1], valid + b"complete\n", valid + valid,
                      self.trace("production-verified") + b"cleanup-ok\n"):
            self.assertIn("failure=unknown", causal_diagnostic(trace, "quiesced", 1))

    def test_adversarial_values_are_redacted_by_actual_writer(self):
        prefix = DRIVER.split('if [[ "$mode" == globals ]]; then', 1)[0]
        payloads = ["restore\nrestored-version", "restore\t", " restore", "restore ", "restore=bad",
                    "$(id);`id`|&<>", "\x1b[31mrestore", "\u202erestore", "restоre", "restore\u200b",
                    "x" * 8192, CANARY, "SCRAM-SHA-256$4096:salt$stored:server",
                    "postgresql://fixture:password@invalid/db", "host=invalid password=fixture", "unknown"]
        for number, payload in enumerate(payloads):
            with self.subTest(payload=number):
                read_fd, write_fd = os.pipe()
                try:
                    env = {"PATH": os.environ["PATH"], "HT12AA_CAUSAL_FD": str(write_fd)}
                    result = run(["bash", "-c", prefix + '\nremote_backup_diagnostic "$5"\n',
                                  "writer-test", str(ROOT / "v126-cutover.sh"), str(self.root),
                                  "quiesced", "backup", payload], env=env, pass_fds=(write_fd,))
                    os.close(write_fd)
                    write_fd = None
                    emitted = os.read(read_fd, 256)
                finally:
                    if write_fd is not None:
                        os.close(write_fd)
                    os.close(read_fd)
                self.assertEqual(result.returncode, 0)
                self.assertEqual(emitted, b"UNKNOWN_REDACTED\n")
                self.assertEqual(result.stdout + result.stderr, b"")

    def test_phase_and_invocation_isolation(self):
        pre = self.trace(phase="pre-drain")
        quiesced = self.trace()
        self.assertIn("failure=unknown", causal_diagnostic(pre, "quiesced", 74))
        self.assertIn("failure=unknown", causal_diagnostic(quiesced, "pre-drain", 74))
        self.assertIn("root_cause=restore", causal_diagnostic(quiesced, "quiesced", 74))
        self.assertIn("failure=unknown", causal_diagnostic(b"", "quiesced", 74))
        self.assertIn("failure=unknown", causal_diagnostic(pre + quiesced, "quiesced", 74))

    def test_missing_or_nonpass_proof_is_safe_prerequisite_failure(self):
        case = BackupTest("test_12_diagnostic_writer_failure_preserves_postcondition_failure")
        result = subprocess.CompletedProcess([], 1, b"", b"")
        result.diagnostic = "HT12AA_DIAG " + causal_diagnostic(self.trace(), "quiesced", 1)
        path = self.root / "run/quiesced-backup-rehearsed.proof"
        path.parent.mkdir()
        for contents in (None, b"result=FAIL\n", b"result=PASS", b"result=PASS\nresult=PASS\n",
                         b"untrusted " + CANARY.encode()):
            if contents is not None:
                path.write_bytes(contents)
            message = case.failure_text(lambda: case.assert_completed_rehearsal(result, self.root, self.root))
            self.assertIn("HT12AA_PREREQUISITE production-pass-proof-required", message)
            self.assertIn("root_cause=restore", message)
            for forbidden in ("FileNotFoundError", str(self.root), CANARY):
                self.assertNotIn(forbidden, message)

    def test_canonical_pass_proof_accepts_genuine_artifact_and_rejects_ambiguity(self):
        result, root, _ = self.fixture()
        self.assertEqual(result.returncode, 0)
        proof = root / "staging/.v126-runs/fixture-rehearsal/quiesced-backup-rehearsed.proof"
        checksum = Path(str(proof) + ".sha256")
        genuine = proof.read_bytes()
        # The fixture's exact production writer supplies this artifact. Keep its
        # generated identity; all negative cases mutate those genuine bytes.
        release = next(row.split(b"=", 1)[1].decode() for row in genuine.splitlines()
                       if row.startswith(b"release_sha="))
        valid = lambda: canonical_pass_proof(proof, root / "backup", "fixture-rehearsal", release)
        self.assertTrue(valid())
        self.assert_cleaned(root)
        rows = genuine.splitlines(keepends=True)
        cases = {
            "missing": None, "empty": b"", "pass-only": b"result=PASS\n",
            "non-pass": genuine.replace(b"result=PASS", b"result=FAIL"),
            "pass-plus-fail": genuine + b"result=FAIL\n", "duplicate-pass": genuine + b"result=PASS\n",
            "truncated-row": genuine[:-1], "truncated-artifact": b"".join(rows[:-2]),
            "duplicate-key": genuine + rows[3], "contradictory-cleanup": genuine + b"rehearsal_cleanup=FAILED\n",
            "extra-field": genuine + b"unexpected=PASS\n", "unknown-value": genuine.replace(b"COMPLETE", b"UNKNOWN"),
            "malformed-row": genuine.replace(b"phase=quiesced", b"phase==quiesced"),
            "whitespace": genuine.replace(b"result=PASS", b"result=PASS "),
            "wrong-phase": genuine.replace(b"phase=quiesced", b"phase=pre-drain"),
            "wrong-hash": genuine.replace(rows[3], b"dump_sha256=" + b"0" * 64 + b"\n"),
            "unknown-container": genuine.replace(rows[6], b"rehearsal_container=UNKNOWN\n"),
            "wrong-owner": genuine.replace(b":quiesced:", b":pre-drain:"),
            "uncontrolled": genuine + CANARY.encode() + b"\n",
        }
        for name, contents in cases.items():
            with self.subTest(proof=name):
                proof.unlink(missing_ok=True)
                if contents is not None:
                    proof.write_bytes(contents)
                    proof.chmod(0o600)
                    # Even a recomputed writable sidecar cannot bless bad grammar.
                    checksum.write_bytes(hashlib.sha256(contents).hexdigest().encode() + b"\n")
                self.assertFalse(valid())
        proof.write_bytes(genuine)
        proof.chmod(0o600)
        checksum.write_bytes(hashlib.sha256(genuine).hexdigest().encode() + b"\n")
        self.assertTrue(valid())
        proof.chmod(0)
        self.assertFalse(valid(), "mode-000 proof must fail even under a privileged test user")
        proof.chmod(0o600)
        with mock.patch.object(Path, "open", side_effect=PermissionError):
            self.assertFalse(valid())
        checksum.write_bytes(b"0" * 64 + b"\n")
        self.assertFalse(valid())
        checksum.unlink()
        self.assertFalse(valid())



class BackupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="ht12aa-backup-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.token = uuid.uuid4().hex
        cls.cleaned = False
        cls.name = "ht12aa-source-" + cls.token
        cls.sentinel = "ht12aa-sentinel-" + cls.token
        cls.docker = shutil.which("docker")
        cls.sha256sum = shutil.which("sha256sum")
        require(cls.docker and cls.sha256sum, "Docker and sha256sum are required; no skip")
        cls.addClassCleanup(cls.cleanup_resources)
        atexit.register(cls.cleanup_resources)  # unittest skips class cleanups on interruption.
        result = run([cls.docker, "run", "--detach", "--name", cls.name, "--network", "none",
                      "--label", LABEL + "=" + cls.token, "--tmpfs", "/var/lib/postgresql/data",
                      "--env", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:17",
                      "-c", "log_connections=on", "-c", "log_line_prefix=%a|%u|%d|"], timeout=240)
        require(result.returncode == 0, "owned PostgreSQL17 launch failed")
        cls.source = result.stdout.decode().strip()
        os_user = run([cls.docker, "exec", cls.source, "id", "-u", "postgres"])
        require(os_user.returncode == 0 and re.fullmatch(rb"[0-9]{1,10}\n", os_user.stdout),
                "image postgres OS identity unavailable")
        cls.postgres_os_uid = int(os_user.stdout)
        print(f"HT12AA postgres OS uid: {cls.postgres_os_uid}", flush=True)
        for _ in range(120):
            ready = run([cls.docker, "exec", cls.source, "pg_isready", "-U", "postgres"])
            if ready.returncode == 0:
                # Require final server, not the image entrypoint's temporary init server.
                state = run([cls.docker, "exec", cls.source, "sh", "-c", "cat /proc/1/comm"])
                if state.stdout == b"postgres\n":
                    break
            time.sleep(0.5)
        else:
            raise AssertionError("owned PostgreSQL17 readiness failed")
        cls.sql("CREATE ROLE " + ident(USER) + " LOGIN SUPERUSER; CREATE ROLE ht12aa_global LOGIN PASSWORD " +
                literal(CANARY) + "; COMMENT ON ROLE ht12aa_global IS 'HT12AA known global'; " +
                "GRANT ht12aa_global TO " + ident(USER) + "; CREATE DATABASE ht12aa_plain OWNER " + ident(USER) + ";")
        cls.sql("CREATE DATABASE " + ident(DATABASE) + " OWNER " + ident(USER) + ";")
        cls.sql("CREATE ROLE " + ident(ODD_USER) + " LOGIN SUPERUSER;")
        require(cls.sql("SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid "
                        "WHERE rolname='ht12aa_global'") == b"t\n", "synthetic password verifier missing")
        for db in ("ht12aa_plain", DATABASE):
            cls.sql("CREATE TABLE flyway_schema_history(version text, success boolean); "
                    "INSERT INTO flyway_schema_history VALUES ('125', true); "
                    "CREATE TABLE ht12aa_payload(id integer PRIMARY KEY, value integer); "
                    "INSERT INTO ht12aa_payload VALUES (1,19),(2,23); "
                    "CREATE SEQUENCE ht12aa_seq; SELECT setval('ht12aa_seq',9);", db, USER)
        cls.sql("ALTER DATABASE postgres ALLOW_CONNECTIONS false; ALTER DATABASE template1 ALLOW_CONNECTIONS false;",
                "ht12aa_plain", USER)
        require(run([cls.docker, "volume", "create", "--label", LABEL + "=" + cls.token,
                     "--label", "hookah.v126.rehearsal-owner=foreign-fixture", cls.sentinel]).returncode == 0,
                "sentinel provisioning failed")
        image = run([cls.docker, "inspect", "--format", "{{.Image}}", cls.source])
        require(image.returncode == 0, "source image identity unavailable")
        cls.image = image.stdout.decode().strip()
        identity = run([cls.docker, "image", "inspect", "--format",
                        "{{.Id}} {{json .RepoDigests}} {{.Os}}/{{.Architecture}}", cls.image])
        require(identity.returncode == 0, "image provenance unavailable")
        print("HT12AA image: " + identity.stdout.decode().strip(), flush=True)
        for command in ("postgres", "pg_dump", "pg_dumpall", "pg_restore"):
            version = run([cls.docker, "exec", cls.source, command, "--version"])
            require(version.returncode == 0 and re.search(rb"PostgreSQL\) 17\.", version.stdout), "PG17 required")
            print("HT12AA " + version.stdout.decode().strip(), flush=True)
        require(cls.sql("SHOW server_version_num", "ht12aa_plain", USER).startswith(b"17"), "server must be PG17")
        before = run(["git", "show", BASE + ":scripts/v126-cutover.sh"], cwd=ROOT)
        require(before.returncode == 0, "immutable before source unavailable")
        cls.before = cls.root / "before.sh"
        cls.before.write_bytes(before.stdout)
        cls.driver = cls.root / "driver.sh"
        cls.driver.write_text(DRIVER)

    @classmethod
    def sql(cls, sql, db="postgres", user="postgres"):
        result = run([cls.docker, "exec", "-i", cls.source, "psql", "-XqAt", "-v", "ON_ERROR_STOP=1",
                      "-U", user, "-d", db], input=sql.encode())
        require(result.returncode == 0, "synthetic SQL failed")
        return result.stdout

    @classmethod
    def cleanup_resources(cls):
        if cls.cleaned:
            return
        # Only this random task label, including resources left by intentional failure.
        for volume in (False, True):
            listing = ["volume", "ls", "-q"] if volume else ["ps", "-aq", "--no-trunc"]
            result = run([cls.docker, *listing, "--filter", "label=" + LABEL + "=" + cls.token])
            require(result.returncode == 0, "test-owned cleanup inventory failed")
            for name in result.stdout.decode().splitlines():
                command = ["volume", "rm", name] if volume else ["rm", "-fv", name]
                require(run([cls.docker, *command]).returncode == 0, "test-owned cleanup failed")
            verify = run([cls.docker, *listing, "--filter", "label=" + LABEL + "=" + cls.token])
            require(verify.returncode == 0 and not verify.stdout.strip(), "test-owned resources remain")
        cls.cleaned = True
        print("HT12AA own-resource cleanup: PASS", flush=True)

    def invoke(self, source=None, phase="pre-drain", database="ht12aa_plain", user=USER,
               fault="", mode="backup", root=None):
        source = source or ROOT / "v126-cutover.sh"
        if root is None:
            root = self.root / uuid.uuid4().hex
            root.mkdir(mode=0o700)
            (root / "run").mkdir(mode=0o700)
            (root / "backups").mkdir(mode=0o700)
        run_id = "ht12aa-" + self.token
        cfg = dict(docker=self.docker, sha256sum=self.sha256sum, token=self.token, source=self.source,
                   run=run_id, sentinel=self.sentinel, user=user, database=database, fault=fault)
        (root / "config.json").write_text(json.dumps(cfg))
        if mode == "globals":
            # Extract the production call verbatim; no duplicate command in the test oracle.
            match = re.findall(r"    remote_compose exec -T postgres sh -c \\\n      '[^\n]*pg_dumpall[^\n]*' \\\n      > \"\$\{globals_file\}\"", source.read_text())
            require(len(match) == 1, "production globals call extraction drift")
            (root / "globals-command.sh").write_text('globals_file="$fixture/globals.sql"\n' + match[0] + "\n")
        env = {key: value for key, value in os.environ.items()
               if key in ("PATH", "HOME", "TMPDIR", "DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CONTEXT")}
        env.update(HT12AA_HELPER=str(Path(__file__).resolve()), HT12AA_RUN=run_id,
                   HT12AA_RELEASE=BASE, HT12AA_SOURCE=self.source)
        event_offset = (root / "events").stat().st_size if (root / "events").exists() else 0
        initialized = True
        try:
            (root / "driver-stage").write_text("driver_started\n")
        except OSError:
            initialized = False
        # Each fixed stream is below 512 bytes (including one failure witness).
        # Keep readers open throughout the child; neither disk metadata nor raw
        # child output supplies causal authority. A lost suffix stays unknown
        # unless an explicit failure/completion boundary was already delivered.
        causal_read, causal_write = os.pipe()
        read_fd, write_fd = os.pipe()
        try:
            os.set_blocking(causal_read, False)
            env["HT12AA_CAUSAL_FD"] = str(causal_write)
            os.set_blocking(read_fd, False)
            env["HT12AA_DIAGNOSTIC_FD"] = str(write_fd)
            result = run(["bash", str(self.driver), str(source), str(root), phase, mode],
                         env=env, group=True, pass_fds=(write_fd, causal_write))
            try:
                trace = os.read(read_fd, 256)
            except OSError:
                trace = None
            try:
                causal_trace = os.read(causal_read, 2049)
            except OSError:
                causal_trace = None
        finally:
            os.close(causal_write)
            os.close(causal_read)
            os.close(write_fd)
            os.close(read_fd)
        result.diagnostic = driver_diagnostic(result, phase, root, event_offset, trace, initialized, mode, causal_trace)
        if fault == "init-transition":
            result.diagnostic += init_transition_diagnostic(root)
        require(CANARY.encode() not in result.stdout + result.stderr,
                "credential in diagnostics; " + result.diagnostic)
        require(b"SCRAM-SHA-256$" not in result.stdout + result.stderr,
                "verifier in diagnostics; " + result.diagnostic)
        return result, root, root / "backups" / "v126" / BASE / run_id

    def legacy_diagnostic(self, result):
        return re.sub(r" production=\S+ root_cause=\S+ cleanup=\S+ cleanup_event=\S+ cleanup_failure=\S+ fixture_check=\S+ failure=\S+", "", result.diagnostic)

    def assert_driver_success(self, result):
        self.assertEqual(result.returncode, 0, result.diagnostic)

    def quiesced_root(self):
        # Bind the pre-existing namespace only; no fabricated backup PASS.
        root = self.root / uuid.uuid4().hex
        (root / "run").mkdir(parents=True, mode=0o700)
        (root / "backups" / "v126" / BASE / ("ht12aa-" + self.token)).mkdir(parents=True, mode=0o700)
        for parent in (root / "backups").rglob("*"):
            parent.chmod(0o700)
        return root

    def instrument_driver(self, marker, commands):
        # Instrument the actual driver at a fixed checkpoint, never the production function.
        marker += "\n"
        require(DRIVER.count(marker) == 1, "driver instrumentation checkpoint drift")
        path = self.root / (uuid.uuid4().hex + "-driver.sh")
        path.write_text(DRIVER.replace(marker, marker + commands + "\n"))
        return mock.patch.object(self, "driver", path)

    def failure_text(self, action):
        output = io.StringIO()
        result = unittest.TextTestRunner(stream=output).run(unittest.FunctionTestCase(action))
        self.assertEqual(len(result.failures), 1, "expected one safe assertion failure")
        self.assertEqual(len(result.errors), 0, "unexpected diagnostic regression error")
        return output.getvalue()

    def rejected(self, result, root, backup, phase="pre-drain"):
        self.assertNotEqual(result.returncode, 0, "unexpected backup success; " + result.diagnostic)
        require(b"ARTIFACT\t" not in result.stdout, "failure emitted successful evidence; " + result.diagnostic)
        self.assertFalse((backup / (phase + ".dump.rehearsal.txt")).exists())
        self.assertFalse((root / "run" / (phase + "-backup-rehearsed.proof")).exists())

    def test_01_immutable_before(self):
        result, root, backup = self.invoke(source=self.before)
        self.rejected(result, root, backup)
        require(b"LIBPQ_CONNINFO_MISSING_EQUALS" in result.stderr, "missing libpq category; " + result.diagnostic)
        self.assertGreater((backup / "pre-drain.dump.pg_restore.list").stat().st_size, 0)
        self.assertEqual((backup / "globals.sql").stat().st_size, 0)
        self.assertFalse((backup / "globals.sql.sha256").exists())
        self.assertNotIn("volume-create", (root / "events").read_text())

    def test_02_init_server_is_not_rehearsal_readiness(self):
        for phase in ("pre-drain", "quiesced"):
            with self.subTest(phase=phase):
                root = self.quiesced_root() if phase == "quiesced" else None
                result, root, backup = self.invoke(phase=phase, root=root, fault="init-transition")
                self.assert_driver_success(result)
                probes = json.loads((root / "init-transition.json").read_text())
                self.assertEqual(probes["socket"], 0, "real init server must accept Unix sockets")
                self.assertEqual(probes["tcp"], 2, "real init server must not listen on loopback TCP")
                self.assertEqual(probes["actual"], 2, "production probe must reject the temporary server")
                self.assertEqual(probes["transition"], dict(boundary="complete", outcome="ok", exit=0))
                self.assertEqual(set(probes["handoffs"]), {"init-release", "final-release"})
                for handoff, observed in probes["handoffs"].items():
                    with self.subTest(handoff=handoff):
                        self.assertEqual(observed["exit"], 0)
                        self.assertEqual(observed["owner_uid"], self.postgres_os_uid)
                        self.assertEqual(observed["writer_uid"], observed["owner_uid"],
                                         "FIFO writer must be its OS owner, even with protected_fifos=0")
                self.assertIn("production=complete root_cause=NONE cleanup=cleanup-ok", result.diagnostic)
                self.assertTrue((root / "run" / (phase + "-backup-rehearsed.proof")).exists())
                events = (root / "events").read_text().splitlines()
                self.assertEqual(events.count("rehearsal-createdb"), 1)
                self.assertEqual(events.count("container-remove"), 1)
                self.assertEqual(events.count("volume-remove"), 1)

    def test_02_full_production_both_phases(self):
        result, root, backup = self.invoke(database=DATABASE)
        self.assert_driver_success(result)
        self.assertIn("stage=complete rehearsal=success exit=0", result.diagnostic)
        self.assertIn("production=complete root_cause=NONE cleanup=cleanup-ok", result.diagnostic)
        self.assertIn("fixture_check=data-globals-ok failure=none", result.diagnostic)
        globals_bytes = (backup / "globals.sql").read_bytes()
        for expected in (b"CREATE ROLE ht12aa_global;", b"HT12AA known global", b"GRANT ht12aa_global TO",
                         ("CREATE ROLE " + USER + ";").encode()):
            require(expected in globals_bytes, "known global object missing")
        for forbidden in (CANARY.encode(), b"SCRAM-SHA-256$", b"md5", b" PASSWORD ", b"CREATE DATABASE"):
            require(forbidden not in globals_bytes, "globals privacy/scope failure")
        before = {p.name: p.read_bytes() for p in backup.iterdir()}
        result, _, _ = self.invoke(phase="quiesced", database=DATABASE, root=root)
        self.assert_driver_success(result)
        self.assertIn("phase=quiesced stage=complete rehearsal=success exit=0", result.diagnostic)
        self.assertIn("production=complete root_cause=NONE cleanup=cleanup-ok", result.diagnostic)
        for name, contents in before.items():
            require((backup / name).read_bytes() == contents, "pre-drain artifact changed")
        self.assertEqual(stat.S_IMODE(backup.stat().st_mode), 0o700)
        for path in backup.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_nlink, 1)
            self.assertFalse(path.is_symlink())
        events = (root / "events").read_text().splitlines()
        self.assertEqual(events.count("pg_dumpall"), 1)
        self.assertEqual(events.count("data-verified-globals-not-applied"), 2)
        self.assertEqual(events.count("container-remove"), 2)
        self.assertEqual(events.count("volume-remove"), 2)
        for gate in ("pre-drain-gate", "quiesced-gate"):
            self.assertIn(gate, events)
        expected = ["pg_dump", "pg_restore", "checksum-write", "checksum-check", "pg_dumpall",
                    "checksum-write", "checksum-check", "volume-create", "container-create", "copy-dump",
                    "rehearsal-createdb", "rehearsal-pg_restore", "rehearsal-psql", "rehearsal-psql",
                    "data-verified-globals-not-applied", "container-remove", "volume-remove"]
        cursor = 0
        for event in events:
            if cursor < len(expected) and event == expected[cursor]:
                cursor += 1
        self.assertEqual(cursor, len(expected), "backup operation order changed")
        logs = run([self.docker, "logs", self.source])
        self.assertEqual(logs.returncode, 0)
        require(("connection authorized: user=" + USER + " database=" + DATABASE +
                 " application_name=pg_dumpall").encode() in logs.stderr + logs.stdout,
                "actual initial database/role not observed")
        require(CANARY.encode() not in logs.stdout + logs.stderr, "credential in server diagnostics")
        marker = run([self.docker, "exec", self.source, "test", "!", "-e", "/tmp/ht12aa-injected"])
        self.assertEqual(marker.returncode, 0, "database name was shell interpreted")
        self.assertEqual(run([self.docker, "volume", "inspect", self.sentinel]).returncode, 0)

    def test_03_initial_database_and_role_fail_closed(self):
        for database, user in (("ht12aa_plain", USER), (DATABASE, USER), (DATABASE, ODD_USER), ("missing_ht12aa", USER),
                               ("", USER), (None, USER), ("postgres", USER), ("template1", USER),
                               ("ht12aa_plain", "missing_role")):
            with self.subTest(case="valid" if database in ("ht12aa_plain", DATABASE) and user in (USER, ODD_USER) else "invalid"):
                result, root, _ = self.invoke(database=database, user=user, mode="globals")
                expected = database in ("ht12aa_plain", DATABASE) and user in (USER, ODD_USER)
                self.assertEqual(result.returncode == 0, expected,
                                 "explicit connection selection mismatch; " + result.diagnostic)
                if expected:
                    require(b"CREATE ROLE ht12aa_global;" in (root / "globals.sql").read_bytes(),
                            "known global object missing")

    def test_04_first_failure_stops_later_actions(self):
        faults = ["pg_dump-fail", "pg_dump-empty", "pg_dump-partial", "pg_dump-nonzero",
                  "pg_restore-fail", "pg_restore-empty", "pg_restore-nonzero", "dump-checksum",
                  "pg_dumpall-fail", "pg_dumpall-empty", "pg_dumpall-partial", "pg_dumpall-nonzero",
                  "globals-checksum", "restore-fail", "wrong-owner-volume"]
        for phase in ("pre-drain", "quiesced"):
            for fault in faults:
                if phase == "quiesced" and (fault.startswith("pg_dumpall") or fault == "globals-checksum"):
                    continue
                with self.subTest(phase=phase, fault=fault):
                    root = None
                    if phase == "quiesced":
                        root = self.quiesced_root()
                    result, root, backup = self.invoke(phase=phase, fault=fault, root=root)
                    self.rejected(result, root, backup, phase)
                    category = ("restore-list" if fault == "pg_dump-partial" or fault.startswith("pg_restore-") else
                                "dump" if fault.startswith("pg_dump-") else
                                "globals-export" if fault.startswith("pg_dumpall-") else
                                "restore" if fault == "restore-fail" else
                                "resource-creation" if fault == "wrong-owner-volume" else fault)
                    self.assertIn("root_cause=" + category + " ", result.diagnostic)
                    self.assertIn("failure=production", result.diagnostic)
                    events = (root / "events").read_text().splitlines()
                    if fault not in ("restore-fail", "wrong-owner-volume"):
                        prefix = ["baseline", phase + "-gate", "user", "psql", "psql", "pg_dump"]
                        if not fault.startswith("pg_dump-") or fault == "pg_dump-partial":
                            prefix.append("pg_restore")
                        if fault == "dump-checksum" or fault.startswith("pg_dumpall-") or fault == "globals-checksum":
                            prefix += ["checksum-write", "checksum-check"]
                        if fault.startswith("pg_dumpall-") or fault == "globals-checksum":
                            prefix.append("pg_dumpall")
                        if fault == "globals-checksum":
                            prefix += ["checksum-write", "checksum-check"]
                        self.assertEqual(events, prefix, "work continued after first failure")
                    if fault.startswith("pg_dump-") and fault != "pg_dump-partial":
                        self.assertNotIn("pg_restore", events)
                    if fault.startswith("pg_restore-") or fault == "dump-checksum":
                        self.assertNotIn("pg_dumpall", events)
                    if fault.startswith("pg_dumpall-"):
                        self.assertFalse((backup / "globals.sql.sha256").exists())
                    if fault == "restore-fail":
                        self.assertNotIn("rehearsal-psql", events)
                        self.assertIn("container-remove", events)
                        self.assertIn("volume-remove", events)
                    if fault == "wrong-owner-volume":
                        self.assertNotIn("container-create", events)
                        self.assertNotIn("volume-remove", events)

    def test_05_create_only(self):
        result, root, backup = self.invoke()
        self.assert_driver_success(result)
        snapshot = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in backup.iterdir()}
        for phase in ("pre-drain", "quiesced"):
            if phase == "quiesced":
                target = backup / "quiesced.dump"
                target.symlink_to(backup / "pre-drain.dump")
            result, _, _ = self.invoke(root=root, phase=phase)
            if phase == "quiesced":
                self.rejected(result, root, backup, phase)
            else:
                self.assertNotEqual(result.returncode, 0, result.diagnostic)
                require(b"ARTIFACT\t" not in result.stdout, "failure emitted successful evidence; " + result.diagnostic)
            self.assertIn("production=prerequisites root_cause=prerequisites cleanup=NONE", result.diagnostic)
            for path, digest in snapshot.items():
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest, "existing artifact overwritten")

    def check_production_failure_diagnostic(self, phase):
        root = self.quiesced_root() if phase == "quiesced" else None
        result, root, backup = self.invoke(phase=phase, fault="pg_dump-fail", root=root)
        self.rejected(result, root, backup, phase)
        self.assertEqual(result.returncode, 71, result.diagnostic)
        expected = (f"HT12AA_DIAG phase={phase} stage=remote_backup_rehearsal "
                    "rehearsal=nonzero exit=71 last_event=pg_dump")
        self.assertEqual(result.diagnostic.split(" production=", 1)[0], expected)
        message = self.failure_text(lambda: self.assert_driver_success(result))
        self.assertIn(expected, message)
        require(CANARY not in message and "SCRAM-SHA-256$" not in message, "unsafe failure message")
        # Reproduce only #487's outer nonzero-driver condition, not its unknown cause.
        legacy = self.failure_text(lambda: self.assertEqual(
            result.returncode, 0, f"real {phase} backup/rehearsal failed"))
        self.assertIn(f"real {phase} backup/rehearsal failed", legacy)
        self.assertNotIn("HT12AA_DIAG phase=", legacy)

    def test_06_diagnostic_production_failure_pre_drain(self):
        self.check_production_failure_diagnostic("pre-drain")

    def test_07_diagnostic_production_failure_quiesced(self):
        self.check_production_failure_diagnostic("quiesced")

    def test_08_diagnostic_postconditions_after_success(self):
        cases = [("trap_" + name, "trap ':' " + name, "trap_postcondition")
                 for name in ("EXIT", "INT", "TERM", "HUP")]
        cases += [
            ("container", "V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER=fixture", "cleanup_container_postcondition"),
            ("volume", "V126_REMOTE_REHEARSAL_CLEANUP_VOLUME=fixture", "cleanup_volume_postcondition"),
        ]
        for name, command, stage in cases:
            with self.subTest(postcondition=name), self.instrument_driver(
                    "  diagnostic_stage remote_backup_rehearsal_success", command):
                result, root, backup = self.invoke(phase="quiesced", root=self.quiesced_root())
                expected = (f"HT12AA_DIAG phase=quiesced stage={stage} "
                            "rehearsal=success exit=1 last_event=checksum-write")
                self.assertEqual(result.returncode, 1, result.diagnostic)
                self.assertEqual(result.diagnostic.split(" production=", 1)[0], expected)
                self.assertIn(expected, self.failure_text(lambda: self.assert_driver_success(result)))
                self.assertTrue((backup / "quiesced.dump.rehearsal.txt").is_file())
                self.assertTrue((root / "run" / "quiesced-backup-rehearsed.proof").is_file())
                self.assertIn("data-verified-globals-not-applied", (root / "events").read_text().splitlines())

    def test_09_diagnostic_secret_detection_stays_fail_closed(self):
        import shlex
        verifier = "SCRAM-SHA-256$4096:fixture-salt$fixture-stored-key:fixture-server-key"
        for name, secret, reason in (("canary", CANARY, "credential"), ("verifier", verifier, "verifier")):
            for stream in (1, 2):
                with self.subTest(material=name, stream=stream), self.instrument_driver(
                        "  diagnostic_stage remote_backup_rehearsal",
                        "printf '%s\\n' " + shlex.quote(secret) + f" >&{stream}"):
                    message = self.failure_text(lambda: self.invoke(fault="pg_dump-fail"))
                    require(secret not in message, "secret bytes escaped into unittest output")
                    self.assertIn(reason + " in diagnostics; HT12AA_DIAG phase=pre-drain", message)
                    self.assertIn("stage=remote_backup_rehearsal rehearsal=nonzero exit=71 last_event=pg_dump", message)

    def test_10_diagnostic_uncontrolled_output_and_events_are_redacted(self):
        import shlex
        payload = "UNCONTROLLED_CHILD_CONTENT_" + "x" * 8192
        command = "printf '%s\\n' " + shlex.quote(payload)
        with self.instrument_driver("  diagnostic_stage remote_backup_rehearsal_success",
                                    command + '\n' + command + ' >&2\n' + command + ' >> "$fixture/events"\n' +
                                    "trap ':' EXIT"):
            result, root, _ = self.invoke(phase="quiesced", root=self.quiesced_root())
        require(payload.encode() in result.stdout and payload.encode() in result.stderr,
                "raw output injection did not reach the actual child")
        self.assertEqual(self.legacy_diagnostic(result),
                         "HT12AA_DIAG phase=quiesced stage=trap_postcondition "
                         "rehearsal=success exit=1 last_event=UNKNOWN_REDACTED")
        message = self.failure_text(lambda: self.assert_driver_success(result))
        require("UNCONTROLLED_CHILD_CONTENT_" not in message, "uncontrolled output escaped")
        self.assertLess(len(result.diagnostic), 640)
        (root / "driver-stage").write_text(payload + "\n")
        summary = driver_diagnostic(result, CANARY, root, 0)
        self.assertIn("phase=UNKNOWN_REDACTED stage=UNKNOWN_REDACTED rehearsal=unknown", summary)
        require(CANARY not in summary and "UNCONTROLLED_CHILD_CONTENT_" not in summary, "unsafe diagnostic fields")
        offset = (root / "events").stat().st_size
        self.assertIn("last_event=NONE", driver_diagnostic(result, "quiesced", root, offset))

    def assert_completed_rehearsal(self, result, root, backup):
        require(canonical_pass_proof(root / "run/quiesced-backup-rehearsed.proof", backup,
                                     backup.name, backup.parent.name),
                "HT12AA_PREREQUISITE production-pass-proof-required; " + result.diagnostic)
        self.assertTrue((backup / "quiesced.dump.rehearsal.txt").is_file())
        events = (root / "events").read_text().splitlines()
        for event in ("data-verified-globals-not-applied", "container-remove", "volume-remove"):
            self.assertIn(event, events)
        self.assertNotIn("rehearsal=nonzero", result.diagnostic)
        require(b"driver-stage" not in result.stdout + result.stderr and
                str(root).encode() not in result.stderr, "raw metadata error escaped")

    def test_11_diagnostic_success_marker_write_failure(self):
        call = '  remote_backup_rehearsal "$fixture" "$HT12AA_RUN" "$HT12AA_RELEASE" "$phase" "fixture:$V125_SOURCE_SHA"'
        # Exact reviewer counterexample: only after the real call returns, make
        # the first success write (and later writes) fail before truncation.
        with self.instrument_driver(call, 'chmod 0400 "$fixture/driver-stage"'):
            result, root, backup = self.invoke(phase="quiesced", root=self.quiesced_root())
        self.assert_driver_success(result)
        self.assert_completed_rehearsal(result, root, backup)
        self.assertEqual((root / "driver-stage").read_bytes(), b"remote_backup_rehearsal\n")
        self.assertEqual(self.legacy_diagnostic(result), "HT12AA_DIAG phase=quiesced stage=complete "
                         "rehearsal=success exit=0 last_event=checksum-write metadata=failed")

    def test_12_diagnostic_writer_failure_preserves_postcondition_failure(self):
        call = '  remote_backup_rehearsal "$fixture" "$HT12AA_RUN" "$HT12AA_RELEASE" "$phase" "fixture:$V125_SOURCE_SHA"'
        with self.instrument_driver(call, 'chmod 0400 "$fixture/driver-stage"\n'
                                    'V126_REMOTE_REHEARSAL_CLEANUP_VOLUME=fixture'):
            result, root, backup = self.invoke(phase="quiesced", root=self.quiesced_root())
        self.assert_completed_rehearsal(result, root, backup)
        self.assertEqual(result.returncode, 1, result.diagnostic)
        self.assertEqual((root / "driver-stage").read_bytes(), b"remote_backup_rehearsal\n")
        self.assertEqual(self.legacy_diagnostic(result), "HT12AA_DIAG phase=quiesced stage=cleanup_volume_postcondition "
                         "rehearsal=success exit=1 last_event=checksum-write metadata=failed")
        message = self.failure_text(lambda: self.assert_driver_success(result))
        self.assertIn(result.diagnostic, message)
        require(str(root) not in message and "Permission denied" not in message, "raw metadata error exposed")

    def test_13_diagnostic_writer_failure_preserves_production_failure(self):
        with self.instrument_driver('mode="$4"', 'chmod 0400 "$fixture/driver-stage"'):
            result, root, backup = self.invoke(phase="quiesced", fault="restore-fail", root=self.quiesced_root())
        self.rejected(result, root, backup, "quiesced")
        self.assertEqual(result.returncode, 74, result.diagnostic)
        self.assertEqual(self.legacy_diagnostic(result), "HT12AA_DIAG phase=quiesced stage=remote_backup_rehearsal "
                         "rehearsal=nonzero exit=74 last_event=volume-remove metadata=failed")
        self.assertIn("production=restore root_cause=restore cleanup=cleanup-ok", result.diagnostic)
        self.assertIn("cleanup_event=volume-cleanup cleanup_failure=NONE fixture_check=NONE failure=production", result.diagnostic)
        events = (root / "events").read_text().splitlines()
        self.assertIn("container-remove", events)
        self.assertIn("volume-remove", events)

    def test_16_causal_channel_failure_and_unexpected_prerequisite_are_bounded(self):
        with self.instrument_driver("  diagnostic_stage remote_backup_rehearsal",
                                    'eval "exec ${HT12AA_CAUSAL_FD}>&-"'):
            result, root, backup = self.invoke(phase="quiesced", fault="pg_dump-fail", root=self.quiesced_root())
        self.assertEqual(result.returncode, 71, result.diagnostic)
        self.assertIn("production=UNKNOWN_REDACTED root_cause=UNKNOWN_REDACTED", result.diagnostic)
        message = self.failure_text(lambda: self.assert_completed_rehearsal(result, root, backup))
        self.assertIn("HT12AA_PREREQUISITE production-pass-proof-required", message)
        for forbidden in ("FileNotFoundError", str(root), "Bad file descriptor", CANARY):
            self.assertNotIn(forbidden, message)

    def test_14_diagnostic_state_ambiguity_is_explicit(self):
        root = self.quiesced_root()
        path = root / "driver-stage"
        prefix = b"remote_backup_rehearsal ok\n"
        success = prefix + b"remote_backup_rehearsal_success failed\ntrap_postcondition failed\n"
        payload = "UNTRUSTED_METADATA_" + CANARY + " postgresql://fixture:synthetic@invalid/db\n"
        states = (None, b"remote_backup_rehearsal\n", b"complete\n", b"remote_backup_rehearsal_suc",
                  b"", payload.encode(), b"\xff\n", b"x" * 257)
        for state in states:
            if path.exists():
                path.unlink()
            if state is not None:
                path.write_bytes(state)
            for trace in (None, b"", prefix, prefix + b"remote_backup_rehearsal_suc",
                          b"complete ok\n", payload.encode(), success):
                for code in (0, 1, 71, -15, 143):
                    with self.subTest(sidecar=states.index(state), trace_valid=trace == success, code=code):
                        result = subprocess.CompletedProcess([], code, b"", b"")
                        summary = driver_diagnostic(result, "quiesced", root, 0, trace, mode="backup")
                        outcome = ("success" if code == 0 or trace == success else
                                   "nonzero" if trace == prefix and code in (1, 71) else "unknown")
                        self.assertIn("rehearsal=" + outcome + " ", summary)
                        if not (state == b"remote_backup_rehearsal\n" and trace == prefix and code in (1, 71)):
                            self.assertIn("metadata=", summary)
                        self.assertLess(len(summary), 640)
                        require(CANARY not in summary and "UNTRUSTED_METADATA_" not in summary and
                                "postgresql://" not in summary and "\n" not in summary,
                                "untrusted metadata escaped")

    def test_15_diagnostic_unavailable_storage_and_lost_pipe_suffix(self):
        root = self.quiesced_root()
        (root / "driver-stage").mkdir(mode=0o700)
        result, root, backup = self.invoke(phase="quiesced", root=root)
        self.assert_driver_success(result)
        self.assert_completed_rehearsal(result, root, backup)
        self.assertIn("stage=complete rehearsal=success exit=0", result.diagnostic)
        self.assertTrue(result.diagnostic.endswith("metadata=failed"))
        call = '  remote_backup_rehearsal "$fixture" "$HT12AA_RUN" "$HT12AA_RELEASE" "$phase" "fixture:$V125_SOURCE_SHA"'
        for code, postcondition in ((1, 'V126_REMOTE_REHEARSAL_CLEANUP_VOLUME=fixture'),
                                    (71, "trap 'exit 71' EXIT")):
            with self.subTest(code=code), self.instrument_driver(call, 'chmod 0400 "$fixture/driver-stage"\n'
                                        'eval "exec ${HT12AA_DIAGNOSTIC_FD}>&-"\n' + postcondition):
                result, root, backup = self.invoke(phase="quiesced", root=self.quiesced_root())
            self.assertEqual(result.returncode, code, result.diagnostic)
            self.assert_completed_rehearsal(result, root, backup)
            self.assertIn(f"rehearsal=unknown exit={code}", result.diagnostic)
            self.assertTrue(result.diagnostic.endswith("metadata=unavailable"))
            message = self.failure_text(lambda: self.assert_driver_success(result))
            self.assertIn(result.diagnostic, message)
            require(str(root) not in message and "Bad file descriptor" not in message, "raw pipe error exposed")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--boundary":
        try:
            sys.exit(boundary(sys.argv[2], sys.argv[3], sys.argv[4:]))
        except Exception:
            sys.stderr.write("HT12AA isolated boundary failed\n")
            sys.exit(97)
    else:
        os.umask(0o077)
        unittest.main(verbosity=2)
