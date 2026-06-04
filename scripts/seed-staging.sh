#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

if [[ -f ".env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source ".env"
  set +a
fi

OWNER_ID="${STAGING_SEED_OWNER_TELEGRAM_ID:-${PLATFORM_OWNER_TELEGRAM_ID:-}}"
MANAGER_ID="${STAGING_SEED_MANAGER_TELEGRAM_ID:-}"
STAFF_ID="${STAGING_SEED_STAFF_TELEGRAM_ID:-}"
VENUE_NAME="${STAGING_SEED_VENUE_NAME:-MIX Staging Smoke}"
VENUE_CITY="${STAGING_SEED_VENUE_CITY:-Москва}"
VENUE_ADDRESS="${STAGING_SEED_VENUE_ADDRESS:-Staging smoke address}"

if [[ -z "${OWNER_ID}" ]]; then
  cat >&2 <<'EOF'
Missing owner Telegram id.

Set one of:
  STAGING_SEED_OWNER_TELEGRAM_ID=<your-telegram-id>
  PLATFORM_OWNER_TELEGRAM_ID=<your-telegram-id>

The seed is manual-only and needs an owner membership so Venue Mini App smoke can open the venue.
EOF
  exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Missing required command: docker" >&2
  exit 2
fi

echo "==> Seeding staging smoke data"
echo "    venue: ${VENUE_NAME}"
echo "    owner membership: configured"
if [[ -n "${MANAGER_ID}" ]]; then
  echo "    manager membership: configured"
fi
if [[ -n "${STAFF_ID}" ]]; then
  echo "    staff membership: configured"
fi

docker compose exec -T \
  -e SEED_OWNER_ID="${OWNER_ID}" \
  -e SEED_MANAGER_ID="${MANAGER_ID}" \
  -e SEED_STAFF_ID="${STAFF_ID}" \
  -e SEED_VENUE_NAME="${VENUE_NAME}" \
  -e SEED_VENUE_CITY="${VENUE_CITY}" \
  -e SEED_VENUE_ADDRESS="${VENUE_ADDRESS}" \
  postgres sh -c 'psql -v ON_ERROR_STOP=1 \
    -v owner_id="$SEED_OWNER_ID" \
    -v manager_id="$SEED_MANAGER_ID" \
    -v staff_id="$SEED_STAFF_ID" \
    -v venue_name="$SEED_VENUE_NAME" \
    -v venue_city="$SEED_VENUE_CITY" \
    -v venue_address="$SEED_VENUE_ADDRESS" \
    -U "$POSTGRES_USER" \
    -d "$POSTGRES_DB"' <<'SQL'
BEGIN;

CREATE TEMP TABLE staging_seed_params AS
SELECT
    :'owner_id'::bigint AS owner_id,
    NULLIF(:'manager_id', '')::bigint AS manager_id,
    NULLIF(:'staff_id', '')::bigint AS staff_id,
    :'venue_name'::text AS venue_name,
    :'venue_city'::text AS venue_city,
    :'venue_address'::text AS venue_address;

INSERT INTO users (telegram_user_id, username, first_name, last_name, updated_at)
SELECT owner_id, 'staging_owner', 'Staging', 'Owner', now()
FROM staging_seed_params
ON CONFLICT (telegram_user_id) DO UPDATE
SET first_name = COALESCE(users.first_name, EXCLUDED.first_name),
    last_name = COALESCE(users.last_name, EXCLUDED.last_name),
    updated_at = now();

INSERT INTO users (telegram_user_id, username, first_name, last_name, updated_at)
SELECT manager_id, 'staging_manager', 'Staging', 'Manager', now()
FROM staging_seed_params
WHERE manager_id IS NOT NULL
  AND manager_id <> owner_id
ON CONFLICT (telegram_user_id) DO UPDATE
SET first_name = COALESCE(users.first_name, EXCLUDED.first_name),
    last_name = COALESCE(users.last_name, EXCLUDED.last_name),
    updated_at = now();

INSERT INTO users (telegram_user_id, username, first_name, last_name, updated_at)
SELECT staff_id, 'staging_staff', 'Staging', 'Staff', now()
FROM staging_seed_params
WHERE staff_id IS NOT NULL
  AND staff_id <> owner_id
  AND (manager_id IS NULL OR staff_id <> manager_id)
ON CONFLICT (telegram_user_id) DO UPDATE
SET first_name = COALESCE(users.first_name, EXCLUDED.first_name),
    last_name = COALESCE(users.last_name, EXCLUDED.last_name),
    updated_at = now();

CREATE TEMP TABLE staging_seed_venue AS
WITH existing AS (
    SELECT v.id
    FROM venues v
    JOIN staging_seed_params p ON p.venue_name = v.name
    WHERE v.deleted_at IS NULL
    ORDER BY v.id
    LIMIT 1
),
inserted AS (
    INSERT INTO venues (
        name,
        city,
        address,
        status,
        card_description,
        guest_contact,
        features,
        ui_layout,
        updated_at
    )
    SELECT
        venue_name,
        venue_city,
        venue_address,
        'PUBLISHED',
        'Тестовое заведение для staging smoke: брони, меню, QR и заказ к столу.',
        '@hookahtootah_support',
        '{}'::jsonb,
        '{}'::jsonb,
        now()
    FROM staging_seed_params
    WHERE NOT EXISTS (SELECT 1 FROM existing)
    RETURNING id
)
SELECT id AS venue_id FROM inserted
UNION ALL
SELECT id AS venue_id FROM existing
LIMIT 1;

UPDATE venues v
SET city = p.venue_city,
    address = p.venue_address,
    status = 'PUBLISHED',
    card_description = 'Тестовое заведение для staging smoke: брони, меню, QR и заказ к столу.',
    guest_contact = '@hookahtootah_support',
    updated_at = now()
FROM staging_seed_params p, staging_seed_venue sv
WHERE v.id = sv.venue_id;

INSERT INTO venue_owner_accounts (primary_owner_user_id, allowed_venues_count, notes, updated_at)
SELECT owner_id, 5, 'Staging smoke seed owner account', now()
FROM staging_seed_params
ON CONFLICT (primary_owner_user_id) DO UPDATE
SET allowed_venues_count = GREATEST(venue_owner_accounts.allowed_venues_count, EXCLUDED.allowed_venues_count),
    updated_at = now();

UPDATE venues v
SET owner_account_id = voa.id,
    updated_at = now()
FROM staging_seed_venue sv
JOIN staging_seed_params p ON true
JOIN venue_owner_accounts voa ON voa.primary_owner_user_id = p.owner_id
WHERE v.id = sv.venue_id;

INSERT INTO venue_members (venue_id, user_id, role, created_at)
SELECT sv.venue_id, p.owner_id, 'OWNER', now()
FROM staging_seed_venue sv
JOIN staging_seed_params p ON true
ON CONFLICT (venue_id, user_id) DO UPDATE
SET role = 'OWNER';

INSERT INTO venue_members (venue_id, user_id, role, created_at)
SELECT sv.venue_id, p.manager_id, 'MANAGER', now()
FROM staging_seed_venue sv
JOIN staging_seed_params p ON true
WHERE p.manager_id IS NOT NULL
  AND p.manager_id <> p.owner_id
ON CONFLICT (venue_id, user_id) DO UPDATE
SET role = 'MANAGER';

INSERT INTO venue_members (venue_id, user_id, role, created_at)
SELECT sv.venue_id, p.staff_id, 'STAFF', now()
FROM staging_seed_venue sv
JOIN staging_seed_params p ON true
WHERE p.staff_id IS NOT NULL
  AND p.staff_id <> p.owner_id
  AND (p.manager_id IS NULL OR p.staff_id <> p.manager_id)
ON CONFLICT (venue_id, user_id) DO UPDATE
SET role = 'STAFF';

INSERT INTO venue_subscriptions (
    venue_id,
    status,
    trial_end_date,
    paid_start_date,
    price_override_minor,
    next_due_date,
    updated_at
)
SELECT
    venue_id,
    'ACTIVE',
    CURRENT_DATE + 30,
    CURRENT_DATE,
    0,
    CURRENT_DATE + 30,
    now()
FROM staging_seed_venue
ON CONFLICT (venue_id) DO UPDATE
SET status = 'ACTIVE',
    trial_end_date = EXCLUDED.trial_end_date,
    paid_start_date = EXCLUDED.paid_start_date,
    next_due_date = EXCLUDED.next_due_date,
    updated_at = now();

INSERT INTO venue_settings (
    venue_id,
    notify_orders_enabled,
    notify_staff_calls_enabled,
    notify_cancellations_enabled,
    timezone,
    updated_at
)
SELECT venue_id, true, true, true, 'Europe/Moscow', now()
FROM staging_seed_venue
ON CONFLICT (venue_id) DO UPDATE
SET notify_orders_enabled = true,
    notify_staff_calls_enabled = true,
    notify_cancellations_enabled = true,
    timezone = 'Europe/Moscow',
    updated_at = now();

INSERT INTO venue_booking_settings (venue_id, hold_minutes, updated_at)
SELECT venue_id, 30, now()
FROM staging_seed_venue
ON CONFLICT (venue_id) DO UPDATE
SET hold_minutes = 30,
    updated_at = now();

INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed, updated_at)
SELECT venue_id, weekday, TIME '12:00', TIME '02:00', false, now()
FROM staging_seed_venue
CROSS JOIN generate_series(1, 7) AS weekday
ON CONFLICT (venue_id, weekday) DO UPDATE
SET opens_at = EXCLUDED.opens_at,
    closes_at = EXCLUDED.closes_at,
    is_closed = false,
    updated_at = now();

CREATE TEMP TABLE staging_seed_categories (
    category_key text PRIMARY KEY,
    category_id bigint NOT NULL
);

WITH category_input(category_key, category_name, sort_order, category_type) AS (
    VALUES
        ('hookah', 'Кальяны', 10, 'HOOKAH'),
        ('drinks', 'Напитки', 20, 'DRINK')
),
existing AS (
    SELECT ci.category_key, mc.id
    FROM category_input ci
    JOIN staging_seed_venue sv ON true
    JOIN menu_categories mc ON mc.venue_id = sv.venue_id AND mc.name = ci.category_name
),
inserted AS (
    INSERT INTO menu_categories (venue_id, name, sort_order, is_active, category_type, updated_at)
    SELECT sv.venue_id, ci.category_name, ci.sort_order, true, ci.category_type, now()
    FROM category_input ci
    JOIN staging_seed_venue sv ON true
    WHERE NOT EXISTS (
        SELECT 1
        FROM existing e
        WHERE e.category_key = ci.category_key
    )
    RETURNING name, id
)
INSERT INTO staging_seed_categories (category_key, category_id)
SELECT ci.category_key, COALESCE(i.id, e.id)
FROM category_input ci
LEFT JOIN inserted i ON i.name = ci.category_name
LEFT JOIN existing e ON e.category_key = ci.category_key;

UPDATE menu_categories mc
SET sort_order = ci.sort_order,
    is_active = true,
    category_type = ci.category_type,
    updated_at = now()
FROM (
    VALUES
        ('hookah', 10, 'HOOKAH'),
        ('drinks', 20, 'DRINK')
) AS ci(category_key, sort_order, category_type)
JOIN staging_seed_categories sc ON sc.category_key = ci.category_key
WHERE mc.id = sc.category_id;

WITH item_input(category_key, item_name, description, price_minor, sort_order, item_type) AS (
    VALUES
        ('hookah', 'Кальян классический', 'Базовый кальян для staging smoke.', 150000, 10, 'HOOKAH'),
        ('hookah', 'Кальян премиум', 'Премиальный микс для проверки счёта.', 220000, 20, 'HOOKAH'),
        ('drinks', 'Чайник чая', 'Чай для проверки дозаказа.', 45000, 10, 'TEA'),
        ('drinks', 'Вода', 'Вода без газа.', 20000, 20, 'DRINK')
),
existing AS (
    SELECT ii.item_name, mi.id
    FROM item_input ii
    JOIN staging_seed_venue sv ON true
    JOIN menu_items mi ON mi.venue_id = sv.venue_id AND mi.name = ii.item_name
),
inserted AS (
    INSERT INTO menu_items (
        venue_id,
        category_id,
        name,
        description,
        price_minor,
        currency,
        is_available,
        sort_order,
        item_type,
        updated_at
    )
    SELECT
        sv.venue_id,
        sc.category_id,
        ii.item_name,
        ii.description,
        ii.price_minor,
        'RUB',
        true,
        ii.sort_order,
        ii.item_type,
        now()
    FROM item_input ii
    JOIN staging_seed_venue sv ON true
    JOIN staging_seed_categories sc ON sc.category_key = ii.category_key
    WHERE NOT EXISTS (
        SELECT 1
        FROM existing e
        WHERE e.item_name = ii.item_name
    )
    RETURNING name, id
)
UPDATE menu_items mi
SET category_id = sc.category_id,
    description = ii.description,
    price_minor = ii.price_minor,
    currency = 'RUB',
    is_available = true,
    sort_order = ii.sort_order,
    item_type = ii.item_type,
    updated_at = now()
FROM item_input ii
JOIN staging_seed_categories sc ON sc.category_key = ii.category_key
LEFT JOIN inserted ins ON ins.name = ii.item_name
LEFT JOIN existing ex ON ex.item_name = ii.item_name
WHERE mi.id = COALESCE(ins.id, ex.id);

CREATE TEMP TABLE staging_seed_tables (
    table_number int PRIMARY KEY,
    table_id bigint NOT NULL,
    preferred_token text NOT NULL
);

WITH table_input(table_number, capacity, preferred_token) AS (
    VALUES
        (104, 4, 'staging-mix-104'),
        (105, 4, 'staging-mix-105')
),
upserted AS (
    INSERT INTO venue_tables (venue_id, table_number, is_active, capacity)
    SELECT sv.venue_id, ti.table_number, true, ti.capacity
    FROM table_input ti
    JOIN staging_seed_venue sv ON true
    ON CONFLICT (venue_id, table_number) DO UPDATE
    SET is_active = true,
        capacity = EXCLUDED.capacity
    RETURNING table_number, id
)
INSERT INTO staging_seed_tables (table_number, table_id, preferred_token)
SELECT ti.table_number, u.id, ti.preferred_token
FROM table_input ti
JOIN upserted u ON u.table_number = ti.table_number;

INSERT INTO table_tokens (token, table_id, is_active, issued_at, revoked_at)
SELECT st.preferred_token, st.table_id, true, now(), NULL
FROM staging_seed_tables st
WHERE NOT EXISTS (
    SELECT 1
    FROM table_tokens tt
    WHERE tt.table_id = st.table_id
      AND tt.is_active = true
)
ON CONFLICT (token) DO UPDATE
SET is_active = true,
    revoked_at = NULL
WHERE table_tokens.table_id = EXCLUDED.table_id;

COMMIT;

SELECT
    v.id AS venue_id,
    v.name AS venue_name,
    v.status AS venue_status,
    vt.table_number,
    tt.token AS active_table_token
FROM staging_seed_venue sv
JOIN venues v ON v.id = sv.venue_id
LEFT JOIN venue_tables vt ON vt.venue_id = v.id
LEFT JOIN table_tokens tt ON tt.table_id = vt.id AND tt.is_active = true
WHERE vt.table_number IN (104, 105)
ORDER BY vt.table_number;
SQL

echo "==> Staging seed finished"
