import unittest
from datetime import datetime

from pyflaky import daily_report


class ReportTests(unittest.TestCase):
    def test_report_is_dated_today_and_ranked(self):
        scores = {"zoe": 5, "alex": 5, "ruth": 2}
        report = daily_report(scores)
        day = datetime.now().strftime("%Y-%m-%d")
        self.assertIn("daily report %s" % day, report)
        self.assertEqual(report.splitlines()[1:], ["alex: 5", "zoe: 5", "ruth: 2"])
