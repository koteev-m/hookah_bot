# Staff Profiles, Today Shift, Staff Schedule And Staff Tips

Дата актуализации: 2026-08-03.

Статус: **canonical staff visibility/schedule/tips spec**.
`STAFF PROFILES + TODAY SHIFT PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`.
`STAFF OPERATIONS SLICE A / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`.
Slice A scope is `MANAGER PARITY + SHIFT TIME DEFAULTS`.
`STAFF IDENTITY LINKING UX + DUPLICATE PREVENTION / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`.
`STAFF SCHEDULE PHASE 1 / FUNCTIONALLY PASSED ON STAGING / IDENTITY LINKING FIX IMPLEMENTED / STAGING RE-SMOKE REQUIRED`.
The identity-linking blocker fix and the already implemented Restore + Bulk Assignment context
still require independent re-review, green Actions, runtime deploy and a new staging smoke. The identity slice does not
change Staff Schedule calculations/lifecycle, Today Staff or Guest source behavior. The separate
Slice A invite-revoke V120/H2 V121 rollout remains its own release gate.
`STAFF_TIP`, photo upload/media picker and staff shift sign-up/chat workflows
remain future. Phase 2 may create staff tip intents with external staff tip links, but the platform
must not collect guest order payments or staff tips in MVP.

## Core Rule

Public staff visibility is opt-in and privacy-first. A public staff profile is a guest-facing
display object owned by a venue; it is not the same thing as `venue_members` RBAC membership.

Guest order payment remains offline: venue staff brings the venue terminal and the venue accepts
payment on its own legal entity/IP. The platform does not become a payment aggregator for guest
orders. Staff tips, when implemented, must target a specific staff profile, not only the venue.

## Domains

| Domain | Purpose | MVP status |
| --- | --- | --- |
| `STAFF_PROFILE` | Guest-visible profile for a hookah master, waiter, admin or other staff subtype. | `STAFF PROFILES + TODAY SHIFT PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`. |
| `SHIFT_TODAY` | Simple manual "today on shift" visibility for public staff profiles. | `STAFF PROFILES + TODAY SHIFT PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`. |
| `STAFF_SCHEDULE` | Optional bounded venue schedule for planned staff shifts. | `STAFF SCHEDULE PHASE 1 / FUNCTIONALLY PASSED ON STAGING / IDENTITY LINKING FIX IMPLEMENTED / STAGING RE-SMOKE REQUIRED`. Restore + Bulk Assignment remains implemented; the identity-linking fix does not change schedule behavior. |
| `STAFF_TIP` | Future CTA and intent to thank a specific staff member. | Phase 2+ / spec draft. |

## Staff Profiles / Today Shift Phase 1 MVP

Current Phase 1 implementation plus Staff Operations Slice A includes:
- Owner keeps broad staff-profile management over same-venue memberships.
- Manager creates and manages display-only and active-Staff-linked profiles, including
  publish/hide; Owner/Manager-linked cards are protected and read-only except the Manager's
  existing safe self-edit fields.
- Profile may be linked to a real venue member through `linked_user_id`, or may be display-only.
- Public visibility is opt-in through `is_guest_visible`.
- Owner publishes/hides profiles; Manager publishes/hides only display-only or Staff-linked
  profiles.
- Staff may edit only their own linked draft fields if policy allows.
- Staff cannot self-publish.
- Owner/Manager may mark "today on shift"; conservative MVP allows Manager to mark
  active/completed/canceled and keeps scheduled shifts Owner-only.
- Manager может менять manual Today Shift только для display-only и active STAFF-linked profiles
  своего venue. OWNER/MANAGER-linked, orphaned, missing, inactive и foreign linkages protected.
- Guest sees `Сегодня работают` on venue detail below the main venue information/actions, not as
  the first block.
- Venue Mode `Персонал` separates `Доступ сотрудников` from `Карточки команды`: Owner/Manager see
  current access, safe pending invites and revoke controls, while protected cards are explicit.
- Accepted members are rendered from the existing `users` identity row as Telegram display name,
  optional `@username`, venue role and profile-link state. Full Telegram user id remains only a
  compatible request value and is not the primary UI label.
- `Создать карточку` from an unlinked member preselects that member and current safe display name;
  `Открыть карточку` opens the existing linked card. Manual id copying is not required.
- One venue member may have at most one active linked profile. Existing duplicates are reported and
  repaired only through explicit safe unlink; they are never merged, deleted, relinked or hidden
  automatically.
- The create form is collapsed
  by default, existing cards are compact, `Другое` requires `Название роли`, and raw User ID /
  Photo ref inputs are not exposed.
- Catalog may later show a short `Сегодня: Иван, Алина` line.

The already implemented Staff Profiles / Today Shift slice explicitly excludes:
- staff tip payments;
- payment provider integration;
- Telegram Stars tips;
- crypto;
- online guest order payment through the platform;
- schedule behavior inside the Profiles/Today slice itself; Staff Schedule Phase 1 is a separate,
  optional implemented module described below;
- photo upload/media picker;
- separate staff communication chat/forum topics;
- Telegram shift confirmation, shift sign-up or shift swaps.

## STAFF_PROFILE Model

Target fields:
- `id`
- `venue_id`
- `linked_user_id nullable` - never exposed to guests
- `display_name`
- `role_label`
- `subtype`: `hookah_master`, `waiter`, `admin`, `other`
- `photo_ref nullable`
- `bio`
- `tags`
- `is_guest_visible`
- `tips_enabled` - future
- `created_by_user_id`
- `updated_by_user_id`
- `published_at nullable`
- `disabled_at nullable`
- audit fields

Rules:
- Display-only profiles are allowed for staff who have not accepted an invite yet.
- Linking a profile to a venue member must verify the user belongs to the same venue.
- Active means `disabled_at IS NULL`; draft and published cards are active, while a hidden/disabled
  historical card is not. A member may have at most one active linked profile in one venue.
- Create, relink and reactivation lock the target `(venue_id, user_id)` row in `venue_members`,
  re-check the current role/scope, then inspect active profiles and mutate plus audit in the same
  database transaction. Concurrent requests for one member have one winner; no process-local lock
  or migration is required.
- If exactly one active linked card exists, mutation returns a typed conflict with its safe profile
  reference. If more than one exists, projection state is `DUPLICATE_LINK_DETECTED` and ordinary
  new linking is blocked until explicit unlink leaves one primary card.
- Existing duplicates remain distinct rows for Schedule/Today/self-view. No read-side dedupe,
  automatic primary selection, merge, delete or relink is allowed.
- If `subtype=other`, Owner must provide `role_label` / `Название роли`; this custom role is what
  guests see. Old or incomplete `other` profiles fall back to `Сотрудник`, not `Другое`.
- Public guest DTOs must not include `linked_user_id`, Telegram ids, phone, email or private notes.
- Owner/Manager private profile DTOs are actor-aware and contain server-computed
  `linkageClass`, `canManage` and `isSelf`. Manager receives `linkedUserId=null` for protected,
  duplicate, missing/orphaned, Owner-linked and Manager-linked cards; the Manager's own linked card
  is `PROTECTED`, `isSelf=true` and keeps only safe self-edit. Owner keeps the existing broader
  private linkage projection and repair controls. Staff self-view uses `isSelf` and does not expose
  the raw linkage id.
- Manager profile mutations re-check current linkage, current membership role, requested linkage
  and requested membership role inside the mutation transaction. Manager targets are limited to
  display-only or a same-venue Staff membership; missing/foreign and Owner/Manager links fail
  closed. Owner keeps the broader existing policy.
