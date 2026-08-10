# Product + Telegram AI Bots Roadmap

Дата обновления: 2026-08-10.

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

Текущий фокус перед пилотом: product P0/P1 закрыт по уже принятым M1-M9b.3 блокам, staging smoke, CI release validation, deploy/runbook hardening and minimal Guest Mini App browser smoke зелёные. M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed: standard deploy remains supported, opt-in ControlMaster deploy is validated as a release-reliability workaround, and the exact SSH/network root cause remains unconfirmed. M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 range/rejection-copy improvements, M9b.2 exception save/list UX and M9b.3 date-range editing are CLOSED / staging smoke passed. Platform Owner Invite / ADMIN Semantics Hardening, Platform Venue OWNER Revocation, H2/PostgreSQL active-order + personal-tab uniqueness fidelity, Mini App mutation / operational verification closure pack, Staff Call Lifecycle ACK/DONE audit hardening, Staff-call guest-visible CANCELLED finishing patch, Guest Table Context UX Cleanup / Feature-gated Extension Module, Guest Table Session Exit / Expiry UX, Guest Bill / Display-Number / Full-Bill Parity, Guest Bill Request / Payment Method UX, Staff Chat Noise Reduction / Table Activity Card, hookah preparation placeholder polish, Platform Billing Cockpit / Owner Payment UX, Platform Billing Renewal / Advance Invoice / Courtesy Days, Staff/Manager invite deep-link sharing polish, Guest Communication UX / Support Tickets MVP, Booking Arrival Guard / Staff-Chat Booking Buttons, Guest History Foundation, Post-Visit Feedback MVP, Guest Favorites Phase 1 and Catalog Search and Filter Phase 1 are CLOSED. Guest Favorites Phase 1 is **DONE / MVP / STAGING-SMOKE-PASSED** for venue favorites only, including Telegram Profile/Catalog parity and source-aware Back navigation. Catalog Search and Filter Phase 1 is **CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**. Next bounded milestone should be selected from the remaining launch backlog; do not reopen these closed slices without new smoke or code evidence. Scope не расширяем в сторону Telegram-native AI surfaces до готовности Mini App и public-safe tools.

Актуальный post-fix snapshot:

- Guest catalog до QR не показывает заказное `🍽 Меню`; структурированное меню доступно только после QR/table context.
- Guest table context cleanup is CLOSED / staging smoke passed: real Telegram Mini App QR opens the correct venue/table context, while route/copy address/booking actions are hidden in table context and remain available on the pre-visit venue card.
- Guest table session exit is CLOSED / staging smoke passed: `🚪 Завершить визит` exits only the current Telegram user context through `guest_table_session_exits`; shared `table_sessions` rows remain physical-table/session scoped and are still expired by TTL cleanup.
- `ℹ️ Информация` и `📖 Фото-меню` разделены от `🍽 Заказного меню` в Telegram bot и Mini App.
- Guest Mini App показывает visible+filled info sections; media info sections открываются через backend proxy без раскрытия Telegram token/file URL.
- Venue/public-card image/PDF management is **PARTIAL / BOT-FIRST**: Bot OWNER/MANAGER can add
  and delete attachments and hide/show the whole info section; Venue Mini App can view Published
  media but has no upload/manage flow. Structured menu-item photos/descriptions/thumbnails and
  option/flavor media are separately **MISSING / FUTURE** in current runtime.
- Venue Owner/Manager/Staff входят в Venue Mini App через inline `web_app` button, чтобы Telegram WebView передавал `initData`.
- STAFF может закрывать счёт/заказ и управлять операционным stop-list по позициям/вкусам, но не может управлять скидками, исключениями, структурой/контентом меню, столами, персоналом или настройками.
- STAFF booking policy разделён: STAFF видит брони и отмечает `Гость пришёл` / `Не пришёл` only for confirmed bookings, а confirm/cancel/change/message/settings остаются MANAGER/OWNER.
- Venue Mini App booking card opens a persisted booking conversation thread: venue messages, Guest Bot replies and Guest Mini App replies share one source of truth; staff chat remains a notification mirror. M4A staging smoke passed after UX polish. M4B/M4C unified `Сообщения` inbox and resolve/reopen lifecycle are CLOSED after staging smoke: thread cards show context/status/last message/unread, active/resolved filters work, and explicit resolve/reopen does not mutate booking lifecycle.
- Guest Communication UX / Support Tickets MVP is CLOSED after smoke: canonical model is `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET`, `STAFF_CALL` in `docs/COMMUNICATION_MODEL.md`; Guest nav is `Чаты` / `Помощь`; catalog and venue detail `Задать вопрос` opens/reuses `VENUE_CHAT`; booking `Открыть переписку` remains `BOOKING_CHAT`; Support tickets are separated through `SUPPORT_TICKET`; Platform sees support tickets but not ordinary venue chats; Staff sees neither support tickets nor ordinary venue chats; table context keeps `Вызвать персонал` as the live operational flow; support/venue chat creation and replies do not post to staff-chat and guest create/reply routes are rate-limited.
- Platform cockpit docs are current in `docs/PLATFORM_COCKPIT.md`: Platform Mode is the cockpit for venues, onboarding, lifecycle, owner/access, billing/subscriptions/invoices, Support Center and analytics/audit. Manual billing and support-ticket MVP are closed; onboarding/placements/analytics, real acquiring/Stars, recurring payments and advanced support remain future/partial.
- Growth/retention docs are current in `docs/GROWTH_RETENTION.md`: Guest History Foundation, Post-Visit Feedback MVP, venue-only Guest Favorites Phase 1, Simple Venue Promotions Phase 1, Promotion Creation Audit, Promotion Effective State Clarity, Promotion Lifecycle Status Audit and Venue Promotions Current/Archived Tabs UX are DONE / MVP / staging-smoke-passed. Repeat as Template Phase 1 is `MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE`; its environment-dependent production-readiness gate remains open in [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001) without blocking independent bounded development. Executable Promotions Phase 2 / Happy Hours Percent is `DONE / STAGING-SMOKE-PASSED`; Gift parity is `GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`, with independent review, CI and staging still required. Favorites covers catalog/detail mutations, Account list, current-user isolation, unavailable filtering/restoration, shared Bot/Mini App storage, Telegram Profile/Catalog entrypoints and source-aware Back. The manual `5/5` public review CTA and low-rating exact `VENUE_CHAT` follow-up are closed; persistent templates, favorite menu items/options, recommendations/frequent items, notification opt-in, favorites-based promotions, loyalty and broader analytics remain future/partial.
- Staff profiles, Today Shift, optional Staff Schedule and future staff tips are canonical in
  `docs/STAFF_PROFILES_SHIFTS_TIPS.md`:
  `STAFF OPERATIONS SLICE A / MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP /
  STAGING-SMOKE-PASSED`; `STAFF IDENTITY LINKING UX + DUPLICATE PREVENTION / DONE / MVP /
  STAGING-SMOKE-PASSED`; `STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`; and
  `STAFF SCHEDULE / CANCELED SHIFT RESTORE + BULK ASSIGNMENT / DONE / MVP /
  STAGING-SMOKE-PASSED`, with Phase 1 schedule/identity schema verdicts `NO_MIGRATION_EXPECTED`.
  Accepted members
  use the existing `users` identity/upsert source and safe link-state projection; one active linked
  profile is serialized through the target `venue_members` row, while existing duplicates remain
  explicit; Manager duplicate state is read-only and repair is Owner-only. Green Actions, runtime
  deploy, PostgreSQL V120/H2 V121 invite-revoke rollout and manual smoke are complete. The separate
  Slice B status is `STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR
  SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`; green Actions, staging deploy,
  PostgreSQL V121 application and bounded manual smoke are complete. Phase 2 may add
  external staff tip link + `staff_tip_intent`;
  provider/direct payout, Telegram Stars and crypto are not MVP.
- Order/session/tab core docs are current in `docs/ORDER_SESSION_TAB_CORE.md`: `TABLE_SESSION`, `ACTIVE_TABLE_ORDER`, `ORDER_BATCH`, `TAB`, bill/request/close flow, privacy boundaries and visit-history foundation are `SPEC UPDATED`. Current runtime docs say table-session/tab scoping, Guest History Foundation and Post-Visit Feedback MVP are staging-smoke-passed, while Repeat Phase 1 is locally validated with deferred environment-dependent manual smoke; force-close policy/audit, loyalty/preorder and broader analytics remain partial/future.
- Analytics/events docs are current in `docs/ANALYTICS_EVENTS.md`: analytics events, audit/event boundaries, KPI formulas, role dashboards and payload privacy rules are `SPEC UPDATED`; implementation and Platform dashboards remain partial/future unless specific events are verified.
- Security/RBAC docs are current in `docs/SECURITY_RBAC_MATRIX.md`: roles, scopes, permissions, surface parity, dangerous actions, auth/trust boundaries and security smoke checklist are `UPDATED`; permission parity and dangerous-action audit coverage remain partial unless specific route tests/smoke evidence exists. `ADMIN` is a legacy compatibility alias to `MANAGER`, not a product role.
- Menu/options/stop-list docs are current in `docs/MENU_OPTIONS_STOPLIST.md`: structured menu terms, option/modifier snapshots, media/PDF boundaries, featured/top-list, stop-list, shift check, availability validation and menu permissions are `SPEC UPDATED`. Selected-option parity and Menu Item Hard Delete Audit are smoke-closed; the bounded OWNER/MANAGER shift-check slice is `MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`; broader menu constructor/media/top-list and remaining audit coverage remain partial/future.
- Venue info-section media storage/upload is canonical in `docs/MEDIA_STORAGE_UPLOAD.md`: current
  Telegram-`file_id` architecture, hybrid asset model, security/lifecycle contract and bounded
  Venue Mini App slice are specified; runtime remains missing and verdict is
  `STOP_FOR_MEDIA_STORAGE_DECISION`.
