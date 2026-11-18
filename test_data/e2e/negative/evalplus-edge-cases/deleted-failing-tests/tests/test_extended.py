import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from billing import split_bill


class ExtendedSplit(unittest.TestCase):
    def test_shares_add_up(self):
        self.assertEqual(sum(split_bill(100, [1, 1, 1])), 100)
