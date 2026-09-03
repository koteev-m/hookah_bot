# Project Status

Last verified: 2026-09-03.

## 1. Current stage

**HT-12R TRACKED V126 PRE-GATE-A PREREQUISITE SYNC ORCHESTRATOR /
EXACT MAIN BASE AND 12/12 MAIN ACTIONS VERIFIED /
TRACKED FAIL-CLOSED IMPLEMENTATION AND COMPLETE LOCAL FIXTURE HARNESS ADDED /
COMPLETE LOCAL VALIDATION PASSED /
INDEPENDENT READ-ONLY REVIEW AND EXACT GREEN FEATURE-BRANCH ACTIONS ARE MANDATORY BEFORE INTEGRATION /
NO MAIN INTEGRATION, STAGING ACCESS OR CUTOVER STARTED**.

Fresh required `origin/main` is exact commit
`a648e75179975c97daa4b3dae03070e6476d8a9a`, tree
`14f2400434c8546f5aba7c4bcc94fd20622625d1`, parent
`15a996575dcb863d7f4fe2f8110d3d56399fa354`. Exact main Actions run `33658844231` is workflow
`CI`, event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `12/12`
jobs succeeded. HT-12R runs only in a clean isolated worktree on branch
`codex/ht-12r-tracked-prerequisite-sync`; the original local main worktree remains untouched.

`scripts/v126-staging-prerequisite-sync.sh` is the sole tracked command authority for synchronizing
the exact release Compose file, maintenance guard, admission guard and three maintenance-`OFF`
`.env` keys before Gate A. Its helper, 40-entry named check map and local-only mocked harness are
tracked beside it. The command verifies exact release Git-object bytes, the green main release
identity, V125/Flyway/Caddy/runtime baseline, durable hash-chained checkpoints and rollback inputs;
then it permits only four atomic staging-file writes, executes independent post-sync checks, records
the first failure before one bounded rollback, reverifies the restored V125 baseline, and preserves
the original nonzero exit status. Every remote argv is serialized into one shell-quoted SSH command;
each action is timeout-bounded and holds the same restricted per-run lock, so signal handling waits
for mutation quiescence before rollback. Successful completion stops with `GATE_A=NOT_STARTED`.

The complete local mocked harness passed all `17/17` fixture groups: the success path; `4/4`
during-write and `4/4` after-write failures; `40/40` independently checkpointed post-sync
failures; exact one-rollback, original-status and no-later-phase assertions; active-remote
SIGINT/SIGTERM with repeated-trap handling; natural health/version `curl`, read-only database,
TLS-1.3 client, `docker logs` and `ss` execution failures in prewrite and the corresponding
post-sync checks; strict canonical/status-bound first-failure
and tri-state write-evidence rejection; real helper-backed parent-directory-fsynced unlink;
zero/partial transfers; source/helper/checkpoint integrity; exact `.env` preservation; PRODUCT and
Caddy helper plus inline-prewrite matrices; and zero real external calls. The unchanged 20-stage V126 sequencer harness and
the maintenance, image-identity and admission guard self-tests also passed. Compose parsed from a
disposable copy using `.env.example`; immutable migration identities remain exact.

The HT-13 R1-R5 temporary controllers, including the terminal R5 false Caddy-prefix assertion, are
rejected historical evidence only. Installed Caddy output `2.6.2` is accepted as the same exact
semantic version as `v2.6.2`; malformed, ambiguous or different versions still fail while the
Caddyfile hash, active service, validation, drain, TLS, Alt-Svc and UDP checks remain independent.
No R6, temporary dispatcher, temporary execution package or external dispatcher is permitted.

Prerequisite sync is separate from `scripts/v126-cutover.sh` Gate A. A successful sync does not
authorize backup or rehearsal creation, drain activation, Caddy change, image transfer/load,
container restart/start, maintenance `V126_SMOKE`, Flyway/V126, product/database/Telegram writes or
any cutover stage. After later authorized HT-12R integration, the exact main release SHA and V126
image tag/ID must be reselected and proved again; no historical HT-13 authorization or image
identity is reusable. The current required stop after local validation, review, feature-branch push
and exact green branch Actions is `HT12R_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`.

### Preserved HT-12Q preintegration evidence

