# Product Audit Summary

Дата ревизии: 2026-04-28. Режим: статический read-only аудит кода и `docs/PRODUCT_SPEC.md`; код и тесты не изменялись.

> Historical audit snapshot. The P0/P1 list in this file is not the current backlog.
>
> Current correction as of 2026-06-03: many items below were later fixed or changed by product decision, including active order table-session scoping, Mini App CORS mutation methods, Mini App staff call payload/lifecycle, STAFF stop-list policy, Venue Mini App full bill/bill controls/close, bookings MVP, pre-QR guest menu behavior, platform owner access, commercial terms sync and venue lifecycle. Check `docs/UPDATED_PRODUCT_AI_ROADMAP.md`, `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` and current code before using any item here as implementation scope.
>
> Current checkpoint as of 2026-06-26: M1-M6 Venue Bot-to-Mini-App parity slices are closed through IA shell, stats, bookings, support inbox lifecycle, staff calls and staff-chat management. M7a booking hold settings is CLOSED / staging smoke passed. M7b Guest Mini App `Мои брони` is implemented with local validation and staging visual parity for Bot `/my` public label, venue-local time and `Держим до`; real two-account Telegram runtime isolation remains unverified. M7c adaptive reminders are implemented, code/test-backed and passed one controlled real Telegram staging smoke; runtime remains disabled by default and staging is back to `BOOKING_REMINDER_WORKER_ENABLED=false`. M8a/M8b-Free public profile/card settings is CLOSED / staging smoke passed: Venue Mini App edits public location/contact/description with provider-free local country/city data and manual address fallback. M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed: the committed opt-in ControlMaster helper completed a real staging deploy and endpoint smoke while the normal deploy command remains supported; the exact SSH/network root cause remains unconfirmed. M9b/M9b.1/M9b.2/M9b.3 schedule parity is CLOSED / staging smoke passed. The latest enriched staff-chat attendance copy is code/test-backed but not manually re-smoked with a new booking. Remaining launch-relevant gaps are no longer the old P0 order/session/CORS/staff-call/schedule list; use the current roadmap for operational Mini App verification, billing, support/growth and runtime regression priorities.
>
> Current checkpoint as of 2026-06-29: Platform Owner Invite / ADMIN Semantics Hardening and Platform Venue OWNER Revocation are CLOSED / staging smoke passed. Bot/API Platform Owner config parity is implemented, Platform Mini App does not offer `ADMIN`, owner invite returns usable Telegram deep link/copy text, invite accept grants OWNER for the intended venue, owner invite create/accept is audited, Platform Owner can list active OWNER memberships and revoke one OWNER while another remains, last-owner revoke is blocked server-side, revoked OWNER loses runtime access through `venue_members`, `owner_account_id` / primary-owner linkage is not relinked, and `VENUE_OWNER_REVOKE` audit evidence exists.
>
> Current checkpoint as of 2026-06-30: H2/PostgreSQL active-order + personal-tab uniqueness fidelity is CLOSED / validation passed and commit `a4a2d71` is on `origin/main`. H2 V112 now mirrors the existing PostgreSQL predicates for one `ACTIVE` order per `table_session_id` and one active `PERSONAL` tab per `table_session_id + owner_user_id`; PostgreSQL already had those constraints, no PostgreSQL production migration was added, runtime API/routes/Mini App/Bot behavior did not change, and no staging deploy was required. Validation passed with split lower-memory Gradle commands and no test XML failure/error markers; PostgreSQL/Testcontainers-backed checks were skipped where Docker was unavailable.
>
> Current checkpoint as of 2026-06-30: Mini App mutation / operational verification closure pack is CLOSED / code-test verification passed. Current tests verify actual Mini App PUT/PATCH/DELETE CORS preflights with `Content-Type` and `Authorization`, Mini App staff-call `tableSessionId` persistence and staff-chat event payload, linked staff-chat staff-call notification enqueue, and Guest Mini App fallback quick-order `Telegram.WebApp.sendData` payload `{ "cmd": "start_quick_order", "table_token": "<tableToken>" }`. No staging smoke is claimed by this checkpoint.
>
> Current checkpoint as of 2026-06-30: Staff Call Lifecycle ACK/DONE audit hardening is CLOSED / staging smoke passed. Successful applied staff-call ACK/DONE transitions write `STAFF_CALL_ACK` / `STAFF_CALL_DONE` audit evidence from both Venue Mini App routes and Telegram staff-chat callbacks with top-level `actorUserId`, venue/call/status/source payload and no guest comment/display-name or secrets. Staging confirmed guest-created calls, Venue Mini App ACK/DONE audit rows with `source=venue_miniapp`, Telegram staff-chat ACK/DONE callback edits plus audit rows with `source=telegram_staff_chat`, and guest ability to create a new call after DONE. Audit remains best-effort after the operational transition; row-level `acked_by` / `done_by` / ACK-DONE timestamp columns, CANCELLED UI/lifecycle and broader staff-call UX remain out of scope.
>
> Current checkpoint as of 2026-07-01: Guest Table Context UX Cleanup / Feature-gated Extension Module and Guest Table Session Exit / Expiry UX are CLOSED / staging smoke passed. Real Telegram Mini App QR smoke confirmed correct venue/table context, route/copy address/booking hidden in table context but preserved on the pre-visit venue card, extension hidden without active order/bill or availability and visible only when active order state makes it actionable, and extension disappearing after bill/order close. `Завершить визит` initially failed on staging with `415 Unsupported Media Type`; Mini App now sends `Content-Type: application/json` plus `{ tableToken, tableSessionId }`, e2e asserts the request contract, and post-deploy smoke confirmed user-scoped exit through `guest_table_session_exits`: no stale restore without QR, explicit QR re-entry, empty personal tab allowed, active current-user order/bill or NEW/ACK staff call blocked, DONE staff call not blocked, another guest at the same physical table unaffected, shared `table_sessions` not closed for all guests, and existing menu/cart/order/staff-call/fallback flows still green.
>
> Current checkpoint as of 2026-07-02: Guest-facing bill / display-number / full-bill parity is CLOSED / staging smoke passed. Guest Mini App shows human `Заказ №<display_number>`, selected account label without raw `tabId`, selected-tab bill totals/discounts/service charges and closed/unavailable copy. Venue Mini App detail exposes human personal/shared account context for batches and non-payable rows, keeps full bill discounts/exclusions/service charges visible, and does not use raw `tabId` as the primary visible account label. Telegram staff full bill title includes the same human order display label, and Venue Mini App / Bot / Guest totals match in the verified smoke.
>
> Current checkpoint as of 2026-07-02: Guest Bill Request / Payment Method UX is CLOSED / staging smoke passed. Guest Mini App active order screen now sends JSON `POST /api/guest/order/bill-request` with Authorization, `Content-Type: application/json`, `tableToken`, `tableSessionId`, `tabId` and operational `paymentMethod` (`CARD`/`CASH`/`UNKNOWN`) after guest-facing choices `Картой на месте`, `Наличными`, `Пока не знаю`. Backend validates current user/session/tab/order access, stores bill requests as structured `staff_calls` context, dedupes active `NEW`/`ACK` requests per venue/table_session/tab without duplicate staff-chat spam, allows a new request after `DONE`, and updates the order-scoped live staff-chat activity card with table/order/account/total/payment context when available. Venue Mini App staff-call queue shows bill-request order/account/payment context. This is not online payment, acquiring, Telegram Payments/Stars or automatic bill close.
>
> Current checkpoint as of 2026-07-02: Staff Chat Noise Reduction / Table Activity Card is CLOSED / staging smoke passed. Staff chat is now a radar/shortcut while Venue Mode remains source of truth: new order, reorder, bill request and safely order-linked staff calls update one live order card keyed by `orderId`; unsafe/no-order/ambiguous calls remain standalone; manual `Обновить` preserves order, bill and staff-call activity; activity uses compact markers (`🆕`, `🚨`, `🛎️`, `🧾`, `💳`, `💵`, `❓`); DONE/CANCELLED generic calls no longer stay in active `Оперативно`; and closing the order/bill resolves linked active BILL requests plus closed-visit staff-call leftovers.
>
> Current checkpoint as of 2026-07-02: Hookah preparation placeholder polish is CLOSED / staging smoke passed. Guest Mini App hookah/flavor note fields, including the nested option/flavor flow when semantic metadata is missing or generic, use `Например: покрепче, полегче, больше мяты, без ментола`; drink/food options keep `Например: без сахара, без льда, потеплее`.
>
> Current checkpoint as of 2026-07-03: Platform Billing Cockpit / Owner Payment UX, Platform Billing Renewal / Advance Invoice / Courtesy Days and Staff/Manager invite deep-link sharing polish are CLOSED / staging smoke passed. Platform Owner billing cockpit and Venue Owner subscription screen show safe human subscription/payment state through read-only GET overviews; invoice/checkout creation happens only through explicit POST ensure actions; the manual/fake invoice flow no longer exposes provider-internal fake URLs; manual mark-paid is audited; paid-through and next-payment copy use human dates. Renewal now creates the next period from effective paid-through + 1 day, reuses existing OPEN/PAST_DUE invoices for the same period, and is idempotent. Courtesy/free days are represented in `billing_adjustments` as `COURTESY_DAYS`, require Platform Owner reason, write `BILLING_COURTESY_DAYS_ADDED` audit, and shift effective paid-through/next-payment dates without mutating paid invoice history. Venue Owner sees the adjusted subscription state but cannot mark paid or add courtesy days. Manager/Staff cannot access billing payment controls. Staff/Manager invites now use valid Telegram deep links, copy/share Mini App UX, one selectable invite link field and a secondary fallback command; invite acceptance smoke passed for Manager/Staff. No real acquiring provider, Telegram Stars, recurring card payment, invoice void/reissue, guest order runtime change or staff-chat runtime change is claimed by this checkpoint.
>
> Current checkpoint as of 2026-07-06: Guest Communication UX / Support Tickets MVP is CLOSED / smoke passed. The canonical model is `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET`, `STAFF_CALL` in `docs/COMMUNICATION_MODEL.md`. Guest visible nav is `Чаты` / `Помощь`; catalog and venue detail `Задать вопрос` opens/reuses `VENUE_CHAT`; booking `Открыть переписку` stays `BOOKING_CHAT`; support tickets are separate `SUPPORT_TICKET` threads with Guest/Venue/Platform routing; Platform does not see ordinary `VENUE_CHAT`; Staff sees neither support tickets nor ordinary venue chats; support/venue chat create/reply does not post to staff-chat; guest support/venue chat create and support message routes are rate-limited. Advanced SLA automation, macros, attachments, CSAT, diagnostics and support analytics remain future work.
>
> Current docs checkpoint as of 2026-07-06: Platform cockpit docs are consolidated in `docs/PLATFORM_COCKPIT.md`. Current implementation uses venue lifecycle statuses `DRAFT`, `PUBLISHED`, `HIDDEN`, `PAUSED`, `SUSPENDED`, `ARCHIVED`, `DELETED`; target product states such as `onboarding`, `paused_by_owner`, `suspended_by_platform` and `deletion_requested` require explicit normalization/migration if needed. Manual/fake billing cockpit, owner invites/revoke and Platform support-ticket center are smoke-closed; onboarding request cockpit, placements, Platform analytics, real acquiring/Stars, recurring payments and advanced support remain future/partial.
>
> Current docs checkpoint as of 2026-07-06: Guest growth/retention is consolidated in `docs/GROWTH_RETENTION.md`. Status is `SPEC UPDATED / PARTIAL-FUTURE`: favorites/history baselines and promotion/loyalty foundations are not enough to call the retention product done. MVP terms are `FAVORITE_VENUE`, `VISIT_HISTORY`, `ORDER_HISTORY`, `BOOKING_HISTORY`, `REPEAT_TEMPLATE`, `POST_VISIT_FEEDBACK`, `VENUE_PROMOTION` and `OPT_IN_NOTIFICATION`; promo codes, loyalty stamps/points, referrals, segmentation, paid placement/boosting and advanced recommendations remain future.
>
> Current docs checkpoint as of 2026-07-06: Order/session/tab core is consolidated in `docs/ORDER_SESSION_TAB_CORE.md`. Status is `SPEC UPDATED`: `TABLE_SESSION`, `ACTIVE_TABLE_ORDER`, `ORDER_BATCH`, `TAB`, bill/request/close flow, privacy boundaries and visit-history foundation are now the canonical model. Current runtime docs still say the old table-only active-order risk is closed; remaining gaps are visit entity/history, force-close reason/audit, DB-level uniqueness nuances and broader analytics events.
>
> Current docs checkpoint as of 2026-07-07: Analytics/events are consolidated in `docs/ANALYTICS_EVENTS.md`. Status is `SPEC UPDATED / implementation PARTIAL`: analytics events, audit/event boundaries, KPI formulas, role dashboards and payload privacy rules are now canonical. Platform analytics dashboard, growth events, broad booking/support/billing analytics and client-event ingestion remain future/partial unless specific implementation evidence exists.
>
> Current docs checkpoint as of 2026-07-07: Security/RBAC is consolidated in `docs/SECURITY_RBAC_MATRIX.md`. Status is `UPDATED / permission parity PARTIAL`: roles, scopes, permission matrix, surface parity, dangerous actions, auth/trust boundaries and the security smoke checklist are now canonical. `ADMIN` remains a legacy compatibility alias to `MANAGER`, not a separate product role. Dangerous-action audit coverage remains partial unless specific implementation evidence exists.

