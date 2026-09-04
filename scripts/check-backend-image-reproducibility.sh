#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPARATOR="${SCRIPT_DIR}/backend-image-reproducibility.py"
ARCHIVE_TOOL="${SCRIPT_DIR}/docker-save-archive.py"
RELEASE_HELPER="${SCRIPT_DIR}/backend-image-release-artifact.sh"
DOCKERFILE="${REPO_ROOT}/backend/Dockerfile"
EXPECTED_SOURCE="https://github.com/koteev-m/hookah_bot"
PUBLIC_URL="${BACKEND_REPRO_PUBLIC_URL:-https://staging.hookahtootah.club}"
GRADLE_JVM_ARGS="-Xmx2048m -XX:MaxMetaspaceSize=768m"
PRESERVE_EVIDENCE="${PRESERVE_REPRO_EVIDENCE:-false}"
RELEASE_TAG=''
DOCKER_SAVE_OUTPUT=''
PUBLISHED_ARCHIVE=''
RUN_TEMP_FILES=()

source "${RELEASE_HELPER}"

if [[ "${1:-}" == "--self-test" ]]; then
  [[ $# -eq 1 ]] || {
    echo "Usage: $0 [--self-test] [--release-tag <full-SHA-tag>] [--docker-save-output <new-absolute-path>]" >&2
    exit 2
  }
  python3 "${COMPARATOR}" self-test
  python3 "${COMPARATOR}" base-pins "${DOCKERFILE}" >/dev/null
  python3 "${ARCHIVE_TOOL}" self-test --cutover-script "${SCRIPT_DIR}/v126-cutover.sh"
  backend_release_artifact_self_test
  exit 0
fi
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-tag)
      [[ $# -ge 2 ]] || {
        echo 'Missing --release-tag value' >&2
        exit 2
      }
      RELEASE_TAG="$2"
      shift 2
      ;;
    --docker-save-output)
      [[ $# -ge 2 ]] || {
        echo 'Missing --docker-save-output value' >&2
        exit 2
      }
      DOCKER_SAVE_OUTPUT="$2"
      shift 2
      ;;
    *)
      echo "Usage: $0 [--self-test] [--release-tag <full-SHA-tag>] [--docker-save-output <new-absolute-path>]" >&2
      exit 2
      ;;
  esac
done
if [[ "${PRESERVE_EVIDENCE}" != "true" && "${PRESERVE_EVIDENCE}" != "false" ]]; then
  echo "PRESERVE_REPRO_EVIDENCE must be true or false" >&2
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
require_cmd python3

cd "${REPO_ROOT}"
if [[ "$(git rev-parse --show-toplevel)" != "${REPO_ROOT}" ]]; then
  echo "The reproducibility guard must run from its exact Git worktree" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain=v1 --untracked-files=all)" ]]; then
  echo "The reproducibility guard requires a clean exact Git worktree" >&2
  git status --short >&2
  exit 2
fi

HEAD_SHA="$(git rev-parse --verify HEAD^{commit})"
HEAD_TREE="$(git rev-parse HEAD^{tree})"
SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
if [[ ! "${HEAD_SHA}" =~ ^[0-9a-f]{40}$ || ! "${HEAD_TREE}" =~ ^[0-9a-f]{40}$ ||
  ! "${SOURCE_DATE_EPOCH}" =~ ^[0-9]+$ ]]; then
  echo "Cannot derive canonical Git identity and SOURCE_DATE_EPOCH" >&2
  exit 2
fi
if [[ -z "${RELEASE_TAG}" ]]; then
  RELEASE_TAG="hookah_bot_ant-backend:${HEAD_SHA}"
fi
if [[ ! "${RELEASE_TAG}" =~ ^[a-z0-9][a-z0-9._/-]*:${HEAD_SHA}$ ]]; then
  echo "Release tag must be one canonical name ending in the exact full HEAD SHA" >&2
  exit 2
