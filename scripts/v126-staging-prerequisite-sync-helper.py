#!/usr/bin/env python3
"""Durable, byte-exact primitives for the tracked V126 prerequisite sync."""

from __future__ import annotations

import ctypes
import hashlib
import json
import os
import re
import stat
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import NoReturn


CONTRACT_VERSION = "HT12R_PREREQUISITE_SYNC_V1"
MAINTENANCE = (
    (b"STAGING_MAINTENANCE_MODE", b"OFF"),
    (b"STAGING_MAINTENANCE_ALLOWED_USER_IDS", b""),
    (b"STAGING_MAINTENANCE_ALLOWED_CHAT_IDS", b""),
)
PRODUCT_KEYS = (
    b"TELEGRAM_PRODUCT_ALLOWED_USER_IDS",
    b"TELEGRAM_PRODUCT_ALLOWED_CHAT_IDS",
    b"TELEGRAM_ALLOWED_USER_IDS",
    b"TELEGRAM_ALLOWED_CHAT_IDS",
)


def fail(message: str) -> NoReturn:
    raise SystemExit(message)


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def path_bytes(path: Path) -> bytes:
    try:
        info = path.lstat()
    except FileNotFoundError:
        fail(f"missing file: {path}")
    if not stat.S_ISREG(info.st_mode) or path.is_symlink():
        fail(f"not a regular non-symlink file: {path}")
    return path.read_bytes()


def fsync_parent(path: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    directory_fd = os.open(path.parent, flags)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def promote_no_replace(source: Path, target: Path) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    if sys.platform.startswith("linux"):
        function = libc.renameat2
        function.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        rc = function(-100, os.fsencode(source), -100, os.fsencode(target), 1)
    elif sys.platform == "darwin":
        function = libc.renamex_np
        function.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        rc = function(os.fsencode(source), os.fsencode(target), 4)
    else:
        fail("atomic no-replace rename is unsupported on this platform")
    if rc != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), str(target))


def exclusive_bytes(target: Path, payload: bytes, mode: int = 0o400) -> None:
    if target.is_symlink() or target.exists():
        fail(f"exclusive target already exists: {target}")
    if not target.parent.is_dir() or target.parent.is_symlink():
        fail(f"exclusive target parent is unsafe: {target.parent}")
    temp = target.parent / f".{target.name}.{os.getpid()}.tmp"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(temp, flags, 0o600)
    try:
        with os.fdopen(fd, "wb", closefd=False) as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.fchmod(fd, mode)
    finally:
        os.close(fd)
    try:
        promote_no_replace(temp, target)
        fsync_parent(target)
    finally:
        if temp.exists() and not temp.is_symlink():
            temp.unlink()
    info = target.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != mode:
        fail(f"exclusive target mode/type mismatch: {target}")
    if path_bytes(target) != payload:
        fail(f"exclusive target bytes mismatch: {target}")


def canonical_record(target: Path, document: dict[str, object]) -> None:
    document = {"contract": CONTRACT_VERSION, **document}
    payload = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
    exclusive_bytes(target, payload, 0o400)


def load_record(path: Path) -> tuple[dict[str, object], bytes]:
    raw = path_bytes(path)
    if stat.S_IMODE(path.lstat().st_mode) != 0o400:
        fail(f"checkpoint is not mode 0400: {path}")
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as error:
        fail(f"checkpoint JSON is malformed: {error}")
    if not isinstance(document, dict) or document.get("contract") != CONTRACT_VERSION:
        fail("checkpoint contract mismatch")
    canonical = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode()
    if raw != canonical:
        fail("checkpoint is not canonical JSON")
    return document, raw


def require_hex(value: str, label: str, size: int = 64) -> None:
    if re.fullmatch(rf"[0-9a-f]{{{size}}}", value) is None:
        fail(f"invalid {label}")


def checkpoint_start(argv: list[str]) -> None:
    if len(argv) != 9:
        fail("checkpoint-start arguments mismatch")
    target, run_id, ordinal, name, release_sha, script_sha, expected, predecessor = argv[1:]
    require_hex(release_sha, "release SHA", 40)
    require_hex(script_sha, "script SHA-256")
    if predecessor != "NONE":
        require_hex(predecessor, "predecessor result hash")
    canonical_record(
        Path(target),
        {
            "expected_sanitized_result": expected,
            "name": name,
            "ordinal": ordinal,
            "predecessor_result_sha256": predecessor,
            "release_sha": release_sha,
            "run_id": run_id,
            "script_sha256": script_sha,
            "started_at": utc_now(),
            "state": "STARTED",
        },
    )


