#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
umask 077

readonly RELEASE_SHA='a648e75179975c97daa4b3dae03070e6476d8a9a'
readonly RELEASE_TREE='14f2400434c8546f5aba7c4bcc94fd20622625d1'
readonly V125_SOURCE='f577934691a1a7a79ba327c54e2055425142b7be'
readonly V125_TAG='hookah_bot_ant-backend:f577934691a1a7a79ba327c54e2055425142b7be'
readonly V125_ID='sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8'
readonly CADDY_SHA_PLACEHOLDER='TO_BE_SET_PER_CASE'

source_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/ht12r-prerequisite-fixtures.XXXXXX")"
chmod 0700 "${fixture_root}"
passes=0
case_counter=0

cleanup() {
  trap - EXIT INT TERM
  if [[ "${V126_PREREQ_KEEP_FIXTURES:-0}" == 1 ]]; then
    printf 'fixtures retained: %s\n' "${fixture_root}" >&2
    return 0
  fi
  [[ -d "${fixture_root}" && ! -L "${fixture_root}" && "$(basename "${fixture_root}")" == ht12r-prerequisite-fixtures.* ]] || return 0
  chmod -R u+w "${fixture_root}"
  rm -rf -- "${fixture_root}"
}
trap cleanup EXIT INT TERM

fail() {
  printf 'HT-12R fixture failure: %s\n' "$1" >&2
  exit 1
}

unexpected_error() {
  local command_status="$1" source_line="$2"
  printf 'HT-12R fixture unexpected command failure at line %s (status %s)\n' "${source_line}" "${command_status}" >&2
}
trap 'unexpected_error "$?" "${LINENO}"' ERR

pass() {
  passes=$((passes + 1))
}

hash_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

mode_file() {
  if [[ "$(uname -s)" == Darwin ]]; then stat -f '%Lp' "$1"; else stat -c '%a' "$1"; fi
}

make_release_template() {
  local root="${fixture_root}/template"
  mkdir -p "${root}/scripts" "${root}/backend/app/src/main/resources/db/migration/postgresql" "${root}/backend/app/src/main/resources/db/migration/h2"
  cp "${source_root}/scripts/v126-staging-prerequisite-sync.sh" "${root}/scripts/"
  cp "${source_root}/scripts/v126-staging-prerequisite-sync-helper.py" "${root}/scripts/"
  cp "${source_root}/scripts/v126-staging-prerequisite-sync-checks.tsv" "${root}/scripts/"
  cp "${source_root}/scripts/check-staging-maintenance-config.sh" "${root}/scripts/"
  cp "${source_root}/scripts/validate-staging-admission.sh" "${root}/scripts/"
  cp "${source_root}/docker-compose.yml" "${root}/"
  cp "${source_root}/backend/app/src/main/resources/db/migration/postgresql/V126__support_thread_read_message_cursor.sql" "${root}/backend/app/src/main/resources/db/migration/postgresql/"
  cp "${source_root}/backend/app/src/main/resources/db/migration/h2/V127__support_thread_read_message_cursor.sql" "${root}/backend/app/src/main/resources/db/migration/h2/"
  chmod 0755 "${root}/scripts/v126-staging-prerequisite-sync.sh" "${root}/scripts/v126-staging-prerequisite-sync-helper.py" \
    "${root}/scripts/check-staging-maintenance-config.sh" "${root}/scripts/validate-staging-admission.sh"
  chmod 0644 "${root}/scripts/v126-staging-prerequisite-sync-checks.tsv" "${root}/docker-compose.yml" \
    "${root}/backend/app/src/main/resources/db/migration/postgresql/V126__support_thread_read_message_cursor.sql" \
    "${root}/backend/app/src/main/resources/db/migration/h2/V127__support_thread_read_message_cursor.sql"
}

write_mock_git() {
  local target="$1"
  cat > "${target}" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' git >> "${V126_FIXTURE_MOCK_LOG}"
all="$*"
case "${all}" in
  *' fetch --no-tags origin main') exit 0 ;;
  *' status --porcelain=v1 --untracked-files=normal') exit 0 ;;
  *' rev-parse --verify HEAD') printf '%s\n' a648e75179975c97daa4b3dae03070e6476d8a9a ;;
  *' rev-parse --verify origin/main') printf '%s\n' a648e75179975c97daa4b3dae03070e6476d8a9a ;;
  *'rev-parse --verify '*'^{tree}') printf '%s\n' 14f2400434c8546f5aba7c4bcc94fd20622625d1 ;;
  *' cat-file blob '*)
    object="${!#}"
    path="${object#*:}"
    exec /bin/cat "${V126_FIXTURE_RELEASE_OBJECTS}/${path}"
    ;;
  *':backend/app/src/main/resources/db/migration/postgresql/V126__support_thread_read_message_cursor.sql') printf '%s\n' 6f39f7d33b1976d0f5eb7a70051bfc5351d12e56 ;;
  *':backend/app/src/main/resources/db/migration/h2/V127__support_thread_read_message_cursor.sql') printf '%s\n' 6f39f7d33b1976d0f5eb7a70051bfc5351d12e56 ;;
  *':backend/app/src/main/resources/db/migration/postgresql') printf '%s\n' bb2778e26e03e03211eab9f149777313f4a6f24b ;;
  *':backend/app/src/main/resources/db/migration/h2') printf '%s\n' 07b5ba6ccf25e79c9cc419b9095bb664f2cfae18 ;;
  *':backend/app/src/main/resources/db/migration') printf '%s\n' 765956602de896b4498a956753272a6bc2d2971e ;;
  *' rev-parse --verify '*) printf '%s\n' 1111111111111111111111111111111111111111 ;;
  *) printf 'unexpected mock git call: %s\n' "${all}" >&2; exit 90 ;;
esac
MOCK
  chmod 0500 "${target}"
}

write_mock_gh() {
  local target="$1"
  cat > "${target}" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' gh >> "${V126_FIXTURE_MOCK_LOG}"
printf '%s\n' '{"attempt":1,"conclusion":"success","databaseId":33658844231,"event":"push","headBranch":"main","headSha":"a648e75179975c97daa4b3dae03070e6476d8a9a","jobs":[{"name":"j1","status":"completed","conclusion":"success"},{"name":"j2","status":"completed","conclusion":"success"},{"name":"j3","status":"completed","conclusion":"success"},{"name":"j4","status":"completed","conclusion":"success"},{"name":"j5","status":"completed","conclusion":"success"},{"name":"j6","status":"completed","conclusion":"success"},{"name":"j7","status":"completed","conclusion":"success"},{"name":"j8","status":"completed","conclusion":"success"},{"name":"j9","status":"completed","conclusion":"success"},{"name":"j10","status":"completed","conclusion":"success"},{"name":"j11","status":"completed","conclusion":"success"},{"name":"j12","status":"completed","conclusion":"success"}],"name":"CI","status":"completed"}'
MOCK
  chmod 0500 "${target}"
}

