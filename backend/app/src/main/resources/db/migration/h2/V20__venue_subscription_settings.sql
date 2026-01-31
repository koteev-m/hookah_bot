CREATE TABLE IF NOT EXISTS venue_subscription_settings (
    venue_id BIGINT PRIMARY KEY,
    trial_end_date DATE NULL,
    paid_start_date DATE NULL,
    base_price_minor INT NULL,
    price_override_minor INT NULL,
    currency VARCHAR NOT NULL DEFAULT 'RUB',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_user_id BIGINT NOT NULL,
    CONSTRAINT fk_venue_subscription_settings_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS venue_price_schedule (
    venue_id BIGINT NOT NULL,
    effective_from DATE NOT NULL,
    price_minor INT NOT NULL,
    currency VARCHAR NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_user_id BIGINT,
    CONSTRAINT pk_venue_price_schedule PRIMARY KEY (venue_id, effective_from),
    CONSTRAINT fk_venue_price_schedule_venue FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
);
