#!/usr/bin/env bash
set -Eeuo pipefail

umask 077
export LC_ALL=C

readonly V126_COMPLETE_MIGRATION_TREE='765956602de896b4498a956753272a6bc2d2971e'
readonly V126_POSTGRESQL_MIGRATION_TREE='bb2778e26e03e03211eab9f149777313f4a6f24b'
readonly V126_H2_MIGRATION_TREE='07b5ba6ccf25e79c9cc419b9095bb664f2cfae18'
readonly V126_MIGRATION_BLOB='6f39f7d33b1976d0f5eb7a70051bfc5351d12e56'
readonly V126_MIGRATION_SHA256='ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5'
readonly V126_FLYWAY_CHECKSUM='1701638026'
readonly V125_SOURCE_SHA='f577934691a1a7a79ba327c54e2055425142b7be'
readonly V125_IMAGE_ID='sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8'

readonly -a V126_STAGES=(
  BASELINE_VERIFIED
  PRE_DRAIN_BACKUP_REHEARSED
  CADDY_CANDIDATE_INSTALLED_AND_RELOADED
  PUBLIC_DRAIN_ACTIVE
  V125_BACKEND_STOPPED
  ZERO_WRITER_GATE_PASSED
  QUIESCED_BACKUP_REHEARSED
  FINAL_V125_PREFLIGHT_PASSED
  V126_MAINTENANCE_CONFIG_PREPARED
  V126_IMAGE_TRANSFERRED_AND_VERIFIED
  V126_BACKEND_STARTED
  V126_SCHEMA_RUNTIME_GATE_PASSED
  MANUAL_SMOKE_AUTHORIZED
  MANUAL_SMOKE_PASSED
  PUBLIC_DRAIN_REACTIVATED
  V126_BACKEND_STOPPED_FOR_OFF_TRANSITION
  MAINTENANCE_OFF_CONFIG_VERIFIED
  FINAL_V126_BACKEND_STARTED
  ORDINARY_CADDY_RESTORED
  FINAL_PUBLIC_GATES_PASSED
)

readonly GATE_A_TOKEN='AUTHORIZE_V126_CUTOVER_GATE_A'
readonly GATE_B_TOKEN='AUTHORIZE_V126_MANUAL_SMOKE_GATE_B'
readonly GATE_C_TOKEN='AUTHORIZE_V126_OFF_TRANSITION_GATE_C'
readonly PRE_V126_ROLLBACK_TOKEN='AUTHORIZE_V126_PRE_V126_ROLLBACK'
readonly POST_V126_STOP_TOKEN='AUTHORIZE_V126_POST_V126_FORWARD_FIX_STOP'
readonly FULL_DR_VERIFY_TOKEN='AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION'

REMOTE_MODE="${V126_INTERNAL_REMOTE_MODE:-false}"
if [[ "${REMOTE_MODE}" == true ]]; then
  [[ "${V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED:-}" == V126_INTERNAL_REMOTE_ENVELOPE_V1 ]] || {
    printf 'V126 cutover contract rejected: invalid internal remote envelope\n' >&2
    exit 4
  }
  SCRIPT_PATH=''
  SCRIPT_DIR=''
  REPO_ROOT=''
else
  SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  SCRIPT_DIR="$(dirname "${SCRIPT_PATH}")"
  REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
fi

STATE_DIR=''
RUN_ID=''
RELEASE_SHA=''
RELEASE_TREE=''
RELEASE_PARENTS=''
MAIN_ACTIONS_RUN_ID=''
RELEASE_WORKTREE=''
REMOTE=''
STAGING_PATH=''
DATABASE_URL_FILE=''
MAINTENANCE_IDENTITIES_FILE=''
V126_IMAGE_TAG=''
V126_IMAGE_ID=''
V125_IMAGE_TAG=''
SCRIPT_SHA256=''
ACTIVE_OPERATION_KIND=''
ACTIVE_OPERATION_NAME=''
ACTIVE_PREDECESSOR_STAGE=''
ACTIVE_PREDECESSOR_HASH=''
ACTIVE_AUTHORIZATION_GATE=''
ACTIVE_AUTHORIZATION_HASH=''
ACTIVE_INTENT_HASH=''
LOCK_OWNER_PID=''
REMOTE_CAPTURED_CONTAINER_IDS=()
REMOTE_RECOVERY_ENV_BEFORE_SHA256=''
REMOTE_RECOVERY_ENV_AFTER_SHA256=''
REMOTE_BOUND_ENV_SHA256=''

die() {
  printf 'V126 cutover contract rejected: %s\n' "$*" >&2
  exit 4
}

usage() {
  cat <<'EOF'
Usage:
  scripts/v126-cutover.sh init --state-dir <absolute-new-dir> --run-id <id> \
    --release-sha <40-hex> --release-tree <40-hex> \
    --release-parents <40-hex[,40-hex]> --main-actions-run-id <id> \
    --release-worktree <absolute-clean-path> --remote <ssh-alias> \
    --staging-path <absolute-path> --database-url-file <absolute-remote-path> \
    --maintenance-identities-file <absolute-remote-path> \
    --v126-image-tag <name:release-sha> --v126-image-id <sha256:id> \
    --v125-image-tag <name:f577934691a1a7a79ba327c54e2055425142b7be>

  scripts/v126-cutover.sh authorize --state-dir <dir> --gate A|B|C \
    --authorization <exact-token>

  scripts/v126-cutover.sh stage --state-dir <dir> <STAGE_NAME> \
    [--evidence-file <absolute-path>]

  scripts/v126-cutover.sh status --state-dir <dir>

  scripts/v126-cutover.sh recover --state-dir <dir> pre-v126 \
    --authorization AUTHORIZE_V126_PRE_V126_ROLLBACK
  scripts/v126-cutover.sh recover --state-dir <dir> post-v126-stop \
    --authorization AUTHORIZE_V126_POST_V126_FORWARD_FIX_STOP
  scripts/v126-cutover.sh recover --state-dir <dir> verify-full-dr \
    --authorization AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION \
    --backup-phase pre-drain|quiesced --boundary-file <absolute-path>

Every `stage` invocation executes exactly one state. There is no multi-stage, retry,
fallback, deploy, build, restore, or automatic authorization command.

Gate tokens:
  A: AUTHORIZE_V126_CUTOVER_GATE_A
  B: AUTHORIZE_V126_MANUAL_SMOKE_GATE_B
  C: AUTHORIZE_V126_OFF_TRANSITION_GATE_C
EOF
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

hash_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${path}" | awk '{print $1}'
  else
    die 'sha256sum or shasum is required'
  fi
}

hash_text() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    die 'sha256sum or shasum is required'
  fi
}

snapshot_image_archive() {
  local source="$1"
  local snapshot="$2"
  python3 - "${source}" "${snapshot}" <<'PY'
import os
import stat
import sys

source_path, snapshot_path = sys.argv[1:]
nofollow = getattr(os, "O_NOFOLLOW", 0)
cloexec = getattr(os, "O_CLOEXEC", 0)
source_fd = os.open(source_path, os.O_RDONLY | nofollow | cloexec)
snapshot_fd = os.open(snapshot_path, os.O_WRONLY | os.O_TRUNC | nofollow | cloexec)
try:
    source_stat = os.fstat(source_fd)
    snapshot_stat = os.fstat(snapshot_fd)
    if not stat.S_ISREG(source_stat.st_mode) or stat.S_IMODE(source_stat.st_mode) != 0o600:
        raise SystemExit("source image archive must be a mode-0600 regular file")
    if not stat.S_ISREG(snapshot_stat.st_mode) or snapshot_stat.st_nlink != 1:
        raise SystemExit("image snapshot target must be one newly linked regular file")
    if (source_stat.st_dev, source_stat.st_ino) == (snapshot_stat.st_dev, snapshot_stat.st_ino):
        raise SystemExit("image snapshot source and target must be different files")
    while True:
        chunk = os.read(source_fd, 1024 * 1024)
        if not chunk:
            break
        view = memoryview(chunk)
        while view:
            written = os.write(snapshot_fd, view)
            if written <= 0:
                raise SystemExit("image snapshot write failed")
            view = view[written:]
    os.fsync(snapshot_fd)
    os.fchmod(snapshot_fd, 0o400)
finally:
    os.close(snapshot_fd)
    os.close(source_fd)
PY
}

verify_saved_image_archive_fd() {
  local archive_fd="$1"
  local expected_tag="$2"
  local expected_image_id="$3"
  [[ "${archive_fd}" =~ ^[0-9]+$ ]] || die 'saved V126 image archive FD is invalid'
  require_image_id expected-image-id "${expected_image_id}"
  python3 - "${archive_fd}" "${expected_tag}" "${expected_image_id#sha256:}" <<'PY'
import hashlib
import gzip
import io
import json
import os
import posixpath
import re
import stat
import sys
import tarfile

archive_fd_text, expected_tag, expected_digest = sys.argv[1:]
archive_fd = int(archive_fd_text)
archive_stat = os.fstat(archive_fd)
if (
    not stat.S_ISREG(archive_stat.st_mode)
    or stat.S_IMODE(archive_stat.st_mode) != 0o400
    or archive_stat.st_nlink != 0
):
    raise SystemExit("verified image snapshot must be an unlinked mode-0400 regular file")

os.lseek(archive_fd, 0, os.SEEK_SET)
digest = hashlib.sha256()
while True:
    chunk = os.read(archive_fd, 1024 * 1024)
    if not chunk:
        break
    digest.update(chunk)
archive_sha256 = digest.hexdigest()
os.lseek(archive_fd, 0, os.SEEK_SET)


def safe_member_name(value):
    return (
        isinstance(value, str)
        and value
        and not value.startswith("/")
        and posixpath.normpath(value) == value
        and all(part not in ("", ".", "..") for part in value.split("/"))
    )


def read_member(archive_file, member, label):
    if member.size > 16 * 1024 * 1024:
        raise SystemExit(f"{label} exceeds the bounded JSON size")
    handle = archive_file.extractfile(member)
    if handle is None:
        raise SystemExit(f"{label} is unreadable")
    return handle.read()


def read_json_member(archive_file, by_name, name, label):
    member = by_name.get(name)
    if member is None or not member.isfile():
        raise SystemExit(f"{label} is missing or non-regular")
    payload = read_member(archive_file, member, label)
    try:
        return json.loads(payload), payload
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SystemExit(f"{label} is invalid JSON") from error


def digest_layer(archive_file, member, expected_diff_id, number):
    raw_handle = archive_file.extractfile(member)
    if raw_handle is None:
        raise SystemExit(f"saved image archive layer {number} is unreadable")
    raw_digest = hashlib.sha256()
    uncompressed_digest = hashlib.sha256()

    class HashingReader(io.RawIOBase):
        def readable(self):
            return True

        def readinto(self, target):
            chunk = raw_handle.read(len(target))
            if not chunk:
                return 0
            raw_digest.update(chunk)
            target[:len(chunk)] = chunk
            return len(chunk)

    buffered = io.BufferedReader(HashingReader(), buffer_size=1024 * 1024)
    prefix = buffered.peek(2)[:2]
    try:
        content = gzip.GzipFile(fileobj=buffered, mode="rb") if prefix == b"\x1f\x8b" else buffered
        while True:
            chunk = content.read(1024 * 1024)
            if not chunk:
                break
            uncompressed_digest.update(chunk)
        if content is not buffered:
            content.close()
    except (gzip.BadGzipFile, EOFError, OSError) as error:
        raise SystemExit(f"saved image archive layer {number} gzip payload is invalid") from error
    finally:
        buffered.close()
    actual_diff_id = "sha256:" + uncompressed_digest.hexdigest()
    if actual_diff_id != expected_diff_id:
        raise SystemExit(f"saved image archive layer {number} DiffID mismatch")
    return "sha256:" + raw_digest.hexdigest()


with os.fdopen(os.dup(archive_fd), "rb") as archive_stream, tarfile.open(
    fileobj=archive_stream, mode="r:*"
) as archive_file:
    members = archive_file.getmembers()
    names = [member.name for member in members]
    if len(names) != len(set(names)):
        raise SystemExit("saved image archive has duplicate members")
    for member in members:
        if not safe_member_name(member.name):
            raise SystemExit("saved image archive contains an unsafe member path")
        if not (member.isfile() or member.isdir()):
            raise SystemExit("saved image archive contains a non-regular member")
    by_name = {member.name: member for member in members}
    manifest, _ = read_json_member(
        archive_file, by_name, "manifest.json", "saved image archive manifest.json"
    )
    if not isinstance(manifest, list) or len(manifest) != 1 or not isinstance(manifest[0], dict):
        raise SystemExit("saved image archive must contain exactly one image manifest")
    image = manifest[0]
    config_name = image.get("Config")
    repo_tags = image.get("RepoTags")
    layers = image.get("Layers")
    if repo_tags != [expected_tag]:
        raise SystemExit("saved image archive tag association mismatch")
    if not safe_member_name(config_name):
        raise SystemExit("saved image archive config path is unsafe")
    config_match = re.fullmatch(
        r"(?:blobs/sha256/)?([0-9a-f]{64})(?:\.json)?", config_name
    )
    if config_match is None:
        raise SystemExit("saved image archive config name has no canonical digest")
    config_member = by_name.get(config_name)
    if config_member is None or not config_member.isfile():
        raise SystemExit("saved image archive config is missing or non-regular")
    config_bytes = read_member(archive_file, config_member, "saved image archive config")
    config_digest = hashlib.sha256(config_bytes).hexdigest()
    if config_match.group(1) != config_digest:
        raise SystemExit("saved image archive config name differs from its content digest")
    try:
        config = json.loads(config_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SystemExit("saved image archive config is invalid JSON") from error
    if not isinstance(config, dict):
        raise SystemExit("saved image archive config is not an object")
    if config.get("os") != "linux" or config.get("architecture") != "amd64":
        raise SystemExit("saved image archive platform is not linux/amd64")
    runtime = config.get("config")
    if not isinstance(runtime, dict) or runtime.get("User") != "appuser":
        raise SystemExit("saved image archive runtime user is not appuser")
    labels = runtime.get("Labels")
    expected_revision = expected_tag.rsplit(":", 1)[-1]
    if not re.fullmatch(r"[0-9a-f]{40}", expected_revision):
        raise SystemExit("saved image archive tag has no exact Git revision")
    if not isinstance(labels, dict) or labels.get("org.opencontainers.image.revision") != expected_revision:
        raise SystemExit("saved image archive revision label is not exact")
    if labels.get("org.opencontainers.image.source") != "https://github.com/koteev-m/hookah_bot":
        raise SystemExit("saved image archive source label is not exact")
    rootfs = config.get("rootfs")
    diff_ids = rootfs.get("diff_ids") if isinstance(rootfs, dict) else None
    if (
        not isinstance(rootfs, dict)
        or rootfs.get("type") != "layers"
        or not isinstance(diff_ids, list)
        or not diff_ids
        or any(not isinstance(value, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", value) is None for value in diff_ids)
    ):
        raise SystemExit("saved image archive rootfs DiffIDs are invalid")
    if (
        not isinstance(layers, list)
        or not layers
        or len(layers) != len(set(layers))
        or len(layers) != len(diff_ids)
    ):
        raise SystemExit("saved image archive layer inventory is invalid")
    layer_digests = []
    for number, (layer_name, expected_diff_id) in enumerate(zip(layers, diff_ids), start=1):
        if not safe_member_name(layer_name):
            raise SystemExit("saved image archive layer path is unsafe")
        layer_member = by_name.get(layer_name)
        if layer_member is None or not layer_member.isfile():
            raise SystemExit("saved image archive layer is missing or non-regular")
        layer_digest = digest_layer(archive_file, layer_member, expected_diff_id, number)
        layer_digests.append(layer_digest)

    has_oci = "oci-layout" in by_name or "index.json" in by_name
    manifest_identity_mode = config_digest != expected_digest
    if has_oci or manifest_identity_mode:
        if "oci-layout" not in by_name or "index.json" not in by_name:
            raise SystemExit("saved image archive OCI identity metadata is incomplete")
        layout, _ = read_json_member(
            archive_file, by_name, "oci-layout", "saved image archive oci-layout"
        )
        if layout != {"imageLayoutVersion": "1.0.0"}:
            raise SystemExit("saved image archive OCI layout version is invalid")
        index, _ = read_json_member(
            archive_file, by_name, "index.json", "saved image archive OCI index"
        )
        descriptors = index.get("manifests") if isinstance(index, dict) else None
        if not isinstance(descriptors, list) or len(descriptors) != 1 or not isinstance(descriptors[0], dict):
            raise SystemExit("saved image archive OCI index must contain exactly one manifest")
        descriptor = descriptors[0]
        manifest_digest = descriptor.get("digest")
        if not isinstance(manifest_digest, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", manifest_digest) is None:
            raise SystemExit("saved image archive OCI manifest digest is invalid")
        manifest_name = "blobs/sha256/" + manifest_digest.removeprefix("sha256:")
        oci_manifest, oci_manifest_bytes = read_json_member(
            archive_file, by_name, manifest_name, "saved image archive OCI manifest"
        )
        if hashlib.sha256(oci_manifest_bytes).hexdigest() != manifest_digest.removeprefix("sha256:"):
            raise SystemExit("saved image archive OCI manifest digest differs from its bytes")
        if descriptor.get("size") != len(oci_manifest_bytes):
            raise SystemExit("saved image archive OCI manifest size is invalid")
        if manifest_identity_mode and manifest_digest.removeprefix("sha256:") != expected_digest:
            raise SystemExit("saved image archive OCI manifest differs from expected image ID")
        oci_config = oci_manifest.get("config") if isinstance(oci_manifest, dict) else None
        oci_layers = oci_manifest.get("layers") if isinstance(oci_manifest, dict) else None
        if (
            not isinstance(oci_config, dict)
            or oci_config.get("digest") != "sha256:" + config_digest
            or oci_config.get("size") != len(config_bytes)
        ):
            raise SystemExit("saved image archive OCI manifest does not bind exact config")
        oci_config_name = "blobs/sha256/" + config_digest
        oci_config_member = by_name.get(oci_config_name)
        if (
            oci_config_member is None
            or not oci_config_member.isfile()
            or read_member(
                archive_file, oci_config_member, "saved image archive OCI config blob"
            ) != config_bytes
        ):
            raise SystemExit("saved image archive OCI config blob is not exact")
        if not isinstance(oci_layers, list) or len(oci_layers) != len(layers):
            raise SystemExit("saved image archive OCI layer inventory is invalid")
        for number, (expected_diff_id, layer_descriptor) in enumerate(
            zip(diff_ids, oci_layers), start=1
        ):
            if not isinstance(layer_descriptor, dict):
                raise SystemExit("saved image archive OCI layer descriptor is invalid")
            descriptor_digest = layer_descriptor.get("digest")
            if (
                not isinstance(descriptor_digest, str)
                or re.fullmatch(r"sha256:[0-9a-f]{64}", descriptor_digest) is None
            ):
                raise SystemExit("saved image archive OCI layer digest is invalid")
            descriptor_name = "blobs/sha256/" + descriptor_digest.removeprefix("sha256:")
            descriptor_member = by_name.get(descriptor_name)
            if descriptor_member is None or not descriptor_member.isfile():
                raise SystemExit("saved image archive OCI layer blob is missing or non-regular")
            observed_digest = digest_layer(
                archive_file, descriptor_member, expected_diff_id, number
            )
            if (
                descriptor_digest != observed_digest
                or layer_descriptor.get("size") != descriptor_member.size
            ):
                raise SystemExit(f"saved image archive OCI layer {number} identity is invalid")
os.lseek(archive_fd, 0, os.SEEK_SET)
print(archive_sha256)
PY
}

utc_now() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

require_absolute_path() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^/[A-Za-z0-9._/+:-]+$ ]] || die "${name} must be a simple absolute path"
  [[ "${value}" != '/' ]] || die "${name} must not be /"
  [[ "${value}" != *'/../'* && "${value}" != */.. && "${value}" != *'/./'* && "${value}" != */. ]] ||
    die "${name} must not contain dot path components"
}

require_sha() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^[0-9a-f]{40}$ ]] || die "${name} must be 40 lowercase hex characters"
}

require_image_id() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^sha256:[0-9a-f]{64}$ ]] || die "${name} must be a canonical sha256 image ID"
}

stage_index() {
  local wanted="$1"
  local index
  for index in "${!V126_STAGES[@]}"; do
    if [[ "${V126_STAGES[${index}]}" == "${wanted}" ]]; then
      printf '%s\n' "$((index + 1))"
      return 0
    fi
  done
  return 1
}

stage_predecessor() {
  local stage="$1"
  local index
  index="$(stage_index "${stage}")" || die "unknown stage: ${stage}"
  if (( index == 1 )); then
    printf 'NONE\n'
  else
    printf '%s\n' "${V126_STAGES[$((index - 2))]}"
  fi
}

stage_gate() {
  local stage="$1"
  local index
  index="$(stage_index "${stage}")" || die "unknown stage: ${stage}"
  if (( index == 1 )); then
    printf 'NONE\n'
  elif (( index <= 12 )); then
    printf 'A\n'
  elif (( index <= 14 )); then
    printf 'B\n'
  else
    printf 'C\n'
  fi
}

gate_anchor_stage() {
  case "$1" in
    A) printf 'BASELINE_VERIFIED\n' ;;
    B) printf 'V126_SCHEMA_RUNTIME_GATE_PASSED\n' ;;
    C) printf 'MANUAL_SMOKE_PASSED\n' ;;
    *) die "unknown authorization gate: $1" ;;
  esac
}

gate_token() {
  case "$1" in
    A) printf '%s\n' "${GATE_A_TOKEN}" ;;
    B) printf '%s\n' "${GATE_B_TOKEN}" ;;
    C) printf '%s\n' "${GATE_C_TOKEN}" ;;
    *) die "unknown authorization gate: $1" ;;
  esac
}

stage_expected_artifacts() {
  case "$1" in
    BASELINE_VERIFIED)
      printf '%s\n' 'baseline-caddy,baseline-env,database-url-binding,local-baseline,main-actions,maintenance-identities,remote-admission-source,remote-compose-source,remote-maintenance-check-source,staging-baseline'
      ;;
    PRE_DRAIN_BACKUP_REHEARSED)
      printf '%s\n' 'pre-drain-backup-dump,pre-drain-backup-inventory,pre-drain-backup-proof,pre-drain-backup-rehearsal,pre-drain-globals'
      ;;
    CADDY_CANDIDATE_INSTALLED_AND_RELOADED)
      printf '%s\n' 'caddy-activation,caddy-candidate,caddy-diff,caddy-original'
      ;;
    PUBLIC_DRAIN_ACTIVE) printf '%s\n' 'public-drain-active' ;;
    V125_BACKEND_STOPPED) printf '%s\n' 'v125-backend-stopped' ;;
    ZERO_WRITER_GATE_PASSED) printf '%s\n' 'zero-writer-v125' ;;
    QUIESCED_BACKUP_REHEARSED)
      printf '%s\n' 'quiesced-backup-dump,quiesced-backup-inventory,quiesced-backup-proof,quiesced-backup-rehearsal'
      ;;
    FINAL_V125_PREFLIGHT_PASSED)
      printf '%s\n' 'final-v125-preflight,final-v125-preflight-source'
      ;;
    V126_MAINTENANCE_CONFIG_PREPARED) printf '%s\n' 'maintenance-v126_smoke' ;;
    V126_IMAGE_TRANSFERRED_AND_VERIFIED)
      printf '%s\n' 'local-v126-image-archive,v126-image-archive,v126-image-transfer-ready,v126-image-transferred'
      ;;
    V126_BACKEND_STARTED) printf '%s\n' 'v126-backend-first-started' ;;
    V126_SCHEMA_RUNTIME_GATE_PASSED) printf '%s\n' 'v126-schema-runtime' ;;
    MANUAL_SMOKE_AUTHORIZED) printf '%s\n' 'manual-smoke-handoff,manual-smoke-window' ;;
    MANUAL_SMOKE_PASSED) printf '%s\n' 'manual-smoke-evidence,manual-smoke-passed' ;;
    PUBLIC_DRAIN_REACTIVATED) printf '%s\n' 'public-drain-reactivated' ;;
    V126_BACKEND_STOPPED_FOR_OFF_TRANSITION) printf '%s\n' 'v126-off-transition-backend-stopped' ;;
    MAINTENANCE_OFF_CONFIG_VERIFIED) printf '%s\n' 'maintenance-off' ;;
    FINAL_V126_BACKEND_STARTED) printf '%s\n' 'v126-backend-final-started' ;;
    ORDINARY_CADDY_RESTORED) printf '%s\n' 'ordinary-caddy-restored' ;;
    FINAL_PUBLIC_GATES_PASSED) printf '%s\n' 'final-public-gates' ;;
    *) die "unknown stage artifact contract: $1" ;;
  esac
}

parse_option_value() {
  local option="$1"
  local value="${2:-}"
  [[ -n "${value}" && "${value}" != --* ]] || die "${option} requires a value"
  printf '%s\n' "${value}"
}

create_state() {
  local state_dir=''
  local run_id=''
  local release_sha=''
  local release_tree=''
  local release_parents=''
  local main_actions_run_id=''
  local release_worktree=''
  local remote=''
  local staging_path=''
  local database_url_file=''
  local maintenance_identities_file=''
  local v126_image_tag=''
  local v126_image_id=''
  local v125_image_tag=''

  shift
  while (( $# > 0 )); do
    case "$1" in
      --state-dir) state_dir="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --run-id) run_id="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --release-sha) release_sha="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --release-tree) release_tree="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --release-parents) release_parents="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --main-actions-run-id) main_actions_run_id="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --release-worktree) release_worktree="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --remote) remote="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --staging-path) staging_path="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --database-url-file) database_url_file="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --maintenance-identities-file) maintenance_identities_file="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --v126-image-tag) v126_image_tag="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --v126-image-id) v126_image_id="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --v125-image-tag) v125_image_tag="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      *) die "unknown init option: $1" ;;
    esac
  done

  require_cmd python3
  require_absolute_path state-dir "${state_dir}"
  require_absolute_path release-worktree "${release_worktree}"
  require_absolute_path staging-path "${staging_path}"
  require_absolute_path database-url-file "${database_url_file}"
  require_absolute_path maintenance-identities-file "${maintenance_identities_file}"
  [[ "${run_id}" =~ ^[a-z0-9][a-z0-9._-]{5,63}$ ]] || die 'run-id must be 6-64 safe lowercase characters'
  require_sha release-sha "${release_sha}"
  require_sha release-tree "${release_tree}"
  [[ "${release_parents}" =~ ^[0-9a-f]{40}(,[0-9a-f]{40})?$ ]] ||
    die 'release-parents must contain one or two comma-separated 40-hex SHAs'
  [[ "${main_actions_run_id}" =~ ^[1-9][0-9]*$ ]] || die 'main-actions-run-id must be positive'
  [[ "${remote}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]*$ ]] || die 'remote must be a safe SSH alias'
  [[ "${v126_image_tag}" =~ ^[a-z0-9][a-z0-9._/-]*:${release_sha}$ ]] ||
    die 'v126-image-tag must end in the exact release SHA'
  require_image_id v126-image-id "${v126_image_id}"
  [[ "${v125_image_tag}" =~ ^[a-z0-9][a-z0-9._/-]*:${V125_SOURCE_SHA}$ ]] ||
    die 'v125-image-tag must end in the exact reviewed V125 source SHA'
  [[ ! -e "${state_dir}" && ! -L "${state_dir}" ]] || die 'state-dir must not already exist'
  [[ -d "${release_worktree}" && ! -L "${release_worktree}" ]] ||
    die 'release-worktree must be a real directory, not a symlink'
  [[ -f "${SCRIPT_PATH}" && ! -L "${SCRIPT_PATH}" ]] || die 'sequencer must be a real file, not a symlink'
  case "${state_dir}/" in
    "${release_worktree}/"*) die 'state-dir must be outside the release worktree' ;;
  esac

  local script_sha
  local created_at
  script_sha="$(hash_file "${SCRIPT_PATH}")"
  created_at="$(utc_now)"
  mkdir -m 0700 "${state_dir}"
  mkdir -m 0700 "${state_dir}/artifacts" "${state_dir}/authorizations" \
    "${state_dir}/intents" "${state_dir}/receipts" "${state_dir}/recovery" "${state_dir}/tmp"

  python3 - "${state_dir}/run.json" \
    "${run_id}" "${release_sha}" "${release_tree}" "${release_parents}" \
    "${main_actions_run_id}" "${release_worktree}" "${remote}" "${staging_path}" \
    "${database_url_file}" "${maintenance_identities_file}" "${v126_image_tag}" \
    "${v126_image_id}" "${v125_image_tag}" "${script_sha}" "${created_at}" <<'PY'
import json
import os
import sys

(
    target,
    run_id,
    release_sha,
    release_tree,
    release_parents,
    main_actions_run_id,
    release_worktree,
    remote,
    staging_path,
    database_url_file,
    maintenance_identities_file,
    v126_image_tag,
    v126_image_id,
    v125_image_tag,
    script_sha256,
    created_at,
) = sys.argv[1:]

document = {
    "created_at": created_at,
    "database_url_file": database_url_file,
    "format_version": 1,
    "main_actions_run_id": int(main_actions_run_id),
    "maintenance_identities_file": maintenance_identities_file,
    "release_parents": release_parents.split(","),
    "release_sha": release_sha,
    "release_tree": release_tree,
    "release_worktree": release_worktree,
    "remote": remote,
    "run_id": run_id,
    "script_sha256": script_sha256,
    "staging_path": staging_path,
    "v125_image_tag": v125_image_tag,
    "v126_image_id": v126_image_id,
    "v126_image_tag": v126_image_tag,
}
payload = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  printf '%s\n' "$(hash_file "${state_dir}/run.json")" > "${state_dir}/run.json.sha256"
  chmod 0400 "${state_dir}/run.json.sha256"
  printf 'V126 run state initialized: %s\n' "${state_dir}"
  printf 'Next executable state: BASELINE_VERIFIED\n'
}

load_state() {
  local requested_state="$1"
  require_cmd python3
  require_absolute_path state-dir "${requested_state}"
  [[ -d "${requested_state}" && ! -L "${requested_state}" ]] || die 'state-dir is unavailable or a symlink'
  [[ -f "${requested_state}/run.json" && ! -L "${requested_state}/run.json" ]] || die 'run manifest is unavailable or a symlink'
  [[ -f "${requested_state}/run.json.sha256" && ! -L "${requested_state}/run.json.sha256" ]] || die 'run manifest checksum is unavailable or a symlink'
  python3 - "${requested_state}" <<'PY' || die 'run-state ownership surface or modes are invalid'
import os
import stat
import sys
root = sys.argv[1]
if stat.S_IMODE(os.stat(root).st_mode) != 0o700:
    raise SystemExit("state directory must be mode 0700")
for name in ("artifacts", "authorizations", "intents", "receipts", "recovery", "tmp"):
    path = os.path.join(root, name)
    if os.path.islink(path) or not os.path.isdir(path) or stat.S_IMODE(os.stat(path).st_mode) != 0o700:
        raise SystemExit(f"invalid state subdirectory: {name}")
for name in ("run.json", "run.json.sha256"):
    path = os.path.join(root, name)
    if stat.S_IMODE(os.stat(path).st_mode) != 0o400:
        raise SystemExit(f"invalid run manifest mode: {name}")
PY
  local expected_hash
  local actual_hash
  expected_hash="$(tr -d '\r\n' < "${requested_state}/run.json.sha256")"
  actual_hash="$(hash_file "${requested_state}/run.json")"
  [[ "${expected_hash}" =~ ^[0-9a-f]{64}$ && "${expected_hash}" == "${actual_hash}" ]] ||
    die 'run manifest checksum mismatch'

  local fields
  fields="$(python3 - "${requested_state}/run.json" <<'PY'
import json
import re
import sys

path = sys.argv[1]
with open(path, "rb") as handle:
    raw = handle.read()
doc = json.loads(raw)
expected = {
    "created_at", "database_url_file", "format_version", "main_actions_run_id",
    "maintenance_identities_file", "release_parents", "release_sha", "release_tree",
    "release_worktree", "remote", "run_id", "script_sha256", "staging_path",
    "v125_image_tag", "v126_image_id", "v126_image_tag",
}
if set(doc) != expected or doc["format_version"] != 1:
    raise SystemExit("invalid run manifest schema")
canonical = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
if raw != canonical:
    raise SystemExit("run manifest is not canonical JSON")
checks = {
    "run_id": r"[a-z0-9][a-z0-9._-]{5,63}",
    "release_sha": r"[0-9a-f]{40}",
    "release_tree": r"[0-9a-f]{40}",
    "script_sha256": r"[0-9a-f]{64}",
    "remote": r"[A-Za-z0-9][A-Za-z0-9._@-]*",
    "v126_image_id": r"sha256:[0-9a-f]{64}",
}
for key, pattern in checks.items():
    if not isinstance(doc[key], str) or not re.fullmatch(pattern, doc[key]):
        raise SystemExit(f"invalid run manifest field: {key}")
if not isinstance(doc["main_actions_run_id"], int) or doc["main_actions_run_id"] <= 0:
    raise SystemExit("invalid main Actions run id")
if not isinstance(doc["release_parents"], list) or len(doc["release_parents"]) not in (1, 2):
    raise SystemExit("invalid release parents")
if any(not re.fullmatch(r"[0-9a-f]{40}", value) for value in doc["release_parents"]):
    raise SystemExit("invalid release parent")
for key in ("release_worktree", "staging_path", "database_url_file", "maintenance_identities_file"):
    value = doc[key]
    if not isinstance(value, str) or not re.fullmatch(r"/[A-Za-z0-9._/+:-]+", value) or value == "/":
        raise SystemExit(f"invalid absolute path: {key}")
    if any(component in (".", "..") for component in value.split("/")):
        raise SystemExit(f"dot path component rejected: {key}")
for key in ("v125_image_tag", "v126_image_tag"):
    if not isinstance(doc[key], str) or not re.fullmatch(r"[a-z0-9][a-z0-9._/-]*:[0-9a-f]{40}", doc[key]):
        raise SystemExit(f"invalid image tag: {key}")
if any("\t" in str(value) or "\n" in str(value) for value in doc.values()):
    raise SystemExit("manifest values must not contain tabs or newlines")

ordered = [
    "run_id", "release_sha", "release_tree", "release_parents", "main_actions_run_id",
    "release_worktree", "remote", "staging_path", "database_url_file",
    "maintenance_identities_file", "v126_image_tag", "v126_image_id", "v125_image_tag",
    "script_sha256",
]
for key in ordered:
    value = doc[key]
    if isinstance(value, list):
        value = ",".join(value)
    print(f"{key}\t{value}")
PY
)" || die 'run manifest validation failed'

  STATE_DIR="${requested_state}"
  while IFS=$'\t' read -r key value; do
    case "${key}" in
      run_id) RUN_ID="${value}" ;;
      release_sha) RELEASE_SHA="${value}" ;;
      release_tree) RELEASE_TREE="${value}" ;;
      release_parents) RELEASE_PARENTS="${value}" ;;
      main_actions_run_id) MAIN_ACTIONS_RUN_ID="${value}" ;;
      release_worktree) RELEASE_WORKTREE="${value}" ;;
      remote) REMOTE="${value}" ;;
      staging_path) STAGING_PATH="${value}" ;;
      database_url_file) DATABASE_URL_FILE="${value}" ;;
      maintenance_identities_file) MAINTENANCE_IDENTITIES_FILE="${value}" ;;
      v126_image_tag) V126_IMAGE_TAG="${value}" ;;
      v126_image_id) V126_IMAGE_ID="${value}" ;;
      v125_image_tag) V125_IMAGE_TAG="${value}" ;;
      script_sha256) SCRIPT_SHA256="${value}" ;;
      *) die "unexpected validated manifest field: ${key}" ;;
    esac
  done <<< "${fields}"
  [[ "${SCRIPT_SHA256}" == "$(hash_file "${SCRIPT_PATH}")" ]] || die 'sequencer identity changed after run initialization'
}

receipt_path() {
  printf '%s/receipts/%02d-%s.receipt.json\n' "${STATE_DIR}" "$(stage_index "$1")" "$1"
}

intent_path() {
  printf '%s/intents/%02d-%s.intent.json\n' "${STATE_DIR}" "$(stage_index "$1")" "$1"
}

authorization_path() {
  printf '%s/authorizations/GATE_%s.authorization.json\n' "${STATE_DIR}" "$1"
}