def checkpoint_finish(argv: list[str]) -> None:
    if len(argv) != 14:
        fail("checkpoint-finish arguments mismatch")
    (
        target,
        run_id,
        ordinal,
        name,
        release_sha,
        script_sha,
        expected,
        predecessor,
        started_path,
        exit_status,
        stdout_path,
        stderr_path,
        state,
    ) = argv[1:]
    if state not in ("PASSED", "FAILED"):
        fail("checkpoint state must be PASSED or FAILED")
    status = int(exit_status)
    if (state == "PASSED") != (status == 0):
        fail("checkpoint state and exit status disagree")
    started, started_raw = load_record(Path(started_path))
    required = {
        "run_id": run_id,
        "ordinal": ordinal,
        "name": name,
        "release_sha": release_sha,
        "script_sha256": script_sha,
        "expected_sanitized_result": expected,
        "predecessor_result_sha256": predecessor,
        "state": "STARTED",
    }
    if any(started.get(key) != value for key, value in required.items()):
        fail("STARTED checkpoint binding mismatch")
    stdout = Path(stdout_path)
    stderr = Path(stderr_path)
    stdout_raw = path_bytes(stdout)
    stderr_raw = path_bytes(stderr)
    if stat.S_IMODE(stdout.lstat().st_mode) != 0o600 or stat.S_IMODE(stderr.lstat().st_mode) != 0o600:
        fail("checkpoint captures must be mode 0600")
    if state == "PASSED":
        lines = stdout_raw.splitlines()
        if not lines or lines[-1] != expected.encode():
            fail("passed checkpoint sanitized result mismatch")
    canonical_record(
        Path(target),
        {
            "completed_at": utc_now(),
            "exit_status": status,
            "expected_sanitized_result": expected,
            "name": name,
            "ordinal": ordinal,
            "predecessor_result_sha256": predecessor,
            "release_sha": release_sha,
            "run_id": run_id,
            "script_sha256": script_sha,
            "started_record_sha256": sha256(started_raw),
            "state": state,
            "stderr_sha256": sha256(stderr_raw),
            "stdout_sha256": sha256(stdout_raw),
        },
    )


def first_failure(argv: list[str]) -> None:
    if len(argv) != 13:
        fail("first-failure arguments mismatch")
    (
        target,
        run_id,
        ordinal,
        name,
        release_sha,
        script_sha,
        exit_status,
        signal_name,
        predecessor,
        stdout_sha,
        stderr_sha,
        last_passed,
    ) = argv[1:]
    require_hex(stdout_sha, "stdout SHA-256")
    require_hex(stderr_sha, "stderr SHA-256")
    status = int(exit_status)
    if status == 0:
        fail("first failure exit status must be nonzero")
    if signal_name not in ("NONE", "SIGINT", "SIGTERM", "EXIT"):
        fail("first failure signal is invalid")
    if predecessor != "NONE":
        require_hex(predecessor, "predecessor result hash")
    canonical_record(
        Path(target),
        {
            "exit_status": status,
            "first_failed_name": name,
            "first_failed_ordinal": ordinal,
            "last_passed": last_passed,
            "predecessor_result_sha256": predecessor,
            "reason_category": "CHECK_OR_PHASE_FAILURE",
            "release_sha": release_sha,
            "run_id": run_id,
            "script_sha256": script_sha,
            "signal": signal_name,
            "state": "FIRST_FAILURE",
            "stderr_sha256": stderr_sha,
            "stdout_sha256": stdout_sha,
            "timestamp": utc_now(),
        },
    )


