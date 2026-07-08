# Mini App Launch Smoke Checklist

Дата: 2026-07-08.

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
- STAFF booking actions are operational only: view bookings and mark `Гость пришёл` / `Не пришёл` only for `confirmed`; confirm/cancel/change/message/settings are MANAGER/OWNER-only.
- Booking guest communication has an M4A persisted thread layer and passed staging smoke after UX polish: `Написать гостю`, Guest Bot replies, Guest Mini App replies and Venue Mini App replies share one booking thread; staff chat is a notification/radar mirror, not the chat stream.
- M4B/M4C message inbox lifecycle is CLOSED after staging smoke: Guest and Venue Mini App show thread cards, context labels, unread state, active/resolved filters and resolve/reopen actions.
- M5 staff calls lifecycle is CLOSED after staging smoke: Guest Mini App uses a transient compose modal and compact NEW/ACK/DONE status, Venue Mini App `Вызовы` supports accept/close, and backend/staff-chat callbacks share the lifecycle.
- M6 staff chat diagnostics/unlink polish is CLOSED after staging smoke: Venue Mini App shows linked/unlinked state, masked chat id, link-code command, copy-first active-code card, regenerate confirmation, outbox-backed test-message result, OWNER-only unlink and relink flow.
- M7b Guest Mini App `Мои брони` passed staging visual parity for the same booking's public label, venue-local time and `Держим до` against Bot `/my`; real two-account Telegram runtime isolation remains unverified.
- M7c adaptive transactional reminders passed one controlled real Telegram staging smoke: one M7C reminder was created and delivered, `Да, буду` visibly updated the reminder message, `Да, буду` disappeared, transfer/cancel remained, Guest/Venue Mini App attendance indicators appeared, booking status stayed venue-controlled, repeat confirmation was idempotent and staff notification was deduplicated. The worker was returned to `BOOKING_REMINDER_WORKER_ENABLED=false`, and `/health` plus `/db/health` returned ok.
- M7c latest enriched staff-chat attendance copy is code/test-backed but was not manually re-smoked with a new booking.
- Booking arrival guard and staff-chat booking lifecycle buttons are CLOSED / staging smoke passed: Venue Mini App and staff-chat show `Гость пришёл` / `Не пришёл` only for confirmed bookings; pending/changed/terminal statuses have no arrival buttons; stale staff-chat arrival callbacks do not mutate booking; `BOOKING_CHAT`, `VENUE_CHAT` and `SUPPORT_TICKET` messages do not post to staff-chat.
- M8a/M8b-Free Venue Mini App structured public profile/card settings is CLOSED after provider-free staging smoke: OWNER/MANAGER can edit guest-facing country/city/address, public contact and short card description without a runtime geodata provider; country/city suggestions are local, missing cities and addresses remain manually enterable, STAFF is hidden/forbidden, and guest public venue card/catalog read models plus route links reflect saved fields. Existing coordinates remain supported for coordinate-first route links, but manually entered addresses are not verified coordinates. Yandex adapters remain optional/commercial-only and disabled by default.
- M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed: the committed opt-in ControlMaster helper opened one authenticated persistent connection after a bounded retry, reused that connection for rsync/plain SSH through the existing deployment script, completed image build/upload and backend recreate, and passed local/public health, DB health and Mini App static checks. The normal `./scripts/deploy-staging.sh hookah-staging` path remains supported and unchanged. The exact fresh SSH connection failure cause remains unconfirmed.
- M9b Venue Working Hours and Date Exceptions Mini App Parity plus M9b.1 date-exception ranges/rejection copy, M9b.2 exception save/list UX and M9b.3 date-range editing is CLOSED / staging smoke passed: OWNER/MANAGER can manage weekly hours, inclusive closed/special-hours exception ranges and optional guest-facing reasons/comments in Venue Mini App; successful exception saves close/reset the form and reveal the saved row in the compact list; existing closed and changed-hours exceptions can be edited to a new inclusive date range; STAFF is hidden/forbidden; guest catalog/card read models expose safe today schedule/open state; and direct Guest Mini App booking create/update validates configured venue hours with human schedule errors. Missing schedule setup shows `График не указан` / `Заведение пока не настроило график бронирования.`, not `Закрыто`.
- Mini App mutation / operational verification closure pack is CLOSED / code-test verification passed: actual Mini App PUT/PATCH/DELETE CORS preflights allow `Content-Type` and `Authorization`, Guest Mini App staff-call payload/backend row/staff-chat event include `tableSessionId`, linked staff-chat staff-call notification enqueue is covered, and fallback quick order emits `Telegram.WebApp.sendData` with `{ "cmd": "start_quick_order", "table_token": "<tableToken>" }`. No staging smoke is claimed by this item.
- Staff Call Lifecycle ACK/DONE audit hardening: CLOSED / staging smoke passed. Real Telegram Mini App smoke confirmed Guest call creation, staff-chat notification, Venue Mode NEW/ACK/DONE, Venue Mini App `STAFF_CALL_ACK` / `STAFF_CALL_DONE` audit with top-level actor evidence and `source=venue_miniapp`, Telegram staff-chat ACK/DONE message edits plus audit with `source=telegram_staff_chat`, and Guest ability to create a new call after DONE. Audit remains best-effort; row-level ACK/DONE actor/timestamp columns, CANCELLED UI/lifecycle and staff-call UX polish remain separate follow-ups.
- Guest Table Context UX Cleanup / Feature-gated Extension Module: CLOSED / staging smoke passed. Real Telegram Mini App QR smoke confirmed correct venue/table context, route/copy address/booking actions hidden in table context, pre-visit venue card still showing address/route/copy/booking, `Продление работы заведения` hidden without active order/bill or unavailable extension state, visible only when active order state makes it available, and hidden again after bill/order close.
- Guest Table Session Exit / Expiry UX: CLOSED / staging smoke passed. First staging attempt returned `415 Unsupported Media Type` on `POST /api/guest/table/session/end`; root cause was missing JSON `Content-Type`. Mini App now sends Authorization plus `Content-Type: application/json` and body `{ tableToken, tableSessionId }`, and e2e asserts URL/method/content-type/body. Post-fix deploy smoke confirmed `Завершить визит` works, reopening without QR no longer restores table 101 for that user, explicit QR re-enters, shared `table_sessions` are not closed for all guests, and empty tab/order/staff-call blocking rules are current-user scoped.
- Guest Bill / Display-Number / Full-Bill Parity: CLOSED / staging smoke passed. Guest sees `Заказ №N`, human account labels, clear no-discount/discounted bill rows and closed-bill copy; Venue Mini App / Bot / Guest totals match.
- Guest Bill Request / Payment Method UX: CLOSED / staging smoke passed. Guest sees `Попросить счёт`, payment choices appear directly under the action, the request carries structured payment method and duplicate active requests do not spam staff chat.
- Staff Chat Noise Reduction / Table Activity Card: CLOSED / staging smoke passed. New order, reorder, bill request and safe linked staff call update one live order card; unsafe/no-order/ambiguous calls stay standalone; manual `Обновить` preserves order/bill/call activity; markers `🆕`, `🚨`, `🛎️`, `🧾`, `💳`, `💵`, `❓` are visible; DONE/CANCELLED generic calls do not remain active; closing order/bill resolves linked active BILL requests and closed-visit staff-call leftovers.
- Hookah preparation placeholder polish: CLOSED / staging smoke passed. Nested hookah flavor/options notes use `Например: покрепче, полегче, больше мяты, без ментола`; food/drink notes keep `Например: без сахара, без льда, потеплее`.
- Platform Billing Cockpit / Owner Payment UX: CLOSED / staging smoke passed. Platform Owner billing cockpit and Venue Owner subscription screen show human paid-through/next-payment state through read-only GET overviews. Invoice/checkout creation uses explicit POST ensure actions. Manual/fake invoices do not expose provider-internal fake URLs, and manual mark-paid writes audit.
- Platform Billing Renewal / Advance Invoice / Courtesy Days: CLOSED / staging smoke passed. Next invoice period is based on effective paid-through + 1 day, repeated next-invoice ensure is idempotent, Platform Owner can create the next invoice in advance, `billing_adjustments` stores `COURTESY_DAYS`, Platform Owner courtesy/free days require reason, `BILLING_COURTESY_DAYS_ADDED` audit is written, paid-through/next-payment dates shift, Venue Owner sees adjusted state, and Manager/Staff cannot access payment controls.
- Staff/Manager invite deep-link sharing polish: CLOSED / staging smoke passed. Telegram invite messages use valid `t.me` staff invite links and copy-text buttons where supported; Venue Mini App invite result has one selectable invite link field, primary copy-link/share-in-Telegram actions, a secondary fallback command and no self-open result-card action. Manager/Staff invite acceptance smoke passed and payment controls stayed hidden/forbidden.
- Guest Communication UX / Support Tickets MVP: CLOSED / smoke passed. Canonical model is `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET`, `STAFF_CALL`; Guest nav is `Чаты` / `Помощь`; catalog/venue detail `Задать вопрос` opens/reuses `VENUE_CHAT`; booking `Открыть переписку` stays `BOOKING_CHAT`; Platform sees support tickets but not ordinary venue chats; Staff sees neither support tickets nor ordinary venue chats; support and venue chat create/reply paths do not post to staff-chat.
- Order/session/tab core docs: `docs/ORDER_SESSION_TAB_CORE.md` is the source of truth for `TABLE_SESSION`, `ACTIVE_TABLE_ORDER`, `ORDER_BATCH`, `TAB`, bill/request/close flow, privacy boundaries and visit-history foundation. Current runtime docs say table-session/tab scoping is closed; visit entity/history, force-close reason/audit, DB-level uniqueness nuances and broader analytics remain future/partial.
- Analytics/events docs: `docs/ANALYTICS_EVENTS.md` is the source of truth for analytics events, KPI formulas, dashboards, audit/event boundaries and payload privacy rules. Implementation remains partial/needs verification; client events must not drive money, access, billing or order state.
- Security/RBAC docs: `docs/SECURITY_RBAC_MATRIX.md` is the source of truth for roles, scopes, permissions, surface parity, dangerous actions, auth/trust boundaries and the security smoke checklist. Permission parity and dangerous-action audit remain partial unless route tests/smoke prove them.
- Menu/options/stop-list docs: `docs/MENU_OPTIONS_STOPLIST.md` is the source of truth for structured menu, option/modifier snapshots, media/PDF boundaries, featured/top-list, stop-list, shift check, availability validation and menu permissions. Selected-option parity is smoke-closed; broader menu constructor/media/top-list/shift-check/audit coverage remains partial/future.
- Venue operations docs: `docs/VENUE_OPERATIONS.md` is the source of truth for Venue dashboard, orders, order detail, batches, tabs/bill, staff calls, bookings, menu/stop-list, tables/QR, staff/invites, staff-chat, settings, stats and operational smoke. Venue Mode is source of truth; staff-chat is radar/shortcut only.
- Booking lifecycle docs: `docs/BOOKING_LIFECYCLE.md` is the source of truth for guest booking flow, Venue booking queue, statuses, hold minutes, `arrival_deadline`, confirmed-only arrival actions, reminders, `BOOKING_CHAT`, booking support routing, analytics, RBAC and booking smoke. Current queue/hold/list/chat/arrival-guard paths are smoke-closed by slice; reminder rollout, automation, preorder and visit-history integration remain partial/future.
- Telegram fallback/staff-chat docs: `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md` is the source of truth for Telegram bot entrypoints, QR `/start`, table-context bot menu, fallback chat order, bot staff-call, staff-chat link/test/unlink, notification policy, callback security and Telegram/Mini App parity. Staff-chat is radar/shortcut only.
- Testing/QA smoke strategy docs: `docs/TESTING_QA_SMOKE_STRATEGY.md` is the source of truth for change-type validation, GitHub Actions expectations, staging policy, manual smoke suites, failure reporting and Codex handoff.
- Deployment/runbook docs: `docs/DEPLOYMENT_RUNBOOK.md` is the source of truth for release model, staging deploy command, environment inventory, migration runbook, rollback policy, troubleshooting and incident response.
- Platform Cockpit docs: current source is `docs/PLATFORM_COCKPIT.md`. Manual billing and Platform Support Center are smoke-closed; onboarding request cockpit, placements, analytics, real acquiring/Stars, recurring payments and lifecycle normalization remain future/partial.
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

