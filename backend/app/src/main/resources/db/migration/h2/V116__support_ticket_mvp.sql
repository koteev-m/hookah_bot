ALTER TABLE support_threads
    ALTER COLUMN venue_id DROP NOT NULL;

ALTER TABLE support_threads
    DROP CONSTRAINT IF EXISTS chk_support_threads_category;

ALTER TABLE support_threads
    DROP CONSTRAINT IF EXISTS chk_support_threads_status;

ALTER TABLE support_messages
    DROP CONSTRAINT IF EXISTS chk_support_messages_source;

ALTER TABLE support_threads
    ADD COLUMN IF NOT EXISTS table_id BIGINT REFERENCES venue_tables(id) ON DELETE SET NULL;

ALTER TABLE support_threads
    ADD COLUMN IF NOT EXISTS thread_type VARCHAR(32);

ALTER TABLE support_threads
    ADD COLUMN IF NOT EXISTS assignee_scope VARCHAR(32);

ALTER TABLE support_threads
    ADD COLUMN IF NOT EXISTS created_source VARCHAR(32);

ALTER TABLE support_threads
    ADD COLUMN IF NOT EXISTS app_version VARCHAR(80);

ALTER TABLE support_threads
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(120);

UPDATE support_threads
SET category =
    CASE
        WHEN category = 'BOOKING' THEN 'BOOKING'
        WHEN category IN ('ORDER', 'TABLE') THEN 'ORDER_SERVICE'
        WHEN category = 'PLATFORM' THEN 'MINIAPP_TECHNICAL'
        WHEN category = 'GENERAL' THEN 'OTHER'
        WHEN booking_id IS NOT NULL THEN 'BOOKING'
        ELSE 'OTHER'
    END;

UPDATE support_threads
SET thread_type =
    CASE
        WHEN booking_id IS NOT NULL OR category = 'BOOKING' THEN 'BOOKING_THREAD'
        ELSE 'SUPPORT_TICKET'
    END
WHERE thread_type IS NULL;

UPDATE support_threads
SET assignee_scope =
    CASE
        WHEN category IN ('MINIAPP_TECHNICAL', 'BILLING') OR venue_id IS NULL THEN 'PLATFORM'
        ELSE 'VENUE'
    END
WHERE assignee_scope IS NULL;

UPDATE support_threads
SET created_source =
    CASE
        WHEN thread_type = 'BOOKING_THREAD' THEN 'BOOKING_FLOW'
        ELSE 'GUEST_MINIAPP'
    END
WHERE created_source IS NULL;

ALTER TABLE support_threads
    ALTER COLUMN thread_type SET DEFAULT 'SUPPORT_TICKET';

ALTER TABLE support_threads
    ALTER COLUMN thread_type SET NOT NULL;

ALTER TABLE support_threads
    ALTER COLUMN assignee_scope SET DEFAULT 'VENUE';

ALTER TABLE support_threads
    ALTER COLUMN assignee_scope SET NOT NULL;

ALTER TABLE support_threads
    ALTER COLUMN created_source SET DEFAULT 'GUEST_MINIAPP';

ALTER TABLE support_threads
    ALTER COLUMN created_source SET NOT NULL;

ALTER TABLE support_threads
    ADD CONSTRAINT chk_support_threads_category
        CHECK (category IN ('BOOKING', 'ORDER_SERVICE', 'MINIAPP_TECHNICAL', 'BILLING', 'OTHER'));

ALTER TABLE support_threads
    ADD CONSTRAINT chk_support_threads_status
        CHECK (status IN ('OPEN', 'NEW', 'IN_PROGRESS', 'WAITING_USER', 'RESOLVED', 'CLOSED'));

ALTER TABLE support_threads
    ADD CONSTRAINT chk_support_threads_thread_type
        CHECK (thread_type IN ('BOOKING_THREAD', 'SUPPORT_TICKET'));

ALTER TABLE support_threads
    ADD CONSTRAINT chk_support_threads_assignee_scope
        CHECK (assignee_scope IN ('VENUE', 'PLATFORM'));

ALTER TABLE support_threads
    ADD CONSTRAINT chk_support_threads_created_source
        CHECK (created_source IN ('BOOKING_FLOW', 'GUEST_MINIAPP', 'GUEST_BOT'));

ALTER TABLE support_messages
    ADD CONSTRAINT chk_support_messages_source
        CHECK (source IN ('GUEST_BOT', 'GUEST_MINIAPP', 'VENUE_MINIAPP', 'PLATFORM_MINIAPP', 'STAFF_CHAT', 'SYSTEM'));

CREATE INDEX IF NOT EXISTS idx_support_threads_type_updated
    ON support_threads (thread_type, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_support_threads_scope_updated
    ON support_threads (assignee_scope, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_support_threads_status_updated
    ON support_threads (status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_support_threads_table_session
    ON support_threads (table_session_id);

CREATE INDEX IF NOT EXISTS idx_support_threads_order
    ON support_threads (order_id);

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
                'GUEST_SUPPORT_WAIT_MESSAGE',
                'BOT_JOIN_SHARED_WAIT_CODE',
                'VENUE_CONNECT_WAIT_NAME',
                'VENUE_CONNECT_WAIT_CITY',
                'VENUE_CONNECT_WAIT_CONTACT_CHOICE',
                'VENUE_CONNECT_WAIT_CONTACT',
                'VENUE_CONNECT_WAIT_CONTACT_EXTRA',
                'VENUE_CONNECT_WAIT_COMMENT',
                'OWNER_VENUE_TERMS_WAIT_TRIAL_CUSTOM_DATE',
                'OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE',
                'OWNER_VENUE_TERMS_WAIT_ZERO_PRICE_CONFIRM',
                'OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE',
                'OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE_DATE',
                'OWNER_VENUE_TERMS_WAIT_NOTE',
                'PLATFORM_SUBSCRIPTION_WAIT_CURRENT_PRICE',
                'PLATFORM_SUBSCRIPTION_WAIT_ZERO_PRICE_CONFIRM',
                'PLATFORM_SUBSCRIPTION_WAIT_FUTURE_PRICE',
                'PLATFORM_SUBSCRIPTION_WAIT_FUTURE_PRICE_DATE',
                'PLATFORM_VENUE_STATUS_WAIT_SUSPEND_REASON',
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
                'VENUE_PROMOTION_WAIT_MEDIA',
                'VENUE_PROMOTION_RULE_WAIT_PERCENT',
                'VENUE_PROMOTION_RULE_WAIT_START_TIME',
                'VENUE_PROMOTION_RULE_WAIT_END_TIME',
                'PLATFORM_PROMOTION_PLACEMENT_WAIT_END_DATE',
                'VENUE_LOYALTY_WAIT_CUSTOM_N',
                'AI_WAIT_PROMOTION_TEXT_BRIEF',
                'AI_WAIT_REVIEW_REPLY_BRIEF',
                'AI_WAIT_BANNER_TEXT_BRIEF',
                'GUEST_PROFILE_WAIT_NAME',
                'GUEST_PROFILE_WAIT_BIRTHDAY',
                'BOT_MENU_CART_WAIT_COMMENT'
            )
        );