# Краткое резюме

Проект уже содержит серьёзный backend-фундамент для Telegram bot + Mini App: auth через Telegram initData, JWT session, venue RBAC, guest catalog, QR/table sessions, cart/order batches, venue order queue, staff chat notifications, staff invites, menu/table management, billing/subscriptions, platform venue management, migrations and tests.

Исторический главный риск этого аудита был core guest order model: в апреле active order был связан с `table_id`, а не с `table_session_id`. Этот риск закрыт в текущем кодовом срезе: active order/table-session/tab scoping, CORS mutation methods, staff-call lifecycle and staff stop-list RBAC были исправлены последующими M1-M6 работами. H2/PostgreSQL active-order and active personal-tab uniqueness fidelity is now also closed as a test/database-fidelity milestone. Guest table context cleanup and user-scoped table-session exit are also closed after staging smoke. Guest/staff bill display parity, Venue Mini App bill-detail parity, Guest Bill Request / Payment Method UX, Staff Chat Noise Reduction / Table Activity Card, hookah preparation placeholder polish, Platform Billing Cockpit / Owner Payment UX, Platform Billing Renewal / Advance Invoice / Courtesy Days, Staff/Manager invite deep-link sharing polish and Guest Communication UX / Support Tickets MVP are staging/smoke-closed as of 2026-07-06. Текущие проверяемые риски: real acquiring provider/Telegram Stars remain future, invoice void/reissue for courtesy conflicts remains a follow-up, billing-created vs manual `SUSPENDED_BY_PLATFORM` distinction remains a follow-up, advanced support/analytics cockpit gaps, booking/reminder rollout controls, and per-venue real Telegram runtime regression.

