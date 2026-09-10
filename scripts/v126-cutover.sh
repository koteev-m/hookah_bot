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

  scripts/v126-cutover.sh inspect-target --target <absolute-local-target>
  scripts/v126-cutover.sh retire-target --target <absolute-local-target> \
    --state-dir <dir> --handoff-file <protected-approved-applied-handoff.json> \
    --operational-version V125|V126 --next-run-id <new-id> \
    --next-release-sha <40-hex> --next-script-sha256 <64-hex> \
    --authorization AUTHORIZE_V126_TARGET_BINDING_RETIREMENT

Binding commands run on the target filesystem, without SSH or daemon changes.
Retirement appends history only after terminal receipts and applied handoff proof.
UNKNOWN operations require a separate external daemon reconciliation decision.

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
  local digest
  if command -v sha256sum >/dev/null 2>&1; then
    digest="$(sha256sum "${path}" | awk '{print $1}')" || die 'file hash consumer failed'
  elif command -v shasum >/dev/null 2>&1; then
    digest="$(shasum -a 256 "${path}" | awk '{print $1}')" || die 'file hash consumer failed'
  else
    die 'sha256sum or shasum is required'
  fi
  [[ "${digest}" =~ ^[0-9a-f]{64}$ ]] || die 'file hash result is invalid'
  printf '%s\n' "${digest}"
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
      printf '%s\n' 'baseline-caddy,baseline-env,database-target-identity,database-url-binding,local-baseline,main-actions,maintenance-identities,remote-admission-source,remote-compose-source,remote-maintenance-check-source,staging-baseline'
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
  local path="${STATE_DIR}/receipts/$(printf '%02d' "$(stage_index "$1")")-$1"
  if [[ ! -e "${path}.receipt.json" && ! -L "${path}.receipt.json" &&
    ( -e "${path}.reconciliation.json" || -L "${path}.reconciliation.json" ) ]]; then
    printf '%s.reconciliation.json\n' "${path}"
  else
    printf '%s.receipt.json\n' "${path}"
  fi
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
  {
    release_ci_python
    remote_operation_bindings_python
    cat <<'PY'
import hashlib
import json
import os
import re
import stat
import sys

(
    manifest_path, receipts_dir, auth_dir, artifacts_dir, requested, current_script_sha,
    token_a, token_b, token_c, script_path, *expected_artifact_specs,
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
    reconciled_path = os.path.join(receipts_dir, f"{index:02d}-{stage}.reconciliation.json")
    reconciled = os.path.lexists(reconciled_path)
    if reconciled:
        if os.path.lexists(path):
            raise SystemExit('native and reconciled completion cannot coexist')
        path = reconciled_path
    doc, digest = read_canonical(path, path + ".sha256")
    expected_keys = {
        "artifacts", "authorization_gate", "authorization_receipt_sha256", "completed_at",
        "format_version", "intent_sha256", "predecessor_receipt_sha256", "predecessor_stage",
        "release_sha", "result_category", "run_id", "script_sha256", "stage",
    }
    if reconciled:
        expected_keys |= {'remote_evidence_sha256', 'original_operation_log_sha256'}
    version = 2 if reconciled else 1
    category = 'RECONCILED_EFFECT' if reconciled else 'PASS'
    if set(doc) != expected_keys or type(doc["format_version"]) is not int or doc["format_version"] != version:
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
        "format_version": version,
        "predecessor_receipt_sha256": previous_hash,
        "predecessor_stage": predecessor,
        "release_sha": manifest["release_sha"],
        "result_category": category,
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
    if reconciled:
        binding_retained_local_artifacts(os.path.dirname(receipts_dir), manifest, artifact_hashes)
    operation_path = os.path.join(artifacts_dir, f"{index}-{stage}.operation.log")
    if reconciled:
        operation_path = str(binding_original_stage_log(artifacts_dir, index, stage))
        binding_protected(Path(operation_path), 0o400)
        if binding_hash(Path(operation_path)) != doc['original_operation_log_sha256']:
            raise SystemExit('original failed operation log changed')
        bundle = Path(artifacts_dir) / f'{index}-{stage}.remote-reconciliation.json'
        if binding_hash(bundle) != doc['remote_evidence_sha256']:
            raise SystemExit('remote reconciliation bundle hash mismatch')
        record, remote_logs = binding_verify_reconciliation_bundle(
            bundle, manifest['staging_path'], current_script_sha, 'STAGE', stage, doc['intent_sha256'],
            hashlib.sha256(binding_embedded_source(Path(script_path).read_bytes(), 'remote_reconciliation_poststate_python')).hexdigest())
        if (record['identity']['run_id'] != manifest['run_id'] or
                record['identity']['release_sha'] != manifest['release_sha']):
            raise SystemExit('remote reconciliation run identity mismatch')
        derived = binding_reconciliation_log(Path(operation_path).read_bytes(), remote_logs)
        operation_path = os.path.join(artifacts_dir, f'{index}-{stage}.reconciliation.log')
        if Path(operation_path).read_bytes() != derived:
            raise SystemExit('reconciliation artifact inventory differs from original evidence')
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
    if stage == "BASELINE_VERIFIED":
        actions_sha = read_main_actions(
            os.path.join(artifacts_dir, "main-actions.json"),
            manifest["release_sha"], manifest["main_actions_run_id"], sealed=True,
        )
        if artifact_hashes["main-actions"] != actions_sha:
            raise SystemExit("main Actions evidence hash mismatch")
        expected_local = hashlib.sha256(local_baseline_bytes(manifest, actions_sha)).hexdigest()
        if artifact_hashes["local-baseline"] != expected_local:
            raise SystemExit("local baseline proof differs from current release CI contract")
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
  } | python3 - "${STATE_DIR}/run.json" "${STATE_DIR}/receipts" "${STATE_DIR}/authorizations" \
    "${STATE_DIR}/artifacts" "${stage}" "${SCRIPT_SHA256}" "${GATE_A_TOKEN}" \
    "${GATE_B_TOKEN}" "${GATE_C_TOKEN}" "${SCRIPT_PATH}" "${expected_artifact_specs[@]}"
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

write_reconciled_stage_completion() {
  local stage="$1" bundle="$2" index predecessor predecessor_hash gate authorization intent_hash
  index="$(stage_index "${stage}")"
  predecessor="$(stage_predecessor "${stage}")"
  predecessor_hash=NONE
  if [[ "${predecessor}" != NONE ]]; then
    predecessor_hash="$(verify_receipt "${predecessor}")" || die 'reconciliation predecessor is invalid'
  fi
  gate="$(stage_gate "${stage}")"
  authorization="$(authorization_hash_for_stage "${stage}")" || die 'reconciliation authorization is invalid'
  [[ "$(classify_status_record "$(intent_path "${stage}")" intent "${stage}" "${predecessor}" \
    "${predecessor_hash}" "${gate}" "${authorization}")" == RECONCILIATION_REQUIRED ]] || die 'reconciliation intent is invalid'
  intent_hash="$(hash_file "$(intent_path "${stage}")")"
  {
    remote_operation_bindings_python
    cat <<'PY'
state, stage, index, bundle_path, source_sha, script_path, expected_spec = sys.argv[1:]
if binding_hash(Path(script_path)) != source_sha: raise BindingError('source_changed')
binding_write_completion(state, 'STAGE', stage, int(index), bundle_path, script_path, expected_spec)
PY
  } | python3 - "${STATE_DIR}" "${stage}" "${index}" "${bundle}" "${SCRIPT_SHA256}" "${SCRIPT_PATH}" "$(stage_expected_artifacts "${stage}")" ||
    die 'reconciled completion evidence is incomplete; no mutation retry is allowed'
  verify_receipt "${stage}" >/dev/null || die 'reconciled completion failed canonical validation'
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
  ( tracked_child_wait_for_release "${gate_path}" || exit $?; cutover_bounded_command 1860 "$@" ) &
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
  ( tracked_child_wait_for_release "${gate_path}" || exit $?; cutover_bounded_command 1860 "$@" < "${input_path}" ) &
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

cutover_bounded_command() {
  python3 -c '
import fcntl
import math
import os
import select
import signal
import subprocess
import sys
import time

try:
    seconds = float(sys.argv[1])
    if not math.isfinite(seconds) or seconds <= 0 or not sys.argv[2:]:
        raise ValueError()
except (IndexError, ValueError):
    raise SystemExit("invalid bounded command")
child = None
reaped = False
cancelled = None
flags = {}
nonblocking = set()
streams = {}
pending = {1: bytearray(), 2: bytearray()}
queue_limit = 65536
deadline = time.monotonic() + seconds

class Refusal(Exception):
    def __init__(self, reason, status):
        self.reason, self.status = reason, status

def interrupted(signum, frame):
    global cancelled
    if cancelled is None:
        cancelled = signum

for signum in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
    signal.signal(signum, interrupted)

def stop_child():
    global reaped
    if child is None or reaped:
        return
    # WNOWAIT retains the leader identity until this group signal. Never signal
    # a reaped PGID. Escaped children/daemon effects remain the outer operation
    # supervisor responsibility and cannot be discharged by this I/O timeout.
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass
    try:
        child.wait(timeout=1)
        reaped = True
    except (subprocess.TimeoutExpired, OSError):
        pass

def emit_unknown(reason):
    if 2 not in nonblocking:
        return
    if reason == "deadline":
        payload = b"IO_OUTCOME=UNKNOWN reason=deadline retry_allowed=false next_action=reconcile_remote_operation\n"
    else:
        payload = ("IO_OUTCOME=UNKNOWN reason=" + reason +
                   " retry_allowed=false next_action=reconcile_remote_operation\n").encode()
    # A blocked diagnostic sink cannot extend the command deadline. The nonzero
    # exit and outer durable operation result remain authoritative in that case.
    try:
        os.write(2, payload)
    except (BlockingIOError, BrokenPipeError, InterruptedError, OSError):
        pass

status = 125
try:
    # Snapshot both before changing either: callers may use 2>&1, sharing flags.
    flags = {fd: fcntl.fcntl(fd, fcntl.F_GETFL) for fd in (1, 2)}
    for fd in (1, 2):
        fcntl.fcntl(fd, fcntl.F_SETFL, flags[fd] | os.O_NONBLOCK)
        nonblocking.add(fd)
    # Private pipes prevent a detached privileged waiter from keeping a Bash
    # command substitution open after this helper has refused its outcome.
    child = subprocess.Popen(sys.argv[2:], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             start_new_session=True)
    for stream, target in ((child.stdout, 1), (child.stderr, 2)):
        os.set_blocking(stream.fileno(), False)
        streams[stream.fileno()] = target
    exited = None
    while True:
        if cancelled is not None:
            raise Refusal("interrupted", 128 + cancelled)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise Refusal("deadline", 124)
        if exited is None:
            exited = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        reads = [fd for fd, target in streams.items() if len(pending[target]) < queue_limit]
        writes = [fd for fd, payload in pending.items() if payload]
        # select also accepts regular-file sinks; epoll cannot register them.
        ready_reads, ready_writes, _ = select.select(reads, writes, [], min(.05, remaining))
        for fd in ready_reads:
            target = streams[fd]
            try:
                payload = os.read(fd, queue_limit - len(pending[target]))
            except (BlockingIOError, InterruptedError):
                continue
            if payload:
                pending[target].extend(payload)
            else:
                del streams[fd]
        for fd in ready_writes:
            try:
                written = os.write(fd, pending[fd])
            except (BlockingIOError, InterruptedError):
                continue
            if written <= 0:
                raise Refusal("output_unavailable", 125)
            del pending[fd][:written]
        if exited is not None and streams and len(reads) == len(streams) and not ready_reads:
            raise Refusal("child_survived", 124)
        if exited is not None and not streams and not any(pending.values()):
            status = child.wait(timeout=max(.001, deadline - time.monotonic()))
            reaped = True
            # Conservative refusal only: this no longer-owned group identifier
            # must never be signalled. The target supervisor proves descendants.
            try:
                os.killpg(child.pid, 0)
            except ProcessLookupError:
                pass
            else:
                raise Refusal("child_survived", 124)
            if cancelled is not None:
                raise Refusal("interrupted", 128 + cancelled)
            if time.monotonic() >= deadline:
                raise Refusal("deadline", 124)
            status = status if status >= 0 else 128 - status
            break
except Refusal as error:
    status = error.status
    stop_child()
    emit_unknown(error.reason)
except subprocess.TimeoutExpired:
    stop_child()
    status = 124
    emit_unknown("deadline")
except (OSError, ValueError):
    stop_child()
    status = 125
    emit_unknown("consumer_unavailable" if child is None else "consumer_io")
finally:
    if child is not None:
        for stream in (child.stdout, child.stderr):
            try:
                stream.close()
            except OSError:
                status = status or 125
    for fd, original in flags.items():
        try:
            fcntl.fcntl(fd, fcntl.F_SETFL, original)
        except OSError:
            status = status or 125
        nonblocking.discard(fd)
if status == 0 and cancelled is not None:
    status = 128 + cancelled
if status == 0 and time.monotonic() >= deadline:
    status = 124
raise SystemExit(status)
' "$@"
}

remote_wait_backend_ready() {
  local expected_container="$1"
  local expected_image="$2"
  local expected_release="$3"
  local phase="$4"
  local expected_env_sha="$5"
  python3 - "${expected_container}" "${expected_image}" "${expected_release}" \
    "${phase}" "${expected_env_sha}" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time

container, image, release, phase, env_sha = sys.argv[1:]
# A single start precedes this read-only observer. These limits cannot authorize a retry.
TOTAL_SECONDS = 120.0
REQUEST_SECONDS = 5.0
POLL_SECONDS = 1.0
deadline = time.monotonic() + TOTAL_SECONDS
active = None

def finish(outcome, reason, status):
    print("READINESS=" + outcome + " reason=" + reason +
          " retry_allowed=false next_action=" +
          ("continue_current_operation" if status == 0 else "reconcile_remote_operation"), file=sys.stderr)
    raise SystemExit(status)

def kill_child():
    if active is not None:
        try:
            os.killpg(active.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        active.wait()

def interrupted(signum, frame):
    kill_child()
    finish("UNKNOWN", "interrupted", 128 + signum)

for signum in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
    signal.signal(signum, interrupted)

def read_command(argv, limit=1048576):
    global active
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        finish("UNKNOWN", "deadline", 75)
    command_deadline = time.monotonic() + min(REQUEST_SECONDS, remaining)
    try:
        active = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError:
        finish("UNKNOWN", "consumer_unavailable", 75)
    payload = bytearray()
    timed_out = False
    with selectors.DefaultSelector() as selector:
        selector.register(active.stdout, selectors.EVENT_READ)
        while selector.get_map():
            wait = command_deadline - time.monotonic()
            if wait <= 0:
                timed_out = True
                break
            for key, _ in selector.select(wait):
                chunk = os.read(key.fd, 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                payload.extend(chunk)
                if len(payload) > limit:
                    kill_child()
                    finish("FAILED", "observation_too_large", 4)
    if timed_out:
        kill_child()
        active.stdout.close()
        active = None
        if time.monotonic() >= deadline:
            finish("UNKNOWN", "deadline", 75)
        return None, b""
    try:
        status = active.wait(timeout=max(0.001, command_deadline - time.monotonic()))
    except subprocess.TimeoutExpired:
        kill_child()
        status = None
    active.stdout.close()
    active = None
    if time.monotonic() >= deadline:
        finish("UNKNOWN", "deadline", 75)
    return status, bytes(payload)

critical = {
    "TELEGRAM_BOT_ENABLED", "TELEGRAM_BOT_MODE", "TELEGRAM_TRAFFIC_POLICY",
    "TELEGRAM_ALLOWED_USER_IDS", "TELEGRAM_ALLOWED_CHAT_IDS", "STAGING_MAINTENANCE_MODE",
    "STAGING_MAINTENANCE_ALLOWED_USER_IDS", "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS",
}
if not re.fullmatch(r"[0-9a-f]{64}", container) or not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
    finish("FAILED", "invalid_expected_identity", 4)
if not re.fullmatch(r"[0-9a-f]{40}", release) or not re.fullmatch(r"[0-9a-f]{64}", env_sha):
    finish("FAILED", "invalid_expected_binding", 4)
if phase not in ("first", "final", "pre-v126"):
    finish("FAILED", "invalid_phase", 4)

def expected_environment():
    try:
        payload = Path(".env").read_bytes()
        if hashlib.sha256(payload).hexdigest() != env_sha:
            finish("FAILED", "bound_environment_changed", 4)
        values = {}
        for row in payload.decode("utf-8").splitlines():
            if "=" in row:
                key, value = row.split("=", 1)
                if key in critical:
                    if key in values:
                        finish("FAILED", "duplicate_bound_environment", 4)
                    values[key] = value
        if set(values) != critical:
            finish("FAILED", "incomplete_bound_environment", 4)
        fixed = {"TELEGRAM_BOT_ENABLED": "true", "TELEGRAM_BOT_MODE": "long_polling",
                 "TELEGRAM_TRAFFIC_POLICY": "PRODUCT", "TELEGRAM_ALLOWED_USER_IDS": "",
                 "TELEGRAM_ALLOWED_CHAT_IDS": "",
                 "STAGING_MAINTENANCE_MODE": "V126_SMOKE" if phase == "first" else "OFF"}
        if any(values[key] != value for key, value in fixed.items()):
            finish("FAILED", "bound_environment_policy", 4)
        for key in ("STAGING_MAINTENANCE_ALLOWED_USER_IDS", "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS"):
            if bool(values[key]) != (phase == "first"):
                finish("FAILED", "bound_environment_allowlists", 4)
        return values
    except (OSError, UnicodeError):
        finish("UNKNOWN", "bound_environment_unavailable", 75)

def observe_container():
    expected = expected_environment()
    status, payload = read_command(["docker", "inspect", container])
    if status != 0:
        finish("UNKNOWN", "container_inspection_failed", 75)
    try:
        rows = json.loads(payload)
        if not isinstance(rows, list) or len(rows) != 1:
            raise ValueError()
        row = rows[0]
        labels = row["Config"]["Labels"]
        project = labels["com.docker.compose.project"]
        if (row["Id"] != container or row["Image"] != image or
                labels["com.docker.compose.service"] != "backend" or not project or
                row["HostConfig"]["RestartPolicy"]["Name"] != "no" or row["RestartCount"] != 0):
            finish("FAILED", "container_identity_or_restart", 4)
        values = {}
        for value in row["Config"]["Env"]:
            key, value = value.split("=", 1)
            if key in critical:
                values.setdefault(key, []).append(value)
        if any(values.get(key) != [value] for key, value in expected.items()):
            finish("FAILED", "container_environment_changed", 4)
        state = row["State"]
        if state["Status"] in ("exited", "dead", "restarting", "removing") or state.get("OOMKilled"):
            finish("FAILED", "container_terminal_state", 4)
        if state.get("Paused"):
            finish("FAILED", "container_paused", 4)
        running = state["Status"] == "running" and state["Running"] is True
        if not running and state["Status"] != "created":
            finish("UNKNOWN", "container_state_unknown", 75)
    except (ValueError, KeyError, TypeError, AttributeError):
        finish("UNKNOWN", "container_inventory_invalid", 75)
    status, payload = read_command(["docker", "ps", "--all", "--quiet", "--no-trunc",
                                    "--filter", "label=com.docker.compose.project=" + project,
                                    "--filter", "label=com.docker.compose.service=backend"])
    if status != 0:
        finish("UNKNOWN", "container_inventory_failed", 75)
    if payload.decode("ascii", errors="replace").splitlines() != [container]:
        finish("FAILED", "container_scope_not_unique", 4)
    return running

def probe(path, kind):
    status, payload = read_command([
        "curl", "--disable", "--silent", "--noproxy", "*", "--proto", "=http",
        "--connect-timeout", "2", "--max-time", str(REQUEST_SECONDS),
        "--max-filesize", "16384", "--write-out", "\n%{http_code}",
        "http://127.0.0.1:8080" + path], limit=16400)
    if status in (None, 7, 28, 52, 56):
        return False
    if status != 0:
        finish("FAILED", "http_consumer_failed", 4)
    body, separator, code = payload.rpartition(b"\n")
    if not separator:
        finish("FAILED", "http_status_missing", 4)
    if code == b"503":
        return False
    if code != b"200":
        finish("FAILED", "http_status_unexpected", 4)
    try:
        value = json.loads(body)
        if kind == "health":
            good = value == {"status": "ok"}
        else:
            good = (isinstance(value, dict) and value.get("service") == "backend" and
                    value.get("env") == "staging" and value.get("version") == release)
        if not good:
            finish("FAILED", "http_identity_mismatch", 4)
    except (ValueError, UnicodeError):
        finish("FAILED", "http_json_invalid", 4)
    return True

announced = False
while True:
    running = observe_container()
    if running and all(probe(path, kind) for path, kind in
                       (("/health", "health"), ("/db/health", "health"), ("/version", "version"))):
        # Fence successful HTTP observations with the same process/configuration identity.
        if observe_container():
            finish("READY", "same_container_health_version", 0)
    if not announced:
        print("READINESS=STARTING reason=not_ready retry_allowed=false", file=sys.stderr)
        announced = True
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        finish("UNKNOWN", "deadline", 75)
    time.sleep(min(POLL_SECONDS, remaining))
PY
}

verify_remote_operation_ack() {
  local capture="$1"
  local action="$2"
  python3 - "${capture}" "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}" \
    "${ACTIVE_INTENT_HASH}" "${ACTIVE_OPERATION_KIND}" "${ACTIVE_OPERATION_NAME}" "${action}" <<'PY'
import hashlib, json, os, stat, sys
path, *values = sys.argv[1:]
info = os.lstat(path)
if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
    raise SystemExit('remote acknowledgement capture metadata invalid')
raw = open(path, 'rb').read()
prefix = b'\nREMOTE_OPERATION_ACK\t'
if raw.count(prefix) != 1:
    raise SystemExit('remote completion acknowledgement is missing or ambiguous')
log, encoded = raw.split(prefix)
try:
    outcome = json.loads(encoded)
except (ValueError, UnicodeError):
    raise SystemExit('remote acknowledgement is malformed') from None
identity = dict(zip(('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'), values))
canonical = lambda doc: (json.dumps(doc, sort_keys=True, separators=(',', ':')) + '\n').encode()
if (set(outcome) != {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} or
        encoded != canonical(outcome) or outcome['identity'] != identity or
        outcome['operation_id'] != hashlib.sha256(canonical(identity)).hexdigest() or
        type(outcome['exit']) is not int or outcome['exit'] != 0 or
        outcome['outcome'] != 'SUCCEEDED' or outcome['children'] != 'REAPED' or
        outcome['log_sha256'] != hashlib.sha256(log).hexdigest() or
        not isinstance(outcome['completed_at'], str) or not outcome['completed_at']):
    raise SystemExit('remote completion acknowledgement failed identity/outcome validation')
sys.stdout.buffer.write(log)
PY
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
  local baseline_database_identity='NONE'
  local baseline_identities_sha='NONE'
  local baseline_compose_sha='NONE'
  local baseline_maintenance_sha='NONE'
  local baseline_admission_sha='NONE'
  local baseline_caddy_sha='NONE'
  local baseline_env_sha='NONE'
  if [[ "${ACTIVE_OPERATION_KIND}:${ACTIVE_OPERATION_NAME}:${action}" != \
    STAGE:BASELINE_VERIFIED:baseline ]]; then
    baseline_database_sha="$(receipt_artifact_hash BASELINE_VERIFIED database-url-binding)"
    baseline_database_identity="$(receipt_artifact_hash BASELINE_VERIFIED database-target-identity)"
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
  local status=0
  (
    cat <<'REMOTE_LOADER'
# Bash must parse the entire loader before any read consumes the following data.
{
set -Eeuo pipefail
umask 077
export LC_ALL=C
loader_die() {
  printf 'V126 cutover contract rejected: %s\n' "$*" >&2
  exit 4
}
# An explicit NUL delimiter prevents Bash from silently discarding NUL bytes.
# EOF (read status 1) preserves the complete text, including trailing newlines.
remote_envelope_content=''
loader_status=0
IFS= read -r -d '' remote_envelope_content || loader_status=$?
[[ "${loader_status}" == 0 || "${loader_status}" == 1 ]] || loader_die 'invalid internal remote envelope encoding'
loader_read() {
  [[ "${remote_envelope_content}" == *$'\n'* ]] || loader_die "truncated internal remote envelope: $1"
  printf -v "$1" '%s' "${remote_envelope_content%%$'\n'*}"
  remote_envelope_content="${remote_envelope_content#*$'\n'}"
}
loader_read magic
loader_read action
if [[ "${action}" == image-upload || "${action}" == preflight-upload ]]; then
  [[ "${loader_status}" == 0 ]] || loader_die 'upload payload delimiter is absent'
else
  [[ "${loader_status}" == 1 ]] || loader_die 'invalid internal remote envelope encoding'
fi
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
loader_read baseline_database_identity
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
remote_body_content="${remote_envelope_content}"
unset remote_envelope_content
remote_body_sha="$(printf '%s' "${remote_body_content}" | sha256sum | awk '{print $1}')" ||
  loader_die 'streamed sequencer body could not be hashed'
[[ "${remote_body_sha}" == "${envelope_script_sha}" ]] || loader_die 'streamed sequencer identity mismatch'
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
export V126_INTERNAL_REMOTE_DATABASE_TARGET_IDENTITY_SHA256="${baseline_database_identity}"
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
V126_REMOTE_VERIFIED_BODY="${remote_body_content}"
unset remote_body_content
remote_dispatch_enveloped "${action}" "${remote_args[@]}"
loader_status=$?
exit "${loader_status}"
}
REMOTE_LOADER
    [[ $? == 0 ]] || exit 4
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
      "${baseline_database_identity}" \
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
      "$#" || exit 4
    printf '%s\n' "$@" || exit 4
    cat "${SCRIPT_PATH}" || exit 4
    if [[ "${action}" == image-upload || "${action}" == preflight-upload ]]; then
      [[ "${V126_LOCAL_UPLOAD_FD:-}" =~ ^[0-9]+$ ]] || exit 4
      printf '\0' || exit 4
      python3 -c 'import os,sys
fd=int(sys.argv[1]); size=os.fstat(fd).st_size
if not 0 < size <= 8*1024**3: raise SystemExit("upload size refused")
os.lseek(fd,0,os.SEEK_SET)
while True:
    block=os.read(fd,1024*1024)
    if not block: break
    sys.stdout.buffer.write(block)
' "${V126_LOCAL_UPLOAD_FD}" || exit 4
    fi
  ) > "${stream}" || status=$?
  if (( status != 0 )); then
    rm -f -- "${stream}"
    die 'internal remote stream could not be produced'
  fi
  if ! chmod 0600 "${stream}"; then
    rm -f -- "${stream}"
    die 'internal remote stream could not be protected'
  fi
  local capture="${stream}.output"
  [[ ! -e "${capture}" && ! -L "${capture}" ]] || die 'remote capture path already exists'
  if run_tracked_command_with_input remote-ssh "${stream}" ssh "${REMOTE}" bash -s > "${capture}"; then
    status=0
  else
    status=$?
  fi
  rm -f -- "${stream}"
  if (( status == 0 )); then
    verify_remote_operation_ack "${capture}" "${action}" || status=$?
  fi
  # Failed transport output is evidence only; it must never reach artifact consumers.
  if (( status != 0 )); then
    chmod 0400 "${capture}" || return 4
    printf 'REMOTE_OUTCOME=UNKNOWN retry_allowed=false next_action=RECONCILE_TARGET_RECORDS\n' >&2
    return "${status}"
  fi
  rm -f -- "${capture}"
  return 0
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

read_attempt_state() {
  python3 - "${STATE_DIR}/attempts" "${RUN_ID}" "${SCRIPT_SHA256}" <<'PY'
import hashlib, json, os, re, stat, sys
from pathlib import Path
root = Path(sys.argv[1])
def metadata(path, mode, directory=False):
    info = path.lstat()
    return ((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)) and
            stat.S_IMODE(info.st_mode) == mode and info.st_uid == os.getuid() and
            (directory or info.st_nlink == 1))
def classify():
    if not root.exists() and not root.is_symlink():
        return 'NOT_STARTED'
    if not metadata(root, 0o700, True):
        return 'INVALID_EVIDENCE'
    incomplete = False
    for attempt in root.iterdir():
        if not re.fullmatch(r'baseline\.[A-Za-z0-9]+', attempt.name) or not metadata(attempt, 0o700, True):
            return 'INVALID_EVIDENCE'
        result = attempt / 'result.json'
        if not result.exists() and not result.is_symlink():
            incomplete = True
            continue
        if not metadata(result, 0o400):
            return 'INVALID_EVIDENCE'
        raw = result.read_bytes()
        doc = json.loads(raw)
        if (raw != (json.dumps(doc, sort_keys=True, separators=(',', ':')) + '\n').encode() or
                set(doc) != {'dispatch', 'phase', 'run_id', 'script_sha256', 'exit', 'files'} or
                doc['dispatch'] != 'NOT_DISPATCHED' or doc['phase'] != 'READ_ONLY_PRECHECK' or
                doc['run_id'] != sys.argv[2] or doc['script_sha256'] != sys.argv[3] or
                type(doc['exit']) is not int or not 0 <= doc['exit'] <= 255 or
                not isinstance(doc['files'], dict) or
                not {'started.proof', 'operation.log'} <= set(doc['files'])):
            return 'INVALID_EVIDENCE'
        if set(p.name for p in attempt.iterdir()) != set(doc['files']) | {'result.json'}:
            return 'INVALID_EVIDENCE'
        for name, digest in doc['files'].items():
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', name) or not isinstance(digest, str):
                return 'INVALID_EVIDENCE'
            path = attempt / name
            if not metadata(path, 0o400) or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                return 'INVALID_EVIDENCE'
        if (attempt / 'started.proof').read_bytes() != b'phase=READ_ONLY_PRECHECK\ndispatch=NOT_DISPATCHED\n':
            return 'INVALID_EVIDENCE'
    return 'RECONCILIATION_REQUIRED' if incomplete else 'NOT_DISPATCHED'
try:
    outcome = classify()
except (OSError, ValueError, TypeError, KeyError):
    outcome = 'INVALID_EVIDENCE'
print(outcome)
PY
}

run_baseline_prechecks() {
  local attempts="${STATE_DIR}/attempts" attempt_state
  attempt_state="$(read_attempt_state)" || die 'attempt evidence query failed'
  case "${attempt_state}" in
    NOT_STARTED | NOT_DISPATCHED) ;;
    *) die 'read attempt evidence is incomplete or invalid; retry is forbidden' ;;
  esac
  if [[ ! -e "${attempts}" && ! -L "${attempts}" ]]; then
    mkdir -m 0700 "${attempts}" || die 'attempt namespace creation failed'
  fi
  BASELINE_ATTEMPT_DIR="$(mktemp -d "${attempts}/baseline.XXXXXXXX")" || die 'attempt creation failed'
  printf 'phase=READ_ONLY_PRECHECK\ndispatch=NOT_DISPATCHED\n' > "${BASELINE_ATTEMPT_DIR}/started.proof" ||
    die 'read attempt start record failed'
  local status=0
  cutover_bounded_command 180 bash -c '
    source "$1"
    load_state "$2"
    BASELINE_ATTEMPT_DIR="$3"
    verify_release_baseline_local
  ' v126-read-precheck "${SCRIPT_PATH}" "${STATE_DIR}" "${BASELINE_ATTEMPT_DIR}" \
    > "${BASELINE_ATTEMPT_DIR}/operation.log" 2>&1 || status=$?
  python3 - "${BASELINE_ATTEMPT_DIR}" "${RUN_ID}" "${SCRIPT_SHA256}" "${status}" <<'PY'
import hashlib, json, os, stat, sys
from pathlib import Path
root, run, source, code = sys.argv[1:]
root = Path(root)
files = {}
for p in root.iterdir():
    info = p.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
        raise SystemExit('invalid precheck artifact')
    p.chmod(0o400)
    files[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
doc = dict(dispatch='NOT_DISPATCHED', phase='READ_ONLY_PRECHECK', run_id=run,
           script_sha256=source, exit=int(code), files=files)
fd = os.open(root / 'result.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
with os.fdopen(fd, 'wb') as f:
    f.write((json.dumps(doc, sort_keys=True, separators=(',', ':')) + '\n').encode())
    f.flush(); os.fsync(f.fileno())
PY
  [[ $? == 0 ]] || die 'read attempt could not be sealed'
  if (( status != 0 )); then
    printf 'BASELINE_PRECHECK=FAILED dispatch=NOT_DISPATCHED retry_allowed=true evidence=%s\n' \
      "${BASELINE_ATTEMPT_DIR}" >&2
    return "${status}"
  fi
  BASELINE_PRECHECK_COMPLETED=true
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
  BASELINE_PRECHECK_COMPLETED=false
  BASELINE_ATTEMPT_DIR=''
  if [[ "${stage}" == BASELINE_VERIFIED ]]; then
    local precheck_status=0
    run_baseline_prechecks || precheck_status=$?
    if (( precheck_status != 0 )); then
      release_state_lock
      clear_state_lock_traps
      return "${precheck_status}"
    fi
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

classify_status_record() {
  python3 - "${STATE_DIR}/run.json" "$1" "$2" "${3:-}" "${4:-}" "${5:-}" "${6:-}" "${7:-}" <<'PY'
import datetime, hashlib, json, os, re, stat, sys
from pathlib import Path
manifest, path, kind, stage, predecessor, previous_hash, gate, authorization = sys.argv[1:]
def checked(path):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o400 or
            info.st_uid != os.getuid() or info.st_nlink != 1):
        raise ValueError('record metadata')
    return path.read_bytes()
try:
    path = Path(path)
    raw = checked(path)
    if checked(Path(str(path) + '.sha256')) != (hashlib.sha256(raw).hexdigest() + '\n').encode():
        raise ValueError('checksum')
    doc = json.loads(raw)
    if raw != (json.dumps(doc, sort_keys=True, separators=(',', ':')) + '\n').encode():
        raise ValueError('canonical JSON')
    run = json.loads(Path(manifest).read_bytes())
    for key in ('run_id', 'release_sha', 'script_sha256'):
        if doc[key] != run[key]:
            raise ValueError('identity')
    if type(doc['format_version']) is not int or doc['format_version'] != 1:
        raise ValueError('version')
    if kind == 'terminal':
        if set(doc) != {'format_version', 'mode', 'release_sha', 'run_id', 'script_sha256', 'status', 'terminal_at'}:
            raise ValueError('terminal schema')
        if doc['mode'] not in ('pre-v126', 'post-v126-stop', 'verify-full-dr') or doc['status'] != 'RECOVERY_INTENT_RECORDED_NO_STAGE_CONTINUATION':
            raise ValueError('terminal state')
        timestamp = doc['terminal_at']
    else:
        if set(doc) != {'authorization_gate', 'authorization_receipt_sha256', 'format_version', 'intent_at', 'kind', 'predecessor_receipt_sha256', 'predecessor_stage', 'release_sha', 'run_id', 'script_sha256', 'stage'}:
            raise ValueError('intent schema')
        expected = dict(kind='STAGE_INTENT', stage=stage, predecessor_stage=predecessor,
                        predecessor_receipt_sha256=previous_hash, authorization_gate=gate,
                        authorization_receipt_sha256=authorization)
        if any(doc[key] != value for key, value in expected.items()):
            raise ValueError('intent binding')
        timestamp = doc['intent_at']
    datetime.datetime.strptime(timestamp, '%Y-%m-%dT%H:%M:%SZ')
    print('RECONCILIATION_REQUIRED')
except (OSError, ValueError, TypeError, KeyError):
    print('INVALID_EVIDENCE')
PY
}

read_recovery_state() {
  python3 - "${STATE_DIR}/run.json" "${STATE_DIR}/recovery" <<'PY'
import hashlib, json, os, re, stat, sys
from pathlib import Path
try:
    run = json.loads(Path(sys.argv[1]).read_bytes())
    root = Path(sys.argv[2])
    entries = list(root.iterdir())
    for path in entries:
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1 or
                stat.S_IMODE(info.st_mode) not in (0o400, 0o600)):
            raise ValueError('metadata')
        if not (re.fullmatch(r'(pre-v126|post-v126-stop|verify-full-dr)\.(intent\.json|receipt\.json|reconciliation\.json|operation\.log)(\.sha256)?', path.name) or path.name == 'dr-boundary.json'):
            raise ValueError('inventory')
        if path.name.endswith('.sha256'):
            original = Path(str(path)[:-7])
            if original.is_symlink() or path.read_bytes() != (hashlib.sha256(original.read_bytes()).hexdigest() + '\n').encode():
                raise ValueError('checksum')
        elif path.name.endswith('.intent.json'):
            raw = path.read_bytes()
            doc = json.loads(raw)
            if (set(doc) != {'authorization_token_sha256', 'format_version', 'intent_at', 'kind', 'mode', 'predecessor_receipt_sha256', 'predecessor_stage', 'release_sha', 'run_id', 'script_sha256'} or
                    raw != (json.dumps(doc, sort_keys=True, separators=(',', ':')) + '\n').encode() or
                    doc['kind'] != 'RECOVERY_INTENT' or doc['format_version'] != 1 or
                    doc['mode'] != path.name[:-12] or
                    any(doc[key] != run[key] for key in ('run_id', 'release_sha', 'script_sha256')) or
                    not re.fullmatch(r'[0-9a-f]{64}', doc['authorization_token_sha256'])):
                raise ValueError('intent')
    print('RECONCILIATION_REQUIRED' if entries else 'ABSENT')
except (OSError, ValueError, TypeError, KeyError):
    print('INVALID_EVIDENCE')
PY
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
  local stage next=NONE outcome=COMPLETE attempt_state terminal_state=ABSENT recovery_state
  local intent receipt predecessor previous_hash gate authorization
  attempt_state="$(read_attempt_state)" || attempt_state=INVALID_EVIDENCE
  recovery_state="$(read_recovery_state)" || recovery_state=INVALID_EVIDENCE
  local recovery_mode recovery_pending=false recovery_completed=false recovery_reconciled=false last_recovery=NONE
  for recovery_mode in pre-v126 post-v126-stop verify-full-dr; do
    if [[ -e "$(recovery_receipt_path "${recovery_mode}")" || -L "$(recovery_receipt_path "${recovery_mode}")" ]]; then
      if (verify_recovery_receipt "${recovery_mode}" &&
        verify_reconciliation_recovery_intent "${recovery_mode}") >/dev/null 2>&1; then
        recovery_completed=true
        last_recovery="${recovery_mode}"
        [[ "$(recovery_receipt_path "${recovery_mode}")" != *.reconciliation.json ]] || recovery_reconciled=true
      else
        recovery_state=INVALID_EVIDENCE
      fi
    elif [[ -e "${STATE_DIR}/recovery/${recovery_mode}.intent.json" || -L "${STATE_DIR}/recovery/${recovery_mode}.intent.json" ]]; then
      recovery_pending=true
    fi
  done
  if [[ "${recovery_state}" != INVALID_EVIDENCE && "${recovery_completed}" == true && "${recovery_pending}" == false ]]; then
    recovery_state=COMPLETED
  fi
  if [[ -e "${STATE_DIR}/run-terminal.json" || -L "${STATE_DIR}/run-terminal.json" ||
        -e "${STATE_DIR}/run-terminal.json.sha256" || -L "${STATE_DIR}/run-terminal.json.sha256" ]]; then
    terminal_state="$(classify_status_record "${STATE_DIR}/run-terminal.json" terminal)" || terminal_state=INVALID_EVIDENCE
  fi
  printf 'run_id=%s\nrelease_sha=%s\nscript_sha256=%s\n' "${RUN_ID}" "${RELEASE_SHA}" "${SCRIPT_SHA256}"
  printf 'read_attempts=%s\nrecovery_records=%s\n' "${attempt_state}" "${recovery_state}"
  for stage in "${V126_STAGES[@]}"; do
    if verify_receipt "${stage}" >/dev/null 2>&1; then
      if [[ "$(receipt_path "${stage}")" == *.reconciliation.json ]]; then
        printf '%s=RECONCILED_EFFECT\n' "${stage}"
      else
        printf '%s=PASS\n' "${stage}"
      fi
      continue
    fi
    receipt="$(receipt_path "${stage}")"
    intent="$(intent_path "${stage}")"
    if [[ -e "${receipt}" || -L "${receipt}" || -e "${receipt}.sha256" || -L "${receipt}.sha256" ]]; then
      outcome=INVALID_EVIDENCE
    elif [[ -e "${intent}" || -L "${intent}" || -e "${intent}.sha256" || -L "${intent}.sha256" ]]; then
      predecessor="$(stage_predecessor "${stage}")"
      previous_hash=NONE
      if [[ "${predecessor}" != NONE ]]; then
        previous_hash="$(verify_receipt "${predecessor}")" || previous_hash=INVALID
      fi
      gate="$(stage_gate "${stage}")"
      authorization="$(authorization_hash_for_stage "${stage}" 2>/dev/null)" || authorization=INVALID
      outcome="$(classify_status_record "${intent}" intent "${stage}" "${predecessor}" "${previous_hash}" "${gate}" "${authorization}")" || outcome=INVALID_EVIDENCE
    else
      outcome=NOT_STARTED
      next="${stage}"
    fi
    case "${attempt_state}" in
      INVALID_EVIDENCE) outcome=INVALID_EVIDENCE ;;
      RECONCILIATION_REQUIRED) [[ "${outcome}" == INVALID_EVIDENCE ]] || outcome=RECONCILIATION_REQUIRED ;;
    esac
    case "${terminal_state}" in
      INVALID_EVIDENCE) outcome=INVALID_EVIDENCE ;;
      RECONCILIATION_REQUIRED) [[ "${outcome}" == INVALID_EVIDENCE ]] || outcome=RECONCILIATION_REQUIRED ;;
    esac
    case "${recovery_state}" in
      INVALID_EVIDENCE) outcome=INVALID_EVIDENCE ;;
      RECONCILIATION_REQUIRED) [[ "${outcome}" == INVALID_EVIDENCE ]] || outcome=RECONCILIATION_REQUIRED ;;
    esac
    [[ "${outcome}" == NOT_STARTED ]] || next=NONE
    printf '%s=%s\n' "${stage}" "${outcome}"
    break
  done
  # Prior evidence corruption blocks even a complete receipt chain.
  case "${attempt_state}:${terminal_state}:${recovery_state}" in
    *INVALID_EVIDENCE*) outcome=INVALID_EVIDENCE; next=NONE ;;
    *RECONCILIATION_REQUIRED*) [[ "${outcome}" == INVALID_EVIDENCE ]] || outcome=RECONCILIATION_REQUIRED; next=NONE ;;
  esac
  if [[ "${outcome}" != INVALID_EVIDENCE && "${recovery_state}" == COMPLETED &&
    "${terminal_state}" == RECONCILIATION_REQUIRED && "${attempt_state}" != RECONCILIATION_REQUIRED ]]; then
    outcome=TERMINAL_RECOVERY_COMPLETE
    [[ "${recovery_reconciled}" != true ]] || outcome=TERMINAL_RECOVERY_RECONCILED
    next=NONE
  fi
  printf 'canonical_execution=%s\nnext_stage=%s\nretry_allowed=false\navailability=NOT_OBSERVED\n' "${outcome}" "${next}"
  case "${outcome}" in
    NOT_STARTED) printf 'next_action=EXECUTE_NEXT_AUTHORIZED_STAGE\n' ;;
    COMPLETE) printf 'next_action=REVIEW_OPERATIONAL_HANDOFF\n' ;;
    TERMINAL_RECOVERY_COMPLETE | TERMINAL_RECOVERY_RECONCILED)
      case "${last_recovery}" in
        pre-v126) printf 'next_action=REVIEW_V125_OPERATIONAL_HANDOFF\n' ;;
        post-v126-stop) printf 'next_action=FORWARD_FIX_REQUIRED\n' ;;
        verify-full-dr) printf 'next_action=DR_AUTHORIZATION_REQUIRED\n' ;;
      esac ;;
    *) printf 'next_action=RECONCILE_EVIDENCE_AND_REMOTE_OUTCOME\n' ;;
  esac
  case "${terminal_state}" in
    ABSENT) printf 'terminal=false\n' ;;
    INVALID_EVIDENCE) printf 'terminal=INVALID_EVIDENCE\n' ;;
    *) printf 'terminal=true\n' ;;
  esac
}

