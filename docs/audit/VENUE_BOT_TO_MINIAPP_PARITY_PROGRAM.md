# Venue Bot-to-Mini-App Parity Program

Дата: 2026-06-17. Режим: audit + bounded parity milestones. Цель: переносить уже реализованную Telegram Bot venue-management логику в Venue Mini App без нового продуктового моделирования и без одного большого небезопасного diff.

## Executive Summary

Telegram Bot остаётся самой широкой venue-management поверхностью: selected venue hub уже разделён на `Работа смены`, `Настройка заведения`, `Статистика`, `Продвижение`, `Предпросмотр для гостя`. Venue Mini App уже покрывает operational core, M1 сгруппировал работающие экраны, M2 добавил backend-backed read-only `Статистика` и прошёл staging smoke, M3 реализовал bookings queue/lifecycle MVP, а M4A закрыл persisted booking conversation threads между гостем и заведением. M4B/M4C локально доводят `Сообщения` до inbox lifecycle: треды имеют карточки, фильтры, unread и явные действия resolve/reopen. Главное правило программы: Mini App показывает только работающие backend-backed экраны; `Продвижение` и `Предпросмотр для гостя` не должны появляться как основные кнопки до появления реальных Mini App routes/screens.

M1 безопасно привёл Venue Mini App shell к bot-like information architecture и сделал видимым уже реализованный экран `Продления` для ролей с `SHIFT_EXTENSION_VIEW`. M2 закрыт как первый новый Mini App parity slice: read-only статистика для OWNER/MANAGER на существующем `VenueStatsRepository`, без новой аналитической модели и без миграций. M3 закрывает Mini App booking operations MVP: active queue, venue-local display fields, confirm/change/cancel for MANAGER/OWNER and arrival/no-show for STAFF. M4A закрывает communication gap для броней: booking messages persist in support threads and are visible from Guest Bot, Guest Mini App and Venue Mini App. M4B implemented locally превращает `Сообщения` в inbox тредов для нескольких заведений и контекстов, а не в один общий чат. M4C implemented locally добавляет отдельный от booking lifecycle статус переписки: active/resolved filters теперь управляются явными действиями `Завершить переписку` и `Возобновить переписку`.

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
| A. Work shift / orders | Implemented. Bot shift hub has `📦 Заказы`; callbacks include `staff_venue_orders_root:*`, order detail/status/bill callbacks. | Implemented: queue/detail/audit/status/reject/close/item bill adjustment routes. | Implemented: order queue/detail, full bill, status actions, bill controls where allowed. | `ORDER_QUEUE_VIEW`, `ORDER_STATUS_UPDATE`; STAFF can operate order status/close but not manager-only bill edits. | `TelegramBotRouter.kt`, `TelegramKeyboards.kt`, `VenueOrderRoutes.kt`, `VenueOrdersRepository.kt`, `venueOrders.ts`, `venueOrderDetail.ts` | Medium: large queues and route parity need smoke; UI ignores `nextCursor`. | M1 IA shell; later order audit/pagination slice. | Mini App build/e2e; backend route tests if pagination/audit added. |
| A. Work shift / staff calls | Implemented in bot/table context and staff chat. Shift hub has `🛎 Вызовы`; staff chat callbacks `sc_call_ack:*`, `sc_call_done:*`. | Implemented: guest create with `tableSessionId`, guest status endpoint, `GET /api/venue/{venueId}/staff-calls`, ack/done routes, staff chat notification on Mini App-created calls. | Implemented: Guest Mini App sends scoped staff calls and shows simple lifecycle status; Venue Mini App calls list supports accept/close. | Uses `ORDER_QUEUE_VIEW` to view and `ORDER_STATUS_UPDATE` to act. STAFF/MANAGER/OWNER can operate calls; guest status remains table-session scoped. | `GuestStaffCallRoutes.kt`, `VenueStaffCallRoutes.kt`, `StaffCallRepository.kt`, `guestVenue.ts`, `venueCalls.ts`, `guestApi.ts`, `venueApi.ts` | Low/Medium: no cancel route and no SLA/escalation yet; staff chat runtime binding still requires pilot smoke. | M5 closed locally / staging smoke target. | Backend StaffCall/RBAC tests, Mini App smoke for guest create/status and venue accept/done, manual staff chat notification smoke. |
| A. Work shift / bookings | Implemented/partial. Bot has booking list, confirm/cancel/change/message, seated/no-show, hold settings. | Implemented: booking list/status/message routes, reminder/expiry workers in backend. | Implemented: booking list, confirm/cancel/change, `Написать гостю`, seated/no-show, venue-local display, hold deadline display. | `BOOKING_VIEW`; `BOOKING_ARRIVAL_UPDATE`; `BOOKING_MANAGE` for management/message actions. | `VenueBookingRoutes.kt`, `GuestBookingRepository.kt`, `venueBookings.ts`, `TelegramBotRouter.kt` | Medium: booking settings/reminder controls are not Mini App parity yet. | M3 closed; keep in regression smoke. | Booking route/RBAC tests, Mini App booking e2e, Telegram runtime smoke. |
| A. Work shift / booking conversations | Partial in bot before M4A: booking reply callback existed, but staff chat was the only reliable inbox for replies. | Implemented in M4A/M4B/M4C: `support_threads` / `support_messages`, booking message thread create/reuse, guest/venue list/detail/reply APIs, `support_thread_reads`, `filter=active\|resolved`, context labels, last message preview, unread counts and resolve/reopen status routes. | Implemented locally in M4B/M4C: booking card opens thread history, Venue `Сообщения` lists current-venue thread cards, Guest `Сообщения` lists venue/context thread cards, both have active/resolved filters, unread badges and resolve/reopen detail actions. | `BOOKING_MANAGE` for venue replies/status changes; STAFF remains denied for booking messages unless product policy changes later. Guest access is user-scoped; Venue inbox stays venue-scoped; Platform inbox must be backend-backed before exposure. Conversation status is separate from booking status. | `SupportThreadRepository.kt`, `SupportRoutes.kt`, `VenueBookingRoutes.kt`, `TelegramBotRouter.kt`, `venueBookings.ts`, `venueMessages.ts`, `guestSupportThreads.ts`, `supportDtos.ts`, `V108__support_thread_reads.sql` | Medium: no DB-level unique thread constraint yet; M4B/M4C need staging smoke with multiple venues/threads; venue/admin bot full inbox is deferred. | M4C implemented locally / smoke target. | Support/booking route tests, Guest Bot reply test, Mini App booking/messages e2e, Telegram runtime smoke; for M4C smoke resolve/reopen and no booking lifecycle side effects. |
| A. Work shift / stop-list | Implemented. Bot shift hub has `🚫 Стоп-лист`; menu item and option availability flows exist. | Implemented via Venue Menu item/option availability routes. | Implemented/smoke-passed inside menu constructor: item-level and option/flavor stop-list. | `MENU_VIEW` + `MENU_AVAILABILITY_MANAGE` allow STAFF/MANAGER/OWNER operational stop-list. `MENU_MANAGE` remains MANAGER/OWNER-only for content. | `VenueMenuRoutes.kt`, `HookahFlavorProfileService.kt`, `venueMenu.ts` | Low: options/flavors parity closed; keep stop-list in regression. | M1 show menu under `Настройки`; STAFF availability parity is implemented as a focused follow-up. | Regression smoke for item/option availability and hidden STAFF content controls. |
| A. Work shift / staff chat alerts | Implemented. Bot can link staff chat and staff chat notifier sends order/call messages. | Implemented: staff chat status/link-code/unlink backend; outbox/notifier. | Implemented: chat status/link-code screen; unlink wrapper exists but screen does not expose unlink. | `STAFF_CHAT_LINK`; unlink backend owner-only. | `VenueRoutes.kt`, `StaffChatNotifier.kt`, `venueChatLink.ts`, `venueApi.ts` | Medium: runtime Telegram group binding must be smoke-tested. | M1 group under `Настройки`; later M7 unlink/status diagnostics. | Manual Telegram group smoke. |
| B. Settings / venue profile-card | Implemented in bot: profile/card fields, address, contacts, description/info sections, hours. | Partial: guest venue/info section routes exist; no broad Venue Mini App settings API for all fields. | Partial: current `settings` screen only manages paid shift extension settings. | `VENUE_SETTINGS` exists but has no matching broad route; `SHIFT_EXTENSION_SETTINGS` works. | `VenueRepository.kt`, `VenueInfoSectionsRepository.kt`, `VenueBookingHoursRepository.kt`, `VenueSettingsRepository.kt`, `venueSettings.ts` | High: exposing generic settings would create dead ends or partial writes. | M6 design small settings APIs by field; keep bot canonical meanwhile. | Route/RBAC tests per setting slice. |
| B. Paid shift extension settings | Implemented in Mini App and backend; bot ordering entry exists, Owner/Manager Bot settings parity is still active roadmap item. | Implemented: settings and pending request routes. | Implemented: settings screen; pending requests screen exists but was hidden before M1. | `SHIFT_EXTENSION_VIEW`, `SHIFT_EXTENSION_CONFIRM`, `SHIFT_EXTENSION_SETTINGS`; STAFF has view/confirm but no settings. | `ShiftExtensionRoutes.kt`, `venueSettings.ts`, `venueShiftExtensions.ts`, `venueApp.ts` | Low for surfacing existing screen; Medium for bot settings parity. | M1 make requests discoverable; separate paid-extension bot settings closure remains. | Mini App build/e2e; shift extension route tests if changed. |
| C. Menu constructor | Implemented. Bot supports categories/items/prices/stop-list/flavors/base profiles and photo-menu split. | Implemented: categories/items/options/base profiles/reorder/availability. | Implemented/smoke-passed for item/flavor scoping, base profiles, price edit, stop-list. | `MENU_VIEW`, `MENU_MANAGE`, `MENU_AVAILABILITY_MANAGE`. | `VenueMenuRoutes.kt`, `VenueMenuRepository.kt`, `HookahFlavorProfileService.kt`, `venueMenu.ts` | Medium: category/item semantic type selection in Mini App can drift. | M1 group under `Настройки`; later M8 semantic type UI/photos/descriptions. | Existing menu route tests + Mini App smoke. |
| D. Tables / QR | Implemented in bot: tables root/list/add/toggle/QR/rotate. | Implemented: list, batch-create, rotate, rotate-all, QR package. | Implemented: tables/QR screen. | `TABLE_VIEW`, `TABLE_MANAGE`, `TABLE_TOKEN_ROTATE`, `TABLE_TOKEN_ROTATE_ALL`, `TABLE_QR_EXPORT`. | `VenueTableRoutes.kt`, `VenueTableRepository.kt`, `venueTables.ts` | Medium: QR export/download must be runtime-smoked. | M1 group under `Настройки`; later QR diagnostics if needed. | `VenueTableRoutesTest`, manual QR export smoke. |
| E. Staff/personnel | Implemented/partial in bot: invites, role changes, remove access. | Implemented: staff list/invites/accept/update/remove with safety checks. | Implemented: staff screen. | STAFF hidden; managers conservative; owner safety applies backend-side. | `VenueStaffRoutes.kt`, `VenueStaffRepository.kt`, `StaffInviteRepository.kt`, `venueStaff.ts` | Medium: identity labels can be ID-heavy; owner transfer not here. | M1 group under `Настройки`; later identity polish. | `VenueStaffRoutesTest`, invite smoke. |
| F. Statistics | Implemented per venue in bot; aggregate `owner_stats_all` is placeholder/limited. | Implemented for Mini App: read-only `GET /api/venue/{venueId}/stats?period=today\|7d\|30d` reuses `VenueStatsRepository`. | Implemented: read-only `Статистика` screen for OWNER/MANAGER with period selector, cards and top items. | OWNER/MANAGER only, matching bot; STAFF denied and does not see nav. | `VenueStatsRepository.kt`, `VenueStatsRoutes.kt`, `TelegramBotRouter.kt`, `venueStats.ts`, `venueApp.ts` | Medium: custom date range, AI summaries and advanced dashboards remain out of scope. | M2 CLOSED / staging smoke passed; keep in regression. | `VenueStatsRoutesTest`, Mini App e2e stats smoke, build. |
| G. Promotions/growth | Implemented in bot marketing hub: promotions, rules, placements/top, loyalty/reviews; broad callback surface. | Backend repositories/services exist for promotions/placements/loyalty/feedback. | Missing in Venue Mini App; no fake screen should be added. | Likely OWNER/MANAGER only; exact Mini App permissions not defined. | `VenuePromotionRepository.kt`, `VenuePromotionRuleRepository.kt`, `PromotionPlacementRepository.kt`, `LoyaltyRepository.kt`, `TelegramBotRouter.kt` | High: broad workflow and money/marketing side effects. | M5 read-only summary first; builders later. | Promotion/loyalty regression tests and smoke. |
| H. Guest preview | Implemented in bot: publish readiness and guest preview callbacks. | Partial support through existing guest catalog/venue/menu APIs. | Missing as Venue Mini App owner preview entry. | OWNER/MANAGER only. | `GuestVenueRoutes.kt`, `GuestMenuRepository.kt`, `TelegramBotRouter.kt`, `venueApp.ts` | Medium: must not leak unpublished/private state incorrectly. | M5 preview deep link/read-only preview after route policy is decided. | Guest visibility tests + manual preview smoke. |

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

