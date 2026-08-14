# Project Status

Last verified: 2026-08-14. The active objective is the bounded independent-review finding closure for
`PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT` from
`/Users/maksimmartynov/.codex/attachments/1b57c559-70fc-4473-bc27-655577607006/goal-objective.md`.

## 1. Current stage

**PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT / MVP IMPLEMENTED /
LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

The runtime, Mini Apps, focused tests and CI selectors are complete in one bounded worktree change.
Green GitHub Actions, staging deploy and consolidated staging smoke have not happened, so this is
not a release-closed or production-ready claim.

Schema verdict: **NO_MIGRATION**. Applicant-row and request-row locks safely serialize submit and
create/link with the current schema.

## 2. Product decisions and boundaries

- Any authenticated Telegram user can submit a first or additional venue connection application,
  including before an OWNER membership exists. Venue Mini App submission is the additional-venue
  entry for an active operational Owner only; Manager, Staff, foreign and Platform-only identities
  are denied on that surface. The adapter selects the entry policy server-side and the applicant is
  always the authenticated subject.
- `owner_quota_create_start` remains a compatibility callback, but it only opens the shared
  application flow. Persisted legacy `OWNER_VENUE_CREATE_WAIT_*` dialogs are cleared and redirected
  there. Neither path creates a venue, membership, request link or selected-venue state.
- Submit creates only a `PENDING` request. The lifecycle remains exactly `PENDING`, `APPROVED`,
  `REJECTED`, `CANCELLED`; there is no `NEEDS_INFO`.
- Platform create/link owns one transaction: lock applicant and request, lock/create the commercial
  owner account, enforce current quota, create one `DRAFT`, assign one operational OWNER membership,
  apply commercial terms, link the request and append success audits. For a first applicant it
  preserves the former connection-flow account initialization and default limit of one. Failure
  rolls everything back; retry returns the already linked venue.
- Quota does not gate submission. It remains commercial-only and is enforced at create/link. Existing
  account, quota and limit-request management remain intact. Existing quota-created venues and
  memberships are not migrated or rewritten.
- Operational ownership is active `venue_members(role=OWNER)`. The commercial account's
  `primary_owner_user_id` is not a primary operational-owner concept.
- Approval, submit and create/link seed zero menu categories. A linked venue appears only after an
  authoritative membership reload and is never auto-selected by submit/link.
- Out of scope remains: `NEEDS_INFO`, primary operational owner, existing-venue chooser, commercial
  account transfer, billing redesign, support/analytics/media/R2, and migration/schema redesign.

## 3. Implemented surfaces

### Shared backend and Telegram

- `VenueOnboardingService` is the single application writer/orchestrator used by Telegram, Venue
  Mini App and Platform Mini App adapters.
- Under the applicant database lock, submit compares the normalized venue name, city, contact and
  optional comment across all `PENDING` and `APPROVED`-unlinked rows. An exact canonical retry
  returns the authoritative row with zero insert/audit; a distinct tuple creates another request.
- Request submit/edit/cancel/decision/terms/close and create-link mutations use transaction-bound
  audit with safe ids/state/source. Exact no-op updates emit no false success audit.
- Telegram application and Platform decision flows use the shared service. The quota dashboard still
  exposes account/limit management, while the new-venue action is the legacy alias into application.

### Venue Mini App

- `Мои заведения` shows authoritative OWNER membership cards and own application history/status.
- Owner can submit, edit or cancel under the same contract as Telegram. Approved-unlinked copy makes
  clear that approval is not yet access.
- Manager/Staff direct route access is denied. Opening/selecting a venue is an explicit user action.

### Platform Mini App

- Top-level `Заявки`, `Кальянные`, `Владельцы` workspaces are implemented.
- Requests support list/detail, approve/reject/close, commercial terms and retry-safe create/link.
- Venue rows show city and every safe active OWNER identity. Owner list/detail aggregates active
  membership portfolios without inventing primary ownership.
