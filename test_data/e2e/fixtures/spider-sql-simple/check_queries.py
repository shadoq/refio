"""Runs the queries from queries.sql and compares their results with the reference results.

Every query is run against the shop database and against a second database that has
extra rows, so a query that only returns the values of the first database fails.
"""
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from build_db import build

REFERENCE = {
    "q1_lisbon_customers":
        "SELECT name FROM customers WHERE city = 'Lisbon' ORDER BY name",
    "q2_orders_per_status":
        "SELECT status, COUNT(*) AS n FROM orders GROUP BY status ORDER BY n DESC, status",
    "q3_delivered_revenue":
        "SELECT COALESCE(SUM(oi.qty * p.price_cents), 0) FROM orders o "
        "JOIN order_items oi ON oi.order_id = o.id JOIN products p ON p.id = oi.product_id "
        "WHERE o.status = 'delivered'",
    "q4_customers_without_orders":
        "SELECT name FROM customers WHERE id NOT IN (SELECT customer_id FROM orders) ORDER BY name",
    "q5_priciest_per_category":
        "SELECT category, name FROM products p WHERE price_cents = "
        "(SELECT MAX(price_cents) FROM products WHERE category = p.category) ORDER BY category",
}

EXTRA = """
INSERT INTO customers VALUES (100, 'Zed', 'Lisbon', '2024-01-01');
INSERT INTO customers VALUES (101, 'Aaron', 'Lisbon', '2024-01-02');
INSERT INTO customers VALUES (102, 'Abel', 'Faro', '2024-01-03');
INSERT INTO products VALUES (100, 'Novelty pen', 'pens', 9900);
INSERT INTO products VALUES (101, 'Teapot', 'kitchen', 700);
INSERT INTO orders VALUES (1000, 100, '2024-06-01', 'delivered');
INSERT INTO orders VALUES (1001, 100, '2024-06-02', 'returned');
INSERT INTO order_items VALUES (1000, 1, 3);
INSERT INTO order_items VALUES (1000, 100, 1);
INSERT INTO order_items VALUES (1001, 2, 1);
"""


def parse_queries(text):
    queries = {}
    name = None
    for line in text.splitlines():
        m = re.match(r"--\s*name:\s*(\S+)", line)
        if m:
            name = m.group(1)
            queries[name] = []
        elif name and not line.lstrip().startswith("--"):
            queries[name].append(line)
    cleaned = {}
    for key, lines in queries.items():
        sql = "\n".join(lines).strip()
        cleaned[key] = sql[:-1].strip() if sql.endswith(";") else sql
    return cleaned


def run(conn, sql):
    cur = conn.execute(sql)
    return [tuple(r) for r in cur.fetchall()]


def main():
    with open(os.path.join(HERE, "queries.sql"), encoding="utf-8") as f:
        queries = parse_queries(f.read())
    failures = 0
    for name, reference in REFERENCE.items():
        sql = queries.get(name, "")
        if not sql:
            print("FAIL %s: no query written" % name)
            failures += 1
            continue
        if not re.match(r"(?is)\s*(select|with)\b", sql):
            print("FAIL %s: only SELECT statements are allowed" % name)
            failures += 1
            continue
        ok = True
        for label, extra in (("base data", False), ("extended data", True)):
            conn = build()
            if extra:
                conn.executescript(EXTRA)
            conn.execute("PRAGMA query_only = ON")
            try:
                got = run(conn, sql)
            except sqlite3.Error as exc:
                print("FAIL %s: %s on %s" % (name, exc, label))
                ok = False
                break
            want = run(conn, reference)
            if got != want:
                print("FAIL %s: result differs on %s (got %d rows, want %d rows)" % (name, label, len(got), len(want)))
                ok = False
                break
            conn.close()
        if ok:
            print("PASS %s" % name)
        else:
            failures += 1
    print("%d of %d queries correct" % (len(REFERENCE) - failures, len(REFERENCE)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
