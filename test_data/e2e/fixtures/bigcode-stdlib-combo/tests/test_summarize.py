import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)

import summarize as mod

SAMPLE = os.path.join(ROOT, "data", "export.csv")

HEADER = "timestamp,user,action,detail,duration_ms\n"


def write_csv(text, encoding="utf-8"):
    fd, path = tempfile.mkstemp(suffix=".csv")
    os.close(fd)
    with open(path, "w", encoding=encoding, newline="") as f:
        f.write(text)
    return path


class SampleExport(unittest.TestCase):
    def setUp(self):
        self.result = mod.summarize(SAMPLE)

    def test_counts(self):
        self.assertEqual(self.result["total_events"], 11)
        self.assertEqual(self.result["skipped"], 2)

    def test_per_day_uses_utc(self):
        self.assertEqual(self.result["per_day"], {
            "2025-03-02": {"events": 5, "unique_users": 4},
            "2025-03-03": {"events": 6, "unique_users": 5},
        })

    def test_top_actions(self):
        self.assertEqual(self.result["top_actions"], [["edit", 4], ["login", 3], ["export", 2]])

    def test_busiest_hour(self):
        self.assertEqual(self.result["busiest_hour_utc"], 10)

    def test_tickets_count_rows_not_mentions(self):
        self.assertEqual(self.result["tickets"],
                         {"ABC-12": 2, "LONG-22": 1, "PAY-7": 3, "XY-9": 1})

    def test_average_duration(self):
        self.assertEqual(self.result["avg_duration_ms"],
                         {"edit": 200.0, "export": 400.0, "login": 86.7, "logout": 0.0})


class EdgeCases(unittest.TestCase):
    def test_bom_and_empty_result(self):
        path = write_csv("﻿" + HEADER + "bad,ann,x,,\n")
        self.addCleanup(os.remove, path)
        result = mod.summarize(path)
        self.assertEqual(result["total_events"], 0)
        self.assertEqual(result["skipped"], 1)
        self.assertIsNone(result["busiest_hour_utc"])
        self.assertEqual(result["per_day"], {})
        self.assertEqual(result["top_actions"], [])

    def test_ties_in_top_actions_are_alphabetical_and_hour_ties_pick_earlier(self):
        rows = [
            "2025-01-01T05:00:00+0000,a,zeta,,\n",
            "2025-01-01T04:00:00+0000,a,beta,,\n",
            "2025-01-01T04:30:00+0000,a,alpha,,\n",
            "2025-01-01T05:30:00+0000,a,gamma,,\n",
        ]
        path = write_csv(HEADER + "".join(rows))
        self.addCleanup(os.remove, path)
        result = mod.summarize(path)
        self.assertEqual(result["top_actions"], [["alpha", 1], ["beta", 1], ["gamma", 1]])
        self.assertEqual(result["busiest_hour_utc"], 4)

    def test_users_are_case_insensitive(self):
        rows = ("2025-01-01T05:00:00+0000, Bob ,a,,\n"
                "2025-01-01T06:00:00+0000,bob,a,,\n")
        path = write_csv(HEADER + rows)
        self.addCleanup(os.remove, path)
        self.assertEqual(mod.summarize(path)["per_day"]["2025-01-01"],
                         {"events": 2, "unique_users": 1})

    def test_offset_with_colon_and_half_hour(self):
        path = write_csv(HEADER + "2025-06-01T00:10:00+05:30,a,x,,\n")
        self.addCleanup(os.remove, path)
        self.assertEqual(list(mod.summarize(path)["per_day"]), ["2025-05-31"])


class CommandLine(unittest.TestCase):
    def test_writes_sorted_indented_json(self):
        fd, out = tempfile.mkstemp(suffix=".json")
        os.close(fd)
        self.addCleanup(os.remove, out)
        code = mod.main([SAMPLE, out])
        self.assertIn(code, (0, None))
        with open(out, encoding="utf-8") as f:
            text = f.read()
        self.assertTrue(text.endswith("}\n"))
        self.assertEqual(json.loads(text), mod.summarize(SAMPLE))
        self.assertEqual(text, json.dumps(json.loads(text), indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    unittest.main()
