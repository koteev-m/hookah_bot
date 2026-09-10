# HT-RELEASE-REPAIR-01 coverage and remaining gates

## Final Open-Gate Closure — validation candidate, 2026-09-10

**HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**

Authorization is `AUTHORIZE_REPAIR_OPEN_GATE_CLOSURE_FEATURE_ONLY`, on the existing
`codex/ht-release-repair-01-rkbe7q` worktree. Starting candidate/parent is
`09e19461cf54376714ae51f2d4c9e480f8365d8e`, tree
`17ec77daf4713264a36381a9d43ce4b4f4ef3151`; base/main remains
`f7828e09863d391e1f714cc65c9c866f814cf6bf` (base tree
`ff66c8639ec7c5c3ad45c2371878f5ba3656df9a`). Existing local final-report edits were
preserved byte-for-byte in `../evidence/open-gate-closure-abp_tolk/initial/`, including
the initial patch, four hashes and authorization. They were not part of09e19461.
Validation candidate65016cf8746ebdbb2dbec088ff920175e6f5609d,
tree7ae79a350c36c23944707aa42b534b925fe581d6, parent09e19461, was committed with
29 explicit files and normally pushed to the same feature (exits0). Exact push CI
34457486489/attempt1 completed with11 jobs PASS and compose FAILURE in4 steps:
full mandatory harness, supervisor comparison, standalone systemd and ordinary lifecycle.
Connected PG17/Docker/JVM, real Caddy and root handoff steps PASS; all28 formerly skipped
PG cases execute in their successful jobs. No full harness/overall CI PASS is claimed.

Two invocation defects have source-bound before evidence and local corrections: actual
standalone hosted_only rejects the published root coordinator; actual ordinary Fixture
constructor needs RUNNER_TEMP lost through sudo. Workflow now runs standalone as the
ordinary runner and explicitly forwards RUNNER_TEMP to the root ordinary fixture. Two
targeted regressions and the full12-test CI contract suite PASS; the same focused review
is clear for this correction. Native after remains pending. The completed compose log
(bf74a2bc6c3be73983f1f00f48316f3cea5ba39f68fac0965194767a09f62f8d) proves the other
two failed steps share3 fixture assertions: direct shared-API refusal raised BindingError
and escaped as Python exit1 instead of the real caller's75. The fixture now embeds the
exact extracted production exception consumer; expected75, specific UNKNOWN diagnostic,
immutable records and no-effect assertions stay strict. Five portable checks PASS.
Production sequencer/helper bytes are unchanged by these corrective fixes.

Full harness stopped there before real libpq, real backup and later legacy groups; it is
not PASS. The independent exact09e before consumer executed and exposed old timeout
status137; full harness now receives that same hash-checked source too, eliminating its
absent-before-source skip. Real Caddy8/process7/rootguard7, connected PG/Docker/JVM, and
the downstream prerequisite18groups/40negative matrix PASS (19m48). New native systemd
and ordinary lifecycle produced no executed cases and remain unverified. A single early
log read returned HTTP404; later completed-job download succeeded. No auth/network
settings changed. Candidate-01 retains every first failure, before/after and review.
Three generated task bytecode files were moved to evidence; imports now suppress bytecode.

One shared source now implements persistent target supervision for cutover, uploads
and ordinary deployment. Separate reconciliation checks the original successful
operation group plus exact current postconditions, retains all original records and
writes a distinctly typed completion. Missing/nonzero/daemon-unknown outcomes still
require an explicit external fencing decision and permit no replay or retirement.
V125 reconciliation/retirement is separate from the V126 manual/release chain.
`docs/V126_REPAIR_OPERATION_PROTOCOL.md` specifies the complete immutable lifecycle;
`docs/V126_OPERATIONAL_HANDOFF.md` specifies the already accepted image limitation.