verify_receipt() {
  local stage="$1"
  local target
  local expected_stage
  local -a expected_artifact_specs=()
  target="$(receipt_path "${stage}")"
  for expected_stage in "${V126_STAGES[@]}"; do
    expected_artifact_specs+=("$(stage_expected_artifacts "${expected_stage}")")
  done
  python3 - "${STATE_DIR}/run.json" "${STATE_DIR}/receipts" "${STATE_DIR}/authorizations" \
    "${STATE_DIR}/artifacts" "${stage}" "${SCRIPT_SHA256}" "${GATE_A_TOKEN}" \
    "${GATE_B_TOKEN}" "${GATE_C_TOKEN}" "${expected_artifact_specs[@]}" <<'PY'
import hashlib
import json
import os
import re
import stat
import sys

(
    manifest_path, receipts_dir, auth_dir, artifacts_dir, requested, current_script_sha,
    token_a, token_b, token_c, *expected_artifact_specs,
) = sys.argv[1:]
stages = [
    "BASELINE_VERIFIED", "PRE_DRAIN_BACKUP_REHEARSED",
    "CADDY_CANDIDATE_INSTALLED_AND_RELOADED", "PUBLIC_DRAIN_ACTIVE",
    "V125_BACKEND_STOPPED", "ZERO_WRITER_GATE_PASSED", "QUIESCED_BACKUP_REHEARSED",
    "FINAL_V125_PREFLIGHT_PASSED", "V126_MAINTENANCE_CONFIG_PREPARED",
    "V126_IMAGE_TRANSFERRED_AND_VERIFIED", "V126_BACKEND_STARTED",
    "V126_SCHEMA_RUNTIME_GATE_PASSED", "MANUAL_SMOKE_AUTHORIZED", "MANUAL_SMOKE_PASSED",
    "PUBLIC_DRAIN_REACTIVATED", "V126_BACKEND_STOPPED_FOR_OFF_TRANSITION",
    "MAINTENANCE_OFF_CONFIG_VERIFIED", "FINAL_V126_BACKEND_STARTED",
    "ORDINARY_CADDY_RESTORED", "FINAL_PUBLIC_GATES_PASSED",
]
timestamp_pattern = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
if requested not in stages:
    raise SystemExit("unknown requested stage")
if len(expected_artifact_specs) != len(stages):
    raise SystemExit("stage artifact contract is incomplete")
expected_artifacts = {}
for stage, spec in zip(stages, expected_artifact_specs):
    names = spec.split(",")
    if not names or any(not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", name) for name in names):
        raise SystemExit(f"invalid expected artifact contract: {stage}")
    if len(names) != len(set(names)) or names != sorted(names):
        raise SystemExit(f"noncanonical expected artifact contract: {stage}")
    expected_artifacts[stage] = set(names) | {"operation-log"}
with open(manifest_path, "rb") as handle:
    manifest = json.load(handle)
if manifest["script_sha256"] != current_script_sha:
    raise SystemExit("script identity mismatch")

def read_canonical(path, checksum_path):
    if os.path.islink(path) or os.path.islink(checksum_path):
        raise SystemExit(f"symlink rejected: {path}")
    if stat.S_IMODE(os.stat(path).st_mode) != 0o400:
        raise SystemExit(f"receipt mode must be 0400: {path}")
    if stat.S_IMODE(os.stat(checksum_path).st_mode) != 0o400:
        raise SystemExit(f"checksum mode must be 0400: {checksum_path}")
    with open(path, "rb") as handle:
        raw = handle.read()
    with open(checksum_path, "rt", encoding="ascii") as handle:
        expected_hash = handle.read().strip()
    actual_hash = hashlib.sha256(raw).hexdigest()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_hash) or expected_hash != actual_hash:
        raise SystemExit(f"receipt checksum mismatch: {path}")
    doc = json.loads(raw)
    if raw != (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode():
        raise SystemExit(f"noncanonical receipt: {path}")
    return doc, actual_hash

auth_cache = {}
def verify_auth(gate, anchor_hash):
    if gate in auth_cache:
        doc, digest = auth_cache[gate]
    else:
        path = os.path.join(auth_dir, f"GATE_{gate}.authorization.json")
        doc, digest = read_canonical(path, path + ".sha256")
        expected_keys = {
            "anchor_receipt_sha256", "anchor_stage", "authorized_at", "format_version", "gate",
            "release_sha", "result_category", "run_id", "script_sha256", "token_sha256",
        }
        if set(doc) != expected_keys or doc["format_version"] != 1:
            raise SystemExit(f"invalid authorization schema for gate {gate}")
        if not isinstance(doc["authorized_at"], str) or not timestamp_pattern.fullmatch(doc["authorized_at"]):
            raise SystemExit(f"invalid authorization timestamp for gate {gate}")
        expected_anchor = {"A": stages[0], "B": stages[11], "C": stages[13]}[gate]
        expected_token = {"A": token_a, "B": token_b, "C": token_c}[gate]
        if doc != {
            **doc,
            "anchor_stage": expected_anchor,
            "gate": gate,
            "release_sha": manifest["release_sha"],
            "result_category": "AUTHORIZED",
            "run_id": manifest["run_id"],
            "script_sha256": current_script_sha,
            "token_sha256": hashlib.sha256(expected_token.encode()).hexdigest(),
        }:
            raise SystemExit(f"authorization identity mismatch for gate {gate}")
        auth_cache[gate] = (doc, digest)
    if doc["anchor_receipt_sha256"] != anchor_hash:
        raise SystemExit(f"authorization anchor mismatch for gate {gate}")
    return digest

previous_hash = "NONE"
anchor_hashes = {}
for index, stage in enumerate(stages[: stages.index(requested) + 1], start=1):
    path = os.path.join(receipts_dir, f"{index:02d}-{stage}.receipt.json")
    doc, digest = read_canonical(path, path + ".sha256")
    expected_keys = {
        "artifacts", "authorization_gate", "authorization_receipt_sha256", "completed_at",
        "format_version", "intent_sha256", "predecessor_receipt_sha256", "predecessor_stage",
        "release_sha", "result_category", "run_id", "script_sha256", "stage",
    }
    if set(doc) != expected_keys or doc["format_version"] != 1:
        raise SystemExit(f"invalid stage receipt schema: {stage}")
    if not isinstance(doc["completed_at"], str) or not timestamp_pattern.fullmatch(doc["completed_at"]):
        raise SystemExit(f"invalid stage completion timestamp: {stage}")
    predecessor = "NONE" if index == 1 else stages[index - 2]
    gate = "NONE" if index == 1 else ("A" if index <= 12 else ("B" if index <= 14 else "C"))
    expected_auth_hash = "NONE"
    if gate != "NONE":
        anchor_stage = {"A": stages[0], "B": stages[11], "C": stages[13]}[gate]
        if anchor_stage not in anchor_hashes:
            raise SystemExit(f"authorization anchor is unavailable for gate {gate}")
        expected_auth_hash = verify_auth(gate, anchor_hashes[anchor_stage])
    fixed = {
        "authorization_gate": gate,
        "authorization_receipt_sha256": expected_auth_hash,
        "format_version": 1,
        "predecessor_receipt_sha256": previous_hash,
        "predecessor_stage": predecessor,
        "release_sha": manifest["release_sha"],
        "result_category": "PASS",
        "run_id": manifest["run_id"],
        "script_sha256": current_script_sha,
        "stage": stage,
    }
    for key, value in fixed.items():
        if doc.get(key) != value:
            raise SystemExit(f"stage receipt mismatch: {stage} field={key}")
    artifacts = doc["artifacts"]
    if not isinstance(artifacts, list) or not artifacts:
        raise SystemExit(f"stage receipt has no artifacts: {stage}")
    if artifacts != sorted(artifacts, key=lambda item: item.get("name", "")):
        raise SystemExit(f"stage receipt artifacts are not canonically ordered: {stage}")
    names = set()
    artifact_hashes = {}
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise SystemExit(f"invalid artifact schema: {stage}")
        if set(artifact) != {"name", "sha256"}:
            raise SystemExit(f"invalid artifact schema: {stage}")
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", artifact["name"]):
            raise SystemExit(f"invalid artifact name: {stage}")
        if not re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]):
            raise SystemExit(f"invalid artifact hash: {stage}")
        if artifact["name"] in names:
            raise SystemExit(f"duplicate artifact name: {stage}")
        names.add(artifact["name"])
        artifact_hashes[artifact["name"]] = artifact["sha256"]
    if names != expected_artifacts[stage]:
        missing = sorted(expected_artifacts[stage] - names)
        extra = sorted(names - expected_artifacts[stage])
        raise SystemExit(f"stage artifact set mismatch: {stage} missing={missing} extra={extra}")
    operation_path = os.path.join(artifacts_dir, f"{index}-{stage}.operation.log")
    if os.path.islink(operation_path) or not os.path.isfile(operation_path):
        raise SystemExit(f"operation log is unavailable or a symlink: {stage}")
    operation_stat = os.stat(operation_path)
    if stat.S_IMODE(operation_stat.st_mode) != 0o400 or operation_stat.st_uid != os.getuid():
        raise SystemExit(f"operation log mode or ownership mismatch: {stage}")
    with open(operation_path, "rb") as handle:
        operation_raw = handle.read()
    operation_hash = hashlib.sha256(operation_raw).hexdigest()
    if artifact_hashes["operation-log"] != operation_hash:
        raise SystemExit(f"operation log hash mismatch: {stage}")
    logged_artifacts = {}
    for raw_line in operation_raw.splitlines():
        if not raw_line.startswith(b"ARTIFACT"):
            continue
        match = re.fullmatch(rb"ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t([0-9a-f]{64})", raw_line)
        if match is None:
            raise SystemExit(f"malformed ARTIFACT line in operation log: {stage}")
        name = match.group(1).decode("ascii")
        digest_value = match.group(2).decode("ascii")
        if name == "operation-log" or name in logged_artifacts:
            raise SystemExit(f"duplicate or reserved ARTIFACT line in operation log: {stage}")
        logged_artifacts[name] = digest_value
    expected_logged = expected_artifacts[stage] - {"operation-log"}
    if set(logged_artifacts) != expected_logged:
        raise SystemExit(f"operation log ARTIFACT set mismatch: {stage}")
    for name in expected_logged:
        if artifact_hashes[name] != logged_artifacts[name]:
            raise SystemExit(f"operation log ARTIFACT hash mismatch: {stage} name={name}")
    intent_path = os.path.join(os.path.dirname(receipts_dir), "intents", f"{index:02d}-{stage}.intent.json")
    intent, intent_hash = read_canonical(intent_path, intent_path + ".sha256")
    expected_intent_keys = {
        "authorization_gate", "authorization_receipt_sha256", "format_version", "intent_at",
        "kind", "predecessor_receipt_sha256", "predecessor_stage", "release_sha", "run_id",
        "script_sha256", "stage",
    }
    if set(intent) != expected_intent_keys or intent["format_version"] != 1 or intent["kind"] != "STAGE_INTENT":
        raise SystemExit(f"invalid stage intent schema: {stage}")
    if not isinstance(intent["intent_at"], str) or not timestamp_pattern.fullmatch(intent["intent_at"]):
        raise SystemExit(f"invalid stage intent timestamp: {stage}")
    for key in (
        "authorization_gate", "authorization_receipt_sha256", "predecessor_receipt_sha256",
        "predecessor_stage", "release_sha", "run_id", "script_sha256", "stage",
    ):
        expected_value = fixed[key]
        if intent.get(key) != expected_value:
            raise SystemExit(f"stage intent mismatch: {stage} field={key}")
    if doc["intent_sha256"] != intent_hash:
        raise SystemExit(f"stage receipt intent hash mismatch: {stage}")
    previous_hash = digest
    anchor_hashes[stage] = digest
print(previous_hash)
PY
}

receipt_artifact_hash() {
  local stage="$1"
  local name="$2"
  verify_receipt "${stage}" >/dev/null
  python3 - "$(receipt_path "${stage}")" "${name}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    doc = json.load(handle)
matches = [item["sha256"] for item in doc["artifacts"] if item["name"] == sys.argv[2]]
if len(matches) != 1:
    raise SystemExit("required receipt artifact is absent or duplicated")
print(matches[0])
PY
}

write_authorization() {
  local gate="$1"
  local supplied_token="$2"
  local expected_token
  local anchor
  local anchor_hash
  local target
  expected_token="$(gate_token "${gate}")"
  [[ "${supplied_token}" == "${expected_token}" ]] || die "authorization token mismatch for Gate ${gate}"
  anchor="$(gate_anchor_stage "${gate}")"
  anchor_hash="$(verify_receipt "${anchor}")" || die "Gate ${gate} anchor receipt is invalid"
  target="$(authorization_path "${gate}")"
  [[ ! -e "${target}" && ! -L "${target}" ]] || die "Gate ${gate} authorization already exists"
  [[ ! -e "${target}.sha256" && ! -L "${target}.sha256" ]] || die "Gate ${gate} authorization checksum already exists"
  python3 - "${target}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${gate}" \
    "${anchor}" "${anchor_hash}" "$(hash_text "${expected_token}")" "$(utc_now)" <<'PY'
import json
import os
import sys
target, run_id, release_sha, script_sha, gate, anchor, anchor_hash, token_hash, timestamp = sys.argv[1:]
doc = {
    "anchor_receipt_sha256": anchor_hash,
    "anchor_stage": anchor,
    "authorized_at": timestamp,
    "format_version": 1,
    "gate": gate,
    "release_sha": release_sha,
    "result_category": "AUTHORIZED",
    "run_id": run_id,
    "script_sha256": script_sha,
    "token_sha256": token_hash,
}
payload = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  printf '%s\n' "$(hash_file "${target}")" > "${target}.sha256"
  chmod 0400 "${target}.sha256"
  printf 'Gate %s authorization recorded for run %s.\n' "${gate}" "${RUN_ID}"
}

authorize_command() {
  local state_dir=''
  local gate=''
  local authorization=''
  shift
  while (( $# > 0 )); do
    case "$1" in
      --state-dir) state_dir="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --gate) gate="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --authorization) authorization="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      *) die "unknown authorize option: $1" ;;
    esac
  done
  [[ "${gate}" == A || "${gate}" == B || "${gate}" == C ]] || die 'gate must be A, B, or C'
  load_state "${state_dir}"
  acquire_state_lock
  install_state_lock_traps
  [[ ! -e "${STATE_DIR}/run-terminal.json" && ! -L "${STATE_DIR}/run-terminal.json" ]] ||
    die 'run is terminal and cannot accept authorization'
  require_no_recovery_intent
  write_authorization "${gate}" "${authorization}"
  release_state_lock
  clear_state_lock_traps
}

authorization_hash_for_stage() {
  local stage="$1"
  local gate
  local path
  gate="$(stage_gate "${stage}")"
  if [[ "${gate}" == NONE ]]; then
    printf 'NONE\n'
    return 0
  fi
  path="$(authorization_path "${gate}")"
  [[ -f "${path}" && -f "${path}.sha256" ]] || die "Gate ${gate} authorization is required"
  local anchor
  anchor="$(gate_anchor_stage "${gate}")"
  verify_receipt "${anchor}" >/dev/null || die "Gate ${gate} anchor receipt is invalid"
  python3 - "${STATE_DIR}/run.json" "${path}" "${path}.sha256" "${gate}" \
    "$(verify_receipt "${anchor}")" "$(hash_text "$(gate_token "${gate}")")" <<'PY'
import hashlib
import json
import os
import re
import stat
import sys
manifest_path, path, checksum_path, gate, anchor_hash, token_hash = sys.argv[1:]
if os.path.islink(path) or os.path.islink(checksum_path):
    raise SystemExit("authorization symlink rejected")
if stat.S_IMODE(os.stat(path).st_mode) != 0o400 or stat.S_IMODE(os.stat(checksum_path).st_mode) != 0o400:
    raise SystemExit("authorization files must be mode 0400")
raw = open(path, "rb").read()
expected = open(checksum_path, "rt", encoding="ascii").read().strip()
digest = hashlib.sha256(raw).hexdigest()
if expected != digest:
    raise SystemExit("authorization checksum mismatch")
doc = json.loads(raw)
if raw != (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode():
    raise SystemExit("authorization is not canonical JSON")
manifest = json.load(open(manifest_path, "rt", encoding="utf-8"))
anchor_stage = {"A": "BASELINE_VERIFIED", "B": "V126_SCHEMA_RUNTIME_GATE_PASSED", "C": "MANUAL_SMOKE_PASSED"}[gate]
required = {
    "anchor_receipt_sha256": anchor_hash,
    "anchor_stage": anchor_stage,
    "format_version": 1,
    "gate": gate,
    "release_sha": manifest["release_sha"],
    "result_category": "AUTHORIZED",
    "run_id": manifest["run_id"],
    "script_sha256": manifest["script_sha256"],
    "token_sha256": token_hash,
}
for key, value in required.items():
    if doc.get(key) != value:
        raise SystemExit(f"authorization mismatch: {key}")
if set(doc) != set(required) | {"authorized_at"}:
    raise SystemExit("authorization schema mismatch")
if not isinstance(doc["authorized_at"], str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", doc["authorized_at"]):
    raise SystemExit("authorization timestamp mismatch")
print(digest)
PY
}

write_stage_receipt() {
  local stage="$1"
  local artifacts_file="$2"
  local intent_hash="$3"
  local predecessor
  local predecessor_hash
  local gate
  local authorization_hash
  local target
  predecessor="$(stage_predecessor "${stage}")"
  if [[ "${predecessor}" == NONE ]]; then
    predecessor_hash='NONE'
  else
    predecessor_hash="$(verify_receipt "${predecessor}")" || die "predecessor receipt is invalid: ${predecessor}"
  fi
  gate="$(stage_gate "${stage}")"
  authorization_hash="$(authorization_hash_for_stage "${stage}")"
  target="$(receipt_path "${stage}")"
  [[ ! -e "${target}" && ! -L "${target}" ]] || die "stage receipt already exists: ${stage}"
  [[ ! -e "${target}.sha256" && ! -L "${target}.sha256" ]] || die "stage receipt checksum already exists: ${stage}"
  python3 - "${target}" "${artifacts_file}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" \
    "${stage}" "${predecessor}" "${predecessor_hash}" "${gate}" "${authorization_hash}" \
    "${intent_hash}" "$(utc_now)" "$(stage_expected_artifacts "${stage}")" <<'PY'
import json
import os
import re
import sys
(
    target, artifacts_path, run_id, release_sha, script_sha, stage, predecessor,
    predecessor_hash, gate, authorization_hash, intent_hash, timestamp, expected_spec,
) = sys.argv[1:]
artifacts = []
names = set()
with open(artifacts_path, "rt", encoding="ascii") as handle:
    for raw_line in handle:
        line = raw_line.rstrip("\n")
        parts = line.split("\t")
        if len(parts) != 2:
            raise SystemExit("invalid artifact manifest line")
        name, digest = parts
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", name):
            raise SystemExit(f"invalid artifact name: {name}")
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise SystemExit(f"invalid artifact hash: {name}")
        if name in names:
            raise SystemExit(f"duplicate artifact name: {name}")
        names.add(name)
        artifacts.append({"name": name, "sha256": digest})
if not artifacts:
    raise SystemExit("a stage receipt requires at least one artifact")
expected_names = set(expected_spec.split(",")) | {"operation-log"}
if names != expected_names:
    raise SystemExit(
        f"stage artifact set mismatch: missing={sorted(expected_names - names)} "
        f"extra={sorted(names - expected_names)}"
    )
artifacts.sort(key=lambda item: item["name"])
doc = {
    "artifacts": artifacts,
    "authorization_gate": gate,
    "authorization_receipt_sha256": authorization_hash,
    "completed_at": timestamp,
    "format_version": 1,
    "intent_sha256": intent_hash,
    "predecessor_receipt_sha256": predecessor_hash,
    "predecessor_stage": predecessor,
    "release_sha": release_sha,
    "result_category": "PASS",
    "run_id": run_id,
    "script_sha256": script_sha,
    "stage": stage,
}
payload = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  printf '%s\n' "$(hash_file "${target}")" > "${target}.sha256"
  chmod 0400 "${target}.sha256"
}

write_stage_intent() {
  local stage="$1"
  local predecessor
  local predecessor_hash
  local gate
  local authorization_hash
  local target
  predecessor="$(stage_predecessor "${stage}")"
  if [[ "${predecessor}" == NONE ]]; then
    predecessor_hash='NONE'
  else
    predecessor_hash="$(verify_receipt "${predecessor}")" || die "predecessor receipt is invalid: ${predecessor}"
  fi
  gate="$(stage_gate "${stage}")"
  authorization_hash="$(authorization_hash_for_stage "${stage}")"
  target="$(intent_path "${stage}")"
  [[ ! -e "${target}" && ! -L "${target}" ]] ||
    die "stage intent already exists; retry is forbidden and reconciliation/recovery is required: ${stage}"
  [[ ! -e "${target}.sha256" && ! -L "${target}.sha256" ]] ||
    die "stage intent checksum already exists; reconciliation/recovery is required: ${stage}"
  python3 - "${target}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" "${stage}" \
    "${predecessor}" "${predecessor_hash}" "${gate}" "${authorization_hash}" "$(utc_now)" <<'PY'
import json
import os
import sys
target, run_id, release_sha, script_sha, stage, predecessor, predecessor_hash, gate, auth_hash, timestamp = sys.argv[1:]
doc = {
    "authorization_gate": gate,
    "authorization_receipt_sha256": auth_hash,
    "format_version": 1,
    "intent_at": timestamp,
    "kind": "STAGE_INTENT",
    "predecessor_receipt_sha256": predecessor_hash,
    "predecessor_stage": predecessor,
    "release_sha": release_sha,
    "run_id": run_id,
    "script_sha256": script_sha,
    "stage": stage,
}
payload = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  printf '%s\n' "$(hash_file "${target}")" > "${target}.sha256"
  chmod 0400 "${target}.sha256"
  hash_file "${target}"
}

try_acquire_state_lock() {
  local lock_dir="${STATE_DIR}/.exclusive-lock"
  mkdir -m 0700 "${lock_dir}" 2>/dev/null || return 1
  if ! mkdir -m 0700 "${lock_dir}/children"; then
    rmdir "${lock_dir}" 2>/dev/null || true
    return 1
  fi
  LOCK_OWNER_PID="$$"
  if ! python3 - "${lock_dir}/pid" "${LOCK_OWNER_PID}" <<'PY'
import os
import sys
path, pid = sys.argv[1:]
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wt", encoding="ascii") as handle:
    handle.write(pid + "\n")
PY
  then
    rmdir "${lock_dir}/children" 2>/dev/null || true
    rmdir "${lock_dir}" 2>/dev/null || true
    LOCK_OWNER_PID=''
    return 1
  fi
}

acquire_state_lock() {
  try_acquire_state_lock ||
    die 'run state is locked or requires explicit interrupted-run reconciliation'
}

validate_state_lock_surface() {
  local lock_dir="$1"
  python3 - "${lock_dir}" <<'PY'
import os
import re
import stat
import sys

root = sys.argv[1]
if os.path.islink(root) or not os.path.isdir(root):
    raise SystemExit("state lock is not a real directory")
root_stat = os.stat(root)
if stat.S_IMODE(root_stat.st_mode) != 0o700 or root_stat.st_uid != os.getuid():
    raise SystemExit("state lock mode or ownership mismatch")
entries = set(os.listdir(root))
if entries not in ({"children", "pid"}, {"children", "pid", "takeover.pid"}):
    raise SystemExit("state lock has an unexpected surface")
pid_path = os.path.join(root, "pid")
children = os.path.join(root, "children")
if os.path.islink(pid_path) or not os.path.isfile(pid_path):
    raise SystemExit("state lock owner PID is invalid")
if os.path.islink(children) or not os.path.isdir(children):
    raise SystemExit("state lock children surface is invalid")
for path, expected_mode in ((pid_path, 0o400), (children, 0o700)):
    info = os.stat(path)
    if stat.S_IMODE(info.st_mode) != expected_mode or info.st_uid != os.getuid():
        raise SystemExit("state lock metadata mode or ownership mismatch")
owner = open(pid_path, "rt", encoding="ascii").read().strip()
if not re.fullmatch(r"[1-9][0-9]*", owner):
    raise SystemExit("state lock owner PID is malformed")
takeover_path = os.path.join(root, "takeover.pid")
if "takeover.pid" in entries:
    if os.path.islink(takeover_path) or not os.path.isfile(takeover_path):
        raise SystemExit("state lock takeover PID is invalid")
    info = os.stat(takeover_path)
    if stat.S_IMODE(info.st_mode) != 0o400 or info.st_uid != os.getuid():
        raise SystemExit("state lock takeover metadata mode or ownership mismatch")
    takeover = open(takeover_path, "rt", encoding="ascii").read().strip()
    if not re.fullmatch(r"[1-9][0-9]*", takeover):
        raise SystemExit("state lock takeover PID is malformed")
for name in os.listdir(children):
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,31}\.(?:pending|pid)", name):
        raise SystemExit("state lock child metadata name is invalid")
    path = os.path.join(children, name)
    if os.path.islink(path) or not os.path.isfile(path):
        raise SystemExit("state lock child metadata is invalid")
    info = os.stat(path)
    if stat.S_IMODE(info.st_mode) != 0o400 or info.st_uid != os.getuid():
        raise SystemExit("state lock child metadata mode or ownership mismatch")
    value = open(path, "rt", encoding="ascii").read().strip()
    if name.endswith(".pending"):
        if value != "PENDING":
            raise SystemExit("state lock pending-child marker is malformed")
    elif not re.fullmatch(r"[1-9][0-9]*", value):
        raise SystemExit("state lock child PID is malformed")
print(owner)
PY
}

lock_child_pending() {
  local token="$1"
  local path="${STATE_DIR}/.exclusive-lock/children/${token}.pending"
  [[ "${token}" =~ ^[a-z][a-z0-9-]{0,31}$ ]] || die 'invalid state-lock child token'
  python3 - "${path}" <<'PY'
import os
import sys
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wt", encoding="ascii") as handle:
    handle.write("PENDING\n")
PY
}

lock_child_started() {
  local token="$1"
  local pid="$2"
  local root="${STATE_DIR}/.exclusive-lock/children"
  [[ "${token}" =~ ^[a-z][a-z0-9-]{0,31}$ && "${pid}" =~ ^[1-9][0-9]*$ ]] ||
    die 'invalid state-lock child binding'
  python3 - "${root}/${token}.pending" "${root}/${token}.pid" "${pid}" <<'PY'
import os
import stat
import sys
pending, target, pid = sys.argv[1:]
if os.path.islink(pending) or not os.path.isfile(pending):
    raise SystemExit("pending child marker is unavailable")
if stat.S_IMODE(os.stat(pending).st_mode) != 0o400:
    raise SystemExit("pending child marker mode mismatch")
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wt", encoding="ascii") as handle:
    handle.write(pid + "\n")
os.unlink(pending)
PY
}

tracked_child_gate_path() {
  local token="$1"
  printf '%s/tmp/tracked-child-%s-%s.release\n' "${STATE_DIR}" "${token}" "$$"
}

tracked_child_wait_for_release() {
  local gate_path="$1"
  local self_path="${gate_path}.self"
  [[ ! -e "${self_path}" && ! -L "${self_path}" ]] || return 75
  /bin/sh -c 'printf "%s\n" "$PPID"' > "${self_path}"
  chmod 0600 "${self_path}"
  local self_pid
  self_pid="$(tr -d '\r\n' < "${self_path}")"
  rm -f -- "${self_path}"
  [[ "${self_pid}" =~ ^[1-9][0-9]*$ ]] || return 75
  local attempt
  local release=''
  for attempt in $(seq 1 50); do
    if [[ -f "${gate_path}" && ! -L "${gate_path}" ]]; then
      release="$(tr -d '\r\n' < "${gate_path}")"
      if [[ "${release}" == "BOUND:${self_pid}" ]]; then
        return 0
      fi
    fi
    sleep 0.1
  done
  return 75
}

release_tracked_child() {
  local gate_path="$1"
  local child_pid="$2"
  [[ "${child_pid}" =~ ^[1-9][0-9]*$ ]] || die 'invalid tracked-child release PID'
  python3 - "${gate_path}" "${child_pid}" <<'PY'
import os
import sys
path, pid = sys.argv[1:]
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wt", encoding="ascii") as handle:
    handle.write(f"BOUND:{pid}\n")
PY
}

finish_tracked_child() {
  local token="$1"
  local child_pid="$2"
  local gate_path="$3"
  lock_child_finished "${token}" "${child_pid}" ||
    die "tracked process metadata could not be reconciled: ${token}"
  [[ -f "${gate_path}" && ! -L "${gate_path}" ]] ||
    die "tracked process release gate is unavailable: ${token}"
  [[ "$(tr -d '\r\n' < "${gate_path}")" == "BOUND:${child_pid}" ]] ||
    die "tracked process release gate identity mismatch: ${token}"
  rm -f -- "${gate_path}"
}

abort_unreleased_tracked_child() {
  local child_pid="$1"
  local gate_path="$2"
  if [[ "${child_pid}" =~ ^[1-9][0-9]*$ ]]; then
    kill -TERM "${child_pid}" 2>/dev/null || true
    wait "${child_pid}" 2>/dev/null || true
  fi
  rm -f -- "${gate_path}" "${gate_path}.self" 2>/dev/null || true
}

lock_child_finished() {
  local token="$1"
  local pid="$2"
  local path="${STATE_DIR}/.exclusive-lock/children/${token}.pid"
  [[ -f "${path}" && ! -L "${path}" && "$(tr -d '\r\n' < "${path}")" == "${pid}" ]] ||
    return 1
  rm -f -- "${path}"
}

state_lock_children_are_dead() {
  local lock_dir="$1"
  python3 - "${lock_dir}/children" <<'PY'
import os
import sys
root = sys.argv[1]
for name in os.listdir(root):
    path = os.path.join(root, name)
    if name.endswith(".pending"):
        raise SystemExit("a child launch is pending and its liveness is ambiguous")
    pid = int(open(path, "rt", encoding="ascii").read().strip())
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        continue
    except PermissionError:
        raise SystemExit("child process liveness is not observable")
    raise SystemExit("a tracked child process is still alive")
PY
}

remove_dead_lock_surface() {
  local lock_dir="$1"
  python3 - "${lock_dir}" <<'PY'
import os
import sys
root = sys.argv[1]
children = os.path.join(root, "children")
for name in os.listdir(children):
    os.unlink(os.path.join(children, name))
os.rmdir(children)
os.unlink(os.path.join(root, "pid"))
os.rmdir(root)
PY
}

release_state_lock() {
  local lock_dir="${STATE_DIR}/.exclusive-lock"
  [[ -d "${lock_dir}" && ! -L "${lock_dir}" ]] || return 0
  local owner
  owner="$(validate_state_lock_surface "${lock_dir}" 2>/dev/null)" || return 1
  [[ -n "${LOCK_OWNER_PID}" && "${owner}" == "${LOCK_OWNER_PID}" && "$$" == "${LOCK_OWNER_PID}" ]] ||
    return 1
  state_lock_children_are_dead "${lock_dir}" >/dev/null 2>&1 || return 1
  remove_dead_lock_surface "${lock_dir}"
  LOCK_OWNER_PID=''
}

terminate_tracked_lock_children() {
  local children="${STATE_DIR}/.exclusive-lock/children"
  [[ -d "${children}" && ! -L "${children}" ]] || return 0
  local path pid
  for path in "${children}"/*.pid; do
    [[ -f "${path}" && ! -L "${path}" ]] || continue
    pid="$(tr -d '\r\n' < "${path}")"
    [[ "${pid}" =~ ^[1-9][0-9]*$ ]] || continue
    kill -TERM "${pid}" 2>/dev/null || true
  done
  local attempt
  for attempt in $(seq 1 20); do
    state_lock_children_are_dead "${STATE_DIR}/.exclusive-lock" >/dev/null 2>&1 && return 0
    sleep 0.1
  done
  for path in "${children}"/*.pid; do
    [[ -f "${path}" && ! -L "${path}" ]] || continue
    pid="$(tr -d '\r\n' < "${path}")"
    [[ "${pid}" =~ ^[1-9][0-9]*$ ]] || continue
    kill -KILL "${pid}" 2>/dev/null || true
  done
  state_lock_children_are_dead "${STATE_DIR}/.exclusive-lock" >/dev/null 2>&1
}

state_lock_exit_cleanup() {
  release_state_lock >/dev/null 2>&1 || true
}

state_lock_signal_exit() {
  local status="$1"
  trap - EXIT HUP INT TERM
  terminate_tracked_lock_children >/dev/null 2>&1 || true
  release_state_lock >/dev/null 2>&1 || true
  exit "${status}"
}

install_state_lock_traps() {
  trap state_lock_exit_cleanup EXIT
  trap 'state_lock_signal_exit 129' HUP
  trap 'state_lock_signal_exit 130' INT
  trap 'state_lock_signal_exit 143' TERM
}

clear_state_lock_traps() {
  trap - EXIT HUP INT TERM
}

acquire_state_lock_for_recovery() {
  local lock_dir="${STATE_DIR}/.exclusive-lock"
  if try_acquire_state_lock; then
    return 0
  fi
  [[ -d "${lock_dir}" && ! -L "${lock_dir}" ]] || die 'recovery stale-lock target is invalid'
  local owner_pid
  owner_pid="$(validate_state_lock_surface "${lock_dir}")" || die 'recovery refuses unsafe state-lock metadata'
  python3 - "${owner_pid}" <<'PY' || die 'recovery cannot prove that the prior state-lock process is dead'
import os
import sys
pid = int(sys.argv[1])
try:
    os.kill(pid, 0)
except ProcessLookupError:
    pass
except PermissionError:
    raise SystemExit("state-lock owner liveness is not observable")
else:
    raise SystemExit("state-lock owner is still alive")
PY
  state_lock_children_are_dead "${lock_dir}" ||
    die 'recovery cannot prove that all tracked child and remote processes are dead'
  local existing_takeover="${lock_dir}/takeover.pid"
  if [[ -e "${existing_takeover}" || -L "${existing_takeover}" ]]; then
    local takeover_pid
    takeover_pid="$(tr -d '\r\n' < "${existing_takeover}")" || die 'recovery takeover PID is unreadable'
    [[ "${takeover_pid}" =~ ^[1-9][0-9]*$ ]] || die 'recovery takeover PID is malformed'
    python3 - "${takeover_pid}" <<'PY' || die 'another recovery takeover is still active or unobservable'
import os
import sys
pid = int(sys.argv[1])
try:
    os.kill(pid, 0)
except ProcessLookupError:
    pass
except PermissionError:
    raise SystemExit("takeover liveness is not observable")
else:
    raise SystemExit("takeover process is still alive")
PY
    rm -f -- "${existing_takeover}"
  fi
  local takeover_candidate="${STATE_DIR}/.exclusive-lock-takeover-$$"
  [[ ! -e "${takeover_candidate}" && ! -L "${takeover_candidate}" ]] ||
    die 'recovery takeover candidate already exists'
  if ! python3 - "${takeover_candidate}" "${existing_takeover}" "$$" <<'PY'
import os
import sys
candidate, target, pid = sys.argv[1:]
fd = os.open(candidate, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wt", encoding="ascii") as handle:
    handle.write(pid + "\n")
try:
    os.link(candidate, target)
finally:
    os.unlink(candidate)
PY
  then
    die 'another recovery won the atomic stale-lock takeover'
  fi
  owner_pid="$(validate_state_lock_surface "${lock_dir}")" || die 'state lock changed during recovery takeover'
  python3 - "${owner_pid}" <<'PY' || die 'prior state-lock owner revived during recovery takeover'
import os
import sys
try:
    os.kill(int(sys.argv[1]), 0)
except ProcessLookupError:
    pass
except PermissionError:
    raise SystemExit("state-lock owner liveness became unobservable")
else:
    raise SystemExit("state-lock owner is alive")
PY
  state_lock_children_are_dead "${lock_dir}" ||
    die 'tracked child process became live during recovery takeover'
  python3 - "${lock_dir}" "$$" <<'PY' || die 'atomic state-lock ownership replacement failed'
import os
import sys
root, pid = sys.argv[1:]
children = os.path.join(root, "children")
for name in os.listdir(children):
    os.unlink(os.path.join(children, name))
next_owner = os.path.join(root, "pid.next")
fd = os.open(next_owner, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wt", encoding="ascii") as handle:
    handle.write(pid + "\n")
os.replace(next_owner, os.path.join(root, "pid"))
os.unlink(os.path.join(root, "takeover.pid"))
PY
  LOCK_OWNER_PID="$$"
}

require_no_recovery_intent() {
  local recovery_intent=''
  if ! recovery_intent="$(find "${STATE_DIR}/recovery" -maxdepth 1 \
    -name '*.intent.json' -print -quit)"; then
    die 'recovery intent inventory failed; ordinary authorization and stage execution are blocked'
  fi
  [[ -z "${recovery_intent}" ]] ||
    die 'a recovery intent exists; ordinary authorization and stage execution are permanently blocked'
}

record_artifact() {
  local manifest="$1"
  local name="$2"
  local digest="$3"
  [[ "${name}" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || die "invalid artifact name: ${name}"
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "invalid artifact hash: ${name}"
  printf '%s\t%s\n' "${name}" "${digest}" >> "${manifest}"
}

collect_remote_artifacts() {
  local log_file="$1"
  local manifest="$2"
  local kind name digest extra
  while IFS=$'\t' read -r kind name digest extra; do
    if [[ "${kind}" == ARTIFACT ]]; then
      [[ -z "${extra}" ]] || die 'remote artifact output has unexpected fields'
      record_artifact "${manifest}" "${name}" "${digest}"
    fi
  done < "${log_file}"
}

run_tracked_command() {
  local token="$1"
  shift
  local gate_path
  gate_path="$(tracked_child_gate_path "${token}")"
  [[ ! -e "${gate_path}" && ! -L "${gate_path}" ]] || die 'tracked process release gate already exists'
  lock_child_pending "${token}" || die "tracked process pending marker failed: ${token}"
  ( tracked_child_wait_for_release "${gate_path}" || exit $?; exec "$@" ) &
  local launch_status=$?
  local child_pid=$!
  (( launch_status == 0 )) || die "tracked process launch failed: ${token}"
  [[ "${child_pid}" =~ ^[1-9][0-9]*$ ]] || die "tracked process PID is invalid: ${token}"
  if ! lock_child_started "${token}" "${child_pid}"; then
    abort_unreleased_tracked_child "${child_pid}" "${gate_path}"
    die "tracked process durable PID binding failed: ${token}"
  fi
  if ! release_tracked_child "${gate_path}" "${child_pid}"; then
    abort_unreleased_tracked_child "${child_pid}" "${gate_path}"
    die "tracked process release failed: ${token}"
  fi
  local status=0
  if wait "${child_pid}"; then
    status=0
  else
    status=$?
  fi
  finish_tracked_child "${token}" "${child_pid}" "${gate_path}" ||
    die "tracked process finalization failed: ${token}"
  return "${status}"
}

run_tracked_command_with_input() {
  local token="$1"
  local input_path="$2"
  shift 2
  [[ -f "${input_path}" && ! -L "${input_path}" ]] || die 'tracked process input is unavailable or symlinked'
  local gate_path
  gate_path="$(tracked_child_gate_path "${token}")"
  [[ ! -e "${gate_path}" && ! -L "${gate_path}" ]] || die 'tracked process release gate already exists'
  lock_child_pending "${token}" || die "tracked process pending marker failed: ${token}"
  ( tracked_child_wait_for_release "${gate_path}" || exit $?; exec "$@" < "${input_path}" ) &
  local launch_status=$?
  local child_pid=$!
  (( launch_status == 0 )) || die "tracked process launch failed: ${token}"
  [[ "${child_pid}" =~ ^[1-9][0-9]*$ ]] || die "tracked process PID is invalid: ${token}"
  if ! lock_child_started "${token}" "${child_pid}"; then
    abort_unreleased_tracked_child "${child_pid}" "${gate_path}"
    die "tracked process durable PID binding failed: ${token}"
  fi
  if ! release_tracked_child "${gate_path}" "${child_pid}"; then
    abort_unreleased_tracked_child "${child_pid}" "${gate_path}"
    die "tracked process release failed: ${token}"
  fi
  local status=0
  if wait "${child_pid}"; then
    status=0
  else
    status=$?
  fi
  finish_tracked_child "${token}" "${child_pid}" "${gate_path}" ||
    die "tracked process finalization failed: ${token}"
  return "${status}"
}

run_remote() {
  local action="$1"
  shift
  [[ "${ACTIVE_OPERATION_KIND}" == STAGE || "${ACTIVE_OPERATION_KIND}" == RECOVERY ]] ||
    die 'internal remote dispatch lacks an operation kind'
  [[ -n "${ACTIVE_OPERATION_NAME}" && -n "${ACTIVE_PREDECESSOR_STAGE}" &&
    -n "${ACTIVE_PREDECESSOR_HASH}" && -n "${ACTIVE_AUTHORIZATION_GATE}" &&
    -n "${ACTIVE_AUTHORIZATION_HASH}" && "${ACTIVE_INTENT_HASH}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'internal remote dispatch lacks a complete receipt envelope'
  (( $# >= 3 )) || die 'internal remote dispatch requires target identity arguments'
  [[ "$1" == "${STAGING_PATH}" && "$2" == "${RUN_ID}" && "$3" == "${RELEASE_SHA}" ]] ||
    die 'internal remote dispatch target differs from the run manifest'
  local baseline_database_sha='NONE'
  local baseline_identities_sha='NONE'
  local baseline_compose_sha='NONE'
  local baseline_maintenance_sha='NONE'
  local baseline_admission_sha='NONE'
  local baseline_caddy_sha='NONE'
  local baseline_env_sha='NONE'
  if [[ "${ACTIVE_OPERATION_KIND}:${ACTIVE_OPERATION_NAME}:${action}" != \
    STAGE:BASELINE_VERIFIED:baseline ]]; then
    baseline_database_sha="$(receipt_artifact_hash BASELINE_VERIFIED database-url-binding)"
    baseline_identities_sha="$(receipt_artifact_hash BASELINE_VERIFIED maintenance-identities)"
    baseline_compose_sha="$(receipt_artifact_hash BASELINE_VERIFIED remote-compose-source)"
    baseline_maintenance_sha="$(receipt_artifact_hash BASELINE_VERIFIED remote-maintenance-check-source)"
    baseline_admission_sha="$(receipt_artifact_hash BASELINE_VERIFIED remote-admission-source)"
    baseline_caddy_sha="$(receipt_artifact_hash BASELINE_VERIFIED baseline-caddy)"
    baseline_env_sha="$(receipt_artifact_hash BASELINE_VERIFIED baseline-env)"
  fi
  local caddy_original_sha='NONE'
  local caddy_candidate_sha='NONE'
  local caddy_diff_sha='NONE'
  local caddy_activation_sha='NONE'
  local maintenance_smoke_sha='NONE'
  local maintenance_off_sha='NONE'
  local caddy_receipt
  caddy_receipt="$(receipt_path CADDY_CANDIDATE_INSTALLED_AND_RELOADED)"
  if [[ -e "${caddy_receipt}" || -L "${caddy_receipt}" ]]; then
    verify_receipt CADDY_CANDIDATE_INSTALLED_AND_RELOADED >/dev/null ||
      die 'Caddy stage receipt exists but fails exact verification'
    caddy_original_sha="$(receipt_artifact_hash CADDY_CANDIDATE_INSTALLED_AND_RELOADED caddy-original)"
    caddy_candidate_sha="$(receipt_artifact_hash CADDY_CANDIDATE_INSTALLED_AND_RELOADED caddy-candidate)"
    caddy_diff_sha="$(receipt_artifact_hash CADDY_CANDIDATE_INSTALLED_AND_RELOADED caddy-diff)"
    caddy_activation_sha="$(receipt_artifact_hash CADDY_CANDIDATE_INSTALLED_AND_RELOADED caddy-activation)"
  fi
  local caddy_receipt_required=false
  if [[ "${ACTIVE_OPERATION_KIND}" == STAGE ]]; then
    if (( $(stage_index "${ACTIVE_OPERATION_NAME}") > 3 )); then
      caddy_receipt_required=true
    fi
  elif [[ "${ACTIVE_OPERATION_NAME}" == post-v126-stop || \
    "${ACTIVE_OPERATION_NAME}" == verify-full-dr ]]; then
    caddy_receipt_required=true
  elif [[ "${ACTIVE_OPERATION_NAME}" == pre-v126 ]]; then
    if [[ "${ACTIVE_PREDECESSOR_STAGE}" == RECOVERY_POST_V126_STOP ]] || \
      (( $(stage_index "${ACTIVE_PREDECESSOR_STAGE}") >= 3 )); then
      caddy_receipt_required=true
    fi
  fi
  if [[ "${caddy_receipt_required}" == true ]]; then
    local caddy_bound_hash
    for caddy_bound_hash in "${caddy_original_sha}" "${caddy_candidate_sha}" \
      "${caddy_diff_sha}" "${caddy_activation_sha}"; do
      [[ "${caddy_bound_hash}" =~ ^[0-9a-f]{64}$ ]] ||
        die 'operation requires the exact immutable Caddy stage receipt'
    done
  fi
  local maintenance_receipt
  maintenance_receipt="$(receipt_path V126_MAINTENANCE_CONFIG_PREPARED)"
  if [[ -e "${maintenance_receipt}" || -L "${maintenance_receipt}" ]]; then
    maintenance_smoke_sha="$(receipt_artifact_hash \
      V126_MAINTENANCE_CONFIG_PREPARED maintenance-v126_smoke)"
  fi
  maintenance_receipt="$(receipt_path MAINTENANCE_OFF_CONFIG_VERIFIED)"
  if [[ -e "${maintenance_receipt}" || -L "${maintenance_receipt}" ]]; then
    maintenance_off_sha="$(receipt_artifact_hash \
      MAINTENANCE_OFF_CONFIG_VERIFIED maintenance-off)"
  fi
  local stream="${STATE_DIR}/tmp/remote-stream-$$-${action}.sh"
  [[ ! -e "${stream}" && ! -L "${stream}" ]] || die 'internal remote stream already exists'
  {
    cat <<'REMOTE_LOADER'
set -Eeuo pipefail
umask 077
export LC_ALL=C
loader_die() {
  printf 'V126 cutover contract rejected: %s\n' "$*" >&2
  exit 4
}
loader_read() {
  IFS= read -r "$1" || loader_die "truncated internal remote envelope: $1"
}
loader_read magic
loader_read action
loader_read envelope_run_id
loader_read envelope_release_sha
loader_read envelope_staging_path
loader_read envelope_script_sha
loader_read envelope_v126_image_id
loader_read operation_kind
loader_read operation_name
loader_read predecessor_stage
loader_read predecessor_hash
loader_read authorization_gate
loader_read authorization_hash
loader_read intent_hash
loader_read baseline_database_sha
loader_read baseline_identities_sha
loader_read baseline_compose_sha
loader_read baseline_maintenance_sha
loader_read baseline_admission_sha
loader_read baseline_caddy_sha
loader_read baseline_env_sha
loader_read caddy_original_sha
loader_read caddy_candidate_sha
loader_read caddy_diff_sha
loader_read caddy_activation_sha
loader_read maintenance_smoke_sha
loader_read maintenance_off_sha
loader_read raw_arg_count
[[ "${magic}" == V126_INTERNAL_REMOTE_ENVELOPE_V1 ]] || loader_die 'invalid internal remote envelope magic'
[[ "${raw_arg_count}" =~ ^[1-9][0-9]*$ && "${raw_arg_count}" -le 32 ]] || loader_die 'invalid internal remote argument count'
remote_args=()
for ((loader_index = 0; loader_index < raw_arg_count; loader_index++)); do
  loader_read loader_argument
  [[ "${loader_argument}" != *$'\t'* && "${loader_argument}" != *$'\r'* ]] || loader_die 'invalid internal remote argument encoding'
  remote_args+=("${loader_argument}")
done
remote_body_sentinel='V126_REMOTE_BODY_SENTINEL_7f3bf5d9'
remote_body_content="$({ cat || exit $?; printf '%s' "${remote_body_sentinel}"; })" ||
  loader_die 'streamed sequencer body could not be read'
[[ "${remote_body_content}" == *"${remote_body_sentinel}" ]] ||
  loader_die 'streamed sequencer body sentinel is absent'
remote_body_content="${remote_body_content%"${remote_body_sentinel}"}"
[[ "$(printf '%s' "${remote_body_content}" | sha256sum | awk '{print $1}')" == \
  "${envelope_script_sha}" ]] || loader_die 'streamed sequencer identity mismatch'
export V126_INTERNAL_REMOTE_MODE=true
export V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED=V126_INTERNAL_REMOTE_ENVELOPE_V1
export V126_INTERNAL_REMOTE_ACTION="${action}"
export V126_INTERNAL_REMOTE_RUN_ID="${envelope_run_id}"
export V126_INTERNAL_REMOTE_RELEASE_SHA="${envelope_release_sha}"
export V126_INTERNAL_REMOTE_STAGING_PATH="${envelope_staging_path}"
export V126_INTERNAL_REMOTE_SCRIPT_SHA256="${envelope_script_sha}"
export V126_INTERNAL_REMOTE_V126_IMAGE_ID="${envelope_v126_image_id}"
export V126_INTERNAL_REMOTE_OPERATION_KIND="${operation_kind}"
export V126_INTERNAL_REMOTE_OPERATION_NAME="${operation_name}"
export V126_INTERNAL_REMOTE_PREDECESSOR_STAGE="${predecessor_stage}"
export V126_INTERNAL_REMOTE_PREDECESSOR_HASH="${predecessor_hash}"
export V126_INTERNAL_REMOTE_AUTHORIZATION_GATE="${authorization_gate}"
export V126_INTERNAL_REMOTE_AUTHORIZATION_HASH="${authorization_hash}"
export V126_INTERNAL_REMOTE_INTENT_HASH="${intent_hash}"
export V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256="${baseline_database_sha}"
export V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256="${baseline_identities_sha}"
export V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256="${baseline_compose_sha}"
export V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256="${baseline_maintenance_sha}"
export V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256="${baseline_admission_sha}"
export V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="${baseline_caddy_sha}"
export V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="${baseline_env_sha}"
export V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256="${caddy_original_sha}"
export V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256="${caddy_candidate_sha}"
export V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256="${caddy_diff_sha}"
export V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256="${caddy_activation_sha}"
export V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256="${maintenance_smoke_sha}"
export V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256="${maintenance_off_sha}"
source /dev/stdin <<< "${remote_body_content}"
unset remote_body_content
remote_dispatch_enveloped "${action}" "${remote_args[@]}"
loader_status=$?
exit "${loader_status}"
REMOTE_LOADER
    printf '%s\n' \
      V126_INTERNAL_REMOTE_ENVELOPE_V1 \
      "${action}" \
      "${RUN_ID}" \
      "${RELEASE_SHA}" \
      "${STAGING_PATH}" \
      "${SCRIPT_SHA256}" \
      "${V126_IMAGE_ID}" \
      "${ACTIVE_OPERATION_KIND}" \
      "${ACTIVE_OPERATION_NAME}" \
      "${ACTIVE_PREDECESSOR_STAGE}" \
      "${ACTIVE_PREDECESSOR_HASH}" \
      "${ACTIVE_AUTHORIZATION_GATE}" \
      "${ACTIVE_AUTHORIZATION_HASH}" \
      "${ACTIVE_INTENT_HASH}" \
      "${baseline_database_sha}" \
      "${baseline_identities_sha}" \
      "${baseline_compose_sha}" \
      "${baseline_maintenance_sha}" \
      "${baseline_admission_sha}" \
      "${baseline_caddy_sha}" \
      "${baseline_env_sha}" \
      "${caddy_original_sha}" \
      "${caddy_candidate_sha}" \
      "${caddy_diff_sha}" \
      "${caddy_activation_sha}" \
      "${maintenance_smoke_sha}" \
      "${maintenance_off_sha}" \
      "$#"
    printf '%s\n' "$@"
    cat "${SCRIPT_PATH}"
  } > "${stream}"
  chmod 0600 "${stream}"
  local status=0
  if run_tracked_command_with_input remote-ssh "${stream}" ssh "${REMOTE}" bash -s; then
    status=0
  else
    status=$?
  fi
  rm -f -- "${stream}"
  return "${status}"
}

require_stage_preconditions() {
  local stage="$1"
  [[ ! -e "${STATE_DIR}/run-terminal.json" && ! -L "${STATE_DIR}/run-terminal.json" ]] ||
    die 'run is terminal and no stage may continue'
  require_no_recovery_intent
  local predecessor
  predecessor="$(stage_predecessor "${stage}")"
  if [[ "${predecessor}" != NONE ]]; then
    verify_receipt "${predecessor}" >/dev/null || die "missing or invalid predecessor receipt: ${predecessor}"
  fi
  authorization_hash_for_stage "${stage}" >/dev/null
  local target
  target="$(receipt_path "${stage}")"
  [[ ! -e "${target}" && ! -L "${target}" ]] || die "stage already completed: ${stage}"
  [[ ! -e "${target}.sha256" && ! -L "${target}.sha256" ]] || die "stage receipt checksum path already exists: ${stage}"
  local intent
  intent="$(intent_path "${stage}")"
  [[ ! -e "${intent}" && ! -L "${intent}" ]] ||
    die "stage has a prior intent; retry is forbidden and reconciliation/recovery is required: ${stage}"
}

stage_command() {
  local state_dir=''
  local stage=''
  local evidence_file=''
  shift
  while (( $# > 0 )); do
    case "$1" in
      --state-dir) state_dir="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --evidence-file) evidence_file="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --*) die "unknown stage option: $1" ;;
      *)
        [[ -z "${stage}" ]] || die 'stage accepts exactly one stage name'
        stage="$1"
        shift
        ;;
    esac
  done
  [[ -n "${stage}" ]] || die 'a stage name is required'
  stage_index "${stage}" >/dev/null || die "unknown stage: ${stage}"
  load_state "${state_dir}"
  acquire_state_lock
  install_state_lock_traps
  require_stage_preconditions "${stage}"
  if [[ "${stage}" == MANUAL_SMOKE_PASSED ]]; then
    require_absolute_path evidence-file "${evidence_file}"
  elif [[ -n "${evidence_file}" ]]; then
    die "--evidence-file is not accepted for ${stage}"
  fi
  local index
  local function_name
  local tmp_log
  local final_log
  local failed_log
  local artifacts
  index="$(stage_index "${stage}")"
  function_name="stage_$(printf '%s' "${stage}" | tr '[:upper:]' '[:lower:]')"
  function_name="${function_name//-/_}"
  declare -F "${function_name}" >/dev/null || die "stage implementation is missing: ${stage}"
  tmp_log="${STATE_DIR}/tmp/${index}-${stage}.operation.log.tmp"
  final_log="${STATE_DIR}/artifacts/${index}-${stage}.operation.log"
  failed_log="${STATE_DIR}/artifacts/${index}-${stage}.failed.log"
  artifacts="${STATE_DIR}/tmp/${index}-${stage}.artifacts.tsv"
  [[ ! -e "${tmp_log}" && ! -L "${tmp_log}" && ! -e "${final_log}" && ! -L "${final_log}" &&
    ! -e "${failed_log}" && ! -L "${failed_log}" && ! -e "${artifacts}" && ! -L "${artifacts}" ]] ||
    die "stage temporary or artifact path already exists: ${stage}"
  : > "${tmp_log}"
  : > "${artifacts}"
  chmod 0600 "${tmp_log}" "${artifacts}"
  local intent_hash
  intent_hash="$(write_stage_intent "${stage}")"

  ACTIVE_OPERATION_KIND='STAGE'
  ACTIVE_OPERATION_NAME="${stage}"
  ACTIVE_PREDECESSOR_STAGE="$(stage_predecessor "${stage}")"
  if [[ "${ACTIVE_PREDECESSOR_STAGE}" == NONE ]]; then
    ACTIVE_PREDECESSOR_HASH='NONE'
  else
    ACTIVE_PREDECESSOR_HASH="$(verify_receipt "${ACTIVE_PREDECESSOR_STAGE}")"
  fi
  ACTIVE_AUTHORIZATION_GATE="$(stage_gate "${stage}")"
  ACTIVE_AUTHORIZATION_HASH="$(authorization_hash_for_stage "${stage}")"
  ACTIVE_INTENT_HASH="${intent_hash}"

  local stage_status=0
  local worker_gate
  worker_gate="$(tracked_child_gate_path stage-worker)"
  [[ ! -e "${worker_gate}" && ! -L "${worker_gate}" ]] || die 'stage-worker release gate already exists'
  lock_child_pending stage-worker || die 'stage-worker pending marker failed'
  (
    tracked_child_wait_for_release "${worker_gate}" || exit $?
    "${function_name}" "${evidence_file}"
  ) > "${tmp_log}" 2>&1 &
  local worker_launch_status=$?
  local worker_pid=$!
  (( worker_launch_status == 0 )) || die 'stage-worker launch failed'
  [[ "${worker_pid}" =~ ^[1-9][0-9]*$ ]] || die 'stage-worker PID is invalid'
  if ! lock_child_started stage-worker "${worker_pid}"; then
    abort_unreleased_tracked_child "${worker_pid}" "${worker_gate}"
    die 'stage-worker durable PID binding failed'
  fi
  if ! release_tracked_child "${worker_gate}" "${worker_pid}"; then
    abort_unreleased_tracked_child "${worker_pid}" "${worker_gate}"
    die 'stage-worker release failed'
  fi
  if wait "${worker_pid}"; then
    stage_status=0
  else
    stage_status=$?
  fi
  finish_tracked_child stage-worker "${worker_pid}" "${worker_gate}" ||
    die 'stage-worker finalization failed'
  if (( stage_status != 0 )); then
    chmod 0400 "${tmp_log}"
    mv "${tmp_log}" "${failed_log}"
    rm -f -- "${artifacts}"
    printf 'Stage %s failed closed (exit %s). Restricted log: %s\n' \
      "${stage}" "${stage_status}" "${STATE_DIR}/artifacts/${index}-${stage}.failed.log" >&2
    return "${stage_status}"
  fi

  chmod 0400 "${tmp_log}"
  mv "${tmp_log}" "${final_log}"
  collect_remote_artifacts "${final_log}" "${artifacts}"
  record_artifact "${artifacts}" operation-log "$(hash_file "${final_log}")"
  write_stage_receipt "${stage}" "${artifacts}" "${intent_hash}"
  rm -f -- "${artifacts}"
  verify_receipt "${stage}" >/dev/null || die "new stage receipt failed verification: ${stage}"
  printf 'Stage %s: PASS\n' "${stage}"
  case "${stage}" in
    V126_SCHEMA_RUNTIME_GATE_PASSED)
      printf 'Gate A boundary reached. Gate B is separately required; manual smoke was not opened.\n'
      ;;
    MANUAL_SMOKE_PASSED)
      printf 'Gate B boundary reached. Gate C is separately required; maintenance remains active.\n'
      ;;
  esac
  release_state_lock
  clear_state_lock_traps
}

status_command() {
  local state_dir=''
  shift
  while (( $# > 0 )); do
    case "$1" in
      --state-dir) state_dir="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      *) die "unknown status option: $1" ;;
    esac
  done
  load_state "${state_dir}"
  printf 'run_id=%s\nrelease_sha=%s\nscript_sha256=%s\n' "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}"
  local stage
  local next='NONE'
  for stage in "${V126_STAGES[@]}"; do
    if verify_receipt "${stage}" >/dev/null 2>&1; then
      printf '%s=PASS\n' "${stage}"
    else
      printf '%s=PENDING\n' "${stage}"
      next="${stage}"
      break
    fi
  done
  printf 'next_stage=%s\n' "${next}"
  if [[ -e "${STATE_DIR}/run-terminal.json" || -L "${STATE_DIR}/run-terminal.json" ]]; then
    python3 - "${STATE_DIR}/run.json" "${STATE_DIR}/run-terminal.json" "${STATE_DIR}/run-terminal.json.sha256" <<'PY'
import hashlib
import json
import os
import stat
import sys
manifest_path, terminal_path, checksum_path = sys.argv[1:]
if os.path.islink(terminal_path) or os.path.islink(checksum_path):
    raise SystemExit("terminal marker symlink rejected")
if stat.S_IMODE(os.stat(terminal_path).st_mode) != 0o400 or stat.S_IMODE(os.stat(checksum_path).st_mode) != 0o400:
    raise SystemExit("terminal marker files must be mode 0400")
raw = open(terminal_path, "rb").read()
digest = hashlib.sha256(raw).hexdigest()
if open(checksum_path, "rt", encoding="ascii").read().strip() != digest:
    raise SystemExit("terminal marker checksum mismatch")
doc = json.loads(raw)
manifest = json.load(open(manifest_path, "rt", encoding="utf-8"))
expected_keys = {"format_version", "mode", "release_sha", "run_id", "script_sha256", "status", "terminal_at"}
if set(doc) != expected_keys or doc["format_version"] != 1:
    raise SystemExit("terminal marker schema mismatch")
if raw != (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode():
    raise SystemExit("terminal marker is not canonical JSON")
for key in ("release_sha", "run_id", "script_sha256"):
    if doc[key] != manifest[key]:
        raise SystemExit(f"terminal marker identity mismatch: {key}")
if doc["status"] != "RECOVERY_INTENT_RECORDED_NO_STAGE_CONTINUATION":
    raise SystemExit("terminal marker status mismatch")
print(f"terminal=true\nrecovery_mode={doc['mode']}")
PY
  else
    printf 'terminal=false\n'
  fi
}

main() {
  local command="${1:-}"
  case "${command}" in
    init) create_state "$@" ;;
    authorize) authorize_command "$@" ;;
    stage) stage_command "$@" ;;
    status) status_command "$@" ;;
    recover) recovery_command "$@" ;;
    --help | -h | help) usage ;;
    '') usage >&2; exit 2 ;;
    *) die "unknown command: ${command}" ;;
  esac
}

# Stage and recovery implementations follow below. The exact source is streamed only after a
# locally verified operation envelope; no remote helper is exposed through the public CLI.

remote_hash_file() {
  sha256sum "$1" | awk '{print $1}'
}

remote_emit_artifact() {
  local name="$1"
  local digest="$2"
  [[ "${name}" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || die "invalid remote artifact name: ${name}"
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "invalid remote artifact hash: ${name}"
  printf 'ARTIFACT\t%s\t%s\n' "${name}" "${digest}"
}

remote_require_absolute_path() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^/[A-Za-z0-9._/+:-]+$ && "${value}" != '/' ]] ||
    die "remote ${name} must be a simple absolute path"
  [[ "${value}" != *'/../'* && "${value}" != */.. && "${value}" != *'/./'* && "${value}" != */. ]] ||
    die "remote ${name} must not contain dot path components"
}

