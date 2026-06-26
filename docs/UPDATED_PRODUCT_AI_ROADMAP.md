# Product + Telegram AI Bots Roadmap

Дата обновления: 2026-06-26.

Статус документа: canonical roadmap. Этот файл объединяет актуальный product roadmap, Mini App launch roadmap и Telegram-native AI Bots roadmap. Старые audit-файлы в `docs/audit/` остаются evidence/history, но не являются текущим backlog без сверки с этим roadmap и текущим кодом.

Назначение: дать рабочий план разработки до market launch и после него. Это не маркетинговый пресс-релиз и не описание одного LLM-провайдера.

## 1. Executive Summary

Мы строим Telegram bot + Mini App платформу для кальянных:

- QR/table flow;
- каталог заведений;
- меню, корзина, заказы со столов;
- staff/manager/owner operations;
- platform operations;
- акции, размещения, лояльность;
- AI assistant layer для диагностики, черновиков и управленческих сводок.

Ключевое решение:

> Market launch требует production-ready Telegram bot + Mini App core. AI входит в продукт как assistant layer. Telegram Guest Mode, Telegram Business / Secretary Bots, Managed branded bots и Bot-to-Bot agents не являются обязательными для первого запуска.

Текущий фокус перед пилотом: product P0/P1 закрыт по уже принятым M1-M9b.3 блокам, staging smoke, CI release validation, deploy/runbook hardening and minimal Guest Mini App browser smoke зелёные. M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed: standard deploy remains supported, opt-in ControlMaster deploy is validated as a release-reliability workaround, and the exact SSH/network root cause remains unconfirmed. M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 range/rejection-copy improvements, M9b.2 exception save/list UX and M9b.3 date-range editing are CLOSED / staging smoke passed. Selected next bounded implementation milestone: Platform Owner Invite / ADMIN Semantics Hardening. Scope не расширяем в сторону Telegram-native AI surfaces до готовности Mini App и public-safe tools.

Актуальный post-fix snapshot:

- Guest catalog до QR не показывает заказное `🍽 Меню`; структурированное меню доступно только после QR/table context.
- `ℹ️ Информация` и `📖 Фото-меню` разделены от `🍽 Заказного меню` в Telegram bot и Mini App.
- Guest Mini App показывает visible+filled info sections; media info sections открываются через backend proxy без раскрытия Telegram token/file URL.
- Venue Owner/Manager/Staff входят в Venue Mini App через inline `web_app` button, чтобы Telegram WebView передавал `initData`.
- STAFF может закрывать счёт/заказ и управлять операционным stop-list по позициям/вкусам, но не может управлять скидками, исключениями, структурой/контентом меню, столами, персоналом или настройками.
- STAFF booking policy разделён: STAFF видит брони и отмечает `Гость пришёл` / `Не пришёл`, а confirm/cancel/change/message/settings остаются MANAGER/OWNER.
- Venue Mini App booking card opens a persisted booking conversation thread: venue messages, Guest Bot replies and Guest Mini App replies share one source of truth; staff chat remains a notification mirror. M4A staging smoke passed after UX polish. M4B/M4C unified `Сообщения` inbox and resolve/reopen lifecycle are CLOSED after staging smoke: thread cards show context/status/last message/unread, active/resolved filters work, and explicit resolve/reopen does not mutate booking lifecycle.
- M5 staff calls lifecycle is CLOSED after staging smoke: Guest Mini App uses a transient staff-call modal and compact status card, Venue Mini App has a real `Вызовы` queue with accept/close, and backend/staff-chat callbacks share the same lifecycle. Keep linked Telegram group notification smoke in per-venue regression.
- Platform Owner определяется через `PLATFORM_OWNER_TELEGRAM_ID`; legacy aliases остаются совместимостью.
- Platform onboarding поддерживает `trial=0`, commercial terms, subscription sync and create/link venue.
- Venue lifecycle поддерживает suspend/archive/delete; `DELETED` hidden from normal lists.
- Staging работает на `staging.hookahtootah.club`; local Telegram Mini App smoke работает через `dev.hookahtootah.club` + SSH reverse tunnel.

Recently verified:

- STAFF booking RBAC split is implemented in backend/Mini App/Telegram callbacks and tests. Local manual smoke through `dev.hookahtootah.club` passed on 2026-06-04; staging deploy/smoke passed on 2026-06-04.
- Full pilot staging smoke on 2026-06-04 completed as `PASS WITH P1 FIXES`; Pilot Smoke Fix Pack #1 was deployed and affected staging re-smoke passed.
- Pilot Smoke Fix Pack #1.1 was deployed and affected staging re-smoke passed on 2026-06-04: health/db/miniapp, Telegram Mini App `initData`, Guest pre-QR info/media loading, venue selector Russian status labels and explicit archive restore copy all passed.
- The previous P1 `Guest pre-QR endless "Загрузка информации..."` issue is resolved in staging.
- CI release validation is green on the latest release snapshot: backend ktlint, backend compile, split backend route/RBAC/Telegram/migration jobs, compose, Mini App build, backend Docker build and aggregate job all passed.
- Deploy/runbook hardening is in place: health-check wait/retry, restart/rollback/log commands and staging/pilot first-response path are documented.
- Minimal Playwright browser smoke covers Guest Mini App pre-QR info/photo-menu vs table-context structured menu separation.
- Cross-channel bill snapshot automation protects Mini App full bill vs Telegram/staff bill totals.
- Live staff-chat order messages, bill-affecting refresh and button lifecycle passed staging smoke. Current follow-up is batch-level clarity, not stale totals.
- Guest table session persistence/restore and Telegram BackButton navigation passed staging smoke on 2026-06-08: returning guest stays in table context without repeat QR, menu/order/profile/support navigation keeps context, and BackButton no longer loops.
- Guest/Menu Options & Flavors parity is CLOSED after staging smoke: structured selected-option persistence, Guest Bot/Mini App option choice, item-scoped Venue Mini App option CRUD, explicit item/option stop-list controls and Mini App `Добавить базовые вкусы` through the shared backend profile service are implemented. Remaining follow-ups are separate: optional Mini App normalize/reset, DB-level duplicate/race protection if needed, and broad Venue Mini App IA parity.
- M4A booking conversation threads are CLOSED after staging smoke: booking `Написать гостю` creates/reuses a persisted thread, the quick compose modal closes after send, booking card shows `Открыть переписку`, Guest Bot replies are saved, Guest Mini App and Venue Mini App can read/reply, and staff chat receives notifications without becoming the storage layer.
- M4B Unified Messages Inbox UX is CLOSED after staging smoke: Guest and Venue Mini App thread lists now use backend context labels, status, last message preview/time, unread counts via `support_thread_reads`, and `Активные` / `Завершённые` filters.
- M4C support thread resolve/reopen lifecycle is CLOSED after staging smoke: Guest and Venue Mini App can `Завершить переписку`, resolved threads move to `Завершённые`, `Возобновить переписку` moves them back to `Активные`, and sending a real user message to a resolved thread reopens it through shared backend message semantics.
- M5 staff calls lifecycle and compact Guest Mini App UX are CLOSED after staging smoke. Keep linked Telegram group notification and inline ACK/DONE behavior in regression smoke per pilot venue.
- M6 staff chat diagnostics/unlink polish is CLOSED after staging smoke: Venue Mini App shows linked/unlinked state, masked chat id, backend-built `/link@BotUsername <код>` command, outbox-backed test-message queue/delivery path, OWNER-only unlink, relink flow, and polished active-code UI with copy-first action plus regenerate confirmation.
- M8a/M8b-Free Venue Mini App structured public profile/card settings is CLOSED / staging smoke passed: OWNER/MANAGER edit provider-free public country/city/address/contact/description, STAFF is hidden/forbidden, guest read models reflect saved fields, route links use saved coordinates when present and otherwise encoded text address search, and Yandex adapters remain optional disabled commercial-only integrations.
- M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed. `scripts/deploy-staging-controlmaster.sh` is committed as an opt-in helper, `./scripts/deploy-staging.sh hookah-staging` remains unchanged, the helper's bounded retry opened the initial master after an SSH banner timeout, rsync/build/image upload/backend recreate succeeded through one persistent connection, PostgreSQL stayed healthy, local `/health`, local `/db/health`, local Mini App static, public `/health`, public `/db/health` and public `/miniapp/` passed, and a separate retry-based public check also passed. The fresh-connection failure cause remains observed but unproven.
- M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 Schedule Exception Ranges and Guest Copy, M9b.2 Schedule Exception Save UX and M9b.3 Schedule Exception Range Editing are CLOSED / staging smoke passed. Venue Mini App manages weekly hours plus inclusive closed/special-hours date-exception ranges for OWNER/MANAGER; the same from/to date means one day; nullable `guest_note` carries the optional guest-facing reason/comment; changed-hours saves visibly return to the compact exception list; existing closed and changed-hours exceptions can be edited to a new inclusive date range; range storage remains per-date overrides and overlapping target dates are overwritten by the latest saved range values; STAFF is hidden/forbidden; guest catalog/card read models expose today's safe schedule/open state; and direct Guest Mini App booking create/update returns human schedule rejection codes/copy. Product decision: the existing Bot `Часы работы` model intentionally represents both public operating hours and booking availability for launch. Missing schedule setup shows `График не указан` / `VENUE_SCHEDULE_NOT_CONFIGURED`, not a deliberate closed day.

## 2. Sources Merged

Этот файл объединяет и заменяет:

- `docs/UPDATED_PRODUCT_AI_ROADMAP.md` прежней версии;
- `docs/TELEGRAM_AI_BOTS_ROADMAP.md`;
- `docs/audit/BOT_FIRST_PRODUCT_ROADMAP.md`.

Supporting docs остаются источниками деталей и evidence:

- `docs/audit/MINI_APP_PRODUCTION_READINESS_AUDIT.md`;
- `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`;
- `docs/audit/PRODUCT_AUDIT_SUMMARY.md`;
- `docs/audit/ROLE_GUEST.md`;
- `docs/audit/ROLE_MANAGER.md`;
- `docs/audit/ROLE_STAFF.md`;
- `docs/audit/ROLE_VENUE_OWNER.md`;
- `docs/audit/ROLE_PLATFORM_OWNER.md`;
- `docs/audit/PRODUCT_IDEAS_REVIEW.md`.
- `docs/audit/VENUE_BOT_TO_MINIAPP_PARITY_PROGRAM.md`.

Word roadmap status:

- `/Users/maksimmartynov/Downloads/Hookah_Tootah_Roadmap.docx` был найден ранее, но содержимого для roadmap decisions не дал.
- `docs/Hookah_Tootah_Roadmap.docx` в проекте не найден.

## 3. Current State

### Bot-First Core

Status: `DONE / STABILIZE`.

Done:

- guest QR/table flow;
- menu/cart/order flow;
- table sessions and tabs;
- staff chat notifications;
- order queue and lifecycle;
- full bill/history/stats;
- feedback/reviews;
- favorites/history;
- role-aware Telegram menus and callbacks.

Launch focus:

- smoke for role visibility and permission boundaries;
- webhook/outbox/staff notification monitoring;
- fallback/error paths;
- pilot venue runbook.

### Guest Flow

Status: `DONE / P1 POLISH`.

Done:

- guest catalog and venue card baseline;
- pre-QR venue card without structured order menu;
- `ℹ️ Информация` and `📖 Фото-меню` info sections;
- Mini App media proxy for info-section images/PDFs;
- table context;
- menu/cart/checkout;
- active order;
- staff call;
- bookings MVP;
- account hub baseline with history/favorites and safe bot-only fallbacks where needed;
- loyalty progress/redemption display;
- support-safe baseline;
- active order scoping through `tableSessionId` and `tabId` in Mini App client/backend path.

Remaining P1/P2:

- richer profile/promotions/loyalty polish in Mini App;
- richer active order display with totals/promo/loyalty parity where needed;
- booking create/confirm/change/cancel smoke passed for current staging MVP; keep it in regression smoke after future booking changes.

### Venue / Manager / Staff

Status: `DONE / P1 POLISH`.

Done:

