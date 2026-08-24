# Project Status

Last verified: 2026-08-23.

## 1. Current stage

**TELEGRAM TRAFFIC ALLOWLIST / MAIN PORT IMPLEMENTED LOCALLY /
PORT-SCOPED LOCAL VALIDATION PASSED / READY FOR INDEPENDENT REVIEW / UNCOMMITTED**.

The reviewed V125 prerequisite commit `b4e13da3179438fad69d2344e1cb136a56f95f6c` is already the
allowlist-enabled staging rollback baseline. HT-05 ports that behavior onto exact main
`84e8c798b3d8ce3d789f79bbdbf10e565a2dfd33` in a separate worktree while preserving the booking
conversation, unread cursor, timezone, PostgreSQL V126/H2 V127 and deployment-preflight changes
that landed after the common base. The changeset is local and uncommitted. Required
allowlist/booking tests, the full Telegram router suite, H2/PostgreSQL migration parity, backend
compile/style, the Mini App production build and the structured `216/216` browser smoke pass with
zero skips in the scoped gates. Task-local read-only audits found a CI XML-floor mismatch, unsafe
rollback wording, stale policy-test expectations and four unbounded best-effort Telegram log
contexts; those findings are fixed and the final task-local re-audits report no remaining actionable
issue. A separate independent review remains pending. The complete backend run reports `2127`
tests, `2` failures, `0` errors and `0` skips; exact main reproduces both failures in
`StaffProfileLinkConcurrencyPostgresTest` with the same two-waiter lock-observation assertion,
outside this port's changed paths. No push, PR, merge, main-port deploy, V126 execution or staging
mutation is recorded. Staging Manifest B remains active and unrestricted staging rollback is
prohibited.

The booking release line below remains unchanged product context and is preserved by this port.

**BOOKING CONVERSATION UX / DISTINCT LABELS, INBOX AND UNREAD DISCOVERABILITY /
MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

This bounded slice keeps authoritative per-venue-service-day booking numbers and adds one stable
venue-local label, `Бронь №N · dd.MM.yyyy, HH:mm`, with a booking-id fallback across Guest/Venue
booking DTOs, booking conversations and Telegram notifications. Venue navigation now separates
`Брони`, `Переписки` (`BOOKING_THREAD` plus `VENUE_CHAT`) and `Поддержка` (`SUPPORT_TICKET`). Existing
actor/thread-scoped message-id cursors drive unread-first inbox ordering, exact booking-card markers
and the aggregate conversation badge. `last_read_message_id` is the sole unread authority;
`last_read_at` remains wall-clock metadata only. Every user-visible message with
`author_user_id = NULL` is a system message and counts as foreign for every actor, including exact
thread/card and aggregate Venue conversation unread. Guest exact-open routes map the fixed
server-owned `CONVERSATIONS` (`BOOKING_THREAD` + `VENUE_CHAT`) or `SUPPORT` (`SUPPORT_TICKET`)
surface into the repository transaction; locked ownership and type validation happen before the
message snapshot, marker update or detail disclosure for ordinary Guest and confirmed Platform
Guest-context calls. A committed Guest booking message can atomically
enqueue one fact-only alert to the canonical venue's already linked staff chat; missing/disabled
configuration safely skips it. Additive PostgreSQL V126/H2 V127 add the nullable cursor and `(thread_id, id)`
message index without a default, backfill, foreign key or destructive rewrite. No thread/RBAC
change, personal subscription domain, Media/R2, reminder, no-show, queue or preorder expansion is
included. Independent review, green Actions, migration rollout, staging redeploy and a fresh
Guest/Venue/Telegram smoke are still required; no staging smoke is recorded for this slice.

Fresh local evidence for the three current findings is exact, not aggregate-only:
`SupportThreadReadRepositoryTest 21/21`, `BookingConversationRoutesTest 9/9`,
`SupportTicketRoutesTest 15/15` and real-PostgreSQL
`SupportThreadReadConcurrencyPostgresTest 6/6` each report zero skipped/failures/errors; the full
structured Playwright run reports `216/216` with zero unexpected/flaky/skipped/runner errors or
failed attempts. This is local validation only, not green Actions or staging evidence.

