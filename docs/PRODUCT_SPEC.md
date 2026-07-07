# Product Spec v1 — Telegram Hookah Platform (Multi-tenant)

## Product summary
A single Telegram bot + Telegram Mini App (WebApp) that serves many hookah lounges (“venues”) without code changes per new venue.
Guests can browse a catalog, book a visit, and in-venue scan a table QR to open the Mini App, view menu, place orders and call staff.
Venue OWNER/MANAGER/STAFF users manage their own venue according to runtime `venue_members` permissions: tables/QRs, menu with photos/options, stop-list, bookings, staff roles, and order processing.
Platform Owner manages onboarding of venues, venue OWNER invitations/revocation, lifecycle (publish/hide/suspend/archive/delete), subscriptions (trial/prices), support and analytics.

Order/session/tab source of truth:
- Canonical `TABLE_SESSION`, `ACTIVE_TABLE_ORDER`, `ORDER_BATCH`, `TAB`, bill/request/close and visit-history foundation model is `docs/ORDER_SESSION_TAB_CORE.md`.
- Current runtime docs say the old active-order-by-physical-table risk is closed; future work must keep current-vs-target gaps explicit.

Analytics/events source of truth:
- Canonical analytics events, audit/event boundaries, KPI formulas, dashboards and privacy rules are tracked in `docs/ANALYTICS_EVENTS.md`.
- Analytics events are not source of truth for operations; domain tables remain authoritative.

Security/RBAC source of truth:
- Canonical roles, scopes, permissions, surface parity, dangerous actions and current-vs-target gaps are tracked in `docs/SECURITY_RBAC_MATRIX.md`.
- Server-side RBAC is the source of truth. UI hiding, Telegram keyboards, QR/table tokens and tab invite tokens are never authority by themselves.

## Core surfaces
- Telegram Bot (chat): navigation, fallback ordering, booking fallback
- Telegram Mini App (WebApp): main UI (Guest/Venue/Platform modes)
- Optional staff group chat per venue for operational notifications and live order activity-card shortcuts (venue creates it; platform stores chat_id and live message ids where needed)

## Roles (RBAC)
Platform:
- platform_owner (super-admin)

Platform Mode source of truth:
- Platform cockpit model and current-vs-target lifecycle/billing/support status are tracked in `docs/PLATFORM_COCKPIT.md`.
Venue:
- OWNER, MANAGER, STAFF in `venue_members`
- ADMIN is a legacy DB compatibility alias that maps to MANAGER; it is not a separate runtime permission model or selectable Platform Mini App assignment role.
Guest:
- guest (end users)
Derived responsibilities:
- Tab Host and Tab Member are scoped tab responsibilities, not global roles.
- Support actor is a derived responsibility for Guest, Venue Owner/Manager or Platform Owner inside a support ticket, not a separate global product role.

## Core entities (domain)
- venue: id, name, structured public location (country/city/address/formatted address/optional coordinates), description, hours, status(lifecycle), owner_account_id legacy/primary linkage, settings
- venue_members: (user_id, venue_id, role) as the source of runtime venue access
- menu_category, menu_item: name, price, description, photos, options (e.g., flavors), is_available (stop-list), is_featured (top list)
- table: id, venue_id, name/number, qr_token, status
- table_session: id, table_id, venue_id, started_at, ended_at, status
- order: one active per table_session
- batch: dosa order inside order; statuses new/accepted/in_progress/delivered/rejected/cancelled
- call: staff call (waiter/hookah/other), statuses new/accepted/done
- booking: id, venue_id, user_id, date/time, party_size(optional), status pending/confirmed/changed/cancelled/expired
- tab: personal/shared bill context inside table_session; membership list; permissions
- shift_extension_settings: per-venue paid extension policy, fixed duration/price, enabled flag
- shift_extension_request: guest request to extend active table/venue service window; statuses pending/approved/rejected/cancelled
- order_service_charge: non-menu bill charge such as approved paid extension; included in bill totals but not shown as a normal order-menu item
- subscription: venue_id, status trialing/active/past_due/suspended/canceled, price override, trial_end, paid_until, grace_end, methods enabled(card/stars)
- support_ticket: guest/venue/platform tickets with context (venue/table/order)
- visit: product-level history/retention concept derived from table session + closed order + booking seated/no-show where implemented; see `docs/ORDER_SESSION_TAB_CORE.md`

## Key UX decisions (must)
- Mini App is primary UX. If Mini App fails to load, show button: “Не грузится? → Оформить в чате” which triggers fallback ordering in chat.
- Each venue builds its own menu inside the admin UX (no platform code changes).
- Stop-list is a 1-click toggle (checkbox) per item; unavailable items disappear from guest menu.
- Each table has its own unique QR. QR opens bot start parameter → establishes table context → opens Mini App.
- Split bill must prevent abuse: by default personal tabs; shared tab requires explicit join/consent.

---

# Blocks 1–18 (requirements)

## Block 1 — Surfaces & navigation
MUST:
- Bot + Mini App. Guest/Venue/Platform modes in Mini App.
- Fallback chat flow for ordering/booking when Mini App fails.
SHOULD:
- Deep links to open venue or table context.