- venue order queue;
- order detail;
- display order number;
- Russian production labels for queue/detail;
- management full bill in Mini App;
- gross/manual/promo/loyalty/excluded/final display;
- staff calls lifecycle baseline;
- STAFF close bill/order;
- STAFF operational stop-list for menu items and item options/flavors;
- manager/owner bill controls for manual discount and item exclude/restore;
- menu and stop-list baseline;
- explicit item-level and option/flavor-level stop-list controls;
- owner Telegram copy split between `🍽 Заказное меню` and `📖 Фото-меню`;
- Venue Mini App entry for OWNER/MANAGER/STAFF through inline `web_app`;
- venue booking queue/actions baseline; M3 Mini App bookings queue/lifecycle MVP is closed in the current release line with venue-local display fields and manager/staff coverage;
- M4A booking conversation threads CLOSED after staging smoke: booking messages, Guest Bot replies and Mini App replies persist in shared support threads;
- booking RBAC split implemented: STAFF view + arrival/no-show, MANAGER/OWNER management actions;
- staff-chat live message clarity for main order vs doporders passed staging smoke: one live message, venue-local time without `UTC`, separate blocks and clear batch statuses/actions;
- guest table session persistence/restore passed staging smoke: reopening Mini App without repeat QR restores the active table context, internal guest navigation keeps that context, and Telegram BackButton exits from root instead of looping;
- broad venue settings are not exposed as a dead-end placeholder; Venue Mini App `Настройки` now contains backend-backed booking hold, paid shift-extension settings, M8b public card basics with structured location (`countryCode`, `city`, `address`, `formattedAddress`, optional coordinates, `guestContact`, `cardDescription`) and M9b weekly hours/date exceptions;
- Venue Mini App read-only `Статистика` CLOSED after staging smoke for OWNER/MANAGER on existing `VenueStatsRepository` semantics;

Remaining P1:

- final staging smoke after each release batch;
- continue bounded venue settings slices where backend-backed; media sections, promotions, preview and billing remain bot/platform-canonical until implemented explicitly;
- Venue Mini App normalize/reset helper only if still needed after pilots; base flavor profile apply, item-level stop-list and flavor-level stop-list parity are smoke-passed. Preserve STAFF no-settings/no-menu-content-management boundaries while keeping operational stop-list allowed;
- custom date range picker, arbitrary period stats, AI-generated summaries, advanced analytics/platform dashboards/network stats remain later; read-only venue stats is covered by closed M2;
- M4B/M4C Unified Messages Inbox UX and lifecycle are CLOSED after staging smoke; keep multi-venue/thread scoping, unread clearing and resolve/reopen in regression;
- deeper operational frontend smoke/e2e coverage beyond the current Guest/Venue Mini App browser smoke.

### Platform Owner

Status: `PARTIAL / LAUNCH BASELINE`.

Done:

- platform panel baseline;
- venues list/detail;
- venue lifecycle/status basics;
- subscription basics;
- platform owner access through `PLATFORM_OWNER_TELEGRAM_ID`;
- connection requests stay visible through approve/create-link lifecycle;
- `trial=0`, commercial terms, subscription sync and future price schedule baseline;
- suspend/archive/delete status actions; deleted venues hidden from normal lists;
- safe onboarding/placements/support/analytics sections without fake data or dead-end controls.

Remaining P1/P2:

- selected next bounded implementation milestone: Platform Owner Invite / ADMIN Semantics Hardening;
- platform support/ticket system if required for scale;
- richer analytics dashboard;
- placements cockpit parity with bot;
- billing/invoices and platform runbook;
- owner assignment/invite flow hardening, including Bot/API platform-owner config precedence, usable owner invite deep link/copy, unsupported `ADMIN` removal from Platform Mini App owner assignment, and audit trail for owner invite grants.

### Promotions / Marketing / Loyalty

Status: `DONE / STABILIZE`.

Done:

- `TEXT_ONLY`;
- `BANNER`;
- `HAPPY_HOURS_PERCENT`;
- `GIFT_WITH_ITEM`;
- fixed and choice rewards;
- item/category targets;
- schedules;
- compatibility/stackability resolver;
- promo ledger;
- guest/staff/full bill/history promo breakdowns;
- global promotions feed;
- banner placements;
- top-in-actions;
- archive UX;
- loyalty accrual and redemption through ledger;
- marketing hub.

Launch focus:

- preview vs checkout parity smoke;
- item-level discount caps;
- loyalty + Happy Hours conflict smoke;
- repeat order behavior;
- archived/paused/unavailable edge cases.

### Mini App Readiness

Status: `P0 CLOSED / PILOT SMOKE PASSED WITH P2 FOLLOW-UP`.

Closed readiness blocks:

- CORS/preflight for Mini App mutation routes;
- guest staff call `tableSessionId`;
- fallback chat order command contract;
- active order scoping regression;
- venue full bill parity;
- manager/owner bill controls parity baseline;
- STAFF close bill/order policy;
- venue order/queue UX polish;
- Guest Mini App pre-QR info/photo-menu parity;
- Guest Mini App media proxy for info sections;
- Venue Mini App inline `web_app` entry for venue roles;
- broad venue settings dead-end hidden; shift-extension settings is backend-backed;
- bookings MVP screens;
- stop-list option availability parity, excluding selected-option order modifiers;
- platform cockpit baseline;
- launch smoke/e2e coverage baseline;
- support/tickets launch-safe baseline;
- frontend active order API scoped by `tableSessionId`/`tabId`.
- Pilot Smoke Fix Pack #1 staging re-smoke passed on 2026-06-04.
- Pilot Smoke Fix Pack #1.1 staging re-smoke passed on 2026-06-04; Guest pre-QR info/media no longer remains on endless loading.

Remaining before pilot:

- repeat real Telegram runtime smoke using `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` after any additional release batch;
- keep accepted P2 follow-ups out of the pilot blocker list unless a smoke regression reclassifies them.

### DevOps / QA

Status: `STAGING READY / SMOKE + CI RELEASE VALIDATION PASSED`.

Done:

- staging on `https://staging.hookahtootah.club` with `/health`, `/db/health`, `/miniapp/`;
- Docker Compose staging deployment support;
- opt-in ControlMaster staging deploy helper for unreliable fresh SSH/rsync connection establishment;
- local Telegram Mini App smoke through `https://dev.hookahtootah.club` and SSH reverse tunnel;
- Mini App static/proxy `/miniapp/` route handling for local and staging;
- V102 PostgreSQL/H2 migration for platform-owner lifecycle dialog states.
- CI release validation uses split backend jobs and is green for the latest release snapshot: ktlint, compile, release-critical routes, venue booking/RBAC, Telegram lightweight tests, migration sanity, compose, Mini App build, backend Docker build and backend aggregate.
- Deploy health-check wait/retry, restart/rollback/log runbook and staging/pilot first response are documented.
- Minimal Playwright browser smoke is wired for Guest Mini App pre-QR/table menu separation.
- Cross-channel bill snapshot automation covers Mini App full bill vs Telegram/staff bill totals for manual discounts, promo discounts, exclusions and restore.
- M9a ControlMaster deploy path is validated on real staging as a workaround and release-reliability improvement; standard deploy remains supported and unchanged.

Remaining P1:

- final staging smoke after each additional release batch;
- separate operations follow-up for the exact SSH drop cause, firewall/VPN/private management networking, SSH daemon hardening, monitoring of rejected pre-auth connections and deployment rollback/blue-green work;

## 3.1 Newly Recorded Product Follow-ups

Status: `FOLLOW-UP BACKLOG / NO CURRENT P0`.

These items were recorded after the pilot release snapshot, CI hardening, deploy runbook hardening, browser smoke, cross-channel bill snapshot regression and live staff-chat staging smoke. Closed rows stay in regression; active rows must be implemented only as bounded milestones.

### Venue Bot-to-Mini-App Parity Program

Status: `P1 ACTIVE / SMALL MILESTONES ONLY`.

Goal: переносить уже реализованные Telegram Bot venue-management возможности в Venue Mini App без нового продуктового моделирования и без фейковых production-facing кнопок. Source map: `docs/audit/VENUE_BOT_TO_MINIAPP_PARITY_PROGRAM.md`.

Milestones:

1. M1: Venue Mini App IA shell: сгруппировать уже работающие экраны под `Работа смены` и `Настройки`, сделать уже реализованные `Продления` видимыми по `SHIFT_EXTENSION_VIEW`, не показывать отсутствующие `Продвижение` / `Предпросмотр для гостя`.
2. M2: read-only venue stats route + screen after SQL/RBAC tests. Status: CLOSED / staging smoke passed; keep in regression.
3. M3: Venue Mini App bookings queue/lifecycle parity; Status: CLOSED after smoke in current release line. Keep STAFF arrival/no-show split and MANAGER/OWNER management actions in regression.
4. M4A: booking conversation threads MVP; Status: CLOSED / staging smoke passed. Persist booking messages, Guest Bot replies and Guest/Venue Mini App replies in shared support threads.
5. M4B: Unified Messages Inbox UX; Status: CLOSED / staging smoke passed. Guest `Сообщения` / `Мои обращения` is a thread list with venue, context, status, last message, time and unread badge; Venue `Сообщения` is scoped to the current venue; Platform Support Center remains later and only backend-backed.
6. M4C: Support thread resolve/reopen lifecycle; Status: CLOSED / staging smoke passed. Conversation lifecycle is separate from booking lifecycle; active/resolved filters have explicit user actions and do not mutate booking lifecycle.
7. M5: Staff Calls Lifecycle and Notification Parity; Status: CLOSED / staging smoke passed. Guest Mini App creates scoped staff calls with compact transient UX; Venue Mini App accepts/closes active calls; staff chat remains notification mirror.
8. M6: staff chat diagnostics/unlink polish. Status: CLOSED / staging smoke passed. Venue Mini App exposes existing status/link/test/unlink semantics, uses masked chat id, reports test-send as queued when it goes through outbox, keeps unlink OWNER-only and shows active-code card with copy-first action plus confirmed regeneration.
9. M7a: booking hold settings in Venue Mini App; Status: CLOSED / staging smoke passed. The `Настройки` screen exposes current hold duration and updates it for OWNER/MANAGER only using the same `venue_booking_settings.hold_minutes` semantics as the bot. STAFF stays denied/hidden.
10. M7b: Guest Mini App `Мои брони`; Status: implemented, code/test/e2e-backed, staging visual parity passed for the same booking's public label, venue-local time and `Держим до` in Bot `/my` versus Guest Mini App. Real two-account Telegram runtime isolation remains unverified.
11. M7c adaptive transactional booking reminders: Status: implemented, code/test/e2e-backed, real Telegram staging smoke passed for one controlled M7C reminder and guest attendance flow. Runtime remains opt-in disabled by default; only explicit `BOOKING_REMINDER_WORKER_ENABLED=true` starts the worker, and staging was returned to `BOOKING_REMINDER_WORKER_ENABLED=false` after smoke. M7c uses explicit confirmation/reschedule anchors, one scheduled pre-visit reminder maximum per booking, `QUEUED` after outbox enqueue, legacy-row isolation, atomic attendance confirmation, Telegram message editing and Mini App guest/venue attendance indicators. The enriched staff-chat attendance copy is code/test-backed but not manually re-smoked with a new booking.
12. M8a/M8b-Free: Venue Mini App structured public profile/card settings; Status: CLOSED / staging smoke passed. OWNER/MANAGER can edit guest-facing country/city/address, public contact and card description; venue name is read-only; STAFF is hidden/forbidden; country/city suggestions are local and provider-free, address entry is manual, and guest public read models/routes prefer existing saved coordinates while falling back to encoded text address search. M8b V110 structured-location migrations keep `countryCode`, `formattedAddress`, `latitude` and `longitude` nullable/backward-compatible. Yandex Geosuggest/Geocoder remain optional disabled adapters with separate keys for a later approved commercial setup, not the production default.
13. M9b: Venue Working Hours and Date Exceptions Mini App Parity; Status: CLOSED / staging smoke passed. Existing backend/Bot weekly hours and concrete-date overrides now have Venue Mini App owner/manager settings, inclusive date-exception ranges with optional guest-facing reason/comment, M9b.2 post-save exception UX, M9b.3 date-range editing for existing exceptions, guest-visible open/closed read models and direct Mini App booking validation with human schedule rejection copy, without broad settings redesign. M9b.1 Schedule Exception Ranges and Guest Copy, M9b.2 Schedule Exception Save UX and M9b.3 Schedule Exception Range Editing are also CLOSED / staging smoke passed; keep schedule validation and Bot closed-date copy in regression.
14. Future: read-only promotions/growth summary; builders stay bot-canonical until explicit Mini App APIs/RBAC exist.
15. Future: guest preview entry using guest-visible read models only.
16. Future: menu semantic type/media polish after current options/flavors regression remains green.