Status: `PASSED FOR CURRENT RELEASE THROUGH GUEST COMMUNICATION UX / SUPPORT TICKETS MVP`; baseline smoke passed on 2026-06-04, with later staged parity/deployment smokes recorded through M9b.3, guest table-context exit, guest bill/bill-request parity, staff-chat activity card, hookah placeholder polish, manual billing cockpit/renewal/courtesy, staff invite deep-link sharing polish and guest communication/support-ticket split.

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
- Venue Mini App M4A booking conversation threads passed staging smoke after UX polish: booking message creates/reuses a thread, quick compose closes after send, booking card shows `Открыть переписку`, Guest Bot and Guest Mini App replies persist in the same thread, Venue Mini App shows history, and staff chat receives notification mirror messages.
- Venue Mini App M4B/M4C messages inbox lifecycle passed staging smoke: Guest/Venue inbox cards show context, status, last message and unread; `Активные` / `Завершённые` filters work; `Завершить переписку` / `Возобновить переписку` moves threads without changing booking lifecycle.
- Venue Mini App M5 staff calls lifecycle passed staging smoke: guest call compose is transient and compact after submit; Venue `Вызовы` queue accepts/closes calls; DONE restores the normal guest call action.
- Venue Mini App M6 staff-chat management passed staging smoke: status/link-code generation, `/link@BotUsername <код>`, masked chat id, test-message queue/delivery path, OWNER-only unlink, relink flow, and polished active-code UI with copy/regenerate confirmation work.
- M7b `Мои брони` staging visual parity passed for Bot `/my` public booking label, venue-local time and `Держим до`; real two-account Telegram runtime isolation remains the only explicitly unverified M7b runtime check.
- M7c controlled real Telegram smoke passed: M7C reminder delivery, visible Telegram message edit after `Да, буду`, removal of the attendance button, retained transfer/cancel buttons, Guest/Venue Mini App attendance display, unchanged venue booking status, idempotent repeat confirmation, deduplicated staff notification, final `/health` and `/db/health` ok, and staging returned to `BOOKING_REMINDER_WORKER_ENABLED=false`.
- M7c legacy audit after smoke recorded `LEGACY/CANCELED = 3`, `LEGACY/SKIPPED = 1`, and claimable legacy rows `0`.
- M8a/M8b-Free public profile/card settings staging smoke passed: OWNER edited country, city, manual address, public contact and description; reload preserved the values; the guest card reflected the saved public fields; `Построить маршрут` opened from the saved textual address; STAFF remained denied/hidden; Yandex geodata remained disabled and unused.
- M9a ControlMaster deployment path staging smoke passed: initial master connection hit an SSH banner timeout, bounded retry opened the master, rsync upload, Docker build, image upload and backend recreate succeeded through the persistent connection, PostgreSQL stayed healthy, local `/health`, `/db/health` and Mini App static checks passed, public `/health`, `/db/health` and `/miniapp/` passed, and a separate retry-based public check also passed for all three endpoints.
- M9b/M9b.1/M9b.2/M9b.3 schedule smoke passed after the M9b.3 fix: OWNER smoke confirmed weekly schedule and date exception functionality; closed period and changed-hours period can be created, edited, have their date ranges changed and deleted; edited old dates no longer behave as exceptions and edited new dates do; guest booking on closed/out-of-hours dates is rejected with human copy; Bot closed-date path shows human copy and action buttons `📅 К выбору дат` plus `🏠 В каталог`.
- Guest Table Context UX Cleanup / Feature-gated Extension Module smoke passed: table QR opened the real Telegram Mini App with the correct venue/table; table context no longer made route/copy address/booking prominent; pre-visit venue card still showed address, route/copy address and booking; extension was hidden without active order/bill or unavailable state, appeared when active order state made it available, and disappeared after bill/order close.
- Guest Table Session Exit / Expiry UX smoke passed after the JSON `Content-Type` fix: `Завершить визит` moved the current guest to no-table mode, reopening Mini App without QR did not restore table 101, re-scanning QR restored table context, empty personal tab/no active order allowed exit, active current-user order/bill blocked, active current-user NEW/ACK staff call blocked, DONE staff call did not block, another guest at the same physical table was not kicked out, and menu/cart/order/staff-call/fallback flows still worked.
- Guest Bill / Display-Number / Full-Bill Parity smoke passed: Guest Mini App showed human order/account labels, clear bill totals/discounts/statuses and closed-bill copy, while Venue Mini App / Bot / Guest totals matched.
- Guest Bill Request / Payment Method UX smoke passed: payment method choices appeared in the right place, structured payment method reached staff context, active duplicate requests did not spam staff chat and generic `Счёт` did not remain a separate generic staff-call path.
- Staff Chat Noise Reduction / Table Activity Card smoke passed: new order, reorder, bill request and safe staff call updated the same live order card, manual refresh preserved activity sections, unsafe calls stayed standalone, DONE/CANCELLED generic calls stopped appearing as active, and bill/order close resolved linked active BILL and closed-visit staff-call leftovers.
- Hookah placeholder smoke passed: nested hookah flavor/options preparation note used hookah-specific examples and did not show the food/drink examples; drink/food options kept the generic copy.
- Platform Billing Cockpit / Owner Payment UX smoke passed: Platform Owner sees billing cockpit state, Venue Owner sees subscription/payment state, GET billing/subscription overviews are read-only, invoice/checkout ensure is POST-only, manual/fake invoice flow does not leak provider-internal fake URLs, manual mark-paid is audited, and paid-through/next-payment copy is human.
- Platform Billing Renewal / Advance Invoice / Courtesy Days smoke passed: next-period invoice starts from effective paid-through + 1 day, repeated ensure does not duplicate invoices, advance next invoice creation works, `COURTESY_DAYS` adjustments require reason, `BILLING_COURTESY_DAYS_ADDED` audit exists, paid-through/next-payment shift, Venue Owner sees adjusted state, and Venue Owner/Manager/Staff cannot access mark-paid/courtesy controls.
- Staff/Manager invite sharing smoke passed: owner/manager can share/copy the valid Telegram deep link from the Venue Mini App result, fallback command remains available in a secondary block, fresh account acceptance grants the intended Manager/Staff role, and Manager/Staff cannot access billing/payment controls.
- Guest Communication UX / Support Tickets smoke passed: Guest `Чаты` and `Помощь` are separate; catalog and venue detail `Задать вопрос` open/reuse `VENUE_CHAT`; ordinary venue chats are visible to Venue Owner/Manager and hidden from Staff/Platform; booking `Открыть переписку` still opens `BOOKING_CHAT`; support tickets are visible in Guest/Venue/Platform support surfaces with transfer to Platform; support and venue chat messages do not post to staff-chat.

