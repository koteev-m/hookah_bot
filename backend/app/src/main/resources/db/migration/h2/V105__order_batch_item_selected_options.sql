CREATE TABLE order_batch_item_options (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_batch_item_id BIGINT NOT NULL REFERENCES order_batch_items(id) ON DELETE CASCADE,
    menu_item_option_id BIGINT NULL REFERENCES menu_item_options(id) ON DELETE SET NULL,
    option_name_snapshot TEXT NOT NULL,
    price_delta_minor_snapshot BIGINT NOT NULL DEFAULT 0 CHECK (price_delta_minor_snapshot >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_order_batch_item_options_item
    ON order_batch_item_options (order_batch_item_id);

CREATE INDEX idx_order_batch_item_options_option
    ON order_batch_item_options (menu_item_option_id);
