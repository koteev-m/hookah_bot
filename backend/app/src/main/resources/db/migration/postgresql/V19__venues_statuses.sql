ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;

UPDATE venues
SET status = CASE status
    WHEN 'draft' THEN 'DRAFT'
    WHEN 'onboarding' THEN 'DRAFT'
    WHEN 'active_published' THEN 'PUBLISHED'
    WHEN 'active_hidden' THEN 'HIDDEN'
    WHEN 'paused_by_owner' THEN 'PAUSED'
    WHEN 'suspended_by_platform' THEN 'SUSPENDED'
    WHEN 'archived' THEN 'ARCHIVED'
    WHEN 'deletion_requested' THEN 'DELETED'
    WHEN 'deleted' THEN 'DELETED'
    ELSE status
END;

UPDATE venues
SET deleted_at = COALESCE(deleted_at, now())
WHERE status = 'DELETED';

ALTER TABLE venues
    ALTER COLUMN status SET DEFAULT 'DRAFT';

ALTER TABLE venues
    DROP CONSTRAINT IF EXISTS venues_status_check;

ALTER TABLE venues
    ADD CONSTRAINT venues_status_check CHECK (
        status IN ('DRAFT','PUBLISHED','HIDDEN','PAUSED','SUSPENDED','ARCHIVED','DELETED')
    );
