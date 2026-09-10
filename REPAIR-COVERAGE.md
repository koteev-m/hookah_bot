# HT-RELEASE-REPAIR-01 — coverage and residual release gates

## Verified implementation and final delivery binding

This report records implementation evidence verified at
`2866db99ae897817c7e9a745d19f3b487c5563b6`, tree
`296b37d0ee4da172f6614000a313cb063c2469b7`, parent
`ee262f2ca6b227930a01a5ce0fb218afb46f4b7b`, exact feature CI34484968850.
Final delivery identity and acceptance are recorded in the immutable final-delivery
attestation for the commit containing these exact report blobs. Acceptance requires
that exact published feature candidate's12 jobs and mandatory steps to succeed, the
reviewed report blobs to match, and no remaining report delta. This is an acceptance
contract; it does not predeclare a future CI result or encode a commit's own SHA.

Worktree `/private/tmp/ht-release-repair-01.RKbe7q/worktree`, branch
`codex/ht-release-repair-01-rkbe7q`; base
`f7828e09863d391e1f714cc65c9c866f814cf6bf`, tree
`ff66c8639ec7c5c3ad45c2371878f5ba3656df9a`; phase begins at09e19461/tree17ec77da.
The four initially dirty reports remain byte-identical in initial/; their historical
tails are retained below the current report. No new branch/worktree was created.

## Findings: phase-start to verified implementation status

All FIXED_AND_VERIFIED labels apply to the listed code/fixture boundary, not to an
unexecuted live release, universal daemon recovery or complete native20-stage cutover.

| Finding | Phase-start → implementation status | Cause and repair | Verification and limit |
| --- | --- | --- | --- |
| F01 | FIXED_AND_VERIFIED → FIXED_AND_VERIFIED at stated scope | SQL/psql exit was an insufficient unsafe decision. Extracted SQL and consumer require structured SAFE/count0 and protected source-bound artifact; unsafe emits no PASS artifact. | Actual PG17 with psql17/18, safe/unsafe/privacy/source binding. Historical PASS8 is not upgraded. |
| F02 | FIXED_AND_VERIFIED → FIXED_AND_VERIFIED at stated scope | Candidate env differed from fixed Compose env_file. Candidate/install/inverse recovery validate effective future backend values while preserving unrelated bytes and metadata. | Allfour real guards+Compose transitions, post-install and cleanup; ordinary actual authority consumers. |
| F03 | IMPLEMENTED_UNVERIFIED → FIXED_AND_VERIFIED at stated scope | Conditional/capture callers could mask validate/install/reload failure; disk hash and active service did not prove active config. | Actual Caddy/systemd validate/install/reload/admin equality, disk divergence, failure and interruption;13 cases+4 process cases. |
| F04 | FIXED_AND_VERIFIED → FIXED_AND_VERIFIED at stated scope | Starting was confused with readiness and could repeat start. One mutation precedes bounded monotonic read-only readiness with distinct terminal/wrong/unknown outcomes. | Real Docker/PG17/JVM503→200, wrong identity, exit and timeout; four exact container IDs each start1. |
| F05 | OPEN → FIXED_AND_VERIFIED at stated scope | Local exit/PID death did not exclude remote children/daemon ambiguity or another local state dir. One target lock/shared supervisor preserves immutable source/run/intent/action/history through canonical and ordinary lifecycles. | Lost ACK with intact original successful records+fresh action-specific observations gives distinct RECONCILED_EFFECT. Missing/nonzero/SIGKILL/daemon ambiguity blocks replay/recovery/retirement. Actual SSH, ordinary lifecycle and private-daemon cases are separately attributed. |
| F06 | IMPLEMENTED_UNVERIFIED → FIXED_AND_VERIFIED at stated scope | I/O and daemon/transport outcomes were insufficiently bounded. Private streams/bounded queues and deadline-aware consumers preserve original failures and UNKNOWN. | CI03 real hung reload39.465s before; CI04/05 correction verified, plus current native reload24.223s/UNKNOWN, two descendants reaped, daemon5/5 cases and cleanup verified. New daemon fixture's initial namespace/exit-only readiness defect and corrective before/after are retained. |
| F07 | FIXED_AND_VERIFIED → FIXED_AND_VERIFIED at stated scope | URI hashes did not establish the semantic database target. Server/database/schema/intended-role identity is joined across backup/preflight/schema/backend. | Two plausible real targets, actual libpq/auth and V125→V126 backend path; no live target change. |
| F08 | FIXED_AND_VERIFIED → FIXED_AND_VERIFIED at stated scope | TOC comparison depended on timezone/client presentation. Exact dump hash and meaningful inventory remain mandatory, narrowly normalized display fields only. | Real PG17 both-phase backup/rehearsal, timezone/client differences versus changed dump/inventory, owned cleanup. |
| F09 | IMPLEMENTED_UNVERIFIED → FIXED_AND_VERIFIED at stated scope | Restart/image/config/ownership and ordinary-deploy authority were inconsistent. Migration/recovery keep restart=no; separately approved/applied handoff precedes unless-stopped and same-lock exact accepted-image recreate. | Actual synthetic handoff/two deployments/next binding, image/config/owner refusals and backend restart. V125 schema125 handoff is separate from V126 manual17. No live handoff application or new image selection is authorized. |
| F10 | FIXED_AND_VERIFIED → FIXED_AND_VERIFIED at stated scope | Read prechecks and possible dispatch shared an ambiguous retry boundary. Immutable attempts distinguish proven NOT_DISPATCHED reads from possible mutation. | Only bounded read precheck can repeat on proven absence; no replay/cachePASS/new run, intent deletion or unknown retry. |
| F11 | FIXED_AND_VERIFIED → FIXED_AND_VERIFIED at stated scope | Status could conflate unstarted, invalid evidence and unresolved execution with later availability. | NOT_STARTED/RECONCILIATION_REQUIRED/INVALID_EVIDENCE, safe next action/retry=false and distinct typed reconciliation; later observations carry their own source/time. |

