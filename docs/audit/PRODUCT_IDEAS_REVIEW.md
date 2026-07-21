# Product Ideas Review

Дата: 2026-04-28. Режим: read-only audit. Код, миграции и тесты не изменялись.

> Current correction as of 2026-07-03: this file remains a historical product-ideas audit. Booking-related rows were updated for M3/M7a/M7b/M7c: Venue Mini App booking queue/lifecycle exists, booking hold settings are CLOSED / staging smoke passed, `arrival_deadline_at` is a persisted booking snapshot, Guest Mini App `Мои брони` is implemented with staging visual parity for Bot `/my` label/time/deadline, and M7c adaptive reminders passed one controlled real Telegram staging smoke while remaining disabled by default for rollout. M8a/M8b-Free public profile/card basics are also CLOSED / staging smoke passed: Venue Mini App can edit provider-free public location/contact/description. M9b/M9b.1/M9b.2/M9b.3 schedule parity is CLOSED / staging smoke passed: Venue Mini App can edit weekly hours and inclusive date-exception ranges with optional guest-facing reason/comment, and guest/Bot booking rejection copy is human. Platform Owner Invite / ADMIN Semantics Hardening and Platform Venue OWNER Revocation are CLOSED / staging smoke passed: config parity, no Platform Mini App `ADMIN` assignment, usable owner invite deep link/copy, owner invite create/accept audit, active OWNER membership list, last-owner-protected revoke, membership-based access loss and `VENUE_OWNER_REVOKE` audit are implemented. H2/PostgreSQL active-order + personal-tab uniqueness fidelity is CLOSED / validation passed: H2 now mirrors PostgreSQL active-order and active personal-tab uniqueness predicates, PostgreSQL already had the intended constraints, no production PostgreSQL migration/runtime change/staging deploy was required, and commit `a4a2d71` is on `origin/main`. Guest Table Context UX Cleanup / Feature-gated Extension Module and Guest Table Session Exit / Expiry UX are CLOSED / staging smoke passed: table-context route/copy/booking actions are hidden, extension is active-order gated, `Завершить визит` is user-scoped through `guest_table_session_exits`, shared `table_sessions` are not closed for all guests, and the staging 415 JSON `Content-Type` bug is fixed. Guest Bill / Display-Number / Full-Bill Parity, Guest Bill Request / Payment Method UX, Staff Chat Noise Reduction / Table Activity Card, hookah preparation placeholder polish, Platform Billing Cockpit / Owner Payment UX, Platform Billing Renewal / Advance Invoice / Courtesy Days and Staff/Manager invite deep-link sharing polish are also CLOSED / staging smoke passed. Info/media editing, guest preview, primary/legal/billing owner transfer helper, real acquiring provider, Telegram Stars, invoice void/reissue for courtesy conflicts, billing-created versus manual `SUSPENDED_BY_PLATFORM` distinction, advanced support and analytics remain later. M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed. Current code also implements Telegram multi-venue selected context; old "Telegram selector missing" notes below are historical, while chain/network entities remain future work.

> Current correction as of 2026-07-06: Guest Communication UX / Support Tickets MVP is now CLOSED / smoke passed. The current model is `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET`, `STAFF_CALL` in `docs/COMMUNICATION_MODEL.md`; Staff is denied support/venue chats, Platform sees support tickets but not ordinary venue chats, and support/venue chats do not post to staff-chat. Advanced support features such as SLA automation, macros, CSAT, attachments and diagnostics reports remain later.
>
> Current correction as of 2026-07-08: Booking arrival guard and staff-chat booking lifecycle buttons are DONE/MVP / staging smoke passed. Seat/no-show actions are shown and accepted only for `CONFIRMED`; `PENDING`, `CHANGED` and terminal statuses do not expose dangerous arrival actions; staff-chat booking notifications are state-aware; stale/no-permission callbacks answer safely; `BOOKING_CHAT` messages do not post to staff-chat. Reminder rollout, automatic no-show, preorder and broader visit-history/feedback integration remain partial/future.
>
> Current docs correction as of 2026-07-06: Platform cockpit status is consolidated in `docs/PLATFORM_COCKPIT.md`. Manual billing and support-ticket center are closed as MVPs; onboarding request cockpit, placements, Platform analytics, real acquiring/Stars, recurring payments and lifecycle state normalization remain future/partial.
>
> Current docs correction as of 2026-07-21: Guest growth/retention status is consolidated in `docs/GROWTH_RETENTION.md`. Guest History Foundation and Post-Visit Feedback MVP are DONE / MVP / staging-smoke-passed. Guest Favorites Phase 1 is DONE / MVP / LOCAL SMOKE PASSED for venue favorites only: catalog/detail add/remove, Account list, current-user isolation, unavailable-venue filtering and shared Bot/Mini App source are closed locally. Favorite menu items, recommendations/frequent items, repeat as a template, simple promotions/banners and notifications remain future. Promo codes, loyalty stamps/points, referrals, segmentation and paid placement/boosting remain future.
>
> Current docs correction as of 2026-07-09: Order/session/tab core status is consolidated in `docs/ORDER_SESSION_TAB_CORE.md`. Current docs say the old active-order-by-physical-table risk is closed through table-session/tab scoping and Guest History Foundation MVP is staging-smoked; the canonical model now defines `TABLE_SESSION`, `ACTIVE_TABLE_ORDER`, `ORDER_BATCH`, `TAB`, lifecycle, privacy/RBAC and visit-history dependencies. Force-close reason/audit, repeat/feedback/loyalty/preorder, some DB-level uniqueness nuances and analytics events remain future/partial.
>
> Current docs correction as of 2026-07-07: Analytics/events status is consolidated in `docs/ANALYTICS_EVENTS.md`. Analytics events, audit/event boundaries, KPI formulas, role dashboards and payload privacy rules are now the canonical target. Implementation remains partial/needs verification; client events are low-trust UX diagnostics and must not drive money, access, billing or order state.
>
> Current docs correction as of 2026-07-07: Security/RBAC status is consolidated in `docs/SECURITY_RBAC_MATRIX.md`. Roles, scopes, permissions, surface parity, dangerous actions, trust boundaries and security smoke are canonical. Permission parity and dangerous-action audit coverage remain partial unless a specific route/test/smoke proves them, and `ADMIN` is a legacy alias to `MANAGER`, not a separate product role.
>
> Current docs correction as of 2026-07-07: Menu/options/stop-list status is consolidated in `docs/MENU_OPTIONS_STOPLIST.md`. Structured menu, option values, snapshots, media/PDF boundaries, featured/top-list, stop-list, shift check, availability validation and menu permissions are canonical. Selected-option parity is smoke-closed; broader media/top-list/shift-check/audit coverage remains partial/future.
>
> Current docs correction as of 2026-07-07: Venue Mode operations are consolidated in `docs/VENUE_OPERATIONS.md`. Venue dashboard, orders, batches, tabs/bill, staff calls, bookings, menu/stop-list, tables/QR, staff/invites, staff-chat, settings, stats and operational smoke are canonical. Staff-chat remains notification/radar/shortcut, not source of truth.
>
> Current docs correction as of 2026-07-09: Booking lifecycle is consolidated in `docs/BOOKING_LIFECYCLE.md`. Guest booking flow, Venue booking queue, statuses/state machine, hold minutes, `arrival_deadline`, reminders, `BOOKING_CHAT`, booking support routing, analytics, RBAC and smoke are canonical. Current bounded slices cover queue/hold/guest list/chat and booking `SEATED` -> Guest History paths, while reminder rollout, automation, preorder and feedback remain partial/future.
>
> Current docs correction as of 2026-07-07: Telegram fallback/staff-chat is consolidated in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Telegram bot entrypoints, QR `/start`, table-context fallback order, bot staff-call, staff-chat link/test/unlink, notification policy, inline callbacks, parity, security and smoke are canonical. Staff-chat remains notification/radar/shortcut, not source of truth.
>
> Current correction as of 2026-07-08: Staff-call lifecycle finishing patch is CLOSED / staging smoke passed. Guest status now includes `NEW`, `ACK`, `DONE` and `CANCELLED` for the current guest and current `tableSessionId`; `CANCELLED` uses `Вызов отменён`; Venue active queue remains `NEW` / `ACK`. Manual cancel UI, quick replies and row-level actor/timestamp columns remain future.
>
> Historical correction as of 2026-07-09: Guest History Foundation MVP staging bugfix is CLOSED / staging smoke passed. History list/detail show closed-order visits and booking-only `SEATED` visits, hide `CANCELED` / `NO_SHOW` / `EXPIRED` / `PENDING` / `CHANGED` bookings as visits, preserve but filter legacy invalid rows, open old closed-order details without required `promotionDiscounts`/options/notes, keep `Не удалось загрузить детали истории.` for real errors, provide `← Назад к истории`, return Telegram BackButton from detail to History list, and preserve foreign-detail 404 plus personal/shared tab privacy. No migration was added. Its then-current future list is superseded by the 2026-07-21 correction above.
>
> Current correction as of 2026-07-21: Post-Visit Feedback MVP and latest smoke-fix are CLOSED / staging smoke passed. Feedback is manual from own completed History detail, including booking-only `SEATED`; Owner/Manager sees own-venue feedback; Staff denied. Owner-only `Ссылка для отзывов` is shared by Bot/Mini App. Manual `5/5` may show a safe configured Yandex CTA on explicit click; low `1..3` follow-up opens exact `VENUE_CHAT` with context and no automatic Owner message, support ticket or staff-chat notification. `VisitFeedbackWorker`, scheduled Telegram prompts and auto-redirect remain disabled.
>
> Current docs correction as of 2026-07-07: Testing/QA smoke strategy is consolidated in `docs/TESTING_QA_SMOKE_STRATEGY.md`. Local validation, GitHub Actions expectations, change-type decision matrix, staging smoke policy, failure reporting, manual smoke suites and Codex handoff rules are canonical. Do not infer release readiness from this historical audit alone.
>
> Current docs correction as of 2026-07-07: Deployment/runbook operations are consolidated in `docs/DEPLOYMENT_RUNBOOK.md`. Release model, staging deploy command, environment inventory, migration runbook, rollback policy, troubleshooting, incident response and Codex/ChatGPT handoff are canonical. Exact production deploy/rollback/backup commands remain needs verification.