**HT-12Q REPRODUCIBLE GRADLE ARCHIVE AND DETERMINISTIC V126 IMAGE CLOSURE /
EXACT MAIN BASE AND 11/11 MAIN ACTIONS VERIFIED /
PRE-FIX WALL-CLOCK JAR TIMESTAMP DEFECT REPRODUCED /
MINIMAL BACKEND APPLICATION ARCHIVE CONFIGURATION IMPLEMENTED /
POST-FIX JAR AND INSTALLDIST REPRODUCIBILITY PROVED /
TWO INDEPENDENT CACHE-FREE LINUX/AMD64 IMAGE BUILDS IDENTICAL /
LOCAL RELEASE GUARDS AND INDEPENDENT READ-ONLY REVIEW PASSED / NO P0, P1 OR BLOCKING P2 /
FEATURE-BRANCH COMMIT, NON-FORCE PUSH AND EXACT GREEN ACTIONS REQUIRED /
NO MAIN INTEGRATION, STAGING ACCESS OR CUTOVER STARTED**.

Fresh required `origin/main` is exact commit
`15a996575dcb863d7f4fe2f8110d3d56399fa354`, tree
`3802e89a61190066c369cfe3293dd5e6ff1ad809`. Exact main Actions run `33632063646` is workflow
`CI`, event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `11/11`
jobs succeeded with zero adverse job conclusions. HT-12Q runs only in the clean isolated worktree
for branch `codex/ht-12q-reproducible-gradle-image`; the original local main worktree is unchanged.

The exact `:backend:app:installDist` task graph is
`checkKotlinGradlePluginConfigurationErrors -> compileKotlin/compileJava -> processResources ->
classes -> jar -> startScripts -> installDist`. The only archive task consumed by this graph is
`:backend:app:jar`, producing `backend/app/build/libs/app-0.1.0.jar` and installing the same bytes as
`lib/app-0.1.0.jar`. Two independent pre-fix clean, no-build-cache, forced builds produced raw JAR
SHA-256 values `b491acaf9aaf59d67396b1340431e164f16b5df36a30af16408c62652abee189` and
`47a579ff54afbe6ff017a073ae4d0afb24bcd4181518ee0e99c670560a595979`. All `4216` entry names,
their order and uncompressed contents matched, but all `4216` ZIP timestamps differed. The complete
`installDist` path/type/mode manifest retained `96` regular files, no symlinks and no unexpected
types; only the application JAR hash differed.

`backend/app/build.gradle.kts` now configures every `AbstractArchiveTask` in the backend application
project with `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true`. This is the
smallest scope covering the complete current `installDist` archive graph while also preventing a
future backend-app archive task from silently retaining timestamps. No compression, permission,
manifest, version, Dockerfile, runtime, sequencer or migration semantics changed.

`scripts/check-gradle-archive-reproducibility.sh` performs two independent `clean` plus
`installDist` builds with `--no-build-cache` and `--rerun-tasks`, separated beyond ZIP timestamp
granularity. It requires raw JAR equality, equal unique entry names and order, Gradle's fixed
`1980-02-01T00:00:00` timestamps, a complete equal regular-file SHA-256/type/mode manifest, exact
launcher and library modes, the generated/installed application JAR identity and zero symlinks or
unexpected paths/types. The check reports only relative distribution paths, labels and hashes. It
failed on the uncorrected base for raw JAR/timestamp/installDist inequality, then passed after the
Gradle correction with both JARs equal to
`34e9b501171df13ea9d84ca8e620c8b81302f2dd7630cb9e9734445688738935`. A dedicated
`backend-archive-reproducibility` CI job runs the guard on Java 21 and is a mandatory dependency of
the existing `backend` aggregate; no existing job, selector or minimum was removed.

Two separate run-created Buildx docker-container builders independently built the same candidate
working tree for `linux/amd64` with `--pull`, `--no-cache`, `--provenance=false`, identical staging
public URL and Gradle JVM arguments, and `SOURCE_DATE_EPOCH=1788351553` derived from the exact source
HEAD timestamp. BuildKit rewrote layer timestamps to that epoch. Both builds produced application
JAR SHA-256 `34e9b501171df13ea9d84ca8e620c8b81302f2dd7630cb9e9734445688738935`, application-layer DiffID
`sha256:8e76286006db24b72ccb0d3c3fcae4099724252de9e10fb337af344d86ebf4a3`, compressed application
layer digest `sha256:4722a47e4a36e34031010d1e08223bed2fab42d49b56ac168e5f4fc0e07b22e1`, image config
`sha256:486427e6721401e8bbcac082d6bfbf5cd91aa7e6eab73af53020b726f7f86b44` and final image ID
`sha256:872df96da5f5f0948b00572617dd7a521c9f7057f6ee52da8886dfcd4bf5893f`. Both complete ten-layer
inventories matched. Only the run-created stopped extraction containers, test tags, builders,
builder caches and temporary evidence directories were removed after hashes were recorded.

