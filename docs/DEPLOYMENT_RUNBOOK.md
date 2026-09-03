# Deployment / Runbook / Operations

Дата актуализации: 2026-09-02.

Статус: **current operations reference / UPDATED**. This document is the canonical deploy, release and operations runbook for the Telegram bot + Mini App platform. Use it together with `docs/TESTING_QA_SMOKE_STRATEGY.md` for validation scope, `docs/STAGING_DEPLOYMENT.md` for one-VPS staging details, `docs/OPERATIONS.md` for metrics/queue incident basics and `docs/MIGRATION_POLICY.md` for Flyway policy.

## Core Rule

Do not mix docs-only work with runtime releases. Runtime changes must pass local checks, GitHub Actions, staging deploy and area smoke according to blast radius. Docs-only changes need local docs sanity and CI after push, but no staging deploy.

Current practice:
- Staging is a one-VPS Docker Compose deployment documented in `docs/STAGING_DEPLOYMENT.md`.
- The Mini App production bundle is built into the backend image for staging.
- Current staging URL is `https://staging.hookahtootah.club`.
- The standard staging deploy script exists, and the ControlMaster wrapper is an opt-in reliability workaround for unstable fresh SSH connections.
- Production deploy automation, exact rollback commands and complete log command coverage remain **needs verification** unless a future task confirms them.

Target runbook:
- Every release path has an explicit validation gate.
- Every runtime release has a staging smoke owner and changed-area checklist.
- Every migration has a compatibility and rollback decision.
- Incidents are triaged by severity, mitigated first, then documented in follow-up Codex tasks.

## Release Model By Change Type

| Change type | Required validation | GitHub Actions | Staging deploy | Smoke requirement | Notes |
| --- | --- | --- | --- | --- | --- |
| Docs-only | `git diff --check`; trailing whitespace check for new docs. | Must pass after push/PR. | No. | No, unless docs changed release checklist itself. | Do not run backend/frontend tests unless docs tooling requires it. |
| Backend/runtime | Targeted backend tests, `compileKotlin`, `ktlintCheck`. | Backend split jobs must pass. | Required for user-facing/runtime behavior. | Changed-area API/product smoke. | Include rollback risk in final summary. |
| Mini App/frontend | `npm --prefix miniapp run build`; targeted or full e2e smoke. | `miniapp` and `miniapp-e2e-smoke` must pass. | Required for user-facing workflow changes. | Browser and Telegram Mini App smoke. | Verify real Telegram WebView when `initData` or routing changed. |
| DB migration | Migration tests/app startup, affected route tests. | `backend-migration-sanity` and backend jobs. | Required. | Verify app startup and affected product flows. | Rollback plan must be explicit before deploy. |
| Telegram bot/staff-chat | Telegram router/notifier tests, compile/lint. | Telegram lightweight and backend jobs. | Required if behavior changed. | Real Telegram private bot and staff-chat group smoke. | Staff-chat is radar/shortcut, not source of truth. |
| Billing/webhook/security | Targeted backend/security tests, audit/log check. | Backend, Docker and affected jobs. | Required. | Provider-safe staging smoke and audit verification. | No production provider switch without provider test confirmation. |

## Pre-Push Checklist

Use explicit staging. Never use `git add .`.

1. Inspect worktree:
   ```bash
   git status --short
   ```
2. Check whitespace:
   ```bash
   git diff --check
   ```
3. Run relevant validation commands from `docs/TESTING_QA_SMOKE_STRATEGY.md`.
4. Stage explicit files only:
   ```bash
   git add <file1> <file2>
   ```
5. Inspect staged files and staged whitespace:
   ```bash
   git diff --cached --name-only
   git diff --cached --check
   ```
6. Commit:
   ```bash
   git commit -m "<focused message>"
   ```
7. Push the reviewed feature branch (never `main` without its explicit gate):
   ```bash
   git push --set-upstream origin '<feature-branch>'
   ```
8. Check GitHub Actions.
9. Deploy staging only if runtime behavior changed.

`scripts/dev/` policy:
- `scripts/dev/` is currently an untracked local helper area.
- Do not stage it in product/docs/runtime commits.
- If it becomes intentional project tooling later, create a separate task, document ownership and validate it separately.

## GitHub Actions Policy

Actions must pass before treating a change as merged or release-ready.

If Actions are red, report:
- failing job name;
- failing test class;
- failing test name;
- assertion/error message;
- first relevant stack frame;
- changed files in the commit;
- last local validation that passed.

Avoid:
- pasting only the Gradle task tail;
- unrelated warnings;
- huge logs unless requested.

Failure report template:

```text
Actions failed in <job>.
Test: <class>.<test>.
Assertion: <message>.
First relevant frame: <file>:<line>.
Relevant changed files: <files>.
Last local validation that passed: <commands>.
```

## Current Staging Deploy Command

Use this only for an ordinary public-pilot deployment after GitHub Actions are green for runtime
changes, unless explicitly doing a debug deploy. It is never a PostgreSQL V126 cutover, transfer,
startup or recovery command:

```bash
STAGING_PATH=/opt/hookah-bot \
STAGING_DOMAIN=staging.hookahtootah.club \
DOCKER_PLATFORM=linux/amd64 \
BACKEND_IMAGE=hookah_bot_ant-backend:<candidate-sha> \
EXPECTED_BACKEND_IMAGE_ID=sha256:<reviewed-image-id> \
./scripts/deploy-staging-controlmaster.sh hookah-staging
```

Notes:
- Standard deploy is still documented in `docs/STAGING_DEPLOYMENT.md`.
- The ControlMaster command is the current preferred reliability path when fresh SSH connections are unstable.
- The only V126 pre-Gate-A prerequisite-sync command is
  `scripts/v126-staging-prerequisite-sync.sh`; the separate cutover command authority is
  `scripts/v126-cutover.sh`. Their shared policy/state-machine authority is
  `docs/V126_STAGING_CUTOVER_CONTRACT.md`. The ordinary deploy script must not be called by either
  path. Its rollback requires canonical status-bound failure evidence, fails closed on indeterminate
  write evidence, and uses the tracked helper's durable parent-directory-fsynced unlink for the
  create-only admission guard. Exact-looking health/version/database output never masks a nonzero
  producer status, and TLS 1.3 absence requires an explicit server protocol-version rejection.
- Docs-only commits do not require staging deploy.

## Environment / Config Inventory

Never expose or commit secrets. Use placeholder names in docs, not real values.

Environment categories:
- Telegram bot token: secret.
- Telegram webhook secret token: secret.
- Platform owner Telegram/user id: operational identifier, not a secret, but avoid broad exposure.
- Database URL/user/password: URL may expose topology; password is secret.
- JWT/session secrets: secret.
- Mini App public URL/domain: public config.
- CORS allowed origin/domain: public config.
- Staging domain: public config.
- Backend image name: public deployment metadata.
- Billing provider keys/webhook secret: secret.
- Telegram Stars/payment config: future/partial; treat provider keys as secrets.
- Staff-chat config: per-venue data in DB, not a global secret.

Rules:
- Never commit `.env` with real values.
- Commit only safe examples such as `docs/env/staging.env.example`.
- Secret presence checks may mask values; raw secret values must not be printed in logs, docs, PR comments or ChatGPT/Codex messages.

### Public-Pilot Telegram Admission Contract

Public-pilot staging runs `PRODUCT`. The historical V125 admission baseline is
`be5d62a5e9058f89cd72be6c313c71fa46ccdbf2`. The successfully deployed HT-12M V125 candidate is
`f577934691a1a7a79ba327c54e2055425142b7be`, with reviewed runtime image ID
`sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`. It runs one backend/long
poller under `TELEGRAM_TRAFFIC_POLICY=PRODUCT`, `STAGING_MAINTENANCE_MODE=OFF`, empty PRODUCT and
maintenance lists, Flyway V125 with V126 absent, healthy inactive queues and unchanged Caddy TLS 1.2
mitigation. The natural external invite acceptance gate remains recorded as
`LIVE_EXTERNAL_NEW_USER_INVITE_ACCEPTANCE = PASS` using aliases only; the HT-12M deploy repeated
read-only Guest/Venue/Platform and RBAC checks, while exact-head automated coverage remains the
evidence for mutating fresh-Guest and invite acceptance.

HT-12M is integrated on main at exact SHA
`9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1`, tree
`4071962a6850d977c4d7c319bfecc7cd4c2273d1`; exact main CI run `33514472076` passed all `11/11`
jobs. Its final verdict is `IDENTITY_GATED_MAINTENANCE_PREREQUISITE_COMPLETE`. It added or changed
no PostgreSQL/H2 migration. Future main and V126 releases must retain the same admission contract:

- `APP_ENV=staging` accepts only explicit `PRODUCT` or separately isolated `ALLOWLIST`;
  `UNRESTRICTED`, missing and unknown modes fail before database initialization.
- The normal public-pilot deploy path requires `PRODUCT`, empty/absent
  `TELEGRAM_ALLOWED_USER_IDS` and `TELEGRAM_ALLOWED_CHAT_IDS`, and exactly one explicit restricted
  `VENUE_STAFF_INVITE_SECRET_PEPPER` that is neither blank nor a normalized known placeholder.
- Runtime `ALLOWLIST` compatibility remains fail-closed and test-covered, but permanent staging
  ALLOWLIST is superseded as a public-pilot or V126 mechanism. It can never be selected by the
  ordinary public-pilot deploy profile and has no current V126 authorization.
- Deploy preflight validates the effective Compose environment, not merely one `.env` file, before
  image build/upload or service restart. Shell interpolation must not override the reviewed
  admission values.
- The normal command defaults to `STAGING_ADMISSION_PROFILE=public-pilot`. The retained
  `isolated-allowlist` script profile is compatibility tooling, not the current normal-staging,
  migration-window or rollback contract. Any separate future use requires its own explicit scope.
  CI runs separate `bash -n` checks for the validator and deploy script plus
  `bash scripts/validate-staging-admission.sh --self-test docker-compose.yml`.
- The immutable runtime policy gates long polling before Router/idempotency/domain writes, webhook
  before enqueue, signed Mini App initData before user/session creation, every protected JWT request,
  outbox claim and each direct chat-targeted Bot API operation.
- `PRODUCT` admits only supported matching positive private actor/chat shapes. A valid signed fresh
  Mini App identity receives Guest authority only; Venue/Platform APIs still require exact active
  membership and server-side RBAC.
- Valid active OWNER, MANAGER and STAFF invites can be previewed and accepted by previously unknown
  identities. The invite supplies the exact role and venue; expiry, revocation, one-time use,
  concurrency, transaction-bound audit and tenant isolation remain authoritative.
- Arbitrary groups remain untrusted. Link bootstrap, linked staff-chat operations and group outbound
  require exact server-owned actor, venue and current chat authority. Private outbound derives from
  validated workflow state; it cannot fall back to arbitrary chat IDs.
- Denial, rate-limit and privacy logging remain bounded and must not expose raw Telegram identities,
  invite material, initData, message bodies, provider payloads or secrets.

The active invite pepper is stored only in the restricted staging environment and never in Git,
build artifacts, logs or release chat. Changing it invalidates every still-pending staff/owner link;
reconcile and reissue those invitations before relying on the new value. A runtime image/config
rollback or a `V126_SMOKE` to `OFF` transition does not delete committed memberships or reverse used
invites; never repair those facts with ad-hoc SQL.

Before any future public-pilot deploy, require green Actions for the exact release SHA, an exact
reviewed image, empty webhook, one backend/poller, healthy PostgreSQL, zero active inbound/outbound
queue rows and the current Caddy configuration left untouched. PostgreSQL V126, production access
and Caddy restart/reload remain separate explicit authorizations.

### IDENTITY_GATED_MAINTENANCE_PREREQUISITE (HT-12M)

This prerequisite replaces the rejected stable-client-CIDR attribution rule for the V126 migration
window. HT-12J-R2 is closed as recovered to the Ubuntu baseline, HT-12K is closed because the current
Caddy exact-URL client identity was not provable, and HT-12L was not authorized. Source IP, forwarded
headers, TLS fingerprints and stable CIDRs are not authority in this design. Do not resume a Caddy
sidecar, patched-Caddy, packet observer or exact-path experiment. Historical HT-12D through HT-12K
evidence remains history and must not be erased.

The infrastructure remains Ubuntu Caddy 2.6.2 with TLS 1.2 and HTTP/1.1 or HTTP/2; TLS 1.3, HTTP/3
and UDP 443 remain disabled. The V125-compatible implementation started from exact source baseline
`be5d62a5e9058f89cd72be6c313c71fa46ccdbf2`, added no migration and produced final candidate
`f577934691a1a7a79ba327c54e2055425142b7be`. Its separately authorized staging deploy passed with
the overlay `OFF`, underlying `PRODUCT`, empty PRODUCT and maintenance lists, PostgreSQL/Flyway V125
and no V126. Caddy was unchanged.

