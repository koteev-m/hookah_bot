CREATE TABLE IF NOT EXISTS venue_booking_hours (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    weekday SMALLINT NOT NULL,
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT venue_booking_hours_weekday_check CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT venue_booking_hours_time_check CHECK (opens_at <> closes_at),
    CONSTRAINT venue_booking_hours_venue_weekday_unique UNIQUE (venue_id, weekday)
);

CREATE INDEX IF NOT EXISTS idx_venue_booking_hours_venue_weekday
    ON venue_booking_hours (venue_id, weekday);
