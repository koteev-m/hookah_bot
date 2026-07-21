# Testing / QA Smoke Strategy

Дата актуализации: 2026-07-21.

Статус: **current product reference / UPDATED**. This document is the canonical QA/smoke strategy for the Telegram bot + Mini App platform. It consolidates local validation, GitHub Actions expectations, area-specific smoke suites, staging policy, failure reporting and Codex handoff rules. Deployment and incident operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`.

## Core Rule

Quality gates must match the blast radius of the change. Do not claim a feature is release-ready from local-only checks when it changes backend runtime, Mini App behavior, Telegram bot, staff-chat, billing/security or migrations. Do not run staging deploy for docs-only changes.

Current practice:
- CI is split into smaller backend jobs, Mini App build/e2e, compose and Docker image checks.
- Local broad Gradle wildcards can hit heap/runtime limits; prefer focused selectors first.
- Manual real Telegram/staff-chat smoke remains required for bot/staff-chat behavior changes.

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
- Advanced support and billing/provider features remain future unless implemented and smoked. Growth remains partial, but Post-Visit Feedback MVP is staging-smoke-passed and stays in regression.
- Menu shift check and per-venue `staff_stoplist_enabled` remain future.
- Staff-chat delivery history/personal notifications/topic routing remain future.
- CI coverage is strong for release-critical slices but not proof of every product scenario; area smoke checklists remain necessary.

## Roadmap Status

- Testing/QA smoke strategy: `UPDATED`.
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