# Что реально готово

- Telegram webhook/outbox/inbound queue with idempotency/backoff/rate limit: `TelegramWebhookRoutes`, `TelegramInboundUpdateWorker`, `TelegramOutboxWorker`.
- Mini App auth with Telegram initData validation and JWT sessions: `MiniAppAuthRoutes`, `TelegramInitDataValidator`, `SessionTokenService`.
- Guest QR resolve and table session creation: `GuestTableResolveRoutes`, `TableSessionRepository`, `TableTokenRepository`.
- Guest add batch order MVP: `GuestOrderRoutes.post("/add-batch")`, `OrdersRepository.createGuestOrderBatch`.
- Venue order queue/status MVP: `VenueOrderRoutes`, `VenueOrdersRepository`.
- Staff invites and role membership MVP: `VenueStaffRoutes`, `StaffInviteRepository`, `VenueAccessRepository`.
- Venue menu/table management MVP: `VenueMenuRoutes`, `VenueTableRoutes`, `VenueMenuRepository`, `VenueTableRepository`.
- Staff chat link and order notification MVP: `VenueRoutes`, `TelegramBotRouter.handleLinkCommand`, `StaffChatNotifier`.
- Platform venue CRUD/status/subscription settings MVP: `PlatformVenueRoutes`, `PlatformVenueRepository`, `PlatformSubscriptionSettingsRepository`.
- Billing engine/invoices/webhook base: `SubscriptionBillingEngine`, `BillingService`, `BillingWebhookRoutes`.
- PostgreSQL migrations and broad backend tests.

