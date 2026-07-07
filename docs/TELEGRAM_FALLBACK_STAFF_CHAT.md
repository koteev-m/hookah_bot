# Telegram Fallback And Staff-Chat Model

Дата актуализации: 2026-07-07.

Статус: **current product reference / SPEC UPDATED**. Telegram bot remains an entrypoint, fallback and notification surface for the same backend/Mini App product. Current docs/code evidence says fallback quick-order payload, Staff Call ACK/DONE, staff-chat link/test/unlink and support/venue-chat staff-chat denial are closed for current smoke paths. The complete Telegram parity model is still **PARTIAL / needs verification** for broad Telegram-vs-Mini-App parity, Platform Owner guest-QR test escape, platform menu placeholders, per-venue real staff-chat delivery, callback audit completeness and future notification history.

## Core Rule

Mini App and backend domain tables are the source of truth. Telegram private bot and staff-chat are interaction surfaces. Staff-chat is notification/radar/shortcut only; it must not become the canonical storage layer or management workspace for orders, bills, staff calls, bookings, support tickets, venue chats, menu edits, settings or billing.

Canonical dependencies:
- `docs/ORDER_SESSION_TAB_CORE.md` for table sessions, active orders, batches, tabs and bill/request lifecycle.
- `docs/COMMUNICATION_MODEL.md` for `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` separation.
- `docs/VENUE_OPERATIONS.md` for operational queues and staff-chat source-of-truth rules.
- `docs/BOOKING_LIFECYCLE.md` for booking queue, booking chat, reminders and booking staff-chat policy.
- `docs/SECURITY_RBAC_MATRIX.md` for roles, scopes, callback trust boundaries and dangerous actions.
- `docs/ANALYTICS_EVENTS.md` for bot/staff-chat analytics and audit/event boundaries.
- `docs/TESTING_QA_SMOKE_STRATEGY.md` for Telegram/staff-chat validation, staging and smoke strategy.

## Canonical Telegram Bot Model

Telegram bot acts as:
- QR entrypoint for `/start <table_token>`;
- navigation shell for Guest, Venue and Platform roles;
- fallback surface when Mini App does not load or the guest chooses chat order;
- notification channel through private chat where Telegram allows messaging;
- staff-chat bridge for operational venue notifications and safe shortcuts.

Rules:
- QR/table token is a context pointer, not authority.
- Bot menus and inline keyboards are convenience only; backend RBAC is authoritative.
- Bot fallback must call the same backend/domain paths as Mini App or produce an explicitly marked fallback batch/task.
- Staff-chat callbacks must resolve the entity server-side, check role/scope/state and handle stale/idempotent actions safely.

## Bot States

| State | Context | Target behavior | Current / gap |
| --- | --- | --- | --- |
| `S0_NO_TABLE_CONTEXT` | Guest has no active table context. | Show catalog/open Mini App, `Чаты`, `Помощь`, `Мои брони` / `Мои заказы` where implemented, `Для кальянной` for venue roles and Platform Mode for Platform Owner. | Current docs say guest catalog, bookings, chats/help and role menus exist. Platform Owner QR guest-test escape needs verification. |
| `S1_TABLE_CONTEXT` | Guest has verified venue/table/table_session context. | Show venue + table header, open menu, active order/bill, reorder, fallback chat order, staff call, switch/rescan table, secondary help/problem entry. | Table context cleanup and user-scoped exit are smoke-closed; keep QR/table restore and exit in regression. |
| `S2_FALLBACK_ORDER_DIALOG` | Guest orders in private bot chat. | Collect text/order, confirm/edit/cancel, create `ORDER_BATCH` with `source=bot_fallback`, attach current table_session and tab. | Current docs say `cmd=start_quick_order` payload is code-test closed; real Telegram fallback remains release smoke. |
| `S3_STAFF_CALL_DIALOG` | Guest creates live staff call from table context. | Choose reason, optional comment, create `STAFF_CALL`, notify operational surfaces. | Staff-call create plus ACK/DONE lifecycle is smoke-closed; cancel/quick replies remain future/partial. |
| `S4_CHAT_SUPPORT_HANDOFF` | Guest wants conversation/help. | Booking chat opens `BOOKING_CHAT`; venue question opens `VENUE_CHAT` where supported; support opens `SUPPORT_TICKET`; do not mix thread types. | Guest communication split is smoke-closed; keep staff-chat denial for support/venue chats in regression. |

## QR `/start` Flow

