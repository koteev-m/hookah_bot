# Guest Growth And Retention Model

Дата актуализации: 2026-08-06.

Статус: **current product reference / SPEC UPDATED**. Runtime-фичи growth/retention не считаются release-ready, пока для них нет требуемого CI/staging evidence. Guest visit/order history foundation, Post-Visit Feedback MVP, Guest Favorites Phase 1 and Simple Venue Promotions Phase 1 are **DONE / MVP / STAGING-SMOKE-PASSED**. Repeat as Template Phase 1 is **MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE**; its environment-dependent production-readiness gate remains open in [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001), while independent bounded development may continue. Executable Promotions Phase 2 / Happy Hours Percent is **DONE / STAGING-SMOKE-PASSED**. Gift parity is **GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**; CI and staging evidence remain open. Promotion lifecycle status/archive audit is **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Venue promotions tabs are **VENUE PROMOTIONS LIST / CURRENT AND ARCHIVED TABS UX / DONE / MVP / STAGING-SMOKE-PASSED**. Broader retention loops and dangerous-action audit remain partial/future.

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
- Repeat as Template Phase 1 is **MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE**. `POST /api/guest/visits/{visitId}/repeat-plan` builds a transient, server-owned plan for one completed order; it never creates an order, batch, notification or persistent template. Required two-venue/real-QR/tab/privacy/Bot parity checks are not marked passed and remain canonical in [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001).
- One shared `RepeatOrderResolver` serves Guest Mini App and Telegram. It scopes History to the authenticated user, requires an active same-venue table session and an authorized personal/shared tab, checks guest venue availability, and re-resolves current menu items, option IDs, availability, item prices and option deltas.
- Mini App History shows `Повторить заказ` only for visits with orders and keeps multiple orders separate. Preview lists eligible lines at current prices and skipped lines with explicit reasons; only `Добавить в корзину` mutates the local cart. Existing cart preview/add-batch remains the only later order-creation path.
- Unavailable items, missing/unavailable options, legacy selected options without a reliable option ID and excluded/rejected/canceled lines are not substituted. A bad selected option skips the whole historical line. Reliable historical base-price snapshots are not available for every legacy line, so the MVP shows current prices without a universal old-vs-current price-change badge.
- Guest Favorites Phase 1 is **DONE / MVP / STAGING-SMOKE-PASSED** for favorite venues only: authenticated catalog/detail DTOs expose current-user `isFavorite`; catalog resolves favorite IDs in one batch query; Mini App catalog, venue detail and Account add/remove/read the shared `GuestFavoritesRepository` source used by Telegram bot.
- Favorites preserve current-user isolation and idempotency. Only guest-available `PUBLISHED` venues are addable/listed; hidden, paused, suspended, archived, deleted and subscription-blocked venues disclose no favorite card data. The row survives temporary unavailability and returns after republish; physical venue deletion keeps the existing cascade behavior.
- Account shows only `Избранные заведения` in Phase 1, with open/book/ask/remove actions and the specified empty state. Legacy favorite-item storage/routes remain compatible but are not exposed in this UI.
- Telegram Bot exposes the same venue favorites from both Catalog and Profile. Back navigation is source-aware: a Catalog-opened list returns to Catalog and a Profile-opened list returns to Profile.
- Closure evidence includes focused backend favorites tests, `compileKotlin`, `ktlintCheck`, Mini App build, full browser e2e smoke `62/62`, green GitHub Actions and manual staging smoke for catalog/detail/Account, two-user isolation, unavailable filtering/restoration, Bot/Mini App synchronization and both Telegram entrypoints.
- Booking `SEATED`, order close and table-session close signals exist as foundations for visit history; no-show remains a non-visit booking outcome.
- Simple Venue Promotions Phase 1 is **DONE / MVP / STAGING-SMOKE-PASSED**. Venue Owner/Manager use the Venue Mini App to list, create, edit, activate, pause and archive informational `TEXT_ONLY` promotions; Staff is hidden and denied server-side. Rule-backed promotion templates remain in their existing Telegram flows and cannot be mutated through this focused API.
- Venue promotions list tabs UX is **VENUE PROMOTIONS LIST / CURRENT AND ARCHIVED TABS UX / DONE / MVP / STAGING-SMOKE-PASSED**. `Текущие` contains loaded `DRAFT`, `ACTIVE` and `PAUSED` promotions; `Архив` contains loaded `ARCHIVED` promotions. Only one panel is visible, neither tab shows a numeric count, and each panel has its own empty state.
- Authoritative same-venue refresh, including `STALE`, preserves the selected tab. Activation and pause stay in `Текущие`; archive moves the refreshed card to `Архив`. Venue/account switch disposes the old request and screen data and resets the new screen to `Текущие`.
- Tabs implement `tablist`, `tab`, `tabpanel`, `aria-selected`, roving `tabindex`, Arrow Left/Right,
  Home/End and visible focus styles. Archived cards preserve title, description, terms, period,
  template and status with neutral archived copy, are read-only, and have no lifecycle actions or
  publication-readiness validation. A full archived rule-configuration viewer is not implemented.
