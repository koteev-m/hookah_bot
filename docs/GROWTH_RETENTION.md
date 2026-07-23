# Guest Growth And Retention Model

Дата актуализации: 2026-07-23.

Статус: **current product reference / SPEC UPDATED**. Runtime-фичи growth/retention не считаются release-ready, пока для них нет требуемого CI/staging evidence. Guest visit/order history foundation, Post-Visit Feedback MVP and Guest Favorites Phase 1 are **DONE / MVP / STAGING-SMOKE-PASSED**. Repeat as Template Phase 1 is **DONE / MVP / LOCAL SMOKE PASSED** and still awaits CI/staging evidence; broader retention loops remain partial/future. Этот документ описывает целевую модель, MVP-границы, зависимости и privacy rules для гостевого удержания.

## Core Rule

Growth не должен превращать QR-меню в спам-канал. Retention-фичи используют только подтверждённый guest context, не создают заказ без active table context and selected tab, не обходят stop-list/menu availability and do not send marketing notifications without opt-in.

Transactional flows remain separate:
- `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` are governed by `docs/COMMUNICATION_MODEL.md`.
- Booking lifecycle, hold/deadline, no-show/seated and reminder semantics are governed by `docs/BOOKING_LIFECYCLE.md`.
- Order/session/tab semantics for history, repeat and feedback dependencies are governed by `docs/ORDER_SESSION_TAB_CORE.md`.
- Growth analytics events and KPI formulas are governed by `docs/ANALYTICS_EVENTS.md`.
- Staff public profiles, today shift visibility and future staff-tip boundaries are governed by `docs/STAFF_PROFILES_SHIFTS_TIPS.md`.
- Booking reminders are transactional booking operations, not growth marketing.
- Staff-chat is operational and must not receive marketing/growth events unless a separate operational event already exists.

## Current Implementation

Current implementation is **partial**:
- Guest visit/order history foundation is **DONE / MVP / staging-smoke-passed**: `/api/guest/visits` and `/api/guest/visits/{visitId}` are scoped to the current user, booking `SEATED` and closed-order signals create/merge visits, non-seated booking statuses and bare table-session cleanup do not create visits, and closed-order details show only the guest's own billable batches/tabs.
- Post-visit feedback MVP and its smoke-fix are **DONE / MVP / staging-smoke-passed**: guests submit one rating `1..5`, optional fixed tags and optional comment from their own visible completed History detail only. Venue Owner/Manager read the own-venue aggregate/list; Staff is denied.
- Successful submit shows `Спасибо, отзыв сохранён.`; low rating `1..3` also shows the safe helper that the feedback is passed to the venue.
- A submitted `5/5` shows `Спасибо за высокую оценку!` and may show `Оставить отзыв на Яндекс.Картах` only when the venue has a validated public review URL. The transition is always an explicit guest click; clearing the setting removes the CTA. Ratings below `5/5` never receive the public review CTA.
- A low rating `1..3` exposes the manual Owner/Manager action `Связаться с гостем`: it opens the exact active `VENUE_CHAT`, reuses an existing active thread or creates a new active thread after a closed/resolved one, and adds a system/context message `Отзыв после визита` with rating, tags, comment and visit date. It does not auto-send an Owner message, create a support ticket or notify staff-chat.
- Booking-only `SEATED` visits remain eligible and explain that the guest can evaluate the booking, welcome and service even when there are no order lines.
- Automated Telegram prompts, `VisitFeedbackWorker`, scheduled feedback requests, marketing sends, automatic Yandex redirect, rewards, loyalty, tips and payments remain disabled/out of scope.
- History list/detail shows real completed visits/orders:
  - `ORDER_CLOSED` / closed order visit;
  - `BOOKING_SEATED` / seated booking visit;
  - merged/deduped visit when booking seated + order closed represent the same real visit.
