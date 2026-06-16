# Venue Bot-to-Mini-App Parity Program

Дата: 2026-06-16. Режим: audit + first bounded milestone. Цель: переносить уже реализованную Telegram Bot venue-management логику в Venue Mini App без нового продуктового моделирования и без одного большого небезопасного diff.

## Executive Summary

Telegram Bot остаётся самой широкой venue-management поверхностью: selected venue hub уже разделён на `Работа смены`, `Настройка заведения`, `Статистика`, `Продвижение`, `Предпросмотр для гостя`. Venue Mini App уже покрывает operational core, M1 сгруппировал работающие экраны, а M2 добавил backend-backed read-only `Статистика`. Главное правило программы: Mini App показывает только работающие backend-backed экраны; `Продвижение` и `Предпросмотр для гостя` не должны появляться как основные кнопки до появления реальных Mini App routes/screens.

M1 безопасно привёл Venue Mini App shell к bot-like information architecture и сделал видимым уже реализованный экран `Продления` для ролей с `SHIFT_EXTENSION_VIEW`. M2 добавляет первый новый Mini App parity slice: read-only статистику для OWNER/MANAGER на существующем `VenueStatsRepository`, без новой аналитической модели и без миграций.

## Current Source of Truth

- Canonical roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`
- Product concept/spec: `docs/PRODUCT_SPEC.md`
- Launch smoke: `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`
- Historical parity evidence: `docs/audit/BOT_MINIAPP_PARITY_AUDIT.md`
- Bot venue hub: `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramKeyboards.kt`
- Bot venue handlers: `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- Venue Mini App shell: `miniapp/src/screens/venueApp.ts`
- Venue API wrappers: `miniapp/src/shared/api/venueApi.ts`
- Venue RBAC: `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueRbac.kt`

## Parity Matrix

