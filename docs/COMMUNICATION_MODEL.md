# Guest Communication Model

Дата актуализации: 2026-08-17.

Статус: **current product reference**. Этот документ является single source of truth для guest communication routing после smoke-tested Support/Tickets and Venue Chat MVP. Старые audit notes про `Сообщения`, booking support и staff-chat нужно сверять с этой моделью перед будущими задачами Codex. Role/scoping decisions for these flows are governed by `docs/SECURITY_RBAC_MATRIX.md`; Venue operational handling is governed by `docs/VENUE_OPERATIONS.md`; booking lifecycle details are governed by `docs/BOOKING_LIFECYCLE.md`; Telegram fallback and staff-chat behavior is governed by `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; validation strategy is governed by `docs/TESTING_QA_SMOKE_STRATEGY.md`; release/deploy operations are governed by `docs/DEPLOYMENT_RUNBOOK.md`.

## Core Rule

Guest communication is split into four different product scenarios. Do not merge them into one generic chat or support workspace.

| Type | Purpose | Entry points | Participants | Staff-chat policy |
| --- | --- | --- | --- | --- |
| `BOOKING_CHAT` | Переписка по конкретной брони. | Booking action `Открыть переписку`. | Guest + Venue Owner/Manager. | Not a support ticket and does not post to staff-chat. |
| `VENUE_CHAT` | Обычный вопрос заведению до визита или вне table context, включая ручный follow-up по низкой оценке после визита. | Catalog card `Задать вопрос`; venue detail `💬 Задать вопрос`; Venue Feedback `Связаться с гостем` for rating `1..3`. | Guest + Venue Owner/Manager. Staff denied. Platform does not see ordinary venue chats. | Does not post to staff-chat. |
| `SUPPORT_TICKET` | Проблема, жалоба, technical/platform support or status-tracked escalation. | Global `Помощь` -> `Сообщить о проблеме`; table-context secondary help/problem entry. | Guest sees own tickets; Venue Owner/Manager sees own venue tickets; Platform Owner sees support tickets; Staff denied. | Does not post to staff-chat. |
| `STAFF_CALL` | Быстрый live-вызов персонала за столом. | Table context `Вызвать персонал`. | Guest plus venue operational queue/staff according to existing staff-call permissions. | May use the existing operational staff queue/staff-chat behavior. |

## Thread Types

- `BOOKING_CHAT` is represented by exactly one booking conversation thread per global
  `bookings.id`. The server locks and reads the booking before deriving guest/venue participants;
  client-supplied tenant fields are not authority. Every Guest/Venue Mini App and Telegram booking
  message uses one booking-specific writer that re-checks the locked booking's Guest identity or
  Venue linkage plus stored thread metadata/status after the canonical booking and thread locks,
  then commits message plus thread
  status/timestamps in one transaction. A concurrently closed thread creates no message and stays
  closed. The generic message writer positively allows only `VENUE_CHAT` / `SUPPORT_TICKET` and
  rejects `BOOKING_THREAD` plus unknown/future types. Database uniqueness and persisted Telegram-message retry dedupe
  remain additional defenses. It is opened from booking flows and remains separate from support
  ticket queues. Booking statuses, lossless duplicate/read-state policy, hold/deadline, reminders
  and no-show/seated behavior are defined in `docs/BOOKING_LIFECYCLE.md`.
- Read-marker writes preserve the same parent-first dependency: booking chat uses
  `bookings -> support_threads -> support_thread_reads`; ordinary venue/support chat uses
  `support_threads -> support_thread_reads` after the caller owns the thread. No read-marker path
  acquires `support_thread_reads` before reaching back to its thread. This ordering changes neither
  participants nor the RBAC matrix.
- `VENUE_CHAT` is represented by a venue-scoped ordinary chat thread for one guest and one venue. Normal venue-question entrypoints reuse an existing ordinary guest+venue chat instead of creating duplicates.
- Low-feedback follow-up opens the exact active `VENUE_CHAT`: reuse an active thread and add fresh feedback context, or create a new active thread when the previous thread is closed/resolved. The system/context message `Отзыв после визита` may include rating, tags, comment and visit date. It is not a personal Owner message.
- `SUPPORT_TICKET` is represented by support-ticket threads with status, category, assignee scope and audit/status behavior.
- `STAFF_CALL` is not a support thread. It remains a staff-call/order operational flow governed by the table/session/order boundaries in `docs/ORDER_SESSION_TAB_CORE.md`. Guest status for the current guest and current `tableSessionId` includes `NEW`, `ACK`, `DONE` and terminal `CANCELLED`; `CANCELLED` uses the existing guest copy `Вызов отменён`.
- Venue Mode staff-call queues and staff-chat rules are detailed in `docs/VENUE_OPERATIONS.md` and `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`.

## Guest UX

- Global nav visible label `Чаты` means all guest chats with venues, including booking chats and ordinary venue chats.
- Global nav visible label `Помощь` means problems/support tickets and ticket status tracking.
- `Чаты` copy: `Здесь все ваши чаты с заведениями: вопросы, брони и другие переписки. Проблемы и жалобы находятся в разделе Помощь.`
- `Помощь` copy: `Здесь можно сообщить о проблеме и посмотреть статус обращений.`
- Catalog cards expose `Задать вопрос`, which opens or creates `VENUE_CHAT`.
- Venue detail exposes local `💬 Задать вопрос`, which opens or creates `VENUE_CHAT`.
- If Owner/Manager later sends a manual reply from a feedback follow-up thread, Guest receives it in `Чаты`, not `Помощь` / Support.
- Booking `Открыть переписку` loads the exact server-returned thread, including a resolved thread or
  one outside the current list window; it must never fall back to another active conversation.
- Booking-thread reconciliation uses a complete read-only bounded batch response for the requested
  server-authorized booking ids. A capped thread list, unfinished batch/page or failed read cannot
  prove that a thread is absent; the composer and first-thread action remain unavailable until the
  exact result is `NO_THREAD`, or until the exact existing thread and its messages load successfully.
  These safety reads bypass and prohibit HTTP storage so reload cannot reuse a stale pre-send state.
- Table context must not show a primary `Связаться с заведением` CTA. Live questions at the table use `Вызвать персонал`.
- Table-context support/help remains secondary for Mini App, QR, technical issues, unresolved complaints or order/service problems that should become tickets.

## Support Routing

- Technical/Mini App/bot/QR/access/payment platform issues may create platform-scoped support tickets without venue context.
- Order/service support outside table requires verified venue/order/table context. If no context is verified, the API rejects the request and the UI should ask the guest to choose a venue.
- Booking support outside table requires a verified booking or venue context and follows the routing rules in `docs/BOOKING_LIFECYCLE.md`.
- Venue detail can prefill venue context for venue-related support categories.
- Table context can attach verified `venue_id`, `table_id`, `table_session_id` and active order/batch context when available.
- A low post-visit rating does not create `SUPPORT_TICKET`. Owner/Manager may explicitly open a `VENUE_CHAT` follow-up from Venue Feedback; Staff remains denied.
- Venue can transfer a venue support ticket to Platform through `Передать платформе`.
- Platform can reply/close support tickets and see platform-assigned/transferred tickets. Platform cockpit and support-center boundaries are detailed in `docs/PLATFORM_COCKPIT.md`.

## RBAC

- Canonical role/scope matrix: `docs/SECURITY_RBAC_MATRIX.md`.
- Guest sees only own chats/tickets.
- Venue Owner/Manager sees only own venue booking chats, venue chats and venue-scoped support tickets.
- Venue users must not see `venue_id = NULL` platform-only tickets.
- Staff does not see `Помощь` / support tickets.
- Staff does not see ordinary `VENUE_CHAT` in the MVP.
- Platform Owner sees support tickets, including platform-only and transferred tickets.
- Platform Owner does not see ordinary `VENUE_CHAT` unless a future product policy explicitly changes that.
- Platform Owner does not list, open, reply to, assign or resolve ordinary `BOOKING_CHAT`; a venue
  cannot transfer a booking chat into the Platform support queue.
- Cross-venue access is rejected server-side; UI hiding is not a security boundary.
- Exact booking-thread lookup derives Guest/Venue scope from canonical bookings. Guest can request
  only own bookings; Venue Owner/Manager can request only bookings of the own venue; Staff remains
  denied. Missing/foreign ids return no partial thread facts, and lookup performs no thread,
  message, read-marker, audit or outbox writes.

## Notifications And Anti-Spam

- `VENUE_CHAT` creation/replies do not enqueue staff-chat notifications.
- `SUPPORT_TICKET` creation/replies do not enqueue staff-chat notifications.
- Feedback follow-up context does not enqueue staff-chat, create a support ticket or auto-send a personal message from Owner/Manager.
- Support/venue replies may notify the guest through the existing safe Telegram outbox when possible.
- Mini App booking replies carry an opaque, non-authoritative `clientMessageId`. The backend scopes
  it to canonical thread + source + actor and writes the message, thread state and server-derived
  Telegram outbox row on one JDBC connection before responding. Exact manual replay after an
  ambiguous network result returns the original message with no new outbox; a changed payload is a
  safe `409` and automatic resend remains out of scope. Venue replies notify the canonical booking
  guest; Guest replies receive only the private acknowledgement and never post the conversation to
  staff-chat.
- `STAFF_CALL` keeps the existing operational notification behavior.
- Telegram fallback and staff-chat allow/deny policy is canonical in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`: support tickets, venue chats and full booking-chat streams must not post to staff-chat.
- Guest support ticket creation, venue chat creation and guest support/venue chat messages are rate-limited through the existing guest rate-limit infrastructure.
- Retention/promo notifications are not covered by this communication MVP; they require `OPT_IN_NOTIFICATION`, frequency limits and unsubscribe as defined in `docs/GROWTH_RETENTION.md`.
- Analytics events for this model are defined in `docs/ANALYTICS_EVENTS.md`: `support_ticket_created`, `support_ticket_message_created`, `support_ticket_status_changed`, `support_ticket_transferred_to_platform`, `support_ticket_closed`, `support_ticket_reopened`, `venue_chat_created`, `venue_chat_message_created` and `booking_chat_message_created`. Message text must stay in domain message tables with RBAC, not analytics events.

## Open Follow-Ups

These are intentionally not part of the current MVP:

- SLA automation and auto-escalation worker.
- Macros, canned replies, CSAT and broad support analytics.
- File attachments/diagnostic reports unless a separate safe upload flow is implemented.
- Platform visibility into ordinary `VENUE_CHAT`.
- Individual support assignees beyond venue/platform scope.
