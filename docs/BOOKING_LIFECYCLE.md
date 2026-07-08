# Booking Lifecycle Model

Дата актуализации: 2026-07-08.

Статус: **current product reference / SPEC UPDATED**. Booking flows are implemented in several bounded slices: guest booking create/list/cancel/change acceptance foundations, Venue Mini App booking queue/lifecycle, hold settings, `arrival_deadline_at`, confirmed-only arrival terminal actions, state-aware staff-chat booking buttons, booking conversation threads and opt-in reminder code are documented as smoke-closed or code/test-backed in the current roadmap. The complete booking lifecycle is still **PARTIAL / needs verification** for rollout-gated reminders, full automation, preorder, visit-history integration and all analytics/audit event coverage.

## Core Rule

`BOOKING` is a pre-visit operational workflow, not a support ticket and not an ordinary venue chat. Booking lifecycle state belongs to booking domain tables and Venue Mode booking queues. `BOOKING_CHAT` is the conversation attached to one booking. Staff-chat may receive operational booking notifications according to venue policy, but it is not the source of truth and must not receive the full booking chat.

Canonical dependencies:
- `docs/COMMUNICATION_MODEL.md` for `BOOKING_CHAT`, `VENUE_CHAT`, `SUPPORT_TICKET` and `STAFF_CALL` separation.
- `docs/VENUE_OPERATIONS.md` for Venue Mode booking queue behavior.
- `docs/SECURITY_RBAC_MATRIX.md` for booking roles, scopes and callback trust boundaries.
- `docs/ORDER_SESSION_TAB_CORE.md` for `VISIT`, `TABLE_SESSION`, active order and seated-booking dependencies.
- `docs/GROWTH_RETENTION.md` for history, feedback, repeat, loyalty and preorder dependencies.
- `docs/ANALYTICS_EVENTS.md` for booking analytics events and KPI formulas.
- `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md` for Telegram booking entrypoints, staff-chat notification policy and callback security.
- `docs/TESTING_QA_SMOKE_STRATEGY.md` for validation, CI, staging and booking smoke strategy.
- `docs/DEPLOYMENT_RUNBOOK.md` for release/deploy, migration, logs and incident operations.

## Canonical Terms

| Term | Meaning | Status |
| --- | --- | --- |
| `BOOKING` | Guest request/reservation for a planned visit at one venue. It has `venue_id`, `guest_user_id`, `scheduled_at`, `party_size`, optional comment and status. | Current domain exists; lifecycle is partial by advanced automation. |
| `BOOKING_REQUEST` | Initial guest-created booking before venue confirmation. | Current status maps to `PENDING`. |
| `BOOKING_CHAT` | Conversation tied to exactly one `booking_id`, opened from booking action `Открыть переписку`. | Implemented/smoke-closed through booking conversation threads; not support. |
| `ARRIVAL_DEADLINE` | Local deadline until which the venue holds a confirmed booking. A changed/proposed-time booking may carry a deadline snapshot, but it is not arrival-ready until confirmed. Target default is `scheduled_at + hold_minutes`. | `arrival_deadline_at` is documented as persisted/smoked; automation semantics remain partial. |
| `HOLD_MINUTES` | Per-venue setting for how long a booking is held after scheduled time. | Current docs say Venue Mini App settings route exists. Target recommended default: 15 minutes, configurable to 15/30/60 and custom later. |
| `SEATED_VISIT` | Booking whose guest arrived and was marked seated. It can become a visit-history source later. | Current enum/status exists; visit-history integration remains partial/future. |

## Status State Machine

Current implementation uses these booking statuses:
- `PENDING`;
- `CONFIRMED`;
- `CHANGED`;
- `CANCELED`;
- `EXPIRED`;
- `NO_SHOW`;
- `SEATED`.

Target product labels:
- `pending`;
- `confirmed`;
- `proposed_time` / `changed`;
- `canceled_by_guest`;
- `canceled_by_venue`;
- `expired`;
- `no_show`;
- `seated`.

Compatibility rule:
- Runtime currently has one `CANCELED` status with cancellation metadata/reason where implemented. Product copy may distinguish guest vs venue cancellation, but persistence must not be described as split until implemented.
- Runtime currently uses `CHANGED` for venue-changed/proposed time. Target copy can call this `proposed_time`, but compatibility with `CHANGED` must stay in tests and UI mappings.
- `CHANGED` means venue-proposed time / waiting state. It is not an arrival-ready confirmed booking and must not allow `SEATED` or `NO_SHOW` until the booking is explicitly confirmed.

