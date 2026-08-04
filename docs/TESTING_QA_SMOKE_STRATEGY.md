# Testing / QA Smoke Strategy

Дата актуализации: 2026-08-04.

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
- Venue Mini App Guest Preview Phase 2.1 is **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**. Focused preview/Guest/RBAC/promotion backend tests, compile/lint, Mini App build and deterministic browser smoke `95/95` are green; GitHub Actions were green, staging deploy completed and manual staging smoke passed for the unified contract.
- Menu shift check is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.
  OWNER/MANAGER use an own-venue local draft and one atomic availability batch; Staff individual
  stop-list policy is unchanged. GitHub Actions were green, staging deploy completed and the
  functional/UX manual smoke passed.
- Catalog search/filter is
  **CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**. Focused
  `GuestVenueRoutesTest`, backend compile/lint, Mini App build, focused catalog/favorite browser
  checks and full deterministic browser smoke `104/104` passed locally; GitHub Actions were green,
  staging deploy completed and manual staging smoke passed on the current limited venue dataset.
  Extended multi-venue coverage remains **NON-BLOCKING DEFERRED MANUAL SMOKE /
  CATALOG-SEARCH-MANUAL-001** and does not downgrade the completed MVP.
- Staff identity linking is
  **STAFF IDENTITY LINKING UX + DUPLICATE PREVENTION / DONE / MVP / STAGING-SMOKE-PASSED**.
- Staff Schedule is
  **STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.
  Staff Operations Slice A is
  **MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP / STAGING-SMOKE-PASSED**. Canceled Shift
  Restore + Bulk Assignment is **DONE / MVP / STAGING-SMOKE-PASSED**. The Phase 1 schedule schema
  remains **NO_MIGRATION_EXPECTED**; Identity Linking used the existing transaction-bound member
  lock; invite revoke uses PostgreSQL V120/H2 V121. Green Actions, deploy and manual staging smoke
  are complete.
- Staff Operations Slice B is
  `STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`. Green GitHub Actions, staging deploy,
  PostgreSQL V121 application and the bounded manual smoke are complete.

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

## Staff Schedule Phase 1 Release Quality Gate

