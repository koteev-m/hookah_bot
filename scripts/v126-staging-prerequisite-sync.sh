#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
umask 077

readonly CONTRACT_VERSION='HT12R_PREREQUISITE_SYNC_V1'
readonly AUTHORIZATION_TOKEN='AUTHORIZE_V126_STAGING_PREREQUISITE_SYNC'
readonly STAGING_PATH='/opt/hookah-bot'
readonly BACKUP_BASE='/var/backups/hookah-bot'
readonly STAGING_DOMAIN='staging.hookahtootah.club'
readonly EXPECTED_ACTIONS_JOBS='12'
readonly REMOTE_SAFE_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
readonly REMOTE_COMMAND_TIMEOUT='120s'
readonly REMOTE_RECOVERY_TIMEOUT='240s'
readonly CONTROLLER_PID="$$"
readonly COMPLETE_MIGRATION_TREE='765956602de896b4498a956753272a6bc2d2971e'
readonly POSTGRESQL_MIGRATION_TREE='bb2778e26e03e03211eab9f149777313f4a6f24b'
readonly H2_MIGRATION_TREE='07b5ba6ccf25e79c9cc419b9095bb664f2cfae18'
readonly V126_MIGRATION_BLOB='6f39f7d33b1976d0f5eb7a70051bfc5351d12e56'
readonly V126_MIGRATION_SHA256='ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5'
readonly V126_FLYWAY_CHECKSUM='1701638026'

SCRIPT_PATH=''
SCRIPT_DIR=''
HELPER_PATH=''
CHECK_MAP_PATH=''
RELEASE_WORKTREE=''
RELEASE_SHA=''
RELEASE_TREE=''
MAIN_ACTIONS_RUN_ID=''
REMOTE=''
RUN_ID=''
LOCAL_EVIDENCE=''
AUTHORIZATION=''
EXPECTED_PRE_COMPOSE_SHA256=''
EXPECTED_PRE_MAINTENANCE_SHA256=''
EXPECTED_PRE_ENV_SHA256=''
EXPECTED_CADDYFILE_SHA256=''
EXPECTED_CADDY_VERSION=''
V125_SOURCE=''
V125_IMAGE_TAG=''
V125_IMAGE_ID=''
SCRIPT_SHA256=''
HELPER_SHA256=''
CHECK_MAP_SHA256=''
REMOTE_PAYLOAD_SHA256=''
FLYWAY_VERIFIER_SHA256=''
RELEASE_COMPOSE_SHA256=''
RELEASE_MAINTENANCE_SHA256=''
RELEASE_ADMISSION_SHA256=''
CURRENT_ORDINAL='BOOTSTRAP'
CURRENT_NAME='BOOTSTRAP'
CURRENT_EXPECTED='NOT_STARTED'
CURRENT_OUT=''
CURRENT_ERR=''
CURRENT_OUT_TEMP=''
CURRENT_ERR_TEMP=''
LAST_RESULT_SHA256='NONE'
LAST_PASSED='NONE'
WRITE_PHASE_DISPATCHED=0
FAILURE_HANDLED=false
ROLLBACK_ATTEMPTED=false
COMPLETED=false
ACTIVE_CHILD_PID=''
ACTIVE_CHILD_PGID=''

usage() {
  cat <<'USAGE'
Usage:
  scripts/v126-staging-prerequisite-sync.sh \
    --release-worktree <absolute-clean-path> \
    --release-sha <40-hex> --release-tree <40-hex> \
    --main-actions-run-id <positive-integer> --remote <ssh-alias> \
    --run-id <V126-PRE-GATE-A-SYNC-YYYYMMDDTHHMMSSZ> \
    --local-evidence-dir <new-absolute-path> \
    --expected-pre-compose-sha256 <64-hex> \
    --expected-pre-maintenance-sha256 <64-hex> \
    --expected-pre-env-sha256 <64-hex> \
    --expected-caddyfile-sha256 <64-hex> \
    --expected-caddy-version 2.6.2 \
    --v125-source <40-hex> --v125-image-tag <immutable-full-sha-tag> \
    --v125-image-id <sha256:64-hex> \
    --authorization AUTHORIZE_V126_STAGING_PREREQUISITE_SYNC

This command synchronizes only the four pre-Gate-A staging prerequisites and stops.
It never starts Gate A, creates a backup, restarts a service, changes Caddy, loads an
image, executes Flyway, or writes product/database/Telegram state.
USAGE
}

die() {
  printf '%s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command unavailable: $1"
}

require_hex() {
  local value="$1" size="$2" label="$3"
  [[ "${value}" =~ ^[0-9a-f]{${size}}$ ]] || die "invalid ${label}"
}

require_absolute_path() {
  local label="$1" value="$2"
  [[ "${value}" == /* && "${value}" != / && "${value}" != *'/../'* && "${value}" != */.. ]] ||
    die "${label} must be a bounded absolute path"
}

hash_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  else
    shasum -a 256 | awk '{print $1}'
  fi
}

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

local_mode() {
  if [[ "$(uname -s)" == Darwin ]]; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

parse_arguments() {
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --release-worktree) RELEASE_WORKTREE="${2:-}"; shift 2 ;;
      --release-sha) RELEASE_SHA="${2:-}"; shift 2 ;;
      --release-tree) RELEASE_TREE="${2:-}"; shift 2 ;;
      --main-actions-run-id) MAIN_ACTIONS_RUN_ID="${2:-}"; shift 2 ;;
      --remote) REMOTE="${2:-}"; shift 2 ;;
      --run-id) RUN_ID="${2:-}"; shift 2 ;;
      --local-evidence-dir) LOCAL_EVIDENCE="${2:-}"; shift 2 ;;
      --expected-pre-compose-sha256) EXPECTED_PRE_COMPOSE_SHA256="${2:-}"; shift 2 ;;
      --expected-pre-maintenance-sha256) EXPECTED_PRE_MAINTENANCE_SHA256="${2:-}"; shift 2 ;;
      --expected-pre-env-sha256) EXPECTED_PRE_ENV_SHA256="${2:-}"; shift 2 ;;
      --expected-caddyfile-sha256) EXPECTED_CADDYFILE_SHA256="${2:-}"; shift 2 ;;
      --expected-caddy-version) EXPECTED_CADDY_VERSION="${2:-}"; shift 2 ;;
      --v125-source) V125_SOURCE="${2:-}"; shift 2 ;;
      --v125-image-tag) V125_IMAGE_TAG="${2:-}"; shift 2 ;;
      --v125-image-id) V125_IMAGE_ID="${2:-}"; shift 2 ;;
      --authorization) AUTHORIZATION="${2:-}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      *) printf 'unknown argument: %s\n' "$1" >&2; return 2 ;;
    esac
  done
}

