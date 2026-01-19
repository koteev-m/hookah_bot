ALTER TABLE menu_items ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
ALTER TABLE menu_categories ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE menu_items ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_menu_items_venue_sort ON menu_items (venue_id, sort_order);
CREATE INDEX idx_menu_items_category_sort ON menu_items (category_id, sort_order);
