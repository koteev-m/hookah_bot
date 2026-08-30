#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

PUBLIC_PILOT_PROFILE="public-pilot"
ISOLATED_ALLOWLIST_PROFILE="isolated-allowlist"
SELF_TEST_FIXTURE_DIR=""

usage() {
  cat <<'EOF'
Usage:
  validate-staging-admission.sh [--profile public-pilot|isolated-allowlist] [--env-file .env] [--compose-file docker-compose.yml]
  validate-staging-admission.sh --self-test [docker-compose.yml]

The default profile is public-pilot. The isolated-allowlist profile is an explicit,
separately reviewed smoke-only path. No validated values are printed.
EOF
}

fail() {
  echo "Staging admission guard failed: $*" >&2
  return 1
}

cleanup_self_test() {
  if [[ -n "${SELF_TEST_FIXTURE_DIR}" && -d "${SELF_TEST_FIXTURE_DIR}" ]]; then
    rm -rf -- "${SELF_TEST_FIXTURE_DIR}"
  fi
}

trim_whitespace() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "${value}"
}

strip_optional_quotes() {
  local value="$1"
  local length=${#value}
  if (( length >= 2 )); then
    if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]] ||
      [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
      value="${value:1:length-2}"
    fi
  fi
  printf '%s' "${value}"
}

dotenv_key_count() {
  local env_file="$1"
  local key="$2"
  awk -F= -v key="${key}" '$1 == key { count++ } END { print count + 0 }' "${env_file}"
}

dotenv_value() {
  local env_file="$1"
  local key="$2"
  awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "${env_file}"
}

require_exactly_one_key() {
  local env_file="$1"
  local key="$2"
  if [[ "$(dotenv_key_count "${env_file}" "${key}")" != "1" ]]; then
    fail "${key} must appear exactly once in the staging env"
  fi
}

require_at_most_one_key() {
  local env_file="$1"
  local key="$2"
  if (( $(dotenv_key_count "${env_file}" "${key}") > 1 )); then
    fail "${key} must not be duplicated in the staging env"
  fi
}

require_literal_env_value() {
  local env_file="$1"
  local key="$2"
  local value
  value="$(dotenv_value "${env_file}" "${key}")"
  if [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
    return 0
  fi
  if [[ "${value}" == *'$'* ]]; then
    fail "${key} must be an explicit literal, not Compose interpolation"
  fi
}

canonicalize_placeholder() {
  local value="$1"
  printf '%s' "${value}" |
    tr '[:upper:]' '[:lower:]' |
    sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//'
}

is_known_placeholder() {
  local canonical="$1"
  case "${canonical}" in
    change-me | please-change-me | please-set-when-enabled | set-when-enabled | \
      replace-me | replace-with-secret | dev-invite-pepper | local-dev-pepper | \
      example | example-secret | example-pepper | example-invite-pepper | \
      placeholder | placeholder-secret | placeholder-pepper | placeholder-invite-pepper | \
      your-secret | your-secret-here | your-pepper | your-pepper-here)
      return 0
      ;;
  esac
  case "-${canonical}-" in
    *-example-* | *-placeholder-*)
      return 0
      ;;
  esac
  return 1
}

validate_invite_pepper() {
  local value="$1"
  local trimmed
  local canonical
  trimmed="$(trim_whitespace "${value}")"
  if [[ -z "${trimmed}" ]]; then
    fail "VENUE_STAFF_INVITE_SECRET_PEPPER must be nonblank"
    return
  fi
  canonical="$(canonicalize_placeholder "${trimmed}")"
  if is_known_placeholder "${canonical}"; then
    fail "VENUE_STAFF_INVITE_SECRET_PEPPER must not be a known placeholder"
  fi
}