| Priority | Block | Current evidence | Product target | Recommended action |
| --- | --- | --- | --- | --- |
| P1 ACTIVE | Bot-to-Mini-App Parity Program | Bot selected-venue hub already has sections `Работа смены`, `Настройка заведения`, `Статистика`, `Продвижение`, `Предпросмотр для гостя`; Venue Mini App has M1 IA shell, M2 read-only `Статистика`, M3 booking queue/lifecycle, M4A booking conversation threads, M4B/M4C inbox lifecycle, M5 staff-call lifecycle, M6 staff-chat diagnostics/unlink and M7a booking hold settings closed. Guest Mini App M7b `Мои брони` is implemented with code/test/e2e evidence and staging visual parity for Bot `/my` label/time/deadline; real two-account Telegram isolation remains unverified. M7c adaptive reminders passed a controlled real Telegram staging smoke and are still disabled by default for rollout. M8a/M8b-Free structured public profile/card settings is CLOSED after provider-free staging smoke; Yandex adapters remain disabled and optional. M9b schedule parity plus M9b.1/M9b.2/M9b.3 improvements are CLOSED / staging smoke passed: Venue Mini App edits weekly hours/date exceptions, supports inclusive exception ranges with optional guest-facing reason/comment, shows saved changed-hours ranges in the compact list, allows editing existing exception date ranges, guest read models expose today's safe schedule/open state, and direct Mini App guest booking validates configured hours with human schedule errors. Full map is in `docs/audit/VENUE_BOT_TO_MINIAPP_PARITY_PROGRAM.md`. | Bot and Mini App are two clients over one backend; required core surfaces must be aligned or documented as intentional exceptions. Mini App should show only backend-backed functionality. Functional correctness stays ahead of broad visual redesign while core blocks are still being closed. | Keep M7b two-account isolation, M7c reminder flow, M8b public card/location settings and M9b schedule validation in regression smoke. Next bounded implementation milestone is Platform Owner Invite / ADMIN Semantics Hardening. Keep structured venue-initiated reschedule proposals, media sections, general tickets, Platform Support Center, `Продвижение` and `Предпросмотр` out of nav until real routes/screens exist. |
| P1 CLOSED | Staff-chat main order vs doporders clarity | Product spec already models `order_batches` with statuses; Venue Mini App can show batches, and live staff-chat now separates the main order and doporders/add-batches in one message. Staging smoke passed: one live message, venue-local time without `UTC`, separate blocks and clear batch statuses/actions. | One live staff-chat message stays canonical, visually separates the main order and each doporder/add-batch, shows batch status, and applies action buttons to the correct operational context. | Keep in regression smoke. Preserve `OrderBillSnapshot` as money source. |
| P1 CLOSED | Guest table session persistence/restore | Backend has authenticated `GET /api/guest/table/restore`; Mini App startup restores the latest safe active table context when no explicit QR token is present, and explicit QR/start token still wins. Automated coverage includes active restore, cross-user denial, closed-only denial, latest-context selection, browser startup restore and account-switch storage isolation. Staging smoke passed on 2026-06-08: reopen without QR restores table context, `Мой заказ` / menu / profile / support navigation keeps context, Telegram BackButton no longer loops, and root can close cleanly. | While an active table session/tab/order exists, returning guest re-enters table context safely without rescanning QR; after bill close, table context resets. | Keep in regression smoke. Preserve QR/start-token priority and account-switch isolation. |
| P1 IN PROGRESS | Paid venue/shift extension | Backend data/API, Guest Mini App request UX, bill service charges, Venue Mini App owner/manager settings, Venue order queue/detail approval, Staff Chat pending approve/reject actions and Guest Bot ordering-menu section request entry are implemented. Existing `order_batch_items` require `menu_item_id`, so extension remains a separate service charge rather than a normal menu/cart item. Owner/Manager Bot settings parity is still pending. | Guest requests extension from active table context through service action `Продление работы заведения` in Mini App and bot ordering section flow; STAFF/MANAGER see and approve/reject fixed-price requests inside active order/table/bill context and staff-chat live order message; MANAGER/OWNER configure price/duration in Mini App and bot; approval adds a dedicated service charge and extends the active table/session orderable window. | Next slice: Owner/Manager Bot settings parity for enabled/duration/price with STAFF hidden/forbidden, then regression smoke/docs closure. Preserve STAFF no-settings rule and never expose extension as catalog item/cart item/order batch item. |
| P1 CLOSED | Guest/Menu Options & Flavors Parity | Backend selected-option persistence, Guest Bot structured submit, Guest Mini App option picker/cart identity, Venue Mini App item-scoped option CRUD, explicit item/option stop-list controls and Mini App shared base flavor profile apply are implemented. Staging smoke passed: new hookah item setup, idempotent `Добавить базовые вкусы`, item/flavor stop-list, manual flavor CRUD, hookah-only guest picker, `selectedOptionId` and `preferenceNote` all passed. | Menu item options/flavors are structured order modifiers. Guest Bot and Guest Mini App both show/select them; same item with different selected option or line-level `Пожелание к вкусу` is a distinct cart/order line; selected options affect price; line preference notes do not affect price; venue order detail, guest active order/bill, Guest Bot `Мой заказ` and staff chat show selected options/notes; unavailable/stop-listed options are hidden or rejected. | Keep in regression smoke. Remaining follow-ups are separate: Mini App normalize/reset if needed, DB-level duplicate/race protection if needed, and broad Venue Mini App IA parity. |
| P2 | Owner working days/hours/exceptions UX | Current owner bot has weekday base schedule controls and date-specific override controls (`open`/`closed`, time fields), which can be unclear when base day and override disagree. | UI should clearly separate weekly schedule from concrete-date exceptions, show whether a day is working/closed/overridden, and make each button's effect explicit. | UX audit/fix-pack after P1 operational blocks. |
| P2 | Owner timezone setup hint | `venue_settings.timezone` is the source of truth for venue-local time formatting. Current code has basic city/address inference rules; M8b-Free public-card location editing does not update timezone inference or add a rich owner-facing timezone suggestion flow. | Owner setup should suggest a timezone from city/address and clearly allow manual override; all venue-context guest/staff/manager/owner times should render in venue local time. | Later owner setup improvement. Keep timezone decisions separate from public-card route coordinates. |
| P2 | Local ФИАС/ГАР street-house suggestions | M8b-Free intentionally avoids runtime geodata APIs and only bundles a small local country/city seed; address remains manual and unverified. | If product needs full local street/house autocomplete, import selected ФИАС/ГАР regions into an indexed local table through an explicit maintenance command, update data offline, and never call ФИАС or another provider during normal user requests. | Separate data-engineering slice; do not commit multi-gigabyte archives or build a nationwide importer inside the settings UX diff. |
| P2 | `📖 Фото-меню` optional subsections | Current info/photo-menu model is a flat visible info section with media attachments; structured `🍽 Заказное меню` is separate. | Simple mode keeps one image list; advanced mode lets owner/manager enable subsections such as кальянное меню, напитки, чай, пробой посуды and custom sections. Guest sees subsections first when enabled. | Product model/read-model design; avoid confusing this with structured order menu. |
| P2 | Owner multi-image upload UX | Owner media upload keeps the upload state and confirms each media item, which can create repeated messages with `Готово`/`Назад`. | Multiple images should be collected without N noisy confirmation screens; after upload, return to an image list with change/delete/back actions. | Telegram UX debt fix-pack. Keep album-end logic explicit and avoid guessing Telegram media group completion. |