- Publish/hide follows the same protected-linkage policy. Staff cannot self-publish.

## Staff Access / Pending Invites

- Member identity is read from the existing `users` row joined to `venue_members`: trimmed
  `first_name + last_name`, then the existing safe fallback convention, plus nullable normalized
  `username`. Bot message/callback handling and Mini App Telegram authentication already upsert
  these fields, so no second identity cache is added.
- Current membership has no parallel active-status table/flag: an active member is an existing
  `venue_members` row; removal deletes that row and a later link attempt fails closed.
- The Owner/Manager projection contains only a safe member reference/current internal user id,
  `displayName`, nullable `username`, role, active state, nullable linked profile id/name and
  `profileLinkState`: `NOT_LINKED`, `LINKED`, `DUPLICATE_LINK_DETECTED` or `PROTECTED`.
- It excludes phone, invite code/hash, raw `initData`, private notes, audit metadata and Telegram
  identity from every Guest DTO. The full Telegram id may remain an internal request value but is
  not rendered as the main label; a last-four-digit hint is allowed when names are ambiguous or
  username is absent.
- Manager creates only `STAFF` invites; Owner creates `STAFF` and `MANAGER` invites. Neither role
  creates `OWNER` or legacy `ADMIN` through this venue flow.
- Pending means `used_at IS NULL AND revoked_at IS NULL AND expires_at > now`.
- Owner lists/revokes pending `STAFF` and `MANAGER`; Manager lists/revokes pending `STAFF` only.
- Pending DTOs contain only opaque handle, role, status and timestamps. Secret code/hash/deep link
  remains confined to the existing one-time create response.
- Pending UI shows only target role, status, created/expires timestamps and the authorized revoke
  action; no recipient name or Telegram identity is invented. After accept the pending row leaves
  the list and the active member projection is rebuilt from current `users` identity/linkage state.
- Accept/decline and revoke use competing conditional claims, so exactly one terminal mutation can
  win and membership creation rolls back if accept loses.
- PostgreSQL `V120` and H2 `V121` add only `revoked_at` and `revoked_by_user_id` plus pending-query
  indexes. New binaries must be deployed before revoke UI is enabled; old runtime instances must
  be drained, then staging-smoked, because they do not understand revoked invites.

## SHIFT_TODAY Model

Target fields:
- `id`
- `venue_id`
- `staff_profile_id`
- `shift_date`
- `starts_at nullable`
- `ends_at nullable`
- `status`: `scheduled`, `active`, `completed`, `canceled`
- `is_guest_visible`
- `manually_marked_active`
- audit fields

MVP behavior:
- The venue manually marks who is working today.
- Full scheduling, recurring shifts, payroll, assignment and staff performance analytics are future.
- Guest visibility requires both the shift and the linked staff profile to be guest-visible.

## Guest UX

- Venue detail shows `Сегодня работают` with visible public staff profiles when such shifts exist.
- `Сегодня работают` appears below main venue information/actions/menu context, not as the first
  card after the venue header.
- Guest API exposes only public profile display fields and the today-shift state; it does not expose
  `linked_user_id`, Telegram ids, invite state or private contact fields.
- Catalog may later show a compact line such as `Сегодня: Иван, Алина`.
- Staff profile detail may show display name, role/custom role, subtype fallback, bio, tags and
  photo placeholder or approved photo when future upload exists.
- After bill requested/paid/closed, a future CTA may offer `Поблагодарить сотрудника`.
- Tip CTA must stay separate from order payment and bill close.

## Venue UX

- Section names: `Доступ сотрудников`, `Карточки команды`, and the existing `График смен`.
- In `Доступ сотрудников`, an accepted member is shown as display name plus `@username` or
  `Без username` (with an optional last-four id hint), role badge and link status. Raw full id is
  never the primary label.

  ```text
  Максим Катаев
  @max_kataev · Сотрудник
  ```

  ```text
  Максим Катаев
  Без username · ID …4821 · Сотрудник
  ```

- An unlinked row offers `Создать карточку`; a linked row offers `Открыть карточку`; a duplicate row
  shows `К этому сотруднику привязано несколько карточек. Выберите основную и отвяжите остальные.`
- Profiles are optional.
- Guest sees only published profiles.
- `Сегодня на смене` makes the published profile appear in `Сегодня работают`.
- Create form is collapsed by default behind `Добавить карточку сотрудника`.
- Existing cards are compact by default and show name, role/custom role, published/hidden state,
  today-shift state and actions.
- Full edit form opens only through `Редактировать`.
- `Привязать к сотруднику` uses the same safe identity labels. Already-linked members are excluded
  or disabled with `Уже привязан к карточке «…»`; duplicate targets cannot be selected for a new
  link.
- `Другое` requires `Название роли` with guest-facing custom role copy.
- Raw User ID and raw Photo ref are not visible manual owner inputs.
- Photo upload is future; use a safe placeholder until real upload/media picker exists.
- Owner manages profiles, guest visibility and publish/hide state.
- Manager manages only display-only/Staff-linked cards; Owner/other-Manager cards remain read-only.
- Manager may manage today's active/completed/canceled shift state only for display-only and active
  same-venue Staff-linked profiles; every protected linkage fails closed.
- Staff may edit only own linked draft fields.
- Staff cannot publish themselves or enable guest visibility through the self-edit route.

### Staff Identity Linking UX Acceptance

Backend/repository/API:

1. Accepted Staff projection contains current Telegram display name and nullable username; missing
   username uses safe fallback copy, while pending invites contain no recipient identity.
2. Manager receives active Staff members only as link targets; Owner retains the current permitted
   projection. Staff/Guest/foreign actors cannot read the directory.
3. The projection exposes only the documented safe fields and link state; Guest DTOs remain
   unchanged and contain no member/Telegram identity.
4. Direct create/preselected form targets one active same-venue member, rereads membership and
   identity server-side through `POST /api/venue/{venueId}/staff/profiles/from-member`, whose body
   contains only `userId`, required `subtype` and compatible `roleLabel`. It creates an active draft
   hidden from Guest and never trusts actor, venue, role, Telegram name, display name or visibility
   from the request. Generic profile create is display-only and rejects `linkedUserId`.
5. One existing active profile returns `LINKED`; a second active link is a typed conflict with the
   existing safe profile reference. PostgreSQL Testcontainers concurrency coverage drives two real
   HTTP transactions through both create-from-member and relink paths, proves the target membership
   lock wait without sleeps, and commits one winner plus one typed conflict with winner-only audit.
6. Existing multiple active links return `DUPLICATE_LINK_DETECTED`, create no cleanup mutation and
   remain distinct until explicit safe unlink. Foreign/removed and protected Owner/Manager targets
   are denied under current policy.
7. Successful create/update reuses `STAFF_PROFILE_CREATED` / `STAFF_PROFILE_UPDATED` with safe
   venue/profile/linkage/role-class/changed-field payload. Denial, duplicate and no-op paths write no
   success audit.
8. Existing Staff Schedule, Today Staff, Guest Today and profile RBAC tests remain green.

Mini App/e2e:

1. Accepted members show display name, `@username` or `Без username`, role and link status without
   a raw-id primary label.
2. `Создать карточку` preselects the correct member/display name; `Открыть карточку` opens the one
   existing card, and already-linked members cannot be selected again.
3. Duplicate linkage shows the exact warning and remains resolvable by explicit unlink without
   masking distinct Schedule rows.
4. Manager sees Staff identities/actions but no editable protected Owner/Manager targets; Owner
   retains current controls.
5. Venue/account switch clears directory, selection and profile state and rejects late responses;
   Staff/Guest never receive the internal directory.