The previous booking-conversation-integrity release remains separately review-required. Its local
implementation and test evidence covers
`BOOKING-CLIENT-ID-RELOAD-RECONCILIATION-001`,
`BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001`,
`BOOKING-MINIAPP-IDEMPOTENCY-PG-EVIDENCE-001`, `BOOKING-DOC-PREVERDICT-001`,
`BOOKING-OUTBOX-LEGACY-DEDUPE-SEMANTICS-001` and
`BOOKING-MESSAGE-MIGRATION-METADATA-001`, plus the H2 semantic-parity fix for
`BOOKING-DEDUP-AUDIT-REF-001`, inside only
`BOOKING-THREAD-UNIQUENESS-001` / `QA-BOOKING-ISOLATION-001`. The four Guest/Venue dedicated/generic
Mini App booking surfaces now reconcile every authoritative booking id through a complete bounded
read-only batch contract before exposing first-send/reply actions after recreate; absence from a
capped thread list is never evidence of no thread, and a failed/partial read remains screen-scoped
and fail-closed.
PostgreSQL V124/H2 V125 reject recursive unknown audit reference keys before domain mutation, the
deployment preflight uses the same predicate, and Mini App message metadata is asserted exactly on
both databases. Legacy outbox enqueue retains key-only replay while only the connection-aware
booking API uses strict canonical-envelope collision checks. Real PostgreSQL evidence observes the
waiting caller's exact independent PID, ungranted `pg_locks` row and
`pg_blocking_pids(waiter) = [caller A]` before release/commit. Independent review is still required;
no final-review verdict, green Actions, preflight, migration, deploy or staging smoke is recorded.
No reminder, no-show, queue, preorder or broad analytics/audit redesign is included.

`BOOKING-V124-CATALOG-INVENTORY-001` is also locally fixed: PostgreSQL V124 now anchors inbound
foreign keys on the exact current-schema `support_threads` OID and records one authoritative row per
constraint through `pg_catalog`, including exact source OID/namespace, complete ordered
`conkey`/`confkey` arrays and action/match metadata. Exact current-schema source OIDs and attnums are
required for the two known single-column references; composite, non-`id`, external same-name,
additional cross-schema and privilege-hidden cross-owner references fail closed before domain
mutation. A controlled PostgreSQL 16 catalog stress reproduced SQLSTATE `57014` at V124's
five-minute statement timeout before the fix. The strengthened production-migration wrapper passes
`47/47` with domain/catalog/index/Flyway-history snapshots, and the exact `44/44` menu mutation class
remains the bounded performance evidence. This local fix still requires a short independent review,
an explicit commit and a new green Actions run before any preflight, migration or deploy; no staging
preflight/deploy or post-failure release-DB migration has been performed.

## 2. Release closure

**PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Release HEAD `e35def99ea8429462e5fdaaeee914f57da72e775` matches `origin/main`.
The user confirmed fully green GitHub Actions for that HEAD, staging deploy, the consolidated
onboarding/ownership smoke and cleanup. Local GitHub CLI authentication is invalid, so Actions are
recorded as user-confirmed rather than independently queried in this docs-only closure.

Recorded smoke outcomes:

- first applicant submitted through Telegram; an existing Owner submitted an additional venue in
  Venue Mini App;
- exact retry created no duplicate request; a different application created a separate request;
- Platform Owner saw requests, venues and owners;
- create/link produced exactly one venue and active OWNER membership;
- selected venue did not change automatically;
- the legacy quota-direct entry used the shared application flow;
- multi-owner venues and owner portfolios worked;
- a first-ever applicant account received baseline limit `1`;
- applicant, actor and source remained server-derived;
- cleanup passed.

This closes only the bounded onboarding/ownership release, not the whole Platform/Venue product or
overall production readiness.

## 3. Remaining-work audit snapshot

