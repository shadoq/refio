"""Acceptance tests for the pure total-cost exercise - do not modify."""
import unittest

from pricing import total_cost


class TestTotalCost(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(total_cost([(10.0, True), (5.0, False)], 0.2), 17.0)
        self.assertEqual(total_cost([], 0.5), 0.0)
        self.assertEqual(total_cost([(2.5, True), (3.0, True)], 0.25), 6.88)

    def test_only_taxable(self):
        self.assertEqual(total_cost([(4.0, True)], 0.5), 6.0)

    def test_only_untaxed(self):
        self.assertEqual(total_cost([(4.0, False)], 0.5), 4.0)

    def test_rounding(self):
        self.assertEqual(total_cost([(1.333, True)], 0.1), 1.47)

    def test_does_not_mutate_input(self):
        src = [(3.0, True), (1.0, False), (2.0, True)]
        snapshot = list(src)
        total_cost(src, 0.3)
        self.assertEqual(src, snapshot)

    def test_pure_across_calls(self):
        src = [(10.0, True)]
        first = total_cost(src, 0.2)
        second = total_cost(src, 0.2)
        self.assertEqual(first, second)
        self.assertEqual(src, [(10.0, True)])


if __name__ == "__main__":
    unittest.main()