## Block 2 — RBAC & permissions
MUST:
- Use `docs/SECURITY_RBAC_MATRIX.md` as the canonical permission model for roles, scopes, dangerous actions and current-vs-target gaps.
- Server-side RBAC checks on every admin/staff action.
- Venue owner can grant/revoke supported staff/manager roles inside its venue with last-owner protection.
- Platform Owner can create venues, invite/add venue OWNER users, list active OWNER memberships and revoke a venue OWNER only when another active OWNER remains.
- Runtime venue ownership access is based on active `venue_members` rows with role `OWNER`; membership revoke does not relink `venues.owner_account_id` or legal/billing primary owner records.
- Staff has no support-ticket, ordinary venue-chat, billing, settings or platform permissions in the MVP.
- Platform Owner sees support tickets but not ordinary `VENUE_CHAT` unless future product policy explicitly changes it.
SHOULD:
- Audit log for owner invite/revoke and role changes.

## Block 3 — Entry points & context (QR/deep links)
MUST:
- Catalog entry, venue link entry, table QR entry.
- Table QR sets context (venue_id + table_id via opaque token).
- QR/table token is a context pointer, not authorization; server validates guest/session/tab/venue access for every order, staff call, bill and tab action.
- Tokens are non-guessable; support re-issue.
SHOULD:
- Venue “general QR” (no table) that opens venue card/menu preview.

## Block 4 — Guest journey (catalog/booking/visit)
MUST:
- List venues with address, description, hours, menu preview and pricing.
- Booking: user selects venue + date/time; venue confirms/changes/cancels.
- Booking status notifications (within Telegram constraints).
- Guest booking parity: Telegram Bot `/my` and Guest Mini App `Мои брони` show the same active/upcoming booking identity, venue-local time, status, party size and `Держим до HH:mm` deadline when applicable.
- Guest table context must survive normal Telegram/Mini App refreshes during a visit, but must not trap the guest forever. Mini App and Bot expose `Завершить визит` for the current guest/table context.
SHOULD:
- Favorites and history (Block 15) integrate with catalog.
- Returning guest can safely restore an active table context while their table session/tab/order is still open, without rescanning QR. Manual `Завершить визит` stores a user-scoped exit marker so that guest no longer restores the table context; scanning the table QR again explicitly re-enters and clears that marker.
- Manual guest exit is blocked only for that guest's obligations in the current `table_session`: active order/bill batches or active `NEW`/`ACK` staff calls created by the guest. Empty personal tabs and another guest's order/call at the same table do not block exit.
- Current staging-smoked behavior: pre-visit venue card keeps address, route/copy address and booking actions; active table context hides those pre-visit actions and shows `Завершить визит`. Exiting does not close the shared physical `table_sessions` row for other guests.
- Adaptive booking reminders are implemented as M7c code/test-backed behavior and passed one controlled real Telegram staging smoke; runtime remains disabled by default and requires explicit opt-in. MVP sends at most one transactional reminder for `CONFIRMED`/`CHANGED` bookings, calculated in venue-local time with a 24h preferred target, 3h fallback, quiet window 10:00-22:00, and actions `Да, буду`, `Перенести`, `Отменить`. Legacy reminder rows are preserved but isolated by policy version; legacy unsent rows are reconciled to canceled by migration and cannot be claimed by the M7c worker. Outbox enqueue means `QUEUED`, not Telegram-delivered. `Да, буду` / `Я приду` records guest attendance intent separately from venue-controlled booking status, edits the guest reminder state, and exposes the response in Bot `/my`, Guest Mini App `Мои брони` and Venue Mini App booking queue. The latest enriched staff-chat attendance copy is code/test-backed but has not been manually re-smoked with a new booking.

