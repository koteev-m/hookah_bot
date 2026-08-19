# Product + Telegram AI Bots Roadmap

Дата обновления: 2026-08-18.

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

Текущий фокус перед пилотом: ранее принятые M1-M9b.3 блоки закрыли свои тогдашние product P0/P1; это не утверждение об отсутствии текущих P0/P1 в master registry. Их staging smoke, CI release validation, deploy/runbook hardening and minimal Guest Mini App browser smoke зелёные. M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed: standard deploy remains supported, opt-in ControlMaster deploy is validated as a release-reliability workaround, and the exact SSH/network root cause remains unconfirmed. M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 range/rejection-copy improvements, M9b.2 exception save/list UX and M9b.3 date-range editing are CLOSED / staging smoke passed. Platform Owner Invite / ADMIN Semantics Hardening, Platform Venue OWNER Revocation, H2/PostgreSQL active-order + personal-tab uniqueness fidelity, Mini App mutation / operational verification closure pack, Staff Call Lifecycle ACK/DONE audit hardening, Staff-call guest-visible CANCELLED finishing patch, Guest Table Context UX Cleanup / Feature-gated Extension Module, Guest Table Session Exit / Expiry UX, Guest Bill / Display-Number / Full-Bill Parity, Guest Bill Request / Payment Method UX, Staff Chat Noise Reduction / Table Activity Card, hookah preparation placeholder polish, Platform Billing Cockpit / Owner Payment UX, Platform Billing Renewal / Advance Invoice / Courtesy Days, Staff/Manager invite deep-link sharing polish, Guest Communication UX / Support Tickets MVP, Booking Arrival Guard / Staff-Chat Booking Buttons, Guest History Foundation, Post-Visit Feedback MVP, Guest Favorites Phase 1 and Catalog Search and Filter Phase 1 are CLOSED. Guest Favorites Phase 1 is **DONE / MVP / STAGING-SMOKE-PASSED** for venue favorites only, including Telegram Profile/Catalog parity and source-aware Back navigation. Catalog Search and Filter Phase 1 is **CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**. Shared initial menu bootstrap is **VENUE MENU ONBOARDING / SHARED INITIAL MENU BOOTSTRAP / DONE / MVP / STAGING-SMOKE-PASSED**. **PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT** is **DONE / MVP / STAGING-SMOKE-PASSED** for release HEAD `e35def99ea8429462e5fdaaeee914f57da72e775`, based on user-confirmed green Actions, deploy, consolidated smoke and cleanup. Do not reopen closed slices without new smoke or code evidence. The next implementation epic is not selected; Section 13 contains the verified master remaining-work inventory and comparative shortlist for user decision. Scope не расширяем в сторону Telegram-native AI surfaces до готовности Mini App и public-safe tools.

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
- Venue Mini App booking card opens a persisted booking conversation thread: venue messages, Guest Bot replies and Guest Mini App replies share one source of truth; staff chat remains a notification mirror. M4A staging smoke passed after UX polish. The current review-ready slice is **BOOKING CONVERSATION UX / DISTINCT LABELS, INBOX AND UNREAD DISCOVERABILITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. It renames the Venue inbox to `Переписки`, keeps `BOOKING_THREAD` plus `VENUE_CHAT` separate from `Поддержка`, adds authoritative booking labels and uses the actor/thread `last_read_message_id` cursor as sole unread authority; `last_read_at` is metadata only. Independent review, green Actions, PostgreSQL V126/H2 V127 rollout and a fresh staging smoke are still required; the earlier integrity release is historical and remains separately review-required, not done.
- Guest Communication UX / Support Tickets MVP is CLOSED after smoke: canonical model is `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET`, `STAFF_CALL` in `docs/COMMUNICATION_MODEL.md`; Guest nav is `Чаты` / `Помощь`; catalog and venue detail `Задать вопрос` opens/reuses `VENUE_CHAT`; booking `Открыть переписку` remains `BOOKING_CHAT`; Support tickets are separated through `SUPPORT_TICKET`; Platform sees support tickets but not ordinary venue chats; Staff sees neither support tickets nor ordinary venue chats; table context keeps `Вызвать персонал` as the live operational flow; support/venue chat creation and replies do not post to staff-chat and guest create/reply routes are rate-limited.
- Platform cockpit docs are current in `docs/PLATFORM_COCKPIT.md`: Platform Mode is the cockpit for venues, onboarding, lifecycle, owner/access, billing/subscriptions/invoices, Support Center and analytics/audit. Manual billing, support-ticket MVP and the bounded onboarding/ownership cockpit are release-closed; placements/analytics, real acquiring/Stars, recurring payments and advanced support remain future/partial.
- Growth/retention docs are current in `docs/GROWTH_RETENTION.md`: Guest History, Post-Visit Feedback, venue-only Favorites, Simple Promotions, Happy Hours and the listed promotion audit/UX slices are closed. Repeat Phase 1 is locally validated with deferred manual smoke; Gift still needs review/CI/staging closure. Favorite-item backend/API/Telegram, Nth Hookah loyalty and manual placement foundations exist, so they are not blank future features; their Mini App/product/release outcomes are cataloged in Section 13. Persistent templates, consent/campaigns, advanced recommendations, promo codes, referrals, paid-ad productization and broader analytics remain open/deferred.
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
- Menu/options/stop-list docs are current in `docs/MENU_OPTIONS_STOPLIST.md`: structured menu terms, option/modifier snapshots, media/PDF boundaries, featured/top-list, stop-list, shift check, availability validation and menu permissions are `SPEC UPDATED`. Selected-option parity and the previously bounded menu audit slices remain smoke-closed. The existing category create/rename/type/reorder and item rename/price-currency/type/category-move/reorder closure is **VENUE MENU MANAGEMENT / EXISTING-CONTRACT AUDIT AND TRANSACTION CLOSURE / DONE / MVP / STAGING-SMOKE-PASSED**; broader constructor/media/top-list and non-menu dangerous-action coverage remain partial/future.
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
- Booking Arrival Guard / Staff-Chat Booking Buttons are CLOSED / staging smoke passed: `Гость пришёл` / `Не пришёл` is shown and accepted only for confirmed bookings; pending/changed/terminal statuses have no arrival buttons; staff-chat booking lifecycle notifications are state-aware; stale/no-permission callbacks stay safe. The new locally implemented exception is one fact-only, message-text-free radar alert for a committed Guest `BOOKING_THREAD` message in the existing linked venue staff-chat; it changes no booking lifecycle authority and awaits review/Actions/staging smoke.
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
- growth/retention remains `SPEC UPDATED / PARTIAL-FUTURE` overall in `docs/GROWTH_RETENTION.md`; History, Feedback, venue Favorites, Simple Promotions and Happy Hours are closed, Repeat has deferred manual smoke, and Gift remains release-open. Favorite-item and Nth Hookah loyalty foundations exist but their broader Mini App/product/release outcomes are not done.

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

- remaining guest growth/retention from `docs/GROWTH_RETENTION.md`: Repeat Phase 1 has deferred manual smoke; Gift awaits review/CI/staging; favorite-item Mini App/options, loyalty productization audit, recommendations, persistent templates, consent and campaigns remain. Venue favorites, History, Feedback, Simple Promotions and Happy Hours stay in regression;
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
- Venue chat/support split remains strict: Owner/Manager `Переписки` handles `BOOKING_CHAT` / `VENUE_CHAT`; `Поддержка` handles own-venue `SUPPORT_TICKET`; `Передать платформе` applies to support tickets only; Staff remains operational and cannot handle either conversation queue.
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
- Booking Arrival Guard / Staff-Chat Booking Buttons: **CLOSED / staging smoke passed**. Arrival terminal actions are visible/accepted only from `CONFIRMED`; `PENDING`, `CHANGED` and terminal statuses do not show or accept seat/no-show; staff-chat booking lifecycle notifications are state-aware and stale/no-permission callbacks answer safely. Full `BOOKING_CHAT` text remains forbidden; the separate discoverability slice locally adds only one fact-only new-Guest-message radar alert and is not staging-closed.
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
Promotion configuration edit audit, QR rotate,
force-close/session audit, tab reopen, analytics export, the Promotion Compatibility Policy and a
broader audit viewer remain future. The overall dangerous-action audit therefore remains partial.
The menu item, empty-category and option hard-delete slices are release-closed only for their
bounded MVPs. They do not by themselves close option create/price/name/type/update, availability,
media or the broader menu audit; option create is separately release-closed.

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
  staging-smoke-passed item availability, item/empty-category/option hard-delete, option-create and
  item-create audits in regression. `IMPLEMENT_MENU_MANAGEMENT_CLOSURE_EPIC_NEXT` is now locally
  implemented for the existing category create/rename/type/reorder and item
  rename/price-currency/type/category-move/reorder writers only. It remains review/Actions/staging
  gated, adds no migration or product semantic change, and keeps broader constructor/media/top-list
  separate;
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

**Decision state: MASTER REMAINING WORK INVENTORY PREPARED / USER DECISION REQUIRED.** No active
implementation Goal or approved `NEXT` epic is recorded. The release-closed history below is retained
for traceability; Section 13 is the only current comparative decision surface.

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

Latest release-closed audit block: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE /
MVP / STAGING-SMOKE-PASSED**. Current release HEAD `0489a2f` equals `origin/main`; the user confirmed
fully green GitHub Actions, staging deploy and bounded smoke. This closes only the authenticated Venue
Mini App existing-option price mutation; it does not close the broader Menu or Dangerous Action Audit
programs.

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
  name+price writes one independent audit of each family. A real availability delta now adds its
  independent availability audit through the current bounded block. No idempotency token is introduced.
- Integer minor units, zero delta, existing validation/request/response/UI parsing, currency and
  rounding are unchanged. Checkout reloads the current available DB option/delta; client/stale-cart
  price is not authority. New orders snapshot the current delta and historical
  `price_delta_minor_snapshot` rows remain immutable.
- Deterministic Testcontainers PostgreSQL coverage is nine tests, uses independent connections and
  confirmed blocking without arbitrary sleep, and covers price/price, price/rename, direct delete
  and atomic normalization. Its release XML was `9/0/0/0`; the current availability extensions raise
  the shared class and CI minimum to 20. Focused repository, route,
  Guest order/history and Guest visit selectors, compile, ktlint, Mini App build and Playwright
  `152/152` pass locally. No workflow or migration was added.

User-confirmed bounded smoke covers price-only success; one price audit and no rename audit; no extra
audit on same-price save; atomic name+price with one rename and one price audit; authoritative current
server pricing or safe reconfirmation at order submit; intact working menu/data; and routine cleanup.
Historical order-snapshot preservation remains confirmed automated coverage only, not a separate
staging scenario. Option create, item price/update, Telegram price management,
membership-recheck hardening, promotion work, viewers and media/storage remain outside this slice.

Implementation contract: **IMPLEMENT_MENU_OPTION_AVAILABILITY_AUDIT_NEXT**, fulfilled and
release-closed.

Recent release-closed audit block: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT /
DONE / MVP / STAGING-SMOKE-PASSED**.

Implementation evidence:

- Production `menu_item_options.is_available` writers were inventoried: direct Mini App, compound
  Mini App, four Telegram stop-list repository call sites and Shift Check updates; option create and
  normalization only insert defaults/preserve rows. No internal/legacy/direct SQL production writer
  exists outside `VenueMenuRepository`.
- `setOptionAvailability` has no unaudited overload. It accepts authenticated actor/server source and
  owns item lock → ascending option locks → DB-current reread → real-delta update → same-connection
  audit → one commit. Compound PATCH appends independent rename, price and availability audits in its
  existing transaction. Audit failure restores name, price, availability, `updated_at` and all audits.
- One real committed individual delta writes `MENU_OPTION_AVAILABILITY_CHANGED`, entity
  `menu_item_option`, option id. Payload keys are exactly `venueId`, `itemId`, `optionId`,
  `oldIsAvailable`, `newIsAvailable`, `source`. Actor is only session subject/current callback user;
  source is server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`. Request/callback/Telegram identity,
  names, prices, canonical/promotion/order/cart/media/secret/PII data are excluded.
- Direct Owner/Manager/Staff keep `MENU_AVAILABILITY_MANAGE`; compound Owner/Manager keeps
  `MENU_MANAGE`; Shift Check permission/lifecycle is unchanged. Same-state/repeat, name-only,
  price-only, denial, foreign/not-found, collision, SQL/audit failure and rollback write zero
  availability audit. No idempotency token was added.
- Shift Check never invokes the individual audit writer and retains only one
  `MENU_SHIFT_CHECK_COMPLETED` on success, including mixed/no-op behavior. Stale/failure writes no
  success audit. Existing payload/cardinality is unchanged.
- Testcontainers PostgreSQL uses production migrations/repositories, independent connections,
  deterministic latches and an observed blocking edge without arbitrary sleep. It covers
  direct/direct, direct/compound, both direct/Shift Check orders, direct/delete and
  direct/normalization; the shared extended XML is `20/0/0/0` and the existing CI minimum is 20.
- Disabled new-order/stale-cart rejection, re-enable behavior and immutable historical snapshots
  remain current regression behavior. No option-create/item audit, price/name/canonical/promotion,
  order-schema, permission, media/R2 or migration change was added. **NO_MIGRATION_EXPECTED**.

Recent release-closed audit block: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT /
DONE / MVP / STAGING-SMOKE-PASSED**.

Implementation evidence:

- Production `menu_items.is_available` writers/callers were inventoried. Individual authenticated
  writers are Mini App direct availability, Mini App compound item PATCH and all Telegram
  Owner/Manager/Staff detail/root stop-list callbacks. Shift Check owns a separate batch writer;
  create supplies initial state, delete removes the row and no internal/system/legacy runtime or
  second direct SQL writer exists outside `VenueMenuRepository`.
- `setItemAvailability` has no unaudited overload. Direct and compound paths use one connection with
  `autoCommit=false`, authoritative item/venue/category scope, item `FOR UPDATE`, DB-current reread,
  real-delta comparison, conditional update, same-connection audit, result reread and one commit.
  Compound item type and other current fields now share that transaction without adding metadata
  audit families. Audit failure restores fields, availability, `updated_at` and every audit row.
- One real committed individual delta writes `MENU_ITEM_AVAILABILITY_CHANGED`, entity `menu_item`,
  item id. Payload keys are exactly `venueId`, `itemId`, `oldIsAvailable`, `newIsAvailable`,
  `source`. Actor is only the authenticated Mini App session subject/current Telegram callback user;
  source is server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`.
- Item/category names, prices/currency, description/type, option/promotion/cart/order contents, raw
  request/initData, Telegram identity/update/callback, media, secrets and PII are excluded.
  Same-state/repeat, metadata-only, denial, foreign/not-found, stale/collision, SQL/audit failure and
  rollback write zero audit; direct no-op preserves `updated_at`.
- Direct Owner/Manager/Staff retain `MENU_AVAILABILITY_MANAGE`; compound item PATCH remains current
  Owner/Manager `MENU_MANAGE`; Shift Check remains Owner/Manager-only. Staff receives no compound
  price/name/type authority.
- Shift Check never invokes the individual helper. Common, individual, mixed and no-op success retain
  exactly one existing `MENU_SHIFT_CHECK_COMPLETED` and zero per-item availability audits;
  stale/failure writes no success audit. Existing payload and RBAC are unchanged.
- Guest disabled-item `ITEM / UNAVAILABLE`, read-only preview, zero-write stale submit, re-enable
  recovery, neighboring cart lines, payload-bound idempotency and immutable historical name/price
  snapshots remain green. Availability changes do not rewrite order snapshots.
- Testcontainers PostgreSQL uses production migrations/repositories, independent connections,
  deterministic latches and an observed real blocking edge without arbitrary sleep. Item coverage is
  direct/direct, direct/compound, both direct/Shift Check orders and delete/direct; Guest
  availability-vs-submit remains green. Shared menu XML/CI minimum is `20/0/0/0`; Guest concurrency
  XML is `9/0/0/0`. No new workflow was added and missing/zero/skipped/failure/error remain fatal.
- Item price/name/type/description/category/currency/media, option availability/create, promotion,
  cart hardening, membership linearization and broader Menu/Dangerous Action Audit remain outside
  this slice. **NO_MIGRATION_EXPECTED**.

