ALTER TABLE orders RENAME TO purchases;

ALTER TABLE purchases ADD COLUMN status TEXT NOT NULL DEFAULT 'new';

CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY,
    note TEXT NOT NULL
);

CREATE VIEW v_customer_totals AS
SELECT customer_id, SUM(total_cents) AS total_cents
FROM purchases
GROUP BY customer_id;
