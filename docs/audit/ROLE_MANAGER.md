# Manager

Дата актуализации: 2026-07-08.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. `ADMIN` в runtime сейчас является legacy alias для `MANAGER`.

## Current status

Manager - операционная management-роль venue. Manager ведёт смену, заказы, брони, меню/availability и столы в рамках текущих backend permissions. Manager не является Platform Owner и не получает platform-wide права. Growth/retention scope is governed by `docs/GROWTH_RETENTION.md`; `Акции и удержание` remain partial/future unless backend-backed and smoked.

Guest communication follows `docs/COMMUNICATION_MODEL.md`: Manager can handle `BOOKING_CHAT`, `VENUE_CHAT` and own-venue `SUPPORT_TICKET`; `STAFF_CALL` remains a separate operational queue. Booking lifecycle and queue rules follow `docs/BOOKING_LIFECYCLE.md`. Telegram bot fallback, staff-chat and callback rules follow `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Manager permissions, billing denial, cross-venue isolation and dangerous-action boundaries are governed by `docs/SECURITY_RBAC_MATRIX.md`. Public staff profiles, today shift and future staff tips follow `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Venue operations are governed by `docs/VENUE_OPERATIONS.md`. Menu/stop-list policy follows `docs/MENU_OPTIONS_STOPLIST.md`. Order/session/tab behavior follows `docs/ORDER_SESSION_TAB_CORE.md`. Analytics/KPI rules follow `docs/ANALYTICS_EVENTS.md`. Testing/QA smoke strategy follows `docs/TESTING_QA_SMOKE_STRATEGY.md`. Release/deploy operations follow `docs/DEPLOYMENT_RUNBOOK.md`.

Role mapping:
- DB `MANAGER` -> `VenueRole.MANAGER`;
- DB `ADMIN` -> `VenueRole.MANAGER` as legacy alias;
- Manager permissions включают order queue/status, booking view/manage, shift-extension view/confirm/settings, menu view/manage/availability, table view/manage/QR export, staff chat link and read-only stats access.

## Telegram bot

Manager bot flow покрывает:
- заказы и статусы;
- staff calls;
- stop-list / availability;
- bookings list/actions where supported;
- booking guest messages/support threads where supported;
- structured menu operations;
- venue card/info through role-aware paths;
- tables/QR operations;
- staff chat operations where allowed;
- stats where implemented.
- future `Акции и удержание` only if the backend-backed MVP grants Manager access; Staff must not manage campaigns.

Manager copy should follow the same naming split:
- `🍽 Заказное меню` - structured menu;
- `📖 Фото-меню` - info/media section.

## Mini App

Manager opens Venue Mini App through inline `web_app` entry, so Telegram initData is present.

Manager Mini App areas:
- dashboard;
- orders queue/detail;
- full bill with human order display label, batches/doporders, personal/shared account context, discounts, service charges and excluded/non-payable items;
- bill controls: manual discount, exclude/restore item;
- close bill/order;
- bookings;
- `Сообщения` for `BOOKING_CHAT` and `VENUE_CHAT`;
- `Помощь` / `Обращения` for own-venue `SUPPORT_TICKET`;
- staff calls;
- shift-extension requests/settings;
- menu and availability management;
- tables management and QR export where backend permission allows;
- staff chat link/status;
- stats;
- staff list/invite only where current conservative route policy allows; invite result uses a valid Telegram deep link, copy/share actions and a secondary fallback command.

## Allowed actions