The user confirmed green Actions for current release HEAD `db08916`, staging deploy and the bounded
Mini App/Telegram/Shift Check/Guest smoke. GitHub CLI did not independently verify Actions because
its active token is invalid. This closure does not cover failure injection or raw-SQL staging cases.

Recent release-closed audit block: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT /
DONE / MVP / STAGING-SMOKE-PASSED**.

- One private `VenueMenuRepository.insertOption` SQL writer serves exactly six authenticated flows:
  Mini App direct/bulk and Telegram canonical direct, custom dialog, bulk and normalization. No
  internal/system/legacy production writer remains unaudited.
- Owner/Manager keep current own-venue create authority; Staff, foreign and unaffiliated callers are
  denied. Actor is the Mini App session subject/current Telegram user and source is fixed server-side
  to `VENUE_MINI_APP` / `TELEGRAM_BOT`.
- Direct create, one bulk operation and normalization each own one repository transaction. Item then
  deterministic option locks, DB-current planning/collision checks, physical inserts and one
  `MENU_OPTION_CREATED` per inserted row commit together. Normalization retains its existing delete
  audits; any create/delete/audit failure restores the whole option and audit snapshot. No-op and
  concurrent loser write zero create audit.
- Action/entity are `MENU_OPTION_CREATED` / `menu_item_option` / option id; payload keys are exactly
  `venueId`, `itemId`, `optionId`, `source`. Content, price/availability, raw request/Telegram data,
  promotion/cart/order/media, secrets and PII are excluded.
- Automated/local/CI contract evidence is repository `41/0/0/0`, routes `37/0/0/0`, Telegram
  `538/0/0/0`, route/security `1137`, PostgreSQL concurrency `26/0/0/0`, compile, ktlint, Mini App
  build and full Playwright `169/169`. This is historical option-create evidence; the current shared
  CI PostgreSQL minimum is 44 after shared bootstrap closure coverage. Full direct/bulk/
  normalization rollback, canonical uniqueness and deterministic locking are automated evidence.
  **NO_MIGRATION_EXPECTED**; no new workflow. For current release HEAD `0e592ff`, the user confirmed
  green Actions, staging deploy and the bounded 18-scenario smoke recorded in the QA strategy.
  Local GitHub CLI did not independently verify Actions because its active token is invalid. The
  broader Menu/Dangerous Action Audit stays `PARTIAL`.

### Release-closed Menu Management existing-contract closure

Implementation contract: **IMPLEMENT_MENU_MANAGEMENT_CLOSURE_EPIC_NEXT**.

Verdict: **VENUE MENU MANAGEMENT / EXISTING-CONTRACT AUDIT AND TRANSACTION CLOSURE / DONE / MVP /
STAGING-SMOKE-PASSED**.

- Inventory found one production SQL owner, `VenueMenuRepository`, and no extra authenticated
  writer. The bounded families are category create/rename/type/reorder and item
  rename/price-currency/type/category-move/reorder. Existing delete, create, availability, option,
  normalization, Shift Check, cart/idempotency and Menu UX release contracts remain unchanged.
- The nine new actions are `MENU_CATEGORY_CREATED`, `MENU_CATEGORY_RENAMED`,
  `MENU_CATEGORY_TYPE_CHANGED`, `MENU_CATEGORIES_REORDERED`, `MENU_ITEM_RENAMED`,
  `MENU_ITEM_PRICE_CHANGED`, `MENU_ITEM_TYPE_CHANGED`, `MENU_ITEM_CATEGORY_MOVED` and
  `MENU_ITEMS_REORDERED`. Actor is the authenticated session/current Telegram user and source is
  fixed server-side. Owner/Manager own-venue access remains allowed; Staff, foreign, unaffiliated
  and Platform-only access remains denied before entity facts.
- Each operation now uses one repository connection/transaction with deterministic category/item
  locks, a DB-current reread, exact deltas, mutation, same-connection audit rows, authoritative
  result reread and one commit. Exact no-op preserves timestamps and writes zero family audit;
  failure rolls back every business field, order/timestamp and audit. Compound item PATCH can write
  one truthful audit for each of rename, price/currency, type, move and existing availability.
- Telegram default-category seeding is atomic, writes one create audit for each physically missing
  row and zero on repeat. Reorders validate the full authoritative set and store only count plus
  deterministic old/new order hashes. Audit payloads exclude names, raw requests/initData/callbacks,
  media, option/promotion/cart/order contents, secrets, PII and full id arrays.
- Existing integer-minor price/currency rules, Guest current-value reload, immutable historical
  order/bill snapshots, promotion calculations, stale-cart/idempotency, API and UX contracts are
  preserved. No schema change is required: **NO_MIGRATION_EXPECTED**.
- Local evidence is repository `51/0/0/0`, Mini App routes `43/0/0/0`, Telegram `549/0/0/0`, Guest
  order/history `61/0/0/0`, exact route/security `1164/0/0/0`, menu PostgreSQL `40/0/0/0` and exact
  five-suite PostgreSQL `73/0/0/0`. The real matrix uses independent PIDs, deterministic barriers
  and observed locks. Compile, ktlint, Mini App build and full Playwright `169/169` passed. The user
  confirmed green Actions, staging deploy and consolidated Menu Management smoke. The overall
  product and broader Dangerous Action Audit remain `PARTIAL`.

### Release-closed bounded block: shared initial menu bootstrap

Implementation contract: **IMPLEMENT_SHARED_INITIAL_MENU_BOOTSTRAP_NEXT**.

Verdict: **VENUE MENU ONBOARDING / SHARED INITIAL MENU BOOTSTRAP / DONE / MVP /
STAGING-SMOKE-PASSED**.

- Approval/linking still grants access without seeding and the authenticated menu GET remains a pure
  read. Owner/Manager Mini App now calls an explicit bootstrap mutation before one authoritative GET;
  the existing Telegram `🍽 Заказное меню` root imports the same shared seed source.
- Exact defaults are `Кальянное меню`, `Напитки`, `Кухня`, in that order and all explicitly
  `MenuSemanticType.OTHER`. `VenueMenuRepository.createMissingCategories` keeps the existing
  category-order lock/transaction/audit contract, appends only normalized-name-missing rows and
  preserves every existing row, item and option. Complete/repeat is a row/timestamp/audit no-op;
  any insert/audit failure rolls the whole bootstrap back.
- Mini App actor is the authenticated session subject and source is `VENUE_MINI_APP`; Telegram actor
  is the current authenticated user and source is `TELEGRAM_BOT`. Owner/Manager own venue are
  allowed; Staff, foreign, unaffiliated and Platform-only actors are denied. Payload remains only
  `venueId`, `categoryId`, `source`; no names/types/raw requests/Telegram data/PII enter audit.
- Local evidence is repository `54`, routes `46`, Telegram `551`, onboarding/connection `18`, menu
  PostgreSQL `44`, exact route/security `1190`, exact five-suite PostgreSQL `77` with vector
  `8 / 14 / 2 / 44 / 9`, compile/ktlint/build and full Playwright `176/176`, all green with no
  accepted skip/failure/error. Seven new deterministic E2E scenarios cover empty, repeat, partial,
  retry, venue/account switch and Staff no-mutation behavior.
- No migration, SYSTEM actor, approval redesign, default-type change, new onboarding engine or UI
  redesign was required. The user confirmed green GitHub Actions for the release HEAD, staging
  deploy, Mini App-first and Telegram-first parity, repeat with zero duplicate rows/audits,
  partial/custom preservation, Staff denial, approval remaining non-seeding and cleanup. This does
  not close broader onboarding, menu constructor/media/top-list or the overall product.

### Release-closed epic: Platform & Venue Onboarding / Ownership Cockpit

Implementation contract: `IMPLEMENT_PLATFORM_ONBOARDING_OWNERSHIP_COCKPIT_NEXT`.

Verdict: **DONE / MVP / STAGING-SMOKE-PASSED** for release HEAD
`e35def99ea8429462e5fdaaeee914f57da72e775`.

The bounded epic was viable because the request, venue, membership, user and commercial account
repositories contained the required facts; owner aggregation needed no primary-owner redesign;
Telegram and both Mini Apps could call one extracted backend contract; and billing, support,
analytics, media/R2 and menu behavior remained outside scope. The implementation used one release
boundary and one consolidated staging smoke.

#### 1. Pre-implementation baseline retained for traceability

- Historically, an authenticated Telegram user submitted a first or additional venue through
  `🤝 Добавить свою кальянную`. `venue_connection_requests` stores applicant user id, venue name,
  city, contact, optional comment, status, created time, optional linked venue and commercial terms.
  The actual statuses are `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`; there is no `NEEDS_INFO`.
- Before and after that Telegram dialog, `findActiveUnlinkedByUser` prevented a normal sequential
  repeat: an existing `PENDING` or `APPROVED`-unlinked request was shown. Pending could be edited or
  cancelled; approved-unlinked could be closed. The check and insert were separate, so simultaneous
  submit was not database-serialized in the pre-implementation baseline.
- Venue Mini App exposed no request endpoints or `Мои заведения` screen. `/api/venue/me` already
  returns all active memberships; `venueApp.ts` builds the current selector from that server list,
  shows it for multiple venues and persists `venueId`.
- Platform approval only changed request status and remained non-seeding. The former Telegram
  Platform flow separately records commercial terms, creates a new `DRAFT` venue, assigns the
  applicant as OWNER, applies subscription terms and links the approved request. There is no current
  UI action to choose and link an arbitrary existing venue. The steps are sequential and later
  failure can leave a DRAFT requiring manual recovery.
- After membership/linking, the venue appeared on the next `/api/venue/me` load and in the existing
  selector. The shared initial menu bootstrap remains explicit first-management-entry behavior and
  is independent of the first surface.
- Platform Mini App could `GET/POST /api/platform/venues`, open venue detail, search
  `/api/platform/users`, assign/invite/revoke active OWNER memberships and run current venue/
  subscription actions. Backend list summaries include city, owner count and subscription summary;
  the pre-implementation TypeScript/list UI omitted city and owner names and was venue-centric.
- Platform routes were venues, create, informational onboarding, placements, support and
  analytics. There was no connection-request API/UI, owner-centric list or owner drill-down. Owner
  identity was visible only in venue detail. Telegram offered requests and venues plus displayed
  `Клиенты / Лимиты`; handled `Владельцы` was an alias for that commercial account flow, not a full
  owners workspace.

Resolved production scope:

- Authenticated Telegram users can submit a first or additional venue regardless of current OWNER
  membership. Venue Mini App is the additional-venue entry for an active operational Owner only;
  Manager, Staff, foreign and Platform-only identities are denied there. The adapter selects the
  entry policy server-side, and applicant/source are never client-controlled.
- Every first or additional venue uses the shared application flow. `owner_quota_create_start` and
  persisted legacy direct-create dialog states are compatibility aliases with zero direct writes.
- Quota/account/limit-request behavior stays commercial-only: it never gates submit and is enforced
  only under account lock at Platform create/link.
- Submit creates only the request. Atomic create/link owns DRAFT, OWNER membership, commercial
  settings, request link and safe audits; rollback leaves no partial state and retry returns the link.
- A first applicant without a prior OWNER membership receives the former connection-request
  commercial initialization, including the existing default account limit of one; no new quota or
  account policy is introduced.
- Existing quota-pilot venues/memberships remain untouched. No migration or history rewrite exists.

#### 2. Pre-implementation user-visible gaps

- Venue Owner had to leave the Mini App and discover a Telegram command to add another venue, could
  not see all request history/state next to memberships, and received no Mini App explanation for an
  approved-but-not-yet-linked request.
- Platform Owner could not process requests in the Platform source-of-truth UI, scan owner names or
  city from the venue list, or start from an owner to inspect their venue portfolio.
- Multiple operational owners were valid but the list presentation reduced them to a count;
  commercial account language could be mistaken for primary operational ownership.
- Telegram contained the onboarding orchestration, so adding UI calls directly to repositories
  would have created a second engine with divergent RBAC, retry, partial-failure and audit behavior.

#### 3. Implemented bounded contract

- Venue Mini App adds `Мои заведения` with current venue cards, an action to select/open the existing
  venue context, `Добавить заведение`, own request list/detail and the current submission fields only:
  venue name, city, contact and optional comment. Preserve pending edit/cancel and approved-unlinked
  close where the shared contract exposes them.
- Show only `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`. Copy distinguishes approval from completed
  linking. After linking, refresh authoritative access and show the venue card/selector option.
- Under the applicant lock, submit compares one production canonical tuple: NFKC-normalized,
  trimmed/collapsed/lowercased venue name, city, contact and optional comment. Exact retry among all
  `PENDING` and `APPROVED`-unlinked rows returns the matching authoritative request without insert or
  audit; a distinct tuple creates another `PENDING`, and `REJECTED`/`CANCELLED` do not block it.
  Simultaneous exact/distinct submissions are serialized server-side; the UI never chooses applicant id.
- Platform Mini App top-level onboarding/ownership workspace has `Заявки`, `Кальянные`, `Владельцы`.
  Existing billing, support, placements and analytics remain reachable but unchanged.
- `Заявки` provides actionable/pending list, safe detail, approve/reject, commercial terms and the
  current create-new-DRAFT-and-link action. Do not invent an existing-venue chooser. Both Mini App
  and Telegram call one extracted orchestration service with common state, recovery/idempotency and
  audit behavior. Approval/linking writes zero menu categories.
- `Кальянные` shows name, city, current lifecycle status, existing subscription/onboarding summary
  when available, all active owner names/count and opens the existing venue detail.
- `Владельцы` shows platform-safe identity, venue count, venue-status counts and linked venues;
  supports search/filter and owner detail → venue list → existing venue detail.

#### 4. API / repository / UI inventory

