# Repository guide for Cursor

## Goal
Maintain this Kotlin Telegram bot with minimal diffs and without breaking current behavior.

## Workflow
- For multi-file, architectural, or risky tasks: use Plan Mode first
- For local fixes: edit directly
- Inspect related handlers, services, config, and tests before editing
- Bot and Mini App are clients of the same backend. For every core product feature, verify required cross-surface parity. Do not mark a feature complete until required Bot/Mini App surfaces are implemented or an intentional exception is documented in the parity roadmap.

## Coding rules
- Idiomatic Kotlin
- Null-safe code
- Small focused changes
- Keep package structure intact
- Update tests for behavior changes
- Add KDoc only for public API or non-obvious logic

## Verification
- Prefer existing Gradle tasks from the repo
- Run the smallest relevant checks first, then broader ones

## Sensitive data
- Never expose bot tokens, .env files, local.properties, or secrets
