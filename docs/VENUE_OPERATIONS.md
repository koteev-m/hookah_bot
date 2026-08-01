# Venue Mode Operations Model

Дата актуализации: 2026-08-01.

Статус: **current product reference / SPEC UPDATED**. Core Venue operations are partly smoke-closed across orders, bill display, staff calls, bookings, confirmed-only booking arrival actions, state-aware staff-chat booking shortcuts, staff-chat, menu options and settings slices. The bounded shift-check slice is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**. The full Venue Mode implementation is still **PARTIAL / needs verification** for broad dashboard completeness, arbitrary stats, remaining dangerous-action audit coverage, broader settings parity and deep cross-surface e2e.

## Core Rule

Venue Mode is the source of truth for day-to-day venue operations. Staff-chat is only notification/radar/shortcut. Staff-chat must not become the canonical storage layer for orders, bills, calls, bookings, support tickets, venue chats, menu edits or settings.

Canonical dependencies:
- `docs/ORDER_SESSION_TAB_CORE.md` for table sessions, active orders, batches, tabs and bill lifecycle.
- `docs/MENU_OPTIONS_STOPLIST.md` for menu, option/modifier, stop-list, media and shift-check policy.
- `docs/MEDIA_STORAGE_UPLOAD.md` for info-section media storage, upload, delivery, lifecycle and the
  Bot/Mini App bridge.
- `docs/COMMUNICATION_MODEL.md` for `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` separation.
- `docs/BOOKING_LIFECYCLE.md` for booking statuses, hold/deadline, reminders, booking chat and no-show/seated policy.
- `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md` for Telegram bot entrypoints, fallback order, staff-call callbacks, staff-chat link/test/unlink, notification policy and callback security.
- `docs/SECURITY_RBAC_MATRIX.md` for roles, permissions, scopes and dangerous actions.
- `docs/STAFF_PROFILES_SHIFTS_TIPS.md` for public staff profiles, manual Today Shift, optional Staff
  Schedule Phase 1 and future staff-tip boundaries.
- `docs/ANALYTICS_EVENTS.md` for operational events, KPIs, audit/event boundaries and dashboard targets.
- `docs/TESTING_QA_SMOKE_STRATEGY.md` for validation commands, staging policy and operational smoke strategy.
- `docs/DEPLOYMENT_RUNBOOK.md` for release/deploy, logs, staging smoke and incident operations.

## Venue Mode Areas

| Area | Purpose | Current implementation from docs | Target / gap |
| --- | --- | --- | --- |
| Dashboard | First operational screen for the current venue. | Dashboard/counters exist in Venue Mini App; read-only stats smoke passed for Owner/Manager. | Full operations dashboard is `PARTIAL`: needs complete queue/call/booking/stop-list/staff-chat/status warning coverage. |
| Orders | Queue of active order batches by table/order. | Venue order queue and order detail exist; display order number and Russian labels are smoke-closed. | Queue may group by table, but detail must preserve `table_session`, batches and tabs. |
| Order detail | Chronological batch and item workspace. | Full bill, display labels and selected options are documented as smoke-closed for current paths. | Status history/audit, force-close reason and all modifier variants need verification. |
| Tabs / bill | Operational bill by personal/shared tabs. | Guest/Venue/Bot bill parity is smoke-closed; bill request/payment method is smoke-closed. | Tab reopen and force-close audit remain future/partial. |
| Staff calls | Live operational requests from table context. | M5 lifecycle, ACK/DONE audit hardening and guest-visible terminal `CANCELLED` status are staging-smoked. | Manual cancel UI, quick replies and row-level actor/timestamps remain future. |
| Bookings | Venue booking queue and lifecycle. | Booking queue/lifecycle, hold settings, confirmed-only arrival actions, state-aware staff-chat booking buttons, reminders and attendance indicators are partially/smoke-closed. | Broader lifecycle automation/preorder/reminder rollout remains partial/future. |
| Menu | Structured order menu management. | Structured selected-option parity and OWNER/MANAGER atomic pre-shift availability review are staging-smoke-passed. Menu constructor broader status is partial. | Use `docs/MENU_OPTIONS_STOPLIST.md`; broad media/top-list governance remains open. |
| Stop-list | Fast operational availability toggles. | Item/option availability parity is documented for current Staff/Manager/Owner paths; shift-check mass changes use an OWNER/MANAGER-only local draft and one batch confirmation. | Per-venue `staff_stoplist_enabled` and audit completeness outside shift check remain future/partial. |
| Tables / QR | Physical table inventory and QR context. | Tables/QR basics exist; table-session runtime behavior is documented separately. | Single table CRUD/diagnostics/QR rotate audit need verification. |
| Staff / invites | Membership, roles and invite links. | Staff/Manager invite sharing and acceptance are staging-smoked; Platform Owner OWNER invite/revoke is smoke-closed. | Role parity still needs regression after new routes. |
| Staff profiles / today shift | Guest-visible opt-in staff profiles and manual "today on shift". | Phase 1 is DONE / MVP / STAGING-SMOKE-PASSED. Both server-selected Guest Preview modes preserve the public-profile/visible-shift filters. | Tips and safe consent-based photo upload remain future. |
| Staff schedule | Optional venue-local planning of one shift per profile/start-date. | Phase 1 canonical spec is ready; runtime is not implemented. | Owner/Manager bounded week management and Staff own/overlap read only; no Guest, Telegram, reminder, payroll or Today-source migration. |
| Staff-chat | Linked group diagnostics and operational notifications. | Link/test/unlink, live order activity-card behavior and state-aware booking shortcuts are smoke-closed. | Personal staff notifications and unified event policy remain future. |
| Feedback | Internal post-visit feedback from completed Guest History. | DONE / MVP / staging-smoke-passed: Owner/Manager read own-venue aggregate/list and can manually open exact `VENUE_CHAT` follow-up for ratings `1..3`; Staff denied. | Platform feedback analytics dashboard and automated prompts remain future. |
| Settings / card preview | Venue profile, schedule, booking hold, extension, staff-chat and read-only public-card preview. | Booking hold, shift extension, public profile/card, schedule/date exceptions and Owner-only public review link are smoke-closed. One `Предпросмотр для гостя` entry uses one backend-selected `PUBLISHED_PUBLIC` / own-venue `PRIVATE_DRAFT` endpoint and is **DONE / MVP / STAGING-SMOKE-PASSED**. Unsaved public-card, weekly-schedule and date-exception changes block preview without auto-save. | Broader settings/media authoring, versioned snapshots and publish workflow remain future; archived/deleted/missing venues continue to fail closed. |
| Stats | Role-specific operational summaries. | Venue Mini App read-only stats passed staging smoke for Owner/Manager. | Custom ranges, arbitrary stats, AI summaries and advanced analytics remain future. |

