ALTER TABLE venue_connection_requests
    ADD COLUMN IF NOT EXISTS trial_configured BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE venue_connection_requests
    ADD COLUMN IF NOT EXISTS trial_ends_on DATE NULL;

ALTER TABLE venue_connection_requests
    ADD COLUMN IF NOT EXISTS current_price_rub BIGINT NULL;

ALTER TABLE venue_connection_requests
    ADD COLUMN IF NOT EXISTS future_price_rub BIGINT NULL;

ALTER TABLE venue_connection_requests
    ADD COLUMN IF NOT EXISTS future_price_effective_on DATE NULL;

ALTER TABLE venue_connection_requests
    ADD COLUMN IF NOT EXISTS commercial_note TEXT NULL;