validate_arguments() {
  require_absolute_path release-worktree "${RELEASE_WORKTREE}" || return $?
  require_absolute_path local-evidence-dir "${LOCAL_EVIDENCE}" || return $?
  require_hex "${RELEASE_SHA}" 40 'release SHA' || return $?
  require_hex "${RELEASE_TREE}" 40 'release tree' || return $?
  require_hex "${EXPECTED_PRE_COMPOSE_SHA256}" 64 'pre-sync Compose SHA-256' || return $?
  require_hex "${EXPECTED_PRE_MAINTENANCE_SHA256}" 64 'pre-sync maintenance guard SHA-256' || return $?
  require_hex "${EXPECTED_PRE_ENV_SHA256}" 64 'pre-sync environment SHA-256' || return $?
  require_hex "${EXPECTED_CADDYFILE_SHA256}" 64 'Caddyfile SHA-256' || return $?
  require_hex "${V125_SOURCE}" 40 'V125 source SHA' || return $?
  [[ "${MAIN_ACTIONS_RUN_ID}" =~ ^[1-9][0-9]*$ ]] || die 'invalid main Actions run ID'
  [[ "${REMOTE}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die 'invalid SSH alias'
  [[ "${RUN_ID}" =~ ^V126-PRE-GATE-A-SYNC-[0-9]{8}T[0-9]{6}Z$ ]] || die 'invalid run ID'
  [[ "${EXPECTED_CADDY_VERSION}" == 2.6.2 ]] || die 'expected Caddy version must be exactly 2.6.2'
  [[ "${V125_IMAGE_TAG}" =~ ^[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+$ && "${V125_IMAGE_TAG}" == *:"${V125_SOURCE}" ]] ||
    die 'V125 image tag must end with the full V125 source SHA'
  [[ "${V125_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]] || die 'invalid V125 image ID'
  [[ "${AUTHORIZATION}" == "${AUTHORIZATION_TOKEN}" ]] || die 'exact prerequisite-sync authorization is required'
  [[ "${LOCAL_EVIDENCE}" != "${RELEASE_WORKTREE}" && "${LOCAL_EVIDENCE}" != "${RELEASE_WORKTREE}/"* ]] ||
    die 'local evidence must be outside the release worktree'
}

release_git() (
  local variable
  while IFS='=' read -r variable _; do
    case "${variable}" in
      GIT_*) unset "${variable}" ;;
    esac
  done < <(env)
  export GIT_NO_REPLACE_OBJECTS=1
  command git \
    -c core.fsmonitor=false \
    -c core.untrackedCache=false \
    -c core.hooksPath=/dev/null \
    -C "${RELEASE_WORKTREE}" "$@"
)

git_object_stream() {
  release_git cat-file blob "${RELEASE_SHA}:$1"
}

git_object_hash() {
  git_object_stream "$1" | hash_stream
}

git_object_size() {
  git_object_stream "$1" | wc -c | tr -d ' '
}

extract_release_block() {
  local begin="$1" end="$2"
  git_object_stream scripts/v126-staging-prerequisite-sync.sh | python3 -c '
import sys
begin = sys.argv[1].encode()
end = sys.argv[2].encode()
raw = sys.stdin.buffer.read()
lines = raw.splitlines(keepends=True)
begin_rows = [i for i, line in enumerate(lines) if line.rstrip(b"\r\n") == begin]
end_rows = [i for i, line in enumerate(lines) if line.rstrip(b"\r\n") == end]
if len(begin_rows) != 1 or len(end_rows) != 1 or begin_rows[0] >= end_rows[0]:
    raise SystemExit("tracked payload markers are missing, duplicated or reversed")
payload = b"".join(lines[begin_rows[0] + 1:end_rows[0]])
if not payload.startswith(b"#!/usr/bin/env bash\n") or not payload.endswith(b"\n"):
    raise SystemExit("tracked payload boundary is malformed")
sys.stdout.buffer.write(payload)
' "$begin" "$end"
}

remote_loader_source() {
  printf '%s' 'import hashlib,subprocess,sys
mode,expected=sys.argv[1:3]
raw=sys.stdin.buffer.read()
if not raw or hashlib.sha256(raw).hexdigest()!=expected:
    raise SystemExit(96)
if mode=="verify":
    print("STREAM=EXACT_BYTES_VERIFIED")
    raise SystemExit(0)
if mode!="run":
    raise SystemExit(97)
completed=subprocess.run(["/bin/bash","-s","--"]+sys.argv[3:],input=raw)
raise SystemExit(completed.returncode)
'
}

remote_receiver_source() {
  printf '%s' 'import hashlib,os,re,stat,sys
base,run_id,relative,expected_size,expected_sha=sys.argv[1:]
allowed={"source/orchestrator.sh","source/helper.py","source/checks.tsv","release-inputs/docker-compose.yml","release-inputs/check-staging-maintenance-config.sh","release-inputs/validate-staging-admission.sh"}
if relative not in allowed or not re.fullmatch(r"V126-PRE-GATE-A-SYNC-[0-9]{8}T[0-9]{6}Z",run_id):
    raise SystemExit(91)
root=os.path.join(base,run_id)
root_info=os.lstat(root)
if not stat.S_ISDIR(root_info.st_mode) or stat.S_IMODE(root_info.st_mode)!=0o700:
    raise SystemExit(92)
target=os.path.join(root,*relative.split("/"))
parent=os.path.dirname(target)
parent_info=os.lstat(parent)
if not stat.S_ISDIR(parent_info.st_mode):
    raise SystemExit(93)
raw=sys.stdin.buffer.read()
if len(raw)!=int(expected_size) or hashlib.sha256(raw).hexdigest()!=expected_sha:
    raise SystemExit(94)
temporary=os.path.join(parent,f".{os.path.basename(target)}.{os.getpid()}.tmp")
flags=os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,"O_NOFOLLOW",0)
try:
    fd=os.open(temporary,flags,0o400)
    try:
        view=memoryview(raw)
        while view:
            written=os.write(fd,view)
            if written<=0: raise OSError("short source transfer write")
            view=view[written:]
        os.fsync(fd)
        os.fchmod(fd,0o400)
    finally:
        os.close(fd)
    if os.path.lexists(target):
        raise SystemExit(95)
    if sys.platform.startswith("linux"):
        import ctypes
        libc=ctypes.CDLL(None,use_errno=True)
        if libc.renameat2(-100,os.fsencode(temporary),-100,os.fsencode(target),1)!=0:
            error=ctypes.get_errno(); raise OSError(error,os.strerror(error),target)
    elif sys.platform=="darwin":
        import ctypes
        libc=ctypes.CDLL(None,use_errno=True)
        if libc.renamex_np(os.fsencode(temporary),os.fsencode(target),4)!=0:
            error=ctypes.get_errno(); raise OSError(error,os.strerror(error),target)
    else:
        raise SystemExit(95)
    directory_fd=os.open(parent,os.O_RDONLY|getattr(os,"O_DIRECTORY",0))
    try: os.fsync(directory_fd)
    finally: os.close(directory_fd)
finally:
    if os.path.lexists(temporary): os.unlink(temporary)
info=os.lstat(target)
if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode)!=0o400:
    raise SystemExit(95)
print("SOURCE_TRANSFER=EXACT_BYTES_VERIFIED")
'
}

remote_context() {
  printf '%s\n' \
    "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${REMOTE_PAYLOAD_SHA256}" \
    "${FLYWAY_VERIFIER_SHA256}" "${HELPER_SHA256}" "${CHECK_MAP_SHA256}" \
    "${RELEASE_COMPOSE_SHA256}" "${RELEASE_MAINTENANCE_SHA256}" "${RELEASE_ADMISSION_SHA256}" \
    "${EXPECTED_PRE_COMPOSE_SHA256}" "${EXPECTED_PRE_MAINTENANCE_SHA256}" "${EXPECTED_PRE_ENV_SHA256}" \
    "${EXPECTED_CADDYFILE_SHA256}" "${EXPECTED_CADDY_VERSION}" "${V125_SOURCE}" \
    "${V125_IMAGE_TAG}" "${V125_IMAGE_ID}"
}

remote_shell_quote() {
  local value="$1"
  value=${value//\'/\'\\\'\'}
  printf "'%s'" "${value}"
}

remote_ssh() {
  local remote_command='' argument quoted
  for argument in "$@"; do
    quoted="$(remote_shell_quote "${argument}")" || return $?
    if [[ -n "${remote_command}" ]]; then remote_command="${remote_command} "; fi
    remote_command="${remote_command}${quoted}"
  done
  ssh -- "${REMOTE}" "${remote_command}"
}

remote_python() {
  local command_timeout="$1" program="$2"
  shift 2
  if [[ "${V126_PREREQ_FIXTURE_MODE:-0}" == 1 ]]; then
    remote_ssh sudo /usr/bin/env -i \
      PATH="${V126_PREREQ_FIXTURE_MOCK_PATH:?}" \
      V126_PREREQ_REMOTE_FIXTURE_ROOT="${V126_PREREQ_FIXTURE_ROOT:?}" \
      V126_PREREQ_REMOTE_FIXTURE_TOKEN=HT12R_LOCAL_FIXTURE_ONLY \
      V126_FIXTURE_MOCK_LOG="${V126_FIXTURE_MOCK_LOG:?}" \
      V126_FIXTURE_CADDY_OUTPUT="${V126_FIXTURE_CADDY_OUTPUT-2.6.2}" \
      V126_FIXTURE_RUNTIME_ENV_JSON="${V126_FIXTURE_RUNTIME_ENV_JSON:-}" \
      V126_PREREQ_FIXTURE_FAIL_ACTION="${V126_PREREQ_FIXTURE_FAIL_ACTION:-}" \
      V126_PREREQ_FIXTURE_FAIL_CHECK="${V126_PREREQ_FIXTURE_FAIL_CHECK:-}" \
      V126_PREREQ_FIXTURE_FAIL_WRITE_POSITION="${V126_PREREQ_FIXTURE_FAIL_WRITE_POSITION:-}" \
      V126_PREREQ_FIXTURE_FAIL_STATUS="${V126_PREREQ_FIXTURE_FAIL_STATUS:-}" \
      V126_PREREQ_FIXTURE_ROLLBACK_FAIL="${V126_PREREQ_FIXTURE_ROLLBACK_FAIL:-0}" \
      V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK="${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" \
      V126_FIXTURE_CURL_HEALTH_ERROR="${V126_FIXTURE_CURL_HEALTH_ERROR:-0}" \
      V126_FIXTURE_HEALTH_HEADERS_ERROR="${V126_FIXTURE_HEALTH_HEADERS_ERROR:-0}" \
      V126_FIXTURE_CURL_VERSION_ERROR="${V126_FIXTURE_CURL_VERSION_ERROR:-0}" \
      V126_FIXTURE_DB_OUTPUT_ERROR="${V126_FIXTURE_DB_OUTPUT_ERROR:-0}" \
      V126_FIXTURE_DOCKER_LOGS_ERROR="${V126_FIXTURE_DOCKER_LOGS_ERROR:-0}" \
      V126_FIXTURE_SS_ERROR="${V126_FIXTURE_SS_ERROR:-0}" \
      V126_FIXTURE_TLS13_CLIENT_ERROR="${V126_FIXTURE_TLS13_CLIENT_ERROR:-0}" \
      V126_PREREQ_FIXTURE_TAMPER_FIRST_FAILURE_MODE="${V126_PREREQ_FIXTURE_TAMPER_FIRST_FAILURE_MODE:-0}" \
      V126_PREREQ_FIXTURE_TAMPER_WRITE_MARKER="${V126_PREREQ_FIXTURE_TAMPER_WRITE_MARKER:-}" \
      V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE="${V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE:-}" \
      V126_PREREQ_FIXTURE_CONTROLLER_PID="${CONTROLLER_PID}" \
      timeout --signal=TERM --kill-after=10s "${command_timeout}" \
      python3 -c "${program}" "$@"
  else
    remote_ssh sudo /usr/bin/env -i PATH="${REMOTE_SAFE_PATH}" \
      timeout --signal=TERM --kill-after=10s "${command_timeout}" \
      python3 -c "${program}" "$@"
  fi
}

remote_payload_call() {
  local action="$1" loader command_timeout="${REMOTE_COMMAND_TIMEOUT}"
  shift
  case "${action}" in controller-failure|rollback|verify-restored) command_timeout="${REMOTE_RECOVERY_TIMEOUT}" ;; esac
  loader="$(remote_loader_source)"
  extract_release_block '# V126_PREREQ_REMOTE_PAYLOAD_BEGIN' '# V126_PREREQ_REMOTE_PAYLOAD_END' |
    remote_python "${command_timeout}" "${loader}" run "${REMOTE_PAYLOAD_SHA256}" "${action}" \
      "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${REMOTE_PAYLOAD_SHA256}" \
      "${FLYWAY_VERIFIER_SHA256}" "${HELPER_SHA256}" "${CHECK_MAP_SHA256}" \
      "${RELEASE_COMPOSE_SHA256}" "${RELEASE_MAINTENANCE_SHA256}" "${RELEASE_ADMISSION_SHA256}" \
      "${EXPECTED_PRE_COMPOSE_SHA256}" "${EXPECTED_PRE_MAINTENANCE_SHA256}" "${EXPECTED_PRE_ENV_SHA256}" \
      "${EXPECTED_CADDYFILE_SHA256}" "${EXPECTED_CADDY_VERSION}" "${V125_SOURCE}" \
      "${V125_IMAGE_TAG}" "${V125_IMAGE_ID}" "$@"
}

remote_verify_payload_stream() {
  local loader
  loader="$(remote_loader_source)"
  extract_release_block '# V126_PREREQ_REMOTE_PAYLOAD_BEGIN' '# V126_PREREQ_REMOTE_PAYLOAD_END' |
    remote_python "${REMOTE_COMMAND_TIMEOUT}" "${loader}" verify "${REMOTE_PAYLOAD_SHA256}"
}

remote_run_flyway_verifier() {
  local loader
  loader="$(remote_loader_source)"
  extract_release_block '# V126_PREREQ_FLYWAY_VERIFIER_BEGIN' '# V126_PREREQ_FLYWAY_VERIFIER_END' |
    remote_python "${REMOTE_COMMAND_TIMEOUT}" "${loader}" run "${FLYWAY_VERIFIER_SHA256}" "${V125_IMAGE_TAG}"
}

remote_verify_flyway_stream() {
  local loader
  loader="$(remote_loader_source)"
  extract_release_block '# V126_PREREQ_FLYWAY_VERIFIER_BEGIN' '# V126_PREREQ_FLYWAY_VERIFIER_END' |
    remote_python "${REMOTE_COMMAND_TIMEOUT}" "${loader}" verify "${FLYWAY_VERIFIER_SHA256}"
}

remote_receive_stream() {
  local relative="$1" size="$2" sha="$3" receiver base
  receiver="$(remote_receiver_source)"
  base="${BACKUP_BASE}"
  if [[ "${V126_PREREQ_FIXTURE_MODE:-0}" == 1 ]]; then base="${V126_PREREQ_FIXTURE_ROOT:?}/backups"; fi
  remote_python "${REMOTE_COMMAND_TIMEOUT}" "${receiver}" "${base}" "${RUN_ID}" "${relative}" "${size}" "${sha}"
}

transfer_release_object() {
  local repository_path="$1" relative="$2" size sha fault
  size="$(git_object_size "${repository_path}")"
  sha="$(git_object_hash "${repository_path}")"
  fault="${V126_PREREQ_FIXTURE_TRANSFER_FAULT:-}"
  if [[ "${fault}" == "zero:${relative}" ]]; then
    printf '' | remote_receive_stream "${relative}" "${size}" "${sha}"
  elif [[ "${fault}" == "partial:${relative}" ]]; then
    git_object_stream "${repository_path}" | head -c 7 | remote_receive_stream "${relative}" "${size}" "${sha}"
  else
    git_object_stream "${repository_path}" | remote_receive_stream "${relative}" "${size}" "${sha}"
  fi
}

prepare_local_evidence() {
  [[ ! -e "${LOCAL_EVIDENCE}" && ! -L "${LOCAL_EVIDENCE}" ]] || die 'local evidence path already exists'
  mkdir "${LOCAL_EVIDENCE}" || die 'local evidence directory allocation failed'
  chmod 0700 "${LOCAL_EVIDENCE}" || die 'local evidence directory mode failed'
  mkdir "${LOCAL_EVIDENCE}/checkpoints" "${LOCAL_EVIDENCE}/raw" || die 'local evidence child allocation failed'
  chmod 0700 "${LOCAL_EVIDENCE}/checkpoints" "${LOCAL_EVIDENCE}/raw" || die 'local evidence child mode failed'
}

verify_bootstrap_sources() {
  [[ -d "${RELEASE_WORKTREE}" && ! -L "${RELEASE_WORKTREE}" ]] || die 'release worktree is unavailable or a symlink'
  [[ -f "${SCRIPT_PATH}" && ! -L "${SCRIPT_PATH}" && "$(local_mode "${SCRIPT_PATH}")" == 755 ]] ||
    die 'orchestrator must be a regular mode-0755 file'
  [[ -f "${HELPER_PATH}" && ! -L "${HELPER_PATH}" && "$(local_mode "${HELPER_PATH}")" == 755 ]] ||
    die 'helper must be a regular mode-0755 file'
  [[ -f "${CHECK_MAP_PATH}" && ! -L "${CHECK_MAP_PATH}" && "$(local_mode "${CHECK_MAP_PATH}")" == 644 ]] ||
    die 'check map must be a regular mode-0644 file'
}

verify_source_identity() {
  SCRIPT_SHA256="$(git_object_hash scripts/v126-staging-prerequisite-sync.sh)" || die 'orchestrator Git object could not be hashed'
  HELPER_SHA256="$(git_object_hash scripts/v126-staging-prerequisite-sync-helper.py)" || die 'helper Git object could not be hashed'
  CHECK_MAP_SHA256="$(git_object_hash scripts/v126-staging-prerequisite-sync-checks.tsv)" || die 'check map Git object could not be hashed'
  [[ "$(hash_file "${SCRIPT_PATH}")" == "${SCRIPT_SHA256}" ]] || die 'executing orchestrator differs from release Git object'
  [[ "$(hash_file "${HELPER_PATH}")" == "${HELPER_SHA256}" ]] || die 'executing helper differs from release Git object'
  [[ "$(hash_file "${CHECK_MAP_PATH}")" == "${CHECK_MAP_SHA256}" ]] || die 'check map differs from release Git object'
  REMOTE_PAYLOAD_SHA256="$(extract_release_block '# V126_PREREQ_REMOTE_PAYLOAD_BEGIN' '# V126_PREREQ_REMOTE_PAYLOAD_END' | hash_stream)" || die 'remote payload could not be hashed'
  FLYWAY_VERIFIER_SHA256="$(extract_release_block '# V126_PREREQ_FLYWAY_VERIFIER_BEGIN' '# V126_PREREQ_FLYWAY_VERIFIER_END' | hash_stream)" || die 'Flyway verifier could not be hashed'
  printf '%s\n' 'SOURCE_IDENTITY=EXACT_RELEASE_OBJECTS'
}

verify_tools_and_arguments() {
  local command
  for command in bash git gh ssh python3 awk sed wc tr uname stat tail chmod mkdir; do
    require_cmd "${command}" || return $?
  done
  if ! command -v sha256sum >/dev/null 2>&1; then require_cmd shasum || return $?; fi
  [[ "${BASH_VERSINFO[0]}" -ge 3 ]] || die 'Bash 3.2 or newer is required'
  validate_arguments || return $?
  printf '%s\n' 'TOOLS_AND_ARGUMENTS=PASS'
}

validate_actions_json() {
  python3 - "$1" "${RELEASE_SHA}" "${MAIN_ACTIONS_RUN_ID}" "${EXPECTED_ACTIONS_JOBS}" <<'PY'
import json
import sys
path, release_sha, run_id, expected_jobs = sys.argv[1:]
with open(path, "rt", encoding="utf-8") as handle:
    document = json.load(handle)
required = {
    "databaseId": int(run_id),
    "name": "CI",
    "event": "push",
    "headBranch": "main",
    "headSha": release_sha,
    "attempt": 1,
    "status": "completed",
    "conclusion": "success",
}
for key, expected in required.items():
    if document.get(key) != expected:
        raise SystemExit(f"main Actions mismatch: {key}")
jobs = document.get("jobs")
if not isinstance(jobs, list) or len(jobs) != int(expected_jobs):
    raise SystemExit("main Actions job cardinality mismatch")
names = [job.get("name") for job in jobs]
if len(set(names)) != len(names) or any(job.get("status") != "completed" or job.get("conclusion") != "success" for job in jobs):
    raise SystemExit("main Actions jobs are not distinct completed successes")
PY
}

flyway_checksum_from_object() {
  git_object_stream "$1" | python3 -c 'import sys,zlib
value=0
for line in sys.stdin.buffer.read().splitlines(): value=zlib.crc32(line,value)
print(value if value < 2**31 else value-2**32)'
}

verify_release_identity() {
  local worktree_status
  worktree_status="$(release_git status --porcelain=v1 --untracked-files=normal)" || die 'release cleanliness could not be determined'
  [[ -z "${worktree_status}" ]] || die 'release worktree is not clean'
  [[ "$(release_git rev-parse --verify HEAD)" == "${RELEASE_SHA}" ]] || die 'release worktree HEAD mismatch'
  [[ "$(release_git rev-parse --verify "${RELEASE_SHA}^{tree}")" == "${RELEASE_TREE}" ]] || die 'release tree mismatch'
  release_git fetch --no-tags origin main || die 'origin/main fetch failed'
  [[ "$(release_git rev-parse --verify origin/main)" == "${RELEASE_SHA}" ]] || die 'fresh origin/main mismatch'
  [[ "$(release_git rev-parse --verify "${RELEASE_SHA}^{tree}")" == "${RELEASE_TREE}" ]] || die 'release tree mismatch after fetch'
  local actions_file="${LOCAL_EVIDENCE}/raw/main-actions.json"
  [[ ! -e "${actions_file}" && ! -L "${actions_file}" ]] || die 'main Actions evidence target already exists'
  gh run view "${MAIN_ACTIONS_RUN_ID}" --json databaseId,name,event,headBranch,headSha,attempt,status,conclusion,jobs |
    python3 "${HELPER_PATH}" exclusive-stdin "${actions_file}" 0600 >/dev/null || die 'main Actions evidence collection failed'
  validate_actions_json "${actions_file}" || return $?
  local migration_root='backend/app/src/main/resources/db/migration'
  local pg_path="${migration_root}/postgresql/V126__support_thread_read_message_cursor.sql"
  local h2_path="${migration_root}/h2/V127__support_thread_read_message_cursor.sql"
  [[ "$(release_git rev-parse --verify "${RELEASE_SHA}:${migration_root}")" == "${COMPLETE_MIGRATION_TREE}" ]] || die 'complete migration tree mismatch'
  [[ "$(release_git rev-parse --verify "${RELEASE_SHA}:${migration_root}/postgresql")" == "${POSTGRESQL_MIGRATION_TREE}" ]] || die 'PostgreSQL migration tree mismatch'
  [[ "$(release_git rev-parse --verify "${RELEASE_SHA}:${migration_root}/h2")" == "${H2_MIGRATION_TREE}" ]] || die 'H2 migration tree mismatch'
  [[ "$(release_git rev-parse --verify "${RELEASE_SHA}:${pg_path}")" == "${V126_MIGRATION_BLOB}" ]] || die 'PostgreSQL V126 migration blob mismatch'
  [[ "$(release_git rev-parse --verify "${RELEASE_SHA}:${h2_path}")" == "${V126_MIGRATION_BLOB}" ]] || die 'H2 V126 migration blob mismatch'
  [[ "$(git_object_hash "${pg_path}")" == "${V126_MIGRATION_SHA256}" ]] || die 'PostgreSQL V126 migration bytes mismatch'
  [[ "$(git_object_hash "${h2_path}")" == "${V126_MIGRATION_SHA256}" ]] || die 'H2 V126 migration bytes mismatch'
  [[ "$(flyway_checksum_from_object "${pg_path}")" == "${V126_FLYWAY_CHECKSUM}" ]] || die 'V126 Flyway checksum mismatch'
  printf '%s\n' "RELEASE_IDENTITY=PASS;ACTIONS=${EXPECTED_ACTIONS_JOBS}/${EXPECTED_ACTIONS_JOBS};MIGRATIONS=EXACT"
}

initialize_release_input_hashes() {
  RELEASE_COMPOSE_SHA256="$(git_object_hash docker-compose.yml)" || die 'release Compose input could not be hashed'
  RELEASE_MAINTENANCE_SHA256="$(git_object_hash scripts/check-staging-maintenance-config.sh)" || die 'release maintenance guard could not be hashed'
  RELEASE_ADMISSION_SHA256="$(git_object_hash scripts/validate-staging-admission.sh)" || die 'release admission guard could not be hashed'
}

verify_release_inputs() {
  [[ "$(git_object_hash docker-compose.yml)" == "${RELEASE_COMPOSE_SHA256}" ]] || die 'release Compose input changed'
  [[ "$(git_object_hash scripts/check-staging-maintenance-config.sh)" == "${RELEASE_MAINTENANCE_SHA256}" ]] || die 'release maintenance guard input changed'
  [[ "$(git_object_hash scripts/validate-staging-admission.sh)" == "${RELEASE_ADMISSION_SHA256}" ]] || die 'release admission guard input changed'
  local manifest="${LOCAL_EVIDENCE}/release-inputs.tsv"
  {
    printf 'repository_path\tgit_blob\tsize\tsha256\n'
    local path
    for path in docker-compose.yml scripts/check-staging-maintenance-config.sh scripts/validate-staging-admission.sh; do
      printf '%s\t%s\t%s\t%s\n' "${path}" "$(release_git rev-parse --verify "${RELEASE_SHA}:${path}")" "$(git_object_size "${path}")" "$(git_object_hash "${path}")"
    done
  } | python3 "${HELPER_PATH}" exclusive-stdin "${manifest}" 0400 >/dev/null || die 'release input manifest write failed'
  printf '%s\n' 'RELEASE_INPUTS=EXACT_GIT_OBJECT_BYTES'
}

checkpoint_start_local() {
  local ordinal="$1" name="$2" expected="$3"
  python3 "${HELPER_PATH}" checkpoint-start \
    "${LOCAL_EVIDENCE}/checkpoints/${ordinal}-${name}.started.json" \
    "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${expected}" "${LAST_RESULT_SHA256}"
}

checkpoint_finish_local() {
  local ordinal="$1" name="$2" expected="$3" status="$4" state="$5"
  local suffix
  suffix="$(printf '%s' "${state}" | tr '[:upper:]' '[:lower:]')"
  local target="${LOCAL_EVIDENCE}/checkpoints/${ordinal}-${name}.${suffix}.json"
  python3 "${HELPER_PATH}" checkpoint-finish \
    "${target}" "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" \
    "${expected}" "${LAST_RESULT_SHA256}" "${LOCAL_EVIDENCE}/checkpoints/${ordinal}-${name}.started.json" \
    "${status}" "${CURRENT_OUT}" "${CURRENT_ERR}" "${state}" || return $?
  if [[ "${state}" == PASSED ]]; then
    LAST_RESULT_SHA256="$(hash_file "${target}")" || return $?
  fi
}

persist_local_failure() {
  local status="$1" signal_name="$2"
  [[ ! -e "${LOCAL_EVIDENCE}/first-failure.json" && ! -L "${LOCAL_EVIDENCE}/first-failure.json" ]] || return 0
  local out_sha err_sha
  out_sha="$(hash_file "${CURRENT_OUT}")"; err_sha="$(hash_file "${CURRENT_ERR}")"
  python3 "${HELPER_PATH}" first-failure "${LOCAL_EVIDENCE}/first-failure.json" \
    "${RUN_ID}" "${CURRENT_ORDINAL}" "${CURRENT_NAME}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" \
    "${status}" "${signal_name}" "${LAST_RESULT_SHA256}" "${out_sha}" "${err_sha}" "${LAST_PASSED}"
}

seal_local_captures() {
  if [[ -n "${CURRENT_OUT_TEMP}" && -f "${CURRENT_OUT_TEMP}" && ! -L "${CURRENT_OUT_TEMP}" ]]; then
    python3 "${HELPER_PATH}" seal-capture "${CURRENT_OUT_TEMP}" "${CURRENT_OUT}" 0600 >/dev/null || return $?
  fi
  if [[ -n "${CURRENT_ERR_TEMP}" && -f "${CURRENT_ERR_TEMP}" && ! -L "${CURRENT_ERR_TEMP}" ]]; then
    python3 "${HELPER_PATH}" seal-capture "${CURRENT_ERR_TEMP}" "${CURRENT_ERR}" 0600 >/dev/null || return $?
  fi
  [[ -f "${CURRENT_OUT}" && ! -L "${CURRENT_OUT}" && -f "${CURRENT_ERR}" && ! -L "${CURRENT_ERR}" ]]
}

run_phase() {
  local ordinal="$1" name="$2" expected="$3"
  shift 3
  CURRENT_ORDINAL="${ordinal}"
  CURRENT_NAME="${name}"
  CURRENT_EXPECTED="${expected}"
  CURRENT_OUT="${LOCAL_EVIDENCE}/raw/${ordinal}-${name}.stdout"
  CURRENT_ERR="${LOCAL_EVIDENCE}/raw/${ordinal}-${name}.stderr"
  CURRENT_OUT_TEMP="${LOCAL_EVIDENCE}/raw/.${ordinal}-${name}.stdout.$$.tmp"
  CURRENT_ERR_TEMP="${LOCAL_EVIDENCE}/raw/.${ordinal}-${name}.stderr.$$.tmp"
  printf '' | python3 "${HELPER_PATH}" exclusive-stdin "${CURRENT_OUT_TEMP}" 0600 >/dev/null || die 'local stdout capture allocation failed'
  printf '' | python3 "${HELPER_PATH}" exclusive-stdin "${CURRENT_ERR_TEMP}" 0600 >/dev/null || die 'local stderr capture allocation failed'
  checkpoint_start_local "${ordinal}" "${name}" "${expected}" || die 'local phase-start checkpoint failed'
  local status=0
  set -m
  if [[ "${V126_PREREQ_FIXTURE_FAIL_LOCAL_PHASE:-}" == "${name}" ]]; then
    (exit "${V126_PREREQ_FIXTURE_FAIL_STATUS:-71}") >> "${CURRENT_OUT_TEMP}" 2>> "${CURRENT_ERR_TEMP}" &
  else
    (set -euo pipefail; trap 'exit 130' INT; trap 'exit 143' TERM; "$@") >> "${CURRENT_OUT_TEMP}" 2>> "${CURRENT_ERR_TEMP}" &
  fi
  ACTIVE_CHILD_PID=$!
  ACTIVE_CHILD_PGID="${ACTIVE_CHILD_PID}"
  set +m
  set +e
  wait "${ACTIVE_CHILD_PID}"
  status=$?
  set -e
  ACTIVE_CHILD_PID=''
  ACTIVE_CHILD_PGID=''
  if [[ "${status}" == 0 && "$(tail -n 1 "${CURRENT_OUT_TEMP}")" != "${expected}" ]]; then
    printf '%s\n' 'sanitized result mismatch' >> "${CURRENT_ERR_TEMP}"
    status=78
  fi
  seal_local_captures || die 'local capture sealing failed'
  if [[ "${status}" == 0 ]]; then
    checkpoint_finish_local "${ordinal}" "${name}" "${expected}" 0 PASSED || die 'local passed checkpoint failed'
    LAST_PASSED="${ordinal}:${name}"
    return 0
  fi
  checkpoint_finish_local "${ordinal}" "${name}" "${expected}" "${status}" FAILED || true
  persist_local_failure "${status}" NONE || true
  handle_failure "${status}"
  return "${status}"
}

transfer_source_closure() {
  transfer_release_object scripts/v126-staging-prerequisite-sync.sh source/orchestrator.sh || return $?
  transfer_release_object scripts/v126-staging-prerequisite-sync-helper.py source/helper.py || return $?
  transfer_release_object scripts/v126-staging-prerequisite-sync-checks.tsv source/checks.tsv || return $?
  transfer_release_object docker-compose.yml release-inputs/docker-compose.yml || return $?
  transfer_release_object scripts/check-staging-maintenance-config.sh release-inputs/check-staging-maintenance-config.sh || return $?
  transfer_release_object scripts/validate-staging-admission.sh release-inputs/validate-staging-admission.sh || return $?
  remote_payload_call source-closure || return $?
}

remote_check() {
  remote_payload_call check "$1"
}

run_all_checks() {
  local ordinal name expected expected_ordinal=1 phase
  while IFS=$'\t' read -r ordinal name expected; do
    [[ "${ordinal}" == "${expected_ordinal}" && "${name}" =~ ^[A-Z][A-Z0-9_]*$ && "${expected}" =~ ^[A-Za-z0-9_./:\;=,-]+$ ]] || {
      die 'check map is malformed, missing, duplicate or reordered'
      return 1
    }
    phase="$(printf 'C%02d' "${ordinal}")"
    run_phase "${phase}" "${name}" "${expected}" remote_check "${ordinal}" || return $?
    expected_ordinal=$((expected_ordinal + 1))
  done < "${CHECK_MAP_PATH}"
  [[ "${expected_ordinal}" == 41 ]] || die 'check map cardinality is not 40/40'
}

record_controller_failure_remote() {
  local status="$1" signal_name="$2" out_sha err_sha
  out_sha="$(hash_file "${CURRENT_OUT}")"; err_sha="$(hash_file "${CURRENT_ERR}")"
  remote_payload_call controller-failure "${CURRENT_ORDINAL}" "${CURRENT_NAME}" "${status}" "${signal_name}" "${out_sha}" "${err_sha}" "${LAST_PASSED}" >/dev/null || true
}

handle_failure() {
  local original_status="$1" signal_name="${2:-NONE}" rollback_status=0 verify_status=0
  if [[ "${FAILURE_HANDLED}" == true ]]; then return "${original_status}"; fi
  FAILURE_HANDLED=true
  if (( WRITE_PHASE_DISPATCHED > 0 )) && [[ "${ROLLBACK_ATTEMPTED}" == false ]]; then
    ROLLBACK_ATTEMPTED=true
    record_controller_failure_remote "${original_status}" "${signal_name}"
    remote_payload_call rollback "${original_status}" >/dev/null 2>&1 || rollback_status=$?
    remote_payload_call verify-restored >/dev/null 2>&1 || verify_status=$?
    python3 "${HELPER_PATH}" event "${LOCAL_EVIDENCE}/rollback-result.json" \
      "${RUN_ID}" "$([[ "${rollback_status}" == 0 && "${verify_status}" == 0 ]] && printf ROLLBACK_PASSED || printf ROLLBACK_FAILED)" \
      "${RELEASE_SHA}" "${SCRIPT_SHA256}" "$((rollback_status + verify_status))" \
      "original_status=${original_status}" "${LAST_RESULT_SHA256}" >/dev/null 2>&1 || true
  fi
  return "${original_status}"
}

on_signal() {
  local status="$1" signal_name="$2"
  trap 'printf "%s\n" "additional signal ignored during bounded rollback" >&2' INT TERM
  if [[ "${ACTIVE_CHILD_PGID}" =~ ^[1-9][0-9]*$ ]]; then
    kill -TERM -- "-${ACTIVE_CHILD_PGID}" 2>/dev/null || true
  elif [[ "${ACTIVE_CHILD_PID}" =~ ^[1-9][0-9]*$ ]]; then
    kill -TERM "${ACTIVE_CHILD_PID}" 2>/dev/null || true
  fi
  if [[ "${ACTIVE_CHILD_PID}" =~ ^[1-9][0-9]*$ ]]; then
    wait "${ACTIVE_CHILD_PID}" 2>/dev/null || true
  fi
  ACTIVE_CHILD_PID=''
  ACTIVE_CHILD_PGID=''
  if [[ "${V126_PREREQ_FIXTURE_MODE:-0}" == 1 && "${V126_PREREQ_FIXTURE_REPEAT_SIGNAL:-0}" == 1 ]]; then
    kill -TERM "$$"
  fi
  if [[ -n "${CURRENT_ERR_TEMP}" && -f "${CURRENT_ERR_TEMP}" ]]; then
    printf 'signal=%s\n' "${signal_name}" >> "${CURRENT_ERR_TEMP}"
    seal_local_captures || true
    checkpoint_finish_local "${CURRENT_ORDINAL}" "${CURRENT_NAME}" "${CURRENT_EXPECTED}" "${status}" FAILED || true
    persist_local_failure "${status}" "${signal_name}" || true
  else
    CURRENT_ORDINAL='SIGNAL'
    CURRENT_NAME='CONTROLLER_SIGNAL'
    CURRENT_EXPECTED='NO_SIGNAL'
    CURRENT_OUT="${LOCAL_EVIDENCE}/raw/SIGNAL-CONTROLLER_SIGNAL.stdout"
    CURRENT_ERR="${LOCAL_EVIDENCE}/raw/SIGNAL-CONTROLLER_SIGNAL.stderr"
    CURRENT_OUT_TEMP="${LOCAL_EVIDENCE}/raw/.SIGNAL-CONTROLLER_SIGNAL.stdout.$$.tmp"
    CURRENT_ERR_TEMP="${LOCAL_EVIDENCE}/raw/.SIGNAL-CONTROLLER_SIGNAL.stderr.$$.tmp"
    printf '' | python3 "${HELPER_PATH}" exclusive-stdin "${CURRENT_OUT_TEMP}" 0600 >/dev/null || true
    printf 'signal=%s\n' "${signal_name}" | python3 "${HELPER_PATH}" exclusive-stdin "${CURRENT_ERR_TEMP}" 0600 >/dev/null || true
    checkpoint_start_local "${CURRENT_ORDINAL}" "${CURRENT_NAME}" "${CURRENT_EXPECTED}" || true
    seal_local_captures || true
    checkpoint_finish_local "${CURRENT_ORDINAL}" "${CURRENT_NAME}" "${CURRENT_EXPECTED}" "${status}" FAILED || true
    persist_local_failure "${status}" "${signal_name}" || true
  fi
  handle_failure "${status}" "${signal_name}" || true
  exit "${status}"
}

on_exit() {
  local status=$?
  trap - EXIT
  if [[ "${COMPLETED}" != true && "${FAILURE_HANDLED}" != true && "${status}" != 0 ]]; then
    seal_local_captures || true
    if [[ -n "${CURRENT_OUT}" && -f "${CURRENT_OUT}" ]]; then persist_local_failure "${status}" EXIT || true; fi
    handle_failure "${status}" || true
  fi
  exit "${status}"
}

main() {
  SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  SCRIPT_DIR="$(dirname "${SCRIPT_PATH}")"
  HELPER_PATH="${SCRIPT_DIR}/v126-staging-prerequisite-sync-helper.py"
  CHECK_MAP_PATH="${SCRIPT_DIR}/v126-staging-prerequisite-sync-checks.tsv"
  parse_arguments "$@" || exit $?
  validate_arguments || exit $?
  verify_bootstrap_sources || exit $?
  verify_source_identity || exit $?
  prepare_local_evidence || exit $?
  trap on_exit EXIT
  trap 'on_signal 130 SIGINT' INT
  trap 'on_signal 143 SIGTERM' TERM

  run_phase L01 SOURCE_IDENTITY_VERIFIED SOURCE_IDENTITY=EXACT_RELEASE_OBJECTS verify_source_identity || return $?
  run_phase L02 REQUIRED_TOOLS_AND_ARGUMENTS_VERIFIED TOOLS_AND_ARGUMENTS=PASS verify_tools_and_arguments || return $?
  run_phase L03 RELEASE_IDENTITY_VERIFIED "RELEASE_IDENTITY=PASS;ACTIONS=${EXPECTED_ACTIONS_JOBS}/${EXPECTED_ACTIONS_JOBS};MIGRATIONS=EXACT" verify_release_identity || return $?
  initialize_release_input_hashes || return $?
  run_phase L04 RELEASE_INPUTS_SELECTED RELEASE_INPUTS=EXACT_GIT_OBJECT_BYTES verify_release_inputs || return $?
  run_phase L05 REMOTE_PAYLOAD_TRANSFER_VERIFIED STREAM=EXACT_BYTES_VERIFIED remote_verify_payload_stream || return $?
  run_phase L06 PREWRITE_BASELINE_PASSED PREWRITE_BASELINE=PASS remote_payload_call prewrite-baseline || return $?
  if [[ "${V126_PREREQ_FIXTURE_MODE:-0}" == 1 && "${V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE:-0}" == 1 ]]; then
    COMPLETED=true
    trap - INT TERM EXIT
    printf '%s\n' 'V126_STAGING_PREREQUISITE_SYNC_PREWRITE=PASS GATE_A=NOT_STARTED'
    return 0
  fi
  run_phase L07 FLYWAY_VERIFIER_TRANSFER_VERIFIED STREAM=EXACT_BYTES_VERIFIED remote_verify_flyway_stream || return $?
  run_phase L08 PREWRITE_FLYWAY_PASSED FLYWAY=125:1:0:0 remote_run_flyway_verifier || return $?
  run_phase L09 RESTRICTED_EVIDENCE_ALLOCATED EVIDENCE=ALLOCATED remote_payload_call allocate-evidence || return $?
  run_phase L10 SOURCE_CLOSURE_TRANSFERRED SOURCE_CLOSURE=VERIFIED transfer_source_closure || return $?
  run_phase L11 ROLLBACK_INPUTS_CAPTURED ROLLBACK_INPUTS=CAPTURED remote_payload_call capture-rollback || return $?
  run_phase L12 ADMISSION_GUARD_ABSENT ADMISSION_GUARD=ABSENT remote_payload_call admission-absent || return $?

  WRITE_PHASE_DISPATCHED=1
  run_phase L13 SYNC_WRITE_COMPOSE_COMPLETED SYNC_WRITE_1=COMPOSE remote_payload_call write 1 || return $?
  WRITE_PHASE_DISPATCHED=2
  run_phase L14 SYNC_WRITE_MAINTENANCE_GUARD_COMPLETED SYNC_WRITE_2=MAINTENANCE_GUARD remote_payload_call write 2 || return $?
  WRITE_PHASE_DISPATCHED=3
  run_phase L15 SYNC_WRITE_ADMISSION_GUARD_COMPLETED SYNC_WRITE_3=ADMISSION_GUARD remote_payload_call write 3 || return $?
  WRITE_PHASE_DISPATCHED=4
  run_phase L16 SYNC_WRITE_ENV_COMPLETED SYNC_WRITE_4=ENV remote_payload_call write 4 || return $?

  run_all_checks || return $?
  run_phase L17 CANONICAL_SUCCESS_PERSISTED PREREQUISITE_SYNC=PASS remote_payload_call success || return $?
  COMPLETED=true
  trap - INT TERM EXIT
  printf 'V126_STAGING_PREREQUISITE_SYNC=PASS RUN_ID=%s GATE_A=NOT_STARTED\n' "${RUN_ID}"
}

: <<'V126_PREREQ_REMOTE_PAYLOAD'
# V126_PREREQ_REMOTE_PAYLOAD_BEGIN
#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
umask 077

readonly REMOTE_CONTRACT_VERSION='HT12R_PREREQUISITE_SYNC_V1'
readonly REMOTE_DEFAULT_STAGING_PATH='/opt/hookah-bot'
readonly REMOTE_DEFAULT_BACKUP_BASE='/var/backups/hookah-bot'
readonly REMOTE_DOMAIN='staging.hookahtootah.club'

ACTION="${1:?remote action}"; shift
RUN_ID="${1:?run id}"; shift
RELEASE_SHA="${1:?release sha}"; shift
SCRIPT_SHA256="${1:?script sha}"; shift
PAYLOAD_SHA256="${1:?payload sha}"; shift
FLYWAY_VERIFIER_SHA256="${1:?verifier sha}"; shift
HELPER_SHA256="${1:?helper sha}"; shift
CHECK_MAP_SHA256="${1:?check map sha}"; shift
RELEASE_COMPOSE_SHA256="${1:?release Compose sha}"; shift
RELEASE_MAINTENANCE_SHA256="${1:?release maintenance sha}"; shift
RELEASE_ADMISSION_SHA256="${1:?release admission sha}"; shift
PRE_COMPOSE_SHA256="${1:?pre Compose sha}"; shift
PRE_MAINTENANCE_SHA256="${1:?pre maintenance sha}"; shift
PRE_ENV_SHA256="${1:?pre env sha}"; shift
CADDYFILE_SHA256="${1:?Caddyfile sha}"; shift
CADDY_VERSION="${1:?Caddy version}"; shift
V125_SOURCE="${1:?V125 source}"; shift
V125_IMAGE_TAG="${1:?V125 tag}"; shift
V125_IMAGE_ID="${1:?V125 image ID}"; shift

STAGING_PATH="${REMOTE_DEFAULT_STAGING_PATH}"
BACKUP_BASE="${REMOTE_DEFAULT_BACKUP_BASE}"
CADDYFILE='/etc/caddy/Caddyfile'
DRAIN_MARKER='/etc/caddy/v126-drain.enabled'
SAFE_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
if [[ -n "${V126_PREREQ_REMOTE_FIXTURE_ROOT:-}" ]]; then
  [[ "${V126_PREREQ_REMOTE_FIXTURE_TOKEN:-}" == HT12R_LOCAL_FIXTURE_ONLY ]] || exit 95
  STAGING_PATH="${V126_PREREQ_REMOTE_FIXTURE_ROOT}/staging"
  BACKUP_BASE="${V126_PREREQ_REMOTE_FIXTURE_ROOT}/backups"
  CADDYFILE="${V126_PREREQ_REMOTE_FIXTURE_ROOT}/etc/caddy/Caddyfile"
  DRAIN_MARKER="${V126_PREREQ_REMOTE_FIXTURE_ROOT}/etc/caddy/v126-drain.enabled"
  SAFE_PATH="${PATH}"
else
  [[ "$(id -u)" == 0 ]] || { printf '%s\n' 'remote prerequisite controller must run as root' >&2; exit 1; }
fi
REMOTE_OWNER_UID="$(id -u)"
REMOTE_OWNER_GID="$(id -g)"

RUN_ROOT="${BACKUP_BASE}/${RUN_ID}"
SOURCE_ROOT="${RUN_ROOT}/source"
RELEASE_INPUT_ROOT="${RUN_ROOT}/release-inputs"
PRE_ROOT="${RUN_ROOT}/pre-sync"
CHECKPOINT_ROOT="${RUN_ROOT}/checkpoints"
RAW_ROOT="${RUN_ROOT}/raw"
STATE_ROOT="${RUN_ROOT}/state"
HELPER="${SOURCE_ROOT}/helper.py"
CHECK_MAP="${SOURCE_ROOT}/checks.tsv"

remote_die() {
  printf '%s\n' "$1" >&2
  exit 1
}

remote_hash() {
  sha256sum "$1" | awk '{print $1}'
}

remote_require_hex() {
  local value="$1" size="$2"
  [[ "${value}" =~ ^[0-9a-f]{${size}}$ ]] || remote_die 'remote identity is malformed'
}

remote_validate_context() {
  [[ "${RUN_ID}" =~ ^V126-PRE-GATE-A-SYNC-[0-9]{8}T[0-9]{6}Z$ ]] || remote_die 'remote run ID is malformed'
  remote_require_hex "${RELEASE_SHA}" 40 || return $?
  local value
  for value in "${SCRIPT_SHA256}" "${PAYLOAD_SHA256}" "${FLYWAY_VERIFIER_SHA256}" "${HELPER_SHA256}" "${CHECK_MAP_SHA256}" \
    "${RELEASE_COMPOSE_SHA256}" "${RELEASE_MAINTENANCE_SHA256}" "${RELEASE_ADMISSION_SHA256}" \
    "${PRE_COMPOSE_SHA256}" "${PRE_MAINTENANCE_SHA256}" "${PRE_ENV_SHA256}" "${CADDYFILE_SHA256}"; do
    remote_require_hex "${value}" 64 || return $?
  done
  remote_require_hex "${V125_SOURCE}" 40 || return $?
  [[ "${V125_IMAGE_TAG}" =~ ^[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+$ && "${V125_IMAGE_TAG}" == *:"${V125_SOURCE}" ]] ||
    remote_die 'remote V125 image identity is malformed'
  [[ "${V125_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]] || remote_die 'remote V125 image ID is malformed'
  [[ "${CADDY_VERSION}" == 2.6.2 ]] || remote_die 'remote Caddy version expectation is invalid'
}

remote_atomic_text() {
  local target="$1" mode="$2"
  python3 /dev/fd/3 "${target}" "${mode}" 3<<'PY'
import os,sys
target,mode=sys.argv[1:]
payload=sys.stdin.buffer.read()
parent=os.path.dirname(target)
temporary=os.path.join(parent,f".{os.path.basename(target)}.{os.getpid()}.tmp")
flags=os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,"O_NOFOLLOW",0)
try:
    fd=os.open(temporary,flags,0o600)
    try:
        view=memoryview(payload)
        while view:
            written=os.write(fd,view)
            if written<=0: raise OSError("short evidence write")
            view=view[written:]
        os.fsync(fd); os.fchmod(fd,int(mode,8))
    finally: os.close(fd)
    if os.path.lexists(target): raise SystemExit("exclusive evidence target exists")
    if sys.platform.startswith("linux"):
        import ctypes
        libc=ctypes.CDLL(None,use_errno=True)
        if libc.renameat2(-100,os.fsencode(temporary),-100,os.fsencode(target),1)!=0:
            error=ctypes.get_errno(); raise OSError(error,os.strerror(error),target)
    else:
        os.link(temporary,target); os.unlink(temporary)
    directory_fd=os.open(parent,os.O_RDONLY|getattr(os,"O_DIRECTORY",0))
    try: os.fsync(directory_fd)
    finally: os.close(directory_fd)
finally:
    if os.path.lexists(temporary): os.unlink(temporary)
info=os.lstat(target)
if not os.path.isfile(target) or os.path.islink(target) or (info.st_mode&0o777)!=int(mode,8): raise SystemExit("exclusive evidence target is unsafe")
with open(target,"rb") as handle:
    if handle.read()!=payload: raise SystemExit("exclusive evidence bytes mismatch")
PY
}

remote_context_lines() {
  printf '%s\n' \
    "contract=${REMOTE_CONTRACT_VERSION}" "run_id=${RUN_ID}" "release_sha=${RELEASE_SHA}" \
    "script_sha256=${SCRIPT_SHA256}" "payload_sha256=${PAYLOAD_SHA256}" \
    "flyway_verifier_sha256=${FLYWAY_VERIFIER_SHA256}" "helper_sha256=${HELPER_SHA256}" \
    "check_map_sha256=${CHECK_MAP_SHA256}" "release_compose_sha256=${RELEASE_COMPOSE_SHA256}" \
    "release_maintenance_sha256=${RELEASE_MAINTENANCE_SHA256}" "release_admission_sha256=${RELEASE_ADMISSION_SHA256}" \
    "pre_compose_sha256=${PRE_COMPOSE_SHA256}" "pre_maintenance_sha256=${PRE_MAINTENANCE_SHA256}" \
    "pre_env_sha256=${PRE_ENV_SHA256}" "caddyfile_sha256=${CADDYFILE_SHA256}" \
    "caddy_version=${CADDY_VERSION}" "v125_source=${V125_SOURCE}" "v125_image_tag=${V125_IMAGE_TAG}" \
    "v125_image_id=${V125_IMAGE_ID}"
}

remote_require_owner() {
  local path="$1" label="$2"
  [[ "$(stat -c '%u:%g' "${path}")" == "${REMOTE_OWNER_UID}:${REMOTE_OWNER_GID}" ]] ||
    remote_die "${label} ownership is invalid"
}

remote_require_evidence_file() {
  local path="$1" mode="$2" label="$3"
  [[ -f "${path}" && ! -L "${path}" && "$(stat -c '%a' "${path}")" == "${mode}" ]] ||
    remote_die "${label} is unavailable or unsafe"
  remote_require_owner "${path}" "${label}" || return $?
}

remote_require_run_root() {
  [[ -d "${RUN_ROOT}" && ! -L "${RUN_ROOT}" && "$(stat -c '%a' "${RUN_ROOT}")" == 700 ]] || remote_die 'run evidence root is unavailable or unsafe'
  remote_require_owner "${RUN_ROOT}" 'run evidence root' || return $?
  remote_require_evidence_file "${RUN_ROOT}/context.txt" 400 'run context' || return $?
  remote_require_evidence_file "${RUN_ROOT}/allocation.json" 400 'allocation checkpoint' || return $?
  remote_require_evidence_file "${STATE_ROOT}/controller.lock" 600 'controller lock' || return $?
  local directory
  for directory in "${SOURCE_ROOT}" "${RELEASE_INPUT_ROOT}" "${PRE_ROOT}" "${CHECKPOINT_ROOT}" "${CHECKPOINT_ROOT}/phases" "${RAW_ROOT}" "${STATE_ROOT}"; do
    [[ -d "${directory}" && ! -L "${directory}" && "$(stat -c '%a' "${directory}")" == 700 ]] || remote_die 'evidence child directory is unavailable or unsafe'
    remote_require_owner "${directory}" 'evidence child directory' || return $?
  done
  [[ "$(remote_context_lines | remote_hash /dev/stdin)" == "$(remote_hash "${RUN_ROOT}/context.txt")" ]] || remote_die 'run context differs from invocation'
}

acquire_remote_lock() {
  remote_require_run_root || return $?
  exec 9<> "${STATE_ROOT}/controller.lock" || remote_die 'controller lock could not be opened'
  python3 - 9 <<'PY'
import fcntl,signal,sys
def expired(_signum, _frame):
    raise SystemExit("controller lock wait expired")
signal.signal(signal.SIGALRM, expired)
signal.alarm(210)
fcntl.flock(int(sys.argv[1]), fcntl.LOCK_EX)
signal.alarm(0)
PY
}

remote_require_source_closure() {
  remote_require_run_root || return $?
  local file
  for file in "${HELPER}" "${CHECK_MAP}" "${SOURCE_ROOT}/orchestrator.sh" \
    "${RELEASE_INPUT_ROOT}/docker-compose.yml" "${RELEASE_INPUT_ROOT}/check-staging-maintenance-config.sh" \
    "${RELEASE_INPUT_ROOT}/validate-staging-admission.sh"; do
    remote_require_evidence_file "${file}" 400 'source-closure file' || return $?
  done
  [[ "$(remote_hash "${HELPER}")" == "${HELPER_SHA256}" ]] || remote_die 'remote helper bytes differ from release object'
  [[ "$(remote_hash "${CHECK_MAP}")" == "${CHECK_MAP_SHA256}" ]] || remote_die 'remote check-map bytes differ from release object'
  [[ "$(remote_hash "${SOURCE_ROOT}/orchestrator.sh")" == "${SCRIPT_SHA256}" ]] || remote_die 'remote orchestrator bytes differ from release object'
  [[ "$(remote_hash "${RELEASE_INPUT_ROOT}/docker-compose.yml")" == "${RELEASE_COMPOSE_SHA256}" ]] || remote_die 'remote Compose input differs from release object'
  [[ "$(remote_hash "${RELEASE_INPUT_ROOT}/check-staging-maintenance-config.sh")" == "${RELEASE_MAINTENANCE_SHA256}" ]] || remote_die 'remote maintenance guard differs from release object'
  [[ "$(remote_hash "${RELEASE_INPUT_ROOT}/validate-staging-admission.sh")" == "${RELEASE_ADMISSION_SHA256}" ]] || remote_die 'remote admission guard differs from release object'
  local map_rows
  map_rows="$(awk -F '\t' 'BEGIN{ok=1} {if ($1!=NR || seen[$1]++ || seen_name[$2]++ || NF!=3 || $2 !~ /^[A-Z][A-Z0-9_]*$/ || $3 !~ /^[A-Za-z0-9_.\/:;=,-]+$/) ok=0} END{if(NR==40 && ok) print NR}' "${CHECK_MAP}")"
  [[ "${map_rows}" == 40 ]] || remote_die 'post-sync check map is incomplete'
  local ordinal
  for ordinal in $(seq 1 40); do [[ "$(type -t "check_${ordinal}")" == function ]] || remote_die 'post-sync check implementation is incomplete'; done
}

compose() {
  if [[ -n "${V126_PREREQ_REMOTE_FIXTURE_ROOT:-}" ]]; then
    (cd "${STAGING_PATH}" && env -i PATH="${SAFE_PATH}" BACKEND_IMAGE="${V125_IMAGE_TAG}" \
      V126_FIXTURE_MOCK_LOG="${V126_FIXTURE_MOCK_LOG:?}" \
      V126_FIXTURE_DB_OUTPUT_ERROR="${V126_FIXTURE_DB_OUTPUT_ERROR:-0}" \
      docker compose --env-file .env --file docker-compose.yml "$@")
  else
    (cd "${STAGING_PATH}" && env -i PATH="${SAFE_PATH}" BACKEND_IMAGE="${V125_IMAGE_TAG}" \
      docker compose --env-file .env --file docker-compose.yml "$@")
  fi
}

one_running_id() {
  local service="$1" all_ids running_ids
  all_ids="$(compose ps -a -q "${service}")" || remote_die "${service} total inventory failed"
  running_ids="$(compose ps --status running -q "${service}")" || remote_die "${service} running inventory failed"
  [[ -n "${all_ids}" && "$(printf '%s\n' "${all_ids}" | wc -l | tr -d ' ')" == 1 ]] || remote_die "${service} total count is not one"
  [[ -n "${running_ids}" && "$(printf '%s\n' "${running_ids}" | wc -l | tr -d ' ')" == 1 ]] || remote_die "${service} running count is not one"
  [[ "${all_ids}" == "${running_ids}" ]] || remote_die "${service} is not the sole running instance"
  printf '%s\n' "${running_ids}"
}

env_key_count() {
  awk -F= -v key="$2" '$1==key {count++} END {print count+0}' "$1"
}

env_value() {
  awk -F= -v key="$2" '$1==key {sub(/^[^=]*=/,""); sub(/\r$/,""); print; exit}' "$1"
}

remote_runtime_semantics() {
  local backend="$1"
  docker inspect --format '{{json .Config.Env}}' "${backend}" | python3 -c '
import json,sys
rows=json.load(sys.stdin)
if not isinstance(rows,list) or any(not isinstance(row,str) or "=" not in row for row in rows): raise SystemExit(1)
values={}
for row in rows:
    key,value=row.split("=",1); values.setdefault(key,[]).append(value)
def one(key):
    found=values.get(key,[])
    if len(found)>1: raise SystemExit(1)
    return found[0] if found else None
def empty(value): return value is None or value==""
if one("TELEGRAM_TRAFFIC_POLICY")!="PRODUCT" or one("TELEGRAM_BOT_ENABLED")!="true" or one("TELEGRAM_BOT_MODE")!="long_polling": raise SystemExit(1)
for key in ("TELEGRAM_PRODUCT_ALLOWED_USER_IDS","TELEGRAM_PRODUCT_ALLOWED_CHAT_IDS","TELEGRAM_ALLOWED_USER_IDS","TELEGRAM_ALLOWED_CHAT_IDS"):
    if not empty(one(key)): raise SystemExit(1)
mode=one("STAGING_MAINTENANCE_MODE")
if mode is not None and mode not in ("","OFF"): raise SystemExit(1)
for key in ("STAGING_MAINTENANCE_ALLOWED_USER_IDS","STAGING_MAINTENANCE_ALLOWED_CHAT_IDS"):
    if not empty(one(key)): raise SystemExit(1)
smoke=one("STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED")
if smoke is not None and smoke not in ("","false"): raise SystemExit(1)
' || remote_die 'backend runtime environment is not exact V125 PRODUCT posture'
}

remote_caddy_version() {
  local observed
  observed="$(caddy version)" || remote_die 'installed Caddy version could not be read'
  python3 - "${CADDY_VERSION}" "${observed}" <<'PY'
import re,sys
expected,observed=sys.argv[1:]
if "\n" in observed or "\r" in observed: raise SystemExit(1)
tokens=observed.split()
if not tokens: raise SystemExit(1)
match=re.fullmatch(r"v?([0-9]+\.[0-9]+\.[0-9]+)",tokens[0])
if match is None or match.group(1)!=expected: raise SystemExit(1)
metadata=re.compile(r"[A-Za-z0-9][A-Za-z0-9._:+/=~@-]*")
version=re.compile(r"v?[0-9]+\.[0-9]+\.[0-9]+")
if any(metadata.fullmatch(token) is None or version.fullmatch(token) is not None for token in tokens[1:]): raise SystemExit(1)
PY
}

run_db_readonly() {
  compose exec -T postgres sh -c \
    'exec env PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X --no-psqlrc -Atq --set=ON_ERROR_STOP=1' <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')), ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status = 'NEW'), ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status = 'SENDING'), ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING') AND (next_attempt_at IS NULL OR next_attempt_at <= now())), ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status = 'FAILED')
);
SELECT pg_database_size(current_database());
ROLLBACK;
SQL
}

