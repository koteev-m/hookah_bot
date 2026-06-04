CREATE TABLE IF NOT EXISTS promotion_rule_targets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id BIGINT NOT NULL REFERENCES promotion_rules(id) ON DELETE CASCADE,
    target_type VARCHAR(32) NOT NULL
        CHECK (target_type IN ('CATEGORY_TYPE', 'MENU_ITEM')),
    semantic_type VARCHAR(32) NULL
        CHECK (semantic_type IS NULL OR semantic_type IN ('HOOKAH', 'TEA', 'DRINK', 'FOOD', 'OTHER')),
    menu_item_id BIGINT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (target_type = 'CATEGORY_TYPE' AND semantic_type IS NOT NULL AND menu_item_id IS NULL)
        OR (target_type = 'MENU_ITEM' AND semantic_type IS NULL AND menu_item_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_targets_rule
    ON promotion_rule_targets (rule_id);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_targets_menu_item
    ON promotion_rule_targets (menu_item_id);

INSERT INTO promotion_rule_targets (rule_id, target_type, semantic_type, menu_item_id)
SELECT r.id, 'CATEGORY_TYPE', r.target_value, NULL
FROM promotion_rules r
WHERE NOT EXISTS (
    SELECT 1
    FROM promotion_rule_targets existing
    WHERE existing.rule_id = r.id
);
