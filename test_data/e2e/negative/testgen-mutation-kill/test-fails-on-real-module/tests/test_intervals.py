import unittest

import intervals as iv


class Strict(unittest.TestCase):
    def test_merge_touching(self):
        self.assertEqual(iv.merge([(1, 3), (3, 6)]), [(1, 3), (3, 6)])

    def test_contains_end_included(self):
        self.assertTrue(iv.contains([(2, 5)], 5))

    def test_unsorted(self):
        self.assertEqual(iv.merge([(5, 7), (1, 3)]), [(1, 3), (5, 7)])

    def test_nested(self):
        self.assertEqual(iv.merge([(1, 10), (2, 3)]), [(1, 10)])

    def test_gaps(self):
        self.assertEqual(iv.gaps([(0, 4)], 0, 5), [(4, 5)])

    def test_clip_edges(self):
        self.assertEqual(iv.clip([(0, 3), (10, 12)], 3, 10), [])
