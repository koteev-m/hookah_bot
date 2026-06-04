ALTER TABLE promotion_rules
    DROP CONSTRAINT IF EXISTS promotion_rules_rule_type_check;

ALTER TABLE promotion_rules
    DROP CONSTRAINT IF EXISTS promotion_rules_discount_percent_check;

ALTER TABLE promotion_rules
    ALTER COLUMN discount_percent DROP NOT NULL;

ALTER TABLE promotion_rules
    ADD CONSTRAINT promotion_rules_rule_type_check
    CHECK (rule_type IN ('HAPPY_HOURS_PERCENT', 'GIFT_WITH_ITEM'));

ALTER TABLE promotion_rules
    ADD CONSTRAINT promotion_rules_discount_percent_check
    CHECK (
        (rule_type = 'HAPPY_HOURS_PERCENT' AND discount_percent BETWEEN 1 AND 100)
        OR (rule_type = 'GIFT_WITH_ITEM' AND discount_percent IS NULL)
    );

ALTER TABLE order_promotion_applications
    ALTER COLUMN discount_percent DROP NOT NULL;

CREATE TABLE IF NOT EXISTS promotion_rule_rewards (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT NOT NULL REFERENCES promotion_rules(id) ON DELETE CASCADE,
    reward_menu_item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE RESTRICT,
    reward_qty INT NOT NULL DEFAULT 1 CHECK (reward_qty >= 1),
    max_rewards_per_batch INT NOT NULL DEFAULT 1 CHECK (max_rewards_per_batch >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_rule_rewards_rule
    ON promotion_rule_rewards (rule_id);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_rewards_menu_item
    ON promotion_rule_rewards (reward_menu_item_id);

CREATE TABLE IF NOT EXISTS order_promotion_reward_items (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES order_promotion_applications(id) ON DELETE CASCADE,
    trigger_order_batch_item_id BIGINT NULL REFERENCES order_batch_items(id) ON DELETE SET NULL,
    reward_order_batch_item_id BIGINT NOT NULL REFERENCES order_batch_items(id) ON DELETE CASCADE,
    reward_menu_item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE RESTRICT,
    reward_qty INT NOT NULL CHECK (reward_qty >= 1),
    label_snapshot TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_order_promotion_reward_items_reward_item
    ON order_promotion_reward_items (reward_order_batch_item_id);

CREATE INDEX IF NOT EXISTS idx_order_promotion_reward_items_application
    ON order_promotion_reward_items (application_id);

CREATE INDEX IF NOT EXISTS idx_order_promotion_reward_items_trigger
    ON order_promotion_reward_items (trigger_order_batch_item_id);