def verify_first_failure(argv: list[str]) -> None:
    if len(argv) != 6:
        fail("verify-first-failure arguments mismatch")
    target, run_id, release_sha, script_sha, exit_status = argv[1:]
    status = int(exit_status)
    if status == 0:
        fail("first failure expected status must be nonzero")
    path = Path(target)
    if path.name != "first-failure.json":
        fail("first failure path mismatch")
    document, raw = load_record(path)
    stable = {
        "contract": CONTRACT_VERSION,
        "exit_status": status,
        "reason_category": "CHECK_OR_PHASE_FAILURE",
        "release_sha": release_sha,
        "run_id": run_id,
        "script_sha256": script_sha,
        "state": "FIRST_FAILURE",
    }
    if any(document.get(key) != value for key, value in stable.items()):
        fail("first failure binding mismatch")
    expected_keys = set(stable) | {
        "first_failed_name",
        "first_failed_ordinal",
        "last_passed",
        "predecessor_result_sha256",
        "signal",
        "stderr_sha256",
        "stdout_sha256",
        "timestamp",
    }
    if set(document) != expected_keys:
        fail("first failure fields mismatch")
    ordinal = document.get("first_failed_ordinal")
    name = document.get("first_failed_name")
    last_passed = document.get("last_passed")
    signal_name = document.get("signal")
    predecessor = document.get("predecessor_result_sha256")
    if not isinstance(ordinal, str) or re.fullmatch(r"(?:L[0-9]{2}|R[0-9]{2}|C[0-9]{2}|SIGNAL)", ordinal) is None:
        fail("first failure ordinal mismatch")
    if not isinstance(name, str) or re.fullmatch(r"[A-Z][A-Z0-9_]*", name) is None:
        fail("first failure name mismatch")
    if signal_name not in ("NONE", "SIGINT", "SIGTERM", "EXIT"):
        fail("first failure signal mismatch")
    for field in ("stderr_sha256", "stdout_sha256"):
        value = document.get(field)
        if not isinstance(value, str):
            fail(f"first failure {field} mismatch")
        require_hex(value, field)
    timestamp = document.get("timestamp")
    if not isinstance(timestamp, str) or re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", timestamp
    ) is None:
        fail("first failure timestamp mismatch")

    if predecessor == "NONE":
        remote_phases = phase_specs(path.parent)
        checks = remote_phases[7:47]
        local_specs: dict[str, tuple[str, str]] = {
            "L13": ("SYNC_WRITE_COMPOSE_COMPLETED", "L12:ADMISSION_GUARD_ABSENT"),
            "L14": ("SYNC_WRITE_MAINTENANCE_GUARD_COMPLETED", "L13:SYNC_WRITE_COMPOSE_COMPLETED"),
            "L15": ("SYNC_WRITE_ADMISSION_GUARD_COMPLETED", "L14:SYNC_WRITE_MAINTENANCE_GUARD_COMPLETED"),
            "L16": ("SYNC_WRITE_ENV_COMPLETED", "L15:SYNC_WRITE_ADMISSION_GUARD_COMPLETED"),
            "L17": ("CANONICAL_SUCCESS_PERSISTED", f"C40:{checks[-1][1]}"),
        }
        for index, (check_ordinal, check_name, _) in enumerate(checks):
            previous = "L16:SYNC_WRITE_ENV_COMPLETED" if index == 0 else f"{checks[index - 1][0]}:{checks[index - 1][1]}"
            local_specs[check_ordinal] = (check_name, previous)
        if ordinal == "SIGNAL":
            allowed_last_passed = {value[1] for value in local_specs.values()} | {
                f"{key}:{value[0]}" for key, value in local_specs.items()
            }
            if (
                name != "CONTROLLER_SIGNAL"
                or signal_name not in ("SIGINT", "SIGTERM")
                or last_passed not in allowed_last_passed
            ):
                fail("controller signal first failure binding mismatch")
        else:
            expected_local = local_specs.get(str(ordinal))
            if expected_local is None or (name, last_passed) != expected_local:
                fail("controller first failure phase binding mismatch")
    else:
        if not isinstance(predecessor, str):
            fail("remote first failure predecessor mismatch")
        require_hex(predecessor, "remote first failure predecessor")
        root = path.parent
        matching = [spec for spec in phase_specs(root) if spec[0] == ordinal]
        if len(matching) != 1:
            fail("remote first failure phase is unknown")
        _, phase_name, phase_expected = matching[0]
        expected_predecessor, previous_path = predecessor_binding(
            root, run_id, release_sha, script_sha, str(ordinal)
        )
        if name != phase_name or predecessor != expected_predecessor:
            fail("remote first failure phase binding mismatch")
        if not isinstance(last_passed, str) or Path(last_passed) != previous_path:
            fail("remote first failure previous-result path mismatch")
        failed = root / "checkpoints" / "phases" / f"{ordinal}-{name}.failed.json"
        failed_document = verify_failed_checkpoint_bound(
            failed,
            run_id,
            str(ordinal),
            name,
            release_sha,
            script_sha,
            phase_expected,
            predecessor,
            status,
        )
        if (
            document["stdout_sha256"] != failed_document["stdout_sha256"]
            or document["stderr_sha256"] != failed_document["stderr_sha256"]
            or signal_name != "NONE"
        ):
            fail("remote first failure capture or signal binding mismatch")
    print(sha256(raw))


