CREATE TABLE IF NOT EXISTS telegram_venue_context (
    chat_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT fk_telegram_venue_context_user
        FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_telegram_venue_context_venue
        FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_telegram_venue_context_user ON telegram_venue_context (user_id);
CREATE INDEX IF NOT EXISTS idx_telegram_venue_context_venue ON telegram_venue_context (venue_id);
