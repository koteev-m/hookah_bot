# HT-12M Identity Maintenance Main-Port Evidence

Status: **integrated in main / prerequisite complete**.

This document records the exact V125-to-main source mapping and the immutable migration boundary for
the identity-gated V126 maintenance prerequisite. It is release evidence, not an activation record;
maintenance identities remain restricted operator data and never belong in Git.

## Immutable anchors

- V125 source base: `be5d62a5e9058f89cd72be6c313c71fa46ccdbf2`, tree
  `8806a4cb7a5f1af0f2e4cecdd166c7ff585ca19c`.
- Reviewed V125 candidate: `f577934691a1a7a79ba327c54e2055425142b7be`, tree
  `f21f7114bf021ddc8f862294e5f54a841200c179`, branch
  `codex/ht-12m-identity-maintenance-v125`.
- Historical main-port base: `b49a89a299d8c9864fcfc5937d455141563b388a`, tree
  `662c6df77ba757e57f9e49f9f75d62f1f0654a15`, parent
  `4daf5546fb622a6b967398f5c25b7bed41d7fa05`.
- Main-port branch: `codex/ht-12m-identity-maintenance-main-port`.
- Main integration commit: `f837b0ed01f68832b305d5a2ed61b3927583f1e9`, tree
  `fe1685020b7bb6f9f097f72f8977be167fa74bb3`, parent
  `b49a89a299d8c9864fcfc5937d455141563b388a`.
- Verified current main: `9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1`, tree
  `4071962a6850d977c4d7c319bfecc7cd4c2273d1`, parent
  `f837b0ed01f68832b305d5a2ed61b3927583f1e9`.
- Exact main Actions: run `33514472076`; workflow `CI`; event `push`; branch `main`; exact head
  `9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1`; attempt `1`; `completed/success`; `11/11` jobs
  successful; zero adverse conclusions.
- Port method: semantic path mapping from the exact V125 base-to-candidate diff; no cherry-pick
  equivalence claim.

## Exact 43-path source mapping

Blob hashes are full Git blob IDs. `candidate / port` uses one hash when the port is byte-identical.

