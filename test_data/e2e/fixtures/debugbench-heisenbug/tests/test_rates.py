import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from rates import RateCalculator, weight_band


class Bands(unittest.TestCase):
    def test_band_edges(self):
        self.assertEqual(weight_band(0.5), 1)
        self.assertEqual(weight_band(1), 1)
        self.assertEqual(weight_band(1.01), 5)
        self.assertEqual(weight_band(20), 20)

    def test_invalid_weights(self):
        for bad in (0, -1, 20.5):
            with self.assertRaises(ValueError):
                weight_band(bad)


class SingleParcel(unittest.TestCase):
    def test_standard_rate(self):
        self.assertEqual(RateCalculator().rate(3, "EU"), 900)
        self.assertEqual(RateCalculator().rate(3, "US"), 1600)

    def test_express_is_fifty_percent_more(self):
        self.assertEqual(RateCalculator().rate(3, "EU", express=True), 1350)

    def test_express_quote_does_not_leak_into_standard_quote(self):
        calc = RateCalculator()
        self.assertEqual(calc.rate(3, "EU", express=True), 1350)
        self.assertEqual(calc.rate(3, "EU"), 900)

    def test_standard_quote_does_not_leak_into_express_quote(self):
        calc = RateCalculator()
        self.assertEqual(calc.rate(3, "EU"), 900)
        self.assertEqual(calc.rate(3, "EU", express=True), 1350)
        self.assertEqual(calc.rate(4, "EU"), 900)

    def test_repeated_calls_are_stable(self):
        calc = RateCalculator()
        results = [calc.rate(0.4, "US", express=True) for _ in range(3)]
        self.assertEqual(results, [1350, 1350, 1350])

    def test_custom_table_is_respected_per_calculator(self):
        a = RateCalculator({"EU": {1: 1, 5: 2, 20: 3}})
        b = RateCalculator()
        self.assertEqual(a.rate(2, "EU"), 2)
        self.assertEqual(b.rate(2, "EU"), 900)


class Quote(unittest.TestCase):
    def test_sum_of_parcels(self):
        self.assertEqual(RateCalculator().quote([1, 3], "EU"), 500 + 900)

    def test_bulk_fee_only_for_big_orders(self):
        calc = RateCalculator()
        self.assertEqual(calc.quote([1, 1, 1, 1], "EU"), 4 * 500 + 250)
        self.assertEqual(calc.quote([1], "EU"), 500)
        self.assertEqual(calc.quote([1, 1, 1], "EU"), 1500)

    def test_bulk_fee_is_not_remembered_between_calculators(self):
        RateCalculator().quote([1, 1, 1, 1, 1], "US")
        self.assertEqual(RateCalculator().quote([1], "US"), 900)

    def test_extra_fees_are_added_once_and_not_modified(self):
        fees = [100, 50]
        calc = RateCalculator()
        self.assertEqual(calc.quote([1, 1, 1, 1], "EU", fees=fees), 4 * 500 + 250 + 150)
        self.assertEqual(fees, [100, 50])
        self.assertEqual(calc.quote([1], "EU", fees=fees), 650)

    def test_express_quote_after_standard_quote(self):
        calc = RateCalculator()
        self.assertEqual(calc.quote([3, 3], "EU"), 1800)
        self.assertEqual(calc.quote([3, 3], "EU", express=True), 2700)


if __name__ == "__main__":
    unittest.main()
