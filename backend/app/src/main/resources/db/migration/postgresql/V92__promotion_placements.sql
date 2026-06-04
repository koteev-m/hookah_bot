CREATE TABLE IF NOT EXISTS promotion_placements (
    id BIGSERIAL PRIMARY KEY,
    promotion_id BIGINT NOT NULL REFERENCES venue_promotions(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    surface VARCHAR(32) NOT NULL
        CHECK (surface IN ('GLOBAL_PROMOTIONS_TOP', 'VENUE_PROMOTIONS_TOP')),
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PENDING', 'APPROVED', 'ACTIVE', 'PAUSED', 'REJECTED', 'ARCHIVED')),
    starts_at TIMESTAMPTZ NULL,
    ends_at TIMESTAMPTZ NULL,
    priority INT NOT NULL DEFAULT 100,
    requested_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    approved_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ NULL,
    rejected_reason TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_surface_status_period_priority
    ON promotion_placements (surface, status, starts_at, ends_at, priority);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_venue_status
    ON promotion_placements (venue_id, status);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_promotion
    ON promotion_placements (promotion_id);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_status_created
    ON promotion_placements (status, created_at);
