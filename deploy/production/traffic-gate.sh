#!/bin/sh
# A replacement container must not inherit a previous process's public admission.
set -eu
gate=$1
shift
export HOOKAH_PILOT_GATE_DIR="${gate%/*}"
while [ -e "$gate" ]; do sleep 1; done
exec "$@"