The runtime suites use an owned root Caddy/systemd unit and owned Docker/PG17/JVM/
synthetic-provider fixture on ordinary GitHub-hosted Ubuntu. They are mandatory steps
in the existing compose job; all12 existing jobs, gates and timeout budgets remain.
Root test dependency installation is confined to the disposable runner. There is no
Mac install, real endpoint use, default service modification or host reboot test.

| Finding | Current phase disposition before exact CI | Delta and proof boundary |
| --- | --- | --- |
| F01 | IMPLEMENTED_UNVERIFIED for expanded consumer; prior repair verified | SAFE/count0/extracted SQL unchanged; new preflight completion includes checked credential cleanup and fresh source-bound read-only observation. Full PG17/17+18 consumer regression remains mandatory. |
| F02 | FIXED_AND_VERIFIED at prior four-transition scope; new joins pending | Real fixed-env_file Compose and inverse semantics preserved. Reconciliation now checks actual fixed configuration and cleanup; hosted closure is pending. |
| F03 | IMPLEMENTED_UNVERIFIED | Explicit caller exits preserved; own real systemd/Caddy tests add install/reload/admin divergence, actual failure/hang and interruption boundaries. Portable lifetime fixes verified; real Linux pending. |
| F04 | IMPLEMENTED_UNVERIFIED for affected create path; prior readiness verified | Actual production Compose create rejected unsupported --no-deps; both start/recovery create calls corrected after real parser before/after. One-start readiness unchanged. Exact Docker/JVM rerun pending. |
| F05 | IMPLEMENTED_UNVERIFIED | Shared source/lock, supervised binary uploads, immutable request records, action-specific exact-effect reconciliation, typed canonical stage/recovery completion, explicit v2 transfer and ordinary-deploy lifecycle implemented. Missing/nonzero/UNKNOWN never clears. Portable canonical V125/V126 chains pass; real lifecycle + final review pending. |
| F06 | IMPLEMENTED_UNVERIFIED | Supervisor stdin is nonblocking; deadline/cancellation latched; reaped process groups never signalled as if still owned. Own systemd caller/descendant cleanup retains fixtures when quiescence is unproved. Actual Linux before/after required. |
| F07 | FIXED_AND_VERIFIED at prior semantic-target scope; new joins pending | Source/backup/preflight/backend server/database/schema/role identity retained. Reconciliation/ordinary worker reuse actual semantic consumers; hosted lifecycle pending. |
| F08 | FIXED_AND_VERIFIED at prior TOC scope; new joins pending | Exact dump + strict semantic TOC retained; original rehearsal resource/cleanup witnesses added and current absence checked. Both-phase real backup rerun mandatory. |
| F09 | IMPLEMENTED_UNVERIFIED | Accepted root/exact-image/restart policy unchanged; ordinary worker holds the shared lock across prechecks/upload/recreate/postconditions and retirement permits the next exact request. Supports already accepted image, no implicit selection/.env rewrite. Real applied fixture handoff and two deployments pending. |
| F10 | FIXED_AND_VERIFIED at prior read-attempt scope | No mutation retry or cached PASS introduced. New reconciliation capture and completion remain distinct; exact mandatory harness rerun pending. |
| F11 | IMPLEMENTED_UNVERIFIED for new typed status join | Status displays reconciled outcomes separately from native PASS. New source consumer exit initially aborted invalid-recovery status; isolated classification now returns INVALID_EVIDENCE and blocks retirement. Targeted regression and full status harness retained. |

G01 operational DR/RPO/loss/actual auth custody remains OPEN; synthetic whole-DB
roles/ACL/auth/settings remains mandatory. G02 actual host reboot/persistent-storage
crash with an external observer is not provable by killing processes or restarting
containers in this runner. No new VM is provisioned. Available systemd/daemon tests
must execute before narrowing that residual gap. G03/G04 delivery acknowledgement
ambiguity and17 real Guest/Owner/MIX assertions remain separate live gates; no replay,
retention or acceptable-loss semantics change, no real Telegram call.

