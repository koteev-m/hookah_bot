CREATE TABLE IF NOT EXISTS telegram_staff_chat_order_messages (
    order_id BIGINT PRIMARY KEY REFERENCES orders(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    chat_id BIGINT NOT NULL,
    message_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_telegram_staff_chat_order_messages_venue
    ON telegram_staff_chat_order_messages (venue_id, updated_at);

CREATE TABLE IF NOT EXISTS telegram_staff_chat_order_outbox_links (
    outbox_id BIGINT PRIMARY KEY REFERENCES telegram_outbox(id) ON DELETE CASCADE,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_telegram_staff_chat_order_outbox_links_order
    ON telegram_staff_chat_order_outbox_links (order_id);
