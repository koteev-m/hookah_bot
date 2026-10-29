# UX08 staff UI and consent — local task evidence

Task: `HT-UX08-STAFF-UI-CONSENT-FIX-01`, project `Hookah_Tootah`, repo `hookah_bot_ANT`.
Date: 2026-10-29. Scope: local code/tests/docs only; no commit/push/PR/SSH/deploy/live writes.

## Identity and starting state

Preflight PASS. Physical cwd/root `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`;
Git dir/common `.git`, index `.git/index`, only registered checkout at that root. No affecting
Git overrides, config includes, core.worktree/hooksPath, alternates or foreign instruction authority.
Origin fetch/push normalized to `github.com/koteev-m/hookah_bot`. Root AGENTS.md matches supplied
instructions; global AGENTS.md empty, no ancestor/narrower overrides or configured fallbacks found.
Starting HEAD `6951f468a9f86cfb246d9e1e32593ba0c4d6cec2`, branch
`codex/ht-ux08-r2-ht70-config`. EXPECTED_HEAD/BASE/branch/parent constraints NOT_SUPPLIED.
Initial `git status --short`: `?? scripts/dev/`. No branch/ref/stash/index operations or edits to
scripts/dev/SQL helpers. User-reported staging release `c805d4d0af63b0c4135eb895741ae2559fe47afb`
and PostgreSQL V127/deploy/R2 smoke are supplied history, not independently verified here.

## Diagnosis and exact boundary

Proven UI gaps: Manager was excluded from `my-profile` although existing server self-photo rights
allow it; all management cards eagerly mounted photo panels; file selection required manual upload.
An already-mounted Owner photo panel did not refresh when returning to the app. The new focus/
visibility refresh closes that stale-state opportunity without retrying a mutation.

Local authenticated PostgreSQL regression passes Owner upload -> Manager own exact photo consent
-> Owner fresh state -> separate publication. It asserts venue/profile/photo identity, linkage and
consent actor equality, revision 2->3, stale revision conflict, and no inherited consent on replacement.
The fixture includes other same-name cards. No backend persistence/permission defect was reproduced.
Backend runtime, RBAC, storage and migrations remain unchanged.

The **reported staging incident cause remains unproven**: stale client state, another visually
similar profile/photo, actual linkage or server data cannot be distinguished from the user report.
Local tests do not establish the actual staging account/profile/photo identifiers or response order.

## Minimal separately authorized live diagnosis, if still needed

No live queries were executed. Request only an operator-run READ ONLY transaction against the exact
staging database, restricted to the affected venue/profile/photo. Do not request credentials, names,
Telegram IDs, image bytes, storage keys, raw payloads or unrestricted logs. The safe parameter inputs
are `:venue_id`, `:profile_id`, and `:photo_id` (opaque photo UUID); no dump of all profiles is needed.
The operator should confirm the expected linked account locally and return only a boolean match.

```sql
BEGIN TRANSACTION READ ONLY;
SELECT p.id AS profile_id, p.venue_id,
       p.linked_user_id IS NOT NULL AS has_linked_account,
       EXISTS (SELECT 1 FROM venue_members m
               WHERE m.venue_id=p.venue_id AND m.user_id=p.linked_user_id
                 AND m.role IN ('MANAGER', 'ADMIN')) AS linked_active_manager
FROM staff_profiles p WHERE p.venue_id=:venue_id AND p.id=:profile_id;

SELECT s.profile_id, s.venue_id, s.revision, s.current_id, s.candidate_id
FROM staff_profile_photo_state s
WHERE s.venue_id=:venue_id AND s.profile_id=:profile_id;

SELECT ph.id AS photo_id, ph.profile_id, ph.venue_id, ph.state,
       ph.consent_kind, ph.consent_at, ph.reserved_revision,
       ph.linked_user_id=p.linked_user_id AS photo_link_matches_profile,
       ph.consent_actor_id=p.linked_user_id AS consent_actor_matches_profile
FROM staff_profile_photos ph
JOIN staff_profiles p ON p.venue_id=ph.venue_id AND p.id=ph.profile_id
WHERE ph.venue_id=:venue_id AND ph.profile_id=:profile_id AND ph.id=:photo_id;
ROLLBACK;
```

Separately request already-captured, sanitized client evidence for the two accounts: route venue/profile,
photo UUID, revision, isSelf/canConsent/canManage, consent/state, HTTP status and sequence of consent
response and Owner refresh. Exclude displayName, raw account identifiers, authorization/session headers,
photo content and provider references. Do not initiate consent/publication/revoke/upload to diagnose.
Photo GET endpoints can initialize state rows, so they are not proposed as strictly read-only DB probes.

## Changed surfaces

- `miniapp/src/screens/venueStaff.ts`: mutually exclusive sections, current role copy, compact accessible
  profile summaries, lazy private panels, collapse/section pending guards, explicit card-hide label.
- `miniapp/src/screens/staffPhoto.ts`: one initial automatic upload, progress/recovery copy, guarded
  picker completion, unchanged-key explicit retry, uncached return refresh and cleanup.
