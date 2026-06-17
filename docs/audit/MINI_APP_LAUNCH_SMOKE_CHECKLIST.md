# Mini App Launch Smoke Checklist

Дата: 2026-06-08.

Цель: зафиксировать launch smoke/e2e coverage для core Mini App сценариев без изменения бизнес-логики. В `miniapp/package.json` есть `dev`, `build`, `preview` и минимальный browser smoke `e2e:smoke`. Поэтому стратегия на этот шаг гибридная:

- backend/API regression tests покрывают критичные контракты;
- `npm run build` покрывает TypeScript/Vite production build;
- `npm run e2e:smoke` покрывает browser-level Guest Mini App smoke с mocked Telegram initData/API context;
- ручной checklist покрывает Telegram WebApp runtime, `initData`, navigation и cross-channel parity.

Актуальный scope после последних fix-pack'ов:

- pre-QR Guest catalog/card shows only public venue info, booking entry and `ℹ️ Информация`; structured order menu is hidden until QR/table context.
- `📖 Фото-меню` is an info section, not the order menu.
- info-section images/PDFs are loaded through backend media proxy.
- Venue Owner/Manager/Staff Mini App entry must be opened through inline `web_app` buttons.
- STAFF can close bill/order and manage operational stop-list for menu items/options, but cannot edit discounts, exclusions, menu content/structure, tables, staff, settings or staff chat link.
- STAFF booking actions are operational only: view bookings and mark `Гость пришёл` / `Не пришёл`; confirm/cancel/change/message/settings are MANAGER/OWNER-only.
- STAFF booking RBAC split local smoke via `dev.hookahtootah.club` and staging deploy/smoke both passed on 2026-06-04.
- Pilot Smoke Fix Pack #1 staging re-smoke passed on 2026-06-04.
- Pilot Smoke Fix Pack #1.1 staging re-smoke passed on 2026-06-04; the previous P1 `Guest pre-QR endless "Загрузка информации..."` is resolved.
- CI release validation is green for the current release snapshot: backend ktlint, backend compile, split backend route/RBAC/Telegram/migration jobs, compose, Mini App build, backend Docker build and backend aggregate passed.
- Cross-channel bill snapshot automation covers Mini App full bill vs Telegram/staff bill totals for manual discounts, promo discounts, exclusions and restore.
- Live staff-chat order messages, bill-affecting refresh, button lifecycle, venue-local timestamps and main order vs doporders clarity passed staging smoke.
- Guest table session persistence/restore and Telegram BackButton navigation passed staging smoke on 2026-06-08: reopen without repeat QR restores table context, guest menu/order/profile/support navigation keeps context, and BackButton no longer loops.
- M2 Venue Mini App read-only `Статистика` passed staging smoke on 2026-06-16: OWNER/MANAGER see stats, periods work, cards/top items render, STAFF does not see stats, and empty state is safe.
- Platform owner lifecycle and commercial terms flows are in smoke scope.

## Current Staging Smoke Status

Status: `PASSED FOR CURRENT RELEASE` on 2026-06-04.

Confirmed:

- staging `/health`, `/db/health` and `/miniapp/` passed;
- Telegram Mini App opens with non-empty `initData`;
- Guest pre-QR venue card opens, text info renders and `Загрузка информации...` disappears;
- info-section image media renders through backend proxy or shows a safe empty/error state;
- PDF media shows `Открыть PDF` when PDF exists;
- empty/hidden info sections do not create endless loading;
- structured order menu remains hidden before QR/table context;
- table QR flow, category-first menu, cart comment draft, order flow and staff/manager/owner processing passed in affected smoke;
- booking action copy is honest as `Перенести бронь`, and booking notifications include venue name, human-readable time and cancellation reason/fallback;
- STAFF booking RBAC split remains passed: STAFF only sees arrival actions, MANAGER/OWNER keep management actions;
- venue selector shows venue names and Russian status labels;
- platform archived venue action copy is explicit that restore immediately publishes with current backend behavior.
- CI release validation passed for the current release snapshot: backend ktlint, backend compile, release-critical routes, venue booking/RBAC, Telegram lightweight tests, migration sanity, compose, Mini App build, backend Docker build and aggregate.
- Guest table session restore passed on staging on 2026-06-08: returning guest opens the active table context without rescanning QR, `Мой заказ` and adjacent guest screens keep `tableSessionId`/`tabId` context, and Telegram BackButton does not loop between screens.
- Venue Mini App M2 stats passed staging smoke on 2026-06-16: OWNER/MANAGER see `Статистика`, periods `Сегодня` / `7 дней` / `30 дней` work, stats cards and top items render, STAFF does not see stats, and empty state works safely.

