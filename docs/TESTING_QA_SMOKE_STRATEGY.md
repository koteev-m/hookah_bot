# Testing / QA Smoke Strategy

Дата актуализации: 2026-08-30.

Статус: **current product reference / UPDATED**. This document is the canonical QA/smoke strategy for the Telegram bot + Mini App platform. It consolidates local validation, GitHub Actions expectations, area-specific smoke suites, staging policy, failure reporting and Codex handoff rules. Deployment and incident operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`.

Latest release-closed bounded menu audit blocks: option hard delete with atomic base-profile
normalization, option rename, option price and **DANGEROUS ACTION AUDIT SLICE / MENU ITEM
AVAILABILITY AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. The broader Menu and Dangerous Action
Audit programs remain partial; keep each bounded gate in regression. Menu item create is
**DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**. The current category/item management closure is **VENUE MENU MANAGEMENT /
EXISTING-CONTRACT AUDIT AND TRANSACTION CLOSURE / DONE / MVP / STAGING-SMOKE-PASSED**. This
completes only its bounded release gates. Shared initial menu bootstrap is **VENUE MENU ONBOARDING /
SHARED INITIAL MENU BOOTSTRAP / DONE / MVP / STAGING-SMOKE-PASSED**; user-confirmed green Actions,
staging deploy and the bounded cross-surface smoke close only that bootstrap slice.

Latest onboarding release closure: **PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT / DONE / MVP /
STAGING-SMOKE-PASSED** for release HEAD `e35def99ea8429462e5fdaaeee914f57da72e775`, with user-confirmed
green Actions, staging deploy, consolidated smoke and cleanup. Local GitHub CLI authentication is
invalid, so Actions are recorded as user-confirmed evidence.

Current review-ready slice: **BOOKING CONVERSATION UX / DISTINCT LABELS, INBOX AND UNREAD
DISCOVERABILITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.
This records bounded local implementation evidence only. Independent review, green Actions,
PostgreSQL V126/H2 V127 rollout, staging redeploy and a fresh Guest/Venue/Telegram smoke remain
required before release.

Historical review-ready slice (still separately awaiting its release gates): **BOOKING CONVERSATION
INTEGRITY / THREAD UNIQUENESS AND REAL MULTI-TENANT ISOLATION / MVP IMPLEMENTED / LOCAL VALIDATION
PASSED / REVIEW REQUIRED BEFORE COMMIT**. Its V124/V125 preflight, migration and two-account staging
requirements remain regression/release evidence; it is not the current slice and is not marked done.

## Core Rule

Quality gates must match the blast radius of the change. Do not claim a feature is release-ready from local-only checks when it changes backend runtime, Mini App behavior, Telegram bot, staff-chat, billing/security or migrations. Do not run staging deploy for docs-only changes.

### Permanent public-pilot Telegram admission quality gate

The authoritative V125 hotfix is
`b4e13da3179438fad69d2344e1cb136a56f95f6c..be5d62a5e9058f89cd72be6c313c71fa46ccdbf2`.
HT-INC-02 ports it onto main without changing either migration tree and preserves PostgreSQL V126 /
H2 V127 plus all main-only booking behavior. Public-pilot staging uses `PRODUCT`; `ALLOWLIST` is a
separately reviewed isolated-smoke mode only. Every future main/V126 release must keep this matrix
and the deployment guards below mandatory:

- `TelegramTrafficPolicyStartupTest`, `TelegramTrafficPolicyConfigTest` and
  `TelegramTrafficPolicyTest`: staging accepts only explicit `ALLOWLIST` or `PRODUCT` and rejects
  missing, unknown and `UNRESTRICTED`. `ALLOWLIST` retains exact canonical list parsing and
  fail-closed identity behavior. `PRODUCT` rejects nonempty static lists, permits positive matching
  private actor/chat identities without manifest membership and admits only the narrow supported
  staff-group bootstrap/operational shapes.
- `TelegramLongPollingWorkerTest`, `TelegramInboundUpdateWorkerTest`,
  `TelegramBotRouterIdempotencyTest` and `TelegramWebhookRoutesTest`: allowed/denied private and
  group updates, callback actor/chat combinations, missing actor/chat and unsupported update shapes;
  direct long-polling/webhook ingress denial occurs before router, idempotency, enqueue or any
  database write. A denied long-polling update advances only the process-local offset. A historical
  queued webhook row is first claimed and then marked processed by the defense-in-depth worker gate,
  but never reaches router/idempotency/domain/outbox writes; future activation still requires zero
  pending/claimed inbound rows. The same-token restart/redelivery composition test
  starts the first worker, waits for its batch, cancels and joins its job/scope, then starts a distinct
  worker with the same router/token configuration and redelivers the batch. With the shared
  idempotency and outbox mocks, the denied update reaches neither dependency, while the replayed
  allowed update makes two idempotency acquisition attempts (`true`, then `false`) and exactly one
  outbound side effect. This is deterministic interaction evidence, not a database restore/restart
  test; the long-polling path does not write the webhook inbound queue. Runtime configuration starts
  only the selected long-polling or webhook path, never both. Product group tests additionally prove
  unrelated traffic is state-free, valid link bootstrap remains role/code/group checked and a linked
  callback must match the exact current staff chat and venue.
- `TelegramAuthRouteTest`, `SessionAuthTest`, venue RBAC and Platform route tests: valid fresh signed
  initData for a previously unknown positive user upserts and issues a Guest session without static
  IDs. Public Guest reads work; Venue and Platform APIs remain denied until exact server-side role
  authority exists. Protected routes keep checking membership/RBAC on every request.
- Staff invite/router/repository and Platform owner-access tests prove the complete
  Platform Owner -> new Venue Owner -> new external Staff/Manager chain. OWNER, MANAGER and STAFF
  are previewable and acceptable by previously unknown identities and are derived only from valid
  active invite rows; expired, revoked, malformed, used and concurrent double acceptance creates
  no extra membership or audit. Invite create/accept audit is committed in the same transaction.
  A second operational Owner invitation preserves the venue's existing commercial owner account
  and creates no commercial account for the invitee; an unlinked venue retains the first-owner
  preparation path.
  Injected audit failure rolls back membership creation, role change, invite use and Owner
  preparation. Each membership is exact-role, exact-venue and tenant isolated, and audit JSON
  contains no raw Telegram identity fields.
- `TelegramProductAbuseLimiterTest` and Router tests prove bounded limits for unknown/private
  traffic, invite attempts, group link/operations and spam without logging raw keys or payloads.
- `TelegramOutboxWorkerTest`, `StaffChatNotifierTest` and
  `BookingMessageStaffChatNotifierTest` and `TelegramApiClientTrafficPolicyTest`: `ALLOWLIST` claim
  behavior remains unchanged. `PRODUCT`
  private recipients require a current server-authoritative user/workflow record and group
  recipients require the exact current venue staff-chat link at enqueue, claim and dispatch.
  Unlink/relink cancels stale authority/live targets; payload/envelope mismatch remains terminal and
  there is no raw-client bypass or cross-venue delivery.
- Captured-log assertions are explicit and scoped: `TelegramTrafficPolicyConfigTest` and
  `TelegramTrafficPolicyLoggingTest` prove invalid
  raw identity values are neither logged nor echoed in errors; `TelegramLongPollingWorkerTest`
  captures a provider/preflight failure sentinel, while `TelegramInboundUpdateWorkerTest` and
  `TelegramWebhookRoutesTest` capture queued and direct-webhook denial payload sentinels; each asserts
  that logs retain only safe source/reason/error-type metadata;
  `TelegramAuthRouteTest` captures the denied initData request and verifies that user IDs, bot token,
  raw initData and user payload are absent; `TelegramApiClientTrafficPolicyTest` does the same for
  provider error payloads. These tests do not claim that arbitrary third-party logging outside the
  tested application paths is redacted.

The mandatory PRODUCT admission contract is the following complete matrix; future changes may add
coverage but may not silently remove any item:

1. Unknown private user receives normal Guest bot behavior in `PRODUCT`.
2. Unknown valid signed and fresh Mini App identity receives a Guest session.
3. Guest remains denied from Venue and Platform APIs without exact server-side authority.
4. Unknown user can preview and accept the exact stored Staff invitation.
5. Unknown user can preview and accept the exact stored Manager invitation.
6. Unknown user can preview and accept the exact stored Owner invitation; a second operational
   Owner preserves the existing commercial owner account across audit rollback and retry.
7. Platform Owner -> new Venue Owner -> new external Staff/Manager succeeds without static IDs.
8. Invite expiry, revocation, one-time use and concurrency remain enforced.
9. Invite creation/acceptance and its audit commit in one transaction.
10. Audit failure rolls back membership creation, role change, invite use and Owner preparation.
11. Audit JSON contains no raw Telegram identity fields.
12. Foreign-venue and Platform escalation remain denied.
13. Unknown group traffic is harmless and creates no product state.
14. Linked staff-chat actions require exact actor, venue and current-chat authority.
15. Outbound authority never falls back to an arbitrary chat ID.
16. Abuse-limit and privacy-log assertions remain green.
17. `ALLOWLIST` remains exact and fail-closed, including state-free denial.
18. `PRODUCT` never requires static user/chat IDs.
19. Staging rejects PRODUCT with static IDs, missing/blank/normalized-placeholder invite pepper,
    and every `UNRESTRICTED` configuration.

CI enforces exact JUnit XML presence, zero skipped/failures/errors, pinned security-critical test
names and these minimum floors: route/security `VenueStaffRoutesTest 34`,
`StaffInviteRepositoryTest 11`, `PlatformVenueRoutesTest 16`,
`TelegramBotRouterTableTokenTest 560`, `TelegramOutboxWorkerTest 18`,
`TelegramOutboxVenueAuthorityTest 2`, `VenueRepositoryTest 7`; lightweight
`HttpAccessLogPrivacyTest 1`, `MiniAppAbuseProtectionTest 3`,
`TelegramBotRouterLinkCommandTest 23`, `TelegramProductAbuseLimiterTest 7`,
`TelegramRateLimiterTest 2`, `TelegramTrafficPolicyStartupTest 4`,
`TelegramTrafficPolicyConfigTest 9`, `TelegramTrafficPolicyTest 8`,
`TelegramWebhookRoutesTest 5`, and `TelegramAuthRouteTest 11`. The Compose job must also run
`bash -n scripts/validate-staging-admission.sh`, `bash -n scripts/deploy-staging.sh`, and
`bash scripts/validate-staging-admission.sh --self-test docker-compose.yml`.

Run focused groups first, then the relevant extended Telegram suite and backend compile/lint:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*TelegramTrafficPolicyStartupTest*' \
  --tests '*TelegramTrafficPolicyConfigTest*' \
  --tests '*TelegramTrafficPolicyTest*' \
  --tests '*TelegramProductAbuseLimiterTest*' \
  --tests '*TelegramTrafficPolicyLoggingTest*' \
  --tests '*TelegramLongPollingWorkerTest*' \
  --console=plain

./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*TelegramInboundUpdateWorkerTest*' \
  --tests '*TelegramBotRouterIdempotencyTest*' \
  --tests '*TelegramWebhookConfigTest*' \
  --tests '*TelegramWebhookRoutesTest*' \
  --console=plain

./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*TelegramAuthRouteTest*' \
  --tests '*SessionAuthTest*' \
  --tests '*VenueRbacRoutesTest*' \
  --tests '*PlatformVenueRoutesTest*' \
  --console=plain

./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*TelegramOutboxWorkerTest*' \
  --tests '*StaffChatNotifierTest*' \
  --tests '*BookingMessageStaffChatNotifierTest*' \
  --tests '*TelegramApiClientTrafficPolicyTest*' \
  --console=plain

./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*TelegramBotRouter*' \
  --tests '*StaffInvite*' \
  --tests '*VenueStaff*' \
  --tests '*MiniApp*Auth*' \
  --console=plain

./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
git diff --check
git status --short
```

HT-INC-02 also runs the full backend suite, PostgreSQL-backed integration/concurrency selectors,
compile, ktlint, the unchanged Mini App production build and complete structured Playwright smoke,
Compose validation, deploy-script syntax/fixture guards and an empty migration diff. Exact JUnit XML
files must exist, meet or exceed their declared floors and report zero skipped/failures/errors; a
selector name alone is not coverage. Green local checks prove readiness for independent review only.

#### Recorded public-pilot staging acceptance

Read-only reconciliation on 2026-08-30 confirms the exact candidate
`be5d62a5e9058f89cd72be6c313c71fa46ccdbf2`, runtime `PRODUCT`, empty static lists, restricted
non-placeholder invite pepper, one backend/long poller, Flyway V125 with V126 absent, healthy
inactive queues and unchanged Caddy TLS 1.2 mitigation. Alias-only transactional evidence
corroborates the two reported external acceptance identities; later explicit audited membership
changes remain separate events and do not rewrite the acceptance ledger.

`LIVE_EXTERNAL_NEW_USER_INVITE_ACCEPTANCE = PASS`

This closes HT-INC-01E. Do not create another coordinated client window. Runtime behavior changes in
future releases still require the normal green-Actions/staging-smoke policy, but HT-INC-02 itself
must not redeploy staging, apply V126, access production or restart/reload Caddy.

### Booking test-only release hygiene

`BOOKING-TEST-SUBSCRIPTION-WORKER-ISOLATION-001 = LOCAL_FIX_REVIEW_REQUIRED`. Before booking
assertions, `SubscriptionBillingJob` could race a strict `seedSubscription` fixture insert by
creating a TRIAL row. H2 then reported SQLState `23505`, duplicate `venue_subscriptions` primary
key. Every fixture-bearing `GuestBookingRoutesTest` and `VenueBookingRoutesTest` configuration now
sets `billing.subscription.intervalSeconds=0`. The same isolation contract remains the default for
`GuestOrderRoutesTest` configurations that use its strict fixture; its two inventoried
non-fixture configurations explicitly retain normal worker behavior. Non-fixture booking configs
are also unchanged. The fixture remains a strict `INSERT`, so an accidental background insert
still fails fast. Production billing defaults, runtime and schema are unchanged.

`BOOKING-TIMEZONE-ATTENDANCE-SINGLE-RESOLUTION-001 = LOCAL_FIX_REVIEW_REQUIRED`. Guest attendance
staff-chat coverage deliberately omits a venue timezone and must also pass with the host timezone
set to UTC. The route resolves one operation zone with the deterministic booking product fallback,
then passes that exact `ZoneId` to both the staff-chat formatter and response DTO. The focused test
retains the one-row outbox, safe guest label, copy and Telegram-ID privacy assertions. Bot `/my`
and reminder attendance callbacks likewise reuse one resolved zone for the staff alert and refreshed
guest message.

`BOOKING-TIMEZONE-STAFF-NOTIFIER-PROPAGATION-001 = LOCAL_FIX_REVIEW_REQUIRED`. Booking lifecycle
staff notifications require their caller's resolved operation `ZoneId`; `notifyBookingNow` cannot
re-read venue timezone independently. Guest Mini App create/update/cancel and Bot
create/update/cancellation paths reuse their one operation zone for persisted/local semantics,
labels and notifier delivery. The unrelated order live-card timezone resolver and the separate
transactional fact-only booking-message radar path keep their existing contracts.

`BOOKING-TIMEZONE-FALLBACK-CONSISTENCY-001 = LOCAL_FIX_REVIEW_REQUIRED`. Attendance, Guest
create/update and Venue confirm/change now resolve one canonical booking zone per operation and
reuse it for booking-hours validation, local-time interpretation, service/display date and number,
hold/deadline presentation, reminder scheduling and response/notification labels. A valid persisted
venue timezone still wins; missing, blank or invalid settings use `Europe/Moscow` through
`defaultBookingDisplayZoneId()`, so the host timezone no longer changes those booking semantics.
UTC production-route regressions assert persisted instants, display dates/numbers, deadlines,
reminder due times and labels at UTC/Moscow calendar boundaries. This is local validation only:
green Actions and deploy/staging evidence are not claimed. The billing-race isolation above remains
strictly test-only; production billing defaults and runtime behavior are unchanged.

The independent-review closure findings for this bounded timezone slice remain review-required:

- `BOOKING-TIMEZONE-TELEGRAM-CREATE-UPDATE-DOUBLE-RESOLUTION-001 = LOCAL_FIX_REVIEW_REQUIRED`.
  Telegram Bot booking create/update resolves the authoritative venue zone exactly once before the
  first timezone-dependent range check, then reuses it for the local instant, persisted schedule,
  service date/number, deadline, reminder, response and staff-chat notification.
- `BOOKING-CI-TZ-UTC-GATE-001 = LOCAL_FIX_REVIEW_REQUIRED`. The affected release-critical
  route/security and booking/RBAC GitHub Actions test steps declare and log `TZ=UTC`; unrelated
  jobs retain their existing timezone environment.
- `BOOKING-CI-STAFF-NOTIFIER-XML-FLOOR-001 = LOCAL_FIX_REVIEW_REQUIRED`.
  `backend-release-critical-routes` parses the exact
  `TEST-com.hookah.platform.backend.telegram.StaffChatNotifierTest.xml` report at minimum `40` and
  fails on a missing/below-minimum/zero, skipped, failed or errored suite.
- `BOOKING-TIMEZONE-REREAD-RACE-EVIDENCE-001 = LOCAL_FIX_REVIEW_REQUIRED`. A single Bot booking
  operation uses a resolver prepared to return Zone A and then Zone B, requires exactly one
  resolver call and proves every range/persistence/deadline/Telegram/staff-chat result remains in
  Zone A. Bot create preserves the lifecycle's `PENDING` state, so its authoritative reminder
  result is no `due_at`/status row and zero scheduling calls; the valid-zone Venue-confirm
  regression separately proves the actual pending reminder's due time and status.
- `BOOKING-TIMEZONE-VALID-PRECEDENCE-EVIDENCE-001 = LOCAL_FIX_REVIEW_REQUIRED`. With host
  `TZ=UTC`, a valid persisted non-Moscow venue zone wins over the Moscow fallback for the persisted
  instant, display date/number, deadline, pending reminder, response label and notification label.
- `BOOKING-TIMEZONE-ATTENDANCE-CONTRACT-EVIDENCE-001 = LOCAL_FIX_REVIEW_REQUIRED`. Production-route
  attendance regressions cover both a missing timezone/Moscow fallback and a valid non-Moscow
  timezone with one resolver call, matching response/staff-chat schedule and deadline semantics,
  and unchanged one-row outbox/dedupe behavior.

These statuses record local implementation and automated evidence only. They do not claim green
GitHub Actions, deploy, staging smoke or production readiness.

### Booking conversation automated regression matrix

The matrix retains the previous bounded integrity slice as historical regression evidence and adds
the current UX/unread closure gates; both remain mandatory where selected in CI:

- PostgreSQL V124 / H2 V125 migration wrappers: minimum `47 / 42` tests, zero skipped/failures/
  errors. Each wrapper executes the production migration and proves: clean/no-duplicate preservation;
  duplicate merge with no reads; merge with complete timestamp-identical reads; fail-before-domain-
  mutation for partial read coverage, conflicting read timestamps, ownership mismatch, null booking,
  missing booking, conflicting status, unexpected physical/normalized/JSON reference families and
  malformed, missing, non-integer, oversized, mismatched, aliased, nested-only, payload-only or
  unknown booking-audit shapes. Unicode-escaped known keys in audit and the 15 non-audit durable
  payload pairs are matched after valid-JSON normalization; mixed plain/escaped top-level
  `ticketId` duplicates and nested/escaped `conversationThreadId`, nested `thread_id`, array
  `ticketIds` and numeric-string references must fail unchanged. Unrelated `conversationStatus`
  must not false-stop. The five PostgreSQL-only inventory cases additionally reject a composite
  expected-looking FK, a non-`id` target FK, an external same-name source, an additional external
  source and a privilege-hidden cross-owner source while comparing domain/catalog/index/Flyway
  history snapshots. `BookingAuditReferencePolicyTest` minimum `6` independently holds the H2
  duplicate-aware semantic cardinality/extraction/remap policy, PostgreSQL normalized-key predicate
  and read-only deployment-preflight text to the same semantic matrix. Production-
  shaped audit payloads prove canonical `entity_id` + `ticketId` remap, unrelated numeric collision
  preservation and multi-row cardinality/order/timestamp/other-field preservation. Message facts,
  exact read timestamps and survivor metadata are compared, not only row counts.
- `BookingThreadIntegrityMigrationConcurrencyPostgresTest`: minimum `2`, zero skipped/failures/
  errors. Writer-first proves an independent read-marker PID blocks the real Flyway V124 PID and
  that the migration re-evaluates the committed unsafe state before mutation. Migration-first proves
  the inverse wait edge, a safe merge, one authoritative survivor marker and a stale duplicate
  writer resolving to `NOT_FOUND` without an orphan.
- `SupportThreadReadRepositoryTest` minimum `21` and
  `SupportThreadReadConcurrencyPostgresTest` minimum `6` form the mandatory
  `BOOKING-READ-LOCK-ORDER-001` gate: booking marks must follow
  `bookings -> support_threads -> support_thread_reads`, non-booking marks
  `support_threads -> support_thread_reads`, and no child-to-parent lock inversion may appear.
  The same suites prove that `last_read_message_id`, not timestamp comparison, is unread authority:
  the accepted exact read locks the canonical parent rows, snapshots `MAX(support_messages.id)`,
  advances the cursor monotonically and reads the returned detail in the same transaction. Real
  PostgreSQL cases cover writer-started-earlier blocking evidence, equal `created_at` ordering,
  concurrent monotonic reads, another-thread isolation and own-message exclusion. The repository
  floor includes the exact NULL-author projection/open case and NULL-author support/unrelated/
  unauthorized exclusion case; the PostgreSQL floor includes the production NULL-author
  `VENUE_CHAT` exact-open case with another actor's raw marker unchanged.
- Additive cursor migration wrappers `SupportThreadReadCursorMigrationH2Test` and
  `SupportThreadReadCursorMigrationPostgresTest`: minimum `4` each, zero skipped/failures/errors.
  They execute actual H2 V127/PostgreSQL V126 heads and assert nullable `BIGINT`, no default or
  backfill, preserved `last_read_at` plus primary-key facts, and the exact `(thread_id, id)` index.
- Additive Mini App message-schema wrappers
  `BookingMessageIdempotencyMigrationH2Test` and
  `BookingMessageIdempotencyMigrationPostgresTest`: minimum `5` each, proving exact nullable
  `VARCHAR(64)` / no-default metadata, legacy/Telegram NULL compatibility, source/author checks,
  exact scoped unique-column order and PG partial-predicate/H2 equivalent-index parity at the actual
  migration heads, with no unexpected semantic schema objects.
- `BookingConversationConcurrencyPostgresTest`: minimum `1`, zero skipped/failures/errors, with two
  independent production repository calls visibly blocked on the canonical booking row and one
  resulting thread. Post-state must prove both callers returned the same authoritative id, exact
  booking/venue/guest/type/status, and zero messages and relevant audits.
- `BookingConversationRepositoryTest`: minimum `14`, covering repeat convergence, distinct booking
  identity, Telegram retry idempotency, both message sources, authoritative actor/metadata checks,
  locked rejection of concurrently closed threads, positive generic-writer type allowlisting,
  ordinary chat/ticket regression, rollback and one-time Guest Bot transactional notification
  callback execution across replay.
- `BookingConversationRoutesTest`: minimum `9`, covering two guests/two venues/multiple bookings,
  Guest and Venue generic booking replies through the authoritative writer, direct privacy denials,
  safe idempotency replay/mismatch `409`, required/strict Mini App key validation, cross-surface
  booking-label parity, actor/thread-scoped unread aggregation/read clearing and the exact ordinary
  Guest wrong-surface/raw-marker/no-facts contract.
