# Booking Lifecycle Model

Дата актуализации: 2026-08-18.

Статус: **current product reference / SPEC UPDATED**. Booking flows are implemented in several bounded slices: guest booking create/list/cancel/change acceptance foundations, Venue Mini App booking queue/lifecycle, hold settings, `arrival_deadline_at`, confirmed-only arrival terminal actions, state-aware staff-chat booking buttons, booking conversation threads, booking `SEATED` -> Guest History integration and opt-in reminder code are documented as smoke-closed or code/test-backed in the current roadmap. The complete booking lifecycle is still **PARTIAL / needs verification** for rollout-gated reminders, full automation, preorder, feedback and all analytics/audit event coverage.

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
| `SEATED_VISIT` | Booking whose guest arrived and was marked seated. It can become a Guest History source. | Current enum/status exists; booking `SEATED` -> Guest History integration is staging-smoked. |

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
- `bookings.display_number` is authoritative and unique within one venue service day; it may
  legitimately restart on another service day. Every Guest/Venue booking and booking-conversation
  surface therefore uses `Бронь №<display_number> · <venue-local dd.MM.yyyy, HH:mm>`.
  Missing/invalid numbers fall back to stable `Бронь #<booking_id>`, never a list index or
  generic `Бронь 1` placeholder. Sort, filter and reload do not change the label.
- Bot `/my` and Guest Mini App `Мои брони` additionally show status, party size,
  comment and `Держим до HH:mm` when applicable.
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
- the same authoritative booking display label used by Guest, conversations and Telegram;
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
- `BOOKING_CHAT` is tied to exactly one global `bookings.id`; `booking_id`, not
  `(venue_id, booking_id)`, is its canonical identity.
- At most one non-null `BOOKING_THREAD` exists for a booking. The backend locks the canonical
  booking row, derives the venue and guest from it, then resolves the thread and writes the first
  or later message in one transaction.
- Every Guest/Venue Mini App and Telegram message write goes through the booking-specific
  repository writer. That writer reloads the thread, derives `booking_id`, locks the canonical
  booking, locks and reloads the canonical thread, re-checks the locked booking's Guest identity or
  Venue linkage, stored thread ownership and locked conversation status, then inserts the message and updates status/
  timestamps in the same transaction. A concurrently `CLOSED` thread is rejected before message
  facts and is never reopened. The generic message writer positively allows only `VENUE_CHAT` and
  `SUPPORT_TICKET`; `BOOKING_THREAD` and unknown/future types fail closed.
- Venue can message guest about time, comment, arrival and ordinary booking coordination.
- Guest can open/reuse the thread from the own-booking Mini App action and reply from Bot/Mini App;
  Venue Owner/Manager can reply only for the own venue. Staff and Platform do not receive ordinary
  booking-chat access.
- Booking `Открыть переписку` opens `BOOKING_CHAT` / `Чаты`, not `SUPPORT_TICKET`.
- Booking chat has its own active/resolved conversation status where implemented; resolving a chat must not confirm, cancel, seat, no-show or otherwise mutate the booking.
- Guest booking replies persist to `BOOKING_CHAT`. The full message stream never posts to
  staff-chat; a newly committed Guest message may create one fact-only alert in the canonical
  venue's existing linked staff-chat, with the authoritative booking label, safe Guest label and
  exact conversation link but without message text.
- If a booking problem becomes a dispute/complaint, the guest uses Help -> `SUPPORT_TICKET` category `Бронь`.
- Booking support outside table requires verified booking or venue context.
- Telegram retries use the persisted inbound message id for per-thread message idempotency; guest
  notification and acknowledgements use stable outbox dedupe keys.
- Every Mini App booking reply carries an opaque `clientMessageId` generated for that pending send.
  It contains no actor, booking, thread or message data and is not authority. The client keeps it
  only for an unchanged manual retry after an ambiguous network result; editing the draft, a
  successful response, or changing account/venue/thread/booking invalidates it. There is no
  automatic resend loop.
