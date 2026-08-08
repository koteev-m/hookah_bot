# Security / RBAC Permission Matrix

Дата актуализации: 2026-08-08.

Статус: **current product reference / UPDATED**. Runtime permission parity is **PARTIAL** unless a specific route, test or smoke result is cited by the relevant implementation task. Menu item hard-delete audit is **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Promotion creation audit is **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. The bounded promotion status block is **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Staff role/removal audit is **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. These bounded slices do not close broader dangerous-action audit parity. The bounded OWNER/MANAGER menu shift-check permission is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**. Staff module settings are `STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`; green Actions, staging deploy and the bounded role/privacy smoke are complete without granting Manager broad `VENUE_SETTINGS`. Venue Mode operational surfaces are detailed in `docs/VENUE_OPERATIONS.md`; staff profile/manual-Today/optional-schedule/tips permissions are detailed in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`; booking lifecycle permissions are detailed in `docs/BOOKING_LIFECYCLE.md`; Telegram fallback/staff-chat permissions are detailed in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; menu/stop-list role policy is detailed in `docs/MENU_OPTIONS_STOPLIST.md`; venue media upload/storage security is detailed in `docs/MEDIA_STORAGE_UPLOAD.md`; validation strategy is detailed in `docs/TESTING_QA_SMOKE_STRATEGY.md`; release/deploy operations are detailed in `docs/DEPLOYMENT_RUNBOOK.md`.

Platform Guest QR status: **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**. Schema verdict is `NO_MIGRATION`; commit/push, green Actions for the release HEAD, staging deploy and the bounded real Telegram role/privacy smoke are complete. Broader permission parity and dangerous-action audit coverage remain `PARTIAL`.

## Core Rule

Server-side RBAC is the source of truth. Mini App navigation, Telegram keyboards and hidden buttons are convenience only; every read/write must verify actor, role, scope and entity ownership on the backend.

Tokens and client-provided ids are context pointers, not authority:
- Telegram `initData` must be validated server-side; `initDataUnsafe` is never trusted.
- QR/table token resolves table context but does not grant venue/admin rights.
- `table_session_id` is required for in-venue guest actions that mutate orders, tabs, staff calls or bill requests.
- Tab invite token points to a shared-tab invitation; membership and state are still verified server-side.
- Telegram `callback_data` must use opaque ids/tokens and must not contain secrets, raw provider data, raw initData, raw message text or unrelated PII.
- Platform Guest QR confirm/cancel uses a short opaque reference to a five-minute process-local pending record bound to exact Platform Owner + chat + token + venue + table. The reference is not authority; restart, expiry, cancel or first valid conditional consumption invalidates the flow safely. Phase 1 is single-instance long-polling; a callback routed to another instance has no pending authority and fails closed.
- Exact Platform Owner Guest Mini App create/touch or explicit-session resolve requires the matching active server-owned Telegram chat context and no user-exit marker. Client `mode=guest`, token/session id and Platform role cannot recreate context after exit.
- Exact Platform Owner authoritative table-bound Guest mutations lock that same chat-context row first and run final context/session/exit authorization plus order/bill, staff-call, tab, shift-extension or support ticket/message/status/read-receipt write in one JDBC transaction. `tableToken` on support always invokes the guard, including token-only requests; a client token never supplies authority or a session.
- Staff-chat callbacks are shortcuts only; every callback must re-check actor role, venue scope and entity state server-side.
- Client analytics events are low-trust diagnostics and cannot drive money, access, billing, order state or venue lifecycle.

## Scopes

| Scope | Meaning | Boundary |
| --- | --- | --- |
| `platform` | Marketplace-level venues, lifecycle, owner access, billing, support center and analytics/audit. | Platform Owner only. Does not automatically grant ordinary venue operations. |
| `venue` | One tenant venue and its operational data. | Venue members access only their own venue; cross-venue access is rejected. |
| `table` | Physical venue table resolved by opaque QR/table token. | Context only; not authorization. |
| `table_session` | Current visit/session at a venue table. | Required for active order, tab, batch, bill request and staff call mutations. |
| `tab` | Personal/shared bill account inside a table session. | Guest sees only own personal tab or joined shared tabs. |
| `support_ticket` | Status-tracked support/problem ticket. | Guest own tickets, Venue Owner/Manager own venue tickets, Platform Owner all tickets. Staff none. |
| `booking` | Guest booking, booking queue and booking conversation. | Guest own booking, Venue Owner/Manager own venue; Staff operational view/arrival/no-show only where allowed; canonical lifecycle in `docs/BOOKING_LIFECYCLE.md`. |
| `feedback` | One post-visit rating/tags/comment bound to a visible completed guest visit. | Guest submits only for own visible completed visit; Venue Owner/Manager reads own venue and may open low-rating `VENUE_CHAT`; Staff none; Platform dashboard future. |
| `staff_profile` | Public staff display profile and linked private venue-member relation. | Guest sees only opt-in public fields; linked user ids stay private. Owner/Manager private directory uses the existing `users` identity projection, while one active member can have at most one active linked card. Owner keeps broad controls; Manager manages only display-only/Staff-linked cards. |
| `staff_shift` | Manual Today publication and optional planned schedule intervals for staff profiles. | Guest sees only the shared public Today projection: explicit visible manual rows in `MANUAL`, or current active public presence in `SCHEDULE`; never the full/future schedule. |
| `staff_schedule` | Optional private venue planning over complete staff-shift intervals. | Owner/Manager manage only their venue; Staff sees own shifts plus safe overlapping colleagues; Guest/foreign/Platform-only access is denied. `STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`; Canceled Shift Restore + Bulk Assignment is also `DONE / MVP / STAGING-SMOKE-PASSED`. |
| `staff_tip` | Future staff-specific tip method/intent. | External tip link + intent only in Phase 2; money does not touch platform in MVP; intent is not proof of payment. |
| `billing` | Subscription, invoices, payments and commercial terms. | Platform Owner manages; Venue Owner views/pays where implemented; Manager/Staff none. |
| `analytics/audit` | KPI dashboards, event facts and critical-change evidence. | Role-specific views; raw event/audit payloads are restricted and privacy-filtered. |

## Roles

| Role | Product meaning | Current / target note |
| --- | --- | --- |
| Guest | End user without venue/platform role. | Can browse, book, order in verified table session, use own chats/tickets and own tabs. |
| Tab Host | Guest who creates/hosts a shared tab. | Derived responsibility inside `tab` scope, not a global role. |
| Tab Member | Guest who joined a shared tab by invitation/consent. | Derived responsibility inside `tab` scope, not a global role. |
| Staff | Shift operations role. | Orders, operational calls, allowed booking arrival/no-show and stop-list availability; Schedule Phase 1 provides own-shift plus safe-overlap read only while the optional module is enabled. No schedule mutation, support tickets, venue chats, feedback dashboard/follow-up, billing, settings or platform. |
| Venue Manager | Venue operations management role. | Own venue only. Can manage bookings, orders, menu/availability, tables where allowed, chats, feedback read/follow-up and own-venue support. No platform/billing commercial controls or public review link editing. |
| Venue Owner | Venue owner role through active `venue_members(role=OWNER)`. | Own venue operations, staff, settings, staff chat, feedback read/follow-up, public review link editing and venue billing view/pay where implemented. |
| Platform Owner | Platform-wide operator. | Venues, lifecycle, owner access, billing, support center, analytics/audit. Does not see ordinary `VENUE_CHAT` by default. |
| Support actor | Derived responsibility when a Guest, Venue Owner/Manager or Platform Owner handles a support ticket. | Not a separate global role in the product model. |

### ADMIN Decision

Current implementation/docs: `ADMIN` is a legacy DB compatibility alias mapped to `MANAGER`; Platform Mini App no longer exposes `ADMIN` as a selectable assignment role.

Target decision: remove `ADMIN` from the product model and keep it only as a compatibility alias until legacy rows/copy are fully normalized. Gap/risk: regression tests and docs must keep the alias explicit so `ADMIN` does not silently become a new Owner-like role.

## Permission Matrix

| Role | Permissions | Scope | Status / notes |
| --- | --- | --- | --- |
| Guest | `catalog.view`, `venue.view` | Published guest-visible venues | Current. |
| Guest | `venue_chat.create_own`, `venue_chat.reply_own` | Own guest+venue chat | Current support/chat split says Staff/Platform do not see ordinary venue chats. |
| Guest | `staff_profile.view_public`, `staff_shift.view_public` | Published venue public staff data | Current Slice B. Empty when the module or Guest visibility is off; otherwise `MANUAL` uses the exact manual Today projection and `SCHEDULE` uses current active public presence. No `linked_user_id`, Telegram id, username as internal identity, membership role, invite/link state, private contacts, future shifts or full schedule. |
| Guest | `staff_schedule.none` | All venue schedule scopes | Direct schedule access remains denied. The shared Guest resolver may derive current active presence in `SCHEDULE`, but exposes no shift id, interval, future/full schedule or private identity. |
| Guest | `staff_tip.intent_create` | Visible/tips-enabled staff profile | Future Phase 2. Creates intent/clickout only; no platform payment and no proof of payment. |
| Guest | `support_ticket.create_own`, `support_ticket.view_own`, `support_ticket.reply_own` | Own support tickets | Current MVP. Venue/order/booking context must be server-verified. |
| Guest | `booking.create_own`, `booking.view_own` | Own bookings | Current. Status/action availability depends on booking lifecycle. |
| Guest | `feedback.submit_own_completed_visit`, `feedback.view_own` | Own visible completed History visit | DONE / MVP / staging-smoke-passed. `ORDER_CLOSED`, booking `SEATED` and merged visits only; duplicate submit does not overwrite. |
| Guest | `order_batch.create_own_in_session` | Current table session + own/joined tab | Current target/core. Requires active table context, selected tab and menu/stop-list validation. |
| Guest | `promotion.preview_own_cart`, `promotion.apply_via_submit` | Current table session + own/joined tab | Current for the Happy Hours percentage slice. Server derives eligibility, current prices and adjustments; Guest cannot supply a trusted discount. |
| Guest | `tab.view_own`, `tab.join_shared_by_invite` | Own personal tab or joined shared tab | Current target/core; two-guest privacy remains smoke-critical. |
| Guest | `staff_call.create_in_session` | Current table session | Current. Staff call is operational, not support. |
| Tab Host | `shared_tab.invite`, `shared_tab.revoke_invite`, `shared_tab.view`, `shared_tab.add_batch` | Hosted shared tab | Target/current where shared tab flow is implemented; member management beyond invite/revoke needs verification. |
| Tab Host | `shared_tab.manage_members` | Hosted shared tab | Target/future unless a concrete implementation task verifies it. |
| Tab Member | `shared_tab.view`, `shared_tab.add_batch`, `shared_tab.leave` | Joined shared tab | Target/current where shared tab flow is implemented. |
| Staff | `order_queue.view`, `order_batch.status_update_allowed` | Own venue operations | Current. Must preserve table-session/batch/tab boundaries. |
| Staff | `staff_call.view`, `staff_call.ack_complete` | Own venue calls | Current ACK/DONE smoke passed; CANCELLED UI/lifecycle and row-level actor columns remain future. |
| Staff | `booking.view`, arrival/no-show where allowed | Own venue bookings | Current STAFF booking split. Confirm/cancel/change/message/settings denied. |
| Staff | `menu.view`, `table.view`, `menu_availability.manage` | Own venue operational availability | Current docs say item/option stop-list parity is aligned. Target menu policy is `staff_stoplist_enabled` or equivalent before Staff can change availability; see `docs/MENU_OPTIONS_STOPLIST.md`. |
| Staff | `MENU_SHIFT_CHECK` denied | All venue scopes | Current Phase 1 rule. Entry is hidden and direct API is denied; existing individual item/option availability permission is unchanged. |
| Staff | `staff_profile.edit_own_draft` | Own linked profile only | Current Phase 1 where policy allows. Staff may edit own draft fields only, cannot self-publish or enable guest visibility. Photo upload remains future. |
| Staff | `STAFF_SCHEDULE_VIEW_OWN` | Own linked profiles in one venue plus safe overlapping colleagues | Current Schedule Phase 1 runtime while the optional module is enabled. The colleague projection may include safe `staffProfileId` plus display/schedule fields; no full roster/calendar, mutation, linked user/account ids, Telegram data, actor metadata or non-overlapping shifts. |
| Staff | `support_ticket.none`, `venue_chat.none`, `feedback.none`, `venue_preview.none`, `billing.none`, `platform.none`, `settings.none` | All scopes | Current product rule. Direct API must return 403/denial even if UI hides nav. |
| Staff | `promotion.manage.none`, `promotion.calculate.none` | All scopes | Phase 1/2 rule. Staff may see persisted order facts but does not configure or calculate promotions. |
| Venue Manager | `order_queue.view`, `order_batch.status_update`, `order_batch.reject` | Own venue | Current where route permissions allow. |
| Venue Manager | `booking.manage`, `staff_call.manage` | Own venue | Current. |
| Venue Manager | `menu.view`, `menu.manage`, `stop_list.manage` | Own venue | Current with policy caveats by route. Conservative target keeps Manager to stop-list/shift check/basic availability unless broad `MENU_MANAGE` is explicitly retained; see `docs/MENU_OPTIONS_STOPLIST.md`. |
| Venue Manager | `MENU_SHIFT_CHECK` | Own venue only | Phase 1 staging-smoke-passed. May prepare a local draft and atomically confirm one bounded availability batch, including no-op completion. |
| Venue Manager | `table.view`, limited `table.manage` | Own venue | Current where backend permission allows; owner-only QR actions must stay denied if configured so. |
| Venue Manager | `support_ticket.manage_own_venue`, `venue_chat.manage_own_venue` | Own venue only | Current support/chat MVP. Venue cannot reply when support ticket is assigned to Platform unless product policy explicitly allows it. |
| Venue Manager | `feedback.view_own_venue`, `feedback.follow_up_low` | Own venue only | Current MVP. Read-only aggregate/list; rating `1..3` follow-up opens exact `VENUE_CHAT`. Public review link edit denied. |
| Venue Manager | `promotion.manage` | Own venue only | Current for informational Phase 1 and the Happy Hours percentage schedule/target/reward/status slice through server-validated routes. |
| Venue Manager | `STAFF_ACCESS_VIEW`, `STAFF_INVITE_CREATE_STAFF`, `STAFF_INVITE_REVOKE_STAFF` | Own venue | Lists current active Staff through the safe identity/link-state projection and pending Staff invites without recipient identity; creates/revokes Staff only. Owner/Manager/Admin identities are not returned as link targets. |
| Venue Manager | `STAFF_PROFILE_MANAGE_STAFF`, `STAFF_PROFILE_PUBLISH_STAFF` | Own venue | Creates/opens and manages display-only/active-Staff-linked cards. One active member has at most one active linked card. Duplicate state is read-only and offers no repair/open/edit/link/unlink authority. Owner/Manager/missing/foreign linkage is protected, `linkedUserId` is redacted and `canManage=false`; duplicate repair is Owner-only. |
| Venue Manager | `staff_shift.manage_today` | Own venue | Current conservative Phase 1 in source `MANUAL`. Source `SCHEDULE` rejects direct manual Today mutation with a typed safe conflict. Manager does not approve public profiles or future tip methods by default. |
| Venue Manager | `STAFF_SCHEDULE_VIEW`, `STAFF_SCHEDULE_MANAGE` | Own venue only | Current Schedule Phase 1 runtime. Same bounded future-shift create/update/cancel authority as Owner; distinct from current Today Shift policy. |
| Venue Manager | `STAFF_MODULE_SETTINGS_MANAGE` | Own venue only | Current Slice B runtime. Narrow full-object Team/Schedule settings authority with CAS and safe transaction-bound audit; it does not grant broad `VENUE_SETTINGS`. Tenant/RBAC denial happens before module-state lookup. |
| Venue Manager | `venue_preview.view` | Own venue only | Current. One endpoint selects `PUBLISHED_PUBLIC` through exact Guest guards or `PRIVATE_DRAFT` through the saved public-facing allowlist. |
| Venue Manager | `billing.none`, dangerous lifecycle none | Billing/platform/lifecycle | Current product rule. |
| Venue Owner | All venue operations inside own venue | Own venue | Current via active `venue_members(role=OWNER)`. |
| Venue Owner | `staff.manage`, `STAFF_ACCESS_VIEW`, `STAFF_INVITE_CREATE_STAFF`, `STAFF_INVITE_CREATE_MANAGER`, `STAFF_INVITE_REVOKE_STAFF`, `STAFF_INVITE_REVOKE_MANAGER`, `menu.manage`, `stop_list.manage`, `table_qr.manage/rotate/export`, `settings.manage`, `staff_chat.link/unlink/test` | Own venue | Keeps the current broad active-member identity projection and staff/profile controls. One-active-link and duplicate guards apply without weakening protected/last-owner constraints; Owner/Admin invite targets remain outside venue flow. Dangerous actions need confirmation/audit. |
| Venue Owner | `MENU_SHIFT_CHECK` | Own venue only | Phase 1 staging-smoke-passed. May prepare a local draft and atomically confirm one bounded availability batch, including no-op completion. |
| Venue Owner | `staff_profile.manage`, `staff_profile.publish`, `staff_shift.manage_today`, `staff_tip_method.approve` | Own venue | Current for Phase 1 profiles + today shift; future for tip method approval. |
| Venue Owner | `STAFF_SCHEDULE_VIEW`, `STAFF_SCHEDULE_MANAGE` | Own venue only | Current Schedule Phase 1 runtime. Bounded list/create/update/cancel with lifecycle, stale-write and audit guards. |
| Venue Owner | `STAFF_MODULE_SETTINGS_MANAGE` | Own venue only | Current Slice B runtime. Manages master, Guest visibility and MANUAL/SCHEDULE source through full-object CAS and transaction-bound audit. |
| Venue Owner | `venue_preview.view` | Own venue only | Current. Server-selected preview is read-only and grants no lifecycle mutation, publication, share-link, auto-save or unsaved-form authority. |
| Venue Owner | `billing.view/pay` | Own venue subscription/payment state | Current manual billing MVP for view/pay surfaces; Platform-only mark-paid/courtesy remain denied. |
| Venue Owner | `support_ticket.manage_own_venue`, `venue_chat.manage_own_venue` | Own venue only | Current. Can transfer support tickets to Platform. |
| Venue Owner | `feedback.view_own_venue`, `feedback.follow_up_low`, `public_review_url.manage` | Own venue only | Current MVP. Public review URL setting is Owner-only and shared by Bot/Mini App. |
| Venue Owner | `promotion.manage` | Own venue only | Current for informational Phase 1 and the Happy Hours percentage schedule/target/reward/status slice. |
| Venue Owner | `venue.lifecycle.request_pause/archive/delete` | Own venue | Target only if product implements owner-requested lifecycle; Platform lifecycle remains Platform Owner. |
| Platform Owner | `platform.venues.manage`, `platform.lifecycle.manage`, `platform.owner_access.manage` | Platform | Current for implemented cockpit/lifecycle/owner access. |
| Platform Owner | `platform.billing.manage`, `platform.support.manage_all`, `platform.analytics.view`, `platform.audit.view`, `platform.settings.manage` | Platform | Billing/support MVP current; analytics/audit explorer partial/future. |
| Platform Owner | Ordinary `VENUE_CHAT` access | Venue chats | Denied by current target unless a future product policy explicitly changes it. |
| Platform Owner | Ordinary venue Guest Preview access | Venue scope | Not granted automatically by platform scope. The Venue preview route requires an allowed OWNER/MANAGER membership in that venue. |
| Platform Owner | Ordinary Staff Schedule access | Venue scope | Not granted automatically in Phase 1. A real own-venue membership and its venue role are required. |
| Platform Owner | Controlled Guest QR test after explicit Telegram confirmation | One currently published, subscription-allowed, active table context | Current bounded runtime. Exact Platform Owner only; no unpublished/subscription/table bypass, no Venue mutation permission and no persistent impersonation. Confirmed context uses ordinary Guest session/tab/API rules until existing visit exit. |

## Surface Parity Matrix

| Feature | Telegram bot | Guest Mini App | Venue Mini App | Platform Mini App | Staff-chat | Rule |
| --- | --- | --- | --- | --- | --- | --- |
| Orders | Guest fallback/order status; venue operational shortcuts where implemented. | Primary guest QR/table order UX. | Primary venue queue/detail source of truth; see `docs/VENUE_OPERATIONS.md`. | No ordinary order workspace by default. | Order notifications/activity cards allowed. | Staff-chat is radar/shortcut, not source of truth. |
| Staff calls | Guest table fallback/actions and staff-chat callbacks where implemented. | Guest create/status. | Venue operations queue. | No. | Allowed for operational staff calls. | Separate from support tickets; Telegram/staff-chat rules in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. |
| Bookings | Guest `/my`, booking actions and venue/admin flows where implemented. | Guest booking/list. | Owner/Manager booking queue/actions; Staff view/arrival/no-show only. | Platform only if future analytics/audit requires. | Booking operational notifications allowed by existing policy. | Booking lifecycle follows `docs/BOOKING_LIFECYCLE.md`; booking chat stays `BOOKING_CHAT`, not support. |
| Support tickets | `/support` fallback where implemented. | Guest `Помощь`. | Owner/Manager `Обращения` for own venue. | Platform `Обращения` / Support Center. | Never for support tickets. | Staff denied. Platform sees support, not ordinary venue chats. |
| Venue chats | Guest bot/Mini App entry where implemented. | Guest `Чаты`. | Owner/Manager `Сообщения`. | No by default. | Never for ordinary venue chats. | Staff denied. |
| Post-visit feedback | No automated prompt; public review setting uses the same backend source as Mini App. | Submit only from own completed History detail; optional explicit Yandex CTA after `5/5`. | Owner/Manager read list and open low-rating exact `VENUE_CHAT`; Owner edits public review URL; Staff denied. | Feedback analytics dashboard future. | Never. | No auto support ticket, Owner message, Telegram prompt or public redirect. |
| Booking chats | Booking action `Открыть переписку`. | Guest `Чаты`. | Owner/Manager `Сообщения`. | No by default. | Notification mirror only where existing policy allows. | Must not become support queue. |
| Menu/stop-list | Bot owner/manager/staff paths where implemented; no Phase 1 shift-check UI. | Guest read/order only after QR. | Owner/Manager manage and have `MENU_SHIFT_CHECK`; Staff keeps individual availability only and has no shift-check entry/API. | No ordinary menu management or automatic Venue shift-check authority. | No source-of-truth edits. | Shift-check batch is own-venue, atomic and audited; existing individual stop-list policy is unchanged. |
| Promotions | Existing Telegram templates and shared server-owned Happy Hours preview/submit. | Informational read plus current server-owned Happy Hours cart breakdown/submit. | Owner/Manager manage informational and bounded Happy Hours rules; Staff denied. | No ordinary venue promotion management. | Persisted order facts only. | One backend engine; Bot/Mini App clients never calculate trusted discounts. |
| Venue card preview | Existing owner/manager guest-preview callbacks. | The real published public card. | One `Предпросмотр для гостя` renderer: server-selected `PUBLISHED_PUBLIC` uses the exact Guest read model; `PRIVATE_DRAFT` uses an own-venue saved public allowlist. Staff has no entry. | No automatic access. | No. | No Guest bypass, mutations, public URL, share token or cache. Private media delivery is authenticated and venue/section/media-scoped; raw refs are excluded. |
| Tables/QR | Bot management where implemented; exact Platform Owner has opaque confirm/cancel for a guarded Guest QR test. | QR context only; confirmed test uses ordinary Guest `mode=guest`. | Owner/Manager table/QR where allowed; Staff read-only and receives no test escape. | No ordinary venue table management; tokenless `/start` stays in active confirmed Guest routing until explicit exit, otherwise opens Platform Mode. | No. | QR/pending reference is context only; activation atomically revalidates actor context, token, venue, subscription and table before apply. Old Mini App token/session entry after exit fails closed. |
| Staff invites | Bot invite acceptance. | No. | Owner/Manager invite where allowed. | OWNER invite/revoke. | No. | Last-owner protection server-side. |
| Staff schedule | No Phase 1 flow. | Current public presence only through the shared Guest resolver; no direct schedule access. | `STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`: Owner/Manager `График смен`; Staff read-only `Мои смены`; explicit restore/bulk is smoke-passed. Slice B guards these surfaces when master is off. | No automatic authority. | No Phase 1 flow. | Venue Mini App is source of truth; effective hours are defaults only. `MANUAL` and `SCHEDULE` never fall back to each other or expose future/full/private schedule data. |
| Settings | Bot owner/manager setup where implemented. | No management. | Owner/Manager settings where allowed; Slice B Team/Schedule settings use the narrow own-venue permission; Staff none. | Platform settings for platform scope; no automatic Venue Slice B authority. | No. | UI hiding is not enough; tenant/RBAC checks precede module-state reads. |
| Billing | Bot/platform/owner messaging where implemented. | No. | Venue Owner view/pay state; Manager/Staff none. | Platform billing cockpit. | No. | Money mutations need explicit POST and audit. |
| Analytics | Bot stats where implemented. | No dashboard; future profile summaries only. | Owner/Manager role dashboards where reliable. Staff operational counters only. | Platform analytics future/partial. | Delivery telemetry only. | Analytics events are not operational truth. |
| Platform lifecycle | Platform bot where implemented. | No. | No direct platform lifecycle. | Platform Owner cockpit. | No. | Requires confirmation/reason/audit where implemented. |

## Dangerous Actions

These actions require server-side authorization and should require confirmation, reason and/or audit according to risk and current implementation:

| Action | Required safety |
| --- | --- |
| Venue staff role changed or member removed; last-owner mutation attempted | Owner-only Mini App and Telegram mutations share one deterministic membership-lock transaction. A real applied role change/removal writes exactly one targeted audit before commit; last-owner, denial, no-op, not-found, invalid and rollback paths write none. Platform owner-access mutations remain a separate slice. |
| Venue published/hidden/paused/suspended/archived/deleted | Confirmation and audit with reason/status where implemented. |
| Table QR token rotated/exported | Confirmation and audit; old/revoked token must not resolve. |
| Platform Owner confirms controlled Guest QR test | Exact Platform Owner + exact chat + unexpired opaque pending only; one conditional consume wins, then commit `PLATFORM_GUEST_QR_TEST_CONFIRMED` before atomic Guest context activation. Audit uses standard actor plus safe venue/table/source only and excludes raw token/hash, callback, initData and Telegram PII. It records confirmation only and is not `GUEST_CONTEXT_APPLIED`; final token/venue/table/public-availability/subscription revalidation and all authoritative Guest-state writes share the activation transaction. |
| Staff chat linked/unlinked/tested | Confirmation for unlink; audit/link evidence without raw secrets. |
| Menu price changed; item archived/deleted; option schema changed; media removed; Staff stop-list toggled; stop-list mass update | The existing item hard-delete paths now require one transaction-bound `MENU_ITEM_DELETED`; authenticated actor and `VENUE_MINI_APP` / `TELEGRAM_BOT` source are server-derived. Other menu families still require safe old/new audit. Shift-check completion requires explicit confirmation and exactly one `MENU_SHIFT_CHECK_COMPLETED` audit in the same transaction, including no-op completion. |
| Venue media uploaded/replaced/hidden/shown/deleted | OWNER/MANAGER own venue only; strict content validation; audit safe asset/status metadata; never expose source ref, object/path key, Telegram file id or storage credentials. |
| Promotion lifecycle status changed or promotion archived | Owner/Manager own venue only. Mini App and Telegram use one repository transaction for the locked parent, deterministic rule synchronization and exactly one `VENUE_PROMOTION_STATUS_CHANGED` or `VENUE_PROMOTION_ARCHIVED` audit. Actor and `VENUE_MINI_APP` / `TELEGRAM_BOT` source are server-derived; denial, stale/repeated/no-op, validation failure, audit failure and rollback write no success audit. |
| Promotion created | Owner/Manager own venue only. Mini App and Telegram pass authenticated actor plus server-owned source to the single repository create transaction. Parent, caller-connection initial rules and exactly one `VENUE_PROMOTION_CREATED` commit or roll back together. Payload is limited to venue/promotion/template identity, `DRAFT`, source and ordered rule id/version/status rows. |
| Promotion schedule, eligibility, reward, compatibility mode/matrix or priority changed | Future audit coverage: record actor, safe old/new rule/version or policy/version and priority without configuration JSON, prices, reward/menu names or unrelated PII. |
| Order force closed; tab reopened | Reason and audit; preserve session/tab boundaries. |
| Invoice manually marked paid; subscription override changed; billing provider config changed | Platform Owner only, explicit action, reason where needed and safe audit. |
| Support ticket transferred/closed/assignee changed | Audit status/scope/actor/source; no message text/raw Telegram payloads. |
| Analytics export | If implemented, audit export actor/scope and exclude raw PII/message text/payment secrets. |
| Staff profile published/hidden, public photo changed, Today Shift marked active/canceled, future tip method updated/approved/disabled | Audit actor/target/old-new safe fields; never expose private Telegram ids or raw external payment/provider data. |
| Staff invite created/revoked | Transaction-bound `STAFF_INVITE_CREATED` / `STAFF_INVITE_REVOKED`; actor column plus venue, opaque handle and safe Staff/Manager role only. No code/hash/deep link or identity payload. |
| Staff profile created/updated/published/hidden | Transaction-bound `STAFF_PROFILE_*`; target membership and one-active-link check share the transaction. Audit contains safe venue/profile id, linkage/target-role class, changed field names and old/new visibility/linkage only. Denial/duplicate/no-op/rollback has no success audit or Telegram identity. |
| Staff Schedule shift created/updated/canceled/restored | Owner/Manager own venue only; update preview and cancel confirmation; active cancel has stronger warning; optimistic stale rejection; `STAFF_SHIFT_CREATED/UPDATED/CANCELED/RESTORED` audit is atomic with safe old/new interval/lifecycle/timezone fields and no private linkage/raw request. |
| Staff module settings updated | Owner/Manager own venue only through `STAFF_MODULE_SETTINGS_MANAGE`; full-object CAS and `STAFF_MODULE_SETTINGS_UPDATED` audit are atomic. No-op, stale, denial, audit failure or rollback writes no success audit; payload contains only safe old/new setting values and changed field names. |

### STAFF ROLE / REMOVAL AUDIT target-identity contract

Status: **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.

The privacy gate is complete. `target_user_id is permitted only as a dedicated internal audit column. It remains prohibited in JSON, logs, errors and client projections.` PostgreSQL V122 and
H2 V123 add nullable `audit_log.target_user_id BIGINT`, the named foreign key
`fk_audit_log_target_user` to `users.telegram_user_id` with `ON DELETE SET NULL`, and
`idx_audit_log_target_user_created_at (target_user_id, created_at)`. Existing rows and legacy audit
inserts keep `target_user_id = NULL`.

- Actor identity remains only in `audit_log.actor_user_id`; target identity remains only in
  `audit_log.target_user_id`. Neither identity is duplicated in `payload_json`.
- `VENUE_STAFF_ROLE_CHANGED` uses entity `venue` / `venueId` and payload `oldRole`, `newRole`,
  `source`. `VENUE_STAFF_MEMBER_REMOVED` uses the same entity and payload `oldRole`, `source`.
  Source is server-owned `VENUE_MINI_APP` or `TELEGRAM_BOT`; `venueId` is not duplicated in payload.
- Audit payloads and custom logs/errors exclude target IDs, actor duplicates, display name,
  username, phone, profile/link content, invite data, raw requests, callback/initData, secrets and
  unrelated PII. Telegram messages and client projections never expose `target_user_id` or use a
  raw target-ID fallback; the existing permitted human identity display remains unchanged.
- One audit-aware repository transaction locks actor, target and every Owner membership in
  deterministic `user_id` order, rechecks current Owner authority and target membership, enforces
  last-owner policy, applies the mutation and writes the targeted audit on the same JDBC connection.
  Audit failure rolls the membership mutation back.
- Audit exists only for `APPLIED`. Same-role `NO_OP`, repeated/not-found removal, invalid role,
  current non-Owner/foreign actor, last-owner mutation, audit failure and any rollback write zero
  success rows. Existing HTTP/Telegram response policy and Mini App controls are unchanged.
- P2 follow-up: the existing cross-dialect `actor_user_id` nullability/FK inconsistency remains
  unchanged and must be handled separately; this slice does not alter the actor column.

### MENU ITEM HARD DELETE AUDIT contract

Status: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

- A committed hard delete writes exactly one `MENU_ITEM_DELETED` for entity `menu_item` / item id.
  Venue Mini App and the existing Telegram menu-management callback pass only the authenticated
  session/callback user plus server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`; client actor/source is
  never accepted.