Status: CLOSED / staging smoke passed on 2026-06-16.

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
- Staging smoke passed: OWNER/MANAGER see `Статистика`; periods `Сегодня`, `7 дней`, `30 дней` work; stats cards and top items render; STAFF does not see stats; empty state is safe.

Follow-ups:

- Custom date range picker: `from` / `to`.
- Arbitrary period backend stats over selected dates.
- AI-generated summaries/insights over the selected period after stats semantics are stable.

### M3 — Venue Bookings Queue/Lifecycle Parity

Status: CLOSED after smoke in the current release line; keep in regression.

Scope:

- Keep Venue Mini App bookings as the next practical parity block after M2.
- Align visible queue, status copy, lifecycle actions and empty/error states with implemented bot/backend booking semantics.
- Preserve STAFF split: view bookings and mark `Гость пришёл` / `Не пришёл`; MANAGER/OWNER keep confirm/cancel/change/message/settings actions.
- Do not introduce new booking product logic unless it already exists in bot/backend.

Definition of Done:

- OWNER/MANAGER booking management and STAFF operational arrival/no-show paths are regression-smoked.
- Hold/lifecycle copy is explicit where backend data supports it.
- Missing backend-backed booking settings are not exposed as working controls.

Implemented notes:

- Venue Mini App `Брони` stays under `Работа смены` and is visible by `BOOKING_VIEW`.
- Backend booking list DTO now includes venue-local display fields: scheduled display, local date/time, service date and hold deadline display.
- Change flow sends venue-local `scheduledLocalDate` / `scheduledLocalTime`; backend converts using venue timezone while preserving backward-compatible `scheduledAt`.
- MANAGER/OWNER can confirm, change and cancel; STAFF sees only `Гость пришёл` / `Не пришёл`.
- MANAGER/OWNER can send `Написать гостю` from the booking card through the existing Telegram outbox path; STAFF does not see or use this action.
- Empty state copy is `Активных броней пока нет.`
- Local backend booking route tests and Mini App e2e smoke cover manager lifecycle, STAFF action split, foreign venue denial, invalid terminal transition and empty list.

