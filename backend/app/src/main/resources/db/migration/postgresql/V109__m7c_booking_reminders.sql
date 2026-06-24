ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS venue_confirmed_at TIMESTAMPTZ NULL;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS last_rescheduled_at TIMESTAMPTZ NULL;

ALTER TABLE booking_reminders
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY';

ALTER TABLE booking_reminders
    DROP CONSTRAINT IF EXISTS booking_reminders_status_check;

ALTER TABLE booking_reminders
    ADD CONSTRAINT booking_reminders_status_check
        CHECK (status IN ('PENDING', 'QUEUED', 'SENT', 'CANCELED', 'SKIPPED', 'FAILED'));

ALTER TABLE booking_reminders
    DROP CONSTRAINT IF EXISTS booking_reminders_policy_version_check;

ALTER TABLE booking_reminders
    ADD CONSTRAINT booking_reminders_policy_version_check
        CHECK (policy_version IN ('LEGACY', 'M7C'));

UPDATE booking_reminders
SET status = 'CANCELED',
    last_error = COALESCE(last_error, 'Legacy reminder canceled by M7c reconciliation'),
    updated_at = NOW()
WHERE policy_version = 'LEGACY'
  AND status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_booking_reminders_policy_status_due
    ON booking_reminders (policy_version, status, scheduled_for);

ALTER TABLE telegram_outbox
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(180) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_telegram_outbox_dedupe_key
    ON telegram_outbox (dedupe_key);
