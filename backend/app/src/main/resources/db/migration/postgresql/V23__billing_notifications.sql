CREATE TABLE IF NOT EXISTS billing_notifications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id BIGINT NOT NULL
        REFERENCES billing_invoices(id) ON DELETE CASCADE,
    kind VARCHAR NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload_json TEXT NOT NULL,
    CONSTRAINT uq_billing_notifications_invoice_kind UNIQUE (invoice_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_billing_notifications_invoice ON billing_notifications (invoice_id);
