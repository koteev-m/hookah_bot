# Model workflow

Project policy for ChatGPT/Codex; common scope, quality, safety, Git, evidence and approval rules
remain in [AGENTS.md](../AGENTS.md) and apply equally to all models.

## Task-based model selection

Choose for the task's actual complexity, uncertainty, risk and expected total cost including
rework. These are starting points, not rigid routing or an OpenAI requirement.

| Model | Useful starting point |
| --- | --- |
| GPT-5.6 Terra (`gpt-5.6-terra`) | Bounded, routine or mechanical work; simple known docs/code changes and low risk. |
| GPT-5.6 Sol (`gpt-5.6-sol`) | Substantive coding, review, analysis/research and ordinary complex engineering when its capability is sufficient. |
| GPT-6 Astra (`gpt-6-astra`) | Hardest or highest-risk end-to-end work: difficult diagnosis, security, concurrency, migrations and unclear cross-module problems where added capability justifies cost. |

Do not choose a more expensive model automatically; a code task alone does not require Astra.
Do not switch a task that is progressing successfully merely to fit the table. Choose the ChatGPT
prompt-authoring model and Codex executor independently, and follow an explicit user model choice.
A model change does not renew or expand authorization. Do not change global model configuration,
the account, other projects or running tasks as a consequence of this policy.

No model is assigned CI watching. Publication follows the shared rule in `AGENTS.md`:
authorized push -> verify exact remote SHA -> STOP.

## Reasoning effort

Choose a supported effort for the current task. Use lower effort for routine bounded work and raise
it only when complexity, interacting constraints or material risk warrants it. `high`, `xhigh` and
`max` are not automatic project defaults. Preserve a working effort unless the task gives a reason
to change it; this document does not modify any global default.

## Model and client boundaries

Check model, client version and sign-in method before using model-specific parameters or
experimental capabilities. Astra advice does not automatically apply to Sol/Terra; mark
unverified support as unknown.

At a model handoff, verify the existing checkpoint and still-applicable evidence, then
continue at the recorded boundary. A model change does not renew authorization or require
repeating all work. Do not switch an executing mutation/rollback operation. Native notes
never replace the shared file checkpoint.

## Official basis — checked 2026-09-13

- [Model guidance](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-6-astra)
- [Models and selection](https://developers.openai.com/api/docs/models)
- [Rethinking skills and prompts for GPT-6 Astra](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)
- [Codex AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Codex Skills](https://learn.chatgpt.com/docs/build-skills)
- [Codex configuration](https://learn.chatgpt.com/docs/config-file/config-basic)

Prompt snippets in guidance are examples, not extra mandatory project workflows.
