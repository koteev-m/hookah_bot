ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS display_date DATE NULL;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS display_number INT NULL;

WITH numbered AS (
    SELECT
        id,
        CAST(scheduled_at AS DATE) AS booking_display_date,
        ROW_NUMBER() OVER (
            PARTITION BY venue_id, CAST(scheduled_at AS DATE)
            ORDER BY scheduled_at, id
        ) AS booking_display_number
    FROM bookings
)
UPDATE bookings b
SET
    display_date = COALESCE(b.display_date, numbered.booking_display_date),
    display_number = COALESCE(b.display_number, numbered.booking_display_number)
FROM numbered
WHERE b.id = numbered.id
  AND (b.display_date IS NULL OR b.display_number IS NULL);

ALTER TABLE bookings
    ALTER COLUMN display_date SET NOT NULL;

ALTER TABLE bookings
    ALTER COLUMN display_number SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bookings_venue_display_date_number
    ON bookings (venue_id, display_date, display_number);
