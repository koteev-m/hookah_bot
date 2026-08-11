# Project Status

Last verified: 2026-08-11 at `HEAD 0489a2f` (`HEAD == origin/main`). **DANGEROUS ACTION AUDIT SLICE /
MENU OPTION PRICE AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.

## 1. Purpose and source-of-truth order

If this handoff disagrees with current code, Git, migrations, tests or canonical docs, those sources
win. This is a compact current handoff, not a historical changelog. Replace stale state rather than
append logs. Update after a staging-smoke closure, material architecture/data-contract decision,
next-block change, P0/P1 blocker change or before a new long task.

## 2. Current product/release state

- Overall product, permission parity and dangerous-action audit remain `PARTIAL`; no whole product,
  Menu module or UX production-readiness claim is made.
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

Verdict: **IMPLEMENT_MENU_OPTION_AVAILABILITY_AUDIT_NEXT**.

Exact outcome: audit only real individual existing-option availability changes through the present
Mini App direct availability route, Mini App compound option PATCH and Telegram Owner/Manager/Staff
stop-list callbacks. Each committed real change writes one safe `MENU_OPTION_AVAILABILITY_CHANGED` in
the mutation transaction with server-derived actor and `VENUE_MINI_APP` or `TELEGRAM_BOT` source;
same-state, denial, foreign/not-found, audit/SQL failure and rollback write zero. Existing batch
Shift Check keeps exactly its one `MENU_SHIFT_CHECK_COMPLETED` and writes no per-option availability
audit. No item availability audit, option create, pricing, rename, schema or product-permission
change is included. **NO_MIGRATION_EXPECTED**.

Read-only evidence: `setOptionAvailability` is the direct unaudited writer used by both clients;
`updateOption` can co-submit availability from the Mini App. The direct Mini App route and Telegram
callbacks require `MENU_AVAILABILITY_MANAGE`, which current Owner, Manager and Staff roles have;
compound PATCH remains `MENU_MANAGE` for Owner/Manager. Shift Check separately locks sorted item and
option rows, compares expected availability and writes its batch audit. This makes individual
availability audit useful, bounded and non-financial while preserving the released batch contract.

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

Price-audit release closure is user-confirmed for current HEAD: green Actions, staging deploy and the
bounded smoke listed above. This docs-only handoff ran no runtime tests and does not make a release
claim for the selected availability slice. Its future runtime change requires focused backend and
Telegram/Mini App tests, green Actions, staging deploy and bounded smoke before closure.

## 8. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- Venue operations: `docs/VENUE_OPERATIONS.md`.
- QA/staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## 9. Next action

Implement only **IMPLEMENT_MENU_OPTION_AVAILABILITY_AUDIT_NEXT**: one transaction-bound safe audit
for each real individual option availability mutation, without duplicate Shift Check audit. Do not
touch price, option create, item availability, migrations, media/R2, stash or `scripts/dev/`.

## 10. Last verified date

2026-08-11. Menu option price audit is release-closed only for its bounded contract; Menu and
Dangerous Action Audit programs remain partial. The next bounded block is option availability audit.
