ALTER TABLE venue_connection_requests
    DROP CONSTRAINT IF EXISTS venue_connection_requests_status_check;

UPDATE venue_connection_requests
SET status = 'PENDING'
WHERE status = 'NEW';

ALTER TABLE venue_connection_requests
    ALTER COLUMN status SET DEFAULT 'PENDING';

ALTER TABLE venue_connection_requests
    ADD CONSTRAINT venue_connection_requests_status_check
        CHECK (
            status IN (
                'PENDING',
                'APPROVED',
                'REJECTED',
                'CANCELLED'
            )
        );