## Dashboard

Target dashboard cards:
- new order batches count;
- active orders count;
- staff calls waiting;
- bookings waiting response;
- stop-list warnings;
- staff-chat linked/unlinked status;
- today stats summary;
- venue status/subscription warning where visible to Owner.

Role behavior:
- Owner sees operations plus settings, staff-chat, subscription/status warnings where implemented.
- Manager sees shift operations, queues, stats and settings only where allowed.
- Staff sees only operational queue/calls/bookings/availability according to role permissions.

Current vs target:
- Current dashboard is `PARTIAL`: operational counters and stats exist in slices, but one complete dashboard model with all cards above needs verification.

## Orders Queue

Target:
- queue can group by physical table for scanning;
- detail preserves `table_session_id`, order, batch and tab boundaries;
- statuses are tracked by batch, not only the whole table;
- filters:
  - `new`;
  - `in_progress`;
  - `ready/delivering`;
  - `delivered`;
  - `rejected/cancelled`.

Each queue card should show:
- venue/table display;
- human display order number;
- latest batch time;
- number of new batches;
- source `miniapp` / `bot_fallback`;
- SLA timer if implemented/future.

Role policy:
- Staff can view the queue and update allowed statuses.
- Manager can accept/reject/manage statuses where backend policy allows.
- Owner can perform all venue operations.
- Platform does not operate ordinary venue orders by default.

Current vs target:
- Queue, order detail, display number and status operations exist in current docs.
- SLA timers and full event-derived queue metrics are future/partial.

## Order Detail / Batches

Target detail shows:
- venue table;
- `table_session_id` / visit context where useful to operators;
- human display order number;
- batches chronologically.

Each batch shows:
- `created_at`;
- source (`miniapp`, `bot_fallback`, service charge path where applicable);
- safe guest/author label;
- tab/account label;
- items;
- selected option snapshots;
- line comments/preferences;
- status;
- status history/audit where implemented.

Actions:
- accept;
- preparing;
- delivering;
- delivered;
- reject with reason;
- cancel if policy allows;
- force close only with reason/audit.

Current vs target:
- Venue order detail, display order number, bill rows, selected options and staff-chat clarity are smoke-closed for current paths.
- Force-close reason/audit and full status history need verification before being called complete.

## Tabs / Bill

Target:
- personal tabs and shared tabs are visible in order detail as operational bill accounts;
- Venue users see the operational bill breakdown by tab;
- Guest privacy boundaries still apply: guests see only their own personal tab or joined shared tabs;
- full bill uses order snapshots, not live menu prices.

Tab lifecycle:
- `open -> bill_requested -> paid -> closed`;
- `closed -> reopened` only by allowed role with audit, if implemented.

Actions:
- mark bill requested;
- mark paid;
- close tab/order where allowed;
- reopen only by allowed role with audit, future/target.

