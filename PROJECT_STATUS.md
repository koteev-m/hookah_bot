# Project Status

Last verified: 2026-08-07 at Git `9732f8a` (`HEAD == origin/main`).

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
- **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**.
- The two bounded staging-smoke results above were supplied for this 2026-08-07 handoff. Local
  `HEAD` equals `origin/main`; current-HEAD GitHub Actions could not be re-queried because the local
  `gh` token is invalid, so this file makes no new green-Actions claim.
- This handoff sync is docs-only and needs no staging deploy.

## 3. Recently completed blocks

- Promotion creation audit: action `VENUE_PROMOTION_CREATED`, entity `venue_promotion`, actor
  server-derived, source `VENUE_MINI_APP` or `TELEGRAM_BOT`. Parent, caller-connection initial rule
  and audit commit in one transaction; one committed parent produces exactly one creation audit.
  Informational creation has `rules=[]`; Mini App Happy Hours/Gift records the actually created
  initial rule; Telegram Happy Hours/Gift parent-draft creation has `rules=[]`. Banner media is
  persisted separately and is absent from the creation payload. Denial, validation, `afterInsert`,
  SQL and audit failure produce no success audit; audit failure rolls back parent and initial
  rules. Payload excludes promotion text/config/prices/media, raw Telegram data and unrelated PII.
- Promotion effective-state clarity: DB lifecycle status is never rewritten automatically. The UI
  derives `DRAFT`, `PAUSED`, `ARCHIVED`, `SCHEDULED`, `ACTIVE_NOW`, `EXPIRED`; manual
  `DRAFT`/`PAUSED`/`ARCHIVED` wins over time. `ACTIVE` before start is `Запланирована`, inside the
  inclusive period is `Действует сейчас`, and after end is `Период завершён`. Expired remains in
  `Текущие`; Guest cannot see it and pricing cannot apply it. `Продлить период` reuses update plus
  authoritative reload. No automatic pause/archive, worker, system actor or lifecycle audit exists.
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

## 4. Current next bounded block

Verdict: **IMPLEMENT_MENU_DANGEROUS_ACTION_AUDIT_SLICE_NEXT**.

Bound it to the existing Venue Mini App menu-item delete path only. Current runtime performs a hard
`DELETE` in `VenueMenuRepository.deleteItem`, already inside the transaction that locks related
promotion-rule references and bumps affected rule versions, but it accepts no actor/source and
writes no audit. The route derives the authenticated user and checks `MENU_MANAGE`; base success and
Staff-denial route tests exist. Add one required transactional `MENU_ITEM_DELETED` audit with entity
`menu_item`, entity id `itemId`, server-derived actor and `source=VENUE_MINI_APP`. Safe payload:
`venueId`, `itemId`, `categoryId`, source and sorted affected promotion rule ids; no item name,
price, option names, media, Telegram payload or PII.

One committed delete must produce one audit. Not-found, denial, failed reference/concurrency checks,
SQL failure and audit failure must produce no success audit; audit failure must roll back deletion
and promotion-rule version bumps. Repeated delete remains the existing not-found response. Schema
verdict: `NO_MIGRATION_EXPECTED` because `audit_log` and transactional writer already exist.

Out of scope: item archive/schema redesign, category/option delete, price/name/type/update audit,
availability/Shift Check changes, Telegram menu management, promotion engine behavior, Guest order
snapshot redesign, media and audit viewer.

Likely files: `VenueMenuRepository.kt`, `VenueMenuRoutes.kt`, `VenueMenuRepositoryTest.kt`,
`VenueMenuRoutesTest.kt`, `VenuePromotionRepositoryTest.kt` and, for the existing lock-order race,
`PromotionConfigurationConcurrencyPostgresTest.kt`, plus the smallest current docs surfaces.
Preserve current API success and safe error envelopes, tenant/RBAC boundaries, promotion reference
lock order and old bill/History snapshots.

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

Selected runtime block, when implemented:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRepositoryTest*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*PromotionConfigurationConcurrencyPostgresTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
```

Then use the canonical QA matrix for Mini App regression, green Actions, staging deploy and bounded
Owner/Manager/Staff/foreign/audit/privacy smoke.

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
3. Verify `HEAD`, worktree and the item-delete call sites/tests against current code.
4. Implement only the item-delete audit slice above, with tests before broader validation.
5. Do not touch stash or `scripts/dev/`; do not stage, commit, push or deploy without instruction.
6. Update this handoff only if stage, blockers or next bounded block changes.

## 11. Last verified date

2026-08-07. Git checks were read-only; runtime tests were not run for this docs-only handoff.
