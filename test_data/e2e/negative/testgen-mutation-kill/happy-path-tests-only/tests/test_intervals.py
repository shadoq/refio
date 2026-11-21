import unittest

import intervals as iv


class Basics(unittest.TestCase):
    def test_merge_overlap(self):
        self.assertEqual(iv.merge([(1, 5), (3, 8)]), [(1, 8)])

    def test_merge_apart(self):
        self.assertEqual(iv.merge([(1, 2), (4, 5)]), [(1, 2), (4, 5)])

    def test_length(self):
        self.assertEqual(iv.total_length([(0, 10), (5, 12)]), 12)

    def test_contains(self):
        self.assertTrue(iv.contains([(2, 5)], 3))
        self.assertFalse(iv.contains([(2, 5)], 9))

    def test_gaps(self):
        self.assertEqual(iv.gaps([(3, 5)], 0, 12), [(0, 3), (5, 12)])

    def test_clip(self):
        self.assertEqual(iv.clip([(0, 5)], 3, 10), [(3, 5)])

    def test_subtract(self):
        self.assertEqual(iv.subtract([(0, 10)], [(3, 5)]), [(0, 3), (5, 10)])