Current vs target:
- Full bill, human order/account labels and bill request/payment method UX are staging-smoked.
- Tab reopen and full paid/closed state machine remain partial/future unless implementation evidence exists.

## Staff Calls

Staff calls are separate from support tickets and venue chats.

Target statuses:
- `new`;
- `acknowledged` / `accepted`;
- `completed` / `closed`;
- `cancelled`.

Queue card shows:
- type/reason;
- table;
- optional guest comment;
- age;
- assigned/accepted by where implemented;
- linked order/bill context where safe.

Actions:
- acknowledge;
- complete;
- optional quick reply such as `Иду` / `2 минуты`.

Current vs target:
- Staff-call lifecycle is CLOSED / staging smoke passed for `NEW` -> `ACK` -> `DONE` plus guest-visible terminal `CANCELLED`.
- Guest staff-call status includes `NEW`, `ACK`, `DONE` and `CANCELLED` only for the current guest and current `tableSessionId`; `CANCELLED` uses copy `Вызов отменён`.
- Venue active queue remains active-only `NEW` / `ACK`; `DONE` and `CANCELLED` are terminal history/status, not active work.
- Manual cancel UI, row-level `acked_by` / `done_by` / timestamps and quick replies remain future/partial.

Staff-chat:
- staff-call notifications may go to staff-chat according to existing operational policy;
- support tickets and `VENUE_CHAT` must not go to staff-chat.

## Bookings Queue

Canonical booking lifecycle: `docs/BOOKING_LIFECYCLE.md`.

Target booking queue is available to Owner/Manager and Staff only where final policy allows.

Statuses:
- `pending`;
- `confirmed`;
- `changed` / `proposed_time`;
- `cancelled`;
- `expired`;
- `no_show`;
- `seated`.

Actions:
- confirm;
- propose time;
- cancel with reason;
- mark seated;
- mark no-show.

Action availability:
- `pending`: confirm/propose/cancel/message where role allows; no arrival buttons.
- `confirmed`: `Гость пришёл` / `Не пришёл`, cancel and message where role allows.
- `changed` / `proposed_time`: cancel/message only; no arrival buttons until explicit confirmation.
- terminal statuses: no dangerous lifecycle buttons.

Hold minutes / arrival deadline:
- booking hold settings and `arrival_deadline_at` are documented as implemented/smoked in current roadmap notes;
- reminders remain opt-in disabled by default unless rollout is explicitly enabled.

Current vs target:
- Venue Mini App booking queue/lifecycle, Staff arrival/no-show split, confirmed-only arrival guard, state-aware staff-chat booking buttons and M7a hold settings are smoke-closed.
- Broader automatic expiry/no-show, preorder and reminder rollout remain partial/future unless explicitly enabled and smoked under `docs/BOOKING_LIFECYCLE.md`; Guest History Foundation and booking-only `SEATED` Post-Visit Feedback are closed and stay in regression.

## Menu / Stop-List Operations

Canonical menu policy: `docs/MENU_OPTIONS_STOPLIST.md`.

Venue Operations view:
- Owner manages structure, prices, media, option schema, featured/top-list and menu policy.
- Manager target is stop-list + shift check + availability unless product explicitly keeps broad `MENU_MANAGE`.
- Staff target is read-only by default; stop-list only if `staff_stoplist_enabled` or equivalent future policy allows it.
- Current docs say Staff can manage item/option availability through `MENU_AVAILABILITY_MANAGE`; keep Bot/Mini App parity in regression until a per-venue flag exists.
- Stop-list changes should be fast and auditable.
- Guest checkout server-side availability validation is required.

Shift-check Phase 1:
- the Menu screen has two mutually exclusive task-oriented accordions, collapsed by default:
  `Редактирование меню` with a hidden-until-requested new-category form and compact expandable
  categories, and `Проверка меню перед сменой`; opening one collapses the other;
- OWNER/MANAGER open the shift-check accordion; STAFF has no entry and direct
  `MENU_SHIFT_CHECK` access is denied;
- current categories and item/option availability counts come from saved backend state. Search,
  unavailable/dirty filters, availability switches and mass item/category/option actions mutate
  only the local draft;
- normal rows show `В наличии` / `Нет в наличии` switches without selection checkboxes; options are
  nested under items and dirty rows show `Изменено`. Checkboxes and selection actions exist only in
  the separate `Массовое изменение` mode and have distinct accessible roles/labels;
- cancel clears the draft without mutation/audit. Confirm shows the change summary and sends one
  `POST /api/venue/menu/shift-check?venueId=<id>` request, including an allowed no-op request;
- the backend validates a combined maximum of 500 changed rows, duplicates, existence, venue and
  item/option ownership and expected availability before changing anything;
- item updates, option updates and the single `MENU_SHIFT_CHECK_COMPLETED` audit commit in one DB
  transaction. Any invalid/stale row rolls back the whole completion;
