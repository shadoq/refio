"""Acceptance tests for the nested-parens exercise - do not modify."""
import unittest

from parse_parens import parse_nested_parens


class TestParseNestedParens(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(parse_nested_parens("(()()) ((())) () ((())()())"), [2, 3, 1, 3])
        self.assertEqual(parse_nested_parens("()"), [1])
        self.assertEqual(parse_nested_parens(""), [])

    def test_deep_nesting(self):
        self.assertEqual(parse_nested_parens("(((())))"), [4])

    def test_multiple_groups(self):
        self.assertEqual(parse_nested_parens("(()) () (())"), [2, 1, 2])

    def test_depth_decreases_within_group(self):
        self.assertEqual(parse_nested_parens("(()())"), [2])

    def test_adjacent_pairs_are_one_group(self):
        self.assertEqual(parse_nested_parens("()(())"), [2])


if __name__ == "__main__":
    unittest.main()
