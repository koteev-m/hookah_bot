#!/usr/bin/env bash
# Test runtime only. No operational image, Docker socket, HOME or Git credentials
# enter the network-denied container. The suites enforce namespace/capability guards.
set -Eeuo pipefail
umask 077
[[ $# == 1 && -n "$1" ]] || { echo 'usage: test-v126-policy-b-isolated.sh TEST_RUNTIME_IMAGE' >&2; exit 2; }
image="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/v126-policy-b-ci.XXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
mkdir "$fixture/scripts" "$fixture/docs"
# Explicit public test-source inventory, never the checkout's .git/config or .env.
cp "$root"/scripts/v126-* "$root"/scripts/test-v126-* "$fixture/scripts/"
cp "$root/docs/V126_DATABASE_RECOVERY_REHEARSAL.md" "$fixture/docs/"
docker run --rm --init --network none --read-only --cap-drop ALL \
  --cap-add SETUID --cap-add SETGID --cap-add SYS_CHROOT --cap-add CHOWN \
  --cap-add DAC_OVERRIDE --cap-add FOWNER --cap-add KILL \
  --pids-limit 256 --memory 2g \
  --tmpfs /tmp:rw,exec,nosuid,size=512m --tmpfs /run:rw,exec,nosuid,size=16m \
  --mount "type=bind,src=$fixture,dst=/work,readonly" --workdir /work \
  -e HOME=/tmp -e PYTHONDONTWRITEBYTECODE=1 "$image" bash -ceu '
    mkdir -p /run/sshd
    python3 scripts/test-v126-policy-b-linux.py --require-linux-ssh
    python3 scripts/test-v126-policy-b-transport.py --require-linux-ssh
    python3 scripts/test-v126-policy-b-attended.py
    python3 scripts/test-v126-genesis-history.py
    python3 scripts/test-v126-genesis-history.py --negative-controls
    python3 scripts/test-v126-genesis-linux.py --require-linux-ssh
  '
