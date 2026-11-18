import unittest

import timecard
from timecard import TimecardError


class ParseTime(unittest.TestCase):
    def test_plain_and_padded(self):
        self.assertEqual(timecard.parse_time("9:05"), 545)
        self.assertEqual(timecard.parse_time(" 09:05 "), 545)

    def test_bad_format(self):
        for bad in ("905", "9:5:1", "ab:cd", "9:-5", ""):
            with self.assertRaises(TimecardError):
                timecard.parse_time(bad)

    def test_out_of_range(self):
        with self.assertRaises(TimecardError):
            timecard.parse_time("24:00")
        with self.assertRaises(TimecardError):
            timecard.parse_time("12:60")


class ParseShift(unittest.TestCase):
    def test_day_shift(self):
        self.assertEqual(timecard.parse_shift("09:00-17:30"), (540, 1050))

    def test_overnight(self):
        self.assertEqual(timecard.parse_shift("22:00-06:00"), (1320, 1800))

    def test_empty_and_malformed(self):
        with self.assertRaises(TimecardError):
            timecard.parse_shift("09:00-09:00")
        with self.assertRaises(TimecardError):
            timecard.parse_shift("09:00")
        with self.assertRaises(TimecardError):
            timecard.parse_shift("09:00-10:00-11:00")


class Breaks(unittest.TestCase):
    def test_break_thresholds(self):
        self.assertEqual(timecard.break_minutes(359), 0)
        self.assertEqual(timecard.break_minutes(360), 30)
        self.assertEqual(timecard.break_minutes(539), 30)
        self.assertEqual(timecard.break_minutes(540), 60)

    def test_paid_minutes_with_and_without_break(self):
        self.assertEqual(timecard.paid_minutes("09:00-17:00"), 450)
        self.assertEqual(timecard.paid_minutes("09:00-17:00", unpaid_break=False), 480)
        self.assertEqual(timecard.paid_minutes("09:00-12:00"), 180)
        self.assertEqual(timecard.paid_minutes("08:00-18:00"), 540)


class WeekSummary(unittest.TestCase):
    def test_regular_week(self):
        days = {"mon": ["09:00-17:00"], "tue": ["09:00-17:00"], "wed": [], "thu": ["10:00-14:00", "15:00-19:00"]}
        summary = timecard.week_summary(days)
        self.assertEqual(summary["total_minutes"], 450 + 450 + 240 + 240)
        self.assertEqual(summary["days_worked"], 3)
        self.assertEqual(summary["longest_day_minutes"], 480)
        self.assertEqual(summary["overtime_minutes"], 0)

    def test_overtime(self):
        days = {d: ["08:00-18:00"] for d in ("mon", "tue", "wed", "thu", "fri")}
        summary = timecard.week_summary(days)
        self.assertEqual(summary["total_minutes"], 5 * 540)
        self.assertEqual(summary["overtime_minutes"], 5 * 540 - 2400)

    def test_empty_week_is_rejected(self):
        with self.assertRaises(TimecardError):
            timecard.week_summary({})

    def test_too_long_day_is_rejected(self):
        with self.assertRaises(TimecardError):
            timecard.week_summary({"sat": ["00:00-12:00", "12:00-23:59", "23:59-23:00"]})


class FormatHours(unittest.TestCase):
    def test_variants(self):
        self.assertEqual(timecard.format_hours(450), "7h 30m")
        self.assertEqual(timecard.format_hours(420), "7h")
        self.assertEqual(timecard.format_hours(45), "45m")
        self.assertEqual(timecard.format_hours(0), "0h")

    def test_negative(self):
        with self.assertRaises(TimecardError):
            timecard.format_hours(-1)


if __name__ == "__main__":
    unittest.main()