- `CANCELED`, `NO_SHOW`, `EXPIRED`, `PENDING` and `CHANGED` bookings are excluded from History as visits. Legacy invalid rows are preserved in storage and hidden by query/filtering; no cleanup migration is required for the closed bugfix.
- Closed order detail opens for the current guest, including old closed orders that do not have `promotionDiscounts`, options, notes or complete item fields. Backend returns `promotionDiscounts: []`; Mini App tolerates missing optional `promotionDiscounts`, `items`, `itemName`/`itemId`/`qty`, options and notes.
- History detail keeps the safe error state `Не удалось загрузить детали истории.` for real 404/errors, has `← Назад к истории`, and Telegram BackButton inside detail returns to the History list instead of app home.
- Privacy filters remain strict: foreign visit detail returns 404, another guest's personal tab/order detail is hidden, and shared-tab-only members do not see чужие personal/order details.
- Repeat as Template Phase 1 is **DONE / MVP / LOCAL SMOKE PASSED**. `POST /api/guest/visits/{visitId}/repeat-plan` builds a transient, server-owned plan for one completed order; it never creates an order, batch, notification or persistent template.
- One shared `RepeatOrderResolver` serves Guest Mini App and Telegram. It scopes History to the authenticated user, requires an active same-venue table session and an authorized personal/shared tab, checks guest venue availability, and re-resolves current menu items, option IDs, availability, item prices and option deltas.
- Mini App History shows `Повторить заказ` only for visits with orders and keeps multiple orders separate. Preview lists eligible lines at current prices and skipped lines with explicit reasons; only `Добавить в корзину` mutates the local cart. Existing cart preview/add-batch remains the only later order-creation path.
- Unavailable items, missing/unavailable options, legacy selected options without a reliable option ID and excluded/rejected/canceled lines are not substituted. A bad selected option skips the whole historical line. Reliable historical base-price snapshots are not available for every legacy line, so the MVP shows current prices without a universal old-vs-current price-change badge.
- Guest Favorites Phase 1 is **DONE / MVP / STAGING-SMOKE-PASSED** for favorite venues only: authenticated catalog/detail DTOs expose current-user `isFavorite`; catalog resolves favorite IDs in one batch query; Mini App catalog, venue detail and Account add/remove/read the shared `GuestFavoritesRepository` source used by Telegram bot.
- Favorites preserve current-user isolation and idempotency. Only guest-available `PUBLISHED` venues are addable/listed; hidden, paused, suspended, archived, deleted and subscription-blocked venues disclose no favorite card data. The row survives temporary unavailability and returns after republish; physical venue deletion keeps the existing cascade behavior.
- Account shows only `Избранные заведения` in Phase 1, with open/book/ask/remove actions and the specified empty state. Legacy favorite-item storage/routes remain compatible but are not exposed in this UI.
- Telegram Bot exposes the same venue favorites from both Catalog and Profile. Back navigation is source-aware: a Catalog-opened list returns to Catalog and a Profile-opened list returns to Profile.
- Closure evidence includes focused backend favorites tests, `compileKotlin`, `ktlintCheck`, Mini App build, full browser e2e smoke `62/62`, green GitHub Actions and manual staging smoke for catalog/detail/Account, two-user isolation, unavailable filtering/restoration, Bot/Mini App synchronization and both Telegram entrypoints.
- Booking `SEATED`, order close and table-session close signals exist as foundations for visit history; no-show remains a non-visit booking outcome.
- Promotions/loyalty/bill breakdown foundations may exist in backend/bot surfaces, but simple venue promotions as a guest retention product are not launch-complete across Bot + Mini App.
- Staff profiles / today on shift are a separate Phase 1 staff visibility module, not a growth
  campaign. They are done/local-smoke-passed in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Staff tips
  are future and must not be treated as guest order online payment.
- Persistent template storage, promo codes, loyalty stamps/points, referrals, campaign segmentation and paid placement boosting remain future unless a later implementation summary says otherwise.

## Terms

