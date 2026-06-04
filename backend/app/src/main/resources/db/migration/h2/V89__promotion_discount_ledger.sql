CREATE TABLE IF NOT EXISTS order_promotion_applications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL,
    batch_id BIGINT NULL,
    venue_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    promotion_id BIGINT NULL,
    rule_id BIGINT NOT NULL,
    title_snapshot TEXT NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_value VARCHAR(32) NOT NULL,
    discount_percent INT NOT NULL,
    discount_total_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'RUB',
    dedupe_key VARCHAR(160) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_promotion_applications_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_promotion_applications_batch FOREIGN KEY (batch_id) REFERENCES order_batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_promotion_applications_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_promotion_applications_user FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_promotion_applications_promotion FOREIGN KEY (promotion_id) REFERENCES venue_promotions(id) ON DELETE SET NULL,
    CONSTRAINT fk_order_promotion_applications_rule FOREIGN KEY (rule_id) REFERENCES promotion_rules(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS order_batch_item_promotion_adjustments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id BIGINT NOT NULL,
    order_batch_item_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    discount_minor BIGINT NOT NULL,
    discount_percent INT NOT NULL,
    original_price_minor BIGINT NOT NULL,
    quantity INT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'RUB',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_batch_item_promotion_adjustments_application FOREIGN KEY (application_id) REFERENCES order_promotion_applications(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_batch_item_promotion_adjustments_item FOREIGN KEY (order_batch_item_id) REFERENCES order_batch_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_batch_item_promotion_adjustments_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE RESTRICT
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
