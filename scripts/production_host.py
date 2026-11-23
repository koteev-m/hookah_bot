"""Host controls shared by the existing production operator entry points."""
from contextlib import contextmanager
from contextvars import ContextVar
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import time
import subprocess

from simple_staging import LOCK, require

SLICE = "hookahproduction.slice"
GATE = Path("/var/lib/hookah-production/ingress/open")
MIB = 1024**2
LIMITS = {"backend": (768, 750_000_000), "postgres": (512, 500_000_000), "pdf-worker": (384, 1_000_000_000)}
# Policy margin per filesystem for neighbours, logs and growth during an operation.
RESERVE = 2 * 1024**3


_lease = ContextVar("production_operator_lease", default=None)


@contextmanager
def shared_lock(path=LOCK, wait=30):
    # Same existing inode and flock protocol as simple_staging.shared_lock. The
    # caller also needs its open description to protect a surviving Docker CLI.
    original = path.lstat()
    fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        def validate():
            st = os.fstat(fd)
            named = path.lstat()
            require(os.path.samestat(original, st) and os.path.samestat(named, st) and
                    stat.S_ISREG(st.st_mode) and st.st_uid == 0 and st.st_nlink == 1 and
                    stat.S_IMODE(st.st_mode) == 0o660, "shared Docker lock identity or permissions changed")
            require(hasattr(os, "listxattr") and not os.listxattr(fd), "unverified shared Docker lock attributes")

        deadline = time.monotonic() + wait
        while True:
            validate()
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                require(time.monotonic() < deadline, "shared Docker lock busy")
                time.sleep(.1)
        validate()
        yield fd, validate
        validate()
    finally:
        os.close(fd)


def lock_args():
    lease = _lease.get()
    require(lease is not None, "Docker mutation requires the shared and production locks")
    shared, private, validate = lease
    validate()
    return {"pass_fds": (shared, private)}


@contextmanager
def operator_lock(gate=GATE):
    require(os.getenv("DOCKER_HOST") == "unix:///var/run/docker.sock" and not os.getenv("DOCKER_CONTEXT"),
            "select the local system Docker socket before taking the shared host lock")
    private = gate.parent / ".operator.lock"
    with shared_lock() as (fd, check_shared):
        lock = os.open(private, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
        try:
            def validate():
                check_shared()
                st = os.fstat(lock)
                require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() and st.st_nlink == 1 and
                        not st.st_mode & 0o022 and os.path.samestat(st, private.lstat()), "invalid production lock")
            validate()
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            token = _lease.set((fd, lock, validate))
            try:
                yield
            finally:
                _lease.reset(token)
        finally:
            os.close(lock)


def verify_slice():
    driver = subprocess.check_output(["docker", "info", "--format", "{{.CgroupVersion}}/{{.CgroupDriver}}"], text=True)
    require(driver.strip() == "2/systemd", "production requires local cgroup v2 with systemd driver")
    path = Path("/sys/fs/cgroup") / SLICE
    limit, period = (path / "cpu.max").read_text().split()
    require(limit.isdecimal() and int(period) > 0 and 4 * int(limit) == 5 * int(period),
            "production slice must enforce 1.25 CPU")


def plan_images(services):
    require(set(services) in ({"backend", "postgres"}, set(LIMITS)), "unexpected production services")
    for service, config in services.items():
        memory, cpu = LIMITS[service]
        require(config.get("cgroup_parent") == SLICE and float(config.get("cpus", 0)) * 1_000_000_000 == cpu and
                int(config.get("mem_limit", 0)) == int(config.get("memswap_limit", 0)) == memory * MIB,
                "admit a shared-host plan before starting " + service)
    return {config["image"] for config in services.values()}


def verify_container(service, data):
    memory, cpu = LIMITS[service]
    host = data["HostConfig"]
    require(host.get("CgroupParent") == SLICE and host.get("Memory") == memory * MIB and
            host.get("MemorySwap") == memory * MIB and host.get("NanoCpus") == cpu,
            f"{service} resource limit mismatch")
    pid = data["State"]["Pid"]
    group = f"{SLICE}/docker-{data['Id']}.scope"
    require(type(pid) is int and pid > 0 and
            f"0::/{group}" in Path(f"/proc/{pid}/cgroup").read_text().splitlines(),
            f"{service} outside production slice")
    leaf = Path("/sys/fs/cgroup") / group
    quota, period = (leaf / "cpu.max").read_text().split()
    require(quota.isdecimal() and int(period) > 0 and int(quota) * 1_000_000_000 == cpu * int(period) and
            (leaf / "memory.max").read_text().strip() == str(memory * MIB) and
            (leaf / "memory.swap.max").read_text().strip() == "0", f"{service} kernel limits differ")


