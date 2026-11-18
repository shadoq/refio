import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from inventory import Inventory


def stocked():
    inv = Inventory()
    inv.add_item("Bolt", 12, 35)
    inv.add_item("nut", 3, 10)
    inv.add_item("Washer", 40, 5)
    return inv


class AddItem(unittest.TestCase):
    def test_names_are_normalised_and_quantities_add_up(self):
        inv = Inventory()
        self.assertEqual(inv.add_item("  Bolt ", 5, 30), 5)
        self.assertEqual(inv.add_item("bolt", 2, 35), 7)
        self.assertEqual(inv.get_quantity("BOLT"), 7)

    def test_price_is_replaced_by_the_latest(self):
        inv = Inventory()
        inv.add_item("bolt", 2, 30)
        inv.add_item("bolt", 2, 50)
        self.assertEqual(inv.total_value_cents(), 4 * 50)

    def test_invalid_arguments(self):
        inv = Inventory()
        for args in (("bolt", 0, 10), ("bolt", -1, 10), ("bolt", 1, -1), ("  ", 1, 10)):
            with self.assertRaises(ValueError):
                inv.add_item(*args)
        self.assertEqual(inv.get_quantity("bolt"), 0)


class RemoveItem(unittest.TestCase):
    def test_returns_what_is_left(self):
        inv = stocked()
        self.assertEqual(inv.remove_item("bolt", 5), 7)

    def test_item_disappears_at_zero(self):
        inv = stocked()
        self.assertEqual(inv.remove_item("nut", 3), 0)
        self.assertEqual(inv.search(""), ["bolt", "washer"])
        self.assertNotIn("nut", inv.low_stock())
        self.assertEqual(inv.total_value_cents(), 12 * 35 + 40 * 5)
        with self.assertRaises(KeyError):
            inv.remove_item("nut", 1)

    def test_too_many_changes_nothing(self):
        inv = stocked()
        with self.assertRaises(ValueError):
            inv.remove_item("nut", 4)
        self.assertEqual(inv.get_quantity("nut"), 3)

    def test_unknown_item_and_bad_quantity(self):
        inv = stocked()
        with self.assertRaises(KeyError):
            inv.remove_item("gear", 1)
        with self.assertRaises(ValueError):
            inv.remove_item("bolt", 0)


class Queries(unittest.TestCase):
    def test_get_quantity_unknown_is_zero(self):
        self.assertEqual(Inventory().get_quantity("x"), 0)

    def test_total_value(self):
        self.assertEqual(stocked().total_value_cents(), 12 * 35 + 3 * 10 + 40 * 5)
        self.assertEqual(Inventory().total_value_cents(), 0)

    def test_low_stock_is_strictly_below_threshold_and_sorted(self):
        inv = Inventory(low_stock_threshold=5)
        inv.add_item("zip", 4, 1)
        inv.add_item("axe", 5, 1)
        inv.add_item("cup", 1, 1)
        self.assertEqual(inv.low_stock(), ["cup", "zip"])

    def test_custom_threshold(self):
        inv = Inventory(low_stock_threshold=13)
        inv.add_item("bolt", 12, 35)
        self.assertEqual(inv.low_stock(), ["bolt"])

    def test_search_is_prefix_and_case_insensitive(self):
        inv = stocked()
        inv.add_item("bolt cap", 1, 1)
        self.assertEqual(inv.search("BOL"), ["bolt", "bolt cap"])
        self.assertEqual(inv.search("olt"), [])
        self.assertEqual(inv.search(""), ["bolt", "bolt cap", "nut", "washer"])

    def test_report_format_and_order(self):
        inv = stocked()
        inv.add_item("axe", 1, 1999)
        inv.add_item("pin", 7, 100)
        self.assertEqual(inv.report(),
                         ["axe: 1 x 19.99", "bolt: 12 x 0.35", "nut: 3 x 0.10",
                          "pin: 7 x 1.00", "washer: 40 x 0.05"])

    def test_report_of_empty_inventory(self):
        self.assertEqual(Inventory().report(), [])


class Restock(unittest.TestCase):
    def test_tops_up_only_low_items(self):
        inv = stocked()
        inv.add_item("cup", 1, 200)
        self.assertEqual(inv.restock(10), {"cup": 9, "nut": 7})
        self.assertEqual(inv.get_quantity("nut"), 10)
        self.assertEqual(inv.get_quantity("bolt"), 12)
        self.assertEqual(inv.low_stock(), [])

    def test_nothing_to_do(self):
        inv = Inventory()
        inv.add_item("bolt", 50, 1)
        self.assertEqual(inv.restock(10), {})

    def test_target_not_above_threshold_is_rejected_without_changes(self):
        inv = stocked()
        with self.assertRaises(ValueError):
            inv.restock(5)
        self.assertEqual(inv.get_quantity("nut"), 3)


if __name__ == "__main__":
    unittest.main()
