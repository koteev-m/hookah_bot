#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEPLOY_SCRIPT="${SCRIPT_DIR}/deploy-staging.sh"

CONTROL_PERSIST="${HOOKAH_STAGING_CM_CONTROL_PERSIST:-10m}"
CONNECT_RETRIES="${HOOKAH_STAGING_CM_CONNECT_RETRIES:-3}"
RETRY_DELAY_SECONDS="${HOOKAH_STAGING_CM_RETRY_DELAY_SECONDS:-3}"
CONNECT_TIMEOUT_SECONDS=10
SERVER_ALIVE_INTERVAL=30
SERVER_ALIVE_COUNT_MAX=3

REMOTE=""
REAL_SSH=""
TMP_ROOT=""
CONTROL_DIR=""
WRAPPER_DIR=""
CONTROL_PATH=""
SSH_WRAPPER=""
MASTER_STARTED=false
CLEANUP_RAN=false
CLEANUP_DIRS=()

usage() {
  cat <<'EOF'
Usage: ./scripts/deploy-staging-controlmaster.sh <ssh-host-alias>

Purpose:
  Run the existing staging deploy through one persistent authenticated SSH
  ControlMaster connection.

Example:
  STAGING_PATH=/opt/hookah-bot \
  STAGING_DOMAIN=staging.example.com \
  DOCKER_PLATFORM=linux/amd64 \
  BACKEND_IMAGE=example-backend:staging \
  ./scripts/deploy-staging-controlmaster.sh staging-alias

Behavior:
  - Reuses ./scripts/deploy-staging.sh for build, upload, restart, and health checks.
  - Makes ssh and rsync in the child deploy process reuse one control socket.
  - Does not alter SSH server configuration, firewall, fail2ban, credentials, or ~/.ssh.
  - Leaves the normal deploy command available:
      ./scripts/deploy-staging.sh <ssh-host-alias>

Optional controls:
  HOOKAH_STAGING_CM_CONNECT_RETRIES=3
  HOOKAH_STAGING_CM_RETRY_DELAY_SECONDS=3
  HOOKAH_STAGING_CM_CONTROL_PERSIST=10m
EOF
}

die() {
  echo "ERROR: $*" >&2
  exit 2
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    die "Missing required command: $1"
  fi
}

require_positive_integer() {
  local name="$1"
  local value="$2"

  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    die "${name} must be a positive integer"
  fi
}

require_control_persist() {
  local value="$1"

  if [[ ! "${value}" =~ ^[1-9][0-9]*[smhdw]?$ ]]; then
    die "HOOKAH_STAGING_CM_CONTROL_PERSIST must be a bounded duration such as 600 or 10m"
  fi
}

remember_temp_dir() {
  local result_var="$1"
  local prefix="$2"
  local dir

  dir="$(mktemp -d "${TMP_ROOT}/${prefix}.XXXXXXXXXX")"
  CLEANUP_DIRS+=("${dir}")
  printf -v "${result_var}" '%s' "${dir}"
}

is_helper_temp_dir() {
  local dir="$1"

  [[ -n "${dir}" ]] || return 1
  [[ "${dir}" == "${TMP_ROOT}/hcm."* || "${dir}" == "${TMP_ROOT}/hcmw."* ]] || return 1
  [[ -d "${dir}" ]] || return 1
}

