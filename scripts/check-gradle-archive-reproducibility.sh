#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
temp_root="${TMPDIR:-/tmp}"
evidence_root="$(mktemp -d "${temp_root%/}/hookah-gradle-repro.XXXXXX")"

cleanup() {
  case "$evidence_root" in
    "${temp_root%/}"/hookah-gradle-repro.*)
      rm -rf -- "$evidence_root"
      ;;
    *)
      printf 'Refusing to remove unexpected evidence path\n' >&2
      return 1
      ;;
  esac
}
trap cleanup EXIT

cd "$repo_root"

run_build() {
  local label="$1"

  printf 'Gradle reproducibility build %s: clean, no build cache, rerun tasks\n' "$label"
  ./gradlew \
    --no-daemon \
    --max-workers=1 \
    --no-build-cache \
    :backend:app:clean \
    :backend:app:installDist \
    --rerun-tasks \
    --console=plain

  python3 - \
    "$label" \
    "backend/app/build/libs" \
    "backend/app/build/install/app" \
    "$evidence_root" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys

label, archive_dir_arg, install_dir_arg, evidence_dir_arg = sys.argv[1:]
archive_dir = Path(archive_dir_arg)
install_dir = Path(install_dir_arg)
evidence_dir = Path(evidence_dir_arg)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


archives = sorted(
    path
    for path in archive_dir.glob("*.jar")
    if path.is_file() and not path.is_symlink()
)
if len(archives) != 1:
    raise SystemExit(
        f"build {label}: expected exactly one application JAR, found {len(archives)}"
    )

archive = archives[0]
installed_archive = install_dir / "lib" / archive.name
if not installed_archive.is_file() or installed_archive.is_symlink():
    raise SystemExit(
        f"build {label}: distribution application JAR is missing or not a regular file"
    )

archive_hash = sha256(archive)
installed_archive_hash = sha256(installed_archive)
if archive_hash != installed_archive_hash:
    raise SystemExit(f"build {label}: generated and installed application JAR differ")

manifest = []
for current_root, directory_names, file_names in os.walk(
    install_dir,
    topdown=True,
    followlinks=False,
):
    directory_names.sort()
    file_names.sort()
    current = Path(current_root)
    for name in directory_names + file_names:
        path = current / name
        relative = path.relative_to(install_dir).as_posix()
        raw_mode = path.lstat().st_mode
        mode = f"{stat.S_IMODE(raw_mode):04o}"
        if stat.S_ISREG(raw_mode):
            manifest.append(
                {"path": relative, "type": "file", "mode": mode, "sha256": sha256(path)}
            )
        elif stat.S_ISDIR(raw_mode):
            manifest.append(
                {"path": relative, "type": "directory", "mode": mode, "sha256": None}
            )
        elif stat.S_ISLNK(raw_mode):
            manifest.append(
                {"path": relative, "type": "symlink", "mode": mode, "sha256": None}
            )
        else:
            manifest.append(
                {"path": relative, "type": "unexpected", "mode": mode, "sha256": None}
            )

manifest.sort(key=lambda entry: entry["path"])
snapshot = {
    "archive_name": archive.name,
    "archive_sha256": archive_hash,
    "manifest": manifest,
}
(evidence_dir / f"{label}.json").write_text(
    json.dumps(snapshot, sort_keys=True, separators=(",", ":")),
    encoding="utf-8",
)
shutil.copyfile(archive, evidence_dir / f"{label}.jar")

print(f"build={label} archive={archive.name} sha256={archive_hash}")
print(
    f"build={label} distribution_regular_files="
    f"{sum(entry['type'] == 'file' for entry in manifest)}"
)
PY
}

run_build A

# ZIP stores timestamps at two-second precision. This guarantees that a task which
# still preserves wall-clock timestamps cannot pass by landing in the same interval.
sleep 3

run_build B

python3 - "$evidence_root" <<'PY'
import hashlib
import json
from pathlib import Path
import stat
import sys
import zipfile

evidence_dir = Path(sys.argv[1])


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


snapshots = {
    label: json.loads((evidence_dir / f"{label}.json").read_text(encoding="utf-8"))
    for label in ("A", "B")
}
jars = {label: evidence_dir / f"{label}.jar" for label in ("A", "B")}
problems = []
entry_names_equal = False
entry_order_equal = False
timestamps_reproducible = False
layout_expected = True
symlink_count = 0

if snapshots["A"]["archive_name"] != snapshots["B"]["archive_name"]:
    problems.append("application JAR names differ")

jar_hashes = {label: sha256(path) for label, path in jars.items()}
if jar_hashes["A"] != jar_hashes["B"]:
    problems.append("raw application JAR SHA-256 differs")

