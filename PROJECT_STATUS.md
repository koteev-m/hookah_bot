# Project Status

Last verified: 2026-08-12. The pre-implementation Git baseline HEAD
`8ec4280830b9734ff04bcd23efd6631944f4154d` equalled `origin/main`. The current worktree contains
the bounded implementation under review plus the pre-existing untracked `scripts/dev/` directory;
stash was not read or changed and `scripts/dev/` was not touched.

## 1. Purpose and source-of-truth order

If this handoff disagrees with current code, Git, migrations, tests or canonical docs, those sources
win. This is a compact current handoff, not a historical changelog. Replace stale state rather than
append logs. Update after a staging-smoke closure, material architecture/data-contract decision,
next-block change, P0/P1 blocker change or before a new long task.

## 2. Current product/release state

- Overall product, permission parity and the broader Menu/Dangerous Action Audit remain `PARTIAL`.
- **GUEST CART STALE MENU SELECTION RECOVERY / REMOVED OR UNAVAILABLE ITEMS AND OPTIONS /
  PAYLOAD-BOUND IDEMPOTENCY + ATOMIC REJECTION / DONE / MVP / STAGING-SMOKE-PASSED**.
- **GUEST CART STALE MENU SELECTION RECOVERY / ITEM-LEVEL ACTION AND COPY POLISH / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / MVP IMPLEMENTED /
  LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. This is not staging-closed.
- Menu option price audit and Venue Menu management UX stabilization remain separately
  `DONE / MVP / STAGING-SMOKE-PASSED` for their bounded contracts.

## 3. Recently completed blocks

- General stale-menu cart recovery: preview and submit share authoritative validation for removed or
  unavailable items/options; own-cart failures use all typed issues, exact `409` handling and
  zero-write rejection. Preview is read-only; final submit is atomic. Current contracts preserve
  preview/submit parity, payload-bound idempotency, exact replay, mismatch conflict, reconstructable
  versus unverifiable legacy handling, no automatic resend, no false success and immutable historical
  order snapshots. A mismatch or unverifiable replay preserves the cart for an explicit user action.
- Item-level action/copy polish: `ITEM / UNAVAILABLE` and `ITEM / REMOVED` explain mandatory line
  removal. `Удалить и выбрать другую` removes only the affected line, keeps other lines, recalculates
  before opening Guest menu, selects no replacement and submits no order. `Удалить из корзины` stays
  in cart after exact-line removal and recalculation. Multiple issues remain independent; option
  recovery, including quantity/preference-note preservation, is unchanged.
- Menu item availability audit: direct Mini App and Telegram changes plus an availability delta in
  Owner/Manager compound item PATCH write exactly one transaction-bound
  `MENU_ITEM_AVAILABILITY_CHANGED` with server-derived actor/source. Same-state requests write no
  audit and preserve `updated_at`; Staff keeps direct availability only. Shift Check remains
  aggregate-only, and an audit failure rolls back the item mutation, timestamp and audit row.
- Menu option create audit is implemented locally for six authenticated flows: Mini App direct and
  add-missing-base-profiles; Telegram canonical direct, custom dialog, add-missing-base-profiles and
  normalization. One private repository SQL writer serves them all; no internal/system/legacy
  production create writer exists. Review, CI and staging evidence are still required.

## 4. Current bounded block

Contract: **IMPLEMENT_MENU_OPTION_CREATE_AUDIT_NEXT**.

`VenueMenuRepository.insertOption` is the only production `INSERT INTO menu_item_options` writer.
Direct `createOption`, atomic `applyMissingBaseProfiles` and atomic
`normalizeHookahFlavorProfiles` own their JDBC transactions; there is no unaudited compatibility
overload. Direct create locks the DB-authoritative item and option rows, rechecks a canonical
collision, inserts, appends audit on the same connection, rereads and commits once. One bulk user
operation computes its DB-current missing-profile plan and commits N inserts plus N audits once.
Normalization preserves its delete contract and commits deterministic deletes, missing creates,
delete audits and create audits together.

Every physically committed option writes exactly one `MENU_OPTION_CREATED` for entity
`menu_item_option` / option id. Payload keys are exactly `venueId`, `itemId`, `optionId`, `source`;
actor is the authenticated Mini App session subject or current authenticated Telegram user, and
source is server-owned `VENUE_MINI_APP` or `TELEGRAM_BOT`. Denial, foreign/not-found, collision,
duplicate/no-op, SQL/audit failure, rollback and concurrent loser write zero create audits. Payload
and logs exclude option/profile content, price/availability, raw request/initData/callback/update,
Telegram identity, promotion/cart/order/media data, secrets and unrelated PII.

