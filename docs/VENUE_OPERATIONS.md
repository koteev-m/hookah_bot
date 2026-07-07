# Venue Mode Operations Model

Дата актуализации: 2026-07-07.

Статус: **current product reference / SPEC UPDATED**. Core Venue operations are partly smoke-closed across orders, bill display, staff calls, bookings, staff-chat, menu options and settings slices. The full Venue Mode implementation is still **PARTIAL / needs verification** for broad dashboard completeness, shift check, arbitrary stats, all dangerous-action audit coverage, broader settings parity and deep cross-surface e2e.

## Core Rule

Venue Mode is the source of truth for day-to-day venue operations. Staff-chat is only notification/radar/shortcut. Staff-chat must not become the canonical storage layer for orders, bills, calls, bookings, support tickets, venue chats, menu edits or settings.

Canonical dependencies:
- `docs/ORDER_SESSION_TAB_CORE.md` for table sessions, active orders, batches, tabs and bill lifecycle.
- `docs/MENU_OPTIONS_STOPLIST.md` for menu, option/modifier, stop-list, media and shift-check policy.
- `docs/COMMUNICATION_MODEL.md` for `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` separation.
- `docs/BOOKING_LIFECYCLE.md` for booking statuses, hold/deadline, reminders, booking chat and no-show/seated policy.
- `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md` for Telegram bot entrypoints, fallback order, staff-call callbacks, staff-chat link/test/unlink, notification policy and callback security.
- `docs/SECURITY_RBAC_MATRIX.md` for roles, permissions, scopes and dangerous actions.
- `docs/ANALYTICS_EVENTS.md` for operational events, KPIs, audit/event boundaries and dashboard targets.

## Venue Mode Areas

| Area | Purpose | Current implementation from docs | Target / gap |
| --- | --- | --- | --- |
| Dashboard | First operational screen for the current venue. | Dashboard/counters exist in Venue Mini App; read-only stats smoke passed for Owner/Manager. | Full operations dashboard is `PARTIAL`: needs complete queue/call/booking/stop-list/staff-chat/status warning coverage. |
| Orders | Queue of active order batches by table/order. | Venue order queue and order detail exist; display order number and Russian labels are smoke-closed. | Queue may group by table, but detail must preserve `table_session`, batches and tabs. |
| Order detail | Chronological batch and item workspace. | Full bill, display labels and selected options are documented as smoke-closed for current paths. | Status history/audit, force-close reason and all modifier variants need verification. |
| Tabs / bill | Operational bill by personal/shared tabs. | Guest/Venue/Bot bill parity is smoke-closed; bill request/payment method is smoke-closed. | Tab reopen and force-close audit remain future/partial. |
| Staff calls | Live operational requests from table context. | M5 lifecycle and ACK/DONE audit hardening are staging-smoked. | CANCELLED UI/lifecycle and row-level actor/timestamps remain future. |
| Bookings | Venue booking queue and lifecycle. | Booking queue/lifecycle, hold settings, reminders and attendance indicators are partially/smoke-closed. | Broader lifecycle automation/preorder/reminder rollout remains partial/future. |
| Menu | Structured order menu management. | Structured selected-option parity is smoke-closed; menu constructor broader status is partial. | Use `docs/MENU_OPTIONS_STOPLIST.md`; shift check and broad media/top-list governance remain future/partial. |
| Stop-list | Fast operational availability toggles. | Item/option availability parity is documented for current Staff/Manager/Owner paths. | Per-venue `staff_stoplist_enabled`, mass stop-list and audit completeness are future/partial. |
| Tables / QR | Physical table inventory and QR context. | Tables/QR basics exist; table-session runtime behavior is documented separately. | Single table CRUD/diagnostics/QR rotate audit need verification. |
| Staff / invites | Membership, roles and invite links. | Staff/Manager invite sharing and acceptance are staging-smoked; Platform Owner OWNER invite/revoke is smoke-closed. | Role parity still needs regression after new routes. |
| Staff-chat | Linked group diagnostics and operational notifications. | Link/test/unlink and live order activity-card behavior are smoke-closed. | Personal staff notifications and unified event policy remain future. |
| Settings | Venue profile, schedule, booking hold, extension, staff-chat and operational settings. | Booking hold, shift extension, public profile/card and schedule/date exceptions are smoke-closed. | Broader settings/media/promotions/preview remain partial/future. |
| Stats | Role-specific operational summaries. | Venue Mini App read-only stats passed staging smoke for Owner/Manager. | Custom ranges, arbitrary stats, AI summaries and advanced analytics remain future. |

## Dashboard

