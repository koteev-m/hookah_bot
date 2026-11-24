#!/usr/bin/env bash
# backup.sh ADMITTED_COMPOSE NEW_DIRECTORY AGE_RECIPIENT [--metadata-only | --online]
set -euo pipefail
exec python3 -B "$(dirname "$0")/media_backup.py" backup "$@"
