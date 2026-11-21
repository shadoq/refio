# Target schema for the raw orders export (authoritative)

`raw_orders.db` currently holds one denormalized table (`raw_orders`) with the customer
denormalized into every row and the items packed into a `sku:qty,sku:qty` CSV string.
Normalize it **in the same database** into:

```
customers(id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL)
orders(id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, placed_at TEXT NOT NULL)
order_items(order_id INTEGER NOT NULL, sku TEXT NOT NULL, qty INTEGER NOT NULL)
```

Rules:

- one `customers` row per distinct email (emails are case-insensitive: the export has
  at least one uppercase duplicate that must fold into the same customer),
- one `orders` row per `raw_orders` row, referencing the merged customer,
- one `order_items` row per `sku:qty` pair, quantities preserved exactly,
- email uniqueness must be **enforced by the schema** (UNIQUE), not just by convention,
- `raw_orders` itself stays untouched as the immutable source.

`python3 checks.py` must exit 0 (it re-derives the expected rows from `raw_orders` and
cross-checks the normalized tables, column by column). Do not modify `raw_orders` table
contents, the docs or the gate.
