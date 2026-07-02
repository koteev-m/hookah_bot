# Product Ideas Review

Дата: 2026-04-28. Режим: read-only audit. Код, миграции и тесты не изменялись.

> Current correction as of 2026-07-02: this file remains a historical product-ideas audit. Booking-related rows were updated for M3/M7a/M7b/M7c: Venue Mini App booking queue/lifecycle exists, booking hold settings are CLOSED / staging smoke passed, `arrival_deadline_at` is a persisted booking snapshot, Guest Mini App `Мои брони` is implemented with staging visual parity for Bot `/my` label/time/deadline, and M7c adaptive reminders passed one controlled real Telegram staging smoke while remaining disabled by default for rollout. M8a/M8b-Free public profile/card basics are also CLOSED / staging smoke passed: Venue Mini App can edit provider-free public location/contact/description. M9b/M9b.1/M9b.2/M9b.3 schedule parity is CLOSED / staging smoke passed: Venue Mini App can edit weekly hours and inclusive date-exception ranges with optional guest-facing reason/comment, and guest/Bot booking rejection copy is human. Platform Owner Invite / ADMIN Semantics Hardening and Platform Venue OWNER Revocation are CLOSED / staging smoke passed: config parity, no Platform Mini App `ADMIN` assignment, usable owner invite deep link/copy, owner invite create/accept audit, active OWNER membership list, last-owner-protected revoke, membership-based access loss and `VENUE_OWNER_REVOKE` audit are implemented. H2/PostgreSQL active-order + personal-tab uniqueness fidelity is CLOSED / validation passed: H2 now mirrors PostgreSQL active-order and active personal-tab uniqueness predicates, PostgreSQL already had the intended constraints, no production PostgreSQL migration/runtime change/staging deploy was required, and commit `a4a2d71` is on `origin/main`. Guest Table Context UX Cleanup / Feature-gated Extension Module and Guest Table Session Exit / Expiry UX are CLOSED / staging smoke passed: table-context route/copy/booking actions are hidden, extension is active-order gated, `Завершить визит` is user-scoped through `guest_table_session_exits`, shared `table_sessions` are not closed for all guests, and the staging 415 JSON `Content-Type` bug is fixed. Guest Bill / Display-Number / Full-Bill Parity, Guest Bill Request / Payment Method UX, Staff Chat Noise Reduction / Table Activity Card and hookah preparation placeholder polish are also CLOSED / staging smoke passed. Info/media editing, guest preview, primary/legal/billing owner transfer helper, platform billing cockpit, support and analytics remain later. M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed. Current code also implements Telegram multi-venue selected context; old "Telegram selector missing" notes below are historical, while chain/network entities remain future work.

## Executive summary

Из нового списка уже частично реализованы: базовые брони, Telegram `/my` для активных броней/заказов, Guest Mini App `Мои брони`, venue-side booking queue/lifecycle API, booking hold settings with persisted `arrival_deadline_at`, owner venue description sections, простой guest catalog, multi-venue membership в БД, Mini App venue selector and Telegram selected-venue context, staff chat notifications для новых order batches, order close/table session cleanup как техническая основа visit retention.

Частично, но с заметными gap: booking lifecycle, Mini App surfaces and M7c core reminder flow are now much stronger, but preorder, automatic expiry/no-show automation, broader reminder rollout and broader retention remain later; каталог без server-side search/filter/map/geo; order history only partially exists; discount существует как ручной percent на item в счёте, но не как loyalty/promo system.

Отсутствует: post-visit feedback/reviews, Yandex review link, paid placement, promotions/coupons/campaigns, promotion boosting, favorites/repeat templates, preorder, cashback/points/flexible loyalty rules, hookah master subrole/profile/shift schedule, network/group entity for venue chains. M7c reminder rollout remains opt-in disabled, not absent.

Главные зависимости:
- Core order/session/tab scoping is no longer the old April P0; current code scopes active orders by table session/tab, PostgreSQL has the intended active-order and active personal-tab uniqueness constraints, and H2 now mirrors those predicates for local/test schema fidelity.
- P1 ops: table-context exit, guest/staff bill display parity, bill request/payment method UX and staff-chat activity card are now staging-closed; platform billing cockpit is the next launch-commercial gap before broad growth.
- P2 growth: favorites/history/repeat/promotions/reviews need stable visit/order history.
- P3 monetization: catalog paid placement and promotion boosting need platform billing/moderation/analytics and clear advertising labels.