Backend reuse and extension:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueConnectionRequestRepository.kt`:
  add scoped list/detail and transactional create-or-return-active/CAS primitives; keep one SQL owner.
- Extract current request decision, commercial terms and create-new-DRAFT-and-link orchestration from
  `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt` into one
  narrowly scoped onboarding service. Telegram becomes an adapter and retains current copy/callbacks.
- Add authenticated request routes through
  `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueRoutes.kt` and Platform
  routes through `PlatformRoutes.kt`/`PlatformVenueRoutes.kt`; wire dependencies in `Application.kt`.
- Reuse/extend `PlatformVenueRepository.kt`, `PlatformVenueMemberRepository.kt`,
  `PlatformUserRepository.kt` and `VenueOwnerAccountRepository.kt`. Owners aggregation is active
  `users + venue_members(role=OWNER) + venues`, not a new table.

Mini App reuse and extension:

- Venue: `miniapp/src/screens/venueApp.ts`, `miniapp/src/shared/api/venueApi.ts` and existing Venue
  DTO definitions; add focused owner venues/requests screen modules only if the current renderer
  would otherwise become less cohesive.
- Platform: `platformApp.ts`, `platformVenuesList.ts`, `platformVenueDetail.ts`,
  `platformCockpitSections.ts`, `shared/api/platformApi.ts` and `shared/api/platformDtos.ts`; add
  request list/detail and owner list/detail screen modules.
- `PlatformVenueSummaryDto` must include existing backend city and owner summary fields instead of
  silently dropping them.

#### 5. RBAC and privacy contract

- Authenticated Telegram applicants read/mutate only their own requests, including before the first
  OWNER membership exists. Venue Mini App additionally requires active operational OWNER before the
  same subject-bound request operations. Client owner/applicant ids are ignored or rejected; Manager
  and Staff have no Venue Mini App application authority even when UI controls are hidden.
- Exact Platform Owner sees all venues, owners and requests and performs decisions. Platform role
  does not grant ordinary Venue-route authority. Actor and source are server-derived on both Mini App
  and Telegram.
- Request owner and linked venue are resolved from server state. Applicant contact/comment is
  visible only to the same applicant and Platform Owner. Owner list uses current safe display name,
  username and necessary opaque/internal id; no raw Telegram payload/initData, phone/private provider
  fields, secrets or unrelated PII.
- Approve/reject/commercial/create-link and membership actions are explicit and audit-aware. Shared
  service owns truthful bounded audit; adapters write no second audit. Failure/denial/no-op emits no
  false success audit.

#### 6. Multi-owner semantics

Active `venue_members(role=OWNER)` is operational authority. Show every owner and count each venue
once per owner portfolio. One user may own multiple venues; one venue may have multiple owners.
`venues.owner_account_id` and `venue_owner_accounts.primary_owner_user_id` are one commercial quota
account relationship, not primary operational membership. Existing last-owner revoke protection and
commercial quota/account mismatch behavior remain authoritative. Do not choose a primary owner by
minimum user id or expose such a label.

Stable P2 finding `OWNERSHIP-MODEL-001`:

- Area: operational membership versus commercial owner-account semantics.
- Evidence: runtime access/listing reads active OWNER memberships, while venues have one optional
  owner account whose `primary_owner_user_id` drives quota. Telegram quota display currently selects
  the minimum OWNER user id as a lookup heuristic; direct assignment can return
  `OwnerAccountMismatch` when the venue is commercially linked elsewhere.
- Risk: a new owners workspace could invent a primary operational owner, hide valid co-owners or
  mutate the wrong commercial account.
- Minimal fix: aggregate/present all OWNER memberships; label account/quota facts separately if they
  are shown; never use minimum user id as authority. Preserve current account validation and add
  multi-owner/multi-venue tests.
- Required trigger/release boundary: mandatory in this Ownership Cockpit epic.
- Status: `DONE`; release closure for the epic still requires green Actions, staging deploy and
  the consolidated smoke.

#### 7. Explicit out of scope

No billing redesign, support workflow, analytics dashboard, media/R2, menu/bootstrap change,
primary-owner redesign, commercial account transfer/merge, new venue lifecycle, `NEEDS_INFO`, paid
placements, provider/Stars work or broad Telegram navigation rewrite. Existing non-onboarding
Platform features remain behaviorally unchanged.

#### 8. Migration verdict

**NO_MIGRATION**. Current request rows/states/link, users, venue memberships, venues and owner
accounts are sufficient. Applicant-row locking plus exact canonical comparison across all retryable
rows has been verified by same/distinct PostgreSQL cross-surface submit and create/link tests; no uniqueness/state migration or
`NEEDS_OTHER_PLATFORM_PREREQUISITE` is required. Adding `NEEDS_INFO` or primary-owner membership
remains a separate product/schema decision. The pre-existing packaged-H2 legacy status-check defect
is tracked separately as `ONBOARDING-H2-001`; it does not change the PostgreSQL/runtime schema
verdict for this epic.

#### 9. Likely files

Backend runtime: `Application.kt`, `VenueRoutes.kt`, `PlatformRoutes.kt`, `PlatformVenueRoutes.kt`,
`VenueConnectionRequestRepository.kt`, `TelegramBotRouter.kt`, `PlatformVenueRepository.kt`,
`PlatformVenueMemberRepository.kt`, `PlatformUserRepository.kt`, `VenueOwnerAccountRepository.kt`,
plus one shared onboarding service/DTO file if extraction cannot fit an existing cohesive module.

Mini App: `venueApp.ts`, `venueApi.ts`, current Venue DTO module, `platformApp.ts`,
`platformVenuesList.ts`, `platformVenueDetail.ts`, `platformCockpitSections.ts`, `platformApi.ts`,
`platformDtos.ts`, focused new owner/request screens and styles only as needed. Tests:
`VenueConnectionRequestRepositoryTest.kt`, `TelegramBotRouterVenueConnectionRequestFlowTest.kt`,
`VenueRbacRoutesTest.kt`, `PlatformRoutesTest.kt`, `PlatformVenueRoutesTest.kt`,
`PlatformOnboardingRoutesTest.kt`,
`PlatformVenueRepositoryTest.kt`, `PlatformVenueMemberRepositoryTest.kt`,
`VenueOwnerAccountRepositoryTest.kt`, a deterministic PostgreSQL concurrency class if needed, and
`miniapp/e2e/guest-smoke.spec.ts`. CI may update only existing focused selectors/minima.

#### 10. Backend tests

- Repository: own/all list ordering and privacy, exact state mapping, edit/cancel CAS, production
  canonical exact/distinct application behavior, rejected/cancelled reuse, simultaneous same/distinct
  cross-surface submit, first-applicant lifecycle, repeated create-link recovery, no duplicate
  membership/link/audit and safe DB failure.
- Venue routes: Owner own cards/requests and fields; cross-user/foreign/Manager/Staff/unaffiliated/
  Platform-only denial without an oracle; actor spoof ignored; malformed/bounded fields; GETs have no
  mutation; access refresh after linking.
- Platform routes/repositories: exact Platform authorization, request list/detail/actions, city and
  all-owner summaries, owner search/filter/status counts, multi-owner/multi-venue aggregation,
  no primary label, last-owner and owner-account mismatch preservation, safe identities and no PII.
- Shared orchestration: approval remains non-seeding; create-new-DRAFT-and-link is idempotent or
  safely recoverable at each current step; truthful audit cardinality/privacy; failure produces no
  false success. Add direct `owner_venue_onboarding_entry` callback dispatch and prove zero category
  writers (`BOOTSTRAP-TEST-002`).

#### 11. Mini App E2E

Add deterministic browser scenarios for Owner one/multiple venue cards, selector navigation, exact
double-submit, distinct second application, approved-unlinked exact retry, lost response, active
pending/edit/cancel, linked access refresh, cross-account late-response isolation and
unaffiliated/Manager/Staff/Platform-only no Venue Mini App application UI/API mutation. Assert associated labels,
stable live regions, textual statuses and predictable submit/error/cancel/back focus.
Platform scenarios cover the three workspace tabs, request detail/action/error recovery, venue city
and all-owner summary, owner search/filter/drill-down, multiple owners, direct DRAFT create
regression and safe empty/error states. Existing full smoke remains green.

#### 12. Telegram regressions

Preserve first-user `🤝 Добавить свою кальянную`, pending edit/cancel, approved-unlinked close, Platform
approve/reject, commercial terms, create-new-DRAFT-and-link, owner notification, `Мои заведения`,
multi-venue selection and current denial/copy. Dispatch each persisted
`OWNER_VENUE_CREATE_WAIT_NAME/CITY/ADDRESS` state into the usable shared flow with zero direct
writes. Prove Telegram calls the shared service, exact repeat does
not duplicate requests/venues/memberships/audits, approval/linking/onboarding callback seed zero menu
categories, and Mini App use does not break Telegram-first flow.

#### 13. CI and release gates

Run the smallest focused backend selectors first, then exact route/security and applicable
PostgreSQL concurrency selectors, `compileKotlin`, `ktlintCheck`, Mini App production build and full
Playwright smoke. Extend the existing CI jobs/parser with exact new XML and post-implementation
minimums; missing/zero/below-minimum/skipped/failed/errored XML must fail. Require independent review,
green GitHub Actions for the release HEAD and staging deploy before claiming release closure. No
production-readiness claim from local-only checks.

#### 14. Consolidated staging smoke

On one clean staging dataset verify: a Telegram first applicant with zero OWNER memberships, Owner
with one/multiple venues, and Manager/Staff/foreign/Platform-only Venue-route denial; Telegram-first
and Mini App-first exact request submission, distinct second request and approved-unlinked exact
retry; pending edit/cancel; own-request
isolation; Manager/Staff direct denial; Platform three-tab navigation; pending detail; reject; approve
without venue/menu creation; commercial terms; create-new-DRAFT-and-link; Owner membership/card/
selector refresh; venue list city and all co-owners; owner search/status counts/drill-down; no primary
label; direct Platform DRAFT create regression; Telegram request/venues/account regressions; exactly
zero category writes through approval/linking/onboarding callback; safe audit/contact privacy;
failure/retry copy; and cleanup. Concurrency/failure injection stays automated unless a safe staging
procedure is explicitly prepared.

#### 15. Independent review finding closures

Epic stage: **DONE / MVP / STAGING-SMOKE-PASSED**. The findings below were individually closed by
implementation and test evidence; user-confirmed green Actions, staging deploy, consolidated smoke
and cleanup close the bounded release gate. This does not close the overall Platform/Venue product.

| Finding | Status | Closure evidence |
| --- | --- | --- |
| `ONBOARDING-CANON-UNICODE-001` | `DONE` | The production tuple preserves NFKC, collapses Unicode-aware `(?U)\s+`, trims, lowercases with `Locale.ROOT` and maps a whitespace-only optional comment to `null`. Repository-path coverage proves `Smoke One` / `Smoke\u0085One` retry, the complete Unicode `White_Space` code-point set, NFKC-compatible forms, a truly distinct non-whitespace payload, and exact physical request/submit-audit cardinality. |
| `ONBOARDING-VENUE-ROUTE-COVERAGE-001` | `DONE` | Eight production-module Venue route scenarios cover exact retry, distinct second application, server-derived applicant/actor/entry policy under spoof-like extra fields, safe database/audit failure, approved-unlinked retry and the denial boundaries without weakening sensitive-fact ordering. |
| `ONBOARDING-PG-FIRST-APPLICANT-001` | `DONE` | The PostgreSQL users-only first-applicant fixture begins with zero commercial account, venue, membership, subscription, link, selected venue or menu category. Create/link preserves the existing default account limit of `1`; deterministic independent connections return one authoritative linked venue with no duplicate account, venue, membership or success audit. |
| `ONBOARDING-A11Y-CREATE-LINK-FOCUS-001` | `DONE` | Successful create/link and linked retry wait for Venue detail render, then restore focus to its stable focusable heading with the expected accessible name instead of leaving focus on the removed action. |
| `ONBOARDING-OWNER-PLURAL-001` | `DONE` | The shared Russian venue-count formatter is applied to owner list and detail labels, with assertions for `1`, `2`, `5`, `11`, `21`, `22` and `25`. |

Validated backend evidence for the release is repository `13`, Venue routes `8`, Platform
routes `15`, Telegram connection flow `18`, Telegram table/legacy state `552`, Telegram keyboards
`169` and PostgreSQL onboarding `7`. The exact route/security aggregate is `1247 / 1247`; the
mandatory PostgreSQL vector is `8 / 14 / 2 / 44 / 9 / 7` (`84 / 84`). The deterministic Mini App
smoke passed `191 / 191`, including ownership onboarding `15 / 15` with focus restoration and
pluralization coverage. For the release HEAD, the user confirmed fully green Actions, staging deploy,
the consolidated first/additional application, exact/distinct retry, Platform visibility,
exactly-one create/link, explicit selection, legacy-flow, multi-owner, baseline-limit,
server-derived-authority smoke and cleanup. Local GitHub CLI authentication is invalid, so Actions
are recorded as user-confirmed rather than independently queried in this docs-only closure.

#### 16. Historical fulfilled implementation prompt (not current `NEXT`)

The preserved prompt below describes the now release-closed epic and is evidence/history only. It
must not be reused as an active Goal; at that closure, the next epic still required a user decision
after Section 13 review.

```text
Следуй AGENTS.md. Реализуй один bounded epic:
PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT.

Сначала прочитай PROJECT_STATUS.md, docs/PRODUCT_SPEC.md, docs/PLATFORM_COCKPIT.md,
docs/SECURITY_RBAC_MATRIX.md, docs/TESTING_QA_SMOKE_STRATEGY.md и этот epic в
docs/UPDATED_PRODUCT_AI_ROADMAP.md. Проверь current runtime перед изменениями.

Outcome:
1) Venue Mini App: «Мои заведения», authoritative membership cards/current selector,
   «Добавить заведение», own request list/detail/create/edit/cancel с текущими fields
   venueName/city/contact/comment и только states PENDING/APPROVED/REJECTED/CANCELLED.
2) Platform Mini App top-level workspace: «Заявки», «Кальянные», «Владельцы».
   Заявки используют current approve/reject/commercial/create-new-DRAFT-and-link flow;
   Кальянные показывают name/city/status/subscription summary/all owners;
   Владельцы агрегируются из users + active OWNER venue_members + venues, имеют
   search/filter/status counts/drill-down.
3) Extract one backend onboarding service used by Telegram and both Mini App adapters.
   Do not duplicate the onboarding engine. Approval/linking/onboarding-entry callback seed
   zero menu categories. Sequential/concurrent repeat and create-link retry are server-safe.
4) Preserve multiple OWNER memberships. Do not invent primary owner:
   venue_owner_accounts.primary_owner_user_id is commercial account/quota state only.
5) Venue Mini App exposes applications only to active operational Owners and only in own scope;
   Manager/Staff are denied there. Every authenticated Telegram user keeps the ordinary first-or-
   additional venue entry and own-request scope. Platform Owner sees all. Actor/source/request
   owner/link are server-derived.
   Keep applicant contact/comment private to applicant + Platform Owner and exclude raw
   Telegram/initData/provider/private fields. Dangerous decisions are explicit and audit-aware.

No migration is expected. Stop with evidence if current locks cannot safely prevent concurrent
duplicate requests without schema. Do not add NEEDS_INFO, existing-venue chooser, primary-owner
redesign, billing/support/analytics/media/R2/menu scope, or unrelated navigation changes.

