#!/bin/sh
set -eu

log_file=$(mktemp)
trap 'rm -f "$log_file"' EXIT
trap 'exit 1' HUP INT TERM
attempt=1
while :; do
    if ./gradlew --version --no-daemon >"$log_file" 2>&1; then
        cat "$log_file"
        exit 0
    else
        status=$?
    fi
    cat "$log_file"
    if [ "$attempt" -ge 3 ] || ! grep -Eq '^Exception in thread "main" java\.io\.IOException: Server returned HTTP response code: 5[0-9][0-9] for URL:' "$log_file"; then
        exit "$status"
    fi
    delay=$((attempt * 2))
    echo "Gradle distribution returned HTTP 5xx; retrying in ${delay}s (attempt $((attempt + 1))/3)." >&2
    sleep "$delay"
    attempt=$((attempt + 1))
done
