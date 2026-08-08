# Project Status

Last verified: 2026-08-08 at Git `822233c` (`HEAD == origin/main`; tracked worktree clean before
this docs-only handoff).

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
- **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**.
- For release HEAD `822233c`, the supplied release evidence records fully green Actions after a
  rerun of one failed backend job, staging deploy and the bounded blocked/allowed smoke passed. The
  initial job failure reason is not asserted here. This closes only menu item hard delete, not the
  whole Menu or Dangerous Action Audit program.

## 3. Recently completed blocks

- Menu item hard-delete audit: **DONE / MVP / STAGING-SMOKE-PASSED**. Existing Venue Mini App and
  Telegram management callers pass
  authenticated actor plus server-owned source to one required repository contract. Promotion
  reference snapshot/recheck, current parent/rule/item lock order, affected rule version bumps and
  cascades, item delete and exactly one `MENU_ITEM_DELETED` share one JDBC transaction. The payload
  contains only venue/item/category ids, source and a bounded deterministic affected-rule summary
  (exact unique count, first 50 sorted ids, omitted count and SHA-256 of the complete sorted set).
  Audit/SQL/reference failure rolls all state back. No migration was added. An authoritative
  pre-write fixed-reward dependency returns safe HTTP 409 and leaves item, reward, versions,
  statuses, timestamps and audit unchanged. Purchase targets and CHOICE allowlist items retain the
  current cleanup/version contract; remaining CHOICE options stay configured, the last option
  removes the incomplete reward configuration, lifecycle status is not rewritten and a fixed
  reward is never replaced automatically. Mini App confirmation explains these consequences and
  Mini App/Telegram map the blocked result without false success.
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

Verdict: **IMPLEMENT_MENU_CATEGORY_DELETE_AUDIT_NEXT**.

Runtime evidence: `VenueMenuRepository.deleteCategory` is the only production SQL writer and is
called by the existing Venue Mini App route and authenticated Telegram management callback. It
already refuses non-empty categories, snapshots authoritative category promotion references,
locks promotion parents then rules then category, rechecks emptiness/references, bumps affected
rule versions and deletes in one JDBC transaction. It has no actor/source parameter and no audit.

Minimal outcome: require server-derived actor plus `VENUE_MINI_APP` / `TELEGRAM_BOT`, and append
exactly one bounded privacy-safe `MENU_CATEGORY_DELETED` audit inside that existing transaction for
one committed empty-category delete. Audit failure must roll back reference cleanup, versions and
category deletion. Non-empty, missing/repeated, denied, reference/concurrency and SQL failure write
zero success audit. Existing responses, confirmation, RBAC, empty-category-only policy, promotion
lifecycle and Guest/order snapshots stay unchanged. Schema verdict: `NO_MIGRATION_EXPECTED`.

Out of scope: cascading deletion of non-empty categories, item/option delete audit, item price/name/
type/update or availability audit, promotion configuration/compatibility changes, order/session
redesign, media/R2 and audit viewer.

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
- Menu option delete, item price/name/type/update, availability beyond Shift Check, fuller item-delete
  rollback snapshot coverage, Telegram negative-case hardening, concurrent membership revoke
  linearization, dependency viewer and automatic fixed-reward replacement remain separate work.
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
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*PromotionConfigurationConcurrencyPostgresTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
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
3. Verify `HEAD`, worktree and every category-delete production caller/SQL writer against current
   code.
4. Preserve the existing promotion parent/rule/category lock order, empty-category-only contract,
   response envelopes and item hard-delete regression while adding category audit atomically.
5. Do not touch stash or `scripts/dev/`; do not stage, commit, push or deploy without instruction.
6. Update this handoff only if stage, blockers or next bounded block changes.

## 11. Last verified date

2026-08-08. Menu item hard-delete audit is release-closed for its bounded MVP after the recorded
green Actions, staging deploy and passed smoke. The next bounded block is category-delete audit;
the broader Menu and Dangerous Action Audit programs remain partial.
