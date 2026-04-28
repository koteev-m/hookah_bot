CREATE TABLE IF NOT EXISTS venue_connection_requests (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    telegram_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    venue_name TEXT NOT NULL,
    city TEXT NOT NULL,
    contact TEXT NOT NULL,
    comment TEXT NULL,
    status TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_venue_connection_requests_status_created
    ON venue_connection_requests (status, created_at DESC);

ALTER TABLE IF EXISTS telegram_dialog_state
    DROP CONSTRAINT IF EXISTS telegram_dialog_state_state_check;

ALTER TABLE IF EXISTS telegram_dialog_state
    ADD CONSTRAINT telegram_dialog_state_state_check
        CHECK (
            state IN (
                'NONE',
                'QUICK_ORDER_WAIT_TEXT',
                'QUICK_ORDER_WAIT_CONFIRM',
                'STAFF_CALL_WAIT_COMMENT',
                'BOT_JOIN_SHARED_WAIT_CODE',
                'VENUE_CONNECT_WAIT_NAME',
                'VENUE_CONNECT_WAIT_CITY',
                'VENUE_CONNECT_WAIT_CONTACT',
                'VENUE_CONNECT_WAIT_COMMENT'
            )
        );

