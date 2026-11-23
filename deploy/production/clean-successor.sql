-- psql -XqAtw, ON_ERROR_STOP=1. Only the final true result admits the target.
SET LOCAL search_path = pg_catalog;
\if :pristine
-- Run before permitting the first backend container or Flyway migration.
SELECT NOT EXISTS (SELECT FROM pg_class WHERE relnamespace = 'public'::regnamespace)
    AND NOT EXISTS (SELECT FROM pg_proc WHERE pronamespace = 'public'::regnamespace)
    AND NOT EXISTS (SELECT FROM pg_type WHERE typnamespace = 'public'::regnamespace) AS clean \gset
\else
-- V136 seeds exactly one policy on an empty database. All other application
-- tables (including future tables) must have no rows; Flyway is checked separately.
DO $$
DECLARE tab record; populated boolean;
BEGIN
    FOR tab IN SELECT oid::regclass AS name FROM pg_class
               WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r','p')
               AND oid NOT IN ('public.flyway_schema_history'::regclass, 'public.loyalty_payment_policy'::regclass)
    LOOP
        EXECUTE format('SELECT EXISTS (SELECT FROM ONLY %s)', tab.name) INTO populated;
        IF populated THEN RAISE EXCEPTION 'successor contains application rows'; END IF;
    END LOOP;
END $$;
SELECT count(*) = 1 AND bool_and(id = 1 AND first_item_id = 1) AS clean FROM public.loyalty_payment_policy \gset
\endif
SELECT :'clean'::boolean
    AND NOT EXISTS (SELECT FROM pg_database WHERE datname NOT IN ('template0','template1','postgres','hookah_prod'))
    AND NOT EXISTS (SELECT FROM pg_namespace WHERE nspname NOT IN ('public','pg_catalog','pg_toast','information_schema')
                    AND nspname !~ '^pg_(toast_)?temp_[0-9]+$')
    AND NOT EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname ~ '^pg_(toast_)?temp_[0-9]+$')
    AND NOT EXISTS (SELECT FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind IN ('f','m'))
    AND NOT EXISTS (SELECT FROM pg_largeobject_metadata)
    AND NOT EXISTS (SELECT FROM pg_prepared_xacts)
    AND NOT EXISTS (SELECT FROM pg_stat_activity WHERE datname = current_database()
                    AND pid <> pg_backend_pid() AND backend_xid IS NOT NULL)
    AND NOT EXISTS (SELECT FROM pg_roles WHERE oid >= 16384 AND rolname <> 'hookah_app')
    AND EXISTS (SELECT FROM pg_roles WHERE rolname = 'hookah_app' AND rolcanlogin AND rolinherit
                AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    AND NOT EXISTS (SELECT FROM pg_auth_members WHERE 'hookah_app'::regrole IN (member,roleid));
