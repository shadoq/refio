import unittest

import intervals as iv


class Merge(unittest.TestCase):
    def test_overlapping(self):
        self.assertEqual(iv.merge([(1, 5), (3, 8)]), [(1, 8)])

    def test_touching_intervals_are_joined(self):
        self.assertEqual(iv.merge([(1, 3), (3, 6)]), [(1, 6)])

    def test_unsorted_input(self):
        self.assertEqual(iv.merge([(5, 7), (1, 3)]), [(1, 3), (5, 7)])

    def test_nested_interval_does_not_shrink_the_outer_one(self):
        self.assertEqual(iv.merge([(1, 10), (2, 3)]), [(1, 10)])

    def test_empty_list(self):
        self.assertEqual(iv.merge([]), [])

    def test_empty_and_reversed_intervals_are_rejected(self):
        with self.assertRaises(ValueError):
            iv.merge([(3, 3)])
        with self.assertRaises(ValueError):
            iv.merge([(4, 2)])


class Length(unittest.TestCase):
    def test_overlaps_count_once(self):
        self.assertEqual(iv.total_length([(0, 10), (5, 12), (20, 21)]), 13)


class Contains(unittest.TestCase):
    def test_start_included_end_excluded(self):
        self.assertTrue(iv.contains([(2, 5)], 2))
        self.assertTrue(iv.contains([(2, 5)], 4))
        self.assertFalse(iv.contains([(2, 5)], 5))
        self.assertFalse(iv.contains([(2, 5)], 1))
        self.assertFalse(iv.contains([], 1))


class Gaps(unittest.TestCase):
    def test_leading_inner_and_trailing_gaps(self):
        self.assertEqual(iv.gaps([(3, 5), (8, 9)], 0, 12), [(0, 3), (5, 8), (9, 12)])

    def test_trailing_gap_of_one(self):
        self.assertEqual(iv.gaps([(0, 4)], 0, 5), [(4, 5)])

    def test_fully_covered(self):
        self.assertEqual(iv.gaps([(0, 10)], 2, 8), [])

    def test_no_intervals(self):
        self.assertEqual(iv.gaps([], 1, 4), [(1, 4)])

    def test_intervals_outside_the_window(self):
        self.assertEqual(iv.gaps([(-5, -1), (20, 30)], 0, 10), [(0, 10)])

    def test_bad_window(self):
        with self.assertRaises(ValueError):
            iv.gaps([], 5, 5)


class Clip(unittest.TestCase):
    def test_cut_to_window(self):
        self.assertEqual(iv.clip([(0, 5), (8, 20)], 3, 10), [(3, 5), (8, 10)])

    def test_intervals_touching_the_window_edges_vanish(self):
        self.assertEqual(iv.clip([(0, 3), (10, 12)], 3, 10), [])

    def test_bad_window(self):
        with self.assertRaises(ValueError):
            iv.clip([(1, 2)], 4, 2)


class Subtract(unittest.TestCase):
    def test_hole_in_the_middle(self):
        self.assertEqual(iv.subtract([(0, 10)], [(3, 5)]), [(0, 3), (5, 10)])

    def test_cut_starting_exactly_at_the_start(self):
        self.assertEqual(iv.subtract([(2, 10)], [(2, 4)]), [(4, 10)])

    def test_cut_ending_exactly_at_the_end(self):
        self.assertEqual(iv.subtract([(2, 10)], [(8, 10)]), [(2, 8)])

    def test_nothing_to_subtract(self):
        self.assertEqual(iv.subtract([(5, 6), (1, 2)], []), [(1, 2), (5, 6)])

    def test_everything_removed(self):
        self.assertEqual(iv.subtract([(2, 4)], [(0, 9)]), [])

    def test_several_cuts_over_several_intervals(self):
        self.assertEqual(iv.subtract([(0, 10), (20, 30)], [(2, 4), (6, 8), (25, 40)]),
                         [(0, 2), (4, 6), (8, 10), (20, 25)])


if __name__ == "__main__":
    unittest.main()
