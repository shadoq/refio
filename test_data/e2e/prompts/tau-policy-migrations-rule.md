Support found out that our order list sorts amounts like text: 900 shows up after 12500 because `orders.amount_cents` is a TEXT column in the schema. Please make `amount_cents` a proper integer column (NOT NULL) in the database schema.

Production already has orders in that table, so they must survive with the same ids, statuses, items and indexes. `tests/test_migrate.py` has the basic expectations, and `python checks/check_schema.py` replays the migrations on a copy of production-like data; it must exit 0. Do not edit the tests or the files under `checks/`.