Target:
- `/start <table_token>` resolves table context.
- `table_token` is short, opaque and base64url/deeplink-compatible.
- Token resolution verifies venue/table visibility and table enabled state.
- Token creates or touches `TABLE_SESSION` and default personal tab according to `docs/ORDER_SESSION_TAB_CORE.md`.
- If token is revoked, unknown or expired, show safe copy and do not disclose internal ids.
- If table is disabled, show safe copy and ask the guest to contact staff.
- If venue is hidden/suspended/unavailable to guests, show safe copy and do not open ordering.
- If the user also has Platform Owner or Venue role, product should provide explicit guest QR test mode or `Продолжить как гость` when role precedence would otherwise block guest QR smoke.

Current vs target:
- QR/table context, restore and exit are documented as staging-smoked.
- Platform Owner guest-QR test escape is **needs verification** unless a later smoke proves it.

## Fallback Chat Order

Target behavior:
- Fallback order creates an `ORDER_BATCH` with `source=bot_fallback`.
- It requires active `TABLE_SESSION`.
- It attaches a `tab_id`:
  - personal tab by default;
  - if shared tabs exist, ask where to add if the UX stays simple enough.
- It uses the same server-side checks as Mini App:
  - venue is available;
  - table_session is active;
  - tab membership is valid;
  - item/menu availability is verified if the selection is structured;
  - idempotency is enforced by client/request key or equivalent;
  - rate limits apply;
  - unavailable item/order bypass is rejected.
- If fallback is free text, store it as free-text batch/comment or staff clarification task according to current model; do not pretend it is a structured menu item unless a safe mapping exists.
- Staff-chat notification is allowed for the created order batch.
- Venue Mode remains source of truth for queue/detail/status/bill.

Current vs target:
- Current docs say the Mini App fallback payload sends `{"cmd":"start_quick_order","table_token":"<tableToken>"}` and has code-test verification.
- Real Telegram client fallback ordering remains part of release smoke.
- Shared-tab fallback selection and structured menu mapping are future/partial unless implementation evidence exists.

## Staff-Call From Bot

Target:
- Bot table context can create `STAFF_CALL`.
- Staff-call is not support ticket and not venue chat.
- Target statuses:
  - `new`;
  - `acknowledged`;
  - `completed`;
  - `cancelled`.
- Staff-chat notification is allowed for staff-call.
- Support tickets, venue chats and booking chat messages must not go to staff-chat.
- Rate-limit by user, table_session and table.

Current vs target:
- Staff-call create/status and ACK/DONE lifecycle are smoke-closed across Venue Mini App and Telegram staff-chat callbacks.
- Row-level actor/timestamps, `cancelled` UX/lifecycle and quick replies such as `Иду` / `2 минуты` remain future/partial unless later implemented.

## Staff-Chat Link / Unlink / Test

Target link flow:
- Owner or allowed role generates one-time staff-chat link code from Venue Mode or bot.
- Venue adds bot to group.
- Authorized user runs `/link@BotUsername <code>`.
- Bot verifies code, venue scope, expiry and actor permission where possible.
- Backend saves `chat_id`, linked timestamp and actor.

Unlink:
- Owner-only unless product explicitly allows Manager.
- Writes audit where implemented.
- Clears active staff-chat binding without deleting historical notification records.

Test:
- Sends a diagnostic test message through Telegram outbox.
- UI copy must say queued/accepted by outbox, not guaranteed delivered, unless delivery is confirmed.
- Last delivery state/history is future unless implemented.

Privacy mode:
- Bot should not need to read all group messages.
- Commands should use `/link@BotUsername` for reliability in groups.

Staff-chat must not accept raw menu price edits, settings changes, billing actions or support-ticket workflows from ordinary group text.

Current vs target:
- Staff-chat link/test/unlink diagnostics are documented as M6 closed / staging smoke passed.
- Personal staff notifications, delivery-history UI and Telegram forum-topic routing remain future.

## Staff-Chat Notification Policy

Allowed events:
- new order batch;
- important order status updates when useful;
- staff-call created / acknowledged / completed;
- booking operational notifications where venue policy allows;
- staff-chat link/test diagnostics.

Forbidden events:
- `SUPPORT_TICKET` create/reply;
- `VENUE_CHAT` create/reply;
- full `BOOKING_CHAT` message stream;
- raw support text;
- raw payment secrets/provider payloads;
- raw Telegram initData;
- raw menu price edits through group text.

Clarifications:
- Staff-chat is notification/radar/shortcut.
- Venue Mode and backend domain tables are source of truth.
- Callback actions must verify role and venue scope server-side.
- Staff-chat messages should include minimum operational context and avoid unrelated PII.

## Inline Callback Actions

Rules:
- `callback_data` uses opaque short ids/tokens only where feasible.
- Do not include raw `user_id`, raw `table_id`, price, role grant payload, payment/provider payload, initData, secrets or message text.
- Server checks:
  - actor Telegram user id;
  - actor role/permission;
  - venue scope;
  - entity state transition;
  - idempotency and concurrency/stale state.
