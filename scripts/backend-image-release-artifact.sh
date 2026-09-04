#!/usr/bin/env bash

# Sourced by check-backend-image-reproducibility.sh after both images compare equal.

backend_release_artifact_verify_loaded_image() {
  local inspect_json="$1"
  local expected_image_id="$2"
  local expected_tag="$3"
  local comparison_json="$4"
  local revision="$5"
  local source="$6"
  python3 - "${inspect_json}" "${expected_image_id}" "${expected_tag}" \
    "${revision}" "${source}" "${comparison_json}" <<'PY'
import json
import sys

inspect_path, expected_id, expected_tag, revision, source, comparison_path = sys.argv[1:]
with open(inspect_path, encoding="utf-8") as handle:
    observed = json.load(handle)
with open(comparison_path, encoding="utf-8") as handle:
    comparison = json.load(handle)["image_a"]
if not isinstance(observed, list) or len(observed) != 1:
    raise SystemExit("loadability proof did not inspect exactly one image")
image = observed[0]
problems = []
if image.get("Id") != expected_id:
    problems.append("loaded image ID differs from the proven image")
if image.get("RepoTags") != [expected_tag]:
    problems.append("loaded image has an unexpected RepoTag inventory")
if image.get("Os") != "linux" or image.get("Architecture") != "amd64":
    problems.append("loaded image platform is not linux/amd64")
runtime = image.get("Config") or {}
if runtime.get("User") != "appuser":
    problems.append("loaded image runtime user is not appuser")
labels = runtime.get("Labels") or {}
if labels.get("org.opencontainers.image.revision") != revision:
    problems.append("loaded image revision label is not exact")
if labels.get("org.opencontainers.image.source") != source:
    problems.append("loaded image source label is not exact")
if (image.get("RootFS") or {}).get("Layers") != comparison.get("rootfs_diff_ids"):
    problems.append("loaded image rootfs DiffIDs/order differ from the proven build")
if problems:
    raise SystemExit("; ".join(problems))
PY
}