## Executive summary

Из нового списка уже частично реализованы: базовые брони, Telegram `/my` для активных броней/заказов, Guest Mini App `Мои брони`, venue-side booking queue/lifecycle API, booking hold settings with persisted `arrival_deadline_at`, owner venue description sections, простой guest catalog, multi-venue membership в БД, Mini App venue selector and Telegram selected-venue context, staff chat notifications для новых order batches, order close/table session cleanup как техническая основа visit retention.

Частично, но с заметными gap: booking lifecycle, Mini App surfaces and M7c core reminder flow are now much stronger, but preorder, automatic expiry/no-show automation, broader reminder rollout and broader retention remain later; каталог без server-side search/filter/map/geo; Guest History Foundation and Post-Visit Feedback are staging-smoke-passed, venue favorites are local-smoke-passed, while favorite menu items, repeat templates and broader retention remain later; discount существует как ручной percent на item в счёте, но не как complete growth/promo/loyalty system.

Отсутствует или не launch-complete как full product flow: favorite menu items, recommendations/frequent items, automated review prompts/public review automation, Platform feedback dashboard, paid placement, promotion boosting, repeat templates, notifications, preorder, cashback/points/flexible loyalty rules, promo-code limits/accounting, referrals, segmentation/campaigns, hookah master subrole/profile/shift schedule, network/group entity for venue chains. Guest History and Post-Visit Feedback are staging-smoked; venue favorites are local-smoke-passed. M7c reminder rollout remains opt-in disabled, not absent.

Главные зависимости:
- Core order/session/tab scoping is no longer the old April P0; current code scopes active orders by table session/tab, PostgreSQL has the intended active-order and active personal-tab uniqueness constraints, and H2 now mirrors those predicates for local/test schema fidelity.
- `docs/ORDER_SESSION_TAB_CORE.md` is the current source for future order/session/tab tasks; do not infer session/tab semantics from this historical audit alone.
- `docs/ANALYTICS_EVENTS.md` is the current source for event names, KPI formulas, dashboard targets and analytics/audit/privacy boundaries; do not infer current event implementation from this historical audit alone.
- `docs/SECURITY_RBAC_MATRIX.md` is the current source for roles, permissions, scopes, dangerous actions and trust boundaries; do not infer current permission parity from this historical audit alone.
- `docs/MENU_OPTIONS_STOPLIST.md` is the current source for menu, option/modifier, stop-list, media, featured/top-list, shift-check and availability-validation decisions; do not infer current menu status from this historical audit alone.
- `docs/VENUE_OPERATIONS.md` is the current source for Venue Mode dashboard, orders, staff calls, bookings, staff-chat, settings, stats and operational smoke decisions; do not infer current venue operations status from this historical audit alone.
- `docs/BOOKING_LIFECYCLE.md` is the current source for guest booking flow, venue queue, statuses, hold/deadline, reminders, booking chat, support routing and booking smoke decisions; do not infer current booking lifecycle status from this historical audit alone.
- `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md` is the current source for Telegram bot entrypoints, fallback order, staff-call, staff-chat, callback and Telegram/Mini App parity decisions; do not infer current Telegram fallback/staff-chat status from this historical audit alone.
- `docs/TESTING_QA_SMOKE_STRATEGY.md` is the current source for validation commands, CI expectations, staging policy, failure reporting and manual smoke suite decisions; do not infer QA/release strategy from this historical audit alone.
- `docs/DEPLOYMENT_RUNBOOK.md` is the current source for release/deploy model, staging command, environment inventory, migrations, rollback, troubleshooting, incident response and Codex/ChatGPT handoff; do not infer operations readiness from this historical audit alone.
- P1 ops: table-context exit, guest/staff bill display parity, bill request/payment method UX, staff-chat activity card, guest-visible staff-call `CANCELLED`, manual platform billing cockpit, renewal/courtesy, staff invite sharing, Platform cockpit documentation and support/tickets MVP are now staging/smoke-closed or docs-closed; provider payment decision is the next launch-commercial gap before broad growth.
- P2 growth: Post-Visit Feedback and venue-only Guest Favorites Phase 1 are closed by their stated evidence level. Favorite menu items, recommendations/frequent items, repeat templates, simple promotions and notifications remain separately scoped future work.
- P3 monetization: catalog paid placement and promotion boosting need platform billing/moderation/analytics and clear advertising labels.

## Таблица статусов

