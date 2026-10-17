#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Legacy SSH-alias invocation is closed before any connection or Docker action.
case "${1:-}" in
  plan|simulate|initialize|deploy|rollback|accept|status|install|preflight|test-admit|public-admit|--help|-h) ;;
  *) exec python3 -B "${SCRIPT_DIR}/staging_release.py" deploy ;;
esac
exec python3 -B "${SCRIPT_DIR}/staging_release.py" "$@"
