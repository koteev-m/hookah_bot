ALTER TABLE IF EXISTS order_batch_items
    ADD COLUMN IF NOT EXISTS item_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE IF EXISTS order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_reason_code VARCHAR(64) NULL;

ALTER TABLE IF EXISTS order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_reason_text TEXT NULL;

ALTER TABLE IF EXISTS order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE IF EXISTS order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_by_user_id BIGINT NULL;

ALTER TABLE IF EXISTS order_batch_items
    ADD CONSTRAINT IF NOT EXISTS chk_order_batch_items_item_status
    CHECK (item_status IN ('ACTIVE', 'CANCELED'));

CREATE INDEX IF NOT EXISTS idx_order_batch_items_status
    ON order_batch_items (item_status);
