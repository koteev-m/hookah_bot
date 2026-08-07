# Venue Owner

Дата актуализации: 2026-08-07.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. Этот файл разделяет Telegram bot owner setup flow и Venue Mini App owner panel.

## Current status

Venue Owner - главный владелец конкретного заведения. Он управляет карточкой, заказным меню, столами/QR, персоналом, staff chat, бронями, сообщениями с гостями, статистикой и операционными заказами. Growth/retention is governed by `docs/GROWTH_RETENTION.md`: Post-Visit Feedback and its public-review/follow-up smoke-fix are closed, while favorites/repeat/promotions and broader retention remain partial/future.

Current staff membership correction: **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL
AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Owner alone changes/removes same-venue memberships
with last-owner protection and transaction-bound targeted audit. Removal revokes venue access but
preserves profiles/history/audit; reconnection uses the ordinary Staff invite flow.

Guest communication follows `docs/COMMUNICATION_MODEL.md`: Owner/Manager handle `BOOKING_CHAT`, `VENUE_CHAT` and own-venue `SUPPORT_TICKET`; Staff does not handle support/venue chats; `STAFF_CALL` remains operational. Booking lifecycle, queue, hold/deadline, reminders and booking chat behavior follow `docs/BOOKING_LIFECYCLE.md`. Telegram bot fallback, staff-chat management and callback behavior follow `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Owner permissions, staff/QR/settings/billing boundaries and dangerous-action expectations are governed by `docs/SECURITY_RBAC_MATRIX.md`. Public staff profiles, today shift and future staff-tip boundaries follow `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Venue operations are governed by `docs/VENUE_OPERATIONS.md`. Menu/options/stop-list policy follows `docs/MENU_OPTIONS_STOPLIST.md`. Order/session/tab behavior follows `docs/ORDER_SESSION_TAB_CORE.md`. Analytics/KPI rules follow `docs/ANALYTICS_EVENTS.md`. Testing/QA smoke strategy follows `docs/TESTING_QA_SMOKE_STRATEGY.md`. Release/deploy operations follow `docs/DEPLOYMENT_RUNBOOK.md`.

Current Staff Operations correction: Staff Profiles/Today, Slice A, Identity Linking, Staff
Schedule Phase 1 and Canceled Shift Restore + Bulk Assignment are `DONE / MVP /
STAGING-SMOKE-PASSED`. Owner manages the bounded schedule and is the only role that repairs a
duplicate link: open both real cards in the common list, safely unlink only the wrong card and keep
the correct link plus all schedule/history rows. No automatic merge/delete/relink is allowed.
`STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE /
DONE / MVP / STAGING-SMOKE-PASSED`: Owner manages the dedicated full-object settings with CAS,
can re-enable retained profiles/shifts, and sees an explanation rather than deleted data while the
module is off. This does not alter the earlier Staff closures.

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
- public review URL setting shared with Venue Mini App; it does not enable Telegram feedback prompts or auto-redirects;
- booking settings, включая custom hold input;
- future `Акции и удержание`: simple venue promotions/banners with title, description, active period, terms and visibility/status; no automatic discount promise unless a real promo engine/accounting path is implemented;
- image/PDF upload for info sections through Bot, with individual attachment delete and
  whole-section hide/show; storage uses Telegram `file_id`, and there is no direct replace or
  per-attachment hide action;
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
- `Отзывы` with read-only aggregate/list and manual low-rating `Связаться с гостем`;
- staff calls;
- shift-extension requests/settings;
- menu/availability management, including **MENU SHIFT CHECK PHASE 1 / DONE / MVP /
  STAGING-SMOKE-PASSED** for the own venue;
- tables/QR management;
- staff management where implemented;
- public staff profile publish/hide and today-shift management;
- `График смен` with create/update/cancel, same-row canceled restore and atomic bulk
  assignment;
- dedicated `Команда и график смен` settings with master toggle, Guest visibility,
  `MANUAL`/`SCHEDULE` source and full-object CAS;
- staff chat link/status;
- subscription/payment state screen with adjusted paid-through and next-payment dates;
- future/partial `Акции и удержание` only when backend-backed; Staff must not manage campaigns;
- read-only stats;
- read-only `Предпросмотр для гостя`: one backend-selected `PUBLISHED_PUBLIC` /
  `PRIVATE_DRAFT` endpoint is **DONE / MVP / STAGING-SMOKE-PASSED**;
