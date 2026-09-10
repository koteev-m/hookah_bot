# HT-RELEASE-REPAIR-01 — unified feature validation candidate

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


## Final Open-Gate Closure — native F06 correction verified; daemon gate pending

**HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**

Continue the existing package under `AUTHORIZE_REPAIR_OPEN_GATE_CLOSURE_FEATURE_ONLY`.
Worktree `/private/tmp/ht-release-repair-01.RKbe7q/worktree`, feature
`codex/ht-release-repair-01-rkbe7q`; exact basef7828e09863d391e1f714cc65c9c866f814cf6bf,
base treeff66c8639ec7c5c3ad45c2371878f5ba3656df9a. Phase parent09e19461cf54376714ae51f2d4c9e480f8365d8e,
tree17ec77daf4713264a36381a9d43ce4b4f4ef3151. Four originally dirty report versions remain
byte-identical in initial/. No new branch/worktree or main change.

Verified implementation candidate `391abc7135e74365dad69e34109b1bc2612caec7`,
tree `c70a0bbb936a043ab97ed137a8c67838af3dd021`, parent
`90d0bda929f673cd2e277e6c243de9ae6bc5b0fc`.
[CI34472343581](https://github.com/koteev-m/hookah_bot/actions/runs/34472343581),
workflow CI/230370033, feature push, attempt1: **12/12 jobs and all mandatory steps PASS**;
compose completed2026-09-10T12:12:18Z. Full mandatory harness PASS6m42;
prerequisites18/18 groups and40/40 negative matrix PASS16m56 by step timestamps.
This green candidate precedes the new required Docker daemon interruption fixture.
The fixture and its CI wiring require their own reviewed candidate and actual native run.

### Contract and exact scope


One source, scripts/v126-operation-bindings.py, serves sequencer and ordinary deployment.
The current baseline remains11 named artifacts plus operation-log,12 total, including
database-target-identity. Producers/validators/tests agree; historical11-artifact
receipts retain their original bytes and meaning.
Its permanent target flock, indefinite binding, create-only/fsynced records and complete
transfer history cover prechecks/uploads/mutation/postconditions/durable result. No
eligibility-to-mutation window, separate rsync, expiry, reset, adoption or automatic retry.
Missing/nonzero/malformed outcomes block subsequent mutation and competing recovery.

Separate source/run/intent/action/target-bound reconciliation requires the exact intact
successful request/start/result/log, retained original proof bytes and bounded fresh
actual action-specific postconditions.18 completing classes and three non-completing
upload/prepare actions are explicit. It appends RECONCILED_EFFECT; local format2 consumers
retain original failed log/intent and typed completion, not a fake normal PASS receipt.
Original manual/DR JSON must remain. Missing/nonzero/daemon-ambiguous evidence remains
UNKNOWN/retry_allowed=false and requires a separate external fencing decision; there is
no generic fence executor. Current source contract is documented in
[V126_REPAIR_OPERATION_PROTOCOL.md](docs/V126_REPAIR_OPERATION_PROTOCOL.md).

Explicit terminal retirement joins approved/applied protected handoff to the exact next
owner/request, preserving records and lock inode. Ordinary deployment holds that same
lock through actual prechecks/upload/config/load/backend-only recreate/readiness and
image/DB/Caddy/proof postconditions. Fixed root-owned .env and accepted exact full-SHA
image remain authority; no workstation ownership, implicit env update, pull/build/tag
fallback or new-image selection. A second deployment requires explicit valid transfer.
[V126_OPERATIONAL_HANDOFF.md](docs/V126_OPERATIONAL_HANDOFF.md) retains restart=no during
migration/recovery and a separate approved/applied handoff before unless-stopped. V125
uses schema125 recovery completion independently of V126 manual17. No live handoff applied.

### Actual Linux evidence and first failures

Evidence root: `../evidence/open-gate-closure-abp_tolk/`. Candidate04 result-ledger.json
and ci-snapshot-43.json bind exact workflow/repository/event/branch/SHA/attempt, all12 jobs
and mandatory steps. Raw compose.log497100bytes, SHA256
`41b98d8935a4f046d18a112a62d270a7d367a575ceae73fed941e912c98067cf`; download exit0.

| Actual boundary | Result and evidence limit |
| --- | --- |
| Full mandatory harness | PASS6m42;479 assertion markers/17 printed suite summaries, not479 unique tests. Readiness25 includes ten new helper regressions. All prior mandatory attempt/status/stdin/operation/config/DB/libpq/backup/ID suites and12-method CI validator retained. `core-compose-proof-final.json` binds exact counts, sources, commands and adapters. |
| Native core | Own OpenSSH and exact09e/current supervisor comparison, PG17/psql17+18 complete safe/unsafe preflight, semantic DB identity, real libpq180006/auth, allfour fixed-env Compose transitions, both-phase Docker/PG backup/rehearsal, timezone TOC and real CID refusal/acceptance PASS. |
| Connected runtime | Actual PG17/Docker/JVM V125→V126,503→200, wrongidentity/exit/timeout and four CIDs eachstart1 PASS; polling/progress and denied writes use strict synthetic provider and owned internal network/relays. Cleanup is checked by actual consumers; not live Telegram/fullE2E. |
| Native Caddy/systemd | All13 cases+4 process cases PASS,33 evidence assertions. Actual validate/install/reload/admin/disk divergence, conditional/capture contexts, failures and interruption boundaries covered. Detailed proof is `systemd-proof/current-extraction/systemd-proof.json`. |
| Ordinary shared lifecycle |19 events PASS, terminal lost ACK with intact successful result→fresh collector→immutable reconciliation→synthetic approved handoff→two same-lock deployments/retirements. Provider polls310/webhook8/menu5/unexpected0/outbound0, cleanup=true. Proof `ordinary-proof/actual-proof-01.json`. |
| Other11 jobs / PG skips | All11 SUCCESS;57 successful declared steps, only inapplicable pnpm setup skipped. All28 former PG skips execute: inbound4/webhook5PG/outbox19, mandatory XML/name/no-skip validators. Browser216 PASS does not establish the cause of the older intermittent debounce failure. |
| Prerequisites and guards |18 groups/40 injected post-sync failures PASS; bounded deadline/cleanup and actual own HTTP-header checks also PASS. Explicit synthetic orchestration adapters remain identified; Gate A is not started. |
| Docker daemon interruption | Applicable missing validation, not a proved production defect or established infrastructure exception. New mandatory private daemon fixture is pending actual native execution on the next candidate. |

CI03 first native failure is preserved: production20s bounded command returned UNKNOWN
but inherited detached stdout/stderr held Bash capture39.465s, violating unchanged19–28s.
Exact extracted old helper+unchanged native DRIVER reproduced0.2s→1.279s and incorrectly
successful exited-parent output cases. Production repair uses private child pipes,
bounded nonblocking queues/forwarding, WNOWAIT leader identity, bounded cleanup and exact
exit/status preservation. No output-only fixture workaround or widened deadline.
All ten new portable regressions plus readiness25/CI12/systemd15/runtime6 passed before
publication, with binary stdin/out/err, backpressure, merged descriptor flag restoration,
setup/refusal and cleanup edges covered. Review findings and first local failures remain
in candidate-03/systemd-diagnosis/pipe-eof-before-after-01/.

Current actual unchanged native reload returns exit4/UNKNOWN/no completion in23.579s;
ControlPID109191 is still reloading. Only after an explicit own-process SIGCONT does a
separate observation show ControlPID0 and active-config equality. Original UNKNOWN and
drain marker remain. Caller descendants are REAPED, and final own unit cgroup EMPTY with
MainPID0/ControlPID0. A process/HTTP snapshot never clears daemon ambiguity. Current source
sequencer SHA256 `d18a638fd64362a381b1d97b0cd7c647a965788cf46f8e783609404604bb45cf`;
native fixture unchanged SHA39489152d40c56ee2317c29c5dcf661c868aaf423fd8f967bacdfe044520c33f.

Current compose versions: Ubuntu24.04.5, PostgreSQL17.11/psql17.11+18.6/libpq180006,
Docker28.0.4/Compose2.38.2, Caddy2.6.2, systemd255.4-1ubuntu8.17, OpenSSH9.6p1,
Temurin21.0.12.1+1. Connected runtime image
sha256:9764a659c5a27216b683aa090f8e67279ac1a44e84775eaa19fb36ad3aca917f;
396 command events, ready6.237s, wrongidentity4/2.886s, JVMexit4/0.080s,
timeout75/120.077s, four exact CIDs eachstart1. Three own relays closed after139
attempts/115 relay refusals; these are not unexpected endpoint counters. Actual backup
PG17.11 image sha256:7296f210ae81031ec955dbad9a67a84fe958572a2153b8d0826a647522904dc1.
Versions and image identities are from this candidate's own logs, not inherited from03.

Ordinary actual image sha256:5eb86836f4407023017ac77d03a025a09f4c19c49b44b5024554bad202a89ab9,
PG CID91bea55c977e31f684b17e483bcbce0e8528b91c89b61b864bc3ccead1bc8d66.
Two distinct deployment requests/proofs/CIDs use the same permanent lock and history.
Backend restart/recreate succeeds with approved unless-stopped; stale image/config/owner,
migration restart=no and unknown replay/retirement refuse. Prior prerequisites and handoff
approval are synthetic; ordinary public_checks=false and owned directstream are explicit.
Actual SSH and local typed format2 chains are separately tested. No full20-stage native E2E,
native completion of all18 action classes,17 manual smoke or raw unexported record bodies
are claimed. Current versions/other image IDs stay attributed to their own core/job proofs.

### Required daemon validation delta

Focused criterion review `candidate-04/g02-criterion-review.md` found a still-applicable
owned Docker daemon test. Before implementation, `daemon-gate/DESIGN.md` fixes ownership,
lifetime/children/cleanup and crash/UNKNOWN limits. Only a second ephemeral owned dockerd
on the disposable hosted runner may be interrupted: private socket/data/exec/pid/config,
managed private containerd namespaces, no bridge/iptables/ip-forward/sysctl changes,
no default daemon/containerd/resources or live endpoint. No production service is added.

The test uses an imported synthetic image and actual create-only metadata effect through
the shared supervisor; no container start/pull/build or application network is needed.
Controlled transport loss before forwarding, after dispatch without observed reply,
after real201 and missing-supervisor-result cases must preserve first outcomes/poststate,
record hashes and same-target exclusion across local state dirs. Matching0/1 current
containers, daemon restart or process death never prove NOT_DISPATCHED or permit replay,
competing recovery or retirement after UNKNOWN. Positive actual create is also required.
Own caller/proxy/daemon/containerd cleanup must be proved separately; unresolved lifetime
retains resources. This is metadata persistence/interruption proof, not JVM survival,
full operational DR or host reboot. No native PASS is claimed before execution.

Same12 jobs, existing budgets/gates and contents:read remain; the new portable self-test
joins the full harness and the required native step joins compose after ordinary. Workflow
trigger review confirms only push/pull_request CI, no deployment/release/environment/secret
consumer. The source-bound CI validator is updated alongside wiring; endpoint confinement
remains mandatory. All four final reports must be in the last reviewed published candidate.

Before publication, daemon portable16/16 and actual workflow/guard CI-contract13/13 PASS
under endpoint wrappers, unexpected0; bytecode-free compile, bash-n and diff checks0.
Python3.13.2 on macOS; no local native daemon/systemd execution. Frozen new test SHA256
a92344773b262bd147209f435c3b93b9f729f6e59f45c9c6b69de469dfb9be61;
commands/exits/mocks and source hashes are in daemon-gate/validation-03.json,
ci-contract-02.json and final-sanity-01.json. The unchanged full Linux entrypoint passed
at391; this added fixture/wiring requires the next full Linux run. Review corrected
explicit handler tracking, incremental first-case evidence and exact API payload scope
before publication. DESIGN-ADDENDUM-01.md preserves the original design and reasons.

### Publication, review, side effects and remaining boundaries

| Candidate / parent | Commit/push and tree | Exact CI and preserved first result |
| --- | --- | --- |
|65016cf8746ebdbb2dbec088ff920175e6f5609d /09e19461 |29 explicit files;0/0;7ae79a350c36c23944707aa42b534b925fe581d6 |34457486489:11 PASS; compose failures in actual exception-consumer test join, wrong standalone root coordinator and missing RUNNER_TEMP under ordinary sudo. |
|cde7a0ebbf89e4d98489d24087fb53762bcd7e05 /65016cf |8 explicit files;0/0;9e6067301fa27375b82a2c3f688eb7d7dbb61a8e |34461706240: full harness/18+40 PASS; two newly reached native joins fail: strict descendant refusal escaped expected negative observer; synthetic provider omitted actual commands-only startup menu. |
|90d0bda929f673cd2e277e6c243de9ae6bc5b0fc /cde7a0e |7 explicit files;0/0;dd5a1d0f7089537a5591fb1a19a4a67d97ee482b |34466473797: earlier corrections PASS; ordinary/18+40/full harness PASS; actual F06 inherited-stream deadline violation above. |
|391abc7135e74365dad69e34109b1bc2612caec7 /90d0bda |6 explicit files; commit/push0/0; treec70a0bbb936a043ab97ed137a8c67838af3dd021 |34472343581:12/12 PASS, full harness6m42/prerequisite18+40 PASS; unchanged actual systemd13+4 and ordinary lifecycle PASS. |

Every commit staged explicit files; every normal same-feature push followed exact remote
parent verification. No force/history rewrite/main/PR/tag mutation. All failed runs remain
in candidate01–03 evidence; CI03 compose SHA96e8c2bc377ee4a85dcd63da290cf4ba5ea1d8ab75ffe0d2fa6dbc81487e821b.
Earlier phase commits/runs and initial dirty report history are preserved below, not
upgraded. Same focused review covers the09e delta, F05 consolidation, F06 source correction
and now the bounded missing daemon gate; no repeated full audit. Candidate04 manifest
contains48 base-changed/30 phase-changed files with Git blobs/SHA hashes in source-manifest-01.json.
The new test is an additional file and must be included in the next explicit manifest/commit.

Commands/exits: current full harness, native IDs/SSH/Caddy/systemd/root/JVM/ordinary,
prerequisite18+40 and all other mandatory CI commands succeed0. Detailed nonzero adverse
observations remain expected assertions, not suppressed failures. New fixture local/native
commands, versions, hashes and results will be retained in daemon-gate/ and its exact next
CI directory. All relevant syntax/compile/lint/diff and review checks remain required.

Actual side effects: feature source/test/docs edits, explicit commits/normal feature
pushes, read-only CI metadata/log retrieval, bounded synthetic local processes, disposable
hosted builds/DB writes/restores and own Docker/SSH/Caddy/systemd resources with checked
cleanup. No Mac installs/global service/auth/network changes. Endpoint wrappers are an
accidental-escape guard, not an OS sandbox; own runtime network confinement is separate.
The earlier phase's disclosed read-only authenticated gh fixture escape remains historical;
it is not erased by later zero unexpected endpoints. A prior unauthenticated CI browser
tab/early unfinished-log404 and local read/diagnostic errors are non-mutating observations.

Current overall status is incomplete until actual new daemon validation and the final
reviewed report candidate pass exact12 CI jobs. F01–F11 and G01–G04 dispositions are in
REPAIR-COVERAGE. Operational DR/RPO/loss, real host reboot/storage durability and live
manual/runtime application remain separate gates even after feature validation. No staging
deploy is needed to validate this patch; separately authorized staging application/smoke
is required before release. Cached origin/main/agreed basef7828e09 and older local
main4daf5546 are unchanged. Worktree contains only the declared daemon test/CI/docs delta;
final Git status must be clean after its reviewed publication. All starting reports and
frozen evidence remain retained. After the final result stop before main/live decisions.

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

`MAIN_INTEGRATION=NOT_AUTHORIZED` · `LIVE_ACTIONS=NONE` ·
`STAGING_RELEASE_READINESS=NOT_ESTABLISHED`

The user authorized explicit-file commits, non-force pushes of this existing feature
branch and ordinary GitHub-hosted Ubuntu CI as a validation candidate. PR, deployment,
main integration and live changes remain unauthorized. This document retains the
prior local-phase evidence below; it does not reinterpret historical receipts.

## Final feature CI result — 2026-09-10

**HT_RELEASE_REPAIR_FEATURE_CI_PASSED_WITH_OPEN_GATES**

The validation candidate is commit `09e19461cf54376714ae51f2d4c9e480f8365d8e`, tree
`17ec77daf4713264a36381a9d43ce4b4f4ef3151`, on the existing feature branch.
[Exact push CI34428598301](https://github.com/koteev-m/hookah_bot/actions/runs/34428598301)
completed successfully: **12/12 jobs and every mandatory test step PASS**. Compose
finished at2026-09-10T02:46:20Z. Its full mandatory harness passed; this is not full
operational cutover E2E, deployment approval or proof that every finding is closed.

All source/test/workflow files are committed and published in the three ordinary
feature commits listed below. These final updates to PROJECT_STATUS.md and the three
REPAIR documents are local, uncommitted evidence updates after CI. They do not alter
the tested executable candidate. No report-only commit or new CI was created merely
to restate the result. The final local diff includes them, separately identified.

Final source sequencer SHA256 is
`5911db8d4a0fc43a7ddb75b0cdaf2c0ef2c29720142715899463813226b8b98f`.
The source/test/workflow changeset has39 files versus the requested base. The final
file manifest, source hashes and unified patch are retained under
`/private/tmp/ht-release-repair-01.RKbe7q/evidence/feature-ci-lqfjbouh/candidate-03/`.
Migration bytes, product/RBAC defaults and scripts/dev are unchanged.

### Current findings and exact proof boundary

The earlier local table below is historical. This table is the current disposition;
priorities are unchanged. IMPLEMENTED_UNVERIFIED means the specific remaining runtime
boundary prevents closing the whole finding, even where subchecks now pass.

| Finding | Current result | Change, established before/after and remaining boundary |
| --- | --- | --- |
| F01 P1 | FIXED_AND_VERIFIED | Complete original SQL/psql unsafe exit0/PASS reproduced, then refused on actual PG17 with clients17/18. Safe requires exact structured safe/count0, not exit alone. Production extractor/consumer, privacy/source binding and dump/schema joins pass; old PASS8 is untouched and remains insufficient. |
| F02 P1 | FIXED_AND_VERIFIED | Fixed env_file candidate validation uses real guards and effective Compose values. All four OFF→SMOKE, SMOKE→OFF, recovery-OFF and recovery-SMOKE transitions pass after the demonstrated before refusal. Inverse/post-install failure, unrelated bytes, metadata and cleanup pass. Privileged filesystem crash durability is not claimed. |
| F03 P1 | IMPLEMENTED_UNVERIFIED | Explicit validate/install/reload/nested-consumer exits repair masked Bash failures. Configuration7/runtime-consumer6 pass. Caddy8 includes5 actual daemon tests of validation/reload/admin-state, disk/runtime divergence, stopped server and output/nonzero. Actual privileged install/systemctl/unit configuration and complete recovery executor under crash remain unverified; their fixture spies are not runtime proof. |
| F04 P1 | FIXED_AND_VERIFIED | One start followed by monotonic bounded read-only readiness passes actual Docker/JVM startup, exact identity and V125→V126 migration. Same backend503→200 makes no second start; wrong identity and JVM exit refuse; running-JVM timeout returns UNKNOWN. HTTP/process unit cases also cover delayed listener/hung consumer. No reboot or daemon no-effect inference is made. |
| F05 P1 | OPEN | Persistent canonical-target lock, immutable source/run/action records and ACK/log binding exclude other local state dirs and competing recovery. Actual own SSH/subreaper10 cases pass, including disconnect before/during/after owned file effect and descendants. Known completed terminal chain can retire explicitly and the next binding needs a fresh baseline (CLI15 and Linux lifecycle PASS). **UNKNOWN daemon reconciliation/fencing is absent and remains OPEN**; PID/reaping/SSH exit/elapsed time/reboot never permits reset or replay. |
| F06 P1 | IMPLEMENTED_UNVERIFIED | Bounded transport/process groups, HTTP and SQL limits preserve failure and classify possible mutation timeout UNKNOWN. Real HTTP/JVM/libpq/SSH, deadline12 and failure matrix pass. Docker/systemd mutation continuing after transport/process loss, daemon completion and VM crash remain unverified. |
| F07 P2 | FIXED_AND_VERIFIED | Actual source SQL, host libpq and backend JDBC identity agree semantically on cluster/database/schema/intended role; plausible alternate database/schema/role refuse. Real PG17 source/backup/preflight/schema and running backend joins now pass. URI hash is not the equality oracle; persistent digest binds the semantic result. Temporal daemon/network drift under crash remains G02, not proof from a single snapshot. |
| F08 P2 | FIXED_AND_VERIFIED | Real before TOC timezone mismatch reproduced; only strictly parsed creation-display metadata is canonicalized. Same exact dump passes timezones with pg_restore17/18; altered dump, ordered inventory or meaningful header/version changes refuse. Exact dump hash remains mandatory. |
| F09 P2 | IMPLEMENTED_UNVERIFIED | Accepted distinct V125-schema125 and completed-V126 handoff policy is documented. Actual root/fixed-Compose guard7 proves exact image/restart/ownership refusal rules; no workstation owner import/fallback/pull/build. No live handoff is applied. Full ordinary-deploy target locking and fresh-target race remain OPEN; all registry-bearing targets are conservatively refused, even after retirement. |
| F10 P2 | FIXED_AND_VERIFIED | Bounded read prechecks precede intent. Actual controller preserves immutable distinct attempts and only validated NOT_DISPATCHED permits another read. Unknown/corrupt attempt cannot retry; no intent deletion, cached PASS, automatic new run/build. Exact source-bound CI validator9 and full harness pass; read-only feature CI observation is not release authorization. |
| F11 P2 | FIXED_AND_VERIFIED | Actual status9/full controller distinguish NOT_STARTED, RECONCILIATION_REQUIRED and INVALID_EVIDENCE, including orphan recovery and incomplete/corrupt attempts. UNKNOWN keeps retry_allowed=false and safe inspect/reconciliation action. Historical availability has its separate source/time, never canonical PASS. |

### Exact CI, commands and individual evidence

Safe local evidence root:
`/private/tmp/ht-release-repair-01.RKbe7q/evidence/feature-ci-lqfjbouh/`.
`candidate-03/result-ledger.json` retains exact SHA/branch/event, all12 jobs, each step,
completion times and every downloaded log hash. `harness-proof.md/.json`,
`runtime-proof.md/.json` and `database-proof/actual-proof-01.md/.json` map actual
consumer proof and its limits. Full compose log SHA256:
`f6cf1c7387addbc2738f69dab39f4525ae03306603c2944e274ab0b513a58e7a`.

| Job / command | Exact candidate03 result |
| --- | --- |
| backend-compile (21) | PASS1m49; mandatory compile step PASS |
| backend-ktlint (21) | PASS1m12; mandatory lint step PASS |
| backend-migration-sanity (21) | PASS4m23; test and mandatory XML validator PASS |
| backend-venue-booking-rbac (21) | PASS3m07; tests and source-bound validator PASS |
| backend-telegram-lightweight (21) | PASS4m06; tests, image inventory and zero-skip validator PASS |
| backend-release-critical-routes (21) | PASS10m56; route/security, menu mutation, integrity/concurrency and all validators PASS |
| backend-archive-reproducibility (21) | PASS3m49; archive reproducibility step PASS |
| backend aggregate | PASS2s, all required dependencies satisfied |
| miniapp (20) | PASS18s; npm build PASS. Optional pnpm setup skipped because npm lockfile is selected; no test gate skipped |
| miniapp-e2e-smoke (20) | PASS3m55; full216 browser cases and structured report validator PASS |
| docker (backend) | PASS6m44; exact image/archive guard and two-build/canonical-save reproducibility PASS |
| compose | PASS32m43; all mandatory steps below actually executed |
| `bash scripts/test-v126-cutover.sh` through endpoint guard | exit0,6m25. Full terminal PASS marker line1275; leading81 regressions, Linux supervisor10 applicable/one non-Linux refusal skip, real libpq11, backup9 and every later legacy gate execute |
| `python3 scripts/test-v126-container-ids.py --real-cli` | exit0, real Docker/Compose case PASS; earlier10 fixture cases also PASS |
| Real process guard / Caddy / root-Compose guard | exit0 each;7 /8 (5 actual Caddy +3 adapter) /7 methods PASS |
| Runtime diagnostic + `test-v126-linux-runtime.py --require-hosted-ci` | exit0;15 diagnostic methods and actual connected PG17/Docker/JVM sequence PASS; safe result has no failure |
| `test-v126-prerequisite-timeout.py` | exit0,12/12 on Linux,6.178s; real deadlines/cleanup/status preservation and native/fallback equivalence |
| `bash scripts/test-v126-staging-prerequisite-sync.sh` | exit0,19m37;18/18 groups,40/40 injected post-sync failures, positive/rollback/recovery paths, during4/after4 writes and INT130/TERM143. External SSH/Docker/PG/Caddy/Telegram are mocks in this suite |
| Final Compose/admission/maintenance/image guards | exit0; all existing gates retained |

The full backup suite now executes actual container PG17 dump/globals/restore in both
phases:5 methods plus4 native-checksum adapter regressions PASS (89.806s), own-resource
cleanup PASS. Its target prerequisite seam remains explicit; semantic equality is
proved separately by the real connected pipeline. Full harness PASS does not turn
synthetic20-stage dispatch/manual attestations into a real release sequence.

All28 previously skipped PostgreSQL cases execute: inbound4, webhook5 PG cases and
outbox19. Webhook's logged class count6 includes one non-PG case. Inbound/webhook have
direct class counts; Outbox19 is established by exact selector/minimum/required-name
and successful zero-skip XML validation, not an invented per-class log count. Separate
invocation totals are Telegram316, migration125, routes1373 and extra PG gates110,
all zero skips/failures/errors; these overlap and are not summed as unique cases.
Actual allowed webhook→worker→SENT, denied-row preservation and synthetic polling
progress add runtime evidence without changing Telegram replay/delivery promises.

Versions from candidate03 compose log: Ubuntu24.04.5 LTS x86_64,
kernel6.17.0-1022-azure; Bash5.2.21; PostgreSQL17.11, psql/pg_restore17.11 and18.6,
libpq180006; Docker28.0.4/API1.48, Compose2.38.2, Caddy2.6.2,
OpenSSH9.6p1; actual runner Java reports Temurin21.0.12.1+1 (setup metadata21.0.12+1).
Backup image is `sha256:7296f210ae81031ec955dbad9a67a84fe958572a2153b8d0826a647522904dc1`,
digest `postgres@sha256:67f41722b7a8cbdb868a44a4995c846eddfdc2973bccb291ce937dce88ad5675`,
linux/amd64, server/dump/restore17.11 Debian. Testcontainers jobs separately inventory
`sha256:1bea307dfb3ee30541a7acf7de14b58bcd6948da98e5d31a04c627c4d35ec64b`, digest
`postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`.
That is job-local tag inventory, not per-test container attestation. Runtime image
and exact126 migration-resource hashes are retained in the runtime proof.

Actual connected runtime image:
`sha256:558df6c21252d81594b162afb12b21f7de53716a31c1fb135152ea69d6629576`;
build exit0 in161.072s. Four backend IDs each start once. Delayed positive JVM reaches
ready in6.348s; same backend503→200 observer takes4.277s. Wrong-version returns4,
exited JVM returns4, running wrong-port JVM returns UNKNOWN75 in120.076s. The synthetic
poller advances two polls with zero denied user writes/outbound calls; accepted queue/
outbox and signed HTTP/RBAC writes are covered by the separate backend suites.
Three owned relays close after138 connections;114 refused/failed fixture connections
are not endpoint-guard violations and their individual causes were not retained.
The outer guard's successful exit enforces its fail-on-latch contract; its raw result
is not exported. Raw successful Docker event/inspect/identity payloads and private
runtime diagnostics are also not exported; their predicates are source-bound passing
assertions in the retained safe result. Exact runtime PG server patch/image digest,
provider image digest and container JVM version were not exported and are not inferred
from host/backup/Testcontainers inventories. Runtime's V125 dump omits owner/ACL and
is not a whole-DB restore rehearsal; the separate synthetic globals/auth suite supplies
that narrower proof. These evidence-export limits remain explicit.

### Failures, repairs and publication history

1. Candidate01 `d97c405c27ed06204af31d4db93284311900fae0`, tree
   `02f4d859b6ae370405e2c1d5bc72b60143e2aa8f`, CI34390646421:11 PASS/compose FAIL.
   First full-harness failures were nested Python leaf quoting and dict-order CLI
   binding fields. Actual runtime failed before backend start on absent443/tcp mapping.
   Corrected exact payload/field order and owned internal-network relay transport;
   retained before/after plus Linux subsequent proof.
2. Candidate02 `6702409fb0e0437118edd36f3d8f4b8494320eec`, tree
   `3437a98006b9f2bfeed98e97c9b43e28a93dc469`, CI34397910541:10 PASS/2 FAIL.
   Supervisor/libpq now passed. Full harness first refused backup stdin hashing before
   dump: fixture sha256sum accepted files only. Runtime reached PG17/Flyway125 but
   actual Compose refused create --no-deps before backend start. Production guards
   correctly refused. Local before/after fixed only those fixture contracts; backup4
   and runtime15 PASS, final Linux backup9/actual JVM PASS. Valid stdout/nonzero still
   refuses. Exact failure logs/stderr hashes remain in candidate02 evidence.
3. Candidate03 `09e19461cf54376714ae51f2d4c9e480f8365d8e`: both corrections, reviewed
   before explicit-file commit and normal same-branch push, both exit0; exact CI12 PASS.
   No force push, rerun, PR, main integration, live or release image publication.

Candidate02 also failed one unchanged Mini App debounce assertion (215/216 PASS,
request count2 rather than1 after fastForward299). Same source/job passed candidate01
and candidate03. Timing sensitivity remains a hypothesis; no Mini App fix is claimed,
no assertion removed and no blind rerun performed. Candidate03 CI was justified by the
two independently demonstrated repair-fixture defects. Earlier local fixture timeouts
and five refused SSH fallback attempts remain preserved; none became external SSH.
Action Node20 deprecation/cache-key-prefix annotations are non-failing existing CI
configuration observations, not repaired F01–F11 defects.

### Review, remaining decisions and stop boundary

The same [unified independent review](/private/tmp/ht-release-repair-01.RKbe7q/evidence/review/REPORT.md)
covers the whole diff and corrective joins, with a final exact-CI/evidence addendum.
Code-level corrections passed before publication; final report-only checks repeat
scope, hashes, diff sanity and evidence links. No separate feature approvals or new
audit replaced the cross-area review. Current gaps are detailed in REPAIR-COVERAGE.md.

- F05-UNKNOWN: approve an operation-specific completion/fencing contract for Docker,
  PostgreSQL, Caddy and service-manager mutations. Preserve exact source/run/intent/
  operation/log/transport evidence. Inspect only while UNKNOWN; no replay/recovery,
  new binding, intent deletion, receipt repair or automatic lock removal. Known
  completed retirement requires the full terminal chain and separately approved/
  applied handoff, appends immutable transfer history and permits only fresh baseline.
  See docs/V126_REPAIR_OPERATION_PROTOCOL.md for exact records/lifetime/children/fields.
- F09: full ordinary-deploy lock/outcome integration remains OPEN, including new-target
  race. Registry targets remain blocked. Accepted root-owned full-SHA .env and eventual
  unless-stopped require separately approved handoff; V125 uses schema125 integrity,
  V126 requires completed release/runtime/manual gates. No live application time selected.
- G02: minimum additional capability is a disposable isolated Linux VM whose guest can
  run real systemd/Caddy/Docker/PG, with independent external observation and controlled
  crash/reboot/persistent disk recovery. It must test operation-specific daemon effects,
  lock/record durability and reconciliation across power/process/transport failure.
  No runner reboot, new VM/service/access or paid resource was created/authorized here.
- G01: synthetic whole-DB roles/memberships/grantor/owner/ACL/default ACL/settings/SCRAM
  restore passes. Operational DR still needs consistent globals/data point, real secret/
  host-auth custody, extensions/tablespaces/external assets and operator-selected
  recovery point/acceptable loss. No automatic restore or loss decision is added.
- G03/G04: actual synthetic queue/poller/write-boundary proof is now available. Delivery
  acknowledgement/crash ambiguity, denied-update offset semantics and all17 actual
  Guest/Owner/MIX assertions remain separate future gates; no live Telegram was used.

Real side effects: three feature commits/pushes and three normal CI runs; disposable
runner package installation, own synthetic DB writes/migrations/restore, Docker test
builds/containers/networks/volumes, owned sshd/Caddy/provider/HTTP/process fixtures,
normal Actions checkout/cache use and local safe log/manifest files. Successful fixture
cleanup is recorded; runtime cleanup proof has its precise resource scope, not global
daemon quiescence. No Mac software/global settings changed, no real credentials in
fixtures, no staging/VPS/Telegram calls, no HT13 release archive or release image push.
The previously disclosed accidental read-only authenticated gh fixture call remains
historical evidence; later endpoint wrappers are a PATH fence, not sandbox weakening.

At finalization feature HEAD/tree equals the tested candidate; only these local result
updates are modified, nothing staged:

```text
 M PROJECT_STATUS.md
 M REPAIR-CHECKPOINT.md
 M REPAIR-COVERAGE.md
 M REPAIR-REPORT.md
```

Original checkout remains HEAD4daf5546fb622a6b967398f5c25b7bed41d7fa05 with its original
PROJECT_STATUS.md/QA docs modifications and untracked scripts/dev. Existing release/
audit worktrees, historical migration/run/receipt/backup bytes and frozen evidence
were preserved. Staging deploy is required only for a later separately authorized
behavior release after open gates; it is neither needed nor authorized to finish this
feature validation. Stop here: review one diff and resolve concrete decisions before
any separate integration/publication or live authorization.

`MAIN_INTEGRATION=NOT_AUTHORIZED` · `LIVE_ACTIONS=NONE` ·
`PUBLICATION=FEATURE_VALIDATION_ONLY` · `STAGING_RELEASE_READINESS=NOT_ESTABLISHED`

## Preserved phase progression and earlier results

The following progress text and local-phase statuses describe their recorded time;
the current disposition is the final candidate03 table above.

## Contract-closure and feature CI phase

The initial24-file uncommitted changeset exactly matched the saved final inventory.
HEAD/base/tree/branch and initial repaired sequencer SHA256
`42fcdd8350fd62df048e23916ed0e33780b5ce69f5e63e3bfaf4b5c4b9853781` were verified.
The received reports, diff, hashes and prior final evidence were copied before edits
under `/private/tmp/ht-release-repair-01.RKbe7q/evidence/feature-ci-lqfjbouh/initial/`.

New integration work:

- Baseline is now consistent at11 named artifacts plus operation-log,12 total; actual
  receipts lacking database-target-identity refuse. Historical11-artifact receipts
  are not updated or reinterpreted.
- Known completed target bindings can be explicitly retired using real terminal
  receipt/predecessor verification plus an approved/applied source-bound handoff.
  History and lock inode remain; next run must establish a fresh baseline. Actual
  CLI15 regressions pass locally. UNKNOWN inspection exists, but clearing daemon
  uncertainty remains **OPEN (F05-UNKNOWN)**, never inferred from PID/reboot/reaping.
- F09 target policy is accepted, with separate V125 schema125 and completed-V126
  handoffs. Application moment remains unauthorized. Ordinary deploy gains fixed
  image/root-ownership checks and conservative refusal of any cutover registry;
  full target-lock integration of ordinary deploy remains **OPEN**.
- Real Caddy and connected PostgreSQL17/Docker/JVM checks are wired into the existing
  compose job, alongside the full unchanged Linux-required harness and existing
  real libpq/backup/container-ID checks. Synthetic TLS Telegram has no external
  egress; the actual backend owns polling. This is connected consumer integration,
  not full cutover E2E, systemd/reboot proof or17 live manual assertions.
- Kotlin test containers use PostgreSQL17 without reuse. A nullable internal test
  transport override preserves production defaults. Actual webhook→worker→SENT and
  denied-row preservation are strengthened. CompileTestKotlin and ktlint pass; CI
  must execute all28 previously skipped PostgreSQL cases without skips. Existing
  classes/floors remain, with increases to inbound4/outbox19/webhook6 and exact
  mandatory maintenance case names.
- Workflow remains12 ordinary Ubuntu jobs with contents:read, no live environment,
  release image push or deployment trigger. Test endpoint wrappers refuse unexpected
  gh/SSH/curl endpoints and preserve violation evidence; they are an accidental-call
  fence, not an OS network sandbox. Tests use only synthetic app credentials.

First read-only `gh auth status` in the restricted shell returned an invalid-token
message (exit1). A scoped ordinary repository API read succeeded (exit0), establishing
GitHub access without changing authentication or network settings. This transport
context failure is not an application defect.

Pre-publication combined checks: full mandatory entrypoint attempt04 passed81 tests
(CI9, stdin10, status9, bindings15, readiness15, runtime-consumers6, configuration7,
PG17 evidence10), then exit1 at the required Linux/owned-SSH gate. Independent legacy
42 groups passed456 assertions (exit0); this separate invocation is not full Linux
harness PASS. Both guard summaries report zero unexpected endpoints. Attempts01/02
preserve the sandbox socket refusal and proved curl write-out compatibility defect.
The latter was fixed without changing the production readiness consumer. Final endpoint
self-test discovered14 cases:13 PASS and actual Linux sudo delegate1 SKIP; root-delegate
refusal latching was corrected and reviewed. Runtime diagnostic self-tests7 PASS,
including immutable first failure and redaction. Local Caddy adapter3 and handoff7 pass;
actual Caddy5 and real root metadata remain for CI. All63 embedded Python bodies compile.
The unchanged prerequisite-sync fixture suite hit an external900-second deadline
(attempt03, after an earlier180-second bound); it is NOT a suite PASS. Its8 HTTP-header
checks passed and the failure-matrix execution was incomplete. The original suite is
still mandatory in Linux CI; no deadline or gate was removed to produce local green.
The same unified review, including targeted contract/diagnostic addenda, found no
remaining actionable code defect before publication. It retains F05/F09 and runtime
gaps. Exact command/exit/version records and preserved failures are under
`/private/tmp/ht-release-repair-01.RKbe7q/evidence/feature-ci-lqfjbouh/`.

First validation candidate `d97c405c27ed06204af31d4db93284311900fae0`, tree
`02f4d859b6ae370405e2c1d5bc72b60143e2aa8f`, was committed and normally pushed only
to `codex/ht-release-repair-01-rkbe7q` (both exit0). Exact push CI
[34390646421](https://github.com/koteev-m/hookah_bot/actions/runs/34390646421)
completed with11 jobs PASS and compose FAIL; all12 jobs and step outcomes/log hashes
are preserved in `candidate-01/result-ledger.json` under the phase evidence directory.
No rerun, PR, main integration, deployment or authentication/settings repair occurred.

Established Linux results in that candidate: all81 leading regressions passed,
including real PostgreSQL17.11 full extracted safe/unsafe preflight with psql17.11/18.6,
TOC/timezone and whole-DB roles/ACL/settings/synthetic authentication. Container-ID
checks and process7 passed. The complete prerequisite-sync suite passed in40m11s,
including40/40 injected post-sync failures and the existing successful/recovery paths.
Actual Caddy2.6.2 passed8 methods and actual root/Compose guard passed7. All28 previously
skipped PostgreSQL cases executed:9 have individual counts; Outbox19 is established
by the exact selector/floor/mandatory-name zero-skip validator. See the saved database
proof map for exact lines and the distinction from per-job image inventory.

The full harness stopped at two Linux fixture defects: nested Python quoting made the
detached leaf fail with SyntaxError; a retirement test used JSON dict value order for
positional CLI fields. The real supervisor correctly refused the broken leaf. Both
fixtures are corrected, and the exact detached payload now runs in a portable local
regression; Linux lifecycle re-verification remains required. The later full legacy,
real libpq and backup portions of that failed harness were not executed.
The connected runtime built the real test image and verified all126 migration resource
bytes, then failed with KeyError('443/tcp') before any backend start. Its internal
Docker inspection lacked the assumed published port; its cause was not established.
The corrected fixture checks PG readiness separately and uses bounded owned loopback
TCP relays to exact validated container/network endpoints. The internal network and
actual libpq/curl/readiness consumers remain. Local diagnostic/relay14 tests PASS with
zero unexpected endpoint calls; actual Docker reachability and JVM/migration/progress
remain pending corrective CI. Synthetic Docker inventory is explicit in local tests.

Addressed local test-runner defects are also source-bound: normal timeout1s previously
allowed a sleep3s child to exit0; ordinary deadlines and external INT/TERM now preserve
non-success even if a child handler returns0. Failed/aborted private fixtures retain
mocks instead of deleting them under exiting children. The aborted local sync03
recorded five refused SSH fallbacks after its900s timeout; no external SSH was executed.
These refusals are distinct from zero-refusal completed harness/legacy runs. Native
Linux stat/SHA consumers replace costly portability wrappers while retaining every
metadata/hash request; equivalent behavior and full matrix must pass in the corrective
CI. Caddy/root/JVM now precede the long matrix, with every existing gate retained.
Abort quiescence across detached fixture sessions remains unproved; retained files and
PID observations are not remote/daemon completion evidence.
Final local deadline regression run:12 discovered,11 PASS and1 Linux-only SKIP,
exit0 with zero unexpected endpoints. The same unified review of all corrective joins
found no remaining actionable defect; AST/extracted-Python/Bash/diff checks pass.

The earlier local findings below retain their original boundary. Final feature
findings will be updated against the exact corrective candidate and individual steps.

Candidate02 `6702409fb0e0437118edd36f3d8f4b8494320eec`, tree
`3437a98006b9f2bfeed98e97c9b43e28a93dc469`, was explicitly committed and normally
pushed to the same feature branch (exit0). Exact push CI
[34397910541](https://github.com/koteev-m/hookah_bot/actions/runs/34397910541)
completed with10 jobs PASS and compose/Mini App browser smoke FAIL. All12 job logs,
step outcomes and hashes are retained in `candidate-02/result-ledger.json`.

The corrected Linux supervisor completed10 applicable tests (one non-Linux refusal
test skipped), including owned SSH disconnect, descendants, exclusion and completed
binding retirement. Real libpq11 passed with PG17 authentication. The harness then
passed its early legacy/container-ID/rehearsal-cleanup sections and reached real backup
tests:5 methods reported25 failures, including subcases. The first failure was the fixture SHA256
adapter accepting file arguments only; the new semantic-target fixture called the
actual production stdin hash consumer and was refused before any backup dump. A
local exact-driver before reproduced exit4. The adapter now forwards unchanged stdin
to actual sha256sum, preserving status; four portable methods pass, including binary/
empty input, valid stdout plus exit73 and actual file corruption refusal. Real Docker
backup/rehearsal and the remaining full harness still require corrective CI.

Runtime preparation proved PG17 readiness, owned internal relay setup, exact126
migration resources and actual Flyway125/no126. Before any backend start, actual
Compose2.38.2 refused `create --no-deps`; the safe24-byte stderr hash and location
identify the unsupported flag exactly. Real local Compose5.1.1 parser reproduces it
without daemon access. Both create call sites now omit that flag; backend has no
declared dependencies. Existing runtime self-tests now15 PASS, with zero unexpected
endpoints. Actual JVM migration/readiness/progress remains unverified until CI.

Candidate02 repeated Caddy8, root/Compose7, Docker IDs and all28 former PostgreSQL
skips successfully. Deadline/cleanup12 passed with no Linux skip. The complete
prerequisite matrix passed all18 groups,40 injected failures and retained recovery
paths in19m38s (candidate01:40m11s); this observed duration is not a general performance
guarantee. Native/fallback equivalence and the bounded microbenchmark also passed.

Mini App browser smoke executed216 cases:215 PASS, one debounce assertion FAIL at
`guest-smoke.spec.ts:9422` (request count2 instead of1 after fastForward299). Mini App
tree and job bytes match candidate01, where the test passed. Clock-boundary sensitivity
is a hypothesis, not an established cause or fixed defect. It is outside the proved
F01–F11 joins; no Mini App edit, skipped assertion or blind retry was made. A new full
CI run will follow only the two independently justified fixture corrections above.
The same unified review continues for that corrective diff; all release-blocking
UNKNOWN/ordinary-deploy/daemon/VM/DR/manual boundaries remain explicit.

## Preserved local-phase result (before feature authorization)

## Identity and preserved evidence

- Feature worktree: `/private/tmp/ht-release-repair-01.RKbe7q/worktree`.
- Branch: `codex/ht-release-repair-01-rkbe7q`.
- Exact base: `f7828e09863d391e1f714cc65c9c866f814cf6bf`;
  tree `ff66c8639ec7c5c3ad45c2371878f5ba3656df9a`.
- Original sequencer SHA256:
  `fae6a429951090b3e899ba7118a16319797f9215f4c5a609593a7b5634019d5c`.
- Audit REPORT/COVERAGE/CHECKPOINT at
  `/private/tmp/ht-release-audit-01-x5mmuf7s/` and recovery REPORT/CHECKPOINT at
  `/private/tmp/ht13-v125-recovery-exec-s22m246w/` were read completely. Referenced
  reproduction/agent evidence was used selectively, without repeating the full audit.
  No required source report was missing.
- The canonical `v126-cutover-20260909t113822z-f7828e09` remains PASS1–8 and intent9
  without PASS9. F01 means historical PASS8 cannot establish unsafe_count=0.
- Historical observation only: the supplied recovery report observed V125
  PRODUCT/OFF/public200, PostgreSQL125/noV126/drain absent/restart=no at
  `2026-09-09T14:15:41.464269+00:00`. No fresh server observation was made and no
  operator recovery was relabelled as native stage9 PASS.

## Prior local finding outcomes

Verification labels below describe the stated local boundary. They do not claim a
complete green Linux procedure or staging readiness. Priorities are unchanged.

| Finding | Status | Cause, change and before/after evidence |
| --- | --- | --- |
| F01 P1 | FIXED_AND_VERIFIED | Real extracted SQL used unsupported `\quit 3`: unsafe_count1 returned0 and the production wrapper emitted PASS on both psql17/18. Unsafe SQL now deliberately fails under ON_ERROR_STOP; safe output must contain exactly the structured version1/safe/count0 result. The consumer rejects exit0/empty and valid-looking stdout/nonzero. Full original extractor + actual wrapper + PG17 safe/unsafe passed with both clients; privacy and source binding retained. |
| F02 P1 | FIXED_AND_VERIFIED | Candidate pathname conflicted with fixed Compose `env_file`. Exact candidate and Compose bytes now occupy a private fixed `.env`; both real guards and effective backend maintenance values are checked before installation and again afterward. All four before transitions refuse; after OFF→SMOKE, SMOKE→OFF, recovery OFF and recovery SMOKE pass with actual Compose. Inverse drift and post-install failure refuse, unrelated bytes/uid/gid/mode are preserved, cleanup checked. Privileged Linux/crash behavior remains G02. |
| F03 P1 | IMPLEMENTED_UNVERIFIED | Bash conditional/substitution callers masked validate/install/reload and nested consumer failures. Explicit producer/caller exits and complete adapted-versus-admin Caddy JSON equality now gate completion. Real Bash before falsely completed; after configuration7 and runtime-consumer6 methods pass, including partial branches, valid stdout/nonzero, real local symlink, guards and cleanup. Caddy, sudo and systemctl are explicit spies; actual daemon failures/disk-runtime divergence still require Linux. |
| F04 P1 | IMPLEMENTED_UNVERIFIED | Running was treated as ready. Each startup retains one checked start followed by monotonic bounded read-only readiness; exact image/container/env/restart and three HTTP identities are fenced before/after. Before503 immediately refused; after delayed listener and503→200 pass with one start, wrong identity/exit refuses, timeout stays UNKNOWN. Real HTTP/processes, synthetic Docker; actual JVM/Docker startup remains unverified. |
| F05 P1 | OPEN | Local SSH/PID lifetime was not remote mutation authority. Pre-implementation protocol adds target lock, immutable run/action records, Linux subreaper and identity/log-bound ACK. Nonzero, lost ACK, unknown dispatch or unresolved children forbid continuation/recovery; reaped children do not prove daemon outcome. Same actual Compose source cannot be rebound through another directory. macOS refusal and actual ACK consumers pass; Linux/own-SSH/process cases await feature CI. Known-run retirement is implemented; UNKNOWN daemon reconciliation is not closed. Real crash/reboot, daemon side effects and concurrent recovery remain G02. |
| F06 P1 | IMPLEMENTED_UNVERIFIED | Unbounded requests/consumers could hang. Bounded process groups, transport/operation deadlines, SQL connect/statement/lock limits and HTTP limits preserve nonzero and classify mutation timeout UNKNOWN. Before hung actual HTTP exceeded the external test deadline; after readiness15 covers bounded hangs, stdout/nonzero and surviving children. Real remote Docker/DB/systemd interaction under interruption is unverified. |
| F07 P2 | IMPLEMENTED_UNVERIFIED | URI bytes did not establish target equality. Source SQL, host libpq and effective/running backend credentials/network/DNS are compared semantically; cluster epoch, DB/schema/OIDs/search path/current and session role are persisted as a baseline digest and rebound through subsequent envelopes. PG17 distinguishes plausible alternate DB/schema/roles; stopped/created plans and both container path labels are checked. Docker/Compose/DNS are synthetic in this pipeline; actual temporal network/recreate proof remains unverified. |
| F08 P2 | FIXED_AND_VERIFIED | Same dump TOC changed its archive-created display timezone. Exact dump/inventory hashes remain; only the strictly parsed creation display line is canonicalized. Before actual full-DR verifier passes UTC and refuses Tokyo; after both pass with real pg_restore17/18. Actual altered dump, ordered TOC entry and meaningful header/version differences still refuse. |
| F09 P2 | IMPLEMENTED_UNVERIFIED | Final availability/restart=no lacked durable image/config/ownership authority. The subsequently accepted handoff policy records eventual restart policy, permanent exact image selection, ownership, incident/reboot/recreate order and target-record retirement. No policy, file authority or handoff time was applied. See decisions below. |
| F10 P2 | FIXED_AND_VERIFIED | Failed read-only GitHub prechecks consumed mutation intent. Bounded reads now precede intent and retain create-only attempt logs/results/hashes. Only validated NOT_DISPATCHED attempts can repeat reads. Actual controller with executable Git/gh/SSH/Docker fixtures confirms repeated gh failure creates distinct sealed attempts, no intent/no remote effect and preserves prior bytes. Unknown or corrupt attempts cannot retry; no cached PASS or automatic run/build is added. |
| F11 P2 | FIXED_AND_VERIFIED | PENDING conflated absence, unknown and corrupt evidence. Status validates attempts, stage intent, receipt, recovery records and terminal marker before next_action. Nine real-controller/status tests pass, including orphan recovery intent before terminal, incomplete attempt and corrupt evidence. Five final before cases reproduce false next-action/classification. Unknown reports retry_allowed=false; availability is explicitly NOT_OBSERVED. |

Component details and command-level evidence:
[configuration](/private/tmp/ht-release-repair-01.RKbe7q/evidence/configuration/REPORT.md),
[database](/private/tmp/ht-release-repair-01.RKbe7q/evidence/database/DB-REPAIR-REPORT.md),
[readiness/product boundaries](/private/tmp/ht-release-repair-01.RKbe7q/evidence/readiness/REPORT.md).
The updated stage/recovery matrix and all gaps are in [REPAIR-COVERAGE.md](REPAIR-COVERAGE.md).

## Changes and test integration

The original local-phase runtime changes were confined to `scripts/v126-cutover.sh`,
`scripts/validate-staging-admission.sh` and its byte-identical embedded/external
`scripts/v126-database-evidence.py`. The coordinator alone edited the sequencer;
three bounded implementation/test areas were delegated without parallel sequencer writes.

New mandatory regressions: attempt/status, fixed-env/Caddy configuration, runtime
consumers, readiness, database evidence and remote operation lifetime. Existing
cutover, backup and stdin fixtures were adapted to the real new envelope/ACK and
consumer interfaces; no tested guard or ACK validator was replaced with exit0.
Dependency mocks are listed in the tests and component reports.

The existing `compose` CI job now requires PostgreSQL17 server, clients17/18 and
OpenSSH for these tests. Job names, existing checks, Kotlin/Playwright floors and
source-bound workflow validator remain. In the original local phase GitHub CI was not
published or rerun; the feature phase above now authorizes validation publication. Existing real Docker/libpq/backup checks remain mandatory.

Docs changed: deployment SQL tail, canonical cutover contract, QA strategy and the
current task checkpoint. New protocol, database rehearsal, operational handoff and
these three repair documents form the smallest cross-area handoff. In that phase Application and Mini App were unchanged. The feature phase adds only
an internal nullable test transport seam; product/RBAC semantics and migration bytes remain unchanged.

## Validation and limitations

The final command ledger, logs, syntax/compile result and Git inventories are under
`/private/tmp/ht-release-repair-01.RKbe7q/evidence/final/`; earlier failed iterations
remain in their component directories. They include fixture incompatibilities and
real implementation defects repaired before subsequent runs; an earlier PASS is not
used in place of a required affected rerun.

Established local results before the final integrated entrypoint:

| Command/scope | Result |
| --- | --- |
| `PG17_BIN=<own17.6/bin> PG18_BIN=<own18.0/bin> python3 scripts/test-v126-database-evidence.py` | exit0,10/10 after final source-label change; `database/complete-suite-03.log` |
| `python3 scripts/test-v126-configuration.py` | exit0,7/7 after partial-branch correction; final combined invocation repeats after trap change |
| `python3 scripts/test-v126-runtime-consumers.py` | exit0,6/6 after final producer/symlink corrections |
| `python3 scripts/test-v126-readiness.py` | exit0,15/15; real owned HTTP, synthetic Docker |
| `python3 scripts/test-v126-attempt-status.py` | exit0,9/9; final orphan-recovery case included |
| `python3 scripts/test-v126-remote-operation.py` | exit0,1 executed PASS,8 SKIPPED on macOS; not Linux verification |
| Isolated offline Gradle compileKotlin + selected maintenance/Telegram tests + ktlintCheck | exit0;22 executed PASS,28 PostgreSQL Testcontainers SKIPPED |
| Isolated HTTP/auth/RBAC/read-marker Kotlin tests | exit0;87/87 PASS,0 skipped |
| Shell syntax, changed Python and embedded Python compile, diff sanity | exit0; final source details in `final/syntax-compile.json` |

The required full entrypoint result and local legacy selection are recorded in the
final validation section below. A Linux requirement failure is an open gate, never a
successful complete harness. No local-only result establishes staging readiness.

Versions: macOS26.5/Darwin25 arm64; Apple Bash3.2.57; Python3.13.2; curl8.7.1;
Docker Compose5.1.1 config renderer; PostgreSQL17.6 source server/libpq and psql/pg_restore
17.6/18.0; Corretto JDK21.0.2; cached Gradle8.14.3. Official PostgreSQL tarball SHA256
and build command/exits are retained in database evidence. These builds deliberately
omit ICU/readline/zlib, so compression and production17.10 binary parity are not proved.

## Single independent review

[Unified review](/private/tmp/ht-release-repair-01.RKbe7q/evidence/review/REPORT.md)
reviewed the whole source/test/CI/docs diff and cross-area callers. Four actionable
findings were corrected within that same review: daemon outcome versus child reaping
(including alias-path exclusion), nested Bash producer/metadata errors, incomplete
attempt/recovery status, and Linux test temp-path portability. Source/after regressions
and exact file hashes are recorded there. No separate eleven-function approval process
or new full audit was used. Linux and complete integration gaps remain outside that
review's code-level conclusion.

## Exact remaining blockers and decisions

1. Ordinary Ubuntu Linux CI is now authorized and wired; execution is pending.
   A separate VM/daemon crash/reboot capability is still unavailable. Do not infer
   its result from process kills or container restart and do not create a VM automatically.
2. F05-UNKNOWN requires an explicit operation-specific daemon completion/fencing
   contract; inspection cannot clear it. F09 policy is accepted, but live handoff
   application and full ordinary-deploy target-lock integration are not authorized/proved.
3. Operational DR, real credential/host-auth custody, recovery point and permitted
   data loss remain undecided. Synthetic whole-DB restoration does not select them.
4. CI must demonstrate actual synthetic queue/write progress and all28 PG cases.
   All17 live Guest/Owner/MIX assertions remain a future separately authorized gate.

## Preserved local-phase side effects

Created this own feature worktree/branch, source/tests/docs, private evidence, isolated
PG source builds and synthetic clusters, owned HTTP/process fixtures and an APFS-cloned
Gradle cache/build outputs. Clusters and test listeners/children were stopped and their
fixture data cleaned by tests. Build/evidence files are retained for review. No container
or volume was created on a shared daemon and no global/host configuration changed.
Application secrets were synthetic; no real Telegram request, server mutation or release
archive was used. One initial full-harness fixture accidentally reached the existing
`gh run view 34146214650` read-only client after its shell mock stopped intercepting the
new subprocess boundary. The configured client returned CI data; no credentials were
printed/changed and no publication or CI mutation occurred. This was outside intended
isolated fixture behavior, was disclosed, and was fixed with an exact-argv executable
mock before rerun. The failed attempt is retained as `required-cutover-harness-attempt01`.
The next full attempt exposed stale stdin-envelope fixture offsets after the new DB
identity field; it is retained as attempt02. These failures are not Linux-gap evidence.

No Git add/commit/push/PR/merge, GitHub auth repair, CI publication, live SSH, deployment,
cutover stage or recovery was performed. Original dirty checkout, release/audit worktrees,
old runs/receipts/backups and scripts/dev were preserved. New source-bound artifacts apply
only to a future reviewed source; historical runs cannot be upgraded/adopted.

Staging deployment remains **not authorized**. Feature validation publication is
now authorized; a future behavior release needs separate integration/deploy permission,
green required CI and authorized smoke. Stop after this feature validation result.

## Preserved local-phase combined validation and Git status

The actual required entrypoint was invoked without deleting or bypassing any mandatory
check, with `PG17_BIN` and `PG18_BIN` pointing to the own builds, `DOCKER_CONTEXT` unset
and `DOCKER_HOST` pointing to a nonexistent private socket:

```text
bash scripts/test-v126-cutover.sh
exit=1; elapsed=89.567s
CI validator9 + stdin/ACK10 + status9 + readiness15 + runtime consumers6
+ configuration7 + database evidence10 = 66 executed PASS
remaining failure: Linux with test-owned OpenSSH endpoint is required; runtime gap OPEN
```

This is **not a complete harness PASS**. The mandatory Linux supervisor gate prevented
later real Docker/libpq/container/backup checks from executing. No check was weakened
or reordered to hide that result. The separate frozen-source local legacy selection
passed42/42 groups and455 assertions (144.923s); its explicit dependency mocks and source
hashes are in
[LEGACY-COMPATIBILITY.md](/private/tmp/ht-release-repair-01.RKbe7q/evidence/readiness/LEGACY-COMPATIBILITY.md).
Final native diagnostic supervisor run:1 executed PASS,8 Linux skips, exit0; this only
proves refusal on the unsupported platform. All61 embedded Python blocks, changed
Python files, affected Bash scripts and `git diff --check` pass. Required Kotlin checks
previously passed with109 executed tests and28 explicitly skipped PostgreSQL cases.

Sequencer SHA256 remained
`42fcdd8350fd62df048e23916ed0e33780b5ce69f5e63e3bfaf4b5c4b9853781`
before/after the frozen legacy and final complete-entrypoint invocations. Final helpers,
file hashes and a patch including new files are retained in `evidence/final/` for review.
No files are staged; migration/scripts-dev diff is empty.

Feature worktree `git status --short` (all listed files belong to this local changeset):

```text
 M .github/workflows/ci.yml
 M PROJECT_STATUS.md
 M docs/DEPLOYMENT_RUNBOOK.md
 M docs/TESTING_QA_SMOKE_STRATEGY.md
 M docs/V126_STAGING_CUTOVER_CONTRACT.md
 M scripts/test-v126-backup.py
 M scripts/test-v126-cutover.sh
 M scripts/test-v126-release-ci.py
 M scripts/test-v126-remote-stdin.py
 M scripts/v126-cutover.sh
 M scripts/validate-staging-admission.sh
?? REPAIR-CHECKPOINT.md
?? REPAIR-COVERAGE.md
?? REPAIR-REPORT.md
?? docs/V126_DATABASE_RECOVERY_REHEARSAL.md
?? docs/V126_OPERATIONAL_HANDOFF.md
?? docs/V126_REPAIR_OPERATION_PROTOCOL.md
?? scripts/test-v126-attempt-status.py
?? scripts/test-v126-configuration.py
?? scripts/test-v126-database-evidence.py
?? scripts/test-v126-readiness.py
?? scripts/test-v126-remote-operation.py
?? scripts/test-v126-runtime-consumers.py
?? scripts/v126-database-evidence.py
```

Original checkout `git status --short` remains its initial dirty state:

```text
 M PROJECT_STATUS.md
 M docs/TESTING_QA_SMOKE_STRATEGY.md
?? scripts/dev/
```

The original checkout HEAD remains `4daf5546fb622a6b967398f5c25b7bed41d7fa05`;
the feature HEAD/tree remain the exact requested base. `scripts/dev/` was not touched.
Staging deploy is needed only for a later separately authorized behavior release after
required green CI; it is not permitted or performed in this task.

`LIVE_ACTIONS=NONE` · `PUBLICATION=FEATURE_VALIDATION_ONLY` ·
`STAGING_RELEASE_READINESS=NOT_ESTABLISHED`
