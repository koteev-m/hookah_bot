# V126 repair operation protocol and binding retirement

This records the implemented F05 contract for HT-RELEASE-REPAIR-01. Exact feature
candidate2866db99ae897817c7e9a745d19f3b487c5563b6 passed CI34484968850, including the
owned terminal/reconciliation/ordinary lifecycle and actual Caddy/systemd consumers.
The separate owned Docker daemon interruption gate verifies five metadata/shared-supervisor
cases and cleanup. REPAIR-REPORT and REPAIR-COVERAGE bind each proof and its limits;
final document delivery requires its own exact CI and reviewed-blob attestation.
This supplements the canonical cutover contract; it is not authority to run against staging.

The authenticated Policy B launcher supervises new CUTOVER actions; the existing
verified stdin path remains recovery-only. Both use a
Linux child subreaper and one nonblocking `flock` on a persistent lock file under
the canonical target directory's `.v126-target-operations` (operator-owned 0700).
The lock inode is never removed. Its scope is the real target path, independent
of SSH alias, local state directory or run. The protocol adds no service, account,
daemon, access grant or lease.

Each executable leaf action gets create-only, fsynced, mode-0400 start/result JSON plus
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
The initial OPEN/SOURCE exchange contains no binary spool. Enrolled source/target/action,
existing lock/history/completion and applicable EARLY admission precede durable intent;
only then may S request and receive the bounded binary payload. Source/history are replayed
around transfer; live lock/cancellation are checked for each chunk, followed by applicable
fresh LATE and final dispatch checks. Stage10 image-upload gains no new Policy B gate.
Busy/missing/invalid history or EARLY refusal receives no payload bytes and creates no spool.
A transfer failure after intent retains UNKNOWN; it cannot authorize retry.

## Approved one-time legacy target binding

`TARGET_BIND / LEGACY_GENESIS / bind-legacy-target` is a metadata operation of the
existing target history, not a stage or migration authority. The approved HT-QR-01
legacy plan §3 and HT-OPS-01 addenda permit its local implementation. Installation,
enrollment, actual fencing, a target invocation and a successor release remain separately
authorized work. The existing release/image identity is not changed by this source diff.

The public `prepare-target-binding` command consumes the exact already prepared INIT
proposal and writes a separate immutable proposal outside the clean source checkout.
Its target device/inode/uid, host fingerprint and complete inventory digest are assertions
for review, not trust. `bind-legacy-target` consumes those exact bytes and their digest;
created_at/nonce/next INIT manifest identity are not regenerated after approval.
No canonical local INIT state is created by preparation or genesis.

The closed schemas in `scripts/v126-legacy-genesis.py` distinguish primary bytes,
limited safe projections, recovered-report claims, missing material, historical outcome
and a separate new-admission basis. HT-OPS-01's exact f782 disposition
`FAILED_PRE_ACTIVE_CONFIG_MUTATION_EXTERNALLY_FENCED` remains attributed history; it
is neither native PASS9 nor an accepted terminal/general fence, and never covers run724.
Admission needs either the complete supported native terminal/handoff validators or
independently observed cessation of **all** indexed old effects plus explicitly accepted
post-fence state. Unknown native producer formats refuse that proof route; they are not
silently parsed using the current producer. No old evidence is reconstructed.

The existing attended V/C/U authority boundary selects the complete inventory from the
independently enrolled catalogue and producer readers, with the full ledger/current
high-water head. The prior U anchor additionally pins discovery, fencing, acceptance and
writer-exclusion methods and disposition authority identity; exact catalogue action scopes
are `LEGACY_GENESIS` and separately `LEGACY_GENESIS_COPY`. Both rounds require fresh
nonce-bound trusted-console C confirmation, source/tool/runtime/target binding, current
clock, revocation and bounded observations. Neither a proposal/report hash nor a
self-asserted fence or approval field grants admission. Producers/acquisition operations
are not implemented by this consumer.

Only the authenticated server-selected launcher reaches this entrypoint. EARLY admission
and its five-second consumption bound precede the first server mkdir. S exclusively
creates the canonical registry. Before mkdir, an inotify watch observes the pinned target
namespace; after the first root open, a same-name renameat no-op fences the parent namespace
before the event queue is checked. Exactly one root CREATE and no root-name replacement,
self-event or queue loss is required before lock creation. Watch/proc unavailability refuses;
there is no stat-only fallback. This covers same-UID namespace replacement during creation,
not hostile root/mount manipulation. S creates its permanent lock with O_EXCL and holds that
same FD/inode across LATE admission and all create-only/fsync/readback writes. Target/root
identity, exact history delta, cancellation and expiry are checked immediately before
writes. The complete two-round operation is bounded; a partial root is retained even if
no intent was written. It is never adopted, removed or retried automatically.