def verify_failed_checkpoint_bound(
    path: Path,
    run_id: str,
    ordinal: str,
    name: str,
    release_sha: str,
    script_sha: str,
    expected: str,
    predecessor: str,
    status: int,
) -> dict[str, object]:
    if path.name != f"{ordinal}-{name}.failed.json":
        fail("failed checkpoint path mismatch")
    document, _ = load_record(path)
    stable = {
        "contract": CONTRACT_VERSION,
        "exit_status": status,
        "expected_sanitized_result": expected,
        "name": name,
        "ordinal": ordinal,
        "predecessor_result_sha256": predecessor,
        "release_sha": release_sha,
        "run_id": run_id,
        "script_sha256": script_sha,
        "state": "FAILED",
    }
    if any(document.get(key) != value for key, value in stable.items()):
        fail("failed checkpoint binding mismatch")
    expected_keys = set(stable) | {
        "completed_at",
        "started_record_sha256",
        "stderr_sha256",
        "stdout_sha256",
    }
    if set(document) != expected_keys:
        fail("failed checkpoint fields mismatch")
    timestamp = document.get("completed_at")
    if not isinstance(timestamp, str) or re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", timestamp
    ) is None:
        fail("failed checkpoint completion timestamp mismatch")
    for field in ("started_record_sha256", "stderr_sha256", "stdout_sha256"):
        value = document.get(field)
        if not isinstance(value, str):
            fail(f"failed checkpoint {field} mismatch")
        require_hex(value, field)
    started_path = path.with_name(f"{ordinal}-{name}.started.json")
    started, started_raw = load_record(started_path)
    started_stable = {
        "contract": CONTRACT_VERSION,
        "expected_sanitized_result": expected,
        "name": name,
        "ordinal": ordinal,
        "predecessor_result_sha256": predecessor,
        "release_sha": release_sha,
        "run_id": run_id,
        "script_sha256": script_sha,
        "state": "STARTED",
    }
    if any(started.get(key) != value for key, value in started_stable.items()):
        fail("failed checkpoint STARTED binding mismatch")
    if set(started) != set(started_stable) | {"started_at"}:
        fail("failed checkpoint STARTED fields mismatch")
    started_at = started.get("started_at")
    if not isinstance(started_at, str) or re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", started_at
    ) is None:
        fail("failed checkpoint STARTED timestamp mismatch")
    if document["started_record_sha256"] != sha256(started_raw):
        fail("failed checkpoint STARTED hash mismatch")
    raw_root = path.parents[2] / "raw"
    stdout_path = raw_root / f"{ordinal}-{name}.stdout"
    stderr_path = raw_root / f"{ordinal}-{name}.stderr"
    stdout_raw = path_bytes(stdout_path)
    stderr_raw = path_bytes(stderr_path)
    if stat.S_IMODE(stdout_path.lstat().st_mode) != 0o600 or stat.S_IMODE(stderr_path.lstat().st_mode) != 0o600:
        fail("failed checkpoint captures must be mode 0600")
    if document["stdout_sha256"] != sha256(stdout_raw) or document["stderr_sha256"] != sha256(stderr_raw):
        fail("failed checkpoint capture hash mismatch")
    return document


def event(argv: list[str]) -> None:
    if len(argv) != 9:
        fail("event arguments mismatch")
    target, run_id, name, release_sha, script_sha, status, detail, predecessor = argv[1:]
    if predecessor != "NONE":
        require_hex(predecessor, "predecessor result hash")
    canonical_record(
        Path(target),
        {
            "detail": detail,
            "exit_status": int(status),
            "name": name,
            "predecessor_result_sha256": predecessor,
            "release_sha": release_sha,
            "run_id": run_id,
            "script_sha256": script_sha,
            "state": name,
            "timestamp": utc_now(),
        },
    )


def verify_record(argv: list[str]) -> None:
    if len(argv) not in (2, 5):
        fail("verify-record arguments mismatch")
    document, raw = load_record(Path(argv[1]))
    if len(argv) == 5:
        run_id, release_sha, script_sha = argv[2:]
        required = {
            "run_id": run_id,
            "release_sha": release_sha,
            "script_sha256": script_sha,
        }
        if any(document.get(key) != value for key, value in required.items()):
            fail("checkpoint belongs to another run or source")
    print(sha256(raw))


def verify_allocation_value(path: Path, run_id: str, release_sha: str, script_sha: str) -> str:
    if path.name != "allocation.json":
        fail("allocation checkpoint path mismatch")
    document, raw = load_record(path)
    expected = {
        "contract": CONTRACT_VERSION,
        "name": "EVIDENCE_ALLOCATED",
        "release_sha": release_sha,
        "run_id": run_id,
        "script_sha256": script_sha,
        "state": "PASSED",
    }
    if document != expected:
        fail("allocation checkpoint binding mismatch")
    return sha256(raw)


def verify_allocation(argv: list[str]) -> None:
    if len(argv) != 5:
        fail("verify-allocation arguments mismatch")
    target, run_id, release_sha, script_sha = argv[1:]
    print(verify_allocation_value(Path(target), run_id, release_sha, script_sha))


