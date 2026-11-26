CREATE TABLE shift_extension_request_keys (
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES table_sessions(id) ON DELETE CASCADE,
    requested_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    visit_generation BIGINT NOT NULL CHECK (visit_generation >= 0),
    request_id BIGINT NOT NULL REFERENCES shift_extension_requests(id) ON DELETE CASCADE,
    request_comment TEXT,
    PRIMARY KEY (venue_id, table_session_id, requested_by_user_id, idempotency_key)
);
CREATE INDEX idx_shift_extension_request_keys_request ON shift_extension_request_keys(request_id);
