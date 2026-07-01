ALTER TABLE staff_calls
    ADD COLUMN IF NOT EXISTS order_id BIGINT NULL REFERENCES orders(id) ON DELETE SET NULL;

ALTER TABLE staff_calls
    ADD COLUMN IF NOT EXISTS tab_id BIGINT NULL REFERENCES tab(id) ON DELETE SET NULL;

ALTER TABLE staff_calls
    ADD COLUMN IF NOT EXISTS payment_method VARCHAR(16) NULL;

ALTER TABLE staff_calls
    ADD CONSTRAINT chk_staff_calls_payment_method
    CHECK (payment_method IS NULL OR payment_method IN ('CARD', 'CASH', 'UNKNOWN'));

CREATE INDEX IF NOT EXISTS idx_staff_calls_bill_request_active
    ON staff_calls (venue_id, table_session_id, tab_id, reason, status);