require_health_body() {
  local body
  body="$(curl -fsS "$1")" || remote_die 'health request failed'
  [[ "${body}" == '{"status":"ok"}' ]] || remote_die 'health response mismatch'
}

# HT12U_HEALTH_HEADERS_BEGIN
require_public_health_headers() {
  local policy="${1:-absent}" headers
  # Keep response bytes in memory only; never echo headers or curl diagnostics.
  headers="$(curl --disable --silent --fail --connect-timeout 5 --max-time 15 \
    --proto '=https' --suppress-connect-headers --request GET \
    --dump-header - --output /dev/null --write-out 'HT12U_STATUS=%{http_code}' \
    "https://${REMOTE_DOMAIN}/health" 2>/dev/null)" || {
    remote_die 'public health header transfer failed'
    return 1
  }
  printf '%s' "${headers}" | python3 -c '
import re, sys
policy = sys.argv[1]
data = sys.stdin.buffer.read()
def reject():
    raise SystemExit(1)
if policy not in ("capture", "absent") or len(data) > 65536:
    reject()
blocks = data.split(b"\r\n\r\n")
if len(blocks) < 2 or blocks.pop() != b"HT12U_STATUS=200":
    reject()
for index, block in enumerate(blocks):
    lines = block.split(b"\r\n")
    status = re.fullmatch(rb"HTTP/(?:1\.0|1\.1|2|3) ([0-9]{3})(?: [\x20-\x7e]*)?", lines[0])
    if status is None:
        reject()
    code = int(status[1])
    if index == len(blocks) - 1:
        if code != 200 or len(lines) < 2:
            reject()
    elif not 100 <= code < 200 or code == 101:
        reject()
    for line in lines[1:]:
        name, separator, value = line.partition(b":")
        if not separator or not re.fullmatch(rb"[!#$%&\x27*+.^_`|~0-9A-Za-z-]+", name):
            reject()
        if any(byte < 32 and byte != 9 or byte == 127 for byte in value):
            reject()
        if policy == "absent" and name.lower() == b"alt-svc":
            reject()
