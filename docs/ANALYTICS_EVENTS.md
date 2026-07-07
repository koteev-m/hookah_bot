# Analytics And Events Model

Дата актуализации: 2026-07-07.

Статус: **current product reference / SPEC UPDATED**. Analytics implementation is **PARTIAL / needs verification** unless a specific repository, migration, dashboard or smoke result is cited by an implementation task. This document defines target events, KPI formulas, dashboards, audit boundaries and privacy rules for Telegram bot + Mini App.

## Core Rule

Analytics is not the operational source of truth. Domain tables remain authoritative:
- orders, order_batches, tabs and table_sessions;
- bookings;
- staff_calls;
- support_tickets / support messages;
- subscriptions, invoices and payments;
- venue_members, venue settings and menu tables.

Analytics events are immutable facts for reporting, funnels and diagnostics. They must not drive money, access, billing, order state or support state.

## Event Families

| Family | Purpose | Source of truth |
| --- | --- | --- |
| `analytics_events` | Product/operational facts for KPI, funnels and dashboards. | Derived from domain writes and selected client diagnostics. |
| `audit_events` / `audit_logs` | Who changed critical state, when, from what to what. | Written alongside sensitive mutations. |
| `notification_outbox` / delivery events | Technical message delivery, retry and Telegram/provider state. | Outbox/delivery tables and workers. |
| `support_audit` | Ticket status, scope, assignee and escalation changes. | Support domain tables plus safe audit payloads. |
| `billing_events` | Subscription, invoice, payment and provider lifecycle. | Billing/subscription/invoice/payment domain tables. |

## Naming Convention

- Event names use `snake_case`.
- Events are past-tense facts: `order_batch_created`, not `create_order_batch`.
- One event = one meaningful business fact.
- IDs are opaque/internal.
- Timestamps are UTC.
- Venue timezone is used for aggregation/reporting only.
- Do not log raw PII, raw Telegram payloads, raw Mini App initData, card data, provider secrets or message text in analytics events.

Target analytics event envelope:

```text
event_id
event_name
event_ts
source: server | miniapp | bot | worker | platform
actor_user_id nullable
venue_id nullable
table_id nullable
table_session_id nullable
order_id nullable
batch_id nullable
tab_id nullable
booking_id nullable
support_ticket_id nullable
subscription_id nullable
properties json
correlation_id nullable
```

## MVP Server-Side Events

These are the target minimum. Mark each implementation task as DONE only after the event is emitted from the authoritative server-side mutation path and covered by tests/smoke.

Table/session:
- `table_token_resolved`
- `table_session_started`
- `table_session_touched`
- `table_session_expired`
- `table_session_closed`

Order/batch:
- `active_order_created`
- `order_batch_created`
- `order_batch_status_changed`
- `order_batch_rejected`
- `order_closed`
- `fallback_order_created`

Tab/bill:
- `personal_tab_created`
- `shared_tab_created`
- `shared_tab_invite_created`
- `shared_tab_joined`
- `tab_bill_requested`
- `tab_paid`
- `tab_closed`

Staff call:
- `staff_call_created`
- `staff_call_acknowledged`
- `staff_call_completed`
- `staff_call_cancelled`

Booking:
- `booking_created`
- `booking_confirmed`
- `booking_changed`
- `booking_cancelled`
- `booking_expired`
- `booking_no_show`
- `booking_seated`

Support:
- `support_ticket_created`
- `support_ticket_message_created`
- `support_ticket_status_changed`
- `support_ticket_transferred_to_platform`
- `support_ticket_closed`
- `support_ticket_reopened`

Communication:
- `venue_chat_created`
- `venue_chat_message_created`
- `booking_chat_message_created`

Billing:
- `subscription_trial_started`
- `subscription_activated`
- `subscription_past_due`
- `subscription_suspended`
- `subscription_canceled`
- `invoice_created`
- `invoice_paid`
- `invoice_voided`
- `payment_webhook_received`
- `payment_webhook_rejected`

Growth:
- `favorite_venue_added`
- `favorite_venue_removed`
- `visit_recorded`
- `repeat_template_created`
- `repeat_template_applied`
- `feedback_requested`
- `feedback_submitted`
- `promotion_viewed`
- `promo_code_copied`
- `promo_code_redeemed`

## Client-Side Events

Client events are lower-trust UX diagnostics. They help funnels and UI debugging only. They must not drive money, access, billing, order state, support state or venue permissions.

