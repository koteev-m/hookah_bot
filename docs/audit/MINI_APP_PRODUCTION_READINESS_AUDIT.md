# Mini App Production Readiness Audit

Дата: 2026-05-21.

> Historical audit snapshot. Some P0/P1 findings below were fixed after this read-only audit.
>
> Current correction as of 2026-06-03: CORS methods, Mini App staff call `tableSessionId`, fallback WebApp command contract, active order session/tab scoping, Venue Mini App full bill parity, bookings screens, and STAFF close bill/order policy have been addressed in code. Use `docs/UPDATED_PRODUCT_AI_ROADMAP.md` and `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` for current release status.

Режим: read-only аудит. Код, миграции, тесты, backend/frontend business logic не менялись.

## 1. Executive Summary

Mini App уже не пустой: в проекте есть guest/venue/platform shells, Telegram `initData` auth, JWT session, guest catalog/menu/cart/order, venue queue/menu/tables/staff, platform venues/subscription basics.

Ключевой вывод: backend сильнее Mini App UI. Во многих местах backend уже умеет больше, чем экран показывает:

- full bill;
- promo/loyalty breakdown;
- exclusions;
- display order number;
- bookings lifecycle;
- option stop-list.

Market launch требует закрыть P0/P1 gaps между bot-first core и Mini App. Следующий development block должен быть P0 implementation, а не новый AI/Guest Mode scope.

## 2. Current Mini App Readiness

### Guest Mini App

Status: `PARTIAL`.

Готово:

- guest shell;
- Telegram `initData` auth and JWT session;
- catalog;
- venue card/menu;
- cart;
- add batch order;
- active order route;
- basic table context;
- backend routes for bookings, visits/history, favorites and staff calls.

Риски:

- staff call frontend payload does not include `tableSessionId`;
- fallback chat order sends payload not handled by `TelegramBotRouter`;
- active order scoping by `tableSessionId/tabId` needs launch regression coverage;
- guest booking/profile/history/support UI parity needs a dedicated pass;
- promotions/loyalty display must be smoke-tested against bot output.

### Venue Mini App

Status: `PARTIAL`.

Готово:

- venue shell;
- order queue;
- order detail status/reject controls;
- menu category/item basics;
- item availability toggle;
- tables/QR basics;
- staff management basics;
- staff chat link screen;
- settings screen entry.

Риски:

- order detail does not provide full bill parity with Telegram bot;
- manual discounts/exclusions/promo/loyalty breakdown are not fully surfaced in UI;
- display order number is not primary in order queue/detail;
- booking backend exists, but booking screen is not present in current Mini App screens;
- menu option management exists backend-side, but UI says `Опции: MVP позже`;
- settings screen is visible but save is disabled.

### Platform Mini App

Status: `PARTIAL`.

Готово:

- platform shell;
- venue list;
- create venue;
- venue detail;
- owner assignment;
- owner invite;
- venue lifecycle basics;
- subscription/pricing settings basics.

Риски:

- onboarding request cockpit is incomplete;
- billing/invoice workspace is not full operator cockpit;
- placements are not represented as a platform Mini App workspace;
- support/tickets product block is missing;
- analytics cockpit is missing or partial.

### Cross-channel Parity

Status: `RISK`.

Main risks:

- Telegram bot has richer money/full bill/promo/loyalty formatting than Mini App detail.
- Backend DTOs/repositories contain fields Mini App UI does not display.
- Bot and Mini App permissions need role-by-role smoke for OWNER/MANAGER/STAFF.
- CORS currently does not cover all Mini App mutation methods.
- Backend tests exist, but Mini App e2e/browser smoke coverage is not obvious.

## 3. P0 Launch Blockers

### P0.1 — CORS/preflight не покрывает Mini App mutation routes

Why it matters:

Mini App uses backend routes with `PATCH`, `DELETE`, `PUT`, while `Application.kt` currently allows only `OPTIONS`, `GET`, `POST` in CORS. Browser preflight can block otherwise valid Mini App actions.