- Markdown surfaces scanned: `35` (`32` under `docs/**` plus this file, `README.md`, root
  `AGENTS.md`).
- Normalized raw candidate records: `198`.
- Canonical catalog items after evidence review and global deduplication: `110`; `105` remain active
  after the five locally implemented booking items below.
- Disposition: `OPEN_CONFIRMED 41`, `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED 5`, `BLOCKED_PRODUCT_DECISION 14`,
  `BLOCKED_PREREQUISITE 13`, `DEFERRED_AFTER_MVP 31`, `UNKNOWN_NEEDS_RESEARCH 6`,
  `STALE_ALREADY_IMPLEMENTED 42`, `DUPLICATE_OF_OTHER_ID 35`, `HISTORICAL_ONLY 11`.
- Historical audits remain evidence/history and do not reactivate closed work without current
  code/test evidence.

Current P2/P3 registry entries preserved as open:

- `ONBOARDING-H2-001`;
- `ONBOARDING-TG-CONFIRM-001`;
- `ONBOARDING-DECISION-RETRY-001`;
- `MENU-CONC-001`;
- `MENU-TEST-002`.

Booking review registry for this Goal:

- `BOOKING-DISPLAY-LABEL-001` — `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED`; authoritative booking
  number plus venue-local date/time and stable booking-id fallback are shared across Guest/Venue
  booking, conversation and Telegram surfaces; independent review/Actions/staging smoke remain;
- `BOOKING-INBOX-DISCOVERABILITY-001` — `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED`; Venue
  `Переписки` contains only booking conversations and venue chats with deterministic unread-first
  ordering, while `Поддержка` remains the support-ticket surface;
- `BOOKING-UNREAD-NOTIFICATION-001` — `MVP_IMPLEMENTED_LOCAL_VALIDATION_PASSED`; actor-scoped
  nav/card unread uses the monotonic `last_read_message_id` cursor, and exact read clearing snapshots
  `MAX(support_messages.id)` under canonical locks. The existing transactional outbox can send one
  privacy-safe canonical-venue staff-chat alert without introducing personal subscriptions;

- `BOOKING-DEDUP-READ-001` — `DONE` after fail-closed H2/PostgreSQL lossless-read migration proof and
  live writer-first/migration-first snapshot serialization;
- `BOOKING-WRITER-CONVERGENCE-001` — `DONE` after production route/repository convergence proof;
- `BOOKING-CI-FLOOR-001` — `DONE` after exact selectors, XML minima and structured Playwright floor;
- `BOOKING-PG-EVIDENCE-001` — `DONE` after exact PostgreSQL race post-state evidence;
- `BOOKING-MINIAPP-OUTBOX-001` — `DONE`; persisted scope-bound Mini App replay identity and
  same-transaction message/thread/strict-booking outbox remain covered; the legacy enqueue contract
  is separately preserved by this bounded fix;
- `BOOKING-DEDUP-AUDIT-REF-001` — `LOCAL_FIX_REVIEW_REQUIRED`; H2 now validates and remaps the exact
  top-level integer `ticketId` with a duplicate-aware semantic JSON helper independent of key order
  and formatting. The recursive unknown-key gap is locally fixed separately under
  `BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001`; both still await independent review;
- `BOOKING-MIGRATION-SNAPSHOT-001` — `DONE`; pre-guard PostgreSQL table locks and real writer-first
  plus migration-first Flyway serialization pass the exact `2/2` concurrency gate;
- `BOOKING-READ-LOCK-ORDER-001` — `DONE`; production read-marker paths retain
  `bookings -> support_threads -> support_thread_reads` or
  `support_threads -> support_thread_reads`, with exact repository `21` and real PostgreSQL `6`
  lock/cursor/RBAC cases and no reverse runtime DML path;
- `BOOKING-SAVEPOINT-COLLISION-001` — `OPEN`; the current unique-conflict branch is defensive-only
  under the booking-row lock and requires a separate review before removal.

Findings fixed locally and still requiring independent review in this bounded fix:

