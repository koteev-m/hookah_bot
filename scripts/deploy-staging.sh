#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

REMOTE="${1:-${STAGING_SSH:-}}"
STAGING_PATH="${STAGING_PATH:-/opt/hookah-bot}"
STAGING_DOMAIN="${STAGING_DOMAIN:-staging.hookahtootah.club}"
STAGING_PUBLIC_URL="${STAGING_PUBLIC_URL:-https://${STAGING_DOMAIN}}"
BACKEND_IMAGE="${BACKEND_IMAGE:-hookah_bot_ant-backend:staging}"
DOCKER_PLATFORM="${DOCKER_PLATFORM:-linux/amd64}"
GRADLE_JVM_ARGS="${GRADLE_JVM_ARGS:--Xmx2048m -XX:MaxMetaspaceSize=768m}"
RUN_PUBLIC_CHECKS="${RUN_PUBLIC_CHECKS:-true}"
HEALTHCHECK_ATTEMPTS="${HEALTHCHECK_ATTEMPTS:-20}"
HEALTHCHECK_SLEEP_SECONDS="${HEALTHCHECK_SLEEP_SECONDS:-3}"

if [[ -z "${REMOTE}" ]]; then
  echo "Usage: $0 user@vps-host"
  echo
  echo "Optional env:"
  echo "  STAGING_PATH=${STAGING_PATH}"
  echo "  STAGING_DOMAIN=${STAGING_DOMAIN}"
  echo "  STAGING_PUBLIC_URL=${STAGING_PUBLIC_URL}"
  echo "  BACKEND_IMAGE=${BACKEND_IMAGE}"
  echo "  DOCKER_PLATFORM=${DOCKER_PLATFORM}"
  echo "  HEALTHCHECK_ATTEMPTS=${HEALTHCHECK_ATTEMPTS}"
  echo "  HEALTHCHECK_SLEEP_SECONDS=${HEALTHCHECK_SLEEP_SECONDS}"
  exit 2
fi

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

require_cmd docker
require_cmd ssh
require_cmd rsync
require_cmd gzip
require_cmd curl

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

echo "==> Validating compose config"
BACKEND_IMAGE="${BACKEND_IMAGE}" \
VITE_BACKEND_PUBLIC_URL="${STAGING_PUBLIC_URL}" \
MINIAPP_DEV_SERVER_URL= \
MINIAPP_STATIC_DIR=/app/miniapp \
CORS_ALLOWED_HOSTS="${STAGING_PUBLIC_URL}" \
docker compose config --quiet

echo "==> Uploading compose files to ${REMOTE}:${STAGING_PATH}"
ssh "${REMOTE}" "mkdir -p '${STAGING_PATH}'"
rsync -azR \
  docker-compose.yml \
  backend/Dockerfile \
  scripts/seed-staging.sh \
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
  for key in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD DB_JDBC_URL DB_USER DB_PASSWORD TELEGRAM_WEBAPP_PUBLIC_URL MINIAPP_STATIC_DIR CORS_ALLOWED_HOSTS; do
    if ! grep -qE \"^\${key}=.+\" .env; then
      echo \"Missing or empty required env: \${key}\" >&2
      missing=1
    fi
  done
  exit \${missing}
"

echo "==> Building backend image locally: ${BACKEND_IMAGE} (${DOCKER_PLATFORM})"
docker buildx build \
  --platform "${DOCKER_PLATFORM}" \
  --load \
  --tag "${BACKEND_IMAGE}" \
  --build-arg "VITE_BACKEND_PUBLIC_URL=${STAGING_PUBLIC_URL}" \
  --build-arg "GRADLE_JVM_ARGS=${GRADLE_JVM_ARGS}" \
  -f backend/Dockerfile \
  .

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

  BACKEND_IMAGE='${BACKEND_IMAGE}' docker compose up -d --no-build postgres backend
  BACKEND_IMAGE='${BACKEND_IMAGE}' docker compose ps
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