## Таблица статусов

| Идея | Статус | Evidence из кода | Что уже есть | Чего нет | MVP реализация | Риски | Приоритет |
|---|---|---|---|---|---|---|---|
| 1. Брони гостя и жизненный цикл | PARTIAL | `V32__bookings.sql`; `GuestBookingRepository.BookingStatus`; `GuestBookingRoutes`; `VenueBookingRoutes`; `VenueSettingsRepository`; `TelegramBotRouter.showMyOrdersAndBookings`; `guestBookings.ts`; `venueBookings.ts` | Create/update/cancel/list, venue confirm/change/cancel/arrival/no-show, Telegram `/my`, Guest Mini App `Мои брони`, Venue Mini App queue, persisted `arrival_deadline_at`, hold setting, M7c reminder anchors and attendance intent | Preorder, broader automatic expiry/no-show policy, broader rollout of opt-in reminders, real two-account M7b isolation evidence | Keep M3/M7a/M7b/M7c in regression | Label/timezone drift across Bot/Mini App if DTO parity regresses | P1 |
| 2. Напоминания о бронях | M7c IMPLEMENTED / CORE SMOKE PASSED | `BookingReminderWorker`, `booking_reminders`, `GuestBookingRepository.scheduleRemindersForBooking`, `BookingReminderWorkerConfig`; `V109__m7c_booking_reminders.sql`; Mini App booking screens | Immediate messages on booking create/update/cancel/venue status; M7c adaptive scheduler; policy-version legacy isolation; outbox dedupe; final callbacks; Guest/Venue attendance indicators; disabled-by-default worker; one controlled real Telegram smoke passed | Broader rollout approval and regression smoke before enabling beyond controlled test | M7c: one adaptive transactional reminder for confirmed/changed bookings using outbox and venue-local quiet window | Спам, timezone/quiet hours, duplicate sends, legacy rows sent under old policy if feature flag/query regress | P1 |
| 3. Поствизитный feedback и отзывы | MISSING | `VenueOrdersRepository` can close orders; `TableSessionRepository` can end sessions; no review/feedback files/tables found | Technical close signals exist | Review/rating tables/routes/screens, post-visit trigger, Yandex review link | Send next-day feedback request after closed visit/order | Wrong timing, ночные сообщения, privacy | P2 |
| 4. Owner venue description sections | PARTIAL | `VenueInfoSectionsRepository.defaultSections`; `V46__venue_info_sections.sql`; `V48__venue_info_section_media.sql`; Telegram owner description callbacks | Default: about/rules/cork_fee/faq/menu; custom sections; image/pdf media in Telegram | Default hall plan/interior; guest Mini App display | Add templates `hall_plan`, `interior`, expose sections in guest venue API | Media storage uses Telegram file_id, Mini App rendering needs file access strategy | P1/P2 |
| 5. Guest catalog search/filter/map | PARTIAL | `GuestVenueRepository.listCatalogVenues` `ORDER BY v.id ASC`; `catalog.ts` local name/city search | Published venues, city/address, local Mini App search | Server search, address/district filters, open now, price, coordinates, map | Backend `q/city/district`, search name/city/address | Geo consent, distance accuracy, no coords | P2 |
| 6. Catalog promotion / paid placement | MISSING | No promo/campaign/placement files found; billing exists separately | Subscription/billing foundation | Placement/campaign/budget/status/labeling | Platform-managed `catalog_placements` with start/end/priority and label | Abuse, undisclosed ads, unfair ranking | P3 |
| 7. Акции для гостей | PLACEHOLDER | `TelegramKeyboards.mainMenu` has `🎁 Акции`; `TelegramBotRouter.showPromotions` returns "следующий шаг" | Visible button | Promotions tables/routes/screens and owner flow | `venue_promotions` list active by venue/catalog | UI обещает несуществующую функцию | P2 |
| 8. Продвижение акций | MISSING | No promotion/campaign models found | Billing infra only | Paid boost for promotions, moderation | Add promotion placement entity linked to promotion | Fraud/moderation/billing disputes | P3 |
| 9. Мульти-venue owner / сеть | PARTIAL | `venue_members` PK `(venue_id,user_id)`; `VenueAccessRepository.listVenueMemberships`; `venueApp.ts` selector; `telegram_venue_context`; Telegram selector callbacks/tests | One user can belong to many venues; Mini App selector; Telegram selected-venue context | Network/group entity; consolidated stats; runtime smoke for multi-venue roles | Keep selector in regression; later design chain/network entity | Wrong venue actions if selected context regresses | P3 for networks; selector is implemented/regression |
| 10. Staff/order notifications | PARTIAL / CORE CLOSED | `StaffChatNotifier.notifyNewBatch`; `TelegramOutboxWorker`; `GuestBookingRoutes.notifyVenueStaffAboutBooking`; bot `notifyStaffChat`; M5/M6 staging smoke | Staff chat new order/reorder, bot and Mini App staff calls lifecycle, booking create/update/cancel messages, staff-chat diagnostics/unlink | Personal staff notifications, unified event policy, richer delivery history | Keep M5/M6 in regression; add personal subscriptions only after core smoke stays green | Telegram sound constraints, disabled chat, missing staff chat link | P2 after launch core |
| 11. История заказов гостя | PARTIAL | `OrdersRepository.listActiveOrderSummariesForUser` filters `o.status='ACTIVE'`; `/my` active only | Active orders in Telegram; order tables retain data | Past order history, repeat order, favorites | `/api/guest/orders/history` from closed orders by user/tab | Current user/order relation depends on idempotency/batches | P2 |
| 12. Избранное / любимые / repeat | MISSING | No favorites files/tables found; cart draft only localStorage in `cartStore.ts` | Local cart draft per table token | Favorite venue/item, repeat templates, frequent items | favorite items + repeat last order only in active table context | Reordering unavailable/stopped items | P2 |
| 13. Предзаказ для постоянных | MISSING | Booking comment mentions "предзаказ"; no preorder model found | Booking exists; orders exist | Preorder settings, eligibility, cutoff, staff queue | `venue_preorder_settings`, `preorders` linked to confirmed booking | Requires reliable visit_count and booking lifecycle | P3 after visit history |
| 14. Loyalty/cashback/discounts | PARTIAL | `V58__order_batch_item_discounts.sql`; `VenueOrdersRepository.setBatchItemDiscountPercent`; Telegram bill discount flow | Manual item discount percent in Telegram bill | Promo codes, cashback, points, tier discounts, category discounts, guest-specific discounts | Start with simple venue promo discount codes or manual permanent guest note later | Financial correctness and abuse | P3 |
| 15. Promotion templates | MISSING | No promotion template model found | None | Template catalog for common promos | Static templates in owner UI creating `venue_promotions` | Template flexibility vs complexity | P2/P3 |
| 16. Hookah master role/profile | MISSING | `venue_members` roles only OWNER/ADMIN/MANAGER/STAFF; no staff_profile/shift files found | Owner can add staff | Hookah master subtype/profile/shift/guest-visible today list | `staff_profiles`, subtype `HOOKAH_MASTER`, active today display name | Privacy: do not expose Telegram username | P2/P3 |
| 17. Booking/visit retention | PARTIAL | `TableSessionRepository.closeExpiredSessions`; `guest_table_session_exits`; `VenueOrdersRepository` close; booking statuses no seated/no_show | Technical table/order close, shared table-session TTL cleanup, user-scoped guest exit marker | Visit entity, seated status, visit_count, shared physical table-session close policy after all bills closed | Create visits from booking seated or table_session/order close | Double counting visits; one guest exit must not count as physical table-session close | P1/P2 dependency |
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

