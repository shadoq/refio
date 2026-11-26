import unittest

from pyflaky import merge


class MergeTests(unittest.TestCase):
    def test_merges_scores_from_both_sources(self):
        base = {"ada": 5, "carol": 1, "grace": 4}
        extra = {"ada": 2, "june": 3}
        self.assertEqual(
            merge(base, extra),
            {"ada": 7, "carol": 1, "grace": 4, "june": 3},
        )

    def test_floor_zero_keeps_every_entry(self):
        self.assertEqual(merge({"tiny": 1}, {}), {"tiny": 1})
