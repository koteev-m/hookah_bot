# Bot vs Mini App Parity Audit

Дата: 2026-05-25.

> Historical/partial snapshot. Not source of truth without checking `docs/UPDATED_PRODUCT_AI_ROADMAP.md`, `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` and current code.
>
> Current correction as of 2026-06-03: several P1 gaps described below were later fixed or changed by product decision. In particular, STAFF close bill/order is allowed in Mini App, manager/owner bill controls exist in Mini App, booking screens exist, guest history/favorites exist, pre-QR guest cards no longer expose structured order menu, and info/photo-menu media is served through backend proxy.
> Additional correction: STAFF booking management is split from order status updates. STAFF can view bookings and mark arrived/no-show; confirm/cancel/change/message/settings are MANAGER/OWNER-only.
> Additional correction as of 2026-06-16: Guest/Menu Options & Flavors parity is CLOSED after staging smoke. Guest Bot and Guest Mini App use structured selected options; Venue Mini App supports item-scoped flavor CRUD, item-level and flavor-level stop-list, and hookah-only shared `Добавить базовые вкусы`.
> Additional correction as of 2026-06-16: STAFF stop-list policy is aligned. STAFF may toggle menu item and item option/flavor availability during a shift, but cannot edit menu content, prices, categories, flavor CRUD or base flavor profiles.
> Additional correction as of 2026-06-18: M4B/M4C support inbox cards, unread/read receipts and resolve/reopen lifecycle are CLOSED after staging smoke. M5 staff-call lifecycle and compact Guest Mini App staff-call UX are CLOSED after staging smoke. M6 staff-chat diagnostics/unlink polish is CLOSED after staging smoke: Mini App now exposes status/link/test/unlink semantics with owner-only unlink, masked chat id, active-code copy-first UI and regenerate confirmation. Manual Telegram staff-chat notification/link/test/unlink checks remain per-venue regression, not an open implementation milestone. Venue Mini App order/bill controls are no longer bot-only for manager/owner. Treat older matrix rows below as historical where they conflict with the canonical roadmap and smoke checklist.
> Additional correction as of 2026-06-25: M7b Guest Mini App `Мои брони` is implemented and has staging visual parity for Bot `/my` public label, venue-local time and `Держим до`; real two-account Telegram runtime isolation remains unverified. M7c adaptive transactional reminders passed one controlled real Telegram staging smoke. Guest and Venue Mini App booking cards expose `lastGuestConfirmationAt`; Bot `/my` shows venue status separately from guest attendance response; Telegram reminder `Да, буду` edits the same reminder message and removes the attendance button. Attendance confirmation is atomic per booking schedule version, repeated actions are no-ops, reschedule clears the response, stale actions are rejected, and staff-chat attendance updates are deduplicated. Runtime remains disabled by default with staging returned to `BOOKING_REMINDER_WORKER_ENABLED=false`. The latest enriched staff-chat attendance copy is code/test-backed but not manually re-smoked with a new booking.
> Additional correction as of post-M9a checkpoint on 2026-06-25: M8a/M8b-Free public profile/card settings is CLOSED / staging smoke passed in provider-free mode, with Yandex adapters still optional disabled integrations. M9a Deployment SSH Reliability Hardening is CLOSED / staging smoke passed; the ControlMaster helper is committed and validated as an opt-in resilient deploy path, while the exact fresh SSH failure cause remains unconfirmed. M9b was selected next at that checkpoint and is now superseded by the post-M9b.3 closure note below.
> Additional correction as of M9b.2 local checkpoint: M9b Venue Working Hours and Date Exceptions Mini App Parity plus M9b.1 inclusive exception ranges/rejection copy was implemented locally, and M9b.2 addressed the staging-discovered save/list UX issue for changed-hours exceptions. This local-only status is now superseded by the post-M9b.3 closure note below.
> Additional correction as of post-M9b.3 checkpoint on 2026-06-26: M9b Venue Working Hours and Date Exceptions Mini App Parity, M9b.1 Schedule Exception Ranges and Guest Copy, M9b.2 Schedule Exception Save UX and M9b.3 Schedule Exception Range Editing are CLOSED / staging smoke passed. Venue Mini App manages weekly hours, inclusive closed/special-hours ranges and optional guest-facing reason/comment; changed-hours range dates are editable after creation; missing schedule shows `График не указан`; guest booking and Bot closed-date paths use human rejection copy.
> Additional correction as of 2026-06-30: Mini App mutation / operational verification closure pack is CLOSED / code-test verification passed. Old claims about missing CORS mutation methods, missing Mini App staff-call `tableSessionId`, missing Mini App staff-call staff-chat notification, and unhandled fallback quick-order payload are stale/superseded by focused backend and Mini App e2e tests. No staging smoke is claimed by this correction.
> Additional correction as of 2026-07-01: Guest Table Context UX Cleanup / Feature-gated Extension Module and Guest Table Session Exit / Expiry UX are CLOSED / staging smoke passed. Table context no longer prominently shows route/copy address/booking actions, pre-visit venue cards keep those public actions, `Продление работы заведения` is hidden unless active order/bill state makes it actionable, and `Завершить визит` is a user-scoped exit through `guest_table_session_exits`. The initial staging `415 Unsupported Media Type` on `POST /api/guest/table/session/end` is fixed by sending JSON with `Content-Type: application/json`; e2e asserts method, URL, content type and body. Shared `table_sessions` are physical-table/session scoped and are not closed for all guests by one guest exit.
> Additional correction as of 2026-07-03: Platform Billing Cockpit / Owner Payment UX, Platform Billing Renewal / Advance Invoice / Courtesy Days and Staff/Manager invite deep-link sharing polish are CLOSED / staging smoke passed. Platform Owner and Venue Owner billing surfaces now cover the manual/fake invoice MVP with read-only GET overviews, explicit invoice/checkout ensure POST, manual mark-paid audit, human paid-through/next-payment copy, next-period invoice creation from effective paid-through + 1 day, idempotent advance invoice ensure, `billing_adjustments` courtesy days with required reason and `BILLING_COURTESY_DAYS_ADDED` audit. Venue Owner sees adjusted state but cannot mark paid or add courtesy days; Manager/Staff payment controls stay hidden/forbidden. Invite messages and Venue Mini App invite result now expose valid staff invite deep links, copy/share actions and a secondary fallback command. Real acquiring, Telegram Stars, recurring card payments, invoice void/reissue and analytics remain future work.
> Additional correction as of 2026-07-06: Guest Communication UX / Support Tickets MVP is CLOSED / smoke passed. Current source of truth is `docs/COMMUNICATION_MODEL.md`: Guest nav is `Чаты` / `Помощь`; `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` are separate flows; catalog/venue detail `Задать вопрос` opens or reuses `VENUE_CHAT`; Platform Support Center sees support tickets but not ordinary venue chats; Staff sees neither support tickets nor ordinary venue chats; support and venue chat create/reply paths do not post to staff-chat. Older rows that say general tickets or Platform Support Center are future-only are superseded for the MVP; SLA automation, macros, attachments, CSAT, diagnostics and support analytics remain future work.
> Additional docs correction as of 2026-07-06: Platform cockpit source of truth is `docs/PLATFORM_COCKPIT.md`. Current Platform Mini App covers venue/subscription/billing baseline, owner invite/revoke, manual billing and support-ticket center; onboarding request cockpit, placements, analytics, real acquiring/Stars, recurring payments and lifecycle state normalization remain future/partial.

