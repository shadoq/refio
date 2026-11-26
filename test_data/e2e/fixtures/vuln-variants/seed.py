#!/usr/bin/env python3
"""Write a seeded database file, e.g. `python3 seed.py shop.db` (default: catalogue.db)."""

import sys

from shop import db

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "catalogue.db"
    conn = db.connect(target)
    for table in ("customers", "products", "orders", "reviews", "offers"):
        count = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        print(f"{table}: {count} rows")
    conn.close()
    print(f"seeded {target}")