' "${policy}" || {
    remote_die 'public health headers invalid or forbidden Alt-Svc present'
    return 1
  }
}
# HT12U_HEALTH_HEADERS_END

require_v125_version() {
  local body observed
  body="$(curl -fsS "$1")" || remote_die 'backend version request failed'
  observed="$(printf '%s' "${body}" | python3 -c 'import json,sys; value=json.load(sys.stdin).get("version"); print(value) if isinstance(value,str) else sys.exit(1)')" ||
    remote_die 'backend version response is malformed'
  [[ "${observed}" == "${V125_SOURCE}" ]] || remote_die 'backend version differs from exact V125 source'
}

read_db_snapshot() {
  local queue_target="$1" size_target="$2" snapshot first second
  snapshot="$(run_db_readonly)" || remote_die 'database state could not be read completely'
  [[ "${snapshot}" == *$'\n'* ]] || remote_die 'database state result is incomplete'
  first="${snapshot%%$'\n'*}"
  second="${snapshot#*$'\n'}"
  [[ -n "${first}" && -n "${second}" && "${second}" != *$'\n'* ]] || remote_die 'database state result shape mismatch'
  printf -v "${queue_target}" '%s' "${first}"
  printf -v "${size_target}" '%s' "${second}"
}

require_tls_profile() {
  local tls13_output tls13_status
  printf '' | openssl s_client -connect "${REMOTE_DOMAIN}:443" -servername "${REMOTE_DOMAIN}" -tls1_2 -brief >/dev/null 2>&1 ||
    remote_die 'TLS 1.2 is unavailable'
  if tls13_output="$(printf '' | openssl s_client -connect "${REMOTE_DOMAIN}:443" -servername "${REMOTE_DOMAIN}" -tls1_3 -brief 2>&1)"; then
    remote_die 'TLS 1.3 is unexpectedly available'
  else
    tls13_status=$?
  fi
  [[ "${tls13_status}" -ne 0 && "${tls13_output}" == *'alert protocol version'* ]] ||
    remote_die 'TLS 1.3 probe did not prove server-side protocol rejection'
}