# Что частично готово

- Full bill, discounts, excluded items: backend/Telegram/Mini App management bill path is staging-smoked for display number, discounts, exclusions, service charges and human tab labels; keep money snapshots and role denials in regression.
- Split bill: tabs/invites/consent and active order scoping by `tableSessionId`/`tabId` exist; H2 now mirrors PostgreSQL active-order and active personal-tab uniqueness predicates. Remaining DB nuance: PostgreSQL still permits active `PERSONAL` tabs with `owner_user_id NULL` at schema level, shared-tab uniqueness remains repository-idempotent rather than DB-enforced, and there is no DB-level uniqueness for one active table session per table because runtime locking handles active session resolution.
- Booking: bot/backend and Guest/Venue Mini App screens exist; M7a hold settings is closed and M7b Guest Mini App `Мои брони` is implemented with staging visual parity for Bot `/my` label/time/deadline. M7c adaptive reminders passed a controlled real Telegram staging smoke with legacy-row reconciliation, truthful `QUEUED` outbox semantics, atomic guest attendance, visible Telegram message editing and cross-channel indicators; runtime remains opt-in disabled for rollout. Preorder remains later.
- Staff calls: create/list/ACK/DONE lifecycle exists across Guest/Venue Mini App and bot/staff chat path, and applied ACK/DONE transitions are audited from Venue Mini App and Telegram staff-chat surfaces. Staff-chat live order cards now hide DONE/CANCELLED generic calls from active `Оперативно`, and closing an order/bill resolves linked active BILL requests plus closed-visit staff-call leftovers. CANCELLED UI/lifecycle, row-level ACK/DONE actor/timestamp columns and SLA/escalation remain later.
- Menu constructor: category/item CRUD, structured options/flavors, base profiles and item/option stop-list exist; photos/descriptions/top-list polish remains later.
- Stop-list: STAFF operational item/option availability is aligned between bot and Mini App; content editing remains MANAGER/OWNER.
- Table QR: batch create/rotate/export есть, single edit/delete/capacity incomplete.
- Statistics: Telegram stats and Venue Mini App read-only stats exist; custom ranges/platform analytics remain later.
- Settings: broad Telegram setup exists; Mini App settings now covers booking hold, shift extension settings, public profile/card basics with provider-free structured location and weekly hours/date exceptions. Info/media editing, preview/readiness and other broad settings should still be expanded through small slices.
- Platform mode: venues/status/owners/subscription, manual billing cockpit MVP and support-ticket center are staging/smoke-closed; `docs/PLATFORM_COCKPIT.md` is the current source for Platform lifecycle/billing/support boundaries. Onboarding request cockpit, placements, analytics cockpit, real acquiring provider, Telegram Stars and recurring payments remain incomplete.

