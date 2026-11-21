"""Builds the shop database from the CSV files in data/.

    python build_db.py shop.db      # writes a database file you can explore
"""
import csv
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

SCHEMA = """
CREATE TABLE customers (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    city TEXT NOT NULL,
    signup_date TEXT NOT NULL
);
CREATE TABLE products (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL,
    price_cents INTEGER NOT NULL
);
CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    order_date TEXT NOT NULL,
    status TEXT NOT NULL
);
CREATE TABLE order_items (
    order_id INTEGER NOT NULL REFERENCES orders(id),
    product_id INTEGER NOT NULL REFERENCES products(id),
    qty INTEGER NOT NULL
);
"""

TABLES = {
    "customers": ["id", "name", "city", "signup_date"],
    "products": ["id", "name", "category", "price_cents"],
    "orders": ["id", "customer_id", "order_date", "status"],
    "order_items": ["order_id", "product_id", "qty"],
}


def build(path=":memory:"):
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    for table, cols in TABLES.items():
        with open(os.path.join(HERE, "data", table + ".csv"), newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        conn.executemany(
            "INSERT INTO %s (%s) VALUES (%s)" % (table, ", ".join(cols), ", ".join("?" * len(cols))),
            [[r[c] for c in cols] for r in rows],
        )
    conn.commit()
    return conn


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "shop.db"
    if os.path.exists(target):
        os.remove(target)
    build(target).close()
    print("wrote", target)