Remaining:

- repeat this smoke after any additional release batch;
- P1 follow-up: paid venue/shift extension is implemented in backend and Guest/Venue Mini App, but Venue approve/reject is still a standalone `Продления` island; next target is order/table detail integration, staff-chat live message actions, Guest Bot entry and Owner/Manager Bot settings parity;
- P1 CLOSED: Guest/Menu Options & Flavors parity staging smoke passed. Guest Bot and Guest Mini App both submit structured selected options; Venue Mini App supports item-scoped hookah flavor CRUD, `Добавить базовые вкусы`, item-level stop-list and flavor-level stop-list. Keep this covered by regression tests for item scoping, unavailable option rejection and line-level preference notes.
- P1 CLOSED: Venue Mini App M2 read-only `Статистика` staging smoke passed. Keep periods, cards/top items, STAFF hidden state and empty state in regression.
- P1 next parity block: Venue Mini App bookings queue/lifecycle. Keep STAFF arrival/no-show split and MANAGER/OWNER booking management actions.
- P2 stats follow-up: custom date range picker (`from`/`to`), arbitrary period stats and future AI-generated summaries/insights.
- P2 follow-ups remain: owner hours/exceptions UX, optional `📖 Фото-меню` subsections, quieter owner multi-image upload, expand frontend/browser e2e beyond the minimal Guest smoke, richer Platform cockpit parity and optional lifecycle restore semantics if product wants restore to non-published state.

## 1. Automated Coverage Map

### Guest Mini App

Покрыто backend/API tests:

- `GuestOrderRoutesTest`
  - add batch creates active order;
  - idempotency key does not duplicate batch;
  - active order is scoped by `tableSessionId` and `tabId`;
  - two sessions at the same physical table do not leak active orders;
  - shared tab requires membership;
  - promo/loyalty preview and checkout behavior are covered by existing order tests.
- `GuestStaffCallRoutesTest`
  - `tableSessionId` is required;
  - successful Mini App staff call creates staff call;
  - staff chat notification receives `tableSessionId`;
  - invalid reason/comment and rate limit are rejected.
- `TelegramBotRouterTableTokenTest`
  - WebApp fallback sends supported `cmd=start_quick_order`.

Known option/flavor coverage:

- Guest Mini App smoke covers item option/flavor selection, selected option persistence in cart submission and line-level preference notes.
- Venue Mini App smoke covers item-level stop-list toggles, option/flavor-level stop-list toggles, item-scoped hookah flavor CRUD, shared hookah-only `Добавить базовые вкусы`, and the new hookah item empty state with `Добавить вкус`.
- Backend guest order tests cover selected option persistence, price delta, unavailable/foreign option rejection and distinct cart lines for the same item with different options.
- Backend guest menu tests must keep asserting that an option is returned only for its owning item and unavailable options stay hidden.
- Follow-ups: Mini App normalize/reset remains deferred unless pilots need it; DB-level duplicate/race protection for base flavor apply is optional if concurrent apply becomes an issue.

Manual runtime coverage for each release batch:

- Telegram opens Mini App with non-empty `Telegram.WebApp.initData`;
- pre-QR catalog does not expose structured order categories/items;
- info/photo-menu sections render text and media through backend proxy;
- guest sees table context;
- frontend sends `tableSessionId` in staff call payload.
- guest support screen opens and does not create fake ticket ids/statuses.

### Venue Mini App

Покрыто backend/API tests:

- `VenueOrderRoutesTest`
  - order list exposes `displayNumber`;
  - order detail exposes management bill DTO;
  - bill includes gross, manual discounts, promo discounts, loyalty discounts, excluded, canceled/rejected and final payable totals.
  - cross-channel bill snapshot compares Mini App full bill DTO with Telegram/staff bill totals for manual discounts, promo discounts, excluded items and restored items.
- `VenueOrdersRepositoryTest`
  - promotion breakdown is grouped by readable labels;
  - loyalty accrual/redemption side effects remain consistent when orders close.

Manual runtime coverage for each release batch:

- Venue Owner/Manager/Staff entry is sent as inline `web_app`, not plain URL;
- queue uses `Заказ №<displayNumber>`;
- order detail renders the user-facing bill block without technical copy;
- UI displays backend totals directly and does not invent frontend-calculated money.
- STAFF close bill/order works while bill edit controls stay hidden;
- venue support screen is informational and points operators to the manual platform support path.

### Platform Mini App

Покрыто backend/API tests:

- `PlatformRoutesTest`
  - only platform owner can access platform root/user list.
- `PlatformVenueRoutesTest`
  - non-owner denied;
  - platform owner can assign owner and publish only after owner membership;
  - owner invite creates owner access;
  - platform cockpit smoke: venue list/detail expose venue and subscription basics.
- `PlatformSubscriptionRoutesTest`
  - subscription settings are platform-owner-only;
  - pricing/date validation;
  - effective price uses override, schedule, then base price.

Manual runtime coverage for each release batch:

- `#/venues` opens platform cockpit;
- `PLATFORM_OWNER_TELEGRAM_ID` grants access without requiring a separate non-empty legacy owner id;
- safe sections `#/onboarding`, `#/placements`, `#/support`, `#/analytics` show explanations without fake data or dead-end controls.
- requests/commercial terms/create-link venue, suspend/archive/delete and hidden deleted venues are smoke-tested in Telegram bot flow.

## 2. Telegram Bot Manual Smoke

### Guest catalog and QR/table split

1. Open Telegram bot as guest without QR/table context.
2. Open catalog and venue card.
3. Confirm venue card does not show pre-QR structured `🍽 Меню`.
4. Open `ℹ️ Информация`.
5. Confirm only visible+filled sections are shown.
6. Confirm `📖 Фото-меню` appears as an info section when filled.
7. Trigger old `bot_catalog_venue_menu:{venueId}` callback if reachable from old message.
8. Confirm bot says that order menu is available after QR/table scan.
9. Scan/open QR table context.
10. Confirm structured order menu/cart/order actions are available only in table context.

### Venue owner setup and Mini App entry

1. Open owner venue setup.
2. Confirm `🍽 Заказное меню` and `📖 Фото-меню` are distinct entries/copy.
3. Open media upload for info section, send multiple media files.
4. Confirm no repeated fallback spam and explicit Done/Back path exists.
5. Press venue Mini App entry text button.
6. Confirm bot sends an inline `web_app` button, not a plain URL.

### Platform owner lifecycle

1. Open platform owner menu.
2. Confirm platform access works through `PLATFORM_OWNER_TELEGRAM_ID`.
3. Open connection requests.
4. Approve request with `trial=0`, commercial monthly price and optional future price/date.
5. Create/link venue and confirm subscription settings summary.
6. Suspend, archive and delete test venue.
7. Confirm deleted venue disappears from normal lists.

## 3. Guest Mini App Manual Smoke

Preconditions:

- backend tunnel is configured;
- Mini App tunnel is configured;
- Telegram bot opens Mini App through a `web_app` button;
- venue has at least one available menu item;
- venue has an active table token.