The reviewed semantic port was integrated without changing PostgreSQL V126/H2 migration blobs or
main-only booking/conversation behavior. Exact source mapping remains historical evidence in
`docs/HT12M_IDENTITY_MAINTENANCE_MAIN_PORT_EVIDENCE.md`; V126 execution retains a separate
authorization gate.

Runtime contract:

```dotenv
TELEGRAM_TRAFFIC_POLICY=PRODUCT
STAGING_MAINTENANCE_MODE=OFF
STAGING_MAINTENANCE_ALLOWED_USER_IDS=
STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=
```

- `OFF` is the default. The two maintenance lists are not parsed for authorization and PRODUCT
  remains byte-for-byte/semantically the public-pilot contract: unknown valid Guests and valid
  external staff/manager/owner invitations continue to work without operator-edited IDs.
- `V126_SMOKE` is accepted only in staging (or automated tests), only over underlying `PRODUCT`, and
  only with canonical nonempty restricted sets. User IDs are positive. Positive chat IDs exactly
  equal the user set; optional staff chats are exact negative IDs. Missing, blank, malformed,
  duplicate, zero, overflowing, mismatched or noncanonical values fail before database
  initialization. Errors and startup logs expose no values.
- The values live only in the restricted mode-0600 staging environment/operator evidence. They are
  never Git data, application user management, a venue roster or a permanent admission list.
- Policy is immutable for the backend process lifetime. Activation and deactivation each require a
  reviewed environment edit and controlled backend start. There is no hot reload or fallback to
  PRODUCT after an invalid active configuration.

#### Complete V125 stateful and outbound boundary inventory

No V125 stateful application route is outside this classification:

| Boundary | `V126_SMOKE` enforcement and mutation ordering |
| --- | --- |
| Telegram long polling | The exact actor/chat decision runs before Router, idempotency, user/domain repositories and outbox. A denied update advances only the process-local Bot API offset and receives no reply. |
| Telegram webhook | Telegram update parsing and PRODUCT shape checks remain; the maintenance decision runs before `telegram_inbound_updates` persistence. Denial is acknowledged without enqueue. |
| Inbound queue worker | Defense-in-depth scans ready rows read-only, parses and applies maintenance plus PRODUCT, then claims only eligible IDs. Malformed or denied ready rows retain status, attempts, errors and timestamps unchanged and do not starve a later eligible row. The Router repeats the decision before idempotency. |
| Mini App Telegram authentication | Telegram signature/freshness validation remains first. The validated positive Telegram user ID is gated before post-validation limiting, user upsert and JWT issue. A valid excluded identity gets only generic `503` and creates no user/session/domain state. Invalid initData retains ordinary validation behavior. |
| Existing JWT and protected APIs | JWT signature/issuer/audience/expiry validation remains first; the trusted positive subject is rechecked by one route-scoped overlay on every `/api/guest`, `/api/venue` and `/api/platform` request before route/domain work. A token issued before activation cannot bypass the overlay. Missing/invalid or excluded credentials receive the same generic `503` while active. |
| Guest/Venue/Platform domains | Catalog, booking, booking/venue conversations, orders/tabs/table/session/staff calls, support, favorites/history/promotions, staff/invites/settings/billing and platform lifecycle routes are all below the common JWT gate. PRODUCT membership and RBAC execute after admission and still deny a foreign venue or Platform escalation. |
| Other public API writers | `/api/billing/webhook` is disabled with generic `503` before IP/secret/body/provider/payment handling. Public Guest Telegram-media delivery is disabled before DB lookup or Bot file download. Unknown/nonstateful API paths add no capability. |
| Outbox and staff notification persistence | All enqueuers and staff notification claims first require the maintenance recipient and then ordinary PRODUCT user/workflow or exact current venue staff-chat authority. Active claim SQL intersects PRODUCT eligibility with exact maintenance chat IDs. Denied `NEW`, retry-ready or stale `SENDING` rows are never locked/claimed: payload, status, attempts, `last_error`, `processed_at` and `next_attempt_at` remain unchanged. Query ordering still reaches later eligible rows. |
| Direct Telegram API calls | Send/edit/photo/document/delete, outbox dispatch, chat lookup/member lookup and callback-answer envelopes use the same exact chat decision in addition to PRODUCT. Staff group operations still require exact venue linkage and actor membership/RBAC. Bot-global `getUpdates`, `getWebhookInfo` and gated file fetches are separately typed; command/menu configuration is disabled while active. |
| Background/scheduled writers | Subscription billing, table-session cleanup, booking expiry and booking reminders do not start while active. Visit-feedback writing was already disabled. These autonomous jobs are neither silently treated as client traffic nor allowed to mutate during the smoke window. Inbound/outbox workers run only with the scoped behavior above. |
| Operator/static reads | `/health`, `/db/health`, `/version`, read-only queue health/metrics and Mini App static assets may remain readable. They issue no session and bypass no protected route. Operator DB/schema evidence remains SSH/loopback based. |

The read-only main-port inventory found the same single Ktor routing composition and the same
public/authenticated/Telegram/background boundary families. Its main-only booking and conversation
writes remain below the common JWT or Telegram Router gates. The integrated port keeps
`BookingMessageStaffChatNotifier` transactionally routed through the dedicated
`TelegramOutboxEnqueuer.enqueueVenueBookingSendMessageInTransaction`, with both the exact PRODUCT
venue/staff-chat check and the maintenance recipient check before the outbox insert. The generic
transactional booking enqueue continues to reject arbitrary group recipients. Main also validates
the explicit PRODUCT staff-invite secret early; maintenance parsing and the PRODUCT-only assertion
must stay before database initialization without weakening that existing guard. The PostgreSQL V126
and H2 companion migration blobs are immutable port inputs, not cherry-pick conflict resolutions.
These are the exact V125-to-main mapping points. The V125 OFF-mode deploy and main-integration gates
passed; any V126 rollout remains unauthorized until its own gates pass.

The overlay uses identity only after Telegram/Mini App/JWT validation. Private Telegram admission
requires positive actor/chat IDs, actor equal to chat and membership in both maintenance sets. Group
admission requires an allowed positive actor plus an exact allowed negative chat; PRODUCT then
performs the existing one-time link, venue/chat and role checks. Maintenance never substitutes for
PRODUCT RBAC.

#### V126 drain, activation and recovery contract

The single policy/state-machine authority is `docs/V126_STAGING_CUTOVER_CONTRACT.md`. The only
pre-Gate-A synchronization command is `scripts/v126-staging-prerequisite-sync.sh`; the only cutover
command is `scripts/v126-cutover.sh`. Their fixture authorities are the correspondingly named
`scripts/test-*.sh` files. Do not reconstruct commands from historical task evidence or older
sections below.

The sequencer initializes one immutable run manifest, writes an intent before each operation,
executes exactly one of the 20 ordered states per invocation and accepts a state only through a
hash-chained receipt bound to the run, release, script, exact predecessor and authorization. Each
state has one fixed artifact-name set plus the real mode-0400 operation log; verification hashes that
file and replays its exact `ARTIFACT` lines, so a rechecksummed receipt with a substituted, missing or
additional artifact is invalid. Remote action dispatch has no public helper command: it is reachable
only through the stdin-streamed internal envelope bound to the script hash, stage/recovery, intent,
predecessor and authorization. Gate A ends after automated V126 schema/runtime verification; Gate B
owns only the manual smoke; Gate C cannot begin before the complete manual-smoke receipt.

The baseline content-binds the restricted database/identity files, the remote Compose, maintenance
guard and admission guard, the complete staging `.env` bytes and the ordinary Caddyfile bytes. The
three executable sources must byte-match the exact `RELEASE_SHA` Git objects before state 1 can pass;
all seven authorities are sealed in the baseline receipt and reverified before dependent remote
actions. Every local release/object read clears inherited `GIT_*` state, disables replacement
objects and reads blob bytes with sanitized Git plumbing, so an alternate index, object store or
replace ref cannot redefine that authority. Later actions accept only the exact applicable baseline
or completed maintenance/Caddy
receipt state. A recovery may recognize an unreceipted partial transition only at its exact
predecessor and only when the deterministic inverse reconstructs the immutable source bytes. Only
the tracked prerequisite-sync command may make the remote release execution surface exact; it stops
before Gate A and does not authorize backup, drain, restart, image operations, `V126_SMOKE` or
Flyway/V126. The sequencer does not upload or repair the surface.

Underlying PRODUCT never changes. Caddy provides transport drain but no identity authority. The
activation order is candidate creation/validation, installation, one reload, active-config proof,
then marker creation and generic public `503`. Both verified backups precede the final preflight.
The already-built V126 image is transferred/verified separately from startup; no cutover path builds
an image or calls the ordinary deploy script. The mandatory live smoke precedes the controlled
OFF/empty-list restart, and the original Caddyfile is restored byte-for-byte.

State 10 snapshots Docker-save bytes into an unlinked mode-0400 descriptor. The exact descriptor is
structure-checked, hashed and transferred with Bash-3.2/openrsync-compatible flags. The remote side
repeats the snapshot, structure and checksum checks and passes that same descriptor—not a re-opened
pathname—to `docker load`; the retained run archive is independently hashed before and after load.
Local archive mismatch stops before the first remote action, and remote byte mismatch stops before
Docker mutation.

Both V126 starts resolve the Compose `backend` service specifically, use create-only `--no-build`,
set restart policy `no`, require `RestartCount=0` and issue exactly one explicit start. Runtime proof
requires one total/running Compose backend with long-polling enabled, the exact V126 image, no live
V125 container globally and no live old-image backend in the staging Compose project. Gate B accepts
only the canonical 17-assertion smoke evidence and re-proves an unauthenticated protected request as
the generic application `503` both when opening and when sealing the window.

Recovery also uses only bounded sequencer commands. It classifies Flyway before any Caddy/backend
mutation. Pre-V126 rollback requires exact V125/V126-absent proof, can finish and drain a validated
partial candidate activation, and may start only the exact reviewed V125 image. Once V126 is present,
the forward-fix stop drains and stops only the scoped Compose backend; it then requires global V125
and run-bound V126 counts to be zero and refuses if an outside-project matching container remains.
Exact V126, already-stopped, unexpected V125 and unknown/multiple scoped states are terminally
classified; the latter two never authorize a V125 or forward-fix start. The full-DR command verifies
the exact post-stop receipt when escalating from that terminal state, then verifies backup/session
prerequisites and records the accepted data-loss boundary before restore. Any recorded recovery
intent terminalizes the run even if recovery fails ambiguously. The lock tracks owner, stage worker, SSH and rsync
children; signal handling terminates them and exits. Only recovery may atomically take over a
validated dead lock after every owner/child liveness check. Partial restore, Flyway repair and ad-hoc
data/schema repair remain prohibited. A completed post-V126 stop may anchor only the separately
authorized full-DR prerequisite verifier; that one-way escalation does not resume a stage or perform
a restore.

## Migrations Runbook

Current practice:
- PostgreSQL and H2 migrations both exist.
- Flyway migration policy is documented in `docs/MIGRATION_POLICY.md`.
- Staging applies Flyway migrations during normal backend startup through `DatabaseFactory`. The
  exact V126 policy/order is fixed in `docs/V126_STAGING_CUTOVER_CONTRACT.md`; only
  `scripts/v126-cutover.sh` may execute that single-backend sequence.

Target process for runtime migrations:
1. Inspect PostgreSQL and H2 migration directories before choosing migration versions.
2. Add forward migrations for both trees where applicable.
3. Run local targeted backend tests and migration sanity checks.
4. Deploy staging after green Actions.
5. Verify backend startup, `/health`, `/db/health` and affected product flows.
6. Record whether rollback is forward-fix only or has an explicit down/restore path.

Dangerous migrations:
- dropping columns/tables;
- changing non-null constraints on live data;
- rewriting status enums;
- changing financial/billing history;
- changing user/role/ticket/order ownership boundaries.

Rules:
- Prefer additive migrations and forward fixes.
- Avoid destructive migrations without retention/archive decision.
- Billing/subscription state changes require audit and cannot be silently reversed.
- The exact two-backup/isolated-rehearsal commands are staging-only and V126-specific. If
  backup/restore commands are unknown for another environment, mark rollback as **RUNBOOK GAP**
  before release.

### Booking Thread Integrity Preflight (PostgreSQL V124)

Run this exact command against staging only after green Actions and before a release that can apply
PostgreSQL V124. It is repeatable, read-only and does not apply the migration. Every query prefixed
`UNSAFE` must return zero rows. The final guard exits `psql` with status `3` if any unsafe predicate
is present; that is `STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION` and blocks deploy. The
informational rows must be attached to the release evidence and reconciled with post-migration
counts, but duplicate groups or a survivor that is not the earliest-created row are not unsafe by
themselves. The shell wrapper requires `DATABASE_URL` before `psql` starts; absent or empty binding
must fail before any database client process is launched. During V126, only the sequencer extracts
and executes this marker-bounded source from the immutable `RELEASE_SHA` Git blob through its
sanitized Git boundary.

