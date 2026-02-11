CREATE TABLE IF NOT EXISTS table_sessions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    table_id BIGINT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT fk_table_sessions_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_table_sessions_table FOREIGN KEY (table_id) REFERENCES venue_tables(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_table_sessions_table_active
    ON table_sessions (table_id, status, expires_at);

ALTER TABLE staff_calls
    ADD COLUMN IF NOT EXISTS table_session_id BIGINT NULL;

ALTER TABLE staff_calls
    ADD CONSTRAINT IF NOT EXISTS fk_staff_calls_table_session
    FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE SET NULL;

ALTER TABLE guest_batch_idempotency
    DROP CONSTRAINT IF EXISTS fk_guest_batch_idempotency_table;

ALTER TABLE guest_batch_idempotency
    ADD CONSTRAINT fk_guest_batch_idempotency_table_session
    FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE CASCADE;
