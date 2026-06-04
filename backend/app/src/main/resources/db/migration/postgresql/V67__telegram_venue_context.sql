CREATE TABLE telegram_venue_context (
    chat_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, user_id)
);

CREATE INDEX idx_telegram_venue_context_user ON telegram_venue_context (user_id);
CREATE INDEX idx_telegram_venue_context_venue ON telegram_venue_context (venue_id);
