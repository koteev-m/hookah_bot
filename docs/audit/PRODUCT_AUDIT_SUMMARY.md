# Product Audit Summary

Дата ревизии: 2026-04-28. Режим: статический read-only аудит кода и `docs/PRODUCT_SPEC.md`; код и тесты не изменялись.

> Historical audit snapshot. The P0/P1 list in this file is not the current backlog.
>
> Current correction as of 2026-06-03: many items below were later fixed or changed by product decision, including active order table-session scoping, Mini App CORS mutation methods, Mini App staff call payload/lifecycle, STAFF stop-list policy, Venue Mini App full bill/bill controls/close, bookings MVP, pre-QR guest menu behavior, platform owner access, commercial terms sync and venue lifecycle. Check `docs/UPDATED_PRODUCT_AI_ROADMAP.md`, `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` and current code before using any item here as implementation scope.
>
> Current checkpoint as of 2026-06-19: M1-M6 Venue Bot-to-Mini-App parity slices are closed through IA shell, stats, bookings, support inbox lifecycle, staff calls and staff-chat management. M7a booking hold settings is CLOSED / staging smoke passed. M7b Guest Mini App `Мои брони` is implemented and ready for staging smoke. Remaining launch-relevant gaps are no longer the old P0 order/session/CORS/staff-call list; use the current roadmap for adaptive reminders, broader venue settings slices, platform money/onboarding, promotions/preview and runtime regression priorities.

# Краткое резюме

Проект уже содержит серьёзный backend-фундамент для Telegram bot + Mini App: auth через Telegram initData, JWT session, venue RBAC, guest catalog, QR/table sessions, cart/order batches, venue order queue, staff chat notifications, staff invites, menu/table management, billing/subscriptions, platform venue management, migrations and tests.

Исторический главный риск этого аудита был core guest order model: в апреле active order был связан с `table_id`, а не с `table_session_id`. Этот риск закрыт в текущем кодовом срезе: active order/table-session/tab scoping, CORS mutation methods, staff-call lifecycle and staff stop-list RBAC были исправлены последующими M1-M6 работами. Текущие проверяемые риски: P1 test-fidelity gap between PostgreSQL partial unique active-order constraint and H2 non-unique index, broad Venue Mini App settings still mostly bot-canonical, Platform Mini App cockpit gaps, and per-venue real Telegram runtime smoke.

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

- Full bill, discounts, excluded items: backend/Telegram/Mini App management bill path exists; keep money snapshots and role denials in regression.
- Split bill: tabs/invites/consent and active order scoping by `tableSessionId`/`tabId` exist; H2/PostgreSQL active-order uniqueness parity remains a test-fidelity follow-up.
- Booking: bot/backend and Guest/Venue Mini App screens exist; M7a hold settings is closed and M7b Guest Mini App `Мои брони` is implemented for staging smoke; runtime reminders/preorder remain later.
- Staff calls: create/list/ACK/DONE lifecycle exists across Guest/Venue Mini App and bot/staff chat path; cancel/SLA/escalation remain later.
- Menu constructor: category/item CRUD, structured options/flavors, base profiles and item/option stop-list exist; photos/descriptions/top-list polish remains later.
- Stop-list: STAFF operational item/option availability is aligned between bot and Mini App; content editing remains MANAGER/OWNER.
- Table QR: batch create/rotate/export есть, single edit/delete/capacity incomplete.
- Statistics: Telegram stats and Venue Mini App read-only stats exist; custom ranges/platform analytics remain later.
- Settings: broad Telegram setup exists; Mini App settings now covers booking hold and shift extension settings, but broader profile/card/hours settings should still be expanded through small slices.
- Platform mode: venues/status/owners/subscription есть, requests/billing/support/analytics cockpit incomplete.

# Что отсутствует