Remaining:

- repeat this smoke after any additional release batch;
- keep M9b/M9b.1/M9b.2/M9b.3 schedule behavior in regression after future release batches;
- real acquiring provider remains future work;
- Telegram Stars remains future work;
- invoice void/reissue for courtesy conflicts with already-open future invoices remains a follow-up;
- billing-created versus manual `SUSPENDED_BY_PLATFORM` distinction remains a follow-up before broader auto-reactivation;
- Support/Tickets MVP beyond booking threads is closed and stays in regression; SLA automation, auto-escalation, macros, attachments, CSAT, diagnostics and support analytics remain future work;
- guest growth/retention flows remain `SPEC UPDATED / PARTIAL-FUTURE` in `docs/GROWTH_RETENTION.md`: favorites, visit/order/booking history, repeat templates, post-visit feedback, simple venue promotions and opt-in notifications are future smoke targets;
- platform analytics dashboards remain future work;
- P1 follow-up: paid venue/shift extension is implemented in backend, Guest/Venue Mini App, Guest Bot entry and staff-chat action path; remaining parity is Owner/Manager Bot settings smoke/closure where still needed by roadmap;
- P1 CLOSED: Guest/Menu Options & Flavors parity staging smoke passed. Guest Bot and Guest Mini App both submit structured selected options; Venue Mini App supports item-scoped hookah flavor CRUD, `Добавить базовые вкусы`, item-level stop-list and flavor-level stop-list. Keep this covered by regression tests for item scoping, unavailable option rejection and line-level preference notes.
- P1 CLOSED: Venue Mini App M2 read-only `Статистика` staging smoke passed. Keep periods, cards/top items, STAFF hidden state and empty state in regression.
- P1 CLOSED: M4B/M4C Unified Messages Inbox UX and lifecycle staging smoke passed. Keep M4A/M4B/M4C booking conversation behavior, multi-venue scoping, unread/status state and resolve/reopen actions in regression.
- P1 CLOSED: M5 staff calls lifecycle and compact Guest Mini App UX staging smoke passed. Keep linked Telegram group notification and inline ACK/DONE behavior in per-venue regression.
- P1 CLOSED: Guest Bill / Display-Number / Full-Bill Parity, Guest Bill Request / Payment Method UX and Staff Chat Noise Reduction / Table Activity Card staging smoke passed. Keep human order/account labels, payment-method request context, live-card dedupe/refresh, standalone unsafe calls and Venue Mode source-of-truth behavior in regression.
- P2 CLOSED: Hookah preparation placeholder polish staging smoke passed. Keep nested hookah flavor/options placeholder copy and generic drink/food placeholder copy in browser smoke.
- P1 CLOSED: Guest Table Context UX Cleanup / Feature-gated Extension Module staging smoke passed. Keep pre-QR vs table-context action separation and extension visibility gating in regression.
- P1 CLOSED: Guest Table Session Exit / Expiry UX staging smoke passed. Keep user-scoped `guest_table_session_exits`, JSON request contract, QR re-entry and active obligation blocking in regression.
- P1 CLOSED: M6 staff chat diagnostics/unlink polish staging smoke passed. Keep real Telegram group link/test/unlink and operational notification delivery in per-venue regression.
- P1 CLOSED: M7c adaptive transactional reminders passed core real Telegram staging smoke and remain disabled by default for rollout. Keep feature flag, legacy-row isolation, attendance idempotency, message editing and staff notification dedupe in regression. The enriched staff-chat attendance copy after the smoke is code/test-backed only.
- P1 CLOSED / staging smoke passed: M8a/M8b-Free Venue Mini App structured public profile/card settings exposes guest-facing public location/contact fields (`countryCode`, `city`, `address`, `formattedAddress`, optional coordinates, `guestContact`, `cardDescription`) for OWNER/MANAGER; STAFF stays denied/hidden; provider-free country/city suggestions and manual address entry are the primary flow. Keep guest public card/catalog reflection, route links, validation and tenant isolation in regression.
- P2 stats follow-up: custom date range picker (`from`/`to`), arbitrary period stats and future AI-generated summaries/insights.
- P2 follow-ups remain: optional `📖 Фото-меню` subsections, quieter owner multi-image upload, expand frontend/browser e2e beyond the minimal Guest smoke, richer Platform cockpit parity and optional lifecycle restore semantics if product wants restore to non-published state.

## 1. Automated Coverage Map

### Guest Mini App

Покрыто backend/API tests:

- `CorsPreflightMiniAppRoutesTest`
  - actual Mini App mutation paths for PUT/PATCH/DELETE pass preflight from allowed Mini App origin;
  - allowed methods and headers include `Content-Type` and `Authorization`.
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
- `GuestVenueRoutesTest`
  - catalog and venue-by-id read models expose safe today schedule/open state only through existing guest visibility gates.
- `GuestBookingRoutesTest`
  - direct guest booking create/update rejects closed weekdays, closed date overrides and out-of-hours scheduled times;
  - valid in-hours booking remains accepted.
- `TelegramBotRouterTableTokenTest`
  - WebApp fallback sends supported `cmd=start_quick_order`.

Known option/flavor coverage:

- Guest Mini App smoke covers item option/flavor selection, selected option persistence in cart submission and line-level preference notes.
- Guest Mini App smoke covers fallback quick-order `Telegram.WebApp.sendData` payload and asserts the action is not a silent no-op.
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
- fallback chat order sends `cmd=start_quick_order` and the current `table_token` through `Telegram.WebApp.sendData`.
- guest `Чаты` screen opens, shows persisted booking and venue chat threads when present, allows reply, and uses safe empty state when there are no chats.
- guest `Помощь` creates/lists support tickets separately and does not auto-open an arbitrary ticket from the list.

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
- `VenueBookingRoutesTest`
  - OWNER/MANAGER can configure weekly hours and date exceptions;
  - STAFF and foreign-venue access are denied for private schedule settings.

Manual runtime coverage for each release batch:

- Venue Owner/Manager/Staff entry is sent as inline `web_app`, not plain URL;
- queue uses `Заказ №<displayNumber>`;
- order detail renders the user-facing bill block without technical copy;
- UI displays backend totals directly and does not invent frontend-calculated money.
- STAFF close bill/order works while bill edit controls stay hidden;
- venue `Сообщения` handles `BOOKING_CHAT` / `VENUE_CHAT`; venue `Помощь` / `Обращения` handles own-venue `SUPPORT_TICKET` and transfer to Platform.

### Platform Mini App

Покрыто backend/API tests:

- `PlatformRoutesTest`
  - only platform owner can access platform root/user list.
- `PlatformVenueRoutesTest`
  - non-owner denied;
  - platform owner can assign owner and publish only after owner membership;
  - owner invite creates owner access;
  - owner invite create/accept audit exists;
  - platform owner can revoke one OWNER when another active OWNER remains;
  - last active OWNER revoke is blocked;
  - non-platform user cannot revoke OWNER;
  - revoked OWNER loses `/api/venue/me` access through `venue_members`;
  - `venues.owner_account_id` is unchanged by membership revoke;
  - `VENUE_OWNER_REVOKE` audit exists;
  - platform cockpit smoke: venue list/detail expose venue and subscription basics.
