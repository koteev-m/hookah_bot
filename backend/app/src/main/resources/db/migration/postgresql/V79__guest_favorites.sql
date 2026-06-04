CREATE TABLE IF NOT EXISTS guest_favorite_venues (
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, venue_id)
);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_venues_user_created
    ON guest_favorite_venues (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_venues_venue
    ON guest_favorite_venues (venue_id);

CREATE TABLE IF NOT EXISTS guest_favorite_items (
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    menu_item_id BIGINT NOT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, menu_item_id)
);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_items_user_venue_created
    ON guest_favorite_items (user_id, venue_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_items_item
    ON guest_favorite_items (menu_item_id);