- stale state uses `Меню изменилось. Обновите проверку и повторите подтверждение.` and leaves the
  screen recoverable. Refresh loads current saved state and keeps still-relevant draft intent;
- collapse/reopen preserves the local draft. Switching venue or disposing the screen collapses the
  accordions, aborts old work and clears draft/selection, so a pending confirmation cannot apply to
  the next venue;
- existing individual item/option availability routes and Telegram stop-list flow remain
  unchanged. This slice adds no stock quantities, menu structure mutation, media, migration,
  Telegram shift-check UI or history table.

## Tables / QR

Target tables list:
- display name;
- zone;
- status;
- active visit/order indicator.

Actions:
- create table;
- bulk create;
- download QR;
- download QR package;
- rotate/reissue QR;
- deactivate table;
- edit display/zone/capacity if product implements it.

Dangerous actions:
- QR rotate/reissue requires confirmation/audit;
- table deactivate with active session should warn and require explicit confirmation.

Current vs target:
- Table/QR basics exist, while single CRUD/diagnostics/QR rotate audit need verification in the current implementation docs.

## Staff / Roles / Invites

Target:
- Owner manages staff and roles.
- Manager may create Staff invite only where current conservative policy allows.
- Staff cannot manage roles.
- Last Owner removal is blocked server-side.
- Invite tokens are short-lived and one-time where appropriate.

Audit:
- role granted;
- role revoked;
- invite created;
- invite accepted;
- owner changed/revoked.

Current vs target:
- Staff/Manager invite sharing polish and acceptance are staging-smoked.
- Platform Owner can invite/revoke Venue Owner with last-owner protection.
- Broader staff management parity should stay in role smoke after new routes.

## Staff Profiles / Today Shift / Staff Schedule

Canonical spec: `docs/STAFF_PROFILES_SHIFTS_TIPS.md`.

Phase 1 implementation:
- Owner creates, edits, publishes and hides public staff profiles.
- A profile may be linked to a venue member or display-only.
- Staff may edit only their own linked draft fields where policy allows and cannot self-publish;
  Mini App photo upload remains future.
- Owner/Manager may mark `Сегодня на смене`; Manager is limited to active/completed/canceled.
- Guest sees only public visible profiles and shifts.
- Venue Mode section is `Карточки сотрудников`: profiles are optional, create form is collapsed by
  default, existing cards are compact, `Другое` requires `Название роли`, raw User ID / Photo ref
  are not manual owner inputs, and photo upload remains future.
- Guest `Сегодня работают` appears below main venue information/actions and shows public display
  name, role/custom role, bio/tags and safe placeholder/photo only.
- No staff tips or payments are implemented in Phase 1.

Staff Schedule Phase 1 target, not current runtime:
- `График смен` is an optional section under `Работа смены`; an empty graph blocks no existing flow
  and there is no enable/disable setting;
- Owner/Manager manage one future interval per staff profile and venue-local start date in a bounded
  week list; Staff sees `Мои смены` and safe colleagues only where intervals overlap;
- local date/time, overnight, DST, 90-day horizon, computed lifecycle, optimistic concurrency,
  atomic audit and privacy follow `docs/STAFF_PROFILES_SHIFTS_TIPS.md`;
- schedule rows never auto-publish to Guest, and current manual Today Shift plus Guest
  `Сегодня работают` remain unchanged;
- no new Telegram or staff-chat flow is part of the slice.

Future outside Schedule Phase 1:
- staff availability, shift swaps, recurring templates, reminders and attendance;
- personal Telegram shift confirmations/sign-up, with Venue Mode schedule as source of truth;
- photo upload/media picker with consent, moderation and safe storage;
- separate staff communication chat/forum topics only after an explicit product decision;
- external staff tip link + `staff_tip_intent`, where money does not touch the platform;
- provider/direct payout only after legal/product decision;
- Telegram Stars and crypto are not MVP for staff tips.

## Staff-Chat

Canonical Telegram/staff-chat model: `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`.

Target:
- link/unlink/test from Venue Mode;
- staff-chat is radar/shortcut, not source of truth;
- callbacks verify server-side role and venue scope;
- `callback_data` uses opaque ids only;
- link/unlink/test writes audit where implemented.

Allowed staff-chat events:
- order batch notification;
- staff-call notification;
- booking operational notification if policy says so.

Forbidden staff-chat events:
- `SUPPORT_TICKET`;
- `VENUE_CHAT`;
- post-visit feedback submission or feedback follow-up context;
- ordinary guest support messages;
- raw menu/price edits.

Current vs target:
- Staff-chat diagnostics/unlink, test-message flow and order activity-card noise reduction are smoke-closed.
- Personal staff notifications and unified event policy remain future/partial.

## Settings

