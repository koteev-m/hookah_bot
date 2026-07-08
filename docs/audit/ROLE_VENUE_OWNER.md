# Venue Owner

Дата актуализации: 2026-07-08.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. Этот файл разделяет Telegram bot owner setup flow и Venue Mini App owner panel.

## Current status

Venue Owner - главный владелец конкретного заведения. Он управляет карточкой, заказным меню, столами/QR, персоналом, staff chat, бронями, сообщениями с гостями, статистикой и операционными заказами. Growth/retention scope such as `Акции и удержание`, favorites/history/repeat/feedback is governed by `docs/GROWTH_RETENTION.md` and remains partial/future until implemented and smoked.

Guest communication follows `docs/COMMUNICATION_MODEL.md`: Owner/Manager handle `BOOKING_CHAT`, `VENUE_CHAT` and own-venue `SUPPORT_TICKET`; Staff does not handle support/venue chats; `STAFF_CALL` remains operational. Booking lifecycle, queue, hold/deadline, reminders and booking chat behavior follow `docs/BOOKING_LIFECYCLE.md`. Telegram bot fallback, staff-chat management and callback behavior follow `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Owner permissions, staff/QR/settings/billing boundaries and dangerous-action expectations are governed by `docs/SECURITY_RBAC_MATRIX.md`. Public staff profiles, today shift and future staff-tip boundaries follow `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Venue operations are governed by `docs/VENUE_OPERATIONS.md`. Menu/options/stop-list policy follows `docs/MENU_OPTIONS_STOPLIST.md`. Order/session/tab behavior follows `docs/ORDER_SESSION_TAB_CORE.md`. Analytics/KPI rules follow `docs/ANALYTICS_EVENTS.md`. Testing/QA smoke strategy follows `docs/TESTING_QA_SMOKE_STRATEGY.md`. Release/deploy operations follow `docs/DEPLOYMENT_RUNBOOK.md`.

Runtime Venue Owner access is granted by an active `venue_members` row with role `OWNER`. Legacy/primary owner linkages such as `venues.owner_account_id` and `venue_owner_accounts.primary_owner_user_id` do not by themselves preserve Venue Mini App or Telegram Bot venue-owner access after the OWNER membership is revoked.

Owner может работать в двух поверхностях:
- Telegram bot - более полный setup/onboarding/editor flow;
- Venue Mini App - операционная панель с заказами, счётом, бронями и частью management screens.

## Telegram bot

Основные owner flows:
- создание/настройка заведения;
- профиль/карточка заведения;
- `📖 Фото-меню` как информационный section карточки;
- `🍽 Заказное меню` как structured menu: категории, позиции, цены, availability/stop-list;
- tables/QR;
- staff list/invites/roles;
- staff public profiles and `Сегодня на смене` management per `docs/STAFF_PROFILES_SHIFTS_TIPS.md`;
- staff chat link;
- booking settings, включая custom hold input;
- future `Акции и удержание`: simple venue promotions/banners with title, description, active period, terms and visibility/status; no automatic discount promise unless a real promo engine/accounting path is implemented;
- media upload для owner info sections без fallback spam;
- явная back-navigation по setup sections.

Важное naming rule:
- `📖 Фото-меню` - информационный text/media/PDF раздел внутри `ℹ️ Информация`;
- `🍽 Заказное меню` - structured ассортимент, который guest использует после QR/table context.

## Mini App

Venue Owner открывает Venue Mini App через inline `web_app` entry, чтобы Telegram передал `initData`.

Доступные owner areas:
- dashboard;
- orders queue/detail;
- full bill with human order display label, batches/doporders, personal/shared account context, discounts, service charges and excluded/non-payable items;
- bill controls: manual item discount, exclude/restore item;
- close bill/order;
- bookings queue/actions;
- `Сообщения` for `BOOKING_CHAT` and `VENUE_CHAT`;
- `Помощь` / `Обращения` for own-venue `SUPPORT_TICKET`;
- staff calls;
- shift-extension requests/settings;
- menu/availability management;
- tables/QR management;
- staff management where implemented;
- public staff profile publish/hide and today-shift management;
- staff chat link/status;
- subscription/payment state screen with adjusted paid-through and next-payment dates;
- future/partial `Акции и удержание` only when backend-backed; Staff must not manage campaigns;
- read-only stats;
- settings where implemented.

Mini App остаётся backend-RBAC enforced: кнопки в UI не являются security boundary.

## Allowed actions