- `BOOKING-UNREAD-NULL-AUTHOR-001` — `LOCAL_FIX_REVIEW_REQUIRED`; user-visible NULL-author rows use
  the same null-safe foreign-author predicate in exact/card and aggregate Venue unread, with
  authoritative exact-open clearing and no RBAC expansion;
- `BOOKING-UNREAD-GUEST-TYPE-GUARD-001` — `LOCAL_FIX_REVIEW_REQUIRED`; ordinary Guest and confirmed
  Platform Guest-context routes pass fixed server-owned `CONVERSATIONS` / `SUPPORT` contracts into
  locked ownership/type validation before markers or message facts;
- `BOOKING-UNREAD-MIXED-VERSION-ROLLOUT-001` — `LOCAL_FIX_REVIEW_REQUIRED`; V126 docs require a
  backup, full old-instance drain, exactly one new image, normal-startup migration, forward-fix
  rollback and bounded NULL-author/type-guard/isolation smoke;
- `BOOKING-UNREAD-TIMESTAMP-AUTHORITY-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-UNREAD-PG-RACE-COVERAGE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-LABEL-TS-PARITY-COVERAGE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-E2E-NOTIFIER-COVERAGE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-DOC-CURRENT-SLICE-DRIFT-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-CLIENT-ID-RELOAD-RECONCILIATION-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-AUDIT-UNKNOWN-REFERENCE-KEY-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-MINIAPP-IDEMPOTENCY-PG-EVIDENCE-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-DOC-PREVERDICT-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-OUTBOX-LEGACY-DEDUPE-SEMANTICS-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-MESSAGE-MIGRATION-METADATA-001` — `LOCAL_FIX_REVIEW_REQUIRED`;
- `BOOKING-DEDUP-AUDIT-REF-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-CATALOG-INVENTORY-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-CONSTRAINT-SHAPE-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-SOURCE-RELATION-IDENTITY-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-PG-CI-FLOOR-001` — `LOCAL_FIX_REVIEW_REQUIRED`.
- `BOOKING-V124-CROSS-OWNER-DOMAIN-SNAPSHOT-001` — `LOCAL_FIX_REVIEW_REQUIRED`.

Still open by design:

- `BOOKING-CI-PLAYWRIGHT-FLAKE-001` — `OPEN`; an earlier structured run executed `197/198` after
  one unrelated favorite-test failure, and this pass first executed `205/206` after one unrelated
  catalog-debounce timing failure before the focused and second full `206/206` reruns passed. The
  discoverability run first executed `207/208` after the same unrelated catalog virtual-clock
  debounce flake; its isolated repeat and second full structured `208/208` run passed. After the
  wrong-thread-type deep-link read guard was added, the final structured run passed `209/209`
  with zero unexpected, flaky or skipped results. This cursor/label/notifier slice then passed the
  raised structured floor at `212/212`, also with zero unexpected, flaky or skipped results. The
  current Guest surface/deep-link closure raised the mandatory structured floor and passed
  `216/216`, again with zero unexpected, flaky or skipped results.
  Trigger: the next Mini App CI-hardening pass or a repeated same failure in GitHub Actions;
- `BOOKING-SAVEPOINT-COLLISION-001` — `OPEN`;
- `BOOKING-AUDIT-EVENTS-001` — `OPEN`;
- `BOOKING-REMINDER-ROLLOUT-001` — `OPEN`;
- `BOOKING-NO-SHOW-AUTOMATION-001` — `OPEN`;
- `BOOKING-QUEUE-POLISH-001` — `OPEN`;
- `BOOKING-PREORDER-001` — `OPEN`.

Media/object-storage work remains blocked by `MEDIA-STORAGE-DECISION-001`; this Goal did not
implement or modify Media/R2. Next step for this slice: independent review, explicit commit, green
Actions, apply the additive cursor migration, staging redeploy and bounded Guest/Venue/Telegram
conversation smoke. The previous integrity release retains its separate V124/V125
preflight/migration sequence.
