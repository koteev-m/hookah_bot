# V125 Linux EMPTY collector — local observations only

`HT-NEW-STAGING-09` / `Hookah_Tootah`: `V125_EMPTY_COLLECTOR_LOCAL_READY`.
This is a bounded observer, not an EMPTY decision, verifier, receipt, or installer.
There is no actual new target yet; local fixture PASS is not Linux target qualification.

The stdlib [collector](../scripts/v125_empty_collector.py) runs in an already started
Linux Python 3.10+ interpreter. A future separately authorized local invocation is:

```sh
python3 -B -S scripts/v125_empty_collector.py \
  --target-path /absolute/new-target --history-path /absolute/existing-history-directory
```

The two paths must be explicit, canonical absolute directory paths without symlinks.
They are operator-selected observation scopes, not authenticated target identity.
No directories need to be created: absent paths become `UNKNOWN` with
`MISSING_OR_DISAPPEARED`, never proof of an empty target. There is no output-file
option; one bounded JSON object goes to stdout. Every collection exits **2**, even
when all selected paths are accessible. `--help` is ordinary CLI help, not a verdict.
No automatic elevation, subprocess, service/container command, socket, API or SSH is used.
Use `-B -S` to suppress Python cache writes and site startup hooks. Collector imports
also disable bytecode generation; it cannot constrain unrelated interpreter startup hooks.

Every observation carries a fixed `scope`, `source`, `status` and `coverage`.
`OBSERVED / BOUNDED_ONLY` means that the stated narrow observation was readable;
`PARTIAL` retains available counts and gap codes, and `UNKNOWN / NOT_OBSERVED` means
that scope failed. Globally the output always has `coverage=INCOMPLETE`,
`verified_state=UNKNOWN`, `execution_authorized=false`, `operationally_qualified=false`.
No output asserts `EMPTY`, protected `MATCH`, approval or an operational receipt.

| Scope/source | Observations | Explicit limits |
| --- | --- | --- |
| Kernel boot ID; uname; uid/euid; current PID | Validated boot UUID, allowlisted architecture, numeric collector identity | No hostname, machine-id, provider/resource identity, runtime attestation or authenticated clock |
| `/proc/{collector_pid,1}/ns/{pid,mnt,user}` | Numeric namespace IDs and equality to visible PID 1 | PID 1 may itself be inside a container; equality does not prove host visibility |
| `/proc/collector_pid/mountinfo` | Mount count, unknown filesystem count; target/history covering mount ID/device/filesystem | No source names, mount paths or options emitted; no unmounted devices, remote attachments or other namespaces proved |
| `/sys/class/block` | Entry/symlink counts | No symlink following or raw device reads; no device ownership/attachment proof |
| `/proc/[numeric_pid]/comm` | Visible/read PID counts, exact postgres/postmaster and known engine name counts | No argv, environ, executable, cgroup, user payloads or process control; names can be changed and PIDs reused |
| Explicit target/history; `/var/lib/postgresql`, `/var/lib/pgsql` | Root stat metadata; bounded entry/type counts; PostgreSQL marker-name counts | No file content, DB identity/schema, history validation, recursive completeness or immutable retention proof |
| `/etc/systemd/system`, `/run/systemd/system`, `/usr/lib/systemd/system`, `/lib/systemd/system`, `/etc/init.d`, `/etc/cron.d`, `/var/spool/cron` | Metadata and possible PostgreSQL service-name counts | No unit/cron content, active/enabled state, custom ExecStart or user autostart inspection |
| `/var/lib/docker`, `/var/lib/containerd`, `/var/lib/containers`, `/run` | Metadata, potential engine-name and persistent-entry counts | No trustworthy container/volume inventory or target attribution; rootless/custom/unsupported engines remain unknown |

Persistent files, including `.env`, configs, registry records, DB markers and user
files, are **never read**. Even `PG_VERSION` is recognized only by its entry name.
Arbitrary filenames, process names, supplied paths, exception text and namespace link
targets are not echoed. Root stat fields are numeric; entries are aggregate counts.
Only the allowlisted proc fields above are read as bounded bytes. Their parsing is
untrusted, and no authenticity is inferred from kernel-looking values.

Bounds: 256 KiB per mountinfo read, 64 bytes per boot/comm read, 512 entries per
directory or mount table, 2 descendant-directory levels and 2,048 entries per tree.
Overflow, permission denial, malformed data, disappearance, changed directory metadata,
symlinks, special files, nested mounts, unsupported storage and scan limits produce gaps.
Some subtrees may remain unvisited after an error; retained counts are partial.
Directory opens use pinned dirfds and `O_NOFOLLOW`. Symlinks are only read for the
three exact namespace identities. FIFO/device contents are not read. Directory access
uses Linux `O_NOATIME`; denial remains a gap without a privilege-changing fallback.
Only known local filesystem types are traversed, with mount/device agreement checked;
overlay backing storage remains outside the observed scope. No claim is made against
hostile root, kernel, mount replacement, PID reuse, away-and-back races or external
writers. Read-only syscalls are not a filesystem snapshot and may be audited by the OS.

The existing `v126-dr-evidence.py:read_regular` requires a nonempty ordinary file with
stable size. It cannot read size-zero procfs streams. This collector consequently has
a narrow proc reader and metadata traversal, not another regular-file reader library,
shared authority schema or validator. Existing consumers are not imported or modified.
It does not feed observations into the
[V125 lifecycle proposal validator](V125_LIFECYCLE_VALIDATOR.md) or
[prospective authority consumers](V126_PROSPECTIVE_AUTHORITY_EPOCH.md).

Local checks (synthetic filesystem and syscall fixtures only):

```sh
python3 -B -m unittest discover -s scripts -p 'test_v125*.py' -v
python3 -B -c 'from pathlib import Path; [compile(p.read_bytes(), str(p), "exec") for p in Path("scripts").glob("*v125*.py")]'
git --no-optional-locks diff --check
```

[Collector tests](../scripts/test_v125_empty_collector.py) cover useful observations,
existing PostgreSQL/data/history indicators, incomplete inventories, inaccessible
namespaces, denied reads, malformed/oversized input, symlinks/hardlinks/FIFOs, scan
limits, concurrent changes, secret canaries, read-only open flags, prohibited process/
network/mutation calls, unchanged file bytes and refusal to qualify partial results.
The unchanged initial-install/lifecycle suites must also pass. No Docker, SQL, SSH,
provider or real Linux server operation is needed for these local checks.

Next prerequisite: separately agree independent acquisition of provider/control-plane
identity, IAM, recovery and administrative-isolation facts, together with how the
future target's Linux observations will be independently obtained and retained.
This collector cannot establish that independence or immutable ancestry. No fabricated
R0/MATCH fills the gap. Exact installation authorization remains separate, and actual
recovery/custody qualification plus Policy B remain mandatory before test access.
