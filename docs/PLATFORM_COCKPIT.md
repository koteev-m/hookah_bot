# Platform Cockpit Model

Дата актуализации: 2026-08-15.

Статус: **current product reference** for Platform Mode. Platform guest QR and the bounded
Platform/Venue onboarding and ownership cockpit are **DONE / MVP / STAGING-SMOKE-PASSED** for their
recorded release scopes. This does not make the whole product production-ready or close broader
Platform/Guest parity. Use this document together with `docs/UPDATED_PRODUCT_AI_ROADMAP.md`,
`docs/COMMUNICATION_MODEL.md`, `docs/SECURITY_RBAC_MATRIX.md` and `docs/ANALYTICS_EVENTS.md` before
opening new Platform, billing, support or analytics tasks.

## Scope

Platform Mode is the operator cockpit for the whole marketplace. It is separate from Venue Mode: Platform Owner can manage platform-owned objects and commercial state, but does not automatically bypass venue-specific RBAC for ordinary venue operations.

Ordinary venue operations such as orders, staff calls, booking queues, menu/stop-list, tables/QR, staff-chat and venue stats are defined in `docs/VENUE_OPERATIONS.md`; booking lifecycle details are defined in `docs/BOOKING_LIFECYCLE.md`; Telegram fallback/staff-chat behavior is defined in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; QA/staging policy is defined in `docs/TESTING_QA_SMOKE_STRATEGY.md`; deployment/runbook operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`; Platform Mode should not become the normal venue operations workspace.

| Area | Current implementation status | Product rule |
| --- | --- | --- |
| Venues | Platform Mini App can list/open venue details and run implemented lifecycle actions. | Platform cockpit is the source of truth for marketplace-level venue state. |
| Onboarding requests | Telegram and Venue Mini App submit through one shared contract; Platform Mini App has actionable `Заявки`, `Кальянные`, `Владельцы`, including retry-safe create/link. The bounded release is staging-smoke-passed. | Telegram and both Mini Apps use one backend application/orchestration contract; no second onboarding engine. |
| Owner / access | Owner invite, accepted Telegram deep links, active OWNER membership list and last-owner-safe OWNER revoke are smoke-passed. | Runtime venue ownership is `venue_members(role=OWNER)`, not legacy owner linkage alone. |
| Billing / subscriptions / invoices | Manual/fake billing cockpit, subscription overview, invoice ensure, manual mark-paid, next-period invoice and courtesy days are staging-smoked. | GET overviews are read-only; money/state mutations require explicit POST actions and audit. |
| Support Center | Support Tickets MVP is smoke-passed for `SUPPORT_TICKET`, including platform-only and venue-transferred tickets. | Platform sees support tickets, not ordinary `VENUE_CHAT`. |
| Controlled Guest QR test | Exact Platform Owner can explicitly confirm one guarded Telegram table QR; activation and every later table-bound mutation use transaction-bound final authorization, and visit exit does not depend on current Guest availability. | Five-minute opaque process-local pending in single-instance long-polling Phase 1, shared exit/mutation chat-context lock, public Guest guards and fail-closed audit-before-apply grant no Venue authority or persistent impersonation. A callback missing on another instance fails closed. |
| Analytics / audit | Audit rows exist for several critical operations; broad Platform analytics dashboards are future work. | `docs/ANALYTICS_EVENTS.md` is the source for event names/KPIs; Platform cockpit should show reliable operational metrics only after event semantics are stable. |
| Growth / placements | Guest growth/retention is specified in `docs/GROWTH_RETENTION.md`; paid placement and promotion boosting are future. | No paid placement in the MVP; if implemented later, promoted content must be labeled and backed by billing, moderation and analytics. |
| Risk / health | Billing state, venue availability and support queue status are partially visible. | Future cockpit should highlight blocked venues, overdue invoices, support queues and integration health without exposing secrets. |

Menu `FEATURED` / `TOP_LIST` is venue-managed menu presentation, not Platform paid placement. Menu governance is defined in `docs/MENU_OPTIONS_STOPLIST.md`; paid placement remains a separate future Platform/Growth capability.

## Venue Lifecycle

Current implementation uses the `VenueStatus` enum:

| Current status | Meaning in current code | Notes |
| --- | --- | --- |
| `DRAFT` | Created/unpublished venue. | Legacy `draft` and `onboarding` rows were normalized to `DRAFT`. |
| `PUBLISHED` | Guest-visible venue when subscription also allows access. | Publishing requires at least one owner-like user. |
| `HIDDEN` | Hidden from public guest flows. | Current `hide` action applies from `PUBLISHED`. |
| `PAUSED` | Owner/platform pause state in current implementation. | Legacy `paused_by_owner` maps to `PAUSED`; ownership semantics are not split in DB. |
| `SUSPENDED` | Platform suspension in current implementation. | Legacy `suspended_by_platform` maps to `SUSPENDED`; billing-created versus manual suspension is not yet distinguished. |
| `ARCHIVED` | Archived venue, not part of normal active operation. | Current publish action can restore from `ARCHIVED`; smoke docs must keep copy explicit. |
| `DELETED` | Soft-deleted venue with `deleted_at`. | Normal lists should not include deleted venues. Hard delete is not the normal flow. |

Target product model:

| Target state | Current mapping | Follow-up |
| --- | --- | --- |
| `draft` | `DRAFT` | Already represented. |
| `onboarding` | currently folded into `DRAFT` | Add only if onboarding needs a separate cockpit lane. |
| `published` | `PUBLISHED` | Already represented. |
| `hidden` | `HIDDEN` | Already represented. |
| `paused_by_owner` | currently `PAUSED` | Split from platform pause only if product needs owner-controlled pause semantics. |
| `suspended_by_platform` | currently `SUSPENDED` | Needed before broad auto-reactivation or billing-created suspension recovery. |
| `archived` | `ARCHIVED` | Already represented. |
| `deletion_requested` | currently no separate state; old rows mapped to `DELETED` | Add only if a legal/data retention review flow is required. |
| `deleted` | `DELETED` | Already represented as soft delete. |

Implementation note: until a migration explicitly splits `PAUSED` / `SUSPENDED`, docs and UI copy must describe the current enum honestly and avoid promising separate owner-vs-platform suspension recovery.

## Billing / Subscriptions / Invoices

Implemented or partial:

- Venue subscription statuses: `trial`, `active`, `past_due`, `canceled`, `suspended`, `suspended_by_platform`.
- Guest availability blocks `canceled`, `suspended`, `suspended_by_platform` and unknown subscription state; `past_due` is documented as non-blocking for guest ordering/booking.
- Platform can manage subscription settings, per-venue price and future price schedule where implemented.
- Platform and Venue Owner billing overviews are read-only on GET.
- Invoice/checkout creation uses explicit POST ensure actions.
- Manual/fake invoices avoid exposing provider-internal fake URLs to users.
- Platform Owner can manually mark an invoice paid; audit action includes `BILLING_MARK_PAID`.
- Platform Owner can create/reuse next-period invoices from effective paid-through + 1 day.
- Courtesy/free days are stored in `billing_adjustments` with kind `COURTESY_DAYS`, require a reason, write `BILLING_COURTESY_DAYS_ADDED`, and do not mutate paid invoice history.
- Checkout ensure audit uses `BILLING_CHECKOUT_ENSURE`.

Future or partial:

- Real acquiring provider rollout is not closed by the manual/fake billing MVP.
- `GenericHmacBillingProvider` exists as an integration base, but production provider secrets, webhook verification, idempotency and smoke are provider-specific work.
- Telegram Stars is future unless a dedicated Stars flow is implemented and smoked.
- Automatic recurring card payments are future.
- Invoice void/reissue for courtesy conflicts with already-open future invoices is future.
- Distinguishing billing-created suspension from manual `SUSPENDED_BY_PLATFORM` is required before broad auto-reactivation.
- Venue-facing payment UX is partial: Venue Owner can see subscription/payment state, but real self-serve provider payment depends on the provider rollout.
- Payment/support issues should be raised as `SUPPORT_TICKET` and handled in Platform Support Center when platform-scoped.

## Support Center

Platform Support Center is for `SUPPORT_TICKET` only. It must not become an all-message inbox.

- Platform Owner can see platform-only support tickets and venue tickets transferred with `Передать платформе`.
- Platform Owner can reply/close support tickets where implemented.
- Venue-related support belongs to Venue first unless transferred or categorized as technical/platform/billing.
- Technical/Mini App/bot/QR/access/payment problems can route directly to Platform.
- Staff never sees support tickets in MVP.
- Support ticket create/reply paths do not post to staff-chat.
- Ordinary `VENUE_CHAT` remains hidden from Platform unless future policy explicitly changes.

Future support features:

- SLA automation and auto-escalation.
- Macros/canned replies.
- Attachments and diagnostic reports.
- CSAT.
- Support analytics: TTFR, TTR, escalation rate, reopen rate, top issue themes and queue aging.
- Individual assignees beyond venue/platform scope.

## Analytics / Audit / Events

Canonical analytics/event model: `docs/ANALYTICS_EVENTS.md`.

Current audit/event foundation is partial and operational:

- Venue lifecycle/status changes write platform status audit evidence.
- Owner invite create/accept and OWNER revoke write audit evidence.
- Billing checkout ensure, manual mark-paid and courtesy days write audit evidence where implemented.
- Support status/scope/assignment/escalation and message-add audit exists for support-ticket operations.
- Staff-call ACK/DONE audit exists from Venue Mini App and Telegram staff-chat callbacks.
- Order audit exists for several venue order mutations.
- Controlled Guest QR confirmation writes `PLATFORM_GUEST_QR_TEST_CONFIRMED` before atomic Guest context activation, using the standard actor field plus safe `venueId`, `tableId` and `source=TELEGRAM_QR_TEST` only. It records the confirmation decision only and is not `GUEST_CONTEXT_APPLIED`; activation may subsequently roll back without making that truthful audit invalid.

Needed Platform cockpit reporting:

- WAAV: weekly active accepted venues.
- Venue counts by lifecycle, city, subscription state and risk state.
- Billing metrics: active/trialing/past_due/suspended venues, MRR, paid-through risk, open invoices, overdue invoices, provider/card/Stars split after providers exist.
- Support metrics: open tickets, platform-assigned queue, transferred tickets, TTFR, TTR, escalation rate, reopen rate, CSAT and top issue themes.
- Future growth metrics: favorite rate, repeat visit rate, promo view/redeem, review completion, opt-in/unsubscribe and abuse/rate-limit indicators.
- Onboarding funnel: lead/request status, approval time, activation time and owner invite acceptance.
- Platform-wide fallback/reject/SLA metrics after event emission is reliable.
- Operational health: webhook/outbox backlog, billing webhook failures, Telegram delivery failures, staff-chat link health and Mini App error rate.

Safety rules:

- Platform permissions, dangerous actions and audit expectations are canonical in `docs/SECURITY_RBAC_MATRIX.md`.
- Do not expose secrets, raw Telegram payloads, provider raw payloads, `.env`, initData, callback payloads or unrelated PII in Platform dashboards, audit payloads or support cards.
- Platform Owner Telegram menu parity remains partial. The bounded Guest QR escape is staging-closed: exact actor/chat opaque TTL pending, single-transaction activation with final token/venue/subscription/table revalidation, server-owned Mini App re-entry guard, availability-independent exit and ordinary Guest `mode=guest` stay in regression.
- Prefer safe aggregate metrics and opaque ids unless an operator needs a specific entity id for support.
- Client events are lower-trust UX diagnostics and must not drive money, access, billing, order state or venue lifecycle.

## Platform & Venue Onboarding / Ownership Cockpit

Status: **PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT / DONE / MVP /
STAGING-SMOKE-PASSED** for release HEAD `e35def99ea8429462e5fdaaeee914f57da72e775`.

### Implemented behavior

An authenticated Telegram user can submit a first or additional venue, including when they have no
active OWNER membership. An existing active operational Owner can also submit an additional venue
from Venue Mini App. Both adapters use one shared onboarding service and repository writer; the
applicant and source are server-derived. Manager, Staff, foreign and Platform-only identities have
no Venue Mini App application authority.

The application stores only venue name, city, contact and optional comment and remains one of exactly
`PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`. Applicant-row locking serializes simultaneous
cross-surface submit. The writer compares the server-side canonical tuple of normalized venue name,
city, contact and optional comment against every `PENDING` and `APPROVED`-unlinked row. An exact retry
returns the authoritative existing request with no insert/audit; a distinct tuple creates a separate
`PENDING` request and one submit audit. `REJECTED` and `CANCELLED` rows do not block a new application.

Venue Mini App exposes `Мои заведения`, authoritative OWNER cards and own application history plus
submit/edit/cancel. Manager/Staff and unaffiliated/Platform-only actors are denied. Approval alone
remains non-seeding and creates no access. After successful create/link, the next authoritative
`/api/venue/me` reload adds the membership/card/selector option; the venue is not auto-selected.

Platform Mini App exposes `Заявки`, `Кальянные`, `Владельцы`. Requests support safe list/detail,
approve/reject/close, commercial terms and create/link; venue rows include city and every safe active
OWNER identity; owners are aggregated from operational memberships with portfolio drill-down. The
existing direct Platform ownerless `DRAFT` tool and existing detail/invite/revoke/lifecycle surfaces
remain available.

Telegram and both Mini App adapters call the same writer/orchestrator. Platform create/link locks the
applicant, request and commercial account in a fixed order, checks quota only at this boundary and
commits the `DRAFT`, OWNER membership, commercial settings, request link and safe audits together.
Failure rolls the whole transaction back; lost-response/concurrent retry returns the linked venue.
For a first applicant without a commercial account, create/link preserves the former authoritative
connection-request semantics: create the account with the existing default limit of one and apply
the already-recorded terms; it does not invent a new default quota or account policy.
`owner_quota_create_start` and persisted legacy direct-create dialogs only enter the application
flow and perform no direct venue/member/link/selection writes.

### Independent review closure evidence

Five bounded review findings were closed before release without expanding the product scope:

- `ONBOARDING-CANON-UNICODE-001`: the production tuple applies Unicode NFKC, collapses the full
  Unicode `White_Space` class, trims, lowercases with `Locale.ROOT` and maps a whitespace-only
  optional comment to `null`. Repository and PostgreSQL tests prove Unicode-equivalent retries keep
  one physical request and one submit audit, while a real non-whitespace difference creates another.
- `ONBOARDING-VENUE-ROUTE-COVERAGE-001`: production-module Venue route tests separately prove exact
  retry, a distinct second application in the own list, server-owned applicant/actor/source/entry
  policy/link authority despite spoof-like extra fields, safe `503 DATABASE_UNAVAILABLE` rollback,
  and the exact `APPROVED`-unlinked retry response with no second `PENDING` row.
- `ONBOARDING-PG-FIRST-APPLICANT-001`: a users-only PostgreSQL fixture starts with zero owner account,
  venue, membership, subscription, request link, selected venue and menu category. Concurrent
  create/link produces exactly one default commercial account with `allowedVenuesCount=1`, one
  linked `DRAFT`, OWNER membership, settings and subscription; retry returns the same authority,
  while selected context and menu categories remain absent.
- `ONBOARDING-A11Y-CREATE-LINK-FOCUS-001`: successful create/link and linked retry wait for the
  authoritative venue detail and focus its stable `h2`; disposed or replaced screens cannot reclaim
  focus.
- `ONBOARDING-OWNER-PLURAL-001`: owner list and detail use one Russian venue-count helper, with
  focused assertions for `1`, `2`, `5`, `11`, `21`, `22` and `25`.

Measured local backend evidence is repository `13`, Venue routes `8`, Platform routes `15`, Telegram
`18 / 552 / 169`, and PostgreSQL onboarding concurrency `7`. The exact route/security aggregate is
`1247`; the mandatory PostgreSQL vector is `8 / 14 / 2 / 44 / 9 / 7` (`84` total). Focused Mini App
assertions cover focus restoration and pluralization. For the release HEAD, the user confirmed fully
green GitHub Actions, staging deploy, the consolidated onboarding/ownership smoke and cleanup. The
smoke covered first and additional applications, exact-versus-distinct retry, Platform request/
venue/owner visibility, exactly-one create/link and OWNER membership, explicit venue selection,
legacy quota-flow convergence, multi-owner portfolios, first-applicant baseline limit `1`, and
server-derived applicant/actor/source. Local GitHub CLI authentication is invalid, so Actions are
recorded as user-confirmed rather than independently queried in this docs-only closure.

### Canonical contract

Venue Owner Mini App receives `Мои заведения`: current venue cards, entry to the existing selector,
`Добавить заведение`, own request list/detail and submission/edit/cancel through the same domain
contract as Telegram. Expose only the actual four states and explain approved-but-unlinked state.
Exact canonical repeat returns the matching authoritative request; a different venue name, city,
contact or comment creates another application. Implementation serializes simultaneous exact and
distinct submissions without relying on the client. After linking, the next authoritative access
refresh adds the venue card and selector option. Telegram remains the acquisition adapter for first
applicants and also supports additional venues.

Platform Mini App receives one top-level onboarding/ownership workspace with:

1. `Заявки`: pending/actionable list, detail, approve/reject, commercial terms and current
   create-new-DRAFT-and-link action; no existing-venue chooser is implied;
2. `Кальянные`: name, city, lifecycle status, existing subscription/onboarding summary when present,
   all-owner count/names and transition to the existing venue detail;
3. `Владельцы`: safe platform-visible identity, venue count, counts by venue status, linked venues,
   search/filter and owner detail → venue list → existing venue detail.

Existing placements, support, analytics and billing capabilities stay reachable and unchanged;
this epic does not redesign them. Request decisions and create/link must use the same server-side
orchestration, state transitions, recovery/idempotency rules and audit policy from both Telegram and
Mini App. Approval remains non-seeding and no menu writer is added.

### API, repository and UI inventory

- Reuse `VenueConnectionRequestRepository` for the request record/state and add authenticated
  Venue-own and Platform-wide route adapters; do not duplicate its SQL in route or UI code.
- Reuse `/api/venue/me`, `VenueRoutes.kt`, `venueApi.ts` and `venueApp.ts` for membership cards,
  selector refresh and the owner workspace.
- Reuse `PlatformRoutes.kt`, `PlatformVenueRoutes.kt`, `PlatformVenueRepository`,
  `PlatformVenueMemberRepository`, `PlatformUserRepository` and `VenueOwnerAccountRepository` for
  venue/member/user/commercial facts. Extract the Telegram request decision/create-link sequence
  from `TelegramBotRouter.kt` into one backend service used by both surfaces.
- Extend `platformApi.ts`, `platformDtos.ts`, `platformApp.ts`, `platformVenuesList.ts` and
  `platformVenueDetail.ts`; add narrowly named request/owner screens only where existing screen
  composition cannot express the workspace.

### Ownership, RBAC and privacy

Operational ownership is every active `venue_members(role=OWNER)` row. Multiple owners per venue
and multiple venues per owner are valid and must be displayed without choosing a primary owner.
`venue_owner_accounts.primary_owner_user_id` is commercial quota/account ownership, not an
operational primary membership. The Telegram minimum-user-id quota lookup is only a current
heuristic and must not become API or UI semantics.

An authenticated Telegram applicant reads and mutates only their own request; active Venue Owner
Mini App users read and mutate only their own requests. Manager/Staff receive no Venue Mini App
application authority, but keep the ordinary authenticated Telegram acquisition entry. Platform
Owner reads all venues, owners and requests. Actor comes from validated server
session/current Telegram user; owner user id, request owner, linked venue and audit actor/source are
never client-controlled. Applicant contact/comment stays visible only to that applicant and exact
Platform Owner scope. Platform owner identity may use current allowlisted display name/username and
opaque/internal id; do not expose raw Telegram payload/initData, phone/private fields, secrets or
unrelated PII. Approve/reject/create/link and owner assignment remain explicit, audit-aware dangerous
actions with safe bounded payloads.

### Scope and migration verdict

Owner aggregation is an existing `users` + active `venue_members` + `venues` query. Request states,
link field, membership and commercial account tables already exist. **NO_MIGRATION_EXPECTED**.
Adding `NEEDS_INFO`, inventing primary-owner membership, commercial-account transfer/redesign, a new
venue lifecycle, billing/support/analytics/media/R2/menu work and a broad navigation redesign are
explicitly out of scope.

## Platform Smoke Checklist

Use this as the Platform-specific part of release smoke:

1. Platform Owner opens Platform Mode and `/api/platform/me` resolves by canonical Telegram owner id.
2. Non-platform user receives 403 for Platform Mode.
3. Platform Owner sees venue list and opens venue detail.
4. Venue lifecycle actions require explicit action/confirmation and dangerous actions are audited where implemented.
5. Deleted venues do not appear in normal platform/owner/guest lists.
6. Platform Owner creates OWNER invite; accepted Telegram deep link grants OWNER for the intended venue.
7. Platform Owner lists active OWNER memberships and cannot revoke the last active OWNER.
8. Revoked OWNER loses venue access and `VENUE_OWNER_REVOKE` audit exists.
9. Billing overview GET is read-only and does not create invoices, checkout links, lifecycle rows or adjustments.
10. Platform Owner creates/reuses current or next invoice via explicit POST; repeat next ensure does not duplicate.
11. Manual mark-paid writes audit and updates human paid-through/next-payment copy.
12. Courtesy days require reason, create `billing_adjustments`, write `BILLING_COURTESY_DAYS_ADDED` and do not mutate paid invoice rows.
13. Venue Owner sees adjusted subscription state; Venue Owner/Manager/Staff cannot mark paid or add courtesy days.
14. Platform Owner opens `Обращения` and sees platform-only or transferred `SUPPORT_TICKET`.
15. Platform Owner does not see ordinary `VENUE_CHAT`.
16. Platform Owner replies/closes support tickets where implemented.
17. Staff does not see Platform Mode or Platform Support Center.
18. Audit payloads contain safe ids/status/scope/reason fields and no secrets/raw provider or Telegram payloads.
19. Platform Owner `/start` without token shows Platform menu only when no active confirmed Guest context exists. With active context it keeps Guest routing and shows the Guest table menu or safe `Завершить визит` instruction.
20. Valid public table QR shows safe venue/table labels and exact confirm/cancel; prompt does not mutate Guest context/session, exit state, persisted dialog, booking draft, cart/draft or success audit.
21. Confirm/cancel and double-confirm conditionally consume one pending reference so exactly one action wins. Cancel produces no audit/activation; winning confirm produces one confirmation audit and one activation attempt.
22. Confirm writes truthful `PLATFORM_GUEST_QR_TEST_CONFIRMED`, then atomically re-resolves and validates token/venue/table/availability/subscription, resolves or touches the session, clears exit/dialog and saves exact context. Injected late failures roll back every authoritative Guest-state change; the audit denotes confirmation, not `GUEST_CONTEXT_APPLIED`.
23. Rotated/disabled token, unavailable table/venue/subscription, audit failure and wrong/expired/canceled/replayed/direct non-Platform callbacks fail closed without raw token/internal-id disclosure. Phase 1 assumes single-instance long-polling; a missing pending record on another instance also fails closed.
24. Exact Platform Owner Mini App create/touch requires the matching active Telegram chat context and no exit marker. Mismatched token/table/venue and old token/session/button after exit create no session or personal tab, touch nothing and do not clear exit.
25. Exact Platform order/bill, staff-call, tab, shift-extension and table-bound support create/read-receipt/reply/status mutations take the same context lock as exit and perform final authorization plus write in one transaction. Support with `tableToken` but no client session derives the live session only from confirmed context; missing/mismatched/exited context has one private side-effect-free denial.
26. `Завершить визит` uses stored context only as teardown identity and clears context/dialog/draft/pending plus preserves exit semantics even after token rotation/revoke, table disable/delete, venue pause/unpublish or subscription block; Platform menu returns.
27. A new QR plus confirmation can enter Guest mode again, and ordinary Guest create/resolve/exit behavior remains unchanged.

## Open Platform Gaps

- The bounded onboarding/ownership cockpit is release-closed; broader lead/readiness funnel,
  moderation/bulk actions and risk/health automation remain separate work in the master inventory.
- Placements cockpit is future/partial.
- Paid placement/promotion boosting is future and must follow `docs/GROWTH_RETENTION.md`: visible ad labels, moderation, billing and analytics are required before launch.
- Platform analytics dashboard is future.
- Event/audit explorer is future/partial and must follow `docs/ANALYTICS_EVENTS.md` payload safety rules.
- Real acquiring provider / Telegram Stars / recurring payments are future.
- Advanced support automation, diagnostics, macros, attachments, CSAT and support analytics are future.
- Lifecycle normalization for `onboarding`, `paused_by_owner`, `suspended_by_platform` and `deletion_requested` requires an explicit migration/product decision if needed.