| Path | Category | Candidate / port blob |
|---|---|---|
| `.github/workflows/ci.yml` | `MERGED_WITH_MAIN_CHANGE` | `ddb1624f7ed1bd8da4d0e284ef220fe50fd6f227` / `cc4da37979be03c5bb10957a5d6055e9d90f33cf` |
| `PROJECT_STATUS.md` | `MERGED_WITH_MAIN_CHANGE` | `85f9696b25bdc1435fbba9c008587c58d48eb0ee` / `8c5220a29f1f03b944cf98eee2c25f1a47bf6fa7` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/Application.kt` | `MERGED_WITH_MAIN_CHANGE` | `b688bf7bb631d94d882091f0e0cf8c4ae24b8746` / `6999bd3a2d00917211b7becd91b7bc10fda4b5b1` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/api/ApiErrors.kt` | `PORTED_AS_IS` | `1d1ff227f461b64e590faf8f96f977aade36f30f` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/api/ApiException.kt` | `PORTED_AS_IS` | `3f86b8dbf3408f0fc5f1cd52c88f980cad14bd5a` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/billing/BillingRoutes.kt` | `PORTED_AS_IS` | `5a9efc887f1da7b68e23962dea8ff89ddd88160e` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/maintenance/StagingMaintenancePolicy.kt` | `PORTED_AS_IS` | `49da2a87b9a2bf745a5a69e18b58711adfe5a0b2` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/auth/MiniAppAuthRoutes.kt` | `PORTED_AS_IS` | `293c9fd114e99d79ee883fd6a167158ce33c2eb0` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVenueRoutes.kt` | `PORTED_AS_IS` | `d15bd6e4a82f9e3b1b846e3d3dec6a15b5738ad7` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/StaffChatNotifier.kt` | `MERGED_WITH_MAIN_CHANGE` | `7c415c67401938f84d977125362aa2480e8bddc1` / `7c15dacf5fd6f2e94fbca95fe273ca01e52205ad` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramApiClient.kt` | `PORTED_AS_IS` | `67b91598191f3f82a8ae6c1d5f935c378f75f5df` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt` | `MERGED_WITH_MAIN_CHANGE` | `d0ea2cfa0f92ca8c41504089ffcaadb5f512d461` / `de3487be0e89f97d6d66cf292ff36ac714272dc3` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramInboundUpdateWorker.kt` | `PORTED_AS_IS` | `4a87a689c702186692960e9b187e0619d568157f` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramLongPollingWorker.kt` | `PORTED_AS_IS` | `314990b7a65ee13153f6482fbddf9981cea03a42` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramOutboxEnqueuer.kt` | `MERGED_WITH_MAIN_CHANGE` | `e1d9e059b979e9f638110477cf3c2834edab7096` / `33dd347907a68db01889d745aa109ef35afe8684` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/StaffChatNotificationRepository.kt` | `PORTED_AS_IS` | `d62380c8671587c5eb0cb188d3e5edffaa3f6a66` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/TelegramInboundUpdateQueueRepository.kt` | `PORTED_AS_IS` | `e3f100dafadae3ef8b099c4e7efc980a670cdbdf` |
| `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/TelegramOutboxRepository.kt` | `MERGED_WITH_MAIN_CHANGE` | `d409680f5412bcc0afb73eef78638184b6e6205b` / `54bf152e3d3488e3b8e3ebe1a0c6408d1d2c4f99` |
| `backend/app/src/main/resources/application.conf` | `PORTED_AS_IS` | `b7cc2d0a4fe9aff9a893c4440c3f88cbd261c5aa` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/MiniAppRoutesTest.kt` | `PORTED_AS_IS` | `211e2a4f00643fbb755b009840393dbfc402df5d` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/maintenance/StagingMaintenancePolicyTest.kt` | `PORTED_AS_IS` | `c0f9ed5b3f81053976205a67ba3eac993a9f4eb7` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/maintenance/StagingMaintenanceStartupTest.kt` | `MERGED_WITH_MAIN_CHANGE` | `fb2af468b02d577f9d7bb4de263d6c318c2a2904` / `14024b96a0d932765529c16e41adff6d790f0bca` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/auth/TelegramAuthRouteTest.kt` | `PORTED_AS_IS` | `4ae642533f5630efeb65fd2cfb534314a073c03c` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/session/SessionAuthTest.kt` | `PORTED_AS_IS` | `87813d57d56b16716b031aa5bf29c12bfcb18271` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/StaffProfileLinkConcurrencyPostgresTest.kt` | `PORTED_AS_IS` | `0dd300affb92fc19c23816a11e65eb4263051c7f` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/VenueRbacRoutesTest.kt` | `PORTED_AS_IS` | `e269d19c996235e0e5003278654308315afd7bcc` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/platform/PlatformRoutesTest.kt` | `PORTED_AS_IS` | `6861e3d308865c4e2911fcf737fe8ce6c4fec263` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/TelegramApiClientTrafficPolicyTest.kt` | `PORTED_AS_IS` | `f8223bcc7028a4712b447ff54561f757273a434f` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouterIdempotencyTest.kt` | `PORTED_AS_IS` | `7c2d5e2d72420a8b512a486c2b2f4be4f5d6135b` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouterLinkCommandTest.kt` | `PORTED_AS_IS` | `056b0f55d33ee3ffe7ecc3be9e8ed3dbc5d94faa` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/TelegramInboundUpdateWorkerTest.kt` | `PORTED_AS_IS` | `667bef3cd51a700bead97c2ec10d734793a6bdf9` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/TelegramLongPollingWorkerTest.kt` | `PORTED_AS_IS` | `a9e78b615c7e9832ce3f043791753df76f89b455` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/TelegramOutboxWorkerTest.kt` | `PORTED_AS_IS` | `0d5c7836d91c91f8d2ee7a01ff427256a347d94d` |
| `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/TelegramWebhookRoutesTest.kt` | `PORTED_AS_IS` | `228d2ed1b1f76c1d0884272aa62976a863bd37cd` |
| `docker-compose.yml` | `OBSOLETE_WITH_PROVEN_REPLACEMENT` | `7037cbba0a677590ba108599fb1583cd1d255c93` / `c49cfc4fa4ceb98648062fa9019b21481724beb2` |
| `docs/DEPLOYMENT_RUNBOOK.md` | `MERGED_WITH_MAIN_CHANGE` | `cc48e1d65427b56c6bb7aecf6e39ad3c9b7f11a2` / `865872359b002d8e55100d115c36f996d47a175b` |
| `docs/PRODUCT_SPEC.md` | `PORTED_AS_IS` | `22be5267c83c2cea3b967b97ca093d9547704d0c` |
| `docs/STAGING_DEPLOYMENT.md` | `MERGED_WITH_MAIN_CHANGE` | `9f73334d3fa70237d7989bfe249049d20ee73352` / `a8ae5f2d2134eae92d1d885d9302df9ee4992dbf` |
| `docs/TESTING_QA_SMOKE_STRATEGY.md` | `MERGED_WITH_MAIN_CHANGE` | `3624539ca3ac32f6f631543a8fde9fd31584c985` / `409ac416b1e1df326a04b5bfc603f1f37ae9dce7` |
| `docs/env/staging.env.example` | `PORTED_AS_IS` | `2d53a7c95ddab0c15dcffee3e13489dfeff1477e` |
| `scripts/check-staging-image-identity.sh` | `MERGED_WITH_MAIN_CHANGE` | `2cbfe6bca430012d81eeb22531578ff85d741b1e` / `09df9a855ddc52606c73549c5b93ef551ea8e66a` |
| `scripts/check-staging-maintenance-config.sh` | `PORTED_AS_IS` | `9fbd2af726ee9f2192685e64294af12546d26e85` |
| `scripts/deploy-staging.sh` | `MERGED_WITH_MAIN_CHANGE` | `b8aada582d688824256bc09167c68cf0f8c9166b` / `0321ae11d6be0aa0a31486509a5e415b41bf0dc2` |

Counts: `PORTED_AS_IS=29`, `MERGED_WITH_MAIN_CHANGE=13`,
`ALREADY_EQUIVALENT_IN_MAIN=0`, `OBSOLETE_WITH_PROVEN_REPLACEMENT=1`, `MISSING=0`,
`CONTRACT_CONFLICT=0`.

## Merged-path proof

1. `.github/workflows/ci.yml` retains the V125 maintenance and image-guard selectors inside main's
   expanded split-test/floor CI, and adds exact startup, booking-notifier, booking-JWT and
   ControlMaster-order anchors without reducing any existing floor.
2. `PROJECT_STATUS.md` retains the exact V125 PRODUCT/OFF deploy evidence while advancing the stage
   to this exact main base and preserving main-only booking/conversation and migration facts.
3. `Application.kt` keeps maintenance parsing, the PRODUCT assertion, common JWT gate, ingress,
   outbound and autonomous-writer controls; main's early PRODUCT invite validation and booking
   notifier wiring remain.
4. `StaffChatNotifier.kt` keeps the V125 maintenance check without changing main's canonical booking
   labels, scheduled instant or caller-supplied venue timezone behavior.
5. `TelegramBotRouter.kt` has the same source and port maintenance patch ID
   `75001bc798c952c190f6b6a524db67865628a33e`; the remaining differences are main-only booking,
   invitation and transactional notification behavior.
6. `TelegramOutboxEnqueuer.kt` retains every shared maintenance decision and adds only main's
   venue-scoped transactional booking enqueue using the same maintenance check.
7. `TelegramOutboxRepository.kt` retains V125 claim filtering and unchanged-denied-row semantics,
   while the main-only booking alert uses exact `venue_id + staff_chat_id` authority under
   `FOR UPDATE` before the transactional insert.
8. `StagingMaintenanceStartupTest.kt` retains all three V125 tests textually and adds a real-H2
   fourth test covering main's billing, table-cleanup, booking-expiry and booking-reminder writers,
   with an OFF+PRODUCT start-call control.
9. `docs/DEPLOYMENT_RUNBOOK.md` retains the identity prerequisite, complete boundary inventory,
   drain/503/deactivation and rollback contract alongside main's V126/H2 and booking semantics.
10. `docs/STAGING_DEPLOYMENT.md` retains maintenance transitions while using main's fixed env-file
    admission, pre-SSH ControlMaster preflight, mandatory immutable image inputs and exact rollback
    image check.
11. `docs/TESTING_QA_SMOKE_STRATEGY.md` retains the complete OFF/V126_SMOKE zero-state, outbox and
    existing-JWT matrix while preserving main's booking/conversation and V126/H2 V127 test gates.
12. `scripts/check-staging-image-identity.sh` retains canonical image-ID comparison and extends the
    ordering proof to mandatory full-SHA tags and pre-SSH ControlMaster artifact validation.
13. `scripts/deploy-staging.sh` retains the explicit maintenance authorization and remote policy
    guard within main's admission validator, immutable-image preflight and scrubbed fixed-env-file
    Compose flow.

The `docker-compose.yml` source edit is obsolete with a proven replacement. Main owns application,
Telegram and maintenance configuration through the fixed `env_file: ./.env`; it must not interpolate
the three maintenance keys. `scripts/validate-staging-admission.sh` rejects such interpolation and
scrubs ambient shell overrides, while the byte-identical `application.conf` port defaults the mode
to `OFF`.

## Main-port-only paths

- `README.md`: mandatory full-SHA `BACKEND_IMAGE` and reviewed
  `EXPECTED_BACKEND_IMAGE_ID` examples.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/BookingMessageStaffChatNotifier.kt`:
  main-only booking alerts use the venue-scoped transactional enqueue.
