ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS venue_confirmed_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS last_rescheduled_at TIMESTAMP WITH TIME ZONE NULL;

ALTER TABLE booking_reminders
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY';

EXECUTE IMMEDIATE (
    SELECT 'ALTER TABLE booking_reminders DROP CONSTRAINT "' || cc.constraint_name || '"'
    FROM information_schema.check_constraints cc
    JOIN information_schema.table_constraints tc
      ON tc.constraint_catalog = cc.constraint_catalog
     AND tc.constraint_schema = cc.constraint_schema
     AND tc.constraint_name = cc.constraint_name
    WHERE LOWER(tc.table_name) = 'booking_reminders'
      AND LOWER(cc.check_clause) LIKE '%status%'
      AND LOWER(cc.check_clause) NOT LIKE '%queued%'
    LIMIT 1
);

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
    updated_at = CURRENT_TIMESTAMP
WHERE policy_version = 'LEGACY'
  AND status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_booking_reminders_policy_status_due
    ON booking_reminders (policy_version, status, scheduled_for);

ALTER TABLE telegram_outbox
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(180) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_telegram_outbox_dedupe_key
    ON telegram_outbox (dedupe_key);
