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
SET table_session_id = mapped.table_session_id
FROM (
    SELECT
        ts.venue_id,
        ts.table_id,
        MAX(ts.id) AS table_session_id
    FROM table_sessions ts
    GROUP BY ts.venue_id, ts.table_id
) mapped
WHERE gbi.venue_id = mapped.venue_id
  AND gbi.table_session_id = mapped.table_id;

ALTER TABLE guest_batch_idempotency
    ADD CONSTRAINT guest_batch_idempotency_table_session_id_fkey
    FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE CASCADE;
