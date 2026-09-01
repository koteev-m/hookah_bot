#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

ENV_FILE="${1:-}"
EXPLICIT_AUTHORIZATION="${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED:-false}"
SELF_TEST_DIR=""

fail() {
  echo "Staging maintenance configuration rejected: $1" >&2
  exit 4
}

env_value() {
  local key="$1"
  awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "${ENV_FILE}"
}

key_count() {
  local key="$1"
  awk -F= -v key="${key}" '$1 == key { count++ } END { print count + 0 }' "${ENV_FILE}"
}

require_single_key() {
  local key="$1"
  [[ "$(key_count "${key}")" == "1" ]] || fail "${key} must appear exactly once"
}

require_optional_unique_key() {
  local key="$1"
  local count
  count="$(key_count "${key}")"
  [[ "${count}" == "0" || "${count}" == "1" ]] || fail "${key} must not be duplicated"
}

require_unique_list_entries() {
  local value="$1"
  local label="$2"
  local entry
  local seen=","
  local entries=()
  IFS=',' read -r -a entries <<< "${value}"
  for entry in "${entries[@]}"; do
    if [[ "${seen}" == *",${entry},"* ]]; then
      fail "${label} must not contain duplicates"
    fi
    seen="${seen}${entry},"
  done
}

require_signed_int64() {
  local token="$1"
  local label="$2"
  local digits
  local maximum
  if [[ "${token}" == -* ]]; then
    digits="${token#-}"
    maximum="9223372036854775808"
  else
    digits="${token}"
    maximum="9223372036854775807"
  fi
  if [[ "${#digits}" -gt 19 ||
    ("${#digits}" -eq 19 && "${digits}" > "${maximum}") ]]; then
    fail "${label} contains an out-of-range ID"
  fi
}

validate_file() {
  [[ -n "${ENV_FILE}" ]] || fail "an env file path is required"
  [[ -f "${ENV_FILE}" ]] || fail "env file is unavailable"

  require_single_key APP_ENV
  require_single_key TELEGRAM_TRAFFIC_POLICY
  require_optional_unique_key STAGING_MAINTENANCE_MODE
  require_optional_unique_key STAGING_MAINTENANCE_ALLOWED_USER_IDS
  require_optional_unique_key STAGING_MAINTENANCE_ALLOWED_CHAT_IDS

  local app_env
  local traffic_policy
  local mode
  app_env="$(env_value APP_ENV | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  traffic_policy="$(env_value TELEGRAM_TRAFFIC_POLICY | tr '[:lower:]' '[:upper:]' | tr -d '[:space:]')"
  mode="$(env_value STAGING_MAINTENANCE_MODE | tr '[:lower:]' '[:upper:]' | tr -d '[:space:]')"
  mode="${mode:-OFF}"

  [[ "${app_env}" == "staging" ]] || fail "APP_ENV must be staging"

  case "${mode}" in
    OFF)
      echo "Staging maintenance preflight: OFF"
      ;;
    V126_SMOKE)
      [[ "${traffic_policy}" == "PRODUCT" ]] ||
        fail "V126_SMOKE requires TELEGRAM_TRAFFIC_POLICY=PRODUCT"
      [[ "${EXPLICIT_AUTHORIZATION}" == "true" ]] ||
        fail "V126_SMOKE requires an explicit reviewed deploy authorization flag"
      require_single_key STAGING_MAINTENANCE_ALLOWED_USER_IDS
      require_single_key STAGING_MAINTENANCE_ALLOWED_CHAT_IDS

      local allowed_users
      local allowed_chats
      local user
      local chat
      local user_entries=()
      local chat_entries=()
      allowed_users="$(env_value STAGING_MAINTENANCE_ALLOWED_USER_IDS)"
      allowed_chats="$(env_value STAGING_MAINTENANCE_ALLOWED_CHAT_IDS)"
      [[ "${allowed_users}" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]] ||
        fail "the maintenance user list must be nonempty canonical positive IDs"
      [[ "${allowed_chats}" =~ ^-?[1-9][0-9]*(,-?[1-9][0-9]*)*$ ]] ||
        fail "the maintenance chat list must be nonempty canonical signed IDs"
      require_unique_list_entries "${allowed_users}" "the maintenance user list"
      require_unique_list_entries "${allowed_chats}" "the maintenance chat list"

      IFS=',' read -r -a user_entries <<< "${allowed_users}"
      for user in "${user_entries[@]}"; do
        require_signed_int64 "${user}" "the maintenance user list"
      done
      IFS=',' read -r -a chat_entries <<< "${allowed_chats}"
      for chat in "${chat_entries[@]}"; do
        require_signed_int64 "${chat}" "the maintenance chat list"
      done

      local positive_chats=()
      for chat in "${chat_entries[@]}"; do
        if [[ "${chat}" =~ ^[1-9][0-9]*$ ]]; then
          positive_chats+=("${chat}")
        fi
      done
      [[ "${#positive_chats[@]}" == "${#user_entries[@]}" ]] ||
        fail "positive maintenance chats must exactly match maintenance users"
      for user in "${user_entries[@]}"; do
        [[ ",${allowed_chats}," == *",${user},"* ]] ||
          fail "positive maintenance chats must exactly match maintenance users"
      done
      echo "Staging maintenance preflight: V126_SMOKE explicitly authorized"
      ;;
    *)
      fail "STAGING_MAINTENANCE_MODE must be OFF or V126_SMOKE"
      ;;
  esac
}

self_test() {
  local test_dir
  local script_path
  test_dir="$(mktemp -d "${TMPDIR:-/tmp}/ht-12m-maintenance-preflight.XXXXXX")"
  SELF_TEST_DIR="${test_dir}"
  script_path="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  trap 'if [[ -n "${SELF_TEST_DIR}" && "$(basename "${SELF_TEST_DIR}")" == ht-12m-maintenance-preflight.* ]]; then rm -rf -- "${SELF_TEST_DIR}"; fi' EXIT

  ENV_FILE="${test_dir}/off.env"
  printf '%s\n' \
    'APP_ENV=staging' \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'STAGING_MAINTENANCE_MODE=OFF' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=' > "${ENV_FILE}"
  bash "${script_path}" "${ENV_FILE}" >/dev/null

  ENV_FILE="${test_dir}/active.env"
  printf '%s\n' \
    'APP_ENV=staging' \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'STAGING_MAINTENANCE_MODE=V126_SMOKE' \
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=101,202' \
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=101,202,-303' > "${ENV_FILE}"
  STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
    bash "${script_path}" "${ENV_FILE}" >/dev/null

  if bash "${script_path}" "${ENV_FILE}" >/dev/null 2>&1; then
    fail "self-test expected active mode without deploy authorization to fail"
  fi
  sed 's/101,202,-303/101,-303/' "${test_dir}/active.env" > "${test_dir}/inconsistent.env"
  ENV_FILE="${test_dir}/inconsistent.env"
  if STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
    bash "${script_path}" "${ENV_FILE}" >/dev/null 2>&1; then
    fail "self-test expected inconsistent identity sets to fail"
  fi
  sed 's/101,202/101,9223372036854775808/g' "${test_dir}/active.env" > "${test_dir}/overflow.env"
  ENV_FILE="${test_dir}/overflow.env"
  if STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
    bash "${script_path}" "${ENV_FILE}" >/dev/null 2>&1; then
    fail "self-test expected an out-of-range identity to fail"
  fi

  echo "Staging maintenance preflight self-test: PASS"
}

if [[ "${ENV_FILE}" == "--self-test" ]]; then
  self_test
  exit 0
fi

validate_file
