# Project Status

Last verified: 2026-08-12. Current release HEAD
`0e592ffddfe5114cd027a600823e6da5f8c6d5f7` equals `origin/main`. The user confirmed fully green
GitHub Actions, staging deploy and the bounded Menu Option Create Audit smoke. Local GitHub CLI
cannot independently verify Actions because its active token is invalid. The worktree has only the
pre-existing untracked `scripts/dev/` directory; stash was not read or changed and `scripts/dev/`
was not touched.

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
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
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
- Menu option create audit is release-closed for six authenticated flows: Mini App direct and
  add-missing-base-profiles; Telegram canonical direct, custom dialog, add-missing-base-profiles and
  normalization. The single private repository SQL writer commits each physical option insert with
  exactly one private `MENU_OPTION_CREATED`; no authenticated internal/system/legacy writer exists.

## 4. Current bounded block

Contract: **IMPLEMENT_MENU_ITEM_CREATE_AUDIT_NEXT**.

Read-only runtime inventory finds one unaudited physical `INSERT INTO menu_items` writer,
`VenueMenuRepository.createItem`, with exactly two authenticated production callers: Venue Mini App
`POST /menu/items` and the Telegram Owner/Manager add-item dialog. Both are own-venue
Owner/Manager paths; legacy `ADMIN` remains Manager-compatible, while Staff, foreign,
unaffiliated and Platform-only actors lack venue authority. Item creation currently inserts one item
with its initial category/name/price/currency/availability/type/sort state and creates no options.

The bounded outcome is a same-transaction, exactly-one private item-create audit for a committed
physical item only, with server-derived actor/source, authoritative venue/category scope and a
privacy-safe payload. Item price, name/type/category/description/currency update audits, option
creation, promotion/order behavior, category seeding and membership-revoke linearization are
outside this next slice. Expected schema verdict: **NO_MIGRATION_EXPECTED**, subject to
implementation verification.

The implementation must add focused repository, route/security, Telegram and Mini App coverage;
use a deterministic PostgreSQL contention test if the final repository transaction needs serialized
category/sort allocation. Release remains gated by focused local validation, green Actions, staging
deploy and bounded cross-surface smoke.

## 5. Open gaps and risks

- Keep separate: menu item price audit; item name/type/category/description/currency audit families;
  menu category create audit; transaction-bound membership-revoke linearization; audit/dependency
  viewer; promotion configuration edit audit; and force-close/session audit.
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

- Current release HEAD and `origin/main` are both `0e592ff`. The user confirmed fully green GitHub
  Actions, staging deploy and bounded smoke for the option-create slice; the invalid local GitHub
  CLI token prevents independent run verification. No migration was added.
- Confirmed staging smoke only: Owner Mini App direct create produced one
  `MENU_OPTION_CREATED` with `VENUE_MINI_APP`; Manager Telegram direct create produced one with
  `TELEGRAM_BOT`; Staff was denied. Bulk added only missing base profiles, preserved custom/current
  canonical profiles plus price/availability, and wrote an equal number of create audits; a repeat
  bulk wrote zero rows/audits. Normalization restored one missing profile with one create audit and
  then repeated as a zero-row/zero-audit no-op. Audit payload contained no names, prices,
  availability or PII; the working Guest menu was intact and cleanup completed normally.
- Automated/local/CI contract evidence, not staging smoke: repository `41`, routes `37`, Telegram
  `538`, route/security `1137`, PostgreSQL concurrency `26` and Mini App E2E `169`; direct, bulk and
  normalization rollback, canonical uniqueness and deterministic locking are covered. No migration.

## 8. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- QA/staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## 9. Next action

Implement only `IMPLEMENT_MENU_ITEM_CREATE_AUDIT_NEXT`: first inventory all item-create writers,
then make the two authenticated paths and one physical insert transaction-bound with an exactly-one
privacy-safe audit. Do not expand into item price/name/type/category/description/currency updates,
options, cart, promotions, media/R2, stash or `scripts/dev/` work.

## 10. Last verified date

2026-08-12. **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED** closes only its bounded contract. It does not close the Menu Audit, the
Dangerous Action Audit, all option mutations or overall product readiness. No migration was added.
Stash was not read, applied, changed, deleted or renamed; `scripts/dev/` was not touched.