write_mock_docker() {
  local target="$1"
  cat > "${target}" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' docker >> "${V126_FIXTURE_MOCK_LOG}"
if [[ "${1:-}" == compose ]]; then
  shift
  command_name=''
  for token in "$@"; do
    case "${token}" in config|ps|exec|version) command_name="${token}"; break ;; esac
  done
  case "${command_name}" in
    version) printf '%s\n' 'Docker Compose version fixture'; exit 0 ;;
    config)
      if [[ " $* " == *' --format json '* ]]; then
        value() { awk -F= -v key="$1" '$1==key {sub(/^[^=]*=/,""); print; exit}' .env; }
        cat <<JSON
{
  "services": {
    "backend": {
      "environment": {
        "APP_ENV": "$(value APP_ENV)",
        "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS": "$(value STAGING_MAINTENANCE_ALLOWED_CHAT_IDS)",
        "STAGING_MAINTENANCE_ALLOWED_USER_IDS": "$(value STAGING_MAINTENANCE_ALLOWED_USER_IDS)",
        "STAGING_MAINTENANCE_MODE": "$(value STAGING_MAINTENANCE_MODE)",
        "TELEGRAM_ALLOWED_CHAT_IDS": "$(value TELEGRAM_ALLOWED_CHAT_IDS)",
        "TELEGRAM_ALLOWED_USER_IDS": "$(value TELEGRAM_ALLOWED_USER_IDS)",
        "TELEGRAM_TRAFFIC_POLICY": "$(value TELEGRAM_TRAFFIC_POLICY)",
        "VENUE_STAFF_INVITE_SECRET_PEPPER": "$(value VENUE_STAFF_INVITE_SECRET_PEPPER)"
      }
    }
  }
}
JSON
      fi
      exit 0
      ;;
    ps)
      service="${!#}"
      case "${service}" in backend) printf '%s\n' fixture-backend ;; postgres) printf '%s\n' fixture-postgres ;; *) exit 1 ;; esac
      ;;
    exec)
      if [[ " $* " == *' pg_isready '* ]]; then exit 0; fi
      input="$(/bin/cat)"
      if [[ "${input}" == *flyway_schema_history* ]]; then
        printf '%s\n' '125:1:0:0'
      else
        printf '%s\n' '0:0:0:0:9' '1073741824'
        if [[ "${V126_FIXTURE_DB_OUTPUT_ERROR:-0}" == 1 ]]; then exit 98; fi
      fi
      ;;
    *) printf 'unexpected mock docker compose call: %s\n' "$*" >&2; exit 91 ;;
  esac
elif [[ "${1:-}" == inspect ]]; then
  format=''; id=''
  while [[ "$#" -gt 0 ]]; do
    case "$1" in --format) format="$2"; shift 2 ;; *) id="$1"; shift ;; esac
  done
  case "${format}" in
    '{{.Image}}') [[ "${id}" == fixture-backend ]] && printf '%s\n' 'sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8' || printf '%s\n' 'sha256:postgres-fixture' ;;
    '{{.RestartCount}}') printf '%s\n' 0 ;;
    '{{.State.Health.Status}}') printf '%s\n' healthy ;;
    '{{.State.StartedAt}}') [[ "${id}" == fixture-backend ]] && printf '%s\n' '2099-01-01T00:00:00Z' || printf '%s\n' '2099-01-01T00:00:01Z' ;;
    '{{json .Config.Env}}') printf '%s\n' "${V126_FIXTURE_RUNTIME_ENV_JSON:-[\"TELEGRAM_TRAFFIC_POLICY=PRODUCT\",\"TELEGRAM_BOT_ENABLED=true\",\"TELEGRAM_BOT_MODE=long_polling\"]}" ;;
    *) printf 'unexpected mock docker inspect format: %s\n' "${format}" >&2; exit 92 ;;
  esac
elif [[ "${1:-}" == ps ]]; then
  printf '%s\n' 'fixture-backend|v125|backend' 'fixture-postgres|postgres|postgres'
elif [[ "${1:-}" == logs ]]; then
  if [[ "${V126_FIXTURE_DOCKER_LOGS_ERROR:-0}" == 1 ]]; then exit 97; fi
  exit 0
else
  printf 'forbidden mock docker mutation: %s\n' "$*" >&2
  exit 93
fi
MOCK
  chmod 0500 "${target}"
}

write_remote_mocks() {
  local mock="$1"
  mkdir "${mock}"
  write_mock_git "${mock}/git"
  write_mock_gh "${mock}/gh"
  write_mock_docker "${mock}/docker"
  cat > "${mock}/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' curl >> "${V126_FIXTURE_MOCK_LOG}"
if [[ " $* " == *' --config - '* ]]; then /bin/cat >/dev/null; printf '%s\n' '{"ok":true,"result":{"url":"","pending_update_count":0}}'; exit 0; fi
if [[ "$*" == *'/version'* ]]; then
  printf '%s\n' '{"version":"f577934691a1a7a79ba327c54e2055425142b7be"}'
  if [[ "${V126_FIXTURE_CURL_VERSION_ERROR:-0}" == 1 ]]; then exit 18; fi
  exit 0
fi
if [[ " $* " == *' -fsSI '* ]]; then printf '%s\n' 'HTTP/2 200' 'content-type: application/json'; exit 0; fi
printf '%s\n' '{"status":"ok"}'
if [[ "${V126_FIXTURE_CURL_HEALTH_ERROR:-0}" == 1 ]]; then exit 18; fi
MOCK
  cat > "${mock}/caddy" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' caddy >> "${V126_FIXTURE_MOCK_LOG}"
case "${1:-}" in version) printf '%s\n' "${V126_FIXTURE_CADDY_OUTPUT-2.6.2}" ;; validate) exit 0 ;; *) exit 94 ;; esac
MOCK
  cat > "${mock}/systemctl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' systemctl >> "${V126_FIXTURE_MOCK_LOG}"
[[ "$*" == 'is-active caddy' ]] && printf '%s\n' active
MOCK
  cat > "${mock}/ss" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' ss >> "${V126_FIXTURE_MOCK_LOG}"
