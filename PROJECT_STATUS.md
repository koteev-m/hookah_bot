# Project Status

Last verified: 2026-08-13. There is no live Goal object. The saved objective in
`/Users/maksimmartynov/.codex/attachments/e9d0953e-4f84-42ee-8a88-f5c2e81d718a/goal-objective.md`
is `IMPLEMENT_SHARED_INITIAL_MENU_BOOTSTRAP_NEXT`, not Media Upload, R2, object storage or staff
photo upload.

## 1. Current stage

**VENUE MENU ONBOARDING / SHARED INITIAL MENU BOOTSTRAP / DONE / MVP /
STAGING-SMOKE-PASSED**.

The user confirmed green GitHub Actions for the release HEAD, staging deploy and the bounded
cross-surface smoke: Mini App-first and Telegram-first bootstrap parity, repeat with no duplicate
rows/audits, partial/custom menu preservation, Staff denial, approval remaining non-seeding and
successful cleanup. The exact defaults, transaction/audit/privacy contract and local automated
evidence remain canonical in `docs/MENU_OPTIONS_STOPLIST.md` and
`docs/TESTING_QA_SMOKE_STRATEGY.md`. This closes only shared initial-menu bootstrap, not broader
onboarding, menu constructor/media/top-list, permission parity or the whole product.

Schema verdict: **NO_MIGRATION_EXPECTED**.

## 2. Current P2/P3 index

Every open P2/P3 has one stable ID and one canonical owner section containing area, evidence, risk,
minimal fix, required trigger/release boundary and status. `OPEN` entries are not release blockers
until their stated trigger; `IN_NEXT_EPIC` entries become gates for that epic; `DONE` requires
recorded implementation or docs evidence. This file is only the current index and must not duplicate
the full finding.

| ID | Canonical owner | Status | Required boundary |
| --- | --- | --- | --- |
| `MENU-CONC-001` | `docs/TESTING_QA_SMOKE_STRATEGY.md` | `OPEN` | Before the next item move/update concurrency change or affected Menu release. |
| `MENU-TEST-002` | `docs/TESTING_QA_SMOKE_STRATEGY.md` | `OPEN` | With the next category writer/audit/concurrency change. |
| `BOOTSTRAP-QA-001` | `docs/TESTING_QA_SMOKE_STRATEGY.md` | `DONE` | Docs-only handoff: current shared PostgreSQL minimum is synchronized to `44`. |
| `BOOTSTRAP-TEST-002` | `docs/TESTING_QA_SMOKE_STRATEGY.md` | `IN_NEXT_EPIC` | Required by the next onboarding epic before runtime release. |
| `OWNERSHIP-MODEL-001` | `docs/UPDATED_PRODUCT_AI_ROADMAP.md` | `IN_NEXT_EPIC` | Ownership Cockpit API/UI contract and tests. |

## 3. Read-only product/runtime inventory

### Venue Owner

- A first or additional venue application is currently Telegram-only through
  `🤝 Добавить свою кальянную`; the dialog writes `venue_connection_requests` with venue name,
  city, contact and optional comment. The Venue Mini App has no self-service application route or
  screen.
- The actual request states are `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`; there is no
  `NEEDS_INFO`. Normal repeated submit checks `findActiveUnlinkedByUser`: an existing `PENDING` or
  approved-but-unlinked request is shown instead of creating another. Pending can be edited or
  cancelled; approved-unlinked can be closed. Historical rejected/cancelled requests are not a
  current self-service list.
- `/api/venue/me` returns all active venue memberships. The header selector in `venueApp.ts` is
  shown when more than one venue is available, persists `venueId`, and derives the allowed set from
  the server response. The model supports multiple venues per user and multiple OWNER memberships
  per venue.
- Approval alone only changes the request state. The existing Telegram create-and-link path
  separately creates a new `DRAFT` venue, grants OWNER membership, applies commercial state and
  links the request; it has no current existing-venue chooser.
  The venue appears in `/api/venue/me` and the existing selector after the next authoritative access
  reload. Shared menu bootstrap then works from either qualifying first management surface.

### Platform Owner

- Platform Mini App can list venues, directly create a `DRAFT` venue, open venue detail, see all
  active OWNER memberships, search a user, assign/revoke OWNER, invite an owner and manage existing
  lifecycle/commercial panels. The list is venue-centric; backend summary includes city, owner count
  and subscription summary, while current TypeScript/list presentation omits city and owner names.
- Top-level routes are currently venues plus separate create/onboarding/placements/support/analytics
  screens. `Подключение` is informational only: connection request intake, approve/reject,
  commercial terms and create/link remain in Telegram and have no Platform Mini App API/UI.
- There is no owner-centric list, owner drill-down or owners workspace. Owner identities are visible
  only after opening a venue; the venue row shows only an owner count. Telegram has `Кальянные`,
  `Заявки на подключение` and a displayed `Клиенты / Лимиты` section; `Владельцы` remains a handled
  alias, not a full membership-based owners cockpit.

## 4. Ownership data-model findings

- Operational venue access is authoritative in active `venue_members(role=OWNER)`: multiple owners
  per venue and multiple venues per user are valid.
- `venues.owner_account_id` and `venue_owner_accounts.primary_owner_user_id` represent one
  commercial quota/account relationship. They do not designate a primary operational OWNER
  membership. Existing Telegram code selecting the minimum OWNER user id for quota display is a
  heuristic, not product authority; the new UI must not expose it as `primary owner`.
- Owners list/count/status aggregation can be built from existing `users`, `venue_members` and
  `venues`. The bounded cockpit needs no schema migration and must keep current commercial-account
  assignment/quota behavior separate from membership presentation.

## 5. Next implementation epic

Verdict: **IMPLEMENT_PLATFORM_ONBOARDING_OWNERSHIP_COCKPIT_NEXT**.

Bounded outcome: one shared onboarding application/orchestration contract serves Telegram and new
Mini App adapters; Venue Owner receives `Мои заведения`, own request states and `Добавить заведение`;
Platform Owner receives top-level `Заявки`, `Кальянные`, `Владельцы`. Venue list gains city and an
all-owner summary, Owners is membership-aggregated with search/filter and drill-down, and Requests
supports current pending/detail/approve/reject/create-new-DRAFT-and-link semantics with existing
RBAC/audit.
Do not add `NEEDS_INFO`, a primary-owner concept, a second onboarding engine or client-selected
actor/owner authority.

Explicitly out of scope: billing redesign, support, analytics, media/R2, menu changes, a new venue
lifecycle, commercial owner-account transfer/redesign, Telegram Stars/provider work and unrelated
Platform navigation redesign. Migration verdict: **NO_MIGRATION_EXPECTED**.

The full implementation contract, likely files, tests, CI/release gates, consolidated staging smoke
and ready implementation prompt are canonical in `docs/UPDATED_PRODUCT_AI_ROADMAP.md`; product
surface rules are in `docs/PLATFORM_COCKPIT.md`; authority/privacy rules are in
`docs/SECURITY_RBAC_MATRIX.md`.

## 6. Worktree constraints

This handoff is docs-only. Runtime/backend, Mini App, tests, CI and migrations are untouched. Do not
stage, commit, push, deploy or read/apply/change stash. The pre-existing untracked `scripts/dev/`
area remains untouched and must not be staged.
