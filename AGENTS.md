# AGENTS.md

Repo-level instructions for Codex tasks in this repository.

## Project

This is a Kotlin/Ktor Telegram bot + Telegram Mini App platform for hookah venues.
The bot, Mini App, backend routes, database and staff-chat integrations are one product.
Work outcome-first: preserve existing flows while delivering the smallest useful change.

## Product Sources Of Truth

After the task checkpoint, use the relevant sections of `docs/PRODUCT_SPEC.md` as the
product map, then read the smallest relevant canonical docs (this list is an index):

- `docs/COMMUNICATION_MODEL.md`
- `docs/PLATFORM_COCKPIT.md`
- `docs/GROWTH_RETENTION.md`
- `docs/ORDER_SESSION_TAB_CORE.md`
- `docs/ANALYTICS_EVENTS.md`
- `docs/SECURITY_RBAC_MATRIX.md`
- `docs/MENU_OPTIONS_STOPLIST.md`
- `docs/VENUE_OPERATIONS.md`
- `docs/BOOKING_LIFECYCLE.md`
- `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`
- `docs/TESTING_QA_SMOKE_STRATEGY.md`
- `docs/DEPLOYMENT_RUNBOOK.md`

If docs conflict, follow the canonical doc for that area and record the conflict as a
follow-up. Do not mark `PARTIAL`, `FUTURE` or `needs verification` items as done
without code/test/smoke evidence.

## Context loading

At the start of a new task:
1. Read the task's checkpoint at the top of `PROJECT_STATUS.md`; read older snapshots only
   for a concrete evidence gap. Keep concurrent tasks under distinct task IDs.
2. Read relevant code, canonical doc sections and applicable skills; expand only for a
   specific gap or risk. Do not copy the full history or runbook into each prompt.
3. Verify mutable claims against the task's actual Git state and applicable evidence.
4. Keep stable rules here, model policy in `docs/MODEL_WORKFLOW.md`, and detailed evidence
   in its existing artifacts. Read model policy when selecting/changing a model or its settings.
5. Update the checkpoint at meaningful boundaries: task/goal; worktree/branch/base; state;
   decisions; checks/results and evidence links; unfinished operations; authorization scope;
   blocker and next step. Use short facts, no full logs, secrets or copied history.

The file checkpoint survives model changes and native notes. ChatGPT maintains the agreed
Google decision/continuation journal; Codex maintains authorized repo docs and checkpoints.
Do not claim a journal update without an actual authorized write. Files and command results
remain implementation evidence.

## Workflow

- Define outcome, necessary sources, autonomy boundaries, acceptance criteria and useful report.
  Prescribe exact steps where sequence is part of safety or an external contract.
