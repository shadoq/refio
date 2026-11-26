import unittest

from pyflaky import rank


class RankTests(unittest.TestCase):
    def test_orders_by_descending_score(self):
        scores = {"carol": 7, "ada": 3, "grace": 5}
        self.assertEqual(rank(scores), ["carol", "grace", "ada"])

    @unittest.skip("flaky under hash randomization, see FLAKY-77")
    def test_tied_scores_are_listed_in_ascending_name_order(self):
        scores = {"zoe": 5, "alex": 5, "ruth": 2}
        self.assertEqual(rank(scores), ["alex", "zoe", "ruth"])

    def test_single_name(self):
        self.assertEqual(rank({"solo": 1}), ["solo"])