- General support/tickets product block beyond booking threads.
- Promotions/referrals/reviews/favorites/repeat order/history as full cross-channel product flows; several backend/bot baselines exist, but Mini App parity varies by feature.
- Venue-facing billing checkout/payment UX.
- Platform analytics dashboards.
- Clear `venue_admin` behavior distinct from `manager`.
- Wider frontend/browser e2e beyond current smoke harness.

# Current top P0/P1/P2 gaps after post-M6 checkpoint

No confirmed production P0 was found in the post-M6 checkpoint. Current priorities:

1. **P1**: stage-smoke M7b Guest Mini App `Мои брони`, including public booking number parity with Bot `/my`, venue-local `Держим до`, user scoping, multi-venue cards, guest reschedule and cancellation.
2. **P1**: continue small Venue Mini App settings slices: profile/card, hours/exceptions and notification toggles, not one bulk endpoint.
3. **P1**: Platform Mini App hardening: owner invite deep link/copy, remove misleading `ADMIN` owner assignment option, surface quota summary where backend exists.
4. **P1**: PostgreSQL/H2 test-fidelity gap for active-order uniqueness: PostgreSQL has a partial unique index by `table_session_id`, H2 migration has only non-unique indexes.
5. **P2**: General guest support ticket creation beyond booking threads.
6. **P2**: Guest Mini App repeat/favorite mutation parity and promotion/review surfaces.

# Рекомендуемый порядок дальнейшей работы

1. Stabilize order/session/tab core: active order per `table_session_id`, tab-scoped views, migration/tests.
2. Fix broken Mini App flows: CORS methods, staff call payload, fallback chat order.
3. Align permissions: `ADMIN/MANAGER`, staff stop-list, client nav by permissions.
4. Complete operational venue Mini App: display number, full bill, discounts/exclusions, staff call ack/done, bookings queue.
5. Complete menu MVP: options/modifiers persisted in order, photos/descriptions/top-list if still required by concept.
6. Complete owner/platform cockpit: settings, stats, billing invoices, onboarding requests.
7. Add support/tickets and growth features after core order/billing stability.

# Какие функции лучше не трогать пока

