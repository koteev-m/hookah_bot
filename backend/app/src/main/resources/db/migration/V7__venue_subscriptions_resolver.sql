ALTER TABLE venue_subscriptions
    ADD COLUMN IF NOT EXISTS trial_end TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS paid_start TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_venue_subscriptions_status ON venue_subscriptions (status);

INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
SELECT venues.id,
       'TRIAL',
       now() + interval '14 days',
       NULL,
       now()
FROM venues
WHERE NOT EXISTS (
    SELECT 1
    FROM venue_subscriptions
    WHERE venue_subscriptions.venue_id = venues.id
);
