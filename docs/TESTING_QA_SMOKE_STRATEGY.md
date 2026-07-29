# Testing / QA Smoke Strategy

Дата актуализации: 2026-07-29.

Статус: **current product reference / UPDATED**. This document is the canonical QA/smoke strategy for the Telegram bot + Mini App platform. It consolidates local validation, GitHub Actions expectations, area-specific smoke suites, staging policy, failure reporting and Codex handoff rules. Deployment and incident operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`.

## Core Rule

Quality gates must match the blast radius of the change. Do not claim a feature is release-ready from local-only checks when it changes backend runtime, Mini App behavior, Telegram bot, staff-chat, billing/security or migrations. Do not run staging deploy for docs-only changes.

Current practice:
- CI is split into smaller backend jobs, Mini App build/e2e, compose and Docker image checks.
- Local broad Gradle wildcards can hit heap/runtime limits; prefer focused selectors first.
- Manual real Telegram/staff-chat smoke remains required for bot/staff-chat behavior changes.
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
- Venue Mini App Published Guest Preview Phase 1 is **MVP IMPLEMENTED / LOCAL VALIDATION PASSED**: focused backend parity/RBAC/availability tests preserve the exact Guest read contract. CI and staging smoke remain pending.
- Venue Mini App Draft Preview Phase 2.1 is **MVP IMPLEMENTED / LOCAL VALIDATION PASSED**: focused Draft/RBAC/Guest regression selectors, promotion repository regression, backend lint/compile, Mini App build and deterministic full e2e `92/92` passed locally. CI and staging smoke remain pending.

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

## Venue Mini App Guest Preview Phase 1 Quality Gate

Published Preview must continue to reuse the exact Guest DTO/read assembly and its availability
guards. A draft/bypass parameter in `GuestVenueReadService`, a private Venue settings DTO, or a
client-side merge of public/private state inside Published Preview is a release blocker. Draft
Preview Phase 2.1 uses a separate Venue API/read model and must not weaken this contract.

Required local coverage:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueGuestPreviewRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueRoutesTest*' --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Acceptance:

- OWNER and MANAGER receive the same guest-visible venue/info DTOs as Guest;
- STAFF and foreign venue access are forbidden, including direct route/hash;
- non-published or guest-blocked venues expose only the safe unavailable state;
- weekly hours, future date exceptions, public info/media, Today Staff and current active promotions match Guest;
- venue switching clears and aborts stale preview state;
- booking, favorites, venue chat, support, staff call, extension, cart, order and table context are absent and generate no mutation traffic;
- after green Actions, staging smoke must repeat OWNER/MANAGER/STAFF roles, unavailable venue copy, venue switching and visual parity with the real Guest card.

## Venue Mini App Draft Preview Phase 2.1 Quality Gate

Draft Preview is a separate OWNER/MANAGER-only Venue read path for already saved `DRAFT` state.
It must not use Guest API bypass flags, private settings DTOs or client-side public/private merges.

Required local coverage:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueDraftPreview*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueGuestPreviewRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueRbacRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Acceptance:

- OWNER and MANAGER receive the saved public-candidate projection only for their own `DRAFT` venue;
- STAFF and foreign venue users are forbidden; Platform Owner receives no automatic Venue-route access;
- missing/DELETED venues fail safely, and direct non-DRAFT requests return no private projection;
- response is read-only with `Cache-Control: no-store` and contains no mutation/action contract;
- public card/address, schedule, public exceptions, visible text info sections, guest-visible Today Staff and current `ACTIVE` promotions are allowlisted server-side;
- hidden info sections, private fields/markers, unpublished staff, inactive/non-current promotions and raw media refs are absent;
- Draft media uses one compact post-publication hint with no new media route; Published media remains unchanged and Guest media routes remain guarded;
- one global entry and one contextual public-card-settings entry open the same read-only renderer;
- PUBLISHED and DRAFT banners are explicit; HIDDEN/PAUSED/SUSPENDED/ARCHIVED expose only safe unavailable copy;
- venue switching clears old state immediately, aborts the previous request and ignores late responses;
- booking, favorites, chats, support, staff calls, extension, cart, order and table context neither appear nor mutate;
- after green Actions, staging smoke must repeat role/status isolation, both entrypoints, both banners, Draft allowlist/media hint, Published Guest parity and stale-response protection.

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
| Menu/stop-list | `docs/MENU_OPTIONS_STOPLIST.md` |
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

Menu/stop-list:
- Owner toggles item unavailable;
- guest cannot submit stale unavailable cart;
- unavailable option is blocked;
- Staff/Manager permissions match policy.

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
- lifecycle actions require reason/audit where implemented.

Telegram/staff-chat:
- `/start` without table;
- `/start <table_token>`;
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
- Platform Owner guest QR test escape remains open/needs verification.
- Booking reminders and future no-show automation remain rollout-gated/partial.
- Advanced support and billing/provider features remain future unless implemented and smoked. Growth remains partial, but Post-Visit Feedback MVP and venue-only Guest Favorites Phase 1 are staging-smoke-passed and stay in regression. Repeat Phase 1 is locally validated with deferred manual smoke in `REPEAT-MANUAL-001`; persistent templates, favorite menu items/options, recommendations/frequent items, notification opt-in, favorites-based promotions and loyalty remain future until their own bounded implementation evidence exists.
- Menu shift check and per-venue `staff_stoplist_enabled` remain future.
- Staff-chat delivery history/personal notifications/topic routing remain future.
- CI coverage is strong for release-critical slices but not proof of every product scenario; area smoke checklists remain necessary.

## Roadmap Status

- Testing/QA smoke strategy: `UPDATED`.
- Published Guest Preview Phase 1: **MVP IMPLEMENTED / LOCAL VALIDATION PASSED**; CI and staging smoke pending.
- Draft Preview Phase 2.1: **MVP IMPLEMENTED / LOCAL VALIDATION PASSED**; CI and staging smoke pending.
- Manual smoke checklist: `CONSOLIDATED`.
- CI coverage: `PARTIAL / release-critical split jobs current`.
- Frontend e2e: `PARTIAL`, with smoke coverage documented.
- Real Telegram smoke: `REQUIRED` for bot/staff-chat changes.
- Staging deploy smoke policy: `DOCUMENTED`.

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
