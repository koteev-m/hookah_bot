# V126 repair operation protocol and binding retirement

This records the pre-implementation F05 design and its feature-validation extension for HT-RELEASE-REPAIR-01. It supplements
the canonical cutover contract; it is not authority to run against staging.

The existing verified stdin dispatcher will supervise each remote action using a
Linux child subreaper and one nonblocking `flock` on a persistent lock file under
the canonical target directory's `.v126-target-operations` (operator-owned 0700).
The lock inode is never removed. Its scope is the real target path, independent
of SSH alias, local state directory or run. The protocol adds no service, account,
daemon, access grant or lease.

Each dispatched action gets create-only, fsynced, mode-0400 start/result JSON plus
a private operation log, bound to run/release/script/intent/action and a SHA-256
operation ID. A result acknowledges command status and that all descendants have
been reaped. It is not a stage receipt or evidence that failed mutations had no
effect. Local transport must return zero and provide the exact acknowledgement
before artifact lines may be consumed. A successful remote result after transport
loss does not authorize replay or a fabricated local PASS.

The supervisor holds the target lock until all children, including detached and
orphaned grandchildren, finish. Output is retained on the target so broken SSH
stdout does not kill the mutation. A monotonic deadline bounds work and cleanup;
timeout is UNKNOWN with respect to side effects. Only the supervisor's own
descendants may be signalled. If cleanup cannot prove no children remain, no
terminal outcome is written. Missing or malformed outcomes block all subsequent
mutations, including recovery, even after a process exits or a host reboots.

SIGHUP does not cancel a supervised action. SIGTERM/INT trigger bounded child
cleanup; SIGKILL/crash/reboot leaves the start record unresolved. Boot ID and PID
are diagnostics, never completion authority. No lease expiration, PID scan or
automatic removal of records/locks can clear an unresolved action. A separate
operator reconciliation/fencing decision is required. Logs and records are
retained; no automatic cleanup of historical evidence is added.

One target is bound to exactly one current run. Different local state directories
cannot bypass it. Repeated action IDs are rejected. Every nonzero or timed-out
operation remains UNKNOWN even after all descendants are reaped: an external Docker,
database or systemd daemon may still be acting. Stage10 prepare/load actions share an
intent and require predecessor action completion.

## Explicit inspection and retirement

`inspect-target --target <canonical-local-path>` acquires the same nonblocking target
lock and validates run/transfer/operation/log metadata, hashes and identities. Its
`COMMAND_RESULTS_VERIFIED` outcome describes command results only. It performs no SSH,
health probe, daemon mutation or receipt repair. An unavailable lock, malformed record,
missing result or nonzero action prevents retirement. UNKNOWN reports
`EXTERNAL_DAEMON_FENCING_DECISION_REQUIRED`, `retry_allowed=false`.

`retire-target` is a separate explicit command executed where the target filesystem
and source-bound local run evidence are available. It requires all of:

1. Exact executing sequencer/run/release/target equality, valid immutable read attempts
   limited to NOT_STARTED or proven NOT_DISPATCHED, and no invalid evidence.
2. V126: real complete20 receipt chain and canonical COMPLETE. V125: verified native
   pre-v126 recovery receipt, canonical terminal marker and verified exact predecessor
   chain/hash. V125 does not require V126 manual17 or claim a stage9 PASS.
3. Every operation for the current binding has a successful immutable result and
   matching log; the corresponding terminal remote action exists. No unresolved,
   failed, detached or daemon-unknown action can be retired.
4. A protected canonical handoff JSON with exact owner/next-owner, terminal receipt
   digest, canonical target digest, V125/V126 version, exact full-SHA backend image and
   exact expected image ID, env/Compose/active-Caddy hashes, root:root,
   `restart_policy=unless-stopped`, explicit `handoff_approved_and_applied=true`, approval
   identity and UTC observation time. These are operator attestations to a separately
   approved and applied handoff, not fresh observations made by the retirement tool.
5. Explicit next run/release/source identities and the exact
   `AUTHORIZE_V126_TARGET_BINDING_RETIREMENT` token. This token is not an authorization
   to apply env/restart/ownership changes. No live invocation is currently authorized.

The complete field schema is source-bound in `scripts/v126-operation-bindings.py`,
embedded byte-for-byte in the sequencer. Retirement appends one mode0400 canonical,
fsynced `transfers/<previous-owner-digest>.json` under an operator-owned0700 directory.
It records previous/next owner, full completed operation inventory hashes and handoff.
`run.json`, all old starts/results/logs and the permanent lock inode remain unchanged.
Every subsequent dispatch revalidates the entire transfer chain and retired inventories;
new entries for retired owners, reused run IDs or unlinked records refuse execution.
The newly bound run must start with its own fresh BASELINE_VERIFIED; it cannot reuse a
PASS or start directly with recovery. No source, receipt or operation record is reset.

A crash while appending retirement can leave invalid/truncated evidence, which blocks
future mutation. An uncertain command outcome must be inspected; no retry can assume
that append was absent. Lifetime is indefinite, without TTL, automatic cleanup, lock
replacement, or reboot-based adoption. Successful process/SSH tests are not VM reboot
or daemon crash durability proof.

## OPEN: UNKNOWN reconciliation authority

Inspection is available; clearing an UNKNOWN daemon mutation is deliberately absent.
A separate reconciliation decision must identify the exact run/source/intent/operation,
archive unchanged start/result/log/transport evidence, and establish the actual effects
and absence of continuing work in each involved daemon (Docker/container/image,
PostgreSQL connection/transaction/schema, Caddy active configuration and service manager).
The proposed evidence must either prove operation-specific completion against the
original receipt contract or prove an explicit effective fence plus the resulting
state. PID death, child reaping, elapsed time, SSH exit and boot ID cannot supply this
proof. Retrying the action or recovery, fabricating PASS, deleting intent, resetting
binding or substituting a new run remains forbidden while outcome is unknown.

**Decision item F05-UNKNOWN:** approve an operation-specific fencing/reconciliation
procedure and the minimum isolated VM/daemon crash capabilities needed to validate it.
No new remote service, privilege or infrastructure is introduced here. Since that
procedure is not implemented and verified, F05 is not closed by successful retirement
of a known completed synthetic run.

Existing runs have no protocol records. They retain their original source-bound
interpretation and cannot be adopted, upgraded or assigned synthetic completion.
The historical PASS1–8/intent9 run remains frozen. Linux/SSH disconnect, detached
children, kill/reboot and cross-state-directory tests are required before claiming
runtime verification; macOS fixtures alone cannot close that gap.
