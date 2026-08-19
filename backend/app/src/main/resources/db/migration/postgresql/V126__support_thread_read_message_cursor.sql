ALTER TABLE support_thread_reads
    ADD COLUMN last_read_message_id BIGINT;

CREATE INDEX idx_support_messages_thread_id
    ON support_messages (thread_id, id);
