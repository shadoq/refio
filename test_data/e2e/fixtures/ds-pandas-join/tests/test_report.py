import os
import unittest

import pandas as pd

from report import build_report, load_customers, load_orders

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")


class ReportTest(unittest.TestCase):
    def test_report_from_files(self):
        orders = load_orders(os.path.join(DATA, "orders.csv"))
        customers = load_customers(os.path.join(DATA, "customers.csv"))
        expected = pd.DataFrame({
            "customer_id": ["001", "002", "007", "010", "012", "013"],
            "name": ["Alder Ltd", "Birch GmbH", "Cedar SA", "Dune BV", "Elm AB", "Fir Oy"],
            "region": ["north", "south", "north", "west", "south", "west"],
            "order_count": pd.Series([2, 1, 2, 0, 1, 0], dtype="int64"),
            "revenue": pd.Series([200.5, 10.0, 315.75, 0.0, 60.0, 0.0], dtype="float64"),
        })
        pd.testing.assert_frame_equal(build_report(orders, customers), expected)

    def test_input_frames_are_not_modified(self):
        orders = load_orders(os.path.join(DATA, "orders.csv"))
        customers = load_customers(os.path.join(DATA, "customers.csv"))
        before = (orders.copy(), customers.copy())
        build_report(orders, customers)
        pd.testing.assert_frame_equal(orders, before[0])
        pd.testing.assert_frame_equal(customers, before[1])

    def test_customer_without_any_order_row(self):
        orders = pd.DataFrame({"order_id": [1], "customer_id": [5], "amount": [9.99], "status": ["paid"]})
        customers = pd.DataFrame({"customer_id": ["005", "006"], "name": ["A", "B"], "region": ["x", "y"]})
        report = build_report(orders, customers)
        self.assertEqual(report["order_count"].tolist(), [1, 0])
        self.assertEqual(str(report["order_count"].dtype), "int64")
        self.assertEqual(report["revenue"].tolist(), [9.99, 0.0])


if __name__ == "__main__":
    unittest.main()
