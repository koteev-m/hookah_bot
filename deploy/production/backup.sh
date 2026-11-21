#!/usr/bin/env bash
# backup.sh ADMITTED_COMPOSE NEW_DIRECTORY AGE_RECIPIENT
set -euo pipefail
umask 077
[[ $# == 3 ]] || { echo 'Expected compose file, new directory and age recipient' >&2; exit 2; }
profile_dir=$(cd "$(dirname "$0")" && pwd)
profile="$profile_dir/../../scripts/production_profile.py"
cid=$(python3 -B "$profile" backup-db "$1" "$3")
mkdir -m 700 -- "$2"
# An incomplete directory has no SHA256SUMS. Retry into a fresh directory.
python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("x-hookah-schema", 142))' "$1" > "$2/schema-version"
docker inspect --format '{{.Config.Image}}' "$cid" > "$2/postgres-image"
docker exec "$cid" pg_dump -w -U hookah_app -d hookah_prod -Fc |
    age --encrypt --recipient "$3" --output "$2/database.dump.age"
[[ "$(python3 -B "$profile" backup-db "$1" "$3")" == "$cid" ]]
(cd "$2" && shasum -a 256 database.dump.age postgres-image schema-version > SHA256SUMS.partial && mv SHA256SUMS.partial SHA256SUMS)
echo 'Encrypted backup written'
