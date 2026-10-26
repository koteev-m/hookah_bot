# HT-VENUE-OWNER-UX02 local validation

## Final browser verification — HT-VENUE-OWNER-UX02-FINAL-BROWSER-VERIFICATION (2026-10-26)

**HT_VENUE_OWNER_UX02_FINAL_BROWSER_PASS** — local fixture-backed Chromium verification.
The independent rerun blocker is resolved and the missing live access/draft regressions now execute.
No P0–P2 implementation defect was confirmed in the exercised scenarios. This is not a new code
review, a release approval, or evidence of staging/real Telegram behavior. Earlier sections remain
historical evidence; this section records only this task's fresh results.

### Identity and preserved starting state

Identity gate **PASS** before project source reads or writes. Expected and observed:
PROJECT `Hookah_Tootah`, repository `hookah_bot_ANT`, task ID as above, physical cwd/toplevel
`/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`, branch
`codex/ht-venue-owner-ux02`, HEAD `6f07c450ba22d7782a592fc71612eb47840d1c60`.
EXPECTED_BASE and parent constraints: **NOT_SUPPLIED**. Git common dir:
`/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT/.git`; Git dir/index belong to its registered
`worktrees/ht-venue-owner-ux02` checkout. Physical paths, `.git`/`commondir`/`gitdir` ownership and
local commit/branch OIDs matched. Origin fetch/push identity: `github.com/koteev-m/hookah_bot`.
No identity-affecting overrides, includes, hooks/worktree redirection or object alternates; only
`GIT_PAGER=cat`. Instructions: platform/session, explicit Hookah task/supplied AGENTS, empty global
AGENTS.md, matching worktree AGENTS.md; no additional ancestor/narrower/configured instruction files.
Old prunable registrations were not used or changed. No fetch or Git-state repair was performed.

Starting and final `git status --short` are identical (8 modified + 2 untracked; nothing staged):

```text
 M PROJECT_STATUS.md
 M docs/TESTING_QA_SMOKE_STRATEGY.md
 M docs/VENUE_OPERATIONS.md
 M miniapp/e2e/guest-smoke.spec.ts
 M miniapp/src/screens/venueApp.ts
 M miniapp/src/screens/venueDashboard.ts
 M miniapp/src/screens/venueSettings.ts
 M miniapp/src/style.css
?? docs/HT_VENUE_OWNER_UX02_LOCAL_VALIDATION.md
?? miniapp/src/screens/venueSettingsCatalog.ts
```

Only `miniapp/e2e/guest-smoke.spec.ts` and this report changed during this task; temporary diagnostic
artifacts are under `.tmp-checks/ht-venue-owner-ux02/final-browser/`. The original test file is a
byte-identical prefix of the final file, and the original report body is retained. Starting hashes
verify the other eight files unchanged, including every pre-existing production source/style change.
No production frontend/backend, API, DB, RBAC, security config, `scripts/dev/`, primary checkout or
other worktree edits. No stage/commit/push/PR/merge/reset/clean/stash/SSH/deploy or real credentials.
The checkpoint and canonical docs remain unchanged under this task's restricted write scope.

### Fresh browser behavior

Three new ordinary Playwright tests exercise the application UI, mocked HTTP auth/access responses,
native same-document navigation and the existing **«Обновить доступ»** control. They do not call
internal draft helpers. Navigation API is hidden before boot to exercise the retained-editor fallback.
Every case first proves an unchanged-access refresh preserves the draft, then changes access and
restores it. A single document navigation request, original Document identity and original connected
`.venue-shell` element prove there was no reload or app remount. Each case records zero venue API
mutation requests. A second legitimate OWNER membership permits UI access refresh after losing the
first venue's role; it does not authorize editing that first venue.

| New regression | Executed result |
| --- | --- |
| Live user A → B → A | A edits the card; the fixture changes both live Telegram user ID and initData. The real auth client obtains a distinct synthetic B token, verified on `/api/venue/me`. After access refresh, B sees the saved empty value, not A's draft. B then edits; returning to A through refresh shows neither old draft. |
| Role OWNER → STAFF → OWNER | After refreshed access, the card route shows «Недостаточно прав» and the editor/draft notice is unavailable. Restoring OWNER opens the saved empty field, not the previous draft. |
| Permission revoked → restored | Removing `SHIFT_EXTENSION_SETTINGS` while retaining OWNER denies the extension form. Restoring the permission opens the saved empty price; the previous `2500` draft does not return. |

