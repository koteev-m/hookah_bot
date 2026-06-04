CREATE TABLE IF NOT EXISTS visit_feedback_requests (
    id BIGSERIAL PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    scheduled_for TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PENDING', 'SENT', 'CANCELED', 'SKIPPED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    dedupe_key VARCHAR(128) NOT NULL UNIQUE,
    sent_at TIMESTAMPTZ NULL,
    last_error TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_visit_feedback_requests_status_due
    ON visit_feedback_requests (status, scheduled_for);

CREATE INDEX IF NOT EXISTS idx_visit_feedback_requests_visit
    ON visit_feedback_requests (visit_id);

CREATE INDEX IF NOT EXISTS idx_visit_feedback_requests_venue_user
    ON visit_feedback_requests (venue_id, user_id);

CREATE TABLE IF NOT EXISTS visit_feedback (
    id BIGSERIAL PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    rating INT NULL CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    comment TEXT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('SUBMITTED', 'SKIPPED')),
    staff_notified_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_visit_feedback_visit_user UNIQUE (visit_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_visit_feedback_venue_user
    ON visit_feedback (venue_id, user_id);

ALTER TABLE telegram_dialog_state
    DROP CONSTRAINT IF EXISTS telegram_dialog_state_state_check;

ALTER TABLE telegram_dialog_state
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
                'GUEST_PROFILE_WAIT_NAME',
                'GUEST_PROFILE_WAIT_BIRTHDAY',
                'BOT_MENU_CART_WAIT_COMMENT'
            )
        );
