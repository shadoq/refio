DROP VIEW v_customer_totals;
DROP TABLE audit_log;
DROP TABLE purchases;
CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    total_cents INTEGER NOT NULL
);
CREATE INDEX idx_orders_customer ON orders(customer_id);