<!-- BOOKING_UNREAD_PREFLIGHT_BEGIN -->
```bash
set -euo pipefail
: "${DATABASE_URL:?DATABASE_URL must bind the exact target}"
psql "${DATABASE_URL}" -X --set=ON_ERROR_STOP=1 <<'SQL'
\pset pager off
\set VERBOSITY terse
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL lock_timeout = '30s';
SET LOCAL statement_timeout = '60s';

\echo 'UNSAFE null booking references (expected 0 rows)'
SELECT id AS thread_id
FROM support_threads
WHERE thread_type = 'BOOKING_THREAD'
  AND booking_id IS NULL
ORDER BY id;

\echo 'UNSAFE missing canonical bookings (expected 0 rows)'
SELECT st.id AS thread_id, st.booking_id
FROM support_threads st
LEFT JOIN bookings b ON b.id = st.booking_id
WHERE st.thread_type = 'BOOKING_THREAD'
  AND st.booking_id IS NOT NULL
  AND b.id IS NULL
ORDER BY st.id;

\echo 'UNSAFE venue/guest ownership mismatches (expected 0 rows)'
SELECT
    st.id AS thread_id,
    st.booking_id,
    st.venue_id AS stored_venue_id,
    b.venue_id AS canonical_venue_id,
    st.guest_user_id AS stored_guest_user_id,
    b.user_id AS canonical_guest_user_id
FROM support_threads st
JOIN bookings b ON b.id = st.booking_id
WHERE st.thread_type = 'BOOKING_THREAD'
  AND (
      st.venue_id IS DISTINCT FROM b.venue_id
      OR st.guest_user_id IS DISTINCT FROM b.user_id
  )
ORDER BY st.id;

\echo 'INFORMATIONAL duplicate booking groups'
SELECT
    booking_id,
    COUNT(*) AS thread_count,
    MIN(id) AS survivor_id,
    ARRAY_AGG(id ORDER BY id) AS thread_ids
FROM support_threads
WHERE thread_type = 'BOOKING_THREAD'
  AND booking_id IS NOT NULL
GROUP BY booking_id
HAVING COUNT(*) > 1
ORDER BY booking_id;

\echo 'UNSAFE conflicting duplicate statuses (expected 0 rows)'
SELECT
    booking_id,
    ARRAY_AGG(DISTINCT status ORDER BY status) AS statuses
FROM support_threads
WHERE thread_type = 'BOOKING_THREAD'
  AND booking_id IS NOT NULL
GROUP BY booking_id
HAVING COUNT(*) > 1
   AND COUNT(DISTINCT status) > 1
ORDER BY booking_id;

\echo 'UNSAFE partial per-user read coverage (expected 0 rows)'
WITH duplicate_groups AS (
    SELECT booking_id, COUNT(*) AS thread_count
    FROM support_threads
    WHERE thread_type = 'BOOKING_THREAD'
      AND booking_id IS NOT NULL
    GROUP BY booking_id
    HAVING COUNT(*) > 1
)
SELECT
    groups.booking_id,
    reads.user_id,
    groups.thread_count,
    COUNT(*) AS marker_count,
    ARRAY_AGG(threads.id ORDER BY threads.id) AS represented_thread_ids
FROM duplicate_groups groups
JOIN support_threads threads
  ON threads.thread_type = 'BOOKING_THREAD'
 AND threads.booking_id = groups.booking_id
JOIN support_thread_reads reads ON reads.thread_id = threads.id
GROUP BY groups.booking_id, groups.thread_count, reads.user_id
HAVING COUNT(*) <> groups.thread_count
ORDER BY groups.booking_id, reads.user_id;

\echo 'UNSAFE conflicting read timestamps (expected 0 rows)'
WITH duplicate_groups AS (
    SELECT booking_id
    FROM support_threads
    WHERE thread_type = 'BOOKING_THREAD'
      AND booking_id IS NOT NULL
    GROUP BY booking_id
    HAVING COUNT(*) > 1
)
SELECT
    groups.booking_id,
    reads.user_id,
    ARRAY_AGG(reads.last_read_at ORDER BY threads.id) AS read_timestamps
FROM duplicate_groups groups
JOIN support_threads threads
  ON threads.thread_type = 'BOOKING_THREAD'
 AND threads.booking_id = groups.booking_id
JOIN support_thread_reads reads ON reads.thread_id = threads.id
GROUP BY groups.booking_id, reads.user_id
HAVING COUNT(DISTINCT reads.last_read_at) <> 1
ORDER BY groups.booking_id, reads.user_id;

\echo 'UNSAFE unknown booking-thread audit entity/action shapes (expected 0 rows)'
WITH audit_payloads AS (
    SELECT
        audit.*,
        audit.payload_json IS JSON OBJECT AS payload_is_object,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload,
        ticket.ticket_id_count,
        ticket.ticket_id_value
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT
            COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count,
            MIN(entry.value::TEXT) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_value
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
)
SELECT audit.id AS audit_id, audit.entity_type, audit.action, audit.entity_id
FROM audit_payloads audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE (
    audit.entity_type = 'support_ticket'
    OR audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
    OR audit.normalized_payload ~
        '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
)
  AND (
      audit.entity_type <> 'support_ticket'
      OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
  )
ORDER BY audit.id;

\echo 'UNSAFE malformed/non-object ticketId-bearing audit payloads (expected 0 rows)'
WITH audit_payloads AS (
    SELECT
        audit.*,
        audit.payload_json IS JSON OBJECT AS payload_is_object,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload
    FROM audit_log audit
)
SELECT audit.id AS audit_id, audit.entity_type, audit.action, audit.entity_id
FROM audit_payloads audit
WHERE audit.normalized_payload ~ '"ticketId"[[:space:]]*:'
  AND NOT audit.payload_is_object
ORDER BY audit.id;

\echo 'UNSAFE repeated or non-top-level audit ticketId (expected 0 rows)'
WITH audit_payloads AS (
    SELECT
        audit.*,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload,
        ticket.ticket_id_count
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
)
SELECT audit.id AS audit_id, audit.entity_type, audit.action, audit.entity_id
FROM audit_payloads audit
WHERE audit.normalized_payload ~ '"ticketId"[[:space:]]*:'
  AND (
      audit.ticket_id_count <> 1
      OR REGEXP_COUNT(audit.normalized_payload, '"ticketId"[[:space:]]*:') <> 1
  )
ORDER BY audit.id;

\echo 'UNSAFE non-integer ticketId-bearing audit payloads (expected 0 rows)'
WITH audit_payloads AS (
    SELECT
        audit.*,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload,
        ticket.ticket_id_count,
        ticket.ticket_id_value
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT
            COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count,
            MIN(entry.value::TEXT) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_value
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
)
SELECT audit.id AS audit_id, audit.entity_type, audit.action, audit.entity_id
FROM audit_payloads audit
WHERE audit.normalized_payload ~ '"ticketId"[[:space:]]*:'
  AND audit.ticket_id_count = 1
  AND audit.ticket_id_value !~ '^-?(0|[1-9][0-9]*)$'
ORDER BY audit.id;

\echo 'UNSAFE unknown recursive audit thread-reference keys (expected 0 rows)'
WITH RECURSIVE audit_nodes(audit_id, node, depth) AS (
    SELECT audit.id, audit.payload_json::JSONB, 0
    FROM audit_log audit
    WHERE audit.payload_json IS JSON
    UNION ALL
    SELECT parent.audit_id, child.value, parent.depth + 1
    FROM audit_nodes parent
    CROSS JOIN LATERAL (
        SELECT object_child.value
        FROM JSONB_EACH(
            CASE WHEN JSONB_TYPEOF(parent.node) = 'object' THEN parent.node ELSE '{}'::JSONB END
        ) object_child
        UNION ALL
        SELECT array_child.value
        FROM JSONB_ARRAY_ELEMENTS(
            CASE WHEN JSONB_TYPEOF(parent.node) = 'array' THEN parent.node ELSE '[]'::JSONB END
        ) array_child
    ) child
), audit_keys AS (
    SELECT parent.audit_id, parent.depth, entry.key
    FROM audit_nodes parent
    CROSS JOIN LATERAL JSONB_EACH(
        CASE WHEN JSONB_TYPEOF(parent.node) = 'object' THEN parent.node ELSE '{}'::JSONB END
    ) entry
), normalized_keys AS (
    SELECT
        audit_id,
        depth,
        key,
        REGEXP_REPLACE(LOWER(NORMALIZE(key, NFKC)), '[_.[:space:]-]', '', 'g') AS compact_key
    FROM audit_keys
)
SELECT audit_id, depth, key
FROM normalized_keys
WHERE NOT (depth = 0 AND key = 'ticketId')
  AND compact_key ~ '(thread|ticket|conversation).*(ids|refs|id|ref)'
ORDER BY audit_id, depth, key;

\echo 'UNSAFE aliased audit thread references (expected 0 rows)'
WITH audit_payloads AS (
    SELECT
        audit.*,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload
    FROM audit_log audit
)
SELECT audit.id AS audit_id, audit.entity_type, audit.action, audit.entity_id
FROM audit_payloads audit
WHERE audit.normalized_payload ~
    '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
ORDER BY audit.id;

\echo 'UNSAFE payload-only BOOKING_THREAD audit references (expected 0 rows)'
WITH audit_payloads AS (
    SELECT audit.*, ticket.ticket_id_count, ticket.ticket_id_value
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT
            COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count,
            MIN(entry.value::TEXT) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_value
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
)
SELECT audit.id AS audit_id, audit.entity_type, audit.action, audit.entity_id, thread.id AS payload_ticket_id
FROM audit_payloads audit
JOIN support_threads thread
  ON thread.thread_type = 'BOOKING_THREAD'
 AND audit.ticket_id_count = 1
 AND thread.id::TEXT = audit.ticket_id_value
WHERE audit.entity_type <> 'support_ticket'
   OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
   OR audit.entity_id IS DISTINCT FROM thread.id
ORDER BY audit.id;

\echo 'UNSAFE malformed/non-object booking-thread audit payloads (expected 0 rows)'
WITH audit_payloads AS (
    SELECT audit.*, audit.payload_json IS JSON OBJECT AS payload_is_object
    FROM audit_log audit
)
SELECT audit.id AS audit_id, audit.entity_id
FROM audit_payloads audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND NOT audit.payload_is_object
ORDER BY audit.id;

\echo 'UNSAFE missing/repeated booking-thread audit ticketId (expected 0 rows)'
WITH audit_payloads AS (
    SELECT
        audit.*,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload,
        ticket.ticket_id_count
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
)
SELECT audit.id AS audit_id, audit.entity_id
FROM audit_payloads audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND (
      audit.ticket_id_count <> 1
      OR REGEXP_COUNT(audit.normalized_payload, '"ticketId"[[:space:]]*:') <> 1
  )
ORDER BY audit.id;

\echo 'UNSAFE non-integer booking-thread audit ticketId (expected 0 rows)'
WITH audit_payloads AS (
    SELECT audit.*, ticket.ticket_id_count, ticket.ticket_id_value
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT
            COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count,
            MIN(entry.value::TEXT) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_value
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
)
SELECT audit.id AS audit_id, audit.entity_id
FROM audit_payloads audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND audit.ticket_id_count = 1
  AND audit.ticket_id_value !~ '^-?(0|[1-9][0-9]*)$'
ORDER BY audit.id;

\echo 'UNSAFE aliased booking-thread audit references (expected 0 rows)'
WITH audit_payloads AS (
    SELECT
        audit.*,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload
    FROM audit_log audit
)
SELECT audit.id AS audit_id, audit.entity_id
FROM audit_payloads audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND audit.normalized_payload ~
      '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
ORDER BY audit.id;

\echo 'UNSAFE booking-thread audit ticketId/entity_id mismatches (expected 0 rows)'
WITH audit_payloads AS (
    SELECT audit.*, ticket.ticket_id_count, ticket.ticket_id_value
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT
            COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count,
            MIN(entry.value::TEXT) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_value
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
)
SELECT audit.id AS audit_id, audit.entity_id
FROM audit_payloads audit
JOIN support_threads thread
  ON thread.id = audit.entity_id
 AND thread.thread_type = 'BOOKING_THREAD'
WHERE audit.entity_type = 'support_ticket'
  AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
  AND audit.ticket_id_count = 1
  AND audit.ticket_id_value ~ '^-?(0|[1-9][0-9]*)$'
  AND audit.ticket_id_value IS DISTINCT FROM audit.entity_id::TEXT
ORDER BY audit.id;

\echo 'INFORMATIONAL survivor MIN(id) is not earliest-created row'
WITH duplicate_groups AS (
    SELECT
        booking_id,
        MIN(id) AS survivor_id,
        MIN(created_at) AS merged_created_at
    FROM support_threads
    WHERE thread_type = 'BOOKING_THREAD'
      AND booking_id IS NOT NULL
    GROUP BY booking_id
    HAVING COUNT(*) > 1
)
SELECT
    groups.booking_id,
    groups.survivor_id,
    survivor.created_at AS survivor_created_at,
    groups.merged_created_at
FROM duplicate_groups groups
JOIN support_threads survivor ON survivor.id = groups.survivor_id
WHERE survivor.created_at IS DISTINCT FROM groups.merged_created_at
ORDER BY groups.booking_id;

\echo 'INFORMATIONAL exact physical FK inventory (expected only two known rows)'
WITH expected_relation_identity(relation_role, relation_name, column_name) AS (
    VALUES
        ('TARGET'::TEXT, 'support_threads'::NAME, 'id'::NAME),
        ('SUPPORT_MESSAGES'::TEXT, 'support_messages'::NAME, 'thread_id'::NAME),
        ('SUPPORT_THREAD_READS'::TEXT, 'support_thread_reads'::NAME, 'thread_id'::NAME)
), relation_identity AS (
    SELECT
        expected.relation_role,
        relation_schema.oid AS namespace_oid,
        relation_schema.nspname::TEXT AS schema_name,
        relation_table.oid AS relation_oid,
        relation_table.relname::TEXT AS relation_name,
        relation_column.attnum AS column_attnum,
        relation_column.attname::TEXT AS column_name
    FROM expected_relation_identity expected
    JOIN pg_catalog.pg_namespace relation_schema
      ON relation_schema.nspname = CURRENT_SCHEMA()
    JOIN pg_catalog.pg_class relation_table
      ON relation_table.relnamespace = relation_schema.oid
     AND relation_table.relname = expected.relation_name
     AND relation_table.relkind = 'r'
    JOIN pg_catalog.pg_attribute relation_column
      ON relation_column.attrelid = relation_table.oid
     AND relation_column.attname = expected.column_name
     AND relation_column.attnum > 0
     AND NOT relation_column.attisdropped
), inbound_references AS (
    SELECT
        fk_constraint.oid AS constraint_oid,
        fk_constraint.conname::TEXT AS constraint_name,
        fk_constraint.conrelid AS source_relation_oid,
        source_table.relnamespace AS source_namespace_oid,
        source_schema.nspname::TEXT AS source_schema_name,
        source_table.relname::TEXT AS source_relation_name,
        fk_constraint.confrelid AS target_relation_oid,
        fk_constraint.conkey::SMALLINT[] AS source_attnums,
        fk_constraint.confkey::SMALLINT[] AS target_attnums,
        ARRAY(
            SELECT source_attribute.attname::TEXT
            FROM pg_catalog.generate_subscripts(fk_constraint.conkey, 1)
                AS source_key(ordinality)
            LEFT JOIN pg_catalog.pg_attribute source_attribute
              ON source_attribute.attrelid = fk_constraint.conrelid
             AND source_attribute.attnum = fk_constraint.conkey[source_key.ordinality]
             AND source_attribute.attnum > 0
             AND NOT source_attribute.attisdropped
            ORDER BY source_key.ordinality
        )::TEXT[] AS source_column_names,
        ARRAY(
            SELECT target_attribute.attname::TEXT
            FROM pg_catalog.generate_subscripts(fk_constraint.confkey, 1)
                AS target_key(ordinality)
            LEFT JOIN pg_catalog.pg_attribute target_attribute
              ON target_attribute.attrelid = fk_constraint.confrelid
             AND target_attribute.attnum = fk_constraint.confkey[target_key.ordinality]
             AND target_attribute.attnum > 0
             AND NOT target_attribute.attisdropped
            ORDER BY target_key.ordinality
        )::TEXT[] AS target_column_names,
        pg_catalog.CARDINALITY(fk_constraint.conkey) AS source_column_count,
        pg_catalog.CARDINALITY(fk_constraint.confkey) AS target_column_count,
        fk_constraint.confupdtype::TEXT AS confupdtype,
        fk_constraint.confdeltype::TEXT AS confdeltype,
        fk_constraint.confmatchtype::TEXT AS confmatchtype
    FROM relation_identity target
    JOIN pg_catalog.pg_constraint fk_constraint
      ON fk_constraint.contype = 'f'
     AND fk_constraint.confrelid = target.relation_oid
    LEFT JOIN pg_catalog.pg_class source_table
      ON source_table.oid = fk_constraint.conrelid
    LEFT JOIN pg_catalog.pg_namespace source_schema
      ON source_schema.oid = source_table.relnamespace
    WHERE target.relation_role = 'TARGET'
), expected_fk_contracts AS (
    SELECT
        expected.source_role,
        source.namespace_oid AS source_namespace_oid,
        source.schema_name AS source_schema_name,
        source.relation_oid AS source_relation_oid,
        source.relation_name AS source_relation_name,
        source.column_attnum AS source_column_attnum,
        source.column_name AS source_column_name,
        target.relation_oid AS target_relation_oid,
        target.column_attnum AS target_column_attnum,
        target.column_name AS target_column_name
    FROM (
        VALUES ('SUPPORT_MESSAGES'::TEXT), ('SUPPORT_THREAD_READS'::TEXT)
    ) expected(source_role)
    LEFT JOIN relation_identity source
      ON source.relation_role = expected.source_role
    LEFT JOIN relation_identity target
      ON target.relation_role = 'TARGET'
), reference_matches AS (
    SELECT expected.source_role, actual.constraint_oid
    FROM expected_fk_contracts expected
    JOIN inbound_references actual
      ON actual.source_relation_oid = expected.source_relation_oid
     AND actual.source_namespace_oid = expected.source_namespace_oid
     AND actual.source_schema_name = expected.source_schema_name
     AND actual.source_relation_name = expected.source_relation_name
     AND actual.target_relation_oid = expected.target_relation_oid
     AND actual.source_column_count = 1
     AND actual.target_column_count = 1
     AND actual.source_attnums = ARRAY[expected.source_column_attnum]::SMALLINT[]
     AND actual.target_attnums = ARRAY[expected.target_column_attnum]::SMALLINT[]
     AND actual.source_column_names = ARRAY[expected.source_column_name]::TEXT[]
     AND actual.target_column_names = ARRAY[expected.target_column_name]::TEXT[]
     AND actual.confupdtype = 'a'
     AND actual.confdeltype = 'c'
     AND actual.confmatchtype = 's'
)
SELECT
    actual.constraint_oid,
    actual.constraint_name,
    actual.source_relation_oid,
    actual.source_namespace_oid,
    actual.source_schema_name,
    actual.source_relation_name,
    actual.target_relation_oid,
    actual.source_attnums,
    actual.target_attnums,
    actual.source_column_names,
    actual.target_column_names,
    actual.source_column_count,
    actual.target_column_count,
    actual.confupdtype,
    actual.confdeltype,
    actual.confmatchtype,
    matched.source_role AS expected_role
FROM inbound_references actual
LEFT JOIN reference_matches matched USING (constraint_oid)
ORDER BY actual.constraint_oid;

\echo 'UNSAFE unknown/missing FK or explicit thread_id reference families (expected 0 rows)'
WITH expected_relation_identity(relation_role, relation_name, column_name) AS (
    VALUES
        ('TARGET'::TEXT, 'support_threads'::NAME, 'id'::NAME),
        ('SUPPORT_MESSAGES'::TEXT, 'support_messages'::NAME, 'thread_id'::NAME),
        ('SUPPORT_THREAD_READS'::TEXT, 'support_thread_reads'::NAME, 'thread_id'::NAME)
), relation_identity AS (
    SELECT
        expected.relation_role,
        relation_schema.oid AS namespace_oid,
        relation_schema.nspname::TEXT AS schema_name,
        relation_table.oid AS relation_oid,
        relation_table.relname::TEXT AS relation_name,
        relation_column.attnum AS column_attnum,
        relation_column.attname::TEXT AS column_name
    FROM expected_relation_identity expected
    JOIN pg_catalog.pg_namespace relation_schema
      ON relation_schema.nspname = CURRENT_SCHEMA()
    JOIN pg_catalog.pg_class relation_table
      ON relation_table.relnamespace = relation_schema.oid
     AND relation_table.relname = expected.relation_name
     AND relation_table.relkind = 'r'
    JOIN pg_catalog.pg_attribute relation_column
      ON relation_column.attrelid = relation_table.oid
     AND relation_column.attname = expected.column_name
     AND relation_column.attnum > 0
     AND NOT relation_column.attisdropped
), identity_issues AS (
    SELECT
        'expected relation identity is not exactly one'::TEXT AS issue,
        CURRENT_SCHEMA()::TEXT AS table_name,
        expected.relation_name::TEXT AS column_name
    FROM expected_relation_identity expected
    LEFT JOIN relation_identity actual
      ON actual.relation_role = expected.relation_role
    GROUP BY expected.relation_role, expected.relation_name
    HAVING COUNT(actual.relation_oid) <> 1
), inbound_references AS (
    SELECT
        fk_constraint.oid AS constraint_oid,
        fk_constraint.conname::TEXT AS constraint_name,
        fk_constraint.conrelid AS source_relation_oid,
        source_table.relnamespace AS source_namespace_oid,
        source_schema.nspname::TEXT AS source_schema_name,
        source_table.relname::TEXT AS source_relation_name,
        fk_constraint.confrelid AS target_relation_oid,
        fk_constraint.conkey::SMALLINT[] AS source_attnums,
        fk_constraint.confkey::SMALLINT[] AS target_attnums,
        ARRAY(
            SELECT source_attribute.attname::TEXT
            FROM pg_catalog.generate_subscripts(fk_constraint.conkey, 1)
                AS source_key(ordinality)
            LEFT JOIN pg_catalog.pg_attribute source_attribute
              ON source_attribute.attrelid = fk_constraint.conrelid
             AND source_attribute.attnum = fk_constraint.conkey[source_key.ordinality]
             AND source_attribute.attnum > 0
             AND NOT source_attribute.attisdropped
            ORDER BY source_key.ordinality
        )::TEXT[] AS source_column_names,
        ARRAY(
            SELECT target_attribute.attname::TEXT
            FROM pg_catalog.generate_subscripts(fk_constraint.confkey, 1)
                AS target_key(ordinality)
            LEFT JOIN pg_catalog.pg_attribute target_attribute
              ON target_attribute.attrelid = fk_constraint.confrelid
             AND target_attribute.attnum = fk_constraint.confkey[target_key.ordinality]
             AND target_attribute.attnum > 0
             AND NOT target_attribute.attisdropped
            ORDER BY target_key.ordinality
        )::TEXT[] AS target_column_names,
        pg_catalog.CARDINALITY(fk_constraint.conkey) AS source_column_count,
        pg_catalog.CARDINALITY(fk_constraint.confkey) AS target_column_count,
        fk_constraint.confupdtype::TEXT AS confupdtype,
        fk_constraint.confdeltype::TEXT AS confdeltype,
        fk_constraint.confmatchtype::TEXT AS confmatchtype
    FROM relation_identity target
    JOIN pg_catalog.pg_constraint fk_constraint
      ON fk_constraint.contype = 'f'
     AND fk_constraint.confrelid = target.relation_oid
    LEFT JOIN pg_catalog.pg_class source_table
      ON source_table.oid = fk_constraint.conrelid
    LEFT JOIN pg_catalog.pg_namespace source_schema
      ON source_schema.oid = source_table.relnamespace
    WHERE target.relation_role = 'TARGET'
), expected_fk_contracts AS (
    SELECT
        expected.source_role,
        source.namespace_oid AS source_namespace_oid,
        source.schema_name AS source_schema_name,
        source.relation_oid AS source_relation_oid,
        source.relation_name AS source_relation_name,
        source.column_attnum AS source_column_attnum,
        source.column_name AS source_column_name,
        target.relation_oid AS target_relation_oid,
        target.column_attnum AS target_column_attnum,
        target.column_name AS target_column_name
    FROM (
        VALUES ('SUPPORT_MESSAGES'::TEXT), ('SUPPORT_THREAD_READS'::TEXT)
    ) expected(source_role)
    LEFT JOIN relation_identity source
      ON source.relation_role = expected.source_role
    LEFT JOIN relation_identity target
      ON target.relation_role = 'TARGET'
), reference_matches AS (
    SELECT expected.source_role, actual.constraint_oid
    FROM expected_fk_contracts expected
    JOIN inbound_references actual
      ON actual.source_relation_oid = expected.source_relation_oid
     AND actual.source_namespace_oid = expected.source_namespace_oid
     AND actual.source_schema_name = expected.source_schema_name
     AND actual.source_relation_name = expected.source_relation_name
     AND actual.target_relation_oid = expected.target_relation_oid
     AND actual.source_column_count = 1
     AND actual.target_column_count = 1
     AND actual.source_attnums = ARRAY[expected.source_column_attnum]::SMALLINT[]
     AND actual.target_attnums = ARRAY[expected.target_column_attnum]::SMALLINT[]
     AND actual.source_column_names = ARRAY[expected.source_column_name]::TEXT[]
     AND actual.target_column_names = ARRAY[expected.target_column_name]::TEXT[]
     AND actual.confupdtype = 'a'
     AND actual.confdeltype = 'c'
     AND actual.confmatchtype = 's'
), reference_issues AS (
    SELECT issue, table_name, column_name
    FROM identity_issues
    UNION ALL
    SELECT
        'unknown inbound FK constraint',
        COALESCE(
            actual.source_schema_name || '.' || actual.source_relation_name,
            '<unresolved source relation>'
        ),
        COALESCE(actual.constraint_name, 'oid=' || actual.constraint_oid::TEXT)
    FROM inbound_references actual
    LEFT JOIN reference_matches matched USING (constraint_oid)
    WHERE matched.constraint_oid IS NULL
    UNION ALL
    SELECT
        'expected inbound FK multiplicity is not exactly one',
        COALESCE(expected.source_schema_name, CURRENT_SCHEMA())::TEXT,
        COALESCE(expected.source_relation_name, expected.source_role)
    FROM expected_fk_contracts expected
    LEFT JOIN reference_matches matched USING (source_role)
    GROUP BY
        expected.source_role,
        expected.source_schema_name,
        expected.source_relation_name
    HAVING COUNT(matched.constraint_oid) <> 1
    UNION ALL
    SELECT
        'physical inbound FK count is not exactly two',
        CURRENT_SCHEMA()::TEXT,
        'support_threads'::TEXT
    WHERE (SELECT COUNT(*) FROM inbound_references) <> 2
), expected_reference_columns(table_name, column_name) AS (
    VALUES ('support_messages', 'thread_id'), ('support_thread_reads', 'thread_id')
)
SELECT issue, table_name, column_name
FROM reference_issues
UNION ALL
SELECT
    'unknown normalized thread-reference column',
    LOWER(columns.table_name),
    LOWER(columns.column_name)
FROM information_schema.columns columns
LEFT JOIN expected_reference_columns expected
  ON expected.table_name = LOWER(columns.table_name)
 AND expected.column_name = LOWER(columns.column_name)
WHERE columns.table_schema = CURRENT_SCHEMA()
  AND REGEXP_REPLACE(LOWER(columns.column_name), '[^a-z0-9]', '', 'g') IN (
      'threadid',
      'supportthreadid',
      'bookingthreadid',
      'ticketid'
  )
  AND expected.table_name IS NULL
ORDER BY 1, 2, 3;

\echo 'UNSAFE missing/unknown JSON and durable payload families (expected 0 rows)'
WITH expected_json(table_name, column_name) AS (
    VALUES
        ('analytics_events', 'payload_json'),
        ('audit_log', 'payload_json'),
        ('billing_invoices', 'provider_raw_payload'),
        ('billing_notifications', 'payload_json'),
        ('billing_payments', 'raw_payload'),
        ('guest_batch_idempotency', 'response_snapshot'),
        ('menu_items', 'options'),
        ('order_batches', 'items_snapshot'),
        ('order_promotion_applications', 'schedule_snapshot_json'),
        ('order_promotion_applications', 'target_snapshot_json'),
        ('telegram_dialog_state', 'payload'),
        ('telegram_inbound_updates', 'payload_json'),
        ('telegram_outbox', 'payload_json'),
        ('venues', 'features'),
        ('venues', 'ui_layout'),
        ('visit_feedback', 'tags_json')
), actual_json AS (
    SELECT LOWER(columns.table_name) AS table_name, LOWER(columns.column_name) AS column_name
    FROM information_schema.columns columns
    WHERE columns.table_schema = CURRENT_SCHEMA()
      AND (
          LOWER(columns.data_type) IN ('json', 'jsonb')
          OR LOWER(columns.column_name) LIKE '%json%'
          OR LOWER(columns.column_name) IN (
              'payload',
              'raw_payload',
              'provider_raw_payload',
              'features',
              'ui_layout',
              'options',
              'items_snapshot',
              'response_snapshot'
          )
      )
)
SELECT 'unknown durable payload family' AS issue, actual.table_name, actual.column_name
FROM actual_json actual
LEFT JOIN expected_json expected USING (table_name, column_name)
WHERE expected.table_name IS NULL
UNION ALL
SELECT 'missing expected durable payload family', expected.table_name, expected.column_name
FROM expected_json expected
LEFT JOIN actual_json actual USING (table_name, column_name)
WHERE actual.table_name IS NULL
ORDER BY 1, 2, 3;

\echo 'UNSAFE thread-reference keys in non-audit durable payload families (expected 0 rows)'
SELECT durable_json.family
FROM (
    SELECT 'venues.features' AS family, features::TEXT AS payload FROM venues
    UNION ALL SELECT 'venues.ui_layout', ui_layout::TEXT FROM venues
    UNION ALL SELECT 'menu_items.options', options::TEXT FROM menu_items
    UNION ALL SELECT 'order_batches.items_snapshot', items_snapshot::TEXT FROM order_batches
    UNION ALL SELECT 'telegram_dialog_state.payload', payload::TEXT FROM telegram_dialog_state
    UNION ALL SELECT 'billing_payments.raw_payload', raw_payload FROM billing_payments
    UNION ALL SELECT 'billing_invoices.provider_raw_payload', provider_raw_payload FROM billing_invoices
    UNION ALL SELECT 'billing_notifications.payload_json', payload_json FROM billing_notifications
    UNION ALL SELECT 'telegram_inbound_updates.payload_json', payload_json FROM telegram_inbound_updates
    UNION ALL SELECT 'telegram_outbox.payload_json', payload_json FROM telegram_outbox
    UNION ALL SELECT 'guest_batch_idempotency.response_snapshot', response_snapshot::TEXT FROM guest_batch_idempotency
    UNION ALL SELECT 'analytics_events.payload_json', payload_json FROM analytics_events
    UNION ALL SELECT 'visit_feedback.tags_json', tags_json FROM visit_feedback
    UNION ALL SELECT 'order_promotion_applications.schedule_snapshot_json', schedule_snapshot_json FROM order_promotion_applications
    UNION ALL SELECT 'order_promotion_applications.target_snapshot_json', target_snapshot_json FROM order_promotion_applications
) durable_json
WHERE (
    CASE
        WHEN durable_json.payload IS JSON THEN (durable_json.payload::JSONB)::TEXT
        ELSE durable_json.payload
    END
) ~
    '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
GROUP BY durable_json.family
ORDER BY durable_json.family;

\echo 'UNSAFE missing migration lock targets or EXCLUSIVE-lock privilege (expected 0 rows)'
WITH required_lock_targets(table_name, lock_order) AS (
    VALUES
        ('bookings', 1),
        ('support_threads', 2),
        ('support_messages', 3),
        ('support_thread_reads', 4),
        ('audit_log', 5)
), lock_targets AS (
    SELECT
        required.table_name,
        required.lock_order,
        TO_REGCLASS(FORMAT('%I.%I', CURRENT_SCHEMA(), required.table_name)) AS relation_oid
    FROM required_lock_targets required
)
SELECT target.table_name, target.lock_order
FROM lock_targets target
WHERE target.relation_oid IS NULL
   OR NOT (
       HAS_TABLE_PRIVILEGE(CURRENT_USER, target.relation_oid, 'UPDATE')
       OR HAS_TABLE_PRIVILEGE(CURRENT_USER, target.relation_oid, 'DELETE')
       OR HAS_TABLE_PRIVILEGE(CURRENT_USER, target.relation_oid, 'TRUNCATE')
       OR PG_HAS_ROLE(
           CURRENT_USER,
           (SELECT relation.relowner FROM pg_catalog.pg_class relation WHERE relation.oid = target.relation_oid),
           'USAGE'
       )
       OR EXISTS (
           SELECT 1
           FROM pg_catalog.pg_roles role
           WHERE role.rolname = CURRENT_USER
             AND role.rolsuper
       )
   )
ORDER BY target.lock_order;

\echo 'INFORMATIONAL known logical reference inventory'
SELECT
    'audit_log' AS table_name,
    'entity_type=support_ticket, entity_id=thread.id, payload_json.ticketId=thread.id' AS reference_contract,
    COUNT(*) FILTER (WHERE st.thread_type = 'BOOKING_THREAD') AS booking_thread_reference_count
FROM audit_log audit
LEFT JOIN support_threads st ON st.id = audit.entity_id
WHERE audit.entity_type = 'support_ticket';

\echo 'INFORMATIONAL expected duplicate-group reparent/collapse counts'
WITH duplicate_groups AS (
    SELECT booking_id, MIN(id) AS survivor_id, COUNT(*) AS thread_count
    FROM support_threads
    WHERE thread_type = 'BOOKING_THREAD'
      AND booking_id IS NOT NULL
    GROUP BY booking_id
    HAVING COUNT(*) > 1
)
SELECT
    groups.booking_id,
    groups.survivor_id,
    groups.thread_count,
    (
        SELECT COUNT(*)
        FROM support_messages messages
        JOIN support_threads threads ON threads.id = messages.thread_id
        WHERE threads.thread_type = 'BOOKING_THREAD'
          AND threads.booking_id = groups.booking_id
          AND threads.id <> groups.survivor_id
    ) AS expected_message_reparents,
    (
        SELECT COUNT(*)
        FROM support_thread_reads reads
        JOIN support_threads threads ON threads.id = reads.thread_id
        WHERE threads.thread_type = 'BOOKING_THREAD'
          AND threads.booking_id = groups.booking_id
    ) AS expected_read_rows_removed,
    (
        SELECT COUNT(DISTINCT reads.user_id)
        FROM support_thread_reads reads
        JOIN support_threads threads ON threads.id = reads.thread_id
        WHERE threads.thread_type = 'BOOKING_THREAD'
          AND threads.booking_id = groups.booking_id
    ) AS expected_exact_read_markers_inserted,
    (
        SELECT COUNT(*)
        FROM audit_log audit
        JOIN support_threads threads ON threads.id = audit.entity_id
        WHERE audit.entity_type = 'support_ticket'
          AND threads.thread_type = 'BOOKING_THREAD'
          AND threads.booking_id = groups.booking_id
          AND threads.id <> groups.survivor_id
    ) AS expected_audit_reparents
FROM duplicate_groups groups
ORDER BY groups.booking_id;

\echo 'FINAL unsafe predicate count'
WITH RECURSIVE duplicate_groups AS (
    SELECT booking_id, COUNT(*) AS thread_count
    FROM support_threads
    WHERE thread_type = 'BOOKING_THREAD'
      AND booking_id IS NOT NULL
    GROUP BY booking_id
    HAVING COUNT(*) > 1
), partial_reads AS (
    SELECT groups.booking_id, reads.user_id
    FROM duplicate_groups groups
    JOIN support_threads threads
      ON threads.thread_type = 'BOOKING_THREAD'
     AND threads.booking_id = groups.booking_id
    JOIN support_thread_reads reads ON reads.thread_id = threads.id
    GROUP BY groups.booking_id, groups.thread_count, reads.user_id
    HAVING COUNT(*) <> groups.thread_count
), conflicting_read_timestamps AS (
    SELECT groups.booking_id, reads.user_id
    FROM duplicate_groups groups
    JOIN support_threads threads
      ON threads.thread_type = 'BOOKING_THREAD'
     AND threads.booking_id = groups.booking_id
    JOIN support_thread_reads reads ON reads.thread_id = threads.id
    GROUP BY groups.booking_id, reads.user_id
    HAVING COUNT(DISTINCT reads.last_read_at) <> 1
), expected_relation_identity(relation_role, relation_name, column_name) AS (
    VALUES
        ('TARGET'::TEXT, 'support_threads'::NAME, 'id'::NAME),
        ('SUPPORT_MESSAGES'::TEXT, 'support_messages'::NAME, 'thread_id'::NAME),
        ('SUPPORT_THREAD_READS'::TEXT, 'support_thread_reads'::NAME, 'thread_id'::NAME)
), relation_identity AS (
    SELECT
        expected.relation_role,
        relation_schema.oid AS namespace_oid,
        relation_schema.nspname::TEXT AS schema_name,
        relation_table.oid AS relation_oid,
        relation_table.relname::TEXT AS relation_name,
        relation_column.attnum AS column_attnum,
        relation_column.attname::TEXT AS column_name
    FROM expected_relation_identity expected
    JOIN pg_catalog.pg_namespace relation_schema
      ON relation_schema.nspname = CURRENT_SCHEMA()
    JOIN pg_catalog.pg_class relation_table
      ON relation_table.relnamespace = relation_schema.oid
     AND relation_table.relname = expected.relation_name
     AND relation_table.relkind = 'r'
    JOIN pg_catalog.pg_attribute relation_column
      ON relation_column.attrelid = relation_table.oid
     AND relation_column.attname = expected.column_name
     AND relation_column.attnum > 0
     AND NOT relation_column.attisdropped
), identity_issues AS (
    SELECT
        'expected relation identity is not exactly one'::TEXT AS issue,
        CURRENT_SCHEMA()::TEXT AS table_name,
        expected.relation_name::TEXT AS column_name
    FROM expected_relation_identity expected
    LEFT JOIN relation_identity actual
      ON actual.relation_role = expected.relation_role
    GROUP BY expected.relation_role, expected.relation_name
    HAVING COUNT(actual.relation_oid) <> 1
), inbound_references AS (
    SELECT
        fk_constraint.oid AS constraint_oid,
        fk_constraint.conname::TEXT AS constraint_name,
        fk_constraint.conrelid AS source_relation_oid,
        source_table.relnamespace AS source_namespace_oid,
        source_schema.nspname::TEXT AS source_schema_name,
        source_table.relname::TEXT AS source_relation_name,
        fk_constraint.confrelid AS target_relation_oid,
        fk_constraint.conkey::SMALLINT[] AS source_attnums,
        fk_constraint.confkey::SMALLINT[] AS target_attnums,
        ARRAY(
            SELECT source_attribute.attname::TEXT
            FROM pg_catalog.generate_subscripts(fk_constraint.conkey, 1)
                AS source_key(ordinality)
            LEFT JOIN pg_catalog.pg_attribute source_attribute
              ON source_attribute.attrelid = fk_constraint.conrelid
             AND source_attribute.attnum = fk_constraint.conkey[source_key.ordinality]
             AND source_attribute.attnum > 0
             AND NOT source_attribute.attisdropped
            ORDER BY source_key.ordinality
        )::TEXT[] AS source_column_names,
        ARRAY(
            SELECT target_attribute.attname::TEXT
            FROM pg_catalog.generate_subscripts(fk_constraint.confkey, 1)
                AS target_key(ordinality)
            LEFT JOIN pg_catalog.pg_attribute target_attribute
              ON target_attribute.attrelid = fk_constraint.confrelid
             AND target_attribute.attnum = fk_constraint.confkey[target_key.ordinality]
             AND target_attribute.attnum > 0
             AND NOT target_attribute.attisdropped
            ORDER BY target_key.ordinality
        )::TEXT[] AS target_column_names,
        pg_catalog.CARDINALITY(fk_constraint.conkey) AS source_column_count,
        pg_catalog.CARDINALITY(fk_constraint.confkey) AS target_column_count,
        fk_constraint.confupdtype::TEXT AS confupdtype,
        fk_constraint.confdeltype::TEXT AS confdeltype,
        fk_constraint.confmatchtype::TEXT AS confmatchtype
    FROM relation_identity target
    JOIN pg_catalog.pg_constraint fk_constraint
      ON fk_constraint.contype = 'f'
     AND fk_constraint.confrelid = target.relation_oid
    LEFT JOIN pg_catalog.pg_class source_table
      ON source_table.oid = fk_constraint.conrelid
    LEFT JOIN pg_catalog.pg_namespace source_schema
      ON source_schema.oid = source_table.relnamespace
    WHERE target.relation_role = 'TARGET'
), expected_fk_contracts AS (
    SELECT
        expected.source_role,
        source.namespace_oid AS source_namespace_oid,
        source.schema_name AS source_schema_name,
        source.relation_oid AS source_relation_oid,
        source.relation_name AS source_relation_name,
        source.column_attnum AS source_column_attnum,
        source.column_name AS source_column_name,
        target.relation_oid AS target_relation_oid,
        target.column_attnum AS target_column_attnum,
        target.column_name AS target_column_name
    FROM (
        VALUES ('SUPPORT_MESSAGES'::TEXT), ('SUPPORT_THREAD_READS'::TEXT)
    ) expected(source_role)
    LEFT JOIN relation_identity source
      ON source.relation_role = expected.source_role
    LEFT JOIN relation_identity target
      ON target.relation_role = 'TARGET'
), reference_matches AS (
    SELECT expected.source_role, actual.constraint_oid
    FROM expected_fk_contracts expected
    JOIN inbound_references actual
      ON actual.source_relation_oid = expected.source_relation_oid
     AND actual.source_namespace_oid = expected.source_namespace_oid
     AND actual.source_schema_name = expected.source_schema_name
     AND actual.source_relation_name = expected.source_relation_name
     AND actual.target_relation_oid = expected.target_relation_oid
     AND actual.source_column_count = 1
     AND actual.target_column_count = 1
     AND actual.source_attnums = ARRAY[expected.source_column_attnum]::SMALLINT[]
     AND actual.target_attnums = ARRAY[expected.target_column_attnum]::SMALLINT[]
     AND actual.source_column_names = ARRAY[expected.source_column_name]::TEXT[]
     AND actual.target_column_names = ARRAY[expected.target_column_name]::TEXT[]
     AND actual.confupdtype = 'a'
     AND actual.confdeltype = 'c'
     AND actual.confmatchtype = 's'
), reference_issues AS (
    SELECT issue, table_name, column_name
    FROM identity_issues
    UNION ALL
    SELECT
        'unknown inbound FK constraint',
        COALESCE(
            actual.source_schema_name || '.' || actual.source_relation_name,
            '<unresolved source relation>'
        ),
        COALESCE(actual.constraint_name, 'oid=' || actual.constraint_oid::TEXT)
    FROM inbound_references actual
    LEFT JOIN reference_matches matched USING (constraint_oid)
    WHERE matched.constraint_oid IS NULL
    UNION ALL
    SELECT
        'expected inbound FK multiplicity is not exactly one',
        COALESCE(expected.source_schema_name, CURRENT_SCHEMA())::TEXT,
        COALESCE(expected.source_relation_name, expected.source_role)
    FROM expected_fk_contracts expected
    LEFT JOIN reference_matches matched USING (source_role)
    GROUP BY
        expected.source_role,
        expected.source_schema_name,
        expected.source_relation_name
    HAVING COUNT(matched.constraint_oid) <> 1
    UNION ALL
    SELECT
        'physical inbound FK count is not exactly two',
        CURRENT_SCHEMA()::TEXT,
        'support_threads'::TEXT
    WHERE (SELECT COUNT(*) FROM inbound_references) <> 2
), expected_reference_columns(table_name, column_name) AS (
    VALUES ('support_messages', 'thread_id'), ('support_thread_reads', 'thread_id')
), expected_json(table_name, column_name) AS (
    VALUES
        ('analytics_events', 'payload_json'),
        ('audit_log', 'payload_json'),
        ('billing_invoices', 'provider_raw_payload'),
        ('billing_notifications', 'payload_json'),
        ('billing_payments', 'raw_payload'),
        ('guest_batch_idempotency', 'response_snapshot'),
        ('menu_items', 'options'),
        ('order_batches', 'items_snapshot'),
        ('order_promotion_applications', 'schedule_snapshot_json'),
        ('order_promotion_applications', 'target_snapshot_json'),
        ('telegram_dialog_state', 'payload'),
        ('telegram_inbound_updates', 'payload_json'),
        ('telegram_outbox', 'payload_json'),
        ('venues', 'features'),
        ('venues', 'ui_layout'),
        ('visit_feedback', 'tags_json')
), actual_json AS (
    SELECT LOWER(columns.table_name) AS table_name, LOWER(columns.column_name) AS column_name
    FROM information_schema.columns columns
    WHERE columns.table_schema = CURRENT_SCHEMA()
      AND (
          LOWER(columns.data_type) IN ('json', 'jsonb')
          OR LOWER(columns.column_name) LIKE '%json%'
          OR LOWER(columns.column_name) IN (
              'payload',
              'raw_payload',
              'provider_raw_payload',
              'features',
              'ui_layout',
              'options',
              'items_snapshot',
              'response_snapshot'
          )
      )
), non_audit_durable_json AS (
    SELECT features::TEXT AS payload FROM venues
    UNION ALL SELECT ui_layout::TEXT FROM venues
    UNION ALL SELECT options::TEXT FROM menu_items
    UNION ALL SELECT items_snapshot::TEXT FROM order_batches
    UNION ALL SELECT payload::TEXT FROM telegram_dialog_state
    UNION ALL SELECT raw_payload FROM billing_payments
    UNION ALL SELECT provider_raw_payload FROM billing_invoices
    UNION ALL SELECT payload_json FROM billing_notifications
    UNION ALL SELECT payload_json FROM telegram_inbound_updates
    UNION ALL SELECT payload_json FROM telegram_outbox
    UNION ALL SELECT response_snapshot::TEXT FROM guest_batch_idempotency
    UNION ALL SELECT payload_json FROM analytics_events
    UNION ALL SELECT tags_json FROM visit_feedback
    UNION ALL SELECT schedule_snapshot_json FROM order_promotion_applications
    UNION ALL SELECT target_snapshot_json FROM order_promotion_applications
), audit_payloads AS (
    SELECT
        audit.*,
        audit.payload_json IS JSON OBJECT AS payload_is_object,
        CASE
            WHEN audit.payload_json IS JSON THEN (audit.payload_json::JSONB)::TEXT
            ELSE audit.payload_json
        END AS normalized_payload,
        ticket.ticket_id_count,
        ticket.ticket_id_value
    FROM audit_log audit
    CROSS JOIN LATERAL (
        SELECT
            COUNT(*) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_count,
            MIN(entry.value::TEXT) FILTER (WHERE entry.key = 'ticketId') AS ticket_id_value
        FROM JSON_EACH(
            CASE
                WHEN audit.payload_json IS JSON OBJECT THEN audit.payload_json::JSON
                ELSE '{}'::JSON
            END
        ) entry
    ) ticket
), audit_nodes(audit_id, node, depth) AS (
    SELECT audit.id, audit.payload_json::JSONB, 0
    FROM audit_log audit
    WHERE audit.payload_json IS JSON
    UNION ALL
    SELECT parent.audit_id, child.value, parent.depth + 1
    FROM audit_nodes parent
    CROSS JOIN LATERAL (
        SELECT object_child.value
        FROM JSONB_EACH(
            CASE WHEN JSONB_TYPEOF(parent.node) = 'object' THEN parent.node ELSE '{}'::JSONB END
        ) object_child
        UNION ALL
        SELECT array_child.value
        FROM JSONB_ARRAY_ELEMENTS(
            CASE WHEN JSONB_TYPEOF(parent.node) = 'array' THEN parent.node ELSE '[]'::JSONB END
        ) array_child
    ) child
), audit_keys AS (
    SELECT parent.audit_id, parent.depth, entry.key
    FROM audit_nodes parent
    CROSS JOIN LATERAL JSONB_EACH(
        CASE WHEN JSONB_TYPEOF(parent.node) = 'object' THEN parent.node ELSE '{}'::JSONB END
    ) entry
), unknown_audit_reference_keys AS (
    SELECT audit_id
    FROM (
        SELECT
            audit_id,
            depth,
            key,
            REGEXP_REPLACE(LOWER(NORMALIZE(key, NFKC)), '[_.[:space:]-]', '', 'g') AS compact_key
        FROM audit_keys
    ) normalized
    WHERE NOT (depth = 0 AND key = 'ticketId')
      AND compact_key ~ '(thread|ticket|conversation).*(ids|refs|id|ref)'
), booking_audits AS (
    SELECT audit.*
    FROM audit_payloads audit
    JOIN support_threads thread
      ON thread.id = audit.entity_id
     AND thread.thread_type = 'BOOKING_THREAD'
), required_lock_targets(table_name, lock_order) AS (
    VALUES
        ('bookings', 1),
        ('support_threads', 2),
        ('support_messages', 3),
        ('support_thread_reads', 4),
        ('audit_log', 5)
), lock_targets AS (
    SELECT
        required.table_name,
        required.lock_order,
        TO_REGCLASS(FORMAT('%I.%I', CURRENT_SCHEMA(), required.table_name)) AS relation_oid
    FROM required_lock_targets required
), unsafe_checks AS (
    SELECT COUNT(*) AS row_count
    FROM support_threads
    WHERE thread_type = 'BOOKING_THREAD' AND booking_id IS NULL
    UNION ALL
    SELECT COUNT(*)
    FROM support_threads st
    LEFT JOIN bookings b ON b.id = st.booking_id
    WHERE st.thread_type = 'BOOKING_THREAD' AND st.booking_id IS NOT NULL AND b.id IS NULL
    UNION ALL
    SELECT COUNT(*)
    FROM support_threads st
    JOIN bookings b ON b.id = st.booking_id
    WHERE st.thread_type = 'BOOKING_THREAD'
      AND (st.venue_id IS DISTINCT FROM b.venue_id OR st.guest_user_id IS DISTINCT FROM b.user_id)
    UNION ALL
    SELECT COUNT(*)
    FROM (
        SELECT booking_id
        FROM support_threads
        WHERE thread_type = 'BOOKING_THREAD' AND booking_id IS NOT NULL
        GROUP BY booking_id
        HAVING COUNT(*) > 1 AND COUNT(DISTINCT status) > 1
    ) conflicts
    UNION ALL
    SELECT COUNT(*) FROM partial_reads
    UNION ALL
    SELECT COUNT(*) FROM conflicting_read_timestamps
    UNION ALL
    SELECT COUNT(*) FROM reference_issues
    UNION ALL
    SELECT COUNT(*)
    FROM information_schema.columns columns
    LEFT JOIN expected_reference_columns expected
      ON expected.table_name = LOWER(columns.table_name)
     AND expected.column_name = LOWER(columns.column_name)
    WHERE columns.table_schema = CURRENT_SCHEMA()
      AND REGEXP_REPLACE(LOWER(columns.column_name), '[^a-z0-9]', '', 'g') IN (
          'threadid',
          'supportthreadid',
          'bookingthreadid',
          'ticketid'
      )
      AND expected.table_name IS NULL
    UNION ALL
    SELECT COUNT(*)
    FROM actual_json actual
    LEFT JOIN expected_json expected USING (table_name, column_name)
    WHERE expected.table_name IS NULL
    UNION ALL
    SELECT COUNT(*)
    FROM expected_json expected
    LEFT JOIN actual_json actual USING (table_name, column_name)
    WHERE actual.table_name IS NULL
    UNION ALL
    SELECT COUNT(*)
    FROM non_audit_durable_json durable_json
    WHERE (
        CASE
            WHEN durable_json.payload IS JSON THEN (durable_json.payload::JSONB)::TEXT
            ELSE durable_json.payload
        END
    ) ~
        '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    UNION ALL
    SELECT COUNT(*)
    FROM lock_targets target
    WHERE target.relation_oid IS NULL
       OR NOT (
           HAS_TABLE_PRIVILEGE(CURRENT_USER, target.relation_oid, 'UPDATE')
           OR HAS_TABLE_PRIVILEGE(CURRENT_USER, target.relation_oid, 'DELETE')
           OR HAS_TABLE_PRIVILEGE(CURRENT_USER, target.relation_oid, 'TRUNCATE')
           OR PG_HAS_ROLE(
               CURRENT_USER,
               (SELECT relation.relowner FROM pg_catalog.pg_class relation WHERE relation.oid = target.relation_oid),
               'USAGE'
           )
           OR EXISTS (
               SELECT 1
               FROM pg_catalog.pg_roles role
               WHERE role.rolname = CURRENT_USER
                 AND role.rolsuper
           )
       )
    UNION ALL
    SELECT COUNT(*)
    FROM unknown_audit_reference_keys
    UNION ALL
    SELECT COUNT(*)
    FROM booking_audits audit
    WHERE (
        audit.entity_type = 'support_ticket'
        OR audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
        OR audit.normalized_payload ~
            '"(ticketId|threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    )
      AND (
          audit.entity_type <> 'support_ticket'
          OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
      )
    UNION ALL
    SELECT COUNT(*)
    FROM audit_payloads audit
    WHERE audit.normalized_payload ~ '"ticketId"[[:space:]]*:'
      AND NOT audit.payload_is_object
    UNION ALL
    SELECT COUNT(*)
    FROM audit_payloads audit
    WHERE audit.normalized_payload ~ '"ticketId"[[:space:]]*:'
      AND (
          audit.ticket_id_count <> 1
          OR REGEXP_COUNT(audit.normalized_payload, '"ticketId"[[:space:]]*:') <> 1
      )
    UNION ALL
    SELECT COUNT(*)
    FROM audit_payloads audit
    WHERE audit.normalized_payload ~ '"ticketId"[[:space:]]*:'
      AND audit.ticket_id_count = 1
      AND audit.ticket_id_value !~ '^-?(0|[1-9][0-9]*)$'
    UNION ALL
    SELECT COUNT(*)
    FROM audit_payloads audit
    WHERE audit.normalized_payload ~
        '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    UNION ALL
    SELECT COUNT(*)
    FROM audit_payloads audit
    JOIN support_threads thread
      ON thread.thread_type = 'BOOKING_THREAD'
     AND audit.ticket_id_count = 1
     AND thread.id::TEXT = audit.ticket_id_value
    WHERE audit.entity_type <> 'support_ticket'
       OR audit.action <> 'SUPPORT_TICKET_STATUS_CHANGED'
       OR audit.entity_id IS DISTINCT FROM thread.id
    UNION ALL
    SELECT COUNT(*)
    FROM booking_audits audit
    WHERE audit.entity_type = 'support_ticket'
      AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
      AND NOT audit.payload_is_object
    UNION ALL
    SELECT COUNT(*)
    FROM booking_audits audit
    WHERE audit.entity_type = 'support_ticket'
      AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
      AND (
          audit.ticket_id_count <> 1
          OR REGEXP_COUNT(audit.normalized_payload, '"ticketId"[[:space:]]*:') <> 1
      )
    UNION ALL
    SELECT COUNT(*)
    FROM booking_audits audit
    WHERE audit.entity_type = 'support_ticket'
      AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
      AND audit.ticket_id_count = 1
      AND audit.ticket_id_value !~ '^-?(0|[1-9][0-9]*)$'
    UNION ALL
    SELECT COUNT(*)
    FROM booking_audits audit
    WHERE audit.entity_type = 'support_ticket'
      AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
      AND audit.normalized_payload ~
          '"(threadId|thread_id|supportThreadId|support_thread_id|bookingThreadId|booking_thread_id|ticket_id)"[[:space:]]*:'
    UNION ALL
    SELECT COUNT(*)
    FROM booking_audits audit
    WHERE audit.entity_type = 'support_ticket'
      AND audit.action = 'SUPPORT_TICKET_STATUS_CHANGED'
      AND audit.ticket_id_count = 1
      AND audit.ticket_id_value ~ '^-?(0|[1-9][0-9]*)$'
      AND audit.ticket_id_value IS DISTINCT FROM audit.entity_id::TEXT
)
SELECT
    SUM(row_count) AS unsafe_row_count,
    CASE WHEN SUM(row_count) > 0 THEN 'true' ELSE 'false' END AS booking_preflight_unsafe
FROM unsafe_checks
\gset

COMMIT;
\echo 'unsafe_row_count=' :unsafe_row_count
\if :booking_preflight_unsafe
  \echo 'STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION'
  \quit 3
\endif
\echo 'BOOKING_THREAD_PREFLIGHT_SAFE_TO_CONTINUE'
SQL
```
<!-- BOOKING_UNREAD_PREFLIGHT_END -->

