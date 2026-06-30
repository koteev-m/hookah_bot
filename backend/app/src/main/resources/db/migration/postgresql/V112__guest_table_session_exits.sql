CREATE TABLE IF NOT EXISTS guest_table_session_exits (
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES table_sessions(id) ON DELETE CASCADE,
    exited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, table_session_id)
);

CREATE INDEX IF NOT EXISTS idx_guest_table_session_exits_session
    ON guest_table_session_exits (table_session_id, user_id);
