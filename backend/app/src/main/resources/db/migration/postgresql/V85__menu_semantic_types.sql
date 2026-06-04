ALTER TABLE menu_categories
    ADD COLUMN IF NOT EXISTS category_type VARCHAR(32) NOT NULL DEFAULT 'OTHER';

ALTER TABLE menu_items
    ADD COLUMN IF NOT EXISTS item_type VARCHAR(32) NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_menu_categories_category_type'
    ) THEN
        ALTER TABLE menu_categories
            ADD CONSTRAINT chk_menu_categories_category_type
            CHECK (category_type IN ('HOOKAH', 'TEA', 'DRINK', 'FOOD', 'OTHER'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_menu_items_item_type'
    ) THEN
        ALTER TABLE menu_items
            ADD CONSTRAINT chk_menu_items_item_type
            CHECK (item_type IS NULL OR item_type IN ('HOOKAH', 'TEA', 'DRINK', 'FOOD', 'OTHER'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_menu_categories_type
    ON menu_categories (venue_id, category_type);

CREATE INDEX IF NOT EXISTS idx_menu_items_type
    ON menu_items (venue_id, item_type);
