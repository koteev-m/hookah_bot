CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    venue_id BIGINT NULL REFERENCES venues(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_audit_log_venue ON audit_log (venue_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log (action);
