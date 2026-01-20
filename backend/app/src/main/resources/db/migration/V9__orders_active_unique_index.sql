DROP INDEX IF EXISTS uq_orders_active_table;
CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_one_active_per_table
ON orders (table_id)
WHERE status = 'ACTIVE';
