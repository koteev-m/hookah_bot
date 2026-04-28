ALTER TABLE order_batches
    ADD COLUMN IF NOT EXISTS rejected_reason_code TEXT NULL,
    ADD COLUMN IF NOT EXISTS rejected_reason_text TEXT NULL,
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ NULL;

CREATE TABLE IF NOT EXISTS order_audit_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    actor_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    actor_role TEXT NOT NULL,
    action TEXT NOT NULL,
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL,
    reason_code TEXT NULL,
    reason_text TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_audit_order ON order_audit_log (order_id, created_at);
