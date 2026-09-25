# AGENTS.md

Repo-level instructions for Codex tasks in this repository.

## Mandatory project identity gate

This section is the single authoritative Hookah project identity contract. Before
substantial work, reading project docs/source, delegation or writes, perform the
read-only preflight below. Reading instruction files/metadata needed for this gate
is allowed. Record the result and safe expected/observed identity in the task.

| Identity | Expected value |
| --- | --- |
| PROJECT_ID | `Hookah_Tootah` |
| REPO | `hookah_bot_ANT` |
| Ordinary TASK_ID prefix | `HT-` |
| Primary repository root | `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT` |
| Git common dir | `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT/.git` |
| Remote repository identity | `github.com/koteev-m/hookah_bot` |

1. Establish the current task/session identity and exact `TASK_ID`; an ordinary
   Hookah task must have the `HT-` prefix. Missing or ambiguous identity fails
   closed. The expected root is the primary root above unless the task explicitly
   supplies another existing Hookah worktree and its exact physical root before
   preflight. Such a named worktree must share the expected Hookah Git common dir;
   do not select or create an alternative root to make a failed gate pass.
2. Without changing cwd, use read-only Git commands with `GIT_OPTIONAL_LOCKS=0`
   (or `git --no-optional-locks`). Record `pwd -P`, physical cwd and the results of
   `git rev-parse --show-toplevel`, `git rev-parse --absolute-git-dir`,
   `git rev-parse --path-format=absolute --git-common-dir`,
   `git rev-parse --path-format=absolute --git-path index`, `git rev-parse HEAD`,
   branch/detached state and `git worktree list --porcelain`. Resolve symlinks,
   `.git` and `commondir` indirection to physical absolute paths. Require root
   equality with the task's expected root, cwd within that root, common-dir
   equality, and Git dir/index ownership by that registered checkout. Check
   identity-affecting `GIT_*` overrides, config includes, `core.worktree`,
   `core.hooksPath`, object-store alternates and path redirections. Unresolved
   ownership or foreign target/namespace/redirect fails closed; a neighbouring
   repository or stale registration alone is not proof of foreign authority.
   Verify local origin fetch/push identity without network or exposing credentials:
   HTTPS/SSH spelling and a `.git` suffix may differ, but host/owner/repository
   must match the table. `koteev-m/hookah_bot` is the expected remote identity
   of local `hookah_bot_ANT`, not a foreign project.
3. Establish origins of all applicable instructions: already inherited task/session
   instructions, global `AGENTS.override.md` / `AGENTS.md`, files along the physical
   directory chain, explicitly configured instruction files/fallback names, and
   narrower instructions before entering a subdirectory's scope. Inspect only
   the instruction metadata/content needed to establish authority. Repo-local
   authority must belong to Hookah; correct cwd does not cancel inherited foreign
   instructions. Do not load another project's instructions for application here.
   Missing or ambiguous origin of active instructions fails closed; the general
   rule for resolving minor ambiguity does not apply to project identity.
4. Check task-supplied `EXPECTED_HEAD`, `EXPECTED_BASE`, branch or parent constraints
   against locally verified commit OIDs (`git rev-parse --verify`). Supplied
   `EXPECTED_HEAD` requires exact equality with actual HEAD. Check base/parent/
   ancestry using precisely the relationship the task specifies; a supplied base
   without a relationship means exact starting HEAD. Missing local objects, drift
   or ambiguous relationships fail closed. For each unsupplied constraint record
   `NOT_SUPPLIED`; never infer an expected SHA from historical evidence or hard-code
   a HEAD. Do not fetch or reconstruct Git state to satisfy the gate.
5. Foreign active task/session identity, repo-local instruction authority, input
   authority, target root or Git common dir/dir/index/namespace requires
   `STOP_PROJECT_CONTEXT_CONTAMINATION`. Examples `clubs_bot`,
   `/IdeaProjects/clubs_bot` and `CLB-*` are negative-control values only, never
   project authority. Historical/foreign references are allowed only as isolated
   evidence explicitly within a bounded audit (including identity-guard validation
   in an authorized remediation); their mere text is not a failing active authority.
   They never authorize applying foreign instructions or executing foreign work.
6. On any mismatch, failed verification or unresolved identity/instruction origin,
   emit `STOP_PROJECT_CONTEXT_CONTAMINATION`, report the safe expected/observed
   identity and offending source, and stop substantial work. Never classify
   ambiguity in favour of continuation. Do not switch repository, create a branch
   or worktree to escape the mismatch, reset/repair Git state or config, or ignore
   already inherited foreign instructions because cwd is correct. No automatic
   continuation of Hookah or foreign work is permitted after STOP.
