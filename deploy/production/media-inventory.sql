-- Snapshot inputs for private recovery. Only the running backend may authorize a guest read.
-- The cleanup journal survives a cascaded section deletion.
WITH venue_policy AS (
    SELECT v.id, v.status, v.city, sub.status AS subscription,
           vs.timezone, vs.team_schedule_module_enabled, vs.guest_team_visible, vs.today_staff_source
    FROM venues v LEFT JOIN venue_settings vs ON vs.venue_id = v.id
    LEFT JOIN venue_subscriptions sub ON sub.venue_id = v.id
), shifts AS (
    SELECT venue_id, staff_profile_id,
           bool_or(status IN ('scheduled','active') AND is_guest_visible) AS manual_candidate,
           bool_or(status IN ('scheduled','active') AND starts_at IS NOT NULL AND ends_at IS NOT NULL) AS scheduled_candidate,
           jsonb_agg(jsonb_build_array(shift_date, starts_at, ends_at, status, is_guest_visible, manually_marked_active)
                     ORDER BY shift_date, id) AS schedule
    FROM staff_shifts GROUP BY venue_id, staff_profile_id
), objects(key, bytes, sha, policy, selectable) AS (
    SELECT f.object_key, f.byte_count, f.checksum,
           jsonb_build_array('staff', f.venue_id, v.status, v.subscription, v.city, v.timezone,
               v.team_schedule_module_enabled, v.guest_team_visible, v.today_staff_source, shifts.schedule, f.profile_id, f.state, f.linked_user_id,
               f.consent_kind, f.consent_actor_id, f.consent_policy, s.current_id, s.candidate_id,
               p.linked_user_id, p.is_guest_visible, p.published_at IS NOT NULL, p.disabled_at IS NOT NULL),
           v.status = 'PUBLISHED' AND coalesce(v.subscription IN ('TRIAL','ACTIVE','PAST_DUE'), false)
             AND v.team_schedule_module_enabled IS TRUE AND v.guest_team_visible IS TRUE
             AND CASE v.today_staff_source WHEN 'MANUAL' THEN shifts.manual_candidate
                                          WHEN 'SCHEDULE' THEN shifts.scheduled_candidate ELSE false END
             AND p.is_guest_visible AND p.published_at IS NOT NULL AND p.disabled_at IS NULL
             AND f.state = 'APPROVED' AND f.id = s.current_id AND f.linked_user_id IS NOT DISTINCT FROM p.linked_user_id
    FROM staff_profile_photos f
    JOIN staff_profile_photo_state s USING (venue_id, profile_id)
    JOIN staff_profiles p ON p.id = f.profile_id AND p.venue_id = f.venue_id
    JOIN venue_policy v ON v.id = f.venue_id
    LEFT JOIN shifts ON shifts.venue_id = f.venue_id AND shifts.staff_profile_id = f.profile_id
    WHERE f.state IN ('PENDING', 'APPROVED', 'HIDDEN') AND f.deleted_at IS NULL
      AND f.id IN (s.current_id, s.candidate_id)
    UNION ALL
    SELECT m.object_key, m.size_bytes, m.checksum_sha256,
           jsonb_build_array('section', s.venue_id, v.status, v.subscription, m.section_id, m.public_id, s.is_visible, m.is_visible),
           v.status = 'PUBLISHED' AND coalesce(v.subscription IN ('TRIAL','ACTIVE','PAST_DUE'), false) AND s.is_visible AND m.is_visible
    FROM venue_info_section_media m JOIN venue_info_sections s ON s.id = m.section_id
    JOIN venue_policy v ON v.id = s.venue_id
    JOIN venue_info_media_cleanup j ON (j.object_key, j.public_id) = (m.object_key, m.public_id)
    WHERE m.source_type = 'OBJECT_STORAGE' AND m.status = 'READY' AND m.deleted_at IS NULL
      AND j.due_at IS NULL AND j.deleted_at IS NULL
    UNION ALL
    SELECT m.object_key, m.size_bytes, m.checksum_sha256,
           jsonb_build_array('hall', m.venue_id, v.status, v.subscription, m.hall_id, m.id, h.plan_id, h.is_active, r.use_plan, r.guest_table_choice),
           v.status = 'PUBLISHED' AND coalesce(v.subscription IN ('TRIAL','ACTIVE','PAST_DUE'), false)
             AND h.is_active AND h.plan_id = m.id AND r.use_plan IS TRUE AND r.guest_table_choice IS TRUE
    FROM hall_plan_media m JOIN venue_halls h ON (h.id, h.venue_id) = (m.hall_id, m.venue_id)
    JOIN venue_policy v ON v.id = m.venue_id
    LEFT JOIN venue_seating_rules r ON r.venue_id = m.venue_id
    WHERE m.status = 'READY'
)
SELECT COALESCE(json_agg(json_build_array(key, bytes, sha,
    encode(sha256(convert_to(policy::text, 'UTF8')), 'hex'), selectable IS TRUE) ORDER BY key), '[]') FROM objects;
