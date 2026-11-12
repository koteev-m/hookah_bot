-- No historical inference: only upgraded writers can assert complete history.
ALTER TABLE orders ADD COLUMN first_accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE orders ADD COLUMN acceptance_history_complete BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE order_batches ADD COLUMN first_accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE order_batches ADD COLUMN first_delivered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE order_batches ADD COLUMN status_history_complete BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE order_batches ADD COLUMN transition_sequence BIGINT NOT NULL DEFAULT 0;

CREATE TABLE order_batch_status_facts (
    batch_id BIGINT NOT NULL REFERENCES order_batches(id) ON DELETE CASCADE,
    transition_sequence BIGINT NOT NULL,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    producer VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    PRIMARY KEY (batch_id, transition_sequence),
    CHECK (from_status <> to_status)
);
CREATE INDEX idx_batch_status_facts_activity ON order_batch_status_facts(to_status, occurred_at, venue_id);
CREATE INDEX idx_batch_status_facts_order ON order_batch_status_facts(order_id, occurred_at);

ALTER TABLE staff_calls ADD COLUMN status_history_complete BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE staff_calls ADD COLUMN acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE staff_calls ADD COLUMN acknowledged_channel VARCHAR(16);
ALTER TABLE staff_calls ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE staff_calls ADD COLUMN completion_kind VARCHAR(32);
ALTER TABLE staff_calls ADD COLUMN completion_channel VARCHAR(16);
ALTER TABLE staff_calls ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE staff_calls ADD COLUMN cancellation_kind VARCHAR(32);
