CREATE TABLE venue_accounting_shifts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    open_slot INT,
    timezone VARCHAR(80) NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    final_report TEXT,
    UNIQUE (venue_id, open_slot),
    UNIQUE (id, venue_id),
    CHECK ((status = 'OPEN' AND open_slot IS NOT NULL AND open_slot = 1 AND closed_at IS NULL AND final_report IS NULL)
        OR (status = 'CLOSED' AND open_slot IS NULL AND closed_at IS NOT NULL AND final_report IS NOT NULL)),
    CHECK (closed_at IS NULL OR closed_at >= opened_at)
);

ALTER TABLE orders ADD COLUMN accounting_shift_id BIGINT;
ALTER TABLE orders ADD CONSTRAINT fk_orders_accounting_shift
    FOREIGN KEY (accounting_shift_id, venue_id) REFERENCES venue_accounting_shifts(id, venue_id);
CREATE INDEX idx_orders_accounting_shift ON orders(accounting_shift_id);

CREATE TABLE venue_accounting_sales (
    id VARCHAR(80) PRIMARY KEY,
    shift_id BIGINT NOT NULL REFERENCES venue_accounting_shifts(id),
    snapshot_json TEXT NOT NULL
);
CREATE INDEX idx_accounting_sales_shift ON venue_accounting_sales(shift_id);

-- Immutable application history: there is no update/delete API for these rows.
CREATE TABLE venue_accounting_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shift_id BIGINT NOT NULL REFERENCES venue_accounting_shifts(id),
    sale_id VARCHAR(80),
    actor_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    action VARCHAR(40) NOT NULL,
    reason_text VARCHAR(500),
    old_json TEXT NOT NULL,
    new_json TEXT NOT NULL
);
CREATE INDEX idx_accounting_events_shift ON venue_accounting_events(shift_id, id);

CREATE TABLE venue_accounting_commands (
    venue_id BIGINT NOT NULL REFERENCES venues(id),
    request_id VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    fingerprint VARCHAR(64) NOT NULL,
    shift_id BIGINT NOT NULL REFERENCES venue_accounting_shifts(id),
    PRIMARY KEY (venue_id, request_id)
);
