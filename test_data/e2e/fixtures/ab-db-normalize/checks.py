#!/usr/bin/env python3
"""Acceptance gate: raw_orders.db must now contain the normalized schema
(customers/orders/order_items) with every raw row represented and nothing lost.

The raw table stays as the immutable source, so the gate re-derives everything from it
and cross-checks the normalized tables.
"""
import re
import sqlite3
import sys


def raw_rows():
    con = sqlite3.connect("raw_orders.db")
    cur = con.cursor()
    rows = cur.execute(
        "SELECT id, customer_name, customer_email, placed_at, items_csv FROM raw_orders ORDER BY id"
    ).fetchall()
    con.close()
    return rows


def main() -> int:
    rows = raw_rows()
    con = sqlite3.connect("raw_orders.db")
    cur = con.cursor()

    def cols(table):
        return sorted(r[1] for r in cur.execute(f"PRAGMA table_info({table})").fetchall())

    problems = []
    want = {
        "customers": ["email", "id", "name"],
        "orders": ["customer_id", "id", "placed_at"],
        "order_items": ["order_id", "qty", "sku"],
    }
    for table, expected_cols in want.items():
        try:
            got = cols(table)
        except sqlite3.OperationalError:
            problems.append(f"table {table} is missing")
            continue
        if got != expected_cols:
            problems.append(f"table {table} columns {got}, expected {expected_cols}")

    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1

    # uniqueness on customers.email must be enforced by a UNIQUE index or constraint
    uniq = 0
    for idx in cur.execute("PRAGMA index_list(customers)").fetchall():
        if idx[2]:  # unique flag
            uniq += 1
    if uniq == 0:
        problems.append("customers.email is not UNIQUE (no unique index)")

    emails = [r[2].strip().lower() for r in rows]
    distinct_emails = sorted(set(emails))
    cust = cur.execute("SELECT COUNT(*), COUNT(DISTINCT email) FROM customers").fetchall()[0]
    if cust[0] != len(distinct_emails) or cust[1] != len(distinct_emails):
        problems.append(
            f"customers has {cust[0]} rows / {cust[1]} distinct emails, "
            f"raw implies {len(distinct_emails)} unique customers"
        )
    db_emails = {r[0].strip().lower() for r in cur.execute("SELECT email FROM customers").fetchall()}
    missing = set(distinct_emails) - db_emails
    if missing:
        problems.append(f"customers missing emails: {sorted(missing)}")
    bad_names = cur.execute(
        "SELECT COUNT(*) FROM customers WHERE name IS NULL OR name=''"
    ).fetchone()[0]
    if bad_names:
        problems.append(f"{bad_names} customer rows have empty name")

    if cur.execute("SELECT COUNT(*) FROM orders").fetchone()[0] != len(rows):
        problems.append(f"orders must have {len(rows)} rows (one per raw order)")
    orphan = cur.execute(
        "SELECT COUNT(*) FROM orders o LEFT JOIN customers c ON c.id=o.customer_id WHERE c.id IS NULL"
    ).fetchone()[0]
    if orphan:
        problems.append(f"{orphan} orders reference a missing customer")

    raw_items = []
    for r in rows:
        for item in r[4].split(","):
            sku, qty = item.strip().split(":")
            raw_items.append((r[0], sku, int(qty)))
    n_items = cur.execute("SELECT COUNT(*) FROM order_items").fetchone()[0]
    if n_items != len(raw_items):
        problems.append(f"order_items has {n_items} rows, raw implies {len(raw_items)}")
    raw_qty = sum(q for _, _, q in raw_items)
    db_qty = cur.execute("SELECT COALESCE(SUM(qty),0) FROM order_items").fetchone()[0]
    if raw_qty != db_qty:
        problems.append(f"quantity conservation broken: db has {db_qty}, raw implies {raw_qty}")
    # spot check: every (order_id, sku) pair from raw exists with the right qty
    db_pairs = {
        (r[0], r[1]): r[2] for r in cur.execute("SELECT order_id, sku, qty FROM order_items")
    }
    order_rowids = {
        r[0]: r[1] for r in cur.execute("SELECT id, customer_id, placed_at FROM orders")
    }
    raw_order_ids = [r[0] for r in rows]
    if sorted(order_rowids) != sorted(raw_order_ids):
        problems.append("orders ids do not match the raw order ids 1:1")
    con.close()
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: normalized schema present, all rows represented, nothing lost")
    return 0


if __name__ == "__main__":
    sys.exit(main())
