-- Only a committed close writer sets this fact. Legacy closed orders remain unknown.
ALTER TABLE orders ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_orders_closed_period ON orders(venue_id, closed_at);
CREATE INDEX idx_order_batches_tab_first ON order_batches(order_id, tab_id, id);