Target settings groups:
- Venue profile: name, description, address, city, contacts, hours.
- Modules: orders, staff calls, bookings, promotions/future, menu visibility policy.
- Operations: timezone, order numbering, notification toggles, staff-chat settings.
- Owner-only: billing/subscription, dangerous lifecycle request, owner access.

Closed public review setting:
- `Ссылка для отзывов` is edited by Owner only; Staff has no access. Telegram bot and Venue Mini App read/write the same backend setting and must not create duplicate storage.
- Description: `Эту кнопку покажем гостю только после оценки 5/5. Гость сам решает, переходить ли на Яндекс.Карты.`
- Helper: `Где взять ссылку: откройте карточку заведения в Яндекс.Картах, нажмите «Поделиться» и скопируйте ссылку. Если у вас есть доступ к Яндекс Бизнесу, лучше взять ссылку на форму отзывов в разделе «О компании» → «Промоматериалы».`
- Ethical hint: `Не обещайте скидки или бонусы за отзыв и не просите поставить конкретную оценку.`
- Only a validated safe URL reaches Guest History detail after manual `5/5`; clearing the setting removes the CTA, and no automatic redirect is allowed.

Closed feedback operations:
- Owner/Manager sees read-only feedback aggregate/list for the current venue; Staff does not see the section or direct API.
- `Связаться с гостем` is available only for ratings `1..3` and opens the exact active `VENUE_CHAT` with `Отзыв после визита` context. An active thread is reused; a closed/resolved old thread leads to a new active thread.
- Context may contain rating, tags, comment and visit date. It is a system/context message, not an auto-sent personal reply.
- Follow-up does not create `SUPPORT_TICKET`, post to staff-chat or send a message until Owner/Manager explicitly replies.

Current vs target:
- Venue Mini App settings are no longer a broad dead placeholder for the closed slices: booking hold, shift extension settings, public profile/card, schedule/date exceptions and staff-chat management are backend-backed in current docs.
- Remaining settings such as media-section authoring, broader module toggles and some Bot parity are partial/future; the unified Published/private-saved Guest Preview is backend-backed.
- If a future settings screen is not backend-backed, hide it or mark it clearly as future.

Closed card-preview slice:
- Current status is **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE /
  MVP / STAGING-SMOKE-PASSED** after green GitHub Actions, staging deploy and manual staging smoke.
- OWNER/MANAGER see one own-venue entry `Предпросмотр для гостя` and one read-only renderer backed by
  `GET /api/venue/{venueId}/guest-preview`. The server selects `PUBLISHED_PUBLIC` only when the
  unchanged Guest lifecycle/subscription guards pass; its venue/info payload is the exact
  Guest-visible state. Otherwise `PRIVATE_DRAFT` uses the same public-facing assembly for an
  allowlisted projection of saved state without weakening any public Guest route.
- `PUBLISHED_PUBLIC` shows badge `Опубликовано` and `Так карточку сейчас видит гость.`. `PRIVATE_DRAFT` shows badge `Черновик`, `Гости пока не видят эту карточку. Это закрытый предпросмотр сохранённой версии.` and only a safe reason: `Заведение ещё не опубликовано.`, `Заведение временно скрыто.` or `Заведение приостановлено.`. Technical subscription state is not exposed.
- The Settings entry returns through `Вернуться к настройкам`; the venue-navigation entry returns through `Вернуться в кабинет`.
- `PRIVATE_DRAFT` is allowlisted to saved guest-facing card/location/contact/description, weekly schedule and date exceptions, visible info sections and authenticated scoped representations of existing guest-visible media, published Today Staff and current `ACTIVE` promotions. Hidden sections/media, unpublished staff, `DRAFT`/`PAUSED`/`ARCHIVED` or non-current promotions, private fields and raw provider/storage refs are absent.
- STAFF has no entry and direct access is forbidden; foreign venue users and Platform-only access receive no Venue preview authority. Archived/deleted/missing venues fail safely.
- Settings blocks preview while public-card, weekly-schedule or date-exception values are unsaved and shows `Есть несохранённые изменения. Сначала сохраните их, затем откройте предпросмотр.`. It never auto-saves; after an explicit save, preview reads the new saved state.
- Every preview response is `no-store`. Venue switching clears the old content, aborts the previous request and ignores late responses across both modes. Booking, favorite, chat, support, staff-call, extension, cart, order and other Guest mutations/actions are absent.

## Venue / Menu Media Status

Current status from the 2026-07-29 code audit:

- Venue/Public Card Media Management: **PARTIAL / BOT-FIRST**.
- Venue Mini App Media Upload: **MISSING / FUTURE**.
- Structured Menu Item Media Management: **MISSING / FUTURE**.
- Staff Profile Photo Upload: **FUTURE** and separate from venue/menu media.

