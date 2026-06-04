ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS last_guest_confirmation_at TIMESTAMP WITH TIME ZONE NULL;

CREATE TABLE IF NOT EXISTS booking_reminders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL
        CHECK (kind IN ('DAY_OF_VISIT', 'PRE_VISIT')),
    scheduled_for TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'CANCELED', 'SKIPPED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    dedupe_key VARCHAR(128) NOT NULL UNIQUE,
    sent_at TIMESTAMP WITH TIME ZONE NULL,
    last_error TEXT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_booking_reminders_status_due
    ON booking_reminders (status, scheduled_for);

CREATE INDEX IF NOT EXISTS idx_booking_reminders_booking_kind
    ON booking_reminders (booking_id, kind);