- `PlatformSubscriptionRoutesTest`
  - subscription settings are platform-owner-only;
  - pricing/date validation;
  - effective price uses override, schedule, then base price.

Manual runtime coverage for each release batch:

- `#/venues` opens platform cockpit;
- `PLATFORM_OWNER_TELEGRAM_ID` grants access without requiring a separate non-empty legacy owner id;
- Platform Mini App owner assignment does not offer `ADMIN`;
- owner invite returns usable Telegram deep link/copy text and Telegram `/start staff_invite_<code>` acceptance grants OWNER for the intended venue;
- owner invite create/accept audit evidence exists;
- venue detail owner list is active OWNER memberships from `venue_members`;
- Platform Owner can revoke an old OWNER when another active OWNER remains;
- revoked OWNER loses Venue Mini App access and Telegram Bot venue-owner access for that venue;
- last OWNER revoke is blocked with safe copy;
- non-platform user cannot create owner invite or revoke OWNER;
- `owner_account_id` and `venue_owner_accounts.primary_owner_user_id` are not relinked by membership revoke;
- `VENUE_OWNER_REVOKE` audit evidence exists;
- safe sections `#/onboarding`, `#/placements` and `#/analytics` show explanations without fake data or dead-end controls.
- `#/support` / Platform `Обращения` is real only for backend-backed `SUPPORT_TICKET`; it must not show ordinary `VENUE_CHAT`.
- Platform Owner billing cockpit shows current paid state, next period dates, explicit next-invoice action and courtesy-days action.
- Platform billing and Venue subscription GET overviews are read-only; invoice/checkout ensure and courtesy-days actions happen only through POST.
- Platform Owner can create/reuse current and next-period invoices, mark manual invoice paid with audit, add courtesy/free days with required reason and audit, and repeat next invoice ensure without duplicates.
- Venue Owner sees adjusted paid-through and next-payment state but cannot mark paid or add courtesy days.
- Manager/Staff cannot access payment controls after invite acceptance.
- Current lifecycle smoke uses `DRAFT`, `PUBLISHED`, `HIDDEN`, `PAUSED`, `SUSPENDED`, `ARCHIVED`, `DELETED`; target states `onboarding`, `paused_by_owner`, `suspended_by_platform` and `deletion_requested` are future normalization work.
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
12. Open `Помощь`.
13. Confirm support tickets are separate from staff calls: table-context issue creation attaches safe context, while urgent live service still uses `Вызвать персонал`.

Expected:

- pre-QR card has no order-menu categories/items or add-to-cart actions;
- info-section media is browser-readable without exposing Telegram token/file URL;
- request payload includes `tableSessionId`;
- order belongs only to current `tableSessionId/tabId`;
- no cross-session order appears after opening the same physical table with a different session.
- support tickets are clearly separated from staff-call operations and do not post to staff-chat.

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
18. Open `Вызовы`, accept and close a staff call. M5 staging smoke passed for Guest Mini App create/status and Venue Mini App accept/close; per-venue regression must still confirm the linked Telegram staff group receives the new-call notification.
19. Open `Брони`: as STAFF, verify `Гость пришёл` / `Не пришёл` only for confirmed bookings and no arrival buttons for pending/changed; as MANAGER/OWNER, confirm/change/cancel and `Написать гостю` as allowed.
20. As MANAGER/OWNER, click `Написать гостю`, confirm there are no template buttons, send a message, and confirm the modal closes with `Сообщение отправлено гостю.`
21. Open `Сообщения` and confirm the same booking thread is listed.
22. Open `Помощь` / `Обращения` as MANAGER/OWNER.
23. Confirm own-venue support tickets list/detail work or show a safe empty state, and STAFF does not see this support management entry.

### M5 staff calls lifecycle smoke status

Status: CLOSED / staging smoke passed for lifecycle UI, `tableSessionId` payload, staff-chat notification delivery, Telegram staff-chat ACK/DONE callbacks and ACK/DONE audit hardening across Venue Mini App and Telegram staff-chat surfaces.

Automated smoke target:

1. Guest opens active table context and sends `Вызвать персонал` with `tableToken`, `tableSessionId`, reason and optional comment.
2. Staff-call compose opens as a transient modal; successful send closes it and leaves the guest on the venue/menu screen.
3. Guest sees a compact status card with `Вызов отправлен`, then `Персонал принял вызов`, then `Вызов выполнен`; ACK is success-styled, not red/error-styled.
4. While NEW/ACK exists, the table-context action is `Вызов активен` and does not open a duplicate compose form.
5. DONE restores the normal `Вызвать персонал` action.
6. STAFF opens Venue Mini App `Вызовы`, sees table/reason/comment/guest, clicks `Принять`, then clicks `Закрыть`.
7. DONE calls leave the active queue.

Per-venue regression smoke:

1. Linked staff Telegram chat receives Mini App-created staff call notification.
2. Staff chat inline `Принять` / `Выполнено` callbacks still edit the group message and do not diverge from Venue Mini App queue state.
3. Applied Venue Mini App ACK/DONE transitions write `STAFF_CALL_ACK` / `STAFF_CALL_DONE` with actor evidence and `source=venue_miniapp`.
4. Applied Telegram staff-chat ACK/DONE callbacks write `STAFF_CALL_ACK` / `STAFF_CALL_DONE` with callback actor evidence and `source=telegram_staff_chat`.
5. Repeated/stale transitions do not create false audit rows; audit insert remains best-effort and must not roll back an already-applied operational transition.

### STAFF booking RBAC split smoke status

Status: local dev smoke PASSED on 2026-06-04; staging deploy/smoke PASSED on 2026-06-04.

Local dev via `dev.hookahtootah.club` - PASSED on 2026-06-04:

1. Stop staging backend so the same bot token is not processed by two backends.
2. Run local backend, local Vite and SSH reverse tunnel according to `docs/LOCAL_TELEGRAM_MINIAPP.md`.
3. Open Venue Mini App as STAFF through Telegram inline `web_app`.
4. Open `Брони`.
5. Confirm STAFF sees booking list/details.
6. Confirm STAFF sees only `Гость пришёл` and `Не пришёл` on confirmed bookings, and no arrival buttons on pending/changed bookings.
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
- STAFF booking management paths are denied while confirmed-only arrival/no-show remains allowed.
- MANAGER/OWNER booking management remains unchanged.
- venue `Сообщения` is a real M4B/M4C venue-scoped inbox with context/status/unread cards and resolve/reopen lifecycle. Staging multi-venue smoke must keep verifying tenant scoping before broader support/ticket UX. Venue `Поддержка` remains a launch-safe informational path, not a half-working ticket UI.

### M6 staff chat diagnostics/unlink smoke target

Status: CLOSED / staging smoke passed. Manual Telegram group smoke remains per-venue regression.

Checks after implementation:

1. OWNER opens Venue Mini App `Чат персонала`.
2. Screen shows linked/unlinked state, masked chat id if linked, active link-code hint if present and clear `/link@BotUsername <код>` instructions after code generation.
3. OWNER can generate a new link code, sees compact `Код привязки готов` card, copies the backend-built command and regenerates only after confirmation.
4. OWNER can send a test message; Mini App says `Тестовое сообщение поставлено в отправку` when the outbox accepts it, not `доставлено`.
5. OWNER can unlink an incorrectly linked staff chat only after explicit confirmation.
6. MANAGER can use only the status/link-code/test actions allowed by backend RBAC and cannot unlink.
7. STAFF does not see `Чат персонала`; direct staff-chat management API calls remain denied.
8. Linked Telegram staff group receives the diagnostic test message.
9. Linked Telegram staff group receives runtime notifications for order, booking, staff call and shift-extension events.
10. Existing order/booking/call/extension notifier behavior is unchanged.

Automated coverage:

- Backend route/RBAC tests cover OWNER status/link/test/unlink, MANAGER status/link/test but no unlink, STAFF denial, foreign venue denial and safe repeated unlink.
- `StaffChatNotifierTest` covers diagnostic test-message payload and verifies it does not include guest/contact data.
- Mini App e2e covers OWNER linked/unlinked UI, link-code command, test-message queued copy, unlink confirmation, MANAGER no-unlink UI and STAFF hidden route/nav.

Follow-ups:

- Last successful operational notification diagnostics.
- Notification event history and delivery failure surfacing from outbox/Telegram worker.
- Per-event notification controls.
- Personal staff subscriptions.
- Telegram forum-topic routing.
- SLA alerts.

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
6. Confirm venue status controls are visible and dangerous lifecycle actions require explicit action/confirmation where implemented.
7. Confirm owners/invite/subscription/price schedule basics are visible.
8. Confirm deleted venues are not shown in the normal/default list.
9. Open `Подключение`.
10. Open `Размещения`.
11. Open `Обращения` / Platform Support Center.
12. Open `Аналитика`.
13. Open billing cockpit and verify GET overview did not create invoices, checkout links, lifecycle rows or adjustments.
14. Create/reuse current or next invoice through explicit POST action; repeat next invoice ensure and verify no duplicate invoice.
15. Mark invoice paid manually and verify audit plus human paid-through/next-payment copy.
16. Add courtesy/free days with required reason and verify `billing_adjustments`, `BILLING_COURTESY_DAYS_ADDED` and no mutation of paid invoices.
17. Open platform-assigned/transferred support ticket, reply/close where implemented, and confirm ordinary `VENUE_CHAT` is absent.
18. Confirm Staff cannot see Platform Mode or Platform Support Center.
19. Confirm lifecycle, owner, billing and support audit payloads contain safe ids/status/scope/reason fields and no raw provider/Telegram payloads.

Expected:

- real venue/subscription sections use backend data;
- safe onboarding/placement/analytics sections contain no fake numbers;
- safe sections do not expose half-working approve/pay controls;
- Platform Support Center is real only for backend-backed `SUPPORT_TICKET` and does not show ordinary `VENUE_CHAT`;
- real acquiring provider, Telegram Stars and recurring payments are not claimed by this smoke;
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
- Staff-chat activity-card behavior passed staging smoke; keep order-linked card updates, manual refresh with activity sections, bill request payment markers and safe/unsafe staff-call split in regression smoke.
- Guest table session restore and Telegram BackButton navigation passed staging smoke; keep restore, QR priority, account-switch isolation and no-loop BackButton behavior in regression smoke.
- Paid venue/shift extension is implemented for backend, Guest/Venue Mini App, Guest Bot table menu entry and staff-chat pending action path as a confirmed service charge/session extension, not as a normal menu/cart/order-batch item. Remaining parity gap: Owner/Manager Bot settings closure where still needed by roadmap.
- Guest/Menu Options & Flavors parity is CLOSED after staging smoke: owner/manager can create hookah items, apply canonical base flavor profiles only to that item, repeat apply without duplicates, manage flavor CRUD and stop-list, stop-list the whole item, water/kitchen/drink items do not receive hookah flavors, Guest Mini App shows the picker only for the selected hookah item, hookah preparation placeholder copy is hookah-specific even in nested flavor/options flow, and `selectedOptionId` / `preferenceNote` still work.
- `📖 Фото-меню` is currently a flat info-section media list; optional owner-defined subsections are a P2 follow-up.
- Owner multi-image upload remains a Telegram UX follow-up: current flow may confirm each media upload separately.
- Platform Mini App onboarding/placements/analytics are still partial/safe sections, not full cockpit parity; Platform Support Center for `SUPPORT_TICKET` is smoke-passed and stays in regression.
- M4A booking conversation threads, M4B/M4C unified inbox lifecycle and the later Guest Communication UX / Support Tickets MVP are staging/smoke-closed. Guest/Venue inbox cards show multi-venue/context clarity, status/unread state, active/resolved filters and explicit `Завершить переписку` / `Возобновить переписку` actions. Venue/admin bot full inbox, structured reschedule proposals, advanced support automation/diagnostics, attachments, CSAT, support analytics and DB-level duplicate/race protection are follow-ups.
- Broad backend test wildcards may hit heap/runtime limits; CI now uses green split release-validation jobs, and local release checks should prefer the targeted smoke/regression commands.

## 10. Order/Session/Tab Core Smoke Checklist

Use this checklist after any order/session/tab, bill, tab, staff-chat order-card, fallback order or visit-history change. Canonical model: `docs/ORDER_SESSION_TAB_CORE.md`.

1. Guest scans QR and gets `tableSessionId`.
2. Guest personal tab is created/resolved.
3. Guest sends first batch.
4. Guest sends second batch; same active order/session, new batch.
5. Second guest scans the same physical table and gets own personal tab.
6. Second guest cannot see the first guest personal tab.
7. Shared tab invite/join requires explicit consent.
8. Guest can add batch only to own personal tab or joined shared tab.
9. Venue queue shows table with batches.
10. Venue detail shows batches and tabs.
11. Staff changes batch status.
12. Guest active order view is scoped to selected tab.
13. Staff chat receives order notification only and remains a mirror/shortcut, not source of truth.
14. Close/expire session prevents a new batch from entering the old active order.
15. Re-scan after close creates or uses the expected new session.
16. Fallback chat order creates a batch with the same session/tab rules.
17. Stop-list change before submit blocks unavailable item/option.

## 11. Future Growth/Retention Smoke Checklist

Use this checklist only after a dedicated Growth/retention implementation milestone. Current status is `SPEC UPDATED / PARTIAL-FUTURE` in `docs/GROWTH_RETENTION.md`.

1. Guest can favorite and unfavorite a venue from catalog/card.
2. Guest sees favorite venues.
3. Guest sees visit, order and booking history with safe public labels.
4. `Повторить` creates a repeat template and requires table context before any order is created.
5. Repeat template skips or clearly marks unavailable/stopped items.
6. Feedback is requested only after confirmed visit.
7. Low rating does not automatically push a public review link.
8. Promotion is visible only during active period.
9. Suspended/hidden venue promotions are not visible.
10. Promo/retention notifications require opt-in and can be disabled.
11. Staff does not see or manage growth campaigns.
12. Platform paid placement label is visible if/when paid placement is implemented.

## 12. Analytics/Events Smoke Checklist

Use this checklist after analytics/event emission, dashboard, audit, billing, support, booking or growth instrumentation changes. Canonical model: `docs/ANALYTICS_EVENTS.md`.

1. QR scan emits or records `table_session_started` where implemented.
2. Add-batch emits `order_batch_created` where implemented.
3. Batch status change emits `order_batch_status_changed` where implemented.
4. Staff-call create emits `staff_call_created` where implemented.
5. Booking status change emits the relevant `booking_*` event where implemented, otherwise remains marked future.
6. Support ticket create/status/transfer emits the relevant `support_*` event where implemented, otherwise remains marked future.
7. Menu availability/price/media/shift-check changes emit the relevant `menu_*` / `shift_check_completed` events where implemented, otherwise remain marked future.
8. Subscription state change emits the relevant `subscription_*` event where implemented.
9. Audit log exists for role/status/billing/menu dangerous changes.
10. Analytics payload contains no message text, raw Telegram payload, raw initData, payment secrets, card data or unrelated PII.
11. Platform Owner can see/export relevant analytics only where implemented.
12. Venue Owner cannot see another venue analytics.
13. Staff does not see platform analytics.

## 13. Security/RBAC Smoke Checklist

Use this checklist after role, navigation, auth, table/session/tab, support/chat, billing, platform lifecycle, staff-chat or analytics/audit changes. Canonical model: `docs/SECURITY_RBAC_MATRIX.md`.

1. Guest cannot access venue or platform APIs.
2. Guest cannot see another guest's personal tab.
3. Guest cannot add a batch to another guest's personal/shared tab without membership.
4. Staff cannot see `Помощь` / `Обращения` or ordinary venue chats.
5. Direct Staff support/chat/billing/settings/platform API access returns denial.
6. Manager cannot access billing payment controls or create/remove Owner access.
7. Venue users cannot access another venue's orders, bookings, support tickets, chats, settings or analytics.
8. Platform Owner can see support tickets but not ordinary `VENUE_CHAT`.
9. Platform lifecycle actions require confirmation/reason/audit where implemented.
10. Table token and tab invite token do not grant admin or cross-user rights.
11. Revoked QR/table tokens do not resolve.
12. Staff-chat button actions verify role and entity scope server-side.
13. Analytics export, if implemented, contains no raw PII, message text, raw initData, provider payloads, payment secrets or card data.

## 14. Menu/Options/Stop-List Smoke Checklist

Use this checklist after menu, option, stop-list, media, featured/top-list, shift-check, cart, checkout or role-permission changes. Canonical model: `docs/MENU_OPTIONS_STOPLIST.md`.

1. Owner creates a category.
2. Owner creates an item with price.
3. Owner creates an option group and option values.
4. Owner toggles item unavailable; guest cannot order it.
5. Owner toggles option value unavailable; guest cannot select/order it.
6. Guest has item in cart, then item becomes unavailable; submit is rejected with safe stale-availability copy.
7. Price changes after order do not alter existing order snapshot.
8. Archived item remains visible in old order history/bill snapshot.
9. Manager can or cannot edit price according to the current product policy.
10. Manager can toggle stop-list if policy allows.
11. Staff cannot edit menu structure or price.
12. Staff stop-list behavior is identical in Telegram Bot and Venue Mini App according to current policy.
13. Stop-list actions write audit where implemented.
14. Mass availability update requires confirmation/audit.
15. Guest menu hides or greys unavailable items/options based on venue policy.
16. Staff-chat does not become source of truth for menu edits.
17. Telegram callback actions verify role and venue scope server-side.