| Term | Meaning | Status |
| --- | --- | --- |
| `FAVORITE_VENUE` | Guest saves/removes a venue and can list favorite venues. | DONE / MVP / STAGING-SMOKE-PASSED for venue-only Phase 1. |
| `VISIT_HISTORY` | Guest-visible history of confirmed visits derived from table session, booking `SEATED` and closed order signals. | DONE / MVP / staging-smoke-passed for completed visits and booking-only seated visits. |
| `ORDER_HISTORY` | Closed orders shown to the guest with safe venue/date/total/context data. | DONE / MVP / staging-smoke-passed for closed-order visit detail; transient repeat is local-smoke-passed. |
| `BOOKING_HISTORY` | Past and upcoming bookings shown in account/history context. | Partial foundation; keep booking MVP in regression. |
| `REPEAT_TEMPLATE` | A transient repeat plan for one past completed order, re-resolved against current menu state and applied only to the current cart. It is not saved as a library object. | DONE / MVP / LOCAL SMOKE PASSED; CI/staging smoke pending. |
| `POST_VISIT_FEEDBACK` | 1-5 rating, tags and optional comment submitted from completed History detail only; internal venue signal. A configured safe public review URL may be offered only after manual `5/5` submit and explicit guest click. | DONE / MVP / staging-smoke-passed. |
| `VENUE_PROMOTION` | Simple venue banner/announcement with title, description, period, terms and visibility. | MVP target/future implementation unless current code is verified and smoked. |
| `PROMO_CODE` | Code-based discount/reward with limits and accounting. | After MVP. |
| `LOYALTY_STAMP` | Simple stamp-card loyalty model. | Future. |
| `LOYALTY_POINTS` | Points/cashback-style ledger and redemption model. | Future; requires financial model. |
| `REFERRAL` | Guest referral reward/invite model. | Future; requires anti-abuse. |
| `OPT_IN_NOTIFICATION` | Guest consent to receive retention/promo notifications with frequency limits and unsubscribe. | MVP privacy requirement before any marketing sends. |

## MVP Scope

Guest Favorites Phase 1 DONE scope:
- favorite venues only, backed by the existing `guest_favorite_venues` storage and shared Bot/Mini App repository;
- add/remove actions in catalog and venue detail;
- Account list `Избранные заведения`, including open/book/ask/remove actions and empty state;
- current-user isolation with `user_id` derived only from the authenticated session;
- unavailable venue filtering without disclosing a hidden/blocked favorite venue; temporary hide/suspend keeps the row so the venue returns after restoration;
- Telegram Catalog and Profile entrypoints to the same list, with Back returning to the originating screen.

Future Favorites/retention scope:
- favorite menu items;
- favorite menu options;
- recommendations and frequent items;
- persistent repeat-template library;
- notification opt-in and any marketing or favorite-related sends;
- favorites-based promotions;
- loyalty.

Broader Growth scope includes:
- Visit history based on `table_session` + booking + closed order, only after the visit/order model is stable.
- Repeat as template Phase 1: selecting one past completed order builds a transient plan only for the active same-venue table context and authorized tab; current menu validation happens before local cart mutation.
- Post-visit feedback: 1-5 rating, tags and optional comment; only after a confirmed visit.
- Simple venue promotions/banners: title, description, active period, terms and visibility/status.

MVP does not include:
- Promo-code limits or discount execution.
- Loyalty stamps/points/cashback.
- Referrals.
- Segmentation/campaign automation.
- Paid placement or promotion boosting.
- Taste quiz, advanced recommendations or AI-driven personalization.
- Phone/email collection.
- Staff tip payments or platform-collected staff tips.

## Target Guest UX

Guest surfaces should present:
- `Избранное` in catalog and venue card, with favorite/unfavorite and a favorite venues list.
- `История` as visits, bookings and closed orders, not a raw technical log.
- `Повторить заказ` as `REPEAT_TEMPLATE`: it validates current venue/menu/options/stop-list availability and requires same-venue table context plus an authorized tab before cart mutation; order creation remains a later explicit flow.
- `Оценить` only after confirmed visit/order close signal.
- `Акции` with clear period, terms and whether the promotion is informational only or backed by a real discount engine.
- Notifications only after `OPT_IN_NOTIFICATION`; unsubscribe must be visible wherever marketing opt-in is offered.