| Domain | Bot feature/status | Backend support | Mini App support | RBAC/permission notes | Files involved | Risk | Recommended milestone | Validation needed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A. Work shift / orders | Implemented. Bot shift hub has `📦 Заказы`; callbacks include `staff_venue_orders_root:*`, order detail/status/bill callbacks. | Implemented: queue/detail/audit/status/reject/close/item bill adjustment routes. | Implemented: order queue/detail, full bill, status actions, bill controls where allowed. | `ORDER_QUEUE_VIEW`, `ORDER_STATUS_UPDATE`; STAFF can operate order status/close but not manager-only bill edits. | `TelegramBotRouter.kt`, `TelegramKeyboards.kt`, `VenueOrderRoutes.kt`, `VenueOrdersRepository.kt`, `venueOrders.ts`, `venueOrderDetail.ts` | Medium: large queues and route parity need smoke; UI ignores `nextCursor`. | M1 IA shell; later M4 order audit/pagination. | Mini App build/e2e; backend route tests if pagination/audit added. |
| A. Work shift / staff calls | Implemented/partial in bot. Shift hub has `🛎 Вызовы`; staff chat callbacks `sc_call_ack:*`, `sc_call_done:*`. | Implemented: `GET /api/venue/{venueId}/staff-calls`, ack/done routes. | Implemented: calls list, accept/close. | Uses `ORDER_QUEUE_VIEW` to view and `ORDER_STATUS_UPDATE` to act. | `VenueStaffCallRoutes.kt`, `StaffCallRepository.kt`, `venueCalls.ts`, `venueApi.ts` | Low/Medium: owner bot calls entry historically less direct; Mini App route is real. | M1 keep under `Работа смены`; no new logic. | E2E smoke for accept/done. |
| A. Work shift / bookings | Implemented/partial. Bot has booking list, confirm/cancel/change/message, seated/no-show, hold settings. | Implemented: booking list/status routes, reminder/expiry workers in backend. | Implemented: booking list, confirm/cancel/change, seated/no-show. | `BOOKING_VIEW`; `BOOKING_ARRIVAL_UPDATE`; `BOOKING_MANAGE` for management actions. | `VenueBookingRoutes.kt`, `GuestBookingRepository.kt`, `venueBookings.ts`, `TelegramBotRouter.kt` | Medium: hold/settings parity in Mini App is not complete. | M1 group under `Работа смены`; M5 booking settings later. | Booking route/RBAC tests and Telegram runtime smoke. |
| A. Work shift / stop-list | Implemented. Bot shift hub has `🚫 Стоп-лист`; menu item and option availability flows exist. | Implemented via Venue Menu item/option availability routes. | Implemented/smoke-passed inside menu constructor: item-level and option/flavor stop-list. | `MENU_VIEW` + `MENU_AVAILABILITY_MANAGE` allow STAFF/MANAGER/OWNER operational stop-list. `MENU_MANAGE` remains MANAGER/OWNER-only for content. | `VenueMenuRoutes.kt`, `HookahFlavorProfileService.kt`, `venueMenu.ts` | Low: options/flavors parity closed; keep stop-list in regression. | M1 show menu under `Настройки`; STAFF availability parity is implemented as a focused follow-up. | Regression smoke for item/option availability and hidden STAFF content controls. |
| A. Work shift / staff chat alerts | Implemented. Bot can link staff chat and staff chat notifier sends order/call messages. | Implemented: staff chat status/link-code/unlink backend; outbox/notifier. | Implemented: chat status/link-code screen; unlink wrapper exists but screen does not expose unlink. | `STAFF_CHAT_LINK`; unlink backend owner-only. | `VenueRoutes.kt`, `StaffChatNotifier.kt`, `venueChatLink.ts`, `venueApi.ts` | Medium: runtime Telegram group binding must be smoke-tested. | M1 group under `Настройки`; later M6 unlink/status diagnostics. | Manual Telegram group smoke. |
| B. Settings / venue profile-card | Implemented in bot: profile/card fields, address, contacts, description/info sections, hours. | Partial: guest venue/info section routes exist; no broad Venue Mini App settings API for all fields. | Partial: current `settings` screen only manages paid shift extension settings. | `VENUE_SETTINGS` exists but has no matching broad route; `SHIFT_EXTENSION_SETTINGS` works. | `VenueRepository.kt`, `VenueInfoSectionsRepository.kt`, `VenueBookingHoursRepository.kt`, `VenueSettingsRepository.kt`, `venueSettings.ts` | High: exposing generic settings would create dead ends or partial writes. | M5 design small settings APIs by field; keep bot canonical meanwhile. | Route/RBAC tests per setting slice. |
| B. Paid shift extension settings | Implemented in Mini App and backend; bot ordering entry exists, Owner/Manager Bot settings parity is still active roadmap item. | Implemented: settings and pending request routes. | Implemented: settings screen; pending requests screen exists but was hidden before M1. | `SHIFT_EXTENSION_VIEW`, `SHIFT_EXTENSION_CONFIRM`, `SHIFT_EXTENSION_SETTINGS`; STAFF has view/confirm but no settings. | `ShiftExtensionRoutes.kt`, `venueSettings.ts`, `venueShiftExtensions.ts`, `venueApp.ts` | Low for surfacing existing screen; Medium for bot settings parity. | M1 make requests discoverable; separate paid-extension bot settings closure remains. | Mini App build/e2e; shift extension route tests if changed. |
| C. Menu constructor | Implemented. Bot supports categories/items/prices/stop-list/flavors/base profiles and photo-menu split. | Implemented: categories/items/options/base profiles/reorder/availability. | Implemented/smoke-passed for item/flavor scoping, base profiles, price edit, stop-list. | `MENU_VIEW`, `MENU_MANAGE`, `MENU_AVAILABILITY_MANAGE`. | `VenueMenuRoutes.kt`, `VenueMenuRepository.kt`, `HookahFlavorProfileService.kt`, `venueMenu.ts` | Medium: category/item semantic type selection in Mini App can drift. | M1 group under `Настройки`; later M7 semantic type UI/photos/descriptions. | Existing menu route tests + Mini App smoke. |
| D. Tables / QR | Implemented in bot: tables root/list/add/toggle/QR/rotate. | Implemented: list, batch-create, rotate, rotate-all, QR package. | Implemented: tables/QR screen. | `TABLE_VIEW`, `TABLE_MANAGE`, `TABLE_TOKEN_ROTATE`, `TABLE_TOKEN_ROTATE_ALL`, `TABLE_QR_EXPORT`. | `VenueTableRoutes.kt`, `VenueTableRepository.kt`, `venueTables.ts` | Medium: QR export/download must be runtime-smoked. | M1 group under `Настройки`; later QR diagnostics if needed. | `VenueTableRoutesTest`, manual QR export smoke. |
| E. Staff/personnel | Implemented/partial in bot: invites, role changes, remove access. | Implemented: staff list/invites/accept/update/remove with safety checks. | Implemented: staff screen. | STAFF hidden; managers conservative; owner safety applies backend-side. | `VenueStaffRoutes.kt`, `VenueStaffRepository.kt`, `StaffInviteRepository.kt`, `venueStaff.ts` | Medium: identity labels can be ID-heavy; owner transfer not here. | M1 group under `Настройки`; later identity polish. | `VenueStaffRoutesTest`, invite smoke. |
| F. Statistics | Implemented per venue in bot; aggregate `owner_stats_all` is placeholder/limited. | Implemented for Mini App: read-only `GET /api/venue/{venueId}/stats?period=today\|7d\|30d` reuses `VenueStatsRepository`. | Implemented: read-only `Статистика` screen for OWNER/MANAGER with period selector, cards and top items. | OWNER/MANAGER only, matching bot; STAFF denied and does not see nav. | `VenueStatsRepository.kt`, `VenueStatsRoutes.kt`, `TelegramBotRouter.kt`, `venueStats.ts`, `venueApp.ts` | Medium: advanced analytics/platform dashboards remain out of scope. | M2 implemented; keep in smoke. | `VenueStatsRoutesTest`, Mini App e2e stats smoke, build. |
| G. Promotions/growth | Implemented in bot marketing hub: promotions, rules, placements/top, loyalty/reviews; broad callback surface. | Backend repositories/services exist for promotions/placements/loyalty/feedback. | Missing in Venue Mini App; no fake screen should be added. | Likely OWNER/MANAGER only; exact Mini App permissions not defined. | `VenuePromotionRepository.kt`, `VenuePromotionRuleRepository.kt`, `PromotionPlacementRepository.kt`, `LoyaltyRepository.kt`, `TelegramBotRouter.kt` | High: broad workflow and money/marketing side effects. | M3 read-only summary first; builders later. | Promotion/loyalty regression tests and smoke. |
| H. Guest preview | Implemented in bot: publish readiness and guest preview callbacks. | Partial support through existing guest catalog/venue/menu APIs. | Missing as Venue Mini App owner preview entry. | OWNER/MANAGER only. | `GuestVenueRoutes.kt`, `GuestMenuRepository.kt`, `TelegramBotRouter.kt`, `venueApp.ts` | Medium: must not leak unpublished/private state incorrectly. | M4 preview deep link/read-only preview after route policy is decided. | Guest visibility tests + manual preview smoke. |