require_bot_idle() {
  local token response
  token="$(env_value "${STAGING_PATH}/.env" TELEGRAM_BOT_TOKEN)"
  [[ "${token}" =~ ^[0-9]+:[A-Za-z0-9_-]+$ ]] || remote_die 'Telegram token binding is malformed'
  response="$(printf '%s\n' silent show-error fail "url = \"https://api.telegram.org/bot${token}/getWebhookInfo\"" | curl --config -)" || remote_die 'Telegram webhook state could not be read'
  printf '%s' "${response}" | python3 -c 'import json,sys
d=json.load(sys.stdin); r=d.get("result") if d.get("ok") is True else None
raise SystemExit(0 if isinstance(r,dict) and r.get("url")=="" and r.get("pending_update_count")==0 else 1)' ||
    remote_die 'Telegram bot is not idle'
}

require_pre_file_baseline() {
  [[ -f "${STAGING_PATH}/docker-compose.yml" && ! -L "${STAGING_PATH}/docker-compose.yml" && "$(stat -c '%a' "${STAGING_PATH}/docker-compose.yml")" == 644 ]] || remote_die 'pre-sync Compose file is unavailable or unsafe'
  [[ -f "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh" && ! -L "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh" && "$(stat -c '%a' "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh")" == 755 ]] || remote_die 'pre-sync maintenance guard is unavailable or unsafe'
  [[ -f "${STAGING_PATH}/.env" && ! -L "${STAGING_PATH}/.env" && "$(stat -c '%a' "${STAGING_PATH}/.env")" == 600 ]] || remote_die 'pre-sync environment file is unavailable or unsafe'
  [[ "$(remote_hash "${STAGING_PATH}/docker-compose.yml")" == "${PRE_COMPOSE_SHA256}" ]] || remote_die 'pre-sync Compose hash mismatch'
  [[ "$(remote_hash "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh")" == "${PRE_MAINTENANCE_SHA256}" ]] || remote_die 'pre-sync maintenance guard hash mismatch'
  [[ "$(remote_hash "${STAGING_PATH}/.env")" == "${PRE_ENV_SHA256}" ]] || remote_die 'pre-sync environment hash mismatch'
  [[ ! -e "${STAGING_PATH}/scripts/validate-staging-admission.sh" && ! -L "${STAGING_PATH}/scripts/validate-staging-admission.sh" ]] || remote_die 'admission guard already exists before synchronization'
  [[ "$(env_key_count "${STAGING_PATH}/.env" STAGING_MAINTENANCE_MODE):$(env_key_count "${STAGING_PATH}/.env" STAGING_MAINTENANCE_ALLOWED_USER_IDS):$(env_key_count "${STAGING_PATH}/.env" STAGING_MAINTENANCE_ALLOWED_CHAT_IDS)" == '0:0:0' ]] || remote_die 'pre-sync environment already contains prerequisite keys'
}

baseline_full() {
  require_pre_file_baseline || return $?
  local backend postgres queue db_size competing logs udp_inventory
  backend="$(one_running_id backend)" || return $?
  postgres="$(one_running_id postgres)" || return $?
  [[ "$(docker inspect --format '{{.Image}}' "${backend}")" == "${V125_IMAGE_ID}" ]] || remote_die 'backend image ID differs from exact V125 image'
  [[ "$(docker inspect --format '{{.RestartCount}}' "${backend}")" == 0 ]] || remote_die 'backend restart count is not zero'
  [[ "$(docker inspect --format '{{.RestartCount}}' "${postgres}")" == 0 ]] || remote_die 'PostgreSQL restart count is not zero'
  [[ "$(docker inspect --format '{{.State.Health.Status}}' "${postgres}")" == healthy ]] || remote_die 'PostgreSQL is not healthy'
  remote_runtime_semantics "${backend}" || return $?
  require_health_body http://127.0.0.1:8080/health || return $?
  require_health_body http://127.0.0.1:8080/db/health || return $?
  require_health_body "https://${REMOTE_DOMAIN}/health" || return $?
  require_health_body "https://${REMOTE_DOMAIN}/db/health" || return $?
  require_v125_version http://127.0.0.1:8080/version || return $?
  curl -fsSI http://127.0.0.1:8080/miniapp/ >/dev/null || remote_die 'loopback Mini App is unavailable'
  curl -fsSI "https://${REMOTE_DOMAIN}/miniapp/" >/dev/null || remote_die 'public Mini App is unavailable'
  read_db_snapshot queue db_size || return $?
  [[ "${queue}" == '0:0:0:0:9' ]] || remote_die 'queue baseline is not exact'
  require_bot_idle || return $?
  local since
  since="$(docker inspect --format '{{.State.StartedAt}}' "${backend}")" || remote_die 'backend start timestamp could not be read'
  logs="$(docker logs "${backend}" --since "${since}" 2>&1)" || remote_die 'backend conflict-log inventory failed'
  if printf '%s\n' "${logs}" | grep -E 'Conflict: terminated by other getUpdates request|terminated by other getUpdates'; then remote_die 'HTTP 409 getUpdates conflict detected'; fi
  remote_caddy_version || remote_die 'installed Caddy version mismatch'
  [[ "$(systemctl is-active caddy)" == active ]] || remote_die 'Caddy service is not active'
  [[ "$(remote_hash "${CADDYFILE}")" == "${CADDYFILE_SHA256}" ]] || remote_die 'Caddyfile hash mismatch'
  caddy validate --config "${CADDYFILE}" --adapter caddyfile >/dev/null || remote_die 'Caddy validation failed'
  [[ ! -e "${DRAIN_MARKER}" && ! -L "${DRAIN_MARKER}" ]] || remote_die 'Caddy drain marker is present'
  require_tls_profile || return $?
  require_public_health_headers absent || return $?
  udp_inventory="$(ss -H -lun 'sport = :443')" || remote_die 'UDP port 443 inventory failed'
  [[ -z "${udp_inventory}" ]] || remote_die 'UDP port 443 is unexpectedly bound'
  competing="$(ps -eo pid=,args= | awk -v self="$$" -v parent="${PPID}" '$1!=self && $1!=parent && $0 ~ /(v126-cutover|v126-staging-prerequisite-sync|pg_dump|pg_restore|caddy[[:space:]]+(reload|stop|start)|docker[[:space:]]+compose[[:space:]]+(up|down|stop|start|restart|create|run))/ {count++} END {print count+0}')" || remote_die 'competing-process inventory failed'
  [[ "${competing}" == 0 ]] || remote_die 'competing mutation-capable process detected'
}

phase_started_path() { printf '%s/phases/%s-%s.started.json\n' "${CHECKPOINT_ROOT}" "$1" "$2"; }
phase_result_path() { printf '%s/phases/%s-%s.%s.json\n' "${CHECKPOINT_ROOT}" "$1" "$2" "$3"; }

previous_result_path() {
  local ordinal="$1"
  case "${ordinal}" in
    R01) printf '%s\n' "${RUN_ROOT}/allocation.json" ;;
    R02) phase_result_path R01 SOURCE_CLOSURE_VERIFIED passed ;;
    R03) phase_result_path R02 ROLLBACK_INPUTS_CAPTURED passed ;;
    R04) phase_result_path R03 ADMISSION_GUARD_ABSENT passed ;;
    R05) phase_result_path R04 SYNC_WRITE_COMPOSE passed ;;
    R06) phase_result_path R05 SYNC_WRITE_MAINTENANCE_GUARD passed ;;
    R07) phase_result_path R06 SYNC_WRITE_ADMISSION_GUARD passed ;;
    C01) phase_result_path R07 SYNC_WRITE_ENV passed ;;
    C*)
      local number previous name
      number=$((10#${ordinal#C}))
      previous=$((number - 1))
      name="$(awk -F '\t' -v n="${previous}" '$1==n {print $2}' "${CHECK_MAP}")"
      phase_result_path "$(printf 'C%02d' "${previous}")" "${name}" passed
      ;;
    R08)
      local name
      name="$(awk -F '\t' '$1==40 {print $2}' "${CHECK_MAP}")"
      phase_result_path C40 "${name}" passed
      ;;
    *) remote_die 'unknown remote ordinal' ;;
  esac
}

run_tracked() {
  local ordinal="$1" name="$2" expected="$3" function="$4"
  shift 4
  remote_require_source_closure || return $?
  local predecessor_path predecessor start passed failed out err out_temp err_temp status suffix
  predecessor_path="$(previous_result_path "${ordinal}")"
  predecessor="$(python3 "${HELPER}" verify-predecessor "${RUN_ROOT}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${ordinal}" 2>/dev/null)" ||
    remote_die 'predecessor checkpoint chain is missing or invalid'
  start="$(phase_started_path "${ordinal}" "${name}")"
  passed="$(phase_result_path "${ordinal}" "${name}" passed)"
  failed="$(phase_result_path "${ordinal}" "${name}" failed)"
  out="${RAW_ROOT}/${ordinal}-${name}.stdout"; err="${RAW_ROOT}/${ordinal}-${name}.stderr"
  out_temp="${RAW_ROOT}/.${ordinal}-${name}.stdout.$$.tmp"; err_temp="${RAW_ROOT}/.${ordinal}-${name}.stderr.$$.tmp"
  [[ ! -e "${out}" && ! -L "${out}" && ! -e "${err}" && ! -L "${err}" ]] || remote_die 'remote raw capture target already exists'
  printf '' | python3 "${HELPER}" exclusive-stdin "${out_temp}" 0600 >/dev/null || remote_die 'remote stdout capture allocation failed'
  printf '' | python3 "${HELPER}" exclusive-stdin "${err_temp}" 0600 >/dev/null || remote_die 'remote stderr capture allocation failed'
  python3 "${HELPER}" checkpoint-start "${start}" "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${expected}" "${predecessor}" ||
    remote_die 'remote phase-start checkpoint failed'
  remote_require_evidence_file "${start}" 400 'remote phase-start checkpoint' || return $?
  if [[ "${V126_PREREQ_FIXTURE_FAIL_ACTION:-}" == "${name}" ]]; then
    (exit "${V126_PREREQ_FIXTURE_FAIL_STATUS:-72}") >> "${out_temp}" 2>> "${err_temp}" &
  else
    (set -euo pipefail; trap 'exit 130' INT; trap 'exit 143' TERM; "${function}" "$@") >> "${out_temp}" 2>> "${err_temp}" &
  fi
  local phase_pid=$!
  set +e
  wait "${phase_pid}"
  status=$?
  set -e
  if [[ "${status}" == 0 ]]; then
    [[ "$(tail -n 1 "${out_temp}")" == "${expected}" ]] || { printf '%s\n' 'sanitized result mismatch' >> "${err_temp}"; status=78; }
  fi
  python3 "${HELPER}" seal-capture "${out_temp}" "${out}" 0600 >/dev/null || remote_die 'remote stdout capture sealing failed'
  python3 "${HELPER}" seal-capture "${err_temp}" "${err}" 0600 >/dev/null || remote_die 'remote stderr capture sealing failed'
  remote_require_evidence_file "${out}" 600 'remote stdout capture' || return $?
  remote_require_evidence_file "${err}" 600 'remote stderr capture' || return $?
  if [[ "${status}" == 0 ]]; then
    python3 "${HELPER}" checkpoint-finish "${passed}" "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${expected}" "${predecessor}" "${start}" 0 "${out}" "${err}" PASSED ||
      remote_die 'remote passed checkpoint failed'
    remote_require_evidence_file "${passed}" 400 'remote passed checkpoint' || return $?
    python3 "${HELPER}" verify-checkpoint "${passed}" "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${expected}" "${predecessor}" >/dev/null ||
      remote_die 'remote passed checkpoint verification failed'
    printf '%s\n' "${expected}"
    return 0
  fi
  python3 "${HELPER}" checkpoint-finish "${failed}" "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${expected}" "${predecessor}" "${start}" "${status}" "${out}" "${err}" FAILED || true
  if [[ -f "${failed}" && ! -L "${failed}" ]]; then remote_require_evidence_file "${failed}" 400 'remote failed checkpoint' || true; fi
  if [[ ! -e "${RUN_ROOT}/first-failure.json" && ! -L "${RUN_ROOT}/first-failure.json" ]]; then
    python3 "${HELPER}" first-failure "${RUN_ROOT}/first-failure.json" "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${status}" NONE "${predecessor}" "$(remote_hash "${out}")" "$(remote_hash "${err}")" "${predecessor_path}" || true
    if [[ -f "${RUN_ROOT}/first-failure.json" && ! -L "${RUN_ROOT}/first-failure.json" ]]; then remote_require_evidence_file "${RUN_ROOT}/first-failure.json" 400 'remote first failure' || true; fi
  fi
  return "${status}"
}