Affected files/modules:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/Application.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/menu/VenueMenuRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/platform/PlatformVenueRoutes.kt`
- any Mini App API client calling non-GET/POST mutation routes.

Acceptance criteria:

- CORS allows every HTTP method used by Mini App frontend/API clients.
- Preflight succeeds for category/item/option update/delete.
- Preflight succeeds for staff role update/remove.
- Preflight succeeds for platform subscription/price schedule saves.

Tests to add/update:

- `CorsPreflightMiniAppRoutesTest`.
- OPTIONS tests for menu category/item/option `PATCH`/`DELETE`.
- OPTIONS tests for venue staff `PATCH`/`DELETE`.
- OPTIONS tests for platform `PUT` subscription/price schedule.

### P0.2 — Mini App staff call frontend не передаёт `tableSessionId`

Why it matters:

Backend `StaffCallRequest` requires `tableSessionId`, and backend already creates the call and notifies staff chat after success. Current `guestVenue.ts` payload contains only `tableToken`, `reason`, `comment`, so guest staff call can fail before reaching the working backend path.

Affected files/modules:

- `miniapp/src/screens/guestVenue.ts`
- `miniapp/src/shared/api/guestDtos.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestStaffCallRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/StaffChatNotifier.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/StaffCallRepository.kt`

Acceptance criteria:

- Mini App staff call payload includes active `tableSessionId`.
- Backend creates staff call scoped to active table session.
- Staff chat receives notification.
- Guest sees clear success/error state.
- Invalid/missing session is handled cleanly.

Tests to add/update:

- `GuestStaffCallRoutesTest`: table session required.
- `GuestStaffCallRoutesTest`: successful call creates row.
- `GuestStaffCallRoutesTest`: notifier called for Mini App staff call.
- UI/API payload smoke if frontend test harness is available.

### P0.3 — Fallback chat order contract не совпадает с router

Why it matters:

Fallback order path is a safety net when Mini App order submission fails. Mini App currently sends `{ type: "CHAT_ORDER" }`, while `TelegramBotRouter.handleJsonWebAppData` reads `cmd` and supports commands such as `call_staff`, `start_quick_order`, `open_active_order`. This can silently produce an unsupported fallback.

Affected files/modules:

- `miniapp/src/screens/cart.ts`
- `miniapp/src/shared/telegramActions.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- Telegram dialog state for quick order fallback.

Acceptance criteria:

- Mini App sends a payload that `TelegramBotRouter` actually handles.
- Unsupported `type: "CHAT_ORDER"` path is removed, mapped, or replaced.
- Fallback either starts deterministic quick order flow or opens bot chat with clear instructions.
- Unsupported payload is covered by tests/logging without exposing sensitive data.

Tests to add/update:

- `TelegramBotRouterTableTokenTest`: supported web app fallback command.
- Router JSON `web_app_data` test for fallback.
- Mini App fallback smoke.

### P0.4 — Venue Mini App full bill parity не готова

Why it matters:

Venue order detail UI shows batches/items/status/reject controls, but does not provide full management bill parity: gross total, manual discounts, exclusions, promo/loyalty breakdown, final payable total. For launch, staff/manager needs the same bill truth in Mini App as in Telegram.

Affected files/modules:

- `miniapp/src/screens/venueOrderDetail.ts`
- `miniapp/src/screens/venueOrders.ts`
- `miniapp/src/shared/api/venueDtos.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/VenueOrderDtos.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/VenueOrderRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/VenueOrdersRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/PromotionApplicationRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/LoyaltyRepository.kt`

Acceptance criteria:

- Venue order detail shows gross total.
- Venue order detail shows manual discounts.
- Venue order detail shows excluded items and reasons.
- Venue order detail shows promo/loyalty breakdown by label.
- Venue order detail shows final total.
- Totals match Telegram full bill for same order.

Tests to add/update:

- `VenueOrderRoutesTest`: full bill DTO includes gross/manual/promo/loyalty/exclusions/final.
- API/repository test with manual discount + promo + loyalty + excluded item.
- UI render smoke for venue order detail.

### P0.5 — Active order scoping needs launch regression coverage

Why it matters:

Active order scoping by `tableSessionId` and `tabId` was previously the highest-risk privacy issue. Current backend has session/tab scoped paths, but this must be covered by launch regression tests.

