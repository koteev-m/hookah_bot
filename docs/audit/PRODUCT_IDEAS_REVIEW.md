# Product Ideas Review

Дата: 2026-04-28. Режим: read-only audit. Код, миграции и тесты не изменялись.

> Current correction as of 2026-06-19: this file remains a historical product-ideas audit. Booking-related rows were updated for M3/M7a/M7b: Venue Mini App booking queue/lifecycle exists, booking hold settings are CLOSED / staging smoke passed, `arrival_deadline_at` is a persisted booking snapshot, and Guest Mini App `Мои брони` is implemented / staging smoke target. Runtime booking reminders are still not implemented; M7c adaptive reminder policy is documented only.

## Executive summary

Из нового списка уже частично реализованы: базовые брони, Telegram `/my` для активных броней/заказов, Guest Mini App `Мои брони`, venue-side booking queue/lifecycle API, booking hold settings with persisted `arrival_deadline_at`, owner venue description sections, простой guest catalog, multi-venue membership в БД и Mini App venue selector, staff chat notifications для новых order batches, order close/table session cleanup как техническая основа visit retention.

Частично, но с заметными gap: booking lifecycle and Mini App surfaces are now much stronger, but runtime reminders, preorder, automatic expiry/no-show automation and broader retention remain later; каталог без server-side search/filter/map/geo; order history only partially exists; discount существует как ручной percent на item в счёте, но не как loyalty/promo system.

Отсутствует: reminder scheduler для броней, post-visit feedback/reviews, Yandex review link, paid placement, promotions/coupons/campaigns, promotion boosting, favorites/repeat templates, preorder, cashback/points/flexible loyalty rules, hookah master subrole/profile/shift schedule, network/group entity for venue chains.

Главные зависимости:
- P0 core: active order/table_session/tab correctness из `PRODUCT_AUDIT_SUMMARY.md` остаётся prerequisite для visit_count, feedback, repeat, preorder, loyalty.
- P1 ops: booking lifecycle and notifications need venue settings, scheduler/outbox, status events, Mini App screens.
- P2 growth: favorites/history/repeat/promotions/reviews need stable visit/order history.
- P3 monetization: catalog paid placement and promotion boosting need platform billing/moderation/analytics and clear advertising labels.

## Таблица статусов

