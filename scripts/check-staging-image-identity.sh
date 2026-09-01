#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "Staging image identity rejected: $1" >&2
  exit 4
}

validate_image_id() {
  local value="$1"
  local label="$2"
  [[ "${value}" =~ ^sha256:[0-9a-f]{64}$ ]] ||
    fail "${label} must be a canonical sha256 image ID"
}

compare_image_ids() {
  local actual="$1"
  local expected="$2"

  validate_image_id "${actual}" "actual image ID"
  validate_image_id "${expected}" "expected image ID"
  [[ "${actual}" == "${expected}" ]] ||
    fail "built image ID does not match the reviewed candidate"

  echo "Staging image identity preflight: PASS ${actual}"
}

first_line() {
  local file="$1"
  local marker="$2"
  local line
  line="$(grep -nF -- "${marker}" "${file}" | head -n 1 | cut -d: -f1 || true)"
  [[ -n "${line}" ]] || fail "deploy-order marker is missing"
  printf '%s' "${line}"
}

verify_deploy_order() {
  local deploy_script="$1"
  local required_tag_line
  local required_id_line
  local build_line
  local guard_line
  local remote_directory_line
  local rsync_line
  local image_upload_line

  [[ -f "${deploy_script}" ]] || fail "deploy script is unavailable"
  required_tag_line="$(first_line "${deploy_script}" 'BACKEND_IMAGE is required')"
  required_id_line="$(first_line "${deploy_script}" 'EXPECTED_BACKEND_IMAGE_ID is required')"
  build_line="$(first_line "${deploy_script}" 'docker buildx build \')"
  guard_line="$(first_line "${deploy_script}" '"${SCRIPT_DIR}/check-staging-image-identity.sh" \')"
  remote_directory_line="$(first_line "${deploy_script}" 'ssh "${REMOTE}" "mkdir -p')"
  rsync_line="$(first_line "${deploy_script}" 'rsync -azR \')"
  image_upload_line="$(first_line "${deploy_script}" 'docker save "${BACKEND_IMAGE}"')"

  (( required_tag_line < build_line )) || fail "full-SHA image tag must be required before build"
  (( required_id_line < build_line )) || fail "reviewed image identity must be required before build"
  (( build_line < guard_line )) || fail "identity guard must follow the local image build"
  (( guard_line < remote_directory_line )) || fail "identity guard must precede remote directory mutation"
  (( guard_line < rsync_line )) || fail "identity guard must precede rsync upload"
  (( guard_line < image_upload_line )) || fail "identity guard must precede image upload"
}

verify_controlmaster_order() {
  local controlmaster_script="$1"
  local preflight_line
  local master_line

  [[ -f "${controlmaster_script}" ]] || fail "ControlMaster deploy script is unavailable"
  preflight_line="$(first_line "${controlmaster_script}" 'STAGING_ARTIFACT_PREFLIGHT_ONLY=true \')"
  master_line="$(first_line "${controlmaster_script}" 'echo "==> Opening persistent SSH ControlMaster')"
  (( preflight_line < master_line )) || fail "artifact identity preflight must precede ControlMaster SSH"
}

self_test() {
  local deploy_script="${1:-}"
  local controlmaster_script="${2:-}"
  local first="sha256:$(printf 'a%.0s' {1..64})"
  local second="sha256:$(printf 'b%.0s' {1..64})"

  compare_image_ids "${first}" "${first}" >/dev/null
  if (compare_image_ids "${first}" "${second}") >/dev/null 2>&1; then
    fail "self-test expected a digest mismatch to fail"
  fi
  if (compare_image_ids "sha256:not-a-digest" "${first}") >/dev/null 2>&1; then
    fail "self-test expected a malformed digest to fail"
  fi
  if [[ -n "${deploy_script}" ]]; then
    verify_deploy_order "${deploy_script}"
  fi
  if [[ -n "${controlmaster_script}" ]]; then
    verify_controlmaster_order "${controlmaster_script}"
  fi

  echo "Staging image identity preflight self-test: PASS"
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test "${2:-}" "${3:-}"
  exit 0
fi

[[ $# -eq 2 ]] || fail "actual and expected image IDs are required"
compare_image_ids "$1" "$2"