Follow-ups:

- Regression smoke for M3 after booking communication changes.
- Structured `Предложить другое время` flow with guest accept / choose another / cancel buttons.
- Mini App booking hold/settings controls if/when product wants settings outside bot.
- Reminder management UI, post-visit feedback, visit retention, preorder and loyalty remain out of scope.

### M4A — Booking Conversation Threads MVP

Status: CLOSED / staging smoke passed after UX polish.

Scope:

- Create or reuse one persisted booking conversation thread when MANAGER/OWNER clicks `Написать гостю`.
- Persist venue messages, Guest Bot replies and Guest Mini App replies in `support_messages`.
- Keep staff chat as a notification mirror with booking context, not as the only storage.
- Add Guest Mini App `Сообщения` and Venue Mini App `Сообщения` for booking threads.
- Do not build full Platform Support Center, generic CRM, general tickets or structured reschedule proposals in this slice.

Definition of Done:

- Booking message route returns thread/message DTO and does not change booking status.
- Repeat booking messages reuse the same thread.
- Guest Bot reply and Guest Mini App reply save into the same booking thread.
- Venue and guest thread lists are tenant/user scoped.
- STAFF cannot send booking thread messages unless a later product/RBAC decision changes this.
- Booking-card quick compose closes after successful send, keeps the manager on `Брони`, and updates the card to `Открыть переписку`.
- Booking-card quick compose has no template buttons; the textarea placeholder keeps one neutral example. Structured propose-time actions stay a follow-up.