## Evidence attribution

See [REPAIR-REPORT.md](REPAIR-REPORT.md) for exact source/proof hashes, commands/exits,
all twelve jobs, real consumer results and first failures. CI06 full harness PASS (476s),
prerequisites18/40 with97 expected outcomes (1188s), all28 PG cases and browser216/216.
Only the non-Linux platform-refusal case and unused pnpm setup are inapplicable skips.
Applicable Linux SSH, libpq, backup, Docker/CID, Caddy/systemd and private-daemon cases run.
The prerequisite matrix uses declared external mocks; native proofs are separate.

## Remaining release gates

| Gap | Verified scope | Still required / not executed |
| --- | --- | --- |
| G01 OPEN → OPEN | Synthetic whole-DB restore with globals/roles/memberships/owners/ACL/settings/auth, synthetic secrets and checked cleanup. | Operational DR and operator-selected recovery point/loss; real secret custody/auth, extensions/tablespaces/external assets and service reconstruction. No automatic live restore. |
| G02 OPEN → OPEN, residual operational scope | Actual disposable Linux/PG17/clients17–18/Docker/Compose/SSH/Caddy/systemd and owned Docker-daemon metadata interruption with fail-closed outcomes. | Real VM reboot/power-loss/persistent storage. Minimal future capability for these host faults: separately authorized disposable VM with persistent test disk and external observer/control, only synthetic data and owned resources. GitHub job process/container restart cannot prove host reboot; no runner reboot/new paid VM was authorized. |
| G03 OPEN → OPEN, unchanged delivery contract | Synthetic provider polling/worker progress and HTTP/Telegram/write admission boundaries. | Real provider/poller behavior and send→markSent crash ambiguity; denied-update retention/replay and acceptable loss are not newly selected. No concurrent getUpdates observer. |
| G04 OPEN → OPEN, live release gate | Synthetic route/manual-chain assertions and connected fixtures. |17 controlled live Guest/Owner/MIX assertions, separately authorized staging application/integrity/runtime smoke. They are not blocked from CI and are not satisfied by CI. |

