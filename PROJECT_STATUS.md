# Project Status

Last verified: 2026-08-09 at release HEAD `03ae0af` (`HEAD == origin/main`; this docs-only handoff is
uncommitted). **DANGEROUS ACTION AUDIT SLICE / MENU OPTION HARD DELETE AUDIT / ATOMIC BASE-PROFILE
NORMALIZATION INCLUDED / DONE / MVP / STAGING-SMOKE-PASSED**.

## 1. Purpose and source-of-truth order

Если PROJECT_STATUS.md расходится с актуальным кодом, Git, migrations,
tests или canonical docs, приоритет имеют код/Git/tests/canonical docs.

This is a compact current handoff, not a canonical product spec or historical changelog. Replace
stale state instead of appending full history. Update it after `STAGING-SMOKE-PASSED`, a material
architecture/data-contract decision, a change of next bounded block, before a new long Codex task,
or when a P0/P1 blocker opens or closes. Do not update it for each small test-only fix, CI rerun,
network-only GitHub failure, formatting-only commit or temporary local log.

## 2. Current product/release state

- Overall product, permission parity and dangerous-action audit remain `PARTIAL`; the whole product
  is not declared production-ready.
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION HARD DELETE AUDIT / ATOMIC BASE-PROFILE
  NORMALIZATION INCLUDED / DONE / MVP / STAGING-SMOKE-PASSED**.
- **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **DANGEROUS ACTION AUDIT SLICE / MENU CATEGORY HARD DELETE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**.
- For release HEAD `03ae0af`, the user-confirmed evidence records fully green Actions, staging
  deploy and the bounded 17-scenario option-delete/normalization smoke passed. This closes only the
  bounded option hard-delete contract. Earlier category/item release evidence remains unchanged;
  no unverified CI failure cause is asserted.

## 3. Recently completed blocks

- Menu item hard-delete audit: **DONE / MVP / STAGING-SMOKE-PASSED**. Mini App/Telegram use one
  actor/source-bearing transaction for promotion reference recheck/cleanup, item delete and exactly
  one bounded `MENU_ITEM_DELETED`. Fixed rewards block safely; failure rolls every write back.
- Menu category hard-delete audit: **DONE / MVP / STAGING-SMOKE-PASSED**. Empty-only scope/recheck,
  promotion target cleanup/version bumps, delete and one bounded `MENU_CATEGORY_DELETED` are
  atomic; failed/no-op paths write none. No migration was added.
- Menu option hard-delete audit with atomic base-profile normalization: **DONE / MVP /
  STAGING-SMOKE-PASSED**. Mini App and Telegram direct delete share the audit-aware repository
  contract. One Telegram callback normalizes one item in one transaction; N physical deletes write
  N `MENU_OPTION_DELETED` rows, N=0 writes none, and delete/create/audit failure rolls back all.
  Canonical set/selection semantics are unchanged; custom/current canonical options and existing
  price/availability are preserved. Historical name/price snapshots survive; deleted stale
  selections create no new order. Release HEAD `03ae0af` passed the confirmed 17-scenario smoke.
- Promotion creation audit: `DONE / MVP / STAGING-SMOKE-PASSED`; parent, caller-connection initial
  rules and one safe `VENUE_PROMOTION_CREATED` commit or roll back together.
- Promotion effective-state clarity: `DONE / MVP / STAGING-SMOKE-PASSED`; presentation is derived
  from lifecycle/period without automatic status rewrites.
- Promotion lifecycle status/archive audit: `DONE / MVP / STAGING-SMOKE-PASSED`; it does not close
  configuration edit or broader dangerous-action audit.
- Staff role/removal audit: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Venue Promotions Current/Archived Tabs UX: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Happy Hours Percent: `DONE / STAGING-SMOKE-PASSED`; informational promotions Phase 1 remains
  `DONE / MVP / STAGING-SMOKE-PASSED`.

Still future for creation/effective-state work: config edits; schedule/target/reward audit;
media/banner audit; Banner retry duplicate-draft UX; broader dangerous-action audit; live
auto-refresh at time boundaries; invalid-timestamp fail-safe UI; exact-boundary regression fixtures;
and the duplicate `Продлить период` / `Редактировать` product decision.

## 4. Selected next bounded block

Verdict: **IMPLEMENT_MENU_OPTION_RENAME_AUDIT_NEXT**.

Runtime evidence: `VenueMenuRepository.updateOption` is the sole option-name SQL writer. Venue Mini
App compound PATCH and Telegram rename dialog are the two production callers. The repository
already uses one transaction with item then ascending-option locks, DB-current target reread and
hookah canonical collision recheck. Existing PostgreSQL coverage serializes canonical rename with
normalization. Historical orders store immutable option name/price snapshots; a future submit with
the same live option id resolves current DB values.

