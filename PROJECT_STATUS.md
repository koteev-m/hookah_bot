# Project Status

Last verified: 2026-08-11 at base `HEAD f748c12` (`HEAD == origin/main`) with an uncommitted bounded
UX follow-up. **GUEST CART STALE MENU SELECTION RECOVERY / ITEM-LEVEL ACTION AND COPY POLISH / MVP
IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

## 1. Purpose and source-of-truth order

If this handoff disagrees with current code, Git, migrations, tests or canonical docs, those sources
win. This is a compact current handoff, not a historical changelog. Replace stale state rather than
append logs. Update after a staging-smoke closure, material architecture/data-contract decision,
next-block change, P0/P1 blocker change or before a new long task.

## 2. Current product/release state

- Overall product, permission parity and dangerous-action audit remain `PARTIAL`; no whole product,
  Menu module or UX production-readiness claim is made.
- **GUEST CART STALE MENU SELECTION RECOVERY / ITEM-LEVEL ACTION AND COPY POLISH / MVP IMPLEMENTED /
  LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. This closes no release gate before
  independent review, green Actions, replacement of every order-writing backend instance, staging
  deploy and focused smoke.
- Menu option availability audit remains **FUNCTIONALLY PASSED ON STAGING / GENERAL CART RECOVERY
  FOLLOW-UP REQUIRED** until the new focused recovery smoke is repeated.
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. This closes only the authenticated Venue Mini App existing-option price
  mutation and its documented money/order/audit contract.
- Current base HEAD `f748c12` equals `origin/main`; only the item-level action/copy polish is
  uncommitted.
- **VENUE MENU MANAGEMENT UX STABILIZATION / MOBILE RESPONSIVENESS + PRICE INPUT ERGONOMICS +
  CONTEXT PRESERVATION / DONE / MVP / STAGING-SMOKE-PASSED** remains closed only for that bounded UX
  contract.

## 3. Recently completed blocks

- Menu option availability audit is **FUNCTIONALLY PASSED ON STAGING / GENERAL CART RECOVERY
  FOLLOW-UP REQUIRED** until the focused recovery smoke is repeated. Its writer/audit/RBAC and Shift
  Check boundaries are unchanged by the current block.
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

Verdict: **GUEST CART STALE MENU SELECTION RECOVERY / ITEM-LEVEL ACTION AND COPY POLISH / MVP
IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

Guest preview `POST /api/guest/order/preview` and final submit `POST /api/guest/order/add-batch` now
share one repository validation for item existence, expected venue/category scope, current item
availability, option existence, expected item/venue ownership and option availability. Deterministic
own-cart failures return HTTP `409`, code `CART_MENU_SELECTION_UNAVAILABLE`, and all issues in request
order as the current client cart-line reference `cartLineRef`, requested ids, `ITEM|OPTION` and
`REMOVED|UNAVAILABLE`. `cartLineRef` is stable for the current cart implementation but is not yet an
opaque identity. Foreign or malformed selections remain generic `INVALID_INPUT`; client
reason/details are not authority.

Mini App sends its existing stable `CartLine.key`, keeps every affected line visible, blocks
totals/submit, and shows line-specific actions. Option names are retained on the cart line; item names
come from the current mutable item cache and therefore are not an immutable cart snapshot. Typed
`ITEM / REMOVED` and `ITEM / UNAVAILABLE` now have distinct mandatory-removal copy.
`Удалить и выбрать другую` removes only that line, invalidates only its issue, rotates the existing
business-payload idempotency lifecycle, waits for authoritative recalculation of the remaining cart,
then opens the existing Guest menu without choosing a replacement automatically. `Удалить из
корзины` performs the same exact-line removal and recalculation while staying in the cart and moving
focus to the next line or cart heading. Other valid and problematic lines remain; submit stays blocked
while any issue remains. Option recovery is unchanged: it reuses the current DB-current picker,
excludes stale choices and preserves quantity/note. Retry remains secondary; no second item/domain
replacement engine was introduced.

The specialized recovery paths for `CART_MENU_SELECTION_UNAVAILABLE`,
`ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH` and `ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE` require exact HTTP
`409`. The same body codes on HTTP `400`, `422` or `500` stay on the generic error path, retain the
cart and current idempotency key, and expose no conflict-recovery UI.