## Milestone Plan

### M1 — Venue Mini App IA Shell And Existing Shift Extension Requests

Status: selected first implementation milestone.

Scope:

- Group existing Venue Mini App nav into bot-like sections:
  - `Работа смены`: `Обзор`, `Заказы`, `Брони`, `Вызовы`, `Продления`.
  - `Настройки`: `Заказное меню`, `Столы и QR`, `Персонал`, `Чат персонала`, `Настройки`.
- Show only routes backed by current screens and current permissions.
- Make `Продления` visible for users with `SHIFT_EXTENSION_VIEW`.
- Do not show `Статистика`, `Продвижение`, `Предпросмотр для гостя` until real screens/routes exist.

Definition of Done:

- No backend/API/DB changes.
- Existing route permissions stay unchanged.
- `Продления` appears only when `SHIFT_EXTENSION_VIEW` is present.
- STAFF still does not see staff/settings/menu content controls beyond current RBAC.
- Mini App build passes.

### M1a — STAFF Operational Stop-List Parity

Status: implemented as a focused RBAC follow-up after M1.

Scope:

- STAFF gets `MENU_AVAILABILITY_MANAGE`, but not `MENU_MANAGE`.
- STAFF can toggle menu item availability and item option/flavor availability in Bot and Venue Mini App.
- STAFF cannot create/edit/delete/reorder categories/items, edit prices, add/delete flavors/options or apply base flavor profiles.

