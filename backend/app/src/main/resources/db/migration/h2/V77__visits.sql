CREATE TABLE IF NOT EXISTS visits (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    booking_id BIGINT NULL,
    table_session_id BIGINT NULL,
    order_id BIGINT NULL,
    source VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    service_date DATE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_visits_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_visits_user FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_visits_booking FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE SET NULL,
    CONSTRAINT fk_visits_table_session FOREIGN KEY (table_session_id) REFERENCES table_sessions(id) ON DELETE SET NULL,
    CONSTRAINT fk_visits_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL,
    CONSTRAINT chk_visits_source CHECK (source IN ('BOOKING_SEATED', 'ORDER_CLOSED', 'MERGED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_visits_booking
    ON visits (booking_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_visits_table_session_user
    ON visits (table_session_id, user_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_visits_order_user
    ON visits (order_id, user_id);

CREATE INDEX IF NOT EXISTS idx_visits_venue_user_recent
    ON visits (venue_id, user_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_visits_user_recent
    ON visits (user_id, occurred_at DESC);
