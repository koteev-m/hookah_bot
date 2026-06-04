CREATE TABLE IF NOT EXISTS visit_feedback_messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feedback_id BIGINT NOT NULL,
    visit_id BIGINT NOT NULL,
    venue_id BIGINT NOT NULL,
    guest_user_id BIGINT NOT NULL,
    sender_type VARCHAR(32) NOT NULL,
    sender_user_id BIGINT NULL,
    message_text TEXT NOT NULL,
    delivered_at TIMESTAMP WITH TIME ZONE NULL,
    staff_chat_notified_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_visit_feedback_messages_feedback FOREIGN KEY (feedback_id) REFERENCES visit_feedback(id) ON DELETE CASCADE,
    CONSTRAINT fk_visit_feedback_messages_visit FOREIGN KEY (visit_id) REFERENCES visits(id) ON DELETE CASCADE,
    CONSTRAINT fk_visit_feedback_messages_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_visit_feedback_messages_guest FOREIGN KEY (guest_user_id) REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_visit_feedback_messages_sender FOREIGN KEY (sender_user_id) REFERENCES users(telegram_user_id) ON DELETE SET NULL,
    CONSTRAINT chk_visit_feedback_messages_sender_type CHECK (sender_type IN ('STAFF', 'GUEST'))
);

CREATE INDEX IF NOT EXISTS idx_visit_feedback_messages_feedback
    ON visit_feedback_messages (feedback_id, created_at);

CREATE INDEX IF NOT EXISTS idx_visit_feedback_messages_guest
    ON visit_feedback_messages (guest_user_id, created_at DESC);

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
                'VENUE_CONNECT_WAIT_CONTACT_CHOICE',
                'VENUE_CONNECT_WAIT_CONTACT',
                'VENUE_CONNECT_WAIT_CONTACT_EXTRA',
                'VENUE_CONNECT_WAIT_COMMENT',
                'OWNER_VENUE_TERMS_WAIT_TRIAL_CUSTOM_DATE',
                'OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE',
                'OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE',
                'OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE_DATE',
                'OWNER_VENUE_TERMS_WAIT_NOTE',
                'OWNER_VENUE_PROFILE_WAIT_NAME',
                'OWNER_VENUE_PROFILE_WAIT_CITY',
                'OWNER_VENUE_PROFILE_WAIT_ADDRESS',
                'OWNER_VENUE_PROFILE_WAIT_CONTACT',
                'OWNER_VENUE_PROFILE_WAIT_CARD_DESCRIPTION',
                'OWNER_VENUE_PROFILE_WAIT_HOURS_OPEN',
                'OWNER_VENUE_PROFILE_WAIT_HOURS_CLOSE',
                'OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TITLE',
                'OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TEXT',
                'OWNER_VENUE_DESCRIPTION_WAIT_SECTION_MEDIA',
                'OWNER_VENUE_ORDER_MENU_WAIT_SECTION_TITLE',
                'OWNER_VENUE_ORDER_MENU_WAIT_SECTION_RENAME',
                'OWNER_VENUE_ORDER_MENU_WAIT_ITEM_NAME',
                'OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE',
                'OWNER_VENUE_ORDER_MENU_WAIT_ITEM_RENAME',
                'OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE_EDIT',
                'OWNER_VENUE_ORDER_MENU_WAIT_OPTION_NAME',
                'OWNER_VENUE_TABLES_WAIT_NUMBER',
                'OWNER_VENUE_TABLES_WAIT_CAPACITY',
                'OWNER_VENUE_CREATE_WAIT_NAME',
                'OWNER_VENUE_CREATE_WAIT_CITY',
                'OWNER_VENUE_CREATE_WAIT_ADDRESS',
                'OWNER_LIMIT_REQUEST_WAIT_COUNT',
                'OWNER_LIMIT_REQUEST_WAIT_COMMENT',
                'PLATFORM_LIMIT_REQUEST_WAIT_APPROVED_COUNT',
                'VENUE_SETTINGS_WAIT_TIMEZONE',
                'VENUE_STAFF_ORDERS_WAIT_BATCH_CANCEL_REASON',
                'VENUE_STAFF_ORDERS_WAIT_ORDER_CANCEL_REASON',
                'VENUE_STAFF_ORDERS_WAIT_ITEM_EXCLUDE_REASON',
                'VENUE_STAFF_ORDERS_WAIT_ITEM_DISCOUNT_PERCENT',
                'VENUE_BOOKING_WAIT_GUEST_MESSAGE',
                'VENUE_BOOKING_CANCEL_WAIT_REASON',
                'VENUE_BOOKING_HOLD_WAIT_CUSTOM_MINUTES',
                'GUEST_BOOKING_WAIT_REPLY',
                'GUEST_FEEDBACK_WAIT_COMMENT',
                'STAFF_FEEDBACK_WAIT_REPLY',
                'GUEST_FEEDBACK_WAIT_REPLY',
                'GUEST_PROFILE_WAIT_NAME',
                'GUEST_PROFILE_WAIT_BIRTHDAY',
                'BOT_MENU_CART_WAIT_COMMENT'
            )
        );
