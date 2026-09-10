# HT-RELEASE-REPAIR-01 checkpoint — final feature validation

## HT-RELEASE-REPAIR-01 — Final Open-Gate Closure (candidate03 failed; F06 correction)

**HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**. Same feature/worktree
`codex/ht-release-repair-01-rkbe7q`, exact base
`f7828e09863d391e1f714cc65c9c866f814cf6bf`, base tree
`ff66c8639ec7c5c3ad45c2371878f5ba3656df9a`, phase parent09e19461/tree17ec77da.
The four starting local report changes are preserved under
`../evidence/open-gate-closure-abp_tolk/initial/`.

Published candidate `90d0bda929f673cd2e277e6c243de9ae6bc5b0fc`, tree
`dd5a1d0f7089537a5591fb1a19a4a67d97ee482b`, parent
`cde7a0ebbf89e4d98489d24087fb53762bcd7e05`. Exact feature push
[CI34466473797](https://github.com/koteev-m/hookah_bot/actions/runs/34466473797),
CI/230370033, attempt1: **11 jobs PASS; compose FAIL only in owned systemd**.
Full mandatory harness PASS8m00; prerequisites18/18 groups and40/40 negative matrix
PASS1234.685s logged (1236s step). Ordinary native lifecycle PASS.

CI03 systemd reached4 process and12 Caddy observations. The hung reload correctly
returned4/UNKNOWN, no completion, with two remaining owned descendants; caller/unit
cleanup succeeded. But actual capture took39.465s, beyond the unchanged19–28s assertion;
systemd had already timed out its reload (ControlPID0). Late-continuation/stopped-service
checks did not execute. This is a proved F06 production I/O join, not an infrastructure
skip: inherited child stdout/stderr can hold Bash command substitution open after the
helper deadline. Exact extracted helper+unchanged native DRIVER reproduces0.2s→1.279s;
new regressions also expose exit0 with detached inherited streams. Do not mask this by
widening bounds or redirecting output only in the fixture.

The correction gives actual child consumers private stdout/stderr pipes and forwards
bounded queues under the monotonic deadline with nonblocking output. WNOWAIT retains
leader identity through bounded group cleanup; no reaped group is signalled. Missing
or surviving streams/output errors/deadline remain UNKNOWN. The outer shared target
supervisor still proves process lifetime; this helper does not fence daemon effects.
Native systemd fixture and production20s reload bound remain unchanged. Review and local
regressions include binary stdin/stdout/stderr, nonzero, detached pipes, backpressure,
merged-output flag restoration and setup refusal. Local readiness25/25, CI contract12/12,
unchanged systemd portable15/15 and runtime-consumers6/6 PASS, with no unexpected endpoints.
Fresh full Linux CI is required.

Ordinary actual proof now closes its previous provider fixture gap:19 ordered events,
exact lost-output terminal→fresh collector→RECONCILED_EFFECT→approved/applied synthetic
handoff→two same-lock deployments→restart/authority/UNKNOWN refusals. Final provider
unexpected0/outbound0/setChatMenuButton5, cleanup=true. No full20-stage E2E: earlier
prerequisites/approval are synthetic, ordinary public_checks=false and owned directstream;
actual SSH and local canonical typed chain are separately covered.

All28 former PostgreSQL skips execute. Current full harness, real PG17/libpq/backup/
Docker/SSH/JVM, eleven-job and ordinary proofs are in candidate-03/. Raw compose log
483434bytes/SHA25696e8c2bc377ee4a85dcd63da290cf4ba5ea1d8ab75ffe0d2fa6dbc81487e821b.
Same focused F05 review has no production blocker; F03/F06 need the new helper's native
verification. Prior candidate01/02/03 failures remain immutable, not blind retries.
Next: finish scoped F06 correction/review, explicit-file commit/normal same-feature push,
verify all12 jobs and mandatory steps, then publish final reviewed reports and verify
their exact candidate. All four final report files must be committed; no dirty result delta.

`MAIN_INTEGRATION=NOT_AUTHORIZED`; `LIVE_ACTIONS=NONE`;
`PUBLICATION=FEATURE_VALIDATION_ONLY`; `STAGING_RELEASE_READINESS=NOT_ESTABLISHED`.
Staging runtime application, operational DR/RPO/loss, host reboot and17 controlled
Guest/Owner/MIX assertions remain separately authorized future gates. No scripts/dev
read/write, original dirty checkout, historical migration/run/intent/receipt/archive/
backup or frozen evidence changes. Historical PASS1–8/intent9 and separately observed
V125 restoration remain historical, never a new native stage9 PASS. Stop after final
feature validation/report before main/PR/HT13/GateABC/live work.

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
