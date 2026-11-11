-- Exact-unit loyalty correction; no order, bill or receipt data is rewritten.
CREATE TABLE loyalty_unit_corrections (
    id BIGSERIAL PRIMARY KEY,
    venue_id BIGINT NOT NULL REFERENCES venues(id),
    request_id VARCHAR(80) NOT NULL,
    paid_unit_id BIGINT NOT NULL UNIQUE REFERENCES loyalty_paid_units(id),
    order_id BIGINT NOT NULL REFERENCES orders(id),
    order_batch_item_id BIGINT NOT NULL REFERENCES order_batch_items(id),
    unit_index INT NOT NULL CHECK (unit_index >= 1),
    guest_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    cycle_id_snapshot VARCHAR(100) NOT NULL REFERENCES loyalty_accumulation_cycles(id),
    payment_fact_snapshot VARCHAR(180) NOT NULL,
    unit_price_minor_snapshot BIGINT NOT NULL CHECK (unit_price_minor_snapshot > 0),
    actor_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    reason VARCHAR(500) NOT NULL,
    actor_label_snapshot VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (venue_id, request_id)
);
CREATE INDEX idx_loyalty_unit_corrections_order ON loyalty_unit_corrections(venue_id, order_id);
ALTER TABLE loyalty_redemptions ADD COLUMN funding_basis VARCHAR(32) NOT NULL DEFAULT 'EARNED'
    CHECK (funding_basis IN ('EARNED', 'VENUE_COMPENSATION'));
ALTER TABLE loyalty_redemptions ADD COLUMN compensated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE loyalty_redemptions ADD COLUMN compensation_source_order_id BIGINT REFERENCES orders(id);
ALTER TABLE loyalty_correction_expenses ADD COLUMN recorded_by_user_id BIGINT REFERENCES users(telegram_user_id);
ALTER TABLE loyalty_correction_expenses ADD COLUMN issued_at_snapshot TIMESTAMP WITH TIME ZONE;
ALTER TABLE loyalty_correction_expenses ADD COLUMN issued_by_user_id_snapshot BIGINT REFERENCES users(telegram_user_id);
ALTER TABLE loyalty_correction_expenses ADD COLUMN delivery_version_snapshot INT CHECK (delivery_version_snapshot > 0);
ALTER TABLE loyalty_correction_expenses ADD COLUMN correction_id BIGINT REFERENCES loyalty_unit_corrections(id);
-- Existing active 002 expense facts already prove venue funding and the current physical issue.
UPDATE loyalty_redemptions SET funding_basis = 'VENUE_COMPENSATION',
    compensated_at = (SELECT e.created_at FROM loyalty_correction_expenses e WHERE e.cycle_id = loyalty_redemptions.cycle_id AND e.active_slot = 1),
    compensation_source_order_id = (SELECT e.order_id FROM loyalty_correction_expenses e WHERE e.cycle_id = loyalty_redemptions.cycle_id AND e.active_slot = 1)
WHERE origin = 'PAYMENT_POLICY' AND status = 'APPLIED'
    AND EXISTS (SELECT 1 FROM loyalty_correction_expenses e WHERE e.cycle_id = loyalty_redemptions.cycle_id AND e.active_slot = 1);
UPDATE loyalty_correction_expenses SET
    issued_at_snapshot = (SELECT r.issued_at FROM loyalty_redemptions r WHERE r.id = loyalty_correction_expenses.redemption_id),
    issued_by_user_id_snapshot = (SELECT r.issued_by_user_id FROM loyalty_redemptions r WHERE r.id = loyalty_correction_expenses.redemption_id),
    delivery_version_snapshot = (SELECT r.delivery_version FROM loyalty_redemptions r WHERE r.id = loyalty_correction_expenses.redemption_id)
WHERE active_slot = 1 AND EXISTS (SELECT 1 FROM loyalty_redemptions r WHERE r.id = loyalty_correction_expenses.redemption_id AND r.origin = 'PAYMENT_POLICY' AND r.status = 'APPLIED');
CREATE UNIQUE INDEX uq_loyalty_expense_physical_issue
    ON loyalty_correction_expenses(redemption_id, delivery_version_snapshot);

CREATE TABLE loyalty_compensation_cancellations (
    redemption_id BIGINT PRIMARY KEY REFERENCES loyalty_redemptions(id),
    venue_id BIGINT NOT NULL REFERENCES venues(id),
    request_id VARCHAR(80) NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users(telegram_user_id),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (venue_id, request_id)
);
