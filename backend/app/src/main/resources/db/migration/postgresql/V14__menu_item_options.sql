CREATE TABLE menu_item_options (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    price_delta_minor INT NOT NULL DEFAULT 0 CHECK (price_delta_minor >= 0),
    is_available BOOLEAN NOT NULL DEFAULT true,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_menu_item_options_venue ON menu_item_options (venue_id);
CREATE INDEX idx_menu_item_options_item ON menu_item_options (item_id);
CREATE INDEX idx_menu_item_options_item_sort ON menu_item_options (item_id, sort_order);