`genesis.result.json`, joined to request, intent, full legacy index and owner, is the sole
completion boundary only after publication as0400. It is created pending0600; the actual mode
is checked before any bytes (including under restrictive umask). File fsync, root fsync and
same-FD readback must succeed before fchmod publishes0400. A file/root barrier EIO leaves
unacceptable pending bytes, never successful completion; all history/copy readers refuse.
No durability barrier follows publication. The payload and directory entry have confirmed
barriers, but publication mode itself is not separately synced: a crash/power loss may restore
pending0600 and leave conservative UNKNOWN, even after an answer. This is not a guarantee of
power-loss persistence of completion recognition. No later reader reseals or adopts it. Both the permanent lock marker and the version2
owner envelope retain genesis ancestry. Deleting a completion marker cannot downgrade
history to the old raw-owner format. All dispatch, reconciliation, retirement, handoff
and transfer readers replay this ancestry. Legacy raw-owner histories remain readable
unchanged. Partial/UNKNOWN inspection needs no new readiness and grants no retry.

The distinct `copy-target-binding-completion` requires fresh exact copy authority and
an independently pinned durable completion digest. Under the same existing target lock,
it verifies and copies accepted history; it writes no new server result or owner and
requires no renewed fence/expired create lease. A lost answer is not evidence of absence.
Completed genesis only permits a **separate** INIT matching the pinned prepared manifest;
INIT retains EARLY/LATE R0/G and its own completion, and baseline remains the first STAGE.
The twenty stages, UNKNOWN/recovery rules and Gate A/B/C are unchanged.

## Attended INIT metadata operation

The locally approved tuple is `INIT / RUN_INITIALIZED / initialize-run`; the twenty
STAGE identities and all legacy receipts retain their meaning. Closed request/result/
attestation schemas are in `v126-operation-bindings.py`, mirrored in the sequencer.
INIT opens the existing permanent lock **without creation**, verifies the complete
history and already accepted CUTOVER owner/transfer, and requires empty own history.
It does not appoint an owner or bootstrap a registry. The physical S lock remains
held through EARLY R0/G, create-only intent/request, LATE R0/G, one WRITE_INIT and
remote durable completion. The exact own in-flight delta is admissible only within
this continuous invocation; a new session sees unresolved history as UNKNOWN.

V creates protected local metadata with fsync/readback. Its LOCAL_WRITTEN attestation
binds manifest bytes, local-state identity and complete metadata inventory. This state
is provisional until S writes `completion=LOCAL_METADATA_ATTESTED`; the immutable local
`init-completion.json` copy must match that remote result before any subsequent STAGE.
INIT has no fictitious leaf log or `children=REAPED` assertion. Baseline is still required.
After history verification the bound is 930s: two300s rounds, writer300s, ACK30s;
preparation/history have finite separate bounds. Partial writes, timeout and lost result
create no retry authority; no file is removed to resume INIT.

`complete-init-copy` requires separate current COPY_ONLY authorization, the existing
local state lock before the same target lock, and exact immutable successful history.
It only copies completion, never repeats INIT/writer or appends another remote result.
Missing/UNKNOWN remote completion refuses. Status/legacy inspection needs no Policy B
readiness; recovery retains its existing independent authority. See
[V126 cutover contract](V126_STAGING_CUTOVER_CONTRACT.md#ht-qr-01-policy-b--attended-authority-and-same-lock-dispatch)
for authenticated transport, current observations and one-use dispatch bounds.

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
A version1 CUTOVER transfer now admits only one metadata INIT, followed by its own
fresh BASELINE_VERIFIED as the first STAGE. A version2 transfer explicitly binds `next_kind=CUTOVER` or
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
daemon interruption fixture has actual five-case proof at2866db99/CI34484968850:
create metadata before/after transport and daemon interruption preserves original
records, refuses replay/recovery/earliest UNKNOWN retirement and verifies cleanup.
A zero or one post-restart object count never replaces missing/nonzero original results.
The baseline identity is synthetic; full terminal/handoff proof belongs to the separate
ordinary lifecycle. No running-JVM survival, complete native cutover, power-loss/storage
durability or host reboot is established. JVM survival is an unexercised workload scope,
not an established infrastructure unavailability. Actual host reboot/power-loss needs a
separately authorized controlled VM with persistent test storage and an external observer;
rebooting the disposable GitHub runner is not authorized. Exact results and final delivery
binding are recorded in REPAIR-REPORT/COVERAGE.

## Prospective isolated domain extension

The separately approved [prospective authority epoch contract](V126_PROSPECTIVE_AUTHORITY_EPOCH.md)
adds a distinct-domain bootstrap. It does not admit the old target, upgrade legacy UNKNOWN,
replace the legacy disposition route, waive R0/G/custody or authorize operational execution.
Prospective mode requires the exact epoch through native manifests, history and all consumers.
