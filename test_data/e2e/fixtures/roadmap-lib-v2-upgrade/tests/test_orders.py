import unittest

from app.orders import order_totals
from helpers import write_tmp


class OrdersTest(unittest.TestCase):
    def test_semicolon_file_with_padding_and_bad_amount(self):
        path = write_tmp(self, "orders.csv", "customer;amount\n acme ; 12.50\nzed;oops\nacme;3.25\n\nzed;1\n")
        self.assertEqual(order_totals(path), {"acme": 15.75, "zed": 1.0})


if __name__ == "__main__":
    unittest.main()
