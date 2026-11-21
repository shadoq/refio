import unittest

from app.inventory import low_stock
from helpers import write_tmp


class InventoryTest(unittest.TestCase):
    def test_low_stock_counts_unreadable_qty_as_zero(self):
        path = write_tmp(self, "stock.csv", "sku,name,qty\nA1, Widget ,5\nB2,Gadget,n/a\nC3,Gizmo,20\nD4,Bolt,\n")
        self.assertEqual(low_stock(path, 10), ["Bolt", "Gadget", "Widget"])

    def test_nothing_low(self):
        path = write_tmp(self, "stock.csv", "sku,name,qty\nA1,Widget,50\n")
        self.assertEqual(low_stock(path, 10), [])


if __name__ == "__main__":
    unittest.main()
