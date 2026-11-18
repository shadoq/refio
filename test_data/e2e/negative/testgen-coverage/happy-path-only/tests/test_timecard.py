import unittest

import timecard


class HappyPath(unittest.TestCase):
    def test_parse_time(self):
        self.assertEqual(timecard.parse_time("9:05"), 545)

    def test_parse_shift(self):
        self.assertEqual(timecard.parse_shift("09:00-17:30"), (540, 1050))

    def test_overnight(self):
        self.assertEqual(timecard.parse_shift("22:00-06:00"), (1320, 1800))

    def test_break(self):
        self.assertEqual(timecard.break_minutes(400), 30)

    def test_paid(self):
        self.assertEqual(timecard.paid_minutes("09:00-17:00"), 450)

    def test_paid_no_break(self):
        self.assertEqual(timecard.paid_minutes("09:00-17:00", unpaid_break=False), 480)

    def test_week(self):
        summary = timecard.week_summary({"mon": ["09:00-17:00"], "tue": []})
        self.assertEqual(summary["days_worked"], 1)

    def test_format(self):
        self.assertEqual(timecard.format_hours(450), "7h 30m")
