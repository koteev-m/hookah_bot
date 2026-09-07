# Model workflow

Project policy for ChatGPT/Codex; common rules remain in [AGENTS.md](../AGENTS.md).
Continuation uses [PROJECT_STATUS.md](../PROJECT_STATUS.md), not a second memory system.

## Active policy: ASTRA_CODE_PRIORITY

This is a workflow name, not a Codex/API setting. The user currently prefers Astra for
writing code as well as design and review. Model selection is a recommendation for the
executor; a prompt does not switch models, attach tools or grant permission.

| Task | Preferred executor |
| --- | --- |
| New features, backend/frontend, fixes, refactoring, substantive tests | GPT-6 Astra (`gpt-6-astra`), medium |
| Complex cross-module work, transactions, concurrency, security, migrations, difficult diagnosis | Astra; raise effort only with a task-based reason and client support |
| Substantive review | Astra; Sol is a full alternative |
| Mechanical docs, exact Git integration of already verified code, CI monitoring and similar bounded work | GPT-5.6 Terra (`gpt-5.6-terra`), medium |

GPT-5.6 Sol (`gpt-5.6-sol`) remains a full alternative when Astra is unavailable, lower
consumption is needed, or another concrete benefit justifies it. Choose supported effort
for the task; do not require a cheaper-model pass before a coding task. No automatic
Max/Ultra, subagents or additional audit passes.

The user's reported reserve of usage resets is a temporary reason for this policy, not an
unlimited resource. Do not invent remaining resets/expiry, persist a count as evergreen,
redeem resets or purchase credits automatically. Actual availability failure calls for a
replacement proposal with the reason; it does not silently change the project policy.

`BALANCED_COST` is inactive: Terra for bounded tasks, Sol for substantial implementation,
Astra for the hardest/highest-risk work. Activate it only after an explicit user decision
to return to cost saving. Quality and safety criteria are identical in both policies.

## Model and client boundaries

Choose the ChatGPT prompt-author model and the Codex execution model independently.
Check model, client version and sign-in method before using model-specific parameters or
experimental capabilities. Astra advice does not automatically apply to Sol/Terra; mark
unverified support as unknown. Do not change global config, model catalog, account,
other projects or running tasks as a consequence of this policy.

At a model handoff, verify the existing checkpoint and still-applicable evidence, then
continue at the recorded boundary. A model change does not renew authorization or require
repeating all work. Do not switch an executing mutation/rollback operation. Native notes
never replace the shared file checkpoint.

## Verified references and local support snapshot — 2026-09-07

These are dated observations, not permanent account guarantees:

- [Official models](https://learn.chatgpt.com/docs/models) documents Astra, Sol and Terra,
  model/effort selection and Ultra's use of subagents. The task allocation above is the
  user's project policy, not an OpenAI requirement or a measured cost comparison.
- [AGENTS discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md) loads
  global guidance and the project-root-to-working-directory chain, preferring
  `AGENTS.override.md` over `AGENTS.md` per directory. Guidance is assembled at run start;
  an unintegrated worktree draft does not update another worktree or a running task.
- [Configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference)
  documents boolean `features.context_management.experimental_mode`, off by default,
  using notes/searchable history; it requires ChatGPT sign-in on Plus, Pro or Pro Lite.
- [Astra API guidance](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6-astra)
  describes Astra-specific behavior and API capabilities; Astra does not support `none`
  reasoning. API guidance alone does not establish a desktop feature's availability.
- Read-only local inspection: bundled `codex-cli 0.153.4`; `codex features list` reports
  `context_management` as `under development / false`, and `multi_agent` as `stable / true`.
  An available feature is not an instruction to use it. No custom agent role config files
  or project `.codex/config.toml` were found in the inspected scope. The global default is
  `gpt-6-astra` / `high`; the cached catalog defaults are Astra medium, Sol low, Terra medium.
  A config default is not proof of a running task's selection. Policy medium does not
  overwrite that global high setting.
- During inspection, `models_cache.json` changed (its `fetched_at` advanced); the selected
  three models' defaults/effort lists stayed the same. No explicit catalog write was issued;
  the refresh cause is unproven. Do not claim byte preservation or restore that shared cache.
- The inspected global config has no context-management entry. The CLI flag listing
  confirms only that feature name/state; acceptance of the exact nested experimental key,
  effective desktop support, account eligibility/sign-in and per-model behavior remain
  unverified. No enable/disable, config write or paid trial was performed. Inspect these
  prerequisites before any separately authorized experiment; do not infer support from
  native notes or the model name. No measured token savings are claimed.