- Each active booking context exposes an explicit reconciliation state: `LOADING / UNKNOWN`,
  `READY_NO_THREAD`, `READY_WITH_THREAD` or `ERROR`. Guest/Venue dedicated booking cards and generic
  booking-thread screens do not expose the composer, send action or `Написать гостю` until an
  exact authoritative lookup succeeds for that booking. The read-only bounded batch endpoints
  `GET /api/guest/support/booking-threads?bookingIds=...` and
  `GET /api/venue/{venueId}/support/booking-threads?bookingIds=...` return one explicit
  `WITH_THREAD` or `NO_THREAD` result for every requested, server-authorized id (maximum 100 ids per
  request). Clients validate the full response and deterministically chunk larger authoritative
  booking sets; reconciliation completes only after every chunk succeeds. Absence from a capped
  thread list, an incomplete page/batch or a failed request never means `READY_NO_THREAD`. Existing
  exact/list/detail safety reads are `no-store` on both HTTP response and Mini App fetch so a stale
  pre-send result cannot survive a lost-response reload. Existing threads additionally require a
  successful message fetch. A failed lookup/message read preserves the draft, shows the screen-scoped
  `Обновить переписку` action and performs reads only; it never creates a fresh key or resends.

Current vs target:
- Booking conversation threads are implemented through `support_threads` / `support_messages`, with
  Guest Bot, Guest Mini App, Venue Telegram and Venue Mini App production writers sharing one
  persisted thread and transaction-bound message path.
- PostgreSQL V124 enforces a partial unique `booking_id` constraint; H2 V125 uses an equivalent
  nullable generated key. Safe legacy duplicates merge without deleting messages. Automatic merge
  is allowed only when the group has no read markers, or every represented user has exactly one
  marker on every duplicate thread and all of that user's `last_read_at` values are identical.
  Partial coverage or different timestamps stops the migration before domain mutation; the
  migration never selects `MIN`/`MAX` as an approximation for lost read evidence.
- The survivor is `MIN(thread.id)`. It keeps that id and receives exact group
  `MIN(created_at)`, `MAX(updated_at)` and `MAX(last_message_at)` with all-null preservation;
  statuses must already agree, while its other stored metadata stays unchanged. Message facts and
  known audit references are reparented without losing evidence. The production producer schema is
  `entity_type=support_ticket`, action `SUPPORT_TICKET_STATUS_CHANGED`, with exact top-level
  `ticketId` as its only thread reference. For each affected row, payload must be an object and its
  integer `ticketId` must equal the pre-migration `entity_id`; both are remapped to the survivor.
  Action/entity, actor, target, timestamp and every other payload value remain unchanged. Valid JSON
  keys are decoded, NFKC-normalized, lowercased with locale-independent semantics and compared after
  `_`, `-`, `.`, spaces are removed. Any additional recursive key containing
  `thread`/`ticket`/`conversation` plus `id(s)`/`ref(s)` fails before mutation; no unknown key is
  silently remapped. `conversationStatus` remains unrelated negative evidence. Missing, non-integer,
  mismatched or aliased thread references and unknown affected audit shapes stop before domain mutation,
  identical read rows collapse to one exact marker, duplicate rows are deleted only after remaps,
  and the unique invariant is installed last. Null/missing booking, canonical venue/guest mismatch,
  conflicting statuses or an unknown/unhandled reference family also stop before domain mutation
  under `STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION`.
- PostgreSQL V124 and the read-only preflight derive top-level `ticketId` from decoded JSON object
  entries. H2 V125 calls deterministic aliases backed by the duplicate-aware
  `BookingAuditReferencePolicy` semantic parser for cardinality, exact integer extraction and
  top-level remap. The H2 decision therefore does not depend on serialized key order, whitespace,
  minification or safe extra fields, and does not use a regex to extract or replace `ticketId`.
  Plain plus Unicode-escaped duplicate keys still fail before domain mutation.
- PostgreSQL V124 takes bounded `EXCLUSIVE` locks before its first guard, in the exact order
  `bookings`, `support_threads`, `support_messages`, `support_thread_reads`, `audit_log`. This blocks
  the confirmed booking/thread/message/read/audit reference writers while preserving ordinary
  reads. The other 15 non-audit durable JSON/payload/snapshot pairs are negative-evidence scans,
  not declared references or extra lock targets. Traffic drain and stopping old writers therefore
  remain mandatory for rollout; the locks are not permission to run mixed binaries.
- Booking read-marker transactions acquire parents first:
  `bookings -> support_threads -> support_thread_reads`. Non-booking callers that already own or
  inserted the thread use `support_threads -> support_thread_reads`; no path locks a read marker and
  then reaches back to the thread or booking. Authorization results and Guest/Venue/Platform role
  boundaries are unchanged by this ordering.
