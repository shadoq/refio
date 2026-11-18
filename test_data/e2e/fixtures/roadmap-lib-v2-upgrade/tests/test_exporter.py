import unittest

from app.exporter import export_tsv
from helpers import out_path, read_tmp


class ExporterTest(unittest.TestCase):
    def test_extra_keys_are_dropped_and_missing_keys_are_empty(self):
        path = out_path(self, "out.tsv")
        export_tsv([
            {"sku": "A1", "name": "Widget", "qty": 5, "note": "fragile"},
            {"sku": "B2", "name": "Gadget"},
        ], path)
        self.assertEqual(read_tmp(path), "sku\tname\tqty\nA1\tWidget\t5\nB2\tGadget\t\n")


if __name__ == "__main__":
    unittest.main()
