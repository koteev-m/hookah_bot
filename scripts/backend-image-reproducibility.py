#!/usr/bin/env python3
"""Inspect and compare complete OCI images for the backend reproducibility gate."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any


DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
BASE_PIN_RE = re.compile(
    r"^# reproducibility-base: stage=(\S+) tag=(\S+) "
    r"index=(sha256:[0-9a-f]{64}) linux-amd64=(sha256:[0-9a-f]{64})$"
)
FROM_RE = re.compile(r"^FROM (\S+?)(?: AS (\S+))?$", re.IGNORECASE)


class ReproducibilityError(RuntimeError):
    """Raised for malformed or incomplete image evidence."""


def sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def canonical_digest(value: Any) -> str:
    return sha256_bytes(canonical_bytes(value))


def type_name(member: tarfile.TarInfo) -> str:
    if member.isreg():
        return "regular"
    if member.isdir():
        return "directory"
    if member.issym():
        return "symlink"
    if member.islnk():
        return "hardlink"
    if member.ischr():
        return "char"
    if member.isblk():
        return "block"
    if member.isfifo():
        return "fifo"
    return f"other:{member.type!r}"


def normalized_path(name: str) -> str:
    path = str(PurePosixPath(name))
    while path.startswith("./"):
        path = path[2:]
    return path.rstrip("/")


def layer_inventory(uncompressed: bytes) -> list[dict[str, Any]]:
    inventory: list[dict[str, Any]] = []
    with tarfile.open(fileobj=io.BytesIO(uncompressed), mode="r:") as archive:
        for order, member in enumerate(archive):
            content_sha256 = None
            if member.isreg():
                stream = archive.extractfile(member)
                if stream is None:
                    raise ReproducibilityError(f"cannot read regular file {member.name!r}")
                content_sha256 = sha256_bytes(stream.read())
            pax_headers = dict(sorted(member.pax_headers.items()))
            inventory.append(
                {
                    "order": order,
                    "path": normalized_path(member.name),
                    "type": type_name(member),
                    "mode": f"{member.mode:04o}",
                    "uid": member.uid,
                    "gid": member.gid,
                    "uname": member.uname,
                    "gname": member.gname,
                    "size": member.size,
                    "mtime": member.mtime,
                    "link_target": member.linkname,
                    "device_major": member.devmajor,
                    "device_minor": member.devminor,
                    "pax_headers": pax_headers,
                    "xattrs": {
                        key: value
                        for key, value in pax_headers.items()
                        if "xattr" in key.lower()
                    },
                    "whiteout": PurePosixPath(member.name).name.startswith(".wh."),
                    "content_sha256": content_sha256,
                }
            )
    return inventory


def apply_layer(tree: dict[str, dict[str, Any]], inventory: list[dict[str, Any]]) -> None:
    for entry in inventory:
        path = entry["path"]
        basename = PurePosixPath(path).name
        parent = str(PurePosixPath(path).parent)
        if parent == ".":
            parent = ""
        if basename == ".wh..wh..opq":
            prefix = parent + "/" if parent else ""
            for existing in list(tree):
                if existing.startswith(prefix) and existing != parent:
                    del tree[existing]
            continue
        if basename.startswith(".wh."):
            target = str(PurePosixPath(parent) / basename[4:]) if parent else basename[4:]
            prefix = target + "/"
            for existing in list(tree):
                if existing == target or existing.startswith(prefix):
                    del tree[existing]
            continue
        tree[path] = {key: value for key, value in entry.items() if key != "order"}


def tree_slice(tree: dict[str, dict[str, Any]], prefix: str) -> list[dict[str, Any]]:
    selected = []
    for path in sorted(tree):
        if path == prefix or path.startswith(prefix + "/"):
            selected.append({"path": path, **tree[path]})
    return selected


def artifact_signature(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "path": entry["path"],
            "type": entry["type"],
            "mode": entry["mode"],
            "size": entry["size"],
            "link_target": entry["link_target"],
            "content_sha256": entry["content_sha256"],
        }
        for entry in entries
    ]


def read_outer_archive(path: Path) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    with tarfile.open(path, mode="r:*") as archive:
        for member in archive:
            if not member.isfile():
                continue
            name = normalized_path(member.name)
            if name in files:
                raise ReproducibilityError(f"duplicate archive entry: {name}")
            if name == "index.json" or name == "oci-layout" or name.startswith("blobs/sha256/"):
                stream = archive.extractfile(member)
                if stream is None:
                    raise ReproducibilityError(f"cannot read archive entry: {name}")
                files[name] = stream.read()
    return files


def descriptor_blob(files: dict[str, bytes], descriptor: dict[str, Any], label: str) -> bytes:
    digest = descriptor.get("digest")
    size = descriptor.get("size")
    if not isinstance(digest, str) or not DIGEST_RE.fullmatch(digest):
        raise ReproducibilityError(f"{label} has an invalid digest")
    blob = files.get(f"blobs/sha256/{digest.removeprefix('sha256:')}")
    if blob is None:
        raise ReproducibilityError(f"{label} blob is missing: {digest}")
    if sha256_bytes(blob) != digest:
        raise ReproducibilityError(f"{label} digest does not match its bytes: {digest}")
    if size != len(blob):
        raise ReproducibilityError(f"{label} size does not match its bytes: {digest}")
    return blob


def mapped_history(config: dict[str, Any], diff_ids: list[str]) -> list[dict[str, Any]]:
    history = config.get("history")
    if not isinstance(history, list):
        raise ReproducibilityError("image config history is missing")
    mapping = []
    layer_index = 0
    for history_index, entry in enumerate(history):
        if not isinstance(entry, dict):
            raise ReproducibilityError("image history entry is not an object")
        if entry.get("empty_layer", False):
            continue
        if layer_index >= len(diff_ids):
            raise ReproducibilityError("history has more filesystem entries than rootfs DiffIDs")
        mapping.append(
            {
                "layer_index_zero_based": layer_index,
                "layer_number_one_based": layer_index + 1,
                "history_index": history_index,
                "created": entry.get("created", ""),
                "created_by": entry.get("created_by", ""),
                "comment": entry.get("comment", ""),
                "diff_id": diff_ids[layer_index],
            }
        )
        layer_index += 1
    if layer_index != len(diff_ids):
        raise ReproducibilityError(
            f"rootfs has {len(diff_ids)} DiffIDs but history maps {layer_index} filesystem entries"
        )
    return mapping


def load_image(path: Path, loaded_image_id: str | None = None) -> dict[str, Any]:
    files = read_outer_archive(path)
    try:
        index = json.loads(files["index.json"])
    except (KeyError, json.JSONDecodeError) as error:
        raise ReproducibilityError("OCI index is missing or invalid") from error
    manifests = index.get("manifests")
    if not isinstance(manifests, list):
        raise ReproducibilityError("OCI index manifests are missing")
    image_manifests = [
        descriptor
        for descriptor in manifests
        if descriptor.get("mediaType")
        in {
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
        }
    ]
    if len(image_manifests) != 1:
        raise ReproducibilityError(
            f"expected exactly one image manifest, found {len(image_manifests)}"
        )
    manifest_descriptor = image_manifests[0]
    manifest_bytes = descriptor_blob(files, manifest_descriptor, "image manifest")
    try:
        manifest = json.loads(manifest_bytes)
    except json.JSONDecodeError as error:
        raise ReproducibilityError("image manifest JSON is invalid") from error

    config_descriptor = manifest.get("config")
    if not isinstance(config_descriptor, dict):
        raise ReproducibilityError("image config descriptor is missing")
    config_bytes = descriptor_blob(files, config_descriptor, "image config")
    try:
        config = json.loads(config_bytes)
    except json.JSONDecodeError as error:
        raise ReproducibilityError("image config JSON is invalid") from error

    rootfs = config.get("rootfs")
    if not isinstance(rootfs, dict) or rootfs.get("type") != "layers":
        raise ReproducibilityError("image rootfs layer configuration is invalid")
    diff_ids = rootfs.get("diff_ids")
    if not isinstance(diff_ids, list) or not all(
        isinstance(value, str) and DIGEST_RE.fullmatch(value) for value in diff_ids
    ):
        raise ReproducibilityError("image rootfs DiffIDs are invalid")
    layer_descriptors = manifest.get("layers")
    if not isinstance(layer_descriptors, list) or len(layer_descriptors) != len(diff_ids):
        raise ReproducibilityError("manifest layer count does not match rootfs DiffIDs")

    layers = []
    final_tree: dict[str, dict[str, Any]] = {}
    for index_value, (descriptor, expected_diff_id) in enumerate(
        zip(layer_descriptors, diff_ids, strict=True)
    ):
        if not isinstance(descriptor, dict):
            raise ReproducibilityError(f"layer {index_value + 1} descriptor is invalid")
        compressed = descriptor_blob(files, descriptor, f"layer {index_value + 1}")
        media_type = descriptor.get("mediaType", "")
        try:
            if media_type.endswith("+gzip"):
                uncompressed = gzip.decompress(compressed)
            elif media_type.endswith(".tar"):
                uncompressed = compressed
            else:
                raise ReproducibilityError(
                    f"layer {index_value + 1} uses unsupported media type: {media_type}"
                )
        except (gzip.BadGzipFile, EOFError) as error:
            raise ReproducibilityError(f"layer {index_value + 1} gzip is invalid") from error
        actual_diff_id = sha256_bytes(uncompressed)
        if actual_diff_id != expected_diff_id:
            raise ReproducibilityError(
                f"layer {index_value + 1} DiffID mismatch: "
                f"config={expected_diff_id} bytes={actual_diff_id}"
            )
        inventory = layer_inventory(uncompressed)
        apply_layer(final_tree, inventory)
        layers.append(
            {
                "index_zero_based": index_value,
                "number_one_based": index_value + 1,
                "media_type": media_type,
                "compressed_digest": descriptor["digest"],
                "compressed_size": descriptor["size"],
                "diff_id": actual_diff_id,
                "uncompressed_size": len(uncompressed),
                "inventory_count": len(inventory),
                "inventory_digest": canonical_digest(inventory),
                "inventory": inventory,
            }
        )

    manifest_digest = manifest_descriptor["digest"]
    if loaded_image_id is not None:
        if not DIGEST_RE.fullmatch(loaded_image_id):
            raise ReproducibilityError("loaded image ID is not a canonical sha256 digest")

    image_config = config.get("config")
    if not isinstance(image_config, dict):
        raise ReproducibilityError("runtime image config is missing")
    labels = image_config.get("Labels") or {}
    if not isinstance(labels, dict):
        raise ReproducibilityError("runtime image labels are invalid")
    user = image_config.get("User", "")
    if user in {"", "0", "root"}:
        raise ReproducibilityError(f"runtime image user is not non-root: {user!r}")

    install_dist = tree_slice(final_tree, "app/app")
    miniapp_dist = tree_slice(final_tree, "app/miniapp")
    if not install_dist:
        raise ReproducibilityError("backend installDist tree is missing from the image")
    if not miniapp_dist:
        raise ReproducibilityError("Mini App dist tree is missing from the image")
    app_jars = [
        entry
        for entry in install_dist
        if entry["type"] == "regular"
        and re.fullmatch(r"app/app/lib/app-[^/]+\.jar", entry["path"])
    ]
    if len(app_jars) != 1:
        raise ReproducibilityError(
            f"expected one backend application JAR, found {len(app_jars)}"
        )
    launchers = [
        entry for entry in install_dist if entry["path"] in {"app/app/bin/app", "app/app/bin/app.bat"}
    ]
    if len(launchers) != 2:
        raise ReproducibilityError("generated backend launcher inventory is incomplete")
    dependency_jars = [
        entry
        for entry in install_dist
        if entry["type"] == "regular"
        and re.fullmatch(r"app/app/lib/.+\.jar", entry["path"])
        and entry["path"] != app_jars[0]["path"]
    ]

    history = config.get("history")
    history_mapping = mapped_history(config, diff_ids)
    return {
        "archive": str(path),
        "manifest_digest": manifest_digest,
        "manifest_size": len(manifest_bytes),
        "config_digest": config_descriptor["digest"],
        "config_size": len(config_bytes),
        "loaded_image_id": loaded_image_id or manifest_digest,
        "architecture": config.get("architecture"),
        "os": config.get("os"),
        "runtime_user": user,
        "labels": labels,
        "rootfs_diff_ids": diff_ids,
        "history": history,
        "history_digest": canonical_digest(history),
        "filesystem_history": history_mapping,
        "layers": layers,
        "app_jar": app_jars[0],
        "install_dist": install_dist,
        "install_dist_digest": canonical_digest(install_dist),
        "miniapp_dist": miniapp_dist,
        "miniapp_dist_digest": canonical_digest(miniapp_dist),
        "launchers": launchers,
        "launchers_signature": artifact_signature(launchers),
        "launchers_digest": canonical_digest(artifact_signature(launchers)),
        "dependency_jars": dependency_jars,
        "dependency_jars_signature": artifact_signature(dependency_jars),
        "dependency_jars_digest": canonical_digest(artifact_signature(dependency_jars)),
    }


def first_inventory_difference(
    inventory_a: list[dict[str, Any]], inventory_b: list[dict[str, Any]]
) -> tuple[str | None, str]:
    if len(inventory_a) != len(inventory_b):
        return None, "entry-count"
    for entry_a, entry_b in zip(inventory_a, inventory_b, strict=True):
        if entry_a == entry_b:
            continue
        path_a = entry_a.get("path")
        path_b = entry_b.get("path")
        if path_a != path_b:
            return path_a or path_b, "path-or-order"
        for field in (
            "type",
            "mode",
            "uid",
            "gid",
            "uname",
            "gname",
            "size",
            "mtime",
            "link_target",
            "device_major",
            "device_minor",
            "pax_headers",
            "xattrs",
            "whiteout",
            "content_sha256",
        ):
            if entry_a.get(field) != entry_b.get(field):
                return path_a, field
        return path_a, "tar-entry"
    return None, "compressed-layer-bytes"


def compare_images(
    image_a: dict[str, Any],
    image_b: dict[str, Any],
    expected_revision: str | None = None,
    expected_source: str | None = None,
) -> dict[str, Any]:
    findings = []

    def unequal(category: str, left: Any, right: Any) -> None:
        if left != right:
            findings.append(category)

    unequal(
        "application-jar",
        artifact_signature([image_a["app_jar"]]),
        artifact_signature([image_b["app_jar"]]),
    )
    unequal("backend-installDist-tree", image_a["install_dist"], image_b["install_dist"])
    unequal("miniapp-dist-tree", image_a["miniapp_dist"], image_b["miniapp_dist"])
    unequal("generated-launchers", image_a["launchers_signature"], image_b["launchers_signature"])
    unequal("dependency-jars", image_a["dependency_jars_signature"], image_b["dependency_jars_signature"])
    unequal("ordered-history", image_a["history"], image_b["history"])
    unequal("filesystem-history-mapping", image_a["filesystem_history"], image_b["filesystem_history"])
    unequal("rootfs-diffids", image_a["rootfs_diff_ids"], image_b["rootfs_diff_ids"])
    unequal(
        "compressed-layers",
        [
            (layer["media_type"], layer["compressed_digest"], layer["compressed_size"])
            for layer in image_a["layers"]
        ],
        [
            (layer["media_type"], layer["compressed_digest"], layer["compressed_size"])
            for layer in image_b["layers"]
        ],
    )
    unequal("config-digest", image_a["config_digest"], image_b["config_digest"])
    unequal("manifest-digest", image_a["manifest_digest"], image_b["manifest_digest"])
    unequal("final-image-id", image_a["loaded_image_id"], image_b["loaded_image_id"])
    unequal("revision-label", image_a["labels"].get("org.opencontainers.image.revision"), image_b["labels"].get("org.opencontainers.image.revision"))
    unequal("source-label", image_a["labels"].get("org.opencontainers.image.source"), image_b["labels"].get("org.opencontainers.image.source"))
    unequal("platform", (image_a["os"], image_a["architecture"]), (image_b["os"], image_b["architecture"]))
    unequal("runtime-user", image_a["runtime_user"], image_b["runtime_user"])
    unequal(
        "complete-layer-inventory",
        [
            (layer["inventory_count"], layer["inventory_digest"])
            for layer in image_a["layers"]
        ],
        [
            (layer["inventory_count"], layer["inventory_digest"])
            for layer in image_b["layers"]
        ],
    )

    if expected_revision is not None:
        for label, image in (("A", image_a), ("B", image_b)):
            if image["labels"].get("org.opencontainers.image.revision") != expected_revision:
                findings.append(f"revision-label-{label}")
    if expected_source is not None:
        for label, image in (("A", image_a), ("B", image_b)):
            if image["labels"].get("org.opencontainers.image.source") != expected_source:
                findings.append(f"source-label-{label}")
    for label, image in (("A", image_a), ("B", image_b)):
        if image["os"] != "linux" or image["architecture"] != "amd64":
            findings.append(f"platform-{label}")
        if image["runtime_user"] in {"", "0", "root"}:
            findings.append(f"runtime-user-{label}")

    first_layer = None
    first_path = None
    first_category = None
    for layer_a, layer_b in zip(image_a["layers"], image_b["layers"]):
        if (
            layer_a["compressed_digest"] == layer_b["compressed_digest"]
            and layer_a["diff_id"] == layer_b["diff_id"]
            and layer_a["inventory_digest"] == layer_b["inventory_digest"]
        ):
            continue
        first_layer = layer_a["number_one_based"]
        first_path, first_category = first_inventory_difference(
            layer_a["inventory"], layer_b["inventory"]
        )
        break
    if first_layer is None and len(image_a["layers"]) != len(image_b["layers"]):
        first_layer = min(len(image_a["layers"]), len(image_b["layers"])) + 1
        first_category = "layer-count"

    instruction_a = None
    instruction_b = None
    if first_layer is not None:
        index_value = first_layer - 1
        if index_value < len(image_a["filesystem_history"]):
            instruction_a = image_a["filesystem_history"][index_value]["created_by"]
        if index_value < len(image_b["filesystem_history"]):
            instruction_b = image_b["filesystem_history"][index_value]["created_by"]

    return {
        "equal": not findings,
        "findings": sorted(set(findings)),
        "first_differing_layer_one_based": first_layer,
        "first_differing_instruction_a": instruction_a,
        "first_differing_instruction_b": instruction_b,
        "first_differing_path": first_path,
        "first_differing_metadata_category": first_category,
    }


def report_image(image: dict[str, Any]) -> dict[str, Any]:
    return {
        "manifest_digest": image["manifest_digest"],
        "config_digest": image["config_digest"],
        "final_image_id": image["loaded_image_id"],
        "application_jar_path": image["app_jar"]["path"],
        "application_jar_sha256": image["app_jar"]["content_sha256"],
        "backend_installDist_digest": image["install_dist_digest"],
        "miniapp_dist_digest": image["miniapp_dist_digest"],
        "generated_launchers_digest": image["launchers_digest"],
        "dependency_jars_digest": image["dependency_jars_digest"],
        "ordered_history_digest": image["history_digest"],
        "runtime_user": image["runtime_user"],
        "revision_label": image["labels"].get("org.opencontainers.image.revision"),
        "source_label": image["labels"].get("org.opencontainers.image.source"),
        "rootfs_diff_ids": image["rootfs_diff_ids"],
        "compressed_layers": [
            {
                "number_one_based": layer["number_one_based"],
                "digest": layer["compressed_digest"],
                "size": layer["compressed_size"],
            }
            for layer in image["layers"]
        ],
        "filesystem_history": image["filesystem_history"],
        "complete_layer_inventory": [
            {
                "number_one_based": layer["number_one_based"],
                "entry_count": layer["inventory_count"],
                "inventory_digest": layer["inventory_digest"],
            }
            for layer in image["layers"]
        ],
    }


def parse_base_pins(path: Path) -> list[dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    pins = []
    for index_value, line in enumerate(lines):
        match = BASE_PIN_RE.fullmatch(line)
        if not match:
            continue
        if index_value + 1 >= len(lines):
            raise ReproducibilityError("base pin comment is not followed by FROM")
        from_match = FROM_RE.fullmatch(lines[index_value + 1])
        if not from_match:
            raise ReproducibilityError("base pin comment must be immediately followed by FROM")
        stage, tag, index_digest, platform_digest = match.groups()
        from_ref, from_stage = from_match.groups()
        expected_ref = f"{tag}@{index_digest}"
        if from_ref != expected_ref or (from_stage or "runtime") != stage:
            raise ReproducibilityError(
                f"base pin for {stage} does not match its FROM instruction"
            )
        pins.append(
            {
                "stage": stage,
                "tag": tag,
                "index": index_digest,
                "linux_amd64": platform_digest,
            }
        )
    expected_stages = ["miniapp-build", "build", "runtime"]
    if [pin["stage"] for pin in pins] != expected_stages:
        raise ReproducibilityError(
            f"expected base stages {expected_stages}, found {[pin['stage'] for pin in pins]}"
        )
    return pins


def verify_index(raw_path: Path, expected_index: str, expected_platform: str) -> None:
    raw = raw_path.read_bytes()
    if sha256_bytes(raw) != expected_index:
        raise ReproducibilityError(
            f"base index bytes do not match pin: expected={expected_index} actual={sha256_bytes(raw)}"
        )
    try:
        index = json.loads(raw)
    except json.JSONDecodeError as error:
        raise ReproducibilityError("base index JSON is invalid") from error
    if index.get("mediaType") not in {
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
    }:
        raise ReproducibilityError("pinned base is not a multi-platform image index")
    matches = [
        descriptor
        for descriptor in index.get("manifests", [])
        if descriptor.get("platform", {}).get("os") == "linux"
        and descriptor.get("platform", {}).get("architecture") == "amd64"
        and descriptor.get("platform", {}).get("variant") in {None, ""}
    ]
    if len(matches) != 1:
        raise ReproducibilityError(
            f"expected one linux/amd64 platform manifest, found {len(matches)}"
        )
    if matches[0].get("digest") != expected_platform:
        raise ReproducibilityError(
            "linux/amd64 platform digest does not match the reviewed pin: "
            f"expected={expected_platform} actual={matches[0].get('digest')}"
        )


def verify_metadata(
    path: Path,
    pins: list[dict[str, str]],
    epoch: str,
    revision: str,
    source: str,
    public_url: str,
    expected_manifest_digest: str,
) -> None:
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReproducibilityError(f"Buildx metadata is invalid: {path}") from error
    if metadata.get("containerimage.digest") != expected_manifest_digest:
        raise ReproducibilityError("Buildx containerimage digest does not match OCI manifest")
    provenance = metadata.get("buildx.build.provenance")
    if not isinstance(provenance, dict):
        raise ReproducibilityError("Buildx metadata has no local provenance record")
    invocation = provenance.get("invocation", {})
    parameters = invocation.get("parameters", {})
    args = parameters.get("args", {})
    expected_args = {
        "build-arg:SOURCE_DATE_EPOCH": epoch,
        "build-arg:VITE_BACKEND_PUBLIC_URL": public_url,
        "label:org.opencontainers.image.revision": revision,
        "label:org.opencontainers.image.source": source,
    }
    for key, expected in expected_args.items():
        if args.get(key) != expected:
            raise ReproducibilityError(
                f"Buildx metadata argument mismatch for {key}: {args.get(key)!r}"
            )
    if "no-cache" not in args:
        raise ReproducibilityError("Buildx metadata does not prove --no-cache")
    descriptor_platform = metadata.get("containerimage.descriptor", {}).get("platform", {})
    if (
        descriptor_platform.get("os") != "linux"
        or descriptor_platform.get("architecture") != "amd64"
    ):
        raise ReproducibilityError("Buildx metadata platform is not linux/amd64")
    material_digests = {
        digest
        for material in provenance.get("materials", [])
        for digest in material.get("digest", {}).values()
        if isinstance(digest, str)
    }
    missing = [pin["index"] for pin in pins if pin["index"].removeprefix("sha256:") not in material_digests]
    if missing:
        raise ReproducibilityError(
            f"Buildx materials do not contain every pinned base index: {missing}"
        )


def add_tar_entry(
    archive: tarfile.TarFile,
    name: str,
    content: bytes | None,
    *,
    mode: int,
    mtime: int,
) -> None:
    info = tarfile.TarInfo(name)
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mode = mode
    info.mtime = mtime
    if content is None:
        info.type = tarfile.DIRTYPE
        info.size = 0
        archive.addfile(info)
    else:
        info.size = len(content)
        archive.addfile(info, io.BytesIO(content))


def fixture_layer(entries: list[tuple[str, bytes | None, int, int]]) -> bytes:
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for name, content, mode, mtime in entries:
            add_tar_entry(archive, name, content, mode=mode, mtime=mtime)
    return output.getvalue()


def gzip_bytes(data: bytes, mtime: int) -> bytes:
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=mtime) as stream:
        stream.write(data)
    return output.getvalue()


def write_fixture_image(
    path: Path,
    *,
    app_content: bytes = b"app-jar",
    miniapp_content: bytes = b"miniapp",
    app_mtime: int = 100,
    gzip_mtime: int = 0,
    env_extra: str | None = None,
    history_suffix: str = "",
    revision: str = "a" * 40,
    source: str = "https://example.invalid/repo",
    manifest_annotation: str | None = None,
) -> str:
    raw_layers = [
        fixture_layer([("app", None, 0o755, 100), ("app/app", None, 0o755, 100), ("app/app/bin", None, 0o755, 100), ("app/app/bin/app", b"launcher", 0o755, app_mtime), ("app/app/bin/app.bat", b"launcher-bat", 0o755, app_mtime), ("app/app/lib", None, 0o755, 100), ("app/app/lib/app-1.jar", app_content, 0o644, app_mtime), ("app/app/lib/dependency.jar", b"dependency", 0o644, app_mtime)]),
        fixture_layer([("app", None, 0o755, 100), ("app/miniapp", None, 0o755, 100), ("app/miniapp/index.html", miniapp_content, 0o644, 100)]),
    ]
    compressed_layers = [gzip_bytes(layer, gzip_mtime) for layer in raw_layers]
    history = [
        {"created": "1970-01-01T00:01:40Z", "created_by": "ENV FIXTURE=1", "empty_layer": True},
        {"created": "1970-01-01T00:01:40Z", "created_by": "COPY installDist" + history_suffix},
        {"created": "1970-01-01T00:01:40Z", "created_by": "USER appuser", "empty_layer": True},
        {"created": "1970-01-01T00:01:40Z", "created_by": "COPY miniapp"},
    ]
    env = ["PATH=/usr/bin"]
    if env_extra is not None:
        env.append(env_extra)
    config = {
        "architecture": "amd64",
        "os": "linux",
        "config": {
            "User": "appuser",
            "Env": env,
            "Labels": {
                "org.opencontainers.image.revision": revision,
                "org.opencontainers.image.source": source,
            },
        },
        "rootfs": {"type": "layers", "diff_ids": [sha256_bytes(layer) for layer in raw_layers]},
        "history": history,
    }
    config_bytes = canonical_bytes(config)
    config_digest = sha256_bytes(config_bytes)
    layer_descriptors = [
        {
            "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
            "digest": sha256_bytes(layer),
            "size": len(layer),
        }
        for layer in compressed_layers
    ]
    manifest = {
        "schemaVersion": 2,
        "mediaType": "application/vnd.oci.image.manifest.v1+json",
        "config": {
            "mediaType": "application/vnd.oci.image.config.v1+json",
            "digest": config_digest,
            "size": len(config_bytes),
        },
        "layers": layer_descriptors,
    }
    if manifest_annotation is not None:
        manifest["annotations"] = {"fixture": manifest_annotation}
    manifest_bytes = canonical_bytes(manifest)
    manifest_digest = sha256_bytes(manifest_bytes)
    index = {
        "schemaVersion": 2,
        "mediaType": "application/vnd.oci.image.index.v1+json",
        "manifests": [
            {
                "mediaType": "application/vnd.oci.image.manifest.v1+json",
                "digest": manifest_digest,
                "size": len(manifest_bytes),
            }
        ],
    }
    blobs = {config_digest: config_bytes, manifest_digest: manifest_bytes}
    blobs.update({descriptor["digest"]: data for descriptor, data in zip(layer_descriptors, compressed_layers, strict=True)})
    with tarfile.open(path, mode="w") as archive:
        members = {
            "oci-layout": canonical_bytes({"imageLayoutVersion": "1.0.0"}),
            "index.json": canonical_bytes(index),
            **{f"blobs/sha256/{digest.removeprefix('sha256:')}": data for digest, data in blobs.items()},
        }
        for name in sorted(members):
            add_tar_entry(archive, name, members[name], mode=0o600, mtime=100)
    return manifest_digest


def self_test() -> None:
    revision = "a" * 40
    source = "https://example.invalid/repo"
    with tempfile.TemporaryDirectory(prefix="backend-image-repro-self-test.") as temp:
        root = Path(temp)
        base_path = root / "base.tar"
        equal_path = root / "equal.tar"
        base_id = write_fixture_image(base_path)
        equal_id = write_fixture_image(equal_path)
        base = load_image(base_path, base_id)
        equal = load_image(equal_path, equal_id)
        result = compare_images(base, equal, revision, source)
        if not result["equal"]:
            raise AssertionError(f"equal fixture false-negative: {result}")

        cases = [
            ("application bytes", {"app_content": b"changed"}, "application-jar"),
            ("Mini App bytes", {"miniapp_content": b"changed"}, "miniapp-dist-tree"),
            ("tar metadata", {"app_mtime": 101}, "complete-layer-inventory"),
            ("compressed bytes", {"gzip_mtime": 1}, "compressed-layers"),
            ("config bytes", {"env_extra": "EXTRA=1"}, "config-digest"),
            ("ordered history", {"history_suffix": " changed"}, "ordered-history"),
            ("revision label", {"revision": "b" * 40}, "revision-label"),
            ("manifest bytes", {"manifest_annotation": "changed"}, "manifest-digest"),
        ]
        for index_value, (label, kwargs, expected_finding) in enumerate(cases):
            candidate_path = root / f"case-{index_value}.tar"
            candidate_id = write_fixture_image(candidate_path, **kwargs)
            candidate = load_image(candidate_path, candidate_id)
            comparison = compare_images(base, candidate, revision, source)
            if comparison["equal"] or expected_finding not in comparison["findings"]:
                raise AssertionError(
                    f"{label} fixture false-PASS: expected {expected_finding}, got {comparison}"
                )
        wrong_id = dict(equal)
        wrong_id["loaded_image_id"] = "sha256:" + "f" * 64
        comparison = compare_images(base, wrong_id, revision, source)
        if comparison["equal"] or "final-image-id" not in comparison["findings"]:
            raise AssertionError(f"final image ID fixture false-PASS: {comparison}")

        mapping = base["filesystem_history"]
        if [entry["history_index"] for entry in mapping] != [1, 3]:
            raise AssertionError(f"empty-layer history alignment failed: {mapping}")
    print(f"backend image comparator self-test passed: {len(cases) + 2} fixture groups")


def command_compare(args: argparse.Namespace) -> None:
    image_a = load_image(Path(args.archive_a), args.image_id_a)
    image_b = load_image(Path(args.archive_b), args.image_id_b)
    comparison = compare_images(image_a, image_b, args.expected_revision, args.expected_source)
    report = {
        "schema": 1,
        "comparison": comparison,
        "image_a": report_image(image_a),
        "image_b": report_image(image_b),
    }
    if args.report:
        report_path = Path(args.report)
        report_path.write_bytes(json.dumps(report, sort_keys=True, indent=2).encode("utf-8") + b"\n")
        os.chmod(report_path, 0o600)
    if not comparison["equal"]:
        print("backend image reproducibility comparison failed", file=sys.stderr)
        print("findings=" + ",".join(comparison["findings"]), file=sys.stderr)
        if comparison["first_differing_layer_one_based"] is not None:
            print(
                "first_differing_layer_one_based="
                + str(comparison["first_differing_layer_one_based"]),
                file=sys.stderr,
            )
            print(
                "first_differing_instruction="
                + str(comparison["first_differing_instruction_a"]),
                file=sys.stderr,
            )
            if comparison["first_differing_path"] is not None:
                print(
                    "first_differing_path=" + comparison["first_differing_path"],
                    file=sys.stderr,
                )
            print(
                "first_differing_metadata_category="
                + str(comparison["first_differing_metadata_category"]),
                file=sys.stderr,
            )
        raise SystemExit(1)
    concise = report["image_a"]
    print("backend image reproducibility comparison passed")
    print(f"application_jar_sha256={concise['application_jar_sha256']}")
    print(f"backend_installDist_digest={concise['backend_installDist_digest']}")
    print(f"miniapp_dist_digest={concise['miniapp_dist_digest']}")
    print(f"ordered_history_digest={concise['ordered_history_digest']}")
    print(f"rootfs_layers={len(concise['rootfs_diff_ids'])}")
    print(f"config_digest={concise['config_digest']}")
    print(f"manifest_digest={concise['manifest_digest']}")
    print(f"final_image_id={concise['final_image_id']}")
    print(f"runtime_user={concise['runtime_user']}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    compare = subparsers.add_parser("compare")
    compare.add_argument("archive_a")
    compare.add_argument("archive_b")
    compare.add_argument("--image-id-a", required=True)
    compare.add_argument("--image-id-b", required=True)
    compare.add_argument("--expected-revision", required=True)
    compare.add_argument("--expected-source", required=True)
    compare.add_argument("--report")
    compare.set_defaults(function=command_compare)

    pins = subparsers.add_parser("base-pins")
    pins.add_argument("dockerfile")

    verify = subparsers.add_parser("verify-index")
    verify.add_argument("raw_index")
    verify.add_argument("expected_index")
    verify.add_argument("expected_platform")

    metadata = subparsers.add_parser("verify-metadata")
    metadata.add_argument("metadata")
    metadata.add_argument("dockerfile")
    metadata.add_argument("epoch")
    metadata.add_argument("revision")
    metadata.add_argument("source")
    metadata.add_argument("public_url")
    metadata.add_argument("expected_manifest_digest")

    self_test_parser = subparsers.add_parser("self-test")
    self_test_parser.set_defaults(function=lambda _: self_test())
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    if args.command == "base-pins":
        for pin in parse_base_pins(Path(args.dockerfile)):
            print("\t".join((pin["stage"], pin["tag"], pin["index"], pin["linux_amd64"])))
        return
    if args.command == "verify-index":
        verify_index(Path(args.raw_index), args.expected_index, args.expected_platform)
        return
    if args.command == "verify-metadata":
        verify_metadata(
            Path(args.metadata),
            parse_base_pins(Path(args.dockerfile)),
            args.epoch,
            args.revision,
            args.source,
            args.public_url,
            args.expected_manifest_digest,
        )
        return
    args.function(args)


if __name__ == "__main__":
    try:
        main()
    except ReproducibilityError as error:
        print(f"reproducibility evidence error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
