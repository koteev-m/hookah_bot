ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE venues
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE venues
    ADD COLUMN status_new VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        CHECK (status_new IN ('DRAFT','PUBLISHED','HIDDEN','PAUSED','SUSPENDED','ARCHIVED','DELETED'));

UPDATE venues
SET status_new = CASE status
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
SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP)
WHERE status_new = 'DELETED';

ALTER TABLE venues DROP COLUMN status;
ALTER TABLE venues ALTER COLUMN status_new RENAME TO status;

ALTER TABLE venues ALTER COLUMN status SET DEFAULT 'DRAFT';

DROP INDEX IF EXISTS idx_venues_status;
CREATE INDEX idx_venues_status ON venues (status);