| Идея | Статус | Evidence из кода | Что уже есть | Чего нет | MVP реализация | Риски | Приоритет |
|---|---|---|---|---|---|---|---|
| 1. Брони гостя и жизненный цикл | PARTIAL | `V32__bookings.sql`; `GuestBookingRepository.BookingStatus`; `GuestBookingRoutes`; `VenueBookingRoutes`; `VenueSettingsRepository`; `TelegramBotRouter.showMyOrdersAndBookings`; `guestBookings.ts`; `venueBookings.ts` | Create/update/cancel/list, venue confirm/change/cancel/arrival/no-show, Telegram `/my`, Guest Mini App `Мои брони`, Venue Mini App queue, persisted `arrival_deadline_at`, hold setting | Runtime reminders, preorder, broader automatic expiry/no-show policy | Keep M3/M7a/M7b in regression; implement M7c reminders separately | Label/timezone drift across Bot/Mini App if DTO parity regresses | P1 |
| 2. Напоминания о бронях | MISSING runtime / DOCUMENTED policy | `Application.kt` starts subscription billing, table session cleanup, telegram workers; no booking reminder worker found; M7c policy in roadmap/spec docs | Immediate messages on booking create/update/cancel/venue status; adaptive reminder policy documented | Scheduler, reminder table, callback runtime, exactly-once reminder delivery | M7c: one adaptive transactional reminder for confirmed/changed bookings using outbox and venue-local quiet window | Спам, timezone/quiet hours, duplicate sends | P1 |
| 3. Поствизитный feedback и отзывы | MISSING | `VenueOrdersRepository` can close orders; `TableSessionRepository` can end sessions; no review/feedback files/tables found | Technical close signals exist | Review/rating tables/routes/screens, post-visit trigger, Yandex review link | Send next-day feedback request after closed visit/order | Wrong timing, ночные сообщения, privacy | P2 |
| 4. Owner venue description sections | PARTIAL | `VenueInfoSectionsRepository.defaultSections`; `V46__venue_info_sections.sql`; `V48__venue_info_section_media.sql`; Telegram owner description callbacks | Default: about/rules/cork_fee/faq/menu; custom sections; image/pdf media in Telegram | Default hall plan/interior; guest Mini App display | Add templates `hall_plan`, `interior`, expose sections in guest venue API | Media storage uses Telegram file_id, Mini App rendering needs file access strategy | P1/P2 |
| 5. Guest catalog search/filter/map | PARTIAL | `GuestVenueRepository.listCatalogVenues` `ORDER BY v.id ASC`; `catalog.ts` local name/city search | Published venues, city/address, local Mini App search | Server search, address/district filters, open now, price, coordinates, map | Backend `q/city/district`, search name/city/address | Geo consent, distance accuracy, no coords | P2 |
| 6. Catalog promotion / paid placement | MISSING | No promo/campaign/placement files found; billing exists separately | Subscription/billing foundation | Placement/campaign/budget/status/labeling | Platform-managed `catalog_placements` with start/end/priority and label | Abuse, undisclosed ads, unfair ranking | P3 |
| 7. Акции для гостей | PLACEHOLDER | `TelegramKeyboards.mainMenu` has `🎁 Акции`; `TelegramBotRouter.showPromotions` returns "следующий шаг" | Visible button | Promotions tables/routes/screens and owner flow | `venue_promotions` list active by venue/catalog | UI обещает несуществующую функцию | P2 |
| 8. Продвижение акций | MISSING | No promotion/campaign models found | Billing infra only | Paid boost for promotions, moderation | Add promotion placement entity linked to promotion | Fraud/moderation/billing disputes | P3 |
| 9. Мульти-venue owner / сеть | PARTIAL | `venue_members` PK `(venue_id,user_id)`; `VenueAccessRepository.listVenueMemberships`; `venueApp.ts` selector; Telegram `resolvePrimaryVenueBotAccess` picks first by role | One user can belong to many venues; Mini App selector | Telegram venue selector; network/group entity; consolidated stats | Telegram "Выбрать заведение"; improve Mini App labels | Wrong venue actions in Telegram | P1 for selector, P3 for networks |
| 10. Staff/order notifications | PARTIAL | `StaffChatNotifier.notifyNewBatch`; `TelegramOutboxWorker`; `GuestBookingRoutes.notifyVenueStaffAboutBooking`; bot `notifyStaffChat` | Staff chat new order/reorder, bot staff calls, booking create/update/cancel messages | Personal staff notifications, unified event policy, Mini App staff call notification broken/absent | Always-on staff chat + optional personal bot subscriptions | Telegram sound constraints, disabled chat, missing staff chat link | P1 |
| 11. История заказов гостя | PARTIAL | `OrdersRepository.listActiveOrderSummariesForUser` filters `o.status='ACTIVE'`; `/my` active only | Active orders in Telegram; order tables retain data | Past order history, repeat order, favorites | `/api/guest/orders/history` from closed orders by user/tab | Current user/order relation depends on idempotency/batches | P2 |
| 12. Избранное / любимые / repeat | MISSING | No favorites files/tables found; cart draft only localStorage in `cartStore.ts` | Local cart draft per table token | Favorite venue/item, repeat templates, frequent items | favorite items + repeat last order only in active table context | Reordering unavailable/stopped items | P2 |
| 13. Предзаказ для постоянных | MISSING | Booking comment mentions "предзаказ"; no preorder model found | Booking exists; orders exist | Preorder settings, eligibility, cutoff, staff queue | `venue_preorder_settings`, `preorders` linked to confirmed booking | Requires reliable visit_count and booking lifecycle | P3 after visit history |
| 14. Loyalty/cashback/discounts | PARTIAL | `V58__order_batch_item_discounts.sql`; `VenueOrdersRepository.setBatchItemDiscountPercent`; Telegram bill discount flow | Manual item discount percent in Telegram bill | Promo codes, cashback, points, tier discounts, category discounts, guest-specific discounts | Start with simple venue promo discount codes or manual permanent guest note later | Financial correctness and abuse | P3 |
| 15. Promotion templates | MISSING | No promotion template model found | None | Template catalog for common promos | Static templates in owner UI creating `venue_promotions` | Template flexibility vs complexity | P2/P3 |
| 16. Hookah master role/profile | MISSING | `venue_members` roles only OWNER/ADMIN/MANAGER/STAFF; no staff_profile/shift files found | Owner can add staff | Hookah master subtype/profile/shift/guest-visible today list | `staff_profiles`, subtype `HOOKAH_MASTER`, active today display name | Privacy: do not expose Telegram username | P2/P3 |
| 17. Booking/visit retention | PARTIAL | `TableSessionRepository.closeExpiredSessions`; `VenueOrdersRepository` close; booking statuses no seated/no_show | Technical table/order close | Visit entity, seated status, visit_count | Create visits from booking seated or table_session/order close | Double counting visits | P0/P1 dependency |
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
- Hold duration setting and guest-facing `Держим до HH:mm` now exist; runtime reminders and broader automation remain missing.

