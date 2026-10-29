# Project Status

## HT-UX08-STAFF-PHOTO-DIRECT-MANAGEMENT-01 — ready for local review, acceptance open (2026-10-29)

**LOCAL_VERIFIED / READY_FOR_REVIEW / STAGING_ACCEPTANCE_OPEN**.
Identity PASS: `Hookah_Tootah`, primary root/common/index and origin
`github.com/koteev-m/hookah_bot`; branch `codex/ht-ux08-staff-ui-consent-fix-01`, starting/unchanged HEAD
`0256e62b21862ecef0de72b6eea3081ba789f2c6`; expected HEAD/base/branch/parent NOT_SUPPLIED.
Initial status: only `?? scripts/dev/`; all existing user changes preserved.

Explicit product-owner requirement supersedes the prior UX08 consent/separate-publish contract.
OWNER/MANAGER equally upload/replace/delete own-venue photos, including protected linked cards;
STAFF has own preview only, including denial of legacy photoRef self-edit. Account/profile/linkage/
finance rights and Guest/MANUAL/SCHEDULE/Today visibility are unchanged. Successful private PUT
atomically activates current and queues replaced bytes; no consent records are fabricated.
Legacy consent/publish/revoke deny without writes (403 STAFF/outsiders, 410 management); hide remains
an OWNER/MANAGER-only legacy action. Existing active photos survive, legacy candidates remain
private even on idempotent replay. No new migrations or bulk/irreversible data conversion.

Validation: photo62/62 + affected backend189/189 PASS; PostgreSQL executed, zero test skips/errors/
failures. Kotlin compile/ktlint, TypeScript/build, final focused Playwright38/38 and full450/450 PASS.
Docs sanity/diff check PASS; V127/H2 V128 byte-identical HEAD. Mobile/short/desktop screenshots
inspected. Existing Vite >500kB warning remains. First full browser failures were four old-contract
expectations, corrected while retaining account/profile/linkage denials; no skips/retries relaxed.

Evidence and exact changed-file list: [local report](docs/UX08_STAFF_PHOTO_DIRECT_MANAGEMENT.md),
`/private/tmp/HT-UX08-STAFF-PHOTO-DIRECT-MANAGEMENT-01/`, `.tmp-checks/ht-ux08-photos/screenshots/`.
No unfinished operations. Authorization local code/tests/docs only; no stage/commit/push/PR/merge,
SSH/deploy/R2/live data/secret changes. scripts/dev/ untouched. Next: review local diff, then separately
authorized publication, exact-release CI, staging deploy and manual iPhone acceptance. Local tests
are not staging acceptance, real R2 verification or production readiness.

## HT-UX08-STAFF-UI-CONSENT-FIX-01 — local verification complete, acceptance open (2026-10-29)

**LOCAL_VERIFIED / LIVE_CAUSE_NOT_PROVEN / UX08_ACCEPTANCE_OPEN**.
Identity PASS: project `Hookah_Tootah`, primary root/common/index, origin
`github.com/koteev-m/hookah_bot`. Branch `codex/ht-ux08-r2-ht70-config`, starting/current HEAD
`6951f468a9f86cfb246d9e1e32593ba0c4d6cec2`; expected HEAD/base/branch/parent NOT_SUPPLIED.
User reports staging release `c805d4d0af63b0c4135eb895741ae2559fe47afb`, PostgreSQL V127 and
technical deploy/R2 smoke PASS; no live verification performed in this task.

Local UI separates Accounts/Team, collapses profiles, loads private photos only when opened,
automatically uploads one selected JPEG/PNG privately, retains exact-key/revision recovery,
and exposes Manager own photo consent through existing rights. Open panels refresh on app return;
Back from own profile returns to its entry section. No backend runtime, storage or migration change.
Authenticated PostgreSQL Owner→Manager→Owner regression proves exact-photo SELF consent persistence,
fresh revision and separate publication; it does not prove the reported live incident cause.

Validation: photo48/48 + affected backend189/189 PASS (real PostgreSQL, zero skips/errors/failures),
compileKotlin/ktlint PASS, TypeScript/build PASS, focused78/78 then final navigation subset20/20,
final full Playwright450/450 PASS. Synthetic mobile390×844/390×568/desktop screenshots inspected.
Independent review found two P2s (repeated private reads and stale picker pending state), both fixed;
final review has no remaining actionable P0/P1/P2. Existing Vite large-chunk warning remains.

Evidence/queries/changed files: [local report](docs/UX08_STAFF_UI_CONSENT_FIX.md),
`/private/tmp/HT-UX08-STAFF-UI-CONSENT-FIX-01/`, `.tmp-checks/ht-ux08-photos/screenshots/`.
No unfinished operations. Authorization local code/tests/docs only. No commit/stage/push/PR/merge,
SSH/deploy/live writes; scripts/dev/, SQL helpers, stash/archive refs not touched. Staging/iPhone,
hosted CI and exact live account/profile/photo correspondence remain unverified. Next: review diff;
separately authorize staging/iPhone acceptance or the report's minimal read-only live diagnosis.

## HT-UX08-CI-PLAYWRIGHT-STABILITY-01 — bounded CI race fixes (2026-10-28)

Identity PASS at the primary Hookah root/common/index, origin `github.com/koteev-m/hookah_bot`.
Existing branch `codex/ht-ux08-r2-ht70-config`, exact starting HEAD
`76194b5da704292511a8640b3dcb14cbde9eb356`; sole immediate parent
`3b61b1fdcaf5bb2b3900791a4123cf3794e4c120`, as explicitly clarified by the user.
PR233 remains the existing Draft; no new branch/PR. Read-only job logs confirmed CI640
attempts 1/2 failed different tests; CI641 passed on the same starting SHA. No CI monitoring.

Controlled API barriers reproduced both original assertions on unchanged production code:
public-card initial GET overwrites an already editable city before save (product defect);
photo test navigates before access bootstrap pins `venueId` in the initial history entry
(test setup defect). Back to that unscoped entry deselects the venue and hides its retained-link.
A separate diagnostic confirmed the file and offscreen upload still survive. Hosted traces were
unavailable; the exact hosted response interleaving is inferred from identical controlled failures.
No shared fixture/context leak was found; CI uses two workers and page-scoped mocks.

Public-card controls now stay disabled until the initial snapshot succeeds, including read failure.
Failed-save draft, exact PUT values and explicit successful retry remain covered. Photo test waits
for a venue-scoped initial history entry and selected preview, then checks both Back/Forward and
pending upload retention. No timeout/retry/skip/config change or photo runtime change.

Validation on macOS, Node24.14.0/npm11.9.0, Playwright1.60.0: baseline isolated6/6 and ordinary
repeat10/10; controlled original failures2/2 reproduced; new loading checks failed on old code.
Fixed targeted15/15 (two workers, retries0); TypeScript and production build PASS. One full
CI/UTC smoke445/445 and the workflow's unchanged JSON validator PASS: zero failures/flaky/skips,
runner errors or failed attempts. Review found one P2 negative-mock counter bypass; non-GET now
falls through to the observed mutation fixture. Only that test-routing line changed after full
smoke; final targeted6/6 validates it with both original scenarios, production bytes unchanged.
Independent focused read-only review PASS after P2 closure; no remaining P0/P1/P2.
Diff/docs sanity PASS. Final Git publication evidence belongs in the task report.
Evidence logs/traces/report: `/private/tmp/HT-UX08-CI-PLAYWRIGHT-STABILITY-01/`.

Hosted Linux/Node20 CI for the new SHA, real Telegram iPhone and staging/R2 acceptance remain
UNVERIFIED. Backend/SQL/deployment bytes unchanged; no backend suite repeated. Four SQL helpers,
stash/archive refs preserved. Authorization: one local commit and one non-force push to the
existing branch after review/checks, then verify exact remote SHA and STOP. No SSH, providers,
Telegram mutations, server work, merge or deploy. A later release still needs exact-SHA green CI
and separately authorized staging acceptance. Next: publish verified fix; user tracks CI.

## HT-UX08-R2-HT70-CONFIG-FIX-01 — optional HT70 R2 configuration ready (2026-10-28)

**HT_UX08_R2_HT70_CONFIG_READY** for publication, not staging acceptance.
Identity gate PASS at primary Hookah root/common/index; origin `github.com/koteev-m/hookah_bot`.
Remote main verified at `3b61b1fdcaf5bb2b3900791a4123cf3794e4c120`; clean tracked checkout
moved from UX08 head `61d407319f9fe56495442dc662855c66f891159b` to new branch
`codex/ht-ux08-r2-ht70-config` at that exact main base. No historical rebase/reset.

HT70 accepts an optional strictly validated R2 block without altering old defaults/hashes.
`--apply-config` reconstructs the saved old runtime, verifies its trusted runtime/Compose hashes
and actual backend/DB binding; undeclared photo environment drift also refuses. Documentation
uses private generated HT70 Compose, not legacy `.env`. Backend/API/RBAC/photo lifecycle,
frontend, SQL, host/image/lock/DB/bootstrap/forward/backup/rollback guards are unchanged.

Validation: portable unit **64/64**, GHCR publication **11/11** (PyYAML 6.0.3), disposable
Docker **1/1** (108s, PostgreSQL17 V126 -> synthetic V127, real backup/isolated restore,
old runtime -> disabled synthetic R2 block, recreation/removal, neighbor retention and owned
cleanup). CI transport fixture now mocks `verify_ci`, replacing its stale unused `gh` stub;
production CI verification is unchanged. Independent read-only review PASS, no P0/P1/P2;
reviewer independently ran focused R2 tests **9/9**. Python AST, documented shell syntax,
docs sanity and diff checks PASS. No frontend repeat: frontend bytes unchanged.
Evidence: task tool results and local logs in
`/private/tmp/HT-UX08-R2-HT70-CONFIG-FIX-01/` (`unit.log`, `ghcr.log`, `docker.log`).

Hosted Linux Caddy/systemd/owned-SSH/libpq gates remain UNVERIFIED on macOS. Real R2/bucket
privacy/jurisdiction/permissions/secret delivery, iPhone smoke and installed staging state
remain unverified. V127 requires exact reviewed manifests and verified off-VPS backup;
no old-schema rollback. Four SQL helpers and stash/archive refs preserved.
Authorization: one focused commit and one non-force push; no PR/merge, SSH/SCP, server/provider
writes or live migrations. Exact commit/parent/tree/remote SHA belong in the task report.
Next boundary: publish, confirm exact remote SHA, STOP; user tracks CI. A later release needs
new exact-SHA green CI/candidate, compatible installed tool and separate deploy/live acceptance.


## HT-UX08-LINUX-RUNTIME-CI-REPAIR-01 — runtime fixtures prepared for CI (2026-10-28)

Identity PASS at primary Hookah checkout/common/index; branch
`codex/ht-venue-team-schedule-roles-ux08`, starting HEAD `151411cafd218642b44336ef85fb3c077a3424ca`,
parent `0728bde338b13d0c30dabbbad28afcda426b455e`, base `919abbb813cbbb724d628bdabeef8e905e6a1c42`.
PR232 remains Draft. CI635/636 runtime failures reached READY with a V127-containing current
backend, then failed exact V126 schema checks. Historical runtime now builds pinned unmodified
V126-compatible source; current JVM forward migration is a separate mandatory scenario. Historical
production guards/authority, SQL, workflows and product code remain unchanged.

Guest snapshot isolation now disables unrelated billing startup writes. Its missing subscription
for synthetic venue102 can be inserted between snapshots; deterministic H2 writer probe reproduced
1->2 subscription rows without any route request. Original CI expected/actual was not retained
in logs/artifacts, so attribution of that exact intermittent assertion remains an inference.

Available checks PASS: runtime17, ordinary23, backend67 (32 real PostgreSQL; zero failures/skips),
Guest repeat54, compileKotlin/ktlint, historical identity/drift negatives, syntax/diff.
Actual native JVM + Docker PostgreSQL17: V125->V126->V127 PASS; historical checksum1701638026,
photo checksum881920632,126 rows unchanged, Flyway validate PASS; V126 guard accepts126/rejects127.
Full hosted Linux runtime/ordinary Caddy/systemd/libpq18 gates remain UNVERIFIED on macOS and
must pass for the new SHA before merge. No frontend repeat: product/UI bytes unchanged.

Evidence: `.tmp-checks/ht-ux08-runtime-ci-repair/validation.md`, XML and local JVM result.
Four SQL helpers and stash/archive refs preserved. User authorizes one focused commit and one
non-force push after available checks/review; this is CI validation, not release acceptance.
After exact remote SHA verification STOP; user/ChatGPT checks new CI. No deploy, SSH, external
provider writes, real Telegram actions, migration of live databases or rollback-safety claim.

## HT-UX08-MIGRATION-CI-FIX-01 — local fixes; Linux validation blocked (2026-10-28)

**HT_UX08_MIGRATION_CI_FIX_BLOCKED**. Three CI regression assumptions repaired locally; no commit,
push, merge, deploy, Cloudflare/server/Telegram mutation. Both original UX08 commits preserved.
Identity PASS: Hookah_Tootah primary checkout/common dir/index, expected branch
`codex/ht-venue-team-schedule-roles-ux08`, HEAD `0728bde338b13d0c30dabbbad28afcda426b455e`,
parent `1acdd0afb61702f8ca39b8492b3835bf83b1c012`, base `919abbb813cbbb724d628bdabeef8e905e6a1c42`.
Read-only origin feature/main matched HEAD/base. CI633/634 independently confirmed terminal red
on this exact HEAD; job logs verified the cursor pending-tail, manifest127vs126 and historical-tree
assertion failures. Missing Caddy/pg_dump/systemd-tool failures followed skipped prerequisite install.

Changed tests retain historical cursor heads and rollback/index contracts, assert exact reviewed
pending tail and apply full PG127/H2128. Real bootstrap126/current127 manifests exercise existing
fresh-install/review/backup/rollback refusal policies. Cutover harness checks the pinned historical
snapshot independently from the reviewed append-only working tree, with byte/inventory/executable
mode/tail negatives and unchanged production guard rejection of the current release tree.
Historical trees/blob/SHA/Flyway fingerprints, all SQL, production guards/controller, workflow,
Mini App and product runtime bytes are unchanged. Four SQL helpers and stash/archive refs preserved.

Executed: cursor10/10; full migration-sanity plus photo persistence165/165 (83 real PostgreSQL,
16 suites, zero failed/errored/skipped); compileKotlin/ktlintCheck PASS. Simple staging55/55;
focused lineage/production guard positives+negatives PASS; actual psql17/libpq17/PostgreSQL17
11/11 PASS. Read-only independent release-safety source review PASS; executable-mode P2 closed.
Full cutover harness attempted and FAILED at Linux-specific `/usr/lib/postgresql/17/bin/psql`
in database-evidence fixtures; Caddy/systemd hosted-Linux guards also refuse macOS. These are
validation-environment failures, not PASS/skips. Hosted libpq18, complete cutover and required
Compose Linux Caddy/systemd/connected-runtime gates remain unverified; conditional commit withheld.

Evidence: `.tmp-checks/ht-ux08-migration-ci-fix/validation.md`, saved XML/JSON and logs. Index empty;
seven tracked test/docs/checkpoint edits remain for continuation, plus original `?? scripts/dev/`.
No frontend rerun: its committed bytes remain unchanged and prior exact UX08 evidence applies.
Next: validate these exact changes on an explicitly authorized disposable Linux runner with all real
prerequisites; only after full PASS create the one authorized local fix commit. Publishing it remains
separately authorized. No workflow dispatch/rerun is authorized by this local task.

## HT-UX08-STAFF-PHOTOS-R2-IMPLEMENT-01 — R2 photos local ready (2026-10-28)

**HT_UX08_R2_PHOTOS_LOCAL_READY**. UX08 E is implemented and validated locally; this supersedes
its earlier storage-decision blocker. Existing A–D commit is preserved. No live R2, real iPhone
Telegram, staging or production acceptance is claimed. No push/PR/SSH/deploy/provider writes.

Identity PASS initially, after resumption and before commit: Hookah_Tootah; primary root
`/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, matching common dir/index and registered checkout,
origin `github.com/koteev-m/hookah_bot`, no foreign instructions/redirects. Branch
`codex/ht-venue-team-schedule-roles-ux08`; exact starting HEAD/required child parent
`1acdd0afb61702f8ca39b8492b3835bf83b1c012` (parent `919abbb813cbbb724d628bdabeef8e905e6a1c42`,
tree `b5e56ba08e45f89fb1786de08db26075e549bd29`). One additional local allowlist commit authorized
with message `feat(venue): add secure staff photo upload with R2`; exact commit identity in task evidence.

Implemented private R2 Standard adapter (AWS SDK Java v2 2.57.5), JPEG/PNG validation/orientation/
metadata stripping, PostgreSQL V127 / H2 V128 durable reservation/consent/current/candidate/cleanup,
revision/idempotency and safe partial-failure recovery. Existing profile RBAC and authoritative Guest
visibility remain enforced; employee self-consent/revoke is separate from Owner/Manager publication.
Identity/role/removal invalidation uses venue-first locking. Integrated Mini App photo panels,
Staff own card, Guest guarded blobs, truthful pending/error states and scoped Back retention.
Configuration is disabled by default and accepts only the existing staging bucket
`hookah-tootah-media-staging-eu`; production is explicitly disabled pending separate admission.

Validation: **236 backend tests PASS** (47 photo +189 latest-per-suite regressions), zero skips/errors;
**29 PostgreSQL cases executed**. Two existing deterministic lock tests were adapted to venue-first
serialization without weakening winner/last-Owner/RBAC/audit assertions. compileKotlin/ktlintCheck,
TypeScript and production build PASS (existing Vite bundle warning). Focused Playwright **28/28**,
full smoke **444/444**.27 synthetic screenshots cover9 states×3 viewports. Migration parity,
docs/diff/secret-pattern sanity and helpers/stash/archive preservation PASS. Independent read-only
backend/storage/security and privacy/Guest/UI reviews PASS, no open P0/P1/P2.

Evidence: `.tmp-checks/ht-ux08-photos/validation.md`, photo/regression result JSON and saved XML,
`playwright-full-final.log`, `screenshots/`, `precommit-identity.json`, `preservation.json`,
`review-files.json` and postcommit `commit.json`. No tests prove Cloudflare connectivity.
Four untracked SQL helpers and stash/archive refs remain unchanged; `scripts/dev/` untouched.

Next step: separately authorize staging configuration verification and bounded synthetic live R2
smoke. Owner must verify private access, Standard class, actual jurisdiction/endpoint and bucket-only
credentials through a protected server channel. Production still requires an approved bucket,
privacy/legal acceptance, operator/rotation/usage controls and deletion-safe backup/restore evidence.
Detailed configuration/acceptance checklist is in `docs/MEDIA_STORAGE_UPLOAD.md`.

## HT-VENUE-TEAM-SCHEDULE-ROLE-UX08 — UI local ready; photo decision required (2026-10-28)

**HT_UX08_UI_LOCAL_READY_PHOTO_DECISION_REQUIRED**. A–D implementation and required local
validation PASS; independent focused review closed all P0/P1/P2. E is
**BLOCKED_BY_STORAGE_DECISION**, with a concrete proposal in the existing media spec. One explicit
allowlist local commit is authorized; exact SHA/parent/tree is in the task result. No push, PR,
merge, SSH, deploy, external credentials or Cloudflare writes. No staging/production acceptance.

Identity PASS initially, after preparation and before commit: `Hookah_Tootah`, primary physical
root `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, checkout-owned `.git`/common dir/index,
origin `github.com/koteev-m/hookah_bot`; verified instruction origins, no redirects/foreign authority.
Initial constraints NOT_SUPPLIED; initial branch `codex/ht-staff-reissue-schedule-ux07`, HEAD
`58cb4a995630881eed8983bf1bb3771a10f73a30`, only four untracked SQL helpers. After initial PASS,
remote main verified at `919abbb813cbbb724d628bdabeef8e905e6a1c42`; targeted fetch obtained that
object. Ordinary branch `codex/ht-venue-team-schedule-roles-ux08` starts at that exact base.
No worktree, local-main rewrite, stash or archive mutation. Remote observation is preparation-time
evidence, not a claim about later remote state.

Implemented:
- A: editable, optional normalized `displayName` in create-from-member, compatible legacy default,
  single atomic hidden draft and exact Telegram link; no user/membership/publication change.
  Existing protected-target RBAC, race/link conflict and audit rollback remain enforced. No migration.
- B: collapsed daily schedule, separate computed-status counts, per-date add, stable toggles/focus,
  same-week expansion retention and week/venue reset. Existing shift/batch/CAS/time/restore contracts.
- C: nested confirmation/form/week history through one Telegram handler, screen and browser controls;
  raw in-memory draft retention, explicit discard, guarded writes and stale/foreign editor rejection.
  Close/save traverses only proven live panel entries; unknown history/state is preserved.
- D: common role shell, Manager operational Home/compact Settings/More and Staff Home/My shifts/More,
  existing permission-filtered routes and safe Back parents. No server permission widening.
- E: no proven Hookah application R2/photo pipeline. Separate AP-00 S3 backup transport is not it.
  Proposal: private dedicated Hookah R2, guarded delivery, consent/moderation, validation, atomic
  lifecycle/cleanup and deletion-safe backup. Operator/budget/retention/backup approvals remain open.
  Corrected stale Draft Preview media documentation against existing authenticated private routes.

Validation on final implementation/test bytes:
- Kotlin compile/ktlint and selected API/repository/RBAC/Guest/Preview suites: **175/175**, 16 suites,
  no failures/errors/skips; real PostgreSQL link and staff mutation concurrency **4/4** executed.
- TypeScript and production build PASS; existing Vite >500 kB bundle warning remains.
- Focused Playwright **37/37**, full Chromium smoke **432/432**, no skips. Existing UX02/UX05–07
  history, role, async and privacy regressions retained; obsolete menu/form locators updated.
- Two read-only reviewers: A–D/API/navigation and E/media proposal PASS; all P2 corrected with
  relevant regression checks. Final `git diff --check`, UTF-8/fences/local links and bounded added
  content credential scan PASS. Implementation hashes recorded for test applicability.
- 27 synthetic screenshots: role homes, week, 6/10 expanded shifts, form/confirmation, profile name
  at 390x844, 390x568 and 1280x900. Browser evidence does not prove real Telegram keyboard/WebView,
  safe-area or native BackButton acceptance. Historical UX07 iPhone 19/19 is not UX08 acceptance.

Evidence: [validation and exact paths](.tmp-checks/ht-ux08/validation.md),
[backend results](.tmp-checks/ht-ux08/backend-results.json),
[full smoke](.tmp-checks/ht-ux08/playwright-full-final.log),
[focused smoke](.tmp-checks/ht-ux08/playwright-focused-final.log),
[review](.tmp-checks/ht-ux08/review.md), [preservation](.tmp-checks/ht-ux08/preservation.json),
[screenshots](.tmp-checks/ht-ux08/screenshots/).
Evidence is ignored/local. Four SQL helpers remain byte-identical; stash/archive refs unchanged.
No unfinished external operation. Runtime A–D requires separately authorized publication/exact-SHA
CI, staging and real iPhone acceptance. Next: user approval of the concrete photo storage decision
and/or a separate publication task; stop at this local result.

## HT-STAFF-INVITE-REISSUE-SCHEDULE-UX07 — local validated; staging pending (2026-10-28)