6. Existing invites, profiles, Today Shift and Staff Schedule e2e remain green.

## STAFF_SCHEDULE Phase 1 / Optional Venue Shift Planning

Status: **STAFF SCHEDULE PHASE 1 / FUNCTIONALLY PASSED ON STAGING / IDENTITY LINKING FIX IMPLEMENTED / STAGING RE-SMOKE REQUIRED**.

The bounded Restore + Bulk Assignment slice adds explicit canceled-shift restoration and atomic
multi-profile assignment. It is locally validated and still requires green Actions, staging deploy
and a new staging smoke. Staff Identity Linking UX Polish is the latest bounded slice; it reuses the
already implemented schedule/profile rows without changing calculations or lifecycle. This is not
a production-readiness claim. The existing Staff Operations
Slice A invite-revoke V120/H2 V121 rollout remains a separate release gate and is not a migration
for this schedule slice.

`STAFF_SCHEDULE` is an optional Venue Mode module. A venue that does not create shifts continues to
use Staff Profiles, manual Today Shift, Staff Calls, Orders, Bookings and every Guest flow exactly as
today. Phase 1 adds no enable/disable setting: an empty schedule is the disabled/not-used state.
Venue Mini App remains the source of truth.

Migration verdict: **NO_MIGRATION_EXPECTED**.

Implementation verdict:
**STAFF OPERATIONS SLICE A / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

### Current Runtime And Schema Evidence

The runtime reuses the existing foundation rather than introducing a second schedule model:

- PostgreSQL migration
  `backend/app/src/main/resources/db/migration/postgresql/V117__staff_profiles_today_shifts.sql`
  and H2 migration
  `backend/app/src/main/resources/db/migration/h2/V118__staff_profiles_today_shifts.sql` both define
  `staff_profiles` and `staff_shifts` with parity.
- The actual constraint is
  `UNIQUE (staff_profile_id, shift_date)`. It already enforces one shift per profile and venue-local
  start date. The same-venue composite foreign key prevents assigning another venue's profile.
- `shift_date DATE`, `starts_at TIME`, `ends_at TIME`, the four stored status values and
  `updated_at` already exist. Schedule create/update requires both times in application code even
  though legacy Today Shift rows allow null times.
- `staff_profiles.linked_user_id` is nullable, so display-only profiles are already supported.
- There is no `version`, cancellation-reason, end-date, origin or timezone-snapshot column. Phase 1
  does not require one: `updated_at` is the optimistic token, cancellation has no reason field, and
  the next-day end is derived from local times.
- `VenueStaffProfileRepository` has focused bounded list/create/CAS-update/CAS-cancel/CAS-restore
  schedule methods plus one transaction-bound batch path. Schedule CRUD does not use the legacy
  Today upsert as a generic overwrite.
- Today Shift mutations preserve existing planned date/time fields when the request omits them;
  schedule update/cancel preserves manual Today Shift visibility and operational flags.
- Manual Today mutations re-check the actor membership/role, lock and classify the current profile
  linkage, lock/read the existing Today row when present, write only the existing manual fields and
  append the existing Today success audit in one JDBC transaction. Denial or audit failure commits
  neither shift nor audit.
- Guest `Сегодня работают` reads the same table but requires `is_guest_visible=true`, stored
  `scheduled|active`, and a published visible profile. It does not require
  `manually_marked_active`; schedule create explicitly stores `is_guest_visible=false` and
  `manually_marked_active=false` rather than relying on database defaults.
- `STAFF_SHIFT_CREATED`, `STAFF_SHIFT_UPDATED`, `STAFF_SHIFT_CANCELED` and
  `STAFF_SHIFT_RESTORED` use transaction-bound audit writes, so a failed/no-op/stale/denied
  mutation creates no schedule audit. A batch writes one audit row per actual create/restore and
  commits none if any assignment or audit fails.
- `VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE` is `Europe/Moscow`. The generic timezone resolver
  is the explicit fallback for missing/blank/invalid configuration. Schedule resolution does not
  trust browser/system timezone or a client UTC offset.
- `VenueRbac` has explicit full-view, own-view and manage schedule permissions with exact
  permission-set and route denial coverage.

### Bounded Shift Model

- One row represents one interval for one `staff_profile` on the venue-local date when the shift
  starts.
- `shift_date` is always that venue-local start date.
- A shift may end on the next local calendar day.
- A profile may have at most one row for the same `shift_date`; the existing unique constraint is
  the final create-race guard.
- Several employees assigned to one common interval are represented by several ordinary
  `staff_shifts` rows created in one atomic batch. There is no group-shift entity.
- Schedule rows require non-null `starts_at` and `ends_at`.
- `staffProfileId` is immutable after creation. Reassignment is cancel + create, keeping shift
  identity and audit simple.
- There are no recurring templates, multiple intervals per profile/date or split shifts.
- There is no hard delete. Cancellation preserves the row and audit history.
- A canceled row continues to occupy its profile/start-date unique slot. This is the exact former
  product gap: ordinary create hit the unique constraint and returned only a generic date conflict,
  while the schedule API treated the canceled row as immutable, so the employee could not be
  scheduled again on that date.
- Restoration is now an explicit action over the existing `shiftId`; it changes the same row from
  `canceled` to `scheduled`, optionally replaces its interval and never inserts a second row.

### Venue Timezone, DST And Overnight

- All request dates/times are local wall-clock values in the selected venue's timezone.
- The browser timezone, operating-system timezone and a client-supplied UTC offset are never
  authoritative.
- Schedule code resolves the venue zone server-side with an explicit
  `ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE)` fallback. Missing, blank or invalid
  venue timezone therefore falls back to `Europe/Moscow` under the current tested fail-safe policy;
  it must not fall back to `ZoneId.systemDefault()`.
- The API accepts `shiftDate` (`YYYY-MM-DD`), `startsAt` (`HH:mm`) and `endsAt` (`HH:mm`), not a
  trusted offset, UTC instant or client-computed lifecycle status.
- JDBC reads/writes `shift_date`, `starts_at` and `ends_at` as `LocalDate`/`LocalTime`; persistence
  must not round-trip those wall-clock values through the JVM/system timezone.
- If `endsAt > startsAt`, end is on `shift_date`. If `endsAt <= startsAt`, end is on the next local
  date. Equal clock values therefore request a full-day candidate, not a zero-length shift.
- The backend resolves start/end through the venue's zone rules and validates the resulting
  instants. Duration must be strictly positive and at most 24 hours, inclusive. Zero, negative and
  over-24-hour results are rejected.
- A nonexistent local time inside a DST gap is rejected with safe human copy. For an ambiguous
  overlap the server deterministically uses the earlier valid offset. The client never chooses an
  offset.
- Responses include `endsNextDay`; the UI renders an overnight interval explicitly, for example
  `22:00–06:00, следующий день`.
- Owner/Manager schedule GET also returns one `effectiveHours` item per requested service date.
  Date exceptions override weekly hours; venue timezone is authoritative; `closesAt <= opensAt`
  means next day. Successful absence is `NOT_CONFIGURED`, an explicit closure is `CLOSED`, and a
  repository error fails the request instead of pretending hours are not configured.
- Effective hours are create-form defaults only. They never reject a manually entered shift
  outside venue opening hours.
- Create starts in `AUTO`: `OPEN` prefills the configured interval, while `CLOSED` and
  `NOT_CONFIGURED` leave time blank with manual-entry copy. After a user edits either time, date
  changes preserve those `MANUAL` values and offer `Заполнить по часам заведения`.
- Edit always starts from the persisted shift interval. It never replaces stored values
  automatically; applying venue hours is an explicit action.
