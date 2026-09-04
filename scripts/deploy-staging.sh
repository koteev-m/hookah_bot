#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

REMOTE="${1:-${STAGING_SSH:-}}"
STAGING_PATH="${STAGING_PATH:-/opt/hookah-bot}"
STAGING_DOMAIN="${STAGING_DOMAIN:-staging.hookahtootah.club}"
STAGING_PUBLIC_URL="${STAGING_PUBLIC_URL:-https://${STAGING_DOMAIN}}"
BACKEND_IMAGE="${BACKEND_IMAGE:-}"
DOCKER_PLATFORM="${DOCKER_PLATFORM:-linux/amd64}"
GRADLE_JVM_ARGS="${GRADLE_JVM_ARGS:--Xmx2048m -XX:MaxMetaspaceSize=768m}"
BACKEND_IMAGE_SOURCE="https://github.com/koteev-m/hookah_bot"
RUN_PUBLIC_CHECKS="${RUN_PUBLIC_CHECKS:-true}"
HEALTHCHECK_ATTEMPTS="${HEALTHCHECK_ATTEMPTS:-20}"
HEALTHCHECK_SLEEP_SECONDS="${HEALTHCHECK_SLEEP_SECONDS:-3}"
STAGING_ADMISSION_PROFILE="${STAGING_ADMISSION_PROFILE:-public-pilot}"
STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED="${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED:-false}"
EXPECTED_BACKEND_IMAGE_ID="${EXPECTED_BACKEND_IMAGE_ID:-}"
STAGING_ARTIFACT_PREFLIGHT_ONLY="${STAGING_ARTIFACT_PREFLIGHT_ONLY:-false}"

if [[ "${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED}" != "true" &&
  "${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED}" != "false" ]]; then
  echo "STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED must be true or false" >&2
  exit 2
fi
if [[ "${STAGING_ARTIFACT_PREFLIGHT_ONLY}" != "true" &&
  "${STAGING_ARTIFACT_PREFLIGHT_ONLY}" != "false" ]]; then
  echo "STAGING_ARTIFACT_PREFLIGHT_ONLY must be true or false" >&2
  exit 2
fi

if [[ -z "${REMOTE}" ]]; then
  echo "Usage: $0 user@vps-host"
  echo
  echo "Required env:"
  echo "  BACKEND_IMAGE=hookah_bot_ant-backend:<full-commit-sha>"
  echo "  EXPECTED_BACKEND_IMAGE_ID=sha256:<reviewed canonical image ID>"
  echo
  echo "Optional env:"
  echo "  STAGING_PATH=${STAGING_PATH}"
  echo "  STAGING_DOMAIN=${STAGING_DOMAIN}"
  echo "  STAGING_PUBLIC_URL=${STAGING_PUBLIC_URL}"
  echo "  DOCKER_PLATFORM=${DOCKER_PLATFORM}"
  echo "  HEALTHCHECK_ATTEMPTS=${HEALTHCHECK_ATTEMPTS}"
  echo "  HEALTHCHECK_SLEEP_SECONDS=${HEALTHCHECK_SLEEP_SECONDS}"
  echo "  STAGING_ADMISSION_PROFILE=public-pilot"
  echo "    Use isolated-allowlist only for a separately reviewed isolated smoke."
  exit 2
fi

case "${STAGING_ADMISSION_PROFILE}" in
  public-pilot | isolated-allowlist) ;;
  *)
    echo "STAGING_ADMISSION_PROFILE must be public-pilot or isolated-allowlist" >&2
    exit 2
    ;;
esac

if [[ "${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED}" == "true" &&
  "${STAGING_ADMISSION_PROFILE}" != "public-pilot" ]]; then
  echo "V126_SMOKE authorization requires the public-pilot PRODUCT admission profile" >&2
  exit 2
fi

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

require_cmd docker
require_cmd git
require_cmd ssh
require_cmd rsync
require_cmd gzip
require_cmd curl

if [[ ! "${BACKEND_IMAGE}" =~ :[0-9a-f]{40}$ ]]; then
  echo "BACKEND_IMAGE is required and must use a full lowercase commit-SHA tag" >&2
  exit 2