All **32 existing UX02 tests** passed again, including two allowed venues with OWNER/MANAGER
contexts, independent retained values while the second venue stays selected, cancelled exit,
confirmed discard, successful explicit save, failed save retention, pending offscreen save,
all five settings sections and zero automatic/duplicate writes. The existing full-reload test remains
a separate memory-loss check; it is not used as evidence for live user isolation.

### Commands and outcomes

Commands ran from the task worktree. Log pipelines used `set -o pipefail`; no assertions were
weakened, tests disabled, expected failures introduced or retries enabled.

| Command | Exit | Outcome |
| --- | --- | --- |
| `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --grep 'owner UX02' --workers=1 --output=../.tmp-checks/ht-venue-owner-ux02/final-browser/initial-results` (sandbox) | 1 | Vite `listen EPERM 127.0.0.1:5174`; no tests executed. Environment failure, not a product diagnosis. |
| Same exact test command, approved execution | 0 | 32 passed / 0 failed / 0 skipped / 0 flaky, 13.4s. |
| `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --grep 'owner UX02' --workers=1 --trace=on --output=../.tmp-checks/ht-venue-owner-ux02/final-browser/focused-results` | 0 | 35 passed / 0 failed / 0 skipped / 0 flaky, 17.5s; all 35 traces retained. |
| `PLAYWRIGHT_JSON_OUTPUT_NAME=../.tmp-checks/ht-venue-owner-ux02/final-browser/full-report.json MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --reporter=list,json --output=../.tmp-checks/ht-venue-owner-ux02/final-browser/full-results` | 0 | 323 passed / 0 failed / 0 skipped / 0 flaky, 150.748s; zero retries and no runner errors. |
| `miniapp/node_modules/.bin/tsc --noEmit -p miniapp/tsconfig.json` | 0 | PASS; exact exit recorded in `typescript-result.json`. |
| `git --no-optional-locks diff --check` | 0 | PASS. |
| `git --no-optional-locks status --short`; `git --no-optional-locks rev-parse HEAD` | 0 each | Status above; HEAD unchanged. |
| `lsof -nP -iTCP:5174 -sTCP:LISTEN` | 1 | Empty output: no remaining local listener after completion. |

EPERM was resolved by command-scoped `require_escalated` approvals for the concrete Playwright
invocations. No session-wide permissions, security configuration, server-bind workaround or external
service was used. Production build was not rerun in this browser/test-only task; earlier build
evidence remains historical and is not presented as a fresh result.

Evidence directory: `.tmp-checks/ht-venue-owner-ux02/final-browser/`:
`identity-gate.json`, `start.json`, `start-status.txt`, `initial-focused.log` (EPERM),
`initial-approved-focused.log`, `focused.log`, `focused-results/*/trace.zip`,
`live-{user,role,permissions}-evidence.json`, six `focused-results/*/live-*.png` screenshots,
`full.log`, `full-report.json`, `full-results/`, `typescript-final.log`, `typescript-result.json`,
`diff-check.log`, `final-status.txt`, `validation.json`.
Role denial, permission denial/restoration and restored-user empty-card screenshots were visually
inspected. JSON attachments include synthetic auth identity, returned access scopes, no-write counts
and document/app-continuity evidence. All credentials and user IDs in these artifacts are fixtures.

### Exact limits and next step

The supported tested transition is **new authenticated/access state received through explicit UI
access refresh in the same running app**. `loadAccess` has no automatic subscription to host account
changes or server role revocations. Instant invalidation before a fresh access response, a real
Telegram account switch, and a host account-change notification are **NOT_VERIFIED**. The fixture
changes the live Telegram object; it does not prove that an actual Telegram client emits this change.
The new cases cover detached settings drafts, not concurrent revocation during an in-flight save.

Real Telegram WebView (iPhone/Android), Firefox/WebKit, staging/production and real backend/RBAC
enforcement remain **NOT_VERIFIED**. No external environment was contacted. No staging deploy is
needed for these test/report edits; the underlying UX02 runtime work still requires separately
authorized staging and real Telegram smoke before release. No unfinished test/server operation.
Next: review this local verification evidence and separately decide the next release-validation step;
this PASS grants no publication permission.