- Stored values remain venue-local wall-clock values. A later venue-timezone setting change does not
  rewrite rows; future shifts keep their local clock fields and are interpreted by the current
  venue timezone. Mutation audit records the timezone used, and timezone-change UX should prompt an
  Owner/Manager to review upcoming shifts. A per-row timezone snapshot is a future decision, not a
  hidden Phase 1 migration.

### Planning And Read Horizon

- A new shift's resolved start instant must be strictly later than server `now`; backdated creation
  is rejected.
- `shift_date` may be no later than venue-local `today + 90 days`.
- Restore keeps the existing `shift_date`. Its resolved replacement start, whether using the saved
  interval or an explicitly supplied new interval, must also be strictly in the future and inside
  the same 90-day horizon.
- Ordinary update is allowed only while the existing shift is computed `SCHEDULED` and no manual
  Today overlay is engaged. A complete row that is invalid under the current venue-zone rules may
  instead receive one explicit repair update only when its old `shift_date` is after venue-local
  today, stored state is `scheduled`, both Today flags are false and the replacement resolves to a
  future valid interval. Every replacement start must remain within the 90-day horizon.
- Every list API requires inclusive `from` and `to` local dates. `from <= to` and the requested span
  is at most 31 calendar days.
- Owner/Manager and Staff may page within `today - 30 days` through `today + 90 days`; dates outside
  that envelope and oversized periods are rejected instead of silently clamped.
- Top-level rows are selected by `shift_date` inside `from..to`. For each own row, same-shift
  colleague lookup considers candidate start dates from `ownShiftDate - 1 day` through
  `ownShiftDate + 1 day`: a colleague may have begun overnight before it or may start after midnight
  while the own shift is still active. The 24-hour maximum keeps this join bounded.

### Lifecycle And Mutations

Canonical lifecycle names are `SCHEDULED`, `ACTIVE`, `COMPLETED`, `CANCELED`; wire/database values
remain lowercase to match current conventions.

- Schedule create stores `scheduled`.
- Cancel stores `canceled`; canceled takes precedence over time-derived state.
- For a complete interval that is not canceled, the API derives `scheduled`, `active` or
  `completed` from server `now` and the venue-zone resolved start/end instants: `now < start` is
  `SCHEDULED`, `start <= now < end` is `ACTIVE`, and `now >= end` is `COMPLETED`.
- No scheduler worker is required in Phase 1. The stored row does not need periodic status writes.
- Each request captures one injected-`Clock` `now` and reuses it for lifecycle, horizon, mutation
  authorization, response and audit calculations.
- Legacy Today rows with either time missing remain Today-only records and are not returned as full
  planned schedule entries. Any complete row that cannot resolve under the current venue timezone
  and DST/duration rules fails closed for Staff schedule/overlap reads; Owner/Manager receives a safe
  `STAFF_SHIFT_INVALID_INTERVAL` warning with local fields and exact `allowedActions` instead of a
  crash, origin guess or silent reinterpretation.
- Create: Owner/Manager creates a future row only when no profile/date row exists. It never silently
  restores a canceled row.
- Update: Owner/Manager may change only `shiftDate`, `startsAt` and `endsAt` while computed status is
  `SCHEDULED` and the shared row still has its schedule defaults: stored `scheduled`,
  `is_guest_visible=false`, `manually_marked_active=false`. The explicit invalid-interval repair
  follows the same defaults, stale guard and future/horizon validation without inventing a computed
  lifecycle for the invalid old interval.
- Active: time editing is forbidden. Owner/Manager may cancel only after an explicit confirmation
  that the active shift is being ended in the plan.
- Completed: immutable; no update or cancel.
- Canceled: a future row with schedule visibility defaults may be restored only through explicit
  `Восстановить смену`. Restore requires exact `expectedUpdatedAt`, keeps the existing `shiftId` and
  `shift_date`, changes `canceled -> scheduled`, and either keeps both saved times or replaces both
  times explicitly. A past canceled row, an invalid/stale token, a Today-overlay row and every
  non-canceled lifecycle fail closed. On the venue-local current date, the existing manual Today
  action remains a separate explicit operational override under its current contract.
- Invalid complete interval: a row dated after venue-local today may be repaired under the strict
  defaults above or, if not already canceled, canceled; a non-canceled row dated today may only be
  canceled with the invalid-state warning; a past row is immutable. An already-canceled invalid row
  cannot prove that its saved start remains future and is therefore not restorable. All paths require
  CAS, confirmation where applicable and safe audit.
- No-op update returns the unchanged safe DTO and writes neither `updated_at` nor audit.

### Today Shift Compatibility

Manual Today Shift remains the sole source of Guest `Сегодня работают` in Phase 1.

- Schedule create/update never promotes Guest/Today state: create writes `is_guest_visible=false`
  and `manually_marked_active=false` explicitly, while schedule update preserves the existing Today
  overlay fields.
- Restore is available only for a canceled row that still has the schedule defaults
  `is_guest_visible=false` and `manually_marked_active=false`. It therefore cannot republish a prior
  Today overlay or erase manual Today state as a side effect. A restored venue-local-today row is
  still only a planned schedule row; publication remains the separate existing Today action.
- Reaching the scheduled start time does not publish the profile to Guest and does not change the
  Guest API/read model.
- The existing Today Shift route, current Today controls and Guest routes keep their current product
  contract. There is no schedule-to-Guest sync, worker or hidden conflict resolver.
- Because the unique constraint intentionally makes schedule and Today share one profile/date row,
  a manual Today action on a planned row is the explicit operational override for that date and may
  replace its stored Today status, including a prior `canceled` value. The schedule admin read model
  must surface the current manual guest-visibility/override state; it may not pretend schedule and
  Today are independent records or reconstruct the superseded state from audit.
- Once that Today overlay is engaged (`is_guest_visible=true`, `manually_marked_active=true` or
  stored status other than `scheduled`), Schedule date/time update is denied even if the interval is
  still time-derived `SCHEDULED`. This prevents moving a Guest-visible/active Today overlay to a
  future date. Explicit Schedule cancel remains available under the lifecycle/confirmation rules.
- Canceling a venue-local Today row through Schedule preserves its Guest flag but stores `canceled`,
  so the unchanged Guest query stops returning it. The confirmation summary must say this when the
  current Today overlay is Guest-visible; a later explicit manual Today action may publish it again.
- The Today repository path must preserve non-null planned `starts_at`/`ends_at` when the Today
  request omits them. This is a compatibility guard, not a new Today feature.
- Current manual audit actions remain separate from the new schedule create/update/cancel audit.
- Migrating Guest `Сегодня работают` to schedule-derived presence requires a separate future slice,
  conflict policy, regression coverage and staging smoke.

### RBAC

| Actor | Phase 1 permission | Scope |
| --- | --- | --- |
| Venue Owner | Read full bounded schedule; create one/batch, restore, update and cancel. | Own venue only. |
| Venue Manager | Same operational schedule management as Owner. | Own venue only. |
| Staff | Read own shifts and safe colleagues whose non-canceled intervals overlap that own shift. | Own linked profiles in the selected venue only. |
| Foreign venue user | Denied. | No cross-venue read or mutation. |
| Guest | Denied from all schedule APIs. | `Сегодня работают` remains the separate public read. |
| Platform Owner | No automatic Phase 1 schedule authority. | A real venue membership is still required. |

Runtime permissions are separate `STAFF_SCHEDULE_VIEW`,
`STAFF_SCHEDULE_VIEW_OWN` and `STAFF_SCHEDULE_MANAGE` values. Owner/Manager receive full view and
manage; Staff receives own view only. UI hiding is convenience; routes must re-check membership,
venue ownership, profile ownership and lifecycle.

