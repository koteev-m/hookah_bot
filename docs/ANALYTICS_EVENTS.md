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

Menu/options/stop-list product semantics are defined in `docs/MENU_OPTIONS_STOPLIST.md`.
Venue Mode operational surfaces and role dashboards are defined in `docs/VENUE_OPERATIONS.md`.
Booking lifecycle semantics for booking events, reminders and booking-to-visit conversion are defined in `docs/BOOKING_LIFECYCLE.md`.
Telegram fallback and staff-chat event semantics are defined in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`.
Staff profile, today shift and future staff-tip event semantics are defined in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`.
Testing and smoke strategy for event implementation is defined in `docs/TESTING_QA_SMOKE_STRATEGY.md`. Release/deploy, logs and incident operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`.

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
- `booking_time_proposed`
- `booking_guest_accepted_time`
- `booking_guest_rejected_time`
- `booking_canceled_by_guest`
- `booking_canceled_by_venue`
- `booking_expired`
- `booking_no_show`
- `booking_seated`
- `booking_reminder_scheduled`
- `booking_reminder_sent`
- `booking_reminder_action_clicked`

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

Telegram fallback / staff-chat:
- `bot_start_received`
- `table_token_resolved`
- `bot_table_context_shown`
- `fallback_order_started`
- `fallback_order_confirmed`
- `fallback_order_cancelled`
- `fallback_order_batch_created`
- `staff_call_created_from_bot`
- `staff_chat_link_code_created`
- `staff_chat_linked`
- `staff_chat_unlinked`
- `staff_chat_test_sent`
- `staff_chat_notification_enqueued`
- `staff_chat_notification_delivered`
- `staff_chat_callback_action_clicked`
- `staff_chat_callback_action_rejected`

Menu/options/stop-list:
- `menu_category_created`
- `menu_category_reordered`
- `menu_item_created`
- `menu_item_updated`
- `menu_item_archived`
- `menu_price_changed`
- `menu_item_availability_changed`
- `menu_option_value_availability_changed`
- `menu_media_uploaded`
- `menu_media_removed`
- `shift_check_completed`
- `checkout_failed_out_of_stock`

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

Staff profiles / today shift / staff tips:
- `staff_profile_viewed`
- `staff_shift_viewed`
- `staff_profile_published`
- `staff_shift_marked_active`
- `staff_tip_intent_created` (future)
- `staff_tip_clicked` (future)

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
- menu option schema changed;
- menu media removed;
- mass stop-list update;
- staff stop-list toggle where Staff availability management is allowed;
- shift check completed where implemented;
- table QR token rotated;
- order force closed;
- tab reopened;
- support status/assignee changed;
- billing provider config changed;
- analytics export, if implemented.

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

Use `docs/VENUE_OPERATIONS.md` for which operational metrics belong to Owner, Manager and Staff surfaces.

Booking:
- Booking submit rate = `booking_created` / booking form starts where tracked.
- Booking confirm rate = `booking_confirmed` / `booking_created`.
- Booking cancel rate = (`booking_canceled_by_guest` + `booking_canceled_by_venue`) / `booking_created`.
- Booking no-show rate = `booking_no_show` / confirmed or changed bookings.
- Booking seated rate = `booking_seated` / confirmed or changed bookings.
- Time to confirm = `booking_confirmed` - `booking_created`.
- Reminder confirmation rate = attendance confirmations / sent reminders. This is future/rollout-gated where reminders remain disabled by default.
- Booking-to-visit conversion = `booking_seated` or linked completed visit / `booking_created`.

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

- Security/RBAC source of truth: `docs/SECURITY_RBAC_MATRIX.md`.
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
| Menu/options/stop-list events | Options/flavors parity is documented as smoke-closed; broader event coverage needs verification. | Emit menu create/update/archive, price, availability, media and shift-check facts with safe payloads. | Needed for stop-list pain points, out-of-stock checkout failure rate and menu operations audit. |
| Booking events | Booking lifecycle and reminders exist in docs; event coverage needs verification. Canonical lifecycle is `docs/BOOKING_LIFECYCLE.md`. | Emit booking lifecycle events, reminder facts and booking-chat facts where enabled. | Booking analytics remains partial/future until confirmed. |
| Support events | Support-ticket audit exists for status/scope/assignment/escalation where implemented. | Emit support analytics events without raw message text. | CSAT and broad support analytics are future. |
| Telegram fallback / staff-chat events | Fallback payload, staff-call ACK/DONE and staff-chat diagnostics are closed for current smoke paths; broad event coverage needs verification. | Emit safe bot entrypoint, fallback order, staff-call, link/test, notification and callback facts. | Delivery/outbox telemetry is not business source of truth; payloads must not include message text or secrets. |
| Billing events | Billing audit exists for checkout ensure, mark-paid and courtesy days. Provider/webhook analytics needs verification. | Emit subscription, invoice and payment webhook facts. | Card/Stars metrics future until provider rollout. |
| Staff profile/shift/tip events | Canonical model is `docs/STAFF_PROFILES_SHIFTS_TIPS.md`; no runtime event implementation yet. | Emit safe profile view/publish, today-shift view/mark-active and future tip intent/click facts. | Tip intent/click must not be interpreted as payment confirmation. |
| Growth events | Growth product is `SPEC UPDATED / PARTIAL-FUTURE`. | Emit favorite, visit, repeat, feedback and promotion facts. | Blocked by Growth implementation and visit foundation. |
| Dashboard UI | Venue read-only stats exist; Platform analytics dashboard is future/partial. | Role-specific dashboards from reliable events. | Advanced dashboards after event reliability. |
| Audit logs | Several critical audit rows exist where implemented. | Audit all critical state changes with actor and safe old/new fields. | Fill gaps for menu price, QR rotate, force close, tab reopen, provider config. |
| Notification outbox | Telegram outbox/workers exist and expose operational metrics. | Delivery state is technical telemetry, separate from analytics facts. | Do not treat outbox rows as business conversions. |

## Roadmap Status

- Analytics/events spec: `UPDATED`.
- Analytics implementation: `PARTIAL / needs verification`.
- Security/RBAC matrix: `UPDATED` in `docs/SECURITY_RBAC_MATRIX.md`; analytics exports and audit views must follow that role/scope model.
- Platform analytics dashboard: `FUTURE/PARTIAL`.
- Booking/support/Telegram fallback/staff-chat/growth events: `PARTIAL/FUTURE` unless implementation evidence exists.
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
13. Staff profile/shift analytics contains only public display fields or opaque ids, not `linked_user_id`, private Telegram usernames, phone/email or external payment secrets.
