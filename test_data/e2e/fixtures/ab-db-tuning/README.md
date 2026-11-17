# Tuning pass - shop.db

- `shop.db` - the database (orders, order_items, customers; ~4k orders). Read-only for
  the data itself - only indexes may be added.
- `docs/slow-queries.md` - the three queries and the index each one needs.
- `checks.py` - the acceptance gate (do not modify it).

Add the indexes to `shop.db` (sqlite3 CLI or Python) and document the DDL in
`indexes.sql`.