Post-M9b.3 checkpoint: M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed. The repository contains the committed opt-in ControlMaster helper, the normal deploy command remains supported and unchanged, one bounded retry opened the master after an SSH banner timeout, the actual staging deploy completed through the persistent connection, and local/public health, DB health and Mini App static checks passed. This proves the resilient path works; it does not prove why fresh SSH connections were dropped or permanently solve server SSH hardening. M7b Guest Mini App `Мои брони` is implemented with local validation plus staging visual comparison against Bot `/my` for public booking label, venue-local time and `Держим до`; only real two-account Telegram runtime isolation remains unverified. M7c adaptive reminders are implemented and passed one controlled real Telegram staging smoke for reminder delivery, visible message edit, attendance indicators, venue-controlled status preservation and idempotent repeat handling. The worker remains opt-in disabled by default, staging is back to `BOOKING_REMINDER_WORKER_ENABLED=false`, and broader operational rollout still needs explicit approval. The latest enriched staff-chat attendance copy is code/test-backed but was not manually re-smoked with a new booking. M8a/M8b-Free Venue Mini App structured public profile/card settings is CLOSED / staging smoke passed: OWNER/MANAGER can edit city/address/contact/description and structured country/city/address without runtime geodata providers, local country/city suggestions are bundled in the Mini App, missing cities and addresses stay manually enterable, STAFF is hidden/forbidden, guest public read models reflect saved fields, and route links prefer existing trusted coordinates while falling back to encoded text address search. Manually entered addresses are not verified coordinates. Yandex Geosuggest/Geocoder adapters remain disabled and optional/commercial-only until licensing changes, with separate Geosuggest and Geocoder key configuration and no real keys in repository or staging. M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 inclusive exception ranges/guest copy, M9b.2 exception save UX and M9b.3 date-range editing are CLOSED / staging smoke passed after owner smoke confirmed closed and changed-hours periods can be created, edited, have their date ranges changed and deleted, and guest/Bot closed-date paths reject with human copy. Do not reopen closed options/flavors, stats, booking lifecycle, M4A-M4C conversations, M5 staff calls, M6 staff-chat management, M7a hold settings, M7b, M7c, M8a, M8b-Free, M9a or M9b-M9b.3 unless new smoke/code evidence contradicts the current status.

### Internal AI Assistant Core

Status: `DONE / HARDEN`.

Done:

- `AiAssistantService`;
- `AiAssistantClient`;
- `FakeAiAssistantClient`;
- OpenAI provider behind feature flag;
- `AiToolRegistry`;
- `AiContextAssembler`;
- `AiAuditLogger`;
- `AiTelegramHandler`;
- OWNER/MANAGER `🤖 Помощник`;
- marketing contextual assistant entry;
- promotion diagnostics;
- draft promotion text;
- draft review reply;
- draft banner text;
- read-only summaries for promotion, feedback, loyalty and orders;
- audit metadata;
- STAFF denied;
- no write actions.

Hardening still needed:

- rate limits enforced and tested end-to-end;
- timeout/error UX for real provider;
- provider fallback behavior;
- audit review/export for operations;
- no-mutation regression tests for every AI tool;
- diagnostics expansion: loyalty, placements/top, Mini App access/initData, onboarding.

## 4. Launch Rule

Market launch requires:

- production-ready Telegram bot core;
- production-ready Guest/Venue/Platform Mini App core;
- stable backend config, CORS, Telegram WebApp URL, webhook URL and secrets handling;
- support baseline;
- billing/subscription baseline;
- monitoring and incident runbook;
- pilot smoke on 1-3 venues.

AI Core can be shipped as an assistant layer if feature flags, provider fallback and guardrails are safe.

Not required for first launch:

- Telegram Guest Mode;
- Telegram Business / Secretary Bots;
- Managed branded bots;
- Bot-to-Bot agents;
- autonomous AI write actions.

## 5. Unified Phase Order

### Phase 1 — Launch Stabilization For Bot + Mini App Core

Goal: finish pilot readiness without new product scope.

Scope:

- real Telegram runtime smoke for guest/venue/platform;
- production/tunnel config verification;
- webhook/outbox/staff notification monitoring;
- backup/restore baseline;
- support/runbook;
- billing/subscription launch baseline;
- role/permission smoke;
- final P0/P1 bugfix batch from pilot.

Done criteria:

- one guest can use QR/table Mini App end to end;
- one venue can operate orders and full bill in Mini App;
- platform owner can manage core venue/subscription state;
- staff receives Mini App staff calls and Mini App orders;
- full bill totals match bot and Mini App;
- no known P0 data leak, checkout, money or auth issue remains.

### Phase 2 — Mini App Production Completeness

Goal: move from launch-safe Mini App to full operational parity.

Guest Mini App:

- order menu option/flavor picker with structured selected-option cart identity;
- profile/history/favorites polish;
- richer active order display;
- support flow polish;
- bookings screens if in commercial scope;
- public venue cards with media/hours/promo previews.

Venue Mini App:

- real settings screen or explicit bot-canonical policy;
- bot-like information architecture shell for existing working screens;
- bookings screens stay in regression after current MVP;
- stats read-only screen is CLOSED after staging smoke; defer custom date range picker, arbitrary period stats, AI summaries and advanced analytics/platform dashboards;
- menu/options/flavors stay in regression; only optional normalize/reset, DB duplicate/race protection and later semantic/media polish remain;
- tables/QR lifecycle polish;
- staff calls dashboard polish.

Platform Mini App:

- onboarding dossier;
- placements controls;
- support/tickets;
- analytics;
- billing/invoices;
- platform operations runbook screens.

### Phase 3 — Internal AI Assistant Core Hardening

Goal: make AI safe enough for production use as an assistant layer.

Scope:

- promotion diagnostics;
- loyalty diagnostics;
- placement/top diagnostics;
- Mini App access/initData diagnostics;
- draft texts;
- read-only summaries;
- real provider behind feature flag;
- rate limits;
- timeouts;
- audit metadata;
- no raw prompt logging by default;
- no write actions without confirmation.

### Phase 4 — AI-Assisted Mini App / Owner Onboarding

Goal: use AI to reduce setup/support work without giving AI direct write authority.

Scope:

- setup assistant;
- venue profile draft;
- menu import draft;
- semantic menu type suggestions;
- promotion builder draft;
- analytics explanations;
- support assistant.

Guardrail:

- AI prepares drafts only.
- User reviews and confirms.
- Existing backend handlers perform writes after explicit confirmation.

### Phase 5 — Guest Concierge Inside Main Bot / Mini App

Goal: help guests discover venues and promotions inside owned surfaces.

Required tools:

- `searchVenues`;
- `getVenueMenu`;
- `getActivePromotions`;
- `getGuestLoyaltyProgress`;
- `getTableContext`;
- `openMiniAppDeepLink`.

Guardrail:

- only public/guest-visible data;
- no private table/order data except current authorized table context;
- no AI checkout/discount/order mutation.

### Phase 6 — Telegram Guest Mode

Goal: allow `@BotUsername` invocation in chats for public venue discovery.

Example:

> `@BotUsername найди кальянную на 4 человека сегодня вечером, чтобы была акция`

Prerequisites:

- reliable public search/ranking;
- public venue readiness model;
- active public promotions read model;
- safe Mini App deep links;
- rate limits;
- abuse controls;
- no private data leakage.

Not allowed:

- table/session/order context;
- guest personal history;
- owner/staff analytics;
- private promotion settings;
- internal billing/placement data.

### Phase 7 — Telegram Business / Secretary Bots

Goal: assistant for Telegram Business inboxes after consent model is ready.

Scope:

- Business connection consent;
- draft replies;
- FAQ automation only if explicitly enabled;
- audit per outgoing message;
- pause/escalation controls.

Not allowed in v1:

- autonomous booking confirmation;
- payment confirmation;
- order confirmation;
- staff/role management.

### Phase 8 — Managed Branded Bots

Goal: premium branded bot identity per venue.

Prerequisites:

- token vault;
- bot lifecycle management;
- webhook isolation;
- per-bot config;
- per-bot rate limits;
- owner transfer/disable policy;
- billing/tariff model;
- platform support tooling.

Postpone until main bot commercial flow is stable.

### Phase 9 — Bot-to-Bot Internal Agents

Goal: advanced automation only after strict loop protection.

Prerequisites:

- trace id;
- max depth;
- dedupe;
- per-agent rate limits;
- timeouts;
- loop detection;
- audit.

## 6. Market Launch Gate

Before market launch, close:

### Product / UX

- pilot smoke for guest QR -> menu -> cart -> checkout -> active order -> staff call;
- pilot smoke for venue queue -> order detail -> full bill -> status lifecycle;
- pilot smoke for platform venue/subscription baseline;
- no dead-end production-facing buttons;
- safe hidden state for features not in launch scope.

### Backend / Ops

- production webhook config;
- Telegram WebApp URL config;
- production CORS allowlist;
- initData validation path;
- outbox/inbound worker monitoring;
- staff notification failure monitoring;
- backup/restore process;
- support escalation path;
- billing/subscription baseline.

### Money / Orders

- full bill totals match bot and Mini App;
- manual discounts, exclusions, promo and loyalty lines match;
- canceled/rejected/excluded items do not affect payable total incorrectly;
- checkout idempotency remains green;
- loyalty and promotion resolver smoke passes.

### Security / Permissions

- OWNER/MANAGER/STAFF/PLATFORM/GUEST visibility smoke;
- cross-venue denied;
- cross-session table access denied;
- shared tab membership enforced;
- no secrets/tokens/initData/table tokens in logs or AI context.

## 7. Remaining Backlog

### Launch-Critical Follow-Up

1. Production config / infra readiness
   - Why: tunnel/local config is not launch infra.
   - Acceptance: stable backend/Mini App URLs, webhook URL, WebApp URL, CORS, secrets and environment profile documented.

2. Platform Owner Invite / ADMIN Semantics Hardening
   - Why: launch onboarding and access must not depend on conflicting Bot/API platform-owner identity, unsupported `ADMIN` UI, or copy-only owner invite handoff.
   - Acceptance: Bot and Mini App/API resolve platform-owner identity consistently; Platform Mini App no longer offers `ADMIN` as an assignable owner role; owner invite response exposes usable bot deep-link/copy when bot username is configured; owner invite create/accept/decline is auditable; non-platform users and cross-venue users remain denied.

3. Billing/subscription baseline
   - Why: platform launch needs clear venue state.
   - Acceptance: trial/paid/past-due/suspended rules are clear and visible; manual operational path exists.

### P1 Product Completeness

- remaining booking regression smoke, including real two-account Guest Mini App isolation and schedule validation;
- remaining backend-backed venue settings slices beyond booking hold, shift extension, public card/location and schedule;
- guest profile/history/favorites polish;
- custom date range venue stats and AI summaries;
- platform placements cockpit parity;
- platform support/tickets;
- platform analytics;
- owner invite/deep link acceptance hardening;
- expand frontend/browser e2e automation beyond the minimal Guest Mini App smoke.

### P2 / Future

- TODO: implement bot cart ↔ Mini App cart sync so cart items added in Telegram bot are visible in Mini App and vice versa.
- richer venue cards;
- media-heavy menu polish;
- advanced analytics;
- promo codes/cashback/tiered loyalty;
- managed branded bots;
- Telegram Business / Secretary automation;
- Telegram Guest Mode;
- Bot-to-Bot agents.

## 8. AI Roadmap Integrated With Product Roadmap

### Correct Model

Telegram AI Bots in this project are not "just OpenAI provider".

Layers:

- Internal AI Assistant Core: backend services, tools, context, audit, guardrails.
- LLM provider: fake/OpenAI/other provider behind feature flag.
- Telegram-native surfaces: Guest Mode, Business/Secretary Bots, Managed Bots, Bot-to-Bot and Mini App AI flows.

Facts always come from project DB/read models/tools:

- prices;
- menu;
- orders;
- tables;
- promotions;
- loyalty;
- reviews;
- venue status;
- platform operations.

AI is never source of truth.

### Already Implemented

- internal assistant foundation;
- fake provider;
- OpenAI provider behind feature flag;
- promotion diagnostics;
- draft promotion text;
- draft review reply;
- draft banner text;
- read-only summaries;
- audit metadata;
- OWNER/MANAGER access;
- STAFF denied;
- no write actions.

### Next AI Work After Launch Stabilization

1. Harden internal AI core
   - rate limits;
   - timeout/error UX;
   - provider fallback;
   - no-mutation tests;
   - audit review/export.

2. Add diagnostics
   - loyalty diagnostics;
   - placement/top diagnostics;
   - Mini App access/initData diagnostics;
   - onboarding readiness diagnostics.

3. AI-assisted onboarding
   - setup checklist;
   - menu import draft;
   - semantic type suggestions;
   - promotion builder draft;
   - analytics explanations.

