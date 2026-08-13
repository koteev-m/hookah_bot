# Project Status

Last verified: 2026-08-13. Active Goal is the objective in
`/Users/maksimmartynov/.codex/attachments/e9d0953e-4f84-42ee-8a88-f5c2e81d718a/goal-objective.md`.
It is the bounded shared initial-menu bootstrap, not Media/R2.

## 1. Current stage

**VENUE MENU ONBOARDING / SHARED INITIAL MENU BOOTSTRAP / MVP IMPLEMENTED / LOCAL VALIDATION
PASSED / REVIEW REQUIRED BEFORE COMMIT**.

This status applies only to the shared initial structured-menu bootstrap. It does not mark the
whole onboarding flow, broader menu constructor/media/top-list work, permission parity or the
overall product production-ready. The previously release-closed Venue Menu Management transaction/
audit closure and its regression contracts remain unchanged.

## 2. Verified current flow and shared defaults

Platform approval, venue linking and Owner assignment still grant access without creating menu
categories. The ordinary authenticated Mini App menu GET remains a pure read. Physical creation of
missing defaults happens on the first qualifying authenticated Owner/Manager management entry
through the explicit Mini App mutation or the existing Telegram `🍽 Заказное меню` root. Either
surface may invoke bootstrap again; a complete menu makes those repeated calls exact no-ops.

Both surfaces use the same internal seed source in `VenueMenuRepository.kt`, in this exact order:

1. `Кальянное меню` — `MenuSemanticType.OTHER`;
2. `Напитки` — `MenuSemanticType.OTHER`;
3. `Кухня` — `MenuSemanticType.OTHER`.

The shared source creates categories only. It creates no items, options, flavors or base profiles.

## 3. Repository, API and client behavior

`VenueMenuRepository.createMissingCategories` remains the single bootstrap writer. One repository-
owned JDBC transaction acquires the existing venue category-order lock, rereads current categories,
matches trimmed lowercase names, appends only missing defaults after the current maximum order,
writes one `MENU_CATEGORY_CREATED` audit for each physical insert, rereads the authoritative menu
and commits once. Existing category ids, names, semantic types, order, timestamps, items and options
are not rewritten. A complete menu is an exact row/timestamp/audit no-op; any insert or audit failure
rolls back all bootstrap rows and audits.

Mini App adds authenticated `POST /api/venue/menu/bootstrap?venueId=...`. It requires current
own-venue `MENU_MANAGE`, derives actor from the session subject, fixes source to `VENUE_MINI_APP`,
ignores actor/source spoof fields and returns only `{venueId}`. Owner and Manager are allowed; Staff,
foreign, unaffiliated and Platform-only actors are denied before mutation. The route writes no
second audit and the existing safe database-failure contract returns `503`.

On each Owner/Manager menu screen mount the Mini App awaits the mutation, then performs exactly one
authoritative GET before rendering menu data. A bootstrap failure uses the existing actionable
manual retry without an automatic loop or false empty-menu success. After bootstrap succeeds, a
failed-GET retry repeats only GET. Staff/read-only entry sends no bootstrap mutation. Existing abort,
disposed-screen, sequence, focus/interaction restoration and venue/account switch protections ignore
late responses from stale contexts.

The Telegram root now imports the same seed source and keeps its existing Owner/Manager guard,
current authenticated Telegram actor, server-owned `TELEGRAM_BOT` source and success/failure copy.
Repeat remains a repository no-op and database/audit failure returns before success UI.

## 4. Audit, privacy and concurrency

Every inserted default uses action `MENU_CATEGORY_CREATED`, entity type `menu_category` and the new
category id. Payload keys remain exactly `venueId`, `categoryId`, `source`; actor is stored only in
the standard actor column. Category names/types, raw request, initData, Telegram update/callback/
identity, media, secrets and unrelated PII are excluded.

The existing ordinary category create, bootstrap and category reorder operations share the same
venue-scoped category-order lock and compatible lock order. Deterministic Testcontainers PostgreSQL
coverage proves Mini App vs Telegram bootstrap, Mini App vs Mini App bootstrap, bootstrap vs ordinary
create, bootstrap vs reorder and partial-audit-failure rollback with independent connections/PIDs,
latches and observed `pg_blocking_pids`/`pg_locks`. Final defaults are unique and committed insert
cardinality equals create-audit cardinality.

## 5. Local validation evidence

Focused JUnit XML is green: repository `54/0/0/0`, Mini App routes `46/0/0/0`, Telegram
`551/0/0/0`, onboarding/connection `18/0/0/0` and menu PostgreSQL concurrency `44/0/0/0`.
The exact current route/security selector passed `1190/0/0/0`. The exact five-suite PostgreSQL
selector passed `77/0/0/0` with minimum vector `8 / 14 / 2 / 44 / 9`.

Standalone `compileKotlin`, `ktlintCheck`, Mini App production build and full Playwright smoke
`176/176` passed. The seven new deterministic browser tests cover empty-first bootstrap, repeat,
partial/custom preservation, bootstrap and subsequent GET failure/retry, delayed venue switch,
delayed account replacement and Staff no-mutation behavior. `git diff --check` is clean.

Only the existing route/security CI gate changed: it now requires onboarding/connection XML with a
minimum of `18` while retaining the existing repository `51 -> 54`, routes `43 -> 46`, Telegram
`549 -> 551` and menu PostgreSQL `40 -> 44` minima. No workflow was added. The blocking CI coverage
gap is fixed locally; the next short independent review, green Actions and staging smoke remain
required before release.

## 6. Release and schema verdict

**NO_MIGRATION_EXPECTED**. No migration, SYSTEM actor, approval transaction redesign, default
semantic-type change, new menu/onboarding engine or UI redesign was required. This local result is
not release or production evidence.

Because backend, Mini App and Telegram runtime behavior changed, staging deploy and a bounded
cross-surface smoke are required only after independent review, commit and green GitHub Actions.
No deploy was performed in this task.

## 7. Remaining work

The blocking CI coverage gap is fixed locally. The next short independent review remains required
before commit; then green Actions, staging deploy and smoke for Owner/Manager/Staff,
Mini App-first/Telegram-first parity, no-op/audit privacy and an existing-menu venue remain release
gates.

Broader onboarding automation, menu constructor/archive/description/media/top-list, per-venue Staff
stop-list policy, permission parity and the wider Dangerous Action Audit remain P2/future or partial
according to their canonical documents. Media/R2 was not opened by this task.

## 8. Worktree constraints

Do not stage, commit, push, deploy or apply/read/change stash in this task. The pre-existing
untracked `scripts/dev/` area remains untouched and must not be staged. Final handoff must include
the exact validation results and `git status --short`.
