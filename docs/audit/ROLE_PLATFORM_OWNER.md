# Platform Owner

Дата актуализации: 2026-07-07.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. Platform cockpit model: `docs/PLATFORM_COCKPIT.md`. Venue operations model: `docs/VENUE_OPERATIONS.md`. Booking lifecycle model: `docs/BOOKING_LIFECYCLE.md`. Telegram fallback/staff-chat model: `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Security/RBAC model: `docs/SECURITY_RBAC_MATRIX.md`. Menu/options/stop-list model: `docs/MENU_OPTIONS_STOPLIST.md`. Analytics/events model: `docs/ANALYTICS_EVENTS.md`. Guest growth/retention model: `docs/GROWTH_RETENTION.md`. Testing/QA smoke strategy: `docs/TESTING_QA_SMOKE_STRATEGY.md`.

## Current status

Platform Owner управляет платформенным onboarding, коммерческими условиями, подписками и lifecycle заведений. Это отдельная роль от Venue Owner/Manager/Staff.

Platform Mode is one cockpit for:
- venues and venue detail;
- onboarding requests;
- venue lifecycle;
- owner/access and OWNER invites/revoke;
- billing, subscriptions and invoices;
- Support Center / `Обращения`;
- future promotion moderation/paid placement only after the Growth/retention and Platform billing/moderation/analytics prerequisites are implemented;
- analytics, audit, events and operational risk/health indicators.

Platform permissions, ordinary venue-chat denial, dangerous actions and audit/export safety expectations are canonical in `docs/SECURITY_RBAC_MATRIX.md`.

Recent closed milestones:
- Platform Owner Invite / ADMIN Semantics Hardening: **CLOSED / staging smoke passed**.
- Platform Venue OWNER Revocation: **CLOSED / staging smoke passed**.
- Platform Billing Cockpit / Owner Payment UX: **CLOSED / staging smoke passed**.
- Platform Billing Renewal / Advance Invoice / Courtesy Days: **CLOSED / staging smoke passed**.
- Support/Tickets MVP beyond booking threads: **CLOSED / smoke passed** for Platform support-ticket visibility/reply/close and transferred-ticket handling.

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
- billing cockpit with read-only overview, human paid-through/next-payment copy, explicit invoice/checkout ensure action, manual mark-paid and audit evidence;
- next-period invoice creation from effective paid-through + 1 day, idempotent advance ensure and courtesy/free days through `billing_adjustments`;
- Platform Support Center / `Обращения` for `SUPPORT_TICKET`, including platform-only technical tickets and venue-transferred tickets;
- status/lifecycle controls where implemented.

Platform Mini App does not expose `ADMIN` as a selectable runtime owner/admin assignment role.

Telegram bot remains the richer platform onboarding surface for connection requests and commercial terms. Platform Mini App cockpit is still partial for onboarding/placements/analytics, but manual billing and support-ticket MVPs are smoke-tested. Paid placement/promotion boosting is not part of the Growth MVP; if implemented later, it must be clearly labeled as advertising/promoted placement.

## Venue lifecycle model

Current implementation:
- `DRAFT`;
- `PUBLISHED`;
- `HIDDEN`;
- `PAUSED`;
- `SUSPENDED`;
- `ARCHIVED`;
- `DELETED` with `deleted_at`.

Target product model:
- `draft`;
- `onboarding`;
- `published`;
- `hidden`;
- `paused_by_owner`;
- `suspended_by_platform`;
- `archived`;
- `deletion_requested`;
- `deleted`.

Current mapping caveat:
- legacy `onboarding` is normalized to `DRAFT`;
- legacy `paused_by_owner` is normalized to `PAUSED`;
- legacy `suspended_by_platform` is normalized to `SUSPENDED`;
- legacy `deletion_requested` is normalized to `DELETED`;
- do not promise separate owner-pause, billing-created suspension or deletion-request workflows until a migration/product decision splits them.

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
- View Platform Owner billing cockpit and Venue Owner subscription state.
- Create/reuse manual/fake invoices through explicit invoice/checkout ensure POST actions.
- Create the next-period invoice in advance from effective paid-through + 1 day; repeated ensure must reuse an existing invoice for the same period.
- Manually mark invoice paid; action must write audit and preserve paid invoice history.
- Add courtesy/free days for a venue only with a required reason; action writes `billing_adjustments` and `BILLING_COURTESY_DAYS_ADDED` audit.
- View/list/reply/close `SUPPORT_TICKET`, including platform-only technical tickets and tickets transferred from Venue by `Передать платформе`.
- View Platform analytics where implemented: WAAV, venue health, onboarding funnel, billing state, support load, risk indicators and platform-wide fallback/reject/SLA metrics.
- Create a venue OWNER invite; the response includes a usable Telegram `deepLink` when bot username is configured and safe `/start staff_invite_<code>` copy text otherwise.
- Add a venue OWNER through accepted Telegram invite for the intended venue.
- List active `venue_members` rows with role `OWNER` as the current owner list.
- Revoke one OWNER membership when at least one other active OWNER remains.

## Analytics / audit

Current audit foundation:
- venue lifecycle/status changes write platform status audit evidence;
- owner invite create/accept and `VENUE_OWNER_REVOKE` write audit evidence;
- billing checkout ensure, manual mark-paid and courtesy days write audit evidence;
- support ticket status/scope/assignment/escalation and message-add audit exists where implemented.

Needed Platform analytics remain future/partial:
- venue counts by lifecycle/subscription/risk state;
- onboarding funnel and owner invite conversion;
- billing metrics such as active/trialing/past_due/suspended venues, open/overdue invoices, paid-through risk and MRR after a real provider exists;
- support metrics such as TTFR, TTR, escalation rate, reopen rate, CSAT and top issue themes;
- future growth metrics such as favorite rate, repeat visit rate, promo view/redeem, review completion, opt-in/unsubscribe and abuse/rate-limit indicators;
- integration health for Telegram outbox/webhooks, billing webhooks, staff-chat links and Mini App errors.

## Denied actions / constraints

- Platform Owner role does not bypass venue-specific RBAC for ordinary venue operations unless the user also has a venue membership.
- Ordinary Venue Mode operations are governed by `docs/VENUE_OPERATIONS.md`; booking lifecycle operations are governed by `docs/BOOKING_LIFECYCLE.md`; Telegram fallback/staff-chat behavior is governed by `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; Platform Mode should not become the normal order/staff-call/booking/menu workspace.
- Platform Owner guest QR test escape and Telegram platform menu parity remain `needs verification` unless a later smoke proves them.
- Testing/QA smoke strategy is `UPDATED` in `docs/TESTING_QA_SMOKE_STRATEGY.md`: Platform/billing/security changes require focused backend tests, audit checks, GitHub Actions and staging smoke when runtime behavior changes.
- `DELETED` venues should not appear in normal guest/owner/platform lists.
- Hard delete is not part of normal flow for real venues with orders/bookings/payments/history.
- Secrets/env values must not be exposed in bot messages/logs/docs.
- Production billing/payment automation must not be triggered by manual staging/onboarding smoke.
- Real acquiring provider, Telegram Stars and automatic recurring card payment are not implemented by the current billing cockpit MVP.
- Courtesy days must not edit old paid invoices and do not implement invoice void/reissue for already-open future invoice conflicts.
- Do not broadly auto-reactivate manual `SUSPENDED_BY_PLATFORM` venues until billing-created versus manual suspension can be distinguished.
- Last active OWNER revoke is blocked server-side; UI disabling is not the security boundary.
- OWNER membership revoke deletes only the venue membership and never deletes the user account.
- Runtime owner access is based on active `venue_members` OWNER membership. `venues.owner_account_id` and `venue_owner_accounts.primary_owner_user_id` are not relinked by membership revoke.
- Platform Owner does not see ordinary `VENUE_CHAT`; only support tickets are in Platform Support Center unless future product policy explicitly changes.
- Paid placement/promotion boosting must not be offered without billing, moderation, analytics and visible advertising labels.
- Menu `FEATURED` / `TOP_LIST` is a venue-managed showcase, not Platform paid placement. Platform paid placement/boosting is separate future scope.
- Cashback/points/flexible loyalty must not be approved before the financial model and discount accounting are correct.
- Analytics exports/dashboards must not expose raw Telegram payloads, initData, message text, payment secrets, card data or unrelated PII.