| Идея | Статус | Evidence из кода | Что уже есть | Чего нет | MVP реализация | Риски | Приоритет |
|---|---|---|---|---|---|---|---|
| 1. Брони гостя и жизненный цикл | PARTIAL | `V32__bookings.sql`; `GuestBookingRepository.BookingStatus`; `GuestBookingRoutes`; `VenueBookingRoutes`; `VenueSettingsRepository`; `TelegramBotRouter.showMyOrdersAndBookings`; `guestBookings.ts`; `venueBookings.ts` | Create/update/cancel/list, venue confirm/change/cancel/confirmed-only arrival/no-show, state-aware staff-chat booking buttons, Telegram `/my`, Guest Mini App `Мои брони`, Venue Mini App queue, persisted `arrival_deadline_at`, hold setting, M7c reminder anchors and attendance intent | Preorder, broader automatic expiry/no-show policy, broader rollout of opt-in reminders, real two-account M7b isolation evidence | Keep M3/M7a/M7b/M7c and arrival/staff-chat button guards in regression | Label/timezone drift across Bot/Mini App if DTO parity regresses | P1 |
| 2. Напоминания о бронях | M7c IMPLEMENTED / CORE SMOKE PASSED | `BookingReminderWorker`, `booking_reminders`, `GuestBookingRepository.scheduleRemindersForBooking`, `BookingReminderWorkerConfig`; `V109__m7c_booking_reminders.sql`; Mini App booking screens | Immediate messages on booking create/update/cancel/venue status; M7c adaptive scheduler; policy-version legacy isolation; outbox dedupe; final callbacks; Guest/Venue attendance indicators; disabled-by-default worker; one controlled real Telegram smoke passed | Broader rollout approval and regression smoke before enabling beyond controlled test | M7c: one adaptive transactional reminder for confirmed/changed bookings using outbox and venue-local quiet window | Спам, timezone/quiet hours, duplicate sends, legacy rows sent under old policy if feature flag/query regress | P1 |
| 3. Поствизитный feedback и отзывы | DONE / MVP / STAGING-SMOKE-PASSED | Current runtime repositories/routes/screens/tests are summarized in `docs/GROWTH_RETENTION.md` | History-only rating/tags/comment; booking-only `SEATED`; Owner/Manager read; Staff denied; manual `5/5` safe Yandex CTA; low `1..3` exact `VENUE_CHAT` follow-up | Platform feedback dashboard and automated review prompts/public review automation | Keep the closed MVP in regression; no worker/Telegram prompt, auto-redirect, support ticket or staff-chat notification | Privacy/RBAC and accidental automation regressions | Regression |
| 4. Owner venue description sections | PARTIAL | `VenueInfoSectionsRepository.defaultSections`; `V46__venue_info_sections.sql`; `V48__venue_info_section_media.sql`; Telegram owner description callbacks | Default: about/rules/cork_fee/faq/menu; custom sections; image/pdf media in Telegram | Default hall plan/interior; guest Mini App display | Add templates `hall_plan`, `interior`, expose sections in guest venue API | Media storage uses Telegram file_id, Mini App rendering needs file access strategy | P1/P2 |
| 5. Guest catalog search/filter/map | PARTIAL | `GuestVenueRepository.listCatalogVenues` `ORDER BY v.id ASC`; `catalog.ts` local name/city search | Published venues, city/address, local Mini App search | Server search, address/district filters, open now, price, coordinates, map | Backend `q/city/district`, search name/city/address | Geo consent, distance accuracy, no coords | P2 |
| 6. Catalog promotion / paid placement | FUTURE | Platform billing exists separately; placements are documented as future/partial | Subscription/billing foundation | Placement/campaign/budget/status/labeling and moderation | Platform-managed paid placement with visible ad label | Abuse, undisclosed ads, unfair ranking | P3 |
| 7. Акции для гостей | SPEC UPDATED / PARTIAL-FUTURE | See `docs/GROWTH_RETENTION.md`; current foundations must be verified before DONE | Possible promotion/billing foundations | Complete simple `VENUE_PROMOTION` guest/owner flow with period/terms/status | Active venue promotion/banner list by venue/catalog without automatic discount promise | UI обещает несуществующую скидку; promo notifications without opt-in | P2 |
| 8. Продвижение акций | FUTURE | Billing infra only; paid boost is out of Growth MVP | Billing infra only | Paid boost for promotions, moderation, analytics and ad labeling | Add promotion placement entity linked to promotion after billing/moderation is ready | Fraud/moderation/billing disputes | P3 |
| 9. Мульти-venue owner / сеть | PARTIAL | `venue_members` PK `(venue_id,user_id)`; `VenueAccessRepository.listVenueMemberships`; `venueApp.ts` selector; `telegram_venue_context`; Telegram selector callbacks/tests | One user can belong to many venues; Mini App selector; Telegram selected-venue context | Network/group entity; consolidated stats; runtime smoke for multi-venue roles | Keep selector in regression; later design chain/network entity | Wrong venue actions if selected context regresses | P3 for networks; selector is implemented/regression |
| 10. Staff/order notifications | PARTIAL / CORE CLOSED | `StaffChatNotifier.notifyNewBatch`; `TelegramOutboxWorker`; `GuestBookingRoutes.notifyVenueStaffAboutBooking`; bot `notifyStaffChat`; M5/M6 staging smoke | Staff chat new order/reorder, bot and Mini App staff calls lifecycle, booking create/update/cancel messages, staff-chat diagnostics/unlink | Personal staff notifications, unified event policy, richer delivery history | Keep M5/M6 in regression; add personal subscriptions only after core smoke stays green | Telegram sound constraints, disabled chat, missing staff chat link | P2 after launch core |
| 11. История заказов гостя | DONE / MVP / STAGING-SMOKE-PASSED | Guest History Foundation list/detail exists; closed-order visits and booking-only `SEATED` visits are visible; non-seated bookings are filtered; legacy invalid rows are hidden; old closed-order details open safely | Current-user scoped History list/detail with privacy filters and merge/dedup | Keep `VISIT_HISTORY` / `ORDER_HISTORY` in regression; broader `BOOKING_HISTORY` polish and repeat remain future | History from completed closed orders and booking `SEATED`; non-seated bookings are not visits | Privacy regression, legacy optional fields, duplicate visits | Regression / then P2 follow-ups |
| 12. Избранное / любимые / repeat | FAVORITE VENUES DONE / MVP / LOCAL SMOKE PASSED; broader scope FUTURE | See `docs/GROWTH_RETENTION.md`; existing `guest_favorite_venues`, shared repository/routes and Mini App catalog/detail/Account surfaces | Venue favorite add/remove/list, current-user isolation, unavailable filtering and Bot/Mini App shared source | Favorite menu items, recommendations/frequent items, `REPEAT_TEMPLATE`, notifications | Keep venue favorites in regression; scope repeat only after Order Session Tab Core Hardening | Reordering unavailable/stopped items; accidental order creation without table context | Regression / future P2 |
| 13. Предзаказ для постоянных | MISSING | Booking comment mentions "предзаказ"; no preorder model found | Booking exists; orders exist | Preorder settings, eligibility, cutoff, staff queue | `venue_preorder_settings`, `preorders` linked to confirmed booking | Requires reliable visit_count and booking lifecycle | P3 after visit history |
| 14. Loyalty/cashback/discounts | FUTURE BEYOND MANUAL DISCOUNTS | `V58__order_batch_item_discounts.sql`; manual bill discount flow | Manual item discount percent in bill | Promo codes, cashback, points, tier discounts, category discounts, guest-specific discounts | Start only after financial model and discount accounting are correct | Financial correctness and abuse | P3 |
| 15. Promotion templates | MISSING | No promotion template model found | None | Template catalog for common promos | Static templates in owner UI creating `venue_promotions` | Template flexibility vs complexity | P2/P3 |
| 16. Hookah master role/profile | P1 DONE / SMOKE-PASSED | `docs/STAFF_PROFILES_SHIFTS_TIPS.md`; Phase 1 backend + Mini App implementation exists for staff profiles, display-only/linked profiles, today-shift visibility and guest `Сегодня работают` | Owner can add staff profile cards and mark today staff without payments | Keep Phase 1 in regression | Future: `STAFF_SCHEDULE`, photo upload/media picker, external staff tip link + intent, Telegram shift notifications/sign-up | Privacy: do not expose Telegram username, `linked_user_id`, phone/email; tips future | Regression / then spec next |
| 17. Booking/visit retention | HISTORY DONE / VISIT_COUNT FUTURE | `TableSessionRepository.closeExpiredSessions`; `guest_table_session_exits`; `VenueOrdersRepository` close; booking seated/no-show lifecycle; Guest History Foundation checkpoint | Technical table/order close, shared table-session TTL cleanup, user-scoped guest exit marker, current-user History | Valid visit counting for feedback/loyalty/preorder; shared physical table-session close policy after all bills closed | Count only closed-order and booking `SEATED` visits; filter non-seated bookings | Double counting visits; one guest exit must not count as physical table-session close | Regression / then P2 |
| 18. Что делать не сейчас | DONE | This document roadmap | Prioritization included | N/A | Roadmap P0-P3 | Growth before core creates debt | P0-P3 |

## Детальный разбор по каждому пункту

### 1. Брони гостя и жизненный цикл брони

Продуктовая цель: гость видит актуальные брони, заведение управляет подтверждением/переносом/отменой, система понимает arrival deadline, no-show and seated.

Текущее состояние в коде:
- Брони хранятся в `bookings`: `id`, `venue_id`, `user_id`, `scheduled_at`, `party_size`, `comment`, `status`, timestamps.
- Status enum в текущем коде включает lifecycle statuses for booking operations, including venue arrival/no-show/seated states where supported by current routes.
- Активные брони у гостя: `GuestBookingRepository.listActiveByUser` is now used by Bot `/my` and Guest Mini App `Мои брони`; active visibility uses persisted `arrival_deadline_at` when available.
- Активные брони у venue: Venue Mini App booking queue/lifecycle MVP exists.
- `arrival_deadline_at` is persisted as a booking snapshot. New bookings use the current hold setting; rescheduled bookings recalculate; changing the setting does not rewrite already-created booking deadlines.

