ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS entity_type VARCHAR;
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS entity_id BIGINT;
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS payload_json TEXT;

UPDATE audit_log
SET entity_type = 'venue'
WHERE entity_type IS NULL
  AND venue_id IS NOT NULL;

UPDATE audit_log
SET entity_id = venue_id
WHERE entity_id IS NULL
  AND venue_id IS NOT NULL;

UPDATE audit_log
SET payload_json = payload
WHERE payload_json IS NULL;

ALTER TABLE audit_log ALTER COLUMN action SET DATA TYPE VARCHAR;
ALTER TABLE audit_log ALTER COLUMN created_at SET DATA TYPE TIMESTAMP;
ALTER TABLE audit_log ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE audit_log ALTER COLUMN actor_user_id SET NOT NULL;
ALTER TABLE audit_log ALTER COLUMN action SET NOT NULL;
ALTER TABLE audit_log ALTER COLUMN entity_type SET NOT NULL;
ALTER TABLE audit_log ALTER COLUMN payload_json SET NOT NULL;

ALTER TABLE audit_log DROP COLUMN IF EXISTS payload;
ALTER TABLE audit_log DROP COLUMN IF EXISTS venue_id;

DROP INDEX IF EXISTS idx_audit_log_venue;
DROP INDEX IF EXISTS idx_audit_log_action;

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_type, entity_id);
