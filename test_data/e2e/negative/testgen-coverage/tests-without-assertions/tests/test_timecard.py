import unittest

import timecard


class Smoke(unittest.TestCase):
    def test_parse_time(self):
        timecard.parse_time("9:05")

    def test_parse_shift(self):
        timecard.parse_shift("09:00-17:30")
        timecard.parse_shift("22:00-06:00")

    def test_breaks(self):
        for n in (100, 400, 600):
            timecard.break_minutes(n)

    def test_paid(self):
        timecard.paid_minutes("09:00-17:00")
        timecard.paid_minutes("09:00-17:00", unpaid_break=False)

    def test_week(self):
        timecard.week_summary({"mon": ["09:00-17:00"], "tue": []})

    def test_format(self):
        timecard.format_hours(450)
        timecard.format_hours(420)
        timecard.format_hours(45)

    def test_format_two(self):
        timecard.format_hours(0)

    def test_parse_time_again(self):
        timecard.parse_time("10:00")
