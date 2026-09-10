# V126 repair operation protocol and binding retirement

This records the implemented F05 contract for HT-RELEASE-REPAIR-01. Exact feature
candidate391abc7135e74365dad69e34109b1bc2612caec7 passed CI34472343581, including the
owned terminal/reconciliation/ordinary lifecycle and actual Caddy/systemd consumers.
The separate owned Docker daemon interruption gate is still pending. REPAIR-REPORT
and REPAIR-COVERAGE bind each proof and its limits. This supplements the canonical
cutover contract; it is not authority to run against staging.

The existing verified stdin dispatcher will supervise each remote action using a
Linux child subreaper and one nonblocking `flock` on a persistent lock file under
the canonical target directory's `.v126-target-operations` (operator-owned 0700).
The lock inode is never removed. Its scope is the real target path, independent
of SSH alias, local state directory or run. The protocol adds no service, account,
daemon, access grant or lease.

Each dispatched action gets create-only, fsynced, mode-0400 start/result JSON plus
a private operation log, bound to run/release/script/intent/action and a SHA-256
operation ID. Cutover/recovery retain registry request JSON; ordinary deployment retains
its protected approved-request.json in the operation work directory, bound by the
transfer/intent descriptor digest and dedicated deployment proof. A result acknowledges command status and that all descendants have
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

Individual bounded consumers use private child stdout/stderr pipes and bounded
nonblocking forwarding under their monotonic deadline, including inside Bash command
substitution. Missing EOF, output backpressure or cancellation cannot prolong capture
indefinitely or yield success. An unreaped leader retains process-group identity during
bounded cleanup; no reaped group is signalled. This I/O helper does not prove complete
descendant lifetime or fence daemon work: the outer supervisor retains the former
responsibility, and unresolved daemon effects remain UNKNOWN.

SIGHUP does not cancel a supervised action. SIGTERM/INT trigger bounded child
cleanup; SIGKILL/crash/reboot leaves the start record unresolved. Boot ID and PID
are diagnostics, never completion authority. No lease expiration, PID scan or
automatic removal of records/locks can clear an unresolved action. A separate
operator reconciliation/fencing decision is required. Logs and records are
retained; no automatic cleanup of historical evidence is added.

One target is bound to exactly one current run. Different local state directories
cannot bypass it. Repeated action IDs are rejected. Every nonzero or timed-out
operation remains UNKNOWN even after all descendants are reaped: an external Docker,
database or systemd daemon may still be acting. Stage8 upload/preflight and stage10 prepare/upload/load share their original intent
and require the complete ordered action group. Binary uploads use the same supervised
SSH stream and target lock; no independent rsync or upload mutation occurs outside it.

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
2. V126: verified complete20 chain and canonical COMPLETE. V125: verified native or
   distinctly typed reconciled pre-v126 recovery completion, canonical terminal marker and verified exact predecessor
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
A version1 transfer still requires the next run to start with its own fresh
BASELINE_VERIFIED. A version2 transfer explicitly binds `next_kind=CUTOVER` or
`ORDINARY_DEPLOY`, exact next request hash for deployment, and terminal proof kind
`NATIVE_RECEIPT`, `RECONCILED_EFFECT` or `ORDINARY_DEPLOY_PROOF`. The next action must
match that policy. No source, receipt or operation record is reset.

A crash while appending retirement can leave invalid/truncated evidence, which blocks
future mutation. An uncertain command outcome must be inspected; no retry can assume
that append was absent. Lifetime is indefinite, without TTL, automatic cleanup, lock
replacement, or reboot-based adoption. Successful process/SSH tests are not VM reboot
or daemon crash durability proof.

## Separate exact-effect reconciliation

`reconcile --state-dir <original-local-state> --stage <original-stage>` (or
`--recovery <original-mode>`) requires the exact
`AUTHORIZE_V126_EXACT_EFFECT_RECONCILIATION` token and current source-bound original
state. It acquires the local state lock and the same remote target lock. Its only
remote work is validating retained records and bounded read-only observation.
It does not call a mutation action or retry upload/start/reload/recovery.
No live invocation is authorized by this document.