- `backend/app/src/test/kotlin/com/hookah/platform/backend/support/BookingConversationRoutesTest.kt`:
  pre-activation Guest and Manager JWTs are rechecked before main-only conversation/unread work.
- `backend/app/src/test/kotlin/com/hookah/platform/backend/telegram/BookingMessageStaffChatNotifierTest.kt`:
  exact PRODUCT-linked allowed staff-chat enqueue and maintenance-denied zero-outbox behavior.
- `docs/BOOKING_LIFECYCLE.md`: current main booking test floors include the added notifier and
  existing-JWT coverage.
- `scripts/deploy-staging-controlmaster.sh`: deterministic artifact identity completes before SSH.
- `scripts/validate-staging-admission.sh`: all maintenance keys are fixed-env-file-only and immune
  to Compose/shell override.
- This evidence document records the exact mapping and immutable boundaries.

## Stateful and outbound boundary closure

The canonical complete inventory is in `docs/DEPLOYMENT_RUNBOOK.md` under
“Complete V125 stateful and outbound boundary inventory.” The port preserves and tests:

- long polling before Router/idempotency/user/domain state, webhook before queue persistence, and
  queued-inbound defense in depth;
- Telegram initData after signature/freshness but before user/session writes, plus per-request JWT
  subject rechecking before every protected Guest, Venue and Platform route;
