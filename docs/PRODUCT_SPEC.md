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

## Block 5 — Orders (one active order per table + batches)
MUST:
- One active order per table_session; multiple batches (dosa orders).
- Guest: browse menu, add to cart, submit batch with notes.
- Staff: receive and process batches with statuses.
- Staff assignment optional; prevent double-accept conflicts.
SHOULD:
- Out-of-stock handling via stop-list, not via last-minute rejects.

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

## Block 8 — Menu builder + photos + stop-list + top list
MUST:
- Categories, items, prices, descriptions, photos.
- Options: flavors/strength/etc (configurable per venue).
- Stop-list toggle per item (available true/false) with immediate effect.
- “Top list” / featured items pinning.
SHOULD:
- Bulk edit / import; optional PDF attachment as secondary (not primary).

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
- Gating: past_due/suspended affect guest actions (orders/booking) per policy.
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