## 15. Venue Mode Operational Smoke Checklist

Use this checklist after Venue Mode dashboard, orders, bills/tabs, staff calls, bookings, menu/stop-list, tables/QR, staff/invites, staff-chat, settings, stats or role-navigation changes. Canonical model: `docs/VENUE_OPERATIONS.md`.

1. Owner opens Venue Mode dashboard.
2. Manager opens Venue Mode dashboard.
3. Staff opens Venue Mode and sees only allowed sections.
4. Guest creates order batch from table context.
5. Venue queue shows table/order with new batch.
6. Venue detail shows batch, items, selected option snapshots and safe line comments where configured.
7. Staff updates allowed batch status.
8. Staff cannot reject/close if policy forbids.
9. Manager can reject with reason if policy allows.
10. Full bill/tabs are visible according to current implementation.
11. Guest requests bill; venue sees `bill_requested`.
12. Guest creates staff call; Venue/Staff sees it.
13. Staff-call ACK/DONE works if implemented, otherwise the expected gap is recorded.
14. Booking appears in queue if implemented.
15. Venue confirms/changes/cancels booking if implemented.
16. Owner toggles item stop-list; guest cannot order unavailable item.
17. Staff stop-list behavior matches current policy across Telegram and Mini App.
18. Owner downloads QR package where implemented.
19. QR rotate requires confirmation/audit where implemented.
20. Owner links staff-chat and sends test message.
21. Staff-chat receives order/staff-call notifications but not support tickets or venue chats.
22. Manager cannot access billing.
23. Staff cannot access settings, billing, support tickets or venue chats.
24. Venue user cannot access another venue.

## 16. Booking Lifecycle Smoke Checklist

Use this checklist after booking create/list, Venue booking queue, status actions, hold/deadline, reminders, booking chat, support routing, staff-chat notification or RBAC changes. Canonical model: `docs/BOOKING_LIFECYCLE.md`.

1. Guest creates booking from venue detail.
2. Guest sees booking in `Мои брони` / profile/history where implemented.
3. Venue Owner/Manager sees booking in Venue Mode queue.
4. Venue confirms booking.
5. Guest sees confirmed status and `Бронь держится до HH:mm` when hold is known.
6. Venue proposes another time.
7. Guest accepts proposed time where implemented.
8. Guest cancels booking while allowed.
9. Venue cancels booking with reason.
10. Confirmed booking remains active until `arrival_deadline`.
11. Pending booking Venue/Mini App card and staff-chat message have no `Гость пришёл` / `Не пришёл`.
12. Confirmed booking Venue/Mini App card and staff-chat message have arrival buttons.
13. Changed/proposed-time booking has no arrival buttons.
14. Stale staff-chat arrival callback answers safely and does not change booking state.
15. Venue marks confirmed guest seated.
16. Venue marks confirmed booking no-show after deadline/manual policy.
17. No-show does not create visit/history success.
18. Seated booking can link to exactly one `BOOKING_SEATED` visit if visit foundation exists.
19. Booking `Открыть переписку` opens `BOOKING_CHAT` / `Чаты`, not Support.
20. Booking support issue requires verified booking or venue context.
21. Booking chat messages do not post to staff-chat.
22. Support/venue chat messages still do not post to staff-chat.
23. Venue users cannot access another venue booking.
24. Staff access matches current RBAC policy: view + confirmed-only seated/no-show.
25. Booking analytics/audit events exist where implemented and payloads contain no raw message text/initData/secrets.

## 17. Telegram Fallback / Staff-Chat Smoke Checklist

Use this checklist after Telegram bot entrypoints, table context, fallback order, staff-call, staff-chat link/test/unlink, notification policy, callbacks or role-menu changes. Canonical model: `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`.

1. Guest starts bot without table and sees global menu.
2. Guest scans table QR and sees venue/table context.
3. Table QR menu opens Mini App.
4. Fallback chat order creates order batch with `source=bot_fallback`.
5. Fallback chat order appears in Venue Mode queue.
6. Fallback chat order sends staff-chat notification if staff-chat is linked.
7. Guest staff-call from bot creates `STAFF_CALL`.
8. Staff-call appears in Venue Mode / operational queue.
9. Staff-call is not mixed with support tickets.
10. Staff-chat receives order/staff-call notifications.
11. Staff-chat does not receive `SUPPORT_TICKET` or `VENUE_CHAT` messages.
12. Staff-chat callback action checks role and venue scope.
13. Unauthorized user pressing staff-chat button gets safe denial.
14. Booking `Открыть переписку` opens `BOOKING_CHAT` / `Чаты`, not Support.
15. Pending booking staff-chat message has no `Гость пришёл` / `Не пришёл`.
16. Confirmed booking staff-chat message has arrival buttons.
17. Changed/proposed-time booking staff-chat message has no arrival buttons.
18. Terminal booking statuses have no dangerous action buttons.
19. Stale booking callback answers `Бронь уже изменилась. Откройте кабинет.` and does not change booking.
20. Booking chat message does not appear in staff-chat.
21. Support booking issue creates `SUPPORT_TICKET` with verified booking/venue context.
22. Staff cannot see support tickets or venue chats from Telegram.
23. Manager cannot access billing/settings from Telegram.
24. Platform Owner can access Platform mode and has a way to test guest QR if product requires it.
25. Multi-venue user selects correct venue or current gap is documented.

## 18. QA Strategy / Release Gate Checklist

Canonical model: `docs/TESTING_QA_SMOKE_STRATEGY.md`.

1. Change type is classified before validation.
2. `git status --short` and `git diff --check` are clean for intended files.
3. `scripts/dev/` is not staged unless an explicit tooling task says so.
4. Relevant backend/Mini App/Telegram validation commands were run or explicitly skipped with reason.
5. GitHub Actions failure reports include job, test class, test name and assertion, not only Gradle tail.
6. Runtime changes wait for green Actions before staging deploy unless doing an explicit debug deploy.
7. Staging/manual smoke matches the changed product area.
8. Final Codex/ChatGPT handoff includes changed files, behavior, tests, validation, manual smoke, `git status --short` and staging need.

## 19. Deployment / Runbook Checklist

Canonical model: `docs/DEPLOYMENT_RUNBOOK.md`.

1. Docs-only changes skip staging deploy.
2. Runtime changes use the current staging deploy command after green Actions.
3. Staging smoke starts with `/health`, `/db/health` and `/miniapp/`.
4. Migration releases record compatibility and rollback/forward-fix decision.
5. Telegram/staff-chat changes include real Telegram smoke.
6. Billing/webhook changes include safe provider/audit/log checks.
7. No secrets, raw `.env` values or provider keys appear in docs, logs or handoff.
8. Rollback gaps are explicitly marked `RUNBOOK GAP` instead of invented commands.

## 20. Recommended Next Test Investment

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

## 21. Next Implementation Smoke Target

Current implementation block after M9b.3: M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed, with the standard deploy command still supported and the opt-in ControlMaster helper validated as a persistent-connection workaround for unreliable fresh SSH/rsync connections. The exact SSH/network root cause remains unconfirmed and belongs to future operations hardening, not the M9a closure. M7a booking hold settings is CLOSED / staging smoke passed. M7b Guest Mini App `Мои брони` is implemented with local validation and staging visual comparison against Bot `/my` for public booking label, venue-local time and `Держим до`; real two-account Telegram runtime isolation remains unverified. M7c adaptive reminders are code/test-backed and passed one controlled real Telegram smoke for reminder delivery, visible message edit, attendance indicators, venue-controlled status preservation and idempotent repeat handling. Staging is currently safe with `BOOKING_REMINDER_WORKER_ENABLED=false`; future rollout still requires explicit approval. M8a/M8b-Free Venue Mini App structured public profile/card settings is CLOSED / staging smoke passed in provider-free mode: OWNER tested country, city, manual address, save/reload, guest card reflection and route opening; visual polish remains deferred until functional blocks are complete. M9b Venue Working Hours and Date Exceptions Mini App Parity plus M9b.1 date-exception ranges, guest-facing reason/comment, human booking rejection copy, M9b.2 exception save/list UX and M9b.3 date-range editing are CLOSED / staging smoke passed. M4A-M4C messages, M5 staff calls, M6 staff-chat management, M7b, M7c, M8b-Free, M9a and M9b/M9b.1/M9b.2/M9b.3 stay in regression smoke. Paid venue/shift extension Owner/Manager Bot settings parity remains a separate P1 closure track.

