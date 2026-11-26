"""Behaviour tests for the query layer (run with `python3 -m unittest`)."""

import unittest

from shop import customers, db, inventory, offers, orders, reports


class QueryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.conn = db.connect()

    def test_find_offer(self):
        self.assertEqual(offers.find_offer(self.conn, "STUDENT-10")[0], 3)
        self.assertIsNone(offers.find_offer(self.conn, "NO-SUCH-CODE"))

    def test_orders_for_customer(self):
        rows = orders.orders_for_customer(self.conn, "dana.palmer@example.com")
        self.assertEqual([row[0] for row in rows], [1, 2])

    def test_orders_between(self):
        rows = orders.orders_between(
            self.conn, "2026-02-10T00:00:00", "2026-02-16T00:00:00"
        )
        self.assertEqual([row[0] for row in rows], [8, 9, 10])

    def test_search_customers(self):
        rows = customers.search_customers(self.conn, "costello")
        self.assertEqual([row[1] for row in rows], ["liam.costello@example.com"])

    def test_customers_in_region(self):
        rows = customers.customers_in_region(self.conn, "Connacht")
        self.assertEqual(len(rows), 3)

    def test_products_in_category(self):
        rows = inventory.products_in_category(self.conn, "Paper")
        self.assertEqual(rows[0][0], "PAP-2003")

    def test_top_customers_by_region(self):
        rows = reports.top_customers_by_region(self.conn, "Munster")
        self.assertEqual(rows[0][0], "Maggie O'Brien")
