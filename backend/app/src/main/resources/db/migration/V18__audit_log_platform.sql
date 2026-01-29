ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS entity_type VARCHAR,
    ADD COLUMN IF NOT EXISTS entity_id BIGINT,
    ADD COLUMN IF NOT EXISTS payload_json TEXT;

UPDATE audit_log
SET entity_type = COALESCE(entity_type, CASE WHEN venue_id IS NOT NULL THEN 'venue' ELSE 'unknown' END),
    entity_id = COALESCE(entity_id, CASE WHEN venue_id IS NOT NULL THEN venue_id ELSE NULL END),
    payload_json = COALESCE(payload_json, payload::text, '{}');

ALTER TABLE audit_log
    ALTER COLUMN actor_user_id SET NOT NULL,
    ALTER COLUMN action TYPE VARCHAR,
    ALTER COLUMN action SET NOT NULL,
    ALTER COLUMN entity_type SET NOT NULL,
    ALTER COLUMN payload_json SET NOT NULL,
    ALTER COLUMN created_at TYPE TIMESTAMP,
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE audit_log
    DROP COLUMN IF EXISTS payload,
    DROP COLUMN IF EXISTS venue_id;

DROP INDEX IF EXISTS idx_audit_log_venue;
DROP INDEX IF EXISTS idx_audit_log_action;

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_type, entity_id);
