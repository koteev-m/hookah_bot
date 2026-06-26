# Product Spec v1 — Telegram Hookah Platform (Multi-tenant)

## Product summary
A single Telegram bot + Telegram Mini App (WebApp) that serves many hookah lounges (“venues”) without code changes per new venue.
Guests can browse a catalog, book a visit, and in-venue scan a table QR to open the Mini App, view menu, place orders and call staff.
Venue owners/admins manage their own venue: tables/QRs, menu with photos/options, stop-list, bookings, staff roles, and order processing.
Platform owner/admins manage onboarding of venues, lifecycle (publish/hide/suspend/archive/delete), subscriptions (trial/prices), support and analytics.

## Core surfaces
- Telegram Bot (chat): navigation, fallback ordering, booking fallback
- Telegram Mini App (WebApp): main UI (Guest/Venue/Platform modes)
- Optional staff group chat per venue for order/call duplicates (venue creates it; platform just stores chat_id)

## Roles (RBAC)
Platform:
- platform_owner (super-admin), platform_admin (optional)
Venue:
- venue_owner (can grant roles), venue_admin, venue_manager
Staff:
- staff_waiter, staff_hookah (or generic staff)
Guest:
- guest (end users)

## Core entities (domain)
- venue: id, name, structured public location (country/city/address/formatted address/optional coordinates), description, hours, status(lifecycle), owner_user_id, settings
- user_role: (user_id, venue_id?, role)
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
- Server-side RBAC checks on every admin/staff action.
- Venue owner can grant/revoke roles inside its venue.
- Platform owner can create venues and assign venue_owner.
SHOULD:
- Audit log for role changes.

## Block 3 — Entry points & context (QR/deep links)
MUST:
- Catalog entry, venue link entry, table QR entry.
- Table QR sets context (venue_id + table_id via opaque token).
- Tokens are non-guessable; support re-issue.
SHOULD:
- Venue “general QR” (no table) that opens venue card/menu preview.

## Block 4 — Guest journey (catalog/booking/visit)
MUST:
- List venues with address, description, hours, menu preview and pricing.
- Booking: user selects venue + date/time; venue confirms/changes/cancels.
- Booking status notifications (within Telegram constraints).
- Guest booking parity: Telegram Bot `/my` and Guest Mini App `Мои брони` show the same active/upcoming booking identity, venue-local time, status, party size and `Держим до HH:mm` deadline when applicable.
SHOULD:
- Favorites and history (Block 15) integrate with catalog.
- Returning guest can safely restore an active table context while their table session/tab/order is still open, without rescanning QR, and the context resets after bill close.
- Adaptive booking reminders are implemented as M7c code/test-backed behavior and passed one controlled real Telegram staging smoke; runtime remains disabled by default and requires explicit opt-in. MVP sends at most one transactional reminder for `CONFIRMED`/`CHANGED` bookings, calculated in venue-local time with a 24h preferred target, 3h fallback, quiet window 10:00-22:00, and actions `Да, буду`, `Перенести`, `Отменить`. Legacy reminder rows are preserved but isolated by policy version; legacy unsent rows are reconciled to canceled by migration and cannot be claimed by the M7c worker. Outbox enqueue means `QUEUED`, not Telegram-delivered. `Да, буду` / `Я приду` records guest attendance intent separately from venue-controlled booking status, edits the guest reminder state, and exposes the response in Bot `/my`, Guest Mini App `Мои брони` and Venue Mini App booking queue. The latest enriched staff-chat attendance copy is code/test-backed but has not been manually re-smoked with a new booking.

## Block 5 — Orders (one active order per table + batches)
MUST:
- One active order per table_session; multiple batches (dosa orders).
- Guest: browse menu, add to cart, submit batch with notes.
- Staff: receive and process batches with statuses.
- Staff assignment optional; prevent double-accept conflicts.
SHOULD:
- Out-of-stock handling via stop-list, not via last-minute rejects.
- Staff-facing live order messages keep the main order and every add-batch/doporder visually separated, with batch status/action context clear to operators. Order/bill totals still come from the canonical backend bill snapshot.

## Block 6 — Split bill (personal/shared) with anti-abuse
MUST:
- Default personal tab per user in a table_session.
- Shared tab creation; join requires explicit consent/invite.
- Prevent ordering on another user’s tab without permission.
SHOULD:
- Show clearly “which tab you’re ordering to” in UI.

