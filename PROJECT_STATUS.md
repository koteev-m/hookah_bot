# Project Status

Last verified: 2026-08-12. Current release HEAD `db08916db099738f7979625ed67ec1bc19934009`
equals `origin/main`; the pre-docs Git check was clean apart from the pre-existing untracked
`scripts/dev/` directory.

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

## 4. Next bounded block

Contract: **IMPLEMENT_MENU_OPTION_CREATE_AUDIT_NEXT**.

The unaudited physical `createOption` paths are the Mini App create route, Telegram individual create
and base-profile creation. The current base-profile bulk helper invokes one create transaction per
profile, while normalization already performs missing-profile inserts in one transaction. The next
slice must make the required audit atomic and preserve one audit per physical created row; it must not
expand into option rename/price/availability, item mutations, Shift Check or a new domain engine.
Schema verdict: **NO_MIGRATION_EXPECTED**.

## 5. Open gaps and risks

- Keep separate: menu option create audit (next), menu item price audit, menu item metadata/name/type
  audit, transaction-bound membership-revoke linearization, audit/dependency viewer, promotion
  configuration edit audit and force-close/session audit.
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

- Release Git evidence: current HEAD and `origin/main` are both `db08916`; the five most recent
  commits identify this HEAD as `Add menu item availability audit`. `git diff --check` passed before
  docs changes.
- GitHub CLI could not query Actions because its active token is invalid. The user confirmed fully
  green GitHub Actions for this current release HEAD; this is user-confirmed, not independently
  verified by this handoff.
- The user also confirmed the staging deploy and only this bounded staging smoke: (1) Owner toggles a
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

Implement only `IMPLEMENT_MENU_OPTION_CREATE_AUDIT_NEXT`: inventory and route all physical option
creates through an actor/source-aware, transaction-bound audit contract; make base-profile bulk
creation atomic before adding per-row audit evidence. Do not expand into price, metadata, item,
cart, promotion, media/R2, stash or `scripts/dev/` work.

## 10. Last verified date

2026-08-12. The Menu Item Availability Audit is release-closed only for its bounded contract; it does
not close all item mutations, the Menu Audit, the Dangerous Action Audit or overall product readiness.
Stash was not read, applied, changed, deleted or renamed; `scripts/dev/` was not touched.