Что делать не сейчас: budgets/auction/auto billing before platform analytics and billing cockpit.

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
- Booking notifications are direct staff chat messages, not unified notifier/dedupe.

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
- `/my` shows active orders and active bookings.
- `OrdersRepository.listActiveOrderSummariesForUser` filters `o.status = 'ACTIVE'`.
- Order data remains in `orders/order_batches/order_batch_items`, but no guest history endpoint/screen found.
- User-order relation for active list uses `guest_batch_idempotency`, not a dedicated guest_order_membership/history model.

Evidence:
- `TelegramBotRouter.showMyOrdersAndBookings`
- `OrdersRepository.listActiveOrderSummariesForUser`
- `miniapp/src/screens/guestApp.ts` has routes catalog/venue/cart/order only.

Расхождение с концепцией:
- Spec Block 15 requires visit history and repeat template.

MVP дизайн:
- Guest history endpoint returns closed orders where user has batches/tab membership.
- Show last 10 orders with venue/date/items/total.
- Repeat only available when guest is in active table context and items are still available.

Расширенный дизайн:
- Visit timeline combining bookings, table sessions, orders, feedback.

Технические изменения:
- Repository method for closed order summaries by user.
- Mini App account/history screen.
- Telegram `/history`.

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
- Canceled/rejected items not repeated.