- Управлять venue profile/card/info sections.
- Загружать и удалять media для info sections.
- Управлять `📖 Фото-меню` как info section.
- Управлять `🍽 Заказным меню`, категориями, позициями, ценами и availability.
- Управлять stop-list item/option availability.
- Управлять option groups/values, item media, featured/top-list and unavailable-display policy where implemented.
- Запускать shift check and mass availability actions where implemented, with confirmation/audit for dangerous changes.
- Управлять tables/QR, включая rotation/export where owner permission allows.
- Управлять staff list/invites/roles with last-owner protection.
- Управлять public staff profiles: create/edit, link to venue member or keep display-only, publish/hide and control guest visibility.
- Mark staff profiles as `Сегодня на смене`, with Manager participation under current conservative policy.
- Approve future external staff tip methods only after the Phase 2 spec/runtime exists.
- Подключать staff chat.
- Отвязывать staff chat through the owner-only Mini App flow after explicit confirmation.
- Смотреть и вести заказы, менять allowed statuses, закрывать счёт.
- Смотреть order detail with batches and tabs while preserving table-session boundaries.
- Управлять bill item adjustments: скидки, исключение, возврат позиции в счёт.
- Смотреть и вести брони.
- Подтверждать, отменять, переносить/предлагать новое время, писать гостю по брони и отмечать confirmed bookings arrived/no-show.
- Настраивать booking settings.
- Смотреть статистику.
- Смотреть Owner analytics where implemented: today/7d/30d orders, accepted/delivered batches, reliable revenue estimate, accept/deliver time, top items, stop-list pain points, staff calls, bookings and support ticket summary.
- Читать/отвечать/завершать/возобновлять booking conversation threads.
- Читать и отвечать на ordinary `VENUE_CHAT` from guests for this venue.
- Читать, отвечать, закрывать/reopen own-venue `SUPPORT_TICKET` and manually `Передать платформе` support tickets when the issue belongs to Platform.
- Создавать и управлять простыми `VENUE_PROMOTION` only after the backend-backed growth MVP exists; terms, period and visibility/status are mandatory, and promo notifications require guest opt-in.
- Смотреть своё subscription/payment state, включая adjusted paid-through and next-payment date, visible open invoices and human period copy.

## Denied actions

- Platform owner flows: platform venue lifecycle, subscription commercial terms, global connection requests.
- Manual mark-paid, courtesy/free days and Platform Owner billing cockpit controls.
- Доступ к hidden/deleted platform-level venue list без platform owner role.
- Bypass backend RBAC через прямые Mini App/API вызовы.
- Hard delete venue data without platform-level safety decision.
- Keep Venue Mode access after Platform Owner revokes this user's active OWNER membership for the venue.
- Mix orders/batches from different `table_session_id` values or treat staff chat as the source of truth for order state.
- Promise automatic discounts, cashback, points or promo-code redemption without a real promotion/loyalty engine and discount accounting.
- Send marketing/promo notifications to guests without opt-in, frequency limits and unsubscribe.
- See another venue's analytics or raw event payloads containing message text/initData/payment secrets/card data.
- Use staff tips to collect guest order payments through the platform.
- Treat future `staff_tip_intent` as proof of payment, close bill from it or mix it with venue subscription billing.
- Add provider/direct payout, Telegram Stars or crypto staff tips before separate legal/product decision.

## Known gaps / needs smoke

- Telegram owner setup остаётся богаче Mini App owner setup; часть настроек всё ещё bot-first.
- Mini App settings and staff management parity should be smoke-tested by role.
- Staff chat diagnostics/unlink polish is closed after M6 staging smoke; keep real Telegram group link/test/unlink and operational notification delivery in per-venue regression.
- Staff-call lifecycle, linked staff-chat notification delivery and ACK/DONE audit hardening are CLOSED / staging smoke passed for Venue Mini App and Telegram staff-chat surfaces. Applied ACK/DONE transitions leave audit evidence with actor user id and source; audit is best-effort.
- Row-level `acked_by` / `done_by` / ACK-DONE timestamp columns, CANCELLED UI/lifecycle and staff-call UX polish are not implemented in this milestone. Guest table-context cleanup/exit is CLOSED / staging smoke passed and belongs to the Guest role regression checklist.
- Menu options/photos/descriptions/top-list richness may still be partial depending on guest surface.
- Menu/options/stop-list spec is `UPDATED` in `docs/MENU_OPTIONS_STOPLIST.md`: selected-option parity is smoke-closed, while broader media/top-list/shift-check/audit coverage remains partial/future.
- Multi-venue owner selector/entry should be smoke-tested if owner has several memberships.
- Platform owner invite, owner revoke and ownership access management belong to Platform Owner flow, not this role doc.
- Platform Owner can add a Venue Owner by invite and can revoke one OWNER when another active OWNER remains; create/accept/revoke actions are audited.
- Manual billing cockpit/renewal/courtesy are staging-smoked: Venue Owner sees adjusted subscription state but cannot mark paid or add courtesy/free days.
- Primary/legal/billing owner relink and a full ownership transfer helper are separate Platform Owner milestones;
  current runtime access is controlled by active `venue_members` OWNER memberships.
