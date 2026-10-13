#!/usr/bin/env bash
set -euo pipefail

# Compatibility name only. The single entrypoint owns the closed live gate.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/deploy-staging.sh" "$@"
