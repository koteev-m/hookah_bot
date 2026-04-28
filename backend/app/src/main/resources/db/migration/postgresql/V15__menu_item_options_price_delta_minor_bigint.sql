DO $$
DECLARE
    schemas text[] := current_schemas(true);
    target_schema text;
BEGIN
    SELECT c.table_schema
    INTO target_schema
    FROM information_schema.columns c
    WHERE c.table_schema = ANY (schemas)
      AND c.table_name = 'menu_item_options'
      AND c.column_name = 'price_delta_minor'
    ORDER BY array_position(schemas, c.table_schema)
    LIMIT 1;

    IF target_schema IS NOT NULL THEN
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = target_schema
              AND table_name = 'menu_item_options'
              AND column_name = 'price_delta_minor'
              AND data_type <> 'bigint'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.menu_item_options ALTER COLUMN price_delta_minor TYPE BIGINT',
                target_schema
            );
        END IF;
    END IF;
END $$;
