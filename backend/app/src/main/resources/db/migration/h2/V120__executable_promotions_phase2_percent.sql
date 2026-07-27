ALTER TABLE promotion_rules
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

ALTER TABLE promotion_rules
    ADD COLUMN IF NOT EXISTS executable_target_type VARCHAR(32);

ALTER TABLE promotion_rules
    ADD CONSTRAINT IF NOT EXISTS chk_promotion_rules_executable_target_type
        CHECK (executable_target_type IS NULL OR executable_target_type IN ('MENU_ITEM', 'MENU_CATEGORY'));

UPDATE promotion_rules
SET version = 1
WHERE version IS NULL OR version < 1;

UPDATE promotion_rules
SET stackable = FALSE,
    conflict_group = NULL,
    max_applications_per_item = 1
WHERE rule_type = 'HAPPY_HOURS_PERCENT';

CREATE TABLE IF NOT EXISTS promotion_rule_weekday_windows (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    starts_minute INT NOT NULL CHECK (starts_minute BETWEEN 0 AND 1439),
    ends_minute INT NOT NULL CHECK (ends_minute BETWEEN 1 AND 1440),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_promotion_rule_weekday_windows_rule
        FOREIGN KEY (rule_id) REFERENCES promotion_rules(id) ON DELETE CASCADE,
    CHECK (starts_minute < ends_minute),
    UNIQUE (rule_id, weekday, starts_minute, ends_minute)
);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_weekday_windows_rule_day
    ON promotion_rule_weekday_windows (rule_id, weekday, starts_minute, ends_minute);

INSERT INTO promotion_rule_weekday_windows (rule_id, weekday, starts_minute, ends_minute)
SELECT
    r.id,
    schedule_day.weekday,
    CASE
        WHEN r.starts_time IS NULL THEN 0
        ELSE CAST(EXTRACT(HOUR FROM r.starts_time) AS INT) * 60 +
            CAST(EXTRACT(MINUTE FROM r.starts_time) AS INT)
    END,
    CASE
        WHEN r.ends_time IS NULL THEN 1440
        ELSE CAST(EXTRACT(HOUR FROM r.ends_time) AS INT) * 60 +
            CAST(EXTRACT(MINUTE FROM r.ends_time) AS INT)
    END
