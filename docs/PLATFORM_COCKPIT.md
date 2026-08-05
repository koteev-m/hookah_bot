# Platform Cockpit Model

Дата актуализации: 2026-08-05.

Статус: **current product reference** for Platform Mode. Platform guest QR status is **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**, schema verdict `NO_MIGRATION`; commit/push, green Actions for the release HEAD, staging deploy and the bounded real Telegram smoke are complete. This does not make the whole product production-ready or close broader Platform/Guest parity. Use this document together with `docs/UPDATED_PRODUCT_AI_ROADMAP.md`, `docs/COMMUNICATION_MODEL.md`, `docs/SECURITY_RBAC_MATRIX.md` and `docs/ANALYTICS_EVENTS.md` before opening new Platform, billing, support or analytics tasks.

## Scope

Platform Mode is the operator cockpit for the whole marketplace. It is separate from Venue Mode: Platform Owner can manage platform-owned objects and commercial state, but does not automatically bypass venue-specific RBAC for ordinary venue operations.

Ordinary venue operations such as orders, staff calls, booking queues, menu/stop-list, tables/QR, staff-chat and venue stats are defined in `docs/VENUE_OPERATIONS.md`; booking lifecycle details are defined in `docs/BOOKING_LIFECYCLE.md`; Telegram fallback/staff-chat behavior is defined in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; QA/staging policy is defined in `docs/TESTING_QA_SMOKE_STRATEGY.md`; deployment/runbook operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`; Platform Mode should not become the normal venue operations workspace.

| Area | Current implementation status | Product rule |
| --- | --- | --- |
| Venues | Platform Mini App can list/open venue details and run implemented lifecycle actions. | Platform cockpit is the source of truth for marketplace-level venue state. |
| Onboarding requests | Telegram bot remains the richer surface for request intake, approval and commercial terms. Mini App is partial. | Requests should become a backend-backed Platform cockpit queue before they are promised as Mini App parity. |
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

- Onboarding request cockpit in Mini App is still partial compared with Telegram bot.
- Placements cockpit is future/partial.
- Paid placement/promotion boosting is future and must follow `docs/GROWTH_RETENTION.md`: visible ad labels, moderation, billing and analytics are required before launch.
- Platform analytics dashboard is future.
- Event/audit explorer is future/partial and must follow `docs/ANALYTICS_EVENTS.md` payload safety rules.
- Real acquiring provider / Telegram Stars / recurring payments are future.
- Advanced support automation, diagnostics, macros, attachments, CSAT and support analytics are future.
- Lifecycle normalization for `onboarding`, `paused_by_owner`, `suspended_by_platform` and `deletion_requested` requires an explicit migration/product decision if needed.
