# Codex Project Instructions — Hookah Platform (Telegram Bot + Mini App)

## Scope
This repo implements a multi-tenant Telegram platform for hookah lounges (“venues”) with:
- Guest Mode (catalog, booking, in-venue QR ordering)
- Venue Mode (admin panel for venue owners/managers/staff)
- Platform Mode (super-admin panel to onboard/manage venues and subscriptions)
- Fallback chat-bot flow when Mini App fails to load

## Golden rules (must follow)
1) Multi-tenant isolation: Every read/write MUST be scoped to venue_id and authorized. No cross-venue data leakage.
2) RBAC: Enforce roles server-side (platform_owner/platform_admin, venue_owner/venue_admin/venue_manager, staff_waiter/staff_hookah, guest).
3) Telegram WebApp: NEVER trust client input. Validate Telegram WebApp initData server-side for auth; do not rely on initDataUnsafe.
4) Telegram Webhook: verify secret token header; handle updates idempotently; ACK fast, process async.
5) Orders:
   - Table QR establishes table context.
   - One active “order” per table_session; multiple “batches” (dosa orders) inside it.
   - Staff receives orders in Venue Mode; optional duplicate messages to a venue-owned staff group chat.
6) Split bill:
   - Default: personal tab per user in a table_session
   - Shared tab requires explicit join/consent; prevent ordering on someone else’s tab.
7) Stop-list: menu items can be toggled available/unavailable instantly; unavailable items must not appear in guest menu.
8) Billing: subscription per venue with trial and per-venue price overrides; card payments via external checkout+webhook; Telegram Stars optional; gating on past_due/suspended.
9) Observability: structured logs, correlation IDs, minimal PII; metrics for webhook lag, queues, outbound delivery success.
10) Reliability: queue-based processing for webhooks and outbound messages; rate limiting; graceful degradation to fallback bot.

## Where the product spec lives
- docs/PRODUCT_SPEC.md is the product source-of-truth for required blocks 1–18.
When reviewing or modifying code, always compare implementation against that spec.

## How to work (Codex)
- Prefer read-only mode for audits.
- For any finding: include file paths and exact function/endpoint names; avoid vague statements.
- When proposing fixes: list minimal patch + how to verify (tests/commands).
