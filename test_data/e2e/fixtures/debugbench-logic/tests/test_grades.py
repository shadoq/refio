import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from grades import letter_grade, rank, weighted_average


class WeightedAverage(unittest.TestCase):
    def test_weights_change_the_result(self):
        self.assertAlmostEqual(weighted_average([80, 100], [1, 3]), 95.0)

    def test_equal_weights_is_plain_mean(self):
        self.assertAlmostEqual(weighted_average([70, 90, 80], [1, 1, 1]), 80.0)

    def test_invalid_input(self):
        for args in (([], []), ([1, 2], [1]), ([1], [0])):
            with self.assertRaises(ValueError):
                weighted_average(*args)


class LetterGrade(unittest.TestCase):
    def test_boundaries_belong_to_the_higher_grade(self):
        self.assertEqual(letter_grade(90), "A")
        self.assertEqual(letter_grade(80), "B")
        self.assertEqual(letter_grade(70), "C")
        self.assertEqual(letter_grade(60), "D")

    def test_just_below_boundaries(self):
        self.assertEqual(letter_grade(89.99), "B")
        self.assertEqual(letter_grade(79.99), "C")
        self.assertEqual(letter_grade(69.99), "D")
        self.assertEqual(letter_grade(59.99), "F")

    def test_extremes(self):
        self.assertEqual(letter_grade(100), "A")
        self.assertEqual(letter_grade(0), "F")


class Rank(unittest.TestCase):
    def test_distinct_scores(self):
        self.assertEqual(rank([("bo", 70), ("al", 90), ("cy", 80)]),
                         [(1, "al", 90), (2, "cy", 80), (3, "bo", 70)])

    def test_ties_share_a_rank_and_skip_places(self):
        self.assertEqual(rank([("d", 70), ("b", 80), ("a", 90), ("c", 80)]),
                         [(1, "a", 90), (2, "b", 80), (2, "c", 80), (4, "d", 70)])

    def test_all_tied(self):
        self.assertEqual(rank([("y", 5), ("x", 5)]), [(1, "x", 5), (1, "y", 5)])

    def test_empty(self):
        self.assertEqual(rank([]), [])


if __name__ == "__main__":
    unittest.main()
