# Guest

Дата актуализации: 2026-07-07.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. Этот файл фиксирует текущее поведение Guest в Telegram bot и Mini App после последних parity/fix-pack'ов.

## Current status

Guest - пользователь без venue-ролей. Основной продуктовый приоритет: каталог, карточка заведения, бронь, QR/table order flow, staff call, сообщения с заведением и просмотр своего заказа/счёта.

Canonical communication split: see `docs/COMMUNICATION_MODEL.md`. Guest-facing labels are `Чаты` for venue conversations and `Помощь` for support tickets/problems. Guest booking lifecycle, hold/deadline, reminders and booking chat behavior are governed by `docs/BOOKING_LIFECYCLE.md`. Telegram QR entrypoints, fallback order and bot staff-call behavior are governed by `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Guest permissions, table/session/tab scope and trust boundaries are governed by `docs/SECURITY_RBAC_MATRIX.md`. Structured menu/options/stop-list behavior is governed by `docs/MENU_OPTIONS_STOPLIST.md`. Public staff profiles, today's visible staff and future staff-tip boundaries are governed by `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Order/session/tab behavior is governed by `docs/ORDER_SESSION_TAB_CORE.md`. Analytics/event rules are governed by `docs/ANALYTICS_EVENTS.md`. Testing/QA smoke strategy is governed by `docs/TESTING_QA_SMOKE_STRATEGY.md`. Release/deploy operations are governed by `docs/DEPLOYMENT_RUNBOOK.md`. Guest growth/retention scope is tracked separately in `docs/GROWTH_RETENTION.md`; favorites/history/repeat/feedback/promotions must not be called complete without dedicated implementation evidence and smoke.

Ключевое разделение:
- **до QR / без table context** guest видит каталог и информацию о заведении, но не видит заказное structured menu;
- **до QR / без table context** guest can ask a venue a normal question from catalog card `Задать вопрос` or venue detail `💬 Задать вопрос`; this opens/reuses `VENUE_CHAT`, not a support ticket;
- **до QR / pre-visit venue card** guest видит address, route/copy address and booking actions;
- **после QR / active table context** guest получает заказное меню, корзину, дозаказы, staff call и active order; route/copy address/booking actions and primary `Связаться с заведением` are not prominent there because live table requests use `Вызвать персонал`.

## Telegram bot

До QR / без стола:
- каталог заведений;
- карточка заведения;
- `ℹ️ Информация`;
- внутри информации: `О заведении`, правила, пробковый сбор, FAQ, `📖 Фото-меню`, custom-разделы;
- guest card больше не показывает отдельную кнопку заказного `🍽 Меню`;
- legacy callback `bot_catalog_venue_menu:{venueId}` должен показывать безопасное объяснение, что заказное меню доступно после QR.

После QR / table context:
- structured order menu с категориями/позициями/ценами;
- item-scoped option/flavor selection through structured `selectedOptionId`;
- корзина;
- отправка заказа или дозаказа;
- staff call;
- active order / счёт;
- table session/tab context используется для scoped заказа.
- `🚪 Завершить визит` завершает только текущий Telegram chat/user context. Если у текущего guest есть active order/bill или active `NEW`/`ACK` staff call в этом table session, бот объясняет, что сначала нужно закрыть счёт или дождаться персонала.

## Mini App

Pre-QR Guest Mini App:
- карточка заведения показывает safe guest-visible данные;
- after Phase 1, venue detail may show `Сегодня работают` with public visible staff profiles only; catalog may later show a compact `Сегодня: Иван, Алина` line;
- `ℹ️ Информация` отдаёт только visible + filled owner info sections;
- `section_type=menu` отображается как `📖 Фото-меню`;
- media из info sections открываются через backend proxy, без raw Telegram URL и без bot token во frontend;
- structured order menu не запрашивается и не показывается без active table context;
- add-to-cart/order actions недоступны без table context.

