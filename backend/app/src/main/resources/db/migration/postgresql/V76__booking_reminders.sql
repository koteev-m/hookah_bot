ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS last_guest_confirmation_at TIMESTAMPTZ NULL;

CREATE TABLE IF NOT EXISTS booking_reminders (
    id BIGSERIAL PRIMARY KEY,
    booking_id BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL
        CHECK (kind IN ('DAY_OF_VISIT', 'PRE_VISIT')),
    scheduled_for TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'CANCELED', 'SKIPPED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    dedupe_key VARCHAR(128) NOT NULL UNIQUE,
    sent_at TIMESTAMPTZ NULL,
    last_error TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_reminders_status_due
    ON booking_reminders (status, scheduled_for);

CREATE INDEX IF NOT EXISTS idx_booking_reminders_booking_kind
    ON booking_reminders (booking_id, kind);
