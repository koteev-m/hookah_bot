CREATE TABLE IF NOT EXISTS promotion_venue_placements (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    surface VARCHAR(32) NOT NULL CHECK (surface IN ('GLOBAL_PROMOTIONS_TOP')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'PAUSED', 'REJECTED', 'ARCHIVED')),
    starts_at TIMESTAMPTZ NULL,
    ends_at TIMESTAMPTZ NULL,
    priority INT NOT NULL DEFAULT 100,
    requested_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    approved_by_user_id BIGINT NULL REFERENCES users(telegram_user_id),
    approved_at TIMESTAMPTZ NULL,
    rejected_reason TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_promotion_venue_placements_surface_status_period_priority
    ON promotion_venue_placements(surface, status, starts_at, ends_at, priority);

CREATE INDEX IF NOT EXISTS idx_promotion_venue_placements_venue_status
    ON promotion_venue_placements(venue_id, status);

CREATE INDEX IF NOT EXISTS idx_promotion_venue_placements_status_created
    ON promotion_venue_placements(status, created_at);
