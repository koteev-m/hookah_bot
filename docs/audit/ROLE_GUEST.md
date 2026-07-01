# Guest

Дата актуализации: 2026-07-01.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. Этот файл фиксирует текущее поведение Guest в Telegram bot и Mini App после последних parity/fix-pack'ов.

## Current status

Guest - пользователь без venue-ролей. Основной продуктовый приоритет: каталог, карточка заведения, бронь, QR/table order flow, staff call, сообщения с заведением и просмотр своего заказа/счёта.

Ключевое разделение:
- **до QR / без table context** guest видит каталог и информацию о заведении, но не видит заказное structured menu;
- **до QR / pre-visit venue card** guest видит address, route/copy address and booking actions;
- **после QR / active table context** guest получает заказное меню, корзину, дозаказы, staff call и active order; route/copy address/booking actions are not prominent there.

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
- `ℹ️ Информация` отдаёт только visible + filled owner info sections;
- `section_type=menu` отображается как `📖 Фото-меню`;
- media из info sections открываются через backend proxy, без raw Telegram URL и без bot token во frontend;
- structured order menu не запрашивается и не показывается без active table context;
- add-to-cart/order actions недоступны без table context.

QR/table Guest Mini App:
- table token resolve и active table session;
- structured order menu;
- option/flavor picker for configured item-scoped options and line-level `preferenceNote`;
- cart preview backend-owned;
- submit order/add batch;
- fallback chat order emits `Telegram.WebApp.sendData` payload `{ "cmd": "start_quick_order", "table_token": "<tableToken>" }`;
- active order screen с refresh/polling, human order label `Заказ №...`, selected account label `Личный счёт` / `Общий счёт`, selected-tab bill totals, discounts and service charges;
- staff call flow as transient compose + compact NEW/ACK/DONE status;
- `Продление работы заведения` hidden without active order/bill or unavailable extension state, visible only when current active order/bill state makes it actionable, and hidden again after bill/order close;
- `🚪 Завершить визит` в active table context очищает только текущий guest restore/local context и возвращает в обычный каталог. Empty personal tab/no order allows exit; active order/bill or active `NEW`/`ACK` staff call blocks with clear copy.
- закрытый счёт больше не должен выглядеть активным.
- Guest-visible account labels must not expose raw `tabId`; technical ids remain internal/request-only.

Account/bookings:
- guest booking MVP присутствует: create/list active bookings/status refresh, cancel/accept changed time where supported;
- M7b Guest Mini App `Мои брони` implemented with staging visual parity: account-level list shows active/upcoming bookings across venues with bot-compatible public `Бронь №...`, venue-local date/time, status, party size, comment and `Держим до HH:mm` when applicable; recorded staging evidence compares Bot `/my` and Guest Mini App public label, venue-local time and deadline, while real two-account Telegram runtime isolation remains unverified;
- account baseline включает history/favorites sections;
- `Сообщения` показывает persisted support/booking threads with context/status/unread and active/resolved filters;
- profile/promotions/loyalty остаются partial или safe fallback, если backend не отдаёт полноценные данные.

## Allowed actions

- Смотреть каталог и published guest-visible venues.
- Открывать карточку заведения и заполненные visible info sections.
- Смотреть `📖 Фото-меню` как информационный media/text section.
- Создавать и отменять свои бронирования, если booking flow доступен.
- После QR/table context смотреть заказное меню, собирать корзину, отправлять заказ/дозаказ.
- Выбирать item-scoped option/flavor where configured and add optional line-level preparation preference in Mini App.
- Вызывать персонал из active table context.
- Читать и отвечать на свои support/booking threads.
- Смотреть свой active/current order и backend-owned счёт.
- Завершать свой table context, если нет активного счёта/обязательств и активного вызова персонала.
- Пользоваться account/favorites/history baseline, где он доступен.

## Denied actions

- Делать заказ до QR/table context.
- Видеть structured order categories/items в pre-QR venue card.
- Получать hidden info sections или hidden media.
- Получать raw Telegram `file_id`, raw Telegram file URL или bot token.
- Управлять меню, stop-list, столами, персоналом, счетами, скидками, venue settings или platform features.
- Видеть чужие bookings/orders/tabs.
- Завершать или скрывать чужой table context/session за тем же физическим столом.

## Known gaps / needs smoke

- M7b `Мои брони` still needs real two-account Telegram runtime isolation smoke; local tests/e2e and staging Bot `/my` visual parity for the same booking's label/time/deadline are already green.
- M7c adaptive booking reminders passed one controlled real Telegram staging smoke but remain disabled by default for rollout; `Да, буду` records attendance intent without changing booking status, edits the guest reminder message, and Guest Mini App shows `Вы подтвердили, что придёте` when recorded.
- Полная guest profile/promotions/loyalty parity остаётся частичной.
- Favorites/history есть как baseline, но должны проходить отдельный smoke на staging.
- M4B/M4C `Сообщения` staging smoke passed; keep thread scoping, unread and resolve/reopen lifecycle in regression.
- M5 staff-call compact UX staging smoke passed; `tableSessionId` payload, backend persistence and staff-chat event/enqueue are CLOSED / code-test verification passed. Linked Telegram staff-chat runtime notification remains per-venue regression.
- Completed DONE staff-call card may visually linger; treat dismiss-after-DONE as a small UX follow-up unless it confuses repeated calls.
- Fallback quick-order payload is CLOSED / code-test verification passed; real Telegram client fallback remains part of release smoke.
- Media proxy требует ручной проверки для image/PDF и скрытых/удалённых sections.
- QR/table order flow должен smoke-тестироваться отдельно от pre-QR catalog flow.
- QR/table exit flow is CLOSED / staging smoke passed and should stay in regression: one guest exits, another guest at the same physical table remains in their own context; explicit QR scan re-enters after exit.
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
11. Открыть `Сообщения`; проверить список тредов, unread, active/resolved filters and resolve/reopen.
12. Открыть `Профиль → Мои брони`; проверить multi-venue cards, public `Бронь №...`, venue-local `Держим до`, перенос и отмену.
13. Обновить active order: новые batches, скидки, исключения и closed state отображаются из backend.