| Surface | Bot current | Venue Mini App current | Guest display | Target |
| --- | --- | --- | --- | --- |
| Public card info-section image | OWNER/MANAGER can add multiple Telegram photos, delete an attachment and hide/show the whole section. Stored as Telegram `file_id`; no direct replace or per-attachment hide. | No section authoring, file picker, upload or attachment management. Guest Preview is view-only; private mode fetches only scoped visible media through an authenticated Venue proxy. | Guest Mini App and `PUBLISHED_PUBLIC` load the image through the guarded Guest proxy; Guest Bot sends the Telegram photo. `PRIVATE_DRAFT` uses the authenticated preview proxy. Raw `file_id` is not exposed in either DTO. | Shared media source/compatible bridge, safe image upload, replace, section/attachment visibility policy, delete, audit and guest-safe delivery URL. |
| Public card info-section PDF | OWNER/MANAGER can add PDF documents, delete an attachment and hide/show the whole section. PDF detection is MIME/filename based; no direct replace or per-attachment hide. | No upload/manage flow. Guest Preview is view-only; private mode uses the same scoped authenticated delivery boundary. | Guest Mini App and `PUBLISHED_PUBLIC` expose `Открыть PDF` through the guarded Guest proxy; `PRIVATE_DRAFT` uses the authenticated preview proxy. | PDF only on explicitly allowed surfaces, with server-side MIME/type/size validation, ownership/venue scoping, replace/delete/audit and safe delivery. |
| Structured menu item photo / description / thumbnail | No current item-photo/thumbnail CRUD. The DB has a legacy `menu_items.description` column, but current Bot/menu repository flow does not read or write it. Option/flavor media is absent. | Current item CRUD covers name, price, type, availability, order and options; no item photo, description, thumbnail or option/flavor media fields/actions. | Current structured Guest menu DTO/rendering shows no item photo, description, thumbnail or option/flavor media. | Item-safe media/description contract after storage/proxy design; no raw provider/storage refs in Guest DTOs. |
| Photo/PDF menu | Uses the `section_type=menu` info-section flow. OWNER/MANAGER add images/PDFs, delete attachments and hide/show the flat section. It is view-only and separate from the order menu. | No authoring/manage flow. Both preview modes can view only visible saved media through their respective guarded/scoped proxy; neither DTO returns raw refs. | Guest pre-QR `ℹ️ Информация` shows the flat `📖 Фото-меню`; it cannot create an order without matching structured items. | Keep view-only semantics; add Mini App management only through the shared media foundation. |
| Staff profile photo | No supported Bot photo upload/manage flow was found. | UI explicitly shows `Фото сотрудника — позже`; no file picker/upload. The data model has nullable `photo_ref`, but raw manual input is hidden. | Guest UI renders an initials placeholder; Guest DTOs and both preview modes omit raw `photoRef`. | Separate consent-based upload with employee consent, moderation, replacement/deletion and approved guest-safe delivery. |

The current `menu_category_images` table/repository is test/seed-oriented and has no owner CRUD or
active Guest/Venue Mini App consumer; it is not evidence of structured menu-item photo support.

Future bounded block: **Venue Mini App Media Upload & Management Foundation**.

Minimum scope:

- OWNER/MANAGER file picker, image upload and PDF upload only for allowed surfaces;
- server-side MIME/type/size validation plus ownership and venue scoping;
- safe storage abstraction, replacement, hide/show, deletion and guest-safe delivery URL;
- audit for upload/replace/delete;
- no raw Telegram `file_id` or storage key in public DTOs;
- Bot and Mini App use one media source of truth or a compatible bridge;
- employee consent for staff photos remains a separate mandatory rule.

The canonical analysis is now `docs/MEDIA_STORAGE_UPLOAD.md`. Its verdict is
**STOP_FOR_MEDIA_STORAGE_DECISION**: the recommendation is a hybrid ledger with legacy
`TELEGRAM_FILE` reads and private S3-compatible storage for new uploads, but provider, cost,
backup/versioning, deletion and production operations are not approved. Telegram technical
storage chat remains a lower-cost alternative. Do not implement upload until one option is recorded.

## Stats

Target Owner stats:
- orders today/7d/30d;
- accepted/delivered batches;
- estimated revenue only if bill data is reliable;
- top items;
- average accept/deliver time;
- staff calls;
- booking response;
- support summary.

Target Manager stats:
- shift queue;
- backlog;
- SLA timers where implemented;
- active staff calls;
- bookings waiting response.

Staff:
- no business analytics by default;
- operational queue/counters only.

Current vs target:
- Venue Mini App read-only stats passed staging smoke for Owner/Manager.
- Custom ranges, arbitrary period stats, AI summaries and advanced analytics remain future.

## Surface Parity Matrix