- Answer messages should be short and safe:
  - `Принято`;
  - `Уже обработано`;
  - `Нет прав`;
  - `Устарело, откройте кабинет`.
- Editing the staff-chat message after action should reflect the latest state to reduce duplicate work and stale callbacks.

Current vs target:
- Existing callback strings may still contain compact numeric ids in older flows. Treat them as internal short pointers only if they are re-authorized server-side and do not expose secrets/PII.
- New callback families should prefer opaque tokens when practical.

## Telegram / Mini App / Staff-Chat Parity Matrix

| Feature | Telegram private bot current | Guest Mini App current | Venue Mini App current | Platform Mini App current | Staff-chat current | Target / gap | Priority |
| --- | --- | --- | --- | --- | --- | --- | --- |
| QR start/table context | `/start <table_token>` supported. | QR/table context supported. | No guest table context. | No. | No. | Role-precedence guest test escape needs verification. | Regression |
| Open Mini App | WebApp buttons exist. | Primary UI. | Primary venue UI. | Platform cockpit. | No. | Keep `initData` path and URLs in smoke. | Regression |
| Fallback order | Chat quick-order path exists. | Sends `start_quick_order` payload where Mini App fallback used. | Queue receives resulting batch. | No. | Order notification allowed. | Real Telegram fallback remains release smoke; shared-tab fallback future. | Regression |
| Staff call | Bot table context/staff-chat callbacks exist. | Guest create/status smoke-closed. | Queue ACK/DONE smoke-closed. | No. | Notifications and ACK/DONE callbacks. | Cancel/quick replies future. | Regression/P2 |
| Active order view | Bot order/bill paths exist. | Active order/bill smoke-closed. | Detail/bill smoke-closed. | No ordinary order workspace. | Activity-card shortcut. | Venue Mode remains source of truth. | Regression |
| Bill/request bill | Bot/staff bill surfaces exist. | Request bill/payment note smoke-closed. | Bill/request context visible. | No. | Activity-card can show bill context. | Online payment is separate future work. | Regression |
| Booking list/actions | Bot `/my` and booking actions exist. | `Мои брони` parity documented. | Queue/lifecycle smoke-closed. | No ordinary booking ops. | Operational notifications allowed. | M7c rollout disabled by default; two-account smoke unverified. | Regression |
| Booking chat | Guest bot replies supported. | `Чаты` includes booking threads. | `Сообщения` includes booking threads. | No ordinary booking chat. | Not full chat stream. | Must not mutate booking lifecycle. | Regression |
| Venue chat | Bot support depends on current implementation. | Catalog/detail `Задать вопрос` -> `VENUE_CHAT`. | Owner/Manager can reply. | No ordinary venue chat. | Forbidden. | Bot venue-chat entry is target/needs verification if not implemented. | P2 |
| Support ticket | `/support` fallback where implemented. | `Помощь`. | Own-venue `Обращения`. | Support Center. | Forbidden. | Staff denied; no staff-chat spam. | Regression |
| Orders queue | Bot shift hub exists. | No. | Primary queue. | No. | Shortcut/notification only. | Large queue/pagination parity needs smoke. | P2 |
| Staff-call queue | Bot shift hub/staff-chat callbacks exist. | No venue queue. | Primary queue. | No. | Shortcut/notification. | Venue Mode source of truth. | Regression |
| Staff-chat link/test | Bot paths exist. | No. | M6 smoke-closed. | No. | Target group. | Delivery history future. | Regression |
| Stop-list actions | Bot callbacks exist. | Guest availability read only. | Staff/Manager/Owner stop-list paths. | No. | Shortcut only if role-checked. | Staff parity is current; per-venue flag future. | Regression |
| Owner/Manager/Staff role menus | Bot role menus exist. | No. | Role-specific Venue Mode. | No. | No private role menu. | UI hiding is not security. | Regression |
| Platform Owner menu | Bot platform paths exist. | No. | No. | Platform Mode. | No. | Placeholder/platform parity needs verification. | P2 |

## Role-Specific Telegram Behavior

Guest:
- catalog and venue card;
- table QR context;
- fallback order;
- staff call;
- active order/bill;
- booking list/actions;
- `Чаты` and `Помощь` where implemented;
- no venue/admin/platform controls.

Staff:
- operational order/call/booking arrival/no-show actions only;
- no support tickets;
- no ordinary `VENUE_CHAT`;
- no billing/settings/platform;
- stop-list behavior must match final RBAC policy in Telegram and Mini App.

Manager:
- venue operations, orders, staff calls, bookings and stop-list according to RBAC;
- no platform-wide controls;
- no venue billing/payment controls.

Owner:
- venue operations;
- staff-chat link/test/unlink according to current owner/manager split;
- staff/invites, menu/stop-list/settings and billing view/pay according to RBAC.