remote_reconciliation_python() {
  remote_operation_bindings_python || return 75
  cat <<'PY'
try:
    target, run, release, source_sha, kind, name, intent_sha = sys.argv[1:]
    source = sys.stdin.buffer.read(2 * 1024**2 + 1)
    if len(source) > 2 * 1024**2 or hashlib.sha256(source).hexdigest() != source_sha:
        raise BindingError('reconciliation_source_binding')
    checker = binding_embedded_source(source, 'remote_reconciliation_poststate_python')
    namespace = {'__name__': 'v126_reconcile_observer'}
    exec(compile(checker, '<source-bound-read-only-observer>', 'exec'), namespace)
    owner = dict(run_id=run, release_sha=release, script_sha256=source_sha)
    record = binding_reconcile(target, owner, kind, name, intent_sha, source_sha,
        hashlib.sha256(checker).hexdigest(), binding_action_sequence(kind, name),
        lambda identity, request, operations: namespace['collect'](Path(target), identity, source, request, operations))
    sys.stdout.buffer.write(binding_canonical(binding_export_reconciliation(Path(target) / '.v126-target-operations', record)))
except (BindingError, OSError, ValueError, KeyError, TypeError):
    print('RECONCILIATION=UNKNOWN retry_allowed=false next_action=VERIFY_MISSING_EVIDENCE_OR_EXTERNAL_DAEMON_FENCE', file=sys.stderr)
    raise SystemExit(75)
PY
}

reconcile_command() {
  local state_dir='' stage='' recovery='' authorization='' kind=STAGE name=''
  shift
  while (( $# > 0 )); do
    case "$1" in
      --state-dir) state_dir="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --stage) stage="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --recovery) recovery="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --authorization) authorization="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      *) die "unknown reconciliation option: $1" ;;
    esac
  done
  [[ "${authorization}" == AUTHORIZE_V126_EXACT_EFFECT_RECONCILIATION ]] || die 'explicit reconciliation authorization is required'
  if [[ -n "${recovery}" ]]; then
    [[ -z "${stage}" ]] || die 'choose one stage or recovery operation'
    case "${recovery}" in pre-v126 | post-v126-stop | verify-full-dr) ;; *) die 'unknown recovery class' ;; esac
    kind=RECOVERY
    name="${recovery}"
  else
    stage_index "${stage}" >/dev/null || die 'a known stage is required'
    name="${stage}"
  fi
  load_state "${state_dir}"
  acquire_state_lock
  install_state_lock_traps
  local completion predecessor previous_hash gate auth_hash intent classification code remote_command capture status=0
  if [[ "${kind}" == STAGE ]]; then
    require_no_recovery_intent
    [[ ! -e "${STATE_DIR}/run-terminal.json" && ! -L "${STATE_DIR}/run-terminal.json" ]] || die 'terminal run cannot reconcile stage continuation'
    completion="$(receipt_path "${stage}")"
  else
    completion="$(recovery_receipt_path "${recovery}")"
  fi
  [[ ! -e "${completion}" && ! -L "${completion}" && ! -e "${completion}.sha256" && ! -L "${completion}.sha256" ]] || die 'completion already exists; inspect it without repeating an action'
  if [[ "${kind}" == STAGE ]]; then
  predecessor="$(stage_predecessor "${stage}")"
  previous_hash=NONE
  if [[ "${predecessor}" != NONE ]]; then
    previous_hash="$(verify_receipt "${predecessor}")" || die 'reconciliation predecessor chain is invalid'
  fi
  gate="$(stage_gate "${stage}")"
  auth_hash="$(authorization_hash_for_stage "${stage}")" || die 'reconciliation gate authority is invalid'
  classification="$(classify_status_record "$(intent_path "${stage}")" intent "${stage}" "${predecessor}" \
    "${previous_hash}" "${gate}" "${auth_hash}")" || die 'reconciliation intent inspection failed'
  [[ "${classification}" == RECONCILIATION_REQUIRED ]] || die 'original dispatched intent is absent or invalid'
  intent="$(hash_file "$(intent_path "${stage}")")"
  else
    intent="$(verify_reconciliation_recovery_intent "${recovery}")" || die 'original recovery intent or predecessor is invalid'
  fi
  code="$(remote_reconciliation_python)" || die 'source-bound reconciliation checker is unavailable'
  printf -v remote_command '%q ' python3 -c "${code}" "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" \
    "${SCRIPT_SHA256}" "${kind}" "${name}" "${intent}"
  capture="${STATE_DIR}/tmp/reconciliation-${name}-$(date -u +%Y%m%dT%H%M%SZ)-$$.capture.json"
  (set -C; : > "${capture}") || die 'reconciliation attempt capture already exists'
  chmod 0600 "${capture}"
  run_tracked_command_with_input reconciliation-ssh "${SCRIPT_PATH}" \
    ssh "${REMOTE}" "${remote_command}" > "${capture}" || status=$?
  chmod 0400 "${capture}"
  if (( status != 0 )); then
    printf 'RECONCILIATION=UNKNOWN retry_allowed=false evidence=%s\n' "${capture}" >&2
    return "${status}"
  fi
  if [[ "${kind}" == STAGE ]]; then
    write_reconciled_stage_completion "${stage}" "${capture}" || die 'reconciliation could not complete the original stage contract'
    printf 'Stage %s: RECONCILED_EFFECT retry_allowed=false\n' "${stage}"
  else
    write_reconciled_recovery_completion "${recovery}" "${capture}" || die 'reconciliation could not complete the original recovery contract'
    printf 'Recovery %s: RECONCILED_TERMINAL_RECOVERY retry_allowed=false\n' "${recovery}"
  fi
  release_state_lock
  clear_state_lock_traps
}

target_binding_command() {
  local command="$1" target='' state_dir='' handoff='' version=''
  local next_run='' next_release='' next_source='' authorization=''
  local next_kind='' next_request=''
  shift
  while (( $# > 0 )); do
    case "$1" in
      --target) target="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --state-dir) state_dir="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --handoff-file) handoff="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --operational-version) version="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --next-run-id) next_run="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --next-release-sha) next_release="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --next-script-sha256) next_source="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --next-kind) next_kind="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --next-request-file) next_request="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      --authorization) authorization="$(parse_option_value "$1" "${2:-}")"; shift 2 ;;
      *) die "unknown binding option: $1" ;;
    esac
  done
  require_absolute_path target "${target}"
  local mode=inspect receipt_sha=NONE expected_image=NONE terminal_kind=NATIVE_RECEIPT
  if [[ "${command}" == retire-target ]]; then
    [[ "${authorization}" == AUTHORIZE_V126_TARGET_BINDING_RETIREMENT ]] || die 'target retirement authorization is absent'
    load_state "${state_dir}"
    [[ "${target}" == "${STAGING_PATH}" ]] || die 'retirement target differs from the source-bound run'
    require_absolute_path handoff-file "${handoff}"
    local execution_status predecessor_fields predecessor predecessor_sha observed_predecessor attempt_state
    execution_status="$(status_command status --state-dir "${state_dir}")" || die 'run evidence classification is unavailable'
    [[ "${execution_status}" != *INVALID_EVIDENCE* ]] || die 'invalid run evidence blocks retirement'
    attempt_state="$(read_attempt_state)" || die 'read attempt classification is unavailable'
    case "${attempt_state}" in
      NOT_STARTED | NOT_DISPATCHED) ;;
      *) die 'unresolved read attempts block retirement' ;;
    esac
    case "${version}" in
      V126)
        [[ "${execution_status}" == *'canonical_execution=COMPLETE'* ]] || die 'V126 retirement requires complete canonical execution'
        expected_image="${V126_IMAGE_ID}"
        receipt_sha="$(verify_receipt FINAL_PUBLIC_GATES_PASSED)" || die 'complete V126 receipt chain is required for retirement'
        [[ "$(receipt_path FINAL_PUBLIC_GATES_PASSED)" != *.reconciliation.json ]] || terminal_kind=RECONCILED_EFFECT
        ;;
      V125)
        expected_image="${V125_IMAGE_ID}"
        receipt_sha="$(verify_recovery_receipt pre-v126)" || die 'verified V125 recovery is required for retirement'
        [[ "$(recovery_receipt_path pre-v126)" != *.reconciliation.json ]] || terminal_kind=RECONCILED_EFFECT
        [[ "${execution_status}" == *'terminal=true'* ]] || die 'V125 retirement requires the canonical recovery terminal marker'
        predecessor_fields="$(python3 - "$(recovery_receipt_path pre-v126)" <<'PY'
import json, sys
with open(sys.argv[1]) as handle:
    receipt = json.load(handle)
print(receipt['predecessor_stage'] + '\t' + receipt['predecessor_receipt_sha256'])
PY
)" || die 'V125 recovery predecessor is unavailable'
        IFS=$'\t' read -r predecessor predecessor_sha <<< "${predecessor_fields}"
        [[ "${predecessor}" != NONE ]] || die 'V125 retirement requires a verified baseline predecessor'
        observed_predecessor="$(verify_receipt "${predecessor}")" || die 'V125 recovery predecessor chain is invalid'
        [[ "${observed_predecessor}" == "${predecessor_sha}" ]] || die 'V125 recovery predecessor hash mismatch'
        ;;
      *) die 'retirement requires explicit V125 or V126 operational version' ;;
    esac
    [[ "${next_run}" =~ ^[a-z0-9][a-z0-9._-]{5,63}$ ]] || die 'next run identity is invalid'
    require_sha next-release-sha "${next_release}"
    [[ "${next_source}" =~ ^[0-9a-f]{64}$ ]] || die 'next source identity is invalid'
    mode=retire
  elif [[ -n "${state_dir}${handoff}${version}${next_run}${next_release}${next_source}${authorization}${next_kind}${next_request}" ]]; then
    die 'inspect-target accepts only a target path'
  fi
  local code
  local -a next_policy=("${mode}")
  if [[ -n "${next_kind}${next_request}" || "${terminal_kind}" == RECONCILED_EFFECT ]]; then
    next_kind="${next_kind:-CUTOVER}"
    if [[ "${next_kind}" == ORDINARY_DEPLOY ]]; then
      require_absolute_path next-request-file "${next_request}"
    else
      [[ "${next_kind}" == CUTOVER && -z "${next_request}" ]] || die 'invalid next binding policy'
      next_request=NONE
    fi
    next_policy=("${next_kind}" "${next_request}" "${terminal_kind}" "${mode}")
  fi
  code="$(remote_operation_bindings_python)" || die 'target binding verifier source unavailable'
  code+=$'\ntry:\n    binding_entry(sys.argv[-1])\nexcept (BindingError, OSError, ValueError, KeyError, TypeError):\n    print("TARGET_BINDING=RECONCILIATION_REQUIRED retry_allowed=false", file=sys.stderr)\n    raise SystemExit(75)\n'
  cutover_bounded_command 30 python3 -c "${code}" "${target}" "${RUN_ID}" "${RELEASE_SHA}" \
    "${SCRIPT_SHA256}" "${receipt_sha}" "${handoff}" "${next_run}" "${next_release}" "${next_source}" "${version}" "${expected_image}" "${next_policy[@]}" ||
    die 'binding outcome is unavailable or refused; do not retry a retirement without reconciliation'
}

main() {
  local command="${1:-}"
  case "${command}" in
    init) create_state "$@" ;;
    authorize) authorize_command "$@" ;;
    stage) stage_command "$@" ;;
    status) status_command "$@" ;;
    recover) recovery_command "$@" ;;
    reconcile) reconcile_command "$@" ;;
    inspect-target | retire-target) target_binding_command "$@" ;;
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
  sudo test -f "${target}" || die 'root-owned file is absent or inaccessible'
  sudo test ! -L "${target}" || die 'root-owned file symlink rejected'
  local metadata
  metadata="$(sudo stat -c '%a:%U:%G' "${target}")" || die 'root-owned file metadata query failed'
  [[ "${metadata}" == "${expected_mode}:root:root" ]] ||
    die "root-owned file mode or ownership mismatch: ${target}"
}

remote_sudo_read_sha256_checksum() {
  local checksum="$1"
  remote_sudo_require_root_file "${checksum}" 600 || die 'checksum metadata verification failed'
  local value lines
  value="$(sudo cat "${checksum}")" || die 'checksum read failed'
  [[ "${value}" =~ ^[0-9a-f]{64}$ ]] || die "invalid root-owned SHA-256 checksum: ${checksum}"
  lines="$(sudo wc -l "${checksum}" | awk '{print $1}')" || die 'checksum line count query failed'
  [[ "${lines}" == 1 ]] || die "checksum must contain one line: ${checksum}"
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
  cutover_bounded_command 600 env -i \
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
      if ! output="$(remote_compose ps --status running -q --no-trunc "${service}" 2>/dev/null | tr '\000' '?')"; then
        die "Compose running-container inventory failed: ${service}"
      fi
      ;;
    all)
      if ! output="$(remote_compose ps -aq --no-trunc "${service}" 2>/dev/null | tr '\000' '?')"; then
        die "Compose all-container inventory failed: ${service}"
      fi
      ;;
    *) die 'invalid Compose inventory scope' ;;
  esac
  REMOTE_CAPTURED_CONTAINER_IDS=()
  [[ -n "${output}" ]] || return 0
  local container_id
  while IFS= read -r container_id; do
    [[ "${container_id}" =~ ^[0-9a-f]{64}$ ]] ||
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
  # Keep NUL invalid before Bash capture; pipefail retains the producer's exit status.
  if ! output="$(docker ps -q --no-trunc "$@" 2>/dev/null | tr '\000' '?')"; then
    die 'Docker running-container inventory failed'
  fi
  REMOTE_CAPTURED_CONTAINER_IDS=()
  [[ -n "${output}" ]] || return 0
  local container_id
  while IFS= read -r container_id; do
    [[ "${container_id}" =~ ^[0-9a-f]{64}$ ]] ||
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
      if ! observed_image_id="$(docker inspect --format '{{.Image}}' "${container_id}" 2>/dev/null | tr '\000' '?')"; then
        die 'Docker image inventory became unobservable'
      fi
      remote_require_image_id "${observed_image_id}"
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
  if [[ -n "${11:-}" ]]; then
    # Only the read-only reconciliation caller supplies a completed recovery
    # artifact, already bound to the original successful operation's exact log.
    local recovery_proof="${run_root}/recovery-pre-v126.proof" recovered_env
    [[ "${11}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid completed recovery proof binding'
    remote_verify_proof "${recovery_proof}" || die 'completed recovery proof unavailable'
    [[ "$(remote_hash_file "${recovery_proof}")" == "${11}" ]] || die 'completed recovery proof identity changed'
    recovered_env="$(python3 - "${recovery_proof}" "${run_id}" "${release_sha}" <<'PY'
import re, sys
values = {}
for row in open(sys.argv[1]):
    key, value = row.rstrip('\n').split('=', 1)
    if key in values: raise SystemExit('duplicate recovery proof key')
    values[key] = value
for key, value in dict(run_id=sys.argv[2], release_sha=sys.argv[3],
        flyway='125:0:0:0', maintenance='OFF', traffic_policy='PRODUCT', allowed_lists='EMPTY',
        result='PRE_V126_ROLLBACK_COMPLETE', start_command_count='1', restart_policy='no', restart_count='0').items():
    if values.get(key) != value: raise SystemExit('completed recovery contract mismatch')
for key in ('env_after_sha256', 'backend_container_id'):
    if not re.fullmatch('[0-9a-f]{64}', values.get(key, '')): raise SystemExit('recovery resource identity missing')
print(values['env_after_sha256'])
PY
)" || die 'completed recovery environment proof invalid'
    [[ "$(remote_hash_file "${environment_path}")" == "${recovered_env}" ]] || die 'completed recovery environment drift'
    REMOTE_BOUND_ENV_SHA256="${recovered_env}"
  elif [[ "${V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256:-}" =~ ^[0-9a-f]{64}$ ]]; then
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
  REMOTE_DATABASE_URL_PATH="${database_url_path}"
  REMOTE_DATABASE_URL_SHA256="${database_url_sha}"
}