## History compatibility — HT-VENUE-OWNER-UX02-HISTORY-COMPAT (2026-10-26)

This section supersedes the earlier HISTORY-HARDEN blocker and the narrower P2-fixed claims below.
The task explicitly authorizes a compatibility contract: preserve drafts and follow the actual URL
when cancellation is unavailable, instead of forcing restoration of an unknowable history position.

### Identity, authority and preserved work

Identity gate PASS. Expected and observed PROJECT_ID `Hookah_Tootah`, repository `hookah_bot_ANT`,
TASK_ID `HT-VENUE-OWNER-UX02-HISTORY-COMPAT`, physical cwd/root
`/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`, branch
`codex/ht-venue-owner-ux02`, exact HEAD `6f07c450ba22d7782a592fc71612eb47840d1c60`.
EXPECTED_BASE and parent constraint: `NOT_SUPPLIED`. Common dir
`/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT/.git`; checkout Git dir/index belong to
`worktrees/ht-venue-owner-ux02`, with physical `.git`/`commondir`/`gitdir` indirection and registered
worktree ownership verified. Origin fetch/push normalize to `github.com/koteev-m/hookah_bot`.
No identity-affecting environment/config redirection, includes, alternate object store or hooks path;
only `GIT_PAGER` set. Authority: platform/session and explicit Hookah task, empty global AGENTS.md,
matching worktree AGENTS.md; no other ancestor/narrower/configured instruction files found.

The initial 8 modified + 2 untracked UX02 files are preserved. Start hashes are in
`.tmp-checks/ht-venue-owner-ux02/compat-start.json`. This task changes only `venueApp.ts`,
`venueSettings.ts`, `guest-smoke.spec.ts`, the two canonical docs, this report and the checkpoint.
Dashboard, catalog and CSS remain byte-identical to task start. No backend/API/DB/RBAC/Bot,
billing/lifecycle/archive, main/other checkout or `scripts/dev/` changes. No Git metadata writes,
fetch, stage, commit, push, PR, merge, SSH or deploy.

### Implemented behavior

- Navigation API: listen to `navigate`, use `preventDefault()` only for same-document events with
  `event.cancelable === true`, then observe committed entries. `canIntercept` is not a cancellation
  gate; no interception, `traverseTo`, inferred index or restoration operation is required.
  Browser-owned entry keys/entries are inspected in tests to prove cancellation leaves them intact.
- Top navigation, hash links, Telegram BackButton and venue selection check the existing form guard
  before changing URL. Dirty forms offer stay/save or explicit confirmation of discard; pending
  explicit writes explain that the user must wait. Preview retains its save-first contract.
- No Navigation API, or noncancelable NavigateEvent: browser traversal proceeds. Popstate/hashchange
  synchronize the route, selected venue and screen from the actual URL. Duplicate notifications
  for the same rendered URL do not remount. No compensation `history.go(delta)`, invented index,
  overwrite of destination URL, extra history entry or outstanding restoration state.
- Dirty/pending settings editors stay in an app-instance map, detached from the visible DOM.
  The key contains user ID, exact venue ID, role, permission scope and settings section. Keeping the
  editor preserves raw/invalid/whitespace text, selected location metadata, weekday inputs and
  date-exception state together; returning does not refetch/reset the draft. Access changes evict
  incompatible entries; app disposal releases all editors and handlers.
- Successful explicit saves clear the corresponding dirty state. Confirmed discard disposes the
  departing editor. An already submitted explicit save can finish while detached; a clean result
  releases the cached editor, while a failed save retains its draft. Other forms' edits stay intact.
  The visible notice explains memory-only retention and offers return links for the selected venue.
  No new persistence in localStorage/sessionStorage/server and no autosave.
- A single app-level beforeunload handler covers visible and detached drafts. It remains a native,
  best-effort document-exit warning; closing/reloading a WebView can lose memory. The notice states
  this limit explicitly. Fresh document load does not restore an in-memory draft.

### Browser evidence and the two P2 fixes

Executed Chromium **148.0.7778.96** (installed Playwright chromium-1223). Available-browser inventory:
Firefox-1522 and WebKit-2287 executables absent; neither engine was installed or claimed tested.
Fallback is exercised by hiding `window.navigation` before app boot in actual Chromium, with native
History API operations/events. This validates the legacy code path, not a real old Telegram WebView.

