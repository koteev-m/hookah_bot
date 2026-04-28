CREATE TABLE IF NOT EXISTS venue_settings (
    venue_id BIGINT PRIMARY KEY,
    notify_orders_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notify_staff_calls_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notify_cancellations_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    timezone VARCHAR(100) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_venue_settings_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
);