Риски:
- Current order/session/tab scoping issues can leak or mix history.

Рекомендованный приоритет: **P2**, after P0 scoping.

Что делать не сейчас: analytics-heavy personalization before history is correct.

### 12. Избранное / любимые позиции / быстрый повтор

Продуктовая цель: reduce guest ordering friction on repeat visits.

Текущее состояние в коде:
- No favorites tables/routes/screens found.
- Cart draft persists only local current table token in `miniapp/src/shared/state/cartStore.ts`.
- No frequent-items aggregation.

Evidence:
- `cartStore.ts` localStorage draft prefix `hookah_guest_cart_draft:`.
- `rg` found no product favorite/favourite files.

Расхождение с концепцией:
- Spec Block 15 requires favorites and repeat template.

MVP дизайн:
- Favorite menu item and favorite venue.
- Repeat last order into cart when guest has table context.
- "Часто берёте" can be computed from order history later.

Расширенный дизайн:
- Cross-venue recommendations, flavor preferences, smart bundles.

Технические изменения:
- Favorites repository/routes.
- Repeat service filters unavailable items.

Миграции БД:
- `guest_favorite_venues(user_id, venue_id, created_at)`.
- `guest_favorite_items(user_id, venue_id, menu_item_id, created_at)`.
- Optional `guest_repeat_templates`.

API/routes:
- `POST/DELETE /api/guest/favorites/venues/{id}`
- `POST/DELETE /api/guest/favorites/items/{id}`
- `POST /api/guest/repeat-last`.

Telegram bot changes:
- Inline favorite/repeat buttons in menu/order.

Mini App changes:
- Favorite icons and repeat block in cart/table context.

Tests/smoke checks:
- Cannot repeat unavailable item.
- Favorites hidden if venue suspended.

Риски:
- Repeat without active table context could imply preorder accidentally.

Рекомендованный приоритет: **P2**.

Что делать не сейчас: favorites before order history and availability handling.

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

Продуктовая цель: show guests who is working today without exposing contacts.

Текущее состояние в коде:
- Roles: `OWNER`, `ADMIN`, `MANAGER`, `STAFF`; current runtime maps `ADMIN` as a legacy alias to `MANAGER`, not a separate Venue Admin model.
- Spec allows `staff_waiter`, `staff_hookah` or generic staff, but code uses generic staff.
- No `staff_profile`, `subrole`, `shift` tables/files found.
- Owner can add hookah master as `STAFF`, but cannot distinguish/display them as hookah master.

Evidence:
- `V11__venue_members.sql`, `V17__venue_staff_invites.sql`
- `VenueStaffRepository`, `VenueStaffRoutes`
- `VenueRbac.kt`
- `ROLE_STAFF.md` notes no staff specialization.

MVP дизайн:
- Keep Telegram account optional.
- Add `staff_profiles`: venue, user_id nullable, display_name, subtype `HOOKAH_MASTER`, is_guest_visible.
- Add active shift for today manually: `active_today`.
- Guest-visible list: "Сегодня работают" with display names only.

Расширенный дизайн:
- Shift schedule, skills, photos, internal assignment, order assignment to hookah master.

Технические изменения:
- Staff profile repository/routes.
- Guest venue API includes visible active profiles.

Миграции БД:
- `staff_profiles(id, venue_id, user_id, display_name, subtype, is_guest_visible, created_at)`.
- `staff_shifts(id, venue_id, staff_profile_id, starts_at, ends_at, status)`.

API/routes:
- `GET/POST/PATCH /api/venue/{venueId}/staff-profiles`
- `GET /api/guest/venue/{id}/today-staff`

