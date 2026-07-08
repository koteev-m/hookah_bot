# Security / RBAC Permission Matrix

Дата актуализации: 2026-07-07.

Статус: **current product reference / UPDATED**. Runtime permission parity is **PARTIAL** unless a specific route, test or smoke result is cited by the relevant implementation task. Venue Mode operational surfaces are detailed in `docs/VENUE_OPERATIONS.md`; staff profile/today-shift/tips permissions are detailed in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`; booking lifecycle permissions are detailed in `docs/BOOKING_LIFECYCLE.md`; Telegram fallback/staff-chat permissions are detailed in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; menu/stop-list role policy is detailed in `docs/MENU_OPTIONS_STOPLIST.md`; validation strategy is detailed in `docs/TESTING_QA_SMOKE_STRATEGY.md`; release/deploy operations are detailed in `docs/DEPLOYMENT_RUNBOOK.md`.

## Core Rule

Server-side RBAC is the source of truth. Mini App navigation, Telegram keyboards and hidden buttons are convenience only; every read/write must verify actor, role, scope and entity ownership on the backend.

Tokens and client-provided ids are context pointers, not authority:
- Telegram `initData` must be validated server-side; `initDataUnsafe` is never trusted.
- QR/table token resolves table context but does not grant venue/admin rights.
- `table_session_id` is required for in-venue guest actions that mutate orders, tabs, staff calls or bill requests.
- Tab invite token points to a shared-tab invitation; membership and state are still verified server-side.
- Telegram `callback_data` must use opaque ids/tokens and must not contain secrets, raw provider data, raw initData, raw message text or unrelated PII.
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
| `staff_profile` | Public staff display profile and linked private venue-member relation. | Guest sees only opt-in public fields; linked user ids stay private; Owner controls publish/hide. |
| `staff_shift` | Manual "today on shift" visibility for public staff profiles. | Guest sees only visible shifts for visible profiles; Manager shift marking depends on venue policy. |
| `staff_tip` | Future staff-specific tip method/intent. | External tip link + intent only in Phase 2; money does not touch platform in MVP; intent is not proof of payment. |
| `billing` | Subscription, invoices, payments and commercial terms. | Platform Owner manages; Venue Owner views/pays where implemented; Manager/Staff none. |
| `analytics/audit` | KPI dashboards, event facts and critical-change evidence. | Role-specific views; raw event/audit payloads are restricted and privacy-filtered. |

## Roles

| Role | Product meaning | Current / target note |
| --- | --- | --- |
| Guest | End user without venue/platform role. | Can browse, book, order in verified table session, use own chats/tickets and own tabs. |
| Tab Host | Guest who creates/hosts a shared tab. | Derived responsibility inside `tab` scope, not a global role. |
| Tab Member | Guest who joined a shared tab by invitation/consent. | Derived responsibility inside `tab` scope, not a global role. |
| Staff | Shift operations role. | Orders, operational calls, allowed booking arrival/no-show and stop-list availability only. No support tickets, venue chats, billing, settings or platform. |
| Venue Manager | Venue operations management role. | Own venue only. Can manage bookings, orders, menu/availability, tables where allowed, chats and own-venue support. No platform/billing commercial controls. |
| Venue Owner | Venue owner role through active `venue_members(role=OWNER)`. | Own venue operations, staff, settings, staff chat and venue billing view/pay where implemented. |
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
| Guest | `staff_profile.view_public`, `staff_shift.view_public` | Published venue public staff data | Target Phase 1. Guest sees only public visible profiles/shifts and never `linked_user_id`, Telegram ids or private contacts. |
| Guest | `staff_tip.intent_create` | Visible/tips-enabled staff profile | Future Phase 2. Creates intent/clickout only; no platform payment and no proof of payment. |
| Guest | `support_ticket.create_own`, `support_ticket.view_own`, `support_ticket.reply_own` | Own support tickets | Current MVP. Venue/order/booking context must be server-verified. |
| Guest | `booking.create_own`, `booking.view_own` | Own bookings | Current. Status/action availability depends on booking lifecycle. |
| Guest | `order_batch.create_own_in_session` | Current table session + own/joined tab | Current target/core. Requires active table context, selected tab and menu/stop-list validation. |
| Guest | `tab.view_own`, `tab.join_shared_by_invite` | Own personal tab or joined shared tab | Current target/core; two-guest privacy remains smoke-critical. |
| Guest | `staff_call.create_in_session` | Current table session | Current. Staff call is operational, not support. |
| Tab Host | `shared_tab.invite`, `shared_tab.revoke_invite`, `shared_tab.view`, `shared_tab.add_batch` | Hosted shared tab | Target/current where shared tab flow is implemented; member management beyond invite/revoke needs verification. |
| Tab Host | `shared_tab.manage_members` | Hosted shared tab | Target/future unless a concrete implementation task verifies it. |
| Tab Member | `shared_tab.view`, `shared_tab.add_batch`, `shared_tab.leave` | Joined shared tab | Target/current where shared tab flow is implemented. |
| Staff | `order_queue.view`, `order_batch.status_update_allowed` | Own venue operations | Current. Must preserve table-session/batch/tab boundaries. |
| Staff | `staff_call.view`, `staff_call.ack_complete` | Own venue calls | Current ACK/DONE smoke passed; CANCELLED UI/lifecycle and row-level actor columns remain future. |
| Staff | `booking.view`, arrival/no-show where allowed | Own venue bookings | Current STAFF booking split. Confirm/cancel/change/message/settings denied. |
| Staff | `menu.view`, `table.view`, `menu_availability.manage` | Own venue operational availability | Current docs say item/option stop-list parity is aligned. Target menu policy is `staff_stoplist_enabled` or equivalent before Staff can change availability; see `docs/MENU_OPTIONS_STOPLIST.md`. |
| Staff | `staff_profile.edit_own_draft` | Own linked profile only | Target Phase 1 where policy allows. Staff may edit own draft bio/photo fields only, cannot self-publish or enable guest visibility. |
| Staff | `support_ticket.none`, `venue_chat.none`, `billing.none`, `platform.none`, `settings.none` | All scopes | Current product rule. Direct API must return 403/denial even if UI hides nav. |
| Venue Manager | `order_queue.view`, `order_batch.status_update`, `order_batch.reject` | Own venue | Current where route permissions allow. |
| Venue Manager | `booking.manage`, `staff_call.manage` | Own venue | Current. |
| Venue Manager | `menu.view`, `menu.manage`, `stop_list.manage` | Own venue | Current with policy caveats by route. Conservative target keeps Manager to stop-list/shift check/basic availability unless broad `MENU_MANAGE` is explicitly retained; see `docs/MENU_OPTIONS_STOPLIST.md`. |
| Venue Manager | `table.view`, limited `table.manage` | Own venue | Current where backend permission allows; owner-only QR actions must stay denied if configured so. |
| Venue Manager | `support_ticket.manage_own_venue`, `venue_chat.manage_own_venue` | Own venue only | Current support/chat MVP. Venue cannot reply when support ticket is assigned to Platform unless product policy explicitly allows it. |
| Venue Manager | `staff_invite.create_staff_only` | Own venue | Current conservative policy where route allows; cannot create Owner/Platform access. |
| Venue Manager | `staff_shift.manage_today` | Own venue | Target Phase 1 only if policy allows. Manager marks today shift state, but does not approve public profiles or future tip methods by default. |
| Venue Manager | `billing.none`, dangerous lifecycle none | Billing/platform/lifecycle | Current product rule. |
| Venue Owner | All venue operations inside own venue | Own venue | Current via active `venue_members(role=OWNER)`. |
| Venue Owner | `staff.manage`, `staff_invite.create`, `menu.manage`, `stop_list.manage`, `table_qr.manage/rotate/export`, `settings.manage`, `staff_chat.link/unlink/test` | Own venue | Current where implemented; dangerous actions need confirmation/audit. |
| Venue Owner | `staff_profile.manage`, `staff_profile.publish`, `staff_shift.manage_today`, `staff_tip_method.approve` | Own venue | Target/future. Phase 1 covers profiles + today shift only; tip method approval is future. |
| Venue Owner | `billing.view/pay` | Own venue subscription/payment state | Current manual billing MVP for view/pay surfaces; Platform-only mark-paid/courtesy remain denied. |
| Venue Owner | `support_ticket.manage_own_venue`, `venue_chat.manage_own_venue` | Own venue only | Current. Can transfer support tickets to Platform. |
| Venue Owner | `venue.lifecycle.request_pause/archive/delete` | Own venue | Target only if product implements owner-requested lifecycle; Platform lifecycle remains Platform Owner. |
| Platform Owner | `platform.venues.manage`, `platform.lifecycle.manage`, `platform.owner_access.manage` | Platform | Current for implemented cockpit/lifecycle/owner access. |
| Platform Owner | `platform.billing.manage`, `platform.support.manage_all`, `platform.analytics.view`, `platform.audit.view`, `platform.settings.manage` | Platform | Billing/support MVP current; analytics/audit explorer partial/future. |
| Platform Owner | Ordinary `VENUE_CHAT` access | Venue chats | Denied by current target unless a future product policy explicitly changes it. |

## Surface Parity Matrix

| Feature | Telegram bot | Guest Mini App | Venue Mini App | Platform Mini App | Staff-chat | Rule |
| --- | --- | --- | --- | --- | --- | --- |
| Orders | Guest fallback/order status; venue operational shortcuts where implemented. | Primary guest QR/table order UX. | Primary venue queue/detail source of truth; see `docs/VENUE_OPERATIONS.md`. | No ordinary order workspace by default. | Order notifications/activity cards allowed. | Staff-chat is radar/shortcut, not source of truth. |
| Staff calls | Guest table fallback/actions and staff-chat callbacks where implemented. | Guest create/status. | Venue operations queue. | No. | Allowed for operational staff calls. | Separate from support tickets; Telegram/staff-chat rules in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. |
| Bookings | Guest `/my`, booking actions and venue/admin flows where implemented. | Guest booking/list. | Owner/Manager booking queue/actions; Staff view/arrival/no-show only. | Platform only if future analytics/audit requires. | Booking operational notifications allowed by existing policy. | Booking lifecycle follows `docs/BOOKING_LIFECYCLE.md`; booking chat stays `BOOKING_CHAT`, not support. |
| Support tickets | `/support` fallback where implemented. | Guest `Помощь`. | Owner/Manager `Обращения` for own venue. | Platform `Обращения` / Support Center. | Never for support tickets. | Staff denied. Platform sees support, not ordinary venue chats. |
| Venue chats | Guest bot/Mini App entry where implemented. | Guest `Чаты`. | Owner/Manager `Сообщения`. | No by default. | Never for ordinary venue chats. | Staff denied. |
| Booking chats | Booking action `Открыть переписку`. | Guest `Чаты`. | Owner/Manager `Сообщения`. | No by default. | Notification mirror only where existing policy allows. | Must not become support queue. |
| Menu/stop-list | Bot owner/manager/staff paths where implemented. | Guest read/order only after QR. | Owner/Manager manage; Staff availability only. | No ordinary menu management. | No source-of-truth edits. | Price/content edits are dangerous and audited where implemented. |
| Tables/QR | Bot management where implemented. | QR context only. | Owner/Manager table/QR where allowed; Staff read-only. | No ordinary venue table management unless platform support policy says so. | No. | QR token is context pointer. |
| Staff invites | Bot invite acceptance. | No. | Owner/Manager invite where allowed. | OWNER invite/revoke. | No. | Last-owner protection server-side. |
| Settings | Bot owner/manager setup where implemented. | No management. | Owner/Manager settings where allowed; Staff none. | Platform settings for platform scope. | No. | UI hiding is not enough. |
| Billing | Bot/platform/owner messaging where implemented. | No. | Venue Owner view/pay state; Manager/Staff none. | Platform billing cockpit. | No. | Money mutations need explicit POST and audit. |
| Analytics | Bot stats where implemented. | No dashboard; future profile summaries only. | Owner/Manager role dashboards where reliable. Staff operational counters only. | Platform analytics future/partial. | Delivery telemetry only. | Analytics events are not operational truth. |
| Platform lifecycle | Platform bot where implemented. | No. | No direct platform lifecycle. | Platform Owner cockpit. | No. | Requires confirmation/reason/audit where implemented. |

## Dangerous Actions

These actions require server-side authorization and should require confirmation, reason and/or audit according to risk and current implementation:

| Action | Required safety |
| --- | --- |
| Role granted/revoked; owner changed; last-owner removal attempted | Audit actor/target/old-new role; block last active Owner removal. |
| Venue published/hidden/paused/suspended/archived/deleted | Confirmation and audit with reason/status where implemented. |
| Table QR token rotated/exported | Confirmation and audit; old/revoked token must not resolve. |
| Staff chat linked/unlinked/tested | Confirmation for unlink; audit/link evidence without raw secrets. |
| Menu price changed; item archived; option schema changed; media removed; Staff stop-list toggled; stop-list mass update | Audit safe old/new fields; no raw media/provider payloads. |
| Order force closed; tab reopened | Reason and audit; preserve session/tab boundaries. |
| Invoice manually marked paid; subscription override changed; billing provider config changed | Platform Owner only, explicit action, reason where needed and safe audit. |
| Support ticket transferred/closed/assignee changed | Audit status/scope/actor/source; no message text/raw Telegram payloads. |
| Analytics export | If implemented, audit export actor/scope and exclude raw PII/message text/payment secrets. |
| Staff profile published/hidden, public photo changed, shift marked active/canceled, future tip method updated/approved/disabled | Audit actor/target/old-new safe fields; never expose private Telegram ids or raw external payment/provider data. |

## Current Implementation Vs Target

| Area | Current implementation from docs | Target product model | Gap / risk / future note |
| --- | --- | --- | --- |
| Core RBAC | Runtime uses venue memberships and platform owner resolver; many route tests and smokes exist. | Every endpoint verifies actor, scope and entity ownership. | Permission parity remains `PARTIAL` until every new route has direct denial tests. |
| `ADMIN` role | Legacy DB alias maps to `MANAGER`; Platform Mini App no longer exposes it. | Remove from product model; keep only compatibility alias. | Open migration/cleanup hygiene until no docs/copy/data imply separate Admin. |
| Guest order/tab privacy | Current docs say table-session/tab scoping is closed. | Guest reads/writes own personal tab or joined shared tab only. | Keep two-guest and shared-tab privacy smoke in regression. |
| Staff access | Staff support/venue-chat denial and operational scope are documented/smoked for current MVP. Current menu docs allow Staff item/option availability. | Staff sees operations only: orders, staff calls, allowed booking actions, menu/table read and stop-list only when enabled by venue policy. | Direct API denial tests remain critical for every new support/chat/billing/settings/menu route. |
| Manager/Owner venue isolation | Own-venue RBAC is the product rule. | No cross-venue detail/reply/manage access. | Keep cross-venue tests for support, chats, orders, bookings and settings. |
| Platform access | Platform Owner can manage platform scope and support tickets; ordinary venue chat hidden. | Platform does not bypass ordinary venue RBAC by default. | Event/audit explorer and analytics exports need additional privacy gates before broad release. |
| Dangerous action audit | Several audits exist: owner invite/revoke, billing mark-paid/courtesy, staff-call ACK/DONE, support status/scope, lifecycle/status where implemented. | All dangerous actions write safe actor/target/old-new/reason evidence. | Audit coverage remains `PARTIAL` until menu price, QR rotate, force close, tab reopen and analytics export are verified. |
| Staff profiles / today shift | Phase 1 backend + Mini App implementation exists; canonical model is `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. | Guest sees only public visible profile/shift data; Owner controls publish/hide; Staff may edit own linked draft only; Manager may mark active/completed/canceled today shifts. | Local route/privacy denial tests exist; staging smoke is still required before production readiness. |
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
16. Guest staff-profile APIs expose only public visible profiles/shifts and never `linked_user_id` or private Telegram/contact data.
17. Staff cannot publish their own profile or enable guest visibility without Owner approval.
18. Staff tip intent, when implemented, does not create provider payment, close bill or prove payment.

## Roadmap Status

- Security/RBAC matrix: `UPDATED`.
- Permission parity: `PARTIAL`; keep route-level denial tests and role smoke in regression.
- Staff profiles / today shift: Phase 1 route/privacy tests exist for backend + Mini App implementation; staging smoke is still required before production readiness.
- Staff tips: `SPEC DRAFT / FUTURE`; payment provider/direct payout requires legal/product decision, and external tip intent is not proof of payment.
- `ADMIN` decision: target is removal from product model / compatibility alias only; implementation cleanup remains a migration/copy hygiene follow-up.
- Staff stop-list parity: current docs say operational item/option availability is aligned; per-venue `staff_stoplist_enabled` is target/future in `docs/MENU_OPTIONS_STOPLIST.md`.
- Dangerous action audit: `PARTIAL` until all listed dangerous actions have verified audit evidence.
- Security smoke checklist: `UPDATED`.
