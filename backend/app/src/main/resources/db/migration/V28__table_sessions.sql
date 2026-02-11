CREATE TABLE IF NOT EXISTS table_sessions (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NULL,
    status VARCHAR(16) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_table_sessions_table_active
    ON table_sessions (table_id, status, expires_at);

ALTER TABLE staff_calls
    ADD COLUMN IF NOT EXISTS table_session_id BIGINT NULL REFERENCES table_sessions(id) ON DELETE SET NULL;

ALTER TABLE guest_batch_idempotency
    DROP CONSTRAINT IF EXISTS guest_batch_idempotency_table_session_id_fkey;

ALTER TABLE guest_batch_idempotency
    ADD CONSTRAINT guest_batch_idempotency_table_session_id_fkey
    FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE CASCADE;
