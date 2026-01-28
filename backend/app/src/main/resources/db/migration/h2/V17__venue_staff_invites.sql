ALTER TABLE venue_members
    ADD COLUMN invited_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL;

CREATE TABLE venue_staff_invites (
    code_hash TEXT PRIMARY KEY,
    code_hint TEXT NULL,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'STAFF')),
    created_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE NULL,
    used_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL
);

CREATE INDEX idx_venue_staff_invites_active ON venue_staff_invites (venue_id, expires_at);

CREATE TABLE telegram_staff_chat_notifications (
    batch_id BIGINT PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
