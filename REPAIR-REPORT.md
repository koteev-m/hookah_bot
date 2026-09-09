# HT-RELEASE-REPAIR-01 — unified feature validation candidate

**HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**

`MAIN_INTEGRATION=NOT_AUTHORIZED` · `LIVE_ACTIONS=NONE` ·
`STAGING_RELEASE_READINESS=NOT_ESTABLISHED`

The user authorized explicit-file commits, non-force pushes of this existing feature
branch and ordinary GitHub-hosted Ubuntu CI as a validation candidate. PR, deployment,
main integration and live changes remain unauthorized. This document retains the
prior local-phase evidence below; it does not reinterpret historical receipts.

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

Current feature CI outcome is pending; no runtime finding is upgraded from planned
checks. First-failure and subsequent logs are saved in the phase evidence directory.
The earlier local findings below describe their original evidence boundary; final
feature disposition will be recorded after the exact candidate CI is inspected.

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