remote_assert_public_drain() {
  remote_assert_caddy_drain_marker
  local body
  body="$(mktemp "${TMPDIR:-/tmp}/v126-public-drain.XXXXXX")"
  chmod 0600 "${body}"
  local status=0
  if status="$(curl --disable --connect-timeout 3 --max-time 10 -sS -o "${body}" -w '%{http_code}' https://staging.hookahtootah.club/health 2>/dev/null)"; then
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
  if ! curl --disable --connect-timeout 3 --max-time 10 -fsS "${url}" > "${target}" 2>/dev/null; then
    rm -f -- "${target}"
    die "health request failed: ${url}"
  fi
  if ! python3 - "${target}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
if payload != {"status": "ok"}:
    raise SystemExit("health JSON mismatch")
PY
  then
    rm -f -- "${target}"
    die 'health JSON mismatch'
  fi
  rm -f -- "${target}"
}

remote_assert_version() {
  local expected="$1"
  local target
  target="$(mktemp "${TMPDIR:-/tmp}/v126-version.XXXXXX")"
  chmod 0600 "${target}"
  if ! curl --disable --connect-timeout 3 --max-time 10 -fsS http://127.0.0.1:8080/version > "${target}" 2>/dev/null; then
    rm -f -- "${target}"
    die 'loopback version request failed'
  fi
  if ! python3 - "${target}" "${expected}" <<'PY'
import json
import sys
with open(sys.argv[1], "rt", encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("service") != "backend" or payload.get("env") != "staging" or payload.get("version") != sys.argv[2]:
    raise SystemExit("backend version identity mismatch")
PY
  then
    rm -f -- "${target}"
    die 'backend version identity mismatch'
  fi
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
  if ! curl --disable --connect-timeout 3 --max-time 10 --config "${config}" --output "${response}" 2> "${error_file}"; then
    die 'Telegram getWebhookInfo failed; restricted response was discarded'
  fi
  if ! python3 - "${response}" <<'PY'
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
  then
    die 'Telegram webhook or pending update gate failed'
  fi
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
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
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
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
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
      ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
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
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1' >/dev/null <<'SQL'
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
  remote_assert_single_v126_backend_poller "${expected_image_id}" || die 'runtime prerequisite consumer failed'
  remote_capture_compose_ids running backend || die 'runtime prerequisite consumer failed'
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'runtime environment proof requires one running Compose backend'
  local environment_phase
  case "${expected_mode}" in
    V126_SMOKE) environment_phase=first ;;
    OFF) environment_phase=final ;;
    *) die 'runtime environment proof has an invalid maintenance mode' ;;
  esac
  remote_assert_bound_container_environment "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" \
    "${environment_phase}" || die 'runtime prerequisite consumer failed'
  remote_capture_compose_ids running postgres || die 'runtime prerequisite consumer failed'
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'running PostgreSQL count is not one'
  remote_assert_health_json http://127.0.0.1:8080/health || die 'runtime prerequisite consumer failed'
  remote_assert_health_json http://127.0.0.1:8080/db/health || die 'runtime prerequisite consumer failed'
  curl --disable --connect-timeout 3 --max-time 10 -fsSI http://127.0.0.1:8080/miniapp/ >/dev/null 2>&1 || die 'loopback Mini App check failed'
  remote_assert_version "${expected_release}" || die 'runtime prerequisite consumer failed'
  if [[ "${expected_mode}" == V126_SMOKE ]]; then
    STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true \
      "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  else
    "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null
  fi
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file .env --compose-file docker-compose.yml >/dev/null || die 'runtime prerequisite consumer failed'
  remote_assert_schema_v126 || die 'runtime prerequisite consumer failed'
  local queue_gate
  queue_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')),
  ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING'))
);
SQL
)"
  [[ "${queue_gate}" == '0:0' ]] || die 'runtime actionable queue gate is not zero'
  remote_assert_telegram_idle .env || die 'runtime prerequisite consumer failed'
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
  REMOTE_DATABASE_URL_PATH="${database_url_file}"
  REMOTE_DATABASE_URL_SHA256="${database_url_sha}"
  remote_assert_database_target || die 'baseline database equality failed'
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
  curl --disable --connect-timeout 3 --max-time 10 -fsSI http://127.0.0.1:8080/miniapp/ >/dev/null 2>&1 || die 'baseline Mini App check failed'
  remote_assert_version "${V125_SOURCE_SHA}"
  local flyway_gate
  flyway_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(MAX(version::integer), ':', COUNT(*) FILTER (WHERE version = '126'), ':', COUNT(*) FILTER (WHERE NOT success))
FROM flyway_schema_history;
SQL
)"
  [[ "${flyway_gate}" == '125:0:0' ]] || die 'baseline Flyway state mismatch'
  local queue_gate
  queue_gate="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
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
  remote_emit_artifact database-target-identity "${REMOTE_DATABASE_TARGET_IDENTITY_SHA256}"
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

  remote_assert_database_target || die 'backup database equality failed'
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
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SHOW server_version_num"')"
  source_db_size="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT pg_database_size(current_database())"')"
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
      ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; pg_dumpall -U "$POSTGRES_USER" -l "$POSTGRES_DB" --globals-only --no-role-passwords' \
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
    "${rehearsal_volume}" >/dev/null || {
    local command_status=$?
    printf '%s (exit=%s)\n' 'rehearsal volume creation outcome requires reconciliation' "${command_status}" >&2
    exit "${command_status}"
  }
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
    "${source_image_id}" >/dev/null || {
    local command_status=$?
    printf '%s (exit=%s)\n' 'rehearsal container creation outcome requires reconciliation' "${command_status}" >&2
    exit "${command_status}"
  }
  local created_container_owner rehearsal_container_id
  rehearsal_container_id="$(docker container inspect --format '{{.Id}}' "${rehearsal_container}")" ||
    die 'rehearsal container identity unavailable'
  [[ "${rehearsal_container_id}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid rehearsal container identity'
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
  docker cp "${dump_file}" "${rehearsal_container}:/tmp/v126-rehearsal.dump" || {
    local command_status=$?
    printf '%s (exit=%s)\n' 'rehearsal archive copy failed' "${command_status}" >&2
    exit "${command_status}"
  }
  docker exec "${rehearsal_container}" \
    createdb -U "${source_db_user}" --maintenance-db=postgres --template=template0 v126_restore_rehearsal || {
    local command_status=$?
    printf '%s (exit=%s)\n' 'rehearsal database creation failed' "${command_status}" >&2
    exit "${command_status}"
  }
  docker exec "${rehearsal_container}" \
    pg_restore -U "${source_db_user}" --exit-on-error --no-owner --no-privileges \
    --dbname v126_restore_rehearsal /tmp/v126-rehearsal.dump || {
    local command_status=$?
    printf '%s (exit=%s)\n' 'rehearsal restore failed' "${command_status}" >&2
    exit "${command_status}"
  }
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
    "rehearsal_container=${rehearsal_container_id}" "rehearsal_volume=${rehearsal_volume}" \
    "rehearsal_owner=${rehearsal_owner}" 'rehearsal_cleanup=COMPLETE' \
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
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || die 'Caddy file metadata verification failed'
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
  cutover_bounded_command 15 sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null || die 'Caddy validation failed'
  cutover_bounded_command 15 sudo caddy validate --config "${candidate}" --adapter caddyfile >/dev/null || die 'Caddy validation failed'
  local original_sha
  local candidate_sha
  local diff_sha
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  candidate_sha="$(sudo sha256sum "${candidate}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  diff_sha="$(sudo sha256sum "${diff_file}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  [[ "${original_sha}" == "${baseline_caddy_sha}" ]] ||
    die 'sealed Caddy original differs from the immutable baseline receipt'
  remote_sudo_require_root_file "${original}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file "${candidate}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file "${diff_file}" 600 || die 'Caddy file metadata verification failed'
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
  cutover_bounded_command 15 sudo install -o root -g root -m 0644 "${candidate}" /etc/caddy/Caddyfile || die 'Caddy install failed; active configuration requires reconciliation'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${candidate_sha}" ]] ||
    die 'installed Caddy candidate hash mismatch'
  cutover_bounded_command 20 sudo systemctl reload caddy || die 'Caddy reload failed; active configuration requires reconciliation'
  remote_assert_caddy_service_active || die 'Caddy active service proof failed'
  sudo test ! -e /etc/caddy/v126-drain.enabled
  remote_assert_caddy_config_active "${candidate}" || die 'candidate Caddy runtime is not established'
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
  if ! curl --disable --connect-timeout 3 --max-time 10 -fsS http://127.0.0.1:2019/config/ > "${admin_config}" 2>/dev/null; then
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
  remote_sudo_require_root_file "${activation_proof}" 600 || die 'Caddy file metadata verification failed'
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
  remote_assert_database_target || die 'database equality failed before operation'
  remote_assert_compose_backend_image "${backend_image}"
}

remote_initialize_reconciled_recovery() {
  local staging_path="$1" run_id="$2" release_sha="$3" backend_image="$4" proof_sha="$5"
  remote_require_absolute_path staging-path "${staging_path}"
  remote_require_run_id "${run_id}"
  remote_require_sha "${release_sha}"
  [[ "${backend_image}" =~ :${V125_SOURCE_SHA}$ ]] || die 'reconciled recovery image source differs'
  cd "${staging_path}"
  remote_require_operator_file .env 600
  remote_verify_baseline_authority "${staging_path}" "${run_id}" "${release_sha}" '' '' '' '' '' '' '' "${proof_sha}"
  REMOTE_BACKEND_IMAGE="${backend_image}"
  remote_assert_database_target || die 'reconciled recovery database equality failed'
  remote_assert_compose_backend_image "${backend_image}" || die 'reconciled recovery Compose image differs'
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
  remote_assert_caddy_config_active "${evidence_root}/Caddyfile.drain" || die 'candidate Caddy runtime is not established'
}

remote_assert_public_live() {
  local target
  target="$(mktemp "${TMPDIR:-/tmp}/v126-public-live.XXXXXX")"
  chmod 0600 "${target}"
  local endpoint
  for endpoint in health db/health; do
    if ! curl --disable --connect-timeout 3 --max-time 10 -fsS "https://staging.hookahtootah.club/${endpoint}" > "${target}" 2>/dev/null; then
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
    local parse_status=$?
    [[ "${parse_status}" == 0 ]] || { rm -f -- "${target}"; die 'public health response is invalid'; }
  done
  rm -f -- "${target}"
  curl --disable --connect-timeout 3 --max-time 10 -fsSI https://staging.hookahtootah.club/miniapp/ >/dev/null 2>&1 ||
    die 'public Mini App endpoint is unavailable'
}

remote_assert_protected_unauthenticated_503() {
  local response
  response="$(mktemp "${TMPDIR:-/tmp}/v126-protected-503.XXXXXX")"
  chmod 0600 "${response}"
  local status=0
  if status="$(curl --disable --connect-timeout 3 --max-time 10 -sS -o "${response}" -w '%{http_code}' \
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

remote_database_evidence_python() {
  cat <<'PY'
#!/usr/bin/env python3
"""Privacy-safe V126 database evidence decisions; no database writes or credentials.

The sequencer embeds this exact source for its remote consumers. The regression suite
checks byte equality, so source binding covers the helper as well as its caller.
"""
import hashlib
import json
import os
import subprocess
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit


IDENTITY_SQL = """BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '2s';
SELECT json_build_object(
 'version',1,
 'system_identifier',(SELECT system_identifier::text FROM pg_control_system()),
 'postmaster_epoch',extract(epoch FROM pg_postmaster_start_time())::text,
 'database',current_database(),
 'database_oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),
 'schema',current_schema(),
 'schema_oid',(SELECT oid::text FROM pg_namespace WHERE nspname=current_schema()),
 'search_path',current_schemas(false),
 'current_role',current_user,
 'session_role',session_user,
 'role_oid',(SELECT oid::text FROM pg_roles WHERE rolname=current_user),
 'read_only',current_setting('transaction_read_only'))::text;
ROLLBACK;
"""


def fail(message):
    raise ValueError(message)


def strict_json(data):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail('structured database evidence contains a duplicate field')
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=object_pairs)


def preflight_outcome(data):
    prefix = b'V126_PREFLIGHT_RESULT='
    lines = data.splitlines()
    results = [line[len(prefix):] for line in lines if line.startswith(prefix)]
    if len(results) != 1:
        fail('preflight requires exactly one structured outcome')
    try:
        outcome = strict_json(results[0])
    except (ValueError, UnicodeError):
        fail('preflight structured outcome is invalid')
    if (not isinstance(outcome, dict) or set(outcome) != {'version', 'safe', 'unsafe_count'}
            or type(outcome['version']) is not int or outcome['version'] != 1
            or outcome['safe'] is not True or type(outcome['unsafe_count']) is not int
            or outcome['unsafe_count'] != 0):
        fail('preflight did not prove safe with zero unsafe rows')
    if lines.count(b'BOOKING_THREAD_PREFLIGHT_SAFE_TO_CONTINUE') != 1:
        fail('preflight positive completion marker is missing or duplicated')
    if any(b'STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION' in line for line in lines):
        fail('preflight contains an unsafe decision')
    return outcome


def canonical_toc(data):
    # This is the sole volatile presentation field: pg_restore formats the archive
    # creation time using the consumer's timezone. Every other byte remains bound.
    if b'\x00' in data or not data.endswith(b'\n'):
        fail('TOC inventory is not complete text')
    lines = data.splitlines(keepends=True)
    candidates = [i for i, line in enumerate(lines) if line.startswith(b'; Archive created at ')]
    if len(candidates) != 1 or candidates[0] != 1 or lines[0] != b';\n':
        fail('TOC archive creation header is missing or misplaced')
    if not re.fullmatch(rb'; Archive created at \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?: [A-Za-z0-9_+:/.-]+)?\n', lines[1]):
        fail('TOC archive creation header is malformed')
    if lines.count(b'; Selected TOC Entries:\n') != 1:
        fail('TOC selected-entry header is missing or duplicated')
    selected = lines.index(b'; Selected TOC Entries:\n')
    headers = b''.join(lines[2:selected])
    for name in [b'dbname', b'TOC Entries', b'Compression', b'Dump Version', b'Format',
                 b'Integer', b'Offset', b'Dumped from database version', b'Dumped by pg_dump version']:
        if len(re.findall(rb'^; +'+re.escape(name)+rb': [^\n]+\n', headers, re.M)) != 1:
            fail('TOC semantic header is missing or duplicated')
    entries = [line for line in lines[selected + 1:] if line != b';\n']
    if not entries or any(not re.match(rb'[0-9]+; [0-9]+ [0-9]+ ', line) for line in entries):
        fail('TOC entries are empty or malformed')
    ids = [line.split(b';', 1)[0] for line in entries]
    if len(set(ids)) != len(ids):
        fail('TOC entry IDs are duplicated')
    lines[1] = b'; Archive creation display omitted; exact dump SHA-256 verified separately\n'
    return b''.join(lines)


def compare_toc(dump, expected_dump_sha, retained, generated):
    if not re.fullmatch('[0-9a-f]{64}', expected_dump_sha):
        fail('dump hash binding is malformed')
    if hashlib.sha256(dump).hexdigest() != expected_dump_sha:
        fail('selected DR backup hash mismatch')
    if canonical_toc(retained) != canonical_toc(generated):
        fail('DR inventory does not match the selected archive')


def database_identity(data):
    try:
        value = strict_json(data)
    except (ValueError, UnicodeError):
        fail('database identity is not one JSON object')
    fields = {'version', 'system_identifier', 'postmaster_epoch', 'database', 'database_oid',
              'schema', 'schema_oid', 'search_path', 'current_role', 'session_role', 'role_oid', 'read_only'}
    if not isinstance(value, dict) or set(value) != fields:
        fail('database identity fields do not match the contract')
    if type(value['version']) is not int or value['version'] != 1 or value['read_only'] != 'on':
        fail('database identity query did not use the read-only contract')
    for name in ['system_identifier', 'database_oid', 'schema_oid', 'role_oid']:
        if not isinstance(value[name], str) or not re.fullmatch('[1-9][0-9]*', value[name]):
            fail('database identity has an invalid catalog identity')
    if not isinstance(value['postmaster_epoch'], str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', value['postmaster_epoch']):
        fail('database identity has an invalid server lifetime')
    for name in ['database', 'schema', 'current_role', 'session_role']:
        if not isinstance(value[name], str) or not value[name] or any(ord(ch) < 32 for ch in value[name]):
            fail('database identity has an invalid name')
    if value['current_role'] != value['session_role']:
        fail('database identity unexpectedly changed roles')
    if (not isinstance(value['search_path'], list) or not value['search_path']
            or value['search_path'][0] != value['schema']
            or any(not isinstance(x, str) or not x for x in value['search_path'])
            or len(set(value['search_path'])) != len(value['search_path'])):
        fail('database identity has an invalid effective schema search path')
    return value


def equal_identities(values):
    if len(values) < 2:
        fail('database equality requires independent consumers')
    identities = [database_identity(value) for value in values]
    if any(value != identities[0] for value in identities[1:]):
        fail('database server/database/schema/intended-role equality failed')
    encoded = json.dumps(identities[0], sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(encoded).hexdigest()


def backend_contract(compose, source, backend, resolved, planned_network=None):
    def environment(container):
        values = {}
        for entry in container['Config']['Env']:
            key, separator, value = entry.partition('=')
            if not separator or key in values:
                fail('container environment is malformed or duplicated')
            values[key] = value
        return values

    producer = environment(source)
    if any(key.startswith('PG') and key not in {'PG_MAJOR', 'PG_VERSION', 'PG_SHA256', 'PGDATA'}
           and value for key, value in producer.items()):
        fail('source container has unsupported libpq environment overrides')
    actual = environment(backend)
    effective = compose['services']['backend']['environment']
    database = producer.get('POSTGRES_DB', '')
    role = producer.get('POSTGRES_USER', '')
    password = producer.get('POSTGRES_PASSWORD', '')
    if (not database or any(ch in database for ch in '/?:#@%')
            or not role or not password or any(ord(ch) < 32 for ch in database + role)):
        fail('source target is not an exact supported JDBC target')
    required = {'DB_JDBC_URL': 'jdbc:postgresql://postgres:5432/' + database,
                'DB_USER': role, 'DB_PASSWORD': password}
    if any(effective.get(key) != value or actual.get(key) != value for key, value in required.items()):
        fail('future/actual backend target differs from the source database')
    source_networks = source['NetworkSettings']['Networks']
    backend_networks = backend['NetworkSettings']['Networks']
    running = backend.get('State', {}).get('Running', True)
    if not running:
        if planned_network is None:
            fail('stopped backend requires an exact future network proof')
        name, network_id = planned_network['Name'], planned_network['Id']
        if (backend['HostConfig']['NetworkMode'] not in {name, network_id}
                or name not in source_networks
                or source_networks[name].get('NetworkID') != network_id):
            fail('stopped backend network plan differs from the selected source')
        backend_networks = {name: {'NetworkID': network_id}}
    shared = set(source_networks) & set(backend_networks)
    expected = set()
    for name in shared:
        left, right = source_networks[name], backend_networks[name]
        if left.get('NetworkID') != right.get('NetworkID') or not left.get('NetworkID'):
            fail('backend/source network identity differs')
        if 'postgres' in (left.get('Aliases') or []):
            address = left.get('IPAddress', '')
            if not re.fullmatch(r'(?:[0-9]{1,3}\.){3}[0-9]{1,3}', address):
                fail('source network endpoint is unavailable')
            expected.add(address)
    if not expected or set(resolved) != expected:
        fail('backend postgres DNS does not resolve only to the selected source endpoint')
    return True


LIBPQ_IDENTITY_WORKER = r"""
import ctypes
import ctypes.util
import json
import re
import shutil
import subprocess
import sys

connection = None
try:
    payload = json.load(sys.stdin)
    library = ctypes.util.find_library('pq')
    if not library and sys.platform == 'darwin':
        linked = subprocess.run(['otool', '-L', shutil.which('psql')], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=3, check=True).stdout
        match = re.search(rb'\n\s+(/[^\n]+/libpq[^\s]+\.dylib) ', linked)
        library = match.group(1).decode() if match else None
    if not library:
        raise ValueError('libpq unavailable')
    pq = ctypes.CDLL(library)
    pointer = ctypes.c_void_p
    pq.PQconnectdbParams.argtypes = [ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_char_p), ctypes.c_int]
    pq.PQconnectdbParams.restype = pointer
    pq.PQstatus.argtypes = [pointer]
    pq.PQstatus.restype = ctypes.c_int
    pq.PQexec.argtypes = [pointer, ctypes.c_char_p]
    pq.PQexec.restype = pointer
    pq.PQresultStatus.argtypes = [pointer]
    pq.PQresultStatus.restype = ctypes.c_int
    pq.PQntuples.argtypes = [pointer]
    pq.PQntuples.restype = ctypes.c_int
    pq.PQnfields.argtypes = [pointer]
    pq.PQnfields.restype = ctypes.c_int
    pq.PQgetvalue.argtypes = [pointer, ctypes.c_int, ctypes.c_int]
    pq.PQgetvalue.restype = ctypes.c_char_p
    pq.PQclear.argtypes = [pointer]
    pq.PQfinish.argtypes = [pointer]
    keys = (ctypes.c_char_p * 4)(b'dbname', b'connect_timeout', b'client_encoding', None)
    values = (ctypes.c_char_p * 4)(payload['uri'].encode(), b'5', b'UTF8', None)
    connection = pq.PQconnectdbParams(keys, values, 1)
    if not connection or pq.PQstatus(connection) != 0:
        raise ValueError('connection failed')
    found = []
    for statement in payload['sql'].split(';'):
        if not statement.strip():
            continue
        result = pq.PQexec(connection, statement.encode())
        if not result:
            raise ValueError('query failed')
        try:
            status = pq.PQresultStatus(result)
            if status == 2:
                if pq.PQntuples(result) != 1 or pq.PQnfields(result) != 1:
                    raise ValueError('query result shape failed')
                found.append(pq.PQgetvalue(result, 0, 0).decode())
            elif status != 1:
                raise ValueError('query status failed')
        finally:
            pq.PQclear(result)
    if len(found) != 1:
        raise ValueError('query result count failed')
    print(found[0])
except BaseException:
    raise SystemExit('read-only libpq target identity failed') from None
finally:
    if connection:
        pq.PQfinish(connection)
"""


def assert_compose_source_labels(source_labels, backend_labels):
    canonical_cwd = str(Path.cwd().resolve(strict=True))
    required = {
        'com.docker.compose.project.working_dir': canonical_cwd,
        'com.docker.compose.project.config_files': str(Path(canonical_cwd) / 'docker-compose.yml'),
    }
    for labels in (source_labels, backend_labels):
        if not isinstance(labels, dict) or any(labels.get(key) != value for key, value in required.items()):
            fail('database containers belong to another Compose working directory or config source')


def assert_live_target(uri_path, backend_image, expected_uri_sha):
    # All credential-bearing configuration and inspect output stays in this process.
    raw_uri = Path(uri_path).read_bytes()
    if (not re.fullmatch('[0-9a-f]{64}', expected_uri_sha)
            or hashlib.sha256(raw_uri).hexdigest() != expected_uri_sha):
        fail('database URI bytes differ from immutable authority')
    clean = {key: os.environ[key] for key in ('PATH', 'HOME')}
    compose_env = dict(clean, BACKEND_IMAGE=backend_image)
    compose = ['docker', 'compose', '--env-file', '.env', '--file', 'docker-compose.yml']

    def command(argv, *, payload=None, environment=None):
        result = subprocess.run(argv, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=environment or clean, timeout=12, check=False)
        if result.returncode:
            fail('database target consumer failed')
        return result.stdout

    def ids(service, running):
        arguments = ['ps', '--status', 'running', '-q', '--no-trunc', service] if running else ['ps', '-aq', '--no-trunc', service]
        values = command(compose + arguments, environment=compose_env).decode().splitlines()
        if len(values) != 1 or not re.fullmatch('[0-9a-f]{64}', values[0]):
            fail('database target requires one canonical source/backend container')
        return values[0]

    def inspected(cid):
        values = json.loads(command(['docker', 'inspect', cid]))
        if not isinstance(values, list) or len(values) != 1 or values[0].get('Id') != cid:
            fail('database target container identity changed')
        return values[0]

    source_id, backend_id = ids('postgres', True), ids('backend', False)
    source, backend = inspected(source_id), inspected(backend_id)
    config = json.loads(command(compose + ['config', '--format', 'json'], environment=compose_env))
    source_labels, backend_labels = source['Config']['Labels'], backend['Config']['Labels']
    assert_compose_source_labels(source_labels, backend_labels)
    if (source_labels.get('com.docker.compose.service') != 'postgres'
            or backend_labels.get('com.docker.compose.service') != 'backend'
            or not source_labels.get('com.docker.compose.project')
            or source_labels.get('com.docker.compose.project') != backend_labels.get('com.docker.compose.project')):
        fail('database target project/service identity differs')
    # The tracked Compose puts both services on the same single default network.
    def networks(service):
        declared = config['services'][service].get('networks', {'default': None})
        if not isinstance(declared, dict) or len(declared) != 1:
            fail('database target has unsupported Compose networks')
        return set(declared)
    logical = networks('postgres')
    if networks('backend') != logical:
        fail('future backend/source Compose networks differ')
    name = config['networks'][next(iter(logical))]['name']
    if name not in source['NetworkSettings']['Networks']:
        fail('source does not use the future Compose network')
    networks_found = json.loads(command(['docker', 'network', 'inspect', name]))
    if not isinstance(networks_found, list) or len(networks_found) != 1:
        fail('future database network is not unique')
    planned_network = networks_found[0]
    project = source_labels['com.docker.compose.project']
    if (planned_network.get('Name') != name
            or planned_network.get('Id') != source['NetworkSettings']['Networks'][name]['NetworkID']
            or planned_network.get('Labels', {}).get('com.docker.compose.project') != project
            or not set(planned_network.get('Containers', {})).issubset({source_id, backend_id})
            or source_id not in planned_network.get('Containers', {})):
        fail('future database network identity or endpoint inventory differs')
    # A stopped backend cannot run a DNS consumer. Its exact retained network and
    # future Compose plan must agree; recheck from the created/running backend later.
    resolver = backend_id if backend['State']['Running'] else source_id
    dns = command(['docker', 'exec', resolver, 'getent', 'ahostsv4', 'postgres']).decode().splitlines()
    addresses = []
    for line in dns:
        parts = line.split()
        if len(parts) not in (2, 3) or not re.fullmatch(r'(?:[0-9]{1,3}\.){3}[0-9]{1,3}', parts[0]):
            fail('database DNS consumer returned malformed output')
        addresses.append(parts[0])
    backend_contract(config, source, backend, addresses, planned_network)
    native = command(['docker', 'exec', '-i', source_id, 'sh', '-c',
                      ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; '
                      'env -i PATH="$PATH" PGCONNECT_TIMEOUT=5 psql -XqAtw -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1'],
                     payload=IDENTITY_SQL.encode())
    raw = raw_uri.removesuffix(b'\n')
    uri = raw.decode('utf-8')
    if (not uri.startswith(('postgresql://', 'postgres://')) or not uri
            or any(ord(ch) < 32 or ord(ch) == 127 for ch in uri)):
        fail('database target URI is malformed')
    parsed = urlsplit(uri)
    if (not parsed.hostname or not parsed.username or not parsed.password or parsed.fragment
            or not parsed.path.startswith('/') or parsed.path.count('/') != 1 or len(parsed.path) < 2
            or ',' in parsed.netloc):
        fail('database target URI lacks explicit connection authority')
    host = command(['python3', '-c', LIBPQ_IDENTITY_WORKER],
                   payload=json.dumps({'uri': uri, 'sql': IDENTITY_SQL}).encode())
    digest = equal_identities([native, host])
    # Inspect again to reject container replacement/config drift during the reads.
    def stable(container):
        return {key: container[key] for key in ('Id', 'Config', 'RestartCount', 'HostConfig')} | {
            'networks': container['NetworkSettings']['Networks'],
            'started_at': container['State']['StartedAt'], 'running': container['State']['Running']}
    if (json.loads(command(['docker', 'network', 'inspect', name])) != [planned_network]
            or stable(inspected(source_id)) != stable(source) or stable(inspected(backend_id)) != stable(backend)
            or ids('postgres', True) != source_id or ids('backend', False) != backend_id):
        fail('database target container changed during verification')
    print('DATABASE_TARGET_EQUALITY=' + digest)


def main(argv):
    if argv == ['identity-sql']:
        print(IDENTITY_SQL, end='')
    elif len(argv) == 4 and argv[0] == 'live-target':
        assert_live_target(argv[1], argv[2], argv[3])
    elif len(argv) == 2 and argv[0] == 'preflight':
        preflight_outcome(Path(argv[1]).read_bytes())
    elif len(argv) == 5 and argv[0] == 'toc':
        if not re.fullmatch('[0-9a-f]{64}', argv[2]):
            fail('dump hash binding is malformed')
        digest = hashlib.sha256()
        with Path(argv[1]).open('rb') as dump:
            for block in iter(lambda: dump.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != argv[2]:
            fail('selected DR backup hash mismatch')
        if canonical_toc(Path(argv[3]).read_bytes()) != canonical_toc(Path(argv[4]).read_bytes()):
            fail('DR inventory does not match the selected archive')
    elif len(argv) >= 3 and argv[0] == 'identity':
        print(equal_identities([Path(path).read_bytes() for path in argv[1:]]))
    else:
        fail('invalid database evidence operation')


if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, UnicodeError, KeyError, TypeError, subprocess.SubprocessError):
        raise SystemExit('database evidence refused; no credentials or target details are logged') from None
PY
}

remote_assert_database_target() {
  local database_path="${1:-${REMOTE_DATABASE_URL_PATH:-}}"
  local database_sha="${2:-${REMOTE_DATABASE_URL_SHA256:-}}"
  [[ -n "${database_path}" && "${database_sha}" =~ ^[0-9a-f]{64}$ ]] ||
    die 'actual database target lacks immutable URI binding'
  [[ -n "${REMOTE_BACKEND_IMAGE:-}" ]] || die 'database equality lacks the bound backend image'
  local code
  code="$(remote_database_evidence_python)" || die 'database evidence source unavailable'
  local result
  result="$(cutover_bounded_command 180 python3 -c "${code}" live-target "${database_path}" \
    "${REMOTE_BACKEND_IMAGE}" "${database_sha}")" ||
    die 'actual server/database/schema/intended-role target equality failed'
  [[ "${result}" =~ ^DATABASE_TARGET_EQUALITY=([0-9a-f]{64})$ ]] || die 'database target result is malformed'
  REMOTE_DATABASE_TARGET_IDENTITY_SHA256="${BASH_REMATCH[1]}"
  local expected="${V126_INTERNAL_REMOTE_DATABASE_TARGET_IDENTITY_SHA256:-}"
  if [[ "${expected}" != NONE ]]; then
    [[ "${expected}" =~ ^[0-9a-f]{64}$ && "${expected}" == "${REMOTE_DATABASE_TARGET_IDENTITY_SHA256}" ]] ||
      die 'actual database identity changed since the immutable baseline'
  fi
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
  remote_assert_database_target "${database_url_file}" "${expected_database_sha}" ||
    die 'preflight semantic database equality failed'
  python3 - "${database_url_file}" "${expected_database_sha}" "${service_file}" "${pass_file}" <<'PY'
# HT12X_LIBPQ_DERIVATION_BEGIN
from hashlib import sha256
from pathlib import Path
from urllib.parse import unquote, urlsplit
import os
import re
import sys


def uri_decode(item):
    if re.search(r"%(?![0-9A-Fa-f]{2})", item):
        raise SystemExit("database URL binding contains invalid percent encoding")
    return unquote(item, encoding="utf-8", errors="strict")


def service_line(key, item):
    # libpq's service parser takes literal bytes after '=' and trims line-end
    # whitespace. Conninfo quoting/escaping would become part of the value.
    if not item or any(ord(ch) < 32 or ord(ch) == 127 for ch in item) or item.endswith(" "):
        raise SystemExit("database URL value is not exactly representable in a service file")
    line = f"{key}={item}\n"
    # parseServiceFile uses a 1024-byte buffer and rejects strlen >= 1023.
    if len(line.encode("utf-8")) >= 1023:
        raise SystemExit("database URL value exceeds the service-file line limit")
    return line


def pgpass_escape(item):
    # Escape literal wildcard/comment characters too: these are exact targets.
    return "".join("\\" + ch if ch in "\\:*#" else ch for ch in item)


def derive():
    source_path, expected_sha, service_path, pass_path = sys.argv[1:]
    raw = Path(source_path).read_bytes()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha) or sha256(raw).hexdigest() != expected_sha:
        raise SystemExit("database URL bytes differ from immutable authority at derivation")
    # Permit one terminal LF, never let urlsplit discard URI controls for us.
    value = raw.removesuffix(b"\n").decode("utf-8")
    if not value or any(ord(ch) < 32 or ord(ch) == 127 for ch in value):
        raise SystemExit("database URL binding must contain exactly one nonempty line without controls")
    parsed = urlsplit(value)
    if not value.startswith(("postgres://", "postgresql://")) or "#" in value:
        raise SystemExit("database URL binding is not an exact PostgreSQL URI")
    if not parsed.username or not parsed.path.startswith("/") or parsed.path.count("/") != 1:
        raise SystemExit("database URL binding lacks an exact host, user, or database")
    # Preserve host spelling; SplitResult.hostname silently lowercases it.
    authority = parsed.netloc.rsplit("@", 1)[-1]
    if authority.startswith("["):
        host, separator, suffix = authority[1:].partition("]")
        if not separator or (suffix and not suffix.startswith(":")):
            raise SystemExit("database URL binding host is invalid")
        port = uri_decode(suffix[1:]) if suffix else "5432"
    else:
        host, separator, port = authority.partition(":")
        port = uri_decode(port) if separator else "5432"
    if not re.fullmatch(r"[0-9]+", port) or not 1 <= int(port) <= 65535:
        raise SystemExit("database URL binding port is invalid")
    host = uri_decode(host)
    user = uri_decode(parsed.username)
    password = uri_decode(parsed.password) if parsed.password is not None else None
    database = uri_decode(parsed.path[1:])
    if not database or password is None or not host or "," in host:
        raise SystemExit("database URL binding requires one exact host, database and password")
    allowed_options = {
        "application_name", "channel_binding", "connect_timeout", "gssencmode", "keepalives",
        "keepalives_count", "keepalives_idle", "keepalives_interval", "options", "sslcert",
        "sslcrl", "sslkey", "sslmode", "sslrootcert", "target_session_attrs",
    }
    options = {}
    for pair in parsed.query.split("&") if parsed.query else ():
        key, separator, option_value = pair.partition("=")
        if not separator:
            raise SystemExit("database URL binding contains a malformed option")
        # URI percent decoding, not HTML form decoding: '+' stays '+'.
        key, option_value = uri_decode(key), uri_decode(option_value)
        if key not in allowed_options or key in options:
            raise SystemExit("database URL binding contains an unsupported or duplicate option")
        options[key] = option_value
    for item in (host, user, password, database, *options.values()):
        if not item or any(ord(ch) < 32 or ord(ch) == 127 for ch in item):
            raise SystemExit("database URL binding contains an invalid empty or control value")
    service = {
        "host": host,
        "port": port,
        "dbname": database,
        "user": user,
        "passfile": pass_path,
        **options,
    }
    service_payload = "[v126_preflight]\n" + "".join(service_line(key, service[key]) for key in sorted(service))
    pass_payload = ":".join(pgpass_escape(item) for item in (host, port, database, user, password)) + "\n"
    if service_path == pass_path or any(os.path.lexists(path) for path in (service_path, pass_path)):
        raise SystemExit("preflight database credential artifacts already exist")
    created = []
    try:
        for path, payload in ((pass_path, pass_payload), (service_path, service_payload)):
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            created.append(path)
            with os.fdopen(fd, "wt", encoding="utf-8", newline="") as handle:
                handle.write(payload)
    except BaseException:
        for path in created:
            os.unlink(path)
        raise


try:
    derive()
except (OSError, ValueError, UnicodeError):
    # Parser/IO exception text may contain input or credential-bearing paths.
    raise SystemExit("preflight database credential derivation failed") from None
# HT12X_LIBPQ_DERIVATION_END
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
  python3 -c "$(remote_database_evidence_python)" preflight "${restricted_output}" ||
    die 'final V125 preflight lacks structured safe/count0 outcome; restricted output retained'
  rm -f -- "${service_file}" "${pass_file}" || die 'preflight credential cleanup failed'
  [[ ! -e "${service_file}" && ! -L "${service_file}" && ! -e "${pass_file}" && ! -L "${pass_file}" ]] ||
    die 'preflight credentials remain after cleanup'
  trap - EXIT INT TERM HUP
  chmod 0600 "${restricted_output}"
  local output_sha
  output_sha="$(remote_hash_file "${restricted_output}")"
  rm -f -- "${restricted_output}" || die 'preflight private output cleanup failed'
  local proof="${run_root}/final-v125-preflight.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" \
    "script_sha256=${expected_script_sha}" "output_sha256=${output_sha}" \
    'preflight_outcome=SAFE' 'unsafe_count=0' 'credentials_cleanup=COMPLETE' \
    'database_target=EXPLICIT_REDACTED' 'flyway=125:0:0' 'result=PASS'
  remote_emit_artifact final-v125-preflight "$(remote_hash_file "${proof}")"
}

# Candidate bytes must occupy Compose's fixed env_file during validation.
remote_validate_environment_candidate() (
  local staging_path="$1"
  local candidate="$2"
  local target_mode="$3"
  local validation_dir
  validation_dir="$(mktemp -d "${TMPDIR:-/tmp}/v126-env-validation.XXXXXX")" ||
    die 'cannot create private candidate validation directory'
  local cleanup_command
  printf -v cleanup_command 'rm -rf -- %q' "${validation_dir}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  chmod 0700 "${validation_dir}" || die 'cannot protect candidate validation directory'
  local candidate_sha
  local compose_sha
  candidate_sha="$(remote_hash_file "${candidate}")" || die 'candidate hash failed'
  compose_sha="$(remote_hash_file "${staging_path}/docker-compose.yml")" || die 'Compose hash failed'
  cp "${candidate}" "${validation_dir}/.env" || die 'candidate validation copy failed'
  cp "${staging_path}/docker-compose.yml" "${validation_dir}/docker-compose.yml" ||
    die 'Compose validation copy failed'
  chmod 0600 "${validation_dir}/.env" "${validation_dir}/docker-compose.yml" ||
    die 'cannot protect candidate validation inputs'
  [[ "$(remote_hash_file "${validation_dir}/.env")" == "${candidate_sha}" && \
    "$(remote_hash_file "${validation_dir}/docker-compose.yml")" == "${compose_sha}" ]] ||
    die 'candidate validation snapshot identity mismatch'
  remote_validate_fixed_environment "${staging_path}" "${validation_dir}" "${target_mode}" ||
    die 'candidate fixed-environment validation failed'
  [[ "$(remote_hash_file "${candidate}")" == "${candidate_sha}" && \
    "$(remote_hash_file "${staging_path}/docker-compose.yml")" == "${compose_sha}" && \
    "$(remote_hash_file "${validation_dir}/.env")" == "${candidate_sha}" && \
    "$(remote_hash_file "${validation_dir}/docker-compose.yml")" == "${compose_sha}" ]] ||
    die 'candidate validation inputs changed'
)

remote_validate_fixed_environment() {
  local staging_path="$1"
  local config_dir="$2"
  local target_mode="$3"
  [[ "${target_mode}" == V126_SMOKE || "${target_mode}" == OFF ]] || die 'invalid environment validation mode'
  local authorized=false
  [[ "${target_mode}" != V126_SMOKE ]] || authorized=true
  cutover_bounded_command 30 env -i PATH="${PATH}" HOME="${HOME}" \
    STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED="${authorized}" \
    "${staging_path}/scripts/check-staging-maintenance-config.sh" "${config_dir}/.env" >/dev/null ||
    die 'fixed-environment maintenance validation failed'
  cutover_bounded_command 30 env -i PATH="${PATH}" HOME="${HOME}" \
    "${staging_path}/scripts/validate-staging-admission.sh" --profile public-pilot \
    --env-file "${config_dir}/.env" --compose-file "${config_dir}/docker-compose.yml" >/dev/null ||
    die 'fixed-environment effective Compose admission validation failed'
  [[ "$(remote_env_value "${config_dir}/.env" STAGING_MAINTENANCE_MODE)" == "${target_mode}" ]] ||
    die 'fixed-environment maintenance target mismatch'
}

remote_install_environment_candidate() {
  cutover_bounded_command 30 python3 - "$1" "$2" "$3" <<'PY'
from pathlib import Path
import hashlib
import os
import stat
import sys
candidate, next_path, expected_source_sha = sys.argv[1:]
source_path = Path('.env')
metadata = source_path.lstat()
if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600:
    raise SystemExit('fixed environment metadata is unsafe')
if hashlib.sha256(source_path.read_bytes()).hexdigest() != expected_source_sha:
    raise SystemExit('fixed environment changed before replacement')
if not stat.S_ISREG(os.lstat(candidate).st_mode):
    raise SystemExit('candidate must be a regular non-symlink file')
payload = Path(candidate).read_bytes()
fd = os.open(next_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'wb') as handle:
    os.fchown(handle.fileno(), metadata.st_uid, metadata.st_gid)
    os.fchmod(handle.fileno(), stat.S_IMODE(metadata.st_mode))
    handle.write(payload)
    handle.flush()
    os.fsync(handle.fileno())
current = source_path.lstat()
if (current.st_dev, current.st_ino, current.st_uid, current.st_gid, current.st_mode) != (
    metadata.st_dev, metadata.st_ino, metadata.st_uid, metadata.st_gid, metadata.st_mode
) or hashlib.sha256(source_path.read_bytes()).hexdigest() != expected_source_sha:
    raise SystemExit('fixed environment identity changed before atomic replacement')
os.replace(next_path, source_path)
for directory in {str(Path(next_path).parent), str(source_path.absolute().parent)}:
    directory_fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)
PY
  local status=$?
  [[ "${status}" == 0 ]] || die 'environment install failed; replacement outcome requires reconciliation'
}

# Compare complete parsed configurations; only JSON object ordering is immaterial.
remote_assert_caddy_config_active() (
  local config_path="$1"
  local proof_dir
  proof_dir="$(mktemp -d "${TMPDIR:-/tmp}/v126-caddy-proof.XXXXXX")" || die 'cannot allocate Caddy runtime proof'
  local cleanup_command
  printf -v cleanup_command 'rm -rf -- %q' "${proof_dir}"
  trap "v126_cleanup_exit_status=\$?; trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after EXIT' >&2; fi; exit \"\${v126_cleanup_exit_status}\"" EXIT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after INT' >&2; fi; exit 130" INT
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after TERM' >&2; fi; exit 143" TERM
  trap "trap - EXIT HUP INT TERM; if ! ${cleanup_command}; then printf '%s\\n' 'cleanup failed after HUP' >&2; fi; exit 129" HUP
  chmod 0700 "${proof_dir}" || die 'cannot protect Caddy runtime proof'
  cutover_bounded_command 15 sudo caddy adapt --config "${config_path}" --adapter caddyfile \
    > "${proof_dir}/expected.json" 2>/dev/null || die 'Caddy configuration adaptation failed'
  curl --disable --noproxy '*' --proto '=http' --connect-timeout 3 --max-time 10 -fsS http://127.0.0.1:2019/config/ \
    > "${proof_dir}/active.json" 2>/dev/null || die 'Caddy active configuration is unavailable'
  python3 - "${proof_dir}/expected.json" "${proof_dir}/active.json" <<'PY'
import json
import sys

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate Caddy configuration key')
        result[key] = value
    return result

try:
    with open(sys.argv[1], encoding='utf-8') as handle:
        expected = json.load(handle, object_pairs_hook=unique_object)
    with open(sys.argv[2], encoding='utf-8') as handle:
        active = json.load(handle, object_pairs_hook=unique_object)
    if (not isinstance(expected, dict) or not expected or
        json.dumps(active, sort_keys=True, separators=(',', ':'), allow_nan=False) !=
        json.dumps(expected, sort_keys=True, separators=(',', ':'), allow_nan=False)):
        raise ValueError('Caddy runtime differs from the sealed configuration')
except (OSError, ValueError):
    raise SystemExit('Caddy active configuration equality is not established')
PY
  local status=$?
  [[ "${status}" == 0 ]] || die 'Caddy active configuration equality is not established'
)

remote_assert_caddy_service_active() {
  local state
  state="$(cutover_bounded_command 10 sudo systemctl is-active caddy)" || die 'Caddy service state query failed'
  [[ "${state}" == active ]] || die 'Caddy is not active'
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
  cp --preserve=mode,ownership,timestamps .env "${before}" || die 'environment snapshot failed'
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
  local derive_status=$?
  [[ "${derive_status}" == 0 ]] || die 'environment derivation failed'
  chmod 0600 "${candidate}" || die 'cannot protect environment candidate'
  remote_validate_environment_candidate "${staging_path}" "${candidate}" "${target_mode}" ||
    die 'candidate environment validation failed'
  [[ "$(remote_hash_file .env)" == "${expected_source_sha}" ]] ||
    die 'staging environment changed during maintenance transformation'
  remote_install_environment_candidate "${candidate}" "${next_env}" "${expected_source_sha}" ||
    die 'environment installation failed; reconcile the fixed environment before any further mutation'
  remote_validate_fixed_environment "${staging_path}" "${staging_path}" "${target_mode}" ||
    die 'installed fixed environment validation failed'
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

remote_receive_upload() {
  local action="$1" staging_path="$2" run_id="$3" release_sha="$4"
  local image_tag="$5" expected_sha="$6" expected_size="$7" run_root destination
  (( $# == 7 )) || die 'upload argument contract differs'
  [[ "${expected_sha}" =~ ^[0-9a-f]{64}$ && "${expected_size}" =~ ^[1-9][0-9]{0,10}$ ]] || die 'invalid upload identity'
  remote_initialize_compose "${staging_path}" "${run_id}" "${release_sha}" "${image_tag}"
  run_root="$(remote_require_run_root "${staging_path}" "${run_id}")" || die 'upload namespace is unavailable'
  remote_assert_public_drain || die 'upload drain check failed'
  remote_assert_zero_writer '125:0:0' || die 'upload zero-writer check failed'
  case "${action}" in
    image-upload)
      remote_verify_proof "${run_root}/v126-image-transfer-ready.proof" || die 'upload preparation proof is absent'
      remote_verify_maintenance_env_binding "${staging_path}" "${run_root}" "${run_id}" \
        "${release_sha}" V126_SMOKE "${V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256:-}" || die 'upload environment binding failed'
      destination="${run_root}/v126-image.tar.partial" ;;
    preflight-upload) destination="${run_root}/final-v125-preflight.sh.partial" ;;
    *) die 'unknown upload class' ;;
  esac
  # FD8 is the remaining NUL-framed SSH input. This receiver is a child of the
  # common target supervisor; there is no independent rsync writer after unlock.
  python3 - "${destination}" "${expected_sha}" "${expected_size}" "${action}" <<'PY'
import hashlib, os, select, stat, sys, time
path, expected, size, action = sys.argv[1:]
size = int(size)
maximum = 8 * 1024**3 if action == 'image-upload' else 1024**2
if not 0 < size <= maximum:
    raise SystemExit('upload size outside source contract')
deadline = time.monotonic() + (840 if action == 'image-upload' else 120)
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
digest = hashlib.sha256()
remaining = size
try:
    while remaining:
        wait = min(30, deadline - time.monotonic())
        if wait <= 0 or not select.select([8], [], [], wait)[0]:
            raise SystemExit('upload transport outcome UNKNOWN')
        block = os.read(8, min(1024**2, remaining))
        if not block:
            raise SystemExit('upload transport truncated')
        digest.update(block)
        remaining -= len(block)
        view = memoryview(block)
        while view:
            view = view[os.write(fd, view):]
    if not select.select([8], [], [], min(30, max(0, deadline-time.monotonic())))[0] or os.read(8, 1):
        raise SystemExit('upload framing did not complete')
    if digest.hexdigest() != expected:
        raise SystemExit('upload bytes differ from source identity')
    os.fsync(fd)
finally:
    os.close(fd)
parent = os.open(os.path.dirname(path), os.O_RDONLY | os.O_DIRECTORY)
try: os.fsync(parent)
finally: os.close(parent)
print('UPLOAD_COMPLETED sha256=' + expected + ' bytes=' + str(size))
PY
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
  remote_compose create --force-recreate --no-build --pull never backend >/dev/null ||
    die 'backend create failed; outcome requires reconciliation'
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
  remote_assert_bound_container_environment "${backend_container}" "${phase}" || die 'created backend environment failed'
  remote_assert_database_target || die 'created backend database target plan failed'
  docker update --restart=no "${backend_container}" >/dev/null || die 'restart policy update failed'
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${backend_container}")" == 'no:0' ]] ||
    die 'V126 backend restart policy was not disabled before start'
  docker start "${backend_container}" >/dev/null || die 'V126 start command failed; outcome requires reconciliation'
  remote_wait_backend_ready "${backend_container}" "${image_id}" "${release_sha}" \
    "${phase}" "${REMOTE_BOUND_ENV_SHA256}" || die 'V126 readiness did not complete; start must not be repeated'
  remote_assert_database_target || die 'running backend database target failed'
  remote_assert_single_v126_backend_poller "${image_id}"
  remote_assert_health_json http://127.0.0.1:8080/health
  remote_assert_version "${release_sha}"
  local proof="${run_root}/v126-backend-${phase}-started.proof"
  if [[ "${phase}" == final ]]; then
    remote_assert_runtime "${staging_path}" "${release_sha}" "${image_id}" OFF true
  fi
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" "phase=${phase}" \
    "image_tag=${image_tag}" "image_id=${image_id}" "backend_container_id=${backend_container}" 'compose_build=false' \
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
  remote_assert_caddy_drain_marker || die 'Caddy consumer verification failed'
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local original="${evidence_root}/Caddyfile.original"
  local original_checksum="${evidence_root}/Caddyfile.original.sha256"
  remote_sudo_require_root_file "${original}" 600 || die 'Caddy file metadata verification failed'
  local original_sha
  local expected_original_sha
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  expected_original_sha="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
  [[ "${original_sha}" == "${expected_original_sha}" ]] ||
    die 'original Caddyfile differs from the immutable stage receipt'
  [[ "$(remote_sudo_read_sha256_checksum "${original_checksum}")" == "${expected_original_sha}" ]] ||
    die 'original Caddyfile sidecar differs from the immutable stage receipt'
  cutover_bounded_command 15 sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null || die 'Caddy validation failed'
  cutover_bounded_command 15 sudo install -o root -g root -m 0644 "${original}" /etc/caddy/Caddyfile || die 'Caddy install failed; active configuration requires reconciliation'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${original_sha}" ]] ||
    die 'restored Caddyfile is not byte-identical to the original'
  cutover_bounded_command 20 sudo systemctl reload caddy || die 'Caddy reload failed; active configuration requires reconciliation'
  remote_assert_caddy_service_active || die 'Caddy active service proof failed'
  remote_assert_caddy_config_active "${original}" || die 'original Caddy runtime is not established'
  sudo rm -f -- /etc/caddy/v126-drain.enabled || die 'Caddy drain marker removal failed'
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
  curl --disable --connect-timeout 3 --max-time 10 -fsS http://127.0.0.1:2019/config/ > "${admin_config}" 2>/dev/null || {
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
  remote_assert_public_live || die 'Caddy consumer verification failed'
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
  cutover_bounded_command 45 git \
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