# Что отсутствует

- Advanced support features beyond MVP: SLA automation, macros, attachments, CSAT, diagnostics reports and support analytics.
- Guest growth/retention as full cross-channel product flows: `FAVORITE_VENUE`, `VISIT_HISTORY`, `ORDER_HISTORY`, `BOOKING_HISTORY`, `REPEAT_TEMPLATE`, `POST_VISIT_FEEDBACK`, simple `VENUE_PROMOTION` and `OPT_IN_NOTIFICATION`. Several backend/bot baselines may exist, but the complete product loop remains partial/future until Bot + Mini App + backend smoke proves it.
- Real acquiring provider, Telegram Stars and automatic recurring card payments.
- Invoice void/reissue for open future-invoice conflicts after courtesy adjustments.
- Better distinction between billing-created and manual `SUSPENDED_BY_PLATFORM` before broader auto-reactivation.
- Platform analytics dashboards and support/billing metrics beyond operational audit rows.
- Clear `venue_admin` behavior distinct from `manager`.
- Wider frontend/browser e2e beyond current smoke harness.

# Current top P0/P1/P2 gaps after 2026-07-02 checkpoint

No confirmed production P0 was found in the 2026-07-02 checkpoint after M9a/M9b, Platform Owner invite/revoke closure, H2/PostgreSQL uniqueness fidelity validation, the Mini App mutation/operational verification pack, Staff Call Lifecycle ACK/DONE audit hardening, the two guest table-context milestones, Guest Bill / Display-Number / Full-Bill Parity, Guest Bill Request / Payment Method UX, Staff Chat Noise Reduction / Table Activity Card and hookah preparation placeholder polish. Current priorities:

1. **P1/P2**: Real acquiring provider or Telegram Stars rollout, only if commercial launch requires online payment. Current billing MVP is manual/fake-provider only and must not be described as card acquiring or Stars.
2. **P2**: Guest growth/retention MVP from `docs/GROWTH_RETENTION.md`: favorites, visit/order/booking history, repeat templates, post-visit feedback, simple venue promotions and opt-in notification rules. Several backend/account surfaces exist, but repeat-order, post-visit feedback and promo/retention notification loops are not launch-complete.
3. **P2**: Platform analytics/operations cockpit polish: onboarding requests, placements, lifecycle risk/health indicators, support metrics and billing metrics after provider semantics are stable.

# Рекомендуемый порядок дальнейшей работы

1. Keep Support/tickets MVP, Guest Communication UX split, Platform billing cockpit/renewal/courtesy, staff invite deep-link sharing, Platform Owner invite/revoke, M7b/M7c, M9b schedule, Mini App mutation/staff-call/fallback payload paths, bill parity, staff-chat activity card and guest table-context exit in regression.
2. Plan real acquiring provider / Telegram Stars as a separate milestone with provider secrets, webhook hardening and payment audit; do not fold it into the manual billing MVP.
3. Defer growth feature implementation until visit/order/session history, opt-in notification rules and owner promotion workflow are clear; use `docs/GROWTH_RETENTION.md` before opening the milestone.

# Какие функции лучше не трогать пока

- Promotions/referrals/reviews/favorites/repeat: product core has higher-risk gaps and the growth MVP depends on stable visit/order/session history.
- Advanced analytics dashboards: first ensure events are complete and reliable.
- Deep menu media/top-list polish: first fix order modifiers and guest bill correctness.
- Real acquiring provider, Telegram Stars and recurring billing: current manual billing cockpit is staging-smoked, but production payment providers need a separate provider-specific design and smoke.