- `support_thread_reads.last_read_message_id` is the sole current unread authority. After the
  canonical parent locks, an accepted exact open snapshots `MAX(support_messages.id)`, advances the
  actor/thread cursor monotonically and returns the marker plus detail/messages in the same
  transaction. A detail failure rolls the marker back. Production unread SQL uses the null-safe
  `author_user_id IS DISTINCT FROM actor_user_id` contract. Unread is a foreign-authored message with a
  NULL cursor or `message.id > last_read_message_id`; equal timestamps do not collapse message
  order and own messages never count. Every user-visible message whose `author_user_id` is NULL is
  a system message and is foreign to every actor. It participates in per-thread/card unread and,
  for `BOOKING_THREAD` or `VENUE_CHAT`, the aggregate Venue conversation badge; the normal exact
  open clears it without expanding thread or RBAC authority. `last_read_at` remains wall-clock
  metadata only.
- PostgreSQL V126/H2 V127 add nullable `BIGINT last_read_message_id` and the exact
  `support_messages(thread_id, id)` index. Existing marker rows deliberately retain NULL cursors;
  there is no default, backfill, foreign key or destructive rewrite.
- Mini App idempotency is persisted as
  `(thread_id, source, author_user_id, client_message_id)`. Replaying the same key with the exact
  normalized stored text returns the original message without touching thread state or outbox;
  reusing it for different text fails with
  `BOOKING_MESSAGE_IDEMPOTENCY_PAYLOAD_MISMATCH` and zero writes. A new key creates a new message.
  Replay resolution occurs after canonical authorization/locks but before mutable closed-status
  rejection, so a response lost just before a later close is still recoverable; a new key on a
  closed thread remains denied. Telegram keeps its separate `telegram_message_id` contract.
- For a new Mini App booking message, authoritative booking/thread participant checks after the
  existing surface authentication/RBAC prerequisite, replay lookup,
  message insert, thread update and server-derived Telegram outbox insert use one JDBC connection
  and one commit. Venue replies notify the canonical guest; Guest replies retain the existing safe
  private acknowledgement and, only for the canonical venue with enabled linked staff-chat, enqueue
  one bounded fact-only staff alert. The message text is not copied into that payload. An outbox
  failure rolls everything back; replay after a lost committed HTTP response returns the original
  message and outbox rows without duplicates. Missing/disabled staff-chat safely omits the venue
  alert and records no venue-alert success; the separate private Guest acknowledgement is unchanged.
- The existing suspend `TelegramOutboxRepository.enqueue` remains the legacy key-only contract:
  a repeated `dedupeKey` is an idempotent no-op even if a caller regenerated mutable envelope bytes.
  The connection-aware booking-only method is separate and strict: equal
  `chat_id + method + canonical payload_json` replays, while any mismatch aborts the booking message
  transaction. Reminder, billing and ordinary Telegram callers do not inherit strict collisions.
- The implementation and regression matrix includes H2/PostgreSQL migration,
  repository race/idempotency/rollback, parent-first read cursor, RBAC route, Telegram and browser
  coverage. The current cursor gate requires repository `21`, real PostgreSQL read-race `6` and
  H2/PostgreSQL cursor-migration wrappers `4/4`; label parity requires Kotlin `2` plus the shared
  JSON fixture's dedicated TypeScript/Playwright cases; notification coverage requires real
  PostgreSQL booking idempotency `19`, notifier unit `8` and legacy outbox `13`. Guest surface
  guards require exact route XML floors `BookingConversationRoutesTest=10` and
  `SupportTicketRoutesTest=15` plus the structured Playwright floor `216`. Historical V124/V125
  semantic/recursive and message-metadata gates remain mandatory regression evidence. Independent
  review is still required; green Actions, cursor migration rollout and staging/two-account
  real-client smoke remain later release gates.
- The seven findings in the preceding integrity fix have local fixes and coverage, but each remains
  `LOCAL_FIX_REVIEW_REQUIRED` and the epic
  remains `REVIEW REQUIRED BEFORE COMMIT`. `BOOKING-DEDUP-AUDIT-REF-001` is not treated as a final
  complete-reference verdict; recursive coverage is the separate
  `BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001` fix awaiting review.
- The five cursor/parity/notifier/documentation findings in the current UX/discoverability closure
  likewise remain `LOCAL_FIX_REVIEW_REQUIRED`; local validation does not mark the epic or release
  done.