- The existing JDBC transaction keeps promotion-parent then promotion-rule then item lock order,
  rechecks the authoritative reference snapshot, derives the affected rule ids, bumps the current
  rule versions, deletes the item and appends audit before one commit. Audit, SQL, reference or
  concurrency failure rolls back item, promotion references/version changes and audit together.
- Payload is exactly `venueId`, `itemId`, `categoryId`, `source` and
  `affectedPromotionRules { totalCount, sampleRuleIds, omittedCount, sha256 }`. IDs are deduplicated
  and sorted; sample is the first 50; hash is lowercase SHA-256 over UTF-8
  `v1:` plus the complete sorted set joined by comma. The full list is never stored and payload is
  below 4096 UTF-8 bytes.
- Payload excludes menu/category/option names, prices, media, promotion titles/config/schedules/
  rewards, raw requests/callbacks/initData, Telegram fields, secrets and unrelated PII. Not-found,
  repeated delete, denial and rollback write zero success audit rows. Existing response and
  OWNER/MANAGER `MENU_MANAGE` policy are unchanged; STAFF/foreign/unaffiliated actors remain denied.
- An authoritative fixed-reward dependency inside the locked transaction returns HTTP `409` /
  `MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD` with the safe replacement/change next step before any
  write. Item, fixed reward, versions, statuses and timestamps remain unchanged and no success audit
  is written. Purchase-target and CHOICE allowlist deletion remains allowed; remaining CHOICE
  options stay configured, the last option removes incomplete reward configuration, lifecycle
  status is unchanged and no fixed reward is replaced automatically.