- PRODUCT membership/RBAC after maintenance admission, including foreign-venue and Platform
  escalation denial;
- outbox eligibility before claim/status/attempt mutation, later allowed work behind denied rows,
  and the same decision for direct Telegram calls, callback answers and staff notifications;
- exact PRODUCT venue/staff-chat authority for main-only booking-message alerts before outbox state;
- active-mode suppression of subscription billing, table cleanup, booking expiry and booking
  reminders; visit feedback remains independently disabled; and
- read-only health, DB health, version and static assets without stateful capability.

No stateful route or autonomous application writer remains unclassified.

## Immutable migration boundary

No migration path occurs in the 43-path source manifest or in the main-port change set. The required
main baseline identities are:

- full migration tree: `765956602de896b4498a956753272a6bc2d2971e`;
- H2 migration tree: `07b5ba6ccf25e79c9cc419b9095bb664f2cfae18`;
- PostgreSQL migration tree: `bb2778e26e03e03211eab9f149777313f4a6f24b`;
- H2 V126 `V126__booking_miniapp_message_idempotency.sql`:
  `f31460f9a755454619f9622ee6f001e603e6ef70`;
- H2 V127 and PostgreSQL V126 `support_thread_read_message_cursor`:
  `6f39f7d33b1976d0f5eb7a70051bfc5351d12e56` in both trees.