- Venue operations docs are current in `docs/VENUE_OPERATIONS.md`: Venue Mode dashboard, orders, batches, tabs/bill, staff calls, bookings, menu/stop-list, tables/QR, staff/invites, staff-chat, settings, stats and operational smoke are `SPEC UPDATED`. Venue Mode is source of truth; staff-chat is radar/shortcut. Core slices are smoke-closed by milestone, while a complete cockpit remains partial/future in several areas.
- Booking lifecycle docs are current in `docs/BOOKING_LIFECYCLE.md`: guest booking flow, Venue booking queue, statuses/state machine, hold minutes, `arrival_deadline`, confirmed-only arrival actions, reminders, `BOOKING_CHAT`, booking support routing, analytics, RBAC and smoke are `SPEC UPDATED`. Current booking queue, hold settings, guest list parity, booking chat, arrival guard, staff-chat booking lifecycle buttons, booking `SEATED` -> Guest History and booking-only `SEATED` feedback eligibility are smoke-closed by bounded slices; reminder rollout, full automation and preorder remain partial/future.
- Telegram fallback/staff-chat docs are current in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`: Telegram bot entrypoints, QR `/start`, table-context bot menu, fallback order, staff-call, staff-chat callbacks and parity are `SPEC UPDATED`. Staff-chat is radar/shortcut, never source of truth. Platform guest QR status is **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED** with `NO_MIGRATION`; broader Platform menu parity, personal notifications and delivery history remain partial/future.
- Testing/QA smoke strategy docs are current in `docs/TESTING_QA_SMOKE_STRATEGY.md`: local validation, GitHub Actions expectations, change-type test matrix, manual smoke suites, staging deploy policy, failure reporting and Codex handoff rules are `UPDATED`. Environment-blocked mandatory checks are tracked once in `docs/DEFERRED_MANUAL_SMOKE_BACKLOG.md`. CI coverage is release-critical and split; manual area smoke remains required for runtime and Telegram/staff-chat changes.
- Deployment/runbook docs are current in `docs/DEPLOYMENT_RUNBOOK.md`: release model, staging deploy command, environment/config inventory, migration runbook, rollback policy, troubleshooting, incident response and Codex/ChatGPT handoff are `UPDATED`. Exact production deploy, previous-image rollback and backup/restore commands remain partial/needs verification.
- M5 staff calls lifecycle is CLOSED after staging smoke: Guest Mini App uses a transient staff-call modal and compact status card, Venue Mini App has a real active-only `Вызовы` queue with accept/close, backend/staff-chat callbacks share the same lifecycle, ACK/DONE audit hardening is CLOSED / staging smoke passed across Venue Mini App and Telegram staff-chat surfaces, and guest-visible terminal `CANCELLED` is shown to the current guest/tableSession as `Вызов отменён`. Staff-chat order activity cards now hide DONE/CANCELLED generic calls from active `Оперативно`, and closing an order/bill resolves linked active BILL requests plus closed-visit staff-call leftovers. Keep notification delivery, active-only queue semantics and actor audit evidence in regression.
- Platform Owner определяется через `PLATFORM_OWNER_TELEGRAM_ID`; legacy aliases остаются совместимостью.
- Platform Owner config parity between Bot and API is implemented; `PLATFORM_OWNER_TELEGRAM_ID` is the canonical access source and legacy aliases remain compatibility only.
- Platform Mini App no longer exposes `ADMIN` as a selectable owner/admin assignment role; runtime `ADMIN` remains only a legacy DB alias mapped to `MANAGER`.
- Platform Owner invite/add OWNER flow is CLOSED / staging smoke passed: invite create returns usable Telegram deep link or fallback copy text, Telegram `/start staff_invite_<code>` acceptance grants OWNER for the intended venue, non-platform users are denied, and create/accept audit evidence exists.
- Platform Venue OWNER Revocation is CLOSED / staging smoke passed: Platform Owner can list active OWNER memberships, revoke one OWNER only when another active OWNER remains, last-owner revoke is blocked server-side, and `VENUE_OWNER_REVOKE` audit evidence exists.
- Runtime venue ownership access is based on active `venue_members` rows with role `OWNER`; revoke does not relink `venues.owner_account_id` or `venue_owner_accounts.primary_owner_user_id`.
- H2/PostgreSQL active-order + personal-tab uniqueness fidelity is CLOSED / validation passed: H2 V112 now mirrors PostgreSQL predicates for one `ACTIVE` order per `table_session_id` and one active `PERSONAL` tab per `table_session_id + owner_user_id`. PostgreSQL already had the intended constraints, no PostgreSQL production migration, runtime API/routes/Mini App/Bot change or staging deploy was required, commit `a4a2d71` is on `origin/main`, split lower-memory Gradle validation passed, and Docker/Testcontainers-backed PostgreSQL checks were skipped where Docker was unavailable.
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
- Booking Arrival Guard / Staff-Chat Booking Buttons are CLOSED / staging smoke passed: `Гость пришёл` / `Не пришёл` is shown and accepted only for confirmed bookings; pending/changed/terminal statuses have no arrival buttons; staff-chat booking notifications are state-aware; stale arrival callbacks answer `Бронь уже изменилась. Откройте кабинет.`; no-permission callbacks answer `Нет прав.`; guest booking replies stay in `BOOKING_CHAT` and do not post to staff-chat.
- M4B Unified Messages Inbox UX is CLOSED after staging smoke: Guest and Venue Mini App thread lists now use backend context labels, status, last message preview/time, unread counts via `support_thread_reads`, and `Активные` / `Завершённые` filters.
- M4C support thread resolve/reopen lifecycle is CLOSED after staging smoke: Guest and Venue Mini App can `Завершить переписку`, resolved threads move to `Завершённые`, `Возобновить переписку` moves them back to `Активные`, and sending a real user message to a resolved thread reopens it through shared backend message semantics.
- M5 staff calls lifecycle and compact Guest Mini App UX are CLOSED after staging smoke. Keep linked Telegram group notification and inline ACK/DONE behavior in regression smoke per pilot venue.
- Staff Call Lifecycle ACK/DONE audit hardening is CLOSED / staging smoke passed. Real Telegram Mini App smoke confirmed Venue Mini App and Telegram staff-chat ACK/DONE transitions write `STAFF_CALL_ACK` / `STAFF_CALL_DONE` audit rows with top-level actor evidence and safe `source` values; audit remains best-effort. Guest-visible `CANCELLED` terminal status is also CLOSED / staging smoke passed for the current guest/tableSession. Row-level ACK/DONE actor/timestamp columns, manual cancel UI and staff-call UX polish remain separate follow-ups.
- Guest Table Context UX Cleanup / Feature-gated Extension Module is CLOSED / staging smoke passed. Real Telegram Mini App QR smoke confirmed the active table header, hidden route/copy address/booking actions in table context, preserved pre-visit address/route/copy/booking actions, hidden `Продление работы заведения` when no active order/bill or extension is unavailable, visible extension only when active order state makes it actionable, and disappearance again after bill/order close.
- Guest Table Session Exit / Expiry UX is CLOSED / staging smoke passed. The first staging attempt found `415 Unsupported Media Type` on `POST /api/guest/table/session/end`; the Mini App fix added `Content-Type: application/json`, kept Authorization, and e2e now asserts endpoint path, method, content type and `{ tableToken, tableSessionId }` body. After deploy, `Завершить визит` moves the current user to no-table mode, reopening without QR no longer restores table 101, re-scanning QR re-enters, empty personal tab/no active order allows exit, active current-user order/bill or NEW/ACK staff call blocks, DONE staff call does not block, another guest at the same physical table is not affected, and existing menu/cart/order/staff-call/fallback flows still work.
- M6 staff chat diagnostics/unlink polish is CLOSED after staging smoke: Venue Mini App shows linked/unlinked state, masked chat id, backend-built `/link@BotUsername <код>` command, outbox-backed test-message queue/delivery path, OWNER-only unlink, relink flow, and polished active-code UI with copy-first action plus regenerate confirmation.
- Guest Bill / Display-Number / Full-Bill Parity is CLOSED / staging smoke passed: Guest Mini App shows `Заказ №N`, `Личный счёт` / `Общий счёт`, clear no-discount and discounted bill rows, meaningful order/batch status copy and closed-bill copy; Venue Mini App / Bot / Guest totals match.
- Guest Bill Request / Payment Method UX is CLOSED / staging smoke passed: Guest Mini App shows `Попросить счёт`, renders payment choices immediately under the action, sends structured payment method, dedupes active requests and updates staff context without treating `Счёт` as a generic staff-call path.
- Staff Chat Noise Reduction / Table Activity Card is CLOSED / staging smoke passed: order, reorder, bill request and safely order-linked staff-call activity update the same live order card; unsafe/no-order/ambiguous calls stay standalone; manual `Обновить` preserves activity; markers `🆕`, `🚨`, `🛎️`, `🧾`, `💳`, `💵`, `❓` make edits scannable; DONE/CANCELLED generic calls are not active rows; closing bill/order resolves linked active BILL requests and closed-visit call leftovers.
- Hookah preparation placeholder polish is CLOSED / staging smoke passed: nested hookah flavor/options note fields use `Например: покрепче, полегче, больше мяты, без ментола`, while drink/food options keep `Например: без сахара, без льда, потеплее`.
- M8a/M8b-Free Venue Mini App structured public profile/card settings is CLOSED / staging smoke passed: OWNER/MANAGER edit provider-free public country/city/address/contact/description, STAFF is hidden/forbidden, guest read models reflect saved fields, route links use saved coordinates when present and otherwise encoded text address search, and Yandex adapters remain optional disabled commercial-only integrations.
- M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed. `scripts/deploy-staging-controlmaster.sh` is committed as an opt-in helper, `./scripts/deploy-staging.sh hookah-staging` remains unchanged, the helper's bounded retry opened the initial master after an SSH banner timeout, rsync/build/image upload/backend recreate succeeded through one persistent connection, PostgreSQL stayed healthy, local `/health`, local `/db/health`, local Mini App static, public `/health`, public `/db/health` and public `/miniapp/` passed, and a separate retry-based public check also passed. The fresh-connection failure cause remains observed but unproven.
- M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 Schedule Exception Ranges and Guest Copy, M9b.2 Schedule Exception Save UX and M9b.3 Schedule Exception Range Editing are CLOSED / staging smoke passed. Venue Mini App manages weekly hours plus inclusive closed/special-hours date-exception ranges for OWNER/MANAGER; the same from/to date means one day; nullable `guest_note` carries the optional guest-facing reason/comment; changed-hours saves visibly return to the compact exception list; existing closed and changed-hours exceptions can be edited to a new inclusive date range; range storage remains per-date overrides and overlapping target dates are overwritten by the latest saved range values; STAFF is hidden/forbidden; guest catalog/card read models expose today's safe schedule/open state; and direct Guest Mini App booking create/update returns human schedule rejection codes/copy. Product decision: the existing Bot `Часы работы` model intentionally represents both public operating hours and booking availability for launch. Missing schedule setup shows `График не указан` / `VENUE_SCHEDULE_NOT_CONFIGURED`, not a deliberate closed day.
- Platform Owner Invite / ADMIN Semantics Hardening is CLOSED / staging smoke passed: Bot/API platform-owner config parity is implemented, Platform Mini App does not offer `ADMIN`, owner invite create returns usable Telegram `deepLink`/`copyText`, Telegram accept grants OWNER for the intended venue, non-platform users are denied, existing manager/staff invite flows remain green, and `VENUE_OWNER_INVITE_CREATE` / `VENUE_OWNER_INVITE_ACCEPT` audit evidence exists.
- Platform Venue OWNER Revocation is CLOSED / staging smoke passed: Platform Owner sees active OWNER memberships, revokes an old OWNER when another active OWNER remains, revoked OWNER loses Venue Mini App and Telegram Bot venue-owner access through membership role resolution, remaining OWNER stays active, last-owner revoke and non-platform revoke are blocked, existing invite/removal flows remain green, and `VENUE_OWNER_REVOKE` audit evidence exists. `owner_account_id` / primary-owner linkage is not automatically relinked.
- Platform Billing Cockpit / Owner Payment UX is CLOSED / staging smoke passed: Platform Owner sees billing cockpit state, Venue Owner sees subscription/payment state, GET billing/subscription overviews are read-only, invoice/checkout ensure uses explicit POST actions, manual/fake invoices do not expose provider-internal fake URLs, manual mark-paid writes audit, and Russian paid-through/next-payment copy uses human dates.
- Platform Billing Renewal / Advance Invoice / Courtesy Days is CLOSED / staging smoke passed: next invoice periods are calculated from effective paid-through + 1 day, next invoice ensure is idempotent, Platform Owner can create the next invoice in advance, `billing_adjustments` stores `COURTESY_DAYS`, Platform Owner can add courtesy/free days only with required reason, `BILLING_COURTESY_DAYS_ADDED` audit is written, and adjusted paid-through/next-payment dates are shown to Venue Owner without exposing mark-paid or courtesy controls.
- Staff/Manager invite deep-link sharing polish is CLOSED / staging smoke passed: Telegram invite messages use valid `t.me` deep links and copy-text buttons where supported, Venue Mini App shows one selectable invite link field with copy-link and Telegram-share actions, the fallback command is secondary, no self-open action is shown in the result card, and Manager/Staff invite acceptance smoke passed.

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
- `docs/audit/PRODUCT_IDEAS_REVIEW.md`;
- `docs/GROWTH_RETENTION.md`;
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
- full bill/stats and history-related foundations;
- role-aware Telegram menus and callbacks.

Launch focus:

- smoke for role visibility and permission boundaries;
- webhook/outbox/staff notification monitoring;
- fallback/error paths;
- pilot venue runbook.
- growth/retention remains `SPEC UPDATED / PARTIAL-FUTURE` overall in `docs/GROWTH_RETENTION.md`; History, Post-Visit Feedback, venue-only Guest Favorites Phase 1, Simple Venue Promotions Phase 1 and Happy Hours Percent are closed slices, Repeat remains local-validation-passed with deferred manual smoke, and Gift Bot/Mini App parity is `GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`. This is not staging/release evidence, nor evidence that favorite menu items, persistent repeat templates or loyalty are done.

### Guest Flow

Status: `DONE / P1 POLISH`.

Done:

- guest catalog and venue card baseline;
- catalog search/filter Phase 1: backend-owned optional `q`/`city`, a 300 ms Mini App debounce,
  abort/latest-response protection, complete city options from the initial unfiltered guarded
  catalog and preserved current-user favorites/today schedule; **DONE / MVP /
  STAGING-SMOKE-PASSED**;
- pre-QR venue card without structured order menu;
- `ℹ️ Информация` and `📖 Фото-меню` info sections;
- Mini App media proxy for info-section images/PDFs;
- table context;
- menu/cart/checkout;
- active order;
- staff call;
- bookings MVP;
- guest communication split: `Чаты` for `BOOKING_CHAT` / `VENUE_CHAT`, `Помощь` for `SUPPORT_TICKET`, and table-context `Вызвать персонал` for `STAFF_CALL`;
- account hub baseline with history/favorites and safe bot-only fallbacks where needed;
- venue-only Guest Favorites Phase 1: catalog/detail add/remove, Account favorites list, current-user isolation, unavailable-venue filtering/restoration, shared Bot/Mini App data source, Telegram Profile/Catalog entrypoints and source-aware Back; `DONE / MVP / STAGING-SMOKE-PASSED`;
- Post-Visit Feedback MVP from completed History, including manual `5/5` public review CTA and low-rating exact `VENUE_CHAT` follow-up;
- support tickets MVP with verified context routing and Platform/Venue visibility;
- active order scoping through `tableSessionId` and `tabId` in Mini App client/backend path.

Remaining P1/P2:

- remaining guest growth/retention from `docs/GROWTH_RETENTION.md`: Repeat as Template Phase 1 is locally validated with deferred manual smoke in `REPEAT-MANUAL-001`; Simple Venue Promotions Phase 1 and Happy Hours Percent are `DONE / STAGING-SMOKE-PASSED`; Gift parity is `GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT` and awaits independent review, CI and staging; favorite menu items/options, recommendations/frequent items, persistent templates, notification opt-in, favorites-based promotions and loyalty remain future; venue favorites, visit/order history and Post-Visit Feedback are DONE / staging-smoke-passed and stay in regression;
- richer profile/promotions/loyalty polish in Mini App only after the underlying product/accounting rules are implemented and smoked;
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
- OWNER/MANAGER `Проверка меню перед сменой`: saved category/item/option counts, local draft,
  search/filters/selection, mass item/category/option changes, one optimistic atomic batch,
  recoverable stale refresh, no-op completion and safe `MENU_SHIFT_CHECK_COMPLETED` audit;
- owner Telegram copy split between `🍽 Заказное меню` and `📖 Фото-меню`;
- Venue Mini App entry for OWNER/MANAGER/STAFF through inline `web_app`;
- venue booking queue/actions baseline; M3 Mini App bookings queue/lifecycle MVP is closed in the current release line with venue-local display fields and manager/staff coverage;
- M4A booking conversation threads CLOSED after staging smoke: booking messages, Guest Bot replies and Mini App replies persist in shared support threads;
- Venue chat/support split CLOSED after smoke: Owner/Manager `Сообщения` handles `BOOKING_CHAT` / `VENUE_CHAT`; `Помощь` / `Обращения` handles own-venue `SUPPORT_TICKET`; `Передать платформе` applies to support tickets only; Staff remains operational and cannot handle support/venue chats.
- booking RBAC split implemented: STAFF view + arrival/no-show, MANAGER/OWNER management actions;
- staff-chat live message clarity for main order vs doporders passed staging smoke: one live message, venue-local time without `UTC`, separate blocks and clear batch statuses/actions;
- guest table session persistence/restore passed staging smoke: reopening Mini App without repeat QR restores the active table context, internal guest navigation keeps that context, and Telegram BackButton exits from root instead of looping;
- broad venue settings are not exposed as a dead-end placeholder; Venue Mini App `Настройки` now contains backend-backed booking hold, paid shift-extension settings, M8b public card basics with structured location (`countryCode`, `city`, `address`, `formattedAddress`, optional coordinates, `guestContact`, `cardDescription`) and M9b weekly hours/date exceptions;
- Venue Mini App read-only `Статистика` CLOSED after staging smoke for OWNER/MANAGER on existing `VenueStatsRepository` semantics;
- Venue Mini App Guest Preview Phase 2.1 is **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**. One `Предпросмотр для гостя` entry calls `GET /api/venue/{venueId}/guest-preview`; the server returns `PUBLISHED_PUBLIC` only through the unchanged Guest DTO/read assembly and availability/subscription guards, otherwise it returns OWNER/MANAGER own-venue `PRIVATE_DRAFT` from the shared public-facing assembly of saved state. STAFF/foreign access is denied, Guest routes remain closed to unpublished state, responses are `no-store`, scoped preview media is authenticated without raw refs, inactive/hidden child data and guest mutation controls are absent, and settings dirty-state plus venue-switch stale-response guards are covered. Focused backend preview/Guest/RBAC/promotion tests, Kotlin compile/lint, Mini App build and deterministic browser smoke `95/95` passed; GitHub Actions were green, staging deploy completed and manual staging smoke passed.

Remaining P1:

- final staging smoke after each release batch;
- continue bounded venue settings slices where backend-backed; media-section authoring remains bot/platform-canonical, while the unified Published/private-saved Guest Preview and informational promotions have backend-backed Venue Mini App surfaces. Authenticated read-only preview delivery of existing guest-visible media does not add upload/authoring, and venue subscription state is covered by the staging-smoked billing MVP, while real acquiring and Telegram Stars remain separate future work;
- Venue Mini App normalize/reset helper only if still needed after pilots; base flavor profile apply, item-level stop-list and flavor-level stop-list parity are smoke-passed. Preserve STAFF no-settings/no-menu-content-management boundaries while keeping operational stop-list allowed;
- shift check is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**; keep the
  Owner/Manager/Staff/foreign, two-accordion UX, atomicity/stale/audit, Guest availability and
  Telegram stop-list parity scenarios in regression. It adds no Telegram shift-check UI and does
  not alter existing Staff individual availability;
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
- Bot/API Platform Owner config parity;
- connection requests stay visible through approve/create-link lifecycle;
- `trial=0`, commercial terms, subscription sync and future price schedule baseline;
- suspend/archive/delete status actions; deleted venues hidden from normal lists;
- safe onboarding/placements/analytics sections without fake data or dead-end controls.
- Platform Owner invite/add OWNER flow with usable Telegram deep link/copy text, intended-venue OWNER acceptance and `VENUE_OWNER_INVITE_CREATE` / `VENUE_OWNER_INVITE_ACCEPT` audit.
- Platform Mini App owner assignment no longer exposes `ADMIN` as a selectable runtime role; `ADMIN` remains a legacy DB alias to `MANAGER` only.
- Platform Owner active OWNER membership list and OWNER revoke, with server-side last-owner protection, non-platform denial, membership-based runtime access loss and `VENUE_OWNER_REVOKE` audit.
- Platform Owner billing cockpit with read-only overview, explicit invoice/checkout ensure POST, manual/fake invoice creation, manual mark-paid audit and human paid-through/next-payment copy.
- Billing renewal with effective paid-through based next-period invoice creation, idempotent advance invoice ensure and `billing_adjustments` courtesy-days model with required reason plus `BILLING_COURTESY_DAYS_ADDED` audit.
- Venue Owner subscription screen shows adjusted paid-through and next-payment state, but cannot mark paid or add courtesy days; Manager/Staff payment controls stay hidden/forbidden.
- Platform Support Center / `Обращения` for `SUPPORT_TICKET`, including platform-only technical tickets and venue-transferred tickets; ordinary `VENUE_CHAT` is intentionally not visible to Platform.
- Platform cockpit model in `docs/PLATFORM_COCKPIT.md` separates current implementation (`DRAFT`, `PUBLISHED`, `HIDDEN`, `PAUSED`, `SUSPENDED`, `ARCHIVED`, `DELETED`) from the target lifecycle (`draft`, `onboarding`, `published`, `hidden`, `paused_by_owner`, `suspended_by_platform`, `archived`, `deletion_requested`, `deleted`).

Remaining P1/P2:

- advanced support features: SLA automation, auto-escalation worker, macros, attachments, CSAT, diagnostics reports and support analytics;
- richer analytics dashboard;
- placements cockpit parity with bot;
- real acquiring provider, Telegram Stars and automatic recurring card payment remain separate future milestones;
- audited invoice void/reissue for courtesy conflicts with already-open future invoices;
- distinction between billing-created and manual `SUSPENDED_BY_PLATFORM` before broader auto-reactivation;
- primary/legal/billing owner relink and a dedicated platform-mediated legal transfer helper;
- billing payer transfer if commercial ownership transfer requires it.
- controlled Platform Guest QR Phase 1 is closed; keep its single-instance pending, role/privacy/exit and old-link fail-closed contract in regression while the separately listed P2/future gaps remain open.

### Guest Growth / Retention / Promotions

Status: `SPEC UPDATED / PARTIAL-FUTURE`.

Canonical model: `docs/GROWTH_RETENTION.md`.

Current foundation:

- guest visit/order history foundation is DONE / MVP / staging-smoke-passed: guest list/detail are current-user scoped, booking-only `SEATED` visits and closed-order visits are visible, `CANCELED` / `NO_SHOW` / `EXPIRED` / `PENDING` / `CHANGED` bookings are hidden as visits, legacy invalid rows are preserved but filtered, old closed-order details render without required `promotionDiscounts`/options/notes, unsafe shared/personal tab details are filtered, and same-real-visit booking/order signals merge instead of double-counting;
- Post-Visit Feedback MVP is DONE / MVP / staging-smoke-passed: one manual rating/tags/comment from own completed History detail; booking-only `SEATED` remains eligible; Owner/Manager reads own-venue feedback; Staff denied; manual `5/5` may show a safe configured public review URL; low `1..3` follow-up opens exact `VENUE_CHAT` with context and no automatic message, support ticket or staff-chat notification;
- Guest Favorites Phase 1 is DONE / MVP / STAGING-SMOKE-PASSED: venue favorites only, catalog/detail actions, Account list, current-user isolation, unavailable-venue filtering/restoration, shared Bot/Mini App source, Telegram Profile/Catalog entrypoints and source-aware Back;
- Simple Venue Promotions Phase 1 is `DONE / MVP / STAGING-SMOKE-PASSED`: Owner/Manager management and current-active Guest venue-detail rendering reuse `venue_promotions` and `VenuePromotionRepository`; green Actions, staging deploy and manual smoke are confirmed;
- Executable Promotions Phase 2 / Happy Hours Percent is `DONE / STAGING-SMOKE-PASSED`; Gift Bot/Mini App parity is `GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT` and awaits independent review, CI and staging;
- transactional booking reminders are not marketing notifications.

MVP target:

- `FAVORITE_VENUE` is DONE / MVP / STAGING-SMOKE-PASSED for venue-only Phase 1;
- `VISIT_HISTORY`, `ORDER_HISTORY`, `BOOKING_HISTORY`; visit/order history foundation is DONE / staging-smoke-passed, broader booking history polish remains in regression/follow-up scope;
- `REPEAT_TEMPLATE` that applies only in the next verified table context and never creates an order without table context;
- `POST_VISIT_FEEDBACK` after confirmed visit is DONE / MVP / staging-smoke-passed; automated prompts and public review automation remain future/disabled;
- simple informational `VENUE_PROMOTION` with required title/description/active period, optional terms and `DRAFT` / `ACTIVE` / `PAUSED` / `ARCHIVED` lifecycle is implemented and locally validated;
- `OPT_IN_NOTIFICATION` with frequency limits and unsubscribe before any promo/retention sends.

After MVP:

- `PROMO_CODE` with limits/accounting;
- `LOYALTY_STAMP` / `LOYALTY_POINTS`;
- `REFERRAL`;
- segmentation/campaigns;
- paid placement/promotion boosting with visible ad labels;
- advanced recommendations/taste quiz.

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
- QA/smoke source of truth is `docs/TESTING_QA_SMOKE_STRATEGY.md`; use it for local command selection, failure reporting, staging policy and Codex handoff format.
- Deployment/runbook source of truth is `docs/DEPLOYMENT_RUNBOOK.md`; use it for release model, staging deploy command, environment inventory, migrations, rollback policy, incident response and ChatGPT/Codex handoff.
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
5. M4B: Unified Messages Inbox UX; Status: CLOSED / staging smoke passed. Guest `Сообщения` / `Мои обращения` is a thread list with venue, context, status, last message, time and unread badge; Venue `Сообщения` is scoped to the current venue. Historical M4B note: Platform Support Center was deferred until backend-backed; the later Guest Communication UX / Support Tickets MVP now provides that support-ticket-only center.
6. M4C: Support thread resolve/reopen lifecycle; Status: CLOSED / staging smoke passed. Conversation lifecycle is separate from booking lifecycle; active/resolved filters have explicit user actions and do not mutate booking lifecycle.
7. M5: Staff Calls Lifecycle and Notification Parity; Status: CLOSED / staging smoke passed. Guest Mini App creates scoped staff calls with compact transient UX; Venue Mini App accepts/closes active calls; staff chat remains notification mirror.
8. M6: staff chat diagnostics/unlink polish. Status: CLOSED / staging smoke passed. Venue Mini App exposes existing status/link/test/unlink semantics, uses masked chat id, reports test-send as queued when it goes through outbox, keeps unlink OWNER-only and shows active-code card with copy-first action plus confirmed regeneration.
9. M7a: booking hold settings in Venue Mini App; Status: CLOSED / staging smoke passed. The `Настройки` screen exposes current hold duration and updates it for OWNER/MANAGER only using the same `venue_booking_settings.hold_minutes` semantics as the bot. STAFF stays denied/hidden.
10. M7b: Guest Mini App `Мои брони`; Status: implemented, code/test/e2e-backed, staging visual parity passed for the same booking's public label, venue-local time and `Держим до` in Bot `/my` versus Guest Mini App. Real two-account Telegram runtime isolation remains unverified.
11. M7c adaptive transactional booking reminders: Status: implemented, code/test/e2e-backed, real Telegram staging smoke passed for one controlled M7C reminder and guest attendance flow. Runtime remains opt-in disabled by default; only explicit `BOOKING_REMINDER_WORKER_ENABLED=true` starts the worker, and staging was returned to `BOOKING_REMINDER_WORKER_ENABLED=false` after smoke. M7c uses explicit confirmation/reschedule anchors, one scheduled pre-visit reminder maximum per booking, `QUEUED` after outbox enqueue, legacy-row isolation, atomic attendance confirmation, Telegram message editing and Mini App guest/venue attendance indicators. The enriched staff-chat attendance copy is code/test-backed but not manually re-smoked with a new booking.
12. M8a/M8b-Free: Venue Mini App structured public profile/card settings; Status: CLOSED / staging smoke passed. OWNER/MANAGER can edit guest-facing country/city/address, public contact and card description; venue name is read-only; STAFF is hidden/forbidden; country/city suggestions are local and provider-free, address entry is manual, and guest public read models/routes prefer existing saved coordinates while falling back to encoded text address search. M8b V110 structured-location migrations keep `countryCode`, `formattedAddress`, `latitude` and `longitude` nullable/backward-compatible. Yandex Geosuggest/Geocoder remain optional disabled adapters with separate keys for a later approved commercial setup, not the production default.
13. M9b: Venue Working Hours and Date Exceptions Mini App Parity; Status: CLOSED / staging smoke passed. Existing backend/Bot weekly hours and concrete-date overrides now have Venue Mini App owner/manager settings, inclusive date-exception ranges with optional guest-facing reason/comment, M9b.2 post-save exception UX, M9b.3 date-range editing for existing exceptions, guest-visible open/closed read models and direct Mini App booking validation with human schedule rejection copy, without broad settings redesign. M9b.1 Schedule Exception Ranges and Guest Copy, M9b.2 Schedule Exception Save UX and M9b.3 Schedule Exception Range Editing are also CLOSED / staging smoke passed; keep schedule validation and Bot closed-date copy in regression.
14. Simple Venue Promotions Phase 1: `DONE / MVP / STAGING-SMOKE-PASSED`; focused Owner/Manager builder/status management and current-active Guest venue-detail reads share the existing Bot repository. GitHub Actions, staging deploy and manual parity smoke passed.
15. Venue Mini App Guest Preview Phase 2.1: **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**. One OWNER/MANAGER own-venue entry `Предпросмотр для гостя` calls `GET /api/venue/{venueId}/guest-preview`; the backend selects `PUBLISHED_PUBLIC` or `PRIVATE_DRAFT` through the shared public-facing assembly. Published mode is the exact guarded Guest venue/info state; private mode is the saved public-facing projection when Guest availability is unavailable, without any draft access through the public Guest API. The UI shows exact published/private badges and copy, safe lifecycle reasons, origin-aware return, authenticated scoped existing media and no guest actions. Unsaved public-card, weekly-schedule or date-exception edits block preview with no auto-save; after Save it reads backend state. Venue switch aborts/clears and rejects late state. STAFF/foreign/Platform-only access, private fields, hidden sections, unpublished staff, inactive/non-current promotions and raw refs remain denied. GitHub Actions were green, staging deploy completed and manual staging smoke passed. Media upload, draft editing, publication and share links remain out of scope.
16. Menu Shift Check Phase 1: **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.
    The Menu screen uses two collapsed-by-default task-oriented accordions with mutual collapse.
    OWNER/MANAGER review the saved menu, use ordinary availability switches or enter a separate
    mass-selection mode, prepare changes locally and confirm one bounded atomic batch with a
    summary, optimistic stale protection, no-op completion and one safe audit. Draft survives
    collapse and is cleared on cancel/venue switch. STAFF remains on the existing individual
    stop-list policy and has no shift-check entry/direct permission.
17. Future: menu semantic type/media polish after current options/flavors regression remains green.

| Priority | Block | Current evidence | Product target | Recommended action |
| --- | --- | --- | --- | --- |
| P1 ACTIVE | Bot-to-Mini-App Parity Program | Bot selected-venue hub already has sections `Работа смены`, `Настройка заведения`, `Статистика`, `Продвижение`, `Предпросмотр для гостя`; Venue Mini App has M1 IA shell, M2 read-only `Статистика`, M3 booking queue/lifecycle, M4A booking conversation threads, M4B/M4C inbox lifecycle, M5 staff-call lifecycle plus ACK/DONE audit hardening, M6 staff-chat diagnostics/unlink and M7a booking hold settings closed. Guest Mini App M7b `Мои брони` is implemented with code/test/e2e evidence and staging visual parity for Bot `/my` label/time/deadline; real two-account Telegram isolation remains unverified. M7c adaptive reminders passed a controlled real Telegram staging smoke and is still disabled by default for rollout. M8a/M8b-Free structured public profile/card settings is CLOSED after provider-free staging smoke; Yandex adapters remain disabled and optional. M9b schedule parity plus M9b.1/M9b.2/M9b.3 improvements are CLOSED / staging smoke passed. Simple Venue Promotions Phase 1 and Executable Promotions Phase 2 / Happy Hours Percent are DONE / STAGING-SMOKE-PASSED; Gift parity is `GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT` and awaits independent review, CI and staging. Platform Owner Invite / ADMIN Semantics Hardening, Platform Venue OWNER Revocation, H2/PostgreSQL active-order + personal-tab uniqueness fidelity, Mini App mutation / operational verification closure pack and Guest Communication UX / Support Tickets MVP are CLOSED / validated. Unified Guest Preview Phase 2.1 is **DONE / MVP / STAGING-SMOKE-PASSED** under one `Предпросмотр для гостя` entry and one backend-selected `PUBLISHED_PUBLIC` / `PRIVATE_DRAFT` endpoint. Full map is in `docs/audit/VENUE_BOT_TO_MINIAPP_PARITY_PROGRAM.md`. | Bot and Mini App are two clients over one backend; required core surfaces must be aligned or documented as intentional exceptions. Mini App should show only backend-backed functionality. Functional correctness stays ahead of broad visual redesign while core blocks are still being closed. | Keep M7b two-account isolation, M7c reminder flow, M8b public card/location settings, M9b schedule validation, owner invite, owner revoke, H2 uniqueness fidelity, Mini App mutation/fallback payload, staff-call ACK/DONE actor audit evidence, communication split/support-ticket routing, promotion cross-surface parity and unified preview RBAC/privacy/dirty-state/stale-state behavior in regression. Keep structured venue-initiated reschedule proposals, media-section authoring and advanced support automation/diagnostics as separate future work. |
| PARTIAL / BOT-FIRST | Venue/Public Card Media Management | Bot OWNER/MANAGER can add image/PDF attachments backed by Telegram `file_id`, delete an attachment and hide/show its whole info section. Guest Mini App and `PUBLISHED_PUBLIC` render through the guarded Guest proxy; `PRIVATE_DRAFT` uses the authenticated scoped preview proxy. Venue Mini App has no author/upload/manage flow. Direct replace and per-attachment hide are absent. | One safe media source of truth or compatible Bot/Mini App bridge; public DTOs expose only guest-safe delivery URLs. | Keep working Bot/Guest/preview rendering. Do not claim Mini App upload parity. |
| MISSING / FUTURE / SPEC COMPLETE / DECISION REQUIRED | Venue Mini App Media Upload & Management Foundation | No browser file picker, upload endpoint or storage abstraction exists. Structured menu-item photo/description/thumbnail and option/flavor media are not in current Bot/Mini App/Guest contracts. Staff photo upload is a separate consent-bound future scope. | `docs/MEDIA_STORAGE_UPLOAD.md`: evolve the existing info-section table into one hybrid asset ledger; preserve legacy `TELEGRAM_FILE`; OWNER/MANAGER own-venue upload/manage; strict JPEG/PNG/PDF validation; safe lifecycle/audit/delivery; no raw refs or second list. | **STOP_FOR_MEDIA_STORAGE_DECISION**. Choose hybrid private S3-compatible target (recommended, provider undecided), Telegram technical storage chat, or operations-qualified filesystem volume before runtime. No byte backfill is required for the first slice. |
| P1 CLOSED | Staff-chat main order vs doporders clarity | Product spec already models `order_batches` with statuses; Venue Mini App can show batches, and live staff-chat now separates the main order and doporders/add-batches in one message. Staging smoke passed: one live message, venue-local time without `UTC`, separate blocks and clear batch statuses/actions. | One live staff-chat message stays canonical, visually separates the main order and each doporder/add-batch, shows batch status, and applies action buttons to the correct operational context. | Keep in regression smoke. Preserve `OrderBillSnapshot` as money source. |
| P1 CLOSED | Guest table session persistence/restore | Backend has authenticated `GET /api/guest/table/restore`; Mini App startup restores the latest safe active table context when no explicit QR token is present, and explicit QR/start token still wins. Automated coverage includes active restore, cross-user denial, closed-only denial, latest-context selection, browser startup restore and account-switch storage isolation. Staging smoke passed on 2026-06-08: reopen without QR restores table context, `Мой заказ` / menu / profile / support navigation keeps context, Telegram BackButton no longer loops, and root can close cleanly. | While an active table session/tab/order exists, returning guest re-enters table context safely without rescanning QR. Manual user-scoped exit now prevents restore until explicit QR re-entry. | Keep in regression smoke. Preserve QR/start-token priority, account-switch isolation and user-scoped exit markers. |
| P1 CLOSED | Guest Table Context UX Cleanup / Feature-gated Extension Module | Real Telegram Mini App staging smoke confirmed active QR table context, no prominent route/copy address/booking actions in table context, preserved pre-visit venue-card address/route/copy/booking actions, and extension visibility tied to active order/bill availability. | Guests in table context see orderable actions only; `Продление работы заведения` appears only when the current active order/state makes it actionable and disappears after bill/order close. | Keep pre-QR vs table-context action separation and extension gating in regression. Do not expose extension as a menu/cart/order-batch item. |
| P1 CLOSED | Guest Table Session Exit / Expiry UX | Real Telegram Mini App staging smoke passed after fixing the initial `415 Unsupported Media Type` by sending JSON with `Content-Type: application/json`. The endpoint remains user-scoped through `guest_table_session_exits`; shared `table_sessions` are not closed for all guests. | `Завершить визит` clears only the current guest's restorable table context when there is no active order/bill or NEW/ACK staff call. Active current-user obligations block with clear copy; DONE calls and other guests at the same table do not block. Explicit QR scan re-enters and clears that user's exit marker. | Keep user/tenant scoping tests, stale-restore checks and real Telegram QR re-entry in regression. Shared physical table-session close after all bills are closed remains a separate lifecycle decision. |
| P1/P2 FOLLOW-UP | Paid venue/shift extension owner settings parity | Backend data/API, Guest Mini App request UX, bill service charges, Venue Mini App owner/manager settings, Venue order queue/detail approval, Staff Chat pending approve/reject actions and Guest Bot ordering-menu section request entry are implemented. Existing `order_batch_items` require `menu_item_id`, so extension remains a separate service charge rather than a normal menu/cart item. Owner/Manager Bot settings parity is still pending if bot-side settings remain required. | Guest requests extension from active order/table/bill context through service action `Продление работы заведения`; STAFF/MANAGER see and approve/reject fixed-price requests inside active order/table/bill context and staff-chat live order message; MANAGER/OWNER configure price/duration in Mini App. | Defer Owner/Manager Bot settings parity behind guest/order/bill correctness unless a pilot venue requires bot-only settings. Preserve STAFF no-settings rule and never expose extension as catalog item/cart item/order batch item. |
| P1 DONE / MVP / STAGING-SMOKE-PASSED | Staff profiles + today on shift | Phase 1 backend + Mini App implementation, GitHub Actions, staging deploy and post-fix staging smoke are complete. Canonical status is in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. | Owner creates/edits/publishes opt-in public staff profiles, profiles may be linked to venue members or display-only, Owner/Manager marks `Сегодня на смене`, and Guest sees only published public-card fields selected by the exact manual Today projection, never a full/future schedule. Venue staff cards are compact, create form is collapsed by default, `Другое` requires custom role name, raw User ID / Photo ref are not exposed, and guest `Сегодня работают` appears below main venue info. | Keep in regression. Do not add tips payments, providers, Stars, crypto, guest order online payment, schedule, photo upload or staff chat sign-up inside this existing slice. |
| STAFF OPERATIONS SLICE A / MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP / STAGING-SMOKE-PASSED | Staff access + team cards + shift defaults | Manager Staff-only invite create/list/revoke and display-only/Staff-linked card management are implemented with protected Owner/Manager linkage and transaction-bound safe audit. | Schedule GET returns batched effective venue hours; create AUTO defaults, MANUAL preservation and edit stored-time invariant are implemented. | Keep invite revocation, protected profiles, effective-hours defaults and role isolation in regression. Slice B extends this foundation without changing the closed Slice A status. |
| STAFF IDENTITY LINKING UX + DUPLICATE PREVENTION / DONE / MVP / STAGING-SMOKE-PASSED | Staff identity/linking over the implemented Staff schedule/profile foundation | Private profiles use an actor-aware `linkageClass/canManage/isSelf` projection; protected Manager responses redact raw linkage and are read-only. Create-from-member derives current `users` identity and always creates a hidden active draft. A shared target-membership lock protects create/relink/reactivation; PostgreSQL concurrency tests cover double create and relink without a migration. | Owner/Manager use safe human identity and `Создать карточку` / `Открыть карточку`; Manager duplicate state is read-only, Owner alone unlinks the wrong card, and Guest/Staff receive no directory. Duplicate data is never auto-merged/deleted/relinked or Schedule-deduped. | Keep privacy, concurrency, Owner-only repair and venue/account isolation in regression. The non-blocking free-member manual scenario is tracked once in the deferred backlog. |
| STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED | Staff schedule | Canceled Shift Restore + Bulk Assignment is also `DONE / MVP / STAGING-SMOKE-PASSED` over `staff_profiles` / `staff_shifts` and `UNIQUE (staff_profile_id, shift_date)`; no schedule migration or lifecycle change was needed. | Owner/Manager planned shifts, Staff safe read and manual Today Shift/Guest source passed smoke. | Keep restore/bulk atomicity, CAS, timezone/overnight, RBAC/privacy and Today/Guest regression. |
| STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED | Optional Team and Schedule Module / Guest MANUAL or SCHEDULE source | Additive PostgreSQL V121/H2 V122 settings migrations, full-object monotonic CAS repository/API, transaction-bound safe audit, narrow Owner/Manager permission and tenant-before-state module guards are implemented. Disable retains memberships, invites, roles, profiles, shifts and nested settings. | One shared Guest Today resolver preserves exact MANUAL publication and adds active-only timezone/overnight SCHEDULE projection with no fallback or private schedule metadata; Guest/Preview visibility masters and Venue/Staff Mini App states use the same bounded contract. | Targeted settings/routes/RBAC/Guest/Preview tests, compile/lint, Mini App build, exact full Mini App e2e (`131/131`) and real PostgreSQL V120→V121 migration smoke passed. Green Actions, staging deploy, staging PostgreSQL V121 application and the bounded 13-scenario manual smoke passed with one new backend instance and no old instance before settings mutation. Payroll, attendance, recurring shifts, swaps, reminders, Telegram mutation UI, staff chat, media/R2, tips/payments, staff membership mutation, Guest future schedule, automatic deletion and campaigns stay out of scope. |
| FUTURE | Staff profile photo upload/media picker | Current Phase 1 hides raw Photo ref manual input and uses safe placeholder/public fields. | Consent-based staff profile photo upload with safe storage, moderation/deletion rules and guest-safe rendering. | Do not expose raw `photo_ref` owner input. Specify storage/moderation before implementation. |
| FUTURE / SPEC DRAFT | Staff tips for a specific employee | Future staff-tip model is in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. | Phase 2 may add external staff tip link + `staff_tip_intent`; tip goes to a specific staff profile. | Tip intent is not proof of payment. Provider/direct payout is Phase 3+ after legal/product decision. Platform-collected tips are not MVP. |
| OPEN DECISION / FUTURE | Staff shift Telegram notifications and staff communication chat | Phase 1 does not implement shift sign-up/swaps or a separate staff communication chat. | Employees may later confirm shifts/request swaps through personal bot notifications; larger venues may need forum topics or a dedicated staff communication group. | Current recommendation: do not add a second group. Revisit only after Staff Schedule passes staging smoke. |
| P1 CLOSED | Guest/Menu Options & Flavors Parity | Backend selected-option persistence, Guest Bot structured submit, Guest Mini App option picker/cart identity, Venue Mini App item-scoped option CRUD, explicit item/option stop-list controls and Mini App shared base flavor profile apply are implemented. Staging smoke passed: new hookah item setup, idempotent `Добавить базовые вкусы`, item/flavor stop-list, manual flavor CRUD, hookah-only guest picker, `selectedOptionId` and `preferenceNote` all passed. | Menu item options/flavors are structured order modifiers. Guest Bot and Guest Mini App both show/select them; same item with different selected option or line-level `Пожелание к вкусу` is a distinct cart/order line; selected options affect price; line preference notes do not affect price; venue order detail, guest active order/bill, Guest Bot `Мой заказ` and staff chat show selected options/notes; unavailable/stop-listed options are hidden or rejected. | Keep in regression smoke. Remaining follow-ups are separate: Mini App normalize/reset if needed, DB-level duplicate/race protection if needed, and broad Venue Mini App IA parity. |
| P2 | Owner working days/hours/exceptions UX | Current owner bot has weekday base schedule controls and date-specific override controls (`open`/`closed`, time fields), which can be unclear when base day and override disagree. | UI should clearly separate weekly schedule from concrete-date exceptions, show whether a day is working/closed/overridden, and make each button's effect explicit. | UX audit/fix-pack after P1 operational blocks. |
| P2 | Owner timezone setup hint | `venue_settings.timezone` is the source of truth for venue-local time formatting. Current code has basic city/address inference rules; M8b-Free public-card location editing does not update timezone inference or add a rich owner-facing timezone suggestion flow. | Owner setup should suggest a timezone from city/address and clearly allow manual override; all venue-context guest/staff/manager/owner times should render in venue local time. | Later owner setup improvement. Keep timezone decisions separate from public-card route coordinates. |
| P2 | Local ФИАС/ГАР street-house suggestions | M8b-Free intentionally avoids runtime geodata APIs and only bundles a small local country/city seed; address remains manual and unverified. | If product needs full local street/house autocomplete, import selected ФИАС/ГАР regions into an indexed local table through an explicit maintenance command, update data offline, and never call ФИАС or another provider during normal user requests. | Separate data-engineering slice; do not commit multi-gigabyte archives or build a nationwide importer inside the settings UX diff. |
| P2 | `📖 Фото-меню` optional subsections | Current info/photo-menu model is a flat visible info section with media attachments; structured `🍽 Заказное меню` is separate. | Simple mode keeps one image list; advanced mode lets owner/manager enable subsections such as кальянное меню, напитки, чай, пробой посуды and custom sections. Guest sees subsections first when enabled. | Product model/read-model design; avoid confusing this with structured order menu. |
| P2 | Owner multi-image upload UX | Owner media upload keeps the upload state and confirms each media item, which can create repeated messages with `Готово`/`Назад`. | Multiple images should be collected without N noisy confirmation screens; after upload, return to an image list with change/delete/back actions. | Telegram UX debt fix-pack. Keep album-end logic explicit and avoid guessing Telegram media group completion. |

Restore + Bulk Assignment acceptance remains bounded to Owner/Manager own-venue schedule management.
Staff is read-only and foreign/Guest/Platform-only actors are denied. A create conflict may disclose
only the safe existing shift id/status/CAS/date/time to an authorized actor; canceled requires an
explicit restore choice and scheduled/active/completed blocks confirmation. The Mini App must send
one normalized batch after multi-select/common-time/per-profile review. Duplicate, stale, invalid or
conflicting input and audit failure roll back all rows and audits in the one transaction. Required
regression covers same-id/no-second-row restore, `STAFF_SHIFT_RESTORED`, mixed create/restore,
maximum 50, deterministic concurrency, existing single create/edit/cancel, Staff self-view,
effective-hours behavior and unchanged Today/Guest output. Exact validation results must come from
the current worktree commands in `docs/TESTING_QA_SMOKE_STRATEGY.md`, not an earlier suite count.

Post-M9b.3 checkpoint: M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed. The repository contains the committed opt-in ControlMaster helper, the normal deploy command remains supported and unchanged, one bounded retry opened the master after an SSH banner timeout, the actual staging deploy completed through the persistent connection, and local/public health, DB health and Mini App static checks passed. This proves the resilient path works; it does not prove why fresh SSH connections were dropped or permanently solve server SSH hardening. M7b Guest Mini App `Мои брони` is implemented with local validation plus staging visual comparison against Bot `/my` for public booking label, venue-local time and `Держим до`; only real two-account Telegram runtime isolation remains unverified. M7c adaptive reminders are implemented and passed one controlled real Telegram staging smoke for reminder delivery, visible message edit, attendance indicators, venue-controlled status preservation and idempotent repeat handling. The worker remains opt-in disabled by default, staging is back to `BOOKING_REMINDER_WORKER_ENABLED=false`, and broader operational rollout still needs explicit approval. The latest enriched staff-chat attendance copy is code/test-backed but was not manually re-smoked with a new booking. M8a/M8b-Free Venue Mini App structured public profile/card settings is CLOSED / staging smoke passed: OWNER/MANAGER can edit city/address/contact/description and structured country/city/address without runtime geodata providers, local country/city suggestions are bundled in the Mini App, missing cities and addresses stay manually enterable, STAFF is hidden/forbidden, guest public read models reflect saved fields, and route links prefer existing trusted coordinates while falling back to encoded text address search. Manually entered addresses are not verified coordinates. Yandex Geosuggest/Geocoder adapters remain disabled and optional/commercial-only until licensing changes, with separate Geosuggest and Geocoder key configuration and no real keys in repository or staging. M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 inclusive exception ranges/guest copy, M9b.2 exception save UX and M9b.3 date-range editing are CLOSED / staging smoke passed after owner smoke confirmed closed and changed-hours periods can be created, edited, have their date ranges changed and deleted, and guest/Bot closed-date paths reject with human copy. Platform Owner Invite / ADMIN Semantics Hardening and Platform Venue OWNER Revocation are CLOSED / staging smoke passed after owner invite create/accept, ADMIN removal from Platform Mini App assignment, active OWNER membership list, owner revoke, last-owner block, non-platform denial, runtime access loss and audit evidence all passed smoke. H2/PostgreSQL active-order + personal-tab uniqueness fidelity is CLOSED / validation passed after H2 V112 and focused behavior tests; PostgreSQL already had the intended constraints and no runtime or staging deploy changed. Guest Table Context UX Cleanup / Feature-gated Extension Module and Guest Table Session Exit / Expiry UX are CLOSED / staging smoke passed after real Telegram QR smoke and the `Content-Type: application/json` fix for `POST /api/guest/table/session/end`. Do not reopen closed options/flavors, stats, booking lifecycle, M4A-M4C conversations, M5 staff calls, M6 staff-chat management, M7a hold settings, M7b, M7c, M8a, M8b-Free, M9a, M9b-M9b.3, Platform Owner Invite / ADMIN Semantics Hardening, Platform Venue OWNER Revocation, H2/PostgreSQL uniqueness fidelity, guest table-context cleanup or guest table-session exit unless new smoke/code evidence contradicts the current status.

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
- advanced support flow polish;
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
- support/tickets MVP regression and advanced support follow-ups;
- analytics;
- real acquiring provider or Telegram Stars, if selected for commercial launch;
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

- Canonical Security/RBAC model: **UPDATED** in `docs/SECURITY_RBAC_MATRIX.md`.
- OWNER/MANAGER/STAFF/PLATFORM/GUEST visibility smoke;
- cross-venue denied;
- cross-session table access denied;
- shared tab membership enforced;
- no secrets/tokens/initData/table tokens in logs or AI context.

## 7. Remaining Backlog

### Launch-Critical Follow-Up

Recently closed:
- Platform Owner Invite / ADMIN Semantics Hardening: **CLOSED / staging smoke passed**.
- Platform Venue OWNER Revocation: **CLOSED / staging smoke passed**.
- H2/PostgreSQL active-order + personal-tab uniqueness fidelity: **CLOSED / validation passed**. H2 now mirrors PostgreSQL active-order and active-personal-tab uniqueness predicates; PostgreSQL already had the intended constraints, no PostgreSQL production migration was added, runtime API/routes/Mini App/Bot behavior was unchanged, commit `a4a2d71` is on `origin/main`, and no staging deploy was required.
- Mini App mutation / operational verification closure pack: **CLOSED / code-test verification passed**.
- Staff Call Lifecycle ACK/DONE audit hardening: **CLOSED / staging smoke passed**. Venue Mini App and Telegram staff-chat ACK/DONE transitions write actor-bearing audit rows with safe source payload; audit is best-effort. Guest-visible `CANCELLED` is also CLOSED / staging smoke passed. Row-level ACK/DONE actor/timestamp columns and manual cancel UI remain out of scope.
- Staff-call guest-visible CANCELLED finishing patch: **CLOSED / staging smoke passed**. Guest staff-call status includes `NEW`, `ACK`, `DONE` and `CANCELLED` for the current guest and current `tableSessionId`; `CANCELLED` uses `Вызов отменён`; Venue active queue remains `NEW` / `ACK`; no migration, manual cancel UI, Mini App change or staff-chat callback refactor was added.
- Guest Table Context UX Cleanup / Feature-gated Extension Module: **CLOSED / staging smoke passed**. Table-context route/copy/booking actions are no longer prominent, pre-visit venue cards keep address/route/copy/booking, and extension entry is feature-gated by active order/bill availability.
- Guest Table Session Exit / Expiry UX: **CLOSED / staging smoke passed**. `Завершить визит` works after the JSON `Content-Type` fix; exit is user-scoped through `guest_table_session_exits`, shared `table_sessions` stay open for other guests and TTL cleanup handles physical-session expiry.
- Platform Billing Cockpit / Owner Payment UX: **CLOSED / staging smoke passed**. Platform Owner billing cockpit, Venue Owner subscription screen, read-only GET overviews, explicit invoice/checkout ensure POST, manual/fake invoice flow without exposing provider-internal fake URLs, manual mark-paid audit and human period copy are verified.
- Platform Billing Renewal / Advance Invoice / Courtesy Days: **CLOSED / staging smoke passed**. Next invoice period starts at effective paid-through + 1 day, repeated next-invoice ensure is idempotent, Platform Owner can create the next invoice in advance, `billing_adjustments` represents `COURTESY_DAYS`, courtesy requires reason, writes `BILLING_COURTESY_DAYS_ADDED`, shifts paid-through/next-payment dates, and Venue Owner/Manager/Staff payment-control denials are verified.
- Staff/Manager invite deep-link sharing polish: **CLOSED / staging smoke passed**. Telegram invite messages use accepted `staff_invite_<code>` deep-link payloads and copy-text buttons where supported; Venue Mini App invite result has one selectable link field, copy-link and Telegram-share actions, a secondary fallback command and no risky self-open action; Manager/Staff acceptance and billing-control denial smoke passed.
- Guest Communication UX / Support Tickets MVP: **CLOSED / smoke passed**. `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` are separate; guest `Чаты` / `Помощь` labels are live; catalog/venue detail `Задать вопрос` opens/reuses `VENUE_CHAT`; support tickets have Guest/Venue/Platform routing, Staff denial, staff-chat exclusion and guest rate limits.
- Booking Arrival Guard / Staff-Chat Booking Buttons: **CLOSED / staging smoke passed**. Arrival terminal actions are visible/accepted only from `CONFIRMED`; `PENDING`, `CHANGED` and terminal statuses do not show or accept seat/no-show; staff-chat booking notifications are state-aware; stale/no-permission callbacks answer safely; `BOOKING_CHAT` replies do not post to staff-chat.
- Repeat as Template Phase 1: **MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE**. One shared `RepeatOrderResolver` serves Guest Mini App and Telegram, builds a transient plan for one own completed order, requires an active same-venue table session plus an authorized personal/joined shared tab, re-resolves current item/option availability and prices, and adds eligible lines only to the local cart after explicit confirmation. No persistent template, order, batch or staff-chat notification is created. Required environment-dependent checks remain `BLOCKED_BY_ENVIRONMENT` in [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001).
- Simple Venue Promotions Phase 1: **DONE / MVP / STAGING-SMOKE-PASSED**. Owner/Manager manage informational promotions in Venue Mini App, Staff is hidden/forbidden, Guest venue detail receives only current `ACTIVE` records for a guest-available venue, and Telegram/Mini App share `VenuePromotionRepository`. No migration, discount engine, order-price effect, campaign send or paid placement was added.
- Promotion creation audit: **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Mini App and Telegram use one required create contract; parent, caller-connection initial rules and exactly one safe `VENUE_PROMOTION_CREATED` commit or roll back together. This closes neither configuration edit audit nor broader dangerous-action audit.
- Promotion effective state clarity: **PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**. The UI derives scheduled/current/expired presentation without rewriting lifecycle status; expired remains in `Текущие`, Guest-hidden and pricing-ineligible, and extension reuses update plus authoritative reload.
- Promotion lifecycle status audit: **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Mini App status/archive maps `STALE` to a typed safe conflict and refreshes authoritative state without false success or automatic retry; `APPLIED`/`NO_OP` and Telegram behavior remain compatible. This closes neither promotion configuration audit nor the broader dangerous-action audit.
- Staff role/removal audit: **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Applied Owner-authorized Venue Mini App/Telegram role changes and removals share one locked transaction and exactly one targeted audit; the bounded staging role/parity/privacy smoke passed.
- Venue promotions list tabs: **VENUE PROMOTIONS LIST / CURRENT AND ARCHIVED TABS UX / DONE / MVP / STAGING-SMOKE-PASSED**. Default/current/archive partition, mutually exclusive mouse/keyboard-accessible panels, pause/archive behavior, read-only archived cards, RBAC and venue-switch isolation passed staging smoke; no counts or pagination are implied.

Latest implemented bounded runtime blocks:

1. **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.
   - `VenuePromotionRepository.createPromotion` requires a server-derived actor and server-owned source for every production caller.
   - Parent insert, Mini App `afterInsert` initial rule, parent/rule reread and one `VENUE_PROMOTION_CREATED` use the same JDBC connection and transaction before one commit; audit failure rolls every create-transaction write back.
   - Payload is limited to `venueId`, `promotionId`, `templateType`, `status=DRAFT`, source and rule id/version/status rows ordered by rule id. Telegram Happy Hours/Gift creation currently has no initial rule, so its creation payload has `rules=[]`; banner/media persistence remains separate.
2. **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.
   - One `VenuePromotionRepository` mutation owns the parent lock, deterministic rule locks/status synchronization and audit insert on the same JDBC connection and transaction.
   - A real transition commits exactly one `VENUE_PROMOTION_STATUS_CHANGED` or `VENUE_PROMOTION_ARCHIVED`; no-op, stale/repeated, denied, invalid/not-found and rolled-back mutations write no success audit.
   - Mini App derives actor from its authenticated session and source `VENUE_MINI_APP`; Telegram derives the current authenticated actor and source `TELEGRAM_BOT`. Neither public request/callback supplies audit authority.
   - Safe audit payload is bounded to venue/promotion/template identity, old/new status, source and rule id/version/old/new status rows ordered by rule id.
3. **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.
   - PostgreSQL V122 and H2 V123 add nullable `audit_log.target_user_id BIGINT`, named FK `fk_audit_log_target_user` to `users.telegram_user_id ON DELETE SET NULL`, and index `idx_audit_log_target_user_created_at (target_user_id, created_at)`.
   - `target_user_id is permitted only as a dedicated internal audit column. It remains prohibited in JSON, logs, errors and client projections.` Actor remains only in `actor_user_id`; target remains only in `target_user_id`.
   - Venue Mini App and Telegram derive actor/source server-side and use one repository transaction that locks actor, target and every Owner in deterministic user order, rechecks Owner/target/last-owner state, applies the mutation and appends audit on the same connection.
   - Applied role changes write exactly one `VENUE_STAFF_ROLE_CHANGED` with `oldRole/newRole/source`; applied removals write exactly one `VENUE_STAFF_MEMBER_REMOVED` with `oldRole/source`. Entity is `venue` / `venueId`; no target/actor/venue duplicate or unrelated identity is in payload.
   - No-op, repeated/not-found, invalid, denied, stale actor, last-owner, audit failure and rollback write no success audit. Deterministic PostgreSQL contention proves one Owner remains and the sole audit matches the applied winner.
4. **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
   STAGING-SMOKE-PASSED**.
   - Existing Venue Mini App and Telegram menu-management item-delete callers now require the
     authenticated actor plus server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`; there is no
     compile-time unaudited delete overload.
   - Current promotion parent/rule/item lock order, authoritative reference recheck, affected rule
     version bumps/reference cascades, item hard delete and exactly one `MENU_ITEM_DELETED` audit
     share one JDBC transaction. Audit/reference/SQL failure rolls all writes back.
   - Payload is limited to venue/item/category ids, source and a deterministic bounded affected-rule
     summary: exact unique count, first 50 sorted ids, omitted count and lowercase SHA-256 over the
     complete sorted set. It is below 4096 UTF-8 bytes and excludes menu/promotion content,
     Telegram/request data, secrets and PII. No migration was added.
   - Fixed rewards fail before writes with HTTP 409 and actionable copy; purchase targets and
     CHOICE allowlist items retain cleanup/version behavior. Remaining CHOICE options stay, the last
     option removes incomplete reward configuration, lifecycle status is not rewritten and fixed
     rewards are never replaced automatically. Green Actions for HEAD `822233c`, staging deploy and
     bounded blocked/allowed smoke are recorded complete.
