ALTER TABLE telegram_chat_context
    ADD COLUMN table_session_id BIGINT REFERENCES table_sessions(id) ON DELETE SET NULL;
