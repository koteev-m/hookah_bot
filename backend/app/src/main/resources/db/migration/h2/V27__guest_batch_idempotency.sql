CREATE TABLE IF NOT EXISTS guest_batch_idempotency (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    table_session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    response_snapshot CLOB NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_guest_batch_idempotency_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_batch_idempotency_table FOREIGN KEY (table_session_id) REFERENCES venue_tables(id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_batch_idempotency_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_batch_idempotency_batch FOREIGN KEY (batch_id) REFERENCES order_batches(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_guest_batch_idempotency_scope
    ON guest_batch_idempotency (venue_id, table_session_id, user_id, idempotency_key);
