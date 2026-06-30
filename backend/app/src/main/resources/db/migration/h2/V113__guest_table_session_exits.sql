CREATE TABLE IF NOT EXISTS guest_table_session_exits (
    user_id BIGINT NOT NULL,
    table_session_id BIGINT NOT NULL,
    exited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, table_session_id),
    CONSTRAINT fk_guest_table_session_exits_user
        FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_table_session_exits_session
        FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_guest_table_session_exits_session
    ON guest_table_session_exits (table_session_id, user_id);