## Block 5 — Orders (one active order per table_session + batches)
MUST:
- Follow `docs/ORDER_SESSION_TAB_CORE.md` for canonical order/session/tab behavior and current-vs-target notes.
- One active order per `table_session_id`; multiple batches (dosa orders).
- Target scope for `ACTIVE_TABLE_ORDER` is `venue_id + table_id + table_session_id`, not physical `table_id` alone.
- Guest: browse menu, add to cart, submit batch with notes.
- Every `ORDER_BATCH` belongs to the active order and current `tab_id`, has source such as Mini App or bot fallback, is idempotent by client idempotency key and preserves item/price/option snapshots.
- Staff: receive and process batches with statuses.
- Staff assignment optional; prevent double-accept conflicts.
- PostgreSQL and the H2 test schema both enforce one `ACTIVE` order per `table_session_id`; the H2/PostgreSQL fidelity milestone is CLOSED / validation passed and did not change runtime API/routes/Mini App/Bot behavior.
SHOULD:
- Out-of-stock handling via stop-list, not via last-minute rejects.
- Staff-facing live order messages keep the main order and every add-batch/doporder visually separated, with batch status/action context clear to operators. Order/bill totals still come from the canonical backend bill snapshot.
- Guest/staff bill identity parity is CLOSED / staging smoke passed as of 2026-07-02: Guest Mini App, Venue Mini App and Telegram staff full-bill surfaces use human order labels (`Заказ №<display_number>`) when present. Guest Mini App shows the selected/current tab bill with a human account label (`Личный счёт` / `Общий счёт`) and does not use raw `tabId` or technical `orderId` as the primary visible identity. Venue Mini App full bill shows included/excluded/discounted items, service charges, closed-state copy and human personal/shared account context for batches and non-payable rows. Venue Mini App / Bot / Guest totals match in the verified smoke.
- Guest bill request / payment method UX is CLOSED / staging smoke passed as of 2026-07-02. Guest Mini App active order screen exposes `Попросить счёт`, then choices `Картой на месте`, `Наличными`, `Пока не знаю`. This is only an on-site operational note for staff, not online payment, acquiring, Telegram Payments/Stars or automatic bill close. Mini App sends JSON `POST /api/guest/order/bill-request` with `Content-Type: application/json`, Authorization, `tableToken`, `tableSessionId`, `tabId` and `paymentMethod`. Backend validates current Telegram user, active table session, tab membership and active order, stores structured context on `staff_calls`, dedupes active `NEW`/`ACK` requests per venue/table_session/tab, allows a new request after `DONE`, updates the order-scoped live staff-chat activity card with table/order/account/total/payment method when available, and falls back to standalone notification only if the live-card path cannot be loaded. Venue Mini App staff-call queue shows bill-request order/account/payment context through the existing ACK/DONE flow.
- Staff Chat Noise Reduction / Table Activity Card is CLOSED / staging smoke passed as of 2026-07-02. Venue Mode remains the source of truth; staff chat is a radar/shortcut. Order, reorder, bill request and safely linked staff-call activity update one live order card keyed by `orderId`; unsafe/no-order/ambiguous staff calls remain standalone. Manual `Обновить` preserves order activity, bill request and staff-call sections; activity uses compact visual markers such as `🆕`, `🚨`, `🛎️`, `🧾`, `💳`, `💵`, `❓`; DONE/CANCELLED generic calls do not remain in the active `Оперативно` section; closing the order/bill resolves linked active BILL requests and closed-visit staff-call leftovers. No staff-chat forum topics, table-session-level card storage, split-bill redesign or online payment provider was added.

## Block 6 — Split bill (personal/shared) with anti-abuse
MUST:
- Follow `docs/ORDER_SESSION_TAB_CORE.md` for canonical split-bill/tab invariants.
- Default personal tab per user in a table_session.
- Shared tab creation; join requires explicit consent/invite.
- Prevent ordering on another user’s tab without permission.
- Guest can add batches only to own personal tab or a joined shared tab.
- Closed/paid tabs are immutable for new batches unless reopened by an allowed role with audit.
- PostgreSQL and the H2 test schema both enforce one active `PERSONAL` tab per `table_session_id + owner_user_id`.
SHOULD:
- Show clearly “which tab you’re ordering to” in UI.
- Visible account labels must be human-readable. Raw `tabId` may stay in API payloads, selectors, internal state and debug logs, but not as customer/staff-facing account copy.

## Block 7 — Venue onboarding & settings
MUST:
- Venue owner/admin can set: description, hours, contact and public location/address; Mini App public-location editing must work provider-free through local country/city lists and manual address entry, with optional backend geodata providers kept disabled/commercial-only unless approved.
- M8a/M8b-Free public card/location basics are CLOSED after staging smoke: Venue Mini App lets OWNER/MANAGER edit public city/address/contact/description plus structured country/city/address fields, while venue name stays read-only and STAFF stays denied/hidden.
- M9b/M9b.1/M9b.2/M9b.3 schedule parity is CLOSED / staging smoke passed: Venue Mini App lets OWNER/MANAGER manage weekly working hours, date exceptions, inclusive exception ranges, post-save exception UX and changed date ranges after creation; STAFF stays denied/hidden.
- Bundled country/city data is a convenience seed, not address verification; full local street/house autocomplete requires a separate ФИАС/ГАР import/indexing slice and must not depend on runtime third-party geodata APIs.
- Create tables; assign staff roles; configure visible modules.
- Connect staff group chat (optional) by providing chat_id / invite flow.
SHOULD:
- Onboarding checklist + readiness score.
- Weekly working hours and concrete-date exceptions are displayed as distinct concepts: base weekday schedule, enabled/closed day state, and override state must not look like duplicate/conflicting controls.
- For launch, the existing weekly working-hours/date-exception model is intentionally the shared source for public venue open/closed state and booking slot availability. Missing schedule setup is shown as `График не указан` / `Заведение пока не настроило график бронирования.`, not as a deliberate closed day.
- Date exceptions support inclusive single-day or multi-day periods for closed dates and special hours; the same from/to date means one day. Optional guest-facing reason/comment may be shown in booking rejection copy; private admin notes must not leak through guest APIs.
- Range exceptions remain stored as per-date overrides for launch. Saving or editing a range upserts each target date, so overlapping target dates are deterministically overwritten by the latest saved closed/special-hours values and guest-facing reason/comment.
- Venue Mini App date-exception saves must give clear post-save feedback: close/reset the form, show the compact exception list, and render changed-hours rows with the date range, special hours and guest comment.
- Venue Mini App date-exception edits must allow changing the inclusive date range; when ranges are stored as per-date overrides, editing a grouped row replaces the old date set atomically.
- Guest booking rejection copy must be human in Bot and Mini App: missing schedule rejects direct booking with `Заведение пока не настроило график бронирования.`, closed dates say the venue does not work on the selected date and include the optional reason when present, and out-of-hours rejection says booking is unavailable with the effective hours for that day when known.

