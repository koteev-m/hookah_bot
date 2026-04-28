CREATE TABLE IF NOT EXISTS bookings (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    scheduled_at TIMESTAMPTZ NOT NULL,
    party_size INTEGER,
    comment TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bookings_venue_status_scheduled
    ON bookings (venue_id, status, scheduled_at DESC);

CREATE INDEX IF NOT EXISTS idx_bookings_user_created
    ON bookings (user_id, created_at DESC);
