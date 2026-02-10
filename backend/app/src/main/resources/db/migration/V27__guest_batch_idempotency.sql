CREATE TABLE IF NOT EXISTS guest_batch_idempotency (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    batch_id BIGINT NOT NULL REFERENCES order_batches(id) ON DELETE CASCADE,
    response_snapshot JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_guest_batch_idempotency_scope
    ON guest_batch_idempotency (venue_id, table_session_id, user_id, idempotency_key);
