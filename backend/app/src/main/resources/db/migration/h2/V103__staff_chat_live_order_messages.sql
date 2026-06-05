CREATE TABLE IF NOT EXISTS telegram_staff_chat_order_messages (
    order_id BIGINT PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    message_id BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_staff_chat_order_message_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_chat_order_message_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_telegram_staff_chat_order_messages_venue
    ON telegram_staff_chat_order_messages (venue_id, updated_at);

CREATE TABLE IF NOT EXISTS telegram_staff_chat_order_outbox_links (
    outbox_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    CONSTRAINT fk_staff_chat_order_outbox_link_outbox FOREIGN KEY (outbox_id) REFERENCES telegram_outbox(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_staff_chat_order_outbox_link_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_telegram_staff_chat_order_outbox_links_order
    ON telegram_staff_chat_order_outbox_links (order_id);