# Какие функции нужно перепроверить вручную

- Mini App browser preflight for category/item update/delete, staff role update/remove, platform status/subscription saves.
- QR scan from real Telegram client into Mini App and fallback bot flow.
- Guest ordering with two different table sessions at the same physical table.
- Shared tab invite/join and active order visibility between two Telegram users.
- Staff call from Mini App after payload fix.
- Staff stop-list access for STAFF in Telegram versus Mini App.
- Manager/owner/staff nav visibility in Mini App.
- Staff chat notifications for order, booking, bot staff call, Mini App staff call.
- Platform owner Telegram access versus Mini App `mode=platform` access.

# Ключевые продуктовые блоки

| Блок | Статус | Evidence | Что дальше |
|---|---|---|---|
| Order/session/tab core | SPEC UPDATED / runtime scoping closed with partial follow-ups | `docs/ORDER_SESSION_TAB_CORE.md`, `GuestOrderRoutes`, `TableSessionRepository`, `GuestTabsRoutes`, `VenueOrdersRepository` | Keep table-session/tab scoping, personal/shared tab visibility, batch idempotency, bill request and close/expire behavior in regression; visit entity/history, force-close audit and DB-level uniqueness nuances remain future/partial |
| Guest QR/table flow | DONE / staging smoke passed | `GuestTableResolveRoutes`, `TableSessionRepository`, `GuestTableResolveRoutesTest`, `guest_table_session_exits` | Keep QR/table restore, user-scoped exit, active obligation blocking and explicit QR re-entry in regression |
| Guest menu/order/cart/comment | PARTIAL | `GuestMenuRepository`, `GuestOrderRoutes`, `cart.ts` | Options/modifiers, fallback contract |
| Order batches / дозаказы | PARTIAL | `OrdersRepository.createGuestOrderBatch`, `order_batches`, `VenueOrdersRepository` | Full DTO and session scoping |
| Venue-side orders queue | DONE/PARTIAL | `VenueOrderRoutes`, `venueOrders.ts`, `venueOrderDetail.ts`, `guest-smoke.spec.ts` | Display number/full bill/account labels are staging-smoked; keep status/close/bill regression |
| Full bill / счёт | DONE/PARTIAL | `TelegramBotRouter.showVenueStaffOrderFullDetails`, `VenueOrdersRepository`, `OrderBillSnapshot`, `POST /api/guest/order/bill-request` | Bill request/payment method UX and live staff-chat activity-card updates are staging-smoked; keep payment-method/dedupe/live-card regression |
| Discounts / excluded items | DONE/PARTIAL | `V57__order_batch_item_exclusions.sql`, `V58__order_batch_item_discounts.sql`, `VenueOrdersRepository`, `venueOrderDetail.ts` | Keep API/UI and role-denial regression |
| Staff calls | DONE/PARTIAL | `GuestStaffCallRoutes`, `StaffCallRepository`, `guestVenue.ts`, `venueCalls.ts` | M5 lifecycle, notification parity and ACK/DONE audit hardening are CLOSED / staging smoke passed; keep Venue Mini App and Telegram staff-chat ACK/DONE audit paths in regression |
| Staff/manager/owner roles | DONE/PARTIAL | `VenueRbac.kt`, `VenueRoleMapping.fromDb`, `TelegramBotRouter` mappings | `ADMIN` is legacy alias to `MANAGER`; Platform Mini App no longer offers it; keep role smoke in regression |
| Staff invites | DONE/PARTIAL | `VenueStaffRoutes`, `StaffInviteRepository`, tests | Deep-link copy/share polish and Manager/Staff acceptance are staging-smoked; keep identity labels and role-denial regression |
| Menu constructor | PARTIAL | `VenueMenuRoutes`, `venueMenu.ts` | Options/photos/top-list |
| Stop-list including options/flavors | PARTIAL/RISKY | `TelegramBotRouter` stop-list methods, `VenueMenuRepository` | Mini App option UI and permissions |
| Table QR generation/fallback | PARTIAL | `VenueTableRoutes`, `TelegramBotRouter` table flow | Single CRUD and diagnostics |
| Statistics | PARTIAL | `VenueStatsRepository`, `TelegramBotRouter.showStatsEntry` | Mini App stats |
| Settings | PLACEHOLDER/PARTIAL | `venueSettings.ts`, `VenueSettingsRepository`, hidden Telegram handler | Make real or hide |
| Display order number per day | DONE/PARTIAL | `OrdersRepository.insertActiveOrder`, `orders.display_number`, `OrdersRepositoryTest` | Runtime display uses venue-local date; no DB uniqueness constraint |
| Booking flow | PARTIAL | `GuestBookingRoutes`, `VenueBookingRoutes`, bot booking methods | Mini App screens and analytics event |
| Split bill / personal/shared tabs | DONE/PARTIAL | `GuestTabsRoutes`, `GuestTabsRepository`, `cart.ts`, `order.ts`, `venueOrderDetail.ts` | Tab-scoped order views are code-test-backed; shared-tab DB uniqueness remains repository-idempotent |
| Platform mode | PARTIAL | `platformApp.ts`, `PlatformVenueRoutes`, `docs/PLATFORM_COCKPIT.md` | Manual billing/support MVP is smoke-closed; onboarding requests, placements, analytics/risk cockpit and lifecycle normalization remain future/partial |
| Guest growth/retention | SPEC UPDATED / PARTIAL-FUTURE | `docs/GROWTH_RETENTION.md`, account/history/favorites baselines where present | MVP is favorite venues, visit/order/booking history, repeat template, post-visit feedback, simple venue promotions and opt-in notifications; promo codes, loyalty, referrals, paid placement boosting and segmentation remain future |
| Onboarding venue/application flow | PARTIAL | `venue_connection_requests`, `TelegramBotRouterVenueConnectionRequestFlowTest` | Integrate with platform UI |
| Subscriptions/billing | DONE/PARTIAL | `SubscriptionRepository`, `SubscriptionBillingEngine`, `PlatformBillingRoutes`, `billing_adjustments` | Manual/fake billing cockpit, advance next invoice and courtesy days are staging-smoked; real acquiring, Stars, void/reissue and suspended-status distinction remain future |
| Support/tickets | MVP / smoke passed | `SupportRoutes`, `SupportThreadRepository`, `support_threads.thread_type`, `guestSupportThreads.ts`, `venueMessages.ts`, `platformSupport.ts` | Advanced SLA/macros/attachments/CSAT/diagnostics/support analytics |
| Analytics/events | SPEC UPDATED / PARTIAL | `docs/ANALYTICS_EVENTS.md`, `AnalyticsEventRepository`, event writes, `AuditLogRepository` where present | Verify event emission and payload safety before dashboards; Platform cockpit still needs venue lifecycle/subscription, billing, support TTFR/TTR/escalation/reopen/CSAT, onboarding funnel and growth reports |
| Security/RBAC matrix | UPDATED / PARTIAL | `docs/SECURITY_RBAC_MATRIX.md`, `VenueRbac.kt`, role docs and route tests where present | Keep server-side role/scope checks, Staff denial, Manager billing denial, cross-venue isolation, table/session/tab membership and dangerous-action audit in regression |
| Staff chat notifications | PARTIAL | `StaffChatNotifier`, bot notify methods | Mini App staff-call notification path is CLOSED / code-test verification passed; keep orders/bookings/shift-extension runtime delivery in regression |
| Telegram commands | PARTIAL | `/start`, `/menu`, `/my`, `/help`, `/link`, `/unlink`, `/link_test` | Add support/help consistency and QR test mode |
| Mini App env/CORS/initData | DONE/PARTIAL | `Application.kt`, `TelegramInitDataValidator`, `miniapp/src/main.ts` | CORS mutation methods are CLOSED / code-test verification passed; keep preflight/env diagnostics in regression |