def verify_checkpoint_bound(
    target: str,
    run_id: str,
    ordinal: str,
    name: str,
    release_sha: str,
    script_sha: str,
    expected: str,
    predecessor: str,
) -> str:
    if predecessor != "NONE":
        require_hex(predecessor, "predecessor result hash")
    path = Path(target)
    if path.name != f"{ordinal}-{name}.passed.json":
        fail("passed checkpoint path mismatch")
    document, raw = load_record(path)
    stable = {
        "contract": CONTRACT_VERSION,
        "exit_status": 0,
        "expected_sanitized_result": expected,
        "name": name,
        "ordinal": ordinal,
        "predecessor_result_sha256": predecessor,
        "release_sha": release_sha,
        "run_id": run_id,
        "script_sha256": script_sha,
        "state": "PASSED",
    }
    if any(document.get(key) != value for key, value in stable.items()):
        fail("passed checkpoint binding mismatch")
    expected_keys = set(stable) | {
        "completed_at",
        "started_record_sha256",
        "stderr_sha256",
        "stdout_sha256",
    }
    if set(document) != expected_keys:
        fail("passed checkpoint fields mismatch")
    timestamp = document.get("completed_at")
    if not isinstance(timestamp, str) or re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", timestamp) is None:
        fail("passed checkpoint completion timestamp mismatch")
    for field in ("started_record_sha256", "stderr_sha256", "stdout_sha256"):
        value = document.get(field)
        if not isinstance(value, str):
            fail(f"passed checkpoint {field} mismatch")
        require_hex(value, field)

    started_path = path.with_name(f"{ordinal}-{name}.started.json")
    started, started_raw = load_record(started_path)
    started_stable = {
        "contract": CONTRACT_VERSION,
        "expected_sanitized_result": expected,
        "name": name,
        "ordinal": ordinal,
        "predecessor_result_sha256": predecessor,
        "release_sha": release_sha,
        "run_id": run_id,
        "script_sha256": script_sha,
        "state": "STARTED",
    }
    if any(started.get(key) != value for key, value in started_stable.items()):
        fail("STARTED checkpoint binding mismatch")
    if set(started) != set(started_stable) | {"started_at"}:
        fail("STARTED checkpoint fields mismatch")
    started_at = started.get("started_at")
    if not isinstance(started_at, str) or re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", started_at) is None:
        fail("STARTED checkpoint timestamp mismatch")
    if document["started_record_sha256"] != sha256(started_raw):
        fail("STARTED checkpoint hash mismatch")

    try:
        run_root = path.parents[2]
    except IndexError:
        fail("passed checkpoint location mismatch")
    if path.parent != run_root / "checkpoints" / "phases":
        fail("passed checkpoint location mismatch")
    stdout_path = run_root / "raw" / f"{ordinal}-{name}.stdout"
    stderr_path = run_root / "raw" / f"{ordinal}-{name}.stderr"
    stdout_raw = path_bytes(stdout_path)
    stderr_raw = path_bytes(stderr_path)
    if stat.S_IMODE(stdout_path.lstat().st_mode) != 0o600 or stat.S_IMODE(stderr_path.lstat().st_mode) != 0o600:
        fail("passed checkpoint captures must be mode 0600")
    if document["stdout_sha256"] != sha256(stdout_raw) or document["stderr_sha256"] != sha256(stderr_raw):
        fail("passed checkpoint capture hash mismatch")
    lines = stdout_raw.splitlines()
    if not lines or lines[-1] != expected.encode():
        fail("passed checkpoint sanitized result mismatch")
    return sha256(raw)


def verify_checkpoint(argv: list[str]) -> None:
    if len(argv) != 9:
        fail("verify-checkpoint arguments mismatch")
    print(verify_checkpoint_bound(*argv[1:]))


def phase_specs(root: Path) -> list[tuple[str, str, str]]:
    phases: list[tuple[str, str, str]] = [
        ("R01", "SOURCE_CLOSURE_VERIFIED", "SOURCE_CLOSURE=VERIFIED"),
        ("R02", "ROLLBACK_INPUTS_CAPTURED", "ROLLBACK_INPUTS=CAPTURED"),
        ("R03", "ADMISSION_GUARD_ABSENT", "ADMISSION_GUARD=ABSENT"),
        ("R04", "SYNC_WRITE_COMPOSE", "SYNC_WRITE_1=COMPOSE"),
        ("R05", "SYNC_WRITE_MAINTENANCE_GUARD", "SYNC_WRITE_2=MAINTENANCE_GUARD"),
        ("R06", "SYNC_WRITE_ADMISSION_GUARD", "SYNC_WRITE_3=ADMISSION_GUARD"),
        ("R07", "SYNC_WRITE_ENV", "SYNC_WRITE_4=ENV"),
    ]
    check_map = root / "source" / "checks.tsv"
    rows = path_bytes(check_map).decode("utf-8").splitlines()
    if len(rows) != 40:
        fail("check map cardinality mismatch")
    seen_names: set[str] = set()
    for number, row in enumerate(rows, 1):
        fields = row.split("\t")
        if (
            len(fields) != 3
            or fields[0] != str(number)
            or re.fullmatch(r"[A-Z][A-Z0-9_]*", fields[1]) is None
            or fields[1] in seen_names
            or re.fullmatch(r"[A-Za-z0-9_./:;=,-]+", fields[2]) is None
        ):
            fail("check map binding mismatch")
        seen_names.add(fields[1])
        phases.append((f"C{number:02d}", fields[1], fields[2]))
    phases.append(("R08", "CANONICAL_SUCCESS", "PREREQUISITE_SYNC=PASS"))
    return phases