- Schema verdict: `NO_MIGRATION_EXPECTED`; no migration was added. Category/option delete, menu
  price/update/availability audit, archive schema, media and audit viewer remain outside this slice.

These bounded menu, staff and promotion creation/lifecycle slices do not close the overall dangerous-action audit.
Promotion configuration edit, remaining menu price/category/option/update/availability audit, QR rotate, force-close/session, tab reopen,
analytics export, the Promotion Compatibility Policy and a broader audit viewer remain future.

## Current Implementation Vs Target

| Area | Current implementation from docs | Target product model | Gap / risk / future note |
| --- | --- | --- | --- |
| Core RBAC | Runtime uses venue memberships and platform owner resolver; many route tests and smokes exist. | Every endpoint verifies actor, scope and entity ownership. | Permission parity remains `PARTIAL` until every new route has direct denial tests. |
| `ADMIN` role | Legacy DB alias maps to `MANAGER`; Platform Mini App no longer exposes it. | Remove from product model; keep only compatibility alias. | Open migration/cleanup hygiene until no docs/copy/data imply separate Admin. |
| Guest order/tab privacy | Current docs say table-session/tab scoping is closed. | Guest reads/writes own personal tab or joined shared tab only. | Keep two-guest and shared-tab privacy smoke in regression. |
| Staff access | Staff support/venue-chat denial and operational scope are documented/smoked for current MVP. Current menu docs allow Staff item/option availability. | Staff sees operations only: orders, staff calls, allowed booking actions, menu/table read and stop-list only when enabled by venue policy. | Direct API denial tests remain critical for every new support/chat/billing/settings/menu route. |
| Menu shift check | OWNER/MANAGER own-venue `MENU_SHIFT_CHECK`, Staff/foreign denial, bounded input, ownership checks, optimistic stale rejection and transactional audit are staging-smoke-passed. | One authenticated actor confirms one all-or-nothing availability review; the client supplies no actor, owner, names, prices or private metadata. | Keep role/tenant/audit denial in regression. Platform Owner receives no automatic Venue-route authority; Telegram shift-check UI is not part of Phase 1. |
| Menu item hard-delete audit | **DONE / MVP / STAGING-SMOKE-PASSED**. Venue Mini App and existing Telegram item-delete management paths require authenticated actor plus server-owned source and one transaction-bound `MENU_ITEM_DELETED`. | One committed item delete, related promotion reference cascades/version bumps and exactly one bounded privacy-safe audit commit atomically. | This closes only item hard delete after green Actions, staging deploy and bounded smoke; other menu mutation audit families remain partial. |
| Manager/Owner venue isolation | Own-venue RBAC is the product rule. | No cross-venue detail/reply/manage access. | Keep cross-venue tests for support, chats, orders, bookings and settings. |
| Platform access | Platform Owner can manage platform scope and support tickets; ordinary venue chat is hidden. The bounded confirmed QR test enters the normal public Guest table flow only. Activation is atomic; teardown uses stored context identity and remains possible when token/table/venue/subscription becomes unavailable. | Platform does not bypass ordinary venue RBAC. Explicit Guest context temporarily wins routing only for ordinary Guest actions and is cleared by existing visit exit. Mini App re-entry requires matching chat context and no exit marker. | Controlled QR Phase 1 is staging-smoke-passed and stays in regression; event/audit explorer and analytics exports still need additional privacy gates before broad release. |
| Dangerous action audit | Several audits exist: owner invite/revoke, billing mark-paid/courtesy, staff-call ACK/DONE, support status/scope, lifecycle/status where implemented. | All dangerous actions write safe actor/target/old-new/reason evidence. | Audit coverage remains `PARTIAL` until menu price, QR rotate, force close, tab reopen and analytics export are verified. |
| Staff role/removal audit | **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Venue Mini App and Telegram use one locked transaction and targeted audit column for applied Owner-authorized mutations. | Exactly one safe audit row for an applied role change/removal; zero for no-op, denial, last-owner, not-found and rollback. | Local H2/PostgreSQL repository/routes/Telegram/concurrency/privacy evidence and the bounded staging role/parity/privacy smoke are recorded passed. Promotion config, menu price/archive, force-close/session, audit viewer and other dangerous actions remain partial. |
| Promotion lifecycle status audit | **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Mini App status/archive routes and Telegram activate/pause/archive callbacks pass authenticated actor plus server-owned source to one repository mutation. Parent status, synchronized rule statuses and one audit row share one JDBC connection and transaction; an audit failure rolls every lifecycle write back. | A committed real transition writes exactly one action: `VENUE_PROMOTION_STATUS_CHANGED` or `VENUE_PROMOTION_ARCHIVED`. Payload contains only `venueId`, `promotionId`, `templateType`, old/new status, source and deterministic rule id/version/old/new status rows; actor stays in the standard audit actor column. | This closes only lifecycle status/archive. Promotion configuration edit and the wider dangerous-action audit remain future; no-op, stale, repeated archive, denial, invalid/not-found and rollback paths have no success audit. Owner/Manager/Staff/foreign RBAC, Telegram/Mini App parity and payload privacy passed staging smoke. |
| Promotion creation audit | **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Mini App and Telegram pass only authenticated/current actor and server-owned source to one required repository contract. | One committed parent writes exactly one `VENUE_PROMOTION_CREATED` for entity `venue_promotion`; parent, Mini App initial rule and audit share one JDBC connection/transaction. Informational and Telegram Happy Hours/Gift parent-draft creation use `rules=[]`; Mini App Happy Hours/Gift records the actually created initial rule. Payload contains only `venueId`, `promotionId`, `templateType`, `status=DRAFT`, source and ordered rule id/version/status rows. | Staff/foreign/invalid/validation/`afterInsert`/SQL/rollback paths write no success audit; audit failure rolls back parent and initial rules and yields no false Mini App/Telegram success. Promotion text/config/prices/media, Telegram PII and unrelated PII are excluded. Configuration edit, schedule/target/reward, media/banner, Banner retry duplicate-draft UX and broader dangerous-action audit remain open. |
| Promotion financial compatibility | Current slices have bounded percentage/manual-discount and gift reward guards, but no documented common cross-promotion conflict policy. Gift smoke observed Happy Hours Percentage and Gift With Item together; this is not a confirmed runtime bug. | One server-owned, reward-type-aware policy uses `STACKABLE`, `EXCLUSIVE` or `OVERRIDE`, explicit priority and deterministic winner/tie-break rules for all executable promotions and manual discounts. | `AUDIT / FUTURE IMPLEMENTATION`. Fail closed against accidental discount addition; later loyalty, promo codes and cashback must reuse the same mechanism. |
| Staff profiles / today shift | `STAFF IDENTITY LINKING UX + DUPLICATE PREVENTION / DONE / MVP / STAGING-SMOKE-PASSED`; canonical model is `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. | Accepted members use current `users` identity. Private profiles carry server-computed `linkageClass/canManage/isSelf`; Manager protected/duplicate cards redact raw linkage and are read-only. Create-from-member derives identity and always creates a Guest-hidden active draft; generic create is display-only. One active link is serialized on `venue_members`; duplicate repair is Owner-only and never automatic. | Keep raw-response privacy, subtype validation, Manager Staff-only policy, PostgreSQL double-create/relink concurrency, winner-only audit, stale-directory fail-closed, account/venue switch and Guest visibility in regression. The deferred free-member manual scenario does not downgrade the completed MVP. |
| Staff Schedule Phase 1 | `STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`; Canceled Shift Restore + Bulk Assignment is `DONE / MVP / STAGING-SMOKE-PASSED`. Explicit runtime permissions/routes/UI reuse `staff_shifts` and `updated_at`; the Phase 1 schedule-schema verdict remains `NO_MIGRATION_EXPECTED`. | Owner/Manager manage own-venue planned shifts; effective opening hours are create defaults only; Staff reads own plus safe overlapping colleagues while the optional module is enabled; Guest/foreign/Platform-only direct schedule access is denied. | Keep route denial/privacy, effective-hours, atomic audit, restore/bulk, CAS, module guards and Today/Guest resolver behavior in regression. |
| Staff Operations Slice B | `DONE / MVP / STAGING-SMOKE-PASSED`. | Narrow Owner/Manager settings permission; Staff own schedule only while enabled; Guest/Preview safe public projection only; foreign/Platform-only denied without a module-state oracle. Core memberships/invites/roles and other operational permissions remain independent of the optional module. | Master-off retains rows, guards only profile/Today/schedule routes after tenant/RBAC and returns empty Guest/Preview projection. `SCHEDULE` is ACTIVE `[start,end)` only with no MANUAL fallback or private/future schedule fields. Staging verified role denial, narrow Manager authority, CAS, privacy and re-enable. |
| Venue card preview | **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**. | One OWNER/MANAGER own-venue endpoint selects exact guarded Guest state or a saved, server-allowlisted private projection through the shared public-facing assembly. | Keep direct role/foreign/lifecycle denial, dirty-form no-auto-save, private-marker absence, `no-store`, authenticated media scoping and stale-state isolation in regression. |
| Post-visit feedback | History-only submit, own-venue Owner/Manager read, Owner-only public review URL and low-rating exact `VENUE_CHAT` follow-up are DONE / MVP / staging-smoke-passed. | Preserve own-visit/own-venue isolation, Staff denial and manual-only external/follow-up actions. | Platform feedback dashboard, automated prompts and public review automation remain future/disabled. |
| Staff tips | No runtime implementation yet; canonical future boundaries are `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. | Phase 2 external staff tip link + intent only; money does not touch platform in MVP; intent is not proof of payment. | Provider/direct payout needs legal/product decision; Telegram Stars and crypto are not MVP. |
| Surface parity | Bot and Mini App parity is closed for several slices; some Telegram flows are still richer. | Required product surfaces are aligned or explicitly documented as exceptions. | Keep parity roadmap current before adding new management functions. |

