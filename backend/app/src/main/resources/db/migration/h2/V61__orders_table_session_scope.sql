ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS table_session_id BIGINT NULL;

UPDATE orders o
SET table_session_id = (
    SELECT MAX(gbi.table_session_id)
    FROM guest_batch_idempotency gbi
    WHERE gbi.order_id = o.id
)
WHERE o.table_session_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM guest_batch_idempotency gbi
    WHERE gbi.order_id = o.id
);

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
    o.venue_id,
    o.table_id,
    DATEADD('SECOND', CAST(o.id AS INT), COALESCE(o.created_at, CURRENT_TIMESTAMP)),
    DATEADD('SECOND', CAST(o.id AS INT), COALESCE(o.updated_at, o.created_at, CURRENT_TIMESTAMP)),
    CASE
        WHEN o.status = 'ACTIVE' THEN DATEADD('HOUR', 2, CURRENT_TIMESTAMP)
        ELSE DATEADD('SECOND', CAST(o.id AS INT), COALESCE(o.updated_at, o.created_at, CURRENT_TIMESTAMP))
    END,
    CASE
        WHEN o.status = 'ACTIVE' THEN NULL
        ELSE DATEADD('SECOND', CAST(o.id AS INT), COALESCE(o.updated_at, o.created_at, CURRENT_TIMESTAMP))
    END,
    CASE WHEN o.status = 'ACTIVE' THEN 'ACTIVE' ELSE 'ENDED' END
FROM orders o
WHERE o.table_session_id IS NULL;

UPDATE orders o
SET table_session_id = (
    SELECT MAX(ts.id)
    FROM table_sessions ts
    WHERE ts.venue_id = o.venue_id
      AND ts.table_id = o.table_id
      AND ts.started_at = DATEADD('SECOND', CAST(o.id AS INT), COALESCE(o.created_at, CURRENT_TIMESTAMP))
      AND ts.last_activity_at = DATEADD('SECOND', CAST(o.id AS INT), COALESCE(o.updated_at, o.created_at, CURRENT_TIMESTAMP))
)
WHERE o.table_session_id IS NULL;

ALTER TABLE orders
    ADD CONSTRAINT IF NOT EXISTS fk_orders_table_session
    FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE RESTRICT;

ALTER TABLE orders
    ALTER COLUMN table_session_id SET NOT NULL;

DROP INDEX IF EXISTS uq_orders_active_table;
DROP INDEX IF EXISTS uq_orders_one_active_per_table;

CREATE INDEX IF NOT EXISTS idx_orders_table_session
    ON orders (table_session_id);

CREATE INDEX IF NOT EXISTS idx_orders_table_session_status
    ON orders (table_session_id, status);