Target transitions:

| From | To | Actor | Notes |
| --- | --- | --- | --- |
| `pending` | `confirmed` | Venue Owner/Manager | Venue accepts the requested time. |
| `pending` | `proposed_time` / `changed` | Venue Owner/Manager | Venue proposes another time. |
| `pending` | `canceled_by_guest` | Guest | Runtime may store as `CANCELED`. |
| `pending` | `canceled_by_venue` | Venue Owner/Manager | Requires reason where implemented/target. Runtime may store as `CANCELED`. |
| `pending` | `expired` | Worker or policy | For unconfirmed requests after policy deadline. |
| `proposed_time` / `changed` | `confirmed` | Guest or Venue depending on flow | Guest acceptance should preserve venue-local time semantics. |
| `proposed_time` / `changed` | `canceled_by_guest` | Guest | Runtime may store as `CANCELED`. |
| `confirmed` | `canceled_by_guest` | Guest | Allowed until venue policy cutoff. |
| `confirmed` | `canceled_by_venue` | Venue Owner/Manager | Requires reason where implemented/target. |
| `confirmed` | `seated` | Venue Owner/Manager; Staff where allowed | Can become visit source. |
| `confirmed` | `no_show` | Venue Owner/Manager; Staff where allowed; worker future | Only after `arrival_deadline`. |
| `confirmed` | `expired` | Worker or policy | If booking expires before confirmation/arrival policy applies. |
| `seated` | immutable | System | Booking lifecycle stops; visit/order/table-session may continue separately. |

Runtime arrival guard:
- only `CONFIRMED` may transition to `SEATED` or `NO_SHOW`;
- `PENDING`, `CHANGED` and terminal statuses (`CANCELED`, `EXPIRED`, `NO_SHOW`, `SEATED`) must reject seat/no-show actions;
- denied arrival transitions must not create visits, booking history success markers or staff-chat state changes.

Actor rules:
- Guest creates own booking, cancels while allowed, accepts a proposed time where implemented, opens `BOOKING_CHAT` and reports a booking problem through support when needed.
- Venue Owner/Manager manages own-venue booking queue: confirm, propose/change time, cancel, mark seated/no-show and open `BOOKING_CHAT`.
- Staff access is operational only where allowed by RBAC: current docs say Staff can view bookings and mark `SEATED` / `NO_SHOW` only for `CONFIRMED` bookings; Staff cannot confirm, cancel, change/propose time, message the guest or change booking settings.
- Platform Owner does not manage ordinary bookings by default. Platform may intervene only through platform support/audit/lifecycle policy if a future task explicitly adds that path.

## Guest Booking UX

Entry points:
- catalog card or venue detail action `Забронировать`;
- account/profile booking list, such as `Мои брони`;
- booking history where implemented.

Form fields:
- date;
- time;
- party size;
- optional comment;
- contact through Telegram identity by default.

Guest-visible statuses:
- `ожидает подтверждения`;
- `подтверждена`;
- `предложено другое время`;
- `отменена`;
- `истекла`;
- `не пришёл` / no-show, only if product chooses to show it carefully;
- `гость пришёл` / seated, only if useful in account/history.

Guest actions:
- create booking from venue context;
- cancel while allowed;
- accept proposed time where implemented;
- choose another time where implemented;
- open booking chat through `Открыть переписку`;
- use Help/support for a dispute or problem.

Guest copy:
- Confirmed with hold: `Бронь держится до HH:mm`.
- Expired: `Время брони прошло. Создайте новую бронь или свяжитесь с заведением.`
- Proposed time: `Заведение предложило другое время: HH:mm.`

Current vs target:
- Current docs say Bot `/my` and Guest Mini App `Мои брони` show active/upcoming bookings with public `Бронь №...`, venue-local time, status, party size, comment and `Держим до HH:mm` when applicable.
- Real two-account Telegram runtime isolation for Guest booking list remains explicitly unverified in current role docs; keep it in regression.

## Venue Booking Queue

Venue Mode booking queue belongs to Venue operations.

