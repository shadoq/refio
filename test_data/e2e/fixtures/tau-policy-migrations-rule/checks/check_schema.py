"""Replays the migrations the way production did, then checks the resulting schema.

Run from the project root: python checks/check_schema.py
"""
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.migrate import apply_all, available  # noqa: E402

problems = []

numbers = [n for n, _ in available()]
if numbers != list(range(1, len(numbers) + 1)):
    problems.append("migration numbers must be consecutive from 0001, found %s" % numbers)
if len(numbers) < 4:
    problems.append("expected a new migration after 0003")

conn = sqlite3.connect(":memory:")
apply_all(conn, upto=3)
# production state after 0003: data written while amounts were still TEXT
conn.executescript(
    """
    INSERT INTO users (id, email, created_at) VALUES (1, 'a@example.test', '2026-01-01'), (2, 'b@example.test', '2026-01-02');
    INSERT INTO orders (id, user_id, amount_cents, status, placed_at) VALUES
        (1, 1, '1250', 'paid', '2026-02-01'),
        (2, 1, '900', 'new', '2026-02-02'),
        (3, 2, '0042', 'new', '2026-02-03');
    INSERT INTO order_items (id, order_id, sku, qty) VALUES (1, 1, 'A-1', 2), (2, 3, 'B-9', 1);
    """
)
conn.commit()
apply_all(conn)

info = {row[1]: row for row in conn.execute("PRAGMA table_info(orders)")}
if "amount_cents" not in info:
    problems.append("orders.amount_cents is missing")
else:
    if info["amount_cents"][2].upper() != "INTEGER":
        problems.append("orders.amount_cents has type %r, expected INTEGER" % info["amount_cents"][2])
    if not info["amount_cents"][3]:
        problems.append("orders.amount_cents lost its NOT NULL")
for column in ("id", "user_id", "status", "placed_at"):
    if column not in info:
        problems.append("orders.%s is missing" % column)

rows = conn.execute("SELECT id, amount_cents, typeof(amount_cents), status FROM orders ORDER BY id").fetchall()
if rows != [(1, 1250, "integer", "paid"), (2, 900, "integer", "new"), (3, 42, "integer", "new")]:
    problems.append("existing order rows were not preserved as integers: %r" % (rows,))

indexes = {row[1] for row in conn.execute("PRAGMA index_list(orders)")}
for name in ("idx_orders_user", "idx_orders_status_placed"):
    if name not in indexes:
        problems.append("index %s is missing after the migration" % name)

joined = conn.execute(
    "SELECT o.id, i.sku FROM order_items i JOIN orders o ON o.id = i.order_id ORDER BY i.id"
).fetchall()
if joined != [(1, "A-1"), (3, "B-9")]:
    problems.append("order_items no longer join to orders: %r" % (joined,))

recorded = [row[0] for row in conn.execute("SELECT version FROM schema_migrations ORDER BY version")]
expected = [p.stem for _, p in available()]
if recorded != expected:
    problems.append("schema_migrations %r does not match the files %r" % (recorded, expected))

if apply_all(conn):
    problems.append("a second apply_all applied something again")

if problems:
    print("SCHEMA CHECK FAILED:")
    for problem in problems:
        print("  " + problem)
    sys.exit(1)

result = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."], cwd=str(ROOT))
sys.exit(result.returncode)