- View and operate venue order queue.
- Update allowed order/batch statuses.
- Close bill/order.
- View order detail with batches/tabs while preserving table-session boundaries.
- View full bill.
- Apply/change/remove manual item discount.
- Exclude/restore bill item.
- View and manage staff calls.
- Manage bookings: confirm, cancel, change/propose time, message guest, mark confirmed bookings arrived/no-show and booking settings.
- Read/reply/resolve booking conversation threads where `BOOKING_MANAGE` allows it.
- Read/reply to ordinary `VENUE_CHAT` for the manager's venue.
- Read/reply/resolve own-venue `SUPPORT_TICKET` and manually `Передать платформе` support tickets when needed.
- View statistics.
- View Manager analytics where implemented: shift dashboard, queue backlog, SLA timers, active staff calls and bookings waiting response.
- Manage paid shift-extension settings and confirm requests.
- Manage structured menu/categories/items and stop-list/availability.
- Manage tables and QR export according to current permissions.
- Link/test staff chat if current role permission allows.
- View staff list and create conservative STAFF invites if current route policy allows.
- Mark public staff profiles as `Сегодня на смене` under the current conservative Phase 1 policy.
- Create/manage simple `VENUE_PROMOTION` only if the growth MVP explicitly allows Manager access; terms, period and visibility/status are mandatory, and promo notifications require guest opt-in.

## Denied actions

- Platform Owner access and platform venue lifecycle.
- Subscription commercial terms, Platform Owner billing cockpit, manual mark-paid and courtesy/free-days controls.
- Billing/payment controls in Venue Owner subscription screen.
- Owner-only venue settings if backend requires owner permission.
- Promote users to owner/platform owner or bypass last-owner protection.
- Rotate all table tokens or other owner-only QR actions if backend permission does not allow it.
- Unlink staff chat if backend keeps unlink owner-only.
- Hard delete venue data.
- Mix orders/batches from different `table_session_id` values or treat staff chat as the source of truth for order state.
- Promise automatic discounts, cashback, points or promo-code redemption without a real promotion/loyalty engine and discount accounting.
- Send marketing/promo notifications without guest opt-in, frequency limits and unsubscribe.
- See billing metrics, platform analytics, another venue's analytics or raw event payloads containing message text/initData/payment secrets/card data.
- Publish/hide public staff profiles or approve future staff tip methods unless product policy explicitly grants it; Owner approval is the conservative default.
- Add payment providers, Telegram Stars, crypto or platform-collected staff tips through staff/today-shift flows.

## Known gaps / needs smoke