QR/table Guest Mini App:
- table token resolve и active `TABLE_SESSION`;
- structured order menu;
- option/flavor picker for configured item-scoped options and line-level `preferenceNote`;
- cart preview backend-owned;
- submit order/add batch into the current active table order/session and selected personal/shared tab;
- fallback chat order emits `Telegram.WebApp.sendData` payload `{ "cmd": "start_quick_order", "table_token": "<tableToken>" }` and must follow the same session/tab rules;
- active order screen с refresh/polling, human order label `Заказ №...`, selected account label `Личный счёт` / `Общий счёт`, selected-tab bill totals, discounts and service charges; guest must not see another guest's personal tab;
- active order screen exposes `Попросить счёт` with on-site operational payment note choices `Картой на месте`, `Наличными`, `Пока не знаю`; backend dedupes active `NEW`/`ACK` bill requests for the current user/tab and staff sees order/account/total/payment context on the live staff-chat order activity card when available, with standalone fallback only when the card cannot be loaded. This is not online payment/acquiring/Telegram Payments/Stars and does not close the bill automatically;
- staff call flow as transient compose + compact NEW/ACK/DONE status;
- `Продление работы заведения` hidden without active order/bill or unavailable extension state, visible only when current active order/bill state makes it actionable, and hidden again after bill/order close;
- `🚪 Завершить визит` в active table context очищает только текущий guest restore/local context и возвращает в обычный каталог. Empty personal tab/no order allows exit; active order/bill or active `NEW`/`ACK` staff call blocks with clear copy.
- закрытый счёт больше не должен выглядеть активным.
- expired/closed table context should show safe copy such as `Отсканируйте QR на столе заново.`
- Guest-visible account labels must not expose raw `tabId`; technical ids remain internal/request-only.

Account/bookings:
- guest booking MVP присутствует: create/list active bookings/status refresh, cancel/accept changed time where supported;
- M7b Guest Mini App `Мои брони` implemented with staging visual parity: account-level list shows active/upcoming bookings across venues with bot-compatible public `Бронь №...`, venue-local date/time, status, party size, comment and `Держим до HH:mm` when applicable; recorded staging evidence compares Bot `/my` and Guest Mini App public label, venue-local time and deadline, while real two-account Telegram runtime isolation remains unverified;
- account baseline включает history/favorites sections;
- Growth/retention target UX:
  - `Избранное` in catalog/card for favorite venues;
  - `История` as visits, bookings and closed orders, not raw technical ids;
  - `Повторить` as a template applied only on the next verified table context;
  - `Оценить` only after confirmed visit;
  - `Акции` with clear period and terms;
  - promo/retention notifications only after opt-in.
- `Чаты` показывает persisted `BOOKING_CHAT` and `VENUE_CHAT` threads with context/status/unread and active/resolved filters. Copy must explain that problems and complaints are in `Помощь`.
- `Помощь` / `Мои обращения` shows `SUPPORT_TICKET` only. Technical/Mini App/QR/platform issues can be submitted without venue; order/service and booking categories require verified venue/booking/table context.
- full growth/retention remains partial/future: profile/promotions/loyalty may show safe fallback only when backend does not provide a complete, smoked product flow.

## Allowed actions

- Смотреть каталог и published guest-visible venues.
- Открывать карточку заведения и заполненные visible info sections.
- Смотреть public visible staff profiles and `Сегодня работают` after Phase 1.
- Смотреть `📖 Фото-меню` как информационный media/text section.
- Создавать и отменять свои бронирования, если booking flow доступен.
- Задать вопрос заведению до визита через catalog card or venue detail; existing ordinary guest+venue chat is reused.
- После QR/table context смотреть заказное меню, собирать корзину, отправлять заказ/дозаказ.
- Добавлять batch только в свой personal tab or joined shared tab in the current table session.
- Выбирать item-scoped option/flavor where configured and add optional line-level preparation preference in Mini App.
- Получать безопасную ошибку при stale cart submit, если позиция или выбранный вариант стали недоступны после добавления в корзину.
- Вызывать персонал из active table context.
- Читать и отвечать на свои `BOOKING_CHAT`, `VENUE_CHAT` and `SUPPORT_TICKET` threads.
- Создавать свои support tickets through `Помощь`; venue-related order/service and booking categories require verified venue/booking context.
- Смотреть свой active/current order и backend-owned счёт.
- Запросить счёт по своему active order/tab and choose an on-site payment note for staff.
- Завершать свой table context, если нет активного счёта/обязательств и активного вызова персонала.
- Пользоваться account/favorites/history baseline, где он доступен; full `FAVORITE_VENUE`, `VISIT_HISTORY`, `ORDER_HISTORY`, `BOOKING_HISTORY`, `REPEAT_TEMPLATE`, `POST_VISIT_FEEDBACK`, `VENUE_PROMOTION` and `OPT_IN_NOTIFICATION` behavior remains governed by `docs/GROWTH_RETENTION.md`.