- Guest exact detail/open is additionally type-clamped by a fixed server-owned surface:
  `CONVERSATIONS` is exactly `BOOKING_THREAD` + `VENUE_CHAT`, and `SUPPORT` is exactly
  `SUPPORT_TICKET`. Ordinary Guest and confirmed Platform Guest-context adapters pass that bounded
  contract into one repository-owned transaction. After fact-free preauthorization, it takes the
  canonical booking lock when applicable and `support_threads FOR UPDATE`, rechecks Guest ownership
  and the locked type, then and only then snapshots `MAX(message.id)`, advances the cursor and reads
  the detail. Wrong-surface opens disclose no message facts and write no marker, audit or outbox.
- `BOOKING-SAVEPOINT-COLLISION-001` stays `OPEN`: unique-conflict recovery is defensive-only behind
  the canonical booking-row lock and must not be removed without a separate concurrency review.

### PostgreSQL V126 Controlled Mixed-Version Rollout Boundary

This is an operational contract for a future release, not evidence that green Actions, a staging
migration, deploy or smoke has happened.

This current-section wording addresses only the two remaining commit-blocking documentation
findings `BOOKING-UNREAD-ROLLOUT-PREFLIGHT-HEAD-001` and
`BOOKING-UNREAD-ROLLOUT-MANUAL-DB-CLEANUP-001`. The already approved label smoke remains unchanged;
this wording does not raise the epic or release status.

PostgreSQL V126 is additive. It adds nullable `BIGINT support_thread_reads.last_read_message_id`
with no default, no backfill and no destructive rewrite;
`last_read_at` and primary key `(thread_id, user_id)` remain intact. The old binary is
schema-compatible but updates only `last_read_at`. The new binary treats a NULL cursor as every
foreign-authored message unread, including a user-visible system message with
`author_user_id = NULL`. No message or marker data is lost, but badges can repeat or be inaccurate
during mixed-version operation. H2 V127 is the additive dialect-parity/test migration: it adds the
same nullable `BIGINT last_read_message_id` with no default, no backfill and no destructive rewrite,
preserves `last_read_at` and primary key `(thread_id, user_id)`, and applies only to H2/local/test.
Staging PostgreSQL applies PostgreSQL V126, never H2 V127.

`EXPECTED_RELEASE_SHA` comes only from the exact commit SHA shown by a fully green GitHub Actions
run after commit and push. The operator copies the full 40-character lowercase SHA from that run
and passes it explicitly; GitHub CLI is optional because the value may be copied from the Actions
UI. Validate it with `^[0-9a-f]{40}$`, fetch `origin`, and require
`origin/main = EXPECTED_RELEASE_SHA`. Never derive the expected value from local `HEAD` or from the
checkout being verified; `EXPECTED_RELEASE_SHA="$(git rev-parse HEAD)"` and equivalent contracts are
forbidden.

Use the exact executable procedure in `docs/DEPLOYMENT_RUNBOOK.md` to create a separate detached
`RELEASE_WORKTREE` at `EXPECTED_RELEASE_SHA`. Its `HEAD` must equal the expected SHA and
`git status --porcelain --untracked-files=all` must be empty. A dirty release worktree, `scripts/dev/`
or any other untracked file is a STOP. The development worktree is not used for preflight, build or
deploy; mutable branches and mutable image tags are not release identity.

Build the backend image only from that detached exact-SHA worktree. Its tag contains the full
`EXPECTED_RELEASE_SHA`, and its immutable image ID is recorded before cutover. If the image really
contains `org.opencontainers.image.revision`, it must equal `EXPECTED_RELEASE_SHA`; if the build
does not create that label, do not invent it. Exact-worktree provenance, the full-SHA tag and the
recorded immutable image ID then remain mandatory. A mutable `staging` tag alone is insufficient,
and the deploy command must use this exact prepared image with an explicit
`DEPLOY_SHA = EXPECTED_RELEASE_SHA`.

The release equality is unconditional:
`green Actions SHA = origin/main = release worktree HEAD = prepared backend image release SHA =
DEPLOY_SHA`. Any mismatch is a STOP.

