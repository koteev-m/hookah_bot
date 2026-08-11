ALTER TABLE guest_batch_idempotency
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(80) NULL;