# Release CI contract: exact expanded job names from .github/workflows/ci.yml.
# The cutover harness checks workflow agreement; the release binds this entire script.
release_ci_python() {
  cat <<'PY_CI'
import hashlib
import json
import os
import stat

CI_REPOSITORY = "koteev-m/hookah_bot"
CI_WORKFLOW_ID = 230370033
CI_REQUIRED_JOBS = (
    "backend",
    "backend-archive-reproducibility (21)",
    "backend-compile (21)",
    "backend-ktlint (21)",
    "backend-migration-sanity (21)",
    "backend-release-critical-routes (21)",
    "backend-telegram-lightweight (21)",
    "backend-venue-booking-rbac (21)",
    "compose",
    "docker (backend)",
    "miniapp (20)",
    "miniapp-e2e-smoke (20)",
)
CI_CONTRACT_SHA256 = hashlib.sha256(json.dumps({
    "repository": CI_REPOSITORY, "workflow_id": CI_WORKFLOW_ID,
    "workflow_path": ".github/workflows/ci.yml", "workflow_name": "CI",
    "event": "push", "branch": "main", "attempt": 1,
    "jobs": CI_REQUIRED_JOBS, "unexpected_jobs": "reject",
}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def ci_unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise SystemExit("main Actions duplicate JSON key")
        result[key] = value
    return result

def read_main_actions(path, release_sha, run_id, sealed=False):
    info = os.lstat(path)
    modes = (0o400,) if sealed else (0o400, 0o600)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) not in modes:
        raise SystemExit("main Actions evidence file mode or ownership mismatch")
    with open(path, "rb") as handle:
        raw = handle.read()
    try:
        doc = json.loads(raw, object_pairs_hook=ci_unique_object)
    except (ValueError, UnicodeError):
        raise SystemExit("main Actions malformed JSON")
    if not isinstance(doc, dict):
        raise SystemExit("main Actions must be an object")
    required = {
        "databaseId": int(run_id), "workflowName": "CI", "workflowDatabaseId": CI_WORKFLOW_ID,
        "event": "push", "headBranch": "main", "headSha": release_sha,
        "attempt": 1, "status": "completed", "conclusion": "success",
    }
    for key, value in required.items():
        if type(doc.get(key)) is not type(value) or doc[key] != value:
            raise SystemExit(f"main Actions mismatch: {key}")
    jobs = doc.get("jobs")
    if not isinstance(jobs, list) or any(not isinstance(job, dict) for job in jobs):
        raise SystemExit("main Actions jobs must be an object list")
    names = [job.get("name") for job in jobs]
    if any(not isinstance(name, str) for name in names) or len(set(names)) != len(names):
        raise SystemExit("main Actions job names are invalid or duplicated")
    if set(names) != set(CI_REQUIRED_JOBS):
        raise SystemExit("main Actions job set differs from tracked release CI contract")
    if any(job.get("status") != "completed" or job.get("conclusion") != "success" for job in jobs):
        raise SystemExit("main Actions required jobs are not completed successes")
    return hashlib.sha256(raw).hexdigest()

def local_baseline_bytes(manifest, actions_sha):
    rows = [
        ("run_id", manifest["run_id"]), ("release_sha", manifest["release_sha"]),
        ("release_tree", manifest["release_tree"]),
        ("release_parents", ",".join(manifest["release_parents"])),
        ("main_actions_run_id", manifest["main_actions_run_id"]),
        ("main_actions_jobs", f"{len(CI_REQUIRED_JOBS)}/{len(CI_REQUIRED_JOBS)}"),
        ("main_actions_contract_sha256", CI_CONTRACT_SHA256),
        ("main_actions_sha256", actions_sha),
        ("migration_sha256", "ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5"),
        ("flyway_checksum", "1701638026"), ("v126_image_id", manifest["v126_image_id"]),
        ("result", "PASS"),
    ]
    return "".join(f"{key}={value}\n" for key, value in rows).encode()
PY_CI
}

validate_main_actions_run() {
  local target="$1"
  {
    release_ci_python
    cat <<'PY'
import sys
read_main_actions(sys.argv[1], sys.argv[2], sys.argv[3])
PY
  } | python3 - "${target}" "${RELEASE_SHA}" "${MAIN_ACTIONS_RUN_ID}"
}

write_local_baseline_proof() {
  local target="$1"
  local actions="$2"
  {
    release_ci_python
    cat <<'PY'
import sys
manifest = json.load(open(sys.argv[1], "rt", encoding="utf-8"))
actions_sha = read_main_actions(sys.argv[2], manifest["release_sha"], manifest["main_actions_run_id"], sealed=True)
fd = os.open(sys.argv[3], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "wb") as handle:
    handle.write(local_baseline_bytes(manifest, actions_sha))
PY
  } | python3 - "${STATE_DIR}/run.json" "${actions}" "${target}"
}

verify_release_baseline_local() {
  require_cmd git
  require_cmd gh
  require_cmd docker
  [[ -d "${RELEASE_WORKTREE}" && ! -L "${RELEASE_WORKTREE}" ]] || die 'release worktree is unavailable'
  release_git "${RELEASE_WORKTREE}" fetch --no-tags origin main || die 'release fetch failed'
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
  local precheck_dir="${BASELINE_ATTEMPT_DIR:-${STATE_DIR}/tmp}"
  local actions_json="${precheck_dir}/main-actions-${MAIN_ACTIONS_RUN_ID}.json"
  [[ ! -e "${actions_json}" && ! -L "${actions_json}" ]] || die 'main Actions temporary artifact exists'
  cutover_bounded_command 45 gh run view "${MAIN_ACTIONS_RUN_ID}" --repo koteev-m/hookah_bot \
    --json databaseId,workflowName,workflowDatabaseId,event,headBranch,headSha,attempt,status,conclusion,jobs > "${actions_json}" ||
    die 'main Actions evidence command failed'
  chmod 0600 "${actions_json}"
  validate_main_actions_run "${actions_json}" || die 'main Actions release contract rejected'
  local actions_hash
  actions_hash="$(hash_file "${actions_json}")"
  local sealed_actions="${BASELINE_ATTEMPT_DIR:-${STATE_DIR}/artifacts}/main-actions.json"
  [[ ! -e "${sealed_actions}" && ! -L "${sealed_actions}" ]] || die 'sealed main Actions evidence exists'
  chmod 0400 "${actions_json}"
  mv "${actions_json}" "${sealed_actions}"

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
  local record="${precheck_dir}/local-baseline.proof"
  write_local_baseline_proof "${record}" "${sealed_actions}" || die 'local baseline proof failed'
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
  if [[ "${BASELINE_PRECHECK_COMPLETED:-false}" == true ]]; then
    [[ ! -e "${STATE_DIR}/artifacts/main-actions.json" && ! -L "${STATE_DIR}/artifacts/main-actions.json" ]] ||
      die 'main Actions publication path exists'
    cp "${BASELINE_ATTEMPT_DIR}/main-actions.json" "${STATE_DIR}/artifacts/main-actions.json" ||
      die 'verified main Actions evidence could not be published'
    chmod 0400 "${STATE_DIR}/artifacts/main-actions.json" || die 'main Actions protection failed'
    cat "${BASELINE_ATTEMPT_DIR}/operation.log" || die 'precheck operation log read failed'
  else
    verify_release_baseline_local || die 'baseline precheck failed'
  fi
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
  local V126_LOCAL_UPLOAD_FD=9 upload_size
  exec 9<"${extracted}"
  upload_size="$(python3 -c 'import os; print(os.fstat(9).st_size)')" || die 'preflight upload size unavailable'
  local_emit_artifact final-v125-preflight-source "${script_sha}"
  run_remote preflight-upload "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" \
    "${V125_IMAGE_TAG}" "${script_sha}" "${upload_size}" || die 'preflight upload outcome requires reconciliation'
  exec 9<&-
  local database_binding_sha
  database_binding_sha="$(receipt_artifact_hash BASELINE_VERIFIED database-url-binding)"
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
  local_emit_artifact local-v126-image-archive "${archive_sha}"
  run_remote image-prepare "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" "${V126_IMAGE_ID}"
  local V126_LOCAL_UPLOAD_FD="${archive_fd}" upload_size
  upload_size="$(python3 -c 'import os; print(os.fstat(9).st_size)')" || die 'image upload size unavailable'
  run_remote image-upload "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" \
    "${V126_IMAGE_TAG}" "${archive_sha}" "${upload_size}" || die 'image upload outcome requires reconciliation'
  run_remote image-load "${STAGING_PATH}" "${RUN_ID}" "${RELEASE_SHA}" "${V126_IMAGE_TAG}" \
    "${V126_IMAGE_ID}" "${archive_sha}"
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
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
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
  cp --preserve=mode,ownership,timestamps .env "${before}" || die 'environment snapshot failed'
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
  local derive_status=$?
  [[ "${derive_status}" == 0 ]] || die 'environment derivation failed'
  chmod 0600 "${candidate}" || die 'cannot protect environment candidate'
  remote_validate_environment_candidate "${staging_path}" "${candidate}" OFF ||
    die 'candidate environment validation failed'
  local before_sha
  before_sha="$(remote_hash_file "${before}")"
  [[ "$(remote_hash_file .env)" == "${expected_source_sha}" ]] ||
    die 'staging environment changed during recovery PRODUCT/OFF transformation'
  remote_install_environment_candidate "${candidate}" "${next_env}" "${expected_source_sha}" ||
    die 'environment installation failed; reconcile the fixed environment before any further mutation'
  remote_validate_fixed_environment "${staging_path}" "${staging_path}" OFF ||
    die 'installed fixed environment validation failed'
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
  local require_drain="${3:-true}"
  [[ "${require_drain}" == true || "${require_drain}" == false ]] || die 'invalid V125 drain policy'
  local backend_container observed
  remote_capture_compose_ids running backend || die 'runtime prerequisite consumer failed'
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'V125 recovery backend count is not one'
  backend_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  remote_capture_compose_ids all backend || die 'runtime prerequisite consumer failed'
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'V125 recovery has an extra stopped or running backend container'
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${backend_container}" ]] ||
    die 'V125 recovery running backend is not the unique Compose backend'
  observed="$(docker inspect --format '{{.Image}}' "${backend_container}")" || die 'V125 recovery running image query failed'
  [[ "${observed}" == "${V125_IMAGE_ID}" ]] ||
    die 'V125 recovery running image ID mismatch'
  observed="$(docker image inspect --format '{{.Id}}' "${image_tag}")" || die 'V125 recovery loaded image query failed'
  [[ "${observed}" == "${V125_IMAGE_ID}" ]] ||
    die 'V125 recovery loaded image ID mismatch'
  observed="$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${backend_container}")" || die 'V125 recovery restart policy query failed'
  [[ "${observed}" == "no:0" ]] ||
    die 'V125 recovery restart policy or RestartCount mismatch'
  docker exec "${backend_container}" sh -c \
    'test "${TELEGRAM_BOT_ENABLED:-}" = true && test "${TELEGRAM_BOT_MODE:-}" = long_polling' >/dev/null ||
    die 'V125 recovery does not have the unique long-polling Telegram poller configuration'
  remote_require_unique_global_image_container "${V125_IMAGE_ID}" "${backend_container}" || die 'runtime prerequisite consumer failed'
  remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID:-}" 0 || die 'runtime prerequisite consumer failed'
  remote_assert_health_json http://127.0.0.1:8080/health || die 'runtime prerequisite consumer failed'
  remote_assert_health_json http://127.0.0.1:8080/db/health || die 'runtime prerequisite consumer failed'
  remote_assert_version "${V125_SOURCE_SHA}" || die 'runtime prerequisite consumer failed'
  "${staging_path}/scripts/check-staging-maintenance-config.sh" .env >/dev/null || die 'runtime prerequisite consumer failed'
  "${staging_path}/scripts/validate-staging-admission.sh" \
    --profile public-pilot --env-file .env --compose-file docker-compose.yml >/dev/null || die 'runtime prerequisite consumer failed'
  observed="$(remote_flyway_state)" || die 'V125 recovery Flyway query failed'
  [[ "${observed}" == '125:0:0:0' ]] || die 'V125 recovery Flyway state mismatch'
  local queues
  queues="$(remote_compose exec -T postgres sh -c \
    ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 PGOPTIONS="-c statement_timeout=15000 -c lock_timeout=5000" psql -X -w -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')), ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING'))
);
SQL
)" || die 'V125 recovery queue query failed'
  [[ "${queues}" == '0:0' ]] || die 'V125 recovery queue gate mismatch'
  remote_assert_telegram_idle .env || die 'runtime prerequisite consumer failed'
  if [[ "${require_drain}" == true ]]; then
    remote_assert_public_drain || die 'runtime prerequisite consumer failed'
  else
    remote_assert_public_live || die 'completed recovery public gate failed'
  fi
}

