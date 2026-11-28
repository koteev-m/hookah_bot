-- Emit only relation names and ordered row hashes. The operator streams this
-- repeatable-read inventory into SHA-256 before and after the offline migration.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path = pg_catalog;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL work_mem = '4MB';
SELECT format(
    'SELECT %L; SELECT encode(sha256(convert_to((%s)::text, ''UTF8'')), ''hex'') COLLATE "C" AS h FROM %I.%I AS r %s ORDER BY h',
    tablename,
    CASE WHEN tablename='guest_batch_idempotency' THEN 'to_jsonb(r) - ''rejected_table_id''' ELSE 'to_jsonb(r)' END,
    schemaname, tablename,
    CASE WHEN tablename='flyway_schema_history' THEN 'WHERE version::int <= 143' ELSE '' END)
FROM pg_tables WHERE schemaname='public' AND tablename NOT IN
    ('guest_visit_generations', 'bot_prepared_checkouts', 'shift_extension_request_keys')
ORDER BY tablename COLLATE "C"
\gexec
COMMIT;