Running JVM/container survival across a Docker-daemon fault was not exercised. This is
an explicit workload-scope limit, not a demonstrated unavailable-infrastructure exception
or a claim that it requires a new VM. The repaired contract requires honest UNKNOWN and
same-target exclusion, which the actual daemon fault cases verify; separately, ordinary
handoff/restart/recreate verifies the accepted restart/image policy. Neither result promises
uninterrupted JVM survival through daemon loss. No extra workload crash campaign is claimed.

MAIN_INTEGRATION=NOT_AUTHORIZED; LIVE_ACTIONS=NONE; STAGING_RELEASE_READINESS=NOT_ESTABLISHED.

## Preserved prior phase report (applies to09e19461 only)


**HT_RELEASE_REPAIR_FEATURE_CI_PASSED_WITH_OPEN_GATES**

Current proof: candidate `09e19461cf54376714ae51f2d4c9e480f8365d8e`, tree
`17ec77daf4713264a36381a9d43ce4b4f4ef3151`, exact push CI34428598301,12/12 jobs
and every mandatory test step PASS. Full cutover harness PASS6m25, complete prerequisite
matrix18/18 groups and40/40 injected failures PASS19m37. This file and the final three
report/status updates are local after CI; executable source/test/workflow remain exact.
Earlier local and candidate01/02 maps/logs are retained in the initial/frozen/individual
candidate evidence directories; they are not rewritten as successful attempts.

The contract is11 named baseline artifacts + operation-log,12 total. Historical11-artifact
receipts remain immutable. Current per-F results: F01/F02/F04/F07/F08/F10/F11
FIXED_AND_VERIFIED within the boundaries below; F03/F06/F09 IMPLEMENTED_UNVERIFIED;
F05 OPEN. No overall CI success substitutes for operation-specific proof.

R = real production consumer/tool in an owned fixture; M = explicit dependency mock;
S = source-bound review; U = remaining unverified runtime. No row is live evidence.

