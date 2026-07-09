# Guest Growth And Retention Model

Дата актуализации: 2026-07-09.

Статус: **current product reference / SPEC UPDATED**. Runtime-фичи growth/retention не считаются закрытыми, пока для них нет backend/Mini App/Bot evidence и staging smoke. Guest visit/order history foundation is **DONE / MVP / staging-smoke-passed**; post-visit feedback MVP is **DONE / MVP / local-validation-passed / staging-smoke-pending**; broader retention loops remain partial/future. Этот документ описывает целевую модель, MVP-границы, зависимости и privacy rules для гостевого удержания.

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
- Post-visit feedback MVP is **DONE / MVP / local-validation-passed / staging-smoke-pending**: guests can submit one internal rating/tag/comment review from completed History detail only; Venue Owner/Manager can read aggregate/latest feedback; Staff cannot view the feedback section. It does not start Telegram prompts, `VisitFeedbackWorker` prompts, public review CTA/handoff, marketing sends, support tickets, loyalty rewards, tips or payments.
- History list/detail shows real completed visits/orders:
  - `ORDER_CLOSED` / closed order visit;
  - `BOOKING_SEATED` / seated booking visit;
  - merged/deduped visit when booking seated + order closed represent the same real visit.
- `CANCELED`, `NO_SHOW`, `EXPIRED`, `PENDING` and `CHANGED` bookings are excluded from History as visits. Legacy invalid rows are preserved in storage and hidden by query/filtering; no cleanup migration is required for the closed bugfix.
- Closed order detail opens for the current guest, including old closed orders that do not have `promotionDiscounts`, options, notes or complete item fields. Backend returns `promotionDiscounts: []`; Mini App tolerates missing optional `promotionDiscounts`, `items`, `itemName`/`itemId`/`qty`, options and notes.
- History detail keeps the safe error state `Не удалось загрузить детали истории.` for real 404/errors, has `← Назад к истории`, and Telegram BackButton inside detail returns to the History list instead of app home.
- Privacy filters remain strict: foreign visit detail returns 404, another guest's personal tab/order detail is hidden, and shared-tab-only members do not see чужие personal/order details.
- Account favorites and broader retention loops still require separate implementation/smoke before being marked complete.
- Booking `SEATED`, order close and table-session close signals exist as foundations for visit history; no-show remains a non-visit booking outcome. Completed repeat/favorites/promotion loops are not closed.
- Promotions/loyalty/bill breakdown foundations may exist in backend/bot surfaces, but simple venue promotions as a guest retention product are not launch-complete across Bot + Mini App.
- Staff profiles / today on shift are a separate Phase 1 staff visibility module, not a growth
  campaign. They are done/local-smoke-passed in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Staff tips
  are future and must not be treated as guest order online payment.
- Repeat templates, promo codes, loyalty stamps/points, referrals, campaign segmentation and paid placement boosting remain future unless a later implementation summary says otherwise.

## Terms

| Term | Meaning | Status |
| --- | --- | --- |
| `FAVORITE_VENUE` | Guest saves/removes a venue and can list favorite venues. | MVP target; current baseline needs verification before DONE. |
| `VISIT_HISTORY` | Guest-visible history of confirmed visits derived from table session, booking `SEATED` and closed order signals. | DONE / MVP / staging-smoke-passed for completed visits and booking-only seated visits. |
| `ORDER_HISTORY` | Closed orders shown to the guest with safe venue/date/total/context data. | DONE / MVP / staging-smoke-passed for closed-order visit detail; repeat templates remain future. |
| `BOOKING_HISTORY` | Past and upcoming bookings shown in account/history context. | Partial foundation; keep booking MVP in regression. |
| `REPEAT_TEMPLATE` | A saved template from a past order/visit that can be applied on the next table context. | MVP target; must not create an order outside table context. |
| `POST_VISIT_FEEDBACK` | 1-5 rating, tags and optional comment submitted from completed History detail only; internal venue/platform signal, no public review handoff. | DONE / MVP / local-validation-passed / staging-smoke-pending. |
| `VENUE_PROMOTION` | Simple venue banner/announcement with title, description, period, terms and visibility. | MVP target/future implementation unless current code is verified and smoked. |
| `PROMO_CODE` | Code-based discount/reward with limits and accounting. | After MVP. |
| `LOYALTY_STAMP` | Simple stamp-card loyalty model. | Future. |
| `LOYALTY_POINTS` | Points/cashback-style ledger and redemption model. | Future; requires financial model. |
| `REFERRAL` | Guest referral reward/invite model. | Future; requires anti-abuse. |
| `OPT_IN_NOTIFICATION` | Guest consent to receive retention/promo notifications with frequency limits and unsubscribe. | MVP privacy requirement before any marketing sends. |

