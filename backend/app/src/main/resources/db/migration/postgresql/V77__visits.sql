CREATE TABLE IF NOT EXISTS visits (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    booking_id BIGINT NULL REFERENCES bookings(id) ON DELETE SET NULL,
    table_session_id BIGINT NULL REFERENCES table_sessions(id) ON DELETE SET NULL,
    order_id BIGINT NULL REFERENCES orders(id) ON DELETE SET NULL,
    source VARCHAR(32) NOT NULL
        CHECK (source IN ('BOOKING_SEATED', 'ORDER_CLOSED', 'MERGED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    service_date DATE NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_visits_booking
    ON visits (booking_id)
    WHERE booking_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_visits_table_session_user
    ON visits (table_session_id, user_id)
    WHERE table_session_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_visits_order_user
    ON visits (order_id, user_id)
    WHERE order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_visits_venue_user_recent
    ON visits (venue_id, user_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_visits_user_recent
    ON visits (user_id, occurred_at DESC);
