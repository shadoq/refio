#!/usr/bin/env python3
"""Existing regression tests for pagerank.utils (do not modify)."""
import datetime as dt
import unittest

from pagerank.utils import month_name


class TestMonthName(unittest.TestCase):
    def test_january(self):
        self.assertEqual(month_name(1), "January")

    def test_december(self):
        self.assertEqual(month_name(12), "December")


if __name__ == "__main__":
    unittest.main()
