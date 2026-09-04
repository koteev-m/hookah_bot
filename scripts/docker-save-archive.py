#!/usr/bin/env python3
"""Verify and atomically publish one canonical Docker-save image archive."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import posixpath
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Any, BinaryIO


DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
CONFIG_PATH_RE = re.compile(r"^(?:blobs/sha256/)?([0-9a-f]{64})(?:\.json)?$")
EXPECTED_SOURCE = "https://github.com/koteev-m/hookah_bot"
MAX_JSON_BYTES = 16 * 1024 * 1024


class ArchiveError(RuntimeError):
    """Raised when archive bytes or publication state are not authoritative."""


def sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def sha256_stream(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
            return "sha256:" + digest.hexdigest()
        digest.update(chunk)


def safe_member_name(value: object) -> bool:
    return (
        isinstance(value, str)
        and bool(value)
        and not value.startswith("/")
        and posixpath.normpath(value) == value
        and all(part not in ("", ".", "..") for part in value.split("/"))
    )


def read_member(archive: tarfile.TarFile, member: tarfile.TarInfo, label: str) -> bytes:
    if member.size > MAX_JSON_BYTES:
        raise ArchiveError(f"{label} exceeds the bounded JSON size")
    handle = archive.extractfile(member)
    if handle is None:
        raise ArchiveError(f"{label} is unreadable")
    return handle.read()


def read_json_member(
    archive: tarfile.TarFile,
    members: dict[str, tarfile.TarInfo],
    name: str,
    label: str,
) -> tuple[Any, bytes]:
    member = members.get(name)
    if member is None or not member.isfile():
        raise ArchiveError(f"{label} is missing or non-regular")
    payload = read_member(archive, member, label)
    try:
        return json.loads(payload), payload
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ArchiveError(f"{label} is invalid JSON") from error


def digest_layer(
    archive: tarfile.TarFile,
    member: tarfile.TarInfo,
    expected_diff_id: str,
    number: int,
) -> tuple[str, str]:
    raw_handle = archive.extractfile(member)
    if raw_handle is None:
        raise ArchiveError(f"layer {number} is unreadable")
    raw_digest = hashlib.sha256()
    uncompressed_digest = hashlib.sha256()

    class HashingReader(io.RawIOBase):
        def readable(self) -> bool:
            return True

        def readinto(self, target: bytearray) -> int:
            chunk = raw_handle.read(len(target))
            if not chunk:
                return 0
            raw_digest.update(chunk)
            target[: len(chunk)] = chunk
            return len(chunk)

    buffered = io.BufferedReader(HashingReader(), buffer_size=1024 * 1024)
    prefix = buffered.peek(2)[:2]
    try:
        if prefix == b"\x1f\x8b":
            content: BinaryIO = gzip.GzipFile(fileobj=buffered, mode="rb")
        else:
            content = buffered
        while True:
            chunk = content.read(1024 * 1024)
            if not chunk:
                break
            uncompressed_digest.update(chunk)
        if content is not buffered:
            content.close()
    except (gzip.BadGzipFile, EOFError, OSError) as error:
        raise ArchiveError(f"layer {number} gzip payload is invalid") from error
    finally:
        buffered.close()

    actual_diff_id = "sha256:" + uncompressed_digest.hexdigest()
    if actual_diff_id != expected_diff_id:
        raise ArchiveError(
            f"layer {number} DiffID mismatch: expected={expected_diff_id} actual={actual_diff_id}"
        )
    return "sha256:" + raw_digest.hexdigest(), actual_diff_id


def require_digest(label: str, value: object) -> str:
    if not isinstance(value, str) or DIGEST_RE.fullmatch(value) is None:
        raise ArchiveError(f"{label} is not a canonical sha256 digest")
    return value


def verify_archive(
    path: Path,
    *,
    expected_tag: str,
    expected_image_id: str,
    expected_config_digest: str | None = None,
    expected_manifest_digest: str | None = None,
    expected_revision: str,
    expected_source: str,
    expected_diff_ids: list[str] | None = None,
    expected_compressed_layers: list[str] | None = None,
) -> dict[str, Any]:
    expected_image_id = require_digest("expected image ID", expected_image_id)
    if expected_config_digest is not None:
        expected_config_digest = require_digest(
            "expected config digest", expected_config_digest
        )
    if expected_manifest_digest is not None:
        expected_manifest_digest = require_digest(
            "expected manifest digest", expected_manifest_digest
        )
    if not re.fullmatch(r"[0-9a-f]{40}", expected_revision):
        raise ArchiveError("expected revision is not an exact Git SHA")
    if not expected_tag.endswith(":" + expected_revision):
        raise ArchiveError("expected tag does not end in the exact revision")

    try:
        archive_stat = path.stat(follow_symlinks=False)
    except FileNotFoundError as error:
        raise ArchiveError("Docker-save archive is missing") from error
    if (
        not stat.S_ISREG(archive_stat.st_mode)
        or stat.S_IMODE(archive_stat.st_mode) != 0o600
        or archive_stat.st_nlink != 1
    ):
        raise ArchiveError("Docker-save archive must be one mode-0600 regular file")
    with path.open("rb") as stream:
        archive_sha256 = sha256_stream(stream)

    try:
        archive_context = tarfile.open(path, mode="r:*")
    except (OSError, tarfile.TarError) as error:
        raise ArchiveError("Docker-save archive is not a readable tar") from error
    with archive_context as archive:
        try:
            archive_members = archive.getmembers()
        except (OSError, tarfile.TarError) as error:
            raise ArchiveError("Docker-save archive member table is invalid") from error
        names = [member.name for member in archive_members]
        if len(names) != len(set(names)):
            raise ArchiveError("Docker-save archive has duplicate member paths")
        for member in archive_members:
            if not safe_member_name(member.name):
                raise ArchiveError("Docker-save archive contains an unsafe member path")
            if not (member.isfile() or member.isdir()):
                raise ArchiveError("Docker-save archive contains a linked or special member")
        members = {member.name: member for member in archive_members}

        manifest, _ = read_json_member(
            archive, members, "manifest.json", "Docker-save manifest.json"
        )
        if (
            not isinstance(manifest, list)
            or len(manifest) != 1
            or not isinstance(manifest[0], dict)
        ):
            raise ArchiveError("Docker-save manifest must contain exactly one image entry")
        image = manifest[0]
        if image.get("RepoTags") != [expected_tag]:
            raise ArchiveError("Docker-save RepoTags do not equal the exact expected tag")

        config_name = image.get("Config")
        if not safe_member_name(config_name):
            raise ArchiveError("Docker-save config path is unsafe")
        config_match = CONFIG_PATH_RE.fullmatch(config_name)
        if config_match is None:
            raise ArchiveError("Docker-save config filename has no canonical digest")
        config_member = members.get(config_name)
        if config_member is None or not config_member.isfile():
            raise ArchiveError("Docker-save config is missing or non-regular")
        config_bytes = read_member(archive, config_member, "Docker-save config")
        config_digest = sha256_bytes(config_bytes)
        named_config_digest = "sha256:" + config_match.group(1)
        if named_config_digest != config_digest:
            raise ArchiveError("Docker-save config filename differs from its content digest")
        if expected_config_digest is not None and config_digest != expected_config_digest:
            raise ArchiveError("Docker-save config digest differs from the proven config digest")
        try:
            config = json.loads(config_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ArchiveError("Docker-save config is invalid JSON") from error
        if not isinstance(config, dict):
            raise ArchiveError("Docker-save config is not an object")
        if config.get("os") != "linux" or config.get("architecture") != "amd64":
            raise ArchiveError("Docker-save platform is not exactly linux/amd64")
        runtime = config.get("config")
        if not isinstance(runtime, dict) or runtime.get("User") != "appuser":
            raise ArchiveError("Docker-save runtime user is not exactly appuser")
        labels = runtime.get("Labels")
        if not isinstance(labels, dict):
            raise ArchiveError("Docker-save labels are missing")
        if labels.get("org.opencontainers.image.revision") != expected_revision:
            raise ArchiveError("Docker-save revision label is not exact")
        if labels.get("org.opencontainers.image.source") != expected_source:
            raise ArchiveError("Docker-save source label is not exact")
        rootfs = config.get("rootfs")
        diff_ids = rootfs.get("diff_ids") if isinstance(rootfs, dict) else None
        if (
            not isinstance(rootfs, dict)
            or rootfs.get("type") != "layers"
            or not isinstance(diff_ids, list)
            or not diff_ids
            or any(not isinstance(value, str) or DIGEST_RE.fullmatch(value) is None for value in diff_ids)
        ):
            raise ArchiveError("Docker-save rootfs DiffIDs are invalid")
        if expected_diff_ids is not None and diff_ids != expected_diff_ids:
            raise ArchiveError("Docker-save rootfs DiffIDs differ from the proven build")

        layer_names = image.get("Layers")
        if (
            not isinstance(layer_names, list)
            or not layer_names
            or len(layer_names) != len(set(layer_names))
            or len(layer_names) != len(diff_ids)
        ):
            raise ArchiveError("Docker-save layer count/order inventory is invalid")
        raw_layer_digests: list[str] = []
        for number, (layer_name, expected_diff_id) in enumerate(
            zip(layer_names, diff_ids, strict=True), start=1
        ):
            if not safe_member_name(layer_name):
                raise ArchiveError(f"Docker-save layer {number} path is unsafe")
            layer_member = members.get(layer_name)
            if layer_member is None or not layer_member.isfile():
                raise ArchiveError(f"Docker-save layer {number} is missing or non-regular")
            raw_digest, _ = digest_layer(
                archive, layer_member, expected_diff_id, number
            )
            raw_layer_digests.append(raw_digest)
        identity_mode = "config"
        oci_manifest_digest: str | None = None
        oci_files = {"oci-layout", "index.json"}
        has_oci = bool(oci_files & set(members))
        must_verify_oci = config_digest != expected_image_id
        if has_oci or must_verify_oci:
            if not oci_files.issubset(members):
                raise ArchiveError("Docker-save OCI identity metadata is incomplete")
            layout, _ = read_json_member(
                archive, members, "oci-layout", "Docker-save oci-layout"
            )
            if layout != {"imageLayoutVersion": "1.0.0"}:
                raise ArchiveError("Docker-save OCI layout version is invalid")
            index, _ = read_json_member(
                archive, members, "index.json", "Docker-save OCI index"
            )
            descriptors = index.get("manifests") if isinstance(index, dict) else None
            if not isinstance(descriptors, list) or len(descriptors) != 1:
                raise ArchiveError("Docker-save OCI index must contain exactly one manifest")
            descriptor = descriptors[0]
            if not isinstance(descriptor, dict):
                raise ArchiveError("Docker-save OCI manifest descriptor is invalid")
            oci_manifest_digest = require_digest(
                "Docker-save OCI manifest digest", descriptor.get("digest")
            )
            manifest_name = "blobs/sha256/" + oci_manifest_digest.removeprefix("sha256:")
            oci_manifest, oci_manifest_bytes = read_json_member(
                archive, members, manifest_name, "Docker-save OCI manifest"
            )
            if sha256_bytes(oci_manifest_bytes) != oci_manifest_digest:
                raise ArchiveError("Docker-save OCI manifest digest differs from its bytes")
            if descriptor.get("size") != len(oci_manifest_bytes):
                raise ArchiveError("Docker-save OCI manifest size is invalid")
            if expected_manifest_digest is not None and oci_manifest_digest != expected_manifest_digest:
                raise ArchiveError("Docker-save OCI manifest differs from the proven manifest")
            if must_verify_oci and oci_manifest_digest != expected_image_id:
                raise ArchiveError("Docker-save OCI manifest differs from the expected image ID")
            oci_config = oci_manifest.get("config") if isinstance(oci_manifest, dict) else None
            oci_layers = oci_manifest.get("layers") if isinstance(oci_manifest, dict) else None
            if (
                not isinstance(oci_config, dict)
                or oci_config.get("digest") != config_digest
                or oci_config.get("size") != len(config_bytes)
            ):
                raise ArchiveError("Docker-save OCI manifest does not bind the exact config")
            if not isinstance(oci_layers, list) or len(oci_layers) != len(layer_names):
                raise ArchiveError("Docker-save OCI manifest layer inventory is invalid")
            descriptor_digests: list[str] = []
            for number, (layer_name, raw_digest, layer_descriptor) in enumerate(
                zip(layer_names, raw_layer_digests, oci_layers, strict=True), start=1
            ):
                if not isinstance(layer_descriptor, dict):
                    raise ArchiveError(f"Docker-save OCI layer {number} descriptor is invalid")
                descriptor_digest = require_digest(
                    f"Docker-save OCI layer {number} digest", layer_descriptor.get("digest")
                )
                descriptor_name = "blobs/sha256/" + descriptor_digest.removeprefix("sha256:")
                if layer_name != descriptor_name or raw_digest != descriptor_digest:
                    raise ArchiveError(
                        f"Docker-save OCI layer {number} does not bind the exact layer bytes"
                    )
                if layer_descriptor.get("size") != members[layer_name].size:
                    raise ArchiveError(f"Docker-save OCI layer {number} size is invalid")
                descriptor_digests.append(descriptor_digest)
            if (
                expected_compressed_layers is not None
                and descriptor_digests != expected_compressed_layers
            ):
                raise ArchiveError(
                    "Docker-save OCI layer digests/order differ from the proven build"
                )
            identity_mode = "oci-manifest" if config_digest != expected_image_id else "config+oci"
        elif config_digest != expected_image_id:
            raise ArchiveError("Docker-save config digest differs from the expected image ID")
        if config_digest == expected_image_id and expected_config_digest not in (None, expected_image_id):
            raise ArchiveError("Docker-save expected config/image identity is contradictory")

    return {
        "schema": 1,
        "archive_sha256": archive_sha256,
        "archive_size": archive_stat.st_size,
        "repo_tag": expected_tag,
        "image_id": expected_image_id,
        "config_digest": config_digest,
        "manifest_digest": oci_manifest_digest,
        "identity_mode": identity_mode,
        "platform": "linux/amd64",
        "runtime_user": "appuser",
        "revision_label": expected_revision,
        "source_label": expected_source,
        "rootfs_diff_ids": diff_ids,
        "layer_digests": raw_layer_digests,
    }


def load_comparison(path: Path) -> dict[str, Any]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ArchiveError("reproducibility comparison report is invalid") from error
    if report.get("comparison", {}).get("equal") is not True:
        raise ArchiveError("reproducibility comparison report is not PASS")
    image_a = report.get("image_a")
    image_b = report.get("image_b")
    if not isinstance(image_a, dict) or image_a != image_b:
        raise ArchiveError("reproducibility report does not contain two exact equal images")
    return image_a


def fsync_file(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        file_stat = os.fstat(descriptor)
        if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_nlink != 1:
            raise ArchiveError("archive temporary path is not one regular file")
        os.fchmod(descriptor, 0o600)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_publish(source: Path, destination: Path) -> None:
    if not destination.is_absolute():
        raise ArchiveError("Docker-save output must be an absolute path")
    if source.parent != destination.parent:
        raise ArchiveError("archive temporary path must be in the output directory")
    fsync_file(source)
    try:
        os.lstat(destination)
    except FileNotFoundError:
        pass
    else:
        raise ArchiveError("Docker-save output path already exists")
    try:
        os.link(source, destination, follow_symlinks=False)
    except FileExistsError as error:
        raise ArchiveError("Docker-save output path already exists") from error
    try:
        directory_fd = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        os.unlink(source)
        directory_fd = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except BaseException:
        # A linked destination is already a complete, flushed publication. Never erase it here.
        raise


def command_verify(args: argparse.Namespace) -> None:
    comparison = load_comparison(Path(args.comparison)) if args.comparison else None
    expected_config = args.expected_config_digest
    expected_manifest = args.expected_manifest_digest
    expected_diff_ids = None
    expected_layers = None
    if comparison is not None:
        comparison_image_id = comparison.get("final_image_id")
        if comparison_image_id != args.expected_image_id:
            raise ArchiveError("expected image ID differs from the proven double-build result")
        expected_config = comparison.get("config_digest")
        expected_manifest = comparison.get("manifest_digest")
        expected_diff_ids = comparison.get("rootfs_diff_ids")
        compressed_layers = comparison.get("compressed_layers")
        if not isinstance(compressed_layers, list):
            raise ArchiveError("proven compressed-layer inventory is invalid")
        expected_layers = [item.get("digest") for item in compressed_layers]
    report = verify_archive(
        Path(args.archive),
        expected_tag=args.expected_tag,
        expected_image_id=args.expected_image_id,
        expected_config_digest=expected_config,
        expected_manifest_digest=expected_manifest,
        expected_revision=args.expected_revision,
        expected_source=args.expected_source,
        expected_diff_ids=expected_diff_ids,
        expected_compressed_layers=expected_layers,
    )
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))


def command_fsync(args: argparse.Namespace) -> None:
    fsync_file(Path(args.archive))


def command_publish(args: argparse.Namespace) -> None:
    atomic_publish(Path(args.temporary), Path(args.output))


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def fixture_tar(entries: list[tuple[str, bytes | None, bytes | None]]) -> bytes:
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w") as archive:
        for name, payload, kind in entries:
            member = tarfile.TarInfo(name)
            member.mode = 0o600
            if kind == b"dir":
                member.type = tarfile.DIRTYPE
                archive.addfile(member)
            elif kind == b"symlink":
                member.type = tarfile.SYMTYPE
                member.linkname = "manifest.json"
                archive.addfile(member)
            elif kind == b"hardlink":
                member.type = tarfile.LNKTYPE
                member.linkname = "manifest.json"
                archive.addfile(member)
            else:
                assert payload is not None
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
    return output.getvalue()


def gzip_payload(value: bytes) -> bytes:
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", mtime=0, filename="") as stream:
        stream.write(value)
    return output.getvalue()


def build_fixture(
    path: Path,
    mode: str,
    *,
    tag: str,
    revision: str,
    source: str,
) -> tuple[str, str]:
    layer_a = b"fixture-layer-a"
    layer_b = b"fixture-layer-b"
    compressed_a = gzip_payload(layer_a)
    compressed_b = gzip_payload(layer_b)
    diff_ids = [sha256_bytes(layer_a), sha256_bytes(layer_b)]
    layers = [compressed_a, compressed_b]
    if mode == "layer-order":
        layers.reverse()
    config = {
        "architecture": "arm64" if mode == "wrong-platform" else "amd64",
        "os": "linux",
        "config": {
            "User": "root" if mode == "wrong-user" else "appuser",
            "Labels": {
                "org.opencontainers.image.revision": "b" * 40 if mode == "wrong-revision" else revision,
                "org.opencontainers.image.source": "https://wrong.invalid/repo" if mode == "wrong-source" else source,
            },
        },
        "rootfs": {
            "type": "layers",
            "diff_ids": ([sha256_bytes(b"wrong"), diff_ids[1]] if mode == "diffid-mismatch" else diff_ids),
        },
    }
    config_bytes = canonical_json(config)
    config_digest = sha256_bytes(config_bytes)
    config_name = "blobs/sha256/" + config_digest.removeprefix("sha256:")
    if mode == "wrong-config-name":
        config_name = "blobs/sha256/" + "f" * 64
    layer_names = [
        "blobs/sha256/" + sha256_bytes(layer).removeprefix("sha256:") for layer in layers
    ]
    repo_tags: list[str] = [tag]
    if mode == "wrong-tag":
        repo_tags = ["fixture.invalid/wrong:" + revision]
    elif mode == "missing-tag":
        repo_tags = []
    elif mode == "multiple-tags":
        repo_tags = [tag, "fixture.invalid/extra:" + revision]
    manifest_layers = layer_names
    if mode == "empty-layers":
        manifest_layers = []
    elif mode == "duplicate-layers":
        manifest_layers = [layer_names[0], layer_names[0]]
    image = {"Config": config_name, "RepoTags": repo_tags, "Layers": manifest_layers}
    manifest_entries = [image, dict(image)] if mode == "multiple-manifests" else [image]
    manifest_bytes = canonical_json(manifest_entries)
    oci_descriptors = [
        {
            "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
            "digest": sha256_bytes(layer),
            "size": len(layer),
        }
        for layer in layers
    ]
    oci_manifest = {
        "schemaVersion": 2,
        "mediaType": "application/vnd.oci.image.manifest.v1+json",
        "config": {"digest": config_digest, "size": len(config_bytes)},
        "layers": oci_descriptors,
    }
    if mode == "wrong-oci-config-digest":
        oci_manifest["config"]["digest"] = "sha256:" + "e" * 64
    elif mode == "wrong-oci-layer-digest":
        oci_manifest["layers"][0]["digest"] = "sha256:" + "e" * 64
    elif mode == "wrong-oci-layer-size":
        oci_manifest["layers"][0]["size"] += 1
    oci_manifest_bytes = canonical_json(oci_manifest)
    image_id = sha256_bytes(oci_manifest_bytes)
    index_manifest_size = len(oci_manifest_bytes)
    if mode == "wrong-oci-manifest-size":
        index_manifest_size += 1
    index_bytes = canonical_json(
        {
            "schemaVersion": 2,
            "manifests": [{"digest": image_id, "size": index_manifest_size}],
        }
    )
    entries: list[tuple[str, bytes | None, bytes | None]] = [
        ("manifest.json", manifest_bytes, None),
        ("index.json", index_bytes, None),
        (
            "blobs/sha256/" + image_id.removeprefix("sha256:"),
            oci_manifest_bytes,
            None,
        ),
    ]
    if mode != "missing-oci-layout":
        entries.append(
            ("oci-layout", canonical_json({"imageLayoutVersion": "1.0.0"}), None)
        )
    if mode != "missing-config":
        entries.append((config_name, config_bytes if mode != "wrong-config-content" else b"{}", None))
    for name, payload in zip(layer_names, layers, strict=True):
        if mode == "missing-layer" and name == layer_names[-1]:
            continue
        entries.append((name, payload, None))
    if mode == "duplicate-tar-member":
        entries.append(("manifest.json", manifest_bytes, None))
    elif mode == "unsafe-absolute":
        entries.append(("/absolute", b"unsafe", None))
    elif mode == "dot-dot":
        entries.append(("../outside", b"unsafe", None))
    elif mode == "symlink":
        entries.append(("unsafe-link", None, b"symlink"))
    elif mode == "hardlink":
        entries.append(("unsafe-hardlink", None, b"hardlink"))
    path.write_bytes(fixture_tar(entries))
    os.chmod(path, 0o600)
    return image_id, config_digest


def build_classic_fixture(
    path: Path,
    *,
    tag: str,
    revision: str,
    source: str,
    reverse_layers: bool,
) -> tuple[str, list[str], list[str]]:
    layer_bytes = [b"classic-layer-a", b"classic-layer-b"]
    layer_names = ["classic-a/layer.tar", "classic-b/layer.tar"]
    diff_ids = [sha256_bytes(layer) for layer in layer_bytes]
    config = {
        "architecture": "amd64",
        "os": "linux",
        "config": {
            "User": "appuser",
            "Labels": {
                "org.opencontainers.image.revision": revision,
                "org.opencontainers.image.source": source,
            },
        },
        "rootfs": {"type": "layers", "diff_ids": diff_ids},
    }
    config_bytes = canonical_json(config)
    config_digest = sha256_bytes(config_bytes)
    manifest_layers = list(reversed(layer_names)) if reverse_layers else layer_names
    manifest = canonical_json(
        [
            {
                "Config": config_digest.removeprefix("sha256:") + ".json",
                "RepoTags": [tag],
                "Layers": manifest_layers,
            }
        ]
    )
    entries: list[tuple[str, bytes | None, bytes | None]] = [
        ("manifest.json", manifest, None),
        (config_digest.removeprefix("sha256:") + ".json", config_bytes, None),
    ]
    entries.extend(
        (name, payload, None)
        for name, payload in zip(layer_names, layer_bytes, strict=True)
    )
    path.write_bytes(fixture_tar(entries))
    os.chmod(path, 0o600)
    comparison_compressed = [sha256_bytes(gzip_payload(layer)) for layer in layer_bytes]
    return config_digest, diff_ids, comparison_compressed


def embedded_verifier(script: Path) -> str:
    text = script.read_text(encoding="utf-8")
    marker = 'archive_fd_text, expected_tag, expected_digest = sys.argv[1:]'
    marker_index = text.index(marker)
    start = text.rfind("import hashlib", 0, marker_index)
    end = text.index("\nPY\n}", marker_index)
    return text[start:end]


def run_embedded_verifier(
    source: str,
    archive_path: Path,
    tag: str,
    image_id: str,
) -> bool:
    with archive_path.open("rb") as stream:
        os.chmod(archive_path, 0o400)
        os.unlink(archive_path)
        result = subprocess.run(
            [sys.executable, "-", str(stream.fileno()), tag, image_id.removeprefix("sha256:")],
            input=source,
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            pass_fds=(stream.fileno(),),
            check=False,
        )
    return result.returncode == 0


def self_test(cutover_script: Path) -> None:
    revision = "a" * 40
    tag = "fixture.invalid/backend:" + revision
    source = EXPECTED_SOURCE
    embedded = embedded_verifier(cutover_script)
    invalid_modes = [
        "wrong-tag",
        "missing-tag",
        "multiple-tags",
        "wrong-config-name",
        "wrong-config-content",
        "missing-config",
        "empty-layers",
        "duplicate-layers",
        "missing-layer",
        "duplicate-tar-member",
        "unsafe-absolute",
        "dot-dot",
        "symlink",
        "hardlink",
        "multiple-manifests",
        "wrong-platform",
        "wrong-user",
        "wrong-revision",
        "wrong-source",
        "diffid-mismatch",
        "layer-order",
        "missing-oci-layout",
        "wrong-oci-manifest-size",
        "wrong-oci-config-digest",
        "wrong-oci-layer-digest",
        "wrong-oci-layer-size",
    ]
    with tempfile.TemporaryDirectory(prefix="docker-save-archive-self-test.") as temp:
        root = Path(temp)
        corpus: list[tuple[str, bool]] = [("valid", True)] + [
            (mode, False) for mode in invalid_modes
        ]
        for index, (mode, expected) in enumerate(corpus):
            fixture = root / f"{index:02d}-{mode}.tar"
            image_id, config_digest = build_fixture(
                fixture, mode, tag=tag, revision=revision, source=source
            )
            helper_result = True
            try:
                verify_archive(
                    fixture,
                    expected_tag=tag,
                    expected_image_id=image_id,
                    expected_config_digest=config_digest,
                    expected_manifest_digest=image_id,
                    expected_revision=revision,
                    expected_source=source,
                )
            except ArchiveError:
                helper_result = False
            embedded_copy = root / f"embedded-{index:02d}-{mode}.tar"
            embedded_copy.write_bytes(fixture.read_bytes())
            os.chmod(embedded_copy, 0o600)
            embedded_result = run_embedded_verifier(embedded, embedded_copy, tag, image_id)
            if helper_result != expected:
                raise AssertionError(
                    f"{mode} helper verifier result={helper_result} expected={expected}"
                )
            if embedded_result != helper_result:
                raise AssertionError(
                    f"{mode} helper/embedded verifier equivalence mismatch: "
                    f"helper={helper_result} embedded={embedded_result}"
                )

        for label, reverse_layers, expected in (
            ("classic-valid", False, True),
            ("classic-layer-order", True, False),
        ):
            fixture = root / f"classic-{label}.tar"
            image_id, diff_ids, compressed_layers = build_classic_fixture(
                fixture,
                tag=tag,
                revision=revision,
                source=source,
                reverse_layers=reverse_layers,
            )
            helper_result = True
            try:
                verify_archive(
                    fixture,
                    expected_tag=tag,
                    expected_image_id=image_id,
                    expected_config_digest=image_id,
                    expected_revision=revision,
                    expected_source=source,
                    expected_diff_ids=diff_ids,
                    expected_compressed_layers=compressed_layers,
                )
            except ArchiveError:
                helper_result = False
            embedded_copy = root / f"embedded-{label}.tar"
            embedded_copy.write_bytes(fixture.read_bytes())
            os.chmod(embedded_copy, 0o600)
            embedded_result = run_embedded_verifier(embedded, embedded_copy, tag, image_id)
            if helper_result != expected:
                raise AssertionError(
                    f"{label} helper verifier result={helper_result} expected={expected}"
                )
            if embedded_result != helper_result:
                raise AssertionError(
                    f"{label} helper/embedded verifier equivalence mismatch: "
                    f"helper={helper_result} embedded={embedded_result}"
                )

        destination = root / "published.tar"
        existing = root / "existing.tar"
        existing.write_bytes(b"existing")
        os.chmod(existing, 0o600)
        try:
            atomic_publish(existing, destination)
        except ArchiveError:
            raise AssertionError("valid atomic publication false-negative")
        if destination.read_bytes() != b"existing" or existing.exists():
            raise AssertionError("atomic publication did not preserve exact bytes")

        preexisting = root / "preexisting.tar"
        preexisting.write_bytes(b"authority")
        os.chmod(preexisting, 0o600)
        candidate = root / "candidate.tar"
        candidate.write_bytes(b"candidate")
        os.chmod(candidate, 0o600)
        try:
            atomic_publish(candidate, preexisting)
        except ArchiveError:
            pass
        else:
            raise AssertionError("pre-existing output path produced a false PASS")
        if preexisting.read_bytes() != b"authority":
            raise AssertionError("pre-existing output path was overwritten")

        interrupted_output = root / "interrupted-output.tar"
        interrupted_temp = root / ".interrupted-output.tar.run.partial"
        interrupted_temp.write_bytes(b"partial")
        os.chmod(interrupted_temp, 0o600)
        interrupted_temp.unlink()
        if interrupted_output.exists():
            raise AssertionError("interrupted export left an authoritative output path")
    print(
        "Docker-save verifier self-test passed: "
        f"{len(corpus) + 2} archive fixtures, exact embedded equivalence, and 3 publication fixtures"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    verify = subparsers.add_parser("verify")
    verify.add_argument("archive")
    verify.add_argument("--expected-tag", required=True)
    verify.add_argument("--expected-image-id", required=True)
    verify.add_argument("--expected-config-digest")
    verify.add_argument("--expected-manifest-digest")
    verify.add_argument("--expected-revision", required=True)
    verify.add_argument("--expected-source", default=EXPECTED_SOURCE)
    verify.add_argument("--comparison")
    verify.set_defaults(function=command_verify)

    fsync_parser = subparsers.add_parser("fsync")
    fsync_parser.add_argument("archive")
    fsync_parser.set_defaults(function=command_fsync)

    publish = subparsers.add_parser("publish")
    publish.add_argument("temporary")
    publish.add_argument("output")
    publish.set_defaults(function=command_publish)

    self_test_parser = subparsers.add_parser("self-test")
    self_test_parser.add_argument(
        "--cutover-script",
        default=str(Path(__file__).with_name("v126-cutover.sh")),
    )
    self_test_parser.set_defaults(
        function=lambda args: self_test(Path(args.cutover_script))
    )
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.function(args)


if __name__ == "__main__":
    try:
        main()
    except ArchiveError as error:
        print(f"Docker-save archive error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
