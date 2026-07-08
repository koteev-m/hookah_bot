# Staff Profiles, Today Shifts And Staff Tips

Дата актуализации: 2026-07-08.

Статус: **canonical staff visibility/tips spec / SPEC READY FOR PHASE 1**.
`STAFF_PROFILE` and `SHIFT_TODAY` are ready for a bounded Phase 1 implementation. `STAFF_TIP`
is a future domain: Phase 2 may create staff tip intents with external staff tip links, but the
platform must not collect guest order payments or staff tips in MVP.

## Core Rule

Public staff visibility is opt-in and privacy-first. A public staff profile is a guest-facing
display object owned by a venue; it is not the same thing as `venue_members` RBAC membership.

Guest order payment remains offline: venue staff brings the venue terminal and the venue accepts
payment on its own legal entity/IP. The platform does not become a payment aggregator for guest
orders. Staff tips, when implemented, must target a specific staff profile, not only the venue.

## Domains

| Domain | Purpose | MVP status |
| --- | --- | --- |
| `STAFF_PROFILE` | Guest-visible profile for a hookah master, waiter, admin or other staff subtype. | Phase 1 / spec ready. |
| `SHIFT_TODAY` | Simple manual "today on shift" visibility for public staff profiles. | Phase 1 / spec ready. |
| `STAFF_TIP` | Future CTA and intent to thank a specific staff member. | Phase 2+ / spec draft. |

## Phase 1 MVP

Phase 1 includes:
- Owner creates and edits staff profiles.
- Profile may be linked to a real venue member through `linked_user_id`, or may be display-only.
- Public visibility is opt-in through `is_guest_visible`.
- Owner publishes/hides profiles.
- Staff may edit only their own linked draft bio/photo fields if policy allows.
- Staff cannot self-publish.
- Owner/Manager may mark "today on shift" if policy allows.
- Guest sees `Сегодня работают` on venue detail.
- Catalog may later show a short `Сегодня: Иван, Алина` line.

Phase 1 explicitly excludes:
- staff tip payments;
- payment provider integration;
- Telegram Stars tips;
- crypto;
- online guest order payment through the platform.

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

- Venue detail may show `Сегодня работают` with visible public staff profiles.
- Catalog may later show a compact line such as `Сегодня: Иван, Алина`.
- Staff profile detail may show photo, display name, role, subtype, bio, tags and today status.
- After bill requested/paid/closed, a future CTA may offer `Поблагодарить сотрудника`.
- Tip CTA must stay separate from order payment and bill close.

## Venue UX

- Owner manages profiles, guest visibility and publish/hide state.
- Manager may manage today's shift state only if policy allows.
- Staff may edit only own linked draft fields if policy allows.
- Staff cannot publish themselves or enable guest visibility without Owner approval in MVP.

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
- Telegram Stars and crypto are future/not MVP for staff tips.

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
- `staff_tip_intent_created` - future
- `staff_tip_clicked` - future

Analytics events are not the source of truth. Domain tables and audit logs remain authoritative.

## Roadmap Status

- Staff profiles / today shift: `SPEC READY / NEXT`.
- Staff tips: `SPEC DRAFT / FUTURE`.
- Payments for tips: `FUTURE / needs legal/payment decision`.
- Guest order online payment: not in scope; order payment remains the offline terminal model.

## Next Implementation Step

Implement Phase 1: Staff profiles + today on shift, no payments.
