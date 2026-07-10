import unittest

from src.dates import parse_duration


class ParseDurationRegressionTest(unittest.TestCase):
    def test_hours_and_minutes_are_both_counted(self):
        # Reported bug: "1h30m" returned 60, the minutes part was dropped.
        self.assertEqual(parse_duration("1h30m"), 90)


if __name__ == "__main__":
    unittest.main()
