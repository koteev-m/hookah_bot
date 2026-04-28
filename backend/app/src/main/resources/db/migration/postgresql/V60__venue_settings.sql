CREATE TABLE IF NOT EXISTS venue_settings (
    venue_id BIGINT PRIMARY KEY REFERENCES venues(id) ON DELETE CASCADE,
    notify_orders_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notify_staff_calls_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notify_cancellations_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    timezone TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
