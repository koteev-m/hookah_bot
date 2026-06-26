# Venue Bot-to-Mini-App Parity Program

Дата: 2026-06-25. Режим: audit + bounded parity milestones. Цель: переносить уже реализованную Telegram Bot venue-management логику в Venue Mini App без нового продуктового моделирования и без одного большого небезопасного diff.

## Executive Summary

Telegram Bot остаётся самой широкой venue-management поверхностью: selected venue hub уже разделён на `Работа смены`, `Настройка заведения`, `Статистика`, `Продвижение`, `Предпросмотр для гостя`. Venue Mini App уже покрывает operational core, M1 сгруппировал работающие экраны, M2 добавил backend-backed read-only `Статистика` и прошёл staging smoke, M3 реализовал bookings queue/lifecycle MVP, M4A закрыл persisted booking conversation threads между гостем и заведением, а M4B/M4C закрыли inbox lifecycle: треды имеют карточки, фильтры, unread и явные действия resolve/reopen. M5 закрыл staff calls lifecycle and compact Guest Mini App staff-call UX. M6 сделал `Чат персонала` launch-safe в Mini App: linked/unlinked diagnostics, link-code command, copy-first active-code card, confirmed regeneration, truthful test-message queue result and owner-only unlink. Manual Telegram staff-chat runtime smoke остаётся обязательным для каждого pilot venue as regression. Главное правило программы: Mini App показывает только работающие backend-backed экраны; `Продвижение` и `Предпросмотр для гостя` не должны появляться как основные кнопки до появления реальных Mini App routes/screens.

