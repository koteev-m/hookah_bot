CREATE TABLE IF NOT EXISTS venue_members (
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (venue_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_venue_members_user ON venue_members (user_id);

ALTER TABLE venue_members
    DROP CONSTRAINT IF EXISTS venue_members_role_check;

ALTER TABLE venue_members
    ADD CONSTRAINT venue_members_role_check
        CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'STAFF'));
