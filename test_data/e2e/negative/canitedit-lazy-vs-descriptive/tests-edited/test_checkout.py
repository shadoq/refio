import unittest

from checkout import checkout_total
from models import Coupon, Item


def pct(value):
    return Coupon("P%d" % value, "percent", value)


def fixed(cents):
    return Coupon("F%d" % cents, "fixed", cents)


class CheckoutTotalTest(unittest.TestCase):
    def test_plain_cart_with_tax(self):
        items = [Item("a", 1000, 2), Item("b", 250)]
        self.assertEqual(checkout_total(items, [], 10), 2475)

    def test_percent_discount_rounds_half_up(self):
        # 10% of 1005 is 100.5 cents, which rounds up to 101
        self.assertEqual(checkout_total([Item("a", 1005)], [pct(10)], 0), 904)

    def test_tax_rounds_half_up(self):
        # 10% of 125 is 12.5 cents, which rounds up to 13
        self.assertEqual(checkout_total([Item("a", 125)], [], 10), 138)

    def test_percent_coupons_apply_before_fixed_whatever_the_order(self):
        items = [Item("a", 2000)]
        self.assertEqual(checkout_total(items, [fixed(500), pct(10)], 0), 1350)
        self.assertEqual(checkout_total(items, [pct(10), fixed(500)], 0), 1300)

    def test_two_percent_coupons_compound(self):
        self.assertEqual(checkout_total([Item("a", 1000)], [pct(10), pct(10)], 0), 810)

    def test_total_never_goes_below_zero(self):
        self.assertEqual(checkout_total([Item("a", 300)], [fixed(1000)], 20), 0)

    def test_tax_is_charged_on_the_discounted_amount(self):
        self.assertEqual(checkout_total([Item("a", 2000)], [pct(50)], 20), 1200)

    def test_the_callers_coupon_list_is_left_alone(self):
        coupons = [fixed(100), pct(10)]
        checkout_total([Item("a", 1000)], coupons, 0)
        self.assertEqual([c.code for c in coupons], ["F100", "P10"])


if __name__ == "__main__":
    unittest.main()
