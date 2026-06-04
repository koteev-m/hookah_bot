ALTER TABLE promotion_rule_rewards
    ADD COLUMN IF NOT EXISTS reward_mode VARCHAR(32) NOT NULL DEFAULT 'FIXED_ITEM'
    CHECK (reward_mode IN ('FIXED_ITEM', 'CHOICE_ITEMS'));

CREATE TABLE IF NOT EXISTS promotion_rule_reward_options (
    id BIGSERIAL PRIMARY KEY,
    reward_id BIGINT NOT NULL REFERENCES promotion_rule_rewards(id) ON DELETE CASCADE,
    menu_item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_rule_reward_options_item
    ON promotion_rule_reward_options (reward_id, menu_item_id);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_reward_options_reward
    ON promotion_rule_reward_options (reward_id);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_reward_options_menu_item
    ON promotion_rule_reward_options (menu_item_id);