Visibility:
- Owner/Manager: visible and manageable for own venue.
- Staff: visibility/actions follow RBAC; current docs allow booking view plus confirmed-only arrival/no-show.
- Platform: no ordinary booking operations workspace by default.

Queue filters:
- pending;
- confirmed;
- proposed time / changed;
- today;
- overdue / needs action;
- no-show / seated history.

Card fields:
- safe guest label;
- scheduled venue-local time;
- party size;
- guest comment where safe;
- status;
- arrival deadline;
- last booking-chat message preview where implemented;
- attendance/reminder response where implemented.

Actions:
- confirm;
- propose another time;
- cancel with reason;
- mark seated;
- mark no-show;
- open booking chat.

Action availability:
- `pending`: confirm, propose/change time, cancel, open booking chat where allowed; no `mark seated` / `mark no-show`.
- `confirmed`: mark seated, mark no-show, cancel, open booking chat where allowed.
- `changed` / `proposed_time`: cancel and open booking chat where allowed; no confirm-from-staff-chat and no arrival terminal actions until explicit confirmation.
- terminal statuses: no dangerous lifecycle action buttons.

Important actions:
- cancel with reason;
- no-show with optional reason;
- manual expire/no-show;
- seated.

These actions should write audit where implemented/target and must preserve venue-local timezone semantics.

## Hold Minutes And Arrival Deadline

Target:
- Venue setting `booking_hold_minutes`.
- Recommended launch default: 15 minutes.
- Supported values: 15/30/60 minutes; custom values can be future if the UI/backend validates them safely.
- `arrival_deadline = scheduled_at + hold_minutes` in venue local time.
- Existing bookings should keep a snapshot deadline/hold where the runtime stores it; changing the setting should affect future bookings by default.

Rules:
- Use venue timezone, not server default, for displayed schedule and deadline.
- UI must show exact local time.
- Confirmed bookings remain arrival-actionable until `arrival_deadline` or manual terminal action.
- Changed/proposed-time bookings may remain visible as waiting state, but they are not arrival-actionable until confirmed.
- After deadline, either a worker marks no-show/expired or Venue manually marks no-show depending on implementation state.

Current vs target:
- Current docs say booking hold settings route uses `venue_booking_settings.hold_minutes`, and `arrival_deadline_at` is persisted/displayed.
- Automatic no-show/expiry policy must remain marked partial/future unless the specific worker/action is verified for the release.

## Reminders

Target reminder:
- one transactional pre-visit reminder per eligible booking;
- suggested timing: venue-local 24h preferred target with 3h fallback where applicable, or a simpler same-day rule for MVP;
- buttons: `Подтвердить`, `Отменить`, `Перенести`;
- store scheduled/sent/queued/canceled state to prevent duplicates;
- respect Telegram constraints: the bot can message only users who started it;
- respect rate limits and quiet hours;
- guest attendance confirmation does not change venue-controlled booking status by itself.

Current vs target:
- Current roadmap says M7c reminder code/tests and one controlled Telegram staging smoke passed, but runtime remains disabled by default and requires explicit opt-in.
- Outbox enqueue means `QUEUED`, not Telegram-delivered.
- Broader rollout, management UI and long-term reminder analytics are partial/future.

## Booking Chat

Rules:
- `BOOKING_CHAT` is tied to one `booking_id`.
- Venue can message guest about time, comment, arrival and ordinary booking coordination.
- Guest can reply from Bot/Mini App where implemented.
- Booking `Открыть переписку` opens `BOOKING_CHAT` / `Чаты`, not `SUPPORT_TICKET`.
- Booking chat has its own active/resolved conversation status where implemented; resolving a chat must not confirm, cancel, seat, no-show or otherwise mutate the booking.
- Guest booking replies persist to `BOOKING_CHAT`; booking chat messages do not post to staff-chat.
- If a booking problem becomes a dispute/complaint, the guest uses Help -> `SUPPORT_TICKET` category `Бронь`.
- Booking support outside table requires verified booking or venue context.

Current vs target:
- Current docs say booking conversation threads are implemented through `support_threads` / `support_messages`, with Guest Bot replies, Guest Mini App replies and Venue Mini App replies sharing one persisted thread.
- No DB-level duplicate/race protection for concurrent booking thread creation is documented as complete; keep as future if needed.

## Staff-Chat Notification Policy