### Pre-QR catalog/card/info

Steps:

1. Open Guest Mini App from Telegram without table context.
2. Confirm the app does not show `initDataLength=0` debug screen.
3. Open catalog and venue card.
4. Confirm structured menu categories/items are not rendered.
5. Confirm `ℹ️ Информация` is shown.
6. Confirm `📖 Фото-меню` appears inside info when owner filled it.
7. Confirm image media loads from backend media proxy and PDF opens through `Открыть PDF`.
8. Confirm booking entry works if bookings are enabled for the venue.

### QR/table order flow

1. Open guest Mini App from Telegram table/QR context.
2. Confirm table/venue context is visible.
3. Open structured order menu.
4. Add one item to cart.
5. Confirm cart total matches item price and backend preview.
6. Submit order.
7. Open active order.
8. Confirm active order shows the submitted item and current status.
9. Trigger staff call.
10. Confirm guest sees success.
11. Confirm staff chat receives notification with venue/table/session context.
12. Open `Поддержка`.
13. Confirm the screen points to staff/platform support and does not show ticket ids or ticket statuses.

Expected:

- pre-QR card has no order-menu categories/items or add-to-cart actions;
- info-section media is browser-readable without exposing Telegram token/file URL;
- request payload includes `tableSessionId`;
- order belongs only to current `tableSessionId/tabId`;
- no cross-session order appears after opening the same physical table with a different session.
- support is clear and informational when ticket automation is not implemented.

## 4. Venue Mini App Manual Smoke

Preconditions:

- platform/owner/manager/staff role has Mini App access;
- venue has an active order created through bot or guest Mini App;
- order has at least one item and, if available, promo/loyalty/manual discount/excluded item fixtures.

Steps:

1. Open venue role menu in Telegram.
2. Press `📱 Открыть панель заведения` / `📱 Открыть рабочую панель`.
3. Confirm bot sends inline `web_app` button and Mini App receives `initData`.
4. Open `Заказы`.
5. Confirm queue uses `Заказ №<displayNumber>` as staff-facing identifier and one card per order.
6. Open order detail.
7. Confirm header uses `Заказ №<displayNumber>`.
8. Confirm statuses/buttons are in Russian.
9. Confirm `🔄 Обновить` refreshes order detail.
10. Confirm the `Счёт` / full bill block is visible without technical backend wording.
11. Confirm rows are shown when applicable:
   - сумма до скидок / gross;
   - ручные скидки;
   - акции;
   - лояльность;
   - исключённые / отменённые / отклонённые позиции;
   - итог к оплате.
12. Compare final payable total with Telegram full bill for the same order.
13. As MANAGER/OWNER, apply manual discount and exclude/restore item.
14. Confirm staff Telegram chat receives `⚠️ Счёт обновлён` with the updated total after each bill-affecting change.
15. Confirm staff Telegram chat updated total matches Venue Mini App and Guest Mini App full bill totals.
16. As STAFF, confirm bill edit controls are hidden.
17. As STAFF, close delivered bill/order.
18. Open `Вызовы`, accept and close a staff call.
19. Open `Брони`: as STAFF, verify only `Гость пришёл` / `Не пришёл`; as MANAGER/OWNER, confirm/change/cancel and `Написать гостю` as allowed.
20. Open `Поддержка`.
21. Confirm the screen explains manual platform support and has no fake ticket controls.

### STAFF booking RBAC split smoke status

Status: local dev smoke PASSED on 2026-06-04; staging deploy/smoke PASSED on 2026-06-04.

Local dev via `dev.hookahtootah.club` - PASSED on 2026-06-04:

1. Stop staging backend so the same bot token is not processed by two backends.
2. Run local backend, local Vite and SSH reverse tunnel according to `docs/LOCAL_TELEGRAM_MINIAPP.md`.
3. Open Venue Mini App as STAFF through Telegram inline `web_app`.
4. Open `Брони`.
5. Confirm STAFF sees booking list/details.
6. Confirm STAFF sees only `Гость пришёл` and `Не пришёл`.
7. Confirm STAFF does not see `Подтвердить`, `Отменить`, `Предложить другое время` or `Написать гостю`.
8. Open as MANAGER/OWNER, click `Написать гостю`, send a short text and confirm the guest receives a Telegram message without staff contacts.
9. Call direct STAFF backend/API attempts for confirm/cancel/change endpoints and the message-guest path.
10. Confirm direct STAFF manage attempts are denied.
11. Trigger old Telegram staff booking confirm/cancel/message callbacks if reachable from old staff-chat messages.
12. Confirm old STAFF callbacks answer safely with no booking mutation.
13. Open as MANAGER/OWNER and confirm booking confirm/cancel/change still work.

Staging after deploy - PASSED on 2026-06-04:

1. Deploy the same local snapshot to staging.
2. Run the STAFF/MANAGER/OWNER booking checks above against staging data.
3. Confirm staging bot WebApp entry still sends inline `web_app` and Mini App receives `initData`.

Expected:

- final total equals backend DTO and Telegram full bill;
- promo/loyalty labels match readable breakdown;
- canceled/rejected/excluded items do not affect payable total.
- STAFF can close bill/order and mutate only menu item/option availability for operational stop-list; STAFF cannot mutate bill discounts/exclusions, menu content/structure, tables, staff, settings or staff chat link.
- STAFF booking management paths are denied while arrival/no-show remains allowed.
- MANAGER/OWNER booking management remains unchanged.
- venue support is a launch-safe informational path, not a half-working ticket UI.

### M2 Venue Mini App statistics smoke status

Status: staging smoke PASSED on 2026-06-16.

Checks:

1. Open Venue Mini App as OWNER or MANAGER.
2. Confirm `Статистика` is visible.
3. Open `Статистика`.
4. Switch periods `Сегодня`, `7 дней`, `30 дней`.
5. Confirm stats cards and top items render without frontend money recalculation.
6. Confirm an empty venue renders zero/empty state safely.
7. Open Venue Mini App as STAFF.
8. Confirm STAFF does not see `Статистика`.

Follow-up, not current smoke blocker:

- custom `from`/`to` date range picker;
- arbitrary period backend stats;
- AI-generated summaries/insights over selected period.

## 5. Platform Mini App Manual Smoke

Preconditions:

- current Telegram user is platform owner;
- at least one venue exists;
- venue has subscription settings row or default snapshot.

Steps:

1. Open `📱 Панель платформы`.
2. Confirm platform access state is valid.
3. Open venue list.
4. Confirm venue status and subscription summary are visible.
5. Open venue detail.
6. Confirm venue status controls are visible.
7. Confirm owners/invite/subscription/price schedule basics are visible.
8. Confirm deleted venues are not shown in the normal/default list.
9. Open `Подключение`.
10. Open `Размещения`.
11. Open `Поддержка`.
12. Open `Аналитика`.

Expected:

- real venue/subscription sections use backend data;
- safe sections contain no fake numbers;
- safe sections do not expose half-working approve/pay/support controls;
- every safe section has a clear path back to venue list.

## 6. Staging Smoke

1. Check staging health:
   - `curl -f https://staging.hookahtootah.club/health`
   - `curl -f https://staging.hookahtootah.club/db/health`
   - `curl -I https://staging.hookahtootah.club/miniapp/`
2. Confirm staging backend is the only process polling the staging bot token.
3. Open Guest/Platform/Venue Mini App through Telegram WebApp buttons.
4. Confirm `initData` auth works for guest, venue role and platform owner.
5. Run Guest, Venue and Platform smoke sections above on staging data.

## 7. Local Dev Smoke