def predecessor_binding(
    root: Path, run_id: str, release_sha: str, script_sha: str, before_ordinal: str
) -> tuple[str, Path]:
    allocation_path = root / "allocation.json"
    allocation_hash = verify_allocation_value(allocation_path, run_id, release_sha, script_sha)
    phases = phase_specs(root)
    known = {ordinal for ordinal, _, _ in phases}
    if before_ordinal not in known:
        fail("predecessor endpoint is unknown")
    index = next(i for i, item in enumerate(phases) if item[0] == before_ordinal)
    if index == 0:
        return allocation_hash, allocation_path
    previous_ordinal, previous_name, previous_expected = phases[index - 1]
    if index == 1:
        expected_predecessor = allocation_hash
    else:
        prior_ordinal, prior_name, _ = phases[index - 2]
        prior_path = root / "checkpoints" / "phases" / f"{prior_ordinal}-{prior_name}.passed.json"
        _, prior_raw = load_record(prior_path)
        expected_predecessor = sha256(prior_raw)
    previous_path = root / "checkpoints" / "phases" / f"{previous_ordinal}-{previous_name}.passed.json"
    return (
        verify_checkpoint_bound(
            str(previous_path),
            run_id,
            previous_ordinal,
            previous_name,
            release_sha,
            script_sha,
            previous_expected,
            expected_predecessor,
        ),
        previous_path,
    )


def verify_predecessor(argv: list[str]) -> None:
    if len(argv) != 6:
        fail("verify-predecessor arguments mismatch")
    root_text, run_id, release_sha, script_sha, before_ordinal = argv[1:]
    predecessor, _ = predecessor_binding(Path(root_text), run_id, release_sha, script_sha, before_ordinal)
    print(predecessor)


def verify_chain(argv: list[str]) -> None:
    if len(argv) != 6:
        fail("verify-chain arguments mismatch")
    root_text, run_id, release_sha, script_sha, before_ordinal = argv[1:]
    root = Path(root_text)
    predecessor = verify_allocation_value(root / "allocation.json", run_id, release_sha, script_sha)
    phases = phase_specs(root)
    known = {ordinal for ordinal, _, _ in phases}
    if before_ordinal not in known:
        fail("chain endpoint is unknown")
    for ordinal, name, expected in phases:
        if ordinal == before_ordinal:
            print(predecessor)
            return
        target = root / "checkpoints" / "phases" / f"{ordinal}-{name}.passed.json"
        predecessor = verify_checkpoint_bound(
            str(target), run_id, ordinal, name, release_sha, script_sha, expected, predecessor
        )
    fail("chain endpoint was not reached")


def key_lines(raw: bytes, key: bytes) -> list[bytes]:
    return [line for line in raw.splitlines(keepends=True) if line.split(b"=", 1)[0] == key]


def derive_env(source: bytes) -> bytes:
    if b"\0" in source:
        fail("environment contains NUL")
    for key, _ in MAINTENANCE:
        if key_lines(source, key):
            fail(f"{key.decode()} must be absent before synchronization")
    separator = b"" if not source or source.endswith((b"\n", b"\r")) else b"\n"
    block = b"\n".join(key + b"=" + value for key, value in MAINTENANCE) + b"\n"
    return source + separator + block


def product_projection(raw: bytes) -> bytes:
    return b"".join(
        line for line in raw.splitlines(keepends=True) if line.split(b"=", 1)[0] in PRODUCT_KEYS
    )


def verify_env_bytes(source: bytes, candidate: bytes) -> None:
    expected = derive_env(source)
    if candidate != expected or not candidate.startswith(source):
        fail("environment is not the exact byte-preserving transformation")
    for key, value in MAINTENANCE:
        lines = key_lines(candidate, key)
        if len(lines) != 1:
            fail(f"{key.decode()} does not occur exactly once")
        observed = lines[0].rstrip(b"\r\n").split(b"=", 1)[1]
        if observed != value:
            fail(f"{key.decode()} has a noncanonical value")
    if product_projection(source) != product_projection(candidate):
        fail("PRODUCT-list bytes changed")


def env_command(argv: list[str]) -> None:
    if len(argv) != 3:
        fail("environment command arguments mismatch")
    action = argv[0]
    source_name, target_name = argv[1:]
    source = path_bytes(Path(source_name))
    target = Path(target_name)
    if action == "env-derive":
        candidate = derive_env(source)
        exclusive_bytes(target, candidate, 0o400)
        verify_env_bytes(source, candidate)
    elif action == "env-verify":
        candidate = path_bytes(target)
        verify_env_bytes(source, candidate)
    else:
        fail("unknown environment command")
    print(f"ENV=BYTE_PRESERVED;KEYS=1/1/1;VALUES=OFF/EMPTY/EMPTY;SHA256={sha256(candidate)}")


def one_or_absent(values: dict[str, list[str]], key: str) -> str | None:
    found = values.get(key, [])
    if len(found) > 1:
        fail(f"duplicate runtime key: {key}")
    return found[0] if found else None


def semantically_empty(value: str | None) -> bool:
    return value is None or value == ""