if [[ "${V126_FIXTURE_SS_ERROR:-0}" == 1 ]]; then exit 97; fi
exit 0
MOCK
  cat > "${mock}/openssl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' openssl >> "${V126_FIXTURE_MOCK_LOG}"
/bin/cat >/dev/null
if [[ " $* " == *' -tls1_3 '* ]]; then
  if [[ "${V126_FIXTURE_TLS13_CLIENT_ERROR:-0}" == 1 ]]; then
    printf '%s\n' 's_client: Unknown option: -tls1_3' >&2
  else
    printf '%s\n' 'SSL routines:ssl3_read_bytes:tlsv1 alert protocol version:SSL alert number 70' >&2
  fi
  exit 1
fi
exit 0
MOCK
  cat > "${mock}/ps" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' ps >> "${V126_FIXTURE_MOCK_LOG}"
exit 0
MOCK
  cat > "${mock}/df" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' df >> "${V126_FIXTURE_MOCK_LOG}"
printf '%s\n' 'Avail' '10737418240'
MOCK
  cat > "${mock}/sha256sum" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
exec shasum -a 256 "$@"
MOCK
  cat > "${mock}/stat" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != -c ]]; then exec /usr/bin/stat "$@"; fi
format="$2"; path="$3"
python3 - "${format}" "${path}" <<'PY'
import grp,os,pwd,stat,sys
fmt,path=sys.argv[1:]
s=os.lstat(path)
values={"%a":f"{stat.S_IMODE(s.st_mode):o}","%u":str(s.st_uid),"%g":str(s.st_gid),"%s":str(s.st_size),"%U":pwd.getpwuid(s.st_uid).pw_name,"%G":grp.getgrgid(s.st_gid).gr_name}
for key in ("%U","%G","%a","%u","%g","%s"): fmt=fmt.replace(key,values[key])
print(fmt)
PY
MOCK
  cat > "${mock}/ssh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" == 3 && "$1" == -- && "$2" == fixture-staging && -n "$3" ]] || {
  printf 'mock SSH serialization mismatch\n' >&2
  exit 98
}
printf '%s\n' ssh-mock >> "${V126_FIXTURE_MOCK_LOG}"
exec /bin/sh -c "$3"
MOCK
  cat > "${mock}/sudo" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
exec "$@"
MOCK
  cat > "${mock}/timeout" <<'MOCK'
#!/usr/bin/env python3
import os
import signal
import subprocess
import sys

arguments = sys.argv[1:]
timeout_signal = signal.SIGTERM
kill_after = 10
while arguments and arguments[0].startswith("--"):
    option = arguments.pop(0)
    if option.startswith("--signal="):
        timeout_signal = getattr(signal, "SIG" + option.split("=", 1)[1])
    elif option.startswith("--kill-after="):
        value = option.split("=", 1)[1]
        if not value.endswith("s") or not value[:-1].isdigit():
            raise SystemExit(97)
        kill_after = int(value[:-1])
    else:
        raise SystemExit(97)
if not arguments:
    raise SystemExit(97)
duration_text = arguments.pop(0)
if not duration_text.endswith("s") or not duration_text[:-1].isdigit():
    raise SystemExit(97)
duration = int(duration_text[:-1])
if not arguments:
    raise SystemExit(97)
if not os.environ.get("V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE"):
    os.execvp(arguments[0], arguments)
child = subprocess.Popen(arguments, start_new_session=True)
interrupted = False

def forward(signum, _frame):
    global interrupted
    interrupted = True
    try:
        os.killpg(child.pid, signum)
    except ProcessLookupError:
        pass

signal.signal(signal.SIGINT, forward)
signal.signal(signal.SIGTERM, forward)
try:
    returncode = child.wait(timeout=duration)
except subprocess.TimeoutExpired:
    interrupted = True
    try:
        os.killpg(child.pid, timeout_signal)
    except ProcessLookupError:
        pass
    try:
        returncode = child.wait(timeout=kill_after)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        returncode = child.wait()
if interrupted:
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
raise SystemExit(returncode if returncode >= 0 else 128 - returncode)
MOCK
  local name
  for name in scp rsync psql pg_dump pg_restore createdb dropdb; do
    cat > "${mock}/${name}" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf 'forbidden external command escaped fixture: %s\n' "$(basename "$0")" >&2