Target client events:
- `miniapp_opened`
- `screen_viewed`
- `catalog_viewed`
- `venue_card_viewed`
- `menu_viewed`
- `item_viewed`
- `item_added_to_cart`
- `cart_viewed`
- `checkout_submit_attempted`
- `checkout_failed`
- `fallback_clicked`
- `support_form_opened`
- `venue_question_clicked`
- `help_opened`

## Audit Events

Audit is separate from analytics. Audit answers who changed critical state; analytics answers what happened for reporting.

Audit required for:
- role granted/revoked;
- owner/manager/staff invite created/accepted/revoked;
- venue status changed;
- venue published/hidden/suspended/archived/deleted;
- subscription override changed;
- invoice manually marked paid;
- menu price changed;
- menu availability changed;
- table QR token rotated;
- order force closed;
- tab reopened;
- support status/assignee changed;
- billing provider config changed.

Audit payload rules:
- `actor_user_id` required;
- target entity required;
- include old/new values for safe fields;
- no message text unless explicitly needed and safe;
- no raw payment secrets;
- no raw Telegram initData;
- no card data.

## KPI Formulas

Guest / in-venue funnel:
- QR Start Rate = `table_token_resolved` / guest venue-card or QR entry impressions where available.
- Table Session Start Rate = `table_session_started` / `table_token_resolved`.
- Mini App Open Rate = `miniapp_opened` / `table_token_resolved` or bot WebApp entry clicks.
- Menu View -> Cart = guests with `item_added_to_cart` / guests with `menu_viewed`.
- Cart -> Batch Submit = `order_batch_created` / carts with `checkout_submit_attempted`.
- Batch Acceptance Rate = accepted batches / `order_batch_created`.
- Delivery Rate = delivered batches / `order_batch_created`.
- Fallback Share = `fallback_order_created` / (`order_batch_created` + `fallback_order_created`).
- TTFO = time from `table_session_started` to first `order_batch_created`.
- Time to Accept = time from `order_batch_created` to first accepted status.
- Time to Deliver = time from `order_batch_created` to delivered status.

Venue operations:
- New batches backlog = current `new` batches not accepted/rejected/cancelled.
- Median accept time = median Time to Accept by venue/period.
- Median deliver time = median Time to Deliver by venue/period.
- Staff call response time = `staff_call_acknowledged` - `staff_call_created`.
- Reject rate = rejected batches / created batches.
- Out-of-stock checkout failure rate = checkout failures caused by unavailable item/option / checkout attempts.
- Staff-call completion rate = completed staff calls / created staff calls.
- Booking response time = first venue booking status response - `booking_created`.

Booking:
- Booking submit rate = `booking_created` / booking form starts where tracked.
- Booking confirm rate = `booking_confirmed` / `booking_created`.
- Booking no-show rate = `booking_no_show` / confirmed or changed bookings.
- Booking seated rate = `booking_seated` / confirmed or changed bookings.
- Time to confirm = `booking_confirmed` - `booking_created`.
- Reminder confirmation rate = attendance confirmations / sent reminders. This is future/rollout-gated where reminders remain disabled by default.

Support:
- TTFR = first non-guest support reply - `support_ticket_created`.
- TTR = resolved/closed timestamp - `support_ticket_created`.
- Escalation rate = transferred/escalated tickets / created tickets.
- Reopen rate = reopened tickets / closed tickets.
- CSAT = post-resolution rating average/completion rate. Future unless CSAT exists.
- Top issue themes = category/tag/theme distribution; do not derive from raw message text unless a safe classifier pipeline is approved.

Platform:
- WAAV = Weekly Active Accepted Venues: venues with at least one accepted/delivered order batch in the last 7 days.
- Venues created/published/suspended = counts of venue lifecycle facts.
- Onboarding completion = published or first accepted batch / approved onboarding requests.
- Time to first accepted batch = first accepted batch - venue created/published.
- Active/trial/past_due/suspended venues = subscription state snapshot by period.
- MRR manual/card/stars = recurring revenue by provider; card/stars are future until provider rollout.
- Support ticket volume by venue = support tickets created by venue/period/category.

Growth:
- Favorite rate = guests adding favorite / venue card viewers.
- Repeat visit rate = guests with more than one `visit_recorded` in period.
- Review completion = `feedback_submitted` / `feedback_requested`.
- Promo view/redeem = `promo_code_redeemed` / promotion views or copied codes.
- Repeat template usage = `repeat_template_applied` / `repeat_template_created`.
- Opt-in notification rate = opted-in guests / eligible guests.

## Dashboards By Role

Guest:
- No analytics dashboard in MVP.
- Later: history/profile-facing summaries only, not operator analytics.