remote_require_run_id() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9._-]{5,63}$ ]] || die 'invalid remote run ID'
}

remote_require_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]] || die 'invalid remote release SHA'
}

remote_require_image_id() {
  [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]] || die 'invalid remote image ID'
}

remote_run_root() {
  local staging_path="$1"
  local run_id="$2"
  printf '%s/.v126-runs/%s\n' "${staging_path}" "${run_id}"
}

remote_caddy_evidence_root() {
  local release_sha="$1"
  local run_id="$2"
  printf '/etc/caddy/v126-evidence/%s/%s\n' "${release_sha}" "${run_id}"
}

remote_backup_root() {
  local release_sha="$1"
  local run_id="$2"
  printf '/var/backups/hookah-bot/v126/%s/%s\n' "${release_sha}" "${run_id}"
}

remote_sudo_require_root_file() {
  local target="$1"
  local expected_mode="$2"
  sudo test -f "${target}"
  sudo test ! -L "${target}"
  [[ "$(sudo stat -c '%a:%U:%G' "${target}")" == "${expected_mode}:root:root" ]] ||
    die "root-owned file mode or ownership mismatch: ${target}"
}

remote_sudo_read_sha256_checksum() {
  local checksum="$1"
  remote_sudo_require_root_file "${checksum}" 600
  local value
  value="$(sudo cat "${checksum}")"
  [[ "${value}" =~ ^[0-9a-f]{64}$ ]] || die "invalid root-owned SHA-256 checksum: ${checksum}"
  [[ "$(sudo wc -l "${checksum}" | awk '{print $1}')" == 1 ]] || die "checksum must contain one line: ${checksum}"
  printf '%s\n' "${value}"
}

remote_assert_caddy_drain_marker() {
  remote_sudo_require_root_file /etc/caddy/v126-drain.enabled 600
}

remote_env_value() {
  local env_file="$1"
  local key="$2"
  local count
  count="$(awk -F= -v key="${key}" '$1 == key { count++ } END { print count + 0 }' "${env_file}")"
  [[ "${count}" == 1 ]] || die "${key} must appear exactly once in the staging env"
  awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "${env_file}"
}

remote_compose() {
  env -i \
    PATH="${PATH:?}" \
    HOME="${HOME:?}" \
    BACKEND_IMAGE="${REMOTE_BACKEND_IMAGE:?}" \
    docker compose --env-file .env --file docker-compose.yml "$@"
}

remote_capture_compose_ids() {
  local scope="$1"
  local service="$2"
  local output=''
  case "${scope}" in
    running)
      if ! output="$(remote_compose ps --status running -q "${service}")"; then
        die "Compose running-container inventory failed: ${service}"
      fi
      ;;
    all)
      if ! output="$(remote_compose ps -aq "${service}")"; then
        die "Compose all-container inventory failed: ${service}"
      fi
      ;;
    *) die 'invalid Compose inventory scope' ;;
  esac
  REMOTE_CAPTURED_CONTAINER_IDS=()
  [[ -n "${output}" ]] || return 0
  local container_id
  while IFS= read -r container_id; do
    [[ "${container_id}" =~ ^[0-9a-f]{12,64}$ ]] ||
      die "Compose returned an invalid container identity: ${service}"
    if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} > 0 )); then
      local existing
      for existing in "${REMOTE_CAPTURED_CONTAINER_IDS[@]}"; do
        [[ "${existing}" != "${container_id}" ]] ||
          die "Compose returned a duplicate container identity: ${service}"
      done
    fi
    REMOTE_CAPTURED_CONTAINER_IDS[${#REMOTE_CAPTURED_CONTAINER_IDS[@]}]="${container_id}"
  done <<< "${output}"
}

remote_capture_docker_running_ids() {
  local output=''
  if ! output="$(docker ps -q "$@")"; then
    die 'Docker running-container inventory failed'
  fi
  REMOTE_CAPTURED_CONTAINER_IDS=()
  [[ -n "${output}" ]] || return 0
  local container_id
  while IFS= read -r container_id; do
    [[ "${container_id}" =~ ^[0-9a-f]{12,64}$ ]] ||
      die 'Docker returned an invalid running-container identity'
    if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} > 0 )); then
      local existing
      for existing in "${REMOTE_CAPTURED_CONTAINER_IDS[@]}"; do
        [[ "${existing}" != "${container_id}" ]] ||
          die 'Docker returned a duplicate running-container identity'
      done
    fi
    REMOTE_CAPTURED_CONTAINER_IDS[${#REMOTE_CAPTURED_CONTAINER_IDS[@]}]="${container_id}"
  done <<< "${output}"
}

remote_capture_running_image_ids() {
  local expected_image_id="$1"
  remote_require_image_id "${expected_image_id}"
  remote_capture_docker_running_ids
  local -a running_ids=()
  if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} > 0 )); then
    running_ids=("${REMOTE_CAPTURED_CONTAINER_IDS[@]}")
  fi
  REMOTE_CAPTURED_CONTAINER_IDS=()
  local container_id
  local observed_image_id
  if (( ${#running_ids[@]} > 0 )); then
    for container_id in "${running_ids[@]}"; do
      if ! observed_image_id="$(docker inspect --format '{{.Image}}' "${container_id}")"; then
        die 'Docker image inventory became unobservable'
      fi
      [[ "${observed_image_id}" == "${expected_image_id}" ]] || continue
      REMOTE_CAPTURED_CONTAINER_IDS[${#REMOTE_CAPTURED_CONTAINER_IDS[@]}]="${container_id}"
    done
  fi
}

remote_require_global_image_count() {
  local expected_image_id="$1"
  local expected_count="$2"
  [[ "${expected_count}" =~ ^[0-9]+$ ]] || die 'invalid expected global image count'
  remote_capture_running_image_ids "${expected_image_id}"
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == expected_count )) ||
    die "global running-container count mismatch for image ${expected_image_id}"
}

remote_require_unique_global_image_container() {
  local expected_image_id="$1"
  local expected_container="$2"
  remote_require_global_image_count "${expected_image_id}" 1
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${expected_container}" ]] ||
    die 'the unique global image container is outside the bound Compose backend'
}

remote_assert_compose_backend_image() {
  local expected_image="$1"
  remote_compose config --format json | python3 -c '
import json
import sys
expected = sys.argv[1]
doc = json.load(sys.stdin)
services = doc.get("services")
if not isinstance(services, dict) or "backend" not in services:
    raise SystemExit("Compose backend service is absent")
backend = services["backend"]
if not isinstance(backend, dict) or backend.get("image") != expected:
    raise SystemExit("Compose backend service image does not match the exact bound tag")
' "${expected_image}" || die 'backend-specific Compose image resolution failed'
}

remote_write_proof() {
  local target="$1"
  shift
  [[ ! -e "${target}" && ! -L "${target}" ]] || die "proof already exists: ${target}"
  [[ ! -e "${target}.sha256" && ! -L "${target}.sha256" ]] || die "proof checksum already exists: ${target}"
  local tmp="${target}.tmp.$$"
  [[ ! -e "${tmp}" && ! -L "${tmp}" ]] || die 'proof temporary path already exists'
  printf '%s\n' "$@" > "${tmp}"
  chmod 0600 "${tmp}"
  mv "${tmp}" "${target}"
  printf '%s\n' "$(remote_hash_file "${target}")" > "${target}.sha256"
  chmod 0600 "${target}.sha256"
}

remote_verify_proof() {
  local target="$1"
  [[ -f "${target}" && ! -L "${target}" ]] || die "proof is unavailable: ${target}"
  [[ -f "${target}.sha256" && ! -L "${target}.sha256" ]] || die "proof checksum is unavailable: ${target}"
  [[ "$(stat -c '%a:%U:%G' "${target}")" == "600:$(id -un):$(id -gn)" ]] ||
    die "proof ownership or mode is not operator 0600: ${target}"
  [[ "$(stat -c '%a:%U:%G' "${target}.sha256")" == "600:$(id -un):$(id -gn)" ]] ||
    die "proof checksum ownership or mode is not operator 0600: ${target}"
  local expected
  expected="$(tr -d '\r\n' < "${target}.sha256")"
  [[ "${expected}" =~ ^[0-9a-f]{64}$ && "${expected}" == "$(remote_hash_file "${target}")" ]] ||
    die "proof checksum mismatch: ${target}"
}

remote_require_run_root() {
  local staging_path="$1"
  local run_id="$2"
  local root
  root="$(remote_run_root "${staging_path}" "${run_id}")"
  [[ -d "${root}" && ! -L "${root}" ]] || die 'remote run root is unavailable or a symlink'
  [[ "$(stat -c '%a:%U:%G' "${root}")" == "700:$(id -un):$(id -gn)" ]] ||
    die 'remote run root must be mode-0700 and operator-owned'
  printf '%s\n' "${root}"
}

remote_create_run_root() {
  local staging_path="$1"
  local run_id="$2"
  local namespace_root="${staging_path}/.v126-runs"
  local run_root
  run_root="$(remote_run_root "${staging_path}" "${run_id}")"
  [[ ! -e "${run_root}" && ! -L "${run_root}" ]] || die 'remote run root already exists'
  if [[ -e "${namespace_root}" || -L "${namespace_root}" ]]; then
    [[ -d "${namespace_root}" && ! -L "${namespace_root}" ]] ||
      die 'remote run namespace root is unavailable or a symlink'
    [[ "$(stat -c '%a:%U:%G' "${namespace_root}")" == "700:$(id -un):$(id -gn)" ]] ||
      die 'remote run namespace root is not a mode-0700 operator-owned directory'
  else
    install -d -m 0700 "${namespace_root}"
  fi
  install -d -m 0700 "${run_root}"
  [[ "$(stat -c '%a:%U:%G' "${run_root}")" == "700:$(id -un):$(id -gn)" ]] ||
    die 'remote run root is not a mode-0700 operator-owned directory'
  printf '%s\n' "${run_root}"
}

remote_require_operator_file() {
  local target="$1"
  local expected_mode="$2"
  [[ -f "${target}" && ! -L "${target}" ]] || die "operator file is unavailable or a symlink: ${target}"
  [[ "$(stat -c '%a:%U:%G' "${target}")" == "${expected_mode}:$(id -un):$(id -gn)" ]] ||
    die "operator file ownership or mode mismatch: ${target}"
}

remote_bound_authority_hash() {
  local label="$1"
  local variable_name="$2"
  local explicit_value="${3:-}"
  local streamed_value="${!variable_name:-}"
  local operation="${V126_INTERNAL_REMOTE_OPERATION_KIND:-}:${V126_INTERNAL_REMOTE_OPERATION_NAME:-}:${V126_INTERNAL_REMOTE_ACTION:-}"
  if [[ "${operation}" == STAGE:BASELINE_VERIFIED:baseline ]]; then
    [[ "${streamed_value}" == NONE && "${explicit_value}" =~ ^[0-9a-f]{64}$ ]] ||
      die "baseline ${label} authority must come directly from the release-bound baseline action"
    printf '%s\n' "${explicit_value}"
    return 0
  fi
  [[ "${streamed_value}" =~ ^[0-9a-f]{64}$ ]] ||
    die "${label} authority is absent from the streamed baseline receipt"
  if [[ -n "${explicit_value}" && "${explicit_value}" != "${streamed_value}" ]]; then
    die "${label} authority does not match the streamed baseline receipt"
  fi
  printf '%s\n' "${streamed_value}"
}

remote_verify_baseline_authority() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local expected_database_sha
  local expected_identities_sha
  local expected_compose_sha
  local expected_maintenance_sha
  local expected_admission_sha
  local expected_caddy_sha
  local expected_env_sha
  expected_database_sha="$(remote_bound_authority_hash database-url \
    V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256 "${4:-}")"
  expected_identities_sha="$(remote_bound_authority_hash maintenance-identities \
    V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256 "${5:-}")"
  expected_compose_sha="$(remote_bound_authority_hash compose-source \
    V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256 "${6:-}")"
  expected_maintenance_sha="$(remote_bound_authority_hash maintenance-check-source \
    V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256 "${7:-}")"
  expected_admission_sha="$(remote_bound_authority_hash admission-source \
    V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256 "${8:-}")"
  expected_caddy_sha="$(remote_bound_authority_hash baseline-caddy \
    V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256 "${9:-}")"
  expected_env_sha="$(remote_bound_authority_hash baseline-environment \
    V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256 "${10:-}")"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  local proof="${run_root}/baseline-authority.proof"
  remote_verify_proof "${proof}"
  local fields
  fields="$(python3 - "${proof}" "${staging_path}" "${run_id}" "${release_sha}" <<'PY'
import re
import sys

proof_path, staging_path, run_id, release_sha = sys.argv[1:]
rows = [line.rstrip("\n") for line in open(proof_path, "rt", encoding="utf-8")]
parsed = {}
for row in rows:
    if "=" not in row:
        raise SystemExit("baseline authority row mismatch")
    key, value = row.split("=", 1)
    if key in parsed:
        raise SystemExit("duplicate baseline authority key")
    parsed[key] = value
expected_keys = {
    "admission_path", "admission_sha256", "compose_path", "compose_sha256",
    "caddy_sha256",
    "database_url_path", "database_url_sha256", "maintenance_check_path",
    "maintenance_check_sha256", "maintenance_identities_path",
    "maintenance_identities_sha256", "environment_path", "environment_sha256",
    "release_sha", "result", "run_id",
}
if set(parsed) != expected_keys:
    raise SystemExit("baseline authority schema mismatch")
if parsed["run_id"] != run_id or parsed["release_sha"] != release_sha or parsed["result"] != "PASS":
    raise SystemExit("baseline authority identity mismatch")
expected_paths = {
    "compose_path": staging_path + "/docker-compose.yml",
    "maintenance_check_path": staging_path + "/scripts/check-staging-maintenance-config.sh",
    "admission_path": staging_path + "/scripts/validate-staging-admission.sh",
    "environment_path": staging_path + "/.env",
}
for key, value in expected_paths.items():
    if parsed[key] != value:
        raise SystemExit(f"baseline authority path mismatch: {key}")
for key in (
    "admission_sha256", "caddy_sha256", "compose_sha256", "database_url_sha256",
    "environment_sha256",
    "maintenance_check_sha256", "maintenance_identities_sha256",
):
    if not re.fullmatch(r"[0-9a-f]{64}", parsed[key]):
        raise SystemExit(f"baseline authority hash mismatch: {key}")
for key in (
    "database_url_path", "database_url_sha256", "maintenance_identities_path",
    "maintenance_identities_sha256", "compose_path", "compose_sha256",
    "maintenance_check_path", "maintenance_check_sha256", "admission_path",
    "admission_sha256", "caddy_sha256", "environment_path", "environment_sha256",
):
    print(f"{key}\t{parsed[key]}")
PY
)" || die 'baseline authority proof failed strict verification'
  local database_url_path=''
  local database_url_sha=''
  local identities_path=''
  local identities_sha=''
  local compose_path=''
  local compose_sha=''
  local maintenance_path=''
  local maintenance_sha=''
  local admission_path=''
  local admission_sha=''
  local caddy_sha=''
  local environment_path=''
  local environment_sha=''
  local key value
  while IFS=$'\t' read -r key value; do
    case "${key}" in
      database_url_path) database_url_path="${value}" ;;
      database_url_sha256) database_url_sha="${value}" ;;
      maintenance_identities_path) identities_path="${value}" ;;
      maintenance_identities_sha256) identities_sha="${value}" ;;
      compose_path) compose_path="${value}" ;;
      compose_sha256) compose_sha="${value}" ;;
      maintenance_check_path) maintenance_path="${value}" ;;
      maintenance_check_sha256) maintenance_sha="${value}" ;;
      admission_path) admission_path="${value}" ;;
      admission_sha256) admission_sha="${value}" ;;
      caddy_sha256) caddy_sha="${value}" ;;
      environment_path) environment_path="${value}" ;;
      environment_sha256) environment_sha="${value}" ;;
      *) die 'unexpected baseline authority field' ;;
    esac
  done <<< "${fields}"
  remote_require_absolute_path database-url-file "${database_url_path}"
  remote_require_absolute_path maintenance-identities-file "${identities_path}"
  remote_require_operator_file "${database_url_path}" 600
  remote_require_operator_file "${identities_path}" 600
  remote_require_operator_file "${compose_path}" 644
  remote_require_operator_file "${maintenance_path}" 755
  remote_require_operator_file "${admission_path}" 755
  remote_require_operator_file "${environment_path}" 600
  [[ "$(remote_hash_file "${database_url_path}")" == "${database_url_sha}" ]] ||
    die 'database URL binding changed after baseline'
  [[ "$(remote_hash_file "${identities_path}")" == "${identities_sha}" ]] ||
    die 'maintenance identity binding changed after baseline'
  [[ "$(remote_hash_file "${compose_path}")" == "${compose_sha}" ]] ||
    die 'release-bound Compose source changed after baseline'
  [[ "$(remote_hash_file "${maintenance_path}")" == "${maintenance_sha}" ]] ||
    die 'release-bound maintenance guard changed after baseline'
  [[ "$(remote_hash_file "${admission_path}")" == "${admission_sha}" ]] ||
    die 'release-bound admission guard changed after baseline'
  [[ "${database_url_sha}" == "${expected_database_sha}" ]] ||
    die 'database URL binding does not match the streamed baseline receipt'
  [[ "${identities_sha}" == "${expected_identities_sha}" ]] ||
    die 'maintenance identity binding does not match the streamed baseline receipt'
  [[ "${compose_sha}" == "${expected_compose_sha}" ]] ||
    die 'Compose source does not match the streamed baseline receipt'
  [[ "${maintenance_sha}" == "${expected_maintenance_sha}" ]] ||
    die 'maintenance guard does not match the streamed baseline receipt'
  [[ "${admission_sha}" == "${expected_admission_sha}" ]] ||
    die 'admission guard does not match the streamed baseline receipt'
  [[ "${caddy_sha}" == "${expected_caddy_sha}" ]] ||
    die 'baseline Caddy identity does not match the streamed baseline receipt'
  [[ "${environment_sha}" == "${expected_env_sha}" ]] ||
    die 'baseline environment identity does not match the streamed baseline receipt'
  if [[ "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256:-}" =~ ^[0-9a-f]{64}$ ]]; then
    remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
      "${release_sha}" OFF "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256}"
  elif [[ "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256:-}" =~ ^[0-9a-f]{64}$ ]]; then
    local allow_partial_off=false
    if [[ "${V126_INTERNAL_REMOTE_OPERATION_KIND:-}" == RECOVERY && \
      "${V126_INTERNAL_REMOTE_OPERATION_NAME:-}" == post-v126-stop && \
      "${V126_INTERNAL_REMOTE_PREDECESSOR_STAGE:-}" == V126_BACKEND_STOPPED_FOR_OFF_TRANSITION ]]; then
      allow_partial_off=true
    elif [[ "${V126_INTERNAL_REMOTE_OPERATION_KIND:-}" == RECOVERY && \
      "${V126_INTERNAL_REMOTE_OPERATION_NAME:-}" == verify-full-dr && \
      "${V126_INTERNAL_REMOTE_PREDECESSOR_STAGE:-}" == RECOVERY_POST_V126_STOP ]]; then
      allow_partial_off=true
    fi
    if [[ "${allow_partial_off}" == true ]]; then
      local smoke_after_sha
      smoke_after_sha="$(remote_read_maintenance_after_sha "${run_root}" "${run_id}" \
        "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256}")" ||
        die 'partial OFF transition lacks an immutable smoke environment'
      if [[ "$(remote_hash_file "${environment_path}")" == "${smoke_after_sha}" ]]; then
        remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
          "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256}"
      else
        remote_verify_partial_environment_transition "${staging_path}" "${identities_path}" \
          V126_SMOKE OFF "${smoke_after_sha}" "${expected_identities_sha}"
      fi
    else
      remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
        "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256}"
    fi
  else
    if [[ "$(remote_hash_file "${environment_path}")" == "${expected_env_sha}" ]]; then
      REMOTE_BOUND_ENV_SHA256="${expected_env_sha}"
    elif [[ "${V126_INTERNAL_REMOTE_OPERATION_KIND:-}" == RECOVERY && \
      "${V126_INTERNAL_REMOTE_PREDECESSOR_STAGE:-}" == FINAL_V125_PREFLIGHT_PASSED ]]; then
      remote_verify_partial_environment_transition "${staging_path}" "${identities_path}" \
        OFF V126_SMOKE "${expected_env_sha}" "${expected_identities_sha}"
    else
      die 'current staging environment differs from immutable authority'
    fi
  fi
  [[ "${REMOTE_BOUND_ENV_SHA256}" =~ ^[0-9a-f]{64}$ && \
    "$(remote_hash_file "${environment_path}")" == "${REMOTE_BOUND_ENV_SHA256}" ]] ||
    die 'accepted staging environment changed during authority verification'
}

