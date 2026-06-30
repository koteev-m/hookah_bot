ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS active_order_table_session_id BIGINT
    GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN table_session_id ELSE NULL END
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_one_active_per_table_session
    ON orders(active_order_table_session_id);

ALTER TABLE tab
    ADD COLUMN IF NOT EXISTS active_personal_table_session_id BIGINT
    GENERATED ALWAYS AS (
        CASE WHEN type = 'PERSONAL' AND status = 'ACTIVE' THEN table_session_id ELSE NULL END
    );

ALTER TABLE tab
    ADD COLUMN IF NOT EXISTS active_personal_owner_user_id BIGINT
    GENERATED ALWAYS AS (
        CASE WHEN type = 'PERSONAL' AND status = 'ACTIVE' THEN owner_user_id ELSE NULL END
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_tab_personal_owner
    ON tab(active_personal_table_session_id, active_personal_owner_user_id);