FROM promotion_rules r
CROSS JOIN (
    VALUES (1), (2), (3), (4), (5), (6), (7)
) AS schedule_day(weekday)
WHERE (
    r.days_of_week IS NULL
    OR TRIM(r.days_of_week) = ''
    OR (
        POSITION(',1,' IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')) = 0
        AND POSITION(',2,' IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')) = 0
        AND POSITION(',3,' IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')) = 0
        AND POSITION(',4,' IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')) = 0
        AND POSITION(',5,' IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')) = 0
        AND POSITION(',6,' IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')) = 0
        AND POSITION(',7,' IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')) = 0
    )
    OR POSITION(
        CONCAT(',', CAST(schedule_day.weekday AS VARCHAR), ',')
        IN CONCAT(',', REPLACE(r.days_of_week, ' ', ''), ',')
    ) > 0
)
AND (
    (r.starts_time IS NULL AND r.ends_time IS NULL)
    OR (
        r.starts_time IS NOT NULL
        AND r.ends_time IS NOT NULL
        AND r.starts_time < r.ends_time
    )
)
AND NOT EXISTS (
    SELECT 1
    FROM promotion_rule_weekday_windows existing
    WHERE existing.rule_id = r.id
      AND existing.weekday = schedule_day.weekday
);

CREATE TABLE IF NOT EXISTS promotion_rule_menu_category_targets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    menu_category_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_promotion_rule_menu_category_targets_rule
        FOREIGN KEY (rule_id) REFERENCES promotion_rules(id) ON DELETE CASCADE,
    CONSTRAINT fk_promotion_rule_menu_category_targets_category
        FOREIGN KEY (menu_category_id) REFERENCES menu_categories(id) ON DELETE CASCADE,
    UNIQUE (rule_id, menu_category_id)
);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_menu_category_targets_rule
    ON promotion_rule_menu_category_targets (rule_id);

CREATE INDEX IF NOT EXISTS idx_promotion_rule_menu_category_targets_category
    ON promotion_rule_menu_category_targets (menu_category_id);

ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS item_name_snapshot TEXT;

ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS base_unit_price_minor_snapshot BIGINT;

ALTER TABLE order_batch_items
    ADD COLUMN IF NOT EXISTS currency_snapshot VARCHAR(16);

UPDATE order_batch_items item
SET item_name_snapshot = COALESCE(
        item.item_name_snapshot,
        (SELECT menu.name FROM menu_items menu WHERE menu.id = item.menu_item_id)
    ),
    base_unit_price_minor_snapshot = COALESCE(
        item.base_unit_price_minor_snapshot,
        (SELECT menu.price_minor FROM menu_items menu WHERE menu.id = item.menu_item_id)
    ),
    currency_snapshot = COALESCE(
        item.currency_snapshot,
        (SELECT menu.currency FROM menu_items menu WHERE menu.id = item.menu_item_id)
    )
WHERE EXISTS (
    SELECT 1
    FROM menu_items menu
    WHERE menu.id = item.menu_item_id
);

UPDATE promotion_rules rule
SET executable_target_type = 'MENU_ITEM'
WHERE rule.rule_type = 'HAPPY_HOURS_PERCENT'
AND (
    SELECT COUNT(*)
    FROM promotion_rule_targets target
    WHERE target.rule_id = rule.id
) = 1
AND EXISTS (
    SELECT 1
    FROM promotion_rule_targets target
    WHERE target.rule_id = rule.id
      AND target.target_type = 'MENU_ITEM'
);

ALTER TABLE order_promotion_applications
    ADD COLUMN IF NOT EXISTS rule_version INT NOT NULL DEFAULT 1;

ALTER TABLE order_promotion_applications
    ADD COLUMN IF NOT EXISTS schedule_snapshot_json CHARACTER LARGE OBJECT;

ALTER TABLE order_promotion_applications
    ADD COLUMN IF NOT EXISTS target_snapshot_json CHARACTER LARGE OBJECT;

ALTER TABLE order_promotion_applications
    ADD COLUMN IF NOT EXISTS original_total_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE order_promotion_applications
    ADD COLUMN IF NOT EXISTS final_total_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE order_promotion_applications
    ADD COLUMN IF NOT EXISTS venue_timezone_snapshot VARCHAR(64);

ALTER TABLE order_promotion_applications
    ADD COLUMN IF NOT EXISTS applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE order_batch_item_promotion_adjustments
    ADD COLUMN IF NOT EXISTS item_name_snapshot VARCHAR(512);

ALTER TABLE order_batch_item_promotion_adjustments
    ADD COLUMN IF NOT EXISTS base_unit_price_minor BIGINT;

ALTER TABLE order_batch_item_promotion_adjustments
    ADD COLUMN IF NOT EXISTS selected_option_delta_minor BIGINT;

ALTER TABLE order_batch_item_promotion_adjustments
    ADD COLUMN IF NOT EXISTS original_amount_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE order_batch_item_promotion_adjustments
    ADD COLUMN IF NOT EXISTS final_amount_minor BIGINT NOT NULL DEFAULT 0;

UPDATE order_batch_item_promotion_adjustments adjustment
SET selected_option_delta_minor = COALESCE(
    (
        SELECT SUM(option_snapshot.price_delta_minor_snapshot)
        FROM order_batch_item_options option_snapshot
        WHERE option_snapshot.order_batch_item_id = adjustment.order_batch_item_id
    ),
    0
)
WHERE adjustment.selected_option_delta_minor IS NULL;

UPDATE order_batch_item_promotion_adjustments adjustment
SET base_unit_price_minor =
    adjustment.original_price_minor - COALESCE(adjustment.selected_option_delta_minor, 0)
WHERE adjustment.base_unit_price_minor IS NULL;

UPDATE order_batch_item_promotion_adjustments adjustment
SET original_amount_minor = adjustment.original_price_minor * adjustment.quantity,
    final_amount_minor = GREATEST(
        adjustment.original_price_minor * adjustment.quantity - adjustment.discount_minor,
        0
    )
WHERE adjustment.original_amount_minor = 0;

UPDATE order_promotion_applications application
SET rule_version = COALESCE(
        (
            SELECT rule.version
            FROM promotion_rules rule
            WHERE rule.id = application.rule_id
        ),
        1
    ),
    original_total_minor = COALESCE(
        (
            SELECT SUM(adjustment.original_amount_minor)
            FROM order_batch_item_promotion_adjustments adjustment
            WHERE adjustment.application_id = application.id
        ),
        application.discount_total_minor
    ),
    final_total_minor = GREATEST(
        COALESCE(
            (
                SELECT SUM(adjustment.original_amount_minor)
                FROM order_batch_item_promotion_adjustments adjustment
                WHERE adjustment.application_id = application.id
            ),
            application.discount_total_minor
        ) - application.discount_total_minor,
        0
    ),
    applied_at = application.created_at;