Staff-chat policy is canonical in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Staff-chat may receive operational booking notifications according to venue policy:
- new booking request;
- confirmed/changed/canceled;
- arrival soon / overdue when future policy enables it;
- reminder attendance updates where current implementation does so safely.

State-aware booking notification buttons:
- `PENDING`: confirm, cancel, message; no arrival buttons.
- `CONFIRMED`: seated, no-show, cancel, message.
- `CHANGED`: cancel, message; no confirm and no arrival buttons.
- Terminal statuses: no dangerous action buttons.

Staff-chat must not:
- become the booking source of truth;
- receive full booking chat messages;
- receive support-ticket messages about booking problems;
- expose raw PII, raw callback payloads or unverified ids.

Actions from staff-chat callbacks must re-load booking state, verify server-side role and venue scope and must use opaque callback ids/tokens. Arrival callbacks are valid only while the booking is still `CONFIRMED`; stale state answers should direct the operator back to Venue Mode instead of mutating the booking.

## Visit, History And Growth Dependencies

- `CONFIRMED -> SEATED` creates exactly one `BOOKING_SEATED` visit where visit foundation is implemented.
- `CONFIRMED -> NO_SHOW` must not create a visit.
- Denied `PENDING` / `CHANGED` / terminal arrival transitions must not create visits.
- Confirmed but not seated should not count as a completed visit.
- Feedback after visit depends on `SEATED` or a closed order/table-session signal.
- Preorder for regular guests depends on reliable booking lifecycle and `visit_count`.
- Repeat/history/loyalty must not treat canceled, expired or no-show bookings as successful visits.

## Analytics And Events

Canonical analytics rules: `docs/ANALYTICS_EVENTS.md`.

Target events:
- `booking_created`;
- `booking_confirmed`;
- `booking_time_proposed`;
- `booking_guest_accepted_time`;
- `booking_guest_rejected_time`;
- `booking_canceled_by_guest`;
- `booking_canceled_by_venue`;
- `booking_expired`;
- `booking_no_show`;
- `booking_seated`;
- `booking_reminder_scheduled`;
- `booking_reminder_sent`;
- `booking_reminder_action_clicked`;
- `booking_chat_message_created`.

KPI formulas:
- booking submit rate;
- booking confirm rate;
- booking cancel rate;
- no-show rate;
- seated rate;
- time to confirm;
- reminder confirmation rate;
- booking-to-visit conversion.

Privacy:
- Analytics payloads must not include raw message text, raw Telegram initData, payment secrets, phone/email or unrelated PII.
- Booking chat text belongs in domain message tables with RBAC, not analytics events.

## RBAC And Security

- Guest sees only own bookings and own booking chats.
- Venue Owner/Manager sees only bookings for their own venue.
- Staff booking access is operational and limited by RBAC; current docs allow view plus confirmed-only seated/no-show.
- Platform Owner does not manage ordinary bookings by default.
- Venue users cannot access another venue's bookings.
- `booking_id` in callbacks must be an opaque/short pointer; callback handling must resolve and authorize server-side.
- No raw PII in analytics or staff-chat notifications.
- Reasons/comments visibility must be controlled by audience.
- Cancellation/no-show/seated actions should be audited where implemented/target.

## Current Implementation Vs Target