## Known gaps / needs smoke

- Platform Mini App cockpit is still partial compared with Telegram bot for onboarding requests, placements and analytics.
- Analytics/events are `SPEC UPDATED / PARTIAL` in `docs/ANALYTICS_EVENTS.md`; Platform analytics dashboard and event/audit explorer remain future/partial until event emission and payload safety are verified.
- Growth/retention is `SPEC UPDATED / PARTIAL-FUTURE` in `docs/GROWTH_RETENTION.md`; Platform paid placements, promotion boosting, growth analytics and moderation are future/advanced, not required for MVP.
- Advanced support features remain future work: SLA automation, auto-escalation worker, macros, attachments, CSAT, diagnostics report and broad support analytics.
- Real acquiring provider, Telegram Stars and recurring automatic payment remain future work. `GenericHmacBillingProvider` is an integration base, not a completed provider rollout.
- Audited invoice void/reissue for courtesy conflicts with already-open future invoices remains future work.
- Distinguishing billing-created versus manual `SUSPENDED_BY_PLATFORM` remains needed before broader auto-reactivation.
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
15. Open billing cockpit; verify GET overview does not create invoices, adjustments, lifecycle rows, checkout links or provider calls.
16. Create/reuse current or next invoice through explicit POST; repeat next invoice ensure and verify no duplicate invoice.
17. Mark invoice paid manually; verify paid-through/next-payment human copy and audit evidence.
18. Add courtesy/free days with required reason; verify `billing_adjustments`, `BILLING_COURTESY_DAYS_ADDED`, adjusted paid-through/next-payment and no mutation of paid invoice rows.
19. Verify Venue Owner sees adjusted state while Venue Owner/Manager/Staff cannot mark paid or add courtesy days.
20. Open Platform Mini App `Обращения`; verify platform-only and venue-transferred support tickets are visible, ordinary `VENUE_CHAT` is not visible, and Platform Owner can reply/close support tickets.
21. Verify Staff does not see Platform Mode or Platform Support Center.
22. Verify lifecycle, owner, billing and support audit payloads contain safe ids/status/scope/reason fields and no secrets/raw provider payloads/raw Telegram payloads.
23. Verify Platform analytics, if shown/exported, follows `docs/ANALYTICS_EVENTS.md` and excludes raw message text, initData, payment secrets, card data and unrelated PII.
