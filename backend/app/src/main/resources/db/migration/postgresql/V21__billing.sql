CREATE TABLE billing_invoices (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id BIGINT NOT NULL
        REFERENCES venues(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    due_at TIMESTAMP NOT NULL,
    amount_minor INT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR NOT NULL,
    description VARCHAR NOT NULL,
    provider VARCHAR NOT NULL,
    provider_invoice_id VARCHAR NULL,
    payment_url TEXT NULL,
    status VARCHAR NOT NULL
        CHECK (status IN ('DRAFT','OPEN','PAID','PAST_DUE','VOID')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP NULL,
    updated_by_user_id BIGINT NULL,
    CONSTRAINT uq_billing_invoices_period UNIQUE (venue_id, period_start, period_end)
);

CREATE INDEX idx_billing_invoices_venue_status ON billing_invoices (venue_id, status);
CREATE INDEX idx_billing_invoices_due_status ON billing_invoices (due_at, status);

CREATE TABLE billing_payments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id BIGINT NOT NULL
        REFERENCES billing_invoices(id) ON DELETE CASCADE,
    provider VARCHAR NOT NULL,
    provider_event_id VARCHAR NOT NULL,
    amount_minor INT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR NOT NULL,
    status VARCHAR NOT NULL
        CHECK (status IN ('SUCCEEDED','FAILED','REFUNDED')),
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    raw_payload TEXT NULL,
    CONSTRAINT uq_billing_payments_provider_event UNIQUE (provider, provider_event_id)
);
