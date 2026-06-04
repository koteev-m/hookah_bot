#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PUBLIC_TUNNEL_URL="${PUBLIC_TUNNEL_URL:-}"
LOCAL_DB_JDBC_URL="${DB_JDBC_URL:-jdbc:postgresql://localhost:5433/hookah}"
LOCAL_DB_USER="${DB_USER:-hookah}"
LOCAL_DB_PASSWORD="${DB_PASSWORD:-hookah}"

if [[ -z "${PUBLIC_TUNNEL_URL}" ]]; then
  echo "PUBLIC_TUNNEL_URL is required."
  echo "Example:"
  echo "  PUBLIC_TUNNEL_URL=https://dev.hookahtootah.club scripts/run-local-telegram-miniapp.sh"
  exit 2
fi

PUBLIC_TUNNEL_URL="${PUBLIC_TUNNEL_URL%/}"

if [[ ! "${PUBLIC_TUNNEL_URL}" =~ ^https://[^/]+$ ]]; then
  echo "PUBLIC_TUNNEL_URL must be an HTTPS origin without path."
  echo "Example: https://dev.hookahtootah.club"
  exit 2
fi

cd "${REPO_ROOT}"

echo "==> Starting local backend for Telegram Mini App smoke"
echo "    public origin: ${PUBLIC_TUNNEL_URL}"
echo "    webapp url:    ${PUBLIC_TUNNEL_URL}/miniapp/"
echo "    miniapp dev:   http://localhost:5173"
echo "    db jdbc url:   ${LOCAL_DB_JDBC_URL}"
echo "    db user:       ${LOCAL_DB_USER}"
echo "    secrets:       not printed"
echo
echo "Safety: stop staging backend before running this script if it uses the same TELEGRAM_BOT_TOKEN."
echo

APP_ENV="${APP_ENV:-dev}" \
TELEGRAM_BOT_MODE="${TELEGRAM_BOT_MODE:-long_polling}" \
MINIAPP_ENTRY_ENABLED="${MINIAPP_ENTRY_ENABLED:-true}" \
TELEGRAM_WEBAPP_PUBLIC_URL="${PUBLIC_TUNNEL_URL}/miniapp/" \
MINIAPP_DEV_SERVER_URL="${MINIAPP_DEV_SERVER_URL:-http://localhost:5173}" \
MINIAPP_STATIC_DIR= \
VITE_BACKEND_PUBLIC_URL="${PUBLIC_TUNNEL_URL}" \
CORS_ALLOWED_HOSTS="${PUBLIC_TUNNEL_URL}" \
DB_JDBC_URL="${LOCAL_DB_JDBC_URL}" \
DB_USER="${LOCAL_DB_USER}" \
DB_PASSWORD="${LOCAL_DB_PASSWORD}" \
./gradlew --no-daemon :backend:app:run --console=plain