Current phase evidence is `../evidence/open-gate-closure-abp_tolk/`. It retains design
before implementation, local command/exits/hashes, before failures and corrections,
focused review and later exact CI ledgers. First local defects retained include Bash3
empty-array retirement; unsupported Compose create flag; nested collector heredoc;
source status exit during invalid recovery; reaped ordinary consumer signalling;
missing original local DR-boundary revalidation; linked image archive rejection in the
reconciliation checker; fixture upload framing, cleanup and adapter joins. Full source/test manifests and exact publication SHA/tree/parent will be bound
in the publication ledger rather than guessed before commit.

No whole-procedure E2E claim: portable tests use declared synthetic immutable records,
manual approvals and selected dependencies; actual Linux consumers retain explicit
fixture namespace/port/source adapters. New ordinary integration starts from synthetic
predecessor/approval setup. The connected hook tests the actual remote terminal, lost
stdout and fresh collector, while the full local typed canonical chain/retirement is
validated separately with synthetic predecessor/manual fixtures. A successful CI run cannot prove operational DR, actual host
reboot or live manual assertions. First failures remain immutable; no blind rerun.

Local mandatory entrypoint attempt `mandatory-local-01` returned exit1: all15 readiness
cases failed at fixture setup because the Mac sandbox denied socket.bind. Earlier
portable selections in that invocation passed; it did not reach Linux SSH/PG17 gates.
No policy or guard was weakened. This is an environment failure, not application
readiness evidence. A separate local selection driver initially gave Bash the sourced
harness path as argv0 and accidentally entered main twice; the same socket restriction
stopped both. Remaining40 dispatches were prevented by reserving their fresh guard
namespaces; no action records were reset. The corrected driver uses an identical syntax
copy with a distinct argv0 and executes unmodified named functions independently.

`legacy-local-02` executed42 named functions:38 PASS and4 refusals. Three are stale
fixture expectations for the removed unsupported Compose create --no-deps flag (two
start/selection groups plus recovery). The rehearsal group also exposed the newly added
explicit failure checks converting original86/87/88 statuses to4. The checks now retain
the original status and diagnostic through cleanup; the regression expectations are
preserved. The successful rehearsal fixture supplies and verifies an exact owned64hex
resource ID, matching the new source witness. All four after selections PASS in `legacy-fixture-closure/after-ledger.json`; the
expanded rehearsal verifies24 direct/conditional/capture combinations, preserving
86/87/88 plus copy89/createdb90/restore91, exact cleanup and no failure proof. All five
guarded after invocations record zero unexpected endpoints. None of these selective
checks is presented as full harness PASS.

Focused review has resolved D1–D9/S1–S2 at source, including original DR JSON, private
anonymous image snapshot and first-error preservation in Linux-hook diagnostics/cleanup.
Native Linux proof remains pending. Exact source/command ledgers preserve each first
failure, scoped correction and exposed fixture boundary. No unexpected endpoint was
allowed; no actual Mac service, database, SSH endpoint or Docker resource was started.

The focused independent source review of this delta and current report updates is
clear after the latest status/fixture corrections; exact hashes/ledgers are retained
in `review-delta-interim.md` and the source snapshots. Next: complete the final local
ledger and explicit-file commit/non-force feature push, then exact
12-job hosted CI and required-step verification. All four final report/status files
must be included in the final reviewed published candidate; none will be left as an
uncommitted final-results delta. A later integration/publication/live phase is not
started automatically.

`MAIN_INTEGRATION=NOT_AUTHORIZED`; `LIVE_ACTIONS=NONE`;
`PUBLICATION=FEATURE_VALIDATION_ONLY`; `STAGING_RELEASE_READINESS=NOT_ESTABLISHED`.
Staging application remains a future separately authorized gate. Original dirty
checkout, scripts/dev, migrations, historical runs/receipts/archives/backups and frozen
evidence are preserved. No scripts/dev read or write. Historical PASS1–8/intent9 and
separately timed V125 restoration remain unchanged; no fresh availability observation.

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