| Scenario | Observed result |
| --- | --- |
| Script-initiated Back and hash navigation, Navigation API | `cancelable=true`; staying prevents commit; URL/current key/entry list unchanged. |
| Two native script Back calls on unindexed history | Both cancelled without compensation; draft remains; subsequent confirmed exit/Forward works. |
| Browser-UI Back twice via Chromium `Page.navigateToHistoryEntry` | First event `{cancelable:true, canIntercept:true}` is cancelled. Repeated Back without a fresh gesture emits `{cancelable:false, canIntercept:true}`; URL/screen move, draft survives return. |
| Missing/invalid legacy history index, fallback | Back reaches Settings, state is untouched; Forward restores exact text. No guessed direction. |
| Overlapping Back and immediate `back(); back()`, fallback | Browser reaches Home, visible screen agrees; compensation trap receives zero calls; return/save/later navigation work. |
| Native multi-entry Back and unknown Forward destination | Correct destination screen and intact legacy state; return restores text; repeated navigation remains usable. |

**P2 unknown-entry compensation eliminated:** router never derives a position from absent/invalid
state. **P2 rapid-Back recovery hang eliminated:** there is no restoration state to become stuck.
The five formerly failing tests were neither removed nor disabled; all six original hardening cases
now exercise the agreed fallback. Expectations changed from forced cancellation to destination
URL/screen agreement plus draft recovery, explicit save and intact subsequent history.

Fourteen additional regressions cover both API modes, all five settings sections, multiple repeated
Back/Forward cycles, no draft-return refetch, exactly one explicit write, explicit discard,
second-venue-kept-selected navigation, OWNER/MANAGER isolation, raw text/review/date exceptions,
pending offscreen save completion, save failure and memory loss after full reload. Existing direct
links, four tabs, saved preview and Telegram tests remain. The 390px draft screenshot was visually
inspected: notice is readable and fields/buttons remain within viewport width.