## Venue UX

Venue Owner/Manager may eventually manage `Акции и удержание`:
- create simple `VENUE_PROMOTION` records with title, description, period, terms, visibility/status;
- pause/archive promotions;
- see only honest status/copy for promotions that are informational only.

Closed feedback slice:
- Owner/Manager sees the own-venue feedback aggregate/list and can manually open `VENUE_CHAT` follow-up for ratings `1..3`; Staff does not see this area or action.
- Owner alone edits `Ссылка для отзывов`; Telegram bot and Venue Mini App use the same backend setting/source of truth.
- Settings helper: `Где взять ссылку: откройте карточку заведения в Яндекс.Картах, нажмите «Поделиться» и скопируйте ссылку. Если у вас есть доступ к Яндекс Бизнесу, лучше взять ссылку на форму отзывов в разделе «О компании» → «Промоматериалы».`
- Ethical hint: `Не обещайте скидки или бонусы за отзыв и не просите поставить конкретную оценку.`

Rules:
- Do not promise automatic discounts if there is no promo engine/accounting path.
- Terms, active period and status are required before a promotion is guest-visible.
- Staff does not manage growth campaigns.
- Promotion notifications require guest opt-in and frequency limits.

## Platform UX

Platform may moderate growth monetization later, but it is not required for MVP:
- Paid placements and promotion boosting are future/advanced.
- If paid placement exists later, it must be clearly labeled as advertising/promoted placement.
- Paid placement depends on Platform billing, moderation, analytics and dispute handling.
- Future analytics: favorite rate, repeat visit rate, promo view/redeem, review completion, opt-in/unsubscribe and abuse/rate-limit indicators.

## Dependencies And Blockers

- Growth implementation depends on stable visit/order history.
- Visit/order history foundation is stable enough for follow-on Growth MVP blocks; repeat/history still depend on active order scoped by `table_session` / `tab` according to `docs/ORDER_SESSION_TAB_CORE.md`. Favorite venues do not require table context.
- Repeat Phase 1 uses current menu availability, stop-list and selected-option IDs/prices through the shared resolver; persistent template storage remains out of scope.
- Feedback depended on a correct close visit/order signal; that dependency is satisfied by the staging-smoked History visit model and remains regression-critical.
- Preorder depends on booking lifecycle from `docs/BOOKING_LIFECYCLE.md` and reliable `visit_count`.
- Paid placement depends on Platform billing, moderation and analytics.
- Cashback/points/flexible loyalty must not be implemented before a correct financial model and discount accounting.
- Promo codes require limits, abuse controls, accounting and clear conflict rules with manual discounts/loyalty.

Target growth events after implementation:
- `favorite_venue_added` / `favorite_venue_removed`;
- `visit_recorded`;
- `repeat_template_created` / `repeat_template_applied`;
- `feedback_submitted` for the History-only internal feedback MVP; `feedback_requested` remains disabled/future;
- `promotion_viewed`;
- `promo_code_copied` / `promo_code_redeemed`.

These events are future/partial until the corresponding growth features are implemented and smoked. Favorites events remain a follow-up because the current idempotent repository contract does not prove a real insert/delete transition without refactoring; duplicate mutations must not emit false events. `feedback_submitted` is the implemented History-only exception; `feedback_requested` stays disabled/future.

## Privacy And Anti-Abuse