- `BookingDisplayLabelTest`: minimum `2`, loading the shared JSON case fixture and exercising the
  production Kotlin formatter for positive-number formatting, stable booking-id fallback, venue
  timezone conversion and deterministic product-default timezone fallback. The dedicated
  `booking-label-parity.spec.ts` minimum is `2`; it must load that same fixture through the
  production TypeScript formatter and pass every case in distinct browser timezones.
- `GuestBookingRoutesTest`: minimum `10`, retaining Guest booking list/create/update lifecycle and
  authoritative `displayLabel` coverage. CI also requires the exact missing-timezone and valid
  Honolulu attendance testcase names, so the two one-resolution route contracts cannot disappear
  behind the class floor.
- The same exact route/repository selectors must cover the read-only bounded reconciliation batch:
  more than 100 existing booking threads with the target beyond the former list cutoff, explicit
  `WITH_THREAD` and authoritative `NO_THREAD`, Guest and Venue scope, Staff and foreign denial,
  malformed/duplicate/oversized input rejection, complete one-result-per-request validation,
  repeated lookup, `Cache-Control: no-store` on exact/list/detail readers and zero
  thread/message/read/audit/outbox writes.
- `BookingMessageIdempotencyPostgresTest`: minimum `19`, covering Guest/Venue same-key replay,
  mismatch, different keys, atomic outbox rollback/recovery and lost-response replay. The contention
  case uses independent connections/PIDs and an observer that proves the waiting caller has an
  ungranted `pg_locks` row and exact caller A in `pg_blocking_pids` before release. Exact and
  canonical-equivalent booking envelopes replay; conflicting chat/method/payload envelopes fail
  closed and roll back. Assertions include one physical message/outbox/logical thread mutation and
  zero duplicate read markers/audits. New Guest Mini App/Bot coverage additionally requires one
  canonical-venue fact-only staff alert, no replay duplicate, safe missing/disabled configuration,
  policy-skipped staff groups, wrong-venue exclusion and full message/outbox rollback on strict
  enqueue failure. The legacy
  suspend enqueue remains key-only and is covered by `TelegramOutboxWorkerTest` minimum `13`.
- `BookingMessageStaffChatNotifierTest`: minimum `6`, executable without Docker and asserting exact
  canonical label/link payload, privacy-safe text, logical dedupe, caller-transaction rollback,
  disabled/unlinked/missing-URL/policy skip and stable product-timezone fallback.
- `StaffChatNotifierTest`: exact XML minimum `40`, with zero skipped/failures/errors; the lifecycle
  notifier must consume its caller-owned `ZoneId` without reading venue settings.
- `SupportTicketRoutesTest` remains an exact support regression selector with minimum `15` and zero
  skipped/failures/errors; its exact confirmed Platform Guest wrong-surface/raw-marker/no-facts
  testcase is mandatory.
- Existing booking/RBAC regression remains exact: `GuestBookingRoutesTest` minimum `10`,
  `BookingReminderWorkerTest` minimum `3`, `VenueBookingRoutesTest` minimum `11` and
  `VenueRbacRoutesTest` minimum `36`. The valid Honolulu venue testcase name is mandatory evidence
  for persisted schedule/deadline, pending reminder and guest-notification precedence.
- `TelegramBotRouterTableTokenTest` remains an exact production-router regression gate (current
  minimum `553`) and verifies persisted inbound Telegram message ids plus stable outbox dedupe keys.
  Its exact single-operation Zone A then Zone B testcase name is mandatory.
- Production Mini App build and the full deterministic browser smoke are required. The current
  structured floor is `216`. CI must require exact passing results for
  `shared booking label fixture stays aligned with the production TypeScript helper`,
  `authoritative booking labels do not change with the browser timezone`,
  `guest booking message keeps its client message id for unchanged manual retry after a lost response`,
  `guest booking message skips the staff alert when staff chat is not configured`,
  `guest conversation deep link rejects support ticket without clearing unread marker`,
  `guest support deep links reject booking and venue chats without clearing unread markers`,
  `guest exact surface deep links clear only the opened thread unread marker` and
  `guest ignores a late detail response from the previous surface`. The matrix
  also includes exact thread reuse/isolation,
  wrong-thread-type deep-link read guards,
  double-click convergence,
  commit-then-lost-response reload/reconciliation on both Venue dedicated and Guest generic
  surfaces, edit rotation, context invalidation, opening a resolved target instead of another
  active conversation, and a target existing beyond a mocked inventory of more than 100 threads.
  The >100-thread case must show the target messages, hide the first-thread action and issue no
  blind POST. A partial batch failure stays `ERROR/UNKNOWN`; refresh performs reads only and does not
  rotate/replay `clientMessageId`. CI writes the Playwright JSON report and cross-checks its suite outcomes
  against `stats`; at least `216` tests must execute with zero
  unexpected, flaky, skipped, runner-error, missing-result, non-passing-expectation or failed-attempt
  entries. A missing, malformed or structurally inconsistent report fails the job.

`BOOKING-CI-PLAYWRIGHT-FLAKE-001` remains `OPEN`: an earlier structured run executed `197/198`
after one unrelated favorite-test failure. This pass first executed `205/206` after one unrelated
catalog-debounce timing failure; its focused rerun and the second full structured `206/206` run
passed. The discoverability slice first executed `207/208` after the same unrelated catalog
virtual-clock debounce flake; its isolated repeat and the second full structured `208/208` run
passed. The subsequent wrong-thread-type read-guard run then passed a structured `209/209` with zero
unexpected, flaky or skipped results. The cursor/label/notifier slice then passed the raised
structured floor at `212/212`, again with zero unexpected, flaky or skipped results. Green reruns do
not erase these flake signals. The current Guest surface/deep-link/stale-response closure then
passed the raised full structured floor at `216/216` with zero unexpected, flaky or skipped results.
Revisit the finding in the next Mini App CI-hardening pass or after a repeated same failure in
GitHub Actions.

CI must assert the exact JUnit XML files and zero skipped/failures/errors. A selector that discovers
fewer tests must fail the job. PostgreSQL/Testcontainers checks may not be treated as optional or
green through skips.

### Booking conversation labels, inbox and unread quality gate

The bounded discoverability slice adds these regression requirements without reopening the
V124/V125 migration, thread-uniqueness or broad outbox design verdicts:

- two bookings for the same Guest, venue and service day at different times keep different booking
  ids, authoritative display numbers and threads; Guest booking, Venue booking and conversation
  list/detail DTOs return the same stable venue-local label before and after reload, sorting and
  filtering;
- missing display number falls back to stable `booking_id`, never DOM/list position or a generic
  `Бронь 1`;
- Venue conversation lists contain only the requested `BOOKING_THREAD` plus `VENUE_CHAT`, order
  unread first then `last_message_at DESC` then `thread_id DESC`, and expose bounded preview/time,
  type and actor-scoped unread count;
- `GET /api/venue/{venueId}/support/unread-count` is Owner/Manager own-venue scoped and counts only
  unread `BOOKING_THREAD` plus `VENUE_CHAT` messages. `SUPPORT_TICKET`, foreign venues and Staff are
  excluded before message facts;
- exact detail read clears only the exact actor/thread marker, another thread and another actor are
  unchanged, and repeat reads remain idempotent. A wrong-queue exact deep link is type-clamped and
  rejected before read-marker mutation;
- `support_thread_reads.last_read_message_id` is the sole unread authority. `last_read_at` is
  wall-clock metadata only and may not appear in an unread predicate. Existing marker rows remain
  nullable with no backfill; therefore a NULL cursor leaves every foreign-authored message unread.
  User-visible rows with `author_user_id = NULL` are system messages and foreign to every actor;
  they count on the exact thread/card and in the aggregate Venue conversation badge for
  `BOOKING_THREAD` / `VENUE_CHAT`, then clear through the same authoritative open. Internal-only rows
  must be excluded by visibility, never by weakening the unread predicate.
  Accepted open snapshots `MAX(message.id)` under the canonical parent locks, advances only
  monotonically and returns marker plus detail/messages from the same transaction. Unread is exactly
  `author_user_id IS DISTINCT FROM actor_user_id` plus `message.id > cursor` (or a NULL cursor);
  own messages never count;
- Guest route regressions must prove the fixed server-owned surface contract:
  `CONVERSATIONS = {BOOKING_THREAD, VENUE_CHAT}` and `SUPPORT = {SUPPORT_TICKET}`. For both ordinary
  authenticated Guest and confirmed Platform Owner Guest-context calls, each wrong-surface case
  returns the current privacy-safe rejection, discloses zero message body/detail facts and leaves
  raw `support_thread_reads(thread_id, user_id, last_read_message_id, last_read_at)` unchanged.
  Correct-surface cases update only the exact marker. The repository transaction must validate the
  locked type before `MAX(message.id)`, marker/audit/outbox mutation or detail read; a client cannot
  supply an arbitrary type set.
- one newly committed Guest booking message creates at most one canonical-venue staff-chat alert
  outbox row when Telegram, staff-chat binding and exact Mini App URL are available. Replay creates
  no second logical alert; missing/disabled configuration creates none; wrong-venue context cannot
  choose a recipient; transaction failure rolls back message plus outbox rows. Alert payload and
  logs contain no raw booking-chat text;
- notification validation is explicitly tiered: backend integration owns outbox cardinality,
  transaction rollback, retry/replay and unlink serialization; Playwright owns visible badges,
  exact deep-link/callback navigation and safe mock alert counters; only a bounded staging smoke can
  prove real Telegram delivery to a linked staff chat;
- deterministic Mini App smoke identifies cards/threads by stable data attributes, never
  `.first()` / `.nth()`, and covers two distinct booking labels and exact threads, nav/card unread,
  exact-read clearing, booking-only Support emptiness, venue/account switch isolation, reload and
  exact callback/deep-link navigation;
- focused backend selectors, exact JUnit XML, Mini App production build, full structured Playwright
  smoke, Kotlin compile/lint, workflow YAML parse, `git diff --check` and `git status --short` are
  mandatory before recording local validation. Runtime/staff-chat behavior still requires green
  Actions, staging redeploy and a fresh real Telegram/Mini App smoke; local success is not staging
  evidence.

#### PostgreSQL V126 controlled mixed-version release and smoke boundary

This is a required future release check, not evidence that green Actions, a deploy, migration or
staging smoke has run.

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
preserves `last_read_at` and primary key `(thread_id, user_id)`, and applies only in H2/local/test
environments. Staging PostgreSQL applies PostgreSQL V126, never H2 V127.

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
`DB_USER = POSTGRES_USER` and the Compose network. Apply the fail-closed runbook predicate: inspect
every `client backend` on the current database/user except the gate session itself and classify it
using recorded old-container PID/client address and Compose network, observed `application_name`,
and individually proved operator PIDs. `idle` is not drained. Any unidentified row is
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

HT-11's pre-V126 V125 dry run is closed as
`HT11_CLOSED_PASS_WITH_EXPLICIT_LIVE_LIMITATION`. Its completed live direction proved one exact
Owner reply, the corresponding Guest unread creation and exact Guest-open clearing, without
duplicate markers or cross-scope mutation. Collision A/B remain retained as
`HISTORICAL_V125_COLLISION_EVIDENCE`. The reverse live direction remains exactly:

`LIVE_V125_GUEST_REPLY_TO_OWNER_UNREAD_CLEAR = NOT_EXECUTED_OPERATIONALLY_BLOCKED`

Recorded `VenueBookingRoutesTest` and Mini App `guest-smoke.spec.ts` coverage is
`AUTOMATED_REGRESSION = PASS`, but automated evidence does not turn the unexecuted real-client V125
assertion into a live PASS or waiver.

The post-V126 smoke therefore includes the mandatory non-substitutable gate
`HT14_MANDATORY_LIVE_GATE_GUEST_REPLY_OWNER_UNREAD_CLEAR`. It must prove:

- one exact Guest reply creates exactly one persisted Guest message;
- exactly one expected Telegram/outbox delivery occurs;
- Owner unread is created only for the exact booking thread;
- one exact Owner open clears only that unread;
- no duplicate marker row or unread resurrection occurs;
- no other-thread, CLIENT or non-MIX state changes.

HT-14 may not conclude PASS while this gate is pending, waived or supported only by automated
evidence.

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

The associated findings `BOOKING-UNREAD-NULL-AUTHOR-001`,
`BOOKING-UNREAD-GUEST-TYPE-GUARD-001` and `BOOKING-UNREAD-MIXED-VERSION-ROLLOUT-001` remain
`LOCAL_FIX_REVIEW_REQUIRED`. The epic stays **BOOKING CONVERSATION UX / DISTINCT LABELS, INBOX AND
UNREAD DISCOVERABILITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE
COMMIT**.

Before the previous integrity V124/V125 migration, run the exact read-only PostgreSQL preflight from
`docs/DEPLOYMENT_RUNBOOK.md`. Stop under `STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION` if any
unsafe section returns a row: null or missing booking, canonical venue/guest mismatch, conflicting
duplicate status, partial read coverage, conflicting read timestamps, malformed/mismatched/unknown
audit shapes, missing lock privilege, or an unknown physical/normalized/JSON reference family.
Duplicate-group, survivor-order and expected message/read/audit remap rows are
informational evidence and must be reviewed against the release inventory. Do not merge/delete
production rows under an inferred status or read-state policy.

Current practice:
- CI is split into smaller backend jobs, Mini App build/e2e, compose and Docker image checks.
- Local broad Gradle wildcards can hit heap/runtime limits; prefer focused selectors first.
- Manual real Telegram/staff-chat smoke remains required for bot/staff-chat behavior changes.
- Shared initial menu bootstrap has green focused backend, deterministic PostgreSQL, production
  Mini App build and full Playwright evidence. User-confirmed green Actions, staging deploy and
  consolidated Mini App-first/Telegram-first smoke close the bounded release.