## Block 8 — Menu builder + photos + stop-list + top list
MUST:
- Categories, items, prices, descriptions, photos.
- Options: flavors/strength/etc (configurable per venue).
- Stop-list toggle per item (available true/false) with immediate effect.
- “Top list” / featured items pinning.
SHOULD:
- Bulk edit / import; optional PDF attachment as secondary (not primary).
- Informational `Фото-меню` can stay as one media list in simple mode, but may support optional owner-defined subsections in advanced mode. This is separate from the structured order menu.

### Guest/Menu Options & Flavors Parity — CLOSED / staging smoke passed

Product intent:
- Menu item options/flavors are structured order modifiers, not free-text comments.
- Guest Bot and Guest Mini App must both show and require the same option/flavor choice for menu items that have available options.
- Same menu item with different selected option/flavor is a separate cart/order line.
- Selected options can affect price through `priceDeltaMinor`; cart preview, checkout, order detail, bill and staff chat must all use the structured selected-option price.
- Staff/venue order detail and staff-chat live order messages must show selected options next to the ordered item.
- Stop-listed or unavailable options must be hidden from guest choice and rejected safely at preview/checkout if submitted stale.
- Guest Bot must stop relying only on comment-only flavor persistence such as `Выбранные вкусы`; comment can remain guest-visible context, but money/read-model truth must be structured.
- Optional guest preparation preference text (`Пожелание к вкусу`) is allowed only as a line-level note attached to the selected cart/order line. It is not a custom flavor, has no price semantics, must be length-limited, and must not replace venue-configured structured options.

Current state:
- Venue menu configuration supports item-scoped options/flavors and both item-level and option-level availability.
- Guest Bot and Guest Mini App submit structured selected options; cart/read models preserve selected option snapshots and line-level preparation notes when present.
- Guest Mini App hides unavailable options in the picker, and backend preview/checkout rejects unavailable or foreign selected options.
- Venue Mini App exposes item-scoped option/flavor CRUD for OWNER/MANAGER and operational stop-list toggles for STAFF/MANAGER/OWNER. STAFF may change only item/option availability; STAFF must not create/edit/delete/reorder items, edit prices, add/delete flavors or apply base flavor profiles. Hookah items show `Вкусы / опции`; non-hookah items show neutral `Опции` only when options are configured.
- Venue Mini App applies missing bot canonical base flavor profiles through item-scoped `Добавить базовые вкусы` for hookah items.
- Staging smoke passed: new/old hookah items show the same editor controls, base profiles are copied only to that hookah item, repeated apply does not duplicate profiles, owner/manager can add/edit/delete/toggle flavors, whole item stop-list and flavor-level stop-list work, water/kitchen/drink items do not receive hookah flavors, Guest Mini App shows the picker only for the selected hookah item, and hookah/flavor preparation notes use hookah-relevant placeholder copy (`Например: покрепче, полегче, больше мяты, без ментола`) while food/drink options keep the generic preparation examples.

Target behavior:
1. Guest menu DTOs expose available options for each item that needs a guest choice.
2. Guest Bot and Guest Mini App render an item option picker before adding that item to cart.
3. Cart identity includes item id plus selected option id(s) plus normalized line preference note, so `Classic · Ягодный · без мяты` and `Classic · Ягодный · покрепче` remain distinct lines.
4. Preview and checkout include option price deltas and validate the option belongs to the item/venue and is available.
5. Order/batch/read-model snapshots preserve selected option id, name and price delta at checkout time so later option edits do not rewrite historical bills.
6. Venue order detail, staff chat, Guest Bot `Мой заказ` and guest active order/bill show selected options and line-level preference notes in human copy under the ordered item.
7. If an option is disabled between selection and submit, preview/checkout rejects it with clear guest copy and does not silently downgrade to the base item.

Implementation slices:
A. Backend structured selected-option persistence and bill/read-model support, including stale/unavailable option rejection and price-delta calculation.
B. Guest Bot submits structured selected option data instead of comment-only flavor persistence, while preserving current flavor choice UX.
C. Guest Mini App item option picker, cart line identity by selected option and structured preview/checkout payload.
D. Venue Mini App option CRUD/flavor-profile parity for OWNER/MANAGER, with STAFF limited by existing menu/availability permissions.
E. Smoke/docs closure: bot vs Mini App option parity, staff chat/order detail display, unavailable option rejection and money snapshot tests.

Remaining follow-ups after parity closure:
- Mini App normalize/reset base flavor profiles only if still needed after venue pilots.
- DB-level duplicate/race protection for base flavor profile apply only if concurrent apply becomes a real operational issue.
- Broad Venue Mini App IA parity with bot sections remains separate. `Работа смены`, `Настройки` and read-only `Статистика` now have real screens; M2 read-only `Статистика` passed staging smoke for OWNER/MANAGER with STAFF hidden. `Продвижение` and `Предпросмотр для гостя` must stay hidden until backend-backed screens exist. Custom date range stats and AI-generated summaries are later follow-ups, not current launch scope.

