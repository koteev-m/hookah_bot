#!/usr/bin/env bash
set -euo pipefail

REMOTE="${DEV_REVERSE_TUNNEL_REMOTE:-hookah-staging}"
REMOTE_BIND="${DEV_REVERSE_TUNNEL_REMOTE_BIND:-127.0.0.1:18080}"
LOCAL_TARGET="${DEV_REVERSE_TUNNEL_LOCAL_TARGET:-localhost:8080}"

echo "==> Starting SSH reverse tunnel for dev.hookahtootah.club"
echo "    remote: ${REMOTE}"
echo "    remote bind: ${REMOTE_BIND}"
echo "    local target: ${LOCAL_TARGET}"
echo "    secrets: not used"
echo
echo "Keep this process running while local Telegram Mini App smoke is active."
echo

ssh -N -T \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -R "${REMOTE_BIND}:${LOCAL_TARGET}" \
  "${REMOTE}"