Target dashboard cards:
- new order batches count;
- active orders count;
- staff calls waiting;
- bookings waiting response;
- stop-list warnings;
- staff-chat linked/unlinked status;
- today stats summary;
- venue status/subscription warning where visible to Owner.

Role behavior:
- Owner sees operations plus settings, staff-chat, subscription/status warnings where implemented.
- Manager sees shift operations, queues, stats and settings only where allowed.
- Staff sees only operational queue/calls/bookings/availability according to role permissions.

Current vs target:
- Current dashboard is `PARTIAL`: operational counters and stats exist in slices, but one complete dashboard model with all cards above needs verification.

## Orders Queue

Target:
- queue can group by physical table for scanning;
- detail preserves `table_session_id`, order, batch and tab boundaries;
- statuses are tracked by batch, not only the whole table;
- filters:
  - `new`;
  - `in_progress`;
  - `ready/delivering`;
  - `delivered`;
  - `rejected/cancelled`.

Each queue card should show:
- venue/table display;
- human display order number;
- latest batch time;
- number of new batches;
- source `miniapp` / `bot_fallback`;
- SLA timer if implemented/future.

Role policy:
- Staff can view the queue and update allowed statuses.
- Manager can accept/reject/manage statuses where backend policy allows.
- Owner can perform all venue operations.
- Platform does not operate ordinary venue orders by default.

Current vs target:
- Queue, order detail, display number and status operations exist in current docs.
- SLA timers and full event-derived queue metrics are future/partial.

## Order Detail / Batches

Target detail shows:
- venue table;
- `table_session_id` / visit context where useful to operators;
- human display order number;
- batches chronologically.

Each batch shows:
- `created_at`;
- source (`miniapp`, `bot_fallback`, service charge path where applicable);
- safe guest/author label;
- tab/account label;
- items;
- selected option snapshots;
- line comments/preferences;
- status;
- status history/audit where implemented.

Actions:
- accept;
- preparing;
- delivering;
- delivered;
- reject with reason;
- cancel if policy allows;
- force close only with reason/audit.

Current vs target:
- Venue order detail, display order number, bill rows, selected options and staff-chat clarity are smoke-closed for current paths.
- Force-close reason/audit and full status history need verification before being called complete.

## Tabs / Bill

Target:
- personal tabs and shared tabs are visible in order detail as operational bill accounts;
- Venue users see the operational bill breakdown by tab;
- Guest privacy boundaries still apply: guests see only their own personal tab or joined shared tabs;
- full bill uses order snapshots, not live menu prices.

Tab lifecycle:
- `open -> bill_requested -> paid -> closed`;
- `closed -> reopened` only by allowed role with audit, if implemented.

Actions:
- mark bill requested;
- mark paid;
- close tab/order where allowed;
- reopen only by allowed role with audit, future/target.

Current vs target:
- Full bill, human order/account labels and bill request/payment method UX are staging-smoked.
- Tab reopen and full paid/closed state machine remain partial/future unless implementation evidence exists.

## Staff Calls

Staff calls are separate from support tickets and venue chats.

Target statuses:
- `new`;
- `acknowledged` / `accepted`;
- `completed` / `closed`;
- `cancelled`.

Queue card shows:
- type/reason;
- table;
- optional guest comment;
- age;
- assigned/accepted by where implemented;
- linked order/bill context where safe.

Actions:
- acknowledge;
- complete;
- optional quick reply such as `Иду` / `2 минуты`.

Current vs target:
- Staff-call lifecycle and ACK/DONE audit hardening are CLOSED / staging smoke passed.
- `cancelled`, row-level `acked_by` / `done_by` / timestamps and quick replies remain future/partial.

Staff-chat:
- staff-call notifications may go to staff-chat according to existing operational policy;
- support tickets and `VENUE_CHAT` must not go to staff-chat.

## Bookings Queue

Canonical booking lifecycle: `docs/BOOKING_LIFECYCLE.md`.

Target booking queue is available to Owner/Manager and Staff only where final policy allows.

Statuses:
- `pending`;
- `confirmed`;
- `changed` / `proposed_time`;
- `cancelled`;
- `expired`;
- `no_show`;
- `seated`.

Actions:
- confirm;
- propose time;
- cancel with reason;
- mark seated;
- mark no-show.

Hold minutes / arrival deadline:
- booking hold settings and `arrival_deadline_at` are documented as implemented/smoked in current roadmap notes;
- reminders remain opt-in disabled by default unless rollout is explicitly enabled.

Current vs target:
- Venue Mini App booking queue/lifecycle, Staff arrival/no-show split and M7a hold settings are smoke-closed.
- Broader automatic expiry/no-show, preorder, visit-history integration and reminder rollout remain partial/future unless explicitly enabled and smoked under `docs/BOOKING_LIFECYCLE.md`.

