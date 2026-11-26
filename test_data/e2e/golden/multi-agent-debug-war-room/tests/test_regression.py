#!/usr/bin/env python3
"""Regression tests for business_days_between (written by the reproducer)."""
import datetime as dt
import unittest

from pagerank.utils import business_days_between


class TestBusinessDaysBetween(unittest.TestCase):
    def test_full_week(self):
        self.assertEqual(business_days_between(dt.date(2024, 1, 8), dt.date(2024, 1, 12)), 5)

    def test_weekend_only(self):
        self.assertEqual(business_days_between(dt.date(2024, 1, 6), dt.date(2024, 1, 7)), 0)

    def test_two_weeks_inclusive(self):
        self.assertEqual(business_days_between(dt.date(2024, 1, 1), dt.date(2024, 1, 14)), 10)

    def test_span_over_weekend(self):
        self.assertEqual(business_days_between(dt.date(2026, 3, 6), dt.date(2026, 3, 9)), 2)

    def test_datetime_bounds_ignored_time(self):
        self.assertEqual(
            business_days_between(dt.datetime(2026, 3, 27, 9, 30), dt.datetime(2026, 3, 30, 18, 0)), 2)

    def test_reversed_bounds(self):
        self.assertEqual(business_days_between(dt.date(2026, 3, 30), dt.date(2026, 3, 27)), 0)

    def test_same_business_day(self):
        self.assertEqual(business_days_between(dt.date(2026, 3, 27), dt.date(2026, 3, 27)), 1)


if __name__ == "__main__":
    unittest.main()