allocate_evidence() {
  if [[ ! -e "${BACKUP_BASE}" && ! -L "${BACKUP_BASE}" ]]; then
    install -d -m 0700 "${BACKUP_BASE}" || remote_die 'backup base allocation failed'
  fi
  [[ -d "${BACKUP_BASE}" && ! -L "${BACKUP_BASE}" && "$(stat -c '%a' "${BACKUP_BASE}")" == 700 ]] || remote_die 'backup base is unavailable or unsafe'
  remote_require_owner "${BACKUP_BASE}" 'backup base' || return $?
  [[ ! -e "${RUN_ROOT}" && ! -L "${RUN_ROOT}" ]] || remote_die 'run evidence root already exists'
  install -d -m 0700 "${RUN_ROOT}" || remote_die 'run evidence root allocation failed'
  install -d -m 0700 "${SOURCE_ROOT}" "${RELEASE_INPUT_ROOT}" "${PRE_ROOT}" "${CHECKPOINT_ROOT}" "${CHECKPOINT_ROOT}/phases" "${RAW_ROOT}" "${STATE_ROOT}" ||
    remote_die 'evidence child allocation failed'
  remote_context_lines | remote_atomic_text "${RUN_ROOT}/context.txt" 0400 || remote_die 'run context write failed'
  printf '%s\n' "run_id=${RUN_ID}" | remote_atomic_text "${STATE_ROOT}/controller.lock" 0600 || remote_die 'controller lock allocation failed'
  printf '%s\n' \
    "{\"contract\":\"${REMOTE_CONTRACT_VERSION}\",\"name\":\"EVIDENCE_ALLOCATED\",\"release_sha\":\"${RELEASE_SHA}\",\"run_id\":\"${RUN_ID}\",\"script_sha256\":\"${SCRIPT_SHA256}\",\"state\":\"PASSED\"}" |
    remote_atomic_text "${RUN_ROOT}/allocation.json" 0400 || remote_die 'allocation checkpoint write failed'
  remote_require_run_root || return $?
  printf '%s\n' 'EVIDENCE=ALLOCATED'
}

verify_embedded_hash() {
  local file="$1" begin="$2" end="$3" expected="$4"
  python3 - "${file}" "${begin}" "${end}" "${expected}" <<'PY'
import hashlib,sys
path,begin,end,expected=sys.argv[1:]
lines=open(path,"rb").read().splitlines(keepends=True)
begin=begin.encode(); end=end.encode()
b=[i for i,line in enumerate(lines) if line.rstrip(b"\r\n")==begin]
e=[i for i,line in enumerate(lines) if line.rstrip(b"\r\n")==end]
if len(b)!=1 or len(e)!=1 or b[0]>=e[0]: raise SystemExit(1)
payload=b"".join(lines[b[0]+1:e[0]])
if hashlib.sha256(payload).hexdigest()!=expected: raise SystemExit(1)
PY
}

source_closure_action() {
  verify_embedded_hash "${SOURCE_ROOT}/orchestrator.sh" '# V126_PREREQ_REMOTE_PAYLOAD_BEGIN' '# V126_PREREQ_REMOTE_PAYLOAD_END' "${PAYLOAD_SHA256}" || remote_die 'remote payload closure hash mismatch'
  verify_embedded_hash "${SOURCE_ROOT}/orchestrator.sh" '# V126_PREREQ_FLYWAY_VERIFIER_BEGIN' '# V126_PREREQ_FLYWAY_VERIFIER_END' "${FLYWAY_VERIFIER_SHA256}" || remote_die 'Flyway verifier closure hash mismatch'
  printf '%s\n' 'SOURCE_CLOSURE=VERIFIED'
}

metadata_line() {
  local name="$1" path="$2"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "${name}" "$(stat -c '%u' "${path}")" "$(stat -c '%g' "${path}")" "$(stat -c '%a' "${path}")" "$(stat -c '%s' "${path}")" "$(remote_hash "${path}")"
}

capture_rollback_action() {
  baseline_full || return $?
  python3 "${HELPER}" capture-file "${STAGING_PATH}/docker-compose.yml" "${PRE_ROOT}/docker-compose.yml" 0400 >/dev/null || remote_die 'Compose rollback capture failed'
  python3 "${HELPER}" capture-file "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh" "${PRE_ROOT}/check-staging-maintenance-config.sh" 0400 >/dev/null || remote_die 'maintenance-guard rollback capture failed'
  python3 "${HELPER}" capture-file "${STAGING_PATH}/.env" "${PRE_ROOT}/.env" 0400 >/dev/null || remote_die 'environment rollback capture failed'
  {
    metadata_line docker-compose.yml "${STAGING_PATH}/docker-compose.yml"
    metadata_line check-staging-maintenance-config.sh "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh"
    metadata_line .env "${STAGING_PATH}/.env"
  } | python3 "${HELPER}" exclusive-stdin "${PRE_ROOT}/metadata.tsv" 0400 >/dev/null || remote_die 'rollback metadata capture failed'
  python3 "${HELPER}" env-derive "${PRE_ROOT}/.env" "${PRE_ROOT}/.env.expected-post" >/dev/null || remote_die 'post-sync environment derivation failed'
  local backend postgres inventory
  backend="$(one_running_id backend)" || return $?
  postgres="$(one_running_id postgres)" || return $?
  inventory="$(docker ps -a --no-trunc --format '{{.ID}}|{{.Image}}|{{.Names}}' | sha256sum | awk '{print $1}')" || remote_die 'Docker inventory capture failed'
  printf '%s\t%s\n' \
    backend_id "${backend}" backend_started_at "$(docker inspect --format '{{.State.StartedAt}}' "${backend}")" \
    postgres_id "${postgres}" postgres_started_at "$(docker inspect --format '{{.State.StartedAt}}' "${postgres}")" \
    postgres_image_id "$(docker inspect --format '{{.Image}}' "${postgres}")" \
    docker_inventory_sha256 "${inventory}" |
    python3 "${HELPER}" exclusive-stdin "${PRE_ROOT}/runtime.tsv" 0400 >/dev/null || remote_die 'runtime rollback capture failed'
  python3 "${HELPER}" event "${PRE_ROOT}/admission-guard.was-absent.json" "${RUN_ID}" ADMISSION_GUARD_WAS_ABSENT "${RELEASE_SHA}" "${SCRIPT_SHA256}" 0 ABSENT NONE >/dev/null ||
    remote_die 'admission absence record failed'
  require_rollback_inputs || return $?
  printf '%s\n' 'ROLLBACK_INPUTS=CAPTURED'
}

admission_absent_action() {
  require_rollback_inputs || return $?
  require_pre_file_baseline || return $?
  printf '%s\n' 'ADMISSION_GUARD=ABSENT'
}

metadata_field() {
  awk -F '\t' -v name="$1" -v column="$2" '$1==name {print $column}' "${PRE_ROOT}/metadata.tsv"
}

require_rollback_inputs() {
  local file name expected_mode expected_hash uid gid size recorded_hash
  for file in "${PRE_ROOT}/docker-compose.yml" "${PRE_ROOT}/check-staging-maintenance-config.sh" \
    "${PRE_ROOT}/.env" "${PRE_ROOT}/.env.expected-post" "${PRE_ROOT}/metadata.tsv" "${PRE_ROOT}/runtime.tsv" \
    "${PRE_ROOT}/admission-guard.was-absent.json"; do
    remote_require_evidence_file "${file}" 400 'rollback input' || return $?
  done
  [[ "$(remote_hash "${PRE_ROOT}/docker-compose.yml")" == "${PRE_COMPOSE_SHA256}" ]] || remote_die 'Compose rollback bytes mismatch'
  [[ "$(remote_hash "${PRE_ROOT}/check-staging-maintenance-config.sh")" == "${PRE_MAINTENANCE_SHA256}" ]] || remote_die 'maintenance-guard rollback bytes mismatch'
  [[ "$(remote_hash "${PRE_ROOT}/.env")" == "${PRE_ENV_SHA256}" ]] || remote_die 'environment rollback bytes mismatch'
  python3 "${HELPER}" env-verify "${PRE_ROOT}/.env" "${PRE_ROOT}/.env.expected-post" >/dev/null || remote_die 'derived environment rollback contract mismatch'
  python3 "${HELPER}" verify-record "${PRE_ROOT}/admission-guard.was-absent.json" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" >/dev/null ||
    remote_die 'admission absence record is invalid'
  [[ "$(awk -F '\t' 'BEGIN{ok=1} {if(NF!=6 || seen[$1]++) ok=0} END{if(NR==3 && ok) print NR}' "${PRE_ROOT}/metadata.tsv")" == 3 ]] ||
    remote_die 'rollback metadata is incomplete'
  for name in docker-compose.yml check-staging-maintenance-config.sh .env; do
    case "${name}" in
      docker-compose.yml) expected_mode=644; expected_hash="${PRE_COMPOSE_SHA256}" ;;
      check-staging-maintenance-config.sh) expected_mode=755; expected_hash="${PRE_MAINTENANCE_SHA256}" ;;
      .env) expected_mode=600; expected_hash="${PRE_ENV_SHA256}" ;;
    esac
    uid="$(metadata_field "${name}" 2)"; gid="$(metadata_field "${name}" 3)"; size="$(metadata_field "${name}" 5)"; recorded_hash="$(metadata_field "${name}" 6)"
    [[ "${uid}" =~ ^[0-9]+$ && "${gid}" =~ ^[0-9]+$ && "${size}" =~ ^[1-9][0-9]*$ ]] || remote_die 'rollback ownership or size metadata is malformed'
    [[ "$(metadata_field "${name}" 4)" == "${expected_mode}" && "${recorded_hash}" == "${expected_hash}" ]] || remote_die 'rollback mode or hash metadata mismatch'
  done
  [[ "$(awk -F '\t' 'BEGIN{ok=1; expected["backend_id"]; expected["backend_started_at"]; expected["postgres_id"]; expected["postgres_started_at"]; expected["postgres_image_id"]; expected["docker_inventory_sha256"]} {if(NF!=2 || !($1 in expected) || seen[$1]++ || $2=="") ok=0} END{for(key in expected) if(seen[key]!=1) ok=0; if(NR==6 && ok) print NR}' "${PRE_ROOT}/runtime.tsv")" == 6 ]] ||
    remote_die 'runtime rollback metadata is incomplete'
}

write_marker() {
  local ordinal="$1"
  printf 'write=%s\n' "${ordinal}" | python3 "${HELPER}" exclusive-stdin "${STATE_ROOT}/write-${ordinal}.completed" 0400 >/dev/null || return $?
  if [[ "${V126_PREREQ_FIXTURE_TAMPER_WRITE_MARKER:-}" == "${ordinal}" ]]; then chmod 0600 "${STATE_ROOT}/write-${ordinal}.completed"; fi
  remote_require_evidence_file "${STATE_ROOT}/write-${ordinal}.completed" 400 'write-completion marker'
}

require_installed_file() {
  local target="$1" expected_hash="$2" expected_mode="$3" expected_uid="$4" expected_gid="$5" label="$6"
  [[ -f "${target}" && ! -L "${target}" ]] || remote_die "${label} is unavailable or unsafe"
  [[ "$(remote_hash "${target}")" == "${expected_hash}" ]] || remote_die "${label} hash does not match its write predecessor"
  [[ "$(stat -c '%a:%u:%g' "${target}")" == "${expected_mode}:${expected_uid}:${expected_gid}" ]] ||
    remote_die "${label} mode or ownership does not match its write predecessor"
}

require_write_predecessor() {
  local ordinal="$1" compose_hash maintenance_hash env_hash
  [[ "${ordinal}" =~ ^[1-4]$ ]] || remote_die 'invalid synchronization write predecessor ordinal'
  compose_hash="${PRE_COMPOSE_SHA256}"
  maintenance_hash="${PRE_MAINTENANCE_SHA256}"
  env_hash="${PRE_ENV_SHA256}"
  if ((ordinal >= 2)); then compose_hash="${RELEASE_COMPOSE_SHA256}"; fi
  if ((ordinal >= 3)); then maintenance_hash="${RELEASE_MAINTENANCE_SHA256}"; fi
  if ((ordinal >= 4)); then
    require_installed_file "${STAGING_PATH}/scripts/validate-staging-admission.sh" "${RELEASE_ADMISSION_SHA256}" 755 \
      "$(metadata_field check-staging-maintenance-config.sh 2)" "$(metadata_field check-staging-maintenance-config.sh 3)" 'admission guard'
  else
    [[ ! -e "${STAGING_PATH}/scripts/validate-staging-admission.sh" && ! -L "${STAGING_PATH}/scripts/validate-staging-admission.sh" ]] ||
      remote_die 'admission guard appeared before its authorized write'
  fi
  require_installed_file "${STAGING_PATH}/docker-compose.yml" "${compose_hash}" 644 \
    "$(metadata_field docker-compose.yml 2)" "$(metadata_field docker-compose.yml 3)" 'Compose file'
  require_installed_file "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh" "${maintenance_hash}" 755 \
    "$(metadata_field check-staging-maintenance-config.sh 2)" "$(metadata_field check-staging-maintenance-config.sh 3)" 'maintenance guard'
  require_installed_file "${STAGING_PATH}/.env" "${env_hash}" 600 \
    "$(metadata_field .env 2)" "$(metadata_field .env 3)" 'environment file'
}

