DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'menu_item_options'
          AND column_name = 'price_delta_minor'
          AND data_type <> 'bigint'
    ) THEN
        ALTER TABLE menu_item_options
            ALTER COLUMN price_delta_minor TYPE BIGINT;
    END IF;
END $$;
