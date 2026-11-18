"""Builds the bike rental database from the CSV files in data/.

    python build_db.py rentals.db      # writes a database file you can explore

The data is loaded as it is: nothing is cleaned. Empty `minutes` and `fee_cents`
become NULL, every other column keeps its text exactly as in the file.
"""
import csv
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

SCHEMA = """
CREATE TABLE members (
    id INTEGER,
    full_name TEXT,
    joined TEXT,
    city TEXT
);
CREATE TABLE bikes (
    id INTEGER PRIMARY KEY,
    model TEXT,
    station TEXT
);
CREATE TABLE rentals (
    id INTEGER,
    member_id INTEGER,
    bike_id INTEGER,
    start_date TEXT,
    minutes INTEGER,
    fee_cents INTEGER,
    status TEXT
);
"""

TABLES = {
    "members": ["id", "full_name", "joined", "city"],
    "bikes": ["id", "model", "station"],
    "rentals": ["id", "member_id", "bike_id", "start_date", "minutes", "fee_cents", "status"],
}
NULLABLE = {"minutes", "fee_cents"}


def build(path=":memory:"):
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    for table, cols in TABLES.items():
        with open(os.path.join(HERE, "data", table + ".csv"), newline="", encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        values = [[None if (c in NULLABLE and r[c] == "") else r[c] for c in cols] for r in rows]
        conn.executemany(
            "INSERT INTO %s (%s) VALUES (%s)" % (table, ", ".join(cols), ", ".join("?" * len(cols))),
            values,
        )
    conn.commit()
    return conn


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "rentals.db"
    if os.path.exists(target):
        os.remove(target)
    build(target).close()
    print("wrote", target)