write_action() {
  local ordinal="$1" target source name mode uid gid policy expected
  require_rollback_inputs || return $?
  require_write_predecessor "${ordinal}" || return $?
  if [[ "${V126_PREREQ_FIXTURE_FAIL_WRITE_POSITION:-}" == "before-${ordinal}" ]]; then return "${V126_PREREQ_FIXTURE_FAIL_STATUS:-73}"; fi
  case "${ordinal}" in
    1) target="${STAGING_PATH}/docker-compose.yml"; source="${RELEASE_INPUT_ROOT}/docker-compose.yml"; name=COMPOSE; mode=0644; uid="$(metadata_field docker-compose.yml 2)"; gid="$(metadata_field docker-compose.yml 3)"; policy=replace ;;
    2) target="${STAGING_PATH}/scripts/check-staging-maintenance-config.sh"; source="${RELEASE_INPUT_ROOT}/check-staging-maintenance-config.sh"; name=MAINTENANCE_GUARD; mode=0755; uid="$(metadata_field check-staging-maintenance-config.sh 2)"; gid="$(metadata_field check-staging-maintenance-config.sh 3)"; policy=replace ;;
    3) target="${STAGING_PATH}/scripts/validate-staging-admission.sh"; source="${RELEASE_INPUT_ROOT}/validate-staging-admission.sh"; name=ADMISSION_GUARD; mode=0755; uid="$(metadata_field check-staging-maintenance-config.sh 2)"; gid="$(metadata_field check-staging-maintenance-config.sh 3)"; policy=create ;;
    4) target="${STAGING_PATH}/.env"; source="${PRE_ROOT}/.env.expected-post"; name=ENV; mode=0600; uid="$(metadata_field .env 2)"; gid="$(metadata_field .env 3)"; policy=replace ;;
    *) remote_die 'invalid synchronization write ordinal' ;;
  esac
  python3 "${HELPER}" atomic-install "${source}" "${target}" "${mode}" "${uid}" "${gid}" "${policy}" >/dev/null || remote_die 'authorized atomic synchronization write failed'
  if [[ -n "${V126_PREREQ_REMOTE_FIXTURE_ROOT:-}" && "${V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE:-}" == "${ordinal}:INT" ]]; then
    kill -INT "${V126_PREREQ_FIXTURE_CONTROLLER_PID:?}" || return $?
    sleep 300
  elif [[ -n "${V126_PREREQ_REMOTE_FIXTURE_ROOT:-}" && "${V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE:-}" == "${ordinal}:TERM" ]]; then
    kill -TERM "${V126_PREREQ_FIXTURE_CONTROLLER_PID:?}" || return $?
    sleep 300
  fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_WRITE_POSITION:-}" == "during-${ordinal}" ]]; then return "${V126_PREREQ_FIXTURE_FAIL_STATUS:-73}"; fi
  write_marker "${ordinal}" || remote_die 'write-completion marker failed'
  if [[ "${V126_PREREQ_FIXTURE_FAIL_WRITE_POSITION:-}" == "after-${ordinal}" ]]; then return "${V126_PREREQ_FIXTURE_FAIL_STATUS:-73}"; fi
  printf 'SYNC_WRITE_%s=%s\n' "${ordinal}" "${name}"
}

pre_runtime_value() {
  awk -F '\t' -v key="$1" '$1==key {print $2}' "${PRE_ROOT}/runtime.tsv"
}

check_1() { [[ "$(remote_hash docker-compose.yml)" == "${RELEASE_COMPOSE_SHA256}" && "$(remote_hash scripts/check-staging-maintenance-config.sh)" == "${RELEASE_MAINTENANCE_SHA256}" && "$(remote_hash scripts/validate-staging-admission.sh)" == "${RELEASE_ADMISSION_SHA256}" ]] || remote_die 'release file bytes mismatch'; printf '%s\n' 'FILES=EXACT_RELEASE_BYTES'; }
check_2() { [[ "$(stat -c '%a:%u:%g' docker-compose.yml)" == "644:$(metadata_field docker-compose.yml 2):$(metadata_field docker-compose.yml 3)" ]] || remote_die 'Compose mode or ownership mismatch'; [[ "$(stat -c '%a:%u:%g' scripts/check-staging-maintenance-config.sh)" == "755:$(metadata_field check-staging-maintenance-config.sh 2):$(metadata_field check-staging-maintenance-config.sh 3)" ]] || remote_die 'maintenance guard mode or ownership mismatch'; [[ "$(stat -c '%a:%u:%g' scripts/validate-staging-admission.sh)" == "755:$(metadata_field check-staging-maintenance-config.sh 2):$(metadata_field check-staging-maintenance-config.sh 3)" ]] || remote_die 'admission guard mode or ownership mismatch'; printf '%s\n' 'MODES=0644,0755,0755;OWNERSHIP=PRESERVED'; }
check_3() { python3 "${HELPER}" env-verify "${PRE_ROOT}/.env" .env | sed 's/;SHA256=[0-9a-f]*$//' || remote_die 'environment derivation mismatch'; }
check_4() { bash -n scripts/check-staging-maintenance-config.sh || remote_die 'maintenance guard syntax invalid'; printf '%s\n' 'BASH_N=PASS'; }
check_5() { bash -n scripts/validate-staging-admission.sh || remote_die 'admission guard syntax invalid'; printf '%s\n' 'BASH_N=PASS'; }
check_6() { [[ -d "${STAGING_PATH}" && ! -L "${STAGING_PATH}" && "${PWD}" -ef "${STAGING_PATH}" ]] || remote_die 'working directory identity mismatch'; printf '%s\n' 'PWD=/opt/hookah-bot'; }
check_7() { compose config --quiet >/dev/null || remote_die 'Compose configuration invalid'; printf '%s\n' 'COMPOSE_CONFIG=PASS'; }
check_8() { scripts/check-staging-maintenance-config.sh .env >/dev/null || remote_die 'maintenance guard OFF check failed'; printf '%s\n' 'MAINTENANCE_GUARD=OFF_PASS'; }
check_9() { scripts/validate-staging-admission.sh --profile public-pilot --env-file .env --compose-file docker-compose.yml >/dev/null || remote_die 'public-pilot admission validation failed'; printf '%s\n' 'ADMISSION_GUARD=PUBLIC_PILOT_PASS'; }
check_10() { [[ "$(docker ps -a --no-trunc --format '{{.ID}}|{{.Image}}|{{.Names}}' | sha256sum | awk '{print $1}')" == "$(pre_runtime_value docker_inventory_sha256)" ]] || remote_die 'Docker inventory changed'; printf '%s\n' 'DOCKER_INVENTORY=UNCHANGED'; }
check_11() { one_running_id backend >/dev/null || return $?; printf '%s\n' 'BACKEND=1'; }
check_12() { one_running_id postgres >/dev/null || return $?; printf '%s\n' 'POSTGRESQL=1'; }
check_13() { local id; id="$(one_running_id backend)" || return $?; [[ "$(docker inspect --format '{{.Image}}' "${id}")" == "${V125_IMAGE_ID}" && "$(docker inspect --format '{{.RestartCount}}' "${id}")" == 0 ]] || remote_die 'backend image or restart count changed'; require_v125_version http://127.0.0.1:8080/version || return $?; printf '%s\n' 'BACKEND=V125_EXACT;RESTARTS=0'; }
check_14() { local id; id="$(one_running_id postgres)" || return $?; [[ "${id}" == "$(pre_runtime_value postgres_id)" && "$(docker inspect --format '{{.Image}}' "${id}")" == "$(pre_runtime_value postgres_image_id)" && "$(docker inspect --format '{{.RestartCount}}' "${id}")" == 0 && "$(docker inspect --format '{{.State.Health.Status}}' "${id}")" == healthy ]] || remote_die 'PostgreSQL runtime identity changed'; printf '%s\n' 'POSTGRESQL=EXACT;RESTARTS=0;HEALTHY'; }
check_15() { local id; id="$(one_running_id backend)" || return $?; docker inspect --format '{{json .Config.Env}}' "${id}" | python3 "${HELPER}" runtime-env || remote_die 'backend runtime environment mismatch'; }
check_16() { compose exec -T postgres sh -c ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; pg_isready -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null || remote_die 'pg_isready failed'; printf '%s\n' 'PG_ISREADY=PASS'; }
check_17() { require_health_body http://127.0.0.1:8080/health || return $?; printf '%s\n' 'LOOPBACK_HEALTH=OK'; }
check_18() { require_health_body "https://${REMOTE_DOMAIN}/health" || return $?; printf '%s\n' 'PUBLIC_HEALTH=OK'; }
check_19() { require_health_body http://127.0.0.1:8080/db/health || return $?; printf '%s\n' 'LOOPBACK_DB_HEALTH=OK'; }
check_20() { require_health_body "https://${REMOTE_DOMAIN}/db/health" || return $?; printf '%s\n' 'PUBLIC_DB_HEALTH=OK'; }
check_21() { curl -fsSI http://127.0.0.1:8080/miniapp/ >/dev/null || remote_die 'loopback Mini App unavailable'; printf '%s\n' 'LOOPBACK_MINIAPP=REACHABLE'; }
check_22() { curl -fsSI "https://${REMOTE_DOMAIN}/miniapp/" >/dev/null || remote_die 'public Mini App unavailable'; printf '%s\n' 'PUBLIC_MINIAPP=REACHABLE'; }
check_23() { require_v125_version http://127.0.0.1:8080/version || return $?; printf '%s\n' 'VERSION=V125_SOURCE_EXACT'; }
check_24() { local queue size; read_db_snapshot queue size || return $?; [[ "${queue}" == '0:0:0:0:9' ]] || remote_die 'queue state changed'; printf '%s\n' 'QUEUES=0:0:0:0:9'; }
check_25() { require_bot_idle || return $?; printf '%s\n' 'WEBHOOK=EMPTY;PENDING=0'; }
check_26() { local id since logs; id="$(one_running_id backend)" || return $?; since="$(pre_runtime_value backend_started_at)" || remote_die 'backend start timestamp missing'; logs="$(docker logs "${id}" --since "${since}" 2>&1)" || remote_die 'backend conflict-log inventory failed'; if printf '%s\n' "${logs}" | grep -E 'Conflict: terminated by other getUpdates request|terminated by other getUpdates'; then remote_die 'HTTP 409 getUpdates conflict detected'; fi; printf '%s\n' 'HTTP_409_CONFLICT=ABSENT'; }
check_27() { local observed; observed="$(caddy version)" || remote_die 'Caddy version unavailable'; python3 "${HELPER}" caddy-version "${CADDY_VERSION}" "${observed}" || remote_die 'Caddy version mismatch'; }
check_28() { [[ "$(systemctl is-active caddy)" == active ]] || remote_die 'Caddy service inactive'; printf '%s\n' 'CADDY_SERVICE=ACTIVE'; }
check_29() { [[ "$(remote_hash "${CADDYFILE}")" == "${CADDYFILE_SHA256}" ]] || remote_die 'Caddyfile bytes changed'; printf '%s\n' 'CADDYFILE=EXACT_SHA256'; }
check_30() { caddy validate --config "${CADDYFILE}" --adapter caddyfile >/dev/null || remote_die 'Caddy validation failed'; printf '%s\n' 'CADDY_VALIDATE=PASS'; }
check_31() { [[ ! -e "${DRAIN_MARKER}" && ! -L "${DRAIN_MARKER}" ]] || remote_die 'drain marker appeared'; printf '%s\n' 'DRAIN_MARKER=ABSENT'; }
check_32() { require_public_health_headers capture || return $?; printf '%s\n' 'PUBLIC_HEADERS=CAPTURED'; }
check_33() { require_tls_profile || return $?; printf '%s\n' 'TLS12=AVAILABLE;TLS13=UNAVAILABLE'; }
check_34() { require_public_health_headers absent || return $?; printf '%s\n' 'ALT_SVC=ABSENT'; }
check_35() { local udp_inventory; udp_inventory="$(ss -H -lun 'sport = :443')" || remote_die 'UDP port 443 inventory failed'; [[ -z "${udp_inventory}" ]] || remote_die 'UDP port 443 unexpectedly bound'; printf '%s\n' 'UDP_443=ABSENT'; }
check_36() { local queue size; read_db_snapshot queue size || return $?; [[ "${size}" =~ ^[1-9][0-9]*$ ]] || remote_die 'database size is not positive'; printf '%s\n' 'POSTGRESQL_SIZE=POSITIVE_BYTES'; }
check_37() { local queue size required free; read_db_snapshot queue size || return $?; [[ "${size}" =~ ^[1-9][0-9]*$ ]] || remote_die 'database size malformed'; required=$((4 * size)); ((required >= 2147483648)) || required=2147483648; free="$(df --output=avail -B1 "${BACKUP_BASE}" | awk 'NR==2 {print $1}')" || remote_die 'free space unavailable'; [[ "${free}" =~ ^[0-9]+$ ]] && ((free >= required)) || remote_die 'free space below required threshold'; printf '%s\n' 'FREE_SPACE=AT_LEAST_MAX_4X_DB_OR_2G'; }
check_38() { local backend postgres; backend="$(one_running_id backend)" || return $?; postgres="$(one_running_id postgres)" || return $?; [[ "${backend}" == "$(pre_runtime_value backend_id)" && "${postgres}" == "$(pre_runtime_value postgres_id)" ]] || remote_die 'container identity changed'; [[ "$(docker inspect --format '{{.State.StartedAt}}' "${backend}")" == "$(pre_runtime_value backend_started_at)" && "$(docker inspect --format '{{.State.StartedAt}}' "${postgres}")" == "$(pre_runtime_value postgres_started_at)" ]] || remote_die 'container start time changed'; printf '%s\n' 'CONTAINERS=UNCHANGED'; }
check_39() { [[ "$(remote_hash .env)" == "$(remote_hash "${PRE_ROOT}/.env.expected-post")" ]] || remote_die 'environment bytes differ from exact derivation'; python3 "${HELPER}" env-verify "${PRE_ROOT}/.env" .env >/dev/null || remote_die 'environment derivation verification failed'; printf '%s\n' 'ENV=EXACT_DERIVATION'; }
check_40() { local result; result="$(run_flyway_from_source)" || remote_die 'Flyway verifier execution failed'; [[ "${result}" == 'FLYWAY=125:1:0:0' ]] || remote_die 'Flyway state mismatch'; printf '%s\n' 'FLYWAY=125:1:0:0'; }

run_flyway_from_source() {
  python3 - "${SOURCE_ROOT}/orchestrator.sh" <<'PY' | /bin/bash -s -- "${V125_IMAGE_TAG}"
import sys
raw=open(sys.argv[1],"rb").read().splitlines(keepends=True)
b=b"# V126_PREREQ_FLYWAY_VERIFIER_BEGIN"; e=b"# V126_PREREQ_FLYWAY_VERIFIER_END"
bi=[i for i,line in enumerate(raw) if line.rstrip(b"\r\n")==b]
ei=[i for i,line in enumerate(raw) if line.rstrip(b"\r\n")==e]
if len(bi)!=1 or len(ei)!=1 or bi[0]>=ei[0]: raise SystemExit(1)
sys.stdout.buffer.write(b"".join(raw[bi[0]+1:ei[0]]))
PY
}

run_check_action() {
  local ordinal="$1" map_name expected function
  [[ "${ordinal}" =~ ^[1-9][0-9]*$ && "${ordinal}" -le 40 ]] || remote_die 'invalid post-sync check ordinal'
  map_name="$(awk -F '\t' -v n="${ordinal}" '$1==n {print $2}' "${CHECK_MAP}")"
  expected="$(awk -F '\t' -v n="${ordinal}" '$1==n {print $3}' "${CHECK_MAP}")"
  [[ "${map_name}" =~ ^[A-Z][A-Z0-9_]*$ && "${expected}" =~ ^[A-Za-z0-9_./:\;=,-]+$ ]] || remote_die 'post-sync check map row is malformed'
  function="check_${ordinal}"
  [[ "$(type -t "${function}")" == function ]] || remote_die 'post-sync check implementation is missing'
  if [[ "${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" == 17 && "${ordinal}" == 17 ]]; then export V126_FIXTURE_CURL_HEALTH_ERROR=1; fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" == "${ordinal}" && ( "${ordinal}" == 32 || "${ordinal}" == 34 ) ]]; then export V126_FIXTURE_HEALTH_HEADERS_ERROR=1; fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" == 23 && "${ordinal}" == 23 ]]; then export V126_FIXTURE_CURL_VERSION_ERROR=1; fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" == 24 && "${ordinal}" == 24 ]]; then export V126_FIXTURE_DB_OUTPUT_ERROR=1; fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" == 26 && "${ordinal}" == 26 ]]; then export V126_FIXTURE_DOCKER_LOGS_ERROR=1; fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" == 33 && "${ordinal}" == 33 ]]; then export V126_FIXTURE_TLS13_CLIENT_ERROR=1; fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK:-}" == 35 && "${ordinal}" == 35 ]]; then export V126_FIXTURE_SS_ERROR=1; fi
  if [[ "${V126_PREREQ_FIXTURE_FAIL_CHECK:-}" == "${ordinal}" ]]; then V126_PREREQ_FIXTURE_FAIL_ACTION="${map_name}"; fi
  (cd "${STAGING_PATH}" && run_tracked "$(printf 'C%02d' "${ordinal}")" "${map_name}" "${expected}" "${function}") || return $?
}

write_phase_action() {
  local ordinal="$1" name expected
  case "${ordinal}" in
    1) name=SYNC_WRITE_COMPOSE; expected=SYNC_WRITE_1=COMPOSE ;;
    2) name=SYNC_WRITE_MAINTENANCE_GUARD; expected=SYNC_WRITE_2=MAINTENANCE_GUARD ;;
    3) name=SYNC_WRITE_ADMISSION_GUARD; expected=SYNC_WRITE_3=ADMISSION_GUARD ;;
    4) name=SYNC_WRITE_ENV; expected=SYNC_WRITE_4=ENV ;;
    *) remote_die 'invalid write phase' ;;
  esac
  run_tracked "$(printf 'R%02d' $((ordinal + 3)))" "${name}" "${expected}" write_action "${ordinal}" || return $?
}

