import unittest

from app.importer import load_items
from helpers import write_tmp


class ImporterTest(unittest.TestCase):
    def test_loads_trimmed_items_and_zeroes_bad_qty(self):
        path = write_tmp(self, "items.csv", "sku, name ,qty\nA1, Widget , 5 \nB2,Gadget,n/a\n\nC3, Gizmo,12\n")
        self.assertEqual(load_items(path), [
            {"sku": "A1", "name": "Widget", "qty": 5},
            {"sku": "B2", "name": "Gadget", "qty": 0},
            {"sku": "C3", "name": "Gizmo", "qty": 12},
        ])


if __name__ == "__main__":
    unittest.main()
