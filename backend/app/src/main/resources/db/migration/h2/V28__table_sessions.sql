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

INSERT INTO table_sessions (
    venue_id,
    table_id,
    started_at,
    last_activity_at,
    expires_at,
    ended_at,
    status
)
SELECT
    source.venue_id,
    source.old_table_id,
    source.started_at,
    source.last_activity_at,
    source.last_activity_at,
    source.last_activity_at,
    'ENDED'
FROM (
    SELECT
        gbi.venue_id,
        gbi.table_session_id AS old_table_id,
        MIN(gbi.created_at) AS started_at,
        MAX(gbi.created_at) AS last_activity_at
    FROM guest_batch_idempotency gbi
    GROUP BY gbi.venue_id, gbi.table_session_id
) source
WHERE NOT EXISTS (
    SELECT 1
    FROM table_sessions ts
    WHERE ts.venue_id = source.venue_id
      AND ts.table_id = source.old_table_id
);

UPDATE guest_batch_idempotency gbi
SET table_session_id = (
    SELECT MAX(ts.id)
    FROM table_sessions ts
    WHERE ts.venue_id = gbi.venue_id
      AND ts.table_id = gbi.table_session_id
)
WHERE EXISTS (
    SELECT 1
    FROM table_sessions ts
    WHERE ts.venue_id = gbi.venue_id
      AND ts.table_id = gbi.table_session_id
);

ALTER TABLE guest_batch_idempotency
    ADD CONSTRAINT fk_guest_batch_idempotency_table_session
    FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE CASCADE;