| Feature | Telegram bot current | Venue Mini App current | Staff-chat current | Target / gap | Priority |
| --- | --- | --- | --- | --- | --- |
| Order queue | Exists for venue roles. | Exists and smoke-closed for core queue/detail. | Notifications/activity-card. | Venue Mode remains source of truth; SLA timers future. | Regression |
| Order status | Exists. | Exists. | Callback shortcuts where implemented. | Callbacks must verify role/scope; status history/audit needs verification. | Regression |
| Full bill | Staff full bill exists. | Management bill parity smoke-closed. | Bill context in activity-card. | Tab reopen/paid state machine partial. | Regression |
| Staff calls | Bot/staff-chat callbacks exist. | M5 queue/lifecycle and guest-visible `CANCELLED` smoke-closed. | Notifications and ACK/DONE callbacks. | Manual cancel UI, quick replies and row-level actors future. | Regression/P2 |
| Bookings | Bot and venue booking flows exist. | Queue/lifecycle/hold settings smoke-closed. | Operational notifications where policy allows. | Broader automation/preorder/reminder rollout future. | Regression/P2 |
| Menu manage | Bot owner/manager flows exist; no Phase 1 shift-check UI. | Options/flavors parity and OWNER/MANAGER shift check are staging-smoke-passed; broader constructor partial. | No source-of-truth edits. | Follow `docs/MENU_OPTIONS_STOPLIST.md`; keep the UX, atomic batch/stale/audit contract in regression. | Regression/P2 |
| Stop-list | Existing immediate Bot paths remain unchanged. | Item/option parity documented/smoked; shift-check draft/mass confirmation is OWNER/MANAGER-only. | Callback shortcuts only if role-checked. | Per-venue Staff stop-list flag future. | Regression/P2 |
| Tables/QR | Bot table flows exist. | Basics exist. | No. | QR rotate audit/diagnostics need verification. | P2 |
| Staff invites | Bot invite acceptance exists. | Copy/share invite result smoke-closed. | No. | Keep role denial/last-owner protection in regression. | Regression |
| Staff schedule | No Phase 1 flow. | Spec ready; runtime not implemented. Target labels are `График смен` for Owner/Manager and `Мои смены` for Staff. | No Phase 1 event/button. | Venue Mini App only; bounded own-venue management/read and Today/Guest compatibility per canonical staff spec. | Next bounded runtime slice |
| Staff-chat link/test | Bot link command exists. | M6 link/test/unlink smoke-closed. | Target group. | Personal notifications future. | Regression |
| Settings / card preview | Bot and Mini App share the public review URL source; Bot remains richer in info-section/media authoring. | Backend-backed slices include Owner-only `Ссылка для отзывов` and one read-only `Предпросмотр для гостя` screen with server-selected `PUBLISHED_PUBLIC` / `PRIVATE_DRAFT`; preview is **DONE / MVP / STAGING-SMOKE-PASSED**. | No. | Keep exact Guest parity, saved-private allowlist, dirty-state guard, RBAC/privacy/media scope and lifecycle/stale-state boundaries in regression; media upload remains a separate future block. | Regression/P2 |
| Stats | Bot stats exist. | Read-only stats smoke-closed. | No. | Custom ranges/advanced analytics future. | P2 |

## Current Known Gaps

- Staff-call ACK/DONE and guest-visible `CANCELLED` are smoke-closed; manual cancel UI, row-level actor/timestamps and quick replies remain future.
- Booking queue/lifecycle is smoke-closed for MVP; automatic expiry/no-show policy, preorder and broad reminder rollout remain future.
- Full bill/display/order snapshots are smoke-closed for current paths; tab reopen, force-close reason/audit and all modifier variants need verification.
- Settings are `PARTIAL`: closed slices are backend-backed, including Owner-only public review URL and the unified read-only Guest Preview with a no-auto-save dirty-form guard, while broader settings/media authoring, versioned snapshots and publish workflow remain future/partial.
- Venue/public-card media management is `PARTIAL / BOT-FIRST`; Venue Mini App media upload is
  `MISSING / FUTURE`. Structured menu-item media is separately `MISSING / FUTURE`; the working
  Guest/Bot info-section rendering and view-only Photo/PDF menu must not be relabeled missing.
- Shift check is **DONE / MVP / STAGING-SMOKE-PASSED** for OWNER/MANAGER atomic availability
  review. Existing Staff item/option stop-list parity is unchanged and per-venue
  `staff_stoplist_enabled` remains future.
- Manager broad `MENU_MANAGE` remains a product-policy decision: keep and test it, or narrow to stop-list/shift check/basic availability.
- Staff-chat notification policy is documented for orders/calls/bookings and explicitly excludes support, venue chats and post-visit feedback/follow-up context; personal staff notifications remain future.
- Staff Schedule Phase 1 is implementation-ready in the canonical staff spec but has no runtime;
  it must reuse `staff_shifts` without auto-publishing to Guest or adding Telegram flows.
- Multi-venue selector/entry should stay in regression for users with several venue memberships.

## Roadmap Status