| Block | Current implementation from docs/code scan | Target product model | Gap / future note |
| --- | --- | --- | --- |
| Guest booking create/list/update/cancel | Guest booking MVP exists; Bot `/my` and Guest Mini App `Мои брони` parity is documented. | Guest owns create/list/cancel/proposed-time response for own bookings. | Real two-account Telegram isolation smoke remains explicitly unverified. |
| Venue booking status actions | Venue Mini App queue/lifecycle is smoke-closed; Owner/Manager confirm/change/cancel/message/settings, Staff confirmed-only arrival/no-show split exists. | Full venue queue with filters, reasons, audit and timezone-safe actions. | Keep route/RBAC, confirmed-only arrival guard and cross-venue regression; audit completeness needs verification. |
| Mini App guest booking screen | `Мои брони` active/upcoming list with public label/time/deadline is documented. | Account booking list/history with safe status labels and actions. | Full history/retention integration remains future. |
| Venue Mini App booking queue | Implemented/smoked for M3/M7a/M7b/M7c slices. | Source-of-truth operational queue under Venue Mode. | Overdue automation, broader reminder UI and preorder remain partial/future. |
| Telegram `/my` booking list | Implemented and compared visually with Guest Mini App for public label/time/deadline. | Same identity/status/deadline semantics across Bot and Mini App. | Keep runtime regression. |
| Booking chat | Implemented as persisted booking threads; `Открыть переписку` opens messages. | `BOOKING_CHAT` is separate from support and staff-chat. | Race/unique constraints and Bot full inbox remain future where needed. |
| Booking lifecycle statuses | Runtime statuses include `PENDING`, `CONFIRMED`, `CHANGED`, `CANCELED`, `EXPIRED`, `NO_SHOW`, `SEATED`. | Product copy distinguishes proposed time and cancellation actor. | Split `canceled_by_guest` / `canceled_by_venue` only when runtime supports it. |
| Hold minutes / arrival deadline | `venue_booking_settings.hold_minutes` and `arrival_deadline_at` are documented as implemented/smoked. | Venue setting with deadline snapshot and venue-local display. | Automatic policy edge cases need verification. |
| Reminders worker | M7c is code/test-backed and one controlled staging smoke passed; runtime disabled by default. | Rollout-gated transactional reminders with dedupe, quiet hours and safe actions. | Enable only with explicit rollout/smoke; management UI future. |
| No-show / seated | Runtime statuses, confirmed-only arrival guard and `BOOKING_SEATED` visit creation are documented. | Seated can feed visit; no-show must not. | Broader visit history and feedback integration remain partial/future. |
| Analytics events | Analytics spec says booking events need verification. | Emit full booking lifecycle/reminder/chat facts. | Event emission/payload safety is partial/future unless tests prove it. |
| Staff-chat booking notifications | State-aware operational booking notifications are allowed by policy and smoke-closed for current paths. | Staff-chat is radar only; no booking chat/support ticket spam. | Keep per-venue real Telegram group regression. |
| Support routing for booking problems | `SUPPORT_TICKET` category `Бронь` requires verified booking/venue context. | Booking problems escalate through support, not booking chat lifecycle. | Keep context verification and staff-chat denial in support regression. |

## Roadmap Status

- Booking lifecycle spec: `UPDATED`.
- Booking implementation: `PARTIAL / CLOSED by bounded MVP slices`; queue, hold settings, guest list parity and booking chat are smoke-closed in current docs, while complete automation and integrations remain partial.
- Booking Mini App management: `CLOSED for current MVP`, with future filters/history/polish.
- Hold minutes / arrival deadline: `CLOSED for current MVP`, with automatic policy edge cases needing verification.
- Reminders: `PARTIAL / rollout-gated`; implemented/test-backed and one controlled staging smoke passed, but disabled by default.
- No-show/seated: `CLOSED for confirmed-only operational guard`; broader visit/history integration remains future.
- Booking chat: `MVP / CLOSED for current smoke paths`.
- Visit/history dependencies: blocked until booking lifecycle plus order/session/tab close signals are reliable across real traffic.

## Smoke Checklist

1. Guest creates booking from venue detail.
2. Guest sees booking in `Мои брони` / profile/history where implemented.
3. Venue Owner/Manager sees booking in Venue Mode queue.
4. Venue confirms booking.
5. Guest sees confirmed status.
6. Venue proposes another time.
7. Guest accepts proposed time where implemented.
8. Guest cancels booking.
9. Venue cancels booking with reason.
10. Confirmed booking remains active until `arrival_deadline`.
11. Pending booking cards and staff-chat messages do not show `Гость пришёл` / `Не пришёл`.
12. Changed/proposed-time booking cards and staff-chat messages do not show arrival buttons.
13. Venue marks confirmed guest seated.
14. Venue marks confirmed booking no-show after deadline/manual policy.
15. No-show does not create a visit.
16. Seated booking creates or links exactly one `BOOKING_SEATED` visit where visit foundation exists.
17. Booking `Открыть переписку` opens `BOOKING_CHAT` / `Чаты`, not Support.
18. Booking support issue requires verified booking or venue context.
19. Booking chat/support messages do not post to staff-chat.
20. Venue users cannot access another venue booking.
21. Staff access matches final RBAC policy: view + confirmed-only seated/no-show unless changed intentionally.
22. Booking analytics/audit events exist where implemented, and payloads contain no raw message text/initData/secrets.
