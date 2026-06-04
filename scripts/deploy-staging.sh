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

if [[ -z "${REMOTE}" ]]; then
  echo "Usage: $0 user@vps-host"
  echo
  echo "Optional env:"
  echo "  STAGING_PATH=${STAGING_PATH}"
  echo "  STAGING_DOMAIN=${STAGING_DOMAIN}"
  echo "  STAGING_PUBLIC_URL=${STAGING_PUBLIC_URL}"
  echo "  BACKEND_IMAGE=${BACKEND_IMAGE}"
  echo "  DOCKER_PLATFORM=${DOCKER_PLATFORM}"
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
  BACKEND_IMAGE='${BACKEND_IMAGE}' docker compose up -d --no-build postgres backend
  BACKEND_IMAGE='${BACKEND_IMAGE}' docker compose ps
  curl -fsS http://127.0.0.1:8080/health
  echo
  curl -fsS http://127.0.0.1:8080/db/health
  echo
  curl -fsSI http://127.0.0.1:8080/miniapp/ >/dev/null
"

if [[ "${RUN_PUBLIC_CHECKS}" == "true" ]]; then
  echo "==> Checking public staging URL: ${STAGING_PUBLIC_URL}"
  curl -fsS "${STAGING_PUBLIC_URL}/health"
  echo
  curl -fsS "${STAGING_PUBLIC_URL}/db/health"
  echo
  curl -fsSI "${STAGING_PUBLIC_URL}/miniapp/" >/dev/null
fi

echo "==> Staging deploy finished"