remote_assert_public_drain() {
  remote_assert_caddy_drain_marker
  local body
  body="$(mktemp "${TMPDIR:-/tmp}/v126-public-drain.XXXXXX")"
  chmod 0600 "${body}"
  local status=0
  if status="$(curl -sS -o "${body}" -w '%{http_code}' https://staging.hookahtootah.club/health 2>/dev/null)"; then
    :
  else
    local curl_status=$?
    rm -f -- "${body}"
    die "public drain probe failed before an HTTP response (curl ${curl_status})"
  fi
  [[ "${status}" == 503 ]] || { rm -f -- "${body}"; die 'public drain did not return HTTP 503'; }
  [[ "$(< "${body}")" == 'Service temporarily unavailable' ]] || {
    rm -f -- "${body}"
    die 'public drain body mismatch'
  }
  rm -f -- "${body}"
}

remote_assert_health_json() {
  local url="$1"
  local target
  target="$(mktemp "${TMPDIR:-/tmp}/v126-health.XXXXXX")"
  chmod 0600 "${target}"
  if ! curl -fsS "${url}" > "${target}" 2>/dev/null; then
    rm -f -- "${target}"
    die "health request failed: ${url}"
  fi
  python3 - "${target}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
if payload != {"status": "ok"}:
    raise SystemExit("health JSON mismatch")
PY
  rm -f -- "${target}"
}

remote_assert_version() {
  local expected="$1"
  local target
  target="$(mktemp "${TMPDIR:-/tmp}/v126-version.XXXXXX")"
  chmod 0600 "${target}"
  if ! curl -fsS http://127.0.0.1:8080/version > "${target}" 2>/dev/null; then
    rm -f -- "${target}"
    die 'loopback version request failed'
  fi
  python3 - "${target}" "${expected}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("service") != "backend" or payload.get("env") != "staging" or payload.get("version") != sys.argv[2]:
    raise SystemExit("backend version identity mismatch")
PY
  rm -f -- "${target}"
}

remote_assert_telegram_idle() {
  local env_file="$1"
  local token
  token="$(remote_env_value "${env_file}" TELEGRAM_BOT_TOKEN)"
  [[ "${token}" =~ ^[0-9]+:[A-Za-z0-9_-]+$ ]] || die 'Telegram bot token is missing or malformed'
  local temp_dir
  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/v126-telegram-idle.XXXXXX")"
  chmod 0700 "${temp_dir}"
  local cleanup_command
  printf -v cleanup_command 'rm -rf -- %q' "${temp_dir}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  local config="${temp_dir}/curl.conf"
  local response="${temp_dir}/response.json"
  local error_file="${temp_dir}/curl.err"
  printf '%s\n' \
    'silent' \
    'show-error' \
    'fail' \
    "url = \"https://api.telegram.org/bot${token}/getWebhookInfo\"" > "${config}"
  chmod 0600 "${config}"
  if ! curl --config "${config}" --output "${response}" 2> "${error_file}"; then
    die 'Telegram getWebhookInfo failed; restricted response was discarded'
  fi
  python3 - "${response}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
result = payload.get("result") if payload.get("ok") is True else None
if not isinstance(result, dict):
    raise SystemExit("Telegram webhook response is not successful")
if result.get("url") != "" or result.get("pending_update_count") != 0:
    raise SystemExit("Telegram webhook or pending update gate failed")
PY
  rm -rf -- "${temp_dir}"
  trap - EXIT INT TERM HUP
}

remote_assert_zero_writer() {
  local expected_flyway="$1"
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'backend container count is not zero'
  remote_capture_compose_ids running postgres
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'PostgreSQL running container count is not one'
  remote_require_global_image_count "${V125_IMAGE_ID}" 0
  remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" 0
  remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null

  local session_gate
  session_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  COUNT(*) FILTER (
    WHERE (backend_type = 'client backend' OR backend_type IS NULL)
      AND pid <> pg_backend_pid()
  ), ':',
  COUNT(*) FILTER (
    WHERE (backend_type = 'client backend' OR backend_type IS NULL)
      AND pid <> pg_backend_pid()
      AND state LIKE 'idle in transaction%'
  ), ':',
  (SELECT COUNT(*) FROM pg_prepared_xacts), ':',
  (SELECT COUNT(*) FROM pg_replication_slots)
)
FROM pg_stat_activity;
SQL
)"
  [[ "${session_gate}" == '0:0:0:0' ]] || die 'session/writer/prepared/slot gate is not zero'

  local queue_gate
  queue_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')),
  ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING'))
);
SQL
)"
  [[ "${queue_gate}" == '0:0' ]] || die 'actionable queue gate is not zero'

  if [[ "${expected_flyway}" != ANY ]]; then
    local flyway_gate
    flyway_gate="$(remote_compose exec -T postgres sh -c \
      ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  MAX(version::integer), ':',
  COUNT(*) FILTER (WHERE version = '126'), ':',
  COUNT(*) FILTER (WHERE NOT success)
)
FROM flyway_schema_history;
SQL
)"
    [[ "${flyway_gate}" == "${expected_flyway}" ]] || die 'Flyway zero-writer gate mismatch'
  fi
}

remote_assert_schema_v126() {
  remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1' >/dev/null <<'SQL'
DO $contract$
BEGIN
  IF (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '126') <> 1 THEN
    RAISE EXCEPTION 'V126 Flyway row count mismatch';
  END IF;
  IF (SELECT COUNT(*) FROM flyway_schema_history
      WHERE version = '126' AND success AND checksum = 1701638026) <> 1 THEN
    RAISE EXCEPTION 'V126 Flyway identity mismatch';
  END IF;
  IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'failed Flyway history row exists';
  END IF;
  IF (SELECT MAX(version::integer) FROM flyway_schema_history) <> 126 THEN
    RAISE EXCEPTION 'Flyway head is not V126';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'support_thread_reads'
      AND column_name = 'last_read_message_id'
      AND data_type = 'bigint'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND is_identity = 'NO'
      AND is_generated = 'NEVER'
  ) THEN
    RAISE EXCEPTION 'last_read_message_id invariant mismatch';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = current_schema()
      AND t.relname = 'support_thread_reads'
      AND c.contype = 'p'
      AND pg_get_constraintdef(c.oid, false) = 'PRIMARY KEY (thread_id, user_id)'
  ) THEN
    RAISE EXCEPTION 'support_thread_reads primary key mismatch';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_index i
    JOIN pg_class idx ON idx.oid = i.indexrelid
    JOIN pg_class tbl ON tbl.oid = i.indrelid
    JOIN pg_namespace n ON n.oid = tbl.relnamespace
    WHERE n.nspname = current_schema()
      AND tbl.relname = 'support_messages'
      AND idx.relname = 'idx_support_messages_thread_id'
      AND NOT i.indisunique
      AND i.indisvalid
      AND i.indisready
      AND pg_get_indexdef(i.indexrelid) LIKE '%(thread_id, id)'
  ) THEN
    RAISE EXCEPTION 'support message unread index mismatch';
  END IF;
END
$contract$;
SQL
}

remote_assert_runtime() {
  local staging_path="$1"
  local expected_release="$2"
  local expected_image_id="$3"
  local expected_mode="$4"
  local require_drain="$5"
  remote_assert_single_v126_backend_poller "${expected_image_id}"
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'runtime environment proof requires one running Compose backend'
  local environment_phase
  case "${expected_mode}" in
    V126_SMOKE) environment_phase=first ;;
    OFF) environment_phase=final ;;
    *) die 'runtime environment proof has an invalid maintenance mode' ;;
  esac
  remote_assert_bound_container_environment "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" \
    "${environment_phase}"
  remote_capture_compose_ids running postgres
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'running PostgreSQL count is not one'
  remote_assert_health_json http://127.0.0.1:8080/health
  remote_assert_health_json http://127.0.0.1:8080/db/health
  curl -fsSI http://127.0.0.1:8080/miniapp/ >/dev/null 2>&1 || die 'loopback Mini App check failed'
  remote_assert_version "${expected_release}"
  if [[ "${expected_mode}" == V126_SMOKE ]]; then
    STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
      "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  else
    "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  fi
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file .env --compose-file docker-compose.yml >/dev/null
  remote_assert_schema_v126
  local queue_gate
  queue_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')),
  ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING'))
);
SQL
)"
  [[ "${queue_gate}" == '0:0' ]] || die 'runtime actionable queue gate is not zero'
  remote_assert_telegram_idle .env
  if [[ "${require_drain}" == true ]]; then
    remote_assert_public_drain
  fi
}

