import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from receipt import format_receipt, format_summary, money

LINES = [("tea", 2, 350), ("cake", 1, 499)]


class Unchanged(unittest.TestCase):
    def test_money(self):
        self.assertEqual(money(1205), "12.05")
        self.assertEqual(money(5), "0.05")

    def test_plain_receipt(self):
        self.assertEqual(format_receipt(LINES), "\n".join([
            "2x tea                      7.00",
            "1x cake                     4.99",
            "--------------------------------",
            "TOTAL EUR                  11.99",
        ]))

    def test_currency_and_width(self):
        self.assertEqual(format_receipt([("pen", 1, 150)], currency="USD", width=20), "\n".join([
            "1x pen          1.50",
            "--------------------",
            "TOTAL USD       1.50",
        ]))

    def test_summary(self):
        self.assertEqual(format_summary(LINES), "3 items, 2 lines")

    def test_explicit_zero_tax_prints_no_tax_row(self):
        self.assertEqual(format_receipt(LINES, tax_rate=0.0), format_receipt(LINES))


class TaxRate(unittest.TestCase):
    def test_tax_row_and_total(self):
        self.assertEqual(format_receipt(LINES, tax_rate=0.2), "\n".join([
            "2x tea                      7.00",
            "1x cake                     4.99",
            "--------------------------------",
            "TAX 20%                     2.40",
            "TOTAL EUR                  14.39",
        ]))

    def test_tax_is_rounded_half_up_to_cents(self):
        # 1050 * 0.075 = 78.75 cents -> 79
        self.assertEqual(format_receipt([("x", 1, 1050)], tax_rate=0.075).splitlines()[-2:], [
            "TAX 7.5%                    0.79",
            "TOTAL EUR                  11.29",
        ])

    def test_tax_rate_is_keyword_after_width(self):
        self.assertEqual(format_receipt([("x", 1, 100)], "EUR", 20, 0.1).splitlines()[-2:], [
            "TAX 10%         0.10",
            "TOTAL EUR       1.10",
        ])

    def test_negative_rate_is_rejected(self):
        with self.assertRaises(ValueError):
            format_receipt(LINES, tax_rate=-0.1)


if __name__ == "__main__":
    unittest.main()
