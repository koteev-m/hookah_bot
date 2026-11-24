"""Publish scheduled encrypted online points, or pull complete points to the operator's external disk."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import os
from pathlib import Path
import plistlib
import re
import stat
import subprocess
import sys
import tempfile

import media_backup
from production_profile import Refused, require, sync_directory
from restore_test import coordinate, verify_archive

STAMP = "full-%Y%m%dT%H%M%SZ"
NAME = re.compile(r"full-[0-9]{8}T[0-9]{6}Z")
MEMBERS = {"database.dump.age", "media.tar.age", "schema-version", "postgres-image", "source-coordinate", "SHA256SUMS"}
VOLUME = Path("/Volumes/TOSHIBA EXT")
SOURCE = "ht-production-backup-read:./"


def private_directory(path):
    mode = path.lstat()
    require(path.resolve() == path.absolute() and stat.S_ISDIR(mode.st_mode) and mode.st_uid == os.geteuid()
            and not mode.st_mode & 0o022, "backup directory must be operator-owned and protected against replacement")


@contextmanager
def exclusive(directory):
    private_directory(directory)
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(fd)


def members(point):
    require(point.is_dir() and not point.is_symlink() and {p.name for p in point.iterdir()} == MEMBERS,
            "unexpected full point contents")
    for f in point.iterdir():
        require(stat.S_ISREG(f.lstat().st_mode) and f.stat().st_nlink == 1, "unexpected full point member")


def verified(point, pin=None):
    members(point)
    verify_archive(point, (point / "postgres-image").read_text().strip(), pin)
    coordinate(point)
    return hashlib.sha256((point / "SHA256SUMS").read_bytes()).hexdigest()


def publish(point, dest, *, shared=False):
    require(not os.path.lexists(dest), "point name already occupied")
    for f in point.iterdir():
        if shared:
            f.chmod(0o640)
        with f.open("rb") as stream:
            os.fsync(stream.fileno())
    if shared:
        point.chmod(0o750)
    sync_directory(point)
    point.rename(dest)
    sync_directory(dest.parent)


def expire(directory, qualified, now):
    points = sorted(p for p in directory.iterdir() if NAME.fullmatch(p.name))
    cutoff = (now - timedelta(days=7)).strftime(STAMP)
    old = [p for p in points[:-2] if p.name < cutoff]
    if not old:
        return
    # Independently restore-tested, off-site custody pins, kept outside the rsync export.
    require(qualified.is_file() and not qualified.is_symlink(), "retention deferred: two qualified pins required")
    st = qualified.stat()
    require(st.st_uid == os.geteuid() and not st.st_mode & 0o077, "private qualification file required")
    pins = {}
    for line in qualified.read_text().splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  (full-[0-9]{8}T[0-9]{6}Z)", line)
        require(match and match[2] not in pins, "invalid qualified point record")
        pins[match[2]] = match[1]
    require(len(pins) == 2, "two distinct qualified points required")
    for name, pin in pins.items():
        verified(directory / name, pin)
    for p in old:
        if p.name not in pins and p.name < max(pins):
            # A crash during removal must not leave a published partial point.
            members(p)
            with tempfile.TemporaryDirectory(dir=directory, prefix=".expired-") as hidden:
                p.rename(Path(hidden) / p.name)
                sync_directory(directory)
    require(not any(p.exists() and p.name > max(pins) for p in old),
            "retention deferred: renew off-site restore qualification")


def create(plan, recipient_file, spool, qualified):
    now = datetime.now(timezone.utc)
    with exclusive(spool):
        slot = now.replace(hour=now.hour // 6 * 6, minute=0, second=0, microsecond=0).strftime(STAMP)
        completed = sorted(p for p in spool.iterdir() if NAME.fullmatch(p.name) and p.name >= slot)
        if completed:
            require(len(completed) == 1 and completed[0].name <= now.strftime(STAMP), "unexpected point in current slot")
            verified(completed[0])
        else:
            dest = spool / now.strftime(STAMP)
            with tempfile.TemporaryDirectory(dir=spool, prefix=".writing-") as tmp:
                point = Path(tmp) / "point"
                media_backup.backup(plan, point, recipient_file.read_text().strip(), False, online=True)
                verified(point)
                publish(point, dest, shared=True)
            print("Published online backup", dest.name, flush=True)
        expire(spool, qualified, now)


def listing(source):
    # Only directory entries and sizes; no shell interpolation or remote-supplied file paths.
    result = subprocess.check_output(["rsync", "--list-only", source], text=True)
    return [line.split(maxsplit=4) for line in result.splitlines() if len(line.split(maxsplit=4)) == 5]


def record(state, name, pin):
    receipt = state / (name + ".sha256")
    data = pin + "  " + name + "\n"
    if receipt.exists():
        require(receipt.read_text() == data, "off-site point differs from its original receipt")
        return
    with tempfile.NamedTemporaryFile(dir=state) as tmp:
        tmp.write(data.encode())
        tmp.flush()
        os.fsync(tmp.fileno())
        os.link(tmp.name, receipt)
    sync_directory(state)


def pull_point(source, destination, state, name):
    final = destination / name
    receipt = state / (name + ".sha256")
    if final.exists() and receipt.exists():
        record(state, name, verified(final))
        return
    remote = source + name + "/"
    # Also reconcile a crash after rename: never establish trust from a pre-existing disk copy alone.
    with tempfile.TemporaryDirectory(dir=state) as tmp:
        manifest = Path(tmp) / "SHA256SUMS"
        subprocess.run(["rsync", "--max-size=2k", remote + manifest.name, str(manifest)], check=True)
        require(manifest.is_file() and 0 < manifest.stat().st_size <= 2048, "missing source manifest")
        expected = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if final.exists():
        record(state, name, verified(final, expected))
        return
    files = {n: int(size.replace(",", "")) for m, size, _, _, n in listing(remote) if m.startswith("-")}
    require(files.keys() == MEMBERS, "source is not a complete encrypted point")
    media_backup.host.check_space([(destination, sum(files.values()))])
    with tempfile.TemporaryDirectory(dir=destination, prefix=".pull-") as tmp:
        point = destination / Path(tmp).name / "point"
        point.mkdir(mode=0o700)
        subprocess.run(["rsync", "-rt", "--files-from=-", remote, str(point) + "/"],
                       input="\n".join(sorted(MEMBERS)) + "\n", text=True, check=True)
        pin = verified(point, expected)
        if receipt.exists():
            require(receipt.read_text() == pin + "  " + name + "\n", "copy conflicts with custody receipt")
        publish(point, final)
        record(state, name, pin)
        print("Verified off-site backup", name, flush=True)


def pull_points(source, destination, state):
    with exclusive(destination):
        entries = listing(source)
        names = sorted(name for mode, _, _, _, name in entries if mode.startswith("d") and NAME.fullmatch(name))
        require(names, "no published server points")
        errors = []
        for name in reversed(names):
            try:
                pull_point(source, destination, state, name)
            except (Refused, ValueError, OSError, subprocess.SubprocessError) as error:
                reason = str(error) if isinstance(error, Refused) else type(error).__name__
                errors.append(f"{name}: {reason}")
        require(not errors, "pull incomplete: " + ", ".join(errors))
        expire(destination, state / "qualified.sha256", datetime.now(timezone.utc))


def pull(uuid, state):
    private_directory(state)
    state = state.resolve()
    disk = plistlib.loads(subprocess.check_output(["/usr/sbin/diskutil", "info", "-plist", str(VOLUME)]))
    require(disk.get("VolumeUUID") == uuid and disk.get("MountPoint") == str(VOLUME)
            and disk.get("Internal") is False and disk.get("BusProtocol") == "USB" and disk.get("WritableVolume"),
            "expected writable external backup disk unavailable")
    # Pin the cwd to that actual filesystem before creating anything on it.
    os.chdir(VOLUME)
    require(os.stat(".").st_dev == os.stat(disk["DeviceNode"]).st_rdev and os.path.ismount(VOLUME),
            "external mount changed")
    directory = Path("Hookah-Production-Scheduled")
    directory.mkdir(mode=0o700, exist_ok=True)
    pull_points(SOURCE, directory, state)


def status(spool, now):
    private_directory(spool)
    media_backup.host.check_space([(spool, 0)])
    due = now - timedelta(hours=2)
    slot = due.replace(hour=due.hour // 6 * 6, minute=0, second=0, microsecond=0)
    names = [p.name for p in spool.iterdir() if NAME.fullmatch(p.name) and (p / "SHA256SUMS").is_file()]
    missing = []
    for _ in range(4):
        start, end = slot.strftime(STAMP), (slot + timedelta(hours=6)).strftime(STAMP)
        if not any(start <= name < end for name in names):
            missing.append(start)
        slot -= timedelta(hours=6)
    require(not missing, "missing scheduled UTC slots: " + ", ".join(missing))
    print("Four scheduled points present; disk reserve available")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    make = sub.add_parser("create")
    for name in ("plan", "recipient", "spool", "qualified"):
        make.add_argument(name, type=Path)
    fetch = sub.add_parser("pull")
    fetch.add_argument("volume_uuid")
    fetch.add_argument("receipt_directory", type=Path)
    sub.add_parser("status").add_argument("spool", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    if args.command == "create":
        create(args.plan, args.recipient, args.spool, args.qualified)
    elif args.command == "pull":
        pull(args.volume_uuid, args.receipt_directory)
    else:
        status(args.spool, datetime.now(timezone.utc))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        reason = str(error) if isinstance(error, Refused) else type(error).__name__
        sys.exit("Scheduled backup failed: " + reason)
