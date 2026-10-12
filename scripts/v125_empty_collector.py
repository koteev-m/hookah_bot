#!/usr/bin/env python3
"""Bounded Linux observations only. No EMPTY verdict, authority, or child processes."""
import argparse
from contextlib import contextmanager
import errno
import json
import os
import re
import stat
import sys

sys.dont_write_bytecode = True
MAX_BYTES = 262144
MAX_ENTRIES = 512
MAX_NODES = 2048
MAX_DEPTH = 2
LOCAL_FS = {'ext2', 'ext3', 'ext4', 'xfs', 'btrfs', 'tmpfs', 'ramfs', 'overlay'}
PG_MARKERS = {'PG_VERSION', 'pg_wal', 'pg_xlog', 'postmaster.pid'}
ENGINES = {'dockerd', 'containerd', 'podman', 'conmon', 'crio', 'lxc-start', 'incusd'}
DIRECTORIES = (
    ('postgres_debian', '/var/lib/postgresql'), ('postgres_redhat', '/var/lib/pgsql'),
    ('systemd_etc', '/etc/systemd/system'), ('systemd_run', '/run/systemd/system'),
    ('systemd_usr', '/usr/lib/systemd/system'), ('systemd_lib', '/lib/systemd/system'),
    ('init', '/etc/init.d'), ('cron', '/etc/cron.d'), ('cron_spool', '/var/spool/cron'),
    ('docker_storage', '/var/lib/docker'), ('containerd_storage', '/var/lib/containerd'),
    ('containers_storage', '/var/lib/containers'), ('engine_runtime', '/run'),
)


class Gap(ValueError):
    pass


def components(path):
    if (not isinstance(path, str) or len(path) > 4096 or not path.startswith('/')
            or path == '/' or any(x in ('', '.', '..') for x in path[1:].split('/'))
            or any(ord(x) < 32 for x in path)):
        raise Gap('INVALID_PATH')
    return path[1:].split('/')


@contextmanager
def directory(path):
    """Pinned dirfd walk, never follow any symlink or open a data file."""
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK
    # Avoid access-time writes on Linux real directories; EPERM is a coverage gap.
    if path != '/proc' and not path.startswith('/proc/'):
        flags |= getattr(os, 'O_NOATIME', 0)
    fd = os.open('/', flags)
    try:
        for part in components(path):
            child = os.open(part, flags, dir_fd=fd)
            os.close(fd)
            fd = child
        yield fd
    finally:
        os.close(fd)


def names(fd):
    result = []
    with os.scandir(fd) as entries:
        for entry in entries:
            if len(result) == MAX_ENTRIES:
                raise Gap('ENTRY_LIMIT')
            result.append(entry.name)
    return sorted(result)