fi
if [[ ! "${EXPECTED_BACKEND_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "EXPECTED_BACKEND_IMAGE_ID is required and must be a canonical sha256 image ID" >&2
  exit 2
fi

release_sha="${BACKEND_IMAGE##*:}"
if [[ "$(git -C "${REPO_ROOT}" rev-parse --verify HEAD^{commit})" != "${release_sha}" ||
  -n "$(git -C "${REPO_ROOT}" status --porcelain=v1 --untracked-files=all)" ]]; then
  echo "Backend image builds require the clean exact Git worktree named by BACKEND_IMAGE" >&2
  exit 2
fi
source_date_epoch="$(git -C "${REPO_ROOT}" show -s --format=%ct "${release_sha}")"
if [[ ! "${source_date_epoch}" =~ ^[0-9]+$ ]]; then
  echo "Cannot derive SOURCE_DATE_EPOCH from BACKEND_IMAGE commit" >&2
  exit 2
fi

wait_http() {
  local label="$1"
  local method="$2"
  local url="$3"
  local attempt

  echo "==> Waiting for ${label}: ${url}"
  for attempt in $(seq 1 "${HEALTHCHECK_ATTEMPTS}"); do
    if [[ "${method}" == "HEAD" ]]; then
      if curl -fsSI "${url}" >/dev/null; then
        echo "OK: ${label}"
        return 0
      fi
    elif curl -fsS "${url}"; then
      echo
      echo "OK: ${label}"
      return 0
    fi

    echo "Waiting for ${label} (${attempt}/${HEALTHCHECK_ATTEMPTS})..."
    sleep "${HEALTHCHECK_SLEEP_SECONDS}"
  done

  echo "Health check failed after ${HEALTHCHECK_ATTEMPTS} attempts: ${label}" >&2
  echo "Do not redeploy blindly. Inspect backend logs and container status on the VPS first." >&2
  return 1
}

if ! docker buildx version >/dev/null 2>&1; then
  echo "Docker buildx is required for cross-platform staging builds." >&2
  exit 2
fi

cd "${REPO_ROOT}"

echo "==> Validating staging admission guard fixtures"
bash scripts/validate-staging-admission.sh --self-test docker-compose.yml

echo "==> Building backend image locally: ${BACKEND_IMAGE} (${DOCKER_PLATFORM})"
docker buildx build \
  --platform "${DOCKER_PLATFORM}" \
  --pull \
  --no-cache \
  --provenance=false \
  --output "type=docker,oci-mediatypes=true,rewrite-timestamp=true" \
  --tag "${BACKEND_IMAGE}" \
  --build-arg "VITE_BACKEND_PUBLIC_URL=${STAGING_PUBLIC_URL}" \
  --build-arg "GRADLE_JVM_ARGS=${GRADLE_JVM_ARGS}" \
  --build-arg "SOURCE_DATE_EPOCH=${source_date_epoch}" \
  --label "org.opencontainers.image.revision=${release_sha}" \
  --label "org.opencontainers.image.source=${BACKEND_IMAGE_SOURCE}" \
  -f backend/Dockerfile \
  .

## This comparison must stay before every SSH, rsync, image upload, or remote mutation.
built_image_id="$(docker image inspect --format '{{.Id}}' "${BACKEND_IMAGE}")"
"${SCRIPT_DIR}/check-staging-image-identity.sh" \
  "${built_image_id}" \
  "${EXPECTED_BACKEND_IMAGE_ID}"

if [[ "${STAGING_ARTIFACT_PREFLIGHT_ONLY}" == "true" ]]; then
  echo "==> Local staging artifact preflight finished before SSH"
  exit 0
fi

echo "==> Uploading compose files to ${REMOTE}:${STAGING_PATH}"
ssh "${REMOTE}" "mkdir -p '${STAGING_PATH}'"
rsync -azR \
  docker-compose.yml \
  backend/Dockerfile \
  scripts/validate-staging-admission.sh \
  scripts/seed-staging.sh \
  scripts/check-staging-maintenance-config.sh \
  scripts/check-staging-image-identity.sh \
  docs/env/staging.env.example \
  docs/STAGING_DEPLOYMENT.md \
  "${REMOTE}:${STAGING_PATH}/"

if ! ssh "${REMOTE}" "test -f '${STAGING_PATH}/.env'"; then
  echo "Missing ${STAGING_PATH}/.env on VPS."
  echo "Create it from ${STAGING_PATH}/docs/env/staging.env.example, fill secrets, then rerun:"
  echo "  ssh ${REMOTE}"
  echo "  cd ${STAGING_PATH}"
  echo "  cp docs/env/staging.env.example .env"
  echo "  chmod 600 .env"
  exit 3
fi

echo "==> Checking required server env keys"
ssh "${REMOTE}" "
  set -euo pipefail
  cd '${STAGING_PATH}'
  missing=0
  for key in APP_ENV POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD DB_JDBC_URL DB_USER DB_PASSWORD TELEGRAM_TRAFFIC_POLICY TELEGRAM_WEBAPP_PUBLIC_URL MINIAPP_STATIC_DIR CORS_ALLOWED_HOSTS; do
    if ! grep -qE \"^\${key}=.+\" .env; then
      echo \"Missing or empty required env: \${key}\" >&2
      missing=1
    fi
  done
  if [[ \${missing} -ne 0 ]]; then
    exit \${missing}
  fi

  BACKEND_IMAGE='${BACKEND_IMAGE}' \
    bash scripts/validate-staging-admission.sh \
      --profile '${STAGING_ADMISSION_PROFILE}' \
      --env-file .env \
      --compose-file docker-compose.yml
"

echo "==> Checking staging maintenance policy"
ssh "${REMOTE}" "
  set -euo pipefail
  cd '${STAGING_PATH}'
  chmod +x scripts/check-staging-maintenance-config.sh
  STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED='${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED}' \\
    ./scripts/check-staging-maintenance-config.sh .env
"

echo "==> Uploading Docker image to VPS"
docker save "${BACKEND_IMAGE}" | gzip | ssh "${REMOTE}" "gzip -dc | docker load"

echo "==> Restarting staging services"
ssh "${REMOTE}" "
  set -euo pipefail
  cd '${STAGING_PATH}'
  wait_http() {
    local label=\"\$1\"
    local method=\"\$2\"
    local url=\"\$3\"
    local attempt

    echo \"==> Waiting for \${label}: \${url}\"
    for attempt in \$(seq 1 '${HEALTHCHECK_ATTEMPTS}'); do
      if [[ \"\${method}\" == \"HEAD\" ]]; then
        if curl -fsSI \"\${url}\" >/dev/null; then
          echo \"OK: \${label}\"
          return 0
        fi
      elif curl -fsS \"\${url}\"; then
        echo
        echo \"OK: \${label}\"
        return 0
      fi

      echo \"Waiting for \${label} (\${attempt}/'${HEALTHCHECK_ATTEMPTS}')...\"
      sleep '${HEALTHCHECK_SLEEP_SECONDS}'
    done

    echo \"Health check failed after '${HEALTHCHECK_ATTEMPTS}' attempts: \${label}\" >&2
    echo \"Do not redeploy blindly. Inspect with: cd '${STAGING_PATH}' && docker compose ps && docker compose logs --tail=120 backend\" >&2
    return 1
  }

  compose_staging() {
    env \
      -u APP_ENV \
      -u TELEGRAM_TRAFFIC_POLICY \
      -u TELEGRAM_ALLOWED_USER_IDS \
      -u TELEGRAM_ALLOWED_CHAT_IDS \
      -u STAGING_MAINTENANCE_MODE \
      -u STAGING_MAINTENANCE_ALLOWED_USER_IDS \
      -u STAGING_MAINTENANCE_ALLOWED_CHAT_IDS \
      -u VENUE_STAFF_INVITE_SECRET_PEPPER \
      BACKEND_IMAGE='${BACKEND_IMAGE}' \
      docker compose --env-file .env \"\$@\"
  }

  compose_staging up -d --no-build postgres backend
  compose_staging ps
  wait_http 'local backend health' GET http://127.0.0.1:8080/health
  wait_http 'local database health' GET http://127.0.0.1:8080/db/health
  wait_http 'local Mini App static' HEAD http://127.0.0.1:8080/miniapp/
"

if [[ "${RUN_PUBLIC_CHECKS}" == "true" ]]; then
  echo "==> Checking public staging URL: ${STAGING_PUBLIC_URL}"
  wait_http "public backend health" GET "${STAGING_PUBLIC_URL}/health"
  wait_http "public database health" GET "${STAGING_PUBLIC_URL}/db/health"
  wait_http "public Mini App static" HEAD "${STAGING_PUBLIC_URL}/miniapp/"
fi

echo "==> Staging deploy finished"
