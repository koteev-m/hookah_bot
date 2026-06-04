ALTER TABLE promotion_rule_rewards
    ADD COLUMN IF NOT EXISTS reward_mode VARCHAR(32) NOT NULL DEFAULT 'FIXED_ITEM'
    CHECK (reward_mode IN ('FIXED_ITEM', 'CHOICE_ITEMS'));

CREATE TABLE IF NOT EXISTS promotion_rule_reward_options (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reward_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_promotion_rule_reward_options_reward FOREIGN KEY (reward_id) REFERENCES promotion_rule_rewards(id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_rule_reward_options_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_promotion_rule_reward_options_item
    ON promotion_rule_reward_options (reward_id, menu_item_id);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_reward_options_reward
    ON promotion_rule_reward_options (reward_id);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_reward_options_menu_item
    ON promotion_rule_reward_options (menu_item_id);
