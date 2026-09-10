#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

REMOTE="${1:-${STAGING_SSH:-}}"
STAGING_PATH="${STAGING_PATH:-/opt/hookah-bot}"
STAGING_DOMAIN="${STAGING_DOMAIN:-staging.hookahtootah.club}"
STAGING_PUBLIC_URL="${STAGING_PUBLIC_URL:-https://${STAGING_DOMAIN}}"
BACKEND_IMAGE="${BACKEND_IMAGE:-}"
DOCKER_PLATFORM="${DOCKER_PLATFORM:-linux/amd64}"
GRADLE_JVM_ARGS="${GRADLE_JVM_ARGS:--Xmx2048m -XX:MaxMetaspaceSize=768m}"
BACKEND_IMAGE_SOURCE="https://github.com/koteev-m/hookah_bot"
RUN_PUBLIC_CHECKS="${RUN_PUBLIC_CHECKS:-true}"
HEALTHCHECK_ATTEMPTS="${HEALTHCHECK_ATTEMPTS:-20}"
HEALTHCHECK_SLEEP_SECONDS="${HEALTHCHECK_SLEEP_SECONDS:-3}"
STAGING_ADMISSION_PROFILE="${STAGING_ADMISSION_PROFILE:-public-pilot}"
STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED="${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED:-false}"
EXPECTED_BACKEND_IMAGE_ID="${EXPECTED_BACKEND_IMAGE_ID:-}"
STAGING_ARTIFACT_PREFLIGHT_ONLY="${STAGING_ARTIFACT_PREFLIGHT_ONLY:-false}"

if [[ "${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED}" != "true" &&
  "${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED}" != "false" ]]; then
  echo "STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED must be true or false" >&2
  exit 2
fi
if [[ "${STAGING_ARTIFACT_PREFLIGHT_ONLY}" != "true" &&
  "${STAGING_ARTIFACT_PREFLIGHT_ONLY}" != "false" ]]; then
  echo "STAGING_ARTIFACT_PREFLIGHT_ONLY must be true or false" >&2
  exit 2
fi

if [[ -z "${REMOTE}" ]]; then
  echo "Usage: $0 user@vps-host"
  echo
  echo "Required env:"
  echo "  BACKEND_IMAGE=hookah_bot_ant-backend:<full-commit-sha>"
  echo "  EXPECTED_BACKEND_IMAGE_ID=sha256:<reviewed canonical image ID>"
  echo
  echo "Optional env:"
  echo "  STAGING_PATH=${STAGING_PATH}"
  echo "  STAGING_DOMAIN=${STAGING_DOMAIN}"
  echo "  STAGING_PUBLIC_URL=${STAGING_PUBLIC_URL}"
  echo "  DOCKER_PLATFORM=${DOCKER_PLATFORM}"
  echo "  HEALTHCHECK_ATTEMPTS=${HEALTHCHECK_ATTEMPTS}"
  echo "  HEALTHCHECK_SLEEP_SECONDS=${HEALTHCHECK_SLEEP_SECONDS}"
  echo "  STAGING_ADMISSION_PROFILE=public-pilot"
  echo "    Use isolated-allowlist only for a separately reviewed isolated smoke."
  exit 2
fi

case "${STAGING_ADMISSION_PROFILE}" in
  public-pilot | isolated-allowlist) ;;
  *)
    echo "STAGING_ADMISSION_PROFILE must be public-pilot or isolated-allowlist" >&2
    exit 2
    ;;
esac

if [[ "${STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED}" == "true" &&
  "${STAGING_ADMISSION_PROFILE}" != "public-pilot" ]]; then
  echo "V126_SMOKE authorization requires the public-pilot PRODUCT admission profile" >&2
  exit 2
fi

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 2
  fi
}

require_cmd docker
require_cmd git
require_cmd ssh
require_cmd python3

if [[ ! "${BACKEND_IMAGE}" =~ :[0-9a-f]{40}$ ]]; then
  echo "BACKEND_IMAGE is required and must use a full lowercase commit-SHA tag" >&2
  exit 2