The final preflight must be extracted at operation time from
`$RELEASE_WORKTREE/docs/DEPLOYMENT_RUNBOOK.md` by the documented Python standard-library extractor.
It requires exactly one `BOOKING_UNREAD_PREFLIGHT_BEGIN` and exactly one
`BOOKING_UNREAD_PREFLIGHT_END`, rejects missing, duplicate, reversed, empty or ambiguous ranges,
selects the current marker-bounded block rather than a first similar fence or historical section,
and writes the exact nonempty body to a timestamped temporary file. The current fenced artifact is
a `bash` wrapper around `psql`, not pure SQL, so the exact extracted file is executed with `bash`,
not `psql -f`. Its SHA-256 is recorded immediately after extraction, and the artifact is never
edited before execution.

SQL or shell text from a ChatGPT message, terminal history, clipboard history, a previously saved
SQL/shell file, cached snippet, another branch, another commit, a historical runbook section or any
other stale copy is forbidden. Manual editing of the extracted artifact and deleting, omitting or
weakening a guard merely to obtain exit code 0 are forbidden. Incomplete or ambiguous extraction
is a STOP. An initial pre-drain preflight never replaces the final post-drain extraction and
execution. The final preflight runs only after `hookah_backend_container_count = 0`,
`hookah_application_writer_session_count = 0` and
`unidentified_candidate_session_count = 0`, and before any new backend starts.

The authorized release must use this exact order:

1. Pin `EXPECTED_RELEASE_SHA` externally from the exact fully green GitHub Actions run and create
   the separate clean detached worktree at that SHA.
2. Verify that the same exact commit/push has fully green Actions and that
   `origin/main = release worktree HEAD = EXPECTED_RELEASE_SHA`; any mismatch is a STOP.
3. Create and verify the database backup and the approved full-database restore path.
4. Before downtime, prepare the exact image only from the detached release worktree, use the full
   SHA tag, verify any real OCI revision label and record the immutable image ID.
5. Drain normal traffic and stop/drain every old hookah backend instance; keep traffic drained
   through migration and the complete smoke.
6. Confirm `hookah_backend_container_count = 0` for the exact staging Compose project and `backend`
   service while PostgreSQL remains running.
7. Inspect `pg_stat_activity` and confirm
   `hookah_application_writer_session_count = 0`, including idle application connections.
8. Confirm `unidentified_candidate_session_count = 0`; every unidentified candidate is a STOP.
9. Extract the final preflight from the exact release worktree, record its SHA-256, execute the exact
   unedited shell artifact and retain its result, including the expected pre-cutover Flyway head.
10. Verify `DEPLOY_SHA = prepared image release SHA = EXPECTED_RELEASE_SHA`, then start exactly one
    new backend from the recorded prepared image; do not start an old instance.
11. Allow that backend's normal startup to apply PostgreSQL V126.
12. Verify the Flyway head is V126 and verify its checksum and successful history row/startup log.
13. Confirm every running hookah backend container uses the recorded new image and that old image
    running count is zero.
14. Run health, database health and the V126 schema invariants.
15. Run the complete bounded staging smoke below with exactly one new backend.
16. Restore normal traffic only after every smoke check passes completely.

The sequence `stop container -> immediately start new backend` is forbidden. The PostgreSQL session
gate and final read-only preflight must occur between stop/drain and the one-new-backend start.
Cutover requires `hookah_backend_container_count = 0`,
`hookah_application_writer_session_count = 0` and
`unidentified_candidate_session_count = 0`; any non-zero value blocks PostgreSQL V126. This is the
mandatory **zero application writer sessions** gate, not an active-query-only check.

The current backend does not configure a unique PostgreSQL `application_name`; staging uses
`DB_USER = POSTGRES_USER` and the Compose network. The release operator must therefore apply the
fail-closed predicate from `docs/DEPLOYMENT_RUNBOOK.md`: inspect every `client backend` on the
current database/user other than the gate session itself and classify it using recorded old-container
PID/client address and Compose network, observed `application_name`, and individually proved
operator PIDs. `idle` is not drained. Any unidentified row is
`STOP_FOR_BOOKING_MIXED_VERSION_ROLLOUT_DECISION`, not an optimistic continue.

After PostgreSQL V126 has applied successfully, improvised manual DB/schema/data cleanup is
forbidden. The following are unconditionally forbidden in every release recovery plan after V126:
restoring one table; restoring a set of selected tables; restoring only `support_thread_reads`;
restoring only `support_messages`; restoring schema objects separately from data; any other
partial-table restore; a partial restore over the migrated schema; or manually merging data from a
backup. Do not run manual `UPDATE`, `DELETE` or `INSERT` statements on read-marker/cursor rows;
manually `ALTER`, `DROP` or recreate schema objects; edit `flyway_schema_history`; mutate migration
versions or checksums; run cleanup SQL; downgrade the schema; run automatic or manual
`flyway repair`; or start the old backend to "repair" state. This prohibition has no
operator-confidence, exceptional-case or separately approved partial-recovery-plan override.