remote_baseline() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local v125_image_tag="$4"
  local database_url_file="$5"
  local identities_file="$6"
  local expected_compose_sha="$7"
  local expected_maintenance_sha="$8"
  local expected_admission_sha="$9"
  remote_require_absolute_path staging-path "${staging_path}"
  remote_require_run_id "${run_id}"
  remote_require_sha "${release_sha}"
  remote_require_absolute_path database-url-file "${database_url_file}"
  remote_require_absolute_path maintenance-identities-file "${identities_file}"
  [[ "${expected_compose_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid release-bound Compose SHA-256'
  [[ "${expected_maintenance_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid release-bound maintenance guard SHA-256'
  [[ "${expected_admission_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid release-bound admission guard SHA-256'
  [[ "${v125_image_tag}" =~ :${V125_SOURCE_SHA}$ ]] || die 'remote V125 image tag mismatch'
  cd "${staging_path}"
  remote_require_operator_file .env 600
  remote_require_operator_file "${database_url_file}" 600
  remote_require_operator_file "${identities_file}" 600
  remote_require_operator_file docker-compose.yml 644
  remote_require_operator_file scripts/check-staging-maintenance-config.sh 755
  remote_require_operator_file scripts/validate-staging-admission.sh 755
  local database_url_sha
  local identities_sha
  local compose_sha
  local maintenance_sha
  local admission_sha
  local env_sha
  database_url_sha="$(remote_hash_file "${database_url_file}")"
  identities_sha="$(remote_hash_file "${identities_file}")"
  compose_sha="$(remote_hash_file docker-compose.yml)"
  maintenance_sha="$(remote_hash_file scripts/check-staging-maintenance-config.sh)"
  admission_sha="$(remote_hash_file scripts/validate-staging-admission.sh)"
  env_sha="$(remote_hash_file .env)"
  [[ "${compose_sha}" == "${expected_compose_sha}" ]] ||
    die 'staging Compose source is not the exact release-tracked file; HT-13 preparation is required'
  [[ "${maintenance_sha}" == "${expected_maintenance_sha}" ]] ||
    die 'staging maintenance guard is not the exact release-tracked file; HT-13 preparation is required'
  [[ "${admission_sha}" == "${expected_admission_sha}" ]] ||
    die 'staging admission guard is not the exact release-tracked file; HT-13 preparation is required'
  "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file .env --compose-file docker-compose.yml >/dev/null
  REMOTE_BACKEND_IMAGE="${v125_image_tag}"
  remote_assert_compose_backend_image "${v125_image_tag}"
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'baseline backend count is not one'
  local backend_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  remote_capture_compose_ids all backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'baseline has an extra stopped or running Compose backend'
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${backend_container}" ]] ||
    die 'baseline running backend is not the unique Compose backend'
  remote_capture_compose_ids running postgres
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'baseline PostgreSQL count is not one'
  [[ "$(docker inspect --format '{{.Image}}' "${backend_container}")" == "${V125_IMAGE_ID}" ]] ||
    die 'baseline V125 image ID mismatch'
  [[ "$(docker image inspect --format '{{.Id}}' "${v125_image_tag}")" == "${V125_IMAGE_ID}" ]] ||
    die 'loaded V125 image identity mismatch'
  remote_assert_bound_container_environment "${backend_container}" baseline
  docker exec "${backend_container}" sh -c \
    'test "${TELEGRAM_BOT_ENABLED:-}" = true && test "${TELEGRAM_BOT_MODE:-}" = long_polling' >/dev/null ||
    die 'baseline V125 backend is not the unique long-polling Telegram poller'
  remote_require_unique_global_image_container "${V125_IMAGE_ID}" "${backend_container}"
  remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" 0
  remote_assert_health_json http://127.0.0.1:8080/health
  remote_assert_health_json http://127.0.0.1:8080/db/health
  curl -fsSI http://127.0.0.1:8080/miniapp/ >/dev/null 2>&1 || die 'baseline Mini App check failed'
  remote_assert_version "${V125_SOURCE_SHA}"
  local flyway_gate
  flyway_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(MAX(version::integer), ':', COUNT(*) FILTER (WHERE version = '126'), ':', COUNT(*) FILTER (WHERE NOT success))
FROM flyway_schema_history;
SQL
)"
  [[ "${flyway_gate}" == '125:0:0' ]] || die 'baseline Flyway state mismatch'
  local queue_gate
  queue_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')),
  ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING'))
);
SQL
)"
  [[ "${queue_gate}" == '0:0' ]] || die 'baseline actionable queues are not empty'
  remote_assert_telegram_idle .env
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644
  sudo test ! -e /etc/caddy/v126-drain.enabled
  sudo test ! -L /etc/caddy/v126-drain.enabled
  sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
  [[ "$(sudo systemctl is-active caddy)" == active ]] || die 'Caddy is not active'
  local caddy_sha
  caddy_sha="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')"
  [[ "${caddy_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'baseline Caddy hash is invalid'
  remote_assert_public_live
  local run_root
  run_root="$(remote_create_run_root "${staging_path}" "${run_id}")"
  local authority_proof="${run_root}/baseline-authority.proof"
  remote_write_proof "${authority_proof}" \
    "run_id=${run_id}" \
    "release_sha=${release_sha}" \
    "database_url_path=${database_url_file}" \
    "database_url_sha256=${database_url_sha}" \
    "maintenance_identities_path=${identities_file}" \
    "maintenance_identities_sha256=${identities_sha}" \
    "compose_path=${staging_path}/docker-compose.yml" \
    "compose_sha256=${compose_sha}" \
    "maintenance_check_path=${staging_path}/scripts/check-staging-maintenance-config.sh" \
    "maintenance_check_sha256=${maintenance_sha}" \
    "admission_path=${staging_path}/scripts/validate-staging-admission.sh" \
    "admission_sha256=${admission_sha}" \
    "caddy_sha256=${caddy_sha}" \
    "environment_path=${staging_path}/.env" \
    "environment_sha256=${env_sha}" \
    'result=PASS'
  remote_verify_baseline_authority "${staging_path}" "${run_id}" "${release_sha}" \
    "${database_url_sha}" "${identities_sha}" "${expected_compose_sha}" \
    "${expected_maintenance_sha}" "${expected_admission_sha}" "${caddy_sha}" "${env_sha}"
  local baseline_record
  baseline_record="$(mktemp "${TMPDIR:-/tmp}/v126-baseline.XXXXXX")"
  printf '%s\n' \
    "run_id=${run_id}" \
    "release_sha=${release_sha}" \
    "v125_source_sha=${V125_SOURCE_SHA}" \
    "v125_image_id=${V125_IMAGE_ID}" \
    'flyway=125:0:0' \
    'queues=0:0' \
    'maintenance=OFF' \
    'traffic_policy=PRODUCT' \
    "caddy_sha256=${caddy_sha}" \
    'result=PASS' > "${baseline_record}"
  chmod 0600 "${baseline_record}"
  remote_emit_artifact database-url-binding "${database_url_sha}"
  remote_emit_artifact maintenance-identities "${identities_sha}"
  remote_emit_artifact remote-compose-source "${compose_sha}"
  remote_emit_artifact remote-maintenance-check-source "${maintenance_sha}"
  remote_emit_artifact remote-admission-source "${admission_sha}"
  remote_emit_artifact baseline-caddy "${caddy_sha}"
  remote_emit_artifact baseline-env "${env_sha}"
  remote_emit_artifact staging-baseline "$(remote_hash_file "${baseline_record}")"
  rm -f -- "${baseline_record}"
}

remote_backup_rehearsal() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local phase="$4"
  local backend_image="$5"
  remote_require_absolute_path staging-path "${staging_path}"
  remote_require_run_id "${run_id}"
  remote_require_sha "${release_sha}"
  [[ "${phase}" == pre-drain || "${phase}" == quiesced ]] || die 'backup phase must be pre-drain or quiesced'
  [[ "${backend_image}" =~ ^[a-z0-9][a-z0-9._/-]*:[0-9a-f]{40}$ ]] || die 'invalid backup Compose image tag'
  cd "${staging_path}"
  REMOTE_BACKEND_IMAGE="${backend_image}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_baseline_authority "${staging_path}" "${run_id}" "${release_sha}"
  remote_assert_compose_backend_image "${backend_image}"
  if [[ "${phase}" == pre-drain ]]; then
    remote_verify_proof "${run_root}/baseline-authority.proof"
  else
    remote_assert_zero_writer '125:0:0'
  fi

  local backup_root
  backup_root="$(remote_backup_root "${release_sha}" "${run_id}")"
  local backup_base='/var/backups/hookah-bot'
  local backup_version_root="${backup_base}/v126"
  local backup_release_root="${backup_version_root}/${release_sha}"
  local artifact
  sudo test -d "${backup_base}"
  sudo test ! -L "${backup_base}"
  for artifact in "${backup_version_root}" "${backup_release_root}"; do
    sudo test ! -L "${artifact}"
    if sudo test -e "${artifact}"; then
      sudo test -d "${artifact}"
      sudo test ! -L "${artifact}"
      [[ "$(sudo stat -c '%a:%U:%G' "${artifact}")" == "700:$(id -un):$(id -gn)" ]] ||
        die 'backup namespace parent ownership or mode mismatch'
    else
      sudo install -d -o "$(id -un)" -g "$(id -gn)" -m 0700 "${artifact}"
    fi
  done
  if [[ "${phase}" == pre-drain ]]; then
    sudo test ! -e "${backup_root}"
    sudo test ! -L "${backup_root}"
    sudo install -d -o "$(id -un)" -g "$(id -gn)" -m 0700 "${backup_root}"
  else
    sudo test -d "${backup_root}"
    sudo test ! -L "${backup_root}"
  fi
  [[ "$(stat -c '%a:%U:%G' "${backup_root}")" == "700:$(id -un):$(id -gn)" ]] ||
    die 'backup root ownership or mode mismatch'

  local dump_file="${backup_root}/${phase}.dump"
  local list_file="${dump_file}.pg_restore.list"
  local sha_file="${dump_file}.sha256"
  local metadata_file="${dump_file}.rehearsal.txt"
  for artifact in "${dump_file}" "${list_file}" "${sha_file}" "${metadata_file}"; do
    [[ ! -e "${artifact}" && ! -L "${artifact}" ]] || die "backup artifact already exists: ${phase}"
  done
  set -o noclobber

  local postgres_container
  remote_capture_compose_ids running postgres
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || die 'PostgreSQL container count is not one'
  postgres_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  local source_image_id
  local source_db_user
  local source_version
  local source_db_size
  source_image_id="$(docker inspect --format '{{.Image}}' "${postgres_container}")"
  [[ "${source_image_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || die 'source PostgreSQL image ID is invalid'
  source_db_user="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}"; printf %s "$POSTGRES_USER"')"
  source_version="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SHOW server_version_num"')"
  source_db_size="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT pg_database_size(current_database())"')"
  [[ -n "${source_db_user}" ]] || die 'source database user is empty'
  [[ "${source_version}" =~ ^[0-9]+$ && "${source_db_size}" =~ ^[0-9]+$ ]] ||
    die 'source PostgreSQL version or size is invalid'

  remote_minimum_available_bytes() {
    local backup_available
    local docker_root
    local docker_available
    backup_available="$(df --output=avail -B1 "${backup_root}" | awk 'NR == 2 {print $1}')"
    docker_root="$(docker info --format '{{.DockerRootDir}}')"
    [[ -d "${docker_root}" ]] || die 'Docker root directory is unavailable'
    docker_available="$(df --output=avail -B1 "${docker_root}" | awk 'NR == 2 {print $1}')"
    [[ "${backup_available}" =~ ^[0-9]+$ && "${docker_available}" =~ ^[0-9]+$ ]] ||
      die 'available byte count is invalid'
    if (( backup_available < docker_available )); then
      printf '%s\n' "${backup_available}"
    else
      printf '%s\n' "${docker_available}"
    fi
  }

  local minimum_bytes=$((2 * 1024 * 1024 * 1024))
  local preliminary_bytes=$((4 * source_db_size))
  if (( preliminary_bytes < minimum_bytes )); then preliminary_bytes="${minimum_bytes}"; fi
  (( $(remote_minimum_available_bytes) >= preliminary_bytes )) || die 'insufficient free space before backup'

  remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
    > "${dump_file}"
  [[ -s "${dump_file}" ]] || die 'backup dump is empty'
  chmod 0600 "${dump_file}"
  remote_compose exec -T postgres sh -c ': "${POSTGRES_USER:?}"; pg_restore --list' \
    < "${dump_file}" > "${list_file}"
  [[ -s "${list_file}" ]] || die 'backup inventory is empty'
  chmod 0600 "${list_file}"
  sha256sum "${dump_file}" > "${sha_file}"
  chmod 0600 "${sha_file}"
  sha256sum -c "${sha_file}" >/dev/null

  local globals_file=''
  if [[ "${phase}" == pre-drain ]]; then
    globals_file="${backup_root}/globals.sql"
    [[ ! -e "${globals_file}" && ! -L "${globals_file}" ]] || die 'globals artifact already exists'
    [[ ! -e "${globals_file}.sha256" && ! -L "${globals_file}.sha256" ]] || die 'globals checksum already exists'
    remote_compose exec -T postgres sh -c \
      ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; pg_dumpall -U "$POSTGRES_USER" -d "$POSTGRES_DB" --globals-only --no-role-passwords' \
      > "${globals_file}"
    [[ -s "${globals_file}" ]] || die 'globals artifact is empty'
    chmod 0600 "${globals_file}"
    sha256sum "${globals_file}" > "${globals_file}.sha256"
    chmod 0600 "${globals_file}.sha256"
    sha256sum -c "${globals_file}.sha256" >/dev/null
  fi

  local dump_size
  local calculated_bytes
  local required_bytes
  dump_size="$(stat -c '%s' "${dump_file}")"
  calculated_bytes=$((4 * source_db_size + 2 * dump_size))
  required_bytes="${minimum_bytes}"
  if (( calculated_bytes > minimum_bytes )); then required_bytes="${calculated_bytes}"; fi
  (( $(remote_minimum_available_bytes) >= required_bytes )) || die 'insufficient free space for rehearsal'

  local safe_run="${run_id//[^a-z0-9-]/-}"
  local rehearsal_volume="hookah-v126-${safe_run}-${phase}-$$"
  local rehearsal_container="hookah-v126-${safe_run}-${phase}-$$"
  local rehearsal_owner="v126:${release_sha}:${safe_run}:${phase}:$$"
  [[ "${rehearsal_volume}" =~ ^hookah-v126-[a-z0-9-]+$ ]] || die 'invalid rehearsal volume name'
  [[ "${rehearsal_container}" =~ ^hookah-v126-[a-z0-9-]+$ ]] || die 'invalid rehearsal container name'
  [[ "${rehearsal_owner}" =~ ^v126:[0-9a-f]{40}:[a-z0-9-]+:(pre-drain|quiesced):[0-9]+$ ]] ||
    die 'invalid rehearsal ownership label'
  remote_rehearsal_exact_name_count() {
    local expected_name="$1"
    local inventory="$2"
    local item
    local count=0
    while IFS= read -r item; do
      [[ "${item}" == "${expected_name}" ]] && count=$((count + 1))
    done <<< "${inventory}"
    printf '%s\n' "${count}"
  }
  local container_inventory
  local volume_inventory
  container_inventory="$(docker ps --all --format '{{.Names}}')" ||
    die 'rehearsal container inventory failed before creation'
  volume_inventory="$(docker volume ls --format '{{.Name}}')" ||
    die 'rehearsal volume inventory failed before creation'
  [[ "$(remote_rehearsal_exact_name_count "${rehearsal_container}" "${container_inventory}")" == 0 ]] ||
    die 'rehearsal container already exists'
  [[ "$(remote_rehearsal_exact_name_count "${rehearsal_volume}" "${volume_inventory}")" == 0 ]] ||
    die 'rehearsal volume already exists'
  V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER="${rehearsal_container}"
  V126_REMOTE_REHEARSAL_CLEANUP_VOLUME="${rehearsal_volume}"
  remote_cleanup_owned_rehearsal_container() {
    local expected_container="$1"
    local expected_owner="$2"
    local inventory
    local count
    local actual_owner
    inventory="$(docker ps --all --format '{{.Names}}')" || {
      printf '%s\n' 'rehearsal container cleanup inventory failed' >&2
      return 1
    }
    count="$(remote_rehearsal_exact_name_count "${expected_container}" "${inventory}")"
    [[ "${count}" == 0 ]] && return 0
    [[ "${count}" == 1 ]] || {
      printf '%s\n' 'rehearsal container cleanup inventory is ambiguous' >&2
      return 1
    }
    actual_owner="$(docker container inspect --format \
      '{{ index .Config.Labels "hookah.v126.rehearsal-owner" }}' "${expected_container}")" || {
      printf '%s\n' 'rehearsal container cleanup ownership proof failed' >&2
      return 1
    }
    [[ "${actual_owner}" == "${expected_owner}" ]] || {
      printf '%s\n' 'rehearsal container cleanup ownership mismatch' >&2
      return 1
    }
    docker rm -f "${expected_container}" >/dev/null 2>&1 || {
      printf '%s\n' 'rehearsal container cleanup removal failed' >&2
      return 1
    }
  }
  remote_cleanup_owned_rehearsal_volume() {
    local expected_volume="$1"
    local expected_owner="$2"
    local inventory
    local count
    local actual_owner
    inventory="$(docker volume ls --format '{{.Name}}')" || {
      printf '%s\n' 'rehearsal volume cleanup inventory failed' >&2
      return 1
    }
    count="$(remote_rehearsal_exact_name_count "${expected_volume}" "${inventory}")"
    [[ "${count}" == 0 ]] && return 0
    [[ "${count}" == 1 ]] || {
      printf '%s\n' 'rehearsal volume cleanup inventory is ambiguous' >&2
      return 1
    }
    actual_owner="$(docker volume inspect --format \
      '{{ index .Labels "hookah.v126.rehearsal-owner" }}' "${expected_volume}")" || {
      printf '%s\n' 'rehearsal volume cleanup ownership proof failed' >&2
      return 1
    }
    [[ "${actual_owner}" == "${expected_owner}" ]] || {
      printf '%s\n' 'rehearsal volume cleanup ownership mismatch' >&2
      return 1
    }
    docker volume rm "${expected_volume}" >/dev/null 2>&1 || {
      printf '%s\n' 'rehearsal volume cleanup removal failed' >&2
      return 1
    }
  }
  remote_cleanup_rehearsal() {
    local expected_container="$1"
    local expected_volume="$2"
    local expected_owner="$3"
    local cleanup_container="${V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER:-}"
    local cleanup_volume="${V126_REMOTE_REHEARSAL_CLEANUP_VOLUME:-}"
    local cleanup_status=0
    [[ -z "${cleanup_container}" || "${cleanup_container}" == "${expected_container}" ]] || {
      printf '%s\n' 'rehearsal container cleanup state mismatch' >&2
      return 1
    }
    [[ -z "${cleanup_volume}" || "${cleanup_volume}" == "${expected_volume}" ]] || {
      printf '%s\n' 'rehearsal volume cleanup state mismatch' >&2
      return 1
    }
    V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER=''
    V126_REMOTE_REHEARSAL_CLEANUP_VOLUME=''
    if [[ -n "${cleanup_container}" ]] &&
      ! remote_cleanup_owned_rehearsal_container "${expected_container}" "${expected_owner}"; then
      cleanup_status=1
    fi
    if [[ -n "${cleanup_volume}" ]] &&
      ! remote_cleanup_owned_rehearsal_volume "${expected_volume}" "${expected_owner}"; then
      cleanup_status=1
    fi
    return "${cleanup_status}"
  }
  local cleanup_command
  printf -v cleanup_command 'remote_cleanup_rehearsal %q %q %q' \
    "${rehearsal_container}" "${rehearsal_volume}" "${rehearsal_owner}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'rehearsal cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'rehearsal cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'rehearsal cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'rehearsal cleanup failed after HUP' >&2; fi; exit 129" HUP
  docker volume create \
    --label "hookah.v126.rehearsal-owner=${rehearsal_owner}" \
    "${rehearsal_volume}" >/dev/null
  local created_volume_owner
  created_volume_owner="$(docker volume inspect --format \
    '{{ index .Labels "hookah.v126.rehearsal-owner" }}' "${rehearsal_volume}")" ||
    die 'created rehearsal volume ownership proof failed'
  [[ "${created_volume_owner}" == "${rehearsal_owner}" ]] ||
    die 'created rehearsal volume ownership mismatch'
  docker run --detach \
    --name "${rehearsal_container}" \
    --label "hookah.v126.rehearsal-owner=${rehearsal_owner}" \
    --network none \
    --mount "type=volume,source=${rehearsal_volume},target=/var/lib/postgresql/data" \
    --env "POSTGRES_USER=${source_db_user}" \
    --env POSTGRES_HOST_AUTH_METHOD=trust \
    "${source_image_id}" >/dev/null
  local created_container_owner
  created_container_owner="$(docker container inspect --format \
    '{{ index .Config.Labels "hookah.v126.rehearsal-owner" }}' "${rehearsal_container}")" ||
    die 'created rehearsal container ownership proof failed'
  [[ "${created_container_owner}" == "${rehearsal_owner}" ]] ||
    die 'created rehearsal container ownership mismatch'
  [[ "$(docker inspect --format '{{.HostConfig.NetworkMode}}' "${rehearsal_container}")" == none ]] ||
    die 'rehearsal network mode mismatch'
  [[ "$(docker inspect --format '{{len .HostConfig.PortBindings}}' "${rehearsal_container}")" == 0 ]] ||
    die 'rehearsal unexpectedly publishes ports'
  [[ "$(docker inspect --format '{{len .Mounts}}' "${rehearsal_container}")" == 1 ]] ||
    die 'rehearsal mount count mismatch'
  [[ "$(docker inspect --format '{{(index .Mounts 0).Type}}' "${rehearsal_container}")" == volume ]] ||
    die 'rehearsal mount type mismatch'
  [[ "$(docker inspect --format '{{(index .Mounts 0).Name}}' "${rehearsal_container}")" == "${rehearsal_volume}" ]] ||
    die 'rehearsal volume identity mismatch'
  [[ "$(docker inspect --format '{{(index .Mounts 0).Destination}}' "${rehearsal_container}")" == /var/lib/postgresql/data ]] ||
    die 'rehearsal mount destination mismatch'

  local ready=false
  local attempt
  for attempt in $(seq 1 60); do
    if docker exec "${rehearsal_container}" \
      pg_isready -U "${source_db_user}" -d postgres >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 1
  done
  [[ "${ready}" == true ]] || die 'rehearsal PostgreSQL did not become ready in 60 attempts'
  docker cp "${dump_file}" "${rehearsal_container}:/tmp/v126-rehearsal.dump"
  docker exec "${rehearsal_container}" \
    createdb -U "${source_db_user}" --maintenance-db=postgres --template=template0 v126_restore_rehearsal
  docker exec "${rehearsal_container}" \
    pg_restore -U "${source_db_user}" --exit-on-error --no-owner --no-privileges \
    --dbname v126_restore_rehearsal /tmp/v126-rehearsal.dump
  local restored_version
  local restored_migration_state
  restored_version="$(docker exec "${rehearsal_container}" \
    psql -X -U "${source_db_user}" -d v126_restore_rehearsal -Atqc 'SHOW server_version_num')"
  [[ "${restored_version}" == "${source_version}" ]] || die 'rehearsal PostgreSQL version mismatch'
  restored_migration_state="$(docker exec "${rehearsal_container}" \
    psql -X -U "${source_db_user}" -d v126_restore_rehearsal -Atqc \
    "SELECT CONCAT(MAX(version::integer), ':', COUNT(*) FILTER (WHERE version = '126'), ':', COUNT(*) FILTER (WHERE NOT success)) FROM flyway_schema_history")"
  [[ "${restored_migration_state}" == '125:0:0' ]] || die 'rehearsal Flyway state mismatch'
  if ! remote_cleanup_rehearsal \
    "${rehearsal_container}" "${rehearsal_volume}" "${rehearsal_owner}"; then
    trap - EXIT INT TERM HUP
    unset V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER V126_REMOTE_REHEARSAL_CLEANUP_VOLUME
    die 'rehearsal resource cleanup failed'
  fi
  trap - EXIT INT TERM HUP
  unset V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER V126_REMOTE_REHEARSAL_CLEANUP_VOLUME

  printf '%s\n' \
    "run_id=${run_id}" \
    "release_sha=${release_sha}" \
    "phase=${phase}" \
    "source_image_id=${source_image_id}" \
    "source_version=${source_version}" \
    "source_db_size=${source_db_size}" \
    "dump_size=${dump_size}" \
    "required_free_bytes=${required_bytes}" \
    'restored_flyway=125:0:0' \
    'rehearsal=PASS' > "${metadata_file}"
  chmod 0600 "${metadata_file}"
  for artifact in "${dump_file}" "${list_file}" "${sha_file}" "${metadata_file}"; do
    [[ "$(stat -c '%a' "${artifact}")" == 600 ]] || die 'backup artifact mode mismatch'
  done
  remote_emit_artifact "${phase}-backup-dump" "$(remote_hash_file "${dump_file}")"
  remote_emit_artifact "${phase}-backup-inventory" "$(remote_hash_file "${list_file}")"
  remote_emit_artifact "${phase}-backup-rehearsal" "$(remote_hash_file "${metadata_file}")"
  if [[ -n "${globals_file}" ]]; then
    remote_emit_artifact pre-drain-globals "$(remote_hash_file "${globals_file}")"
  fi
  local backup_proof="${run_root}/${phase}-backup-rehearsed.proof"
  remote_write_proof "${backup_proof}" \
    "run_id=${run_id}" \
    "release_sha=${release_sha}" \
    "phase=${phase}" \
    "dump_sha256=$(remote_hash_file "${dump_file}")" \
    "inventory_sha256=$(remote_hash_file "${list_file}")" \
    "rehearsal_sha256=$(remote_hash_file "${metadata_file}")" \
    'result=PASS'
  remote_emit_artifact "${phase}-backup-proof" "$(remote_hash_file "${backup_proof}")"
}

remote_assert_caddy_candidate_derived() {
  local original="$1"
  local candidate="$2"
  sudo python3 - "${original}" "${candidate}" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_bytes()
candidate = Path(sys.argv[2]).read_bytes()
if b"v126_staging_drain" in source or b"v126-drain.enabled" in source:
    raise SystemExit("sealed original Caddyfile already contains the V126 drain block")
lines = source.splitlines(keepends=True)
matches = [index for index, line in enumerate(lines) if line.strip() == b"staging.hookahtootah.club {"]
if len(matches) != 1:
    raise SystemExit("sealed original Caddyfile lacks one exact staging site block")
index = matches[0]
opening = lines[index]
newline = b"\r\n" if opening.endswith(b"\r\n") else b"\n"
indent = opening[: len(opening) - len(opening.lstrip())] + b"    "
block = [
    indent + b"@v126_staging_drain file {" + newline,
    indent + b"    root /" + newline,
    indent + b"    try_files /etc/caddy/v126-drain.enabled" + newline,
    indent + b"}" + newline,
    indent + b'respond @v126_staging_drain "Service temporarily unavailable" 503' + newline,
]
expected = b"".join(lines[: index + 1] + block + lines[index + 1 :])
if candidate != expected:
    raise SystemExit("sealed Caddy candidate is not the exact deterministic drain transform")
PY
}

remote_verify_caddy_receipt_evidence() {
  local release_sha="$1"
  local run_id="$2"
  local expected_original="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
  local expected_candidate="${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}"
  local expected_diff="${V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256:-}"
  local expected_activation="${V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256:-}"
  local expected_hash
  for expected_hash in "${expected_original}" "${expected_candidate}" \
    "${expected_diff}" "${expected_activation}"; do
    [[ "${expected_hash}" =~ ^[0-9a-f]{64}$ ]] ||
      die 'immutable Caddy receipt evidence is unavailable'
  done
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'Caddy evidence root ownership or mode mismatch'
  local original="${evidence_root}/Caddyfile.original"
  local candidate="${evidence_root}/Caddyfile.drain"
  local diff_file="${evidence_root}/Caddyfile.drain.diff"
  local activation="${evidence_root}/activation.proof"
  remote_sudo_require_root_file "${original}" 600
  remote_sudo_require_root_file "${candidate}" 600
  remote_sudo_require_root_file "${diff_file}" 600
  remote_sudo_require_root_file "${activation}" 600
  [[ "$(sudo sha256sum "${original}" | awk '{print $1}')" == "${expected_original}" ]] ||
    die 'Caddy original differs from the immutable stage receipt'
  [[ "$(sudo sha256sum "${candidate}" | awk '{print $1}')" == "${expected_candidate}" ]] ||
    die 'Caddy candidate differs from the immutable stage receipt'
  [[ "$(sudo sha256sum "${diff_file}" | awk '{print $1}')" == "${expected_diff}" ]] ||
    die 'Caddy diff differs from the immutable stage receipt'
  [[ "$(sudo sha256sum "${activation}" | awk '{print $1}')" == "${expected_activation}" ]] ||
    die 'Caddy activation proof differs from the immutable stage receipt'
  [[ "$(remote_sudo_read_sha256_checksum "${original}.sha256")" == "${expected_original}" ]] ||
    die 'Caddy original sidecar differs from the immutable stage receipt'
  [[ "$(remote_sudo_read_sha256_checksum "${candidate}.sha256")" == "${expected_candidate}" ]] ||
    die 'Caddy candidate sidecar differs from the immutable stage receipt'
  [[ "$(remote_sudo_read_sha256_checksum "${activation}.sha256")" == "${expected_activation}" ]] ||
    die 'Caddy activation sidecar differs from the immutable stage receipt'
  remote_assert_caddy_candidate_derived "${original}" "${candidate}"
  sudo python3 - "${activation}" "${run_id}" "${release_sha}" \
    "${expected_original}" "${expected_candidate}" "${expected_diff}" <<'PY'
import re
import sys
proof_path, run_id, release_sha, original_sha, candidate_sha, diff_sha = sys.argv[1:]
parsed = {}
for row in open(proof_path, "rt", encoding="utf-8"):
    row = row.rstrip("\n")
    if "=" not in row:
        raise SystemExit("Caddy activation proof row mismatch")
    key, value = row.split("=", 1)
    if key in parsed:
        raise SystemExit("duplicate Caddy activation proof key")
    parsed[key] = value
expected_keys = {
    "run_id", "release_sha", "original_sha256", "candidate_sha256", "diff_sha256",
    "active_admin_config_sha256", "marker_present", "activation_reload",
}
if set(parsed) != expected_keys:
    raise SystemExit("Caddy activation proof schema mismatch")
fixed = {
    "run_id": run_id,
    "release_sha": release_sha,
    "original_sha256": original_sha,
    "candidate_sha256": candidate_sha,
    "diff_sha256": diff_sha,
    "marker_present": "false",
    "activation_reload": "PASS",
}
for key, value in fixed.items():
    if parsed[key] != value:
        raise SystemExit(f"Caddy activation proof mismatch: {key}")
if not re.fullmatch(r"[0-9a-f]{64}", parsed["active_admin_config_sha256"]):
    raise SystemExit("Caddy activation admin-config hash mismatch")
PY
}

remote_verify_partial_caddy_evidence() {
  local release_sha="$1"
  local run_id="$2"
  local expected_original="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256:-}"
  [[ "${expected_original}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'partial Caddy recovery lacks the immutable baseline Caddy identity'
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'partial Caddy evidence root ownership or mode mismatch'
  local original="${evidence_root}/Caddyfile.original"
  local candidate="${evidence_root}/Caddyfile.drain"
  remote_sudo_require_root_file "${original}" 600
  remote_sudo_require_root_file "${candidate}" 600
  local original_sha
  local candidate_sha
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')"
  candidate_sha="$(sudo sha256sum "${candidate}" | awk '{print $1}')"
  [[ "${original_sha}" == "${expected_original}" ]] ||
    die 'partial Caddy original differs from the immutable baseline receipt'
  [[ "$(remote_sudo_read_sha256_checksum "${original}.sha256")" == "${original_sha}" ]] ||
    die 'partial Caddy original checksum mismatch'
  [[ "$(remote_sudo_read_sha256_checksum "${candidate}.sha256")" == "${candidate_sha}" ]] ||
    die 'partial Caddy candidate checksum mismatch'
  remote_assert_caddy_candidate_derived "${original}" "${candidate}"
}

remote_caddy_activate() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  remote_require_absolute_path staging-path "${staging_path}"
  remote_require_run_id "${run_id}"
  remote_require_sha "${release_sha}"
  remote_require_run_root "${staging_path}" "${run_id}" >/dev/null
  remote_verify_baseline_authority "${staging_path}" "${run_id}" "${release_sha}"
  local baseline_caddy_sha="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256:-}"
  [[ "${baseline_caddy_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'baseline Caddy receipt identity is absent'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${baseline_caddy_sha}" ]] ||
    die 'active Caddyfile changed after the immutable baseline receipt'
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local evidence_base='/etc/caddy/v126-evidence'
  local evidence_release_root="${evidence_base}/${release_sha}"
  local artifact
  for artifact in "${evidence_base}" "${evidence_release_root}"; do
    sudo test ! -L "${artifact}"
    if sudo test -e "${artifact}"; then
      sudo test -d "${artifact}"
      sudo test ! -L "${artifact}"
      [[ "$(sudo stat -c '%a:%U:%G' "${artifact}")" == '700:root:root' ]] ||
        die 'Caddy evidence namespace parent ownership or mode mismatch'
    else
      sudo install -d -o root -g root -m 0700 "${artifact}"
    fi
  done
  sudo test ! -e "${evidence_root}"
  sudo test ! -L "${evidence_root}"
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644
  sudo test ! -e /etc/caddy/v126-drain.enabled
  sudo test ! -L /etc/caddy/v126-drain.enabled
  sudo install -d -o root -g root -m 0700 "${evidence_root}"
  local original="${evidence_root}/Caddyfile.original"
  local candidate="${evidence_root}/Caddyfile.drain"
  local diff_file="${evidence_root}/Caddyfile.drain.diff"
  sudo install -o root -g root -m 0600 /etc/caddy/Caddyfile "${original}"
  sudo python3 - "${original}" "${candidate}" <<'PY'
from pathlib import Path
import os
import sys

source_path = Path(sys.argv[1])
target_path = Path(sys.argv[2])
source = source_path.read_bytes()
if b"v126_staging_drain" in source or b"v126-drain.enabled" in source:
    raise SystemExit("drain block already exists in active Caddyfile")
lines = source.splitlines(keepends=True)
matches = [index for index, line in enumerate(lines) if line.strip() == b"staging.hookahtootah.club {"]
if len(matches) != 1:
    raise SystemExit("exact staging site block opening must appear once")
index = matches[0]
opening = lines[index]
newline = b"\r\n" if opening.endswith(b"\r\n") else b"\n"
indent = opening[: len(opening) - len(opening.lstrip())] + b"    "
block = [
    indent + b"@v126_staging_drain file {" + newline,
    indent + b"    root /" + newline,
    indent + b"    try_files /etc/caddy/v126-drain.enabled" + newline,
    indent + b"}" + newline,
    indent + b'respond @v126_staging_drain "Service temporarily unavailable" 503' + newline,
]
candidate = b"".join(lines[: index + 1] + block + lines[index + 1 :])
fd = os.open(target_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as handle:
    handle.write(candidate)
PY
  sudo chown root:root "${candidate}"
  sudo chmod 0600 "${candidate}"
  sudo install -o root -g root -m 0600 /dev/null "${diff_file}"
  local diff_status=0
  set +e
  sudo diff -u --label Caddyfile.original --label Caddyfile.drain "${original}" "${candidate}" \
    | sudo tee "${diff_file}" >/dev/null
  diff_status="${PIPESTATUS[0]}"
  set -e
  [[ "${diff_status}" == 1 ]] || die 'Caddy candidate diff must contain one bounded change'
  [[ "$(sudo awk 'NR > 2 && /^-/{count++} END {print count + 0}' "${diff_file}")" == 0 ]] ||
    die 'Caddy candidate removes lines'
  [[ "$(sudo awk 'NR > 2 && /^+/{count++} END {print count + 0}' "${diff_file}")" == 5 ]] ||
    die 'Caddy candidate must add exactly five lines'
  local added_lines
  added_lines="$(sudo awk 'NR > 2 && /^+/{sub(/^+[[:space:]]*/, ""); print}' "${diff_file}")"
  [[ "${added_lines}" == $'@v126_staging_drain file {\nroot /\ntry_files /etc/caddy/v126-drain.enabled\n}\nrespond @v126_staging_drain "Service temporarily unavailable" 503' ]] ||
    die 'Caddy candidate additions mismatch'
  sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null
  sudo caddy validate --config "${candidate}" --adapter caddyfile >/dev/null
  local original_sha
  local candidate_sha
  local diff_sha
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')"
  candidate_sha="$(sudo sha256sum "${candidate}" | awk '{print $1}')"
  diff_sha="$(sudo sha256sum "${diff_file}" | awk '{print $1}')"
  [[ "${original_sha}" == "${baseline_caddy_sha}" ]] ||
    die 'sealed Caddy original differs from the immutable baseline receipt'
  remote_sudo_require_root_file "${original}" 600
  remote_sudo_require_root_file "${candidate}" 600
  remote_sudo_require_root_file "${diff_file}" 600
  sudo test ! -e "${evidence_root}/Caddyfile.original.sha256"
  sudo test ! -L "${evidence_root}/Caddyfile.original.sha256"
  sudo test ! -e "${evidence_root}/Caddyfile.drain.sha256"
  sudo test ! -L "${evidence_root}/Caddyfile.drain.sha256"
  printf '%s\n' "${original_sha}" | sudo tee "${evidence_root}/Caddyfile.original.sha256" >/dev/null
  printf '%s\n' "${candidate_sha}" | sudo tee "${evidence_root}/Caddyfile.drain.sha256" >/dev/null
  sudo chown root:root "${evidence_root}/Caddyfile.original.sha256" "${evidence_root}/Caddyfile.drain.sha256"
  sudo chmod 0600 "${evidence_root}/Caddyfile.original.sha256" "${evidence_root}/Caddyfile.drain.sha256"
  [[ "$(remote_sudo_read_sha256_checksum "${evidence_root}/Caddyfile.original.sha256")" == "${original_sha}" ]] ||
    die 'Caddy original checksum write mismatch'
  [[ "$(remote_sudo_read_sha256_checksum "${evidence_root}/Caddyfile.drain.sha256")" == "${candidate_sha}" ]] ||
    die 'Caddy candidate checksum write mismatch'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${original_sha}" ]] ||
    die 'active Caddyfile changed during preparation'
  sudo install -o root -g root -m 0644 "${candidate}" /etc/caddy/Caddyfile
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${candidate_sha}" ]] ||
    die 'installed Caddy candidate hash mismatch'
  sudo systemctl reload caddy
  [[ "$(sudo systemctl is-active caddy)" == active ]] || die 'Caddy is not active after reload'
  sudo test ! -e /etc/caddy/v126-drain.enabled
  local admin_config
  admin_config="$(mktemp "${TMPDIR:-/tmp}/v126-caddy-admin.XXXXXX")"
  chmod 0600 "${admin_config}"
  remote_cleanup_caddy_admin_snapshot() {
    local cleanup_admin_config="$1"
    rm -f -- "${cleanup_admin_config}"
  }
  local cleanup_command
  printf -v cleanup_command 'remote_cleanup_caddy_admin_snapshot %q' "${admin_config}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  if ! curl -fsS http://127.0.0.1:2019/config/ > "${admin_config}" 2>/dev/null; then
    die 'Caddy admin config proof is unavailable after reload'
  fi
  python3 - "${admin_config}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
rendered = json.dumps(payload, sort_keys=True, separators=(",", ":"))
if rendered.count("/etc/caddy/v126-drain.enabled") != 1:
    raise SystemExit("active Caddy config lacks exact drain switch")
if rendered.count("Service temporarily unavailable") != 1 or '"status_code":503' not in rendered:
    raise SystemExit("active Caddy config lacks exact generic 503 response")
PY
  local admin_hash
  admin_hash="$(remote_hash_file "${admin_config}")"
  rm -f -- "${admin_config}"
  trap - EXIT INT TERM HUP
  local activation_proof="${evidence_root}/activation.proof"
  sudo test ! -e "${activation_proof}"
  sudo test ! -L "${activation_proof}"
  sudo test ! -e "${activation_proof}.sha256"
  sudo test ! -L "${activation_proof}.sha256"
  sudo sh -c 'umask 077; : > "$1"' sh "${activation_proof}"
  printf '%s\n' \
    "run_id=${run_id}" \
    "release_sha=${release_sha}" \
    "original_sha256=${original_sha}" \
    "candidate_sha256=${candidate_sha}" \
    "diff_sha256=${diff_sha}" \
    "active_admin_config_sha256=${admin_hash}" \
    'marker_present=false' \
    'activation_reload=PASS' | sudo tee "${activation_proof}" >/dev/null
  sudo chown root:root "${activation_proof}"
  sudo chmod 0600 "${activation_proof}"
  printf '%s\n' "$(sudo sha256sum "${activation_proof}" | awk '{print $1}')" | \
    sudo tee "${activation_proof}.sha256" >/dev/null
  sudo chown root:root "${activation_proof}.sha256"
  sudo chmod 0600 "${activation_proof}.sha256"
  remote_sudo_require_root_file "${activation_proof}" 600
  remote_sudo_read_sha256_checksum "${activation_proof}.sha256" >/dev/null
  remote_emit_artifact caddy-original "${original_sha}"
  remote_emit_artifact caddy-candidate "${candidate_sha}"
  remote_emit_artifact caddy-diff "${diff_sha}"
  remote_emit_artifact caddy-activation "$(sudo sha256sum "${activation_proof}" | awk '{print $1}')"
}

remote_initialize_compose() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local backend_image="$4"
  local expected_database_sha="${5:-}"
  local expected_identities_sha="${6:-}"
  remote_require_absolute_path staging-path "${staging_path}"
  remote_require_run_id "${run_id}"
  remote_require_sha "${release_sha}"
  [[ "${backend_image}" =~ ^[a-z0-9][a-z0-9._/-]*:[0-9a-f]{40}$ ]] ||
    die 'remote backend image tag must end in a full SHA'
  cd "${staging_path}"
  remote_require_operator_file .env 600
  remote_verify_baseline_authority "${staging_path}" "${run_id}" "${release_sha}" \
    "${expected_database_sha}" "${expected_identities_sha}"
  REMOTE_BACKEND_IMAGE="${backend_image}"
  remote_assert_compose_backend_image "${backend_image}"
}

remote_assert_caddy_candidate_active() {
  local release_sha="$1"
  local run_id="$2"
  remote_verify_caddy_receipt_evidence "${release_sha}" "${run_id}"
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == \
    "${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256}" ]] ||
    die 'active Caddy candidate differs from the immutable stage receipt'
  [[ "$(sudo systemctl is-active caddy)" == active ]] || die 'Caddy is not active'
}

remote_assert_public_live() {
  local target
  target="$(mktemp "${TMPDIR:-/tmp}/v126-public-live.XXXXXX")"
  chmod 0600 "${target}"
  local endpoint
  for endpoint in health db/health; do
    if ! curl -fsS "https://staging.hookahtootah.club/${endpoint}" > "${target}" 2>/dev/null; then
      rm -f -- "${target}"
      die "public ${endpoint} endpoint is unavailable"
    fi
    python3 - "${target}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
if payload != {"status": "ok"}:
    raise SystemExit("public health JSON mismatch")
PY
  done
  rm -f -- "${target}"
  curl -fsSI https://staging.hookahtootah.club/miniapp/ >/dev/null 2>&1 ||
    die 'public Mini App endpoint is unavailable'
}

remote_assert_protected_unauthenticated_503() {
  local response
  response="$(mktemp "${TMPDIR:-/tmp}/v126-protected-503.XXXXXX")"
  chmod 0600 "${response}"
  local status=0
  if status="$(curl -sS -o "${response}" -w '%{http_code}' \
    https://staging.hookahtootah.club/api/guest/_ping 2>/dev/null)"; then
    :
  else
    local curl_status=$?
    rm -f -- "${response}"
    die "protected unauthenticated probe failed before an HTTP response (curl ${curl_status})"
  fi
  [[ "${status}" == 503 ]] || {
    rm -f -- "${response}"
    die 'protected unauthenticated traffic did not return HTTP 503'
  }
  python3 - "${response}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
if set(payload) - {"error", "requestId"}:
    raise SystemExit("protected 503 envelope exposes unexpected fields")
error = payload.get("error")
if not isinstance(error, dict) or set(error) - {"code", "message", "details"}:
    raise SystemExit("protected 503 error envelope mismatch")
if error.get("code") != "SERVICE_UNAVAILABLE" or error.get("message") != "Service unavailable":
    raise SystemExit("protected 503 is not the generic maintenance denial")
if error.get("details") not in (None, {}):
    raise SystemExit("protected 503 exposes details")
PY
  rm -f -- "${response}"
}

remote_public_drain_on() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local backend_image="$4"
  local phase="$5"
  local expected_image_id="$6"
  remote_require_image_id "${expected_image_id}"
  remote_require_run_id "${run_id}"
  remote_require_sha "${release_sha}"
  [[ "${phase}" == initial || "${phase}" == reactivated ]] || die 'invalid public-drain phase'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${backend_image}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"
  sudo test ! -e /etc/caddy/v126-drain.enabled
  sudo test ! -L /etc/caddy/v126-drain.enabled
  if [[ "${phase}" == initial ]]; then
    sudo test -f "$(remote_caddy_evidence_root "${release_sha}" "${run_id}")/activation.proof"
    local backend_container
    remote_capture_compose_ids running backend
    (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
      die 'initial drain requires exactly one V125 backend'
    backend_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
    [[ "$(docker inspect --format '{{.Image}}' "${backend_container}")" == "${expected_image_id}" ]] ||
      die 'initial drain backend image identity mismatch'
    remote_require_unique_global_image_container "${V125_IMAGE_ID}" "${backend_container}"
    remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" 0
    [[ "$(remote_flyway_state)" == '125:0:0:0' ]] || die 'initial drain Flyway state mismatch'
  else
    remote_verify_proof "${run_root}/manual-smoke-passed.proof"
    remote_assert_runtime "${staging_path}" "${release_sha}" "${expected_image_id}" V126_SMOKE false
  fi
  sudo install -o root -g root -m 0600 /dev/null /etc/caddy/v126-drain.enabled
  remote_assert_caddy_drain_marker
  remote_assert_public_drain
  local proof_name='public-drain-active.proof'
  [[ "${phase}" == initial ]] || proof_name='public-drain-reactivated.proof'
  local proof="${run_root}/${proof_name}"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "phase=${phase}" \
    'http_status=503' 'body=Service temporarily unavailable' 'result=PASS'
  remote_emit_artifact "${proof_name%.proof}" "$(remote_hash_file "${proof}")"
}

remote_stop_backend() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local backend_image="$4"
  local phase="$5"
  local expected_image_id="$6"
  remote_require_image_id "${expected_image_id}"
  [[ "${phase}" == v125 || "${phase}" == v126-off-transition ]] || die 'invalid backend-stop phase'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${backend_image}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"
  remote_assert_caddy_drain_marker
  remote_assert_public_drain
  if [[ "${phase}" == v125 ]]; then
    remote_verify_proof "${run_root}/public-drain-active.proof"
  else
    remote_verify_proof "${run_root}/public-drain-reactivated.proof"
  fi
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'backend-stop stage requires exactly one running backend'
  local backend_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  [[ "$(docker inspect --format '{{.Image}}' "${backend_container}")" == "${expected_image_id}" ]] ||
    die 'backend-stop live image identity mismatch'
  if [[ "${phase}" == v125 ]]; then
    remote_require_unique_global_image_container "${V125_IMAGE_ID}" "${backend_container}"
    remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" 0
  else
    remote_require_global_image_count "${V125_IMAGE_ID}" 0
    remote_require_unique_global_image_container "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" "${backend_container}"
  fi
  remote_compose stop backend >/dev/null
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'backend remains running after stop'
  remote_require_global_image_count "${V125_IMAGE_ID}" 0
  remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" 0
  remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null
  local proof="${run_root}/${phase}-backend-stopped.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "phase=${phase}" \
    'backend_running_count=0' 'public_drain=PASS' 'result=PASS'
  remote_emit_artifact "${phase}-backend-stopped" "$(remote_hash_file "${proof}")"
}

remote_zero_writer_stage() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local backend_image="$4"
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${backend_image}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/v125-backend-stopped.proof"
  remote_assert_public_drain
  remote_assert_zero_writer '125:0:0'
  local proof="${run_root}/zero-writer-v125.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" 'flyway=125:0:0' \
    'backend=0' 'sessions=0' 'idle_in_transaction=0' 'prepared=0' 'slots=0' \
    'queues=0:0' 'result=PASS'
  remote_emit_artifact zero-writer-v125 "$(remote_hash_file "${proof}")"
}

remote_final_v125_preflight() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local backend_image="$4"
  local database_url_file="$5"
  local uploaded_script="$6"
  local expected_script_sha="$7"
  local expected_database_sha="$8"
  [[ "${expected_database_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid database URL baseline hash'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${backend_image}" \
    "${expected_database_sha}"
  remote_require_absolute_path database-url-file "${database_url_file}"
  remote_require_absolute_path uploaded-preflight "${uploaded_script}"
  [[ "${expected_script_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid preflight SHA-256'
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  [[ "${uploaded_script}" == "${run_root}/final-v125-preflight.sh.partial" ]] ||
    die 'preflight upload path is outside the sealed run namespace'
  [[ -f "${uploaded_script}" && ! -L "${uploaded_script}" ]] || die 'uploaded preflight is unavailable'
  [[ "$(stat -c '%a' "${uploaded_script}")" == 600 ]] || die 'uploaded preflight must be mode 0600'
  [[ "$(remote_hash_file "${uploaded_script}")" == "${expected_script_sha}" ]] ||
    die 'uploaded preflight checksum mismatch'
  remote_require_operator_file "${database_url_file}" 600
  [[ "$(remote_hash_file "${database_url_file}")" == "${expected_database_sha}" ]] ||
    die 'database URL binding changed after the baseline receipt'
  remote_verify_proof "${run_root}/quiesced-backup-rehearsed.proof"
  remote_assert_public_drain
  remote_assert_zero_writer '125:0:0'
  local sealed_script="${run_root}/final-v125-preflight.sh"
  [[ ! -e "${sealed_script}" && ! -L "${sealed_script}" ]] || die 'sealed preflight already exists'
  mv "${uploaded_script}" "${sealed_script}"
  chmod 0500 "${sealed_script}"
  local restricted_output="${run_root}/final-v125-preflight.output"
  [[ ! -e "${restricted_output}" && ! -L "${restricted_output}" ]] || die 'preflight output already exists'
  local service_file="${run_root}/final-v125-preflight.pg_service.conf"
  local pass_file="${run_root}/final-v125-preflight.pgpass"
  [[ ! -e "${service_file}" && ! -L "${service_file}" && ! -e "${pass_file}" && ! -L "${pass_file}" ]] ||
    die 'preflight database credential artifacts already exist'
  remote_cleanup_preflight_credentials() {
    local cleanup_service_file="$1"
    local cleanup_pass_file="$2"
    rm -f -- "${cleanup_service_file}" "${cleanup_pass_file}"
  }
  local cleanup_command
  printf -v cleanup_command 'remote_cleanup_preflight_credentials %q %q' \
    "${service_file}" "${pass_file}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  python3 - "${database_url_file}" "${expected_database_sha}" "${service_file}" "${pass_file}" <<'PY'
from hashlib import sha256
from pathlib import Path
from urllib.parse import parse_qsl, unquote, urlsplit
import os
import re
import sys

source_path, expected_sha, service_path, pass_path = sys.argv[1:]
raw = Path(source_path).read_bytes()
if not re.fullmatch(r"[0-9a-f]{64}", expected_sha) or sha256(raw).hexdigest() != expected_sha:
    raise SystemExit("database URL bytes differ from immutable authority at derivation")
if not raw or b"\x00" in raw or raw.count(b"\n") > 1 or (b"\n" in raw and not raw.endswith(b"\n")):
    raise SystemExit("database URL binding must contain exactly one nonempty line")
try:
    value = raw.rstrip(b"\r\n").decode("utf-8")
except UnicodeDecodeError:
    raise SystemExit("database URL binding is not UTF-8")
parsed = urlsplit(value)
if parsed.scheme not in ("postgres", "postgresql") or parsed.fragment:
    raise SystemExit("database URL binding is not an exact PostgreSQL URI")
if not parsed.hostname or not parsed.username or not parsed.path.startswith("/") or parsed.path.count("/") != 1:
    raise SystemExit("database URL binding lacks an exact host, user, or database")
try:
    port = parsed.port or 5432
except ValueError:
    raise SystemExit("database URL binding port is invalid")
host = unquote(parsed.hostname)
user = unquote(parsed.username)
password = unquote(parsed.password) if parsed.password is not None else None
database = unquote(parsed.path[1:])
if not database or password is None:
    raise SystemExit("database URL binding requires an explicit database and password")
allowed_options = {
    "application_name", "channel_binding", "connect_timeout", "gssencmode", "keepalives",
    "keepalives_count", "keepalives_idle", "keepalives_interval", "options", "sslcert",
    "sslcrl", "sslkey", "sslmode", "sslrootcert", "target_session_attrs",
}
options = {}
for key, option_value in parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True):
    if key not in allowed_options or key in options:
        raise SystemExit("database URL binding contains an unsupported or duplicate option")
    options[key] = option_value
for item in (host, user, password, database, *options.values()):
    if not item or any(ord(ch) < 32 or ord(ch) == 127 for ch in item):
        raise SystemExit("database URL binding contains an invalid empty or control value")
def service_quote(item):
    return "'" + item.replace("\\", "\\\\").replace("'", "\\'") + "'"
def pgpass_escape(item):
    return item.replace("\\", "\\\\").replace(":", "\\:")
pass_payload = ":".join(pgpass_escape(item) for item in (host, str(port), database, user, password)) + "\n"
fd = os.open(pass_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wt", encoding="utf-8", newline="") as handle:
    handle.write(pass_payload)
service = {
    "host": host,
    "port": str(port),
    "dbname": database,
    "user": user,
    "passfile": pass_path,
    **options,
}
lines = ["[v126_preflight]"] + [f"{key}={service_quote(service[key])}" for key in sorted(service)]
fd = os.open(service_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wt", encoding="utf-8", newline="") as handle:
    handle.write("\n".join(lines) + "\n")
PY
  remote_require_operator_file "${service_file}" 600
  remote_require_operator_file "${pass_file}" 600
  if ! python3 - "${sealed_script}" "${expected_script_sha}" "${restricted_output}" \
    "${service_file}" "${pass_file}" "${PATH:?}" "${HOME:?}" <<'PY'
from pathlib import Path
import hashlib
import os
import re
import stat
import subprocess
import sys

script_path, expected_sha, output_path, service_path, pass_path, path_value, home_value = sys.argv[1:]
script_stat = os.lstat(script_path)
if not stat.S_ISREG(script_stat.st_mode) or stat.S_ISLNK(script_stat.st_mode):
    raise SystemExit("sealed preflight source is not a regular file")
if stat.S_IMODE(script_stat.st_mode) != 0o500:
    raise SystemExit("sealed preflight source mode is not 0500")
script = Path(script_path).read_bytes()
if not re.fullmatch(r"[0-9a-f]{64}", expected_sha) or hashlib.sha256(script).hexdigest() != expected_sha:
    raise SystemExit("sealed preflight bytes differ from immutable release source")
fd = os.open(output_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as output:
    completed = subprocess.run(
        ["bash", "-s"],
        input=script,
        stdout=output,
        stderr=subprocess.STDOUT,
        env={
            "PATH": path_value,
            "HOME": home_value,
            "DATABASE_URL": "service=v126_preflight",
            "PGSERVICEFILE": service_path,
            "PGPASSFILE": pass_path,
        },
        check=False,
    )
raise SystemExit(completed.returncode)
PY
  then
    if [[ -f "${restricted_output}" && ! -L "${restricted_output}" ]]; then
      chmod 0600 "${restricted_output}"
    fi
    die 'final V125 booking-integrity preflight failed; restricted output retained'
  fi
  rm -f -- "${service_file}" "${pass_file}"
  trap - EXIT INT TERM HUP
  chmod 0600 "${restricted_output}"
  local output_sha
  output_sha="$(remote_hash_file "${restricted_output}")"
  rm -f -- "${restricted_output}"
  local proof="${run_root}/final-v125-preflight.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" \
    "script_sha256=${expected_script_sha}" "output_sha256=${output_sha}" \
    'database_target=EXPLICIT_REDACTED' 'flyway=125:0:0' 'result=PASS'
  remote_emit_artifact final-v125-preflight "$(remote_hash_file "${proof}")"
}

remote_transform_maintenance_config() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local backend_image="$4"
  local identities_file="$5"
  local target_mode="$6"
  local expected_identities_sha="$7"
  [[ "${target_mode}" == V126_SMOKE || "${target_mode}" == OFF ]] || die 'invalid maintenance target mode'
  [[ "${expected_identities_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid maintenance identity baseline hash'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${backend_image}" \
    '' "${expected_identities_sha}"
  remote_require_absolute_path maintenance-identities-file "${identities_file}"
  remote_require_operator_file "${identities_file}" 600
  [[ "$(remote_hash_file "${identities_file}")" == "${expected_identities_sha}" ]] ||
    die 'maintenance identities argument does not match the streamed baseline receipt'
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  if [[ "${target_mode}" == V126_SMOKE ]]; then
    [[ "$(remote_hash_file .env)" == "${V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256:-}" ]] ||
      die 'stage-9 environment input differs from the immutable baseline receipt'
  else
    remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
      "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256:-}"
  fi
  local expected_source_sha="${REMOTE_BOUND_ENV_SHA256}"
  [[ "${expected_source_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'maintenance transformation lacks an immutable source environment hash'
  remote_assert_caddy_drain_marker
  remote_assert_public_drain
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'maintenance configuration may change only with backend stopped'
  if [[ "${target_mode}" == V126_SMOKE ]]; then
    remote_verify_proof "${run_root}/final-v125-preflight.proof"
    remote_assert_zero_writer '125:0:0'
  else
    remote_verify_proof "${run_root}/v126-off-transition-backend-stopped.proof"
    remote_assert_zero_writer '126:1:0'
  fi
  local candidate="${run_root}/env.${target_mode}.candidate"
  local before="${run_root}/env.before-${target_mode}"
  local next_env="${run_root}/.env.next"
  [[ ! -e "${candidate}" && ! -L "${candidate}" && ! -e "${before}" && ! -L "${before}" && \
    ! -e "${next_env}" && ! -L "${next_env}" ]] ||
    die 'maintenance transformation artifacts already exist'
  remote_cleanup_maintenance_temporaries() {
    local cleanup_candidate="$1"
    local cleanup_before="$2"
    local cleanup_next_env="$3"
    rm -f -- "${cleanup_candidate}" "${cleanup_before}" "${cleanup_next_env}"
  }
  local cleanup_command
  printf -v cleanup_command 'remote_cleanup_maintenance_temporaries %q %q %q' \
    "${candidate}" "${before}" "${next_env}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  cp --preserve=mode,ownership,timestamps .env "${before}"
  chmod 0600 "${before}"
  python3 - "${before}" "${expected_source_sha}" "${identities_file}" \
    "${expected_identities_sha}" "${candidate}" "${target_mode}" <<'PY'
from pathlib import Path
import hashlib
import os
import re
import sys

source_path, expected_source_sha, identities_path, expected_identities_sha, target_path, mode = sys.argv[1:]
source = Path(source_path).read_bytes()
if hashlib.sha256(source).hexdigest() != expected_source_sha:
    raise SystemExit("maintenance source bytes differ from immutable authority at derivation")
if b"\x00" in source:
    raise SystemExit("NUL byte in environment file")
lines = source.splitlines(keepends=True)
keys = [
    b"STAGING_MAINTENANCE_MODE",
    b"STAGING_MAINTENANCE_ALLOWED_USER_IDS",
    b"STAGING_MAINTENANCE_ALLOWED_CHAT_IDS",
]
positions = {}
for key in keys:
    matches = [index for index, line in enumerate(lines) if line.split(b"=", 1)[0] == key]
    if len(matches) != 1:
        raise SystemExit(f"{key.decode()} must occur exactly once")
    positions[key] = matches[0]
values = {
    b"STAGING_MAINTENANCE_MODE": mode.encode(),
    b"STAGING_MAINTENANCE_ALLOWED_USER_IDS": b"",
    b"STAGING_MAINTENANCE_ALLOWED_CHAT_IDS": b"",
}
identity_raw = Path(identities_path).read_bytes()
if hashlib.sha256(identity_raw).hexdigest() != expected_identities_sha:
    raise SystemExit("maintenance identity bytes differ from immutable authority at derivation")
if mode == "V126_SMOKE":
    identity_lines = identity_raw.splitlines()
    if len(identity_lines) != 2:
        raise SystemExit("identity binding must contain exactly two lines")
    parsed = {}
    for line in identity_lines:
        if b"=" not in line:
            raise SystemExit("invalid identity binding")
        key, value = line.split(b"=", 1)
        if key in parsed or key not in keys[1:]:
            raise SystemExit("invalid or duplicate identity key")
        if not re.fullmatch(rb"-?[0-9]+(?:,-?[0-9]+)*", value):
            raise SystemExit("identity list must be an explicit comma-separated integer list")
        parsed[key] = value
    if set(parsed) != set(keys[1:]):
        raise SystemExit("both identity bindings are required")
    values.update(parsed)
for key, index in positions.items():
    newline = b"\r\n" if lines[index].endswith(b"\r\n") else b"\n"
    if not lines[index].endswith((b"\n", b"\r\n")):
        newline = b""
    lines[index] = key + b"=" + values[key] + newline
payload = b"".join(lines)
fd = os.open(target_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  chmod 0600 "${candidate}"
  if [[ "${target_mode}" == V126_SMOKE ]]; then
    STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
      "${staging_path}/scripts/check-staging-maintenance-config.sh" "${candidate}" >/dev/null
  else
    "${staging_path}/scripts/check-staging-maintenance-config.sh" "${candidate}" >/dev/null
  fi
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file "${candidate}" --compose-file docker-compose.yml >/dev/null
  [[ "$(remote_hash_file .env)" == "${expected_source_sha}" ]] ||
    die 'staging environment changed during maintenance transformation'
  install -m 0600 "${candidate}" "${next_env}"
  mv "${next_env}" .env
  if [[ "${target_mode}" == V126_SMOKE ]]; then
    STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
      "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  else
    "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  fi
  local before_sha
  local after_sha
  before_sha="$(remote_hash_file "${before}")"
  after_sha="$(remote_hash_file .env)"
  remote_cleanup_maintenance_temporaries "${candidate}" "${before}" "${next_env}"
  trap - EXIT INT TERM HUP
  local lower_mode
  lower_mode="$(printf '%s' "${target_mode}" | tr '[:upper:]' '[:lower:]')"
  local proof="${run_root}/maintenance-${lower_mode}.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "mode=${target_mode}" \
    "before_sha256=${before_sha}" "after_sha256=${after_sha}" \
    'identities=BOUND_REDACTED' 'unrelated_bytes=PRESERVED' 'result=PASS'
  remote_emit_artifact "maintenance-${lower_mode}" "$(remote_hash_file "${proof}")"
}

remote_read_maintenance_after_sha() {
  local run_root="$1"
  local run_id="$2"
  local release_sha="$3"
  local mode="$4"
  local expected_proof_sha="$5"
  [[ "${mode}" == V126_SMOKE || "${mode}" == OFF ]] || die 'invalid bound maintenance mode'
  [[ "${expected_proof_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'immutable maintenance receipt hash is unavailable'
  local lower_mode
  lower_mode="$(printf '%s' "${mode}" | tr '[:upper:]' '[:lower:]')"
  local proof="${run_root}/maintenance-${lower_mode}.proof"
  remote_verify_proof "${proof}"
  [[ "$(remote_hash_file "${proof}")" == "${expected_proof_sha}" ]] ||
    die 'maintenance proof differs from the immutable stage receipt'
  python3 - "${proof}" "${run_id}" "${release_sha}" "${mode}" <<'PY'
import re
import sys
proof_path, run_id, release_sha, mode = sys.argv[1:]
parsed = {}
for row in open(proof_path, "rt", encoding="utf-8"):
    row = row.rstrip("\n")
    if "=" not in row:
        raise SystemExit("maintenance proof row mismatch")
    key, value = row.split("=", 1)
    if key in parsed:
        raise SystemExit("duplicate maintenance proof key")
    parsed[key] = value
expected_keys = {
    "run_id", "release_sha", "mode", "before_sha256", "after_sha256",
    "identities", "unrelated_bytes", "result",
}
if set(parsed) != expected_keys:
    raise SystemExit("maintenance proof schema mismatch")
fixed = {
    "run_id": run_id,
    "release_sha": release_sha,
    "mode": mode,
    "identities": "BOUND_REDACTED",
    "unrelated_bytes": "PRESERVED",
    "result": "PASS",
}
for key, value in fixed.items():
    if parsed[key] != value:
        raise SystemExit(f"maintenance proof mismatch: {key}")
for key in ("before_sha256", "after_sha256"):
    if not re.fullmatch(r"[0-9a-f]{64}", parsed[key]):
        raise SystemExit(f"maintenance proof hash mismatch: {key}")
print(parsed["after_sha256"])
PY
}

remote_verify_partial_environment_transition() {
  local staging_path="$1"
  local identities_path="$2"
  local source_mode="$3"
  local target_mode="$4"
  local expected_source_sha="$5"
  local expected_identities_sha="$6"
  [[ "${source_mode}:${target_mode}" == OFF:V126_SMOKE || \
    "${source_mode}:${target_mode}" == V126_SMOKE:OFF ]] ||
    die 'invalid partial maintenance transition'
  [[ "${expected_source_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'partial maintenance transition lacks immutable source bytes'
  [[ "${expected_identities_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'partial maintenance transition lacks immutable identity bytes'
  local verified_target_sha
  verified_target_sha="$(python3 - "${staging_path}/.env" "${identities_path}" "${source_mode}" \
    "${target_mode}" "${expected_source_sha}" "${expected_identities_sha}" <<'PY'
from pathlib import Path
import hashlib
import re
import sys

env_path, identities_path, source_mode, target_mode, expected_source_sha, expected_identities_sha = sys.argv[1:]
raw = Path(env_path).read_bytes()
if b"\x00" in raw:
    raise SystemExit("NUL byte in partial maintenance environment")
lines = raw.splitlines(keepends=True)
keys = [
    b"STAGING_MAINTENANCE_MODE",
    b"STAGING_MAINTENANCE_ALLOWED_USER_IDS",
    b"STAGING_MAINTENANCE_ALLOWED_CHAT_IDS",
]
positions = {}
current = {}
for key in keys:
    matches = [index for index, line in enumerate(lines) if line.split(b"=", 1)[0] == key]
    if len(matches) != 1:
        raise SystemExit(f"{key.decode()} must occur exactly once in partial transition")
    index = matches[0]
    positions[key] = index
    current[key] = lines[index].split(b"=", 1)[1].rstrip(b"\r\n")
identity_raw = Path(identities_path).read_bytes()
if hashlib.sha256(identity_raw).hexdigest() != expected_identities_sha:
    raise SystemExit("partial transition identities differ from immutable authority at derivation")
identity_rows = identity_raw.splitlines()
identities = {}
for row in identity_rows:
    if b"=" not in row:
        raise SystemExit("partial transition identity row mismatch")
    key, value = row.split(b"=", 1)
    if key in identities or key not in keys[1:] or not re.fullmatch(rb"-?[0-9]+(?:,-?[0-9]+)*", value):
        raise SystemExit("partial transition identity binding mismatch")
    identities[key] = value
if set(identities) != set(keys[1:]):
    raise SystemExit("partial transition identity set mismatch")
values = {
    "OFF": {
        keys[0]: b"OFF",
        keys[1]: b"",
        keys[2]: b"",
    },
    "V126_SMOKE": {
        keys[0]: b"V126_SMOKE",
        keys[1]: identities[keys[1]],
        keys[2]: identities[keys[2]],
    },
}
if current != values[target_mode]:
    raise SystemExit("current environment is not the exact deterministic partial-transition target")
for key, index in positions.items():
    newline = b"\r\n" if lines[index].endswith(b"\r\n") else (b"\n" if lines[index].endswith(b"\n") else b"")
    lines[index] = key + b"=" + values[source_mode][key] + newline
reconstructed = b"".join(lines)
if hashlib.sha256(reconstructed).hexdigest() != expected_source_sha:
    raise SystemExit("partial transition does not reconstruct the immutable source environment")
print(hashlib.sha256(raw).hexdigest())
PY
  )" || die 'partial maintenance transition failed exact byte verification'
  [[ "${verified_target_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'partial maintenance target hash is invalid'
  if [[ "${target_mode}" == V126_SMOKE ]]; then
    STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
      "${staging_path}/scripts/check-staging-maintenance-config.sh" "${staging_path}/.env" >/dev/null
  else
    "${staging_path}/scripts/check-staging-maintenance-config.sh" "${staging_path}/.env" >/dev/null
  fi
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file "${staging_path}/.env" \
    --compose-file "${staging_path}/docker-compose.yml" >/dev/null
  [[ "$(remote_hash_file "${staging_path}/.env")" == "${verified_target_sha}" ]] ||
    die 'partial maintenance environment changed during verification'
  REMOTE_BOUND_ENV_SHA256="${verified_target_sha}"
}

remote_verify_maintenance_env_binding() {
  local staging_path="$1"
  local run_root="$2"
  local run_id="$3"
  local release_sha="$4"
  local mode="$5"
  local expected_proof_sha="$6"
  local after_sha
  after_sha="$(remote_read_maintenance_after_sha "${run_root}" "${run_id}" \
    "${release_sha}" "${mode}" "${expected_proof_sha}")" ||
    die 'maintenance proof failed strict verification'
  [[ "$(remote_hash_file "${staging_path}/.env")" == "${after_sha}" ]] ||
    die 'current staging environment differs from the immutable maintenance transform'
  if [[ "${mode}" == V126_SMOKE ]]; then
    STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
      "${staging_path}/scripts/check-staging-maintenance-config.sh" "${staging_path}/.env" >/dev/null
  else
    "${staging_path}/scripts/check-staging-maintenance-config.sh" "${staging_path}/.env" >/dev/null
  fi
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file "${staging_path}/.env" \
    --compose-file "${staging_path}/docker-compose.yml" >/dev/null
  REMOTE_BOUND_ENV_SHA256="${after_sha}"
}

remote_image_prepare() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  remote_require_image_id "${image_id}"
  [[ "${image_tag}" =~ :${release_sha}$ ]] || die 'V126 image tag does not match release SHA'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
    "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256:-}"
  remote_assert_public_drain
  remote_assert_zero_writer '125:0:0'
  [[ ! -e "${run_root}/v126-image.tar" && ! -L "${run_root}/v126-image.tar" ]] ||
    die 'remote V126 image archive already exists'
  [[ ! -e "${run_root}/v126-image.tar.partial" && ! -L "${run_root}/v126-image.tar.partial" ]] ||
    die 'remote partial V126 image archive already exists'
  local proof="${run_root}/v126-image-transfer-ready.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "image_tag=${image_tag}" \
    "expected_image_id=${image_id}" 'backend_running_count=0' 'result=PASS'
  remote_emit_artifact v126-image-transfer-ready "$(remote_hash_file "${proof}")"
}

remote_image_load() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  local archive_sha="$6"
  remote_require_image_id "${image_id}"
  [[ "${archive_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid V126 archive checksum'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/v126-image-transfer-ready.proof"
  remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
    "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256:-}"
  remote_assert_public_drain
  remote_assert_zero_writer '125:0:0'
  local partial="${run_root}/v126-image.tar.partial"
  local archive="${run_root}/v126-image.tar"
  [[ -f "${partial}" && ! -L "${partial}" && "$(stat -c '%a' "${partial}")" == 600 ]] ||
    die 'remote V126 partial archive must be a mode-0600 regular file'
  [[ ! -e "${archive}" && ! -L "${archive}" ]] || die 'remote V126 archive already sealed'
  local snapshot
  snapshot="$(mktemp "${run_root}/.v126-image-load-snapshot.XXXXXX")"
  chmod 0600 "${snapshot}"
  if ! snapshot_image_archive "${partial}" "${snapshot}"; then
    rm -f -- "${snapshot}"
    die 'remote V126 image archive could not be captured exactly'
  fi
  local archive_fd=9
  exec 9<"${snapshot}" || {
    rm -f -- "${snapshot}"
    die 'remote V126 image snapshot could not be opened'
  }
  rm -f -- "${snapshot}"
  local snapshot_sha
  if ! snapshot_sha="$(verify_saved_image_archive_fd \
    "${archive_fd}" "${image_tag}" "${image_id}")"; then
    exec 9>&-
    die 'remote V126 image snapshot structure or identity mismatch'
  fi
  [[ "${snapshot_sha}" == "${archive_sha}" ]] || {
    exec 9>&-
    die 'remote V126 archive checksum mismatch'
  }
  mv "${partial}" "${archive}"
  chmod 0400 "${archive}"
  [[ -f "${archive}" && ! -L "${archive}" && "$(stat -c '%a' "${archive}")" == 400 &&
    "$(remote_hash_file "${archive}")" == "${archive_sha}" ]] || {
    exec 9>&-
    die 'sealed remote V126 archive differs from the verified upload snapshot'
  }
  local load_output="${run_root}/v126-image-load.output"
  [[ ! -e "${load_output}" && ! -L "${load_output}" ]] || die 'image-load output already exists'
  if ! docker load <&"${archive_fd}" > "${load_output}" 2>&1; then
    exec 9>&-
    chmod 0600 "${load_output}"
    die 'Docker rejected the verified V126 image archive; restricted output retained'
  fi
  exec 9>&-
  chmod 0600 "${load_output}"
  local load_output_sha
  load_output_sha="$(remote_hash_file "${load_output}")"
  rm -f -- "${load_output}"
  [[ "$(docker image inspect --format '{{.Id}}' "${image_tag}")" == "${image_id}" ]] ||
    die 'remote loaded V126 image ID mismatch'
  [[ "${image_id}" == "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" ]] ||
    die 'loaded V126 image differs from the run manifest envelope'
  [[ -f "${archive}" && ! -L "${archive}" && "$(stat -c '%a' "${archive}")" == 400 &&
    "$(remote_hash_file "${archive}")" == "${archive_sha}" ]] ||
    die 'sealed remote V126 archive changed during exact-FD image load'
  local resolved_images
  if ! resolved_images="$(remote_compose config --images)"; then
    die 'Compose image inventory failed after V126 load'
  fi
  local resolved_count
  resolved_count="$(awk -v image="${image_tag}" '$0 == image {count++} END {print count + 0}' <<< "${resolved_images}")"
  [[ "${resolved_count}" == 1 ]] || die 'Compose does not resolve exactly one backend image to the V126 tag'
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'backend started during the image-transfer stage'
  local proof="${run_root}/v126-image-transferred.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "image_tag=${image_tag}" \
    "archive_sha256=${archive_sha}" "remote_image_id=${image_id}" \
    "load_output_sha256=${load_output_sha}" 'backend_running_count=0' 'result=PASS'
  remote_emit_artifact v126-image-archive "${archive_sha}"
  remote_emit_artifact v126-image-transferred "$(remote_hash_file "${proof}")"
}

remote_wait_backend_running() {
  local expected_container="$1"
  local attempt
  for attempt in $(seq 1 60); do
    remote_capture_compose_ids running backend
    if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) && \
      [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${expected_container}" ]]; then
      return 0
    fi
    sleep 1
  done
  die 'backend did not reach running state in 60 bounded attempts'
}

remote_assert_single_v126_backend_poller() {
  local expected_image_id="$1"
  remote_require_image_id "${expected_image_id}"
  local -a all_backend=()
  local -a running_backend=()
  remote_capture_compose_ids all backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'exactly one Compose backend container must exist'
  all_backend[0]="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'exactly one Compose backend container must run'
  running_backend[0]="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  [[ "${all_backend[0]}" == "${running_backend[0]}" ]] || die 'running backend is not the unique Compose backend'
  local backend_container="${running_backend[0]}"
  [[ "$(docker inspect --format '{{.Image}}' "${backend_container}")" == "${expected_image_id}" ]] ||
    die 'running backend image ID mismatch'
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${backend_container}")" == 'no:0' ]] ||
    die 'backend restart policy or RestartCount violates the single-start contract'
  docker exec "${backend_container}" sh -c \
    'test "${TELEGRAM_BOT_ENABLED:-}" = true && test "${TELEGRAM_BOT_MODE:-}" = long_polling' >/dev/null ||
    die 'the unique backend does not bind exactly one long-polling Telegram poller'
  remote_require_global_image_count "${V125_IMAGE_ID}" 0
  remote_require_unique_global_image_container "${expected_image_id}" "${backend_container}"
  local project_name
  project_name="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "${backend_container}")"
  [[ -n "${project_name}" && "${project_name}" != '<no value>' ]] || die 'backend Compose project label is absent'
  local old_backend_count=0
  local candidate
  remote_capture_docker_running_ids \
    --filter "label=com.docker.compose.project=${project_name}" \
    --filter 'label=com.docker.compose.service=backend'
  if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} > 0 )); then
    for candidate in "${REMOTE_CAPTURED_CONTAINER_IDS[@]}"; do
      if [[ "$(docker inspect --format '{{.Image}}' "${candidate}")" != "${expected_image_id}" ]]; then
        old_backend_count=$((old_backend_count + 1))
      fi
    done
  fi
  (( old_backend_count == 0 )) || die 'a live old-image backend remains in the staging Compose project'
}

remote_start_v126() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  local phase="$6"
  [[ "${phase}" == first || "${phase}" == final ]] || die 'invalid V126 start phase'
  remote_require_image_id "${image_id}"
  [[ "${image_id}" == "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" ]] ||
    die 'V126 startup image differs from the run manifest envelope'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/v126-image-transferred.proof"
  remote_assert_public_drain
  [[ "$(docker image inspect --format '{{.Id}}' "${image_tag}")" == "${image_id}" ]] ||
    die 'V126 image identity changed before startup'
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'backend is already running before separately gated startup'
  if [[ "${phase}" == first ]]; then
    remote_assert_zero_writer '125:0:0'
    remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
      "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256:-}"
  else
    remote_assert_zero_writer '126:1:0'
    remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
      "${release_sha}" OFF "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256:-}"
  fi
  remote_assert_compose_backend_image "${image_tag}"
  [[ "$(remote_hash_file .env)" == "${REMOTE_BOUND_ENV_SHA256}" ]] ||
    die 'staging environment changed between immutable verification and backend creation'
  remote_compose create --force-recreate --no-build --no-deps --pull never backend >/dev/null
  remote_capture_compose_ids all backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || die 'Compose did not create exactly one V126 backend'
  local backend_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'Compose create unexpectedly started the V126 backend'
  [[ "$(docker inspect --format '{{.Image}}' "${backend_container}")" == "${image_id}" ]] ||
    die 'created V126 backend image ID mismatch'
  [[ "$(remote_hash_file .env)" == "${REMOTE_BOUND_ENV_SHA256}" ]] ||
    die 'staging environment changed during V126 backend creation'
  remote_assert_bound_container_environment "${backend_container}" "${phase}"
  docker update --restart=no "${backend_container}" >/dev/null
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${backend_container}")" == 'no:0' ]] ||
    die 'V126 backend restart policy was not disabled before start'
  docker start "${backend_container}" >/dev/null
  remote_wait_backend_running "${backend_container}"
  remote_assert_single_v126_backend_poller "${image_id}"
  remote_assert_health_json http://127.0.0.1:8080/health
  remote_assert_version "${release_sha}"
  local proof="${run_root}/v126-backend-${phase}-started.proof"
  if [[ "${phase}" == final ]]; then
    remote_assert_runtime "${staging_path}" "${release_sha}" "${image_id}" OFF true
  fi
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "phase=${phase}" \
    "image_tag=${image_tag}" "image_id=${image_id}" 'compose_build=false' \
    'start_command_count=1' 'restart_policy=no' 'restart_count=0' \
    'backend_count=1' 'poller_count=1' 'live_v125_count=0' 'live_old_image_count=0' \
    'public_drain=PASS' 'result=PASS'
  remote_emit_artifact "v126-backend-${phase}-started" "$(remote_hash_file "${proof}")"
}

remote_schema_runtime_gate() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/v126-backend-first-started.proof"
  remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"
  remote_assert_public_drain
  remote_assert_runtime "${staging_path}" "${release_sha}" "${image_id}" V126_SMOKE true
  local proof="${run_root}/v126-schema-runtime.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" \
    "image_id=${image_id}" 'flyway=126:1:0' "flyway_checksum=${V126_FLYWAY_CHECKSUM}" \
    'backend_count=1' 'poller_count=1' 'live_v125_count=0' 'live_old_image_count=0' \
    'restart_policy=no' 'restart_count=0' \
    'schema=PASS' 'runtime=PASS' 'queues=0:0' 'telegram_idle=PASS' \
    'public_drain=PASS' 'result=PASS'
  remote_emit_artifact v126-schema-runtime "$(remote_hash_file "${proof}")"
}

remote_open_manual_smoke() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/v126-schema-runtime.proof"
  remote_assert_runtime "${staging_path}" "${release_sha}" "${image_id}" V126_SMOKE true
  remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"
  remote_assert_caddy_drain_marker
  sudo rm -f -- /etc/caddy/v126-drain.enabled
  sudo test ! -e /etc/caddy/v126-drain.enabled
  remote_assert_public_live
  remote_assert_protected_unauthenticated_503
  local proof="${run_root}/manual-smoke-window.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" 'maintenance=V126_SMOKE' \
    'schema_runtime_receipt=VERIFIED' 'public_window=OPEN' \
    'protected_unauthenticated=GENERIC_503' 'result=AUTHORIZED'
  remote_emit_artifact manual-smoke-window "$(remote_hash_file "${proof}")"
}

remote_record_manual_smoke() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  local evidence_sha="$6"
  [[ "${evidence_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid manual-smoke evidence hash'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/manual-smoke-window.proof"
  sudo test ! -e /etc/caddy/v126-drain.enabled
  sudo test ! -L /etc/caddy/v126-drain.enabled
  remote_assert_public_live
  remote_assert_protected_unauthenticated_503
  remote_assert_runtime "${staging_path}" "${release_sha}" "${image_id}" V126_SMOKE false
  local proof="${run_root}/manual-smoke-passed.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "evidence_sha256=${evidence_sha}" \
    'required_live_gate=HT14_MANDATORY_LIVE_GATE_GUEST_REPLY_OWNER_UNREAD_CLEAR' \
    'maintenance=V126_SMOKE' 'result=PASS'
  remote_emit_artifact manual-smoke-passed "$(remote_hash_file "${proof}")"
}

remote_restore_caddy() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/v126-backend-final-started.proof"
  remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
    "${release_sha}" OFF "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256:-}"
  remote_assert_runtime "${staging_path}" "${release_sha}" "${image_id}" OFF true
  remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"
  remote_assert_caddy_drain_marker
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local original="${evidence_root}/Caddyfile.original"
  local original_checksum="${evidence_root}/Caddyfile.original.sha256"
  remote_sudo_require_root_file "${original}" 600
  local original_sha
  local expected_original_sha
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')"
  expected_original_sha="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
  [[ "${original_sha}" == "${expected_original_sha}" ]] ||
    die 'original Caddyfile differs from the immutable stage receipt'
  [[ "$(remote_sudo_read_sha256_checksum "${original_checksum}")" == "${expected_original_sha}" ]] ||
    die 'original Caddyfile sidecar differs from the immutable stage receipt'
  sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null
  sudo install -o root -g root -m 0644 "${original}" /etc/caddy/Caddyfile
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${original_sha}" ]] ||
    die 'restored Caddyfile is not byte-identical to the original'
  sudo systemctl reload caddy
  [[ "$(sudo systemctl is-active caddy)" == active ]] || die 'Caddy is not active after ordinary restoration'
  sudo rm -f -- /etc/caddy/v126-drain.enabled
  sudo test ! -e /etc/caddy/v126-drain.enabled
  local admin_config
  admin_config="$(mktemp "${TMPDIR:-/tmp}/v126-caddy-restored.XXXXXX")"
  chmod 0600 "${admin_config}"
  remote_cleanup_restored_caddy_snapshot() {
    local cleanup_admin_config="$1"
    rm -f -- "${cleanup_admin_config}"
  }
  local cleanup_command
  printf -v cleanup_command 'remote_cleanup_restored_caddy_snapshot %q' "${admin_config}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  curl -fsS http://127.0.0.1:2019/config/ > "${admin_config}" 2>/dev/null || {
    die 'restored Caddy admin config proof is unavailable'
  }
  local grep_status=0
  if grep -Fq 'v126-drain.enabled' "${admin_config}"; then
    die 'restored active Caddy config still contains the drain matcher'
  else
    grep_status=$?
  fi
  [[ "${grep_status}" == 1 ]] || die 'restored Caddy admin config could not be inspected'
  local admin_sha
  admin_sha="$(remote_hash_file "${admin_config}")"
  rm -f -- "${admin_config}"
  trap - EXIT INT TERM HUP
  remote_assert_public_live
  local proof="${run_root}/ordinary-caddy-restored.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "original_sha256=${original_sha}" \
    "active_admin_config_sha256=${admin_sha}" 'maintenance=OFF' 'public=LIVE' \
    'byte_preserving=true' 'result=PASS'
  remote_emit_artifact ordinary-caddy-restored "$(remote_hash_file "${proof}")"
}

remote_final_public_gates() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  remote_verify_proof "${run_root}/ordinary-caddy-restored.proof"
  remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
    "${release_sha}" OFF "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256:-}"
  sudo test ! -e /etc/caddy/v126-drain.enabled
  sudo test ! -L /etc/caddy/v126-drain.enabled
  remote_assert_public_live
  remote_assert_runtime "${staging_path}" "${release_sha}" "${image_id}" OFF false
  local proof="${run_root}/final-public-gates.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "image_id=${image_id}" \
    'maintenance=OFF' 'flyway=126:1:0' "flyway_checksum=${V126_FLYWAY_CHECKSUM}" \
    'backend_count=1' 'poller_count=1' 'live_v125_count=0' 'live_old_image_count=0' \
    'restart_policy=no' 'restart_count=0' \
    'schema=PASS' 'runtime=PASS' 'queues=0:0' 'telegram_idle=PASS' \
    'public=LIVE' 'ordinary_caddy=PASS' 'result=PASS'
  remote_emit_artifact final-public-gates "$(remote_hash_file "${proof}")"
}

local_emit_artifact() {
  local name="$1"
  local digest="$2"
  [[ "${name}" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || die "invalid local artifact name: ${name}"
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die "invalid local artifact hash: ${name}"
  printf 'ARTIFACT\t%s\t%s\n' "${name}" "${digest}"
}

release_git() (
  local worktree="$1"
  shift
  local variable
  while IFS='=' read -r variable _; do
    case "${variable}" in
      GIT_*) unset "${variable}" ;;
    esac
  done < <(env)
  export GIT_NO_REPLACE_OBJECTS=1
  command git \
    -c core.fsmonitor=false \
    -c core.untrackedCache=false \
    -c core.hooksPath=/dev/null \
    -C "${worktree}" "$@"
)

git_object_sha256() {
  local worktree="$1"
  local object="$2"
  if command -v sha256sum >/dev/null 2>&1; then
    release_git "${worktree}" cat-file blob "${object}" | sha256sum | awk '{print $1}'
  else
    release_git "${worktree}" cat-file blob "${object}" | shasum -a 256 | awk '{print $1}'
  fi
}

flyway_checksum_from_git_object() {
  local worktree="$1"
  local object="$2"
  release_git "${worktree}" cat-file blob "${object}" | python3 -c \
    'import sys,zlib; value=0
for line in sys.stdin.buffer.read().splitlines(): value=zlib.crc32(line,value)
print(value if value < 2**31 else value-2**32)'
}

validate_main_actions_run() {
  local target="$1"
  python3 - "${target}" "${RELEASE_SHA}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    doc = json.load(handle)
required = {
    "name": "CI",
    "event": "push",
    "headBranch": "main",
    "headSha": sys.argv[2],
    "attempt": 1,
    "status": "completed",
    "conclusion": "success",
}
for key, value in required.items():
    if doc.get(key) != value:
        raise SystemExit(f"main Actions mismatch: {key}")
jobs = doc.get("jobs")
if not isinstance(jobs, list) or len(jobs) != 11:
    raise SystemExit("main Actions must contain exactly 11 jobs")
names = [job.get("name") for job in jobs]
if len(set(names)) != 11 or any(job.get("status") != "completed" or job.get("conclusion") != "success" for job in jobs):
    raise SystemExit("main Actions jobs are not 11/11 distinct completed successes")
PY
}

verify_release_baseline_local() {
  require_cmd git
  require_cmd gh
  require_cmd docker
  [[ -d "${RELEASE_WORKTREE}" && ! -L "${RELEASE_WORKTREE}" ]] || die 'release worktree is unavailable'
  release_git "${RELEASE_WORKTREE}" fetch --no-tags origin main
  local release_status=''
  if ! release_status="$(release_git "${RELEASE_WORKTREE}" status --porcelain=v1 --untracked-files=normal)"; then
    die 'release worktree cleanliness could not be determined'
  fi
  [[ -z "${release_status}" ]] ||
    die 'release worktree is not clean'
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify HEAD)" == "${RELEASE_SHA}" ]] || die 'release worktree HEAD mismatch'
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify origin/main)" == "${RELEASE_SHA}" ]] || die 'fresh origin/main mismatch'
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify "${RELEASE_SHA}^{tree}")" == "${RELEASE_TREE}" ]] || die 'release tree mismatch'
  local actual_parents
  actual_parents="$(release_git "${RELEASE_WORKTREE}" show -s --format='%P' "${RELEASE_SHA}" | tr ' ' ',')"
  [[ "${actual_parents}" == "${RELEASE_PARENTS}" ]] || die 'ordered release parents mismatch'
  local tracked_script_sha
  tracked_script_sha="$(git_object_sha256 "${RELEASE_WORKTREE}" "${RELEASE_SHA}:scripts/v126-cutover.sh")"
  [[ "${tracked_script_sha}" == "${SCRIPT_SHA256}" ]] ||
    die 'executing sequencer does not match the release-tracked sequencer identity'
  local actions_json="${STATE_DIR}/tmp/main-actions-${MAIN_ACTIONS_RUN_ID}.json"
  [[ ! -e "${actions_json}" && ! -L "${actions_json}" ]] || die 'main Actions temporary artifact exists'
  gh run view "${MAIN_ACTIONS_RUN_ID}" --json name,event,headBranch,headSha,attempt,status,conclusion,jobs > "${actions_json}"
  chmod 0600 "${actions_json}"
  validate_main_actions_run "${actions_json}"
  local actions_hash
  actions_hash="$(hash_file "${actions_json}")"
  rm -f -- "${actions_json}"

  local migration_root='backend/app/src/main/resources/db/migration'
  local pg_path="${migration_root}/postgresql/V126__support_thread_read_message_cursor.sql"
  local h2_path="${migration_root}/h2/V127__support_thread_read_message_cursor.sql"
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify "${RELEASE_SHA}:${migration_root}")" == "${V126_COMPLETE_MIGRATION_TREE}" ]] ||
    die 'complete migration tree mismatch'
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify "${RELEASE_SHA}:${migration_root}/postgresql")" == "${V126_POSTGRESQL_MIGRATION_TREE}" ]] ||
    die 'PostgreSQL migration tree mismatch'
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify "${RELEASE_SHA}:${migration_root}/h2")" == "${V126_H2_MIGRATION_TREE}" ]] ||
    die 'H2 migration tree mismatch'
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify "${RELEASE_SHA}:${pg_path}")" == "${V126_MIGRATION_BLOB}" ]] ||
    die 'PostgreSQL V126 blob mismatch'
  [[ "$(release_git "${RELEASE_WORKTREE}" rev-parse --verify "${RELEASE_SHA}:${h2_path}")" == "${V126_MIGRATION_BLOB}" ]] ||
    die 'H2 V127 blob mismatch'
  [[ "$(git_object_sha256 "${RELEASE_WORKTREE}" "${RELEASE_SHA}:${pg_path}")" == "${V126_MIGRATION_SHA256}" ]] ||
    die 'PostgreSQL V126 SHA-256 mismatch'
  [[ "$(git_object_sha256 "${RELEASE_WORKTREE}" "${RELEASE_SHA}:${h2_path}")" == "${V126_MIGRATION_SHA256}" ]] ||
    die 'H2 V127 SHA-256 mismatch'
  [[ "$(flyway_checksum_from_git_object "${RELEASE_WORKTREE}" "${RELEASE_SHA}:${pg_path}")" == "${V126_FLYWAY_CHECKSUM}" ]] ||
    die 'Flyway V126 checksum mismatch'
  [[ "$(docker image inspect --format '{{.Id}}' "${V126_IMAGE_TAG}")" == "${V126_IMAGE_ID}" ]] ||
    die 'local V126 image ID mismatch before any remote call'
  local record="${STATE_DIR}/tmp/local-baseline.proof"
  printf '%s\n' \
    "run_id=${RUN_ID}" "release_sha=${RELEASE_SHA}" "release_tree=${RELEASE_TREE}" \
    "release_parents=${RELEASE_PARENTS}" "main_actions_run_id=${MAIN_ACTIONS_RUN_ID}" \
    'main_actions_jobs=11/11' "migration_sha256=${V126_MIGRATION_SHA256}" \
    "flyway_checksum=${V126_FLYWAY_CHECKSUM}" "v126_image_id=${V126_IMAGE_ID}" 'result=PASS' > "${record}"
  chmod 0600 "${record}"
  local_emit_artifact local-baseline "$(hash_file "${record}")"
  local_emit_artifact main-actions "${actions_hash}"
  rm -f -- "${record}"
}