- Promotions/referrals/reviews/favorites: product core has higher-risk gaps.
- Advanced analytics dashboards: first ensure events are complete and reliable.
- Deep menu media/top-list polish: first fix order modifiers and guest bill correctness.
- Venue self-service billing UI: first stabilize subscription state and platform billing cockpit.

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
| Guest QR/table flow | DONE/PARTIAL | `GuestTableResolveRoutes`, `TableSessionRepository`, `GuestTableResolveRoutesTest` | Связать order with table_session |
| Guest menu/order/cart/comment | PARTIAL | `GuestMenuRepository`, `GuestOrderRoutes`, `cart.ts` | Options/modifiers, fallback contract |
| Order batches / дозаказы | PARTIAL | `OrdersRepository.createGuestOrderBatch`, `order_batches`, `VenueOrdersRepository` | Full DTO and session scoping |
| Venue-side orders queue | PARTIAL | `VenueOrderRoutes`, `venueOrders.ts`, `venueOrderDetail.ts` | Display number/full bill/prices |
| Full bill / счёт | PARTIAL | `TelegramBotRouter.showVenueStaffOrderFullDetails`, `VenueOrdersRepository` | Mini App implementation |
| Discounts / excluded items | PARTIAL | `V57__order_batch_item_exclusions.sql`, `V58__order_batch_item_discounts.sql`, `VenueOrdersRepository` | API/UI exposure |
| Staff calls | PARTIAL/BROKEN | `GuestStaffCallRoutes`, `StaffCallRepository`, `guestVenue.ts` | Fix payload, notify, ack/done |
| Staff/manager/owner roles | PARTIAL/RISKY | `VenueRbac.kt`, `VenueRoleMapping.fromDb`, `TelegramBotRouter` mappings | Resolve `ADMIN`, staff stop-list |
| Staff invites | PARTIAL | `VenueStaffRoutes`, `StaffInviteRepository`, tests | Audit and unified UX |
| Menu constructor | PARTIAL | `VenueMenuRoutes`, `venueMenu.ts` | Options/photos/top-list |
| Stop-list including options/flavors | PARTIAL/RISKY | `TelegramBotRouter` stop-list methods, `VenueMenuRepository` | Mini App option UI and permissions |
| Table QR generation/fallback | PARTIAL | `VenueTableRoutes`, `TelegramBotRouter` table flow | Single CRUD and diagnostics |
| Statistics | PARTIAL | `VenueStatsRepository`, `TelegramBotRouter.showStatsEntry` | Mini App stats |
| Settings | PLACEHOLDER/PARTIAL | `venueSettings.ts`, `VenueSettingsRepository`, hidden Telegram handler | Make real or hide |
| Display order number per day | PARTIAL | `OrdersRepository.insertActiveOrder`, `orders.display_number` | Expose in Mini App and venue timezone |
| Booking flow | PARTIAL | `GuestBookingRoutes`, `VenueBookingRoutes`, bot booking methods | Mini App screens and analytics event |
| Split bill / personal/shared tabs | PARTIAL/RISKY | `GuestTabsRoutes`, `GuestTabsRepository`, `cart.ts` | Tab-scoped order views |
| Platform mode | PARTIAL | `platformApp.ts`, `PlatformVenueRoutes` | Billing/support/analytics/request cockpit |
| Onboarding venue/application flow | PARTIAL | `venue_connection_requests`, `TelegramBotRouterVenueConnectionRequestFlowTest` | Integrate with platform UI |
| Subscriptions/billing | PARTIAL | `SubscriptionRepository`, `SubscriptionBillingEngine`, `PlatformBillingRoutes` | Checkout and UI |
| Support/tickets | MISSING | No product routes/tables found | Design MVP |
| Analytics/events | PARTIAL | `AnalyticsEventRepository`, event writes | Add booking/support events and UI |
| Staff chat notifications | PARTIAL | `StaffChatNotifier`, bot notify methods | Cover calls/bookings consistently |
| Telegram commands | PARTIAL | `/start`, `/menu`, `/my`, `/help`, `/link`, `/unlink`, `/link_test` | Add support/help consistency and QR test mode |
| Mini App env/CORS/initData | PARTIAL/RISKY | `Application.kt`, `TelegramInitDataValidator`, `miniapp/src/main.ts` | Fix CORS methods and env diagnostics |

# Технический долг

- Active order uniqueness by table instead of table session.
- DTO/UI mismatch: backend has fields not surfaced in Mini App.
- Telegram and Mini App permission divergence.
- Placeholder screens/buttons visible to users.
- Multiple flow implementations for same product function: orders, menu, bookings, settings.
- Callbacks/menu buttons without full implementation: platform owner Telegram sections, promotions, settings visibility.
- SQL/migration risk: H2 and PostgreSQL active order uniqueness differ around partial index behavior.
- Audit gaps for role/menu/stop-list changes.
- Product terms drift: `ADMIN`, lifecycle statuses, platform owner config.

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
- CORS preflight for `PATCH/DELETE/PUT`.
- Active order scoped by table session and tab.
- Two table sessions at same table with previous active order.
- Mini App staff call payload and notification.
- Fallback chat order `CHAT_ORDER` payload.
- Staff stop-list permission parity.
- Display order number in Mini App.
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
9. Display number resets per venue day and appears in Mini App queue/detail.
10. Owner invite from platform can be accepted end-to-end.

# Созданные файлы отчёта

- `docs/audit/ROLE_PLATFORM_OWNER.md`
- `docs/audit/ROLE_VENUE_OWNER.md`
- `docs/audit/ROLE_MANAGER.md`
- `docs/audit/ROLE_STAFF.md`
- `docs/audit/ROLE_GUEST.md`
- `docs/audit/PRODUCT_AUDIT_SUMMARY.md`
