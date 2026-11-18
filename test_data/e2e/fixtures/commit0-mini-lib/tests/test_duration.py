import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from tinylib import duration


class Parse(unittest.TestCase):
    def test_simple_and_combined(self):
        self.assertEqual(duration.parse("90s"), 90)
        self.assertEqual(duration.parse("1h30m"), 5400)
        self.assertEqual(duration.parse("2d 4h"), 2 * 86400 + 4 * 3600)
        self.assertEqual(duration.parse("1w"), 7 * 86400)

    def test_case_and_whitespace(self):
        self.assertEqual(duration.parse(" 1H 30 M "), 5400)
        self.assertEqual(duration.parse("90S"), 90)

    def test_any_order(self):
        self.assertEqual(duration.parse("30m1h"), 5400)

    def test_decimals(self):
        self.assertEqual(duration.parse("1.5h"), 5400)
        self.assertEqual(duration.parse("0.25m"), 15)
        self.assertEqual(duration.parse("1.5m30s"), 120)

    def test_zero(self):
        self.assertEqual(duration.parse("0s"), 0)

    def test_invalid(self):
        for bad in ("", "  ", "90", "1x", "1h1h", "h", "1h30", "abc", "1h,30m", "-5s", "0.5s", "1..5h"):
            with self.assertRaises(ValueError, msg=bad):
                duration.parse(bad)


class Format(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(duration.format(93784), "1d2h3m4s")
        self.assertEqual(duration.format(3600), "1h")
        self.assertEqual(duration.format(61), "1m1s")
        self.assertEqual(duration.format(0), "0s")

    def test_weeks_are_not_used(self):
        self.assertEqual(duration.format(8 * 86400), "8d")

    def test_max_parts_cuts_off(self):
        self.assertEqual(duration.format(93784, 2), "1d2h")
        self.assertEqual(duration.format(93784, 1), "1d")
        self.assertEqual(duration.format(86400 + 4, 2), "1d4s")
        self.assertEqual(duration.format(5, 3), "5s")

    def test_invalid(self):
        for args in ((-1,), (1.5,), (True,), ("5",), (10, 0)):
            with self.assertRaises(ValueError, msg=args):
                duration.format(*args)

    def test_round_trip(self):
        for secs in (0, 1, 59, 60, 3599, 3600, 86399, 86400, 1234567):
            self.assertEqual(duration.parse(duration.format(secs)), secs)


if __name__ == "__main__":
    unittest.main()
