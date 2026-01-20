CREATE TABLE staff_calls (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    created_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    reason TEXT NOT NULL CHECK (reason IN ('COALS', 'BILL', 'COME', 'OTHER')),
    comment TEXT NULL,
    status TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'ACK', 'DONE', 'CANCELLED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_staff_calls_venue ON staff_calls (venue_id);
CREATE INDEX idx_staff_calls_status ON staff_calls (status);
CREATE INDEX idx_staff_calls_table ON staff_calls (table_id);
