ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2),
    ADD COLUMN IF NOT EXISTS formatted_address TEXT,
    ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

ALTER TABLE venues
    ADD CONSTRAINT chk_venues_location_coordinates_pair
    CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL)
    );

ALTER TABLE venues
    ADD CONSTRAINT chk_venues_location_latitude_range
    CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90));

ALTER TABLE venues
    ADD CONSTRAINT chk_venues_location_longitude_range
    CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180));