## Block 9 — Tables & QR
MUST:
- Follow `docs/ORDER_SESSION_TAB_CORE.md` for `TABLE_SESSION` lifecycle, close/expire policy and guest re-entry rules.
- Table CRUD; unique QR per table.
- QR token rotation/reissue.
- Print/export QR for venues.
- Table sessions: created on scan; TTL/closing rules. Current `table_sessions` are shared by physical table, so a guest-level exit must not close the shared row.
- Per-guest visit exit is represented by a user-scoped marker keyed by Telegram user and `table_session_id`; restore ignores exited markers for that user, and explicit QR resolve clears that user's marker.
SHOULD:
- “Table code” confirmation for high-risk actions (optional anti-photo-QR).

## Block 10 — Shift ops + staff chat live-card behavior
MUST:
- Staff queue view (new/accepted/in_progress/delivered).
- Staff calls view and acknowledgements.
- Duplicate order/call notifications to venue staff group chat if configured.
SHOULD:
- SLA timers and alerts.
- Paid extension of the current venue/table service window can be requested by guest and confirmed by staff/manager; confirmed extension adds the agreed charge to the bill and extends the current operational/session window.

### Paid venue/shift extension — P1 design

Product intent:
- Paid extension is a table service request, not an ordinary menu item/cart item/order batch item.
- Guest sees it in the same table service/order flow as menu and bill, but the charge is created only after staff confirmation.
- Staff/manager sees it in the active table/order/bill context, not as a separate operational island.
- Confirmation adds a fixed, preconfigured charge to the current bill and extends the active table/session ordering window by the configured duration, default 60 minutes.
- Extension can be repeated while the bill/session remains active.
- Staff chat must stay one live order/bill message where possible; extension state should update that message instead of creating noisy side messages.

Current implementation map:
- Backend/data/API: settings, pending requests, approve/reject, service charge creation, session extension and bill snapshot totals are implemented.
- Guest Mini App: active order/table state exposes service action `Продление работы заведения` only when extension settings and current order/bill state make it actionable; request/pending/confirmed states are implemented outside cart logic.
- Venue Mini App: owner/manager settings are implemented; order queue/detail exposes pending extension state and staff/manager/owner approve/reject from the order context.
- Staff chat: pending extension appears inside the existing live order/bill message with approve/reject actions; approved/rejected state refreshes the same live message and approval shows the service charge under bill service charges.
- Guest Bot: bot ordering menu section lists expose `Продление работы заведения` from `🍽️ Меню` and `Мой заказ → Дозаказать`; the request screen creates the same fixed-price pending request, shows duplicate pending state and keeps extension outside cart/menu-item/cart/order-batch logic.
- Owner/Manager Bot: extension settings flow is not implemented.

Guest Mini App target:
1. In active table context, show service action `Продление работы заведения` in the ordering section list only when venue extension settings are enabled, price is configured, and the table session/order is active.
2. Inside the service action, show button `Продлить на 1 час` and copy: `Продление на 1 час — 3 000 ₽. Персонал подтвердит возможность продления.`
3. Guest sends request with optional comment; UI state becomes `Ожидает подтверждения`.
4. If approved, guest sees `Продление подтверждено до HH:mm. Сумма добавлена в счёт.`
5. If rejected or timed out, guest sees `Продление отклонено. Обратитесь к персоналу.` or another clear non-mutating status, and the bill is unchanged.
6. The service action must never add anything to cart, menu items, order batches or batch item snapshots.

Guest Bot target:
1. In every bot ordering menu section list entry point, including `🍽️ Меню` and `Мой заказ → Дозаказать`, show `Продление работы заведения` only when the venue exposes enabled, configured extension settings and the current table/order state makes extension actionable.
2. Opening it shows `Продление на 1 час — 3 000 ₽` and `Персонал подтвердит возможность продления.`
3. Primary action: `Продлить на 1 час`; after submit, replace action with `Ожидает подтверждения`.
4. Duplicate pending requests should show the existing pending state instead of creating another request.
5. Approval/rejection should be reflected on the next table/order refresh with human copy: `Продление подтверждено. Сумма добавлена в счёт.` or `Продление отклонено. Обратитесь к персоналу.`
6. Guest bot implementation must use the same fixed-price backend semantics as Mini App and must not create menu/cart/order-batch records.

Venue Mini App target:
1. Remove or demote the standalone primary `Продления` navigation entry after order-scoped handling is available.
2. Order/table queue rows show a compact pending badge/count, for example `Продление ожидает`, when an active order has pending extension requests.
3. Order detail shows an operational block inside the bill/order context:
   - title `Запрос на продление работы заведения`;
   - amount line `На 1 час — 3 000 ₽`;
   - current/proposed allowed-until time;
   - buttons `✅ Подтвердить продление` and `❌ Отказать` for roles with confirmation permission.