- The tabs use the existing management response only. Its existing current/archive collection limit `100` remains unchanged; database-wide totals, pagination, `hasMore`, cursor, server-side filtering or a separate/lazy archive endpoint require separate product evidence and are not implied by this frontend slice.
- Guest venue detail shows only `ACTIVE` promotions inside their current period after the existing `PUBLISHED` venue and guest/subscription availability checks. Draft, paused, archived, future and expired promotions are not disclosed.
- Mini App and Telegram reuse the existing `venue_promotions` schema and `VenuePromotionRepository`; no migration, parallel model or discount engine was added. Informational promotions do not change order totals or send marketing notifications.
- Promotion status/archive writes in Venue Mini App and Telegram now use one authoritative repository transaction. Parent status, currently synchronized rule statuses and exactly one `VENUE_PROMOTION_STATUS_CHANGED` or `VENUE_PROMOTION_ARCHIVED` audit commit together; audit failure rolls the lifecycle write back.
- Actor and `VENUE_MINI_APP` / `TELEGRAM_BOT` source are server-derived. The safe audit payload is limited to venue/promotion/template identity, old/new status, source and deterministic rule id/version/old/new status rows. No-op, stale/repeated, denied, invalid/not-found and rollback paths create no success audit.
- Mini App lifecycle `STALE` returns HTTP `409`, code `PROMOTION_LIFECYCLE_STALE` and message
  `Статус акции уже изменился. Обновите список и повторите действие.`. The client shows no
  false success, reloads the authoritative list once and never retries the mutation automatically.
