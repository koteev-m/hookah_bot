# Platform Owner

Дата актуализации: 2026-06-29.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`.

## Current status

Platform Owner управляет платформенным onboarding, коммерческими условиями, подписками и lifecycle заведений. Это отдельная роль от Venue Owner/Manager/Staff.

Recent closed milestones:
- Platform Owner Invite / ADMIN Semantics Hardening: **CLOSED / staging smoke passed**.
- Platform Venue OWNER Revocation: **CLOSED / staging smoke passed**.

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
- active OWNER membership list;
- owner invite/add flow with usable Telegram deep link or fallback copy text;
- Platform Owner-only OWNER membership revoke with last-owner protection;
- subscription summary/settings/price schedule basics;
- status/lifecycle controls where implemented.

Platform Mini App does not expose `ADMIN` as a selectable runtime owner/admin assignment role.

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
- Create a venue OWNER invite; the response includes a usable Telegram `deepLink` when bot username is configured and safe `/start staff_invite_<code>` copy text otherwise.
- Add a venue OWNER through accepted Telegram invite for the intended venue.
- List active `venue_members` rows with role `OWNER` as the current owner list.
- Revoke one OWNER membership when at least one other active OWNER remains.

## Denied actions / constraints

- Platform Owner role does not bypass venue-specific RBAC for ordinary venue operations unless the user also has a venue membership.
- `DELETED` venues should not appear in normal guest/owner/platform lists.
- Hard delete is not part of normal flow for real venues with orders/bookings/payments/history.
- Secrets/env values must not be exposed in bot messages/logs/docs.
- Production billing/payment automation must not be triggered by manual staging/onboarding smoke.
- Last active OWNER revoke is blocked server-side; UI disabling is not the security boundary.
- OWNER membership revoke deletes only the venue membership and never deletes the user account.
- Runtime owner access is based on active `venue_members` OWNER membership. `venues.owner_account_id` and `venue_owner_accounts.primary_owner_user_id` are not relinked by membership revoke.

## Known gaps / needs smoke

- Platform Mini App cockpit is still partial compared with Telegram bot.
- Support/tickets and richer platform analytics remain future work.
- Billing invoices/payments UI is not a complete platform cockpit yet.
- `ADMIN` remains a legacy alias to `MANAGER`; product copy should avoid promising separate venue-admin behavior until implemented.
- There is still no separate Venue Admin permission model.
- Primary/legal/billing owner relink and a dedicated ownership transfer helper remain separate Platform Owner milestones.
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
10. Create owner invite; accept it through Telegram `/start staff_invite_<code>`; invited account becomes OWNER for the intended venue.
11. Verify owner invite create/accept audit evidence exists and contains no secrets/invite deep links beyond safe action metadata.
12. Revoke one OWNER while another active OWNER remains; revoked user loses Venue Mini App and Telegram Bot venue-owner access for that venue.
13. Attempt to revoke the last active OWNER is blocked with safe copy.
14. Verify `VENUE_OWNER_REVOKE` audit evidence exists and `owner_account_id` / primary-owner linkage did not change.
