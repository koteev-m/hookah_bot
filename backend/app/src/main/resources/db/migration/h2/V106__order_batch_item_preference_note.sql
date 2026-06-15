ALTER TABLE IF EXISTS order_batch_items
    ADD COLUMN IF NOT EXISTS preference_note VARCHAR(200) NULL;

ALTER TABLE IF EXISTS order_batch_items
    ADD CONSTRAINT IF NOT EXISTS chk_order_batch_items_preference_note_length
    CHECK (preference_note IS NULL OR CHAR_LENGTH(preference_note) <= 200);