The one affected logical support-thread audit family is
`audit_log(entity_type='support_ticket', entity_id=thread.id, payload_json.ticketId=thread.id)` with
the explicit booking-compatible action `SUPPORT_TICKET_STATUS_CHANGED`. There is no accepted legacy
booking-audit action without `ticketId`. Other audit entity types use unrelated id spaces, so a
numeric collision alone is not rewritten; if an explicit payload `ticketId` resolves to a booking
thread but the audit envelope is not canonical, the preflight and migration stop for a decision.
Valid JSON is normalized before logical-key scanning, including decoding JSON Unicode escapes.
Audit `ticketId` cardinality is counted from decoded top-level `json` keys rather than collapsed
`jsonb`, so a plain key plus an escaped spelling of the same logical key is still a duplicate and
fails closed. The same preflight recursively visits decoded object/array keys, applies Unicode NFKC,
locale-independent lowercase and separator removal, and stops on any extra key containing
`thread`/`ticket`/`conversation` plus `id(s)`/`ref(s)`. Exact top-level `ticketId` is the only
allowed thread-reference key; unknown keys are never generically remapped. Unrelated keys such as
`conversationStatus` remain valid negative evidence. The preflight is read-only and returns a
non-zero exit through the existing unsafe-result branch.

PostgreSQL V124 and this preflight keep their semantic JSON-object entry handling unchanged. H2
V125 reaches the same order/whitespace-independent decision through deterministic SQL aliases backed
by the duplicate-aware `BookingAuditReferencePolicy` parser; it does not extract or rewrite
`ticketId` with a regex over serialized JSON. Reordered safe fields, pretty/minified payloads and
Unicode-escaped duplicate keys are migration-test parity cases. This local parity fix remains
`LOCAL_FIX_REVIEW_REQUIRED` until independent review.

