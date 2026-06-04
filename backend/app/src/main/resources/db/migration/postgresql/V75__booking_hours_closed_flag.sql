ALTER TABLE venue_booking_hours
    ADD COLUMN IF NOT EXISTS is_closed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE venue_booking_hours_overrides
    ADD COLUMN IF NOT EXISTS is_closed BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE venue_booking_hours
SET is_closed = TRUE
WHERE opens_at = closes_at;

UPDATE venue_booking_hours_overrides
SET is_closed = TRUE
WHERE opens_at = closes_at;