Evidence:
- `backend/app/src/main/resources/db/migration/postgresql/V32__bookings.sql`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/db/GuestBookingRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/bookings/VenueBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`: `showMyOrdersAndBookings`, `editMyBooking`, `cancelMyBooking`

Расхождение с концепцией:
- `docs/PRODUCT_SPEC.md` требует booking status `pending/confirmed/changed/cancelled/expired`.
- В новом списке также нужны `no_show/seated`; их нет.
- Hold duration setting, guest-facing `Держим до HH:mm` and M7c reminder code now exist; one controlled real Telegram reminder smoke passed, while broader opt-in rollout and broader automation remain missing.

MVP дизайн:
- Keep current Bot `/my`, Guest Mini App `Мои брони`, Venue Mini App queue, hold settings and M7c disabled-by-default behavior in regression.
- Run M7c adaptive transactional reminders as an approval-gated regression or rollout smoke before enabling beyond the controlled test.
- Do not broaden booking settings into reminders/quiet hours/preorder in the same slice.

Расширенный дизайн:
- Настройки per day/venue, автоматический no-show после grace period.
- Seat booking creates/links `visit` and optionally table_session.
- Booking timeline/audit and analytics `booking_status_changed`.

Технические изменения:
- `BookingStatus`, `GuestBookingRepository`, `VenueBookingRoutes`, Telegram booking actions.
- Reminder scheduler/outbox repository and policy-version reconciliation are implemented for M7c.
- Optional future preorder/visit retention links after booking reminders and visit_count are stable.

Миграции БД:
- No new migration for M7b.
- M7c reminder tables/outbox scheduling are separate from hold settings.

API/routes:
- `GET /api/guest/bookings` account-level active/upcoming list.
- Existing guest update/cancel/confirm routes stay lifecycle source of truth.

Telegram bot changes:
- `/my` remains Bot parity reference for public booking label and deadline.
- M7c reminder buttons must not overwrite venue confirmation status.

Mini App changes:
- Guest `Мои брони` screen is implemented with staging visual parity against Bot `/my` for label/time/deadline; real two-account Telegram runtime isolation remains unverified.
- Keep venue booking queue/settings smoke in regression.

Tests/smoke checks:
- Active booking remains visible until `scheduled_at + hold_minutes`.
- Expiry/no_show worker does not affect canceled/seated bookings.
- Guest cannot edit expired/no_show/seated booking.

Риски:
- Timezone correctness; current formatting often uses system default.
- Automatic no-show can anger venues if grace settings wrong.

Рекомендованный приоритет: **P1**, after P0 order/session/tab blockers.

Что делать не сейчас: advanced deposits/table assignment until booking lifecycle is stable.

### 2. Напоминания о бронях

Продуктовая цель: снижать no-show и дать гостю быстрые actions перед визитом.

Текущее состояние в коде:
- Immediate outbound messages есть: guest booking create/update/cancel notifies staff chat; venue confirm/change/cancel notifies guest.
- M7c `BookingReminderWorker`, `booking_reminders` policy versioning and Telegram callbacks implement the accepted adaptive transactional policy locally.
- The worker is opt-in disabled by default through `BOOKING_REMINDER_WORKER_ENABLED=false`; missing/blank/malformed config does not start it, and explicit `true` is required.
- `TelegramOutboxWorker` уже умеет retries/backoff for outbound messages.

Evidence:
- `GuestBookingRoutes.notifyVenueStaffAboutBooking`
- `VenueBookingRoutes` sends messages to `booking.userId`
- `Application.kt` worker startup and `BookingReminderWorkerConfig`
- `BookingReminderWorker`, `GuestBookingRepository.scheduleRemindersForBooking`, `V109__m7c_booking_reminders.sql`
- `TelegramOutboxEnqueuer`, `TelegramOutboxWorker`, `V26__telegram_outbox.sql`

Расхождение с концепцией:
- Code now implements the adaptive one-reminder M7c policy and one controlled real Telegram staging smoke passed.
- Historical legacy `PENDING`/`FAILED` rows are preserved but reconciled to `CANCELED` by V109, and the worker claims only `policy_version='M7C'`. Recorded staging audit after acceptance: `LEGACY/CANCELED = 3`, `LEGACY/SKIPPED = 1`, claimable legacy rows `0`.
- Reminder state is truthful: outbox enqueue sets `QUEUED`; Telegram delivery remains sourced from `telegram_outbox`.
- Runtime rollout remains disabled by default and currently false on staging; explicit opt-in is required for future smoke or rollout.

MVP дизайн:
- Worker every 1-5 minutes finds due M7C reminders for confirmed/changed bookings when the feature flag is explicitly enabled.
- One adaptive transactional reminder maximum per booking: preferred 24h target, 3h fallback, venue-local quiet window.
- Message: venue, public booking number, time, party size, hold until, buttons `Да, буду`, `Перенести`, `Отменить`.
- Store reminder and outbox dedupe keys to prevent duplicates; `Да, буду` records `last_guest_confirmation_at` separately and never overwrites venue confirmation status.

Расширенный дизайн:
- Multiple reminders: day before + same day.
- Quiet hours and venue timezone.
- Auto-expire/no-show after hold.

Технические изменения:
- Replace/upgrade legacy `BookingReminderWorker` scheduling policy behind the opt-in flag. Done and core real Telegram staging smoke passed.
- Use existing outbox for delivery and keep result semantics honest: outbox enqueue is not Telegram-delivered. Done with `QUEUED`.
- Add/fix Telegram callback handlers for final M7c copy and guest attendance intent. Done and covered by the core Telegram smoke.

Миграции БД:
- V109 adds booking confirmation/reschedule anchors, reminder `policy_version`, reminder `QUEUED` status and outbox dedupe key; `last_guest_confirmation_at` is reused for guest attendance intent.
- Per-venue reminder timing/quiet hours remain later; do not add them to M7c MVP unless the accepted policy requires it.

API/routes:
- No reminder timing settings route in M7c MVP.

Telegram bot changes:
- Callback data for `Да, буду`, `Перенести`, `Отменить`; button copy must not say `Подтвердить бронь` because venue confirmation is a separate status.

Mini App changes:
- Guest and Venue Mini App booking cards expose attendance-confirmed state when `lastGuestConfirmationAt` is present. No reminder timing settings UI in M7c MVP.
- The latest enriched staff-chat attendance copy includes public booking number, venue-local booking date/time, guest display name/fallback, party size and persisted hold deadline; this copy is code/test-backed but was not manually re-smoked with a new booking.

Tests/smoke checks:
- Worker disabled by default and explicit-enable only.
- Reminder sent once under final adaptive policy.
- Reschedule invalidates old reminder and schedules one new reminder when eligible.
- Quiet window moves only earlier or skips.
- Real Telegram staging regression verifies visible message/buttons before broader rollout.

Риски:
- Telegram users who never opened bot may not receive messages.
- Too many reminders can trigger complaints.

Рекомендованный приоритет: **P1**.

Что делать не сейчас: multistep marketing automation; start with one operational reminder.

### 3. Поствизитный feedback и отзывы

Current correction: the April evidence/design below is historical and superseded by the staging-smoked MVP described in `docs/GROWTH_RETENTION.md`. The implemented flow is History-detail-only; it deliberately does not schedule a worker/Telegram prompt. The public review CTA is a manual `5/5` click when the Owner-configured safe URL exists, and low-rating follow-up uses exact `VENUE_CHAT` with context.

Продуктовая цель: after visit ask for rating/comment and optionally route happy guests to public review.

Текущее состояние в коде:
- Order close exists in `VenueOrdersRepository` and Telegram staff order flow.
- Table sessions can close by TTL or explicit repository method.
- No review/rating/feedback tables/routes/screens found.
- Yandex route URL exists only as address route helper, not review link storage.

Evidence:
- `VenueOrdersRepository.updateBatchStatus`/close paths and `OrderWorkflowStatus.CLOSED`
- `TableSessionRepository.closeSession`, `closeExpiredSessions`
- `TelegramBotRouter.buildVenueRouteUrl` creates `https://yandex.ru/maps/?text=...`
- `rg` did not find product review/feedback route/table files.

Расхождение с концепцией:
- `docs/PRODUCT_SPEC.md` Block 15 requires post-visit review flow.

MVP дизайн:
- Trigger when order becomes `CLOSED` or table session ends after delivered order.
- Send next day at venue local 11:00-13:00, not immediately and not at night.
- Ask rating 1-5. If 4-5 and Yandex link exists, show "Оставить отзыв на Яндекс.Картах"; always store internal rating/comment.

Расширенный дизайн:
- Segment by visit type, no-show exclusion, retry once if no response.
- Owner dashboard for feedback trends.

Технические изменения:
- Feedback repository/routes.
- PostVisitFeedbackWorker using outbox.
- Venue settings for public review link.

Миграции БД:
- `venue_review_links(venue_id, yandex_maps_review_url, updated_by, updated_at)` or column in venue settings/profile.
- `guest_feedback(id, venue_id, user_id, order_id, table_session_id, booking_id, rating, comment, source, created_at)`.
- `feedback_requests(id, trigger_type, trigger_id, send_after, sent_at, status)`.

API/routes:
- `POST /api/guest/feedback`
- `GET/PUT /api/venue/{venueId}/review-link`
- `GET /api/venue/feedback`

Telegram bot changes:
- Inline rating buttons and comment prompt.
- Link button to Yandex review.

Mini App changes:
- Owner settings field for Yandex link.
- Optional feedback modal if user opens Mini App after visit.

Tests/smoke checks:
- Closed order schedules feedback next day at allowed local time.
- No duplicate request for same visit/order.
- Low rating does not push public review link.

Риски:
- Incorrect timing if venue timezone missing.
- Public review link moderation and abuse.

Рекомендованный приоритет: **P2**, after reliable visit/order close.

Что делать не сейчас: public review automation before visit_count and close signals are correct.

### 4. Owner venue description sections

Продуктовая цель: owner can build rich venue card: about, rules, cork fee, FAQ, menu preview, hall plan, interior.

Текущее состояние в коде:
- Default sections exist: `about`, `rules`, `cork_fee`, `faq`, `menu`.
- Custom sections exist.
- Media attachments support `image` and `pdf` via Telegram file IDs.
- Owner Telegram UI manages description; guest Telegram catalog can show about sections.
- Guest Mini App venue DTO only has id/name/city/address/status; description sections are not exposed.

Evidence:
- `VenueInfoSectionsRepository.defaultSections`
- `V46__venue_info_sections.sql`
- `V48__venue_info_section_media.sql`
- `VenueInfoSectionMediaRepository`
- `TelegramKeyboards` callbacks `owner_venue_description_*`, `bot_catalog_venue_about_section`
- `GuestVenueRoutes.toVenueDto`