The integrated main result reproduced all five identities exactly. HT-12M ran no Flyway migration,
and HT-12C must keep the same migration trees and blobs byte-identical.

## Local validation

The frozen pre-review worktree passed:

- focused maintenance/config, initData/session/JWT, Guest/Venue/Platform RBAC and main-only booking
  conversation coverage; exact affected XML included policy `4`, startup `4`, auth `12`, session
  `7`, Mini App boundary `5`, Venue RBAC `37`, Platform `10`, booking conversation `10`, booking
  notifier `8` and staff notifier `40`, all with zero skips/failures/errors;
- forced CI-property Telegram persistence coverage: long polling `6`, inbound queue `4`, webhook
  `6` and outbox `19`, all executed with zero skips/failures/errors;
- the standalone full `TelegramBotRouter*` suite: `604` tests, zero skips/failures/errors;
- nine real-Testcontainers PostgreSQL integrity/concurrency classes, including the directly ported
  staff-profile gate, plus the exact current-main 14-class H2/PostgreSQL migration-sanity matrix;
- the full backend suite under `TZ=UTC`: `2207` tests discovered, zero failures; its integration
  assumptions are covered by the separately forced PostgreSQL runs;
- Kotlin compile and ktlint;
- Mini App production build and full Playwright smoke: `216/216` passed;
- Compose validation, shell syntax, fixed-env staging admission, maintenance fail-closed,
  exact-image mismatch/order and pre-SSH ControlMaster self-tests;
- `git diff --check`, no conflict marker, temporary Compose env removal, and no tracked or untracked
  migration path.

A non-required oversized repeat that combined every MockK-heavy affected class into one JVM is not
counted: after its early classes passed, the Java instrumentation agent reported a JPLIS assertion.
Every class it did not reach then passed in bounded standalone commands matching the CI split. The
authoritative full-suite and required split gates above are green.

## Independent review

An independent final read-only security review inspected the complete frozen 50-path staged diff
and returned PASS with no P0, P1 or blocking P2. The reviewer independently verified admission and
identity ordering, OFF-mode PRODUCT behavior, existing-JWT rechecking, inbound/outbound unchanged-
denial semantics, direct and staff notification gates, main-only booking venue/chat authority,
tenant/RBAC preservation, autonomous-writer suppression, fail-closed startup/deploy controls,
restricted-value privacy, all 43 source mappings, immutable migration identities and nondecreasing
CI floors.

## Final verdict and successor boundary

The reviewed branch was integrated by non-force commit
`f837b0ed01f68832b305d5a2ed61b3927583f1e9`; the following exact-main CI correction produced
`9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1` and exact successful main Actions run `33514472076`.
HT-12M is therefore closed with final verdict
`IDENTITY_GATED_MAINTENANCE_PREREQUISITE_COMPLETE`.

That main SHA is the exact HT-12C base, not automatically the final V126 release SHA. HT-12C may
reconcile documentation and bounded static operational verification without changing production
code or migrations. It does not authorize a staging deploy, maintenance activation, Caddy reload,
backup, Flyway/V126 or cutover. After its reviewed feature branch and exact branch Actions are
green, its next gate is `HT12C_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`; only a later explicit
authorization may integrate it and begin final release-identity selection.
