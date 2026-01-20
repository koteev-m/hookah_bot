DO $$
DECLARE
    enum_type TEXT;
    constraint_name TEXT;
    constraint_def TEXT;
    constraint_expr TEXT;
BEGIN
    SELECT t.typname
    INTO enum_type
    FROM pg_type t
    JOIN pg_attribute a ON a.atttypid = t.oid
    JOIN pg_class c ON c.oid = a.attrelid
    WHERE c.relname = 'order_batches'
      AND a.attname = 'source'
      AND t.typtype = 'e';

    IF enum_type IS NOT NULL THEN
        EXECUTE format('ALTER TYPE %I ADD VALUE IF NOT EXISTS ''MINIAPP''', enum_type);
        RETURN;
    END IF;

    SELECT c.conname, pg_get_constraintdef(c.oid)
    INTO constraint_name, constraint_def
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    WHERE t.relname = 'order_batches'
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) ILIKE '%source%';

    IF constraint_name IS NULL THEN
        RAISE NOTICE 'order_batches.source constraint not found, skipping';
        RETURN;
    END IF;

    IF constraint_def ILIKE '%MINIAPP%' THEN
        RAISE NOTICE 'order_batches.source already allows MINIAPP';
        RETURN;
    END IF;

    constraint_expr := substring(constraint_def FROM '^CHECK\\s*\\((.*)\\)$');
    IF constraint_expr IS NULL THEN
        RAISE NOTICE 'order_batches.source constraint has unexpected format: %', constraint_def;
        RETURN;
    END IF;

    EXECUTE format('ALTER TABLE order_batches DROP CONSTRAINT %I', constraint_name);
    EXECUTE format(
        'ALTER TABLE order_batches ADD CONSTRAINT %I CHECK ((source = ''MINIAPP'') OR (%s))',
        constraint_name,
        constraint_expr
    );
END $$;
