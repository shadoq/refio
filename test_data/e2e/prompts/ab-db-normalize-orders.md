A partner exported their orders into `raw_orders.db` as one denormalized table
(`raw_orders`: customer name and email repeated on every row, items packed into a
`sku:qty,sku:qty` CSV column, and at least one uppercase duplicate of an email that must
fold into the same customer). The target schema and the migration rules are documented
in `docs/target-schema.md`.

Your task: normalize the database **in place** - create the `customers`, `orders` and
`order_items` tables next to the raw table, migrate every row over (one customer per
distinct email, case-insensitive; one order per raw row; one item row per `sku:qty`
pair, quantities preserved), and make email uniqueness enforced by the schema itself
(UNIQUE). Keep `raw_orders` untouched as the immutable source. Any tool is fine (sqlite3
CLI, Python, ...). `python3 checks.py` must exit 0 - it re-derives everything from the
raw table and cross-checks your tables, columns included.
