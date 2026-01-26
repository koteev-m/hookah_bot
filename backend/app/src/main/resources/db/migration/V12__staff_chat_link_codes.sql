CREATE TABLE IF NOT EXISTS telegram_staff_chat_link_codes (
    code_hash TEXT PRIMARY KEY,
    code_hint TEXT NULL,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    created_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    used_at TIMESTAMPTZ NULL,
    used_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    used_in_chat_id BIGINT NULL,
    used_message_id BIGINT NULL
);

CREATE INDEX IF NOT EXISTS idx_link_codes_venue_active
    ON telegram_staff_chat_link_codes (venue_id, expires_at)
    WHERE used_at IS NULL AND revoked_at IS NULL;

ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS staff_chat_linked_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS staff_chat_linked_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS staff_chat_unlinked_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS staff_chat_unlinked_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_venues_staff_chat
    ON venues (staff_chat_id)
    WHERE staff_chat_id IS NOT NULL;
