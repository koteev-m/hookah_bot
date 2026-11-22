#!/usr/bin/env bash
# backup.sh ADMITTED_COMPOSE NEW_DIRECTORY AGE_RECIPIENT
set -euo pipefail
umask 077
[[ $# == 3 || ( $# == 4 && "$4" == --metadata-only ) ]] || { echo 'Expected compose file, new directory, age recipient and optional --metadata-only' >&2; exit 2; }
profile_dir=$(cd "$(dirname "$0")" && pwd)
# Hold the traffic operator lock even when the caller supplies a media-disabled plan.
exec 9>>/var/lib/hookah-production/ingress/.operator.lock
flock -n 9
target() { python3 -B "$profile_dir/media_backup.py" target "$1" "$3"; }
binding=$(target "$@")
read -r cid backend_id media <<< "$binding"
members=(database.dump.age postgres-image schema-version)
if [[ "$media" == true ]]; then
    cold() {
        [[ ! -e /var/lib/hookah-production/ingress/open && ! -e /var/lib/hookah-production/ingress/activate ]] || return 1
        local running
        running=$(docker ps -q --filter label=com.docker.compose.project=hookah_production --filter label=com.docker.compose.service=backend) || return 1
        [[ -z "$running" ]]
    }
    cold || { echo 'Close traffic and stop the production backend before media backup' >&2; exit 1; }
fi
[[ "$media" == true || $# == 3 ]] || { echo "Metadata-only requires media mode" >&2; exit 1; }
mkdir -m 700 -- "$2"
# An incomplete directory has no SHA256SUMS. Retry into a fresh directory.
python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("x-hookah-schema", 142))' "$1" > "$2/schema-version"
docker inspect --format '{{.Config.Image}}' "$cid" > "$2/postgres-image"
python3 -B "$profile_dir/media_backup.py" capture "$1" "$3" "$binding" "$2" "${@:4}"
if [[ "$media" == true ]]; then
    if [[ ${4:-} != --metadata-only ]]; then
        members+=(media.tar.age)
    fi
    docker exec "$cid" psql -XqAtw -v ON_ERROR_STOP=1 -U postgres -d hookah_prod -c \
        'SELECT system_identifier, oid, timeline_id, pg_current_wal_insert_lsn() FROM pg_control_system(), pg_control_checkpoint(), pg_database WHERE datname=current_database()' > "$2/source-coordinate"
    members+=(source-coordinate)
    cold
fi
[[ "$(target "$@")" == "$binding" ]]

(cd "$2" && shasum -a 256 "${members[@]}" > SHA256SUMS.partial && mv SHA256SUMS.partial SHA256SUMS)
echo 'Encrypted backup written'