### Staff Visibility And Display-Only Profiles

- Staff self-view is derived from the authenticated user and all own-venue profiles whose
  `linked_user_id` equals that user. The request never supplies a trusted user id.
- For each own non-canceled shift, colleagues are profiles with non-canceled intervals satisfying
  `ownStart < colleagueEnd && colleagueStart < ownEnd`. Touching boundaries do not overlap.
- The colleague side excludes every profile linked to the authenticated user, not only the current
  own row; a user's second linked profile must never return that same user as their own colleague.
- A canceled own shift has no working-together colleague projection.
- A colleague projection contains only the safe profile identity `staffProfileId`, `displayName`,
  `roleLabel`, `subtype`, local start/end, `endsNextDay` and computed lifecycle status.
- Staff receives no full venue roster or schedule on dates where they have no own shift.
- Staff DTOs contain no `linked_user_id`, venue-member/Telegram user id, Telegram username, invite
  state, photo ref, bio/private notes, actor ids, audit metadata, guest-publication flags or
  `updatedAt` concurrency token.
- A display-only profile can be scheduled and appears to Owner/Manager and safe overlapping
  colleagues by display name/role. It has no login/self-view because it has no linked user.
- Guest visibility of that profile remains controlled independently by the existing profile and
  manual Today Shift settings. Schedule presence never publishes it.
- Phase 1 adds no venue visibility toggle for colleagues.

### Runtime API Contract

The most compatible resource namespace extends the existing `/staff/shifts/today` collection.
`PUT` is intentional for schedule update: all three mutable date/time fields are required and
replace the interval as one unit. The Today wrapper instead preserves planned times when they are
omitted. Existing Staff Profile `PATCH` remains appropriate for its partial-field contract;
Schedule does not copy that behavior.

| Method | Route | Access | Contract |
| --- | --- | --- | --- |
| `GET` | `/api/venue/{venueId}/staff/shifts?from=&to=` | Owner/Manager | Full bounded schedule plus batched effective venue hours for every requested date. |
| `POST` | `/api/venue/{venueId}/staff/shifts` | Owner/Manager | Create one future shift. |
| `POST` | `/api/venue/{venueId}/staff/shifts/batch` | Owner/Manager | Atomically apply 1..50 normalized `CREATE`/`RESTORE` assignments. |
| `PUT` | `/api/venue/{venueId}/staff/shifts/{shiftId}` | Owner/Manager | Replace mutable date/time fields with optimistic token. |
| `POST` | `/api/venue/{venueId}/staff/shifts/{shiftId}/restore` | Owner/Manager | Explicitly restore one future canceled shift with optimistic token and optional replacement interval. |
| `POST` | `/api/venue/{venueId}/staff/shifts/{shiftId}/cancel` | Owner/Manager | Cancel with optimistic token. |
| `GET` | `/api/venue/{venueId}/staff/shifts/me?from=&to=` | Staff | Own shifts plus safe overlapping colleagues. |

Create request:

```json
{
  "staffProfileId": 42,
  "shiftDate": "2026-08-10",
  "startsAt": "22:00",
  "endsAt": "06:00"
}
```

Update request; `staffProfileId` is intentionally immutable:

```json
{
  "shiftDate": "2026-08-11",
  "startsAt": "21:00",
  "endsAt": "05:00",
  "expectedUpdatedAt": "2026-08-01T10:15:30Z"
}
```

Cancel request:

```json
{
  "expectedUpdatedAt": "2026-08-01T10:15:30Z",
  "expectedConfirmationState": "SCHEDULED"
}
```

Restore request. `startsAt` and `endsAt` are omitted together to reuse the saved interval or supplied
together to restore with new times:

```json
{
  "expectedUpdatedAt": "2026-08-01T10:15:30Z",
  "startsAt": "20:00",
  "endsAt": "04:00"
}
```

Batch request. The client normalizes the common date/default interval into every assignment;
`RESTORE` requires `expectedUpdatedAt`, while `CREATE` must not send it:

```json
{
  "assignments": [
    {
      "staffProfileId": 42,
      "shiftDate": "2026-08-10",
      "startsAt": "18:00",
      "endsAt": "02:00",
      "operation": "CREATE"
    },
    {
      "staffProfileId": 43,
      "shiftDate": "2026-08-10",
      "startsAt": "20:00",
      "endsAt": "00:00",
      "operation": "RESTORE",
      "expectedUpdatedAt": "2026-08-01T10:15:30Z"
    }
  ]
}
```

Owner/Manager shift DTO contains shift id, safe staff-profile id/display name/role/type, local
date/time, `endsNextDay`, nullable `computedStatus`, server-derived nullable
`cancelConfirmationState`, venue timezone and `updatedAt`. For the shared-row compatibility case it
also returns admin-only `storedStatus`, `isGuestVisible` and `manuallyMarkedActive`, so a Today
override is explicit. It returns server-derived `restoreAllowed` only for a future canceled row that
passes the restore preconditions. An unresolvable non-canceled complete row has no invented status and returns
safe `STAFF_SHIFT_INVALID_INTERVAL` warning/allowed-actions metadata with cancel confirmation state
`INVALID_INTERVAL` when cancel is allowed. The DTO omits linked users, Telegram data and actor
metadata. Staff uses a separate DTO: own shift identity/date/time/computed status/venue plus nested
safe colleagues. A colleague's safe `staffProfileId` is present; invalid rows, shift-row ids,
linked-user/account/Telegram ids, Today overlay and admin/optimistic fields are absent.

Requests must not accept `actorUserId`, owner id, arbitrary venue id, Telegram identifiers,
`isGuestVisible`, `manuallyMarkedActive`, trusted lifecycle status, timezone, UTC offset or computed
duration. Cancel's `expectedConfirmationState` is not trusted state or desired status: it is an
optimistic confirmation precondition (`SCHEDULED`, `ACTIVE` or `INVALID_INTERVAL`) that the server
must compare with a fresh computation and reject on mismatch. `venueId` comes only from the scoped
route and is checked against membership. Every shift-by-id repository query/update uses both
`venue_id` and `id`.

Ordinary create classifies an authorized existing profile/date row without disclosing private
linkage: canceled returns typed `STAFF_SHIFT_CANCELED_CONFLICT` (`409`) with safe
`existingShiftId`, `staffProfileId`, lifecycle status, `expectedUpdatedAt`, date/time,
`endsNextDay` and `canRestore`; scheduled returns `STAFF_SHIFT_DATE_CONFLICT` (`409`) with
`Смена уже запланирована на эту дату.`; active/completed return the current immutable-policy denial.
Foreign/unauthorized actors receive only the normal scoped denial and no existing-row details.

Other expected domain failures include `STAFF_SHIFT_DATE_CONFLICT` (`409`) for a date-changing
update, invalid/bounded range, past or over-90-day shift,
`STAFF_SHIFT_TODAY_OVERRIDE` (`409`) for an engaged Today overlay, invalid timezone-resolved
interval, immutable lifecycle, `STAFF_SHIFT_CONFIRMATION_STALE` (`409`) when time-derived state
changed after cancel preview, and `STAFF_SHIFT_STALE` (`409`). Unknown/foreign ids fail safely
without disclosing another venue.

### Optimistic Concurrency And Atomicity

- Update, cancel and restore require the exact `updatedAt` returned by the admin DTO. Every batch
  `RESTORE` carries the token for its own row.
- Inside one transaction, select the own-venue row, compare the token and normalized desired
  fields, validate lifecycle/horizon, apply `UPDATE ... WHERE venue_id=? AND id=? AND updated_at=?`,
  and append audit through the same connection.
