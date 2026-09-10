# HT-RELEASE-REPAIR-01 coverage and remaining gates

## Latest closure validation — CI05 fixture correction

**HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**. Published candidate
`ee262f2ca6b227930a01a5ce0fb218afb46f4b7b`, tree
`c8e952da0d0b3b44bdd7a15b1398f7488ee6c54c`, parent
`391abc7135e74365dad69e34109b1bc2612caec7`.
[CI34479384440](https://github.com/koteev-m/hookah_bot/actions/runs/34479384440),
CI/230370033, exact feature push/attempt1, completed2026-09-10T13:33:33Z:
11 other jobs and every other compose step PASS; new owned daemon step FAILED.
Full mandatory harness6m26 and prerequisites18 groups/40 negatives21m44 PASS.
The failure is preserved in candidate-05/result-ledger.json and compose.log,
517680bytes/SHA25683fc5762f737def26f154da3ee45916273b418fb0a56d056d45b972a5cb73eec.

The fixture passed identical containerd container/plugin namespaces; actual Docker28
refused startup. The first reported assertion was root/driver mismatch because formatted
Docker info returned exit0 despite an unavailable server. No fault case started.
Own systemd journal records the namespace refusal; cleanup completed, own cgroup EMPTY,
callers REAPED, runtime root removed. No default daemon or running container was changed.
This is a test setup/readiness defect, not evidence of a new production supervisor defect.
The corrective fixture uses distinct private namespaces and actual server readiness,
with bounded read-only waiting and immediate refusal of foreign identity. Local regression
before:5 failing methods/6 assertions; after:23 PASS. Workflow/guard CI-contract13 PASS;
endpoint violations0, syntax/compile/diff0. Current fixture SHA256
a0ffcc98ad8fb9203ad4af18c3129570b4cf1034ae2935e9152de901e7595621.
Evidence is daemon-proof/correction-01/DIAGNOSIS.md and final-validation-02.json, plus
candidate-06/ci-contract-01.json and sanity-01.json. The portable readiness observations
are synthetic; native after is unverified until the corrective candidate executes in CI.

Actual CI05 ordinary lifecycle retains19 events, two distinct same-lock deployments,
cleanup=true and synthetic provider polls309/webhook8/menu5/unexpected0/outbound0.
Other native/core/systemd and28 former PG cases pass at their recorded fixture scopes.
Current source production sequencer remains d18a638fd64362a381b1d97b0cd7c647a965788cf46f8e783609404604bb45cf.
All candidate05 observations stay separate from the CI04 details below; CI05 is not green.
Its11-file commit/push exited0/0; manifest49 base-changed/32 phase-changed paths.

Next: review the demonstrated fixture correction and narrow regressions, explicitly commit
and normally push only this feature, then verify exact12 jobs/native outcomes. Native daemon
validation and final published report acceptance remain required. Historical report tails,
starting dirty reports and first failures remain unchanged. MAIN_INTEGRATION=NOT_AUTHORIZED;
LIVE_ACTIONS=NONE; STAGING_RELEASE_READINESS=NOT_ESTABLISHED. No scripts/dev access.


## Final Open-Gate Closure — CI04 proof and pending native daemon gate

**HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**

Verified implementation candidate `391abc7135e74365dad69e34109b1bc2612caec7`,
tree `c70a0bbb936a043ab97ed137a8c67838af3dd021`, parent
`90d0bda929f673cd2e277e6c243de9ae6bc5b0fc`.
[CI34472343581](https://github.com/koteev-m/hookah_bot/actions/runs/34472343581),
workflow CI/230370033, feature push, attempt1: **12/12 jobs and all mandatory steps PASS**;
compose completed2026-09-10T12:12:18Z. Full mandatory harness PASS6m42;
prerequisites18/18 groups and40/40 negative matrix PASS16m56 by step timestamps.
This green candidate precedes the new required Docker daemon interruption fixture.
The fixture and its CI wiring require their own reviewed candidate and actual native run.

Evidence: `../evidence/open-gate-closure-abp_tolk/candidate-04/`. Source/CI/log hashes,
commands, actual versions/identities and exclusions are in REPAIR-REPORT and the bound
proofs. Statuses below apply to actual executed scope, not the historical tables.

| Finding | Result at current proven boundary | Evidence / remaining boundary |
| --- | --- | --- |
| F01 | FIXED_AND_VERIFIED, core scope | Full extracted SQL/structuredSAFE-count0, unsafe/noartifact/privacy/source binding on PG17/psql17+18 PASS. Reconciliation reuses actual consumer, not18 independent native completed stages. Historical PASS8 stays unchanged. |
| F02 | FIXED_AND_VERIFIED, core scope | Fixed env_file guards+real Compose allfour OFF/SMOKE/recovery transitions, inverse/post-install/metadata/unrelatedbytes/cleanup PASS; ordinary real config consumers PASS. No arbitrary env-file authority or crash proof. |
| F03 | FIXED_AND_VERIFIED at actual Caddy/systemd scope | Actual validate/install/reload exits, command substitution, disk/admin divergence, active-unit checks, hung admin/reload, post-install/post-reload interruption and late continuation PASS in unchanged13-case fixture. Service active/disk hash alone never completes the action. Host reboot remains G02. |
| F04 | FIXED_AND_VERIFIED, core scope | Owned actual PG17/Docker/JVM503→200/readiness, wrongidentity,exit,timeout;4 exact CIDs eachstart1. No start retry; ordinary authorized recreate is separate lifecycle. |
| F05 | FIXED_AND_VERIFIED at stated protocol/native fixture scope | Shared target lock/history, successful lost ACK→fresh actual collector→distinct immutable reconciliation→explicit retirement→two deployments PASS; unknown/nonzero/missing records block replay/recovery/retirement. Real SSH/descendants and separate typed local chains PASS. New actual Docker-daemon fault gate remains pending under G02; no universal external fence or full20-stage E2E claim. |
| F06 | IMPLEMENTED_UNVERIFIED only at pending Docker-daemon gate | Production inherited-stream join corrected; exact before39.465s→after23.579s on unchanged native reload assertion. Private pipes, bounded queues/I/O, deadlines, original exits, backpressure and child cleanup PASS. Actual Caddy continuation remains UNKNOWN. Available isolated Docker-daemon interruption validation is being added; it cannot be replaced by container restart or labelled unavailable without evidence. |
| F07 | FIXED_AND_VERIFIED, core scope | Two plausible semantic server/database/schema/role targets, actual backup/preflight/backend equality, real libpq/auth and ordinary DB consumer PASS. HashURI never substitutes; no live target change. |
| F08 | FIXED_AND_VERIFIED, core scope | Exact dump hash, meaningful TOC timezone/client-format differences versus real changes PASS; native both-phase backup/rehearsal and owned cleanup witnesses PASS. Original local sealed DR boundary still required; not operationalDR. |
| F09 | FIXED_AND_VERIFIED at stated handoff/ordinary fixture scope | Actual migration=no→explicit approved/applied synthetic handoff→unless-stopped; accepted exactimage/root.env authority, two same-lock backend-only deployments, PG unchanged, backend restart and stale/wrongimage/config/owner/UNKNOWN refusals PASS. V125 schema125 boundary separate; no live policy/time/newimage authority chosen. |
| F10 | FIXED_AND_VERIFIED, core scope | Immutable attempts/status/stdinACK/dispatch consumers PASS; only proved NOT_DISPATCHED reads repeat. Unknown cannot replay/delete intent/cachePASS. Both native supervisor invocations use exact09e before source. |
| F11 | FIXED_AND_VERIFIED, core scope | Distinct NOT_STARTED/RECONCILIATION_REQUIRED/INVALID_EVIDENCE and typed V125/V126 chains PASS; source/predecessor/manual/DR corruption refuses. Remote native reconciliation and separate portable localformat2 chain are explicit. Historical availability separately timed/sourced, never nativePASS9. |

| Gap | Established scope | Remaining boundary |
| --- | --- | --- |
| G01 OPEN | Mandatory actual synthetic whole-DB roles/memberships/owners/ACL/settings/auth and schema/data restore PASS. | Operational assets/secret custody/auth/recovery point and accepted RPO/loss decision. No loss default or automatic live restore. |
| G02 OPEN | Actual hosted Linux/PG17/clients17–18/Docker/Compose/own SSH/JVM,13 native Caddy/systemd plus4 process cases, ordinary restart/recreate and all cleanup proofs PASS. | Actual isolated Docker-daemon interruption remains applicable and pending. Real host reboot/power-loss/persistent-storage durability needs controlled VM/persistent storage/external observer; runner reboot is unauthorized. These are distinct limits. |
| G03 OPEN | All28 related PG cases plus connected polling/progress, allowed/denied HTTP/Telegram boundaries and strict synthetic provider PASS. | Real provider/poller conflict and send→markSent acknowledgement/crash ambiguity; no new denied-update retention/replay or accepted loss/delivery promise. |
| G04 OPEN | Source-bound17-artifact assertion validator, browser216 and backend mandatory gates PASS. |17 actual controlled Guest/Owner/MIX assertions remain future live Gate B/C; synthetic manual/handoff prerequisites never substitute. |

Current full harness retains479 assertion markers and17 printed suite summaries,
including readiness25 after the ten new inherited-stream/binary/backpressure/descriptor
regressions. This is not479 unique tests. CI-contract12 methods are captured by the
successful harness consumer. Remote supervisor has18 PASS+one non-Linux platform skip;
no applicable Linux case is silently skipped. Other11 jobs have57 successful declared
steps and only the inapplicable pnpm setup skipped. The28 former PG skips are inbound4,
webhook5PG (+1non-PG) and outbox19 under actual successful selectors/XML/no-skip validators;
no fabricated individual outbox log lines or cross-suite unique total is claimed.

Systemd before/after source binding proves unchanged fixture/deadlines and corrected
production helper:13 cases+4 process cases and33 evidence checks PASS. Hung reload
23.579s/UNKNOWN/no completion, active ControlPID followed by separate late equality;
original marker preserved, caller/unit cleanup independently proved. Ordinary19 events
PASS, provider polls310/webhook8/menu5/unexpected0/outbound0, cleanup=true.

Actual consumers use explicitly documented topology/fault adapters and synthetic
prerequisites. Full mocked cutover/prerequisite18+40 orchestration is not full native
E2E. New private daemon metadata mutation is likewise narrower than running backend
restart survival, reboot or operational DR. Its before boundary is missing requested
coverage, not a newly demonstrated production failure. Its first native result remains
pending and must be recorded separately from this green CI04.

New fixture portable16/16 and actual workflow/guard CI-contract13/13 PASS, unexpected
endpoints0; syntax/compile/diff0. Platform/UID/peer/thread/diagnostic leaves are explicit
portable mocks. The exact minimal create payload reaches real Docker only in the required
next native run; no portable result is upgraded to daemon proof.

`MAIN_INTEGRATION=NOT_AUTHORIZED`; `LIVE_ACTIONS=NONE`;
`PUBLICATION=FEATURE_VALIDATION_ONLY`; `STAGING_RELEASE_READINESS=NOT_ESTABLISHED`.
Operational DR/RPO/loss, actual host reboot/power-loss/storage durability,17 controlled
live Guest/Owner/MIX assertions and staging application remain separate future gates.
No scripts/dev read/write, original dirty checkout, migration bytes, historical runs/
intents/receipts/archives/backups or frozen evidence changes. Historical PASS1–8/intent9
and separately sourced V125 restoration are not a new stage9 PASS. No main, PR, HT13,
Gate A/B/C, recovery, deployment or live smoke is authorized by this result.

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
