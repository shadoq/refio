import unittest
from decimal import Decimal

from shipcalc import billable_weight, quote, rate_for


class RateTests(unittest.TestCase):
    def test_tier_bounds_are_inclusive(self):
        self.assertEqual(rate_for(Decimal("0.5")), Decimal("4.00"))
        self.assertEqual(rate_for(Decimal("1")), Decimal("4.00"))
        self.assertEqual(rate_for(Decimal("1.01")), Decimal("3.20"))
        self.assertEqual(rate_for(Decimal("5")), Decimal("3.20"))
        self.assertEqual(rate_for(Decimal("20")), Decimal("2.50"))
        self.assertEqual(rate_for(Decimal("20.5")), Decimal("2.00"))


class BillableWeightTests(unittest.TestCase):
    def test_small_dense_parcel_uses_actual_weight(self):
        self.assertEqual(billable_weight(2, 10, 10, 10), 2)

    def test_bulky_light_parcel_uses_volumetric_weight(self):
        self.assertEqual(billable_weight(1, 50, 40, 30), 12)


class QuoteTests(unittest.TestCase):
    def test_dense_parcel(self):
        self.assertEqual(quote(2, 10, 10, 10), Decimal("6.40"))

    def test_bulky_parcel(self):
        self.assertEqual(quote(1, 50, 40, 30), Decimal("30.00"))

    def test_tier_edge_and_rounding(self):
        self.assertEqual(quote(5, 10, 10, 10), Decimal("16.00"))
        self.assertEqual(quote(0.333, 1, 1, 1), Decimal("1.33"))


if __name__ == "__main__":
    unittest.main()
