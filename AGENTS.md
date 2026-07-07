# AGENTS.md

Repo-level instructions for Codex tasks in this repository.

## Project

This is a Kotlin/Ktor Telegram bot + Telegram Mini App platform for hookah venues.
The bot, Mini App, backend routes, database and staff-chat integrations are one product.
Work outcome-first: preserve existing flows while delivering the smallest useful change.

## Product Sources Of Truth

Start from `docs/PRODUCT_SPEC.md`, then read the smallest relevant canonical docs:

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

## Workflow

- Inspect relevant docs, routes, repositories, UI screens and tests before editing.
- Keep diffs small and cohesive.
- Prefer existing local patterns over new abstractions.
- For multi-file, architectural, risky or ambiguous work, plan first.
- For behavior changes, update tests and the smallest relevant docs surface.
- Runtime behavior changes require validation matching `docs/TESTING_QA_SMOKE_STRATEGY.md`.
- Docs-only changes require docs sanity checks, not staging deploy.
- Do not deploy, push, SSH or stage/commit unless explicitly asked.

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

End implementation/docs tasks with:

- changed files;
- behavior/docs summary;
- tests or validation commands and results;
- open risks/follow-ups;
- whether staging deploy is needed;
- whether `scripts/dev/` was touched;
- `git status --short`.