M1 безопасно привёл Venue Mini App shell к bot-like information architecture и сделал видимым уже реализованный экран `Продления` для ролей с `SHIFT_EXTENSION_VIEW`. M2 закрыт как первый новый Mini App parity slice: read-only статистика для OWNER/MANAGER на существующем `VenueStatsRepository`, без новой аналитической модели и без миграций. M3 закрывает Mini App booking operations MVP: active queue, venue-local display fields, confirm/change/cancel for MANAGER/OWNER and arrival/no-show for STAFF. M4A закрывает communication gap для броней: booking messages persist in support threads and are visible from Guest Bot, Guest Mini App and Venue Mini App. M4B/M4C превращают `Сообщения` в inbox тредов для нескольких заведений и контекстов, а не в один общий чат, и добавляют отдельный от booking lifecycle статус переписки: active/resolved filters управляются явными действиями `Завершить переписку` и `Возобновить переписку`. M5 закрывает staff-call queue/lifecycle parity. M6 закрыт как bounded Mini App staff-chat management slice: backend/bot semantics are reused, STAFF permissions are not broadened, active code UX no longer pushes accidental regeneration, and the test-send result is explicitly `QUEUED` when routed through Telegram outbox. M7a is CLOSED after staging smoke: Venue Mini App can view/update booking hold minutes through the existing backend setting. M7b is implemented as Guest Mini App booking parity and passed staging visual comparison against Bot `/my` for public label, venue-local time and deadline; real two-account Telegram isolation remains unverified. M7c adaptive reminders passed one controlled real Telegram staging smoke with legacy-row reconciliation, explicit confirmation/reschedule anchors, truthful `QUEUED` outbox semantics, visible Telegram message editing and cross-channel attendance indicators. Runtime remains disabled by default, staging is back to `BOOKING_REMINDER_WORKER_ENABLED=false`, and broader rollout remains explicit opt-in. M8a/M8b-Free structured public profile/card settings is CLOSED after provider-free staging smoke: OWNER/MANAGER can edit guest-facing country/city/address, contact and short description fields without runtime geodata providers; country/city suggestions are local, missing cities and addresses remain manually enterable, Yandex adapters stay disabled/optional, STAFF is hidden/forbidden, and manually entered addresses are not described as verified coordinates. Post-M9b code checkpoint: M9b Venue Working Hours and Date Exceptions Mini App Parity plus M9b.1 inclusive exception ranges, optional guest-facing reason/comment and human booking rejection copy are implemented and locally validated with backend tests, Mini App build and browser e2e; staging smoke is still pending before closure.

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
| A. Work shift / staff calls | Implemented in bot/table context and staff chat. Shift hub has `🛎 Вызовы`; staff chat callbacks `sc_call_ack:*`, `sc_call_done:*`. | Implemented: guest create with `tableSessionId`, guest status endpoint, `GET /api/venue/{venueId}/staff-calls`, ack/done routes, staff chat notification on Mini App-created calls. | Implemented/smoke-passed: Guest Mini App sends scoped staff calls through a transient compose modal, shows compact NEW/ACK/DONE status and prevents duplicate active submissions; Venue Mini App calls list supports accept/close. | Uses `ORDER_QUEUE_VIEW` to view and `ORDER_STATUS_UPDATE` to act. STAFF/MANAGER/OWNER can operate calls; guest status remains table-session scoped. | `GuestStaffCallRoutes.kt`, `VenueStaffCallRoutes.kt`, `StaffCallRepository.kt`, `guestVenue.ts`, `guestApp.ts`, `venueCalls.ts`, `guestApi.ts`, `venueApi.ts` | Low/Medium: no cancel route and no SLA/escalation yet; linked staff chat runtime remains per-venue regression. | M5 CLOSED / staging smoke passed. | Backend StaffCall/RBAC tests, Mini App smoke for guest create/status and venue accept/done, manual staff chat notification regression per pilot venue. |
| A. Work shift / bookings | Implemented. Bot has `/my` active booking list/actions, confirm/cancel/change/message, seated/no-show, hold settings and M7c reminder callbacks behind the opt-in worker. | Implemented: booking list/status/message routes, expiry foundation, M7c adaptive reminder scheduling/worker gated off by default, booking hold settings route using `venue_booking_settings.hold_minutes`, and Guest account-level active booking route. | Implemented: Venue booking list/actions, `Написать гостю`, seated/no-show, venue-local display, hold deadline display, M7a `Настройки брони`, M7b Guest Mini App `Мои брони`, and Guest/Venue attendance intent indicators. | `BOOKING_VIEW`; `BOOKING_ARRIVAL_UPDATE`; `BOOKING_MANAGE` for venue management/message/settings actions. Guest list is user-scoped. STAFF view/arrival only; settings stay OWNER/MANAGER. | `GuestBookingRoutes.kt`, `GuestBookingRepository.kt`, `VenueBookingRoutes.kt`, `guestBookings.ts`, `venueBookings.ts`, `venueSettings.ts`, `TelegramBotRouter.kt`, `BookingReminderWorkerConfig.kt`, `BookingReminderWorker.kt` | Medium: M7c rollout remains opt-in disabled and must stay in regression; preorder remains later; real two-account M7b runtime isolation is not recorded. | M3 closed; M7a CLOSED; M7b implemented with staging visual parity; M7c implemented and core Telegram staging smoke passed. | Booking route/RBAC tests, Mini App booking/settings e2e, Telegram `/my` runtime regression, reminder feature-flag and worker tests, approval-gated M7c rollout smoke when enabling. |
| A. Work shift / booking conversations | Partial in bot before M4A: booking reply callback existed, but staff chat was the only reliable inbox for replies. | Implemented in M4A/M4B/M4C: `support_threads` / `support_messages`, booking message thread create/reuse, guest/venue list/detail/reply APIs, `support_thread_reads`, `filter=active\|resolved`, context labels, last message preview, unread counts and resolve/reopen status routes. | Implemented/smoke-passed in M4B/M4C: booking card opens thread history, Venue `Сообщения` lists current-venue thread cards, Guest `Сообщения` lists venue/context thread cards, both have active/resolved filters, unread badges and resolve/reopen detail actions. | `BOOKING_MANAGE` for venue replies/status changes; STAFF remains denied for booking messages unless product policy changes later. Guest access is user-scoped; Venue inbox stays venue-scoped; Platform inbox must be backend-backed before exposure. Conversation status is separate from booking status. | `SupportThreadRepository.kt`, `SupportRoutes.kt`, `VenueBookingRoutes.kt`, `TelegramBotRouter.kt`, `venueBookings.ts`, `venueMessages.ts`, `guestSupportThreads.ts`, `supportDtos.ts`, `V108__support_thread_reads.sql` | Medium: no DB-level unique thread constraint yet; venue/admin bot full inbox is deferred. | M4B/M4C CLOSED / staging smoke passed. | Support/booking route tests, Guest Bot reply test, Mini App booking/messages e2e, regression smoke for tenant scoping/unread/resolve-reopen and no booking lifecycle side effects. |
| A. Work shift / stop-list | Implemented. Bot shift hub has `🚫 Стоп-лист`; menu item and option availability flows exist. | Implemented via Venue Menu item/option availability routes. | Implemented/smoke-passed inside menu constructor: item-level and option/flavor stop-list. | `MENU_VIEW` + `MENU_AVAILABILITY_MANAGE` allow STAFF/MANAGER/OWNER operational stop-list. `MENU_MANAGE` remains MANAGER/OWNER-only for content. | `VenueMenuRoutes.kt`, `HookahFlavorProfileService.kt`, `venueMenu.ts` | Low: options/flavors parity closed; keep stop-list in regression. | M1 show menu under `Настройки`; STAFF availability parity is implemented as a focused follow-up. | Regression smoke for item/option availability and hidden STAFF content controls. |
| A. Work shift / staff chat alerts | Implemented. Bot can link staff chat, show status/test commands and unlink from the linked chat; staff chat notifier sends order/booking/call/extension messages. | Implemented: staff chat status/link-code/unlink backend, masked status DTO, outbox-backed test-message route. | Implemented/smoke-passed: Mini App shows linked/unlinked state, masked chat id, link-code command, copy-first active-code card, regenerate confirmation, truthful test-send copy, OWNER-only unlink and relink flow. | `STAFF_CHAT_LINK` allows OWNER/MANAGER status/link/test; unlink stays OWNER-only; STAFF has no chat-link access. Test-send returns `QUEUED` when the outbox accepted the message, not Telegram delivery confirmation. | `VenueRoutes.kt`, `StaffChatNotifier.kt`, `TelegramBotRouter.kt`, `venueChatLink.ts`, `venueApi.ts` | Medium: wrong or stale group binding can still break runtime notifications; keep real group checks per venue. | M6 CLOSED / staging smoke passed. | Owner/manager/staff RBAC tests, Mini App e2e for status/link/test/unlink visibility, per-venue Telegram group regression smoke. |
| B. Settings / venue profile-card and schedule | Bot implements profile/card fields, address, contacts, description/info sections, weekly hours and date-specific open/closed overrides. | Implemented for M8a/M8b-Free and M9b/M9b.1: public-card route covers structured location/contact/description; optional Yandex geodata proxy is config-gated, disabled by default and configured with separate Geosuggest/Geocoder keys; guest venue read models expose display address, existing coordinate routes and text-route fallback; schedule settings routes reuse existing booking-hours/date-override tables; date-exception ranges expand to per-date rows with nullable guest-facing `guest_note`; guest read models expose today's safe schedule/open state; direct Guest Mini App booking create/update validates against configured hours and returns human schedule rejection codes/copy. | Implemented/code-test-e2e-backed: current `settings` screen manages booking hold, paid shift extension, structured public card basics, weekly hours and compact date-exception ranges with optional reason/comment, with weekly base schedule separated from concrete-date overrides. Guest catalog/card shows today schedule/open state. | OWNER/MANAGER only for settings writes; STAFF hidden/forbidden. Guest schedule/open-state reads preserve published/subscription visibility gates. Guest-facing reason/comment is explicit public copy; private admin notes must not leak. | `VenueBookingHoursRepository.kt`, `VenueRoutes.kt`, `VenueRbac.kt`, `GuestVenueRoutes.kt`, `GuestBookingRoutes.kt`, `TelegramBotRouter.kt`, `venueSettings.ts`, `guestVenue.ts`, `catalog.ts`, `venueApi.ts`, `guestDtos.ts` | Medium: timezone and overnight hours semantics must stay aligned with Bot; staging smoke remains pending. | M9b/M9b.1 implemented / staging smoke pending. Keep M8b-Free provider-free public card fields and Bot booking slot behavior in regression. | Backend route/RBAC tests, Guest booking validation tests, Mini App build/e2e passed locally; manual owner/manager schedule smoke, range exception/reason smoke and guest booking/open-state staging smoke still required. |
| B. Paid shift extension settings | Implemented in Mini App and backend; bot ordering entry exists, Owner/Manager Bot settings parity is still active roadmap item. | Implemented: settings and pending request routes. | Implemented: settings screen; pending requests screen exists but was hidden before M1. | `SHIFT_EXTENSION_VIEW`, `SHIFT_EXTENSION_CONFIRM`, `SHIFT_EXTENSION_SETTINGS`; STAFF has view/confirm but no settings. | `ShiftExtensionRoutes.kt`, `venueSettings.ts`, `venueShiftExtensions.ts`, `venueApp.ts` | Low for surfacing existing screen; Medium for bot settings parity. | M1 make requests discoverable; separate paid-extension bot settings closure remains. | Mini App build/e2e; shift extension route tests if changed. |
| C. Menu constructor | Implemented. Bot supports categories/items/prices/stop-list/flavors/base profiles and photo-menu split. | Implemented: categories/items/options/base profiles/reorder/availability. | Implemented/smoke-passed for item/flavor scoping, base profiles, price edit, stop-list. | `MENU_VIEW`, `MENU_MANAGE`, `MENU_AVAILABILITY_MANAGE`. | `VenueMenuRoutes.kt`, `VenueMenuRepository.kt`, `HookahFlavorProfileService.kt`, `venueMenu.ts` | Medium: category/item semantic type selection in Mini App can drift. | M1 group under `Настройки`; later M8 semantic type UI/photos/descriptions. | Existing menu route tests + Mini App smoke. |
| D. Tables / QR | Implemented in bot: tables root/list/add/toggle/QR/rotate. | Implemented: list, batch-create, rotate, rotate-all, QR package. | Implemented: tables/QR screen. | `TABLE_VIEW`, `TABLE_MANAGE`, `TABLE_TOKEN_ROTATE`, `TABLE_TOKEN_ROTATE_ALL`, `TABLE_QR_EXPORT`. | `VenueTableRoutes.kt`, `VenueTableRepository.kt`, `venueTables.ts` | Medium: QR export/download must be runtime-smoked. | M1 group under `Настройки`; later QR diagnostics if needed. | `VenueTableRoutesTest`, manual QR export smoke. |
| E. Staff/personnel | Implemented/partial in bot: invites, role changes, remove access. | Implemented: staff list/invites/accept/update/remove with safety checks. | Implemented: staff screen. | STAFF hidden; managers conservative; owner safety applies backend-side. | `VenueStaffRoutes.kt`, `VenueStaffRepository.kt`, `StaffInviteRepository.kt`, `venueStaff.ts` | Medium: identity labels can be ID-heavy; owner transfer not here. | M1 group under `Настройки`; later identity polish. | `VenueStaffRoutesTest`, invite smoke. |
| F. Statistics | Implemented per venue in bot; aggregate `owner_stats_all` is placeholder/limited. | Implemented for Mini App: read-only `GET /api/venue/{venueId}/stats?period=today\|7d\|30d` reuses `VenueStatsRepository`. | Implemented: read-only `Статистика` screen for OWNER/MANAGER with period selector, cards and top items. | OWNER/MANAGER only, matching bot; STAFF denied and does not see nav. | `VenueStatsRepository.kt`, `VenueStatsRoutes.kt`, `TelegramBotRouter.kt`, `venueStats.ts`, `venueApp.ts` | Medium: custom date range, AI summaries and advanced dashboards remain out of scope. | M2 CLOSED / staging smoke passed; keep in regression. | `VenueStatsRoutesTest`, Mini App e2e stats smoke, build. |
| G. Promotions/growth | Implemented in bot marketing hub: promotions, rules, placements/top, loyalty/reviews; broad callback surface. | Backend repositories/services exist for promotions/placements/loyalty/feedback. | Missing in Venue Mini App; no fake screen should be added. | Likely OWNER/MANAGER only; exact Mini App permissions not defined. | `VenuePromotionRepository.kt`, `VenuePromotionRuleRepository.kt`, `PromotionPlacementRepository.kt`, `LoyaltyRepository.kt`, `TelegramBotRouter.kt` | High: broad workflow and money/marketing side effects. | M8 read-only summary first; builders later. | Promotion/loyalty regression tests and smoke. |
| H. Guest preview | Implemented in bot: publish readiness and guest preview callbacks. | Partial support through existing guest catalog/venue/menu APIs. | Missing as Venue Mini App owner preview entry. | OWNER/MANAGER only. | `GuestVenueRoutes.kt`, `GuestMenuRepository.kt`, `TelegramBotRouter.kt`, `venueApp.ts` | Medium: must not leak unpublished/private state incorrectly. | M9 preview deep link/read-only preview after route policy is decided. | Guest visibility tests + manual preview smoke. |