Status:
**STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.
Slice A status:
**STAFF OPERATIONS SLICE A / MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP /
STAGING-SMOKE-PASSED**.
Schedule and identity-linking schema verdict: **NO_MIGRATION_EXPECTED**. Restore + Bulk Assignment
is **DONE / MVP / STAGING-SMOKE-PASSED**; identity linking is also **DONE / MVP /
STAGING-SMOKE-PASSED** and changes no Schedule calculation/lifecycle, Today Staff or Guest source.
The complete acceptance matrix remains canonical in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`.
PostgreSQL V120/H2 V121 belongs to Slice A invite revoke, not Restore + Bulk Assignment; its rollout
is included in the completed release evidence.

### Staff Operations Slice B Release Quality Gate

Status:
`STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`.

The additive settings migrations, runtime repository/API/CAS/audit, narrow RBAC, fail-closed module
guards, shared MANUAL/SCHEDULE Guest resolver and bounded Mini App UX are implemented. Local
targeted evidence covers PostgreSQL/H2 defaults and source check, monotonic full-object CAS,
Owner/Manager settings routes, Staff/foreign/Guest/Platform denial, tenant-before-state behavior,
transaction-bound audit and rollback, retained-data disable/re-enable, unchanged core access,
MANUAL exact-date publication, SCHEDULE ACTIVE `[start,end)` timezone/overnight/DST behavior with
no fallback, Guest/Preview privacy/parity and venue-switch isolation.

Recorded local validation:

- targeted `*VenueStaffModuleSettings*`, `*VenueStaffRoutesTest*`, `*VenueRbacRoutesTest*`,
  `*GuestVenueRoutesTest*` and `*VenueGuestPreviewRoutesTest*` selectors passed;
- `:backend:app:compileKotlin` and `:backend:app:ktlintCheck` passed;
- `npm --prefix miniapp run build` passed;
- the real PostgreSQL migration smoke executed V120→V121 with `skipped=0` and `failures=0`;
- focused Slice B browser checks and the exact full Mini App e2e smoke passed (`131/131`);
- independent read-only review found no remaining P0/P1 findings.

Release/staging evidence:

- GitHub Actions completed green and staging deploy succeeded;
- staging PostgreSQL applied V121 after the Testcontainers V120 -> V121 run reported
  `skipped=0`, `failures=0`; H2 V122 remains the test-family counterpart;
- exactly one new backend instance served settings mutation and no old backend instance remained;
- the 13-scenario manual smoke passed: defaults, Owner persistence, Manager narrow authority, Staff
  denial/navigation, stale CAS, MANUAL persistence, Guest visibility-off, master-off retained
  data/core access, re-enable, SCHEDULE active-only/no-fallback, privacy, venue/account isolation and
  cleanup;
- cleanup restored module enabled, Guest visibility enabled, source `MANUAL`, the original manual
  Today state and core Staff access.

The exact scenario-by-scenario record remains in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. Old-binary
rollback after real `source=SCHEDULE` use is semantically unsafe; release handling remains
forward-fix.

Locally validated backend coverage:

- Manager creates Staff but not Manager/Owner/Admin invites, sees/revokes pending Staff only; Owner
  retains Staff/Manager create/list/revoke, and Staff/foreign/Guest/Platform-only is denied;
- invite pending predicates, revoked preview/accept/decline failure, used/expired/repeated revoke,
  controlled accept-vs-revoke race, secret-safe transaction-bound create/revoke audit and rollback;
- Manager display-only/Staff-linked profile create/edit/publish/hide, protected Owner/other-Manager
  denial, safe own edit, invalid/foreign linkage denial, Owner/Staff regression and safe
  transaction-bound audit with no denial/no-op/rollback success row;
- accepted Staff member projection from the existing `users` identity row, including trimmed
  display name, present `username`, safe missing-username fallback and no second identity cache;
- pending invite projection with role/status/created/expires only in the visible UI and no invented
  recipient identity; accept removes pending state and exposes the fresh active member/link state;
- Manager receives active Staff identities/link targets only, while Owner retains the current
  permitted active-member projection; Staff/Guest/foreign/Platform-only directory reads are denied;
- member/profile-link DTOs exclude phone, invite secrets, raw `initData`, private notes/audit
  metadata and any Telegram/member fields from Guest DTOs;
- private profile raw bodies use server-computed `linkageClass/canManage/isSelf`; Manager
  Owner/Manager/orphan/duplicate projections redact raw linkage, Staff self uses `isSelf`, Owner
  retains the broader current private contract and protected errors reveal no target metadata;
- create-from-member accepts only `userId`, required `subtype` and compatible `roleLabel`, re-reads
  target membership/current `users` identity, creates one active Guest-hidden draft and never treats
  a client display name or visibility flag as authority; generic create rejects linked writes;
- one-active-link enforcement for create/relink/reactivation under target `venue_members FOR UPDATE`,
  typed existing-profile conflict and no success audit on denial;
- PostgreSQL Testcontainers drives two real HTTP transactions through concurrent create-from-member
  and relink scenarios, proves both wait on the target membership lock via PostgreSQL lock state,
  uses no arbitrary sleep and requires exactly one winner/one typed conflict/winner-only audit;
- pre-existing multiple active links report `DUPLICATE_LINK_DETECTED` and remain distinct; Manager
  sees the duplicate state read-only, while Owner repairs it by opening the concrete wrong card in
  the common card list and using safe unlink; no automatic merge/delete/relink and no
  Schedule/self-view dedupe;
- weekly/exception/overnight/closed/not-configured effective-hours response, venue timezone,
  batched range semantics and explicit error propagation; shifts outside hours remain allowed;

- Owner and Manager bounded create/update/cancel in their own venue;
- Staff mutation, Guest, foreign venue and Platform-only denial;
- Staff own-shift read plus safe non-canceled overlapping colleagues, with unrelated shifts and all
  of the requester's own linked profiles excluded from colleague projections;
- colleague DTOs may include the safe profile identifier `staffProfileId` plus display/schedule
  fields, but exclude shift-row ids, linked user/account ids, Telegram fields, `updatedAt`,
  guest-visibility flags and admin/mutation metadata; display-only profiles have no self-view;
- venue-local interpretation with explicit `Europe/Moscow` fail-safe, browser/system timezone
  independence, DST handling and no trusted client offset/status;
- overnight and inclusive 24-hour maximum; non-positive and over-24-hour rejection;
- future-only create/update, 90-day create horizon, required bounded `from/to`, 31-day maximum query
  and 30-day recent/90-day future read envelope;
- one profile/start-date conflict, including concurrent create and date-changing update mapping;
- computed scheduled/active/completed, stored canceled, no lifecycle worker, completed immutability,
  explicit future canceled restore and active cancel-only policy;
- cancel confirmation carries a non-authoritative expected confirmation state; crossing
  `SCHEDULED -> ACTIVE` after preview is rejected and requires the stronger active confirmation;
- expected-`updatedAt` stale rejection and no-op behavior; every real Schedule/related Today write
  advances the round-tripped token, and two mutations with one token commit exactly one;
- exactly one transaction-bound safe `STAFF_SHIFT_CREATED`, `STAFF_SHIFT_UPDATED`,
  `STAFF_SHIFT_CANCELED` or `STAFF_SHIFT_RESTORED` audit for a successful real mutation and none for
  no-op/denial/error/rollback;
- planned times survive a Today request that omits them, schedule rows stay guest-hidden, an engaged
  Today overlay blocks Schedule date/time moves, and current Staff Profile/Today Shift/Guest
  `Сегодня работают` behavior remains unchanged.
- any complete row invalid under the current venue timezone/DST/duration rules fails closed for
  Staff overlap/self reads and returns a neutral safe Owner/Manager warning/repair contract instead
  of guessing its origin, crashing or silently reinterpreting it; future-date rows follow
  repair/cancel, venue-today rows cancel-only and past rows read-only.
- future canceled restore with saved and new times keeps the same `shiftId`, one database row,
  schedule visibility defaults and an advanced CAS token;
- restore rejects stale, scheduled, active, completed, past canceled, Today-overlay, Staff, foreign,
  Guest and Platform-only requests without false audit or safe-row disclosure;
- `STAFF_SHIFT_RESTORED` has the safe old/new interval/lifecycle payload and audit-column actor;
  forced audit failure rolls the restore back;
- ordinary authorized create classifies canceled/scheduled/active/completed conflicts safely, while
  foreign actors receive no existing-row details;
- 1..50 assignment batch supports common/per-profile intervals and mixed `CREATE`/`RESTORE`; one
  database transaction rejects duplicate slots, missing/foreign profiles, invalid interval, stale
  restore or one lifecycle conflict and rolls back every write and audit;
- deterministic lock order is profile id, then profile/date row, then writes, per-row audits and
  commit; concurrent create/restore has one deterministic winner without process-local locks or
  arbitrary sleeps;
- the existing single-profile create, individual edit/cancel/CAS, planned/manual Today and Staff
  self-view regressions remain green.

Locally validated Mini App/e2e coverage:

- Owner `График смен` week list/editor and Manager parity;
- Staff read-only `Мои смены` with overlap-only colleagues and no admin controls;
- accepted employee display name, `@username`/`Без username`, role badge and link status in
  `Доступ сотрудников`, with full raw id never used as the primary label;
- `Создать карточку` preselects the correct active member and safe display name; one linked profile
  changes the row action to `Открыть карточку` and removes/disables that member in other selectors;
- duplicate-link warning uses the canonical copy and keeps every distinct profile/shift visible;
- Manager sees Staff actions but no editable Owner/Manager targets; Owner controls are preserved;
- venue switch and account switch abort/clear member identity, linkage, profile form and late
  response state; Staff/Guest receive no internal directory;
- week navigation, timezone copy and overnight rendering;
- loading, optional empty, retryable error, update preview, cancel confirmation and active warning;
- stale conflict offers refresh and never overwrites current state;
- venue switch aborts/clears old data and ignores late responses; selected-venue persistence is
  restored only after fresh access-list validation;
- direct Staff mutation denial plus existing Staff Profiles/Today Shift and Guest regression.
- multi-select Staff/display-only profiles, common effective hours, apply-to-all, per-employee
  override and employee removal;
- explicit canceled-row restore choice, scheduled/active/completed blocking reasons and exact
  create/restore confirmation counts;
- one normalized batch request, atomic-error unchanged state, success week refresh and retained
  canceled historical row with restore action;
- Staff has no restore/batch controls, existing individual edit/cancel remains, and venue switch
  clears selection/draft/confirmation/stale response.

No Telegram behavior is added. Do not add reminders, outbox events, buttons, staff-chat messages or
Telegram mutation UI to satisfy this gate.

Recorded local gate:

```bash
git status --short
git diff --check
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueStaffRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueRbacRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueRoutesTest*' --console=plain
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*StaffProfile*Concurrency*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