- The Happy Hours percentage Phase 2 slice is **DONE / STAGING-SMOKE-PASSED**. Owner/Manager can configure title, description, terms, parent date range, venue-timezone weekday windows, one item/category target, percentage `1..100` and lifecycle in Venue Mini App; Staff is hidden and denied.
- Guest Mini App and Telegram route the same current-price cart through `OrdersRepository` and the shared `PromotionRuleEngine`. Preview is side-effect free; submit revalidates time, lifecycle, current menu/option prices and availability, session/tab authorization, persists one immutable application snapshot and is idempotent.
- Staging smoke covered creation and activation validation, weekday/time windows, item/category targets, current price, selected-option delta, cart preview, submit recalculation, persisted bill/History, no stacking, manual-discount rejection, Owner/Manager/Staff RBAC, Bot/Mini App parity and `TEXT_ONLY` regression.
- The Happy Hours slice applies at most one executable percentage promotion per line. Automatic promotion and manual item discount cannot coexist; persisted promotion facts drive Guest History, Venue bill and staff-chat.
- Gift status is **GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. Venue Owner/Manager have a separate `Подарок при покупке` preset; Guest Mini App and Telegram consume one explicit fixed/selectable/unavailable offer and accept/select/skip decision contract over the same engine.
- Gift preview is mutation-free and returns a 10-minute HMAC-signed `gift_decision` scope with a separate purpose/audience/domain. It binds authenticated user, venue, table session, tab, canonical cart fingerprint and promotion/rule/version/offer, and carries no trusted price, discount or final amount.
- Submit verifies signature, expiry and complete scope before revalidating current venue time, lifecycle, schedule, trigger and required options, allowlist, availability, prices, session/tab membership, one-gift winner and idempotency. Legacy unsigned choice/skip fields fail closed. A stale cart/session/tab decision persists nothing and returns `Корзина изменилась. Проверьте подарок ещё раз.`
- Mini App draft storage is a UX cache scoped by user, venue, table session, tab, cart fingerprint and token expiry. Account, venue, session, tab or cart changes clear it, including initial restore with no previous tab and a replacement session reached through the same physical QR. Telegram callbacks use amount-free per-offer tags; the process map is UX-only and stale/different-context callbacks fail safely.
- Canceling an unavailable trigger and excluding a trigger atomically apply the matching transition to its active linked reward. Repeat transitions are idempotent; an already inactive reward is not restored, and reward-only cancellation/exclusion never mutates the paid trigger. Guest bill, Venue bill, History and staff-chat read the same persisted result while applications, links and immutable snapshots remain present.
- Manual discount is rejected on both an active reward and its linked trigger with `На эту позицию уже действует акция. Ручную скидку применить нельзя.` No role bypasses the server check: STAFF is denied by the repository, and Telegram also hides the action and rejects direct or stale-dialog attempts. After the linked reward is inactive, the normal trigger discount workflow applies for an allowed Owner/Manager actor. Gift eligibility also fails closed when an incompatible manual adjustment already exists.
- Staff profiles / today on shift are a separate Phase 1 staff visibility module, not a growth
  campaign. They are done/local-smoke-passed in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Staff tips
  are future and must not be treated as guest order online payment.
- Persistent template storage, promo codes, loyalty stamps/points, referrals, campaign segmentation and paid placement boosting remain future unless a later implementation summary says otherwise.

## Promotion Compatibility Policy

Status: **AUDIT / FUTURE IMPLEMENTATION**.

Gift With Item smoke observed Happy Hours Percentage and Gift With Item being applied together.
This is not recorded as a confirmed runtime bug or as a status change for either current slice.
The product gap is the absence of one explicit cross-promotion conflict policy. Existing bounded
`no stacking` evidence covers the current per-line percentage/manual-discount and gift reward
guards; it does not define compatibility between every executable reward type.

All executable promotions must use one server-owned Promotion Compatibility Policy rather than
separate compatibility settings inside Happy Hours, Gift With Item or any later promotion type.
The same mechanism must cover Happy Hours, Gift With Item, future cashback, personal discounts,
loyalty and promo codes and must be reward-type-aware.

Compatibility modes:

- `STACKABLE`: compatible rewards may apply together, for example Happy Hours `-50%` plus free tea.
- `EXCLUSIVE`: one best offer wins the conflict, for example Happy Hours `-50%` instead of a
  personal discount `-20%`.
- `OVERRIDE`: the promotion suppresses all other rewards and discounts in its defined scope.

Resolution must use explicit promotion priority plus a deterministic winner/tie-break policy.
Rule/database iteration order, client order and timing must never decide the result. Recommended
defaults are:

| Reward pair | Default policy |
| --- | --- |
| Discount vs discount | `EXCLUSIVE`. |
| Discount vs gift | `STACKABLE`. |
| Gift vs gift | `EXCLUSIVE`; at most one gift. |
| Cashback | Separate future policy inside the same compatibility mechanism, after its financial model is defined. |

The Guest sees only the final applied combination and totals, not rejected candidates or internal
priority. Venue Owner/Manager must receive an understandable explanation of which compatibility
mode and priority selected or suppressed each offer. The resolver must fail closed against
accidental discount addition. Manual discount policy must participate in the same compatibility
decision and preserve server-side actor/RBAC rules; current STAFF denial is not a bypass. Future
loyalty and cashback must reuse this mechanism rather than add another stacking engine.

## Terms