1. Follow `docs/LOCAL_TELEGRAM_MINIAPP.md`.
2. Stop staging backend before local long polling.
3. Start local Postgres, Vite, SSH reverse tunnel and local backend.
4. Check:
   - `curl -f https://dev.hookahtootah.club/health`
   - `curl -f https://dev.hookahtootah.club/db/health`
   - `curl -I https://dev.hookahtootah.club/miniapp/`
5. Confirm Telegram buttons open `https://dev.hookahtootah.club/miniapp/`.
6. Restore staging backend after local smoke.

## 8. Cross-channel Parity Smoke

1. Create an order through Telegram bot and verify it appears in Venue Mini App queue.
2. Create an order through Guest Mini App and verify Telegram staff flow receives it.
3. Compare Telegram full bill and Venue Mini App `Счёт`:
   - final total;
   - promo lines;
   - loyalty lines;
   - excluded/canceled/rejected rows.
4. Verify role visibility:
   - OWNER sees venue operational panel;
   - MANAGER sees venue operational panel;
   - STAFF sees only allowed operational sections;
   - platform owner sees platform panel only when configured.
5. Verify table/session isolation:
   - two different `tableSessionId` values at the same table do not share active orders;
   - shared tab requires membership.

## 9. Known Gaps

- Minimal Playwright browser smoke exists for Guest Mini App pre-QR/table menu separation; wider Venue/Platform/browser coverage is still pending.
- Telegram WebApp `initData` can only be fully validated in Telegram runtime or a dedicated WebApp test harness.
- Manual comparison with Telegram full bill remains useful in release smoke, but money-critical totals now also have cross-channel backend snapshot coverage.
- Staff Telegram chat totals refresh and main order vs doporders clarity passed staging smoke; keep one-message/no-spam and batch-status behavior in regression smoke.
- Guest table session restore and Telegram BackButton navigation passed staging smoke; keep restore, QR priority, account-switch isolation and no-loop BackButton behavior in regression smoke.
- Paid venue/shift extension is implemented for backend and Mini App as a confirmed service charge/session extension, not as a normal menu/cart/order-batch item. Remaining parity gaps: order-scoped Venue Mini App approve/reject, staff-chat live message pending actions, Guest Bot table menu entry and Owner/Manager Bot settings.
- Guest/Menu Options & Flavors parity is CLOSED after staging smoke: owner/manager can create hookah items, apply canonical base flavor profiles only to that item, repeat apply without duplicates, manage flavor CRUD and stop-list, stop-list the whole item, water/kitchen/drink items do not receive hookah flavors, Guest Mini App shows the picker only for the selected hookah item, and `selectedOptionId` / `preferenceNote` still work.
- `📖 Фото-меню` is currently a flat info-section media list; optional owner-defined subsections are a P2 follow-up.
- Owner multi-image upload remains a Telegram UX follow-up: current flow may confirm each media upload separately.
- Platform Mini App onboarding/placements/support/analytics are still partial/safe sections, not full cockpit parity.
- Broad backend test wildcards may hit heap/runtime limits; CI now uses green split release-validation jobs, and local release checks should prefer the targeted smoke/regression commands.

## 10. Recommended Next Test Investment

1. Expand the lightweight Playwright/Vite smoke harness with fixture-driven UI tests for:
   - guest cart/order/staff call;
   - guest item option/flavor picker, cart line identity and checkout payload;
   - venue order detail/full bill;
   - platform safe sections.
2. Add backend cross-channel tests for selected menu options before enabling Mini App option checkout:
   - same item with different option/flavor produces separate order lines;
   - selected option price deltas affect preview, checkout and bill snapshots;
   - disabled/stale option ids are rejected at preview/checkout;
   - Guest Bot and Guest Mini App submit the same structured selected-option shape.
3. Extend cross-channel bill snapshots when selected option price deltas are implemented.

## 11. Next Implementation Smoke Target

Recommended next parity smoke block: Venue Mini App bookings queue/lifecycle M3. Paid venue/shift extension Owner/Manager Bot settings parity remains a separate P1 closure track.

