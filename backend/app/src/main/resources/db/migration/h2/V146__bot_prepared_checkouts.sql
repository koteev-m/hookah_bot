CREATE TABLE bot_prepared_checkouts (
    chat_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES table_sessions(id) ON DELETE CASCADE,
    visit_generation BIGINT NOT NULL,
    checkout_id VARCHAR(36) NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (chat_id, user_id, table_session_id, visit_generation)
);

-- The same scoped key records either a committed batch or a final refusal.
ALTER TABLE guest_batch_idempotency ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE guest_batch_idempotency ALTER COLUMN batch_id DROP NOT NULL;
ALTER TABLE guest_batch_idempotency ADD COLUMN rejected_table_id BIGINT;
ALTER TABLE guest_batch_idempotency ADD CONSTRAINT ck_guest_batch_idempotency_outcome CHECK (
    (order_id IS NOT NULL AND batch_id IS NOT NULL AND rejected_table_id IS NULL)
    OR (order_id IS NULL AND batch_id IS NULL AND rejected_table_id IS NOT NULL AND request_fingerprint IS NOT NULL)
);
