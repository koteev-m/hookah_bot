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
                'BOT_JOIN_SHARED_WAIT_CODE'
            )
        );