def runtime_env() -> None:
    try:
        rows = json.load(sys.stdin)
    except json.JSONDecodeError as error:
        fail(f"runtime environment JSON is malformed: {error}")
    if not isinstance(rows, list) or any(not isinstance(row, str) or "=" not in row for row in rows):
        fail("runtime environment inventory is malformed")
    values: dict[str, list[str]] = {}
    for row in rows:
        key, value = row.split("=", 1)
        values.setdefault(key, []).append(value)
    if one_or_absent(values, "TELEGRAM_TRAFFIC_POLICY") != "PRODUCT":
        fail("runtime traffic policy is not PRODUCT")
    if one_or_absent(values, "TELEGRAM_BOT_ENABLED") != "true":
        fail("Telegram bot is not enabled")
    if one_or_absent(values, "TELEGRAM_BOT_MODE") != "long_polling":
        fail("Telegram bot is not in long-polling mode")
    product_user_keys = ("TELEGRAM_PRODUCT_ALLOWED_USER_IDS", "TELEGRAM_ALLOWED_USER_IDS")
    product_chat_keys = ("TELEGRAM_PRODUCT_ALLOWED_CHAT_IDS", "TELEGRAM_ALLOWED_CHAT_IDS")
    for key in product_user_keys + product_chat_keys:
        if not semantically_empty(one_or_absent(values, key)):
            fail("PRODUCT list is nonempty or malformed")
    mode = one_or_absent(values, "STAGING_MAINTENANCE_MODE")
    if mode is not None and mode not in ("", "OFF"):
        fail("maintenance mode is not semantically OFF")
    for key in ("STAGING_MAINTENANCE_ALLOWED_USER_IDS", "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS"):
        if not semantically_empty(one_or_absent(values, key)):
            fail("maintenance list is nonempty or malformed")
    smoke = one_or_absent(values, "STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED")
    if smoke is not None and smoke not in ("", "false"):
        fail("V126_SMOKE authorization is active")
    print("PRODUCT;OFF;PRODUCT_LISTS=0/0;MAINTENANCE_LISTS=0/0;V126_SMOKE=INACTIVE;POLLER=1")


def caddy_version(argv: list[str]) -> None:
    if len(argv) != 3:
        fail("caddy-version requires expected and observed values")
    expected, observed = argv[1:]
    if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", expected) is None:
        fail("expected Caddy version is malformed")
    if "\n" in observed or "\r" in observed:
        fail("Caddy version output contains multiple lines")
    tokens = observed.split()
    if not tokens:
        fail("Caddy version output is empty")
    match = re.fullmatch(r"v?([0-9]+\.[0-9]+\.[0-9]+)", tokens[0])
    if match is None or match.group(1) != expected:
        fail("Caddy semantic version mismatch")
    metadata_token = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:+/=~@-]*")
    version_token = re.compile(r"v?[0-9]+\.[0-9]+\.[0-9]+")
    for token in tokens[1:]:
        if metadata_token.fullmatch(token) is None or version_token.fullmatch(token) is not None:
            fail("Caddy version metadata is malformed or ambiguous")
    print(f"CADDY_VERSION={expected}")


