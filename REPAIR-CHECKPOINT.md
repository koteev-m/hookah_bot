# HT-RELEASE-REPAIR-01 checkpoint — final feature closure

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


## HT-RELEASE-REPAIR-01 — Final Open-Gate Closure (CI04 passed; daemon gate pending)

**HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**. Same worktree/feature
`/private/tmp/ht-release-repair-01.RKbe7q/worktree`, `codex/ht-release-repair-01-rkbe7q`.
Exact basef7828e09863d391e1f714cc65c9c866f814cf6bf,
base treeff66c8639ec7c5c3ad45c2371878f5ba3656df9a; phase starts09e19461/tree17ec77da.
All four initially dirty reports remain preserved under
`../evidence/open-gate-closure-abp_tolk/initial/`.

Verified implementation candidate `391abc7135e74365dad69e34109b1bc2612caec7`,
tree `c70a0bbb936a043ab97ed137a8c67838af3dd021`, parent
`90d0bda929f673cd2e277e6c243de9ae6bc5b0fc`.
[CI34472343581](https://github.com/koteev-m/hookah_bot/actions/runs/34472343581),
workflow CI/230370033, feature push, attempt1: **12/12 jobs and all mandatory steps PASS**;
compose completed2026-09-10T12:12:18Z. Full mandatory harness PASS6m42;
prerequisites18/18 groups and40/40 negative matrix PASS16m56 by step timestamps.
This green candidate precedes the new required Docker daemon interruption fixture.
The fixture and its CI wiring require their own reviewed candidate and actual native run.

The proved F06 inherited-stream defect is corrected and native verified: unchanged actual
Caddy/systemd hung-reload case now23.579s within19–28s, exit4/UNKNOWN/no completion.
Actual reload ControlPID remains active at return; a separate later observation after
explicit own-process resume records completed reload/admin equality. Marker and original
UNKNOWN remain intact. All13 Caddy and4 process cases pass; callers reaped and own unit
cgroup empty/MainPID0/ControlPID0. Before39.465s and exact extracted-helper regressions,
review findings and all candidate01–03 first failures remain immutable.

F05 shared-lock/reconciliation/retirement and F09 ordinary lifecycle are native verified at
the recorded fixture scope. Nineteen ordinary events cover successful terminal/lost ACK,
fresh actual collector, distinct RECONCILED_EFFECT, synthetic approved/applied handoff,
two exact-image deployments/next binding and refusal cases. Provider unexpected0/outbound0,
cleanup=true. Earlier prerequisites/approval are synthetic; public_checks=false and direct
owned stream, not full20-stage E2E. Actual SSH and local typed-chain checks are separate.

The focused review identified actual Docker daemon interruption as still applicable,
not a proved unavailable capability or a new production-code defect. The new mandatory
fixture uses a second ephemeral owned daemon with private socket/data/exec roots and no
bridge/firewall/sysctl changes, preserving the default daemon. It exercises only real
synthetic create metadata, dispatch/reply loss and blocked replay/recovery/retirement
through the actual shared supervisor. Native verification remains pending; no claim of
JVM daemon-restart survival, full operational DR or host reboot follows from this fixture.

Current exact proof: candidate-04/result-ledger.json, core-compose-proof-final.json,
systemd-proof/current-extraction/systemd-proof.json, ordinary-proof/actual-proof-01.json,
completed-jobs-proof-01.json and database-proof/actual-proof-01.json. Raw compose log
497100bytes/SHA25641b98d8935a4f046d18a112a62d270a7d367a575ceae73fed941e912c98067cf.
All28 former PostgreSQL skips execute. Full details and per-F/G limits are in
REPAIR-REPORT/COVERAGE. No overall green CI substitutes for the pending native gate.

Next: finish/review the bounded new fixture, local regressions, explicit-file commit and
ordinary same-feature push; verify exact12 jobs and native steps. Then commit final
reviewed reports and validate that exact final candidate, leaving no dirty results delta.

`MAIN_INTEGRATION=NOT_AUTHORIZED`; `LIVE_ACTIONS=NONE`;
`PUBLICATION=FEATURE_VALIDATION_ONLY`; `STAGING_RELEASE_READINESS=NOT_ESTABLISHED`.
Operational DR/RPO/loss, actual host reboot/power-loss/storage durability,17 controlled
live Guest/Owner/MIX assertions and staging application remain separate future gates.
No scripts/dev read/write, original dirty checkout, migration bytes, historical runs/
intents/receipts/archives/backups or frozen evidence changes. Historical PASS1–8/intent9
and separately sourced V125 restoration are not a new stage9 PASS. No main, PR, HT13,
Gate A/B/C, recovery, deployment or live smoke is authorized by this result.

## Preserved prior phase checkpoint (09e19461 only)

**HT_RELEASE_REPAIR_FEATURE_CI_PASSED_WITH_OPEN_GATES**

Worktree `/private/tmp/ht-release-repair-01.RKbe7q/worktree`, branch
`codex/ht-release-repair-01-rkbe7q`; exact base
`f7828e09863d391e1f714cc65c9c866f814cf6bf`, tree
`ff66c8639ec7c5c3ad45c2371878f5ba3656df9a`.

Published candidate `09e19461cf54376714ae51f2d4c9e480f8365d8e`, tree
`17ec77daf4713264a36381a9d43ce4b4f4ef3151`.
[Exact push CI34428598301](https://github.com/koteev-m/hookah_bot/actions/runs/34428598301):
12/12 jobs and all mandatory steps PASS; compose complete2026-09-10T02:46:20Z.
Full mandatory harness PASS6m25; complete prerequisite18groups/40failure matrix
PASS19m37; actual Linux SSH/Caddy/root/PG17/backup/Docker/JVM proof retained.
All28 former PostgreSQL skips execute. Candidate02 unchanged Mini App failure is
preserved; candidate03 full216 smoke PASS does not establish its underlying cause fixed.

One changeset contains three normal feature commits; candidate01/02 failures and scoped
corrective before/after remain immutable in evidence/feature-ci-lqfjbouh/candidate-*.
Source sequencer SHA256:
`5911db8d4a0fc43a7ddb75b0cdaf2c0ef2c29720142715899463813226b8b98f`.
Final result updates to PROJECT_STATUS.md and three REPAIR documents remain local,
uncommitted; source/test/workflow exactly match the green candidate. Nothing staged.
One unified review covers whole diff, corrective joins and final evidence/status.

F01/F02/F04/F07/F08/F10/F11 FIXED_AND_VERIFIED at stated scope;
F03/F06/F09 IMPLEMENTED_UNVERIFIED; F05 OPEN. Full details in REPAIR-REPORT/COVERAGE.
Known-completed binding retirement is explicit, source-bound and preserves lock/history;
next binding needs fresh baseline. UNKNOWN has no implemented clearing path and forbids
replay/recovery/reset. Full ordinary-deploy locking/fresh-target race remains OPEN.
Accepted handoff policy does not authorize applying live env/restart/ownership changes.
G01 operational DR/RPO/loss/auth, G02 systemd/daemon/VM crash/reboot and G03/G04 delivery
ambiguity/17 real manual assertions remain open. Connected integration is not full E2E.

Original dirty checkout, scripts/dev, migrations, old runs/receipts/backups and frozen
evidence remain untouched. Canonical PASS1–8/intent9 and separately sourced historical
V125 restoration remain unchanged; no fresh live observation or stage9 PASS.

Stop after this result. Next is review of the single diff and concrete reconciliation/
operational decisions, followed only by separately authorized integration/publication
or live work. Do not start HT13, Gate A/B/C, recovery, a new archive, PR, CI rerun or
GitHub diagnostics automatically. Staging deploy is not part of this validation.

`MAIN_INTEGRATION=NOT_AUTHORIZED`
`LIVE_ACTIONS=NONE`
`PUBLICATION=FEATURE_VALIDATION_ONLY`
`STAGING_RELEASE_READINESS=NOT_ESTABLISHED`