## Security Smoke Checklist

1. Guest cannot access venue or platform APIs.
2. Guest cannot see another guest's personal tab.
3. Guest cannot add a batch to another guest's personal/shared tab without membership.
4. Staff cannot see support tickets.
5. Staff cannot see ordinary venue chats.
6. Staff cannot access billing, settings or platform routes.
7. Manager cannot access billing payment controls.
8. Manager cannot remove or create Owner access.
9. Venue users cannot access another venue's orders, bookings, support tickets, chats, settings or analytics.
10. Platform Owner can see support tickets but not ordinary venue chats.
11. Platform lifecycle actions require confirmation/reason/audit where implemented.
12. Table token does not grant admin rights.
13. Revoked QR token does not resolve.
14. Staff-chat button actions verify role, venue scope and entity state server-side.
15. Analytics export, if implemented, contains no raw PII, message text, raw initData, provider payloads, payment secrets or card data.
16. Guest staff-profile APIs expose only published public-card fields selected by the shared Today
    resolver: exact manual publication in `MANUAL`, current active presence in `SCHEDULE`, or an
    empty team when master/Guest visibility is off. They expose no full/future schedule,
    `linked_user_id`, membership role or private Telegram/contact data.
17. Staff cannot publish their own profile or enable guest visibility without Owner approval.
18. Staff tip intent, when implemented, does not create provider payment, close bill or prove payment.
19. Guest can submit feedback only for their own visible completed History visit; foreign/hidden/non-seated visits are denied safely.
20. Staff cannot read venue feedback, trigger follow-up or edit/view the Owner settings control for public review URL through direct API.
21. Venue Owner/Manager cannot read or follow up feedback from another venue; Manager cannot edit the Owner-only public review URL.
22. Low-rating follow-up opens only the feedback guest's exact `VENUE_CHAT`, creates no support ticket and posts nothing to staff-chat.
23. A public review URL reaches Guest only after manual `5/5`, only when validated/configured, and never causes an automatic redirect.
24. Promotion compatibility, priority and winner resolution are server-owned and deterministic;
    clients cannot submit a trusted mode, priority, winner, discount or reward.
