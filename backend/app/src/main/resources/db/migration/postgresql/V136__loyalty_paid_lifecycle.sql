-- Prospective paid loyalty policy. Existing balances are preserved as explicitly legacy origin.
CREATE TABLE loyalty_payment_policy (
    id INT PRIMARY KEY CHECK (id = 1),
    first_item_id BIGINT NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO loyalty_payment_policy (id, first_item_id)
SELECT 1, COALESCE(MAX(id), 0) + 1 FROM order_batch_items;

CREATE TABLE loyalty_terms_versions (
    id VARCHAR(100) PRIMARY KEY,
    program_id BIGINT NOT NULL REFERENCES loyalty_programs(id),
    nth_value INT NOT NULL CHECK (nth_value BETWEEN 2 AND 50),
    origin VARCHAR(32) NOT NULL CHECK (origin IN ('LEGACY_CUTOVER', 'PAYMENT_POLICY')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE loyalty_terms_targets (
    version_id VARCHAR(100) NOT NULL REFERENCES loyalty_terms_versions(id),
    target_kind VARCHAR(8) NOT NULL CHECK (target_kind IN ('EARN', 'REWARD')),
    target_type VARCHAR(32) NOT NULL CHECK (target_type IN ('CATEGORY_TYPE', 'MENU_ITEM')),
    semantic_type VARCHAR(32),
    menu_item_id BIGINT,
    menu_item_name VARCHAR(500)
);
INSERT INTO loyalty_terms_versions (id, program_id, nth_value, origin)
SELECT 'legacy-' || CAST(id AS VARCHAR), id, nth_value, 'LEGACY_CUTOVER' FROM loyalty_programs;
INSERT INTO loyalty_terms_targets (version_id, target_kind, target_type, semantic_type, menu_item_id, menu_item_name)
SELECT 'legacy-' || CAST(t.program_id AS VARCHAR), 'EARN', t.target_type, t.semantic_type, t.menu_item_id, mi.name
FROM loyalty_program_earn_targets t LEFT JOIN menu_items mi ON mi.id = t.menu_item_id;
INSERT INTO loyalty_terms_targets (version_id, target_kind, target_type, semantic_type, menu_item_id, menu_item_name)
SELECT 'legacy-' || CAST(t.program_id AS VARCHAR), 'REWARD', t.target_type, t.semantic_type, t.menu_item_id, mi.name
FROM loyalty_program_reward_targets t LEFT JOIN menu_items mi ON mi.id = t.menu_item_id;

CREATE TABLE loyalty_legacy_balances (
    program_id BIGINT NOT NULL REFERENCES loyalty_programs(id),
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    terms_id VARCHAR(100) NOT NULL REFERENCES loyalty_terms_versions(id),
    progress_initial INT NOT NULL CHECK (progress_initial >= 0),
    rewards_initial INT NOT NULL CHECK (rewards_initial >= 0),
    rewards_remaining INT NOT NULL CHECK (rewards_remaining >= 0),
    reserved_initial INT NOT NULL CHECK (reserved_initial >= 0),
    PRIMARY KEY (program_id, user_id)
);
INSERT INTO loyalty_legacy_balances
SELECT program_id, user_id, 'legacy-' || CAST(program_id AS VARCHAR), progress_count,
       rewards_available, rewards_available, rewards_reserved FROM guest_loyalty_progress;

CREATE TABLE loyalty_accumulation_cycles (
    id VARCHAR(100) PRIMARY KEY,
    program_id BIGINT NOT NULL REFERENCES loyalty_programs(id),
    venue_id BIGINT NOT NULL REFERENCES venues(id),
    user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    terms_id VARCHAR(100) NOT NULL REFERENCES loyalty_terms_versions(id),
    legacy_units INT NOT NULL DEFAULT 0 CHECK (legacy_units >= 0),
    state VARCHAR(20) NOT NULL CHECK (state IN ('EMPTY', 'OPEN', 'AVAILABLE', 'RESERVED', 'APPLIED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO loyalty_accumulation_cycles (id, program_id, venue_id, user_id, terms_id, legacy_units, state)
SELECT 'legacy-progress-' || CAST(p.program_id AS VARCHAR) || '-' || CAST(p.user_id AS VARCHAR),
       p.program_id, p.venue_id, p.user_id, 'legacy-' || CAST(p.program_id AS VARCHAR), p.progress_count, 'OPEN'
FROM guest_loyalty_progress p JOIN loyalty_programs lp ON lp.id = p.program_id
WHERE p.progress_count > 0 AND p.progress_count < lp.nth_value - 1;
-- Out-of-range legacy progress stays unchanged in its explicit legacy initial state; no guessed terms/backfill.
CREATE INDEX idx_loyalty_cycles_guest ON loyalty_accumulation_cycles(program_id, user_id, state);
CREATE TABLE loyalty_paid_units (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(100) NOT NULL REFERENCES loyalty_accumulation_cycles(id),
    order_id BIGINT NOT NULL REFERENCES orders(id),
    batch_id BIGINT NOT NULL REFERENCES order_batches(id),
    order_batch_item_id BIGINT NOT NULL REFERENCES order_batch_items(id),
    unit_index INT NOT NULL CHECK (unit_index >= 1),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    generation INT NOT NULL DEFAULT 1 CHECK (generation >= 1),
    payment_fact VARCHAR(180) NOT NULL,
    UNIQUE (order_batch_item_id, unit_index)
);
CREATE INDEX idx_loyalty_units_order ON loyalty_paid_units(order_id);
CREATE INDEX idx_loyalty_units_cycle ON loyalty_paid_units(cycle_id, active);
CREATE TABLE loyalty_correction_expenses (
    id BIGSERIAL PRIMARY KEY,
    cycle_id VARCHAR(100) NOT NULL REFERENCES loyalty_accumulation_cycles(id),
    active_slot INT CHECK (active_slot IS NULL OR active_slot = 1),
    venue_id BIGINT NOT NULL REFERENCES venues(id),
    order_id BIGINT NOT NULL REFERENCES orders(id),
    redemption_id BIGINT NOT NULL REFERENCES loyalty_redemptions(id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    reason VARCHAR(500) NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (cycle_id, active_slot),
    CHECK ((resolved_at IS NULL AND active_slot IS NOT NULL AND active_slot = 1) OR (resolved_at IS NOT NULL AND active_slot IS NULL))
);
CREATE TABLE loyalty_delivery_operations (
    venue_id BIGINT NOT NULL REFERENCES venues(id),
    request_id VARCHAR(80) NOT NULL,
    redemption_id BIGINT NOT NULL REFERENCES loyalty_redemptions(id),
    action VARCHAR(12) NOT NULL CHECK (action IN ('CONFIRM', 'CORRECT')),
    issued_at_snapshot TIMESTAMP WITH TIME ZONE,
    actor_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    reason VARCHAR(500) NOT NULL,
    PRIMARY KEY (venue_id, request_id)
);
ALTER TABLE order_batch_items ADD COLUMN loyalty_semantic_type_snapshot VARCHAR(32);
ALTER TABLE order_batch_items ADD COLUMN fulfilled_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE order_batch_items ADD COLUMN fulfilled_by_user_id BIGINT REFERENCES users(telegram_user_id);
ALTER TABLE loyalty_redemptions ADD COLUMN origin VARCHAR(32) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE loyalty_redemptions ADD COLUMN cycle_id VARCHAR(100) REFERENCES loyalty_accumulation_cycles(id);
ALTER TABLE loyalty_redemptions ADD COLUMN terms_id VARCHAR(100) REFERENCES loyalty_terms_versions(id);
ALTER TABLE loyalty_redemptions ADD COLUMN table_session_id BIGINT REFERENCES table_sessions(id);
ALTER TABLE loyalty_redemptions ADD COLUMN active_slot INT CHECK (active_slot IS NULL OR active_slot = 1);
CREATE UNIQUE INDEX uq_loyalty_redemption_guest_session
ON loyalty_redemptions(program_id, user_id, table_session_id, active_slot);
ALTER TABLE loyalty_redemptions ADD CONSTRAINT loyalty_payment_redemption_shape CHECK (
    origin = 'LEGACY' OR (origin = 'PAYMENT_POLICY' AND terms_id IS NOT NULL AND table_session_id IS NOT NULL
        AND ((status IN ('RESERVED', 'APPLIED') AND active_slot IS NOT NULL AND active_slot = 1) OR (status = 'CANCELED' AND active_slot IS NULL)))
);

ALTER TABLE guest_loyalty_ledger ADD COLUMN origin VARCHAR(32) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE guest_loyalty_ledger ADD COLUMN cycle_id VARCHAR(100) REFERENCES loyalty_accumulation_cycles(id);
ALTER TABLE guest_loyalty_ledger ADD COLUMN payment_fact VARCHAR(180);

ALTER TABLE loyalty_redemptions ADD COLUMN issued_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE loyalty_redemptions ADD COLUMN issued_by_user_id BIGINT REFERENCES users(telegram_user_id);

ALTER TABLE loyalty_redemptions ADD COLUMN delivery_version INT NOT NULL DEFAULT 0 CHECK (delivery_version >= 0);

ALTER TABLE loyalty_redemptions ADD CONSTRAINT loyalty_issued_fact_required CHECK (
    origin = 'LEGACY' OR status <> 'APPLIED'
    OR (issued_at IS NOT NULL AND issued_by_user_id IS NOT NULL AND delivery_version > 0)
);

ALTER TABLE guest_loyalty_ledger ADD COLUMN paid_unit_id BIGINT REFERENCES loyalty_paid_units(id);
ALTER TABLE guest_loyalty_ledger ADD COLUMN previous_cycle_id VARCHAR(100) REFERENCES loyalty_accumulation_cycles(id);
