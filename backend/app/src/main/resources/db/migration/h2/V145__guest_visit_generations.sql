CREATE TABLE guest_visit_generations (
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES table_sessions(id) ON DELETE CASCADE,
    consumed_exit_event_id BIGINT NOT NULL CHECK (consumed_exit_event_id > 0),
    PRIMARY KEY (user_id, table_session_id)
);
