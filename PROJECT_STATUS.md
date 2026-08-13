# Project Status

Last verified: 2026-08-12. Active Goal is the objective in
`/Users/maksimmartynov/.codex/attachments/b3a604ea-adda-4635-9f79-7937180caa97/goal-objective.md`.
It is the bounded Menu Management transaction/audit closure, not media/R2.

## 1. Current stage

**VENUE MENU MANAGEMENT / EXISTING-CONTRACT AUDIT AND TRANSACTION CLOSURE / MVP IMPLEMENTED /
LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

This status applies only to the existing category/item management writers. The overall product,
broader menu constructor/media/top-list work, permission parity and Dangerous Action Audit remain
`PARTIAL`. Previously release-closed menu create/delete/availability/option/normalization/Shift
Check, Guest stale-cart/idempotency and Venue Menu UX contracts remain unchanged.

## 2. Verified writer inventory and scope

`VenueMenuRepository` is still the sole main-source production SQL owner for `menu_categories`,
`menu_items` and `menu_item_options`; no additional authenticated runtime writer was found.
Migration/test/staging seed SQL is not a production writer. This closure contains exactly nine
families:

1. category create, including authenticated Telegram default seeding;
2. category rename;
3. category semantic type;
4. category reorder;
5. item rename;
6. item price/currency;
7. item semantic type;
8. item category move;
9. item reorder.

No option reorder writer exists. No scope, lock-order, authority, financial, API/UX or seeding stop
rule was triggered.

## 3. Implemented transaction and audit contract

The nine actions are `MENU_CATEGORY_CREATED`, `MENU_CATEGORY_RENAMED`,
`MENU_CATEGORY_TYPE_CHANGED`, `MENU_CATEGORIES_REORDERED`, `MENU_ITEM_RENAMED`,
`MENU_ITEM_PRICE_CHANGED`, `MENU_ITEM_TYPE_CHANGED`, `MENU_ITEM_CATEGORY_MOVED` and
`MENU_ITEMS_REORDERED`.

Each operation uses one repository-owned JDBC connection/transaction: deterministic ordering/category
locks, authoritative reread, real-delta calculation, business mutation, same-connection audit rows,
authoritative result reread and one commit. Exact no-op writes no family audit and preserves
`updated_at`; any SQL/audit failure rolls back every field, order/timestamp and audit row. Compound
item PATCH can atomically emit one truthful audit for each real rename, price/currency, type, move
and existing availability delta.

Category default seeding is one atomic operation with one create audit per physically missing row
and zero on repeat. Reorders require the exact authoritative set and store count plus deterministic
old/new SHA-256 order hashes, never full id arrays. Move locks source/destination categories in
ascending id before the item; item reorder locks category then authoritative items by ascending id.

## 4. Authority, privacy and compatibility

Owner/Manager own-venue authority is unchanged; legacy `ADMIN` remains only Manager-compatible.
Staff, foreign, unaffiliated and Platform-only actors are denied structure/commercial writes before
entity facts. Mini App actor is the authenticated session subject and source is `VENUE_MINI_APP`.
Telegram actor is the current user, must equal persisted dialog owner for dialog continuations, and
source is `TELEGRAM_BOT`. Routes/router append no duplicate audit.

Payloads contain only allowlisted ids/source, authoritative finite old/new type or price/currency
values, and bounded reorder counts/hashes. Names, raw requests/initData/Telegram data, media,
option/promotion/cart/order contents, secrets, PII and full reorder arrays are excluded. Current
Guest menu/new orders use current values; historical name/price snapshots remain immutable.
Pricing, currency/minor units, promotion, stale-cart/idempotency, DTO/status and UX semantics did
not change.

## 5. Local evidence

Green XML records repository `51/0/0/0`, Mini App routes `43/0/0/0`, Telegram `549/0/0/0`, Guest
order/history `61/0/0/0` and real Testcontainers PostgreSQL menu concurrency `40/0/0/0`. The exact
route/security selector passed `1164/0/0/0`; the exact five-suite PostgreSQL selector passed
`73/0/0/0` with minimum vector `8 / 14 / 2 / 40 / 9`. The menu matrix contains all ten required
management schedules with independent connections/PIDs, deterministic latches and observed
`pg_blocking_pids`/`pg_locks` evidence.

Standalone `compileKotlin`, `ktlintCheck`, Mini App production build and full Playwright smoke
`169/169` passed. Existing CI minima have been raised to Guest `61`, repository `51`, routes `43`,
Telegram `549` and menu PostgreSQL `40`; missing/zero/below-minimum/skipped/failing XML still fails
the existing mandatory gates. Final `git diff --check` and status are the handoff-only checks.

## 6. Release and schema verdict

**NO_MIGRATION_EXPECTED**. No new workflow, schema, unique constraint, ordering engine, API or
product feature was added. Independent review, green GitHub Actions, staging deploy and bounded
manual smoke are still required before release; local evidence must not be described as production
readiness.

## 7. Worktree constraints

Do not stage, commit, push, deploy or apply/read/change stash in this task. The pre-existing
untracked `scripts/dev/` area remains untouched and must not be staged. Final handoff must include
the exact validation results, remaining release gates and `git status --short`.