## Denied actions

- Делать заказ до QR/table context.
- Видеть structured order categories/items в pre-QR venue card.
- Получать hidden info sections или hidden media.
- Получать raw Telegram `file_id`, raw Telegram file URL или bot token.
- Управлять меню, stop-list, столами, персоналом, счетами, скидками, venue settings или platform features.
- Получать marketing/promo notifications без явного opt-in или после unsubscribe.
- Создавать order через `Повторить` без active table context/current tab/menu validation.
- Видеть чужие bookings/orders/tabs.
- Добавлять batch в чужой personal tab or shared tab without membership.
- Заказывать hidden/archived/unavailable menu item or option value.
- Доверять client-side price; итоговая цена и option deltas считаются server-side.
- Видеть чужие chats/support tickets.
- Завершать или скрывать чужой table context/session за тем же физическим столом.
- Видеть `linked_user_id`, private Telegram username/id, phone/email or hidden staff profiles/shifts.
- Считать future `staff_tip_intent` подтверждением оплаты или оплатой счёта.
- Видеть operator analytics dashboards; Guest gets only profile/history-facing summaries later.

## Known gaps / needs smoke

- M7b `Мои брони` still needs real two-account Telegram runtime isolation smoke; local tests/e2e and staging Bot `/my` visual parity for the same booking's label/time/deadline are already green.
- M7c adaptive booking reminders passed one controlled real Telegram staging smoke but remain disabled by default for rollout; `Да, буду` records attendance intent without changing booking status, edits the guest reminder message, and Guest Mini App shows `Вы подтвердили, что придёте` when recorded.
- Booking lifecycle spec is `UPDATED` in `docs/BOOKING_LIFECYCLE.md`: guest create/list/cancel/proposed-time response, `Держим до`, reminders, `BOOKING_CHAT`, booking support routing and no-show/seated history dependencies are canonical.
- Telegram fallback/staff-chat spec is `UPDATED` in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`: Guest QR `/start`, table context, fallback chat order, bot staff-call, `Чаты`/`Помощь` handoff and staff-chat allow/deny policy are canonical.
- Testing/QA smoke strategy is `UPDATED` in `docs/TESTING_QA_SMOKE_STRATEGY.md`: Guest-facing changes require matching backend/Mini App/Telegram validation and manual smoke according to change type.
- Полная guest profile/promotions/loyalty parity остаётся частичной.
- Favorites/history есть как baseline, но full growth/retention MVP remains `SPEC UPDATED / PARTIAL-FUTURE` until staging smoke proves favorite/unfavorite, visit/order/booking history, repeat template, post-visit feedback, simple promotions and opt-in notification behavior.
- M4B/M4C `Сообщения` staging smoke passed; keep thread scoping, unread and resolve/reopen lifecycle in regression.
- Guest Communication UX split is CLOSED / smoke passed: global `Чаты`, global `Помощь`, catalog/venue-detail `Задать вопрос`, `VENUE_CHAT` reuse, support ticket context routing and table-context staff-call separation stay in regression. Advanced support features such as SLA automation, attachments, macros, CSAT and diagnostics reports remain future work.
- M5 staff-call compact UX staging smoke passed; `tableSessionId` payload, backend persistence and staff-chat event/enqueue are CLOSED / code-test verification passed. Staff-chat activity-card polish also passed: DONE/CANCELLED generic calls no longer stay active in `Оперативно`, and linked closed-visit call leftovers are resolved on order/bill close.
- Fallback quick-order payload is CLOSED / code-test verification passed; real Telegram client fallback remains part of release smoke.
- Media proxy требует ручной проверки для image/PDF и скрытых/удалённых sections.
- QR/table order flow должен smoke-тестироваться отдельно от pre-QR catalog flow.
- QR/table exit flow is CLOSED / staging smoke passed and should stay in regression: one guest exits, another guest at the same physical table remains in their own context; explicit QR scan re-enters after exit.
- Guest bill request / payment method UX is CLOSED / staging smoke passed; payment choices are structured, active duplicate requests do not spam staff chat and no online payment provider was added.
- Analytics/events are `SPEC UPDATED / PARTIAL` in `docs/ANALYTICS_EVENTS.md`; Guest-facing analytics is limited to future profile/history summaries, while client events remain low-trust UX diagnostics.
- Menu/options/stop-list model is `SPEC UPDATED` in `docs/MENU_OPTIONS_STOPLIST.md`: selected-option parity is smoke-closed, but broader modifier/media/top-list/shift-check coverage remains partial/future.
- Real acquiring provider, Telegram Stars and automatic recurring payments remain future work; guest bill request is still an on-site operational request, not online payment.
- Staff profiles / today shift are `SPEC READY / FUTURE-NEXT` in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`; runtime Guest UI/API must not be called complete until public visibility, hidden-profile denial and privacy smoke pass.
- Staff tips are future: Phase 2 may use external staff tip link + intent, but no money touches the platform in MVP and intent is not proof of payment.
- Booking changed-time/accept status зависит от backend support и должен проверяться по статусам.

