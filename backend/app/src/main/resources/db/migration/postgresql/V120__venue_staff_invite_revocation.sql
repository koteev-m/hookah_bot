ALTER TABLE venue_staff_invites
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ NULL;

ALTER TABLE venue_staff_invites
    ADD COLUMN IF NOT EXISTS revoked_by_user_id BIGINT NULL
        REFERENCES users(telegram_user_id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_venue_staff_invites_pending
    ON venue_staff_invites (venue_id, expires_at)
    WHERE used_at IS NULL AND revoked_at IS NULL;