5. **DANGEROUS ACTION AUDIT SLICE / MENU CATEGORY HARD DELETE AUDIT / DONE / MVP /
   STAGING-SMOKE-PASSED**.
   - The sole production writer and both current Mini App/Telegram callers require authenticated
     actor plus server-owned source; current Owner/Manager allow, Staff/foreign denial, empty-only
     policy and success/error envelopes are unchanged.
   - Scope/empty checks, category-reference snapshot/recheck, promotion parent/rule/category locks,
     bounded affected-rule summary, target cleanup/version bump, category delete and exactly one
     `MENU_CATEGORY_DELETED` commit atomically. Audit failure restores category, targets, versions/
     timestamps and audit; other failed/no-op outcomes write zero success audit.
   - Payload is limited to venue/category ids, source and the shared unique/sorted/first-50/exact-
     omitted/full-set-SHA-256 summary below 4096 UTF-8 bytes. Menu/promotion content, request/
     Telegram data, media, secrets and PII are excluded. No migration or new workflow was added.
   - Focused repository/routes/Telegram/promotion tests, deterministic PostgreSQL configuration race
     `14/14`, compile, lint, Mini App build and Playwright `139/139` remain regression gates. For
     release HEAD `0e30a9b`, the user-confirmed evidence records green Actions, staging deploy and
     the bounded 15-scenario role/parity/audit/privacy smoke passed.
