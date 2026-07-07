# Platform Cockpit Model

Дата актуализации: 2026-07-06.

Статус: **current product reference** for Platform Mode. Use this document together with `docs/UPDATED_PRODUCT_AI_ROADMAP.md`, `docs/COMMUNICATION_MODEL.md` and `docs/ANALYTICS_EVENTS.md` before opening new Platform, billing, support or analytics tasks.

## Scope

Platform Mode is the operator cockpit for the whole marketplace. It is separate from Venue Mode: Platform Owner can manage platform-owned objects and commercial state, but does not automatically bypass venue-specific RBAC for ordinary venue operations.

| Area | Current implementation status | Product rule |
| --- | --- | --- |
| Venues | Platform Mini App can list/open venue details and run implemented lifecycle actions. | Platform cockpit is the source of truth for marketplace-level venue state. |
| Onboarding requests | Telegram bot remains the richer surface for request intake, approval and commercial terms. Mini App is partial. | Requests should become a backend-backed Platform cockpit queue before they are promised as Mini App parity. |
| Owner / access | Owner invite, accepted Telegram deep links, active OWNER membership list and last-owner-safe OWNER revoke are smoke-passed. | Runtime venue ownership is `venue_members(role=OWNER)`, not legacy owner linkage alone. |
| Billing / subscriptions / invoices | Manual/fake billing cockpit, subscription overview, invoice ensure, manual mark-paid, next-period invoice and courtesy days are staging-smoked. | GET overviews are read-only; money/state mutations require explicit POST actions and audit. |
| Support Center | Support Tickets MVP is smoke-passed for `SUPPORT_TICKET`, including platform-only and venue-transferred tickets. | Platform sees support tickets, not ordinary `VENUE_CHAT`. |
| Analytics / audit | Audit rows exist for several critical operations; broad Platform analytics dashboards are future work. | `docs/ANALYTICS_EVENTS.md` is the source for event names/KPIs; Platform cockpit should show reliable operational metrics only after event semantics are stable. |
| Growth / placements | Guest growth/retention is specified in `docs/GROWTH_RETENTION.md`; paid placement and promotion boosting are future. | No paid placement in the MVP; if implemented later, promoted content must be labeled and backed by billing, moderation and analytics. |
| Risk / health | Billing state, venue availability and support queue status are partially visible. | Future cockpit should highlight blocked venues, overdue invoices, support queues and integration health without exposing secrets. |

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

- Do not expose secrets, raw Telegram payloads, provider raw payloads, `.env`, initData, callback payloads or unrelated PII in Platform dashboards, audit payloads or support cards.
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

## Open Platform Gaps

- Onboarding request cockpit in Mini App is still partial compared with Telegram bot.
- Placements cockpit is future/partial.
- Paid placement/promotion boosting is future and must follow `docs/GROWTH_RETENTION.md`: visible ad labels, moderation, billing and analytics are required before launch.
- Platform analytics dashboard is future.
- Event/audit explorer is future/partial and must follow `docs/ANALYTICS_EVENTS.md` payload safety rules.
- Real acquiring provider / Telegram Stars / recurring payments are future.
- Advanced support automation, diagnostics, macros, attachments, CSAT and support analytics are future.
- Lifecycle normalization for `onboarding`, `paused_by_owner`, `suspended_by_platform` and `deletion_requested` requires an explicit migration/product decision if needed.
