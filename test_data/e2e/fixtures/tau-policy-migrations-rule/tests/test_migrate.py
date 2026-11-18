import sqlite3
import unittest

from app.migrate import apply_all


class MigrateTest(unittest.TestCase):
    def setUp(self):
        self.conn = sqlite3.connect(":memory:")

    def test_fresh_database_gets_all_tables(self):
        apply_all(self.conn)
        names = {r[0] for r in self.conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({"users", "orders", "order_items", "schema_migrations"} <= names)

    def test_applying_twice_changes_nothing(self):
        apply_all(self.conn)
        self.assertEqual(apply_all(self.conn), [])

    def test_amounts_sort_numerically(self):
        apply_all(self.conn)
        self.conn.execute("INSERT INTO users (id, email, created_at) VALUES (1, 'a@example.test', '2026-01-01')")
        for oid, cents in ((1, 900), (2, 12500), (3, 45)):
            self.conn.execute(
                "INSERT INTO orders (id, user_id, amount_cents, status, placed_at) VALUES (?, 1, ?, 'new', '2026-01-02')",
                (oid, cents),
            )
        rows = [r[0] for r in self.conn.execute("SELECT amount_cents FROM orders ORDER BY amount_cents")]
        self.assertEqual(rows, [45, 900, 12500])


if __name__ == "__main__":
    unittest.main()