Manual bookings lifecycle smoke after M3 deploy:

1. Open Venue Mini App as STAFF and confirm `Брони` shows booking list/details.
2. As STAFF, mark a booking `Гость пришёл` and `Не пришёл`; confirm management actions are hidden.
3. As MANAGER/OWNER, confirm/change/cancel a booking.
4. Confirm scheduled time and `Держим до` render in venue-local timezone.
5. Confirm lifecycle copy and empty/error states are clear, including `Активных броней пока нет.`
6. Trigger direct STAFF manage attempts and confirm they are denied.
7. Confirm Telegram bot booking actions remain aligned with Mini App behavior.

Manual paid extension smoke after full parity:

1. Configure extension for a venue in Venue Mini App: enabled, fixed one-hour duration and price.
2. Configure the same extension in Owner/Manager Bot once the remaining bot settings parity slice is implemented; confirm copy `Показывать гостям возможность продления`.
3. Guest Mini App active table context shows service entry `Продление работы заведения` in the ordering section list, then `Продлить на 1 час` inside that service screen.
4. Guest Bot `🍽️ Меню` and `Мой заказ → Дозаказать` section lists show `Продление работы заведения` alongside ordering sections and create the same fixed-price request.
5. Guest creates one extension request; repeated taps/callbacks do not duplicate pending requests.
6. Venue Mini App order queue shows a pending extension badge/count on the affected order/table.
7. Venue Mini App order detail shows `Запрос на продление работы заведения`, `На 1 час — 3 000 ₽`, `✅ Подтвердить продление`, `❌ Отказать`.
8. Staff chat live order/bill message updates in place with the pending extension block and inline approve/reject buttons; no separate noisy lifecycle message is sent.
9. STAFF/MANAGER approves from Venue Mini App or staff chat; bill gains service charge `Продление работы на 1 час`, Guest/Venue/Telegram bill totals match, and table session orderable-until time extends.
10. Create and approve a second extension; charge and session extension are applied once per request.
11. Reject a request and confirm guest sees rejection copy while bill/session do not mutate.
12. As STAFF, confirm price/duration/settings are not editable in Mini App or bot.
13. As MANAGER/OWNER, confirm settings are editable in Mini App; repeat in bot after the remaining bot settings parity slice lands.
14. Close bill/session and confirm extension request/approve endpoints are denied and extension UI disappears or disables safely.

Manual options/flavors parity regression smoke:

1. Configure a hookah item with two available flavors/options and one stop-listed option.
2. Guest Bot ordering flow shows only available options and requires/selects one before adding the hookah to cart.
3. Guest Mini App ordering flow shows the same option picker before adding the hookah to cart.
4. Add the same hookah with two different flavors; cart and checkout keep two separate lines.
5. In Guest Mini App, add optional `Пожелание к вкусу` to one selected flavor; same item/flavor/same note increments qty, while same item/flavor/different note stays a separate line.
6. Confirm selected option price deltas appear in preview, checkout, guest active order/bill, Venue Mini App order detail and staff chat.
7. Confirm line preference notes appear in guest active order, Venue Mini App order detail, staff chat and Guest Bot `Мой заказ`; notes must not affect price.
8. Disable an option after selection and confirm preview/checkout rejects the stale option without silently adding the base item.
9. Confirm Guest Bot no longer relies only on `Выбранные вкусы` comment text for persistence; selected option data is structured in order/read models. Guest Bot input for optional `Пожелание к вкусу` remains a follow-up until implemented.
10. As OWNER/MANAGER, manage options in Venue Mini App, apply `Добавить базовые вкусы` only to hookah items, and confirm STAFF edit/apply/content controls are hidden/forbidden while item/flavor stop-list toggles remain available.
11. Confirm repeated `Добавить базовые вкусы` does not create duplicates.
12. Confirm water/kitchen/drink items do not receive hookah flavors.