7. PASS permits only continuation of the currently authorized task. It grants no
   network, stage/commit/push, SSH, provider/server, deploy or other external-action
   permission. All existing task/platform restrictions still apply. Repeat the gate
   after task resumption/handoff, changes to cwd/instructions/identity, and before
   any separately authorized external action.

## Project

This is a Kotlin/Ktor Telegram bot + Telegram Mini App platform for hookah venues.
The bot, Mini App, backend routes, database and staff-chat integrations are one product.
Work outcome-first: preserve existing flows while delivering the smallest useful change.

## Instruction scope

System/platform constraints, tool contracts, approvals and mandatory Skill contracts remain
binding. Within those boundaries, the current explicit user task defines project intent and scope
and takes precedence over optional project recommendations when they conflict. Project instructions,
Skills, checklists and handoffs guide the task; they do not expand its scope or authorize mutations.
If an applicable Skill requires a pause, confirmation or changed direction, identify the exact
file/rule and explain the practical reason.

## Product Sources Of Truth

When product context is needed, use relevant sections of `docs/PRODUCT_SPEC.md` as the
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
1. Start with the current task and any task-specific checkpoint. Consult the current
   `PROJECT_STATUS.md` checkpoint when stage, blockers or evidence continuity matter; read older
   snapshots only for a concrete evidence gap. Keep concurrent tasks under distinct task IDs.
2. Read relevant code, canonical doc sections and applicable skills; expand only for a
   specific gap or risk. Do not copy the full history or runbook into each prompt.
3. Verify mutable claims against the task's actual Git state and applicable evidence.
4. Keep stable rules here, model policy in `docs/MODEL_WORKFLOW.md`, and detailed evidence
   in its existing artifacts. Read model policy when selecting/changing a model or its settings.
5. Update the checkpoint only when the product/release stage, blocker or next step changes.
   At such boundaries record task/goal; worktree/branch/base; state; decisions; checks/results and
   evidence links; unfinished operations; authorization scope; blocker and next step. Use short
   facts, no full logs, secrets or copied history. Routine instruction/docs edits need no checkpoint.

The file checkpoint survives model changes and native notes. ChatGPT maintains the agreed
Google decision/continuation journal; Codex maintains authorized repo docs and checkpoints.
Do not claim a journal update without an actual authorized write. Files and command results
remain implementation evidence.

## Workflow

- For small reversible ambiguity, make a reasonable assumption and continue. Ask when uncertainty
  materially changes the result, safety, an irreversible action, a product/architecture contract,
  or authorization for an external or mutating action. Mandatory constraints still bind.
- Plan when complexity, risk, architecture, dependent stages or unclear scope make it useful.
  File count alone does not require a plan. Prescribe exact steps where sequence is part of safety
  or an external contract.
- Inspect affected docs/code/tests before editing; routes and UI only when relevant.
- Keep diffs small and cohesive.
- Prefer existing local patterns over new abstractions.
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
- Do not automatically add agents, subagents, worktrees, Goal mode or audit passes. Use them only
  when permitted and when parallel work, independent review or isolation materially helps.
  Read model/effort policy in `docs/MODEL_WORKFLOW.md`; quality/safety rules apply to every model.

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
- After a separately authorized push, verify that the exact remote branch SHA equals the published
  commit, then STOP. If exact identity cannot be confirmed, report it as unverified and stop.
- Codex does not wait for or monitor GitHub Actions after push: no `gh run watch`, polling loops,
  repeated `gh run view`, workflow waiting or delegated CI monitoring. The user/ChatGPT tracks CI.
  A terminal red run gets a separate bounded Codex diagnosis task. Workflow rerun, cancel or
  dispatch requires separate explicit authorization.
- Required Actions must be green for the exact release SHA before release/deploy; a later authorized
  release task consumes that completed evidence. This gate is distinct from CI monitoring.
  Staging smoke is required when runtime behavior changes.
- Do not claim production readiness from local-only checks.

## Final Response Checklist

Every task outcome needs a useful report, including read-only work, BLOCKED, FAIL, DIVERGED,
NOT_PROVABLE and stopping before implementation. Never return only a verdict. Keep the report
proportionate to the task; do not run extra checks just to fill it. For simple docs-only work,
combine non-applicable staging and `scripts/dev/` status in one line. Include:

- what was completed and the exact success boundary or first failure;
- expected versus actual results when they differ;
- tests or validation commands, results and evidence sources;
- changed files, behavior/docs summary and actual side effects (or no changes);
- unverified items and open risks/follow-ups;
- whether staging deploy is needed;
- whether `scripts/dev/` was touched;
- observed `git status --short` (or explicitly unavailable);
- one next step.