4. Approve/reject refreshes the same order detail and bill snapshot. Approval adds service charge `Продление работы на 1 час`; rejection must not mutate bill/session.
5. STAFF can see and approve/reject fixed-price requests, but must not see settings/price/duration edit controls.

Staff chat target:
1. Pending extension appears inside the existing live order/bill message under a section `Запрос на продление работы заведения`.
2. The section shows `На 1 час — 3 000 ₽`, current/proposed time and guest/comment if available.
3. Inline buttons on the live message: `✅ Подтвердить продление` and `❌ Отказать`.
4. Actions update the same live order/bill message and remove/disable stale pending buttons. Approval then shows the service charge under `Дополнительно`; rejection shows non-bill status or removes the pending block.
5. Do not send a new staff-chat message for every extension lifecycle step unless the live message is missing/unrecoverable; use existing fallback logic sparingly.

Owner/Manager settings target:
1. Venue Mini App and bot eventually both expose `Продление времени` settings for OWNER/MANAGER only.
2. Fields: enabled, duration minutes, price in ₽, currency `RUB`, optional max repeats later.
3. Toggle copy: `Показывать гостям возможность продления`.
4. Helper copy: `Если выключено, гости не увидят продление, но цена и длительность сохранятся.`
5. STAFF must not see configuration controls in any channel.

Venue/Staff copy:
1. Staff/manager sees a pending extension request with venue/table/session/order, guest display name, price, current allowed-until time and proposed new allowed-until time.
2. Buttons: `✅ Подтвердить продление` and `❌ Отказать`.
3. Approval is idempotent: one request can create one charge and one session extension only once.
4. Rejection requires either a short reason or safe default `Причина не указана`.

Role policy:
- STAFF may approve/reject fixed-price extension requests because this is an operational shift action, not arbitrary billing editing.
- STAFF must not configure extension price/duration, waive the charge, apply manual discounts or change session rules.
- MANAGER/OWNER may approve/reject requests and configure extension settings.
- OWNER/MANAGER configure: enabled, duration minutes, price, currency, optional max repeats per table session.

API/data target:
- `GET /api/guest/table/extension-options` returns enabled state, duration, price, current allowed-until and whether a pending request exists.
- `POST /api/guest/table/extension-requests` creates a pending request for authenticated tab member.
- `GET /api/venue/{venueId}/shift-extension-requests?status=pending` lists operational requests.
- Order queue/detail read models should expose pending extension request state by order/table so Venue Mini App and staff chat do not need a separate polling island.
- Staff chat needs request id and order id in inline callback payloads, guarded by the same `SHIFT_EXTENSION_CONFIRM` permission policy.
- Guest Bot and Owner/Manager Bot may call repository/service code directly instead of going through HTTP, but must share validation/copy/state semantics with Mini App APIs.
- `POST /api/venue/{venueId}/shift-extension-requests/{requestId}/approve` approves and atomically:
  - validates role/venue/session/order;
  - creates an `order_service_charge` with source `SHIFT_EXTENSION`;
  - extends `table_sessions.expires_at` to at least the approved `extended_until`;
  - refreshes Guest/Venue bill DTOs and staff-chat live order message through the canonical bill snapshot path.
- `POST /api/venue/{venueId}/shift-extension-requests/{requestId}/reject` rejects without bill/session mutation.

Bill/session rules:
- Extension charge is a dedicated service charge line, not a `menu_item`.
- Order bill snapshot must include service charges in gross/final payable totals and expose a readable line `Продление работы на 1 час`.
- Approved extension uses venue timezone for all user-facing times.
- `table_sessions.expires_at` is the current orderable-until/session window. Extension logic must not be shortened by normal session touch/restore operations.
- Closed bills, closed tabs, ended sessions and unavailable/deleted/suspended venues cannot be extended.

Current checkpoint:
- Backend data/API, Guest Mini App request UX, bill service charges, Venue Mini App order integration, staff-chat pending approve/reject actions and Guest Bot table-flow request entry are implemented.
- Remaining parity gap in this block: Owner/Manager Bot settings closure for enabled/duration/price where still required by the active roadmap.

Implementation slices:
1. Backend order-scoped read model: pending extension summary/count on order queue/detail, with no DB migration unless the existing request table cannot support the read shape.
2. Venue Mini App order integration: pending badge in queue, approve/reject block inside order detail, standalone `Продления` nav removed or demoted after parity is complete.
3. Staff chat live message integration: pending request block and inline approve/reject callbacks on the live order/bill message, with no noisy lifecycle messages.
4. Guest Bot table-flow request: service entry `Продление работы заведения`, request/pending state, same duplicate-pending behavior and service-charge visibility in `Мой заказ`.
5. Owner/Manager Bot settings parity: enabled/duration/price flow under venue settings; STAFF hidden/forbidden.
6. Regression closure: cross-channel bill snapshot, QR/table restore, role denial, pending/approve/reject, staff-chat one-message behavior and bot/Mini App parity smoke.

