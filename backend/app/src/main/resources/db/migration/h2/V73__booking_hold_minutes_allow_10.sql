ALTER TABLE venue_booking_settings
    DROP CONSTRAINT IF EXISTS venue_booking_settings_hold_minutes_check;

ALTER TABLE venue_booking_settings
    ADD CONSTRAINT venue_booking_settings_hold_minutes_check
        CHECK (hold_minutes IN (10, 15, 30, 60));
