CREATE INDEX IF NOT EXISTS idx_menu_categories_venue_sort
ON menu_categories (venue_id, sort_order);
