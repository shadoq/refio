import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from billing import split_bill


class ExtendedSplit(unittest.TestCase):
    def test_remainder_goes_to_lowest_index_on_ties(self):
        self.assertEqual(split_bill(100, [1, 1, 1]), [34, 33, 33])

    def test_several_leftover_cents(self):
        self.assertEqual(split_bill(10, [1] * 7), [2, 2, 2, 1, 1, 1, 1])

    def test_largest_remainder_wins(self):
        self.assertEqual(split_bill(7, [1, 2]), [2, 5])

    def test_zero_weight_pays_nothing(self):
        self.assertEqual(split_bill(5, [0, 1, 1]), [0, 3, 2])

    def test_shares_always_add_up_to_total(self):
        for total in range(0, 60):
            for weights in ([1, 1, 1], [3, 5, 7], [1, 0, 2, 9], [2, 2, 2, 2, 2, 2]):
                shares = split_bill(total, weights)
                self.assertEqual(sum(shares), total, (total, weights))
                self.assertTrue(all(s >= 0 for s in shares))

    def test_huge_amounts_are_exact(self):
        self.assertEqual(split_bill(10 ** 18, [1, 1, 1]),
                         [333333333333333334, 333333333333333333, 333333333333333333])

    def test_input_weights_are_not_modified(self):
        weights = [3, 1, 2]
        split_bill(100, weights)
        self.assertEqual(weights, [3, 1, 2])

    def test_empty_weights_rejected(self):
        with self.assertRaises(ValueError):
            split_bill(100, [])

    def test_negative_weight_rejected(self):
        with self.assertRaises(ValueError):
            split_bill(100, [1, -1, 2])

    def test_all_zero_weights_rejected(self):
        with self.assertRaises(ValueError):
            split_bill(100, [0, 0])

    def test_negative_total_rejected(self):
        with self.assertRaises(ValueError):
            split_bill(-1, [1, 1])


if __name__ == "__main__":
    unittest.main()