Tests: repository/service/routes/RBAC/privacy/audit/retry/multi-owner/multi-venue; direct dispatch
of owner_venue_onboarding_entry with zero category writers; deterministic PostgreSQL concurrency
where required; Venue and Platform Mini App E2E; Telegram regression. Update existing CI selectors
and exact minima only from actual XML. Then require review, green Actions, staging deploy and the
single consolidated smoke from the roadmap. Do not stage/commit/push/deploy unless explicitly asked.
```

### Current implemented bounded block

Implementation contract: **IMPLEMENT_MENU_ITEM_CREATE_AUDIT_NEXT**.

Verdict: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

- Runtime inventory confirms the sole production `INSERT INTO menu_items` in the private helper
  behind required `VenueMenuRepository.createItem`, called only by authenticated Venue Mini App
  `POST /menu/items` and the Telegram Owner/Manager add-item dialog. Operational staging seed SQL is
  not a runtime writer; item creation creates no options.
- Owner/Manager retain own-venue authority with Manager-compatible legacy `ADMIN`; Staff, foreign,
  unaffiliated and Platform-only actors are denied. Mini App passes only its session subject and
  `VENUE_MINI_APP`. Telegram checks venue authority before category lookup, requires current user to
  equal persisted dialog owner and passes only that user plus `TELEGRAM_BOT`.
- One repository-owned connection/transaction performs category scope, blocking category lock,
  scope recheck, existing `MAX(sort_order)+1`, item insert, generated id, same-connection
  `MENU_ITEM_CREATED`, item reread and commit. Reorder shares the category lock; category delete
  preserves its `NOWAIT` outcome. Audit failure after physical item/audit insert rolls everything
  back and neither surface emits false success.
- Exactly one committed item has exactly one audit for entity `menu_item` / item id. Payload keys are
  exactly `venueId`, `itemId`, `source`; actor stays only in the standard actor field. Item content,
  price/currency/availability/type/category/description/sort, media/options/promotion/cart/order,
  request/initData/Telegram content, secrets and PII are excluded. Duplicate-name creates remain
  separate; no idempotency, uniqueness, migration or business-default/API/UI change was added.
- Automated evidence is repository `44/0/0/0`, routes `40/0/0/0`, Telegram `542/0/0/0`, Testcontainers
  PostgreSQL `31/0/0/0`, compile/ktlint, Mini App build and full Playwright `169/169`. PostgreSQL
  evidence uses production migrations/repositories, independent PIDs, latches and real blocking for
  Mini/Mini, Mini/Telegram, create/reorder and concurrent audit failure, plus deterministic
  create/category-delete `NOWAIT`. CI minima are updated in the existing workflow; no new workflow.
  **NO_MIGRATION_EXPECTED**.
- User-confirmed green Actions, staging deploy and bounded cross-surface smoke passed: Owner Mini App
  create, Manager Telegram create, Staff denial, duplicate-name distinct rows/audits, intact item
  order and Guest menu, payload privacy and cleanup. That item-create slice alone excluded item
  update audits; the separate local Menu Management closure above now covers the existing
  name/price-currency/type/category-move/reorder writers. Description/media, membership revoke,
  Guest cart/order, promotions, media/R2 and broader audit closure stay outside. Stash remains
  unread and untouched; `scripts/dev/` remains untouched.

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

| Candidate | Runtime evidence at that historical selection | Selection result |
| --- | --- | --- |
| Menu option price audit | Mini App can combine name/price/availability; Telegram has no option-price writer. Current `updateOption` is locked/transactional, but delta changes preview, submit and promotion-adjusted totals. | Defer: asymmetric financial scope and current-order behavior make it riskier. |
| Menu item price audit | Mini App had a compound patch and Telegram a separate price dialog; at that selection point `updateItem` lacked the later locked audit contract. | Deferred then; now covered by the separate local Menu Management closure above. |
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

## 13. MASTER REMAINING WORK INVENTORY

Status: **MASTER REMAINING WORK INVENTORY PREPARED / USER DECISION REQUIRED**. This section is the
canonical owner of the complete remaining-work catalog. It neither selects `NEXT` nor creates an
active implementation Goal. Current code, migrations and focused tests were inspected read-only;
runtime tests were intentionally not run for this docs-only inventory.

### 13.1 Document inventory and audit method

All 35 Markdown surfaces were scanned: 32 files under `docs/**`, plus `PROJECT_STATUS.md`,
`README.md` and root `AGENTS.md`. Root `AGENTS.md` is instruction-only, not backlog evidence. No
local nested `AGENTS.md` exists.

| Classification | Files |
| --- | --- |
| `CANONICAL_CURRENT` (17) | `PROJECT_STATUS.md`, `README.md`, `AGENTS.md`; `ANALYTICS_EVENTS.md`, `BOOKING_LIFECYCLE.md`, `COMMUNICATION_MODEL.md`, `GROWTH_RETENTION.md`, `MEDIA_STORAGE_UPLOAD.md`, `MENU_OPTIONS_STOPLIST.md`, `ORDER_SESSION_TAB_CORE.md`, `PLATFORM_COCKPIT.md`, `PRODUCT_SPEC.md`, `SECURITY_RBAC_MATRIX.md`, `STAFF_PROFILES_SHIFTS_TIPS.md`, `TELEGRAM_FALLBACK_STAFF_CHAT.md`, this roadmap, `VENUE_OPERATIONS.md`. |
| `ROLE_CURRENT` (5) | `docs/audit/ROLE_GUEST.md`, `ROLE_MANAGER.md`, `ROLE_PLATFORM_OWNER.md`, `ROLE_STAFF.md`, `ROLE_VENUE_OWNER.md`. |
| `QA_CURRENT` (3) | `DEFERRED_MANUAL_SMOKE_BACKLOG.md`, `TESTING_QA_SMOKE_STRATEGY.md`, `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`. |
| `OPERATIONS_CURRENT` (5) | `DEPLOYMENT_RUNBOOK.md`, `LOCAL_TELEGRAM_MINIAPP.md`, `MIGRATION_POLICY.md`, `OPERATIONS.md`, `STAGING_DEPLOYMENT.md`. |
| `HISTORICAL_AUDIT` (4) | `BOT_MINIAPP_PARITY_AUDIT.md`, `MINI_APP_PRODUCTION_READINESS_AUDIT.md`, `PRODUCT_AUDIT_SUMMARY.md`, `PRODUCT_IDEAS_REVIEW.md`. |
| `HANDOFF_HISTORY` (1) | `VENUE_BOT_TO_MINIAPP_PARITY_PROGRAM.md`. |
| `OBSOLETE_OR_DUPLICATE` (0) | No whole file is safe to discard; stale and duplicate claims exist inside otherwise useful files. |

Candidate counting uses normalized claim records: repeated wording inside one local context is
collapsed, while cross-document and cross-domain repeats remain candidates until global
deduplication. The three domain audits yielded 187 records; eleven explicit AI roadmap outcomes bring
the total to **198**. Final disposition is:

| Disposition | Count |
| --- | ---: |
| `OPEN_CONFIRMED` | 41 |
| `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED` | 5 |
| `BLOCKED_PRODUCT_DECISION` | 14 |
| `BLOCKED_PREREQUISITE` | 13 |
| `DEFERRED_AFTER_MVP` | 31 |
| `UNKNOWN_NEEDS_RESEARCH` | 6 |
| `STALE_ALREADY_IMPLEMENTED` | 42 |
| `DUPLICATE_OF_OTHER_ID` | 35 |
| `HISTORICAL_ONLY` | 11 |
| **Total raw candidates** | **198** |
| **Canonical catalog items below** | **110** |

Table shorthand: `MRWI` means this section is canonical owner; the following documents are other
sources. `A` = green Actions, `F` = focused automated tests, `PG` = PostgreSQL migration/integration/
concurrency evidence, `E2E` = Mini App browser smoke, `S` = staging deploy and bounded smoke, `RT` =
real Telegram/staff-chat smoke, `D` = explicit product decision. The final column is always
`scope / migration; main affected areas; release gates; trigger; priority evidence`.

### 13.2 Security, correctness and data-integrity hardening

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `ONBOARDING-H2-001` — truthful packaged-H2 legacy status check | `OPEN_CONFIRMED` | MRWI; QA, Security, Platform | PostgreSQL onboarding is release-closed; packaged H2 legacy status check is still known-defective. Fix only with isolated H2 evidence; do not question the production migration verdict. | Platform correctness / misleading local status. | Reproducible H2 fixture. | S / NO; backend H2 status path; F+A; when H2 path is used; P2 existing registry. |
| `ONBOARDING-TG-CONFIRM-001` — explicit Telegram submit confirmation | `OPEN_CONFIRMED` | MRWI; QA, Platform | Shared submit/idempotency is closed; Telegram still lacks the optional pre-submit confirmation UX. | Applicant confidence / accidental submission UX. | Preserve exact-retry contract. | S / NO; Telegram dialog/copy/tests; F+A+RT; when funnel friction is observed; P3 existing registry. |
| `ONBOARDING-DECISION-RETRY-001` — clearer lost-response decision retry | `OPEN_CONFIRMED` | MRWI; QA, Platform | Server retry is authoritative and non-duplicating; the UI can better distinguish a recovered late response from a fresh decision. | Platform operator trust / confusing recovery, not data loss. | Existing shared service. | S / NO; Platform/Telegram copy; F+E2E+A+S; when support sees ambiguous retries; P3 existing registry. |
| `SECURITY-AUDIT-ACTOR-FK-001` — align audit actor integrity | `OPEN_CONFIRMED` | MRWI; Security, migration policy | Audits are widely used; H2/PostgreSQL actor FK/nullability differ. Inspect legacy nulls, then align both dialects. | All roles / orphaned or unportable audit truth. | Legacy-data verdict. | M / YES; audit migrations/repositories; PG+F+A+S; before broader audit tooling; P2 data integrity. |
| `QA-RBAC-DIRECT-DENIAL-001` — complete direct route denial coverage | `OPEN_CONFIRMED` | MRWI; Security, QA, role docs | Explicit permissions and many route tests work; matrix remains partial until every current/new route has Staff/foreign/Platform-only direct denial cases. | Tenant privacy / hidden UI can mask authority gaps. | Route inventory. | M / NO; backend routes/tests/CI; F+A; before enlarging role surfaces; P1 security. |
| `SECURITY-ADMIN-COMPAT-001` — finish legacy ADMIN cleanup | `OPEN_CONFIRMED` | MRWI; Security, Venue, Telegram | Assignment already rejects product ADMIN and DB mapping aliases it to MANAGER; access/copy branches still accept or display ADMIN. Resolve data and copy without breaking legacy rows. | Venue role clarity / inconsistent authority and copy. | Legacy-row scan and alias policy. | M / POSSIBLE; DB/access/Telegram/UI; PG+F+A+S; before role redesign; P2 hygiene. |
| `SECURITY-MANAGER-MENU-SCOPE-001` — decide Manager menu authority | `BLOCKED_PRODUCT_DECISION` | MRWI; Security, Menu, Venue | Current `VenuePermissions` grants full `MENU_MANAGE`; docs describe both retention and conservative narrowing. Choose one server policy before changing UI. | Owner control / accidental privilege drift. | Explicit Owner/Manager product decision. | M / POSSIBLE; RBAC/routes/Bot/Mini App; D+F+A+S; before new constructor scope; P1 authority. |
| `VENUE-STAFF-STOPLIST-POLICY-001` — configurable Staff stop-list authority | `BLOCKED_PRODUCT_DECISION` | MRWI; Venue, Menu, Security, Telegram | Staff item/option availability paths work; no per-venue `staff_stoplist_enabled` exists. Decide always-on versus tenant-controlled enforcement. | Venue operations / over- or under-authorized Staff. | Staff operating policy. | M / YES likely; settings/RBAC/UI/Bot; D+PG+F+A+S; before exposing more Staff controls; P2. |
| `MENU-RBAC-RACE-001` — transaction-bound option delete authority | `OPEN_CONFIRMED` | MRWI; Menu, Security | Route membership check precedes the repository hard-delete transaction. Bind final membership/permission recheck to the mutation and audit. | Venue security / revoke-to-write race. | Existing option-delete contract. | M / NO likely; routes/repository/concurrency tests; F+A+S; now or with related audit slice; P2 race. |
| `SECURITY-TABLE-QR-AUDIT-001` — safe QR rotate/export consequences | `OPEN_CONFIRMED` | MRWI; Security, Venue | Rotate/export and batch operations work; audit is not consistently mutation-bound and rotate UX lacks explicit consequence confirmation. | Guest access safety / stale links and weak accountability. | Current table permissions. | M / NO/POSSIBLE; table routes/repo/UI/audit; F+A+S; before broader QR operations; P1 dangerous action. |
| `NOTIFY-CALLBACK-AUDIT-001` — complete operational callback evidence | `OPEN_CONFIRMED` | MRWI; Telegram, Security, Analytics | State-aware callbacks/RBAC work and selected slices are audited; no complete enqueue/deliver/click/reject inventory or safe exactly-once policy exists. | Venue operations / actions become hard to reconstruct. | Audit-vs-analytics boundary. | M / POSSIBLE; Telegram/outbox/audit; F+A+RT; before notification automation; P1 operations/security. |
| `SECURITY-GUEST-CONTEXT-RACES-001` — distributed Guest-context hardening | `DEFERRED_AFTER_MVP` | MRWI; Core, Security, roadmap | Controlled Platform Guest QR Phase 1 is closed for single-instance long polling. Remaining P2s cover distributed pending, immediate-exit edge cases, draft/dialog linearity and fuller endpoint race matrix. | Guest privacy / horizontal scaling races. | Scaling or expansion trigger. | L / POSSIBLE/YES; context stores/routes/UI; PG+F+A+RT; at multi-instance rollout; P2 deferred. |

### 13.3 Guest order / session / tab / cart / bill core

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `ORDER-PERSONAL-TAB-INTEGRITY-001` — non-null personal-tab ownership | `OPEN_CONFIRMED` | MRWI; Core, Security | Personal/shared guards, idempotency and H2 fidelity work; PostgreSQL V29 still permits active PERSONAL rows with null owner. Scan/backfill, constrain and prove concurrency; decide separately whether shared uniqueness needs strengthening. | Guest privacy / ambiguous ownership. | Legacy-data verdict. | M / YES; tab schema/repository/routes; PG+F+A+S; before wider tab lifecycle; P1 integrity. |
| `ORDER-TAB-LIFECYCLE-001` — paid/closed/reopen/member lifecycle | `OPEN_CONFIRMED` | MRWI; Core, Venue, Security | ACTIVE/CLOSED tabs, create/join/invite and scoped bills work. Define settlement/paid/reopen/leave/member management, reason/audit and UI. | Guest/Venue bill correctness / unsafe reopen or stranded tabs. | Bill settlement decision. | L / YES; tab/order DB/API/UI/Bot; D+PG+F+A+S; before shared-tab expansion; P1. |
| `ORDER-SESSION-CLOSE-POLICY-001` — safe staff physical-session close | `BLOCKED_PRODUCT_DECISION` | MRWI; Core, Venue, Security | User exit and TTL expiry correctly leave the shared physical session intact. Decide who may force-close, what happens to orders/tabs/visits, and required reason/confirmation/audit. | Guest/Venue correctness / data loss or never-ending sessions. | Product ownership of physical visit. | L / POSSIBLE; session/order/visit/UI; D+PG+F+A+S; before force-close UI; P1. |
| `CART-IMMUTABLE-DRAFT-LABEL-001` — truthful stale-cart labels | `OPEN_CONFIRMED` | MRWI; Core | Typed atomic stale-cart recovery and idempotency are closed; Mini App cart persists ids/options but recovery copy can use a mutable current item name. Persist a safe label or approve fallback semantics. | Guest clarity / misleading recovery. | Cart compatibility policy. | S / NO; `cartStore`/copy/tests; F+E2E+A+S; with next cart change; P2 UX/correctness. |
| `CART-CROSS-SURFACE-SYNC-001` — one server-scoped Bot/Mini App draft | `DEFERRED_AFTER_MVP` | MRWI; Core, Telegram | Mini App cart is scoped localStorage; Bot draft is process-local. A common versioned server draft needs session/tab isolation and conflict resolution. | Guest continuity / split carts and restart loss. | Cart ownership/version policy. | XL / YES/POSSIBLE; DB/API/Mini App/Telegram; D+PG+F+A+RT; proven cross-surface demand; P2 deferred. |
| `TELEGRAM-FALLBACK-TAB-001` — shared-tab-aware fallback order | `DEFERRED_AFTER_MVP` | MRWI; Telegram, Core | Quick-order fallback and payload contract work. Add authorized personal/joined tab selection and consistent structured lines. | Guest parity / wrong-tab confusion. | Tab lifecycle and selected-tab contract. | M / NO likely; Telegram/order API; F+A+RT; when shared tabs need Bot parity; P2. |

### 13.4 Venue operations

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `VENUE-DASHBOARD-001` — one actionable Venue cockpit | `OPEN_CONFIRMED` | MRWI; Venue, Product Spec | Dashboard has venue/staff-call/staff-chat facts and separate stats work. Add role-specific order, booking, stop-list, shift and subscription warning cards from authoritative read models. | Venue speed / fragmented operations. | Stable queue/settings summaries. | M/L / POSSIBLE; Venue API/Mini App; F+E2E+A+S; before multi-venue pilot load; P1 operator UX. |
| `VENUE-ORDER-QUEUE-ADVANCED-001` — SLA-aware order queue | `DEFERRED_AFTER_MVP` | MRWI; Venue, Analytics | Queue/detail/status/full bill are working. Rich filters/grouping, SLA timers and event-derived metrics remain. | Venue throughput / missed or delayed orders. | Event fidelity. | M/L / POSSIBLE; order queries/UI/analytics; F+A+S; at real queue volume; P2 deferred. |
| `VENUE-TABLE-CRUD-001` — complete table edit/deactivate diagnostics | `OPEN_CONFIRMED` | MRWI; Venue | List, batch create, rotate and export work. Add single-table edit/deactivate, active-session warnings and operational diagnostics without treating QR as authority. | Venue setup / disruptive table changes. | Session-close consequences. | M / NO; table routes/UI; F+E2E+A+S; before larger floor plans; P1. |
| `VENUE-SETTINGS-PARITY-001` — backend-backed settings parity | `OPEN_CONFIRMED` | MRWI; Venue, Telegram | Team/schedule, public card, hours/exceptions, booking hold and shift extension work; timezone and notification toggles exist in DB/Bot but lack complete Venue Mini App management. | Venue self-service / Bot-only configuration drift. | Notification policy. | M / NO; settings repo/API/UI/Bot regression; F+E2E+A+S; next settings epic; P1 operations. |
| `VENUE-PUBLISH-WORKFLOW-001` — versioned settings publish/rollback | `DEFERRED_AFTER_MVP` | MRWI; Venue, Platform | Saved settings and guarded private/published preview work. Deliberate versioned publishing, history and rollback do not. | Venue safety / accidental public changes. | Lifecycle/product ownership decision. | L/XL / YES/POSSIBLE; settings DB/API/UI; D+PG+F+A+S; at multi-editor demand; P3. |
| `VENUE-STATS-ADVANCED-001` — custom operational statistics | `DEFERRED_AFTER_MVP` | MRWI; Venue, Analytics | Today/7d/30d orders, revenue, average bill, discounts, cancellations and top items work. Add custom periods, booking/call metrics and only reliable AI summaries. | Venue decisions / incomplete operational picture. | Analytics event baseline. | L / POSSIBLE; stats queries/UI/AI; F+A+S; when fixed periods are insufficient; P2/P3. |
| `STAFF-CALL-OPS-POLISH-001` — complete call actors/timing/replies | `OPEN_CONFIRMED` | MRWI; Venue, Telegram | NEW/ACK/DONE, guest CANCELLED and bounded audit are closed. Add permitted manual cancel, quick replies and `acked_by/done_by` timestamps/history. | Guest service / weak accountability and response-time truth. | Cancel/reply policy. | M / YES; calls schema/API/UI/staff-chat; PG+F+A+RT; before SLA metrics; P2. |
| `STAFF-SCHEDULE-ADVANCED-001` — availability, swaps and recurring shifts | `DEFERRED_AFTER_MVP` | MRWI; Staff, Venue | Phase 1, restore, bulk assignment and optional module are closed. Availability, swaps, recurring templates, reminders, attendance and richer planning remain. | Staff planning / manual coordination. | Staff communication policy. | XL / YES likely; schedule DB/API/UI/Telegram; D+PG+F+A+S; proven scheduling demand; P3. |
| `STAFF-COMMS-POLICY-001` — choose staff notification topology | `BLOCKED_PRODUCT_DECISION` | MRWI; Staff, Telegram, Venue | Staff-chat radar works. Decide personal bot confirmations, swap/signup messages and one forum topic versus another group, including consent and privacy. | Staff reliability / noisy or missed alerts. | Explicit topology/consent decision. | L / UNKNOWN; notifications/Telegram/settings; D; before schedule automation; P2. |
| `VENUE-ADDRESS-AUTOCOMPLETE-001` — provider-independent local address search | `DEFERRED_AFTER_MVP` | MRWI; Product Spec, Venue | Manual structured country/city/address and route fallback are closed; optional commercial providers remain disabled. A local FIAS import/index is absent. | Guest routing/Venue setup / unverified addresses. | Dataset/license/update operations. | XL / YES; geodata DB/API/UI/ops; PG+F+A+S; only with validated business need; P3. |

### 13.5 Telegram parity and notifications

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `TELEGRAM-PLATFORM-PARITY-001` — verified Platform Bot/Mini App action map | `UNKNOWN_NEEDS_RESEARCH` | MRWI; Telegram, Platform | Platform Bot has many real callbacks and Mini App has current workspaces; remaining docs still use broad parity/placeholders language. Produce a read-only action diff before scoping implementation. | Platform clarity / duplicate or phantom work. | Current screen/callback inventory. | M research / UNKNOWN; docs/code audit, then affected surfaces; no runtime gate until facts; now for planning; P2 research. |
| `TELEGRAM-VENUE-CHAT-ENTRY-001` — verify Bot entry to VENUE_CHAT | `UNKNOWN_NEEDS_RESEARCH` | MRWI; Telegram, Communication | Mini App `Задать вопрос` and persisted venue replies work; Bot guest entry remains `needs verification`. Trace callbacks/routes and do a bounded manual walkthrough. | Guest communication / unreachable Bot path. | Communication routing. | S research / NO likely; Telegram/manual QA; RT if gap found; before parity claim; P2 research. |
| `QA-TG-FALLBACK-SMOKE-001` — real-client fallback order proof | `OPEN_CONFIRMED` | MRWI; Telegram, QA | Quick-order payload and automated tests work; real Telegram message/edit/cancel/batch/source/tab behavior is still a release-confidence gate. | Guest fallback / integration regressions. | Staging bot/table. | S manual / NO; Telegram+order; RT; next suitable staging window; P1 QA. |
| `QA-STAFF-CHAT-PILOT-001` — real per-venue staff-chat pilot | `OPEN_CONFIRMED` | MRWI; Telegram, Venue, Booking | Link/test/notifier/callback tests work. Verify calls/orders/bookings, retry and explicit absence of support/venue-chat/feedback noise in a real group. | Venue operations / silent or noisy radar. | Real staging group. | S/M manual / NO; staff-chat/Telegram; RT; before pilot venue reliance; P1. |
| `NOTIFY-DELIVERY-HISTORY-001` — safe delivery status and retry history | `DEFERRED_AFTER_MVP` | MRWI; Telegram, Platform, Analytics | Outbox, notifier and link/test work; recipient-safe history/last delivery UI and coherent retry diagnostics do not. | Venue/Platform support / invisible delivery failures. | Event/callback policy. | M/L / POSSIBLE; outbox/API/UI; F+A+RT; at notification volume; P2. |

### 13.6 Platform and commercial operations

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `PLATFORM-GTM-PIPELINE-001` — lead-to-paid onboarding funnel | `OPEN_CONFIRMED` | MRWI; Product Spec, Platform, Analytics | Shared applications, terms, decisions and atomic create/link are closed. Add source/partner attribution, qualified/demo/trial/activated/paid stages, timestamps, notes and conversion metrics. | Platform acquisition/revenue / off-system funnel. | Closed onboarding and event contract. | L / YES; onboarding DB/API/Platform/Telegram/analytics; PG+F+E2E+A+S; before B2B scale; P1 commercial readiness. |
| `PLATFORM-LIFECYCLE-NORMALIZATION-001` — explicit lifecycle origins | `BLOCKED_PRODUCT_DECISION` | MRWI; Platform, Product Spec | Current DRAFT/PUBLISHED/HIDDEN/PAUSED/SUSPENDED/ARCHIVED/DELETED actions work. Decide separate onboarding, owner pause, platform suspension and deletion-request states. | Platform/Venue correctness / wrong restore or copy. | Product/legal retention decision. | L / YES; lifecycle DB/API/UI/availability; D+PG+F+A+S; before automation/legal deletion; P2. |
| `PLATFORM-OWNER-TRANSFER-001` — legal/commercial owner transfer | `BLOCKED_PRODUCT_DECISION` | MRWI; Platform, Security | Multi-owner memberships, portfolios, invites and safe revoke work. Define payer/legal ownership transfer, quota/account relink, invoices and transactional audit. | Venue continuity / legal, access and billing risk. | Legal versus operational OWNER model. | XL / YES; owner/billing DB/API/UI; D+PG+F+A+S; first sale/entity transfer; P2→P1 at trigger. |
| `PLATFORM-PLACEMENTS-PARITY-001` — existing placement management in Mini App | `OPEN_CONFIRMED` | MRWI; Platform, Growth | V92/V94 repositories and Telegram request/moderation/feed lifecycle work; Platform cockpit still has a placement placeholder. Add safe Mini App parity without calling it paid ads. | Platform/Venue efficiency / Bot-only management. | Existing placement contract. | M / NO; Platform/Venue API/UI; F+E2E+A+S; when cockpit is canonical for placements; P2. |
| `PLATFORM-HEALTH-001` — risk and integration health cockpit | `BLOCKED_PREREQUISITE` | MRWI; Platform, Analytics, Ops | Lifecycle, billing and support facts exist separately. Unified overdue/blocked/queue/outbox/webhook/Telegram/staff-chat/Mini App health needs reliable instrumentation. | Platform operations / late incident detection. | Event coverage and delivery/provider telemetry. | L / POSSIBLE; aggregates/Platform UI/ops; F+A+S; before venue scale/on-call; P2 blocked. |
| `PLATFORM-MODERATION-OPS-001` — flags, notes and bounded bulk review | `DEFERRED_AFTER_MVP` | MRWI; Platform, Product Spec | Individual lifecycle actions/audits work. Add moderation queues, operator notes and only safe bounded bulk actions. | Platform scale / manual repetition. | Lifecycle/audit policy. | L / POSSIBLE; DB/API/UI/audit; F+A+S; at sustained moderation volume; P3. |
| `BILLING-PROVIDER-001` — production payment provider | `BLOCKED_PRODUCT_DECISION` | MRWI; Platform, Product Spec, Ops | Fake and generic HMAC foundations, signature checks, webhook/payment idempotency and checkout exist. Choose provider/legal contract and implement specific status/refund/chargeback/reconciliation/UX. | Platform revenue / money and reconciliation risk. | Provider, credentials, legal/commercial owner. | XL / POSSIBLE; billing/webhooks/UI/ops; D+provider tests+A+sandbox S; before online collection; P1 at trigger. |
| `BILLING-INVOICE-VOID-REISSUE-001` — audited invoice correction | `OPEN_CONFIRMED` | MRWI; Platform | VOID enum and manual/renewal/courtesy flows work; open-invoice courtesy overlap only reports an error. Add idempotent void/reissue/correction without rewriting paid history. | Platform billing / blocked manual correction. | Correction policy. | M / POSSIBLE; billing repo/API/UI/audit; F+A+S; before first overlap incident; P1. |
| `BILLING-SUSPENSION-PROVENANCE-001` — safe cause-specific reactivation | `OPEN_CONFIRMED` | MRWI; Platform, Security | Billing suspension and availability guards work; persisted cause/version is insufficient to distinguish billing recovery from manual Platform suspension. | Venue access / unsafe auto-reactivation. | Optional lifecycle normalization. | M / YES; subscription schema/engine/UI/audit; PG+F+A+S; before automatic recovery; P1. |
| `BILLING-STARS-001` — Telegram Stars subscription channel | `DEFERRED_AFTER_MVP` | MRWI; Product Spec, Platform | Provider abstraction/invoices work. Stars eligibility, payment/refund/reconciliation mapping and UX are absent. | Venue convenience / channel-specific financial risk. | Commercial/channel decision. | L / POSSIBLE; Telegram/billing/events; provider F+A+S; demand and eligibility; P3. |
| `BILLING-RECURRING-001` — automatic recurring billing | `DEFERRED_AFTER_MVP` | MRWI; Product Spec, Platform | Invoice periods, paid-through, renewal and billing engine work. Add mandates/tokens, retries/dunning, consent, cancellation/refunds and reconciliation. | Recurring revenue / compliance and double-charge risk. | Real provider, suspension provenance, agreement. | XL / YES; billing/subscriptions/notifications/ops; provider F+A+controlled S; after provider stability; P3. |
| `SUPPORT-AUTOMATION-001` — SLA, aging and escalation | `OPEN_CONFIRMED` | MRWI; Platform, Communication | SUPPORT_TICKET list/reply/status/reopen and scope assignment work. Add SLA clocks, queue aging, worker/alerts, auto-escalation and operator assignment. | Guest/Venue support / missed tickets. | Notification reliability and ownership model. | L / YES; support DB/workers/UI/notifications; PG+F+A+S; before manual queue overload; P2. |
| `SUPPORT-ADVANCED-001` — diagnostics, attachments, macros and CSAT | `BLOCKED_PREREQUISITE` | MRWI; Platform, Analytics, Media | Structured threads/context work. Privacy-safe diagnostic bundles, attachments/retention, macros and truthful CSAT/TTFR/TTR metrics require storage, redaction, SLA and event semantics. | Faster support / PII, storage and misleading-metric risk. | Media storage, analytics, SLA semantics. | L/XL / YES; support/storage/events/UI; privacy F+A+S; repeated support demand; P3 blocked. |
| `ANALYTICS-EVENT-COVERAGE-001` — canonical server event baseline | `OPEN_CONFIRMED` | MRWI; Analytics, Product Spec, all domain docs | Event store and six observed names work, including `promotion_applied`; names/envelope/idempotency/retention and booking/support/staff/menu/notification/onboarding/favorites/repeat/provider coverage remain partial. | All roles / unreliable KPIs and automation. | Domain-owned server facts. | L / YES; event schema/emitters/tests/docs; PG+F+A+S sample; before dashboards/campaigns/funnel; P1. |
| `ANALYTICS-PLATFORM-DASHBOARD-001` — reconciled Platform KPIs | `BLOCKED_PREREQUISITE` | MRWI; Analytics, Platform | Operational facts exist and UI intentionally avoids fake numbers. Add agreed aggregates, filters and drill-down only after event normalization. | Platform decisions / false confidence. | `ANALYTICS-EVENT-COVERAGE-001`. | L / POSSIBLE; query/API/Platform UI; fixtures+privacy F+A+S; immediately after event baseline; P1/P2 blocked. |
| `PLATFORM-AUDIT-VIEWER-001` — privacy-filtered audit explorer | `OPEN_CONFIRMED` | MRWI; Security, Platform, Analytics | Safe audit rows exist for many bounded actions; no general Platform viewer/filter/export policy exists. Add scoped view, payload allowlists and direct denials; do not conflate with missing write audits. | Platform accountability / costly incident review. | Stable audit contracts. | L / POSSIBLE; audit queries/API/UI; F+A+S; after/alongside critical writer audits; P2. |

### 13.7 Menu, content and media

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `MENU-CONC-001` — race-safe menu item relocation | `OPEN_CONFIRMED` | MRWI; QA, Menu | Current item mutation/audit families are closed, but non-locking `sourceCategoryHint` can become stale before category locks. Resolve authoritative source under deterministic locks. | Venue correctness / lost or misordered concurrent move. | Existing repository transaction model. | S / NO; menu repository/concurrency tests; F+A+S; next menu hardening slice; P2 existing registry. |
| `MENU-TEST-002` — stronger category rollback proof | `OPEN_CONFIRMED` | MRWI; QA, Menu | Category create/seed/compound/reorder tests exist; not every injected same-connection failure asserts all immediate pre-state. Add bounded rollback invariants. | Venue data integrity / false confidence in atomicity. | No product decision. | S / NO; repository tests/CI; F+A; now with low scope; P2 existing registry. |
| `MENU-DESCRIPTIONS-001` — item descriptions end to end | `OPEN_CONFIRMED` | MRWI; Menu, Product Spec | `menu_items.description` exists since V1; active repository/DTO/Guest/Venue UI paths do not expose it. Add bounded authoring, safe display and audit. | Guest choice/Venue content / impoverished menu. | Copy and payload privacy. | M / NO; menu repo/API/Bot/Mini Apps; F+E2E+A+S; content-quality epic; P1/P2 user value. |
| `MENU-FEATURED-001` — venue-managed featured/top-list | `OPEN_CONFIRMED` | MRWI; Menu, Product Spec | Stats top items exist but no manual FEATURED/TOP_LIST field, ordering rule, API or UI. Keep separate from Platform paid placement. | Guest discovery/Venue merchandising / missed upsell. | Product ordering and caps. | M / YES likely; menu DB/API/UI/Bot; PG+F+E2E+A+S; when merchandising is prioritized; P2. |
| `MENU-ADVANCED-CONSTRUCTOR-001` — advanced modifiers and bulk governance | `DEFERRED_AFTER_MVP` | MRWI; Menu, QA | Flat selected option, CRUD, shift check and bounded audit families work. Remaining bundle: modifier groups/required/min-max/multi-select, bulk import, unavailable display policy, general option uniqueness, Telegram shift-check/history. | Venue setup/Guest choice / large schema and combinatorial order risk. | Explicit demand and compatibility design. | XL / YES; menu/order DB/API/Bot/Mini Apps; D+PG+F+A+S; only with concrete venue use cases; P3. |
| `MEDIA-STORAGE-DECISION-001` — approve media operating model | `BLOCKED_PRODUCT_DECISION` | MRWI; Media, Ops | Telegram file-id media works; no platform-owned object lifecycle. Decide provider, environment separation, credentials, retention/deletion, RPO/RTO, budget and incident owner. No Media/R2 implementation is authorized by this inventory. | All media surfaces / security, durability and cost risk. | User/product/operations decision. | S decision / NO runtime migration; architecture/ops docs; D; before any upload foundation; P1 blocker at trigger. |
| `MEDIA-INFO-UPLOAD-001` — Mini App Photo/PDF management | `BLOCKED_PREREQUISITE` | MRWI; Media, Venue | Bot OWNER/MANAGER can author Telegram-file-id info media and Guest/Preview proxy is guarded. Browser upload/picker, scanning, object lifecycle and Mini App management are absent. | Venue self-service/Guest content / Bot-only authoring. | Storage decision and foundation. | XL / YES; media DB/API/storage/Venue+Guest UI; security F+A+S; after decision; P1 media slice. |
| `MEDIA-STRUCTURED-MENU-001` — item photos/thumbnails | `BLOCKED_PREREQUISITE` | MRWI; Media, Menu | Structured menu/options and ordering work; item media schema/API/UI do not. Add lifecycle-safe item images and thumbnails; descriptions remain separate. | Guest conversion/Venue presentation / blind ordering. | Media foundation. | L/XL / YES; menu/media DB/API/Bot/Mini Apps; PG+security F+A+S; after foundation; P2. |
| `MEDIA-STAFF-PHOTO-001` — consented staff profile photos | `DEFERRED_AFTER_MVP` | MRWI; Media, Staff, Security | Staff profiles and Guest-safe projection work without photos. Add consent, moderation, revocation and retention before any upload. | Guest trust/Staff identity / privacy and employment risk. | Media foundation and consent policy. | L / YES; staff/media DB/API/UI; privacy F+A+S; explicit venue demand; P3. |
| `MEDIA-PROMOTION-MIGRATION-001` — promotion banners on shared media | `BLOCKED_PREREQUISITE` | MRWI; Media, Growth | Promotion text/rules and current banner-related drafts exist; shared object lifecycle does not. Migrate only after foundation with deletion/reference and rollback policy. | Venue promotions / broken assets and storage leaks. | Media foundation and promo audit. | L / YES; promotion/media DB/API/UI; PG+F+A+S; after first media slice; P2 blocked. |
| `MEDIA-ADVANCED-001` — later option/flavor, galleries and processing | `DEFERRED_AFTER_MVP` | MRWI; Media, Menu | First-slice requirements are documented but unbuilt. Defer option/flavor media, Photo-menu subsections, quieter bulk Bot upload, scanner/backfill, video/editor/CDN/optimization/AI/galleries. | Rich content / major cost, moderation and scope explosion. | Stable foundation plus measured scale. | XL / YES; media pipeline/all UIs/ops; security/performance F+A+S; after production usage; P3. |

### 13.8 Booking and visit lifecycle

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `BOOKING-REMINDER-ROLLOUT-001` — operational reminder enablement | `BLOCKED_PRODUCT_DECISION` | MRWI; Booking, QA, Ops | Worker, quiet hours, dedupe and one controlled real smoke work; feature remains disabled by default. Approve owner/runbook/monitoring, management visibility and fresh delivery smoke. | Guest attendance/Venue planning / silent sends or missed reminders. | Notification policy and ops owner. | S/M / NO; worker/config/UI/ops; D+F+A+RT; approved controlled rollout; P1 readiness. |
| `BOOKING-NO-SHOW-AUTOMATION-001` — explicit expiry/no-show semantics | `BLOCKED_PRODUCT_DECISION` | MRWI; Booking, Venue | Worker expires overdue eligible bookings and staff can mark confirmed arrival/no-show. Decide EXPIRED versus NO_SHOW timing, grace/recovery and notifications. | Guest/Venue fairness / wrong status and analytics. | Product/operations policy. | M / POSSIBLE; booking worker/API/copy/events; D+F+A+S; before automatic no-show; P1 correctness. |
| `BOOKING-QUEUE-POLISH-001` — needs-action booking workspace | `DEFERRED_AFTER_MVP` | MRWI; Booking, Venue | Queue, lifecycle, hold/deadline and state-aware actions work. Add full filters/history, overdue/needs-action/reminder views and clearer guest-versus-venue cancellation copy using already persisted actor facts. | Venue operations / slower handling and ambiguous cancellations. | Automation/copy decisions. | M / NO/POSSIBLE; booking queries/UI; F+E2E+A+S; at queue volume; P2. |
| `BOOKING-DISPLAY-LABEL-001` — stable distinguishable booking identity | `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED` | MRWI; Booking, Communication | Existing authoritative `display_number` stays unique per venue service day. One shared reader formats `Бронь №N · dd.MM.yyyy, HH:mm` in venue time with stable `booking_id` fallback across Guest/Venue booking DTOs, conversations and Telegram; no migration or DOM index. | Guest/Venue clarity / removes indistinguishable same-number bookings across service days. | Independent review, green Actions and fresh cross-surface staging smoke. | S / NO; backend/Mini App/Telegram/tests/docs; F+E2E+A+S+RT; bounded local implementation; P1 UX correctness. |
| `BOOKING-INBOX-DISCOVERABILITY-001` — explicit Venue conversation workspace | `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED` | MRWI; Communication, Venue | Venue nav is `Брони` for management, `Переписки` for `BOOKING_THREAD` + `VENUE_CHAT`, and `Поддержка` for `SUPPORT_TICKET`. Type/label/preview/time and deterministic unread-first ordering are implemented without changing routes, thread types or RBAC. | Owner/Manager discoverability / new Guest messages are no longer hidden between three ambiguous sections. | Independent review, green Actions and Venue staging smoke. | S / NO; Venue Mini App/support reads; F+E2E+A+S; bounded local implementation; P1 operator UX. |
| `BOOKING-UNREAD-NOTIFICATION-001` — scoped unread and venue radar | `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED` | MRWI; Booking, Telegram, Security | `support_thread_reads.last_read_message_id` is actor/thread scoped and the sole unread authority; `last_read_at` is metadata only. Exact open locks the canonical parent, snapshots `MAX(message.id)`, advances the cursor monotonically and returns detail in one transaction. User-visible NULL-author system messages are foreign to every actor. Aggregate nav count hard-clamps `BOOKING_THREAD` + `VENUE_CHAT`; booking cards use exact counts. Guest routes map only fixed server-owned `CONVERSATIONS` or `SUPPORT` surfaces into the locked transaction before facts or marker mutation. A committed Guest booking message may atomically enqueue one deduplicated fact-only alert to the canonical venue's linked staff-chat, with exact thread URL and no raw message text; missing/disabled configuration safely skips it. PostgreSQL V126/H2 V127 add the nullable cursor and `(thread_id, id)` index without default or backfill. | Venue response speed / visible unread without support mixing or privacy leak. | Independent review, green Actions, drained single-new-image additive migration rollout, staging redeploy and real linked-group smoke; Telegram delivery ambiguity/history remains existing ops follow-up. | M / YES; support-read schema/repo/outbox/Telegram/Mini App; PG+F+E2E+A+S+RT; bounded local implementation; P1 operations/privacy. |
| `BOOKING-THREAD-UNIQUENESS-001` — one booking conversation thread | `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED` | MRWI; Booking, Communication | Global `booking_id` is canonical. PostgreSQL V124/H2 V125 preserve lossless reads and remap only the proved top-level audit `ticketId`; recursive unknown thread/ticket/conversation reference keys fail before mutation and preflight shares the predicate. Booking writers/read markers keep parent-first order. All four Mini App booking surfaces reconcile inventory/messages after recreate before exposing first-send/reply, while strict connection-aware booking outbox collisions remain separate from legacy key-only enqueue. | Guest/Venue privacy / prevents split conversations, lost read/audit evidence and duplicate messages/notifications. | One final short independent review, green Actions, production preflight and staging migration/smoke. Defensive savepoint review remains separately open. | M / YES; support-thread schema/repo/routes/outbox/Telegram/Mini App; PG+F+E2E+A+S+RT; bounded MVP locally complete; P1 integrity. |
| `BOOKING-AUDIT-EVENTS-001` — complete private booking evidence | `OPEN_CONFIRMED` | MRWI; Booking, Analytics, Security | Lifecycle statuses, reasons and cancellation actor are persisted; create/change/confirm/cancel/seated/no-show/reminder/chat lack a complete privacy-safe event/audit matrix. | Venue/Platform observability / unreconstructable actions. | Analytics envelope. | M/L / POSSIBLE; booking emitters/audit/events; F+A+S; before automation/dashboard; P1. |
| `QA-BOOKING-ISOLATION-001` — real two-account booking isolation | `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED` | MRWI; Booking, QA | Production-path routes cover two guests, two venues and distinct bookings. Real PostgreSQL proves exact independent waiter/blocker PIDs, ungranted lock evidence and one same-key post-state. Telegram parity plus Mini App double-click, lost-response reload reconciliation, edit and account/venue/thread context invalidation are automated. Foreign Guest/Venue and Platform booking access fail before message exposure. | Guest privacy / prevents cross-account and cross-tenant disclosure. | One final short independent review, green Actions and bounded two-account real Telegram/Mini App staging smoke. | S/M / NO; QA/routes/Telegram/Mini App; F+PG+E2E+A+S+RT; local closure complete, release smoke pending; P1. |
| `BOOKING-PREORDER-001` — controlled preorder lifecycle | `BLOCKED_PREREQUISITE` | MRWI; Booking, Core, Growth | Booking, menu/order and `getVisitCount` foundations work; no preorder eligibility, snapshot/repricing, cutoff, acceptance/fulfillment/payment or cancellation policy exists. | Guest convenience/Venue prep / stale menu and unpaid waste. | Stable order/tab, promo compatibility and product policy. | XL / YES; booking/order/menu DB/API/Bot/Mini Apps; D+PG+F+A+controlled S; approved use case; P2 blocked. |

Independent review registry for the bounded booking implementations:

| Finding | Status | Evidence / remaining trigger |
| --- | --- | --- |
| `BOOKING-DEDUP-READ-001` | `DONE` | Production PostgreSQL/H2 migrations prove no-read and exact-identical-read merge, exact survivor metadata/reference preservation, and unchanged state for partial/different read evidence; real writer-first and migration-first serialization closes the live-snapshot race. |
| `BOOKING-WRITER-CONVERGENCE-001` | `DONE` | Guest/Venue generic replies, booking-specific Venue route and both Telegram directions use the booking-specific transaction. Locked concurrent-close rejection, positive generic type allowlisting, actor/metadata denial, rollback and ordinary `VENUE_CHAT`/`SUPPORT_TICKET` regression are automated. |
| `BOOKING-CI-FLOOR-001` | `DONE` | Existing workflow uses exact FQCN selectors and JUnit XML files with factual minima, including legacy/strict outbox, message/PID-lock and real migration-lock suites; missing/below-minimum/skipped/failed/errored reports fail. The current closure raises the structured Playwright floor to `216`, requires the shared-fixture label parity, missing-configuration notifier and four exact Guest surface/deep-link/stale-response guards, and registers exact cursor migration/read-race, formatter, booking idempotency, notifier and outbox XML minima; Docker remains mandatory for PostgreSQL gates. |
| `BOOKING-PG-EVIDENCE-001` | `DONE` | Deterministic independent-PID race proves both callers return one authoritative id and exact booking/venue/guest/type/status post-state with zero messages and relevant audits. |
| `BOOKING-MINIAPP-OUTBOX-001` | `DONE` | Every Mini App booking reply has a persisted scope-bound opaque identity; message, thread state and canonical notification outbox row use one JDBC transaction. Strict booking replay/collision remains separate from legacy key-only enqueue. The prior exact PostgreSQL `11/11` and Playwright `206/206` evidence remains closed; subsequent unread/notifier and surface-guard coverage raises current CI floors to PostgreSQL booking idempotency `17` and Playwright `216` and still awaits review/Actions/staging smoke. |
| `BOOKING-DEDUP-AUDIT-REF-001` | `LOCAL_FIX_REVIEW_REQUIRED` | PostgreSQL and preflight retain semantic top-level `ticketId` handling. H2 now uses the same duplicate-aware semantic policy helper for validation/extraction/remap, independent of JSON key order, whitespace and safe extra fields. Recursive unknown-key coverage remains separate under `BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001`; independent review is still required. |
| `BOOKING-MIGRATION-SNAPSHOT-001` | `DONE` | PostgreSQL V124 takes bounded pre-guard `EXCLUSIVE` locks in canonical table order. The exact real Flyway `2/2` gate proves writer-first fail-closed reread and migration-first safe serialization with distinct blocked PIDs. |
| `BOOKING-READ-LOCK-ORDER-001` | `DONE` | Parent-first `bookings -> support_threads -> support_thread_reads` or `support_threads -> support_thread_reads` ordering remains closed. The current exact repository `21` and independent-PID PostgreSQL `6` suites additionally prove monotonic message-id cursor authority, NULL-author policy, writer-started-earlier blocking, equal-timestamp ordering, rollback and actor/thread isolation. |
| `BOOKING-UNREAD-NULL-AUTHOR-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Every user-visible `support_messages.author_user_id = NULL` row is a system message and is foreign to every actor. The null-safe unread predicate is shared by exact thread/card and aggregate Venue conversation paths; authoritative exact open clears the cursor without expanding visibility/RBAC. Repository and real PostgreSQL evidence, including raw marker fields and `SUPPORT_TICKET` aggregate exclusion, requires the next independent review. |
| `BOOKING-UNREAD-GUEST-TYPE-GUARD-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Guest routes now select only the server-owned `CONVERSATIONS = BOOKING_THREAD + VENUE_CHAT` or `SUPPORT = SUPPORT_TICKET` contract. Ordinary Guest and confirmed Platform Guest-context calls recheck locked ownership and type before message snapshot, marker mutation or detail disclosure; wrong-surface raw-marker and crafted deep-link cases await independent review. |
| `BOOKING-UNREAD-MIXED-VERSION-ROLLOUT-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Canonical booking, QA and deployment docs record the additive V126 mixed-binary limitation, mandatory backup/drain/single-new-image/Flyway-head cutover, no schema downgrade or Flyway repair, forward-fix rollback, and bounded NULL-author/type-guard/account-isolation staging smoke. No migration, deploy or smoke is claimed. |
| `BOOKING-UNREAD-TIMESTAMP-AUTHORITY-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Unread now depends only on foreign author plus NULL/`id > last_read_message_id`; `last_read_at` is metadata. Exact open snapshots `MAX(id)` under canonical locks and returns marker plus detail in one transaction. PostgreSQL V126/H2 V127 add a nullable cursor with no backfill. Independent review and rollout remain required. |
| `BOOKING-UNREAD-PG-RACE-COVERAGE-001` | `LOCAL_FIX_REVIEW_REQUIRED` | The exact PostgreSQL `6` gate includes the production NULL-author unread/open marker case plus writer-started-earlier lock evidence with independent PIDs/`pg_locks`/`pg_blocking_pids`, equal-`created_at` id ordering, monotonic concurrency, isolation and rollback. Docker remains mandatory; independent review is pending. |
| `BOOKING-LABEL-TS-PARITY-COVERAGE-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Kotlin `2` and the dedicated TypeScript/Playwright cases consume one shared JSON fixture through production helpers, including invalid/missing timezone fallback and browser-timezone invariance. Independent review is pending. |
| `BOOKING-E2E-NOTIFIER-COVERAGE-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Backend integration owns outbox cardinality/failure/replay/unlink races; Playwright owns visible badges, exact deep links/navigation and safe mock counters; real Telegram delivery remains a staging-smoke assertion. Current minima are PostgreSQL booking idempotency `17`, notifier `5`, outbox `8` and structured Playwright `216`. |
| `BOOKING-DOC-CURRENT-SLICE-DRIFT-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Canonical status, lifecycle, communication, security, staff-chat and QA surfaces name the UX/discoverability slice as current and keep the preceding V124/V125 integrity slice explicitly historical and separately review-required. Independent review is pending. |
| `BOOKING-CLIENT-ID-RELOAD-RECONCILIATION-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Guest/Venue dedicated and generic booking surfaces use explicit loading/no-thread/with-thread/error states. A complete bounded batch GET returns one explicit result per authorized booking id; capped/partial inventory cannot prove no thread. Deterministic >100-thread and reload-after-commit coverage remains subject to independent review. |
| `BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Recursive decoded NFKC key guards fail before mutation for nested/escaped `conversationThreadId`, `thread_id`, array `ticketIds` and numeric strings; unrelated `conversationStatus` passes. Local migration and policy evidence requires independent review. |
| `BOOKING-MINIAPP-IDEMPOTENCY-PG-EVIDENCE-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Independent callers plus observer locally prove exact backend PIDs, an ungranted waiter lock and exact caller A in `pg_blocking_pids`; release yields one message/outbox/logical thread update and no duplicate reads/audits. Independent review is still required. |
| `BOOKING-DOC-PREVERDICT-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Current status surfaces claim only local fixes and local validation. They do not claim a final independent verdict, absence of P0/P1, green Actions, staging evidence or production readiness. |
| `BOOKING-OUTBOX-LEGACY-DEDUPE-SEMANTICS-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Legacy suspend enqueue locally remains key-only no-op on duplicate keys, including changed envelopes; only the connection-aware booking API enforces canonical exact-envelope collisions. Independent review is still required. |
| `BOOKING-MESSAGE-MIGRATION-METADATA-001` | `LOCAL_FIX_REVIEW_REQUIRED` | Local H2/PostgreSQL migration evidence asserts nullable `VARCHAR(64)`, no default, legacy NULL rows, exact unique-column order, PG partial predicate, H2 equivalent shape, no extra semantic objects and actual migration heads. Independent review is still required. |
| `BOOKING-CI-PLAYWRIGHT-FLAKE-001` | `OPEN` | An earlier structured run executed `197/198` after one unrelated favorite-test failure. The integrity pass first executed `205/206`, and this discoverability pass first executed `207/208`, after the same unrelated catalog virtual-clock debounce timing failure; each focused rerun and second full run passed (`206/206`, then `208/208`). The subsequent read-guard run passed structured `209/209`, the cursor/label/notifier slice passed `212/212`, and the Guest surface/deep-link/stale-response closure passed the raised full floor at `216/216`; trigger the next Mini App CI-hardening pass or a repeated same failure in GitHub Actions. Green reruns do not erase the evidence. |
| `BOOKING-SAVEPOINT-COLLISION-001` | `OPEN` | Unique-conflict recovery is defensive-only under the current canonical booking-row lock. Keep it until a separate review proves it can be removed safely. |

### 13.9 Growth and retention

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `REPEAT-MANUAL-001` — real Repeat Phase 1 evidence | `BLOCKED_PREREQUISITE` | MRWI; Deferred Smoke, Growth, Core | Transient server-resolved Repeat is locally validated. Complete prescribed two-venue/two-user/real-QR/menu-change smoke without expanding implementation. | Guest confidence / environment-only regressions. | Required fixtures/accounts. | S manual / NO; QA/Guest/Telegram; exact smoke; when fixtures exist; P1 existing release-confidence gate. |
| `GROWTH-GIFT-RELEASE-001` — close Gift With Item release gates | `OPEN_CONFIRMED` | MRWI; Growth, QA, roadmap | Backend/Bot/Mini App implementation and local focused evidence exist. Independent review, green Actions, deploy and bounded application/bill/history smoke are not recorded closed. | Guest/Venue promotion / release uncertainty. | No new product scope. | S / NO; review/CI/staging; A+S; current closure opportunity; P1. |
| `GROWTH-PROMO-COMPAT-001` — deterministic reward/discount compatibility | `BLOCKED_PRODUCT_DECISION` | MRWI; Growth, Security, Core | Happy Hours/Gift work and generic rule fields include stackable/conflict group; current candidate paths still hardcode behavior. Approve STACKABLE/EXCLUSIVE/OVERRIDE, priority/tie-break and manual/loyalty/code policy. | Guest bill/Venue revenue / accidental stacking. | Financial semantics decision. | L / POSSIBLE; promo engine/orders/bills/UI; D+F+A+S; before codes/advanced loyalty; P1. |
| `SECURITY-PROMO-CONFIG-AUDIT-001` — transaction-bound promotion edit audit | `OPEN_CONFIRMED` | MRWI; Growth, Security | Promotion create/status/archive audits work. Effective configuration edits still need one safe old/new, actor, venue-scoped atomic audit. | Venue accountability / financial changes cannot be reconstructed. | Mutable-field and compatibility contract. | S/M / NO; promotion repo/routes/audit; rollback F+A+S; before wider executable promos; P2. |
| `GROWTH-PROMO-UX-HARDENING-001` — race-safe accessible promotion lists | `OPEN_CONFIRMED` | MRWI; Growth, QA | Current/archive tabs, lifecycle cards and effective state are closed. Fix loading empty-state, two-query transition snapshots, non-color selection, keyboard E2E, time-boundary live refresh and expired-card action decision. | Venue UX/correctness / stale, duplicate or inaccessible cards. | Small expired-action decision. | M / NO; Venue UI/API snapshots/E2E; F+E2E+A+S; bounded quality epic; P2. |
| `GROWTH-FAVORITE-MENU-001` — menu-item favorites in Mini App | `OPEN_CONFIRMED` | MRWI; Growth, Product Spec | V79, repository/API and Telegram item-favorite flows work. Add Guest Mini App item UI/account list and decide option-level/unavailable restoration semantics. | Guest retention/reorder / current foundation is Bot-heavy. | Stable item/option identity. | L / POSSIBLE; favorites DB/API/Guest UI/Telegram; F+E2E+A+S; after venue favorites; P2. |
| `GROWTH-REPEAT-LIBRARY-001` — persistent named repeat templates | `DEFERRED_AFTER_MVP` | MRWI; Growth, Core | Transient repeat plan revalidates current menu and never auto-orders. Add owned named templates, edit/delete and stale-menu reconciliation only after real smoke. | Guest convenience / stale identity/price assumptions. | `REPEAT-MANUAL-001`. | M/L / YES; DB/API/Guest/Telegram; PG+F+A+S; after Phase 1 production proof; P2 deferred. |
| `GROWTH-HISTORY-SNAPSHOT-AUDIT-001` — verify immutable historical base facts | `UNKNOWN_NEEDS_RESEARCH` | MRWI; Growth, Core | Visit entity, list/detail, merge, option and promotion facts work. Audit whether any legacy/current base name/price detail still depends on mutable menu data before promising immutable history. | Guest trust/Repeat fidelity / incorrect historical totals or labels. | Read-only evidence first. | S/M research / POSSIBLE; visit/order repo/migrations; no runtime change until gap proven; before persistent repeat; P2 research. |
| `GROWTH-FEEDBACK-AUTOMATION-001` — consent-safe feedback prompts | `DEFERRED_AFTER_MVP` | MRWI; Growth, Telegram | Manual History feedback, low-rating VENUE_CHAT and manual 5/5 review CTA are closed; automated worker remains disabled. Add opt-in, caps, suppression and idempotent scheduling; never auto-redirect publicly. | Venue feedback/Guest trust / spam and consent risk. | Notification consent/delivery. | M / POSSIBLE; worker/outbox/consent; F+A+controlled RT; only after consent; P3. |
| `GROWTH-NOTIFICATION-CONSENT-001` — persisted scopes and unsubscribe | `OPEN_CONFIRMED` | MRWI; Growth, Product Spec, Security | Transactional sends are separately gated and no marketing campaign runtime was found. Define operational versus marketing scopes, evidence/version/source/time, preferences, revoke and suppression. | Guest privacy/retention / non-compliant messaging. | Legal/product consent policy. | L / YES; consent DB/API/UI/Telegram/workers; PG+privacy F+A+S; before any campaign/prompt; P1. |
| `GROWTH-CAMPAIGNS-001` — safe segmentation and campaigns | `BLOCKED_PREREQUISITE` | MRWI; Growth, Analytics | Favorites/history/promotions exist; marketing orchestration does not. Add audience, draft/approval/schedule, suppression/caps, delivery/results and abuse controls. | Venue revenue/Guest retention / spam and reputation risk. | Consent, event coverage, delivery reliability. | XL / YES; growth DB/API/Venue UI/outbox/analytics; PG+F+A+controlled S; approved use case; P2 blocked. |
| `GROWTH-RECOMMENDATIONS-001` — explainable frequent-item suggestions | `DEFERRED_AFTER_MVP` | MRWI; Growth, Analytics | History, favorites, repeat and catalog foundations work. Add frequent-item aggregation, taste/profile policy, substitutions and measurable relevance without private leakage. | Guest discovery / weak or intrusive recommendations. | Real data volume and privacy policy. | L/XL / POSSIBLE; analytics/service/Guest UI; privacy/relevance F+A+evaluation; after data volume; P3. |
| `GROWTH-CATALOG-SCALE-001` — large-catalog discovery and ranking | `DEFERRED_AFTER_MVP` | MRWI; Growth, QA, Product Spec | Search/filter Phase 1 is staging-closed on limited data. Pagination/ranking/map/geo and large-pilot behavior remain; environment smoke is separately tracked. | Guest discovery / degraded results at scale. | Representative dataset and ranking decision. | L / POSSIBLE; catalog queries/API/UI; performance F+A+S; at dataset growth; P2/P3. |

### 13.10 Advanced monetization, loyalty and ads

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `GROWTH-PROMO-CODE-001` — limited, auditable promo codes | `BLOCKED_PREREQUISITE` | MRWI; Growth, Product Spec | PROMO_CODE exists only as a template/check-constraint foundation. Build code issue/input, scope/time/user/global limits, idempotency, accounting, abuse guard and bill/history only after compatibility. | Guest acquisition/Venue conversion / fraud and discount leakage. | `GROWTH-PROMO-COMPAT-001` and limit policy. | L/XL / YES; promotions/orders/bills/Bot/Mini Apps; PG+F+A+S; approved campaign; P2 blocked. |
| `GROWTH-LOYALTY-PRODUCTIZE-001` — reconcile existing Nth Hookah loyalty | `UNKNOWN_NEEDS_RESEARCH` | MRWI; Growth, Product Spec, Security | V98-V100, ledger/progress/redemption, order accrual and Telegram owner/guest flows exist despite docs saying future. Determine enablement, ledger/RBAC/compatibility/migration/release evidence and Mini App parity before any status claim. | Guest/Venue value / undocumented financial runtime. | Promotion compatibility and product decision. | L/XL research / POSSIBLE; loyalty/order DB/Bot/Mini Apps/docs/QA; dedicated audit then A+S; before pilot claims; P1 research. |
| `GROWTH-LOYALTY-ADVANCED-001` — points, cashback and tiers | `BLOCKED_PRODUCT_DECISION` | MRWI; Growth, Product Spec | Bounded Nth Hookah foundation exists; points/cashback/tier liability model does not. Define earn/redeem/expiry/refund/accounting/anti-abuse and compatibility. | Retention/revenue / financial liability and fraud. | Productized bounded loyalty and legal model. | XL / YES; loyalty/billing/orders/UI/analytics; D+PG+property/concurrency F+A+controlled S; later business case; P3. |
| `GROWTH-REFERRAL-001` — referral rewards with anti-abuse | `DEFERRED_AFTER_MVP` | MRWI; Growth, Product Spec | No complete referral runtime/schema. Define attribution, qualifying event, reversals, limits and self/device/account abuse. | Acquisition / reward fraud and liability. | Loyalty/promo compatibility, analytics, consent. | L/XL / YES; DB/Guest/Telegram/rewards/events; PG+F+A+cohort S; approved model; P3. |
| `GROWTH-PAID-PLACEMENT-001` — transparent paid placement | `BLOCKED_PRODUCT_DECISION` | MRWI; Growth, Platform | Manual Telegram placement request/moderation/feed foundation works. Decide editorial versus paid; paid requires ad labels, budget/billing, moderation, analytics, refunds/disputes and ordering. | Platform revenue/Venue acquisition / advertising and billing risk. | Commercial/legal policy, provider, analytics. | XL / POSSIBLE; placements/billing/Bot/Mini Apps/support; D+F+A+S; before charging; P2 decision. |
| `GROWTH-PROMO-REWARDS-ADVANCED-001` — additional executable rewards | `DEFERRED_AFTER_MVP` | MRWI; Growth, Core | Informational promos, Happy Hours and Gift foundations work. BOGO/X+Y, special/fixed price, free option/refill and accounting/history do not. | Venue merchandising / combinatorial bill errors. | Compatibility and configuration audit. | XL / POSSIBLE; promo engine/orders/bills/UI; matrix F+A+S; proven venue demand; P3. |
| `STAFF-TIPS-001` — legally bounded external staff tip intent | `BLOCKED_PRODUCT_DECISION` | MRWI; Staff, Security, Product Spec | Staff public-card foundation exists; no tip runtime. Decide external link plus intent/consent/moderation; platform payment, provider payout, Stars and crypto remain outside MVP unless separately approved. | Guest convenience/Staff income / legal, fraud and proof-of-payment risk. | Legal/product/payment boundary. | L / YES likely; staff DB/API/Guest UI/audit; D+PG+privacy F+A+S; approved venue pilot; P3. |

### 13.11 QA, operations and documentation debt

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `CATALOG-SEARCH-MANUAL-001` — representative multi-venue catalog smoke | `BLOCKED_PREREQUISITE` | MRWI; Deferred Smoke, Growth | Limited-dataset Search/Filter Phase 1 is closed. Execute the existing extended scenario only with meaningful multi-venue data. | Guest discovery / scale regressions unobserved. | Representative dataset. | S manual / NO; QA/catalog; exact manual evidence; before ranking/scale work; P2 existing. |
| `STAFF-IDENTITY-MANUAL-001` — free-member identity linking smoke | `BLOCKED_PREREQUISITE` | MRWI; Deferred Smoke, Staff | Identity linking/concurrency and released paths are closed. The exact free-member create-from-member manual scenario lacks a suitable account fixture. | Venue staffing / one environment path unverified. | Suitable account/environment. | S manual / NO; QA/Venue Staff UI; exact smoke; when fixture exists; P2 existing. |
| `QA-COVERAGE-EXPANSION-001` — next risk-based browser/test targets | `UNKNOWN_NEEDS_RESEARCH` | MRWI; QA, current domain docs | Broad backend and Playwright coverage exists; docs still say frontend/scenario coverage partial without one bounded priority. Produce a risk/route matrix before adding tests. | Product quality / either blind spots or low-value test sprawl. | Master inventory priority. | M research / UNKNOWN; QA/tests/CI; read-only selection then F; before broad test epic; P2. |
| `QA-PILOT-READINESS-001` — consolidated 1-3 venue pilot gate | `OPEN_CONFIRMED` | MRWI; roadmap launch gate, QA, Ops | Many bounded staging smokes are closed; the roadmap still requires one coherent Guest/Venue/Platform pilot, no known P0 auth/money leak, monitoring and incident ownership. | All users/business / slice-green but system-unready pilot. | Selected pilot venues and open P1 verdicts. | M manual/ops / NO; all surfaces/QA/ops; A+S+RT; before market pilot claim; P1 launch gate. |
| `OPS-PRODUCTION-RUNBOOK-001` — verified production deploy/rollback/log commands | `OPEN_CONFIRMED` | MRWI; Deployment, Product Spec, Ops | Staging runbook/deploy is concrete; exact production deploy, previous-image rollback and log commands remain `needs verification`. | Platform operations / slow unsafe release recovery. | Production environment/authority. | M / NO; runbooks/deploy scripts only when separately authorized; verification gates; before production release; P1 ops. |
| `OPS-BACKUP-RESTORE-001` — backup policy and restore drill | `OPEN_CONFIRMED` | MRWI; Deployment, Ops | Migration policy exists; exact production backup/restore commands, storage, retention and restore drill do not. | All data / unrecoverable incident. | Production DB/storage ownership. | M / NO runtime migration; ops/runbook; controlled drill; before production data; P1 durability. |
| `OPS-OBSERVABILITY-001` — production logs, alerts and incident baseline | `OPEN_CONFIRMED` | MRWI; Deployment, Platform, Analytics | Health endpoints and some diagnostics work. Define production log access/retention, alert thresholds, outbox/webhook/provider/DB signals and incident ownership without secrets. | Platform reliability / late diagnosis. | Environment and telemetry inventory. | M/L / POSSIBLE; backend/infra/runbook; failure-state verification; before on-call/pilot scale; P1. |
| `OPS-TELEGRAM-ADMIN-TOOLING-001` — safe webhook/outbox/staff-chat diagnostics | `OPEN_CONFIRMED` | MRWI; Deployment, Telegram | Current runbooks use manual API/SQL and working notifier primitives. Add bounded registration/status/replay/diagnostic workflows with auth and payload redaction. | Platform support / risky manual intervention. | Webhook strategy and audit policy. | M/L / POSSIBLE; admin endpoints/CLI/runbook; security F+A+staging; before webhook/notification scale; P2. |
| `OPS-BILLING-DIAGNOSTICS-001` — provider replay/rotation runbook | `BLOCKED_PREREQUISITE` | MRWI; Deployment, Billing | Generic webhook foundation exists; provider-specific dashboard, test event, replay, reconciliation and credential rotation cannot be finalized without a provider. | Platform money operations / weak incident response. | `BILLING-PROVIDER-001`. | M / UNKNOWN; provider/ops/runbook; sandbox verification; alongside provider rollout; P1 blocked. |
| `OPS-WEBHOOK-ROLLOUT-001` — Telegram webhook operating mode | `DEFERRED_AFTER_MVP` | MRWI; Deployment, Telegram | Long polling and staging operations are stable; webhook rollout is intentionally deferred. Add public HTTPS registration, secret verification, rollback and monitoring only when chosen. | Platform scale/reliability / premature infra risk. | Stable HTTPS and ops tooling. | M / POSSIBLE; Telegram config/ops; F+A+staging failover; scale trigger; P3. |
| `OPS-ORDER-IDEMPOTENCY-PROD-ROLLOUT-001` — authorized V123/V124 production boundary | `DEFERRED_AFTER_MVP` | MRWI; Core, Migration, Deployment | Fingerprint/idempotency migrations and code are ready/validated outside production. Apply only in an authorized production release with backup/rollback evidence. | Order integrity / rollout risk, not missing code. | Production release authority. | S ops / migration already YES; DB/deploy; migration health+smoke; production release; P2 operational. |
| `OPS-SSH-ROOTCAUSE-001` — identify fresh-SSH failure cause | `UNKNOWN_NEEDS_RESEARCH` | MRWI; Deployment, roadmap | ControlMaster workaround and staging deploy are closed; server/network cause of dropped fresh connections remains unproven. | Release reliability / recurrence under new conditions. | Recurrence or production hardening window. | Unknown / NO likely; SSH/network/runbook; diagnostics only; at recurrence; P2 research. |

### 13.12 Explicitly deferred / not for MVP

| Stable ID / user-visible outcome | Status | Canonical owner; other sources | Current evidence; works → remains | User value / delay risk | Dependencies | Delivery |
| --- | --- | --- | --- | --- | --- | --- |
| `AI-CORE-HARDENING-001` — production-safe internal assistant | `OPEN_CONFIRMED` | MRWI; roadmap AI sections, Security | Read-only assistant, fake/OpenAI provider flags, tools, context, audit metadata and Staff denial work. Add enforced E2E rate limits, timeout/error/fallback UX, audit operations and no-mutation regression per tool. | Owner/Manager productivity / provider abuse or unintended writes. | Stable public-safe tools and ops. | M/L / NO/POSSIBLE; AI service/provider/Telegram/tests; F+A+controlled S; before enabling real provider broadly; P2. |
| `AI-OWNER-ONBOARDING-001` — draft-only setup assistant | `DEFERRED_AFTER_MVP` | MRWI; roadmap | AI drafting foundation exists. Later add setup/profile/menu-import/type/promotion/analytics/support drafts; user confirms and existing handlers write. | Venue onboarding / unsafe automation if rushed. | AI core hardening and mature write APIs. | L/XL / POSSIBLE; AI tools/Venue UI; safety F+A+S; after launch stabilization; deferred Phase 4. |
| `AI-GUEST-CONCIERGE-001` — public-data concierge in owned surfaces | `DEFERRED_AFTER_MVP` | MRWI; roadmap, Growth | Public catalog/menu/promotion foundations exist; no complete safe concierge. Limit to public facts/deep links and current authorized table context, never checkout/discount/order mutation. | Guest discovery / privacy and hallucinated facts. | Search/readiness, public tools, rate limits. | L/XL / NO/POSSIBLE; AI/Guest Bot/Mini App; safety/eval F+A+S; after public data quality; deferred Phase 5. |
| `AI-TELEGRAM-GUEST-MODE-001` — inline public discovery | `DEFERRED_AFTER_MVP` | MRWI; roadmap | Not required for launch. Needs reliable ranking/readiness/promotions/deep links, abuse controls and strict exclusion of private history/table/order/operations data. | Guest acquisition / public data leakage and abuse. | Guest concierge/public search. | XL / POSSIBLE; Telegram inline/AI/search; abuse/privacy F+A+S; after core launch; deferred Phase 6. |
| `AI-BUSINESS-BOTS-001` — consented Business inbox drafts | `DEFERRED_AFTER_MVP` | MRWI; roadmap | No Business connection runtime. Add explicit consent, draft replies, optional FAQ, per-message audit and pause/escalation; no autonomous booking/payment/order/staff actions. | Venue support / unauthorized outbound messages. | Consent, support and AI hardening. | XL / YES/POSSIBLE; Telegram Business/AI/audit; safety F+A+pilot; after core commercial stability; deferred Phase 7. |
| `AI-MANAGED-BOTS-001` — premium per-venue bot identities | `DEFERRED_AFTER_MVP` | MRWI; roadmap, Billing, Ops | Main bot works; per-venue managed bot lifecycle does not. Requires token vault, webhook isolation, per-bot config/rate limits, transfer/disable, tariff and support tooling. | Venue branding/revenue / secret isolation and support burden. | Provider billing, owner transfer, webhook ops. | XL / YES; bot platform/vault/billing/ops; security F+A+pilot; after main bot stability; deferred Phase 8. |
| `AI-BOT-AGENTS-001` — loop-safe internal bot agents | `DEFERRED_AFTER_MVP` | MRWI; roadmap | No bot-to-bot automation. Require trace id, depth/dedupe/rate/timeout/loop guards and audit before a bounded use case. | Platform automation / runaway loops and duplicated actions. | Mature event/audit and AI core. | XL / POSSIBLE; agents/events/audit; adversarial F+A+controlled S; late explicit use case; deferred Phase 9. |
| `AI-AUTONOMOUS-WRITES-001` — keep sensitive AI writes prohibited | `DEFERRED_AFTER_MVP` | MRWI; roadmap guardrails | Current assistant is read-only. Any future bill/order/discount/role/promotion/reply/menu/loyalty/placement/broadcast/booking/payment write requires explicit confirmation and existing handlers; no broad autonomous Goal is approved. | All users / catastrophic authority and money risk. | Separate use-case decision and safety case. | Unknown / UNKNOWN; all write domains; D+threat model+F+A+controlled S; no trigger approved; P3/not for MVP. |

### 13.13 Cleanup ledger: stale, duplicates and history

The following old claims are `STALE_ALREADY_IMPLEMENTED`; only the narrower remaining IDs above may
be reopened:

1. Active order scoped only by table and H2 uniqueness mismatch — PostgreSQL V61 and H2 V112 enforce
   table-session active-order fidelity; keep regression, while null personal-owner integrity remains
   `ORDER-PERSONAL-TAB-INTEGRITY-001`.
2. Cart recovery/idempotency missing — request fingerprints, concurrency coverage and typed atomic
   stale-cart rejection are present; only immutable recovery labels and cross-surface sync remain.
3. Full bill, display number, discounts/exclusions missing — Guest/Venue/Bot read committed tab bills,
   V59 display allocation and current adjustments; no backlog item exists without new regression.
4. Staff-call ACK/DONE or guest cancel missing — bounded lifecycle/audit releases are closed; only
   actor/timing/manual-cancel/reply polish remains.
5. Booking queue/hold/arrival/chat/reminder/expiry entirely missing — routes, workers and persisted
   threads exist; remaining work is rollout semantics, thread uniqueness, events and real isolation.
6. Visit entity/history/feedback and `visit_count` missing — V77, `VisitRepository`, History, feedback
   and `getVisitCount()` exist; snapshot research and automation are narrower future work.
7. Telegram multi-venue selector and Owner invite acceptance/revoke missing — V67 selector/context,
   membership checks, deep-link acceptance and last-owner-safe revoke are implemented and released.
8. Venue settings/stats/tables/QR/preview are only placeholders — multiple bounded screens are real;
   the inventory names only settings parity, advanced stats, table CRUD and QR audit gaps.
9. Structured option selection, Staff availability, Shift Check, initial bootstrap and menu mutation
   audits are absent — current flat option flow and released bounded menu slices exist; do not reopen.
10. Support Center/manual billing/renewal/courtesy are missing — released MVPs exist; advanced support,
    real provider and the two concrete billing correction/provenance gaps remain.
11. Favorite menu items, loyalty and placement foundations are wholly absent — each has material
    schema/backend/Telegram code. Their exact Mini App/productization/research outcomes are cataloged.
12. All promotion analytics are absent — `promotion_applied` is emitted; broader event coverage is
    still `ANALYTICS-EVENT-COVERAGE-001`.

Global duplicate merges (`DUPLICATE_OF_OTHER_ID`) include:

- force-close/session wording → `ORDER-SESSION-CLOSE-POLICY-001`; tab reopen/settlement →
  `ORDER-TAB-LIFECYCLE-001`;
- broad Dangerous Action Audit → the exact QR, menu-RBAC, promotion-config, callback, booking-event,
  invoice/lifecycle writers above; read exploration remains `PLATFORM-AUDIT-VIEWER-001`;
- settings partial → `VENUE-SETTINGS-PARITY-001` or deferred `VENUE-PUBLISH-WORKFLOW-001`;
- staff-call future rows → `STAFF-CALL-OPS-POLISH-001`; personal/group policy →
  `STAFF-COMMS-POLICY-001`; delivery visibility → `NOTIFY-DELIVERY-HISTORY-001`;
- booking automation → reminder rollout plus no-show policy; preorder wording →
  `BOOKING-PREORDER-001`;
- public-card Photo/PDF upload → `MEDIA-INFO-UPLOAD-001`; item photo/thumbnail →
  `MEDIA-STRUCTURED-MENU-001`; descriptions remain separate;
- Staff stop-list and Manager authority were not collapsed because they are two different decisions;
- manual smoke copies → only `REPEAT-MANUAL-001`, `CATALOG-SEARCH-MANUAL-001`,
  `STAFF-IDENTITY-MANUAL-001`, `QA-TG-FALLBACK-SMOKE-001`, `QA-BOOKING-ISOLATION-001` and
  `QA-STAFF-CHAT-PILOT-001`;
- Platform KPI claims → `ANALYTICS-PLATFORM-DASHBOARD-001`; event-name/coverage claims →
  `ANALYTICS-EVENT-COVERAGE-001`; audit reading is not write-path audit completeness;
- support diagnostics/attachments/macros/CSAT → `SUPPORT-ADVANCED-001`, while SLA/assignment remains
  `SUPPORT-AUTOMATION-001`;
- notification opt-in/suppression → `GROWTH-NOTIFICATION-CONSENT-001`; segmentation/favorites sends →
  `GROWTH-CAMPAIGNS-001`; cross-reward stacking → `GROWTH-PROMO-COMPAT-001`;
- paid placement claims → `GROWTH-PAID-PLACEMENT-001`; existing non-paid UI parity stays separately
  `PLATFORM-PLACEMENTS-PARITY-001`;
- persistent repeat/save-as-template → `GROWTH-REPEAT-LIBRARY-001`; legal/payer/primary-owner relink →
  `PLATFORM-OWNER-TRANSFER-001`;
- production deploy/rollback/log/backup repetitions → `OPS-PRODUCTION-RUNBOOK-001`,
  `OPS-OBSERVABILITY-001` and `OPS-BACKUP-RESTORE-001` according to distinct outcome.

`HISTORICAL_ONLY` groups are dated pre-implementation audit matrices, fulfilled selected-next prompts,
historical validation transcripts, unpromoted idea-scoring rows and older disabled-worker proposals.
They remain evidence/history and do not own current backlog state.

Five current P2/P3 registry items are deliberately preserved as open:

- `ONBOARDING-H2-001`;
- `ONBOARDING-TG-CONFIRM-001`;
- `ONBOARDING-DECISION-RETRY-001`;
- `MENU-CONC-001`;
- `MENU-TEST-002`.

### 13.14 Comparative shortlist — booking integrity selected and locally implemented

Scores remain comparative for future selection. `Readiness` includes absence of unresolved
decisions and availability of code/test foundations. The booking integrity row was the approved
bounded Goal and is now review-ready; this does not select another epic.

| Epic | Included master IDs | User/business value | Correctness/security | Operational value | Readiness | Effort | Migration risk | Scope-explosion risk | Staging demonstrability | Why now / why defer | Stop decisions |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- | ---: | --- | --- |
| Booking conversation integrity and real isolation — locally implemented | `BOOKING-THREAD-UNIQUENESS-001`, `QA-BOOKING-ISOLATION-001` | 4 — one trustworthy chat | 5 | 4 | 5 | M | Medium | Low | 5 | Bounded implementation and local proof complete; review, Actions, preflight and staging smoke remain. | Stop only if preflight finds null/cross-owner/conflicting-status legacy data. |
| Dangerous operational action hardening | `SECURITY-AUDIT-ACTOR-FK-001`, `MENU-RBAC-RACE-001`, `SECURITY-TABLE-QR-AUDIT-001` | 3 — fewer invisible failures | 5 | 5 | 4 | L | Medium | Medium | 4 | Closes concrete integrity/authority gaps; can defer only if risky actions stay low-volume. | Legacy audit actors; QR export policy. |
| Menu content and merchandising | `MENU-DESCRIPTIONS-001`, `MENU-FEATURED-001` | 5 — better choice and discovery | 3 | 4 | 4 | L | Medium | Medium | 5 | Highly visible Guest/Venue value on existing menu core; can wait if content quality is not pilot bottleneck. | Featured ordering/caps; audit payload. |
| Venue operations cockpit | `VENUE-DASHBOARD-001`, `VENUE-TABLE-CRUD-001`, `VENUE-SETTINGS-PARITY-001` | 4 — faster venue work | 4 | 5 | 4 | L | Low/Medium | Medium | 5 | Converts real screens into a coherent operating loop; defer while one venue can tolerate navigation. | Notification settings policy; table deactivation consequences. |
| Gift release and promotion UX hardening | `GROWTH-GIFT-RELEASE-001`, `SECURITY-PROMO-CONFIG-AUDIT-001`, `GROWTH-PROMO-UX-HARDENING-001` | 5 — visible promotion value | 4 | 4 | 5 | M | Low | Low/Medium | 5 | Most work/evidence already exists and staging result is obvious; defer if promotion launch is not current. | Expired-card action; mutable config allowlist. |
| Analytics event baseline and Platform KPIs | `ANALYTICS-EVENT-COVERAGE-001`, `ANALYTICS-PLATFORM-DASHBOARD-001`, `PLATFORM-AUDIT-VIEWER-001` | 4 — reliable decisions | 4 | 5 | 3 | XL | High | High | 3 | Unlocks funnel, health and campaigns; defer because event normalization can expand across every domain. | Event names/envelope/retention/KPI definitions. |
| Order/tab settlement lifecycle | `ORDER-PERSONAL-TAB-INTEGRITY-001`, `ORDER-TAB-LIFECYCLE-001`, `ORDER-SESSION-CLOSE-POLICY-001` | 5 — trustworthy bill/visit close | 5 | 5 | 2 | XL | High | High | 5 | Highest core correctness leverage; defer until settlement and force-close decisions are explicit. | Paid/reopen semantics; physical-session ownership; legacy rows. |
| Production billing provider and safe recovery | `BILLING-PROVIDER-001`, `BILLING-SUSPENSION-PROVENANCE-001`, `BILLING-INVOICE-VOID-REISSUE-001`, `OPS-BILLING-DIAGNOSTICS-001` | 5 — online revenue | 5 | 5 | 2 | XL | High | High | 4 | Mandatory before online collection; defer while manual billing is commercially acceptable. | Provider/legal contract, refunds/chargebacks, secret owner. |

Decision lenses:

- safest bounded next epic: **Booking conversation integrity and real isolation**;
- highest direct user value: **Menu content and merchandising**;
- strongest quality/security value: **Dangerous operational action hardening**;
- fastest conspicuous result: **Gift release and promotion UX hardening**;
- recommended balance: **Booking conversation integrity and real isolation** — a concrete P1
  concurrency/privacy gap, clear migration/test boundary, strong staging demonstration and no broad
  product redesign. This is a recommendation only, not an approved `NEXT`.

Final decision state: **NEXT EPIC DECISION / MASTER REMAINING WORK INVENTORY PREPARED /
USER DECISION REQUIRED**. No implementation-ready prompt is created for any shortlist candidate.