remote_recovery_restore_original_caddy() {
  local release_sha="$1"
  local run_id="$2"
  local expected_original
  local expected_candidate
  if [[ "${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}" == NONE ]]; then
    remote_verify_partial_caddy_evidence "${release_sha}" "${run_id}" || die 'Caddy consumer verification failed'
    expected_original="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256:-}"
    expected_candidate="$(sudo sha256sum \
      "$(remote_caddy_evidence_root "${release_sha}" "${run_id}")/Caddyfile.drain" | awk '{print $1}')" ||
      die 'Caddy file digest query failed'
  else
    remote_verify_caddy_receipt_evidence "${release_sha}" "${run_id}" || die 'Caddy consumer verification failed'
    expected_original="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
    expected_candidate="${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}"
  fi
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local original="${evidence_root}/Caddyfile.original"
  local candidate="${evidence_root}/Caddyfile.drain"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'recovery Caddy evidence root ownership or mode mismatch'
  remote_sudo_require_root_file "${original}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file "${candidate}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || die 'Caddy file metadata verification failed'
  local digest
  local candidate_digest
  local active_digest
  digest="$(sudo sha256sum "${original}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  candidate_digest="$(sudo sha256sum "${candidate}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  active_digest="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" || die 'Caddy file digest query failed'
  [[ "${digest}" == "${expected_original}" ]] ||
    die 'recovery original Caddyfile differs from immutable authority'
  [[ "${candidate_digest}" == "${expected_candidate}" ]] ||
    die 'recovery candidate Caddyfile differs from immutable authority'
  [[ "${active_digest}" == "${expected_candidate}" ]] ||
    die 'recovery refuses to overwrite an active Caddyfile other than the sealed candidate'
  cutover_bounded_command 15 sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null || die 'Caddy validation failed'
  cutover_bounded_command 15 sudo install -o root -g root -m 0644 "${original}" /etc/caddy/Caddyfile || die 'Caddy install failed; active configuration requires reconciliation'
  cutover_bounded_command 20 sudo systemctl reload caddy || die 'Caddy reload failed; active configuration requires reconciliation'
  remote_assert_caddy_service_active || die 'Caddy active service proof failed'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${digest}" ]] ||
    die 'Caddy recovery restoration is not byte-identical'
  remote_assert_caddy_config_active "${original}" || die 'original Caddy runtime is not established'
  remote_assert_caddy_drain_marker || die 'Caddy consumer verification failed'
  sudo rm -f -- /etc/caddy/v126-drain.enabled || die 'Caddy drain marker removal failed'
  remote_assert_public_live || die 'Caddy consumer verification failed'
  printf '%s\n' "${digest}"
}

remote_recovery_ensure_candidate_drain() {
  local release_sha="$1"
  local run_id="$2"
  remote_verify_caddy_receipt_evidence "${release_sha}" "${run_id}" || die 'Caddy consumer verification failed'
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local candidate="${evidence_root}/Caddyfile.drain"
  local original="${evidence_root}/Caddyfile.original"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'recovery Caddy evidence root ownership or mode mismatch'
  remote_sudo_require_root_file "${candidate}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file "${original}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || die 'Caddy file metadata verification failed'
  local candidate_sha
  local original_sha
  local active_sha
  candidate_sha="$(sudo sha256sum "${candidate}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  active_sha="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" || die 'Caddy file digest query failed'
  [[ "${candidate_sha}" == "${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}" ]] ||
    die 'recovery Caddy candidate differs from the immutable stage receipt'
  [[ "${original_sha}" == "${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}" ]] ||
    die 'recovery Caddy original differs from the immutable stage receipt'
  [[ "${active_sha}" == "${original_sha}" || "${active_sha}" == "${candidate_sha}" ]] ||
    die 'post-V126 recovery refuses an unrecognized active Caddyfile'
  if [[ "${active_sha}" != "${candidate_sha}" ]]; then
    cutover_bounded_command 15 sudo caddy validate --config "${candidate}" --adapter caddyfile >/dev/null || die 'Caddy validation failed'
    cutover_bounded_command 15 sudo install -o root -g root -m 0644 "${candidate}" /etc/caddy/Caddyfile || die 'Caddy install failed; active configuration requires reconciliation'
    cutover_bounded_command 20 sudo systemctl reload caddy || die 'Caddy reload failed; active configuration requires reconciliation'
  fi
  remote_assert_caddy_candidate_active "${release_sha}" "${run_id}"
  sudo test ! -L /etc/caddy/v126-drain.enabled
  if ! sudo test -e /etc/caddy/v126-drain.enabled; then
    sudo install -o root -g root -m 0600 /dev/null /etc/caddy/v126-drain.enabled
  fi
  remote_assert_caddy_drain_marker || die 'Caddy consumer verification failed'
  remote_assert_public_drain || die 'Caddy consumer verification failed'
}

remote_recovery_ensure_pre_v126_drain() {
  local release_sha="$1"
  local run_id="$2"
  local expected_original
  local expected_candidate
  if [[ "${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}" == NONE ]]; then
    remote_verify_partial_caddy_evidence "${release_sha}" "${run_id}" || die 'Caddy consumer verification failed'
    expected_original="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256:-}"
    expected_candidate="$(sudo sha256sum \
      "$(remote_caddy_evidence_root "${release_sha}" "${run_id}")/Caddyfile.drain" | awk '{print $1}')" ||
      die 'Caddy file digest query failed'
  else
    remote_verify_caddy_receipt_evidence "${release_sha}" "${run_id}" || die 'Caddy consumer verification failed'
    expected_original="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256:-}"
    expected_candidate="${V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256:-}"
  fi
  local evidence_root
  evidence_root="$(remote_caddy_evidence_root "${release_sha}" "${run_id}")"
  local original="${evidence_root}/Caddyfile.original"
  local candidate="${evidence_root}/Caddyfile.drain"
  [[ "$(sudo stat -c '%a:%U:%G' "${evidence_root}")" == '700:root:root' ]] ||
    die 'pre-V126 Caddy evidence root ownership or mode mismatch'
  remote_sudo_require_root_file "${original}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file "${candidate}" 600 || die 'Caddy file metadata verification failed'
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || die 'Caddy file metadata verification failed'
  local original_sha
  local candidate_sha
  local active_sha
  original_sha="$(sudo sha256sum "${original}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  candidate_sha="$(sudo sha256sum "${candidate}" | awk '{print $1}')" || die 'Caddy file digest query failed'
  active_sha="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" || die 'Caddy file digest query failed'
  [[ "${original_sha}" == "${expected_original}" ]] ||
    die 'pre-V126 original Caddy differs from immutable authority'
  [[ "${candidate_sha}" == "${expected_candidate}" ]] ||
    die 'pre-V126 candidate Caddy differs from immutable authority'
  [[ "${active_sha}" == "${original_sha}" || "${active_sha}" == "${candidate_sha}" ]] ||
    die 'pre-V126 recovery refuses an unrecognized active Caddyfile'
  cutover_bounded_command 15 sudo caddy validate --config "${original}" --adapter caddyfile >/dev/null || die 'Caddy validation failed'
  cutover_bounded_command 15 sudo caddy validate --config "${candidate}" --adapter caddyfile >/dev/null || die 'Caddy validation failed'
  cutover_bounded_command 15 sudo install -o root -g root -m 0644 "${candidate}" /etc/caddy/Caddyfile || die 'Caddy install failed; active configuration requires reconciliation'
  cutover_bounded_command 20 sudo systemctl reload caddy || die 'Caddy reload failed; active configuration requires reconciliation'
  remote_assert_caddy_service_active || die 'Caddy active service proof failed'
  [[ "$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" == "${candidate_sha}" ]] ||
    die 'pre-V126 recovery Caddy candidate is not byte-identical'
  remote_assert_caddy_config_active "${candidate}" || die 'candidate Caddy runtime is not established'
  sudo test ! -L /etc/caddy/v126-drain.enabled
  if ! sudo test -e /etc/caddy/v126-drain.enabled; then
    sudo install -o root -g root -m 0600 /dev/null /etc/caddy/v126-drain.enabled
  fi
  remote_assert_caddy_drain_marker || die 'Caddy consumer verification failed'
  remote_assert_public_drain || die 'Caddy consumer verification failed'
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
  remote_compose create --force-recreate --no-build --pull never backend >/dev/null ||
    die 'backend create failed; outcome requires reconciliation'
  remote_capture_compose_ids all backend
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) ||
    die 'pre-V126 recovery did not create exactly one V125 backend'
  local recovery_container="${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
  [[ "$(docker inspect --format '{{.Image}}' "${recovery_container}")" == "${V125_IMAGE_ID}" ]] ||
    die 'created pre-V126 recovery backend image mismatch'
  [[ "$(remote_hash_file .env)" == "${REMOTE_RECOVERY_ENV_AFTER_SHA256}" ]] ||
    die 'recovery environment changed during V125 backend creation'
  remote_assert_bound_container_environment "${recovery_container}" pre-v126 || die 'recovery backend environment failed'
  remote_assert_database_target || die 'recovery backend database target plan failed'
  docker update --restart=no "${recovery_container}" >/dev/null || die 'recovery restart policy update failed'
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${recovery_container}")" == 'no:0' ]] ||
    die 'pre-V126 recovery restart policy was not disabled before start'
  docker start "${recovery_container}" >/dev/null || die 'V125 start command failed; outcome requires reconciliation'
  remote_wait_backend_ready "${recovery_container}" "${V125_IMAGE_ID}" "${V125_SOURCE_SHA}" \
    pre-v126 "${REMOTE_RECOVERY_ENV_AFTER_SHA256}" || die 'V125 readiness did not complete; start must not be repeated'
  remote_assert_database_target || die 'running recovery database target failed'
  remote_assert_v125_runtime "${staging_path}" "${v125_image_tag}" || die 'recovery runtime verification failed'
  local caddy_sha
  caddy_sha="$(remote_recovery_restore_original_caddy "${release_sha}" "${run_id}")" ||
    die 'Caddy recovery did not complete; no recovery completion proof is allowed'
  [[ "${caddy_sha}" =~ ^[0-9a-f]{64}$ ]] || die 'invalid recovered Caddy identity'
  local proof="${run_root}/recovery-pre-v126.proof"
  remote_write_proof "${proof}" \
    "run_id=${run_id}" "release_sha=${release_sha}" 'flyway=125:0:0:0' \
    "v125_source_sha=${V125_SOURCE_SHA}" "v125_image_id=${V125_IMAGE_ID}" "backend_container_id=${recovery_container}" \
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
    < "${dump}" > "${generated}" || die 'DR inventory consumer failed'
  chmod 0600 "${generated}"
  python3 -c "$(remote_database_evidence_python)" toc "${dump}" "${expected_dump_sha}" \
    "${inventory}" "${generated}" || die 'DR inventory does not match the selected archive'
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
  {
    remote_operation_bindings_python || return $?
    cat <<'PY'
source, target, run_id, release_sha, phase = sys.argv[1:]
if stat.S_IMODE(os.stat(source).st_mode) not in (0o400, 0o600):
    raise SystemExit("DR boundary evidence must be mode 0400 or 0600")
raw = Path(source).read_bytes()
binding_validate_dr_boundary(raw, run_id, release_sha, phase)
binding_create_raw(Path(target), raw)
PY
  } | python3 - "${source}" "${destination}" "${RUN_ID}" "${RELEASE_SHA}" "${phase}"
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
    "$(recovery_receipt_path post-v126-stop)" \
    "$(recovery_receipt_path post-v126-stop).sha256" \
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
reconciled = receipt_path.endswith('.reconciliation.json')
if reconciled: receipt_keys |= {'remote_evidence_sha256', 'original_operation_log_sha256'}
if set(terminal) != terminal_keys or set(intent) != intent_keys or set(receipt) != receipt_keys:
    raise SystemExit("post-V126 recovery schema mismatch")
if any(doc.get("format_version") != 1 for doc in (terminal, intent)) or receipt["format_version"] != (2 if reconciled else 1):
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
if receipt.get("mode") != "post-v126-stop" or receipt.get("result_category") != ("RECONCILED_TERMINAL_RECOVERY" if reconciled else "TERMINAL_RECOVERY_BOUNDARY"):
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

verify_reconciliation_recovery_intent() {
  local mode="$1" token_hash fields predecessor previous_hash intent_sha observed
  case "${mode}" in
    pre-v126) token_hash="$(hash_text "${PRE_V126_ROLLBACK_TOKEN}")" ;;
    post-v126-stop) token_hash="$(hash_text "${POST_V126_STOP_TOKEN}")" ;;
    verify-full-dr) token_hash="$(hash_text "${FULL_DR_VERIFY_TOKEN}")" ;;
    *) die 'unknown reconciliation recovery class' ;;
  esac
  [[ "$(classify_status_record "${STATE_DIR}/run-terminal.json" terminal)" == RECONCILIATION_REQUIRED ]] ||
    die 'original recovery terminal marker is invalid'
  [[ "$(read_recovery_state)" != INVALID_EVIDENCE ]] || die 'recovery evidence inventory is invalid'
  fields="$(
    {
      remote_operation_bindings_python
      cat <<'PY'
state, mode, token_sha = sys.argv[1:]
state = Path(state)
manifest = binding_read(state / 'run.json')
path = state / 'recovery' / (mode + '.intent.json')
doc = binding_read(path)
checksum = Path(str(path) + '.sha256')
binding_protected(checksum, 0o400)
if checksum.read_bytes() != (binding_hash(path) + '\n').encode(): raise BindingError('intent_checksum')
keys = {'authorization_token_sha256','format_version','intent_at','kind','mode',
        'predecessor_receipt_sha256','predecessor_stage','release_sha','run_id','script_sha256'}
if (set(doc) != keys or type(doc['format_version']) is not int or doc['format_version'] != 1 or
        doc['kind'] != 'RECOVERY_INTENT' or doc['mode'] != mode or doc['authorization_token_sha256'] != token_sha or
        any(doc[key] != manifest[key] for key in ('run_id','release_sha','script_sha256'))):
    raise BindingError('recovery_intent_binding')
datetime.datetime.strptime(doc['intent_at'], '%Y-%m-%dT%H:%M:%SZ')
print(doc['predecessor_stage']+'\t'+doc['predecessor_receipt_sha256']+'\t'+binding_hash(path))
PY
    } | python3 - "${STATE_DIR}" "${mode}" "${token_hash}"
  )" || die 'original recovery intent is invalid'
  IFS=$'\t' read -r predecessor previous_hash intent_sha <<< "${fields}"
  if [[ "${predecessor}" == RECOVERY_POST_V126_STOP ]]; then
    [[ "${mode}" == verify-full-dr ]] || die 'invalid recovery predecessor kind'
    observed="$(verify_post_v126_recovery_for_dr)" || die 'post-stop predecessor is invalid'
    [[ "${observed%%$'\t'*}" == "${previous_hash}" ]] || die 'post-stop predecessor hash differs'
  elif [[ "${predecessor}" == NONE ]]; then
    [[ "${previous_hash}" == NONE ]] || die 'empty predecessor hash differs'
  else
    [[ "$(verify_receipt "${predecessor}")" == "${previous_hash}" ]] || die 'recovery predecessor chain is invalid'
  fi
  printf '%s\n' "${intent_sha}"
}

write_reconciled_recovery_completion() {
  local mode="$1" bundle="$2" expected
  verify_reconciliation_recovery_intent "${mode}" >/dev/null || die 'recovery intent no longer verifies'
  case "${mode}" in
    pre-v126) expected=recovery-pre-v126 ;;
    post-v126-stop) expected=recovery-post-v126-stop ;;
    verify-full-dr) expected=dr-boundary,dr-selected-backup,dr-selected-inventory,recovery-full-dr-prerequisites ;;
    *) die 'unknown recovery class' ;;
  esac
  {
    remote_operation_bindings_python
    cat <<'PY'
state, mode, bundle_path, source_path, expected = sys.argv[1:]
binding_write_completion(state, 'RECOVERY', mode, 0, bundle_path, source_path, expected)
PY
  } | python3 - "${STATE_DIR}" "${mode}" "${bundle}" "${SCRIPT_PATH}" "${expected}" ||
    die 'original recovery evidence is incomplete; target remains blocked'
  verify_recovery_receipt "${mode}" >/dev/null || die 'reconciled recovery completion failed validation'
}

