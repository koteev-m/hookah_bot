CREATE INDEX idx_order_batches_dashboard_pending ON order_batches(status, order_id, id);
CREATE INDEX idx_order_batches_dashboard_first ON order_batches(order_id, id);
CREATE INDEX idx_orders_dashboard_active ON orders(venue_id, status, id);
CREATE INDEX idx_staff_calls_dashboard_pending ON staff_calls(venue_id, status, id);