| Term | Meaning | Status |
| --- | --- | --- |
| `FAVORITE_VENUE` | Guest saves/removes a venue and can list favorite venues. | DONE / MVP / STAGING-SMOKE-PASSED for venue-only Phase 1. |
| `VISIT_HISTORY` | Guest-visible history of confirmed visits derived from table session, booking `SEATED` and closed order signals. | DONE / MVP / staging-smoke-passed for completed visits and booking-only seated visits. |
| `ORDER_HISTORY` | Closed orders shown to the guest with safe venue/date/total/context data. | DONE / MVP / staging-smoke-passed for closed-order visit detail; transient repeat is local-smoke-passed. |
| `BOOKING_HISTORY` | Past and upcoming bookings shown in account/history context. | Partial foundation; keep booking MVP in regression. |
| `REPEAT_TEMPLATE` | A transient repeat plan for one past completed order, re-resolved against current menu state and applied only to the current cart. It is not saved as a library object. | MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE. |
| `POST_VISIT_FEEDBACK` | 1-5 rating, tags and optional comment submitted from completed History detail only; internal venue signal. A configured safe public review URL may be offered only after manual `5/5` submit and explicit guest click. | DONE / MVP / staging-smoke-passed. |
| `VENUE_PROMOTION` | Simple informational venue announcement with title, description, required period, optional terms and lifecycle status. | DONE / MVP / STAGING-SMOKE-PASSED for informational Phase 1. |
| `EXECUTABLE_PROMOTION` | A server-evaluated schedule + eligibility + reward rule with an immutable application snapshot in the order. | Happy Hours Percent: DONE / STAGING-SMOKE-PASSED. Gift parity: GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT. |
| `PROMOTION_COMPATIBILITY_POLICY` | One reward-type-aware conflict resolver for all executable promotions and manual discounts. | AUDIT / FUTURE IMPLEMENTATION. |
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

Venue Owner/Manager manage `Акции`:
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
- Promo codes require limits, abuse controls, accounting and the shared Promotion Compatibility
  Policy for conflicts with manual discounts/loyalty.

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

Repeat as Template Phase 1:

- automated state: `MVP IMPLEMENTED / LOCAL VALIDATION PASSED`;
- environment-dependent manual state: `BLOCKED_BY_ENVIRONMENT`;
- canonical prerequisites, scenarios, cleanup and closure criteria:
  [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001);
- do not use `STAGING-SMOKE-PASSED` until that record is closed.

Simple Venue Promotions Phase 1 staging smoke (`PASSED`):

1. Owner/Manager management API and Mini App create/edit/status/archive flows use the existing repository.
2. Staff and foreign-venue access are denied.
3. Guest venue detail exposes only current `ACTIVE` promotions for an available venue.
4. Draft, paused, archived, future and expired promotions remain hidden.
5. GitHub Actions were green; staging deploy and manual cross-surface smoke passed.

Broader Growth smoke remains future:

1. Post-Visit Feedback regression checklist above remains green.
2. Notifications require opt-in and can be disabled.
3. Paid placement label is visible if/when paid placement is implemented.

## Status Summary

- Growth/retention: `SPEC UPDATED / PARTIAL-FUTURE`.
- Visit/order history foundation: `DONE / MVP / STAGING-SMOKE-PASSED`.
- History detail legacy order compatibility: `DONE`.
- Full base item historical snapshotting: `FUTURE/FOLLOW-UP` if a later audit still finds gaps beyond the current safe rendering.
- Favorite venues Phase 1: `DONE / MVP / STAGING-SMOKE-PASSED`; favorite menu items/options remain `FUTURE`.
- Repeat as Template Phase 1: `MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE`; [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001) remains open. Persistent template library remains `FUTURE`.
- Simple Venue Promotions Phase 1: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Venue Promotions Current/Archived Tabs UX: `VENUE PROMOTIONS LIST / CURRENT AND ARCHIVED TABS UX / DONE / MVP / STAGING-SMOKE-PASSED`.
- Executable Promotions Phase 2 / Happy Hours Percent: `DONE / STAGING-SMOKE-PASSED`.
- Gift parity: `GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`; CI/staging gate remains open.
- Promotion lifecycle status audit: `DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED`; this closes only status/archive lifecycle audit, not the broader dangerous-action audit.
- Reviews/post-visit feedback: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Manual `5/5` public review link CTA: `DONE / MVP`; automated review prompts and public review automation remain `FUTURE / disabled`.
- Low-rating manual follow-up through exact `VENUE_CHAT`: `DONE / MVP`; Platform feedback analytics dashboard remains `FUTURE`.
- Loyalty/referrals: `FUTURE`.
- Paid placement/promotion boosting: `FUTURE`.
- Visit history foundation: implemented on top of order/session/booking lifecycle; keep privacy, dedup and terminal-status tests in regression.

