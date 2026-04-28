ALTER TABLE venue_subscriptions
    DROP CONSTRAINT IF EXISTS venue_subscriptions_status_check;

ALTER TABLE venue_subscriptions
    ADD CONSTRAINT venue_subscriptions_status_check
    CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'SUSPENDED_BY_PLATFORM'));