- Inspect affected docs/code/tests before editing; routes and UI only when relevant.
- Keep diffs small and cohesive.
- Prefer existing local patterns over new abstractions.
- For multi-file, architectural, risky or ambiguous work, plan first.
- For behavior changes, update tests and the smallest relevant docs surface.
- Runtime behavior changes require validation matching `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Docs-only changes require docs sanity checks, not staging deploy.
- Do not deploy, push, SSH or stage/commit unless explicitly asked.
- Handoff/checklist commands describe the applicable process, not permission to execute it.
  Bound authorization by purpose, environment, allowed actions and risks; do not invent a
  universal session-count limit. Existing operation/environment limits still apply.
- Classify a failure before proposing development: product defect, environment failure,
  diagnostic-check error, missing evidence, or instruction/actual-contract mismatch.
  One FAIL/NOT_PROVABLE is not a diagnosis. Locate the failed boundary using available
  evidence; record unknown causes as unknown.
- Continue after clarification or a recoverable local blocker when execution state and the
  contract permit. A new chat, Goal, worktree or run needs a concrete reason, not just a FAIL.
  Terminal release states, fresh-evidence gates and consumed one-time permissions still bind.
- No automatic Max/Ultra, extra agents or repeated audits. Read the current model selection
  policy in `docs/MODEL_WORKFLOW.md`; quality/safety rules apply to every model.

## Review evidence

Tie findings to an explicit commit/branch and environment. Distinguish concept, feature/main
code, deployed runtime and migration state. Missing deployed V126 does not by itself invalidate
a finding; a fix in main does not prove server behavior. Historical audits are not fresh reviews,
and static analysis is not an executed runtime test. Request missing reviewer scope/evidence
before a conclusion that depends on it; continue independent authorized work. A review request
does not authorize fixing every finding or changing the product concept.

## Product Boundaries

Preserve these boundaries unless a task explicitly changes them and updates docs/tests:

- `BOOKING_CHAT` is booking-specific and is not support.
- `VENUE_CHAT` is a normal guest-to-venue conversation and is not support.
- `SUPPORT_TICKET` is a problem/ticket flow; Staff does not manage support in MVP.
- `STAFF_CALL` is an urgent operational table request and is separate from support.
- Staff-chat is notification/radar/shortcut only, not source of truth.
- Venue Mode is source of truth for venue operations.
- Platform Mode is for platform lifecycle, billing, support center and platform analytics.
- Order/session/tab logic must preserve `table_session`, `order_batch`, `tab` and RBAC boundaries.
- QR/table tokens and tab invite tokens are context pointers, not authority.
- Server-side RBAC is source of truth; UI hiding is convenience only.

## User-facing guidance

For user-facing work:
- explain non-obvious states, prerequisites, restrictions and consequences;
- destructive actions must describe important side effects before confirmation;
- blocked actions must state what happened, a safe reason and the next action;
- do not rely only on color, hidden controls or generic error text;
- add guidance for complex settings, empty states and cross-feature dependencies;
- avoid tooltips or explanatory copy for obvious actions;
- cover critical guidance and denial copy with tests.

## Coding Rules

- Kotlin: idiomatic, null-safe, focused changes.
- TypeScript/Mini App: follow existing screen/API patterns and Russian UX copy.
- Keep package structure intact.
- Add comments only where logic is not self-explanatory.
- Do not expose secrets, `.env`, raw Telegram payloads, provider data or unrelated PII.
- Do not touch `scripts/dev/` unless the user explicitly asks.

## Git Rules

- Never use `git add .`.
- Stage explicit files only when asked to stage.
- Do not revert user changes unless explicitly requested.
- `scripts/dev/` is an untracked local helper area and must not be staged accidentally.

## Validation

Always choose the smallest relevant checks first, then broader checks as needed.
Reuse applicable checks and unchanged artifacts after verifying identity (commit/tree or
content hash, inputs and environment), scope and freshness. Rerun when changes, failures or
unresolved risks justify it, or the canonical gate requires fresh evidence. This never waives
mandatory CI/release checks, PRODUCT admission, RBAC, tenant isolation, data protection or
dangerous-action approval. Docs-only work needs docs sanity, not a full suite or release build.

General:

```bash
git status --short
git diff --check
```

Backend examples:

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

If Gradle OOM occurs, split selectors by concrete class and use
`_JAVA_OPTIONS=-Xmx4g --max-workers=1` where appropriate.

## Release / Deploy

Use `docs/DEPLOYMENT_RUNBOOK.md` for release model, staging deploy, rollback,
environment, logs, incident response and Codex/ChatGPT handoff.

- Docs-only: no staging deploy.
- Runtime/backend/Mini App/Telegram/staff-chat/billing/migration changes: wait for
  green Actions before release; staging smoke is required when behavior changes.
- Do not claim production readiness from local-only checks.

## Final Response Checklist

Every task outcome needs a useful report, including read-only work, BLOCKED, FAIL, DIVERGED,
NOT_PROVABLE and stopping before implementation. Never return only a verdict. Keep the report
proportionate to the task; do not run extra checks just to fill it. Include:

- what was completed and the exact success boundary or first failure;
- expected versus actual results when they differ;
- tests or validation commands, results and evidence sources;
- changed files, behavior/docs summary and actual side effects (or no changes);
- unverified items and open risks/follow-ups;
- whether staging deploy is needed;
- whether `scripts/dev/` was touched;
- observed `git status --short` (or explicitly unavailable);
- one next step.