fi
if [[ -n "${DOCKER_SAVE_OUTPUT}" ]]; then
  if [[ "${DOCKER_SAVE_OUTPUT}" != /* || "${DOCKER_SAVE_OUTPUT}" == / ||
    -e "${DOCKER_SAVE_OUTPUT}" || -L "${DOCKER_SAVE_OUTPUT}" ]]; then
    echo "Docker-save output must be a new absolute path" >&2
    exit 2
  fi
  if [[ ! -d "$(dirname "${DOCKER_SAVE_OUTPUT}")" ||
    -L "$(dirname "${DOCKER_SAVE_OUTPUT}")" ]]; then
    echo "Docker-save output parent must be an existing non-symlink directory" >&2
    exit 2
  fi
fi

docker version
docker buildx version
if docker image inspect "${RELEASE_TAG}" >/dev/null 2>&1; then
  echo "Refusing to reuse existing canonical release tag: ${RELEASE_TAG}" >&2
  exit 2
fi

RUN_TOKEN="${HEAD_SHA:0:12}-$$-${RANDOM}"
BUILDER_A="ht12t-${RUN_TOKEN}-a"
BUILDER_B="ht12t-${RUN_TOKEN}-b"
TAG_A="hookah-bot-ant-repro:${RUN_TOKEN}-a"
TAG_B="hookah-bot-ant-repro:${RUN_TOKEN}-b"
EVIDENCE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/backend-image-repro.${RUN_TOKEN}.XXXXXX")"
chmod 700 "${EVIDENCE_DIR}"
CREATED_BUILDERS=()
CREATED_TAGS=()

cleanup() {
  local cleanup_status="$1"
  local tag
  local builder
  local path
  trap - EXIT INT TERM
  for tag in "${CREATED_TAGS[@]}"; do
    docker image rm "${tag}" >/dev/null 2>&1 || true
  done
  for builder in "${CREATED_BUILDERS[@]}"; do
    docker buildx rm --force "${builder}" >/dev/null 2>&1 || true
  done
  for path in "${RUN_TEMP_FILES[@]}"; do
    [[ -n "${path}" && "${path}" != / ]] && rm -f -- "${path}"
  done
  if [[ "${PRESERVE_EVIDENCE}" == "true" ]]; then
    chmod -R go-rwx "${EVIDENCE_DIR}" || true
    if [[ "${cleanup_status}" -ne 0 ]]; then
      echo "Failure evidence preserved at ${EVIDENCE_DIR}" >&2
    else
      echo "Requested evidence preserved at ${EVIDENCE_DIR}"
    fi
  else
    rm -rf -- "${EVIDENCE_DIR}"
  fi
  exit "${cleanup_status}"
}
trap 'cleanup "$?"' EXIT
trap 'cleanup 130' INT
trap 'cleanup 143' TERM

echo "Git revision: ${HEAD_SHA}"
echo "Git tree: ${HEAD_TREE}"
echo "SOURCE_DATE_EPOCH: ${SOURCE_DATE_EPOCH}"
echo "Platform: linux/amd64"
echo "Exporter: type=docker,oci-mediatypes=true,rewrite-timestamp=true plus mode-0600 OCI evidence"
echo "Provenance export: disabled"
echo "Public URL build argument: ${PUBLIC_URL}"
echo "Canonical release tag: ${RELEASE_TAG}"

BASE_PINS_FILE="${EVIDENCE_DIR}/base-pins.tsv"
python3 "${COMPARATOR}" base-pins "${DOCKERFILE}" >"${BASE_PINS_FILE}"
while IFS=$'\t' read -r stage tag index_digest platform_digest; do
  raw_index="${EVIDENCE_DIR}/base-${stage}.index.json"
  echo "Verifying base ${stage}: ${tag}@${index_digest} linux/amd64=${platform_digest}"
  docker buildx imagetools inspect --raw "${tag}@${index_digest}" >"${raw_index}"
  python3 "${COMPARATOR}" verify-index "${raw_index}" "${index_digest}" "${platform_digest}"
done <"${BASE_PINS_FILE}"

create_builder() {
  local builder="$1"
  if docker buildx inspect "${builder}" >/dev/null 2>&1; then
    echo "Refusing to reuse existing builder: ${builder}" >&2
    exit 2
  fi
  docker buildx create --name "${builder}" --driver docker-container >/dev/null
  CREATED_BUILDERS+=("${builder}")
  docker buildx inspect --bootstrap "${builder}"
}

build_image() {
  local label="$1"
  local builder="$2"
  local tag="$3"
  local metadata="$4"
  local archive="$5"
  if docker image inspect "${tag}" >/dev/null 2>&1; then
    echo "Refusing to reuse existing image tag: ${tag}" >&2
    exit 2
  fi
  echo "Building independent image ${label} with builder ${builder}"
  CREATED_TAGS+=("${tag}")
  docker buildx build \
    --builder "${builder}" \
    --platform linux/amd64 \
    --pull \
    --no-cache \
    --provenance=false \
    --output "type=docker,oci-mediatypes=true,rewrite-timestamp=true" \
    --output "type=oci,dest=${archive},oci-mediatypes=true,rewrite-timestamp=true" \
    --tag "${tag}" \
    --metadata-file "${metadata}" \
    --build-arg "VITE_BACKEND_PUBLIC_URL=${PUBLIC_URL}" \
    --build-arg "GRADLE_JVM_ARGS=${GRADLE_JVM_ARGS}" \
    --build-arg "SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH}" \
    --label "org.opencontainers.image.revision=${HEAD_SHA}" \
    --label "org.opencontainers.image.source=${EXPECTED_SOURCE}" \
    --file backend/Dockerfile \
    .
}

create_builder "${BUILDER_A}"
create_builder "${BUILDER_B}"
build_image A "${BUILDER_A}" "${TAG_A}" "${EVIDENCE_DIR}/build-a.metadata.json" \
  "${EVIDENCE_DIR}/image-a.tar"
build_image B "${BUILDER_B}" "${TAG_B}" "${EVIDENCE_DIR}/build-b.metadata.json" \
  "${EVIDENCE_DIR}/image-b.tar"

IMAGE_ID_A="$(docker image inspect --format '{{.Id}}' "${TAG_A}")"
IMAGE_ID_B="$(docker image inspect --format '{{.Id}}' "${TAG_B}")"
chmod 600 "${EVIDENCE_DIR}"/*

python3 "${COMPARATOR}" compare \
  "${EVIDENCE_DIR}/image-a.tar" "${EVIDENCE_DIR}/image-b.tar" \
  --image-id-a "${IMAGE_ID_A}" \
  --image-id-b "${IMAGE_ID_B}" \
  --expected-revision "${HEAD_SHA}" \
  --expected-source "${EXPECTED_SOURCE}" \
  --report "${EVIDENCE_DIR}/comparison.json"
MANIFEST_A="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["image_a"]["manifest_digest"])' "${EVIDENCE_DIR}/comparison.json")"
MANIFEST_B="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["image_b"]["manifest_digest"])' "${EVIDENCE_DIR}/comparison.json")"
python3 "${COMPARATOR}" verify-metadata \
  "${EVIDENCE_DIR}/build-a.metadata.json" "${DOCKERFILE}" "${SOURCE_DATE_EPOCH}" \
  "${HEAD_SHA}" "${EXPECTED_SOURCE}" "${PUBLIC_URL}" "${MANIFEST_A}"
python3 "${COMPARATOR}" verify-metadata \
  "${EVIDENCE_DIR}/build-b.metadata.json" "${DOCKERFILE}" "${SOURCE_DATE_EPOCH}" \
  "${HEAD_SHA}" "${EXPECTED_SOURCE}" "${PUBLIC_URL}" "${MANIFEST_B}"

backend_release_artifact_run \
  "${TAG_A}" "${TAG_B}" "${IMAGE_ID_A}" "${EVIDENCE_DIR}/comparison.json" \
  "${EVIDENCE_DIR}" "${HEAD_SHA}" "${EXPECTED_SOURCE}" "${RELEASE_TAG}" \
  "${DOCKER_SAVE_OUTPUT}"

echo "Backend full-image reproducibility guard passed"
