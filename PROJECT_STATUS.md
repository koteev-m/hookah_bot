# Project Status

Last verified: 2026-08-10 at `HEAD 9440b8f` (`HEAD == origin/main` before the current unstaged
working-tree change). **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / MVP IMPLEMENTED /
LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

## 1. Purpose and source-of-truth order

If this handoff disagrees with current code, Git, migrations, tests or canonical docs, those sources
win. This is a compact current handoff, not a historical changelog. Replace stale state rather than
append logs. Update after a staging-smoke closure, material architecture/data-contract decision,
next-block change, P0/P1 blocker change or before a new long task.

## 2. Current product/release state

- Overall product, permission parity and dangerous-action audit remain `PARTIAL`; no whole product,
  Menu module or UX production-readiness claim is made.
- **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / MVP IMPLEMENTED / LOCAL VALIDATION
  PASSED / REVIEW REQUIRED BEFORE COMMIT**. This is an unstaged local implementation; Actions,
  staging deploy and bounded staging smoke have not run for it.
- **VENUE MENU MANAGEMENT UX STABILIZATION / MOBILE RESPONSIVENESS + PRICE INPUT ERGONOMICS +
  CONTEXT PRESERVATION / DONE / MVP / STAGING-SMOKE-PASSED**. Only that bounded UX contract is
  closed.
- Menu item, empty-category and option hard-delete audits, option rename audit, promotion creation
  audit, promotion effective-state clarity, promotion lifecycle audit, staff role/removal audit and
  Venue Promotions Current/Archived Tabs UX are closed only for their documented bounded contracts.
- Release HEAD `a62faa5` equals `origin/main`. User-confirmed evidence records fully green GitHub
  Actions, staging deploy and the bounded 16-scenario Venue Menu UX smoke passed. `gh` is installed
  but its active token is invalid, so this handoff does not independently query Actions or infer any
  historical failure cause.

## 3. Recently completed blocks

- Venue Menu management UX stabilization: **VENUE MENU MANAGEMENT UX STABILIZATION / MOBILE
  RESPONSIVENESS + PRICE INPUT ERGONOMICS + CONTEXT PRESERVATION / DONE / MVP /
  STAGING-SMOKE-PASSED**. At 320/360/390/430 px cards, forms and controls fit without global
  horizontal-overflow masking; price entry preserves current minor-unit semantics; authoritative
  reload restores stable-ID context only until later user interaction takes precedence; category and
  cancel focus are deterministic; screen-owned success cannot cross venue/account disposal. Backend,
  schema, RBAC, audits, normalization, Guest menu and order snapshots are unchanged.
- Menu option rename audit: **DONE / MVP / STAGING-SMOKE-PASSED**. One transaction-bound writer
  covers the Mini App compound PATCH and Telegram rename; real rename has one safe audit and failure
  restores co-submitted fields.
- Menu option hard-delete audit with atomic base-profile normalization: **DONE / MVP /
  STAGING-SMOKE-PASSED**. One Telegram callback normalizes one item transactionally; physical
  deletes audit per removed row; history and stale-selection contracts remain regression.
- Menu item and empty-category hard-delete audits: **DONE / MVP / STAGING-SMOKE-PASSED**. Their
  existing cleanup, RBAC, atomic rollback and safe-payload contracts remain regression.
- Promotion creation/effective-state/lifecycle and staff role/removal audit slices remain closed only
  within their existing no-migration, bounded contracts.

## 4. Current bounded block

Verdict: **IMPLEMENT_MENU_OPTION_PRICE_AUDIT_NEXT** is implemented locally and requires independent
review before commit.

Exact outcome: a real committed `menu_item_options.price_delta_minor` change through the existing
authenticated Venue Mini App compound `PATCH /menu/options/{id}` writes exactly one safe price audit
in the existing item-lock/ascending-option-lock transaction. Payload keys are exactly `venueId`,
`itemId`, `optionId`, `oldPriceDeltaMinor`, `newPriceDeltaMinor`, `source`; actor remains in the
standard audit column. Exact price no-op, name/availability-only request, denial/foreign/missing,
collision, SQL/audit failure and rollback write no price audit. Existing rename audit stays
independent; a compound rename+price change can write one audit per changed audited field.

Read-only evidence: `VenueMenuRepository.updateOption` is the sole option-price SQL writer and
already locks the item then all options before its DB-current update. The Mini App route derives
actor and `VENUE_MINI_APP`; Telegram has no option-price writer. Checkout resolves the current
available option and persists `price_delta_minor_snapshot`, so historical orders remain immutable
while later submits use the DB-current price. The implementation writes the price audit on the same
connection after any rename audit and before the one commit; an audit failure restores the complete
compound row and both audit families. Deterministic PostgreSQL coverage is now nine tests and proves
truthful price ordering/no-op plus serialization with rename, delete and normalization.
**NO_MIGRATION**.

## 5. Open gaps and risks

- Keep open: option create audit, option availability audit, item price/update audit,
  transaction-bound membership-revoke linearization, fuller rollback coverage, Telegram negative
  tests, audit viewer, dependency viewer, Promotion Configuration edit audit, Force-close/session
  audit, Notification Consent, Promotion Compatibility Policy and broader Menu/Dangerous Action
  Audit.
- Force-close/session has no yet-bounded production staff mutation path; Guest obligations and shared
  physical-session semantics need separate design.
- Notification Consent needs persisted operational/marketing scopes, evidence/version/source and
  opt-out before sends. Promotion Compatibility has high financial/product-policy risk.
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

## 7. Local verification

Passed for the current unstaged slice:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestOrder*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVisit*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuOptionNormalizationConcurrencyPostgresTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

The PostgreSQL XML is `9/0/0/0`; Playwright is `152/152`. `git diff --check` passes. Runtime release
closure still requires independent review, a future authorized commit/push, green Actions, staging
deploy and bounded staging smoke.

## 8. Canonical document map

- Product entrypoint: `docs/PRODUCT_SPEC.md`.
- Menu contract: `docs/MENU_OPTIONS_STOPLIST.md`.
- Roles/audit/privacy: `docs/SECURITY_RBAC_MATRIX.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- Venue operations: `docs/VENUE_OPERATIONS.md`.
- QA/staging gates: `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Current roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.
- Manual smoke checklist: `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`.

## 9. Next action

1. Check active Goal/objective, then read this file and only relevant canonical docs.
2. Independently review the unstaged `IMPLEMENT_MENU_OPTION_PRICE_AUDIT_NEXT` implementation,
   especially exactly-one/no-op, actor/source, compound rollback, PostgreSQL ordering and immutable
   order snapshots.
3. Do not call the slice release-closed until an authorized commit/push has green Actions and the
   changed runtime has passed staging deploy plus bounded smoke.
4. Do not select or begin a second audit slice during this review.
5. Do not touch stash or `scripts/dev/`; do not stage, commit, push or deploy without instruction.

## 10. Last verified date

2026-08-10. Venue Menu UX stabilization and option rename are release-closed only for their bounded
contracts. Menu option price audit is locally implemented and validated but still requires review,
Actions and staging evidence; the broader Menu and Dangerous Action Audit programs remain partial.