decimal_within_limit() {
  local digits="$1"
  local limit="$2"
  if (( ${#digits} < ${#limit} )); then
    return 0
  fi
  if (( ${#digits} > ${#limit} )); then
    return 1
  fi
  [[ "${digits}" < "${limit}" || "${digits}" == "${limit}" ]]
}

validate_allowlist_values() {
  local allowed_users="$1"
  local allowed_chats="$2"
  local entry
  local digits
  local limit
  local seen
  local -a entries

  if [[ ! "${allowed_users}" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ ]]; then
    fail "TELEGRAM_ALLOWED_USER_IDS must be a nonempty canonical positive-ID list in isolated ALLOWLIST"
    return
  fi
  if [[ ! "${allowed_chats}" =~ ^-?[1-9][0-9]*(,-?[1-9][0-9]*)*$ ]]; then
    fail "TELEGRAM_ALLOWED_CHAT_IDS must be a nonempty canonical signed-ID list in isolated ALLOWLIST"
    return
  fi

  IFS=',' read -r -a entries <<< "${allowed_users}"
  seen=","
  for entry in "${entries[@]}"; do
    if ! decimal_within_limit "${entry}" "9223372036854775807"; then
      fail "TELEGRAM_ALLOWED_USER_IDS contains a value outside signed 64-bit range"
      return
    fi
    if [[ "${seen}" == *",${entry},"* ]]; then
      fail "TELEGRAM_ALLOWED_USER_IDS must not contain duplicates"
      return
    fi
    seen+="${entry},"
    if [[ ",${allowed_chats}," != *",${entry},"* ]]; then
      fail "Every isolated ALLOWLIST user must have the matching positive private chat"
      return
    fi
  done

  IFS=',' read -r -a entries <<< "${allowed_chats}"
  seen=","
  for entry in "${entries[@]}"; do
    digits="${entry#-}"
    limit="9223372036854775807"
    if [[ "${entry}" == -* ]]; then
      limit="9223372036854775808"
    fi
    if ! decimal_within_limit "${digits}" "${limit}"; then
      fail "TELEGRAM_ALLOWED_CHAT_IDS contains a value outside signed 64-bit range"
      return
    fi
    if [[ "${seen}" == *",${entry},"* ]]; then
      fail "TELEGRAM_ALLOWED_CHAT_IDS must not contain duplicates"
      return
    fi
    seen+="${entry},"
  done
}

require_env_file_only_admission() {
  local compose_file="$1"
  local key
  local backend_env_file_found

  for key in \
    APP_ENV \
    TELEGRAM_TRAFFIC_POLICY \
    TELEGRAM_ALLOWED_USER_IDS \
    TELEGRAM_ALLOWED_CHAT_IDS \
    VENUE_STAFF_INVITE_SECRET_PEPPER; do
    if awk -v key="${key}" '$0 ~ "^[[:space:]]+" key ":[[:space:]]*" { found=1 } END { exit !found }' "${compose_file}"; then
      fail "${key} must come only from the fixed backend env_file, not Compose interpolation"
      return
    fi
    if awk -v key="${key}" '$0 ~ "^[[:space:]]*-[[:space:]]*" key "([=].*)?[[:space:]]*$" { found=1 } END { exit !found }' "${compose_file}"; then
      fail "${key} must not be inherited from the Compose process environment"
      return
    fi
  done

  backend_env_file_found="$(awk '
    /^  backend:[[:space:]]*$/ { in_backend=1; next }
    in_backend && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { in_backend=0; in_env_file=0 }
    in_backend && /^    env_file:[[:space:]]*$/ { in_env_file=1; next }
    in_backend && in_env_file && /^      - / { total++ }
    in_backend && in_env_file && /^      - \.\/\.env[[:space:]]*$/ { fixed++ }
    in_backend && in_env_file && /^    [A-Za-z0-9_.-]+:/ { in_env_file=0 }
    END { print (fixed + 0) ":" (total + 0) }
  ' "${compose_file}")"
  if [[ "${backend_env_file_found}" != "1:1" ]]; then
    fail "backend must use exactly one fixed ./.env env_file"
  fi
}

effective_admission_json() {
  local compose_file="$1"
  local env_file="$2"
  local compose_dir
  local compose_name
  local env_dir
  local env_name
  compose_dir="$(cd "$(dirname "${compose_file}")" && pwd)"
  compose_name="$(basename "${compose_file}")"
  env_dir="$(cd "$(dirname "${env_file}")" && pwd)"
  env_name="$(basename "${env_file}")"

  if [[ "${env_dir}/${env_name}" != "${compose_dir}/.env" ]]; then
    fail "the validated env must be the fixed .env next to the Compose file"
    return
  fi

  (
    cd "${compose_dir}"
    env \
      -u APP_ENV \
      -u TELEGRAM_TRAFFIC_POLICY \
      -u TELEGRAM_ALLOWED_USER_IDS \
      -u TELEGRAM_ALLOWED_CHAT_IDS \
      -u VENUE_STAFF_INVITE_SECRET_PEPPER \
      docker compose --env-file "${env_name}" -f "${compose_name}" config --format json 2>/dev/null
  ) | awk '
    BEGIN {
      wanted["APP_ENV"]=1
      wanted["TELEGRAM_TRAFFIC_POLICY"]=1
      wanted["TELEGRAM_ALLOWED_USER_IDS"]=1
      wanted["TELEGRAM_ALLOWED_CHAT_IDS"]=1
      wanted["VENUE_STAFF_INVITE_SECRET_PEPPER"]=1
    }
    /^    "backend": \{$/ { in_backend=1; next }
    in_backend && /^      "environment": \{$/ { in_environment=1; next }
    in_backend && in_environment && /^      \},?[[:space:]]*$/ { in_environment=0; in_backend=0; next }
    in_backend && in_environment {
      for (key in wanted) {
        prefix="        \"" key "\": "
        if (index($0, prefix) == 1) {
          value=substr($0, length(prefix) + 1)
          sub(/,[[:space:]]*$/, "", value)
          print key "\t" value
          found[key]++
        }
      }
    }
    END {
      for (key in found) {
        if (found[key] != 1) exit 2
      }
    }
  '
}

effective_token() {
  local effective="$1"
  local key="$2"
  awk -F '\t' -v key="${key}" '$1 == key { sub(/^[^\t]*\t/, ""); print; found++ } END { exit !(found == 1) }' <<< "${effective}"
}

json_string_value() {
  local token="$1"
  local length=${#token}
  if (( length < 2 )) || [[ "${token:0:1}" != '"' || "${token: -1}" != '"' ]]; then
    fail "effective Compose admission value is not a string"
    return
  fi
  token="${token:1:length-2}"
  printf '%b' "${token}"
}

validate_admission() {
  local profile="$1"
  local env_file="$2"
  local compose_file="$3"
  local raw_app_env
  local raw_policy
  local raw_users=""
  local raw_chats=""
  local raw_pepper
  local effective
  local effective_app_env
  local effective_policy
  local effective_users=""
  local effective_chats=""
  local effective_pepper
  local token

  case "${profile}" in
    "${PUBLIC_PILOT_PROFILE}" | "${ISOLATED_ALLOWLIST_PROFILE}") ;;
    *)
      fail "profile must be public-pilot or isolated-allowlist"
      return
      ;;
  esac
  [[ -f "${env_file}" ]] || { fail "staging env file is missing"; return; }
  [[ -f "${compose_file}" ]] || { fail "Compose file is missing"; return; }

  require_exactly_one_key "${env_file}" APP_ENV || return
  require_exactly_one_key "${env_file}" TELEGRAM_TRAFFIC_POLICY || return
  require_at_most_one_key "${env_file}" TELEGRAM_ALLOWED_USER_IDS || return
  require_at_most_one_key "${env_file}" TELEGRAM_ALLOWED_CHAT_IDS || return

  raw_app_env="$(strip_optional_quotes "$(dotenv_value "${env_file}" APP_ENV)")"
  raw_app_env="$(trim_whitespace "${raw_app_env}")"
  if [[ "$(printf '%s' "${raw_app_env}" | tr '[:upper:]' '[:lower:]')" != "staging" ]]; then
    fail "APP_ENV must be staging"
    return
  fi

  raw_policy="$(strip_optional_quotes "$(dotenv_value "${env_file}" TELEGRAM_TRAFFIC_POLICY)")"
  raw_policy="$(trim_whitespace "${raw_policy}")"
  raw_policy="$(printf '%s' "${raw_policy}" | tr '[:lower:]' '[:upper:]')"
  if (( $(dotenv_key_count "${env_file}" TELEGRAM_ALLOWED_USER_IDS) == 1 )); then
    raw_users="$(strip_optional_quotes "$(dotenv_value "${env_file}" TELEGRAM_ALLOWED_USER_IDS)")"
    raw_users="$(trim_whitespace "${raw_users}")"
  fi
  if (( $(dotenv_key_count "${env_file}" TELEGRAM_ALLOWED_CHAT_IDS) == 1 )); then
    raw_chats="$(strip_optional_quotes "$(dotenv_value "${env_file}" TELEGRAM_ALLOWED_CHAT_IDS)")"
    raw_chats="$(trim_whitespace "${raw_chats}")"
  fi

  case "${profile}" in
    "${PUBLIC_PILOT_PROFILE}")
      if [[ "${raw_policy}" != "PRODUCT" ]]; then
        fail "the public-pilot profile requires TELEGRAM_TRAFFIC_POLICY=PRODUCT"
        return
      fi
      if [[ -n "${raw_users}" || -n "${raw_chats}" ]]; then
        fail "the public-pilot PRODUCT profile requires empty static Telegram lists"
        return
      fi
      require_exactly_one_key "${env_file}" VENUE_STAFF_INVITE_SECRET_PEPPER || return
      require_literal_env_value "${env_file}" VENUE_STAFF_INVITE_SECRET_PEPPER || return
      raw_pepper="$(strip_optional_quotes "$(dotenv_value "${env_file}" VENUE_STAFF_INVITE_SECRET_PEPPER)")"
      validate_invite_pepper "${raw_pepper}" || return
      ;;
    "${ISOLATED_ALLOWLIST_PROFILE}")
      if [[ "${raw_policy}" != "ALLOWLIST" ]]; then
        fail "the isolated-allowlist profile requires TELEGRAM_TRAFFIC_POLICY=ALLOWLIST"
        return
      fi
      require_exactly_one_key "${env_file}" TELEGRAM_ALLOWED_USER_IDS || return
      require_exactly_one_key "${env_file}" TELEGRAM_ALLOWED_CHAT_IDS || return
      validate_allowlist_values "${raw_users}" "${raw_chats}" || return
      ;;
  esac

  require_env_file_only_admission "${compose_file}" || return
  if ! effective="$(effective_admission_json "${compose_file}" "${env_file}")"; then
    fail "Docker Compose could not render the effective staging admission config"
    return
  fi

  if ! token="$(effective_token "${effective}" APP_ENV)"; then
    fail "effective Compose config is missing APP_ENV"
    return
  fi
  if ! effective_app_env="$(json_string_value "${token}")"; then
    return
  fi
  effective_app_env="$(trim_whitespace "${effective_app_env}")"
  if [[ "$(printf '%s' "${effective_app_env}" | tr '[:upper:]' '[:lower:]')" != "staging" ]]; then
    fail "effective Compose APP_ENV must be staging"
    return
  fi

  if ! token="$(effective_token "${effective}" TELEGRAM_TRAFFIC_POLICY)"; then
    fail "effective Compose config is missing TELEGRAM_TRAFFIC_POLICY"
    return
  fi
  if ! effective_policy="$(json_string_value "${token}")"; then
    return
  fi
  effective_policy="$(trim_whitespace "${effective_policy}")"
  effective_policy="$(printf '%s' "${effective_policy}" | tr '[:lower:]' '[:upper:]')"

  if token="$(effective_token "${effective}" TELEGRAM_ALLOWED_USER_IDS 2>/dev/null)"; then
    if ! effective_users="$(json_string_value "${token}")"; then
      return
    fi
    effective_users="$(trim_whitespace "${effective_users}")"
  fi
  if token="$(effective_token "${effective}" TELEGRAM_ALLOWED_CHAT_IDS 2>/dev/null)"; then
    if ! effective_chats="$(json_string_value "${token}")"; then
      return
    fi
    effective_chats="$(trim_whitespace "${effective_chats}")"
  fi

  case "${profile}" in
    "${PUBLIC_PILOT_PROFILE}")
      if [[ "${effective_policy}" != "PRODUCT" ]]; then
        fail "effective Compose policy must be PRODUCT for public-pilot deploys"
        return
      fi
      if [[ -n "${effective_users}" || -n "${effective_chats}" ]]; then
        fail "effective Compose static Telegram lists must be empty for public-pilot deploys"
        return
      fi
      if ! token="$(effective_token "${effective}" VENUE_STAFF_INVITE_SECRET_PEPPER)"; then
        fail "effective Compose config is missing VENUE_STAFF_INVITE_SECRET_PEPPER"
        return
      fi
      if ! effective_pepper="$(json_string_value "${token}")"; then
        return
      fi
      validate_invite_pepper "${effective_pepper}" || return
      ;;
    "${ISOLATED_ALLOWLIST_PROFILE}")
      if [[ "${effective_policy}" != "ALLOWLIST" ]]; then
        fail "effective Compose policy must be ALLOWLIST for isolated smoke"
        return
      fi
      validate_allowlist_values "${effective_users}" "${effective_chats}" || return
      ;;
  esac

  echo "Staging admission guard: PASS (${profile})"
}

write_self_test_env() {
  local target="$1"
  local policy="$2"
  local users="$3"
  local chats="$4"
  local pepper="$5"
  {
    printf '%s\n' \
      'APP_ENV=staging' \
      'POSTGRES_DB=fixture' \
      'POSTGRES_USER=fixture' \
      'POSTGRES_PASSWORD=fixture-password' \
      "TELEGRAM_TRAFFIC_POLICY=${policy}" \
      "TELEGRAM_ALLOWED_USER_IDS=${users}" \
      "TELEGRAM_ALLOWED_CHAT_IDS=${chats}"
    if [[ "${pepper}" != "__ABSENT__" ]]; then
      printf '%s\n' "VENUE_STAFF_INVITE_SECRET_PEPPER=${pepper}"
    fi
  } > "${target}"
}

expect_self_test_pass() {
  local label="$1"
  local profile="$2"
  local env_file="$3"
  local compose_file="$4"
  if ! validate_admission "${profile}" "${env_file}" "${compose_file}" >/dev/null 2>&1; then
    fail "self-test expected PASS: ${label}"
    return
  fi
}

expect_self_test_fail() {
  local label="$1"
  local profile="$2"
  local env_file="$3"
  local compose_file="$4"
  if validate_admission "${profile}" "${env_file}" "${compose_file}" >/dev/null 2>&1; then
    fail "self-test expected rejection: ${label}"
    return
  fi
}

self_test() {
  local source_compose="$1"
  local fixture_dir
  local compose_file
  local env_file
  local placeholder
  local -a placeholders=(
    change-me please-change-me please-set-when-enabled set-when-enabled
    replace-me replace-with-secret dev-invite-pepper local-dev-pepper
    example example-secret example-pepper example-invite-pepper
    placeholder placeholder-secret placeholder-pepper placeholder-invite-pepper
    your-secret your-secret-here your-pepper your-pepper-here
    invite-secret-placeholder example-value
  )

  command -v docker >/dev/null 2>&1 || { fail "docker is required for self-test"; return; }
  docker compose version >/dev/null 2>&1 || { fail "Docker Compose is required for self-test"; return; }
  [[ -f "${source_compose}" ]] || { fail "Compose file is missing for self-test"; return; }

  fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/staging-admission-guard.XXXXXX")"
  SELF_TEST_FIXTURE_DIR="${fixture_dir}"
  trap cleanup_self_test EXIT
  compose_file="${fixture_dir}/docker-compose.yml"
  env_file="${fixture_dir}/.env"
  cp "${source_compose}" "${compose_file}"

  write_self_test_env "${env_file}" PRODUCT '' '' fixture-strong-invite-secret-2026
  expect_self_test_pass "public pilot PRODUCT" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"
  if ! APP_ENV=production \
    TELEGRAM_TRAFFIC_POLICY=UNRESTRICTED \
    TELEGRAM_ALLOWED_USER_IDS=999 \
    TELEGRAM_ALLOWED_CHAT_IDS=999 \
    VENUE_STAFF_INVITE_SECRET_PEPPER=change-me \
    validate_admission "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}" >/dev/null 2>&1; then
    fail "self-test expected scrubbed shell overrides to preserve reviewed PRODUCT config"
    return
  fi

  write_self_test_env "${env_file}" ALLOWLIST 101 101 __ABSENT__
  expect_self_test_fail "ALLOWLIST is not the default public-pilot path" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"
  expect_self_test_pass "explicit isolated ALLOWLIST" "${ISOLATED_ALLOWLIST_PROFILE}" "${env_file}" "${compose_file}"

  write_self_test_env "${env_file}" UNRESTRICTED '' '' fixture-strong-invite-secret-2026
  expect_self_test_fail "UNRESTRICTED" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"
  expect_self_test_fail "UNRESTRICTED isolated smoke" "${ISOLATED_ALLOWLIST_PROFILE}" "${env_file}" "${compose_file}"

  write_self_test_env "${env_file}" PRODUCT 101 101 fixture-strong-invite-secret-2026
  expect_self_test_fail "PRODUCT with static IDs" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"

  write_self_test_env "${env_file}" PRODUCT '' '' __ABSENT__
  expect_self_test_fail "PRODUCT without invite pepper" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"

  write_self_test_env "${env_file}" PRODUCT '' '' '   '
  expect_self_test_fail "PRODUCT with blank invite pepper" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"

  write_self_test_env "${env_file}" PRODUCT '' '' Please_Change.Me
  expect_self_test_fail "PRODUCT with normalized placeholder pepper" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"

  write_self_test_env "${env_file}" PRODUCT '' '' '${REMOTE_SHELL_PEPPER}'
  expect_self_test_fail "PRODUCT with interpolated invite pepper" "${PUBLIC_PILOT_PROFILE}" "${env_file}" "${compose_file}"

  for placeholder in "${placeholders[@]}"; do
    if ! is_known_placeholder "$(canonicalize_placeholder "${placeholder}")"; then
      fail "known-placeholder self-test coverage is incomplete"
      return
    fi
  done

  write_self_test_env "${env_file}" ALLOWLIST 101,101 101 __ABSENT__
  expect_self_test_fail "ALLOWLIST duplicate users" "${ISOLATED_ALLOWLIST_PROFILE}" "${env_file}" "${compose_file}"
  write_self_test_env "${env_file}" ALLOWLIST 101 202 __ABSENT__
  expect_self_test_fail "ALLOWLIST without matching private chat" "${ISOLATED_ALLOWLIST_PROFILE}" "${env_file}" "${compose_file}"

  echo "Staging admission guard self-test: PASS"
}

main() {
  local profile="${PUBLIC_PILOT_PROFILE}"
  local env_file=".env"
  local compose_file="docker-compose.yml"

  if [[ "${1:-}" == "--self-test" ]]; then
    shift
    self_test "${1:-docker-compose.yml}"
    return
  fi

  while (( $# > 0 )); do
    case "$1" in
      --profile)
        [[ $# -ge 2 ]] || { usage >&2; return 2; }
        profile="$2"
        shift 2
        ;;
      --env-file)
        [[ $# -ge 2 ]] || { usage >&2; return 2; }
        env_file="$2"
        shift 2
        ;;
      --compose-file)
        [[ $# -ge 2 ]] || { usage >&2; return 2; }
        compose_file="$2"
        shift 2
        ;;
      -h | --help)
        usage
        return
        ;;
      *)
        usage >&2
        return 2
        ;;
    esac
  done

  validate_admission "${profile}" "${env_file}" "${compose_file}"
}

main "$@"