## Latest Bounded Runtime Block

Guest Favorites Phase 1 is staging-closed. Repeat as Template Phase 1 is implemented and locally validated, while its environment-dependent smoke remains deferred in [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001). That open feature-specific production-readiness gate does not block an independent bounded runtime block. Read-only code verification also shows that the previously recommended Order Session Tab Core Hardening is already represented by current table-session active-order uniqueness, tab-scoped guest routes and regression coverage; do not reopen it without concrete regression evidence.

Closed bounded frontend block: **VENUE PROMOTIONS LIST / CURRENT AND ARCHIVED TABS UX / DONE / MVP / STAGING-SMOKE-PASSED**. It partitions the already loaded management response into mutually exclusive accessible `Текущие` and `Архив` panels without counts and changes no backend, lifecycle, audit, pricing, Happy Hours or Gift contract.

Closed bounded runtime block: **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**:

- preserves the existing `DRAFT` / `ACTIVE` / `PAUSED` / `ARCHIVED` lifecycle and routes all current Mini App and Telegram status/archive writers through one repository mutation;
- commits parent status, synchronized rule status and one safe actor-bearing audit on the same JDBC connection and transaction, or rolls them all back;
- derives actor/source on the server and writes no success audit for no-op, stale/repeated, denied, invalid/not-found or rolled-back mutations;
- preserves the existing `200` plus authoritative DTO contract for `APPLIED` and `NO_OP`; only
  `STALE` is now a typed safe `409` conflict;
- changes no Guest visibility guard, Happy Hours/Gift calculation, bill/History snapshot or
  compatibility/stacking policy.

Simple Venue Promotions Phase 1 remains `DONE / MVP / STAGING-SMOKE-PASSED`. The audit slice reuses
the current schema and repository with no migration. Promotion create/config edit audit and broader
dangerous-action audit coverage remain future.

Current staging correction: the repeated lifecycle smoke passed for Owner Mini App status/archive,
Manager transition, Staff and foreign-venue denial, Telegram activate/pause/archive, Mini App ↔
Telegram parity, Happy Hours and Gift lifecycle regression, exactly-one audit, no-op without a
duplicate audit, actor/source/action/payload privacy, Guest `ACTIVE`/`PAUSED`/`ARCHIVED` visibility,
Guest catalog/detail availability and cleanup. Guest availability was restored before the repeated
Guest smoke. The historical first failed smoke remains relevant: promotion pause succeeded, a
separate archive request followed, and Guest catalog was unavailable because the subscription was
`SUSPENDED_BY_PLATFORM`. That subscription incident was environmental and is not a promotion
defect.

Current tabs staging evidence passed for default `Текущие`, current/archive partition,
one visible list, mouse and keyboard tabs, pause staying current, archive cancel preserving status,
confirmed archive moving the card after authoritative refresh, archived read-only/no-readiness
behavior, Owner/Manager access, Staff denial, venue-switch isolation, automated empty-state
coverage and cleanup. The management API still returns at most `100` current and `100` archived
records; no totals, counts, pagination, `hasMore`, cursor or all-cardinality completeness is claimed.