# Технический долг

- Historical active-order table-only risk is closed in current code; H2/PostgreSQL uniqueness fidelity for active orders and personal tabs is also closed.
- DTO/UI mismatch: bill/display-number/tab-label fields are now surfaced in the relevant Mini App bill views; keep this class of issue in regression for future DTO additions.
- Telegram and Mini App permission divergence.
- Placeholder screens/buttons visible to users.
- Multiple flow implementations for same product function: orders, menu, bookings, settings.
- Callbacks/menu buttons without full implementation: platform owner Telegram sections, promotions, settings visibility.
- SQL/migration follow-ups: PostgreSQL still permits active `PERSONAL` tabs with `owner_user_id NULL` at schema level; shared-tab uniqueness is repository-idempotent but not DB-enforced; one active table session per table is runtime-locking behavior, not a DB uniqueness constraint.
- Audit gaps for role/menu/stop-list changes.
- Product terms drift: lifecycle statuses and any future Venue Admin model. `ADMIN` is currently documented as a legacy alias to `MANAGER`, and Platform Owner config parity is implemented.

# Тесты

Найденные тестовые группы:
- Health/DB/migrations: `HealthTest`, `DbHealthTest`, `PostgresMigrationSmokeTest`, `TableSessionsMigrationV28PostgresTest`.
- Auth/session/security: `TelegramAuthRouteTest`, `TelegramInitDataValidatorTest`, `SessionAuthTest`, `IpAllowlistTest`.
- Guest: `GuestVenueRoutesTest`, `GuestVenueMenuRoutesTest`, `GuestTableResolveRoutesTest`, `GuestOrderRoutesTest`, `GuestTabsRoutesTest`, `GuestStaffCallRoutesTest`, `GuestBookingRoutesTest`, `ProdCriticalGuestVenueFlowTest`, `VenueAvailabilityResolverTest`.
- Venue: `VenueRbacRoutesTest`, `VenueMenuRoutesTest`, `VenueOrderRoutesTest`, `VenueStaffRoutesTest`, `VenueTableRoutesTest`, `AuditLogRepositoryTest`.
- Platform/billing: `PlatformRoutesTest`, `PlatformVenueRoutesTest`, `PlatformVenueRepositoryTest`, `PlatformVenueMemberRepositoryTest`, `PlatformSubscriptionRoutesTest`, `PlatformBillingRoutesTest`, billing repository/service/webhook/engine tests.
- Telegram: `TelegramBotRouterTableTokenTest`, `TelegramBotRouterLinkCommandTest`, `TelegramBotRouterIdempotencyTest`, `TelegramBotRouterVenueConnectionRequestFlowTest`, `StaffChatNotifierTest`, webhook/outbox/inbound/keyboards/url/log tests.

