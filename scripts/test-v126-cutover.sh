#!/usr/bin/env bash
set -Eeuo pipefail

export LC_ALL=C
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CUTOVER_SCRIPT="${SCRIPT_DIR}/v126-cutover.sh"

readonly RELEASE_SHA='ecb09601975678a41d89e5c824cc7812c7876481'
readonly RELEASE_TREE='8c97996e317f0182b4871d2a2537a732d4830f64'
readonly RELEASE_PARENTS='9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1,d9c656b1c5feb757b79558209f130c08cba81cf5'
readonly MAIN_ACTIONS_RUN_ID='33536142005'
readonly V126_IMAGE_ID='sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a'
readonly V125_SOURCE_SHA='f577934691a1a7a79ba327c54e2055425142b7be'
readonly SECRET_CANARY='HT12P_SECRET_CANARY_8f91e9cd2eaa4a6596e4'
readonly FIXTURE_BASELINE_CADDY_SHA='7777777777777777777777777777777777777777777777777777777777777777'

readonly EXPECTED_COMPLETE_MIGRATION_TREE='765956602de896b4498a956753272a6bc2d2971e'
readonly EXPECTED_POSTGRESQL_MIGRATION_TREE='bb2778e26e03e03211eab9f149777313f4a6f24b'
readonly EXPECTED_H2_MIGRATION_TREE='07b5ba6ccf25e79c9cc419b9095bb664f2cfae18'
readonly EXPECTED_MIGRATION_BLOB='6f39f7d33b1976d0f5eb7a70051bfc5351d12e56'
readonly EXPECTED_MIGRATION_SHA256='ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5'
readonly EXPECTED_FLYWAY_CHECKSUM='1701638026'

readonly -a STAGES=(
  BASELINE_VERIFIED
  PRE_DRAIN_BACKUP_REHEARSED
  CADDY_CANDIDATE_INSTALLED_AND_RELOADED
  PUBLIC_DRAIN_ACTIVE
  V125_BACKEND_STOPPED
  ZERO_WRITER_GATE_PASSED
  QUIESCED_BACKUP_REHEARSED
  FINAL_V125_PREFLIGHT_PASSED
  V126_MAINTENANCE_CONFIG_PREPARED
  V126_IMAGE_TRANSFERRED_AND_VERIFIED
  V126_BACKEND_STARTED
  V126_SCHEMA_RUNTIME_GATE_PASSED
  MANUAL_SMOKE_AUTHORIZED
  MANUAL_SMOKE_PASSED
  PUBLIC_DRAIN_REACTIVATED
  V126_BACKEND_STOPPED_FOR_OFF_TRANSITION
  MAINTENANCE_OFF_CONFIG_VERIFIED
  FINAL_V126_BACKEND_STARTED
  ORDINARY_CADDY_RESTORED
  FINAL_PUBLIC_GATES_PASSED
)

TEST_ROOT=''
TEST_NUMBER=0
LAST_OUTPUT=''
NEW_STATE=''
INIT_ARGS=()
FIXTURE_INIT_RELEASE_SHA=''
FIXTURE_INIT_RELEASE_TREE=''

fail() {
  printf 'V126 cutover test failed: %s\n' "$*" >&2
  exit 1
}

pass() {
  printf 'PASS: %s\n' "$1"
}

cleanup() {
  if [[ -n "${TEST_ROOT}" && -d "${TEST_ROOT}" && ! -L "${TEST_ROOT}" &&
    "$(basename "${TEST_ROOT}")" == ht12p-v126-cutover-test.* ]]; then
    rm -rf -- "${TEST_ROOT}"
  fi
}

capture_path() {
  TEST_NUMBER=$((TEST_NUMBER + 1))
  LAST_OUTPUT="${TEST_ROOT}/output-${TEST_NUMBER}.log"
}

assert_no_canary_file() {
  local path="$1"
  if [[ -f "${path}" ]] && grep -F -- "${SECRET_CANARY}" "${path}" >/dev/null 2>&1; then
    fail "secret canary reached output: ${path}"
  fi
}

expect_success() {
  local label="$1"
  shift
  capture_path
  if ! "$@" > "${LAST_OUTPUT}" 2>&1; then
    sed -n '1,160p' "${LAST_OUTPUT}" >&2
    fail "expected success: ${label}"
  fi
  assert_no_canary_file "${LAST_OUTPUT}"
  pass "${label}"
}

expect_failure() {
  local label="$1"
  local pattern="$2"
  shift 2
  capture_path
  if "$@" > "${LAST_OUTPUT}" 2>&1; then
    sed -n '1,160p' "${LAST_OUTPUT}" >&2
    fail "expected fail-closed rejection: ${label}"
  fi
  if [[ -n "${pattern}" ]] && ! grep -E -- "${pattern}" "${LAST_OUTPUT}" >/dev/null 2>&1; then
    sed -n '1,160p' "${LAST_OUTPUT}" >&2
    fail "rejection text mismatch for ${label}: ${pattern}"
  fi
  assert_no_canary_file "${LAST_OUTPUT}"
  pass "${label}"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

invoke_script() {
  local script="$1"
  shift
  bash "${script}" "$@"
}

stage_csv() {
  local IFS=,
  printf '%s' "${STAGES[*]}"
}

stage_artifacts_oracle() {
  case "$1" in
    BASELINE_VERIFIED)
      printf '%s\n' 'baseline-caddy,baseline-env,database-url-binding,local-baseline,main-actions,maintenance-identities,remote-admission-source,remote-compose-source,remote-maintenance-check-source,staging-baseline'
      ;;
    PRE_DRAIN_BACKUP_REHEARSED)
      printf '%s\n' 'pre-drain-backup-dump,pre-drain-backup-inventory,pre-drain-backup-proof,pre-drain-backup-rehearsal,pre-drain-globals'
      ;;
    CADDY_CANDIDATE_INSTALLED_AND_RELOADED)
      printf '%s\n' 'caddy-activation,caddy-candidate,caddy-diff,caddy-original'
      ;;
    PUBLIC_DRAIN_ACTIVE) printf '%s\n' 'public-drain-active' ;;
    V125_BACKEND_STOPPED) printf '%s\n' 'v125-backend-stopped' ;;
    ZERO_WRITER_GATE_PASSED) printf '%s\n' 'zero-writer-v125' ;;
    QUIESCED_BACKUP_REHEARSED)
      printf '%s\n' 'quiesced-backup-dump,quiesced-backup-inventory,quiesced-backup-proof,quiesced-backup-rehearsal'
      ;;
    FINAL_V125_PREFLIGHT_PASSED)
      printf '%s\n' 'final-v125-preflight,final-v125-preflight-source'
      ;;
    V126_MAINTENANCE_CONFIG_PREPARED) printf '%s\n' 'maintenance-v126_smoke' ;;
    V126_IMAGE_TRANSFERRED_AND_VERIFIED)
      printf '%s\n' 'local-v126-image-archive,v126-image-archive,v126-image-transfer-ready,v126-image-transferred'
      ;;
    V126_BACKEND_STARTED) printf '%s\n' 'v126-backend-first-started' ;;
    V126_SCHEMA_RUNTIME_GATE_PASSED) printf '%s\n' 'v126-schema-runtime' ;;
    MANUAL_SMOKE_AUTHORIZED) printf '%s\n' 'manual-smoke-handoff,manual-smoke-window' ;;
    MANUAL_SMOKE_PASSED) printf '%s\n' 'manual-smoke-evidence,manual-smoke-passed' ;;
    PUBLIC_DRAIN_REACTIVATED) printf '%s\n' 'public-drain-reactivated' ;;
    V126_BACKEND_STOPPED_FOR_OFF_TRANSITION) printf '%s\n' 'v126-off-transition-backend-stopped' ;;
    MAINTENANCE_OFF_CONFIG_VERIFIED) printf '%s\n' 'maintenance-off' ;;
    FINAL_V126_BACKEND_STARTED) printf '%s\n' 'v126-backend-final-started' ;;
    ORDINARY_CADDY_RESTORED) printf '%s\n' 'ordinary-caddy-restored' ;;
    FINAL_PUBLIC_GATES_PASSED) printf '%s\n' 'final-public-gates' ;;
    *) fail "unknown harness stage artifact oracle: $1" ;;
  esac
}

stage_local_artifacts_oracle() {
  case "$1" in
    BASELINE_VERIFIED) printf '%s\n' 'local-baseline,main-actions' ;;
    FINAL_V125_PREFLIGHT_PASSED) printf '%s\n' 'final-v125-preflight-source' ;;
    V126_IMAGE_TRANSFERRED_AND_VERIFIED) printf '%s\n' 'local-v126-image-archive' ;;
    MANUAL_SMOKE_AUTHORIZED) printf '%s\n' 'manual-smoke-handoff' ;;
    MANUAL_SMOKE_PASSED) printf '%s\n' 'manual-smoke-evidence' ;;
    *) printf '\n' ;;
  esac
}

stage_remote_action_oracle() {
  case "$1" in
    BASELINE_VERIFIED) printf '%s\n' baseline ;;
    PRE_DRAIN_BACKUP_REHEARSED|QUIESCED_BACKUP_REHEARSED) printf '%s\n' backup-rehearsal ;;
    CADDY_CANDIDATE_INSTALLED_AND_RELOADED) printf '%s\n' caddy-activate ;;
    PUBLIC_DRAIN_ACTIVE|PUBLIC_DRAIN_REACTIVATED) printf '%s\n' public-drain-on ;;
    V125_BACKEND_STOPPED|V126_BACKEND_STOPPED_FOR_OFF_TRANSITION) printf '%s\n' stop-backend ;;
    ZERO_WRITER_GATE_PASSED) printf '%s\n' zero-writer ;;
    FINAL_V125_PREFLIGHT_PASSED) printf '%s\n' final-v125-preflight ;;
    V126_MAINTENANCE_CONFIG_PREPARED|MAINTENANCE_OFF_CONFIG_VERIFIED) printf '%s\n' transform-maintenance ;;
    V126_IMAGE_TRANSFERRED_AND_VERIFIED) printf '%s\n' 'image-prepare,image-load' ;;
    V126_BACKEND_STARTED|FINAL_V126_BACKEND_STARTED) printf '%s\n' start-v126 ;;
    V126_SCHEMA_RUNTIME_GATE_PASSED) printf '%s\n' schema-runtime-gate ;;
    MANUAL_SMOKE_AUTHORIZED) printf '%s\n' open-manual-smoke ;;
    MANUAL_SMOKE_PASSED) printf '%s\n' record-manual-smoke ;;
    ORDINARY_CADDY_RESTORED) printf '%s\n' restore-caddy ;;
    FINAL_PUBLIC_GATES_PASSED) printf '%s\n' final-public-gates ;;
    *) fail "unknown harness stage remote action oracle: $1" ;;
  esac
}

stage_remote_artifacts_oracle() {
  local stage="$1"
  local all local_items
  all="$(stage_artifacts_oracle "${stage}")"
  local_items="$(stage_local_artifacts_oracle "${stage}")"
  python3 - "${all}" "${local_items}" <<'PY'
import sys
all_items = [item for item in sys.argv[1].split(",") if item]
local_items = {item for item in sys.argv[2].split(",") if item}
print(",".join(item for item in all_items if item not in local_items))
PY
}

validate_stage_markers() {
  python3 - "$1" "$(stage_csv)" <<'PY'
import re
import sys

path, expected_csv = sys.argv[1:]
expected = expected_csv.split(",")
marker_re = re.compile(r"^# V126_STAGE_([A-Z0-9_]+)_(BEGIN|END)$")
markers = []
with open(path, "rt", encoding="utf-8") as handle:
    for number, raw in enumerate(handle, start=1):
        match = marker_re.fullmatch(raw.rstrip("\n"))
        if match:
            markers.append((match.group(1), match.group(2), number))
wanted = []
for stage in expected:
    wanted.extend(((stage, "BEGIN"), (stage, "END")))
actual = [(stage, kind) for stage, kind, _ in markers]
if actual != wanted:
    raise SystemExit(f"stage marker sequence mismatch: expected={wanted!r} actual={actual!r}")
if len(markers) != 40:
    raise SystemExit(f"expected exactly 40 marker lines, found {len(markers)}")
PY
}

extract_stage_block() {
  local source="$1"
  local stage="$2"
  local target="$3"
  python3 - "${source}" "${stage}" "${target}" "$(stage_csv)" <<'PY'
import re
import sys

source, stage, target, expected_csv = sys.argv[1:]
expected = expected_csv.split(",")
if stage not in expected:
    raise SystemExit(f"unknown stage requested from extractor: {stage}")
lines = open(source, "rt", encoding="utf-8").readlines()
begin = f"# V126_STAGE_{stage}_BEGIN\n"
end = f"# V126_STAGE_{stage}_END\n"
begin_indexes = [i for i, line in enumerate(lines) if line == begin]
end_indexes = [i for i, line in enumerate(lines) if line == end]
if len(begin_indexes) != 1 or len(end_indexes) != 1 or begin_indexes[0] >= end_indexes[0]:
    raise SystemExit(
        f"stage block is not uniquely extractable: {stage} "
        f"begin={begin_indexes} end={end_indexes}"
    )
unexpected = []
marker_re = re.compile(r"^# V126_STAGE_([A-Z0-9_]+)_(BEGIN|END)\n$")
for line in lines[begin_indexes[0] + 1 : end_indexes[0]]:
    if marker_re.fullmatch(line):
        unexpected.append(line.rstrip())
if unexpected:
    raise SystemExit(f"nested stage marker rejected: {unexpected}")
with open(target, "wt", encoding="utf-8", newline="") as handle:
    handle.writelines(lines[begin_indexes[0] : end_indexes[0] + 1])
PY
}

extract_function_source() {
  local source="$1"
  local function_name="$2"
  local target="$3"
  python3 - "${source}" "${function_name}" "${target}" <<'PY'
import re
import sys

source, function_name, target = sys.argv[1:]
lines = open(source, "rt", encoding="utf-8").readlines()
declaration = f"{function_name}() {{\n"
starts = [index for index, line in enumerate(lines) if line == declaration]
if len(starts) != 1:
    raise SystemExit(f"function must be declared exactly once: {function_name} actual={starts}")
start = starts[0]
next_function = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*\(\) \{\n$")
end = len(lines)
for index in range(start + 1, len(lines)):
    if next_function.fullmatch(lines[index]):
        end = index
        break
with open(target, "wt", encoding="utf-8", newline="") as handle:
    handle.writelines(lines[start:end])
PY
}

extract_recovery_block() {
  local source="$1"
  local recovery="$2"
  local target="$3"
  python3 - "${source}" "${recovery}" "${target}" <<'PY'
import sys

source, recovery, target = sys.argv[1:]
lines = open(source, "rt", encoding="utf-8").readlines()
begin = f"# V126_RECOVERY_{recovery}_BEGIN\n"
end = f"# V126_RECOVERY_{recovery}_END\n"
begin_indexes = [index for index, line in enumerate(lines) if line == begin]
end_indexes = [index for index, line in enumerate(lines) if line == end]
if len(begin_indexes) != 1 or len(end_indexes) != 1 or begin_indexes[0] >= end_indexes[0]:
    raise SystemExit(
        f"recovery block is not uniquely extractable: {recovery} "
        f"begin={begin_indexes} end={end_indexes}"
    )
with open(target, "wt", encoding="utf-8", newline="") as handle:
    handle.writelines(lines[begin_indexes[0] : end_indexes[0] + 1])
PY
}

assert_literals_in_order() {
  local path="$1"
  local label="$2"
  shift 2
  python3 - "${path}" "${label}" "$@" <<'PY'
import sys

path, label, *patterns = sys.argv[1:]
text = open(path, "rt", encoding="utf-8").read()
cursor = 0
for pattern in patterns:
    location = text.find(pattern, cursor)
    if location < 0:
        raise SystemExit(f"{label}: missing or out-of-order source fragment: {pattern!r}")
    cursor = location + len(pattern)
PY
}

validate_no_build_surface() {
  python3 - "$1" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, "rt", encoding="utf-8").read()
patterns = {
    "Docker build": r"(?m)^[^#\n]*\bdocker\s+build(?:x\s+build)?\b",
    "Compose build subcommand": r"(?m)^[^#\n]*\b(?:docker\s+compose|docker-compose|remote_compose)\b[^\n]*(?<!no-)\bbuild\b",
    "Compose --build option": r"(?m)^[^#\n]*\s--build(?:\s|$)",
    "deploy wrapper": r"(?m)^[^#\n]*(?:deploy-staging(?:-controlmaster)?|build-staging-images|package-staging-bundle)\.sh\b",
    "build command indirection": r"(?m)^[^#\n]*(?:cmd|command|verb|action|subcommand|helper|wrapper)\s*=\s*[^\n#]*\bbuild(?:x)?\b",
    "Docker build array indirection": r"(?m)^[^#\n]*\([^\n)]*\bdocker\b[^\n)]*\bbuild(?:x)?\b[^\n)]*\)",
    "dynamic Docker or Compose subcommand": r"(?m)^[^#\n]*\b(?:docker|remote_compose)\s+['\"]?\$(?:\{|[A-Za-z_])",
    "variable command build": r"(?m)^[^#\n]*(?:['\"]\$\{?[A-Za-z_][A-Za-z0-9_]*\}?['\"]|\$\{?[A-Za-z_][A-Za-z0-9_]*\}?)\s+build(?:x)?\b",
    "eval indirection": r"(?m)^[^#\n]*\beval\b",
}
for label, pattern in patterns.items():
    match = re.search(pattern, text)
    if match:
        line = text.count("\n", 0, match.start()) + 1
        raise SystemExit(f"{label} is forbidden at {path}:{line}")
PY
}

validate_database_client_targets() {
  python3 - "$1" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, "rt", encoding="utf-8").read()
# The embedded extractor validates its generated host-side psql artifact separately.
start = text.find("extract_booking_preflight() {\n")
if start >= 0:
    end = text.find("\nvalidate_manual_smoke_evidence() {", start)
    if end < 0:
        raise SystemExit("booking preflight extractor boundary is unavailable")
    text = text[:start] + ("\n" * text[start:end].count("\n")) + text[end:]

text = text.replace("\\\n", " ")
commands = ("psql", "pg_dump", "pg_dumpall", "pg_restore", "createdb", "dropdb", "pg_isready")
seen = {command: 0 for command in commands}

def command_segment(start):
    tail = text[start:]
    boundary = re.search(r"(?:;|&&|\|\||\n)", tail)
    return tail[:boundary.start()] if boundary else tail

for command in commands:
    for match in re.finditer(rf"(?<![A-Za-z0-9_]){command}(?=\s)", text):
        seen[command] += 1
        before = text[max(0, match.start() - 180):match.start()]
        client = " ".join(command_segment(match.start()).split())
        context = " ".join((before + client).split())
        if command in ("psql", "pg_dump", "pg_dumpall", "pg_isready"):
            target = re.search(r"(?:^|\s)(?:-d\s+(\S+)|--dbname(?:=|\s+)(\S+))", client)
            if not target:
                line = text.count("\n", 0, match.start()) + 1
                raise SystemExit(f"{command} lacks an explicit database target at {path}:{line}: {context}")
            target_value = next(value for value in target.groups() if value is not None)
            if "POSTGRES_DB" in target_value and "${POSTGRES_DB:?}" not in before:
                line = text.count("\n", 0, match.start()) + 1
                raise SystemExit(f"{command} database target is not fail-closed at {path}:{line}: {context}")
        elif command == "createdb":
            if "--maintenance-db=postgres" not in client or not re.search(
                r"--template=template0\s+v126_restore_rehearsal(?:\s|$)", client
            ):
                line = text.count("\n", 0, match.start()) + 1
                raise SystemExit(f"createdb lacks exact maintenance and destination targets at {path}:{line}")
        elif command == "dropdb":
            if "--maintenance-db=postgres" not in client or not re.search(
                r"(?:^|\s)v126_restore_rehearsal(?:\s|$)", client
            ):
                line = text.count("\n", 0, match.start()) + 1
                raise SystemExit(f"dropdb lacks exact maintenance and destination targets at {path}:{line}")
        else:
            if re.search(r"(?:^|\s)(?:-d\s+\S+|--dbname(?:=|\s+)\S+)", client):
                continue
            if not re.search(r"^pg_restore\s+--list(?:\s|['\"]|$)", client):
                line = text.count("\n", 0, match.start()) + 1
                raise SystemExit(f"pg_restore is neither exact-target restore nor bounded list-only at {path}:{line}")
if not seen["psql"] or not seen["pg_restore"] or not seen["pg_dump"]:
    raise SystemExit(f"database client enumeration unexpectedly incomplete: {seen}")
print("database-client-enumeration=" + ",".join(f"{key}:{seen[key]}" for key in commands))
PY
}

validate_remote_emit_oracle() {
  python3 - "$1" <<'PY'
import re
import sys

path = sys.argv[1]
lines = open(path, "rt", encoding="utf-8").readlines()
expected = {
    "remote_baseline": [
        "database-url-binding", "maintenance-identities", "remote-compose-source",
        "remote-maintenance-check-source", "remote-admission-source", "baseline-caddy",
        "baseline-env", "staging-baseline",
    ],
    "remote_backup_rehearsal": [
        "${phase}-backup-dump", "${phase}-backup-inventory",
        "${phase}-backup-rehearsal", "pre-drain-globals", "${phase}-backup-proof",
    ],
    "remote_caddy_activate": ["caddy-original", "caddy-candidate", "caddy-diff", "caddy-activation"],
    "remote_public_drain_on": ["${proof_name%.proof}"],
    "remote_stop_backend": ["${phase}-backend-stopped"],
    "remote_zero_writer_stage": ["zero-writer-v125"],
    "remote_final_v125_preflight": ["final-v125-preflight"],
    "remote_transform_maintenance_config": ["maintenance-${lower_mode}"],
    "remote_image_prepare": ["v126-image-transfer-ready"],
    "remote_image_load": ["v126-image-archive", "v126-image-transferred"],
    "remote_start_v126": ["v126-backend-${phase}-started"],
    "remote_schema_runtime_gate": ["v126-schema-runtime"],
    "remote_open_manual_smoke": ["manual-smoke-window"],
    "remote_record_manual_smoke": ["manual-smoke-passed"],
    "remote_restore_caddy": ["ordinary-caddy-restored"],
    "remote_final_public_gates": ["final-public-gates"],
}

declarations = {}
for index, line in enumerate(lines):
    match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)\(\) \{\n", line)
    if match:
        declarations[match.group(1)] = index
ordered = sorted((index, name) for name, index in declarations.items())
ends = {name: (ordered[pos + 1][0] if pos + 1 < len(ordered) else len(lines))
        for pos, (index, name) in enumerate(ordered)}
for function, fragments in expected.items():
    if function not in declarations:
        raise SystemExit(f"remote artifact function is absent: {function}")
    body = "".join(lines[declarations[function]:ends[function]])
    emits = [line for line in body.splitlines() if "remote_emit_artifact " in line]
    if len(emits) != len(fragments):
        raise SystemExit(
            f"remote artifact emission count mismatch: {function} "
            f"expected={len(fragments)} actual={len(emits)}"
        )
    for fragment in fragments:
        matches = [line for line in emits if fragment in line]
        if len(matches) != 1:
            raise SystemExit(
                f"remote artifact emission mismatch: {function} fragment={fragment!r} "
                f"matches={matches!r}"
            )
PY
}

validate_full_dr_non_restore_surface() {
  python3 - "$1" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, "rt", encoding="utf-8").read()
client_invocations = re.findall(
    r"(?<![A-Za-z0-9_.-])(psql|createdb|dropdb|pg_restore)(?=\s)", text
)
if client_invocations != ["pg_restore"] or text.count("pg_restore --list") != 1:
    raise SystemExit(f"full-DR database client surface is not one list-only pg_restore: {client_invocations!r}")
compose_exec = [line.strip() for line in text.splitlines() if "remote_compose exec" in line]
if len(compose_exec) != 1 or "pg_restore --list" not in compose_exec[0]:
    raise SystemExit(f"full-DR Compose exec surface mismatch: {compose_exec!r}")
restore_line = next(line for line in text.splitlines() if "pg_restore" in line)
if re.search(r"(?:--dbname(?:=|\s+)|(?:^|\s)-d(?:=|\s+))", restore_line):
    raise SystemExit("full-DR pg_restore invocation contains a database target")
patterns = {
    "database restore target": r"--dbname(?:=|\s+)",
    "SQL/data mutation": r"(?i)\b(?:insert|update|delete|alter|truncate|create\s+database|drop\s+database|copy\s+\S+\s+from)\b",
    "migration mutation": r"(?i)\b(?:flyway|liquibase)\s+(?:migrate|repair|undo|clean)\b",
    "backend/service start": r"(?m)^[^#\n]*\b(?:remote_compose\s+(?:up|start|restart)|docker\s+(?:start|restart))\b",
    "restore helper or wrapper": r"(?m)^[^#\n]*\b(?:apply|import|load|perform|run)[A-Za-z0-9_-]*(?:_|-)?(?:dump|restore)\b",
    "dynamic execution": r"(?m)^[^#\n]*(?:\beval\b|\+=)",
}
for label, pattern in patterns.items():
    match = re.search(pattern, text)
    if match:
        line = text.count("\n", 0, match.start()) + 1
        raise SystemExit(f"full-DR contains forbidden {label} at {path}:{line}")
for forbidden in ("restore_performed=true", "data_mutation=", "migration_mutation="):
    if forbidden in text:
        raise SystemExit(f"full-DR contains forbidden mutation declaration: {forbidden}")
if "restore_performed=false" not in text or "result=DR_AUTHORIZATION_REQUIRED" not in text:
    raise SystemExit("full-DR lacks its terminal non-restore result")
PY
}

make_marker_fixture() {
  local source="$1"
  local target="$2"
  local mode="$3"
  local stage="$4"
  python3 - "${source}" "${target}" "${mode}" "${stage}" <<'PY'
import sys

source, target, mode, stage = sys.argv[1:]
lines = open(source, "rt", encoding="utf-8").readlines()
marker = f"# V126_STAGE_{stage}_BEGIN\n"
matches = [i for i, line in enumerate(lines) if line == marker]
if len(matches) != 1:
    raise SystemExit("source marker required exactly once for fixture")
index = matches[0]
if mode == "missing":
    del lines[index]
elif mode == "duplicate":
    lines.insert(index, marker)
else:
    raise SystemExit("unknown marker fixture mode")
with open(target, "wt", encoding="utf-8", newline="") as handle:
    handle.writelines(lines)
PY
}

make_instrumented_script() {
  local source="$1"
  local target="$2"
  local keep_stage="${3:-}"
  local oracle_specs=''
  local oracle_stage
  for oracle_stage in "${STAGES[@]}"; do
    oracle_specs+="$(stage_artifacts_oracle "${oracle_stage}")"$'\n'
  done
  validate_stage_markers "${source}"
  python3 - "${source}" "${target}" "$(stage_csv)" "${keep_stage}" "${oracle_specs}" <<'PY'
import hashlib
import os
import sys

source, target, stages_csv, keep_stage, oracle_specs = sys.argv[1:]
stages = stages_csv.split(",")
spec_lines = oracle_specs.splitlines()
if len(spec_lines) != len(stages):
    raise SystemExit("harness stage artifact oracle is incomplete")
expected_artifacts = dict(zip(stages, spec_lines))
lines = open(source, "rt", encoding="utf-8").readlines()
result = []
cursor = 0
for stage in stages:
    begin_line = f"# V126_STAGE_{stage}_BEGIN\n"
    end_line = f"# V126_STAGE_{stage}_END\n"
    begin = lines.index(begin_line, cursor)
    end = lines.index(end_line, begin + 1)
    result.extend(lines[cursor : begin + 1])
    if stage == keep_stage:
        result.extend(lines[begin + 1 : end])
    else:
        name = "stage_" + stage.lower()
        result.extend(
            [
                f"{name}() {{\n",
                "  local artifact_name\n",
                f"  while IFS= read -r artifact_name; do\n",
                "    printf 'ARTIFACT\\t%s\\t%s\\n' \"${artifact_name}\" "
                f"\"$(hash_text 'fixture:{stage}:'\"${{artifact_name}}\")\"\n",
                f"  done < <(printf '%s\\n' '{expected_artifacts[stage]}' | tr ',' '\\n')\n",
                "}\n",
            ]
        )
    result.append(lines[end])
    cursor = end + 1
result.extend(lines[cursor:])
with open(target, "wt", encoding="utf-8", newline="") as handle:
    handle.writelines(result)
os.chmod(target, 0o700)
PY
}

set_init_args() {
  local state_dir="$1"
  local fixture_release_sha="${FIXTURE_INIT_RELEASE_SHA:-${RELEASE_SHA}}"
  local fixture_release_tree="${FIXTURE_INIT_RELEASE_TREE:-${RELEASE_TREE}}"
  INIT_ARGS=(
    --state-dir "${state_dir}"
    --run-id "ht12p-$(basename "${state_dir}" | tr '[:upper:]_' '[:lower:]-')"
    --release-sha "${fixture_release_sha}"
    --release-tree "${fixture_release_tree}"
    --release-parents "${RELEASE_PARENTS}"
    --main-actions-run-id "${MAIN_ACTIONS_RUN_ID}"
    --release-worktree "${TEST_ROOT}/release-worktree"
    --remote fixture-remote
    --staging-path "${TEST_ROOT}/remote-staging"
    --database-url-file "${TEST_ROOT}/remote-secrets/database-url"
    --maintenance-identities-file "${TEST_ROOT}/remote-secrets/maintenance-identities"
    --v126-image-tag "hookah-v126:${fixture_release_sha}"
    --v126-image-id "${V126_IMAGE_ID}"
    --v125-image-tag "hookah-v125:${V125_SOURCE_SHA}"
  )
}

new_state() {
  local script="$1"
  local label="$2"
  NEW_STATE="${TEST_ROOT}/state-${label}"
  set_init_args "${NEW_STATE}"
  expect_success "initialize ${label}" invoke_script "${script}" init "${INIT_ARGS[@]}"
}

expect_missing_init_option() {
  local option="$1"
  local label="missing-${option#--}"
  local state_dir="${TEST_ROOT}/state-${label}"
  local -a filtered=()
  local index=0
  set_init_args "${state_dir}"
  while (( index < ${#INIT_ARGS[@]} )); do
    if [[ "${INIT_ARGS[${index}]}" == "${option}" ]]; then
      index=$((index + 2))
      continue
    fi
    filtered+=("${INIT_ARGS[${index}]}" "${INIT_ARGS[$((index + 1))]}")
    index=$((index + 2))
  done
  expect_failure "init rejects ${label}" 'rejected|must|required|missing|invalid' \
    invoke_script "${CUTOVER_SCRIPT}" init "${filtered[@]}"
  [[ ! -e "${state_dir}" && ! -L "${state_dir}" ]] ||
    fail "rejected init created state: ${label}"
}

seed_chain() {
  local state_dir="$1"
  local count="$2"
  local expected_specs=''
  local fixture_stage
  for fixture_stage in "${STAGES[@]}"; do
    expected_specs+="$(stage_artifacts_oracle "${fixture_stage}")"$'\n'
  done
  python3 - "${state_dir}" "${count}" "$(stage_csv)" "${expected_specs}" <<'PY'
import hashlib
import json
import os
import sys

state_dir, raw_count, stages_csv, expected_specs_raw = sys.argv[1:]
count = int(raw_count)
stages = stages_csv.split(",")
spec_lines = expected_specs_raw.splitlines()
if len(spec_lines) != len(stages):
    raise SystemExit("fixture stage artifact contract is incomplete")
expected_artifacts = {
    stage: spec.split(",") for stage, spec in zip(stages, spec_lines)
}
tokens = {
    "A": "AUTHORIZE_V126_CUTOVER_GATE_A",
    "B": "AUTHORIZE_V126_MANUAL_SMOKE_GATE_B",
    "C": "AUTHORIZE_V126_OFF_TRANSITION_GATE_C",
}
anchors = {"A": stages[0], "B": stages[11], "C": stages[13]}
manifest = json.load(open(os.path.join(state_dir, "run.json"), "rt", encoding="utf-8"))

def write_document(path, document):
    payload = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(fd, "wb") as handle:
        handle.write(payload)
    digest = hashlib.sha256(payload).hexdigest()
    checksum = path + ".sha256"
    fd = os.open(checksum, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(fd, "wt", encoding="ascii") as handle:
        handle.write(digest + "\n")
    return digest

receipt_hashes = {}
authorization_hashes = {}
for index, stage in enumerate(stages[:count], start=1):
    gate = "NONE" if index == 1 else ("A" if index <= 12 else ("B" if index <= 14 else "C"))
    if gate != "NONE" and gate not in authorization_hashes:
        anchor = anchors[gate]
        if anchor not in receipt_hashes:
            raise SystemExit(f"fixture authorization anchor unavailable: {gate}")
        authorization = {
            "anchor_receipt_sha256": receipt_hashes[anchor],
            "anchor_stage": anchor,
            "authorized_at": "2026-09-01T00:00:00Z",
            "format_version": 1,
            "gate": gate,
            "release_sha": manifest["release_sha"],
            "result_category": "AUTHORIZED",
            "run_id": manifest["run_id"],
            "script_sha256": manifest["script_sha256"],
            "token_sha256": hashlib.sha256(tokens[gate].encode()).hexdigest(),
        }
        authorization_hashes[gate] = write_document(
            os.path.join(state_dir, "authorizations", f"GATE_{gate}.authorization.json"),
            authorization,
        )
    predecessor = "NONE" if index == 1 else stages[index - 2]
    predecessor_hash = "NONE" if index == 1 else receipt_hashes[predecessor]
    authorization_hash = "NONE" if gate == "NONE" else authorization_hashes[gate]
    fixed = {
        "authorization_gate": gate,
        "authorization_receipt_sha256": authorization_hash,
        "predecessor_receipt_sha256": predecessor_hash,
        "predecessor_stage": predecessor,
        "release_sha": manifest["release_sha"],
        "run_id": manifest["run_id"],
        "script_sha256": manifest["script_sha256"],
        "stage": stage,
    }
    intent = {
        **fixed,
        "format_version": 1,
        "intent_at": "2026-09-01T00:00:00Z",
        "kind": "STAGE_INTENT",
    }
    prefix = f"{index:02d}-{stage}"
    intent_hash = write_document(
        os.path.join(state_dir, "intents", prefix + ".intent.json"), intent
    )
    artifact_pairs = [
        (
            name,
            hashlib.sha256(("artifact:" + stage + ":" + name).encode()).hexdigest(),
        )
        for name in expected_artifacts[stage]
    ]
    operation_path = os.path.join(
        state_dir, "artifacts", f"{index}-{stage}.operation.log"
    )
    operation_payload = "".join(
        f"ARTIFACT\t{name}\t{digest}\n" for name, digest in artifact_pairs
    ).encode()
    fd = os.open(operation_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(fd, "wb") as handle:
        handle.write(operation_payload)
    artifact_pairs.append(("operation-log", hashlib.sha256(operation_payload).hexdigest()))
    artifacts = [
        {"name": name, "sha256": digest}
        for name, digest in artifact_pairs
    ]
    receipt = {
        **fixed,
        "artifacts": sorted(artifacts, key=lambda item: item["name"]),
        "completed_at": "2026-09-01T00:00:01Z",
        "format_version": 1,
        "intent_sha256": intent_hash,
        "result_category": "PASS",
    }
    receipt_hashes[stage] = write_document(
        os.path.join(state_dir, "receipts", prefix + ".receipt.json"), receipt
    )
PY
}

rewrite_canonical_json() {
  local path="$1"
  local key="$2"
  local value="$3"
  python3 - "${path}" "${key}" "${value}" <<'PY'
import hashlib
import json
import os
import sys

path, key, value = sys.argv[1:]
with open(path, "rt", encoding="utf-8") as handle:
    document = json.load(handle)
document[key] = value
payload = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
os.chmod(path, 0o600)
with open(path, "wb") as handle:
    handle.write(payload)
os.chmod(path, 0o400)
checksum_path = path + ".sha256"
os.chmod(checksum_path, 0o600)
with open(checksum_path, "wt", encoding="ascii") as handle:
    handle.write(hashlib.sha256(payload).hexdigest() + "\n")
os.chmod(checksum_path, 0o400)
PY
}

rewrite_receipt_artifacts() {
  local path="$1"
  local action="$2"
  local name="$3"
  local value="${4:-}"
  python3 - "${path}" "${action}" "${name}" "${value}" <<'PY'
import hashlib
import json
import os
import re
import sys

path, action, name, value = sys.argv[1:]
with open(path, "rt", encoding="utf-8") as handle:
    document = json.load(handle)
artifacts = document.get("artifacts")
if not isinstance(artifacts, list):
    raise SystemExit("fixture receipt has no artifact list")
matches = [index for index, item in enumerate(artifacts) if item.get("name") == name]
if action in ("remove", "replace", "duplicate") and len(matches) != 1:
    raise SystemExit(f"fixture artifact must occur exactly once: {name}")
if action == "remove":
    del artifacts[matches[0]]
elif action == "replace":
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise SystemExit("replacement hash must be canonical")
    artifacts[matches[0]]["sha256"] = value
elif action == "duplicate":
    artifacts.append(dict(artifacts[matches[0]]))
elif action == "add":
    if matches or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise SystemExit("added artifact fixture is invalid")
    artifacts.append({"name": name, "sha256": value})
else:
    raise SystemExit(f"unknown artifact rewrite action: {action}")
artifacts.sort(key=lambda item: item.get("name", ""))
payload = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
os.chmod(path, 0o600)
with open(path, "wb") as handle:
    handle.write(payload)
os.chmod(path, 0o400)
checksum_path = path + ".sha256"
os.chmod(checksum_path, 0o600)
with open(checksum_path, "wt", encoding="ascii") as handle:
    handle.write(hashlib.sha256(payload).hexdigest() + "\n")
os.chmod(checksum_path, 0o400)
PY
}

assert_state_modes() {
  python3 - "$1" <<'PY'
import os
import stat
import sys

root = sys.argv[1]
for directory in (
    root,
    os.path.join(root, "artifacts"),
    os.path.join(root, "authorizations"),
    os.path.join(root, "intents"),
    os.path.join(root, "receipts"),
    os.path.join(root, "recovery"),
    os.path.join(root, "tmp"),
):
    mode = stat.S_IMODE(os.stat(directory).st_mode)
    if mode != 0o700:
        raise SystemExit(f"state directory mode mismatch: {directory} {mode:o}")
for directory, _, files in os.walk(root):
    for filename in files:
        path = os.path.join(directory, filename)
        mode = stat.S_IMODE(os.stat(path).st_mode)
        if filename.endswith((".json", ".sha256", ".log")) and mode != 0o400:
            raise SystemExit(f"immutable state file mode mismatch: {path} {mode:o}")
PY
}

assert_file_contains_once() {
  local path="$1"
  local value="$2"
  local count
  count="$(grep -F -c -- "${value}" "${path}" || true)"
  [[ "${count}" == 1 ]] || fail "expected exactly one occurrence in ${path}: ${value} (found ${count})"
}

run_real_stage_artifact_fixture() {
  local stage="$1"
  local expected_actions="$2"
  local remote_artifacts="$3"
  local fixture_root="$4"
  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${stage}" "${expected_actions}" \
    "${remote_artifacts}" "${fixture_root}" "${RELEASE_SHA}" "${V126_IMAGE_ID}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_stage="$2"
fixture_expected_actions="$3"
fixture_remote_artifacts="$4"
STATE_DIR="$5"
RUN_ID=fixture-stage-artifacts
RELEASE_SHA="$6"
RELEASE_WORKTREE="${STATE_DIR}/release-worktree"
REMOTE=fixture-remote
STAGING_PATH="${STATE_DIR}/remote-staging"
DATABASE_URL_FILE="${STATE_DIR}/database-url"
MAINTENANCE_IDENTITIES_FILE="${STATE_DIR}/maintenance-identities"
V126_IMAGE_TAG="hookah-v126:${RELEASE_SHA}"
V126_IMAGE_ID="$7"
V125_IMAGE_TAG="hookah-v125:${V125_SOURCE_SHA}"
mkdir -m 0700 -p "${STATE_DIR}/artifacts" "${STATE_DIR}/tmp" \
  "${RELEASE_WORKTREE}" "${STAGING_PATH}"
printf '%s\n' 'postgresql://fixture.invalid/exact' > "${DATABASE_URL_FILE}"
printf '%s\n' 'user_id=1' > "${MAINTENANCE_IDENTITIES_FILE}"
chmod 0600 "${DATABASE_URL_FILE}" "${MAINTENANCE_IDENTITIES_FILE}"

verify_release_baseline_local() {
  local_emit_artifact local-baseline "$(hash_text fixture-local-baseline)"
  local_emit_artifact main-actions "$(hash_text fixture-main-actions)"
}
git_object_sha256() { hash_text "git-object:$*"; }
receipt_artifact_hash() { hash_text "receipt-artifact:$*"; }
extract_booking_preflight() {
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > "$1"
  chmod 0600 "$1"
  hash_file "$1"
}
require_cmd() { :; }
run_tracked_command() {
  [[ "$1" == remote-rsync ]] || die "unexpected tracked command in stage fixture: $1"
  if [[ "${fixture_stage}" == V126_IMAGE_TRANSFERRED_AND_VERIFIED ]]; then
    printf 'tracked %s\n' "$*" >> "${STATE_DIR}/command-spy.log"
  fi
}
docker() {
  if [[ "${fixture_stage}" == V126_IMAGE_TRANSFERRED_AND_VERIFIED ]]; then
    printf 'docker %s\n' "$*" >> "${STATE_DIR}/command-spy.log"
  fi
  if [[ "$*" == "image inspect --format {{.Id}} ${V126_IMAGE_TAG}" ]]; then
    printf '%s\n' "${V126_IMAGE_ID}"
    return 0
  fi
  if [[ "${1:-}" == save && "${2:-}" == --output && "${4:-}" == "${V126_IMAGE_TAG}" ]]; then
    python3 - "$3" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID#sha256:}" <<'PY'
import io
import json
import tarfile
import sys

target, tag, digest = sys.argv[1:]
config = b"{}"
layer = b"fixture-layer"
manifest = json.dumps([{
    "Config": digest + ".json",
    "RepoTags": [tag],
    "Layers": ["layer.tar"],
}], separators=(",", ":")).encode()
with tarfile.open(target, "w") as archive:
    for name, payload in (("manifest.json", manifest), (digest + ".json", config), ("layer.tar", layer)):
        member = tarfile.TarInfo(name)
        member.size = len(payload)
        member.mode = 0o600
        archive.addfile(member, io.BytesIO(payload))
PY
    return 0
  fi
  die "unexpected Docker call in stage artifact fixture: $*"
}
validate_manual_smoke_evidence() {
  cp "$1" "$2"
  chmod 0400 "$2"
}

IFS=, read -r -a fixture_actions <<< "${fixture_expected_actions}"
fixture_action_index=0
run_remote() {
  local action="$1"
  shift
  if [[ "${fixture_stage}" == V126_IMAGE_TRANSFERRED_AND_VERIFIED ]]; then
    printf 'remote %s\n' "${action}" >> "${STATE_DIR}/command-spy.log"
  fi
  (( fixture_action_index < ${#fixture_actions[@]} )) ||
    die "extra remote action in real stage: ${fixture_stage}:${action}"
  [[ "${action}" == "${fixture_actions[${fixture_action_index}]}" ]] ||
    die "wrong remote action in real stage: ${fixture_stage}:${action}"
  fixture_action_index=$((fixture_action_index + 1))
  local emitted="${fixture_remote_artifacts}"
  if [[ "${fixture_stage}" == V126_IMAGE_TRANSFERRED_AND_VERIFIED ]]; then
    case "${action}" in
      image-prepare) emitted='v126-image-transfer-ready' ;;
      image-load) emitted='v126-image-archive,v126-image-transferred' ;;
      *) die "unexpected image-transfer action: ${action}" ;;
    esac
  fi
  local artifact_name
  while IFS= read -r artifact_name; do
    [[ -n "${artifact_name}" ]] || continue
    printf 'ARTIFACT\t%s\t%s\n' "${artifact_name}" \
      "$(hash_text "fixture-remote:${fixture_stage}:${artifact_name}")"
  done < <(printf '%s\n' "${emitted}" | tr ',' '\n')
}

evidence="${STATE_DIR}/manual-evidence.json"
printf '%s\n' '{}' > "${evidence}"
chmod 0400 "${evidence}"
function_name="stage_$(printf '%s' "${fixture_stage}" | tr '[:upper:]' '[:lower:]')"
declare -F "${function_name}" >/dev/null || die "real stage function is unavailable: ${function_name}"
"${function_name}" "${evidence}"
(( fixture_action_index == ${#fixture_actions[@]} )) ||
  die "real stage omitted its expected remote action: ${fixture_stage}"
SH
}

test_independent_stage_artifact_contract() {
  local source_specs oracle_specs stage
  source_specs="$(bash -s -- "${CUTOVER_SCRIPT}" "$(stage_csv)" <<'SH'
set -Eeuo pipefail
source "$1"
IFS=, read -r -a fixture_stages <<< "$2"
for fixture_stage in "${fixture_stages[@]}"; do
  stage_expected_artifacts "${fixture_stage}"
done
SH
)"
  oracle_specs=''
  for stage in "${STAGES[@]}"; do
    oracle_specs+="$(stage_artifacts_oracle "${stage}")"$'\n'
  done
  [[ "${source_specs}" == "${oracle_specs%$'\n'}" ]] ||
    fail 'production stage artifact mapping differs from the independent harness oracle'
  expect_success 'remote helper artifact emissions match the independent oracle' \
    validate_remote_emit_oracle "${CUTOVER_SCRIPT}"

  local expected_actions remote_artifacts expected output_root
  for stage in "${STAGES[@]}"; do
    expected_actions="$(stage_remote_action_oracle "${stage}")"
    remote_artifacts="$(stage_remote_artifacts_oracle "${stage}")"
    expected="$(stage_artifacts_oracle "${stage}")"
    output_root="${TEST_ROOT}/real-stage-artifacts-${stage}"
    expect_success "real stage dispatch and artifacts ${stage}" \
      run_real_stage_artifact_fixture "${stage}" "${expected_actions}" \
      "${remote_artifacts}" "${output_root}"
    python3 - "${LAST_OUTPUT}" "${stage}" "${expected}" <<'PY'
import re
import sys

path, stage, expected_csv = sys.argv[1:]
expected = expected_csv.split(",")
actual = []
for raw in open(path, "rt", encoding="utf-8"):
    line = raw.rstrip("\n")
    if not line.startswith("ARTIFACT\t"):
        raise SystemExit(f"real stage emitted non-artifact output: {stage}: {line!r}")
    match = re.fullmatch(r"ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t([0-9a-f]{64})", line)
    if not match:
        raise SystemExit(f"real stage artifact output is malformed: {stage}: {line!r}")
    actual.append(match.group(1))
if sorted(actual) != sorted(expected) or len(actual) != len(set(actual)):
    raise SystemExit(f"real stage artifact set mismatch: {stage}: expected={expected!r} actual={actual!r}")
PY
    if [[ "${stage}" == V126_IMAGE_TRANSFERRED_AND_VERIFIED ]]; then
      python3 - "${output_root}/command-spy.log" "${RELEASE_SHA}" <<'PY'
import re
import sys

path, release_sha = sys.argv[1:]
lines = open(path, "rt", encoding="utf-8").read().splitlines()
if len(lines) != 5:
    raise SystemExit(f"image-transfer command count mismatch: {lines!r}")
expected = [
    rf"^docker image inspect --format \{{\{{\.Id\}}\}} hookah-v126:{release_sha}$",
    rf"^docker save --output .+/tmp/v126-image\.tar hookah-v126:{release_sha}$",
    r"^remote image-prepare$",
    r"^tracked remote-rsync rsync --archive --copy-links --chmod=Fu=rw,Fgo= /dev/fd/9 .+:/.*v126-image\.tar\.partial$",
    r"^remote image-load$",
]
for line, pattern in zip(lines, expected):
    if not re.fullmatch(pattern, line):
        raise SystemExit(f"image-transfer command mismatch: pattern={pattern!r} line={line!r}")
for line in lines:
    if re.search(r"(?:^|\s)(?:build|buildx|--build|deploy-staging|build-staging-images|package-staging-bundle)(?:\s|$)", line):
        raise SystemExit(f"image-transfer command spy observed a build/deploy surface: {line}")
PY
    fi
  done
  pass 'all 20 real stage dispatches and local/remote artifact sets match an independent oracle'
}

test_syntax_and_markers() {
  expect_success 'cutover sequencer bash syntax' bash -n "${CUTOVER_SCRIPT}"
  expect_success 'cutover harness bash syntax' bash -n "$0"
  expect_success 'exact ordered stage markers' validate_stage_markers "${CUTOVER_SCRIPT}"

  local stage
  local block
  local function_name
  for stage in "${STAGES[@]}"; do
    block="${TEST_ROOT}/block-${stage}.sh"
    extract_stage_block "${CUTOVER_SCRIPT}" "${stage}" "${block}"
    function_name="stage_$(printf '%s' "${stage}" | tr '[:upper:]' '[:lower:]')"
    assert_file_contains_once "${block}" "${function_name}()"
  done
  pass 'all 20 stages have one marker-bounded implementation'

  local missing="${TEST_ROOT}/markers-missing.sh"
  local duplicate="${TEST_ROOT}/markers-duplicate.sh"
  make_marker_fixture "${CUTOVER_SCRIPT}" "${missing}" missing BASELINE_VERIFIED
  make_marker_fixture "${CUTOVER_SCRIPT}" "${duplicate}" duplicate BASELINE_VERIFIED
  expect_failure 'extractor rejects a missing marker' 'marker sequence mismatch|exactly' \
    validate_stage_markers "${missing}"
  expect_failure 'extractor rejects a duplicate marker' 'marker sequence mismatch|exactly' \
    validate_stage_markers "${duplicate}"
}

test_init_contract() {
  expect_missing_init_option --run-id
  expect_missing_init_option --release-sha
  expect_missing_init_option --v126-image-tag
  expect_missing_init_option --v126-image-id
  expect_missing_init_option --database-url-file

  new_state "${CUTOVER_SCRIPT}" exact-binding
  local state="${NEW_STATE}"
  python3 - "${state}/run.json" "${CUTOVER_SCRIPT}" "${V126_IMAGE_ID}" <<'PY'
import hashlib
import json
import os
import stat
import sys

manifest_path, script_path, expected_image_id = sys.argv[1:]
raw = open(manifest_path, "rb").read()
manifest = json.loads(raw)
if raw != (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode():
    raise SystemExit("run manifest is not canonical JSON")
required = {
    "run_id": "ht12p-state-exact-binding",
    "release_sha": "ecb09601975678a41d89e5c824cc7812c7876481",
    "release_tree": "8c97996e317f0182b4871d2a2537a732d4830f64",
    "main_actions_run_id": 33536142005,
    "remote": "fixture-remote",
    "v126_image_id": expected_image_id,
}
for key, value in required.items():
    if manifest.get(key) != value:
        raise SystemExit(f"run manifest binding mismatch: {key}")
script_hash = hashlib.sha256(open(script_path, "rb").read()).hexdigest()
if manifest["script_sha256"] != script_hash:
    raise SystemExit("run manifest did not bind exact script identity")
if stat.S_IMODE(os.stat(manifest_path).st_mode) != 0o400:
    raise SystemExit("run manifest mode is not 0400")
PY
  assert_state_modes "${state}"
  pass 'run state is canonical, exactly bound, and immutable'

  local symlink_worktree="${TEST_ROOT}/release-worktree-link"
  ln -s "${TEST_ROOT}/release-worktree" "${symlink_worktree}"
  local symlink_state="${TEST_ROOT}/state-symlink-worktree"
  set_init_args "${symlink_state}"
  local -a changed=("${INIT_ARGS[@]}")
  local index=0
  while (( index < ${#changed[@]} )); do
    if [[ "${changed[${index}]}" == --release-worktree ]]; then
      changed[$((index + 1))]="${symlink_worktree}"
      break
    fi
    index=$((index + 2))
  done
  expect_failure 'init rejects symlink release worktree' 'symlink' \
    invoke_script "${CUTOVER_SCRIPT}" init "${changed[@]}"
}

test_receipt_integrity() {
  local state path
  local forged_hash='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

  new_state "${CUTOVER_SCRIPT}" order
  state="${NEW_STATE}"
  expect_failure 'stage cannot skip its predecessor' 'predecessor|receipt' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" PRE_DRAIN_BACKUP_REHEARSED

  new_state "${CUTOVER_SCRIPT}" receipt-tamper
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  chmod 0600 "${path}"
  printf ' ' >> "${path}"
  chmod 0400 "${path}"
  expect_failure 'forged receipt bytes are rejected' 'invalid|receipt|anchor|checksum' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A

  new_state "${CUTOVER_SCRIPT}" stale-release
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  rewrite_canonical_json "${path}" release_sha 2222222222222222222222222222222222222222
  expect_failure 'stale release receipt is rejected' 'invalid|receipt|anchor|mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A

  new_state "${CUTOVER_SCRIPT}" stale-run
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  rewrite_canonical_json "${path}" run_id ht12p-stale-run-id
  expect_failure 'stale run receipt is rejected' 'invalid|receipt|anchor|mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A

  new_state "${CUTOVER_SCRIPT}" stale-script-receipt
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  rewrite_canonical_json "${path}" script_sha256 3333333333333333333333333333333333333333333333333333333333333333
  expect_failure 'stale script receipt is rejected' 'invalid|receipt|anchor|mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A

  new_state "${CUTOVER_SCRIPT}" substituted-artifact
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  rewrite_receipt_artifacts "${path}" replace local-baseline "${forged_hash}"
  expect_failure 'canonically rechecksummed substituted artifact rejects before Gate A' \
    'artifact|operation log|receipt|anchor|mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  [[ ! -e "${state}/authorizations/GATE_A.authorization.json" ]] ||
    fail 'substituted receipt artifact reached Gate A authorization'

  new_state "${CUTOVER_SCRIPT}" missing-artifact
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  rewrite_receipt_artifacts "${path}" remove local-baseline
  expect_failure 'canonically rechecksummed missing artifact rejects before Gate A' \
    'artifact|operation log|receipt|anchor|mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  [[ ! -e "${state}/authorizations/GATE_A.authorization.json" ]] ||
    fail 'missing receipt artifact reached Gate A authorization'

  new_state "${CUTOVER_SCRIPT}" extra-artifact
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  rewrite_receipt_artifacts "${path}" add forged-extra "${forged_hash}"
  expect_failure 'canonically rechecksummed extra artifact rejects before Gate A' \
    'artifact|operation log|receipt|anchor|mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  [[ ! -e "${state}/authorizations/GATE_A.authorization.json" ]] ||
    fail 'extra receipt artifact reached Gate A authorization'

  new_state "${CUTOVER_SCRIPT}" operation-log-mismatch
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/artifacts/1-BASELINE_VERIFIED.operation.log"
  chmod 0600 "${path}"
  printf 'post-receipt log mutation\n' >> "${path}"
  chmod 0400 "${path}"
  expect_failure 'actual operation-log mismatch rejects before Gate A' \
    'artifact|operation log|receipt|anchor|mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  [[ ! -e "${state}/authorizations/GATE_A.authorization.json" ]] ||
    fail 'mismatched operation log reached Gate A authorization'

  new_state "${CUTOVER_SCRIPT}" intent-tamper
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/intents/01-BASELINE_VERIFIED.intent.json"
  rewrite_canonical_json "${path}" run_id ht12p-stale-intent
  expect_failure 'tampered stage intent is rejected' 'invalid|intent|receipt|anchor' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A

  new_state "${CUTOVER_SCRIPT}" receipt-symlink
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  path="${state}/receipts/01-BASELINE_VERIFIED.receipt.json"
  mv "${path}" "${TEST_ROOT}/real-receipt.json"
  ln -s "${TEST_ROOT}/real-receipt.json" "${path}"
  expect_failure 'receipt symlink is rejected' 'invalid|receipt|anchor|symlink' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A

  new_state "${CUTOVER_SCRIPT}" manifest-symlink
  state="${NEW_STATE}"
  mv "${state}/run.json" "${TEST_ROOT}/real-run.json"
  ln -s "${TEST_ROOT}/real-run.json" "${state}/run.json"
  expect_failure 'run manifest symlink is rejected' 'manifest|symlink' \
    invoke_script "${CUTOVER_SCRIPT}" status --state-dir "${state}"

  local copied_script="${TEST_ROOT}/v126-cutover-stale.sh"
  cp "${CUTOVER_SCRIPT}" "${copied_script}"
  chmod 0700 "${copied_script}"
  new_state "${copied_script}" changed-script
  state="${NEW_STATE}"
  printf '\n# identity changed after init\n' >> "${copied_script}"
  expect_failure 'changed sequencer identity is rejected' 'identity changed|identity mismatch' \
    invoke_script "${copied_script}" status --state-dir "${state}"

  new_state "${CUTOVER_SCRIPT}" prior-intent
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  expect_success 'record exact Gate A authorization for prior-intent fixture' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  path="${state}/intents/02-PRE_DRAIN_BACKUP_REHEARSED.intent.json"
  : > "${path}"
  chmod 0400 "${path}"
  expect_failure 'prior intent forbids hidden retry' 'prior intent|retry is forbidden' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" PRE_DRAIN_BACKUP_REHEARSED
}

test_gate_and_sequence_contract() {
  local instrumented="${TEST_ROOT}/v126-cutover-instrumented.sh"
  local evidence="${TEST_ROOT}/manual-evidence.txt"
  local state
  local stage
  local index
  make_instrumented_script "${CUTOVER_SCRIPT}" "${instrumented}"
  printf 'reviewed manual evidence fixture\n' > "${evidence}"
  chmod 0400 "${evidence}"
  new_state "${instrumented}" sequencer
  state="${NEW_STATE}"

  expect_failure 'stage CLI accepts only one stage' 'exactly one stage' \
    invoke_script "${instrumented}" stage --state-dir "${state}" \
    BASELINE_VERIFIED PRE_DRAIN_BACKUP_REHEARSED
  expect_failure 'no multi-stage run command exists' 'unknown command' invoke_script "${instrumented}" run

  expect_success 'execute baseline only' \
    invoke_script "${instrumented}" stage --state-dir "${state}" BASELINE_VERIFIED
  [[ "$(find "${state}/receipts" -maxdepth 1 -type f -name '*.receipt.json' | wc -l | tr -d ' ')" == 1 ]] ||
    fail 'baseline stage continued automatically'
  expect_failure 'Gate A is required before stage 2' 'Gate A authorization is required' \
    invoke_script "${instrumented}" stage --state-dir "${state}" PRE_DRAIN_BACKUP_REHEARSED
  expect_failure 'Gate A token is exact' 'token mismatch' \
    invoke_script "${instrumented}" authorize --state-dir "${state}" --gate A --authorization WRONG
  expect_success 'authorize Gate A' \
    invoke_script "${instrumented}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A

  index=0
  for stage in "${STAGES[@]}"; do
    index=$((index + 1))
    (( index >= 2 )) || continue
    (( index <= 12 )) || break
    expect_success "Gate A stage ${stage}" \
      invoke_script "${instrumented}" stage --state-dir "${state}" "${stage}"
  done
  expect_failure 'Gate A cannot enter manual smoke' 'Gate B authorization is required' \
    invoke_script "${instrumented}" stage --state-dir "${state}" MANUAL_SMOKE_AUTHORIZED
  expect_success 'authorize Gate B' \
    invoke_script "${instrumented}" authorize --state-dir "${state}" --gate B \
    --authorization AUTHORIZE_V126_MANUAL_SMOKE_GATE_B
  expect_success 'Gate B authorizes manual smoke only' \
    invoke_script "${instrumented}" stage --state-dir "${state}" MANUAL_SMOKE_AUTHORIZED
  expect_success 'Gate B records reviewed manual smoke evidence' \
    invoke_script "${instrumented}" stage --state-dir "${state}" MANUAL_SMOKE_PASSED \
    --evidence-file "${evidence}"
  expect_failure 'Gate B cannot enter the OFF transition' 'Gate C authorization is required' \
    invoke_script "${instrumented}" stage --state-dir "${state}" PUBLIC_DRAIN_REACTIVATED
  expect_success 'authorize Gate C' \
    invoke_script "${instrumented}" authorize --state-dir "${state}" --gate C \
    --authorization AUTHORIZE_V126_OFF_TRANSITION_GATE_C

  index=0
  for stage in "${STAGES[@]}"; do
    index=$((index + 1))
    (( index >= 15 )) || continue
    expect_success "Gate C stage ${stage}" \
      invoke_script "${instrumented}" stage --state-dir "${state}" "${stage}"
  done
  expect_success 'completed chain verifies all receipts' \
    invoke_script "${instrumented}" status --state-dir "${state}"
  grep -F 'next_stage=NONE' "${LAST_OUTPUT}" >/dev/null || fail 'completed chain has a pending stage'
  [[ "$(find "${state}/receipts" -maxdepth 1 -type f -name '*.receipt.json' | wc -l | tr -d ' ')" == 20 ]] ||
    fail 'completed chain does not contain exactly 20 receipts'
  [[ "$(find "${state}/intents" -maxdepth 1 -type f -name '*.intent.json' | wc -l | tr -d ' ')" == 20 ]] ||
    fail 'completed chain does not contain exactly 20 intents'
  assert_state_modes "${state}"
  pass 'authorization boundaries, predecessor chain, and durable state verify'
}

test_static_safety_contract() {
  expect_success 'cutover path contains no direct, helper, wrapper, option, or indirect build path' \
    validate_no_build_surface "${CUTOVER_SCRIPT}"
  local build_fixture="${TEST_ROOT}/forbidden-build-fixture.sh"
  local fixture_label fixture_line
  while IFS='|' read -r fixture_label fixture_line; do
    printf '#!/usr/bin/env bash\n%s\n' "${fixture_line}" > "${build_fixture}"
    expect_failure "no-build scanner catches ${fixture_label}" 'forbidden' \
      validate_no_build_surface "${build_fixture}"
  done <<'EOF'
direct Docker build|docker build -t forbidden .
BuildKit helper|docker buildx build -t forbidden .
Compose build helper|remote_compose build backend
Compose --build|remote_compose up -d --build backend
deploy wrapper|bash scripts/deploy-staging.sh
image build wrapper|scripts/deploy/build-staging-images.sh
command indirection|command=build; docker "$command" .
array indirection|builder=(docker build); "${builder[@]}" .
split dynamic subcommand|op=bu; op+=ild; docker "$op" -t forbidden .
variable Docker executable|engine=docker; "$engine" build -t forbidden .
eval indirection|eval "$untrusted_command"
EOF
  pass 'no-build scanner adversarial controls reject direct and indirect build surfaces'

  local value
  for value in \
    AUTHORIZE_V126_CUTOVER_GATE_A \
    AUTHORIZE_V126_MANUAL_SMOKE_GATE_B \
    AUTHORIZE_V126_OFF_TRANSITION_GATE_C \
    AUTHORIZE_V126_PRE_V126_ROLLBACK \
    AUTHORIZE_V126_POST_V126_FORWARD_FIX_STOP \
    AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION; do
    grep -F -- "${value}" "${CUTOVER_SCRIPT}" >/dev/null || fail "missing exact authorization token: ${value}"
  done
  pass 'all gate and recovery tokens are exact'

  grep -F -- '${DATABASE_URL:?' "${CUTOVER_SCRIPT}" >/dev/null ||
    fail 'host-side database URL is not fail-closed'
  pass 'database URL has a fail-closed required binding'

  [[ "$(grep -F -c 'printf -v cleanup_command' "${CUTOVER_SCRIPT}" || true)" == 7 ]] ||
    fail 'all seven resource cleanup families must bind a literal command'
  [[ "$(grep -F -c 'v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then' \
    "${CUTOVER_SCRIPT}" || true)" == 7 ]] ||
    fail 'cleanup EXIT traps must be one-shot and preserve the original exit status'
  local signal_name signal_status
  while IFS=: read -r signal_name signal_status; do
    [[ "$(grep -F -c \
      "fi; exit ${signal_status}\" ${signal_name}" "${CUTOVER_SCRIPT}" || true)" == 7 ]] ||
      fail "cleanup ${signal_name} traps must be one-shot with exact status ${signal_status}"
  done <<'EOF'
INT:130
TERM:143
HUP:129
EOF
  ! grep -E \
    "trap ('rm -rf -- .*temp_dir|remote_cleanup_(rehearsal|caddy_admin_snapshot|preflight_credentials|maintenance_temporaries|restored_caddy_snapshot|recovery_env_temporaries)(;|['\"]|[[:space:]]))" \
    "${CUTOVER_SCRIPT}" >/dev/null ||
    fail 'a cleanup trap still defers expansion of dynamic function locals'
  grep -F "printf -v cleanup_command 'remote_cleanup_rehearsal %q %q %q'" \
    "${CUTOVER_SCRIPT}" >/dev/null || fail 'rehearsal cleanup lacks exact literal name/owner binding'
  grep -F -- '--label "hookah.v126.rehearsal-owner=${rehearsal_owner}"' \
    "${CUTOVER_SCRIPT}" >/dev/null || fail 'rehearsal resources lack unique ownership labels'
  pass 'all cleanup traps bind literal targets and clear themselves before exact signal exits'
}

run_real_telegram_cleanup_fixture() {
  local fixture_root="$1"
  local cleanup_log="$2"
  local fixture_mode="${3:-rejection}"
  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${fixture_root}" "${cleanup_log}" \
    "${SECRET_CANARY}" "${fixture_mode}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_root="$2"
fixture_cleanup_log="$3"
fixture_canary="$4"
fixture_mode="$5"
fixture_tmp="${fixture_root}/tmp"
mkdir -m 0700 -p "${fixture_tmp}"
export TMPDIR="${fixture_tmp}"
remote_env_value() {
  [[ "$2" == TELEGRAM_BOT_TOKEN ]] || die 'unexpected Telegram environment lookup'
  printf '123456:%s_TELEGRAM\n' "${fixture_canary}"
}
curl() {
  local config=''
  local output=''
  while (( $# > 0 )); do
    case "$1" in
      --config) config="$2"; shift 2 ;;
      --output) output="$2"; shift 2 ;;
      *) die "unexpected Telegram curl argument: $1" ;;
    esac
  done
  [[ -f "${config}" && "${output}" == "${config%/curl.conf}/response.json" ]] ||
    die 'Telegram cleanup fixture path mismatch'
  if [[ "${fixture_mode}" == signal-term ]]; then
    kill -TERM "$$"
    return 99
  fi
  printf '%s\n' '{"ok":true,"result":{"url":"","pending_update_count":1}}' > "${output}"
}
rm() {
  if (( $# == 3 )) && [[ "$1" == -rf && "$2" == -- &&
    "$3" == "${fixture_tmp}"/v126-telegram-idle.* ]]; then
    printf '%s\n' "$3" >> "${fixture_cleanup_log}"
    if [[ "${fixture_mode}" == cleanup-failure ]]; then
      return 88
    fi
  fi
  command rm "$@"
}
remote_assert_telegram_idle "${fixture_root}/restricted.env"
SH
}

test_telegram_cleanup_trap() {
  local fixture_root="${TEST_ROOT}/telegram-cleanup"
  local cleanup_log="${fixture_root}/cleanup.log"
  mkdir -m 0700 "${fixture_root}"
  expect_failure 'Telegram idle rejection cleans its token-bearing temporary directory once' \
    'Telegram webhook or pending update gate failed' \
    run_real_telegram_cleanup_fixture "${fixture_root}" "${cleanup_log}"
  [[ -f "${cleanup_log}" && "$(wc -l < "${cleanup_log}" | tr -d ' ')" == 1 ]] ||
    fail 'Telegram token temporary cleanup did not run exactly once'
  local cleaned_path
  cleaned_path="$(< "${cleanup_log}")"
  [[ "${cleaned_path}" == "${fixture_root}/tmp"/v126-telegram-idle.* &&
    ! -e "${cleaned_path}" && ! -L "${cleaned_path}" ]] ||
    fail 'Telegram cleanup did not target the exact generated temporary directory'
  [[ -z "$(find "${fixture_root}/tmp" -mindepth 1 -print -quit)" ]] ||
    fail 'Telegram cleanup retained token-bearing temporary state'

  local failed_root="${TEST_ROOT}/telegram-cleanup-failure"
  local failed_log="${failed_root}/cleanup.log"
  mkdir -m 0700 "${failed_root}"
  capture_path
  local failed_status=0
  run_real_telegram_cleanup_fixture "${failed_root}" "${failed_log}" cleanup-failure \
    > "${LAST_OUTPUT}" 2>&1 || failed_status=$?
  [[ "${failed_status}" == 1 ]] ||
    fail "EXIT cleanup failure replaced the original Telegram rejection status: ${failed_status}"
  grep -F 'Telegram webhook or pending update gate failed' "${LAST_OUTPUT}" >/dev/null ||
    fail 'EXIT cleanup failure hid the original Telegram rejection'
  grep -F 'cleanup failed after EXIT' "${LAST_OUTPUT}" >/dev/null ||
    fail 'EXIT cleanup failure lacked a generic cleanup diagnostic'
  [[ "$(wc -l < "${failed_log}" | tr -d ' ')" == 1 ]] ||
    fail 'failed EXIT cleanup retried its destructive command'
  assert_no_canary_file "${LAST_OUTPUT}"
  command rm -rf -- "${failed_root}/tmp"
  pass 'EXIT cleanup failure preserves the original rejection and never retries'

  local signal_root="${TEST_ROOT}/telegram-cleanup-signal"
  local signal_log="${signal_root}/cleanup.log"
  mkdir -m 0700 "${signal_root}"
  capture_path
  local signal_status=0
  run_real_telegram_cleanup_fixture "${signal_root}" "${signal_log}" signal-term \
    > "${LAST_OUTPUT}" 2>&1 || signal_status=$?
  [[ "${signal_status}" == 143 ]] ||
    fail 'TERM cleanup did not preserve exact signal status 143'
  [[ "$(wc -l < "${signal_log}" | tr -d ' ')" == 1 ]] ||
    fail 'TERM cleanup invoked its destructive command more than once'
  cleaned_path="$(< "${signal_log}")"
  [[ ! -e "${cleaned_path}" && ! -L "${cleaned_path}" ]] ||
    fail 'TERM cleanup retained the token-bearing temporary directory'
  assert_no_canary_file "${LAST_OUTPUT}"
  pass 'TERM cleanup clears EXIT first, runs once, and exits exactly 143'
  pass 'Telegram temporary cleanup is literal-bound, exact-once, and fail-closed'
}

run_direct_dispatch_bypass_fixture() {
  local mutation_log="$1"
  bash -s -- "${CUTOVER_SCRIPT}" "${mutation_log}" "${TEST_ROOT}/remote-staging" \
    "${RELEASE_SHA}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_log="$2"
fixture_staging="$3"
fixture_release="$4"
remote_baseline() { printf '%s\n' MUTATED >> "${fixture_log}"; }
remote_dispatch_enveloped baseline "${fixture_staging}" fixture-run "${fixture_release}" \
  "hookah-v125:${V125_SOURCE_SHA}" /fixture/database /fixture/identities \
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
SH
}

extract_remote_loader_fixture() {
  local target="$1"
  python3 - "${CUTOVER_SCRIPT}" "${target}" <<'PY'
import os
import sys

source_path, target_path = sys.argv[1:]
source = open(source_path, "rb").read()
begin = b"    cat <<'REMOTE_LOADER'\n"
end = b"\nREMOTE_LOADER\n"
if source.count(begin) != 1:
    raise SystemExit("remote loader heredoc is not unique")
start = source.index(begin) + len(begin)
stop = source.index(end, start)
payload = source[start:stop] + b"\n"
fd = os.open(target_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o700)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
}

run_remote_loader_fixture() {
  local loader="$1"
  local body="$2"
  local expected_sha="$3"
  local marker="$4"
  {
    printf '%s\n' \
      V126_INTERNAL_REMOTE_ENVELOPE_V1 \
      fixture-action \
      fixture-run \
      "${RELEASE_SHA}" \
      /fixture/staging \
      "${expected_sha}" \
      "${V126_IMAGE_ID}" \
      STAGE \
      BASELINE_VERIFIED \
      NONE \
      NONE \
      NONE \
      NONE \
      9999999999999999999999999999999999999999999999999999999999999999 \
      NONE NONE NONE NONE NONE NONE NONE \
      NONE NONE NONE NONE \
      NONE NONE \
      1 \
      "${marker}"
    command cat "${body}"
  } | bash "${loader}"
}

test_remote_loader_same_use_body() {
  local loader="${TEST_ROOT}/remote-loader.sh"
  local body="${TEST_ROOT}/remote-loader-body.sh"
  local safe_marker="${TEST_ROOT}/remote-loader-safe.marker"
  local malicious_marker="${TEST_ROOT}/remote-loader-malicious.marker"
  local former_body_path="${TEST_ROOT}/remote-loader-former-path.sh"
  extract_remote_loader_fixture "${loader}"
  printf '%s\n' \
    'remote_dispatch_enveloped() {' \
    '  [[ "$1" == fixture-action ]]' \
    '  printf "%s\n" SAFE_BUFFER > "$2"' \
    '}' > "${body}"
  printf '%s\n' \
    'remote_dispatch_enveloped() {' \
    "  printf '%s\\n' MALICIOUS_PATH > '${malicious_marker}'" \
    '}' > "${former_body_path}"
  chmod 0600 "${body}" "${former_body_path}"
  local body_sha
  body_sha="$(sha256_file "${body}")"
  expect_success 'remote loader hashes and sources the same newline-preserved in-memory body' \
    run_remote_loader_fixture "${loader}" "${body}" "${body_sha}" "${safe_marker}"
  [[ "$(< "${safe_marker}")" == SAFE_BUFFER && ! -e "${malicious_marker}" ]] ||
    fail 'remote loader reopened a pathname instead of sourcing the verified body buffer'
  rm -f -- "${safe_marker}"
  expect_failure 'remote loader rejects a body whose exact in-memory hash is not enveloped' \
    'streamed sequencer identity mismatch' \
    run_remote_loader_fixture "${loader}" "${body}" \
    0000000000000000000000000000000000000000000000000000000000000000 \
    "${safe_marker}"
  [[ ! -e "${safe_marker}" && ! -e "${malicious_marker}" ]] ||
    fail 'remote loader hash mismatch reached a body dispatcher'
  grep -F 'remote_body_content="$({ cat || exit $?; printf' "${loader}" >/dev/null ||
    fail 'remote loader no longer captures a newline-preserved body buffer'
  assert_literals_in_order "${loader}" 'remote loader body same-use order' \
    'remote_body_content="$({ cat || exit $?; printf' \
    'printf '\''%s'\'' "${remote_body_content}" | sha256sum' \
    'source /dev/stdin <<< "${remote_body_content}"' \
    'unset remote_body_content'
  ! grep -E 'mktemp|remote_body_path|remote_body_file|source.*remote_body_(path|file)' \
    "${loader}" >/dev/null ||
    fail 'remote loader retains a mutable body pathname/reopen surface'
  pass 'streamed remote body identity and execution share one in-memory byte buffer'
}

test_internal_remote_dispatch_boundary() {
  local mutation_log="${TEST_ROOT}/dispatch-mutation.log"
  expect_failure 'public top-level __remote command is unavailable' 'unknown command: __remote' \
    invoke_script "${CUTOVER_SCRIPT}" __remote baseline "${TEST_ROOT}/remote-staging" \
    fixture-run "${RELEASE_SHA}"
  [[ ! -s "${mutation_log}" ]] || fail 'public __remote command reached a helper mutation'
  expect_failure 'direct internal dispatcher bypass rejects before helper mutation' \
    'internal streamed envelope|remote dispatch requires' \
    run_direct_dispatch_bypass_fixture "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] || fail 'direct dispatcher bypass reached remote helper mutation'
  ! sed -n '/main() {/,/^}/p' "${CUTOVER_SCRIPT}" | grep -F '__remote' >/dev/null ||
    fail 'public main dispatcher still exposes __remote'
  pass 'only the validated streamed envelope can reach internal remote helpers'
}

test_caddy_ordering_contract() {
  local activate="${TEST_ROOT}/function-caddy-activate.sh"
  local drain="${TEST_ROOT}/function-public-drain.sh"
  local manual="${TEST_ROOT}/function-open-manual-smoke.sh"
  local restore="${TEST_ROOT}/function-restore-caddy.sh"
  extract_function_source "${CUTOVER_SCRIPT}" remote_caddy_activate "${activate}"
  extract_function_source "${CUTOVER_SCRIPT}" remote_public_drain_on "${drain}"
  extract_function_source "${CUTOVER_SCRIPT}" remote_open_manual_smoke "${manual}"
  extract_function_source "${CUTOVER_SCRIPT}" remote_restore_caddy "${restore}"

  assert_literals_in_order "${activate}" 'Caddy candidate activation' \
    'sudo python3 - "${original}" "${candidate}"' \
    'sudo caddy validate --config "${candidate}"' \
    'sudo install -o root -g root -m 0644 "${candidate}" /etc/caddy/Caddyfile' \
    'sudo systemctl reload caddy' \
    'active_admin_config_sha256=' \
    "'activation_reload=PASS'"
  [[ "$(grep -F -c 'sudo systemctl reload caddy' "${activate}" || true)" == 1 ]] ||
    fail 'Caddy candidate activation must contain exactly one reload'
  assert_literals_in_order "${drain}" 'Caddy drain activation' \
    'remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"' \
    'activation.proof' \
    'sudo install -o root -g root -m 0600 /dev/null /etc/caddy/v126-drain.enabled' \
    'remote_assert_public_drain'
  assert_literals_in_order "${manual}" 'manual-smoke marker removal' \
    'remote_verify_proof "${run_root}/v126-schema-runtime.proof"' \
    'remote_assert_runtime' \
    'sudo rm -f -- /etc/caddy/v126-drain.enabled' \
    'remote_assert_public_live' \
    'remote_assert_protected_unauthenticated_503' \
    "'protected_unauthenticated=GENERIC_503'"
  assert_literals_in_order "${restore}" 'ordinary Caddy restoration' \
    'remote_verify_proof "${run_root}/v126-backend-final-started.proof"' \
    'remote_verify_maintenance_env_binding' \
    '"${release_sha}" OFF "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256:-}"' \
    'remote_assert_runtime' \
    'remote_assert_caddy_candidate_active' \
    'remote_assert_caddy_drain_marker' \
    'sudo install -o root -g root -m 0644 "${original}" /etc/caddy/Caddyfile' \
    'sudo systemctl reload caddy' \
    'sudo rm -f -- /etc/caddy/v126-drain.enabled' \
    'remote_assert_public_live'
  pass 'Caddy install/reload/active-proof/marker and restoration prerequisites are ordered'
}

run_real_caddy_admin_snapshot_failure_fixture() {
  local fixture_mode="$1"
  local fixture_root="$2"

  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${fixture_mode}" "${fixture_root}" \
    "${RELEASE_SHA}" "${V126_IMAGE_ID}" <<'SH'
set -Eeuo pipefail
source "$1"

fixture_mode="$2"
fixture_root="$3"
fixture_release="$4"
fixture_image_id="$5"
fixture_run_id='fixture-caddy-admin'
fixture_staging="${fixture_root}/staging"
fixture_run_root="${fixture_root}/run-root"
fixture_tmp="${fixture_root}/tmp"
fixture_evidence="${fixture_root}/evidence"
fixture_active_state="${fixture_root}/active.state"
fixture_marker_state="${fixture_root}/marker.state"
fixture_mutations="${fixture_root}/mutations.log"
fixture_cleanup="${fixture_root}/cleanup.log"
fixture_snapshot="${fixture_root}/snapshot.path"

fixture_original_sha='1111111111111111111111111111111111111111111111111111111111111111'
fixture_candidate_sha='2222222222222222222222222222222222222222222222222222222222222222'
fixture_diff_sha='3333333333333333333333333333333333333333333333333333333333333333'

mkdir -m 0700 -p \
  "${fixture_staging}" "${fixture_run_root}" "${fixture_tmp}"
export TMPDIR="${fixture_tmp}"

case "${fixture_mode}" in
  activate)
    printf '%s\n' "${fixture_original_sha}" > "${fixture_active_state}"
    printf '%s\n' false > "${fixture_marker_state}"
    ;;
  restore)
    printf '%s\n' "${fixture_candidate_sha}" > "${fixture_active_state}"
    printf '%s\n' true > "${fixture_marker_state}"
    ;;
  *) die 'unknown Caddy admin cleanup fixture mode' ;;
esac

V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_original_sha}"
V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256="${fixture_original_sha}"
V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

remote_require_run_root() {
  [[ "$1" == "${fixture_staging}" && "$2" == "${fixture_run_id}" ]] ||
    die 'fixture run-root binding mismatch'
  printf '%s\n' "${fixture_run_root}"
}

remote_verify_baseline_authority() {
  [[ "$1" == "${fixture_staging}" &&
    "$2" == "${fixture_run_id}" &&
    "$3" == "${fixture_release}" ]] ||
    die 'fixture baseline binding mismatch'
}

remote_caddy_evidence_root() {
  [[ "$1" == "${fixture_release}" && "$2" == "${fixture_run_id}" ]] ||
    die 'fixture Caddy evidence binding mismatch'
  printf '%s\n' "${fixture_evidence}"
}

remote_sudo_require_root_file() {
  :
}

remote_sudo_read_sha256_checksum() {
  case "$1" in
    */Caddyfile.original.sha256) printf '%s\n' "${fixture_original_sha}" ;;
    */Caddyfile.drain.sha256) printf '%s\n' "${fixture_candidate_sha}" ;;
    *) die "unexpected fixture checksum read: $1" ;;
  esac
}

remote_initialize_compose() {
  [[ "$1" == "${fixture_staging}" &&
    "$2" == "${fixture_run_id}" &&
    "$3" == "${fixture_release}" &&
    "$4" == "hookah-v126:${fixture_release}" ]] ||
    die 'fixture Compose binding mismatch'
}

remote_verify_proof() {
  [[ "$1" == "${fixture_run_root}/v126-backend-final-started.proof" ]] ||
    die 'fixture prerequisite-proof binding mismatch'
}

remote_verify_maintenance_env_binding() {
  [[ "$1" == "${fixture_staging}" &&
    "$2" == "${fixture_run_root}" &&
    "$3" == "${fixture_run_id}" &&
    "$4" == "${fixture_release}" &&
    "$5" == OFF &&
    "$6" == "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256}" ]] ||
    die 'fixture maintenance binding mismatch'
}

remote_assert_runtime() {
  [[ "$1" == "${fixture_staging}" &&
    "$2" == "${fixture_release}" &&
    "$3" == "${fixture_image_id}" &&
    "$4" == OFF &&
    "$5" == true ]] ||
    die 'fixture runtime binding mismatch'
}

remote_assert_caddy_candidate_active() {
  [[ "$1" == "${fixture_release}" &&
    "$2" == "${fixture_run_id}" &&
    "$(< "${fixture_active_state}")" == "${fixture_candidate_sha}" ]] ||
    die 'fixture candidate-active prerequisite mismatch'
}

remote_assert_caddy_drain_marker() {
  [[ "$(< "${fixture_marker_state}")" == true ]] ||
    die 'fixture drain-marker prerequisite mismatch'
}

remote_assert_public_live() {
  printf '%s\n' FORBIDDEN-public-continuation >> "${fixture_mutations}"
}

sudo() {
  local last_argument=''
  local argument

  for argument in "$@"; do
    last_argument="${argument}"
  done

  case "${1:-}" in
    test)
      case "$*" in
        'test -e /etc/caddy/v126-evidence'|\
        "test -e /etc/caddy/v126-evidence/${fixture_release}")
          return 1
          ;;
        'test -e /etc/caddy/v126-drain.enabled')
          [[ "$(< "${fixture_marker_state}")" == true ]]
          ;;
        *)
          return 0
          ;;
      esac
      ;;
    install)
      if [[ "${last_argument}" == /etc/caddy/Caddyfile ]]; then
        case "${fixture_mode}" in
          activate)
            printf '%s\n' "${fixture_candidate_sha}" > "${fixture_active_state}"
            printf '%s\n' candidate-installed >> "${fixture_mutations}"
            ;;
          restore)
            printf '%s\n' "${fixture_original_sha}" > "${fixture_active_state}"
            printf '%s\n' original-restored >> "${fixture_mutations}"
            ;;
        esac
      fi
      ;;
    sha256sum)
      case "$2" in
        /etc/caddy/Caddyfile)
          printf '%s  %s\n' "$(< "${fixture_active_state}")" "$2"
          ;;
        */Caddyfile.original)
          printf '%s  %s\n' "${fixture_original_sha}" "$2"
          ;;
        */Caddyfile.drain)
          printf '%s  %s\n' "${fixture_candidate_sha}" "$2"
          ;;
        */Caddyfile.drain.diff)
          printf '%s  %s\n' "${fixture_diff_sha}" "$2"
          ;;
        *)
          die "unexpected fixture SHA-256 target: $2"
          ;;
      esac
      ;;
    diff)
      return 1
      ;;
    tee)
      command cat >/dev/null
      ;;
    awk)
      case "$2" in
        'NR > 2 && /^-/{count++} END {print count + 0}')
          printf '%s\n' 0
          ;;
        'NR > 2 && /^+/{count++} END {print count + 0}')
          printf '%s\n' 5
          ;;
        'NR > 2 && /^+/{sub(/^+[[:space:]]*/, ""); print}')
          printf '%s\n' \
            '@v126_staging_drain file {' \
            'root /' \
            'try_files /etc/caddy/v126-drain.enabled' \
            '}' \
            'respond @v126_staging_drain "Service temporarily unavailable" 503'
          ;;
        *) die "unexpected fixture awk program: $2" ;;
      esac
      ;;
    systemctl)
      case "$2 $3" in
        'reload caddy')
          printf '%s\n' caddy-reloaded >> "${fixture_mutations}"
          ;;
        'is-active caddy')
          printf '%s\n' active
          ;;
        *) die "unexpected fixture systemctl command: $*" ;;
      esac
      ;;
    rm)
      [[ "$2" == -f && "$3" == -- &&
        "$4" == /etc/caddy/v126-drain.enabled ]] ||
        die "unexpected fixture sudo rm: $*"
      printf '%s\n' false > "${fixture_marker_state}"
      printf '%s\n' drain-marker-removed >> "${fixture_mutations}"
      ;;
    python3|caddy|chown|chmod)
      :
      ;;
    stat)
      printf '%s\n' 700:root:root
      ;;
    *)
      printf 'FORBIDDEN sudo %s\n' "$*" >> "${fixture_mutations}"
      return 98
      ;;
  esac
}

curl() {
  [[ "$*" == '-fsS http://127.0.0.1:2019/config/' ]] ||
    die "unexpected fixture curl: $*"

  local -a snapshots=()
  case "${fixture_mode}" in
    activate) snapshots=("${fixture_tmp}"/v126-caddy-admin.*) ;;
    restore) snapshots=("${fixture_tmp}"/v126-caddy-restored.*) ;;
  esac

  (( ${#snapshots[@]} == 1 )) &&
    [[ -f "${snapshots[0]}" && ! -L "${snapshots[0]}" ]] ||
    die 'Caddy admin snapshot was not created exactly once'

  printf '%s\n' "${snapshots[0]}" > "${fixture_snapshot}"
  printf '%s\n' admin-fetch >> "${fixture_mutations}"
  return 55
}

rm() {
  if (( $# == 3 )) &&
    [[ "$1" == -f && "$2" == -- ]] &&
    [[ "$3" == "${fixture_tmp}"/v126-caddy-admin.* ||
      "$3" == "${fixture_tmp}"/v126-caddy-restored.* ]]; then
    printf '%s\n' "$3" >> "${fixture_cleanup}"
  fi
  command rm "$@"
}

case "${fixture_mode}" in
  activate)
    remote_caddy_activate \
      "${fixture_staging}" "${fixture_run_id}" "${fixture_release}"
    ;;
  restore)
    remote_restore_caddy \
      "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
      "hookah-v126:${fixture_release}" "${fixture_image_id}"
    ;;
esac
SH
}

test_real_caddy_admin_snapshot_cleanup_contract() {
  local mode fixture_root expected_error expected_mutations expected_state
  local snapshot cleaned_path

  for mode in activate restore; do
    fixture_root="${TEST_ROOT}/real-caddy-admin-cleanup-${mode}"
    case "${mode}" in
      activate)
        expected_error='Caddy admin config proof is unavailable after reload'
        expected_mutations=$'candidate-installed\ncaddy-reloaded\nadmin-fetch'
        expected_state='2222222222222222222222222222222222222222222222222222222222222222'
        ;;
      restore)
        expected_error='restored Caddy admin config proof is unavailable'
        expected_mutations=$'original-restored\ncaddy-reloaded\ndrain-marker-removed\nadmin-fetch'
        expected_state='1111111111111111111111111111111111111111111111111111111111111111'
        ;;
    esac

    expect_failure "real Caddy ${mode} rejection cleans its admin snapshot once" \
      "${expected_error}" \
      run_real_caddy_admin_snapshot_failure_fixture "${mode}" "${fixture_root}"

    [[ -f "${fixture_root}/cleanup.log" &&
      "$(wc -l < "${fixture_root}/cleanup.log" | tr -d ' ')" == 1 ]] ||
      fail "real Caddy ${mode} cleanup was not exact-once"

    snapshot="$(< "${fixture_root}/snapshot.path")"
    cleaned_path="$(< "${fixture_root}/cleanup.log")"
    [[ "${snapshot}" == "${cleaned_path}" &&
      ! -e "${snapshot}" && ! -L "${snapshot}" ]] ||
      fail "real Caddy ${mode} retained or mis-targeted its admin snapshot"

    [[ -z "$(find "${fixture_root}/tmp" -mindepth 1 -print -quit)" ]] ||
      fail "real Caddy ${mode} retained temporary admin state"

    [[ "$(< "${fixture_root}/mutations.log")" == "${expected_mutations}" ]] ||
      fail "real Caddy ${mode} crossed its exact failure mutation boundary"

    [[ "$(< "${fixture_root}/active.state")" == "${expected_state}" &&
      "$(< "${fixture_root}/marker.state")" == false ]] ||
      fail "real Caddy ${mode} stopped at the wrong active/marker state"

    ! grep -F $'ARTIFACT\t' "${LAST_OUTPUT}" >/dev/null ||
      fail "real Caddy ${mode} emitted an artifact after admin-proof failure"

    [[ ! -e "${fixture_root}/evidence/activation.proof" &&
      ! -e "${fixture_root}/evidence/activation.proof.sha256" &&
      ! -e "${fixture_root}/run-root/ordinary-caddy-restored.proof" &&
      ! -e "${fixture_root}/run-root/ordinary-caddy-restored.proof.sha256" ]] ||
      fail "real Caddy ${mode} sealed a proof after admin-proof failure"
  done

  pass 'real Caddy admin snapshots clean exact-once before proof, artifact, or public continuation'
}

run_partial_caddy_recovery_fixture() {
  local active_kind="$1"
  local mutation_log="$2"
  bash -s -- "${CUTOVER_SCRIPT}" "${active_kind}" "${mutation_log}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_active="$2"
fixture_log="$3"
fixture_marker=false
fixture_original='1111111111111111111111111111111111111111111111111111111111111111'
fixture_candidate='2222222222222222222222222222222222222222222222222222222222222222'
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_original}"
V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256=NONE
V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256=NONE
V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256=NONE
V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256=NONE
remote_caddy_evidence_root() { printf '%s\n' /fixture/evidence; }
remote_sudo_require_root_file() { :; }
remote_assert_caddy_candidate_derived() { :; }
remote_sudo_read_sha256_checksum() {
  case "$1" in
    *original*) printf '%s\n' "${fixture_original}" ;;
    *drain*) printf '%s\n' "${fixture_candidate}" ;;
    *) return 97 ;;
  esac
}
remote_assert_caddy_drain_marker() {
  [[ "${fixture_marker}" == true ]] || die 'fixture drain marker missing'
  printf '%s\n' marker-proved >> "${fixture_log}"
}
remote_assert_public_drain() { printf '%s\n' public-drain-proved >> "${fixture_log}"; }
sudo() {
  case "$*" in
    'stat -c %a:%U:%G /fixture/evidence') printf '%s\n' 700:root:root ;;
    'sha256sum /fixture/evidence/Caddyfile.original') printf '%s  %s\n' "${fixture_original}" "$2" ;;
    'sha256sum /fixture/evidence/Caddyfile.drain') printf '%s  %s\n' "${fixture_candidate}" "$2" ;;
    'sha256sum /etc/caddy/Caddyfile')
      case "${fixture_active}" in
        original) printf '%s  %s\n' "${fixture_original}" /etc/caddy/Caddyfile ;;
        candidate) printf '%s  %s\n' "${fixture_candidate}" /etc/caddy/Caddyfile ;;
        unknown) printf '%064d  %s\n' 9 /etc/caddy/Caddyfile ;;
      esac
      ;;
    'caddy validate --config /fixture/evidence/Caddyfile.original --adapter caddyfile') : ;;
    'caddy validate --config /fixture/evidence/Caddyfile.drain --adapter caddyfile') : ;;
    'install -o root -g root -m 0644 /fixture/evidence/Caddyfile.drain /etc/caddy/Caddyfile')
      fixture_active=candidate
      printf '%s\n' candidate-installed >> "${fixture_log}"
      ;;
    'systemctl reload caddy') printf '%s\n' caddy-reloaded >> "${fixture_log}" ;;
    'systemctl is-active caddy') printf '%s\n' active ;;
    'test ! -L /etc/caddy/v126-drain.enabled') : ;;
    'test -e /etc/caddy/v126-drain.enabled') [[ "${fixture_marker}" == true ]] ;;
    'install -o root -g root -m 0600 /dev/null /etc/caddy/v126-drain.enabled')
      fixture_marker=true
      printf '%s\n' marker-installed >> "${fixture_log}"
      ;;
    *) printf 'unexpected sudo Caddy fixture: %s\n' "$*" >&2; return 98 ;;
  esac
}
remote_recovery_ensure_pre_v126_drain fixture-release fixture-run
SH
}

run_unknown_active_caddy_fixture() {
  local mode="$1"
  local mutation_log="$2"
  bash -s -- "${CUTOVER_SCRIPT}" "${mode}" "${mutation_log}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_mode="$2"
fixture_log="$3"
fixture_original_sha=1111111111111111111111111111111111111111111111111111111111111111
fixture_candidate_sha=2222222222222222222222222222222222222222222222222222222222222222
fixture_unknown_sha=9999999999999999999999999999999999999999999999999999999999999999
V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256="${fixture_original_sha}"
V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256="${fixture_candidate_sha}"
V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256=3333333333333333333333333333333333333333333333333333333333333333
V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256=4444444444444444444444444444444444444444444444444444444444444444
remote_caddy_evidence_root() { printf '%s\n' /fixture/evidence; }
remote_verify_caddy_receipt_evidence() { :; }
remote_sudo_require_root_file() { :; }
remote_sudo_read_sha256_checksum() {
  case "$1" in
    *original*) printf '%s\n' "${fixture_original_sha}" ;;
    *drain*) printf '%s\n' "${fixture_candidate_sha}" ;;
    *) return 97 ;;
  esac
}
sudo() {
  case "$*" in
    'stat -c %a:%U:%G /fixture/evidence') printf '%s\n' 700:root:root ;;
    'sha256sum /fixture/evidence/Caddyfile.original') printf '%s  %s\n' "${fixture_original_sha}" "$2" ;;
    'sha256sum /fixture/evidence/Caddyfile.drain') printf '%s  %s\n' "${fixture_candidate_sha}" "$2" ;;
    'sha256sum /etc/caddy/Caddyfile') printf '%s  %s\n' "${fixture_unknown_sha}" "$2" ;;
    *) printf 'MUTATION sudo %s\n' "$*" >> "${fixture_log}"; return 98 ;;
  esac
}
case "${fixture_mode}" in
  ensure-candidate) remote_recovery_ensure_candidate_drain fixture-release fixture-run ;;
  restore-original) remote_recovery_restore_original_caddy fixture-release fixture-run ;;
  *) die 'unknown active-Caddy fixture mode' ;;
esac
SH
}

test_partial_caddy_activation_recovery() {
  local mutation_log="${TEST_ROOT}/partial-caddy-recovery.log"
  expect_success 'pre-V126 recovery seals drain after interrupted Caddy activation' \
    run_partial_caddy_recovery_fixture original "${mutation_log}"
  assert_literals_in_order "${mutation_log}" 'partial Caddy activation recovery' \
    candidate-installed caddy-reloaded marker-installed marker-proved public-drain-proved

  : > "${mutation_log}"
  expect_failure 'pre-V126 recovery rejects an unrecognized active Caddyfile before mutation' \
    'unrecognized active Caddyfile' run_partial_caddy_recovery_fixture unknown "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] || fail 'unrecognized Caddyfile reached recovery mutation'

  : > "${mutation_log}"
  expect_failure 'post-V126 candidate-drain recovery rejects unknown active Caddy bytes' \
    'refuses an unrecognized active Caddyfile' \
    run_unknown_active_caddy_fixture ensure-candidate "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] ||
    fail 'post-V126 unknown active Caddy bytes reached an install/reload/marker mutation'

  : > "${mutation_log}"
  expect_failure 'pre-V126 original restoration rejects unknown active Caddy bytes' \
    'refuses to overwrite an active Caddyfile other than the sealed candidate' \
    run_unknown_active_caddy_fixture restore-original "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] ||
    fail 'pre-V126 unknown active Caddy bytes reached overwrite/reload/marker removal'
  pass 'partial Caddy activation drains safely and both recovery paths reject unknown active bytes'
}

prepare_caddy_receipt_fixture() {
  local evidence_root="$1"
  local run_id="$2"
  local version="$3"
  mkdir -m 0700 -p "${evidence_root}"
  printf '%s\n' \
    "# fixture-${version}" \
    'staging.hookahtootah.club {' \
    '    reverse_proxy 127.0.0.1:8080' \
    '}' > "${evidence_root}/Caddyfile.original"
  printf '%s\n' \
    "# fixture-${version}" \
    'staging.hookahtootah.club {' \
    '    @v126_staging_drain file {' \
    '        root /' \
    '        try_files /etc/caddy/v126-drain.enabled' \
    '    }' \
    '    respond @v126_staging_drain "Service temporarily unavailable" 503' \
    '    reverse_proxy 127.0.0.1:8080' \
    '}' > "${evidence_root}/Caddyfile.drain"
  local diff_status=0
  set +e
  diff -u --label Caddyfile.original --label Caddyfile.drain \
    "${evidence_root}/Caddyfile.original" "${evidence_root}/Caddyfile.drain" > \
    "${evidence_root}/Caddyfile.drain.diff"
  diff_status=$?
  set -e
  [[ "${diff_status}" == 1 ]] || fail 'fixture Caddy diff was not a bounded change'
  local original_sha candidate_sha diff_sha
  original_sha="$(sha256_file "${evidence_root}/Caddyfile.original")"
  candidate_sha="$(sha256_file "${evidence_root}/Caddyfile.drain")"
  diff_sha="$(sha256_file "${evidence_root}/Caddyfile.drain.diff")"
  printf '%s\n' \
    "run_id=${run_id}" \
    "release_sha=${RELEASE_SHA}" \
    "original_sha256=${original_sha}" \
    "candidate_sha256=${candidate_sha}" \
    "diff_sha256=${diff_sha}" \
    'active_admin_config_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    'marker_present=false' \
    'activation_reload=PASS' > "${evidence_root}/activation.proof"
  printf '%s\n' "${original_sha}" > "${evidence_root}/Caddyfile.original.sha256"
  printf '%s\n' "${candidate_sha}" > "${evidence_root}/Caddyfile.drain.sha256"
  printf '%s\n' "$(sha256_file "${evidence_root}/activation.proof")" > \
    "${evidence_root}/activation.proof.sha256"
  chmod 0600 "${evidence_root}"/*
}

run_caddy_receipt_binding_fixture() {
  local evidence_root="$1"
  local run_id="$2"
  local mutation_log="$3"
  local expected_original="$4"
  local expected_candidate="$5"
  local expected_diff="$6"
  local expected_activation="$7"
  bash -s -- "${CUTOVER_SCRIPT}" "${evidence_root}" "${run_id}" "${RELEASE_SHA}" \
    "${mutation_log}" "${expected_original}" "${expected_candidate}" "${expected_diff}" \
    "${expected_activation}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_evidence_root="$2"
fixture_run_id="$3"
fixture_release="$4"
fixture_log="$5"
V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256="$6"
V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256="$7"
V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256="$8"
V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256="$9"
remote_caddy_evidence_root() { printf '%s\n' "${fixture_evidence_root}"; }
remote_sudo_require_root_file() {
  [[ -f "$1" && ! -L "$1" ]] || die "fixture root file is unavailable: $1"
  local mode
  mode="$(python3 - "$1" <<'PY'
import os
import stat
import sys
print(f"{stat.S_IMODE(os.stat(sys.argv[1]).st_mode):o}")
PY
)"
  [[ "${mode}" == "$2" ]] || die "fixture root file mode mismatch: $1"
}
remote_sudo_read_sha256_checksum() {
  remote_sudo_require_root_file "$1" 600
  local value
  value="$(tr -d '\r\n' < "$1")"
  [[ "${value}" =~ ^[0-9a-f]{64}$ && "$(wc -l < "$1" | tr -d ' ')" == 1 ]] ||
    die "fixture checksum mismatch: $1"
  printf '%s\n' "${value}"
}
sudo() {
  case "${1:-}" in
    stat) printf '%s\n' 700:root:root ;;
    sha256sum) shift; command sha256sum "$@" ;;
    python3) shift; command python3 "$@" ;;
    *) printf 'MUTATION sudo %s\n' "$*" >> "${fixture_log}"; return 98 ;;
  esac
}
remote_verify_caddy_receipt_evidence "${fixture_release}" "${fixture_run_id}"
SH
}

test_caddy_receipt_binding_contract() {
  local root="${TEST_ROOT}/caddy-receipt-binding"
  local evidence_root="${root}/evidence"
  local run_id='caddy-receipt-run'
  local mutation_log="${root}/mutation.log"
  local original_sha candidate_sha diff_sha activation_sha artifact label
  mkdir -m 0700 -p "${root}"
  prepare_caddy_receipt_fixture "${evidence_root}" "${run_id}" v1
  original_sha="$(sha256_file "${evidence_root}/Caddyfile.original")"
  candidate_sha="$(sha256_file "${evidence_root}/Caddyfile.drain")"
  diff_sha="$(sha256_file "${evidence_root}/Caddyfile.drain.diff")"
  activation_sha="$(sha256_file "${evidence_root}/activation.proof")"
  expect_success 'exact Caddy evidence matches all four immutable receipt hashes' \
    run_caddy_receipt_binding_fixture "${evidence_root}" "${run_id}" "${mutation_log}" \
    "${original_sha}" "${candidate_sha}" "${diff_sha}" "${activation_sha}"
  [[ ! -s "${mutation_log}" ]] || fail 'exact Caddy evidence reached a mutation command'

  while IFS='|' read -r label artifact; do
    prepare_caddy_receipt_fixture "${evidence_root}" "${run_id}" v1
    printf '%s\n' "rewritten-${label}" >> "${evidence_root}/${artifact}"
    case "${artifact}" in
      Caddyfile.original|Caddyfile.drain)
        printf '%s\n' "$(sha256_file "${evidence_root}/${artifact}")" > \
          "${evidence_root}/${artifact}.sha256"
        ;;
      activation.proof)
        printf '%s\n' "$(sha256_file "${evidence_root}/${artifact}")" > \
          "${evidence_root}/${artifact}.sha256"
        ;;
    esac
    : > "${mutation_log}"
    expect_failure "rechecksummed Caddy ${label} substitution rejects" \
      'differs from the immutable stage receipt' \
      run_caddy_receipt_binding_fixture "${evidence_root}" "${run_id}" "${mutation_log}" \
      "${original_sha}" "${candidate_sha}" "${diff_sha}" "${activation_sha}"
    [[ ! -s "${mutation_log}" ]] || fail "Caddy ${label} substitution reached mutation"
  done <<'EOF'
original|Caddyfile.original
candidate|Caddyfile.drain
diff|Caddyfile.drain.diff
activation-proof|activation.proof
EOF

  prepare_caddy_receipt_fixture "${evidence_root}" "${run_id}" v2
  : > "${mutation_log}"
  expect_failure 'consistent Caddy evidence, proof, and sidecar rewrite rejects' \
    'Caddy original differs from the immutable stage receipt' \
    run_caddy_receipt_binding_fixture "${evidence_root}" "${run_id}" "${mutation_log}" \
    "${original_sha}" "${candidate_sha}" "${diff_sha}" "${activation_sha}"
  [[ ! -s "${mutation_log}" ]] || fail 'consistent Caddy receipt rewrite reached mutation'
  pass 'Caddy recovery evidence remains bound to all four immutable stage receipt hashes'
}

run_caddy_source_swap_fixture() {
  local fixture_root="$1"
  local mutation_log="$2"
  bash -s -- "${CUTOVER_SCRIPT}" "${fixture_root}" "${mutation_log}" \
    "${RELEASE_SHA}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_root="$2"
fixture_log="$3"
fixture_release="$4"
fixture_staging="${fixture_root}/staging"
fixture_run_root="${fixture_root}/run-root"
fixture_evidence_root="${fixture_root}/evidence"
fixture_active="${fixture_root}/active-Caddyfile"
fixture_swapped="${fixture_root}/swapped-Caddyfile"
mkdir -m 0700 -p "${fixture_staging}" "${fixture_run_root}"
printf '%s\n' \
  'staging.hookahtootah.club {' \
  '    reverse_proxy 127.0.0.1:8080' \
  '}' > "${fixture_active}"
printf '%s\n' \
  '# swapped after outer Caddy hash check' \
  'staging.hookahtootah.club {' \
  '    reverse_proxy 127.0.0.1:8080' \
  '}' > "${fixture_swapped}"
fixture_baseline_sha="$(remote_hash_file "${fixture_active}")"
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_baseline_sha}"
remote_require_run_root() { printf '%s\n' "${fixture_run_root}"; }
remote_verify_baseline_authority() { :; }
remote_caddy_evidence_root() { printf '%s\n' "${fixture_evidence_root}"; }
remote_sudo_require_root_file() { :; }
sudo() {
  case "$*" in
    'test ! -L /etc/caddy/v126-evidence'|\
    "test ! -L /etc/caddy/v126-evidence/${fixture_release}"|\
    "test ! -L ${fixture_evidence_root}"|\
    'test ! -L /etc/caddy/v126-drain.enabled') return 0 ;;
    "test ! -e ${fixture_evidence_root}") [[ ! -e "${fixture_evidence_root}" ]] ;;
    'test ! -e /etc/caddy/v126-drain.enabled') return 0 ;;
    'test -e /etc/caddy/v126-evidence'|\
    "test -e /etc/caddy/v126-evidence/${fixture_release}"|\
    "test -e ${fixture_evidence_root}"|\
    'test -e /etc/caddy/v126-drain.enabled') return 1 ;;
    'install -d -o root -g root -m 0700 /etc/caddy/v126-evidence'|\
    "install -d -o root -g root -m 0700 /etc/caddy/v126-evidence/${fixture_release}")
      return 0
      ;;
    "install -d -o root -g root -m 0700 ${fixture_evidence_root}")
      mkdir -m 0700 -p "${fixture_evidence_root}"
      ;;
    "sha256sum /etc/caddy/Caddyfile") command sha256sum "${fixture_active}" ;;
    "install -o root -g root -m 0600 /etc/caddy/Caddyfile ${fixture_evidence_root}/Caddyfile.original")
      command cp "${fixture_swapped}" "${fixture_evidence_root}/Caddyfile.original"
      chmod 0600 "${fixture_evidence_root}/Caddyfile.original"
      printf '%s\n' snapshot-source-swapped >> "${fixture_log}"
      ;;
    python3\ *) shift; command python3 "$@" ;;
    chown\ root:root\ *) return 0 ;;
    chmod\ 0600\ *) shift 2; command chmod 0600 "$@" ;;
    "install -o root -g root -m 0600 /dev/null ${fixture_evidence_root}/Caddyfile.drain.diff")
      command install -m 0600 /dev/null "${fixture_evidence_root}/Caddyfile.drain.diff"
      ;;
    diff\ *) shift; command diff "$@" ;;
    tee\ *) shift; command tee "$@" ;;
    awk\ *)
      shift
      if [[ "$1" == *'/^-/'* ]]; then
        printf '%s\n' 0
      elif [[ "$1" == *'/^+/'* && "$1" == *'count++'* ]]; then
        printf '%s\n' 5
      elif [[ "$1" == *'/^+/'* ]]; then
        printf '%s\n' \
          '@v126_staging_drain file {' \
          'root /' \
          'try_files /etc/caddy/v126-drain.enabled' \
          '}' \
          'respond @v126_staging_drain "Service temporarily unavailable" 503'
      else
        command awk "$@"
      fi
      ;;
    caddy\ validate\ *) return 0 ;;
    sha256sum\ *) shift; command sha256sum "$@" ;;
    "install -o root -g root -m 0644 ${fixture_evidence_root}/Caddyfile.drain /etc/caddy/Caddyfile")
      printf '%s\n' ACTIVE-INSTALL >> "${fixture_log}"
      ;;
    'systemctl reload caddy') printf '%s\n' RELOAD >> "${fixture_log}" ;;
    *) printf 'UNEXPECTED sudo %s\n' "$*" >> "${fixture_log}"; return 98 ;;
  esac
}
remote_caddy_activate "${fixture_staging}" fixture-run "${fixture_release}"
SH
}

test_caddy_source_same_read_binding() {
  local fixture_root="${TEST_ROOT}/caddy-source-same-read"
  local mutation_log="${fixture_root}/mutation.log"
  expect_failure 'Caddy source swap after outer validation rejects the sealed snapshot' \
    'sealed Caddy original differs from the immutable baseline receipt' \
    run_caddy_source_swap_fixture "${fixture_root}" "${mutation_log}"
  grep -Fx snapshot-source-swapped "${mutation_log}" >/dev/null ||
    fail 'Caddy same-read fixture did not swap the snapshotted source'
  ! grep -E 'ACTIVE-INSTALL|RELOAD' "${mutation_log}" >/dev/null ||
    fail 'Caddy same-read source swap reached active install or reload'
  pass 'Caddy activation binds the snapshotted source bytes before install/reload'
}

make_saved_image_archive_fixture() {
  local target="$1"
  local mode="$2"
  if [[ "${mode}" == malformed ]]; then
    printf '%s\n' 'not-a-docker-save-tar' > "${target}"
    chmod 0600 "${target}"
    return 0
  fi
  python3 - "${target}" "${mode}" "hookah-v126:${RELEASE_SHA}" \
    "${V126_IMAGE_ID#sha256:}" <<'PY'
import io
import json
import tarfile
import sys

target, mode, expected_tag, expected_digest = sys.argv[1:]
config_name = expected_digest + ".json"
config_bytes = b"{}"
layer_name = "fixture-layer/layer.tar"
directories = ["fixture-layer"]
repo_tag = expected_tag
if mode == "valid-blob":
    config_name = "blobs/sha256/" + expected_digest
    layer_name = "blobs/sha256/" + "2" * 64
    directories = ["blobs", "blobs/sha256"]
elif mode == "wrong-tag":
    repo_tag = "hookah-v126:" + "f" * 40
elif mode == "wrong-config-name":
    config_name = "e" * 64 + ".json"
elif mode == "wrong-config-bytes":
    config_bytes = b'{"unexpected":true}'
elif mode not in {
    "valid-legacy",
    "duplicate-member",
    "missing-member",
    "multiple-images",
    "unsafe-member",
    "unsafe-dot-alias",
    "unsafe-absolute",
    "symlink-member",
    "hardlink-member",
    "device-member",
}:
    raise SystemExit("unknown saved-image fixture mode")
image = {
    "Config": config_name,
    "RepoTags": [repo_tag],
    "Layers": [layer_name],
}
manifest_images = [image, dict(image)] if mode == "multiple-images" else [image]
manifest = json.dumps(manifest_images, separators=(",", ":")).encode()
members = [("manifest.json", manifest), (config_name, config_bytes)]
if mode != "missing-member":
    members.append((layer_name, b"fixture-layer"))
if mode == "duplicate-member":
    members.append(("manifest.json", manifest))
with tarfile.open(target, "w") as archive:
    for name in directories:
        member = tarfile.TarInfo(name)
        member.type = tarfile.DIRTYPE
        member.mode = 0o700
        archive.addfile(member)
    for name, payload in members:
        member = tarfile.TarInfo(name)
        member.size = len(payload)
        member.mode = 0o600
        archive.addfile(member, io.BytesIO(payload))
    if mode == "unsafe-member":
        member = tarfile.TarInfo("../outside")
        member.size = 6
        member.mode = 0o600
        archive.addfile(member, io.BytesIO(b"escape"))
    elif mode == "unsafe-dot-alias":
        member = tarfile.TarInfo("./manifest.json")
        member.size = len(manifest)
        member.mode = 0o600
        archive.addfile(member, io.BytesIO(manifest))
    elif mode == "unsafe-absolute":
        member = tarfile.TarInfo("/absolute-member")
        member.size = 8
        member.mode = 0o600
        archive.addfile(member, io.BytesIO(b"absolute"))
    elif mode == "symlink-member":
        member = tarfile.TarInfo("unsafe-link")
        member.type = tarfile.SYMTYPE
        member.linkname = "manifest.json"
        member.mode = 0o600
        archive.addfile(member)
    elif mode == "hardlink-member":
        member = tarfile.TarInfo("unsafe-hardlink")
        member.type = tarfile.LNKTYPE
        member.linkname = "manifest.json"
        member.mode = 0o600
        archive.addfile(member)
    elif mode == "device-member":
        member = tarfile.TarInfo("unsafe-device")
        member.type = tarfile.CHRTYPE
        member.devmajor = 1
        member.devminor = 3
        member.mode = 0o600
        archive.addfile(member)
PY
  chmod 0600 "${target}"
}

run_saved_image_archive_stage_fixture() {
  local archive_fixture="$1"
  local action_log="$2"
  local fixture_root="$3"
  local upload_capture="$4"
  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${archive_fixture}" "${action_log}" \
    "${fixture_root}" "${RELEASE_SHA}" "${V126_IMAGE_ID}" "${upload_capture}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_archive="$2"
fixture_action_log="$3"
STATE_DIR="$4"
RUN_ID=fixture-saved-image
RELEASE_SHA="$5"
REMOTE=fixture-remote
STAGING_PATH="${STATE_DIR}/remote-staging"
V126_IMAGE_TAG="hookah-v126:${RELEASE_SHA}"
V126_IMAGE_ID="$6"
fixture_upload_capture="$7"
fixture_expected_sha="$(hash_file "${fixture_archive}")"
mkdir -m 0700 -p "${STATE_DIR}/tmp" "${STAGING_PATH}"
require_cmd() { :; }
docker() {
  case "$*" in
    "image inspect --format {{.Id}} ${V126_IMAGE_TAG}") printf '%s\n' "${V126_IMAGE_ID}" ;;
    "save --output ${STATE_DIR}/tmp/v126-image.tar ${V126_IMAGE_TAG}")
      cp "${fixture_archive}" "${STATE_DIR}/tmp/v126-image.tar"
      ;;
    *) die "unexpected saved-image Docker fixture call: $*" ;;
  esac
}
run_tracked_command() {
  [[ "$1" == remote-rsync && "$2" == rsync ]] || die 'unexpected saved-image tracked command'
  [[ "$3" == --archive && "$4" == --copy-links && "$5" == --chmod=Fu=rw,Fgo= &&
    "$6" == /dev/fd/9 && "$7" == "${REMOTE}:"* ]] ||
    die 'saved-image rsync flags or fixed anonymous FD differ from the portable contract'
  local fixture_rsync
  fixture_rsync="$(command -v rsync)" || die 'real rsync is unavailable for compatibility fixture'
  if [[ "$(uname -s)" == Darwin && "${fixture_rsync}" != /usr/bin/rsync ]]; then
    die 'macOS compatibility fixture did not resolve the system rsync'
  fi
  "${fixture_rsync}" "$3" "$4" "$5" "$6" "${fixture_upload_capture}"
  python3 - "${fixture_upload_capture}" <<'PY'
import os
import stat
import sys

if stat.S_IMODE(os.stat(sys.argv[1]).st_mode) != 0o600:
    raise SystemExit("real rsync did not enforce the exact restricted upload mode")
PY
  printf '%s\n' rsync >> "${fixture_action_log}"
}
run_remote() {
  local action="$1"
  printf 'remote %s\n' "${action}" >> "${fixture_action_log}"
  case "${action}" in
    image-prepare)
      printf '%s\n' 'HT12P_REINTRODUCED_ARCHIVE_PATH_MUST_NOT_UPLOAD' > \
        "${STATE_DIR}/tmp/v126-image.tar"
      chmod 0600 "${STATE_DIR}/tmp/v126-image.tar"
      printf 'ARTIFACT\tv126-image-transfer-ready\t%s\n' \
        aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
      ;;
    image-load)
      [[ "$7" == "${fixture_expected_sha}" ]] ||
        die 'image-load checksum did not bind the anonymous local snapshot'
      [[ "$(hash_file "${fixture_upload_capture}")" == "${fixture_expected_sha}" ]] ||
        die 'rsync did not consume the exact verified anonymous snapshot'
      printf 'ARTIFACT\tv126-image-archive\t%s\n' \
        bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
      printf 'ARTIFACT\tv126-image-transferred\t%s\n' \
        cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
      ;;
    *) die 'unexpected saved-image remote action' ;;
  esac
}
stage_v126_image_transferred_and_verified
SH
}

test_saved_image_archive_binding() {
  local mode archive action_log fixture_root upload_capture pattern
  for mode in valid-legacy valid-blob; do
    archive="${TEST_ROOT}/saved-image-${mode}.tar"
    action_log="${TEST_ROOT}/saved-image-${mode}.actions"
    fixture_root="${TEST_ROOT}/saved-image-${mode}-state"
    upload_capture="${TEST_ROOT}/saved-image-${mode}.uploaded"
    make_saved_image_archive_fixture "${archive}" "${mode}"
    expect_success "${mode} Docker-save archive reaches bounded transfer actions" \
      run_saved_image_archive_stage_fixture "${archive}" "${action_log}" \
      "${fixture_root}" "${upload_capture}"
    [[ "$(cat "${action_log}")" == $'remote image-prepare\nrsync\nremote image-load' ]] ||
      fail "${mode} saved-image archive action order mismatch"
    cmp -s "${archive}" "${upload_capture}" ||
      fail "${mode} anonymous-FD upload differs from the verified Docker-save archive"
    grep -F 'HT12P_REINTRODUCED_ARCHIVE_PATH_MUST_NOT_UPLOAD' \
      "${fixture_root}/tmp/v126-image.tar" >/dev/null ||
      fail "${mode} path-swap control was not installed after image-prepare"
    ! grep -F 'HT12P_REINTRODUCED_ARCHIVE_PATH_MUST_NOT_UPLOAD' \
      "${upload_capture}" >/dev/null ||
      fail "${mode} rsync followed the reintroduced archive path instead of the verified FD"
  done
  while IFS='|' read -r mode pattern; do
    archive="${TEST_ROOT}/saved-image-${mode}.tar"
    action_log="${TEST_ROOT}/saved-image-${mode}.actions"
    fixture_root="${TEST_ROOT}/saved-image-${mode}-state"
    upload_capture="${TEST_ROOT}/saved-image-${mode}.uploaded"
    make_saved_image_archive_fixture "${archive}" "${mode}"
    expect_failure "${mode} Docker-save archive rejects before remote mutation" "${pattern}" \
      run_saved_image_archive_stage_fixture "${archive}" "${action_log}" \
      "${fixture_root}" "${upload_capture}"
    [[ ! -s "${action_log}" ]] || fail "${mode} saved-image archive reached remote or rsync"
    [[ ! -e "${upload_capture}" ]] || fail "${mode} saved-image archive reached the upload FD"
  done <<'EOF'
wrong-tag|tag association mismatch
wrong-config-name|config name differs from expected image ID
wrong-config-bytes|config digest differs from expected image ID
duplicate-member|duplicate members
missing-member|layer is missing or non-regular
multiple-images|exactly one image manifest
malformed|snapshot structure or identity mismatch|not a gzip file|truncated header|ReadError
unsafe-member|unsafe member path
unsafe-dot-alias|unsafe member path
unsafe-absolute|unsafe member path
symlink-member|non-regular member
hardlink-member|non-regular member
device-member|non-regular member
EOF
  pass 'saved image anonymous FD binds bytes, tag, config digest, unique members, and layers before transfer'
}

run_remote_image_fd_fixture() {
  local archive_fixture="$1"
  local fixture_root="$2"
  local action_log="$3"
  local load_capture="$4"
  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${archive_fixture}" "${fixture_root}" \
    "${action_log}" "${load_capture}" "${RELEASE_SHA}" "${V126_IMAGE_ID}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_archive="$2"
fixture_root="$3"
fixture_action_log="$4"
fixture_load_capture="$5"
fixture_release_sha="$6"
fixture_image_id="$7"
fixture_staging="${fixture_root}/staging"
fixture_run_id=remote-image-fd
fixture_run_root="${fixture_staging}/.v126-runs/${fixture_run_id}"
fixture_image_tag="hookah-v126:${fixture_release_sha}"
mkdir -m 0700 -p "${fixture_run_root}"
cp "${fixture_archive}" "${fixture_run_root}/v126-image.tar.partial"
chmod 0600 "${fixture_run_root}/v126-image.tar.partial"
fixture_archive_sha="$(hash_file "${fixture_archive}")"
V126_INTERNAL_REMOTE_V126_IMAGE_ID="${fixture_image_id}"

stat() {
  if [[ "$1" == -c && "$2" == %a ]]; then
    python3 - "$3" <<'PY'
import os
import stat
import sys

print(f"{stat.S_IMODE(os.stat(sys.argv[1]).st_mode):o}")
PY
  else
    command stat "$@"
  fi
}
remote_hash_file() { hash_file "$1"; }
remote_initialize_compose() { :; }
remote_require_run_root() { printf '%s\n' "${fixture_run_root}"; }
remote_verify_proof() { :; }
remote_verify_maintenance_env_binding() { :; }
remote_assert_public_drain() { :; }
remote_assert_zero_writer() { :; }
remote_compose() {
  printf '%s\n' compose-reached >> "${fixture_action_log}"
  return 98
}
remote_capture_compose_ids() {
  printf '%s\n' inventory-reached >> "${fixture_action_log}"
  return 98
}
remote_write_proof() {
  printf '%s\n' proof-reached >> "${fixture_action_log}"
  return 98
}
remote_emit_artifact() {
  printf '%s\n' artifact-reached >> "${fixture_action_log}"
  return 98
}
docker() {
  if [[ "$1" == load ]]; then
    chmod 0600 "${fixture_run_root}/v126-image.tar"
    printf '%s\n' 'HT12P_REMOTE_SEALED_PATH_SWAP_MUST_NOT_LOAD' > \
      "${fixture_run_root}/v126-image.tar"
    chmod 0400 "${fixture_run_root}/v126-image.tar"
    printf '%s\n' path-swapped >> "${fixture_action_log}"
    cat > "${fixture_load_capture}"
    chmod 0600 "${fixture_load_capture}"
    printf '%s\n' docker-load >> "${fixture_action_log}"
    printf '%s\n' 'Loaded fixture image'
    return 0
  fi
  if [[ "$1" == image && "$2" == inspect ]]; then
    printf '%s\n' image-inspect >> "${fixture_action_log}"
    printf '%s\n' "${fixture_image_id}"
    return 0
  fi
  printf 'unexpected docker call: %s\n' "$*" >> "${fixture_action_log}"
  return 97
}

remote_image_load "${fixture_staging}" "${fixture_run_id}" "${fixture_release_sha}" \
  "${fixture_image_tag}" "${fixture_image_id}" "${fixture_archive_sha}"
SH
}

test_remote_image_exact_fd_binding() {
  local fixture_root="${TEST_ROOT}/remote-image-exact-fd"
  local valid_archive="${TEST_ROOT}/remote-image-valid.tar"
  local malformed_archive="${TEST_ROOT}/remote-image-malformed.tar"
  local action_log="${fixture_root}/actions.log"
  local load_capture="${fixture_root}/docker-load.input"
  mkdir -m 0700 -p "${fixture_root}"
  make_saved_image_archive_fixture "${valid_archive}" valid-legacy
  expect_failure 'remote sealed-path swap cannot alter exact bytes fed to docker load' \
    'sealed remote V126 archive changed during exact-FD image load' \
    run_remote_image_fd_fixture "${valid_archive}" "${fixture_root}" \
    "${action_log}" "${load_capture}"
  cmp -s "${valid_archive}" "${load_capture}" ||
    fail 'docker load did not consume the exact verified anonymous remote snapshot'
  [[ "$(cat "${action_log}")" == $'path-swapped\ndocker-load\nimage-inspect' ]] ||
    fail 'remote archive path-swap fixture crossed an unexpected mutation boundary'
  grep -F 'HT12P_REMOTE_SEALED_PATH_SWAP_MUST_NOT_LOAD' \
    "${fixture_root}/staging/.v126-runs/remote-image-fd/v126-image.tar" >/dev/null ||
    fail 'remote sealed-path swap control did not alter the stored pathname'
  ! grep -F 'HT12P_REMOTE_SEALED_PATH_SWAP_MUST_NOT_LOAD' "${load_capture}" >/dev/null ||
    fail 'docker load followed the swapped sealed path instead of the verified FD'

  fixture_root="${TEST_ROOT}/remote-image-malformed"
  action_log="${fixture_root}/actions.log"
  load_capture="${fixture_root}/docker-load.input"
  mkdir -m 0700 -p "${fixture_root}"
  make_saved_image_archive_fixture "${malformed_archive}" malformed
  expect_failure 'remote loader independently rejects malformed transferred archive' \
    'remote V126 image snapshot structure or identity mismatch' \
    run_remote_image_fd_fixture "${malformed_archive}" "${fixture_root}" \
    "${action_log}" "${load_capture}"
  [[ ! -s "${action_log}" && ! -e "${load_capture}" ]] ||
    fail 'malformed remote archive reached docker load or later mutation'
  pass 'remote image loader reparses and loads the exact anonymous FD, independent of stored-path swaps'
}

test_image_separation_and_mismatch() {
  local transfer="${TEST_ROOT}/stage-image-transfer.sh"
  local startup="${TEST_ROOT}/stage-image-start.sh"
  local remote_load="${TEST_ROOT}/function-image-load.sh"
  local remote_start="${TEST_ROOT}/function-image-start.sh"
  extract_stage_block "${CUTOVER_SCRIPT}" V126_IMAGE_TRANSFERRED_AND_VERIFIED "${transfer}"
  extract_stage_block "${CUTOVER_SCRIPT}" V126_BACKEND_STARTED "${startup}"
  extract_function_source "${CUTOVER_SCRIPT}" remote_image_load "${remote_load}"
  extract_function_source "${CUTOVER_SCRIPT}" remote_start_v126 "${remote_start}"
  assert_literals_in_order "${transfer}" 'image transfer stage' \
    'docker image inspect' 'docker save' 'run_remote image-prepare' 'rsync ' 'run_remote image-load'
  grep -F 'local archive_fd=9' "${transfer}" >/dev/null ||
    fail 'image transfer does not use the Bash-3.2-compatible fixed archive FD'
  grep -F 'run_tracked_command remote-rsync rsync --archive --copy-links --chmod=Fu=rw,Fgo=' \
    "${transfer}" >/dev/null ||
    fail 'image transfer lacks the portable exact rsync flag contract'
  grep -F '"/dev/fd/${archive_fd}"' "${transfer}" >/dev/null ||
    fail 'image transfer does not upload the verified anonymous FD'
  ! grep -E -- '--protect-args|--chmod=F600|exec \{archive_fd\}' "${transfer}" >/dev/null ||
    fail 'image transfer reintroduced a macOS Bash/rsync-incompatible construct'
  ! grep -F 'start-v126' "${transfer}" >/dev/null || fail 'image transfer stage can start V126'
  grep -F 'run_remote start-v126' "${startup}" >/dev/null || fail 'startup stage lacks exact start action'
  ! grep -E 'docker (save|load)|image-(prepare|load)' "${startup}" >/dev/null ||
    fail 'startup stage performs image transfer work'
  grep -F 'docker load' "${remote_load}" >/dev/null || fail 'remote image load is absent'
  grep -F 'local archive_fd=9' "${remote_load}" >/dev/null ||
    fail 'remote image load does not use the Bash-3.2-compatible fixed archive FD'
  grep -F 'docker load <&"${archive_fd}"' "${remote_load}" >/dev/null ||
    fail 'remote Docker load does not consume the verified anonymous FD'
  grep -F 'backend started during the image-transfer stage' "${remote_load}" >/dev/null ||
    fail 'remote image transfer does not prove backend count zero'
  ! grep -F 'remote_compose up' "${remote_load}" >/dev/null || fail 'remote image load starts backend'
  assert_literals_in_order "${remote_start}" 'single V126 backend start contract' \
    'remote_assert_compose_backend_image "${image_tag}"' \
    'remote_compose create --force-recreate --no-build --no-deps --pull never backend' \
    'docker update --restart=no "${backend_container}"' \
    "'{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}'" \
    'docker start "${backend_container}"' \
    'remote_wait_backend_running "${backend_container}"' \
    'remote_assert_single_v126_backend_poller "${image_id}"'
  [[ "$(grep -F -c 'docker start "${backend_container}"' "${remote_start}" || true)" == 1 ]] ||
    fail 'V126 startup must contain exactly one start command'
  ! grep -F 'remote_compose up' "${remote_start}" >/dev/null ||
    fail 'V126 startup bypasses the create/restart-disable/single-start contract'
  ! grep -E 'docker (save|load)' "${remote_start}" >/dev/null || fail 'startup transfers or loads an image'

  local hybrid="${TEST_ROOT}/v126-cutover-image-mismatch.sh"
  local fake_bin="${TEST_ROOT}/image-fake-bin"
  local docker_log="${TEST_ROOT}/image-docker.log"
  local remote_log="${TEST_ROOT}/image-remote.log"
  local old_path="${PATH}"
  local state
  make_instrumented_script "${CUTOVER_SCRIPT}" "${hybrid}" V126_IMAGE_TRANSFERRED_AND_VERIFIED
  mkdir -m 0700 "${fake_bin}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "docker %s\\n" "$*" >> "${HT12P_DOCKER_LOG:?}"' \
    'if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then' \
    "  printf '%s\\n' 'sha256:2222222222222222222222222222222222222222222222222222222222222222'" \
    '  exit 0' \
    'fi' \
    'exit 97' > "${fake_bin}/docker"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "ssh %s\\n" "$*" >> "${HT12P_REMOTE_LOG:?}"' \
    'exit 98' > "${fake_bin}/ssh"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "rsync %s\\n" "$*" >> "${HT12P_REMOTE_LOG:?}"' \
    'exit 99' > "${fake_bin}/rsync"
  chmod 0700 "${fake_bin}/docker" "${fake_bin}/ssh" "${fake_bin}/rsync"
  new_state "${hybrid}" image-mismatch
  state="${NEW_STATE}"
  seed_chain "${state}" 9
  export HT12P_DOCKER_LOG="${docker_log}"
  export HT12P_REMOTE_LOG="${remote_log}"
  PATH="${fake_bin}:${old_path}"
  expect_failure 'local image-ID mismatch fails before remote mutation' \
    'Stage V126_IMAGE_TRANSFERRED_AND_VERIFIED failed closed' \
    invoke_script "${hybrid}" stage --state-dir "${state}" V126_IMAGE_TRANSFERRED_AND_VERIFIED
  PATH="${old_path}"
  unset HT12P_DOCKER_LOG HT12P_REMOTE_LOG
  grep -F 'local V126 image ID mismatch before remote mutation' \
    "${state}/artifacts/10-V126_IMAGE_TRANSFERRED_AND_VERIFIED.failed.log" >/dev/null ||
    fail 'restricted failed-stage log lacks the local image-ID mismatch reason'
  [[ -s "${docker_log}" ]] || fail 'image mismatch fixture did not inspect the local image'
  [[ ! -s "${remote_log}" ]] || fail 'image mismatch reached SSH or rsync'
  pass 'image transfer/startup separation and pre-remote mismatch rejection verify'
}

run_compose_mapping_fixture() {
  local compose_json="$1"
  local expected_image="$2"
  local mutation_log="$3"
  bash -s -- "${CUTOVER_SCRIPT}" "${compose_json}" "${expected_image}" "${mutation_log}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_json="$2"
fixture_expected="$3"
fixture_mutation_log="$4"
remote_compose() {
  if [[ "$*" == 'config --format json' ]]; then
    printf '%s\n' "${fixture_json}"
    return 0
  fi
  printf 'remote_compose %s\n' "$*" >> "${fixture_mutation_log}"
}
remote_assert_compose_backend_image "${fixture_expected}"
remote_compose create --force-recreate --no-build --no-deps --pull never backend
SH
}

test_backend_specific_compose_mapping() {
  local mutation_log="${TEST_ROOT}/compose-mapping-mutation.log"
  local expected="hookah-v126:${RELEASE_SHA}"
  local wrong="hookah-v125:${V125_SOURCE_SHA}"
  local misleading
  misleading="{\"services\":{\"backend\":{\"image\":\"${wrong}\"},\"worker\":{\"image\":\"${expected}\"}}}"
  expect_failure 'backend-specific Compose mismatch rejects before create/start' \
    'backend-specific Compose image resolution failed|backend service image' \
    run_compose_mapping_fixture "${misleading}" "${expected}" "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] || fail 'Compose backend mismatch reached create/start mutation'

  local exact
  exact="{\"services\":{\"backend\":{\"image\":\"${expected}\"},\"worker\":{\"image\":\"${wrong}\"}}}"
  expect_success 'backend-specific Compose mapping accepts only the backend exact tag' \
    run_compose_mapping_fixture "${exact}" "${expected}" "${mutation_log}"
  [[ "$(grep -F -c 'remote_compose create --force-recreate --no-build --no-deps --pull never backend' \
    "${mutation_log}" || true)" == 1 ]] || fail 'exact backend mapping did not reach one bounded create'
  pass 'Compose service mapping is backend-specific and fail-closed before start'
}

run_explicit_compose_file_fixture() {
  local staging="$1"
  local fake_bin="$2"
  local expected_image="$3"
  bash -s -- "${CUTOVER_SCRIPT}" "${staging}" "${fake_bin}" \
    "${expected_image}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_staging="$2"
fixture_fake_bin="$3"
fixture_expected="$4"
PATH="${fixture_fake_bin}:${PATH}"
cd "${fixture_staging}"
REMOTE_BACKEND_IMAGE="${fixture_expected}"
remote_assert_compose_backend_image "${fixture_expected}"
remote_compose create --force-recreate --no-build --no-deps --pull never backend >/dev/null
SH
}

test_explicit_compose_file_selection() {
  local staging="${TEST_ROOT}/explicit-compose-file-staging"
  local fake_bin="${TEST_ROOT}/explicit-compose-file-bin"
  local command_log="${TEST_ROOT}/explicit-compose-file.log"
  local expected="hookah-v126:${RELEASE_SHA}"
  mkdir -m 0700 -p "${staging}" "${fake_bin}"
  printf '%s\n' 'BOUND_ENV=1' > "${staging}/.env"
  printf 'services:\n  backend:\n    image: %s\n' "${expected}" > \
    "${staging}/docker-compose.yml"
  printf '%s\n' \
    'services:' \
    '  backend:' \
    "    image: hookah-v125:${V125_SOURCE_SHA}" > "${staging}/compose.yaml"
  printf '%s\n' \
    'services:' \
    '  backend:' \
    '    image: attacker-controlled:latest' > "${staging}/compose.override.yaml"
  chmod 0600 "${staging}/.env"
  chmod 0644 "${staging}/docker-compose.yml" "${staging}/compose.yaml" \
    "${staging}/compose.override.yaml"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    "printf 'docker %s\\n' \"\$*\" >> '${command_log}'" \
    'case "$*" in' \
    "  'compose --env-file .env --file docker-compose.yml config --format json')" \
    "    printf '%s\\n' '{\"services\":{\"backend\":{\"image\":\"${expected}\"}}}'" \
    '    ;;' \
    "  'compose --env-file .env --file docker-compose.yml create --force-recreate --no-build --no-deps --pull never backend') : ;;" \
    '  *) exit 97 ;;' \
    'esac' > "${fake_bin}/docker"
  chmod 0700 "${fake_bin}/docker"

  expect_success 'Compose calls select only canonical docker-compose.yml despite competing defaults' \
    run_explicit_compose_file_fixture "${staging}" "${fake_bin}" "${expected}"
  python3 - "${command_log}" <<'PY'
import sys

actual = open(sys.argv[1], "rt", encoding="utf-8").read().splitlines()
expected = [
    "docker compose --env-file .env --file docker-compose.yml config --format json",
    "docker compose --env-file .env --file docker-compose.yml create --force-recreate --no-build --no-deps --pull never backend",
]
if actual != expected:
    raise SystemExit(f"explicit Compose file selection mismatch: {actual!r}")
if any("compose.yaml" in line or "compose.override.yaml" in line for line in actual):
    raise SystemExit("competing Compose default reached the command surface")
PY
  pass 'every real Compose invocation carries the explicit canonical file and ignores competing defaults'
}

run_real_backup_rehearsal_cleanup_fixture() {
  local fixture_mode="$1"
  local fixture_root="$2"
  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${fixture_mode}" "${fixture_root}" \
    "${RELEASE_SHA}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_mode="$2"
fixture_root="$3"
fixture_release="$4"
fixture_staging="${fixture_root}/staging"
fixture_run_id=fixture-rehearsal
fixture_run_root="${fixture_staging}/.v126-runs/${fixture_run_id}"
fixture_backup_root="${fixture_root}/backup"
fixture_sudo_root="${fixture_root}/sudo-root"
fixture_docker_root="${fixture_root}/docker-root"
fixture_log="${fixture_root}/lifecycle.log"
fixture_volume_state="${fixture_root}/volume.state"
fixture_volume_owner="${fixture_root}/volume.owner"
fixture_container_state="${fixture_root}/container.state"
fixture_container_owner="${fixture_root}/container.owner"
fixture_expected_owner_file="${fixture_root}/expected-owner"
fixture_sentinel_guard="${fixture_root}/sentinel.guard"
fixture_postgres_id=aaaaaaaaaaaa
fixture_postgres_image=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
fixture_expected_name="hookah-v126-fixture-rehearsal-quiesced-$$"
fixture_expected_owner="v126:${fixture_release}:fixture-rehearsal:quiesced:$$"
fixture_sentinel_name="${fixture_expected_name}-sentinel"

mkdir -m 0700 -p \
  "${fixture_staging}" \
  "${fixture_run_root}" \
  "${fixture_backup_root}" \
  "${fixture_sudo_root}/var/backups/hookah-bot" \
  "${fixture_docker_root}"
printf '%s\n' "${fixture_expected_owner}" > "${fixture_expected_owner_file}"
printf '%s\n' SENTINEL_UNTOUCHED > "${fixture_sentinel_guard}"

remote_require_run_root() {
  [[ "$1" == "${fixture_staging}" && "$2" == "${fixture_run_id}" ]] ||
    die 'rehearsal run-root fixture binding mismatch'
  printf '%s\n' "${fixture_run_root}"
}
remote_verify_baseline_authority() {
  [[ "$1" == "${fixture_staging}" && "$2" == "${fixture_run_id}" &&
    "$3" == "${fixture_release}" ]] ||
    die 'rehearsal baseline-authority fixture binding mismatch'
}
remote_assert_compose_backend_image() {
  [[ "$1" == "hookah-v125:${V125_SOURCE_SHA}" ]] ||
    die 'rehearsal Compose-image fixture binding mismatch'
}
remote_assert_zero_writer() {
  [[ "$1" == 125:0:0 ]] ||
    die 'rehearsal zero-writer fixture binding mismatch'
}
remote_backup_root() {
  [[ "$1" == "${fixture_release}" && "$2" == "${fixture_run_id}" ]] ||
    die 'rehearsal backup-root fixture binding mismatch'
  printf '%s\n' "${fixture_backup_root}"
}
remote_capture_compose_ids() {
  [[ "$*" == 'running postgres' ]] ||
    die "unexpected rehearsal Compose inventory call: $*"
  REMOTE_CAPTURED_CONTAINER_IDS=("${fixture_postgres_id}")
}
remote_compose() {
  case "$*" in
    *'printf %s "$POSTGRES_USER"'*) printf '%s\n' fixture_user ;;
    *'SHOW server_version_num'*) printf '%s\n' 160004 ;;
    *'SELECT pg_database_size(current_database())'*) printf '%s\n' 1024 ;;
    *'pg_dump '*'--format=custom'*) printf '%s\n' fixture-dump ;;
    *'pg_restore --list'*)
      cat >/dev/null
      printf '%s\n' fixture-inventory
      ;;
    *) die "unexpected rehearsal Compose fixture call: $*" ;;
  esac
}

fixture_map_sudo_path() {
  case "$1" in
    /var/backups/hookah-bot*) printf '%s%s\n' "${fixture_sudo_root}" "$1" ;;
    *) printf '%s\n' "$1" ;;
  esac
}
stat() {
  if [[ "${1:-}" == -c ]]; then
    python3 - "${2:-}" "${3:-}" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys

fmt, path, user, group = sys.argv[1:]
info = os.stat(path)
if fmt == "%a:%U:%G":
    print(f"{stat.S_IMODE(info.st_mode):o}:{user}:{group}")
elif fmt == "%a":
    print(f"{stat.S_IMODE(info.st_mode):o}")
elif fmt == "%s":
    print(info.st_size)
else:
    raise SystemExit(f"unexpected rehearsal stat format: {fmt}")
PY
    return
  fi
  command stat "$@"
}
sudo() {
  local verb="$1"
  local target
  shift
  case "${verb}" in
    test)
      if [[ "${1:-}" == '!' ]]; then
        target="$(fixture_map_sudo_path "$3")"
        builtin test ! "$2" "${target}"
      else
        target="$(fixture_map_sudo_path "$2")"
        builtin test "$1" "${target}"
      fi
      ;;
    install)
      target=''
      while (( $# > 0 )); do
        target="$1"
        shift
      done
      target="$(fixture_map_sudo_path "${target}")"
      mkdir -p "${target}"
      chmod 0700 "${target}"
      ;;
    stat)
      [[ "$1" == -c ]] || die 'unexpected rehearsal sudo-stat fixture call'
      stat -c "$2" "$(fixture_map_sudo_path "$3")"
      ;;
    *) die "unexpected rehearsal sudo fixture call: ${verb} $*" ;;
  esac
}
df() {
  [[ "$*" == '--output=avail -B1 '* ]] ||
    die "unexpected rehearsal df fixture call: $*"
  printf '%s\n' Avail 10737418240
}
sleep() { die 'rehearsal fixture unexpectedly slept'; }

docker() {
  local rendered="$*"
  local name=''
  local owner=''
  local actual_owner=''
  local network=''
  local mount=''
  local image=''
  case "${1:-}" in
    ps)
      [[ "${rendered}" == 'ps --all --format {{.Names}}' ]] ||
        die "unexpected rehearsal Docker ps call: ${rendered}"
      printf '%s\n' "${fixture_sentinel_name}"
      [[ ! -f "${fixture_container_state}" ]] || cat "${fixture_container_state}"
      ;;
    info)
      [[ "${2:-}" == --format && "${3:-}" == '{{.DockerRootDir}}' ]] ||
        die "unexpected rehearsal Docker info call: ${rendered}"
      printf '%s\n' "${fixture_docker_root}"
      ;;
    container)
      [[ "${2:-}" == inspect && "${3:-}" == --format &&
        "${4:-}" == '{{ index .Config.Labels "hookah.v126.rehearsal-owner" }}' ]] ||
        die "unexpected rehearsal Docker container call: ${rendered}"
      name="${5:-}"
      [[ -f "${fixture_container_state}" &&
        "$(< "${fixture_container_state}")" == "${name}" ]] || return 1
      actual_owner="$(< "${fixture_container_owner}")"
      if [[ "${actual_owner}" == "${fixture_expected_owner}" ]]; then
        printf '%s\n' CONTAINER_OWNER_EXACT >> "${fixture_log}"
      else
        printf '%s\n' CONTAINER_OWNER_WRONG >> "${fixture_log}"
      fi
      printf '%s\n' "${actual_owner}"
      ;;
    volume)
      case "${2:-}" in
        ls)
          [[ "${rendered}" == 'volume ls --format {{.Name}}' ]] ||
            die "unexpected rehearsal Docker volume-ls call: ${rendered}"
          printf '%s\n' "${fixture_sentinel_name}"
          [[ ! -f "${fixture_volume_state}" ]] || cat "${fixture_volume_state}"
          ;;
        create)
          [[ "${3:-}" == --label ]] ||
            die "unexpected rehearsal volume-create call: ${rendered}"
          owner="${4#hookah.v126.rehearsal-owner=}"
          name="${5:-}"
          [[ "${owner}" == "${fixture_expected_owner}" &&
            "${name}" == "${fixture_expected_name}" ]] ||
            die 'rehearsal volume creation lost its exact name/owner binding'
          actual_owner="${owner}"
          [[ "${fixture_mode}" != wrong-owner-volume ]] || actual_owner=WRONG_OWNER
          printf '%s\n' "${name}" > "${fixture_volume_state}"
          printf '%s\n' "${actual_owner}" > "${fixture_volume_owner}"
          printf '%s\n' VOLUME_CREATE >> "${fixture_log}"
          case "${fixture_mode}" in
            post-volume) return 86 ;;
            cleanup-failure) return 88 ;;
          esac
          printf '%s\n' "${name}"
          ;;
        inspect)
          [[ "${3:-}" == --format &&
            "${4:-}" == '{{ index .Labels "hookah.v126.rehearsal-owner" }}' ]] ||
            die "unexpected rehearsal Docker volume-inspect call: ${rendered}"
          name="${5:-}"
          [[ -f "${fixture_volume_state}" &&
            "$(< "${fixture_volume_state}")" == "${name}" ]] || return 1
          actual_owner="$(< "${fixture_volume_owner}")"
          if [[ "${actual_owner}" == "${fixture_expected_owner}" ]]; then
            printf '%s\n' VOLUME_OWNER_EXACT >> "${fixture_log}"
          else
            printf '%s\n' VOLUME_OWNER_WRONG >> "${fixture_log}"
          fi
          printf '%s\n' "${actual_owner}"
          ;;
        rm)
          name="${3:-}"
          [[ "${name}" == "${fixture_expected_name}" &&
            -f "${fixture_volume_state}" &&
            "$(< "${fixture_volume_state}")" == "${name}" ]] ||
            die 'rehearsal cleanup targeted a wrong or absent volume'
          printf '%s\n' VOLUME_RM_ATTEMPT >> "${fixture_log}"
          [[ "${fixture_mode}" != cleanup-failure ]] || return 93
          rm -f -- "${fixture_volume_state}" "${fixture_volume_owner}"
          printf '%s\n' VOLUME_RM >> "${fixture_log}"
          printf '%s\n' "${name}"
          ;;
        *) die "unexpected rehearsal Docker volume call: ${rendered}" ;;
      esac
      ;;
    run)
      shift
      printf '%s\n' CONTAINER_RUN >> "${fixture_log}"
      while (( $# > 0 )); do
        case "$1" in
          --name) name="$2"; shift 2 ;;
          --label) owner="${2#hookah.v126.rehearsal-owner=}"; shift 2 ;;
          --network) network="$2"; shift 2 ;;
          --mount) mount="$2"; shift 2 ;;
          --env) shift 2 ;;
          --detach) shift ;;
          *) image="$1"; shift ;;
        esac
      done
      [[ "${name}" == "${fixture_expected_name}" &&
        "${owner}" == "${fixture_expected_owner}" &&
        "${network}" == none &&
        "${mount}" == "type=volume,source=${fixture_expected_name},target=/var/lib/postgresql/data" &&
        "${image}" == "${fixture_postgres_image}" ]] ||
        die 'rehearsal Docker run lost its isolated owned-resource binding'
      printf '%s\n' "${name}" > "${fixture_container_state}"
      printf '%s\n' "${owner}" > "${fixture_container_owner}"
      printf '%s\n' CONTAINER_CREATE >> "${fixture_log}"
      [[ "${fixture_mode}" != post-container ]] || return 87
      printf '%s\n' fixture-container-id
      ;;
    inspect)
      [[ "${2:-}" == --format ]] ||
        die "unexpected rehearsal Docker inspect call: ${rendered}"
      if [[ "${3:-}" == '{{.Image}}' && "${4:-}" == "${fixture_postgres_id}" ]]; then
        printf '%s\n' "${fixture_postgres_image}"
        return
      fi
      name="${4:-}"
      [[ -f "${fixture_container_state}" &&
        "$(< "${fixture_container_state}")" == "${name}" ]] ||
        die 'rehearsal inspection targeted the wrong container'
      case "${3:-}" in
        '{{.HostConfig.NetworkMode}}') printf '%s\n' none ;;
        '{{len .HostConfig.PortBindings}}') printf '%s\n' 0 ;;
        '{{len .Mounts}}') printf '%s\n' 1 ;;
        '{{(index .Mounts 0).Type}}') printf '%s\n' volume ;;
        '{{(index .Mounts 0).Name}}') printf '%s\n' "${fixture_expected_name}" ;;
        '{{(index .Mounts 0).Destination}}') printf '%s\n' /var/lib/postgresql/data ;;
        *) die "unexpected rehearsal Docker inspection format: ${3:-}" ;;
      esac
      ;;
    exec)
      name="${2:-}"
      [[ -f "${fixture_container_state}" &&
        "$(< "${fixture_container_state}")" == "${name}" ]] ||
        die 'rehearsal exec targeted the wrong container'
      case "${3:-}" in
        pg_isready|createdb|pg_restore) : ;;
        psql)
          case "${rendered}" in
            *'SHOW server_version_num'*) printf '%s\n' 160004 ;;
            *flyway_schema_history*) printf '%s\n' 125:0:0 ;;
            *) die "unexpected rehearsal psql fixture call: ${rendered}" ;;
          esac
          ;;
        *) die "unexpected rehearsal Docker exec call: ${rendered}" ;;
      esac
      ;;
    cp)
      [[ -s "${2:-}" && -f "${fixture_container_state}" &&
        "${3:-}" == "$(< "${fixture_container_state}"):/tmp/v126-rehearsal.dump" ]] ||
        die "unexpected rehearsal Docker cp call: ${rendered}"
      ;;
    rm)
      [[ "${2:-}" == -f ]] || die "unexpected rehearsal Docker rm call: ${rendered}"
      name="${3:-}"
      [[ "${name}" == "${fixture_expected_name}" &&
        -f "${fixture_container_state}" &&
        "$(< "${fixture_container_state}")" == "${name}" ]] ||
        die 'rehearsal cleanup targeted a wrong or absent container'
      printf '%s\n' CONTAINER_RM_ATTEMPT >> "${fixture_log}"
      rm -f -- "${fixture_container_state}" "${fixture_container_owner}"
      printf '%s\n' CONTAINER_RM >> "${fixture_log}"
      printf '%s\n' "${name}"
      ;;
    *) die "unexpected rehearsal Docker fixture call: ${rendered}" ;;
  esac
}

remote_backup_rehearsal "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
  quiesced "hookah-v125:${V125_SOURCE_SHA}"
[[ "${fixture_mode}" == success ]] || die 'failure rehearsal fixture returned unexpectedly'
[[ ! -e "${fixture_volume_state}" && ! -e "${fixture_volume_owner}" &&
  ! -e "${fixture_container_state}" && ! -e "${fixture_container_owner}" ]] ||
  die 'successful rehearsal left a fake Docker resource allocated'
! declare -p V126_REMOTE_REHEARSAL_CLEANUP_VOLUME >/dev/null 2>&1 ||
  die 'successful rehearsal retained its cleanup-volume global'
! declare -p V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER >/dev/null 2>&1 ||
  die 'successful rehearsal retained its cleanup-container global'
[[ -z "$(trap -p EXIT INT TERM HUP)" ]] ||
  die 'successful rehearsal retained a resource-cleanup trap'
printf '%s\n' SUCCESS_CLEANUP_GLOBALS_RESET
SH
}

assert_real_backup_rehearsal_lifecycle() {
  local fixture_mode="$1"
  local fixture_root="$2"
  local expected=''
  case "${fixture_mode}" in
    post-volume)
      expected=$'VOLUME_CREATE\nVOLUME_OWNER_EXACT\nVOLUME_RM_ATTEMPT\nVOLUME_RM'
      ;;
    post-container)
      expected=$'VOLUME_CREATE\nVOLUME_OWNER_EXACT\nCONTAINER_RUN\nCONTAINER_CREATE\nCONTAINER_OWNER_EXACT\nCONTAINER_RM_ATTEMPT\nCONTAINER_RM\nVOLUME_OWNER_EXACT\nVOLUME_RM_ATTEMPT\nVOLUME_RM'
      ;;
    success)
      expected=$'VOLUME_CREATE\nVOLUME_OWNER_EXACT\nCONTAINER_RUN\nCONTAINER_CREATE\nCONTAINER_OWNER_EXACT\nCONTAINER_OWNER_EXACT\nCONTAINER_RM_ATTEMPT\nCONTAINER_RM\nVOLUME_OWNER_EXACT\nVOLUME_RM_ATTEMPT\nVOLUME_RM'
      ;;
    wrong-owner-volume)
      expected=$'VOLUME_CREATE\nVOLUME_OWNER_WRONG\nVOLUME_OWNER_WRONG'
      ;;
    cleanup-failure)
      expected=$'VOLUME_CREATE\nVOLUME_OWNER_EXACT\nVOLUME_RM_ATTEMPT'
      ;;
    *) fail "unknown rehearsal lifecycle fixture mode: ${fixture_mode}" ;;
  esac
  [[ "$(< "${fixture_root}/lifecycle.log")" == "${expected}" ]] || {
    sed -n '1,120p' "${fixture_root}/lifecycle.log" >&2
    fail "rehearsal lifecycle mismatch: ${fixture_mode}"
  }
  [[ "$(< "${fixture_root}/sentinel.guard")" == SENTINEL_UNTOUCHED ]] ||
    fail "${fixture_mode} touched the similarly named sentinel"
}

test_real_backup_rehearsal_cleanup_contract() {
  local fixture_mode fixture_root actual_status expected_status
  for fixture_mode in post-volume post-container success wrong-owner-volume cleanup-failure; do
    fixture_root="${TEST_ROOT}/real-rehearsal-${fixture_mode}"
    mkdir -m 0700 -p "${fixture_root}"
    capture_path
    if run_real_backup_rehearsal_cleanup_fixture \
      "${fixture_mode}" "${fixture_root}" > "${LAST_OUTPUT}" 2>&1; then
      actual_status=0
    else
      actual_status=$?
    fi
    assert_no_canary_file "${LAST_OUTPUT}"
    case "${fixture_mode}" in
      post-volume) expected_status=86 ;;
      post-container) expected_status=87 ;;
      success) expected_status=0 ;;
      wrong-owner-volume) expected_status=4 ;;
      cleanup-failure) expected_status=88 ;;
    esac
    [[ "${actual_status}" == "${expected_status}" ]] ||
      fail "${fixture_mode} rehearsal status mismatch: expected=${expected_status} actual=${actual_status}"
    assert_real_backup_rehearsal_lifecycle "${fixture_mode}" "${fixture_root}"
    case "${fixture_mode}" in
      post-volume|post-container|success)
        [[ ! -e "${fixture_root}/volume.state" && ! -e "${fixture_root}/volume.owner" &&
          ! -e "${fixture_root}/container.state" && ! -e "${fixture_root}/container.owner" ]] ||
          fail "${fixture_mode} rehearsal retained an owned fake resource"
        ;;
      wrong-owner-volume)
        [[ -f "${fixture_root}/volume.state" &&
          "$(< "${fixture_root}/volume.owner")" == WRONG_OWNER &&
          ! -e "${fixture_root}/container.state" ]] ||
          fail 'wrong-owner rehearsal resource was deleted or mutated'
        grep -F 'created rehearsal volume ownership mismatch' "${LAST_OUTPUT}" >/dev/null ||
          fail 'wrong-owner race did not fail at the immediate ownership check'
        grep -F 'rehearsal cleanup failed after EXIT' "${LAST_OUTPUT}" >/dev/null ||
          fail 'wrong-owner cleanup refusal was not reported'
        ;;
      cleanup-failure)
        [[ -f "${fixture_root}/volume.state" && ! -e "${fixture_root}/container.state" ]] ||
          fail 'cleanup-failure fixture did not retain only the failed volume'
        cmp -s "${fixture_root}/volume.owner" "${fixture_root}/expected-owner" ||
          fail 'cleanup-failure fixture changed the owned volume identity'
        grep -F 'rehearsal volume cleanup removal failed' "${LAST_OUTPUT}" >/dev/null ||
          fail 'cleanup removal failure was not reported'
        grep -F 'rehearsal cleanup failed after EXIT' "${LAST_OUTPUT}" >/dev/null ||
          fail 'EXIT cleanup failure was not reported'
        ;;
    esac
    pass "real rehearsal cleanup contract: ${fixture_mode}"
  done
  pass 'real rehearsal prearms exact ownership, cleans partial resources, and preserves original failures'
}

run_inventory_failure_fixture() {
  local mode="$1"
  local mutation_log="$2"
  bash -s -- "${CUTOVER_SCRIPT}" "${mode}" "${mutation_log}" <<'SH'
set -Eeuo pipefail
source "$1"
set +u # macOS Bash 3 treats an explicitly empty indexed array as unset under nounset.
fixture_mode="$2"
fixture_log="$3"
remote_compose() {
  case "${fixture_mode}" in
    compose-running|compose-all) return 91 ;;
    compose-duplicate) printf '%s\n%s\n' aaaaaaaaaaaa aaaaaaaaaaaa ;;
    *) return 92 ;;
  esac
}
docker() {
  case "${fixture_mode}:$1:$2" in
    docker-ps:ps:-q) return 93 ;;
    docker-inspect:ps:-q) printf '%s\n' bbbbbbbbbbbb ;;
    docker-inspect:inspect:--format) return 94 ;;
    *) return 95 ;;
  esac
}
case "${fixture_mode}" in
  compose-running) remote_capture_compose_ids running backend ;;
  compose-all) remote_capture_compose_ids all backend ;;
  compose-duplicate) remote_capture_compose_ids running backend ;;
  docker-ps) remote_capture_docker_running_ids ;;
  docker-inspect)
    remote_capture_running_image_ids \
      sha256:1111111111111111111111111111111111111111111111111111111111111111
    ;;
  *) die 'unknown inventory failure fixture' ;;
esac
printf '%s\n' MUTATION >> "${fixture_log}"
SH
}

test_inventory_failure_contract() {
  local mutation_log="${TEST_ROOT}/inventory-failure-mutation.log"
  local mode pattern
  while IFS='|' read -r mode pattern; do
    : > "${mutation_log}"
    expect_failure "${mode} inventory failure rejects before zero/count claims" \
      "${pattern}" run_inventory_failure_fixture "${mode}" "${mutation_log}"
    [[ ! -s "${mutation_log}" ]] ||
      fail "${mode} inventory failure returned an empty/successful count"
  done <<'EOF'
compose-running|Compose running-container inventory failed
compose-all|Compose all-container inventory failed
compose-duplicate|duplicate container identity
docker-ps|Docker running-container inventory failed
docker-inspect|Docker image inventory became unobservable
EOF
  pass 'Compose and Docker inventory errors cannot collapse into an apparent zero count'
}

run_backend_runtime_guard_fixture() {
  local restart_state="$1"
  local poller_state="$2"
  local live_v125="$3"
  local live_old="$4"
  bash -s -- "${CUTOVER_SCRIPT}" "${V126_IMAGE_ID}" "${restart_state}" \
    "${poller_state}" "${live_v125}" "${live_old}" <<'SH'
set -Eeuo pipefail
source "$1"
expected_id="$2"
fixture_restart="$3"
fixture_poller="$4"
fixture_live_v125="$5"
fixture_live_old="$6"
remote_compose() {
  case "$*" in
    'ps -aq backend'|'ps --status running -q backend') printf '%s\n' aaaaaaaaaaaa ;;
    *) return 97 ;;
  esac
}
docker() {
  local command="${1:-}"
  shift || true
  case "${command} $*" in
    "inspect --format {{.Image}} aaaaaaaaaaaa") printf '%s\n' "${expected_id}" ;;
    "inspect --format {{.Image}} bbbbbbbbbbbb") printf '%s\n' \
      'sha256:9999999999999999999999999999999999999999999999999999999999999999' ;;
    "inspect --format {{.Image}} cccccccccccc") printf '%s\n' "${V125_IMAGE_ID}" ;;
    "inspect --format {{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}} aaaaaaaaaaaa")
      printf '%s\n' "${fixture_restart}"
      ;;
    "inspect --format {{index .Config.Labels \"com.docker.compose.project\"}} aaaaaaaaaaaa")
      printf '%s\n' fixture-project
      ;;
    'exec aaaaaaaaaaaa sh -c test "${TELEGRAM_BOT_ENABLED:-}" = true && test "${TELEGRAM_BOT_MODE:-}" = long_polling')
      [[ "${fixture_poller}" == exact ]]
      ;;
    "ps -q")
      printf '%s\n' aaaaaaaaaaaa
      [[ "${fixture_live_v125}" == true ]] && printf '%s\n' cccccccccccc
      return 0
      ;;
    "ps -q --filter label=com.docker.compose.project=fixture-project --filter label=com.docker.compose.service=backend")
      printf '%s\n' aaaaaaaaaaaa
      [[ "${fixture_live_old}" == true ]] && printf '%s\n' bbbbbbbbbbbb
      return 0
      ;;
    *) printf 'unexpected docker fixture call: %s %s\n' "${command}" "$*" >&2; return 98 ;;
  esac
}
remote_assert_single_v126_backend_poller "${expected_id}"
SH
}

test_runtime_poller_and_old_image_gates() {
  expect_success 'runtime accepts one exact no-restart V126 long poller' \
    run_backend_runtime_guard_fixture no:0 exact false false
  expect_failure 'runtime rejects an enabled restart policy' \
    'restart policy|RestartCount' run_backend_runtime_guard_fixture always:0 exact false false
  expect_failure 'runtime rejects a nonzero RestartCount' \
    'restart policy|RestartCount' run_backend_runtime_guard_fixture no:1 exact false false
  expect_failure 'runtime rejects a missing or non-long-polling poller' \
    'poller|long-polling' run_backend_runtime_guard_fixture no:0 missing false false
  expect_failure 'runtime rejects a live V125 image' \
    'V125|old-image|global running-container count mismatch' \
    run_backend_runtime_guard_fixture no:0 exact true false
  expect_failure 'runtime rejects another live old-image backend in the project' \
    'old-image|old image' run_backend_runtime_guard_fixture no:0 exact false true
  pass 'runtime requires one exact poller and rejects restart or old-image ambiguity'
}

run_runtime_environment_binding_fixture() {
  local action="$1"
  local fixture_root="$2"
  local container_environment_mode="$3"
  bash -s -- "${CUTOVER_SCRIPT}" "${action}" "${fixture_root}" \
    "${container_environment_mode}" "${RELEASE_SHA}" "${V126_IMAGE_ID}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_action="$2"
fixture_root="$3"
fixture_container_environment_mode="$4"
fixture_release="$5"
fixture_image_id="$6"
fixture_staging="${fixture_root}/staging"
fixture_run_root="${fixture_root}/run-root"
fixture_marker="${fixture_root}/v126-drain.enabled"
fixture_log="${fixture_root}/commands.log"
fixture_backend_id=aaaaaaaaaaaa
mkdir -m 0700 -p "${fixture_staging}/scripts" "${fixture_run_root}"
printf '%s\n' \
  'TELEGRAM_BOT_ENABLED=true' \
  'TELEGRAM_BOT_MODE=long_polling' \
  'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
  'TELEGRAM_ALLOWED_USER_IDS=' \
  'TELEGRAM_ALLOWED_CHAT_IDS=' \
  'STAGING_MAINTENANCE_MODE=V126_SMOKE' \
  'STAGING_MAINTENANCE_ALLOWED_USER_IDS=918273645012345678' \
  'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-918273645012345679' > \
  "${fixture_staging}/.env"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
  "${fixture_staging}/scripts/check-staging-maintenance-config.sh"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
  "${fixture_staging}/scripts/validate-staging-admission.sh"
chmod 0600 "${fixture_staging}/.env"
chmod 0755 "${fixture_staging}/scripts/check-staging-maintenance-config.sh" \
  "${fixture_staging}/scripts/validate-staging-admission.sh"
: > "${fixture_marker}"
chmod 0600 "${fixture_marker}"

log_command() { printf '%s\n' "$*" >> "${fixture_log}"; }
remote_initialize_compose() { cd "${fixture_staging}"; }
remote_require_run_root() { printf '%s\n' "${fixture_run_root}"; }
remote_verify_proof() { :; }
remote_assert_caddy_candidate_active() { log_command caddy-candidate-active; }
remote_assert_caddy_drain_marker() {
  [[ -f "${fixture_marker}" ]] || die 'fixture drain marker is absent'
  log_command drain-marker-present
}
remote_assert_public_drain() {
  [[ -f "${fixture_marker}" ]] || die 'fixture public drain is absent'
  log_command public-drain
}
remote_assert_public_live() {
  [[ ! -e "${fixture_marker}" ]] || die 'fixture public route opened while drain remained'
  log_command public-live
}
remote_assert_protected_unauthenticated_503() { log_command protected-503; }
remote_assert_single_v126_backend_poller() {
  [[ "$1" == "${fixture_image_id}" &&
    "$(docker inspect --format '{{.Image}}' "${fixture_backend_id}")" == "${fixture_image_id}" ]] ||
    die 'runtime fixture same-image binding mismatch'
  log_command same-v126-image
}
remote_assert_schema_v126() { log_command schema-v126; }
remote_assert_telegram_idle() { log_command telegram-idle; }
remote_write_proof() {
  local target="$1"
  shift
  printf '%s\n' "$@" > "${target}"
  chmod 0600 "${target}"
  log_command "proof $(basename "${target}")"
}
remote_emit_artifact() { log_command "artifact $1"; }
remote_compose() {
  case "$*" in
    'ps --status running -q backend') printf '%s\n' "${fixture_backend_id}" ;;
    'ps --status running -q postgres') printf '%s\n' bbbbbbbbbbbb ;;
    'exec -T postgres sh -c '*)
      cat >/dev/null
      printf '%s\n' 0:0
      ;;
    *) die "unexpected runtime-binding Compose call: $*" ;;
  esac
}
docker() {
  case "$*" in
    "inspect --format {{.Image}} ${fixture_backend_id}")
      printf '%s\n' "${fixture_image_id}"
      ;;
    "inspect --format {{json .Config.Env}} ${fixture_backend_id}")
      if [[ "${fixture_container_environment_mode}" == stale ]]; then
        fixture_traffic_policy=V126_SMOKE
        fixture_telegram_users=918273645012345678
        fixture_maintenance_mode=OFF
      else
        fixture_traffic_policy=PRODUCT
        fixture_telegram_users=
        fixture_maintenance_mode=V126_SMOKE
      fi
      python3 - "${fixture_traffic_policy}" "${fixture_telegram_users}" \
        "${fixture_maintenance_mode}" <<'PY'
import json
import sys
traffic, users, maintenance = sys.argv[1:]
print(json.dumps([
    "TELEGRAM_BOT_ENABLED=true",
    "TELEGRAM_BOT_MODE=long_polling",
    f"TELEGRAM_TRAFFIC_POLICY={traffic}",
    f"TELEGRAM_ALLOWED_USER_IDS={users}",
    "TELEGRAM_ALLOWED_CHAT_IDS=",
    f"STAGING_MAINTENANCE_MODE={maintenance}",
    "STAGING_MAINTENANCE_ALLOWED_USER_IDS=918273645012345678",
    "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-918273645012345679",
]))
PY
      ;;
    *) die "unexpected runtime-binding Docker call: $*" ;;
  esac
}
curl() {
  case "$*" in
    '-fsS http://127.0.0.1:8080/health'|'-fsS http://127.0.0.1:8080/db/health')
      printf '%s\n' '{"status":"ok"}'
      ;;
    '-fsS http://127.0.0.1:8080/version')
      printf '{"service":"backend","env":"staging","version":"%s"}\n' "${fixture_release}"
      ;;
    '-fsSI http://127.0.0.1:8080/miniapp/') return 0 ;;
    *) die "unexpected runtime-binding curl call: $*" ;;
  esac
}
sudo() {
  case "$*" in
    'rm -f -- /etc/caddy/v126-drain.enabled')
      command rm -f -- "${fixture_marker}"
      log_command drain-marker-removed
      ;;
    'test ! -e /etc/caddy/v126-drain.enabled') [[ ! -e "${fixture_marker}" ]] ;;
    *) die "unexpected runtime-binding sudo call: $*" ;;
  esac
}

case "${fixture_action}" in
  schema)
    remote_schema_runtime_gate "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v126:${fixture_release}" "${fixture_image_id}"
    ;;
  open)
    remote_open_manual_smoke "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v126:${fixture_release}" "${fixture_image_id}"
    ;;
  *) die 'unknown runtime environment fixture action' ;;
esac
SH
}

test_runtime_environment_rebind_gates() {
  local action fixture_root proof
  for action in schema open; do
    fixture_root="${TEST_ROOT}/runtime-environment-${action}-exact"
    expect_success "state ${action} runtime proof rebinds exact live container environment" \
      run_runtime_environment_binding_fixture "${action}" "${fixture_root}" exact
    case "${action}" in
      schema)
        proof="${fixture_root}/run-root/v126-schema-runtime.proof"
        [[ -f "${fixture_root}/v126-drain.enabled" ]] ||
          fail 'state-12 runtime proof unexpectedly removed the public drain marker'
        ;;
      open)
        proof="${fixture_root}/run-root/manual-smoke-window.proof"
        [[ ! -e "${fixture_root}/v126-drain.enabled" ]] ||
          fail 'state-13 exact runtime proof did not remove the public drain marker'
        assert_literals_in_order "${fixture_root}/commands.log" \
          'state-13 exact runtime/public sequence' \
          same-v126-image schema-v126 telegram-idle drain-marker-present \
          drain-marker-removed public-live protected-503
        ;;
    esac
    [[ -f "${proof}" ]] || fail "state ${action} exact runtime did not seal its proof"

    fixture_root="${TEST_ROOT}/runtime-environment-${action}-stale"
    expect_failure "state ${action} rejects same-image stale live container environment" \
      'backend container does not have the exact bound traffic/maintenance/poller environment' \
      run_runtime_environment_binding_fixture "${action}" "${fixture_root}" stale
    [[ -f "${fixture_root}/v126-drain.enabled" ]] ||
      fail "state ${action} stale container environment removed the public drain marker"
    ! grep -E 'drain-marker-removed|public-live|protected-503|artifact ' \
      "${fixture_root}/commands.log" >/dev/null ||
      fail "state ${action} stale container environment reached public-open or proof mutation"
    case "${action}" in
      schema) proof="${fixture_root}/run-root/v126-schema-runtime.proof" ;;
      open) proof="${fixture_root}/run-root/manual-smoke-window.proof" ;;
    esac
    [[ ! -e "${proof}" ]] || fail "state ${action} stale environment sealed a proof"
  done
  pass 'state-12/state-13 runtime proofs reject same-image stale container environments before public open'
}

run_single_start_fixture() {
  local staging_path="$1"
  local phase="$2"
  local failure_mode="$3"
  local mutation_log="$4"
  bash -s -- "${CUTOVER_SCRIPT}" "${staging_path}" "${phase}" \
    "${failure_mode}" "${mutation_log}" "${RELEASE_SHA}" "${V126_IMAGE_ID}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_staging="$2"
fixture_phase="$3"
fixture_failure_mode="$4"
fixture_log="$5"
fixture_release="$6"
fixture_image_id="$7"
fixture_backend_id=aaaaaaaaaaaa
case "${fixture_phase}" in
  first)
    fixture_maintenance_mode=V126_SMOKE
    fixture_maintenance_users=918273645012345678
    fixture_maintenance_chats=-918273645012345679
    ;;
  final)
    fixture_maintenance_mode=OFF
    fixture_maintenance_users=
    fixture_maintenance_chats=
    ;;
  *) die 'unknown single-start fixture phase' ;;
esac
printf '%s\n' \
  'TELEGRAM_BOT_ENABLED=true' \
  'TELEGRAM_BOT_MODE=long_polling' \
  'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
  'TELEGRAM_ALLOWED_USER_IDS=' \
  'TELEGRAM_ALLOWED_CHAT_IDS=' \
  "STAGING_MAINTENANCE_MODE=${fixture_maintenance_mode}" \
  "STAGING_MAINTENANCE_ALLOWED_USER_IDS=${fixture_maintenance_users}" \
  "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=${fixture_maintenance_chats}" > \
  "${fixture_staging}/.env"
chmod 0600 "${fixture_staging}/.env"
remote_initialize_compose() { cd "${fixture_staging}"; }
remote_require_run_root() { printf '%s\n' /fixture/run-root; }
remote_verify_proof() { :; }
remote_assert_public_drain() { :; }
remote_assert_zero_writer() { :; }
remote_verify_maintenance_env_binding() {
  local expected_mode
  case "${fixture_phase}" in first) expected_mode=V126_SMOKE ;; final) expected_mode=OFF ;; esac
  [[ "$5" == "${expected_mode}" ]] || die 'single-start maintenance phase mismatch'
  REMOTE_BOUND_ENV_SHA256="$(remote_hash_file .env)"
}
remote_assert_compose_backend_image() { :; }
remote_wait_backend_running() { :; }
remote_assert_single_v126_backend_poller() { :; }
remote_assert_health_json() { :; }
remote_assert_version() { :; }
remote_assert_runtime() { :; }
remote_write_proof() { :; }
remote_emit_artifact() { :; }
remote_compose() {
  case "$*" in
    'ps --status running -q backend') : ;;
    'create --force-recreate --no-build --no-deps --pull never backend')
      printf '%s\n' compose-create >> "${fixture_log}"
      if [[ "${fixture_failure_mode}" == drift-during-create ]]; then
        printf '%s\n' 'UNRELATED_DRIFT=1' >> .env
      fi
      ;;
    'ps -aq backend') printf '%s\n' "${fixture_backend_id}" ;;
    *) printf 'unexpected remote_compose start fixture: %s\n' "$*" >&2; return 97 ;;
  esac
}
docker() {
  case "$*" in
    'image inspect --format {{.Id}} '*) printf '%s\n' "${fixture_image_id}" ;;
    "inspect --format {{.Image}} ${fixture_backend_id}") printf '%s\n' "${fixture_image_id}" ;;
    "inspect --format {{json .Config.Env}} ${fixture_backend_id}")
      if [[ "${fixture_failure_mode}" == wrong-container-env ]]; then
        fixture_container_bot_mode=webhook
      else
        fixture_container_bot_mode=long_polling
      fi
      python3 - "${fixture_container_bot_mode}" "${fixture_maintenance_mode}" \
        "${fixture_maintenance_users}" "${fixture_maintenance_chats}" <<'PY'
import json
import sys
bot_mode, maintenance_mode, users, chats = sys.argv[1:]
print(json.dumps([
    "TELEGRAM_BOT_ENABLED=true",
    f"TELEGRAM_BOT_MODE={bot_mode}",
    "TELEGRAM_TRAFFIC_POLICY=PRODUCT",
    "TELEGRAM_ALLOWED_USER_IDS=",
    "TELEGRAM_ALLOWED_CHAT_IDS=",
    f"STAGING_MAINTENANCE_MODE={maintenance_mode}",
    f"STAGING_MAINTENANCE_ALLOWED_USER_IDS={users}",
    f"STAGING_MAINTENANCE_ALLOWED_CHAT_IDS={chats}",
]))
PY
      ;;
    "update --restart=no ${fixture_backend_id}")
      printf '%s\n' restart-disabled >> "${fixture_log}"
      ;;
    "inspect --format {{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}} ${fixture_backend_id}")
      printf '%s\n' no:0
      ;;
    "start ${fixture_backend_id}")
      printf '%s\n' docker-start >> "${fixture_log}"
      [[ "${fixture_failure_mode}" != start-fail ]]
      ;;
    *) printf 'unexpected docker start fixture: %s\n' "$*" >&2; return 98 ;;
  esac
}
V126_INTERNAL_REMOTE_V126_IMAGE_ID="${fixture_image_id}"
remote_start_v126 "${fixture_staging}" fixture-run "${fixture_release}" \
  "hookah-v126:${fixture_release}" "${fixture_image_id}" "${fixture_phase}"
SH
}

test_restart_disabled_single_start() {
  local staging="${TEST_ROOT}/single-start-staging"
  local log="${TEST_ROOT}/single-start.log"
  mkdir -m 0700 -p "${staging}/scripts"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/check-staging-maintenance-config.sh"
  chmod 0700 "${staging}/scripts/check-staging-maintenance-config.sh"
  local phase
  for phase in first final; do
    : > "${log}"
    expect_success "${phase} V126 start binds exact stopped-container environment" \
      run_single_start_fixture "${staging}" "${phase}" success "${log}"
    [[ "$(grep -F -c docker-start "${log}" || true)" == 1 ]] ||
      fail "successful ${phase} V126 start did not execute exactly once"
    assert_literals_in_order "${log}" "${phase} restart disable before start" \
      restart-disabled docker-start

    : > "${log}"
    expect_failure "${phase} V126 start rejects environment drift during Compose create" \
      'staging environment changed during V126 backend creation' \
      run_single_start_fixture "${staging}" "${phase}" drift-during-create "${log}"
    ! grep -F docker-start "${log}" >/dev/null ||
      fail "${phase} V126 environment drift reached docker start"

    : > "${log}"
    expect_failure "${phase} V126 start rejects wrong stopped-container environment" \
      'backend container does not have the exact bound traffic/maintenance/poller environment' \
      run_single_start_fixture "${staging}" "${phase}" wrong-container-env "${log}"
    ! grep -F docker-start "${log}" >/dev/null ||
      fail "${phase} wrong stopped-container environment reached docker start"
  done

  : > "${log}"
  expect_failure 'failed backend start is not retried' '' \
    run_single_start_fixture "${staging}" first start-fail "${log}"
  [[ "$(grep -F -c docker-start "${log}" || true)" == 1 ]] ||
    fail 'failed V126 start was retried or never attempted'
  [[ "$(grep -F -c restart-disabled "${log}" || true)" == 1 ]] ||
    fail 'failed V126 start was attempted before disabling restart policy'
  pass 'V126 backend receives one start attempt with restart policy disabled and zero prior restarts'
}

write_baseline_authority_fixture() {
  local staging="$1"
  local run_root="$2"
  local database_file="$3"
  local identities_file="$4"
  local run_id="${5:-fixture-run}"
  local caddy_sha="${6:-${FIXTURE_BASELINE_CADDY_SHA}}"
  [[ "${caddy_sha}" =~ ^[0-9a-f]{64}$ ]] || fail 'fixture baseline Caddy hash is invalid'
  printf '%s\n' \
    "admission_path=${staging}/scripts/validate-staging-admission.sh" \
    "admission_sha256=$(sha256_file "${staging}/scripts/validate-staging-admission.sh")" \
    "compose_path=${staging}/docker-compose.yml" \
    "compose_sha256=$(sha256_file "${staging}/docker-compose.yml")" \
    "caddy_sha256=${caddy_sha}" \
    "database_url_path=${database_file}" \
    "database_url_sha256=$(sha256_file "${database_file}")" \
    "maintenance_check_path=${staging}/scripts/check-staging-maintenance-config.sh" \
    "maintenance_check_sha256=$(sha256_file "${staging}/scripts/check-staging-maintenance-config.sh")" \
    "maintenance_identities_path=${identities_file}" \
    "maintenance_identities_sha256=$(sha256_file "${identities_file}")" \
    "environment_path=${staging}/.env" \
    "environment_sha256=$(sha256_file "${staging}/.env")" \
    "release_sha=${RELEASE_SHA}" \
    'result=PASS' \
    "run_id=${run_id}" > "${run_root}/baseline-authority.proof"
  printf '%s\n' "$(sha256_file "${run_root}/baseline-authority.proof")" > \
    "${run_root}/baseline-authority.proof.sha256"
  chmod 0600 "${run_root}/baseline-authority.proof" \
    "${run_root}/baseline-authority.proof.sha256"
}

run_real_baseline_fixture() {
  local staging="$1"
  local run_id="$2"
  local database_file="$3"
  local identities_file="$4"
  local expected_compose_sha="$5"
  local expected_maintenance_sha="$6"
  local expected_admission_sha="$7"
  local container_environment_mode="${8:-exact}"
  bash -s -- "${CUTOVER_SCRIPT}" "${staging}" "${run_id}" "${RELEASE_SHA}" \
    "${database_file}" "${identities_file}" "${expected_compose_sha}" \
    "${expected_maintenance_sha}" "${expected_admission_sha}" \
    "${container_environment_mode}" "${V126_IMAGE_ID}" \
    "${FIXTURE_BASELINE_CADDY_SHA}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_staging="$2"
fixture_run_id="$3"
fixture_release="$4"
fixture_database_file="$5"
fixture_identities_file="$6"
fixture_compose_sha="$7"
fixture_maintenance_sha="$8"
fixture_admission_sha="$9"
fixture_container_environment_mode="${10}"
fixture_v126_image_id="${11}"
fixture_caddy_sha="${12}"
V126_INTERNAL_REMOTE_OPERATION_KIND=STAGE
V126_INTERNAL_REMOTE_OPERATION_NAME=BASELINE_VERIFIED
V126_INTERNAL_REMOTE_ACTION=baseline
V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256=NONE
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256=NONE
V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256=NONE
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256=NONE
V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256=NONE
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256=NONE
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256=NONE
V126_INTERNAL_REMOTE_V126_IMAGE_ID="${fixture_v126_image_id}"

stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    python3 - "$3" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys
path, user, group = sys.argv[1:]
print(f"{stat.S_IMODE(os.stat(path).st_mode):o}:{user}:{group}")
PY
    return 0
  fi
  command stat "$@"
}
remote_assert_telegram_idle() { :; }
remote_sudo_require_root_file() { :; }
remote_compose() {
  case "$*" in
    'config --format json')
      printf '{"services":{"backend":{"image":"hookah-v125:%s"}}}\n' "${V125_SOURCE_SHA}"
      ;;
    'ps --status running -q backend'|'ps -aq backend') printf '%s\n' aaaaaaaaaaaa ;;
    'ps --status running -q postgres') printf '%s\n' bbbbbbbbbbbb ;;
    'exec -T postgres sh -c '*)
      local query
      query="$(cat)"
      if [[ "${query}" == *flyway_schema_history* ]]; then
        printf '%s\n' 125:0:0
      elif [[ "${query}" == *telegram_inbound_updates* && "${query}" == *telegram_outbox* ]]; then
        printf '%s\n' 0:0
      else
        die 'unexpected baseline SQL fixture'
      fi
      ;;
    *) die "unexpected baseline Compose call: $*" ;;
  esac
}
docker() {
  case "$*" in
    'inspect --format {{json .Config.Env}} aaaaaaaaaaaa')
      if [[ "${fixture_container_environment_mode}" == stale ]]; then
        python3 - <<'PY'
import json
print(json.dumps([
    "TELEGRAM_BOT_ENABLED=true",
    "TELEGRAM_BOT_MODE=long_polling",
    "TELEGRAM_TRAFFIC_POLICY=V126_SMOKE",
    "TELEGRAM_ALLOWED_USER_IDS=918273645012345678",
    "TELEGRAM_ALLOWED_CHAT_IDS=-918273645012345679",
    "STAGING_MAINTENANCE_MODE=V126_SMOKE",
    "STAGING_MAINTENANCE_ALLOWED_USER_IDS=918273645012345678",
    "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-918273645012345679",
]))
PY
      else
        python3 - <<'PY'
import json
print(json.dumps([
    "TELEGRAM_BOT_ENABLED=true",
    "TELEGRAM_BOT_MODE=long_polling",
    "TELEGRAM_TRAFFIC_POLICY=PRODUCT",
    "TELEGRAM_ALLOWED_USER_IDS=",
    "TELEGRAM_ALLOWED_CHAT_IDS=",
    "STAGING_MAINTENANCE_MODE=OFF",
    "STAGING_MAINTENANCE_ALLOWED_USER_IDS=",
    "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=",
]))
PY
      fi
      ;;
    'inspect --format {{.Image}} aaaaaaaaaaaa'|'image inspect --format {{.Id}} '*)
      printf '%s\n' "${V125_IMAGE_ID}"
      ;;
    'inspect --format {{.Image}} bbbbbbbbbbbb')
      printf 'sha256:%064d\n' 3
      ;;
    'exec aaaaaaaaaaaa sh -c test "${TELEGRAM_BOT_ENABLED:-}" = true && test "${TELEGRAM_BOT_MODE:-}" = long_polling')
      return 0
      ;;
    'ps -q')
      printf '%s\n' aaaaaaaaaaaa bbbbbbbbbbbb
      ;;
    *) die "unexpected baseline Docker call: $*" ;;
  esac
}
curl() {
  case "$*" in
    '-fsS http://127.0.0.1:8080/health'|'-fsS http://127.0.0.1:8080/db/health'|\
    '-fsS https://staging.hookahtootah.club/health'|\
    '-fsS https://staging.hookahtootah.club/db/health')
      printf '%s\n' '{"status":"ok"}'
      ;;
    '-fsS http://127.0.0.1:8080/version')
      printf '{"service":"backend","env":"staging","version":"%s"}\n' "${V125_SOURCE_SHA}"
      ;;
    '-fsSI http://127.0.0.1:8080/miniapp/'|\
    '-fsSI https://staging.hookahtootah.club/miniapp/') return 0 ;;
    *) die "unexpected baseline curl call: $*" ;;
  esac
}
sudo() {
  case "$*" in
    'test ! -e /etc/caddy/v126-drain.enabled'|'test ! -L /etc/caddy/v126-drain.enabled'|\
    'caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile') return 0 ;;
    'systemctl is-active caddy') printf '%s\n' active ;;
    'sha256sum /etc/caddy/Caddyfile')
      printf '%s  %s\n' "${fixture_caddy_sha}" /etc/caddy/Caddyfile
      ;;
    *) die "unexpected baseline sudo call: $*" ;;
  esac
}

remote_baseline "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
  "hookah-v125:${V125_SOURCE_SHA}" "${fixture_database_file}" "${fixture_identities_file}" \
  "${fixture_compose_sha}" "${fixture_maintenance_sha}" "${fixture_admission_sha}"
SH
}

test_real_baseline_secret_egress() {
  local staging="${TEST_ROOT}/real-baseline-staging"
  local database_file="${TEST_ROOT}/real-baseline-database-url"
  local identities_file="${TEST_ROOT}/real-baseline-identities"
  local run_id='real-baseline-pass'
  local compose_sha maintenance_sha admission_sha
  mkdir -m 0700 -p "${staging}/scripts"
  printf '%s\n' \
    "JWT_SECRET=${SECRET_CANARY}_JWT" \
    "TELEGRAM_INIT_DATA=${SECRET_CANARY}_INIT_DATA" \
    'TELEGRAM_BOT_ENABLED=true' \
    'TELEGRAM_BOT_MODE=long_polling' \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'TELEGRAM_ALLOWED_USER_IDS=' \
    'TELEGRAM_ALLOWED_CHAT_IDS=' \
    'STAGING_MAINTENANCE_MODE=OFF' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' > "${staging}/.env"
  printf 'postgresql://operator:%s_DATABASE@db.invalid:5432/exact\n' \
    "${SECRET_CANARY}" > "${database_file}"
  printf 'user_id=%s_IDENTITY\nchat_id=-456\n' \
    "${SECRET_CANARY}" > "${identities_file}"
  printf '%s\n' 'services: {}' > "${staging}/docker-compose.yml"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/check-staging-maintenance-config.sh"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/validate-staging-admission.sh"
  chmod 0600 "${staging}/.env" "${database_file}" "${identities_file}"
  chmod 0644 "${staging}/docker-compose.yml"
  chmod 0755 "${staging}/scripts/check-staging-maintenance-config.sh" \
    "${staging}/scripts/validate-staging-admission.sh"
  compose_sha="$(sha256_file "${staging}/docker-compose.yml")"
  maintenance_sha="$(sha256_file "${staging}/scripts/check-staging-maintenance-config.sh")"
  admission_sha="$(sha256_file "${staging}/scripts/validate-staging-admission.sh")"

  expect_success 'real baseline consumes restricted secret-bearing authority inputs without egress' \
    run_real_baseline_fixture "${staging}" "${run_id}" "${database_file}" \
    "${identities_file}" "${compose_sha}" "${maintenance_sha}" "${admission_sha}"
  python3 - "${LAST_OUTPUT}" "$(stage_remote_artifacts_oracle BASELINE_VERIFIED)" <<'PY'
import re
import sys

path, expected_csv = sys.argv[1:]
expected = sorted(expected_csv.split(","))
actual = []
for raw in open(path, "rt", encoding="utf-8"):
    match = re.fullmatch(r"ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t[0-9a-f]{64}\n?", raw)
    if not match:
        raise SystemExit(f"real baseline emitted unexpected output: {raw!r}")
    actual.append(match.group(1))
if sorted(actual) != expected or len(actual) != len(set(actual)):
    raise SystemExit(f"real baseline artifact set mismatch: {actual!r}")
PY
  assert_tree_has_no_canary "${staging}/.v126-runs/${run_id}"

  expect_failure 'real baseline rejects stale running-container traffic and maintenance bindings' \
    'backend container does not have the exact bound traffic/maintenance/poller environment' \
    run_real_baseline_fixture "${staging}" real-baseline-stale-container \
    "${database_file}" "${identities_file}" "${compose_sha}" "${maintenance_sha}" \
    "${admission_sha}" stale
  ! grep -F $'ARTIFACT\t' "${LAST_OUTPUT}" >/dev/null ||
    fail 'stale baseline running-container environment emitted baseline artifacts'
  [[ ! -e "${staging}/.v126-runs/real-baseline-stale-container/baseline-authority.proof" ]] ||
    fail 'stale baseline running-container environment sealed baseline authority'

  expect_failure 'real baseline failure path redacts restricted authority inputs' \
    'staging Compose source is not the exact release-tracked file' \
    run_real_baseline_fixture "${staging}" real-baseline-failure "${database_file}" \
    "${identities_file}" "$(printf '%064d' 9)" "${maintenance_sha}" "${admission_sha}"
  [[ ! -e "${staging}/.v126-runs/real-baseline-failure" ]] ||
    fail 'failed real baseline created persistent run state'

  rm -f -- "${staging}/.env" "${database_file}" "${identities_file}"
  pass 'real baseline secret canaries remain absent from success output, failure logs, and run state'
}

run_authority_verification_fixture() {
  local staging="$1"
  local run_root="$2"
  local expected_database_sha="$3"
  local expected_identities_sha="$4"
  local expected_compose_sha="$5"
  local expected_maintenance_sha="$6"
  local expected_admission_sha="$7"
  local expected_caddy_sha="$8"
  local expected_env_sha="$9"
  bash -s -- "${CUTOVER_SCRIPT}" "${staging}" "${run_root}" "${RELEASE_SHA}" \
    "${expected_database_sha}" "${expected_identities_sha}" "${expected_compose_sha}" \
    "${expected_maintenance_sha}" "${expected_admission_sha}" "${expected_caddy_sha}" \
    "${expected_env_sha}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_staging="$2"
fixture_run_root="$3"
fixture_release="$4"
fixture_database_sha="$5"
fixture_identities_sha="$6"
fixture_compose_sha="$7"
fixture_maintenance_sha="$8"
fixture_admission_sha="$9"
fixture_caddy_sha="${10}"
fixture_env_sha="${11}"
V126_INTERNAL_REMOTE_OPERATION_KIND=STAGE
V126_INTERNAL_REMOTE_OPERATION_NAME=PRE_DRAIN_BACKUP_REHEARSED
V126_INTERNAL_REMOTE_ACTION=backup-rehearsal
V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256="${fixture_database_sha}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256="${fixture_identities_sha}"
V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256="${fixture_compose_sha}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256="${fixture_maintenance_sha}"
V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256="${fixture_admission_sha}"
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_caddy_sha}"
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="${fixture_env_sha}"
V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256=NONE
V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256=NONE
remote_require_run_root() { printf '%s\n' "${fixture_run_root}"; }
remote_verify_proof() { :; }
remote_require_operator_file() { :; }
remote_verify_baseline_authority "${fixture_staging}" fixture-run "${fixture_release}"
SH
}

run_real_authority_verification_fixture() {
  local staging="$1"
  local run_id="$2"
  shift 2
  bash -s -- "${CUTOVER_SCRIPT}" "${staging}" "${run_id}" "${RELEASE_SHA}" "$@" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_staging="$2"
fixture_run_id="$3"
fixture_release="$4"
shift 4
fixture_hashes=("$@")
(( ${#fixture_hashes[@]} == 7 )) || die 'authority fixture hash count mismatch'
V126_INTERNAL_REMOTE_OPERATION_KIND=RECOVERY
V126_INTERNAL_REMOTE_OPERATION_NAME=pre-v126
V126_INTERNAL_REMOTE_ACTION=recover-pre-v126
V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256="${fixture_hashes[0]}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256="${fixture_hashes[1]}"
V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256="${fixture_hashes[2]}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256="${fixture_hashes[3]}"
V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256="${fixture_hashes[4]}"
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_hashes[5]}"
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="${fixture_hashes[6]}"
V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256=NONE
V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256=NONE
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    python3 - "$3" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys
path, user, group = sys.argv[1:]
print(f"{stat.S_IMODE(os.stat(path).st_mode):o}:{user}:{group}")
PY
    return 0
  fi
  command stat "$@"
}
remote_verify_baseline_authority "${fixture_staging}" "${fixture_run_id}" "${fixture_release}"
SH
}

run_remote_envelope_authority_fixture() {
  local mode="$1"
  local mutation="$2"
  local mutation_log="$3"
  bash -s -- "${CUTOVER_SCRIPT}" "${mode}" "${mutation}" "${mutation_log}" \
    "${TEST_ROOT}/remote-staging" "${RELEASE_SHA}" "${V126_IMAGE_ID}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_mode="$2"
fixture_mutation="$3"
fixture_log="$4"
fixture_staging="$5"
fixture_release="$6"
fixture_v126_image_id="$7"
fixture_hashes=(
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
  eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
  6666666666666666666666666666666666666666666666666666666666666666
  5555555555555555555555555555555555555555555555555555555555555555
)
REMOTE_MODE=true
V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED=V126_INTERNAL_REMOTE_ENVELOPE_V1
V126_INTERNAL_REMOTE_RUN_ID=fixture-run
V126_INTERNAL_REMOTE_RELEASE_SHA="${fixture_release}"
V126_INTERNAL_REMOTE_STAGING_PATH="${fixture_staging}"
V126_INTERNAL_REMOTE_SCRIPT_SHA256=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
V126_INTERNAL_REMOTE_V126_IMAGE_ID="${fixture_v126_image_id}"
V126_INTERNAL_REMOTE_INTENT_HASH=9999999999999999999999999999999999999999999999999999999999999999
V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256="${fixture_hashes[0]}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256="${fixture_hashes[1]}"
V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256="${fixture_hashes[2]}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256="${fixture_hashes[3]}"
V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256="${fixture_hashes[4]}"
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_hashes[5]}"
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="${fixture_hashes[6]}"
V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256=NONE
V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256=NONE
V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256=NONE
V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256=NONE
V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256=NONE
V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256=NONE
case "${fixture_mutation}" in
  database) V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256=NONE ;;
  identities) V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256=NONE ;;
  compose) V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256=NONE ;;
  maintenance) V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256=NONE ;;
  admission) V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256=NONE ;;
  caddy) V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256=NONE ;;
  environment) V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256=NONE ;;
  none) : ;;
  *) die 'unknown envelope authority fixture mutation' ;;
esac
remote_baseline() { printf '%s\n' baseline >> "${fixture_log}"; }
remote_recover_pre_v126() {
  local actual
  actual="$(remote_bound_authority_hash database-url V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256)"
  [[ "${actual}" == "${fixture_hashes[0]}" ]] || die 'recovery DB authority delivery mismatch'
  actual="$(remote_bound_authority_hash maintenance-identities V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256)"
  [[ "${actual}" == "${fixture_hashes[1]}" ]] || die 'recovery identities authority delivery mismatch'
  actual="$(remote_bound_authority_hash compose-source V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256)"
  [[ "${actual}" == "${fixture_hashes[2]}" ]] || die 'recovery Compose authority delivery mismatch'
  actual="$(remote_bound_authority_hash maintenance-check-source V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256)"
  [[ "${actual}" == "${fixture_hashes[3]}" ]] || die 'recovery maintenance authority delivery mismatch'
  actual="$(remote_bound_authority_hash admission-source V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256)"
  [[ "${actual}" == "${fixture_hashes[4]}" ]] || die 'recovery admission authority delivery mismatch'
  actual="$(remote_bound_authority_hash baseline-caddy V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256)"
  [[ "${actual}" == "${fixture_hashes[5]}" ]] || die 'recovery Caddy authority delivery mismatch'
  actual="$(remote_bound_authority_hash baseline-environment V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256)"
  [[ "${actual}" == "${fixture_hashes[6]}" ]] || die 'recovery environment authority delivery mismatch'
  printf '%s\n' recovery >> "${fixture_log}"
}
case "${fixture_mode}" in
  baseline)
    V126_INTERNAL_REMOTE_ACTION=baseline
    V126_INTERNAL_REMOTE_OPERATION_KIND=STAGE
    V126_INTERNAL_REMOTE_OPERATION_NAME=BASELINE_VERIFIED
    V126_INTERNAL_REMOTE_PREDECESSOR_STAGE=NONE
    V126_INTERNAL_REMOTE_PREDECESSOR_HASH=NONE
    V126_INTERNAL_REMOTE_AUTHORIZATION_GATE=NONE
    V126_INTERNAL_REMOTE_AUTHORIZATION_HASH=NONE
    if [[ "${fixture_mutation}" == none ]]; then
      V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256=NONE
      V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256=NONE
      V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256=NONE
      V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256=NONE
      V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256=NONE
      V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256=NONE
      V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256=NONE
    fi
    remote_dispatch_enveloped baseline "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v125:${V125_SOURCE_SHA}" /fixture/database /fixture/identities \
      aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
      bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
      cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
    ;;
  recovery)
    V126_INTERNAL_REMOTE_ACTION=recover-pre-v126
    V126_INTERNAL_REMOTE_OPERATION_KIND=RECOVERY
    V126_INTERNAL_REMOTE_OPERATION_NAME=pre-v126
    V126_INTERNAL_REMOTE_PREDECESSOR_STAGE=BASELINE_VERIFIED
    V126_INTERNAL_REMOTE_PREDECESSOR_HASH=8888888888888888888888888888888888888888888888888888888888888888
    V126_INTERNAL_REMOTE_AUTHORIZATION_GATE=RECOVERY
    V126_INTERNAL_REMOTE_AUTHORIZATION_HASH=7777777777777777777777777777777777777777777777777777777777777777
    remote_dispatch_enveloped recover-pre-v126 "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v125:${V125_SOURCE_SHA}" "hookah-v126:${fixture_release}"
    ;;
  *) die 'unknown envelope authority fixture mode' ;;
esac
SH
}

test_baseline_authority_file_swaps() {
  local staging="${TEST_ROOT}/authority-staging"
  local run_root="${TEST_ROOT}/authority-run-root"
  local database_file="${TEST_ROOT}/authority-database-url"
  local identities_file="${TEST_ROOT}/authority-identities"
  local database_sha identities_sha compose_sha maintenance_sha admission_sha caddy_sha env_sha target label
  mkdir -m 0700 -p "${staging}/scripts" "${run_root}"
  printf '%s\n' 'services: {}' > "${staging}/docker-compose.yml"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/check-staging-maintenance-config.sh"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/validate-staging-admission.sh"
  printf 'postgresql://operator:%s_DATABASE@db.invalid:5432/exact\n' \
    "${SECRET_CANARY}" > "${database_file}"
  printf 'user_id=%s_IDENTITY\nchat_id=-456\n' \
    "${SECRET_CANARY}" > "${identities_file}"
  printf '%s\n' \
    "JWT_SECRET=${SECRET_CANARY}_AUTHORITY_JWT" \
    "INIT_DATA_SECRET=${SECRET_CANARY}_AUTHORITY_INIT" \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'STAGING_MAINTENANCE_MODE=OFF' > "${staging}/.env"
  chmod 0644 "${staging}/docker-compose.yml"
  chmod 0755 "${staging}/scripts/check-staging-maintenance-config.sh" \
    "${staging}/scripts/validate-staging-admission.sh"
  chmod 0600 "${database_file}" "${identities_file}" "${staging}/.env"
  write_baseline_authority_fixture "${staging}" "${run_root}" "${database_file}" "${identities_file}"
  database_sha="$(sha256_file "${database_file}")"
  identities_sha="$(sha256_file "${identities_file}")"
  compose_sha="$(sha256_file "${staging}/docker-compose.yml")"
  maintenance_sha="$(sha256_file "${staging}/scripts/check-staging-maintenance-config.sh")"
  admission_sha="$(sha256_file "${staging}/scripts/validate-staging-admission.sh")"
  caddy_sha="${FIXTURE_BASELINE_CADDY_SHA}"
  env_sha="$(sha256_file "${staging}/.env")"
  expect_success 'unchanged baseline DB, identity, Compose, helper, Caddy, and environment authorities verify' \
    run_authority_verification_fixture "${staging}" "${run_root}" \
    "${database_sha}" "${identities_sha}" "${compose_sha}" "${maintenance_sha}" \
    "${admission_sha}" "${caddy_sha}" "${env_sha}"

  while IFS='|' read -r label target; do
    printf '%s\n' "swapped-${label}" >> "${target}"
    expect_failure "post-baseline ${label} swap rejects" \
      'changed after baseline|release-bound.*changed|does not match the baseline receipt|differs from immutable' \
      run_authority_verification_fixture "${staging}" "${run_root}" \
      "${database_sha}" "${identities_sha}" "${compose_sha}" "${maintenance_sha}" \
      "${admission_sha}" "${caddy_sha}" "${env_sha}"
    case "${label}" in
      database-url)
        printf 'postgresql://operator:%s_DATABASE@db.invalid:5432/exact\n' \
          "${SECRET_CANARY}" > "${target}"
        ;;
      maintenance-identities)
        printf 'user_id=%s_IDENTITY\nchat_id=-456\n' \
          "${SECRET_CANARY}" > "${target}"
        ;;
      compose-source) printf '%s\n' 'services: {}' > "${target}" ;;
      maintenance-helper) printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > "${target}" ;;
      admission-helper) printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > "${target}" ;;
      baseline-environment)
        printf '%s\n' \
          "JWT_SECRET=${SECRET_CANARY}_AUTHORITY_JWT" \
          "INIT_DATA_SECRET=${SECRET_CANARY}_AUTHORITY_INIT" \
          'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
          'STAGING_MAINTENANCE_MODE=OFF' > "${target}"
        ;;
    esac
  done <<EOF
database-url|${database_file}
maintenance-identities|${identities_file}
compose-source|${staging}/docker-compose.yml
maintenance-helper|${staging}/scripts/check-staging-maintenance-config.sh
admission-helper|${staging}/scripts/validate-staging-admission.sh
baseline-environment|${staging}/.env
EOF

  local real_run_id='consistent-authority-run'
  local real_run_root="${staging}/.v126-runs/${real_run_id}"
  mkdir -m 0700 -p "${staging}/.v126-runs" "${real_run_root}"
  write_baseline_authority_fixture "${staging}" "${real_run_root}" \
    "${database_file}" "${identities_file}" "${real_run_id}"
  expect_success 'real baseline authority proof matches all seven streamed receipt hashes' \
    run_real_authority_verification_fixture "${staging}" "${real_run_id}" \
    "${database_sha}" "${identities_sha}" "${compose_sha}" "${maintenance_sha}" \
    "${admission_sha}" "${caddy_sha}" "${env_sha}"
  while IFS='|' read -r label target; do
    printf '%s\n' "consistently-rewritten-${label}" >> "${target}"
    write_baseline_authority_fixture "${staging}" "${real_run_root}" \
      "${database_file}" "${identities_file}" "${real_run_id}"
    expect_failure "consistent proof and ${label} source substitution rejects" \
      'does not match the streamed baseline receipt' \
      run_real_authority_verification_fixture "${staging}" "${real_run_id}" \
      "${database_sha}" "${identities_sha}" "${compose_sha}" "${maintenance_sha}" \
      "${admission_sha}" "${caddy_sha}" "${env_sha}"
    case "${label}" in
      database-url)
        printf 'postgresql://operator:%s_DATABASE@db.invalid:5432/exact\n' \
          "${SECRET_CANARY}" > "${target}"
        ;;
      maintenance-identities)
        printf 'user_id=%s_IDENTITY\nchat_id=-456\n' \
          "${SECRET_CANARY}" > "${target}"
        ;;
      compose-source) printf '%s\n' 'services: {}' > "${target}" ;;
      maintenance-helper) printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > "${target}" ;;
      admission-helper) printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > "${target}" ;;
      baseline-environment)
        printf '%s\n' \
          "JWT_SECRET=${SECRET_CANARY}_AUTHORITY_JWT" \
          "INIT_DATA_SECRET=${SECRET_CANARY}_AUTHORITY_INIT" \
          'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
          'STAGING_MAINTENANCE_MODE=OFF' > "${target}"
        ;;
    esac
    write_baseline_authority_fixture "${staging}" "${real_run_root}" \
      "${database_file}" "${identities_file}" "${real_run_id}"
  done <<EOF
database-url|${database_file}
maintenance-identities|${identities_file}
compose-source|${staging}/docker-compose.yml
maintenance-helper|${staging}/scripts/check-staging-maintenance-config.sh
admission-helper|${staging}/scripts/validate-staging-admission.sh
baseline-environment|${staging}/.env
EOF
  write_baseline_authority_fixture "${staging}" "${real_run_root}" \
    "${database_file}" "${identities_file}" "${real_run_id}" \
    8888888888888888888888888888888888888888888888888888888888888888
  expect_failure 'consistent baseline Caddy proof substitution rejects' \
    'baseline Caddy identity does not match the streamed baseline receipt' \
    run_real_authority_verification_fixture "${staging}" "${real_run_id}" \
    "${database_sha}" "${identities_sha}" "${compose_sha}" "${maintenance_sha}" \
    "${admission_sha}" "${caddy_sha}" "${env_sha}"
  write_baseline_authority_fixture "${staging}" "${real_run_root}" \
    "${database_file}" "${identities_file}" "${real_run_id}"
  assert_tree_has_no_canary "${run_root}"
  assert_tree_has_no_canary "${real_run_root}"
  rm -f -- "${database_file}" "${identities_file}" "${staging}/.env"
  pass 'baseline seals five execution sources plus Caddy and environment bytes against consistent proof substitution'
}

test_baseline_authority_envelope_contract() {
  local mutation_log="${TEST_ROOT}/authority-envelope-mutation.log"
  expect_success 'baseline envelope carries exactly seven NONE authority bindings' \
    run_remote_envelope_authority_fixture baseline none "${mutation_log}"
  [[ "$(cat "${mutation_log}")" == baseline ]] ||
    fail 'exact baseline NONE envelope did not reach its bounded helper'
  local authority
  for authority in database identities compose maintenance admission caddy environment; do
    : > "${mutation_log}"
    expect_failure "baseline envelope rejects pre-existing ${authority} authority" \
      'must not claim a pre-existing authority receipt' \
      run_remote_envelope_authority_fixture baseline "${authority}" "${mutation_log}"
    [[ ! -s "${mutation_log}" ]] ||
      fail "invalid baseline ${authority} envelope reached its helper"
  done

  : > "${mutation_log}"
  expect_success 'recovery receives all seven exact baseline authority hashes' \
    run_remote_envelope_authority_fixture recovery none "${mutation_log}"
  [[ "$(cat "${mutation_log}")" == recovery ]] ||
    fail 'seven-hash recovery envelope did not reach its bounded helper'
  for authority in database identities compose maintenance admission caddy environment; do
    : > "${mutation_log}"
    expect_failure "recovery rejects missing ${authority} authority hash" \
      'lacks an exact baseline authority receipt binding' \
      run_remote_envelope_authority_fixture recovery "${authority}" "${mutation_log}"
    [[ ! -s "${mutation_log}" ]] ||
      fail "recovery with missing ${authority} authority reached its helper"
  done
  pass 'baseline and recovery envelopes enforce all seven authority hash positions'
}

write_maintenance_env_proof_fixture() {
  local run_root="$1"
  local run_id="$2"
  local mode="$3"
  local env_sha="$4"
  local before_sha="${5:-${env_sha}}"
  local lower_mode
  lower_mode="$(printf '%s' "${mode}" | tr '[:upper:]' '[:lower:]')"
  printf '%s\n' \
    "run_id=${run_id}" \
    "release_sha=${RELEASE_SHA}" \
    "mode=${mode}" \
    "before_sha256=${before_sha}" \
    "after_sha256=${env_sha}" \
    'identities=BOUND_REDACTED' \
    'unrelated_bytes=PRESERVED' \
    'result=PASS' > "${run_root}/maintenance-${lower_mode}.proof"
  printf '%s\n' "$(sha256_file "${run_root}/maintenance-${lower_mode}.proof")" > \
    "${run_root}/maintenance-${lower_mode}.proof.sha256"
  chmod 0600 "${run_root}/maintenance-${lower_mode}.proof" \
    "${run_root}/maintenance-${lower_mode}.proof.sha256"
}

prepare_environment_authority_fixture() {
  local fixture_root="$1"
  local run_id="$2"
  local staging="${fixture_root}/staging"
  local run_root="${staging}/.v126-runs/${run_id}"
  local database_file="${fixture_root}/database-url"
  local identities_file="${fixture_root}/identities"
  mkdir -m 0700 -p "${staging}/scripts" "${run_root}"
  printf '%s\n' \
    'JWT_SECRET=fixture-environment-secret' \
    'INIT_DATA_SECRET=fixture-init-data-secret' \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'STAGING_MAINTENANCE_MODE=OFF' > "${staging}/.env"
  printf '%s\n' 'services: {}' > "${staging}/docker-compose.yml"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/check-staging-maintenance-config.sh"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/validate-staging-admission.sh"
  printf '%s\n' 'postgresql://operator:fixture@db.invalid:5432/exact' > "${database_file}"
  printf '%s\n' 'user_id=123456789' 'chat_id=-123456789' > "${identities_file}"
  chmod 0600 "${staging}/.env" "${database_file}" "${identities_file}"
  chmod 0644 "${staging}/docker-compose.yml"
  chmod 0755 "${staging}/scripts/check-staging-maintenance-config.sh" \
    "${staging}/scripts/validate-staging-admission.sh"
  write_baseline_authority_fixture "${staging}" "${run_root}" \
    "${database_file}" "${identities_file}" "${run_id}"
}

run_environment_binding_fixture() {
  local staging="$1"
  local run_id="$2"
  local mode="$3"
  local mutation_log="$4"
  shift 4
  bash -s -- "${CUTOVER_SCRIPT}" "${staging}" "${run_id}" "${RELEASE_SHA}" \
    "${mode}" "${mutation_log}" "$@" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_staging="$2"
fixture_run_id="$3"
fixture_release="$4"
fixture_mode="$5"
fixture_log="$6"
shift 6
fixture_hashes=("$@")
(( ${#fixture_hashes[@]} == 9 )) || die 'environment authority fixture hash count mismatch'
fixture_run_root="${fixture_staging}/.v126-runs/${fixture_run_id}"
V126_INTERNAL_REMOTE_OPERATION_KIND=STAGE
V126_INTERNAL_REMOTE_OPERATION_NAME=PRE_DRAIN_BACKUP_REHEARSED
V126_INTERNAL_REMOTE_ACTION=backup-rehearsal
V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256="${fixture_hashes[0]}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256="${fixture_hashes[1]}"
V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256="${fixture_hashes[2]}"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256="${fixture_hashes[3]}"
V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256="${fixture_hashes[4]}"
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_hashes[5]}"
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="${fixture_hashes[6]}"
V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256=NONE
V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256=NONE
case "${fixture_mode}" in
  smoke|smoke-substitution|recovery-smoke)
    V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256="${fixture_hashes[7]}"
    ;;
  off|off-substitution)
    V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256="${fixture_hashes[8]}"
    ;;
  baseline) : ;;
  *) die 'unknown environment authority fixture mode' ;;
esac
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    python3 - "$3" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys
path, user, group = sys.argv[1:]
print(f"{stat.S_IMODE(os.stat(path).st_mode):o}:{user}:{group}")
PY
    return 0
  fi
  command stat "$@"
}
remote_assert_compose_backend_image() { printf '%s\n' COMPOSE_MAPPING >> "${fixture_log}"; }
remote_flyway_state() { printf '%s\n' FLYWAY >> "${fixture_log}"; printf '%s\n' 125:0:0:0; }
remote_compose() { printf 'COMPOSE %s\n' "$*" >> "${fixture_log}"; return 97; }
docker() { printf 'DOCKER %s\n' "$*" >> "${fixture_log}"; return 98; }
sudo() { printf 'CADDY %s\n' "$*" >> "${fixture_log}"; return 99; }
if [[ "${fixture_mode}" == recovery-smoke ]]; then
  V126_INTERNAL_REMOTE_OPERATION_KIND=RECOVERY
  V126_INTERNAL_REMOTE_OPERATION_NAME=pre-v126
  V126_INTERNAL_REMOTE_ACTION=recover-pre-v126
  remote_recover_pre_v126 "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
    "hookah-v125:${V125_SOURCE_SHA}" "hookah-v126:${fixture_release}"
else
  remote_initialize_compose "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
    "hookah-v125:${V125_SOURCE_SHA}"
fi
SH
}

test_state_aware_environment_authority() {
  local mode root staging run_id run_root database_file identities_file
  local database_sha identities_sha compose_sha maintenance_sha admission_sha env_sha
  local smoke_sha=NONE off_sha=NONE mutation_log proof label pattern
  for mode in baseline smoke off smoke-substitution off-substitution recovery-smoke; do
    root="${TEST_ROOT}/environment-authority-${mode}"
    run_id="environment-${mode}"
    staging="${root}/staging"
    run_root="${staging}/.v126-runs/${run_id}"
    database_file="${root}/database-url"
    identities_file="${root}/identities"
    mutation_log="${root}/mutation.log"
    prepare_environment_authority_fixture "${root}" "${run_id}"
    database_sha="$(sha256_file "${database_file}")"
    identities_sha="$(sha256_file "${identities_file}")"
    compose_sha="$(sha256_file "${staging}/docker-compose.yml")"
    maintenance_sha="$(sha256_file "${staging}/scripts/check-staging-maintenance-config.sh")"
    admission_sha="$(sha256_file "${staging}/scripts/validate-staging-admission.sh")"
    env_sha="$(sha256_file "${staging}/.env")"
    smoke_sha=NONE
    off_sha=NONE
    case "${mode}" in
      smoke|smoke-substitution|recovery-smoke)
        write_maintenance_env_proof_fixture "${run_root}" "${run_id}" V126_SMOKE "${env_sha}"
        proof="${run_root}/maintenance-v126_smoke.proof"
        smoke_sha="$(sha256_file "${proof}")"
        ;;
      off|off-substitution)
        write_maintenance_env_proof_fixture "${run_root}" "${run_id}" OFF "${env_sha}"
        proof="${run_root}/maintenance-off.proof"
        off_sha="$(sha256_file "${proof}")"
        ;;
    esac
    printf '%s\n' "ARBITRARY_ENV_DRIFT=${mode}" >> "${staging}/.env"
    case "${mode}" in
      smoke-substitution)
        write_maintenance_env_proof_fixture "${run_root}" "${run_id}" V126_SMOKE \
          "$(sha256_file "${staging}/.env")"
        ;;
      off-substitution)
        write_maintenance_env_proof_fixture "${run_root}" "${run_id}" OFF \
          "$(sha256_file "${staging}/.env")"
        ;;
    esac
    case "${mode}" in
      baseline)
        label='baseline-state environment drift rejects before Compose'
        pattern='current staging environment differs from immutable authority'
        ;;
      smoke)
        label='V126_SMOKE-state environment drift rejects before Compose'
        pattern='current staging environment differs from the immutable maintenance transform'
        ;;
      off)
        label='OFF-state environment drift rejects before Compose'
        pattern='current staging environment differs from the immutable maintenance transform'
        ;;
      smoke-substitution)
        label='rechecksummed V126_SMOKE proof and environment substitution rejects'
        pattern='maintenance proof differs from the immutable stage receipt'
        ;;
      off-substitution)
        label='rechecksummed OFF proof and environment substitution rejects'
        pattern='maintenance proof differs from the immutable stage receipt'
        ;;
      recovery-smoke)
        label='pre-V126 recovery rejects V126_SMOKE environment drift before Flyway'
        pattern='current staging environment differs from the immutable maintenance transform'
        ;;
    esac
    expect_failure "${label}" "${pattern}" \
      run_environment_binding_fixture "${staging}" "${run_id}" "${mode}" "${mutation_log}" \
      "${database_sha}" "${identities_sha}" "${compose_sha}" "${maintenance_sha}" \
      "${admission_sha}" "${FIXTURE_BASELINE_CADDY_SHA}" "${env_sha}" \
      "${smoke_sha}" "${off_sha}"
    [[ ! -s "${mutation_log}" ]] ||
      fail "${mode} environment drift reached Compose, Docker, Caddy, or Flyway"
  done
  pass 'baseline, maintenance, and recovery environment authority is receipt-bound before mutation'
}

run_real_final_preflight_secret_fixture() {
  local staging="$1"
  local run_id="$2"
  local database_file="$3"
  local fake_bin="$4"
  local mutation_mode="${5:-none}"
  local alternate_marker="${6:-${TEST_ROOT}/unused-preflight-alternate.marker}"
  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${staging}" "${run_id}" "${RELEASE_SHA}" \
    "${database_file}" "${fake_bin}" "${mutation_mode}" "${alternate_marker}" <<'SH'
set -Eeuo pipefail
source "$1"
eval "$(declare -f remote_require_operator_file | \
  sed '1s/^remote_require_operator_file/original_remote_require_operator_file/')"
fixture_staging="$2"
fixture_run_id="$3"
fixture_release="$4"
fixture_database_file="$5"
fixture_fake_bin="$6"
fixture_mutation_mode="$7"
fixture_alternate_marker="$8"
fixture_run_root="${fixture_staging}/.v126-runs/${fixture_run_id}"
fixture_cleanup_log="${fixture_run_root}/preflight-cleanup.log"
uploaded="${fixture_run_root}/final-v125-preflight.sh.partial"
database_sha="$(remote_hash_file "${fixture_database_file}")"
script_sha="$(remote_hash_file "${uploaded}")"
PATH="${fixture_fake_bin}:${PATH}"

remote_initialize_compose() {
  [[ "$1" == "${fixture_staging}" && "$2" == "${fixture_run_id}" &&
    "$3" == "${fixture_release}" && "$5" == "${database_sha}" ]] ||
    die 'final-preflight authority binding mismatch'
}
remote_require_run_root() { printf '%s\n' "${fixture_run_root}"; }
remote_verify_proof() {
  if [[ "${fixture_mutation_mode}" == database-swap-at-proof ]]; then
    printf '%s\n' 'postgresql://operator:swapped-password@other.invalid:5432/swapped' > \
      "${fixture_database_file}"
    chmod 0600 "${fixture_database_file}"
  fi
}
remote_assert_public_drain() { :; }
remote_assert_zero_writer() { [[ "$1" == 125:0:0 ]]; }
remote_require_operator_file() {
  original_remote_require_operator_file "$@"
  if [[ "${fixture_mutation_mode}" == credential-guard-failure &&
    "$1" == "${fixture_run_root}/final-v125-preflight.pg_service.conf" ]]; then
    [[ -f "${fixture_run_root}/final-v125-preflight.pg_service.conf" &&
      -f "${fixture_run_root}/final-v125-preflight.pgpass" ]] ||
      die 'preflight credential fixture did not create both restricted files'
    printf '%s\n' CREDENTIALS-CREATED >> "${fixture_cleanup_log}"
    return 97
  fi
  if [[ "${fixture_mutation_mode}" == script-swap-after-hash &&
    "$1" == "${fixture_run_root}/final-v125-preflight.pg_service.conf" ]]; then
    chmod 0600 "${fixture_run_root}/final-v125-preflight.sh"
    printf '%s\n' \
      '#!/usr/bin/env bash' \
      "printf '%s\\n' ALTERED_PRELIGHT_EXECUTED > '${fixture_alternate_marker}'" > \
      "${fixture_run_root}/final-v125-preflight.sh"
    chmod 0500 "${fixture_run_root}/final-v125-preflight.sh"
  fi
}
rm() {
  if [[ "${fixture_mutation_mode}" == credential-guard-failure && $# == 4 &&
    "$1" == -f && "$2" == -- &&
    "$3" == "${fixture_run_root}/final-v125-preflight.pg_service.conf" &&
    "$4" == "${fixture_run_root}/final-v125-preflight.pgpass" ]]; then
    printf '%s\n' PREFLIGHT-CLEANUP-EXACT >> "${fixture_cleanup_log}"
  fi
  command rm "$@"
}
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a' ]]; then
    python3 - "$3" <<'PY'
import os
import stat
import sys
print(f"{stat.S_IMODE(os.stat(sys.argv[1]).st_mode):o}")
PY
    return 0
  fi
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    python3 - "$3" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys
path, user, group = sys.argv[1:]
print(f"{stat.S_IMODE(os.stat(path).st_mode):o}:{user}:{group}")
PY
    return 0
  fi
  command stat "$@"
}

remote_final_v125_preflight "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
  "hookah-v125:${V125_SOURCE_SHA}" "${fixture_database_file}" "${uploaded}" \
  "${script_sha}" "${database_sha}"
SH
}

run_real_maintenance_secret_fixture() {
  local staging="$1"
  local run_id="$2"
  local identities_file="$3"
  local expected_identities_sha="$4"
  local mutation_log="$5"
  local mutation_mode="${6:-none}"
  /bin/bash -s -- "${CUTOVER_SCRIPT}" "${staging}" "${run_id}" "${RELEASE_SHA}" \
    "${identities_file}" "${expected_identities_sha}" "${mutation_log}" \
    "${mutation_mode}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_staging="$2"
fixture_run_id="$3"
fixture_release="$4"
fixture_identities_file="$5"
fixture_identities_sha="$6"
fixture_mutation_log="$7"
fixture_mutation_mode="$8"
fixture_run_root="${fixture_staging}/.v126-runs/${fixture_run_id}"
fixture_env_sha="$(remote_hash_file "${fixture_staging}/.env")"
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="${fixture_env_sha}"
if [[ "${fixture_mutation_mode}" == current-env-drift-before-install ]]; then
  export HT12P_MAINTENANCE_GUARD_MUTATE=true
  export HT12P_MAINTENANCE_LIVE_ENV="${fixture_staging}/.env"
  export HT12P_MAINTENANCE_GUARD_MARKER="${fixture_run_root}/guard-mutated.marker"
fi

remote_initialize_compose() {
  [[ "$1" == "${fixture_staging}" && "$2" == "${fixture_run_id}" &&
    "$3" == "${fixture_release}" && "${6:-}" == "${fixture_identities_sha}" ]] ||
    die 'maintenance authority binding mismatch'
  REMOTE_BOUND_ENV_SHA256="${fixture_env_sha}"
  printf '%s\n' authority-verified >> "${fixture_mutation_log}"
  cd "${fixture_staging}"
}
remote_require_run_root() {
  if [[ "${fixture_mutation_mode}" == identity-swap-at-run-root ]]; then
    printf '%s\n' \
      'STAGING_MAINTENANCE_ALLOWED_USER_IDS=111111111' \
      'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-222222222' > "${fixture_identities_file}"
    chmod 0600 "${fixture_identities_file}"
  fi
  printf '%s\n' MUTATION-run-root >> "${fixture_mutation_log}"
  printf '%s\n' "${fixture_run_root}"
}
remote_assert_caddy_drain_marker() {
  if [[ "${fixture_mutation_mode}" == environment-swap-at-caddy ]]; then
    printf '%s\n' 'UNRELATED_SAME_READ_DRIFT=1' >> "${fixture_staging}/.env"
  fi
  printf '%s\n' MUTATION-caddy >> "${fixture_mutation_log}"
}
remote_assert_public_drain() { :; }
remote_compose() {
  [[ "$*" == 'ps --status running -q backend' ]] || return 97
}
remote_verify_proof() { :; }
remote_assert_zero_writer() { [[ "$1" == 125:0:0 ]]; }
cp() {
  if [[ "${1:-}" == --preserve=mode,ownership,timestamps ]]; then
    shift
  fi
  command cp "$@"
  if [[ "${fixture_mutation_mode}" == identity-swap-at-run-root &&
    "${2:-}" == "${fixture_run_root}/env.before-V126_SMOKE" ]]; then
    printf '%s\n' IDENTITY-BEFORE-CREATED >> "${fixture_mutation_log}"
  fi
}
rm() {
  if [[ "${fixture_mutation_mode}" == identity-swap-at-run-root &&
    "$*" == "-f -- ${fixture_run_root}/env.V126_SMOKE.candidate ${fixture_run_root}/env.before-V126_SMOKE ${fixture_run_root}/.env.next" ]]; then
    printf '%s\n' IDENTITY-CLEANUP-EXACT >> "${fixture_mutation_log}"
  fi
  command rm "$@"
}
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    python3 - "$3" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys
path, user, group = sys.argv[1:]
print(f"{stat.S_IMODE(os.stat(path).st_mode):o}:{user}:{group}")
PY
    return 0
  fi
  command stat "$@"
}

remote_transform_maintenance_config "${fixture_staging}" "${fixture_run_id}" \
  "${fixture_release}" "hookah-v125:${V125_SOURCE_SHA}" "${fixture_identities_file}" \
  V126_SMOKE "${fixture_identities_sha}"
SH
}

assert_sensitive_value_locations() {
  local root="$1"
  local allowed_csv="$2"
  shift 2
  python3 - "${root}" "${allowed_csv}" "$@" <<'PY'
import os
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
allowed = {str(pathlib.Path(item)) for item in sys.argv[2].split(",") if item}
needles = [item.encode() for item in sys.argv[3:]]
hits = set()
for directory, _, files in os.walk(root):
    for name in files:
        path = pathlib.Path(directory) / name
        try:
            raw = path.read_bytes()
        except OSError:
            continue
        if any(needle in raw for needle in needles):
            hits.add(str(path))
if hits != allowed:
    raise SystemExit(f"sensitive fixture value locations mismatch: expected={sorted(allowed)!r} actual={sorted(hits)!r}")
PY
}

test_sensitive_consumer_secret_redaction() {
  local preflight_staging="${TEST_ROOT}/real-preflight-secret-staging"
  local preflight_run_id='preflight-secret-run'
  local preflight_run_root="${preflight_staging}/.v126-runs/${preflight_run_id}"
  local database_file="${TEST_ROOT}/real-preflight-database-url"
  local fake_bin="${TEST_ROOT}/real-preflight-fake-bin"
  local leaf_log="${TEST_ROOT}/real-preflight-leaf.log"
  mkdir -m 0700 -p "${preflight_run_root}" "${fake_bin}"
  printf 'postgresql://operator:%s_DB_PASSWORD@db.invalid:5432/exact?sslmode=require\n' \
    "${SECRET_CANARY}" > "${database_file}"
  chmod 0600 "${database_file}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -Eeuo pipefail' \
    '[[ "${DATABASE_URL:?}" == service=v126_preflight ]]' \
    '[[ -f "${PGSERVICEFILE:?}" && -f "${PGPASSFILE:?}" ]]' \
    'printf "%s\n" preflight-leaf-pass' > \
    "${preflight_run_root}/final-v125-preflight.sh.partial"
  chmod 0600 "${preflight_run_root}/final-v125-preflight.sh.partial"
  printf '%s\n' \
    '#!/bin/bash' \
    'set -Eeuo pipefail' \
    "printf '%s\\n' invoked >> '${leaf_log}'" \
    "grep -F '${SECRET_CANARY}_DB_PASSWORD' \"\${PGPASSFILE:?}\" >/dev/null" \
    'grep -F "passfile=" "${PGSERVICEFILE:?}" >/dev/null' \
    "printf '%s\\n' validated >> '${leaf_log}'" \
    'exec /bin/bash "$@"' > "${fake_bin}/bash"
  chmod 0700 "${fake_bin}/bash"

  expect_success 'real final-V125 preflight parses a secret URI without credential egress' \
    run_real_final_preflight_secret_fixture "${preflight_staging}" "${preflight_run_id}" \
    "${database_file}" "${fake_bin}"
  [[ -s "${leaf_log}" ]] || fail 'real final-V125 preflight did not exercise its generated pgpass/service binding'
  [[ ! -e "${preflight_run_root}/final-v125-preflight.pgpass" &&
    ! -e "${preflight_run_root}/final-v125-preflight.pg_service.conf" &&
    ! -e "${preflight_run_root}/final-v125-preflight.output" ]] ||
    fail 'real final-V125 preflight retained transient credential/output files'
  assert_sensitive_value_locations "${TEST_ROOT}" \
    "${database_file},${fake_bin}/bash" "${SECRET_CANARY}_DB_PASSWORD"
  assert_tree_has_no_canary "${preflight_run_root}"

  local preflight_swap_run_id='preflight-database-swap-run'
  local preflight_swap_run_root="${preflight_staging}/.v126-runs/${preflight_swap_run_id}"
  mkdir -m 0700 "${preflight_swap_run_root}"
  printf 'postgresql://operator:%s_DB_PASSWORD@db.invalid:5432/exact?sslmode=require\n' \
    "${SECRET_CANARY}" > "${database_file}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -Eeuo pipefail' \
    'printf "%s\n" FORBIDDEN_CLIENT_LAUNCH >> "${HT12P_PREFLIGHT_SWAP_CLIENT_LOG:?}"' > \
    "${preflight_swap_run_root}/final-v125-preflight.sh.partial"
  chmod 0600 "${database_file}" \
    "${preflight_swap_run_root}/final-v125-preflight.sh.partial"
  local leaf_lines_before_swap
  leaf_lines_before_swap="$(wc -l < "${leaf_log}" | tr -d ' ')"
  expect_failure 'database URL swap between outer validation and parser derivation rejects before client' \
    'database URL bytes differ from immutable authority at derivation' \
    run_real_final_preflight_secret_fixture "${preflight_staging}" \
    "${preflight_swap_run_id}" "${database_file}" "${fake_bin}" database-swap-at-proof
  [[ "$(wc -l < "${leaf_log}" | tr -d ' ')" == "${leaf_lines_before_swap}" ]] ||
    fail 'database URL same-read swap launched the preflight/database client'
  [[ ! -e "${preflight_swap_run_root}/final-v125-preflight.proof" &&
    ! -e "${preflight_swap_run_root}/final-v125-preflight.pgpass" &&
    ! -e "${preflight_swap_run_root}/final-v125-preflight.pg_service.conf" ]] ||
    fail 'database URL same-read swap retained credentials or sealed a proof'
  ! grep -F "${SECRET_CANARY}_DB_PASSWORD" "${LAST_OUTPUT}" >/dev/null ||
    fail 'database URL same-read rejection leaked its credential sentinel'

  local preflight_guard_run_id='preflight-credential-guard-run'
  local preflight_guard_root="${preflight_staging}/.v126-runs/${preflight_guard_run_id}"
  mkdir -m 0700 "${preflight_guard_root}"
  printf 'postgresql://operator:%s_DB_PASSWORD@db.invalid:5432/exact?sslmode=require\n' \
    "${SECRET_CANARY}" > "${database_file}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -Eeuo pipefail' \
    'printf "%s\n" FORBIDDEN_CLIENT_LAUNCH' > \
    "${preflight_guard_root}/final-v125-preflight.sh.partial"
  chmod 0600 "${database_file}" \
    "${preflight_guard_root}/final-v125-preflight.sh.partial"
  leaf_lines_before_swap="$(wc -l < "${leaf_log}" | tr -d ' ')"
  expect_failure 'preflight credential guard failure cleans both restricted files exactly once' '' \
    run_real_final_preflight_secret_fixture "${preflight_staging}" \
    "${preflight_guard_run_id}" "${database_file}" "${fake_bin}" credential-guard-failure
  [[ "$(wc -l < "${leaf_log}" | tr -d ' ')" == "${leaf_lines_before_swap}" ]] ||
    fail 'preflight credential guard failure launched the client'
  [[ "$(cat "${preflight_guard_root}/preflight-cleanup.log")" == \
    $'CREDENTIALS-CREATED\nPREFLIGHT-CLEANUP-EXACT' ]] ||
    fail 'preflight restricted credentials were not cleaned exactly once'
  [[ ! -e "${preflight_guard_root}/final-v125-preflight.pgpass" &&
    ! -e "${preflight_guard_root}/final-v125-preflight.pg_service.conf" &&
    ! -e "${preflight_guard_root}/final-v125-preflight.output" &&
    ! -e "${preflight_guard_root}/final-v125-preflight.proof" ]] ||
    fail 'preflight credential guard failure retained credentials/output or sealed a proof'

  local preflight_script_swap_run_id='preflight-script-swap-run'
  local preflight_script_swap_root="${preflight_staging}/.v126-runs/${preflight_script_swap_run_id}"
  local preflight_alternate_marker="${TEST_ROOT}/preflight-alternate-payload.executed"
  mkdir -m 0700 "${preflight_script_swap_root}"
  printf 'postgresql://operator:%s_DB_PASSWORD@db.invalid:5432/exact?sslmode=require\n' \
    "${SECRET_CANARY}" > "${database_file}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -Eeuo pipefail' \
    'printf "%s\n" SAFE_ORIGINAL_PREFLIGHT' > \
    "${preflight_script_swap_root}/final-v125-preflight.sh.partial"
  chmod 0600 "${database_file}" \
    "${preflight_script_swap_root}/final-v125-preflight.sh.partial"
  leaf_lines_before_swap="$(wc -l < "${leaf_log}" | tr -d ' ')"
  expect_failure 'sealed preflight swap after outer hash rejects before altered bytes execute' \
    'final V125 booking-integrity preflight failed; restricted output retained' \
    run_real_final_preflight_secret_fixture "${preflight_staging}" \
    "${preflight_script_swap_run_id}" "${database_file}" "${fake_bin}" \
    script-swap-after-hash "${preflight_alternate_marker}"
  [[ ! -e "${preflight_alternate_marker}" ]] ||
    fail 'post-hash substituted preflight bytes reached bash'
  [[ "$(wc -l < "${leaf_log}" | tr -d ' ')" == "${leaf_lines_before_swap}" ]] ||
    fail 'post-hash preflight substitution launched the bash consumer'
  [[ ! -e "${preflight_script_swap_root}/final-v125-preflight.proof" &&
    ! -e "${preflight_script_swap_root}/final-v125-preflight.pgpass" &&
    ! -e "${preflight_script_swap_root}/final-v125-preflight.pg_service.conf" &&
    ! -e "${preflight_script_swap_root}/final-v125-preflight.output" ]] ||
    fail 'post-hash preflight substitution retained credentials or sealed a proof'
  rm -f -- "${database_file}" "${fake_bin}/bash"

  local maintenance_staging="${TEST_ROOT}/real-maintenance-secret-staging"
  local maintenance_run_id='maintenance-secret-run'
  local maintenance_run_root="${maintenance_staging}/.v126-runs/${maintenance_run_id}"
  local identities_file="${TEST_ROOT}/real-maintenance-identities"
  local alternate_identities="${TEST_ROOT}/real-maintenance-identities-alternate"
  local mutation_log="${TEST_ROOT}/real-maintenance-mutation.log"
  local user_canary='812345670987654321'
  local chat_canary='-812345670987654322'
  mkdir -m 0700 -p "${maintenance_run_root}" "${maintenance_staging}/scripts"
  printf '%s\n' \
    'STAGING_MAINTENANCE_MODE=OFF' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' \
    'UNRELATED_SETTING=preserved' > "${maintenance_staging}/.env"
  printf 'STAGING_MAINTENANCE_ALLOWED_USER_IDS=%s\nSTAGING_MAINTENANCE_ALLOWED_CHAT_IDS=%s\n' \
    "${user_canary}" "${chat_canary}" > "${identities_file}"
  printf '%s\n' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=111111111' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-222222222' > "${alternate_identities}"
  printf '%s\n' 'services: {}' > "${maintenance_staging}/docker-compose.yml"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -Eeuo pipefail' \
    'if [[ "${HT12P_MAINTENANCE_GUARD_MUTATE:-false}" == true && "$1" == *candidate &&' \
    '  ! -e "${HT12P_MAINTENANCE_GUARD_MARKER:?}" ]]; then' \
    '  printf "%s\n" "UNRELATED_GUARD_TIME_DRIFT=1" >> "${HT12P_MAINTENANCE_LIVE_ENV:?}"' \
    '  : > "${HT12P_MAINTENANCE_GUARD_MARKER}"' \
    'fi' \
    'exit 0' > "${maintenance_staging}/scripts/check-staging-maintenance-config.sh"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${maintenance_staging}/scripts/validate-staging-admission.sh"
  chmod 0600 "${maintenance_staging}/.env" "${identities_file}" "${alternate_identities}"
  chmod 0644 "${maintenance_staging}/docker-compose.yml"
  chmod 0755 "${maintenance_staging}/scripts/check-staging-maintenance-config.sh" \
    "${maintenance_staging}/scripts/validate-staging-admission.sh"
  local identities_sha
  identities_sha="$(sha256_file "${identities_file}")"

  expect_success 'real maintenance transformer places numeric identities only in restricted .env' \
    run_real_maintenance_secret_fixture "${maintenance_staging}" "${maintenance_run_id}" \
    "${identities_file}" "${identities_sha}" "${mutation_log}"
  grep -Fx "STAGING_MAINTENANCE_ALLOWED_USER_IDS=${user_canary}" \
    "${maintenance_staging}/.env" >/dev/null || fail 'maintenance user identity was not installed exactly'
  grep -Fx "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=${chat_canary}" \
    "${maintenance_staging}/.env" >/dev/null || fail 'maintenance chat identity was not installed exactly'
  local maintenance_env_mode
  maintenance_env_mode="$(python3 - "${maintenance_staging}/.env" <<'PY'
import os
import stat
import sys

print(f"{stat.S_IMODE(os.stat(sys.argv[1]).st_mode):o}")
PY
)"
  [[ "${maintenance_env_mode}" == 600 ]] ||
    fail 'maintenance .env is not restricted mode 0600'
  assert_sensitive_value_locations "${TEST_ROOT}" \
    "${maintenance_staging}/.env,${identities_file}" "${user_canary}" "${chat_canary}"
  ! grep -E "${user_canary}|${chat_canary}" "${LAST_OUTPUT}" >/dev/null ||
    fail 'maintenance identity sentinel reached command output'
  [[ ! -e "${maintenance_run_root}/env.V126_SMOKE.candidate" &&
    ! -e "${maintenance_run_root}/env.before-V126_SMOKE" &&
    ! -e "${maintenance_run_root}/.env.next" ]] ||
    fail 'maintenance transformer retained a temporary identity-bearing copy'

  local swapped_run_id='maintenance-swapped-run'
  mkdir -m 0700 -p "${maintenance_staging}/.v126-runs/${swapped_run_id}"
  printf '%s\n' \
    'STAGING_MAINTENANCE_MODE=OFF' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' > "${maintenance_staging}/.env"
  chmod 0600 "${maintenance_staging}/.env"
  : > "${mutation_log}"
  expect_failure 'swapped manifest identities argument rejects before maintenance mutation' \
    'maintenance identities argument does not match the streamed baseline receipt' \
    run_real_maintenance_secret_fixture "${maintenance_staging}" "${swapped_run_id}" \
    "${alternate_identities}" "${identities_sha}" "${mutation_log}"
  [[ "$(cat "${mutation_log}")" == authority-verified ]] ||
    fail 'swapped identities argument crossed the post-authority mutation boundary'
  [[ ! -e "${maintenance_staging}/.v126-runs/${swapped_run_id}/env.V126_SMOKE.candidate" ]] ||
    fail 'swapped identities argument created a maintenance candidate'
  ! grep -E '111111111|-222222222' "${LAST_OUTPUT}" >/dev/null ||
    fail 'swapped identities values leaked through failure output'

  local same_read_mode same_read_pattern same_read_run_id same_read_run_root
  while IFS='|' read -r same_read_mode same_read_pattern; do
    same_read_run_id="maintenance-${same_read_mode}"
    same_read_run_root="${maintenance_staging}/.v126-runs/${same_read_run_id}"
    mkdir -m 0700 -p "${same_read_run_root}"
    printf '%s\n' \
      'STAGING_MAINTENANCE_MODE=OFF' \
      'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' \
      'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' \
      'UNRELATED_SETTING=preserved' > "${maintenance_staging}/.env"
    printf 'STAGING_MAINTENANCE_ALLOWED_USER_IDS=%s\nSTAGING_MAINTENANCE_ALLOWED_CHAT_IDS=%s\n' \
      "${user_canary}" "${chat_canary}" > "${identities_file}"
    chmod 0600 "${maintenance_staging}/.env" "${identities_file}"
    : > "${mutation_log}"
    expect_failure "maintenance ${same_read_mode} rejects before install or receipt" \
      "${same_read_pattern}" \
      run_real_maintenance_secret_fixture "${maintenance_staging}" "${same_read_run_id}" \
      "${identities_file}" "${identities_sha}" "${mutation_log}" "${same_read_mode}"
    [[ ! -e "${same_read_run_root}/maintenance-v126_smoke.proof" &&
      ! -e "${same_read_run_root}/env.V126_SMOKE.candidate" &&
      ! -e "${same_read_run_root}/env.before-V126_SMOKE" &&
      ! -e "${same_read_run_root}/.env.next" ]] ||
      fail "maintenance ${same_read_mode} retained transform state or sealed a receipt"
    if [[ "${same_read_mode}" == identity-swap-at-run-root ]]; then
      [[ "$(cat "${mutation_log}")" == $'authority-verified\nMUTATION-run-root\nMUTATION-caddy\nIDENTITY-BEFORE-CREATED\nIDENTITY-CLEANUP-EXACT' ]] ||
        fail 'maintenance identity-swap did not create then exactly clean all transform paths'
    fi
    ! grep -F $'ARTIFACT\t' "${LAST_OUTPUT}" >/dev/null ||
      fail "maintenance ${same_read_mode} emitted a receipt artifact"
    ! grep -E "${user_canary}|${chat_canary}" "${maintenance_staging}/.env" >/dev/null ||
      fail "maintenance ${same_read_mode} installed swapped identities"
  done <<'EOF'
identity-swap-at-run-root|maintenance identity bytes differ from immutable authority at derivation
environment-swap-at-caddy|maintenance source bytes differ from immutable authority at derivation
current-env-drift-before-install|staging environment changed during maintenance transformation
EOF

  rm -f -- "${maintenance_staging}/.env" "${identities_file}" "${alternate_identities}"
  pass 'real sensitive consumers redact DB credentials and maintenance identities on success and failure'
}

extract_real_booking_preflight() {
  local target="$1"
  local release_worktree="${2:-${REPO_ROOT}}"
  local release_sha="${3:-${RELEASE_SHA}}"
  bash -s -- "${CUTOVER_SCRIPT}" "${release_worktree}" "${target}" \
    "${release_sha}" <<'SH'
set -Eeuo pipefail
source "$1"
RELEASE_WORKTREE="$2"
fixture_target="$3"
RELEASE_SHA="$4"
extract_booking_preflight "${fixture_target}" >/dev/null
SH
}

prepare_preflight_release_fixture() {
  local fixture_repo="${TEST_ROOT}/release-worktree"
  if [[ ! -d "${fixture_repo}/.git" ]]; then
    git -C "${fixture_repo}" init -q
    git -C "${fixture_repo}" config user.name 'V126 Fixture'
    git -C "${fixture_repo}" config user.email 'v126-fixture.invalid@example.invalid'
    mkdir -m 0700 -p "${fixture_repo}/docs"
    cp "${REPO_ROOT}/docs/DEPLOYMENT_RUNBOOK.md" \
      "${fixture_repo}/docs/DEPLOYMENT_RUNBOOK.md"
    chmod 0600 "${fixture_repo}/docs/DEPLOYMENT_RUNBOOK.md"
    git -C "${fixture_repo}" add docs/DEPLOYMENT_RUNBOOK.md
    git -C "${fixture_repo}" commit -qm 'canonical booking preflight fixture'
  fi
  FIXTURE_INIT_RELEASE_SHA="$(git -C "${fixture_repo}" rev-parse HEAD)"
  FIXTURE_INIT_RELEASE_TREE="$(git -C "${fixture_repo}" rev-parse HEAD^{tree})"
  [[ "${FIXTURE_INIT_RELEASE_SHA}" =~ ^[0-9a-f]{40}$ &&
    "${FIXTURE_INIT_RELEASE_TREE}" =~ ^[0-9a-f]{40}$ ]] ||
    fail 'isolated immutable preflight release fixture is invalid'
}

run_release_git_sanitization_fixture() {
  local fixture_repo="$1"
  local original_sha="$2"
  local original_tree="$3"
  local original_doc_sha="$4"
  local target="$5"
  env \
    GIT_DIR=/HT12P_FORBIDDEN_GIT_DIR \
    GIT_WORK_TREE=/HT12P_FORBIDDEN_GIT_WORK_TREE \
    GIT_COMMON_DIR=/HT12P_FORBIDDEN_GIT_COMMON_DIR \
    GIT_INDEX_FILE=/HT12P_FORBIDDEN_GIT_INDEX \
    GIT_OBJECT_DIRECTORY=/HT12P_FORBIDDEN_GIT_OBJECTS \
    GIT_ALTERNATE_OBJECT_DIRECTORIES=/HT12P_FORBIDDEN_GIT_ALTERNATES \
    GIT_CEILING_DIRECTORIES=/ \
    GIT_NAMESPACE=HT12P_FORBIDDEN_NAMESPACE \
    GIT_CONFIG_GLOBAL=/HT12P_FORBIDDEN_GLOBAL_CONFIG \
    GIT_CONFIG_SYSTEM=/HT12P_FORBIDDEN_SYSTEM_CONFIG \
    GIT_CONFIG_NOSYSTEM=0 \
    GIT_CONFIG_COUNT=1 \
    GIT_CONFIG_KEY_0=core.fsmonitor \
    GIT_CONFIG_VALUE_0=/HT12P_FORBIDDEN_FSMONITOR \
    GIT_NO_REPLACE_OBJECTS=0 \
    /bin/bash -s -- "${CUTOVER_SCRIPT}" "${fixture_repo}" "${original_sha}" \
      "${original_tree}" "${original_doc_sha}" "${target}" <<'SH'
set -Eeuo pipefail
source "$1"
RELEASE_WORKTREE="$2"
RELEASE_SHA="$3"
expected_tree="$4"
expected_doc_sha="$5"
target="$6"
[[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify "${RELEASE_SHA}^{tree}")" == \
  "${expected_tree}" ]] || die 'sanitized release Git tree identity was spoofed'
[[ "$(git_object_sha256 "${RELEASE_WORKTREE}" \
  "${RELEASE_SHA}:docs/DEPLOYMENT_RUNBOOK.md")" == "${expected_doc_sha}" ]] ||
  die 'sanitized release Git blob identity was spoofed'
extract_booking_preflight "${target}" >/dev/null
SH
}

test_release_git_sanitization() {
  local fixture_repo="${TEST_ROOT}/release-git-sanitized"
  local expected_preflight="${TEST_ROOT}/release-git-expected-preflight.sh"
  local hostile_preflight="${TEST_ROOT}/release-git-hostile-preflight.sh"
  local sentinel='HT12P_GIT_REPLACE_PREFLIGHT_MUST_NOT_EXECUTE'
  local original_sha original_tree original_doc_sha replacement_sha
  local release_git_source="${TEST_ROOT}/function-release-git.sh"
  local baseline_source="${TEST_ROOT}/function-release-baseline-local.sh"
  mkdir -m 0700 -p "${fixture_repo}/docs"
  git -C "${fixture_repo}" init -q
  git -C "${fixture_repo}" config user.name 'V126 Sanitized Git Fixture'
  git -C "${fixture_repo}" config user.email 'v126-git-fixture.invalid@example.invalid'
  cp "${REPO_ROOT}/docs/DEPLOYMENT_RUNBOOK.md" \
    "${fixture_repo}/docs/DEPLOYMENT_RUNBOOK.md"
  chmod 0600 "${fixture_repo}/docs/DEPLOYMENT_RUNBOOK.md"
  git -C "${fixture_repo}" add docs/DEPLOYMENT_RUNBOOK.md
  git -C "${fixture_repo}" commit -qm 'canonical immutable release object'
  original_sha="$(git -C "${fixture_repo}" rev-parse HEAD)"
  original_tree="$(git -C "${fixture_repo}" rev-parse HEAD^{tree})"
  original_doc_sha="$(sha256_file "${fixture_repo}/docs/DEPLOYMENT_RUNBOOK.md")"
  extract_real_booking_preflight "${expected_preflight}" "${fixture_repo}" "${original_sha}"

  python3 - "${fixture_repo}/docs/DEPLOYMENT_RUNBOOK.md" "${sentinel}" <<'PY'
import sys

path, sentinel = sys.argv[1:]
payload = open(path, "rb").read()
begin = b"<!-- BOOKING_UNREAD_PREFLIGHT_BEGIN -->"
end = b"<!-- BOOKING_UNREAD_PREFLIGHT_END -->"
begin_at = payload.index(begin) + len(begin)
end_at = payload.index(end)
marked = payload[begin_at:end_at]
needle = b"SELECT "
if needle not in marked:
    raise SystemExit("replace-ref fixture lacks a marker SQL target")
marked = marked.replace(
    needle,
    b"SELECT '" + sentinel.encode("ascii") + b"' AS replace_ref_canary;\nSELECT ",
    1,
)
with open(path, "wb") as handle:
    handle.write(payload[:begin_at] + marked + payload[end_at:])
PY
  git -C "${fixture_repo}" add docs/DEPLOYMENT_RUNBOOK.md
  git -C "${fixture_repo}" commit -qm 'hostile replacement object'
  replacement_sha="$(git -C "${fixture_repo}" rev-parse HEAD)"
  git -C "${fixture_repo}" replace "${original_sha}" "${replacement_sha}"
  [[ "$(git -C "${fixture_repo}" replace -l)" == "${original_sha}" ]] ||
    fail 'replace-ref adversarial control was not installed'

  expect_success 'release Git ignores replace refs and all inherited Git authority variables' \
    run_release_git_sanitization_fixture "${fixture_repo}" "${original_sha}" \
    "${original_tree}" "${original_doc_sha}" "${hostile_preflight}"
  cmp -s "${expected_preflight}" "${hostile_preflight}" ||
    fail 'hostile Git environment or replace ref spoofed the immutable preflight bytes'
  ! grep -F "${sentinel}" "${hostile_preflight}" >/dev/null ||
    fail 'replace-ref SQL reached the extracted preflight artifact'

  python3 - "${CUTOVER_SCRIPT}" "${release_git_source}" <<'PY'
import sys

source, target = sys.argv[1:]
text = open(source, "rt", encoding="utf-8").read()
start = text.index("release_git() (\n")
end = text.index("\n)\n\ngit_object_sha256() {", start) + 3
with open(target, "wt", encoding="utf-8") as handle:
    handle.write(text[start:end])
PY
  assert_literals_in_order "${release_git_source}" 'sanitized release Git invocation' \
    'while IFS=' 'GIT_*) unset "${variable}"' 'export GIT_NO_REPLACE_OBJECTS=1' \
    '-c core.fsmonitor=false' '-c core.untrackedCache=false' \
    '-c core.hooksPath=/dev/null' '-C "${worktree}"'
  extract_function_source "${CUTOVER_SCRIPT}" verify_release_baseline_local "${baseline_source}"
  ! grep -E '(^|[$(;|&])[[:space:]]*(command[[:space:]]+)?git[[:space:]]' \
    "${baseline_source}" >/dev/null ||
    fail 'release baseline bypasses the sanitized release_git wrapper'
  [[ "$(grep -F -c 'release_git ' "${baseline_source}" || true)" -ge 8 ]] ||
    fail 'release baseline does not consistently route Git object checks through release_git'
  pass 'release baseline, object hashing, and preflight extraction ignore inherited Git and replace authority'
}

test_database_target_binding() {
  local preflight="${TEST_ROOT}/booking-preflight.sh"
  local fake_bin="${TEST_ROOT}/database-fake-bin"
  local client_log="${TEST_ROOT}/database-client.log"
  prepare_preflight_release_fixture
  extract_real_booking_preflight "${preflight}" "${TEST_ROOT}/release-worktree" \
    "${FIXTURE_INIT_RELEASE_SHA}"
  chmod 0700 "${preflight}"
  assert_literals_in_order "${preflight}" 'booking preflight database binding' \
    ': "${DATABASE_URL:?DATABASE_URL must bind the exact target}"' \
    'psql "${DATABASE_URL}" -X --set=ON_ERROR_STOP=1'
  mkdir -m 0700 "${fake_bin}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "psql %s\\n" "$*" >> "${HT12P_DATABASE_CLIENT_LOG:?}"' \
    'exit 96' > "${fake_bin}/psql"
  chmod 0700 "${fake_bin}/psql"
  export HT12P_DATABASE_CLIENT_LOG="${client_log}"
  expect_failure 'missing DATABASE_URL fails before database client launch' \
    'DATABASE_URL must bind the exact target' \
    env -u DATABASE_URL PATH="${fake_bin}:${PATH}" bash "${preflight}"
  unset HT12P_DATABASE_CLIENT_LOG
  [[ ! -s "${client_log}" ]] || fail 'psql started without an exact database target'
  expect_success 'every sequencer database client has an exact target or bounded list-only mode' \
    validate_database_client_targets "${CUTOVER_SCRIPT}"
  local bad_client="${TEST_ROOT}/database-client-without-target.sh"
  local client_name client_line
  while IFS='|' read -r client_name client_line; do
    printf '#!/usr/bin/env bash\n%s\n' "${client_line}" > "${bad_client}"
    expect_failure "database-client scanner rejects untargeted ${client_name}" \
      'lacks|neither exact-target' validate_database_client_targets "${bad_client}"
  done <<'EOF'
psql|psql -X -Atqc 'SELECT 1'
pg_dump|pg_dump -Fc > backup.dump
pg_dumpall|pg_dumpall --globals-only > globals.sql
pg_restore|pg_restore backup.dump
createdb|createdb restored_database
dropdb|dropdb restored_database
pg_isready|pg_isready -U postgres
EOF
  printf '%s\n' \
    "psql -X -Atqc 'SELECT 1'; psql -d exact_database -X -Atqc 'SELECT 2'" > "${bad_client}"
  expect_failure 'database scanner does not borrow a later command target' \
    'psql lacks an explicit database target' validate_database_client_targets "${bad_client}"
  printf '%s\n' \
    'psql -X -d "$POSTGRES_DB" -Atqc '\''SELECT 1'\''' > "${bad_client}"
  expect_failure 'database scanner requires variable targets to fail closed' \
    'target is not fail-closed' validate_database_client_targets "${bad_client}"
  pass 'database target is required before any client process starts'
}

test_real_stage_eight_artifact_collection() {
  local hybrid="${TEST_ROOT}/v126-cutover-real-stage-eight.sh"
  local fake_bin="${TEST_ROOT}/stage-eight-fake-bin"
  local old_path="${PATH}"
  local state receipt operation_log expected_preflight
  local transferred_preflight="${TEST_ROOT}/stage-eight-transferred-preflight.sh"
  local remote_stream="${TEST_ROOT}/stage-eight-remote-stream.log"
  local mutable_sentinel='HT12P_MUTABLE_WORKTREE_PREFLIGHT_SQL_MUST_NOT_RUN'
  local remote_hash='8888888888888888888888888888888888888888888888888888888888888888'
  make_instrumented_script "${CUTOVER_SCRIPT}" "${hybrid}" FINAL_V125_PREFLIGHT_PASSED
  prepare_preflight_release_fixture
  mkdir -m 0700 "${fake_bin}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'source_path=' \
    'for argument in "$@"; do' \
    '  if [[ -f "${argument}" ]]; then source_path="${argument}"; fi' \
    'done' \
    '[[ -n "${source_path}" ]]' \
    'cp "${source_path}" "${HT12P_RSYNC_SOURCE_CAPTURE:?}"' > "${fake_bin}/rsync"
  chmod 0700 "${fake_bin}/rsync"

  make_artifact_ssh "${fake_bin}/ssh" "final-v125-preflight=${remote_hash}"
  new_state "${hybrid}" real-stage-eight
  state="${NEW_STATE}"
  seed_chain "${state}" 7
  python3 - "${TEST_ROOT}/release-worktree/docs/DEPLOYMENT_RUNBOOK.md" \
    "${mutable_sentinel}" <<'PY'
import sys

path, sentinel = sys.argv[1:]
payload = open(path, "rb").read()
begin = b"<!-- BOOKING_UNREAD_PREFLIGHT_BEGIN -->"
end = b"<!-- BOOKING_UNREAD_PREFLIGHT_END -->"
begin_at = payload.index(begin) + len(begin)
end_at = payload.index(end)
marked = payload[begin_at:end_at]
needle = b"SELECT "
if needle not in marked:
    raise SystemExit("fixture marker lacks mutable SQL target")
marked = marked.replace(
    needle,
    b"SELECT '" + sentinel.encode("ascii") + b"' AS mutable_worktree_canary;\nSELECT ",
    1,
)
with open(path, "wb") as handle:
    handle.write(payload[:begin_at] + marked + payload[end_at:])
PY
  grep -F "${mutable_sentinel}" \
    "${TEST_ROOT}/release-worktree/docs/DEPLOYMENT_RUNBOOK.md" >/dev/null ||
    fail 'stage-8 mutable working-tree SQL sentinel was not installed after baseline'
  export HT12P_RSYNC_SOURCE_CAPTURE="${transferred_preflight}"
  export HT12P_REMOTE_STREAM_LOG="${remote_stream}"
  PATH="${fake_bin}:${old_path}"
  expect_success 'real state 8 collects and seals an exact artifact set' \
    invoke_script "${hybrid}" stage --state-dir "${state}" FINAL_V125_PREFLIGHT_PASSED
  PATH="${old_path}"
  receipt="${state}/receipts/08-FINAL_V125_PREFLIGHT_PASSED.receipt.json"
  operation_log="${state}/artifacts/8-FINAL_V125_PREFLIGHT_PASSED.operation.log"
  expected_preflight="${TEST_ROOT}/stage-eight-expected-preflight.sh"
  extract_real_booking_preflight "${expected_preflight}" \
    "${TEST_ROOT}/release-worktree" "${FIXTURE_INIT_RELEASE_SHA}"
  cmp -s "${expected_preflight}" "${transferred_preflight}" ||
    fail 'state-8 transferred preflight differs from the immutable release Git object'
  local inspected_path
  for inspected_path in "${transferred_preflight}" "${remote_stream}" "${operation_log}"; do
    ! grep -F "${mutable_sentinel}" "${inspected_path}" >/dev/null ||
      fail "mutable working-tree SQL reached the stage-8 transfer/remote surface: ${inspected_path}"
  done
  python3 - "${receipt}" "${operation_log}" "$(sha256_file "${expected_preflight}")" \
    "${remote_hash}" <<'PY'
import hashlib
import json
import sys

receipt_path, operation_path, expected_local, expected_remote = sys.argv[1:]
receipt = json.load(open(receipt_path, "rt", encoding="utf-8"))
artifacts = receipt["artifacts"]
names = [item["name"] for item in artifacts]
expected_names = ["final-v125-preflight", "final-v125-preflight-source", "operation-log"]
if names != expected_names or len(set(names)) != len(names):
    raise SystemExit(f"state-8 sealed artifact set mismatch: {names!r}")
by_name = {item["name"]: item["sha256"] for item in artifacts}
if by_name["final-v125-preflight-source"] != expected_local:
    raise SystemExit("state-8 local preflight source hash mismatch")
if by_name["final-v125-preflight"] != expected_remote:
    raise SystemExit("state-8 remote preflight proof hash mismatch")
operation = open(operation_path, "rb").read()
if by_name["operation-log"] != hashlib.sha256(operation).hexdigest():
    raise SystemExit("state-8 operation log is not sealed by the receipt")
lines = [line for line in operation.decode().splitlines() if line.startswith("ARTIFACT\t")]
if len(lines) != 2:
    raise SystemExit(f"state-8 operation log artifact count mismatch: {lines!r}")
PY
  pass 'real state 8 seals its local, remote, and operation-log artifacts'

  make_artifact_ssh "${fake_bin}/ssh" \
    "final-v125-preflight=${remote_hash}" "final-v125-preflight=${remote_hash}"
  new_state "${hybrid}" real-stage-eight-duplicate
  state="${NEW_STATE}"
  seed_chain "${state}" 7
  PATH="${fake_bin}:${old_path}"
  expect_failure 'real state 8 rejects duplicate artifact collection' \
    'duplicate artifact name|artifact.*duplicate' \
    invoke_script "${hybrid}" stage --state-dir "${state}" FINAL_V125_PREFLIGHT_PASSED
  PATH="${old_path}"
  [[ ! -e "${state}/receipts/08-FINAL_V125_PREFLIGHT_PASSED.receipt.json" ]] ||
    fail 'duplicate state-8 artifacts produced a receipt'
  [[ -f "${state}/artifacts/8-FINAL_V125_PREFLIGHT_PASSED.operation.log" ]] ||
    fail 'duplicate state-8 artifact refusal did not retain its immutable operation log'
  pass 'real state 8 duplicate artifact collection fails closed'

  local unsafe_base_target="${TEST_ROOT}/stage-eight-unsafe-base-preflight.sh"
  expect_failure 'development-base preflight object is rejected as non-executable authority' \
    'booking preflight lacks the exact fail-closed database binding' \
    extract_real_booking_preflight "${unsafe_base_target}" "${REPO_ROOT}" "${RELEASE_SHA}"
  [[ ! -e "${unsafe_base_target}" ]] ||
    fail 'unsafe development-base preflight object produced an executable artifact'

  local missing_object_repo="${TEST_ROOT}/stage-eight-missing-object-repo"
  local missing_object_target="${TEST_ROOT}/stage-eight-missing-object-preflight.sh"
  mkdir -m 0700 "${missing_object_repo}"
  expect_failure 'unavailable immutable preflight object rejects before transfer' \
    'immutable release preflight source is unavailable|missing or duplicate booking preflight markers|not a git repository' \
    extract_real_booking_preflight "${missing_object_target}" "${missing_object_repo}" \
    "${FIXTURE_INIT_RELEASE_SHA}"
  [[ ! -e "${missing_object_target}" ]] ||
    fail 'unavailable immutable preflight object produced an executable artifact'
  unset HT12P_RSYNC_SOURCE_CAPTURE HT12P_REMOTE_STREAM_LOG
  FIXTURE_INIT_RELEASE_SHA=''
  FIXTURE_INIT_RELEASE_TREE=''
  pass 'stage 8 extracts only immutable release-object SQL and rejects unavailable/unsafe objects'
}

write_manual_smoke_evidence() {
  local state="$1"
  local target="$2"
  local mutation="${3:-valid}"
  python3 - "${state}/run.json" "${target}" "${mutation}" "${SECRET_CANARY}" <<'PY'
import json
import os
import sys

manifest_path, target, mutation, secret_canary = sys.argv[1:]
manifest = json.load(open(manifest_path, "rt", encoding="utf-8"))
required = [
    "MATRIX_GUEST",
    "MATRIX_OWNER",
    "MATRIX_MIX",
    "MATRIX_MIX_STAFF_CHAT",
    "TENANT_RBAC_NEGATIVES",
    "LINKED_STAFF_CHAT_DELIVERY",
    "NULL_AUTHOR_UNREAD_CREATE_CLEAR_RESURRECT",
    "WRONG_SURFACE_MARKERS_UNCHANGED",
    "EXACT_SURFACE_ONLY_CLEAR",
    "SUPPORT_CONVERSATIONS_SEPARATION",
    "LABEL_COLLISION",
    "LIVE_ONE_GUEST_REPLY",
    "LIVE_ONE_PERSISTED_GUEST_MESSAGE",
    "LIVE_EXACTLY_ONE_TELEGRAM_OUTBOX_DELIVERY",
    "LIVE_OWNER_EXACT_THREAD_UNREAD_CREATED_AND_CLEARED",
    "LIVE_NO_DUPLICATE_OR_RESURRECTED_MARKER",
    "LIVE_NO_OTHER_THREAD_CLIENT_OR_NON_MIX_MUTATION",
]
assertions = {name: "PASS" for name in required}
doc = {
    "assertions": assertions,
    "format_version": 1,
    "release_sha": manifest["release_sha"],
    "result_category": "PASS",
    "run_id": manifest["run_id"],
}
if mutation.startswith("missing:"):
    assertions.pop(mutation.split(":", 1)[1])
elif mutation.startswith("failed:"):
    assertions[mutation.split(":", 1)[1]] = "FAIL"
elif mutation == "extra":
    assertions["UNREVIEWED_EXTRA"] = "PASS"
elif mutation == "wrong-result":
    doc["result_category"] = "PARTIAL"
elif mutation == "wrong-release":
    doc["release_sha"] = "0" * 40
elif mutation == "secret-extra":
    doc["jwt_like"] = secret_canary + "_JWT"
    doc["init_data_like"] = "query_id=x&hash=" + secret_canary + "_INIT_DATA"
elif mutation != "valid":
    raise SystemExit(f"unknown manual evidence fixture mutation: {mutation}")
payload = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
}

make_success_ssh() {
  local target="$1"
  local artifact_name="$2"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "ssh %s\\n" "$*" >> "${HT12P_REMOTE_LOG:?}"' \
    'if [[ -n "${HT12P_REMOTE_STREAM_LOG:-}" ]]; then' \
    '  cat >> "${HT12P_REMOTE_STREAM_LOG}"' \
    'else' \
    '  while IFS= read -r _line; do :; done' \
    'fi' \
    "printf 'ARTIFACT\\t${artifact_name}\\t%s\\n' '4444444444444444444444444444444444444444444444444444444444444444'" \
    > "${target}"
  chmod 0700 "${target}"
}

make_artifact_ssh() {
  local target="$1"
  shift
  {
    printf '%s\n' \
      '#!/usr/bin/env bash' \
      'if [[ -n "${HT12P_REMOTE_STREAM_LOG:-}" ]]; then' \
      '  cat > "${HT12P_REMOTE_STREAM_LOG}"' \
      'else' \
      '  while IFS= read -r _line; do :; done' \
      'fi'
    local item name digest
    for item in "$@"; do
      name="${item%%=*}"
      digest="${item#*=}"
      [[ "${name}" != "${item}" && "${digest}" =~ ^[0-9a-f]{64}$ ]] ||
        fail "invalid fake SSH artifact fixture: ${item}"
      printf "printf 'ARTIFACT\\t%%s\\t%%s\\n' '%s' '%s'\n" \
        "${name}" "${digest}"
    done
  } > "${target}"
  chmod 0700 "${target}"
}

make_state_lock() {
  local state="$1"
  local owner_pid="$2"
  mkdir -m 0700 "${state}/.exclusive-lock"
  mkdir -m 0700 "${state}/.exclusive-lock/children"
  printf '%s\n' "${owner_pid}" > "${state}/.exclusive-lock/pid"
  chmod 0400 "${state}/.exclusive-lock/pid"
}

log_line_count() {
  local path="$1"
  if [[ -f "${path}" ]]; then
    wc -l < "${path}" | tr -d ' '
  else
    printf '0\n'
  fi
}

write_dr_boundary() {
  local state="$1"
  local target="$2"
  local phase="$3"
  python3 - "${state}/run.json" "${target}" "${phase}" <<'PY'
import json
import os
import sys

manifest_path, target, phase = sys.argv[1:]
manifest = json.load(open(manifest_path, "rt", encoding="utf-8"))
boundaries = {
    "pre-drain": "ALL_WRITES_AFTER_PRE_DRAIN_BACKUP_MAY_BE_LOST",
    "quiesced": "ALL_WRITES_AFTER_QUIESCED_BACKUP_MAY_BE_LOST",
}
document = {
    "accepted_data_loss_boundary": boundaries[phase],
    "accepted_recovery_point_utc": "2026-09-01T00:00:00Z",
    "backup_phase": phase,
    "format_version": 1,
    "release_sha": manifest["release_sha"],
    "result_category": "DR_PREREQUISITES_ACCEPTED",
    "run_id": manifest["run_id"],
}
payload = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
}

receipt_artifact_from_state() {
  local state="$1"
  local receipt="$2"
  local name="$3"
  python3 - "${state}/receipts/${receipt}" "${name}" <<'PY'
import json
import sys

path, name = sys.argv[1:]
document = json.load(open(path, "rt", encoding="utf-8"))
matches = [item["sha256"] for item in document["artifacts"] if item["name"] == name]
if len(matches) != 1:
    raise SystemExit(f"fixture receipt artifact mismatch: {name}")
print(matches[0])
PY
}

recovery_receipt_artifact_from_state() {
  local state="$1"
  local receipt="$2"
  local name="$3"
  python3 - "${state}/recovery/${receipt}" "${name}" <<'PY'
import json
import sys

path, name = sys.argv[1:]
document = json.load(open(path, "rt", encoding="utf-8"))
matches = [item["sha256"] for item in document["artifacts"] if item["name"] == name]
if len(matches) != 1:
    raise SystemExit(f"fixture recovery receipt artifact mismatch: {name}")
print(matches[0])
PY
}

test_manual_smoke_evidence() {
  local hybrid="${TEST_ROOT}/v126-cutover-manual-evidence.sh"
  local fake_bin="${TEST_ROOT}/manual-fake-bin"
  local remote_log="${TEST_ROOT}/manual-remote.log"
  local valid="${TEST_ROOT}/manual-valid.json"
  local old_path="${PATH}"
  local state before after evidence assertion index mutation
  local -a required_assertions=(
    MATRIX_GUEST
    MATRIX_OWNER
    MATRIX_MIX
    MATRIX_MIX_STAFF_CHAT
    TENANT_RBAC_NEGATIVES
    LINKED_STAFF_CHAT_DELIVERY
    NULL_AUTHOR_UNREAD_CREATE_CLEAR_RESURRECT
    WRONG_SURFACE_MARKERS_UNCHANGED
    EXACT_SURFACE_ONLY_CLEAR
    SUPPORT_CONVERSATIONS_SEPARATION
    LABEL_COLLISION
    LIVE_ONE_GUEST_REPLY
    LIVE_ONE_PERSISTED_GUEST_MESSAGE
    LIVE_EXACTLY_ONE_TELEGRAM_OUTBOX_DELIVERY
    LIVE_OWNER_EXACT_THREAD_UNREAD_CREATED_AND_CLEARED
    LIVE_NO_DUPLICATE_OR_RESURRECTED_MARKER
    LIVE_NO_OTHER_THREAD_CLIENT_OR_NON_MIX_MUTATION
  )
  make_instrumented_script "${CUTOVER_SCRIPT}" "${hybrid}" MANUAL_SMOKE_PASSED
  mkdir -m 0700 "${fake_bin}"
  make_success_ssh "${fake_bin}/ssh" manual-smoke-passed
  export HT12P_REMOTE_LOG="${remote_log}"
  PATH="${fake_bin}:${old_path}"

  index=0
  for assertion in "${required_assertions[@]}"; do
    index=$((index + 1))
    new_state "${hybrid}" "manual-missing-$(printf '%02d' "${index}")"
    state="${NEW_STATE}"
    seed_chain "${state}" 13
    evidence="${TEST_ROOT}/manual-missing-$(printf '%02d' "${index}").json"
    write_manual_smoke_evidence "${state}" "${evidence}" "missing:${assertion}"
    before="$(log_line_count "${remote_log}")"
    expect_failure "manual evidence rejects missing claim ${assertion}" \
      'Stage MANUAL_SMOKE_PASSED failed closed' \
      invoke_script "${hybrid}" stage --state-dir "${state}" MANUAL_SMOKE_PASSED \
      --evidence-file "${evidence}"
    after="$(log_line_count "${remote_log}")"
    [[ "${after}" == "${before}" ]] ||
      fail "missing manual claim reached remote boundary: ${assertion}"
    grep -F 'manual-smoke evidence schema or identity mismatch' \
      "${state}/artifacts/14-MANUAL_SMOKE_PASSED.failed.log" >/dev/null ||
      fail "manual claim refusal lacks schema reason: ${assertion}"
  done

  index=0
  for mutation in failed:MATRIX_GUEST extra wrong-result wrong-release secret-extra; do
    index=$((index + 1))
    new_state "${hybrid}" "manual-schema-${index}"
    state="${NEW_STATE}"
    seed_chain "${state}" 13
    evidence="${TEST_ROOT}/manual-schema-${index}.json"
    write_manual_smoke_evidence "${state}" "${evidence}" "${mutation}"
    before="$(log_line_count "${remote_log}")"
    expect_failure "manual evidence rejects schema mutation ${mutation}" \
      'Stage MANUAL_SMOKE_PASSED failed closed' \
      invoke_script "${hybrid}" stage --state-dir "${state}" MANUAL_SMOKE_PASSED \
      --evidence-file "${evidence}"
    after="$(log_line_count "${remote_log}")"
    [[ "${after}" == "${before}" ]] ||
      fail "manual evidence schema mutation reached remote: ${mutation}"
    if [[ "${mutation}" == secret-extra ]]; then
      assert_tree_has_no_canary "${state}"
      rm -f -- "${evidence}"
    fi
  done

  new_state "${hybrid}" manual-valid
  state="${NEW_STATE}"
  seed_chain "${state}" 13
  write_manual_smoke_evidence "${state}" "${valid}" valid
  expect_success 'exact manual-smoke evidence is sealed before Gate C' \
    invoke_script "${hybrid}" stage --state-dir "${state}" MANUAL_SMOKE_PASSED \
    --evidence-file "${valid}"
  [[ -s "${remote_log}" ]] || fail 'valid manual-smoke evidence did not reach the bounded remote action'
  [[ -f "${state}/artifacts/manual-smoke-evidence.json" ]] || fail 'manual-smoke evidence was not sealed'
  PATH="${old_path}"
  unset HT12P_REMOTE_LOG
  pass 'all 17 manual claims are exact, complete, mandatory, and sealed'
}

run_pre_exec_errexit_fixture() {
  local variant="$1"
  local state="$2"
  local payload="$3"
  bash -s -- "${CUTOVER_SCRIPT}" "${variant}" "${state}" "${payload}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_variant="$2"
STATE_DIR="$3"
fixture_payload="$4"
mkdir -m 0700 -p "${STATE_DIR}" "${STATE_DIR}/tmp"
try_acquire_state_lock
tracked_child_wait_for_release() { return 75; }
case "${fixture_variant}" in
  direct)
    run_tracked_command direct-errexit /bin/sh -c \
      'printf "%s\n" MUTATED > "$1"' payload "${fixture_payload}"
    ;;
  input)
    input="${STATE_DIR}/tracked-input"
    printf '%s\n' fixture-input > "${input}"
    chmod 0600 "${input}"
    run_tracked_command_with_input input-errexit "${input}" /bin/sh -c \
      'printf "%s\n" MUTATED > "$1"' payload "${fixture_payload}"
    ;;
  *) die 'unknown pre-exec errexit fixture' ;;
esac
SH
}

test_pre_exec_child_binding_barrier() {
  local gate_variant gate_state gate_payload
  for gate_variant in direct input; do
    gate_state="${TEST_ROOT}/pre-exec-errexit-${gate_variant}"
    gate_payload="${TEST_ROOT}/pre-exec-errexit-${gate_variant}.mutated"
    expect_failure "${gate_variant} child gate failure exits before payload exec" '' \
      run_pre_exec_errexit_fixture "${gate_variant}" "${gate_state}" "${gate_payload}"
    [[ ! -e "${gate_payload}" ]] ||
      fail "${gate_variant} child executed after its release wait failed"
  done
  [[ "$(grep -F -c 'tracked_child_wait_for_release "${' "${CUTOVER_SCRIPT}" || true)" == 3 ]] ||
    fail 'all three tracked launch paths must retain an explicit release wait'
  [[ "$(grep -F -c 'tracked_child_wait_for_release "${' "${CUTOVER_SCRIPT}" | tr -d ' ')" == \
    "$(grep -F 'tracked_child_wait_for_release "${' "${CUTOVER_SCRIPT}" | \
      grep -F -c '|| exit $?' | tr -d ' ')" ]] ||
    fail 'a tracked launch path can ignore release-gate failure under disabled errexit'
  pass 'all tracked launch paths exit explicitly when their pre-exec gate fails'

  local pending_state="${TEST_ROOT}/pending-child-barrier-state"
  local pending_ready="${TEST_ROOT}/pending-child-barrier.ready"
  local pending_payload="${TEST_ROOT}/pending-child-payload.started"
  mkdir -m 0700 "${pending_state}" "${pending_state}/tmp"
  bash -c '
set -Eeuo pipefail
source "$1"
STATE_DIR="$2"
ready="$3"
payload="$4"
try_acquire_state_lock
lock_child_started() {
  printf "%s\n" "$2" > "${ready}"
  chmod 0600 "${ready}"
  while :; do sleep 0.1; done
}
run_tracked_command pending-child /bin/sh -c '\''printf "MUTATED\n" > "$1"'\'' payload "${payload}"
' barrier-launcher "${CUTOVER_SCRIPT}" "${pending_state}" "${pending_ready}" "${pending_payload}" &
  local pending_launcher=$!
  local attempt
  for attempt in $(seq 1 100); do
    [[ -s "${pending_ready}" ]] && break
    kill -0 "${pending_launcher}" 2>/dev/null || fail 'pending-window launcher exited before the test barrier'
    sleep 0.05
  done
  [[ -s "${pending_ready}" ]] || fail 'pending-window launcher never reached the pre-bind barrier'
  local pending_child
  pending_child="$(tr -d '\r\n' < "${pending_ready}")"
  [[ "${pending_child}" =~ ^[1-9][0-9]*$ ]] || fail 'pending-window child PID was not captured'
  [[ -f "${pending_state}/.exclusive-lock/children/pending-child.pending" &&
    ! -e "${pending_state}/.exclusive-lock/children/pending-child.pid" ]] ||
    fail 'pending-window child was not kept behind an explicit pending marker'
  [[ ! -e "${pending_payload}" ]] || fail 'pending-window payload started before durable PID binding'
  kill -KILL "${pending_launcher}" 2>/dev/null || true
  wait "${pending_launcher}" 2>/dev/null || true
  for attempt in $(seq 1 70); do
    kill -0 "${pending_child}" 2>/dev/null || break
    sleep 0.1
  done
  ! kill -0 "${pending_child}" 2>/dev/null || fail 'unreleased pending child did not self-exit in bounded time'
  [[ ! -e "${pending_payload}" ]] || fail 'orphan pending child reached its payload after launcher death'
  [[ -f "${pending_state}/.exclusive-lock/children/pending-child.pending" &&
    ! -e "${pending_state}/.exclusive-lock/children/pending-child.pid" ]] ||
    fail 'launcher death did not retain explicit pending reconciliation state'
  [[ -z "$(find "${pending_state}/tmp" -maxdepth 1 -name '*.release' -print -quit)" ]] ||
    fail 'launcher death forged a child release gate'
  pass 'a child cannot execute before its exact PID is durably bound and released'

  local bound_state="${TEST_ROOT}/bound-child-signal-state"
  local bound_started="${TEST_ROOT}/bound-child.started"
  local bound_completed="${TEST_ROOT}/bound-child.completed"
  mkdir -m 0700 "${bound_state}" "${bound_state}/tmp"
  bash -c '
set -Eeuo pipefail
source "$1"
STATE_DIR="$2"
started="$3"
completed="$4"
try_acquire_state_lock
install_state_lock_traps
run_tracked_command bound-child /bin/sh -c '\''trap "" TERM; : > "$1"; sleep 30; : > "$2"'\'' payload "${started}" "${completed}"
' barrier-signal "${CUTOVER_SCRIPT}" "${bound_state}" "${bound_started}" "${bound_completed}" &
  local bound_launcher=$!
  for attempt in $(seq 1 100); do
    [[ -f "${bound_started}" && -f "${bound_state}/.exclusive-lock/children/bound-child.pid" ]] && break
    kill -0 "${bound_launcher}" 2>/dev/null || fail 'bound-child launcher exited before signal fixture setup'
    sleep 0.05
  done
  [[ -f "${bound_started}" && -f "${bound_state}/.exclusive-lock/children/bound-child.pid" ]] ||
    fail 'bound child never reached the durably tracked payload boundary'
  local bound_child
  bound_child="$(tr -d '\r\n' < "${bound_state}/.exclusive-lock/children/bound-child.pid")"
  kill -TERM "${bound_launcher}"
  local launcher_status=0
  if wait "${bound_launcher}"; then
    fail 'signaled bound-child launcher unexpectedly succeeded'
  else
    launcher_status=$?
  fi
  [[ "${launcher_status}" == 143 ]] || fail "bound-child launcher signal status mismatch: ${launcher_status}"
  for attempt in $(seq 1 50); do
    kill -0 "${bound_child}" 2>/dev/null || break
    sleep 0.1
  done
  ! kill -0 "${bound_child}" 2>/dev/null || fail 'signal cleanup left the durably tracked child alive'
  [[ ! -e "${bound_completed}" ]] || fail 'tracked child completed after its lock owner was signaled'
  pass 'a signal after binding terminates the exact tracked child before continuation'
}

test_state_lock_recovery_contract() {
  local fake_bin="${TEST_ROOT}/lock-fake-bin"
  local remote_log="${TEST_ROOT}/lock-remote.log"
  local old_path="${PATH}"
  local state
  local dead_pid=99999999
  mkdir -m 0700 "${fake_bin}"
  make_success_ssh "${fake_bin}/ssh" recovery-pre-v126
  export HT12P_REMOTE_LOG="${remote_log}"
  PATH="${fake_bin}:${old_path}"

  new_state "${CUTOVER_SCRIPT}" live-lock-authorization
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  make_state_lock "${state}" "$$"
  expect_failure 'live exclusive lock rejects ordinary authorization' \
    'run state is locked or requires explicit interrupted-run reconciliation' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  expect_failure 'live exclusive lock rejects recovery takeover' \
    'state-lock owner is still alive|cannot prove that the prior state-lock process is dead' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  [[ "$(< "${state}/.exclusive-lock/pid")" == "$$" ]] || fail 'live lock owner changed'
  [[ "$(log_line_count "${remote_log}")" == 0 ]] || fail 'live-lock refusal reached SSH'

  new_state "${CUTOVER_SCRIPT}" live-lock-stage
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  expect_success 'prepare Gate A for live-lock stage fixture' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  make_state_lock "${state}" "$$"
  expect_failure 'live exclusive lock rejects ordinary stage' \
    'run state is locked or requires explicit interrupted-run reconciliation' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" PRE_DRAIN_BACKUP_REHEARSED
  [[ ! -e "${state}/intents/02-PRE_DRAIN_BACKUP_REHEARSED.intent.json" ]] ||
    fail 'lock refusal created a stage intent'

  new_state "${CUTOVER_SCRIPT}" dead-owner-live-child
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  make_state_lock "${state}" "${dead_pid}"
  sleep 30 &
  local orphan_pid=$!
  printf '%s\n' "${orphan_pid}" > "${state}/.exclusive-lock/children/remote.pid"
  chmod 0400 "${state}/.exclusive-lock/children/remote.pid"
  expect_failure 'dead lock owner with a live tracked orphan child rejects recovery' \
    'tracked child|all tracked child|still alive' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  [[ -d "${state}/.exclusive-lock" && ! -e "${state}/run-terminal.json" ]] ||
    fail 'live orphan-child refusal changed the lock or terminal state'
  kill "${orphan_pid}" >/dev/null 2>&1 || true
  wait "${orphan_pid}" >/dev/null 2>&1 || true
  expect_success 'dead owner lock becomes recoverable only after tracked child death' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  [[ -f "${state}/run-terminal.json" ]] ||
    fail 'orphan-death recovery did not terminalize'

  new_state "${CUTOVER_SCRIPT}" dead-owner-pending-child
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  make_state_lock "${state}" "${dead_pid}"
  printf '%s\n' PENDING > "${state}/.exclusive-lock/children/remote.pending"
  chmod 0400 "${state}/.exclusive-lock/children/remote.pending"
  expect_failure 'ambiguous pending child launch rejects stale-lock recovery' \
    'tracked child|pending|all tracked child' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  [[ ! -e "${state}/run-terminal.json" ]] ||
    fail 'pending child ambiguity terminalized the run'

  kill -0 "${dead_pid}" >/dev/null 2>&1 && fail 'chosen dead-lock PID is unexpectedly live'
  new_state "${CUTOVER_SCRIPT}" dead-lock-recovery
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  expect_success 'prepare Gate A for dead-lock fixture' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  make_state_lock "${state}" "${dead_pid}"
  expect_failure 'dead lock still rejects ordinary stage takeover' \
    'run state is locked or requires explicit interrupted-run reconciliation' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" PRE_DRAIN_BACKUP_REHEARSED
  expect_failure 'unauthorized recovery cannot reclaim a dead lock' 'authorization mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization WRONG
  [[ "$(< "${state}/.exclusive-lock/pid")" == "${dead_pid}" ]] ||
    fail 'unauthorized recovery changed the dead lock'
  [[ ! -e "${state}/run-terminal.json" ]] || fail 'unauthorized recovery terminalized the run'
  expect_success 'authorized recovery alone reclaims a dead lock and terminalizes' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  grep -F 'PRE_V126_ROLLBACK_COMPLETE' "${LAST_OUTPUT}" >/dev/null ||
    fail 'dead-lock recovery lacks the terminal result'
  [[ ! -e "${state}/.exclusive-lock" && -f "${state}/run-terminal.json" &&
    -f "${state}/recovery/pre-v126.receipt.json" ]] ||
    fail 'dead-lock recovery did not release the lock into terminal state'
  expect_failure 'dead-lock recovery terminal forbids later stages' 'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" PRE_DRAIN_BACKUP_REHEARSED
  expect_failure 'dead-lock recovery terminal forbids later authorization' 'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  assert_state_modes "${state}"

  new_state "${CUTOVER_SCRIPT}" concurrent-recovery-takeover
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  make_state_lock "${state}" "${dead_pid}"
  local concurrent_before
  concurrent_before="$(log_line_count "${remote_log}")"
  local out_one="${TEST_ROOT}/concurrent-recovery-one.log"
  local out_two="${TEST_ROOT}/concurrent-recovery-two.log"
  invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK > "${out_one}" 2>&1 &
  local recovery_one=$!
  invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK > "${out_two}" 2>&1 &
  local recovery_two=$!
  local status_one status_two
  if wait "${recovery_one}"; then status_one=0; else status_one=$?; fi
  if wait "${recovery_two}"; then status_two=0; else status_two=$?; fi
  [[ "$(( (status_one == 0 ? 1 : 0) + (status_two == 0 ? 1 : 0) ))" == 1 ]] || {
    sed -n '1,120p' "${out_one}" >&2
    sed -n '1,120p' "${out_two}" >&2
    fail "concurrent recovery takeover winners mismatch: ${status_one},${status_two}"
  }
  local concurrent_after
  concurrent_after="$(log_line_count "${remote_log}")"
  [[ "$((concurrent_after - concurrent_before))" == 1 ]] ||
    fail 'concurrent recovery takeover crossed the remote boundary more than once'
  [[ -f "${state}/run-terminal.json" && \
    -f "${state}/recovery/pre-v126.receipt.json" ]] ||
    fail 'winning concurrent recovery did not record one terminal receipt'
  [[ "$(find "${state}/recovery" -maxdepth 1 -name 'pre-v126.intent.json' | wc -l | tr -d ' ')" == 1 ]] ||
    fail 'concurrent recovery produced more than one intent'
  assert_no_canary_file "${out_one}"
  assert_no_canary_file "${out_two}"
  pass 'atomic stale-lock takeover allows exactly one recovery winner'

  PATH="${old_path}"
  unset HT12P_REMOTE_LOG
  pass 'live locks refuse mutation and only authorized recovery reclaims a dead lock'
}

test_authorization_recovery_lock_order() {
  local authorize_source="${TEST_ROOT}/authorize-function.sh"
  extract_function_source "${CUTOVER_SCRIPT}" authorize_command "${authorize_source}"
  assert_literals_in_order "${authorize_source}" 'authorization lock-before-terminal ordering' \
    'load_state "${state_dir}"' \
    'acquire_state_lock' \
    'install_state_lock_traps' \
    'run-terminal.json' \
    'require_no_recovery_intent' \
    'write_authorization "${gate}" "${authorization}"'

  local fake_bin="${TEST_ROOT}/authorization-recovery-race-bin"
  local ready="${TEST_ROOT}/authorization-recovery.ready"
  local release="${TEST_ROOT}/authorization-recovery.release"
  local remote_log="${TEST_ROOT}/authorization-recovery-remote.log"
  local recovery_output="${TEST_ROOT}/authorization-recovery.out"
  local old_path="${PATH}"
  local state attempt recovery_pid recovery_status
  mkdir -m 0700 "${fake_bin}"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "ssh %s\n" "$*" >> "${HT12P_REMOTE_LOG:?}"' \
    'while IFS= read -r _line; do :; done' \
    'printf "%s\n" ready > "${HT12P_RACE_READY:?}"' \
    'attempt=0' \
    'while [[ ! -f "${HT12P_RACE_RELEASE:?}" ]]; do' \
    '  attempt=$((attempt + 1))' \
    '  (( attempt <= 250 )) || exit 96' \
    '  sleep 0.02' \
    'done' \
    "printf 'ARTIFACT\\trecovery-pre-v126\\t%s\\n' '4444444444444444444444444444444444444444444444444444444444444444'" \
    > "${fake_bin}/ssh"
  chmod 0700 "${fake_bin}/ssh"
  export HT12P_REMOTE_LOG="${remote_log}"
  export HT12P_RACE_READY="${ready}"
  export HT12P_RACE_RELEASE="${release}"
  PATH="${fake_bin}:${old_path}"

  new_state "${CUTOVER_SCRIPT}" authorization-recovery-race
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK > "${recovery_output}" 2>&1 &
  recovery_pid=$!
  for attempt in $(seq 1 250); do
    [[ -f "${ready}" ]] && break
    kill -0 "${recovery_pid}" 2>/dev/null || break
    sleep 0.02
  done
  [[ -f "${ready}" && -d "${state}/.exclusive-lock" && \
    -f "${state}/run-terminal.json" ]] || {
    sed -n '1,160p' "${recovery_output}" >&2
    kill "${recovery_pid}" 2>/dev/null || true
    fail 'recovery did not reach the terminalized lock-held race window'
  }
  expect_failure 'authorization cannot pass a lock-held terminal recovery' \
    'locked|terminal|reconciliation' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  [[ ! -e "${state}/authorizations/GATE_A.authorization.json" && \
    ! -e "${state}/authorizations/GATE_A.authorization.json.sha256" ]] ||
    fail 'blocked concurrent authorization wrote a Gate A file'
  : > "${release}"
  if wait "${recovery_pid}"; then recovery_status=0; else recovery_status=$?; fi
  [[ "${recovery_status}" == 0 ]] || {
    sed -n '1,160p' "${recovery_output}" >&2
    fail "terminal recovery lost the authorization race: ${recovery_status}"
  }
  [[ -f "${state}/run-terminal.json" && \
    -f "${state}/recovery/pre-v126.receipt.json" ]] ||
    fail 'winning recovery did not seal its terminal receipt'
  expect_failure 'authorization remains forbidden after recovery releases the lock' \
    'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  [[ ! -e "${state}/authorizations/GATE_A.authorization.json" && \
    ! -e "${state}/authorizations/GATE_A.authorization.json.sha256" ]] ||
    fail 'post-terminal authorization wrote a Gate A file'
  PATH="${old_path}"
  unset HT12P_REMOTE_LOG HT12P_RACE_READY HT12P_RACE_RELEASE
  pass 'authorization acquires the state lock before terminal checks and cannot race recovery'
}

NEW_POST_STATE=''

prepare_partial_off_dr_fixture() {
  local root="$1"
  local run_id="$2"
  local staging="${root}/staging"
  local run_root="${staging}/.v126-runs/${run_id}"
  local backup_root="${root}/backup"
  local database_file="${root}/database-url"
  local identities_file="${root}/identities"
  local smoke_env="${root}/smoke.env"
  mkdir -m 0700 -p "${staging}/scripts" "${run_root}" "${backup_root}"
  printf '%s\n' \
    'JWT_SECRET=fixture-partial-off-secret' \
    'PRESERVED_SETTING=exact-bytes' \
    'STAGING_MAINTENANCE_MODE=OFF' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' > "${staging}/.env"
  printf '%s\n' \
    'JWT_SECRET=fixture-partial-off-secret' \
    'PRESERVED_SETTING=exact-bytes' \
    'STAGING_MAINTENANCE_MODE=V126_SMOKE' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=918273645012345678' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-918273645012345679' > "${smoke_env}"
  printf '%s\n' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=918273645012345678' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-918273645012345679' > "${identities_file}"
  printf '%s\n' 'postgresql://operator:fixture@db.invalid:5432/exact' > "${database_file}"
  printf '%s\n' 'services: {}' > "${staging}/docker-compose.yml"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/check-staging-maintenance-config.sh"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
    "${staging}/scripts/validate-staging-admission.sh"
  chmod 0600 "${staging}/.env" "${smoke_env}" "${database_file}" "${identities_file}"
  chmod 0644 "${staging}/docker-compose.yml"
  chmod 0755 "${staging}/scripts/check-staging-maintenance-config.sh" \
    "${staging}/scripts/validate-staging-admission.sh"
  write_baseline_authority_fixture "${staging}" "${run_root}" \
    "${database_file}" "${identities_file}" "${run_id}"
  write_maintenance_env_proof_fixture "${run_root}" "${run_id}" V126_SMOKE \
    "$(sha256_file "${smoke_env}")" "$(sha256_file "${staging}/.env")"
  printf '%s\n' fixture-list-only-inventory > "${backup_root}/quiesced.dump"
  cp "${backup_root}/quiesced.dump" "${backup_root}/quiesced.dump.pg_restore.list"
  chmod 0600 "${backup_root}/quiesced.dump" \
    "${backup_root}/quiesced.dump.pg_restore.list"
  rm -f -- "${smoke_env}"
}

run_partial_off_dr_fixture() {
  local root="$1"
  local run_id="$2"
  local mode="$3"
  local command_log="$4"
  local mutation_log="$5"
  bash -s -- "${CUTOVER_SCRIPT}" "${root}" "${run_id}" "${RELEASE_SHA}" \
    "${mode}" "${command_log}" "${mutation_log}" "${V126_IMAGE_ID}" \
    "${FIXTURE_BASELINE_CADDY_SHA}" <<'SH'
set -Eeuo pipefail
source "$1"
eval "$(declare -f remote_read_maintenance_after_sha | \
  sed '1s/^remote_read_maintenance_after_sha/original_remote_read_maintenance_after_sha/')"
set +u
fixture_root="$2"
fixture_run_id="$3"
fixture_release="$4"
fixture_mode="$5"
fixture_command_log="$6"
fixture_mutation_log="$7"
fixture_v126_image_id="$8"
fixture_caddy_sha="$9"
fixture_staging="${fixture_root}/staging"
fixture_run_root="${fixture_staging}/.v126-runs/${fixture_run_id}"
fixture_backup_root="${fixture_root}/backup"
fixture_database_file="${fixture_root}/database-url"
fixture_identities_file="${fixture_root}/identities"
fixture_backend_id=aaaaaaaaaaaa
fixture_backend_running=true
fixture_post_receipt_sha=6666666666666666666666666666666666666666666666666666666666666666
V126_INTERNAL_REMOTE_V126_IMAGE_ID="${fixture_v126_image_id}"
V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256="$(remote_hash_file "${fixture_database_file}")"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256="$(remote_hash_file "${fixture_identities_file}")"
V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256="$(remote_hash_file "${fixture_staging}/docker-compose.yml")"
V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256="$(remote_hash_file "${fixture_staging}/scripts/check-staging-maintenance-config.sh")"
V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256="$(remote_hash_file "${fixture_staging}/scripts/validate-staging-admission.sh")"
V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${fixture_caddy_sha}"
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="$(python3 - "${fixture_run_root}/baseline-authority.proof" <<'PY'
import sys
for row in open(sys.argv[1], encoding="utf-8"):
    if row.startswith("environment_sha256="):
        print(row.split("=", 1)[1].strip())
        break
PY
)"
V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256="$(remote_hash_file "${fixture_run_root}/maintenance-v126_smoke.proof")"
V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256=NONE
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    python3 - "$3" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys
path, user, group = sys.argv[1:]
print(f"{stat.S_IMODE(os.stat(path).st_mode):o}:{user}:{group}")
PY
    return 0
  fi
  command stat "$@"
}
log_command() { printf '%s\n' "$*" >> "${fixture_command_log}"; }
remote_assert_compose_backend_image() { log_command "compose-map $1"; }
remote_read_maintenance_after_sha() {
  local verified_sha
  verified_sha="$(original_remote_read_maintenance_after_sha "$@")" || return
  if [[ "${fixture_mode}" == partial-identity-late-swap ]]; then
    printf '%s\n' \
      'STAGING_MAINTENANCE_ALLOWED_USER_IDS=111111111' \
      'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-222222222' > "${fixture_identities_file}"
    chmod 0600 "${fixture_identities_file}"
  fi
  printf '%s\n' "${verified_sha}"
}
remote_flyway_state() { log_command flyway; printf '%s\n' 126:1:1:0; }
remote_recovery_ensure_candidate_drain() { log_command caddy-drain; }
remote_assert_caddy_drain_marker() { log_command caddy-marker; }
remote_assert_public_drain() { log_command public-drain; }
remote_assert_zero_writer() { log_command "zero-writer $1"; }
remote_backup_root() { printf '%s\n' "${fixture_backup_root}"; }
remote_emit_artifact() { log_command "artifact $1 $2"; }
remote_compose() {
  case "$*" in
    'ps --status running -q backend')
      if [[ "${fixture_backend_running}" == true ]]; then
        printf '%s\n' "${fixture_backend_id}"
      fi
      ;;
    'stop backend')
      log_command 'compose stop backend'
      fixture_backend_running=false
      ;;
    'exec -T postgres sh -c : "${POSTGRES_USER:?}"; pg_restore --list')
      log_command 'compose pg_restore --list'
      cat
      ;;
    *) printf 'FORBIDDEN compose %s\n' "$*" >> "${fixture_mutation_log}"; return 97 ;;
  esac
}
docker() {
  if [[ "$*" == "inspect --format {{.Image}} ${fixture_backend_id}" ]]; then
    printf '%s\n' "${fixture_v126_image_id}"
    return 0
  fi
  printf 'FORBIDDEN docker %s\n' "$*" >> "${fixture_mutation_log}"
  return 98
}
psql() { printf 'FORBIDDEN psql %s\n' "$*" >> "${fixture_mutation_log}"; return 99; }
createdb() { printf 'FORBIDDEN createdb %s\n' "$*" >> "${fixture_mutation_log}"; return 99; }
dropdb() { printf 'FORBIDDEN dropdb %s\n' "$*" >> "${fixture_mutation_log}"; return 99; }
pg_restore() { printf 'FORBIDDEN pg_restore %s\n' "$*" >> "${fixture_mutation_log}"; return 99; }

V126_INTERNAL_REMOTE_OPERATION_KIND=RECOVERY
V126_INTERNAL_REMOTE_OPERATION_NAME=post-v126-stop
V126_INTERNAL_REMOTE_ACTION=recover-post-v126-stop
V126_INTERNAL_REMOTE_PREDECESSOR_STAGE=V126_BACKEND_STOPPED_FOR_OFF_TRANSITION
V126_INTERNAL_REMOTE_PREDECESSOR_HASH=5555555555555555555555555555555555555555555555555555555555555555
remote_recover_post_v126_stop "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
  "hookah-v126:${fixture_release}" "${fixture_v126_image_id}"
fixture_post_proof="${fixture_run_root}/recovery-post-v126-stop.proof"
fixture_post_proof_sha="$(remote_hash_file "${fixture_post_proof}")"
if [[ "${fixture_mode}" == bad-post-proof ]]; then
  printf '%s\n' 'substituted=PASS' >> "${fixture_post_proof}"
  printf '%s\n' "$(remote_hash_file "${fixture_post_proof}")" > "${fixture_post_proof}.sha256"
  chmod 0600 "${fixture_post_proof}" "${fixture_post_proof}.sha256"
fi
V126_INTERNAL_REMOTE_OPERATION_NAME=verify-full-dr
V126_INTERNAL_REMOTE_ACTION=verify-full-dr
V126_INTERNAL_REMOTE_PREDECESSOR_STAGE=RECOVERY_POST_V126_STOP
V126_INTERNAL_REMOTE_PREDECESSOR_HASH="${fixture_post_receipt_sha}"
dump="${fixture_backup_root}/quiesced.dump"
inventory="${dump}.pg_restore.list"
remote_verify_full_dr "${fixture_staging}" "${fixture_run_id}" "${fixture_release}" \
  "hookah-v126:${fixture_release}" quiesced "$(remote_hash_file "${dump}")" \
  "$(remote_hash_file "${inventory}")" "$(hash_text fixture-boundary)" \
  "${fixture_post_proof_sha}"
SH
}

test_partial_off_post_stop_full_dr_chain() {
  local root="${TEST_ROOT}/partial-off-dr-success"
  local run_id='partial-off-dr-success'
  local command_log="${root}/commands.log"
  local mutation_log="${root}/mutations.log"
  prepare_partial_off_dr_fixture "${root}" "${run_id}"
  [[ ! -e "${root}/staging/.v126-runs/${run_id}/maintenance-off.proof" ]] ||
    fail 'partial OFF fixture accidentally contains a stage-17 receipt proof'
  expect_success 'exact unreceipted stage-17 OFF supports post-stop and chained full-DR verification' \
    run_partial_off_dr_fixture "${root}" "${run_id}" success "${command_log}" "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] || fail 'partial OFF recovery chain crossed a forbidden mutation surface'
  [[ "$(grep -F -c 'compose stop backend' "${command_log}" || true)" == 1 ]] ||
    fail 'partial OFF recovery chain did not perform exactly one terminal backend stop'
  [[ "$(grep -F -c 'compose pg_restore --list' "${command_log}" || true)" == 1 ]] ||
    fail 'partial OFF recovery chain did not perform exactly one list-only DR inspection'
  python3 - "${root}/staging/.v126-runs/${run_id}/recovery-full-dr-prerequisites.proof" <<'PY'
import sys
parsed = dict(line.rstrip("\n").split("=", 1) for line in open(sys.argv[1], encoding="utf-8"))
if parsed.get("post_v126_stop_receipt_sha256") != "6" * 64:
    raise SystemExit("partial OFF full-DR proof lacks exact post-stop receipt binding")
if parsed.get("post_v126_stop_proof_sha256") in (None, "NONE"):
    raise SystemExit("partial OFF full-DR proof lacks exact post-stop proof binding")
if parsed.get("restore_performed") != "false":
    raise SystemExit("partial OFF full-DR fixture performed a restore")
PY

  root="${TEST_ROOT}/partial-off-dr-drift"
  run_id='partial-off-dr-drift'
  command_log="${root}/commands.log"
  mutation_log="${root}/mutations.log"
  prepare_partial_off_dr_fixture "${root}" "${run_id}"
  printf '%s\n' 'ARBITRARY_OFF_DRIFT=unsafe' >> "${root}/staging/.env"
  expect_failure 'arbitrary unreceipted OFF drift rejects before post-stop runtime actions' \
    'partial transition does not reconstruct the immutable source environment' \
    run_partial_off_dr_fixture "${root}" "${run_id}" success "${command_log}" "${mutation_log}"
  [[ ! -s "${command_log}" && ! -s "${mutation_log}" ]] ||
    fail 'arbitrary OFF drift reached Compose, Flyway, Caddy, Docker, or DR inspection'

  root="${TEST_ROOT}/partial-off-dr-identity-late-swap"
  run_id='partial-off-dr-identity-late-swap'
  command_log="${root}/commands.log"
  mutation_log="${root}/mutations.log"
  prepare_partial_off_dr_fixture "${root}" "${run_id}"
  expect_failure 'partial OFF transition rejects identity bytes swapped after outer authority hash' \
    'partial transition identities differ from immutable authority at derivation' \
    run_partial_off_dr_fixture "${root}" "${run_id}" partial-identity-late-swap \
    "${command_log}" "${mutation_log}"
  [[ ! -s "${command_log}" && ! -s "${mutation_log}" ]] ||
    fail 'partial-transition identity late swap reached Compose, Flyway, Caddy, Docker, or DR'
  [[ ! -e "${root}/staging/.v126-runs/${run_id}/recovery-post-v126-stop.proof" ]] ||
    fail 'partial-transition identity late swap sealed a recovery proof'

  root="${TEST_ROOT}/partial-off-dr-bad-proof"
  run_id='partial-off-dr-bad-proof'
  command_log="${root}/commands.log"
  mutation_log="${root}/mutations.log"
  prepare_partial_off_dr_fixture "${root}" "${run_id}"
  expect_failure 'rechecksummed substituted post-stop proof rejects chained full-DR' \
    'post-V126 stop proof does not match the immutable recovery receipt artifact' \
    run_partial_off_dr_fixture "${root}" "${run_id}" bad-post-proof \
    "${command_log}" "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] || fail 'substituted post-stop proof reached a forbidden mutation'
  [[ "$(grep -F -c 'compose pg_restore --list' "${command_log}" || true)" == 0 ]] ||
    fail 'substituted post-stop proof reached the DR archive inspection'
  pass 'unreceipted exact OFF is bounded to post-stop/full-DR while drift and proof substitution reject'
}

prepare_post_v126_recovery() {
  local label="$1"
  local fake_ssh="$2"
  local state
  new_state "${CUTOVER_SCRIPT}" "${label}"
  state="${NEW_STATE}"
  seed_chain "${state}" 7
  make_success_ssh "${fake_ssh}" recovery-post-v126-stop
  expect_success "successful post-V126 stop fixture ${label}" \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" post-v126-stop \
    --authorization AUTHORIZE_V126_POST_V126_FORWARD_FIX_STOP
  grep -F 'FORWARD_FIX_REQUIRED' "${LAST_OUTPUT}" >/dev/null ||
    fail "post-V126 stop fixture lacks terminal result: ${label}"
  NEW_POST_STATE="${state}"
}

test_post_v126_dr_chain() {
  local fake_bin="${TEST_ROOT}/post-dr-fake-bin"
  local remote_log="${TEST_ROOT}/post-dr-remote.log"
  local remote_stream_log="${TEST_ROOT}/post-dr-remote-stream.log"
  local old_path="${PATH}"
  local state boundary before after receipt
  mkdir -m 0700 "${fake_bin}"
  export HT12P_REMOTE_LOG="${remote_log}"
  PATH="${fake_bin}:${old_path}"

  prepare_post_v126_recovery post-dr-wrong "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-wrong-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  receipt="${state}/recovery/post-v126-stop.receipt.json"
  rewrite_canonical_json "${receipt}" mode pre-v126
  before="$(log_line_count "${remote_log}")"
  expect_failure 'wrong post-V126 receipt rejects DR before remote' \
    'post-V126 recovery receipt failed strict verification|full-DR escalation requires' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'wrong post-V126 receipt reached SSH'
  [[ ! -e "${state}/recovery/verify-full-dr.intent.json" ]] ||
    fail 'wrong post-V126 receipt created a DR intent'

  prepare_post_v126_recovery post-dr-missing "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-missing-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  mv "${state}/recovery/post-v126-stop.receipt.json" "${TEST_ROOT}/missing-post-receipt.json"
  mv "${state}/recovery/post-v126-stop.receipt.json.sha256" "${TEST_ROOT}/missing-post-receipt.sha256"
  before="$(log_line_count "${remote_log}")"
  expect_failure 'missing post-V126 receipt rejects DR before remote' \
    'post-V126 recovery receipt failed strict verification|full-DR escalation requires' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'missing post-V126 receipt reached SSH'

  prepare_post_v126_recovery post-dr-tampered "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-tampered-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  receipt="${state}/recovery/post-v126-stop.receipt.json"
  chmod 0600 "${receipt}"
  printf ' ' >> "${receipt}"
  chmod 0400 "${receipt}"
  before="$(log_line_count "${remote_log}")"
  expect_failure 'tampered post-V126 receipt rejects DR before remote' \
    'post-V126 recovery receipt failed strict verification|full-DR escalation requires' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'tampered post-V126 receipt reached SSH'

  prepare_post_v126_recovery post-dr-missing-operation-artifact "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-missing-operation-artifact-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  receipt="${state}/recovery/post-v126-stop.receipt.json"
  rewrite_receipt_artifacts "${receipt}" remove operation-log
  before="$(log_line_count "${remote_log}")"
  expect_failure 'post-V126 receipt missing operation-log rejects before DR' \
    'post-V126 recovery receipt failed strict verification|artifact|operation' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'missing recovery operation-log reached SSH'
  [[ ! -e "${state}/recovery/verify-full-dr.intent.json" ]] ||
    fail 'missing recovery operation-log created a DR intent'

  prepare_post_v126_recovery post-dr-substituted-operation "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-substituted-operation-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  receipt="${state}/recovery/post-v126-stop.receipt.json"
  rewrite_receipt_artifacts "${receipt}" replace operation-log \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  before="$(log_line_count "${remote_log}")"
  expect_failure 'post-V126 receipt substituted operation-log rejects before DR' \
    'post-V126 recovery receipt failed strict verification|artifact|operation' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'substituted recovery operation-log reached SSH'

  prepare_post_v126_recovery post-dr-missing-remote-proof "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-missing-remote-proof-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  receipt="${state}/recovery/post-v126-stop.receipt.json"
  rewrite_receipt_artifacts "${receipt}" remove recovery-post-v126-stop
  before="$(log_line_count "${remote_log}")"
  expect_failure 'post-V126 receipt missing remote proof rejects before DR' \
    'post-V126 recovery receipt failed strict verification|artifact|proof' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'missing recovery remote proof reached SSH'

  prepare_post_v126_recovery post-dr-substituted-remote-proof "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-substituted-remote-proof-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  receipt="${state}/recovery/post-v126-stop.receipt.json"
  rewrite_receipt_artifacts "${receipt}" replace recovery-post-v126-stop \
    bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  before="$(log_line_count "${remote_log}")"
  expect_failure 'post-V126 receipt substituted remote proof rejects before DR' \
    'post-V126 recovery receipt failed strict verification|artifact|proof|operation' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'substituted recovery remote proof reached SSH'

  prepare_post_v126_recovery post-dr-mutated-operation-file "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-mutated-operation-file-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  chmod 0600 "${state}/recovery/post-v126-stop.operation.log"
  printf 'post-receipt recovery log mutation\n' >> \
    "${state}/recovery/post-v126-stop.operation.log"
  chmod 0400 "${state}/recovery/post-v126-stop.operation.log"
  before="$(log_line_count "${remote_log}")"
  expect_failure 'actual post-V126 operation-log mismatch rejects before DR' \
    'post-V126 recovery receipt failed strict verification|artifact|operation' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'mutated recovery operation file reached SSH'

  prepare_post_v126_recovery post-dr-success "${fake_bin}/ssh"
  state="${NEW_POST_STATE}"
  boundary="${TEST_ROOT}/post-dr-success-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  expect_failure 'post-V126 stop remains terminal for stages' 'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" FINAL_V126_BACKEND_STARTED
  expect_failure 'post-V126 stop cannot be retried' 'run is already terminal' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" post-v126-stop \
    --authorization AUTHORIZE_V126_POST_V126_FORWARD_FIX_STOP
  local dump_sha inventory_sha boundary_sha post_receipt_sha post_proof_sha
  dump_sha="$(receipt_artifact_from_state "${state}" \
    07-QUIESCED_BACKUP_REHEARSED.receipt.json quiesced-backup-dump)"
  inventory_sha="$(receipt_artifact_from_state "${state}" \
    07-QUIESCED_BACKUP_REHEARSED.receipt.json quiesced-backup-inventory)"
  boundary_sha="$(sha256_file "${boundary}")"
  post_receipt_sha="$(sha256_file "${state}/recovery/post-v126-stop.receipt.json")"
  post_proof_sha="$(recovery_receipt_artifact_from_state "${state}" \
    post-v126-stop.receipt.json recovery-post-v126-stop)"
  make_success_ssh "${fake_bin}/ssh" recovery-full-dr-prerequisites
  before="$(log_line_count "${remote_log}")"
  export HT12P_REMOTE_STREAM_LOG="${remote_stream_log}"
  expect_success 'post-V126 terminal anchors one separately authorized full-DR verifier' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  unset HT12P_REMOTE_STREAM_LOG
  grep -F 'DR_AUTHORIZATION_REQUIRED' "${LAST_OUTPUT}" >/dev/null ||
    fail 'full-DR verifier lacks its terminal authorization boundary'
  after="$(log_line_count "${remote_log}")"
  [[ "$((after - before))" == 1 ]] || fail 'full-DR verifier did not make exactly one remote call'
  grep -F "${dump_sha}" "${remote_stream_log}" >/dev/null || fail 'DR remote envelope lacks selected dump hash'
  grep -F "${inventory_sha}" "${remote_stream_log}" >/dev/null || fail 'DR remote envelope lacks selected inventory hash'
  grep -F "${boundary_sha}" "${remote_stream_log}" >/dev/null || fail 'DR remote envelope lacks sealed boundary hash'
  grep -F "${post_receipt_sha}" "${remote_stream_log}" >/dev/null ||
    fail 'DR remote envelope lacks exact post-V126 receipt hash'
  grep -F "${post_proof_sha}" "${remote_stream_log}" >/dev/null ||
    fail 'DR remote envelope lacks exact post-V126 proof hash'
  ! grep -F "${dump_sha}" "${remote_log}" >/dev/null || fail 'DR dump hash leaked into SSH argv'
  ! grep -F "${inventory_sha}" "${remote_log}" >/dev/null || fail 'DR inventory hash leaked into SSH argv'
  ! grep -F "${post_proof_sha}" "${remote_log}" >/dev/null ||
    fail 'post-V126 proof hash leaked into SSH argv'
  cmp "${boundary}" "${state}/recovery/dr-boundary.json" >/dev/null ||
    fail 'DR boundary evidence was not sealed byte-for-byte'
  python3 - "${state}" "${post_receipt_sha}" <<'PY'
import hashlib
import json
import os
import stat
import sys

state, expected_post_hash = sys.argv[1:]
paths = {
    "terminal": os.path.join(state, "run-terminal.json"),
    "post": os.path.join(state, "recovery", "post-v126-stop.receipt.json"),
    "intent": os.path.join(state, "recovery", "verify-full-dr.intent.json"),
    "receipt": os.path.join(state, "recovery", "verify-full-dr.receipt.json"),
}
documents = {name: json.load(open(path, "rt", encoding="utf-8")) for name, path in paths.items()}
for path in paths.values():
    if stat.S_IMODE(os.stat(path).st_mode) != 0o400:
        raise SystemExit(f"recovery chain mode mismatch: {path}")
post_hash = hashlib.sha256(open(paths["post"], "rb").read()).hexdigest()
intent_hash = hashlib.sha256(open(paths["intent"], "rb").read()).hexdigest()
if post_hash != expected_post_hash:
    raise SystemExit("post-V126 receipt changed during DR escalation")
if documents["terminal"].get("mode") != "post-v126-stop":
    raise SystemExit("DR escalation replaced the post-V126 terminal marker")
for name in ("intent", "receipt"):
    if documents[name].get("predecessor_stage") != "RECOVERY_POST_V126_STOP":
        raise SystemExit(f"DR {name} predecessor stage mismatch")
    if documents[name].get("predecessor_receipt_sha256") != post_hash:
        raise SystemExit(f"DR {name} predecessor hash mismatch")
if documents["receipt"].get("intent_sha256") != intent_hash:
    raise SystemExit("DR receipt intent hash mismatch")
if documents["receipt"].get("result_category") != "TERMINAL_RECOVERY_BOUNDARY":
    raise SystemExit("DR receipt result mismatch")
PY
  before="$(log_line_count "${remote_log}")"
  expect_failure 'full-DR prerequisite verification cannot be repeated' \
    'exists|recovery intent|already' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "${after}" == "${before}" ]] || fail 'repeated full-DR verification reached SSH'
  expect_failure 'full-DR escalation remains terminal for stages' 'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" FINAL_V126_BACKEND_STARTED
  assert_state_modes "${state}"

  new_state "${CUTOVER_SCRIPT}" direct-dr
  state="${NEW_STATE}"
  seed_chain "${state}" 7
  boundary="${TEST_ROOT}/direct-dr-boundary.json"
  write_dr_boundary "${state}" "${boundary}" quiesced
  make_success_ssh "${fake_bin}/ssh" recovery-full-dr-prerequisites
  before="$(log_line_count "${remote_log}")"
  expect_success 'verified normal-stage state may enter direct full-DR prerequisite verification' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase quiesced --boundary-file "${boundary}"
  after="$(log_line_count "${remote_log}")"
  [[ "$((after - before))" == 1 ]] || fail 'direct DR verifier did not make exactly one remote call'
  python3 - "${state}/run-terminal.json" <<'PY'
import json
import sys
if json.load(open(sys.argv[1], "rt", encoding="utf-8")).get("mode") != "verify-full-dr":
    raise SystemExit("direct DR verifier did not terminalize in verify-full-dr mode")
PY
  expect_failure 'direct full-DR path is terminal for stages' 'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" FINAL_V126_BACKEND_STARTED
  assert_state_modes "${state}"

  PATH="${old_path}"
  unset HT12P_REMOTE_LOG
  pass 'post-V126 stop strictly anchors one full-DR verifier and direct DR remains bounded'
}

run_recovery_refusal_fixture() {
  local mode="$1"
  local flyway_state="$2"
  local running_image="$3"
  local mutation_log="$4"
  bash -s -- "${CUTOVER_SCRIPT}" "${mode}" "${flyway_state}" "${running_image}" \
    "${mutation_log}" "${TEST_ROOT}/remote-staging" "${RELEASE_SHA}" "${V126_IMAGE_ID}" <<'SH'
set -Eeuo pipefail
source "$1"
set +u # macOS Bash 3 treats an explicitly empty indexed array as unset under nounset.
mode="$2"
fixture_flyway="$3"
fixture_running_image="$4"
mutation_log="$5"
fixture_staging="$6"
fixture_release="$7"
fixture_v126_image_id="$8"
fixture_run_root="$(mktemp -d "${fixture_staging}/recovery-proof.XXXXXX")"
remote_initialize_compose() { :; }
remote_require_run_root() { printf '%s\n' "${fixture_run_root}"; }
remote_recovery_ensure_pre_v126_drain() { :; }
remote_recovery_ensure_candidate_drain() { printf '%s\n' candidate-drain >> "${mutation_log}"; }
remote_assert_public_drain() { :; }
remote_assert_zero_writer() { printf '%s\n' zero-writer >> "${mutation_log}"; }
remote_flyway_state() { printf '%s\n' "${fixture_flyway}"; }
remote_recovery_product_off() { printf '%s\n' product-off >> "${mutation_log}"; }
remote_assert_compose_backend_image() { :; }
remote_wait_backend_running() { :; }
remote_assert_v125_runtime() { :; }
remote_recovery_restore_original_caddy() { printf '%s\n' fixture-caddy; }
remote_write_proof() {
  local target="$1"
  shift
  printf '%s\n' "$@" > "${target}"
  chmod 0600 "${target}"
  printf '%064d\n' 4 > "${target}.sha256"
  chmod 0600 "${target}.sha256"
  printf 'proof %s\n' "$*" >> "${mutation_log}"
}
remote_hash_file() { printf '%064d\n' 4; }
remote_emit_artifact() { :; }
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    printf '600:%s:%s\n' "$(id -un)" "$(id -gn)"
    return 0
  fi
  command stat "$@"
}
sudo() { return 0; }
fixture_backend_running=false
[[ -n "${fixture_running_image}" ]] && fixture_backend_running=true
remote_compose() {
  if [[ "${1:-}" == ps && "$*" == *'running -q backend'* ]]; then
    if [[ "${fixture_backend_running}" == true ]]; then
      printf '%s\n' cccccccccccc
    fi
    return 0
  fi
  if [[ "${1:-}" == stop && "${2:-}" == backend ]]; then
    fixture_backend_running=false
    printf '%s\n' stop-backend >> "${mutation_log}"
    return 0
  fi
  printf 'remote_compose %s\n' "$*" >> "${mutation_log}"
  return 0
}
docker() {
  if [[ "${1:-}" == inspect ]]; then
    printf '%s\n' "${fixture_running_image}"
    return 0
  fi
  printf 'docker %s\n' "$*" >> "${mutation_log}"
  return 0
}
case "${mode}" in
  pre)
    remote_recover_pre_v126 "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v125:${V125_SOURCE_SHA}" "hookah-v126:${fixture_release}"
    ;;
  post)
    remote_recover_post_v126_stop "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v126:${fixture_release}" "${fixture_v126_image_id}"
    ;;
  *) exit 95 ;;
esac
SH
}

run_real_recovery_transition_fixture() {
  local mode="$1"
  local fixture_root="$2"
  local running_image="${3:-${V126_IMAGE_ID}}"
  local failure_mode="${4:-none}"
  bash -s -- "${CUTOVER_SCRIPT}" "${mode}" "${fixture_root}" "${RELEASE_SHA}" \
    "${running_image}" "${V126_IMAGE_ID}" "${failure_mode}" "${SECRET_CANARY}" <<'SH'
set -Eeuo pipefail
source "$1"
set +u # macOS Bash 3 treats an explicitly empty indexed array as unset under nounset.
eval "$(declare -f remote_recovery_product_off | \
  sed '1s/^remote_recovery_product_off/original_remote_recovery_product_off/')"
fixture_mode="$2"
fixture_root="$3"
fixture_release="$4"
fixture_running_image="$5"
fixture_v126_image_id="$6"
fixture_failure_mode="$7"
fixture_secret_canary="$8"
fixture_backend_id=aaaaaaaaaaaa
fixture_staging="${fixture_root}/staging"
fixture_run_root="${fixture_root}/run-root"
fixture_log="${fixture_root}/commands.log"
backend_state_file="${fixture_root}/backend.state"
backend_image_file="${fixture_root}/backend.image"
restart_state_file="${fixture_root}/restart.state"
start_count_file="${fixture_root}/start.count"
public_state_file="${fixture_root}/public.state"
product_state_file="${fixture_root}/product.state"
mkdir -m 0700 -p "${fixture_staging}" "${fixture_run_root}" "${fixture_staging}/scripts"
printf '%s\n' running > "${backend_state_file}"
printf '%s\n' "${fixture_running_image}" > "${backend_image_file}"
printf '%s\n' unset > "${restart_state_file}"
printf '%s\n' 0 > "${start_count_file}"
printf '%s\n' PRODUCT > "${public_state_file}"
printf '%s\n' 'INITIAL:UNKNOWN:UNKNOWN' > "${product_state_file}"
if [[ "${fixture_failure_mode}" == real-product-off ]]; then
  printf '%s\n' \
    'TELEGRAM_BOT_ENABLED=true' \
    'TELEGRAM_BOT_MODE=long_polling' \
    'TELEGRAM_TRAFFIC_POLICY=V126_SMOKE' \
    'TELEGRAM_ALLOWED_USER_IDS=918273645012345678' \
    'TELEGRAM_ALLOWED_CHAT_IDS=-918273645012345679' \
    'STAGING_MAINTENANCE_MODE=V126_SMOKE' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=918273645012345678' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-918273645012345679' \
    "JWT_SECRET=${fixture_secret_canary}_RECOVERY_JWT" \
    "INIT_DATA_SECRET=${fixture_secret_canary}_RECOVERY_INIT" > "${fixture_staging}/.env"
else
  printf '%s\n' \
    'TELEGRAM_BOT_ENABLED=true' \
    'TELEGRAM_BOT_MODE=long_polling' \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'TELEGRAM_ALLOWED_USER_IDS=' \
    'TELEGRAM_ALLOWED_CHAT_IDS=' \
    'STAGING_MAINTENANCE_MODE=V126_SMOKE' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=918273645012345678' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=-918273645012345679' > \
    "${fixture_staging}/.env"
fi
chmod 0600 "${fixture_staging}/.env"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
  "${fixture_staging}/scripts/check-staging-maintenance-config.sh"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > \
  "${fixture_staging}/scripts/validate-staging-admission.sh"
chmod 0755 "${fixture_staging}/scripts/check-staging-maintenance-config.sh" \
  "${fixture_staging}/scripts/validate-staging-admission.sh"

log_command() { printf '%s\n' "$*" >> "${fixture_log}"; }
remote_initialize_compose() {
  [[ "$1" == "${fixture_staging}" && "$2" == fixture-run && "$3" == "${fixture_release}" ]] ||
    die 'recovery initialize binding mismatch'
  log_command "initialize $1 $2 $3 $4"
  cd "${fixture_staging}"
  if [[ "${fixture_failure_mode}" == real-product-off ||
    "${fixture_failure_mode}" == recovery-source-drift ]]; then
    REMOTE_BOUND_ENV_SHA256="$(remote_hash_file .env)"
  fi
}
remote_require_run_root() {
  [[ "$1" == "${fixture_staging}" && "$2" == fixture-run ]] || die 'recovery run-root binding mismatch'
  log_command "run-root $1 $2"
  printf '%s\n' "${fixture_run_root}"
}
remote_flyway_state() {
  case "${fixture_mode}" in
    pre) log_command 'flyway 125:0:0:0'; printf '%s\n' 125:0:0:0 ;;
    post) log_command 'flyway 126:1:1:0'; printf '%s\n' 126:1:1:0 ;;
    *) die 'unknown real recovery fixture mode' ;;
  esac
}
remote_recovery_ensure_pre_v126_drain() {
  [[ "$(< "${backend_state_file}")" == running ]] || die 'pre-recovery drain lacked candidate backend'
  printf '%s\n' DRAIN > "${public_state_file}"
  log_command 'caddy drain'
}
remote_recovery_ensure_candidate_drain() {
  printf '%s\n' DRAIN > "${public_state_file}"
  log_command 'caddy drain'
}
remote_assert_zero_writer() {
  [[ "$(< "${backend_state_file}")" == stopped ]] || die 'zero-writer ran before backend stop'
  case "${fixture_mode}:$1" in pre:125:0:0|post:126:1:0) : ;; *) die 'zero-writer state mismatch' ;; esac
  log_command "zero-writer $1"
}
remote_recovery_product_off() {
  [[ "$(< "${backend_state_file}")" == stopped ]] || die 'PRODUCT/OFF transition ran before stop'
  if [[ "${fixture_failure_mode}" == real-product-off ||
    "${fixture_failure_mode}" == recovery-source-drift ]]; then
    original_remote_recovery_product_off "$@"
    [[ "${REMOTE_RECOVERY_ENV_BEFORE_SHA256:-}" =~ ^[0-9a-f]{64}$ &&
      "${REMOTE_RECOVERY_ENV_AFTER_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] ||
      die 'real recovery env hashes were not retained after credential cleanup'
    printf '%s\n' 'PRODUCT:OFF:EMPTY' > "${product_state_file}"
    log_command 'product PRODUCT OFF EMPTY'
    return 0
  fi
  cp "${fixture_staging}/.env" "${fixture_run_root}/recovery-env.before"
  chmod 0600 "${fixture_run_root}/recovery-env.before"
  printf '%s\n' \
    'TELEGRAM_BOT_ENABLED=true' \
    'TELEGRAM_BOT_MODE=long_polling' \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'TELEGRAM_ALLOWED_USER_IDS=' \
    'TELEGRAM_ALLOWED_CHAT_IDS=' \
    'STAGING_MAINTENANCE_MODE=OFF' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' > "${fixture_staging}/.env"
  chmod 0600 "${fixture_staging}/.env"
  printf '%s\n' 'PRODUCT:OFF:EMPTY' > "${product_state_file}"
  REMOTE_RECOVERY_ENV_BEFORE_SHA256="$(remote_hash_file "${fixture_run_root}/recovery-env.before")"
  REMOTE_RECOVERY_ENV_AFTER_SHA256="$(remote_hash_file "${fixture_staging}/.env")"
  log_command 'product PRODUCT OFF EMPTY'
}
cp() {
  if [[ "${1:-}" == --preserve=mode,ownership,timestamps ]]; then
    shift
  fi
  if [[ "${fixture_failure_mode}" == recovery-source-drift &&
    "${1:-}" == .env && "${2:-}" == "${fixture_run_root}/recovery-env.before" ]]; then
    printf '%s\n' 'UNRELATED_RECOVERY_SOURCE_DRIFT=1' >> "${fixture_staging}/.env"
    remote_hash_file "${fixture_staging}/.env" > "${fixture_root}/recovery-source-drift.sha256"
  fi
  command cp "$@"
  if [[ "${fixture_failure_mode}" == recovery-source-drift &&
    "${1:-}" == .env && "${2:-}" == "${fixture_run_root}/recovery-env.before" ]]; then
    log_command 'recovery-before-created'
  fi
}
rm() {
  if [[ "${fixture_failure_mode}" == recovery-source-drift && $# == 5 &&
    "$1" == -f && "$2" == -- &&
    "$3" == "${fixture_run_root}/recovery-product-off.env" &&
    "$4" == "${fixture_run_root}/recovery-env.before" &&
    "$5" == "${fixture_run_root}/recovery-env.next" ]]; then
    log_command 'recovery-cleanup-exact'
  fi
  command rm "$@"
}
remote_assert_compose_backend_image() {
  [[ "$1" == "hookah-v125:${V125_SOURCE_SHA}" && "${REMOTE_BACKEND_IMAGE}" == "$1" ]] ||
    die 'pre-recovery Compose image binding mismatch'
  log_command "compose-image $1"
  if [[ "${fixture_failure_mode}" == env-before-create ]]; then
    printf '%s\n' 'UNRELATED_PRE_CREATE_DRIFT=1' >> "${fixture_staging}/.env"
  fi
}
remote_assert_v125_runtime() {
  [[ "$1" == "${fixture_staging}" && "$2" == "hookah-v125:${V125_SOURCE_SHA}" ]] ||
    die 'V125 runtime binding mismatch'
  [[ "$(< "${backend_state_file}")" == running &&
    "$(< "${backend_image_file}")" == "${V125_IMAGE_ID}" &&
    "$(< "${restart_state_file}")" == no:0 &&
    "$(< "${start_count_file}")" == 1 &&
    "$(< "${product_state_file}")" == PRODUCT:OFF:EMPTY ]] ||
    die 'V125 runtime state transition mismatch'
  log_command "runtime-v125 $2"
}
remote_recovery_restore_original_caddy() {
  [[ "$(< "${backend_state_file}")" == running ]] || die 'ordinary Caddy restored before V125 runtime'
  printf '%s\n' LIVE > "${public_state_file}"
  log_command 'caddy restore-original'
  printf '%064d\n' 5
}
remote_compose() {
  case "$*" in
    'stop backend')
      log_command 'compose stop backend'
      [[ "${fixture_failure_mode}" != compose-stop ]] || return 91
      printf '%s\n' stopped > "${backend_state_file}"
      ;;
    'ps --status running -q backend')
      log_command 'compose ps --status running -q backend'
      if [[ "$(< "${backend_state_file}")" == running ]]; then
        printf '%s\n' "${fixture_backend_id}"
      fi
      return 0
      ;;
    'create --force-recreate --no-build --no-deps --pull never backend')
      [[ "${fixture_mode}" == pre && "$(< "${backend_state_file}")" == stopped ]] ||
        die 'Compose create crossed the recovery state boundary'
      log_command 'compose create --force-recreate --no-build --no-deps --pull never backend'
      printf '%s\n' "${V125_IMAGE_ID}" > "${backend_image_file}"
      if [[ "${fixture_failure_mode}" == env-during-create ]]; then
        printf '%s\n' 'UNRELATED_CREATE_DRIFT=1' >> "${fixture_staging}/.env"
      fi
      ;;
    'ps -aq backend')
      log_command 'compose ps -aq backend'
      printf '%s\n' "${fixture_backend_id}"
      ;;
    *) log_command "FORBIDDEN compose $*"; return 97 ;;
  esac
}
docker() {
  case "$*" in
    "image inspect --format {{.Id}} hookah-v125:${V125_SOURCE_SHA}")
      log_command "docker image inspect --format {{.Id}} hookah-v125:${V125_SOURCE_SHA}"
      printf '%s\n' "${V125_IMAGE_ID}"
      ;;
    "inspect --format {{.Image}} ${fixture_backend_id}")
      log_command "docker inspect --format {{.Image}} ${fixture_backend_id}"
      cat "${backend_image_file}"
      ;;
    "inspect --format {{json .Config.Env}} ${fixture_backend_id}")
      log_command "docker inspect --format {{json .Config.Env}} ${fixture_backend_id}"
      if [[ "${fixture_failure_mode}" == wrong-container-env ]]; then
        fixture_container_maintenance_mode=V126_SMOKE
      else
        fixture_container_maintenance_mode=OFF
      fi
      python3 - "${fixture_container_maintenance_mode}" <<'PY'
import json
import sys
maintenance_mode = sys.argv[1]
print(json.dumps([
    "TELEGRAM_BOT_ENABLED=true",
    "TELEGRAM_BOT_MODE=long_polling",
    "TELEGRAM_TRAFFIC_POLICY=PRODUCT",
    "TELEGRAM_ALLOWED_USER_IDS=",
    "TELEGRAM_ALLOWED_CHAT_IDS=",
    f"STAGING_MAINTENANCE_MODE={maintenance_mode}",
    "STAGING_MAINTENANCE_ALLOWED_USER_IDS=",
    "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=",
]))
PY
      ;;
    "update --restart=no ${fixture_backend_id}")
      [[ "${fixture_mode}" == pre && "$(< "${backend_state_file}")" == stopped ]] ||
        die 'restart disable crossed the pre-recovery state boundary'
      log_command "docker update --restart=no ${fixture_backend_id}"
      printf '%s\n' no:0 > "${restart_state_file}"
      ;;
    "inspect --format {{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}} ${fixture_backend_id}")
      log_command "docker inspect --format {{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}} ${fixture_backend_id}"
      cat "${restart_state_file}"
      ;;
    "start ${fixture_backend_id}")
      [[ "${fixture_mode}" == pre && "$(< "${backend_state_file}")" == stopped &&
        "$(< "${restart_state_file}")" == no:0 && "$(< "${start_count_file}")" == 0 ]] ||
        die 'extra or unsafe V125 start attempt'
      log_command "docker start ${fixture_backend_id}"
      printf '%s\n' 1 > "${start_count_file}"
      [[ "${fixture_failure_mode}" != docker-start ]] || return 92
      printf '%s\n' running > "${backend_state_file}"
      ;;
    *) log_command "FORBIDDEN docker $*"; return 98 ;;
  esac
}
sleep() { die 'real recovery fixture entered an unexpected retry'; }
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    python3 - "$3" "$(id -un)" "$(id -gn)" <<'PY'
import os
import stat
import sys
path, user, group = sys.argv[1:]
print(f"{stat.S_IMODE(os.stat(path).st_mode):o}:{user}:{group}")
PY
    return 0
  fi
  command stat "$@"
}

case "${fixture_mode}" in
  pre)
    remote_recover_pre_v126 "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v125:${V125_SOURCE_SHA}" "hookah-v126:${fixture_release}"
    ;;
  post)
    remote_recover_post_v126_stop "${fixture_staging}" fixture-run "${fixture_release}" \
      "hookah-v126:${fixture_release}" "${fixture_v126_image_id}"
    ;;
  *) die 'unknown real recovery transition fixture' ;;
esac
SH
}

assert_real_recovery_transition() {
  local mode="$1"
  local fixture_root="$2"
  python3 - "${mode}" "${fixture_root}" "${RELEASE_SHA}" "${V125_SOURCE_SHA}" <<'PY'
import pathlib
import sys

mode, root_raw, release, v125_sha = sys.argv[1:]
root = pathlib.Path(root_raw)
staging = root_raw.rstrip("/") + "/staging"
backend = "aaaaaaaaaaaa"
if mode == "pre":
    expected = [
        f"initialize {staging} fixture-run {release} hookah-v126:{release}",
        f"run-root {staging} fixture-run",
        "flyway 125:0:0:0",
        "caddy drain",
        "compose stop backend",
        "compose ps --status running -q backend",
        "zero-writer 125:0:0",
        "product PRODUCT OFF EMPTY",
        f"docker image inspect --format {{{{.Id}}}} hookah-v125:{v125_sha}",
        f"compose-image hookah-v125:{v125_sha}",
        "compose create --force-recreate --no-build --no-deps --pull never backend",
        "compose ps -aq backend",
        f"docker inspect --format {{{{.Image}}}} {backend}",
        f"docker inspect --format {{{{json .Config.Env}}}} {backend}",
        f"docker update --restart=no {backend}",
        f"docker inspect --format {{{{.HostConfig.RestartPolicy.Name}}}}:{{{{.RestartCount}}}} {backend}",
        f"docker start {backend}",
        "compose ps --status running -q backend",
        f"runtime-v125 hookah-v125:{v125_sha}",
        "caddy restore-original",
    ]
else:
    expected = [
        f"initialize {staging} fixture-run {release} hookah-v126:{release}",
        f"run-root {staging} fixture-run",
        "flyway 126:1:1:0",
        "caddy drain",
        "compose ps --status running -q backend",
        f"docker inspect --format {{{{.Image}}}} {backend}",
        "compose stop backend",
        "compose ps --status running -q backend",
        "zero-writer 126:1:0",
    ]
actual = (root / "commands.log").read_text(encoding="utf-8").splitlines()
if actual != expected:
    raise SystemExit(f"real {mode} recovery transition mismatch:\nexpected={expected!r}\nactual={actual!r}")
for line in actual:
    if line.startswith("FORBIDDEN "):
        raise SystemExit(f"real {mode} recovery crossed a forbidden command surface: {line}")
if actual.count(f"docker start {backend}") != (1 if mode == "pre" else 0):
    raise SystemExit(f"real {mode} recovery start count mismatch")
if any(line.startswith(("compose up ", "compose start ", "compose restart ", "docker restart ")) for line in actual):
    raise SystemExit(f"real {mode} recovery used a retry/restart command")
PY
}

run_real_full_dr_fixture() {
  local fixture_root="$1"
  local mutation_log="$2"
  local command_log="$3"
  local mode="${4:-direct}"
  bash -s -- "${CUTOVER_SCRIPT}" "${fixture_root}" "${mutation_log}" "${command_log}" \
    "${RELEASE_SHA}" "${mode}" <<'SH'
set -Eeuo pipefail
source "$1"
fixture_root="$2"
mutation_log="$3"
command_log="$4"
fixture_release="$5"
fixture_mode="$6"
fixture_staging="${fixture_root}/staging"
fixture_run_root="${fixture_root}/run-root"
fixture_backup_root="${fixture_root}/backup-root"
mkdir -m 0700 -p "${fixture_staging}" "${fixture_run_root}" "${fixture_backup_root}"
dump="${fixture_backup_root}/quiesced.dump"
inventory="${dump}.pg_restore.list"
printf '%s\n' fixture-list-only-inventory > "${dump}"
cp "${dump}" "${inventory}"
chmod 0600 "${dump}" "${inventory}"
dump_sha="$(remote_hash_file "${dump}")"
inventory_sha="$(remote_hash_file "${inventory}")"
boundary_sha="$(hash_text fixture-dr-boundary)"
post_receipt_sha=NONE
post_proof_sha=NONE
write_post_stop_proof() {
  local classification="$1"
  local proof="${fixture_run_root}/recovery-post-v126-stop.proof"
  printf '%s\n' \
    'run_id=fixture-run' \
    "release_sha=${fixture_release}" \
    'flyway=126:1:1:0' \
    'observed_running_backend_count=1' \
    "image_classification=${classification}" \
    'backend=0' \
    'writers=0' \
    'sessions=0' \
    'prepared=0' \
    'slots=0' \
    'global_v125_count=0' \
    'global_v126_count=0' \
    'public_drain=PASS' \
    'v125_start=REFUSED' \
    'data_or_migration_mutation=NONE' \
    'result=FORWARD_FIX_REQUIRED' > "${proof}"
  chmod 0600 "${proof}"
  printf '%s\n' "$(remote_hash_file "${proof}")" > "${proof}.sha256"
  chmod 0600 "${proof}.sha256"
}
case "${fixture_mode}" in
  direct)
    V126_INTERNAL_REMOTE_PREDECESSOR_STAGE=QUIESCED_BACKUP_REHEARSED
    ;;
  escalated|substituted)
    V126_INTERNAL_REMOTE_PREDECESSOR_STAGE=RECOVERY_POST_V126_STOP
    V126_INTERNAL_REMOTE_PREDECESSOR_HASH=6666666666666666666666666666666666666666666666666666666666666666
    post_receipt_sha="${V126_INTERNAL_REMOTE_PREDECESSOR_HASH}"
    write_post_stop_proof EXACT_V126_STOPPED
    post_proof_sha="$(remote_hash_file "${fixture_run_root}/recovery-post-v126-stop.proof")"
    if [[ "${fixture_mode}" == substituted ]]; then
      write_post_stop_proof UNKNOWN_REFUSED_AND_STOPPED
    fi
    ;;
  *) die 'unknown full-DR fixture mode' ;;
esac

remote_initialize_compose() { :; }
remote_require_run_root() { printf '%s\n' "${fixture_run_root}"; }
remote_backup_root() { printf '%s\n' "${fixture_backup_root}"; }
remote_assert_caddy_drain_marker() { :; }
remote_assert_public_drain() { :; }
remote_assert_zero_writer() { :; }
remote_flyway_state() { printf '%s\n' 126:1:1:0; }
stat() {
  if [[ "${1:-}" == -c && "${2:-}" == '%a:%U:%G' ]]; then
    if [[ -d "$3" ]]; then
      printf '700:%s:%s\n' "$(id -un)" "$(id -gn)"
    else
      printf '600:%s:%s\n' "$(id -un)" "$(id -gn)"
    fi
    return 0
  fi
  command stat "$@"
}
record_forbidden_client() {
  printf '%s %s\n' "$1" "${*:2}" >> "${mutation_log}"
  return 93
}
psql() { record_forbidden_client psql "$@"; }
createdb() { record_forbidden_client createdb "$@"; }
dropdb() { record_forbidden_client dropdb "$@"; }
pg_restore() { record_forbidden_client pg_restore "$@"; }
remote_compose() {
  printf 'remote_compose %s\n' "$*" >> "${command_log}"
  if [[ "$*" == 'exec -T postgres sh -c : "${POSTGRES_USER:?}"; pg_restore --list' ]]; then
    cat
    return 0
  fi
  printf 'remote_compose %s\n' "$*" >> "${mutation_log}"
  return 94
}

remote_verify_full_dr "${fixture_staging}" fixture-run "${fixture_release}" \
  "hookah-v126:${fixture_release}" quiesced "${dump_sha}" "${inventory_sha}" \
  "${boundary_sha}" "${post_proof_sha}"
SH
}

test_recovery_contract() {
  local pre="${TEST_ROOT}/recovery-pre.sh"
  local post="${TEST_ROOT}/recovery-post.sh"
  local dr="${TEST_ROOT}/recovery-dr.sh"
  local mutation_log="${TEST_ROOT}/recovery-mutation.log"
  extract_recovery_block "${CUTOVER_SCRIPT}" PRE_V126 "${pre}"
  extract_recovery_block "${CUTOVER_SCRIPT}" POST_V126_STOP "${post}"
  extract_recovery_block "${CUTOVER_SCRIPT}" FULL_DR_VERIFY "${dr}"
  assert_literals_in_order "${pre}" 'pre-V126 rollback Flyway boundary' \
    "[[ \"\${before_flyway}\" == '125:0:0:0' ]]" \
    'remote_recovery_ensure_pre_v126_drain' \
    'remote_compose stop backend' \
    '[[ "${v125_image_tag}" =~ :${V125_SOURCE_SHA}$ ]]' \
    'remote_assert_compose_backend_image "${v125_image_tag}"' \
    'remote_compose create --force-recreate --no-build --no-deps --pull never backend' \
    'docker update --restart=no "${recovery_container}"' \
    'docker start "${recovery_container}"'
  [[ "$(grep -F -c 'docker start "${recovery_container}"' "${pre}" || true)" == 1 ]] ||
    fail 'pre-V126 recovery must contain exactly one V125 start attempt'
  ! grep -F 'remote_compose up' "${pre}" >/dev/null ||
    fail 'pre-V126 recovery bypasses restart disable with Compose up'
  assert_literals_in_order "${post}" 'post-V126 forward-fix stop boundary' \
    "[[ \"\${flyway}\" == '126:1:1:0' ]]" \
    'remote_recovery_ensure_candidate_drain' \
    "image_classification='UNKNOWN_REFUSED_AND_STOPPED'" \
    'remote_compose stop backend' \
    "remote_assert_zero_writer '126:1:0'" \
    '"image_classification=${image_classification}"' \
    "'result=FORWARD_FIX_REQUIRED'" \
    'remote_verify_post_v126_stop_proof "${run_root}" "${run_id}" "${release_sha}"' \
    'remote_emit_artifact recovery-post-v126-stop'
  ! grep -F 'remote_compose up' "${post}" >/dev/null || fail 'post-V126 stop can start a backend'
  assert_literals_in_order "${dr}" 'full-DR list-only verifier' \
    'remote_assert_public_drain' \
    'remote_assert_zero_writer ANY' \
    'remote_hash_file "${dump}"' \
    'remote_hash_file "${inventory}"' \
    'pg_restore --list' \
    '< "${dump}" > "${generated}"' \
    'restore_performed=false' \
    'result=DR_AUTHORIZATION_REQUIRED'
  python3 - "${dr}" <<'PY'
import re
import sys

text = open(sys.argv[1], "rt", encoding="utf-8").read()
invocations = re.findall(r"(?<![A-Za-z0-9_.-])pg_restore(?=\s)", text)
if len(invocations) != 1 or text.count("pg_restore --list") != 1:
    raise SystemExit("full-DR verifier must contain exactly one list-only pg_restore invocation")
for forbidden in ("--dbname", "createdb ", "dropdb ", "flyway repair", "remote_compose up"):
    if forbidden in text:
        raise SystemExit(f"full-DR verifier contains forbidden operation: {forbidden}")
if "restore_performed=false" not in text or "result=DR_AUTHORIZATION_REQUIRED" not in text:
    raise SystemExit("full-DR verifier lacks the terminal non-restore result")
PY
  expect_success 'full-DR static surface is one exact list-only operation' \
    validate_full_dr_non_restore_surface "${dr}"
  local forbidden_dr="${TEST_ROOT}/forbidden-dr-surface.sh"
  local forbidden_label forbidden_line
  while IFS='|' read -r forbidden_label forbidden_line; do
    printf '%s\n' \
      'remote_compose exec -T postgres sh -c '\''pg_restore --list'\''' \
      "${forbidden_line}" \
      'restore_performed=false' \
      'result=DR_AUTHORIZATION_REQUIRED' > "${forbidden_dr}"
    expect_failure "full-DR scanner rejects ${forbidden_label}" 'full-DR' \
      validate_full_dr_non_restore_surface "${forbidden_dr}"
  done <<'EOF'
psql archive input|psql -d exact_database < selected.dump
created database|createdb --maintenance-db=postgres restored
dropped database|dropdb --maintenance-db=postgres restored
targeted pg_restore|pg_restore --dbname=restored selected.dump
Flyway mutation|flyway repair
Compose start|remote_compose up -d backend
Docker restart|docker restart backend
restore helper|apply_dump selected.dump
split dynamic client|client=ps; client+=ql; "$client" -d exact_database < selected.dump
EOF

  local dr_fixture_root="${TEST_ROOT}/real-full-dr"
  local dr_mutation_log="${TEST_ROOT}/real-full-dr-mutation.log"
  local dr_command_log="${TEST_ROOT}/real-full-dr-command.log"
  expect_success 'real full-DR verifier performs only one list-only inventory operation' \
    run_real_full_dr_fixture "${dr_fixture_root}" "${dr_mutation_log}" "${dr_command_log}"
  [[ ! -s "${dr_mutation_log}" ]] || fail 'real full-DR fixture observed a restore/data/migration mutation'
  [[ "$(wc -l < "${dr_command_log}" | tr -d ' ')" == 1 ]] ||
    fail 'real full-DR fixture executed more than one database command'
  grep -F 'pg_restore --list' "${dr_command_log}" >/dev/null ||
    fail 'real full-DR fixture did not execute its exact list-only inventory command'
  grep -F $'ARTIFACT\trecovery-full-dr-prerequisites\t' "${LAST_OUTPUT}" >/dev/null ||
    fail 'real full-DR fixture did not emit its terminal proof artifact'
  grep -Fx 'post_v126_stop_receipt_sha256=NONE' \
    "${dr_fixture_root}/run-root/recovery-full-dr-prerequisites.proof" >/dev/null ||
    fail 'direct full-DR proof does not bind an explicit NONE post-V126 receipt'
  grep -Fx 'post_v126_stop_proof_sha256=NONE' \
    "${dr_fixture_root}/run-root/recovery-full-dr-prerequisites.proof" >/dev/null ||
    fail 'direct full-DR proof does not bind an explicit NONE post-V126 proof'

  local escalated_root="${TEST_ROOT}/real-full-dr-escalated"
  local escalated_mutation_log="${TEST_ROOT}/real-full-dr-escalated-mutation.log"
  local escalated_command_log="${TEST_ROOT}/real-full-dr-escalated-command.log"
  expect_success 'real escalated full-DR binds exact post-V126 receipt and proof hashes' \
    run_real_full_dr_fixture "${escalated_root}" "${escalated_mutation_log}" \
    "${escalated_command_log}" escalated
  [[ ! -s "${escalated_mutation_log}" ]] ||
    fail 'escalated real full-DR observed a restore/data/migration mutation'
  local escalated_post_proof_sha
  escalated_post_proof_sha="$(sha256_file \
    "${escalated_root}/run-root/recovery-post-v126-stop.proof")"
  grep -Fx \
    'post_v126_stop_receipt_sha256=6666666666666666666666666666666666666666666666666666666666666666' \
    "${escalated_root}/run-root/recovery-full-dr-prerequisites.proof" >/dev/null ||
    fail 'escalated full-DR proof omitted its exact post-V126 receipt hash'
  grep -Fx "post_v126_stop_proof_sha256=${escalated_post_proof_sha}" \
    "${escalated_root}/run-root/recovery-full-dr-prerequisites.proof" >/dev/null ||
    fail 'escalated full-DR proof omitted its exact post-V126 proof hash'

  local substituted_root="${TEST_ROOT}/real-full-dr-substituted"
  local substituted_mutation_log="${TEST_ROOT}/real-full-dr-substituted-mutation.log"
  local substituted_command_log="${TEST_ROOT}/real-full-dr-substituted-command.log"
  expect_failure 'consistently rewritten post-V126 proof and checksum reject before full-DR' \
    'post-V126 stop proof does not match the immutable recovery receipt artifact' \
    run_real_full_dr_fixture "${substituted_root}" "${substituted_mutation_log}" \
    "${substituted_command_log}" substituted
  [[ ! -s "${substituted_mutation_log}" && ! -s "${substituted_command_log}" ]] ||
    fail 'substituted post-V126 proof reached the full-DR database boundary'
  [[ ! -e "${substituted_root}/run-root/recovery-full-dr-prerequisites.proof" ]] ||
    fail 'substituted post-V126 proof produced a full-DR prerequisite proof'
  pass 'recovery blocks are uniquely extractable and bounded'

  local real_pre_root="${TEST_ROOT}/real-pre-v126-transition"
  expect_success 'real pre-V126 recovery performs one controlled V125 start transition' \
    run_real_recovery_transition_fixture pre "${real_pre_root}"
  assert_real_recovery_transition pre "${real_pre_root}"
  [[ "$(< "${real_pre_root}/backend.state")" == running &&
    "$(< "${real_pre_root}/restart.state")" == no:0 &&
    "$(< "${real_pre_root}/start.count")" == 1 &&
    "$(< "${real_pre_root}/public.state")" == LIVE &&
    "$(< "${real_pre_root}/product.state")" == PRODUCT:OFF:EMPTY ]] ||
    fail 'real pre-V126 recovery final state is not exact PRODUCT/OFF one-start V125'
  grep -Fx 'start_command_count=1' \
    "${real_pre_root}/run-root/recovery-pre-v126.proof" >/dev/null ||
    fail 'real pre-V126 proof lacks its one-start claim'
  grep -Fx 'restart_policy=no' \
    "${real_pre_root}/run-root/recovery-pre-v126.proof" >/dev/null ||
    fail 'real pre-V126 proof lacks restart-policy disablement'
  grep -Fx 'restart_count=0' \
    "${real_pre_root}/run-root/recovery-pre-v126.proof" >/dev/null ||
    fail 'real pre-V126 proof lacks zero RestartCount'
  grep -Fx 'result=PRE_V126_ROLLBACK_COMPLETE' \
    "${real_pre_root}/run-root/recovery-pre-v126.proof" >/dev/null ||
    fail 'real pre-V126 proof lacks terminal result'

  local real_post_root="${TEST_ROOT}/real-post-v126-transition"
  expect_success 'real post-V126 recovery performs one terminal stop with no restart path' \
    run_real_recovery_transition_fixture post "${real_post_root}" "${V126_IMAGE_ID}"
  assert_real_recovery_transition post "${real_post_root}"
  [[ "$(< "${real_post_root}/backend.state")" == stopped &&
    "$(< "${real_post_root}/start.count")" == 0 &&
    "$(< "${real_post_root}/public.state")" == DRAIN ]] ||
    fail 'real post-V126 recovery final state is not exact drained terminal stop'
  local post_proof="${real_post_root}/run-root/recovery-post-v126-stop.proof"
  local post_field
  for post_field in \
    'observed_running_backend_count=1' \
    'image_classification=EXACT_V126_STOPPED' \
    'backend=0' \
    'v125_start=REFUSED' \
    'data_or_migration_mutation=NONE' \
    'result=FORWARD_FIX_REQUIRED'; do
    grep -Fx "${post_field}" "${post_proof}" >/dev/null ||
      fail "real post-V126 proof field mismatch: ${post_field}"
  done

  local pre_stop_failure_root="${TEST_ROOT}/real-pre-v126-stop-failure"
  expect_failure 'pre-V126 Compose stop failure cannot reach zero-writer or create/start' '' \
    run_real_recovery_transition_fixture pre "${pre_stop_failure_root}" \
    "${V126_IMAGE_ID}" compose-stop
  [[ "$(< "${pre_stop_failure_root}/backend.state")" == running &&
    ! -e "${pre_stop_failure_root}/run-root/recovery-pre-v126.proof" ]] ||
    fail 'pre-V126 failed Compose stop changed runtime state or sealed a proof'
  ! grep -E 'zero-writer|compose create|docker start' \
    "${pre_stop_failure_root}/commands.log" >/dev/null ||
    fail 'pre-V126 failed Compose stop continued into zero/create/start'

  local post_stop_failure_root="${TEST_ROOT}/real-post-v126-stop-failure"
  expect_failure 'post-V126 Compose stop failure cannot reach zero-writer or terminal proof' '' \
    run_real_recovery_transition_fixture post "${post_stop_failure_root}" \
    "${V126_IMAGE_ID}" compose-stop
  [[ "$(< "${post_stop_failure_root}/backend.state")" == running &&
    ! -e "${post_stop_failure_root}/run-root/recovery-post-v126-stop.proof" ]] ||
    fail 'post-V126 failed Compose stop changed runtime state or sealed a proof'
  ! grep -F 'zero-writer' "${post_stop_failure_root}/commands.log" >/dev/null ||
    fail 'post-V126 failed Compose stop continued into zero-writer proof'

  local pre_env_before_root="${TEST_ROOT}/real-pre-v126-env-before-create"
  expect_failure 'pre-V126 environment drift before Compose create rejects before start' \
    'recovery environment changed before V125 backend creation' \
    run_real_recovery_transition_fixture pre "${pre_env_before_root}" \
    "${V126_IMAGE_ID}" env-before-create
  ! grep -F 'compose create' "${pre_env_before_root}/commands.log" >/dev/null ||
    fail 'pre-V126 pre-create environment drift reached Compose create'
  ! grep -F 'docker start' "${pre_env_before_root}/commands.log" >/dev/null ||
    fail 'pre-V126 pre-create environment drift reached docker start'
  [[ ! -e "${pre_env_before_root}/run-root/recovery-pre-v126.proof" ]] ||
    fail 'pre-V126 pre-create environment drift sealed a recovery proof'

  local pre_env_during_root="${TEST_ROOT}/real-pre-v126-env-during-create"
  expect_failure 'pre-V126 environment drift during Compose create rejects before start' \
    'recovery environment changed during V125 backend creation' \
    run_real_recovery_transition_fixture pre "${pre_env_during_root}" \
    "${V126_IMAGE_ID}" env-during-create
  ! grep -F 'docker start' "${pre_env_during_root}/commands.log" >/dev/null ||
    fail 'pre-V126 create-time environment drift reached docker start'
  [[ ! -e "${pre_env_during_root}/run-root/recovery-pre-v126.proof" ]] ||
    fail 'pre-V126 create-time environment drift sealed a recovery proof'

  local pre_wrong_container_env_root="${TEST_ROOT}/real-pre-v126-wrong-container-env"
  expect_failure 'pre-V126 wrong stopped-container environment rejects before start' \
    'backend container does not have the exact bound traffic/maintenance/poller environment' \
    run_real_recovery_transition_fixture pre "${pre_wrong_container_env_root}" \
    "${V126_IMAGE_ID}" wrong-container-env
  ! grep -F 'docker start' "${pre_wrong_container_env_root}/commands.log" >/dev/null ||
    fail 'pre-V126 wrong stopped-container environment reached docker start'
  [[ ! -e "${pre_wrong_container_env_root}/run-root/recovery-pre-v126.proof" ]] ||
    fail 'pre-V126 wrong stopped-container environment sealed a recovery proof'

  local pre_recovery_source_drift_root="${TEST_ROOT}/real-pre-v126-source-drift"
  expect_failure 'pre-V126 recovery source drift at derivation rejects before create/start' \
    'recovery environment source differs from immutable authority at derivation' \
    run_real_recovery_transition_fixture pre "${pre_recovery_source_drift_root}" \
    "${V126_IMAGE_ID}" recovery-source-drift
  ! grep -E 'compose create|docker start' \
    "${pre_recovery_source_drift_root}/commands.log" >/dev/null ||
    fail 'pre-V126 recovery source drift reached Compose create or docker start'
  [[ ! -e "${pre_recovery_source_drift_root}/run-root/recovery-pre-v126.proof" &&
    ! -e "${pre_recovery_source_drift_root}/run-root/recovery-product-off.env" &&
    ! -e "${pre_recovery_source_drift_root}/run-root/recovery-env.before" &&
    ! -e "${pre_recovery_source_drift_root}/run-root/recovery-env.next" ]] ||
    fail 'pre-V126 recovery source drift retained transform state or sealed a proof'
  [[ "$(grep -F -c 'recovery-before-created' \
      "${pre_recovery_source_drift_root}/commands.log" || true)" == 1 &&
    "$(grep -F -c 'recovery-cleanup-exact' \
      "${pre_recovery_source_drift_root}/commands.log" || true)" == 1 ]] ||
    fail 'pre-V126 recovery source drift did not create then clean exact transform paths once'
  assert_literals_in_order "${pre_recovery_source_drift_root}/commands.log" \
    'pre-V126 recovery source-drift cleanup' \
    'recovery-before-created' 'recovery-cleanup-exact'
  [[ "$(sha256_file "${pre_recovery_source_drift_root}/staging/.env")" == \
    "$(< "${pre_recovery_source_drift_root}/recovery-source-drift.sha256")" ]] ||
    fail 'pre-V126 recovery source drift installed transformed environment bytes'

  local pre_start_failure_root="${TEST_ROOT}/real-pre-v126-start-failure"
  expect_failure 'pre-V126 Docker start failure receives exactly one attempt and no retry' '' \
    run_real_recovery_transition_fixture pre "${pre_start_failure_root}" \
    "${V126_IMAGE_ID}" docker-start
  [[ "$(< "${pre_start_failure_root}/backend.state")" == stopped &&
    "$(< "${pre_start_failure_root}/start.count")" == 1 &&
    ! -e "${pre_start_failure_root}/run-root/recovery-pre-v126.proof" ]] ||
    fail 'failed pre-V126 Docker start retried, ran, or sealed a proof'
  [[ "$(grep -F -c 'docker start aaaaaaaaaaaa' \
    "${pre_start_failure_root}/commands.log" || true)" == 1 ]] ||
    fail 'failed pre-V126 Docker start did not remain exactly one attempt'
  ! grep -F 'runtime-v125' "${pre_start_failure_root}/commands.log" >/dev/null ||
    fail 'failed pre-V126 Docker start continued into runtime proof'

  local recovery_secret_root="${TEST_ROOT}/real-pre-v126-secret-cleanup"
  expect_success 'real pre-V126 PRODUCT/OFF transformation cleans every transient secret copy' \
    run_real_recovery_transition_fixture pre "${recovery_secret_root}" \
    "${V126_IMAGE_ID}" real-product-off
  assert_real_recovery_transition pre "${recovery_secret_root}"
  local recovery_env="${recovery_secret_root}/staging/.env"
  grep -Fx 'TELEGRAM_ALLOWED_USER_IDS=' "${recovery_env}" >/dev/null ||
    fail 'real recovery did not clear Telegram user allowlist'
  grep -Fx 'TELEGRAM_ALLOWED_CHAT_IDS=' "${recovery_env}" >/dev/null ||
    fail 'real recovery did not clear Telegram chat allowlist'
  grep -Fx 'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' "${recovery_env}" >/dev/null ||
    fail 'real recovery did not clear maintenance user allowlist'
  grep -Fx 'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' "${recovery_env}" >/dev/null ||
    fail 'real recovery did not clear maintenance chat allowlist'
  [[ ! -e "${recovery_secret_root}/run-root/recovery-product-off.env" &&
    ! -e "${recovery_secret_root}/run-root/recovery-env.before" &&
    ! -e "${recovery_secret_root}/run-root/recovery-env.next" ]] ||
    fail 'real recovery retained an identity/JWT-bearing environment copy'
  assert_sensitive_value_locations "${recovery_secret_root}" "${recovery_env}" \
    "${SECRET_CANARY}_RECOVERY_JWT" "${SECRET_CANARY}_RECOVERY_INIT"
  assert_sensitive_value_locations "${recovery_secret_root}" '' \
    918273645012345678 -918273645012345679
  assert_tree_has_no_canary "${recovery_secret_root}/run-root"
  rm -f -- "${recovery_env}"
  pass 'real pre/post recovery command spies prove exact no-retry state transitions'

  expect_failure 'pre-V126 rollback refuses live V126' \
    'refuses (before Caddy/backend mutation )?unless Flyway head is exactly V125' \
    run_recovery_refusal_fixture pre 126:1:1:0 '' "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] || fail 'pre-V126 Flyway refusal mutated runtime state'
  expect_failure 'post-V126 stop refuses live V125' \
    'refuses before Caddy/backend mutation unless exact V126 is present' \
    run_recovery_refusal_fixture post 125:0:0:0 '' "${mutation_log}"
  [[ ! -s "${mutation_log}" ]] || fail 'post-V126 Flyway refusal mutated runtime state'
  expect_success 'post-V126 path stops and terminally refuses a running V125 image' \
    run_recovery_refusal_fixture post 126:1:1:0 \
    sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8 \
    "${mutation_log}"
  assert_literals_in_order "${mutation_log}" 'post-V126 V125 terminal stop' \
    candidate-drain stop-backend zero-writer image_classification=V125_REFUSED_AND_STOPPED
  ! grep -E '(^| )(start|up)( |$)' "${mutation_log}" >/dev/null ||
    fail 'post-V126 V125 refusal restarted a backend'

  : > "${mutation_log}"
  expect_success 'post-V126 path stops and terminally refuses an unknown image' \
    run_recovery_refusal_fixture post 126:1:1:0 \
    sha256:9999999999999999999999999999999999999999999999999999999999999999 \
    "${mutation_log}"
  assert_literals_in_order "${mutation_log}" 'post-V126 unknown-image terminal stop' \
    candidate-drain stop-backend zero-writer image_classification=UNKNOWN_REFUSED_AND_STOPPED

  : > "${mutation_log}"
  expect_success 'post-V126 path stops the exact V126 image into terminal forward-fix state' \
    run_recovery_refusal_fixture post 126:1:1:0 "${V126_IMAGE_ID}" "${mutation_log}"
  assert_literals_in_order "${mutation_log}" 'post-V126 exact-image terminal stop' \
    candidate-drain stop-backend zero-writer image_classification=EXACT_V126_STOPPED

  local state
  local fake_bin="${TEST_ROOT}/recovery-fake-bin"
  local remote_log="${TEST_ROOT}/recovery-remote.log"
  local old_path="${PATH}"
  mkdir -m 0700 "${fake_bin}"
  make_success_ssh "${fake_bin}/ssh" recovery-pre-v126
  new_state "${CUTOVER_SCRIPT}" recovery-terminal
  state="${NEW_STATE}"
  seed_chain "${state}" 1
  expect_failure 'pre-V126 recovery token is exact' 'authorization mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 --authorization WRONG
  expect_failure 'post-V126 recovery token is exact' 'authorization mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" post-v126-stop --authorization WRONG
  expect_failure 'full-DR token is exact' 'authorization mismatch' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" verify-full-dr --authorization WRONG
  export HT12P_REMOTE_LOG="${remote_log}"
  PATH="${fake_bin}:${old_path}"
  expect_success 'authorized pre-V126 recovery records a terminal receipt' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  grep -F 'PRE_V126_ROLLBACK_COMPLETE' "${LAST_OUTPUT}" >/dev/null ||
    fail 'pre-V126 terminal result token is missing'
  [[ -f "${state}/run-terminal.json" && -f "${state}/recovery/pre-v126.receipt.json" ]] ||
    fail 'successful recovery lacks terminal state or receipt'
  expect_failure 'terminal recovery forbids stage continuation' 'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" stage --state-dir "${state}" PRE_DRAIN_BACKUP_REHEARSED
  expect_failure 'terminal recovery forbids authorization' 'run is terminal' \
    invoke_script "${CUTOVER_SCRIPT}" authorize --state-dir "${state}" --gate A \
    --authorization AUTHORIZE_V126_CUTOVER_GATE_A
  expect_failure 'terminal recovery cannot be retried' 'already terminal' \
    invoke_script "${CUTOVER_SCRIPT}" recover --state-dir "${state}" pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  PATH="${old_path}"
  unset HT12P_REMOTE_LOG
  assert_state_modes "${state}"
  pass 'recovery tokens, live Flyway refusals, V125 refusal, and terminal behavior verify'
}

test_migration_identity() {
  local migration_root='backend/app/src/main/resources/db/migration'
  local postgresql_path="${migration_root}/postgresql/V126__support_thread_read_message_cursor.sql"
  local h2_path="${migration_root}/h2/V127__support_thread_read_message_cursor.sql"
  local actual
  actual="$(git -C "${REPO_ROOT}" rev-parse "HEAD:${migration_root}")"
  [[ "${actual}" == "${EXPECTED_COMPLETE_MIGRATION_TREE}" ]] || fail 'complete migration tree changed'
  actual="$(git -C "${REPO_ROOT}" rev-parse "HEAD:${migration_root}/postgresql")"
  [[ "${actual}" == "${EXPECTED_POSTGRESQL_MIGRATION_TREE}" ]] || fail 'PostgreSQL migration tree changed'
  actual="$(git -C "${REPO_ROOT}" rev-parse "HEAD:${migration_root}/h2")"
  [[ "${actual}" == "${EXPECTED_H2_MIGRATION_TREE}" ]] || fail 'H2 migration tree changed'
  [[ "$(git -C "${REPO_ROOT}" hash-object "${REPO_ROOT}/${postgresql_path}")" == "${EXPECTED_MIGRATION_BLOB}" ]] ||
    fail 'PostgreSQL V126 blob changed'
  [[ "$(git -C "${REPO_ROOT}" hash-object "${REPO_ROOT}/${h2_path}")" == "${EXPECTED_MIGRATION_BLOB}" ]] ||
    fail 'H2 V127 blob changed'
  [[ "$(sha256_file "${REPO_ROOT}/${postgresql_path}")" == "${EXPECTED_MIGRATION_SHA256}" ]] ||
    fail 'PostgreSQL V126 SHA-256 changed'
  [[ "$(sha256_file "${REPO_ROOT}/${h2_path}")" == "${EXPECTED_MIGRATION_SHA256}" ]] ||
    fail 'H2 V127 SHA-256 changed'
  cmp "${REPO_ROOT}/${postgresql_path}" "${REPO_ROOT}/${h2_path}" >/dev/null ||
    fail 'PostgreSQL V126 and H2 V127 are not byte-identical'
  actual="$(python3 - "${REPO_ROOT}/${postgresql_path}" <<'PY'
import zlib
import sys

with open(sys.argv[1], "rt", encoding="utf-8-sig", newline=None) as handle:
    lines = handle.read().splitlines()
checksum = 0
for line in lines:
    checksum = zlib.crc32(line.encode("utf-8"), checksum)
if checksum >= 2 ** 31:
    checksum -= 2 ** 32
print(checksum)
PY
)"
  [[ "${actual}" == "${EXPECTED_FLYWAY_CHECKSUM}" ]] || fail 'Flyway V126 checksum changed'
  pass 'migration tree/blob/SHA-256/Flyway identities are exact'
}

test_existing_guards() {
  expect_success 'maintenance guard self-test' \
    bash "${SCRIPT_DIR}/check-staging-maintenance-config.sh" --self-test
  expect_success 'image identity guard self-test' \
    bash "${SCRIPT_DIR}/check-staging-image-identity.sh" --self-test \
    "${SCRIPT_DIR}/deploy-staging.sh" "${SCRIPT_DIR}/deploy-staging-controlmaster.sh"
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    expect_success 'admission and Compose PRODUCT/OFF/V126_SMOKE fixtures' \
      bash "${SCRIPT_DIR}/validate-staging-admission.sh" --self-test "${REPO_ROOT}/docker-compose.yml"
  else
    fail 'Docker Compose is required for the admission and maintenance fixtures'
  fi
}

assert_tree_has_no_canary() {
  local root="$1"
  python3 - "${root}" "${SECRET_CANARY}" <<'PY'
import os
import sys

root, canary = sys.argv[1:]
for directory, _, files in os.walk(root):
    for filename in files:
        path = os.path.join(directory, filename)
        if os.path.islink(path):
            continue
        if canary.encode() in open(path, "rb").read():
            raise SystemExit(f"secret canary leaked into durable state: {path}")
PY
}

test_secret_input_redaction() {
  local instrumented="${TEST_ROOT}/v126-cutover-secret-redaction.sh"
  local database_file="${TEST_ROOT}/remote-secrets/database-url"
  local identities_file="${TEST_ROOT}/remote-secrets/maintenance-identities"
  local jwt_file="${TEST_ROOT}/remote-secrets/jwt-secret"
  local init_data_file="${TEST_ROOT}/remote-secrets/telegram-init-data"
  local state output_file
  printf 'postgresql://operator:%s_DATABASE@db.invalid:5432/exact\n' "${SECRET_CANARY}" > \
    "${database_file}"
  printf 'user_id=%s_IDENTITY\nchat_id=-123\n' "${SECRET_CANARY}" > "${identities_file}"
  printf '%s_JWT\n' "${SECRET_CANARY}" > "${jwt_file}"
  printf 'query_id=x&hash=%s_INIT_DATA\n' "${SECRET_CANARY}" > "${init_data_file}"
  chmod 0600 "${database_file}" "${identities_file}" "${jwt_file}" "${init_data_file}"

  make_instrumented_script "${CUTOVER_SCRIPT}" "${instrumented}"
  new_state "${instrumented}" secret-redaction
  state="${NEW_STATE}"
  export JWT_SECRET_FILE="${jwt_file}"
  export TELEGRAM_INIT_DATA_FILE="${init_data_file}"
  expect_success 'restricted DB, identity, JWT, and initData-like inputs are never emitted' \
    invoke_script "${instrumented}" stage --state-dir "${state}" BASELINE_VERIFIED
  output_file="${LAST_OUTPUT}"
  unset JWT_SECRET_FILE TELEGRAM_INIT_DATA_FILE
  assert_tree_has_no_canary "${state}"
  assert_no_canary_file "${output_file}"
  python3 - "${database_file}" "${identities_file}" "${jwt_file}" "${init_data_file}" <<'PY'
import os
import stat
import sys
for path in sys.argv[1:]:
    if stat.S_IMODE(os.stat(path).st_mode) != 0o600:
        raise SystemExit(f"secret fixture is not restricted mode 0600: {path}")
PY
  rm -f -- "${database_file}" "${identities_file}" "${jwt_file}" "${init_data_file}"
  pass 'secret canaries are present only in restricted source inputs, never output or state'
}

assert_no_secret_canary() {
  python3 - "${TEST_ROOT}" "${SECRET_CANARY}" <<'PY'
import os
import sys

root, canary = sys.argv[1:]
hits = []
for directory, _, files in os.walk(root):
    for filename in files:
        path = os.path.join(directory, filename)
        if os.path.islink(path):
            continue
        try:
            payload = open(path, "rb").read()
        except OSError:
            continue
        if canary.encode() in payload:
            hits.append(path)
if hits:
    raise SystemExit("secret canary leaked into test artifacts: " + ", ".join(hits))
PY
  pass 'secret canary never reaches output or run state'
}

main() {
  [[ -f "${CUTOVER_SCRIPT}" && ! -L "${CUTOVER_SCRIPT}" ]] || fail 'cutover sequencer is missing or a symlink'
  command -v python3 >/dev/null 2>&1 || fail 'python3 is required'
  command -v git >/dev/null 2>&1 || fail 'git is required'
  TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/ht12p-v126-cutover-test.XXXXXX")"
  [[ -d "${TEST_ROOT}" && ! -L "${TEST_ROOT}" ]] || fail 'test root creation failed'
  trap cleanup EXIT INT TERM
  mkdir -m 0700 "${TEST_ROOT}/release-worktree" "${TEST_ROOT}/remote-staging" \
    "${TEST_ROOT}/remote-secrets"

  export DATABASE_URL="${SECRET_CANARY}"
  export TELEGRAM_BOT_TOKEN="${SECRET_CANARY}"
  export STAGING_MAINTENANCE_ALLOWED_USER_IDS="${SECRET_CANARY}"
  export STAGING_MAINTENANCE_ALLOWED_CHAT_IDS="${SECRET_CANARY}"

  test_syntax_and_markers
  test_independent_stage_artifact_contract
  test_init_contract
  test_receipt_integrity
  test_gate_and_sequence_contract
  test_static_safety_contract
  test_telegram_cleanup_trap
  test_internal_remote_dispatch_boundary
  test_remote_loader_same_use_body
  test_caddy_ordering_contract
  test_real_caddy_admin_snapshot_cleanup_contract
  test_partial_caddy_activation_recovery
  test_caddy_receipt_binding_contract
  test_caddy_source_same_read_binding
  test_saved_image_archive_binding
  test_remote_image_exact_fd_binding
  test_image_separation_and_mismatch
  test_backend_specific_compose_mapping
  test_explicit_compose_file_selection
  test_inventory_failure_contract
  test_real_backup_rehearsal_cleanup_contract
  test_runtime_poller_and_old_image_gates
  test_runtime_environment_rebind_gates
  test_restart_disabled_single_start
  test_real_baseline_secret_egress
  test_baseline_authority_file_swaps
  test_baseline_authority_envelope_contract
  test_state_aware_environment_authority
  test_sensitive_consumer_secret_redaction
  test_release_git_sanitization
  test_database_target_binding
  test_real_stage_eight_artifact_collection
  test_manual_smoke_evidence
  test_pre_exec_child_binding_barrier
  test_state_lock_recovery_contract
  test_authorization_recovery_lock_order
  test_post_v126_dr_chain
  test_partial_off_post_stop_full_dr_chain
  test_recovery_contract
  test_migration_identity
  test_existing_guards
  test_secret_input_redaction
  assert_no_secret_canary

  printf 'V126 executable cutover contract tests: PASS\n'
}

main "$@"
