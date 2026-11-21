import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from tinylib import table


class Render(unittest.TestCase):
    def test_example_from_the_docs(self):
        out = table.render([["pen", 3], ["notebook", 12]], headers=["item", "qty"], align="lr")
        self.assertEqual(out, "item     | qty\n---------+----\npen      |   3\nnotebook |  12")

    def test_no_headers_no_rule(self):
        self.assertEqual(table.render([["a", "bb"], ["ccc", "d"]]), "a   | bb\nccc | d")

    def test_trailing_spaces_removed(self):
        out = table.render([["x", ""], ["yyyy", "z"]])
        self.assertEqual(out.split("\n"), ["x    |", "yyyy | z"])

    def test_center_puts_the_extra_space_right(self):
        out = table.render([["ab"], ["abcde"]], align="c")
        self.assertEqual(out.split("\n"), [" ab", "abcde"])

    def test_center_even(self):
        out = table.render([["ab"], ["abcd"]], align="c")
        self.assertEqual(out.split("\n"), [" ab", "abcd"])

    def test_none_and_non_strings(self):
        out = table.render([[None, 1.5, True]], headers=["a", "b", "c"])
        self.assertEqual(out, "a | b   | c\n--+-----+-----\n  | 1.5 | True")

    def test_ragged_rows_and_headers(self):
        out = table.render([["a"], ["b", "c", "d"]], headers=["h1", "h2"])
        self.assertEqual(out.split("\n"), [
            "h1 | h2 |",
            "---+----+--",
            "a  |    |",
            "b  | c  | d",
        ])

    def test_only_headers(self):
        self.assertEqual(table.render([], headers=["ab", "c"]), "ab | c\n---+--")

    def test_empty(self):
        self.assertEqual(table.render([]), "")
        self.assertEqual(table.render([], headers=None, align="lr"), "")

    def test_missing_align_letters_default_left(self):
        out = table.render([["a", "b"], ["ccc", "dd"]], align="r")
        self.assertEqual(out.split("\n"), ["  a | b", "ccc | dd"])

    def test_bad_align(self):
        with self.assertRaises(ValueError):
            table.render([["a"]], align="x")

    def test_header_wider_than_cells_and_alignment_of_headers(self):
        out = table.render([["1"]], headers=["total"], align="r")
        self.assertEqual(out, "total\n-----\n    1")


if __name__ == "__main__":
    unittest.main()
