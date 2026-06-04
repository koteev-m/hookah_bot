ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS table_session_id BIGINT NULL;

WITH batch_sessions AS (
    SELECT
        gbi.order_id,
        MAX(gbi.table_session_id) AS table_session_id
    FROM guest_batch_idempotency gbi
    GROUP BY gbi.order_id
)
UPDATE orders o
SET table_session_id = batch_sessions.table_session_id
FROM batch_sessions
WHERE o.id = batch_sessions.order_id
  AND o.table_session_id IS NULL;

DO $$
DECLARE
    order_record RECORD;
    created_session_id BIGINT;
    session_started_at TIMESTAMPTZ;
    session_last_activity_at TIMESTAMPTZ;
    session_expires_at TIMESTAMPTZ;
BEGIN
    FOR order_record IN
        SELECT id, venue_id, table_id, status, created_at, updated_at
        FROM orders
        WHERE table_session_id IS NULL
        ORDER BY id
    LOOP
        session_started_at := COALESCE(order_record.created_at, now());
        session_last_activity_at := COALESCE(order_record.updated_at, order_record.created_at, now());
        session_expires_at :=
            CASE
                WHEN order_record.status = 'ACTIVE' THEN GREATEST(session_last_activity_at + interval '2 hours', now() + interval '2 hours')
                ELSE session_last_activity_at
            END;

        INSERT INTO table_sessions (
            venue_id,
            table_id,
            started_at,
            last_activity_at,
            expires_at,
            ended_at,
            status
        )
        VALUES (
            order_record.venue_id,
            order_record.table_id,
            session_started_at,
            session_last_activity_at,
            session_expires_at,
            CASE WHEN order_record.status = 'ACTIVE' THEN NULL ELSE session_last_activity_at END,
            CASE WHEN order_record.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'ENDED' END
        )
        RETURNING id INTO created_session_id;

        UPDATE orders
        SET table_session_id = created_session_id
        WHERE id = order_record.id;
    END LOOP;
END $$;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_table_session
    FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE RESTRICT;

ALTER TABLE orders
    ALTER COLUMN table_session_id SET NOT NULL;

DROP INDEX IF EXISTS uq_orders_active_table;
DROP INDEX IF EXISTS uq_orders_one_active_per_table;

CREATE INDEX IF NOT EXISTS idx_orders_table_session
    ON orders (table_session_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_one_active_per_table_session
    ON orders (table_session_id)
    WHERE status = 'ACTIVE';