## Smoke-critical checks

1. Открыть каталог без QR: карточка venue не показывает заказное `🍽 Меню`.
2. Открыть `ℹ️ Информация`: видны только заполненные visible sections.
3. Проверить `📖 Фото-меню`: изображения/PDF открываются через Mini App.
4. Сканировать QR: structured order menu появляется только после table context.
5. Добавить item в cart, отправить order/add batch.
6. Выбрать configured flavor/option and optional preference note; confirm active order/bill preserves it.
7. Вызвать персонал из active table; форма закрывается, status card показывает NEW/ACK/DONE.
8. Без active order/bill/staff call нажать `Завершить визит`; повторное открытие Mini App не должно restore table context, повторный QR scan должен re-enter. Request contract stays `POST /api/guest/table/session/end` with JSON `Content-Type` and `{ tableToken, tableSessionId }`.
9. С active order/bill или active NEW/ACK staff call нажать `Завершить визит`; exit blocked with clear copy and table context remains.
10. Нажать fallback `Оформить в чате`; payload должен быть `cmd=start_quick_order` with current `table_token`.
11. Открыть `Чаты`; проверить `BOOKING_CHAT` / `VENUE_CHAT` list, unread, active/resolved filters and resolve/reopen where allowed.
12. From catalog card and venue detail press `Задать вопрос` / `💬 Задать вопрос`; confirm `Чат с <venueName>` opens and no support ticket or staff-chat message is created.
13. Открыть `Помощь`; create technical support without venue, and verify order/service support outside table asks for a venue.
14. Открыть `Профиль → Мои брони`; проверить multi-venue cards, public `Бронь №...`, venue-local `Держим до`, перенос и отмену.
15. Обновить active order: новые batches, скидки, исключения и closed state отображаются из backend.
16. На active order screen нажать `Попросить счёт`, выбрать `Картой на месте` / `Наличными` / `Пока не знаю`, проверить JSON request contract, guest confirmation, duplicate active request copy and staff notification context.
17. Verify order/session/tab core from `docs/ORDER_SESSION_TAB_CORE.md`: first and second batches stay in the same active table session/order with separate batches; second guest at the same table gets own personal tab and cannot see the first guest personal tab; joined shared tab is visible only after consent; expired session shows `Отсканируйте QR на столе заново.`
18. Verify menu/options/stop-list core from `docs/MENU_OPTIONS_STOPLIST.md`: unavailable item/option is hidden or disabled by venue policy, stale cart submit is rejected safely, and selected option names/prices remain visible in the resulting order/bill snapshot.

Future Growth/retention smoke from `docs/GROWTH_RETENTION.md`:

19. Favorite and unfavorite a venue; verify it appears/disappears in favorite venues.
20. Open history and verify visits, closed orders and bookings are shown with safe labels.
21. Use `Повторить`; confirm it creates only a repeat template and requires the next table context before order creation.
22. Confirm unavailable/stopped items are skipped or clearly marked during repeat template application.
23. Confirm feedback is requested only after a confirmed visit and low ratings do not auto-open a public review link.
24. Confirm promotions show active period/terms, hidden/suspended promotions are absent, and marketing notifications require opt-in plus unsubscribe.
