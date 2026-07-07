# Deployment / Runbook / Operations

Дата актуализации: 2026-07-07.

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
7. Push:
   ```bash
   git push origin main
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

Use this only after GitHub Actions are green for runtime changes, unless explicitly doing a debug deploy:

```bash
STAGING_PATH=/opt/hookah-bot \
STAGING_DOMAIN=staging.hookahtootah.club \
DOCKER_PLATFORM=linux/amd64 \
BACKEND_IMAGE=hookah_bot_ant-backend:staging \
./scripts/deploy-staging-controlmaster.sh hookah-staging
```

Notes:
- Standard deploy is still documented in `docs/STAGING_DEPLOYMENT.md`.
- The ControlMaster command is the current preferred reliability path when fresh SSH connections are unstable.
- The script behavior is mostly documented in `docs/STAGING_DEPLOYMENT.md`; exact rollback behavior remains **needs verification**.
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

## Migrations Runbook

Current practice:
- PostgreSQL and H2 migrations both exist.
- Flyway migration policy is documented in `docs/MIGRATION_POLICY.md`.
- Staging likely applies migrations during backend startup/deploy; exact startup sequence should be verified from current deploy logs when a migration release is prepared.

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
- If backup/restore commands are unknown for the environment, mark rollback as **RUNBOOK GAP** before release.

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
- exact database backup/restore commands;
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
- Staging deploy policy: `DOCUMENTED`.
- Rollback policy: `PARTIAL`; exact previous-image/DB restore commands need verification.
- Operations troubleshooting: `PARTIAL`; exact production log and provider diagnostic commands need verification.
- Production release process: `FUTURE / PARTIAL` until production deploy, backups and rollback are confirmed.
- `scripts/dev/` policy: `DOCUMENTED`.
