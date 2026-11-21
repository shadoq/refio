import unittest
from datetime import date

from timelog import format_minutes, group_by_day, longest_day, parse_duration, total_by_project

ENTRIES = [
    {"day": date(2026, 5, 4), "project": "site", "minutes": 90},
    {"day": date(2026, 5, 4), "project": "api", "minutes": 45},
    {"day": date(2026, 5, 5), "project": "site", "minutes": 135},
    {"day": date(2026, 5, 6), "project": "api", "minutes": 30},
]


class ParseDurationTest(unittest.TestCase):
    def test_hours_and_minutes(self):
        self.assertEqual(parse_duration("1h30m"), 90)
        self.assertEqual(parse_duration("2h"), 120)
        self.assertEqual(parse_duration("45m"), 45)

    def test_bare_number_is_minutes(self):
        self.assertEqual(parse_duration("90"), 90)

    def test_spaces_and_capitals(self):
        self.assertEqual(parse_duration(" 1H 15M "), 75)

    def test_decimal_hours(self):
        self.assertEqual(parse_duration("1.5h"), 90)
        self.assertEqual(parse_duration("0.25h"), 15)
        self.assertEqual(parse_duration("2.5h15m"), 165)

    def test_garbage_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_duration("soon")


class ReportingTest(unittest.TestCase):
    def test_format_minutes(self):
        self.assertEqual(format_minutes(90), "1h30m")
        self.assertEqual(format_minutes(65), "1h05m")
        self.assertEqual(format_minutes(120), "2h")
        self.assertEqual(format_minutes(45), "45m")

    def test_group_by_day(self):
        self.assertEqual(
            group_by_day(ENTRIES),
            {date(2026, 5, 4): 135, date(2026, 5, 5): 135, date(2026, 5, 6): 30},
        )

    def test_total_by_project_is_sorted_by_time_then_name(self):
        self.assertEqual(list(total_by_project(ENTRIES).items()), [("site", 225), ("api", 75)])

    def test_longest_day_prefers_the_earliest_on_a_tie(self):
        self.assertEqual(longest_day(ENTRIES), (date(2026, 5, 4), 135))
        self.assertIsNone(longest_day([]))


if __name__ == "__main__":
    unittest.main()