The commands above are the required current-worktree gate for Staff Identity Linking UX Polish and
the unchanged Restore + Bulk Assignment regression. Record the exact results in the implementation
handoff; do not reuse an earlier Slice A browser count as proof for this slice.

The PostgreSQL selector is proof only when Gradle reports executed tests greater than zero,
`skipped = 0` and `failures = 0`; a Docker/Testcontainers assumption skip is not a pass.

Release result: green GitHub Actions, staging deploy and manual smoke passed for Owner/Manager/Staff
identity and schedule boundaries, username/missing-username labels, duplicate Owner-only repair,
venue/account isolation, saved/new-time restore, common/per-person and mixed atomic batch, typed
conflicts, timezone/overnight, Staff privacy, unchanged Today/Guest behavior and cleanup. A separate
free-Staff-account create-from-member UI path was not evidenced and is tracked as the non-blocking
[`STAFF-IDENTITY-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#staff-identity-manual-001); it does
not downgrade Identity Linking MVP.

## Catalog Search And Filter Phase 1 Quality Gate

Status:
**CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.

Backend coverage must prove:

- optional `q` and `city` are trimmed, blank is equivalent to absent and values over 100
  characters fail validation without silent truncation;
- `q` matches name, city, address and formatted address case-insensitively; `city` uses a
  case-insensitive exact match; combined filters use `AND`;
- prepared parameters plus explicit escaping treat `%`, `_`, `!` and `\` literally, SQL-like input
  cannot alter query semantics, and behavior stays PostgreSQL/H2 compatible;
- the same query retains `PUBLISHED` lifecycle and guest subscription/availability guards, stable
  deterministic ordering, safe DTOs, authenticated access, current-user `isFavorite` and today
  schedule/open state;
- favorites and schedule remain batch-enriched without per-venue N+1 reads, two users receive
  isolated favorite state, unavailable venues disclose no card data and empty results are stable.

Mini App/browser coverage must prove:

- search sends encoded `q`, city selection sends encoded `city`, and both are sent together;
- a fixed 300 ms debounce avoids an immediate request per keystroke;
- query/filter replacement and screen disposal abort pending work, and only the latest response can
  update the catalog even when an older response completes later;
- city options use the initial complete unfiltered guarded response. The endpoint has no limit or
  pagination; blank cities are removed, case-insensitive duplicates preserve normal display
  spelling and the final list is sorted predictably;
- initial loading, retryable error, base-catalog empty and
  `По вашему запросу ничего не найдено` are distinct, and reset clears both `q` and `city`;
- optimistic favorite add/remove works inside filtered results, a filtered reload cannot restore a
  stale backend favorite value, out-of-result mutations stay safe and account switching clears the
  previous user's query/filter/favorite state;
- existing venue-card open, booking, ask-question, schedule and pre-QR menu-separation actions stay
  green. Tests wait for observable requests/state and do not use arbitrary sleeps.

Required local commands:

```bash
git status --short
git diff --check

./gradlew --no-daemon --max-workers=1 :backend:app:test \
  --tests '*GuestVenueRoutesTest*' --console=plain

./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain

npm --prefix miniapp run build

CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 \
npm --prefix miniapp run e2e:smoke
```

Recorded local evidence: focused `GuestVenueRoutesTest`, backend compile, ktlint, Mini App
production build, focused catalog/favorite browser checks and the prescribed deterministic browser
smoke `104/104` passed. GitHub Actions were green, staging deploy completed and the current
limited-dataset manual staging smoke passed for initial catalog, name/city/address search, city
filter, combined `q + city`, no-match/reset, literal special characters, filtered favorites,
schedule enrichment, unavailable-venue non-disclosure and venue-card regressions.

The completed Phase 1 status does not claim an extended dataset smoke. Multi-result ordering,
case-insensitive city deduplication, larger-set latest-response-wins, two-account favorite state
and hide/publish restoration remain **NON-BLOCKING DEFERRED MANUAL SMOKE /
CATALOG-SEARCH-MANUAL-001** in
[`docs/DEFERRED_MANUAL_SMOKE_BACKLOG.md`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#catalog-search-manual-001).
That entry is required before catalog pagination, ranking, map/geo or a large pilot rollout.

## Venue Mini App Guest Preview Phase 2.1 Quality Gate

The Venue Mini App has one `Предпросмотр для гостя` entry and one read-only Venue endpoint. The
server, never cached client membership state, selects `PUBLISHED_PUBLIC` or `PRIVATE_DRAFT`.
`PUBLISHED_PUBLIC` must reuse the exact Guest venue/info DTO assembly and unchanged
availability/subscription guards. `PRIVATE_DRAFT` must be an OWNER/MANAGER own-venue,
server-allowlisted projection of saved guest-facing state. A query bypass on a Guest route, a
private settings DTO, client-side public/private merge or client-selected visibility mode is a
release blocker.

Required local coverage:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueGuestPreviewRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueRbacRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenuePromotionRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Local result on 2026-07-30: all four focused backend test selectors passed, Kotlin compile and lint
passed, the Mini App production build passed, and deterministic Playwright smoke passed `95/95`.

Staging result on 2026-07-30: green GitHub Actions and the staging deploy were followed by manual
smoke for published/private server-selected modes, saved-state/dirty-form behavior, OWNER/MANAGER
allow plus STAFF/foreign denial, privacy/child visibility, read-only action absence, origin-aware
navigation, two-venue stale-response isolation and unchanged real Guest/Venue Mode behavior.

Acceptance:

- OWNER and MANAGER receive only their own venue preview; STAFF has no entry/direct access, foreign
  venue users are forbidden, and Platform Owner receives no automatic Venue-route authority;
- `PUBLISHED_PUBLIC` venue/info payloads equal Guest payloads, including weekly hours, future date
  exceptions, visible info/media, Today Staff and current active promotions;
- `PRIVATE_DRAFT` includes saved guest-facing card/location/contact/description, schedule and public
  exceptions, visible info sections, guest-visible Today Staff and only current `ACTIVE`
  promotions. Hidden sections/media, unpublished staff, inactive/non-current promotions, private
  markers and raw Telegram/storage refs are absent;
- private preview media uses an authenticated, venue/section/media-scoped Venue proxy. Public Guest
  media routes and their availability guards remain unchanged;
- every preview response is read-only and `Cache-Control: no-store`; missing/archived/deleted venues
  fail safely and Guest routes still expose no unpublished/private projection;
- `PUBLISHED_PUBLIC` shows `Опубликовано` and `Так карточку сейчас видит гость.`;
  `PRIVATE_DRAFT` shows `Черновик`,
  `Гости пока не видят эту карточку. Это закрытый предпросмотр сохранённой версии.` and only the
  safe reason `Заведение ещё не опубликовано.`, `Заведение временно скрыто.` or
  `Заведение приостановлено.`, never technical subscription state;
- the Settings origin shows `Вернуться к настройкам`; venue navigation shows
  `Вернуться в кабинет`;
- public-card, weekly-schedule and date-exception dirty state blocks preview with
  `Есть несохранённые изменения. Сначала сохраните их, затем откройте предпросмотр.`;
  no auto-save/request occurs, and an explicit save makes the new saved state visible;
- venue switching clears old state immediately, aborts the previous request and ignores late
  responses across both modes, including same-mode switches;
- booking, favorites, venue chat, support, staff call, extension, cart, order and table context are
  absent and generate no mutation traffic.

The Preview smoke does **not** validate Venue Mini App media upload or management. No file picker,
upload endpoint, replace/hide/delete flow or new storage path is part of Guest Preview Phase 2.1.

## Venue Menu Shift Check Phase 1 Quality Gate

Status:
**MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**.

The shift-check block is part of the existing Venue Menu screen. Existing immediate individual
item/option stop-list routes remain unchanged; draft toggles and mass actions must not send
availability mutations before one explicit confirmation.

Required coverage:

- OWNER and MANAGER own-venue allow; STAFF/Guest/Platform-only/foreign venue direct denial;
- two collapsed-by-default task-oriented accordions are mutually exclusive; the category form and
  category details stay compact until explicitly opened;
- normal mode exposes only availability switches with nested options and dirty badges; selection
  checkboxes/actions appear only in the separate mass-selection mode with distinct accessible
  roles/labels;
- category item/option counts, search, unavailable/dirty filters, selected rows, category item
  changes, item option changes and select-all-filtered behavior;
- collapse/reopen preserves the draft; cancel restores backend state; venue switch clears the
  expanded state, draft, selection and pending confirmation context;
- cancel sends no mutation/audit; confirm sends exactly one batch; no-op confirm writes one audit
  with zero changed counts;
- combined maximum 500 changes, duplicate rejection, missing/foreign item/option rejection and
  option/item ownership validation;
- one DB transaction for item updates, option updates and exactly one
  `MENU_SHIFT_CHECK_COMPLETED` audit; every invalid/stale/rollback path leaves zero partial writes
  and zero completion audits;
- expected availability conflict rejects the whole batch with
  `Меню изменилось. Обновите проверку и повторите подтверждение.`;
- audit contains safe actor/venue ids, changed/reviewed counts and bounded changed-id lists, with no
  names, prices, raw Telegram/initData/private/customer data or full request body;
- venue switch/dispose aborts old requests, clears draft/selection and ignores late responses;
- confirmed item/option availability is reflected by Guest menu and revalidated by stale cart
  preview/add-batch;
- existing individual availability routes and Telegram Bot stop-list regression remain green.

Required local commands:

```bash
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueMenuRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueMenuRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestOrderRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*AuditLogRepositoryTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*TelegramBotRouterTableTokenTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

Recorded local evidence: `VenueMenuRoutesTest`, `VenueMenuRepositoryTest`,
`GuestVenueMenuRoutesTest`, `GuestOrderRoutesTest`, `AuditLogRepositoryTest`,
`TelegramBotRouterTableTokenTest`, isolated `compileKotlin`, `ktlintCheck`, Mini App production
build and the full deterministic browser smoke `100/100` passed. GitHub Actions were green,
staging deploy completed and manual smoke passed for Owner, Manager, Staff/foreign denial, the two
accordion UX, mass mode, draft/cancel/venue isolation, atomic/no-op/stale behavior, safe audit,
Guest availability/stale-cart rejection and Telegram stop-list parity.

## Venue Mini App Media Foundation Future Quality Gate

The canonical future contract is `docs/MEDIA_STORAGE_UPLOAD.md`. Its current verdict is
`STOP_FOR_MEDIA_STORAGE_DECISION`; this gate applies only after one durable storage option,
backup/deletion policy and operations owner are approved.

Required future coverage:

- PostgreSQL/H2 legacy-row migration and one source-neutral asset ledger;
- OWNER/MANAGER own-venue allow plus STAFF/Guest/Platform-only/foreign denial;
- server MIME sniffing, malformed/spoofed file, size, dimensions, cap and rate limits;
- no raw Telegram/object/filesystem ref in DTO, URL, DOM, error, log or audit;
- upload/replace/hide/show/delete lifecycle, safe audit, orphan/failed cleanup and
  reference-protected physical deletion;
- legacy Telegram plus selected-target delivery in Guest and Bot;
- exact `PUBLISHED_PUBLIC` Guest parity and authenticated/ref-free `PRIVATE_DRAFT` delivery;
- Mini App build/e2e plus real Telegram and selected-storage staging smoke;
- container recreate and backup/restore evidence for PostgreSQL metadata and selected byte storage.

Do not claim the media foundation release-ready from upload UI/API tests alone.

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
| Staff Operations / Schedule | `*VenueStaffRoutesTest*`, `*StaffInviteRepositoryTest*`, `*VenueRbacRoutesTest*`, `*GuestVenueRoutesTest*`, compile/lint, Mini App build/e2e. | Backend split + Mini App. | Yes for runtime; migration only when the selected slice has one. | Safe member identity/link projection, Owner/Manager/Staff boundaries, one-active-link and duplicate/race/unlink/audit, restore/create typed conflict, atomic batch, effective hours, timezone/overnight, privacy, Today/Guest regression and venue/account switch. | High for atomicity/RBAC/privacy/time semantics. |
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
| Staff profiles / Today Shift / Staff Schedule | `docs/STAFF_PROFILES_SHIFTS_TIPS.md` |
| Menu/stop-list | `docs/MENU_OPTIONS_STOPLIST.md` |
| Venue media storage/upload | `docs/MEDIA_STORAGE_UPLOAD.md` |
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

Guest catalog search/filter:
- open the authenticated pre-QR catalog and confirm its initial request is unfiltered and returns
  only guest-available venues with favorites and today schedule intact;
- search by mixed-case name, city and address, then select a city and confirm backend `q + city`
  `AND` behavior;
- verify `%`, `_`, `!` and `\` are literal search text and oversized `q`/`city` fail safely;
- type quickly and switch filters while delaying an older response; confirm debounce reduces
  requests and the older response cannot overwrite the latest state;
- confirm city options exclude blanks, deduplicate case-insensitively, preserve display spelling
  and stay complete/sorted after search or filtering;
- verify retryable error, unfiltered empty catalog, filtered no-match copy and reset of both controls;
- add/remove a favorite in filtered results, reload the filter and switch accounts; confirm the
  optimistic state does not roll back and no query/favorite state crosses users;
- hide, suspend or subscription-block a matching venue and confirm search reveals neither its name
  nor address; restore it and confirm normal guarded visibility returns.

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

Staff Identity Linking UX / Staff Operations Slice A / Staff Schedule staging smoke
(passed, excluding the deferred free-member create-from-member manual scenario below):
- accepted Staff member shows Telegram display name, `@username` when present or `Без username`
  with safe hint, role badge and link status; full raw id is not the main label;
- pending invitation shows role/status/created/expires and authorized revoke only, with no recipient
  identity; after accept it disappears and the active member appears with fresh identity/link state;
- Automated/contract coverage confirms that `Создать карточку` from a member row preselects that
  member/name, creates one Guest-hidden draft, changes the linked member to `Открыть карточку` and
  excludes it from repeat selection. A separate qualifying free-member manual run is not claimed;
- Manager sees active Staff identities/actions only and cannot receive Owner/Manager as editable
  targets; Owner retains current controls and protected/last-owner constraints;
- a second ordinary link is rejected; two concurrent create/link requests have one winner; existing
  duplicate data shows `К этому сотруднику привязано несколько карточек. Выберите основную и
  отвяжите остальные.` with no automatic cleanup or success audit;
- Manager duplicate state is read-only, exposes no arbitrary profile reference and offers no
  open/edit/link/unlink action; Owner opens the concrete wrong card in the common card list and uses
  the existing safe unlink flow; the other card stays linked, every distinct profile/shift/history
  remains visible, and no automatic merge/delete occurs;
- venue switch and account switch clear member identities, selected member, profile/link state and
  late responses; Staff/Guest never receive the internal directory and Guest DTOs remain unchanged;
- Manager sees `Добавить сотрудника`, can create only Staff, sees/revokes only pending Staff and
  cannot use the revoked invite; Owner still creates/sees/revokes Staff and Manager invites;
- Manager creates display-only and Staff-linked cards, edits/publishes/hides them, while Owner and
  other Manager cards are read-only; Owner and Staff self-edit regressions remain unchanged;
- Owner and Manager open `График смен`, navigate weeks, create/edit a future ordinary shift and
  create an overnight shift with `следующий день` copy;
- an `OPEN` date prefills effective venue hours, a date exception wins over weekly, `CLOSED` and
  `NOT_CONFIGURED` stay blank with manual copy, and load error is not shown as not configured;
- manual time survives date change, explicit `Заполнить по часам заведения` reapplies hours, and
  editing an existing shift preserves persisted times until explicit action;
- active shift has no edit action and requires stronger cancel confirmation; completed/canceled is
  immutable except that a future schedule-default canceled row has explicit restore;
- Owner/Manager selects several Staff/display-only profiles, applies common hours, overrides one
  interval, removes one employee and sees exact `Будет создано` / `Будет восстановлено` counts;
- a future canceled historical row remains visible with `Отменена`, old interval and explicit
  `Восстановить`; ordinary create reports the typed canceled conflict and never restores silently;
- scheduled/active/completed conflict blocks confirmation with a safe employee-specific reason;
- one mixed `CREATE`/`RESTORE` request commits all shifts and one audit per row; one stale/invalid/
  conflicting assignment leaves every row and audit unchanged;
- Staff opens `Мои смены`, sees only own rows and colleagues overlapping each row, including one
  display-only colleague; safe `staffProfileId` is allowed, while shift-row ids, private
  account/Telegram linkage and admin actions are absent;
- a second Staff account with no overlap sees no colleague/full-venue schedule;
- stale update is rejected and refresh loads the other actor's state;
- switching venue during a delayed request clears the old week and ignores its late response;
- switching venue also clears selected employees, per-profile interval overrides, confirmation and
  stale conflict state;
- a scheduled/restored row never appears in Guest `Сегодня работают`; manual Today Shift still controls
  Guest presence and does not erase planned times;
- no Telegram reminder/button, staff-chat message or outbox event is created.

Recorded result: **PASSED** after green Actions and staging deploy. At that pre-Slice-B smoke the
source remained `MANUAL`: only explicit manual Today Shift publication made a published
guest-visible card appear in `Сегодня работают`; planned/future schedule rows and the full staff
schedule remained private. The current correction is the Slice B release gate above: a venue may
now save `MANUAL` or active-only `SCHEDULE` with no fallback. The create-from-member step above was
not separately run with a qualifying free Staff member and is deferred only in
`STAFF-IDENTITY-MANUAL-001`.

Menu/stop-list:
- Owner toggles item unavailable;
- guest cannot submit stale unavailable cart;
- unavailable option is blocked;
- Staff/Manager permissions match policy.
- Owner/Manager shift-check draft changes make no request until confirmation;
- cancel and failed validation create no mutation/audit;
- one mixed item/option confirm is atomic and produces one safe completion audit;
- no-op confirm produces one audit with zero changed counts;
- stale expected availability rejects the whole batch and offers refresh;
- Staff entry/direct request and foreign venue request are denied without changing individual
  Staff availability policy;
- venue switch clears draft/selection and confirmed availability reaches Guest menu plus stale cart
  preview/add-batch validation.

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
- Menu shift check is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED** and stays
  in regression. Per-venue `staff_stoplist_enabled` remains future.
- Staff Schedule Phase 1, Canceled Shift Restore + Bulk Assignment, Staff Operations Slice A and
  Identity Linking are `DONE / MVP / STAGING-SMOKE-PASSED`. Identity/linking,
  duplicate/race/Owner-only repair, restore/batch atomicity, typed-conflict,
  privacy/RBAC/effective-hours/Today compatibility remain regression gates.
- Staff-chat delivery history/personal notifications/topic routing remain future.
- CI coverage is strong for release-critical slices but not proof of every product scenario; area smoke checklists remain necessary.

## Roadmap Status

- Testing/QA smoke strategy: `UPDATED`.
- Catalog search/filter: **CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP /
  STAGING-SMOKE-PASSED** after green Actions, staging deploy and limited-dataset manual smoke.
  Extended dataset coverage remains non-blocking deferred manual smoke in
  `CATALOG-SEARCH-MANUAL-001`.
- Guest Preview Phase 2.1: **VENUE MINI APP GUEST PREVIEW / PUBLISHED + PRIVATE DRAFT READ-ONLY / DONE / MVP / STAGING-SMOKE-PASSED**.
- Menu shift check: **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**; regression
  gates remain active.
- Staff Operations Slice A:
  `MANAGER PARITY + SHIFT TIME DEFAULTS / DONE / MVP / STAGING-SMOKE-PASSED`.
- Staff Schedule Phase 1:
  `STAFF SCHEDULE PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`; Canceled Shift Restore + Bulk
  Assignment and Identity Linking are also `DONE / MVP / STAGING-SMOKE-PASSED`; the Phase 1
  schedule/identity schema verdict remains `NO_MIGRATION_EXPECTED`.
- Staff Operations Slice B:
  `STAFF OPERATIONS SLICE B / OPTIONAL TEAM AND SCHEDULE MODULE / GUEST MANUAL OR SCHEDULE SOURCE / DONE / MVP / STAGING-SMOKE-PASSED`; local validation, independent review, green Actions,
  staging deploy, PostgreSQL V121 rollout and manual smoke are complete.
- Manual smoke checklist: `CONSOLIDATED`.
- CI coverage: `PARTIAL / release-critical split jobs current`.
- Frontend e2e: `PARTIAL`, with smoke coverage documented.
- Real Telegram smoke: `REQUIRED` for bot/staff-chat changes.
- Staging deploy smoke policy: `DOCUMENTED`.
- Venue media foundation quality gate: `DOCUMENTED / STORAGE DECISION REQUIRED`.

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