## Block 11 — Platform Mode (multi-venue onboarding & lifecycle)
MUST:
- Treat Platform Mode as one cockpit for venues, onboarding requests, venue lifecycle, owner/access, billing/subscriptions/invoices, Support Center, analytics/audit and operational risk/health indicators.
- Platform Owner can create venue, invite/add venue OWNER users, list active OWNER memberships and revoke one OWNER only when another active OWNER remains.
- OWNER invite/revoke actions are audited; membership revoke does not relink primary/legal/billing owner linkage.
- Current implementation lifecycle statuses are `DRAFT`, `PUBLISHED`, `HIDDEN`, `PAUSED`, `SUSPENDED`, `ARCHIVED`, `DELETED`.
- Target product lifecycle is `draft`, `onboarding`, `published`, `hidden`, `paused_by_owner`, `suspended_by_platform`, `archived`, `deletion_requested`, `deleted`; currently `onboarding` is folded into `DRAFT`, `paused_by_owner` into `PAUSED`, `suspended_by_platform` into `SUSPENDED`, and `deletion_requested` into `DELETED`.
- Ability to hide/archive/delete venues; clean inactive venues.
SHOULD:
- Moderation flags and notes; onboarding request cockpit; bulk actions; risk/health indicators.

## Block 12 — Monetization & subscription
MUST:
- Subscription per venue with trial, `active`, `past_due`, `canceled`, `suspended`, `suspended_by_platform` and per-venue price overrides.
- Current staging-smoked MVP is manual/fake-provider billing: Platform/Venue overviews, explicit invoice/checkout ensure POST, manual mark-paid, next-period invoice creation and courtesy days. It does not close a real acquiring provider rollout.
- External card checkout and `GenericHmacBillingProvider` are provider-integration foundations; production acquiring still requires provider-specific secrets, webhook verification/idempotency and smoke.
- Optional Telegram Stars method remains future work unless implemented as a dedicated flow.
- Gating policy: `past_due` allows guest actions (orders/booking), while `suspended`, `suspended_by_platform`, and `canceled` block guest actions until status recovery.
- Platform owner can set/override prices without code changes.
- Platform Owner billing cockpit and Venue Owner subscription screen show read-only subscription/payment overviews through GET routes; invoice/checkout creation happens through explicit POST ensure actions.
- Manual/fake invoice flow must not expose provider-internal fake URLs or secrets to Mini App users; manual mark-paid writes audit evidence.
- Renewal invoices use real invoice periods: the next monthly period starts at current effective paid-through + 1 day, and repeated ensure reuses an existing OPEN/PAST_DUE invoice for the same period.
- Courtesy/free days are represented by `billing_adjustments` rows with kind `COURTESY_DAYS`, positive days, required reason, previous/new paid-through dates, actor user id and creation time. Courtesy writes `BILLING_COURTESY_DAYS_ADDED` audit and must not mutate paid invoice history.
- Venue Owner sees adjusted paid-through and next-payment dates but cannot mark invoices paid or add courtesy days. Manager/Staff cannot access billing payment controls.
- Payment/support problems route through `SUPPORT_TICKET` / Platform Support Center when platform-scoped.
SHOULD:
- Billing events log; self-serve invoice link generation.
- Follow-up: audited invoice void/reissue for open future invoice conflicts after courtesy adjustments.
- Follow-up: distinguish billing-created from manual `SUSPENDED_BY_PLATFORM` before broader auto-reactivation.

## Block 13 — Analytics/KPI/events
MUST:
- Use `docs/ANALYTICS_EVENTS.md` as the canonical analytics/event model. Current status is `SPEC UPDATED`; implementation remains partial unless a specific event/dashboard is verified.
- Keep analytics facts separate from audit logs, support audit, billing events and notification/outbox delivery state.
- Current event/audit foundation is partial: platform venue status audit, owner invite/revoke audit, billing checkout/mark-paid/courtesy audit, support ticket status/scope/assignment/escalation audit and staff-call/order audit exist where implemented.
- Server-side events needed for reporting include: table_session_started, order_batch_created, order_batch_status_changed, booking_status_changed, subscription_status_changed, venue_lifecycle_changed, owner_invite_created/accepted, billing_invoice_state_changed, support_ticket_status_changed and support_ticket_scope_changed.
- Order/session/tab analytics from `docs/ORDER_SESSION_TAB_CORE.md` need: table_session_started/closed, order_batch_created/status_changed, tab_bill_requested/paid/closed, order_closed, booking_seated/no_show when implemented.
- Event names use snake_case past-tense facts; event timestamps are UTC; venue timezone is used for reporting aggregation; payloads must not include raw PII, raw Telegram initData, message text, payment secrets or card data.
- Correlation IDs (venue/table/session/order/batch/tab).
- Minimal PII; pseudonymize in analytics if exported.
SHOULD:
- Dashboards: platform + venue (owner) + shift (manager).
- Platform cockpit reports: venue lifecycle/subscription distribution, active/trialing/past_due/suspended counts, MRR after real providers exist, open/overdue invoices, support TTFR/TTR/escalation/reopen/CSAT/top issue themes, onboarding funnel and integration health.
- Future growth analytics after `docs/GROWTH_RETENTION.md` MVP: favorite rate, repeat visit rate, promo view/redeem, review completion and opt-in/unsubscribe.

