CREATE TABLE IF NOT EXISTS support_thread_reads (
    thread_id BIGINT NOT NULL REFERENCES support_threads(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    last_read_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (thread_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_support_thread_reads_user
    ON support_thread_reads (user_id, last_read_at DESC);
