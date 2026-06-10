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
- venue: id, name, address, geo(optional), description, hours, status(lifecycle), owner_user_id, settings
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
SHOULD:
- Favorites and history (Block 15) integrate with catalog.
- Returning guest can safely restore an active table context while their table session/tab/order is still open, without rescanning QR, and the context resets after bill close.

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
- Venue owner/admin can set: description, hours, contact, address.
- Create tables; assign staff roles; configure visible modules.
- Connect staff group chat (optional) by providing chat_id / invite flow.
SHOULD:
- Onboarding checklist + readiness score.
- Weekly working hours and concrete-date exceptions are displayed as distinct concepts: base weekday schedule, enabled/closed day state, and override state must not look like duplicate/conflicting controls.

## Block 8 — Menu builder + photos + stop-list + top list
MUST:
- Categories, items, prices, descriptions, photos.
- Options: flavors/strength/etc (configurable per venue).
- Stop-list toggle per item (available true/false) with immediate effect.
- “Top list” / featured items pinning.
SHOULD:
- Bulk edit / import; optional PDF attachment as secondary (not primary).
- Informational `Фото-меню` can stay as one media list in simple mode, but may support optional owner-defined subsections in advanced mode. This is separate from the structured order menu.

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
- Paid extension is not an ordinary menu item/cart item/order batch item. In the Mini App it may appear as a service action in the guest ordering section list, separate from catalog categories and cart logic.
- Guest requests extension from active table context, near/after configured closing time or whenever the venue exposes the action.
- Venue staff/manager confirms operationally; the guest cannot self-add the charge without confirmation.
- Confirmation adds a fixed, preconfigured charge to the current bill and extends the active table/session ordering window by the configured duration, default 60 minutes.
- Extension can be repeated while the bill/session remains active.

Guest flow:
1. In table context, show service action `Продление работы заведения` in the ordering section list only when venue extension settings are enabled, price is configured, and the table session/order is active.
2. Inside the service action, show button `Продлить на 1 час` and copy: `Продление на 1 час — 3 000 ₽. Персонал подтвердит возможность продления.`
3. Guest sends request with optional comment; UI state becomes `Ожидает подтверждения`.
4. If approved, guest sees `Продление подтверждено до HH:mm. Сумма добавлена в счёт.`
5. If rejected or timed out, guest sees a clear non-mutating status and the bill is unchanged.

Venue flow:
1. Staff/manager sees a pending extension request with venue/table/session/order, guest display name, price, current allowed-until time and proposed new allowed-until time.
2. Buttons: `✅ Продлить на 1 час` and `❌ Отказать`.
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

Implementation slices:
1. Data/API slice: settings, request, service charge and session extension persistence with PostgreSQL/H2 migrations and backend tests.
2. Guest/Venue UI slice: Mini App request/approve/reject screens plus staff-chat notification/update.
3. Regression slice: bill snapshot parity, repeated extension, role denial, timezone and closed-session tests.
4. Bot parity follow-up: owner/manager bot settings for extension enabled/duration/price and guest bot table menu service entry `Продление работы заведения` when enabled.

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