Расхождение с концепцией:
- Concept asks description/hours/menu preview; new list asks default hall plan/interior.
- `hall_plan` and `interior` defaults are missing.
- Mini App guest does not display these sections.

MVP дизайн:
- Add default templates `hall_plan` title `Схема зала`, sort 60; `interior` title `Интерьер`, sort 70.
- Expose visible sections in `GET /api/guest/venue/{id}`.
- For media, first keep Telegram `file_id` not directly renderable in Mini App; expose metadata and show in Telegram, or add backend media proxy later.

Расширенный дизайн:
- Upload media from Mini App to object storage.
- Section templates with recommended copy, ordering and required/optional flags.

Технические изменения:
- Extend `VenueInfoSectionsRepository.defaultSections`.
- Add GuestVenueRepository method to load visible sections.
- Add Mini App venue detail section renderer.

Миграции БД:
- No schema change for new section types.
- Optional backfill inserting missing defaults for existing venues.

API/routes:
- Extend `VenueDto` with `sections`.
- Add admin Mini App routes if owner editing moves out of Telegram.

Telegram bot changes:
- Humanize section types `hall_plan`, `interior`.

Mini App changes:
- Guest venue card: sections and media.
- Owner settings/description editor later.

Tests/smoke checks:
- Existing venues get new default sections once.
- Hidden sections are not returned to guest.

Риски:
- Telegram `file_id` cannot be safely used as public Mini App image URL without proxy/file download strategy.

Рекомендованный приоритет: **P1/P2**.

Что делать не сейчас: full media CMS before deciding storage/proxy.

### 5. Guest catalog: поиск, сортировка, фильтры, карта

Продуктовая цель: guest finds relevant venues by name, area, city, availability and distance.

Текущее состояние в коде:
- Backend catalog returns published venues not blocked by subscription.
- Sort is `ORDER BY v.id ASC`.
- Mini App local search filters by venue name and city only.
- DB `venues` has city/address, no latitude/longitude/district columns found.
- Telegram route helper parses coordinates from address text if present; this is not a real geo model.

Evidence:
- `GuestVenueRepository.listCatalogVenues`
- `GuestVenueRoutes.get("/catalog")`
- `miniapp/src/screens/catalog.ts`
- `V1__init.sql` `venues(name, city, address, status, features, ui_layout...)`
- `TelegramBotRouter.buildVenueRouteUrl`

Расхождение с концепцией:
- Spec requires address/description/hours/menu preview/pricing; new idea adds filters and map.
- Current catalog is minimal.

MVP дизайн:
- Backend params `q`, `city`; search `name/city/address`.
- Sort by `name` or `city,name`; later promoted/nearby.
- Mini App server-side search input with debounce.

Расширенный дизайн:
- Add `district`, `lat`, `lon`, `price_level`, opening hours.
- "Рядом со мной": Telegram WebApp geolocation consent, distance calculation, sorting.
- Map view using stored coordinates.

Технические изменения:
- Extend `GuestVenueRepository.listCatalogVenues(filters)`.
- DTO fields for description/hours/price preview.
- Add indexes for search.

Миграции БД:
- `venues.district`, `latitude`, `longitude`, maybe PostGIS later.
- `venue_price_summary` materialized fields or computed from menu.

API/routes:
- `GET /api/guest/catalog?q=&city=&district=&openNow=&lat=&lon=`

Telegram bot changes:
- Optional text search in catalog, city filters.

Mini App changes:
- Filters UI; location consent and "near me" button.

Tests/smoke checks:
- Search by name/city/address.
- Suspended/canceled venues still hidden.
- Distance sort only after consent and coords.

Риски:
- Address parsing as coords is unreliable.
- "Open now" requires correct venue timezone and hours model.

Рекомендованный приоритет: **P2**.

Что делать не сейчас: map/distance before coordinates and search MVP.

### 6. Catalog promotion / paid placement

Продуктовая цель: platform monetizes catalog visibility without hiding organic trust.

Текущее состояние в коде:
- No promoted placement/campaign/ad model found.
- Billing/subscription exists but not tied to catalog ranking.
- Catalog sort is deterministic by id.

Evidence:
- `GuestVenueRepository.listCatalogVenues`
- `SubscriptionBillingEngine`, `PlatformBillingRoutes` exist for subscriptions/invoices.
- `rg --files ... | rg -i 'promo|campaign|placement|ads|advert'` returned no product files.

Расхождение с концепцией:
- Spec includes subscriptions, not paid catalog placement.
- This is a new monetization layer.

MVP дизайн:
- Platform-only manual placements: venue, start/end, priority, status.
- Catalog merges active promoted venues at top with label `Промо`.
- No budget billing in first MVP; invoice/manual mark paid can be linked later.

Расширенный дизайн:
- Campaign budget, CPM/CPC-like metrics, moderation, automatic expiry, billing invoices.

Технические изменения:
- Placement repository and platform routes.
- Catalog query sorts active placements first, then organic.

Миграции БД:
- `catalog_promoted_placements(id, venue_id, starts_at, ends_at, status, priority, label, created_by, created_at)`.
- Optional `campaign_id`, `budget_minor`, `billing_invoice_id`.

API/routes:
- Platform CRUD: `/api/platform/catalog-placements`.
- Guest catalog returns `placementLabel` or `isSponsored`.

Telegram bot changes:
- Catalog card label `Промо`/`Реклама`.

Mini App changes:
- Clear label: `Промо`, `Реклама` or `Рекомендуем`.

Tests/smoke checks:
- Only published and guest-available venues can be promoted.
- Expired placements do not show.
- Label always present.

Риски:
- Hidden ads and unfair ranking can damage trust.
- Need moderation for content and billing disputes.

Рекомендованный приоритет: **P3**.

Что делать не сейчас: budgets/auction/auto billing before platform analytics and a real acquiring/Stars provider decision.

### 7. Акции для гостей

Продуктовая цель: guest sees current venue offers in catalog, venue card and table context.

Текущее состояние в коде:
- Guest Telegram menu has `🎁 Акции`.
- Handler `showPromotions` returns placeholder text.
- No promotions/coupons/campaign tables/routes/screens found.

Evidence:
- `TelegramKeyboards.mainMenu`
- `TelegramBotRouter.showPromotions`
- `ROLE_GUEST.md` and `PRODUCT_AUDIT_SUMMARY.md` also mark promotions missing.

Расхождение с концепцией:
- Spec Block 15 requires venue promotions/announcements.

MVP дизайн:
- Venue owner/manager creates active text promotions: title, description, valid_from/to, optional venue/menu category.
- Guest sees list in `🎁 Акции`, venue card and Mini App venue screen.

Расширенный дизайн:
- Coupon codes, redemption tracking, table-context-only offers.
- Promotion templates and moderation.

Технические изменения:
- Promotion repository/routes.
- Owner/manager Mini App screen.
- Guest catalog and venue screen integration.

Миграции БД:
- `venue_promotions(id, venue_id, title, description, starts_at, ends_at, status, visibility_scope, created_by, updated_at)`.

API/routes:
- `GET /api/guest/promotions`, `GET /api/guest/venue/{id}/promotions`
- `GET/POST/PATCH/DELETE /api/venue/{venueId}/promotions`

Telegram bot changes:
- Replace placeholder with active promo list.

Mini App changes:
- Promo badges in catalog/venue/table context.
- Owner/manager promo editor.

Tests/smoke checks:
- Expired/hidden promos not visible.
- Suspended venue promos not visible.

Риски:
- Misleading offers without moderation.
- Needs terms/conditions text.

Рекомендованный приоритет: **P2**.

Что делать не сейчас: coupon redemption/paid promotion until simple promo list is stable.

### 8. Продвижение акций

Продуктовая цель: allow paid/top placement for specific promotions.

Текущее состояние в коде:
- No promo model; no promotion placement model.
- Billing exists independently.

Evidence:
- Same as items 6 and 7.

Расхождение с концепцией:
- New monetization/growth idea; not in current implementation.

MVP дизайн:
- Requires item 7 first.
- Add `promotion_placements` active start/end/priority.
- Platform owner approves promotion before top placement.

Расширенный дизайн:
- Budget, impressions, clicks, conversion to booking/order.

Технические изменения:
- Extend promotions with `moderation_status`.
- Placement repository/platform routes.

Миграции БД:
- `promotion_placements(id, promotion_id, starts_at, ends_at, priority, status, billing_invoice_id)`.
- `promotion_impressions/clicks` later.

API/routes:
- Platform moderation and placement CRUD.
- Guest promo list returns sponsored metadata.

Telegram bot changes:
- Label boosted promos.

Mini App changes:
- Sponsored promo slots in catalog/venue.

Tests/smoke checks:
- Boosted expired promotion not shown.
- Label always present.

Риски:
- Abuse and misleading discounts.
- Needs moderation and billing reconciliation.

Рекомендованный приоритет: **P3**.

Что делать не сейчас: before simple promotions and platform billing UI.

