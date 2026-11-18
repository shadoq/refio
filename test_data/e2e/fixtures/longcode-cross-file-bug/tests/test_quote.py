import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from catalog.tiers import get_tiers, unit_price
from checkout.quote import quote
from promo.loyalty import loyalty_discount_cents
from reports.daily import cheapest_unit_price, revenue_estimate


class PlainQuote(unittest.TestCase):
    def test_tier_prices(self):
        self.assertEqual(unit_price("widget", 1), 1000)
        self.assertEqual(unit_price("widget", 12), 900)
        self.assertEqual(unit_price("gadget", 20), 2000)

    def test_quote_without_promo(self):
        result = quote([("widget", 12), ("gizmo", 3)])
        self.assertEqual(result, {"lines": [10800, 1200], "total": 12000})

    def test_unknown_sku(self):
        with self.assertRaises(KeyError):
            quote([("nope", 1)])


class PromoQuote(unittest.TestCase):
    def test_seasonal_promo_applies_to_each_line(self):
        result = quote([("widget", 12), ("gadget", 5)], promo_percent=10)
        self.assertEqual(result, {"lines": [12 * 810, 5 * 2070], "total": 12 * 810 + 5 * 2070})

    def test_same_promo_quote_is_repeatable(self):
        first = quote([("widget", 12)], promo_percent=10)
        second = quote([("widget", 12)], promo_percent=10)
        self.assertEqual(first, second)
        self.assertEqual(second["total"], 12 * 810)

    def test_quote_without_promo_after_a_promo_quote_is_unaffected(self):
        quote([("widget", 12), ("gizmo", 1)], promo_percent=25)
        self.assertEqual(quote([("widget", 12)])["total"], 10800)
        self.assertEqual(unit_price("gizmo", 1), 400)

    def test_catalog_is_unchanged_by_quoting(self):
        before = [list(t) for t in get_tiers("gadget")]
        quote([("gadget", 30)], promo_percent=50)
        self.assertEqual([list(t) for t in get_tiers("gadget")], before)

    def test_different_promo_percentages_in_a_row(self):
        self.assertEqual(quote([("gizmo", 1)], promo_percent=50)["total"], 200)
        self.assertEqual(quote([("gizmo", 1)], promo_percent=10)["total"], 360)


class OtherModules(unittest.TestCase):
    def test_reports_see_catalog_prices_after_promos(self):
        quote([("widget", 60)], promo_percent=20)
        self.assertEqual(cheapest_unit_price("widget"), 800)
        self.assertEqual(revenue_estimate({"widget": 2, "gizmo": 10}), 2 * 800 + 10 * 350)

    def test_loyalty_discount(self):
        self.assertEqual(loyalty_discount_cents(10000, 3), 300)
        self.assertEqual(loyalty_discount_cents(10000, 15), 1000)


if __name__ == "__main__":
    unittest.main()
