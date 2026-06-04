ALTER TABLE promotion_rules
    ADD COLUMN IF NOT EXISTS stackable BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE promotion_rules
    ADD COLUMN IF NOT EXISTS conflict_group VARCHAR(64) NULL;

ALTER TABLE promotion_rules
    ADD COLUMN IF NOT EXISTS max_applications_per_item INT NOT NULL DEFAULT 1;

ALTER TABLE promotion_rules
    ADD CONSTRAINT IF NOT EXISTS promotion_rules_max_applications_per_item_check
    CHECK (max_applications_per_item >= 1);
