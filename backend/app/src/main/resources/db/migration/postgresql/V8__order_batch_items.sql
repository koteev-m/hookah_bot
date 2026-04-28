CREATE TABLE IF NOT EXISTS order_batch_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_batch_id BIGINT NOT NULL REFERENCES order_batches(id) ON DELETE CASCADE,
    menu_item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE RESTRICT,
    qty INT NOT NULL CHECK (qty >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_batch_items_batch ON order_batch_items (order_batch_id);
CREATE INDEX IF NOT EXISTS idx_order_batch_items_item ON order_batch_items (menu_item_id);
