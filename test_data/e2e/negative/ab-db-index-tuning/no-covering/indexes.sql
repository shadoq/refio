-- Tuning pass for docs/slow-queries.md
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_status_placed_at ON orders(status, placed_at);
CREATE INDEX idx_order_items_sku_qty ON order_items(sku, qty);
CREATE INDEX idx_order_items_sku ON order_items(sku);
