CREATE TABLE IF NOT EXISTS loyalty_programs (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    program_type VARCHAR(32) NOT NULL CHECK (program_type IN ('NTH_HOOKAH_FREE')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    nth_value INT NOT NULL CHECK (nth_value IN (3, 5, 6)),
    max_redemptions_per_visit INT NOT NULL DEFAULT 1 CHECK (max_redemptions_per_visit >= 1),
    created_by_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_loyalty_programs_one_active_per_venue
    ON loyalty_programs (venue_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_loyalty_programs_venue_status
    ON loyalty_programs (venue_id, status);

CREATE TABLE IF NOT EXISTS loyalty_program_earn_targets (
    id BIGSERIAL PRIMARY KEY,
    program_id BIGINT NOT NULL REFERENCES loyalty_programs(id) ON DELETE CASCADE,
    target_type VARCHAR(32) NOT NULL CHECK (target_type IN ('CATEGORY_TYPE')),
    semantic_type VARCHAR(32) NOT NULL CHECK (semantic_type IN ('HOOKAH')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_loyalty_program_earn_targets_program
    ON loyalty_program_earn_targets (program_id, target_type, semantic_type);

CREATE TABLE IF NOT EXISTS guest_loyalty_progress (
    program_id BIGINT NOT NULL REFERENCES loyalty_programs(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    progress_count INT NOT NULL DEFAULT 0 CHECK (progress_count >= 0),
    rewards_available INT NOT NULL DEFAULT 0 CHECK (rewards_available >= 0),
    rewards_reserved INT NOT NULL DEFAULT 0 CHECK (rewards_reserved >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (program_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_guest_loyalty_progress_user
    ON guest_loyalty_progress (user_id);

CREATE INDEX IF NOT EXISTS idx_guest_loyalty_progress_venue_user
    ON guest_loyalty_progress (venue_id, user_id);

CREATE TABLE IF NOT EXISTS guest_loyalty_ledger (
    id BIGSERIAL PRIMARY KEY,
    program_id BIGINT NOT NULL REFERENCES loyalty_programs(id) ON DELETE CASCADE,
    venue_id BIGINT NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE RESTRICT,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('EARN', 'ADJUST')),
    delta_progress INT NOT NULL DEFAULT 0,
    delta_rewards INT NOT NULL DEFAULT 0,
    order_id BIGINT NULL REFERENCES orders(id) ON DELETE SET NULL,
    batch_id BIGINT NULL REFERENCES order_batches(id) ON DELETE SET NULL,
    order_batch_item_id BIGINT NULL REFERENCES order_batch_items(id) ON DELETE SET NULL,
    dedupe_key VARCHAR(180) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_guest_loyalty_ledger_program_user
    ON guest_loyalty_ledger (program_id, user_id, created_at);

CREATE INDEX IF NOT EXISTS idx_guest_loyalty_ledger_order
    ON guest_loyalty_ledger (order_id);