- Every real Schedule mutation, and every manual Today write touching a complete planned row, must
  advance `updated_at` to a round-tripped database token different from the previous token. Do not
  assume `CURRENT_TIMESTAMP` precision alone is sufficient across PostgreSQL and H2.
- A stale token rejects the whole mutation with `409 STAFF_SHIFT_STALE`; no field changes and no
  audit are committed. UI offers refresh and never silently retries an edit.
- Cancel recomputes `expectedConfirmationState` with the same request `now` before authorization.
  If a previewed shift crossed `SCHEDULED -> ACTIVE`, the whole request fails with
  `409 STAFF_SHIFT_CONFIRMATION_STALE`; UI refreshes and requires the stronger active confirmation.
- A concurrent create or date-changing update that violates
  `staff_shifts_one_per_profile_date` maps to a safe duplicate conflict, not generic
  database-unavailable.
- Batch size is bounded to 1..50 and duplicate `(staffProfileId, shiftDate)` slots inside one
  request are rejected before writes. All profiles must resolve inside the selected venue;
  display-only and Staff-linked profiles remain valid schedule targets. Owner/Manager-linked
  protected profiles follow the existing schedule-management policy; batch does not widen Manager
  profile editing, role or membership authority.
- One database transaction validates the complete batch before mutation, then applies all creates
  and restores and appends all per-row audits. One missing/foreign profile, invalid interval,
  scheduled/active/completed conflict, stale restore or audit failure rolls back every row and every
  audit; partial success is impossible.
- Database locks use the deterministic order `staff profiles by id -> existing staff_shift rows by
  profile/date -> create/restore writes -> audit rows -> commit`. The implementation sorts by
  `staffProfileId`, then `shiftDate`; it does not use a process-local lock.
- `updated_at` is sufficient for Phase 1. Persist an application-generated token strictly later
  than the old value at a precision round-tripped identically by PostgreSQL and H2, return that
  exact persisted value, and test two mutations with one expected token: exactly one commits. A
  compatible algorithm is
  `max(clockNow.truncatedTo(MILLIS), oldUpdatedAt.truncatedTo(MILLIS) + 1 ms)`, bound explicitly in
  the CAS update and then read back. The request contract has no expected-current-fields fallback.
  Do not add a hidden version migration.

### Audit And Confirmations

Schedule mutation and audit are one transaction. Required exact actions:

- `STAFF_SHIFT_CREATED`;
- `STAFF_SHIFT_UPDATED`;
- `STAFF_SHIFT_CANCELED`;
- `STAFF_SHIFT_RESTORED`.

Actor identity is stored through the audit actor column. Safe payload contains `venueId`,
`staffProfileId`, `shiftId`, old/new `shiftDate`,
old/new `startsAt`, old/new `endsAt`, old/new computed lifecycle state and `venueTimezone`. Creation
uses null old values; cancellation uses the unchanged interval plus old/new lifecycle. Phase 1 has
no cancellation reason, so no reason field is accepted or audited. For an invalid-row repair or
cancel, unavailable old computed lifecycle is null and safe `oldValidationState=INVALID_INTERVAL`
explains why; it is never guessed.

Restore records `CANCELED -> SCHEDULED` with the saved or explicitly replaced old/new interval.
Batch writes one `STAFF_SHIFT_CREATED` or `STAFF_SHIFT_RESTORED` row for each actual assignment and
no aggregate success audit. Audit failure rolls back the corresponding schedule writes.

Never include Telegram ids/usernames, linked user id, invite state, private profile fields, payroll,
raw request body or unrelated PII. Update form shows an old-to-new date/time summary before submit;
cancel always requires confirmation, with stronger copy for an active shift. Confirmation is UX,
not authorization: backend revalidates all state. A no-op update produces no false audit.

### Owner / Manager UX

- Venue Mode section: `График смен` under `Работа смены`.
- Use a compact Monday-Sunday week list, not drag-and-drop: week navigation, day groups, employee
  display name/role, interval, lifecycle status and overnight marker.
- Actions: `Добавить смену`, `Редактировать`, `Отменить` and explicit `Восстановить` according to
  lifecycle. A future canceled historical row keeps its safe old interval and `Отменена` badge.
- `Добавить смену` is a group-assignment form: date, effective venue hours, common default interval,
  multi-select over Staff/display-only profiles and one selected-employee row with editable start/end
  for each profile. The user can `Применить общее время всем`, override one employee, remove an
  employee, `Проверить смены` and then `Создать смены`.
- Existing edit remains a one-shift form with the employee locked. Mass edit of saved scheduled
  shifts is not part of this slice.
- When end is not after start by wall clock, preview says it ends next day. Confirmation summary
  uses copy such as `22:00–06:00, следующий день`.
- Loading, empty, retryable error and stale-edit states are distinct. Empty copy explains that the
  optional graph is unused and does not block current operations.
- Preflight classifies every selected profile/date. A canceled row says
  `Смена на эту дату была отменена.` and requires explicit `Восстановить` or removal from the batch;
  it is never restored silently. Scheduled/active/completed conflicts identify the employee with a
  safe reason, block confirmation and prevent a partial request. Confirmation shows exact
  `Будет создано: N` / `Будет восстановлено: N` counts and submits one batch request.
- An engaged manual Today overlay removes the time-edit action and explains that `Сегодня на смене`
  already controls this shared row; explicit cancel remains available with its impact summary.
- `STAFF_SHIFT_INVALID_INTERVAL` renders a safe admin warning. A row dated after venue-local today
  with schedule defaults offers repair/cancel; a row dated today offers cancel; a past row is
  read-only. The UI never guesses lifecycle or origin.
- Update preview and cancel confirmation happen before mutation. A stale response shows
  `График изменился. Обновите данные и повторите.` with refresh action.
- If the shift crosses a lifecycle boundary after cancel preview, the UI refreshes the row and asks
  for the confirmation appropriate to the new state; it never upgrades a scheduled confirmation to
  an active cancellation silently.
- Venue switch disposes/aborts the old request, clears rows/form/confirmation, and ignores late
  responses. It also clears employee selection, per-profile overrides, batch confirmation and stale
  conflict details. Reuse current AbortController + sequence guards.
- The selected venue remains selected through section/week navigation and reload. Reuse the
  existing Venue Mode selector and persist its `venueId` in the sanctioned navigation context;
  restore it only after revalidating it against the fresh membership list, otherwise clear it and
  select an allowed venue.

### Staff UX

- Venue Mode section label for Staff: `Мои смены`, also under `Работа смены`.
- Read-only week list shows venue, date, local start/end, overnight marker, status and colleagues
  overlapping that particular shift.
- Empty state is safe when no linked profile or no shifts exist.
- There are no edit/cancel buttons, admin forms, full venue schedule, colleague rows outside an own
  overlap, private account/Telegram data or Today guest-publication controls.

### Implementation Acceptance Matrix

Backend/repository/API:

1. Owner can create, update and cancel.
2. Manager can create, update and cancel.
3. Staff mutation is denied.
4. Staff self read is allowed.
5. Staff sees safe same-shift colleagues, including overnight overlap, and never sees their own
   current/second linked profile as a colleague.
6. Staff cannot see unrelated venue shifts or colleagues outside an own overlap.
7. Foreign venue access is denied.
8. Guest access is denied.
9. Display-only staff profile can be scheduled and rendered safely.
10. Local date/time uses the venue timezone and explicit Moscow fail-safe, never client/system zone.
11. Overnight resolution and `endsNextDay` are correct.
12. Zero/non-positive and resolved over-24-hour duration is rejected; an existing invalid complete
    row fails closed and follows the future repair/cancel, today cancel-only, past read-only policy.
