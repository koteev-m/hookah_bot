CREATE TABLE venues (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL,
    city TEXT NULL,
    address TEXT NULL,
    status TEXT NOT NULL
        CHECK (
            status IN (
                'draft',
                'onboarding',
                'active_published',
                'active_hidden',
                'paused_by_owner',
                'suspended_by_platform',
                'archived',
                'deletion_requested',
                'deleted'
            )
        )
);

CREATE INDEX idx_venues_status ON venues (status);