| Procedure boundary | Established candidate03 proof | Exact remaining gate |
| --- | --- | --- |
| Read precheck → stage1 intent | R controller/attempt/status and CI validators; immutable NOT_DISPATCHED read retries; M Git/gh/remote fixtures. Exact feature CI read separately verified. | Actual future release still needs separate source/run authorization; no cached PASS or retry on UNKNOWN. |
| Every remote stage/recovery | R actual Linux supervisor and owned SSH before/during/after file effect; detached descendants, competing recovery, different local state dirs and ACK/log identity.10 applicable PASS. | U daemon-side mutation under disconnect/process crash and VM reboot. UNKNOWN requires operation-specific external completion/fence; no automatic clearing. |
| Completed run → next binding | R CLI15 and Linux lifecycle: exact terminal chain, protected applied-handoff attestation, immutable transfer, lock inode/history preservation and next fresh baseline. | No UNKNOWN retirement; no historical run adoption. Ordinary deploy remains blocked on every registry target and lacks full-operation locking for a fresh target. |
| Baseline/DB authority | R source PostgreSQL, host libpq and actual backend JDBC equality; alternate plausible DB/schema/role refusal; semantic digest bound to later consumers. | U temporal daemon/network/recreate drift under crash; a snapshot is not exclusion. |
| Backups/rehearsals | R actual Docker PG17 both-phase dump/globals/restore5 plus checksum4 PASS, cleanup; exact hashes/inventory; R synthetic whole-DB globals/roles/auth. M backup precondition seam. | G01 consistent operational restore point, actual host auth/secret custody/assets and VM recovery; no automatic restore. |
| Caddy activation/drain/ordinary restore | R Bash producer/consumer error paths, real Caddy validate/reload/admin equality, disk/runtime mismatch and inverse;8 cases,5 actual daemon. M privileged install/systemctl in configuration7/runtime-consumer6. | U production privileged install/unit configuration, systemctl/recovery executor and daemon crash durability. HTTP opens on reload before marker cleanup. |
| Stop/zero writers/re-drain | R retained actual guards/ordering in full legacy harness; M service inventory and mutations. | U daemon quiescence, external writers and HTTP/TG write timing through crash/disconnect. No PID or zero-count shortcut. |
| Final V125 preflight | R complete extracted SQL on PG17 with psql17/18 and structured safe/count0 consumer, actual backend-linked source; safe/unsafe before/after. | Historical PASS8 remains unusable as count0 proof; canonical intent9 remains without PASS9. |
| OFF/SMOKE and recovery transitions | R real guards+fixed-env_file Compose all four transitions; post-install/inverse/failure/unrelated bytes/metadata/cleanup. | U privileged filesystem crash durability and full recovery executor; actual connected JVM covers a migration path, not every whole-procedure transition. |
| Exact image transfer/recreate | R existing Docker two-build/canonical-save reproducibility, real canonical container IDs; R connected owned image build/resource identity. M sequencer transfer/transport seams. | U actual daemon save/load/recreate mutation outcome across disconnect; no HT13 release archive or fallback/build added to cutover. |
| First/final start and readiness | R actual Docker/JVM one-start migration/readiness, same backend503→200, wrong version/exit, bounded UNKNOWN timeout; actual HTTP15 including delayed/hung consumer. | U daemon continuing after transport timeout and full privileged recovery startup; no second start to become ready. |
| Schema/runtime/progress | R V125→V126 actual Flyway/JVM, semantic database identity, synthetic polling progress and HTTP/Telegram write boundaries; all28 former PG skips execute with zero-skip validators. | G03/G04 external poller conflict/delivery crash ambiguity and17 real manual assertions. Connected consumers are not full cutover E2E. |
| Manual smoke gates | R full legacy source-bound17-assertion attestation validator with synthetic input. | U all17 real Guest/Owner/MIX assertions; synthetic provider or valid attestation fixture is not live smoke. |
| Final public/handoff | R retained identity/schema/authority consumers; real root fixed image/restart/ownership guard7. Accepted V125 and V126 handoff policies. | No live handoff/time selected. Restart=no throughout gates; separately approved applied handoff needed for unless-stopped. V125 uses its own schema125 integrity. |
| Pre-V126 recovery / post-V126 stop | R full legacy/controller inverse and terminal contracts, no-V125-start-after126; remote UNKNOWN blocks recovery. | U whole privileged daemon recovery/crash/stop completion. Operator restoration never becomes canonical stage9 PASS. |
| Full-DR prerequisite TOC | R actual17/18 timezone before/after, exact dump hash, strict semantic inventory/version refusal. | G01 operational DR and user-selected loss/recovery point remain open. |
| Status/attempt outcomes | R9 cases, preserved old records/attempts, explicit NOT_STARTED/RECONCILIATION_REQUIRED/INVALID_EVIDENCE and retry_allowed=false on UNKNOWN. | Availability is separately sourced/timestamped; no new live observation. |

## Gaps and concrete decisions