def capture_file(argv: list[str]) -> None:
    if len(argv) != 4:
        fail("capture-file requires source, target and mode")
    source, target, mode_text = argv[1:]
    source_path = Path(source)
    info = source_path.lstat()
    if not stat.S_ISREG(info.st_mode) or source_path.is_symlink():
        fail("capture source is not a regular non-symlink file")
    raw = source_path.read_bytes()
    exclusive_bytes(Path(target), raw, int(mode_text, 8))
    print(
        json.dumps(
            {
                "gid": info.st_gid,
                "mode": f"{stat.S_IMODE(info.st_mode):04o}",
                "sha256": sha256(raw),
                "size": len(raw),
                "uid": info.st_uid,
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )


def exclusive_stdin(argv: list[str]) -> None:
    if len(argv) != 3:
        fail("exclusive-stdin requires target and mode")
    target, mode_text = argv[1:]
    payload = sys.stdin.buffer.read()
    exclusive_bytes(Path(target), payload, int(mode_text, 8))
    print(sha256(payload))


def seal_capture(argv: list[str]) -> None:
    if len(argv) != 4:
        fail("seal-capture requires source, target and mode")
    source_name, target_name, mode_text = argv[1:]
    source = Path(source_name)
    target = Path(target_name)
    if source.parent != target.parent:
        fail("capture promotion must remain in the same directory")
    info = source.lstat()
    if not stat.S_ISREG(info.st_mode) or source.is_symlink():
        fail("capture source is not a regular non-symlink file")
    if target.exists() or target.is_symlink():
        fail("capture target already exists")
    flags = os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(source, flags)
    try:
        os.fchmod(fd, int(mode_text, 8))
        os.fsync(fd)
    finally:
        os.close(fd)
    try:
        promote_no_replace(source, target)
        fsync_parent(target)
    finally:
        if source.exists() and not source.is_symlink():
            source.unlink()
    target_raw = path_bytes(target)
    target_info = target.lstat()
    if stat.S_IMODE(target_info.st_mode) != int(mode_text, 8):
        fail("sealed capture mode mismatch")
    print(sha256(target_raw))


def atomic_install(argv: list[str]) -> None:
    if len(argv) != 7:
        fail("atomic-install requires source, target, mode, uid, gid and policy")
    source_name, target_name, mode_text, uid_text, gid_text, policy = argv[1:]
    source = Path(source_name)
    target = Path(target_name)
    raw = path_bytes(source)
    if target.parent.is_symlink() or not target.parent.is_dir():
        fail("atomic install parent is unsafe")
    if policy not in ("replace", "create"):
        fail("atomic install policy is invalid")
    if policy == "create" and (target.exists() or target.is_symlink()):
        fail("create-only target exists")
    if policy == "replace":
        info = target.lstat()
        if not stat.S_ISREG(info.st_mode) or target.is_symlink():
            fail("replace target is not a regular non-symlink file")
    temp = target.parent / f".{target.name}.{os.getpid()}.next"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(temp, flags, 0o600)
    try:
        with os.fdopen(fd, "wb", closefd=False) as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.fchmod(fd, int(mode_text, 8))
        os.fchown(fd, int(uid_text), int(gid_text))
    finally:
        os.close(fd)
    try:
        if policy == "create":
            promote_no_replace(temp, target)
        else:
            os.replace(temp, target)
        fsync_parent(target)
    finally:
        if temp.exists() and not temp.is_symlink():
            temp.unlink()
    target_raw = path_bytes(target)
    info = target.lstat()
    if target_raw != raw:
        fail("installed target bytes differ from source")
    if stat.S_IMODE(info.st_mode) != int(mode_text, 8) or info.st_uid != int(uid_text) or info.st_gid != int(gid_text):
        fail("installed target metadata mismatch")
    print(sha256(raw))


def durable_unlink(argv: list[str]) -> None:
    if len(argv) != 6:
        fail("durable-unlink requires target, SHA-256, mode, uid and gid")
    target_name, expected_sha, mode_text, uid_text, gid_text = argv[1:]
    require_hex(expected_sha, "unlink SHA-256")
    target = Path(target_name)
    if target.parent.is_symlink() or not target.parent.is_dir():
        fail("durable unlink parent is unsafe")
    info = target.lstat()
    if not stat.S_ISREG(info.st_mode) or target.is_symlink():
        fail("durable unlink target is not a regular non-symlink file")
    expected_mode = int(mode_text, 8)
    expected_uid = int(uid_text)
    expected_gid = int(gid_text)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(target, flags)
    try:
        opened = os.fstat(fd)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != info.st_dev
            or opened.st_ino != info.st_ino
            or stat.S_IMODE(opened.st_mode) != expected_mode
            or opened.st_uid != expected_uid
            or opened.st_gid != expected_gid
        ):
            fail("durable unlink target identity or metadata mismatch")
        with os.fdopen(fd, "rb", closefd=False) as handle:
            raw = handle.read()
        if sha256(raw) != expected_sha:
            fail("durable unlink target bytes mismatch")
        current = target.lstat()
        if current.st_dev != opened.st_dev or current.st_ino != opened.st_ino or target.is_symlink():
            fail("durable unlink target changed during validation")
        os.unlink(target)
        fsync_parent(target)
    finally:
        os.close(fd)
    if os.path.lexists(target):
        fail("durable unlink target still exists")
    print(expected_sha)


def main() -> None:
    if len(sys.argv) < 2:
        fail("helper command is required")
    command = sys.argv[1]
    argv = sys.argv[1:]
    if command == "checkpoint-start":
        checkpoint_start(argv)
    elif command == "checkpoint-finish":
        checkpoint_finish(argv)
    elif command == "first-failure":
        first_failure(argv)
    elif command == "verify-first-failure":
        verify_first_failure(argv)
    elif command == "event":
        event(argv)
    elif command == "verify-record":
        verify_record(argv)
    elif command == "verify-allocation":
        verify_allocation(argv)
    elif command == "verify-checkpoint":
        verify_checkpoint(argv)
    elif command == "verify-chain":
        verify_chain(argv)
    elif command == "verify-predecessor":
        verify_predecessor(argv)
    elif command in ("env-derive", "env-verify"):
        env_command(argv)
    elif command == "runtime-env":
        runtime_env()
    elif command == "caddy-version":
        caddy_version(argv)
    elif command == "capture-file":
        capture_file(argv)
    elif command == "exclusive-stdin":
        exclusive_stdin(argv)
    elif command == "seal-capture":
        seal_capture(argv)
    elif command == "atomic-install":
        atomic_install(argv)
    elif command == "durable-unlink":
        durable_unlink(argv)
    elif command == "sha256" and len(argv) == 2:
        print(sha256(path_bytes(Path(argv[1]))))
    else:
        fail("unknown helper command")


if __name__ == "__main__":
    main()
