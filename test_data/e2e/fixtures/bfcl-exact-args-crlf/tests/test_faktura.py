import unittest
from decimal import Decimal

from faktura import describe_line, invoice_total, line_totals, money


class MoneyTest(unittest.TestCase):
    def test_halves_round_up(self):
        self.assertEqual(money(Decimal("0.125")), Decimal("0.13"))
        self.assertEqual(money(Decimal("2.675")), Decimal("2.68"))
        self.assertEqual(money(Decimal("1.005")), Decimal("1.01"))

    def test_below_half_rounds_down(self):
        self.assertEqual(money(Decimal("0.124")), Decimal("0.12"))


class LineTest(unittest.TestCase):
    def test_vat_on_a_tie_is_rounded_half_up(self):
        self.assertEqual(line_totals("2.50", 1, "5"), (Decimal("2.50"), Decimal("0.13"), Decimal("2.63")))

    def test_plain_line(self):
        self.assertEqual(line_totals("10.00", 3, "23"), (Decimal("30.00"), Decimal("6.90"), Decimal("36.90")))

    def test_exempt_line_has_no_vat(self):
        self.assertEqual(line_totals("100.00", 1, "zw")[1], Decimal("0.00"))

    def test_unknown_rate_is_reported_in_polish(self):
        with self.assertRaises(ValueError) as caught:
            line_totals("1.00", 1, "7")
        self.assertEqual(str(caught.exception), "Nieprawid\u0142owa stawka VAT: 7")

    def test_description_uses_decimal_commas_and_polish_labels(self):
        self.assertEqual(
            describe_line("2.50", 1, "5"),
            "Warto\u015b\u0107 netto: 2,50 z\u0142 | Kwota VAT: 0,13 z\u0142 | Warto\u015b\u0107 brutto: 2,63 z\u0142 | stawka 5%",
        )
        self.assertTrue(describe_line("100.00", 1, "zw").endswith("zwolniony z podatku"))

    def test_invoice_total_adds_the_gross_values(self):
        lines = [("2.50", 1, "5"), ("10.00", 3, "23"), ("100.00", 1, "zw")]
        self.assertEqual(invoice_total(lines), Decimal("139.53"))


if __name__ == "__main__":
    unittest.main()