### 9. Мульти-venue owner / сеть кальянных

Продуктовая цель: support owners/managers with many venues and future chains.

Текущее состояние в коде:
- DB supports many memberships per user via `venue_members` primary key `(venue_id,user_id)`.
- Mini App `/api/venue/me` returns all memberships; `venueApp.ts` has `<select>`.
- Telegram bot stores selected venue context per chat/user and prompts selection when multiple current memberships are available; stale or unauthorized callbacks are rejected.
- Manager/staff assigned per venue via `venue_members`.
- No network/group entity.

Evidence:
- `VenueAccessRepository.listVenueMemberships`
- `VenueRoutes.get("/me")`
- `miniapp/src/screens/venueApp.ts`
- `TelegramBotRouter.resolveSelectedVenueBotAccess`
- `TelegramVenueContextRepository`
- `V67__telegram_venue_context.sql`
- `V11__venue_members.sql`

Расхождение с концепцией:
- Multi-tenant exists; chain/network management is beyond spec v1.
- Historical Telegram "first venue" problem is closed in current code; keep multi-venue role smoke in regression.

MVP дизайн:
- Telegram: keep "Выберите заведение" selected-context behavior in regression.
- Mini App: keep venue selector labels clear.

Расширенный дизайн:
- `venue_networks`, `network_members`, bulk roles, consolidated stats, shared menu templates.

Технические изменения:
- Future chain/network data model and aggregate dashboards only if product needs multi-venue networks.

Миграции БД:
- MVP none.
- Extended: `venue_networks`, `venue_network_members`, `venue_network_roles`.

API/routes:
- Future `/api/network/...`.

Telegram bot changes:
- Role-aware menu is selected-venue-aware; keep callback authorization and stale-context cleanup in regression.

Mini App changes:
- Better network filters only after chain entity design.

Tests/smoke checks:
- Owner/manager/staff with two venues can choose each in Telegram and actions apply to the selected venue.

Риски:
- Existing flows parse venueId in callbacks; selected context and callback authorization must stay aligned.

Рекомендованный приоритет: selector is implemented/regression; **P3** for chain entity.

Что делать не сейчас: consolidated chain analytics before per-venue stats are complete in Mini App.

### 10. Staff/order notifications

Продуктовая цель: staff reliably notices new orders/calls/bookings.

Текущее состояние в коде:
- Mini App orders call `StaffChatNotifier.notifyNewBatch` after non-idempotent `add-batch`.
- Bot quick orders and bot staff calls call `TelegramBotRouter.notifyStaffChat`.
- Booking create/update/cancel notifies staff chat directly in `GuestBookingRoutes`.
- Venue booking confirm/change/cancel notifies guest directly in `VenueBookingRoutes`.
- Outbox has retry/backoff.
- Personal staff/manager/owner notifications are not implemented.
- Mini App staff call route exists but prior audit found frontend payload missing `tableSessionId`; route also does not call `StaffChatNotifier`.

Evidence:
- `GuestOrderRoutes.notifyStaffChat`
- `StaffChatNotifier.notifyNewBatch`
- `TelegramBotRouter.confirmQuickOrder`, `createStaffCall`, `notifyStaffChat`
- `GuestBookingRoutes.notifyVenueStaffAboutBooking`
- `TelegramOutboxWorker`
- `VenueSettingsRepository` notification toggles

Почему при тесте могло не прийти явное уведомление:
- Staff chat not linked: `venues.staff_chat_id` null.
- Telegram disabled or token empty: `StaffChatNotifier.isTelegramActive`.
- `notify_orders_enabled` false in `venue_settings`.
- Idempotency replay suppresses repeat notification in `GuestOrderRoutes`.
- Outbox failed/retrying or staff chat bot lacks access.
- Mini App staff call payload/lifecycle and staff-chat notification path were fixed in later M5 work; keep linked staff-chat runtime in per-venue regression.
- Booking notifications are direct staff chat messages, now with state-aware lifecycle buttons; they are not a unified notifier/dedupe pipeline.

MVP дизайн:
- Make staff chat notification for order/reorder always-on by default.
- Keep Mini App staff call notification in regression.
- Add personal opt-in notifications for staff via bot only after staff starts bot.

Расширенный дизайн:
- Event bus: `venue_events` -> notification policies.
- Per role and per event settings.
- SLA alerts if unaccepted after N minutes.

Технические изменения:
- Unified notification service for order/call/booking/cancellation.
- Preserve Mini App staff call payload and route notification coverage.
- Add diagnostics screen "Последнее уведомление".

Миграции БД:
- Optional `staff_notification_subscriptions(user_id, venue_id, event_type, enabled)`.
- Optional `venue_notification_events`.

API/routes:
- Staff notification preferences.
- Notification diagnostics endpoint.

Telegram bot changes:
- `/notifications_on`, `/notifications_off` or settings callbacks.

Mini App changes:
- Venue settings/diagnostics for staff chat and event toggles.

Tests/smoke checks:
- New order creates outbox message.
- Disabled setting suppresses only intended event.
- Retry after 429.
- Staff call Mini App sends notification.

Риски:
- Telegram cannot force sound; user/chat notification settings control sound.
- Personal notifications require prior bot chat with staff.

Рекомендованный приоритет: **P1**.

Что делать не сейчас: per-staff advanced routing before basic all-event notification reliability.

### 11. История заказов гостя

Продуктовая цель: guest can see past visits/orders and reuse them.

Текущее состояние в коде:
- Historical note below is superseded by the 2026-07-09 Guest History Foundation checkpoint.
- `/api/guest/visits` and `/api/guest/visits/{visitId}` exist for current-user scoped History.
- Closed-order visits and booking-only `SEATED` visits are visible; `CANCELED`, `NO_SHOW`, `EXPIRED`, `PENDING` and `CHANGED` bookings are hidden as visits.
- Legacy invalid rows are preserved but filtered; old closed-order details open safely without required `promotionDiscounts`/options/notes.
- Repeat templates remain future.

Evidence:
- Current evidence is tracked in `docs/GROWTH_RETENTION.md`, `docs/ORDER_SESSION_TAB_CORE.md` and the 2026-07-09 checkpoint above.

Расхождение с концепцией:
- Spec Block 15 still needs repeat template and broader retention loops; the History foundation itself is staging-smoked.

MVP дизайн:
- Guest History Foundation MVP is DONE / staging-smoke-passed.
- Repeat should remain a separate template flow and be available only when guest is in active table context and items are still available.

Расширенный дизайн:
- Visit timeline combining bookings, table sessions, orders, feedback.

Технические изменения:
- Future: repeat-template service from completed history with current menu/stop-list validation.
- Future: full base item historical snapshotting if a later audit still finds gaps beyond current safe rendering.

Миграции БД:
- Optional `guest_order_participants(order_id,user_id,tab_id)` to avoid inferring from idempotency.
- Optional `visits`.

API/routes:
- `GET /api/guest/orders/history`
- `POST /api/guest/orders/{orderId}/repeat-template`

Telegram bot changes:
- `/history`, inline "Повторить".

Mini App changes:
- History tab/screen, repeat to cart.

Tests/smoke checks:
- Closed order appears in history.
- Old closed order with missing discounts/options opens detail.
- Non-seated bookings do not appear as visits.
- Foreign details remain 404 and чужие personal/shared/private details stay hidden.
- Canceled/rejected items are not repeated when repeat template is implemented later.

Риски:
- Current order/session/tab scoping issues can leak or mix history.

Рекомендованный приоритет: History, Post-Visit Feedback and venue-only Guest Favorites Phase 1 are DONE at their documented evidence levels. Do not move directly to repeat/promotions; next runtime recommendation is **Order Session Tab Core Hardening**.

Что делать не сейчас: analytics-heavy personalization before growth analytics events and safe aggregation are verified.

### 12. Избранное / любимые позиции / быстрый повтор

Продуктовая цель: reduce guest ordering friction on repeat visits.

Текущее состояние в коде:
- Guest Favorites Phase 1 is DONE / MVP / LOCAL SMOKE PASSED for favorite venues only.
- Existing `guest_favorite_venues` storage and `GuestFavoritesRepository` are the shared Bot/Mini App source of truth; no new migration was required.
- Guest can add/remove from catalog and venue detail and open the venue-only Account list.
- Catalog/detail expose current-user `isFavorite`; unavailable venues are filtered without disclosure and temporary hide/suspend preserves the favorite row.
- Favorite menu items, frequent-items aggregation, recommendations and repeat remain absent/future.

Evidence:
- Focused backend favorites tests passed together with `compileKotlin` and `ktlintCheck`.
- Mini App build passed; full browser e2e smoke passed `62/62`.
- Bot and Mini App use the same repository/source.

Phase 1 DONE scope:
- favorite venues;
- catalog/detail actions;
- Account favorites list and empty state;
- current-user isolation;
- hidden/suspended/subscription-blocked venue filtering without row deletion.

