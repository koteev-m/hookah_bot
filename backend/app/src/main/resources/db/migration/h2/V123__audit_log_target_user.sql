ALTER TABLE audit_log
    ADD COLUMN target_user_id BIGINT NULL;

ALTER TABLE audit_log
    ADD CONSTRAINT fk_audit_log_target_user
        FOREIGN KEY (target_user_id)
        REFERENCES users(telegram_user_id)
        ON DELETE SET NULL;

CREATE INDEX idx_audit_log_target_user_created_at
    ON audit_log (target_user_id, created_at);
