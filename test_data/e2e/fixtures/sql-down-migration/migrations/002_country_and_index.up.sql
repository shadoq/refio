ALTER TABLE customers ADD COLUMN country TEXT NOT NULL DEFAULT 'PL';

CREATE INDEX idx_orders_customer ON orders(customer_id);