## Milestone Plan

### M1 — Venue Mini App IA Shell And Existing Shift Extension Requests

Status: CLOSED / implemented.

Scope:

- Group existing Venue Mini App nav into bot-like sections:
  - `Работа смены`: `Обзор`, `Заказы`, `Брони`, `Вызовы`, `Продления`.
  - `Настройки`: `Заказное меню`, `Столы и QR`, `Персонал`, `Чат персонала`, `Настройки`.
- Show only routes backed by current screens and current permissions.
- Make `Продления` visible for users with `SHIFT_EXTENSION_VIEW`.
- Do not show `Продвижение` / `Предпросмотр для гостя` until real screens/routes exist. `Статистика` is now real after M2 and is visible only to OWNER/MANAGER.

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
- Advanced thread statuses beyond `OPEN` / `RESOLVED` / future `CLOSED` and unread polish beyond current read receipts.
- Audit events for support messages.
- SLA/escalation and search by venue/guest/context.
- DB-level duplicate/race protection for concurrent booking thread creation if staging shows duplicate threads.

### M4B — Unified Messages Inbox UX

Status: CLOSED / staging smoke passed. Full Platform Support Center remains out of scope.

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

Status: CLOSED / staging smoke passed. Full Platform Support Center remains out of scope.

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

Status: CLOSED / staging smoke passed. Manual Telegram staff-chat runtime smoke remains in per-venue regression.

