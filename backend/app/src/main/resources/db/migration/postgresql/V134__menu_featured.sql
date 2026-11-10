ALTER TABLE menu_items ADD COLUMN featured_order INTEGER;
ALTER TABLE menu_items ADD CONSTRAINT menu_items_featured_order_range
    CHECK (featured_order IS NULL OR featured_order BETWEEN 1 AND 6);
CREATE UNIQUE INDEX menu_items_venue_featured_order ON menu_items (venue_id, featured_order);