Implemented notes:

- New backend tables: `support_threads`, `support_messages`.
- New route groups: guest support thread list/detail/reply and venue support thread list/detail/reply.
- Existing booking `Написать гостю` route now persists a venue message before sending Telegram outbox.
- Existing Guest Bot reply callback now persists guest replies even if staff chat is not linked.
- Venue Mini App booking card uses `Написать гостю` only before a thread exists; after first send or initial thread load it shows `Открыть переписку`.
- Top-level `Сообщения` under `Работа смены` lists booking conversations for `BOOKING_MANAGE` users and remains the canonical ongoing dialogue screen.
- Guest Mini App support entry is now `Сообщения` with real booking thread list and reply form.

Follow-ups:

- M4B Unified Messages Inbox UX for multi-venue, multi-context thread lists.
- Structured `Предложить другое время` flow with guest accept / choose another / cancel buttons.
- Venue/admin bot full inbox for open booking threads if operators need bot-side reply management.
- General guest `Сообщить о проблеме` tickets and full Platform Support Center.
- Thread statuses and unread tracking if not already complete.
- Audit events for support messages.
- SLA/escalation and search by venue/guest/context.
- DB-level duplicate/race protection for concurrent booking thread creation if staging shows duplicate threads.

### M4B — Unified Messages Inbox UX