Definition of Done:

- Backend route tests allow STAFF item/option availability and deny STAFF content endpoints.
- Mini App smoke shows STAFF stop-list toggles and hides content controls.
- Bot staff stop-list callbacks remain allowed.

### M2 — Venue Stats Read-Only Mini App

Status: implemented.

Scope:

- Add minimal read-only `GET /api/venue/{venueId}/stats?period=today|7d|30d`.
- Reuse `VenueStatsRepository`; add direct SQL/repository tests before UI.
- Add Mini App `Статистика` screen only after route/RBAC is covered.

Definition of Done:

- OWNER/MANAGER can read venue stats; STAFF policy is explicit.
- No frontend money recalculation.
- Tests cover route denied paths and period handling.

Implemented notes:

- API returns existing bot semantics: orders count, revenue, average check, discount total, cancellations/exclusions and top items.
- Mini App uses backend minor-unit values and existing `formatPrice`.
- No charts, no new analytics pipeline, no promotions/growth metrics.

### M3 — Promotions Read-Only Summary

Scope:

- Start with read-only current promotions/placements/loyalty state.
- No promotion builder or billing mutation in first Mini App slice.
- Keep bot canonical for creation/editing until route permissions are explicit.

Definition of Done:

- Mini App does not expose placeholder marketing actions.
- OWNER/MANAGER can inspect active/paused/archived status safely.

### M4 — Guest Preview Entry

Scope:

- Add read-only owner/manager preview using the same guest-visible read models.
- Decide whether unpublished draft preview is allowed; otherwise preview only published guest state.

Definition of Done:

- Preview matches Guest Mini App visibility rules.
- No private staff/order/billing data leaks.

### M5 — Venue Settings Slices

Scope:

- Add only small settings slices with existing bot semantics: profile/card, hours/exceptions, booking hold settings, notification toggles.
- Do not create one bulk settings endpoint.

Definition of Done:

- Each settings slice has backend route, RBAC test, UI, smoke checklist.
- `VENUE_SETTINGS` no longer leads to a screen that only supports shift extension settings.

### M6 — Staff Chat Diagnostics And Unlink

Scope:

- Expose existing unlink action safely for OWNER.
- Show last linked status and operational diagnostics if backend supports it.

Definition of Done:

- Owner-only unlink is visible and tested.
- Link-code flow remains unchanged.

### M7 — Menu Semantic And Media Polish

Scope:

- Add Mini App category/item semantic type controls only if backend semantics are already stable.
- Add photos/descriptions only with clear media storage/proxy strategy.

Definition of Done:

- Hookah flavors remain item-scoped.
- Non-hookah options remain explicit and neutral.

## Do Not Reopen Without New Evidence

- Guest/Menu Options & Flavors Parity is closed after staging smoke.
- Item-level and flavor-level stop-list parity is closed after staging smoke.
- STAFF close bill/order policy is implemented and must stay permission-tested.
- Booking RBAC split is implemented: STAFF arrival/no-show, MANAGER/OWNER management.
- Bill calculations remain backend-owned; no frontend recalculation.

## Immediate Risks

- Hidden implemented features: `Продления` route exists but was not reachable from nav.
- Settings mismatch: `VENUE_SETTINGS` can imply a broad settings screen while current Mini App settings only controls shift extension settings.
- Stats advanced gap: Mini App now has read-only per-venue stats, but no advanced analytics, platform dashboards or consolidated network stats.
- Promotions gap: bot marketing hub is broad and callback-heavy; Mini App should start read-only.
- Queue scale: Venue Mini App order queue currently uses a fixed limit and does not expose pagination.
- Runtime Telegram dependencies still need manual smoke: WebApp `initData`, staff chat group binding, QR export/download.