25. Promotion compatibility changes are limited to Owner/Manager in their own venue and write safe
    policy/version, old/new value and actor audit evidence; Staff has no configuration bypass.
26. Manual discount policy and every executable reward use one compatibility decision that fails
    closed against accidental discount addition; future loyalty, promo codes and cashback cannot
    bypass it.
27. One Venue Guest Preview endpoint, `GET /api/venue/{venueId}/guest-preview`, selects mode
    server-side. `PUBLISHED_PUBLIC` continues to require the exact Guest lifecycle/subscription
    availability guards and exposes the same venue/info DTO state as Guest; no query or client
    mode can bypass those guards.
28. `PRIVATE_DRAFT` allows only OWNER/MANAGER of the own venue when public Guest assembly is
    unavailable but a private saved projection is permitted. STAFF, foreign venue users,
    Platform-only access and missing/ARCHIVED/DELETED venues are denied safely; Guest routes cannot
    reach the projection.
29. Preview responses are read-only, `no-store` and allowlisted. They expose no private settings
    DTO, hidden sections/media, unpublished staff, inactive/non-current promotions, raw media refs,
    mutation actions, public links or share tokens. Existing private-preview media is delivered
    only through an authenticated venue/section/media-scoped route. Unsaved public-card,
    weekly-schedule or date-exception state blocks navigation and is never auto-saved.
