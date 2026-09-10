# HT-RELEASE-REPAIR-01 — final closure checkpoint

The unified F01–F11 implementation is FIXED_AND_VERIFIED at the explicit scopes in
[REPAIR-COVERAGE.md](REPAIR-COVERAGE.md). F05 UNKNOWN reconciliation and ordinary deployment
now share the persistent target lock and immutable history; no known code/protocol blocker
remains in the reviewed implementation. Exact implementation2866db99/tree296b37d0,
[CI34484968850](https://github.com/koteev-m/hookah_bot/actions/runs/34484968850),
CI/230370033/push feature/attempt1, passed12/12 jobs and mandatory steps.
Full Linux harness476s/18 printed suites/479 separate PASS markers; prerequisite18 groups,
40 negatives/97 expected exits1188s. All28 required PostgreSQL cases executed; browser216/216.

Actual Caddy/systemd13+4 cases/33 checks, corrected hung reload24.223s/UNKNOWN, connected
PG17/V125→V126/Docker/JVM and four one-start container IDs pass. Actual ordinary
reconciliation/handoff/two deployments pass with cleanup. Owned daemon five cases pass:
6 starts/readiness identities,5 daemon SIGKILL,1 supervisor SIGKILL,6 EMPTY/REAPED,
27 precise refusals, original records unchanged and UNKNOWN non-retryable. No container
was started by the daemon metadata fixture. This is not a complete native cutover,
uninterrupted JVM survival, host reboot or live smoke proof.

All six closure candidates and first failures are preserved in [REPAIR-REPORT.md](REPAIR-REPORT.md)
and local evidence. The last changes corrected the observed CI03 inherited-stream deadline
defect and CI05 private-daemon namespace/readiness fixture defect. The full manifest is
49 base-relative/32 phase-relative paths. Production sequencer SHA256
`d18a638fd64362a381b1d97b0cd7c647a965788cf46f8e783609404604bb45cf`.
Final delivery changes only four reports and three canonical docs; its exact SHA/tree,
remote ref, reviewed blobs and exact CI are bound by the immutable delivery attestation.

G01–G04 remain OPEN only at the explicitly recorded release boundaries: operational
DR/RPO/loss decision, actual host reboot/power/storage, real provider/delivery ambiguities,
17 live Guest/Owner/MIX assertions and separately authorized staging application/integrity.
JVM survival through daemon loss is an untested workload scope, not proven unavailable
infrastructure. Migration/recovery remains restart=no; an actual live handoff is not
approved by these tests. Restored V125 has its independent schema125 handoff boundary.

Next after accepted delivery: review the single diff and make a separate main/release
decision. Do not resume HT13, Gate A/B/C, recovery or staging. No staging deploy is required
for this patch validation; future runtime application/smoke remains a separate release gate.
Original dirty checkout, scripts/dev (no read/write), migrations and historical evidence
are preserved. Clean implementation worktree was observed before this docs-only update;
final clean Git status is required and recorded by the delivery attestation.

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

MAIN_INTEGRATION=NOT_AUTHORIZED; LIVE_ACTIONS=NONE; STAGING_RELEASE_READINESS=NOT_ESTABLISHED.

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
