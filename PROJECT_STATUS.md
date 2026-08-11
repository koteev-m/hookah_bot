# Project Status

Last verified: 2026-08-11. Current release `bd5395b` equals `origin/main`.

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
- Menu option availability audit: direct Mini App and Telegram mutations are audit-aware with
  server-derived actor/source and exactly one audit for a committed individual delta. No-op writes no
  audit; Staff retains direct availability authority only, while compound PATCH remains Owner/Manager.
  Shift Check retains one aggregate `MENU_SHIFT_CHECK_COMPLETED` and no per-option availability
  audits. Audit failure rolls back the mutation.

## 4. Selected next bounded block

Verdict: **IMPLEMENT_MENU_ITEM_AVAILABILITY_AUDIT_NEXT**.

Read-only runtime audit found unaudited item-availability writes in the direct and compound Venue
Mini App paths plus Telegram Owner/Manager/Staff stop-list callbacks. Item availability has an
existing Guest stale-cart rejection contract, so the next slice can add safe mutation evidence without
a new domain engine. It must keep Shift Check as its current aggregate-only audit path and leave price
and non-price metadata untouched.

## 5. Open gaps and risks

- Keep separate: duplicate-item-name E2E hardening, opaque cart-line identity, option replacement
  merge semantics, issue owner tuple/generation, immutable item-name cart snapshot, live-region
  deduplication, error-response size hardening, analytics/outbox failure checkpoint and post-commit
  notifier failure test.
- Keep separate audit work: menu option create, item price, item update/rename, transaction-bound
  membership-revoke linearization, audit/dependency viewer, promotion configuration edit and
  force-close/session audit.
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

The user confirmed fully green GitHub Actions for current release `bd5395b`, staging deploy and the
bounded staging smoke. GitHub CLI has an invalid local token, so no CLI Actions result is recorded.
The confirmed smoke covers only: clear `ITEM / UNAVAILABLE` and `ITEM / REMOVED` reasons; exact-line
`Удалить и выбрать другую`; preservation of other lines; recalculation before Menu navigation; no
automatic replacement or order submission; in-cart `Удалить из корзины`; independent multiple issues;
no regression of `OPTION / REMOVED` or `OPTION / UNAVAILABLE`; intact working Guest cart/menu data;
and routine cleanup. No independent claim is made here about migration `V123` or single-backend
topology verification.

## 8. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- QA/staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## 9. Next action

Implement only the selected menu-item availability audit slice: direct and compound Mini App plus
Telegram writers, server-derived actor/source, one audit per real committed delta, no-op zero audit,
atomic rollback, Staff direct-availability authority and Shift Check aggregate-only preservation.
Require focused backend, Telegram, Mini App and PostgreSQL contention coverage, then green CI,
staging deploy and a bounded smoke before any release closure. Do not include item price,
rename/metadata, option create, cart-hardening, media/R2, stash or `scripts/dev/`.

## 10. Last verified date

2026-08-11. The three listed release blocks are staging-smoke-passed only for their stated contracts.
Stash was not read, applied, changed, deleted or renamed; `scripts/dev/` was not touched.