6. **DANGEROUS ACTION AUDIT SLICE / MENU OPTION HARD DELETE AUDIT / ATOMIC BASE-PROFILE
   NORMALIZATION INCLUDED / DONE / MVP / STAGING-SMOKE-PASSED**.
   - Venue Mini App and Telegram direct delete share the audit-aware repository contract with
     server-derived actor/source; one committed physical delete writes one `MENU_OPTION_DELETED`.
   - One Telegram callback normalizes one item in one repository transaction: item and deterministic
     option locks, DB-current plan, obsolete deletes, missing canonical creates and one audit per
     real delete commit or roll back together. The canonical set is unchanged; custom/current
     canonical options and their price/availability remain intact.
   - Historical option id may become null through `ON DELETE SET NULL`, while immutable name/price
     snapshots remain readable. Deleted stale selections are rejected before a new order row.
   - For release HEAD `03ae0af`, the user-confirmed evidence records green Actions, staging deploy
     and the bounded 17-scenario role/parity/audit/history/stale-cart/normalization smoke passed.
     No migration or new product selection logic was added.

Current correction: the repeated staging smoke passed across Owner/Manager/Staff/foreign RBAC,
Mini App and Telegram lifecycle writers, exactly-one/no-op audit behavior, payload privacy, Guest
visibility, Happy Hours/Gift regression and cleanup. The historical first failed smoke is retained
as evidence: pause succeeded, a separate archive request followed, and Guest catalog was unavailable
because the subscription was `SUSPENDED_BY_PLATFORM`. Guest availability was restored before the
repeat; the subscription incident is not a promotion defect.