exit 99
MOCK
  done
  chmod 0500 "${mock}"/*
}

make_case() {
  local label="$1"
  local root="${fixture_root}/${label}"
  mkdir "${root}"
  cp -R "${fixture_root}/template" "${root}/release-objects"
  cp -R "${fixture_root}/template" "${root}/working"
  chmod 0755 "${root}/working/scripts/v126-staging-prerequisite-sync.sh" "${root}/working/scripts/v126-staging-prerequisite-sync-helper.py"
  chmod 0644 "${root}/working/scripts/v126-staging-prerequisite-sync-checks.tsv"
  mkdir -p "${root}/remote/staging/scripts" "${root}/remote/backups" "${root}/remote/etc/caddy"
  chmod 0700 "${root}/remote" "${root}/remote/staging" "${root}/remote/staging/scripts" "${root}/remote/backups" "${root}/remote/etc" "${root}/remote/etc/caddy"
  printf '%s\n' 'old compose fixture' > "${root}/remote/staging/docker-compose.yml"
  printf '%s\n' '#!/usr/bin/env bash' 'exit 0' > "${root}/remote/staging/scripts/check-staging-maintenance-config.sh"
  printf '%s\n' \
    'APP_ENV=staging' \
    'POSTGRES_DB=hookah' \
    'POSTGRES_USER=hookah' \
    'POSTGRES_PASSWORD=fixture-db-password' \
    'DB_PASSWORD=fixture-db-password' \
    'TELEGRAM_BOT_TOKEN=123456:fixture-token' \
    'TELEGRAM_TRAFFIC_POLICY=PRODUCT' \
    'TELEGRAM_ALLOWED_USER_IDS=' \
    'TELEGRAM_ALLOWED_CHAT_IDS=' \
    'VENUE_STAFF_INVITE_SECRET_PEPPER=fixture-secure-pepper-value' > "${root}/remote/staging/.env"
  printf '%s\n' 'fixture caddy configuration' > "${root}/remote/etc/caddy/Caddyfile"
  chmod 0644 "${root}/remote/staging/docker-compose.yml" "${root}/remote/etc/caddy/Caddyfile"
  chmod 0755 "${root}/remote/staging/scripts/check-staging-maintenance-config.sh"
  chmod 0600 "${root}/remote/staging/.env"
  write_remote_mocks "${root}/mocks"
  : > "${root}/mock-calls.log"; chmod 0600 "${root}/mock-calls.log"
  printf '%s\n' "${root}"
}

run_case() {
  local root="$1" expected_status="$2"
  shift 2
  case_counter=$((case_counter + 1))
  local run_id local_evidence status caddy_sha pre_compose pre_guard pre_env
  run_id="V126-PRE-GATE-A-SYNC-20990101T$(printf '%06d' "${case_counter}")Z"
  local_evidence="${root}/local-evidence"
  caddy_sha="$(hash_file "${root}/remote/etc/caddy/Caddyfile")"
  pre_compose="$(hash_file "${root}/remote/staging/docker-compose.yml")"
  pre_guard="$(hash_file "${root}/remote/staging/scripts/check-staging-maintenance-config.sh")"
  pre_env="$(hash_file "${root}/remote/staging/.env")"
  set +e
  env \
    PATH="${root}/mocks:${PATH}" \
    V126_FIXTURE_MOCK_LOG="${root}/mock-calls.log" \
    V126_FIXTURE_RELEASE_OBJECTS="${root}/release-objects" \
    V126_PREREQ_FIXTURE_MODE=1 \
    V126_PREREQ_FIXTURE_ROOT="${root}/remote" \
    V126_PREREQ_FIXTURE_MOCK_PATH="${root}/mocks:${PATH}" \
    "$@" \
    bash "${root}/working/scripts/v126-staging-prerequisite-sync.sh" \
      --release-worktree "${root}/working" \
      --release-sha "${RELEASE_SHA}" \
      --release-tree "${RELEASE_TREE}" \
      --main-actions-run-id 33658844231 \
      --remote fixture-staging \
      --run-id "${run_id}" \
      --local-evidence-dir "${local_evidence}" \
      --expected-pre-compose-sha256 "${pre_compose}" \
      --expected-pre-maintenance-sha256 "${pre_guard}" \
      --expected-pre-env-sha256 "${pre_env}" \
      --expected-caddyfile-sha256 "${caddy_sha}" \
      --expected-caddy-version 2.6.2 \
      --v125-source "${V125_SOURCE}" \
      --v125-image-tag "${V125_TAG}" \
      --v125-image-id "${V125_ID}" \
      --authorization AUTHORIZE_V126_STAGING_PREREQUISITE_SYNC > "${root}/stdout" 2> "${root}/stderr"
  status=$?
  set -e
  [[ "${status}" == "${expected_status}" ]] || {
    sed -n '1,160p' "${root}/stderr" >&2
    if [[ -f "${local_evidence}/first-failure.json" ]]; then
      sed -n '1,80p' "${local_evidence}/first-failure.json" >&2
    fi
    local diagnostic
    for diagnostic in "${local_evidence}"/raw/*.stderr; do
      [[ -s "${diagnostic}" ]] || continue
      printf 'diagnostic: %s\n' "$(basename "${diagnostic}")" >&2
      sed -n '1,80p' "${diagnostic}" >&2
    done
    fail "case $(basename "${root}") returned ${status}, expected ${expected_status}"
  }
  printf '%s\n' "${run_id}" > "${root}/run-id"
}

assert_restored() {
  local root="$1" run_root name live captured expected_mode
  run_root="${root}/remote/backups/$(< "${root}/run-id")"
  for name in docker-compose.yml check-staging-maintenance-config.sh .env; do
    case "${name}" in
      docker-compose.yml) live="${root}/remote/staging/docker-compose.yml"; expected_mode=644 ;;
      check-staging-maintenance-config.sh) live="${root}/remote/staging/scripts/check-staging-maintenance-config.sh"; expected_mode=755 ;;
      .env) live="${root}/remote/staging/.env"; expected_mode=600 ;;
    esac
    captured="${run_root}/pre-sync/${name}"
    cmp -s "${live}" "${captured}" || fail "rollback bytes differ for ${name}"
    [[ "$(mode_file "${live}")" == "${expected_mode}" ]] || fail "rollback mode differs for ${name}"
  done
  [[ ! -e "${root}/remote/staging/scripts/validate-staging-admission.sh" && ! -L "${root}/remote/staging/scripts/validate-staging-admission.sh" ]]
}

run_signal_matrix() {
  local root run_id
  root="$(make_case signal-int)"; run_case "${root}" 130 V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE=2:INT V126_PREREQ_FIXTURE_REPEAT_SIGNAL=1; run_id="$(< "${root}/run-id")"; [[ "$(find "${root}/remote/backups/${run_id}" -name rollback.started.json | wc -l | tr -d ' ')" == 1 ]]; grep -q '"exit_status":130' "${root}/local-evidence/first-failure.json"; grep -q '"signal":"SIGINT"' "${root}/local-evidence/first-failure.json"; [[ -f "${root}/local-evidence/checkpoints/L14-SYNC_WRITE_MAINTENANCE_GUARD_COMPLETED.failed.json" ]]; grep -q '"signal":"SIGINT"' "${root}/remote/backups/${run_id}/first-failure.json"; grep -q 'additional signal ignored during bounded rollback' "${root}/stderr"; assert_restored "${root}"
  root="$(make_case signal-term)"; run_case "${root}" 143 V126_PREREQ_FIXTURE_SIGNAL_DURING_WRITE=2:TERM V126_PREREQ_FIXTURE_REPEAT_SIGNAL=1; run_id="$(< "${root}/run-id")"; [[ "$(find "${root}/remote/backups/${run_id}" -name rollback.started.json | wc -l | tr -d ' ')" == 1 ]]; grep -q '"exit_status":143' "${root}/local-evidence/first-failure.json"; grep -q '"signal":"SIGTERM"' "${root}/local-evidence/first-failure.json"; [[ -f "${root}/local-evidence/checkpoints/L14-SYNC_WRITE_MAINTENANCE_GUARD_COMPLETED.failed.json" ]]; grep -q '"signal":"SIGTERM"' "${root}/remote/backups/${run_id}/first-failure.json"; grep -q 'additional signal ignored during bounded rollback' "${root}/stderr"; assert_restored "${root}"
}

run_negative_command_matrix() {
  local command_name root ordinal_batch ordinal run_id name next next_name pid
  local pids
  for command_name in curl-health curl-version db-output docker-logs tls13-client ss; do
    root="$(make_case "prewrite-${command_name}-error")"
    case "${command_name}" in
      curl-health) run_case "${root}" 1 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_CURL_HEALTH_ERROR=1 ;;
      curl-version) run_case "${root}" 1 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_CURL_VERSION_ERROR=1 ;;
      db-output) run_case "${root}" 1 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_DB_OUTPUT_ERROR=1 ;;
      docker-logs) run_case "${root}" 1 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_DOCKER_LOGS_ERROR=1 ;;
      tls13-client) run_case "${root}" 1 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_TLS13_CLIENT_ERROR=1 ;;
      ss) run_case "${root}" 1 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_SS_ERROR=1 ;;
    esac
    [[ ! -e "${root}/remote/staging/scripts/validate-staging-admission.sh" ]]
    [[ "$(find "${root}/remote/backups" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' ')" == 0 ]]
  done
  for ordinal_batch in '17 23 24' '26 33 35'; do
    pids=()
    for ordinal in ${ordinal_batch}; do
      (
        root="$(make_case "natural-command-error-check-${ordinal}")"
        run_case "${root}" 1 V126_PREREQ_FIXTURE_FAIL_COMMAND_CHECK="${ordinal}"
        run_id="$(< "${root}/run-id")"
        name="$(awk -F '\t' -v n="${ordinal}" '$1==n {print $2}' "${source_root}/scripts/v126-staging-prerequisite-sync-checks.tsv")"
        [[ -f "${root}/remote/backups/${run_id}/checkpoints/phases/$(printf 'C%02d' "${ordinal}")-${name}.failed.json" ]]
        next=$((ordinal + 1))
        next_name="$(awk -F '\t' -v n="${next}" '$1==n {print $2}' "${source_root}/scripts/v126-staging-prerequisite-sync-checks.tsv")"
        [[ ! -e "${root}/remote/backups/${run_id}/checkpoints/phases/$(printf 'C%02d' "${next}")-${next_name}.started.json" ]]
        assert_restored "${root}"
      ) &
      pids+=("$!")
    done
    for pid in "${pids[@]}"; do
      wait "${pid}" || fail "natural command-error fixture batch ${ordinal_batch} failed"
    done
  done
}

make_release_template

if [[ "${V126_PREREQ_FIXTURE_ONLY_SIGNAL:-0}" == 1 ]]; then
  run_signal_matrix
  printf '%s\n' 'HT12R_SIGNAL_FIXTURES=PASS'
  exit 0
fi

if [[ "${V126_PREREQ_FIXTURE_ONLY_NEGATIVE_COMMANDS:-0}" == 1 ]]; then
  run_negative_command_matrix
  printf '%s\n' 'HT12R_NEGATIVE_COMMAND_FIXTURES=PASS'
  exit 0
fi

# Static syntax, helper syntax, exact map closure, and forbidden runtime surface.
bash -n "${source_root}/scripts/v126-staging-prerequisite-sync.sh"
bash -n "${source_root}/scripts/test-v126-staging-prerequisite-sync.sh"
PYTHONPYCACHEPREFIX="${fixture_root}/pycache" python3 -m py_compile "${source_root}/scripts/v126-staging-prerequisite-sync-helper.py"
awk -F '\t' 'BEGIN{ok=1} {if($1!=NR || NF!=3 || seen[$1]++ || names[$2]++ || $2 !~ /^[A-Z][A-Z0-9_]*$/ || $3 !~ /^[A-Za-z0-9_.\/:;=,-]+$/) ok=0} END{exit !(NR==40 && ok)}' "${source_root}/scripts/v126-staging-prerequisite-sync-checks.tsv"
[[ "$(grep -Ec '^check_[0-9]+\(\)' "${source_root}/scripts/v126-staging-prerequisite-sync.sh")" == 40 ]]
if grep -En '^[[:space:]]*(sudo[[:space:]]+)?docker[[:space:]]+(build|load|save|create|start|stop|restart|run)|^[[:space:]]*(sudo[[:space:]]+)?docker[[:space:]]+compose[[:space:]]+(up|down|create|start|stop|restart|run)|^[[:space:]]*(sudo[[:space:]]+)?(pg_dump|pg_restore)([[:space:]]|$)|(^|[;&|()])[[:space:]]*eval([[:space:]]|$)|api\.telegram\.org[^[:space:]]*getUpdates' \
  "${source_root}/scripts/v126-staging-prerequisite-sync.sh" "${source_root}/scripts/v126-staging-prerequisite-sync-helper.py"; then
  fail 'forbidden runtime mutation or dynamic evaluation surface exists'
fi
pass

# Caddy semantic-version fixtures, including exact-prefix and ambiguity rejection.
helper="${source_root}/scripts/v126-staging-prerequisite-sync-helper.py"
for observed in '2.6.2' 'v2.6.2' '2.6.2 h1:fixture=' 'v2.6.2 h1:fixture='; do
  python3 "${helper}" caddy-version 2.6.2 "${observed}" >/dev/null || fail "Caddy PASS fixture rejected: ${observed}"
done
for observed in '2.6.3' '2.6.20' '2.6.2foo' '' 'V2.6.2' '2.6.2 2.6.2' $'2.6.2\nextra' '2.6.2 bad,token'; do
  if python3 "${helper}" caddy-version 2.6.2 "${observed}" >/dev/null 2>&1; then fail "Caddy FAIL fixture accepted: ${observed}"; fi
done
pass

# The independent inline prewrite parser sees the same semantic Caddy matrix through serialized mock SSH.
for observed in '2.6.2' 'v2.6.2' '2.6.2 h1:fixture=' 'v2.6.2 h1:fixture='; do
  root="$(make_case "caddy-inline-pass-${case_counter}")"
  run_case "${root}" 0 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_CADDY_OUTPUT="${observed}"
done
for observed in '2.6.3' '2.6.20' '2.6.2foo' '' 'V2.6.2' '2.6.2 2.6.2' $'2.6.2\nextra' '2.6.2 bad,token'; do
  root="$(make_case "caddy-inline-fail-${case_counter}")"
  run_case "${root}" 1 V126_PREREQ_FIXTURE_STOP_AFTER_PREWRITE=1 V126_FIXTURE_CADDY_OUTPUT="${observed}"
done
pass

# PRODUCT absent/empty/mixed semantics and nonempty/malformed rejection.
runtime_base='["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling"]'
printf '%s' "${runtime_base}" | python3 "${helper}" runtime-env >/dev/null
printf '%s' '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_ALLOWED_USER_IDS=","TELEGRAM_ALLOWED_CHAT_IDS="]' | python3 "${helper}" runtime-env >/dev/null
printf '%s' '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_ALLOWED_CHAT_IDS="]' | python3 "${helper}" runtime-env >/dev/null
printf '%s' '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_PRODUCT_ALLOWED_USER_IDS=","TELEGRAM_PRODUCT_ALLOWED_CHAT_IDS="]' | python3 "${helper}" runtime-env >/dev/null
for payload in \
  '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_ALLOWED_USER_IDS=1"]' \
  '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_ALLOWED_CHAT_IDS=-1"]' \
  '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_PRODUCT_ALLOWED_USER_IDS=1"]' \
  '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_PRODUCT_ALLOWED_CHAT_IDS=-1"]' \
  '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_ALLOWED_USER_IDS= "]' \
  '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_ALLOWED_USER_IDS=not,a,list"]' \
  '["TELEGRAM_TRAFFIC_POLICY=PRODUCT","TELEGRAM_BOT_ENABLED=true","TELEGRAM_BOT_MODE=long_polling","TELEGRAM_ALLOWED_CHAT_IDS=1,,2"]'; do
  if printf '%s' "${payload}" | python3 "${helper}" runtime-env >/dev/null 2>&1; then fail 'nonempty or malformed PRODUCT list passed'; fi
done
pass

# Complete success path reaches 40/40 and stops before Gate A.
root="$(make_case success)"
original_env="$(hash_file "${root}/remote/staging/.env")"
run_case "${root}" 0
run_id="$(< "${root}/run-id")"
passed_file_paths="$(find "${root}/remote/backups/${run_id}/checkpoints/phases" -type f -name '*.passed.json' -print)" ||
  fail 'success fixture PASSED-file inventory failed'
passed_file_count="$(printf '%s\n' "${passed_file_paths}" | awk 'NF {count++} END {print count+0}')"
[[ "${passed_file_count}" == 48 ]] || fail "success fixture has ${passed_file_count}/48 PASSED files"
while IFS= read -r passed_file; do
  [[ -n "${passed_file}" ]] || continue
  grep -q '"state":"PASSED"' "${passed_file}" || fail "success fixture state missing from $(basename "${passed_file}")"
done <<< "${passed_file_paths}"
[[ -f "${root}/remote/backups/${run_id}/checkpoints/phases/R08-CANONICAL_SUCCESS.passed.json" ]] || fail 'canonical success checkpoint missing'
[[ ! -e "${root}/remote/backups/${run_id}/rollback.started.json" ]] || fail 'success fixture unexpectedly rolled back'
grep -q 'GATE_A=NOT_STARTED' "${root}/stdout" || fail 'success fixture did not stop before Gate A'
[[ "$(awk -F= '$1~/^STAGING_MAINTENANCE_/ {print}' "${root}/remote/staging/.env" | tr '\n' ':')" == 'STAGING_MAINTENANCE_MODE=OFF:STAGING_MAINTENANCE_ALLOWED_USER_IDS=:STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=:' ]] ||
  fail 'success fixture maintenance environment suffix mismatch'
[[ "${original_env}" != "$(hash_file "${root}/remote/staging/.env")" ]] || fail 'success fixture environment hash did not change'
pre_env_path="${root}/remote/backups/${run_id}/pre-sync/.env"
live_env_path="${root}/remote/staging/.env"
pre_env_size="$(wc -c < "${pre_env_path}" | tr -d ' ')"
cmp -s <(dd if="${live_env_path}" bs=1 count="${pre_env_size}" 2>/dev/null) "${pre_env_path}" || fail 'independent environment prefix oracle failed'
cmp -s <(dd if="${live_env_path}" bs=1 skip="${pre_env_size}" 2>/dev/null) \
  <(printf '%s\n' 'STAGING_MAINTENANCE_MODE=OFF' 'STAGING_MAINTENANCE_ALLOWED_USER_IDS=' 'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=') ||
  fail 'independent environment suffix oracle failed'
grep -q '^ssh-mock$' "${root}/mock-calls.log" || fail 'serialized mock SSH path was not exercised'
if grep -En 'Gate A|AUTHORIZE_V126_CUTOVER_GATE_A|docker (start|stop|restart|load)|pg_dump|pg_restore' "${root}/mock-calls.log"; then fail 'success fixture crossed the prerequisite boundary'; fi
pass

# Failure before any write creates no rollback.
root="$(make_case prewrite-failure)"
run_case "${root}" 71 V126_PREREQ_FIXTURE_FAIL_LOCAL_PHASE=PREWRITE_BASELINE_PASSED V126_PREREQ_FIXTURE_FAIL_STATUS=71
[[ ! -e "${root}/remote/backups"/*/rollback.started.json ]]
pass