MVP дизайн:
- Keep current Bot `/my`, Guest Mini App `Мои брони`, Venue Mini App queue and hold settings in regression.
- Implement M7c adaptive transactional reminders as a separate runtime slice.
- Do not broaden booking settings into reminders/quiet hours/preorder in the same slice.

Расширенный дизайн:
- Настройки per day/venue, автоматический no-show после grace period.
- Seat booking creates/links `visit` and optionally table_session.
- Booking timeline/audit and analytics `booking_status_changed`.

Технические изменения:
- `BookingStatus`, `GuestBookingRepository`, `VenueBookingRoutes`, Telegram booking actions.
- Reminder scheduler/outbox repository, if M7c is implemented.
- Optional future preorder/visit retention links after booking reminders and visit_count are stable.

Миграции БД:
- No new migration for M7b.
- Future M7c reminder tables/outbox scheduling should be separate from hold settings.

API/routes:
- `GET /api/guest/bookings` account-level active/upcoming list.
- Existing guest update/cancel/confirm routes stay lifecycle source of truth.

Telegram bot changes:
- `/my` remains Bot parity reference for public booking label and deadline.
- Future M7c reminder buttons must not overwrite venue confirmation status.

Mini App changes:
- Guest `Мои брони` screen is implemented / staging smoke target.
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
- В `Application.kt` запускаются `SubscriptionBillingJob`, `TableSessionCleanupWorker`, Telegram webhook/outbox workers. Booking reminder worker не найден.
- `TelegramOutboxWorker` уже умеет retries/backoff for outbound messages.

Evidence:
- `GuestBookingRoutes.notifyVenueStaffAboutBooking`
- `VenueBookingRoutes` sends messages to `booking.userId`
- `Application.kt` worker startup
- `TelegramOutboxEnqueuer`, `TelegramOutboxWorker`, `V26__telegram_outbox.sql`

Расхождение с концепцией:
- Spec требует booking status notifications, но scheduled reminders and pre-visit confirm/cancel/reschedule отсутствуют.

MVP дизайн:
- Worker every 1-5 minutes finds confirmed/changed bookings requiring reminder.
- One reminder at configurable `N` hours before visit, default 3 hours.
- Message: venue, time, hold until, buttons `Подтвердить`, `Отменить`, `Перенести`.
- Store sent reminders to prevent duplicates.

Расширенный дизайн:
- Multiple reminders: day before + same day.
- Quiet hours and venue timezone.
- Auto-expire/no-show after hold.

Технические изменения:
- `BookingReminderWorker`, repository method `listDueReminders`.
- Use existing outbox for delivery.
- Add Telegram callback handlers.

Миграции БД:
- `booking_notifications(id, booking_id, kind, scheduled_for, sent_at, status, attempts)` or `booking_reminder_sent_at` columns.
- `venue_booking_settings.reminder_hours_before`, `quiet_start`, `quiet_end`.