If startup or verification fails after schema cutover:

1. Keep normal traffic drained.
2. Do not start the old backend.
3. Perform no manual DB/schema/data cleanup.
4. Preserve the verified backup, evidence and logs.
5. Prepare a reviewed forward-fix binary.
6. Start only the reviewed forward-fixed backend.
7. Repeat health, database health, schema invariants and the complete smoke before reopening
   traffic.
8. Reopen traffic only after every repeated check succeeds completely.

The only restore path permitted by this release recovery contract is a full, consistent restore of
the entire database as a separate disaster-recovery decision. It is not an ordinary release
rollback and requires separate explicit confirmation from the user/product owner. After the full
restore, select a backend binary compatible with the restored Flyway state, reassess migrations,
then repeat health, database health, schema invariants and the complete smoke before reopening
traffic. Never combine a full-database restore with a partial transfer of tables, rows or schema
objects over V126. A long mixed deployment is prohibited. No old binary may be started to repair
the V126 database, and only the new or reviewed forward-fixed backend may run before traffic
reopens, unless a separately approved full-database disaster recovery has first restored a Flyway
state compatible with the selected binary.

With exactly one new backend and old image running count zero, the bounded staging smoke must prove:

- a user-visible NULL-author system message in a real `VENUE_CHAT` produces an unread badge;
- exact open clears the badge, and a new system message after open becomes unread again;
- wrong-surface deep links leave raw read markers unchanged;
- the correct surface clears only the exact marker;
- Guest, Venue and account/venue isolation hold;
- a real staff-chat Telegram notification reaches the canonical linked chat;
- Support and Conversations remain separate and do not mix.

The exact label-collision acceptance scenario is mandatory:

1. Create or select two test bookings for the same venue with the same display number (the
   authoritative `display_number`), but different service dates (`display_date`).
2. Confirm that the two records really have the same number, for example №1 and №1.
3. Confirm that their user-visible labels differ because of venue-local date/time:
   `Бронь №1 · <дата A>, <время A>` and `Бронь №1 · <дата B>, <время B>`.
4. Verify both labels in Guest booking list/detail, Venue booking list/detail, conversation
   list/detail, and the staff-chat notification if one is created in this smoke.
5. Confirm that no surface shortens both records to identical `Бронь 1` or `Бронь №1` labels.
6. Confirm that each label and its associated open action resolves to the exact corresponding
   booking/thread.

Two bookings with different display numbers do not reproduce the original collision and cannot
substitute for this scenario. If staging fixtures cannot naturally produce the same display number
on different service dates, record that capability as a smoke setup prerequisite; do not weaken the
smoke to different numbers.

`BOOKING-UNREAD-NULL-AUTHOR-001`, `BOOKING-UNREAD-GUEST-TYPE-GUARD-001` and
`BOOKING-UNREAD-MIXED-VERSION-ROLLOUT-001` remain `LOCAL_FIX_REVIEW_REQUIRED` until the next
independent review. The epic remains **BOOKING CONVERSATION UX / DISTINCT LABELS, INBOX AND UNREAD
DISCOVERABILITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

## Staff-Chat Notification Policy