Manual M9b Venue Working Hours and Date Exceptions regression smoke (passed once on staging after M9b.3; keep in regression):
1. OWNER opens Venue Mini App `Настройки`.
2. `Часы работы` shows weekly base schedule separately from date exceptions.
3. OWNER saves weekday hours and reloads to confirm persistence.
4. OWNER marks one weekday closed and reloads to confirm it remains closed.
5. OWNER adds a closed single-day exception with a reason, reloads, then edits/deletes it.
6. OWNER adds a multi-day closed period with a reason and a multi-day changed-hours period with a guest comment; each successful save shows success feedback, closes/resets the form, opens/refreshes the compact list, and shows the saved row as a grouped date range.
7. Clicking `Изменить часы на период` again after a save opens a clean form, not stale dates or the previous comment.
8. OWNER edits the closed period and changes start/end dates; the old row disappears, the new range appears, and guest booking follows the edited dates.
9. OWNER edits the changed-hours period and changes start/end dates, open/close times and comment; the old row disappears, the new row appears, and booking follows the edited hours.
10. MANAGER repeats a safe edit.
11. STAFF cannot see schedule controls, and direct schedule API calls are forbidden.
12. Guest public catalog/card shows today's open/closed state and today's hours for the published visible venue.
13. Guest booking in valid hours succeeds.
14. Guest booking on a closed weekday, closed override or closed period is rejected with human copy; if a reason exists, the guest sees it and `Выберите другую дату.`
15. Guest booking outside configured hours is rejected with human copy showing the effective hours for that day when known.
16. A venue with no configured weekly/date schedule shows `График не указан`, and guest booking returns `Заведение пока не настроило график бронирования.`
17. Bot closed-date selection shows the reason when present and offers `📅 К выбору дат` plus `🏠 В каталог`; ordinary slot behavior still matches the same schedule.
18. A user from another venue cannot read or mutate private schedule settings.

Manual M8b-Free structured public profile/card settings regression smoke (passed once on staging; keep in regression):
1. OWNER opens Venue Mini App `Настройки`.
2. `Публичная карточка` shows venue name read-only, visible caret/focus states, disabled save while clean, local country search, local city suggestions, manual address field, `Контакт для гостей` and `Краткое описание`.
3. With all geodata-provider flags disabled, search country by two characters, select a city from local suggestions, enter street/house manually, save and confirm `✓ Сохранено`.
4. Reload confirms country/city/address persist and no provider-derived coordinates are fabricated.
5. Open Guest Mini App catalog/card for the venue and confirm formatted address, public contact and description match saved values.
6. Tap `Построить маршрут` and verify the external map search uses coordinates when already stored and otherwise uses the saved text address; verify `Скопировать адрес`.
7. MANAGER can repeat the edit.
8. STAFF cannot see `Публичная карточка` controls, and direct API/provider access is denied.
9. A manager/owner of another venue cannot read, update or call optional provider routes for this venue's public card settings.
10. Blank optional fields normalize to absent public fields; invalid lengths/coordinates preserve existing values.
11. Restore original public copy after smoke if production-like test data was changed.

Manual M7a booking hold settings regression smoke:
1. OWNER opens Venue Mini App `Настройки`.
2. `Настройки брони` shows current hold duration and example `19:00 -> HH:mm`.
3. OWNER saves a custom value such as `15`.
4. Reload confirms the value is persisted.
5. A newly created booking on 19:00 shows `Держим до 19:15` in Venue Mini App and guest notification copy.
6. Existing bookings keep their stored `arrival_deadline_at` unless rescheduled.
7. Rescheduling after the setting change recalculates the deadline from the new scheduled time plus current hold minutes.
8. MANAGER behavior matches backend policy; STAFF does not see or access booking settings.

Manual M7b Guest Mini App `Мои брони` regression smoke:
1. Guest opens Guest Mini App and goes to `Профиль → Мои брони`.
2. Active/upcoming bookings across at least two venues render as separate cards ordered by nearest scheduled time.
3. Each card shows venue name, public label `Бронь №...`, venue-local date/time, party size, status and comment when present.
4. A confirmed or changed booking shows `Держим стол до HH:mm` from the persisted booking deadline.
5. Compare the same booking in Telegram Bot `/my`; public booking number, venue-local time and deadline copy must match. This visual parity check has passed once on staging.
6. Guest clicks `Перенести`, saves a new date/time/party/comment and sees the card refresh through the existing guest update endpoint.
7. Guest clicks `Отменить бронь`, confirms the destructive dialog and the booking disappears from active list.
8. Empty state shows `Активных броней пока нет.`
9. Switch Telegram user/account and confirm another guest's bookings are not visible. This real two-account runtime isolation check remains explicitly unverified in recorded evidence.
10. Existing Guest/Venue booking queue, conversations, staff calls, staff chat, menu/order and QR restore smoke remains green.

M7c adaptive booking reminders are implemented and accepted for the core real Telegram flow, but remain approval-gated for any future enablement:
- one transactional reminder maximum per `CONFIRMED`/`CHANGED` booking in MVP;
- preferred target 24h before visit if the confirmation/reschedule anchor is at least 6h before that target; fallback 3h before visit if still future and at least 2h after the anchor; otherwise no scheduled reminder;
- venue-local quiet window 10:00-22:00, only moving reminders earlier and never after the intended target or booking time;
- buttons `Да, буду`, `Перенести`, `Отменить`;
- `Да, буду` / `Я приду` writes `last_guest_confirmation_at` atomically per booking schedule version and must not overwrite venue-controlled booking status;
- repeated confirmation returns `Вы уже подтвердили визит.`, produces no database rewrite and no duplicate staff-chat notification;
- after a valid `Да, буду`, Telegram edits the same reminder message, shows `✅ Вы подтвердили, что придёте.`, removes `Да, буду`, and keeps `Перенести` / `Отменить`;
- reschedule clears the previous guest attendance response and stale reminder/Mini App attendance actions must not confirm the new schedule;
- Guest Mini App shows venue status as primary plus compact `Ваш ответ: придёте`; Venue Mini App keeps the staff-oriented `Гость подтвердил визит: DD.MM.YYYY, HH:mm`;
- `Перенести` and `Отменить` reuse existing guest booking lifecycle flows;
- legacy rows are preserved but isolated by `policy_version`; V109 marks legacy `PENDING`/`FAILED` rows `CANCELED`, and the worker only claims M7C rows;
- worker writes reminder status `QUEUED` after Telegram outbox enqueue; outbox delivery status remains the delivery source of truth;
- recorded staging legacy audit after acceptance: `LEGACY/CANCELED = 3`, `LEGACY/SKIPPED = 1`, claimable legacy rows `0`;
- latest enriched staff-chat attendance copy includes public booking number, venue-local booking date/time, guest name/fallback, party size and persisted hold deadline; this copy is code/test-backed but was not manually re-smoked with a new booking.

Read-only M7c pre-enable audit query:

```sql
SELECT
    policy_version,
    status,
    COUNT(*) AS rows_count,
    MIN(scheduled_for) AS earliest_scheduled_for,
    MAX(scheduled_for) AS latest_scheduled_for
FROM booking_reminders
GROUP BY policy_version, status
ORDER BY policy_version, status;
```

Legacy claimability audit:

```sql
SELECT COUNT(*) AS legacy_claimable_rows
FROM booking_reminders br
JOIN bookings b ON b.id = br.booking_id
WHERE br.policy_version <> 'M7C'
  AND br.status = 'PENDING'
  AND br.scheduled_for <= NOW()
  AND b.status IN ('CONFIRMED', 'CHANGED');
```

Manual M7c staging regression or rollout smoke, do not execute without approval:
1. Deploy with `BOOKING_REMINDER_WORKER_ENABLED=false`.
2. Verify `/health`, `/db/health`, `/miniapp/` and the disabled startup log.
3. Run the read-only audit queries above and confirm no legacy row is claimable by the M7c worker.
4. Create a disposable test booking with enough lead time for a valid M7c target.
5. Verify its calculated venue-local reminder schedule in `booking_reminders` has `policy_version='M7C'`.
6. Reconcile or cancel only approved old test rows, preserving history.
7. Explicitly enable the worker only for the smoke.
8. Verify exactly one Telegram reminder appears with the final copy and buttons.
9. Press `Да, буду` and verify the reminder message changes visibly, `Да, буду` disappears, `Перенести` / `Отменить` remain, and booking status remains `CONFIRMED` or `CHANGED`.
10. Press the same callback again and verify `Вы уже подтвердили визит.` with no duplicate staff-chat notification.
11. Verify Bot `/my` shows venue status plus secondary `Ваш ответ: придёте`.
12. Verify Guest Mini App shows compact `Ваш ответ: придёте` and no duplicate primary confirmation paragraph.
13. Verify Venue Mini App shows `Гость подтвердил визит: DD.MM.YYYY, HH:mm`.
14. Verify reschedule clears the guest response, requires a new response for the new time and makes old reminder actions stale.
15. Verify reminder `Перенести` and `Отменить` reuse existing guest flows and cancel/avoid duplicate unsent reminders.
16. Disable the worker immediately if any acceptance check fails, and return staging to `BOOKING_REMINDER_WORKER_ENABLED=false` after the smoke.