API/routes:
- Venue settings route for reminder timing.

Telegram bot changes:
- Callback data `booking_reminder_confirm`, `booking_reminder_cancel`, `booking_reminder_reschedule`.

Mini App changes:
- Optional settings UI; not required for first MVP if default is global.

Tests/smoke checks:
- Reminder sent once.
- Reschedule invalidates old reminder.
- Quiet hours shift send time.

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
- Telegram bot resolves a single primary venue: role priority OWNER > MANAGER > STAFF, then first membership by `venue_id`.
- Manager/staff assigned per venue via `venue_members`.
- No network/group entity.

Evidence:
- `VenueAccessRepository.listVenueMemberships`
- `VenueRoutes.get("/me")`
- `miniapp/src/screens/venueApp.ts`
- `TelegramBotRouter.resolvePrimaryVenueBotAccess`
- `V11__venue_members.sql`

Расхождение с концепцией:
- Multi-tenant exists; chain/network management is beyond spec v1.
- Telegram "first venue" problem exists.

MVP дизайн:
- Telegram: if multiple memberships, show "Выберите заведение" before owner/manager/staff menu.
- Persist selected venue in `telegram_chat_context` or dialog state.
- Mini App: show venue names, not only `Venue #id`.

Расширенный дизайн:
- `venue_networks`, `network_members`, bulk roles, consolidated stats, shared menu templates.

Технические изменения:
- Telegram venue selector keyboards and selected venue context.
- Extend `/api/venue/me` with venue name/status.

Миграции БД:
- MVP none.
- Extended: `venue_networks`, `venue_network_members`, `venue_network_roles`.

API/routes:
- `GET /api/venue/me` add names.
- Future `/api/network/...`.

Telegram bot changes:
- Role-aware menu becomes selected-venue-aware.

Mini App changes:
- Better selector labels and network filters.

Tests/smoke checks:
- Owner with two venues can choose each in Telegram.
- Staff actions apply to selected venue.

Риски:
- Existing flows parse venueId in callbacks; selector must not break callbacks.

Рекомендованный приоритет: **P1** for Telegram selector, **P3** for chain entity.

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
- Mini App staff call broken payload and no notifier.
- Booking notifications are direct staff chat messages, not unified notifier/dedupe.

MVP дизайн:
- Make staff chat notification for order/reorder always-on by default.
- Add Mini App staff call notification.
- Add personal opt-in notifications for staff via bot only after staff starts bot.

Расширенный дизайн:
- Event bus: `venue_events` -> notification policies.
- Per role and per event settings.
- SLA alerts if unaccepted after N minutes.

Технические изменения:
- Unified notification service for order/call/booking/cancellation.
- Fix Mini App staff call payload and route notification.
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
- Roles: `OWNER`, `ADMIN`, `MANAGER`, `STAFF`.
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
- Existing table/order session scoping bug can corrupt visits.

Рекомендованный приоритет: **P0/P1 dependency**; design now, implement after order/session fix.

Что делать не сейчас: loyalty/preorder rules until visit_count is reliable.

### 18. Что делать не сейчас

Делать сейчас:
- Fix P0 core blockers from previous audit: order per `table_session`, tab-scoped active order, CORS mutation methods.
- Booking lifecycle MVP: hold minutes, statuses, active visibility.
- Notification reliability for orders/calls/bookings.
- Multi-venue Telegram selector if owner/manager operates multiple venues.

Отложить до стабилизации core order/session/tab:
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

1. Active order per `table_session`, not only `table_id`.
2. Guest active order endpoints scoped by `tableSessionId` and `tabId`.
3. CORS methods for Mini App mutations.
4. Mini App staff call payload/notification fix.
5. Fallback chat order payload contract.

### P1 Operational completeness

1. Booking lifecycle: `EXPIRED/NO_SHOW/SEATED`, hold minutes, active visibility until deadline.
2. Booking reminders worker with confirm/cancel/reschedule.
3. Venue booking queue in Mini App.
4. Unified staff notifications for orders/reorders/calls/bookings/cancellations.
5. Telegram multi-venue selector.
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
