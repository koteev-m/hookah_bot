CREATE TABLE telegram_staff_chat_link_codes (
    code_hash TEXT PRIMARY KEY,
    code_hint TEXT NULL,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    created_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    used_at TIMESTAMP WITH TIME ZONE NULL,
    used_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    used_in_chat_id BIGINT NULL,
    used_message_id BIGINT NULL
);

CREATE INDEX idx_link_codes_venue_active ON telegram_staff_chat_link_codes (venue_id, expires_at);

ALTER TABLE venues
    ADD COLUMN staff_chat_linked_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE venues
    ADD COLUMN staff_chat_linked_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL;

ALTER TABLE venues
    ADD COLUMN staff_chat_unlinked_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE venues
    ADD COLUMN staff_chat_unlinked_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_venues_staff_chat ON venues (staff_chat_id);
