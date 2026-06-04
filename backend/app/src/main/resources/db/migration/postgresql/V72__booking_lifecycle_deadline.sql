CREATE TABLE IF NOT EXISTS venue_booking_settings (
    venue_id BIGINT PRIMARY KEY REFERENCES venues(id) ON DELETE CASCADE,
    hold_minutes INTEGER NOT NULL DEFAULT 30,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT venue_booking_settings_hold_minutes_check CHECK (hold_minutes IN (15, 30, 60))
);

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS arrival_deadline_at TIMESTAMPTZ NULL;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS seated_at TIMESTAMPTZ NULL;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS no_show_at TIMESTAMPTZ NULL;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS expired_at TIMESTAMPTZ NULL;
