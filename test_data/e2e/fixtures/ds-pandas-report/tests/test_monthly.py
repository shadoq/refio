import os
import unittest

import pandas as pd

from monthly import monthly_report

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "data")


class MonthlyReportTest(unittest.TestCase):
    def test_matches_expected_csv(self):
        events = pd.read_csv(os.path.join(DATA, "events.csv"))
        expected = pd.read_csv(
            os.path.join(HERE, "expected_report.csv"),
            dtype={"month": "object", "store": "object", "orders": "int64"},
        )
        pd.testing.assert_frame_equal(monthly_report(events), expected)

    def test_events_frame_is_not_modified(self):
        events = pd.read_csv(os.path.join(DATA, "events.csv"))
        before = events.copy()
        monthly_report(events)
        pd.testing.assert_frame_equal(events, before)

    def test_midnight_in_warsaw_decides_the_month_across_dst(self):
        events = pd.DataFrame({
            "ts": ["2025-01-31T22:59:59Z", "2025-01-31T23:00:00Z",
                   "2025-03-31T21:59:59Z", "2025-03-31T22:00:00Z"],
            "store": ["S"] * 4,
            "amount": [1.0, 2.0, 4.0, 8.0],
            "qty": [1] * 4,
        })
        report = monthly_report(events)
        self.assertEqual(report["month"].tolist(), ["2025-01", "2025-02", "2025-03", "2025-04"])
        self.assertEqual(report["revenue"].tolist(), [1.0, 2.0, 4.0, 8.0])

    def test_half_up_follows_the_written_decimal(self):
        events = pd.DataFrame({
            "ts": ["2025-05-10T10:00:00Z"] * 3,
            "store": ["S"] * 3,
            "amount": [0.125, 0.125, 2.675],
            "qty": [1] * 3,
        })
        row = monthly_report(events).iloc[0]
        self.assertEqual(row["revenue"], 2.93)
        self.assertEqual(row["avg_ticket"], 0.98)


if __name__ == "__main__":
    unittest.main()