- Venue Mode operational spec: `UPDATED`.
- Venue operations implementation: `PARTIAL / DONE-POLISH by slice`; core orders/bill/staff-call/bookings/staff-chat/menu-options settings slices are smoke-closed, but a complete operating cockpit still has future/partial areas.
- Staff-call lifecycle: `CLOSED for NEW/ACK/DONE MVP plus guest-visible CANCELLED`, `PARTIAL` for manual cancel UI, quick replies and row-level actors.
- Booking queue: `CLOSED for MVP`, `PARTIAL` for automation/preorder/reminder rollout.
- Post-Visit Feedback: `DONE / MVP / STAGING-SMOKE-PASSED`, including manual `5/5` public review CTA and low-rating `VENUE_CHAT` follow-up.
- Settings: `PARTIAL`, with several backend-backed slices closed, including Owner-only `Ссылка для отзывов` shared by Bot/Mini App.
- Guest Preview Phase 2.1: **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**; focused backend preview/Guest/RBAC/promotion tests, compile/lint, Mini App build and deterministic smoke `95/95` are green, GitHub Actions were green, staging deploy completed and manual staging smoke passed.
- Full bill/display/order snapshots: `CLOSED for current smoke paths`, `PARTIAL` for force-close/reopen/all modifier variants.
- Shift check: **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**; keep its role,
  tenant, UX, atomicity, stale-state, Guest availability and Telegram parity checks in regression.
- Stop-list parity: current individual item/option parity remains documented; per-venue Staff
  policy remains future.
- Staff-chat source-of-truth policy: `DOCUMENTED`.
- Staff Schedule Phase 1: `SPEC READY / RUNTIME NOT IMPLEMENTED / NO MIGRATION EXPECTED`.

## Operational Smoke Checklist

1. Owner opens Venue Mode dashboard.
2. Manager opens Venue Mode dashboard.
3. Staff opens Venue Mode and sees only allowed sections.
4. Guest creates order batch from table context.
5. Venue queue shows table/order with new batch.
6. Venue detail shows batch and items.
7. Venue detail shows selected option snapshots and line comments where configured.
8. Staff updates allowed batch status.
9. Staff cannot reject/close if policy forbids.
10. Manager can reject with reason if policy allows.
11. Full bill/tabs are visible according to current implementation.
12. Guest requests bill; Venue sees `bill_requested` context.
13. Guest creates staff call.
14. Venue/Staff sees `NEW` / `ACK` staff calls in the active queue.
15. Staff-call ACK/DONE works and guest sees terminal `DONE`.
16. Auto-cancelled/`CANCELLED` call appears to the same guest in the current table session as `Вызов отменён`.
17. Venue active queue does not show `CANCELLED`.
18. Booking appears in queue if implemented.
19. Venue confirms/changes/cancels booking if implemented.
20. Owner toggles item stop-list; guest cannot order unavailable item.
21. Staff stop-list behavior matches current policy across Telegram and Mini App.
22. Owner downloads QR package where implemented.
23. QR rotate requires confirmation/audit where implemented.
24. Owner links staff-chat and sends test message.
25. Staff-chat receives order/staff-call notifications but not support tickets or venue chats.
26. Manager cannot access billing.
27. Staff cannot access settings, billing, support tickets or venue chats.
28. Venue user cannot access another venue.
29. Owner opens `Ссылка для отзывов`, sees the Yandex Maps/Yandex Business helper plus ethical hint, and can save/update/clear a safe URL; Manager/Staff cannot edit it.
30. Guest manual `5/5` submit shows the Yandex CTA only while the safe URL exists; clearing the URL removes it and no automatic redirect occurs.
31. Guest `1/5` feedback appears in the own-venue Feedback list; Staff cannot see the section/action.
32. Owner/Manager clicks `Связаться с гостем` and the exact `VENUE_CHAT` opens with rating, tags/comment when present and visit date context.
33. Existing active guest+venue chat is reused with fresh feedback context; a closed/resolved old chat results in a new active thread.
34. Guest receives only a manual Owner/Manager reply in `Чаты`, not Support.
35. Feedback submission/follow-up creates no support ticket and no staff-chat notification.
36. `VisitFeedbackWorker`, scheduled Telegram feedback prompts and automatic Yandex redirect remain disabled.
37. Owner and Manager open `Проверка меню перед сменой`; Staff has no entry/direct access and a
    foreign venue user is denied.
38. Shift-check search, filters, selection and mass item/category/option changes stay local until
    confirmation; cancel sends no mutation.
39. One confirm atomically applies mixed availability changes and one safe completion audit; stale
    or invalid input applies nothing and no-op confirmation audits zero changes.
40. Guest menu and stale cart preview/add-batch use the confirmed server-side item/option
    availability immediately.
41. Venue switching clears shift-check draft/selection and prevents an old request from updating
    the new venue.