# Failure during and after every one of the four writes rolls back exactly once.
for position in during after; do
  for ordinal in 1 2 3 4; do
    root="$(make_case "${position}-write-${ordinal}")"
    run_case "${root}" 73 V126_PREREQ_FIXTURE_FAIL_WRITE_POSITION="${position}-${ordinal}" V126_PREREQ_FIXTURE_FAIL_STATUS=73
    run_id="$(< "${root}/run-id")"
    [[ -f "${root}/remote/backups/${run_id}/rollback.started.json" && -f "${root}/remote/backups/${run_id}/rollback.result.json" ]]
    [[ "$(find "${root}/remote/backups/${run_id}" -name 'rollback.started.json' | wc -l | tr -d ' ')" == 1 ]]
    assert_restored "${root}"
  done
done
pass

# Every named post-sync check stops at the first failure and preserves its status. Cases are
# isolated and run in bounded batches; ordering and predecessor validation within each case remain serial.
for batch_start in $(seq 1 4 40); do
  pids=()
  for ordinal in $(seq "${batch_start}" "$((batch_start + 3))"); do
    (
      root="$(make_case "check-${ordinal}")"
      code=$((80 + ordinal))
      run_case "${root}" "${code}" V126_PREREQ_FIXTURE_FAIL_CHECK="${ordinal}" V126_PREREQ_FIXTURE_FAIL_STATUS="${code}"
      run_id="$(< "${root}/run-id")"
      name="$(awk -F '\t' -v n="${ordinal}" '$1==n {print $2}' "${source_root}/scripts/v126-staging-prerequisite-sync-checks.tsv")"
      [[ -f "${root}/remote/backups/${run_id}/checkpoints/phases/$(printf 'C%02d' "${ordinal}")-${name}.failed.json" ]]
      next=$((ordinal + 1))
      if ((next <= 40)); then
        next_name="$(awk -F '\t' -v n="${next}" '$1==n {print $2}' "${source_root}/scripts/v126-staging-prerequisite-sync-checks.tsv")"
        [[ ! -e "${root}/remote/backups/${run_id}/checkpoints/phases/$(printf 'C%02d' "${next}")-${next_name}.started.json" ]]
      fi
      assert_restored "${root}"
    ) &
    pids+=("$!")
  done
  for pid in "${pids[@]}"; do
    wait "${pid}" || fail "post-sync failure fixture batch starting ${batch_start} failed"
  done