def proc_text(path, limit=MAX_BYTES):
    """Only called with fixed proc paths / numeric PIDs; proc files have size zero.

    The existing read_regular rejects size-zero procfs files. This reader does
    not read ordinary target/history/configuration contents.
    """
    parent, leaf = path.rsplit('/', 1)
    with directory(parent) as fd:
        child = os.open(leaf, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        try:
            meta = os.fstat(child)
            if not stat.S_ISREG(meta.st_mode) or meta.st_nlink != 1 or meta.st_size > limit:
                raise Gap('UNEXPECTED_FILE')
            chunks, remaining = [], limit + 1
            while remaining:
                raw = os.read(child, remaining)
                if not raw:
                    break
                chunks.append(raw)
                remaining -= len(raw)
            raw = b''.join(chunks)
            if len(raw) > limit:
                raise Gap('BYTE_LIMIT')
            return raw.decode('ascii', errors='strict')
        finally:
            os.close(child)


def metadata(s):
    return dict(device=s.st_dev, inode=s.st_ino, mode=stat.S_IMODE(s.st_mode),
                uid=s.st_uid, gid=s.st_gid, size=s.st_size, mtime_ns=s.st_mtime_ns)


def stable(s):
    return (s.st_dev, s.st_ino, s.st_mode, s.st_mtime_ns, s.st_ctime_ns)


def reason(exc):
    if isinstance(exc, Gap):
        return str(exc)
    if isinstance(exc, PermissionError):
        return 'PERMISSION_DENIED'
    if isinstance(exc, FileNotFoundError):
        return 'MISSING_OR_DISAPPEARED'
    if isinstance(exc, OSError) and exc.errno in (errno.ELOOP, errno.ENOTDIR):
        return 'SYMLINK_OR_NOT_DIRECTORY'
    return 'UNREADABLE_OR_MALFORMED'


def mounts(pid):
    rows = []
    for line in proc_text(f'/proc/{pid}/mountinfo').splitlines():
        fields = line.split()
        if len(rows) == MAX_ENTRIES:
            raise Gap('ENTRY_LIMIT')
        if fields.count('-') != 1:
            raise Gap('MALFORMED_MOUNTINFO')
        sep = fields.index('-')
        if (sep < 6 or len(fields) != sep + 4 or not fields[0].isdigit()
                or not fields[1].isdigit() or not re.fullmatch(r'[0-9]+:[0-9]+', fields[2])
                or not fields[3].startswith('/') or not fields[4].startswith('/')):
            raise Gap('MALFORMED_MOUNTINFO')
        point = fields[4]
        if re.search(r'\\(?!040|011|012|134)', point):
            raise Gap('MALFORMED_MOUNTINFO')
        point = re.sub(r'\\(040|011|012|134)', lambda m: chr(int(m[1], 8)), point)
        rows.append((point, fields[sep + 1], fields[2], int(fields[0])))
    if not rows or len({r[3] for r in rows}) != len(rows):
        raise Gap('MALFORMED_MOUNTINFO')
    return rows


def covering(rows, path):
    candidates = [r for r in rows if r[0] == '/' or path == r[0] or path.startswith(r[0] + '/')]
    if not candidates or any(r[1] not in LOCAL_FS for r in candidates):
        raise Gap('UNKNOWN_OR_REMOTE_STORAGE')
    # Stacked mounts are ambiguous; do not guess which is visible.
    longest = max(len(r[0]) for r in candidates)
    selected = [r for r in candidates if len(r[0]) == longest]
    if len(selected) != 1:
        raise Gap('AMBIGUOUS_MOUNT')
    return selected[0]


def tree(path, mount_rows):
    components(path)
    point, fs, dev, mount_id = covering(mount_rows, path)
    counts = dict(entries=0, directories=0, regular=0, symlinks=0, special=0,
                  pg_markers=0, pg_service_names=0, engine_names=0)
    gaps = set()
    with directory(path) as rootfd:
        root = os.fstat(rootfd)
        if dev != f'{os.major(root.st_dev)}:{os.minor(root.st_dev)}':
            raise Gap('MOUNT_DEVICE_MISMATCH')
        def visit(fd, current, depth):
            before = os.fstat(fd)
            for name in names(fd):
                if counts['entries'] == MAX_NODES:
                    raise Gap('NODE_LIMIT')
                counts['entries'] += 1
                counts['pg_markers'] += name in PG_MARKERS
                counts['pg_service_names'] += 'postgres' in name.lower() or 'pgsql' in name.lower()
                counts['engine_names'] += any(x in name.lower() for x in ('docker', 'podman', 'containerd', 'crio', 'lxc', 'incus'))
                s = os.stat(name, dir_fd=fd, follow_symlinks=False)
                if stat.S_ISLNK(s.st_mode):
                    counts['symlinks'] += 1
                    gaps.add('SYMLINK_NOT_FOLLOWED')
                elif stat.S_ISREG(s.st_mode):
                    counts['regular'] += 1
                elif stat.S_ISDIR(s.st_mode):
                    counts['directories'] += 1
                    childpath = current + '/' + name
                    if depth == MAX_DEPTH:
                        gaps.add('DEPTH_LIMIT')
                    elif covering(mount_rows, childpath)[3] != mount_id or s.st_dev != root.st_dev:
                        gaps.add('NESTED_MOUNT_NOT_TRAVERSED')
                    else:
                        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK
                        flags |= getattr(os, 'O_NOATIME', 0)
                        child = os.open(name, flags, dir_fd=fd)
                        try:
                            if stable(s) != stable(os.fstat(child)):
                                raise Gap('DIRECTORY_CHANGED')
                            visit(child, childpath, depth + 1)
                        finally:
                            os.close(child)
                else:
                    counts['special'] += 1
                    gaps.add('SPECIAL_FILE_NOT_OPENED')
            if stable(before) != stable(os.fstat(fd)):
                raise Gap('DIRECTORY_CHANGED')
        try:
            visit(rootfd, path, 0)
        except (OSError, ValueError) as exc:
            gaps.add(reason(exc))
        return dict(root=metadata(root), counts=counts, filesystem=fs,
                    mount_id=mount_id, mount_device=dev, gaps=sorted(gaps))


def processes():
    with directory('/proc') as fd:
        pids = [n for n in names(fd) if re.fullmatch(r'[1-9][0-9]{0,9}', n)]
        pg = engines = observed = 0
        gaps = set()
        for pid in pids:
            try:
                comm = proc_text(f'/proc/{pid}/comm', 64).rstrip('\n')
                if not comm or '\n' in comm or '\x00' in comm:
                    raise Gap('MALFORMED_COMM')
                pg += comm in ('postgres', 'postmaster')
                engines += comm in ENGINES
                observed += 1
            except (OSError, ValueError) as exc:
                gaps.add(reason(exc))
        if pids != [n for n in names(fd) if re.fullmatch(r'[1-9][0-9]{0,9}', n)]:
            gaps.add('PROCESS_SET_CHANGED')
        return dict(visible_pids=len(pids), comm_read=observed, postgres_names=pg,
                    engine_names=engines, gaps=sorted(gaps))


def namespaces(pid):
    result = {}
    for kind in ('pid', 'mnt', 'user'):
        values = []
        for who in (pid, 1):
            with directory(f'/proc/{who}/ns') as fd:
                value = os.readlink(kind, dir_fd=fd)
            if not re.fullmatch(kind + r':\[[0-9]+\]', value):
                raise Gap('MALFORMED_NAMESPACE')
            values.append(int(value.split('[')[1][:-1]))
        result[kind] = dict(collector=values[0], visible_pid1=values[1], same=values[0] == values[1])
    return result


def collect(target_path, history_path):
    result = dict(format_version=1, project_id='Hookah_Tootah', status='OBSERVATIONS_ONLY',
                  verified_state='UNKNOWN', coverage='INCOMPLETE', execution_authorized=False,
                  operationally_qualified=False, observations=[], gaps=[
                      'HOST_NAMESPACE_AND_HONEST_KERNEL_NOT_ATTESTED',
                      'PROCESS_NAMES_ONLY_NONATOMIC_SNAPSHOT',
                      'UNMOUNTED_REMOTE_AND_OTHER_NAMESPACE_STORAGE_UNKNOWN',
                      'CONTAINER_API_INVENTORY_AND_TARGET_ATTRIBUTION_UNAVAILABLE',
                      'CUSTOM_DATA_PATHS_AND_USER_AUTOSTART_NOT_ENUMERATED',
                      'HISTORY_CONTENT_ANCESTRY_AND_RETENTION_NOT_VERIFIED',
                      'PROVIDER_CONTROL_PLANE_INDEPENDENCE_REQUIRED'])
    def observe(scope, source, fn):
        row = dict(scope=scope, source=source)
        try:
            facts = fn()
            row.update(status='PARTIAL' if facts.get('gaps') else 'OBSERVED',
                       coverage='BOUNDED_ONLY', facts=facts)
        except (OSError, ValueError) as exc:
            row.update(status='UNKNOWN', coverage='NOT_OBSERVED', reason=reason(exc))
        result['observations'].append(row)
        return row.get('facts')
    if sys.platform != 'linux':
        result['gaps'].append('UNSUPPORTED_PLATFORM')
        return result
    for path in (target_path, history_path):
        components(path)
    pid = os.getpid()
    def host():
        boot = proc_text('/proc/sys/kernel/random/boot_id', 64).strip()
        if not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', boot):
            raise Gap('MALFORMED_BOOT_ID')
        uname = os.uname()
        return dict(boot_id=boot, architecture=uname.machine if uname.machine in
                    ('x86_64', 'aarch64', 'armv7l', 'riscv64', 'ppc64le', 's390x') else 'OTHER',
                    collector_pid=pid, uid=os.getuid(), euid=os.geteuid())
    observe('host_visible_to_collector', 'uname/getuid/getpid; /proc/sys/kernel/random/boot_id', host)
    observe('collector_and_visible_pid1_namespaces', '/proc/{collector_pid,1}/ns/{pid,mnt,user}', lambda: namespaces(pid))
    rows = []
    def storage():
        rows.extend(mounts(pid))
        return dict(visible_mounts=len(rows), unknown_filesystem_mounts=sum(r[1] not in LOCAL_FS for r in rows))
    observe('collector_mount_namespace', '/proc/collector_pid/mountinfo; counts only, no paths/options', storage)
    def block_devices():
        with directory('/sys/class/block') as fd:
            entries = names(fd)
            kinds = [os.stat(n, dir_fd=fd, follow_symlinks=False).st_mode for n in entries]
            return dict(visible_entries=len(entries), symlinks=sum(stat.S_ISLNK(m) for m in kinds),
                        gaps=['DEVICE_CONTENTS_AND_UNATTACHED_STORAGE_NOT_OBSERVED'])
    observe('visible_block_class_metadata', '/sys/class/block; no link traversal/device reads', block_devices)
    observe('visible_process_names_only', '/proc/[numeric_pid]/comm; no argv/environ', processes)
    for scope, path in (('target_metadata', target_path), ('history_metadata', history_path), *DIRECTORIES):
        source = scope + '_argument' if scope in ('target_metadata', 'history_metadata') else path
        observe(scope, source + '; bounded directory metadata only', lambda p=path: tree(p, rows))
    result['gaps'].append('NO_GLOBAL_EMPTY_VERDICT_FROM_BOUNDED_OBSERVATIONS')
    return result


class Arguments(argparse.ArgumentParser):
    def error(self, message):
        raise Gap('INVALID_ARGUMENTS')


def main(argv=None):
    parser = Arguments(description=__doc__)
    parser.add_argument('--target-path', required=True)
    parser.add_argument('--history-path', required=True)
    try:
        args = parser.parse_args(argv)
        result = collect(args.target_path, args.history_path)
    except (OSError, ValueError):
        result = dict(status='OBSERVATIONS_ONLY', verified_state='UNKNOWN', coverage='INCOMPLETE',
                      execution_authorized=False, operationally_qualified=False, gaps=['INVALID_INPUT'])
    print(json.dumps(result, sort_keys=True, separators=(',', ':')))
    return 2  # No exit-0 qualification, including apparently quiet fixtures.


if __name__ == '__main__':
    sys.exit(main())