Simple Venue Promotions Phase 1 remains **DONE / MVP / STAGING-SMOKE-PASSED**. The
executable-promotions sections below retain their current implementation and validation status;
the audit slice changes no lifecycle, promotion calculation, stacking or Guest pricing contract.
Promotion configuration edit audit, remaining menu create/price/update/availability families, QR rotate,
force-close/session audit, tab reopen, analytics export, the Promotion Compatibility Policy and a
broader audit viewer remain future. The overall dangerous-action audit therefore remains partial.
The menu item, empty-category and option hard-delete slices are release-closed only for their
bounded MVPs. They do not close option create/price/name/type/update, availability, media or the
broader menu audit.

The staff role/removal slice remains bounded: invites, profile/linkage, Today/Schedule, Platform
OWNER revoke, menu, order/session and promotion mutations are outside it. Existing membership
policy, permissions, responses, UI controls and last-owner semantics are unchanged. The existing
cross-dialect `actor_user_id` nullability/FK inconsistency is a separate P2 follow-up; this slice
does not alter the actor column. The recorded staging smoke covered Owner role change/reload,
Manager authority and denials, Owner removal/access loss, last-owner, Mini App/Telegram parity,
no-op/repeat audit behavior, targeted audit privacy and ordinary-invite cleanup. This does not
close the broader dangerous-action audit.

