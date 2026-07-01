# Manager

Дата актуализации: 2026-06-30.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. `ADMIN` в runtime сейчас является legacy alias для `MANAGER`.

## Current status

Manager - операционная management-роль venue. Manager ведёт смену, заказы, брони, меню/availability и столы в рамках текущих backend permissions. Manager не является Platform Owner и не получает platform-wide права.

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

Manager copy should follow the same naming split:
- `🍽 Заказное меню` - structured menu;
- `📖 Фото-меню` - info/media section.

## Mini App

Manager opens Venue Mini App through inline `web_app` entry, so Telegram initData is present.

Manager Mini App areas:
- dashboard;
- orders queue/detail;
- full bill with human order display label, personal/shared account context, discounts, service charges and excluded/non-payable items;
- bill controls: manual discount, exclude/restore item;
- close bill/order;
- bookings;
- messages/support threads for booking conversations;
- staff calls;
- shift-extension requests/settings;
- menu and availability management;
- tables management and QR export where backend permission allows;
- staff chat link/status;
- stats;
- staff list/invite only where current conservative route policy allows.

## Allowed actions

- View and operate venue order queue.
- Update allowed order/batch statuses.
- Close bill/order.
- View full bill.
- Apply/change/remove manual item discount.
- Exclude/restore bill item.
- View and manage staff calls.
- Manage bookings: confirm, cancel, change/propose time, message guest, mark arrived/no-show and booking settings.
- Read/reply/resolve booking conversation threads where `BOOKING_MANAGE` allows it.
- View statistics.
- Manage paid shift-extension settings and confirm requests.
- Manage structured menu/categories/items and stop-list/availability.
- Manage tables and QR export according to current permissions.
- Link/test staff chat if current role permission allows.
- View staff list and create conservative STAFF invites if current route policy allows.

## Denied actions

- Platform Owner access and platform venue lifecycle.
- Subscription commercial terms and platform billing cockpit.
- Owner-only venue settings if backend requires owner permission.
- Promote users to owner/platform owner or bypass last-owner protection.
- Rotate all table tokens or other owner-only QR actions if backend permission does not allow it.
- Unlink staff chat if backend keeps unlink owner-only.
- Hard delete venue data.

## Known gaps / needs smoke

- `ADMIN == MANAGER` remains an intentional legacy alias, but product copy should avoid promising a separate admin role.
- Manager staff management scope is conservative and should be smoke-tested before pilot.
- Some Telegram manager flows may still be richer than Mini App equivalents.
- Menu options/photos/descriptions/top-list parity may still be partial.
- Staff chat diagnostics/test flow is implemented in Mini App; manager must stay denied for owner-only unlink.
- Staff-call lifecycle, linked staff-chat notification delivery and ACK/DONE audit hardening are CLOSED / staging smoke passed for Venue Mini App and Telegram staff-chat surfaces. Applied ACK/DONE transitions leave audit evidence with actor user id and source; audit is best-effort.
- Row-level `acked_by` / `done_by` / ACK-DONE timestamp columns, CANCELLED UI/lifecycle and staff-call UX polish are not implemented in this milestone. Guest table-context cleanup/exit is CLOSED / staging smoke passed and belongs to the Guest role regression checklist.
- Multi-venue manager selector/entry needs smoke if a manager belongs to several venues.

## Smoke-critical checks

1. Manager opens Venue Mini App through inline `web_app`; auth succeeds.
2. Manager can accept/deliver/close orders.
3. Manager can use bill controls and final total reloads from backend.
4. Manager can confirm/cancel/propose booking where supported.
5. Manager can open `Сообщения`, reply to booking threads and use active/resolved filters where backend allows.
6. Manager can open `Статистика`.
7. Manager can manage menu/availability and tables according to permissions.
8. Manager cannot enter platform owner mode.
9. Manager cannot perform owner/platform-only role escalation or owner-only staff-chat unlink.
10. Linked Telegram staff group receives Mini App-created staff-call notification and staff-call ACK/DONE audit rows include actor evidence during regression smoke.
