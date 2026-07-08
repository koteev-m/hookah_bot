# Staff Profiles, Today Shifts And Staff Tips

Дата актуализации: 2026-07-08.

Статус: **canonical staff visibility/tips spec / PHASE 1 DONE / LOCAL SMOKE-PASSED**.
`STAFF_PROFILE`, `SHIFT_TODAY`, guest-visible `Сегодня работают` and Venue staff-profile UX
polish have a bounded Phase 1 implementation in backend + Mini App and passed local route/build/e2e
smoke. Staging UX acceptance is still required before production readiness is claimed. `STAFF_TIP`,
`STAFF_SCHEDULE`, photo upload/media picker and staff shift sign-up/chat workflows remain future.
Phase 2 may create staff tip intents with external staff tip links, but the platform must not
collect guest order payments or staff tips in MVP.

## Core Rule

Public staff visibility is opt-in and privacy-first. A public staff profile is a guest-facing
display object owned by a venue; it is not the same thing as `venue_members` RBAC membership.

Guest order payment remains offline: venue staff brings the venue terminal and the venue accepts
payment on its own legal entity/IP. The platform does not become a payment aggregator for guest
orders. Staff tips, when implemented, must target a specific staff profile, not only the venue.

## Domains

| Domain | Purpose | MVP status |
| --- | --- | --- |
| `STAFF_PROFILE` | Guest-visible profile for a hookah master, waiter, admin or other staff subtype. | Phase 1 done; local smoke passed; staging acceptance pending. |
| `SHIFT_TODAY` | Simple manual "today on shift" visibility for public staff profiles. | Phase 1 done; local smoke passed; staging acceptance pending. |
| `STAFF_SCHEDULE` | Optional future calendar/schedule module for planned shifts. | Future / spec needed. |
| `STAFF_TIP` | Future CTA and intent to thank a specific staff member. | Phase 2+ / spec draft. |

## Phase 1 MVP

Current Phase 1 implementation includes:
- Owner creates and edits staff profiles.
- Profile may be linked to a real venue member through `linked_user_id`, or may be display-only.
- Public visibility is opt-in through `is_guest_visible`.
- Owner publishes/hides profiles.
- Staff may edit only their own linked draft fields if policy allows.
- Staff cannot self-publish.
- Owner/Manager may mark "today on shift"; conservative MVP allows Manager to mark
  active/completed/canceled and keeps scheduled shifts Owner-only.
- Guest sees `Сегодня работают` on venue detail below the main venue information/actions, not as
  the first block.
- Venue Mode `Персонал` has the polished `Карточки сотрудников` UX: the create form is collapsed
  by default, existing cards are compact, `Другое` requires `Название роли`, and raw User ID /
  Photo ref inputs are not exposed.
- Catalog may later show a short `Сегодня: Иван, Алина` line.

Phase 1 explicitly excludes:
- staff tip payments;
- payment provider integration;
- Telegram Stars tips;
- crypto;
- online guest order payment through the platform;
- full staff scheduling/calendar;
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
- If `subtype=other`, Owner must provide `role_label` / `Название роли`; this custom role is what
  guests see. Old or incomplete `other` profiles fall back to `Сотрудник`, not `Другое`.
- Public guest DTOs must not include `linked_user_id`, Telegram ids, phone, email or private notes.
- Profile publish/hide is a venue-owner controlled action in MVP.

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

- Section name: `Карточки сотрудников`.
- Profiles are optional.
- Guest sees only published profiles.
- `Сегодня на смене` makes the published profile appear in `Сегодня работают`.
- Create form is collapsed by default behind `Добавить карточку сотрудника`.
- Existing cards are compact by default and show name, role/custom role, published/hidden state,
  today-shift state and actions.
- Full edit form opens only through `Редактировать`.
- `Другое` requires `Название роли` with guest-facing custom role copy.
- Raw User ID and raw Photo ref are not visible manual owner inputs.
- Photo upload is future; use a safe placeholder until real upload/media picker exists.
- Owner manages profiles, guest visibility and publish/hide state.
- Manager may manage today's active/completed/canceled shift state.
- Staff may edit only own linked draft fields.
- Staff cannot publish themselves or enable guest visibility without Owner approval in MVP.

## STAFF_SCHEDULE Future

`STAFF_SCHEDULE` is an optional future module and is not required for small venues in Phase 1.

Future target:
- Owner/Manager creates full shifts for future dates.
- Staff can see their own shifts.
- Staff can see who works with them if venue policy allows it.
- Shift statuses remain `scheduled`, `active`, `completed`, `canceled`.
- Later: shift swap/request, staff availability, reminders and payroll/assignment integrations.

Rules:
- Venue Mode schedule remains the source of truth.
- Telegram notifications/buttons may mirror or update allowed shift decisions, but Telegram chat is
  not the source of truth.
- `SHIFT_TODAY` remains the current implemented manual visibility mechanism until schedule exists.

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

Current recommendation: do not add a second group yet. Prefer personal bot notifications plus Venue
Mode source of truth when `STAFF_SCHEDULE` is specified.

## Photo Upload Future

- Future profile photos need safe media upload/photo picker, not manual raw `photo_ref` entry.
- Employee consent is required before showing a public profile photo.
- Storage, moderation and deletion rules must be specified before broad rollout.
- Guest UI uses a placeholder or approved public photo only.

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
- Owner manages profiles, publish/hide state and future tip-method approval.
- Manager can mark shifts only if venue policy allows.
- Staff edits only own linked draft fields.
- Platform Owner may later moderate/disable unsafe public profiles or tip methods.
- Public profile, shift and tip-method changes require audit where implemented.

## Privacy And Security

- No phone/email public by default.
- No raw Telegram username unless explicit opt-in exists.
- `linked_user_id` is not exposed through guest APIs.
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
- `staff_schedule_shift_created` - future
- `staff_shift_confirmed` - future
- `staff_tip_intent_created` - future
- `staff_tip_clicked` - future

Analytics events are not the source of truth. Domain tables and audit logs remain authoritative.

## Roadmap Status

- Staff profiles: `MVP DONE / LOCAL SMOKE-PASSED`; staging UX acceptance remains release gate.
- Today on shift: `MVP DONE / LOCAL SMOKE-PASSED`; staging UX acceptance remains release gate.
- Staff profile UX polish: `DONE / LOCAL SMOKE-PASSED`.
- Photo upload/media picker: `FUTURE`.
- Staff schedule: `FUTURE / SPEC NEEDED`.
- Staff shift Telegram notifications/sign-up/swaps: `FUTURE`.
- Separate staff communication chat/forum topics: `OPEN DECISION / FUTURE`.
- Staff tips: `SPEC DRAFT / FUTURE`.
- External staff tip link + staff tip intent: `SPEC DRAFT / FUTURE`.
- Telegram Stars / crypto tips: `FUTURE / not MVP`.
- Payments for tips: `FUTURE / needs legal/payment decision`.
- Guest order online payment: not in scope; order payment remains the offline terminal model.

## Next Implementation Step

Continue smoke-polish for Staff Profiles Phase 1 until staging UX is accepted. After that, choose
between `STAFF_SCHEDULE` spec-first or returning to higher-priority operational gaps. Keep staff
tips, photo upload, staff communication chat/sign-up and all payment paths out of scope until their
separate specs are approved.
