ALTER TABLE venue_booking_hours_overrides
    ADD COLUMN IF NOT EXISTS guest_note TEXT;
