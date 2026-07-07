# Staff

Дата актуализации: 2026-07-07.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. STAFF - операционная роль смены, не management-role.

## Current status

STAFF может работать с заказами, вызовами, закрытием счёта и операционным stop-list по позициям/вкусам. STAFF не получает финансовые bill-edit права и не управляет структурой/контентом меню, столами, персоналом или настройками.

Guest communication follows `docs/COMMUNICATION_MODEL.md`: STAFF handles operational `STAFF_CALL` / order flows only. STAFF does not see `Помощь` / `SUPPORT_TICKET` and does not handle ordinary `VENUE_CHAT`. STAFF permissions, denied scopes and direct-API smoke expectations are governed by `docs/SECURITY_RBAC_MATRIX.md`. Venue operations are governed by `docs/VENUE_OPERATIONS.md`. Menu/stop-list policy follows `docs/MENU_OPTIONS_STOPLIST.md`. Order/session/tab behavior follows `docs/ORDER_SESSION_TAB_CORE.md`. Analytics/KPI rules follow `docs/ANALYTICS_EVENTS.md`.

Current backend permissions:
- `ORDER_QUEUE_VIEW`;
- `ORDER_STATUS_UPDATE`;
- `BOOKING_VIEW`;
- `BOOKING_ARRIVAL_UPDATE`;
- `SHIFT_EXTENSION_VIEW`;
- `SHIFT_EXTENSION_CONFIRM`;
- `MENU_VIEW`;
- `MENU_AVAILABILITY_MANAGE`;
- `TABLE_VIEW`.

STAFF не получает:
- `MENU_MANAGE`;
- `TABLE_MANAGE`;
- `TABLE_TOKEN_ROTATE`;
- `TABLE_TOKEN_ROTATE_ALL`;
- `TABLE_QR_EXPORT`;
- `BOOKING_MANAGE`;
- `SHIFT_EXTENSION_SETTINGS`;
- `STAFF_CHAT_LINK`;
- `VENUE_SETTINGS`.

## Telegram bot

Staff bot flow:
- order queue;
- allowed status updates;
- staff calls;
- read-only operational context;
- Venue Mini App entry trigger that sends inline `web_app` button.

Group/supergroup safety:
- linked staff group is notification/operations-only;
- ordinary group text must not show private role menu/reply keyboard;
- management commands stay in private bot chat or approved inline callbacks.

## Mini App

STAFF opens Venue Mini App through inline `web_app` entry (`📱 Открыть рабочую панель` flow), so Telegram initData is present.

STAFF Mini App behavior:
- dashboard shows operational counters, not staff chat diagnostics;
- order queue/detail;
- full bill read-only with human order display label, batches/doporders, personal/shared account context, discounts, service charges and excluded/non-payable items;
- accept/deliver allowed statuses;
- close bill/order;
- staff calls view/accept/close;
- bookings view and arrival/no-show marking;
- shift-extension requests view/confirm where backend allows;
- menu content read-only, with operational item/option availability toggles;
- tables read-only;
- forbidden management controls hidden and backend-protected.
- `Помощь` / `Обращения` and ordinary venue chats are hidden and backend-forbidden.

## Allowed actions

- View venue order queue.
- View order detail with batches/tabs and full bill read-only.
- Update allowed order statuses.
- Close bill/order.
- View active staff calls.
- Accept/close staff calls.
- View active bookings.
- Mark booking guest as arrived (`SEATED`).
- Mark booking guest as no-show (`NO_SHOW`).
- View menu content without edit controls.
- Put menu item into stop-list and return it from stop-list.
- Put item option/flavor into stop-list and return it from stop-list.
- View and confirm shift-extension requests where current backend policy allows it.
- View tables read-only.
- Use Venue Mini App working panel.

## Denied actions

- Manual item discount.
- Exclude/restore bill items.
- Edit menu item/category names.
- Edit menu prices.
- Create/delete/reorder menu categories or items.
- Add/edit/delete flavors/options or change option/flavor names/prices.
- Apply base flavor profiles.
- Confirm new booking.
- Cancel booking.
- Change/propose booking time.
- Message guest about booking.
- Manage booking settings.
- Create/update/delete tables.
- Rotate/export QR tokens.
- Read full staff list or manage staff.
- Create invites/update roles/remove members.
- Manage staff chat link/status diagnostics.
- See or reply to `SUPPORT_TICKET`.
- See or reply to ordinary `VENUE_CHAT`.
- Manage venue settings.
- Manage billing/subscription/platform features, including mark-paid, invoice ensure, courtesy/free-days or payment controls.
- Treat staff chat as the source of truth for order/bill state or merge orders from different table sessions.
- See business analytics, billing metrics, platform analytics or raw event payloads.

