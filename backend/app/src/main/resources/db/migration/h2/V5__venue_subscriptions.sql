CREATE TABLE IF NOT EXISTS venue_subscriptions (
    venue_id BIGINT PRIMARY KEY,
    status TEXT NOT NULL CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED')),
    trial_end TIMESTAMP WITH TIME ZONE NULL,
    paid_start TIMESTAMP WITH TIME ZONE NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_venue_subscriptions_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_venue_subscriptions_status ON venue_subscriptions (status);

INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
SELECT venues.id,
       'TRIAL',
       DATEADD('DAY', 14, CURRENT_TIMESTAMP),
       NULL,
       CURRENT_TIMESTAMP
FROM venues
WHERE NOT EXISTS (
    SELECT 1
    FROM venue_subscriptions
    WHERE venue_subscriptions.venue_id = venues.id
);
