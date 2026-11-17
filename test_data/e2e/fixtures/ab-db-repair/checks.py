#!/usr/bin/env python3
"""Acceptance gate: crm.db must satisfy every invariant in docs/invariants.md.

The money total (877.0) and the row counts (6 customers / 6 orders / 10 items) are
digests of the correct repair - they hold only when orphans are dropped, duplicates are
merged (not copied), the NULL-email account is retired, and comma decimals are
normalized to real numbers without changing any amount.
"""
import sqlite3
import sys

EXPECTED_REVENUE = 877.0
EXPECTED_COUNTS = (6, 6, 10)


def main() -> int:
    problems = []
    con = sqlite3.connect("crm.db")
    cur = con.cursor()

    orphan_items = cur.execute(
        "SELECT COUNT(*) FROM order_items oi LEFT JOIN orders o ON o.id=oi.order_id "
        "WHERE o.id IS NULL"
    ).fetchone()[0]
    if orphan_items:
        problems.append(f"{orphan_items} order_items rows reference a missing order")
    orphan_orders = cur.execute(
        "SELECT COUNT(*) FROM orders o LEFT JOIN customers c ON c.id=o.customer_id "
        "WHERE c.id IS NULL"
    ).fetchone()[0]
    if orphan_orders:
        problems.append(f"{orphan_orders} orders reference a missing customer")

    null_emails = cur.execute(
        "SELECT COUNT(*) FROM customers WHERE email IS NULL OR email=''"
    ).fetchone()[0]
    if null_emails:
        problems.append(f"{null_emails} customers still have a NULL/empty email")
    dupes = cur.execute(
        "SELECT COUNT(*) FROM (SELECT email FROM customers GROUP BY email HAVING COUNT(*) > 1)"
    ).fetchone()[0]
    if dupes:
        problems.append(f"{dupes} email values are still duplicated across customers")

    text_prices = cur.execute(
        "SELECT COUNT(*) FROM order_items WHERE typeof(unit_price) != 'real'"
    ).fetchone()[0]
    if text_prices:
        problems.append(f"{text_prices} order_items rows still store unit_price as TEXT")
    bad_prices = cur.execute(
        "SELECT COUNT(*) FROM order_items WHERE unit_price IS NULL OR unit_price <= 0"
    ).fetchone()[0]
    if bad_prices:
        problems.append(f"{bad_prices} order_items rows have a non-positive price")

    counts = (
        cur.execute("SELECT COUNT(*) FROM customers").fetchone()[0],
        cur.execute("SELECT COUNT(*) FROM orders").fetchone()[0],
        cur.execute("SELECT COUNT(*) FROM order_items").fetchone()[0],
    )
    if counts != EXPECTED_COUNTS:
        problems.append(f"row counts are {counts}, the correct repair has {EXPECTED_COUNTS}")
    revenue = cur.execute("SELECT ROUND(SUM(qty*unit_price), 2) FROM order_items").fetchone()[0]
    if revenue != EXPECTED_REVENUE:
        problems.append(f"money total is {revenue}, expected {EXPECTED_REVENUE}")
    merged = cur.execute("SELECT customer_id FROM orders WHERE id=103").fetchone()[0]
    if merged != 1:
        problems.append(f"order 103 still points at customer {merged} (duplicate account was not merged)")
    con.close()
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: every invariant holds and no money was created or destroyed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
