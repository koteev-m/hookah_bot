ALTER TABLE venue_booking_hours
    DROP CONSTRAINT IF EXISTS venue_booking_hours_time_check;

CREATE TABLE IF NOT EXISTS venue_booking_hours_overrides (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    service_date DATE NOT NULL,
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT venue_booking_hours_overrides_venue_date_unique UNIQUE (venue_id, service_date)
);

CREATE INDEX IF NOT EXISTS idx_venue_booking_hours_overrides_venue_date
    ON venue_booking_hours_overrides (venue_id, service_date);