UX07 local implementation and required validation complete; focused independent review PASS,
no open P0/P1/P2. One explicit-allowlist local commit is authorized; its SHA/parent/tree belongs
in the task result. No push, PR, merge, deploy, SSH or server write. This is not staging acceptance.

Identity gate PASS before preparation and after branch switch: `Hookah_Tootah`, primary physical
root `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, checkout-owned Git/common dir and index
under `.git`, origin `github.com/koteev-m/hookah_bot`. Initial HEAD/base/parent/branch constraints
NOT_SUPPLIED. Initial HEAD `d33007950dba3d37c6f465e919c8fe1a3393b3ea`, UX06 branch; only the four
original untracked SQL helpers, no tracked edits. After PASS, read-only remote main verification
returned `990dad46a47aae4362f6c49f629bc6aa73365d9e`; targeted no-tags fetch obtained that object.
Ordinary branch `codex/ht-staff-reissue-schedule-ux07` starts at that exact base. No worktree or
stash/archive operation; local main was not rewritten. Root/global/session instruction origins
verified; no narrower instructions, foreign authority or Git path redirect.

Implemented:
- Atomic `POST /api/venue/{venueId}/staff/invites/{handle}/reissue`, no body fields. Same-role
  replacement and default TTL, exact source pending claim, current actor membership permissions,
  shared create limiter/quota and existing revoked/created audits in one transaction. Lock order
  source invite -> venue -> actor; no migration. Owner STAFF/MANAGER, Manager STAFF only;
  no OWNER escalation or secret pending fields. UI confirmation, current-screen-only new link,
  old-handle tombstone and GET recovery after an unknown response; no automatic POST or Telegram send.
- Schedule: one common time pair, compact summary and opt-in individual drafts for multiple
  employees; explicit reset and canceled restoration; 2->1 keeps the remaining actual interval.
  Compact week arrows/empty days, date-specific add, clipped horizon-edge weeks, heading focus
  without input autofocus. Stale preflight cannot reopen a changed/closed editor; unknown mutation
  result requires reading current state. Schedule API/schema/CAS/batch/audit/shiftId/RBAC,
  venue-local time/DST/defaults/overnight and MANUAL/SCHEDULE Guest semantics remain unchanged.
- Canonical Staff, Venue Operations and QA docs updated. Addressed invites and photos remain future.

Validation on final implementation:
- `*StaffInvite*`, `*VenueStaff*`, VenueRbacRoutesTest, GuestVenueRoutesTest,
  VenueGuestPreviewRoutesTest, AuditLogRepositoryTest and MiniAppAbuseProtectionTest: **181/181**,
  18 suites, failures/errors/skipped 0. PostgreSQL reissue **4/4** executed: observed blocked backend
  PIDs for accept/revoke/reissue, two replacements plus bounded create, demotion/removal, expiry
  after lock wait and second-audit failure rollback. H2 covers role/TTL/one-use/secret-free projection.
- TypeScript `--noEmit`, production build, compileKotlin and ktlintCheck PASS. Existing Vite
  >500 kB bundle warning remains. Focused Playwright **26/26**, full Chromium **419/419**, no skips.
- Independent review found two P2 during development (2->1 interval loss and stale preflight);
  both fixed, regression-tested and independently closed. Final review PASS, no actionable findings.
- Synthetic screenshots visually checked at 390px, 390x568 and desktop. Actual Telegram iPhone
  keyboard/WebView and deployment behavior are unverified and require separate acceptance.
- `git diff --check`, docs UTF-8/fences/link sanity and bounded added-content secret check PASS.
  Four SQL helpers remain byte-identical; stash/archive refs untouched; index empty before staging.

Evidence: [validation](.tmp-checks/ht-ux07/validation.md),
[backend results](.tmp-checks/ht-ux07/backend-results.json),
[backend regression](.tmp-checks/ht-ux07/backend-regression.log),
[full Playwright](.tmp-checks/ht-ux07/playwright-full.log),
[focused Playwright](.tmp-checks/ht-ux07/playwright-targeted.log),
[review](.tmp-checks/ht-ux07/review.md),
[preservation](.tmp-checks/ht-ux07/preservation.json),
[mobile](.tmp-checks/ht-ux07/screenshots/common-mobile.png),
[small height](.tmp-checks/ht-ux07/screenshots/form-short.png),
[desktop](.tmp-checks/ht-ux07/screenshots/form-desktop.png).
Test evidence is local/ignored; no real invite, member or external message was changed.
No unfinished operation. Next: separately authorized publication/exact-SHA CI, then staging deploy
and real iPhone acceptance (reissue/lost response, common/individual/restore, focus/BackButton).

## HT-UX06-CI-FAILURES-FIX-01 — local fixes validated (2026-10-27)

**HT_UX06_CI_FIX_LOCAL_VALIDATED** — both reproduced test defects fixed; independent focused
review PASS, no open P0/P1/P2. One local commit is authorized after final identity/preservation
checks; commit identity is reported in the task result. No push, PR update, rerun, merge or deploy.
Original red CI remains historical evidence; this does not claim a new CI or staging PASS.

Identity gate PASS: `Hookah_Tootah`, physical primary root
`/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, Git/common dir `.git`, checkout-owned index,
origin `github.com/koteev-m/hookah_bot`, branch `codex/ht-venue-staff-invites-ux06`.
Starting/expected HEAD `ea47a273d79ca8dec590607a47f4096e82653cda`; verified first parent
`446d6a6785ff43a2c9253bead77ed010712a6851`; separate BASE NOT_SUPPLIED. Root/global/session
instruction origins verified, no narrower authority or path redirects. No new worktree.
Initial status only four untracked SQL helpers; no pre-existing tracked edits.