## Block 7 — Venue onboarding & settings
MUST:
- Venue owner/admin can set: description, hours, contact and public location/address; Mini App public-location editing must work provider-free through local country/city lists and manual address entry, with optional backend geodata providers kept disabled/commercial-only unless approved.
- M8a/M8b-Free public card/location basics are CLOSED after staging smoke: Venue Mini App lets OWNER/MANAGER edit public city/address/contact/description plus structured country/city/address fields, while venue name stays read-only and STAFF stays denied/hidden.
- Bundled country/city data is a convenience seed, not address verification; full local street/house autocomplete requires a separate ФИАС/ГАР import/indexing slice and must not depend on runtime third-party geodata APIs.
- Create tables; assign staff roles; configure visible modules.
- Connect staff group chat (optional) by providing chat_id / invite flow.
SHOULD:
- Onboarding checklist + readiness score.
- Weekly working hours and concrete-date exceptions are displayed as distinct concepts: base weekday schedule, enabled/closed day state, and override state must not look like duplicate/conflicting controls.
- For launch, the existing weekly working-hours/date-exception model is intentionally the shared source for public venue open/closed state and booking slot availability. Missing schedule setup is shown as `График не указан` / `Заведение пока не настроило график бронирования.`, not as a deliberate closed day.
- Date exceptions support inclusive single-day or multi-day periods for closed dates and special hours. Optional guest-facing reason/comment may be shown in booking rejection copy; private admin notes must not leak through guest APIs.
- Venue Mini App date-exception saves must give clear post-save feedback: close/reset the form, show the compact exception list, and render changed-hours rows with the date range, special hours and guest comment.
- Venue Mini App date-exception edits must allow changing the inclusive date range; when ranges are stored as per-date overrides, editing a grouped row replaces the old date set atomically.
- Guest booking rejection copy must be human: closed dates say the venue does not work on the selected date and include the optional reason when present; out-of-hours rejection says booking is unavailable and shows the effective hours for that day when known.

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
- Staging smoke passed: new/old hookah items show the same editor controls, base profiles are copied only to that hookah item, repeated apply does not duplicate profiles, owner/manager can add/edit/delete/toggle flavors, whole item stop-list and flavor-level stop-list work, water/kitchen/drink items do not receive hookah flavors, and Guest Mini App shows the picker only for the selected hookah item.

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
- Table CRUD; unique QR per table.
- QR token rotation/reissue.
- Print/export QR for venues.
- Table sessions: created on scan; TTL/closing rules.
SHOULD:
- “Table code” confirmation for high-risk actions (optional anti-photo-QR).

## Block 10 — Shift ops + staff chat duplicate
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
- Guest Mini App: active table menu exposes service action `Продление работы заведения`; request/pending/confirmed states are implemented outside cart logic.
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
1. In every bot ordering menu section list entry point, including `🍽️ Меню` and `Мой заказ → Дозаказать`, show `Продление работы заведения` when the venue exposes enabled, configured extension settings.
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

Current post-M6 checkpoint:
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
- Platform owner can create venue and assign venue_owner without code changes.
- Lifecycle statuses: draft/onboarding/published/hidden/suspended/archived/deleted.
- Ability to hide/archive/delete venues; clean inactive venues.
SHOULD:
- Moderation flags and notes; bulk actions.

## Block 12 — Monetization & subscription
MUST:
- Subscription per venue with trial and per-venue price overrides.
- Card payment: external checkout link + webhook verification + idempotency.
- Optional Telegram Stars method (if implemented).
- Gating policy: `past_due` allows guest actions (orders/booking), while `suspended`, `suspended_by_platform`, and `canceled` block guest actions until status recovery.
- Platform owner can set/override prices without code changes.
SHOULD:
- Billing events log; self-serve invoice link generation.

## Block 13 — Analytics/KPI/events
MUST:
- Server-side events for: table_session_started, batch_created, batch_status_changed, booking_status_changed, subscription_status_changed.
- Correlation IDs (venue/table/session/order/batch/tab).
- Minimal PII; pseudonymize in analytics if exported.
SHOULD:
- Dashboards: platform + venue (owner) + shift (manager).

## Block 14 — Security/anti-fraud/audit
MUST:
- Validate Telegram WebApp initData server-side.
- Verify Telegram webhook secret token header.
- Tenant isolation + RBAC enforced server-side.
- Idempotency keys for create-order/create-batch/payment webhooks.
- Rate limiting for staff calls and spam orders.
- Audit logs for critical changes (roles, prices, stop-list, lifecycle).
SHOULD:
- Abuse detection heuristics and soft-lock.

## Block 15 — Growth (guest retention)
MUST:
- Favorites (venues), visit history, “repeat template” (applies on next visit/table context).
- Venue promotions/announcements.
- Post-visit review flow (rating + optional comment).
SHOULD:
- Flavor quiz/recommendations; referral sharing.

## Block 16 — Support (tickets + diagnostics + escalations)
MUST:
- “Report a problem” entry in guest + venue.
- Tickets with context (venue/table/order) and statuses new/in_progress/waiting_user/resolved.
- Venue handles operational tickets; platform handles technical/billing.
- Escalation if venue doesn’t respond in SLA.
- MVP foundation: booking-related guest↔venue conversation threads persist messages with venue, guest and booking context; staff chat is a notification mirror, not the only source of truth. Full platform support/ticket routing remains a later layer.
- M4B/M4C implemented booking-thread inbox lifecycle: guest `Сообщения` / `Мои обращения` is a list of threads, not one endless global chat. Thread cards show venue, context label (`Бронь №...`, `Заказ №...`, `Стол №...`, `Общий вопрос`, `Проблема`), status, last message preview/time and unread badge; active/resolved filters and resolve/reopen actions are smoke-passed for booking conversations.
- Venue `Сообщения` is scoped to the selected venue and shows guest display, context, status, last message and unread badge. Platform support later sees all support/ticket threads only through a backend-backed cockpit.
- Product model: one booking maps to one booking thread; one order issue maps to one order thread; one general question maps to one general thread; one technical/platform problem maps to one platform/support ticket thread. Do not merge all messages with one venue into one endless chat.
SHOULD:
- Diagnostic report from Mini App; CSAT.

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
