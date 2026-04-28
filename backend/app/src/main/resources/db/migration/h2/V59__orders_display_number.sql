ALTER TABLE IF EXISTS orders
    ADD COLUMN IF NOT EXISTS display_number INT NULL;

ALTER TABLE IF EXISTS orders
    ADD COLUMN IF NOT EXISTS display_date DATE NULL;

CREATE INDEX IF NOT EXISTS idx_orders_venue_display_date_number
    ON orders (venue_id, display_date, display_number);