4. Guest concierge inside owned surfaces
   - public venue search;
   - public menu/promotions;
   - Mini App deep links.

5. Telegram-native surfaces
   - Guest Mode;
   - Business/Secretary Bots;
   - Managed Branded Bots;
   - Bot-to-Bot agents.

## 9. AI Guardrails

AI can:

- explain current settings;
- diagnose likely reasons;
- summarize data;
- create drafts;
- propose next actions;
- prepare structured draft payloads;
- point to the correct screen/callback path.

AI cannot do these without explicit confirmation:

- change bill totals;
- close an order;
- apply discounts;
- change roles or staff access;
- publish/enable/archive promotions;
- send a reply to a guest;
- change menu items/prices/availability;
- change loyalty settings;
- approve placements;
- trigger broadcasts;
- confirm bookings/payments.

Sensitive data forbidden in AI context:

- bot tokens;
- API keys;
- auth headers;
- Telegram initData;
- QR/table tokens;
- payment data;
- DB credentials;
- webhook secrets;
- excessive guest PII;
- private chat history unless the user manually pasted it for a draft.

Audit metadata for AI actions:

- user id;
- role;
- venue id;
- tool/action name;
- prompt version;
- success/failure;
- failure code;
- no raw prompt by default.

## 10. Do Not Build Now

Do not start these before Mini App launch hardening and public-safe tools:

- Telegram Guest Mode implementation;
- Telegram Business / Secretary Bots;
- Managed branded bots;
- Bot-to-Bot agents;
- autonomous AI writes;
- AI order/payment/discount mutations;
- guest AI concierge before public search/ranking;
- advanced AI automations before Mini App hardening;
- large billing redesign during pilot readiness.

## 11. Supporting Documentation

Keep these documents:

- `docs/audit/MINI_APP_PRODUCTION_READINESS_AUDIT.md` - audit history and original P0/P1 findings.
- `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` - manual pilot smoke checklist.
- `docs/audit/PRODUCT_AUDIT_SUMMARY.md` - broader product audit context.
- role audit files under `docs/audit/ROLE_*.md`.
- `docs/audit/PRODUCT_IDEAS_REVIEW.md`.

Retired roadmap files removed:

- `docs/TELEGRAM_AI_BOTS_ROADMAP.md`;
- `docs/audit/BOT_FIRST_PRODUCT_ROADMAP.md`.

If a new roadmap is needed later, update this file instead of creating another roadmap source of truth.

## 12. Next Development Block

Latest booking block: M7c adaptive transactional reminders.

M7b status: IMPLEMENTED with code/test/e2e evidence and staging visual parity. Guest Mini App exposes `Профиль → Мои брони`, lists active/upcoming bookings across venues, uses backend public booking labels and venue-local display/deadline fields, and reuses existing guest change/cancel endpoints. Staging evidence covers the same booking's Bot `/my` versus Guest Mini App public label, venue-local time and `Держим до`; real two-account Telegram runtime isolation remains unverified.

M7c adaptive transactional booking reminders are implemented, code/test-backed and accepted by one controlled real Telegram staging smoke. Runtime remains disabled by default and currently false on staging; explicit `BOOKING_REMINDER_WORKER_ENABLED=true` is required for any future smoke or rollout.

- Purpose: one operational reminder per confirmed/changed booking to reduce forgotten visits, not marketing.
- Eligibility: only `CONFIRMED` or `CHANGED`; never pending, canceled, seated, no-show or expired.
- Timing: prefer 24h before booking only if confirmation/reschedule happened at least 6h before that target; otherwise try 3h before only if still future and at least 2h after confirmation/reschedule; if neither target is valid, send no scheduled reminder.
- Quiet time: calculate in venue-local timezone, default allowed window 10:00-22:00; move an out-of-window target to the nearest earlier allowed time, never later than target or after booking; skip when no earlier valid time remains.
- Message: venue, public booking number, venue-local date/time, party size and `Держим до HH:mm`.
- Buttons: `✅ Да, буду`, `🔄 Перенести`, `❌ Отменить`.
- Important: `Да, буду` / `Я приду` records `last_guest_confirmation_at` separately and must not overwrite venue-controlled booking status.
- Attendance idempotency: confirmation is atomic per booking schedule version. Repeated presses return `Вы уже подтвердили визит.`, do not rewrite the booking, and do not send another staff notification.
- Reminder UX: after a valid `Да, буду`, the same reminder message is edited to show `✅ Вы подтвердили, что придёте.`, the `Да, буду` button is removed, and `Перенести` / `Отменить` remain available.
- Lifecycle: reschedule cancels/replaces an unsent M7c schedule; if a reminder is already `QUEUED` or `SENT`, no second scheduled reminder is created in MVP.
- Reschedule/old actions: rescheduling clears the previous guest attendance response and requires a new response for the new time; reminder and Mini App attendance actions carry a schedule-version token so stale actions cannot confirm the new schedule.
- Cross-channel display: Bot `/my` and Guest Mini App show venue-controlled status as primary and guest response as secondary (`Ваш ответ: придёте`); Venue Mini App keeps the operational timestamp `Гость подтвердил визит: DD.MM.YYYY, HH:mm`.
- Staff mirror: attendance confirmation enqueues at most one deduplicated staff-chat operational update per booking schedule version.
- Staff copy: the latest staff-chat attendance update includes the public booking number, venue-local booking date/time, guest display name/fallback, party size and persisted hold deadline. This enriched copy is code/test-backed but was not manually re-smoked with a new booking.
- Delivery truth: worker writes `QUEUED` after Telegram outbox accepts the message; outbox status remains the delivery source of truth. `SENT` must not be written merely because the outbox row exists.
- Legacy reconciliation: V109 adds `policy_version`, marks legacy `PENDING`/`FAILED` rows `CANCELED`, and the worker only claims `policy_version='M7C'` rows. Recorded staging audit after acceptance: `LEGACY/CANCELED = 3`, `LEGACY/SKIPPED = 1`, claimable legacy rows `0`.
- Feature flag: missing/blank/malformed config and explicit `false` keep runtime disabled; explicit `true` is required for a later smoke.
- Acceptance: the core M7c real Telegram smoke passed for one reminder delivery, visible reminder edit, button state, attendance indicators, venue-controlled booking status and idempotent repeat handling; `/health` and `/db/health` returned ok and staging was returned to `BOOKING_REMINDER_WORKER_ENABLED=false`. Keep M7c in regression smoke before broader operational rollout.