## Menu / Stop-List Operations

Canonical menu policy: `docs/MENU_OPTIONS_STOPLIST.md`.

Venue Operations view:
- Owner manages structure, prices, media, option schema, featured/top-list and menu policy.
- Manager target is stop-list + shift check + availability unless product explicitly keeps broad `MENU_MANAGE`.
- Staff target is read-only by default; stop-list only if `staff_stoplist_enabled` or equivalent future policy allows it.
- Current docs say Staff can manage item/option availability through `MENU_AVAILABILITY_MANAGE`; keep Bot/Mini App parity in regression until a per-venue flag exists.
- Stop-list changes should be fast and auditable.
- Guest checkout server-side availability validation is required.

## Tables / QR

Target tables list:
- display name;
- zone;
- status;
- active visit/order indicator.

Actions:
- create table;
- bulk create;
- download QR;
- download QR package;
- rotate/reissue QR;
- deactivate table;
- edit display/zone/capacity if product implements it.

Dangerous actions:
- QR rotate/reissue requires confirmation/audit;
- table deactivate with active session should warn and require explicit confirmation.

Current vs target:
- Table/QR basics exist, while single CRUD/diagnostics/QR rotate audit need verification in the current implementation docs.

## Staff / Roles / Invites

Target:
- Owner manages staff and roles.
- Manager may create Staff invite only where current conservative policy allows.
- Staff cannot manage roles.
- Last Owner removal is blocked server-side.
- Invite tokens are short-lived and one-time where appropriate.

Audit:
- role granted;
- role revoked;
- invite created;
- invite accepted;
- owner changed/revoked.

Current vs target:
- Staff/Manager invite sharing polish and acceptance are staging-smoked.
- Platform Owner can invite/revoke Venue Owner with last-owner protection.
- Broader staff management parity should stay in role smoke after new routes.

## Staff-Chat

Canonical Telegram/staff-chat model: `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`.

Target:
- link/unlink/test from Venue Mode;
- staff-chat is radar/shortcut, not source of truth;
- callbacks verify server-side role and venue scope;
- `callback_data` uses opaque ids only;
- link/unlink/test writes audit where implemented.

Allowed staff-chat events:
- order batch notification;
- staff-call notification;
- booking operational notification if policy says so.

Forbidden staff-chat events:
- `SUPPORT_TICKET`;
- `VENUE_CHAT`;
- ordinary guest support messages;
- raw menu/price edits.

Current vs target:
- Staff-chat diagnostics/unlink, test-message flow and order activity-card noise reduction are smoke-closed.
- Personal staff notifications and unified event policy remain future/partial.

## Settings

Target settings groups:
- Venue profile: name, description, address, city, contacts, hours.
- Modules: orders, staff calls, bookings, promotions/future, menu visibility policy.
- Operations: timezone, order numbering, notification toggles, staff-chat settings.
- Owner-only: billing/subscription, dangerous lifecycle request, owner access.

Current vs target:
- Venue Mini App settings are no longer a broad dead placeholder for the closed slices: booking hold, shift extension settings, public profile/card, schedule/date exceptions and staff-chat management are backend-backed in current docs.
- Remaining settings such as media sections, promotions, guest preview, broader module toggles and some Bot parity are partial/future.
- If a future settings screen is not backend-backed, hide it or mark it clearly as future.

## Stats

Target Owner stats:
- orders today/7d/30d;
- accepted/delivered batches;
- estimated revenue only if bill data is reliable;
- top items;
- average accept/deliver time;
- staff calls;
- booking response;
- support summary.

Target Manager stats:
- shift queue;
- backlog;
- SLA timers where implemented;
- active staff calls;
- bookings waiting response.

Staff:
- no business analytics by default;
- operational queue/counters only.

Current vs target:
- Venue Mini App read-only stats passed staging smoke for Owner/Manager.
- Custom ranges, arbitrary period stats, AI summaries and advanced analytics remain future.

## Surface Parity Matrix

