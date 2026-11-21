# Slow query report (authoritative)

The nightly jobs log three queries that scan the whole database (see `checks.py` for the
exact shapes). The tuning pass must give the planner the right indexes:

1. **Order lookup by customer** - `SELECT id, status, placed_at, total FROM orders WHERE
   customer_id = ?` - needs a plain index on `orders(customer_id)` named
   `idx_orders_customer_id`.
2. **Status + date window** - `SELECT id, total FROM orders WHERE status = ? AND
   placed_at >= ?` - needs a composite index `orders(status, placed_at)` (this order!)
   named `idx_orders_status_placed_at`.
3. **Per-sku aggregation** - `SELECT sku, SUM(qty) FROM order_items GROUP BY sku` - needs
   a **covering** index `order_items(sku, qty)` named `idx_order_items_sku_qty`, so the
   planner stops spilling the GROUP BY into a TEMP B-TREE.

The gate proves each point through `EXPLAIN QUERY PLAN` against the real database, so the
indexes must exist in `shop.db` itself - and the DDL must be documented in `indexes.sql`
(one `CREATE INDEX` per line). Do not modify the data, the docs or the gate.
