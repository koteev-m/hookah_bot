CREATE TABLE IF NOT EXISTS venue_owner_accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    primary_owner_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    allowed_venues_count INTEGER NOT NULL CHECK (allowed_venues_count >= 0),
    notes TEXT NULL,
    commercial_note TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    CONSTRAINT uq_venue_owner_accounts_primary_owner UNIQUE (primary_owner_user_id)
);

ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS owner_account_id BIGINT NULL;

WITH primary_owners AS (
    SELECT venue_id, user_id
    FROM (
        SELECT
            vm.venue_id,
            vm.user_id,
            ROW_NUMBER() OVER (
                PARTITION BY vm.venue_id
                ORDER BY vm.created_at ASC, vm.user_id ASC
            ) AS rn
        FROM venue_members vm
        WHERE UPPER(vm.role) = 'OWNER'
    ) ranked
    WHERE rn = 1
),
owner_counts AS (
    SELECT
        po.user_id,
        COUNT(*) FILTER (
            WHERE COALESCE(v.status, 'DRAFT') IN ('DRAFT', 'PUBLISHED', 'HIDDEN', 'PAUSED', 'SUSPENDED')
        ) AS used_count
    FROM primary_owners po
    JOIN venues v ON v.id = po.venue_id
    GROUP BY po.user_id
)
INSERT INTO venue_owner_accounts (primary_owner_user_id, allowed_venues_count)
SELECT user_id, GREATEST(1, used_count::INTEGER)
FROM owner_counts
ON CONFLICT (primary_owner_user_id) DO UPDATE
SET allowed_venues_count = GREATEST(
        venue_owner_accounts.allowed_venues_count,
        EXCLUDED.allowed_venues_count
    ),
    updated_at = now();

WITH primary_owners AS (
    SELECT venue_id, user_id
    FROM (
        SELECT
            vm.venue_id,
            vm.user_id,
            ROW_NUMBER() OVER (
                PARTITION BY vm.venue_id
                ORDER BY vm.created_at ASC, vm.user_id ASC
            ) AS rn
        FROM venue_members vm
        WHERE UPPER(vm.role) = 'OWNER'
    ) ranked
    WHERE rn = 1
)
UPDATE venues v
SET owner_account_id = voa.id
FROM primary_owners po
JOIN venue_owner_accounts voa ON voa.primary_owner_user_id = po.user_id
WHERE v.id = po.venue_id
  AND v.owner_account_id IS NULL;

ALTER TABLE venues
    DROP CONSTRAINT IF EXISTS fk_venues_owner_account;

ALTER TABLE venues
    ADD CONSTRAINT fk_venues_owner_account
        FOREIGN KEY (owner_account_id)
        REFERENCES venue_owner_accounts(id)
        ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_venues_owner_account
    ON venues (owner_account_id);

CREATE TABLE IF NOT EXISTS owner_venue_limit_requests (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_account_id BIGINT NOT NULL REFERENCES venue_owner_accounts(id) ON DELETE CASCADE,
    requested_extra_count INTEGER NOT NULL CHECK (requested_extra_count > 0),
    comment TEXT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ NULL,
    decided_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_owner_venue_limit_requests_account_status
    ON owner_venue_limit_requests (owner_account_id, status, created_at DESC);