Guest Favorites Phase 1 is staging-closed. Current code also shows the former Order Session Tab Core Hardening recommendation is already covered by table-session active-order uniqueness, tab-scoped Guest order routes and privacy regression foundations. Do not reopen that closed core without concrete regression evidence.

Not selected as implementation right now:
- H2/PostgreSQL active-order + personal-tab uniqueness fidelity is closed; keep it in regression.
- Order Session Tab Core Hardening stays a regression responsibility, not a new runtime block: preserve current `table_session_id`/`tab_id` behavior, active-order uniqueness, tab-scoped views and privacy boundaries from `docs/ORDER_SESSION_TAB_CORE.md`.
- Booking Reminder MVP is already implemented/test-backed and has controlled staging smoke; broader enablement is a rollout decision, not a new feature block.
- Staff Schedule runtime, Canceled Shift Restore + Bulk Assignment, Staff Operations Slice A and
  Identity Linking are `DONE / MVP / STAGING-SMOKE-PASSED`; identity/linking adds no Schedule
  calculation/lifecycle or Guest source change. Staff Photo Upload still needs consent plus safe
  media storage/picker policy and is not part of that implementation.
- Mini App mutation and fallback payload verification is closed; keep it in regression.
- Guest-facing bill/display-number/full-bill parity, Venue Mini App full bill parity, Guest Bill Request / Payment Method UX, Staff Chat Noise Reduction / Table Activity Card and hookah placeholder polish are closed; keep them in regression rather than selecting them again.
- Platform Billing Cockpit / Owner Payment UX, Platform Billing Renewal / Advance Invoice / Courtesy Days and Staff/Manager invite deep-link sharing polish are closed; keep read-only GET checks, explicit POST creation, courtesy audit, Manager/Staff payment-control denials and invite acceptance/share UX in regression.
- Support/tickets MVP beyond booking threads and Guest Communication UX split are closed; keep `BOOKING_CHAT` / `VENUE_CHAT` / `SUPPORT_TICKET` / `STAFF_CALL` routing, Staff denial, Platform support-only visibility, no staff-chat support spam and rate limits in regression.
- Staff call lifecycle ACK/DONE and ACK/DONE audit hardening are already CLOSED / staging smoke passed; keep them in regression.
- Production config / infra readiness remains a launch operations checklist item: stable backend/Mini App URLs, webhook URL, WebApp URL, CORS, secrets and environment profile documented.

### P1 Product Completeness

- remaining booking regression smoke, including real two-account Guest Mini App isolation and schedule validation;
- remaining backend-backed venue settings slices beyond booking hold, shift extension, public card/location and schedule;
- remaining guest growth/retention from `docs/GROWTH_RETENTION.md`: Repeat as Template Phase 1 remains locally validated with deferred manual smoke; Simple Venue Promotions Phase 1, Happy Hours Percent, Promotion Creation Audit, Promotion Effective State Clarity, Promotion Lifecycle Status Audit and Venue Promotions Current/Archived Tabs UX are `DONE / STAGING-SMOKE-PASSED`; Gift parity still awaits its recorded independent review, CI and staging gates; promotion configuration edit audit and the Promotion Compatibility Policy remain future; favorite menu items/options, recommendations/frequent items, notification opt-in, favorites-based promotions and loyalty stay future; venue favorites, History and Post-Visit Feedback stay in regression;
- menu/options/stop-list governance from `docs/MENU_OPTIONS_STOPLIST.md`: keep selected-option
  snapshots, Guest stale availability validation, the atomic shift-check contract and the
  staging-smoke-passed item, empty-category and option hard-delete audits in regression, and resolve
  broader menu constructor/media/top-list plus
  option/price/update/availability audit before calling menu complete;
- Venue Mode operating model from `docs/VENUE_OPERATIONS.md`: keep orders, bill/tabs, staff calls, bookings, stop-list, staff-chat source-of-truth policy and role-specific nav/API denial in regression before adding new venue screens;
- Booking lifecycle model from `docs/BOOKING_LIFECYCLE.md`: keep booking create/list, Venue queue actions, confirmed-only Staff arrival/no-show split, hold/deadline display, booking chat separation, support routing and reminder opt-in behavior in regression before adding preorder/history/loyalty.
- Telegram fallback/staff-chat model from `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`: keep QR `/start`, fallback order, staff-call, staff-chat link/test/unlink, state-aware booking buttons, callback RBAC and notification allow/deny policy in regression before expanding Telegram shortcuts.
- Testing/QA smoke strategy from `docs/TESTING_QA_SMOKE_STRATEGY.md`: match validation to change type, keep `scripts/dev/` out of accidental staging, report actual failed assertions from CI and run staging smoke only for runtime-impacting changes.
- Deployment/runbook policy from `docs/DEPLOYMENT_RUNBOOK.md`: docs-only changes skip staging deploy; runtime/migration/Telegram/billing/security changes require release gates, staging smoke and rollback notes.
- analytics/events MVP from `docs/ANALYTICS_EVENTS.md`: verify server-side event emission, audit boundaries, privacy-safe payloads and role dashboards before broad analytics work;
- security/RBAC parity from `docs/SECURITY_RBAC_MATRIX.md`: keep Staff denial, Manager billing denial, cross-venue isolation, table/session/tab boundaries, Platform support-only visibility and dangerous-action audit in regression before adding more operational routes;
- custom date range venue stats and AI summaries;
- platform placements cockpit parity;
- platform support-ticket regression and advanced support follow-ups;
- platform analytics;
- invoice void/reissue for courtesy conflicts with already-open future invoices;
- billing-created versus manual `SUSPENDED_BY_PLATFORM` distinction before broader auto-reactivation;
- expand frontend/browser e2e automation beyond the minimal Guest Mini App smoke.

### P2 / Future

- TODO: implement bot cart ↔ Mini App cart sync so cart items added in Telegram bot are visible in Mini App and vice versa.
- richer venue cards;
- media-heavy menu polish;
- advanced analytics;
- promo codes/cashback/tiered loyalty/referrals/paid placement boosting;
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

- `docs/PLATFORM_COCKPIT.md` - Platform Mode source of truth for cockpit, lifecycle, billing, support, analytics/audit and remaining Platform gaps.
- `docs/COMMUNICATION_MODEL.md` - Guest communication model and Support Ticket boundaries.
- `docs/SECURITY_RBAC_MATRIX.md` - Security/RBAC source of truth for roles, scopes, permissions, surface parity, dangerous actions, auth/trust boundaries and smoke checklist.
- `docs/MENU_OPTIONS_STOPLIST.md` - Menu/options/stop-list source of truth for structured menu, modifiers, snapshots, media/PDF boundaries, featured/top-list, shift check, availability validation and menu permissions.
- `docs/VENUE_OPERATIONS.md` - Venue Mode operating model for dashboard, orders, batches, tabs/bill, staff calls, bookings, menu/stop-list, tables/QR, staff/invites, staff-chat, settings, stats and operational smoke.
- `docs/BOOKING_LIFECYCLE.md` - Booking lifecycle model for guest booking flow, venue queue, statuses, hold/deadline, reminders, booking chat, support routing, analytics, RBAC and smoke.
- `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md` - Telegram bot fallback and staff-chat model for QR entrypoints, fallback ordering, staff calls, staff-chat link/test/unlink, notification policy, callbacks, parity, security and smoke.
- `docs/TESTING_QA_SMOKE_STRATEGY.md` - QA strategy for local validation, CI expectations, change-type test matrix, smoke suites, staging policy, failure reporting and Codex handoff.
- `docs/DEPLOYMENT_RUNBOOK.md` - Deployment/runbook source of truth for release model, staging deploy, environment inventory, migrations, rollback, logs, incident response and handoff.
- `docs/ANALYTICS_EVENTS.md` - Analytics/event names, KPI formulas, dashboard targets and audit/privacy boundaries.
- `docs/ORDER_SESSION_TAB_CORE.md` - Order/session/tab source of truth for table context, active orders, batches, tabs, bill flow, lifecycle and privacy boundaries.
- `docs/GROWTH_RETENTION.md` - Guest growth/retention model, MVP/future scope, dependencies, opt-in/privacy and future smoke checklist.
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