The confirmed durable reference set is intentionally narrower than the negative-evidence scan:
`support_messages.thread_id` and `support_thread_reads.thread_id` are physical inbound FKs, while
the audit family above is the only accepted logical JSON reference. The other 15 allowlisted
non-audit JSON/payload/snapshot table-column pairs are scanned as negative evidence; they are not
declared thread references or migration rewrite targets. Adding a new family, inbound FK,
normalized thread-reference column or decoded thread-like key stops both preflight and migration
until it has an explicit remap or no-reference proof.

The read-only preflight sets both timeouts but intentionally does not take the migration's table
locks. PostgreSQL V124 itself, in its single Flyway transaction and before its first catalog or
domain guard, takes `EXCLUSIVE` locks in this order:
`bookings`, `support_threads`, `support_messages`, `support_thread_reads`, `audit_log`. `EXCLUSIVE`
conflicts with both `ROW SHARE` from `SELECT ... FOR UPDATE` and `ROW EXCLUSIVE` from
`INSERT`/`UPDATE`/`DELETE`, while ordinary reads can continue. The `30s` migration lock timeout and
`5min` statement timeout fail and release the transaction instead of applying from an unstable
confirmed-reference set. The 15 negative-evidence payload families are not additional lock targets;
their writer drain remains a mandatory cutover condition. The database locks do not replace that
traffic drain.

