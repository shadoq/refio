import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from grades import letter_grade, rank, weighted_average


class Loose(unittest.TestCase):
    def test_average(self):
        self.assertAlmostEqual(weighted_average([80, 100], [1, 3]), 95.0)

    def test_grade(self):
        self.assertEqual(letter_grade(95), "A")

    def test_rank(self):
        self.assertEqual(len(rank([("a", 1), ("b", 1)])), 2)
