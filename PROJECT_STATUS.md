# Project Status

Last verified: 2026-08-11 at base `HEAD c39c854` (`HEAD == origin/main`) with an uncommitted bounded
implementation. **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT / MVP IMPLEMENTED /
LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

## 1. Purpose and source-of-truth order

If this handoff disagrees with current code, Git, migrations, tests or canonical docs, those sources
win. This is a compact current handoff, not a historical changelog. Replace stale state rather than
append logs. Update after a staging-smoke closure, material architecture/data-contract decision,
next-block change, P0/P1 blocker change or before a new long task.

## 2. Current product/release state

- Overall product, permission parity and dangerous-action audit remain `PARTIAL`; no whole product,
  Menu module or UX production-readiness claim is made.
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT / MVP IMPLEMENTED / LOCAL
  VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. This closes no release gate before independent
  review, green Actions, staging deploy and bounded smoke.
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. This closes only the authenticated Venue Mini App existing-option price
  mutation and its documented money/order/audit contract.
- Current release HEAD `0489a2f` equals `origin/main`. The user confirmed fully green GitHub Actions,
  staging deploy and bounded staging smoke. `gh` is installed but its active token is invalid, so this
  handoff does not independently query Actions or infer a cause for any historical failure.
- **VENUE MENU MANAGEMENT UX STABILIZATION / MOBILE RESPONSIVENESS + PRICE INPUT ERGONOMICS +
  CONTEXT PRESERVATION / DONE / MVP / STAGING-SMOKE-PASSED** remains closed only for that bounded UX
  contract.

## 3. Recently completed blocks

- Menu option availability audit is locally implemented for individual authenticated Mini App,
  compound Mini App and Telegram stop-list mutations. Every committed real delta writes one
  transaction-bound safe audit; direct no-op and Shift Check write no per-option availability audit.
  The mandatory PostgreSQL class is `15/0/0/0`. Independent review and release gates remain open.
- Menu option price audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. `VenueMenuRepository.updateOption` is the sole existing-option price SQL
  writer. Authenticated Mini App actor and server-owned `VENUE_MINI_APP` source enter one transaction:
  item lock, deterministic option locks, DB-current reread, compound update, independent rename/price
  audits and one commit. Exactly one committed real delta change emits `MENU_OPTION_PRICE_CHANGED`;
  no-op, denial, collision and rollback emit none. Payload is only `venueId`, `itemId`, `optionId`,
  old/new delta and `source`; no raw request, Telegram, media, secret or PII data. Current server
  pricing wins at submit and new rows snapshot it; existing snapshots stay immutable. No migration.
  User-confirmed smoke: price-only success; one price/no rename audit; same-price retry creates none;
  atomic name+price creates one audit of each kind; server current price or safe reconfirmation at
  submit; intact menu/data; routine cleanup. Historical snapshot preservation is automated evidence,
  not a separately confirmed staging scenario.
- Venue Menu management UX stabilization, option rename, option hard delete with atomic base-profile
  normalization, item/empty-category hard deletes, promotion creation/effective-state/lifecycle and
  staff role/removal stay closed only for their separately documented bounded contracts.

## 4. Current bounded block

Verdict: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT / MVP IMPLEMENTED / LOCAL
VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

`VenueMenuRepository.setOptionAvailability` now requires actor/source and owns one transaction:
non-locking option-to-item hint, authoritative item lock, all item options by ascending id, DB-current
reread, real-delta update, same-connection audit and one commit. `updateOption` appends independent
rename, price and availability audits for only the field families that actually changed, and all row
fields, `updated_at` and audits roll back together on failure.

Mini App actor is the authenticated session subject and Telegram actor is the current callback user;
sources are server-fixed `VENUE_MINI_APP` / `TELEGRAM_BOT`. Availability payload keys are exactly
`venueId`, `itemId`, `optionId`, `oldIsAvailable`, `newIsAvailable`, `source`. Direct availability keeps
current Owner/Manager/Staff `MENU_AVAILABILITY_MANAGE`; compound PATCH remains Owner/Manager
`MENU_MANAGE`. Shift Check retains only its single `MENU_SHIFT_CHECK_COMPLETED`, including mixed,
no-op and stale behavior. No item audit, option-create audit, permission, money/name, order schema,
media or migration change was added.

## 5. Open gaps and risks

- Keep open: separate price-only audit-failure regression test; option create audit; item price/update
  audit; transaction-bound membership-revoke linearization; fuller rollback coverage; audit viewer;
  dependency viewer; Promotion Configuration edit audit; Force-close/session audit; Notification
  Consent; Promotion Compatibility Policy; broader Menu/Dangerous Action Audit; and deferred
  canonical smoke items.
- Force-close/session has no bounded production staff mutation path; Guest obligations and shared
  physical-session semantics need separate design. Notification Consent needs persisted
  operational/marketing scopes, evidence/version/source and opt-out. Promotion Compatibility has high
  financial/product-policy risk.
- Media/R2 foundation remains `STOP_FOR_MEDIA_STORAGE_DECISION`; stash was not read or applied.
- Deferred manual smoke stays unchanged: `REPEAT-MANUAL-001` P1 environment-blocked,
  `CATALOG-SEARCH-MANUAL-001` P2 and `STAFF-IDENTITY-MANUAL-001` P2.

## 6. Important architecture/data constraints

- Venue Mode is operational source of truth; staff-chat is radar/shortcut only.
- Server-side RBAC is authoritative; UI hiding and QR/table/tab tokens are not authority.
- Preserve `table_session`, `order_batch`, `tab`, tenant and current-user boundaries.
- Structured menu is the order source of truth; historical order/bill snapshots survive menu edits.
- Audit actor/source are server-derived. Safe payloads exclude secrets, raw Telegram/initData,
  provider data, guest text and unrelated PII.
- Do not add a second promotion engine or implicitly define cross-promotion compatibility.

## 7. Release and validation state

Price-audit release closure remains user-confirmed for its release HEAD. Availability-audit local
evidence includes focused repository, route, Telegram and Guest order selectors, deterministic
Testcontainers PostgreSQL `15/0/0/0`, compile/lint, Mini App build and full browser smoke. Green
Actions, independent review, staging deploy and bounded smoke remain required before release closure.

## 8. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- Venue operations: `docs/VENUE_OPERATIONS.md`.
- QA/staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## 9. Next action

Independently review only the bounded option-availability audit diff, then run green Actions and the
bounded staging smoke before release closure. Do not expand into option create, item availability,
permissions, migrations, media/R2, stash or `scripts/dev/`.

## 10. Last verified date

2026-08-11. Menu option availability audit is local-review-ready only for its bounded contract; Menu
and Dangerous Action Audit programs remain partial.