Telegram bot changes:
- Owner staff profile setup optional.

Mini App changes:
- Venue staff profiles and guest "Сегодня работают".

Tests/smoke checks:
- Hidden profiles not returned.
- Username/contact not exposed.

Риски:
- Privacy and employee consent.
- Confusing account member vs display-only profile.

Рекомендованный приоритет: **P2/P3**.

Что делать не сейчас: scheduling/assignment before basic staff operations.

### 17. Booking/visit retention

Продуктовая цель: count real visits and use them for reminders, loyalty, feedback, preorder eligibility.

Текущее состояние в коде:
- Table sessions have ACTIVE/ENDED and cleanup worker.
- Orders can close.
- Bookings cannot become seated/no_show.
- No visit entity or visit_count.

Evidence:
- `TableSessionRepository`, `TableSessionCleanupWorker`
- `VenueOrdersRepository` close handling
- `GuestBookingRepository.BookingStatus`
- No `visits` migration/files found.

Расхождение с концепцией:
- Spec has table_session and booking, but not explicit visit entity; Block 15 needs history/retention.

MVP дизайн:
- Define visit as one of:
  - booking marked `SEATED`;
  - table_session with at least one delivered/closed order;
  - closed order with guest participant.
- Add `visits` table and update it from order close/booking seated.
- `visit_count` computed from visits, not denormalized first.

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
- Guest history and venue retention endpoints.

Telegram bot changes:
- Post-visit feedback trigger.

Mini App changes:
- Guest history and owner stats.

Tests/smoke checks:
- Closing same order twice creates one visit.
- Booking no_show does not create visit.
- Multiple guests in shared tab counted correctly.

Риски:
- H2/PostgreSQL uniqueness fidelity is now closed; remaining schema follow-ups are narrower: PostgreSQL permits active `PERSONAL` tabs with `owner_user_id NULL`, shared-tab uniqueness is repository-idempotent rather than DB-enforced, and one active table session per table is handled by runtime locking rather than a DB uniqueness constraint.

Рекомендованный приоритет: core notification path is regression; personal subscriptions/unified notification history are **P2**.

Что делать не сейчас: loyalty/preorder rules until visit_count is reliable.

### 18. Что делать не сейчас

Делать сейчас:
- Platform Billing Cockpit / Owner Payment UX now that guest/staff money display, bill request/payment method UX and staff-chat order activity are staging-closed.
- Keep Mini App mutation, table-context exit, booking lifecycle, bill/bill-request parity, staff-chat activity card, notification reliability and multi-venue Telegram selector in regression smoke.
- Support/tickets MVP after billing cockpit unless pilot support intake becomes a concrete blocker.

Отложить до стабилизации post-platform-owner launch core:
- Visit_count, guest order history, repeat order, favorites, feedback trigger, preorder eligibility.

Отложить до platform billing/analytics:
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

1. Platform Billing Cockpit / Owner Payment UX.
2. Support/tickets MVP beyond booking threads if pilot operations need general support routing.
3. Booking lifecycle, reminder rollout, table-context exit, bill/bill-request parity, staff-chat activity card and multi-venue selector as regression smoke unless new gaps appear.
4. Venue Mini App remaining profile setup parity through small backend-backed slices; public card/location basics are closed by M8a/M8b-Free and hours/exceptions are closed by M9b-M9b.3.
5. Unified staff notifications beyond the closed core if personal subscriptions/history are needed.
6. Owner description section defaults `Схема зала` and `Интерьер`; expose sections to guests.

### P2 Growth/retention

1. Guest order history based on reliable visits/orders.
2. Favorites and repeat last order.
3. Simple venue promotions/announcements.
4. Post-visit feedback and Yandex review link.
5. Catalog server search/filter by name/city/address/district.
6. Hookah master guest-visible display profile.
7. Promotion templates as non-executable templates.

### P3 Advanced monetization/ads/loyalty

1. Catalog paid placement.
2. Paid promotion boosting.
3. Preorder for regular guests.
4. Cashback/points/tier loyalty/flexible rules.
5. Venue network/group entity with consolidated stats and bulk roles.
6. Map/near-me ranking with geolocation, coordinates and consent.

## Просмотренные файлы и источники

- `docs/PRODUCT_SPEC.md`
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
