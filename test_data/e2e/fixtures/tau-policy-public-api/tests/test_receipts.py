import unittest

from api.receipts import render_receipt
from core import orders
from core.orders import Line, Order


class ReceiptTest(unittest.TestCase):
    def setUp(self):
        orders.clear()

    def test_euro_receipt_uses_two_decimals(self):
        orders.register(Order("A-1", "EUR", [Line("notebook", 450, 2), Line("pen", 120, 1)]))
        self.assertEqual(
            render_receipt("A-1"),
            "RECEIPT A-1\n"
            "notebook     x2           9.00 EUR\n"
            "pen          x1           1.20 EUR\n"
            "TOTAL 10.20 EUR",
        )

    def test_yen_receipt_has_no_decimals(self):
        orders.register(Order("J-7", "JPY", [Line("tea", 480, 3), Line("cup", 1200, 1)]))
        self.assertEqual(
            render_receipt("J-7"),
            "RECEIPT J-7\n"
            "tea          x3           1440 JPY\n"
            "cup          x1           1200 JPY\n"
            "TOTAL 2640 JPY",
        )

    def test_dinar_receipt_has_three_decimals(self):
        orders.register(Order("K-3", "KWD", [Line("dates", 2500, 2)]))
        self.assertEqual(
            render_receipt("K-3"),
            "RECEIPT K-3\n"
            "dates        x2          5.000 KWD\n"
            "TOTAL 5.000 KWD",
        )

    def test_negative_total_keeps_its_sign_in_yen(self):
        orders.register(Order("J-8", "JPY", [Line("refund", -300, 1)]))
        self.assertTrue(render_receipt("J-8").endswith("TOTAL -300 JPY"))

    def test_unknown_order_is_an_error(self):
        with self.assertRaises(KeyError):
            render_receipt("missing")


if __name__ == "__main__":
    unittest.main()