- Guest Communication UX split is CLOSED / smoke passed: Owner/Manager can handle ordinary venue chats separately from support tickets, support transfer to Platform is explicit, and neither `VENUE_CHAT` nor `SUPPORT_TICKET` posts to staff-chat. SLA automation, macros, CSAT, attachments and diagnostics remain future support follow-ups.
- Order/session/tab core is `SPEC UPDATED` in `docs/ORDER_SESSION_TAB_CORE.md`: queue may group by table, but detail must preserve batches/tabs/session boundaries; force close should require reason/audit if implemented.
- Venue operations spec is `UPDATED` in `docs/VENUE_OPERATIONS.md`: Owner dashboard, orders, tabs/bill, staff calls, bookings, menu/stop-list, tables/QR, staff/invites, staff-chat, settings, stats and operational smoke are canonical.
- Booking lifecycle spec is `UPDATED` in `docs/BOOKING_LIFECYCLE.md`: Owner booking queue actions, confirmed-only arrival actions, hold settings, reminders, booking chat, support routing, staff-chat boundaries and no-show/seated visit dependencies are canonical.
- Telegram fallback/staff-chat spec is `UPDATED` in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`: Owner staff-chat link/test/unlink, Telegram operational menus, state-aware booking callbacks, callback RBAC and notification allow/deny policy are canonical.
- Testing/QA smoke strategy is `UPDATED` in `docs/TESTING_QA_SMOKE_STRATEGY.md`: Owner/Venue operational changes require targeted validation, role smoke and staging smoke when runtime behavior changes.
- Analytics/events are `SPEC UPDATED / PARTIAL` in `docs/ANALYTICS_EVENTS.md`: Owner dashboards should use reliable server-side events; advanced growth metrics and arbitrary analytics remain future.
- Growth/retention is `SPEC UPDATED / PARTIAL-FUTURE`: simple venue promotions, favorite/history/repeat loops and post-visit feedback need implementation and staging smoke before being called complete. Staff remains excluded from growth campaign management.
- Staff profiles / today shift are `MVP DONE / LOCAL SMOKE-PASSED`: Owner is the approver for public visibility and profile publish/hide; Staff may edit only own linked draft fields if policy allows. Photo upload, schedule and staff tips remain future.
- Staff tips are `SPEC DRAFT / FUTURE`: Phase 2 external staff tip link + intent only, no platform-collected money; provider/direct payout needs legal/product decision.

## Smoke-critical checks

1. Owner bot setup shows separate `🍽 Заказное меню` and `📖 Фото-меню`.
2. Upload multiple media files in info section; state remains stable until `Готово`/`Назад`.
3. Guest sees filled `📖 Фото-меню` through `ℹ️ Информация`.
4. Owner opens Venue Mini App through inline `web_app`; auth succeeds with initData.
5. Owner can view queue/detail/full bill and close bill.
6. Owner can apply/remove manual discount and exclude/restore bill item.
7. Owner can manage bookings/menu/tables/staff according to current UI and backend permissions.
8. Owner can open `Сообщения`, reply to `BOOKING_CHAT` / `VENUE_CHAT` and use resolve/reopen without changing booking lifecycle.
9. Owner can open `Помощь` / `Обращения`, reply to own-venue support tickets and use `Передать платформе` only for support tickets.
10. Owner can open `Статистика`.
11. Owner can manage staff chat link/status/test flow, copy or regenerate an active link code, and safely unlink an incorrect staff chat binding.
12. Owner can accept/close staff calls; linked Telegram staff group receives Mini App-created staff-call notification and staff-call ACK/DONE audit rows include actor evidence during regression smoke.
13. Owner opens subscription screen and sees adjusted `Оплачено до ... включительно` / next-payment state when billing/courtesy exists.
14. Owner sees visible open invoice/payment state where allowed, but cannot mark invoice paid or add courtesy/free days.
15. Owner order queue can group by table, while detail shows separate batches and tabs; closing/force-closing order/session does not allow new batches into the old active order and requires reason/audit where implemented.
16. Owner menu smoke follows `docs/MENU_OPTIONS_STOPLIST.md`: create category/item/options, change availability, verify guest stale-submit rejection, and verify price/name/options snapshots in old orders.
17. Phase 1 staff profile smoke: Owner creates display-only and linked profiles, publishes/hides public visibility, marks `Сегодня на смене`, and guest sees only public visible profiles/shifts without `linked_user_id` or private contact data.

Future Growth/retention checks:

18. Owner/Manager can create a simple promotion with title, description, active period, terms and visibility/status.
19. Promotion is visible to guests only during the active period and not visible when hidden/suspended.
20. Promotion copy does not imply automatic discount unless the promo engine is implemented.
21. Staff cannot see or manage `Акции и удержание`.