- Guest Favorites Phase 1 is `DONE / MVP / STAGING-SMOKE-PASSED`: focused backend favorites tests, `compileKotlin`, `ktlintCheck`, Mini App build and full e2e smoke `62/62` passed locally; GitHub Actions were green and manual staging smoke covered Mini App, Telegram parity, isolation and availability restoration.
- Repeat as Template Phase 1 is `MVP IMPLEMENTED / LOCAL VALIDATION PASSED / DEFERRED MANUAL SMOKE`. Its environment-dependent production-readiness scenarios remain `BLOCKED_BY_ENVIRONMENT` in [`REPEAT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#repeat-manual-001); this does not mark them passed or block independent bounded development.
- Simple Venue Promotions Phase 1 is `DONE / MVP / STAGING-SMOKE-PASSED`: GitHub Actions
  were green and manual staging smoke covered Owner/Manager/Staff RBAC, current-period Guest
  visibility, unavailable-venue filtering, informational-only totals and Telegram/Mini App state.
- Executable Promotions Phase 2 is
  `EXECUTABLE PROMOTIONS PHASE 2 / HAPPY HOURS PERCENT SLICE / DONE / STAGING-SMOKE-PASSED`.
- Gift parity is
  `GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`.
  GitHub Actions and staging cross-surface smoke remain required.
- Promotion creation audit is
  **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.
  Focused H2 repository/routes, Telegram router and real PostgreSQL transaction coverage remain
  regression gates; the bounded cross-surface audit/privacy/rollback staging smoke is recorded passed.
- Promotion effective state clarity is
  **PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**.
  It changes presentation only: lifecycle status remains authoritative and no automatic lifecycle
  mutation, worker or audit was added.
- Promotion lifecycle status audit is
  **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.
  This closes only promotion status/archive lifecycle audit; broader dangerous-action coverage
  remains partial.
- Staff role/removal audit is
  **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.
  Local H2/PostgreSQL, repository, route, Telegram, privacy, rollback and deterministic concurrency
  gates are recorded green, and the bounded staging role/parity/privacy smoke is recorded passed.
- Venue Mini App Guest Preview Phase 2.1 is **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**. Focused preview/Guest/RBAC/promotion backend tests, compile/lint, Mini App build and deterministic browser smoke `95/95` are green; GitHub Actions were green, staging deploy completed and manual staging smoke passed for the unified contract.
- Menu shift check is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.
  OWNER/MANAGER use an own-venue local draft and one atomic availability batch; Staff individual
  stop-list policy is unchanged. GitHub Actions were green, staging deploy completed and the
  functional/UX manual smoke passed.
- Menu item hard-delete audit is **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT /
  DONE / MVP / STAGING-SMOKE-PASSED**. Green Actions for release HEAD `822233c`, staging deploy and
  the bounded Mini App/Telegram blocked/allowed smoke are recorded complete; broader Menu and
  Dangerous Action Audit coverage remains partial.
- Catalog search/filter is
  **CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**. Focused
  `GuestVenueRoutesTest`, backend compile/lint, Mini App build, focused catalog/favorite browser
  checks and full deterministic browser smoke `104/104` passed locally; GitHub Actions were green,
  staging deploy completed and manual staging smoke passed on the current limited venue dataset.
  Extended multi-venue coverage remains **NON-BLOCKING DEFERRED MANUAL SMOKE /
  CATALOG-SEARCH-MANUAL-001** and does not downgrade the completed MVP.
- Staff identity linking is
  **STAFF IDENTITY LINKING UX + DUPLICATE PREVENTION / DONE / MVP / STAGING-SMOKE-PASSED**.
- Staff Schedule is
  **STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.
  Staff Operations Slice A is
  **MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP / STAGING-SMOKE-PASSED**. Canceled Shift
  Restore + Bulk Assignment is **DONE / MVP / STAGING-SMOKE-PASSED**. The Phase 1 schedule schema
  remains **NO_MIGRATION_EXPECTED**; Identity Linking used the existing transaction-bound member
  lock; invite revoke uses PostgreSQL V120/H2 V121. Green Actions, deploy and manual staging smoke
  are complete.
- Staff Operations Slice B is
  `STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`. Green GitHub Actions, staging deploy,
  PostgreSQL V121 application and the bounded manual smoke are complete.

Target QA model:
- Every task ends with changed files, behavior summary, tests run, validation result, manual smoke checklist, `git status --short`, whether `scripts/dev/` was touched and whether staging deploy is needed.
- GitHub Actions must be green before release/merge.
- Staging smoke is required for runtime behavior changes that affect guests, venue operations, Telegram/staff-chat, billing/security, migrations or deployment.

## QA Levels

| Level | Purpose | Current / target |
| --- | --- | --- |
| A. Static / local sanity | Catch whitespace, accidental files, staged mistakes. | Always run `git status --short` and `git diff --check`; before commit also run cached checks. |
| B. Backend targeted tests | Validate changed backend contracts with small selectors. | Required for backend/RBAC/security/Telegram/billing/order/booking/support/menu changes. |
| C. Backend compile/lint | Prove Kotlin compiles and formatting passes. | `:backend:app:compileKotlin` and `:backend:app:ktlintCheck` for runtime backend changes. |
| D. Mini App checks | Prove production bundle and browser smoke. | `npm --prefix miniapp run build` and e2e smoke for frontend/user-flow changes. |
| E. Manual staging smoke | Prove real environment, Telegram WebView, staff-chat and deploy behavior. | Required after runtime/frontend/backend/Telegram/deploy changes; not required for docs-only. |
| F. GitHub Actions | Release gate and source of CI truth. | Must be green before considering a task merged/released. If red, report failing test/assertion first, not Gradle tail. |

## Platform Owner Controlled Guest QR Test Escape Quality Gate

Status: **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**. Schema verdict: `NO_MIGRATION`. Commit/push, green Actions for the release HEAD, staging deploy and the bounded real Telegram role/privacy/exit smoke are complete. This closes only the controlled single-instance Phase 1 slice and does not declare the whole product production-ready.

Required automated evidence:
- Platform Owner tokenless `/start` opens Platform Mode only without active confirmed Guest context. With active context it keeps Guest routing and shows the table menu or safe `Завершить визит` instruction. A new QR prompt does not mutate current context/session, exit marker, persisted dialog, booking draft, cart/draft or success audit.
- The five-minute process-local pending uses a short opaque callback reference bound to exact actor/chat/token/venue/table, lazily sweeps expired entries and is removed by cancel/confirm/Guest exit/clear. Phase 1 is single-instance long-polling; restart, a callback on another instance, wrong actor/chat, expiry and missing/consumed references fail closed.
- Deterministic confirm-vs-cancel and double-confirm tests prove one conditional-consume winner without sleeps: cancel winner has no audit/activation; confirm winner has one audit and one activation attempt.
- Confirm re-authorizes exact Platform Owner and commits safe `PLATFORM_GUEST_QR_TEST_CONFIRMED` before activation. The event records confirmation only and is not `GUEST_CONTEXT_APPLIED`; audit failure produces no Guest context and retry requires a fresh QR confirmation.
- Real repository transaction tests inject failures after session resolve/touch, exit clear, dialog clear and at context save. Final token/venue/table identity and public Guest/subscription guards, session resolve/create/touch, exit clear, dialog clear and exact context save are one transaction; every late failure leaves session, exit, dialog and context unchanged. In-memory cart/draft cleanup follows commit only, and raw SQL failures are normalized to safe copy.
- The H2 activation rollback matrix executes all 16 reachable `NEW|EXISTING session × INSERT|UPDATE context × four checkpoints` scenarios and compares the full authoritative snapshot, including unrelated rows. The PostgreSQL Testcontainers gate executes the required `NEW+INSERT` and `EXISTING+UPDATE` branches at all four checkpoints: 8 scenarios, `skipped=0`.
- Exact Platform Owner Mini App create/touch and explicit-session resolve require matching active server-owned Telegram chat context and no exit marker. Missing/mismatched token/venue/table context and old token/session/button after exit cause no session touch/create, personal-tab creation or exit-marker clearing. Ordinary Guest resolve remains unchanged.
- Deterministic DB-backed tests use latches, the production coordinator and production connection overloads for order, staff-call, tab, shift-extension and support. They prove exit-first denial with unchanged authoritative counts, mutation-first commit before teardown, post-exit denial and full rollback of a forced SQL failure; no arbitrary sleeps or mock-only authorization proof are used.
- Exact Platform table-bound support create, detail read-receipt, reply and status flows cover confirmed token+session, confirmed token-only, missing confirmation, mismatched context and post-exit denial. Denials share one private error and leave ticket/thread/message/read/audit/session/tab state unchanged; ordinary Guest token-only support remains compatible.
- Availability-independent teardown uses stored actor/chat context only as cleanup identity, clears context/dialog/cart/draft/pending and preserves exit semantics after token rotation/revoke, table disable/delete, venue pause/unpublish or subscription block, then returns Platform menu. New QR + confirm may re-enter.
- Confirmed routing uses ordinary Guest menu/order/staff-call/session paths and Guest Mini App URL `mode=guest`; ordinary Guest, Venue Owner, Manager and Staff precedence remains unchanged.

Recorded release evidence for current release HEAD `d7eb5c5a268d10c1fbcf06137833a3f23b3c128c`:

- the required route/security gate completed successfully with `discovered=911`, `executed=911`,
  `skipped=0`, `failures=0`, `errors=0`;
- required selectors include `TelegramBotRouterTableTokenTest`, `TelegramKeyboardsTest`,
  `GuestTableResolveRoutesTest`, activation/teardown tests, the mutation coordinator, pending
  confirmation store, Guest order/tabs/staff-call/shift-extension/support and venue/platform route
  regressions;
- the PostgreSQL rollback gate completed with `executed=8`, `skipped=0`, `failures=0`, `errors=0`;
  CI fails on missing XML, zero tests, any skipped/failure/error result or fewer than eight rollback
  scenarios;
- two earlier CI failures were test-only timezone-fixture defects caused by
  `ZoneId.systemDefault()`. No production defect was found; the fixture now returns its explicit
  fallback and the `TZ=UTC` regression passed;
- GitHub Actions completed successfully, the release was deployed to staging and exactly one
  backend instance served the bounded smoke.

Recorded manual Telegram staging smoke (`PASSED`):

1. Platform `/start` without token returned the ordinary Platform menu.
2. A valid table QR showed the confirmation prompt.
3. Cancel created no Guest context.
4. Confirm entered the ordinary Guest flow.
5. Guest Mini App opened with `mode=guest`.
6. Table-bound menu/order/tabs/staff call/Support worked.
7. Shift extension was covered by the permitted automated evidence because staging state did not
   allow the manual scenario.
8. `Завершить визит` returned to Platform Mode.
9. The old Mini App link after exit failed closed.
10. A new QR required a new confirmation.
11. Replayed old confirm/cancel callbacks were denied.
12. Ordinary Guest regression passed.
13. Venue Owner/Manager/Staff regression passed.
14. One backend instance was confirmed.
15. Test Guest context and actions were cleaned up.

The release added no migration: `NO_MIGRATION`. It reuses the existing table-token, table-session,
chat-context, dialog, user-exit and audit tables.

Required local commands:
```bash
git status --short
git diff --check
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramKeyboardsTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestTableResolveRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestTable*Activation*' --tests '*GuestTable*Teardown*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*PlatformGuestTableMutationCoordinatorTest*' --tests '*GuestOrderRoutesTest*' --tests '*GuestTabsRoutesTest*' --tests '*GuestStaffCallRoutesTest*' --tests '*ShiftExtensionRoutesTest*' --tests '*SupportTicketRoutesTest*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 _JAVA_OPTIONS=-Xmx4g ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestTableContextActivationPostgresTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

## Staff Schedule Phase 1 Release Quality Gate

Status:
**STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.
Slice A status:
**STAFF OPERATIONS SLICE A / MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP /
STAGING-SMOKE-PASSED**.
Schedule and identity-linking schema verdict: **NO_MIGRATION_EXPECTED**. Restore + Bulk Assignment
is **DONE / MVP / STAGING-SMOKE-PASSED**; identity linking is also **DONE / MVP /
STAGING-SMOKE-PASSED** and changes no Schedule calculation/lifecycle, Today Staff or Guest source.
The complete acceptance matrix remains canonical in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`.
PostgreSQL V120/H2 V121 belongs to Slice A invite revoke, not Restore + Bulk Assignment; its rollout
is included in the completed release evidence.

### Staff Operations Slice B Release Quality Gate

Status:
`STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`.

The additive settings migrations, runtime repository/API/CAS/audit, narrow RBAC, fail-closed module
guards, shared MANUAL/SCHEDULE Guest resolver and bounded Mini App UX are implemented. Local
targeted evidence covers PostgreSQL/H2 defaults and source check, monotonic full-object CAS,
Owner/Manager settings routes, Staff/foreign/Guest/Platform denial, tenant-before-state behavior,
transaction-bound audit and rollback, retained-data disable/re-enable, unchanged core access,
MANUAL exact-date publication, SCHEDULE ACTIVE `[start,end)` timezone/overnight/DST behavior with
no fallback, Guest/Preview privacy/parity and venue-switch isolation.

Recorded local validation:

- targeted `*VenueStaffModuleSettings*`, `*VenueStaffRoutesTest*`, `*VenueRbacRoutesTest*`,
  `*GuestVenueRoutesTest*` and `*VenueGuestPreviewRoutesTest*` selectors passed;
- `:backend:app:compileKotlin` and `:backend:app:ktlintCheck` passed;
- `npm --prefix miniapp run build` passed;
- the real PostgreSQL migration smoke executed V120→V121 with `skipped=0` and `failures=0`;
- focused Slice B browser checks and the exact full Mini App e2e smoke passed (`131/131`).

That historical Slice B evidence does not attest the current onboarding/ownership worktree; its
independent review remains a separate release gate below.

Release/staging evidence:

- GitHub Actions completed green and staging deploy succeeded;
- staging PostgreSQL applied V121 after the Testcontainers V120 -> V121 run reported
  `skipped=0`, `failures=0`; H2 V122 remains the test-family counterpart;
- exactly one new backend instance served settings mutation and no old backend instance remained;
- the 13-scenario manual smoke passed: defaults, Owner persistence, Manager narrow authority, Staff
  denial/navigation, stale CAS, MANUAL persistence, Guest visibility-off, master-off retained
  data/core access, re-enable, SCHEDULE active-only/no-fallback, privacy, venue/account isolation and
  cleanup;
- cleanup restored module enabled, Guest visibility enabled, source `MANUAL`, the original manual
  Today state and core Staff access.

The exact scenario-by-scenario record remains in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Old-binary
rollback after real `source=SCHEDULE` use is semantically unsafe; release handling remains
forward-fix.

Locally validated backend coverage:

- Manager creates Staff but not Manager/Owner/Admin invites, sees/revokes pending Staff only; Owner
  retains Staff/Manager create/list/revoke, and Staff/foreign/Guest/Platform-only is denied;
- invite pending predicates, revoked preview/accept/decline failure, used/expired/repeated revoke,
  controlled accept-vs-revoke race, secret-safe transaction-bound create/revoke audit and rollback;
- Manager display-only/Staff-linked profile create/edit/publish/hide, protected Owner/other-Manager
  denial, safe own edit, invalid/foreign linkage denial, Owner/Staff regression and safe
  transaction-bound audit with no denial/no-op/rollback success row;
- accepted Staff member projection from the existing `users` identity row, including trimmed
  display name, present `username`, safe missing-username fallback and no second identity cache;
- pending invite projection with role/status/created/expires only in the visible UI and no invented
  recipient identity; accept removes pending state and exposes the fresh active member/link state;
- Manager receives active Staff identities/link targets only, while Owner retains the current
  permitted active-member projection; Staff/Guest/foreign/Platform-only directory reads are denied;
- member/profile-link DTOs exclude phone, invite secrets, raw `initData`, private notes/audit
  metadata and any Telegram/member fields from Guest DTOs;
- private profile raw bodies use server-computed `linkageClass/canManage/isSelf`; Manager
  Owner/Manager/orphan/duplicate projections redact raw linkage, Staff self uses `isSelf`, Owner
  retains the broader current private contract and protected errors reveal no target metadata;
- create-from-member accepts only `userId`, required `subtype` and compatible `roleLabel`, re-reads
  target membership/current `users` identity, creates one active Guest-hidden draft and never treats
  a client display name or visibility flag as authority; generic create rejects linked writes;
- one-active-link enforcement for create/relink/reactivation under target `venue_members FOR UPDATE`,
  typed existing-profile conflict and no success audit on denial;
- PostgreSQL Testcontainers drives two real HTTP transactions through concurrent create-from-member
  and relink scenarios, proves both wait on the target membership lock via PostgreSQL lock state,
  uses no arbitrary sleep and requires exactly one winner/one typed conflict/winner-only audit;
- pre-existing multiple active links report `DUPLICATE_LINK_DETECTED` and remain distinct; Manager
  sees the duplicate state read-only, while Owner repairs it by opening the concrete wrong card in
  the common card list and using safe unlink; no automatic merge/delete/relink and no
  Schedule/self-view dedupe;
- weekly/exception/overnight/closed/not-configured effective-hours response, venue timezone,
  batched range semantics and explicit error propagation; shifts outside hours remain allowed;

- Owner and Manager bounded create/update/cancel in their own venue;
- Staff mutation, Guest, foreign venue and Platform-only denial;
- Staff own-shift read plus safe non-canceled overlapping colleagues, with unrelated shifts and all
  of the requester's own linked profiles excluded from colleague projections;
- colleague DTOs may include the safe profile identifier `staffProfileId` plus display/schedule
  fields, but exclude shift-row ids, linked user/account ids, Telegram fields, `updatedAt`,
  guest-visibility flags and admin/mutation metadata; display-only profiles have no self-view;
- venue-local interpretation with explicit `Europe/Moscow` fail-safe, browser/system timezone
  independence, DST handling and no trusted client offset/status;
- overnight and inclusive 24-hour maximum; non-positive and over-24-hour rejection;
- future-only create/update, 90-day create horizon, required bounded `from/to`, 31-day maximum query
  and 30-day recent/90-day future read envelope;
- one profile/start-date conflict, including concurrent create and date-changing update mapping;
- computed scheduled/active/completed, stored canceled, no lifecycle worker, completed immutability,
  explicit future canceled restore and active cancel-only policy;
- cancel confirmation carries a non-authoritative expected confirmation state; crossing
  `SCHEDULED -> ACTIVE` after preview is rejected and requires the stronger active confirmation;
- expected-`updatedAt` stale rejection and no-op behavior; every real Schedule/related Today write
  advances the round-tripped token, and two mutations with one token commit exactly one;
- exactly one transaction-bound safe `STAFF_SHIFT_CREATED`, `STAFF_SHIFT_UPDATED`,
  `STAFF_SHIFT_CANCELED` or `STAFF_SHIFT_RESTORED` audit for a successful real mutation and none for
  no-op/denial/error/rollback;
- planned times survive a Today request that omits them, schedule rows stay guest-hidden, an engaged
  Today overlay blocks Schedule date/time moves, and current Staff Profile/Today Shift/Guest
  `Сегодня работают` behavior remains unchanged.
- any complete row invalid under the current venue timezone/DST/duration rules fails closed for
  Staff overlap/self reads and returns a neutral safe Owner/Manager warning/repair contract instead
  of guessing its origin, crashing or silently reinterpreting it; future-date rows follow
  repair/cancel, venue-today rows cancel-only and past rows read-only.
- future canceled restore with saved and new times keeps the same `shiftId`, one database row,
  schedule visibility defaults and an advanced CAS token;
- restore rejects stale, scheduled, active, completed, past canceled, Today-overlay, Staff, foreign,
  Guest and Platform-only requests without false audit or safe-row disclosure;
- `STAFF_SHIFT_RESTORED` has the safe old/new interval/lifecycle payload and audit-column actor;
  forced audit failure rolls the restore back;
- ordinary authorized create classifies canceled/scheduled/active/completed conflicts safely, while
  foreign actors receive no existing-row details;
- 1..50 assignment batch supports common/per-profile intervals and mixed `CREATE`/`RESTORE`; one
  database transaction rejects duplicate slots, missing/foreign profiles, invalid interval, stale
  restore or one lifecycle conflict and rolls back every write and audit;
- deterministic lock order is profile id, then profile/date row, then writes, per-row audits and
  commit; concurrent create/restore has one deterministic winner without process-local locks or
  arbitrary sleeps;
- the existing single-profile create, individual edit/cancel/CAS, planned/manual Today and Staff
  self-view regressions remain green.

Locally validated Mini App/e2e coverage:

- Owner `График смен` week list/editor and Manager parity;
- Staff read-only `Мои смены` with overlap-only colleagues and no admin controls;
- accepted employee display name, `@username`/`Без username`, role badge and link status in
  `Доступ сотрудников`, with full raw id never used as the primary label;
- `Создать карточку` preselects the correct active member and safe display name; one linked profile
  changes the row action to `Открыть карточку` and removes/disables that member in other selectors;
- duplicate-link warning uses the canonical copy and keeps every distinct profile/shift visible;
- Manager sees Staff actions but no editable Owner/Manager targets; Owner controls are preserved;
- venue switch and account switch abort/clear member identity, linkage, profile form and late
  response state; Staff/Guest receive no internal directory;
- week navigation, timezone copy and overnight rendering;
- loading, optional empty, retryable error, update preview, cancel confirmation and active warning;
- stale conflict offers refresh and never overwrites current state;
- venue switch aborts/clears old data and ignores late responses; selected-venue persistence is
  restored only after fresh access-list validation;
- direct Staff mutation denial plus existing Staff Profiles/Today Shift and Guest regression.
- multi-select Staff/display-only profiles, common effective hours, apply-to-all, per-employee
  override and employee removal;
- explicit canceled-row restore choice, scheduled/active/completed blocking reasons and exact
  create/restore confirmation counts;
- one normalized batch request, atomic-error unchanged state, success week refresh and retained
  canceled historical row with restore action;
- Staff has no restore/batch controls, existing individual edit/cancel remains, and venue switch
  clears selection/draft/confirmation/stale response.

No Telegram behavior is added. Do not add reminders, outbox events, buttons, staff-chat messages or
Telegram mutation UI to satisfy this gate.

Recorded local gate:

```bash
git status --short
git diff --check
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueStaffRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueRbacRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueRoutesTest*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*StaffProfile*Concurrency*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

The commands above are the required current-worktree gate for Staff Identity Linking UX Polish and
the unchanged Restore + Bulk Assignment regression. Record the exact results in the implementation
handoff; do not reuse an earlier Slice A browser count as proof for this slice.

The PostgreSQL selector is proof only when Gradle reports executed tests greater than zero,
`skipped = 0` and `failures = 0`; a Docker/Testcontainers assumption skip is not a pass.

Release result: green GitHub Actions, staging deploy and manual smoke passed for Owner/Manager/Staff
identity and schedule boundaries, username/missing-username labels, duplicate Owner-only repair,
venue/account isolation, saved/new-time restore, common/per-person and mixed atomic batch, typed
conflicts, timezone/overnight, Staff privacy, unchanged Today/Guest behavior and cleanup. A separate
free-Staff-account create-from-member UI path was not evidenced and is tracked as the non-blocking
[`STAFF-IDENTITY-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#staff-identity-manual-001); it does
not downgrade Identity Linking MVP.

## Catalog Search And Filter Phase 1 Quality Gate

Status:
**CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.

Backend coverage must prove:

- optional `q` and `city` are trimmed, blank is equivalent to absent and values over 100
  characters fail validation without silent truncation;
- `q` matches name, city, address and formatted address case-insensitively; `city` uses a
  case-insensitive exact match; combined filters use `AND`;
- prepared parameters plus explicit escaping treat `%`, `_`, `!` and `\` literally, SQL-like input
  cannot alter query semantics, and behavior stays PostgreSQL/H2 compatible;
- the same query retains `PUBLISHED` lifecycle and guest subscription/availability guards, stable
  deterministic ordering, safe DTOs, authenticated access, current-user `isFavorite` and today
  schedule/open state;
- favorites and schedule remain batch-enriched without per-venue N+1 reads, two users receive
  isolated favorite state, unavailable venues disclose no card data and empty results are stable.

Mini App/browser coverage must prove:

- search sends encoded `q`, city selection sends encoded `city`, and both are sent together;
- a fixed 300 ms debounce avoids an immediate request per keystroke;
- query/filter replacement and screen disposal abort pending work, and only the latest response can
  update the catalog even when an older response completes later;
- city options use the initial complete unfiltered guarded response. The endpoint has no limit or
  pagination; blank cities are removed, case-insensitive duplicates preserve normal display
  spelling and the final list is sorted predictably;
- initial loading, retryable error, base-catalog empty and
  `По вашему запросу ничего не найдено` are distinct, and reset clears both `q` and `city`;
- optimistic favorite add/remove works inside filtered results, a filtered reload cannot restore a
  stale backend favorite value, out-of-result mutations stay safe and account switching clears the
  previous user's query/filter/favorite state;
- existing venue-card open, booking, ask-question, schedule and pre-QR menu-separation actions stay
  green. Tests wait for observable requests/state and do not use arbitrary sleeps.

Required local commands:

```bash
git status --short
git diff --check

./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*GuestVenueRoutesTest*' --console=plain

./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain

npm --prefix miniapp run build

CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 \
npm --prefix miniapp run e2e:smoke
```

Recorded local evidence: focused `GuestVenueRoutesTest`, backend compile, ktlint, Mini App
production build, focused catalog/favorite browser checks and the prescribed deterministic browser
smoke `104/104` passed. GitHub Actions were green, staging deploy completed and the current
limited-dataset manual staging smoke passed for initial catalog, name/city/address search, city
filter, combined `q + city`, no-match/reset, literal special characters, filtered favorites,
schedule enrichment, unavailable-venue non-disclosure and venue-card regressions.

The completed Phase 1 status does not claim an extended dataset smoke. Multi-result ordering,
case-insensitive city deduplication, larger-set latest-response-wins, two-account favorite state
and hide/publish restoration remain **NON-BLOCKING DEFERRED MANUAL SMOKE /
CATALOG-SEARCH-MANUAL-001** in
[`docs/DEFERRED_MANUAL_SMOKE_BACKLOG.md`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#catalog-search-manual-001).
That entry is required before catalog pagination, ranking, map/geo or a large pilot rollout.

## Venue Mini App Guest Preview Phase 2.1 Quality Gate

The Venue Mini App has one `Предпросмотр для гостя` entry and one read-only Venue endpoint. The
server, never cached client membership state, selects `PUBLISHED_PUBLIC` or `PRIVATE_DRAFT`.
`PUBLISHED_PUBLIC` must reuse the exact Guest venue/info DTO assembly and unchanged
availability/subscription guards. `PRIVATE_DRAFT` must be an OWNER/MANAGER own-venue,
server-allowlisted projection of saved guest-facing state. A query bypass on a Guest route, a
private settings DTO, client-side public/private merge or client-selected visibility mode is a
release blocker.

Required local coverage:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueGuestPreviewRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueRbacRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Local result on 2026-07-30: all four focused backend test selectors passed, Kotlin compile and lint
passed, the Mini App production build passed, and deterministic Playwright smoke passed `95/95`.

Staging result on 2026-07-30: green GitHub Actions and the staging deploy were followed by manual
smoke for published/private server-selected modes, saved-state/dirty-form behavior, OWNER/MANAGER
allow plus STAFF/foreign denial, privacy/child visibility, read-only action absence, origin-aware
navigation, two-venue stale-response isolation and unchanged real Guest/Venue Mode behavior.

Acceptance:

- OWNER and MANAGER receive only their own venue preview; STAFF has no entry/direct access, foreign
  venue users are forbidden, and Platform Owner receives no automatic Venue-route authority;
- `PUBLISHED_PUBLIC` venue/info payloads equal Guest payloads, including weekly hours, future date
  exceptions, visible info/media, Today Staff and current active promotions;
- `PRIVATE_DRAFT` includes saved guest-facing card/location/contact/description, schedule and public
  exceptions, visible info sections, guest-visible Today Staff and only current `ACTIVE`
  promotions. Hidden sections/media, unpublished staff, inactive/non-current promotions, private
  markers and raw Telegram/storage refs are absent;
- private preview media uses an authenticated, venue/section/media-scoped Venue proxy. Public Guest
  media routes and their availability guards remain unchanged;
- every preview response is read-only and `Cache-Control: no-store`; missing/archived/deleted venues
  fail safely and Guest routes still expose no unpublished/private projection;
- `PUBLISHED_PUBLIC` shows `Опубликовано` and `Так карточку сейчас видит гость.`;
  `PRIVATE_DRAFT` shows `Черновик`,
  `Гости пока не видят эту карточку. Это закрытый предпросмотр сохранённой версии.` and only the
  safe reason `Заведение ещё не опубликовано.`, `Заведение временно скрыто.` or
  `Заведение приостановлено.`, never technical subscription state;
- the Settings origin shows `Вернуться к настройкам`; venue navigation shows
  `Вернуться в кабинет`;
- public-card, weekly-schedule and date-exception dirty state blocks preview with
  `Есть несохранённые изменения. Сначала сохраните их, затем откройте предпросмотр.`;
  no auto-save/request occurs, and an explicit save makes the new saved state visible;
- venue switching clears old state immediately, aborts the previous request and ignores late
  responses across both modes, including same-mode switches;
- booking, favorites, venue chat, support, staff call, extension, cart, order and table context are
  absent and generate no mutation traffic.

The Preview smoke does **not** validate Venue Mini App media upload or management. No file picker,
upload endpoint, replace/hide/delete flow or new storage path is part of Guest Preview Phase 2.1.

## Venue Menu Shift Check Phase 1 Quality Gate

Status:
**MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.

The shift-check block is part of the existing Venue Menu screen. Existing immediate individual
item/option stop-list routes remain unchanged; draft toggles and mass actions must not send
availability mutations before one explicit confirmation.

Required coverage:

- OWNER and MANAGER own-venue allow; STAFF/Guest/Platform-only/foreign venue direct denial;
- two collapsed-by-default task-oriented accordions are mutually exclusive; the category form and
  category details stay compact until explicitly opened;
- normal mode exposes only availability switches with nested options and dirty badges; selection
  checkboxes/actions appear only in the separate mass-selection mode with distinct accessible
  roles/labels;
- category item/option counts, search, unavailable/dirty filters, selected rows, category item
  changes, item option changes and select-all-filtered behavior;
- collapse/reopen preserves the draft; cancel restores backend state; venue switch clears the
  expanded state, draft, selection and pending confirmation context;
- cancel sends no mutation/audit; confirm sends exactly one batch; no-op confirm writes one audit
  with zero changed counts;
- combined maximum 500 changes, duplicate rejection, missing/foreign item/option rejection and
  option/item ownership validation;
- one DB transaction for item updates, option updates and exactly one
  `MENU_SHIFT_CHECK_COMPLETED` audit; every invalid/stale/rollback path leaves zero partial writes
  and zero completion audits;
- expected availability conflict rejects the whole batch with
  `Меню изменилось. Обновите проверку и повторите подтверждение.`;
- audit contains safe actor/venue ids, changed/reviewed counts and bounded changed-id lists, with no
  names, prices, raw Telegram/initData/private/customer data or full request body;
- venue switch/dispose aborts old requests, clears draft/selection and ignores late responses;
- confirmed item/option availability is reflected by Guest menu and revalidated by stale cart
  preview/add-batch;
- existing individual availability routes and Telegram Bot stop-list regression remain green.

Required local commands:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestOrderRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*AuditLogRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Recorded local evidence: `VenueMenuRoutesTest`, `VenueMenuRepositoryTest`,
`GuestVenueMenuRoutesTest`, `GuestOrderRoutesTest`, `AuditLogRepositoryTest`,
`TelegramBotRouterTableTokenTest`, isolated `compileKotlin`, `ktlintCheck`, Mini App production
build and the full deterministic browser smoke `100/100` passed. GitHub Actions were green,
staging deploy completed and manual smoke passed for Owner, Manager, Staff/foreign denial, the two
accordion UX, mass mode, draft/cancel/venue isolation, atomic/no-op/stale behavior, safe audit,
Guest availability/stale-cart rejection and Telegram stop-list parity.

### Menu Item Hard Delete Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Regression must preserve this bounded contract:

- existing authenticated Venue Mini App and Telegram item-delete management callers pass the
  authenticated actor plus server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`; request/query/path/
  callback data never supplies actor or source;
- Owner/Manager own venue are allowed under current `MENU_MANAGE`; Staff, foreign, unaffiliated and
  Platform-only without venue authority are denied without item/promotion disclosure or audit;
- one committed hard delete writes exactly one `MENU_ITEM_DELETED` for `menu_item` / item id in the
  same JDBC transaction as authoritative promotion-reference recheck, current rule version bumps,
  reference cascades and item delete; not-found/repeat/reference/SQL/audit/rollback writes none;
- after the locked reference recheck and before any write, a fixed reward returns HTTP `409` /
  `MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD` with the exact safe next step. Item/reward/rule
  version/status/timestamps and audit stay unchanged on first and repeated attempts;
- purchase-target and choice-allowlist deletion stays allowed. Choice primary pointers are
  deterministically re-homed, empty reward configuration is removed, affected rule versioning and
  exactly-one audit remain atomic;
- Mini App confirmation explains both automatic cleanup and the fixed-reward restriction. Typed
  conflict performs an authoritative refresh, keeps the item, shows no success and never retries
  deletion; cancel sends no request and Staff still has no structural delete control;
- Telegram renders the same actionable typed-conflict copy, never generic database/success copy,
  and preserves current authenticated Owner/Manager success plus Staff denial;
- payload keys are exactly `venueId`, `itemId`, `categoryId`, `source`,
  `affectedPromotionRules`. The nested keys are `totalCount`, `sampleRuleIds`, `omittedCount`,
  `sha256`; ids are deduplicated/sorted, sample is the first 50, and lowercase SHA-256 uses UTF-8
  `v1:` plus the complete sorted set joined by comma. The payload is below 4096 UTF-8 bytes;
- privacy tests exclude names, prices, media, promotion title/config/schedule/reward, raw request,
  callback/initData, Telegram fields, secrets and unrelated PII;
- audit/SQL/reference failure rolls item, promotion state/version and audit back together; existing
  success/error envelopes remain unchanged;
- the existing real-PostgreSQL promotion configuration class deterministically covers delete-first
  parent/rule/item lock order and configuration-first item `NOWAIT` conflict without arbitrary
  sleep. A committed delete has one audit; a failed delete has none; neither has partial state;
- existing promotion calculation, Guest order/bill/History snapshots, shift check, category/option
  mutations and availability policy stay regression-only and unchanged.

Required local commands:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*PromotionConfigurationConcurrencyPostgresTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

`PromotionConfigurationConcurrencyPostgresTest` remains inside the existing mandatory PostgreSQL
CI step; no new class or decorative workflow is needed. Its XML must exist with tests `> 0`,
`skipped=0`, `failures=0`, `errors=0`. Schema verdict is `NO_MIGRATION_EXPECTED`.

Release closure evidence records fully green Actions after rerun of one failed backend job (without
asserting an unverified root cause), staging deploy and passed smoke for exactly these scenarios:
Owner/Manager allowed; Staff denied; Mini App fixed-reward block with actionable copy; Telegram
fixed-reward block without generic DB error; blocked item/reward preserved with zero
`MENU_ITEM_DELETED`; allowed CHOICE item removed while the remaining CHOICE item stayed; exactly one
audit for the allowed delete; confirmation explained side effects/restriction; cancel sent no delete
request; Guest menu and working data remained intact; cleanup completed normally.

### Menu Category Hard Delete Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU CATEGORY HARD DELETE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Regression must preserve this bounded contract:

- the sole repository writer and both existing Mini App/Telegram callers require authenticated
  actor plus server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`; client payload/query/callback cannot
  control actor/source and no unaudited production overload remains;
- current Owner/Manager own-venue allow and Staff/foreign/unaffiliated denial are unchanged;
- only empty categories delete; non-empty category/items and promotion state remain unchanged;
- authoritative scope/empty check, category-reference snapshot/recheck, promotion parent/rule then
  category locks, bounded summary, current target cleanup/version bump, category delete and exactly
  one `MENU_CATEGORY_DELETED` commit in one transaction. Missing/repeated, denial, reference/
  concurrency, SQL, audit and rollback paths write zero success audit;
- audit failure after production writes restores category, category targets and rule version/
  `updated_at`, leaves audit absent and preserves lifecycle status;
- payload keys are exactly `venueId`, `categoryId`, `source`, `affectedPromotionRules`; summary ids
  are unique/ascending, sample first 50, omitted exact, and lowercase SHA-256 covers UTF-8 `v1:`
  plus the complete sorted set joined by comma. Payload stays below 4096 UTF-8 bytes, stores no full
  unbounded list and has no silent truncation;
- privacy tests exclude names, prices, promotion title/config/schedule/reward, media, raw request/
  callback/initData, Telegram identity, secrets and unrelated PII;
- the existing real-PostgreSQL class deterministically covers delete-first parent/rule/category
  lock order and configuration-first category `NOWAIT` conflict through latches plus `pg_locks`,
  without arbitrary sleep. Committed delete has one audit; failed delete has none; neither has
  partial state;
- item/option delete, price/name/type/update, availability/Shift Check, promotion lifecycle/
  calculation/compatibility, Telegram UX, audit viewer and media remain unchanged.

Release closure evidence for HEAD `0e30a9b` records user-confirmed green Actions, staging deploy and
only these confirmed staging scenarios:

1. Owner deletes an empty category through Venue Mini App.
2. Manager deletes an empty category.
3. Staff is denied.
4. A non-empty category is not deleted and its items remain.
5. A referenced empty category is deleted.
6. Its promotion category target is removed.
7. The affected rule version increases.
8. Rule and promotion lifecycle status do not change.
9. Telegram category delete works for an allowed actor.
10. A repeated attempt creates no second audit.
11. One successful delete creates exactly one audit.
12. Audit actor, source, entity and payload are correct.
13. The payload contains no private data.
14. Guest menu and working data remain intact.
15. Cleanup completes normally.

The existing menu repository/routes, promotion repository, Telegram router, PostgreSQL
configuration concurrency minimum `tests=14 skipped=0 failures=0 errors=0`, compile, lint, Mini App
build and Playwright regression gates remain required. No migration or new workflow was added.

### Menu Option Hard Delete Audit And Atomic Normalization quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION HARD DELETE AUDIT / ATOMIC BASE-PROFILE
NORMALIZATION INCLUDED / DONE / MVP / STAGING-SMOKE-PASSED**.

Regression must preserve this bounded contract:

- Mini App and Telegram direct delete pass only the authenticated actor and server-owned source to
  the one audit-aware repository delete; Owner/Manager own venue succeed while Staff, foreign and
  unaffiliated actors are denied without a mutation, fact oracle or success audit;
- authoritative item/option scope, stable item-then-option locks, final reread, direct delete and
  same-connection `MENU_OPTION_DELETED` insert share one JDBC transaction and commit;
- one Telegram normalization callback invokes one repository transaction, not N methods with
  separate connections. Existing custom/current canonical options are preserved, obsolete standard
  profiles are deleted, missing canonical profiles retain current product fields/order, and each
  physical delete has exactly one audit;
- direct audit failure after the delete restores the option. Failure after several normalization
  deletes/creates or on one of N audit inserts restores the exact initial option set and leaves no
  partial audits or item state;
- not-found/repeated direct delete, normalization no-op, denied/foreign/stale/conflicting, SQL/
  create/audit failure and rollback have zero success audit;
- audit action/entity are exactly `MENU_OPTION_DELETED`, `menu_item_option`, option id. Payload keys
  are exactly `venueId`, `itemId`, `optionId`, `source`; names, prices, media, order/cart contents,
  raw request/callback/initData, Telegram identity, secrets and unrelated PII are forbidden;
- historical option FK becomes null through the current `ON DELETE SET NULL`, while immutable name/
  price snapshots remain readable in active/closed order history. New submit with a deleted option
  receives the current safe validation error and creates no new order rows;
- `VenueMenuOptionNormalizationConcurrencyPostgresTest` uses production repositories/migrations,
  independent connections, deterministic latches and an observed `pg_blocking_pids` plus
  `pg_locks` edge. It covers normalization/normalization, normalization/direct delete,
  canonical-create/normalization, canonical-update/normalization, rename/rename,
  rename/canonical-create and rename/direct-delete with no duplicate canonical profiles, partial
  state or loser audit;
- hookah-section canonical create and actual rename use the compatible item-then-option lock plus a
  final collision check. Non-hookah duplicates and unchanged-name price/availability updates,
  including legacy duplicates, remain allowed. No process lock, idempotency token, unique
  constraint, migration or new workflow is added.

Required focused local commands are the menu repository/routes, Telegram router, `*GuestOrder*`,
`*VenueOrder*`, `*GuestVisitRoutesTest*`, compile, ktlint, Mini App build and full Playwright smoke
selectors listed in the task handoff. The mandatory real-PostgreSQL selector also includes:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*GuestTableContextActivationPostgresTest*' \
  --tests '*PromotionConfigurationConcurrencyPostgresTest*' \
  --tests '*VenueStaffMutationConcurrencyPostgresTest*' \
  --tests 'com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOptionNormalizationConcurrencyPostgresTest' \
  --console=plain
```

CI must require exact XML
`TEST-com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOptionNormalizationConcurrencyPostgresTest.xml`
with at least 26 tests and exactly zero skipped/failures/errors. The original hard-delete release
evidence predates later extensions; missing/zero/silently skipped XML fails the current mandatory
PostgreSQL gate and Docker is mandatory. The changed critical route gate
also requires `GuestVisitRoutesTest` so the closed-history nullable-reference regression cannot be
silently omitted.

Recorded local evidence: focused repository, Mini App route, Telegram, Guest order/history, Venue
order, compile and ktlint selectors passed; the combined mandatory PostgreSQL selector produced
`8/0/0/0`, `14/0/0/0`, `2/0/0/0`, `7/0/0/0` for its four exact XML classes; Mini App production
build and Playwright smoke `139/139` passed. Schema verdict: **NO_MIGRATION_EXPECTED**.

For release HEAD `03ae0af`, which matches `origin/main` at this handoff, the user-confirmed evidence
records fully green GitHub Actions, staging deploy and these bounded staging scenarios passed:

1. Mini App Owner/Manager direct option delete.
2. Telegram Owner/Manager direct option delete.
3. Staff denied.
4. Foreign/unaffiliated actors denied under the current contract.
5. Direct delete creates one audit row.
6. Repeated delete creates no second audit row.
7. Audit actor/source/entity/payload are correct.
8. Atomic base-profile normalization completes as one operation.
9. Obsolete profiles are removed.
10. Custom options are preserved.
11. Existing canonical profiles are preserved.
12. Missing canonical profiles are created.
13. Repeated normalization creates neither duplicate profiles nor new delete audits.
14. Historical order retains option name and price snapshots.
15. A stale cart with the deleted option is rejected safely without a new order row.
16. Guest menu and ordinary work data remain intact.
17. Cleanup completed normally.

### Menu Option Create Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Required regression coverage:

- inventory the sole private production `INSERT INTO menu_item_options` writer and all six
  authenticated callers: Mini App direct/bulk; Telegram canonical direct, custom dialog, bulk and
  normalization. No internal/system/legacy writer or unaudited compatibility overload may exist;
- Owner/Manager own-venue success and Staff/foreign/unaffiliated denial on both surfaces. Actor is
  only the Mini App session subject or current authenticated Telegram callback/message user;
  source is server-fixed `VENUE_MINI_APP` / `TELEGRAM_BOT` and cannot come from client/dialog data;
- direct create uses one connection with `autoCommit=false`, authoritative item/category scope,
  item `FOR UPDATE`, option rows `ORDER BY id FOR UPDATE`, DB-current collision recheck, insert,
  generated id, same-connection audit, reread and one commit. Audit failure removes the inserted row
  and produces a safe 503/no false success;
- one add-missing-base-profiles action locks/plans/inserts/audits in one repository transaction and
  canonical order. N committed inserts produce N audits; N=0 produces neither. Any later insert or
  audit failure restores the exact initial option and audit snapshots, preserving custom/current
  canonical rows plus existing price/availability/sort behavior;
- Telegram normalization preserves obsolete deletes, custom/current canonical rows and existing
  delete audits while adding one create audit per physically inserted missing profile. Delete,
  create and both audit families share one transaction; injected failure after multiple writes
  restores the complete pre-operation option/audit snapshots;
- action/entity are exactly `MENU_OPTION_CREATED`, `menu_item_option`, option id. Payload keys are
  exactly `venueId`, `itemId`, `optionId`, `source`. Names/profile values, price/availability,
  item/category names, promotion/cart/order data, raw request/initData/callback/update, Telegram
  identity, media, secrets and PII are forbidden in audit and logs;
- denial, foreign/not-found, canonical collision, duplicate/no-op, insert/audit failure, rollback
  and concurrent loser write zero create audit; no idempotency token is introduced;
- Testcontainers PostgreSQL uses production migrations/repositories, independent connections/PIDs,
  deterministic barriers and observed `pg_blocking_pids` / `pg_locks` edges without arbitrary
  sleep. Coverage includes same-canonical and different-custom direct/direct, direct/bulk,
  bulk/bulk, direct/normalization, bulk/normalization, rename/create collision and delete/create;
  audit count equals committed physical insert count with no duplicate canonical or partial state.

Mandatory CI selectors are `VenueMenuRepositoryTest`, `VenueMenuRoutesTest`,
`TelegramBotRouterTableTokenTest`,
`VenueMenuOptionNormalizationConcurrencyPostgresTest` and full Mini App smoke. Exact XML must exist,
have tests `> 0`, and have zero skipped/failures/errors; the shared current PostgreSQL XML minimum is 44. No new
workflow may replace or silently skip these gates.

Automated/local/CI contract evidence (`tests/skipped/failures/errors` where recorded): repository
`41/0/0/0`, routes `37/0/0/0`, Telegram `538/0/0/0`, route/security `1137`, PostgreSQL
`26/0/0/0` and Mini App E2E `169/169`; `compileKotlin`, `ktlintCheck` and the Mini App production
build passed. Direct, bulk and normalization full rollback, no duplicate canonical rows and
deterministic locking are automated coverage, not manual staging smoke. `git diff --check` remains a
handoff gate. Schema verdict: **NO_MIGRATION_EXPECTED**.

For current release HEAD `0e592ff`, the user confirmed fully green GitHub Actions, staging deploy
and this bounded smoke. Local GitHub CLI did not independently verify Actions because its active
token is invalid. Confirmed staging smoke only:

1. Owner created one option through the Mini App.
2. Exactly one `MENU_OPTION_CREATED` was created.
3. The Mini App audit source was `VENUE_MINI_APP`.
4. Manager created one option through Telegram.
5. Exactly one `MENU_OPTION_CREATED` was created.
6. The Telegram audit source was `TELEGRAM_BOT`.
7. Staff did not receive permission to create options.
8. Bulk add-missing-base-profiles added only missing profiles.
9. Its create-audit count equalled the number of physically created profiles.
10. Custom options remained preserved.
11. Existing canonical profiles and their price and availability were not reset.
12. A repeated bulk wrote zero options and zero create audits.
13. Normalization restored a missing profile.
14. The restored profile wrote exactly one create audit.
15. A repeated normalization wrote zero options and zero create audits.
16. Audit payload contained no names, prices, availability or PII.
17. The working Guest menu was not damaged.
18. Cleanup completed normally.

No rollback/failure injection or concurrency case is claimed as staging smoke. The broader
Menu/Dangerous Action Audit remains `PARTIAL`.

### Menu Item Create Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Required regression coverage:

- writer inventory proves one production `INSERT INTO menu_items` behind required
  `VenueMenuRepository.createItem`, exactly two authenticated callers (Mini App POST and Telegram
  add-item dialog), no automatic option insert and no internal/system/legacy runtime writer. Staging
  seed SQL is operational only;
- Owner/Manager own-venue create succeeds on both surfaces; Staff, foreign, unaffiliated and
  Platform-only actors are denied. Legacy `ADMIN` remains Manager-compatible. Permission is checked
  before category facts. Mini App actor is the session subject; Telegram actor is the present current
  user matching persisted dialog owner. Client/dialog actor/source fields are ignored;
- one repository-owned connection uses `autoCommit=false`, authoritative category scope, category
  `FOR UPDATE`, scope recheck, existing `MAX(sort_order)+1`, item insert, generated id,
  same-connection audit, item reread and one commit. Reorder shares the category lock. Audit failure
  after observable item and audit inserts restores item/category/audit snapshots and surfaces safe
  `503 DATABASE_UNAVAILABLE`/Telegram retry state without false success;
- one committed item row writes exactly one `MENU_ITEM_CREATED`, entity `menu_item`, item id. Payload
  keys are exactly `venueId`, `itemId`, `source`; actor is only the standard actor field. Item name,
  price, currency, availability, type, category id/name, description, sort, media, options,
  promotions, cart/order, raw request/initData/Telegram content, secrets and PII are absent;
- each independent create, including equal names, remains a separate item plus audit. Denial,
  invalid input/scope, not-found, SQL/audit failure and rollback produce zero. No idempotency token,
  unique constraint, migration or business-default/DTO/form/dialog change is introduced;
- Testcontainers PostgreSQL uses production migrations/repositories, independent connections/PIDs,
  deterministic latches and real `pg_blocking_pids` / `pg_locks` evidence without sleep. It covers
  Mini/Mini, Mini/Telegram, create/category-delete `NOWAIT`, create/reorder shared lock and one
  concurrent post-insert audit failure. Committed item count equals create-audit count and no
  unaudited item survives rollback.

Mandatory CI selectors are exact `VenueMenuRepositoryTest`, `VenueMenuRoutesTest`,
`TelegramBotRouterTableTokenTest`, `VenueMenuOptionNormalizationConcurrencyPostgresTest` and full
Mini App smoke. That item-create release recorded minima `44`, `40`, `542`, `31`; current shared
Menu Management minima are repository `51`, routes `43`, Telegram `549`; the current shared PostgreSQL minimum is `44`. Exact JUnit XML must exist
with zero skipped/failures/errors. The PostgreSQL job retains `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`.
No new workflow replaces these gates.

Recorded local evidence: repository `44/0/0/0`, routes `40/0/0/0`, Telegram `542/0/0/0`,
PostgreSQL `31/0/0/0`, `compileKotlin`, `ktlintCheck`, Mini App production build and Playwright
`169/169`. `git diff --check` and clean-scope status remain final handoff gates. Schema verdict:
**NO_MIGRATION_EXPECTED**.

User-confirmed green Actions, staging deploy and bounded smoke passed: Owner Mini App create,
Manager Telegram create, Staff denial, exactly-one safe audits, duplicate-name separate physical rows,
intact item order and working Guest menu, privacy and cleanup. The contract intentionally claims no
extra staging scenario; failure injection and concurrency remain automated evidence.

That item-create release alone did not close item update audits. They are now implemented by the
separate local Menu Management closure below; description/media, Guest order/cart expansion,
promotions and the broader Menu/Dangerous Action Audit remain outside it.

### Venue Menu Management existing-contract closure quality gate

Status: **VENUE MENU MANAGEMENT / EXISTING-CONTRACT AUDIT AND TRANSACTION CLOSURE / DONE / MVP /
STAGING-SMOKE-PASSED**.

Required regression coverage:

- inventory proves `VenueMenuRepository` is the sole production SQL owner and that only the existing
  category create/rename/type/reorder and item rename/price-currency/type/category-move/reorder
  families are in scope; previously closed menu families remain green;
- repository tests assert all nine exact actions/payloads, one per real family delta, no-op timestamp
  stability, duplicate-name semantics, price-only/currency-only/combined cardinality, atomic default
  seeding, exact-set/hash reorders, compound five-family audit cardinality, privacy and rollback;
- Mini App tests prove Owner/Manager success, Staff/foreign/unaffiliated/Platform-only denial before
  entity facts, ignored actor/source spoofing, unchanged DTO/status, safe `503` and no route-side
  duplicate audit. Mini App source is always `VENUE_MINI_APP`;
- Telegram tests prove Owner and Manager category create/rename/type parity, authenticated atomic
  default seeding, item rename/price/type, Staff/foreign denial, current-user/dialog-owner equality,
  `TELEGRAM_BOT`, retry preservation and no false success/dialog clear on database/audit failure;
- Guest/order regression proves current menu and new order batches use current authoritative
  name/price while existing order/bill/history snapshots remain immutable; pricing, promotions,
  stale-cart and idempotency behavior do not change;
- the 40-case Testcontainers PostgreSQL class uses production migrations/repositories, independent
  connections/PIDs, deterministic latches and real `pg_blocking_pids`/`pg_locks` evidence. It covers
  create-vs-seed, category update-vs-reorder, category reorder-vs-item create, compound item
  update-vs-Telegram type, move-vs-source/destination reorder, item reorder-vs-create/delete and
  compound/reorder audit-failure rollback. No arbitrary sleep is evidence.

For that Menu Management release, mandatory focused counts were repository `51`, routes `43`,
Telegram `549`, Guest routes `61` and menu PostgreSQL `40`, each with exact JUnit XML and
`skipped=0`, `failures=0`, `errors=0`. The
existing CI route/security and five-suite PostgreSQL parsers must fail missing, zero/below-minimum,
skipped, failed or errored XML; Docker and full Mini App smoke remain mandatory. That release's
recorded PostgreSQL minimum vector was `8 / 14 / 2 / 40 / 9`; the current shared minimum is recorded
in the bootstrap gate below. No workflow or migration is added.

Mandatory local commands include the focused repository/routes/Telegram/Guest selectors, the exact
current route/security selector, the exact five-class PostgreSQL selector with
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`, `compileKotlin`, `ktlintCheck`, Mini App production build,
full Playwright smoke, `git diff --check` and `git status --short`. The user confirmed green Actions,
staging deploy and the consolidated manual Menu Management smoke for this release. Future runtime
changes still require their own green Actions, staging deploy and bounded manual smoke; this local
evidence does not make the overall product production-ready.

Recorded local validation: repository `51/0/0/0`, routes `43/0/0/0`, Telegram `549/0/0/0`, Guest
routes `61/0/0/0`, exact route/security `1164/0/0/0`, menu PostgreSQL `40/0/0/0`, and exact
five-suite PostgreSQL `73/0/0/0` with vector `8 / 14 / 2 / 40 / 9`. Standalone `compileKotlin`,
`ktlintCheck`, Mini App production build and full Playwright `169/169` passed. No skipped, failures
or errors were accepted.

### Shared Initial Menu Bootstrap release quality gate

Status: **VENUE MENU ONBOARDING / SHARED INITIAL MENU BOOTSTRAP / DONE / MVP /
STAGING-SMOKE-PASSED**.

Required regression coverage:

- repository empty/partial/normalized/complete cases prove exact `Кальянное меню`, `Напитки`,
  `Кухня` order with explicit `OTHER`, preservation of existing/custom rows and items/options,
  one audit per physical insert, repeat row/timestamp/audit no-op, privacy and full rollback;
- Mini App routes prove Owner/Manager own-venue success, Staff/foreign/unaffiliated/Platform-only
  denial, ignored actor/source spoofing, bounded `{venueId}`, safe `503`, no duplicate route audit
  and an unchanged read-only GET;
- Telegram proves the shared seed source, current actor, `TELEGRAM_BOT`, repeat no-op, denial and
  failure without false success; onboarding/connection tests prove approval/linking alone seed zero;
- deterministic Testcontainers PostgreSQL proves Mini App vs Telegram, Mini App vs Mini App,
  bootstrap vs ordinary category create, bootstrap vs reorder and partial audit failure. It uses
  production migrations/repository, independent connections/PIDs, latches and observed
  `pg_blocking_pids`/`pg_locks`, with no arbitrary sleep as evidence;
- deterministic Playwright proves empty-first mutation-before-GET with no empty flash, repeat,
  partial/custom preservation, actionable bootstrap and GET retry, stale venue/account response
  isolation and Staff no-mutation/no-new-authority behavior.

Recorded local evidence is repository `54/0/0/0`, routes `46/0/0/0`, Telegram `551/0/0/0`,
onboarding/connection `18/0/0/0` and menu PostgreSQL `44/0/0/0`. The exact route/security selector
passed `1190/0/0/0`; the exact five-suite PostgreSQL selector passed `77/0/0/0` with vector
`8 / 14 / 2 / 44 / 9`. Standalone `compileKotlin`, `ktlintCheck`, Mini App production build and full
Playwright `176/176` passed. All accepted XML has zero skipped, failures and errors.

The existing route/security gate also requires onboarding/connection XML with a minimum of `18`;
repository `54`, routes `46`, Telegram `551` and menu PostgreSQL `44` minima are retained. Missing,
below-minimum, skipped, failed or errored XML still fails the existing gates. No workflow or migration
was added. The user confirmed green GitHub Actions for the release HEAD, staging deploy, Mini
App-first and Telegram-first parity, repeat with zero duplicate rows/audits, partial/custom menu
preservation, Staff denial, approval remaining non-seeding and successful cleanup. Failure injection
and deterministic contention remain automated evidence rather than staging operations. This closes
only the shared bootstrap and does not mark broader onboarding or the product production-ready.

### Current P2/P3 finding process and registry

Every unresolved P2/P3 must have one stable ID and one canonical entry with: area, observed evidence,
user/runtime risk, smallest sufficient fix, the trigger or release boundary that makes it required,
and status. `PROJECT_STATUS.md` carries only the short current index. Status meanings are: `OPEN`
(accepted, not yet inside an active release boundary), `IN_NEXT_EPIC` (mandatory for that bounded
epic), `BLOCKED` (external decision/evidence named in the entry) and `DONE` (fix plus required evidence
recorded). Do not close a finding from intent, a code comment or an unrelated green suite.

#### MENU-CONC-001

- Area: structured-menu item move/update concurrency.
- Evidence: `VenueMenuRepository.updateItem` reads a non-locking source-category hint before it locks
  the hinted and requested categories. A concurrent committed move can make the later authoritative
  item category differ from that hint, and the method returns `null` through the not-found contract
  rather than a retryable serialization/conflict outcome.
- Risk: a valid same-venue item can be reported as absent during relocation contention, producing a
  false user-facing not-found and discouraging a safe retry.
- Minimal fix: after deterministic category/item locking, reread authoritative item scope and map a
  detected relocation race to the existing retryable conflict/service contract; preserve foreign-
  venue privacy and real not-found behavior.
- Required trigger/release boundary: before the next item move/update concurrency change or any Menu
  release that changes those locks/outcomes.
- Status: `OPEN`.

#### MENU-TEST-002

- Area: category create/seed/compound/reorder rollback tests.
- Evidence: current rollback coverage verifies restored final state, but category create, default
  seed, compound update and reorder cases do not all assert the intermediate business/audit snapshot
  from the same JDBC connection immediately before the injected failure.
- Risk: a hook can fire before the intended writer/audit boundary and still yield a green final
  rollback assertion, weakening proof that partially written state was actually rolled back.
- Minimal fix: add same-connection pre-failure assertions for the expected row/order/timestamp and
  audit delta, then throw and retain the existing post-rollback independent-connection assertions.
- Required trigger/release boundary: with the next category writer, transaction/audit or concurrency
  test change; no standalone runtime release is required.
- Status: `OPEN`.

#### BOOTSTRAP-QA-001

- Area: shared menu PostgreSQL CI/docs gate.
- Evidence: `.github/workflows/ci.yml` and the release XML contract require `44`, while several QA
  sentences still called `40` the current shared minimum after the bootstrap cases were added.
- Risk: a handoff could select too few cases or treat a below-current XML result as acceptable.
- Minimal fix: current/shared/mandatory references use `44` and vector `8 / 14 / 2 / 44 / 9`;
  explicitly historical Menu Management results may retain their recorded `40`.
- Required trigger/release boundary: this docs-only release handoff.
- Status: `DONE`.

#### BOOTSTRAP-TEST-002

- Area: Telegram onboarding-entry regression.
- Evidence: `TelegramBotRouterVenueConnectionRequestFlowTest` now creates/links through the shared
  contract, directly dispatches `owner_venue_onboarding_entry` for the linked Owner, verifies the
  venue card and asserts zero `createMissingCategories`/`createCategory` calls.
- Risk: callback routing could later seed categories or bypass the explicit first-management-entry
  contract without failing the approval/linking tests.
- Minimal fix: implemented by the direct callback regression above.
- Required trigger/release boundary: mandatory in `PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT`
  before runtime release.
- Status: `DONE`; keep it in the mandatory Telegram release selector.

#### ONBOARDING-H2-001

- Area: legacy H2 request-status constraint fidelity.
- Evidence: H2 V36 created an unnamed inline `status IN ('NEW')` check, while V38 drops only the
  PostgreSQL-style canonical constraint name. A fresh empty H2 migration can finish but retains the
  legacy check, so a canonical `PENDING` insert is rejected; migrating a database that contains a
  `NEW` row can fail during the V38 backfill. The onboarding H2 suites therefore use an explicit
  test fixture that migrates to V37, discovers/drops the actual legacy check through metadata and
  then continues normal Flyway. PostgreSQL V36/V38 and the mandatory Testcontainers suite do not
  have this defect.
- Risk: packaged H2 dev runtime cannot reliably write/migrate canonical onboarding rows, and a
  generic empty-schema module test can miss the retained check.
- Minimal fix: in a separately approved migration-maintenance boundary, add a forward-only H2
  repair plus a packaged-runtime regression; never edit the checksum of applied V36.
- Required trigger/release boundary: before relying on packaged H2 for onboarding or the next H2
  migration-maintenance release. It is not a PostgreSQL duplicate/create-link prerequisite.
- Status: `OPEN`.

#### ONBOARDING-TG-CONFIRM-001

- Area: Telegram confirmation UX for consequential onboarding actions.
- Evidence: the legacy applicant cancel and Platform reject/close/create callbacks execute their
  current mutation on the first callback. This epic preserves that Telegram UX as required, while
  the new Mini App surfaces use explicit confirmation copy.
- Risk: an accidental Telegram tap can cancel/reject/close a request or start create/link without a
  second confirmation screen; backend RBAC, state CAS and atomic rollback still prevent authority or
  partial-write escalation.
- Minimal fix: in a separately approved Telegram onboarding UX boundary, keep compatibility
  callbacks but make the first tap show consequences and a distinct final confirmation callback;
  prove the first tap writes nothing and preserve the four-state model.
- Required trigger/release boundary: with the next Telegram onboarding destructive-action UX change;
  it is not a shared-writer, duplicate-submit or create/link prerequisite.
- Status: `OPEN`.

#### ONBOARDING-DECISION-RETRY-001

- Area: response-loss recovery for request decision/cancel/close mutations.
- Evidence: mutations are serialized, transactional and truthfully audited, but after a committed
  response is lost, repeating the same approve/reject/cancel/close action can return current-state
  conflict and leave the client on its retry error until an authoritative reload. Submit and
  create/link already have the stronger idempotent replay contract required by this epic.
- Risk: an operator/applicant can briefly see stale failure copy after the requested state was
  committed; this does not create duplicate or partial database state.
- Minimal fix: either make same-target replay return the authoritative current record with zero
  duplicate audit, or make clients refresh and explain the committed current state after conflict.
- Required trigger/release boundary: with the next request decision/cancel retry-contract or client
  mutation UX change; it is not a create/link recovery prerequisite.
- Status: `OPEN`.

#### ONBOARDING-FIRST-VENUE-001

- Area: Telegram acquisition and first-applicant Platform create/link.
- Evidence: a server-authenticated Telegram user with zero OWNER memberships sees the application
  entry, submits through the shared writer, and produces zero venue/member/link/selection/menu writes
  before Platform action. Platform create/link initializes the existing commercial-account default,
  one DRAFT and exactly one OWNER membership atomically; retry returns that link.
- Status: `DONE`; keep the first-user repository, Telegram and PostgreSQL regressions mandatory.

#### ONBOARDING-APPLICATION-EQUIVALENCE-001

- Area: logical application equivalence across Telegram, Venue Mini App and PostgreSQL.
- Evidence: production normalization and tests use one exact tuple: normalized venue name, city,
  contact and optional comment. Exact `PENDING`/`APPROVED`-unlinked retry returns the authoritative
  row with zero insert/audit; any distinct tuple field creates another request; rejected/cancelled
  rows do not block. Deterministic independent-connection PostgreSQL tests cover simultaneous same
  and distinct cross-surface submissions.
- Status: `DONE`; no applicant-only uniqueness, process mutex, migration or unique constraint exists.

#### ONBOARDING-ROUTE-COVERAGE-001

- Area: Platform production-path route/RBAC/privacy/projection gate.
- Evidence: `PlatformOnboardingRoutesTest` runs through `Application.module()` and covers request
  list/detail/decisions/close/terms/create-link/retry/failures, every non-Platform role, spoofed
  authority fields, exact safe DTOs, venue city/all active operational owners and owner portfolio
  list/detail graphs.
- Status: `DONE`; exact CI XML minimum is `15` with zero skipped/failure/error.

#### ONBOARDING-UX-A11Y-001

- Area: onboarding/ownership form and async-state accessibility.
- Evidence: every new form/filter control has an associated label; stable polite status and assertive
  error live regions expose loading/mutations; status is textual; focused browser tests assert focus
  after submit/error/cancel/back and account-isolated late responses.
- Status: `DONE`; retain the focused ownership onboarding browser scenarios in full smoke.

#### ONBOARDING-TG-LEGACY-STATE-001

- Area: persisted Telegram direct-create dialog compatibility.
- Evidence: actual incoming messages are dispatched from each persisted
  `OWNER_VENUE_CREATE_WAIT_NAME`, `OWNER_VENUE_CREATE_WAIT_CITY` and
  `OWNER_VENUE_CREATE_WAIT_ADDRESS` state; each state is cleared, the shared application dialog stays
  usable, and draft/member/link/selection/menu writers remain at zero calls.
- Status: `DONE`; retain the regression in `TelegramBotRouterTableTokenTest`.

#### ONBOARDING-CANON-UNICODE-001

- Area: Unicode-aware canonical application tuple.
- Evidence: the production tuple keeps NFKC, Unicode White_Space collapse, trim,
  `Locale.ROOT` lowercase and blank optional comment normalization. Repository-path tests cover NEL
  U+0085, the complete Unicode White_Space code-point set, NFKC-compatible forms, blank comment and a
  genuinely distinct non-whitespace character with exact physical request and audit cardinality;
  PostgreSQL cross-surface retry also includes NEL.
- Status: `DONE`; retain the repository and PostgreSQL regressions in their mandatory selectors.

#### ONBOARDING-VENUE-ROUTE-COVERAGE-001

- Area: Venue Mini App production-route onboarding coverage.
- Evidence: `VenueOnboardingRoutesTest` runs through `Application.module()` and production
  route/service/repository boundaries for canonical retry, a distinct second application,
  client-field spoof resistance, safe database/audit failure, approved-unlinked replay and the full
  Manager/Staff/foreign/Platform-only denial matrix.
- Status: `DONE`; exact CI XML minimum is `8` with zero skipped/failure/error.

#### ONBOARDING-PG-FIRST-APPLICANT-001

- Area: real first-ever applicant PostgreSQL create/link evidence.
- Evidence: the dedicated fixture starts with only applicant and Platform Owner users and zero
  account/venue/membership/subscription/link/selection/menu state. Deterministic independent waiter
  PIDs prove applicant-lock contention; concurrent create/link commits one default-limit-`1`
  commercial account, one DRAFT, one OWNER membership, one settings/subscription/link/audit set,
  leaves selected venue state unchanged and seeds no menu categories. The retry returns the same
  authoritative venue.
- Status: `DONE`; exact PostgreSQL XML minimum is `7` with zero skipped/failure/error.

#### ONBOARDING-A11Y-CREATE-LINK-FOCUS-001

- Area: Platform create/link focus restoration.
- Evidence: after both first create/link and linked idempotent replay, the rendered venue-detail
  heading has a stable target and receives programmatic focus; deterministic browser assertions
  verify its exact accessible name and prevent focus from remaining on the removed action.
- Status: `DONE`; retain both focused create/link scenarios in full Mini App smoke.

#### ONBOARDING-OWNER-PLURAL-001

- Area: Russian venue-count pluralization.
- Evidence: the shared venue-count formatter is used by owner list cards and owner detail summary;
  focused browser assertions cover `1`, `2`, `5`, `11`, `21`, `22` and `25`.
- Status: `DONE`; retain the focused pluralization scenario in full Mini App smoke.

### Platform & Venue Onboarding / Ownership Cockpit quality gate

Status: **PLATFORM & VENUE ONBOARDING / OWNERSHIP COCKPIT / DONE / MVP /
STAGING-SMOKE-PASSED** for release HEAD `e35def99ea8429462e5fdaaeee914f57da72e775`.
The automated gate below remains regression evidence. The user confirmed fully green GitHub Actions,
staging deploy, completion of the consolidated smoke and cleanup; local GitHub CLI authentication is
invalid, so Actions are recorded as user-confirmed rather than independently queried here.

- Shared repository tests cover the production canonical tuple, exact-versus-distinct cross-surface
  submit, exact no-op audit behavior, rejected/cancelled reuse, first-user submit/edit/cancel with
  zero operational writes, quota rejection/audit rollback, create/link retry, one DRAFT/OWNER
  membership/settings/link/audit set and zero menu categories.
- `VenueOnboardingRoutesTest` covers active Owner own scope, applicant isolation and Manager/Staff/
  unaffiliated/Platform-only denial for the Venue Mini App additional-venue entry.
- `PlatformOnboardingRoutesTest` covers the production module request list/detail/approve/reject/
  close/terms/create-link routes, invalid/not-found/database/audit failures, exact safe DTOs, the
  complete non-Platform denial matrix, venue city/all active operational owners and one-to-many/
  many-to-one owner projections.
- `VenueOnboardingConcurrencyPostgresTest` is mandatory with minimum `7`: observed-lock Telegram/
  Mini App exact submit produces one request/audit, distinct submissions both commit on independent
  connections, approve/reject has one truthful winner, concurrent Platform create/link produces one
  venue/membership/settings/link/audit set, and first applicants submit/link without a prior OWNER
  membership. The mandatory PostgreSQL vector is `8 / 14 / 2 / 44 / 9 / 7` (total minimum `84`). Missing/
  below-minimum/skipped/failed/errored XML remains a hard CI failure.
- Telegram regression exposes first-venue application to a zero-membership authenticated user, keeps
  `owner_quota_create_start` as an alias, dispatches all three persisted legacy states, proves zero
  direct draft/membership/link/selection/menu writers and keeps account/limit-request behavior.
- The exact route/security aggregate passed `1247 / 1247 / 0 / 0 / 0`
  (discovered / executed / skipped / failures / errors). Measured focused counts are repository
  `13`, Venue routes `8`, Platform onboarding routes
  `15`, Telegram connection flow `18`, Telegram table/legacy state `552`, Telegram keyboards `169`
  and PostgreSQL onboarding concurrency `7`. Exact CI minima remain repository `11`, Venue routes
  `8`, Platform onboarding routes `15`, Telegram `18 / 552 / 169` and PostgreSQL onboarding `7`;
  skipped/failure/error or missing XML fails the gate.
- Full Mini App smoke retains deterministic Venue ownership cards/explicit selection, exact
  double-submit, distinct second venue, approved-unlinked exact retry, account/late-response
  isolation, edit/cancel/reject/close/Platform denial and focused labels/live-region/focus assertions.
  The deterministic local run passed `191 / 191`, including ownership onboarding `15 / 15`.

Consolidated staging smoke:

1. An authenticated Telegram user with zero OWNER memberships sees and submits a first-venue
   application. Existing Owner with one/multiple venues uses Telegram or Venue Mini App; Manager/
   Staff/foreign/Platform-only direct Venue ownership routes are denied before facts.
2. Telegram-first and Mini-App-first exact submit, including exhausted quota, create one logical
   request and zero venue/member/link/menu rows. A distinct canonical payload creates a second row;
   rejected/cancelled history does not block it.
3. Pending edit/cancel and applicant isolation work; only the four canonical statuses appear.
4. Legacy quota callback and each of the three persisted legacy direct-create states enter the
   usable shared application flow with zero direct writes and no selected-venue change.
5. Platform tabs list safe request detail, city/all co-owners and operational-owner portfolios; no
   primary-owner label or private/raw Telegram/provider fields appear.
6. Approve/reject/close and commercial terms are explicit. Approval alone creates no venue,
   membership, selector entry or menu category.
7. Insufficient quota at create/link leaves the approved request unlinked with no partial state;
   after Platform adjusts the existing limit, retry succeeds once.
8. Successful first-applicant create/link uses the existing commercial account default limit of one
   and yields one DRAFT, active OWNER membership, commercial settings and request link; repeat returns
   the same venue and emits no duplicate success notification/audit.
9. Owner sees the new venue only after authoritative membership refresh and selects/opens it
   explicitly; link does not auto-select. The direct onboarding callback still seeds no menu.
10. Existing quota-pilot venues/memberships and current account/limit-request management remain
    unchanged. Existing direct Platform ownerless DRAFT creation remains available.

Recorded outcome: all ten scenarios passed. In particular, first-user Telegram and additional-venue
Mini App applications worked; exact retry created no duplicate and a different application remained
separate; Platform Owner saw requests, venues and owners; create/link produced exactly one venue and
OWNER membership; selected venue did not change automatically; the legacy quota-direct path used the
shared application flow; multi-owner/portfolio projections worked; the first-ever applicant received
baseline limit `1`; applicant/actor/source stayed server-derived; cleanup passed.

### Menu Option Rename Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION RENAME AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Regression must preserve this bounded contract:

- Venue Mini App Owner and current allowed Manager behavior produce one audit for a real rename;
  Staff, foreign and unaffiliated denial produce zero. Telegram success records the current
  authenticated message user with server-owned `TELEGRAM_BOT`; absent/mismatched identity fails
  closed. Neither client surface supplies actor/source.
- The repository's item lock, ascending option locks, DB-current reread/canonical collision check,
  compound row update and same-connection audit use one JDBC transaction. Audit failure restores
  name, price and availability and leaves zero audit.
- Action/entity are exactly `MENU_OPTION_RENAMED`, `menu_item_option`, option id. Payload keys are
  exactly `venueId`, `itemId`, `optionId`, `oldName`, `newName`, `source`. Raw request/callback/
  initData, Telegram identity, prices, availability, canonical values, media and unrelated PII are
  forbidden.
- Exact-name no-op/retry, price-only, availability-only, not-found, collision, denial, SQL/audit
  failure and rollback write zero rename audit. Compound name+price/availability preserves its
  existing response and atomic field behavior but writes only one rename audit.
- Existing hookah canonical normalization, self-exclusion, non-hookah duplicate behavior,
  historical order snapshots and future-submit current-value resolution stay unchanged.
- The option-rename release recorded 26 PostgreSQL tests; the shared current 44-test class retains deterministic rename versus rename, distinct and
  same-target price updates, atomic base-profile normalization, canonical create and direct delete
  in addition to the released normalization regressions. It uses observed blocking/locks and no
  arbitrary sleep.

Focused repository, route, Telegram and Guest/order/history selectors, `compileKotlin`,
`ktlintCheck`, Mini App build and Playwright `139/139` passed locally. The mandatory PostgreSQL XML
result recorded for that released rename slice is `8/0/0/0`, `14/0/0/0`, `2/0/0/0`, `7/0/0/0`;
the current option-class CI minimum is 44 after the shared bootstrap closure. Green Actions, staging
deploy and bounded cross-surface
RBAC/audit/privacy/concurrency/history smoke are recorded functionally passed; schema verdict is
**NO_MIGRATION_EXPECTED**. That rename slice does not itself close option create or availability;
option create has its separate release-closed contract, while item mutations, broader audit and
media remain open.

### Menu Option Price Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Regression must preserve this bounded contract:

- Owner and current allowed Manager update an own-venue option through the authenticated Mini App;
  Staff, foreign and unaffiliated actors are denied. Actor is the session subject and source is the
  server-owned `VENUE_MINI_APP`; actor/source-like body, query, path or client metadata are ignored
  and cannot influence audit authority. Telegram has no option-price writer.
- The repository uses the non-locking option-to-item hint, item `FOR UPDATE`, all item options in
  ascending id `FOR UPDATE`, DB-current target reread, conditional rename collision check, compound
  name/price/availability update, same-connection rename, price and availability audits and one
  commit. Any SQL/audit failure restores the full row including `updated_at` and all audit families.
- One real committed delta change writes exactly one `MENU_OPTION_PRICE_CHANGED` for entity
  `menu_item_option` / option id. Exact-price no-op/retry, name-only, availability-only, denied,
  foreign, missing, collision, failed and rolled-back paths write zero price audit.
- Price-only writes only price audit; name-only keeps exactly one rename audit; availability-only
  writes only availability audit. Name+price writes one independent audit of each family, and a real
  availability delta adds its own audit while all fields remain atomic. Existing response DTO is unchanged.
- Price payload keys are exactly `venueId`, `itemId`, `optionId`, `oldPriceDeltaMinor`,
  `newPriceDeltaMinor`, `source`. Names, availability, canonical values, promotion/cart/order
  contents, raw request/initData, Telegram fields, media, secrets and unrelated PII are forbidden.
  Rename payload continues to contain only its existing allowlist.
- Existing integer/minor-unit validation, zero-delta allowance, request/response/UI parsing,
  currency and rounding remain unchanged. Stale client/cart price is not authority: submit resolves
  the current available DB option, persists the current delta snapshot and never rewrites older
  `price_delta_minor_snapshot` rows.
- The production Testcontainers PostgreSQL class uses independent connections, deterministic
  latches and a confirmed blocking edge without arbitrary sleep. Its current 44 tests prove truthful
  price-versus-price ordering, same-target loser no-op, price versus rename, direct delete and atomic
  normalization, plus no partial compound updates or extra loser audits.

Mandatory CI keeps the existing exact selectors/XML for `VenueMenuRepositoryTest`,
`VenueMenuRoutesTest`, `GuestOrderRoutesTest`, `GuestVisitRoutesTest` and
`VenueMenuOptionNormalizationConcurrencyPostgresTest`. The current option PostgreSQL XML minimum is 44;
all critical XML must have tests `> 0` and exactly zero skipped/failures/errors.

Recorded automated evidence: all four focused repository/route/order/history selectors, the nine-test
PostgreSQL selector (`9/0/0/0`), `compileKotlin`, `ktlintCheck`, Mini App production build and full
Playwright smoke `152/152` passed. `git diff --check` passes. No migration or workflow was added.
For current release HEAD `0489a2f`, the user confirmed fully green GitHub Actions, staging deploy and
only this bounded staging smoke:

1. Price-only change succeeds.
2. Price-only change creates one `MENU_OPTION_PRICE_CHANGED`.
3. Price-only change creates no `MENU_OPTION_RENAMED`.
4. Repeating the same price creates no new price audit.
5. Name plus price saves atomically.
6. Name plus price creates one rename audit and one price audit.
7. A stale client price is not authority at checkout.
8. The server applies the current price or safely requires reconfirmation.
9. The working menu and data remain intact.
10. Cleanup completes normally.

Existing and new order snapshot preservation is confirmed automated coverage only; it is not asserted
as a separate staging smoke scenario. The broader Menu/Dangerous Action Audit remains `PARTIAL`.

### Menu Option Availability Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Required regression coverage:

- direct Mini App Owner/Manager/Staff allow under `MENU_AVAILABILITY_MANAGE`; foreign/unaffiliated
  denial; compound PATCH remains `MENU_MANAGE` for Owner/Manager and Staff denial;
- Mini App actor is the authenticated session subject, Telegram actor is current callback user and
  sources are server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`; client actor/source/callback metadata
  cannot override them or enter payload;
- one real direct true→false or false→true writes exactly one
  `MENU_OPTION_AVAILABILITY_CHANGED`; exact/repeated no-op, name-only, price-only, denial,
  foreign/not-found, collision, SQL/audit failure and rollback write zero;
- action/entity are exact and payload keys are only `venueId`, `itemId`, `optionId`,
  `oldIsAvailable`, `newIsAvailable`, `source`, excluding names, prices, canonical/promotion/order/
  cart data, raw request/initData, Telegram identity/update, media, secrets and unrelated PII;
- compound availability-only, name+availability, price+availability and all-fields cases write one
  audit for each actually changed family; availability-audit failure restores row fields,
  `updated_at` and every audit row;
- Shift Check common/individual/mixed/no-op/retry/stale paths add zero per-option availability audit
  and preserve only the current single `MENU_SHIFT_CHECK_COMPLETED` success contract;
- Telegram Owner/Manager/Staff current allow, denial, exact current actor/source, no-op and database/
  audit failure without false success; callback payload is excluded;
- disabled option rejection for new order, stale preview/submit safety, successful re-enable under
  current server validation and immutable historical option snapshots;
- Testcontainers PostgreSQL production migrations/repository, independent connections, deterministic
  latches and observed blocking without arbitrary sleep for direct/direct, direct/compound, both
  direct/Shift Check orders, direct/delete and direct/normalization. XML is exactly required,
  tests `>= 40` in the shared extended class, skipped/failures/errors zero.

Required local commands are the focused `VenueMenuRepositoryTest`, `VenueMenuRoutesTest`,
`TelegramBotRouterTableTokenTest`, `GuestOrderRoutesTest`, `*MenuShiftCheck*`,
`VenueMenuOptionNormalizationConcurrencyPostgresTest`, compile, ktlint, Mini App build and full e2e
smoke selectors documented in the current implementation handoff. No workflow or migration is added;
the existing mandatory PostgreSQL CI gate minimum is now 44 after the shared bootstrap closure.

### Menu Item Availability Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Release closure evidence is bounded: the user confirmed green Actions for current release HEAD
`db08916`, a staging deploy and this smoke only—Owner toggles a test item off/on with truthful audit;
Staff changes availability but cannot compound-edit name/price/type; Telegram toggle works with
`TELEGRAM_BOT`; Shift Check changes availability, writes one aggregate audit and no per-item audit;
Guest cannot order an unavailable item, can order after re-enable, and working menu/cart plus normal
cleanup remain intact. GitHub CLI could not independently query Actions because its active token is
invalid. This is not staging evidence for failure injection, raw SQL or other unobserved scenarios.

Required regression coverage:

- inventory every `menu_items.is_available` writer/caller: Mini App direct and compound, all
  Telegram detail/root stop-list callbacks, Shift Check common/individual/mixed, create, delete and
  any system/legacy/direct SQL path; create has no old value and Shift Check stays aggregate-only;
- direct Owner/Manager/Staff allow under `MENU_AVAILABILITY_MANAGE`; compound item PATCH remains
  Owner/Manager `MENU_MANAGE`; Staff compound and Shift Check are denied under current runtime RBAC;
- Mini App session subject and current Telegram callback user are the only actors; source is fixed
  server-side to `VENUE_MINI_APP` / `TELEGRAM_BOT`, never accepted from client input;
- true→false and false→true each persist exactly one `MENU_ITEM_AVAILABILITY_CHANGED` with entity
  `menu_item` / item id and exact payload keys `venueId`, `itemId`, `oldIsAvailable`,
  `newIsAvailable`, `source`; privacy denylist covers item/category metadata, prices/currency,
  options/promotions/cart/order, request/initData, Telegram identity/update, media, secrets and PII;
- same-state/repeated, metadata-only, denial, foreign/not-found, stale/collision, SQL/audit failure
  and rollback write zero item audit; direct no-op leaves `updated_at` unchanged;
- availability-only and metadata+availability compound PATCH write one availability audit only;
  all co-submitted fields and audit rows commit or roll back together, without item metadata audits;
- audit failure after the SQL update restores availability, `updated_at`, compound fields and all
  audit rows; route and Telegram surfaces return no false success;
- common, individual, mixed and no-op Shift Check success writes exactly one existing
  `MENU_SHIFT_CHECK_COMPLETED` and zero item availability audits; stale/failure writes no success;
- Guest disabled item returns `ITEM / UNAVAILABLE`, stale preview remains read-only, stale submit
  creates no authoritative order writes, re-enable recovers, neighboring lines and immutable
  historical name/price snapshots remain intact, and payload-bound idempotency is unchanged;
- Testcontainers PostgreSQL uses production migrations/repositories, independent connections,
  deterministic latches and an observed real blocking edge without arbitrary sleep for item
  direct/direct, direct/compound, both direct/Shift Check orders and delete/direct. Guest
  availability-vs-submit remains a separate required PostgreSQL edge.

Mandatory CI selectors remain `VenueMenuRepositoryTest`, `VenueMenuRoutesTest`,
`TelegramBotRouterTableTokenTest`, `GuestOrderRoutesTest`, the Shift Check cases embedded in the
first two classes, `VenueMenuOptionNormalizationConcurrencyPostgresTest`,
`GuestOrderIdempotencyConcurrencyPostgresTest` and full Mini App smoke. Exact local XML is
repository `36/0/0/0`, routes `31/0/0/0`, Telegram `535/0/0/0`, Guest routes `61/0/0/0`, menu
PostgreSQL `20/0/0/0` and Guest PostgreSQL `9/0/0/0` for that release slice; the current shared
menu minimum is 44. Existing CI parsing must fail missing/zero,
skipped, failures or errors. No new workflow or migration is allowed.

Non-blocking future hardening, not a release defect: assert raw `updated_at` before/after an injected
availability-audit failure; and hold the item lock while a real PostgreSQL Guest submit reaches an
observed `pg_blocking_pids` / `pg_locks` wait, then assert an allowed serial outcome.

### Guest Cart Stale Menu Selection Recovery quality gate

Status: **GUEST CART STALE MENU SELECTION RECOVERY / REMOVED OR UNAVAILABLE ITEMS AND OPTIONS /
PAYLOAD-BOUND IDEMPOTENCY + ATOMIC REJECTION / DONE / MVP / STAGING-SMOKE-PASSED**;
**ITEM-LEVEL ACTION AND COPY POLISH / DONE / MVP / STAGING-SMOKE-PASSED**. Schema contract remains
the additive PostgreSQL `V123` / H2 `V124` nullable `request_fingerprint VARCHAR(80)` with no
backfill or global unique constraint.

Required regression coverage:

- preview and final add-batch share the same authoritative item existence, venue/category scope,
  item availability, option existence, option ownership and option availability validation;
- exact HTTP `409` / `CART_MENU_SELECTION_UNAVAILABLE` returns all deterministic own-cart issues as
  `cartLineRef`, requested ids, `ITEM|OPTION` and `REMOVED|UNAVAILABLE`; foreign selections remain
  generic and malformed/unknown/database failures never receive stale-menu copy;
- preview is read-only for ordinary Guest and exact Platform Owner;
- removed/unavailable item and option preview/submit paths leave unchanged snapshots of table
  session timestamps, exits, tabs/memberships, chat context/dialog state, orders, batches, lines,
  selected options, idempotency, analytics and outbox; a corrected cart uses current authoritative
  prices and snapshots;
- final submit uses one connection/transaction for authoritative context locks, session-scoped
  idempotency, deterministic item/option locks, final validation, session touch, personal-tab ensure,
  order state, fingerprint and analytics. Injected failures after session touch, tab/member ensure,
  batch write and idempotency insert roll back the same expanded snapshot;
- canonical fingerprint `v1` covers actor/venue/table session/tab, normalized comment and sorted
  normalized merged `itemId / BASE-or-optionId / note / quantity` lines. It excludes key,
  `cartLineRef`, client order/prices/fingerprints, availability, names and display fields;
- an exact in-screen network retry keeps its key; item/option/note/quantity/comment or
  account/venue/table-session/tab mutation rotates it. Server price/availability/pricing-fingerprint
  change alone does not. Mismatch keeps the cart and creates a new key only on the next explicit
  submit; legacy unverifiable recovery exposes active-order review and explicit new-submit actions,
  with no automatic resend. A delayed success for submitted payload A cannot clear a business-
  mutated cart B; B remains for a separate explicit submit with a new key;
- exact retry and equivalent line order return one committed batch before current menu/gift
  validation; quantity/item/option/note/comment/tab/actor mismatches in one table session conflict;
  the same key in another table session is independent;
- reconstructable legacy `NULL` rows exact-replay or mismatch safely and may lazy-upgrade; lost
  option identity or multiple ambiguous physical rows in one logical session/key namespace are
  `ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE` with no new operation;
- deterministic PostgreSQL races cover exact retries, exact versus mismatch, concurrent new key,
  different tab/session scope, menu writer versus submit, stale rejection versus context creation and
  legacy lazy-upgrade without arbitrary sleeps or partial state;
- `ITEM / UNAVAILABLE` renders the exact mandatory-removal copy and `ITEM / REMOVED` renders its
  distinct exact copy; the old `Вернуться в меню` action is absent;
- item `Удалить и выбрать другую` deletes only the affected line, rotates the existing business
  payload/key lifecycle, authoritatively recalculates the remaining cart, opens the existing Guest
  menu without automatic selection and focuses its heading. Item `Удалить из корзины` stays in cart,
  recalculates and focuses the next line or cart heading. Both actions have line-specific accessible
  names and remove the old warning/actions from the accessibility tree;
- option recovery remains unchanged: it reuses the current picker, excludes the stale option and
  preserves quantity/note until successful preview;
- multiple issues are rendered on their exact lines; fixing one preserves every valid/remaining
  line and keeps submit blocked; retry preserves deterministic state and can recover after re-enable;
- line warnings are textual live regions, actions have line-specific accessible names, option-picker
  focus and post-delete/post-replacement focus are deterministic.

Mandatory local commands:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests 'GuestOrderRoutesTest' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests 'GuestOrderIdempotencyFingerprintTest' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests 'GuestOrderIdempotencyConcurrencyPostgresTest' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests 'GuestBatchIdempotencyFingerprintMigrationH2Test' --tests 'GuestBatchIdempotencyFingerprintMigrationPostgresTest' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
git diff --check
```

The existing workflow must select `GuestOrderRoutesTest` (minimum 61),
`GuestOrderIdempotencyFingerprintTest` (minimum 7),
`GuestOrderIdempotencyConcurrencyPostgresTest` and both fingerprint migration suites. Their exact
JUnit XML files must exist, meet the declared minima (`9` for concurrency and `2` per migration),
and report zero
skipped/failures/errors; a missing/zero/skipped/failing XML fails the gate. No workflow is added.

### Venue Menu Management UX Stabilization quality gate

Status: **VENUE MENU MANAGEMENT UX STABILIZATION / MOBILE RESPONSIVENESS + PRICE INPUT ERGONOMICS +
CONTEXT PRESERVATION / DONE / MVP / STAGING-SMOKE-PASSED**.

Required deterministic browser coverage:

- at 320x700, 360x800, 390x844 and 430x932, `documentElement.scrollWidth <= clientWidth`; the menu
  editor, cards, labels, inputs and item/option action controls fit their viewport rects without
  relying on horizontal-overflow masking. The check covers read-only cards plus active add/edit
  item and option forms, base-flavor actions, availability, submit/cancel and delete controls;
- long Russian labels wrap; item cards become a clear one-column sequence of identity/price,
  availability and primary action, options, add action/existing options, then secondary/destructive
  action; desktop/tablet retains compact multi-column use;
- all menu price fields have labels and suitable mobile numeric input. New required item price is
  empty with example placeholder; keyboard `150` renders as `150` and sends the expected minor-unit
  value. Existing item and option `0` survives focus/blur but keyboard entry and real paste replace
  it; after the first edit, repeated focus does not reselect the non-zero value. Invalid or empty
  required input remains safe and actionable;
- after successful item/option create/update/availability/delete and base-profile mutation, an
  authoritative menu GET retains the relevant expanded category, stable item/option ID, visible
  anchor and logical focus when the user has not interacted again. A controlled delayed GET must
  not steal later manual scroll or focus, while the authoritative data still renders; if the user
  starts another menu form, its draft, caret/focus and visible context remain current after render.
  Failure keeps the form values and a nearby live error;
- mutation success uses a live region owned by the current Menu screen. Venue/account switch
  disposes that announcement and scoped context, aborts or ignores old reads/mutations and cannot
  restore old cards, forms, scroll anchors, focus or success text into the new venue;
- OWNER/MANAGER manage the existing contract; STAFF remains read-only except current individual
  availability controls, and keyboard/focus accessibility stays available.

Backend production files are intentionally unchanged when create/update responses contain stable IDs;
no entity may be rediscovered by name/price matching. This gate requires no migration and does not
alter money, RBAC, audits, option normalization, Guest order snapshots or media.

Recorded bounded staging smoke evidence (user-confirmed for release HEAD `a62faa5`):

1. Mobile menu cards and actions fit the Telegram Mini App viewport.
2. Expanded item/options/forms create no horizontal overflow.
3. A new price field accepts `150`, not `0150`.
4. An existing zero is replaced correctly by the first input.
5. Repeated focus preserves an entered non-zero value.
6. An item mutation preserves category/item/scroll/focus context.
7. An option mutation preserves item/option context.
8. Category create returns focus to the new category summary.
9. Category rename returns focus to the same summary.
10. Category reorder returns focus to the moved category.
11. Cancelling inline forms returns logical focus.
12. Manual scroll/focus during reload is not overwritten.
13. Old-venue success does not appear in a new venue.
14. Old-account success/state does not appear after an account switch.
15. Guest menu and working data remain intact.
16. Cleanup completes normally.

## Venue Mini App Media Foundation Future Quality Gate

The canonical future contract is `docs/MEDIA_STORAGE_UPLOAD.md`. Its current verdict is
`STOP_FOR_MEDIA_STORAGE_DECISION`; this gate applies only after one durable storage option,
backup/deletion policy and operations owner are approved.

Required future coverage:

- PostgreSQL/H2 legacy-row migration and one source-neutral asset ledger;
- OWNER/MANAGER own-venue allow plus STAFF/Guest/Platform-only/foreign denial;
- server MIME sniffing, malformed/spoofed file, size, dimensions, cap and rate limits;
- no raw Telegram/object/filesystem ref in DTO, URL, DOM, error, log or audit;
- upload/replace/hide/show/delete lifecycle, safe audit, orphan/failed cleanup and
  reference-protected physical deletion;
- legacy Telegram plus selected-target delivery in Guest and Bot;
- exact `PUBLISHED_PUBLIC` Guest parity and authenticated/ref-free `PRIVATE_DRAFT` delivery;
- Mini App build/e2e plus real Telegram and selected-storage staging smoke;
- container recreate and backup/restore evidence for PostgreSQL metadata and selected byte storage.

Do not claim the media foundation release-ready from upload UI/API tests alone.

## Executable Promotions Phase 2 Quality Gate

The first runtime slice must prove one shared Bot/Mini App calculation path; parallel client-side
discount engines are a release blocker.

Required backend coverage:

- venue timezone, date range, every weekday boundary, multiple per-day windows and invalid/overnight
  schedule policy;
- item/category eligibility, unavailable item/option, current item price and selected-option delta;
- identical preview and submit result when state is unchanged, and safe recalculation when time,
  price, availability or cart composition changes;
- no stacking, explicit manual-discount conflict policy, zero lower bound and excluded,
  canceled/rejected line handling;
- immutable application/rule/version and affected-line snapshots in active order, bill and History;
- idempotent add-batch replay creates no duplicate application, adjustment or reward;
- personal/shared tab membership and cross-venue isolation remain unchanged.

Required parity coverage:

- the same request fixture produces the same rule id/version, eligible lines, adjustment and final
  total through Bot and Mini App adapters;
- both clients display ordinary price, named promotion adjustment and final amount before normal
  confirmation;
- neither preview creates an order or batch, and neither client submits trusted prices/discounts;
- stale schedule/availability/price is rejected or recalculated by the shared server path;
- staff-chat receives the persisted order facts only and never evaluates promotions.

Gift, BOGO and free-option tests were not part of the Happy Hours percentage closure. Happy Hours
remains a percentage preset and must not absorb these reward mechanisms.

Final result for the Happy Hours percentage slice:

- required `*PromotionRuleEngine*`, `*VenuePromotionRepository*`, `*GuestOrderRoutes*`,
  `*VenueOrdersRepository*` and `*TelegramBotRouter*` selectors passed;
- additional promotion routes, settings, order repository, History and diagnostics regression
  selectors passed;
- `:backend:app:ktlintCheck`, `:backend:app:compileKotlin` and Mini App production build passed;
- deterministic Mini App Playwright smoke passed `71/71`;
- staging smoke passed creation and activation validation, weekday/time windows, item/category
  targets, current price, selected-option delta, cart preview, submit recalculation, persisted
  bill/History, no stacking, manual-discount rejection, Owner/Manager/Staff RBAC, Bot/Mini App
  parity and `TEXT_ONLY` regression.

### GIFT_WITH_ITEM Bot/Mini App parity local validation gate

Status:
`GIFT_WITH_ITEM BOT/MINIAPP PARITY / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`.

Required backend coverage:

- fixed gift and selectable allowlist gift use the same schedule/date/weekday/time and item/category
  target resolver as preview and submit;
- preview emits explicit fixed/selectable/unavailable offer state without writes and issues a
  server-signed decision scope;
- the opaque HMAC-SHA-256 token uses the existing server-secret pattern with
  `gift_decision/v1` domain separation, purpose `gift_decision`, audience
  `hookah-order-submit` and a 10-minute TTL. It binds authenticated user, venue, table session, tab,
  canonical cart fingerprint, promotion/rule/version and offer type, and contains no trusted
  original, discount or final amount;
- the deterministic server-side fingerprint covers venue/session/tab, menu item IDs, quantities,
  sorted selected option IDs, normalized note/comment and promotion context without depending on
  DB row order, client timezone/prices or unsorted JSON;
- submit requires explicit accept/select or scope-bound skip, verifies signature/purpose/audience,
  expiry and complete identity/cart/rule scope, then revalidates current venue time, lifecycle,
  schedule, trigger, required trigger options, allowlist membership, availability/current price,
  session/tab membership, one-gift winner and idempotency;
- legacy unsigned selected-choice/skip inputs fail closed. A stale/tampered/wrong-user,
  venue/session/tab/cart/rule scope creates no partial state and returns
  `Корзина изменилась. Проверьте подарок ещё раз.`;
- trigger removed, rejected, canceled or excluded before authoritative persistence cannot create a
  gift adjustment;
- post-submit cancel-as-unavailable on a trigger atomically cancels its active linked reward;
  trigger exclusion atomically excludes it. Repeat operations and already inactive rewards are
  idempotent, reward-only mutation does not change the trigger, and application/link/config/pricing
  snapshots remain in the audit trail;
- coupled mutations lock deterministically in order/batch → trigger → linked reward →
  link/application order and recalculate the bill inside the same transaction. Injected failure
  rolls back both item states, bill and History; Guest bill, Venue bill, History and staff-chat
  expose the same committed persisted facts;
- unavailable fixed reward, stale selected reward, all-unavailable allowlist and unsupported
  required reward option fail closed without silent substitution;
- changed reward price recalculates through one current snapshot and preserves winner/conflict
  policy;
- at most one gift redemption is persisted for the current batch/tab; multiple trigger quantities
  and multiple eligible gift rules do not multiply rewards;
- reward line snapshots original amount, 100% adjustment and final zero; trigger/reward linkage,
  selected reward item, rule/version and label remain immutable in active order, bill and History;
- reward line receives neither percentage nor manual discount. Its linked trigger also rejects
  manual discount while the reward remains active, using the exact safe copy
  `На эту позицию уже действует акция. Ручную скидку применить нельзя.`; roles do not bypass the
  check, and normal trigger discount policy resumes only after the linked reward is inactive;
- repeated idempotent submit changes no order, batch, application, adjustment or reward-link count;
- gift offer identity, rule version and accept/select/skip decision participate in pricing
  fingerprint/recalculation coverage;
- real PostgreSQL concurrency uses two independent connections and a deterministic barrier, without
  arbitrary sleeps. Two simultaneous submits with one idempotency key persist exactly one batch,
  reward line, application, adjustment, trigger/reward link and idempotency result.

Required Bot/Mini App parity coverage:

- one repository-backed common fixture uses a real rule, real cart, one server offer/resolver and
  one submit path; Bot and Mini App adapters expose the same scope, trigger, allowlist, selection,
  original/adjustment/final facts and persisted application/reward link;
- fixed reward requires visible confirmation; selectable reward requires one explicit choice;
- both clients support `Пропустить подарок`;
- cart changes invalidate stale decisions and cause a new server preview;
- Mini App LocalStorage is UX-only and scoped by authenticated user, venue, table session, tab,
  canonical cart fingerprint and token expiry. Initial restore with no previous tab, account or
  venue switch, session replacement including the same physical QR, tab change and cart
  item/quantity/option/note change clear stale state;
- Telegram callback payloads use amount-free per-offer tags. The process map remains UX-only;
  missing, old-session/tab or mismatched bindings fail safely;
- restart/process-memory loss cannot create a gift from an unconfirmed decision. Fresh
  resolver/router regression serializes fixed accept, selectable choice and skip, clears process
  draft state and submits through the production repository/service path for server revalidation;
- both clients render gift original amount, named 100% adjustment and final zero, then show the same
  persisted bill/History facts;
- all-unavailable reward state uses explicit human copy instead of silently hiding or substituting
  the gift.

Required focused selectors for local regression:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*PromotionRuleEngine*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRepository*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRoutes*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GiftDecisionScopeTokenService*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestOrderRoutes*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueOrderRoutes*' --tests '*VenueOrdersRepository*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVisitRoutes*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VisitRepository*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouter*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*Promotion*Concurrency*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Final local result:

| Validation | Executed / skipped |
| --- | --- |
| `PromotionRuleEngine` | 37 / 0 |
| `VenuePromotionRepository` | 27 / 0 |
| `VenuePromotionRoutes` | 9 / 0 |
| `GiftDecisionScopeTokenService` | 6 / 0 |
| `GuestOrderRoutes` | 51 / 0 |
| `VenueOrderRoutes` + `VenueOrdersRepository` | 54 / 0 |
| `GuestVisitRoutes` | 6 / 0 |
| `VisitRepository` final rerun | 16 / 0 |
| `TelegramBotRouter` | 503 / 0 |
| real PostgreSQL `*Promotion*Concurrency*` with `api.version=1.44` | 6 / 0 |
| deterministic Playwright smoke | 83/83 passed |

The first `VisitRepository` run detected a presentation regression; it was fixed and the final
selector passed 16/0. `git diff --check`, `ktlintCheck`, `compileKotlin` and the Mini App production
build also passed. Preview-no-mutation, transaction rollback, duplicate/concurrent submit,
persisted linkage/history, coupled lifecycle, manual-discount guards including Telegram direct/stale
STAFF denial and repository defense-in-depth, cross-surface parity and fresh-instance
fixed/selectable/skip behavior are covered. This is local evidence only; independent
review, GitHub Actions and staging remain open.

### Venue Promotions Current/Archived Tabs UX local quality gate

Status: **VENUE PROMOTIONS LIST / CURRENT AND ARCHIVED TABS UX / DONE / MVP / STAGING-SMOKE-PASSED**.

Required deterministic browser regression proves:

- `Текущие` is selected by default and contains only loaded `DRAFT`, `ACTIVE` and `PAUSED`
  promotions; `Архив` contains only loaded `ARCHIVED` promotions, with no numeric tab counts and
  only one visible panel/list at a time;
- pause/activation remain in `Текущие`; confirmed archive sends the existing separate `DELETE`,
  removes the refreshed card from `Текущие` and exposes it in `Архив`;
- ordinary same-venue authoritative refresh and `STALE` refresh preserve the selected tab, while
  venue switch clears old cards, resets to `Текущие` and ignores a disposed late response;
- current empty copy is `Текущих акций пока нет.` plus
  `Создайте акцию, чтобы подготовить или опубликовать предложение для гостей.`; archive empty copy
  is `Архивных акций пока нет.`;
- `tablist` / `tab` / `tabpanel`, `aria-selected`, linked controls/panels, roving keyboard focus and
  visible active/focus states remain accessible;
- archived cards remain read-only without readiness validation; Owner/Manager/Staff RBAC and
  Happy Hours/Gift management regression remain unchanged.

This is a frontend-only loaded-response UX. The existing backend/API/DTO/repository and per-list
limit `100` are unchanged. Database-wide totals, pagination, `hasMore`, cursor and server-side
filtering or a separate/lazy archive endpoint remain future follow-up and are not represented by
tab counts. Backend selectors are not required when no backend production file changed.

Required local validation:

```bash
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Current local evidence: Mini App production build passed and the full deterministic browser smoke
passed `136/136`. No backend production file changed, so backend selectors were not rerun.

### Promotion effective state clarity local quality gate

Status: **PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**.

Deterministic browser coverage fixes `Date.now` and proves that the one shared management helper
labels and groups an in-period `ACTIVE` promotion as `Действует сейчас`, future `ACTIVE` as
`Запланирована`, and past-end `ACTIVE` as `Период завершён`. The expired card remains in
`Текущие`, is absent from `Архив`, has guest-hidden explanatory copy and does not expose
`Приостановить`; it exposes `Продлить период`, the existing edit form and archive. `PAUSED`,
`DRAFT` and `ARCHIVED` retain lifecycle precedence even when their period is past. Extending an
expired period uses only the existing update request and authoritative reload, with no status,
archive or automatic lifecycle request as fake time advances. Existing pause/archive/`STALE`,
Happy Hours and Gift smoke remain in the full suite. No backend production file or migration is
required because Guest and rule application already guard `ACTIVE` plus the current date range.

Required local validation:

```bash
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Staging evidence passed for default `Текущие`, current/archive partition with one list visible,
mouse and keyboard tabs, pause staying current, cancel archive preserving status, confirmed archive
moving the refreshed card to `Архив`, archived read-only/no-readiness behavior, Owner/Manager
access, Staff denial, venue-switch isolation and cleanup. Empty-state behavior is additionally
covered by deterministic automated tests. This does not claim search, counts, pagination or a
complete dataset beyond the existing at-most-`100` current and at-most-`100` archived response.

Current effective-state correction: the bounded staging smoke passed with database lifecycle status
unchanged. `DRAFT`, `PAUSED` and `ARCHIVED` retain priority over time; `ACTIVE` is `Запланирована`
before start, `Действует сейчас` inside the inclusive period and `Период завершён` after end.
Expired remains in `Текущие`, is absent from Guest and ineligible for pricing. `Продлить период`
uses the existing update plus authoritative reload. No automatic pause/archive, worker, system actor
or lifecycle audit exists. Live time-boundary refresh, invalid-timestamp fail-safe UI, exact-boundary
fixtures and the duplicate extension/edit action decision remain future.

### Promotion Lifecycle Status Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.

Current correction: the repeated staging smoke passed. Preserve the historical first failed smoke:
pause had applied correctly, a separate archive request followed, and Guest catalog was unavailable
because the venue subscription was `SUSPENDED_BY_PLATFORM`. Guest availability was restored before
the repeated Guest smoke; this subscription incident is not a promotion defect.

Required regression proves:

- Venue Mini App status/archive and Telegram activate/pause/archive use one authoritative
  repository mutation with authenticated server-derived actor and source `VENUE_MINI_APP` or
  `TELEGRAM_BOT`;
- one committed transition writes exactly one `VENUE_PROMOTION_STATUS_CHANGED` or
  `VENUE_PROMOTION_ARCHIVED` row; no-op, stale/repeated, invalid/not-found, RBAC denial, foreign
  venue, audit failure and rollback write no success row;
- parent status, all currently synchronized rule statuses and the audit insert use one JDBC
  connection, transaction and commit. Injected audit failure restores parent/rules plus affected
  timestamps/versions and keeps Guest visibility unchanged;
- the safe payload contains only venue/promotion identity, template type, old/new status,
  server-derived source and rule rows ordered by `ruleId` with id/version/old/new status. It contains
  no promotion text/configuration, prices, reward/menu names, media, raw requests/callbacks,
  Telegram identity fields, `initData`, secrets or client actor;
- real PostgreSQL status/status, status/archive and lifecycle/configuration races preserve the
  existing parent-then-rules lock order and produce only committed winner audit evidence;
- Mini App status/archive returns the existing HTTP success plus authoritative promotion DTO for
  `APPLIED` and `NO_OP`, while `STALE` returns `409` with code
  `PROMOTION_LIFECYCLE_STALE`, safe message
  `Статус акции уже изменился. Обновите список и повторите действие.` and no internal details;
- Mini App never shows lifecycle success copy for `STALE`, performs one authoritative list refresh
  without automatically repeating the mutation and preserves the selected venue;
- archived promotion cards are read-only, expose no unavailable lifecycle actions or publication
  readiness validation, and do not present an unloaded archived rule as missing configuration;
- deterministic browser coverage proves one pause click sends one `PAUSED` status request and no
  `DELETE`, while archive sends one `DELETE` only after the existing explicit confirmation;
- current Owner/Manager behavior, Staff/foreign denial, API response envelopes, Telegram safe
  errors, Guest active/paused/archived visibility, Happy Hours, Gift, bill and History behavior do
  not regress.

Before repeating the Guest promotion staging smoke:

1. Verify the venue status is `PUBLISHED`.
2. Verify the subscription is Guest-available.
3. Open Guest catalog and venue detail successfully before any promotion lifecycle mutation.
4. If the subscription is `SUSPENDED_BY_PLATFORM`, record Guest visibility as
   `BLOCKED_BY_ENVIRONMENT`, not as a promotion regression. Do not change subscription or billing
   state as part of promotion smoke.

Keep the incident promotion archived. Use a replacement promotion created through the normal UI
for the next lifecycle smoke; no raw SQL recovery or archive restore is part of this gate.

Required focused local selectors:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRepositoryTest*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*PromotionConfigurationConcurrencyPostgresTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*AuditLogRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Current local P2 correction evidence: `VenuePromotionRoutesTest` `11/11`,
`VenuePromotionRepositoryTest` `35/35`, Kotlin compile and ktlint, Mini App production build and
the full deterministic browser smoke `134/134` passed.

Staging evidence passed for Owner Mini App status/archive, Manager status transition, Staff and
foreign-venue denial, Telegram activate/pause/archive, Mini App ↔ Telegram parity, Happy Hours and
Gift lifecycle regression, exactly-one audit, no-op without duplicate audit,
actor/source/action/payload privacy, Guest `ACTIVE`/`PAUSED`/`ARCHIVED` visibility, Guest catalog
and detail availability throughout the repeated lifecycle check, and cleanup.

`PromotionConfigurationConcurrencyPostgresTest` is a mandatory real-PostgreSQL gate. Its current
bounded matrix has 13 tests and must report `skipped=0`, `failures=0`, `errors=0`; a missing XML,
zero-test run or Testcontainers skip is a failed security gate. The ordinary release-critical
selector must also execute `VenuePromotionRoutesTest`, `VenuePromotionRepositoryTest` and
`AuditLogRepositoryTest` with nonzero, non-skipped results.

### Promotion Creation Audit quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Regression must preserve the confirmed contract:

- action `VENUE_PROMOTION_CREATED`, entity `venue_promotion`, server-derived actor and source
  `VENUE_MINI_APP` or `TELEGRAM_BOT`;
- parent, caller-connection initial rule and audit use one transaction; one committed parent writes
  exactly one creation audit;
- informational creation has `rules=[]`; Mini App Happy Hours/Gift records the actually created
  initial rule; Telegram Happy Hours/Gift parent-draft creation has `rules=[]`;
- Banner media persists separately and is not part of the creation payload;
- denial, validation, `afterInsert`, SQL and audit failure write no success audit; audit failure
  rolls back parent and initial rules;
- payload is limited to venue/promotion/template identity, `DRAFT`, source and ordered rule
  id/version/status rows and contains no promotion text/config/prices/media, Telegram PII or
  unrelated PII.

Focused repository, route, Telegram and real-PostgreSQL transaction gates must execute with no
skips, failures or errors. The bounded staging smoke is recorded passed for Mini App and Telegram
creation parity, informational/Mini App/Telegram rule projections, exactly-one audit, failure
rollback and payload privacy. This closes only parent creation audit. Configuration edit,
schedule/target/reward, media/banner, Banner retry duplicate-draft UX and broader dangerous-action
audit remain future.

### STAFF ROLE / REMOVAL AUDIT quality gate

Status: **DANGEROUS ACTION AUDIT SLICE / STAFF ROLE AND REMOVAL AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**.

The approved privacy decision is: `target_user_id is permitted only as a dedicated internal audit column. It remains prohibited in JSON, logs, errors and client projections.` Actor remains only in
`audit_log.actor_user_id`; target remains only in nullable `audit_log.target_user_id`. PostgreSQL
V122 and H2 V123 must expose the exact BIGINT/nullability, named FK to
`users.telegram_user_id ON DELETE SET NULL` and ordered `(target_user_id, created_at)` index.

Required automated evidence:

- migration tests start from the previous dialect head, preserve an existing row with NULL target,
  verify exact column/FK/delete rule/index, preserve legacy writer compatibility, store a targeted
  value and keep the audit row with NULL target after deleting a distinct target-only user;
- repository tests prove exact applied role/removal audit, same-role/repeated/not-found zero audit,
  last-owner and stale actor denial, safe payload/logging, and rollback of both mutation and audit;
- route tests preserve Owner/Manager/Staff/foreign/invalid/status/body behavior, derive actor/source
  on the server, return safe audit-failure errors and expose no audit target projection;
- Telegram tests prove Owner role/removal, Manager/Staff denial, success followed by stale callback,
  audit-failure safety, transactional-Forbidden handling, hardcoded `TELEGRAM_BOT`, no direct router
  audit call and no target ID in messages or captured mutation logs;
- real PostgreSQL concurrency tests must deterministically observe both production transactions in
  the `pg_blocking_pids` chain for the ordered membership `FOR UPDATE`, preserve one Owner and match
  the sole audit actor/target exactly to the applied winner.

Recorded local evidence: `AuditLogTargetMigrationH2Test` `2/0/0/0`,
`AuditLogTargetMigrationPostgresTest` `2/0/0/0`, `AuditLogRepositoryTest` `2/0/0/0`,
`VenueStaffRepositoryTest` `7/0/0/0`, `VenueStaffRoutesTest` `31/0/0/0`,
`TelegramBotRouterTableTokenTest` `514/0/0/0`, and
`VenueStaffMutationConcurrencyPostgresTest` `2/0/0/0` (`tests/skipped/failures/errors`). Kotlin
compile and ktlint, Mini App production build and deterministic browser smoke `136/136` passed.

Both PostgreSQL classes remain mandatory CI selectors. Their XML must exist with tests `> 0`,
`skipped=0`, `failures=0`, `errors=0`; the release-critical selector also requires nonzero XML for
the repository and route classes. A missing/zero/skipped XML fails the gate.

Recorded bounded staging smoke: Owner changed STAFF to MANAGER and back with reload persistence and
Manager-only authority; Manager could neither change roles nor remove members; Owner removed the
test Staff and that user lost venue access; last-owner protection held; Mini App and Telegram gave
the same result; no-op/repeat created no extra semantic mutation/audit; role-change audit contained
actor, target, old/new role and source; removal audit contained actor, target, old role and source;
payload exposed no private identity fields; cleanup used the ordinary Staff invite flow.

### Promotion Compatibility Policy audit and future quality gate

Status: **AUDIT / FUTURE IMPLEMENTATION**.

Gift With Item smoke observed Happy Hours Percentage and Gift With Item applied together. Record
this as missing cross-promotion product policy, not as a confirmed runtime bug or a status change
for Happy Hours or Gift With Item. Existing `no stacking` evidence remains bounded to the current
per-line percentage/manual-discount and gift reward guards.

The future implementation must prove one server-owned, reward-type-aware compatibility resolver
for Happy Hours, Gift With Item, personal discounts, loyalty, promo codes and future cashback.
Separate stacking/conflict switches inside individual promotion types are not acceptable.

Required future coverage:

- `STACKABLE` applies every compatible offer and produces one stable final combination;
- `EXCLUSIVE` selects exactly one best offer by explicit promotion priority and a deterministic
  tie-breaker;
- `OVERRIDE` suppresses every other reward/discount in its defined scope;
- discount vs discount defaults to `EXCLUSIVE`; discount vs gift defaults to `STACKABLE`; gift vs
  gift defaults to `EXCLUSIVE` with at most one gift;
- cashback remains a separate future policy within the same resolver and is not enabled before
  its financial/accounting model is defined;
- identical candidates resolve identically regardless of database iteration order, client,
  request ordering, retry or concurrent submit;
- preview and submit use the same current policy/version, revalidate changed state and persist the
  applied combination plus enough safe decision evidence to explain winner/suppression;
- Guest Bot/Mini App show only the final applied combination and totals; Venue Owner/Manager see a
  clear explanation of the effective mode, priority and winner/suppression reason;
- no path accidentally adds discounts, and zero-bound, rounding, idempotency, bill, History and
  staff-chat persisted-fact parity remain intact;
- manual discounts enter the same compatibility decision while preserving actor/RBAC policy,
  including current STAFF denial and direct/stale action rejection;
- future loyalty, cashback and promo codes reuse this gate rather than introduce another resolver.

The observed Happy Hours plus gift combination matches the recommended discount-vs-gift default
only after that policy is explicitly implemented, configured and verified. This audit alone is no
runtime or release evidence.

## GitHub Actions Expectations

Current CI jobs:
- `backend-ktlint`;
- `backend-compile`;
- `backend-release-critical-routes`;
- `backend-venue-booking-rbac`;
- `backend-telegram-lightweight`;
- `backend-migration-sanity`;
- `backend` aggregate;
- `compose`;
- `miniapp`;
- `miniapp-e2e-smoke`;
- `docker`.

Expectations:
- All required jobs must be green before merge/release.
- `backend-release-critical-routes` has separate required steps. The non-PostgreSQL route/security
  selector explicitly executes both menu repository/route suites plus Telegram, resolve, H2
  activation/teardown, mutation-coordinator, order, tab, staff-call, shift-extension, support and
  promotion route/repository/audit classes; its per-class XML assertion fails on a
  missing/zero/skipped/failing suite. The mandatory PostgreSQL section uses two sequential worker
  steps with `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`: the first runs and immediately parses
  `VenueMenuOptionNormalizationConcurrencyPostgresTest` at minimum `44`; the second runs
  `GuestTableContextActivationPostgresTest`, `SupportThreadReadConcurrencyPostgresTest`,
  `PromotionConfigurationConcurrencyPostgresTest`, `VenueStaffMutationConcurrencyPostgresTest`,
  `GuestOrderIdempotencyConcurrencyPostgresTest`, `VenueOnboardingConcurrencyPostgresTest`,
  `BookingConversationConcurrencyPostgresTest` and `BookingMessageIdempotencyPostgresTest`, then
  independently parses all eight XML reports at minimums `8 / 6 / 14 / 2 / 9 / 7 / 1 / 19`.
  Both parsers require `skipped=0`, `failures=0`, `errors=0`; a missing or below-minimum report is
  fatal. Docker availability alone is not evidence, and route failure must not silently skip the
  PostgreSQL gates.
- The route/security selector runs with `TZ=UTC` and also executes and asserts
  `SupportTicketRoutesTest=15`, `SupportThreadReadRepositoryTest=21`,
  `BookingMessageStaffChatNotifierTest=6`, `StaffChatNotifierTest=40`,
  `TelegramOutboxWorkerTest=13`, `GuestOrderRoutesTest=61` and
  `TelegramBotRouterTableTokenTest=553`, preserving cursor/RBAC behavior, fixed confirmed-Platform
  Guest surface denial, caller-owned booking-notifier timezone, privacy-safe staff alerts and
  legacy key-only enqueue/retry alongside the strict booking-only transaction API. The two
  NULL-author repository testcase names, the confirmed Platform Guest wrong-surface testcase name
  and `bot booking create uses zone A once when resolver would next return zone B` are mandatory,
  so unrelated tests cannot satisfy the class floors.
- `backend-venue-booking-rbac` asserts exact XML minima `BookingDisplayLabelTest=2`,
  `GuestBookingRoutesTest=10`, `BookingReminderWorkerTest=3`, `VenueBookingRoutesTest=11`,
  `VenueRbacRoutesTest=36`, `BookingConversationRoutesTest=9` and
  `BookingConversationRepositoryTest=14`. It runs with `TZ=UTC` and requires the exact testcase
  names `guest attendance missing timezone uses Moscow once for response staff and deadline under
  UTC`, `guest attendance Honolulu timezone wins once for response staff and deadline under UTC`,
  `valid Honolulu timezone wins over UTC host for persisted
  deadline reminder and notification`, plus the ordinary Guest wrong-surface contract.
- `backend-migration-sanity` requires Docker and exact production-migration selectors/XML. All
  fourteen selected reports are asserted: Telegram dialog-state `1`, PostgreSQL table-session V28
  `1`, audit-target H2/PostgreSQL `2 / 2`, guest idempotency-fingerprint H2/PostgreSQL `2 / 2`,
  booking-thread H2/PostgreSQL `42 / 47`, audit-key semantic/predicate parity `6`, Mini App message-schema
  H2/PostgreSQL `5 / 5`, cursor H2/PostgreSQL `4 / 4`, and real PostgreSQL migration-lock
  concurrency `2`. Missing, below-minimum, skipped, failed or errored reports fail the existing
  mandatory job.
- `miniapp-e2e-smoke` parses the full structured Playwright JSON and requires at least `216`
  executed with zero failure, flaky, skipped, runner error, missing result, non-passing expectation
  or failed attempt. It additionally fails unless both exact `booking-label-parity.spec.ts` cases,
  the exact replay-one/missing-configuration-zero staff-alert counter cases and all three exact
  Guest surface/deep-link guard cases plus the exact late-previous-surface response guard pass.
- If CI is red, first identify the failing job, failing test class, failing test name, assertion/error and first useful stack frame.
- Do not paste only `Execution failed for task ':backend:app:test'`; inspect XML/test output or CI logs for the actual assertion.
- External/transient failures should be separated from product regressions. A network/dependency timeout is not the same as a Kotlin compile/test failure.

## Change-Type Decision Matrix

| Change type | Required local checks | Required GitHub Actions | Staging deploy | Manual smoke | Rollback risk |
| --- | --- | --- | --- | --- | --- |
| Docs-only | `git status --short`, `git diff --check`, trailing whitespace check for new docs. | Standard CI after push. | No. | No. | Low. |
| Backend-only route/service | Targeted backend tests, `compileKotlin`, `ktlintCheck`. | Backend split jobs. | Usually yes if user-facing/runtime behavior changed. | Area-specific backend/API smoke. | Medium. |
| Mini App frontend-only | `npm --prefix miniapp run build`, targeted/full e2e smoke. | `miniapp`, `miniapp-e2e-smoke`. | Yes if user-facing workflow changed. | Relevant Guest/Venue/Platform smoke. | Medium. |
| Telegram bot/router | `*TelegramBotRouter*`, Telegram lightweight tests, compile/lint. | `backend-telegram-lightweight` plus backend aggregate. | Yes. | Real Telegram bot smoke. | Medium/high. |
| DB migration | Migration tests, app startup/compile, affected route tests. | `backend-migration-sanity`, backend split jobs. | Recommended/usually required. | Startup, health, affected product flow. | High. |
| RBAC/security | Route/RBAC tests, direct forbidden-path tests, compile/lint. | Backend split jobs. | Usually yes. | Role-based smoke. | High. |
| Billing/platform | Platform/billing tests, audit check, compile/lint. | Backend split jobs and Docker. | Yes. | Platform Owner + Venue Owner billing smoke. | High. |
| Order/session/tab | `*GuestOrder*`, `*VenueOrder*`, table/session/tab tests, Mini App e2e. | Backend routes + Mini App e2e. | Yes. | QR/table/order/bill smoke. | High. |
| Staff-chat/notifications | Telegram/staff-chat tests, notifier tests, compile/lint. | Telegram lightweight + backend. | Yes. | Real Telegram group smoke. | High. |
| Support/tickets | `*Support*`, RBAC tests, Mini App build/e2e if UI changed. | Backend split + Mini App if affected. | Yes for runtime. | Guest/Venue/Platform support smoke. | Medium/high. |
| Booking | `*VenueBookingRoutesTest*`, Guest booking/reminder tests if affected, Telegram tests if bot changed. | `backend-venue-booking-rbac`, Telegram lightweight where affected. | Yes for runtime. | Booking lifecycle smoke. | Medium/high. |
| Menu/stop-list | Menu/availability route tests, order stale-availability tests, Mini App build/e2e if UI changed. | Backend + Mini App if affected. | Usually yes. | Menu/stop-list smoke. | Medium/high. |
| Staff Operations / Schedule | `*VenueStaffRoutesTest*`, `*StaffInviteRepositoryTest*`, `*VenueRbacRoutesTest*`, `*GuestVenueRoutesTest*`, compile/lint, Mini App build/e2e. | Backend split + Mini App. | Yes for runtime; migration only when the selected slice has one. | Safe member identity/link projection, Owner/Manager/Staff boundaries, one-active-link and duplicate/race/unlink/audit, restore/create typed conflict, atomic batch, effective hours, timezone/overnight, privacy, Today/Guest regression and venue/account switch. | High for atomicity/RBAC/privacy/time semantics. |
| Guest history/growth | `*Visit*`, `*GuestVisitRoutesTest*`, Mini App build/e2e smoke for UI changes. | Backend split + Mini App if affected. | Yes for runtime. | Guest History or Growth checklist from `docs/GROWTH_RETENTION.md`. | Medium/high for privacy. |

## Standard Pre-Commit Workflow

Use explicit staging. Never use `git add .`.

1. Check worktree:
   ```bash
   git status --short
   ```
2. Check whitespace:
   ```bash
   git diff --check
   ```
3. Run relevant validation commands from the catalog below.
4. Stage explicit files only:
   ```bash
   git add <file1> <file2>
   ```
5. Inspect staged scope:
   ```bash
   git diff --cached --name-only
   git diff --cached --check
   git status --short
   ```
6. Commit with focused message.
7. Push.
8. Wait for GitHub Actions.
9. Deploy staging only if runtime behavior changed and release policy requires it.

## `scripts/dev/` Policy

Current status:
- `scripts/dev/` is an untracked local helper area.
- It must not be staged accidentally.

Rules:
- Do not include `scripts/dev/` in routine feature/doc commits.
- If `scripts/dev/` becomes intentional project tooling later, create a separate task/commit and document ownership, purpose and validation.
- Until then, stage explicit files only and verify `git status --short` before final response/commit.

## Deferred Environment-Dependent Manual Smoke

[`docs/DEFERRED_MANUAL_SMOKE_BACKLOG.md`](DEFERRED_MANUAL_SMOKE_BACKLOG.md) is the
canonical backlog for mandatory manual checks that cannot currently run because required
environments, data, external integrations or physical prerequisites are missing.

Rules:

- keep exact prerequisites, steps, expected behavior, cleanup and result placeholders in that
  backlog instead of duplicating them across strategy/audit/roadmap docs;
- never translate automated evidence into `PASSED` for an environment-dependent manual check;
- a deferred check keeps its feature-specific production-readiness gate open but does not block
  unrelated bounded implementation work;
- move a check to `READY_TO_RUN` only after all prerequisites are confirmed;
- use `PASSED` only after the recorded closure criteria and cleanup are complete.

## Standard Validation Command Catalog

General:
```bash
git status --short
git diff --check
git diff --cached --name-only
git diff --cached --check
```

Backend targeted:
```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*Support*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouter*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueRbacRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueBookingRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestOrder*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueOrder*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
```

Mini App:
```bash
npm --prefix miniapp run build
MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

XML failure scan:
```bash
grep -R "<failure\|<error" backend/app/build/test-results/test || true
```

Docs:
```bash
git diff --check
grep -n '[[:blank:]]$' <new_doc_file>
```

If Gradle OOM occurs:
- split by concrete test class;
- use `--max-workers=1`;
- if needed, rerun with `_JAVA_OPTIONS=-Xmx4g`;
- report whether XML has real `<failure>` / `<error>` markers.

## Area-Specific Smoke Checklist Index

| Area | Canonical doc |
| --- | --- |
| Guest communication | `docs/COMMUNICATION_MODEL.md` |
| Order/session/tab | `docs/ORDER_SESSION_TAB_CORE.md` |
| Venue operations | `docs/VENUE_OPERATIONS.md` |
| Staff profiles / Today Shift / Staff Schedule | `docs/STAFF_PROFILES_SHIFTS_TIPS.md` |
| Menu/stop-list | `docs/MENU_OPTIONS_STOPLIST.md` |
| Venue media storage/upload | `docs/MEDIA_STORAGE_UPLOAD.md` |
| Booking lifecycle | `docs/BOOKING_LIFECYCLE.md` |
| Telegram fallback/staff-chat | `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md` |
| Platform cockpit | `docs/PLATFORM_COCKPIT.md` |
| Security/RBAC | `docs/SECURITY_RBAC_MATRIX.md` |
| Analytics/events | `docs/ANALYTICS_EVENTS.md` |
| Growth/retention | `docs/GROWTH_RETENTION.md` |
| Consolidated Mini App launch smoke | `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` |

## Release / Staging Smoke Policy

Docs-only:
- no staging deploy;
- run local docs sanity;
- wait for CI only after push/PR.

Runtime change touching backend/Mini App/Telegram:
- run relevant local checks first;
- push and wait for GitHub Actions;
- deploy staging only after CI is green unless explicitly doing a debug deploy;
- run product smoke relevant to the changed area.

Current staging deploy command is canonical in `docs/DEPLOYMENT_RUNBOOK.md`:
```bash
STAGING_PATH=/opt/hookah-bot \
STAGING_DOMAIN=staging.hookahtootah.club \
DOCKER_PLATFORM=linux/amd64 \
BACKEND_IMAGE=hookah_bot_ant-backend:staging \
./scripts/deploy-staging-controlmaster.sh hookah-staging
```

Post-deploy minimum:
- `/health`;
- `/db/health`;
- `/miniapp/`;
- changed product flow smoke;
- Telegram/staff-chat smoke if Telegram/staff-chat changed.

Do not claim production readiness from local-only checks.

## Failure Reporting Format

When Actions fail, report:
- failing job name;
- failing test class;
- failing test name;
- assertion/error message;
- first relevant stack frame;
- changed files in the commit;
- last local validation that passed.

Avoid:
- full Gradle tail without the assertion;
- unrelated warnings;
- huge logs unless requested.

Template:

```text
Actions failed in <job>.
Test: <class>.<test>.
Assertion: <message>.
First relevant frame: <file>:<line>.
Relevant changed files: <files>.
Last local validation that passed: <commands>.
```

## Manual Smoke Suites

Guest catalog search/filter:
- open the authenticated pre-QR catalog and confirm its initial request is unfiltered and returns
  only guest-available venues with favorites and today schedule intact;
- search by mixed-case name, city and address, then select a city and confirm backend `q + city`
  `AND` behavior;
- verify `%`, `_`, `!` and `\` are literal search text and oversized `q`/`city` fail safely;
- type quickly and switch filters while delaying an older response; confirm debounce reduces
  requests and the older response cannot overwrite the latest state;
- confirm city options exclude blanks, deduplicate case-insensitively, preserve display spelling
  and stay complete/sorted after search or filtering;
- verify retryable error, unfiltered empty catalog, filtered no-match copy and reset of both controls;
- add/remove a favorite in filtered results, reload the filter and switch accounts; confirm the
  optimistic state does not roll back and no query/favorite state crosses users;
- hide, suspend or subscription-block a matching venue and confirm search reveals neither its name
  nor address; restore it and confirm normal guarded visibility returns.

Guest communication:
- catalog `Задать вопрос` creates/reuses `VENUE_CHAT`;
- booking `Открыть переписку` opens `BOOKING_CHAT`;
- Help creates `SUPPORT_TICKET`;
- staff-call remains separate;
- support/venue chat does not post to staff-chat.

Order/session/tab:
- scan QR;
- personal tab created;
- first batch;
- second batch uses same session/order and new batch;
- second guest personal tab privacy;
- shared tab join by invite;
- close/expire prevents old order reuse.

Venue operations:
- queue sees order;
- detail sees batches/tabs;
- status update works;
- guest creates staff-call;
- staff-call active queue shows only `NEW` / `ACK`;
- staff accepts and completes staff-call;
- guest sees terminal `DONE`;
- auto-cancelled/`CANCELLED` staff-call appears to the same guest in the current table session as `Вызов отменён`;
- venue active queue does not show `CANCELLED`;
- guest does not see another guest/tableSession `CANCELLED` call;
- booking queue works if implemented;
- staff-chat receives order/call only.

Staff Identity Linking UX / Staff Operations Slice A / Staff Schedule staging smoke
(passed, excluding the deferred free-member create-from-member manual scenario below):
- accepted Staff member shows Telegram display name, `@username` when present or `Без username`
  with safe hint, role badge and link status; full raw id is not the main label;
- pending invitation shows role/status/created/expires and authorized revoke only, with no recipient
  identity; after accept it disappears and the active member appears with fresh identity/link state;
- Automated/contract coverage confirms that `Создать карточку` from a member row preselects that
  member/name, creates one Guest-hidden draft, changes the linked member to `Открыть карточку` and
  excludes it from repeat selection. A separate qualifying free-member manual run is not claimed;
- Manager sees active Staff identities/actions only and cannot receive Owner/Manager as editable
  targets; Owner retains current controls and protected/last-owner constraints;
- a second ordinary link is rejected; two concurrent create/link requests have one winner; existing
  duplicate data shows `К этому сотруднику привязано несколько карточек. Выберите основную и
  отвяжите остальные.` with no automatic cleanup or success audit;
- Manager duplicate state is read-only, exposes no arbitrary profile reference and offers no
  open/edit/link/unlink action; Owner opens the concrete wrong card in the common card list and uses
  the existing safe unlink flow; the other card stays linked, every distinct profile/shift/history
  remains visible, and no automatic merge/delete occurs;
- venue switch and account switch clear member identities, selected member, profile/link state and
  late responses; Staff/Guest never receive the internal directory and Guest DTOs remain unchanged;
- Manager sees `Добавить сотрудника`, can create only Staff, sees/revokes only pending Staff and
  cannot use the revoked invite; Owner still creates/sees/revokes Staff and Manager invites;
- Manager creates display-only and Staff-linked cards, edits/publishes/hides them, while Owner and
  other Manager cards are read-only; Owner and Staff self-edit regressions remain unchanged;
- Owner and Manager open `График смен`, navigate weeks, create/edit a future ordinary shift and
  create an overnight shift with `следующий день` copy;
- an `OPEN` date prefills effective venue hours, a date exception wins over weekly, `CLOSED` and
  `NOT_CONFIGURED` stay blank with manual copy, and load error is not shown as not configured;
- manual time survives date change, explicit `Заполнить по часам заведения` reapplies hours, and
  editing an existing shift preserves persisted times until explicit action;
- active shift has no edit action and requires stronger cancel confirmation; completed/canceled is
  immutable except that a future schedule-default canceled row has explicit restore;
- Owner/Manager selects several Staff/display-only profiles, applies common hours, overrides one
  interval, removes one employee and sees exact `Будет создано` / `Будет восстановлено` counts;
- a future canceled historical row remains visible with `Отменена`, old interval and explicit
  `Восстановить`; ordinary create reports the typed canceled conflict and never restores silently;
- scheduled/active/completed conflict blocks confirmation with a safe employee-specific reason;
- one mixed `CREATE`/`RESTORE` request commits all shifts and one audit per row; one stale/invalid/
  conflicting assignment leaves every row and audit unchanged;
- Staff opens `Мои смены`, sees only own rows and colleagues overlapping each row, including one
  display-only colleague; safe `staffProfileId` is allowed, while shift-row ids, private
  account/Telegram linkage and admin actions are absent;
- a second Staff account with no overlap sees no colleague/full-venue schedule;
- stale update is rejected and refresh loads the other actor's state;
- switching venue during a delayed request clears the old week and ignores its late response;
- switching venue also clears selected employees, per-profile interval overrides, confirmation and
  stale conflict state;
- a scheduled/restored row never appears in Guest `Сегодня работают`; manual Today Shift still controls
  Guest presence and does not erase planned times;
- no Telegram reminder/button, staff-chat message or outbox event is created.

Recorded result: **PASSED** after green Actions and staging deploy. At that pre-Slice-B smoke the
source remained `MANUAL`: only explicit manual Today Shift publication made a published
guest-visible card appear in `Сегодня работают`; planned/future schedule rows and the full staff
schedule remained private. The current correction is the Slice B release gate above: a venue may
now save `MANUAL` or active-only `SCHEDULE` with no fallback. The create-from-member step above was
not separately run with a qualifying free Staff member and is deferred only in
`STAFF-IDENTITY-MANUAL-001`.

Menu/stop-list:
- Owner toggles item unavailable;
- guest cannot submit stale unavailable cart;
- unavailable option is blocked;
- Staff/Manager permissions match policy.
- Owner/Manager shift-check draft changes make no request until confirmation;
- cancel and failed validation create no mutation/audit;
- one mixed item/option confirm is atomic and produces one safe completion audit;
- no-op confirm produces one audit with zero changed counts;
- stale expected availability rejects the whole batch and offers refresh;
- Staff entry/direct request and foreign venue request are denied without changing individual
  Staff availability policy;
- venue switch clears draft/selection and confirmed availability reaches Guest menu plus stale cart
  preview/add-batch validation.

Booking:
- Guest creates booking;
- Venue confirms;
- Venue proposes time;
- Guest accepts where implemented;
- Guest/Venue cancels where allowed;
- seated/no-show works only for confirmed bookings;
- pending and changed booking cards have no arrival buttons;
- stale staff-chat booking arrival callback does not change booking state;
- booking chat stays `BOOKING_CHAT`.
- the same booking opened from Guest, Venue Mini App and both Telegram directions resolves one
  thread; distinct bookings at the same venue remain distinct;
- two accounts across two venues cannot list, open or reply to each other's booking conversation;
  Staff and Platform cannot access ordinary booking chat through direct API;
- two simultaneous first opens/messages converge without split threads or message loss;
- an exact Telegram retry persists and enqueues guest notification/acknowledgement once;
- an injected message/update failure rolls the whole booking-message transaction back;
- opening a resolved booking thread displays that exact conversation even when another active chat
  exists or the target is outside the current list window.

Guest History:
- new guest sees empty History state;
- closed order appears in History;
- old closed order with no discounts/options opens detail;
- detail shows positions and total;
- missing `promotionDiscounts`, options or note does not crash;
- booking-only `SEATED` visit can show safe copy if no order lines exist;
- canceled/no-show/expired/pending/changed bookings do not appear as visits;
- `← Назад к истории` returns to the History list;
- Telegram BackButton inside detail returns to the History list;
- real 404 shows `Не удалось загрузить детали истории.`;
- foreign detail returns 404;
- guest does not see another guest's personal tab/order detail;
- shared-tab-only member does not see чужие personal/order details;
- booking `SEATED` + order closed does not double-count the same real visit where merge/dedup applies.

Post-Visit Feedback:
- Owner opens Venue Settings `Ссылка для отзывов` and sees the Yandex Maps/Yandex Business helper plus ethical hint;
- Owner saves a safe public review URL; Bot and Mini App read the same setting;
- Guest submits manual `5/5` from an eligible History detail and sees `Оставить отзыв на Яндекс.Картах` only when the URL exists;
- clearing the URL removes the CTA; no broken CTA or automatic Yandex redirect appears;
- Guest submits `1/5`; the feedback appears in the own-venue Feedback list with low-rating helper;
- Owner/Manager clicks `Связаться с гостем`; the exact `VENUE_CHAT` detail opens with `Отзыв после визита` context;
- an existing active chat is reused with fresh feedback context; a closed/resolved old chat leads to a new active chat;
- Owner/Manager sends a manual reply and Guest receives it in `Чаты`, not Support;
- Staff cannot see the Feedback section or follow-up action, including through direct API;
- feedback submit/follow-up creates no staff-chat notification and no support ticket;
- `VisitFeedbackWorker`, scheduled Telegram feedback prompts, marketing push and automatic Yandex redirect remain disabled;
- booking-only `SEATED` feedback keeps `Можно оценить бронь, встречу и обслуживание.` and non-seated booking outcomes remain ineligible.

Guest Favorites Phase 1:
- add/remove favorite from catalog;
- add/remove favorite from venue detail;
- Account shows the venue-only `Избранные заведения` list and open/book/ask/remove actions;
- empty state shows `Пока нет избранных заведений. Добавляйте их из каталога или карточки заведения.`;
- two authenticated users have isolated favorite state;
- hidden/suspended or subscription-blocked venue is filtered without disclosing its card data, while the favorite row survives temporary unavailability;
- restored/republished venue reappears from the preserved row;
- Bot-created favorite is visible in Mini App and Mini App-created favorite is visible in Bot;
- Telegram Profile and Catalog entrypoints open the shared current-user list;
- Back from Profile-opened favorites returns to Profile, and Back from Catalog-opened favorites returns to Catalog.

Platform/support:
- Platform sees support tickets;
- Platform does not see ordinary `VENUE_CHAT`;
- billing/manual status smoke if changed;
- lifecycle actions require reason/audit where implemented;
- Platform Owner tokenless `/start` opens Platform Mode without active Guest context and keeps Guest table routing while confirmed context is active;
- valid table QR shows safe venue/table labels and exact confirm/cancel, with no pre-confirm context/session/exit/dialog/draft/audit mutation;
- confirm/cancel and double-confirm have one conditional-consume winner;
- `PLATFORM_GUEST_QR_TEST_CONFIRMED` contains only standard actor plus safe venue/table/source fields and means confirmation, not `GUEST_CONTEXT_APPLIED`;
- activation late failures roll back session, exit, dialog and context together;
- exact Platform Owner Mini App re-entry requires matching active server-owned chat context and no exit marker; old token/session entry after exit fails closed;
- `Завершить визит` clears Guest context/dialog/draft/pending and preserves exit semantics despite current token/table/venue/subscription unavailability, then restores Platform menu.

Telegram/staff-chat:
- `/start` without table;
- `/start <table_token>`;
- exact Platform Owner confirm/cancel, opaque pending TTL/lazy cleanup, single-instance topology, stale/rotated/disabled token and audit/repository-failure denial;
- Guest/Staff/Manager/Venue Owner/wrong-chat/expired/replayed direct callback denial;
- concurrent confirm/cancel and double-confirm single-winner behavior;
- confirmed Platform Owner table menu, Guest action routing, guarded Mini App `mode=guest`, availability-independent exit and new-confirmation re-entry;
- fallback order;
- staff call;
- staff-call ACK/DONE;
- guest-visible staff-call `CANCELLED` copy `Вызов отменён`;
- staff-chat notification;
- callback role denial;
- pending booking staff-chat notification has no `Гость пришёл` / `Не пришёл`;
- confirmed booking staff-chat notification has arrival buttons;
- changed booking staff-chat notification has no arrival buttons;
- booking chat message does not appear in staff-chat;
- no support/venue-chat spam.

## Coverage Gaps / Known Risks

- Analytics implementation remains `PARTIAL` unless event emission/payload tests prove coverage.
- Permission parity remains `PARTIAL` unless route tests prove each direct API denial/allow path.
- Staff-call guest-visible `CANCELLED` is closed for the current guest/tableSession; manual cancel UI, quick replies and row-level actor/timestamp gaps remain future unless implemented.
- Real Telegram fallback order smoke remains required for release confidence.
- Platform Owner controlled Guest QR test is `DONE / MVP / STAGING-SMOKE-PASSED`; keep its CI selectors, single-instance topology and bounded Telegram role/privacy/exit scenarios in regression.
- Booking reminders and future no-show automation remain rollout-gated/partial.
- Booking conversation integrity is locally implemented and automated, but release confidence still
  requires green Actions, the production-data preflight/migration and bounded two-account real
  Telegram/Mini App staging smoke. Local tests are not evidence that production legacy rows are safe.
- Advanced support and billing/provider features remain future unless implemented and smoked. Growth remains partial, but Post-Visit Feedback MVP and venue-only Guest Favorites Phase 1 are staging-smoke-passed and stay in regression. Repeat Phase 1 is locally validated with deferred manual smoke in `REPEAT-MANUAL-001`; persistent templates, favorite menu items/options, recommendations/frequent items, notification opt-in, favorites-based promotions and loyalty remain future until their own bounded implementation evidence exists.
- Menu shift check is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED** and stays
  in regression. Per-venue `staff_stoplist_enabled` remains future.
- Staff Schedule Phase 1, Canceled Shift Restore + Bulk Assignment, Staff Operations Slice A and
  Identity Linking are `DONE / MVP / STAGING-SMOKE-PASSED`. Identity/linking,
  duplicate/race/Owner-only repair, restore/batch atomicity, typed-conflict,
  privacy/RBAC/effective-hours/Today compatibility remain regression gates.
- Staff-chat delivery history/personal notifications/topic routing remain future.
- CI coverage is strong for release-critical slices but not proof of every product scenario; area smoke checklists remain necessary.

## Roadmap Status

- Testing/QA smoke strategy: `UPDATED`.
- Booking conversation UX / distinct labels, inbox and unread discoverability:
  **MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. The current local
  closure includes NULL-author system unread, fixed Guest queue/type guards and the documented V126
  mixed-version boundary, but still requires independent review, green Actions, a drained
  single-new-image migration rollout and bounded staging smoke. The preceding V124/V125 integrity /
  real-isolation slice remains historical regression evidence and separately review-required; it is
  not reopened or marked done here.
- Catalog search/filter: **CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP /
  STAGING-SMOKE-PASSED** after green Actions, staging deploy and limited-dataset manual smoke.
  Extended dataset coverage remains non-blocking deferred manual smoke in
  `CATALOG-SEARCH-MANUAL-001`.
- Guest Preview Phase 2.1: **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**.
- Menu shift check: **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**; regression
  gates remain active.
- Menu item hard-delete audit: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE /
  MVP / STAGING-SMOKE-PASSED**. The existing mandatory PostgreSQL configuration class keeps the
  config/delete race and non-skipped XML gate. Option delete, price/update/availability and
  the broader audit program remain open.
- Menu category hard-delete audit: **DANGEROUS ACTION AUDIT SLICE / MENU CATEGORY HARD DELETE AUDIT /
  DONE / MVP / STAGING-SMOKE-PASSED**. H2/route/Telegram/privacy/rollback and PostgreSQL
  config/delete race gates remain in regression; CI minimum is 14. No migration was added.
- Menu option hard-delete audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION HARD DELETE AUDIT /
  ATOMIC BASE-PROFILE NORMALIZATION INCLUDED / DONE / MVP / STAGING-SMOKE-PASSED**. Focused local,
  mandatory PostgreSQL, green Actions, staging deploy and the bounded 17-scenario smoke are
  recorded complete; keep the contract in regression.
- Menu option create audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. Repository `41/0/0/0`, routes `37/0/0/0`, Telegram `538/0/0/0`,
  route/security `1137`, PostgreSQL `26/0/0/0`, compile/ktlint, Mini App build and Playwright
  `169/169` are automated evidence; user-confirmed Actions, staging deploy and bounded smoke close
  only this contract. No migration.
- Menu item create audit: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. Repository `44/0/0/0`, routes `40/0/0/0`, Telegram `542/0/0/0`, shared
  PostgreSQL `31/0/0/0`, compile/ktlint, Mini App build and Playwright `169/169` are automated
  evidence; user-confirmed Actions, staging deploy and bounded smoke are complete; no migration.
- Venue Menu Management existing-contract closure: **DONE / MVP / STAGING-SMOKE-PASSED**. Mandatory
  minima are repository `51`, routes `43`, Telegram `549`, Guest routes `61` and shared PostgreSQL
  `40`; user-confirmed green Actions, staging deploy and consolidated smoke are complete. No
  migration.
- Menu option rename audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION RENAME AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. The current 44-test shared menu concurrency XML gate, focused cross-surface tests,
  privacy/rollback checks, green Actions, staging deploy and bounded smoke are recorded complete.
  No migration was added.
- Menu option price audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. Focused repository/route/order/history, current 20-test shared PostgreSQL, build/lint
  and `152/152` browser checks remain regression evidence; user-confirmed green Actions, staging
  deploy and bounded smoke close only this contract. No migration was added.
- Menu option availability audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT /
  DONE / MVP / STAGING-SMOKE-PASSED**. Keep actor/source, exact-one/no-op, rollback and Shift Check
  aggregate-only behavior in regression.
- Menu item availability audit: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT /
  DONE / MVP / STAGING-SMOKE-PASSED**. Focused repository/routes/Telegram/Guest and deterministic
  PostgreSQL gates remain regression evidence; user-confirmed Actions, staging deploy and bounded
  smoke close only this contract. GitHub CLI did not independently query Actions because its active
  token is invalid. No migration was added.
- Guest cart stale menu recovery: **GUEST CART STALE MENU SELECTION RECOVERY / REMOVED OR UNAVAILABLE
  ITEMS AND OPTIONS / PAYLOAD-BOUND IDEMPOTENCY + ATOMIC REJECTION / DONE / MVP /
  STAGING-SMOKE-PASSED**; **ITEM-LEVEL ACTION AND COPY POLISH / DONE / MVP /
  STAGING-SMOKE-PASSED**. Exact route/repository/migration/PostgreSQL concurrency and `169/169`
  browser checks remain regression evidence. The user confirmed green Actions, staging deploy and
  only the bounded smoke recorded in the launch checklist; this does not independently verify
  rollout topology.
- Venue Promotions Current/Archived Tabs UX: **VENUE PROMOTIONS LIST / CURRENT AND ARCHIVED TABS UX / DONE / MVP / STAGING-SMOKE-PASSED**.
- Promotion lifecycle status audit: **DANGEROUS ACTION AUDIT SLICE / PROMOTION LIFECYCLE STATUS AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**; broader dangerous-action coverage remains partial.
- Promotion creation audit: **DANGEROUS ACTION AUDIT SLICE / PROMOTION CREATION AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**; mandatory repository/route/Telegram and PostgreSQL gates remain regression requirements. Configuration edit, schedule/target/reward, media/banner and broader dangerous-action coverage remain open.
- Promotion effective state clarity: **PROMOTION EFFECTIVE STATE CLARITY / DONE / MVP / STAGING-SMOKE-PASSED**; time-derived presentation does not rewrite lifecycle state.
- Staff Operations Slice A:
  `MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP / STAGING-SMOKE-PASSED`.
- Staff Schedule Phase 1:
  `STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`; Canceled Shift Restore + Bulk
  Assignment and Identity Linking are also `DONE / MVP / STAGING-SMOKE-PASSED`; the Phase 1
  schedule/identity schema verdict remains `NO_MIGRATION_EXPECTED`.
- Staff Operations Slice B:
  `STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`; local validation, independent review, green Actions,
  staging deploy, PostgreSQL V121 rollout and manual smoke are complete.
- Manual smoke checklist: `CONSOLIDATED`.
- CI coverage: `PARTIAL / release-critical split jobs current`.
- Frontend e2e: `PARTIAL`, with smoke coverage documented.
- Real Telegram smoke: `REQUIRED` for bot/staff-chat changes.
- Platform Owner controlled Guest QR test: **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**; `NO_MIGRATION`, bounded CI/deploy/staging smoke complete.
- Staging deploy smoke policy: `DOCUMENTED`.
- Venue media foundation quality gate: `DOCUMENTED / STORAGE DECISION REQUIRED`.

## Codex Workflow Guidance

Every future Codex implementation task should end with:
- changed files;
- behavior summary;
- tests run;
- validation result;
- manual smoke checklist;
- `git status --short`;
- whether `scripts/dev/` was touched;
- whether staging deploy is needed.

For ChatGPT handoff after a Codex summary, paste:
- Codex final summary;
- `git status --short`;
- any CI failure details if present.

ChatGPT should return:
- exact `git add` file list;
- commit message;
- push instructions;
- deploy/staging smoke instructions where needed.
