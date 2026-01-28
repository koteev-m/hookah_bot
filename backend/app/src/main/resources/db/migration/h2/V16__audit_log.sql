CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id BIGINT NULL,
    venue_id BIGINT NULL,
    action TEXT NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_audit_log_venue ON audit_log (venue_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action);