Status: implemented locally / smoke target. Full Platform Support Center remains out of scope.

Product decision:

- M4A is only the booking conversation MVP.
- Guest Mini App `Сообщения` / `Мои обращения` must be a thread list, not one messy global chat.
- Venue Mini App `Сообщения` must show only threads for the selected/current `venue_id`.
- Platform support later sees all support/ticket threads only through a backend-backed cockpit; no fake Platform Support Center entries.
- Do not merge all messages with one venue into one endless chat. One booking = one booking thread; one order issue = one order thread; one general question = one general thread; one technical/platform problem = one platform/support ticket thread.

Guest Mini App target UX:

- Thread card fields: venue name, context label (`Бронь №...`, `Заказ №...`, `Стол №...`, `Общий вопрос`, `Проблема`), status, last message preview, last message time and unread count/badge.
- Status labels should map project states such as `NEW`, `OPEN`, `WAITING_GUEST`, `RESOLVED`, `CLOSED` to clear Russian labels.
- Filters: `Активные` and `Завершённые`.
- Empty state: `Сообщений пока нет.`
- Resolved/closed threads should not clutter the active inbox.

Venue Mini App target UX:

- `Сообщения` shows only current venue threads.
- Thread card fields: guest display, context label, status, last message preview, last message time and unread badge.
- Booking card links to the booking thread; future order/table/support context cards reuse the same pattern.
- STAFF permissions must be explicit and tested; do not silently broaden STAFF reply/view rights.

Implemented scope:

- Added `support_thread_reads` read model for per-user unread counts.
- Extended support thread DTOs with guest display, context label, order/table ids, last message preview and unread count.
- Added `filter=active|resolved` to guest and venue thread lists.
- Marked a thread as read when a guest or venue user opens the thread detail; sending a message also marks the sender's thread read.
- Improved Guest Mini App and Venue Mini App inbox cards and active/resolved filters.
- Preserved booking card `Открыть переписку`, M4A thread detail reply flow, Guest Bot replies and staff chat notifications.

Deferred scope:

- Guest `Новый вопрос` from venue card remains deferred until backend-backed general thread creation is explicitly scoped.
- Structured `Предложить другое время`, generic problem tickets, Platform Support Center, audit events, SLA/escalation and search remain follow-ups.

Definition of Done:

- Guest with threads across multiple venues sees a scannable inbox list and can open the correct thread by venue/context.
- Venue user sees only selected venue threads and cannot access foreign venue threads.
- Thread cards show status, last message preview/time and unread state from backend data, not frontend guesses.
- Active/resolved filters work and old closed threads do not dominate the active inbox.
- Platform support/tickets are not exposed unless backed by real routes and permissions.

### M4C — Support Thread Resolve/Reopen Lifecycle

Status: implemented locally / smoke target. Full Platform Support Center remains out of scope.

Product decision:

- Conversation lifecycle is separate from booking lifecycle.
- `Завершить переписку` marks only `support_threads.status = RESOLVED`; it must not confirm, cancel, change, seat or no-show a booking.
- Resolved threads stay available as history through `Завершённые` and through booking card `Открыть переписку`.
- `Возобновить переписку` moves the thread back to `OPEN`.
- A real user message added to a resolved thread reopens it through the shared `addMessage` backend semantics, so old Guest Bot reply buttons do not write into an invisible finished thread.

Implemented scope:

- Guest routes: `POST /api/guest/support/threads/{threadId}/resolve` and `/reopen`.
- Venue routes: `POST /api/venue/{venueId}/support/threads/{threadId}/resolve` and `/reopen`.
- Guest Mini App thread detail: active threads show `Завершить переписку`; resolved threads show `Переписка завершена` and `Возобновить переписку`.
- Venue Mini App thread detail mirrors the same UX for users with `BOOKING_MANAGE`.
- Composer is hidden for resolved threads until the thread is reopened.

Deferred scope:

- `CLOSED` remains reserved for future/final support operations.
- Structured `Предложить другое время`, generic problem tickets, Platform Support Center, audit events, SLA/escalation and search remain follow-ups.

Definition of Done:

- Guest and Venue can move a thread from active to resolved and back without changing the linked booking state.
- Active filter contains `OPEN` threads; resolved filter contains `RESOLVED` / `CLOSED` threads.
- Booking card keeps opening resolved conversation history.
- STAFF does not gain new message/status permissions.

### M5 — Staff Calls Lifecycle And Notification Parity

Status: implemented locally / staging smoke target.

Scope:

- Guest Mini App creates staff calls from active table context with `tableToken`, `tableSessionId`, reason and optional comment.
- Guest Mini App shows simple call status labels: `Вызов отправлен`, `Персонал принял вызов`, `Вызов закрыт`.
- Venue Mini App `Вызовы` queue lists active calls and lets STAFF/MANAGER/OWNER accept and close calls.
- Backend keeps staff-call status model `NEW -> ACK -> DONE`; stale transitions return `applied=false` and do not mutate state.
- Staff chat receives Mini App-created call notifications through the existing notifier path and remains a quick-action mirror.

Deferred scope:

- Configurable call categories.
- Cancel route.
- SLA timers/escalation.
- Personal staff subscriptions.
- Audit events for ack/done actors and timestamps.

Definition of Done:

- Guest create/status route tests pass.
- Venue route/RBAC tests cover STAFF/MANAGER/OWNER actions and invalid transition no-op.
- Mini App smoke covers guest create/status and venue accept/close.
- Manual pilot smoke confirms linked staff chat receives the new-call notification and inline ACK/DONE still work.

### M6 — Promotions Read-Only Summary

Scope:

- Start with read-only current promotions/placements/loyalty state.
- No promotion builder or billing mutation in first Mini App slice.
- Keep bot canonical for creation/editing until route permissions are explicit.

Definition of Done:

- Mini App does not expose placeholder marketing actions.
- OWNER/MANAGER can inspect active/paused/archived status safely.

### M7 — Guest Preview Entry

Scope:

- Add read-only owner/manager preview using the same guest-visible read models.
- Decide whether unpublished draft preview is allowed; otherwise preview only published guest state.

Definition of Done:

- Preview matches Guest Mini App visibility rules.
- No private staff/order/billing data leaks.

### M8 — Venue Settings Slices

Scope:

- Add only small settings slices with existing bot semantics: profile/card, hours/exceptions, booking hold settings, notification toggles.
- Do not create one bulk settings endpoint.

Definition of Done:

- Each settings slice has backend route, RBAC test, UI, smoke checklist.
- `VENUE_SETTINGS` no longer leads to a screen that only supports shift extension settings.

### M9 — Staff Chat Diagnostics And Unlink

Scope:

- Expose existing unlink action safely for OWNER.
- Show last linked status and operational diagnostics if backend supports it.

Definition of Done:

- Owner-only unlink is visible and tested.
- Link-code flow remains unchanged.

### M10 — Menu Semantic And Media Polish

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
- Stats advanced gap: Mini App now has read-only per-venue stats, but no custom date range picker, AI summaries, advanced analytics, platform dashboards or consolidated network stats.
- Messages inbox smoke risk: M4B/M4C unified inbox UX is implemented locally, but still needs staging smoke with multiple venues/threads to verify cards, active/resolved filters, unread clearing, resolve/reopen lifecycle and venue scoping. Structured reschedule proposals, generic tickets, Platform Support Center and venue/admin bot full inbox remain follow-ups.
- Promotions gap: bot marketing hub is broad and callback-heavy; Mini App should start read-only.
- Queue scale: Venue Mini App order queue currently uses a fixed limit and does not expose pagination.
- Runtime Telegram dependencies still need manual smoke: WebApp `initData`, staff chat group binding, QR export/download.
