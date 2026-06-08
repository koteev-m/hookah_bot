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
- STAFF can close bill/order but cannot edit discounts, exclusions, stop-list, menu, tables, staff, settings or staff chat link.
- STAFF booking actions are operational only: view bookings and mark `Гость пришёл` / `Не пришёл`; confirm/cancel/change/message/settings are MANAGER/OWNER-only.
- STAFF booking RBAC split local smoke via `dev.hookahtootah.club` and staging deploy/smoke both passed on 2026-06-04.
- Pilot Smoke Fix Pack #1 staging re-smoke passed on 2026-06-04.
- Pilot Smoke Fix Pack #1.1 staging re-smoke passed on 2026-06-04; the previous P1 `Guest pre-QR endless "Загрузка информации..."` is resolved.
- CI release validation is green for the current release snapshot: backend ktlint, backend compile, split backend route/RBAC/Telegram/migration jobs, compose, Mini App build, backend Docker build and backend aggregate passed.
- Cross-channel bill snapshot automation covers Mini App full bill vs Telegram/staff bill totals for manual discounts, promo discounts, exclusions and restore.
- Live staff-chat order messages, bill-affecting refresh and button lifecycle passed staging smoke. Remaining staff-chat follow-up is batch-level clarity: main order and doporders should be visually separated.
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

Remaining:

- repeat this smoke after any additional release batch;
- P1 follow-up: staff-chat live message should separate main order and doporders/add-batches by block and status;
- P1 follow-up: Guest table session restore should let a returning guest re-enter an active table context safely without rescanning QR;
- P1 follow-up: paid venue/shift extension needs a scoped product/API design before implementation;
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
19. Open `Брони`: as STAFF, verify only `Гость пришёл` / `Не пришёл`; as MANAGER/OWNER, confirm/change/cancel as allowed.
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
8. Call direct STAFF backend/API attempts for confirm/cancel/change endpoints and any available message-guest path.
9. Confirm direct STAFF manage attempts are denied.
10. Trigger old Telegram staff booking confirm/cancel/message callbacks if reachable from old staff-chat messages.
11. Confirm old STAFF callbacks answer safely with no booking mutation.
12. Open as MANAGER/OWNER and confirm booking confirm/cancel/change still work.

Staging after deploy - PASSED on 2026-06-04:

1. Deploy the same local snapshot to staging.
2. Run the STAFF/MANAGER/OWNER booking checks above against staging data.
3. Confirm staging bot WebApp entry still sends inline `web_app` and Mini App receives `initData`.

Expected:

- final total equals backend DTO and Telegram full bill;
- promo/loyalty labels match readable breakdown;
- canceled/rejected/excluded items do not affect payable total.
- STAFF can close bill/order but cannot mutate bill/menu/stop-list/tables/staff/settings/staff chat link.
- STAFF booking management paths are denied while arrival/no-show remains allowed.
- MANAGER/OWNER booking management remains unchanged.
- venue support is a launch-safe informational path, not a half-working ticket UI.

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
- Staff Telegram chat totals refresh passed staging smoke; current P1 staff-chat gap is visual/operational clarity for main order vs doporders/add-batches and their statuses.
- Guest table session restore is not yet implemented as a no-QR return path; active table context currently remains safest through Telegram QR/start/table token flows.
- Paid venue/shift extension is not yet implemented; treat it as a product/API design block, not as a normal menu item.
- `📖 Фото-меню` is currently a flat info-section media list; optional owner-defined subsections are a P2 follow-up.
- Owner multi-image upload remains a Telegram UX follow-up: current flow may confirm each media upload separately.
- Platform Mini App onboarding/placements/support/analytics are still partial/safe sections, not full cockpit parity.
- Broad backend test wildcards may hit heap/runtime limits; CI now uses green split release-validation jobs, and local release checks should prefer the targeted smoke/regression commands.

## 10. Recommended Next Test Investment

1. Expand the lightweight Playwright/Vite smoke harness with fixture-driven UI tests for:
   - guest cart/order/staff call;
   - venue order detail/full bill;
   - platform safe sections.
2. Extend cross-channel bill snapshots only if new money-affecting adjustments are added.

## 11. Next Implementation Smoke Target

Recommended next implementation block: `P1 Staff-chat main order vs doporders clarity`.

Manual smoke after implementation:

1. Create a first order from Guest Mini App and confirm the staff Telegram chat has one live message.
2. Confirm the message shows the main order as its own block with status and bill totals from `OrderBillSnapshot`.
3. Add a doporder/add-batch from Guest Mini App.
4. Confirm the same live message is edited, not spammed, and now shows a separate doporder block with its own status.
5. Accept/deliver the main order and doporder in different order.
6. Confirm each block's status remains understandable and action buttons do not imply the wrong batch.
7. Apply discount/exclude/restore and confirm totals still match Venue Mini App full bill and Telegram/staff full bill.
