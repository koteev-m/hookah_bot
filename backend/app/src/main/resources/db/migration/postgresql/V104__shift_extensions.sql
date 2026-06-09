CREATE TABLE IF NOT EXISTS shift_extension_settings (
    venue_id BIGINT PRIMARY KEY REFERENCES venues(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    duration_minutes INTEGER NOT NULL DEFAULT 60,
    price_minor BIGINT,
    currency CHAR(3) NOT NULL DEFAULT 'RUB',
    max_extensions_per_session INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_shift_extension_settings_duration
        CHECK (duration_minutes > 0 AND duration_minutes <= 240),
    CONSTRAINT chk_shift_extension_settings_price
        CHECK (price_minor IS NULL OR price_minor >= 0),
    CONSTRAINT chk_shift_extension_settings_max
        CHECK (max_extensions_per_session IS NULL OR max_extensions_per_session > 0)
);

CREATE TABLE IF NOT EXISTS shift_extension_requests (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES table_sessions(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES venue_tables(id) ON DELETE RESTRICT,
    tab_id BIGINT NOT NULL REFERENCES tab(id) ON DELETE CASCADE,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    requested_by_user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    duration_minutes INTEGER NOT NULL,
    price_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'RUB',
    current_orderable_until TIMESTAMPTZ NOT NULL,
    requested_until TIMESTAMPTZ NOT NULL,
    comment TEXT,
    idempotency_key TEXT,
    decided_by_user_id BIGINT,
    decided_at TIMESTAMPTZ,
    reject_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_shift_extension_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_shift_extension_requests_duration
        CHECK (duration_minutes > 0 AND duration_minutes <= 240),
    CONSTRAINT chk_shift_extension_requests_price
        CHECK (price_minor >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_shift_extension_requests_pending_tab
    ON shift_extension_requests (table_session_id, tab_id)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS uq_shift_extension_requests_idempotency
    ON shift_extension_requests (venue_id, table_session_id, requested_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_shift_extension_requests_venue_status
    ON shift_extension_requests (venue_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_shift_extension_requests_order
    ON shift_extension_requests (order_id);

CREATE TABLE IF NOT EXISTS order_service_charges (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    table_session_id BIGINT NOT NULL REFERENCES table_sessions(id) ON DELETE CASCADE,
    tab_id BIGINT REFERENCES tab(id) ON DELETE SET NULL,
    source VARCHAR(32) NOT NULL,
    source_request_id BIGINT REFERENCES shift_extension_requests(id) ON DELETE SET NULL,
    label TEXT NOT NULL,
    qty INTEGER NOT NULL DEFAULT 1,
    unit_price_minor BIGINT NOT NULL,
    total_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'RUB',
    created_by_user_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_order_service_charges_source
        CHECK (source IN ('SHIFT_EXTENSION')),
    CONSTRAINT chk_order_service_charges_qty
        CHECK (qty > 0),
    CONSTRAINT chk_order_service_charges_amounts
        CHECK (unit_price_minor >= 0 AND total_minor >= 0),
    CONSTRAINT uq_order_service_charges_source_request UNIQUE (source, source_request_id)
);

CREATE INDEX IF NOT EXISTS idx_order_service_charges_order
    ON order_service_charges (order_id, created_at);
