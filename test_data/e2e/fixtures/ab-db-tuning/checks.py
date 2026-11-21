#!/usr/bin/env python3
"""Acceptance gate: the three documented slow queries must be covered by the right
indexes - proven through EXPLAIN QUERY PLAN, not through the DDL text."""
import sqlite3
import sys

QUERIES = {
    "q1": (
        "SELECT id, status, placed_at, total FROM orders WHERE customer_id = ?",
        (17,),
        "idx_orders_customer_id",
    ),
    "q2": (
        "SELECT id, total FROM orders WHERE status = ? AND placed_at >= ?",
        ("delivered", "2026-03-01"),
        "idx_orders_status_placed_at",
    ),
    "q3": (
        "SELECT sku, SUM(qty) FROM order_items GROUP BY sku",
        (),
        "idx_order_items_sku_qty",
    ),
}


def main() -> int:
    problems = []
    try:
        with open("indexes.sql", encoding="utf-8") as fh:
            ddl = fh.read()
    except OSError:
        print("FAIL: indexes.sql is missing (document the DDL there)")
        return 1
    con = sqlite3.connect("shop.db")
    cur = con.cursor()

    # indexes.sql must declare each index
    for _, _, name in QUERIES.values():
        if f"CREATE INDEX" not in ddl.upper() or name not in ddl:
            problems.append(f"indexes.sql does not declare {name}")

    # the database itself must actually carry them
    db_indexes = {
        r[1] for r in cur.execute("PRAGMA index_list('orders')").fetchall()
    } | {r[1] for r in cur.execute("PRAGMA index_list('order_items')").fetchall()}
    for _, _, name in QUERIES.values():
        if name not in db_indexes:
            problems.append(f"index {name} does not exist in shop.db")

    # and the planner must use them
    for qid, (sql, params, name) in QUERIES.items():
        plan = "\n".join(
            str(row[3]) for row in cur.execute("EXPLAIN QUERY PLAN " + sql, params).fetchall()
        )
        if name not in plan:
            problems.append(f"{qid}: planner does not use {name}; plan was: {plan[:160]}")
        if qid == "q3" and "TEMP B-TREE" in plan:
            problems.append(f"{qid}: GROUP BY still spills to a TEMP B-TREE (need a covering index)")
        if qid == "q3" and "COVERING INDEX" not in plan:
            problems.append(f"{qid}: the sku index is not covering (must include qty)")

    # sanity: the data is untouched - aggregate over shipped units must still hold
    total_qty = cur.execute("SELECT SUM(qty) FROM order_items").fetchone()[0]
    con.close()
    if total_qty != 49694:
        problems.append(f"order_items total qty is {total_qty}, expected 49694 (data changed?)")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: all three queries hit their indexes and the data is intact")
    return 0


if __name__ == "__main__":
    sys.exit(main())