Scope:

- Guest Mini App creates staff calls from active table context with `tableToken`, `tableSessionId`, reason and optional comment.
- Guest Mini App uses a transient compose modal for `Вызвать персонал`; after success the modal closes and a compact status card keeps the menu usable.
- Guest Mini App shows simple call status labels: `Вызов отправлен`, `Персонал принял вызов`, `Вызов выполнен`; ACK uses success styling, not error styling.
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
- Mini App smoke covers guest modal create/status UX, active-call CTA, DONE restore and venue accept/close.
- Manual pilot smoke confirms linked staff chat receives the new-call notification and inline ACK/DONE still work.

### M6 — Staff Chat Diagnostics And Unlink

Status: CLOSED / staging smoke passed. Real Telegram group smoke remains in per-venue regression.

Scope:

- Reuse existing backend/bot staff-chat semantics: status, link-code, outbox-backed test message and owner-only unlink.
- Venue Mini App `Чат персонала` shows linked/unlinked state, masked chat id, full link code/command immediately after generation, active-code hint after reload, copy-first active-code card, confirmed regeneration and clear Telegram group instructions.
- Expose unlink only for OWNER with explicit confirmation.
- Keep MANAGER link/status/test permissions through current `STAFF_CHAT_LINK` RBAC, but preserve backend denial for unlink.
- STAFF must not see or access staff-chat management.
- Do not rewrite notification policy, notifier delivery, Telegram outbox or staff-chat callbacks in this slice.

