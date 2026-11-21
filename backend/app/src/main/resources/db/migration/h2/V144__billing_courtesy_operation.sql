ALTER TABLE billing_adjustments ADD COLUMN operation_key UUID;
ALTER TABLE billing_adjustments ADD COLUMN operation_response TEXT;
ALTER TABLE billing_adjustments ADD CONSTRAINT uq_billing_adjustment_operation UNIQUE (operation_key);