- Existing direct Platform `DRAFT` creation remains the separate ownerless Platform lifecycle tool;
  existing billing, support, placements and analytics behavior is unchanged.

## 4. Current evidence and release gates

Local validation is complete for this worktree state:

- the exact route/security selector and its XML enforcement passed `1247 / 1247 / 0 / 0 / 0`
  (discovered / executed / skipped / failures / errors);
- the mandatory PostgreSQL Testcontainers vector passed `8 / 14 / 2 / 44 / 9 / 7` (`84 / 84`),
  with zero skipped, failures or errors, including deterministic cross-surface submit, decision and
  create/link contention;
- the onboarding repository/Venue routes/Platform routes/Telegram flow/legacy-state/keyboards
  actual counts are `13 / 8 / 15 / 18 / 552 / 169`, with exact XML enforcement;
- backend `compileKotlin` and `ktlintCheck`, the Mini App production build and the full Playwright
  smoke (`191 / 191`, including ownership onboarding `15 / 15`) passed.

Required before release closure:

1. independent review and green GitHub Actions for the release HEAD;
2. staging deploy and the consolidated Telegram/Venue/Platform/RBAC/privacy/retry smoke in
   `docs/TESTING_QA_SMOKE_STRATEGY.md`.

## 5. Review and registry

The bounded implementation has local regression evidence, but it is not an independent-review or
release-closure claim. The next required gate is a short independent review of this current diff;
green Actions, staging deploy and consolidated staging smoke remain release requirements.

- **P2/future:** `ONBOARDING-H2-001`, `ONBOARDING-TG-CONFIRM-001` and
  `ONBOARDING-DECISION-RETRY-001` remain open under their explicit future triggers; the other
  existing P2 backlog remains governed by its canonical docs. This epic adds no PostgreSQL migration,
  primary-owner, billing, support, analytics, media/R2 or existing-venue-linking scope.

Onboarding and related registry (canonical details remain in the relevant docs):

- `MENU-CONC-001` — `OPEN`;
- `MENU-TEST-002` — `OPEN`;
- `BOOTSTRAP-QA-001` — `DONE`;
- `BOOTSTRAP-TEST-002` — `DONE`;
- `OWNERSHIP-MODEL-001` — `DONE`;
- `ONBOARDING-FIRST-VENUE-001` — `DONE`;
- `ONBOARDING-APPLICATION-EQUIVALENCE-001` — `DONE`;
- `ONBOARDING-ROUTE-COVERAGE-001` — `DONE`;
- `ONBOARDING-UX-A11Y-001` — `DONE`;
- `ONBOARDING-TG-LEGACY-STATE-001` — `DONE`;
- `ONBOARDING-CANON-UNICODE-001` — `DONE`;
- `ONBOARDING-VENUE-ROUTE-COVERAGE-001` — `DONE`;
- `ONBOARDING-PG-FIRST-APPLICANT-001` — `DONE`;
- `ONBOARDING-A11Y-CREATE-LINK-FOCUS-001` — `DONE`;
- `ONBOARDING-OWNER-PLURAL-001` — `DONE`;
- `ONBOARDING-H2-001` — `OPEN`;
- `ONBOARDING-TG-CONFIRM-001` — `OPEN`;
- `ONBOARDING-DECISION-RETRY-001` — `OPEN`.

The `DONE` entries have the required local implementation/test evidence. `ONBOARDING-H2-001`
tracks the pre-existing packaged-H2 legacy status-check defect; the other two onboarding P2s retain
the current Telegram UX and describe non-partial response-loss presentation only. PostgreSQL
production migrations and this epic's no-migration verdict are unaffected. The epic itself is still
release-open until the P1 independent review, green Actions and staging evidence above.

## 6. Worktree constraints

Do not stage, commit, push or deploy. Do not read/apply/change stash. The pre-existing untracked
`scripts/dev/` area remains untouched and must not be staged.
