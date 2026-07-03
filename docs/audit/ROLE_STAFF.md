# Staff

Дата актуализации: 2026-07-03.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. STAFF - операционная роль смены, не management-role.

## Current status

STAFF может работать с заказами, вызовами, закрытием счёта и операционным stop-list по позициям/вкусам. STAFF не получает финансовые bill-edit права и не управляет структурой/контентом меню, столами, персоналом или настройками.

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
- full bill read-only with human order display label, personal/shared account context, discounts, service charges and excluded/non-payable items;
- accept/deliver allowed statuses;
- close bill/order;
- staff calls view/accept/close;
- bookings view and arrival/no-show marking;
- shift-extension requests view/confirm where backend allows;
- menu content read-only, with operational item/option availability toggles;
- tables read-only;
- forbidden management controls hidden and backend-protected.

## Allowed actions

- View venue order queue.
- View order detail and full bill read-only.
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
- Manage venue settings.
- Manage billing/subscription/platform features, including mark-paid, invoice ensure, courtesy/free-days or payment controls.

## Known gaps / needs smoke

- Telegram and Mini App staff entries must both use inline `web_app` as runtime Mini App opener.
- Staff multi-venue selector needs smoke if a staff user belongs to more than one venue.
- Staff/Manager invite acceptance and sharing polish is CLOSED / staging smoke passed; once accepted, STAFF can enter Venue Mode but still cannot access billing/payment controls.
- Staff-call lifecycle, linked staff-chat notification delivery and ACK/DONE audit hardening are CLOSED / staging smoke passed for Venue Mini App and Telegram staff-chat surfaces. Applied ACK/DONE transitions leave audit evidence with actor user id and source; audit is best-effort.
- Row-level `acked_by` / `done_by` / ACK-DONE timestamp columns, CANCELLED UI/lifecycle and staff-call UX polish are not implemented in this milestone. Guest table-context cleanup/exit is CLOSED / staging smoke passed and belongs to the Guest role regression checklist.
- Direct API denial tests remain critical: UI hiding is not a security boundary.

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
11. STAFF sees and confirms shift-extension requests where `SHIFT_EXTENSION_CONFIRM` allows it, but cannot edit extension settings.
12. Newly invited STAFF can open Venue Mode after accepting deep link, but cannot see billing/payment controls.
