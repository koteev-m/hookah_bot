ALTER TABLE telegram_staff_chat_link_codes
    DROP CONSTRAINT IF EXISTS telegram_staff_chat_link_codes_created_by_user_id_fkey;

ALTER TABLE telegram_staff_chat_link_codes
    ALTER COLUMN created_by_user_id DROP NOT NULL;

ALTER TABLE telegram_staff_chat_link_codes
    ADD CONSTRAINT telegram_staff_chat_link_codes_created_by_user_id_fkey
        FOREIGN KEY (created_by_user_id) REFERENCES users(telegram_user_id) ON DELETE SET NULL;
