#!/usr/bin/env python3
"""Acceptance gate: answers/answers.json must answer every question in
docs/questions.md, recomputed here from shop.db with plain SQL."""
import json
import sqlite3
import sys

QUESTIONS = [
    "q1_total_revenue",
    "q2_top_customer",
    "q3_returned_orders",
    "q4_best_seller",
    "q5_april_revenue",
    "q6_emea_customers",
    "q7_avg_order_value",
    "q8_most_orders_customer_id",
]

MONEY = {"q1_total_revenue", "q5_april_revenue", "q7_avg_order_value"}


def recompute() -> dict:
    con = sqlite3.connect("shop.db")
    cur = con.cursor()
    out = {}
    out["q1_total_revenue"] = cur.execute(
        "SELECT SUM(qty*unit_price_at_sale) FROM order_items oi JOIN orders o ON o.id=oi.order_id "
        "WHERE o.status != 'cancelled'"
    ).fetchone()[0]
    out["q2_top_customer"] = cur.execute(
        "SELECT c.name FROM customers c JOIN orders o ON o.customer_id=c.id "
        "JOIN order_items oi ON oi.order_id=o.id WHERE o.status!='cancelled' "
        "GROUP BY c.id ORDER BY SUM(oi.qty*oi.unit_price_at_sale) DESC LIMIT 1"
    ).fetchone()[0]
    out["q3_returned_orders"] = cur.execute(
        "SELECT COUNT(*) FROM orders WHERE status='returned'"
    ).fetchone()[0]
    out["q4_best_seller"] = cur.execute(
        "SELECT p.name FROM products p JOIN order_items oi ON oi.product_id=p.id "
        "JOIN orders o ON o.id=oi.order_id WHERE o.status!='cancelled' "
        "GROUP BY p.id ORDER BY SUM(oi.qty) DESC, p.id LIMIT 1"
    ).fetchone()[0]
    out["q5_april_revenue"] = cur.execute(
        "SELECT SUM(oi.qty*oi.unit_price_at_sale) FROM order_items oi JOIN orders o ON o.id=oi.order_id "
        "WHERE o.status!='cancelled' AND o.placed_at LIKE '2026-04-%'"
    ).fetchone()[0]
    out["q6_emea_customers"] = cur.execute(
        "SELECT COUNT(*) FROM customers WHERE region='EMEA'"
    ).fetchone()[0]
    out["q7_avg_order_value"] = cur.execute(
        "SELECT AVG(total) FROM (SELECT SUM(oi.qty*oi.unit_price_at_sale) AS total FROM order_items oi "
        "JOIN orders o ON o.id=oi.order_id WHERE o.status!='cancelled' GROUP BY o.id)"
    ).fetchone()[0]
    out["q8_most_orders_customer_id"] = cur.execute(
        "SELECT c.id FROM customers c JOIN orders o ON o.customer_id=c.id "
        "GROUP BY c.id ORDER BY COUNT(*) DESC, c.id LIMIT 1"
    ).fetchone()[0]
    con.close()
    for key in MONEY:
        out[key] = round(out[key], 2)
    return out


def main() -> int:
    try:
        with open("answers/answers.json", encoding="utf-8") as fh:
            reported = json.load(fh)
    except OSError:
        print("FAIL: answers/answers.json is missing")
        return 1
    except ValueError as exc:
        print(f"FAIL: answers/answers.json does not parse: {exc}")
        return 1
    expected = recompute()
    problems = []
    for key in QUESTIONS:
        if key not in reported:
            problems.append(f"missing answer for {key}")
            continue
        got = reported[key]
        want = expected[key]
        if isinstance(want, float):
            try:
                ok = round(float(got), 2) == round(want, 2)
            except (TypeError, ValueError):
                ok = False
        else:
            ok = got == want
        if not ok:
            problems.append(f"{key}: reported {got!r}, recomputed {want!r}")
    extra = set(reported) - set(QUESTIONS)
    if extra:
        problems.append(f"unexpected keys: {sorted(extra)}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: all eight answers match the recomputation from shop.db")
    return 0


if __name__ == "__main__":
    sys.exit(main())