extract_booking_preflight() {
  local target="$1"
  release_git "${RELEASE_WORKTREE}" cat-file blob \
    "${RELEASE_SHA}:docs/DEPLOYMENT_RUNBOOK.md" | \
    python3 /dev/fd/3 "${RELEASE_SHA}" "${target}" 3<<'PY'
from hashlib import sha256
import os
import re
import sys
release_sha, target_path = sys.argv[1:]
if not re.fullmatch(r"[0-9a-f]{40}", release_sha):
    raise SystemExit("immutable preflight release SHA is invalid")
source = sys.stdin.buffer.read()
begin = b"<!-- BOOKING_UNREAD_PREFLIGHT_BEGIN -->"
end = b"<!-- BOOKING_UNREAD_PREFLIGHT_END -->"
if source.count(begin) != 1 or source.count(end) != 1:
    raise SystemExit("missing or duplicate booking preflight markers")
begin_at = source.index(begin) + len(begin)
end_at = source.index(end)
if begin_at >= end_at:
    raise SystemExit("booking preflight markers are reversed")
fence = b"`" * 3
marked = source[begin_at:end_at]
prefix = b"\n" + fence + b"bash\n"
suffix = fence + b"\n"
if not marked.startswith(prefix) or not marked.endswith(suffix) or marked.count(fence) != 2:
    raise SystemExit("booking preflight marker range is not one exact bash fence")
artifact = marked[len(prefix):-len(suffix)]
expected = (
    b"set -euo pipefail\n"
    b': "${DATABASE_URL:?DATABASE_URL must bind the exact target}"\n'
    b'psql "${DATABASE_URL}" -X --set=ON_ERROR_STOP=1 <<\'SQL\'\n'
)
if not artifact.startswith(expected):
    raise SystemExit("booking preflight lacks the exact fail-closed database binding")
if artifact.count(b"psql ") != 1 or b"pg_dump" in artifact or b"pg_restore" in artifact:
    raise SystemExit("booking preflight client surface mismatch")
fd = os.open(target_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as handle:
    handle.write(artifact)
print(sha256(artifact).hexdigest())
PY
}

validate_manual_smoke_evidence() {
  local source="$1"
  local destination="$2"
  [[ -f "${source}" && ! -L "${source}" ]] || die 'manual-smoke evidence must be a regular non-symlink file'
  python3 - "${source}" "${destination}" "${RUN_ID}" "${RELEASE_SHA}" <<'PY'
import json
import os
import stat
import sys
source, target, run_id, release_sha = sys.argv[1:]
mode = stat.S_IMODE(os.stat(source).st_mode)
if mode not in (0o400, 0o600):
    raise SystemExit("manual-smoke evidence must be mode 0400 or 0600")
raw = open(source, "rb").read()
doc = json.loads(raw)
assertion_names = [
    "MATRIX_GUEST",
    "MATRIX_OWNER",
    "MATRIX_MIX",
    "MATRIX_MIX_STAFF_CHAT",
    "TENANT_RBAC_NEGATIVES",
    "LINKED_STAFF_CHAT_DELIVERY",
    "NULL_AUTHOR_UNREAD_CREATE_CLEAR_RESURRECT",
    "WRONG_SURFACE_MARKERS_UNCHANGED",
    "EXACT_SURFACE_ONLY_CLEAR",
    "SUPPORT_CONVERSATIONS_SEPARATION",
    "LABEL_COLLISION",
    "LIVE_ONE_GUEST_REPLY",
    "LIVE_ONE_PERSISTED_GUEST_MESSAGE",
    "LIVE_EXACTLY_ONE_TELEGRAM_OUTBOX_DELIVERY",
    "LIVE_OWNER_EXACT_THREAD_UNREAD_CREATED_AND_CLEARED",
    "LIVE_NO_DUPLICATE_OR_RESURRECTED_MARKER",
    "LIVE_NO_OTHER_THREAD_CLIENT_OR_NON_MIX_MUTATION",
]
expected = {
    "assertions": {name: "PASS" for name in assertion_names},
    "format_version": 1,
    "release_sha": release_sha,
    "result_category": "PASS",
    "run_id": run_id,
}
if doc != expected:
    raise SystemExit("manual-smoke evidence schema or identity mismatch")
canonical = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
if raw != canonical:
    raise SystemExit("manual-smoke evidence is not canonical JSON")
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(raw)
PY
}

# V126_STAGE_BASELINE_VERIFIED_BEGIN
stage_baseline_verified() {
  verify_release_baseline_local
  local compose_sha
  local maintenance_sha
  local admission_sha
  compose_sha="$(git_object_sha256 "${RELEASE_WORKTREE}" "${RELEASE_SHA}:docker-compose.yml")"
  maintenance_sha="$(git_object_sha256 "${RELEASE_WORKTREE}" \
    "${RELEASE_SHA}:scripts/check-staging-maintenance-config.sh")"
  admission_sha="$(git_object_sha256 "${RELEASE_WORKTREE}" \
    "${RELEASE_SHA}:scripts/validate-staging-admission.sh")"
  run_remote baseline "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V125_IMAGE_TAG}" \
    "${DATABASE_URL_FILE}" "${MAINTENANCE_IDENTITIES_FILE}" \
    "${compose_sha}" "${maintenance_sha}" "${admission_sha}"
}
# V126_STAGE_BASELINE_VERIFIED_END

# V126_STAGE_PRE_DRAIN_BACKUP_REHEARSED_BEGIN
stage_pre_drain_backup_rehearsed() {
  run_remote backup-rehearsal "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" pre-drain "${V125_IMAGE_TAG}"
}
# V126_STAGE_PRE_DRAIN_BACKUP_REHEARSED_END

# V126_STAGE_CADDY_CANDIDATE_INSTALLED_AND_RELOADED_BEGIN
stage_caddy_candidate_installed_and_reloaded() {
  run_remote caddy-activate "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}"
}
# V126_STAGE_CADDY_CANDIDATE_INSTALLED_AND_RELOADED_END

# V126_STAGE_PUBLIC_DRAIN_ACTIVE_BEGIN
stage_public_drain_active() {
  run_remote public-drain-on "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V125_IMAGE_TAG}" initial "${V125_IMAGE_ID}"
}
# V126_STAGE_PUBLIC_DRAIN_ACTIVE_END

# V126_STAGE_V125_BACKEND_STOPPED_BEGIN
stage_v125_backend_stopped() {
  run_remote stop-backend "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V125_IMAGE_TAG}" v125 "${V125_IMAGE_ID}"
}
# V126_STAGE_V125_BACKEND_STOPPED_END

# V126_STAGE_ZERO_WRITER_GATE_PASSED_BEGIN
stage_zero_writer_gate_passed() {
  run_remote zero-writer "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V125_IMAGE_TAG}"
}
# V126_STAGE_ZERO_WRITER_GATE_PASSED_END

# V126_STAGE_QUIESCED_BACKUP_REHEARSED_BEGIN
stage_quiesced_backup_rehearsed() {
  run_remote backup-rehearsal "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" quiesced "${V125_IMAGE_TAG}"
}
# V126_STAGE_QUIESCED_BACKUP_REHEARSED_END

# V126_STAGE_FINAL_V125_PREFLIGHT_PASSED_BEGIN
stage_final_v125_preflight_passed() {
  local extracted="${STATE_DIR}/tmp/final-v125-preflight.sh"
  local script_sha
  [[ ! -e "${extracted}" && ! -L "${extracted}" ]] || die 'local preflight artifact exists'
  script_sha="$(extract_booking_preflight "${extracted}")"
  chmod 0600 "${extracted}"
  local remote_target="${STAGING_PATH}/.v126-runs/${RUN_ID}/final-v125-preflight.sh.partial"
  run_tracked_command remote-rsync rsync --archive --chmod=Fu=rw,Fgo= \
    "${extracted}" "${REMOTE}:${remote_target}"
  local database_binding_sha
  database_binding_sha="$(receipt_artifact_hash BASELINE_VERIFIED database-url-binding)"
  local_emit_artifact final-v125-preflight-source "${script_sha}"
  run_remote final-v125-preflight "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V125_IMAGE_TAG}" \
    "${DATABASE_URL_FILE}" "${remote_target}" "${script_sha}" "${database_binding_sha}"
  rm -f -- "${extracted}"
}
# V126_STAGE_FINAL_V125_PREFLIGHT_PASSED_END

# V126_STAGE_V126_MAINTENANCE_CONFIG_PREPARED_BEGIN
stage_v126_maintenance_config_prepared() {
  local identities_sha
  identities_sha="$(receipt_artifact_hash BASELINE_VERIFIED maintenance-identities)"
  run_remote transform-maintenance "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V125_IMAGE_TAG}" \
    "${MAINTENANCE_IDENTITIES_FILE}" V126_SMOKE "${identities_sha}"
}
# V126_STAGE_V126_MAINTENANCE_CONFIG_PREPARED_END

# V126_STAGE_V126_IMAGE_TRANSFERRED_AND_VERIFIED_BEGIN
stage_v126_image_transferred_and_verified() {
  require_cmd docker
  require_cmd rsync
  local actual_image_id
  actual_image_id="$(docker image inspect --format '{{.Id}}' "${V126_IMAGE_TAG}")"
  [[ "${actual_image_id}" == "${V126_IMAGE_ID}" ]] ||
    die 'local V126 image ID mismatch before remote mutation'
  local archive="${STATE_DIR}/tmp/v126-image.tar"
  local snapshot
  [[ ! -e "${archive}" && ! -L "${archive}" ]] || die 'local V126 image archive exists'
  docker save --output "${archive}" "${V126_IMAGE_TAG}"
  chmod 0600 "${archive}"
  snapshot="$(mktemp "${STATE_DIR}/tmp/v126-image-transfer-snapshot.XXXXXX")"
  chmod 0600 "${snapshot}"
  if ! snapshot_image_archive "${archive}" "${snapshot}"; then
    rm -f -- "${snapshot}" "${archive}"
    die 'local V126 image archive could not be captured exactly'
  fi
  local archive_fd=9
  exec 9<"${snapshot}" || {
    rm -f -- "${snapshot}" "${archive}"
    die 'local V126 image snapshot could not be opened'
  }
  rm -f -- "${snapshot}" "${archive}"
  local archive_sha
  if ! archive_sha="$(verify_saved_image_archive_fd \
    "${archive_fd}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}")"; then
    exec 9>&-
    die 'local V126 image snapshot structure or identity mismatch before remote mutation'
  fi
  run_remote image-prepare "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}"
  local remote_target="${STAGING_PATH}/.v126-runs/${RUN_ID}/v126-image.tar.partial"
  run_tracked_command remote-rsync rsync --archive --copy-links --chmod=Fu=rw,Fgo= \
    "/dev/fd/${archive_fd}" "${REMOTE}:${remote_target}"
  run_remote image-load "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" \
    "${V126_IMAGE_ID}" "${archive_sha}"
  local_emit_artifact local-v126-image-archive "${archive_sha}"
  exec 9>&-
}
# V126_STAGE_V126_IMAGE_TRANSFERRED_AND_VERIFIED_END