## MVP Scope

MVP includes:
- Favorites for venues in catalog and venue card.
- Visit history based on `table_session` + booking + closed order, only after the visit/order model is stable.
- Repeat as template: selecting a past order creates a template for the next table context; it does not create an order without QR/table context, selected tab and current menu validation.
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
- `Повторить` as `REPEAT_TEMPLATE`: it validates current venue/menu/stop-list availability and requires table context before order creation.
- `Оценить` only after confirmed visit/order close signal.
- `Акции` with clear period, terms and whether the promotion is informational only or backed by a real discount engine.
- Notifications only after `OPT_IN_NOTIFICATION`; unsubscribe must be visible wherever marketing opt-in is offered.

## Venue UX

Venue Owner/Manager may eventually manage `Акции и удержание`:
- create simple `VENUE_PROMOTION` records with title, description, period, terms, visibility/status;
- pause/archive promotions;
- see only honest status/copy for promotions that are informational only.

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
- Visit/order history foundation is stable enough for follow-on Growth MVP blocks; repeat/favorites/history still depend on active order scoped by `table_session` / `tab` according to `docs/ORDER_SESSION_TAB_CORE.md`.
- Repeat templates depend on current menu availability, stop-list and selected-option validation.
- Feedback depends on a correct close visit/order signal.
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

These events are future/partial until the corresponding growth features are implemented and smoked.

## Privacy And Anti-Abuse

- The bot may send marketing messages only to users who already started a dialog with the bot.
- Promo/retention notifications require explicit opt-in.
- Every marketing channel needs frequency limits and unsubscribe.
- Do not collect phone/email for MVP retention.
- Reviews/feedback can be requested only after confirmed visit.
- Low rating must not automatically push a public review link.
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

## Future Acceptance / Smoke Checklist

1. Guest can favorite and unfavorite a venue.
2. Guest sees favorite venues.
3. Guest history regression checklist above remains green.
4. Repeat template requires table context before creating an order.
5. Repeat template skips or clearly marks unavailable/stopped items.
6. Feedback is requested only after confirmed visit.
7. Low rating does not automatically push a public review link.
8. Promotion is visible only during its active period.
9. Suspended/hidden venue promotions are not visible.
10. Notifications require opt-in and can be disabled.
11. Staff does not manage growth campaigns.
12. Paid placement label is visible if/when paid placement is implemented.

## Status Summary

- Growth/retention: `SPEC UPDATED / PARTIAL-FUTURE`.
- Visit/order history foundation: `DONE / MVP / STAGING-SMOKE-PASSED`.
- History detail legacy order compatibility: `DONE`.
- Full base item historical snapshotting: `FUTURE/FOLLOW-UP` if a later audit still finds gaps beyond the current safe rendering.
- Favorites/repeat: `FUTURE/PARTIAL FOUNDATION` until implementation smoke proves the end-to-end flows.
- Promotions: `PLACEHOLDER/PARTIAL FOUNDATION` unless backend + Guest/Venue surfaces are verified for the simple promotion MVP.
- Reviews/post-visit feedback: `DONE / MVP / local-validation-passed / staging-smoke-pending`.
- Loyalty/referrals: `FUTURE`.
- Paid placement/promotion boosting: `FUTURE`.
- Visit history foundation: implemented on top of order/session/booking lifecycle; keep privacy, dedup and terminal-status tests in regression.

## Recommended Next Runtime Block

Recommended next release step: **staging deploy + staging smoke for Post-visit feedback MVP**.

Why:
- It changes backend routes, database schema, Mini App runtime behavior and legacy feedback prompt/public review runtime paths.
- Local validation is green, but production readiness must still come from the deployment runbook: green Actions, staging deploy and staging smoke.
- Later Growth runtime blocks should be selected only after this staging evidence is recorded.