30. Venue media upload/manage allows OWNER/MANAGER for the own venue only; STAFF, Guest,
    Platform-only and foreign venue direct requests are denied before storage access.
31. MIME spoofing, over-limit files/dimensions, WebP/SVG/archive/executable and PDF on a disallowed
    surface are rejected server-side; filename and browser `Content-Type` are never authority.
32. Guest and both preview-mode DTOs, responses, errors, logs and audit contain no raw Telegram
    `file_id`, object key, filesystem path, storage credential or provider payload.
33. OWNER and MANAGER can call menu shift check only for their own venue; STAFF, Guest,
    Platform-only and foreign venue requests are denied.
34. Shift-check input accepts only changed item/option ids, expected/desired availability and the
    option's owning item id; unknown fields, duplicates, invalid ids and more than 500 combined
    changes are rejected.
35. Missing/foreign items or options and option/item ownership mismatch apply no partial changes
    and create no completion audit.
36. A stale expected item or option availability rejects the whole batch.
37. Successful mixed and no-op confirmations each create exactly one safe
    `MENU_SHIFT_CHECK_COMPLETED` audit in the same transaction; invalid/RBAC/rollback paths create
    none.
38. Existing Staff individual item/option availability routes and Telegram stop-list callbacks
    remain unchanged.