13. Past start is rejected.
14. More than 90 days ahead is rejected; list periods are bounded.
15. One shift per profile/start-date is enforced and create/update races return conflict.
16. Completed shifts are immutable; canceled shifts are historical rows and only a future
    schedule-default row can use the explicit CAS restore action.
17. Active shift cannot be edited and can be canceled only through the stronger explicit action;
    crossing `SCHEDULED -> ACTIVE` after preview forces refresh and reconfirmation.
18. Stale update/cancel is rejected atomically; two mutations with one token commit exactly one,
    and a Today write invalidates an open Schedule editor.
19. Create/update/cancel/restore writes one safe transaction-bound audit; no-op writes none. Batch
    audit count and action match its created/restored row count.
20. Staff/colleague DTOs contain no private linkage, Telegram or actor metadata.
21. Today Shift and Guest `Сегодня работают` remain manual/unchanged; planned times survive Today
    requests that omit them, schedule rows never auto-publish, an engaged Today overlay blocks
    Schedule date/time moves, and the documented manual-Today override/cancel interaction is
    explicit and routed through the existing Today audit path.
22. Restore keeps the same `shiftId`, creates no second row, supports saved or new times, advances
    the CAS token and records `STAFF_SHIFT_RESTORED` in the same transaction.
23. Restore rejects stale, scheduled, active, completed, past canceled, foreign and unauthorized
    requests; an audit failure rolls it back and Guest Today remains unchanged.
24. Authorized ordinary create returns lifecycle-specific typed conflict details for an occupied
    safe slot; foreign actors receive no row details.
25. Batch creates several profiles with common or per-profile intervals and may mix `CREATE` with
    `RESTORE`.
26. Duplicate slots, missing/foreign profiles, invalid intervals, oversized requests, scheduled
    conflicts and stale restores reject the whole batch with zero partial rows and audits.
27. Concurrent create/restore and competing CAS mutations have one deterministic winner; tests use
    deterministic barriers/concurrency fixtures, not arbitrary sleeps.
28. The existing single-profile create route, individual edit/cancel/CAS, Today planned/manual
    compatibility and Staff self-view stay green.

Mini App/e2e:

1. Owner week list/editor with multi-select assignment.
2. Manager week list/editor with the same batch/restore controls.
3. Staff read-only `Мои смены`.
4. Same-shift colleagues only.
5. Empty/loading/error states.
6. Overnight rendering.
7. Week navigation and bounded queries.
8. Cancel confirmation, including active warning; future canceled rows remain visible with
   `Отменена`, safe old interval and explicit restore.
9. Stale error and refresh.
10. Venue switch clears stale data and late responses; the selected venue survives navigation and
    reload only after fresh access-list validation.
11. Staff direct mutation is denied even if called outside UI.
12. Existing Today Shift and Staff Profile flows remain green.
13. Guest `Сегодня работают` remains unchanged and receives no planned/restored shift automatically.
14. Common effective hours, apply-to-all, per-employee override and removal are deterministic; closed
    and not-configured dates preserve the existing manual-entry behavior.
15. Preflight shows canceled restore choice, blocks scheduled/active/completed conflicts, reports
    create/restore counts and sends exactly one normalized batch request.
16. Atomic error leaves every row unchanged; success refreshes the week.
17. Existing individual edit/cancel and Staff no-batch/no-restore controls remain green.

Telegram:

- No new Telegram flow, reminder, outbox event, button or mutation UI.
- Current Today Shift Telegram behavior remains unchanged.
- Staff-chat remains notification/radar/shortcut for existing domains and receives no schedule
  notification in Phase 1.

### Runtime Implementation Files