recovery_receipt_path() {
  local base="${STATE_DIR}/recovery/$1"
  if [[ ! -e "${base}.receipt.json" && ! -L "${base}.receipt.json" &&
    ( -e "${base}.reconciliation.json" || -L "${base}.reconciliation.json" ) ]]; then
    printf '%s.reconciliation.json\n' "${base}"
  else
    printf '%s.receipt.json\n' "${base}"
  fi
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
  {
    remote_operation_bindings_python
    cat <<'PY'
import hashlib
import json
import os
import re
import stat
import sys

(
    manifest_path, intent_path, intent_sum, receipt_path, receipt_sum, operation_path,
    mode, token_hash, expected_spec, script_path,
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
reconciled = receipt_path.endswith('.reconciliation.json')
if reconciled and os.path.lexists(receipt_path.removesuffix('.reconciliation.json')+'.receipt.json'):
    raise SystemExit('native and reconciled recovery cannot coexist')
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
if reconciled:
    receipt_keys |= {'remote_evidence_sha256', 'original_operation_log_sha256'}
if set(intent) != intent_keys or set(receipt) != receipt_keys:
    raise SystemExit("recovery receipt schema mismatch")
if intent["format_version"] != 1 or type(receipt["format_version"]) is not int or receipt["format_version"] != (2 if reconciled else 1) or intent["kind"] != "RECOVERY_INTENT":
    raise SystemExit("recovery receipt format mismatch")
if not timestamp.fullmatch(intent["intent_at"]) or not timestamp.fullmatch(receipt["completed_at"]):
    raise SystemExit("recovery receipt timestamp mismatch")
for doc in (intent, receipt):
    for key in ("run_id", "release_sha", "script_sha256"):
        if doc.get(key) != manifest[key]:
            raise SystemExit(f"recovery receipt identity mismatch: {key}")
    if doc.get("mode") != mode or doc.get("authorization_token_sha256") != token_hash:
        raise SystemExit("recovery receipt mode or authorization mismatch")
if receipt["result_category"] != ("RECONCILED_TERMINAL_RECOVERY" if reconciled else "TERMINAL_RECOVERY_BOUNDARY") or receipt["intent_sha256"] != intent_hash:
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
if reconciled:
    binding_protected(Path(operation_path), 0o400)
    if binding_hash(Path(operation_path)) != receipt['original_operation_log_sha256']:
        raise SystemExit('original recovery log changed')
    artifacts_dir = Path(manifest_path).parent / 'artifacts'
    bundle = artifacts_dir / f'recovery-{mode}.remote-reconciliation.json'
    if binding_hash(bundle) != receipt['remote_evidence_sha256']:
        raise SystemExit('recovery reconciliation bundle changed')
    record, remote_logs = binding_verify_reconciliation_bundle(bundle, manifest['staging_path'], manifest['script_sha256'],
        'RECOVERY', mode, intent_hash, hashlib.sha256(binding_embedded_source(Path(script_path).read_bytes(),
        'remote_reconciliation_poststate_python')).hexdigest())
    if any(record['identity'][key] != manifest[key] for key in ('run_id','release_sha','script_sha256')):
        raise SystemExit('reconciled recovery identity mismatch')
    binding_retained_local_artifacts(Path(manifest_path).parent, manifest, artifact_hashes, bundle)
    derived = binding_reconciliation_log(Path(operation_path).read_bytes(), remote_logs)
    operation_path = str(artifacts_dir / f'recovery-{mode}.reconciliation.log')
    if Path(operation_path).read_bytes() != derived:
        raise SystemExit('reconciled recovery inventory differs from original evidence')
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
  } | python3 - "${STATE_DIR}/run.json" "${STATE_DIR}/recovery/${mode}.intent.json" \
    "${STATE_DIR}/recovery/${mode}.intent.json.sha256" \
    "$(recovery_receipt_path "${mode}")" \
    "$(recovery_receipt_path "${mode}").sha256" \
    "${STATE_DIR}/recovery/${mode}.operation.log" "${mode}" "${token_hash}" \
    "${expected_artifacts}" "${SCRIPT_PATH}"
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

remote_reconciliation_poststate_python() {
  cat <<'V126_RECONCILIATION_PY'
#!/usr/bin/env python3
"""Fresh read-only observations for intact successful V126 operation records.

The binding coordinator holds the permanent target lock throughout collect(). This
module never dispatches an action or writes a stage proof. Its temporary source and
observer files are private; retained operation records are opened read-only.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import tempfile
import time


ACTIONS = frozenset({
    'baseline', 'backup-rehearsal', 'caddy-activate', 'public-drain-on',
    'stop-backend', 'zero-writer', 'final-v125-preflight', 'transform-maintenance',
    'image-prepare', 'image-load', 'start-v126', 'schema-runtime-gate',
    'open-manual-smoke', 'record-manual-smoke', 'restore-caddy', 'final-public-gates',
    'recover-pre-v126', 'recover-post-v126-stop', 'verify-full-dr',
    'preflight-upload', 'image-upload',
})
IMPLEMENTED = frozenset({
    'start-v126', 'schema-runtime-gate', 'open-manual-smoke', 'record-manual-smoke',
    'restore-caddy', 'final-public-gates', 'stop-backend', 'zero-writer',
    'recover-pre-v126', 'recover-post-v126-stop', 'transform-maintenance',
    'caddy-activate', 'public-drain-on', 'image-load',
    'baseline', 'backup-rehearsal', 'final-v125-preflight', 'verify-full-dr',
})
UNSUPPORTED_REASONS = {
    'image-prepare': 'prepare_alone_cannot_complete_ordered_transfer_group',
    'preflight-upload': 'upload_alone_cannot_complete_ordered_preflight_group',
    'image-upload': 'upload_alone_cannot_complete_ordered_transfer_group',
}
MAX_RECORD = 16 * 1024 * 1024
DEADLINE_SECONDS = 600
PROOF_FILENAMES = {'pre-drain-backup-proof': 'pre-drain-backup-rehearsed',
                   'quiesced-backup-proof': 'quiesced-backup-rehearsed'}


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def require(condition, reason):
    if not condition:
        raise ValueError('INSUFFICIENT_EVIDENCE:' + reason)


def regular(path, mode):
    path = Path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        metadata = os.fstat(fd)
        require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == os.geteuid() and
                stat.S_IMODE(metadata.st_mode) == mode and metadata.st_nlink == 1,
                'protected_file_metadata')
        require(metadata.st_size <= MAX_RECORD, 'protected_file_bound')
        raw = bytearray()
        while len(raw) <= MAX_RECORD:
            block = os.read(fd, min(65536, MAX_RECORD + 1 - len(raw)))
            if not block:
                break
            raw.extend(block)
        require(len(raw) == metadata.st_size, 'protected_file_changed')
        after = os.fstat(fd)
        require((metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns) ==
                (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'protected_file_changed')
        return bytes(raw)
    finally:
        os.close(fd)


def directory(path):
    metadata = Path(path).lstat()
    require(stat.S_ISDIR(metadata.st_mode) and metadata.st_uid == os.geteuid() and
            stat.S_IMODE(metadata.st_mode) == 0o700, 'protected_directory_metadata')


def document(path):
    raw = regular(path, 0o400)
    value = json.loads(raw)
    require(raw == canonical(value), 'original_record_not_canonical')
    return value, digest(raw)


def validate_request(target, identity, request):
    require(set(request) == {'format_version', 'identity', 'target_sha256', 'args', 'environment'} and
            type(request['format_version']) is int and request['format_version'] == 1 and
            request['identity'] == identity and request['target_sha256'] == digest(str(target).encode()),
            'original_request_binding')
    require(isinstance(request['args'], list) and 3 <= len(request['args']) <= 32 and
            all(isinstance(arg, str) and not any(c in arg for c in '\x00\n\r') for arg in request['args']) and
            request['args'][:3] == [str(target), identity['run_id'], identity['release_sha']],
            'original_request_args')
    require(isinstance(request['environment'], dict) and all(
        re.fullmatch(r'V126_INTERNAL_REMOTE_[A-Z0-9_]+', key) and isinstance(value, str) and
        not any(c in value for c in '\x00\n\r') for key, value in request['environment'].items()),
        'original_request_environment')


def operation(root, operation_id, target):
    require(re.fullmatch('[0-9a-f]{64}', operation_id), 'operation_id')
    start, start_sha = document(root / (operation_id + '.start.json'))
    require(set(start) == {'identity', 'operation_id', 'started_at', 'boot_id'} and
            start['operation_id'] == operation_id and digest(canonical(start['identity'])) == operation_id,
            'original_start_binding')
    identity = start['identity']
    require(set(identity) == {'run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'} and
            re.fullmatch('[a-z0-9][a-z0-9._-]{0,79}', identity['run_id']) and
            re.fullmatch('[0-9a-f]{40}', identity['release_sha']) and
            all(re.fullmatch('[0-9a-f]{64}', identity[key]) for key in ('script_sha256', 'intent_sha256')),
            'original_identity_schema')
    request, request_sha = document(root / (operation_id + '.request.json'))
    validate_request(target, identity, request)
    result, result_sha = document(root / (operation_id + '.result.json'))
    require(set(result) == {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} and
            result['identity'] == identity and result['operation_id'] == operation_id and
            type(result['exit']) is int and result['exit'] == 0 and result['outcome'] == 'SUCCEEDED' and
            result['children'] == 'REAPED', 'UNKNOWN_original_result_or_children')
    log = regular(root / (operation_id + '.log'), 0o400)
    require(digest(log) == result['log_sha256'], 'original_log_digest')
    files = {operation_id + suffix: value for suffix, value in (
        ('.start.json', start_sha), ('.request.json', request_sha),
        ('.result.json', result_sha), ('.log', digest(log)))}
    artifacts = {}
    for row in log.splitlines():
        if row.startswith(b'ARTIFACT'):
            match = re.fullmatch(rb'ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t([0-9a-f]{64})', row)
            require(match is not None, 'original_artifact_format')
            name, value = (part.decode() for part in match.groups())
            require(name not in artifacts, 'duplicate_original_artifact')
            artifacts[name] = value
    return identity, request, files, artifacts


class Evidence:
    def __init__(self, target, identity, request, operations):
        self.target, self.identity = Path(target), identity
        self.root = self.target / '.v126-target-operations'
        self.run_root = self.target / '.v126-runs' / identity['run_id']
        directory(self.root)
        directory(self.target / '.v126-runs')
        directory(self.run_root)
        require(isinstance(operations, list) and operations, 'original_operation_group_empty')
        self.artifacts, self.selected, self.proofs, self.original_requests = {}, {}, {}, []
        self.derived_environment = {}
        selected_ids = []
        for selected in operations:
            require(set(selected) == {'operation_id', 'files'}, 'selected_operation_schema')
            original, original_request, files, artifacts = operation(self.root, selected['operation_id'], self.target)
            require(all(original[key] == identity[key] for key in
                        ('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name')) and
                    files == selected['files'], 'selected_operation_binding')
            selected_ids.append(selected['operation_id'])
            for name, value in artifacts.items():
                require(name not in self.selected, 'duplicate_selected_artifact')
                self.selected[name] = value
        require(len(selected_ids) == len(set(selected_ids)) and original == identity and
                original_request == request, 'last_original_operation_binding')
        # Predecessor IDs/proofs must also originate in intact successful records;
        # a writable proof plus a matching writable sidecar is insufficient.
        for path in sorted(self.root.glob('*.start.json')):
            candidate, _ = document(path)
            candidate_identity = candidate.get('identity', {})
            if any(candidate_identity.get(key) != identity[key] for key in ('run_id', 'release_sha', 'script_sha256')):
                continue
            original, original_request, _, artifacts = operation(self.root, path.name.removesuffix('.start.json'), self.target)
            self.original_requests.append((original, original_request))
            for name, value in artifacts.items():
                require(name not in self.artifacts, 'ambiguous_original_artifact')
                self.artifacts[name] = value

    def derive(self, key, artifact):
        require(artifact in self.selected, 'missing_original_artifact_' + artifact)
        self.derived_environment[key] = self.selected[artifact]

    def prior_request(self, action):
        requests = [request for identity, request in self.original_requests if identity['action'] == action]
        require(len(requests) == 1, 'ambiguous_prior_request_' + action)
        return requests[0]

    def proof(self, name, *, selected=False):
        expected = (self.selected if selected else self.artifacts).get(name)
        require(expected is not None, 'missing_original_artifact_' + name)
        path = self.run_root / (PROOF_FILENAMES.get(name, name) + '.proof')
        raw = regular(path, 0o600)
        require(digest(raw) == expected and regular(Path(str(path) + '.sha256'), 0o600) ==
                (expected + '\n').encode(), 'original_proof_digest_' + name)
        fields = {}
        for row in raw.decode('utf-8').splitlines():
            require('=' in row, 'proof_row_' + name)
            key, value = row.split('=', 1)
            require(key not in fields and re.fullmatch('[a-z0-9_]+', key), 'proof_key_' + name)
            fields[key] = value
        require(fields.get('run_id') == self.identity['run_id'] and
                fields.get('release_sha') == self.identity['release_sha'], 'proof_identity_' + name)
        self.proofs[name] = expected
        return fields


def quoted_call(name, *args):
    require(re.fullmatch(r'[a-z][a-z0-9_]*', name), 'checker_function')
    return name + ' ' + ' '.join(shlex.quote(str(arg)) for arg in args)


def observer_plan(evidence, request):
    """Return only checked read-only calls; no original action implementation."""
    action = evidence.identity['action']
    require(action in IMPLEMENTED, 'unsupported_action_' + action + '_' + UNSUPPORTED_REASONS.get(action, 'unknown'))
    args = request['args']
    target, run, release = args[:3]
    expected_id = None
    require(len(args) >= (3 if action == 'caddy-activate' else 4), 'action_args_' + action)
    init_args = args[:4]
    if action == 'baseline':
        require(len(args) == 9, 'baseline_args')
        for suffix, artifact in [('DATABASE_URL', 'database-url-binding'), ('MAINTENANCE_IDENTITIES', 'maintenance-identities'),
                                 ('COMPOSE_SOURCE', 'remote-compose-source'), ('MAINTENANCE_CHECK_SOURCE', 'remote-maintenance-check-source'),
                                 ('ADMISSION_SOURCE', 'remote-admission-source'), ('CADDY', 'baseline-caddy'), ('ENV', 'baseline-env')]:
            evidence.derive('V126_INTERNAL_REMOTE_BASELINE_' + suffix + '_SHA256', artifact)
        evidence.derive('V126_INTERNAL_REMOTE_DATABASE_TARGET_IDENTITY_SHA256', 'database-target-identity')
    elif action == 'backup-rehearsal':
        require(len(args) == 5 and args[3] in ('pre-drain', 'quiesced'), 'backup_args')
        init_args = args[:3] + [args[4]]
    if action == 'caddy-activate':
        require(len(args) == 3, 'caddy_args')
        baseline = evidence.prior_request('baseline')
        require(len(baseline['args']) >= 4, 'baseline_image_argument')
        init_args = args + [baseline['args'][3]]
        for suffix, artifact in [('ORIGINAL', 'caddy-original'), ('CANDIDATE', 'caddy-candidate'),
                                 ('DIFF', 'caddy-diff'), ('ACTIVATION', 'caddy-activation')]:
            evidence.derive('V126_INTERNAL_REMOTE_CADDY_' + suffix + '_SHA256', artifact)
    calls = [('initialize', quoted_call('remote_initialize_compose', *init_args))]
    image = args[4] if len(args) >= 5 else None
    if action in {'start-v126', 'schema-runtime-gate', 'open-manual-smoke', 'record-manual-smoke',
                  'restore-caddy', 'final-public-gates'}:
        require(len(args) == (6 if action in {'start-v126', 'record-manual-smoke'} else 5) and
                re.fullmatch('sha256:[0-9a-f]{64}', image or '') and
                request['environment'].get('V126_INTERNAL_REMOTE_V126_IMAGE_ID') == image,
                'runtime_image_args')
        phase = args[5] if action == 'start-v126' else (
            'final' if action in {'restore-caddy', 'final-public-gates'} else 'first')
        require(phase in ('first', 'final'), 'start_phase')
        started = 'v126-backend-' + phase + '-started'
        original = evidence.proof(started, selected=(action == 'start-v126'))
        expected_id = original.get('backend_container_id')
        require(re.fullmatch('[0-9a-f]{64}', expected_id or '') and original.get('image_id') == image and
                original.get('image_tag') == args[3] and original.get('phase') == phase and
                original.get('start_command_count') == '1' and original.get('restart_policy') == 'no' and
                original.get('restart_count') == '0' and original.get('result') == 'PASS',
                'original_exact_start_resource')
        calls.append(('exact_backend', quoted_call('reconcile_exact_backend', expected_id, image)))
        calls.append(('readiness', quoted_call('remote_wait_backend_ready', expected_id, image, release,
                                               phase) + ' "${REMOTE_BOUND_ENV_SHA256}"'))
        mode = 'OFF' if phase == 'final' else 'V126_SMOKE'
        live = action in {'open-manual-smoke', 'record-manual-smoke', 'restore-caddy', 'final-public-gates'}
        calls.append(('runtime', quoted_call('remote_assert_runtime', target, release, image, mode, str(not live).lower())))
        if action != 'start-v126':
            own_name = {'schema-runtime-gate': 'v126-schema-runtime', 'open-manual-smoke': 'manual-smoke-window',
                        'record-manual-smoke': 'manual-smoke-passed', 'restore-caddy': 'ordinary-caddy-restored',
                        'final-public-gates': 'final-public-gates'}[action]
            own = evidence.proof(own_name, selected=True)
            require(own.get('result') == ('AUTHORIZED' if action == 'open-manual-smoke' else 'PASS'), 'original_action_result')
            if action == 'record-manual-smoke':
                require(re.fullmatch('[0-9a-f]{64}', args[5]) and own.get('evidence_sha256') == args[5], 'manual_original_binding')
        if phase == 'final' and live:
            restored = evidence.proof('ordinary-caddy-restored', selected=(action == 'restore-caddy'))
            require(restored.get('original_sha256') == request['environment'].get('V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256'),
                    'original_caddy_binding')
            calls.append(('ordinary_caddy', quoted_call('reconcile_ordinary_caddy', release, run)))
        else:
            calls.append(('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)))
        if live:
            calls.extend([('drain_absent', 'sudo test ! -e /etc/caddy/v126-drain.enabled\nsudo test ! -L /etc/caddy/v126-drain.enabled'),
                          ('public_live', 'remote_assert_public_live')])
            if phase == 'first':
                calls.append(('protected_denial', 'remote_assert_protected_unauthenticated_503'))
        else:
            calls.append(('public_drain', 'remote_assert_public_drain'))
    elif action == 'stop-backend':
        require(len(args) == 6 and args[4] in ('v125', 'v126-off-transition') and
                re.fullmatch('sha256:[0-9a-f]{64}', args[5]), 'stop_args')
        own = evidence.proof(args[4] + '-backend-stopped', selected=True)
        require(own.get('phase') == args[4] and own.get('backend_running_count') == '0' and own.get('result') == 'PASS',
                'original_stop_proof')
        calls.extend([('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)),
                      ('public_drain', 'remote_assert_public_drain'), ('stopped_backend', 'reconcile_stopped_backend')])
    elif action == 'zero-writer':
        require(len(args) == 4, 'zero_writer_args')
        own = evidence.proof('zero-writer-v125', selected=True)
        require(own.get('flyway') == '125:0:0' and own.get('result') == 'PASS', 'original_zero_writer_proof')
        calls.extend([('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0'))])
    elif action == 'recover-pre-v126':
        require(len(args) == 5, 'recovery_args')
        own = evidence.proof('recovery-pre-v126', selected=True)
        expected_id = own.get('backend_container_id')
        require(re.fullmatch('[0-9a-f]{64}', expected_id or '') and
                re.fullmatch('sha256:[0-9a-f]{64}', own.get('v125_image_id', '')) and
                own.get('result') == 'PRE_V126_ROLLBACK_COMPLETE' and own.get('flyway') == '125:0:0:0' and
                own.get('start_command_count') == '1' and own.get('restart_policy') == 'no' and
                own.get('restart_count') == '0', 'original_recovery_resource')
        calls = [('initialize_recovery', quoted_call('remote_initialize_reconciled_recovery',
                  *args[:4], evidence.proofs['recovery-pre-v126'])),
                 ('exact_backend', quoted_call('reconcile_exact_backend', expected_id, own['v125_image_id'])),
                 ('recovery_environment', quoted_call('remote_assert_bound_container_environment', expected_id, 'pre-v126')),
                 ('readiness', quoted_call('remote_wait_backend_ready', expected_id, own['v125_image_id'],
                  own.get('v125_source_sha', ''), 'pre-v126', own.get('env_after_sha256', ''))),
                 ('v125_runtime', quoted_call('remote_assert_v125_runtime', target, args[3], 'false')),
                 ('ordinary_caddy', quoted_call('reconcile_ordinary_caddy', release, run)),
                 ('drain_absent', 'sudo test ! -e /etc/caddy/v126-drain.enabled\nsudo test ! -L /etc/caddy/v126-drain.enabled'),
                 ('public_live', 'remote_assert_public_live')]
    elif action == 'recover-post-v126-stop':
        require(len(args) == 5, 'post_stop_args')
        evidence.proof('recovery-post-v126-stop', selected=True)
        calls.extend([('original_stop_contract', quoted_call('remote_verify_post_v126_stop_proof', evidence.run_root,
                      run, release, evidence.proofs['recovery-post-v126-stop'])),
                      ('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)),
                      ('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '126:1:0'))])
    elif action == 'transform-maintenance':
        require(len(args) == 7 and args[5] in ('V126_SMOKE', 'OFF'), 'maintenance_args')
        mode = args[5]
        name = 'maintenance-' + mode.lower()
        own = evidence.proof(name, selected=True)
        require(own.get('mode') == mode and own.get('result') == 'PASS', 'original_maintenance_result')
        evidence.derive('V126_INTERNAL_REMOTE_MAINTENANCE_' + ('SMOKE' if mode == 'V126_SMOKE' else 'OFF') + '_SHA256', name)
        calls.extend([('maintenance_proof', quoted_call('remote_verify_maintenance_env_binding', target,
                      evidence.run_root, run, release, mode, evidence.proofs[name])),
                      ('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0' if mode == 'V126_SMOKE' else '126:1:0')),
                      ('temporary_cleanup', quoted_call('reconcile_maintenance_cleanup', evidence.run_root, mode))])
    elif action == 'caddy-activate':
        calls.extend([('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)),
                      ('drain_absent', 'sudo test ! -e /etc/caddy/v126-drain.enabled\nsudo test ! -L /etc/caddy/v126-drain.enabled')])
    elif action == 'public-drain-on':
        require(len(args) == 6 and args[4] in ('initial', 'reactivated') and
                re.fullmatch('sha256:[0-9a-f]{64}', args[5]), 'drain_args')
        own = evidence.proof('public-drain-' + ('active' if args[4] == 'initial' else 'reactivated'), selected=True)
        require(own.get('phase') == args[4] and own.get('result') == 'PASS', 'original_drain_result')
        calls.append(('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)))
        if args[4] == 'initial':
            calls.append(('v125_runtime', quoted_call('remote_assert_v125_runtime', target, args[3])))
        else:
            started = evidence.proof('v126-backend-first-started')
            expected_id = started.get('backend_container_id')
            require(re.fullmatch('[0-9a-f]{64}', expected_id or '') and started.get('image_id') == args[5], 'drain_backend_binding')
            evidence.proof('manual-smoke-passed')
            calls.extend([('exact_backend', quoted_call('reconcile_exact_backend', expected_id, args[5])),
                          ('runtime', quoted_call('remote_assert_runtime', target, release, args[5], 'V126_SMOKE', 'true'))])
        calls.append(('public_drain', 'remote_assert_public_drain'))
    elif action == 'image-load':
        require(len(args) == 6 and re.fullmatch('sha256:[0-9a-f]{64}', args[4]) and
                re.fullmatch('[0-9a-f]{64}', args[5]), 'image_args')
        own = evidence.proof('v126-image-transferred', selected=True)
        evidence.proof('v126-image-transfer-ready', selected=True)
        require(own.get('archive_sha256') == args[5] and own.get('remote_image_id') == args[4] and
                own.get('image_tag') == args[3] and own.get('result') == 'PASS' and
                evidence.selected.get('v126-image-archive') == args[5], 'original_image_result')
        calls.extend([('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0')),
                      ('sealed_image', quoted_call('reconcile_loaded_image', evidence.run_root, *args[3:6]))])
    elif action == 'baseline':
        require(all(evidence.selected.get(name) == value for name, value in zip(
                    ('remote-compose-source', 'remote-maintenance-check-source', 'remote-admission-source'), args[6:9])),
                'baseline_original_source_arguments')
        calls.extend([('baseline_paths', quoted_call('reconcile_baseline_paths', evidence.run_root, args[4], args[5])),
                      ('v125_runtime', quoted_call('remote_assert_v125_runtime', target, args[3], 'false')),
                      ('baseline_environment', 'remote_capture_compose_ids running backend\n'
                       'remote_assert_bound_container_environment "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" baseline'),
                      ('baseline_caddy', 'reconcile_baseline_caddy'),
                      ('public_live', 'remote_assert_public_live')])
    elif action == 'backup-rehearsal':
        phase = args[3]
        own = evidence.proof(phase + '-backup-proof', selected=True)
        require(own.get('phase') == phase and own.get('result') == 'PASS', 'original_backup_result')
        calls += backup_checks(evidence, release, run, phase, own, selected=True)
        if phase == 'quiesced':
            calls.extend([('public_drain', 'remote_assert_public_drain'),
                          ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0'))])
    elif action == 'final-v125-preflight':
        require(len(args) == 8 and re.fullmatch('[0-9a-f]{64}', args[6]), 'preflight_args')
        own = evidence.proof('final-v125-preflight', selected=True)
        require(own.get('script_sha256') == args[6] and own.get('preflight_outcome') == 'SAFE' and
                own.get('unsafe_count') == '0' and own.get('credentials_cleanup') == 'COMPLETE' and
                own.get('result') == 'PASS' and own.get('flyway') == '125:0:0',
                'historical_preflight_structured_witness_missing_or_invalid')
        backup = evidence.proof('quiesced-backup-proof')
        calls += backup_checks(evidence, release, run, 'quiesced', backup)
        calls.extend([('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0')),
                      ('preflight_retained_source_and_cleanup', quoted_call('reconcile_preflight_evidence', evidence.run_root, args[6])),
                      ('preflight_current_safe', quoted_call('reconcile_preflight_safe', evidence.run_root, args[4], args[6], args[7]))])
    elif action == 'verify-full-dr':
        require(len(args) == 9 and args[4] in ('pre-drain', 'quiesced') and
                all(re.fullmatch('[0-9a-f]{64}', value) for value in args[5:8]), 'full_dr_args')
        own = evidence.proof('recovery-full-dr-prerequisites', selected=True)
        phase = args[4]
        backup = evidence.proof(phase + '-backup-proof')
        require(backup.get('dump_sha256') == args[5] and backup.get('inventory_sha256') == args[6], 'full_dr_original_archive')
        predecessor = request['environment'].get('V126_INTERNAL_REMOTE_PREDECESSOR_STAGE')
        receipt_sha = request['environment'].get('V126_INTERNAL_REMOTE_PREDECESSOR_HASH') if predecessor == 'RECOVERY_POST_V126_STOP' else 'NONE'
        if predecessor == 'RECOVERY_POST_V126_STOP':
            evidence.proof('recovery-post-v126-stop')
            require(evidence.proofs['recovery-post-v126-stop'] == args[8], 'full_dr_original_stop')
            calls.append(('original_stop_contract', quoted_call('remote_verify_post_v126_stop_proof', evidence.run_root, run, release, args[8])))
        else:
            require(args[8] == 'NONE', 'full_dr_unexpected_stop')
        calls += backup_checks(evidence, release, run, phase, backup)
        calls.extend([('full_dr_original_contract', quoted_call('remote_verify_full_dr_proof',
                      evidence.run_root / 'recovery-full-dr-prerequisites.proof', run, release, phase,
                      *args[5:8], receipt_sha, args[8])),
                      ('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', 'ANY'))])
    return calls, expected_id


def backup_checks(evidence, release, run, phase, proof, *, selected=False):
    artifacts = evidence.selected if selected else evidence.artifacts
    for field, suffix in [('dump_sha256', 'dump'), ('inventory_sha256', 'inventory'), ('rehearsal_sha256', 'rehearsal')]:
        require(re.fullmatch('[0-9a-f]{64}', proof.get(field, '')) and
                proof[field] == artifacts.get(phase + '-backup-' + suffix), 'original_backup_' + field)
    cid, volume, owner = (proof.get(key, '') for key in ('rehearsal_container', 'rehearsal_volume', 'rehearsal_owner'))
    require(re.fullmatch('[0-9a-f]{64}', cid) and re.fullmatch('hookah-v126-[a-z0-9-]+', volume) and
            re.fullmatch('v126:' + release + r':[a-z0-9-]+:' + phase + r':[0-9]+', owner) and
            proof.get('rehearsal_cleanup') == 'COMPLETE', 'historical_backup_resource_witness_missing_or_invalid')
    globals_sha = artifacts.get('pre-drain-globals', 'NONE') if phase == 'pre-drain' else 'NONE'
    require(phase != 'pre-drain' or re.fullmatch('[0-9a-f]{64}', globals_sha), 'original_globals_binding')
    return [('backup_archive_' + phase, quoted_call('reconcile_backup_archive', release, run, phase,
             proof['dump_sha256'], proof['inventory_sha256'], proof['rehearsal_sha256'], globals_sha)),
            ('backup_resources_' + phase, quoted_call('reconcile_rehearsal_absent', cid, volume, owner))]


READ_ONLY_HELPERS = r'''
reconcile_exact_backend() {
  local expected="$1" image="$2" observed
  remote_capture_compose_ids running backend || return 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || return 4
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${expected}" ]] || return 4
  remote_capture_compose_ids all backend || return 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || return 4
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${expected}" ]] || return 4
  observed="$(docker inspect --format '{{.Image}}:{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${expected}")" || return 4
  [[ "${observed}" == "${image}:no:0" ]] || return 4
}
reconcile_stopped_backend() {
  remote_capture_compose_ids running backend || return 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) || return 4
  remote_require_global_image_count "${V125_IMAGE_ID}" 0 || return 4
  remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID}" 0 || return 4
  remote_compose exec -T postgres sh -c ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null
}
reconcile_ordinary_caddy() {
  local release="$1" run="$2" root observed
  local expected="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256}"
  if [[ "${expected}" == NONE ]]; then
    remote_verify_partial_caddy_evidence "${release}" "${run}" || return 4
    expected="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256}"
  else
    remote_verify_caddy_receipt_evidence "${release}" "${run}" || return 4
  fi
  root="$(remote_caddy_evidence_root "${release}" "${run}")" || return 4
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || return 4
  observed="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" || return 4
  [[ "${observed}" == "${expected}" ]] || return 4
  remote_assert_caddy_service_active || return 4
  remote_assert_caddy_config_active "${root}/Caddyfile.original" || return 4
}
reconcile_maintenance_cleanup() {
  local root="$1" mode="$2" path
  for path in "${root}/env.${mode}.candidate" "${root}/env.before-${mode}" "${root}/.env.next"; do
    [[ ! -e "${path}" && ! -L "${path}" ]] || return 4
  done
}
reconcile_loaded_image() (
  local root="$1" tag="$2" image="$3" archive_sha="$4" observed
  v126_reconcile_image_copy='' v126_reconcile_image_snapshot=''
  trap 'status=$?; trap - EXIT; if [[ -n "${v126_reconcile_image_copy:-}" ]]; then rm -f -- "${v126_reconcile_image_copy}" || status=4; fi; if [[ -n "${v126_reconcile_image_snapshot:-}" ]]; then rm -f -- "${v126_reconcile_image_snapshot}" || status=4; fi; exit "${status}"' EXIT
  remote_require_operator_file "${root}/v126-image.tar" 400 || exit 4
  [[ ! -e "${root}/v126-image.tar.partial" && ! -L "${root}/v126-image.tar.partial" ]] || exit 4
  [[ "$(remote_hash_file "${root}/v126-image.tar")" == "${archive_sha}" ]] || exit 4
  # The real saved-image verifier accepts only an unlinked private snapshot.
  # Preserve the retained sealed archive and use the source's actual snapshotter.
  v126_reconcile_image_copy="$(mktemp "${V126_RECONCILE_PRIVATE}/image-source.XXXXXX")" || exit 4
  v126_reconcile_image_snapshot="$(mktemp "${V126_RECONCILE_PRIVATE}/image-snapshot.XXXXXX")" || exit 4
  cat "${root}/v126-image.tar" > "${v126_reconcile_image_copy}" || exit 4
  chmod 0600 "${v126_reconcile_image_copy}" "${v126_reconcile_image_snapshot}" || exit 4
  snapshot_image_archive "${v126_reconcile_image_copy}" "${v126_reconcile_image_snapshot}" || exit 4
  exec 9<"${v126_reconcile_image_snapshot}" || exit 4
  rm -f -- "${v126_reconcile_image_snapshot}" "${v126_reconcile_image_copy}" || { exec 9<&-; exit 4; }
  observed="$(verify_saved_image_archive_fd 9 "${tag}" "${image}")" || { exec 9<&-; exit 4; }
  exec 9<&-
  [[ "${observed}" == "${archive_sha}" ]] || exit 4
  remote_require_operator_file "${root}/v126-image.tar" 400 || exit 4
  [[ "$(remote_hash_file "${root}/v126-image.tar")" == "${archive_sha}" ]] || exit 4
  observed="$(docker image inspect --format '{{.Id}}' "${tag}")" || exit 4
  [[ "${observed}" == "${image}" && "${image}" == "${V126_INTERNAL_REMOTE_V126_IMAGE_ID}" ]] || exit 4
  remote_assert_compose_backend_image "${tag}" || exit 4
)
reconcile_baseline_paths() {
  python3 - "$1/baseline-authority.proof" "$2" "$3" <<'PY'
from pathlib import Path
import sys
fields = dict(row.split('=', 1) for row in Path(sys.argv[1]).read_text().splitlines())
if fields.get('database_url_path') != sys.argv[2] or fields.get('maintenance_identities_path') != sys.argv[3]:
    raise SystemExit('original baseline path differs')
PY
}
reconcile_baseline_caddy() {
  local observed
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || return 4
  observed="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" || return 4
  [[ "${observed}" == "${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256}" ]] || return 4
  sudo test ! -e /etc/caddy/v126-drain.enabled || return 4
  sudo test ! -L /etc/caddy/v126-drain.enabled || return 4
  cutover_bounded_command 15 sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null || return 4
  remote_assert_caddy_service_active || return 4
  remote_assert_caddy_config_active /etc/caddy/Caddyfile || return 4
}
reconcile_rehearsal_absent() {
  local cid="$1" volume="$2" owner="$3" observed
  observed="$(docker container ls --all --no-trunc --filter "id=${cid}" --format '{{.ID}}')" || return 4
  [[ -z "${observed}" ]] || return 4
  observed="$(docker container ls --all --no-trunc --filter "label=hookah.v126.rehearsal-owner=${owner}" --format '{{.ID}}')" || return 4
  [[ -z "${observed}" ]] || return 4
  observed="$(docker volume ls --filter "name=${volume}" --format '{{.Name}}')" || return 4
  [[ -z "${observed}" ]] || return 4
  observed="$(docker volume ls --filter "label=hookah.v126.rehearsal-owner=${owner}" --format '{{.Name}}')" || return 4
  [[ -z "${observed}" ]] || return 4
}
reconcile_backup_archive() (
  local release="$1" run="$2" phase="$3" dump_sha="$4" inventory_sha="$5" metadata_sha="$6" globals_sha="$7"
  local root dump inventory metadata temporary observed code
  root="$(remote_backup_root "${release}" "${run}")" || exit 4
  [[ -d "${root}" && ! -L "${root}" ]] || exit 4
  [[ "$(stat -c '%a:%U:%G' "${root}")" == "700:$(id -un):$(id -gn)" ]] || exit 4
  dump="${root}/${phase}.dump"
  inventory="${dump}.pg_restore.list"
  metadata="${dump}.rehearsal.txt"
  remote_require_operator_file "${dump}" 600 || exit 4
  remote_require_operator_file "${inventory}" 600 || exit 4
  remote_require_operator_file "${metadata}" 600 || exit 4
  remote_require_operator_file "${dump}.sha256" 600 || exit 4
  [[ "$(remote_hash_file "${dump}")" == "${dump_sha}" ]] || exit 4
  [[ "$(remote_hash_file "${inventory}")" == "${inventory_sha}" ]] || exit 4
  [[ "$(remote_hash_file "${metadata}")" == "${metadata_sha}" ]] || exit 4
  [[ "$(cat "${dump}.sha256")" == "${dump_sha}  ${dump}" ]] || exit 4
  sha256sum -c "${dump}.sha256" >/dev/null || exit 4
  if [[ "${globals_sha}" != NONE ]]; then
    remote_require_operator_file "${root}/globals.sql" 600 || exit 4
    remote_require_operator_file "${root}/globals.sql.sha256" 600 || exit 4
    [[ "$(remote_hash_file "${root}/globals.sql")" == "${globals_sha}" ]] || exit 4
    [[ "$(cat "${root}/globals.sql.sha256")" == "${globals_sha}  ${root}/globals.sql" ]] || exit 4
    sha256sum -c "${root}/globals.sql.sha256" >/dev/null || exit 4
  fi
  remote_capture_compose_ids running postgres || exit 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || exit 4
  observed="$(docker inspect --format '{{.Image}}' "${REMOTE_CAPTURED_CONTAINER_IDS[0]}")" || exit 4
  python3 - "${metadata}" "${release}" "${run}" "${phase}" "${observed}" <<'PY'
from pathlib import Path
import re
import sys
path, release, run, phase, image = sys.argv[1:]
fields = {}
for row in Path(path).read_text().splitlines():
    key, value = row.split('=', 1)
    if key in fields: raise SystemExit('duplicate rehearsal metadata')
    fields[key] = value
for key, expected in dict(run_id=run, release_sha=release, phase=phase, source_image_id=image,
                          restored_flyway='125:0:0', rehearsal='PASS').items():
    if fields.get(key) != expected: raise SystemExit('original rehearsal metadata differs')
if not re.fullmatch(r'17[0-9]{4}', fields.get('source_version', '')):
    raise SystemExit('original rehearsal is not PostgreSQL17')
PY
  [[ "$?" == 0 ]] || exit 4
  temporary="${V126_RECONCILE_PRIVATE}/toc-${phase}"
  [[ ! -e "${temporary}" && ! -L "${temporary}" ]] || exit 4
  (set -o noclobber; umask 077; : > "${temporary}") || exit 4
  chmod 0600 "${temporary}" || exit 4
  remote_compose exec -T postgres sh -c ': "${POSTGRES_USER:?}"; pg_restore --list' < "${dump}" > "${temporary}" || exit 4
  code="$(remote_database_evidence_python)" || exit 4
  python3 -c "${code}" toc "${dump}" "${dump_sha}" "${inventory}" "${temporary}" || exit 4
  [[ "$(remote_hash_file "${inventory}")" == "${inventory_sha}" ]] || exit 4
  [[ "$(remote_hash_file "${metadata}")" == "${metadata_sha}" ]] || exit 4
)
reconcile_preflight_evidence() {
  local root="$1" expected="$2" path
  remote_require_operator_file "${root}/final-v125-preflight.sh" 500 || return 4
  [[ "$(remote_hash_file "${root}/final-v125-preflight.sh")" == "${expected}" ]] || return 4
  for path in final-v125-preflight.sh.partial final-v125-preflight.output final-v125-preflight.pg_service.conf final-v125-preflight.pgpass; do
    [[ ! -e "${root}/${path}" && ! -L "${root}/${path}" ]] || return 4
  done
}
reconcile_preflight_safe() {
  local root="$1" uri="$2" script_sha="$3" uri_sha="$4" temporary="${V126_RECONCILE_PRIVATE}"
  remote_assert_database_target "${uri}" "${uri_sha}" || return 4
  python3 "${temporary}/derive.py" "${uri}" "${uri_sha}" "${temporary}/pg_service.conf" "${temporary}/pgpass" || return 4
  python3 "${temporary}/execute.py" "${root}/final-v125-preflight.sh" "${script_sha}" "${temporary}/preflight.output" \
    "${temporary}/pg_service.conf" "${temporary}/pgpass" "${PATH}" "${HOME}" || return 4
  python3 -c "$(remote_database_evidence_python)" preflight "${temporary}/preflight.output" || return 4
}
'''


def preflight_sources(source_bytes):
    source = source_bytes.decode('utf-8')
    start = source.index('remote_final_v125_preflight() {\n')
    end = source.index('\n# Candidate bytes ', start)
    chunks = re.findall(r"<<'PY'\n(.*?)\nPY\n", source[start:end], re.S)
    derive = [chunk for chunk in chunks if chunk.startswith('# HT12X_LIBPQ_DERIVATION_BEGIN\n')]
    execute = [chunk for chunk in chunks if 'script_path, expected_sha, output_path, service_path, pass_path, path_value, home_value = sys.argv[1:]' in chunk]
    require(len(derive) == len(execute) == 1, 'source_bound_preflight_consumers_unavailable')
    return derive[0] + '\n', execute[0] + '\n'


def run_observer(source_bytes, request, evidence, calls, expected_id):
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR') if key in os.environ}
    env.update(request['environment'])
    env.update(evidence.derived_environment)
    env.update({'V126_INTERNAL_REMOTE_MODE': 'true',
                'V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED': 'V126_INTERNAL_REMOTE_ENVELOPE_V1'})
    with tempfile.TemporaryDirectory(prefix='v126-reconcile-read-') as temp:
        root = Path(temp)
        env['V126_RECONCILE_PRIVATE'] = str(root)
        env['TMPDIR'] = str(root)
        source, script = root / 'source.sh', root / 'observe.sh'
        source.write_bytes(source_bytes)
        if evidence.identity['action'] == 'final-v125-preflight':
            derive, execute = preflight_sources(source_bytes)
            for name, code in [('derive.py', derive), ('execute.py', execute)]:
                (root / name).write_text(code)
                (root / name).chmod(0o400)
        body = 'set -Eeuo pipefail\nsource "$1"\n' + READ_ONLY_HELPERS
        for name in evidence.proofs:
            body += quoted_call('remote_verify_proof', evidence.run_root / (PROOF_FILENAMES.get(name, name) + '.proof')) + '\n'
        for name, command in calls:
            body += "printf '%s\\n' 'V126_RECONCILE_CHECK=" + name + "'\n" + command + '\n'
            body += 'v126_reconcile_status=$?\n[[ "${v126_reconcile_status}" == 0 ]] || exit 4\n'
        body += '''remote_assert_database_target || exit 4
remote_capture_compose_ids running postgres || exit 4
(( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || exit 4
[[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" =~ ^[0-9a-f]{64}$ ]] || exit 4
printf 'V126_RECONCILE_POSTGRES=%s\\n' "${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
printf 'V126_RECONCILE_DATABASE=%s\\n' "${REMOTE_DATABASE_TARGET_IDENTITY_SHA256}"
printf 'V126_RECONCILE_ENVIRONMENT=%s\\n' "${REMOTE_BOUND_ENV_SHA256}"
remote_capture_compose_ids running backend || exit 4
(( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} <= 1 )) || exit 4
if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )); then
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" =~ ^[0-9a-f]{64}$ ]] || exit 4
  printf 'V126_RECONCILE_BACKEND=%s\\n' "${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
else
  printf '%s\\n' 'V126_RECONCILE_BACKEND=NONE'
fi
printf '%s\\n' 'V126_RECONCILE_COMPLETED=true'
'''
        script.write_text(body)
        source.chmod(0o400)
        script.chmod(0o400)
        started = time.monotonic()
        # The actual source supervisor bounds and reaps the read-only consumer
        # tree. The enclosing binding supervisor continues to hold the target lock.
        result = subprocess.run(['bash', '-c',
                                 'set -Eeuo pipefail; source "$1"; cutover_bounded_command "$3" bash "$2" "$1"',
                                 'v126-reconcile-read-only', str(source), str(script), str(DEADLINE_SECONDS)],
                                cwd=evidence.target, env=env, stdin=subprocess.DEVNULL, capture_output=True)
        elapsed = time.monotonic() - started
        require(result.returncode == 0, 'current_postconditions_exit_' + str(result.returncode) +
                '_stdout_' + digest(result.stdout) + '_stderr_' + digest(result.stderr))
        rows = {}
        observed_checks = []
        for line in result.stdout.splitlines():
            if not line.startswith(b'V126_RECONCILE_'):
                continue
            key, value = line.decode('ascii').split('=', 1)
            if key == 'V126_RECONCILE_CHECK':
                observed_checks.append(value)
            else:
                require(key not in rows, 'duplicate_observation')
                rows[key] = value
        require(observed_checks == [name for name, _ in calls] and set(rows) == {
            'V126_RECONCILE_POSTGRES', 'V126_RECONCILE_DATABASE',
            'V126_RECONCILE_ENVIRONMENT', 'V126_RECONCILE_BACKEND', 'V126_RECONCILE_COMPLETED'} and
            rows['V126_RECONCILE_COMPLETED'] == 'true' and
            all(re.fullmatch('[0-9a-f]{64}', rows[key]) for key in rows if not key.endswith(('COMPLETED', 'BACKEND'))) and
            (rows['V126_RECONCILE_BACKEND'] == 'NONE' or re.fullmatch('[0-9a-f]{64}', rows['V126_RECONCILE_BACKEND'])) and
            (expected_id is None or rows['V126_RECONCILE_BACKEND'] == expected_id),
            'structured_current_postconditions')
        return dict(checks=observed_checks, backend_container_id=(None if rows['V126_RECONCILE_BACKEND'] == 'NONE' else rows['V126_RECONCILE_BACKEND']),
                    postgres_container_id=rows['V126_RECONCILE_POSTGRES'],
                    database_identity_sha256=rows['V126_RECONCILE_DATABASE'],
                    environment_sha256=rows['V126_RECONCILE_ENVIRONMENT'],
                    stdout_sha256=digest(result.stdout), stderr_sha256=digest(result.stderr),
                    observer_sha256=digest(body.encode()), exit=0, elapsed_seconds=round(elapsed, 6),
                    deadline_seconds=DEADLINE_SECONDS)


def collect(target, identity, source_bytes, request, operations):
    """Called only while binding_reconcile holds the canonical persistent lock."""
    target = Path(target)
    require(target.is_absolute() and target.resolve(strict=True) == target, 'target_not_canonical')
    require(isinstance(source_bytes, bytes) and digest(source_bytes) == identity.get('script_sha256'), 'source_binding')
    validate_request(target, identity, request)
    action = identity.get('action')
    require(action in ACTIONS, 'unknown_action')
    require(action in IMPLEMENTED, 'unsupported_action_' + action + '_' + UNSUPPORTED_REASONS.get(action, 'unknown'))
    evidence = Evidence(target, identity, request, operations)
    calls, expected_id = observer_plan(evidence, request)
    observed = run_observer(source_bytes, request, evidence, calls, expected_id)
    # Re-read the bound files after observation; no proof/record may drift while
    # the observer runs, even if its current resource checks happened to succeed.
    after = Evidence(target, identity, request, operations)
    for name, original_sha in evidence.proofs.items():
        after.proof(name)
        require(after.proofs[name] == original_sha, 'proof_changed_during_observation')
    return dict(format_version=1, action=action, outcome='EXACT_COMPLETED_EFFECT', retry_allowed=False,
                source_sha256=digest(source_bytes), request_sha256=digest(canonical(request)),
                original_artifacts=dict(sorted(evidence.proofs.items())),
                derived_original_artifact_bindings=dict(sorted(evidence.derived_environment.items())), observation=observed,
                observed_at=datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
V126_RECONCILIATION_PY
}

remote_operation_bindings_python() {
  cat <<'PY'
#!/usr/bin/env python3
"""Source-bound target binding history; no SSH or daemon mutation.

The sequencer embeds these same bytes. Retirement only appends a protected transfer
record after the caller verifies its real terminal receipt and approved handoff.
Unknown daemon outcomes cannot be retired by this protocol.
"""
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys


class BindingError(ValueError):
    pass


def binding_action_sequence(kind, name):
    stages = {
        'BASELINE_VERIFIED': ['baseline'],
        'PRE_DRAIN_BACKUP_REHEARSED': ['backup-rehearsal'],
        'CADDY_CANDIDATE_INSTALLED_AND_RELOADED': ['caddy-activate'],
        'PUBLIC_DRAIN_ACTIVE': ['public-drain-on'],
        'V125_BACKEND_STOPPED': ['stop-backend'],
        'ZERO_WRITER_GATE_PASSED': ['zero-writer'],
        'QUIESCED_BACKUP_REHEARSED': ['backup-rehearsal'],
        'FINAL_V125_PREFLIGHT_PASSED': ['preflight-upload', 'final-v125-preflight'],
        'V126_MAINTENANCE_CONFIG_PREPARED': ['transform-maintenance'],
        'V126_IMAGE_TRANSFERRED_AND_VERIFIED': ['image-prepare', 'image-upload', 'image-load'],
        'V126_BACKEND_STARTED': ['start-v126'],
        'V126_SCHEMA_RUNTIME_GATE_PASSED': ['schema-runtime-gate'],
        'MANUAL_SMOKE_AUTHORIZED': ['open-manual-smoke'],
        'MANUAL_SMOKE_PASSED': ['record-manual-smoke'],
        'PUBLIC_DRAIN_REACTIVATED': ['public-drain-on'],
        'V126_BACKEND_STOPPED_FOR_OFF_TRANSITION': ['stop-backend'],
        'MAINTENANCE_OFF_CONFIG_VERIFIED': ['transform-maintenance'],
        'FINAL_V126_BACKEND_STARTED': ['start-v126'],
        'ORDINARY_CADDY_RESTORED': ['restore-caddy'],
        'FINAL_PUBLIC_GATES_PASSED': ['final-public-gates'],
    }
    recovery = {'pre-v126': ['recover-pre-v126'], 'post-v126-stop': ['recover-post-v126-stop'],
                'verify-full-dr': ['verify-full-dr']}
    selected = stages if kind == 'STAGE' else recovery if kind == 'RECOVERY' else {}
    if name not in selected:
        raise BindingError('reconciliation_action_class')
    return selected[name]


def binding_canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def binding_protected(path, mode, directory=False):
    info = path.lstat()
    if (not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)) or
            info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != mode or
            (not directory and info.st_nlink != 1)):
        raise BindingError('record_metadata')


def binding_read(path):
    binding_protected(path, 0o400)
    raw = path.read_bytes()
    value = json.loads(raw)
    if raw != binding_canonical(value):
        raise BindingError('record_not_canonical')
    return value


def binding_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def binding_owner(value):
    if (not isinstance(value, dict) or set(value) != {'run_id', 'release_sha', 'script_sha256'} or
            not re.fullmatch(r'[a-z0-9][a-z0-9._-]{5,63}', value['run_id']) or
            not re.fullmatch(r'[0-9a-f]{40}', value['release_sha']) or
            not re.fullmatch(r'[0-9a-f]{64}', value['script_sha256'])):
        raise BindingError('owner_schema')
    return value


def binding_owner_id(owner):
    return hashlib.sha256(binding_canonical(binding_owner(owner))).hexdigest()


def binding_inventory(root, owner):
    files = {}
    unknown = False
    identities = []
    for start in sorted(root.glob('*.start.json')):
        doc = binding_read(start)
        identity = doc.get('identity', {})
        if {key: identity.get(key) for key in owner} != owner:
            continue
        op = start.name.removesuffix('.start.json')
        if (set(doc) != {'identity', 'operation_id', 'started_at', 'boot_id'} or
                set(identity) != {'run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'} or
                doc['operation_id'] != op or hashlib.sha256(binding_canonical(identity)).hexdigest() != op):
            raise BindingError('start_binding')
        identities.append(identity)
        files[start.name] = binding_hash(start)
        request = root / (op + '.request.json')
        if request.exists() or request.is_symlink():
            binding_request(request, identity, root.parent)
            files[request.name] = binding_hash(request)
        result = root / (op + '.result.json')
        log = root / (op + '.log')
        if not result.exists():
            unknown = True
            continue
        outcome = binding_read(result)
        if (set(outcome) != {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} or
                outcome['identity'] != identity or outcome['operation_id'] != op or
                type(outcome['exit']) is not int or not 0 <= outcome['exit'] <= 255 or
                outcome['outcome'] != ('SUCCEEDED' if outcome['exit'] == 0 else 'UNKNOWN') or
                outcome['children'] != 'REAPED'):
            raise BindingError('result_binding')
        binding_protected(log, 0o400)
        if binding_hash(log) != outcome['log_sha256']:
            raise BindingError('log_binding')
        files[result.name] = binding_hash(result)
        files[log.name] = outcome['log_sha256']
        if identity['kind'] == 'DEPLOY' and outcome['exit'] == 0:
            proof = root / (op + '.deploy-proof.json')
            binding_deploy_proof(proof, identity, root.parent)
            files[proof.name] = binding_hash(proof)
        unknown = unknown or outcome['exit'] != 0
    for name, digest in binding_reconciliation_inventory(root, root.parent).items():
        identity = binding_read(root / name)['identity']
        if {key: identity.get(key) for key in owner} == owner:
            files[name] = digest
    return files, unknown, identities


def binding_handoff(doc, owner, next_owner, receipt_sha, target):
    keys = {'format_version', 'owner', 'next_owner', 'terminal_receipt_sha256', 'target_sha256',
            'operational_version', 'backend_image', 'image_id', 'environment_sha256',
            'compose_sha256', 'caddy_runtime_sha256', 'config_owner', 'restart_policy',
            'handoff_approved_and_applied', 'approval_id', 'observed_at'}
    if (not isinstance(doc, dict) or set(doc) != keys or type(doc['format_version']) is not int or
            doc['format_version'] != 1 or doc['owner'] != owner or doc['next_owner'] != next_owner or
            doc['terminal_receipt_sha256'] != receipt_sha or
            doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
            doc['operational_version'] not in ('V125', 'V126') or
            doc['config_owner'] != 'root:root' or doc['restart_policy'] != 'unless-stopped' or
            doc['handoff_approved_and_applied'] is not True or
            not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{5,127}', doc['approval_id'])):
        raise BindingError('handoff_contract')
    for key in ('terminal_receipt_sha256', 'environment_sha256', 'compose_sha256', 'caddy_runtime_sha256'):
        if not re.fullmatch(r'[0-9a-f]{64}', doc[key]):
            raise BindingError('handoff_digest')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', doc['image_id']):
        raise BindingError('handoff_image')
    release = owner['release_sha'] if doc['operational_version'] == 'V126' else 'f577934691a1a7a79ba327c54e2055425142b7be'
    if not re.fullmatch(r'[a-z0-9][a-z0-9._/-]*:' + release, doc['backend_image']):
        raise BindingError('handoff_image_source')
    datetime.datetime.strptime(doc['observed_at'], '%Y-%m-%dT%H:%M:%SZ')


def binding_chain(root, target):
    owner = binding_owner(binding_read(root / 'run.json'))
    owners = [owner]
    directory = root / 'transfers'
    if not directory.exists() and not directory.is_symlink():
        return owner, owners
    binding_protected(directory, 0o700, True)
    names = set(path.name for path in directory.iterdir())
    consumed = set()
    while binding_owner_id(owner) + '.json' in names:
        name = binding_owner_id(owner) + '.json'
        if name in consumed:
            raise BindingError('binding_cycle')
        transfer = binding_read(directory / name)
        version = transfer.get('format_version')
        keys = {'format_version', 'previous_owner', 'next_owner', 'inventory', 'handoff'}
        if version == 2:
            keys |= {'next_kind', 'request_sha256', 'terminal_kind'}
        if (type(version) is not int or version not in (1, 2) or set(transfer) != keys or
                transfer['previous_owner'] != owner):
            raise BindingError('transfer_schema')
        if version == 2 and (transfer['next_kind'] not in ('ORDINARY_DEPLOY', 'CUTOVER') or
                (transfer['next_kind'] == 'ORDINARY_DEPLOY' and not re.fullmatch('[0-9a-f]{64}', transfer['request_sha256'])) or
                (transfer['next_kind'] == 'CUTOVER' and transfer['request_sha256'] is not None) or
                transfer['terminal_kind'] not in ('NATIVE_RECEIPT', 'RECONCILED_EFFECT', 'ORDINARY_DEPLOY_PROOF')):
            raise BindingError('transfer_policy')
        next_owner = binding_owner(transfer['next_owner'])
        if any(previous['run_id'] == next_owner['run_id'] for previous in owners):
            raise BindingError('run_id_reuse')
        inventory, unknown, identities = binding_inventory(root, owner)
        if unknown or not inventory or transfer['inventory'] != inventory:
            raise BindingError('retired_outcome_not_proven')
        handoff = transfer['handoff']
        binding_handoff(handoff, owner, next_owner, handoff['terminal_receipt_sha256'], target)
        ordinary = version == 2 and transfer['terminal_kind'] == 'ORDINARY_DEPLOY_PROOF'
        required = ('DEPLOY', 'ORDINARY_DEPLOY') if ordinary else (('STAGE', 'FINAL_PUBLIC_GATES_PASSED') if handoff['operational_version'] == 'V126' else ('RECOVERY', 'pre-v126'))
        if not any((identity['kind'], identity['name']) == required for identity in identities):
            raise BindingError('terminal_remote_operation_missing')
        if ordinary:
            matches = [identity for identity in identities if identity['kind'] == 'DEPLOY']
            if len(matches) != 1:
                raise BindingError('ordinary_terminal_operation_count')
            op = hashlib.sha256(binding_canonical(matches[0])).hexdigest()
            proof_path = root / (op + '.deploy-proof.json')
            proof = binding_deploy_proof(proof_path, matches[0], target)
            binding_deploy_handoff(proof, binding_hash(proof_path), handoff)
        consumed.add(name)
        owner = next_owner
        owners.append(owner)
    if consumed != names:
        raise BindingError('unlinked_transfer_record')
    return owner, owners


def binding_entry(mode):
    # Called only from the sequencer CLI after its source/receipt checks.
    target = Path(sys.argv[1])
    if not target.is_absolute() or target.resolve(strict=True) != target:
        raise BindingError('target_not_canonical')
    info = target.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise BindingError('target_ownership')
    root = target / '.v126-target-operations'
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    fd = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        current, owners = binding_chain(root, target)
        inventory, unknown, identities = binding_inventory(root, current)
        allowed = {'lock', 'run.json'} | ({'transfers'} if (root / 'transfers').exists() else set())
        for owner in owners:
            files, old_unknown, _ = binding_inventory(root, owner)
            allowed.update(name.split('/')[0] for name in files)
            # A missing result may have a private active/partial log: inspection is
            # conservative; it never adopts or removes such evidence.
            for name in tuple(files):
                if name.endswith('.start.json'):
                    log = name.removesuffix('.start.json') + '.log'
                    if (root / log).exists(): allowed.add(log)
        if set(p.name for p in root.iterdir()) != allowed:
            raise BindingError('unexpected_target_records')
        if mode == 'inspect':
            reconciled = sum(name.startswith('reconciliations/') for name in inventory)
            print(json.dumps(dict(owner=current, history_count=len(owners),
                  outcome='UNKNOWN' if unknown else 'COMMAND_RESULTS_VERIFIED',
                  reconciled_completions=reconciled,
                  next_action='EXTERNAL_DAEMON_FENCING_DECISION_REQUIRED' if unknown else 'VERIFY_TERMINAL_RECEIPT_AND_APPROVED_HANDOFF',
                  retry_allowed=False), sort_keys=True))
            return
        owner = dict(zip(('run_id', 'release_sha', 'script_sha256'), sys.argv[2:5]))
        receipt_sha, handoff_path = sys.argv[5:7]
        next_owner = dict(zip(('run_id', 'release_sha', 'script_sha256'), sys.argv[7:10]))
        binding_owner(next_owner)
        if current != owner or unknown or not inventory:
            raise BindingError('retirement_requires_known_completed_current_run')
        handoff = binding_read(Path(handoff_path))
        binding_handoff(handoff, owner, next_owner, receipt_sha, target)
        if handoff['image_id'] != sys.argv[11]:
            raise BindingError('terminal_handoff_image_mismatch')
        if handoff['operational_version'] != sys.argv[10]:
            raise BindingError('terminal_handoff_version_mismatch')
        required = ('STAGE', 'FINAL_PUBLIC_GATES_PASSED') if handoff['operational_version'] == 'V126' else ('RECOVERY', 'pre-v126')
        if not any((identity['kind'], identity['name']) == required for identity in identities):
            raise BindingError('terminal_remote_operation_missing')
        if any(previous['run_id'] == next_owner['run_id'] for previous in owners):
            raise BindingError('run_id_reuse')
        directory = root / 'transfers'
        if not directory.exists():
            directory.mkdir(mode=0o700)
        binding_protected(directory, 0o700, True)
        transfer = dict(format_version=1, previous_owner=owner, next_owner=next_owner,
                        inventory=inventory, handoff=handoff)
        if len(sys.argv) > 13:
            next_kind, request_path, terminal_kind = sys.argv[12:15]
            if next_kind not in ('ORDINARY_DEPLOY', 'CUTOVER') or terminal_kind not in ('NATIVE_RECEIPT', 'RECONCILED_EFFECT'):
                raise BindingError('next_binding_policy')
            request_sha = None
            if next_kind == 'ORDINARY_DEPLOY':
                request = binding_read(Path(request_path))
                binding_next_request(request, next_owner, handoff, target)
                request_sha = binding_hash(Path(request_path))
            elif request_path != 'NONE':
                raise BindingError('cutover_transfer_has_no_deploy_request')
            transfer.update(format_version=2, next_kind=next_kind,
                            request_sha256=request_sha, terminal_kind=terminal_kind)
        recordfd = os.open(directory / (binding_owner_id(owner) + '.json'),
                           os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o400)
        with os.fdopen(recordfd, 'wb') as handle:
            handle.write(binding_canonical(transfer)); handle.flush(); os.fsync(handle.fileno())
        for path in (directory, root):
            syncfd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(syncfd)
            finally: os.close(syncfd)
        print('TARGET_BINDING_RETIRED history_preserved=true ' +
              ('next_request_only=true' if transfer.get('next_kind') == 'ORDINARY_DEPLOY' else 'next_baseline_only=true'))
    finally:
        os.close(fd)


def binding_refuse(reason):
    raise BindingError(reason)


def binding_sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def binding_create(path, value):
    binding_create_raw(path, binding_canonical(value))


def binding_create_raw(path, raw):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o400)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(raw); handle.flush(); os.fsync(handle.fileno())
    binding_sync_dir(path.parent)


def binding_active_policy(root, target):
    _, owners = binding_chain(root, target)
    if len(owners) == 1:
        return dict(next_kind='CUTOVER', request_sha256=None)
    transfer = binding_read(root / 'transfers' / (binding_owner_id(owners[-2]) + '.json'))
    return dict(next_kind=transfer.get('next_kind', 'CUTOVER'), request_sha256=transfer.get('request_sha256'))


def binding_deploy_proof(path, identity, target):
    doc = binding_read(path)
    keys = {'format_version', 'identity', 'target_sha256', 'request_sha256',
            'environment_sha256', 'compose_sha256', 'image_id', 'backend_container_id',
            'database_identity_sha256', 'caddy_runtime_sha256', 'result', 'completed_at'}
    if (set(doc) != keys or type(doc['format_version']) is not int or doc['format_version'] != 1 or doc['identity'] != identity or
            doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
            doc['request_sha256'] != identity['intent_sha256'] or
            doc['result'] != 'ORDINARY_DEPLOY_COMPLETED'):
        raise BindingError('ordinary_deploy_proof')
    for key in ('request_sha256', 'environment_sha256', 'compose_sha256', 'backend_container_id',
                'database_identity_sha256', 'caddy_runtime_sha256'):
        if not re.fullmatch('[0-9a-f]{64}', doc[key]):
            raise BindingError('ordinary_deploy_proof_digest')
    if not re.fullmatch('sha256:[0-9a-f]{64}', doc['image_id']):
        raise BindingError('ordinary_deploy_proof_image')
    datetime.datetime.strptime(doc['completed_at'], '%Y-%m-%dT%H:%M:%SZ')
    return doc


def binding_next_request(request, next_owner, handoff, target):
    # Full descriptor/source/file validation belongs to the single deploy consumer.
    # A transfer binds its exact bytes; these joins preserve the applied authority.
    if (request.get('owner') != next_owner or
            request.get('target_sha256') != hashlib.sha256(str(target).encode()).hexdigest()):
        raise BindingError('next_request_binding')
    for key in ('operational_version', 'backend_image', 'image_id', 'environment_sha256',
                'caddy_runtime_sha256', 'config_owner', 'restart_policy',
                'handoff_approved_and_applied'):
        if request.get(key) != handoff[key]:
            raise BindingError('next_request_handoff_' + key)
    if request.get('compose_before_sha256') != handoff['compose_sha256']:
        raise BindingError('next_request_previous_compose')


def binding_deploy_handoff(proof, digest, handoff):
    if digest != handoff['terminal_receipt_sha256']:
        raise BindingError('ordinary_terminal_proof_binding')
    for key in ('image_id', 'environment_sha256', 'compose_sha256', 'caddy_runtime_sha256'):
        if proof[key] != handoff[key]:
            raise BindingError('ordinary_terminal_handoff_' + key)


def binding_request(path, identity, target):
    doc = binding_read(path)
    if (set(doc) != {'format_version', 'identity', 'target_sha256', 'args', 'environment'} or
            type(doc['format_version']) is not int or doc['format_version'] != 1 or
            doc['identity'] != identity or
            doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
            not isinstance(doc['args'], list) or not 3 <= len(doc['args']) <= 32 or
            any(not isinstance(value, str) or '\x00' in value or '\n' in value for value in doc['args']) or
            doc['args'][:3] != [str(target), identity['run_id'], identity['release_sha']] or
            not isinstance(doc['environment'], dict) or
            any(not key.startswith('V126_INTERNAL_REMOTE_') or not isinstance(value, str)
                for key, value in doc['environment'].items())):
        raise BindingError('original_request_contract')
    return doc


def binding_operation_evidence(root, operation_id, target):
    if not re.fullmatch('[0-9a-f]{64}', operation_id):
        raise BindingError('operation_id_schema')
    start_path = root / (operation_id + '.start.json')
    start = binding_read(start_path)
    identity = start['identity']
    if (set(start) != {'identity', 'operation_id', 'started_at', 'boot_id'} or
            start['operation_id'] != operation_id or
            hashlib.sha256(binding_canonical(identity)).hexdigest() != operation_id):
        raise BindingError('original_start_binding')
    request_path = root / (operation_id + '.request.json')
    request = binding_request(request_path, identity, target)
    result_path = root / (operation_id + '.result.json')
    result = binding_read(result_path)
    if (set(result) != {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} or
            result['identity'] != identity or result['operation_id'] != operation_id or
            type(result['exit']) is not int or result['exit'] != 0 or result['outcome'] != 'SUCCEEDED' or
            result['children'] != 'REAPED'):
        raise BindingError('UNKNOWN_external_daemon_fencing_required')
    log = root / (operation_id + '.log')
    binding_protected(log, 0o400)
    if binding_hash(log) != result['log_sha256']:
        raise BindingError('original_log_binding')
    files = {path.name: binding_hash(path) for path in (start_path, request_path, result_path, log)}
    return identity, files, request


def binding_reconciliation_inventory(root, target):
    directory = root / 'reconciliations'
    if not directory.exists() and not directory.is_symlink():
        return {}
    binding_protected(directory, 0o700, True)
    inventory = {}
    for path in sorted(directory.iterdir()):
        doc = binding_read(path)
        keys = {'format_version', 'kind', 'identity', 'operation_id', 'target_sha256',
                'operations', 'checker_sha256', 'poststate', 'observed_at', 'retry_allowed'}
        if (set(doc) != keys or type(doc['format_version']) is not int or doc['format_version'] != 1 or
                doc['kind'] != 'RECONCILED_EFFECT' or doc['retry_allowed'] is not False or
                doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
                path.name != doc['operation_id'] + '.json' or
                not re.fullmatch('[0-9a-f]{64}', doc['checker_sha256']) or
                not isinstance(doc['poststate'], dict) or doc['poststate'].get('outcome') != 'EXACT_COMPLETED_EFFECT' or
                not isinstance(doc['operations'], list) or not doc['operations']):
            raise BindingError('reconciliation_schema')
        datetime.datetime.strptime(doc['observed_at'], '%Y-%m-%dT%H:%M:%SZ')
        ids = []
        for operation in doc['operations']:
            if set(operation) != {'operation_id', 'files'}:
                raise BindingError('reconciliation_operation_schema')
            identity, files, _ = binding_operation_evidence(root, operation['operation_id'], target)
            if (operation['files'] != files or any(identity[key] != doc['identity'][key]
                    for key in ('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name'))):
                raise BindingError('reconciliation_original_binding')
            ids.append(operation['operation_id'])
        if len(ids) != len(set(ids)) or ids[-1] != doc['operation_id'] or identity != doc['identity']:
            raise BindingError('reconciliation_operation_order')
        inventory['reconciliations/' + path.name] = binding_hash(path)
    return inventory


def binding_reconcile(target, owner, kind, name, intent_sha, source_sha, checker_sha, actions, observe):
    """Append exact-effect evidence after lost ACK. Never dispatch an action again.

    observe is the source-bound read-only action checker supplied by the sequencer,
    not an operator attestation. Nonzero/missing durable results stay UNKNOWN even
    if current state resembles the desired state.
    """
    target = Path(target)
    if not target.is_absolute() or target.resolve(strict=True) != target:
        raise BindingError('target_not_canonical')
    if source_sha != owner['script_sha256'] or not re.fullmatch('[0-9a-f]{64}', checker_sha):
        raise BindingError('reconciliation_source_binding')
    root = target / '.v126-target-operations'
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    fd = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        current, owners = binding_chain(root, target)
        _, unknown, identities = binding_inventory(root, current)
        if current != owner or unknown:
            raise BindingError('UNKNOWN_external_daemon_fencing_required')
        original = binding_reconciliation_inventory(root, target)
        allowed = {'run.json', 'lock'} | ({'transfers'} if (root / 'transfers').exists() else set())
        for previous in owners:
            allowed.update(path.split('/')[0] for path in binding_inventory(root, previous)[0])
        if set(path.name for path in root.iterdir()) != allowed:
            raise BindingError('unexpected_target_records')
        selected = [identity for identity in identities if
                    (identity['kind'], identity['name'], identity['intent_sha256']) == (kind, name, intent_sha)]
        by_action = {identity['action']: identity for identity in selected}
        if len(by_action) != len(selected) or set(by_action) != set(actions) or not actions:
            raise BindingError('original_action_sequence_incomplete')
        operations = []
        requests = []
        for action in actions:
            identity = by_action[action]
            op = hashlib.sha256(binding_canonical(identity)).hexdigest()
            _, files, request = binding_operation_evidence(root, op, target)
            operations.append(dict(operation_id=op, files=files))
            requests.append(request)
        directory = root / 'reconciliations'
        path = directory / (op + '.json')
        if path.exists() or path.is_symlink():
            # Explicit readback of a prior immutable reconciliation is not replay.
            if 'reconciliations/' + path.name not in original:
                raise BindingError('reconciliation_invalid')
            return binding_read(path)
        poststate = observe(identity, requests[-1], operations)
        if not isinstance(poststate, dict) or poststate.get('outcome') != 'EXACT_COMPLETED_EFFECT':
            raise BindingError('UNKNOWN_postconditions_insufficient')
        record = dict(format_version=1, kind='RECONCILED_EFFECT', identity=identity,
                      operation_id=op, target_sha256=hashlib.sha256(str(target).encode()).hexdigest(),
                      operations=operations, checker_sha256=checker_sha, poststate=poststate,
                      observed_at=datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                      retry_allowed=False)
        if not directory.exists():
            directory.mkdir(mode=0o700)
            binding_sync_dir(root)
        binding_protected(directory, 0o700, True)
        binding_create(path, record)
        binding_reconciliation_inventory(root, target)
        return record
    finally:
        os.close(fd)


def binding_export_reconciliation(root, record):
    import base64
    names = {name for operation in record['operations'] for name in operation['files']}
    names.add('reconciliations/' + record['operation_id'] + '.json')
    return dict(format_version=1, files={name: base64.b64encode((root / name).read_bytes()).decode('ascii')
                                       for name in sorted(names)})


def binding_reconciliation_log(original, remote_logs):
    artifacts = {}
    for raw in [original, *remote_logs]:
        seen = set()
        for line in raw.splitlines():
            if not line.startswith(b'ARTIFACT'):
                continue
            match = re.fullmatch(rb'ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t([0-9a-f]{64})', line)
            if not match:
                raise BindingError('original_artifact_encoding')
            name, digest = (value.decode('ascii') for value in match.groups())
            if name == 'operation-log' or name in seen or (name in artifacts and artifacts[name] != digest):
                raise BindingError('original_artifact_conflict')
            seen.add(name); artifacts[name] = digest
    return b'RECONCILIATION_ARTIFACT_INVENTORY_FROM_ORIGINAL_RECORDS\n' + b''.join(
        ('ARTIFACT\t' + name + '\t' + digest + '\n').encode('ascii') for name, digest in sorted(artifacts.items()))


def binding_original_stage_log(directory, index, stage):
    paths = [Path(directory) / f'{index}-{stage}.{suffix}.log' for suffix in ('operation', 'failed')]
    present = [path for path in paths if path.exists() or path.is_symlink()]
    if len(present) != 1:
        raise BindingError('original_local_operation_log_ambiguous_or_missing')
    binding_protected(present[0], 0o400)
    return present[0]


def binding_validate_dr_boundary(raw, run_id, release_sha, phase):
    doc = json.loads(raw)
    keys = {'accepted_data_loss_boundary', 'accepted_recovery_point_utc', 'backup_phase',
            'format_version', 'release_sha', 'result_category', 'run_id'}
    if (set(doc) != keys or type(doc['format_version']) is not int or doc['format_version'] != 1 or
            raw != binding_canonical(doc) or phase not in ('pre-drain', 'quiesced') or
            doc['run_id'] != run_id or doc['release_sha'] != release_sha or
            doc['backup_phase'] != phase or doc['result_category'] != 'DR_PREREQUISITES_ACCEPTED'):
        raise BindingError('DR boundary evidence schema or identity mismatch')
    expected = ('ALL_WRITES_AFTER_PRE_DRAIN_BACKUP_MAY_BE_LOST' if phase == 'pre-drain'
                else 'ALL_WRITES_AFTER_QUIESCED_BACKUP_MAY_BE_LOST')
    if doc['accepted_data_loss_boundary'] != expected:
        raise BindingError('DR data-loss boundary does not match selected backup')
    datetime.datetime.strptime(doc['accepted_recovery_point_utc'], '%Y-%m-%dT%H:%M:%SZ')
    return doc


def binding_retained_local_artifacts(state, manifest, artifacts, bundle_path=None):
    for name in ('manual-smoke-handoff', 'manual-smoke-evidence'):
        if name not in artifacts:
            continue
        path = Path(state) / 'artifacts' / (name + '.json')
        doc = binding_read(path)
        if (binding_hash(path) != artifacts[name] or
                doc.get('run_id') != manifest['run_id'] or doc.get('release_sha') != manifest['release_sha']):
            raise BindingError('original_local_manual_evidence_missing_or_changed')
    if 'dr-boundary' in artifacts:
        import base64
        path = Path(state) / 'recovery/dr-boundary.json'
        binding_protected(path, 0o400)
        if binding_hash(path) != artifacts['dr-boundary'] or bundle_path is None:
            raise BindingError('original_local_DR_boundary_missing_or_changed')
        bundle = binding_read(Path(bundle_path))
        requests = [json.loads(base64.b64decode(raw, validate=True)) for name, raw in bundle['files'].items()
                    if name.endswith('.request.json')]
        if len(requests) != 1 or requests[0]['identity']['action'] != 'verify-full-dr':
            raise BindingError('original_DR_request_missing')
        args = requests[0]['args']
        if (len(args) != 9 or args[5:8] != [artifacts['dr-selected-backup'], artifacts['dr-selected-inventory'], artifacts['dr-boundary']]):
            raise BindingError('original_DR_backup_boundary_binding')
        binding_validate_dr_boundary(path.read_bytes(), manifest['run_id'], manifest['release_sha'], args[4])
    # Baseline's local proof is source-derived and main-actions is separately
    # validated by the canonical CI consumer. Image/source uploads retain their
    # identical bytes remotely; the action observer verifies those sealed files.


def binding_embedded_source(source, name):
    delimiter = 'V126_RECONCILIATION_PY' if name == 'remote_reconciliation_poststate_python' else 'PY'
    marker = (name + "() {\n  cat <<'" + delimiter + "'\n").encode('ascii')
    if source.count(marker) != 1:
        raise BindingError('embedded_source_marker')
    body = source.split(marker, 1)[1].split(('\n' + delimiter + '\n}').encode('ascii'), 1)[0] + b'\n'
    return body


def binding_verify_reconciliation_bundle(path, target, source_sha, kind, name, intent_sha, checker_sha):
    """Validate transferred immutable evidence using the same record validators.

    Temporary files are exact private copies for validation, never target records.
    A bundle cannot authorize a mutation replay or synthesize a native receipt.
    """
    import base64
    import tempfile
    bundle = binding_read(Path(path))
    if (set(bundle) != {'format_version', 'files'} or type(bundle['format_version']) is not int or
            bundle['format_version'] != 1 or not isinstance(bundle['files'], dict) or
            not 5 <= len(bundle['files']) <= 13):
        raise BindingError('reconciliation_bundle_schema')
    with tempfile.TemporaryDirectory(prefix='v126-evidence-check-') as directory:
        root = Path(directory)
        (root / 'reconciliations').mkdir(mode=0o700)
        total = 0
        for filename, encoded in bundle['files'].items():
            if not re.fullmatch(r'(?:[0-9a-f]{64}\.(?:start|request|result)\.json|[0-9a-f]{64}\.log|reconciliations/[0-9a-f]{64}\.json)', filename):
                raise BindingError('reconciliation_bundle_filename')
            raw = base64.b64decode(encoded, validate=True)
            total += len(raw)
            if total > 16 * 1024**2:
                raise BindingError('reconciliation_bundle_size')
            file = root / filename
            file.write_bytes(raw); file.chmod(0o400)
        inventory = binding_reconciliation_inventory(root, Path(target))
        if len(inventory) != 1:
            raise BindingError('reconciliation_bundle_record_count')
        record_name = next(iter(inventory))
        record = binding_read(root / record_name)
        identity = record['identity']
        if (identity['script_sha256'] != source_sha or identity['kind'] != kind or
                identity['name'] != name or identity['intent_sha256'] != intent_sha or
                record['checker_sha256'] != checker_sha):
            raise BindingError('reconciliation_bundle_identity')
        expected = {filename for operation in record['operations'] for filename in operation['files']}
        if set(bundle['files']) != expected | {record_name}:
            raise BindingError('reconciliation_bundle_inventory')
        actual_actions = [binding_read(root / (operation['operation_id'] + '.start.json'))['identity']['action']
                          for operation in record['operations']]
        if actual_actions != binding_action_sequence(kind, name):
            raise BindingError('reconciliation_action_sequence')
        logs = [(root / (operation['operation_id'] + '.log')).read_bytes() for operation in record['operations']]
        return record, logs


def binding_write_completion(state, kind, name, index, bundle_path, source_path, expected_spec):
    state = Path(state)
    source = Path(source_path).read_bytes()
    source_sha = hashlib.sha256(source).hexdigest()
    manifest = binding_read(state / 'run.json')
    if manifest['script_sha256'] != source_sha:
        raise BindingError('local_completion_source_identity')
    stage = kind == 'STAGE'
    if kind not in ('STAGE', 'RECOVERY'):
        raise BindingError('local_completion_kind')
    prefix = f'{index}-{name}' if stage else 'recovery-' + name
    base = state / ('receipts' if stage else 'recovery') / (f'{index:02d}-{name}' if stage else name)
    intent_path = state / ('intents' if stage else 'recovery') / ((f'{index:02d}-{name}' if stage else name) + '.intent.json')
    intent = binding_read(intent_path)
    intent_sha = binding_hash(intent_path)
    checksum = Path(str(intent_path) + '.sha256')
    binding_protected(checksum, 0o400)
    if checksum.read_bytes() != (intent_sha + '\n').encode():
        raise BindingError('original_intent_checksum')
    record, logs = binding_verify_reconciliation_bundle(bundle_path, manifest['staging_path'], source_sha, kind, name,
        intent_sha, hashlib.sha256(binding_embedded_source(source, 'remote_reconciliation_poststate_python')).hexdigest())
    if any(record['identity'][key] != manifest[key] for key in ('run_id', 'release_sha', 'script_sha256')):
        raise BindingError('reconciliation_run_binding')
    original = binding_original_stage_log(state / 'artifacts', index, name) if stage else state / 'recovery' / (name + '.operation.log')
    binding_protected(original, 0o400)
    derived = binding_reconciliation_log(original.read_bytes(), logs)
    artifacts = [dict(name=parts[1].decode(), sha256=parts[2].decode())
                 for parts in (line.split(b'\t') for line in derived.splitlines() if line.startswith(b'ARTIFACT\t'))]
    if {item['name'] for item in artifacts} != set(expected_spec.split(',')):
        raise BindingError('original_complete_artifact_set_required')
    binding_retained_local_artifacts(state, manifest, {item['name']: item['sha256'] for item in artifacts}, bundle_path)
    artifacts.append(dict(name='operation-log', sha256=hashlib.sha256(derived).hexdigest()))
    fields = ('run_id', 'release_sha', 'script_sha256', 'predecessor_stage', 'predecessor_receipt_sha256')
    fields += ('stage', 'authorization_gate', 'authorization_receipt_sha256') if stage else ('mode', 'authorization_token_sha256')
    doc = {key: intent[key] for key in fields}
    doc.update(format_version=2, result_category='RECONCILED_EFFECT' if stage else 'RECONCILED_TERMINAL_RECOVERY',
               completed_at=record['observed_at'], intent_sha256=intent_sha,
               artifacts=sorted(artifacts, key=lambda item: item['name']),
               remote_evidence_sha256=binding_hash(Path(bundle_path)), original_operation_log_sha256=binding_hash(original))
    target = Path(str(base) + '.reconciliation.json')
    bundle_target = state / 'artifacts' / (prefix + '.remote-reconciliation.json')
    log_target = state / 'artifacts' / (prefix + '.reconciliation.log')
    for path in (Path(str(base)+'.receipt.json'), Path(str(base)+'.receipt.json.sha256'),
                 target, Path(str(target)+'.sha256'), bundle_target, log_target):
        if path.exists() or path.is_symlink():
            raise BindingError('completion_evidence_already_exists')
    binding_create_raw(bundle_target, Path(bundle_path).read_bytes())
    binding_create_raw(log_target, derived)
    binding_create(target, doc)
    binding_create_raw(Path(str(target)+'.sha256'), (binding_hash(target)+'\n').encode('ascii'))
    return target


def binding_retire_deploy(target, owner, proof_sha, handoff_path, next_request_path):
    """Explicit retirement of one completed deploy; never reset or unlock history."""
    target = Path(target)
    if not target.is_absolute() or target.resolve(strict=True) != target:
        raise BindingError('target_not_canonical')
    root = target / '.v126-target-operations'
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    fd = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        current, owners = binding_chain(root, target)
        policy = binding_active_policy(root, target)
        files, unknown, identities = binding_inventory(root, current)
        if (current != binding_owner(owner) or policy['next_kind'] != 'ORDINARY_DEPLOY' or
                unknown or len(identities) != 1 or identities[0]['kind'] != 'DEPLOY'):
            raise BindingError('ordinary_retirement_requires_known_completion')
        allowed = {'run.json', 'lock', 'transfers'}
        for previous in owners:
            allowed.update(name.split('/')[0] for name in binding_inventory(root, previous)[0])
        if set(path.name for path in root.iterdir()) != allowed:
            raise BindingError('unexpected_target_records')
        identity = identities[0]
        if identity['intent_sha256'] != policy['request_sha256']:
            raise BindingError('ordinary_terminal_request_binding')
        op = hashlib.sha256(binding_canonical(identity)).hexdigest()
        path = root / (op + '.deploy-proof.json')
        proof = binding_deploy_proof(path, identity, target)
        if binding_hash(path) != proof_sha:
            raise BindingError('ordinary_terminal_proof_digest')
        handoff = binding_read(Path(handoff_path))
        request = binding_read(Path(next_request_path))
        next_owner = binding_owner(request['owner'])
        binding_handoff(handoff, owner, next_owner, proof_sha, target)
        binding_deploy_handoff(proof, proof_sha, handoff)
        binding_next_request(request, next_owner, handoff, target)
        if any(previous['run_id'] == next_owner['run_id'] for previous in owners):
            raise BindingError('run_id_reuse')
        transfer = dict(format_version=2, previous_owner=owner, next_owner=next_owner,
                        inventory=files, handoff=handoff, terminal_kind='ORDINARY_DEPLOY_PROOF',
                        next_kind='ORDINARY_DEPLOY', request_sha256=binding_hash(Path(next_request_path)))
        binding_create(root / 'transfers' / (binding_owner_id(owner) + '.json'), transfer)
        print('TARGET_BINDING_RETIRED history_preserved=true next_request_only=true')
    finally:
        os.close(fd)


def binding_supervise(target, identity, worker_argv, *, input_data=None, input_fd=None,
                      env=None, timeout=300, request_sha256=None, pass_fds=(), request_context=None):
    """Hold the single target lock through admission, children and durable result.

    Callers validate source before entry; workers contain only action consumers.
    Missing/nonzero outcomes never become no-effect authority.
    """
    import ctypes
    import signal
    import subprocess
    import time
    if sys.platform != 'linux':
        binding_refuse('linux_subreaper_required')
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:
        binding_refuse('subreaper_unavailable')
    binding_owner({key: identity.get(key) for key in ('run_id', 'release_sha', 'script_sha256')})
    if (set(identity) != {'run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'} or
            not re.fullmatch('[0-9a-f]{64}', identity['intent_sha256']) or not worker_argv or
            not 0 < timeout <= 1800):
        binding_refuse('invalid_identity')
    lockfd = None
    previous_signals = {sig: signal.getsignal(sig) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)}
    try:
        operation_id = hashlib.sha256(binding_canonical(identity)).hexdigest()
        target = Path(target)
        if not target.is_absolute() or str(target.resolve(strict=True)) != str(target):
            binding_refuse('target_not_canonical')
        # Existing sequencer source/input guards still run inside the worker.
        info = target.stat()
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            binding_refuse('target_ownership')
        root = target / '.v126-target-operations'
        try:
            root.mkdir(mode=0o700)
            binding_sync_dir(target)
        except FileExistsError:
            pass
        binding_protected(root, 0o700, True)
        lockfd = os.open(root / 'lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        binding_protected(root / 'lock', 0o600)
        try:
            fcntl.flock(lockfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            binding_refuse('target_busy')
        owner = {key: identity[key] for key in ('run_id', 'release_sha', 'script_sha256')}
        if (root / 'run.json').exists():
            try:
                active_owner, prior_owners = binding_chain(root, target)
            except (BindingError, OSError, ValueError, KeyError, TypeError):
                binding_refuse('binding_history_invalid')
            if active_owner != owner:
                binding_refuse('target_bound_to_another_run')
            policy = binding_active_policy(root, target)
            _, _, current_operations = binding_inventory(root, owner)
            if policy['next_kind'] == 'ORDINARY_DEPLOY':
                if ((identity['kind'], identity['name'], identity['action']) != ('DEPLOY', 'ORDINARY_DEPLOY', 'ordinary-deploy') or
                        request_sha256 != policy['request_sha256'] or identity['intent_sha256'] != request_sha256 or current_operations):
                    binding_refuse('ordinary_deploy_requires_exact_next_request')
            elif identity['kind'] == 'DEPLOY':
                binding_refuse('ordinary_deploy_not_authorized_by_transfer')
            elif not current_operations and (identity['kind'], identity['name']) != ('STAGE', 'BASELINE_VERIFIED'):
                binding_refuse('next_binding_requires_fresh_baseline')
        else:
            # A legacy/uninitialized target may only be claimed by a fresh baseline.
            if identity['kind'] != 'STAGE' or identity['name'] != 'BASELINE_VERIFIED':
                binding_refuse('legacy_run_requires_reconciliation')
            if set(p.name for p in root.iterdir()) != {'lock'}:
                binding_refuse('uninitialized_target_records')
            binding_create(root / 'run.json', owner)
            prior_owners = [owner]
        starts = sorted(root.glob('*.start.json'))
        expected_names = {'lock', 'run.json'} | ({'transfers'} if (root / 'transfers').exists() else set())
        failed = False
        recovery_seen = False
        for start_path in starts:
            prior = binding_read(start_path)
            prior_id = start_path.name.removesuffix('.start.json')
            if set(prior) != {'identity', 'operation_id', 'started_at', 'boot_id'}:
                binding_refuse('start_schema')
            if prior['operation_id'] != prior_id or hashlib.sha256(binding_canonical(prior['identity'])).hexdigest() != prior_id:
                binding_refuse('start_binding')
            result_path = root / (prior_id + '.result.json')
            if not result_path.exists():
                binding_refuse('prior_outcome_unknown')
            outcome = binding_read(result_path)
            if (set(outcome) != {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} or
                    outcome['identity'] != prior['identity'] or outcome['operation_id'] != prior_id or
                    outcome['children'] != 'REAPED' or type(outcome['exit']) is not int or
                    outcome['outcome'] != ('SUCCEEDED' if outcome['exit'] == 0 else 'UNKNOWN')):
                binding_refuse('result_binding')
            log_path = root / (prior_id + '.log')
            binding_protected(log_path, 0o400)
            if hashlib.sha256(log_path.read_bytes()).hexdigest() != outcome['log_sha256']:
                binding_refuse('log_binding')
            if outcome['exit'] != 0:
                binding_refuse('prior_daemon_outcome_unknown')
            failed = failed or outcome['exit'] != 0
            prior_owner = {key: prior['identity'].get(key) for key in owner}
            if prior_owner not in prior_owners:
                binding_refuse('unbound_operation_history')
            if prior_owner == owner:
                recovery_seen = recovery_seen or prior['identity']['kind'] == 'RECOVERY'
            expected_names.update((start_path.name, result_path.name, log_path.name))
            request_path = root / (prior_id + '.request.json')
            if request_path.exists() or request_path.is_symlink():
                binding_request(request_path, prior['identity'], target)
                expected_names.add(request_path.name)
            if prior['identity']['kind'] == 'DEPLOY':
                proof_path = root / (prior_id + '.deploy-proof.json')
                binding_deploy_proof(proof_path, prior['identity'], target)
                expected_names.add(proof_path.name)
            if (root / 'reconciliations').exists():
                binding_reconciliation_inventory(root, target)
                expected_names.add('reconciliations')
        if set(p.name for p in root.iterdir()) != expected_names:
            binding_refuse('unexpected_target_records')
        if (failed or recovery_seen) and identity['kind'] != 'RECOVERY':
            binding_refuse('prior_failed_operation_requires_recovery')
        if (root / (operation_id + '.start.json')).exists():
            binding_refuse('operation_already_dispatched')
        now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
        binding_create(root / (operation_id + '.start.json'), dict(identity=identity, operation_id=operation_id,
               started_at=now(), boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip()))
        if request_context is not None:
            request_path = root / (operation_id + '.request.json')
            binding_create(request_path, dict(format_version=1, identity=identity,
                target_sha256=hashlib.sha256(str(target).encode()).hexdigest(), **request_context))
            binding_request(request_path, identity, target)
        log_path = root / (operation_id + '.log')
        logfd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        # Mutation output never depends on the SSH stdout pipe staying open.
        signal.signal(signal.SIGHUP, signal.SIG_IGN)
        cancelled = False

        def cancel(signum, frame):
            nonlocal cancelled
            cancelled = True

        for signum in (signal.SIGTERM, signal.SIGINT):
            signal.signal(signum, cancel)
        deadline = time.monotonic() + timeout
        child = subprocess.Popen(worker_argv, stdin=(input_fd if input_fd is not None else subprocess.PIPE),
                                 stdout=logfd, stderr=logfd, start_new_session=True,
                                 env=env, pass_fds=pass_fds)
        pending_input = memoryview(input_data or b'') if input_fd is None else memoryview(b'')
        input_error = False
        if input_fd is None:
            os.set_blocking(child.stdin.fileno(), False)
        status = None
        worker_reaped = False
        interrupted = False
        descendant_failure = False
        cleanup_deadline = None
        # waitpid(-1) plus subreaper adoption is the completion proof, not a PID scan.
        while True:
            interrupted = interrupted or cancelled or time.monotonic() >= deadline
            if input_fd is None and not child.stdin.closed:
                try:
                    if pending_input:
                        sent = os.write(child.stdin.fileno(), pending_input[:65536])
                        pending_input = pending_input[sent:]
                    if not pending_input:
                        child.stdin.close()
                except BlockingIOError:
                    pass
                except BrokenPipeError:
                    input_error = bool(pending_input)
                    child.stdin.close()
            try:
                pid, wait_status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if pid:
                value = os.waitstatus_to_exitcode(wait_status)
                if pid == child.pid:
                    worker_reaped = True
                    status = value if value >= 0 else 128 - value
                    child.returncode = value
                elif value != 0:
                    descendant_failure = True
                continue
            if interrupted:
                interrupted = True
                if cleanup_deadline is None:
                    cleanup_deadline = time.monotonic() + 10
                    status = 124
                    if not worker_reaped:
                        # The unreaped leader reserves this PID/PGID; after reaping
                        # it, only our unreaped adopted children are safe to signal.
                        try:
                            os.killpg(child.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                # Direct children are ours and remain unreaped, so their PIDs cannot be reused.
                for pid_text in Path('/proc/self/task/' + str(os.getpid()) + '/children').read_text().split():
                    try:
                        os.kill(int(pid_text), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if time.monotonic() >= cleanup_deadline:
                    binding_refuse('children_completion_unknown')
            time.sleep(0.05)
        if status is None:
            binding_refuse('worker_outcome_unknown')
        if input_fd is None and not child.stdin.closed:
            child.stdin.close()
        if interrupted or cancelled or time.monotonic() >= deadline:
            status = 124
        elif (input_error or pending_input) and status == 0:
            status = 75
        if descendant_failure and status == 0:
            status = 75
        if status == 0 and identity['kind'] == 'DEPLOY':
            try:
                binding_deploy_proof(root / (operation_id + '.deploy-proof.json'), identity, target)
            except (BindingError, OSError, ValueError, KeyError, TypeError):
                status = 75
        os.fsync(logfd)
        os.fchmod(logfd, 0o400)
        os.close(logfd)
        raw = log_path.read_bytes()
        outcome = dict(identity=identity, operation_id=operation_id, exit=status,
                       outcome='SUCCEEDED' if status == 0 else 'UNKNOWN', children='REAPED',
                       log_sha256=hashlib.sha256(raw).hexdigest(), completed_at=now())
        binding_create(root / (operation_id + '.result.json'), outcome)
        # Broken transport does not alter durable operation results.
        try:
            sys.stdout.buffer.write(raw)
            sys.stdout.buffer.write(b'\nREMOTE_OPERATION_ACK\t' + binding_canonical(outcome))
            sys.stdout.buffer.flush()
        except BrokenPipeError:
            pass
        return status
    finally:
        if lockfd is not None:
            os.close(lockfd)
        for sig, handler in previous_signals.items():
            signal.signal(sig, handler)
PY
}

remote_operation_python() {
  remote_operation_bindings_python || { printf 'binding source unavailable\n' >&2; return 75; }
  cat <<'PY'
try:
    source = sys.stdin.buffer.read(2 * 1024 * 1024 + 1)
    fields = ('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action')
    target, *args = sys.argv[1:]
    identity = dict(zip(fields, args[:7]))
    worker_args = args[7:]
    if (len(identity) != 7 or not worker_args or len(source) > 2 * 1024 * 1024 or
            hashlib.sha256(source).hexdigest() != identity['script_sha256']):
        raise BindingError('source_identity')
    status = binding_supervise(target, identity,
        ['bash', '-c', 'source /dev/stdin; remote_dispatch_action "$@"',
         'v126-operation', identity['action'], *worker_args], input_data=source,
        timeout={'backup-rehearsal': 1800, 'image-load': 900,
                 'image-upload': 900, 'final-v125-preflight': 600}.get(identity['action'], 300),
        pass_fds=(8,) if identity['action'] in ('image-upload', 'preflight-upload') else (),
        request_context=dict(args=worker_args, environment={key: value for key, value in os.environ.items()
                             if key.startswith('V126_INTERNAL_REMOTE_')}))
    raise SystemExit(status)
except (BindingError, OSError, ValueError, KeyError, TypeError) as error:
    print('REMOTE_OPERATION=RECONCILIATION_REQUIRED reason=' +
          (str(error) if isinstance(error, BindingError) else 'invalid_or_unavailable_evidence') +
          ' retry_allowed=false', file=sys.stderr)
    raise SystemExit(75)
PY
}

remote_supervise_action() {
  local action="$1"
  shift
  [[ -n "${V126_REMOTE_VERIFIED_BODY:-}" ]] || die 'remote supervision lacks verified source'
  if [[ "${action}" == image-upload || "${action}" == preflight-upload ]]; then
    exec 8<&0 || die 'upload stream is unavailable'
  fi
  printf '%s' "${V126_REMOTE_VERIFIED_BODY}" | python3 -c "$(remote_operation_python)" \
    "${V126_INTERNAL_REMOTE_STAGING_PATH}" "${V126_INTERNAL_REMOTE_RUN_ID}" \
    "${V126_INTERNAL_REMOTE_RELEASE_SHA}" "${V126_INTERNAL_REMOTE_SCRIPT_SHA256}" \
    "${V126_INTERNAL_REMOTE_INTENT_HASH}" "${V126_INTERNAL_REMOTE_OPERATION_KIND}" \
    "${V126_INTERNAL_REMOTE_OPERATION_NAME}" "${action}" "$@"
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
  local baseline_database_identity="${V126_INTERNAL_REMOTE_DATABASE_TARGET_IDENTITY_SHA256:-}"
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
    [[ "${baseline_database_sha}" == NONE && "${baseline_database_identity}" == NONE && "${baseline_identities_sha}" == NONE && \
      "${baseline_compose_sha}" == NONE && "${baseline_maintenance_sha}" == NONE && \
      "${baseline_admission_sha}" == NONE && "${baseline_caddy_sha}" == NONE && \
      "${baseline_env_sha}" == NONE ]] ||
      die 'baseline remote envelope must not claim a pre-existing authority receipt'
  else
    local authority_hash
    for authority_hash in "${baseline_database_sha}" "${baseline_database_identity}" "${baseline_identities_sha}" \
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
      STAGE:FINAL_V125_PREFLIGHT_PASSED:preflight-upload | \
      STAGE:V126_MAINTENANCE_CONFIG_PREPARED:transform-maintenance | \
      STAGE:V126_IMAGE_TRANSFERRED_AND_VERIFIED:image-prepare | \
      STAGE:V126_IMAGE_TRANSFERRED_AND_VERIFIED:image-load | \
      STAGE:V126_IMAGE_TRANSFERRED_AND_VERIFIED:image-upload | \
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
  remote_supervise_action "${action}" "$@"
}

remote_dispatch_action() {
  local action="$1"
  shift
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
    image-upload | preflight-upload) remote_receive_upload "${action}" "$@" ;;
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