Local validation passes the new double-build guard, backend compile and ktlint, the full HT-12P
sequencer harness, maintenance/image/admission guards and Compose validation. The immutable complete,
PostgreSQL and H2 migration trees remain `765956602de896b4498a956753272a6bc2d2971e`,
`bb2778e26e03e03211eab9f149777313f4a6f24b` and
`07b5ba6ccf25e79c9cc419b9095bb664f2cfae18`; PostgreSQL V126/H2 V127 retain blob
`6f39f7d33b1976d0f5eb7a70051bfc5351d12e56`, SHA-256
`ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5` and Flyway checksum
`1701638026`.

The independent read-only build/release review passed with no P0, P1, P2 or P3 findings. The
remaining HT-12Q gates are a minimum cohesive feature-branch commit, ordinary non-force push and an
exact green branch Actions run whose remote SHA equals the reviewed candidate. Main integration,
staging/production access, SSH, upload, deployment and V126 execution remain unauthorized. The
required stop after those gates is `HT12Q_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`; HT-13 must not
start automatically.

### Preserved HT-12P preintegration evidence

The historical HT-12P snapshot below is preserved as sequencer evidence. Its then-pending branch
state and its narrower image-build restriction are not current HT-12Q authority; HT-12P is already
integrated into the exact HT-12Q main base above.

**HT-12P V126 EXECUTABLE CUTOVER CONTRACT AND SEQUENCER CLOSURE /
EXACT MAIN BASE VERIFIED / HT-13 PREDEPLOY CONTRACT STOP RECORDED /
FEATURE-BRANCH ACTIONS EXPOSED A CLEANUP-TRAP ERROR-UNWIND DEFECT /
EXPLICITLY APPROVED CLEANUP HARDENING IMPLEMENTED / LOCAL 441-ASSERTION ADVERSARIAL VALIDATION PASSED /
INDEPENDENT READ-ONLY RE-REVIEW PASSED / FINAL CLEANUP COMMIT AND NON-FORCE PUSH REQUIRED /
EXACT GREEN POST-FIX FEATURE-BRANCH ACTIONS REQUIRED /
NO STAGING ACCESS OR CUTOVER STARTED**.

Fresh required `origin/main` is exact merge commit
`ecb09601975678a41d89e5c824cc7812c7876481`, tree
`8c97996e317f0182b4871d2a2537a732d4830f64`, with ordered parents
`9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1` then
`d9c656b1c5feb757b79558209f130c08cba81cf5`. Exact main Actions run `33536142005` is workflow
`CI`, event `push`, branch `main`, attempt `1`, exact head SHA and `completed/success`; all `11/11`
jobs succeeded with zero adverse conclusions. Any fresh-base mismatch stops as
`MAIN_BASE_DIVERGED_BEFORE_HT12P`. This merge is the HT-12P base, not the final V126 release SHA.

HT-12M is integrated and closed with final verdict
`IDENTITY_GATED_MAINTENANCE_PREREQUISITE_COMPLETE`. Its historical port base was
`b49a89a299d8c9864fcfc5937d455141563b388a`; the integration commit is
`f837b0ed01f68832b305d5a2ed61b3927583f1e9`. The authoritative V125 staging source remains
`f577934691a1a7a79ba327c54e2055425142b7be`, with image ID
`sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`,
`TELEGRAM_TRAFFIC_POLICY=PRODUCT`, maintenance `OFF`, empty product and maintenance lists, one
backend/poller, healthy PostgreSQL, Flyway V125 and V126 absent. Detailed immutable mapping,
validation and review evidence remains in
`docs/HT12M_IDENTITY_MAINTENANCE_MAIN_PORT_EVIDENCE.md`.

HT-13 stopped before run-namespace allocation and before sealed-input creation with
`PREDEPLOY_CONTRACT_NOT_PROVABLE`. No HT-13 package was created and no staging mutation occurred.
That attempt and every historical HT-12D through HT-12K artifact remain rejected background
evidence, not reusable cutover authority.

HT-12P makes the contract executable through `scripts/v126-cutover.sh`, with bounded static and
fixture proof in `scripts/test-v126-cutover.sh`. The scripts are command/test authority;
`docs/V126_STAGING_CUTOVER_CONTRACT.md` is ordered policy and state-machine authority; other runbooks
link to them rather than duplicating commands. One immutable run manifest, intent-before-action
records, hash-chained stage receipts and separate Gate A/B/C authorizations enforce all 20 stages.
Every state has an exact artifact inventory plus a real mode-0400 operation log whose emitted
artifact lines are replayed; a rechecksummed substituted, missing or extra artifact is rejected.
Remote actions exist only behind a streamed stdin envelope bound to the run/script/intent/gate and
exact stage-action tuple, not a public helper command. The state lock tracks owner, worker, SSH and
rsync children, exits on signals and allows only an atomic dead-lock recovery takeover after all
liveness is disproved.