Do not weaken the preflight by selecting `MIN`/`MAX(last_read_at)`, accepting a generic missing
`ticketId`, or deleting read/message/audit evidence. Do not run this command against production
without separate release authorization.

### Booking Thread Integrity Cutover Order

PostgreSQL V124 and the additive Mini App message-idempotency migration must be released in this
exact order:

1. Verify a current database backup and an approved restore path are ready.
2. Drain traffic and stop every old booking-message, status, read and audit writer.
3. Verify exactly one backend instance remains for the controlled cutover.
4. Run the read-only, repeatable preflight above and retain its zero-unsafe evidence.
5. Deploy only the new backend binary; do not run old and new booking writers together.
6. Let Flyway apply V124 under its explicit database locks and the additive message migration.
7. Verify `/health`, `/db/health`, migration version and startup logs.
8. Compare post-migration thread/message/read/audit counts and all documented invariants with the
   preflight evidence.
9. Run the bounded two-account Guest/Venue booking-chat smoke, including one safe manual replay.
10. Return traffic only after every preceding check passes.

After schema cutover, the previous binary is not a valid semantic rollback: it does not supply the
persisted Mini App key or same-transaction notification contract. Use a forward fix or the approved
database restore path. A failed V124 transaction leaves its schema version and domain facts
unchanged; still investigate the exact guard/lock failure before retrying.