Local bounded evidence is repository `41/0/0/0`, routes `37/0/0/0`, Telegram `538/0/0/0` and
real-PostgreSQL menu concurrency `26/0/0/0` (`tests/skipped/failures/errors`); `compileKotlin`,
`ktlintCheck`, Mini App production build and full Playwright `169/169` passed. Schema verdict:
**NO_MIGRATION_EXPECTED**.

## 5. Open gaps and risks

- Keep separate: remaining option-create review/CI/staging gates, menu item price audit, menu item
  metadata/name/type audit, transaction-bound membership-revoke linearization, audit/dependency
  viewer, promotion configuration edit audit and force-close/session audit.
- Keep separate cart hardening: duplicate-name cart E2E, opaque cart-line identity, option
  replacement merge semantics, issue owner tuple/generation, immutable cart item-name snapshot,
  live-region deduplication and error-response size hardening.
- Keep separate reliability work: analytics/outbox failure checkpoint and post-commit notifier failure
  test.
- Non-blocking Menu Availability hardening: (1) assert raw `updated_at` before/after an injected
  audit failure; (2) hold the item lock while a real PostgreSQL Guest submit reaches a confirmed
  `pg_blocking_pids` / `pg_locks` wait, then assert an allowed serial outcome. These are future
  verification, not runtime defects.
- Notification Consent needs persisted operational/marketing scopes, evidence/version/source and
  opt-out. Promotion Compatibility Policy has high financial/product-policy risk.
- Media/R2 foundation remains `STOP_FOR_MEDIA_STORAGE_DECISION`; stash is not part of this handoff.
- Deferred canonical manual smoke remains `REPEAT-MANUAL-001` P1 environment-blocked,
  `CATALOG-SEARCH-MANUAL-001` P2 and `STAFF-IDENTITY-MANUAL-001` P2.

## 6. Important architecture/data constraints

- Venue Mode is the operational source of truth; staff-chat is notification/radar/shortcut only.
- Server-side RBAC is authoritative; UI hiding and QR/table/tab tokens are not authority.
- Preserve `table_session`, `order_batch`, `tab`, tenant and current-user boundaries.
- Structured menu is the order source of truth; historical order/bill snapshots survive menu edits.
- Audit actor/source are server-derived. Payloads exclude secrets, raw Telegram/initData, provider
  data, guest text and unrelated PII.
- Do not add a second promotion engine or define cross-promotion compatibility implicitly.

## 7. Release and staging evidence

- Git baseline evidence: before this bounded implementation, current HEAD and `origin/main` were
  both `8ec4280`. No commit, push, deploy or staging smoke is claimed for the option-create slice.
- GitHub CLI previously could not query Actions because its active token was invalid. The earlier
  user-confirmed green Actions evidence belongs to the release-closed item-availability slice, not
  this option-create implementation.
- The earlier user-confirmed staging evidence covers only this item-availability smoke: (1) Owner toggles a
  test item off/on; (2) each real toggle creates truthful item-availability audit; (3) Staff changes
  availability only; (4) Staff cannot compound-edit name, price or type; (5) Telegram toggle works;
  (6) its audit source is `TELEGRAM_BOT`; (7) Shift Check changes availability by its own contract;
  (8) Shift Check writes one aggregate audit; (9) it writes no per-item availability audits; (10) a
  Guest cannot order an unavailable item; (11) re-enable restores ordering; (12) working menu and
  Guest cart remain intact; (13) cleanup completed normally.
- No failure-injection, raw-SQL or other unconfirmed staging scenario is recorded. No migration was
  added by the completed slice.

## 8. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- QA/staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## 9. Next action

Independently review `IMPLEMENT_MENU_OPTION_CREATE_AUDIT_NEXT`, verify the exact CI selectors/XML
including the menu PostgreSQL minimum of 26, then require green Actions, staging deploy and bounded
cross-surface smoke before release closure. Do not
expand into price, metadata, item, cart, promotion, media/R2, stash or `scripts/dev/` work.

## 10. Last verified date

2026-08-12. Menu Option Create Audit is locally implemented only for its bounded contract; it does
not close the Menu Audit, the Dangerous Action Audit or overall product readiness, and it has no
staging evidence yet. No migration was added. Stash was not read, applied, changed, deleted or
renamed; `scripts/dev/` was not touched.
