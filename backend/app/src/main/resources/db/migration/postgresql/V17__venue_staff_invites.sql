ALTER TABLE venue_members
    ADD COLUMN IF NOT EXISTS invited_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS venue_staff_invites (
    code_hash TEXT PRIMARY KEY,
    code_hint TEXT NULL,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    created_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL,
    used_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL
);

ALTER TABLE venue_staff_invites
    DROP CONSTRAINT IF EXISTS venue_staff_invites_role_check;

ALTER TABLE venue_staff_invites
    ADD CONSTRAINT venue_staff_invites_role_check
        CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'STAFF'));

CREATE INDEX IF NOT EXISTS idx_venue_staff_invites_active
    ON venue_staff_invites (venue_id, expires_at)
    WHERE used_at IS NULL;

CREATE TABLE IF NOT EXISTS telegram_staff_chat_notifications (
    batch_id BIGINT PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