### Support Read Cursor PostgreSQL V126 Compatibility And Cutover Boundary

The current ordered policy/state-machine contract is `docs/V126_STAGING_CUTOVER_CONTRACT.md`, and
the only executable sequencer is `scripts/v126-cutover.sh`. This section retains the migration shape
and summarizes the uniquely marker-bounded preflight linkage. It is not command authority or
evidence that a backup, Caddy reload, deploy, migration or staging smoke happened.

PostgreSQL V126 is additive. It adds nullable `BIGINT support_thread_reads.last_read_message_id`
with no default, no backfill and no destructive rewrite;
`last_read_at` and the existing primary key `(thread_id, user_id)` are preserved. The old binary is
schema-compatible but updates only `last_read_at`. The new binary treats a NULL cursor as every
foreign-authored message unread, including a user-visible system message whose `author_user_id` is
NULL. No message or marker data is lost, but badges can repeat or be inaccurate if mixed-version
operation occurs; the current contract prohibits that operation rather than treating compatibility
as rollout authorization.

H2 V127 is the additive dialect-parity/test migration. It adds the same nullable
`BIGINT last_read_message_id` with no default, no backfill and no destructive rewrite, while preserving
`last_read_at` and primary key `(thread_id, user_id)`. H2 V127 applies only in H2/local/test
environments. Staging PostgreSQL applies PostgreSQL V126, never H2 V127.

#### HT-12P executable linkage and final-preflight extraction

The pre-HT-12P standalone release, rollout, session-gate, recovery and bounded-smoke command drafts
have been removed from this runbook so they cannot become a second executable path. Use only
`scripts/v126-cutover.sh` for run initialization, one-state execution, authorization, status and
bounded recovery; use `docs/V126_STAGING_CUTOVER_CONTRACT.md` for release identity, ordering,
evidence and stop policy.

The current final V125 booking-integrity preflight source is the one block between
`BOOKING_UNREAD_PREFLIGHT_BEGIN` and `BOOKING_UNREAD_PREFLIGHT_END` earlier in this file. The
sequencer extractor requires both markers exactly once and in order, exactly one `bash` fence, and
this exact prefix:

```bash
set -euo pipefail
: "${DATABASE_URL:?DATABASE_URL must bind the exact target}"
psql "${DATABASE_URL}" -X --set=ON_ERROR_STOP=1 <<'SQL'
```

It extracts the shell body byte-for-byte from the immutable `RELEASE_SHA` Git blob through the
sanitized Git boundary, rejects any missing, duplicate, reversed, empty or ambiguous marker/fence,
rejects an unexpected client surface, records the SHA-256 and transfers only the mode-0600 artifact
into the sealed run namespace. The remote stage reads and hashes that source once, then feeds those
same in-memory bytes to Bash; a later pathname swap cannot change credentialed execution. The remote
stage reads `DATABASE_URL` only from the exact restricted
mode-0600 target file and fails before `psql` if that binding is missing, blank or malformed. The
artifact runs only after the quiesced-backup receipt and zero-writer/public-drain proofs validate.

