-- Run on the newly enrolled, migrated serving database with all writers stopped.
-- Media and access must start empty; the incident review separately covers business records.
SELECT pg_current_wal_insert_lsn() WHERE
    NOT EXISTS (SELECT FROM pg_prepared_xacts WHERE database = current_database())
    AND NOT EXISTS (SELECT FROM pg_stat_activity WHERE datname = current_database()
                    AND pid <> pg_backend_pid() AND backend_xid IS NOT NULL)
    AND NOT EXISTS (SELECT FROM venue_members) AND NOT EXISTS (SELECT FROM venue_owner_accounts)
    AND NOT EXISTS (SELECT FROM venue_staff_invites) AND NOT EXISTS (SELECT FROM venue_connection_requests)
    AND NOT EXISTS (SELECT FROM telegram_staff_chat_link_codes) AND NOT EXISTS (SELECT FROM tab_invite)
    -- Guest tab lookup and invitations use ACTIVE tabs; an active session can create a personal tab.
    -- Support order context also treats tab_member as authority for a CLOSED tab.
    AND NOT EXISTS (SELECT FROM table_sessions WHERE status = 'ACTIVE')
    AND NOT EXISTS (SELECT FROM tab WHERE status = 'ACTIVE' OR owner_user_id IS NOT NULL)
    AND NOT EXISTS (SELECT FROM tab_member)
    AND NOT EXISTS (SELECT FROM table_tokens) AND NOT EXISTS (SELECT FROM telegram_dialog_state)
    AND NOT EXISTS (SELECT FROM telegram_chat_context) AND NOT EXISTS (SELECT FROM telegram_venue_context)
    AND NOT EXISTS (SELECT FROM telegram_outbox)
    AND NOT EXISTS (SELECT FROM venues WHERE upper(btrim(status)) = 'PUBLISHED' OR staff_chat_id IS NOT NULL OR owner_account_id IS NOT NULL)
    AND NOT EXISTS (SELECT FROM venue_info_section_media) AND NOT EXISTS (SELECT FROM venue_info_media_cleanup)
    AND NOT EXISTS (SELECT FROM staff_profile_photos) AND NOT EXISTS (SELECT FROM staff_profile_photo_state)
    AND NOT EXISTS (SELECT FROM staff_profiles WHERE linked_user_id IS NOT NULL OR photo_ref IS NOT NULL)
    AND NOT EXISTS (SELECT FROM hall_plan_media) AND NOT EXISTS (SELECT FROM venue_halls WHERE plan_id IS NOT NULL)
    AND NOT EXISTS (SELECT FROM menu_category_images) AND NOT EXISTS (SELECT FROM venue_promotion_media);