def docker_storage():
    # Read only the selected daemon's storage fields (Docker info may contain proxy credentials).
    template = "{{.Driver}}\n{{.DockerRootDir}}\n{{json .DriverStatus}}\n{{json .Containerd}}"
    driver, directory, status, endpoint = subprocess.check_output(
        ["docker", "info", "--format", template], text=True).strip().splitlines()
    root = Path(directory)
    roots = [root]
    ctr = []
    if driver == "overlay2" and dict(json.loads(status)).get("driver-type") != "io.containerd.snapshotter.v1":
        roots.extend([root / "overlay2", root / "image/overlay2"])
    else:
        require(driver == "overlayfs" and dict(json.loads(status)).get("driver-type") == "io.containerd.snapshotter.v1",
                "unsupported Docker image store")
        info = json.loads(endpoint)
        address = Path(info["Address"])
        require(address.is_absolute() and stat.S_ISSOCK(address.stat().st_mode), "unverified containerd endpoint")
        ctr = ["ctr", "--address", str(address), "--namespace", info["Namespaces"]["Containers"]]
        for kind, plugin in (("content", "content"), ("snapshotter", "overlayfs")):
            output = subprocess.check_output(ctr + ["plugins", "ls", "-d",
                f"type==io.containerd.{kind}.v1,id=={plugin}"], text=True)
            exports = re.findall(r"^\s+root\s+(/[^\n]+?)\s*$", output, re.MULTILINE)
            require(len(exports) == 1 and "Error:" not in output, "cannot establish active containerd " + kind + " root")
            path = Path(exports[0])
            roots.append(path)
            # Account for separate mounts at the actual blob/ingest or snapshot directories.
            for sub in (("blobs", "blobs/sha256", "ingest") if kind == "content" else ("snapshots",)):
                child = path / sub
                if child.exists():
                    roots.append(child)
    require(all(path.is_absolute() and path.is_dir() for path in roots), "cannot measure active image filesystems")
    return driver, roots, ctr


def classic_pull_tmp(root):
    # dockerd may put compressed temporary files on a different mount. Resolve
    # DOCKER_TMPDIR in its own mount namespace, without logging daemon settings.
    pid = subprocess.check_output(["systemctl", "show", "--value", "-p", "MainPID", "docker.service"], text=True).strip()
    require(pid.isdecimal() and int(pid) > 1, "cannot identify Docker service for cold pull")
    process = Path("/proc") / pid
    require((process / "comm").read_text().strip() == "dockerd" and
            (process / "root" / root.relative_to("/")).samefile(root), "unverified Docker storage namespace")
    override = [v.partition(b"=")[2] for v in (process / "environ").read_bytes().split(b"\x00")
                if v.startswith(b"DOCKER_TMPDIR=")]
    require(len(override) <= 1, "ambiguous Docker temporary storage")
    directory = Path(os.fsdecode(override[0])) if override and override[0] else root / "tmp"
    require(directory.is_absolute(), "absolute Docker temporary storage path required")
    live = process / "root" / directory.relative_to("/")
    require(live.is_dir(), "cannot measure active Docker temporary storage")
    return live


def cached_image_size(ref, ctr):
    result = subprocess.run(["docker", "image", "inspect", "--platform", "linux/amd64", ref], capture_output=True)
    if result.returncode:
        return None
    image, = json.loads(result.stdout)
    require(ref in [name.removeprefix("docker.io/library/") for name in image["RepoDigests"]]
            and (image["Os"], image["Architecture"]) == ("linux", "amd64"), "cached image identity mismatch")
    if ctr:
        layers = image["RootFS"]["Layers"]
        require(layers and all(re.fullmatch(r"sha256:[0-9a-f]{64}", layer) for layer in layers), "invalid image layers")
        chain = layers[0]
        for layer in layers[1:]:
            chain = "sha256:" + hashlib.sha256((chain + " " + layer).encode()).hexdigest()
        snapshot = subprocess.run(ctr + ["snapshots", "--snapshotter", "overlayfs", "info", chain], capture_output=True)
        if snapshot.returncode:
            return None
    nominal = image["Size"]
    require(type(nominal) is int and nominal > 0, "missing Engine image accounting")
    return nominal


def check_space(allocations):
    """Sum simultaneous allocations on each backing filesystem; count its reserve once."""
    devices = {}
    for path, size in allocations:
        require(type(size) is int and size >= 0, "unknown production disk budget")
        device = path.stat().st_dev
        devices[device] = (path, devices.get(device, (None, 0))[1] + size)
    for path, required in devices.values():
        free = shutil.disk_usage(path).free
        require(free >= required + RESERVE,
                f"insufficient space on {path}: {free} bytes free, need {required + RESERVE}")


