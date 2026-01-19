CREATE TABLE IF NOT EXISTS venue_subscriptions (
    venue_id BIGINT PRIMARY KEY REFERENCES venues(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    trial_end TIMESTAMPTZ NULL,
    paid_start TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE venue_subscriptions
    ADD COLUMN IF NOT EXISTS trial_end TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS paid_start TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

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