39. Schedule Owner/Manager full reads and mutations require own-venue membership and the enabled
    optional module; Staff, Guest, foreign venue and Platform-only direct requests are denied.
40. Staff schedule read derives the current user server-side, returns only own shifts plus
    non-canceled overlapping colleagues, excludes every profile linked to that same user from the
    colleague side, and may expose the colleague's safe `staffProfileId` but no linked user/account
    id, Telegram/member id, username, invite state, actor metadata, private notes or unrelated venue
    schedule.
41. Schedule create writes guest visibility false. A planned/future shift never reaches Guest;
    `MANUAL` requires the explicit Today publication, while `SCHEDULE` ignores manual flags and
    returns only current active public presence with no fallback.
42. Schedule update/cancel requires the expected `updatedAt`; cancel also compares a
    non-authoritative expected confirmation state with freshly computed lifecycle. Stale data or a
    `SCHEDULED -> ACTIVE` boundary rejects the whole mutation and writes no audit.
43. Schedule create/update/cancel/restore writes exactly one transaction-bound safe audit; no-op update,
    invalid input, denial, conflict and rollback write none.
44. Accepted member projection reads `first_name`, `last_name` and nullable `username` from the
    existing `users` row refreshed by Bot/Mini App authentication; no second identity cache exists.
45. Manager receives active Staff members only; Owner retains the current permitted active-member
    projection. Staff, Guest, Platform-only and foreign actors cannot read the directory.