Open P2/future remains unchanged: promotion create/config edit audit, audit payload
cardinality/summary policy, archived rule configuration viewer, menu price/archive audit,
`IMPLEMENT_DANGEROUS_ACTION_AUDIT_SLICE_NEXT — STAFF ROLE / REMOVAL AUDIT` (it begins with the
target-audit-identifier privacy gate and stops before runtime changes if no allowed identifier
exists; see `docs/SECURITY_RBAC_MATRIX.md` and `docs/UPDATED_PRODUCT_AI_ROADMAP.md`), force-close/
session audit, broader audit viewer and Promotion Compatibility Policy; plus pre-loading empty-state
correctness, duplicate promotion-ID reconciliation between current/archive queries, a non-color
selected-tab indicator, complete ArrowLeft/End/roving-tabindex e2e coverage and pagination/total/
`hasMore` only when real cardinality requires it.

## Executable Promotions Phase 2

Status: **EXECUTABLE PROMOTIONS PHASE 2 / HAPPY HOURS PERCENT SLICE / DONE / STAGING-SMOKE-PASSED**.

Implemented bounded behavior:

- existing `VenuePromotionRepository`, `VenuePromotionRuleRepository`, `PromotionRuleEngine`,
  `PromotionApplicationRepository`, `OrdersRepository` and promotion ledger remain the only
  calculation/persistence path; no second promotion engine was added;
- one rule stores normalized weekday windows with inclusive start/exclusive end in the venue
  timezone, alongside a versioned item/category target and percentage `1..100`;
- activation is one server-side Owner/Manager validation for parent lifecycle, target ownership,
  valid non-overlapping windows, percentage and timezone; Staff and foreign venue access are denied;
- preview creates no order, batch, ledger or notification; submit re-resolves current item price,
  selected-option deltas, availability and active session/tab authorization, then recalculates;
- deterministic no-stacking selects one winner per line; a manual item discount is rejected when a
  promotion adjustment exists, while exclusion remains a separate action;
- successful submit stores title, rule/config/target/version, eligible-line pricing, amounts,
  applied time, timezone and dedupe snapshots; replay cannot duplicate the application/adjustment;
- Guest cart, History, Venue bill and staff-chat render persisted original/discount/final facts.

Closure evidence covers required backend promotion/repository/routes/order/Telegram selectors,
Kotlin compile/lint, Mini App production build, deterministic Playwright smoke `71/71`, green
release validation and successful staging smoke for creation/activation, schedules, targets,
current price and option delta, preview/recalculation, immutable bill/History, conflict policy,
RBAC, Bot/Mini App parity and `TEXT_ONLY` regression.

Explicitly out of scope for this first slice: gifts, BOGO, second-item-free, free option/refill,
special fixed price, fixed-amount discount, loyalty/points/cashback, promo codes, birthday and
visit-count rules, referrals, notifications, paid placement/boosting, recommendations, payments,
arbitrary rule builder, multiple promotion stacking, automatic substitution and any change to
`REPEAT-MANUAL-001`.

### Promotion template UX

One server-owned Promotion Rules & Rewards Engine remains the only calculation path. Venue UX
exposes bounded presets rather than one universal Happy Hours form:

1. `TEXT_ONLY` — информационная акция.
2. `HAPPY_HOURS_PERCENT` — скидка по расписанию.
3. `GIFT_WITH_ITEM` — подарок при покупке.
4. BOGO / X+Y — later.
5. Специальная цена — later.
6. Бесплатная option/refill — later.

Schedule, targets, no-stacking resolution, preview, submit recalculation, ledger and History are
shared. Happy Hours stays a schedule-based percentage preset and does not become a container for
gift or future reward mechanisms.

### Implemented bounded slice — GIFT_WITH_ITEM Bot/Mini App parity

Status: **GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

Implemented runtime evidence:

- existing `VenuePromotionRuleRepository`, `PromotionRuleEngine`, `OrdersRepository`, promotion
  adjustments, applications and reward-link ledger remain the only rule, calculation and
  persistence path; no migration or second engine was added;
- the shared server contract exposes `NO_GIFT`, `FIXED_GIFT_AVAILABLE`,
  `GIFT_CHOICE_REQUIRED`, `GIFT_UNAVAILABLE`, `GIFT_SKIPPED` and `GIFT_SELECTED`, with client
  decisions limited to `ACCEPT_FIXED`, `SELECT_ITEM` and `SKIP`;