- The bot may send marketing messages only to users who already started a dialog with the bot.
- Promo/retention notifications require explicit opt-in.
- Every marketing channel needs frequency limits and unsubscribe.
- Do not collect phone/email for MVP retention.
- Feedback can be submitted only from the current guest's own visible completed History visit.
- Repeat plan can be built only from the current guest's own visible completed order, for an active same-venue table session and a personal or joined shared tab. Prices, availability and option validity are server-owned; foreign visits/orders keep the 404 style.
- Low rating must not expose a public review link. A `5/5` link is optional, validated, venue-configured and opened only by an explicit guest click.
- Feedback follow-up is a manual Owner/Manager `VENUE_CHAT` action. It must not create a support ticket, send an automatic Owner message or post to staff-chat.
- Referrals require anti-abuse, reward limits and fraud monitoring; not MVP.
- List cards, notifications and analytics must avoid unrelated PII, raw Telegram payloads, initData, secrets and provider payloads.
- Staff profile/today-shift public data must follow `docs/STAFF_PROFILES_SHIFTS_TIPS.md`: no public phone/email by default, no raw Telegram username without explicit opt-in and no guest exposure of `linked_user_id`.

## History Foundation Regression Checklist

1. New guest sees empty History state.
2. Closed order appears in History.
3. Old closed order with no discounts/options opens detail.
4. Detail shows positions and total.
5. Missing `promotionDiscounts`, options or note does not crash the UI.
6. Booking-only `SEATED` visit can show safe copy if no order lines exist.
7. `CANCELED`, `NO_SHOW`, `EXPIRED`, `PENDING` and `CHANGED` bookings do not appear as visits.
8. `← Назад к истории` returns to the History list.
9. Telegram BackButton inside detail returns to the History list.
10. Real 404/error shows `Не удалось загрузить детали истории.`.
11. Foreign detail returns 404; guest does not see another guest's personal tab/order detail; shared-tab-only member does not see чужие personal/order details.
12. Booking `SEATED` + order closed does not double-count the same real visit where merge/dedup applies.

## Post-Visit Feedback Regression Checklist

1. Eligible closed-order, booking-only `SEATED` and merged visits show `Оценить визит`; non-seated booking outcomes do not.
2. Guest submits rating `1..5`, up to five allowed tags and an optional comment from History detail only.
3. Successful submit shows `Спасибо, отзыв сохранён.`; low rating shows the venue-feedback helper. Duplicate submit returns the existing feedback and does not overwrite it.
4. Foreign/hidden/non-existent visit feedback is rejected with the existing safe not-found/denial style.
5. Booking-only `SEATED` detail keeps `Посещение по брони. Заказов в этом визите нет.` and adds `Можно оценить бронь, встречу и обслуживание.`
6. After manual `5/5`, the guest sees `Спасибо за высокую оценку!`; `Оставить отзыв на Яндекс.Картах` appears only for a configured safe URL.
7. Clearing the public review URL removes the CTA; there is no broken button, auto-redirect or public CTA below `5/5`.
8. Owner sees the settings helper and ethical hint; only Owner can edit the shared Bot/Mini App public review URL.
9. Guest `1/5` appears in Venue Feedback; Owner/Manager can use `Связаться с гостем`, while Staff cannot see the feedback area/action.
10. Follow-up opens the exact `VENUE_CHAT` with visible feedback context. An active thread is reused with new context; a closed/resolved old thread results in a new active thread.
11. No personal message is sent until Owner/Manager explicitly writes; the guest receives that manual reply in `Чаты`, not Support.
12. Feedback submit/follow-up creates no support ticket and no staff-chat notification.
13. `VisitFeedbackWorker`, scheduled Telegram prompts, marketing push and automatic Yandex redirect remain disabled.

## Acceptance / Smoke Checklist

Guest Favorites Phase 1 staging smoke (`PASSED`):
1. Add/remove favorite from Mini App catalog.
2. Add/remove favorite from Mini App venue detail.
3. Account shows the venue-only `Избранные заведения` list and open/book/ask/remove actions.
4. Empty favorites shows `Пока нет избранных заведений. Добавляйте их из каталога или карточки заведения.`
5. Two authenticated accounts have isolated favorite state.
6. Hidden/suspended or subscription-blocked venue disappears without disclosure while its favorite row is preserved.
7. Republished/restored venue reappears without recreating the favorite row.
8. Bot-created favorite is visible in Mini App and Mini App-created favorite is visible in Bot.
9. Telegram Profile entrypoint opens the current user's favorites; empty state works.
10. Existing Telegram Catalog entrypoint opens the same list.
11. Back returns to Profile or Catalog according to the entrypoint.