- settings where implemented.

`Ссылка для отзывов` is Owner-only and shared by Bot/Mini App. Venue Settings shows:
- `Где взять ссылку: откройте карточку заведения в Яндекс.Картах, нажмите «Поделиться» и скопируйте ссылку. Если у вас есть доступ к Яндекс Бизнесу, лучше взять ссылку на форму отзывов в разделе «О компании» → «Промоматериалы».`
- `Не обещайте скидки или бонусы за отзыв и не просите поставить конкретную оценку.`

Mini App остаётся backend-RBAC enforced: кнопки в UI не являются security boundary.

## Allowed actions

- Управлять venue profile/card; info-section authoring/media management is currently Bot-first.
- Через Bot загружать image/PDF и удалять attachments для info sections, а также скрывать/показывать
  весь section. Venue Mini App media upload/manage is not implemented.
- Управлять `📖 Фото-меню` через Bot как flat view-only info section.
- Управлять `🍽 Заказным меню`, категориями, позициями, ценами и availability.
- Управлять stop-list item/option availability.
- Управлять option groups/values, item media, featured/top-list and unavailable-display policy where implemented.
- Запускать own-venue shift check: use ordinary availability switches or the separate mass-selection
  mode, keep changes in a local draft, review the confirmation summary and send one atomic batch.
- Управлять tables/QR, включая rotation/export where owner permission allows.
- Управлять staff list/invites/roles with last-owner protection.
- Управлять public staff profiles: create/edit, link to venue member or keep display-only, publish/hide and control guest visibility.
- In source `MANUAL`, mark staff profiles as `Сегодня на смене`, with Manager participation under
  current conservative policy; in `SCHEDULE`, the manual control is unavailable and direct
  mutation fails safely.
- Manage bounded Staff Schedule, including explicit canceled restore and atomic bulk assignment.
- Manage the dedicated Staff Module settings; module-off retains profiles/shifts/nested settings
  and core membership/invite/role/operational access, while re-enable restores the saved module
  data without duplicates.
- Repair duplicate profile linkage through Owner-only safe unlink of the wrong concrete card; never
  merge/delete/relink automatically.
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
- Смотреть own-venue feedback and manually open exact `VENUE_CHAT` follow-up with rating/tags/comment/date context for ratings `1..3`.
- Save/update/clear the safe public review URL used by both Bot and Mini App.
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
- Guest-visible `CANCELLED` terminal status is CLOSED / staging smoke passed for the current guest/tableSession. Venue active queue remains `NEW` / `ACK`; manual cancel UI, row-level `acked_by` / `done_by` / ACK-DONE timestamp columns and staff-call UX polish remain future. Guest table-context cleanup/exit is CLOSED / staging smoke passed and belongs to the Guest role regression checklist.
- Menu options/photos/descriptions/top-list richness may still be partial depending on guest surface.
- Venue/public-card media management is `PARTIAL / BOT-FIRST`: Guest and `PUBLISHED_PUBLIC` render
  guarded media, while `PRIVATE_DRAFT` uses authenticated scoped delivery without raw refs. Venue
  Mini App still has no file picker, upload endpoint, replace, hide/show or delete UI.
- Structured menu item photos/descriptions/thumbnails and option/flavor media are
  `MISSING / FUTURE`; do not confuse them with the working view-only `📖 Фото-меню`.
- Menu/options/stop-list spec is `UPDATED` in `docs/MENU_OPTIONS_STOPLIST.md`: selected-option
  parity and Menu Shift Check Phase 1 are staging-smoke-passed; broader media/top-list and
  dangerous-action audit coverage remains partial/future.
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
- Growth/retention is `SPEC UPDATED / PARTIAL-FUTURE` overall. Post-Visit Feedback, manual `5/5` public review CTA and low-rating exact `VENUE_CHAT` follow-up are DONE / MVP / staging-smoke-passed. Favorites, repeat and simple promotions remain future; Staff remains excluded from feedback and growth campaign management.
- Staff profiles/Today, identity linking and Staff Schedule are `DONE / MVP /
  STAGING-SMOKE-PASSED`: Owner manages visibility, schedule and Owner-only duplicate repair. Photo
  upload and staff tips remain future. Slice B is also `DONE / MVP / STAGING-SMOKE-PASSED` with
  Owner settings/CAS, module-off retention, MANUAL persisted control and active-only SCHEDULE
  projection in regression.
