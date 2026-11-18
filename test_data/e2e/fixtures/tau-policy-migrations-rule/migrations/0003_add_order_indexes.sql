CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_status_placed ON orders(status, placed_at);