done
pass

# Natural command failures in prewrite health/version/DB/TLS, conflict-log and UDP probes fail closed.
run_negative_command_matrix
pass

# Invalid failure or write-marker evidence cannot authorize or falsely pass rollback.
root="$(make_case tampered-first-failure)"
run_case "${root}" 81 V126_PREREQ_FIXTURE_FAIL_CHECK=1 V126_PREREQ_FIXTURE_FAIL_STATUS=81 V126_PREREQ_FIXTURE_TAMPER_FIRST_FAILURE_MODE=1
run_id="$(< "${root}/run-id")"
[[ "$(mode_file "${root}/remote/backups/${run_id}/first-failure.json")" == 600 ]]
[[ ! -e "${root}/remote/backups/${run_id}/rollback.started.json" ]]
[[ ! -e "${root}/remote/backups/${run_id}/rollback.result.json" ]]

root="$(make_case tampered-write-marker)"
run_case "${root}" 1 V126_PREREQ_FIXTURE_TAMPER_WRITE_MARKER=1
run_id="$(< "${root}/run-id")"
[[ "$(mode_file "${root}/remote/backups/${run_id}/state/write-1.completed")" == 600 ]]
[[ -f "${root}/remote/backups/${run_id}/rollback.started.json" ]]
[[ ! -e "${root}/remote/backups/${run_id}/rollback.result.json" ]]
if cmp -s "${root}/remote/staging/docker-compose.yml" "${root}/remote/backups/${run_id}/pre-sync/docker-compose.yml"; then
  fail 'indeterminate write evidence incorrectly authorized rollback success'