Ключевые сценарии, которые покрыты:
- Telegram initData validation.
- Guest QR table resolve and subscription availability.
- Guest orders and tabs backend routes.
- Venue RBAC, menu, table, order routes.
- Staff invite accept and last owner protection.
- Platform venue/subscription/billing routes.
- Staff chat link and notification base.

Ключевые сценарии без достаточного покрытия:
- Mini App frontend end-to-end flows; frontend tests не найдены.
- CORS preflight for `PATCH/DELETE/PUT` as regression coverage; the old mutation-method gap is documented as fixed/superseded.
- H2/PostgreSQL active-order and personal-tab uniqueness fidelity is closed; keep it in regression.
- Mini App staff call payload and notification regression smoke; M5 lifecycle is closed.
- Fallback chat order `CHAT_ORDER` payload regression smoke if fallback chat ordering is touched.
- Staff stop-list permission parity.
- Bill/display-number/account-label parity in Mini App and Telegram staff full bill.
- Guest bill request/payment method JSON request contract, dedupe and staff-chat context.
- Booking status analytics.
- Venue settings save.

Рекомендуемые smoke tests:
1. `OPTIONS` preflight for every Mini App mutation route.
2. Guest scan QR, create order, close/expire session, scan same table, verify new order.
3. Two users shared tab: personal/shared order visibility remains scoped.
4. Mini App staff call creates row and staff chat notification.
5. `WebApp.sendData` fallback creates quick chat order.
6. STAFF can manage operational stop-list for menu items/options, but cannot edit menu content.
7. Manager cannot access owner-only settings/staff chat link in UI and API.
8. Booking create/update/cancel emits analytics and appears in venue queue.
9. Display number resets per venue day and appears in Guest/Venue Mini App order surfaces.
10. Guest opens `Мой заказ`, requests bill with `Картой на месте`, repeats while active to verify duplicate copy/no duplicate staff-chat notification, then repeats after DONE with `Наличными`.
11. Order/session/tab core regression from `docs/ORDER_SESSION_TAB_CORE.md`: first batch and second batch use the same active order/session with separate batches; second guest cannot see the first guest personal tab; fallback chat order uses the same session/tab rules; stop-list change before submit blocks stale unavailable item/option.
12. Analytics/events regression from `docs/ANALYTICS_EVENTS.md`: QR scan, add-batch, batch status change, staff-call create, support/billing dangerous changes and audit payloads are emitted/recorded where implemented and contain no raw message text/initData/payment secrets/card data.
13. Security/RBAC regression from `docs/SECURITY_RBAC_MATRIX.md`: Guest/Staff/Manager/Venue/Platform denials, cross-venue isolation, table/session/tab membership, Platform support-only visibility, QR token revocation and dangerous-action audit all follow the canonical matrix.
14. Owner invite from platform can be accepted end-to-end.

# Созданные файлы отчёта

- `docs/audit/ROLE_PLATFORM_OWNER.md`
- `docs/audit/ROLE_VENUE_OWNER.md`
- `docs/audit/ROLE_MANAGER.md`
- `docs/audit/ROLE_STAFF.md`
- `docs/audit/ROLE_GUEST.md`
- `docs/audit/PRODUCT_AUDIT_SUMMARY.md`
- `docs/SECURITY_RBAC_MATRIX.md`