Three outcomes have different consequences:

| Evidence | Outcome and permitted next action |
| --- | --- |
| Original read attempt proves dispatch was absent | `NOT_DISPATCHED`; only the existing bounded read precheck may be repeated with new immutable attempt evidence. A missing remote result cannot prove this. |
| Complete original successful start/request/result/log group plus unchanged original proof bytes and all fresh action-specific postconditions | Append `RECONCILED_EFFECT`; consume the distinct typed completion in the original chain. No replay of the action. |
| Missing/nonzero result, unproved children/daemon outcome, mismatched source or original files, partial/mixed current state, crash/reboot/SIGKILL without intact success, failed fresh observation | `UNKNOWN`, `retry_allowed=false`, explicit external daemon fencing decision required. Target binding remains blocked. |

The second row covers a lost transport acknowledgement after durable remote success.
A successful process exit alone is insufficient: the observer also verifies actual
postconditions against the original action contract. Conversely matching current HTTP,
image, schema or file hashes cannot discharge a nonzero/missing original result, since
another request may still be pending in a daemon. No universal daemon fence is invented.
If external fencing is required, the operator must separately establish the exact
in-flight operation boundaries and resulting daemon/database state; this package has no
fence executor, reset/adoption command, overwrite, forced retirement or implicit retry.

The checker is `scripts/v126-reconcile-poststate.py`, embedded byte-for-byte in the
sequencer. It uses the source's actual read-only guards and consumers under a monotonic
600-second total observation bound plus their existing per-request/DB/transport limits.
All original requests preserve the action arguments and allowed internal binding values;
credentials are file references and hashes, never URI contents in records. Original
successful proof/sidecar bytes, owner/run/release/source/intent/action/target and exact
ordered group are prerequisites for these fresh observations:

| Action class | Required actual post-state evidence in addition to intact original success |
| --- | --- |
| Baseline | Protected original authority paths/config/Compose/Caddy hashes, current unique V125/PG container and image/runtime identity plus semantic server/database/schema/role equality. |
| Backup/rehearsal | Exact dump, globals and metadata hashes; semantic TOC inventory; source DB equality; original labelled rehearsal container/volume identity and confirmed absence after checked cleanup. Missing historical resource witness refuses. |
| Caddy activation/restoration and public gates | Protected disk config and actual adapted/admin config equality, active systemd and marker/admission. Public HTTP/runtime checks apply where required by the original action; activation does not add a public HTTP gate. Original backend ID binding applies to the corresponding recorded start/recovery outcome. |
| Environment replacement | Actual fixed `.env` hash/metadata and effective Compose values, mode-specific immutable proof, candidate/rollback temporary cleanup. |
| Image transfer/load | Complete prepare/upload/load group, exact original anonymous local archive hash retained before dispatch, identical sealed remote archive bytes and loaded exact image/source identity. Upload or prepare alone never completes a stage. |
| Start/readiness and schema/runtime/manual boundaries | Exact original backend container ID, accepted image, restart policy, runtime/DB/schema identity and original action-specific health/drain/manual prerequisites. No second start; original local manual JSON bytes must remain and match. |
| Stop/zero-writer | Actual stopped/absent backend as specified, PG/schema identity and zero-writer consumers. A PID scan alone does not clear a daemon ambiguity. |
| Final preflight | Original extracted source/hash, SAFE/count0 result and credential-cleanup proof; fresh execution of the exact read-only SQL/psql consumer on the same semantic target, then private credential cleanup. |
| V125 recovery | Original exact V125 container/image, schema125/no126, OFF/product, fixed config, Caddy disk/runtime and public outcome, restart=no. It does not require V126 manual17. |
| Post-V126 stop / full-DR verification | Original stop/schema classification and exact backup/boundary/proof chain; current absence/runtime classification as required. Full-DR verification still reports restore_performed=false and requires separate DR authorization. |

