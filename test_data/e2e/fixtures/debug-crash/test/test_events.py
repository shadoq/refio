import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))

from events import record_event, take_recent


class RecordEventTest(unittest.TestCase):
    def test_appends_to_explicit_log(self):
        log = ["signup"]
        result = record_event("login", log)
        self.assertEqual(result, ["signup", "login"])
        self.assertIs(result, log)

    def test_returns_log(self):
        self.assertEqual(record_event("login", ["signup"]), ["signup", "login"])


class TakeRecentTest(unittest.TestCase):
    def test_takes_most_recent(self):
        events = ["a", "b", "c"]
        self.assertEqual(take_recent(events, 2), ["b", "c"])
        self.assertEqual(events, ["a"])

    def test_takes_all_when_n_equals_len(self):
        events = ["a", "b"]
        self.assertEqual(take_recent(events, 2), ["a", "b"])
        self.assertEqual(events, [])


if __name__ == "__main__":
    unittest.main()