# V126_STAGE_V126_BACKEND_STARTED_BEGIN
stage_v126_backend_started() {
  run_remote start-v126 "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}" first
}
# V126_STAGE_V126_BACKEND_STARTED_END

# V126_STAGE_V126_SCHEMA_RUNTIME_GATE_PASSED_BEGIN
stage_v126_schema_runtime_gate_passed() {
  run_remote schema-runtime-gate "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}"
}
# V126_STAGE_V126_SCHEMA_RUNTIME_GATE_PASSED_END

# V126_STAGE_MANUAL_SMOKE_AUTHORIZED_BEGIN
stage_manual_smoke_authorized() {
  local handoff="${STATE_DIR}/artifacts/manual-smoke-handoff.json"
  [[ ! -e "${handoff}" && ! -L "${handoff}" ]] || die 'manual-smoke handoff already exists'
  python3 - "${handoff}" "${RUN_ID}" "${RELEASE_SHA}" <<'PY'
import json
import os
import sys
target, run_id, release_sha = sys.argv[1:]
required_assertions = [
    "MATRIX_GUEST",
    "MATRIX_OWNER",
    "MATRIX_MIX",
    "MATRIX_MIX_STAFF_CHAT",
    "TENANT_RBAC_NEGATIVES",
    "LINKED_STAFF_CHAT_DELIVERY",
    "NULL_AUTHOR_UNREAD_CREATE_CLEAR_RESURRECT",
    "WRONG_SURFACE_MARKERS_UNCHANGED",
    "EXACT_SURFACE_ONLY_CLEAR",
    "SUPPORT_CONVERSATIONS_SEPARATION",
    "LABEL_COLLISION",
    "LIVE_ONE_GUEST_REPLY",
    "LIVE_ONE_PERSISTED_GUEST_MESSAGE",
    "LIVE_EXACTLY_ONE_TELEGRAM_OUTBOX_DELIVERY",
    "LIVE_OWNER_EXACT_THREAD_UNREAD_CREATED_AND_CLEARED",
    "LIVE_NO_DUPLICATE_OR_RESURRECTED_MARKER",
    "LIVE_NO_OTHER_THREAD_CLIENT_OR_NON_MIX_MUTATION",
]
doc = {
    "format_version": 1,
    "release_sha": release_sha,
    "required_assertions": required_assertions,
    "run_id": run_id,
    "status": "AWAITING_MANUAL_EVIDENCE",
}
payload = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  local_emit_artifact manual-smoke-handoff "$(hash_file "${handoff}")"
  run_remote open-manual-smoke "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}"
}
# V126_STAGE_MANUAL_SMOKE_AUTHORIZED_END

# V126_STAGE_MANUAL_SMOKE_PASSED_BEGIN
stage_manual_smoke_passed() {
  local evidence_file="$1"
  local sealed="${STATE_DIR}/artifacts/manual-smoke-evidence.json"
  validate_manual_smoke_evidence "${evidence_file}" "${sealed}"
  local evidence_sha
  evidence_sha="$(hash_file "${sealed}")"
  local_emit_artifact manual-smoke-evidence "${evidence_sha}"
  run_remote record-manual-smoke "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" \
    "${V126_IMAGE_ID}" "${evidence_sha}"
}
# V126_STAGE_MANUAL_SMOKE_PASSED_END

# V126_STAGE_PUBLIC_DRAIN_REACTIVATED_BEGIN
stage_public_drain_reactivated() {
  run_remote public-drain-on "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" reactivated "${V126_IMAGE_ID}"
}
# V126_STAGE_PUBLIC_DRAIN_REACTIVATED_END

# V126_STAGE_V126_BACKEND_STOPPED_FOR_OFF_TRANSITION_BEGIN
stage_v126_backend_stopped_for_off_transition() {
  run_remote stop-backend "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" v126-off-transition "${V126_IMAGE_ID}"
}
# V126_STAGE_V126_BACKEND_STOPPED_FOR_OFF_TRANSITION_END

# V126_STAGE_MAINTENANCE_OFF_CONFIG_VERIFIED_BEGIN
stage_maintenance_off_config_verified() {
  local identities_sha
  identities_sha="$(receipt_artifact_hash BASELINE_VERIFIED maintenance-identities)"
  run_remote transform-maintenance "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" \
    "${MAINTENANCE_IDENTITIES_FILE}" OFF "${identities_sha}"
}
# V126_STAGE_MAINTENANCE_OFF_CONFIG_VERIFIED_END

# V126_STAGE_FINAL_V126_BACKEND_STARTED_BEGIN
stage_final_v126_backend_started() {
  run_remote start-v126 "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}" final
}
# V126_STAGE_FINAL_V126_BACKEND_STARTED_END

# V126_STAGE_ORDINARY_CADDY_RESTORED_BEGIN
stage_ordinary_caddy_restored() {
  run_remote restore-caddy "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}"
}
# V126_STAGE_ORDINARY_CADDY_RESTORED_END

# V126_STAGE_FINAL_PUBLIC_GATES_PASSED_BEGIN
stage_final_public_gates_passed() {
  run_remote final-public-gates "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}"
}
# V126_STAGE_FINAL_PUBLIC_GATES_PASSED_END

remote_flyway_state() {
  remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  COALESCE(MAX(version::integer), 0), ':',
  COUNT(*) FILTER (WHERE version = '126'), ':',
  COUNT(*) FILTER (WHERE version = '126' AND success AND checksum = 1701638026), ':',
  COUNT(*) FILTER (WHERE NOT success)
)
FROM flyway_schema_history;
SQL
}

remote_recovery_product_off() {
  local staging_path="$1"
  local run_root="$2"
  local expected_source_sha="${REMOTE_BOUND_ENV_SHA256}"
  [[ "${expected_source_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'recovery PRODUCT/OFF transformation lacks an immutable source environment hash'
  local candidate="${run_root}/recovery-product-off.env"
  local before="${run_root}/recovery-env.before"
  local next_env="${run_root}/recovery-env.next"
  [[ ! -e "${candidate}" && ! -L "${candidate}" && ! -e "${before}" && ! -L "${before}" && \
    ! -e "${next_env}" && ! -L "${next_env}" ]] ||
    die 'recovery environment artifact exists'
  remote_cleanup_recovery_env_temporaries() {
    local cleanup_candidate="$1"
    local cleanup_before="$2"
    local cleanup_next_env="$3"
    rm -f -- "${cleanup_candidate}" "${cleanup_before}" "${cleanup_next_env}"
  }
  local cleanup_command
  printf -v cleanup_command 'remote_cleanup_recovery_env_temporaries %q %q %q' \
    "${candidate}" "${before}" "${next_env}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  cp --preserve=mode,ownership,timestamps .env "${before}"
  chmod 0600 "${before}"
  python3 - "${before}" "${expected_source_sha}" "${candidate}" <<'PY'
from pathlib import Path
import hashlib
import os
import sys
source_path, expected_source_sha, target_path = sys.argv[1:]
source = Path(source_path).read_bytes()
if hashlib.sha256(source).hexdigest() != expected_source_sha:
    raise SystemExit("recovery environment source differs from immutable authority at derivation")
lines = source.splitlines(keepends=True)
values = {
    b"TELEGRAM_TRAFFIC_POLICY": b"PRODUCT",
    b"TELEGRAM_ALLOWED_USER_IDS": b"",
    b"TELEGRAM_ALLOWED_CHAT_IDS": b"",
    b"STAGING_MAINTENANCE_MODE": b"OFF",
    b"STAGING_MAINTENANCE_ALLOWED_USER_IDS": b"",
    b"STAGING_MAINTENANCE_ALLOWED_CHAT_IDS": b"",
}
for key, value in values.items():
    matches = [index for index, line in enumerate(lines) if line.split(b"=", 1)[0] == key]
    if len(matches) != 1:
        raise SystemExit(f"{key.decode()} must occur exactly once")
    index = matches[0]
    newline = b"\r\n" if lines[index].endswith(b"\r\n") else (b"\n" if lines[index].endswith(b"\n") else b"")
    lines[index] = key + b"=" + value + newline
payload = b"".join(lines)
fd = os.open(target_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  chmod 0600 "${candidate}"
  "${staging_path}/scripts/check-staging-maintenance-config.sh" "${candidate}" >/dev/null
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file "${candidate}" --compose-file docker-compose.yml >/dev/null
  local before_sha
  before_sha="$(remote_hash_file "${before}")"
  [[ "$(remote_hash_file .env)" == "${expected_source_sha}" ]] ||
    die 'staging environment changed during recovery PRODUCT/OFF transformation'
  install -m 0600 "${candidate}" "${next_env}"
  mv "${next_env}" .env
  "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file .env --compose-file docker-compose.yml >/dev/null
  local after_sha
  after_sha="$(remote_hash_file .env)"
  remote_cleanup_recovery_env_temporaries "${candidate}" "${before}" "${next_env}"
  trap - EXIT INT TERM HUP
  REMOTE_RECOVERY_ENV_BEFORE_SHA256="${before_sha}"
  REMOTE_RECOVERY_ENV_AFTER_SHA256="${after_sha}"
}

remote_assert_bound_container_environment() {
  local container_id="$1"
  local phase="$2"
  [[ "${phase}" == baseline || "${phase}" == first || "${phase}" == final || \
    "${phase}" == pre-v126 ]] || die 'invalid bound-container environment verification phase'
  if ! docker inspect --format '{{json .Config.Env}}' "${container_id}" | python3 -c '
import json
from pathlib import Path
import sys

env_path, phase = sys.argv[1:]
critical_keys = {
    "TELEGRAM_BOT_ENABLED",
    "TELEGRAM_BOT_MODE",
    "TELEGRAM_TRAFFIC_POLICY",
    "TELEGRAM_ALLOWED_USER_IDS",
    "TELEGRAM_ALLOWED_CHAT_IDS",
    "STAGING_MAINTENANCE_MODE",
    "STAGING_MAINTENANCE_ALLOWED_USER_IDS",
    "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS",
}
env_values = {}
for raw_row in Path(env_path).read_bytes().splitlines():
    if b"=" not in raw_row:
        continue
    raw_key, raw_value = raw_row.split(b"=", 1)
    try:
        key = raw_key.decode("ascii")
        value = raw_value.decode("utf-8")
    except UnicodeDecodeError:
        raise SystemExit("critical environment encoding is invalid")
    if key in critical_keys:
        env_values.setdefault(key, []).append(value)
if set(env_values) != critical_keys or any(len(values) != 1 for values in env_values.values()):
    raise SystemExit("critical staging environment inventory is invalid")
expected = {key: values[0] for key, values in env_values.items()}
fixed = {
    "TELEGRAM_BOT_ENABLED": "true",
    "TELEGRAM_BOT_MODE": "long_polling",
    "TELEGRAM_TRAFFIC_POLICY": "PRODUCT",
    "TELEGRAM_ALLOWED_USER_IDS": "",
    "TELEGRAM_ALLOWED_CHAT_IDS": "",
}
if phase == "first":
    fixed["STAGING_MAINTENANCE_MODE"] = "V126_SMOKE"
    if not expected["STAGING_MAINTENANCE_ALLOWED_USER_IDS"] or not expected["STAGING_MAINTENANCE_ALLOWED_CHAT_IDS"]:
        raise SystemExit("V126_SMOKE identities are empty")
else:
    fixed.update({
        "STAGING_MAINTENANCE_MODE": "OFF",
        "STAGING_MAINTENANCE_ALLOWED_USER_IDS": "",
        "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS": "",
    })
for key, value in fixed.items():
    if expected[key] != value:
        raise SystemExit(f"staging environment mismatch: {key}")
rows = json.load(sys.stdin)
if not isinstance(rows, list) or any(not isinstance(row, str) or "=" not in row for row in rows):
    raise SystemExit("container environment inventory is invalid")
values = {}
for row in rows:
    key, value = row.split("=", 1)
    values.setdefault(key, []).append(value)
for key, expected_value in expected.items():
    if values.get(key) != [expected_value]:
        raise SystemExit(f"pre-start container environment mismatch: {key}")
' .env "${phase}"; then
    die 'backend container does not have the exact bound traffic/maintenance/poller environment'
  fi
}

remote_assert_v125_runtime() {
  local staging_path="$1"
  local image_tag="$2"
  local backend_container
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'V125 recovery backend count is not one'
  backend_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  remote_capture_compose_ids all backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'V125 recovery has an extra stopped or running backend container'
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${backend_container}" ]] ||
    die 'V125 recovery running backend is not the unique Compose backend'
  [[ "$(docker inspect --format '{{.Image}}' "${backend_container}")" == "${V125_IMAGE_ID}" ]] ||
    die 'V125 recovery running image ID mismatch'
  [[ "$(docker image inspect --format '{{.Id}}' "${image_tag}")" == "${V125_IMAGE_ID}" ]] ||
    die 'V125 recovery loaded image ID mismatch'
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${backend_container}")" == 'no:0' ]] ||
    die 'V125 recovery restart policy or RestartCount mismatch'
  docker exec "${backend_container}" sh -c \
    'test "${TELEGRAM_BOT_ENABLED:-}" = true && test "${TELEGRAM_BOT_MODE:-}" = long_polling' >/dev/null ||
    die 'V125 recovery does not have the unique long-polling Telegram poller configuration'
  remote_require_unique_global_image_container "${V125_IMAGE_ID}" "${backend_container}"
  remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" 0
  remote_assert_health_json http://127.0.0.1:8080/health
  remote_assert_health_json http://127.0.0.1:8080/db/health
  remote_assert_version "${V125_SOURCE_SHA}"
  "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file .env --compose-file docker-compose.yml >/dev/null
  [[ "$(remote_flyway_state)" == '125:0:0:0' ]] || die 'V125 recovery Flyway state mismatch'
  local queues
  queues="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')), ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING'))
);
SQL
)"
  [[ "${queues}" == '0:0' ]] || die 'V125 recovery queue gate mismatch'
  remote_assert_telegram_idle .env
  remote_assert_public_drain
}

remote_recovery_restore_original_caddy() {
  local release_sha="$1"
  local run_id="$2"
  local expected_original
  local expected_candidate
  if [[ "${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}" == NONE ]]; then
    remote_verify_partial_caddy_evidence "${release_sha}" "${run_id}"
    expected_original="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256:-}"
    expected_candidate="$(sudo sha256sum \
      "$(remote_caddy_evidence_root "${release_sha}" "${run_id}")/Caddyfile.drain" | awk '{print $1}')"
  else
    remote_verify_caddy_receipt_evidence "${release_sha}" "${run_id}"
    expected_original="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
    expected_candidate="${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}"
  fi
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local original="${evidence_root}/Caddyfile.original"
  local candidate="${evidence_root}/Caddyfile.drain"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'recovery Caddy evidence root ownership or mode mismatch'
  remote_sudo_require_root_file "${original}" 600
  remote_sudo_require_root_file "${candidate}" 600
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644
  local digest
  local candidate_digest
  local active_digest
  digest="$(sudo sha256sum "${original}" | awk '{print $1}')"
  candidate_digest="$(sudo sha256sum "${candidate}" | awk '{print $1}')"
  active_digest="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')"
  [[ "${digest}" == "${expected_original}" ]] ||
    die 'recovery original Caddyfile differs from immutable authority'
  [[ "${candidate_digest}" == "${expected_candidate}" ]] ||
    die 'recovery candidate Caddyfile differs from immutable authority'
  [[ "${active_digest}" == "${expected_candidate}" ]] ||
    die 'recovery refuses to overwrite an active Caddyfile other than the sealed candidate'
  sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null
  sudo install -o root -g root -m 0644 "${original}" /etc/caddy/Caddyfile
  sudo systemctl reload caddy
  [[ "$(sudo systemctl is-active caddy)" == active ]] || die 'Caddy recovery reload failed'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${digest}" ]] ||
    die 'Caddy recovery restoration is not byte-identical'
  remote_assert_caddy_drain_marker
  sudo rm -f -- /etc/caddy/v126-drain.enabled
  remote_assert_public_live
  printf '%s\n' "${digest}"
}

remote_recovery_ensure_candidate_drain() {
  local release_sha="$1"
  local run_id="$2"
  remote_verify_caddy_receipt_evidence "${release_sha}" "${run_id}"
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local candidate="${evidence_root}/Caddyfile.drain"
  local original="${evidence_root}/Caddyfile.original"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'recovery Caddy evidence root ownership or mode mismatch'
  remote_sudo_require_root_file "${candidate}" 600
  remote_sudo_require_root_file "${original}" 600
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644
  local candidate_sha
  local original_sha
  local active_sha
  candidate_sha="$(sudo sha256sum "${candidate}" | awk '{print $1}')"
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')"
  active_sha="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')"
  [[ "${candidate_sha}" == "${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}" ]] ||
    die 'recovery Caddy candidate differs from the immutable stage receipt'
  [[ "${original_sha}" == "${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}" ]] ||
    die 'recovery Caddy original differs from the immutable stage receipt'
  [[ "${active_sha}" == "${original_sha}" || "${active_sha}" == "${candidate_sha}" ]] ||
    die 'post-V126 recovery refuses an unrecognized active Caddyfile'
  if [[ "${active_sha}" != "${candidate_sha}" ]]; then
    sudo caddy validate --config "${candidate}" --adapter caddyfile >/dev/null
    sudo install -o root -g root -m 0644 "${candidate}" /etc/caddy/Caddyfile
    sudo systemctl reload caddy
  fi
  remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"
  sudo test ! -L /etc/caddy/v126-drain.enabled
  if ! sudo test -e /etc/caddy/v126-drain.enabled; then
    sudo install -o root -g root -m 0600 /dev/null /etc/caddy/v126-drain.enabled
  fi
  remote_assert_caddy_drain_marker
  remote_assert_public_drain
}

remote_recovery_ensure_pre_v126_drain() {
  local release_sha="$1"
  local run_id="$2"
  local expected_original
  local expected_candidate
  if [[ "${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}" == NONE ]]; then
    remote_verify_partial_caddy_evidence "${release_sha}" "${run_id}"
    expected_original="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256:-}"
    expected_candidate="$(sudo sha256sum \
      "$(remote_caddy_evidence_root "${release_sha}" "${run_id}")/Caddyfile.drain" | awk '{print $1}')"
  else
    remote_verify_caddy_receipt_evidence "${release_sha}" "${run_id}"
    expected_original="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
    expected_candidate="${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}"
  fi
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local original="${evidence_root}/Caddyfile.original"
  local candidate="${evidence_root}/Caddyfile.drain"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'pre-V126 Caddy evidence root ownership or mode mismatch'
  remote_sudo_require_root_file "${original}" 600
  remote_sudo_require_root_file "${candidate}" 600
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644
  local original_sha
  local candidate_sha
  local active_sha
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')"
  candidate_sha="$(sudo sha256sum "${candidate}" | awk '{print $1}')"
  active_sha="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')"
  [[ "${original_sha}" == "${expected_original}" ]] ||
    die 'pre-V126 original Caddy differs from immutable authority'
  [[ "${candidate_sha}" == "${expected_candidate}" ]] ||
    die 'pre-V126 candidate Caddy differs from immutable authority'
  [[ "${active_sha}" == "${original_sha}" || "${active_sha}" == "${candidate_sha}" ]] ||
    die 'pre-V126 recovery refuses an unrecognized active Caddyfile'
  sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null
  sudo caddy validate --config "${candidate}" --adapter caddyfile >/dev/null
  sudo install -o root -g root -m 0644 "${candidate}" /etc/caddy/Caddyfile
  sudo systemctl reload caddy
  [[ "$(sudo systemctl is-active caddy)" == active ]] || die 'pre-V126 recovery Caddy drain reload failed'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${candidate_sha}" ]] ||
    die 'pre-V126 recovery Caddy candidate is not byte-identical'
  sudo test ! -L /etc/caddy/v126-drain.enabled
  if ! sudo test -e /etc/caddy/v126-drain.enabled; then
    sudo install -o root -g root -m 0600 /dev/null /etc/caddy/v126-drain.enabled
  fi
  remote_assert_caddy_drain_marker
  remote_assert_public_drain
}

# V126_RECOVERY_PRE_V126_BEGIN
remote_recover_pre_v126() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local v125_image_tag="$4"
  local v126_image_tag="$5"
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${v126_image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  local before_flyway
  before_flyway="$(remote_flyway_state)"
  [[ "${before_flyway}" == '125:0:0:0' ]] ||
    die 'pre-V126 rollback refuses unless Flyway head is exactly V125 and V126 is absent; no Caddy/backend mutation occurred'
  remote_recovery_ensure_pre_v126_drain "${release_sha}" "${run_id}"
  remote_compose stop backend >/dev/null || die 'scoped candidate backend stop failed during pre-V126 rollback'
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'candidate backend remains running during pre-V126 rollback'
  remote_assert_zero_writer '125:0:0'
  remote_recovery_product_off "${staging_path}" "${run_root}"
  REMOTE_BACKEND_IMAGE="${v125_image_tag}"
  [[ "${v125_image_tag}" =~ :${V125_SOURCE_SHA}$ ]] || die 'pre-V126 rollback source tag mismatch'
  [[ "$(docker image inspect --format '{{.Id}}' "${v125_image_tag}")" == "${V125_IMAGE_ID}" ]] ||
    die 'exact reviewed V125 image is unavailable'
  remote_assert_compose_backend_image "${v125_image_tag}"
  [[ "${REMOTE_RECOVERY_ENV_AFTER_SHA256}" =~ ^[0-9a-f]{64}$ && \
    "$(remote_hash_file .env)" == "${REMOTE_RECOVERY_ENV_AFTER_SHA256}" ]] ||
    die 'recovery environment changed before V125 backend creation'
  remote_compose create --force-recreate --no-build --no-deps --pull never backend >/dev/null
  remote_capture_compose_ids all backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'pre-V126 recovery did not create exactly one V125 backend'
  local recovery_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  [[ "$(docker inspect --format '{{.Image}}' "${recovery_container}")" == "${V125_IMAGE_ID}" ]] ||
    die 'created pre-V126 recovery backend image mismatch'
  [[ "$(remote_hash_file .env)" == "${REMOTE_RECOVERY_ENV_AFTER_SHA256}" ]] ||
    die 'recovery environment changed during V125 backend creation'
  remote_assert_bound_container_environment "${recovery_container}" pre-v126
  docker update --restart=no "${recovery_container}" >/dev/null
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${recovery_container}")" == 'no:0' ]] ||
    die 'pre-V126 recovery restart policy was not disabled before start'
  docker start "${recovery_container}" >/dev/null
  remote_wait_backend_running "${recovery_container}"
  remote_assert_v125_runtime "${staging_path}" "${v125_image_tag}"
  local caddy_sha
  caddy_sha="$(remote_recovery_restore_original_caddy "${release_sha}" "${run_id}")"
  local proof="${run_root}/recovery-pre-v126.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" 'flyway=125:0:0:0' \
    "v125_source_sha=${V125_SOURCE_SHA}" "v125_image_id=${V125_IMAGE_ID}" \
    "env_before_sha256=${REMOTE_RECOVERY_ENV_BEFORE_SHA256}" \
    "env_after_sha256=${REMOTE_RECOVERY_ENV_AFTER_SHA256}" \
    "ordinary_caddy_sha256=${caddy_sha}" 'maintenance=OFF' 'traffic_policy=PRODUCT' \
    'allowed_lists=EMPTY' 'start_command_count=1' 'restart_policy=no' 'restart_count=0' \
    'global_v125_count=1' 'global_v126_count=0' \
    'public=LIVE' 'result=PRE_V126_ROLLBACK_COMPLETE'
  remote_emit_artifact recovery-pre-v126 "$(remote_hash_file "${proof}")"
}
# V126_RECOVERY_PRE_V126_END

# V126_RECOVERY_POST_V126_STOP_BEGIN
remote_verify_post_v126_stop_proof() {
  local run_root="$1"
  local run_id="$2"
  local release_sha="$3"
  local expected_proof_sha="${4:-}"
  local proof="${run_root}/recovery-post-v126-stop.proof"
  remote_verify_proof "${proof}"
  if [[ -n "${expected_proof_sha}" ]]; then
    [[ "${expected_proof_sha}" =~ ^[0-9a-f]{64}$ && \
      "$(remote_hash_file "${proof}")" == "${expected_proof_sha}" ]] ||
      die 'post-V126 stop proof does not match the immutable recovery receipt artifact'
  fi
  python3 - "${proof}" "${run_id}" "${release_sha}" <<'PY'
import re
import sys
proof_path, run_id, release_sha = sys.argv[1:]
parsed = {}
for row in open(proof_path, "rt", encoding="utf-8"):
    row = row.rstrip("\n")
    if "=" not in row:
        raise SystemExit("post-V126 stop proof row mismatch")
    key, value = row.split("=", 1)
    if key in parsed:
        raise SystemExit("duplicate post-V126 stop proof key")
    parsed[key] = value
expected = {
    "backend", "data_or_migration_mutation", "flyway", "image_classification",
    "global_v125_count", "global_v126_count",
    "observed_running_backend_count", "prepared", "public_drain", "release_sha",
    "result", "run_id", "sessions", "slots", "v125_start", "writers",
}
if set(parsed) != expected:
    raise SystemExit("post-V126 stop proof schema mismatch")
fixed = {
    "backend": "0",
    "data_or_migration_mutation": "NONE",
    "flyway": "126:1:1:0",
    "global_v125_count": "0",
    "global_v126_count": "0",
    "prepared": "0",
    "public_drain": "PASS",
    "release_sha": release_sha,
    "result": "FORWARD_FIX_REQUIRED",
    "run_id": run_id,
    "sessions": "0",
    "slots": "0",
    "v125_start": "REFUSED",
    "writers": "0",
}
for key, value in fixed.items():
    if parsed[key] != value:
        raise SystemExit(f"post-V126 stop proof mismatch: {key}")
if not re.fullmatch(r"[0-9]+", parsed["observed_running_backend_count"]):
    raise SystemExit("post-V126 running backend count mismatch")
count = int(parsed["observed_running_backend_count"])
classification = parsed["image_classification"]
if classification not in {
    "EXACT_V126_STOPPED", "NO_BACKEND_ALREADY_STOPPED", "V125_REFUSED_AND_STOPPED",
    "UNKNOWN_REFUSED_AND_STOPPED",
}:
    raise SystemExit("post-V126 image classification mismatch")
if classification == "EXACT_V126_STOPPED" and count != 1:
    raise SystemExit("exact V126 classification requires one observed backend")
if classification == "NO_BACKEND_ALREADY_STOPPED" and count != 0:
    raise SystemExit("already-stopped classification requires zero observed backends")
if classification in ("V125_REFUSED_AND_STOPPED", "UNKNOWN_REFUSED_AND_STOPPED") and count < 1:
    raise SystemExit("refused image classification requires an observed backend")
PY
}

remote_recover_post_v126_stop() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local image_id="$5"
  remote_require_image_id "${image_id}"
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  local flyway
  flyway="$(remote_flyway_state)"
  [[ "${flyway}" == '126:1:1:0' ]] ||
    die 'post-V126 stop refuses before Caddy/backend mutation unless exact V126 is present and successful'
  remote_recovery_ensure_candidate_drain "${release_sha}" "${run_id}"
  local running_count=0
  local image_classification='NO_BACKEND_ALREADY_STOPPED'
  local saw_v125=false
  local saw_unknown=false
  local backend_container
  remote_capture_compose_ids running backend
  if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} > 0 )); then
    for backend_container in "${REMOTE_CAPTURED_CONTAINER_IDS[@]}"; do
      running_count=$((running_count + 1))
      local observed_image
      if ! observed_image="$(docker inspect --format '{{.Image}}' "${backend_container}")"; then
        die 'post-V126 backend image classification became unobservable'
      fi
      if [[ "${observed_image}" == "${V125_IMAGE_ID}" ]]; then
        saw_v125=true
      elif [[ "${observed_image}" != "${image_id}" ]]; then
        saw_unknown=true
      fi
    done
  fi
  if [[ "${saw_v125}" == true ]]; then
    image_classification='V125_REFUSED_AND_STOPPED'
  elif [[ "${saw_unknown}" == false && "${running_count}" == 1 ]]; then
    image_classification='EXACT_V126_STOPPED'
  elif (( running_count > 0 )); then
    image_classification='UNKNOWN_REFUSED_AND_STOPPED'
  fi
  remote_compose stop backend >/dev/null || die 'scoped backend stop failed during post-V126 recovery'
  remote_capture_compose_ids running backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) ||
    die 'post-V126 terminal stop could not prove backend count zero'
  remote_assert_zero_writer '126:1:0'
  local proof="${run_root}/recovery-post-v126-stop.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" 'flyway=126:1:1:0' \
    "observed_running_backend_count=${running_count}" "image_classification=${image_classification}" \
    'backend=0' 'writers=0' 'sessions=0' 'prepared=0' 'slots=0' \
    'global_v125_count=0' 'global_v126_count=0' \
    'public_drain=PASS' 'v125_start=REFUSED' \
    'data_or_migration_mutation=NONE' 'result=FORWARD_FIX_REQUIRED'
  remote_verify_post_v126_stop_proof "${run_root}" "${run_id}" "${release_sha}"
  remote_emit_artifact recovery-post-v126-stop "$(remote_hash_file "${proof}")"
}
# V126_RECOVERY_POST_V126_STOP_END

# V126_RECOVERY_FULL_DR_VERIFY_BEGIN
remote_verify_full_dr_proof() {
  local proof="$1"
  local run_id="$2"
  local release_sha="$3"
  local phase="$4"
  local dump_sha="$5"
  local inventory_sha="$6"
  local boundary_sha="$7"
  local post_v126_receipt_sha="$8"
  local post_v126_proof_sha="$9"
  remote_verify_proof "${proof}"
  python3 - "${proof}" "${run_id}" "${release_sha}" "${phase}" \
    "${dump_sha}" "${inventory_sha}" "${boundary_sha}" \
    "${post_v126_receipt_sha}" "${post_v126_proof_sha}" <<'PY'
import re
import sys
(
    proof_path, run_id, release_sha, phase, dump_sha, inventory_sha, boundary_sha,
    post_v126_receipt_sha, post_v126_proof_sha,
) = sys.argv[1:]
parsed = {}
for row in open(proof_path, "rt", encoding="utf-8"):
    row = row.rstrip("\n")
    if "=" not in row:
        raise SystemExit("full-DR proof row mismatch")
    key, value = row.split("=", 1)
    if key in parsed:
        raise SystemExit("duplicate full-DR proof key")
    parsed[key] = value
expected = {
    "accepted_boundary_sha256", "backend", "backup_phase", "backup_sha256",
    "inventory_sha256", "live_flyway", "prepared", "release_sha", "restore_performed",
    "result", "run_id", "sessions", "slots", "post_v126_stop_proof_sha256",
    "post_v126_stop_receipt_sha256", "writers",
}
if set(parsed) != expected:
    raise SystemExit("full-DR proof schema mismatch")
fixed = {
    "accepted_boundary_sha256": boundary_sha,
    "backend": "0",
    "backup_phase": phase,
    "backup_sha256": dump_sha,
    "inventory_sha256": inventory_sha,
    "prepared": "0",
    "post_v126_stop_proof_sha256": post_v126_proof_sha,
    "post_v126_stop_receipt_sha256": post_v126_receipt_sha,
    "release_sha": release_sha,
    "restore_performed": "false",
    "result": "DR_AUTHORIZATION_REQUIRED",
    "run_id": run_id,
    "sessions": "0",
    "slots": "0",
    "writers": "0",
}
for key, value in fixed.items():
    if parsed[key] != value:
        raise SystemExit(f"full-DR proof mismatch: {key}")
if not re.fullmatch(r"[0-9]+:[0-9]+:[0-9]+:[0-9]+", parsed["live_flyway"]):
    raise SystemExit("full-DR live Flyway proof mismatch")
PY
}