Future scope:
- favorite menu items;
- recommendations and `Часто берёте`;
- repeat order/template;
- notifications.

Расширенный дизайн:
- Cross-venue recommendations, flavor preferences, smart bundles.

Tests/smoke checks:
- Add favorite from catalog.
- Add favorite from venue detail.
- Remove favorite.
- Verify Account venue-only list and empty state.
- Verify two-user isolation.
- Verify hidden/suspended/subscription-blocked filtering while the row survives.
- Verify Bot/Mini App shared source.

Риски:
- Personalized `isFavorite` must never leak through a shared catalog cache.
- Future repeat without active table context could imply preorder accidentally.

Рекомендованный приоритет: keep Phase 1 in regression; next runtime block is **Order Session Tab Core Hardening**.

Что делать не сейчас: favorite menu items, recommendations/frequent items, repeat or notification shortcuts. Repeat must not be started before active-order/session/tab privacy hardening is complete.

### 13. Предзаказ для постоянных гостей

Продуктовая цель: let trusted regular guests preorder for a confirmed booking.

Текущее состояние в коде:
- No preorder model found.
- Booking comment text mentions "предзаказ", but it is only free text.
- No visit_count model.

Evidence:
- `TelegramBotRouter.renderBotVenueBookingCommentStep` asks for wishes/preorder in comment.
- `GuestBookingRepository`, `GuestOrderRoutes` have no preorder link.
- `rg --files ... | rg -i preorder` no product files.

Расхождение с концепцией:
- Not in base spec, new growth/ops feature.

MVP дизайн:
- Venue setting: enabled, minimum past visits N, cutoff minutes before booking.
- Only guests with `CONFIRMED` booking and visit_count >= N can create preorder.
- Preorder enters staff queue as future batch/order with due time.

Расширенный дизайн:
- Deposit/payment, auto-convert to order on seating, cancellation policies.

Технические изменения:
- Visit_count foundation first.
- Preorder repository/routes.
- Staff queue supports future/preorder status.

Миграции БД:
- `venue_preorder_settings(venue_id, enabled, min_visit_count, cutoff_minutes)`.
- `preorders(id, venue_id, booking_id, user_id, status, due_at, comment)`.
- `preorder_items`.

API/routes:
- `POST /api/guest/preorders`
- `GET /api/venue/preorders`
- Venue settings routes.

Telegram bot changes:
- Booking detail button "Предзаказ" if eligible.

Mini App changes:
- Preorder cart tied to booking.
- Venue queue filter "Предзаказы".

Tests/smoke checks:
- Ineligible guest blocked.
- Confirmed booking required.
- Cutoff enforced.

Риски:
- Requires reliable booking lifecycle and visit_count; otherwise unfair eligibility.

Рекомендованный приоритет: **P3**.

Что делать не сейчас: preorder before P0/P1 booking and visit retention.

### 14. Акции/лояльность/cashback/скидки

Продуктовая цель: flexible retention and discount mechanics.

Текущее состояние в коде:
- Manual bill discount percent exists at order item level.
- Statistics count discounts.
- No promo codes, cashback, points, loyalty tiers, category discounts, guest-specific discount tables/routes/screens found.

Evidence:
- `V58__order_batch_item_discounts.sql`
- `VenueOrdersRepository.setBatchItemDiscountPercent`
- `TelegramBotRouter` callbacks `staff_order_bill_discount:*`
- `VenueStatsRepository.discountMinor`

Расхождение с концепцией:
- Spec mentions promotions/announcements, not full loyalty engine.

MVP дизайн:
- Do not start with flexible engine.
- First add simple promotions (item 7).
- Then add promo code percent/fixed discount applied to order/batch with strict limits.

Расширенный дизайн:
- Loyalty rules engine: conditions, rewards, category scope, max payment with points, tiers by spend.

Технические изменения:
- Discount calculation service.
- Persist applied discounts separately from manual item percent.

Миграции БД:
- `promo_codes`, `promo_redemptions`.
- Later `loyalty_accounts`, `loyalty_transactions`, `loyalty_rules`, `guest_discounts`.

API/routes:
- Apply promo code on cart/order.
- Owner promo rule CRUD.

Telegram bot changes:
- Enter promo code, display applied discount.

Mini App changes:
- Promo code field, loyalty balance later.

Tests/smoke checks:
- Max discount cap.
- No double application.
- Excluded items/categories respected.

Риски:
- Money correctness, abuse, refund/cancel reversals.

Рекомендованный приоритет: **P3**.

Что делать не сейчас: cashback/points/flexible loyalty in MVP.

### 15. Promotion templates

Продуктовая цель: make common hookah promotions easy to create.

Текущее состояние в коде:
- No promotion templates.
- No promotion entity yet.

Evidence:
- No files for promotion/campaign/template found.

Популярные шаблоны для кальянных:
- Happy hours: скидка в будни/до 18:00.
- Sixth hookah free: каждый 6-й кальян бесплатно.
- Birthday discount: скидка в день рождения +/- N дней.
- Weekday discount: тихие дни недели.
- Combo hookah + tea: набор фиксированной ценой.
- Cashback percent: X% бонусами.
- New guest bonus: первый визит/первый заказ.
- Loyal guest tier: скидка по сумме/числу визитов.

MVP дизайн:
- Start with static templates in frontend, creating simple `venue_promotions` records: title/description/period/terms.
- No automatic discount application in first MVP.

Расширенный дизайн:
- Template parameters and automatic rule execution.

Технические изменения:
- Requires item 7 promotion entity first.
- Template JSON registry can live in frontend/backend.

Миграции БД:
- For static copy templates: none beyond `venue_promotions`.
- For executable templates: `promotion_templates`, `promotion_rules`.

API/routes:
- `GET /api/venue/promotion-templates`.

Telegram bot changes:
- Owner can pick template in bot later.

Mini App changes:
- Owner promo creation wizard.

Tests/smoke checks:
- Template creates valid promotion.
- Terms are visible to guest.

Риски:
- Automatic discount rules too early will couple with billing/order totals.

Рекомендованный приоритет: **P2/P3**.

Что делать не сейчас: executable loyalty templates before simple announcements.

### 16. Hookah master / кальянщик как роль или сменный профиль

Canonical update as of 2026-07-08: `docs/STAFF_PROFILES_SHIFTS_TIPS.md` defines
`STAFF_PROFILE`, `SHIFT_TODAY`, future `STAFF_SCHEDULE` and future `STAFF_TIP`. Phase 1
staff profiles + today on shift is implemented and local-smoke-passed with no payments. Phase 2 may
add external staff tip link + `staff_tip_intent`; provider/direct payout, Telegram Stars and crypto
are not MVP.

Продуктовая цель: show guests who is working today without exposing contacts.

Текущее состояние в коде:
- Roles: `OWNER`, `ADMIN`, `MANAGER`, `STAFF`; current runtime maps `ADMIN` as a legacy alias to `MANAGER`, not a separate Venue Admin model.
- Staff profiles support display-only or linked staff cards with subtype/custom role label.
- Owner can publish/hide public staff profiles and mark `Сегодня на смене`; Manager may mark today
  shift state under current conservative policy.
- Guest venue card shows `Сегодня работают` below main venue info with public fields only.
- Venue UX polish is complete locally: `Карточки сотрудников`, collapsed create form, compact
  cards, `Другое` requires `Название роли`, and raw User ID / Photo ref are not exposed.

Evidence:
- `V117__staff_profiles_today_shifts.sql`, `V118__staff_profiles_today_shifts.sql`
- `VenueStaffProfileRepository`, `VenueStaffRoutes`, `GuestVenueRoutes`
- `VenueRbac.kt`
- `VenueStaffRoutesTest`, `GuestVenueRoutesTest`, Mini App smoke coverage.

MVP дизайн:
- Keep Telegram account optional.
- Use `staff_profiles`: venue, `linked_user_id` nullable, display name, role label, subtype,
  opt-in guest visibility and safe audit fields.
- Use `staff_shifts` / `SHIFT_TODAY`: manual active/scheduled today state.
- Guest-visible list: `Сегодня работают` with public display name, role/custom role, bio/tags and
  safe placeholder/photo only.
- No tips payments in Phase 1.

Расширенный дизайн:
- Shift schedule, shift sign-up/swaps via Telegram, skills, profile photo upload/media picker,
  internal assignment, order assignment to hookah master and dedicated staff communication chat/forum
  topics remain future.

Технические изменения:
- Phase 1 staff profile/today-shift repository/routes and guest visible active profiles exist.
- Staff schedule, staff tip method/intent routes, photo upload and staff communication workflows are
  future and must not be mixed with guest order payment.

Миграции БД:
- `staff_profiles(id, venue_id, linked_user_id nullable, display_name, role_label, subtype, photo_ref, bio, tags, is_guest_visible, tips_enabled future, created_by_user_id, updated_by_user_id, published_at, disabled_at, audit fields)`.
- `staff_shifts(id, venue_id, staff_profile_id, shift_date, starts_at, ends_at, status, is_guest_visible, manually_marked_active, audit fields)`.

