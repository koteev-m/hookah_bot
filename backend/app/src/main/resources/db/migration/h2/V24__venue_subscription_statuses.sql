ALTER TABLE venue_subscriptions
    DROP CONSTRAINT IF EXISTS "CONSTRAINT_D6";

ALTER TABLE venue_subscriptions
    ADD CONSTRAINT venue_subscriptions_status_check
    CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'SUSPENDED_BY_PLATFORM'));