with zipfile.ZipFile(jars["A"]) as archive_a, zipfile.ZipFile(jars["B"]) as archive_b:
    entries_a = archive_a.infolist()
    entries_b = archive_b.infolist()
    names_a = [entry.filename for entry in entries_a]
    names_b = [entry.filename for entry in entries_b]
    entry_names_equal = sorted(names_a) == sorted(names_b)
    entry_order_equal = names_a == names_b

    if not entry_names_equal:
        problems.append("application JAR entry names differ")
    if not entry_order_equal:
        problems.append("application JAR entry order differs")
    if len(names_a) != len(set(names_a)) or len(names_b) != len(set(names_b)):
        problems.append("application JAR contains duplicate entry names")

    reproducible_timestamp = (1980, 2, 1, 0, 0, 0)
    timestamps_a_reproducible = all(
        entry.date_time == reproducible_timestamp for entry in entries_a
    )
    timestamps_b_reproducible = all(
        entry.date_time == reproducible_timestamp for entry in entries_b
    )
    timestamps_reproducible = timestamps_a_reproducible and timestamps_b_reproducible
    if not timestamps_a_reproducible:
        problems.append("build A JAR preserves non-reproducible entry timestamps")
    if not timestamps_b_reproducible:
        problems.append("build B JAR preserves non-reproducible entry timestamps")

install_manifest_equal = snapshots["A"]["manifest"] == snapshots["B"]["manifest"]
if not install_manifest_equal:
    problems.append("complete installDist file/type/mode/SHA-256 manifest differs")

for label, snapshot in snapshots.items():
    manifest = snapshot["manifest"]
    entries_by_path = {entry["path"]: entry for entry in manifest}
    symlinks = [entry["path"] for entry in manifest if entry["type"] == "symlink"]
    unexpected_types = [
        entry["path"] for entry in manifest if entry["type"] == "unexpected"
    ]
    unexpected_paths = [
        entry["path"]
        for entry in manifest
        if not (
            entry["path"] in {"bin", "lib", "bin/app", "bin/app.bat"}
            or (entry["path"].startswith("lib/") and entry["path"].endswith(".jar"))
        )
    ]

    if symlinks:
        symlink_count += len(symlinks)
        layout_expected = False
        problems.append(f"build {label} distribution contains symlinks")
    if unexpected_types:
        layout_expected = False
        problems.append(f"build {label} distribution contains unexpected file types")
    if unexpected_paths:
        layout_expected = False
        problems.append(f"build {label} distribution contains unexpected paths")

    if entries_by_path.get("bin", {}).get("type") != "directory":
        layout_expected = False
        problems.append(f"build {label} distribution bin is not a directory")
    if entries_by_path.get("lib", {}).get("type") != "directory":
        layout_expected = False
        problems.append(f"build {label} distribution lib is not a directory")

    for launcher_path in ("bin/app", "bin/app.bat"):
        executable = entries_by_path.get(launcher_path)
        if executable is None or executable["type"] != "file" or executable["mode"] != "0755":
            layout_expected = False
            problems.append(f"build {label} launcher mode is not 0755: {launcher_path}")

    for entry in manifest:
        if entry["type"] == "file" and entry["path"] not in {"bin/app", "bin/app.bat"}:
            if entry["mode"] != "0644":
                layout_expected = False
                problems.append(
                    f"build {label} non-launcher regular file mode is not 0644: "
                    f"{entry['path']}"
                )

    installed_archive = entries_by_path.get(
        f"lib/{snapshot['archive_name']}"
    )
    if installed_archive is None or installed_archive.get("sha256") != jar_hashes[label]:
        layout_expected = False
        problems.append(f"build {label} installed application JAR does not match archive output")

regular_files = sum(
    entry["type"] == "file" for entry in snapshots["A"]["manifest"]
)
entry_count = 0
with zipfile.ZipFile(jars["A"]) as archive:
    entry_count = len(archive.infolist())

print(f"archive_sha256_A={jar_hashes['A']}")
print(f"archive_sha256_B={jar_hashes['B']}")
print(f"archive_entries={entry_count}")
print(f"archive_entry_names={'equal' if entry_names_equal else 'different'}")
print(f"archive_entry_order={'equal' if entry_order_equal else 'different'}")
print(
    "archive_entry_timestamps="
    + ("1980-02-01T00:00:00" if timestamps_reproducible else "non_reproducible")
)
print(f"installDist_regular_files={regular_files}")
print(f"installDist_manifest={'equal' if install_manifest_equal else 'different'}")
print(f"installDist_types_and_modes={'expected' if layout_expected else 'unexpected'}")
print(f"installDist_symlinks={symlink_count}")

if problems:
    raise SystemExit("Gradle archive reproducibility check failed:\n- " + "\n- ".join(problems))

print("Gradle archive reproducibility check passed")
PY