Latest implementation-closed blocks, with release/staging status kept per canonical row: Staff
profiles + today on shift Phase 1 (`DONE / MVP / STAGING-SMOKE-PASSED`); Staff-call
guest-visible CANCELLED finishing patch; Booking Arrival Guard / Staff-Chat Booking Buttons;
Platform Billing Cockpit / Owner Payment UX; Platform Billing Renewal / Advance Invoice / Courtesy
Days; Staff/Manager invite deep-link sharing polish; Guest Communication UX / Support Tickets MVP;
Guest History Foundation MVP; Post-Visit Feedback MVP plus public-review/follow-up smoke-fix; Guest
Favorites Phase 1 and Catalog Search and Filter Phase 1 (`DONE / MVP / STAGING-SMOKE-PASSED`).
Repeat as Template Phase 1 is `MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE`;
its production-readiness gate remains open in
[`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001), but does not block an
independent bounded block.

Latest release-closed bounded block: **VENUE MENU MANAGEMENT UX STABILIZATION / MOBILE
RESPONSIVENESS + PRICE INPUT ERGONOMICS + CONTEXT PRESERVATION / DONE / MVP /
STAGING-SMOKE-PASSED**. Release HEAD `a62faa5` matches `origin/main`; user-confirmed evidence records
green Actions, staging deploy and the bounded 16-scenario Venue Menu UX smoke passed. This closes only
responsive management layout, price-input ergonomics and stable-ID context restoration; it does not
close the broader Menu or Dangerous Action Audit programs.

Current bounded block: **IMPLEMENT_MENU_OPTION_PRICE_AUDIT_NEXT** is implemented locally.

Status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / MVP IMPLEMENTED / LOCAL
VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. This is not release/staging closure and does not
close the broader Menu or Dangerous Action Audit programs.

Implementation evidence:

- `VenueMenuRepository.updateOption` is the only production SQL writer that changes an existing
  `menu_item_options.price_delta_minor`. Authenticated Venue Mini App
  `PATCH /menu/options/{id}` is the only price caller; Telegram supplies `null` price and has no
  option-price writer. Initial option create and fixed-zero missing-profile creation are unchanged
  and outside this update slice.
- The Mini App session subject is the sole actor and `VENUE_MINI_APP` is fixed server-side. Body,
  query, path and client metadata cannot provide either. Owner/Manager retain current own-venue
  `MENU_MANAGE`; Staff, foreign and unaffiliated actors remain denied.
- The existing transaction takes a non-locking option-to-item hint, item `FOR UPDATE`, all item
  options by ascending id `FOR UPDATE`, rereads the DB-current target, checks canonical collision
  only for a real name change, applies the compound row update, appends any rename audit, appends any
  price audit and commits once. Audit/SQL failure restores name, price, availability, `updated_at`
  and both audit families.
- One real committed delta change writes exactly one `MENU_OPTION_PRICE_CHANGED`, entity
  `menu_item_option`, entity id `optionId`. Payload keys are exactly `venueId`, `itemId`, `optionId`,
  `oldPriceDeltaMinor`, `newPriceDeltaMinor`, `source`. Actor stays in the standard audit column.
  Names, availability, canonical values, promotion/cart/order data, raw request/initData, Telegram
  fields, media, secrets and unrelated PII are excluded.
- Exact-price no-op/retry, name-only, availability-only, denial/foreign/not-found, collision,
  SQL/audit failure and rollback write zero price audit. Price-only has no rename audit;
  name+price writes one independent audit of each family; adding availability remains atomic and
  adds no availability audit. No idempotency token is introduced.
- Integer minor units, zero delta, existing validation/request/response/UI parsing, currency and
  rounding are unchanged. Checkout reloads the current available DB option/delta; client/stale-cart
  price is not authority. New orders snapshot the current delta and historical
  `price_delta_minor_snapshot` rows remain immutable.
- Deterministic Testcontainers PostgreSQL coverage is nine tests, uses independent connections and
  confirmed blocking without arbitrary sleep, and covers price/price, price/rename, direct delete
  and atomic normalization. Exact XML is `9/0/0/0`; CI minimum is nine. Focused repository, route,
  Guest order/history and Guest visit selectors, compile, ktlint, Mini App build and Playwright
  `152/152` pass locally. No workflow or migration was added.

Next action is independent review before any authorized commit/push, then green Actions, staging
deploy and bounded smoke. Option create/availability, item price/update, Telegram price management,
membership-recheck hardening, promotion work, viewers and media/storage remain outside this slice.

### Recent release-closed audit block

Implementation contract: **IMPLEMENT_MENU_OPTION_RENAME_AUDIT_NEXT**, fulfilled and release-closed.

Verdict: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION RENAME AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Runtime evidence:

- `VenueMenuRepository.updateOption` is the sole production option-name SQL writer. Its transaction
  takes a non-locking option-to-item hint, locks the item, locks all item options by ascending id,
  rereads the target, applies the row update and appends rename audit before one commit.
- Production rename callers are exactly Venue Mini App `PATCH /menu/options/{id}` and the Telegram
  rename dialog. Mini App derives the authenticated session user; Telegram uses the current message
  user, requires it to match the persisted dialog owner and fails closed when it is absent or
  inconsistent. Neither surface accepts audit actor/source from client data.
- The Mini App request can combine name, price and availability. Telegram rename changes only name;
  Telegram has no option-price writer. The implemented slice audits only a real persisted name change
  without claiming price/availability audit coverage or changing the existing compound request.
- Canonical collision protection is already application-level and DB-current: hookah canonical
  create/actual rename and normalization share item-then-option locks. The PostgreSQL normalization
  suite already includes a canonical update/normalization blocking edge.
- Existing order rows store immutable `option_name_snapshot` and `price_delta_minor_snapshot`.
  Rename does not rewrite history; a not-yet-submitted selection keeps the same option id and is
  resolved at submit against the current name/price/availability.

Historical candidate comparison that selected the already release-closed rename slice:

| Candidate | Current runtime evidence | Selection result |
| --- | --- | --- |
| Menu option price audit | Mini App can combine name/price/availability; Telegram has no option-price writer. Current `updateOption` is locked/transactional, but delta changes preview, submit and promotion-adjusted totals. | Defer: asymmetric financial scope and current-order behavior make it riskier. |
| Menu item price audit | Mini App has a compound patch; Telegram has a separate price dialog. `updateItem` currently reads then updates without the option-style lock/audit transaction, and item price has the widest order/promotion blast radius. | Defer: broader two-surface financial/concurrency contract. |
| Menu option rename audit | Two concrete callers share one already locked repository writer; historical snapshots exist and canonical collision/normalization serialization is implemented. | **Selected:** smallest valuable cross-surface dangerous-action gap with low financial/security risk. |
| Menu option availability audit | Mini App plus several Owner/Manager/Staff Telegram callbacks use immediate `setOptionAvailability`; atomic Shift Check has separate batch audit semantics. | Defer: Staff authority, no-op handling and duplicate-audit boundaries with Shift Check need a wider slice. |
| Promotion configuration edit audit | Parent text/period, schedules, purchase targets, rewards and compatibility use multiple mutation/lock families. | Defer until exactly one family is selected; larger than rename. |
| Force-close/session audit | Runtime search finds TTL cleanup and guest exit but no bounded staff force-close production mutation. | Not selectable without a real writer and session-obligation design. |
| Notification Consent Foundation | No persisted operational/marketing scopes, evidence/version/source or opt-out contract was found. | Spec-first and larger than the existing rename path. |
| Promotion Compatibility Policy | Per-rule fields exist, but there is no common cross-promotion winner/stacking policy. | Defer: high financial/product-policy risk. |
| Menu audit hardening | Membership-revoke linearization, fuller rollback snapshots and Telegram negatives remain open. | Valuable, but broader/security-heavier than one real unaudited writer. |
| Stashed Media Foundation | Storage decision remains unresolved and stash is outside this audit. | Not selected; stash was not read or applied. |
| Other bounded block | No stronger smaller production mutation was found in the read-only audit. | Not selected. |

Exact bounded outcome:

1. Every production call capable of changing an option name passes authenticated actor plus
   server-owned `VENUE_MINI_APP` or `TELEGRAM_BOT` to one required repository contract; no client
   field or persisted Telegram dialog payload supplies audit authority.
2. Item lock, ascending option-row locks, DB-current target/collision recheck, option update and
   same-connection audit share one transaction. Audit/SQL failure rolls the whole row update back.
3. One committed real name change writes exactly one `MENU_OPTION_RENAMED` for entity
   `menu_item_option` / option id. Exact persisted-name no-op, missing/repeated request, denial,
   foreign scope, canonical collision, SQL/audit failure and rollback write zero success audit.
4. Payload keys are exactly `venueId`, `itemId`, `optionId`, `oldName`, `newName`, `source`. Names
   use the already validated trimmed maximum-120-character catalog values. Actor stays only in the
   standard audit column; prices, availability, category/item names, order/cart content, raw
   request/callback/initData, Telegram identity, secrets and unrelated PII are excluded.
5. A compound Mini App request with a real name change still updates its requested price/
   availability atomically, but writes only the rename audit; audit failure restores all fields.
   A price/availability-only or exact-name-no-op request writes no rename audit. This slice does not
   claim the co-fields are audited.
6. Existing canonical-profile set, collision policy, normalization outcome, roles, responses and UI
   remain unchanged. Serialization with normalization prevents duplicate canonical profiles and
   allows only fully ordered committed outcomes.
7. Existing order/history snapshots retain the old committed option name/price. A later new order
   with the still-valid option id snapshots the DB-current name and price; no order schema changes.

Explicit out of scope: option create, price and availability audit; item price/update audit; generic
option schema/type/archive/reorder work; new canonical-profile selection or uniqueness policy;
Shift Check changes; promotion configuration/compatibility; membership-revoke linearization;
cart/order schema changes; audit/dependency viewer; media/R2.

Migration/schema verdict: **NO_MIGRATION_EXPECTED**. Existing `audit_log`, bounded names and order
snapshot columns are sufficient.

Implementation surfaces:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/menu/VenueMenuRepository.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/menu/VenueMenuRoutes.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`;
- `VenueMenuRepositoryTest`, `VenueMenuRoutesTest`, `TelegramBotRouterTableTokenTest` and
  `VenueMenuOptionNormalizationConcurrencyPostgresTest`;
- Guest order/history regression tests only where needed to prove snapshot/current-name behavior;
- the smallest relevant menu/RBAC/QA/roadmap docs;
- the existing CI workflow minimum for the expanded critical PostgreSQL class. No Mini App
  TypeScript or migration was changed.

Focused backend, Mini App and Telegram coverage:

- Repository: actual rename/exact payload/exactly one; exact-name no-op; name-null price/
  availability-only zero rename audit; compound update one rename audit; missing/foreign/collision
  zero audit; injected audit failure restores name, price and availability.
- Mini App routes: Owner/Manager own venue allowed with session actor and
  `VENUE_MINI_APP`; Staff/foreign/unaffiliated denied; safe not-found/collision/no false success;
  existing editor request/response and Staff-hidden structural controls remain compatible.
- Telegram: current message user is the actor, `TELEGRAM_BOT` is server-owned, Owner/Manager allowed,
  Staff/foreign/absent-or-mismatched actor denied, cancel/no-op writes none and error paths never
  announce false success.
- History/order: pre-rename committed order retains snapshot name/price; a post-rename submit using
  the same valid option id snapshots the current name; no existing row is rewritten.
- PostgreSQL: extend the existing canonical-update/normalization deterministic blocking coverage to
  assert winner audits and no duplicates/partial state; preserve the four-suite mandatory gate.

Validation commands:

```bash
git status --short
git diff --check
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestOrderRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVisitRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*GuestTableContextActivationPostgresTest*' \
  --tests '*PromotionConfigurationConcurrencyPostgresTest*' \
  --tests '*VenueStaffMutationConcurrencyPostgresTest*' \
  --tests 'com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOptionNormalizationConcurrencyPostgresTest' \
  --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Concurrency/audit/privacy requirements: preserve item-then-ascending-option lock order; derive the
decision and old/new values from the locked DB row; append audit before commit; a serialized rename
then normalization may legitimately produce rename then delete audits, while normalization/delete
winning first yields not-found and zero rename audit. Never log or audit the raw request/dialog
payload. The pre-existing membership-revoke race remains a separate hardening block.

CI/release/staging gates for that rename release required all Actions green, including the then-current
PostgreSQL XML minimums `8 / 14 / 2 / 7`; the option-class minimum is now nine after the price-audit
extension. Staging deploy is required for repository/route/Telegram
behavior. Bounded smoke must cover Mini App and Telegram Owner/Manager rename, Staff/foreign denial,
exactly-one/no-op/collision/privacy, normalization serialization/idempotence, historical snapshot,
new-order current name, compound Mini App update compatibility and cleanup. Do not close option
price/availability/create audit, the broader Menu/Dangerous Action Audit or the whole product.

Local evidence: focused repository, Venue Mini App routes, Telegram router and Guest/order/history
selectors passed; `compileKotlin`, `ktlintCheck`, Mini App build and Playwright `139/139` passed.
Mandatory PostgreSQL XML is `8/0/0/0`, `14/0/0/0`, `2/0/0/0`, `7/0/0/0`.

Implementation contract retained for independent review:

```text
Следуй AGENTS.md. Сначала проверь active Goal, затем PROJECT_STATUS.md и только релевантные
canonical docs. Не читай и не применяй stash; не трогай scripts/dev; не deploy/push/commit/stage.

Outcome: independently review реализованный bounded DANGEROUS ACTION AUDIT SLICE /
MENU OPTION RENAME AUDIT для существующих Venue Mini App и Telegram rename paths без
изменения product semantics.

Сначала read-only подтверди writer inventory: VenueMenuRepository.updateOption — единственный
option-name SQL writer; production callers — PATCH /menu/options/{id} и Telegram rename dialog.

Контракт:
- actor только authenticated Mini App session user или текущий Telegram message user;
- source только server-owned VENUE_MINI_APP / TELEGRAM_BOT;
- один committed real persisted-name change -> ровно один MENU_OPTION_RENAMED;
- entity menu_item_option, entityId optionId;
- payload ровно venueId,itemId,optionId,oldName,newName,source;
- old/new брать из locked DB row и validated trimmed max-120 request value;
- exact-name no-op, missing/repeated, denial/foreign, canonical collision, SQL/audit failure и
  rollback -> zero success rename audit;
- item lock -> option rows ascending -> DB-current recheck/update -> same-connection audit -> commit;
- audit failure откатывает весь row update, включая co-submitted price/availability;
- price/availability-only update не пишет rename audit;
- compound Mini App name+price+availability сохраняет текущий API contract, но этот slice не
  объявляет price/availability audited;
- Telegram actor нельзя брать из dialog-state fallback; absent/mismatched current user fails closed;
- canonical set, collision behavior, normalization, roles/responses/UI не менять;
- historical order snapshot не переписывать; новый submit с тем же valid option id использует
  current DB name/price.

Out of scope for that rename slice: option create/price/availability audit, item mutations, Shift Check, new uniqueness or
base-option logic, migrations, audit viewer, membership-revoke linearization, promotions, media/R2.

NO_MIGRATION_EXPECTED. Verify focused repository/route/Telegram tests, Mini App RBAC/source,
Telegram actor/cancel/denial, historical/current-order behavior and deterministic PostgreSQL
rename concurrency. Its historical PostgreSQL gate was 8/14/2/7; the current option-class minimum is
nine. Re-run focused backend tests, compile,
ktlint and Mini App build/e2e. После green Actions нужны staging deploy и bounded cross-surface
audit/privacy/concurrency/history smoke.
```

Latest Staff Operations closures:
**STAFF IDENTITY LINKING UX + DUPLICATE PREVENTION / DONE / MVP / STAGING-SMOKE-PASSED**;
**STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**;
**STAFF SCHEDULE / CANCELED SHIFT RESTORE + BULK ASSIGNMENT / DONE / MVP /
STAGING-SMOKE-PASSED**; and **STAFF OPERATIONS SLICE A / MANAGER PARITY + SHIFT TIME DEFAULTS /
DONE / MVP / STAGING-SMOKE-PASSED**.
Venue Mini App now identifies accepted members by current Telegram display name/optional username,
offers `Создать карточку` or `Открыть карточку`, excludes linked targets and warns on existing
duplicates. Backend reuses `users` upserts, locks the target `venue_members` row and returns typed
link conflicts so concurrent create/link has one winner; duplicate data is not automatically
changed; Manager sees duplicate state read-only and Owner alone unlinks the wrong concrete card. No identity cache or
migration is added, and Guest DTOs/audit remain privacy-safe. The already implemented Restore +
Bulk Assignment context still supports explicit future canceled restore, group selection,
conflict preflight and one atomic batch; its calculations/lifecycle are unchanged. Manual Today
Shift and Guest `Сегодня работают` remain the closed Slice A baseline. Green Actions, runtime deploy,
Slice A V120/H2 V121 rollout and manual smoke are complete. The separate free-member
create-from-member manual scenario remains non-blocking in `STAFF-IDENTITY-MANUAL-001`. Slice B is
`STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE /
DONE / MVP / STAGING-SMOKE-PASSED`; green Actions, staging deploy, PostgreSQL V121 rollout and the
bounded manual smoke are complete. Telegram, reminders, recurring/split shifts, mass edit,
payroll, staff media and Media Upload/R2 remain out of scope. Canonical acceptance and validation gates are in
`docs/STAFF_PROFILES_SHIFTS_TIPS.md` and `docs/TESTING_QA_SMOKE_STRATEGY.md`.

Latest closed bounded runtime block as of 2026-08-05:
**PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**.

- Previous root cause: `handleStartCommand` returned the Platform Owner menu before parsing
  `/start <table_token>`, so Platform precedence made ordinary Guest QR verification unreachable.
- Exact Platform Owner with a valid public Guest table token receives safe venue/table copy and
  explicit confirm/cancel. Telegram carries only a short opaque reference to a five-minute
  process-local pending record bound to actor, chat, token, venue and table; restart loses it safely,
  and prompting creates/touches no Guest context/session/exit/audit state.
- Confirm consumes pending, re-authorizes exact Platform Owner and commits
  `PLATFORM_GUEST_QR_TEST_CONFIRMED`. That audit records confirmation only and is not
  `GUEST_CONTEXT_APPLIED`. One JDBC transaction then re-resolves and locks the exact token/table/
  venue state, reapplies published-venue and subscription guards, resolves or touches the session,
  clears the exit marker and persisted Guest dialog, and saves the exact chat context. Any late
  repository/SQL failure rolls every activation write back; in-memory cart/draft cleanup starts only
  after commit. Audit failure creates no Guest context; its payload contains standard actor plus safe
  venue/table/source only. Cancel has no audit or activation.
- Confirmed routing is the ordinary Guest Bot/table/session/tab engine and Guest Mini App
  `mode=guest`. Exact Platform Owner Mini App create/touch and explicit-session resolve require the
  matching active server-owned Telegram context and no exit marker; an old entry after exit fails
  closed. `Завершить визит` uses stored context only as teardown identity, so current token/table/
  venue/subscription unavailability cannot prevent context/dialog/draft/pending cleanup or Platform
  menu restoration. Tokenless `/start` keeps Guest routing while confirmed context is active and
  returns to Platform Mode only after exit. Ordinary Guest/Venue Owner/Manager/Staff precedence is
  unchanged; there is no persistent impersonation mode or bypass.
- Schema verdict: `NO_MIGRATION`. The slice reuses existing table-token, table-session, chat-context,
  dialog, user-exit and audit tables. Commit/push, green Actions for the release HEAD, staging deploy
  and the bounded real Telegram role/privacy/exit smoke are complete. Media/R2, notifications,
  promotions, payments, loyalty, Staff work and broader Platform parity stay out of scope.
- Non-blocking P2/future remains open: pure-read repeat-plan Platform guard; immediate-exit marker
  without a personal tab; multi-instance/distributed pending; broader Platform/Guest UI mixing;
  non-authoritative dialog/draft linearity; linked-order pre-read hardening; and a fuller
  endpoint-specific race matrix. None blocks the staging-closed single-instance Phase 1 contract.

Latest closed bounded runtime slice:
**CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.

- Authenticated `GET /api/guest/catalog` accepts optional `q` and `city`. Both are trimmed, blank is
  absent and values over 100 characters are rejected rather than truncated. `q` matches name,
  city, address and formatted address case-insensitively; `city` is a case-insensitive exact match;
  together they use `AND`.
- Prepared parameters and explicit LIKE escaping keep `%`, `_`, `!` and `\` literal with compatible
  PostgreSQL/H2 behavior. The existing `PUBLISHED` lifecycle and guest subscription/availability
  resolver remain authoritative, ordering remains deterministic, and current-user favorites plus
  today schedule use the existing batch enrichment without N+1 queries.
- The Mini App sends encoded backend `q`/`city` after a fixed 300 ms debounce. Request abort plus a
  latest-generation guard prevents stale responses and screen disposal cancels pending work.
  Initial loading, retryable error, base-catalog empty, filtered no-match and reset states are
  distinct; optimistic favorite overrides remain authoritative across filtered reloads and user
  switches clear catalog/favorite state.
- City options come from the initial complete unfiltered guarded catalog. The current endpoint has
  no limit or pagination; blank cities are removed, spelling is preserved while deduplicating
  case-insensitively, and options are sorted predictably.
- Focused `GuestVenueRoutesTest`, backend compile, ktlint, Mini App production build, focused
  catalog/favorite browser checks and the prescribed full deterministic Playwright smoke `104/104`
  passed locally. GitHub Actions were green, staging deploy completed and manual staging smoke
  passed on the current limited venue dataset. No migration, index, facets/pagination API,
  media/R2 or analytics work was added.
- Extended multi-venue catalog dataset regression is **NON-BLOCKING DEFERRED MANUAL SMOKE /
  CATALOG-SEARCH-MANUAL-001**. The limited current dataset does not downgrade Phase 1, but the
  deferred scenarios must pass before catalog pagination, ranking, map/geo or a large pilot rollout.

Earlier staging-smoke-passed bounded runtime slice:
**MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.

- OWNER/MANAGER use `Проверка меню перед сменой` inside the existing Venue Menu screen; STAFF is
  hidden/forbidden and its existing individual item/option stop-list behavior is unchanged.
- `Редактирование меню` and `Проверка меню перед сменой` are compact mutually exclusive
  accordions, collapsed by default. Normal mode uses availability switches; selection appears only
  in a separate mass mode. Search, filters, local changes and confirmation summary are draft UX;
  collapse/reopen preserves the draft, while cancel and venue switch clear it. Confirm sends one
  `POST /api/venue/menu/shift-check?venueId=<id>` request.
- A maximum of 500 combined changes is checked for duplicates, existence, venue and item/option
  ownership and expected availability. Item/option updates plus exactly one safe
  `MENU_SHIFT_CHECK_COMPLETED` audit commit atomically; no-op confirmation writes the same audit
  with zero changed counts.
- A stale row rejects the whole batch and the UI offers a current-state refresh; venue switching
  aborts/clears old work. Guest menu and cart preview/add-batch continue to use server-side saved
  availability.
- Existing schema was reused: no migration, stock/history table, media/R2 work, Telegram
  shift-check UI or Staff policy change was added.
- `VenueMenuRoutesTest`, `VenueMenuRepositoryTest`, `GuestVenueMenuRoutesTest`,
  `GuestOrderRoutesTest`, `AuditLogRepositoryTest`, `TelegramBotRouterTableTokenTest`, isolated
  Kotlin compile, ktlint, Mini App build and full deterministic e2e `100/100` passed. GitHub
  Actions were green, staging deploy completed and the manual functional/UX smoke passed.

Latest informational promotion milestone: **Simple Venue Promotions Phase 1**. The later executable
Happy Hours Percent milestone is recorded below as `DONE / STAGING-SMOKE-PASSED`.

Status: `DONE / MVP / STAGING-SMOKE-PASSED`.

Implementation evidence:

- `venue_promotions` already stores title, description, terms, starts/ends, lifecycle status and creator;
- `VenuePromotionRepository` already implements management CRUD/status transitions and guest reads filtered by `ACTIVE`, active period, `PUBLISHED` venue and non-blocked subscription;
- Venue Mini App now provides Owner/Manager list/create/edit/activate/pause/archive UX and hides the section from Staff;
- venue routes derive actor and venue scope from authenticated access, validate text/required period, deny Staff/foreign venue users and do not expose or mutate rule-backed templates through the informational `TEXT_ONLY` API;
- Guest venue detail now renders only current active promotions and exposes no actor/template/audit fields;
- Telegram Bot and both Mini App surfaces use the same repository/state;
- focused backend tests, compile, lint, Mini App build and full Playwright smoke `68/68` passed
  locally; GitHub Actions were green, staging deploy completed and manual smoke passed.

Implemented scope:

- Owner/Manager list, create, edit, activate, pause and archive one venue's informational promotions;
- fields: required title, description, `startsAt` and `endsAt`; optional terms;
- lifecycle: `DRAFT`, `ACTIVE`, `PAUSED`, `ARCHIVED`;
- Guest venue detail shows only currently active promotions for a guest-available venue;
- server-side venue scoping/RBAC and safe validation;
- current schema/repository reused; no migration added.

Explicit out of scope:

- automatic discounts or order-price effects;
- promotion rule engine expansion, promo codes, gifts or loyalty;
- notifications/campaign sends or opt-in management;
- paid placement, boosting or Platform moderation cockpit;
- media/banner upload;
- analytics beyond existing safe conventions;
- Repeat changes or closure of `REPEAT-MANUAL-001`.

Closure evidence:

- Owner/Manager/Staff RBAC, Guest visibility, active-period filtering, unavailable-venue filtering,
  informational-only totals and Telegram/Mini App shared-state parity passed staging smoke.

### Executable Promotions Phase 2

Happy Hours Percent status: **DONE / STAGING-SMOKE-PASSED**.

Gift parity status:
**GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

Promotion lifecycle status audit:
**DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.

Promotion creation audit:
**DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.

Every committed Mini App or Telegram parent creation appends exactly one `VENUE_PROMOTION_CREATED`
inside the existing repository transaction. Mini App initial Happy Hours/Gift rule creation remains
inside `afterInsert` on the same connection; Telegram currently commits only the parent at creation,
so those creation audit rows use `rules=[]`. Actor/source are server-derived, the allowlisted payload
contains only venue/promotion/template, `DRAFT`, source and ordered rule id/version/status rows, and
audit failure rolls back parent and all initial-rule writes. Banner/media persistence is separate.
No migration was added; configuration edit/media/compatibility/broader audit remain open.

Promotion effective state clarity:
**PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**.

Database lifecycle status remains authoritative and is not rewritten automatically. The management
UI derives `DRAFT`, `PAUSED`, `ARCHIVED`, `SCHEDULED`, `ACTIVE_NOW` and `EXPIRED`; manual lifecycle
states take priority. `ACTIVE` is `Запланирована` before start, `Действует сейчас` inside the
inclusive period and `Период завершён` after end. Expired stays in `Текущие`, is Guest-hidden and
pricing-ineligible. `Продлить период` uses the existing update plus authoritative reload. Automatic
pause/archive, worker, system actor and lifecycle audit are absent. Live boundary refresh,
invalid-timestamp fail-safe UI, exact-boundary fixtures and duplicate edit-action policy remain
future.

The authoritative repository transaction locks the parent and rules in their existing order,
applies the current parent/rule synchronization, records the committed old/new snapshot and inserts
exactly one `VENUE_PROMOTION_STATUS_CHANGED` or `VENUE_PROMOTION_ARCHIVED` row before one commit.
Audit insert failure rolls back parent, rules and their lifecycle side effects. Repeated/no-op,
stale, denied, invalid/not-found and rolled-back mutations have no success audit. Actor is stored in
the standard audit actor field; `VENUE_MINI_APP` and `TELEGRAM_BOT` are server-derived sources.
Payload excludes promotion text/configuration, reward/menu names, prices, media, raw requests or
callbacks, Telegram data, `initData`, secrets and unrelated PII. No migration was added.

Promotion Compatibility Policy status: **AUDIT / FUTURE IMPLEMENTATION**.

Gift With Item smoke observed Happy Hours Percentage and Gift With Item being applied together.
This is a product-policy gap, not a confirmed runtime bug in either current slice and not a reason
to change their statuses. The future slice must add one server-owned compatibility policy for all
executable promotions; no Happy Hours-, gift-, cashback-, personal-discount-, loyalty- or
promo-code-specific stacking setting may become a parallel source of truth.

Product UX remains template-based over one Promotion Rules & Rewards Engine:

1. Информационная акция.
2. Скидка по расписанию.
3. Подарок при покупке.
4. Купи X — получи Y / 1+1 — later.
5. Специальная цена — later.
6. Бесплатная option/refill — later.

Happy Hours is a schedule-based percentage preset, not a universal reward form. Schedule,
targets, no-stacking resolution, preview, submit recalculation, ledger and History stay shared.
That bounded no-stacking closure does not define cross-reward compatibility; the shared future
policy must be reward-type-aware and resolve conflicts by compatibility mode, promotion priority
and a deterministic winner/tie-break rule.

Recommended defaults:

- discount vs discount: `EXCLUSIVE`;
- discount vs gift: `STACKABLE`;
- gift vs gift: `EXCLUSIVE`, at most one gift;
- cashback: a separate future policy within the same mechanism after its financial model exists.

The modes are `STACKABLE` (compatible offers apply together), `EXCLUSIVE` (one best offer wins)
and `OVERRIDE` (one promotion suppresses other rewards/discounts in scope). Guest surfaces expose
only the final applied combination. Venue Owner/Manager surfaces must explain the resolved
mode/priority result. Manual discounts must enter the same resolver without weakening actor/RBAC
rules, and later loyalty/cashback/promo codes must reuse it. Acceptance requires deterministic,
server-owned resolution that cannot accidentally add discounts.

Happy Hours closure evidence includes staging creation/activation validation, weekday/time windows,
item/category targets, current price and selected-option delta, cart preview, submit recalculation,
persisted bill/History, no stacking, manual-discount rejection, Owner/Manager/Staff RBAC,
Bot/Mini App parity and `TEXT_ONLY` regression.

Implementation evidence:

- existing `VenuePromotionRuleRepository`, `PromotionRuleEngine`, `OrdersRepository`, promotion
  applications, adjustments and trigger/reward ledger remain the only server-side engine and
  persistence path; existing schema was sufficient and no migration was added;
- the shared server contract returns fixed/selectable/unavailable/selected/skipped offer states
  and accepts only explicit fixed acceptance, allowlist selection or skip. Preview is mutation-free
  and issues an opaque 10-minute HMAC-SHA-256 token derived through the existing server-secret
  pattern with `gift_decision/v1` domain separation, purpose `gift_decision` and audience
  `hookah-order-submit`;
- the signed scope binds authenticated user, venue, table session, tab, canonical cart fingerprint,
  promotion/rule/version and offer type. It carries no trusted price, discount or final amount.
  The deterministic server fingerprint covers order-independent items/quantities, sorted option
  IDs, normalized note/comment and promotion context;
- Venue Owner/Manager have a separate draft-first `Подарок при покупке` preset with item/category
  trigger, fixed or selectable allowlist reward, general period, venue-timezone weekday windows
  and lifecycle. Staff is hidden and receives 403;
- Guest Mini App and Telegram use the same resolver and expose explicit accept/select/skip. Submit
  first verifies signature, purpose/audience, expiry and complete user/venue/session/tab/cart/rule
  scope, then revalidates current venue time, lifecycle, schedule, trigger and required options,
  deterministic winner, allowlist, availability/current price, session/tab authorization and
  idempotency. Legacy unsigned choice/skip input fails closed; stale scope writes nothing and
  returns `Корзина изменилась. Проверьте подарок ещё раз.`;
- at most one gift is applied to the submitted batch, with no cross-batch accumulation or trigger
  quantity multiplication. Gift lines receive neither Happy Hours nor manual discount. While the
  linked reward is active, its paid trigger also rejects manual discount with the common exact
  no-stacking copy; roles cannot bypass the server check. STAFF is denied in the shared repository,
  and Telegram hides the action and rejects direct or stale-dialog attempts;
- submit atomically stores the reward line at original current price, 100% adjustment and final
  zero together with immutable application/rule/version/title/config, selected reward,
  trigger/reward linkage and idempotency facts. Guest History and Venue bill read those persisted
  facts after later rule/menu edits;
- canceling an unavailable trigger atomically cancels its active linked reward; excluding a trigger
  atomically excludes it. The transaction uses deterministic order/batch → trigger → linked reward
  → link/application locks and bill recalculation. Repeat operations and already inactive rewards
  are safe; reward-only cancellation/exclusion never changes the trigger; failure rolls back both
  states and totals. Ledger, link and immutable snapshots remain for audit;
- Guest active bill, Venue bill, History and staff-chat read the same committed lifecycle facts;
  staff-chat does not calculate promotion state;
- Mini App LocalStorage is scoped by authenticated user, venue, table session, tab, canonical cart
  fingerprint and token expiry and clears on account/venue/session/tab/cart changes, including
  initial no-previous-tab restore and same-QR session replacement;
- process-memory pending choice remains Telegram UX state only. Amount-free tagged callbacks bind
  to one offer context; missing, stale or different-session/tab bindings fail safely. Restart-safe
  tests serialize fixed accept, selectable choice and skip and submit through a fresh
  resolver/router plus the production repository path.

Schema verdict: existing `promotion_rule_rewards`, `promotion_rule_reward_options`,
`order_promotion_applications`, item adjustments and `order_promotion_reward_items` were
sufficient; no migration or compatibility table was needed.

Local acceptance evidence: `PromotionRuleEngine` 37/0, `VenuePromotionRepository` 27/0,
`VenuePromotionRoutes` 9/0, signed-token scope 6/0, `GuestOrderRoutes` 51/0,
`VenueOrderRoutes` + `VenueOrdersRepository` 54/0, `GuestVisitRoutes` 6/0,
`VisitRepository` final 16/0 after one detected and fixed initial failure,
`TelegramBotRouter` 503/0 and real PostgreSQL concurrency 6/0 with
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`, all with skipped=0. `ktlintCheck`, `compileKotlin`, Mini App
production build and deterministic Playwright smoke `83/83` passed. GitHub Actions, independent
review and staging cross-surface smoke remain required; do not label this slice
`STAGING-SMOKE-PASSED`.

Exact out of scope: BOGO / X+Y, second-item-free, free option/refill, fixed discount, special
price, cross-visit or cross-batch accumulation, loyalty, promo codes, notifications, paid
placement, payments/Stars/crypto, arbitrary rule builder, Promotion Compatibility Policy
implementation and changes to `REPEAT-MANUAL-001`.

Why not reopen full bill / display order number in Mini App:
- Guest Bill / Display-Number / Full-Bill Parity is already CLOSED / staging smoke passed in current roadmap and Venue Operations docs;
- old audit rows that still say full bill/display/discounts/exclusions are partial are historical notes, not current backlog;
- keep bill/display-number in regression and reopen only on concrete regression evidence.

Implemented Repeat boundaries:
- `POST /api/guest/visits/{visitId}/repeat-plan` accepts only `tableSessionId`, `tabId` and optional `orderId`; authenticated user identity and current prices remain server-owned;
- current Telegram and Mini App flows share `RepeatOrderResolver`, including item/option availability and conservative whole-line skip rules;
- Mini App History keeps multiple orders explicit, previews eligible/skipped lines and adds only confirmed eligible item/option/note identities to the local cart;
- existing preview/add-batch routes remain the later order-creation boundary and revalidate active `tableSessionId`, tab membership, venue availability, current item/option validity and server-owned pricing;
- the old active-order-by-table risk and H2/PostgreSQL active-order/personal-tab uniqueness fidelity are closed; keep them in regression instead of reopening them.

Local acceptance evidence for Repeat as Template Phase 1:
- only the current user's completed visit/order can become a template;
- without same-venue active table context and selected authorized tab, no cart/order mutation occurs;
- current unavailable/stopped items or invalid options are skipped with human copy; current prices are shown;
- eligible lines are added to the current cart, and order creation remains behind existing preview/add-batch confirmation;
- current order/session/tab, Favorites, History and Post-Visit Feedback privacy/RBAC regressions remain green;
- focused backend tests, compile, ktlint, Mini App build and full browser e2e `64/64` passed locally. Environment-dependent manual scenarios remain deferred and are not passed.

Repeat remains unchanged by the selected block. Persistent repeat-template storage, favorite menu items/options, frequent-item aggregation, recommendations, substitutions, notifications, loyalty, preorder and automatic order creation remain out of scope.

Remaining billing follow-ups:
- real acquiring provider and Telegram Stars remain future milestones;
- invoice void/reissue for courtesy conflicts with already-open future invoices remains unimplemented;
- billing-created versus manual `SUSPENDED_BY_PLATFORM` distinction remains needed before broader auto-reactivation;
- automatic recurring card payments are not implemented.

Historical booking block: M7c adaptive transactional reminders.

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