Bounded outcome: a real committed name change writes exactly one `MENU_OPTION_RENAMED` for
`menu_item_option` / option id. Actor is the authenticated Mini App session user or current Telegram
message user; source is server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`. Payload is exactly
`venueId`, `itemId`, `optionId`, `oldName`, `newName`, `source`. Item/options locks, update and audit
share one transaction; audit failure restores every co-submitted row field. Exact-name no-op,
missing/repeated, denial/foreign, collision, SQL/audit failure and rollback write zero rename audit.

Preserve the compound Mini App request: name+price+availability may commit together, but this slice
audits only a real rename. Price/availability-only updates write no rename audit. Telegram actor
must not come from dialog-state fallback. Product roles, UI/responses, canonical set/normalization,
historical snapshots and new-order current-value resolution stay unchanged.

Schema verdict: **NO_MIGRATION_EXPECTED**. Likely runtime files are `VenueMenuRepository.kt`,
`VenueMenuRoutes.kt` and `TelegramBotRouter.kt`; likely tests are repository/routes/Telegram plus
the existing normalization PostgreSQL suite and focused order/history regression. Full outcome,
out-of-scope, tests, validation, release gates and the ready prompt are in
`docs/UPDATED_PRODUCT_AI_ROADMAP.md` section 12.

## 5. Open blockers and non-blocking risks

- P1: Gift With Item parity remains locally validated but still carries its recorded independent
  review/CI/staging release gate in canonical docs.
- P1 deferred: `REPEAT-MANUAL-001` remains environment-blocked without blocking independent work.
- P2: exact promotion period boundaries, invalid timestamps, open-screen live refresh and duplicate
  extension/edit actions remain hardening work.
- Promotion compatibility needs an explicit financial product decision; notification consent needs
  a persisted operational/marketing scope, opt-out, evidence/version/source contract before sends.
- Force-close/session reason/audit is a real gap, but there is no bounded staff force-close runtime
  path yet; Guest obligations and shared physical-session semantics need a separate design first.
- P2: route authorization still precedes the repository transaction, so a membership revoke racing
  an already-authorized mutation is not transaction-linearized. This slice adds no new privilege
  path; transaction-bound membership recheck remains separate hardening.
- Option create/price/availability and item price/update audit, fuller item-delete rollback snapshot
  coverage, dependency viewer and automatic fixed-reward replacement remain separate work. Option
  rename is only the selected next block, not yet implemented.
- Media stash was neither read nor applied during this handoff.

## 6. Important architecture/data constraints

- Venue Mode is operational source of truth; staff-chat is radar/shortcut only.
- Server-side RBAC is authoritative; tokens and UI hiding are not authority.
- Preserve `table_session`, `order_batch`, `tab`, current-user and tenant boundaries.
- Structured menu is order source of truth; historical order/bill snapshots must survive menu edits
  and deletes.
- Audit actor/source must be server-derived; safe payloads exclude secrets, raw Telegram/initData,
  provider data, guest text and unrelated PII.
- Do not add a second promotion engine or implicitly define cross-promotion compatibility.

## 7. Deferred manual smoke identifiers

- `REPEAT-MANUAL-001` — P1, `BLOCKED_BY_ENVIRONMENT`.
- `CATALOG-SEARCH-MANUAL-001` — P2, non-blocking before broader catalog expansion.
- `STAFF-IDENTITY-MANUAL-001` — P2, non-blocking free-account scenario.

## 8. Minimal verification commands

Docs-only handoff:

```bash
git diff --check
git status --short
git diff --stat
git diff --name-only
git diff --cached --name-only
```

Selected next block implementation validation (not run in this docs-only handoff):

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestOrderRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVisitRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*GuestTableContextActivationPostgresTest*' \
  --tests '*PromotionConfigurationConcurrencyPostgresTest*' \
  --tests '*VenueStaffMutationConcurrencyPostgresTest*' \
  --tests 'com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOptionNormalizationConcurrencyPostgresTest' \
  --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Then use the canonical QA matrix for green Actions, staging deploy and bounded Mini App/Telegram
rename RBAC/audit/privacy/concurrency/history smoke.

## 9. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Growth/promotions: `docs/GROWTH_RETENTION.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- Venue operations: `docs/VENUE_OPERATIONS.md`.
- QA and staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.
- Manual smoke checklist: `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`.
- Deferred manual work: `docs/DEFERRED_MANUAL_SMOKE_BACKLOG.md`.
- Release operations: `docs/DEPLOYMENT_RUNBOOK.md`.

## 10. Start-of-next-task instructions

1. Check the active Goal/objective and stop on any mismatch required by the new task.
2. Read this file, then `PRODUCT_SPEC`, `MENU_OPTIONS_STOPLIST`, `SECURITY_RBAC_MATRIX` and the
   relevant QA section; load historical audits only if evidence requires them.
3. Implement only `IMPLEMENT_MENU_OPTION_RENAME_AUDIT_NEXT` from roadmap section 12; verify the two
   production name writers and preserve the current compound Mini App request.
4. Preserve direct-delete/normalization, order snapshots, RBAC, item-then-option lock order and
   payload privacy; do not broaden into option price/create/availability audit.
5. Do not touch stash or `scripts/dev/`; do not stage, commit, push or deploy without instruction.
6. Update this handoff only if stage, blockers or next bounded block changes.

## 11. Last verified date

2026-08-09. Menu item, empty-category and option hard-delete audits remain release-closed for their
bounded contracts. Option delete includes atomic Telegram base-profile normalization and release
HEAD `03ae0af` passed the confirmed gates. Next verdict is
`IMPLEMENT_MENU_OPTION_RENAME_AUDIT_NEXT`; the broader Menu and Dangerous Action Audit programs
remain partial.