## Block 14 — Security/anti-fraud/audit
MUST:
- Use `docs/SECURITY_RBAC_MATRIX.md` as the canonical security/RBAC matrix and smoke checklist.
- Validate Telegram WebApp initData server-side.
- Never trust `initDataUnsafe`.
- Verify Telegram webhook secret token header.
- Tenant isolation + RBAC enforced server-side.
- QR/table token and tab invite token are context pointers, not authorities.
- Table-session-scoped actions must verify table session, tab membership and venue ownership server-side.
- Telegram callback payloads must use opaque ids/tokens and must not include raw sensitive data.
- Idempotency keys for create-order/create-batch/payment webhooks.
- Rate limiting for staff calls and spam orders.
- Audit logs for critical changes (roles, prices, stop-list, lifecycle).
- Dangerous actions such as owner changes, lifecycle changes, QR rotation, staff-chat link/unlink, order force close, tab reopen, invoice manual mark-paid, subscription override and support transfer/close require confirmation/reason/audit where implemented.
SHOULD:
- Abuse detection heuristics and soft-lock.

## Block 15 — Growth (guest retention)
MUST:
- Use `docs/GROWTH_RETENTION.md` as the canonical model for guest growth and retention. Current status is `SPEC UPDATED / PARTIAL-FUTURE`, not a closed implementation claim.
- `FAVORITE_VENUE`: guest can favorite/unfavorite venues and list favorite venues.
- `VISIT_HISTORY`, `ORDER_HISTORY`, `BOOKING_HISTORY`: guest history combines confirmed visits, closed orders and bookings only after the visit/order/session model is stable.
- `REPEAT_TEMPLATE`: repeat uses a saved template and applies it on the next verified table context; it must not create an order without QR/table context, selected tab and current menu/stop-list validation.
- `POST_VISIT_FEEDBACK`: rating 1-5, tags and optional comment only after a confirmed visit/order close signal.
- `VENUE_PROMOTION`: simple venue promotions/banners with title, description, period, terms and visibility/status; do not promise automatic discounts without a real promo engine/accounting path.
- `OPT_IN_NOTIFICATION`: retention/promo notifications require explicit guest opt-in, frequency limits and unsubscribe.
SHOULD:
- `PROMO_CODE` with limits/accounting, `LOYALTY_STAMP` / `LOYALTY_POINTS`, `REFERRAL`, segmentation/campaigns, paid placement/promotion boosting, flavor quiz and advanced recommendations remain future work.
- Paid placement must be labeled and depends on Platform billing, moderation and analytics.
- Cashback/points/flexible loyalty must wait for a correct financial model and discount accounting.

## Block 16 — Support (tickets + diagnostics + escalations)
MUST:
- Use the canonical Guest communication model from `docs/COMMUNICATION_MODEL.md`: `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` are separate scenarios.
- `BOOKING_CHAT`: booking `Открыть переписку`; Guest + Venue Owner/Manager; not a support ticket; does not post to staff-chat.
- `VENUE_CHAT`: catalog `Задать вопрос` and venue detail `💬 Задать вопрос`; Guest + Venue Owner/Manager; Staff denied; Platform does not see ordinary venue chats; does not post to staff-chat; new thread creation is rate-limited and existing guest+venue chats are reused.
- `SUPPORT_TICKET`: global `Помощь` -> `Сообщить о проблеме` plus table-context secondary help/problem entry; Guest own tickets, Venue Owner/Manager own venue tickets, Platform Owner support tickets, Staff denied.
- Support tickets carry verified context when available: venue, table, table session, order, booking, user, source/app metadata.
- Support routing: technical/Mini App/bot/QR/platform issue can go to Platform without venue; order/service outside table requires verified venue/order/table context; booking outside table requires booking or venue; table context attaches verified venue/table/session and order context when available.
- Venue can manually `Передать платформе` a venue support ticket; Platform can list, reply and close support tickets, including platform-only and transferred tickets.
- Support ticket creation/replies and venue chat creation/replies do not post to staff-chat. Staff-chat remains for existing operational order, booking notification and `STAFF_CALL` behavior.
- Guest support ticket creation, venue chat creation and guest support messages are rate-limited.
- M4B/M4C booking-thread inbox lifecycle remains compatibility-critical: guest `Чаты` is a list of `BOOKING_CHAT` / `VENUE_CHAT` threads, not one endless global chat, and booking resolve/reopen/message flows must not regress.
SHOULD:
- SLA automation, auto-escalation worker, diagnostic report from Mini App, attachments, macros, CSAT and support analytics.

## Block 17 — GTM (onboarding pipeline)
MUST:
- Self-apply form for venues → lead pipeline stages (lead/qualified/demo/trial/activated/paid).
- Owner invite via deep link.
- Track lead source via tokens within Telegram constraints.
SHOULD:
- Partner/referral codes for B2B.

## Block 18 — Reliability/DevOps
MUST:
- Webhook ACK fast; process async via queue/worker.
- Outbound message queue with rate limiting + retry/backoff (handle 429).
- Monitoring for webhook backlog and failures.
- Graceful degradation: fallback bot ordering if Mini App fails; Venue Mode as source-of-truth if staff chat fails.
SHOULD:
- Feature flags; safe migrations; backups + restore drills.

--- END OF SPEC ---
