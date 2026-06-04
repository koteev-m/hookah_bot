# Manager

Дата актуализации: 2026-06-03.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. `ADMIN` в runtime сейчас является legacy alias для `MANAGER`.

## Current status

Manager - операционная management-роль venue. Manager ведёт смену, заказы, брони, меню/availability и столы в рамках текущих backend permissions. Manager не является Platform Owner и не получает platform-wide права.

Role mapping:
- DB `MANAGER` -> `VenueRole.MANAGER`;
- DB `ADMIN` -> `VenueRole.MANAGER` as legacy alias;
- Manager permissions включают order queue/status, menu view/manage/availability, table view/manage/QR export, staff chat link.

## Telegram bot

Manager bot flow покрывает:
- заказы и статусы;
- staff calls;
- stop-list / availability;
- bookings list/actions where supported;
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
- full bill;
- bill controls: manual discount, exclude/restore item;
- close bill/order;
- bookings;
- staff calls;
- menu and availability management;
- tables management and QR export where backend permission allows;
- staff chat link/status;
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
- Hard delete venue data.

## Known gaps / needs smoke

- `ADMIN == MANAGER` remains an intentional legacy alias, but product copy should avoid promising a separate admin role.
- Manager staff management scope is conservative and should be smoke-tested before pilot.
- Some Telegram manager flows may still be richer than Mini App equivalents.
- Menu options/photos/descriptions/top-list parity may still be partial.
- Multi-venue manager selector/entry needs smoke if a manager belongs to several venues.

## Smoke-critical checks

1. Manager opens Venue Mini App through inline `web_app`; auth succeeds.
2. Manager can accept/deliver/close orders.
3. Manager can use bill controls and final total reloads from backend.
4. Manager can confirm/cancel/propose booking where supported.
5. Manager can manage menu/availability and tables according to permissions.
6. Manager cannot enter platform owner mode.
7. Manager cannot perform owner/platform-only role escalation.