Platform Owner:
- platform menu/support/billing where implemented;
- should not be blocked from guest QR testing without an explicit `Продолжить как гость` / guest-mode escape if role precedence would otherwise prevent table context smoke.

## Current Known Gaps

- Fallback quick-order payload contract is code-test closed in current docs, but real Telegram client fallback remains release smoke.
- Staff-call ACK/DONE is smoke-closed; `cancelled`, row-level actor/timestamps and quick replies remain future.
- Mini App-created staff-call notifications and Telegram staff-chat ACK/DONE are smoke-closed, but every pilot venue still needs real group binding smoke.
- Staff stop-list parity is documented as aligned for current item/option availability; per-venue `staff_stoplist_enabled` remains future.
- Telegram multi-venue selected context is documented as implemented; keep selector/entry in regression for multi-venue users.
- Platform Owner guest QR test escape is **needs verification**.
- Telegram Platform menu placeholders/parity are **PARTIAL / needs verification** compared with Platform Mini App cockpit.
- Staff-chat notification policy is documented here; delivery failure history, topic routing and personal staff notifications remain future.
- Staff-chat diagnostics/link/test/unlink are M6 closed for current paths; link-code expiry/actor audit and per-venue delivery must stay in regression.

## Analytics And Events

Canonical event rules: `docs/ANALYTICS_EVENTS.md`.

Target events:
- `bot_start_received`;
- `table_token_resolved`;
- `bot_table_context_shown`;
- `fallback_order_started`;
- `fallback_order_confirmed`;
- `fallback_order_cancelled`;
- `fallback_order_batch_created`;
- `staff_call_created_from_bot`;
- `staff_chat_link_code_created`;
- `staff_chat_linked`;
- `staff_chat_unlinked`;
- `staff_chat_test_sent`;
- `staff_chat_notification_enqueued`;
- `staff_chat_notification_delivered`;
- `staff_chat_callback_action_clicked`;
- `staff_chat_callback_action_rejected`.

Rules:
- Client/bot UX events are lower-trust diagnostics unless emitted/validated server-side.
- Staff-chat delivery/outbox events are technical telemetry, not business source of truth.
- Do not copy support/chat message text into analytics payloads.

## Security And Privacy

- Mini App `initData` validation belongs to Mini App auth; never trust `initDataUnsafe`.
- Telegram webhook should use `secret_token` when configured.
- Telegram callback data must be short, opaque and re-authorized server-side.
- Staff-chat messages include minimum PII and no secrets.
- Support/chat text stays in domain message tables with RBAC; it must not be copied to analytics/staff-chat.
- Rate-limit:
  - fallback order attempts;
  - staff calls;
  - support create/messages;
  - staff-chat notifications;
  - callback actions if abused.
- Outgoing messages should use outbox/retry/backoff and respect Telegram limits.
- Telegram API `QUEUED`/outbox acceptance is not proof of user/group delivery unless a delivery state exists.

## Roadmap Status

- Telegram fallback/staff-chat spec: `UPDATED`.
- Fallback order: `CLOSED for payload contract / PARTIAL for real Telegram client smoke and shared-tab UX`.
- Staff-call lifecycle: `CLOSED for ACK/DONE MVP / PARTIAL for cancelled, quick replies and row-level actor columns`.
- Staff-chat policy: `DOCUMENTED`.
- Staff-chat as source of truth: explicitly `NO`.
- Telegram/Mini App parity: `PARTIAL`, with closed slices and documented exceptions.
- Platform Telegram menu placeholders: `OPEN/PARTIAL / needs verification`.

## Smoke Checklist

1. Guest starts bot without table and sees global menu.
2. Guest scans table QR and sees venue/table context.
3. Table QR menu opens Mini App.
4. Fallback chat order creates order batch with `source=bot_fallback`.
5. Fallback chat order appears in Venue Mode queue.
6. Fallback chat order sends staff-chat notification if staff-chat is linked.
7. Guest staff-call from bot creates `STAFF_CALL`.
8. Staff-call appears in Venue Mode / operational queue.
9. Staff-call is not mixed with support tickets.
10. Staff-chat receives order/staff-call notifications.
11. Staff-chat does not receive `SUPPORT_TICKET` or `VENUE_CHAT` messages.
12. Staff-chat callback action checks role and venue scope.
13. Unauthorized user pressing staff-chat button gets safe denial.
14. Booking `Открыть переписку` opens `BOOKING_CHAT` / `Чаты`, not Support.
15. Support booking issue creates `SUPPORT_TICKET` with verified booking/venue context.
16. Staff cannot see support tickets or venue chats from Telegram.
17. Manager cannot access billing/settings from Telegram.
18. Platform Owner can access Platform mode and has a way to test guest QR if product requires it.
19. Multi-venue user selects correct venue or current gap is documented.