backend_release_artifact_run() {
  local tag_a="$1"
  local tag_b="$2"
  local image_id="$3"
  local comparison="$4"
  local evidence_dir="$5"
  local revision="$6"
  local source="$7"
  local release_tag="$8"
  local output_path="$9"
  local archive_parent
  local archive_name
  local archive_temp
  local verification_before
  local verification_after
  local archive_sha_before
  local archive_sha_after
  local archive_size
  local load_output
  local loaded_inspect

  if [[ ! "${release_tag}" =~ ^[a-z0-9][a-z0-9._/-]*:${revision}$ ]]; then
    echo "Release tag must be one canonical name ending in the exact full HEAD SHA" >&2
    return 2
  fi
  if docker image inspect "${release_tag}" >/dev/null 2>&1; then
    echo "Refusing to reuse existing canonical release tag: ${release_tag}" >&2
    return 2
  fi
  if [[ -n "${output_path}" ]]; then
    if [[ "${output_path}" != /* || "${output_path}" == / ||
      -e "${output_path}" || -L "${output_path}" ]]; then
      echo "Docker-save output must be a new absolute path" >&2
      return 2
    fi
    archive_parent="$(dirname "${output_path}")"
    if [[ ! -d "${archive_parent}" || -L "${archive_parent}" ]]; then
      echo "Docker-save output parent must be an existing non-symlink directory" >&2
      return 2
    fi
    archive_name="$(basename "${output_path}")"
  else
    archive_parent="${evidence_dir}"
    archive_name="hookah_bot_ant-backend-${revision}.docker-save.tar"
  fi

  docker image tag "${tag_a}" "${release_tag}"
  CREATED_TAGS+=("${release_tag}")
  [[ "$(docker image inspect --format '{{.Id}}' "${release_tag}")" == "${image_id}" ]] || {
    echo 'Canonical release tag does not resolve to proven image A' >&2
    return 1
  }

  archive_temp="$(mktemp "${archive_parent}/.${archive_name}.${revision:0:12}.XXXXXX")"
  RUN_TEMP_FILES+=("${archive_temp}")
  docker image save --output "${archive_temp}" "${release_tag}"
  python3 "${ARCHIVE_TOOL}" fsync "${archive_temp}"

  verification_before="${evidence_dir}/archive-verification-before-load.json"
  python3 "${ARCHIVE_TOOL}" verify "${archive_temp}" \
    --expected-tag "${release_tag}" \
    --expected-image-id "${image_id}" \
    --expected-revision "${revision}" \
    --expected-source "${source}" \
    --comparison "${comparison}" >"${verification_before}"
  chmod 600 "${verification_before}"
  archive_sha_before="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["archive_sha256"])' "${verification_before}")"
  archive_size="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["archive_size"])' "${verification_before}")"

  docker image rm "${release_tag}" >/dev/null
  docker image rm "${tag_a}" >/dev/null
  docker image rm "${tag_b}" >/dev/null
  if docker image inspect "${release_tag}" >/dev/null 2>&1 ||
    docker image inspect "${image_id}" >/dev/null 2>&1; then
    echo 'Loadability proof cannot exclude pre-existing local image state' >&2
    return 1
  fi

  load_output="${evidence_dir}/docker-load.output"
  docker image load --input "${archive_temp}" >"${load_output}" 2>&1
  chmod 600 "${load_output}"
  loaded_inspect="${evidence_dir}/loaded-image-inspect.json"
  docker image inspect "${release_tag}" >"${loaded_inspect}"
  chmod 600 "${loaded_inspect}"
  backend_release_artifact_verify_loaded_image \
    "${loaded_inspect}" "${image_id}" "${release_tag}" "${comparison}" \
    "${revision}" "${source}"

  verification_after="${evidence_dir}/archive-verification-after-load.json"
  python3 "${ARCHIVE_TOOL}" verify "${archive_temp}" \
    --expected-tag "${release_tag}" \
    --expected-image-id "${image_id}" \
    --expected-revision "${revision}" \
    --expected-source "${source}" \
    --comparison "${comparison}" >"${verification_after}"
  chmod 600 "${verification_after}"
  archive_sha_after="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["archive_sha256"])' "${verification_after}")"
  [[ "${archive_sha_after}" == "${archive_sha_before}" ]] || {
    echo 'Docker-save archive changed during exact load proof' >&2
    return 1
  }

  if [[ -n "${output_path}" ]]; then
    python3 "${ARCHIVE_TOOL}" publish "${archive_temp}" "${output_path}"
    PUBLISHED_ARCHIVE="${output_path}"
    local final_mode
    final_mode="$(python3 -c 'import os,stat,sys; print(f"{stat.S_IMODE(os.stat(sys.argv[1], follow_symlinks=False).st_mode):04o}")' "${output_path}")"
    [[ "${final_mode}" == 0600 ]] || {
      echo 'Published Docker-save archive mode is not 0600' >&2
      return 1
    }
  else
    rm -f -- "${archive_temp}"
  fi

  echo "canonical_release_tag=${release_tag}"
  echo "canonical_image_id=${image_id}"
  echo "canonical_archive_sha256=${archive_sha_before}"
  echo "canonical_archive_size=${archive_size}"
  echo "canonical_archive_loadability=PASS"
  if [[ -n "${PUBLISHED_ARCHIVE}" ]]; then
    echo "canonical_archive_output=${PUBLISHED_ARCHIVE}"
  else
    echo 'canonical_archive_output=validated-temporary-removed'
  fi
}

backend_release_artifact_self_test() (
  set -Eeuo pipefail
  umask 077
  local root
  local action_log
  local expected_log
  root="$(mktemp -d "${TMPDIR:-/tmp}/backend-image-release-self-test.XXXXXX")"
  trap 'rm -rf -- "${root}"' EXIT INT TERM
  mkdir -m 0700 "${root}/evidence"
  printf 'preserve\n' >"${root}/preserved.tar"
  printf 'unowned\n' >"${root}/unowned"
  printf 'temporary\n' >"${root}/temporary"
  printf 'internal\n' >"${root}/evidence/internal.tar"
  action_log="${root}/docker-actions.log"
  expected_log="${root}/expected-actions.log"
  docker() {
    printf '%s\n' "$*" >>"${action_log}"
  }
  CREATED_TAGS=(run-a run-b canonical-release loaded-release)
  CREATED_BUILDERS=(builder-a builder-b)
  RUN_TEMP_FILES=("${root}/temporary" "${root}/evidence/internal.tar")
  PUBLISHED_ARCHIVE="${root}/preserved.tar"
  for tag in "${CREATED_TAGS[@]}"; do
    docker image rm "${tag}" >/dev/null 2>&1 || true
  done
  for builder in "${CREATED_BUILDERS[@]}"; do
    docker buildx rm --force "${builder}" >/dev/null 2>&1 || true
  done
  for path in "${RUN_TEMP_FILES[@]}"; do
    rm -f -- "${path}"
  done
  cat >"${expected_log}" <<'EOF'
image rm run-a
image rm run-b
image rm canonical-release
image rm loaded-release
buildx rm --force builder-a
buildx rm --force builder-b
EOF
  cmp -s "${action_log}" "${expected_log}" || {
    echo 'Artifact cleanup touched an unexpected Docker resource' >&2
    exit 1
  }
  [[ -f "${PUBLISHED_ARCHIVE}" && -f "${root}/unowned" ]] || {
    echo 'Artifact cleanup removed a preserved or unowned path' >&2
    exit 1
  }
  [[ ! -e "${root}/temporary" && ! -e "${root}/evidence/internal.tar" ]] || {
    echo 'Artifact cleanup left a run-owned temporary archive' >&2
    exit 1
  }
  printf 'Backend release-artifact cleanup self-test passed: run-owned resources only\n'
)

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -Eeuo pipefail
  umask 077
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  ARCHIVE_TOOL="${SCRIPT_DIR}/docker-save-archive.py"
  CREATED_BUILDERS=()
  CREATED_TAGS=()
  RUN_TEMP_FILES=()
  PUBLISHED_ARCHIVE=''
  [[ "${1:-}" == --self-test && $# -eq 1 ]] || {
    echo "Usage: $0 --self-test" >&2
    exit 2
  }
  backend_release_artifact_self_test
fi