Venue Owner:
- Today/7d/30d orders.
- Accepted/delivered batches.
- Revenue estimate only if bill data is reliable.
- Average accept/deliver time.
- Top items.
- Stop-list pain points.
- Staff calls.
- Bookings.
- Support ticket summary.
- Growth metrics later.

Venue Manager:
- Shift dashboard.
- Queue backlog.
- SLA timers.
- Active staff calls.
- Bookings waiting response.
- No billing metrics.

Staff:
- Operational queue only.
- No business analytics by default.

Platform Owner:
- WAAV.
- Venue health.
- Onboarding funnel.
- Billing state.
- Support load.
- Risk indicators.
- Platform-wide fallback/reject/SLA.
- Event/audit explorer as future/partial.

## Privacy And Security

- No raw PII in analytics events.
- Telegram user id can be an internal operational id; analytics exports should use pseudonymized user id.
- No raw initData.
- No payment secrets or card data.
- Message text should not be copied into analytics events.
- Support/chat text belongs in domain message tables with RBAC, not analytics events.
- Client events are untrusted; server validates critical facts.
- Rate-limit event ingestion if a public endpoint exists.
- Correlation IDs are safe opaque IDs.

## Current Implementation Vs Target

| Area | Current implementation from docs | Target product model | Gap / future note |
| --- | --- | --- | --- |
| Analytics events table/repository | Docs mention `AnalyticsEventRepository` and event writes, but coverage needs verification. | Unified `analytics_events` envelope for server facts and client diagnostics. | Needs implementation audit before DONE. |
| Table/session events | Table session creation/TTL/exit behavior exists; analytics emission coverage needs verification. | Emit `table_token_resolved`, `table_session_started/touched/expired/closed`. | Required for visit history and QR funnel. |
| Order/batch events | Order/batch routes and some order audit exist; analytics coverage needs verification. | Emit active order, batch create/status/reject/closed and fallback facts. | Needed for QR->order, TTFO, accept/deliver KPIs. |
| Booking events | Booking lifecycle and reminders exist in docs; event coverage needs verification. | Emit booking lifecycle events and reminder confirmation metrics where enabled. | Booking analytics remains partial/future until confirmed. |
| Support events | Support-ticket audit exists for status/scope/assignment/escalation where implemented. | Emit support analytics events without raw message text. | CSAT and broad support analytics are future. |
| Billing events | Billing audit exists for checkout ensure, mark-paid and courtesy days. Provider/webhook analytics needs verification. | Emit subscription, invoice and payment webhook facts. | Card/Stars metrics future until provider rollout. |
| Growth events | Growth product is `SPEC UPDATED / PARTIAL-FUTURE`. | Emit favorite, visit, repeat, feedback and promotion facts. | Blocked by Growth implementation and visit foundation. |
| Dashboard UI | Venue read-only stats exist; Platform analytics dashboard is future/partial. | Role-specific dashboards from reliable events. | Advanced dashboards after event reliability. |
| Audit logs | Several critical audit rows exist where implemented. | Audit all critical state changes with actor and safe old/new fields. | Fill gaps for menu price, QR rotate, force close, tab reopen, provider config. |
| Notification outbox | Telegram outbox/workers exist and expose operational metrics. | Delivery state is technical telemetry, separate from analytics facts. | Do not treat outbox rows as business conversions. |

## Roadmap Status

- Analytics/events spec: `UPDATED`.
- Analytics implementation: `PARTIAL / needs verification`.
- Platform analytics dashboard: `FUTURE/PARTIAL`.
- Booking/support/growth events: `PARTIAL/FUTURE` unless implementation evidence exists.
- Growth remains blocked by order/session/tab and visit history stability.
- Advanced dashboards should wait until event emission, payload safety and aggregation semantics are reliable.

## Smoke / Acceptance Checklist

1. QR scan emits or records `table_session_started`.
2. Add-batch emits `order_batch_created`.
3. Batch status change emits `order_batch_status_changed`.
4. Staff-call create emits `staff_call_created`.
5. Booking status change emits the correct `booking_*` event if implemented; otherwise remains marked future.
6. Support ticket create/status/transfer emits `support_*` event if implemented; otherwise remains marked future.
7. Subscription state change emits `subscription_*` event if implemented.
8. Audit log exists for role/status/billing dangerous changes.
9. Analytics event payload contains no message text, raw initData, payment secrets or card data.
10. Platform Owner can see/export relevant analytics only where implemented.
11. Venue Owner cannot see another venue analytics.
12. Staff does not see platform analytics.
