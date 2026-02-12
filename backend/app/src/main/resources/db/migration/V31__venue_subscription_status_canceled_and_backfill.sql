UPDATE venue_subscriptions
SET status = CASE UPPER(status)
    WHEN 'CANCELLED' THEN 'CANCELED'
    ELSE UPPER(status)
END
WHERE status IS NOT NULL;

ALTER TABLE venue_subscriptions
    DROP CONSTRAINT IF EXISTS venue_subscriptions_status_check;

ALTER TABLE venue_subscriptions
    ADD CONSTRAINT venue_subscriptions_status_check
    CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'SUSPENDED', 'SUSPENDED_BY_PLATFORM'));
