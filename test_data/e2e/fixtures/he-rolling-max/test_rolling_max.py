"""Acceptance tests for the rolling-max exercise - do not modify."""
import unittest

from rolling_max import rolling_max


class TestRollingMax(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(rolling_max([3, 1, 4, 1, 5], 2), [3, 4, 4, 5])
        self.assertEqual(rolling_max([4], 1), [4])
        self.assertEqual(rolling_max([1, 2], 5), [])

    def test_window_equals_length(self):
        self.assertEqual(rolling_max([2, 7, 1], 3), [7])

    def test_k_one_is_identity(self):
        self.assertEqual(rolling_max([5, -2, 9, 0], 1), [5, -2, 9, 0])

    def test_duplicates(self):
        self.assertEqual(rolling_max([4, 4, 4, 4], 2), [4, 4, 4])

    def test_negatives(self):
        self.assertEqual(rolling_max([-5, -1, -7, -3], 2), [-1, -1, -3])

    def test_k_must_be_positive(self):
        with self.assertRaises(ValueError):
            rolling_max([2, 1, 3], 0)
        with self.assertRaises(ValueError):
            rolling_max([2, 1, 3], -2)

    def test_empty_input(self):
        self.assertEqual(rolling_max([], 3), [])


if __name__ == "__main__":
    unittest.main()
