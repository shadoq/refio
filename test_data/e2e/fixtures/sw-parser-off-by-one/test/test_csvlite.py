import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from csvlite import pad_row, parse, split_row


class SplitRowTest(unittest.TestCase):
    def test_plain_row(self):
        self.assertEqual(split_row("a,b,c"), ["a", "b", "c"])

    def test_quoted_comma(self):
        self.assertEqual(split_row('"x,y",z'), ["x,y", "z"])

    def test_doubled_quote_escape(self):
        self.assertEqual(split_row('"a""b",c'), ['a"b', 'c'])

    def test_inner_empty_field(self):
        self.assertEqual(split_row("a,,c"), ["a", "", "c"])

    def test_custom_separator(self):
        self.assertEqual(split_row("a;b;c", ";"), ["a", "b", "c"])


class ParseTest(unittest.TestCase):
    def test_parse_multi_line(self):
        self.assertEqual(parse("a,b\nc,d"), [["a", "b"], ["c", "d"]])

    def test_parse_skips_blank_lines(self):
        self.assertEqual(parse("a,b\n\nc,d"), [["a", "b"], ["c", "d"]])


class PadRowTest(unittest.TestCase):
    def test_pads_short_row(self):
        self.assertEqual(pad_row(["a"], 3), ["a", "", ""])

    def test_row_of_full_width_unchanged(self):
        self.assertEqual(pad_row(["a", "b"], 2), ["a", "b"])


if __name__ == "__main__":
    unittest.main()