- Staff tips are `SPEC DRAFT / FUTURE`: Phase 2 external staff tip link + intent only, no platform-collected money; provider/direct payout needs legal/product decision.

## Smoke-critical checks

1. Owner bot setup shows separate `🍽 Заказное меню` and `📖 Фото-меню`.
2. Upload multiple media files in info section; state remains stable until `Готово`/`Назад`.
3. Guest sees filled `📖 Фото-меню` through `ℹ️ Информация`.
4. Owner opens unified Guest Preview in `PUBLISHED_PUBLIC` and confirms exact real-Guest parity, no
   guest mutations and no stale data after venue switching.
5. Owner opens `PRIVATE_DRAFT` and confirms the saved-state banner/copy, guest-safe fields,
   authenticated scoped visible media, private-field absence and no raw refs.
6. Owner opens Venue Mini App through inline `web_app`; auth succeeds with initData.
7. Owner can view queue/detail/full bill and close bill.
8. Owner can apply/remove manual discount and exclude/restore bill item.
9. Owner can manage bookings/menu/tables/staff according to current UI and backend permissions.
10. Owner can open `Сообщения`, reply to `BOOKING_CHAT` / `VENUE_CHAT` and use resolve/reopen without changing booking lifecycle.
11. Owner can open `Помощь` / `Обращения`, reply to own-venue support tickets and use `Передать платформе` only for support tickets.
12. Owner can open `Статистика`.
13. Owner can manage staff chat link/status/test flow, copy or regenerate an active link code, and safely unlink an incorrect staff chat binding.
14. Owner can accept/close active `NEW` / `ACK` staff calls; linked Telegram staff group receives Mini App-created staff-call notification and staff-call ACK/DONE audit rows include actor evidence during regression smoke. Terminal `CANCELLED` is not active work.
15. Owner opens subscription screen and sees adjusted `Оплачено до ... включительно` / next-payment state when billing/courtesy exists.
16. Owner sees visible open invoice/payment state where allowed, but cannot mark invoice paid or add courtesy/free days.
17. Owner order queue can group by table, while detail shows separate batches and tabs; closing/force-closing order/session does not allow new batches into the old active order and requires reason/audit where implemented.
18. Owner menu smoke follows `docs/MENU_OPTIONS_STOPLIST.md`: create category/item/options, change
    availability, and keep the passed shift-check two-accordion UX, local draft/mass mode, atomic
    summary confirmation, stale rejection, safe audit, Guest stale-submit and venue isolation in
    regression; verify price/name/options snapshots in old orders.
19. Staff Operations smoke: Owner creates display-only/linked profiles, publishes/hides and marks
    manual Today; creates/edits/cancels/restores/bulk-assigns shifts; sees both duplicate cards and
    safely unlinks only the wrong one. Owner saves the dedicated module settings with CAS; module
    off retains data/core access and re-enable restores it. Guest receives only the exact MANUAL or
    current active SCHEDULE public projection, never fallback, linkage identity or future/full
    schedule.
20. Owner opens `Ссылка для отзывов`, sees the Yandex Maps/Yandex Business helper plus ethical hint, and saves/clears the same URL used by Bot and Mini App.
21. Owner sees only own-venue feedback and `Связаться с гостем` only for ratings `1..3`.
22. Follow-up opens exact `VENUE_CHAT` with `Отзыв после визита` context; active chat is reused and a closed/resolved old chat leads to a new active chat.
23. No personal message is sent until Owner writes; feedback follow-up creates no support ticket or staff-chat notification.

Future Growth/retention checks:

22. Owner/Manager can create a simple promotion with title, description, active period, terms and visibility/status.
23. Promotion is visible to guests only during the active period and not visible when hidden/suspended.
24. Promotion copy does not imply automatic discount unless the promo engine is implemented.
25. Staff cannot see or manage `Акции и удержание`.