State 1 content-binds the restricted database/identity files and the exact `RELEASE_SHA` Compose,
maintenance-guard and admission-guard sources; the later HT-13 preparation must make that remote
execution surface exact before baseline can pass. Release/object reads clear inherited Git controls,
disable replacement objects and use exact blob plumbing. The no-build image transfer snapshots one
mode-0400 unlinked descriptor for local parse/hash/upload and another for remote parse/hash/load, with
actual Bash-3.2/openrsync-compatible syntax; no post-verification pathname is executed. Transfer and
later startup are separate states. Startup resolves the backend service specifically, disables
restart, requires `RestartCount=0`, executes one explicit start and proves one long-polling V126
backend with global
V125 zero and staging-project old-image zero. Caddy candidate activation is proved before the drain
marker; Gate B requires the exact 17-assertion evidence schema and a protected unauthenticated
generic-`503` proof.
Recovery classifies Flyway before Caddy/backend mutation, covers partial Caddy activation and
terminalizes exact-V126, already-stopped, unexpected-V125 or unknown-image post-V126 states safely.
Pre-V126 rollback, post-V126 forward-fix stop and full-DR prerequisite verification remain bounded
executable branches.

Feature-branch Compose run `33595906441` first exposed a harness-only GNU `stat` portability defect;
normal commit `4a33dd79b54ca2bbc8ee6d7296fabec0668696d8` corrected it. The next exact branch run
`33596741034` then exposed a real Bash error-unwind defect: cleanup traps referenced function-local
paths after those locals had left scope. With explicit
`APPROVE_HT12P_CLEANUP_TRAP_HARDENING`, all seven cleanup families now bind concrete targets before
use, preserve the primary exit/signal status and exercise exact mocked cleanup. Restore-rehearsal
container/volume cleanup additionally pre-arms exact names and verifies a run-owner label before use
or deletion. Neither failed CI run nor this local hardening accessed staging or created real Docker
resources.

Post-hardening branch run `33628764473` passed the sequencer through the cleanup/rehearsal fixtures,
then proved that the Compose job's depth-1 checkout omitted immutable base object
`ecb09601975678a41d89e5c824cc7812c7876481:docs/DEPLOYMENT_RUNBOOK.md`. The exact-base rejection test
therefore stopped before its intended assertion. The Compose job now fetches full history so that
the frozen baseline object is actually present and tested; no test assertion or execution boundary
was weakened.

The frozen sequencer SHA-256 is
`41f0b1d98e848e65b3bc4b605cb1f53ce28081c3e6ecf9153328d4b77486d1d4`; the frozen harness SHA-256 is
`6f6e746d3f16eb4bf0090aecec1513d23071f87046242fb99cba9754ad5a5e17`. The complete local harness
passed all 441 bounded assertions after cleanup and documentation reconciliation. This is local
fixture evidence only. Independent read-only final re-review passed with no P0, P1 or blocking P2;
exact post-fix feature-branch Actions are not yet claimed.

Normal public-pilot staging remains `PRODUCT` with maintenance `OFF`; temporary `V126_SMOKE` remains
an identity-gated migration-window overlay, never a replacement for membership or RBAC. Permanent
staging `ALLOWLIST`, stable client CIDRs and Caddy/source-IP attribution remain rejected historical
experiments. The immutable PostgreSQL/H2 migration trees, blobs and runtime behavior are unchanged.

This task authorizes local implementation and tests, independent read-only review, feature-branch
commit/push and branch Actions. It does not authorize main integration, staging or production access,
SSH, backup/rehearsal creation, image build/transfer, service mutation, maintenance activation,
Caddy reload/restart, Flyway/V126, Telegram/database writes or cutover. After local/review PASS and
an exact green feature-branch Actions run, the next gate is
`HT12P_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`. Only a later exact authorization may integrate the
branch, select the final release SHA after a new green main Actions run and repeat deterministic
two-build image proof.

The booking release line below is a preserved historical preintegration task snapshot. It is not
the current HT-12P stage, release identity or executable state/receipt status.

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

Release HEAD `e35def99ea8429462e5fdaaeee914f57da72e775` matched `origin/main` at that recorded closure.
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

Media/object-storage work remains blocked by `MEDIA-STORAGE-DECISION-001`; that historical Goal did
not implement or modify Media/R2. Its then-recorded next step was independent review, explicit
commit, green Actions, the additive cursor migration, staging redeploy and bounded
Guest/Venue/Telegram conversation smoke. The current repository has since integrated the code,
HT-12M prerequisite and HT-12C policy baseline. HT-12P's only current next gate after complete
local validation, independent review, commit/push and exact green branch Actions is the explicit
main-integration authorization recorded in section 1. The previous integrity release retains its
separate historical V124/V125 preflight/migration evidence.
