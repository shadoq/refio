import unittest

from app.reports import write_summary
from helpers import out_path, read_tmp


class ReportsTest(unittest.TestCase):
    def test_summary_is_sorted_with_two_decimals(self):
        path = out_path(self, "summary.csv")
        write_summary(path, {"zed": 3, "acme": 15.5})
        self.assertEqual(read_tmp(path), "customer,total\nacme,15.50\nzed,3.00\n")


if __name__ == "__main__":
    unittest.main()
