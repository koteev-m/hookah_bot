CREATE TABLE billing_adjustments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL
        REFERENCES venues(id) ON DELETE CASCADE,
    kind VARCHAR NOT NULL
        CHECK (kind IN ('COURTESY_DAYS')),
    days INT NOT NULL CHECK (days > 0),
    reason TEXT NOT NULL CHECK (LENGTH(TRIM(reason)) > 0),
    previous_paid_through DATE NOT NULL,
    new_paid_through DATE NOT NULL,
    actor_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (new_paid_through > previous_paid_through)
);

CREATE INDEX idx_billing_adjustments_venue ON billing_adjustments (venue_id);
CREATE INDEX idx_billing_adjustments_created_at ON billing_adjustments (created_at);