Definition of Done:

- Owner can see status, generate link code, send a test message through the existing outbox path, and unlink an incorrectly linked chat from Venue Mini App.
- Manager can see status/generate code/test where current RBAC allows, but cannot unlink.
- STAFF cannot access the screen or direct API.
- Existing order/booking/call/extension staff-chat notification behavior is unchanged.
- Mini App copy must say `Тестовое сообщение поставлено в отправку` for outbox acceptance, not claim Telegram delivery.
- Manual pilot smoke still verifies a real Telegram group receives order, booking, call and extension messages.

Implemented notes:

- Staff-chat status DTO returns `maskedChatId`; Mini App no longer needs to render the full Telegram group id.
- Link-code generation response returns a ready `/link@BotUsername <код>` command using backend bot username configuration.
- Test-send route reuses `StaffChatNotifier.notifyTestMessageNow`; result `QUEUED` means the Telegram outbox accepted the diagnostic message.
- OWNER-only unlink has a confirmation modal and refreshes the Mini App back to unlinked state on success.
- Active-code UX is copy-first: `Скопировать команду` is the primary action, raw code is compact, `Создать новый код` is low-emphasis and requires confirmation because current backend revokes previous active unused codes.

Follow-ups:

- Last successful operational notification diagnostics.
- Notification event history and delivery failure surfacing from outbox/Telegram worker.
- Per-event notification controls.
- Personal staff subscriptions.
- Telegram forum-topic routing.
- SLA alerts for unacknowledged operations.