| Feature | Telegram bot current | Venue Mini App current | Staff-chat current | Target / gap | Priority |
| --- | --- | --- | --- | --- | --- |
| Order queue | Exists for venue roles. | Exists and smoke-closed for core queue/detail. | Notifications/activity-card. | Venue Mode remains source of truth; SLA timers future. | Regression |
| Order status | Exists. | Exists. | Callback shortcuts where implemented. | Callbacks must verify role/scope; status history/audit needs verification. | Regression |
| Full bill | Staff full bill exists. | Management bill parity smoke-closed. | Bill context in activity-card. | Tab reopen/paid state machine partial. | Regression |
| Staff calls | Bot/staff-chat callbacks exist. | M5 queue/lifecycle smoke-closed. | Notifications and ACK/DONE callbacks. | CANCELLED/quick replies/row-level actors future. | P2 |
| Bookings | Bot and venue booking flows exist. | Queue/lifecycle/hold settings smoke-closed. | Operational notifications where policy allows. | Broader automation/preorder/reminder rollout future. | Regression/P2 |
| Menu manage | Bot owner/manager flows exist. | Options/flavors parity smoke-closed; broader constructor partial. | No source-of-truth edits. | Follow `docs/MENU_OPTIONS_STOPLIST.md`; shift check future. | P2 |
| Stop-list | Bot paths exist. | Item/option parity documented/smoked. | Callback shortcuts only if role-checked. | Per-venue Staff stop-list flag future. | Regression/P2 |
| Tables/QR | Bot table flows exist. | Basics exist. | No. | QR rotate audit/diagnostics need verification. | P2 |
| Staff invites | Bot invite acceptance exists. | Copy/share invite result smoke-closed. | No. | Keep role denial/last-owner protection in regression. | Regression |
| Staff-chat link/test | Bot link command exists. | M6 link/test/unlink smoke-closed. | Target group. | Personal notifications future. | Regression |
| Settings | Bot richer in some areas. | Backend-backed slices exist; broader settings partial. | No. | Hide non-backed placeholders. | P2 |
| Stats | Bot stats exist. | Read-only stats smoke-closed. | No. | Custom ranges/advanced analytics future. | P2 |

## Current Known Gaps

- Staff-call ACK/DONE is smoke-closed; `cancelled`, row-level actor/timestamps and quick replies remain future.
- Booking queue/lifecycle is smoke-closed for MVP; automatic expiry/no-show policy, preorder and broad reminder rollout remain future.
- Full bill/display/order snapshots are smoke-closed for current paths; tab reopen, force-close reason/audit and all modifier variants need verification.
- Settings are `PARTIAL`: closed slices are backend-backed, but broader settings/media/promotions/preview remain future/partial.
- Staff stop-list parity is documented as aligned for current item/option availability; per-venue `staff_stoplist_enabled` remains future.
- Manager broad `MENU_MANAGE` remains a product-policy decision: keep and test it, or narrow to stop-list/shift check/basic availability.
- Staff-chat notification policy is documented for orders/calls/bookings and explicitly excludes support/venue chats; personal staff notifications remain future.
- Multi-venue selector/entry should stay in regression for users with several venue memberships.

## Roadmap Status

- Venue Mode operational spec: `UPDATED`.
- Venue operations implementation: `PARTIAL / DONE-POLISH by slice`; core orders/bill/staff-call/bookings/staff-chat/menu-options settings slices are smoke-closed, but a complete operating cockpit still has future/partial areas.
- Staff-call lifecycle: `CLOSED for ACK/DONE MVP`, `PARTIAL` for cancellation/quick replies/row-level actors.
- Booking queue: `CLOSED for MVP`, `PARTIAL` for automation/preorder/reminder rollout.
- Settings: `PARTIAL`, with several backend-backed slices closed.
- Full bill/display/order snapshots: `CLOSED for current smoke paths`, `PARTIAL` for force-close/reopen/all modifier variants.
- Stop-list parity: current item/option parity documented; per-venue Staff policy and mass/shift-check remain future.
- Staff-chat source-of-truth policy: `DOCUMENTED`.

## Operational Smoke Checklist

1. Owner opens Venue Mode dashboard.
2. Manager opens Venue Mode dashboard.
3. Staff opens Venue Mode and sees only allowed sections.
4. Guest creates order batch from table context.
5. Venue queue shows table/order with new batch.
6. Venue detail shows batch and items.
7. Venue detail shows selected option snapshots and line comments where configured.
8. Staff updates allowed batch status.
9. Staff cannot reject/close if policy forbids.
10. Manager can reject with reason if policy allows.
11. Full bill/tabs are visible according to current implementation.
12. Guest requests bill; Venue sees `bill_requested` context.
13. Guest creates staff call.
14. Venue/Staff sees staff call.
15. Staff-call ACK/DONE works if implemented; otherwise record as expected gap.
16. Booking appears in queue if implemented.
17. Venue confirms/changes/cancels booking if implemented.
18. Owner toggles item stop-list; guest cannot order unavailable item.
19. Staff stop-list behavior matches current policy across Telegram and Mini App.
20. Owner downloads QR package where implemented.
21. QR rotate requires confirmation/audit where implemented.
22. Owner links staff-chat and sends test message.
23. Staff-chat receives order/staff-call notifications but not support tickets or venue chats.
24. Manager cannot access billing.
25. Staff cannot access settings, billing, support tickets or venue chats.
26. Venue user cannot access another venue.