remote_verify_full_dr() {
  local staging_path="$1"
  local run_id="$2"
  local release_sha="$3"
  local image_tag="$4"
  local phase="$5"
  local expected_dump_sha="$6"
  local expected_inventory_sha="$7"
  local boundary_sha="$8"
  local expected_post_v126_proof_sha="$9"
  [[ "${phase}" == pre-drain || "${phase}" == quiesced ]] || die 'invalid DR backup phase'
  [[ "${expected_dump_sha}" =~ ^[0-9a-f]{64}$ && "${expected_inventory_sha}" =~ ^[0-9a-f]{64}$ && "${boundary_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'invalid DR prerequisite artifact hash'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  local run_root
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")"
  local post_v126_receipt_sha='NONE'
  if [[ "${V126_INTERNAL_REMOTE_PREDECESSOR_STAGE:-}" == RECOVERY_POST_V126_STOP ]]; then
    [[ "${expected_post_v126_proof_sha}" =~ ^[0-9a-f]{64}$ ]] ||
      die 'full-DR escalation lacks the immutable post-V126 stop proof hash'
    post_v126_receipt_sha="${V126_INTERNAL_REMOTE_PREDECESSOR_HASH:-}"
    [[ "${post_v126_receipt_sha}" =~ ^[0-9a-f]{64}$ ]] ||
      die 'full-DR escalation lacks the immutable post-V126 stop receipt hash'
    remote_verify_post_v126_stop_proof "${run_root}" "${run_id}" "${release_sha}" \
      "${expected_post_v126_proof_sha}"
  else
    [[ "${expected_post_v126_proof_sha}" == NONE ]] ||
      die 'direct full-DR verification must not claim a post-V126 stop proof'
  fi
  remote_assert_caddy_drain_marker
  remote_assert_public_drain
  remote_assert_zero_writer ANY
  local live_flyway
  live_flyway="$(remote_flyway_state)"
  [[ "${live_flyway}" =~ ^[0-9]+:[0-9]+:[0-9]+:[0-9]+$ ]] || die 'full-DR live Flyway inventory is invalid'
  local backup_root
  backup_root="$(remote_backup_root "${release_sha}" "${run_id}")"
  local dump="${backup_root}/${phase}.dump"
  local inventory="${dump}.pg_restore.list"
  [[ -d "${backup_root}" && ! -L "${backup_root}" && \
    "$(stat -c '%a:%U:%G' "${backup_root}")" == "700:$(id -un):$(id -gn)" ]] ||
    die 'selected DR backup root ownership or mode mismatch'
  remote_require_operator_file "${dump}" 600
  remote_require_operator_file "${inventory}" 600
  [[ "$(remote_hash_file "${dump}")" == "${expected_dump_sha}" ]] || die 'selected DR backup hash mismatch'
  [[ "$(remote_hash_file "${inventory}")" == "${expected_inventory_sha}" ]] || die 'selected DR inventory hash mismatch'
  local generated="${run_root}/dr-${phase}.generated-list"
  [[ ! -e "${generated}" && ! -L "${generated}" ]] || die 'DR inventory verification artifact exists'
  remote_compose exec -T postgres sh -c ': "${POSTGRES_USER:?}"; pg_restore --list' \
    < "${dump}" > "${generated}"
  chmod 0600 "${generated}"
  cmp -s "${generated}" "${inventory}" || die 'DR inventory does not match the selected archive'
  local proof="${run_root}/recovery-full-dr-prerequisites.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "backup_phase=${phase}" \
    "backup_sha256=${expected_dump_sha}" "inventory_sha256=${expected_inventory_sha}" \
    "accepted_boundary_sha256=${boundary_sha}" 'backend=0' 'writers=0' 'sessions=0' \
    'prepared=0' 'slots=0' "live_flyway=${live_flyway}" \
    "post_v126_stop_receipt_sha256=${post_v126_receipt_sha}" \
    "post_v126_stop_proof_sha256=${expected_post_v126_proof_sha}" \
    'restore_performed=false' 'result=DR_AUTHORIZATION_REQUIRED'
  remote_verify_full_dr_proof "${proof}" "${run_id}" "${release_sha}" "${phase}" \
    "${expected_dump_sha}" "${expected_inventory_sha}" "${boundary_sha}" \
    "${post_v126_receipt_sha}" "${expected_post_v126_proof_sha}"
  remote_emit_artifact recovery-full-dr-prerequisites "$(remote_hash_file "${proof}")"
}
# V126_RECOVERY_FULL_DR_VERIFY_END

latest_valid_receipt() {
  local stage
  local latest_stage='NONE'
  local latest_hash='NONE'
  local candidate_hash
  for stage in "${V126_STAGES[@]}"; do
    if candidate_hash="$(verify_receipt "${stage}" 2>/dev/null)"; then
      latest_stage="${stage}"
      latest_hash="${candidate_hash}"
    else
      break
    fi
  done
  [[ "${latest_stage}" != NONE ]] || die 'recovery requires at least a valid BASELINE_VERIFIED receipt'
  printf '%s\t%s\n' "${latest_stage}" "${latest_hash}"
}

validate_dr_boundary() {
  local source="$1"
  local destination="$2"
  local phase="$3"
  [[ -f "${source}" && ! -L "${source}" ]] || die 'DR boundary evidence must be a regular non-symlink file'
  python3 - "${source}" "${destination}" "${RUN_ID}" "${RELEASE_SHA}" "${phase}" <<'PY'
import datetime
import json
import os
import stat
import sys
source, target, run_id, release_sha, phase = sys.argv[1:]
if stat.S_IMODE(os.stat(source).st_mode) not in (0o400, 0o600):
    raise SystemExit("DR boundary evidence must be mode 0400 or 0600")
raw = open(source, "rb").read()
doc = json.loads(raw)
expected_keys = {
    "accepted_data_loss_boundary", "accepted_recovery_point_utc", "backup_phase",
    "format_version", "release_sha", "result_category", "run_id",
}
if set(doc) != expected_keys or doc.get("format_version") != 1:
    raise SystemExit("DR boundary evidence schema mismatch")
fixed = {
    "backup_phase": phase,
    "release_sha": release_sha,
    "result_category": "DR_PREREQUISITES_ACCEPTED",
    "run_id": run_id,
}
for key, value in fixed.items():
    if doc.get(key) != value:
        raise SystemExit(f"DR boundary evidence mismatch: {key}")
if doc.get("accepted_data_loss_boundary") not in (
    "ALL_WRITES_AFTER_PRE_DRAIN_BACKUP_MAY_BE_LOST",
    "ALL_WRITES_AFTER_QUIESCED_BACKUP_MAY_BE_LOST",
):
    raise SystemExit("DR data-loss boundary is not explicit")
expected_boundary = (
    "ALL_WRITES_AFTER_PRE_DRAIN_BACKUP_MAY_BE_LOST" if phase == "pre-drain"
    else "ALL_WRITES_AFTER_QUIESCED_BACKUP_MAY_BE_LOST"
)
if doc["accepted_data_loss_boundary"] != expected_boundary:
    raise SystemExit("DR data-loss boundary does not match selected backup")
try:
    parsed = datetime.datetime.strptime(doc["accepted_recovery_point_utc"], "%Y-%m-%dT%H:%M:%SZ")
except (TypeError, ValueError):
    raise SystemExit("DR recovery point must be an exact UTC timestamp")
canonical = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
if raw != canonical:
    raise SystemExit("DR boundary evidence is not canonical JSON")
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(raw)
PY
}

write_recovery_intent_and_terminal() {
  local mode="$1"
  local token_hash="$2"
  local predecessor_stage="$3"
  local predecessor_hash="$4"
  local preserve_terminal="${5:-false}"
  local intent="${STATE_DIR}/recovery/${mode}.intent.json"
  local terminal="${STATE_DIR}/run-terminal.json"
  [[ ! -e "${intent}" && ! -L "${intent}" ]] || die 'recovery intent already exists'
  [[ ! -e "${intent}.sha256" && ! -L "${intent}.sha256" ]] || die 'recovery intent checksum already exists'
  if [[ "${preserve_terminal}" == true ]]; then
    [[ -f "${terminal}" && ! -L "${terminal}" ]] || die 'post-V126 terminal marker is unavailable for DR escalation'
  else
    [[ ! -e "${terminal}" && ! -L "${terminal}" ]] || die 'terminal marker already exists'
    [[ ! -e "${terminal}.sha256" && ! -L "${terminal}.sha256" ]] || die 'terminal marker checksum already exists'
  fi
  python3 - "${intent}" "${terminal}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" \
    "${mode}" "${token_hash}" "${predecessor_stage}" "${predecessor_hash}" "$(utc_now)" \
    "${preserve_terminal}" <<'PY'
import json
import os
import sys
intent_path, terminal_path, run_id, release_sha, script_sha, mode, token_hash, predecessor, predecessor_hash, timestamp, preserve_terminal = sys.argv[1:]
intent = {
    "authorization_token_sha256": token_hash,
    "format_version": 1,
    "intent_at": timestamp,
    "kind": "RECOVERY_INTENT",
    "mode": mode,
    "predecessor_receipt_sha256": predecessor_hash,
    "predecessor_stage": predecessor,
    "release_sha": release_sha,
    "run_id": run_id,
    "script_sha256": script_sha,
}
terminal = {
    "format_version": 1,
    "mode": mode,
    "release_sha": release_sha,
    "run_id": run_id,
    "script_sha256": script_sha,
    "status": "RECOVERY_INTENT_RECORDED_NO_STAGE_CONTINUATION",
    "terminal_at": timestamp,
}
documents = [(intent_path, intent)]
if preserve_terminal != "true":
    documents.append((terminal_path, terminal))
for path, doc in documents:
    payload = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(fd, "wb") as handle:
        handle.write(payload)
PY
  printf '%s\n' "$(hash_file "${intent}")" > "${intent}.sha256"
  chmod 0400 "${intent}.sha256"
  if [[ "${preserve_terminal}" != true ]]; then
    printf '%s\n' "$(hash_file "${terminal}")" > "${terminal}.sha256"
    chmod 0400 "${terminal}.sha256"
  fi
}

verify_post_v126_recovery_for_dr() {
  verify_recovery_receipt post-v126-stop >/dev/null ||
    die 'post-V126 recovery receipt or operation log failed exact verification'
  local fields
  fields="$(python3 - "${STATE_DIR}/run.json" "${STATE_DIR}/run-terminal.json" \
    "${STATE_DIR}/run-terminal.json.sha256" "${STATE_DIR}/recovery/post-v126-stop.intent.json" \
    "${STATE_DIR}/recovery/post-v126-stop.intent.json.sha256" \
    "${STATE_DIR}/recovery/post-v126-stop.receipt.json" \
    "${STATE_DIR}/recovery/post-v126-stop.receipt.json.sha256" \
    "$(hash_text "${POST_V126_STOP_TOKEN}")" <<'PY'
import hashlib
import json
import os
import re
import stat
import sys
manifest_path, terminal_path, terminal_sum, intent_path, intent_sum, receipt_path, receipt_sum, token_hash = sys.argv[1:]
manifest = json.load(open(manifest_path, "rt", encoding="utf-8"))
timestamp = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")
def read(path, checksum):
    if os.path.islink(path) or os.path.islink(checksum):
        raise SystemExit("recovery chain symlink rejected")
    if stat.S_IMODE(os.stat(path).st_mode) != 0o400 or stat.S_IMODE(os.stat(checksum).st_mode) != 0o400:
        raise SystemExit("recovery chain files must be mode 0400")
    raw = open(path, "rb").read()
    digest = hashlib.sha256(raw).hexdigest()
    if open(checksum, "rt", encoding="ascii").read().strip() != digest:
        raise SystemExit("recovery chain checksum mismatch")
    doc = json.loads(raw)
    if raw != (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode():
        raise SystemExit("recovery chain JSON is not canonical")
    return doc, digest
terminal, _ = read(terminal_path, terminal_sum)
intent, intent_hash = read(intent_path, intent_sum)
receipt, receipt_hash = read(receipt_path, receipt_sum)
terminal_keys = {"format_version", "mode", "release_sha", "run_id", "script_sha256", "status", "terminal_at"}
intent_keys = {
    "authorization_token_sha256", "format_version", "intent_at", "kind", "mode",
    "predecessor_receipt_sha256", "predecessor_stage", "release_sha", "run_id", "script_sha256",
}
receipt_keys = {
    "artifacts", "authorization_token_sha256", "completed_at", "format_version", "intent_sha256",
    "mode", "predecessor_receipt_sha256", "predecessor_stage", "release_sha", "result_category",
    "run_id", "script_sha256",
}
if set(terminal) != terminal_keys or set(intent) != intent_keys or set(receipt) != receipt_keys:
    raise SystemExit("post-V126 recovery schema mismatch")
if any(doc.get("format_version") != 1 for doc in (terminal, intent, receipt)):
    raise SystemExit("post-V126 recovery format mismatch")
for doc, key in ((terminal, "terminal_at"), (intent, "intent_at"), (receipt, "completed_at")):
    if not isinstance(doc.get(key), str) or not timestamp.fullmatch(doc[key]):
        raise SystemExit("post-V126 recovery timestamp mismatch")
for doc in (terminal, intent, receipt):
    for key in ("run_id", "release_sha", "script_sha256"):
        if doc.get(key) != manifest[key]:
            raise SystemExit(f"post-V126 recovery identity mismatch: {key}")
if terminal.get("mode") != "post-v126-stop" or terminal.get("status") != "RECOVERY_INTENT_RECORDED_NO_STAGE_CONTINUATION":
    raise SystemExit("terminal marker is not the post-V126 stop")
if intent.get("mode") != "post-v126-stop" or intent.get("kind") != "RECOVERY_INTENT":
    raise SystemExit("post-V126 recovery intent mismatch")
if receipt.get("mode") != "post-v126-stop" or receipt.get("result_category") != "TERMINAL_RECOVERY_BOUNDARY":
    raise SystemExit("post-V126 recovery receipt mismatch")
if intent.get("authorization_token_sha256") != token_hash or receipt.get("authorization_token_sha256") != token_hash:
    raise SystemExit("post-V126 recovery authorization mismatch")
if receipt.get("intent_sha256") != intent_hash:
    raise SystemExit("post-V126 recovery intent hash mismatch")
for key in ("predecessor_stage", "predecessor_receipt_sha256"):
    if receipt.get(key) != intent.get(key):
        raise SystemExit(f"post-V126 recovery predecessor mismatch: {key}")
artifacts = receipt.get("artifacts")
if not isinstance(artifacts, list) or artifacts != sorted(artifacts, key=lambda item: item.get("name", "")):
    raise SystemExit("post-V126 recovery artifacts are not canonical")
proof_hashes = [item.get("sha256") for item in artifacts if item.get("name") == "recovery-post-v126-stop"]
if len(proof_hashes) != 1:
    raise SystemExit("post-V126 stop proof artifact is absent or duplicated")
for item in artifacts:
    if set(item) != {"name", "sha256"} or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", item["name"]) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
        raise SystemExit("post-V126 recovery artifact mismatch")
print(
    f"{intent['predecessor_stage']}\t{intent['predecessor_receipt_sha256']}\t"
    f"{receipt_hash}\t{proof_hashes[0]}"
)
PY
)" || die 'post-V126 recovery receipt failed strict verification'
  local predecessor_stage="${fields%%$'\t'*}"
  local remainder="${fields#*$'\t'}"
  local predecessor_hash="${remainder%%$'\t'*}"
  remainder="${remainder#*$'\t'}"
  local recovery_hash="${remainder%%$'\t'*}"
  local proof_hash="${remainder#*$'\t'}"
  stage_index "${predecessor_stage}" >/dev/null || die 'post-V126 recovery predecessor stage is invalid'
  [[ "$(verify_receipt "${predecessor_stage}")" == "${predecessor_hash}" ]] ||
    die 'post-V126 recovery predecessor receipt is invalid'
  [[ "${recovery_hash}" =~ ^[0-9a-f]{64}$ && "${proof_hash}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'post-V126 recovery receipt or proof artifact hash is invalid'
  printf '%s\t%s\n' "${recovery_hash}" "${proof_hash}"
}

write_recovery_receipt() {
  local mode="$1"
  local predecessor_stage="$2"
  local predecessor_hash="$3"
  local token_hash="$4"
  local artifact_log="$5"
  local target="${STATE_DIR}/recovery/${mode}.receipt.json"
  [[ ! -e "${target}" && ! -L "${target}" ]] || die 'recovery receipt already exists'
  [[ ! -e "${target}.sha256" && ! -L "${target}.sha256" ]] || die 'recovery receipt checksum already exists'
  python3 - "${target}" "${artifact_log}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" \
    "${mode}" "${predecessor_stage}" "${predecessor_hash}" "${token_hash}" \
    "$(hash_file "${STATE_DIR}/recovery/${mode}.intent.json")" "$(hash_file "${artifact_log}")" \
    "$(utc_now)" <<'PY'
import json
import os
import re
import sys
target, artifact_path, run_id, release_sha, script_sha, mode, predecessor, predecessor_hash, token_hash, intent_hash, operation_hash, timestamp = sys.argv[1:]
artifacts = []
names = set()
with open(artifact_path, "rt", encoding="utf-8") as handle:
    for line in handle:
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 3 and parts[0] == "ARTIFACT":
            name, digest = parts[1:]
            if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", name) or not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise SystemExit("invalid recovery artifact")
            if name in names:
                raise SystemExit("duplicate recovery artifact")
            names.add(name)
            artifacts.append({"name": name, "sha256": digest})
if not artifacts:
    raise SystemExit("recovery requires a remote proof artifact")
if "operation-log" in names:
    raise SystemExit("remote recovery artifact may not shadow the operation log")
artifacts.append({"name": "operation-log", "sha256": operation_hash})
doc = {
    "artifacts": sorted(artifacts, key=lambda item: item["name"]),
    "authorization_token_sha256": token_hash,
    "completed_at": timestamp,
    "format_version": 1,
    "intent_sha256": intent_hash,
    "mode": mode,
    "predecessor_receipt_sha256": predecessor_hash,
    "predecessor_stage": predecessor,
    "release_sha": release_sha,
    "result_category": "TERMINAL_RECOVERY_BOUNDARY",
    "run_id": run_id,
    "script_sha256": script_sha,
}
payload = (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode()
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, "wb") as handle:
    handle.write(payload)
PY
  printf '%s\n' "$(hash_file "${target}")" > "${target}.sha256"
  chmod 0400 "${target}.sha256"
}

verify_recovery_receipt() {
  local mode="$1"
  local token_hash
  local expected_artifacts
  case "${mode}" in
    pre-v126)
      token_hash="$(hash_text "${PRE_V126_ROLLBACK_TOKEN}")"
      expected_artifacts='recovery-pre-v126'
      ;;
    post-v126-stop)
      token_hash="$(hash_text "${POST_V126_STOP_TOKEN}")"
      expected_artifacts='recovery-post-v126-stop'
      ;;
    verify-full-dr)
      token_hash="$(hash_text "${FULL_DR_VERIFY_TOKEN}")"
      expected_artifacts='dr-boundary,dr-selected-backup,dr-selected-inventory,recovery-full-dr-prerequisites'
      ;;
    *) die 'unknown recovery receipt mode' ;;
  esac
  python3 - "${STATE_DIR}/run.json" "${STATE_DIR}/recovery/${mode}.intent.json" \
    "${STATE_DIR}/recovery/${mode}.intent.json.sha256" \
    "${STATE_DIR}/recovery/${mode}.receipt.json" \
    "${STATE_DIR}/recovery/${mode}.receipt.json.sha256" \
    "${STATE_DIR}/recovery/${mode}.operation.log" "${mode}" "${token_hash}" \
    "${expected_artifacts}" <<'PY'
import hashlib
import json
import os
import re
import stat
import sys

(
    manifest_path, intent_path, intent_sum, receipt_path, receipt_sum, operation_path,
    mode, token_hash, expected_spec,
) = sys.argv[1:]
timestamp = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")

def read_canonical(path, checksum):
    if os.path.islink(path) or os.path.islink(checksum) or not os.path.isfile(path) or not os.path.isfile(checksum):
        raise SystemExit("recovery receipt chain is unavailable or symlinked")
    for candidate in (path, checksum):
        info = os.stat(candidate)
        if stat.S_IMODE(info.st_mode) != 0o400 or info.st_uid != os.getuid():
            raise SystemExit("recovery receipt chain mode or ownership mismatch")
    raw = open(path, "rb").read()
    digest = hashlib.sha256(raw).hexdigest()
    if open(checksum, "rt", encoding="ascii").read().strip() != digest:
        raise SystemExit("recovery receipt chain checksum mismatch")
    doc = json.loads(raw)
    if raw != (json.dumps(doc, sort_keys=True, separators=(",", ":")) + "\n").encode():
        raise SystemExit("recovery receipt chain is not canonical JSON")
    return doc, digest

manifest = json.load(open(manifest_path, "rt", encoding="utf-8"))
intent, intent_hash = read_canonical(intent_path, intent_sum)
receipt, receipt_hash = read_canonical(receipt_path, receipt_sum)
intent_keys = {
    "authorization_token_sha256", "format_version", "intent_at", "kind", "mode",
    "predecessor_receipt_sha256", "predecessor_stage", "release_sha", "run_id", "script_sha256",
}
receipt_keys = {
    "artifacts", "authorization_token_sha256", "completed_at", "format_version", "intent_sha256",
    "mode", "predecessor_receipt_sha256", "predecessor_stage", "release_sha", "result_category",
    "run_id", "script_sha256",
}
if set(intent) != intent_keys or set(receipt) != receipt_keys:
    raise SystemExit("recovery receipt schema mismatch")
if intent["format_version"] != 1 or receipt["format_version"] != 1 or intent["kind"] != "RECOVERY_INTENT":
    raise SystemExit("recovery receipt format mismatch")
if not timestamp.fullmatch(intent["intent_at"]) or not timestamp.fullmatch(receipt["completed_at"]):
    raise SystemExit("recovery receipt timestamp mismatch")
for doc in (intent, receipt):
    for key in ("run_id", "release_sha", "script_sha256"):
        if doc.get(key) != manifest[key]:
            raise SystemExit(f"recovery receipt identity mismatch: {key}")
    if doc.get("mode") != mode or doc.get("authorization_token_sha256") != token_hash:
        raise SystemExit("recovery receipt mode or authorization mismatch")
if receipt["result_category"] != "TERMINAL_RECOVERY_BOUNDARY" or receipt["intent_sha256"] != intent_hash:
    raise SystemExit("recovery receipt result or intent mismatch")
for key in ("predecessor_stage", "predecessor_receipt_sha256"):
    if receipt[key] != intent[key]:
        raise SystemExit(f"recovery receipt predecessor mismatch: {key}")
artifacts = receipt["artifacts"]
if not isinstance(artifacts, list) or artifacts != sorted(artifacts, key=lambda item: item.get("name", "")):
    raise SystemExit("recovery receipt artifacts are not canonical")
artifact_hashes = {}
for item in artifacts:
    if not isinstance(item, dict) or set(item) != {"name", "sha256"}:
        raise SystemExit("recovery receipt artifact schema mismatch")
    name = item["name"]
    digest = item["sha256"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", name) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise SystemExit("recovery receipt artifact value mismatch")
    if name in artifact_hashes:
        raise SystemExit("duplicate recovery receipt artifact")
    artifact_hashes[name] = digest
expected = set(expected_spec.split(",")) | {"operation-log"}
if set(artifact_hashes) != expected:
    raise SystemExit("recovery receipt artifact set mismatch")
if os.path.islink(operation_path) or not os.path.isfile(operation_path):
    raise SystemExit("recovery operation log is unavailable or symlinked")
operation_stat = os.stat(operation_path)
if stat.S_IMODE(operation_stat.st_mode) != 0o400 or operation_stat.st_uid != os.getuid():
    raise SystemExit("recovery operation log mode or ownership mismatch")
operation_raw = open(operation_path, "rb").read()
if artifact_hashes["operation-log"] != hashlib.sha256(operation_raw).hexdigest():
    raise SystemExit("recovery operation log hash mismatch")
logged = {}
for line in operation_raw.splitlines():
    if not line.startswith(b"ARTIFACT"):
        continue
    match = re.fullmatch(rb"ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t([0-9a-f]{64})", line)
    if match is None:
        raise SystemExit("malformed recovery ARTIFACT line")
    name = match.group(1).decode("ascii")
    digest = match.group(2).decode("ascii")
    if name == "operation-log" or name in logged:
        raise SystemExit("duplicate or reserved recovery ARTIFACT line")
    logged[name] = digest
if set(logged) != expected - {"operation-log"}:
    raise SystemExit("recovery operation log ARTIFACT set mismatch")
for name, digest in logged.items():
    if artifact_hashes[name] != digest:
        raise SystemExit("recovery operation log ARTIFACT hash mismatch")
print(receipt_hash)
PY
}

recovery_command() {
  local state_dir=''
  local mode=''
  local authorization=''
  local backup_phase=''
  local boundary_file=''
  shift
  while (( $# > 0 )); do
    case "$1" in
      --state-dir) state_dir="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --authorization) authorization="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --backup-phase) backup_phase="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --boundary-file) boundary_file="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --*) die "unknown recovery option: $1" ;;
      *)
        [[ -z "${mode}" ]] || die 'recover accepts exactly one recovery mode'
        mode="$1"
        shift
        ;;
    esac
  done
  case "${mode}" in
    pre-v126) [[ "${authorization}" == "${PRE_V126_ROLLBACK_TOKEN}" ]] || die 'pre-V126 recovery authorization mismatch' ;;
    post-v126-stop) [[ "${authorization}" == "${POST_V126_STOP_TOKEN}" ]] || die 'post-V126 recovery authorization mismatch' ;;
    verify-full-dr) [[ "${authorization}" == "${FULL_DR_VERIFY_TOKEN}" ]] || die 'full-DR verification authorization mismatch' ;;
    *) die 'unknown recovery mode' ;;
  esac
  load_state "${state_dir}"
  acquire_state_lock_for_recovery
  install_state_lock_traps
  local latest
  local predecessor_stage
  local predecessor_hash
  local post_v126_proof_sha='NONE'
  local preserve_terminal=false
  if [[ -e "${STATE_DIR}/run-terminal.json" || -L "${STATE_DIR}/run-terminal.json" ]]; then
    [[ "${mode}" == verify-full-dr ]] || die 'run is already terminal'
    predecessor_stage='RECOVERY_POST_V126_STOP'
    local post_v126_binding
    post_v126_binding="$(verify_post_v126_recovery_for_dr)" ||
      die 'full-DR escalation requires an exact successful post-V126 stop receipt'
    predecessor_hash="${post_v126_binding%%$'\t'*}"
    post_v126_proof_sha="${post_v126_binding#*$'\t'}"
    [[ "${predecessor_hash}" =~ ^[0-9a-f]{64}$ && "${post_v126_proof_sha}" =~ ^[0-9a-f]{64}$ ]] ||
      die 'full-DR escalation post-V126 receipt binding is invalid'
    preserve_terminal=true
  else
    latest="$(latest_valid_receipt)"
    predecessor_stage="${latest%%$'\t'*}"
    predecessor_hash="${latest#*$'\t'}"
  fi
  local token_hash
  token_hash="$(hash_text "${authorization}")"
  local boundary_sha=''
  local selected_dump_sha=''
  local selected_inventory_sha=''
  if [[ "${mode}" == verify-full-dr ]]; then
    [[ "${backup_phase}" == pre-drain || "${backup_phase}" == quiesced ]] || die 'full-DR verification requires --backup-phase'
    require_absolute_path boundary-file "${boundary_file}"
    if [[ "${backup_phase}" == pre-drain ]]; then
      selected_dump_sha="$(receipt_artifact_hash PRE_DRAIN_BACKUP_REHEARSED pre-drain-backup-dump)"
      selected_inventory_sha="$(receipt_artifact_hash PRE_DRAIN_BACKUP_REHEARSED pre-drain-backup-inventory)"
    else
      selected_dump_sha="$(receipt_artifact_hash QUIESCED_BACKUP_REHEARSED quiesced-backup-dump)"
      selected_inventory_sha="$(receipt_artifact_hash QUIESCED_BACKUP_REHEARSED quiesced-backup-inventory)"
    fi
    local sealed_boundary="${STATE_DIR}/recovery/dr-boundary.json"
    validate_dr_boundary "${boundary_file}" "${sealed_boundary}" "${backup_phase}"
    boundary_sha="$(hash_file "${sealed_boundary}")"
  elif [[ -n "${backup_phase}" || -n "${boundary_file}" ]]; then
    die 'backup phase and boundary file are accepted only for verify-full-dr'
  fi
  write_recovery_intent_and_terminal "${mode}" "${token_hash}" "${predecessor_stage}" \
    "${predecessor_hash}" "${preserve_terminal}"
  ACTIVE_OPERATION_KIND='RECOVERY'
  ACTIVE_OPERATION_NAME="${mode}"
  ACTIVE_PREDECESSOR_STAGE="${predecessor_stage}"
  ACTIVE_PREDECESSOR_HASH="${predecessor_hash}"
  ACTIVE_AUTHORIZATION_GATE='RECOVERY'
  ACTIVE_AUTHORIZATION_HASH="${token_hash}"
  ACTIVE_INTENT_HASH="$(hash_file "${STATE_DIR}/recovery/${mode}.intent.json")"
  local log="${STATE_DIR}/recovery/${mode}.operation.log"
  [[ ! -e "${log}" && ! -L "${log}" ]] || die 'recovery operation log exists'
  local status=0
  case "${mode}" in
    pre-v126)
      run_remote recover-pre-v126 "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" \
        "${V125_IMAGE_TAG}" "${V126_IMAGE_TAG}" > "${log}" 2>&1 || status=$?
      ;;
    post-v126-stop)
      run_remote recover-post-v126-stop "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" \
        "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}" > "${log}" 2>&1 || status=$?
      ;;
    verify-full-dr)
      {
        local_emit_artifact dr-boundary "${boundary_sha}"
        local_emit_artifact dr-selected-backup "${selected_dump_sha}"
        local_emit_artifact dr-selected-inventory "${selected_inventory_sha}"
        run_remote verify-full-dr "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" \
          "${V126_IMAGE_TAG}" "${backup_phase}" "${selected_dump_sha}" "${selected_inventory_sha}" \
          "${boundary_sha}" "${post_v126_proof_sha}"
      } > "${log}" 2>&1 || status=$?
      ;;
  esac
  chmod 0400 "${log}"
  if (( status != 0 )); then
    printf 'Recovery %s failed closed (exit %s); the run remains terminal. Restricted log: %s\n' \
      "${mode}" "${status}" "${log}" >&2
    return "${status}"
  fi
  write_recovery_receipt "${mode}" "${predecessor_stage}" "${predecessor_hash}" "${token_hash}" "${log}"
  verify_recovery_receipt "${mode}" >/dev/null || die 'new recovery receipt failed exact verification'
  case "${mode}" in
    pre-v126) printf 'PRE_V126_ROLLBACK_COMPLETE\n' ;;
    post-v126-stop) printf 'FORWARD_FIX_REQUIRED\n' ;;
    verify-full-dr) printf 'DR_AUTHORIZATION_REQUIRED\n' ;;
  esac
  release_state_lock
  clear_state_lock_traps
}

remote_dispatch_enveloped() {
  local action="${1:-}"
  shift || true
  [[ "${REMOTE_MODE}" == true &&
    "${V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED:-}" == V126_INTERNAL_REMOTE_ENVELOPE_V1 ]] ||
    die 'remote dispatch requires the internal streamed envelope'
  require_cmd sha256sum
  unset DATABASE_URL PGHOST PGPORT PGUSER PGDATABASE PGSERVICE PGSERVICEFILE \
    DOCKER_HOST DOCKER_CONTEXT COMPOSE_FILE COMPOSE_PROFILES COMPOSE_PROJECT_NAME || true
  (( $# >= 3 )) || die 'remote action requires staging path, run ID, and release SHA bindings'
  remote_require_absolute_path staging-path "$1"
  remote_require_run_id "$2"
  remote_require_sha "$3"
  [[ "${action}" == "${V126_INTERNAL_REMOTE_ACTION:-}" &&
    "$1" == "${V126_INTERNAL_REMOTE_STAGING_PATH:-}" &&
    "$2" == "${V126_INTERNAL_REMOTE_RUN_ID:-}" &&
    "$3" == "${V126_INTERNAL_REMOTE_RELEASE_SHA:-}" ]] ||
    die 'remote action target does not match the streamed envelope'
  [[ "${V126_INTERNAL_REMOTE_SCRIPT_SHA256:-}" =~ ^[0-9a-f]{64}$ &&
    "${V126_INTERNAL_REMOTE_INTENT_HASH:-}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'remote envelope script or intent identity is invalid'
  local manifest_v126_image_id="${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}"
  remote_require_image_id "${manifest_v126_image_id}"
  local operation_kind="${V126_INTERNAL_REMOTE_OPERATION_KIND:-}"
  local operation_name="${V126_INTERNAL_REMOTE_OPERATION_NAME:-}"
  local predecessor_stage="${V126_INTERNAL_REMOTE_PREDECESSOR_STAGE:-}"
  local predecessor_hash="${V126_INTERNAL_REMOTE_PREDECESSOR_HASH:-}"
  local authorization_gate="${V126_INTERNAL_REMOTE_AUTHORIZATION_GATE:-}"
  local authorization_hash="${V126_INTERNAL_REMOTE_AUTHORIZATION_HASH:-}"
  local baseline_database_sha="${V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256:-}"
  local baseline_identities_sha="${V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256:-}"
  local baseline_compose_sha="${V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256:-}"
  local baseline_maintenance_sha="${V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256:-}"
  local baseline_admission_sha="${V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256:-}"
  local baseline_caddy_sha="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256:-}"
  local baseline_env_sha="${V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256:-}"
  local caddy_original_sha="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
  local caddy_candidate_sha="${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}"
  local caddy_diff_sha="${V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256:-}"
  local caddy_activation_sha="${V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256:-}"
  local maintenance_smoke_sha="${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256:-}"
  local maintenance_off_sha="${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256:-}"
  if [[ "${operation_kind}:${operation_name}:${action}" == STAGE:BASELINE_VERIFIED:baseline ]]; then
    [[ "${baseline_database_sha}" == NONE && "${baseline_identities_sha}" == NONE && \
      "${baseline_compose_sha}" == NONE && "${baseline_maintenance_sha}" == NONE && \
      "${baseline_admission_sha}" == NONE && "${baseline_caddy_sha}" == NONE && \
      "${baseline_env_sha}" == NONE ]] ||
      die 'baseline remote envelope must not claim a pre-existing authority receipt'
  else
    local authority_hash
    for authority_hash in "${baseline_database_sha}" "${baseline_identities_sha}" \
      "${baseline_compose_sha}" "${baseline_maintenance_sha}" "${baseline_admission_sha}" \
      "${baseline_caddy_sha}" "${baseline_env_sha}"; do
      [[ "${authority_hash}" =~ ^[0-9a-f]{64}$ ]] ||
        die 'remote envelope lacks an exact baseline authority receipt binding'
    done
  fi
  local caddy_binding_count=0
  local caddy_bound_hash
  for caddy_bound_hash in "${caddy_original_sha}" "${caddy_candidate_sha}" \
    "${caddy_diff_sha}" "${caddy_activation_sha}"; do
    if [[ "${caddy_bound_hash}" =~ ^[0-9a-f]{64}$ ]]; then
      caddy_binding_count=$((caddy_binding_count + 1))
    elif [[ "${caddy_bound_hash}" != NONE ]]; then
      die 'remote envelope Caddy receipt binding is malformed'
    fi
  done
  (( caddy_binding_count == 0 || caddy_binding_count == 4 )) ||
    die 'remote envelope Caddy receipt binding is incomplete'
  [[ "${maintenance_smoke_sha}" == NONE || "${maintenance_smoke_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'remote envelope maintenance-smoke receipt binding is malformed'
  [[ "${maintenance_off_sha}" == NONE || "${maintenance_off_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'remote envelope maintenance-OFF receipt binding is malformed'
  if [[ "${operation_kind}" == STAGE ]]; then
    stage_index "${operation_name}" >/dev/null || die 'remote envelope stage is invalid'
    [[ "${predecessor_stage}" == "$(stage_predecessor "${operation_name}")" ]] ||
      die 'remote envelope predecessor stage mismatch'
    [[ "${authorization_gate}" == "$(stage_gate "${operation_name}")" ]] ||
      die 'remote envelope authorization gate mismatch'
    if [[ "${predecessor_stage}" == NONE ]]; then
      [[ "${predecessor_hash}" == NONE ]] || die 'remote envelope baseline predecessor hash mismatch'
    else
      [[ "${predecessor_hash}" =~ ^[0-9a-f]{64}$ ]] || die 'remote envelope predecessor hash is invalid'
    fi
    if [[ "${authorization_gate}" == NONE ]]; then
      [[ "${authorization_hash}" == NONE ]] || die 'remote envelope baseline authorization hash mismatch'
    else
      [[ "${authorization_hash}" =~ ^[0-9a-f]{64}$ ]] || die 'remote envelope authorization hash is invalid'
    fi
  elif [[ "${operation_kind}" == RECOVERY ]]; then
    [[ "${operation_name}" == pre-v126 || "${operation_name}" == post-v126-stop ||
      "${operation_name}" == verify-full-dr ]] || die 'remote envelope recovery mode is invalid'
    [[ "${authorization_gate}" == RECOVERY && "${authorization_hash}" =~ ^[0-9a-f]{64}$ ]] ||
      die 'remote envelope recovery authorization binding is invalid'
    if [[ "${predecessor_stage}" == RECOVERY_POST_V126_STOP ]]; then
      [[ "${predecessor_hash}" =~ ^[0-9a-f]{64}$ ]] || die 'remote recovery predecessor hash is invalid'
    else
      stage_index "${predecessor_stage}" >/dev/null || die 'remote recovery predecessor stage is invalid'
      [[ "${predecessor_hash}" =~ ^[0-9a-f]{64}$ ]] || die 'remote recovery predecessor hash is invalid'
    fi
  else
    die 'remote envelope operation kind is invalid'
  fi
  local caddy_receipt_required=false
  if [[ "${operation_kind}" == STAGE ]]; then
    if (( $(stage_index "${operation_name}") > 3 )); then
      caddy_receipt_required=true
    fi
  elif [[ "${operation_name}" == post-v126-stop || "${operation_name}" == verify-full-dr ]]; then
    caddy_receipt_required=true
  elif [[ "${operation_name}" == pre-v126 ]]; then
    if [[ "${predecessor_stage}" == RECOVERY_POST_V126_STOP ]] || \
      (( $(stage_index "${predecessor_stage}") >= 3 )); then
      caddy_receipt_required=true
    fi
  fi
  if [[ "${caddy_receipt_required}" == true ]]; then
    (( caddy_binding_count == 4 )) ||
      die 'remote operation lacks the required immutable Caddy stage receipt'
  else
    (( caddy_binding_count == 0 )) ||
      die 'remote operation claims a Caddy stage receipt before that authority is available'
  fi
  if [[ "${operation_kind}" == STAGE ]]; then
    local operation_stage_index
    operation_stage_index="$(stage_index "${operation_name}")"
    if (( operation_stage_index >= 10 )); then
      [[ "${maintenance_smoke_sha}" =~ ^[0-9a-f]{64}$ ]] ||
        die 'remote stage lacks the immutable maintenance-smoke receipt'
    else
      [[ "${maintenance_smoke_sha}" == NONE ]] ||
        die 'remote stage claims maintenance-smoke authority before stage 9 completes'
    fi
    if (( operation_stage_index >= 18 )); then
      [[ "${maintenance_off_sha}" =~ ^[0-9a-f]{64}$ ]] ||
        die 'remote stage lacks the immutable maintenance-OFF receipt'
    else
      [[ "${maintenance_off_sha}" == NONE ]] ||
        die 'remote stage claims maintenance-OFF authority before stage 17 completes'
    fi
  fi
  case "${operation_kind}:${operation_name}:${action}" in
    STAGE:BASELINE_VERIFIED:baseline | \
      STAGE:PRE_DRAIN_BACKUP_REHEARSED:backup-rehearsal | \
      STAGE:CADDY_CANDIDATE_INSTALLED_AND_RELOADED:caddy-activate | \
      STAGE:PUBLIC_DRAIN_ACTIVE:public-drain-on | \
      STAGE:V125_BACKEND_STOPPED:stop-backend | \
      STAGE:ZERO_WRITER_GATE_PASSED:zero-writer | \
      STAGE:QUIESCED_BACKUP_REHEARSED:backup-rehearsal | \
      STAGE:FINAL_V125_PREFLIGHT_PASSED:final-v125-preflight | \
      STAGE:V126_MAINTENANCE_CONFIG_PREPARED:transform-maintenance | \
      STAGE:V126_IMAGE_TRANSFERRED_AND_VERIFIED:image-prepare | \
      STAGE:V126_IMAGE_TRANSFERRED_AND_VERIFIED:image-load | \
      STAGE:V126_BACKEND_STARTED:start-v126 | \
      STAGE:V126_SCHEMA_RUNTIME_GATE_PASSED:schema-runtime-gate | \
      STAGE:MANUAL_SMOKE_AUTHORIZED:open-manual-smoke | \
      STAGE:MANUAL_SMOKE_PASSED:record-manual-smoke | \
      STAGE:PUBLIC_DRAIN_REACTIVATED:public-drain-on | \
      STAGE:V126_BACKEND_STOPPED_FOR_OFF_TRANSITION:stop-backend | \
      STAGE:MAINTENANCE_OFF_CONFIG_VERIFIED:transform-maintenance | \
      STAGE:FINAL_V126_BACKEND_STARTED:start-v126 | \
      STAGE:ORDINARY_CADDY_RESTORED:restore-caddy | \
      STAGE:FINAL_PUBLIC_GATES_PASSED:final-public-gates | \
      RECOVERY:pre-v126:recover-pre-v126 | \
      RECOVERY:post-v126-stop:recover-post-v126-stop | \
      RECOVERY:verify-full-dr:verify-full-dr)
      ;;
    *) die 'remote action is not authorized by the current operation envelope' ;;
  esac
  case "${action}" in
    baseline) remote_baseline "$@" ;;
    backup-rehearsal) remote_backup_rehearsal "$@" ;;
    caddy-activate) remote_caddy_activate "$@" ;;
    public-drain-on) remote_public_drain_on "$@" ;;
    stop-backend) remote_stop_backend "$@" ;;
    zero-writer) remote_zero_writer_stage "$@" ;;
    final-v125-preflight) remote_final_v125_preflight "$@" ;;
    transform-maintenance) remote_transform_maintenance_config "$@" ;;
    image-prepare) remote_image_prepare "$@" ;;
    image-load) remote_image_load "$@" ;;
    start-v126) remote_start_v126 "$@" ;;
    schema-runtime-gate) remote_schema_runtime_gate "$@" ;;
    open-manual-smoke) remote_open_manual_smoke "$@" ;;
    record-manual-smoke) remote_record_manual_smoke "$@" ;;
    restore-caddy) remote_restore_caddy "$@" ;;
    final-public-gates) remote_final_public_gates "$@" ;;
    recover-pre-v126) remote_recover_pre_v126 "$@" ;;
    recover-post-v126-stop) remote_recover_post_v126_stop "$@" ;;
    verify-full-dr) remote_verify_full_dr "$@" ;;
    *) die "unknown remote action: ${action}" ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
