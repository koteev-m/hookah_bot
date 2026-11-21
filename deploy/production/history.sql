SELECT json_agg(json_build_object('version', version::integer, 'script', script,
                                  'checksum', checksum, 'success', success)
                ORDER BY installed_rank)
FROM public.flyway_schema_history;