controller_failure_action() {
  remote_require_source_closure || return $?
  local ordinal="$1" name="$2" status="$3" signal_name="$4" out_sha="$5" err_sha="$6" last_passed="$7"
  if [[ ! -e "${RUN_ROOT}/first-failure.json" && ! -L "${RUN_ROOT}/first-failure.json" ]]; then
    python3 "${HELPER}" first-failure "${RUN_ROOT}/first-failure.json" "${RUN_ID}" "${ordinal}" "${name}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${status}" "${signal_name}" NONE "${out_sha}" "${err_sha}" "${last_passed}" ||
      remote_die 'remote first-failure persistence failed'
  fi
  if [[ "${V126_PREREQ_FIXTURE_TAMPER_FIRST_FAILURE_MODE:-0}" == 1 ]]; then chmod 0600 "${RUN_ROOT}/first-failure.json"; fi
  remote_require_evidence_file "${RUN_ROOT}/first-failure.json" 400 'remote first failure' || return $?
  python3 "${HELPER}" verify-first-failure "${RUN_ROOT}/first-failure.json" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${status}" >/dev/null ||
    remote_die 'remote first failure is not canonical or bound to the failed action'
  printf '%s\n' 'CONTROLLER_FAILURE=RECORDED'
}

writes_occurred() {
  local n marker_hash expected_marker_hash compose_hash maintenance_hash admission_hash env_hash expected_env_hash detected=0
  for n in 1 2 3 4; do
    if [[ -e "${STATE_ROOT}/write-${n}.completed" || -L "${STATE_ROOT}/write-${n}.completed" ]]; then
      remote_require_evidence_file "${STATE_ROOT}/write-${n}.completed" 400 'write-completion marker' || return 2
      marker_hash="$(remote_hash "${STATE_ROOT}/write-${n}.completed")" || return 2
      expected_marker_hash="$(printf 'write=%s\n' "${n}" | remote_hash /dev/stdin)" || return 2
      [[ "${marker_hash}" == "${expected_marker_hash}" ]] || { printf '%s\n' 'write-completion marker bytes are invalid' >&2; return 2; }
      detected=1
    fi
  done
  compose_hash="$(remote_hash "${STAGING_PATH}/docker-compose.yml")" || return 2
  maintenance_hash="$(remote_hash "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh")" || return 2
  env_hash="$(remote_hash "${STAGING_PATH}/.env")" || return 2
  expected_env_hash="$(remote_hash "${PRE_ROOT}/.env.expected-post")" || return 2
  if [[ "${compose_hash}" == "${RELEASE_COMPOSE_SHA256}" && "${RELEASE_COMPOSE_SHA256}" != "${PRE_COMPOSE_SHA256}" ]]; then detected=1
  elif [[ "${compose_hash}" != "${PRE_COMPOSE_SHA256}" ]]; then printf '%s\n' 'Compose write state is indeterminate' >&2; return 2
  fi
  if [[ "${maintenance_hash}" == "${RELEASE_MAINTENANCE_SHA256}" && "${RELEASE_MAINTENANCE_SHA256}" != "${PRE_MAINTENANCE_SHA256}" ]]; then detected=1
  elif [[ "${maintenance_hash}" != "${PRE_MAINTENANCE_SHA256}" ]]; then printf '%s\n' 'maintenance-guard write state is indeterminate' >&2; return 2
  fi
  if [[ "${env_hash}" == "${expected_env_hash}" && "${expected_env_hash}" != "${PRE_ENV_SHA256}" ]]; then detected=1
  elif [[ "${env_hash}" != "${PRE_ENV_SHA256}" ]]; then printf '%s\n' 'environment write state is indeterminate' >&2; return 2
  fi
  if [[ -e "${STAGING_PATH}/scripts/validate-staging-admission.sh" || -L "${STAGING_PATH}/scripts/validate-staging-admission.sh" ]]; then
    [[ -f "${STAGING_PATH}/scripts/validate-staging-admission.sh" && ! -L "${STAGING_PATH}/scripts/validate-staging-admission.sh" ]] || { printf '%s\n' 'admission-guard write state is unsafe' >&2; return 2; }
    admission_hash="$(remote_hash "${STAGING_PATH}/scripts/validate-staging-admission.sh")" || return 2
    [[ "${admission_hash}" == "${RELEASE_ADMISSION_SHA256}" ]] || { printf '%s\n' 'admission-guard write state is indeterminate' >&2; return 2; }
    detected=1
  fi
  [[ "${detected}" == 1 ]] && return 0
  return 10
}

restore_one() {
  local name="$1" source="$2" target="$3" mode uid gid
  uid="$(metadata_field "${name}" 2)" || return $?
  gid="$(metadata_field "${name}" 3)" || return $?
  mode="$(metadata_field "${name}" 4)" || return $?
  python3 "${HELPER}" atomic-install "${source}" "${target}" "${mode}" "${uid}" "${gid}" replace >/dev/null || return $?
}

rollback_action() {
  local original_status="$1" rollback_status=0 write_state=0 admission_uid admission_gid
  remote_require_source_closure || return $?
  require_rollback_inputs || return $?
  remote_require_evidence_file "${RUN_ROOT}/first-failure.json" 400 'remote first failure' || return $?
  python3 "${HELPER}" verify-first-failure "${RUN_ROOT}/first-failure.json" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${original_status}" >/dev/null ||
    remote_die 'first failure is not canonical or bound to this rollback'
  [[ ! -e "${RUN_ROOT}/rollback.started.json" && ! -L "${RUN_ROOT}/rollback.started.json" ]] || remote_die 'rollback already invoked'
  python3 "${HELPER}" event "${RUN_ROOT}/rollback.started.json" "${RUN_ID}" ROLLBACK_STARTED "${RELEASE_SHA}" "${SCRIPT_SHA256}" 0 "original_status=${original_status}" NONE >/dev/null ||
    remote_die 'rollback-start persistence failed'
  remote_require_evidence_file "${RUN_ROOT}/rollback.started.json" 400 'rollback-start record' || return $?
  writes_occurred || write_state=$?
  case "${write_state}" in
    0)
    restore_one docker-compose.yml "${PRE_ROOT}/docker-compose.yml" "${STAGING_PATH}/docker-compose.yml" || rollback_status=1
    restore_one check-staging-maintenance-config.sh "${PRE_ROOT}/check-staging-maintenance-config.sh" "${STAGING_PATH}/scripts/check-staging-maintenance-config.sh" || rollback_status=1
    restore_one .env "${PRE_ROOT}/.env" "${STAGING_PATH}/.env" || rollback_status=1
    if [[ -e "${STAGING_PATH}/scripts/validate-staging-admission.sh" && ! -L "${STAGING_PATH}/scripts/validate-staging-admission.sh" && "$(remote_hash "${STAGING_PATH}/scripts/validate-staging-admission.sh")" == "${RELEASE_ADMISSION_SHA256}" ]]; then
      admission_uid="$(metadata_field check-staging-maintenance-config.sh 2)" || rollback_status=1
      admission_gid="$(metadata_field check-staging-maintenance-config.sh 3)" || rollback_status=1
      python3 "${HELPER}" durable-unlink "${STAGING_PATH}/scripts/validate-staging-admission.sh" "${RELEASE_ADMISSION_SHA256}" 0755 "${admission_uid}" "${admission_gid}" >/dev/null || rollback_status=1
    elif [[ -e "${STAGING_PATH}/scripts/validate-staging-admission.sh" || -L "${STAGING_PATH}/scripts/validate-staging-admission.sh" ]]; then
      rollback_status=1
    fi
    ;;
    10) ;;
    *) remote_die 'write state is indeterminate; rollback was not attempted' ;;
  esac
  if [[ "${V126_PREREQ_FIXTURE_ROLLBACK_FAIL:-0}" == 1 ]]; then rollback_status=88; fi
  if [[ "${rollback_status}" == 0 ]]; then
    python3 "${HELPER}" event "${RUN_ROOT}/rollback.result.json" "${RUN_ID}" ROLLBACK_PASSED "${RELEASE_SHA}" "${SCRIPT_SHA256}" 0 "original_status=${original_status}" NONE >/dev/null ||
      rollback_status=1
  else
    python3 "${HELPER}" event "${RUN_ROOT}/rollback.result.json" "${RUN_ID}" ROLLBACK_FAILED "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${rollback_status}" "original_status=${original_status}" NONE >/dev/null || true
  fi
  if [[ -f "${RUN_ROOT}/rollback.result.json" && ! -L "${RUN_ROOT}/rollback.result.json" ]]; then
    remote_require_evidence_file "${RUN_ROOT}/rollback.result.json" 400 'rollback-result record' || rollback_status=1
  fi
  return "${rollback_status}"
}

verify_restored_action() {
  remote_require_source_closure || return $?
  require_rollback_inputs || return $?
  require_pre_file_baseline || return $?
  baseline_full || return $?
  local backend postgres
  backend="$(one_running_id backend)" || return $?
  postgres="$(one_running_id postgres)" || return $?
  [[ "${backend}" == "$(pre_runtime_value backend_id)" && "${postgres}" == "$(pre_runtime_value postgres_id)" ]] || remote_die 'restored container identity mismatch'
  [[ "$(docker inspect --format '{{.State.StartedAt}}' "${backend}")" == "$(pre_runtime_value backend_started_at)" ]] || remote_die 'restored backend start timestamp mismatch'
  [[ "$(docker inspect --format '{{.State.StartedAt}}' "${postgres}")" == "$(pre_runtime_value postgres_started_at)" ]] || remote_die 'restored PostgreSQL start timestamp mismatch'
  [[ "$(run_flyway_from_source)" == 'FLYWAY=125:1:0:0' ]] || remote_die 'restored Flyway state mismatch'
  python3 "${HELPER}" event "${RUN_ROOT}/restored-baseline.json" "${RUN_ID}" RESTORED_BASELINE_PASSED "${RELEASE_SHA}" "${SCRIPT_SHA256}" 0 'V125=EXACT' NONE >/dev/null ||
    remote_die 'restored-baseline persistence failed'
  remote_require_evidence_file "${RUN_ROOT}/restored-baseline.json" 400 'restored-baseline record' || return $?
  printf '%s\n' 'RESTORED_BASELINE=PASS'
}

success_action() {
  local name
  name="$(awk -F '\t' '$1==40 {print $2}' "${CHECK_MAP}")"
  run_tracked R08 CANONICAL_SUCCESS 'PREREQUISITE_SYNC=PASS' success_body
}

require_phase_evidence_ownership() {
  local ordinal="$1" name="$2"
  remote_require_evidence_file "$(phase_started_path "${ordinal}" "${name}")" 400 'phase STARTED checkpoint' || return $?
  remote_require_evidence_file "$(phase_result_path "${ordinal}" "${name}" passed)" 400 'phase PASSED checkpoint' || return $?
  remote_require_evidence_file "${RAW_ROOT}/${ordinal}-${name}.stdout" 600 'phase stdout capture' || return $?
  remote_require_evidence_file "${RAW_ROOT}/${ordinal}-${name}.stderr" 600 'phase stderr capture'
}

success_body() {
  [[ ! -e "${RUN_ROOT}/first-failure.json" && ! -L "${RUN_ROOT}/first-failure.json" ]] || remote_die 'success is forbidden after a first failure'
  [[ ! -e "${RUN_ROOT}/rollback.started.json" && ! -L "${RUN_ROOT}/rollback.started.json" ]] || remote_die 'success is forbidden after rollback started'
  require_phase_evidence_ownership R01 SOURCE_CLOSURE_VERIFIED || return $?
  require_phase_evidence_ownership R02 ROLLBACK_INPUTS_CAPTURED || return $?
  require_phase_evidence_ownership R03 ADMISSION_GUARD_ABSENT || return $?
  require_phase_evidence_ownership R04 SYNC_WRITE_COMPOSE || return $?
  require_phase_evidence_ownership R05 SYNC_WRITE_MAINTENANCE_GUARD || return $?
  require_phase_evidence_ownership R06 SYNC_WRITE_ADMISSION_GUARD || return $?
  require_phase_evidence_ownership R07 SYNC_WRITE_ENV || return $?
  local n check_name
  for n in $(seq 1 40); do
    check_name="$(awk -F '\t' -v n="${n}" '$1==n {print $2}' "${CHECK_MAP}")"
    require_phase_evidence_ownership "$(printf 'C%02d' "${n}")" "${check_name}" || return $?
  done
  python3 "${HELPER}" verify-chain "${RUN_ROOT}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" R08 >/dev/null ||
    remote_die 'passed-phase and check chain is incomplete or invalid'
  printf '%s\n' 'PREREQUISITE_SYNC=PASS'
}

remote_validate_context || exit $?
case "${ACTION}" in
  prewrite-baseline|allocate-evidence) ;;
  *) acquire_remote_lock || exit $? ;;
esac
case "${ACTION}" in
  prewrite-baseline) baseline_full || exit $?; printf '%s\n' 'PREWRITE_BASELINE=PASS' ;;
  allocate-evidence) allocate_evidence || exit $? ;;
  source-closure) run_tracked R01 SOURCE_CLOSURE_VERIFIED SOURCE_CLOSURE=VERIFIED source_closure_action || exit $? ;;
  capture-rollback) run_tracked R02 ROLLBACK_INPUTS_CAPTURED ROLLBACK_INPUTS=CAPTURED capture_rollback_action || exit $? ;;
  admission-absent) run_tracked R03 ADMISSION_GUARD_ABSENT ADMISSION_GUARD=ABSENT admission_absent_action || exit $? ;;
  write) write_phase_action "$@" || exit $? ;;
  check) run_check_action "$@" || exit $? ;;
  controller-failure) controller_failure_action "$@" || exit $? ;;
  rollback) rollback_action "$@" || exit $? ;;
  verify-restored) verify_restored_action || exit $? ;;
  success) success_action || exit $? ;;
  *) remote_die 'unknown remote action' ;;
esac
# V126_PREREQ_REMOTE_PAYLOAD_END
V126_PREREQ_REMOTE_PAYLOAD

: <<'V126_PREREQ_FLYWAY_VERIFIER'
# V126_PREREQ_FLYWAY_VERIFIER_BEGIN
#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
readonly V125_IMAGE_TAG="${1:?exact V125 image tag}"
STAGING_PATH='/opt/hookah-bot'
SAFE_PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
if [[ -n "${V126_PREREQ_REMOTE_FIXTURE_ROOT:-}" ]]; then
  STAGING_PATH="${V126_PREREQ_REMOTE_FIXTURE_ROOT}/staging"
  SAFE_PATH="${PATH}"
fi
if [[ -n "${V126_PREREQ_REMOTE_FIXTURE_ROOT:-}" ]]; then
  result="$(
    cd "${STAGING_PATH}"
    env -i PATH="${SAFE_PATH}" BACKEND_IMAGE="${V125_IMAGE_TAG}" V126_FIXTURE_MOCK_LOG="${V126_FIXTURE_MOCK_LOG:?}" \
      docker compose --env-file .env --file docker-compose.yml exec -T postgres sh -c \
        'exec env PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X --no-psqlrc -Atq --set=ON_ERROR_STOP=1' <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT CONCAT(
  COALESCE(MAX(version::integer), 0), ':',
  COUNT(*) FILTER (WHERE version = '125' AND success), ':',
  COUNT(*) FILTER (WHERE version = '126'), ':',
  COUNT(*) FILTER (WHERE NOT success)
)
FROM flyway_schema_history;
ROLLBACK;
SQL
  )"
else
  result="$(
    cd "${STAGING_PATH}"
    env -i PATH="${SAFE_PATH}" BACKEND_IMAGE="${V125_IMAGE_TAG}" \
      docker compose --env-file .env --file docker-compose.yml exec -T postgres sh -c \
      'exec env PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X --no-psqlrc -Atq --set=ON_ERROR_STOP=1' <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT CONCAT(
  COALESCE(MAX(version::integer), 0), ':',
  COUNT(*) FILTER (WHERE version = '125' AND success), ':',
  COUNT(*) FILTER (WHERE version = '126'), ':',
  COUNT(*) FILTER (WHERE NOT success)
)
FROM flyway_schema_history;
ROLLBACK;
SQL
  )"
fi
[[ "${result}" == '125:1:0:0' ]] || { printf '%s\n' 'Flyway verifier returned a noncanonical result' >&2; exit 1; }
printf '%s\n' 'FLYWAY=125:1:0:0'
# V126_PREREQ_FLYWAY_VERIFIER_END
V126_PREREQ_FLYWAY_VERIFIER

main "$@"