A clipboard copy, task-message snippet, cached script, historical branch or manually edited extract
is not evidence and must not be executed. Release identity, no-build image transfer, Caddy drain,
manual smoke, OFF transition and all three recovery branches remain summaries in this runbook; their
complete policy is the contract and their complete commands are the sequencer.

### Guest Order Payload-Bound Idempotency Rollout Boundary

This planned rollout for additive PostgreSQL V123 / H2 V124 has not been executed in production:

1. Require green GitHub Actions on the exact release HEAD.
2. Apply the additive migration; if Flyway runs during startup, confirm from deploy logs that it
   completes before order-writing traffic is accepted.
3. Start only the new backend binary.
4. Drain or replace every order-writing backend instance.
5. Confirm exactly one staging backend remains and that it runs the new binary.
6. After cutover, old order writers are prohibited because they can create rows with
   `request_fingerprint NULL`.
7. Mixed old/new binaries are allowed only as a brief migration-compatible transition, never as the
   completed rollout state.
8. After new fingerprint rows exist, rollback to the old binary does not damage their data, but it
   semantically loses payload validation for idempotency reuse.
9. After real order traffic, prefer a forward fix over old-binary rollback.
10. Before UI or release smoke, verify that no old order-writing instance remains.

### Staff Operations Slice B Rollout Boundary

`STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE /
DONE / MVP / STAGING-SMOKE-PASSED` uses additive PostgreSQL V121 and H2 V122 migrations. The
staging rollout completed in this order:

1. Staging PostgreSQL applied V121; the Testcontainers migration run had already executed
   V120 -> V121 with `skipped=0` and `failures=0`.
2. Only the new backend binary was deployed.
3. Instance verification showed exactly one new backend instance and no remaining old backend
   instances before settings mutation.
4. Settings mutation was exercised only after that verification.
5. The bounded Owner/Manager/Staff/Guest manual smoke from the canonical Staff plan passed, and
   cleanup restored `true / true / MANUAL` plus the original manual Today state.

Before real Slice B settings use, the old binary was structurally schema-compatible because it
ignores the additive columns. After a venue switches `todayStaffSource` to `SCHEDULE`, an old binary
would ignore that source and resume `MANUAL` Guest behavior. Previous-binary rollback is therefore
semantically unsafe after real settings use; use a forward fix. Feature-specific rollback command
refinement remains open, and exact deploy/rollback commands remain subject to the existing runbook
verification requirement.

## Staging Smoke Policy

After runtime staging deploy:
1. Check health:
   ```bash
   curl -f https://staging.hookahtootah.club/health
   curl -f https://staging.hookahtootah.club/db/health
   curl -I https://staging.hookahtootah.club/miniapp/
   ```
2. Open Guest Mini App.
3. Open Venue Mode if Venue flows changed.
4. Open Platform Mode if Platform/billing/support/lifecycle changed.
5. Run changed-area smoke from `docs/TESTING_QA_SMOKE_STRATEGY.md`.
6. Verify support/venue chat messages do not spam staff-chat.
7. Verify Staff denial for affected forbidden surfaces.
8. Verify Platform visibility boundaries for affected support/billing/lifecycle surfaces.

Area smoke anchors:
- Support and guest communication: `docs/COMMUNICATION_MODEL.md`.
- Venue operations: `docs/VENUE_OPERATIONS.md`.
- Order/session/tab: `docs/ORDER_SESSION_TAB_CORE.md`.
- Menu/stop-list: `docs/MENU_OPTIONS_STOPLIST.md`.
- Booking: `docs/BOOKING_LIFECYCLE.md`.
- Telegram/staff-chat: `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`.
- Platform/billing: `docs/PLATFORM_COCKPIT.md`.
- RBAC/security: `docs/SECURITY_RBAC_MATRIX.md`.

## Logs And Troubleshooting Index

Known commands:
- Public health:
  ```bash
  curl -f https://staging.hookahtootah.club/health
  curl -f https://staging.hookahtootah.club/db/health
  curl -I https://staging.hookahtootah.club/miniapp/
  ```
- Container state/logs on staging:
  ```bash
  ssh hookah-staging
  cd /opt/hookah-bot
  docker compose ps
  docker compose logs --tail=120 backend
  ```
- Local metrics when backend is reachable:
  ```bash
  curl -sS http://localhost:8080/metrics
  ```

Needs verification:
- exact production log access;
- exact database backup/restore commands outside the staging-only V126 contract;
- exact billing provider dashboard/log flow;
- exact Telegram webhook registration command used in staging if/when webhook mode is enabled;
- exact outbox/staff-chat diagnostic SQL or admin UI.

Troubleshooting cases:

| Symptom | First checks | Likely next action |
| --- | --- | --- |
| Telegram webhook not receiving updates | Bot mode, webhook secret token, proxy route, backend logs. | Verify setWebhook/getWebhookInfo flow before changing code. |
| Mini App auth fails | `initData` delivery, public URL, CORS, backend auth logs. | Smoke from real Telegram WebView; plain browser is not enough. |
| CORS/preflight failure | `CORS_ALLOWED_HOSTS`, browser console, request method/headers. | Add focused backend preflight test if runtime code changes. |
| Staging deploy failed | Last script phase, Docker build logs, SSH/rsync errors, container state. | Do not redeploy blindly; inspect failure boundary first. |
| Migration failed | Flyway error, DB health, last applied migration. | Stop release; decide forward fix/restore path. |
| Staff-chat notification missing | Venue staff-chat link, outbox/logs, Telegram API errors. | Verify Venue Mode still has source-of-truth event. |
| Support ticket not visible to Platform | Assignee scope, status filters, Platform route auth, nullable venue queries. | Check support list/detail API before UI. |
| Venue chat not visible to Venue | Venue scope, thread type/filter, role access. | Preserve booking/support separation. |
| Booking not in queue | Booking status, venue id, hold/deadline filters, role. | Compare Guest list and Venue queue data. |
| Billing webhook rejected | Provider signature/config, webhook secret, idempotency, logs. | Do not manually mark paid without audit. |
| Platform Owner cannot access Platform Mode | `PLATFORM_OWNER_TELEGRAM_ID`, auth session, Telegram id mapping. | Verify config and platform role gates. |
| Staff sees forbidden nav/API | Mini App nav plus backend RBAC. | Treat backend allow as security bug; UI hiding alone is insufficient. |

## Telegram Webhook / Outbox Operations

Target policy:
- Telegram webhook should be protected by `TELEGRAM_WEBHOOK_SECRET_TOKEN` when webhook mode is used.
- Long polling and webhook mode must not run for the same bot token at the same time.
- Outbound Telegram messages should go through outbox/retry/backoff where implemented.
- Telegram API `429` should be treated as rate-limit pressure, not as a reason to bypass the outbox.

Operational checks:
- Check `/metrics` for inbound/outbound queue depth where available.
- Verify bot responds to `/start`.
- Verify real Telegram Mini App opens with `initData`.
- Verify staff-chat notification delivery only for allowed operational events.

Needs verification:
- current staging bot mode before each deploy;
- exact webhook registration/unregistration command in active operations;
- outbox replay/admin tooling.

## Staff-Chat Diagnostics

Staff-chat is notification/radar/shortcut, not source of truth.

Allowed smoke:
- link/test/unlink if the task touches staff-chat config;
- order batch notification;
- staff-call notification;
- role denial on staff-chat callback;
- callback state update/idempotency where implemented.

Forbidden:
- support ticket create/reply notifications to staff-chat;
- ordinary venue chat messages to staff-chat;
- raw payment secrets, raw Telegram initData or broad PII in staff-chat.

If staff-chat delivery fails:
1. Confirm venue has linked staff chat.
2. Confirm changed event is allowed by policy.
3. Check Telegram outbox/logs.
4. Verify Venue Mode shows the source-of-truth state.
5. Do not use staff-chat as the only recovery path.

## Billing Webhook Diagnostics

Current billing implementation is manual/fake-provider for closed MVP slices unless a specific provider rollout is cited.

Rules:
- Do not switch production provider behavior without provider test confirmation.
- Do not log raw provider secrets, card data or webhook signatures.
- Manual invoice/subscription changes require audit.
- Payment webhook rejection must preserve enough safe diagnostic data to debug signature/idempotency/state mismatch.

Needs verification:
- exact provider dashboard/test-event flow;
- exact webhook replay process;
- exact production secret rotation process.

## Rollback Policy

| Change type | Rollback policy |
| --- | --- |
| Docs-only | Revert/fix docs commit if wrong. No staging rollback. |
| Frontend-only | Redeploy previous image/build if available; exact process **needs verification**. |
| Backend runtime | Previous image plus migration compatibility check; exact image rollback command **needs verification**. |
| DB migration | Prefer forward fix unless explicit safe rollback/restore exists. |
| Billing/payment | Do not silently change `paid_until`, invoices or subscriptions; use audited correction. |
| Venue lifecycle/security | Rollback requires reason/audit and visibility review. |

Do not promise a rollback command that the repo/runbook does not currently prove. Mark missing rollback commands as **RUNBOOK GAP** and add a follow-up task.

For PostgreSQL V126 staging, do not use the ordinary deployment or rollback examples. The only
approved executable branches are `scripts/v126-cutover.sh recover ...` under the exact pre-V126,
post-V126-stop and full-DR-prerequisite policies in
`docs/V126_STAGING_CUTOVER_CONTRACT.md`. None is an automatic fallback.

For the booking-thread V124/message-idempotency cutover specifically, an old backend binary is
semantically prohibited after the schema cutover because it omits the required Mini App replay key
and atomic outbox write. The only approved recovery directions are a forward fix or the previously
verified database restore path; do not restore traffic to the old writer as a routine rollback.

## Incident Response

| Severity | Definition | First response |
| --- | --- | --- |
| SEV0 | Orders/QR/Mini App broadly broken across platform. | Check health, DB, deploy status, Telegram runtime; mitigate or rollback; notify stakeholders. |
| SEV1 | One venue cannot receive orders/staff-chat or critical operational flow is down. | Check venue config, staff-chat link, queue visibility, logs; apply targeted mitigation. |
| SEV2 | Support, billing, booking or role flow broken for subset. | Preserve evidence, check affected route/logs, decide hotfix vs scheduled fix. |
| SEV3 | Docs, copy or non-critical UI issue. | Fix in normal queue; no emergency deploy unless misleading release instructions. |

Incident workflow:
1. Triage severity and affected roles/venues.
2. Preserve failure evidence, time window, changed commit and last deploy command.
3. Check health/logs before redeploy.
4. Mitigate with the least risky action.
5. Record what was verified manually.
6. Create follow-up Codex task with root cause, affected files, tests/smoke needed and rollback notes.

## Product Release Checklist

Before release-ready:
- Area docs updated if behavior changed.
- Local validation passed for change type.
- GitHub Actions green.
- Staging deployed if runtime behavior changed.
- Changed-area smoke passed.
- Known gaps recorded.
- `scripts/dev/` not staged.
- No secrets in diff.
- Migration reviewed if any.
- Manual smoke owner/result recorded.

## Codex / ChatGPT Handoff Format

Codex final response should include:
- Verdict;
- changed files;
- what changed;
- tests/validation;
- open/future;
- `git status --short`;
- whether `scripts/dev/` was touched;
- whether staging deploy is needed.

ChatGPT should return:
- exact `git add` list;
- commit message;
- push instruction;
- deploy instruction if needed;
- manual smoke checklist;
- what to send back if Actions fail.

## Roadmap Status

- Deployment/runbook docs: `UPDATED`.
- PostgreSQL V126 staging contract: `HT-12P HARDENED CANDIDATE IMPLEMENTED AFTER FIRST REVIEW BLOCK /
  LOCAL ADVERSARIAL VALIDATION PASSED / INDEPENDENT READ-ONLY RE-REVIEW REQUIRED BEFORE COMMIT AND
  PUSH / EXACT GREEN FEATURE-BRANCH ACTIONS REQUIRED AFTER PUSH`; no staging access,
  backup, Caddy reload, maintenance activation, image transfer, migration, deploy or smoke is
  recorded. Command authority is `scripts/v126-cutover.sh`; policy/state-machine authority is
  `docs/V126_STAGING_CUTOVER_CONTRACT.md`.
- Staging deploy policy: `DOCUMENTED`.
- Rollback policy: V126 pre-runtime rollback, post-V126 forward-fix stop and full-DR prerequisite
  verification are bounded sequencer branches; unrelated production rollback remains `PARTIAL`.
- Operations troubleshooting: `PARTIAL`; exact production log and provider diagnostic commands need verification.
- Production release process: `FUTURE / PARTIAL`; the V126 commands are staging-only and are not
  production authorization.
- `scripts/dev/` policy: `DOCUMENTED`.
