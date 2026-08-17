ALTER TABLE support_messages
    ADD COLUMN client_message_id VARCHAR(64) NULL;

ALTER TABLE support_messages
    ADD CONSTRAINT chk_support_messages_client_message_id_scope
        CHECK (
            client_message_id IS NULL
            OR (
                source IN ('GUEST_MINIAPP', 'VENUE_MINIAPP')
                AND author_user_id IS NOT NULL
            )
        );

CREATE UNIQUE INDEX uq_support_messages_miniapp_client_message
    ON support_messages (thread_id, source, author_user_id, client_message_id);
