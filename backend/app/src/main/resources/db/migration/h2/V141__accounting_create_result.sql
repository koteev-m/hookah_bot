-- Durable CREATE identity; legacy receipts remain unknown without guessing or backfill.
ALTER TABLE venue_accounting_commands ADD COLUMN created_sale_id VARCHAR(80);
