# Project Status

Last verified: 2026-09-01.

## 1. Current stage

**HT-12M IDENTITY-GATED MAINTENANCE / MAIN PORT IMPLEMENTED /
V125 STAGING DEPLOY PASS / EXACT MAIN BASE VERIFIED / SOURCE MAPPING CLOSED /
LOCAL VALIDATION AND INDEPENDENT REVIEW PASSED / COMMIT AND BRANCH ACTIONS PENDING /
NO MAIN INTEGRATION**.

Fresh `origin/main` is exact commit `b49a89a299d8c9864fcfc5937d455141563b388a`, tree
`662c6df77ba757e57f9e49f9f75d62f1f0654a15`, parent
`4daf5546fb622a6b967398f5c25b7bed41d7fa05`. The isolated main-port branch is
`codex/ht-12m-identity-maintenance-main-port`; the original dirty main worktree and the dirty V125
deployment-evidence worktree remain untouched.

The authoritative V125 source candidate is
`f577934691a1a7a79ba327c54e2055425142b7be`, tree
`f21f7114bf021ddc8f862294e5f54a841200c179`, based on exact V125 source
`be5d62a5e9058f89cd72be6c313c71fa46ccdbf2`. All 43 changed paths are mapped in
`docs/HT12M_IDENTITY_MAINTENANCE_MAIN_PORT_EVIDENCE.md`: `PORTED_AS_IS=29`,
`MERGED_WITH_MAIN_CHANGE=13`, `OBSOLETE_WITH_PROVEN_REPLACEMENT=1`, `MISSING=0` and unresolved
`CONTRACT_CONFLICT=0`. Base-compatible paths are byte-identical; explicit merges preserve current
main-only Application, Telegram, booking/conversation, deployment, CI and canonical-document
behavior.

Staging successfully runs that V125 candidate as
`hookah_bot_ant-backend:f577934691a1a7a79ba327c54e2055425142b7be`, canonical image ID
`sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`, with
`TELEGRAM_TRAFFIC_POLICY=PRODUCT`, maintenance `OFF`, empty product/maintenance lists, Flyway V125,
V126 absent, one backend/poller, empty active queues and unchanged Caddy. Exact image, health,
authenticated read-only Guest/Venue/Platform, negative tenant/Platform RBAC, Bot API and privacy-log
checks passed. No live mutating Guest/invite smoke was repeated; exact-head automated coverage
remains the evidence for those mutations.

The main port adds, removes or edits no migration. The current PostgreSQL and H2 migration trees,
including PostgreSQL V126 and the H2 V126/V127 identities, were recorded before editing and must
remain byte-identical at the candidate. `PRODUCT` stays the normal public-pilot admission mode;
identity-gated `V126_SMOKE` is a separate temporary fail-closed overlay and never substitutes for
membership/RBAC or static user management.

This phase authorizes implementation, local validation, independent read-only security review,
feature-branch commit/push and branch Actions only. It does not authorize main integration, staging
deploy, maintenance activation, Flyway/V126, production access or any Caddy change. The next gate
after a complete green main-port candidate is
`IDENTITY_MAINTENANCE_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`.

Current local evidence is green: focused maintenance/config/auth/JWT/RBAC and main-only booking
tests; zero-skip Telegram ingress/outbox/direct/staff-notification selectors; the full Router suite;
the mandatory PostgreSQL concurrency/integrity and H2/PostgreSQL migration-sanity matrices; the
full backend suite under its UTC contract; compile and ktlint; Mini App production build and
`216/216` browser smoke; Compose; fixed-env admission, maintenance fail-closed, image-identity and
pre-SSH deployment self-tests; `git diff --check`; and a zero-path, byte-identical migration proof.
One non-required oversized repeat selector exhausted the JVM instrumentation agent after its early
classes passed; it is not counted as evidence, and all classes it did not reach passed in the
bounded standalone selectors used by CI.

The independent final read-only security review inspected the complete frozen 50-path staged diff
and returned PASS with no P0, P1 or blocking P2. It independently verified admission ordering,
existing-JWT rechecking, unchanged denied queue/outbox rows, direct/staff outbound gates, tenant and
RBAC preservation, autonomous-writer suppression, fail-closed startup/deploy controls, all 43 source
mapping entries, unchanged migration identities and nondecreasing CI floors.

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

### HT-11 V125 dry-run closure

HT-11 closed on 2026-08-27 as
`HT11_CLOSED_PASS_WITH_EXPLICIT_LIVE_LIMITATION`. Live staging evidence passed the Telegram
allowlist and MIX/CLIENT/non-MIX isolation gates, the V125 collision pair creation and Guest/Owner
visibility checks, exact `V126_BOOKING_THREAD` identity and isolation, one exact Owner open and
reply, the message transition `2/0/0 -> 2/1/0`, one terminal private Guest delivery, and the exact
Guest unread `0 -> 1 -> 0` transition after the Owner reply and Guest exact-thread open. Collision
A/B are retained as `HISTORICAL_V125_COLLISION_EVIDENCE`. No duplicate marker row appeared;
`V126_READ_BASELINE`, other threads, CLIENT and non-MIX state remained unchanged.

Exactly one V125 real-client assertion remains explicitly unexecuted:
`LIVE_V125_GUEST_REPLY_TO_OWNER_UNREAD_CLEAR = NOT_EXECUTED_OPERATIONALLY_BLOCKED`. The Guest did
not send the planned reply; consequently, the corresponding Owner unread creation and exact-thread
clear were not exercised live. This is neither failed behavior, a live pass nor a waiver.
`VenueBookingRoutesTest` and the Mini App Guest smoke provide passing automated regression coverage,
but they do not replace the mandatory post-V126 live smoke:

`AUTOMATED_REGRESSION = PASS`

`LIVE_REAL_CLIENT_V125_ASSERTION = NOT_EXECUTED_OPERATIONALLY_BLOCKED`

HT-14 must retain `HT14_MANDATORY_LIVE_GATE_GUEST_REPLY_OWNER_UNREAD_CLEAR`: one exact Guest reply,
one persisted Guest message, one expected Telegram/outbox delivery, exact-thread-only Owner unread
creation and clear, no duplicate marker row or unread resurrection, and no other-thread, CLIENT or
non-MIX mutation. HT-14 cannot conclude PASS while this gate is pending, waived or represented only
by automated evidence. No manual window or retained WebView/session remains. After HT-INC-02 is
fully integrated, the then-recorded next task was **HT-12J-R1 — Patched Caddy Retry Baseline Refresh
and Resume**. That historical direction is now superseded: HT-12J-R2 is closed as
`FAILED_SEALED_EXECUTION_RECOVERED_TO_UBUNTU_BASELINE`, HT-12K is closed as
`CURRENT_CADDY_EXACT_URL_IDENTITY_NOT_PROVABLE`, and no Caddy sidecar, patched-Caddy,
packet-observer or exact-path work is authorized.

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
