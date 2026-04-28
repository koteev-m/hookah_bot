ALTER TABLE venue_connection_requests
    ADD COLUMN IF NOT EXISTS linked_venue_id BIGINT NULL;

ALTER TABLE venue_connection_requests
    DROP CONSTRAINT IF EXISTS fk_venue_connection_requests_linked_venue;

ALTER TABLE venue_connection_requests
    ADD CONSTRAINT fk_venue_connection_requests_linked_venue
        FOREIGN KEY (linked_venue_id) REFERENCES venues(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_venue_connection_requests_linked_venue_id
    ON venue_connection_requests (linked_venue_id);