API/routes:
- `GET/POST/PATCH /api/venue/{venueId}/staff/profiles`
- `GET /api/guest/venue/{id}/today-staff`

Telegram bot changes:
- Owner staff profile setup optional.

Mini App changes:
- Venue staff profiles and guest "Сегодня работают".

Tests/smoke checks:
- Hidden profiles not returned.
- Username/contact not exposed.
- `Другое` requires custom role label.
- Guest `Сегодня работают` appears below main venue info.

Риски:
- Privacy and employee consent.
- Confusing account member vs display-only profile.
- Treating future tip intent as payment proof.

Рекомендованный приоритет: **P2/P3**.

Что делать не сейчас: scheduling/assignment before basic staff operations.

### 17. Booking/visit retention

Продуктовая цель: count real visits and use them for reminders, loyalty, feedback, preorder eligibility.

Текущее состояние в коде:
- Table sessions have ACTIVE/ENDED and cleanup worker.
- Orders can close.
- Bookings can become `SEATED` / `NO_SHOW` only from `CONFIRMED`; denied pending/changed/terminal arrival transitions must not create visits.
- Guest History Foundation MVP is staging-smoked: `BOOKING_SEATED` and closed-order visits are visible, non-seated booking statuses are filtered, legacy invalid rows are hidden, and same-real-visit booking/order signals merge instead of double-counting. Full `visit_count` product coverage remains partial/future.

Evidence:
- `TableSessionRepository`, `TableSessionCleanupWorker`
- `VenueOrdersRepository` close handling
- `GuestBookingRepository.BookingStatus`
- Booking seated/no-show and visit repository evidence is tracked in the canonical booking lifecycle docs; this historical section should not be used to reopen old seated/no-show claims.

Расхождение с концепцией:
- Spec has table_session and booking; Block 15 History foundation is now implemented, while repeat/feedback/loyalty/preorder still need product-specific use of visits.

MVP дизайн:
- Define visit as one of:
  - booking marked `SEATED`;
  - table_session with at least one delivered/closed order;
  - closed order with guest participant.
- Filter `CANCELED`, `NO_SHOW`, `EXPIRED`, `PENDING` and `CHANGED` bookings out of visit history.
- `visit_count` should be computed from valid visits later, not denormalized first.

Расширенный дизайн:
- Merge booking + table_session + order into one visit timeline.
- Retention cohorts and loyalty eligibility.

Технические изменения:
- Visit repository and idempotent creation.
- Hook from venue order close and booking seated.

Миграции БД:
- `visits(id, venue_id, user_id, booking_id, table_session_id, order_id, started_at, ended_at, source, created_at)`.
- Unique constraints by source ids to prevent duplicates.

API/routes:
- Guest history exists for current-user list/detail; venue retention endpoints remain future.

Telegram bot changes:
- Post-visit feedback trigger.

Mini App changes:
- Guest history exists; owner stats remain future.

Tests/smoke checks:
- Closing same order twice creates one visit.
- Booking no_show does not create visit.
- Multiple guests in shared tab counted correctly.
- Old closed order detail without discounts/options/notes opens safely.
- Foreign detail returns 404.

Риски:
- H2/PostgreSQL uniqueness fidelity is now closed; remaining schema follow-ups are narrower: PostgreSQL permits active `PERSONAL` tabs with `owner_user_id NULL`, shared-tab uniqueness is repository-idempotent rather than DB-enforced, and one active table session per table is handled by runtime locking rather than a DB uniqueness constraint.

Рекомендованный приоритет: core notification path is regression; personal subscriptions/unified notification history are **P2**.

Что делать не сейчас: loyalty/preorder rules until valid visit counting is explicitly designed beyond the current History foundation.

### 18. Что делать не сейчас

Делать сейчас:
- Keep support/tickets MVP, Guest Communication UX split, manual platform billing cockpit, renewal/courtesy, staff invite sharing, Mini App mutation, table-context exit, booking lifecycle, bill/bill-request parity, staff-chat activity card, notification reliability and multi-venue Telegram selector in regression smoke.
- Plan real acquiring provider or Telegram Stars as a separate provider-specific milestone, not as an extension of the manual/fake billing MVP.
- Use `docs/PLATFORM_COCKPIT.md` before opening Platform Mode tasks; keep onboarding requests, placements, analytics and lifecycle normalization as explicit Platform follow-ups.
- Use `docs/GROWTH_RETENTION.md` before opening Growth/retention tasks; keep current implementation versus MVP versus future status explicit.

Отложить до стабилизации post-platform-owner launch core:
- Visit_count, favorite menu items, recommendations/frequent items, repeat template, simple promotions, notifications and preorder eligibility until their own rules are stable. Guest History, Post-Visit Feedback and venue favorites are stable at their documented evidence levels.

Отложить до real provider billing/analytics:
- Catalog paid placement, promotion boosting, campaign budgets, impressions/clicks.

Не делать в MVP:
- Cashback/points/flexible loyalty rules engine.
- Full map/near-me ranking before coordinates and consent.
- Hookah master scheduling/assignment beyond simple display profile.
- Automated discount templates before simple promotion announcements.

## Итоговый roadmap

### P0 Core blockers

No current P0 is selected from this historical list. The original P0 order/session, CORS and staff-call items have been fixed or superseded by later code and smoke evidence; keep them in regression instead of reopening without a concrete regression.

### P1 Operational completeness

1. Real acquiring provider / Telegram Stars provider decision if pilot commercial payment requires online payment.
2. Booking lifecycle, reminder rollout, table-context exit, support/tickets MVP, Guest Communication UX split, bill/bill-request parity, staff-chat activity card, manual billing renewal/courtesy and multi-venue selector as regression smoke unless new gaps appear.
3. Platform cockpit follow-ups: onboarding requests, placements, analytics/risk indicators and lifecycle normalization after provider/support MVP boundaries stay stable.
4. Venue Mini App remaining profile setup parity through small backend-backed slices; public card/location basics are closed by M8a/M8b-Free and hours/exceptions are closed by M9b-M9b.3.
5. Unified staff notifications beyond the closed core if personal subscriptions/history are needed.
6. Owner description section defaults `Схема зала` and `Интерьер`; expose sections to guests.

### P2 Growth/retention

1. Order Session Tab Core Hardening before the next Growth runtime slice: one active order per `table_session_id`, tab-scoped order views and privacy regression.
2. Favorite menu items and recommendations/frequent items only as a separately scoped future block.
3. Repeat templates that require next table context; do not start directly after Favorites.
4. Simple venue promotions/announcements with terms, period, status and opt-in-safe notifications; do not start directly after Favorites.
5. Visit_count / owner retention summaries after valid visit counting is explicitly designed.
6. Catalog server search/filter by name/city/address/district.
7. Hookah master guest-visible display profile.
8. Promotion templates as non-executable templates.

### P3 Advanced monetization/ads/loyalty

1. Catalog paid placement.
2. Paid promotion boosting.
3. Preorder for regular guests.
4. Cashback/points/tier loyalty/flexible rules.
5. Venue network/group entity with consolidated stats and bulk roles.
6. Map/near-me ranking with geolocation, coordinates and consent.

## Просмотренные файлы и источники

- `docs/PRODUCT_SPEC.md`
- `docs/ORDER_SESSION_TAB_CORE.md`
- `docs/ANALYTICS_EVENTS.md`
- `docs/GROWTH_RETENTION.md`
- `docs/audit/PRODUCT_AUDIT_SUMMARY.md`
- `docs/audit/ROLE_GUEST.md`
- `docs/audit/ROLE_VENUE_OWNER.md`
- `docs/audit/ROLE_MANAGER.md`
- `docs/audit/ROLE_STAFF.md`
- `docs/audit/ROLE_PLATFORM_OWNER.md`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/Application.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/db/GuestBookingRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/bookings/VenueBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/db/GuestVenueRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVenueRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestOrderRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/db/TableSessionRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/TableSessionCleanupWorker.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/VenueStaffRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramKeyboards.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/StaffChatNotifier.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramOutboxEnqueuer.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramOutboxWorker.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/OrdersRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueAccessRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueInfoSectionsRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueInfoSectionMediaRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueSettingsRepository.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/StaffChatNotificationRepository.kt`
- `backend/app/src/main/resources/db/migration/postgresql/V1__init.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V11__venue_members.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V17__venue_staff_invites.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V26__telegram_outbox.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V32__bookings.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V46__venue_info_sections.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V48__venue_info_section_media.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V58__order_batch_item_discounts.sql`
- `backend/app/src/main/resources/db/migration/postgresql/V60__venue_settings.sql`
- `miniapp/src/screens/catalog.ts`
- `miniapp/src/screens/venueApp.ts`
- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/shared/state/cartStore.ts`

Созданный файл: `docs/audit/PRODUCT_IDEAS_REVIEW.md`.