## Known gaps / needs smoke

- Telegram and Mini App staff entries must both use inline `web_app` as runtime Mini App opener.
- Staff multi-venue selector needs smoke if a staff user belongs to more than one venue.
- Staff/Manager invite acceptance and sharing polish is CLOSED / staging smoke passed; once accepted, STAFF can enter Venue Mode but still cannot access billing/payment controls.
- Staff-call lifecycle, linked staff-chat notification delivery and ACK/DONE audit hardening are CLOSED / staging smoke passed for Venue Mini App and Telegram staff-chat surfaces. Applied ACK/DONE transitions leave audit evidence with actor user id and source; audit is best-effort.
- Row-level `acked_by` / `done_by` / ACK-DONE timestamp columns, CANCELLED UI/lifecycle and staff-call UX polish are not implemented in this milestone. Guest table-context cleanup/exit is CLOSED / staging smoke passed and belongs to the Guest role regression checklist.
- Direct API denial tests remain critical: UI hiding is not a security boundary.
- Guest Communication UX split is CLOSED / smoke passed for STAFF boundaries: staff remains operational, support/venue-chat API access is denied, and staff-call/order behavior stays separate.
- Order/session/tab core is `SPEC UPDATED` in `docs/ORDER_SESSION_TAB_CORE.md`: STAFF can operate statuses according to role, but queue/detail must preserve table-session, batch and tab boundaries. Force close should require reason/audit if implemented by an allowed role.
- Venue operations spec is `UPDATED` in `docs/VENUE_OPERATIONS.md`: STAFF remains operational-only, sees allowed queue/call/booking/availability surfaces and must not see settings/billing/support/venue-chat workspaces.
- Analytics/events are `SPEC UPDATED / PARTIAL` in `docs/ANALYTICS_EVENTS.md`: STAFF has operational queue/counters only and no business analytics by default.
- Menu/options/stop-list spec is `UPDATED` in `docs/MENU_OPTIONS_STOPLIST.md`: current runtime docs allow STAFF item/option availability through `MENU_AVAILABILITY_MANAGE`; target policy is Staff stop-list only when venue policy enables it, with no structure, price, media, option schema or featured/top-list access.

## Smoke-critical checks

1. STAFF opens Venue Mini App through inline `web_app`; auth succeeds.
2. STAFF sees order queue/detail and full bill read-only.
3. STAFF accepts/delivers and closes bill/order.
4. STAFF does not see `Скидка`, `Исключить`, `Вернуть`.
5. Direct STAFF bill-edit/menu/table/staff/settings mutations return 403.
6. STAFF sees menu content and tables read-only, but can toggle menu item and option/flavor availability for operational stop-list.
7. STAFF dashboard has call counters and no staff chat status row.
8. STAFF can accept/close staff calls; linked Telegram staff group receives Mini App-created staff-call notification and staff-call ACK/DONE audit rows include actor evidence during regression smoke.
9. STAFF sees bookings and can mark arrived/no-show only.
10. Direct STAFF confirm/cancel/change/message/settings booking attempts are denied.
11. STAFF does not see `Помощь` / `Обращения`, cannot open/reply support tickets, and cannot open/reply ordinary venue chats through direct API calls.
12. STAFF sees and confirms shift-extension requests where `SHIFT_EXTENSION_CONFIRM` allows it, but cannot edit extension settings.
13. Newly invited STAFF can open Venue Mode after accepting deep link, but cannot see billing/payment controls.
14. STAFF order detail shows batches and tabs without exposing unrelated guests' private/support data; staff chat order notification mirrors the backend order state but is not treated as source of truth.
15. STAFF does not see Owner/Platform analytics dashboards or raw analytics/audit payloads.
16. STAFF stop-list action, where allowed, changes only item/option availability, writes audit where implemented and behaves identically in Telegram Bot and Venue Mini App.
