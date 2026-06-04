CREATE TABLE IF NOT EXISTS venue_promotions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL,
    title VARCHAR NOT NULL,
    description VARCHAR NOT NULL,
    terms VARCHAR NULL,
    starts_at TIMESTAMP WITH TIME ZONE NULL,
    ends_at TIMESTAMP WITH TIME ZONE NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    created_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_venue_promotions_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT fk_venue_promotions_user FOREIGN KEY (created_by_user_id) REFERENCES users(telegram_user_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_venue_promotions_venue_status
    ON venue_promotions (venue_id, status);

CREATE INDEX IF NOT EXISTS idx_venue_promotions_status_period
    ON venue_promotions (status, starts_at, ends_at);

CREATE INDEX IF NOT EXISTS idx_venue_promotions_venue_updated
    ON venue_promotions (venue_id, updated_at DESC);

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
                'VENUE_FEEDBACK_WAIT_PUBLIC_REVIEW_URL',
                'VENUE_PROMOTION_WAIT_TITLE',
                'VENUE_PROMOTION_WAIT_DESCRIPTION',
                'VENUE_PROMOTION_WAIT_TERMS',
                'VENUE_PROMOTION_WAIT_STARTS_AT',
                'VENUE_PROMOTION_WAIT_ENDS_AT',
                'GUEST_PROFILE_WAIT_NAME',
                'GUEST_PROFILE_WAIT_BIRTHDAY',
                'BOT_MENU_CART_WAIT_COMMENT'
            )
        );