fi
if [[ ! "${EXPECTED_BACKEND_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "EXPECTED_BACKEND_IMAGE_ID is required and must be a canonical sha256 image ID" >&2
  exit 2
fi

release_sha="${BACKEND_IMAGE##*:}"
if [[ "$(git -C "${REPO_ROOT}" rev-parse --verify HEAD^{commit})" != "${release_sha}" ||
  -n "$(git -C "${REPO_ROOT}" status --porcelain=v1 --untracked-files=all)" ]]; then
  echo "Backend image builds require the clean exact Git worktree named by BACKEND_IMAGE" >&2
  exit 2
fi
source_date_epoch="$(git -C "${REPO_ROOT}" show -s --format=%ct "${release_sha}")"
if [[ ! "${source_date_epoch}" =~ ^[0-9]+$ ]]; then
  echo "Cannot derive SOURCE_DATE_EPOCH from BACKEND_IMAGE commit" >&2
  exit 2
fi

if ! docker buildx version >/dev/null 2>&1; then
  echo "Docker buildx is required for cross-platform staging builds." >&2
  exit 2
fi

cd "${REPO_ROOT}"

echo "==> Validating staging admission guard fixtures"
bash scripts/validate-staging-admission.sh --self-test docker-compose.yml

echo "==> Building backend image locally: ${BACKEND_IMAGE} (${DOCKER_PLATFORM})"
docker buildx build \
  --platform "${DOCKER_PLATFORM}" \
  --pull \
  --no-cache \
  --provenance=false \
  --output "type=docker,oci-mediatypes=true,rewrite-timestamp=true" \
  --tag "${BACKEND_IMAGE}" \
  --build-arg "VITE_BACKEND_PUBLIC_URL=${STAGING_PUBLIC_URL}" \
  --build-arg "GRADLE_JVM_ARGS=${GRADLE_JVM_ARGS}" \
  --build-arg "SOURCE_DATE_EPOCH=${source_date_epoch}" \
  --label "org.opencontainers.image.revision=${release_sha}" \
  --label "org.opencontainers.image.source=${BACKEND_IMAGE_SOURCE}" \
  -f backend/Dockerfile \
  .

## This comparison must stay before every SSH, rsync, image upload, or remote mutation.
built_image_id="$(docker image inspect --format '{{.Id}}' "${BACKEND_IMAGE}")"
"${SCRIPT_DIR}/check-staging-image-identity.sh" \
  "${built_image_id}" \
  "${EXPECTED_BACKEND_IMAGE_ID}"

if [[ "${STAGING_ARTIFACT_PREFLIGHT_ONLY}" == "true" ]]; then
  echo "==> Local staging artifact preflight finished before SSH"
  exit 0
fi

# One remote invocation owns the persistent target lock from eligibility through
# payload installation, exact-image recreate/readiness and durable acknowledgement.
# The descriptor is approved/applied root authority, never an implicit .env edit.
[[ "${STAGING_PATH}" =~ ^/[A-Za-z0-9_./-]+$ ]] || { echo 'Unsafe staging target path' >&2; exit 2; }
: "${APPROVED_DEPLOYMENT_FILE:?Protected approved ordinary-deploy descriptor is required}"
: "${DEPLOY_STATE_DIR:?Fresh explicit deployment evidence directory is required}"
python3 "${SCRIPT_DIR}/v126-ordinary-deploy.py" client \
  --remote "${REMOTE}" \
  --target "${STAGING_PATH}" \
  --request-file "${APPROVED_DEPLOYMENT_FILE}" \
  --state-dir "${DEPLOY_STATE_DIR}" \
  --expected-image "${BACKEND_IMAGE}" \
  --expected-image-id "${EXPECTED_BACKEND_IMAGE_ID}" \
  --public-url "${STAGING_PUBLIC_URL}" \
  --public-checks "${RUN_PUBLIC_CHECKS}"

echo "==> Staging deploy durably acknowledged; next deployment requires explicit retirement"