Manual Staff Profiles Phase 1 regression smoke:

1. OWNER opens Venue Mini App `Персонал`.
2. Section `Карточки сотрудников` is visible and explains that guests see only published cards.
3. Create form is collapsed by default; existing employee cards are compact.
4. OWNER clicks `Добавить карточку сотрудника` and sees profile fields without raw User ID or raw Photo ref inputs.
5. Selecting `Другое` shows required `Название роли` with guest-facing helper copy; saving without it is blocked.
6. OWNER creates a display-only profile and a linked profile, then publishes and hides visibility.
7. OWNER marks a published profile `Сегодня на смене`.
8. MANAGER, where current policy allows, can mark today-shift state but cannot publish/hide profiles or approve future tip methods.
9. STAFF cannot publish/hide profiles and can edit only own linked draft fields where policy allows.
10. Guest venue detail shows `Сегодня работают` below main venue information/actions.
11. Guest sees public display name, role/custom role, bio/tags and safe placeholder/photo only.
12. Hidden profile is not visible to guest.
13. Guest UI/API exposes no `linked_user_id`, Telegram id, invite state, private contact fields, raw User ID or raw Photo ref.
14. Photo upload/media picker, staff tips/payments, full schedule/calendar and shift sign-up/chat swaps are not expected in current smoke.

Manual Guest Communication / Support Tickets regression smoke after deployment:

1. Open Guest Mini App outside table context and confirm global nav shows `Чаты` and `Помощь`.
2. Open catalog and confirm each eligible venue card has `Задать вопрос`.
3. Tap catalog `Задать вопрос`; confirm `Чат с <venueName>` opens/creates `VENUE_CHAT`, not a support ticket.
4. Open venue detail and confirm local `💬 Задать вопрос` opens/reuses the same `VENUE_CHAT`.
5. Open guest `Чаты` and confirm copy says chats are for questions, bookings and other venue conversations, while problems/complaints are in `Помощь`.
6. Venue Owner/Manager opens Venue Mini App `Сообщения`, sees the `VENUE_CHAT`, replies, and the guest sees the reply in `Чаты`.
7. Confirm `VENUE_CHAT` create/reply does not create a staff-chat notification.
8. Booking card `Открыть переписку` still opens `BOOKING_CHAT` / `Сообщения`, not `Помощь` / support.
9. Guest opens `Помощь` and creates a technical/Mini App/QR support ticket without choosing venue; Platform Owner sees it in Platform `Обращения`.
10. Guest tries order/service support outside table without venue; UI or API requires venue before submit.
11. Guest tries booking support outside table without booking/venue; UI or API requires booking or venue context.
12. Guest table context does not show primary `Связаться с заведением`; table context still shows `Вызвать персонал`.
13. Create a table-context support ticket and confirm venue/table/session context is attached when available.
14. Venue Owner/Manager opens `Помощь` / `Обращения`, sees own-venue support tickets, replies and can `Передать платформе`.
15. Platform Owner opens Platform `Обращения`, verifies `Переданные платформе` support tickets are visible, replies and closes.
16. Confirm Platform does not see ordinary `VENUE_CHAT`.
17. Confirm Staff does not see `Помощь` / `Обращения`, cannot open support tickets and cannot open ordinary venue chats through direct API.
18. Confirm `SUPPORT_TICKET` create/reply does not create a staff-chat notification.
19. Confirm `STAFF_CALL` still works separately and keeps existing operational staff queue/staff-chat behavior.
20. Confirm guest support create, venue chat create and guest support messages are rate-limited enough to prevent repeated spam without blocking normal reopen/open-existing-chat behavior.

Manual M4B/M4C inbox regression smoke after deployment:

1. Create or seed multiple booking/general-like threads for one guest across at least two venues.
2. Open Guest Mini App `Чаты` and confirm it shows a list of `BOOKING_CHAT` / `VENUE_CHAT` thread cards, not one merged chat.
3. Confirm every guest thread card shows venue name, context label (`Бронь №...`, `Заказ №...`, `Стол №...`, `Общий вопрос` or `Проблема`), status, last message preview, last message time and unread badge/count when applicable.
4. Confirm `Активные` hides resolved/closed threads and `Завершённые` shows old resolved/closed threads.
5. Open one thread and confirm M4A booking conversation behavior still works: message history, Guest Bot reply persistence, Guest Mini App reply and Venue Mini App reply.
6. In Guest Mini App, click `Завершить переписку`; confirm the thread moves to `Завершённые`, shows `Переписка завершена` and hides the composer until `Возобновить переписку`.
7. Reopen the guest thread and confirm it returns to `Активные`.
8. Open Venue Mini App `Сообщения` for venue A and confirm only venue A threads are visible.
9. Switch/select venue B and confirm venue A threads are not visible.
10. Confirm venue thread cards show guest display, context label, status, last message preview/time and unread badge.
11. In Venue Mini App, resolve and reopen the same thread; confirm this does not change booking confirm/change/cancel/arrived/no-show state.
12. Confirm booking cards still link to the booking thread through `Открыть переписку`.
13. Open as STAFF and confirm view/reply/status permissions match the explicit RBAC decision; do not silently broaden STAFF access.
14. Confirm Platform Support Center is exposed only for backend-backed `SUPPORT_TICKET` and does not show ordinary `VENUE_CHAT`.
15. Keep M4A regression: quick compose closes after send, manager stays on `Брони`, staff chat remains a notification mirror, and booking confirm/change/cancel/arrived/no-show actions are unchanged.

Manual guest table context exit regression smoke after deployment:

1. Guest opens real Telegram Mini App from a table QR and sees the correct venue/table context.
2. Confirm table context does not prominently show pre-visit route/copy address/booking actions.
3. Open the same venue without table context and confirm address, route/copy address and booking remain visible on the pre-visit card.
4. With no active order/bill and no NEW/ACK staff call, tap `🚪 Завершить визит`; confirm the request is `POST /api/guest/table/session/end` with `Content-Type: application/json` and body `{ tableToken, tableSessionId }`.
5. Confirm the guest returns to no-table/catalog mode and reopening the Mini App without QR does not restore the old table.
6. Scan the QR again and confirm the guest re-enters table context.
7. Create an active order/bill and confirm `Завершить визит` is blocked with clear bill-close copy.
8. Create a NEW/ACK staff call and confirm exit is blocked until the call is DONE.
9. Mark the call DONE and confirm it no longer blocks exit.
10. With two guests at the same physical table, confirm one guest exit does not kick out the other guest or close the shared physical `table_sessions` row.
11. Confirm existing menu/cart/order/staff-call/fallback quick-order flows still work after exit and QR re-entry.

Manual paid extension smoke after full parity:

1. Configure extension for a venue in Venue Mini App: enabled, fixed one-hour duration and price.
2. Configure the same extension in Owner/Manager Bot once the remaining bot settings parity slice is implemented; confirm copy `Показывать гостям возможность продления`.
3. Guest Mini App active table context with no active order/bill does not show `Продление работы заведения`.
4. After creating an active order/bill where extension is available, Guest Mini App shows service entry `Продление работы заведения` in the ordering section list, then `Продлить на 1 час` inside that service screen.
5. Guest Bot `🍽️ Меню` and `Мой заказ → Дозаказать` section lists show `Продление работы заведения` only when the current active order/table state makes it actionable and create the same fixed-price request.
6. Guest creates one extension request; repeated taps/callbacks do not duplicate pending requests.
7. Venue Mini App order queue shows a pending extension badge/count on the affected order/table.
8. Venue Mini App order detail shows `Запрос на продление работы заведения`, `На 1 час — 3 000 ₽`, `✅ Подтвердить продление`, `❌ Отказать`.
9. Staff chat live order/bill message updates in place with the pending extension block and inline approve/reject buttons; no separate noisy lifecycle message is sent.
10. STAFF/MANAGER approves from Venue Mini App or staff chat; bill gains service charge `Продление работы на 1 час`, Guest/Venue/Telegram bill totals match, and table session orderable-until time extends.
11. Create and approve a second extension; charge and session extension are applied once per request.
12. Reject a request and confirm guest sees rejection copy while bill/session do not mutate.
13. As STAFF, confirm price/duration/settings are not editable in Mini App or bot.
14. As MANAGER/OWNER, confirm settings are editable in Mini App; repeat in bot after the remaining bot settings parity slice lands.
15. Close bill/session and confirm extension request/approve endpoints are denied and extension UI disappears or disables safely.

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