Режим: read-only аудит. Backend, frontend business logic, миграции, checkout, ledger, promotions, loyalty и AI flows не менялись.

## 1. Executive Summary

Текущая готовность: Telegram bot остаётся самой полной поверхностью продукта. Mini App уже покрывает launch-critical operational core: guest catalog/table/menu/cart/checkout/active order/staff call, venue order queue/detail/full bill/status lifecycle, menu/stop-list/tables/staff/support, platform venues/subscription baseline.

Главный вывод: подтверждённых P0 по money correctness, checkout, staff notification, initData/session scoping или role leak в текущем кодовом срезе не найдено. Pilot smoke можно продолжать по core сценарию, но market launch ещё требует закрыть P1 parity gaps, чтобы Mini App не отставал от bot-first логики и не создавал operator confusion.

Mini App отстаёт в основном не по расчётам, а по ширине продукта:

- Guest/Menu Options & Flavors parity is closed for structured `selectedOptionId`; the remaining small bot-side gap is optional per-line `preferenceNote` input, while Mini App already supports it;
- guest profile/promotions/loyalty progress screens не доведены до bot parity; history/favorites baseline уже есть;
- platform support tickets now have a smoke-passed Support Center; placements/analytics remain partial or explicitly delegated;
- venue hours/date exceptions are CLOSED / staging smoke passed through M9b/M9b.1/M9b.2/M9b.3;
- final staging smoke remains required for initData, money, staff notifications and role denied paths.

Known fixed/outdated rows in this document:

- `Venue advanced bill management is bot-only` is outdated: Mini App has manager/owner controls for manual discounts and item exclude/restore.
- `STAFF close mismatch` is outdated: STAFF can close bill/order, while discount/exclude/restore remain manager/owner-only.
- `Bookings remain safely hidden` is outdated: Guest and Venue booking screens/actions exist.
- `Guest history/favorites missing` is outdated: Guest account baseline includes visit history and favorites.
- `M4B/M4C support inbox not implemented` is outdated: code/tests and staging smoke now cover inbox cards, filters, unread and resolve/reopen lifecycle.
- `M5 staff calls lifecycle missing` is outdated: code/e2e and staging smoke now cover compact guest UX and Venue `Вызовы`; linked Telegram group notifications remain per-venue regression.
- `Missing Mini App staff-call tableSessionId` is outdated: code-test verification covers frontend payload, backend persistence and staff-chat event payload.
- `Unhandled fallback quick-order payload` is outdated: code-test verification covers `Telegram.WebApp.sendData` payload with `cmd=start_quick_order`.
- `Missing CORS mutation methods` is outdated: code-test verification covers actual Mini App PUT/PATCH/DELETE preflight paths with `Content-Type` and `Authorization`.
- Old stuck-table-context claims are outdated: `Завершить визит` passed staging smoke and prevents stale restore for the current user until explicit QR re-entry.
- `Table-session exit closes the shared physical session` is wrong for current product behavior: guest exit is user-scoped through `guest_table_session_exits`; TTL cleanup still handles shared `table_sessions` expiry.
- The `POST /api/guest/table/session/end` 415 incident is resolved: Mini App now sends JSON `Content-Type` and the e2e request contract covers it.
- `No full billing/invoice cockpit in Mini App` is outdated for the manual MVP: Platform billing cockpit, Venue Owner subscription state, advance next invoice and courtesy-days adjustment are staging-smoked. Real acquiring, Telegram Stars and recurring automatic payments are still not implemented.
- Old invite-copy friction is outdated: staff/manager invite sharing now uses valid Telegram deep links, copy/share actions and a secondary fallback command in the Venue Mini App result.
- Old `general tickets/platform support later` claims are outdated for the MVP: Support Tickets / Platform Support Center is smoke-passed for `SUPPORT_TICKET`, while ordinary `VENUE_CHAT` remains intentionally hidden from Platform.
- Pre-QR guest `🍽 Menu` behavior changed: order menu is available only after QR/table context; `📖 Фото-меню` is an info section.
- Info-section media parity changed: Mini App uses a backend media proxy instead of Telegram file IDs/raw file URLs.