Repeat as Template Phase 1 local smoke (`PASSED`; CI/staging pending):
1. Guest history regression checklist above remains green.
2. Booking-only visit has no repeat action; multiple orders are selected explicitly.
3. Missing or wrong-venue table context does not mutate cart or create an order.
4. Active table session and personal/joined shared tab are enforced server-side.
5. Preview shows eligible/skipped lines, current item/option prices and current eligible total.
6. Unavailable item or missing/unavailable/unreliable selected option skips the whole line with human copy.
7. Explicit confirm adds only eligible quantity/options/note to cart; order/batch/staff-chat/History remain unchanged.
8. Telegram and Mini App use the same resolver rules.
9. Backend focused gate, compile, ktlint, Mini App build and full browser e2e smoke `64/64` passed locally.

Broader Growth smoke remains future:
1. Post-Visit Feedback regression checklist above remains green.
2. Promotion is visible only during its active period.
3. Suspended/hidden venue promotions are not visible.
4. Notifications require opt-in and can be disabled.
5. Staff does not manage growth campaigns.
6. Paid placement label is visible if/when paid placement is implemented.

## Status Summary

- Growth/retention: `SPEC UPDATED / PARTIAL-FUTURE`.
- Visit/order history foundation: `DONE / MVP / STAGING-SMOKE-PASSED`.
- History detail legacy order compatibility: `DONE`.
- Full base item historical snapshotting: `FUTURE/FOLLOW-UP` if a later audit still finds gaps beyond the current safe rendering.
- Favorite venues Phase 1: `DONE / MVP / STAGING-SMOKE-PASSED`; favorite menu items/options remain `FUTURE`.
- Repeat as Template Phase 1: `DONE / MVP / LOCAL SMOKE PASSED`; CI and staging smoke remain required before release closure. Persistent template library remains `FUTURE`.
- Promotions: `PLACEHOLDER/PARTIAL FOUNDATION` unless backend + Guest/Venue surfaces are verified for the simple promotion MVP.
- Reviews/post-visit feedback: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Manual `5/5` public review link CTA: `DONE / MVP`; automated review prompts and public review automation remain `FUTURE / disabled`.
- Low-rating manual follow-up through exact `VENUE_CHAT`: `DONE / MVP`; Platform feedback analytics dashboard remains `FUTURE`.
- Loyalty/referrals: `FUTURE`.
- Paid placement/promotion boosting: `FUTURE`.
- Visit history foundation: implemented on top of order/session/booking lifecycle; keep privacy, dedup and terminal-status tests in regression.

## Recommended Next Runtime Block

Guest Favorites Phase 1 is staging-closed. Repeat as Template Phase 1 is implemented and **LOCAL SMOKE PASSED**, but is not staging-closed. Read-only code verification also shows that the previously recommended Order Session Tab Core Hardening is already represented by current table-session active-order uniqueness, tab-scoped guest routes and regression coverage; do not reopen it without concrete regression evidence.

Recommended next action: **Repeat as Template Phase 1 release closure**:
- run green GitHub Actions for the runtime change;
- deploy to staging through the normal runbook;
- smoke own/foreign History, no-context/wrong-venue context, personal/joined shared/unauthorized tabs, current prices/options, skipped legacy/unavailable lines, explicit cart confirmation and no automatic add-batch/staff-chat mutation;
- re-smoke the Telegram repeat path against the same resolver.

No broader growth feature should be selected until that release gate is recorded. Favorite menu items/options, recommendations/frequent items, promotions, notifications, loyalty, tips, online payments, Telegram Stars and crypto are not part of Repeat Phase 1.
