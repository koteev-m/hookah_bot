CREATE TABLE IF NOT EXISTS venue_owner_accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    primary_owner_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    allowed_venues_count INTEGER NOT NULL CHECK (allowed_venues_count >= 0),
    notes TEXT NULL,
    commercial_note TEXT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    CONSTRAINT uq_venue_owner_accounts_primary_owner UNIQUE (primary_owner_user_id)
);

ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS owner_account_id BIGINT NULL;

INSERT INTO venue_owner_accounts (primary_owner_user_id, allowed_venues_count)
SELECT owner_counts.user_id, GREATEST(1, CAST(owner_counts.used_count AS INTEGER))
FROM (
    SELECT
        po.user_id,
        COUNT(CASE
            WHEN COALESCE(v.status, 'DRAFT') IN ('DRAFT', 'PUBLISHED', 'HIDDEN', 'PAUSED', 'SUSPENDED')
            THEN 1
        END) AS used_count
    FROM (
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
    ) po
    JOIN venues v ON v.id = po.venue_id
    GROUP BY po.user_id
) owner_counts
WHERE NOT EXISTS (
    SELECT 1
    FROM venue_owner_accounts voa
    WHERE voa.primary_owner_user_id = owner_counts.user_id
);

UPDATE venues v
SET owner_account_id = (
    SELECT voa.id
    FROM (
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
    ) po
    JOIN venue_owner_accounts voa ON voa.primary_owner_user_id = po.user_id
    WHERE po.venue_id = v.id
)
WHERE v.owner_account_id IS NULL
  AND EXISTS (
      SELECT 1
      FROM (
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
      ) po
      WHERE po.venue_id = v.id
  );

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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMP WITH TIME ZONE NULL,
    decided_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_owner_venue_limit_requests_account_status
    ON owner_venue_limit_requests (owner_account_id, status, created_at DESC);