Primary references (implementation is bounded by executed browser evidence):
[HTML NavigateEvent and cancellation](https://html.spec.whatwg.org/multipage/nav-history-apis.html#the-navigateevent-interface),
[HTML firing the navigate event](https://html.spec.whatwg.org/multipage/nav-history-apis.html#fire-a-traverse-navigate-event),
[Chrome Navigation API overview](https://developer.chrome.com/docs/web-platform/navigation-api).
The overview's broad browser Back limitation must not override current `event.cancelable` evidence;
script traversal and browser-UI traversal have different cancellation/activation rules.

### Checks, evidence and limits

- TypeScript `miniapp/node_modules/.bin/tsc --noEmit -p miniapp/tsconfig.json`: PASS.
- `npm --prefix miniapp run build`: PASS; existing >500 kB chunk warning (615.16 kB main JS).
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --grep 'owner UX02' --workers=1`:
  **32/32 PASS (13.8s)**.
- Full `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke`: **320/320 PASS (2.5m)**,
  zero skipped/expected-failure cases. All original 306 remain, including the five former failures.
- `git --no-optional-locks diff --check`: PASS; source hashes confirm the three unrelated UX02
  runtime/style files unchanged. Final status is the original **8 modified + 2 untracked**, no staged
  changes; only the seven scoped files listed above changed during this task. HEAD unchanged.
- Result: **`HT_VENUE_OWNER_UX02_HISTORY_COMPAT_READY`**. No unfinished test/server operation.
  Next: review the local diff, then separately authorize publication/staging/real Telegram smoke.

Evidence: `.tmp-checks/ht-venue-owner-ux02/compat-{probe,typescript,build,focused,full}.log`,
`compat-noncancelable.log`, `compat-draft-390.png`, and final `compat-validation.json`.
Early test failures were diagnostic fixture issues: Playwright `goBack/goForward` waits for `load`
even when the navigation is cancelled; extension endpoint filter and initial team-checkbox state;
ambiguous Manager select locator; and treating script Back as browser-UI Back. Fixtures were corrected
without suppressing failures or weakening final URL/draft/history/write-count invariants. Initial
sandbox localhost listen EPERM was resolved by approved localhost execution, with no external service.

Final `git status --short` (pre-existing UX02 changes retained; nothing staged):

```text
 M PROJECT_STATUS.md
 M docs/TESTING_QA_SMOKE_STRATEGY.md
 M docs/VENUE_OPERATIONS.md
 M miniapp/e2e/guest-smoke.spec.ts
 M miniapp/src/screens/venueApp.ts
 M miniapp/src/screens/venueDashboard.ts
 M miniapp/src/screens/venueSettings.ts
 M miniapp/src/style.css
?? docs/HT_VENUE_OWNER_UX02_LOCAL_VALIDATION.md
?? miniapp/src/screens/venueSettingsCatalog.ts
```

Real Telegram/iPhone/Android WebView, WebKit/Firefox, host close/reload behavior and staging remain
NOT_VERIFIED. In-memory drafts deliberately cannot survive app disposal/full reload/host process
termination; native unload prompts may be suppressed by the host. Separately authorized staging and
real Telegram smoke remain required before release. This is local compatibility readiness only.


## History hardening diagnosis — HT-VENUE-OWNER-UX02-HISTORY-HARDEN (2026-10-26)

**BLOCKED / STOP BEFORE RUNTIME IMPLEMENTATION. Neither P2 is fixed.** This section supersedes
any broader history-safety interpretation of the earlier 300/300 result below. The task explicitly
requires stopping if the current history scheme cannot safely restore arbitrary existing entries.

Identity gate PASS: PROJECT_ID `Hookah_Tootah`, repository `hookah_bot_ANT`, physical worktree
`/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`, branch
`codex/ht-venue-owner-ux02`, exact expected/observed HEAD
`6f07c450ba22d7782a592fc71612eb47840d1c60`. EXPECTED_BASE and parent relationship: `NOT_SUPPLIED`.
Common dir `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT/.git`; registered checkout Git dir,
index and both indirection files agree. Origin fetch/push identify `github.com/koteev-m/hookah_bot`.
No identity overrides/redirections/includes/alternates; only `GIT_PAGER=cat`. Applicable instructions:
platform/session, supplied Hookah task, empty global AGENTS.md and matching worktree AGENTS.md;
no ancestor/narrower/configured extra instructions. Starting tree: 8 modified + 2 untracked.

### Real Chromium findings before any runtime change

Six new browser cases in `miniapp/e2e/guest-smoke.spec.ts` use the native History API:

| Case | Actual result | Boundary |
| --- | --- | --- |
| Existing same-document entries, missing index | FAIL | Back reaches Settings, router marks it `+1` and goes Back again to Home; dirty Card remains displayed. |
| Existing same-document entries, invalid index | FAIL | Same wrong-direction compensation; existing legacy state is augmented with an invented index. |
| Two Back while compensation submission is held | FAIL | Native Back reaches Settings then Home; queued `go(+1)` reaches only Settings, dirty Card remains displayed. |
| Immediate native `back(); back()` | FAIL | URL stays at Home with dirty Card displayed; no artificial event/delay used. |
| Native `go(-2)` with known indices | PASS | Restores Card, retains draft, explicit save works, later `go(-2)`/`go(2)` works. |
| Existing unindexed Forward destination two entries away | FAIL | Compensation uses `-1` instead of `-2`, stopping at Settings with dirty Card displayed. |

The first two fixtures seed history through native replaceState/pushState in an init script before
the router boots. The overlap fixture holds only submission of the application's compensation;
both Back traversals, compensation and all events execute in Chromium. It deterministically exposes
the outstanding-restoration window, independently of normal event timing. The immediate double-Back
case additionally reproduces the issue without that seam. No synthetic popstate/hashchange model
replaces browser behavior. Event evidence records `cancelable: false` and retained draft text.
The overlap behavior differs in detail from the delayed fixture (Home versus Settings), not in safety.

Shared cause: `readHistoryIndex() ?? renderedHistoryIndex + 1` treats an unknown destination as a new
forward entry; `restoringHistory` ignores intermediate movement and only clears at an exact match.
An issued relative `go(delta)` can also be superseded by another traversal. Counting on one eventual
compensation event is unsafe. Registration/disposal uses matching popstate/hashchange handlers;
no duplicate registration was found in the inspected code. The existing same-document guard is
save-first, with no discard dialog; native document exit uses the existing beforeunload confirmation.

### Why implementation stopped, and the minimum alternative

The legacy History API exposes current state and total length, but no current position, traversal
source/delta or lookup/traversal by entry identity. Arbitrary unmarked entries can occur behind or
ahead of the form, at different distances, with repeated URLs. Identical observable URL/state/length
can therefore require different recovery deltas. A guessed direction, scanning unknown entries,
a timeout that clears the flag or rewriting/pushing the rendered URL cannot guarantee the task's
original-position, intact-history and dirty-form invariants. Fixing the known-index overlap alone
would not satisfy the requested common solution.

This is a limitation of the current mechanism, not a claim that browser navigation can never be
made safe. Minimum alternative: use browser-owned Navigation API entry keys (`currentEntry.key`,
`traverseTo(key)`) for restoration, serialize/observe restoration by target identity and retain the
form until that target is reached. This requires an explicit compatibility/fallback contract for
supported Telegram WebViews; a feature-detected return to the existing unknown-index algorithm is
not a safe fallback. Native cancellation alone must not be assumed for every browser Back/Forward.
For clients without the required API, a separate full-document form-navigation boundary using the
native beforeunload guard is an alternative, with an explicit product/UX change. Neither alternative
was implemented or claimed tested in this bounded task.

Primary references: [HTML History interface](https://html.spec.whatwg.org/multipage/nav-history-apis.html#the-history-interface),
[NavigationHistoryEntry](https://html.spec.whatwg.org/multipage/nav-history-apis.html#the-navigationhistoryentry-interface),
[Chrome Navigation API overview and cancellation limits](https://developer.chrome.com/docs/web-platform/navigation-api).

### Validation and scope

- Targeted `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --grep 'owner UX02' --workers=1`:
  **13 PASS / 5 FAIL (37.7s)**. All 12 existing UX02 regressions passed; 1 of 6 new cases passed.
- TypeScript `miniapp/node_modules/.bin/tsc --noEmit -p miniapp/tsconfig.json`: PASS.
- Production `npm --prefix miniapp run build`: PASS; existing >500 kB chunk warning remains.
- Full `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke`: **301 PASS / 5 FAIL, 306 total (2.9m)**.
  All original 300 tests passed; the same five new regressions failed. This is a fresh red run, not closure.
- `git --no-optional-locks diff --check`: PASS. Hash-based scope check: all five runtime/style files
  byte-identical to task start; only the five listed test/docs files changed. Final status remains
  8 modified + 2 untracked, with no staged changes. Local test server has exited (no listener on 5174).
- Initial sandbox localhost listen EPERM was an environment failure; approved localhost execution
  ran the actual browser checks. No application workaround was made for that failure.

Evidence under `.tmp-checks/ht-venue-owner-ux02/`: `harden-before.log`, `harden-focused.log`,
`harden-full.log`, `harden-typescript.log`, `harden-build.log`; preserved `harden-focused-*.zip`
traces and extracted `harden-focused-*.json` snapshots contain the observed browser events/drafts.
`harden-start.json` records all ten starting-file hashes; `harden-validation.json` records final
scope/results. Failing cases remain ordinary regressions (no skip/expected-failure masking).
Assertions after their first failure are intended acceptance checks, not executed success evidence.

Runtime sources/styles remain byte-identical to task start. Changes in this task are regression tests,
this validation report, QA coverage, the Venue Operations limitation note and the current checkpoint.
Backend/API/DB/Flyway/RBAC/Bot/business logic, `scripts/dev/`, Git metadata and other checkouts are
untouched. No stage/commit/push/PR/merge/SSH/deploy. Staging was not run; this diagnostic-only change
does not need deployment. Any later runtime fix requires fresh tests and separately authorized
staging/real Telegram smoke. Second-venue-kept-selected navigation and API-call-count invariants
are not newly proved by these failing reproductions; old switching-back-to-first coverage is narrower.
Next: agree the Navigation API compatibility/fallback contract before resuming implementation.

## P2 history correction — HT-VENUE-OWNER-UX02-FIX-P2-HISTORY (2026-10-26)

Fresh identity gate PASS in the same physical worktree, branch, HEAD and common dir listed below.
HEAD remains `6f07c450ba22d7782a592fc71612eb47840d1c60`; no new base/parent constraint was supplied.
The starting state was the expected eight modified and two untracked UX02 files, not a clean tree.

Cause: the blocked hash traversal used `replaceState` after the browser had moved to the previous
entry, overwriting that entry's URL. The new real Chromium regression failed before the fix:
after two blocked Back attempts and explicit save, Back reached `about:blank` instead of Settings.
Evidence: `.tmp-checks/ht-venue-owner-ux02/p2-before.log`.

The bounded change in `venueApp.ts` attaches an index to each native hash history entry while
preserving other history state. Blocked Back/Forward traverses back to the rendered entry by the
index delta. Restoration skips the guard and rendering, keeping the same dirty form and avoiding
extra entries. Both popstate/hashchange are handled and removed on disposal; the already-rendered
entry is not rendered twice. Updating the selected venue URL retains history state. No pushState
or previous-entry URL replacement is used for cancellation.

Three new browser tests cover repeated blocked Back → explicit save → catalog → home → Forward,
unchanged history length and exactly one explicit write; blocked Forward from a direct form and
subsection history; native document Back with discard cancellation/acceptance and no write.
The existing two-venue test also checks subsequent Back/Forward. The existing hash guard still
requires saving before leaving; there is no new discard dialog. Native document exits retain the
existing beforeunload dialog. Preview, pending saves and other UX02 regressions remain in the suite.

Checks and scope:

- TypeScript `miniapp/node_modules/.bin/tsc --noEmit -p miniapp/tsconfig.json`: PASS.
- `npm --prefix miniapp run build`: PASS; existing >500 kB chunk warning remains.
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --grep 'owner UX02'`: **12/12 PASS**.
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke`: **300/300 PASS (2.4m)**, Chromium.
- `git --no-optional-locks diff --check`: PASS.
- Result: `HT_VENUE_OWNER_UX02_P2_FIXED`; no commit/publication performed.
- Initial local listen EPERM required approved localhost execution. Native test development exposed
  fixture issues (hash-only versus document navigation, waiting for a cancelled load, null DTO versus
  empty input); these were corrected without application changes or weakening existing tests.
- Source hashes against the original UX02 manifest confirm `venueDashboard.ts`, `venueSettings.ts`,
  `venueSettingsCatalog.ts` and `style.css` are unchanged. Only router, regression tests and relevant
  docs/checkpoint change in this correction. Backend/API/DB/RBAC/Bot/billing/lifecycle and `scripts/dev/`
  remain untouched. No other checkout, Git metadata or external action was changed.

Logs use the `p2-` prefix in `.tmp-checks/ht-venue-owner-ux02/`; `p2-validation.json` records
current source hashes and results. The original `validation.json` and
297-test evidence below describe the pre-correction UX02 implementation. Real iPhone Telegram,
WebKit and staging remain NOT_VERIFIED; local Chromium is not release readiness.

## Identity and scope

Identity gate PASS, read-only and without fetch or Git metadata mutation:

- PROJECT_ID `Hookah_Tootah`; repository `hookah_bot_ANT` / `github.com/koteev-m/hookah_bot`.
- Expected and physical root: `/Users/maksimmartynov/IdeaProjects/hookah_bot_worktrees/ht-venue-owner-ux02`.
- Branch `codex/ht-venue-owner-ux02`; expected/observed HEAD and exact starting base
  `6f07c450ba22d7782a592fc71612eb47840d1c60`; parent constraint `NOT_SUPPLIED`.
- Common dir `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT/.git`; checkout Git dir and index
  belong to `worktrees/ht-venue-owner-ux02`; `.git`, `gitdir`, `commondir` and physical paths agree
  with its `git worktree list --porcelain` registration.
- Origin fetch/push normalize to the expected remote. No identity overrides, includes,
  `core.worktree`, hooks redirection or object alternates; only `GIT_PAGER=cat` in the environment.
  Local configuration has the normal origin fetch refspec and worktree-config extension.
- Instruction origins: inherited platform/session instructions, user Hookah task, empty global
  `/Users/maksimmartynov/.codex/AGENTS.md`, this worktree's matching `AGENTS.md`; no additional
  ancestor/narrower/configured instruction files found. Other registered worktrees are metadata
  only, not task authority. Initial `git status --short` was empty.

Only Mini App code/styles, frontend tests and these behavior/checkpoint docs change. Existing
forms and APIs are reused. Backend, API DTOs, DB/Flyway, RBAC policy, Bot, Guest/Platform behavior,
billing/lifecycle and `scripts/dev/` are untouched. No external actions are authorized or executed.

## Commands and results

- `npm --prefix miniapp ci --offline --ignore-scripts --cache /Users/maksimmartynov/.npm --no-audit --no-fund`:
  PASS, 16 packages from local cache, no dependency/lockfile change.
- `miniapp/node_modules/.bin/tsc --noEmit -p miniapp/tsconfig.json`: PASS.
- `npm --prefix miniapp run build`: PASS. Existing Vite chunk-size warning remains
  (main JS approximately 612 kB before gzip); no new dependency.
- `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke`: **297/297 PASS** (2.4m), Chromium; complete configured frontend suite.
- `git --no-optional-locks diff --check`: PASS, including final documentation sanity.

The first sandboxed browser run failed before tests with local `listen EPERM`; the subsequent
approved localhost execution ran normally. Initial integration run: 280/288 PASS; failures were
old navigation/fixture expectations, one Manager unread-label helper and duplicate rendering on
queued hash changes. Those were addressed, retaining behavioral assertions. The focused rerun
of affected cases plus new UX02 scenarios passed 21/21 before final layout refinements.
The complete suite passed 297/297, and passed 297/297 again after the final unread badge contrast
fix. PNGs were visually inspected, including DRAFT/PUBLISHED, catalog, card, week, More, desktop
and the reduced-height schedule. One primary navigation entry is active at a time.

## Changed files

- `miniapp/src/screens/venueApp.ts`: Owner catalog/subsection routing, five-entry More, navigation
  selection/return handling, conversation badge and settings leave/venue-switch guards.
- `miniapp/src/screens/venueDashboard.ts`: shared DRAFT/PUBLISHED operations, compact draft notice,
  existing counters, permission-filtered shortcuts and restricted lifecycle context.
- `miniapp/src/screens/venueSettingsCatalog.ts`: permission-filtered catalog of existing screens/forms.
- `miniapp/src/screens/venueSettings.ts`: subsection rendering without duplicated forms, compact Owner
  weekdays, dirty draft preservation, guarded navigation and input locking during writes.
- `miniapp/src/style.css`: compact catalog/week, touch target sizes, responsive form controls and
  unread contrast; existing UX01 colors, typography and card style retained.
- `miniapp/e2e/guest-smoke.spec.ts`: existing assertions adapted to actual navigation; nine new UX02
  cases cover catalog/viewport, drafts/saves, preview origin, loading errors and pending-save guards.
- `docs/VENUE_OPERATIONS.md`, `docs/TESTING_QA_SMOKE_STRATEGY.md`, this report and `PROJECT_STATUS.md`:
  behavior, regression scope and local checkpoint/evidence; no staging success claim.

## Evidence and limits

Local evidence directory: `.tmp-checks/ht-venue-owner-ux02/` (ignored, not staged). Final command
logs, PNGs and source hashes are recorded there in `validation.json`. Screenshot fixtures contain
no real venue or guest data. Chromium viewport tests simulate dimensions; they do not simulate
native iOS keyboard/WebView behavior. WebKit and real iPhone Telegram were not executed.

Media upload is an existing product gap, not a new server-contract requirement for this task.
The Settings card contains no nonfunctional upload button. There are no fabricated dashboard
metrics; the existing bounded staff-call result and unread endpoint are used as-is.

Staging is NOT_VERIFIED. Runtime UI changes require separately authorized staging/real Telegram
smoke before release. No commit, staging of files, push, PR, merge, SSH or deploy was performed.

Result: `HT_VENUE_OWNER_UX02_LOCAL_READY`. Next: review the local diff; publication/deployment requires a separate task.

Local screenshot links:

- [DRAFT mobile](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-draft-390.png)
- [PUBLISHED mobile](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-published-390.png)
- [Settings catalog](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-catalog-390.png)
- [Card form](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-card-390.png)
- [Weekly hours](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-hours-390.png)
- [More](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-more-390.png)
- [Desktop catalog](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-catalog-1280.png)
- [Reduced-height hours](../.tmp-checks/ht-venue-owner-ux02/screenshots/owner-hours-keyboard-390.png)
