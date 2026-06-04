CREATE TABLE IF NOT EXISTS guest_favorite_venues (
    user_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, venue_id),
    CONSTRAINT fk_guest_favorite_venues_user FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_favorite_venues_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_venues_user_created
    ON guest_favorite_venues (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_venues_venue
    ON guest_favorite_venues (venue_id);

CREATE TABLE IF NOT EXISTS guest_favorite_items (
    user_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, menu_item_id),
    CONSTRAINT fk_guest_favorite_items_user FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_favorite_items_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_favorite_items_item FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_items_user_venue_created
    ON guest_favorite_items (user_id, venue_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_guest_favorite_items_item
    ON guest_favorite_items (menu_item_id);