Diagnosis and changes:
- Push CI [40561612292](https://github.com/koteev-m/hookah_bot/actions/runs/40561612292)
  tested exact starting HEAD; PR CI
  [40561707637](https://github.com/koteev-m/hookah_bot/actions/runs/40561707637) tested its merge
  into the verified parent. Both report 1438 tests, one failure in the new invite handle test.
  No JUnit artifacts were retained. Linux isolated reproduction identifies expiry equality at
  original line 1173: `.406193543Z` vs stored `.406194Z`; handle equality already passed.
  H2 TIMESTAMP(6) rounds JVM nanoseconds to microseconds; macOS clock precision hid the invalid
  string-equality expectation. Route test now compares the exact rounded Instant and checks order;
  repository samples cover rounding down, half-up and second carry. STAFF/MANAGER/STAFF, exact
  handle set/count, repeated refresh and secret-free keys remain enforced. Production API,
  schema/migrations, cap, RBAC, audit, accept/revoke and sorting code are unchanged.
- PR Compose failed SIGINT elapsed 5.028s after accepting status130. Inherited SIG_IGN reproduces
  the same five-second/130 failure on macOS and Linux; the child had no explicit INT handler.
  A delayed wrapper also proves child readiness can precede parent handler installation.
  Child handlers now precede atomic PID publication; timeout mock has a separate optional
  handler-ready handshake. Forwarding still requires <2s and exact130/143; six-second independent
  communication bound remains. Failed readiness/communication cleans the owned child group
  before another subtest replaces its record. Old-cleanup negative control fails that regression.
  Historical runner signal disposition/scheduling is unobserved; no deployment SIGINT defect
  was established or deployment code changed.

Validation: isolated backend baseline macOS PASS/Linux FAIL -> fixed Linux PASS; full
VenueStaffRoutesTest35 plus invite/staff repositories, RBAC, audit, session/auth: **112/112 PASS
on both macOS and Linux**, no skips/failures. Offline Gradle compileKotlin/ktlintCheck PASS.
Linux uses Temurin21/H2, JUnit Platform and the same Gradle-compiled classes/runtime classpath
in a network-disabled read-only container (aarch64, not the original CI x64 runner).
Final Python fixture suite Linux **15/15 PASS**, macOS **14 PASS + one GNU-only skip**;
Linux shell signal matrix PASS INT130/TERM143 plus header tests8/8. Shell/Python syntax and
`git diff --check` PASS. No full release suite, new Actions run or PostgreSQL runtime claim.

Evidence: [validation](.tmp-checks/ht-ux06-ci-fix/validation.json),
[CI excerpts](.tmp-checks/ht-ux06-ci-fix/ci-excerpts.txt),
[Linux backend baseline](.tmp-checks/ht-ux06-ci-fix/backend-baseline-linux.log),
[Linux backend final](.tmp-checks/ht-ux06-ci-fix/backend-full-focused-linux.log),
[macOS backend](.tmp-checks/ht-ux06-ci-fix/backend-full-focused-macos.log),
[Linux signal baseline](.tmp-checks/ht-ux06-ci-fix/compose-ignored-int-baseline-linux.log),
[Linux final](.tmp-checks/ht-ux06-ci-fix/compose-fixed-linux.log),
[review](.tmp-checks/ht-ux06-ci-fix/review.md). One focused review found and closed one cleanup P2;
no repeated broad audit. Four original SQL helpers remain byte-identical; stash/archive refs
unchanged. Test artifacts only under ignored `.tmp-checks`; no real external operation.
These test-only fixes need no staging deploy; the UX06 feature retains its separate staging/iPhone
acceptance requirements. Next: separately authorized publication and fresh exact-SHA CI; no
automatic push/rerun or CI monitoring in this task.


## HT-VENUE-STAFF-INVITES-UX06 — local READY (2026-10-27)

**HT_VENUE_STAFF_INVITES_UX06_LOCAL_READY** — agreed UI and additive handle contract only.
All required local checks passed; this is not staging/production acceptance. The earlier PARTIAL
limitation is resolved by the user's explicit follow-up permission for additive existing opaque handle in
StaffInviteResponse/TypeScript DTO and exact single-card correlation. No SQL migration, RBAC or
accept/revoke logic change, permanent browser storage, commit/push/PR/merge/deploy/SSH is authorized.
Addressed invitations and durable staff photos remain proposals, not implemented features.

Identity gate PASS before preparation, after preparation and on this resumption. Expected/observed:
`Hookah_Tootah`, primary physical root `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, Git/common
dir and checkout-owned index in `.git`, origin `github.com/koteev-m/hookah_bot`. Root AGENTS + empty
global, no narrower instructions, foreign authority, GIT path overrides, includes or alternates.
Initial HEAD/base/parent constraints NOT_SUPPLIED; initial branch `codex/ht-venue-team-access-ux05`,
HEAD `fe2b2894cb5e07c5240ab43f81541996650b5711`, status only original `?? scripts/dev/`. Authorized
no-tags targeted fetch verified `origin/main = 446d6a6785ff43a2c9253bead77ed010712a6851`; ordinary local
`codex/ht-venue-staff-invites-ux06` still has that exact HEAD/base. Same checkout, no new worktree.
No parent/ancestry constraint inferred. Working changes are this task's authorized changes.

Implemented:
- Backend returns only existing `result.handle` as an additive response field; pending DTO keeps
  exactly handle/role/status/createdAt/expiresAt and receives no code/hash/link. Repository, SQL,
  RBAC, accept/revoke claims and Guest privacy are unchanged. Older clients keep prior fields;
  TS optional handle permits safe explicit fallback against an older server.
- One expandable card per exact handle merges creation and pending. Latest session-created links
  appear first; **Готово**, next Staff/Manager creation and read-only refresh retain each link
  in screen memory without duplicates. No role/time/list-delta correlation, hash reconstruction,
  automatic POST or browser persistence. Section/venue/account disposal and page reload clear copies.
  Older no-handle response retains copies with a warning and hides uncorrelatable server-only rows.
- Copy/share do not imply delivery, recipient or acceptance; canceled share and popup null/throw
  retain the screen/link. Explicit expiry/confirmed revoke disable share/copy. Missing handle after
  successful GET says **Нет в действующих приглашениях**, never an invented acceptance/accepter.
  GET error retains last data and links with warning. Sequence/abort/snapshot guards and tombstones
  prevent stale create/revoke reads from losing or resurrecting a handle. Passed original expiry
  is observed even after revoke, preventing repeated row rebuild/focus loss.
- Own account **Это вы** + role without inert role/remove controls. One primary Guest visibility
  and reason, existing Guest Preview public/private mode and IDs for both sources; MANUAL has no
  planned-shift prerequisite, SCHEDULE includes overnight presence. Writes/reads invalidate stale
  Preview; label-only completion preserves editor focus/drafts. Accepted members can link existing
  cards after refresh, linked cards open, Manager/protected/foreign rules remain unchanged.
- Photo UI says upload unavailable. [Design](docs/HT_VENUE_STAFF_INVITES_UX06_DESIGN.md): recommended
  addressed-invite operation uses server-bound prepared request_users/trusted UsersShared and exact
  target ID during claim, preserving old unbound invites. No target schema/model implemented.
  Staff photo pipeline is absent: agree private S3-compatible provider/region, operator, budget,
  validation, consent/approval, private draft, Guest delivery and replacement/delete lifecycle first.

Validation on final content:
- TypeScript --noEmit PASS; production build PASS (existing Vite >500 kB bundle warning).
- Final focused Playwright **27/27 PASS**, including 23 UX06 regressions.
- Final full Playwright Chromium **410/410 PASS**, 2.9m; FAIL 0, SKIP 0.
- Offline VenueStaffRoutesTest **35/35 PASS**, failures/errors/skipped 0; compileKotlin and
  ktlintCheck PASS. Test proves STAFF→MANAGER→STAFF handles equal pending rows across two refreshes,
  distinct handles and unchanged secret-free exact keys. Disposable H2, not PostgreSQL/server proof.
- Initial targeted UI review findings were addressed; new narrow handle-contract/unification
  review found no concrete defect and recommended the passing deferred-GET regressions. No repeated
  broad audit. Author's final revoke/expiry focus edge is fixed and tested. [Review](.tmp-checks/ht-ux06/review.md).
- git diff --check PASS; docs UTF-8/fence/local-link sanity PASS. Backend evidence hashes unchanged.

Evidence: [TypeScript](.tmp-checks/ht-ux06/handle-typescript.log),
[build](.tmp-checks/ht-ux06/handle-build.log), [targeted](.tmp-checks/ht-ux06/handle-final-targeted.log),
[full](.tmp-checks/ht-ux06/handle-full-playwright.log),
[backend](.tmp-checks/ht-ux06/handle-backend-validation.log),
[backend scope](.tmp-checks/ht-ux06/handle-backend-evidence.json),
[preservation](.tmp-checks/ht-ux06/preservation.json), [Git status](.tmp-checks/ht-ux06/git-status.txt).
Visually inspected synthetic screenshots: [mobile](.tmp-checks/ht-ux06/screenshots/invites-mobile.png),
[reduced height](.tmp-checks/ht-ux06/screenshots/invites-short.png),
[mobile revoke](.tmp-checks/ht-ux06/screenshots/invites-pending-mobile.png),
[desktop](.tmp-checks/ht-ux06/screenshots/invites-desktop.png),
[MANUAL](.tmp-checks/ht-ux06/screenshots/card-manual-mobile.png),
[SCHEDULE](.tmp-checks/ht-ux06/screenshots/cards-schedule-mobile.png).
No screenshots were supplied this session; described states were reproduced locally.

Changed: VenueStaffRoutes.kt + VenueStaffRoutesTest.kt; miniapp venueStaff.ts, venueDtos.ts,
style.css, guest-smoke.spec.ts; VENUE_OPERATIONS.md, STAFF_PROFILES_SHIFTS_TIPS.md,
TESTING_QA_SMOKE_STRATEGY.md, new design doc and this checkpoint. Git: 10 modified tracked files,
new design doc, original ?? scripts/dev/; index empty. Four original SQL helpers byte-identical;
stash/archive refs unchanged, no archived WIP applied. No real invite/member/chat/message or server
mutation. No publication/external operation. Local checks do not establish iPhone/staging readiness.
No unfinished operation. Next: review the local diff. Future staging deploy and dedicated iPhone
smoke require separate authorization: share cancel/copy/two roles, collapse/GET retention,
accept→existing-card linkage, revoke/expiry, Owner/Manager protection and actual Guest visibility
in MANUAL/SCHEDULE including overnight presence. Targeted invitation and photo decisions remain open.


## HT-VENUE-TEAM-ACCESS-UX05 — local READY (2026-10-27)

**HT_VENUE_TEAM_ACCESS_UX05_READY** — local readiness only; no publication or UX05 staging acceptance.

Identity: preparatory `HT-VENUE-TEAM-ACCESS-UX05-BASE-PREP` gate PASS, then resumed UX05 gate PASS.
Expected/observed physical root `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, Git/common dir
and checkout-owned index in its `.git`, origin `github.com/koteev-m/hookah_bot`. Global AGENTS empty;
repo/session instructions agree, no narrower instructions or identity-affecting overrides,
includes, alternates or redirects. AGENTS unchanged. Initial tracked tree/index clean; only four
original untracked SQL helpers. The original STOP was valid for the missing target object; the
new preparatory task supplied no initial base and authorized obtaining it only after its PASS.

Git sequence: initial `codex/ht-venue-qr-telegram-download-ux04` / HEAD
`7b4abb77aaad67fbfd56ce8db12dbff1d7a68694` -> read-only remote main check and targeted no-tags fetch ->
`origin/main = 0a70795d1a3b6fb0ba89f2061c10fe7c63a8bbcd` -> new ordinary local
`codex/ht-venue-team-access-ux05` starting at that exact HEAD/base. Original HEAD is the merge's
second parent; `merge-base --is-ancestor` passed. Initial branch constraint matched; initial
base/parent/ancestry NOT_SUPPLIED. Resumed branch/HEAD/base matched; additional parents NOT_SUPPLIED.
Same primary checkout, no worktree, no local main/UX04 rewrite or archive/stash changes.

Confirmed issues and resulting behavior:
- `miniapp/src/screens/venueStaff.ts`: misleading direct-add invitation label -> explicit
  **Создать приглашение**, role/acceptance guidance and read-only **Обновить доступы и карточки**.
  In-flight invite/create/profile/member guards stop accidental repeated requests. Last successful
  invite and role survive an error. Confirmed revoke removes its opaque handle before a possibly
  failing GET. The create DTO has no pending handle: no secret/recipient/status correlation guessed.
- Card raw inputs previously came from DTOs on every render -> per-profile/per-create-target
  screen-local drafts survive collapse, switching cards, unrelated mutations and read-only retry.
  **Свернуть** retains; **Отмена** discards; successful save clears only its own draft. No local-storage
  persistence; venue/account disposal clears state. Publication says **Карточка опубликована** and
  does not promise current Guest visibility. Membership, card, publication and Today remain distinct.
- Removal confirmation now explains retained card/shifts/history and a new invitation to restore access.
  Settings clarify MANUAL vs SCHEDULE and separate publication/Guest visibility. Existing schedule,
  timezone/overnight/default hours, explicit canceled restore and batch rules remain unchanged.
- `venueChatLink.ts`: unknown initial state no longer appears disconnected or permits generation;
  open-screen code expiry and copy-time guard prevent stale use. Failed/unconfirmed regeneration
  stops advertising the old full code, warns and performs GET only. Clipboard false/failure selects
  the command without false success. Code/test/unlink actions cannot compete while pending; queued
  remains distinct from delivered. No auto generation/send/unlink or automatic send retry.

Before-fix evidence: manual-card double click produced two POSTs; idle linkage code remained ready
past expiry. Initial invite/draft reproduction harness had a delay-counter/path/locator error;
those were diagnostic-test errors, corrected before final validation, not extra product diagnoses.
Source evidence for unguarded invite/member/profile handlers and DTO-based draft reset is retained
in the base diff. Existing assertions remain; only changed labels/copy were updated.

Validation PASS:
- `miniapp/node_modules/.bin/tsc --project miniapp/tsconfig.json --noEmit`;
- `npm --prefix miniapp run build` (existing Vite >500 kB warning);
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2 --grep 'UX05|staff|Staff|staff module|manual Today|schedule source|future canceled'`: **64/64**;
- full `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2`: **387/387**, 2.8m, Chromium;
- `_JAVA_OPTIONS='-Xmx4g' ./gradlew --offline --no-daemon --max-workers=1 :backend:app:test --tests '*VenueStaffRoutesTest' --tests '*VenueRbacRoutesTest' --console=plain`: **71/71** disposable H2 route tests;
- `git diff --check`; docs UTF-8/fence/link sanity and `bash -n` parse-only SSH example.
Final executed tests: FAIL 0, SKIP 0. PostgreSQL/concurrency suites not rerun: no backend/SQL/schema/
role-policy changes. H2 and browser mocks are not new PostgreSQL or deployed-runtime evidence.
No real working DB, Telegram messages/groups, SSH/SCP or VPS operation used.

One independent targeted read-only review found two P2 draft races: pending create-mode replacement
and delayed profile-link conflict clearing an independent form. Root fixed both, preserving the
originating target/current editor, and added passing deferred-response regressions. Reviewer found
no other concrete issue; no cyclic audit or second review pass. See [review](.tmp-checks/ht-ux05/review.md).

Changed: `miniapp/src/screens/venueStaff.ts`, `venueChatLink.ts`, `venueSettings.ts`;
`miniapp/e2e/guest-smoke.spec.ts` (15 added UX05 cases); `docs/VENUE_OPERATIONS.md`,
`STAFF_PROFILES_SHIFTS_TIPS.md`, `TELEGRAM_FALLBACK_STAFF_CHAT.md`, `TESTING_QA_SMOKE_STRATEGY.md`,
`STAGING_ORDINARY_RELEASE.md`, `STAGING_DEPLOYMENT.md`, `DEPLOYMENT_RUNBOOK.md`; this checkpoint.
UX04 acceptance and agreed strict SSH/SCP contract are source-qualified in these existing docs;
no duplicate runbook or SSH/executor/config/trust change. Backend/API/RBAC/schema/Guest DTOs unchanged.
Owner/Manager/Staff/foreign denial, last Owner, accept/revoke claims, protected cards, own Staff edit/
schedule privacy, module data retention and audit boundaries remain in current source and tests.

Evidence: [targeted](.tmp-checks/ht-ux05/final-targeted.log),
[full smoke](.tmp-checks/ht-ux05/full-playwright.log), [backend](.tmp-checks/ht-ux05/backend.log),
[build](.tmp-checks/ht-ux05/final-build.log), [preservation](.tmp-checks/ht-ux05/preservation.json),
[final Git status](.tmp-checks/ht-ux05/git-status.txt).
Rendered local screenshots visually inspected: [mobile](.tmp-checks/ht-ux05/screenshots/team-mobile.png),
[reduced-height keyboard simulation](.tmp-checks/ht-ux05/screenshots/team-keyboard.png),
[desktop](.tmp-checks/ht-ux05/screenshots/team-desktop.png),
[unconfirmed code result](.tmp-checks/ht-ux05/screenshots/chat-uncertain.png).
These are mock-fixture Chromium UI, not a supplied screenshot or real device keyboard/Telegram smoke.

Observed Git: 12 modified tracked files, original `?? scripts/dev/`; index empty. All four helpers
match initial SHA-256 byte-for-byte. No commit/push/PR/merge/deploy, new worktree, backup, globals,
server, Clubs, secrets, migrations or real invitation/member mutations. No unfinished operation.

Future separately authorized staging smoke is required before UX05 acceptance: Owner/Manager create
and accept a dedicated invitation, check pending/expiry/revoke without real-user cleanup; verify
card vs membership, draft/collapse and Guest visibility; module off/on and MANUAL/SCHEDULE with
an overnight shift; connect a dedicated chat with explicit code, queued test, unlink confirmation,
Back and venue-switch/error behavior on iPhone. Keep existing roles/privacy and data-retention checks.
Desktop ZIP remains manually unverified from UX04. Next: review this local diff; publication and
staging execution require separate authorization.


## UX04 accepted release — evidence reconciliation (2026-10-27)

Supersedes the historical UX04 local-only / pending-deploy / pending-iPhone status below, only for
this bounded accepted release. Source: user's HT-VENUE-TEAM-ACCESS-UX05 task, not a fresh server,
CI or Telegram check in this task.

- PR #228 merged; release `0a70795d1a3b6fb0ba89f2061c10fe7c63a8bbcd`;
  supplied CI `40503171235`, attempt 1.
- Previous Codex HT70 deploy report: operation `207c31b248114e239b1588930545fc0b` SUCCESS.
- User confirmed real iPhone smoke: sending common PDF and individual PNG, retained Mini App
  screen and working navigation. This is user evidence, not a Codex device-smoke run.
- Desktop ZIP saving was not manually checked. Broader unrelated checks retain their own gates.
- SSH docs now record the agreed strict Hookah SSH/SCP profile from completed diagnostics and
  UX03/UX04 deploy evidence; no new connection, trust/config/executor/server change was made.
  Banner-timeout cause remains unknown; historical ControlMaster is separate from current HT70.
- UX04 acceptance does not accept or deploy UX05 changes. Older checkpoints remain historical.


## HT-VENUE-QR-TELEGRAM-DOWNLOAD-UX04-FIX-P1 — local FIXED (2026-10-27)

**HT_VENUE_QR_TELEGRAM_DOWNLOAD_UX04_P1_FIXED** — local frontend boundary; ready for focused
re-review. Supersedes the SDK-presence criterion in the UX04 READY checkpoint below.

Identity gate PASS: task `HT-VENUE-QR-TELEGRAM-DOWNLOAD-UX04-FIX-P1`, project `Hookah_Tootah`;
expected/observed physical primary root `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`,
Git/common dir and checkout-owned index in `.git`, origin `github.com/koteev-m/hookah_bot`.
Branch `codex/ht-venue-qr-telegram-download-ux04` and exact HEAD
`4515224ad78b35bff93f4606ab596232c2986fee` match supplied constraints. Base/parent NOT_SUPPLIED.
Inherited/repo instructions agree; global AGENTS empty, no narrower instructions or Git identity
override/include/redirect mismatch. Gate repeated before official SDK retrieval. Initial five
modified tracked UX04 files and four untracked SQL helpers preserved; no worktree/branch created.

Root cause: `index.html` loads Telegram SDK in browsers too; SDK creates WebApp with empty
initData/platform unknown, so `isTelegram = Boolean(webApp)` cannot select QR delivery safely.
Added shared `hasTelegramLaunchData`, derived only from non-empty existing resolved initData
(SDK or existing hash tgWebAppData fallback). Tables/QR uses that UX signal. Raw launch data is
not authentication; auth gate, session logic and server checks are unchanged. Legacy `isTelegram`
and SDK pointer/capabilities remain unchanged, including BackButton, scanner, ready/expand,
chat/link actions, Guest/Venue user identity and start/table context. Official Telegram docs allow
empty-data keyboard-button/inline-mode launches; these are not this product's authenticated Venue
launch and are not reclassified globally. No UA/platform/function/query-only launch heuristics.

Validation PASS:
- `miniapp/node_modules/.bin/tsc --project miniapp/tsconfig.json --noEmit`;
- `npm --prefix miniapp run build` (existing >500 kB bundle warning);
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2 telegram-context.spec.ts venue-tables-qr.spec.ts`: 39/39 (13 context + 26 QR);
- same runner with `--grep 'UX03|UX04|Telegram QR delivery|table view without manage|table batch previews'`: 49/49;
- full same runner `--workers=2`: 372/372, 2.6m, Chromium;
- official SDK override, same context/QR specs with `--grep 'sdk-browser|sdk-bootstrap|SDK without launch'`: 4/4;
- `git diff --check`; SQL helpers 4/4, index bytes and stash/archive refs unchanged.
Initial sandbox Vite bind failed with EPERM before tests; authorized local execution passed.
CI uses a deterministic reproduction of the relevant SDK bootstrap; it never fetches the CDN.
Optional local `UX04_TELEGRAM_SDK_PATH` verified the same browser regression against the actual
official script (SHA-256 `124bdff7ba3fe86c8ba5143467afec21e020405e74abdb92cfdda6c0ee3b5908`).
Tests assert buttons, bearer/venue/format/table requests, no send during browser downloads,
filename/MIME/Blob lifetime, no Telegram Blob/ZIP/navigation, auth rejection despite raw data,
preserved SDK consumers, no auto retry, reopening and venue changes after errors/success.
Original UX03/UX04 assertions retained.

Evidence: [targeted](.tmp-checks/ht-ux04-p1/targeted.log),
[UX03/UX04](.tmp-checks/ht-ux04-p1/ux03-ux04.log),
[full smoke](.tmp-checks/ht-ux04-p1/full-playwright.log),
[official SDK](.tmp-checks/ht-ux04-p1/official-sdk.log),
[build](.tmp-checks/ht-ux04-p1/build.log),
[input hashes](.tmp-checks/ht-ux04-p1/validation-inputs.json),
[preservation](.tmp-checks/ht-ux04-p1/preservation-after.json).
Changed surface: original five UX04 files plus `miniapp/src/shared/telegram.ts`, new
`miniapp/e2e/telegram-context.spec.ts` and `miniapp/e2e/fixtures/telegram-launch.ts`.
Observed Git: six modified tracked files, two new test files, original `?? scripts/dev/`;
staging index empty. Backend/API/RBAC/DB/migrations unchanged. No stash/archive changes,
commit/push/PR/merge/deploy, real Telegram send, bot settings or user-data changes.
No unfinished test operation. Inline-button/menu/main/direct-link data shape and hash fallback
are fixture-covered; real Telegram launch modes and iOS WebView are not device-smoke-confirmed.
Separately authorized staging deploy and iPhone smoke remain required for runtime release evidence.
Next: focused read-only P1 re-review; publication remains separately authorized.

## HT-VENUE-QR-TELEGRAM-DOWNLOAD-UX04 — local READY (2026-10-27)

**HT_VENUE_QR_TELEGRAM_DOWNLOAD_UX04_READY** — local frontend validation only.

Identity gate PASS: task/project `HT-VENUE-QR-TELEGRAM-DOWNLOAD-UX04` / `Hookah_Tootah`;
expected/observed physical checkout `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`,
common dir/root-owned Git dir/index in its `.git`, origin `github.com/koteev-m/hookah_bot`.
Session/repo AGENTS agree; global AGENTS is empty; no narrower instructions, foreign authority,
Git ownership/namespace/override/include/redirection mismatch. EXPECTED_HEAD, EXPECTED_BASE and
parent/ancestry constraints NOT_SUPPLIED. Initial UX03 HEAD was `342ea0c3f94fccda6cd4f097da9728b3e3da2591`;
after gate PASS, authorized main fetch confirmed `4515224ad78b35bff93f4606ab596232c2986fee`.
New ordinary local branch `codex/ht-venue-qr-telegram-download-ux04` starts at that exact
`origin/main`/HEAD; primary checkout reused, no new worktree. Existing local `main` remains unchanged.

Cause: Telegram WebView exposed direct Blob PDF/PNG opening alongside private-chat delivery,
trapping iPhone users outside the Mini App screen. Existing `getTelegramContext().isTelegram`
now selects one bulk **Отправить все QR в Telegram** PDF action and one per-table
**Отправить QR в Telegram** PNG action, both through the existing guarded delivery API.
No QR download/ZIP/manual Blob link in Telegram; no download fallback on send failure.
Success/error/unknown outcomes retain Tables/QR, pending deduplication and no automatic resend.
Browser PDF/PNG/ZIP Blob/filename/lifetime/fallback and explicit send actions are retained.
No backend/API/RBAC/identity/venue-scope/token/DB/migration/bot-client changes.

Validation PASS:
- `miniapp/node_modules/.bin/tsc --project miniapp/tsconfig.json --noEmit`;
- `npm --prefix miniapp run build` (existing Vite >500 kB warning);
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2 venue-tables-qr.spec.ts`: 18/18;
- same command with `--grep 'UX03|UX04|Telegram QR delivery|table view without manage|table batch previews'`: 28/28;
- full `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2`: 351/351, 2.5m, Chromium;
- `git diff --check`; original four SQL helpers SHA-256 identical.
Initial sandbox Vite bind failed with EPERM; authorized local execution passed. Initial two new
tests misclassified `/src/shared/api/` module loads as API calls; corrected the test filter to
pathname `/api/`. All original UX03 assertions retained, with Telegram fixtures/labels updated.
Evidence: [full smoke log](.tmp-checks/ht-ux04/full-playwright.log),
[helper preservation](.tmp-checks/ht-ux04/preservation.json),
[final Git status](.tmp-checks/ht-ux04/git-status.txt).

Changed: `miniapp/src/screens/venueTables.ts`, `miniapp/e2e/venue-tables-qr.spec.ts`,
`docs/VENUE_OPERATIONS.md`, `docs/TESTING_QA_SMOKE_STRATEGY.md`, this checkpoint.
Observed Git: five modified tracked files, original `?? scripts/dev/`; index empty.
No stash/archive ref changes, commit/push/PR/merge/deploy, real Telegram send or user-data changes.
No unfinished test operation. User reports UX03 merged/staged and PDF/PNG private delivery working;
UX04 itself is not deployed. Future separately authorized staging deploy and real iPhone smoke
must verify single actions, retained screen/Back behavior, private-chat files and blocked bot.
Next: review this local diff; publication/release requires separate authorization.

## HT-VENUE-MENU-TABLES-QR-UX03-FIX-P2 — local FIXED (2026-10-26)

**HT_VENUE_MENU_TABLES_QR_UX03_P2_FIXED** — local validation boundary; ready for short review.

Identity gate PASS: task `HT-VENUE-MENU-TABLES-QR-UX03-FIX-P2`, project `Hookah_Tootah`,
repo `hookah_bot_ANT`; expected/observed physical root
`/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, Git common dir/root-owned Git dir/index,
origin `github.com/koteev-m/hookah_bot`, branch `codex/ht-venue-menu-tables-qr-ux03`, exact HEAD
`2dc6f97b2eac34d64d4e760f84745a0929ca8285`. Base/parent/ancestry NOT_SUPPLIED.
Instruction origins (session, empty global AGENTS, repository AGENTS; no narrower overrides),
Git overrides/config/includes/redirections and worktree registration verified; no foreign authority.
No branch/worktree/index/ref/stash mutation. Original uncommitted UX03 changes preserved.

P2-1: successful menu mutations previously lost submitted-form context after a failed GET.
Screen-local pending successes now survive repeated GET errors independently of interaction generation.
Only an unchanged submitted form is blocked from resubmission and omitted on confirmed refresh;
newer and independent drafts, current focus/disclosure and venue isolation survive. Retry runs GET only.
Confirmed analogous category rename/item create/edit paths use the same fix; no Menu UI rewrite.
P2-2: unverified Telegram responses and post-send audit failure now return the existing INVALID_INPUT
error envelope with additive `details.deliveryOutcome=unknown` and the check-private-chat warning.
UI also warns on lost HTTP responses/generic server errors/unconfirmed success, without automatic resend
or a retry shortcut. Confirmed success, definite denials, current RBAC/recipient/private-chat,
traffic/maintenance/rate checks and mandatory audit attempt are preserved. No token data in errors.

Final validation: TypeScript typecheck PASS; production build PASS (existing >500 kB Vite warning);
targeted Playwright 17/17 PASS; final full Chromium smoke 343/343 PASS (2 workers, 2.6m).
Backend: tables/Telegram 22/22, Menu/shift-check/RBAC 142/142, Guest QR/session/token 685/685:
**849 PASS, zero failures/errors/skips**. Kotlin compile and ktlintCheck PASS; diff-check PASS.
PostgreSQL: batch concurrency/token/audit/rollback 1/1; Guest resolve suite 27/27 (20 database cases
plus 7 policy guards); activation rollback 8/8. These are disposable Testcontainers PostgreSQL17
with production migrations, not the listening local working database. Both migration trees equal HEAD.

Initial environment attempts hit sandbox loopback/cache restrictions and docker-java/Docker API
incompatibility; authorized local runs used process-only `-Dapi.version=1.44`, no system configuration
change. The newly executable UX03 batch test exposed an invalid seed status (`published`); corrected
only the fixture to current V19 `PUBLISHED`, retaining all assertions. A new mock line's ktlint violation
was corrected. Final commands pass; original browser test source remains intact with appended cases,
and original backend assertions are retained/strengthened.

Commands:
- `miniapp/node_modules/.bin/tsc --project miniapp/tsconfig.json --noEmit`
- `npm --prefix miniapp run build`
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2 --grep 'UX03|Telegram QR delivery'`
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2`
- `_JAVA_OPTIONS='-Xmx4g -Dapi.version=1.44' ./gradlew --offline --no-daemon --max-workers=1 :backend:app:test`
  in separate groups: `*VenueTableRoutesTest`, `*VenueTableBatchPostgresTest`,
  `*TelegramDocumentDeliveryTest`, `*TelegramApiClientTrafficPolicyTest`; then `*VenueMenuRoutesTest`,
  `*VenueMenuRepositoryTest`, `*VenueRbacRoutesTest`; then `*GuestTableResolve*Test`,
  `*GuestTableContextActivationRepositoryTest`, `*GuestTableContextActivationPostgresTest`,
  `*TelegramBotRouterTableTokenTest`; all with `--console=plain`.
- Same Gradle flags with `:backend:app:compileKotlin :backend:app:ktlintCheck`; `git diff --check`.
Evidence: [command logs / per-group XML](.tmp-checks/ht-ux03-p2/),
[P2-only diff](.tmp-checks/ht-ux03-p2/p2-only.diff),
[preservation hashes](.tmp-checks/ht-ux03-p2/preservation.json),
[observed Git status](.tmp-checks/ht-ux03-p2/git-status.txt).

P2 changes only: `miniapp/src/screens/venueMenu.ts`, `venueTables.ts`;
`miniapp/e2e/guest-smoke.spec.ts`, `venue-tables-qr.spec.ts`;
backend `miniapp/venue/tables/VenueTableRoutes.kt`, tests `miniapp/venue/VenueTableRoutesTest.kt`,
`VenueTableBatchPostgresTest.kt` (seed correction only);
docs `MENU_OPTIONS_STOPLIST.md`, `VENUE_OPERATIONS.md`, `TESTING_QA_SMOKE_STRATEGY.md`; this checkpoint.
All other existing UX03 files and all four untracked SQL helpers are byte-for-byte unchanged.
Observed status remains 19 modified tracked files, 3 new UX03 tests and 4 original untracked helpers;
nothing staged. No commit/push/PR/merge/deploy/SSH, real Telegram send, server/working DB or migration
change. No unfinished test operation. Staging/manual Telegram iPhone/Android release smoke remains
PENDING and needs separate authorization; local results do not prove deployed behavior.
Next: short independent review of P2-only diff; commit/PR only after separate explicit authorization.


## HT-VENUE-MENU-TABLES-QR-UX03 — local READY (2026-10-26)

Identity gate PASS: `Hookah_Tootah` / `hookah_bot_ANT`, primary physical checkout
`/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`, common dir `.git` in that checkout,
origin `github.com/koteev-m/hookah_bot`. Exact expected/observed starting `main`/HEAD:
`2dc6f97b2eac34d64d4e760f84745a0929ca8285`; parent/ancestry NOT_SUPPLIED. Instruction origins,
Git dir/index/config/overrides and registered checkout verified. Local feature branch
`codex/ht-venue-menu-tables-qr-ux03`; same HEAD, uncommitted changes, no new worktree.

**HT_VENUE_MENU_TABLES_QR_UX03_READY** (local only).

Implemented: collapsed item-scoped hookah flavors with retained in-screen editor drafts; one atomic
numeric table batch with backward-compatible `tableNumbers`; protected single PNG / bulk PDF / ZIP
and explicit own-private-chat binary Telegram delivery. No schema migration. PDF is the UI primary;
omitted-format API ZIP stays compatible. No Guest menu/order/session/token rotation contract change.
Batch audit is transactional with tables/tokens. Export does not alter tokens. Telegram uses verified
JWT recipient, exact private chat, current venue permission, traffic/maintenance gates, bounded
rate/size/count, confirmed message result and no automatic retry/public download URL. Private delivery
is not atomically coupled to post-send audit; unconfirmed outcomes require checking the chat.

Validation: backend tables/Telegram **21 PASS + 1 PostgreSQL SKIP**; menu/shift-check/RBAC **142 PASS**;
Guest QR/session/token **657 PASS + 20 PostgreSQL SKIP**. Docker unavailable: PostgreSQL not verified.
TypeScript, production build, Kotlin compile and ktlintCheck PASS. Vite's existing >500 kB chunk
warning remains. Final full Mini App smoke **330/330 PASS (2.5m, Chromium, 2 workers)**.
Total backend evidence: **820 PASS, 21 PostgreSQL SKIP, zero final failures**. Earlier broad backend run hit confirmed JVM heap
OOM; split groups with `_JAVA_OPTIONS=-Xmx4g` passed. Earlier smoke found four Guest cases lacking the
Telegram identity fixture and a label test loading an external SDK; fixtures now deterministic with
all assertions retained. A scroll test now waits for user scroll to settle before the unchanged 8 px
assertion. One subsequent full run had an existing promotion-form DOM-detachment timeout (329/330);
final 330/330 rerun covers that case, the last editor focus correction and deleted-flavor isolation.
No tests disabled or assertions weakened.

Commands (all local, no real Telegram sends):
- `miniapp/node_modules/.bin/tsc --project miniapp/tsconfig.json --noEmit`
- `npm --prefix miniapp run build`
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2`
- `_JAVA_OPTIONS=-Xmx4g ./gradlew --offline --no-daemon --max-workers=1 :backend:app:test`
  with separate selectors: `*VenueTableRoutesTest`, `*VenueTableBatchPostgresTest`,
  `*TelegramDocumentDeliveryTest`, `*TelegramApiClientTrafficPolicyTest`; then
  `*VenueMenuRoutesTest`, `*VenueMenuRepositoryTest`, `*VenueRbacRoutesTest`; then
  `*GuestTableResolve*Test`, `*GuestTableContextActivationRepositoryTest`, `*TelegramBotRouterTableTokenTest`.
- Same Gradle flags with `:backend:app:compileKotlin :backend:app:ktlintCheck`; `git diff --check`.
Evidence: `.tmp-checks/ht-ux03/` (test XML by group and command logs; local ignored artifacts).

Changed files (all within this task): `miniapp/src/screens/venueMenu.ts`, `venueTables.ts`;
`miniapp/src/shared/api/venueApi.ts`, `venueDtos.ts`; `miniapp/src/style.css`;
`miniapp/e2e/guest-smoke.spec.ts`, `booking-label-parity.spec.ts`, new `venue-tables-qr.spec.ts`;
backend `Application.kt`, `miniapp/security/MiniAppAbuseProtection.kt`, `miniapp/venue/tables/`
`VenueTableDtos.kt`, `VenueTableRepository.kt`, `VenueTableRoutes.kt`, `telegram/TelegramApiClient.kt`;
tests `miniapp/venue/VenueTableRoutesTest.kt`, new `VenueTableBatchPostgresTest.kt`, new
`telegram/TelegramDocumentDeliveryTest.kt`; docs `VENUE_OPERATIONS.md`, `MENU_OPTIONS_STOPLIST.md`,
`SECURITY_RBAC_MATRIX.md`, `TESTING_QA_SMOKE_STRATEGY.md`; this checkpoint.

Real Telegram/iPhone/Android, staging and PostgreSQL concurrency remain NOT_VERIFIED/PENDING.
Staging smoke is required before release and needs separate authorization. No stage/commit/push/PR/
merge/deploy/SSH, working-data/secret/server-migration changes or real Telegram sends. Four original
untracked SQL helpers in `scripts/dev/` are untouched. No external operation is pending.
Observed final `git status --short`: 19 modified tracked files, 3 new task test files, and the
4 original untracked SQL helpers; nothing staged. No local test server/worker operation remains.
Next: local diff review, then separately authorized release/staging and real Telegram/iPhone smoke.

## HT-VENUE-OWNER-UX02-HISTORY-COMPAT — local compatibility ready (2026-10-26)

Identity gate PASS: `Hookah_Tootah` / `hookah_bot_ANT`, physical worktree
`/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`, branch
`codex/ht-venue-owner-ux02`, expected/actual HEAD `6f07c450ba22d7782a592fc71612eb47840d1c60`.
Expected common dir/checkout index/origin/instruction authority verified; base/parent NOT_SUPPLIED.
Starting 8 modified + 2 untracked UX02 files preserved; same final Git status, nothing staged.

State: **HT_VENUE_OWNER_UX02_HISTORY_COMPAT_READY**. Supersedes the HISTORY-HARDEN blocker below.
Both P2s eliminated: no unknown-index compensation and no restoration state that can hang after
rapid Back. Cancel only cancellable Navigation API events; otherwise follow actual URL/venue/screen
and retain dirty editors in memory by user/venue/role/permission scope/form. Stay or explicitly
confirm discard on cancellable exits; no autosave/storage persistence. Saved/confirmed-discarded
state is cleared; pending explicit writes may finish while detached. Host close/reload can lose drafts.

Fresh evidence: TypeScript `--noEmit`, production build and diff-check PASS; **32/32 targeted** and
**320/320 full Playwright smoke (2.5m)** in Chromium 148. Five formerly failing cases remain and pass
under the agreed fallback contract. Real browser-UI repeated Back proves `cancelable=false` despite
`canIntercept=true`; screen/draft recovery passes. Two-venue/OWNER-MANAGER, all five forms, exact raw
text, API counts, errors and pending writes covered. Existing Vite >500 kB warning remains.
See [validation report](docs/HT_VENUE_OWNER_UX02_LOCAL_VALIDATION.md) and local
`.tmp-checks/ht-venue-owner-ux02/compat-*` logs, screenshot and scope/hash manifest.

Only router, settings draft lifetime, related tests/docs/checkpoint changed during this task.
Dashboard/catalog/CSS match task-start hashes. No backend/API/DB/RBAC/Bot/lifecycle/billing/archive,
other checkout, `scripts/dev/`, stage/commit/push/PR/merge/fetch/SSH/deploy changes. No unfinished
external operation or local test server. Staging and real Telegram/iPhone/Android, WebKit/Firefox
NOT_VERIFIED; Chromium fallback emulation is not real old-WebView evidence.
Next: local diff review; publication and required staging/real Telegram smoke need separate authorization.

## HT-VENUE-OWNER-UX02-HISTORY-HARDEN — blocked before runtime fix (2026-10-26)

Identity PASS in `/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`,
branch `codex/ht-venue-owner-ux02`, expected/actual HEAD `6f07c450ba22d7782a592fc71612eb47840d1c60`;
common dir/index/origin/instruction authority verified; base/parent NOT_SUPPLIED. Starting 8 modified
+ 2 untracked preserved. Goal: harden unknown and overlapping browser history without losing drafts.

State: **BLOCKED; neither P2 fixed.** Both reproduced in real Chromium, including native rapid Back.
Legacy History API cannot identify arbitrary unindexed entry positions/deltas. Stopped runtime
implementation under the task's explicit limitation clause; source files remain unchanged from start.
Six new tests: targeted UX02 13 PASS / 5 FAIL (all 12 old cases pass). TypeScript/build PASS.
Fresh full smoke: **301 PASS / 5 FAIL of 306 (2.9m)**; all original 300 tests pass. Diff-check PASS;
all five runtime/style files match starting hashes. Final status 8 modified + 2 untracked; no staged
changes or running test server. See [current validation](docs/HT_VENUE_OWNER_UX02_LOCAL_VALIDATION.md).
Evidence: `.tmp-checks/ht-venue-owner-ux02/harden-*` logs, preserved browser traces and source hashes.
Earlier 300/300 does not cover these failures. New regressions intentionally remain red.

Only local tests/docs authorized and changed at this stop; no stage/commit/push/PR/merge/SSH/deploy,
other checkout or scripts/dev changes. Staging/real Telegram NOT_VERIFIED. No publication authorized.
Next: agree browser-owned Navigation API entry identity and compatibility/fallback behavior, then
resume the runtime fix and full acceptance checks. No partial fix is claimed.

## HT-VENUE-OWNER-UX02-FIX-P2-HISTORY — local P2 fixed (2026-10-26)

Worktree `/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`, branch
`codex/ht-venue-owner-ux02`, expected/observed HEAD `6f07c450ba22d7782a592fc71612eb47840d1c60`.
Fresh identity gate PASS; same common dir/index/origin/instruction authority. Starting state:
eight modified and two untracked UX02 files. No new base/parent constraint supplied.

State: `HT_VENUE_OWNER_UX02_P2_FIXED`. Cancelled hash Back/Forward restores the indexed history
position without overwriting earlier URLs or remounting the dirty form. Venue URL updates retain
history state. Existing save-first hash guard and native document discard confirmation remain.
Only router, tests and relevant docs changed from the reviewed UX02 state; no business logic changes.

Evidence: [P2 validation](docs/HT_VENUE_OWNER_UX02_LOCAL_VALIDATION.md),
[pre-fix browser failure](.tmp-checks/ht-venue-owner-ux02/p2-before.log),
[final full smoke](.tmp-checks/ht-venue-owner-ux02/p2-full.log),
[current source hashes](.tmp-checks/ht-venue-owner-ux02/p2-validation.json).
Three new Chromium regressions plus extended two-venue history coverage. Targeted UX02 **12/12**,
fresh full frontend **300/300 (2.4m)**, TypeScript/build/diff-check PASS; existing Vite chunk warning.
Real iPhone Telegram/WebKit/staging remain NOT_VERIFIED. No stage/commit/push/PR/merge/SSH/deploy,
other checkout or `scripts/dev/` changes; no unfinished test server/external operation.

Next: separate review/publication decision. Required staging/real Telegram release smoke needs
separate authorization. Stop this bounded local correction here.

## HT-VENUE-OWNER-UX02 — local Owner workspace ready (2026-10-26)

Worktree: `/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`.
Branch: `codex/ht-venue-owner-ux02`; expected/observed HEAD and exact starting base:
`6f07c450ba22d7782a592fc71612eb47840d1c60`. Read-only identity gate PASS; common dir,
checkout/index ownership, origin and instruction provenance verified. Initial status clean.

State: `HT_VENUE_OWNER_UX02_LOCAL_READY`, uncommitted local Mini App/test/docs changes.
DRAFT/PUBLISHED share an operational home; Settings opens an eight-entry permission-filtered
catalog; More has portfolio, statistics, feedback, subscription and support. Existing forms/API
contracts are reused. Owner hours show seven compact days, preserving dirty inputs across
collapse and other-day/exception saves. Settings navigation, browser/Telegram Back and venue
switching protect dirty drafts and in-flight writes. Saved preview returns to its subsection.
MANAGER/STAFF navigation and server RBAC boundaries remain. Media upload is still an existing gap.

Validation: TypeScript `--noEmit`, production build, `git diff --check` PASS. Final full configured
Chromium Playwright smoke **297/297 PASS** (2.4m); nine new UX02 cases plus existing regressions.
390×844, reduced 390×420 and desktop checks; requested PNGs created and visually inspected.
Vite still warns about the >500 kB JS chunk. Initial local port sandbox failure was resolved by
approved localhost test execution; no filesystem restriction was bypassed.

Evidence: [validation report](docs/HT_VENUE_OWNER_UX02_LOCAL_VALIDATION.md),
[source hashes/results](.tmp-checks/ht-venue-owner-ux02/validation.json),
[full smoke log](.tmp-checks/ht-venue-owner-ux02/playwright-full.log),
[screenshots](.tmp-checks/ht-venue-owner-ux02/screenshots/owner-catalog-390.png).
The ignored evidence is local only. Staging/real iPhone Telegram/native keyboard/WebKit are
NOT_VERIFIED; this is not production readiness. Backend/API/DB/Bot/Guest/Platform/billing/lifecycle
are unchanged. Main/other worktrees and `scripts/dev/` untouched. No stage/commit/push/PR/merge,
SSH or deploy; no unfinished external operations or local test server.

Next: review this worktree's local diff. Publication and required staging/real Telegram smoke
need separate authorization; stop this local task here.

## HT-VENUE-OWNER-UX01 — local Owner home/navigation ready (2026-10-25)

Worktree: `/Users/maksimmartynov/.codex/worktrees/ht-onboarding-ux01-isolate/hookah_bot_ANT`.
Branch: `codex/ht-venue-owner-ux01`; base/current HEAD:
`ab1c37f9498750e4bf79c3df30a39a94f89420b0`. Identity gates passed before and after
branch creation; `codex/ht-owner-draft-access-fix` remains at `bbd166a615b1fe938f6b99c76a7d6283f06a038a`.

State: `HT_VENUE_OWNER_UX01_LOCAL_READY`, uncommitted local Mini App changes only plus tests/docs.
OWNER has a compact venue header, four primary navigation entries, grouped More page and DRAFT
preparation actions on existing routes. Other statuses retain operational counts/chat context.
Ownership applications/history, permissions and MANAGER/STAFF layouts are preserved.
No backend/API, lifecycle, archive recovery, billing or real venue data changes.

Validation: TypeScript `--noEmit`, Mini App production build and `git diff --check` PASS;
final full Chromium Playwright smoke **288/288 PASS** (2.5m), including 390×844/1280×900,
reduced-height form/save, route/back/unread, venue/role switching and existing operational tests.
Initial test-navigation/mocking failures were corrected before the final full green run.
Vite reports a JS chunk over 500 kB. Local evidence with source hashes and four screenshots:
[validation](.tmp-checks/ht-venue-owner-ux01/validation.json),
[DRAFT mobile](.tmp-checks/ht-venue-owner-ux01/screenshots/owner-draft-390.png),
[PUBLISHED mobile](.tmp-checks/ht-venue-owner-ux01/screenshots/owner-published-390.png),
[DRAFT desktop](.tmp-checks/ht-venue-owner-ux01/screenshots/owner-draft-1280.png),
[More mobile](.tmp-checks/ht-venue-owner-ux01/screenshots/owner-more-390.png).
These ignored local artifacts are not committed or deployed evidence.

No unfinished process or external operation. No commit, push, PR, merge, deploy or SSH;
main/deployment checkouts, staging and `scripts/dev/` untouched.
Next: review the local diff; publication is a separately authorized task. Before release,
run separately authorized staging/real iPhone Telegram smoke for safe-area and native keyboard;
WebKit is not installed locally. Fixture-backed Chromium tests do not establish deployed behavior.

## HT-71-SIMPLE-STAGING-DRAFT-PR — HT70 isolated publication (2026-10-20)

HT70 simple staging install/update is isolated from the unpublished HT46–HT69 work.
Base: `dfa577a8e92958bf863b12eaaa6617b37820f1c1`; publication branch:
`codex/ht-71-simple-staging-draft-pr`; worktree:
`/private/tmp/ht71-simple-staging-publication`. Project/instruction gates passed.
The original dirty worktree and its index remain unchanged.

Scope: independent operator script, immutable image build helper, unit/Docker tests,
one CI validation step and scoped deployment guidance. Existing historical contracts
and implementations are retained. Simple staging does not require HDC/1 or HDC/2.
Updates replace only backend; schema changes require review and a verified backup.
Recovery blocks unknown replay and schema rollback; the shared flock is cooperative.

Isolated-tree validation: 21/21 unit tests and 21/21 with Python -O PASS; Docker
integration PASS (90.317 s), including EMPTY→V126, unchanged PostgreSQL identity/data,
backup/isolated restore, failed backend/rollback, reviewed V127, neighbor preservation
and owned cleanup. Bash/Python/YAML syntax and diff checks PASS. The Docker fixture
uses a retained V126 image; GitHub CI and registry transport are fixtures. This does
not prove a new image build, actual remote CI, AEZA installation or Telegram smoke.

Authorization covers this branch, commit, push and one Draft PR to main only.
No merge, image publication, deployment, AEZA changes or CI monitoring is authorized.
Next: review the Draft PR and completed CI for its exact commit; image publication
and any staging installation/smoke require a separate authorized task.

## HT-V126-MAIN-INTEGRATION-CHECKPOINT-25 — PR #214 integrated in main (2026-10-16)

[PR #214](https://github.com/koteev-m/hookah_bot/pull/214) is CLOSED/MERGED. GitHub
`main` points to merge commit `fd8115f36e9b0c34b3ab7e0a4a70ffbddbf3c907`, with
first parent `8e69044d843e23a843592aa5ceb444e0904c1462` and second parent
`5f89fe7de1f31eac2bc4162fa2c9bac2dfb17a28`. The reviewed V126 one-VPS-safe
code and documentation are integrated in main. This record supersedes the earlier
feature-branch preparation checkpoints below as a statement of GitHub integration;
their evidence remains historical.

The exact-merge-SHA [post-merge CI #38802856278](https://github.com/koteev-m/hookah_bot/actions/runs/38802856278)
completed successfully on attempt 1: 15/15 jobs SUCCESS, including
`compose-cutover` with its isolated Linux SSH harness, backend, PostgreSQL and Mini
App checks. GitHub integration and isolated CI do not prove readiness of the real
VPS. No deployment, staging, cutover, recovery or other operational action is
authorized or recorded by this checkpoint.

`run724=UNKNOWN`, `CUTOVER_READY=NOT_PROVEN`, `LEGACY_EVIDENCE_INSUFFICIENT` and
`ONE_VPS_SAFE_PATH_NOT_PROVEN` remain. Independent legacy cessation and writer
exclusion evidence, installation of updated executors, ordinary V126 new-image
authority transfer, and all real VPS/database operations remain closed. Next:
separately assess the missing independent legacy evidence and decide the ordinary
authority contract before any separately authorized server work.

## HT-V126-PR-CHECKPOINT-FIX-18 — published feature branch, Draft PR preparation (2026-10-15)

This checkpoint supersedes the local, unpublished-status reports below without rewriting
their historical evidence. The `Hookah_Tootah` feature branch
`codex/ht-v126-one-vps-safe-implement-01` is published at
`064b2dcb1e3f6120a56f83c58cd82d3db1f3cb9a`. Its base and merge-base with
`origin/main` are both `8e69044d843e23a843592aa5ceb444e0904c1462`;
the feature is five commits ahead and zero behind. The published commits are
`08b404d`, `f5209be`, `31fbc9a`, `3bcfbb0` and `064b2dc`. The reviewed full
base-to-HEAD diff is 28 files, +2403/-85.

The supplied exact-HEAD [GitHub Actions run #38781777338](https://github.com/koteev-m/hookah_bot/actions/runs/38781777338)
completed successfully on attempt 1: 15/15 jobs SUCCESS, including `compose-cutover`;
GenesisSSH passed 11/11 and ProspectiveSSH passed 6/6. Independent full-branch review
`HT-V126-FULL-BRANCH-PR-READINESS-17` inspected all five commits and the full diff.
Its result was `FINDINGS`: no confirmed production-code defects and one P2 finding,
the stale top-level checkpoint. This new entry addresses that documentation finding;
the final diff after this edit still needs independent confirmation. A Draft PR has
not been created. Merge and operational deployment are not authorized.

`run724=UNKNOWN`, `CUTOVER_READY=NOT_PROVEN`, `LEGACY_EVIDENCE_INSUFFICIENT` and
`ONE_VPS_SAFE_PATH_NOT_PROVEN` remain unchanged. Independent legacy cessation and
writer-exclusion evidence, staging/cutover/recovery, installation of the updated
executors, and new-image ordinary authority transfer remain closed. Server operations
require their own independent evidence and separate authorization; CI and this
documentation correction do not establish operational readiness.

## HT-V126-LINUX-CI-CANDIDATE-04 — local publication candidate (2026-10-15)

The existing feature worktree remains at `8e69044d843e23a843592aa5ceb444e0904c1462`
on `codex/ht-v126-one-vps-safe-implement-01`; nothing is staged or published.
`compose-cutover` already runs the accepted-baseline regressions through
`test-v126-cutover.sh`. It now also runs the standalone isolated-inventory tests,
while the existing network-denied Policy B container runs the copied-source
inventory and Genesis consumers. The release CI contract checks these bindings.
Local focused fixtures, negative cases, source parity, migration identity, syntax
and diff checks pass. The hosted Linux PostgreSQL17/18, Docker and owned-SSH gates
remain unexecuted for this candidate; no CI or cutover PASS is claimed.
`run724=UNKNOWN`, ordinary V126 new-image promotion and all operational permissions
remain closed. Next: explicit approval for one exact-file commit and non-force push
of the same feature branch, followed by independent exact remote SHA verification.

## HT-V126-ONE-VPS-REVIEW-FIX-03 — bounded local review fixes (2026-10-15)

The primary/worktree identity gates passed against the exact task-supplied HEAD/tree/
parents. Work remains uncommitted in the same worktree and branch below. The initial
20-file diff (+1588/-66) was preserved; primary checkout, both indexes and `scripts/dev/`
were not changed. No server, publication or real database operation was performed.

The existing semantic validator now joins actual Compose/backend/URI database evidence
to the accepted V125 identity and retains the Docker network ID in the baseline digest.
V3 recovery-to-ordinary transfer is intentionally closed before immutable append until
independent canonical-tag/source/image provenance and descriptor executability are
established. Existing V125 ordinary behavior remains covered. The isolated harness now
copies `staging_release.py` and verifies actual copied-source imports with a missing-module
negative control. The [current handoff boundary](docs/V126_OPERATIONAL_HANDOFF.md#current-ordinary-controller-boundary)
supersedes the earlier implementation's implied V3 handoff availability.

Focused offline regressions pass. The full cutover gate is **not PASS** here: its
PostgreSQL suite cannot find the required Linux client path; PostgreSQL18 and a running
Docker daemon are unavailable. Portable/source-closure tests do not prove Linux
flock/inotify/owned-SSH or PostgreSQL17/18 integration. `run724=UNKNOWN`, all admission/
operational permissions and the closed new-image V126 promotion boundary remain intact.
Next: run the existing compose-cutover/isolated gates on a provisioned isolated Linux
test runner; no staging/cutover authorization is implied.

## HT-V126-ONE-VPS-SAFE-IMPLEMENT-01 — local guards, external decisions open (2026-10-15)

**LOCAL_PARTIAL_DECISION_REQUIRED / UNCOMMITTED / NO CUTOVER AUTHORIZATION**.
The authorized implementation worktree is `/private/tmp/ht-v126-one-vps-safe-implement-01`,
branch `codex/ht-v126-one-vps-safe-implement-01`, exact base
`8e69044d843e23a843592aa5ceb444e0904c1462`, tree
`6b2040ef36a3cd48bb2b86da69ed6e6a072f8d80`. Identity gates passed in the primary
checkout and this registered Hookah worktree. Primary user changes and `scripts/dev/`
are outside the implementation scope.

New proposals bind the protected accepted ordinary V125 state, registration and exact
source/image/database identity, including the enrolled R0/Q data and restore runtimes.
The same baseline is retained through INIT, dispatch and pre-V126 recovery/handoff;
historical source-bound records retain their meaning. The ordinary writer and genesis
now exclude each other through the existing ordinary lock and V126 registry boundary.
This closes that known race only when both updated executors are installed; it does not
prove legacy effect cessation. No new genesis route, producer or manual override exists.

Local validation passed 28 offline suites (587 tests), all 43 original shell fixture
functions, nine portable libpq checks, syntax, embedded-source parity and diff checks.
This covers synthetic baseline/history, ordinary, Policy B/DR and recovery decisions.
Non-author code review found no remaining actionable issue in the local diff; it is
not independent operational/security certification. Full isolated Linux/owned-SSH and PostgreSQL17/18 integration
remain unverified here: Docker daemon is unavailable, PostgreSQL18 is absent and the
local PostgreSQL17 installation cannot initialize its test cluster. No actual target,
database, backup/restore, registry or operational evidence was accessed or changed.
The supplied old candidate remains locally verified and undeployed; changed tooling
requires a new unified release build from the future integrated SHA.

`run724=UNKNOWN`, `LEGACY_EVIDENCE_INSUFFICIENT` and `ONE_VPS_SAFE_PATH_NOT_PROVEN`
remain. Same-target admission needs the existing independently enrolled complete
inventory, cessation/writer-exclusion and accepted-poststate evidence. The retained
V126 ordinary consumer can recreate only an already accepted image with its full
applied handoff; new-image releases through the installed controller remain closed.

Next: owner/security decision on the missing independent legacy evidence acquisition
and the exact post-V126 ordinary authority transfer, as bounded in the existing
[cutover contract](docs/V126_STAGING_CUTOVER_CONTRACT.md#current-accepted-v125-baseline-for-new-attempts)
and [operational handoff](docs/V126_OPERATIONAL_HANDOFF.md#current-ordinary-controller-boundary).
Linux integration, exact release CI/build and all separately authorized operational
gates remain required; local checks do not establish `CUTOVER_READY`.

## HT-DOC-SYNC-01B — current staging and next V126 target (2026-10-14)

The ordinary V125 staging release controller is installed; its initial `initialize/status`
was `ACCEPTED`. The QR release from source `1a2011ed27b0ea13d24bc6c88e48a0c7e9dcb5ff`
reached `SUCCESS / ACCEPTED` with image
`sha256:888e4d41e9a713873328aed4f542569a5089ce968c8f29aa481377a7c0b29c23`.
Manual Mix QR smoke passed 7/7. Alpha Hookah and direct access to another venue were not
manually checked. PR #210 and #211 are merged; CI #559 completed `SUCCESS` 15/15
(per the supplied independently verified GitHub checkpoint). The published main is
`cb92c66a545b486f255479db4bc4b4904a927cef`, tree
`f8489f282501ecc149237d19512ca1f266a22920`.

The last confirmed staging PostgreSQL head is V125 (`V126=0`, `failed=0`). Main
contains `backend/app/src/main/resources/db/migration/postgresql/V126__support_thread_read_message_cursor.sql`,
but V126 cutover has not run or been accepted. Historical run724 remains `UNKNOWN`.
The ordinary controller does not authorize or execute this migration. After this
documentation reconciliation, V126 is the next agreed technical target: first prove
one exact current-main V126 release candidate (SHA/tree, migration manifest, green
exact-SHA CI, immutable image ID/artifact bytes). Fresh protected-input authority,
backup/rehearsal, Policy-B R0/DR and target evidence remain `NOT_PROVEN`; any
staging V126 operation needs a separate owner decision under the existing
[V126 cutover contract](docs/V126_STAGING_CUTOVER_CONTRACT.md). No old readiness gate
is upgraded by the QR release. The single current product backlog is
[roadmap §13](docs/UPDATED_PRODUCT_AI_ROADMAP.md#13-master-remaining-work-inventory):
115 canonical entries; the 198 historical raw candidates are unchanged.

## HT-NEW-STAGING-09 — Linux EMPTY collector, local only

**V125_EMPTY_COLLECTOR_LOCAL_READY / UNCOMMITTED** (2026-10-12).
Identity gate passed for `Hookah_Tootah` in
`/private/tmp/hookah-ht-new-staging-07-20261012`, branch
`codex/ht-new-staging-07-minimal-v125-initial-install`, exact HEAD/base
`5d32a7198b5617bd10afd1fb810fb7605e59163e`; parent constraint NOT_SUPPLIED.
The [bounded Linux observer](docs/V125_EMPTY_COLLECTOR.md) acquires only fixed proc
fields and directory metadata. All output remains UNKNOWN/INCOMPLETE, with no
execution or qualification authority. Existing V125/V126 consumers are unchanged.

Local synthetic V125 suites: **64/64 PASS**, including 23 collector regressions and
41 existing lifecycle/initial-install tests. Syntax/static and diff checks passed.
This is not real Linux target qualification: no new server exists, no EMPTY proof,
provider/control-plane independence, immutable history or installation authority was
acquired. No SSH, Docker, SQL, enrollment, installation, commit, push or deploy.
Initial five modified/untracked files and Git index were preserved byte-for-byte;
primary checkout, other worktrees, backups and `scripts/dev/` were not modified.

Next: a separate decision on independent provider/control-plane fact acquisition and
independent collection/retention of future Linux observations. Installation requires
its own exact authorization; actual recovery/custody and Policy B precede test access.

## HT-QR-01 — prospective isolated genesis, local candidate

**LOCAL IMPLEMENTATION / ISOLATED LINUX QUALIFIED / UNCOMMITTED** (2026-09-25).
Approved architecture `9690f53c465f7110258f2272bba15496d1587b90c43ec1069773bd1eef8bba9e`;
isolated detached worktree `/private/tmp/htqr-prospective-isolated-genesis-20260925`,
base `0d565392cbee62890d467ea92eeec530c3005e83`, tree
`f2df07cec2fb33e1a9839700f3940d7fc3044d04`. The original exact checkout and user-edited
primary checkout are preserved. No branch, commit, push, PR or operational change is made.

[Prospective authority contract](docs/V126_PROSPECTIVE_AUTHORITY_EPOCH.md) adds an explicit
isolated effect/control domain, immutable predecessor ancestry, complete independently acquired
isolation facts, current control ledger, bootstrap-only U, prospective AP06 and epoch-bound
consumers. Old 724 remains UNKNOWN; f782 keeps its historical attributed disposition only.
No old-domain retry, adoption, implicit data/account/credential continuity or terminal upgrade
is permitted. Full U still requires genuine DR/custody/protected facts and separate enrollment
rebind. There is no database migration or product/QR change.

The final public-source snapshot ran 623 Linux tests plus 14 separate negative controls,
without failures/skips, in the retained network-denied Linux/arm64 Python3.12.3 test image.
This includes actual owned SSH/launcher/attended verifier and bootstrap INIT refusal. Portable
legacy/Policy B/DR/custody/history tests and 215 sequencer assertions passed; source/embedded
parity and migration identity are preserved. Review findings about revocation dependency
closure and missing decision provenance were corrected with regressions. Optional unchanged
AP00 SDK/crypto/worker suites could not qualify in the available incomplete old Python venv;
those environment failures remain recorded, never PASS. No packages were installed.

Exact manifest, command/log/source bindings, independent non-author review, preservation and
bounded publication-readiness decision are retained in
`~/.hookahtootah/ht-qr-01/evidence/20260925T083042Z-prospective-isolated-genesis-implementation/`.
No real prospective domain, producer, ledger, U, key or AP06 enrollment was created. Power-loss
persistence was not modeled. New candidate hosted-amd64/PG/systemd/private-daemon/compose and
release/operational gates remain future work; completed baseline CI is not reused as candidate CI.

Next: one separate decision on publication of the exact reviewed local candidate. No publication
or operational execution is authorized by these local results. Older checkpoints below are
retained history and do not override this candidate's evidence or the integrated baseline.

## HT-QR-01-LEGACY-GENESIS — F-01–F-03 local correction

**LOCAL CORRECTION / PORTABLE CHECKS PASSED / LINUX QUALIFICATION OPEN / UNCOMMITTED**
(2026-09-25). Branch `codex/ht-qr-01-legacy-genesis`, worktree
`/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-qr-01-legacy-genesis`,
base/HEAD `c28b3da03d7bae62cd441498f28335c0a29cc6ce`,
base tree `c49fa893d1d66cbf4ab77fc832306fd7c4fec5d6`.

The retained implementation candidate matched all 17 candidate and 60 context files before
correction; its independent review required F-01–F-03. Native terminal admission now closes
the exact INIT/STAGE/RECOVERY set and checks recovery/action chronology. Genesis result
bytes remain unpublishable until file/root fsync and same-FD readback succeed; actual mode
is checked before any bytes so umask cannot publish a pending result. All completion/history/
copy readers retain strict metadata checks. A Linux parent namespace watch plus same-name
rename fence ties the first root FD to exclusive creation and refuses intervening replacement.
Embedded bindings match the standalone source; old raw-owner history and twenty stages remain.

Portable native, EIO/umask, history, transport, authority, INIT, reconciliation, ordinary-deploy
and related self-tests passed. Independent non-author review found no remaining confirmed
F-01–F-03 code blockers in the static/portable scope after the umask correction. Full Linux
history/owned-SSH suites and the new F-03 syscall reproducer were not run: the existing test
runtime's Docker socket is inaccessible in this sandbox. No Linux PASS is inferred from event
parser tests. The result publication mode is not separately fsynced: power loss can restore
pending0600 and conservatively block completion even after an answer; no automatic reseal,
adoption or retry is permitted. Power-loss persistence remains unmodelled.

Evidence: `~/.hookahtootah/ht-qr-01-legacy-genesis/evidence/20260925T025913Z-findings-correction/`.
The original implementation and independent-review packages, approved plan/addenda and release
artifacts are unchanged. Product/QR UX, migrations, release identity, index/refs and unrelated
work are preserved. No commit, publication or operational action occurred.

Next: validate the exact revised bytes with the existing isolated Linux test runtime when
available, including full history/transport/owned-SSH and F-03 replacement regressions, before
any separate publication decision. Full hosted-amd64/PG/systemd/private-daemon qualification
remains OPEN; COMPOSE_PASS and real inventory, writer exclusion, disposition authority,
accepted poststate, enrollment and per-action approvals are absent. Native positives remain
synthetic supported history. This local correction grants no genesis/INIT/cutover/deploy admission.

## HT-QR-01-POLICY-B — remaining compose correction

**LOCAL CORRECTION / HOSTED COMPOSE QUALIFICATION OPEN / UNCOMMITTED DIFF**.
Branch `codex/ht-qr-01-policy-b-gates`, worktree
`/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-qr-01-policy-b-gates`,
HEAD `41a21b7d691e9fb88961ab68320fef44323c030e`; PR #204.
No successor release is selected.

Completed PR-run #522 / `35824291019`, attempt1, checked merge
`c7f8b960330c216431e5dc721048ef6ab97f34b7`; its tree equals feature HEAD.
Cutover fixtures and new Policy B/owned-SSH steps passed. Two steps remained red:
private daemon exposed raw flock contention instead of typed `target_busy`; terminal
lifecycle collector parsed accepted INIT metadata as a stage request, yielding
`INSUFFICIENT_EVIDENCE:original_request_binding`.

Only flock EAGAIN/EWOULDBLOCK now becomes `target_busy`; other errors and FD cleanup
remain distinct. Collector excludes exact INIT metadata from artifact ancestry after
mandatory native history validation. Both embedded copies match standalone sources.
Regressions also fix the daemon's later other-run/UNKNOWN precedence expectation and
the INIT busy assertion. No gates, UNKNOWN/recovery rules, stages or product/migrations
are weakened. Linux tests use real flock/processes and own SSH with synthetic authority;
the complete synthetic terminal/handoff/ordinary chain preserves exact history bindings.

Evidence: `~/.hookahtootah/ht-qr-01-policy-b/evidence/20260923T065605Z-remaining-compose/`.
The report retains original failures, review findings, final commands/exits and source
hashes. Full native hosted-amd64 private dockerd/systemd/PG/Caddy/JVM qualification
remains OPEN; local capture-only results do not establish COMPOSE_PASS. Older evidence
is unchanged. No publication, Actions mutation or operational action was performed.

Next: one explicit approval of the final file list and diff hash for a correction commit
and non-force push to PR #204 with normal automatic CI. No merge, release selection,
enrollment, INIT/cutover or deployment is authorized by this local pass.

## HT-QR-01 — Guest QR and order-channel entry

**QR-R1 / QR-R2 CORRECTED LOCALLY / UNCOMMITTED / LOCAL CHECKS PASS / IPHONE SMOKE OPEN**.
Branch `codex/guest-qr-order-entry`, worktree
`/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-qr-01`, base/HEAD
`8e672022a4340300dab1c5891819ffc539667c7f`. Before correction the 18-file candidate matched
`HT-QR-01-review.zip` and its declared SHA-256 exactly; original archive is preserved.

QR-R1: four guest-entry/channel messages now use scoped outbox ordering across retries, so
late keyboard removal cannot overtake the selected bot keyboard. Real PostgreSQL + actual
worker + synthetic transport reproduced both first/repeated-entry failures before the fix.
Other traffic and existing terminal failure policy remain unchanged. QR-R2: a scan cancelling
slow restore returns to the last stable state on rejection, never abandoned `resolving`;
ready session/tab context and explicit-exit late-response guards are retained.

Fresh correction checks: **107 backend tests / 8 suites**, **43 targeted frontend tests**,
**252 full browser tests**, Mini App build and Kotlin compile/ktlint PASS (exit 0, zero test
failures/errors/skips, browser retries=0). Source hashes before/after these checks match.
Red evidence and intermediate compile/lint failures are retained; stale XML copied after
compile-only failures is excluded from executed-test evidence. Final review closed five
negative keyboard assertions that previously missed non-null dedupe keys; no open findings.
The earlier 986-test result belongs to the original candidate and was not automatically rerun.
[Exact correction commands, evidence scope and iPhone smoke](docs/TESTING_QA_SMOKE_STRATEGY.md#ht-qr-01--guest-scanner-and-order-channel-entry).

Corrected review archive contains current source/diff and new command/exit/hash/log/XML evidence.
No real Telegram/iPhone or deployed-runtime verification is claimed. Permanent delivery failure,
unknown launch bot identity and existing per-interface tab selection remain explicit limits.
Next: separately authorized publication/deployment and real iPhone smoke. No schema/dependencies,
staging/commit/push/PR/merge, deploy/Actions/SSH/provider activity or changes to the main checkout,
`scripts/dev/`, HT-OPS-39 or HT-OPS-41.

## HT-OPS-39 — local retention-only observer adaptation

The user-approved local change is committed as
`27a2c785e156940d4344ad1d0ff689eca322f312` in
`/private/tmp/ht-ops-25-ap00/worktree`, branch
`codex/ht-ops-33-ap00-dedicated-worker-18390444`. This integration brings authoritative
current `main` `7dfdee55153dce59fc96e3e1a68b22dcc8ffcb53` into that branch. User-provided
post-merge CI #509 is `SUCCESS`; this task did not query Actions.

Observer now issues only exact-version GetObjectRetention and the two bucket
settings reads. Its result is `requested_version_id`, `mode`, `retained_until`:
request correlation, not a provider response-ID/size/delete-marker attestation.
Private IPC v2 rejects v1 and old/mixed results. Reader response VersionId,
delete-marker/length/bytes checks and independent readback remain unchanged.
The user accepted losing the observer's duplicate HEAD proof and the residual
availability risk when reader response VersionId is absent; reader stays fail-closed.
Three independent principals remain required. [Contract](docs/DR_AP00_ADAPTER_FOUNDATION.md).

Fresh validation of exact local merge `46c587222ff9b17f6c69c6b811d9f73162338b2b`:
adapter 37/37, worker/IPC 24/24 and stdlib AP-01 48/48 PASS; in-memory syntax
compile 5/5, Markdown targets 12/12 and `git diff --check` PASS; no conflict markers.
The focused merge review found no blocking runtime defect. This docs-only correction
closes its sole stale-handoff finding. No provider test was performed. Earlier
implementation identities, diff and logs remain in
`/private/tmp/ht-ops-39-yandex-context/REPORT.md`.

HT-OPS-39 remains `BLOCKED_HT_OPS39_LEAST_PRIVILEGE`: reproducible policy/ACL,
writer effective permissions, billing, F4 capacity, custody and operational R0/G
remain open. Manifest NOT EXECUTABLE; request NOT READY TO ISSUE. The former
four-call observer mismatch is historical for the original implementation.
No operational schema/Trust, provisioning, IAM or provider change; no credentials,
push/PR/publication, staging/SSH/deploy/V126 or Actions activity.

The implementation/merge review is complete. Publication remains separately
unauthorized and requires an explicit decision after this docs-only correction.

## PR #198 — merged main and staging reconciliation — 2026-09-17

**MERGED / CI PASS / STAGING AND MANUAL SMOKE OPEN**.

At the PR #198 merge checkpoint, authoritative `main` was
`465ce33c4a9e244ad5f9771beadf35cfa7b8d9d4`; CI #504, CI #505 and post-merge
CI #506 passed, and the remote PR branch was deleted.

The reviewed Guest Order / Session / Tab fixes remain intact: active-order reads
and ordinary bill requests validate current session, exit and tab authorization;
service charges are selected by order/tab; active summaries derive items,
promotions and loyalty from the same authorized batches. Personal ownership,
shared membership/revocation, legacy tabless authorship under active-session/no-exit
checks, explicit QR re-entry and full-order/staff aggregation are preserved.
No migration, API/DTO, client behavior or new product/security contract is introduced.
[Canonical scope](docs/ORDER_SESSION_TAB_CORE.md#guest-order-isolation-consolidation--2026-09-16)
and [blocked manual smoke](docs/DEFERRED_MANUAL_SMOKE_BACKLOG.md#order-context-manual-001).

The merge conflict resolution was limited to this checkpoint and the catalog debounce
test. Both test variants pause time; the merged result retains PR #198's single pause
before navigation, with all 299ms/+1ms, query/filter/reset/navigation assertions
unchanged. Backend sources/tests match the reviewed PR #198 candidate. Old PR #198
Compose failures describe the pre-integration tree, not current main.

Prior consolidated evidence (235 backend tests / 16 suites, 24 focused browser
checks) remains historical at `/private/tmp/guest-order-consolidation-evidence-20260916/`.
The debounce reproduction and 216-test local verification are recorded at
`/private/tmp/pr198-miniapp-ci-20260916/` and in the QA strategy. Pre-merge combined-tree
checks passed: **235/235 backend tests in 16 suites**, including **11/11 real PostgreSQL
idempotency/concurrency tests**, plus forced backend compile/ktlint, Mini App build,
focused catalog **1/1** and full structured browser smoke **216/216** (113.8s).
XML and the unchanged CI JSON validator report zero failures/errors/skips/flaky
outcomes/failed attempts. Testcontainers containers/volumes return to zero; diff
and documentation consistency checks pass. Local runtime: macOS arm64, Java 21,
Docker Desktop, Node 20.20.2/npm 10.8.2, Playwright 1.60.0, UTC, two full-smoke workers,
zero retries. The first focused invocation selected no tests because its grep was
incorrectly anchored; corrected discovery selected exactly one unchanged test.
Commands, logs, XML and JSON: `/private/tmp/pr198-main-integration-20260917/`.

Read-only staging reconciliation found deployed runtime
`f577934691a1a7a79ba327c54e2055425142b7be` at Flyway head V125. V126 is absent,
the then-current main candidate `465ce33c4a9e244ad5f9771beadf35cfa7b8d9d4` was not deployed, and its exact
candidate image, image ID and deployment descriptor are absent. The canonical
target-operation registry is absent. The protected V126 input files now exist with
the expected shape and metadata, but their approval, provenance and semantic binding
remain unproven. Policy-B R0 operational readiness remains unresolved and is on the
V126 critical path.

Staging/manual smoke remains open. `ORDER-CONTEXT-MANUAL-001` is
`BLOCKED_BY_ENVIRONMENT`; green merged-feature CI does not substitute for the missing
deployment, V126 transition prerequisites, controlled actors/contexts or cleanup
authority. No `STAGING-SMOKE-PASSED`, V126 readiness, provider readiness, operational
DR PASS or production readiness is claimed. Full implementation and pre-merge evidence
remain in the merge parents and the paths above.

## CI Compose Stability — V126 Cutover Fixtures (2026-09-16)

Isolated worktree `/private/tmp/ci-v126-compose-stability-20260916`, branch
`codex/ci-v126-compose-stability`; follow-up parent
`be32509d20f72fcacbd559e59c191eeb6529e835`, original base
`ad23f8e0bd1846c95242fde10314ceb579fedb98`. The existing TCP readiness fix for
CI 498's `database-create` race is preserved. PR #199 CI 499/500
(`35103344782` / `35103386597`, compose `104818091340` / `104818238165`)
passed the ordinary positive backup but failed the new transition fixture in
both phases at `readiness`, exit 4. Their source trees match `be32509`.

The fixture's FIFO owner/writer mismatch (OS postgres/root) is a confirmed
portability defect. Both releases now explicitly use OS `postgres`; actual writer
and owner UIDs are checked without pinning UID 999. Safe fixture diagnostics retain
the first failed handoff and observed exit status through later readiness failure.
Production code, TCP readiness, deadlines, cleanup ownership and workflow are
unchanged. [Contract and evidence limits](docs/TESTING_QA_SMOKE_STRATEGY.md#ht-12aa-postgresql17-globals-and-backup-regression).

With the immutable CI PG17.11 AMD64 image on Docker Desktop, the targeted
transition/positive cases pass; backup suite **41/41**, boundary checks **7/7**,
sequential transition series **5/5** and cleanup caller contracts **24/24** pass.
The temporary default-root writer control is rejected at all four writer/phase
boundaries; the socket-only production-copy control fails both phases at
`database-create`. Owned container/volume inventory returns to zero. Syntax,
static safety, documentation and diff checks pass. Local `fs.protected_fifos=0` is unchanged;
the historical CI attribution to `fs.protected_fifos=1` remains unproven, and
the full GitHub-hosted Linux gate remains unverified. Historical CI #487/#489
exact functional attribution also remains unproven.

Publication is superseded by merged PR #199 at
`e08f4b74824d56e096cdad3eafe813a97d20067c`; both `be32509` and `1cb22f2`
are preserved ancestors. The results above describe local verification only;
this integration does not inspect Actions or establish a new CI/release verdict.

## HT-OPS-31 — AP-00 Dedicated S3 Worker Runtime

Same isolated worktree `/private/tmp/ht-ops-25-ap00/worktree`, branch
`codex/ht-ops-25-ap00`, base/HEAD `77caf48a46e5d3c61b01ffdfc0a43bc9ec55ceaa`.
Authenticated starting full patch SHA-256:
`b673b0daf44253f7fd695f6b83430195b93de7b1dbb02a0d7f1b68da335a8acf`.
Exact pre/post files, full patches, architecture delta, hashes and report:
`/private/tmp/ht-ops-31-worker/REPORT.md`. HT-OPS-29/30 evidence remains unchanged.

Operational S3 now executes only in one fresh, bounded subprocess per logical
operation. The parent remains stdlib/SDK-free, with closed binary-safe IPC, a local
timeout and process cleanup. The worker admits CPython 3.13 with GIL before site/SDK
acquisition. Exact Python patch qualification remains bound by Policy B; existing
tooling includes worker/IPC source hashes. Raw child stdout/stderr/provider messages
are not diagnostic authority. Write timeout/crash/invalid IPC after start is UNKNOWN,
with no second PUT or reconciliation. [Contract](docs/DR_AP00_ADAPTER_FOUNDATION.md).

HT-OPS-29 in-process LogRecordFactory ownership (setter patch, ContextVar, lock and
counter) is removed and superseded **before publication**; it never became operational
evidence. Historical checkpoints below describe prior local candidates only.
Local worker suite **22/22 PASS**, SDK/crypto/provisioning **34/34 PASS**, AP-01 with
`-S` **48/48 PASS**. Actual malformed-header child WARNING reaches no parent factory/
handler/output; parent reload/logging state remains independent. Adversarial IPC,
runtime negatives, one-PUT ambiguity, distinct PIDs, concurrency/reaping and source
binding are covered. Prior PG17/database/diagnostic evidence is reused only for
unchanged sources after hash/applicability checks; no Docker rerun.

Crypto, provisioning, dependency pins, F3 history NOT_PROVEN and F4 temporary 64 MiB
bound/capacity NOT_PROVEN remain unchanged. Provider is unprovisioned. AP-02 producer,
AP-06 independent observations and AP-07 custody remain unimplemented/unauthorized;
AP-03 real transfer remains unexecuted/unauthorized and blocked by
`AP03_CAPACITY_COMPATIBILITY_NOT_PROVEN`. No DR PASS, operational qualification,
publication, staging/SSH/deploy/V126, provider access or credentials/keys.
Index empty; no commit/push/PR/Actions activity.

Next: independent security review of the exact HT-OPS-31 dedicated-worker changeset
before any publication or provisioning.


## HT-OPS-29 — AP-00 Logging Factory Ownership Repair

Same worktree `/private/tmp/ht-ops-25-ap00/worktree`, branch `codex/ht-ops-25-ap00`,
base/HEAD `77caf48a46e5d3c61b01ffdfc0a43bc9ec55ceaa`; index empty, no publication.
Authenticated starting candidate patch SHA-256:
`f022fa6c79ae3d77c3f2a1fdde9827abb84a8f1e45cb3d069f14c3fc07b5277a`.
Exact pre/post files, full patches, F1-only delta, hashes and report:
`/private/tmp/ht-ops-29-repair/REPORT.md`. HT-OPS-28 evidence is unchanged.

HT-OPS-28 F1-RACE/P1 is repaired locally: public factory replacement and adapter
admission now use the same ownership lock/counter. Active scopes reject replacement
with `AP00_S3_LOGGING_FACTORY_BUSY`; saved public-setter aliases are mediated too.
Response consumption, read retries and stream cleanup retain ownership. Idle replacement
works; subsequent adapter admission refuses before SDK activity without reinstalling.
[Runtime boundary](docs/DR_AP00_ADAPTER_FOUNDATION.md#target-and-authority-boundaries):
qualified CPython/GIL stdlib setter, one adapter runtime owner, no request serialization.

Preserved pre-copy race: 4/4 canaries at factory/handler/formatter at all three timing
points. Repaired public/saved-alias matrix: 0/4, expected single-PUT success retained.
Focused logging **13/13 PASS**, S3 **20/20 PASS** including existing F2 matrix; independent
concurrency/nesting/failure-cleanup probes PASS. Static/integrity evidence is retained
with the report. F2–F4 semantics, crypto/provisioning/pins and broader evidence inputs
remain unchanged. Historical HT-OPS-27 checkpoint below is superseded for F1 ownership.
Local repair is not provider/staging/DR or production-readiness evidence.

Next: bounded independent re-review of only the authenticated HT-OPS-29 F1 ownership
repair delta. No publication or further operational action is authorized.


## HT-OPS-27 — AP-00 Security Findings Bounded Repair

Same worktree `/private/tmp/ht-ops-25-ap00/worktree`, branch `codex/ht-ops-25-ap00`,
base/HEAD `77caf48a46e5d3c61b01ffdfc0a43bc9ec55ceaa`; no commit/index changes.
Authenticated HT-OPS-25 pre-repair patch SHA-256:
`f0938bb9c78296b3a635de31f5a7f69f86e89915678bb3985b61d54dfbc8d863`.
Full pre/post files, patches, manifests and before/after F1/F2 evidence:
`/private/tmp/ht-ops-27-repair/`; detailed handoff `REPORT.md` there.

F1: context-scoped SDK record redaction before downstream logging factories/handlers,
with persistent factory chaining and no temporary global logging mutation. F2:
unexpected post-dispatch 2xx is UNKNOWN, with one PUT and no reconciliation. F3:
conditional creation protects current key state; all-history uniqueness is NOT_PROVEN.
F4: temporary enforced 64 MiB bound unchanged; operational capacity is NOT_PROVEN.
AP-03 real transfer is blocked by `AP03_CAPACITY_COMPATIBILITY_NOT_PROVEN` until the
[mandatory gate](docs/DR_AP00_ADAPTER_FOUNDATION.md#mandatory-pre-ap-03-capacity-compatibility-gate)
is PASS. No streaming/chunked design selected. Crypto, provisioning schema/pins,
AP-01, AP-02/AP-06/AP-07 boundaries and Policy B thresholds remain unchanged.

Local adapter regressions **40/40 PASS**, including real urllib3 WARNING parsing,
concurrent logging and stream cleanup, and the write-status matrix. Before-repair
F1 leak and F2 incorrect rejections reproduced from preserved bytes; after repair
no canaries escape and unexpected 2xx stays UNKNOWN. Local evidence is not operational
DR PASS or Linux/amd64 capacity proof. HT-OPS-25 PG17 evidence remains applicable to
unchanged AP-01/database/Docker code; no Docker rerun or external operation here.

Next: bounded independent re-review of only the authenticated HT-OPS-27 F1–F4 repair
delta against the already reviewed HT-OPS-25 candidate. No publication/provisioning.


## HT-OPS-25 — AP-00 Production Adapter Foundation

Local changeset at exact base/HEAD `77caf48a46e5d3c61b01ffdfc0a43bc9ec55ceaa`;
initial read-only remote-main verification matched. Independent local Git copy in
`/private/tmp/ht-ops-25-ap00/repository.git`, worktree
`/private/tmp/ht-ops-25-ap00/worktree`, branch `codex/ht-ops-25-ap00`.
No shared alternates/hardlinks; original `.git` content comparison is unchanged.
The primary checkout is unused; its `.git` was read only to isolate the task.

Implemented optional pinned SDK transport, current-key conditional COMPLIANCE PUT with exact
VersionId and explicit UNKNOWN outcomes, exact reader/metadata observer, AES-256-GCM
authenticated envelope, injected key-provider interface and closed non-secret
provisioning declarations. [Contract and limits](docs/DR_AP00_ADAPTER_FOUNDATION.md).
AP-01 schemas/validators/Trust/R0/Q and V126 production code remain unchanged.

Local tests: adapter **34/34 PASS**, stdlib-only AP-01 **48/48 PASS**, existing
DR + installed PG17 ARM64 whole-DB fixture **49/49 PASS**, database unit **4/4 PASS**,
diagnostic boundary/causal regressions **21/21 PASS**. Synthetic fixtures only;
no operational DR PASS or Linux/amd64/PG18/live qualification is inferred.
Evidence and final changeset manifest: `/private/tmp/ht-ops-25-ap00/evidence/`.

No provider resources, real credentials/keys, source capture, transfer or custody
were provisioned/executed. AP-02 producer, AP-06 Trust acquisition and AP-07 custody
remain unimplemented/unauthorized; AP-03 real transfer/read-back remains unexecuted/
unauthorized. No staging/SSH/deploy/V126, publication or Actions operation occurred.
HT-OPS-25 review/repair continuation is recorded in HT-OPS-27 above. Earlier
checkpoints below remain historical.

## HT-OPS-20 — Diagnostic Transport Findings Repair

Local F1–F3 repair in `/private/tmp/ht-ops-04-policy-b-dr`, branch
`codex/ht-ops-04-policy-b-dr`, exact base/HEAD `c134e8b26cb3213e85ea460fc74d78cab1b38754`.
Fresh read-only remote checks returned that SHA for both main and the feature branch.
The exact HT-OPS-19 starting patch and four file hashes matched and were preserved in
`/private/tmp/ht-ops-20-repair/pre.patch` and `pre/`; index empty and no untracked files.
The primary checkout and all external operational systems remain outside this repair.

F1 contains PIPE/write failures in a diagnostic-only subshell, retaining the production
exit and completed cleanup even when the reader disappears. F2 requires an explicit
failure/completion witness; a delivered successful-stage prefix cannot become root from
nonzero exit alone. F3 validates the complete canonical production PASS proof, its bound
artifact hashes and cleanup witnesses; malformed/ambiguous proof fails with fixed copy.
Contract: `docs/V126_STAGING_CUTOVER_CONTRACT.md`, HT-OPS-20 section.

Final focused regressions **21/21 PASS**; full installed-PG17 suite **37/37 PASS**
(253.221s); selected real-body fake-Docker cleanup fixtures **24/24 PASS**; container-ID
suite **10/10 PASS**. Closed-reader before/after evidence preserves exact exit 72 (and
success 0), completes cleanup and removes both synthetic resources; lost successful-stage
prefixes give UNKNOWN. Genuine proof acceptance and 23 invalid/unreadable proof states pass.
AST/compile, standalone/embedded Bash syntax, diff/docs checks and bounded output scan pass.
The local PG17.8 ARM64 image was used with no pulls, ports or external network; both task
labels have zero containers and volumes after cleanup. Earlier failed validation attempts
and the final evidence are retained under `/private/tmp/ht-ops-20-repair`.

Production `scripts/v126-cutover.sh` bytes are unchanged by HT-OPS-20; stripping only the
existing observation additions reproduces exact HEAD in full. No production commands,
predicates, traps, SQL, restore flags, retries/sleeps or workflows changed.
`CI_487_489_FUNCTIONAL_ROOT_CAUSE_STILL_UNPROVEN` remains unchanged.
Verdict: `DIAGNOSTIC_TRANSPORT_FINDINGS_FIXED_READY_FOR_BOUNDED_REREVIEW`.

No commit, push, PR, Actions, SSH/staging, provider, V126 or deploy action is performed.
Next step: bounded independent re-review of the exact F1-F3 repair delta.


## HT-OPS-04 — Policy B DR / AP-01 local implementation

AP-01 is the only authorized package. The isolated feature changeset from exact
base `2c331a7c3809275367360685c7549c41f4e3ea86` implements closed versioned DR evidence,
source/tool binding, attempt-ledger/freshness/readiness decisions, S3/encryption
interfaces with test doubles, strict bundle/bootstrap/catalog validation and R0/Q
consumer hooks. Accepted D1–D4, DR-A…DR-G and authority boundaries are in
`docs/DEPLOYMENT_RUNBOOK.md`; restore and cutover contracts retain their own scope.

No operational DR phase, real provider/crypto/custody/restore, scheduler/fence,
V126 attempt, deploy, commit or push is performed by AP-01. Historical repair
reports/receipts remain historical. Source/runtime dispatch integration and real
adapters are future separately reviewed/authorized prerequisites. Next step:
independent review of the local diff and its focused test evidence. This is not
DR PASS or staging release readiness. Earlier checkpoints below are preserved.

Local validation: `python3 scripts/test-v126-dr-evidence.py --postgres-docker`
**37/37 PASS** (36 pure regressions + one real owned PG17 whole-DB test), existing
database-evidence `--unit` **4/4 PASS**, Python syntax, shell syntax, canonical
docs links/fences and tracked/new-file whitespace checks PASS. The installed PG17
image is ARM64; PG18/full Linux cutover harness and operational Linux/amd64 app/
real-auth restore were not run. The first Docker fixture startup failed because
it observed the temporary initialization server; the final-process readiness
check fixed it and later runs passed. All owned fixture containers were removed.

## HT-RELEASE-REPAIR-01 — implementation closure and delivery contract

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

Final acceptance requires the exact published commit containing these reviewed documents
to pass all12 jobs/mandatory steps with unchanged implementation and no report delta.
The immutable final-delivery attestation records its concrete SHA/tree/CI and disposition;
this paragraph does not predeclare a future CI result.

MAIN_INTEGRATION=NOT_AUTHORIZED; LIVE_ACTIONS=NONE; STAGING_RELEASE_READINESS=NOT_ESTABLISHED.

## Task checkpoints

Read the matching task entry first; preserved snapshots below are evidence/history, not
fresh instructions or authorization. Update concurrent tasks separately at meaningful boundaries.

### HT-RELEASE-REPAIR-01 — 2026-09-10 feature CI complete with open gates

- **HT_RELEASE_REPAIR_FEATURE_CI_PASSED_WITH_OPEN_GATES**. One F01–F11 changeset in
  `/private/tmp/ht-release-repair-01.RKbe7q/worktree`, existing branch
  `codex/ht-release-repair-01-rkbe7q`, exact basef7828e09863d391e1f714cc65c9c866f814cf6bf.
- Published candidate09e19461cf54376714ae51f2d4c9e480f8365d8e,
  tree17ec77daf4713264a36381a9d43ce4b4f4ef3151; exact push CI34428598301:
  **12/12 jobs and all mandatory steps PASS**, compose complete2026-09-10T02:46:20Z.
  Full mandatory harness6m25 and prerequisite18groups/40failure matrix19m37 PASS.
  Real Linux own SSH/Caddy/root, PG17/clients17–18/backup/Docker/JVM and all28 formerly
  skipped PostgreSQL cases execute. Synthetic worker progress is established, not live smoke.
- Candidate01/02 failures and corrective evidence retained. Candidate03 fixes the proven
  native stdin-checksum fixture and unsupported Compose create flag; production consumer
  remains unchanged by that correction. Candidate02 Mini App debounce failure is preserved;
  unchanged candidate03 full216 smoke PASS does not prove its underlying cause fixed.
- Baseline12 artifacts and known-completed target retirement pass with immutable history
  and fresh next baseline. F05 UNKNOWN daemon reconciliation and ordinary-deploy whole
  lifecycle locking/fresh-target race remain OPEN. F09 separate V125-schema125/V126 handoff
  policy is accepted, with no live env/restart/ownership application time authorized.
- F01/F02/F04/F07/F08/F10/F11 verified at recorded scope; F03/F06/F09 remain partially
  unverified. G01 operational DR/RPO/loss/auth, G02 privileged systemd/daemon/VM crash/reboot
  and G03/G04 delivery ambiguity/17 actual manual assertions remain open.
- One unified review and safe exact-CI evidence accompany REPAIR-REPORT/COVERAGE/CHECKPOINT.
  Final updates to those three files and this status remain local/uncommitted; executable
  candidate/source/test/workflow match green CI. Original dirty checkout, scripts/dev,
  migration bytes and frozen runs/evidence remain unchanged. Historical PASS1–8/intent9
  and separately sourced V125 availability are not upgraded.
- Stop here. Next is the single diff review and concrete decisions, then separate authority
  for any integration/publication/live step. MAIN_INTEGRATION=NOT_AUTHORIZED;
  LIVE_ACTIONS=NONE; STAGING_RELEASE_READINESS=NOT_ESTABLISHED. No HT13/new archive,
  PR, deployment, Gate A/B/C, recovery, automatic CI retry or GitHub diagnostics.

### HT-12AA — 2026-09-09

- Goal: correct pg_dumpall initial database selection; deliver one reviewed feature
  candidate with exact green CI, then stop before separately authorized main integration.
- Worktree `/private/tmp/hookah-ht-12aa-pg-dumpall`, branch `codex/ht-12aa-pg-dumpall`;
  fresh remote main/base `724dbe931c0af969b761cc174eb289a3e46b17ca`, tree
  `2c1d6577cc9b3aa338544370691abaef3149cff0`; base CI34301190566, workflow230370033,
  push/main attempt1, exact12/12 success verified. The different local main ref is untouched.
- Production change: only pg_dumpall `-d` to `-l`; exact source container/user/database,
  globals-only/no-role-passwords, dump/inventory/checksum, rehearsal/cleanup remain intact.
  Immutable before failure reproduced with container PostgreSQL17.8 linux/arm64; old
  harness only exercised quiesced with mocked clients and never reached globals; its
  static scanner also required the wrong -d for pg_dumpall. Both gaps now have regressions.
- Regression: real PG17 both-phase backup/rehearsal and explicit database/role proof;
  fault injection, privacy, create-only and ownership assertions in mandatory cutover/compose
  harness. Local full cutover458 PASS assertions, process guard7/7, health headers8/8,
  shell/Python/47 embedded-block compile and diff sanity PASS. One independent read-only
  review PASS after the scanner finding was fixed within that review. Final adjacent-harness
  results, candidate SHA/tree, exact feature CI and publication status are recorded in
  `/private/tmp/ht12aa-evidence/REPORT.md`; local PG17.8 is not live17.10 or Gate A evidence.
- Separate fixture finding: PG17.8 initdb failed for a bootstrap role containing a double
  quote; no producer/rehearsal workaround added. Full restore uses a normal synthetic role;
  complex role selection is checked by the real production globals command separately.
- Boundary: feature explicit-file commit/non-force push only. No PR/main integration,
  SSH/server/deploy, failed-stage retry, cutover init/authorization, Gate A/B/C/recovery,
  product writes or HT-13 artifact build. Frozen failed-run/partial files, dirty/release
  checkouts, scripts/dev and other-project resources are untouched.
- Next boundary after exact feature CI succeeds: separately authorized main integration only.

### HT-12Z — 2026-09-09

- Goal: repair canonical container-ID comparison; deliver one reviewed feature candidate with
  exact green feature CI, then stop before separately authorized main integration.
- Worktree `/private/tmp/hookah-ht-12z-feature`, branch `codex/ht-12z-canonical-container-id`;
  freshly verified origin/main/base `c67c364eb1c7d48a1919aabbc65f7ce7caa1d3e7`, tree
  `5d11052342eddeec9db810048f0905cd1834c2c3`; base CI34256848456, CI/230370033,
  push/main attempt1, 12/12 success.
- Before: unchanged production collectors/comparator reproduce Compose64/Docker12 count1 then
  identity refusal exit4. Previous baseline/runtime mocks returned the same short IDs from both
  sources. After: both collectors request `--no-trunc`, require unique canonical64 IDs, preserve
  full equality and separate image/count guards; malformed/error inventory remains fail-closed.
- Validation: argument-sensitive production fixtures, full cutover CI harness and real inert
  Docker/Compose regression in the existing `compose` job; no CI gate/job removed or weakened.
  Local daemon isolation is unconfirmed, so local fixture selection excludes real Docker/libpq
  authentication; those full checks remain mandatory in CI. Exact results, candidate SHA/tree,
  versions, one focused independent read-only review and CI evidence are recorded in
  `/private/tmp/ht12z-evidence/REPORT.md`.
- Local selection: 451 PASS assertions, canonical IDs 10/10; adjacent process guard 7/7 and health
  headers 8/8 after resolving local sandbox access limits; syntax/compile/diff sanity PASS.
- Boundary: explicit-file feature commit and ordinary feature push only; no PR/main integration,
  SSH, live inventory, cutover init/stage/retry, Gate A/B/C, prerequisite sync, server/product
  action or archive rebuild/retag. The consumed HT-13 intent still has no PASS and is not retryable.
  Original dirty/release worktrees, scripts/dev and frozen evidence remain untouched.
- Next boundary after exact feature CI succeeds: separately authorized main integration only.

### HT-12Y — 2026-09-08

- Goal: repair remote-envelope stdin framing; deliver one reviewed feature candidate with exact
  green CI and stop before separately authorized main integration.
- Worktree `/private/tmp/hookah-ht-12y-remote-envelope-stdin`, branch
  `codex/ht-12y-remote-envelope-stdin`; exact base `67fbfd4d587244712b15e974f485e08316ffd486`,
  tree `4cc8c18192f1f09116f9918c6123b960fda0dfc3`. Fresh origin/main matches; main CI
  `34210477394`, CI/230370033, push/main attempt1, exact 12/12 success verified.
- Cause: unchanged production builder/loader through real local `bash -s` fails exit4 before
  dispatch; a separate instrumented copy reads `loader_read action` as magic. Old loader tests
  read code from a file and only data from stdin. Historical SSH magic bytes remain unavailable.
- Decision: parse the complete loader as one Bash compound command before reading data; retain
  exact envelope/body/action/receipt checks and explicitly reject producer/protection/hash-command
  errors. Review also reproduced Bash NUL normalization; a NUL-rejecting capture now preserves
  exact text bytes before field parsing/hash. One transport covers stages and all three recovery
  modes; no new executor or fallback.
- Local evidence: Apple Bash3.2.57 stdin regression 9/9; full cutover harness 452 PASS assertions,
  including real libpq14.18 / isolated PostgreSQL17 authentication; process guard 7/7 and health
  headers 8/8; syntax, Python compile and diff sanity PASS. The complete cutover harness passed
  again after NUL remediation. One focused independent read-only reviewer returned final PASS.
  Exact final SHA/tree, Linux Bash/CI, review and publication results are recorded in
  `/private/tmp/ht12y-evidence/REPORT.md`; local evidence is not a Linux or live baseline claim.
- Boundary: only feature commit/non-force push and exact candidate CI are authorized. No PR,
  main change, SSH, server/product write, stage/init/retry, Gate A/B/C or archive rebuild/retag.
  The failed HT-13 intent remains without PASS and non-retryable. Dirty/release worktree files,
  scripts/dev, prior evidence and resources of other projects are not used or changed.
- Next boundary after feature CI succeeds: separately authorized main integration only.

### HT-12X — 2026-09-08

- Goal: repair tracked URI → service/pgpass serialization; stop at a reviewed green feature
  candidate before separate main integration. No server action, PR or release authority granted.
- Worktree `/private/tmp/hookah-ht-12x-libpq-service-serialization`, branch
  `codex/ht-12x-libpq-service-serialization`; base `5b205a8c53dfb374eed2803b7a120fe593149ddd`,
  tree `c59d82e3cbc807687c4e9ac7b6c187801b1c50c8`. Fresh remote main matches; base CI
  `34181927285`, push/main attempt 1, 12/12 success, independently verified.
- Decision: literal service values with explicit representability checks; strict URI decoding;
  distinct pgpass escaping; real linked-libpq coverage in the existing mandatory cutover harness.
  Allowed options, stages/receipts/recovery/gates and CI provenance remain unchanged.
- Evidence: local psql 14.18 / `PQlibVersion=140018` reproduces old quoted-port exit2 and verifies
  fixed values; disposable PostgreSQL17 SCRAM proof passes exact role/database/read-only and
  password-used checks. Full cutover harness: 451 PASS assertions; final real-libpq suite 11/11;
  standalone process guard 7/7 and health headers 8/8; shell/47 embedded Python blocks/compile/diff
  sanity PASS. The initial bundled process-check command contaminated its own inventory; the
  unchanged standalone rerun passed. One independent read-only reviewer returned PASS after two
  test-only lifecycle/privacy findings were fixed. Exact candidate/CI and detailed evidence are
  recorded in `/private/tmp/ht12x-evidence/REPORT.md`; no Linux18 or live claim rests on local14.
- Boundary: original/release worktrees have no writes; initial mistaken root-doc read is recorded
  in the report. scripts/dev and frozen evidence untouched. No server client install, protected
  input, prerequisite write, cutover, product/DB/Telegram action or live host proof.
- Authorized remainder: explicit-file feature commit/non-force push and exact feature CI, then
  stop for separate main integration; consult the final report for publication/CI state. No PR.
  HT-13 remains blocked:
  HOST_CONNECTION_VERIFIED=NO, DB_INPUT_CREATED=NO, CUTOVER_INIT=NOT_STARTED, GATE_A=NOT_AUTHORIZED.

### HT-12W — 2026-09-07

- Goal: repair tracked cutover CI contract and prepare ownership/protected-input resolution;
  stop at a green feature candidate, without HT-13 or main integration.
- Worktree `/private/tmp/hookah-ht-12w-ci-contract`, branch `codex/ht-12w-ci-contract`;
  base `8436ee7b219ca6074aceecc47d6bf5f24803621d`, tree
  `ea5ffc713337ce01c2ad7a0308363a99dbbf31dd`. Fresh remote main matches; exact base
  CI `34146214650`, workflow `CI`, push/main, attempt 1, 12/12 success.
- Decision: embedded exact release job-name contract checked against the tracked workflow;
  explicit repository/workflow/run provenance; retained mode-0400 Actions evidence;
  downstream receipt replay validates CI and reconstructs the new local proof. No old-proof bypass.
- Validation: focused suite 9/9, full cutover harness 448 PASS assertions, real-process 7/7,
  syntax/compile and diff sanity pass. Initial sandbox ps denial was resolved by an authorized
  local rerun. Full unchanged prerequisite harness is running; final results are in
  `/private/tmp/ht12w-evidence/REPORT.md`. One independent read-only reviewer returned PASS
  with no blockers. Feature commit/push and exact feature CI are next; no live operation exists.
- External blockers: root cutover rejects three 501:0 release files. Parent `scripts` is also
  501:0/0755, so three-file chown alone is insufficient. Separately approve that one directory
  plus the three exact file ownership changes, preserving bytes/modes. No metadata change made.
  Old alias manifest exists at 0:0/0600 but is not an approved maintenance input. Database and
  maintenance input paths/provenance/approval remain unconfirmed; proposed paths and minimal
  preparation are in the canonical cutover contract's HT-12W readiness table.
- Preserved: successful sync `V126-PRE-GATE-A-SYNC-20260907T181340Z` (four writes, 40/40)
  and old release archive; no repeat, rollback, retag or rebuild. A changed source SHA requires
  separate archive applicability review. Original dirty/release worktrees and scripts/dev untouched.
- Authorized remainder: one read-only independent review, explicit-file feature commit and
  ordinary non-force feature push, exact CI wait. No PR/merge/deploy, remote writes, cutover
  init/stage/authorize, new protected inputs, product/DB/Telegram writes or Gate A/B/C.
- Next: complete the remaining local prerequisite gate and exact feature CI; candidate SHA/tree,
  CI receipt and final Git status are recorded in `/private/tmp/ht12w-evidence/REPORT.md`.
  Stop for a separate main-integration decision. Ownership/inputs are not declared ready.

### HT-12V — 2026-09-07

- Goal: repair prerequisite process-observer self-match, verify one feature candidate and stop
  before main integration. Worktree `/private/tmp/hookah-ht-12v-process-guard`, branch
  `codex/ht-12v-process-guard`; exact base `55721216febf00111db26ad6e37cdd02728424a5`, tree
  `7874153749dee12f763f9b6d176aef370399841e`. Fresh remote main matches; the original dirty
  checkout's local main remains `4daf5546fb622a6b967398f5c25b7bed41d7fa05` and is untouched.
- Decision: stdin-embedded observer, checked full-width ps inventory and status, exact PID
  exclusions, bounded sanitized refusal. Shared prewrite/capture/restored-baseline callsite;
  no equivalent host-process guard in cutover. No new lock or later-source-file dependency.
- Checks: real macOS production-process regression 7/7; full prerequisite harness 18 groups,
  40/40 post-sync failures, seven process prewrite refusals with zero config writes, existing
  command/signal/rollback-once gates; unchanged health regression 8/8; complete cutover harness,
  syntax/compile, maintenance/image/admission self-tests, Compose fixture and diff check PASS.
  Only the final harness display denominator was corrected from 17 to 18 after that full run;
  the executed fixture body is byte-identical. A parallel local real-ps attempt correctly
  detected this task's running prerequisite harness; the final serial rerun passed.
- Review/state: one independent read-only full-diff review found no blockers. Feature commit,
  ordinary push and exact CI remain within this task; candidate/CI receipts and final status
  are recorded in `/private/tmp/ht12v-report.md` after those operations. No main authorization.
- Boundary: application/Mini App/migrations/PRODUCT/maintenance/GET-health remain unchanged;
  four config writes, rollback-once and `GATE_A=NOT_STARTED` preserved. No SSH, live diagnosis,
  sync/cutover outside fixtures, deploy, new HT-13 artifact or main integration. No unfinished
  live operation. `scripts/dev/`, dirty/release worktrees and frozen evidence remain untouched.
- Historical source: `/private/tmp/ht13-preparation-20260907T.yDuLOJ/SYNC-EXECUTION-REPORT.md`;
  original L06 matching rows unavailable. No claim that fixing the reproduced self-match proves
  the absence of an original transient competitor or future HT-13 success.
- Next boundary: after exact feature CI succeeds, separately authorized main integration only.
  Do not resume HT-13 or reuse its consumed sync authorization.

### HT-12U — 2026-09-07

- Goal: reconcile the already reviewed health-response-header GET probe with the integrated
  workflow instructions; no new health/405 diagnosis or product behavior work.
- Sources: main `4785070623611043d23778ece047edf43ba00662`, tree
  `dc602e8c51038a55628aff9a88a659b83f2192f3`, has CI `34086765241` successful at 12/12;
  reviewed HT-12U `71e7a8fbfe3617204c983b7e381b3def6626d40a`, tree
  `de0ddda7b3fb06ed2c3476bef0899f314d9684f3`, has feature CI `33990992887` successful at
  12/12. Their exact common parent is `47b79ca86574024f60be6dcbdf3533c561f24d02`.
- State/decision: ordinary merge `4dc821c2c27838e93481fb15db79d085146c4830` has parents
  HT-12U then main. It preserves main `AGENTS.md` and `docs/MODEL_WORKFLOW.md`, and preserves
  HT-12U health-header scripts/tests, CI and release docs byte-for-byte. The only remaining
  reconciliation is this checkpoint update; main integration, V126 deploy and HT-13 remain
  unperformed and unauthorized.
- Checks/evidence: Git object/ancestry and both exact CI runs verified. Existing candidate
  validation remains applicable after byte identity confirmation; docs sanity and diff check
  are required on the final feature candidate. A new exact feature CI is mandatory after push.
- Authorization: one checkpoint-only commit and ordinary non-force push of this feature are
  authorized. No rebase, cherry-pick, squash, amend, force push, PR, main mutation, SSH,
  staging/production, sync/cutover/deploy, backup, Flyway/V126 or global configuration work.
  `scripts/dev/` is not read or changed. Historical release artifacts/evidence are not reused
  or renamed for this candidate.
- Next: verify final byte identity, push the reconciled feature candidate, require its 12/12
  Actions including health-header regression, prerequisite/cutover and image/archive gates,
  then stop for separate main-integration authorization.

### HT-WORKFLOW-MODELS — 2026-09-07

- Goal: common instructions, temporary Astra coding priority and portable continuation;
  docs-only local draft, not a product audit or release task.
- Worktree: `/private/tmp/hookah-ht-workflow-models`; branch `codex/ht-workflow-models`;
  base/HEAD `47b79ca86574024f60be6dcbdf3533c561f24d02`. A fresh fetch in the authorized
  commit/push phase found `origin/main` unchanged at that same SHA; no merge or rebase.
  Original dirty `main` is `4daf5546fb622a6b967398f5c25b7bed41d7fa05`, 16 commits behind
  that local ref. No fetch/live remote freshness claim; no foreign changes copied.
- State/decisions: draft in `AGENTS.md`, [model policy](docs/MODEL_WORKFLOW.md) and this
  existing checkpoint; no second memory file. `ASTRA_CODE_PRIORITY` is the requested
  policy; `BALANCED_COST` requires the user's explicit decision. The task instructions
  govern this run; draft files are uncommitted/unintegrated and do not update other worktrees.
- Direct workflow review: user reports ChatGPT read the full package and verified SHA-256
  `d0335364c8a73986785b86e548b8d2b55406d4245c04a5d6b9d9a3b4418846bb`.
  Structure/model policy accepted; requested final-report, policy-reference, Astra-link and
  checkpoint corrections applied locally. User then authorized one feature commit and ordinary
  non-force push of the three reviewed files; main integration remains unperformed. Follow-up
  docs checks are recorded separately in
  [review correction results](/private/tmp/ht-workflow-models-review-corrections-20260907.md).
- Checks/evidence: initial worktree clean; Git refs/log and ancestry checked;
  official sources and safe local client observations are linked in the model policy.
  Docs sanity, seven manual policy scenarios and `git diff --check` passed;
  [local validation record](/private/tmp/ht-workflow-models-validation.txt) (temporary artifact).
  Original dirty tracked files/global config and preserved release snapshot are unchanged.
  Shared model-cache bytes changed during inspection; cause unknown, selected defaults/efforts
  unchanged, no explicit write or restoration. No runtime tests/build/deploy required or run.
- Unfinished operations: none started; no mutation/rollback or release run to resume here.
- Authorization: one explicit-file feature commit and ordinary non-force push are authorized
  for `AGENTS.md`, `PROJECT_STATUS.md` and `docs/MODEL_WORKFLOW.md` only. Main integration,
  merge, rebase, force push, SSH, staging/production, Docker builds, sync/cutover/deploy/
  migrations, product code, global config/catalog/account changes and Google-journal writes
  remain unauthorized. `scripts/dev/` not read or changed. Existing release evidence,
  procedures and one-time permissions remain intact.
- Blocker: none for this docs task; exact experimental-key/client/account support remains
  partly unverified as documented, with no paid probe. Next: final docs check, authorized
  feature commit/push and candidate-specific Actions check; main integration needs separate
  authorization.

### Other task boundaries — observed 2026-09-07, not resumed

- HT-12U/HT-13: local HT-12U ref `codex/ht-12u-health-response-headers` is
  `71e7a8fbfe3617204c983b7e381b3def6626d40a`; `git merge-base --is-ancestor` against this
  base returns `1`. No HT-12U integration into this base is established. V126 deployment,
  live migration state and fresh release evidence were not checked. No task is closed or
  authorized to resume by this workflow update.
- Separate product audit: user reports via ChatGPT that it was received and reviewed there;
  audited checkout was `4daf5546fb622a6b967398f5c25b7bed41d7fa05`. This is not a fresh
  current-main or staging check. Codex has not read that audit in this task; no findings are
  imported or acted on here, and its checkpoint/worktree remains outside this task.

## Preserved product/release snapshot

The dated text below is retained unchanged. Its "fresh/current/pending" statements refer to
their recorded task boundary, not a fresh Git/CI/runtime verification by HT-WORKFLOW-MODELS.

Last verified: 2026-09-04.

## 1. Current stage

**HT-12U HEALTH RESPONSE HEADERS / LOCAL GET-PROBE REPAIR AND FULL FIXTURES PASSED /
INDEPENDENT REVIEW AND EXACT FEATURE CI REQUIRED BEFORE MAIN INTEGRATION**.

This bounded stage starts from main `47b79ca86574024f60be6dcbdf3533c561f24d02`, tree
`f3d0330366a9af30650e885609c970dc7233973e`, with CI run `33946844132` successful at 12/12 jobs.
Public header acquisition in the prerequisite baseline and checks 32/34 now uses bounded GET
`/health`, exact 200 and strict header parsing. Alt-Svc refusal, healthy-JSON/TLS/UDP checks,
four-write/rollback boundaries, migrations, application behavior and Mini App probes are preserved.
The retained L06 HEAD `/health` 405 is historical evidence with unestablished network origin;
the earlier `MINIAPP_405_ROOT_CAUSE_NOT_PROVABLE` verdict is unchanged. No staging access or
HT-13 resumption is authorized here. Main integration requires separate authorization after the
complete local and feature-CI gates. The earlier build evidence below remains historical.

Local evidence: 8 HTTP regression methods, prerequisite harness 17/17 groups with 40/40 named
post-sync failures and 8/8 prewrite plus 8/8 post-sync command errors, complete cutover harness,
syntax/Python compile, Compose and admission/maintenance/image/archive guard self-tests passed.

### Preserved HT-12T preintegration evidence

**HT-12T CANONICAL DOCKER-SAVE RELEASE ARTIFACT EXPORT /
EXACT MAIN BASE, TREE, PARENT AND 12/12 MAIN ACTIONS VERIFIED /
TERMINAL HT-13 ARCHIVE FAILURE CLASSIFIED AS `ARCHIVE_VALIDATOR_CONTRACT_MISMATCH` /
SAME-RUN ATOMIC EXPORT, STRICT ARCHIVE VERIFIER, EXACT LOAD PROOF AND CI ENFORCEMENT IMPLEMENTED /
LOCAL STATIC/FIXTURE/HARNESS VALIDATION AND INDEPENDENT NON-BLOCKING REVIEW PASSED /
FROZEN-CANDIDATE DOUBLE BUILD/ARCHIVE/LOAD AND GREEN FEATURE-BRANCH ACTIONS REQUIRED /
NO MAIN INTEGRATION, STAGING ACCESS OR HT-13 RESUMPTION**.

Fresh `origin/main` is exact commit `364e04640dbd53b79084faba61362c02f1ba6301`, tree
`20b9a6cf7acd3cfed5c9ae18169bc07a8fb7b5dc`, direct parent
`9547ebc0738257e60b262104cecee9c8ee1bfdfc`. Main Actions run `33894927720` is workflow `CI`,
event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `12/12` jobs
succeeded, including the Docker backend comparator fixtures and two independent complete no-cache
builds. HT-12T runs only in the clean isolated worktree on branch
`codex/ht-12t-canonical-docker-save-artifact`; the original dirty main worktree and `scripts/dev/`
remain untouched.

The retained latest HT-13 Phase-0 evidence contains two equal complete images. The proven
daemon-facing final image/OCI manifest ID is
`sha256:a8ef37c18a2e6054ba8c1b010cb94eeab20d5cbe5774516ed25e9f8c8150cd0c`, while the exact config
digest is `sha256:024446fe04b9e81fe170f9885bcff6fafd971f19b670bf4633bdb0edfcf785f0`.
The retained Docker-save archive has the correct full-SHA RepoTag, config bytes, ten layers and OCI
manifest linkage, but the previous sequencer verifier compared its config path to the distinct
containerd manifest ID and stopped at `saved image archive config name differs from expected image
ID`. The exact classification is `ARCHIVE_VALIDATOR_CONTRACT_MISMATCH`; Docker load was never the
failed boundary.

The same double-build guard now exports one canonical mode-0600 Docker-save archive from proven
image A before cleanup. It fixes the archive SHA-256 before load, validates tag/config/final-ID,
platform, runtime user, labels, compressed layers and ordered DiffIDs, removes the run-owned image
records, proves the tag/ID absent, loads the exact archive, re-inspects the complete identity and
publishes only through an explicit new absolute path using a no-replace atomic link. Normal CI
validates and removes the archive. The standard-library verifier has a 37-case archive corpus plus
publication and cleanup fixtures; those same archive cases require exact accept/reject equivalence
with the sequencer's embedded verifier. No third build, OCI post-cleanup reconstruction, registry
publish or staging action exists.

The independent read-only build/release review found no open P0, P1 or blocking P2. Remaining gates
are a cohesive frozen-candidate commit, complete two-build validation including one exact candidate
archive and load proof, ordinary feature-branch push and exact green branch Actions with the Docker
archive gate executed. Main integration requires separate exact authorization. The required stop is
`HT12T_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`.

The first feature run `33916435202` was correctly non-green: Docker Engine 28 proved both images
equal with config/final ID `sha256:acce75da6b132b5be59ff3c715c8576d072ba8737b667af0b835f91bef57e474`,
but its Docker-save output also carried a reserialized OCI side manifest distinct from the original
build manifest. The first implementation incorrectly treated the mere presence of that side metadata
as manifest-ID mode and stopped at `Docker-save OCI manifest differs from the proven manifest`.
The correction selects strict OCI-manifest identity only when the proven final ID differs from the
config digest; config-ID archives remain bound through config bytes plus ordered DiffIDs even when
Docker includes OCI side metadata, while every present OCI sidecar is still checked for internally
exact config and ordered layer-blob linkage. The absent-image exact reload remains mandatory. Run
`33916435202` is regression evidence only and is not release authority; a succeeding exact feature
run is required.

### Preserved HT-12S preintegration evidence

The historical HT-12S snapshot below is retained as reproducibility evidence. Its then-pending
branch/integration wording is superseded by the exact HT-12T main base and green main run above.

**HT-12S DETERMINISTIC BACKEND FULL-IMAGE REPRODUCIBILITY CLOSURE /
EXACT MAIN BASE AND 12/12 MAIN ACTIONS VERIFIED /
EXACT FILESYSTEM ROOT CAUSE PROVED AS BUILDKIT EXPORTER/TIMESTAMP CONTRACT /
IMMUTABLE BASE PINS, EXPLICIT TIMESTAMP-REWRITING EXPORT AND FULL-IMAGE CI GUARD IMPLEMENTED /
LOCAL FULL-IMAGE VALIDATION, INDEPENDENT REVIEW AND GREEN FEATURE-BRANCH ACTIONS REQUIRED /
NO MAIN INTEGRATION, STAGING ACCESS OR HT-13 RESUMPTION**.

Fresh required `origin/main` is exact commit
`9547ebc0738257e60b262104cecee9c8ee1bfdfc`, tree
`54db71be7b5983c3a6d64da14d1494e7bb6e8866`. Exact main Actions run `33834936070` is workflow
`CI`, event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `12/12`
jobs succeeded. HT-12S runs only in a clean isolated worktree on branch
`codex/ht-12s-deterministic-backend-image`; the original dirty main worktree remains untouched.

The retained failed HT-13 images have manifests
`sha256:e05131bbad7be60b3cd83e9e9690648bd15cfec7f7a721e8de4f1421840085b3` and
`sha256:125d55204d197d5ceef4b291a4ab1309f62b6aea8ed163fbb3bd4b234944ea8e`, and configs
`sha256:b6d436ac4f240d1c62294b14c46728fdcebcdab08e464dfeda8fc8e95e879901` and
`sha256:329801345ec7052fb9d67b5d0fb570850ba3a2fdcb46de2aa240f54ef6faab0b`.
History-to-DiffID alignment proves the first differing one-based filesystem layer is layer 8,
`RUN groupadd -r appuser && useradd -r -g appuser appuser`. The first differing tar byte is byte 144,
the `mtime` field of `etc`; eight account-layer paths differ only in mtime. Layers 9 and 10 contain
equal backend and Mini App path order, types, modes, owners, link/device/PAX/xattr/whiteout metadata
and regular-file contents, but all 100 backend entries and all six Mini App entries have build-time
mtimes. Account-file bytes, 93 dependency JARs, both launchers, the application JAR and three Mini App
files are identical. The exact classification is `BUILDKIT_EXPORTER_OR_TIMESTAMP_CONTRACT`.

`backend/Dockerfile` pins the Node build, Temurin JDK build and Temurin JRE runtime bases by both
reviewable tag and immutable index digest, with the exact resolved `linux/amd64` platform digest
recorded beside each pin. Backend image builds use the explicit
`type=docker,oci-mediatypes=true,rewrite-timestamp=true` exporter with the commit-derived
`SOURCE_DATE_EPOCH`. `scripts/check-backend-image-reproducibility.sh` and its standard-library-only
comparator perform two independent no-cache builds and compare the complete image, including final
image ID. The existing Gradle archive gate remains mandatory. The Docker CI job now executes the
comparator fixtures and the complete double-build guard, and the backend aggregate requires that
job. The required stop after local validation, independent review, ordinary feature-branch push and
exact green branch Actions is `HT12S_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`; HT-13 and staging
remain unauthorized.

### Preserved HT-12R preintegration evidence

**HT-12R TRACKED V126 PRE-GATE-A PREREQUISITE SYNC ORCHESTRATOR /
EXACT MAIN BASE AND 12/12 MAIN ACTIONS VERIFIED /
TRACKED FAIL-CLOSED IMPLEMENTATION AND COMPLETE LOCAL FIXTURE HARNESS ADDED /
COMPLETE LOCAL VALIDATION PASSED /
INDEPENDENT READ-ONLY REVIEW AND EXACT GREEN FEATURE-BRANCH ACTIONS ARE MANDATORY BEFORE INTEGRATION /
NO MAIN INTEGRATION, STAGING ACCESS OR CUTOVER STARTED**.

Fresh required `origin/main` is exact commit
`a648e75179975c97daa4b3dae03070e6476d8a9a`, tree
`14f2400434c8546f5aba7c4bcc94fd20622625d1`, parent
`15a996575dcb863d7f4fe2f8110d3d56399fa354`. Exact main Actions run `33658844231` is workflow
`CI`, event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `12/12`
jobs succeeded. HT-12R runs only in a clean isolated worktree on branch
`codex/ht-12r-tracked-prerequisite-sync`; the original local main worktree remains untouched.

`scripts/v126-staging-prerequisite-sync.sh` is the sole tracked command authority for synchronizing
the exact release Compose file, maintenance guard, admission guard and three maintenance-`OFF`
`.env` keys before Gate A. Its helper, 40-entry named check map and local-only mocked harness are
tracked beside it. The command verifies exact release Git-object bytes, the green main release
identity, V125/Flyway/Caddy/runtime baseline, durable hash-chained checkpoints and rollback inputs;
then it permits only four atomic staging-file writes, executes independent post-sync checks, records
the first failure before one bounded rollback, reverifies the restored V125 baseline, and preserves
the original nonzero exit status. Every remote argv is serialized into one shell-quoted SSH command;
each action is timeout-bounded and holds the same restricted per-run lock, so signal handling waits
for mutation quiescence before rollback. Successful completion stops with `GATE_A=NOT_STARTED`.

The complete local mocked harness passed all `17/17` fixture groups: the success path; `4/4`
during-write and `4/4` after-write failures; `40/40` independently checkpointed post-sync
failures; exact one-rollback, original-status and no-later-phase assertions; active-remote
SIGINT/SIGTERM with repeated-trap handling; natural health/version `curl`, read-only database,
TLS-1.3 client, `docker logs` and `ss` execution failures in prewrite and the corresponding
post-sync checks; strict canonical/status-bound first-failure
and tri-state write-evidence rejection; real helper-backed parent-directory-fsynced unlink;
zero/partial transfers; source/helper/checkpoint integrity; exact `.env` preservation; PRODUCT and
Caddy helper plus inline-prewrite matrices; and zero real external calls. The unchanged 20-stage V126 sequencer harness and
the maintenance, image-identity and admission guard self-tests also passed. Compose parsed from a
disposable copy using `.env.example`; immutable migration identities remain exact.

The HT-13 R1-R5 temporary controllers, including the terminal R5 false Caddy-prefix assertion, are
rejected historical evidence only. Installed Caddy output `2.6.2` is accepted as the same exact
semantic version as `v2.6.2`; malformed, ambiguous or different versions still fail while the
Caddyfile hash, active service, validation, drain, TLS, Alt-Svc and UDP checks remain independent.
No R6, temporary dispatcher, temporary execution package or external dispatcher is permitted.

Prerequisite sync is separate from `scripts/v126-cutover.sh` Gate A. A successful sync does not
authorize backup or rehearsal creation, drain activation, Caddy change, image transfer/load,
container restart/start, maintenance `V126_SMOKE`, Flyway/V126, product/database/Telegram writes or
any cutover stage. After later authorized HT-12R integration, the exact main release SHA and V126
image tag/ID must be reselected and proved again; no historical HT-13 authorization or image
identity is reusable. The current required stop after local validation, review, feature-branch push
and exact green branch Actions is `HT12R_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`.

### Preserved HT-12Q preintegration evidence

**HT-12Q REPRODUCIBLE GRADLE ARCHIVE AND DETERMINISTIC V126 IMAGE CLOSURE /
EXACT MAIN BASE AND 11/11 MAIN ACTIONS VERIFIED /
PRE-FIX WALL-CLOCK JAR TIMESTAMP DEFECT REPRODUCED /
MINIMAL BACKEND APPLICATION ARCHIVE CONFIGURATION IMPLEMENTED /
POST-FIX JAR AND INSTALLDIST REPRODUCIBILITY PROVED /
TWO INDEPENDENT CACHE-FREE LINUX/AMD64 IMAGE BUILDS IDENTICAL /
LOCAL RELEASE GUARDS AND INDEPENDENT READ-ONLY REVIEW PASSED / NO P0, P1 OR BLOCKING P2 /
FEATURE-BRANCH COMMIT, NON-FORCE PUSH AND EXACT GREEN ACTIONS REQUIRED /
NO MAIN INTEGRATION, STAGING ACCESS OR CUTOVER STARTED**.

Fresh required `origin/main` is exact commit
`15a996575dcb863d7f4fe2f8110d3d56399fa354`, tree
`3802e89a61190066c369cfe3293dd5e6ff1ad809`. Exact main Actions run `33632063646` is workflow
`CI`, event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `11/11`
jobs succeeded with zero adverse job conclusions. HT-12Q runs only in the clean isolated worktree
for branch `codex/ht-12q-reproducible-gradle-image`; the original local main worktree is unchanged.

The exact `:backend:app:installDist` task graph is
`checkKotlinGradlePluginConfigurationErrors -> compileKotlin/compileJava -> processResources ->
classes -> jar -> startScripts -> installDist`. The only archive task consumed by this graph is
`:backend:app:jar`, producing `backend/app/build/libs/app-0.1.0.jar` and installing the same bytes as
`lib/app-0.1.0.jar`. Two independent pre-fix clean, no-build-cache, forced builds produced raw JAR
SHA-256 values `b491acaf9aaf59d67396b1340431e164f16b5df36a30af16408c62652abee189` and
`47a579ff54afbe6ff017a073ae4d0afb24bcd4181518ee0e99c670560a595979`. All `4216` entry names,
their order and uncompressed contents matched, but all `4216` ZIP timestamps differed. The complete
`installDist` path/type/mode manifest retained `96` regular files, no symlinks and no unexpected
types; only the application JAR hash differed.

`backend/app/build.gradle.kts` now configures every `AbstractArchiveTask` in the backend application
project with `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true`. This is the
smallest scope covering the complete current `installDist` archive graph while also preventing a
future backend-app archive task from silently retaining timestamps. No compression, permission,
manifest, version, Dockerfile, runtime, sequencer or migration semantics changed.

`scripts/check-gradle-archive-reproducibility.sh` performs two independent `clean` plus
`installDist` builds with `--no-build-cache` and `--rerun-tasks`, separated beyond ZIP timestamp
granularity. It requires raw JAR equality, equal unique entry names and order, Gradle's fixed
`1980-02-01T00:00:00` timestamps, a complete equal regular-file SHA-256/type/mode manifest, exact
launcher and library modes, the generated/installed application JAR identity and zero symlinks or
unexpected paths/types. The check reports only relative distribution paths, labels and hashes. It
failed on the uncorrected base for raw JAR/timestamp/installDist inequality, then passed after the
Gradle correction with both JARs equal to
`34e9b501171df13ea9d84ca8e620c8b81302f2dd7630cb9e9734445688738935`. A dedicated
`backend-archive-reproducibility` CI job runs the guard on Java 21 and is a mandatory dependency of
the existing `backend` aggregate; no existing job, selector or minimum was removed.

Two separate run-created Buildx docker-container builders independently built the same candidate
working tree for `linux/amd64` with `--pull`, `--no-cache`, `--provenance=false`, identical staging
public URL and Gradle JVM arguments, and `SOURCE_DATE_EPOCH=1788351553` derived from the exact source
HEAD timestamp. BuildKit rewrote layer timestamps to that epoch. Both builds produced application
JAR SHA-256 `34e9b501171df13ea9d84ca8e620c8b81302f2dd7630cb9e9734445688738935`, application-layer DiffID
`sha256:8e76286006db24b72ccb0d3c3fcae4099724252de9e10fb337af344d86ebf4a3`, compressed application
layer digest `sha256:4722a47e4a36e34031010d1e08223bed2fab42d49b56ac168e5f4fc0e07b22e1`, image config
`sha256:486427e6721401e8bbcac082d6bfbf5cd91aa7e6eab73af53020b726f7f86b44` and final image ID
`sha256:872df96da5f5f0948b00572617dd7a521c9f7057f6ee52da8886dfcd4bf5893f`. Both complete ten-layer
inventories matched. Only the run-created stopped extraction containers, test tags, builders,
builder caches and temporary evidence directories were removed after hashes were recorded.

Local validation passes the new double-build guard, backend compile and ktlint, the full HT-12P
sequencer harness, maintenance/image/admission guards and Compose validation. The immutable complete,
PostgreSQL and H2 migration trees remain `765956602de896b4498a956753272a6bc2d2971e`,
`bb2778e26e03e03211eab9f149777313f4a6f24b` and
`07b5ba6ccf25e79c9cc419b9095bb664f2cfae18`; PostgreSQL V126/H2 V127 retain blob
`6f39f7d33b1976d0f5eb7a70051bfc5351d12e56`, SHA-256
`ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5` and Flyway checksum
`1701638026`.

The independent read-only build/release review passed with no P0, P1, P2 or P3 findings. The
remaining HT-12Q gates are a minimum cohesive feature-branch commit, ordinary non-force push and an
exact green branch Actions run whose remote SHA equals the reviewed candidate. Main integration,
staging/production access, SSH, upload, deployment and V126 execution remain unauthorized. The
required stop after those gates is `HT12Q_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`; HT-13 must not
start automatically.

### Preserved HT-12P preintegration evidence

The historical HT-12P snapshot below is preserved as sequencer evidence. Its then-pending branch
state and its narrower image-build restriction are not current HT-12Q authority; HT-12P is already
integrated into the exact HT-12Q main base above.

**HT-12P V126 EXECUTABLE CUTOVER CONTRACT AND SEQUENCER CLOSURE /
EXACT MAIN BASE VERIFIED / HT-13 PREDEPLOY CONTRACT STOP RECORDED /
FEATURE-BRANCH ACTIONS EXPOSED A CLEANUP-TRAP ERROR-UNWIND DEFECT /
EXPLICITLY APPROVED CLEANUP HARDENING IMPLEMENTED / LOCAL 441-ASSERTION ADVERSARIAL VALIDATION PASSED /
INDEPENDENT READ-ONLY RE-REVIEW PASSED / FINAL CLEANUP COMMIT AND NON-FORCE PUSH REQUIRED /
EXACT GREEN POST-FIX FEATURE-BRANCH ACTIONS REQUIRED /
NO STAGING ACCESS OR CUTOVER STARTED**.

Fresh required `origin/main` is exact merge commit
`ecb09601975678a41d89e5c824cc7812c7876481`, tree
`8c97996e317f0182b4871d2a2537a732d4830f64`, with ordered parents
`9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1` then
`d9c656b1c5feb757b79558209f130c08cba81cf5`. Exact main Actions run `33536142005` is workflow
`CI`, event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `11/11`
jobs succeeded with zero adverse conclusions. Any fresh-base mismatch stops as
`MAIN_BASE_DIVERGED_BEFORE_HT12P`. This merge is the HT-12P base, not the final V126 release SHA.

HT-12M is integrated and closed with final verdict
`IDENTITY_GATED_MAINTENANCE_PREREQUISITE_COMPLETE`. Its historical port base was
`b49a89a299d8c9864fcfc5937d455141563b388a`; the integration commit is
`f837b0ed01f68832b305d5a2ed61b3927583f1e9`. The authoritative V125 staging source remains
`f577934691a1a7a79ba327c54e2055425142b7be`, with image ID
`sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`,
`TELEGRAM_TRAFFIC_POLICY=PRODUCT`, maintenance `OFF`, empty product and maintenance lists, one
backend/poller, healthy PostgreSQL, Flyway V125 and V126 absent. Detailed immutable mapping,
validation and review evidence remains in
`docs/HT12M_IDENTITY_MAINTENANCE_MAIN_PORT_EVIDENCE.md`.

HT-13 stopped before run-namespace allocation and before sealed-input creation with
`PREDEPLOY_CONTRACT_NOT_PROVABLE`. No HT-13 package was created and no staging mutation occurred.
That attempt and every historical HT-12D through HT-12K artifact remain rejected background
evidence, not reusable cutover authority.

HT-12P makes the contract executable through `scripts/v126-cutover.sh`, with bounded static and
fixture proof in `scripts/test-v126-cutover.sh`. The scripts are command/test authority;
`docs/V126_STAGING_CUTOVER_CONTRACT.md` is ordered policy and state-machine authority; other runbooks
link to them rather than duplicating commands. One immutable run manifest, intent-before-action
records, hash-chained stage receipts and separate Gate A/B/C authorizations enforce all 20 stages.
Every state has an exact artifact inventory plus a real mode-0400 operation log whose emitted
artifact lines are replayed; a rechecksummed substituted, missing or extra artifact is rejected.
Remote actions exist only behind a streamed stdin envelope bound to the run/script/intent/gate and
exact stage-action tuple, not a public helper command. The state lock tracks owner, worker, SSH and
rsync children, exits on signals and allows only an atomic dead-lock recovery takeover after all
liveness is disproved.

State 1 content-binds the restricted database/identity files and the exact `RELEASE_SHA` Compose,
maintenance-guard and admission-guard sources; the later HT-13 preparation must make that remote
execution surface exact before baseline can pass. Release/object reads clear inherited Git controls,
disable replacement objects and use exact blob plumbing. The no-build image transfer snapshots one
mode-0400 unlinked descriptor for local parse/hash/upload and another for remote parse/hash/load, with
actual Bash-3.2/openrsync-compatible syntax; no post-verification pathname is executed. Transfer and
later startup are separate states. Startup resolves the backend service specifically, disables
restart, requires `RestartCount=0`, executes one explicit start and proves one long-polling V126
backend with global
V125 zero and staging-project old-image zero. Caddy candidate activation is proved before the drain
marker; Gate B requires the exact 17-assertion evidence schema and a protected unauthenticated
generic-`503` proof.
Recovery classifies Flyway before Caddy/backend mutation, covers partial Caddy activation and
terminalizes exact-V126, already-stopped, unexpected-V125 or unknown-image post-V126 states safely.
Pre-V126 rollback, post-V126 forward-fix stop and full-DR prerequisite verification remain bounded
executable branches.

Feature-branch Compose run `33595906441` first exposed a harness-only GNU `stat` portability defect;
normal commit `4a33dd79b54ca2bbc8ee6d7296fabec0668696d8` corrected it. The next exact branch run
`33596741034` then exposed a real Bash error-unwind defect: cleanup traps referenced function-local
paths after those locals had left scope. With explicit
`APPROVE_HT12P_CLEANUP_TRAP_HARDENING`, all seven cleanup families now bind concrete targets before
use, preserve the primary exit/signal status and exercise exact mocked cleanup. Restore-rehearsal
container/volume cleanup additionally pre-arms exact names and verifies a run-owner label before use
or deletion. Neither failed CI run nor this local hardening accessed staging or created real Docker
resources.

Post-hardening branch run `33628764473` passed the sequencer through the cleanup/rehearsal fixtures,
then proved that the Compose job's depth-1 checkout omitted immutable base object
`ecb09601975678a41d89e5c824cc7812c7876481:docs/DEPLOYMENT_RUNBOOK.md`. The exact-base rejection test
therefore stopped before its intended assertion. The Compose job now fetches full history so that
the frozen baseline object is actually present and tested; no test assertion or execution boundary
was weakened.

The frozen sequencer SHA-256 is
`41f0b1d98e848e65b3bc4b605cb1f53ce28081c3e6ecf9153328d4b77486d1d4`; the frozen harness SHA-256 is
`6f6e746d3f16eb4bf0090aecec1513d23071f87046242fb99cba9754ad5a5e17`. The complete local harness
passed all 441 bounded assertions after cleanup and documentation reconciliation. This is local
fixture evidence only. Independent read-only final re-review passed with no P0, P1 or blocking P2;
exact post-fix feature-branch Actions are not yet claimed.

Normal public-pilot staging remains `PRODUCT` with maintenance `OFF`; temporary `V126_SMOKE` remains
an identity-gated migration-window overlay, never a replacement for membership or RBAC. Permanent
staging `ALLOWLIST`, stable client CIDRs and Caddy/source-IP attribution remain rejected historical
experiments. The immutable PostgreSQL/H2 migration trees, blobs and runtime behavior are unchanged.

This task authorizes local implementation and tests, independent read-only review, feature-branch
commit/push and branch Actions. It does not authorize main integration, staging or production access,
SSH, backup/rehearsal creation, image build/transfer, service mutation, maintenance activation,
Caddy reload/restart, Flyway/V126, Telegram/database writes or cutover. After local/review PASS and
an exact green feature-branch Actions run, the next gate is
`HT12P_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`. Only a later exact authorization may integrate the
branch, select the final release SHA after a new green main Actions run and repeat deterministic
two-build image proof.

The booking release line below is a preserved historical preintegration task snapshot. It is not
the current HT-12P stage, release identity or executable state/receipt status.

**BOOKING CONVERSATION UX / DISTINCT LABELS, INBOX AND UNREAD DISCOVERABILITY /
MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

This bounded slice keeps authoritative per-venue-service-day booking numbers and adds one stable
venue-local label, `Бронь №N · dd.MM.yyyy, HH:mm`, with a booking-id fallback across Guest/Venue
booking DTOs, booking conversations and Telegram notifications. Venue navigation now separates
`Брони`, `Переписки` (`BOOKING_THREAD` plus `VENUE_CHAT`) and `Поддержка` (`SUPPORT_TICKET`). Existing
actor/thread-scoped message-id cursors drive unread-first inbox ordering, exact booking-card markers
and the aggregate conversation badge. `last_read_message_id` is the sole unread authority;
`last_read_at` remains wall-clock metadata only. Every user-visible message with
`author_user_id = NULL` is a system message and counts as foreign for every actor, including exact
thread/card and aggregate Venue conversation unread. Guest exact-open routes map the fixed
server-owned `CONVERSATIONS` (`BOOKING_THREAD` + `VENUE_CHAT`) or `SUPPORT` (`SUPPORT_TICKET`)
surface into the repository transaction; locked ownership and type validation happen before the
message snapshot, marker update or detail disclosure for ordinary Guest and confirmed Platform
Guest-context calls. A committed Guest booking message can atomically
enqueue one fact-only alert to the canonical venue's already linked staff chat; missing/disabled
configuration safely skips it. Additive PostgreSQL V126/H2 V127 add the nullable cursor and `(thread_id, id)`
message index without a default, backfill, foreign key or destructive rewrite. No thread/RBAC
change, personal subscription domain, Media/R2, reminder, no-show, queue or preorder expansion is
included. Independent review, green Actions, migration rollout, staging redeploy and a fresh
Guest/Venue/Telegram smoke are still required; no staging smoke is recorded for this slice.

Fresh local evidence for the three current findings is exact, not aggregate-only:
`SupportThreadReadRepositoryTest 21/21`, `BookingConversationRoutesTest 9/9`,
`SupportTicketRoutesTest 15/15` and real-PostgreSQL
`SupportThreadReadConcurrencyPostgresTest 6/6` each report zero skipped/failures/errors; the full
structured Playwright run reports `216/216` with zero unexpected/flaky/skipped/runner errors or
failed attempts. This is local validation only, not green Actions or staging evidence.

The previous booking-conversation-integrity release remains separately review-required. Its local
implementation and test evidence covers
`BOOKING-CLIENT-ID-RELOAD-RECONCILIATION-001`,
`BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001`,
`BOOKING-MINIAPP-IDEMPOTENCY-PG-EVIDENCE-001`, `BOOKING-DOC-PREVERDICT-001`,
`BOOKING-OUTBOX-LEGACY-DEDUPE-SEMANTICS-001` and
`BOOKING-MESSAGE-MIGRATION-METADATA-001`, plus the H2 semantic-parity fix for
`BOOKING-DEDUP-AUDIT-REF-001`, inside only
`BOOKING-THREAD-UNIQUENESS-001` / `QA-BOOKING-ISOLATION-001`. The four Guest/Venue dedicated/generic
Mini App booking surfaces now reconcile every authoritative booking id through a complete bounded
read-only batch contract before exposing first-send/reply actions after recreate; absence from a
capped thread list is never evidence of no thread, and a failed/partial read remains screen-scoped
and fail-closed.
PostgreSQL V124/H2 V125 reject recursive unknown audit reference keys before domain mutation, the
deployment preflight uses the same predicate, and Mini App message metadata is asserted exactly on
both databases. Legacy outbox enqueue retains key-only replay while only the connection-aware
booking API uses strict canonical-envelope collision checks. Real PostgreSQL evidence observes the
waiting caller's exact independent PID, ungranted `pg_locks` row and
`pg_blocking_pids(waiter) = [caller A]` before release/commit. Independent review is still required;
no final-review verdict, green Actions, preflight, migration, deploy or staging smoke is recorded.
No reminder, no-show, queue, preorder or broad analytics/audit redesign is included.

`BOOKING-V124-CATALOG-INVENTORY-001` is also locally fixed: PostgreSQL V124 now anchors inbound
foreign keys on the exact current-schema `support_threads` OID and records one authoritative row per
constraint through `pg_catalog`, including exact source OID/namespace, complete ordered
`conkey`/`confkey` arrays and action/match metadata. Exact current-schema source OIDs and attnums are
required for the two known single-column references; composite, non-`id`, external same-name,
additional cross-schema and privilege-hidden cross-owner references fail closed before domain
mutation. A controlled PostgreSQL 16 catalog stress reproduced SQLSTATE `57014` at V124's
five-minute statement timeout before the fix. The strengthened production-migration wrapper passes
`47/47` with domain/catalog/index/Flyway-history snapshots, and the exact `44/44` menu mutation class
remains the bounded performance evidence. This local fix still requires a short independent review,
an explicit commit and a new green Actions run before any preflight, migration or deploy; no staging
preflight/deploy or post-failure release-DB migration has been performed.

## 2. Release closure

**PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Release HEAD `e35def99ea8429462e5fdaaeee914f57da72e775` matched `origin/main` at that recorded closure.
The user confirmed fully green GitHub Actions for that HEAD, staging deploy, the consolidated
onboarding/ownership smoke and cleanup. Local GitHub CLI authentication is invalid, so Actions are
recorded as user-confirmed rather than independently queried in this docs-only closure.

Recorded smoke outcomes:

- first applicant submitted through Telegram; an existing Owner submitted an additional venue in
  Venue Mini App;
- exact retry created no duplicate request; a different application created a separate request;
- Platform Owner saw requests, venues and owners;
- create/link produced exactly one venue and active OWNER membership;
- selected venue did not change automatically;
- the legacy quota-direct entry used the shared application flow;
- multi-owner venues and owner portfolios worked;
- a first-ever applicant account received baseline limit `1`;
- applicant, actor and source remained server-derived;
- cleanup passed.

This closes only the bounded onboarding/ownership release, not the whole Platform/Venue product or
overall production readiness.

### HT-11 V125 dry-run closure

HT-11 closed on 2026-08-27 as
`HT11_CLOSED_PASS_WITH_EXPLICIT_LIVE_LIMITATION`. Live staging evidence passed the Telegram
allowlist and MIX/CLIENT/non-MIX isolation gates, the V125 collision pair creation and Guest/Owner
visibility checks, exact `V126_BOOKING_THREAD` identity and isolation, one exact Owner open and
reply, the message transition `2/0/0 -> 2/1/0`, one terminal private Guest delivery, and the exact
Guest unread `0 -> 1 -> 0` transition after the Owner reply and Guest exact-thread open. Collision
A/B are retained as `HISTORICAL_V125_COLLISION_EVIDENCE`. No duplicate marker row appeared;
`V126_READ_BASELINE`, other threads, CLIENT and non-MIX state remained unchanged.

Exactly one V125 real-client assertion remains explicitly unexecuted:
`LIVE_V125_GUEST_REPLY_TO_OWNER_UNREAD_CLEAR = NOT_EXECUTED_OPERATIONALLY_BLOCKED`. The Guest did
not send the planned reply; consequently, the corresponding Owner unread creation and exact-thread
clear were not exercised live. This is neither failed behavior, a live pass nor a waiver.
`VenueBookingRoutesTest` and the Mini App Guest smoke provide passing automated regression coverage,
but they do not replace the mandatory post-V126 live smoke:

`AUTOMATED_REGRESSION = PASS`

`LIVE_REAL_CLIENT_V125_ASSERTION = NOT_EXECUTED_OPERATIONALLY_BLOCKED`

HT-14 must retain `HT14_MANDATORY_LIVE_GATE_GUEST_REPLY_OWNER_UNREAD_CLEAR`: one exact Guest reply,
one persisted Guest message, one expected Telegram/outbox delivery, exact-thread-only Owner unread
creation and clear, no duplicate marker row or unread resurrection, and no other-thread, CLIENT or
non-MIX mutation. HT-14 cannot conclude PASS while this gate is pending, waived or represented only
by automated evidence. No manual window or retained WebView/session remains. After HT-INC-02 is
fully integrated, the then-recorded next task was **HT-12J-R1 — Patched Caddy Retry Baseline Refresh
and Resume**. That historical direction is now superseded: HT-12J-R2 is closed as
`FAILED_SEALED_EXECUTION_RECOVERED_TO_UBUNTU_BASELINE`, HT-12K is closed as
`CURRENT_CADDY_EXACT_URL_IDENTITY_NOT_PROVABLE`, and no Caddy sidecar, patched-Caddy,
packet-observer or exact-path work is authorized.

## 3. Remaining-work audit snapshot

- Markdown surfaces scanned: `35` (`32` under `docs/**` plus this file, `README.md`, root
  `AGENTS.md`).
- Normalized raw candidate records: `198`.
- Canonical catalog items after evidence review and global deduplication: `110`; `105` remain active
  after the five locally implemented booking items below.
- Disposition: `OPEN_CONFIRMED 41`, `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED 5`, `BLOCKED_PRODUCT_DECISION 14`,
  `BLOCKED_PREREQUISITE 13`, `DEFERRED_AFTER_MVP 31`, `UNKNOWN_NEEDS_RESEARCH 6`,
  `STALE_ALREADY_IMPLEMENTED 42`, `DUPLICATE_OF_OTHER_ID 35`, `HISTORICAL_ONLY 11`.
- Historical audits remain evidence/history and do not reactivate closed work without current
  code/test evidence.

Current P2/P3 registry entries preserved as open:

- `ONBOARDING-H2-001`;
- `ONBOARDING-TG-CONFIRM-001`;
- `ONBOARDING-DECISION-RETRY-001`;
- `MENU-CONC-001`;
- `MENU-TEST-002`.

Booking review registry for this Goal:

- `BOOKING-DISPLAY-LABEL-001` — `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED`; authoritative booking
  number plus venue-local date/time and stable booking-id fallback are shared across Guest/Venue
  booking, conversation and Telegram surfaces; independent review/Actions/staging smoke remain;
- `BOOKING-INBOX-DISCOVERABILITY-001` — `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED`; Venue
  `Переписки` contains only booking conversations and venue chats with deterministic unread-first
  ordering, while `Поддержка` remains the support-ticket surface;
- `BOOKING-UNREAD-NOTIFICATION-001` — `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED`; actor-scoped
  nav/card unread uses the monotonic `last_read_message_id` cursor, and exact read clearing snapshots
  `MAX(support_messages.id)` under canonical locks. The existing transactional outbox can send one
  privacy-safe canonical-venue staff-chat alert without introducing personal subscriptions;

- `BOOKING-DEDUP-READ-001` — `DONE` after fail-closed H2/PostgreSQL lossless-read migration proof and
  live writer-first/migration-first snapshot serialization;
- `BOOKING-WRITER-CONVERGENCE-001` — `DONE` after production route/repository convergence proof;
- `BOOKING-CI-FLOOR-001` — `DONE` after exact selectors, XML minima and structured Playwright floor;
- `BOOKING-PG-EVIDENCE-001` — `DONE` after exact PostgreSQL race post-state evidence;
- `BOOKING-MINIAPP-OUTBOX-001` — `DONE`; persisted scope-bound Mini App replay identity and
  same-transaction message/thread/strict-booking outbox remain covered; the legacy enqueue contract
  is separately preserved by this bounded fix;
- `BOOKING-DEDUP-AUDIT-REF-001` — `LOCAL_FIX_REVIEW_REQUIRED`; H2 now validates and remaps the exact
  top-level integer `ticketId` with a duplicate-aware semantic JSON helper independent of key order
  and formatting. The recursive unknown-key gap is locally fixed separately under
  `BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001`; both still await independent review;
- `BOOKING-MIGRATION-SNAPSHOT-001` — `DONE`; pre-guard PostgreSQL table locks and real writer-first
  plus migration-first Flyway serialization pass the exact `2/2` concurrency gate;
- `BOOKING-READ-LOCK-ORDER-001` — `DONE`; production read-marker paths retain
  `bookings -> support_threads -> support_thread_reads` or
  `support_threads -> support_thread_reads`, with exact repository `21` and real PostgreSQL `6`
  lock/cursor/RBAC cases and no reverse runtime DML path;
- `BOOKING-SAVEPOINT-COLLISION-001` — `OPEN`; the current unique-conflict branch is defensive-only
  under the booking-row lock and requires a separate review before removal.

Findings fixed locally and still requiring independent review in this bounded fix:

- `BOOKING-UNREAD-NULL-AUTHOR-001` — `LOCAL_FIX_REVIEW_REQUIRED`; user-visible NULL-author rows use
  the same null-safe foreign-author predicate in exact/card and aggregate Venue unread, with
  authoritative exact-open clearing and no RBAC expansion;
- `BOOKING-UNREAD-GUEST-TYPE-GUARD-001` — `LOCAL_FIX_REVIEW_REQUIRED`; ordinary Guest and confirmed
  Platform Guest-context routes pass fixed server-owned `CONVERSATIONS` / `SUPPORT` contracts into
  locked ownership/type validation before markers or message facts;
- `BOOKING-UNREAD-MIXED-VERSION-ROLLOUT-001` — `LOCAL_FIX_REVIEW_REQUIRED`; V126 docs require a
  backup, full old-instance drain, exactly one new image, normal-startup migration, forward-fix
  rollback and bounded NULL-author/type-guard/isolation smoke;
- `BOOKING-UNREAD-TIMESTAMP-AUTHORITY-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-UNREAD-PG-RACE-COVERAGE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-LABEL-TS-PARITY-COVERAGE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-E2E-NOTIFIER-COVERAGE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-DOC-CURRENT-SLICE-DRIFT-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-CLIENT-ID-RELOAD-RECONCILIATION-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-MINIAPP-IDEMPOTENCY-PG-EVIDENCE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-DOC-PREVERDICT-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-OUTBOX-LEGACY-DEDUPE-SEMANTICS-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-MESSAGE-MIGRATION-METADATA-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-DEDUP-AUDIT-REF-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-CATALOG-INVENTORY-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-CONSTRAINT-SHAPE-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-SOURCE-RELATION-IDENTITY-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-PG-CI-FLOOR-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-CROSS-OWNER-DOMAIN-SNAPSHOT-001` — `LOCAL_FIX_REVIEW_REQUIRED`.

Still open by design:

- `BOOKING-CI-PLAYWRIGHT-FLAKE-001` — `OPEN`; an earlier structured run executed `197/198` after
  one unrelated favorite-test failure, and this pass first executed `205/206` after one unrelated
  catalog-debounce timing failure before the focused and second full `206/206` reruns passed. The
  discoverability run first executed `207/208` after the same unrelated catalog virtual-clock
  debounce flake; its isolated repeat and second full structured `208/208` run passed. After the
  wrong-thread-type deep-link read guard was added, the final structured run passed `209/209`
  with zero unexpected, flaky or skipped results. This cursor/label/notifier slice then passed the
  raised structured floor at `212/212`, also with zero unexpected, flaky or skipped results. The
  current Guest surface/deep-link closure raised the mandatory structured floor and passed
  `216/216`, again with zero unexpected, flaky or skipped results.
  Trigger: the next Mini App CI-hardening pass or a repeated same failure in GitHub Actions;
- `BOOKING-SAVEPOINT-COLLISION-001` — `OPEN`;
- `BOOKING-AUDIT-EVENTS-001` — `OPEN`;
- `BOOKING-REMINDER-ROLLOUT-001` — `OPEN`;
- `BOOKING-NO-SHOW-AUTOMATION-001` — `OPEN`;
- `BOOKING-QUEUE-POLISH-001` — `OPEN`;
- `BOOKING-PREORDER-001` — `OPEN`.

Media/object-storage work remains blocked by `MEDIA-STORAGE-DECISION-001`; that historical Goal did
not implement or modify Media/R2. Its then-recorded next step was independent review, explicit
commit, green Actions, the additive cursor migration, staging redeploy and bounded
Guest/Venue/Telegram conversation smoke. The current repository has since integrated the code,
HT-12M prerequisite and HT-12C policy baseline. HT-12P's only current next gate after complete
local validation, independent review, commit/push and exact green branch Actions is the explicit
main-integration authorization recorded in section 1. The previous integrity release retains its
separate historical V124/V125 preflight/migration evidence.


HT-RELEASE-REPAIR-01 feature validation continuation: explicit-file commit/non-force push
of `codex/ht-release-repair-01-rkbe7q` and existing12-job Ubuntu CI are authorized.
Baseline12-artifact and completed-run retirement contracts are being validated together;
UNKNOWN daemon reconciliation, operational deploy locking, VM reboot/DR and live manual
gates remain open. V125 and V126 handoff target policies are accepted separately, with
no live application moment authorized. See REPAIR-REPORT.md and REPAIR-CHECKPOINT.md.
No main integration, PR, deployment or new V126 attempt follows this validation.
