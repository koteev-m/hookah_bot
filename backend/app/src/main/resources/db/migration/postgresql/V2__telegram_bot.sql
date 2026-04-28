CREATE TABLE staff_calls (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE CASCADE,
    created_by_user_id BIGINT NULL REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    reason TEXT NOT NULL CHECK (reason IN ('COALS', 'BILL', 'COME', 'OTHER')),
    comment TEXT NULL,
    status TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'ACK', 'DONE', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_calls_venue ON staff_calls (venue_id);
CREATE INDEX idx_staff_calls_status ON staff_calls (status);
CREATE INDEX idx_staff_calls_table ON staff_calls (table_id);

CREATE TABLE telegram_processed_updates (
    update_id BIGINT PRIMARY KEY,
    chat_id BIGINT NULL,
    message_id BIGINT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_telegram_processed_chat_message
    ON telegram_processed_updates (chat_id, message_id)
    WHERE message_id IS NOT NULL;

CREATE TABLE telegram_chat_context (
    chat_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
    venue_id BIGINT NULL REFERENCES venues(id) ON DELETE SET NULL,
    table_id BIGINT NULL REFERENCES venue_tables(id) ON DELETE SET NULL,
    table_token VARCHAR(64) NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_telegram_chat_context_user ON telegram_chat_context (user_id);

CREATE TABLE telegram_dialog_state (
    chat_id BIGINT PRIMARY KEY,
    state TEXT NOT NULL CHECK (
        state IN ('NONE', 'QUICK_ORDER_WAIT_TEXT', 'QUICK_ORDER_WAIT_CONFIRM', 'STAFF_CALL_WAIT_COMMENT')
    ),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