### M7 — Booking Settings And Guest Booking Parity

Scope:

- M7a CLOSED / staging smoke passed: booking hold settings in Venue Mini App reuse existing bot/backend hold-minutes semantics; OWNER/MANAGER can update, STAFF stays denied/hidden.
- M7b implemented with local validation and staging visual parity: Guest Mini App `Профиль → Мои брони` lists active/upcoming bookings across venues with bot-compatible public labels, venue-local time/deadline fields and guest change/cancel actions. Recorded staging evidence compares Bot `/my` and Guest Mini App public label, venue-local time and `Держим до`; real two-account Telegram runtime isolation remains unverified.
- M7c implemented and core Telegram staging smoke passed: adaptive transactional reminders use one pre-visit reminder maximum per `CONFIRMED`/`CHANGED` booking, explicit confirmation/reschedule anchors, venue-local 24h/3h target guards and quiet window 10:00-22:00. Legacy rows are preserved but isolated by `policy_version`; legacy `PENDING`/`FAILED` rows are reconciled to `CANCELED` by migration and the worker only claims M7C rows. Recorded audit: `LEGACY/CANCELED = 3`, `LEGACY/SKIPPED = 1`, claimable legacy rows `0`.
- M7c delivery semantics: Telegram outbox enqueue marks the reminder `QUEUED`, not `SENT`; outbox status is the delivery source of truth. `Да, буду` / `Я приду` writes guest attendance intent without changing venue-controlled booking status. The write is atomic per booking schedule version, repeated actions are no-ops, reschedule clears the previous response, stale actions are rejected, and staff-chat attendance updates are deduplicated. Bot `/my` and Guest Mini App show venue status as primary plus secondary guest response; Venue Mini App keeps the staff-facing confirmation timestamp.
- M7c operations: staging was returned to `BOOKING_REMINDER_WORKER_ENABLED=false` after smoke, and `/health` plus `/db/health` were ok. The latest enriched staff-chat attendance copy is code/test-backed but was not manually re-smoked with a new booking.
- Add only small settings slices with existing bot semantics: profile/card, hours/exceptions, booking hold settings, notification toggles.
- Do not create one bulk settings endpoint.

Definition of Done:

- Each settings slice has backend route, RBAC test, UI, smoke checklist.
- Guest booking parity remains in regression; real cross-account isolation is the only explicitly unverified M7b runtime check.

### M8 — Public Profile/Card Settings

Status: CLOSED / staging smoke passed as M8a/M8b-Free.

Scope:

- OWNER/MANAGER can edit guest-facing public country/city/address/contact/description with provider-free local country/city suggestions and manual address fallback.
- STAFF is hidden/forbidden.
- Guest public read models reflect saved fields and route links prefer saved coordinates when present, otherwise encoded text address search.
- Yandex Geosuggest/Geocoder adapters remain optional, disabled by default and commercial-only until explicitly approved.

Definition of Done:

- Provider-free staging smoke passed.
- Manually entered addresses are not described as verified coordinates.
- Existing trusted coordinates remain supported.

### M9a — Deployment SSH Reliability Hardening

Status: CLOSED / staging smoke passed.

Scope:

- Standard command remains `./scripts/deploy-staging.sh <ssh-alias>`.
- Opt-in resilient command is `./scripts/deploy-staging-controlmaster.sh <ssh-alias>`.
- The helper opens one authenticated ControlMaster connection with bounded initial retries, reuses its helper-owned socket for plain SSH and rsync through the existing deploy script, uses temporary `mktemp` directories, does not modify `~/.ssh`, and cleans up its temporary resources.

Verified evidence:

- Initial master connection attempt hit an SSH banner timeout; bounded retry opened the master.
- rsync upload, Docker image build, image upload and backend recreate succeeded through the persistent connection.
- PostgreSQL stayed healthy.
- Local `/health`, local `/db/health`, Mini App static, public `/health`, public `/db/health`, public `/miniapp/`, and a separate retry-based public endpoint check passed.
- The exact fresh SSH connection failure cause remains unconfirmed; permanent SSH/network hardening is separate operations work.

### M9b — Venue Working Hours And Date Exceptions Mini App Parity

Status: IMPLEMENTED / code-test-e2e-backed; staging smoke pending.

Scope:

- Reuse existing booking-hours and date-override tables/repository semantics.
- Product decision: despite the repository name, existing Bot/product behavior treats these rows as venue `Часы работы`; for launch they intentionally govern both public operating state and booking availability.
- Owner/manager Venue Mini App APIs and UI manage weekly hours and date exceptions.
- Date exceptions support inclusive single-day or multi-day closed/special-hours ranges; the current per-date override table remains canonical and ranges expand into per-date rows.
- Optional guest-facing reason/comment is stored as nullable override `guest_note` and may appear in guest booking rejection copy.
- Keep weekly base schedule and concrete-date exceptions visually and semantically separate.
- Guest-visible today schedule/open/closed fields are added to public guest read models without exposing unpublished/private venue state.
- Direct Mini App guest booking requests are rejected outside configured hours, on closed weekdays, or on closed date overrides/periods with human schedule error codes/copy.
- Missing weekly/date schedule setup is not presented as closed: guest catalog/card shows `График не указан`, and direct booking create/update returns `VENUE_SCHEDULE_NOT_CONFIGURED` with safe guest copy.
- Preserve Bot booking behavior and existing provider-free public card settings.

Definition of Done:

- OWNER/MANAGER can save/reload weekly hours, closed weekdays and date exceptions in Venue Mini App.
- STAFF cannot see or call schedule settings routes.
- Guest catalog/card open/closed state uses the same venue timezone/date-override semantics as Bot slot generation.
- Direct guest booking POST cannot bypass schedule rules.
- V111 adds nullable `guest_note` to existing date overrides; no date-range table/backfill is introduced.

Validation:

- Backend schedule/RBAC, guest read-model and guest booking validation tests passed in focused runs.
- Mini App production build and browser e2e smoke passed locally.
- M9b.1 focused backend schedule, guest booking and Telegram closed-date tests passed locally; broad Telegram wildcard may still need splitting if local JVM stalls.
- Staging smoke is still required before marking M9b closed.

### Future — Promotions Read-Only Summary

Scope:

- Start with read-only current promotions/placements/loyalty state.
- No promotion builder or billing mutation in first Mini App slice.
- Keep bot canonical for creation/editing until route permissions are explicit.

Definition of Done:

- Mini App does not expose placeholder marketing actions.
- OWNER/MANAGER can inspect active/paused/archived status safely.

### Future — Guest Preview Entry

Scope:

- Add read-only owner/manager preview using the same guest-visible read models.
- Decide whether unpublished draft preview is allowed; otherwise preview only published guest state.

Definition of Done:

- Preview matches Guest Mini App visibility rules.
- No private staff/order/billing data leaks.

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

- Regression risk: `Продления` route is now reachable from nav for `SHIFT_EXTENSION_VIEW`; keep visibility and STAFF no-settings boundaries in smoke.
- Settings mismatch: `VENUE_SETTINGS` can imply a broad settings screen while current Mini App settings only controls backend-backed slices: booking hold, shift extension and M8b structured public card/location basics. Continue with small settings slices, not a bulk settings rewrite.
- Stats advanced gap: Mini App now has read-only per-venue stats, but no custom date range picker, AI summaries, advanced analytics, platform dashboards or consolidated network stats.
- Messages inbox regression risk: M4B/M4C unified inbox UX is staging-smoke closed, but future support work must keep multi-venue/thread scoping, active/resolved filters, unread clearing, resolve/reopen lifecycle and venue scoping covered. Structured reschedule proposals, generic tickets, Platform Support Center and venue/admin bot full inbox remain follow-ups.
- Promotions gap: bot marketing hub is broad and callback-heavy; Mini App should start read-only.
- Queue scale: Venue Mini App order queue currently uses a fixed limit and does not expose pagination.
- Runtime Telegram dependencies still need manual smoke: WebApp `initData`, staff chat group binding, QR export/download.