An exact in-screen network retry of the same business payload keeps its idempotency key. Any
item/option/note/quantity/comment or account/venue/table-session/tab change rotates the key; a server
price, availability or pricing-fingerprint change alone does not. Payload mismatch keeps the cart and
allocates a new key only on the next explicit submit. An unverifiable legacy replay offers explicit
`Проверить активный заказ` and `Отправить как новый заказ` actions and never resends automatically.
If the cart changes while a request is in flight, a success for the submitted payload does not clear
or navigate away from the newer cart; the committed order is acknowledged and the newer payload
requires a separate explicit submit.

Preview is read-only for ordinary Guest and exact Platform Owner. Submit uses the existing order
engine inside one authoritative JDBC transaction: locked token/subscription/table-session/context,
session-scoped idempotency lookup, deterministic menu item/option locks, final typed validation,
session touch, personal-tab ensure, order/batch/items/options, fingerprint row and analytics commit or
rollback together. Staff notification runs only after commit and only for a new batch.

New idempotency rows store `request_fingerprint = v1:<lowercase SHA-256>` over canonical JSON for
actor, venue, table session, tab, normalized comment and sorted normalized merged lines. Exact replay
returns the committed batch before menu validation; different actor/tab/business payload in the same
table session returns `ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH`. The same key in another table session is
an independent operation. Legacy `NULL` rows are lazily upgraded only from complete immutable facts;
lost option identity or multiple ambiguous physical legacy rows in one logical session/key namespace
return `ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE` with no new order.

Additive PostgreSQL `V123` and H2 `V124` add nullable `VARCHAR(80) request_fingerprint`; existing rows
remain `NULL`, `response_snapshot` is unchanged, and no backfill or global unique constraint exists.
Raw request or canonical JSON is not stored, and `response_snapshot` is not repurposed. Mixed old/new
order writers are only migration-compatible, not a final rollout state: every
order-writing backend instance must run the new binary before release closure. Audit semantics,
availability RBAC, Shift Check, pricing formula, historical snapshots, Telegram runtime and media/R2
paths are unchanged; no new CI workflow was added.

## 5. Open gaps and risks

- No P0/P1 implementation blocker remains in this bounded local diff. Mini App build, the focused
  item-recovery pack and full browser smoke are green locally; the committed backend contract and its
  exact CI-equivalent route/security, PostgreSQL and migration gates remain unchanged. Independent
  review, green Actions, all-writer binary rollout, staging deploy and focused real item/option
  removed/unavailable plus retry/conflict smoke remain release gates.
- Keep P2/future: opaque cart-line identity, replacement merge semantics, issue owner
  tuple/generation, immutable item-name cart snapshot, live-region deduplication, error-size
  hardening, item availability audit, a new cart/order domain engine, global
  idempotency uniqueness across table sessions, true in-place item replacement/suggested alternatives
  (current safe Menu fallback),
  separate price-only audit-failure regression test; option create audit; item price/update
  audit; transaction-bound membership-revoke linearization; fuller rollback coverage; audit viewer;
  dependency viewer; Promotion Configuration edit audit; Force-close/session audit; Notification
  Consent; Promotion Compatibility Policy; broader Menu/Dangerous Action Audit; and deferred
  canonical smoke items.
- Keep P3/future: an analytics/outbox failure checkpoint and a post-commit notifier failure
  regression test; neither was implemented in this review closure.
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

Current stale-cart recovery local evidence: the exact route/security gate discovered and executed
`1114/1114` tests with `0` skipped, failures or errors; exact `GuestOrderRoutesTest` is `61/0/0/0`,
fingerprint repository coverage is `7/0/0/0`, and PostgreSQL concurrency is `9/0/0/0`. The current
migration gate is green at `2/0/0/0` for each H2/PostgreSQL audit and fingerprint suite. Compile and
ktlint remain green from the committed backend block. Mini App build is green; the item-level recovery
pack is `7/7`, the strict non-`409` browser pack remains covered, and full browser smoke is `169/169`;
`git diff --check` is green. The existing CI workflow selects the exact route, fingerprint, migration
and concurrency XML with fail-closed minima. Independent review, green Actions, all-writer rollout,
staging deploy and focused smoke remain required.

## 8. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- Venue operations: `docs/VENUE_OPERATIONS.md`.
- QA/staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## 9. Next action

Independently review only the bounded item-level action/copy diff, then run green Actions, staging
deploy and the focused item/option removed/unavailable smoke. Do not expand into item-replacement
engine, audits, permissions, additional migrations, media/R2, stash or `scripts/dev/`.

## 10. Last verified date

2026-08-11. Item-level cart recovery polish is local-review-ready only for its bounded contract; Menu
and Dangerous Action Audit programs remain partial. Stash was not read/applied/changed and
`scripts/dev/` was not touched.