Affected files/modules:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestOrderRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/OrdersRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/db/GuestTabsRepository.kt`
- `miniapp/src/screens/order.ts`
- `miniapp/src/screens/cart.ts`

Acceptance criteria:

- Two active sessions at the same physical table cannot see each other's active order.
- Active order endpoint uses current `tableSessionId` and selected/personal `tabId`.
- Shared tab access requires membership.
- Missing or mismatched `tableSessionId/tabId` is rejected or safely resolves to the current user's personal tab.

Tests to add/update:

- `GuestOrderRoutesTest`: active order scoped by `tableSessionId/tabId`.
- `GuestOrderRoutesTest`: two sessions same table do not leak active order.
- `GuestOrderRoutesTest`: tab access denied if user is not member.
- Mini App smoke: QR scan, order, close/expire session, scan same table again.

## 4. P1 Gaps

### P1.1 — Venue order display number not primary in UI

Why it matters:

Staff works with daily venue order numbers, not raw DB ids. Current queue reads like `Order #id · Batch id`, which is less usable during a shift.

Affected files/modules:

- `miniapp/src/screens/venueOrders.ts`
- `miniapp/src/screens/venueOrderDetail.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/VenueOrdersRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/VenueOrderDtos.kt`

Acceptance criteria:

- Queue and detail show display order number as the primary staff-facing identifier.
- Table label remains visible.
- Raw DB id is secondary or hidden.

Tests to add/update:

- Venue order API test: display number present.
- Mini App render smoke for queue/detail.

### P1.2 — Bookings screens missing in Mini App

Why it matters:

Booking backend routes and lifecycle exist, but Mini App screen files do not include obvious booking screens. If bookings are in launch scope, guest and venue sides need UI parity.

Affected files/modules:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/bookings/VenueBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/db/GuestBookingRepository.kt`
- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/screens/venueApp.ts`
- new guest/venue booking screens if implemented.

Acceptance criteria:

- Guest can view/create/update/cancel booking in Mini App, if booking remains launch scope.
- Venue staff/manager can see booking queue and perform confirm/change/cancel/seat/no-show.
- Booking reminders/status behavior remains unchanged.

Tests to add/update:

- Guest booking Mini App smoke.
- Venue booking queue/status smoke.
- Backend route tests for booking status transitions remain green.

### P1.3 — Stop-list options parity incomplete

Why it matters:

Backend supports `menu_item_options` CRUD and availability, but Mini App UI currently states `Опции: MVP позже`. This creates channel divergence with Telegram/backend capability.

Affected files/modules:

- `miniapp/src/screens/venueMenu.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/menu/VenueMenuRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/menu/VenueMenuRepository.kt`
- Telegram stop-list flows in `TelegramBotRouter`.

Acceptance criteria:

- Mini App exposes option availability controls; or
- option management is intentionally hidden/deferred in UI and launch docs.
- Role permissions match final OWNER/MANAGER/STAFF decision.

Tests to add/update:

- Option availability API permission tests.
- Mini App smoke for option stop-list if UI is added.
- Role parity tests for STAFF/MANAGER/OWNER.

### P1.4 — Venue settings screen visible but save disabled

Why it matters:

A visible settings screen that cannot save is a dead end for venue operators and increases support load.

Affected files/modules:

- `miniapp/src/screens/venueSettings.ts`
- `miniapp/src/screens/venueApp.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueSettingsRepository.kt`
- relevant venue settings routes if added.

Acceptance criteria:

- Either the settings screen saves launch-critical fields; or
- the screen is hidden and Telegram bot remains canonical for settings.
- User-facing copy must not promise unavailable save behavior.

Tests to add/update:

- Settings save route/API tests if implemented.
- UI nav permission test if hidden.
- Owner/manager smoke.

### P1.5 — Platform cockpit lacks support/analytics/placements parity

Why it matters:

Platform Mini App has venues/create/detail/subscription basics, but market launch needs at least a baseline cockpit for platform operator work.

Affected files/modules:

- `miniapp/src/screens/platformApp.ts`
- `miniapp/src/screens/platformVenuesList.ts`
- `miniapp/src/screens/platformVenueDetail.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/platform/PlatformVenueRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/platform/PlatformBillingRoutes.kt`
- placement repositories/routes/screens if added later.

Acceptance criteria:

- Platform owner can manage venue lifecycle and subscription state.
- Onboarding requests are visible/actionable or explicitly handled in bot.
- Placements are visible/actionable or explicitly delegated to bot.
- Support path exists for platform operations.
- Analytics baseline is either present or not promised in UI.

Tests to add/update:

- Platform venue lifecycle smoke.
- Subscription save smoke.
- Platform support/placement screen smoke if implemented.

## 5. P2 Improvements

- Guest Mini App public venue card enrichment: media, hours, richer promo previews.
- Profile/history/favorites polish in Mini App.
- Platform analytics dashboard beyond operational baseline.
- Menu photos/modifiers/top-list if not required for launch.
- Channel sync polish: start in bot, continue in Mini App, back to bot.

## 6. Bot / Mini App Parity Risks

- Money display risk: Telegram has richer bill/promo/loyalty formatting than Mini App detail.
- Permission risk: venue nav checks permissions, but role parity still needs smoke across OWNER/MANAGER/STAFF.
- DTO/UI mismatch: backend returns or computes fields UI does not surface.
- Support risk: docs mention support/tickets, but no coherent product block was confirmed in code during the audit.
- Frontend test gap: backend tests exist, but Mini App e2e/browser smoke coverage is not obvious.

## 7. Recommended Implementation Order

1. CORS/preflight methods.
2. Mini App staff call `tableSessionId` payload + notification smoke.
3. Fallback chat order contract.
4. Full bill/discount/exclusion/promo/loyalty display in venue order detail.
5. Display number in queue/detail.
6. Venue settings: make real or hide.
7. Bookings screens if launch scope.
8. Stop-list options parity.
9. Platform support/analytics/placements baseline.

## 8. Tests To Add / Update

- `CorsPreflightMiniAppRoutesTest`.
- `GuestStaffCallRoutesTest`: table session required, success path, notifier called.
- `TelegramBotRouterTableTokenTest`: supported `web_app_data` fallback command.
- `GuestOrderRoutesTest`: active order scoped by `tableSessionId/tabId`.
- `VenueOrderRoutesTest`: full bill DTO includes gross/manual/promo/loyalty/exclusions/final.
- UI smoke/e2e: guest QR -> menu -> cart -> order -> staff call.
- UI smoke/e2e: venue queue -> detail -> full bill.
- Platform smoke: venue lifecycle + subscription save.

## 9. First P0 Implementation Prompt

```text
Ты senior Kotlin/Ktor + Vite Mini App engineer.

Goal:
Закрыть первый P0 Mini App Production Readiness block.

Scope:
1. Update backend CORS so Mini App mutation routes pass preflight for all used methods.
2. Fix Guest Mini App staff call payload: include tableSessionId and keep staff chat notification.
3. Fix fallback chat order contract: Mini App sends a TelegramBotRouter-supported cmd or opens safe bot fallback.
4. Add regression tests for active order scoping by tableSessionId/tabId if missing.

Do not change:
- checkout calculations;
- promo ledger;
- promotion/loyalty business logic;
- DB schema;
- AI/Guest Mode/Managed Bots.

Validation:
./gradlew --no-daemon :backend:app:compileKotlin --console=plain
./gradlew --no-daemon :backend:app:test \
  --tests "*GuestOrderRoutesTest*" \
  --tests "*GuestStaffCallRoutesTest*" \
  --tests "*TelegramBotRouterTableTokenTest*" \
  --tests "*TelegramKeyboardsTest*" \
  --console=plain
```

## 10. Notes / Non-goals

- This audit is read-only.
- Backend/frontend business logic was not changed.
- Migrations were not changed.
- Tests were not changed.
- Checkout, ledger, promotions and loyalty business logic were not changed.
- AI, Telegram Guest Mode, Telegram Business / Secretary Bots and Managed Bots are out of scope.
- This document preserves launch blockers and implementation guidance so P0/P1 issues do not get lost.