cleanup() {
  local status=$?
  local dir

  if [[ "${CLEANUP_RAN}" == "true" ]]; then
    return "${status}"
  fi
  CLEANUP_RAN=true

  if [[ "${MASTER_STARTED}" == "true" && -n "${CONTROL_PATH}" && -n "${REAL_SSH}" && -n "${REMOTE}" ]]; then
    "${REAL_SSH}" -O exit -S "${CONTROL_PATH}" "${REMOTE}" >/dev/null 2>&1 || true
  fi

  if (( ${#CLEANUP_DIRS[@]} > 0 )); then
    for dir in "${CLEANUP_DIRS[@]}"; do
      if is_helper_temp_dir "${dir}"; then
        rm -rf -- "${dir}"
      fi
    done
  fi

  return "${status}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if (( $# > 1 )); then
  usage >&2
  exit 2
fi

REMOTE="${1:-${STAGING_SSH:-}}"
if [[ -z "${REMOTE}" ]]; then
  usage >&2
  exit 2
fi
if [[ "${REMOTE}" == -* ]]; then
  die "Remote SSH target must not start with '-'"
fi

require_positive_integer "HOOKAH_STAGING_CM_CONNECT_RETRIES" "${CONNECT_RETRIES}"
require_positive_integer "HOOKAH_STAGING_CM_RETRY_DELAY_SECONDS" "${RETRY_DELAY_SECONDS}"
require_control_persist "${CONTROL_PERSIST}"

require_cmd mktemp
require_cmd chmod
require_cmd rm
require_cmd seq
require_cmd sleep
require_cmd docker
require_cmd ssh
require_cmd rsync
require_cmd gzip
require_cmd curl

if [[ ! -x "${DEPLOY_SCRIPT}" ]]; then
  die "Expected executable deploy script at ${DEPLOY_SCRIPT}"
fi

REAL_SSH="$(command -v ssh)"

if ! docker info >/dev/null 2>&1; then
  echo "Docker is unavailable. Start Docker Desktop or the Docker daemon, then retry." >&2
  echo "Persistent SSH deploy did not start." >&2
  exit 2
fi

if ! docker buildx version >/dev/null 2>&1; then
  echo "Docker buildx is required for cross-platform staging builds." >&2
  echo "Persistent SSH deploy did not start." >&2
  exit 2
fi

TMP_ROOT="${TMPDIR:-/tmp}"
TMP_ROOT="${TMP_ROOT%/}"
if [[ -z "${TMP_ROOT}" || "${TMP_ROOT}" == "/" ]]; then
  TMP_ROOT="/tmp"
fi

remember_temp_dir CONTROL_DIR "hcm"
remember_temp_dir WRAPPER_DIR "hcmw"
CONTROL_PATH="${CONTROL_DIR}/cm.sock"
SSH_WRAPPER="${WRAPPER_DIR}/ssh"

cat > "${SSH_WRAPPER}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

: "${HOOKAH_STAGING_CM_REAL_SSH:?}"
: "${HOOKAH_STAGING_CM_CONTROL_PATH:?}"
: "${HOOKAH_STAGING_CM_REMOTE:?}"
: "${HOOKAH_STAGING_CM_CONNECT_TIMEOUT_SECONDS:?}"
: "${HOOKAH_STAGING_CM_SERVER_ALIVE_INTERVAL:?}"
: "${HOOKAH_STAGING_CM_SERVER_ALIVE_COUNT_MAX:?}"

if ! "${HOOKAH_STAGING_CM_REAL_SSH}" \
  -O check \
  -S "${HOOKAH_STAGING_CM_CONTROL_PATH}" \
  "${HOOKAH_STAGING_CM_REMOTE}" >/dev/null 2>&1; then
  echo "ControlMaster connection is not available; aborting SSH command." >&2
  exit 255
fi

exec "${HOOKAH_STAGING_CM_REAL_SSH}" \
  -S "${HOOKAH_STAGING_CM_CONTROL_PATH}" \
  -o ControlMaster=no \
  -o ControlPersist=no \
  -o BatchMode=yes \
  -o ProxyCommand=false \
  -o "ConnectTimeout=${HOOKAH_STAGING_CM_CONNECT_TIMEOUT_SECONDS}" \
  -o "ServerAliveInterval=${HOOKAH_STAGING_CM_SERVER_ALIVE_INTERVAL}" \
  -o "ServerAliveCountMax=${HOOKAH_STAGING_CM_SERVER_ALIVE_COUNT_MAX}" \
  "$@"
EOF
chmod 700 "${SSH_WRAPPER}"

echo "==> Opening persistent SSH ControlMaster for staging deploy"
for attempt in $(seq 1 "${CONNECT_RETRIES}"); do
  if "${REAL_SSH}" \
    -MNf \
    -o ControlMaster=yes \
    -o "ControlPath=${CONTROL_PATH}" \
    -o "ControlPersist=${CONTROL_PERSIST}" \
    -o BatchMode=yes \
    -o "ConnectTimeout=${CONNECT_TIMEOUT_SECONDS}" \
    -o "ServerAliveInterval=${SERVER_ALIVE_INTERVAL}" \
    -o "ServerAliveCountMax=${SERVER_ALIVE_COUNT_MAX}" \
    "${REMOTE}"; then
    MASTER_STARTED=true
    break
  fi

  if (( attempt < CONNECT_RETRIES )); then
    echo "Initial ControlMaster connection failed (${attempt}/${CONNECT_RETRIES}); retrying shortly..." >&2
    sleep "${RETRY_DELAY_SECONDS}"
  fi
done

if [[ "${MASTER_STARTED}" != "true" ]]; then
  echo "Could not establish the persistent SSH ControlMaster after ${CONNECT_RETRIES} attempt(s)." >&2
  echo "No fallback deploy was attempted. Normal deploy remains available manually:" >&2
  echo "  ./scripts/deploy-staging.sh <ssh-host-alias>" >&2
  exit 255
fi

if ! "${REAL_SSH}" -O check -S "${CONTROL_PATH}" "${REMOTE}" >/dev/null 2>&1; then
  echo "ControlMaster was opened but failed verification with ssh -O check." >&2
  echo "No fallback deploy was attempted. Normal deploy remains available manually:" >&2
  echo "  ./scripts/deploy-staging.sh <ssh-host-alias>" >&2
  exit 255
fi

echo "==> ControlMaster is ready; invoking existing staging deploy script"
cd "${REPO_ROOT}"

deploy_status=0
if PATH="${WRAPPER_DIR}:${PATH}" \
  RSYNC_RSH="${SSH_WRAPPER}" \
  HOOKAH_STAGING_CM_REAL_SSH="${REAL_SSH}" \
  HOOKAH_STAGING_CM_CONTROL_PATH="${CONTROL_PATH}" \
  HOOKAH_STAGING_CM_REMOTE="${REMOTE}" \
  HOOKAH_STAGING_CM_CONNECT_TIMEOUT_SECONDS="${CONNECT_TIMEOUT_SECONDS}" \
  HOOKAH_STAGING_CM_SERVER_ALIVE_INTERVAL="${SERVER_ALIVE_INTERVAL}" \
  HOOKAH_STAGING_CM_SERVER_ALIVE_COUNT_MAX="${SERVER_ALIVE_COUNT_MAX}" \
  "${DEPLOY_SCRIPT}" "${REMOTE}"; then
  deploy_status=0
else
  deploy_status=$?
fi

if (( deploy_status != 0 )); then
  echo "Persistent SSH deploy failed with exit code ${deploy_status}." >&2
  echo "No fallback deploy was attempted. Normal deploy remains available manually:" >&2
  echo "  ./scripts/deploy-staging.sh <ssh-host-alias>" >&2
fi

exit "${deploy_status}"
