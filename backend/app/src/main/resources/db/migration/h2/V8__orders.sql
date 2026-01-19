CREATE TABLE orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED', 'CANCELLED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_orders_active_table ON orders (table_id, status);
CREATE INDEX idx_orders_venue ON orders (venue_id);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_batches (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    author_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    source TEXT NOT NULL CHECK (source IN ('MINIAPP', 'CHAT', 'STAFF')),
    status TEXT NOT NULL CHECK (
        status IN ('NEW', 'ACCEPTED', 'PREPARING', 'DELIVERING', 'DELIVERED', 'REJECTED')
    ),
    items_snapshot TEXT NOT NULL DEFAULT '[]',
    guest_comment TEXT NULL
);

CREATE INDEX idx_order_batches_order ON order_batches (order_id);
CREATE INDEX idx_order_batches_status ON order_batches (status);

CREATE TABLE order_batch_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_batch_id BIGINT NOT NULL REFERENCES order_batches(id) ON DELETE CASCADE,
    menu_item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE RESTRICT,
    qty INT NOT NULL CHECK (qty >= 1),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_order_batch_items_batch ON order_batch_items (order_batch_id);
CREATE INDEX idx_order_batch_items_item ON order_batch_items (menu_item_id);