fi

# Exercise the real helper unlink plus parent-directory fsync path used by every successful rollback.
unlink_root="${fixture_root}/durable-unlink"; mkdir "${unlink_root}"; chmod 0700 "${unlink_root}"
printf '%s\n' 'durable unlink fixture' > "${unlink_root}/target"; chmod 0755 "${unlink_root}/target"
unlink_sha="$(hash_file "${unlink_root}/target")"
unlink_uid="$(python3 -c 'import os,sys; print(os.lstat(sys.argv[1]).st_uid)' "${unlink_root}/target")"
unlink_gid="$(python3 -c 'import os,sys; print(os.lstat(sys.argv[1]).st_gid)' "${unlink_root}/target")"
python3 "${helper}" durable-unlink "${unlink_root}/target" "${unlink_sha}" 0755 "${unlink_uid}" "${unlink_gid}" >/dev/null
[[ ! -e "${unlink_root}/target" && ! -L "${unlink_root}/target" ]]
pass

# Rollback failure never replaces the original first-failure status.
root="$(make_case rollback-failure)"
run_case "${root}" 83 V126_PREREQ_FIXTURE_FAIL_CHECK=1 V126_PREREQ_FIXTURE_FAIL_STATUS=83 V126_PREREQ_FIXTURE_ROLLBACK_FAIL=1
run_id="$(< "${root}/run-id")"
grep -q '"exit_status":83' "${root}/remote/backups/${run_id}/first-failure.json"
grep -q '"state":"ROLLBACK_FAILED"' "${root}/remote/backups/${run_id}/rollback.result.json"
pass

# Active remote SIGINT/SIGTERM preserve 130/143, quiesce before rollback, and tolerate a repeated trap.
run_signal_matrix
pass

# Zero-byte and partial transfers fail before synchronization writes.
for fault in 'zero:source/helper.py' 'partial:release-inputs/docker-compose.yml'; do
  root="$(make_case "transfer-${fault%%:*}")"
  run_case "${root}" 94 V126_PREREQ_FIXTURE_TRANSFER_FAULT="${fault}"
  [[ ! -e "${root}/remote/staging/scripts/validate-staging-admission.sh" ]]
done
pass

# Changed executing source/helper and incomplete check closure fail closed.
root="$(make_case changed-source)"; printf '\n' >> "${root}/working/scripts/v126-staging-prerequisite-sync.sh"; run_case "${root}" 1
root="$(make_case changed-helper)"; printf '\n' >> "${root}/working/scripts/v126-staging-prerequisite-sync-helper.py"; run_case "${root}" 1
root="$(make_case missing-check)"; sed -n '1,39p' "${root}/release-objects/scripts/v126-staging-prerequisite-sync-checks.tsv" > "${root}/release-objects/scripts/map.tmp"; mv "${root}/release-objects/scripts/map.tmp" "${root}/release-objects/scripts/v126-staging-prerequisite-sync-checks.tsv"; cp "${root}/release-objects/scripts/v126-staging-prerequisite-sync-checks.tsv" "${root}/working/scripts/v126-staging-prerequisite-sync-checks.tsv"; chmod 0644 "${root}/working/scripts/v126-staging-prerequisite-sync-checks.tsv"; run_case "${root}" 1
pass