def image_space(refs, measurements=None, allocations=(), *, reserve_release=False):
    measured = {}
    if measurements:
        st = measurements.lstat()
        require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() and not st.st_mode & 0o022,
                "operator-owned image qualification file required")
        measured = json.loads(measurements.read_bytes())
    refs = set(refs)
    require(isinstance(measured, dict) and set(measured) <= refs, "unexpected image size evidence")
    driver, roots, ctr = docker_storage()
    cached = {ref: cached_image_size(ref, ctr) for ref in refs}
    missing = {ref for ref, size in cached.items() if size is None}
    # Preserve the existing extra release margin. Engine Size is only the floor
    # for cached images; it never qualifies a pull or estimates extraction.
    margin = sum(size for size in cached.values() if size is not None)
    budget = 0
    for ref in missing:
        sample = measured.get(ref)
        require(isinstance(sample, dict) and sample.get("platform") == "linux/amd64" and sample.get("driver") == driver and
                all(type(sample.get(k)) is int and sample[k] > 0 for k in ("packed_bytes", "unpacked_bytes")),
                "missing qualified packed/unpacked measurements for " + ref)
        # Allow concurrent compressed staging and unpack work. Charge every missing
        # image in full. Existing shared layers and previous releases remain in place.
        margin += max(sample["packed_bytes"], sample["unpacked_bytes"])
        budget += 2 * (sample["packed_bytes"] + sample["unpacked_bytes"])
    if reserve_release:
        budget += margin
    if missing and driver == "overlay2":
        roots = [*roots, classic_pull_tmp(roots[0])]
    devices = {p.stat().st_dev: p for p in roots}
    check_space([*((p, budget) for p in devices.values()), *allocations])
    return missing


def local_volume_path(name, existing):
    root = Path(subprocess.check_output(["docker", "info", "--format", "{{.DockerRootDir}}"], text=True).strip())
    pid = subprocess.check_output(["systemctl", "show", "--value", "-p", "MainPID", "docker.service"], text=True).strip()
    require(root.is_absolute() and pid.isdecimal() and int(pid) > 1, "unknown Docker volume filesystem")
    process = Path("/proc") / pid
    require((process / "comm").read_text().strip() == "dockerd" and
            (process / "root" / root.relative_to("/")).samefile(root), "unknown Docker volume namespace")
    # stat in the daemon mount namespace: volumes can be a distinct mount from DockerRootDir.
    pool = process / "root" / root.relative_to("/") / "volumes"
    require(pool.is_dir(), "Docker local volume directory unavailable")
    if existing is None:
        require(not os.path.lexists(pool / name), "unregistered PostgreSQL volume path")
        return pool
    mount = root / "volumes" / name / "_data"
    require(existing[1] == str(mount), "unexpected PostgreSQL local volume mountpoint")
    target = pool / name / "_data"
    require(target.is_dir() and not target.is_symlink() and not target.parent.is_symlink(), "unknown PostgreSQL data path")
    return target


def bootstrap_space(plan, doc, existing):
    volume = doc["volumes"]["pgdata"]
    require(volume["name"] == "hookah_production_pgdata" and volume.get("driver", "local") == "local"
            and not volume.get("driver_opts") and not volume.get("external"), "unsupported PostgreSQL volume")
    evidence = plan.parent / "pgdata-budget.json"
    require(evidence.is_file(), "missing PostgreSQL bootstrap disk qualification")
    st = evidence.lstat()
    require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() and not st.st_mode & 0o022,
            "untrusted PostgreSQL bootstrap disk qualification")
    sample = json.loads(evidence.read_bytes())
    require(isinstance(sample, dict) and sample.get("platform") == "linux/amd64" and
            all(sample.get(service) == doc["services"][service]["image"] for service in ("postgres", "backend")),
            "PostgreSQL bootstrap qualification belongs to another release")
    capacity = sample.get("qualified_capacity_bytes")
    require(type(capacity) is int and capacity > 0, "unknown PostgreSQL bootstrap footprint")
    # Capacity of the bounded filesystem that completed initdb and the migrations,
    # plus the same amount of working room. check_space adds the shared 2 GiB floor.
    return local_volume_path(volume["name"], existing), 2 * capacity


def database_space(cid):
    sql = ("SELECT pg_database_size(current_database()), COALESCE(sum(size),0)::bigint FROM pg_ls_waldir()")
    raw = subprocess.check_output(["docker", "exec", cid, "psql", "-XqAtw", "-v", "ON_ERROR_STOP=1",
                                   "-U", "postgres", "-d", "hookah_prod", "-c", sql], text=True)
    db, wal = map(int, raw.strip().split("|"))
    require(db > 0 and wal >= 0, "cannot measure database and WAL")
    mounts = json.loads(subprocess.check_output(["docker", "inspect", "--format", "{{json .Mounts}}", cid]))
    paths = [Path(m["Source"]) for m in mounts if m["Destination"] == "/var/lib/postgresql/data" and m["Type"] == "volume"]
    require(len(paths) == 1 and paths[0].is_absolute(), "cannot measure PostgreSQL volume filesystem")
    return paths[0], db, wal
