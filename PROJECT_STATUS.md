# Project Status

Last verified: 2026-08-12. Active Goal check: no active Goal/objective. This is a
docs-only release handoff; the media/R2 goal mismatch stop rule was not triggered.

## 1. Current product and release state

- Overall product, permission parity and the broader Menu/Dangerous Action Audit remain `PARTIAL`.
- **DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**.
- The closed status applies only to authenticated item creation. It does not close Menu Audit,
  Dangerous Action Audit or the product.
- Menu option create, item/category/option hard delete, item/option availability, option rename,
  option price, atomic base-profile normalization, Shift Check aggregate audit and Venue Menu UX
  stabilization remain their separately bounded release-closed contracts.

## 2. Current release evidence

- Current HEAD and `origin/main` are both `4d3ef651c2a3e12b6f018ddea43bd63da4d7c4b0`
  (`Fix booking route test billing race`). The preceding item-create implementation is `0523b7f`.
- At handoff `git status --short` reports only pre-existing untracked `scripts/dev/`; `git diff --check`
  is clean. No stash was read, applied or changed.
- User-confirmed release evidence for item create: the new GitHub Actions run was fully green, staging
  deploy completed, and bounded smoke passed. Owner created through Mini App; Manager through
  Telegram; Staff was denied; duplicate names made distinct item rows and audits; item ordering and
  working Guest menu remained intact; cleanup completed normally.

## 3. Recently completed: Menu Item Create Audit

Contract: one authenticated production item-create writer, `VenueMenuRepository.createItem` through
its private `INSERT INTO menu_items`. Mini App `POST /menu/items` and the Telegram Owner/Manager
add-item dialog are the only authenticated callers. Owner/Manager are allowed for their venue;
Staff, foreign, unaffiliated and Platform-only actors are denied. Actor/source are server-derived:
`VENUE_MINI_APP` or `TELEGRAM_BOT`.

The repository owns one transaction: category scope, blocking category lock, scope recheck, current
`MAX(sort_order)+1`, physical insert, generated id, same-connection audit, reread and commit.
Each committed row writes exactly one `MENU_ITEM_CREATED` for `menu_item` / `itemId`, with only
`venueId`, `itemId`, `source`. Audit failure rolls back the item; duplicate names retain separate-row
semantics; item creation creates no options; no migration exists.

## 4. Booking test release hygiene

This is test-only, not a booking runtime defect. Before schedule assertions,
`SubscriptionBillingJob` could create a TRIAL row while `seedSubscription` made its strict insert,
causing H2 SQLState `23505` duplicate `venue_subscriptions` primary key. Seven relevant
`VenueBookingRoutesTest` configurations now set `billing.subscription.intervalSeconds=0`.
Production defaults, runtime and schema did not change. The exact `VenueBookingRoutesTest` plus
`VenueRbacRoutesTest` selector passed; the new Actions run was green.

## 5. Current Menu Management inventory

All production `menu_categories`, `menu_items` and `menu_item_options` SQL writers are in
`VenueMenuRepository`; there is no other main-source runtime writer. Migration/test/staging seed SQL
is not an authenticated production writer. The seven remaining unaudited families are: category
create (including Telegram default-category seeding), category rename, category semantic type,
category reorder, item metadata/commercial PATCH, Telegram standalone item semantic type, and item
reorder. See `docs/MENU_OPTIONS_STOPLIST.md` for the complete writer/caller/lock/coverage matrix.

Current audit gaps are precisely: `MENU_CATEGORY_CREATED`, `MENU_CATEGORY_RENAMED`,
`MENU_CATEGORY_TYPE_CHANGED`, `MENU_CATEGORIES_REORDERED`, `MENU_ITEM_RENAMED`,
`MENU_ITEM_PRICE_CHANGED`, `MENU_ITEM_TYPE_CHANGED`, `MENU_ITEM_CATEGORY_MOVED` and
`MENU_ITEMS_REORDERED`. No option reorder writer exists.

## 6. One next implementation epic

**IMPLEMENT_MENU_MANAGEMENT_CLOSURE_EPIC_NEXT**.

Go: all remaining writers use the same repository and existing schema; there are seven, not more
than twelve, physical writer families. The work preserves current product semantics and needs no
migration, API or UI expansion. It can use one worktree, one CI set and one consolidated staging
smoke. No unresolved authority, financial or privacy decision remains.

Bounded payload policy: rename actions contain ids and source only; type changes contain finite
old/new authoritative types; price/currency contain only old/new authoritative DB values; a category
move contains old/new category ids; reorder records scope, count and deterministic old/new ordered-id
hashes, never a full list. All use server-derived actor/source and exact one/no-op/rollback rules.

Explicit exclusions: media/R2, images/files/PDF, promotions, cart recovery hardening, modifier-domain
work, new product UI/features, audit viewer, membership-revoke linearization, force-close/session,
Notification Consent, migrations and schema redesign.

## 7. Next action and constraints

Start the single closure epic only after re-verifying the inventory against current code. Keep already
closed families closed. Require focused repository/routes/Telegram/PostgreSQL tests, existing exact CI
selectors/XML, compile, ktlint, Mini App build/e2e and `git diff --check`; then one consolidated manual
smoke for category/item mutations, Telegram parity, Staff denial, audit cardinality/privacy, Guest
reload/current price, historical snapshot and cleanup.

No runtime, tests, CI workflow, migration, stash or `scripts/dev/` change was made in this handoff.
Do not stage, commit, push or deploy from this docs-only task.