Backend:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffScheduleRoutes.kt`,
  mounted from `backend/app/src/main/kotlin/com/hookah/platform/backend/Application.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/VenueStaffScheduleDomain.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/VenueStaffProfileRepository.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueRbac.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/api/ApiErrors.kt` and exception mapping for
  conflict/stale codes;
- `VenueStaffRoutesTestSchedule.kt`, `VenueStaffRoutesTestTimeContract.kt`, the existing
  `VenueStaffRoutesTest.kt`, exact RBAC permission tests and `GuestVenueRoutesTest` regression.

Mini App:

- `miniapp/src/screens/venueApp.ts`;
- a focused `miniapp/src/screens/venueStaffSchedule.ts`;
- `miniapp/src/shared/api/venueApi.ts` and `venueDtos.ts`;
- `miniapp/src/style.css`;
- `miniapp/e2e/guest-smoke.spec.ts`.

The implementation reuses `staff_profiles`, `staff_shifts` and
`VenueStaffProfileRepository`, with explicit bounded list/create/CAS-update/CAS-cancel/CAS-restore
and atomic batch methods.
`upsertTodayShift` remains the focused Today wrapper with preserve-on-omission compatibility, while
schedule lifecycle/horizon logic uses an injected `Clock` for deterministic tests.

### Validation Evidence And Remaining Release Gates

Local:

```bash
git status --short
git diff --check
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueStaffRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*VenueRbacRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:test --tests '*GuestVenueRoutesTest*' --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:compileKotlin --console=plain
./gradlew --no-daemon --max-workers=1 :backend:app:ktlintCheck --console=plain
npm --prefix miniapp run build
CI=1 TZ=UTC MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke
```

The commands above are the required local gate for Staff Identity Linking UX Polish and the
unchanged Restore + Bulk Assignment regression. Record exact command results from the current
worktree in the implementation handoff; do not reuse an earlier Slice A browser count as evidence
for this slice.

Remaining release gates:

- green GitHub Actions before release;
- staging deploy after green Actions;
- accepted-name, username/missing-username, create/open and already-linked manual smoke;
- duplicate warning, explicit unlink and deterministic double-link race smoke;
- venue/account-switch identity and selection isolation smoke;
- Owner, Manager and Staff manual smoke;
- overnight/timezone/DST smoke;
- two-account Staff visibility smoke;
- venue-switch stale-response smoke;
- explicit old-time/new-time restore plus mixed atomic create/restore batch smoke;
- canceled/scheduled conflict presentation, create/restore confirmation counts and one-request proof;
- Today Shift/Guest regression;
- cleanup of disposable test shifts/profiles.

### Explicit Out Of Scope

- payroll, rates/salary and tips/payments;
- attendance, clock-in or actual arrival tracking;
- recurring templates, multiple shifts per day and split shifts;
- availability/preferences, open-shift signup, employee confirmations, swaps and substitutions;
- reminders, Telegram notifications/buttons/mutation UI and a separate staff communication chat;
- leave/vacation;
- Guest `Сегодня работают` migration;
- staff media/photos and Media Upload/R2/object storage;
- Catalog, Menu or Promotions changes;
- a Phase 1 schedule enable/disable setting.

### Non-Blocking Follow-Ups

- A future per-row timezone snapshot may be reconsidered only with a separate schema decision if
  venues changing timezone make the current local-wall-time policy insufficient.
- A future Guest schedule source needs a separate conflict/publication policy and staging smoke.
- Cancellation reasons, recurring templates, notifications, confirmations and swaps remain
  separate product slices. None blocks Phase 1 implementation.

## Staff Communication And Shift Sign-Up Future

Shift confirmation/sign-up through Telegram is a future/open decision:
- Employees could confirm shifts or request swaps through personal Telegram bot notifications.
- Staff-chat must not become the source of truth for schedule state.
- Venue Mode schedule remains authoritative.
- Separate staff communication chat is not MVP.

Possible future options:
- Keep one staff-chat with forum topics.
- Use personal bot notifications for shift reminders and confirmations.
- Add a dedicated staff communication group only for larger venues.

Current recommendation: do not add a second group in Schedule Phase 1. Reconsider personal bot
notifications only in a later bounded slice after the Venue Mode runtime is implemented and smoked.

## Photo Upload Future

- `docs/MEDIA_STORAGE_UPLOAD.md` defines the venue info-section foundation only. It must not bypass
  the employee consent, moderation, visibility and deletion decisions required here.
- Current status: **FUTURE**. No supported Bot or Venue Mini App file picker/upload/manage flow
  exists. Venue Mini App shows `Фото сотрудника — позже`; raw manual Photo ref input is hidden.
- The nullable `photo_ref` data/API field is not a safe upload pipeline. Current Guest UI renders
  an initials placeholder instead of using it, and both Guest Preview modes omit the raw photo ref.
- Future profile photos need safe media upload/photo picker, not manual raw `photo_ref` entry.
- Employee consent is required before showing a public profile photo.
- Storage, moderation, replacement and deletion rules must be specified before broad rollout.
- Guest UI uses a placeholder or approved public photo only.
- Staff photos remain separate from venue/public-card/menu media even if a future shared storage
  abstraction is reused.

## STAFF_TIP Future

Phase 2 target:
- external staff tip link stored as a moderated/approved staff tip method;
- `staff_tip_intent` created before opening/clicking the external link;
- recipient label must clearly identify the specific staff member.

Rules:
- Money does not touch the platform in MVP.
- Tip intent is not proof of payment.
- Provider/direct payout is Phase 3+ only after legal/product decision.
- Platform-collects-and-later-pays-out is not recommended for MVP.
- Staff tips are for a specific staff member/profile, not only the venue.
- Telegram Stars tips are future, not MVP. Stars may be useful later only after the legal/payment
  flow and recipient model are decided.
- Crypto tips are future/not MVP.
- Do not mix guest order payment, venue subscription billing and staff tips.

## RBAC

- Guest views only public visible profiles and shifts.
- Guest may create a future tip intent only for a visible, `tips_enabled` staff profile.
- Owner manages profiles, publish/hide state, optional own-venue schedule and future tip-method
  approval.
- Manager manages display-only/Staff-linked profiles and their publish/hide state, cannot mutate
  Owner/other-Manager linkage or visibility, and keeps safe self-edit for their own linked card.
- Manager receives active Staff identities only in the private directory/link selector and may
  create/open/link/unlink only Staff cards. Owner retains the broader current policy and
  last-owner/protected constraints.
- Manager may change manual Today Shift only for display-only and active same-venue Staff-linked
  profiles. Owner/Manager-linked, orphaned, missing, inactive/removed and foreign linkages are
  protected. Manager receives the same own-venue planned-shift management as Owner.
- Staff edits only own linked draft fields; Schedule Phase 1 adds read-own and safe overlapping
  colleague visibility, never schedule mutation.
- Platform Owner may later moderate/disable unsafe public profiles or tip methods.
- Venue Mini App invite create/revoke and dedicated profile create/update/publish/hide routes write
  transaction-bound safe audit:
  `STAFF_INVITE_CREATED`, `STAFF_INVITE_REVOKED`, `STAFF_PROFILE_CREATED`,
  `STAFF_PROFILE_UPDATED`, `STAFF_PROFILE_PUBLISHED`, `STAFF_PROFILE_HIDDEN`. Payloads contain only
  venue/entity identity, opaque invite handle, safe role/linkage class, changed field names and
  old/new publication/linkage state; no code/hash, Telegram identity, raw content or photo ref.
- Telegram Bot invite audit parity and feature-specific generic PATCH visibility action taxonomy
  remain P2 follow-ups; this Slice A does not claim them closed.
- Schedule create/update/cancel keeps the transaction-bound audit contract above.
- Duplicate-link denial is not a successful profile mutation and writes no success audit.

## Privacy And Security

- No phone/email public by default.
- Guest/public surfaces contain no raw Telegram username. The authenticated Owner/Manager staff
  directory may show the current safe `@username` for identity linking; it exposes no phone or
  other private Telegram data and is unavailable to Staff/Guest.
- `linked_user_id` is not exposed through guest APIs.
- Schedule Staff DTOs also exclude linked users, Telegram/member ids, usernames, invite state,
  actor metadata, private notes and non-overlapping venue schedule data.
- External tip links require moderation/allowlist later.
- Photo consent and moderation are future requirements before broad rollout.
- Rate-limit public profile/tip actions where relevant.
- Analytics payloads must not include raw Telegram payloads, payment secrets, card data, private contacts or unrelated PII.

## Analytics Events

Target events:
- `staff_profile_viewed`
- `staff_shift_viewed`
- `staff_profile_published`
- `staff_shift_marked_active`
- `staff_schedule_shift_created` - future; Phase 1 requires audit actions, not a new analytics event
- `staff_shift_confirmed` - future
- `staff_tip_intent_created` - future
- `staff_tip_clicked` - future

Analytics events are not the source of truth. Domain tables and audit logs remain authoritative.

## Roadmap Status

- Staff profiles: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Today on shift: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Staff profile UX polish: `DONE / MVP / STAGING-SMOKE-PASSED`.
- Staff Identity Linking UX + Duplicate Prevention:
  `MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`.
- Photo upload/media picker: `FUTURE`.
- Venue info-section media decision does not close staff-photo scope; see
  `docs/MEDIA_STORAGE_UPLOAD.md`.
- Staff Operations Slice A:
  `MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT`;
  scope is `MANAGER PARITY + SHIFT TIME DEFAULTS`.
- Staff schedule:
  `STAFF SCHEDULE PHASE 1 / FUNCTIONALLY PASSED ON STAGING / IDENTITY LINKING FIX IMPLEMENTED / STAGING RE-SMOKE REQUIRED`;
  Restore + Bulk Assignment remains implemented and the schedule model itself remains
  `NO_MIGRATION_EXPECTED`. Identity linking also requires no migration because mutations serialize
  on the existing `venue_members` row.
- Optional Team/Schedule module settings and a Guest `MANUAL`/`SCHEDULE` source switch are Slice B
  `FUTURE`; Restore + Bulk Assignment and Staff Identity Linking UX Polish add neither.
- Staff shift Telegram notifications/sign-up/swaps: `FUTURE`.
- Separate staff communication chat/forum topics: `OPEN DECISION / FUTURE`.
- Staff tips: `SPEC DRAFT / FUTURE`.
- External staff tip link + staff tip intent: `SPEC DRAFT / FUTURE`.
- Telegram Stars / crypto tips: `FUTURE / not MVP`.
- Payments for tips: `FUTURE / needs legal/payment decision`.
- Guest order online payment: not in scope; order payment remains the offline terminal model.

## Next Release Step

Wait for green GitHub Actions, deploy the runtime change and run the listed identity/linking,
duplicate/race/unlink, Owner/Manager/Staff, restore/batch atomicity,
effective-hours/timezone/overnight/privacy, venue-switch/account-switch and Today/Guest
regression smoke. Do not claim production readiness until those gates pass. The existing
PostgreSQL V120 invite-revoke rollout/drain requirement belongs to Staff Operations Slice A. The
Restore + Bulk Assignment and identity-linking slices add no migration and do not change existing
schedule constraints. Do not enable the new linking UI while an old runtime without the duplicate
check can still accept profile writes. Keep Slice B module/source settings, tips, photo upload,
staff communication/chat/sign-up and every payment path out of scope.
