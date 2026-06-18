# Platform Owner

Дата актуализации: 2026-06-03.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## Current status

Platform Owner управляет платформенным onboarding, коммерческими условиями, подписками и lifecycle заведений. Это отдельная роль от Venue Owner/Manager/Staff.

Canonical identity:
- основной ключ: `PLATFORM_OWNER_TELEGRAM_ID`;
- `OWNER_TELEGRAM_ID` может оставаться legacy alias;
- `PLATFORM_OWNER_USER_ID` не должен быть обязательным для Telegram-auth platform access, когда canonical identity - `users.telegram_user_id`.

## Telegram bot

Platform Owner bot flow покрывает:
- доступ через Telegram user id;
- заявки на подключение venue;
- статусы заявок: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`;
- запрет нескольких active requests от одного Telegram account;
- approve/reject;
- commercial terms dialog;
- `trial=0` as no trial / paid immediately;
- current monthly price;
- future price + `dd.MM.yyyy` effective date;
- notes/individual agreements;
- create/link venue after approved request;
- sync commercial terms to venue subscription settings/price schedule;
- later price editing for created venue;
- venue lifecycle: suspend/archive/delete;
- `DELETED` venues hidden from normal lists.

## Mini App

Platform Mini App baseline:
- platform auth through Telegram Mini App initData;
- `/api/platform/me` based on canonical platform owner resolver;
- venue list/detail;
- owner/admin assignment basics;
- subscription summary/settings/price schedule basics;
- status/lifecycle controls where implemented.

Telegram bot remains the richer platform onboarding surface for connection requests and commercial terms. Platform Mini App cockpit is partial and should not be treated as full replacement yet.

## Allowed actions

- View and process platform connection requests.
- Approve/reject/cancel according to request lifecycle.
- Create/link venue from approved request.
- Set trial days, including `0`.
- Set current monthly price.
- Set future price and effective date.
- Save notes/individual commercial terms.
- Edit current/future price after venue creation.
- Suspend, archive, delete venues through platform lifecycle flow.
- View normal platform venue lists without `DELETED` venues.
- Manage platform-level subscription settings/price schedule where implemented.

## Denied actions / constraints

- Platform Owner role does not bypass venue-specific RBAC for ordinary venue operations unless the user also has a venue membership.
- `DELETED` venues should not appear in normal guest/owner/platform lists.
- Hard delete is not part of normal flow for real venues with orders/bookings/payments/history.
- Secrets/env values must not be exposed in bot messages/logs/docs.
- Production billing/payment automation must not be triggered by manual staging/onboarding smoke.

## Known gaps / needs smoke

- Platform Mini App cockpit is still partial compared with Telegram bot.
- Support/tickets and richer platform analytics remain future work.
- Billing invoices/payments UI is not a complete platform cockpit yet.
- `ADMIN` remains a legacy alias to `MANAGER`; product copy should avoid promising separate venue-admin behavior until implemented.
- Platform Mini App owner assignment UI should not offer `ADMIN` as a separate owner role while backend accepts only the supported owner assignment semantics.
- Lifecycle and commercial terms fixes need staging smoke after every deploy, including V102 dialog-state migration availability.

## Smoke-critical checks

1. Platform owner access works with `PLATFORM_OWNER_TELEGRAM_ID` and empty `PLATFORM_OWNER_USER_ID`.
2. Non-owner Telegram user receives 403 for platform mode.
3. Create connection request; platform owner sees it while PENDING.
4. Approve with `trial=0`, current price and optional future price/date.
5. Create/link venue; subscription settings and price schedule receive terms.
6. Edit current price and future price after venue creation.
7. Suspend/archive/delete venue.
8. Deleted venue disappears from normal platform/owner/guest lists.
9. Direct callback to deleted venue returns safe deleted message.
