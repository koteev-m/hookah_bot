CREATE TABLE IF NOT EXISTS telegram_outbox (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    method VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_telegram_outbox_status_next
    ON telegram_outbox (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_telegram_outbox_chat_status
    ON telegram_outbox (chat_id, status, next_attempt_at);
