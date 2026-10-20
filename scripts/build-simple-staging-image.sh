#!/usr/bin/env bash
# Run on a trusted build machine, never on the VPS. Explicit invocation publishes an image.
set -euo pipefail
if [[ $# != 3 ]]; then
  echo 'Usage: bash scripts/build-simple-staging-image.sh <release-sha> <completed-ci-run-id> <new-output-directory>' >&2
  exit 2
fi
revision="$1"
ci_run="$2"
output="$3"
[[ "$revision" =~ ^[0-9a-f]{40}$ && "$ci_run" =~ ^[0-9]+$ ]]
[[ "$(git --no-optional-locks rev-parse HEAD)" == "$revision" ]]
[[ -z "$(git --no-optional-locks status --porcelain --untracked-files=all)" ]]
python3 -B scripts/simple_staging.py verify-ci --revision "$revision" --ci-run "$ci_run"
umask 077
mkdir "$output"
output="$(cd "$output" && pwd -P)"
image="ghcr.io/koteev-m/hookah_bot/backend:$revision"
docker buildx build --platform linux/amd64 --provenance=false \
  --file backend/Dockerfile \
  --build-arg VITE_BACKEND_PUBLIC_URL=https://staging.hookahtootah.club \
  --label org.opencontainers.image.source=https://github.com/koteev-m/hookah_bot \
  --label "org.opencontainers.image.revision=$revision" \
  --tag "$image" --push --metadata-file "$output/build.json" .
python3 - "$output" "$revision" "$ci_run" <<'PY'
import json, pathlib, re, sys
root, revision, ci_run = pathlib.Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
digest = json.loads((root / 'build.json').read_text())['containerimage.digest']
if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
    raise SystemExit('Published digest missing; no deploy candidate created')
(root / 'candidate.json').write_text(json.dumps({'image': 'ghcr.io/koteev-m/hookah_bot/backend@' + digest,
                                               'revision': revision, 'ci_run': ci_run}, indent=2) + '\n')
print('Published immutable candidate:', root / 'candidate.json')
PY
