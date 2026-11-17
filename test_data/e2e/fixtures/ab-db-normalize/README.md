# Migration run - raw_orders.db

- `raw_orders.db` - the export: one denormalized `raw_orders` table (customer repeated
  per row, items packed as `sku:qty,sku:qty`, at least one uppercase duplicate email).
- `docs/target-schema.md` - the authoritative target schema and rules.
- `checks.py` - the acceptance gate (do not modify it).

Normalize the database in place (new tables next to the raw one, data migrated over,
uniqueness enforced by the schema). You may use the sqlite3 CLI, Python, or any tool
available; the raw table must remain untouched as the source of truth.
