# shop

`shop` is the query layer of the catalogue service for Kelly's Art Supplies. It is a small
Python package (standard library only, `sqlite3`) that the web front end and the weekly
report jobs import to read customers, products, orders, reviews and offers.

## Layout

| path | content |
| --- | --- |
| `shop/db.py` | `connect()` opens the catalogue database (in memory by default, seeded from `shop/seed.py`) |
| `shop/seed.py` | schema and seed rows |
| `shop/offers.py` | discount offers |
| `shop/orders.py` | orders |
| `shop/customers.py` | customer lookups and search |
| `shop/inventory.py` | products |
| `shop/reviews.py` | product reviews |
| `shop/reports.py` | aggregate figures for the weekly jobs |
| `cli.py` | thin command-line front end (`python3 cli.py --help`) |
| `seed.py` | writes a seeded database file (`python3 seed.py shop.db`) |
| `tests/` | unit tests (`python3 -m unittest`) |

Every query function takes an open connection as its first argument:

```python
from shop import db, orders

conn = db.connect()
rows = orders.orders_for_customer(conn, "dana.palmer@example.com")
```

## Conventions

- Query functions return a list of tuples (column order is in each docstring) and never
  commit; the caller owns the transaction.
- The in-memory database is rebuilt per process, so tests can run anywhere.

## Security

Advisories are listed in `SECURITY.md`. `check.py` in the project root is the acceptance
gate for security work on the query layer; it is maintained by the security team, not by the
application team.
