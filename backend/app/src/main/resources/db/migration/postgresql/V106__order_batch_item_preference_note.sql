ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS preference_note VARCHAR(200) NULL;

DO $$
BEGIN
    ALTER TABLE order_batch_items
        ADD CONSTRAINT chk_order_batch_items_preference_note_length
        CHECK (preference_note IS NULL OR char_length(preference_note) <= 200);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