Staff-chat policy is canonical in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Staff-chat may receive operational booking notifications according to venue policy:
- new booking request;
- confirmed/changed/canceled;
- arrival soon / overdue when future policy enables it;
- reminder attendance updates where current implementation does so safely.
- a fact-only new-Guest-message radar alert for `BOOKING_THREAD`, never the raw message stream.

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
| Mini App guest booking screen | `Мои брони` active/upcoming list with public label/time/deadline is documented; Guest History Foundation is staging-smoked for booking-only `SEATED` visits and non-seated booking filtering. | Account booking list/history with safe status labels and actions. | Broader booking-history polish, feedback and retention loops remain future. |
| Venue Mini App booking queue | Implemented/smoked for M3/M7a/M7b/M7c slices. | Source-of-truth operational queue under Venue Mode. | Overdue automation, broader reminder UI and preorder remain partial/future. |
| Telegram `/my` booking list | Implemented and compared visually with Guest Mini App for public label/time/deadline. | Same identity/status/deadline semantics across Bot and Mini App. | Keep runtime regression. |
| Booking chat | Implemented as one persisted thread per global `booking_id`; `Открыть переписку` opens the exact active or resolved thread. | DB uniqueness, booking-row serialization, atomic messages, retry dedupe and Guest/Venue/Platform isolation are locally proved. | Green Actions, migration preflight and bounded staging/two-account Telegram smoke remain release gates; Bot full inbox remains future. |
| Booking lifecycle statuses | Runtime statuses include `PENDING`, `CONFIRMED`, `CHANGED`, `CANCELED`, `EXPIRED`, `NO_SHOW`, `SEATED`. | Product copy distinguishes proposed time and cancellation actor. | Split `canceled_by_guest` / `canceled_by_venue` only when runtime supports it. |
| Hold minutes / arrival deadline | `venue_booking_settings.hold_minutes` and `arrival_deadline_at` are documented as implemented/smoked. | Venue setting with deadline snapshot and venue-local display. | Automatic policy edge cases need verification. |
| Reminders worker | M7c is code/test-backed and one controlled staging smoke passed; runtime disabled by default. | Rollout-gated transactional reminders with dedupe, quiet hours and safe actions. | Enable only with explicit rollout/smoke; management UI future. |
| No-show / seated | Runtime statuses, confirmed-only arrival guard and `BOOKING_SEATED` visit creation are documented; staging smoke confirmed non-seated booking statuses are hidden from Guest History. | Seated can feed visit; no-show must not. | Feedback, preorder and `visit_count` remain partial/future. |
| Analytics events | Analytics spec says booking events need verification. | Emit full booking lifecycle/reminder/chat facts. | Event emission/payload safety is partial/future unless tests prove it. |
| Staff-chat booking notifications | State-aware lifecycle notifications exist; the bounded new-Guest-message alert is locally implemented without raw chat text. | Staff-chat is radar only; no booking-chat stream, venue-chat or support-ticket spam. | Independent review, green Actions and a per-venue real Telegram group smoke remain required. |
| Support routing for booking problems | `SUPPORT_TICKET` category `Бронь` requires verified booking/venue context. | Booking problems escalate through support, not booking chat lifecycle. | Keep context verification and staff-chat denial in support regression. |

## Roadmap Status

- Booking lifecycle spec: `UPDATED`.
- Booking implementation: `PARTIAL / CLOSED by bounded MVP slices`; queue, hold settings, guest list parity and booking chat are smoke-closed in current docs, while complete automation and integrations remain partial.
- Booking Mini App management: `CLOSED for current MVP`, with future filters/history/polish.
- Hold minutes / arrival deadline: `CLOSED for current MVP`, with automatic policy edge cases needing verification.
- Reminders: `PARTIAL / rollout-gated`; implemented/test-backed and one controlled staging smoke passed, but disabled by default.
- No-show/seated: `CLOSED for confirmed-only operational guard`; booking `SEATED` -> Guest History integration is staging-smoked; feedback, preorder and `visit_count` remain future.
- Booking chat: `MVP / CLOSED for current smoke paths`.
- Visit/history dependencies: Guest History Foundation is staging-smoked; keep booking `SEATED` conversion, non-seated filtering, order/session/tab close signals and privacy/dedup in regression.

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
19. A new Guest booking message creates at most one fact-only canonical-venue staff-chat alert with
    exact-thread navigation when linked; raw booking-chat text, support tickets and venue chats do
    not post to staff-chat.
20. Venue users cannot access another venue booking.
21. Staff access matches final RBAC policy: view + confirmed-only seated/no-show unless changed intentionally.
22. Booking analytics/audit events exist where implemented, and payloads contain no raw message text/initData/secrets.
23. A user-visible NULL-author `VENUE_CHAT` system message appears unread in the exact card and
    aggregate Venue conversation badge, then clears only for the actor/thread opened; another actor
    and unrelated thread marker remain unchanged.
24. Ordinary Guest and confirmed Platform Guest-context crafted wrong-surface opens disclose no
    messages and preserve exact raw `thread_id`, `user_id`, `last_read_message_id`, `last_read_at`;
    correct `CONVERSATIONS` / `SUPPORT` opens update only their exact marker.
25. After an authorized V126 staging cutover, verify exactly one backend with the new image, no old
    image, account/venue isolation and the complete NULL-author/wrong-surface smoke from the rollout
    boundary above.
