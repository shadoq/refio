import unittest

from src.dates import parse_duration


class ParseDurationTest(unittest.TestCase):
    def test_minutes_only(self):
        self.assertEqual(parse_duration("45m"), 45)

    def test_hours_only(self):
        self.assertEqual(parse_duration("2h"), 120)

    def test_rejects_garbage(self):
        with self.assertRaises(ValueError):
            parse_duration("soon")


if __name__ == "__main__":
    unittest.main()
