"""Acceptance tests for the team stats module - do not modify."""
import unittest

from stats import median, variance


class TestMedian(unittest.TestCase):
    def test_odd(self):
        self.assertEqual(median([1, 3, 5]), 3)

    def test_even_averages_middles(self):
        self.assertEqual(median([1, 3, 5, 7]), 4.0)
        self.assertIsInstance(median([1, 3, 5, 7]), float)

    def test_single(self):
        self.assertEqual(median([2]), 2)

    def test_unsorted_input(self):
        self.assertEqual(median([9, 1, 5]), 5)


class TestVariance(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(variance([2, 4]), 1.0)

    def test_constant(self):
        self.assertEqual(variance([5, 5, 5]), 0.0)

    def test_single(self):
        self.assertEqual(variance([7]), 0.0)

    def test_float_result(self):
        self.assertIsInstance(variance([1, 2, 3]), float)


if __name__ == "__main__":
    unittest.main()