| Gap | Disposition | Evidence now available and exact remaining need |
| --- | --- | --- |
| G01 | OPEN; synthetic whole-DB strategy VERIFIED | Real own PG17 globals/memberships/grantor/owners/ACL/default ACL/settings/sequence/extension/SCRAM restore passes; original no-owner/no-privileges loss reproduced. Need consistent data+globals point, real host auth/secret custody/rotation, deployed extensions/tablespaces/assets and operational recreation. User must choose recovery point and acceptable loss; neither is defaulted here. |
| G02 | OPEN; hosted Linux runtime gap narrowed | Real Ubuntu VM, PG17/clients17+18, Docker/Compose, Caddy and own SSH now PASS. Remaining: privileged systemctl/unit/recovery and daemon mutation completion, independent observation of controlled VM crash/reboot/persistent-disk recovery, lock/record durability and explicit UNKNOWN fencing. Minimum new capability is a disposable isolated VM with external observer and permission for those controlled failures; no runner reboot or new infrastructure was performed. |
| G03 | OPEN for delivery/crash semantics | All28 prior PG skips execute; actual webhook→worker→SENT, denied-row preservation and connected synthetic polling/HTTP boundaries pass. OFF may resume autonomous/TG writes before public HTTP; denied offset advances; send→markSent can be ambiguous. No retention/replay/exactly-once/loss promise changed. Need operation-specific daemon/queue outcome through interruption. |
| G04 | OPEN for live operational/manual gate | Bounded actual synthetic worker/poller progress verified, not inferred from count1/health. All17 real controlled Guest/Owner/MIX assertions and actual provider/conflict evidence remain future separately authorized gates; no concurrent getUpdates observer or real Telegram call used. |

F05-UNKNOWN cannot be cleared by inspection. The proposed reconciliation must preserve
source/run/intent/operation/log/transport records and prove each affected daemon's completed
effect or effective fence/resulting state. Until separately designed/validated, no retry,
recovery, binding reset/retirement or fabricated PASS. Known-completed retirement is a
separate implemented contract in docs/V126_REPAIR_OPERATION_PROTOCOL.md. F09 ordinary-deploy
whole-operation locking remains OPEN; current refusal is conservative, not complete integration.

## Mocks, artifacts and limits

- Full20-stage/legacy and prerequisite matrices use explicit Git/gh/SSH/Docker/Caddy/
  service/manual-attestation fixtures; their PASS is not full real cutover E2E.
- Full extracted SQL/TOC/DR uses actual PG clients. Target integration additionally uses
  actual Docker/Compose/libpq/backend JDBC; backup fixture still has synthetic prerequisites.
- Configuration runs actual Compose/guards; sudo install/systemctl are spies. Separate real
  Caddy runs owned files/admin ports with a restricted adapt/HTTP adapter, no systemctl.
- Real supervisor uses actual own sshd and file/process effects, not detached daemon
  completion. Local portability/relay tests retain explicit Docker inventory mocks.
- Connected runtime uses the actual backend/JVM/Flyway plus test-only Java helper, bounded
  internal-network loopback relays and synthetic TLS Telegram/provider gating. Production
  defaults and migration bytes are unchanged. No actual Telegram/live smoke is claimed.
- Test endpoint wrappers refuse unexpected calls and latch evidence; they are a PATH
  accidental-call fence, not an OS network sandbox. Completed guarded CI steps pass; earlier
  local abort retained five refused SSH fallbacks and the earlier accidental read-only gh
  call remains disclosed. These are not silently relabelled zero-refusal attempts.
- PG Testcontainers image evidence is job-local tag inventory, not per-test container
  attestation. Tests have enforced selectors/floors/names and zero-skip XML checks.
- Runtime safe results retain command exits/times/checkpoints; raw inspect/identity/event
  payloads and private diagnostics are not exported. Runtime PG patch/image/provider
  digest/container JVM version remain unavailable; host or other-job inventory cannot
  substitute. Relay refused/failed114 is not an endpoint violation count. The runtime
  dump omits owners/ACL; whole-DB globals/auth proof is a separate synthetic suite.

See REPAIR-REPORT.md and candidate03 result-ledger/harness/runtime/database proofs for
commands, exits, versions, hashes, before/after and the single independent review.
`MAIN_INTEGRATION=NOT_AUTHORIZED`; `LIVE_ACTIONS=NONE`;
`PUBLICATION=FEATURE_VALIDATION_ONLY`; `STAGING_RELEASE_READINESS=NOT_ESTABLISHED`.
