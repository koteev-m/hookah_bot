-- flyway:executeInTransaction=false
DO $$
DECLARE
    table_oid OID;
    table_schema TEXT;
    table_name TEXT;
    enum_schema TEXT;
    enum_type TEXT;
    source_attnum SMALLINT;
    constraint_record RECORD;
    constraint_expr TEXT;
    updated_constraints BOOLEAN := FALSE;
BEGIN
    SELECT c.oid, n.nspname, c.relname
    INTO table_oid, table_schema, table_name
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relname = 'order_batches'
      AND c.relkind = 'r'
      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    ORDER BY n.nspname
    LIMIT 1;

    IF table_oid IS NULL THEN
        RAISE NOTICE 'order_batches table not found, skipping';
        RETURN;
    END IF;

    SELECT n.nspname, t.typname
    INTO enum_schema, enum_type
    FROM pg_type t
    JOIN pg_namespace n ON n.oid = t.typnamespace
    JOIN pg_attribute a ON a.atttypid = t.oid
    WHERE a.attrelid = table_oid
      AND a.attname = 'source'
      AND t.typtype = 'e';

    IF enum_type IS NOT NULL THEN
        EXECUTE format(
            'ALTER TYPE %I.%I ADD VALUE IF NOT EXISTS ''MINIAPP''',
            enum_schema,
            enum_type
        );
        RETURN;
    END IF;

    SELECT a.attnum
    INTO source_attnum
    FROM pg_attribute a
    WHERE a.attrelid = table_oid
      AND a.attname = 'source'
      AND NOT a.attisdropped;

    IF source_attnum IS NULL THEN
        RAISE NOTICE 'order_batches.source column not found, skipping';
        RETURN;
    END IF;

    FOR constraint_record IN
        SELECT c.conname, pg_get_constraintdef(c.oid) AS condef
        FROM pg_constraint c
        WHERE c.contype = 'c'
          AND c.conrelid = table_oid
          AND c.conkey @> ARRAY[source_attnum]::smallint[]
    LOOP
        IF constraint_record.condef ILIKE '%MINIAPP%' THEN
            RAISE NOTICE 'order_batches.source constraint % already allows MINIAPP', constraint_record.conname;
            CONTINUE;
        END IF;

        constraint_expr := substring(constraint_record.condef FROM '^CHECK\\s*\\((.*)\\)$');
        IF constraint_expr IS NULL THEN
            RAISE NOTICE 'order_batches.source constraint % has unexpected format: %',
                constraint_record.conname,
                constraint_record.condef;
            CONTINUE;
        END IF;

        EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT %I', table_schema, table_name, constraint_record.conname);
        EXECUTE format(
            'ALTER TABLE %I.%I ADD CONSTRAINT %I CHECK ((source = ''MINIAPP'') OR (%s))',
            table_schema,
            table_name,
            constraint_record.conname,
            constraint_expr
        );
        updated_constraints := TRUE;
    END LOOP;

    IF NOT updated_constraints THEN
        RAISE NOTICE 'order_batches.source constraint not found or already allows MINIAPP';
    END IF;
END $$;