- `ADMIN == MANAGER` remains an intentional legacy alias, but product copy should avoid promising a separate admin role.
- Manager staff management scope is conservative and should be smoke-tested before pilot.
- Staff invite deep-link sharing polish is CLOSED / staging smoke passed for the allowed manager invite path: link is selectable/copyable/shareable and accepted payload grants the intended role.
- Some Telegram manager flows may still be richer than Mini App equivalents.
- Menu options/photos/descriptions/top-list parity may still be partial.
- Staff chat diagnostics/test flow is implemented in Mini App; manager must stay denied for owner-only unlink.
- Staff-call lifecycle, linked staff-chat notification delivery and ACK/DONE audit hardening are CLOSED / staging smoke passed for Venue Mini App and Telegram staff-chat surfaces. Applied ACK/DONE transitions leave audit evidence with actor user id and source; audit is best-effort.
- Guest-visible `CANCELLED` terminal status is CLOSED / staging smoke passed for the current guest/tableSession. Venue active queue remains `NEW` / `ACK`; manual cancel UI, row-level `acked_by` / `done_by` / ACK-DONE timestamp columns and staff-call UX polish remain future. Guest table-context cleanup/exit is CLOSED / staging smoke passed and belongs to the Guest role regression checklist.
- Multi-venue manager selector/entry needs smoke if a manager belongs to several venues.
- Guest Communication UX split is CLOSED / smoke passed for Manager surfaces: `Сообщения` handles booking/venue chats, `Помощь` handles support tickets, Staff is not granted access, and support/venue chats do not post to staff-chat. SLA automation, macros, attachments, CSAT and diagnostics remain future support follow-ups.
- Order/session/tab core is `SPEC UPDATED` in `docs/ORDER_SESSION_TAB_CORE.md`: queue may group by table, but detail must preserve batches/tabs/session boundaries; force close should require reason/audit if implemented.
- Venue operations spec is `UPDATED` in `docs/VENUE_OPERATIONS.md`: Manager dashboard, queues, bookings, stop-list, staff-chat source-of-truth policy and operational smoke are canonical.
- Booking lifecycle spec is `UPDATED` in `docs/BOOKING_LIFECYCLE.md`: Manager booking confirm/change/cancel/message actions, confirmed-only arrival actions, hold/deadline display, booking chat separation and support routing stay canonical and in regression.
- Telegram fallback/staff-chat spec is `UPDATED` in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`: Manager bot operations, staff-chat link/test permissions, state-aware booking callbacks, callback RBAC and notification policy stay canonical and in regression.
- Testing/QA smoke strategy is `UPDATED` in `docs/TESTING_QA_SMOKE_STRATEGY.md`: Manager/Venue changes require role smoke and direct denial checks according to change type.
- Analytics/events are `SPEC UPDATED / PARTIAL` in `docs/ANALYTICS_EVENTS.md`: Manager dashboard should stay shift/operations-focused and must not expose billing/platform analytics.
- Menu/options/stop-list spec is `UPDATED` in `docs/MENU_OPTIONS_STOPLIST.md`: current docs allow broad Manager menu management, but conservative target policy keeps Manager to stop-list, shift check and basic availability unless product explicitly retains broader `MENU_MANAGE`.
- Growth/retention is `SPEC UPDATED / PARTIAL-FUTURE`: simple venue promotions, favorite/history/repeat loops and post-visit feedback need implementation and staging smoke before being called complete. Staff remains excluded from growth campaign management.
- Staff profiles / today shift are `MVP DONE / SMOKE-PASSED`: Manager may mark today's visible shift under current conservative policy, while Owner remains the default for profile publish/hide and future tip-method approval. Schedule, photo upload and staff tips remain future.

## Smoke-critical checks

1. Manager opens Venue Mini App through inline `web_app`; auth succeeds.
2. Manager can accept/deliver/close orders.
3. Manager can use bill controls and final total reloads from backend.
4. Manager can confirm/cancel/propose booking where supported.
5. Manager can open `Сообщения`, reply to `BOOKING_CHAT` / `VENUE_CHAT` and use active/resolved filters where backend allows.
6. Manager can open `Помощь` / `Обращения`, reply to own-venue support tickets and transfer support tickets to Platform.
7. Manager can open `Статистика`.
8. Manager can manage menu/availability and tables according to permissions.
9. Manager cannot enter platform owner mode.
10. Manager cannot perform owner/platform-only role escalation or owner-only staff-chat unlink.
11. Manager can manage active `NEW` / `ACK` staff calls; linked Telegram staff group receives Mini App-created staff-call notification and staff-call ACK/DONE audit rows include actor evidence during regression smoke. Terminal `CANCELLED` is not active work.
12. If manager can create STAFF invite under current policy, invite result shows valid Telegram deep link, copy/share actions and fallback command; accepted invite grants STAFF.
13. Manager cannot access billing payment controls, mark-paid or courtesy/free-days actions.
14. Manager order queue can group by table, while detail shows separate batches and tabs; closing/force-closing order/session does not allow new batches into the old active order and requires reason/audit where implemented.
15. Manager menu permissions match the product policy from `docs/MENU_OPTIONS_STOPLIST.md`: stop-list/shift check/basic availability are allowed, while price/media/structure/schema edits are allowed only if broad Manager `MENU_MANAGE` is intentionally retained and tested.
16. Manager can mark a public staff profile `Сегодня на смене` only inside own venue and cannot publish/hide profiles or approve tip methods.

Future Growth/retention checks:

17. If Manager access is allowed, Manager can create a simple promotion with title, description, active period, terms and visibility/status.
18. Promotion is visible only during active period and hidden/suspended promotions are absent.
19. Promotion copy does not imply automatic discount unless promo engine/accounting is implemented.
20. Staff cannot see or manage `Акции и удержание`.