- Venue Owner/Manager manage a draft-first `Подарок при покупке` preset with item/category
  trigger, fixed/selectable reward, date range, venue-timezone weekday windows and lifecycle;
  Staff is hidden and denied;
- Guest Mini App and Telegram render the same server-owned offer, require explicit confirmation or
  selection, support skip, never submit trusted price/discount facts and preserve normal cart
  confirmation before order creation;
- preview creates no order, batch, reward line, adjustment, application, reward link or
  staff-chat event. It returns a 10-minute HMAC-SHA-256 opaque token derived from the existing
  server secret with `gift_decision/v1` domain separation, purpose `gift_decision` and audience
  `hookah-order-submit`. The signed scope binds user, venue, table session, tab, canonical cart
  fingerprint, promotion/rule/version and offer type without financial amounts;
- the canonical fingerprint is server-owned and deterministic over venue/session/tab,
  order-independent menu items and quantities, sorted selected option IDs, normalized note/comment
  and promotion context. Submit verifies signature, purpose, audience, expiry and full scope before
  recalculating current venue time, lifecycle, schedule, trigger/options, deterministic winner,
  allowlist, required-option support, reward availability/current price, session/tab authorization
  and idempotency inside one transaction. Legacy unsigned gift choice/skip input fails closed;
- stale scope changes no financial state and returns
  `Корзина изменилась. Проверьте подарок ещё раз.`;
- at most one gift is persisted per submitted batch. Multiple triggers do not multiply it, reward
  lines cannot receive Happy Hours or manual discount, trigger lines with an active linked reward
  cannot receive manual discount, and fixed/selectable unavailable states never cause silent
  substitution;
- the reward line snapshots current original price, quantity, 100% adjustment, final zero,
  currency, promotion title, rule/type/version and selected item. Trigger/reward linkage and
  persisted facts drive Guest History, Venue bill and idempotent replay after later promotion/menu
  edits;
- canceling an unavailable trigger and excluding a trigger acquire deterministic
  order/batch → trigger → linked reward → link/application locks and atomically transition the
  active linked reward. Repeat operations and already inactive rewards are safe; reward-only
  cancellation/exclusion remains one-way. Rollback preserves the original bill, and application,
  link and immutable snapshots are never deleted. Guest/Venue bills, History and staff-chat use
  the same committed facts;
- Mini App LocalStorage remains a UX cache scoped by user, venue, table session, tab, canonical
  cart fingerprint and token expiry, and is cleared on account/venue/session/tab/cart changes.
  Telegram `ConcurrentHashMap` state also remains UX-only; amount-free tagged callbacks bind to one
  offer context and stale or missing bindings fail safely. Fresh resolver/router tests submit
  serialized fixed/selectable/skip decisions through the production repository path.

Local evidence: `PromotionRuleEngine` 37/0, `VenuePromotionRepository` 27/0,
`VenuePromotionRoutes` 9/0, signed-token tests 6/0, `GuestOrderRoutes` 51/0,
`VenueOrderRoutes` + `VenueOrdersRepository` 54/0, `GuestVisitRoutes` 6/0,
`VisitRepository` final 16/0 after one detected and fixed initial failure,
`TelegramBotRouter` 503/0 and real PostgreSQL concurrency 6/0 with
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44` passed with skipped=0. Kotlin lint/compile, Mini App
production build and deterministic Playwright smoke `83/83` passed. GitHub Actions and staging
cross-surface smoke remain required before release; this slice is not `STAGING-SMOKE-PASSED`.

Exact out of scope: BOGO / X+Y, second-item-free, free option/refill, fixed-amount discount,
special price, cross-visit or cross-batch accumulation, loyalty, promo codes, notifications, paid
placement, payments/Stars/crypto, arbitrary rule builder, automatic substitution, multiple gift
rewards and changes to Repeat or `REPEAT-MANUAL-001`.