- `miniapp/src/screens/venueApp.ts`: existing Manager own permission, own-screen Back origin/highlight.
- `miniapp/src/style.css`: compact staff navigation/summary layout.
- `miniapp/e2e/guest-smoke.spec.ts`: adapt existing workflows to explicit opening and immediate upload;
  add compact 1/10 actual-private-image reads, own Manager empty/module-disabled state, two role UI
  sessions, stale picker and original-revision retry assertions. No retries/skips/timeouts relaxed.
- `StaffPhotoRoutesPostgresTest.kt`: distinct authenticated accounts and DB identity regression.
- Canonical Staff/QA docs and current `PROJECT_STATUS.md` checkpoint; this evidence note.

## Checks and review

- Photo backend suites: 48 tests, zero failures/errors/skips, including actual PostgreSQL.
- Affected Staff/Invite/RBAC/Guest/Preview/membership suites: 189 tests, zero failures/errors/skips.
- `:backend:app:compileKotlin` and `:backend:app:ktlintCheck`: PASS.
- `npm --prefix miniapp run build`: TypeScript and Vite PASS; existing large-chunk warning remains.
- Focused Playwright: 78/78 PASS before final Back/byte-counter refinements, covered again by full smoke.
- Final navigation/picker/real-private-byte subset: 20/20 PASS.
- Final full Playwright: **450/450 PASS**, zero failures/flaky/skips, 2 workers, retries unchanged (0).
- Final `git diff --check`: PASS. Source/test hashes and successful logs saved with XML evidence.

Backend command uses `DOCKER_HOST=unix:///Users/maksimmartynov/.docker/run/docker.sock` and
`_JAVA_OPTIONS='-Xmx4g -Dapi.version=1.44'` process-locally, with `./gradlew --offline --no-daemon
--max-workers=1`. Initial sandbox denial was solved by execution approval; initial Testcontainers
skips were an environment/client API issue, not a product diagnosis. The successful runs contain
no skips. A first ktlint failure in the new test was formatting and was corrected.

Photo selectors: `--tests '*StaffPhoto*'`. Regression selectors:
`*VenueStaffRoutesTest*`, `*VenueStaffRepositoryTest`, `*VenueStaffMutationConcurrencyPostgresTest`,
`*StaffProfileLinkConcurrencyPostgresTest`, `*StaffInviteRepositoryTest`, `*StaffInviteReissuePostgresTest`,
`*VenueStaffModule*Test`, `*GuestVenueRoutesTest`, `*VenueGuestPreviewRoutesTest`, `*VenueRbacRoutesTest`,
`*PlatformVenueMemberRepositoryTest`, `*VenueOwnerAccountRepositoryTest`.
Playwright: `MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2`.
Early exploratory full runs were interrupted during adaptation, not reported as full PASS.
Legacy focused failures were missing section/expansion steps after the intentional UI change;
original mutation/denial/privacy assertions remain.

Independent read-only agent review found two P2s: unnecessary re-fetch of already-open private
panels; stale picker selection after disabled storage leaving invisible pending state. Both fixed
and covered. Final review of runtime/tests/SQL proposal and own Back navigation found no remaining
actionable P0/P1/P2. The reviewer did not independently run tests. A preliminary hidden-section
assertion concern was retracted after checking the full navigation path; negative controls also
use includeHidden where appropriate.

Evidence directory: `/private/tmp/HT-UX08-STAFF-UI-CONSENT-FIX-01/` (XML, totals, hashes and logs).
Synthetic screenshots: `.tmp-checks/ht-ux08-photos/screenshots/`, 390×844, 390×568 and 1280×900.
Screenshots inspected for compact rows, uploads, Manager protected card and role copy. No real
photos or credentials were used. Tests use loopback/FakeStorage, not Cloudflare R2.

## Remaining acceptance and risks

A separately authorized staging deployment and smoke is needed for the runtime changes.
On real iPhone/Telegram: native picker JPEG/PNG/HEIC behavior, reduced keyboard viewport, selection
cancel, return from picker/background, Back during upload and failed response recovery; then the
same exact Owner→Manager→Owner photo scenario with safe identifiers and separate publication.
Recheck protected roles, no linked card, module-disabled self-revoke, and guest visibility/privacy.
Hosted CI, real iPhone behavior and the deployed release data are unverified. The stale-state
improvement does not prove the live incident root cause. UX08 product acceptance remains OPEN.
No migrations, provider configuration, R2 credentials/bucket, backend permissions, deletion,
consent, approval, backups or publication rules changed. No stage/commit/push/PR/merge/SSH/deploy.


## Final local status

`LOCAL_VERIFIED / LIVE_CAUSE_NOT_PROVEN / UX08_ACCEPTANCE_OPEN`.
Final working tree: 9 modified tracked files listed above (including checkpoint), new
`docs/UX08_STAFF_UI_CONSENT_FIX.md`, and the pre-existing untracked `scripts/dev/`.
No files staged; no refs or stash changed by this task. Next: review local diff, then separately
authorize release/staging/iPhone work; if incident diagnosis is needed before that, authorize only
the bounded read-only operator queries above. No automatic continuation into live work.
