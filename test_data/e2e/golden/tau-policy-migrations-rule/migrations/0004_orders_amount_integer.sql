CREATE TABLE orders_new (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    amount_cents INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'new',
    placed_at TEXT NOT NULL
);

INSERT INTO orders_new (id, user_id, amount_cents, status, placed_at)
SELECT id, user_id, CAST(amount_cents AS INTEGER), status, placed_at FROM orders;

DROP TABLE orders;
ALTER TABLE orders_new RENAME TO orders;

CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_status_placed ON orders(status, placed_at);