18 completing action classes are implemented. The other three are the intermediate
preflight-upload, image-prepare and image-upload actions, which cannot complete their
stage without the final consumer. Baseline's local proof is rederived through its
source-bound validator and original main CI JSON. Temporary local archive/preflight
bytes may have been unlinked; their original pre-dispatch log hashes must agree with
identical retained remote bytes verified by the checker. Manual JSON and the sealed DR recovery-point/loss JSON have no such
exception; typed DR verification reopens the original boundary and joins its phase,
dump/inventory and digest to the original request. These are explicit artifact contracts, not cached PASS substitution.

A successful observation appends canonical mode0400
`reconciliations/<final-operation-id>.json` with exact original file inventory hashes,
checker SHA, fresh observation time/digests and `retry_allowed=false`; file and parent
are fsynced under the target lock. Original start/request/result/log/intent stays
unchanged. Readback of an existing valid record exports it without redispatch or a new
observation. Incomplete/corrupt append blocks; no repair-in-place is provided.

The local consumer retains the original failed/operation log and an immutable bundle
of exact remote records. It writes a separate format2 `*.reconciliation.json` plus
checksum and a derived artifact inventory log. Ordinary format1 `*.receipt.json` cannot
coexist. Canonical validators recheck the original intent, predecessor, authorization,
source/checker, bundle and complete artifact set. A predecessor digest may name this
typed record, which retains its explicit result category; status displays
`RECONCILED_EFFECT` or `TERMINAL_RECOVERY_RECONCILED`, never a new native PASS. Availability
observations remain separately sourced and timed. V125 retirement consumes its own
terminal chain; historical stage9 is untouched.

## Ordinary deployment under the same binding

After an approved/applied handoff, explicit retirement may select
`--next-kind ORDINARY_DEPLOY --next-request-file <protected-canonical-descriptor>`.
The next owner source SHA identifies the canonical deployment source bundle (obtainable
with `python3 scripts/v126-ordinary-deploy.py source-identity`), including this single
shared supervisor helper. The exact descriptor digest is the only permitted next
operation intent. A deployment cannot initialize an unbound legacy target.

The one remote stream holds the persistent target lock across preconditions, framed
upload, exact archive verification/load, protected root-owned config installation,
backend-only recreate, bounded readiness and actual image/DB/Caddy/public postconditions.
No independent eligibility probe grants mutation authority. The durable start/result/log
contract is shared with cutover; a successful deploy additionally requires its distinct
`*.deploy-proof.json` joined to request, actual image/container/config/DB/Caddy identities.
No migration receipt or manual evidence is manufactured.

A completed deploy is retired explicitly using `scripts/v126-ordinary-deploy.py retire`
with the protected completed descriptor, exact proof hash, separately approved/applied
handoff, exact next descriptor and
`AUTHORIZE_ORDINARY_DEPLOY_TARGET_BINDING_RETIREMENT`. The shared validator appends a
version2 transfer and preserves every previous inventory. An uncertain local ACK can
be resolved only by inspection of the intact successful dedicated proof and handoff;
nonzero/missing/corrupt operation results prohibit retirement and competing recovery.
There is no automatically selected next run or automatic retirement after HTTP200.

Old version1 transfers remain strict and retain their original meaning. Existing runs
without request/protocol records cannot be upgraded, adopted or assigned synthetic
completion. The frozen historical PASS1–8/intent9 remains unchanged. The owned Docker
daemon interruption fixture exercises synthetic create metadata and blocked replay;
until its actual native result is recorded, that applicable gate remains unverified.
It does not establish JVM survival, power-loss/storage durability or host reboot.
The latter needs a controlled VM with persistent storage and an external observer;
rebooting the disposable GitHub runner is not authorized.
