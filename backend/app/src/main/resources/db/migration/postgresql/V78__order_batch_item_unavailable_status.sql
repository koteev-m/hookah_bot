ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS item_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_reason_code VARCHAR(64) NULL;

ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_reason_text TEXT NULL;

ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMPTZ NULL;

ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS canceled_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL;

ALTER TABLE order_batch_items
    ADD CONSTRAINT chk_order_batch_items_item_status
    CHECK (item_status IN ('ACTIVE', 'CANCELED'));

CREATE INDEX IF NOT EXISTS idx_order_batch_items_status
    ON order_batch_items (item_status);