## 2. Sources Used

Документы:

- `docs/UPDATED_PRODUCT_AI_ROADMAP.md`
- `docs/PLATFORM_COCKPIT.md`
- `docs/COMMUNICATION_MODEL.md`
- `docs/audit/MINI_APP_PRODUCTION_READINESS_AUDIT.md`
- `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`
- `docs/audit/ROLE_GUEST.md`
- `docs/audit/ROLE_MANAGER.md`
- `docs/audit/ROLE_STAFF.md`
- `docs/audit/ROLE_PLATFORM_OWNER.md`

Telegram bot:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramKeyboards.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/StaffChatNotifier.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramTypes.kt`

Mini App backend:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/*`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/*`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/*`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/platform/*`

Mini App frontend:

- `miniapp/src/main.ts`
- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/screens/catalog.ts`
- `miniapp/src/screens/guestVenue.ts`
- `miniapp/src/screens/cart.ts`
- `miniapp/src/screens/order.ts`
- `miniapp/src/screens/venueApp.ts`
- `miniapp/src/screens/venueDashboard.ts`
- `miniapp/src/screens/venueOrders.ts`
- `miniapp/src/screens/venueOrderDetail.ts`
- `miniapp/src/screens/venueMenu.ts`
- `miniapp/src/screens/venueStaff.ts`
- `miniapp/src/screens/venueTables.ts`
- `miniapp/src/screens/venueSettings.ts`
- `miniapp/src/screens/supportScreens.ts`
- `miniapp/src/screens/platformApp.ts`
- `miniapp/src/screens/platformVenueDetail.ts`
- `miniapp/src/screens/platformCockpitSections.ts`
- `miniapp/src/shared/api/guestApi.ts`
- `miniapp/src/shared/api/guestDtos.ts`
- `miniapp/src/shared/api/venueApi.ts`
- `miniapp/src/shared/api/venueDtos.ts`
- `miniapp/src/shared/api/platformApi.ts`
- `miniapp/src/shared/api/platformDtos.ts`

## 3. Guest Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| `/start` без QR | Role-aware guest menu, catalog entry, Mini App entry, venue onboarding lead | `mode=guest` opens catalog through WebApp auth | Partial parity | Bot has richer entry menu and onboarding actions | P2 | Keep bot as entrypoint; enrich Mini App catalog after pilot smoke. |
| `/start <tableToken>` / QR | Resolves table, shows table context menu and Mini App choice | Resolves table by token, hides QR CTA after context, routes to venue/menu/cart/order; explicit QR re-enters after user exit marker | Done / staging smoke passed | Keep runtime smoke in Telegram regression | P2 | Continue manual smoke with real WebApp `initData`, QR re-entry and account-switch isolation. |
| Table context actions | Menu, cart, my order, quick order, shared bill, staff call, venue promos, favorites, history, profile, change table, `Завершить визит` | Menu, cart, my order, account, support/messages, staff call, user-scoped `Завершить визит`; route/copy address/booking are hidden in table context and remain on pre-visit venue card | Partial / table cleanup CLOSED | Account hub exists, but promotions/loyalty/profile edit/change-table are not full Mini App parity | P1/P2 | Keep table cleanup/exit, staff-call NEW/ACK/DONE and no-stale-active-call behavior in regression; polish guest account hub later. |
| Menu | Bot menu supports order flow and hookah flavor/option selection with structured selected options | Guest Mini App exposes item option/flavor picker for configured items; `selectedOptionId` and line-level `preferenceNote` persist through cart/order flow; hookah nested flavor notes use hookah-specific placeholder copy | Done / smoke | No current options/flavors launch gap; photos/descriptions remain separate menu polish | P2 | Keep option/flavor picker, hookah placeholder copy, stale unavailable rejection and line identity in regression smoke. |
| Cart / checkout | Bot cart includes promo/loyalty preview and fallback quick order | Cart submits scoped `tableSessionId/tabId`, supports personal/shared tabs and fallback chat command | Done / CLOSED / code-test verification passed | Cart UI shows basic item totals, not full preview breakdown before checkout | P2 | Add cart preview breakdown later if product needs pre-checkout transparency. |
| Active order / My order | Bot active order shows status and bill lines | Mini App active order uses scoped `tableSessionId/tabId`, shows items, prices, promo/loyalty/manual totals, final total and human order/account labels | Done / staging smoke passed | Keep money/status regression; no current display-number/full-bill launch gap | P2 | Keep cross-channel bill snapshot and manual release smoke in regression. |
| Shared bill / tabs | Bot has shared bill entry and invite flow | Cart screen supports personal/shared tab selection, create/join invite | Partial | Table home has no explicit `Общий счёт` action; behavior is buried inside cart | P1 | Add a clear table-home entry or surface tab mode more explicitly in cart header. |
| Quick order / fallback order | Bot has `✍️ Быстрый заказ` | Mini App fallback emits supported `cmd=start_quick_order` WebApp payload | CLOSED / code-test verification passed | Mini App intentionally does not replicate chat quick order | P2 | Keep as fallback; do not rebuild quick order in Mini App before core launch. |
| Staff call | Bot table context can create staff call and staff chat callbacks can ACK/DONE | Mini App staff call sends `tableSessionId`, backend notifies staff chat, Guest Mini App uses compact NEW/ACK/DONE status, Venue Mini App `Вызовы` can accept/close, and safely order-linked calls update the live order activity card | Done / staging smoke passed / CLOSED / code-test verification passed | Unsafe/no-order/ambiguous calls intentionally remain standalone; linked staff chat runtime remains per-venue regression | P2 | Keep safe-link, standalone-call, ACK/DONE audit and live-card refresh behavior in pilot smoke. |
| Venue promotions | Bot exposes `🎁 Акции заведения` and global promotions | Mini App has no dedicated venue promotions screen | Missing in Mini App | Guest cannot inspect active venue promotions outside order/bill context | P1 | Add read-only venue promotions screen before market launch, or remove/avoid Mini App promise. |
| Loyalty progress | Bot profile shows progress and reward targets; cart/order shows redemption lines | Mini App active order/full bill shows loyalty discounts; no profile loyalty screen found | Partial | Progress and available rewards are not visible as standalone Mini App account UX | P1 | Add loyalty progress to guest account hub; keep checkout logic unchanged. |
| Profile | Bot supports guest profile and loyalty entry | Mini App account has profile section with explicit bot-only edit fallback | Partial / safe fallback | Profile edit remains bot-only | P2 | Keep safe copy; add Mini App edit only when backend/user-profile model is scoped. |
| History / repeat order | Bot has visit history/detail/repeat flow | Mini App account has history list/detail backed by `GuestVisitRoutes`; repeat remains later | Partial | Repeat order is not Mini App parity | P2 | Keep history in regression; add repeat only after cart/order availability rules are stable. |
| Favorites | Bot favorite venues/items flows exist | Mini App account has favorite venues/items using backend favorites routes | Partial | Favorite mutations/discovery polish may still be incomplete | P2 | Keep favorites read flow; add mutation polish only after pilot smoke. |
| Bookings | Bot booking flow exists, `/my` shows active bookings with public `Бронь №...`, `Держим до`, venue status and secondary guest attendance response; Bot sends M7c reminders only when the opt-in worker is explicitly enabled | Guest and Venue Mini App booking baselines exist; Venue M3 lifecycle and M7a hold-minutes settings are closed; Guest Mini App M7b `Мои брони` is implemented with staging visual parity for Bot `/my` label/time/deadline; attendance intent is shown when `lastGuestConfirmationAt` is present | Done / regression | M7c core real Telegram smoke passed but rollout remains opt-in disabled; preorder remains later; M7b real two-account runtime isolation remains unverified | P2 | Keep booking lifecycle, hold settings, M7c disabled-by-default behavior, Guest/Bot public booking label parity, attendance idempotency and stale-action rejection in regression. |
| Communication / support | Bot fallback and Guest Bot booking replies persist to shared communication threads where applicable | Guest Mini App uses `Чаты` for `BOOKING_CHAT` / `VENUE_CHAT` and `Помощь` for `SUPPORT_TICKET`; booking inbox lifecycle and general support tickets are smoke-passed | MVP / smoke passed | Advanced SLA automation, macros, attachments, CSAT, diagnostics and support analytics remain later | Regression / P2 | Preserve the split from `docs/COMMUNICATION_MODEL.md`: booking chat, venue chat, support ticket and staff call must not collapse into one workspace. |

## 4. Venue / Staff / Manager Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Venue panel entry | Bot shows role-aware `📱 Панель заведения` WebApp entry | Venue Mini App opens in `mode=venue` and checks access | Done | Needs ongoing initData smoke | P2 | Keep WebApp button smoke in release checklist. |
| Overview | Bot role menus are broad | Mini App overview shows venue name, Russian role label, new/in-work counts, staff chat status | Done | Counts are operational, not analytics | P2 | Add richer dashboard later. |
| Orders queue | Bot has staff/manager queue and staff chat notifications | Mini App queue shows display number, table, status, item count, comment | Done | No advanced filtering by source/guest yet | P2 | Keep for launch; add filters after pilot. |
| Order lifecycle | Bot staff chat supports accept/deliver/close bill path; venue callbacks update statuses | Mini App shows status actions by current status and uses backend transition route | Done / smoke | STAFF close policy is aligned; remaining gap is queue pagination/filter polish, not core lifecycle | P2 | Keep status/close in regression; consider order queue `nextCursor` consumption later. |
| Guest notifications | Bot status changes notify guest | Mini App status route enqueues guest notifications for accepted/delivered/closed | Done | Needs smoke with real outbox worker | P2 | Keep in pilot checklist. |
| Full bill display | Bot full bill shows gross/manual/promo/loyalty/excluded/final | Mini App order detail shows management bill from backend DTO, human order/account labels and closed-state copy | Done / staging smoke passed | Keep snapshot/manual regression; no current full-bill display launch blocker | P2 | Preserve cross-channel bill snapshot, role denials and staff-chat card totals in regression. |
| Manual discount / exclusion | Bot has item exclusion and discount dialog states | Mini App has manager/owner bill edit controls for item discount and exclude/restore; STAFF is denied | Done / smoke | No current launch blocker; keep money snapshots in regression | P2 | Preserve backend-owned bill totals and STAFF denial tests. |
| Cancel/reject order/batch/item | Bot has richer cancel/reject flows | Mini App has order reject for owner/manager; no item-level cancel/exclude controls | Partial | Item/batch granular management is bot-only | P1 | Add only backend-supported actions; otherwise keep explicit bot-only text. |
| Staff calls lifecycle | Bot has active calls and callbacks for ack/done in staff chat router paths | Mini App has backend-backed `Вызовы` queue with accept/close and compact guest status labels; live order cards hide DONE/CANCELLED generic calls from active `Оперативно` | Done / staging smoke passed | No cancel/SLA/escalation yet; staff chat group binding remains per-venue runtime dependency | P1 | Keep linked Telegram group notification, inline callbacks, active-call cleanup on order close and audit evidence in regression. |
| Menu CRUD | Bot owner/manager menu management exists | Mini App menu supports category/item CRUD, reorder, item availability | Done / partial | Uses prompt-style inputs; not polished | P2 | Accept for pilot; later replace prompts with forms. |
| Option stop-list | Bot stop-list supports item/options, base flavor profile apply and normalize helpers | Mini App displays item-scoped options/flavors, structured guest selected options, explicit item/option stop-list toggles and shared hookah-only `Добавить базовые вкусы` | Done / smoke | STAFF can toggle availability only; normalize/reset helper remains optional; broad menu IA parity remains separate | P2 | Keep shared base profile apply and item/flavor stop-list in regression smoke; add normalize/reset only if venues need it after pilots. |
| Option line preference note | Bot renders structured order/read-model lines but does not yet collect optional preparation notes | Guest Mini App collects optional `Пожелание к вкусу` on selected option lines | Partial | Bot input parity for line-level preference note is missing; note must remain separate from structured flavor and have no price semantics | P1 follow-up | Add a small Guest Bot conversational step for optional line note after flavor selection, or explicitly skip with “без пожеланий”. |
| Tables / QR | Bot tables/QR flows exist | Mini App tables screen supports create, rotate, rotate all by permission, QR export | Partial | Staff sees tables if backend permission allows read; product policy should confirm | P2 | Align staff table visibility with role matrix before launch. |
| Staff management | Bot owner staff management exists | Mini App staff screen supports list, invite, role update/remove by permissions | Partial | Member labels are `User <id>`, not names; manager invite capability is conservative | P2 | Polish identity display; keep permissions conservative. |
| Staff chat link | Bot supports staff chat bind/status/test/unlink | Mini App chat link/status screen shows linked/unlinked state, masked chat id, backend-built link command, copy-first active-code card, regenerate confirmation, outbox-backed test result and OWNER-only unlink confirmation | Done / staging smoke passed | Real Telegram group link/test/unlink and operational notification delivery stay in per-venue regression | P1 | Keep M6 linked group behavior in pilot smoke. |
| Settings | Bot remains canonical for broad venue settings | Mini App settings route manages booking hold settings, paid shift extension settings, M8a/M8b-Free structured public card basics (`countryCode`, `city`, `address`, `formattedAddress`, optional coordinates, `guestContact`, `cardDescription`) and M9b weekly hours/date exceptions for OWNER/MANAGER; STAFF is hidden/forbidden | Partial / M8a-M8b-Free and M9b-M9b.3 staging-smoke closed | Media/info sections, promotions and guest preview remain bot/platform-canonical; local city seed is intentionally incomplete and manual input remains fallback; no bulk settings endpoint | P1/P2 by slice | Continue small backend-backed settings slices and keep provider-free public card/location plus schedule validation in regression. |
| Bookings | Bot has booking list/actions/settings and M7c transactional reminder callbacks (`Да, буду`, `Перенести`, `Отменить`) behind the disabled-by-default worker; `/my` can expose `Я приду` for eligible bookings | Venue Mini App has booking queue/lifecycle actions, M7a hold-minutes settings and guest-confirmed visit indicator; Guest Mini App has account-level `Мои брони` with change/cancel actions, `Я приду` for eligible bookings and compact confirmed-response state | Done / smoke for lifecycle, M7a, M7b visual parity and core M7c Telegram flow | Preorder remains later; M7b real two-account runtime isolation is still not recorded; M7c broader rollout remains opt-in | P2 | Keep booking lifecycle, hold settings, RBAC split, public booking label parity, reminder feature flag, attendance idempotency, staff-chat dedupe and outbox `QUEUED` semantics in regression. |
| Stats | Bot owner/manager stats exist | Venue Mini App has read-only stats screen for OWNER/MANAGER with today/7d/30d | Done / staging smoke passed | Custom ranges/AI summaries remain later | P2 | Keep stats screen and STAFF hidden state in regression. |
| Marketing/promotions/loyalty management | Bot marketing hub is rich | Venue Mini App has no marketing hub | Bot-only | Not exposed as Mini App dead-end | P2 | Keep bot canonical; Mini App marketing builder belongs later phase. |

## 5. Owner Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Venue profile/settings | Bot owner flows edit profile, description, hours, contacts and settings | Mini App has booking hold, paid shift extension, M8a/M8b-Free structured public card basics with local country/city suggestions/manual address, and M9b weekly hours/date exceptions; venue name is read-only, and broader media/promotions/preview settings remain bot-canonical | Partial / M8a-M8b-Free and M9b-M9b.3 staging-smoke closed | Owner must still use bot/platform flows for broad setup beyond public location/contact/card description and schedule; optional Yandex adapters are disabled/commercial-only and not required for production-default flow | P1/P2 by slice | Implement further real settings only as small backend-supported slices; keep M9b schedule behavior in regression. |
| Menu management | Bot owner menu management exists | Mini App supports core menu CRUD and availability | Partial parity | Mini App does not cover all rich content/media/flavor profile flows | P2 | Keep current operational menu; defer rich content. |
| Tables/QR | Bot owner tables/QR flow exists | Mini App tables/QR screen exists | Partial parity | QR export/runtime download needs smoke | P2 | Add to pilot smoke. |
| Personnel | Bot owner staff management exists | Mini App staff management exists | Partial parity | Invite deep-link sharing is staging-smoked; identity labels may still be basic | P2 | Keep invite copy/share and role-denial smoke in regression; polish identity labels later. |
| Promotions / marketing hub | Bot supports promotions, banners, placements, top, AI helper | Mini App does not expose marketing hub | Bot-only | Expected by updated roadmap as later Mini App/AI-assisted layer | P2 | Do not add before Mini App operational core is stable. |
| Loyalty setup | Bot owner/manager controls loyalty program, targets, status | Mini App does not expose loyalty setup | Bot-only | No Mini App parity | P2 | Keep bot canonical for launch. |
| Reviews / review link | Bot supports reviews and public review link flows | No Mini App review cockpit found | Bot-only | Not launch-critical for order pilot | P2 | Add when venue cockpit expands. |
| Statistics | Bot stats exist | Venue Mini App read-only stats screen exists for OWNER/MANAGER | Done / staging smoke passed | Custom range/AI summaries remain later | P2 | Keep stats in regression. |
| Communication / support | Bot remains operational fallback for supported flows; staff chat is not support storage | Venue Mini App `Сообщения` covers `BOOKING_CHAT` / `VENUE_CHAT`; `Помощь` / `Обращения` covers own-venue `SUPPORT_TICKET` with reply/close/transfer behavior | MVP / smoke passed | Advanced support automation and venue/admin bot full inbox remain later | Regression / P2 | Keep current-venue scoping, STAFF denial, no booking lifecycle side effects and no support/venue-chat staff-chat spam in regression. |

## 6. Platform Owner Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Platform panel entry | Bot has `📱 Панель платформы` WebApp entry | `mode=platform` checks platform access | Done | Bot/API Platform Owner config parity is implemented; legacy aliases remain compatibility | P2 | Keep config smoke in deployment runbook. |
| Venue list/detail | Bot has platform menus; some old sections were placeholder | Mini App has venue list/detail/status/owner/subscription basics | Mini App stronger | Bot/Mini App responsibilities differ | P2 | Treat Mini App as platform cockpit baseline. |
| Venue lifecycle | Bot supports platform status flows in promotion/platform areas | Mini App supports status actions for current enum `DRAFT/PUBLISHED/HIDDEN/PAUSED/SUSPENDED/ARCHIVED/DELETED` | Partial parity | Target product states `onboarding`, `paused_by_owner`, `suspended_by_platform` and `deletion_requested` need explicit normalization if product wants them distinct | P2 | Keep current lifecycle smoke; add lifecycle/readiness labels and normalization later. |
| Owner assignment/invite | Bot platform onboarding exists | Mini App supports OWNER assignment/invite generation without `ADMIN` as a selectable runtime role | Done / staging smoke passed | Owner invite create returns usable Telegram deep link/copy text; Telegram accept grants OWNER for the intended venue; create/accept audit exists | Regression | Keep owner invite create/accept and non-platform denial in release smoke. |
| Owner membership revoke | Bot venue access resolves through membership role resolution | Mini App lists active OWNER memberships and exposes Platform Owner-only revoke controls | Done / staging smoke passed | Revoke deletes active OWNER membership only when another OWNER remains; `owner_account_id` and primary-owner linkage are not relinked | Regression / future legal transfer | Keep last-owner block, access loss, non-platform denial and `VENUE_OWNER_REVOKE` audit in smoke; primary/legal/billing transfer helper remains later. |
| Subscriptions/pricing | Bot subscription menus may be limited | Mini App supports subscription basics, Platform Owner billing cockpit, Venue Owner subscription screen, explicit invoice/checkout ensure, advance next invoice and courtesy-days read model | Done / partial | Manual/fake billing MVP is staging-smoked; real acquiring, Telegram Stars, recurring payments, invoice void/reissue and suspended-status distinction remain future | Regression / P1/P2 future providers | Keep read-only GET, explicit POST ensure, manual mark-paid audit, courtesy audit and Manager/Staff payment-control denials in regression. |
| Onboarding requests | Bot handles connection requests | Mini App safe section says requests remain in bot | Safe fallback | Platform operator must switch surfaces | P1 | Add onboarding request list if platform pilot depends on Mini App. |
| Placements | Bot supports banner/top placements management | Mini App safe section says placements remain in bot | Safe fallback | Not parity | P1 | Add read-only pending/active placements summary before market launch. |
| Support | Platform bot support remains limited | Mini App Platform Support Center / `Обращения` is backend-backed for `SUPPORT_TICKET`, including platform-only and transferred tickets; ordinary `VENUE_CHAT` is hidden | MVP / smoke passed | SLA automation, diagnostics, macros, attachments, CSAT and support analytics are not implemented | Regression / P2 | Keep platform support-ticket RBAC, filters and ordinary venue-chat exclusion in regression. |
| Analytics | Bot/platform reports partial | Mini App safe analytics section, no fake numbers | Launch-safe | No platform analytics dashboard or support/billing metrics cockpit | P2 | Add real read models after event semantics and provider/support metrics are stable. |

## 7. Promotions / Loyalty Parity

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Promotion setup | Bot supports TEXT/BANNER/HH/GIFT, rules, schedule, stackability, archive | Mini App has no promotion management | Bot-only | Not promised in Mini App UI | P2 | Keep bot canonical until AI-assisted owner onboarding phase. |
| Promotion feed | Bot has global and venue-specific promotions | Mini App catalog/venue screens do not show dedicated promotions feed | Missing in Mini App | Guests cannot browse promotions in Mini App except via order/bill impact | P1 | Add read-only venue promotions and catalog promotion teaser. |
| Cart/checkout promo calculation | Bot and Mini App use backend checkout/read models | Mini App displays active order and venue bill promo lines from backend | Done | Cart pre-submit preview is lighter | P2 | Optional cart preview polish later. |
| Loyalty setup | Bot owner/manager setup and targets | Mini App no setup | Bot-only | Not launch blocker | P2 | Keep in bot. |
| Loyalty redemption display | Bot shows loyalty in cart/order/full bill | Mini App order/full bill display loyalty line | Done | Guest standalone progress missing | P1 | Add guest loyalty progress screen in account hub. |
| Promo/loyalty breakdown | Bot staff/full bill/history show breakdown | Mini App active order and venue bill show discount breakdowns | Done / partial | History Mini App not present | P1 | Add history screens using existing backend visit DTOs. |
| Placements/top | Bot platform/marketing flows exist | Platform Mini App safe fallback only | Safe fallback | Not parity | P1 | Add platform placements cockpit summary. |

## 8. Permissions / Role Parity

| Role | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| GUEST | Full guest menu and table context in bot | Guest Mini App has catalog/table/menu/cart/order/support; account features missing | Partial | Profile/history/favorites/promos/loyalty progress absent | P1 | Build guest account hub and read-only venue promotions. |
| STAFF | Bot staff can operate orders/calls and operational booking arrival/no-show actions | Mini App permits queue/status/menu/tables read-only and operational booking arrival/no-show actions; dangerous actions are denied backend-side | Mostly aligned | Remaining gaps should be verified by smoke, especially old callbacks and direct API denied paths | P1 smoke | Keep STAFF matrix in role docs and release checklist. |
| MANAGER | Bot broad operational/marketing menu | Mini App operational panel plus stats and shift-extension settings; no marketing builder/broad venue settings | Partial | Marketing and broad setup remain bot-canonical | P2 | Accept for pilot; add small settings/promotions slices later. |
| OWNER | Bot full venue setup/marketing/loyalty/reviews | Mini App operational panel plus staff/menu/tables/stats/shift-extension settings; broad setup remains bot-canonical | Partial | Owner product setup mostly bot-only | P1/P2 | Add real settings screen next if launch requires Mini App owner setup. |
| PLATFORM_OWNER | Bot has platform flows; old docs mention some placeholders | Mini App has venue/subscription/billing baseline, owner invite/revoke, manual billing cockpit and Support Center for support tickets | Partial | Platform onboarding/placements/analytics not full parity; real acquiring/Stars, recurring payments and lifecycle normalization are not implemented | P1 | Use `docs/PLATFORM_COCKPIT.md`; keep manual billing/support tickets in regression, and treat provider billing, placements, analytics and lifecycle normalization as separate milestones. |

Security notes:

- Guest active order path now sends `tableSessionId` and `tabId`; backend enforces tab membership.
- Mini App route access is backend-enforced even where frontend nav is broader.
- No Mini App AI entry promising unavailable AI flows was found; internal AI assistant remains Telegram bot only.

## 9. Remaining P0

No confirmed remaining P0 was found in this read-only pass.

P0-sensitive areas that must stay in pilot smoke:

- Telegram WebApp opens with non-empty `initData`.
- Mini App mutation preflights still allow actual PUT/PATCH/DELETE routes with `Content-Type` and `Authorization`.
- Guest order and active order remain scoped to current `tableSessionId/tabId`.
- Staff chat receives Mini App order and staff call notifications.
- Venue Mini App final payable total matches Telegram full bill for the same order.
- Role boundaries are checked for STAFF/MANAGER/OWNER/PLATFORM_OWNER.

## 10. Remaining P1

Current correction note:

- P1.2, P1.3, P1.4 and P1.6 are historical as written. Keep them only as evidence of earlier gaps; use `docs/UPDATED_PRODUCT_AI_ROADMAP.md` and the launch smoke checklist for current backlog.
- Still active from this section: Guest profile/promotions/loyalty parity, Platform cockpit placements/analytics partial sections, final staging smoke, frontend/browser e2e gap and broad test heap/runtime risk.

### P1.1 — Guest account hub parity is missing

Why it matters: Bot has profile, loyalty, history, favorites and venue promotions; Mini App guest shell only exposes catalog/table/menu/cart/order/support. This blocks a complete launch-safe guest Mini App experience.

Affected modules:

- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/shared/api/guestApi.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVisitRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestFavoritesRoutes.kt`
- loyalty/progress repository and existing bot profile flow.

Acceptance:

- Mini App has `👤 Профиль`, `🕘 История`, `⭐ Любимое`, `🎁 Акции` or clearly does not show/claim them.
- Guest loyalty progress is readable without entering Telegram profile.
- History uses backend visit DTO and does not recalculate money on frontend.

### P1.2 — Venue advanced bill management is bot-only

Why it matters: Mini App displays full bill correctly, but manual discounts, item exclusion/cancel and granular batch/item management are available in Telegram bot only. Operators may expect the same controls in the venue cockpit.

Affected modules:

- `miniapp/src/screens/venueOrderDetail.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueOrdersRepository.kt`
- venue order routes if controls are added.

Acceptance:

- Either supported controls are implemented using existing backend routes, or Mini App exposes a direct, clear bot fallback for this exact order.
- No half-working buttons.
- Full bill calculations remain backend-owned.

### P1.3 — STAFF order/stop-list permissions differ by surface

Current correction: STAFF order/close policy and operational stop-list policy are aligned in current code. STAFF may toggle item/option availability in Bot and Venue Mini App, but menu content editing remains MANAGER/OWNER-only.

Why it mattered historically: Staff-facing capabilities must not depend on whether staff uses Telegram bot or Mini App. Older code suggested Mini App disallowed STAFF stop-list while Telegram staff stop-list callbacks existed.

Affected modules:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueRbac.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/VenueOrderRoutes.kt`
- `miniapp/src/screens/venueOrderDetail.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramKeyboards.kt`

Acceptance:

- One documented STAFF permission matrix.
- Bot and Mini App show the same allowed operational actions, or intentionally explain why one surface is restricted.
- Tests cover STAFF accept/deliver/close/reject/stop-list allowed/denied behavior.

### P1.4 — Staff call lifecycle parity needs final verification

Why it matters: Guest calls are created and staff chat notification is covered, but operator ack/done lifecycle should be equally usable in bot and Mini App for pilots.

Affected modules:

- `miniapp/src/screens/venueStaff.ts`
- venue staff call routes/repositories, if present.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`

Acceptance:

- Venue Mini App can show active calls and perform supported lifecycle actions, or states clearly that lifecycle is handled in Telegram bot.
- STAFF/MANAGER/OWNER permissions are tested.

### P1.5 — Platform cockpit sections are safe fallbacks, not full parity

Why it matters: Platform Mini App is launch-safe and support tickets have a backend-backed Support Center, but onboarding requests, placements and analytics are still informational/safe fallback sections. Platform operator still needs Telegram bot or manual operations for those flows.

Affected modules:

- `miniapp/src/screens/platformCockpitSections.ts`
- `miniapp/src/screens/platformApp.ts`
- `docs/PLATFORM_COCKPIT.md`
- platform promotion/placement repositories and bot flows.

Acceptance:

- At minimum, add read-only pending/active placements and onboarding request summaries, or keep these explicitly bot-only in pilot runbook.
- No fake analytics or fake ticket controls.

### P1.6 — Bookings remain safely hidden

Why it matters: This is acceptable if bookings are out of pilot scope, but not market-ready if venues expect Mini App booking operations.

Affected modules:

- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/screens/venueApp.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/bookings/VenueBookingRoutes.kt`

Acceptance:

- Product decision: bookings excluded from pilot, or minimal working guest/venue booking screens are implemented.

## 11. P2 Backlog

- Rich guest catalog cards: media, hours, contacts, public promotion teasers.
- Guest menu media/descriptions/top-list.
- Cart pre-submit promo/loyalty preview polish.
- Cross-channel bill snapshot tests.
- Venue dashboard stats.
- Venue menu forms instead of `window.prompt`.
- Staff/member display names instead of raw `User <id>`.
- Platform analytics dashboard backed by real read models.
- Real acquiring provider / Telegram Stars rollout if commercial launch requires online payment.
- Invoice void/reissue for courtesy conflicts with already-open future invoices.
- Billing-created versus manual `SUSPENDED_BY_PLATFORM` distinction before broader auto-reactivation.
- Mini App AI-assisted flows after operational Mini App hardening.
- Telegram Guest Mode / Business Bots / Managed Bots remain future layers per canonical roadmap.

## 12. Recommended Fix Order

1. Guest account hub parity:
   - profile shell;
   - loyalty progress;
   - visit history;
   - favorites;
   - venue promotions.
2. STAFF permission alignment:
   - close order policy;
   - stop-list policy;
   - tests for bot/Mini App allowed actions.
3. Venue order management fallback:
   - direct bot guidance for current order;
   - then minimal Mini App controls for supported manual discount/exclusion if still needed.
4. Staff call lifecycle parity:
   - active calls list;
   - ack/done or safe bot-only message.
5. Platform cockpit read-only summaries:
   - onboarding requests;
   - pending/active placements.
6. Bookings decision:
   - keep safely hidden for pilot or implement minimal screens.
7. P2 polish and frontend e2e harness.

## 13. First Implementation Prompt

```text
Ты senior Kotlin/Ktor + Vite Mini App + Telegram Bot product engineer.

Контекст:
Главный roadmap-source:
- docs/UPDATED_PRODUCT_AI_ROADMAP.md

Новый supporting audit:
- docs/audit/BOT_MINIAPP_PARITY_AUDIT.md

Goal:
Закрыть первый P1 Bot vs Mini App parity fix-pack: Guest account hub parity.

Scope:
1. Добавить в Guest Mini App launch-safe account hub:
   - 👤 Профиль
   - 🎁 Лояльность
   - 🕘 История
   - ⭐ Любимое
   - 🎁 Акции заведения / текущего venue, если есть table context.
2. Использовать существующие backend read models/routes там, где они уже есть:
   - GuestVisitRoutes;
   - GuestFavoritesRoutes;
   - loyalty progress repository/routes if already exposed, otherwise add minimal read-only route;
   - venue promotions read model if already available.
3. Не пересчитывать деньги на frontend.
4. Не добавлять checkout/ledger/promotions/loyalty business logic.
5. Если какой-то read-only backend route отсутствует и scope растёт, показать safe bot-only message instead of half-working UI.

Do not change:
- checkout calculations;
- promo ledger persistence;
- loyalty redemption/accrual logic;
- DB schema/migrations unless absolutely unavoidable;
- AI/Guest Mode/Managed Bots;
- Venue/Platform Mini App behavior.

Tests/validation:
./gradlew --no-daemon :backend:app:compileKotlin --console=plain
./gradlew --no-daemon :backend:app:test \
  --tests "*GuestVisitRoutesTest*" \
  --tests "*GuestOrderRoutesTest*" \
  --tests "*TelegramBotRouterTableTokenTest*" \
  --tests "*TelegramKeyboardsTest*" \
  --console=plain
cd miniapp && npm run build
```