# Durable checkpoint duplicate, state, exact-binding, capture, mode and symlink cases.
checkpoint_root="${fixture_root}/checkpoint-unit/run"
mkdir -p "${checkpoint_root}/checkpoints/phases" "${checkpoint_root}/raw" "${checkpoint_root}/source"
chmod 0700 "${checkpoint_root}" "${checkpoint_root}/checkpoints" "${checkpoint_root}/checkpoints/phases" "${checkpoint_root}/raw" "${checkpoint_root}/source"
cp "${source_root}/scripts/v126-staging-prerequisite-sync-checks.tsv" "${checkpoint_root}/source/checks.tsv"
chmod 0400 "${checkpoint_root}/source/checks.tsv"
checkpoint_out="${checkpoint_root}/raw/1-NAME.stdout"; checkpoint_err="${checkpoint_root}/raw/1-NAME.stderr"
checkpoint_started="${checkpoint_root}/checkpoints/phases/1-NAME.started.json"; checkpoint_passed="${checkpoint_root}/checkpoints/phases/1-NAME.passed.json"
printf '%s\n' EXPECTED > "${checkpoint_out}"; : > "${checkpoint_err}"; chmod 0600 "${checkpoint_out}" "${checkpoint_err}"
hex64="$(printf 'a%.0s' {1..64})"
if python3 "${helper}" verify-checkpoint "${checkpoint_root}/checkpoints/phases/1-NAME.passed.json" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE >/dev/null 2>&1; then fail 'missing checkpoint passed'; fi
python3 "${helper}" checkpoint-start "${checkpoint_started}" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE
if python3 "${helper}" checkpoint-start "${checkpoint_started}" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE >/dev/null 2>&1; then fail 'duplicate checkpoint passed'; fi
python3 "${helper}" checkpoint-finish "${checkpoint_passed}" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE "${checkpoint_started}" 0 "${checkpoint_out}" "${checkpoint_err}" PASSED
python3 "${helper}" verify-checkpoint "${checkpoint_passed}" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE >/dev/null
if python3 "${helper}" verify-checkpoint "${checkpoint_passed}" OTHER 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE >/dev/null 2>&1; then fail 'stale checkpoint passed'; fi
if python3 "${helper}" verify-checkpoint "${checkpoint_passed}" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" WRONG NONE >/dev/null 2>&1; then fail 'wrong expected result passed'; fi
masquerade_root="${fixture_root}/checkpoint-masquerade/run"; mkdir -p "${masquerade_root}/checkpoints/phases" "${masquerade_root}/raw"; cp "${checkpoint_started}" "${masquerade_root}/checkpoints/phases/1-NAME.passed.json"; chmod 0400 "${masquerade_root}/checkpoints/phases/1-NAME.passed.json"
if python3 "${helper}" verify-checkpoint "${masquerade_root}/checkpoints/phases/1-NAME.passed.json" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE >/dev/null 2>&1; then fail 'STARTED record masqueraded as PASSED'; fi
chmod 0600 "${checkpoint_passed}"
if python3 "${helper}" verify-checkpoint "${checkpoint_passed}" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE >/dev/null 2>&1; then fail 'wrong-mode checkpoint passed'; fi
chmod 0400 "${checkpoint_passed}"
printf '%s\n' TAMPERED > "${checkpoint_out}"; chmod 0600 "${checkpoint_out}"
if python3 "${helper}" verify-checkpoint "${checkpoint_passed}" RUN 1 NAME "${RELEASE_SHA}" "${hex64}" EXPECTED NONE >/dev/null 2>&1; then fail 'tampered capture passed'; fi
ln -s "$(basename "${checkpoint_passed}")" "${checkpoint_root}/checkpoints/phases/link.json"
if python3 "${helper}" verify-record "${checkpoint_root}/checkpoints/phases/link.json" >/dev/null 2>&1; then fail 'symlink checkpoint passed'; fi
first_failure_path="${checkpoint_root}/first-failure.json"
python3 "${helper}" first-failure "${first_failure_path}" RUN L13 SYNC_WRITE_COMPOSE_COMPLETED "${RELEASE_SHA}" "${hex64}" 73 NONE NONE "${hex64}" "${hex64}" L12:ADMISSION_GUARD_ABSENT
python3 "${helper}" verify-first-failure "${first_failure_path}" RUN "${RELEASE_SHA}" "${hex64}" 73 >/dev/null
if python3 "${helper}" verify-first-failure "${first_failure_path}" RUN "${RELEASE_SHA}" "${hex64}" 74 >/dev/null 2>&1; then fail 'wrong-status first failure passed'; fi
chmod 0600 "${first_failure_path}"
if python3 "${helper}" verify-first-failure "${first_failure_path}" RUN "${RELEASE_SHA}" "${hex64}" 73 >/dev/null 2>&1; then fail 'wrong-mode first failure passed'; fi
pass

# Staging symlink and wrong-mode baselines are rejected before mutation.
root="$(make_case staging-symlink)"; rm "${root}/remote/staging/docker-compose.yml"; ln -s /dev/null "${root}/remote/staging/docker-compose.yml"; run_case "${root}" 1
root="$(make_case staging-wrong-mode)"; chmod 0666 "${root}/remote/staging/.env"; run_case "${root}" 1
pass

# The mocked harness never reaches a real external integration or mutation command.
escaped_commands=''
fixture_log_paths="$(find "${fixture_root}" -type f \( -name stderr -o -name mock-calls.log \) -print)" ||
  fail 'fixture external-command file inventory failed'
while IFS= read -r fixture_log; do
  [[ -n "${fixture_log}" ]] || continue
  if fixture_matches="$(grep -EnH 'forbidden external command escaped fixture|forbidden mock docker mutation' "${fixture_log}")"; then
    grep_exit=0
  else
    grep_exit=$?
  fi
  case "${grep_exit}" in
    0) escaped_commands="${escaped_commands}${escaped_commands:+$'\n'}${fixture_matches}" ;;
    1) ;;
    *) fail 'fixture external-command scan failed' ;;
  esac
done <<< "${fixture_log_paths}"
if [[ -n "${escaped_commands}" ]]; then
  printf '%s\n' "${escaped_commands}"
  fail 'real or forbidden external command escaped fixture isolation'
fi
pass

printf '%s\n' \
  'HT12R_PREREQUISITE_SYNC_FIXTURES=PASS' \
  'SUCCESS_PATH=PASS;GATE_A=NOT_STARTED' \
  'WRITE_FAILURES=DURING_4/4;AFTER_4/4;ROLLBACK_ONCE=PASS' \
  'POST_SYNC_FAILURES=40/40;NO_LATER_PHASE=PASS' \
  'NEGATIVE_COMMAND_ERRORS=PREWRITE_6/6;POST_SYNC_6/6' \
  'ROLLBACK_EVIDENCE=STRICT;DURABLE_UNLINK=FSYNCED' \
  'SIGNALS=SIGINT_130,SIGTERM_143;REPEATED_TRAP_ROLLBACK=ONCE' \
  'TRANSFER_REJECTIONS=ZERO_AND_PARTIAL' \
  'SOURCE_HELPER_CHECKPOINT_INTEGRITY=PASS' \
  'ENV_BYTE_PRESERVATION=PASS' \
  'PRODUCT_MATRIX=4_PASS/7_FAIL' \
  'CADDY_VERSION_MATRIX=4_PASS/8_FAIL' \
  'REAL_SSH=0;REAL_DOCKER=0;REAL_POSTGRESQL=0;REAL_CADDY=0;REAL_TELEGRAM=0' \
  "FIXTURE_GROUPS=${passes}/17"
