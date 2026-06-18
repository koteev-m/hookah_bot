# Product Audit Summary

Дата ревизии: 2026-04-28. Режим: статический read-only аудит кода и `docs/PRODUCT_SPEC.md`; код и тесты не изменялись.

> Historical audit snapshot. The P0/P1 list in this file is not the current backlog.
>
> Current correction as of 2026-06-03: many items below were later fixed or changed by product decision, including active order table-session scoping, Mini App CORS mutation methods, Mini App staff call payload/lifecycle, STAFF stop-list policy, Venue Mini App full bill/bill controls/close, bookings MVP, pre-QR guest menu behavior, platform owner access, commercial terms sync and venue lifecycle. Check `docs/UPDATED_PRODUCT_AI_ROADMAP.md`, `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` and current code before using any item here as implementation scope.
>
> Current checkpoint as of 2026-06-18: M1-M5 Venue Bot-to-Mini-App parity slices are code-backed through IA shell, stats, bookings, support inbox lifecycle and staff calls. M4A is staging-smoke closed; M4B/M4C and M5 have code/test/e2e evidence but still require staging/runtime smoke where noted. Remaining launch-relevant gaps are no longer the old P0 order/session/CORS/staff-call list; use the current roadmap for staff-chat diagnostics/unlink, broad venue settings slices, platform money/onboarding, promotions/preview and runtime smoke priorities.

# Краткое резюме

Проект уже содержит серьёзный backend-фундамент для Telegram bot + Mini App: auth через Telegram initData, JWT session, venue RBAC, guest catalog, QR/table sessions, cart/order batches, venue order queue, staff chat notifications, staff invites, menu/table management, billing/subscriptions, platform venue management, migrations and tests.

Главный продуктовый риск: core guest order model расходится с концепцией. Спецификация говорит "один active order на table_session", но код создаёт/ищет active order по `table_id`. В связке со split bill это создаёт риск смешивания гостей, смен и вкладок. Второй критичный риск: Mini App и Telegram имеют разные возможности и местами разные permissions.

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

- Full bill, discounts, excluded items: backend/Telegram есть, Mini App нет.
- Split bill: tabs/invites/consent есть, active order endpoint Mini App не scoped.
- Booking: bot/backend есть, Mini App guest/venue screens отсутствуют.
- Staff calls: create/list есть, ack/done отсутствуют; Mini App payload broken.
- Menu constructor: category/item CRUD есть, options backend есть, Mini App options/photos/top-list incomplete.
- Stop-list: items/options в Telegram, item toggle в Mini App, permissions расходятся.
- Table QR: batch create/rotate/export есть, single edit/delete/capacity incomplete.
- Statistics: Telegram stats есть, Mini App/platform analytics отсутствуют.
- Settings: Telegram partial/hidden, Mini App placeholder.
- Platform mode: venues/status/owners/subscription есть, requests/billing/support/analytics cockpit incomplete.

# Что отсутствует

- Support/tickets product block.
- Promotions/referrals/reviews/favorites/repeat order/history как продуктовые flows.
- Venue-facing billing checkout/payment UX.
- Platform analytics dashboards.
- Full Mini App booking management.
- Staff call acknowledgement/completion.
- Proper menu item modifiers/options persisted in orders.
- Clear `venue_admin` behavior distinct from `manager`.
- Frontend tests for Mini App.

# Топ-10 P0/P1 доработок

1. **P0**: перевести active order identity с `table_id` на `table_session_id`.
   Evidence: `OrdersRepository.findActiveOrderForUpdate`, `OrdersRepository.findActiveOrderDetails`, migration `V9__orders_active_unique_index.sql`.

2. **P0**: сделать guest active order endpoint scoped by `tableSessionId` and `tabId`.
   Evidence: `GuestOrderRoutes.get("/active")` принимает только `tableToken`, `OrdersRepository.findActiveOrderDetails(tableId)`.

3. **P0/P1**: исправить Mini App CORS для `PUT/PATCH/DELETE`.
   Evidence: `Application.kt` разрешает только `Options`, `Get`, `Post`, while `PlatformVenueRoutes`, `VenueMenuRoutes`, `VenueStaffRoutes` use other methods.

4. **P1**: починить Mini App staff call.
   Evidence: backend `GuestStaffCallDtos.kt` требует `tableSessionId`, frontend `miniapp/src/shared/api/guestDtos.ts` не содержит его, `guestVenue.ts` не отправляет.

5. **P1**: согласовать fallback chat order payload.
   Evidence: `cart.ts` sends `type: "CHAT_ORDER"`, `TelegramBotRouter.handleJsonWebAppData` expects `cmd`.

6. **P1**: устранить role confusion `ADMIN == MANAGER`.
   Evidence: `VenueRoleMapping.fromDb`, `TelegramBotRouter.resolvePrimaryVenueBotAccess`, `PlatformVenueRoutes` owner/admin assignment.

7. **P1**: синхронизировать staff stop-list permissions.
   Evidence: `VenueRbac.kt` staff lacks `MENU_MANAGE`, but `TelegramBotRouter.setVenueStaffStopListItemAvailability` and option equivalent allow changes.

8. **P1**: добавить staff call lifecycle.
   Evidence: `staff_calls` create/list found, no ack/done route/callback found.

9. **P1**: вывести display order number, full bill, discounts and excluded items in Mini App.
   Evidence: `orders.display_number` exists in `OrdersRepository.insertActiveOrder`; `venueOrders.ts` displays `Order #id`; `VenueOrderDtos` lacks bill fields.

10. **P1**: сделать venue settings real or remove placeholder.
    Evidence: `miniapp/src/screens/venueSettings.ts` disables inputs and shows save unavailable; Telegram settings handler is hidden from owner/manager keyboards.

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
