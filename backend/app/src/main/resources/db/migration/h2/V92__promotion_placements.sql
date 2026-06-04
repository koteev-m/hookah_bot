CREATE TABLE IF NOT EXISTS promotion_placements (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    promotion_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    surface VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    starts_at TIMESTAMP NULL,
    ends_at TIMESTAMP NULL,
    priority INT NOT NULL DEFAULT 100,
    requested_by_user_id BIGINT NOT NULL,
    approved_by_user_id BIGINT NULL,
    approved_at TIMESTAMP NULL,
    rejected_reason TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_promotion_placements_promotion FOREIGN KEY (promotion_id) REFERENCES venue_promotions(id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_placements_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_placements_requested_user FOREIGN KEY (requested_by_user_id) REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_promotion_placements_approved_user FOREIGN KEY (approved_by_user_id) REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    CONSTRAINT promotion_placements_surface_check CHECK (surface IN ('GLOBAL_PROMOTIONS_TOP', 'VENUE_PROMOTIONS_TOP')),
    CONSTRAINT promotion_placements_status_check CHECK (status IN ('PENDING', 'APPROVED', 'ACTIVE', 'PAUSED', 'REJECTED', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_surface_status_period_priority
    ON promotion_placements (surface, status, starts_at, ends_at, priority);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_venue_status
    ON promotion_placements (venue_id, status);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_promotion
    ON promotion_placements (promotion_id);

CREATE INDEX IF NOT EXISTS idx_promotion_placements_status_created
    ON promotion_placements (status, created_at);
