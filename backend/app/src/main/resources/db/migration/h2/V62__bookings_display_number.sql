ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS display_date DATE NULL;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS display_number INT NULL;

UPDATE bookings
SET display_date = CAST(scheduled_at AS DATE)
WHERE display_date IS NULL;

MERGE INTO bookings b
USING (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY venue_id, display_date
            ORDER BY scheduled_at, id
        ) AS booking_display_number
    FROM bookings
) numbered
ON b.id = numbered.id
WHEN MATCHED THEN UPDATE SET
    display_number = COALESCE(b.display_number, numbered.booking_display_number);

ALTER TABLE bookings
    ALTER COLUMN display_date SET NOT NULL;

ALTER TABLE bookings
    ALTER COLUMN display_number SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bookings_venue_display_date_number
    ON bookings (venue_id, display_date, display_number);
