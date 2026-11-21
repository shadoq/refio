import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from billing import split_bill


class BasicSplit(unittest.TestCase):
    def test_even_split(self):
        self.assertEqual(split_bill(100, [1, 1]), [50, 50])

    def test_proportional_split(self):
        self.assertEqual(split_bill(90, [1, 2]), [30, 60])

    def test_zero_total(self):
        self.assertEqual(split_bill(0, [1, 1]), [0, 0])

    def test_single_person_pays_everything(self):
        self.assertEqual(split_bill(1234, [7]), [1234])


if __name__ == "__main__":
    unittest.main()
