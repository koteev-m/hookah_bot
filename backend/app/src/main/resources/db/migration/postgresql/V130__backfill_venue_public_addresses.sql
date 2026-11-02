-- Issued spellings are permanent. This backfill never changes lifecycle or location.
CREATE FUNCTION ht_venue_slug_v130(input TEXT) RETURNS TEXT
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    letters TEXT[] := ARRAY['а','б','в','г','д','е','ё','ж','з','и','й','к','л','м','н','о','п','р','с','т','у','ф','х','ц','ч','ш','щ','ъ','ы','ь','э','ю','я','і','ї','є','ґ','ў'];
    latin TEXT[] := ARRAY['a','b','v','g','d','e','yo','zh','z','i','y','k','l','m','n','o','p','r','s','t','u','f','kh','ts','ch','sh','shch','','y','','e','yu','ya','i','yi','ye','g','u'];
    result TEXT := lower(coalesce(input, ''));
    i INTEGER;
BEGIN
    FOR i IN 1..array_length(letters, 1) LOOP
        result := replace(result, letters[i], latin[i]);
    END LOOP;
    RETURN rtrim(left(trim(both '-' from regexp_replace(result, '[^a-z0-9]+', '-', 'g')), 64), '-');
END;
$$;

DO $$
DECLARE
    venue RECORD;
    base TEXT;
    detail TEXT;
    detailed TEXT;
    candidate TEXT;
    suffix TEXT;
    stage INTEGER;
    attempt INTEGER;
    inserted INTEGER;
BEGIN
    FOR venue IN SELECT id, name, city, address FROM venues WHERE public_slug IS NULL ORDER BY id LOOP
        base := coalesce(nullif(ht_venue_slug_v130(venue.name), ''), 'venue');
        detail := ht_venue_slug_v130(concat_ws(' ', venue.city, venue.address));
        detailed := CASE WHEN detail = '' THEN base
            ELSE rtrim(left(base, 31), '-') || '-' || rtrim(left(detail, 32), '-') END;
        stage := 0;
        attempt := 0;
        LOOP
            IF stage = 0 THEN candidate := base;
            ELSIF stage = 1 THEN candidate := detailed;
            ELSE
                suffix := '-' || venue.id::text || CASE WHEN attempt = 0 THEN '' ELSE '-' || attempt::text END;
                candidate := rtrim(left(detailed, 64 - length(suffix)), '-') || suffix;
                attempt := attempt + 1;
            END IF;
            INSERT INTO venue_public_addresses (slug, venue_id) VALUES (candidate, venue.id)
                ON CONFLICT (slug) DO NOTHING;
            GET DIAGNOSTICS inserted = ROW_COUNT;
            EXIT WHEN inserted = 1;
            stage := stage + 1;
        END LOOP;
        UPDATE venues SET public_slug = candidate WHERE id = venue.id;
    END LOOP;
END;
$$;
DROP FUNCTION ht_venue_slug_v130(TEXT);