46. Member DTO and UI expose only safe display name, optional username, role, active/link state and
    safe profile reference. Phone, invite secret, raw `initData`, private notes/audit metadata and
    Telegram identity in Guest DTOs remain absent; full user id is not the primary label.
47. Create/relink/reactivate locks the target `venue_members` row, validates role/scope and checks
    active linked profiles before transaction-bound mutation/audit. Two concurrent requests produce
    one active profile and one typed conflict without a process-local lock.
48. Existing multiple active profiles return `DUPLICATE_LINK_DETECTED`, show the safe warning and
    remain distinct. Manager state is read-only. No automatic merge/delete/relink or frontend
    Schedule dedupe occurs; only Owner opens the concrete wrong card and uses safe unlink.
49. Pending invites contain no recipient identity. Accepted membership replaces pending state with
    the current safe projection; venue/account switches clear cached identity/link state.
50. Platform Owner `/start` without a token shows Platform menu only without active confirmed Guest
    context. While context is active it keeps Guest routing and shows the table menu or safe exit instruction.
51. A valid table token shows safe public venue/table labels but does not mutate Guest context,
    table session, exit marker, persisted dialog, booking draft, cart/draft or success audit before
    explicit confirmation.
52. Confirm requires the exact Platform Owner, chat, opaque reference and live TTL. Exactly one
    conditional consume wins; then the safe confirmation-only audit is written before one transaction
    finally re-resolves token and guards and atomically changes session, exit, dialog and exact context.
    Audit failure creates no Guest context; activation failure rolls back authoritative Guest state.
53. Guest, Staff, Manager, Venue Owner, inactive/non-owner actor, wrong chat/reference, expired,
    canceled, missing and already consumed pending callbacks fail closed without an existence oracle.
54. Confirmed Platform Owner receives only ordinary Guest table/session/tab/API and Mini App
    `mode=guest` behavior. Mini App create/touch and explicit old session require matching active chat
    context and no exit marker; denial creates no session/tab, touches nothing and clears no exit.
55. Every exact-Platform table-bound order/bill, staff-call, tab, shift-extension and support mutation
    shares the exit chat-context lock and commits final authorization with the domain write. Support
    `tableToken` without `tableSessionId` derives identity/session only from the confirmed context;
    missing, exited or mismatched context returns the same safe denial and writes no ticket/message/read row.
56. `Завершить визит` clears context/dialog/cart/draft/pending and preserves exit semantics even after
    token rotation/revoke, table disable/delete, venue pause/unpublish or subscription block, then
    restores Platform precedence. Ordinary Guest and venue-role behavior remains unchanged.

## Roadmap Status

- Security/RBAC matrix: `UPDATED`.
- Permission parity: `PARTIAL`; keep route-level denial tests and role smoke in regression.
- Staff profiles / today shift and Identity Linking UX + Duplicate Prevention are `DONE / MVP /
  STAGING-SMOKE-PASSED`; Manager duplicate state is read-only and repair is Owner-only.
- Staff Operations Slice A / Manager Parity + Shift Time Defaults is `DONE / MVP /
  STAGING-SMOKE-PASSED`.
- Staff Schedule Phase 1 and Canceled Shift Restore + Bulk Assignment are `DONE / MVP /
  STAGING-SMOKE-PASSED`; the Phase 1 schedule and identity-linking changes required no migration.
- Invite revoke uses PostgreSQL V120/H2 V121 and its rollout/manual smoke are complete.
- `STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE /
  DONE / MVP / STAGING-SMOKE-PASSED`. Narrow settings RBAC, master-off retention/core access,
  MANUAL/SCHEDULE Guest privacy and safe stale-CAS behavior passed the bounded staging smoke after
  green Actions, deploy and PostgreSQL V121 application.
- Guest Preview Phase 2.1: **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**; focused preview/Guest/RBAC/promotion tests, compile/lint, Mini App build and deterministic smoke `95/95` passed, GitHub Actions were green, staging deploy completed and manual staging smoke passed.
- Staff tips: `SPEC DRAFT / FUTURE`; payment provider/direct payout requires legal/product decision, and external tip intent is not proof of payment.
- `ADMIN` decision: target is removal from product model / compatibility alias only; implementation cleanup remains a migration/copy hygiene follow-up.
- Staff stop-list parity: current docs say operational item/option availability is aligned; per-venue `staff_stoplist_enabled` is target/future in `docs/MENU_OPTIONS_STOPLIST.md`.
- Menu shift check: **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**; keep
  role/tenant/audit denial and unchanged Staff individual stop-list policy in regression.
- Menu item hard delete: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**; existing Mini App and Telegram management writers are actor-bearing and
  transaction-bound. Green Actions, staging deploy and bounded blocked/allowed smoke are recorded
  complete. No migration was added.
- Dangerous action audit: `PARTIAL` until all listed dangerous actions have verified audit evidence.
- Controlled Platform Guest QR test: **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**; schema verdict `NO_MIGRATION`, with the bounded role/privacy/exit regression complete.
- Promotion lifecycle status audit: **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**; configuration/create audit and the broader dangerous-action audit remain future.
- Promotion Compatibility Policy: `AUDIT / FUTURE IMPLEMENTATION`; no common cross-promotion
  financial conflict policy has implementation or verification evidence yet.
- Security smoke checklist: `UPDATED`.
- Post-Visit Feedback RBAC/privacy: `DONE / MVP / STAGING-SMOKE-PASSED`; Platform feedback dashboard remains `FUTURE`.
- Venue Mini App media foundation: security contract is canonical in
  `docs/MEDIA_STORAGE_UPLOAD.md`; runtime is `MISSING` and storage selection is
  `STOP_FOR_MEDIA_STORAGE_DECISION`.
