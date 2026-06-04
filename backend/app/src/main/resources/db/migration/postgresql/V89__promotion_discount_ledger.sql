CREATE TABLE IF NOT EXISTS order_promotion_applications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    batch_id BIGINT NULL REFERENCES order_batches(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    promotion_id BIGINT NULL REFERENCES venue_promotions(id) ON DELETE SET NULL,
    rule_id BIGINT NOT NULL REFERENCES promotion_rules(id) ON DELETE RESTRICT,
    title_snapshot TEXT NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_value VARCHAR(32) NOT NULL,
    discount_percent INT NOT NULL,
    discount_total_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'RUB',
    dedupe_key VARCHAR(160) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS order_batch_item_promotion_adjustments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES order_promotion_applications(id) ON DELETE CASCADE,
    order_batch_item_id BIGINT NOT NULL REFERENCES order_batch_items(id) ON DELETE CASCADE,
    menu_item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE RESTRICT,
    discount_minor BIGINT NOT NULL,
    discount_percent INT NOT NULL,
    original_price_minor BIGINT NOT NULL,
    quantity INT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'RUB',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_promotion_applications_order
    ON order_promotion_applications (order_id);

CREATE INDEX IF NOT EXISTS idx_order_promotion_applications_batch
    ON order_promotion_applications (batch_id);

CREATE INDEX IF NOT EXISTS idx_order_promotion_applications_rule
    ON order_promotion_applications (rule_id);

CREATE INDEX IF NOT EXISTS idx_order_batch_item_promotion_adjustments_item
    ON order_batch_item_promotion_adjustments (order_batch_item_id);

CREATE INDEX IF NOT EXISTS idx_order_batch_item_promotion_adjustments_menu_item
    ON order_batch_item_promotion_adjustments (menu_item_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_order_batch_item_promotion_adjustments_item
    ON order_batch_item_promotion_adjustments (order_batch_item_id);
