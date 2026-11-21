import unittest
from datetime import datetime

from scheduler.digest import next_digest, parse_time
from scheduler.zones import to_local, warsaw_offset_hours

EVERY_DAY = {"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"], "at": "09:00"}


class DigestTest(unittest.TestCase):
    def test_parse_time(self):
        self.assertEqual(parse_time("07:05"), (7, 5))

    def test_offset_switches_on_the_last_sunday_of_march(self):
        self.assertEqual(warsaw_offset_hours(datetime(2026, 3, 29, 0, 59)), 1)
        self.assertEqual(warsaw_offset_hours(datetime(2026, 3, 29, 1, 0)), 2)
        self.assertEqual(to_local(datetime(2026, 3, 29, 1, 0)), datetime(2026, 3, 29, 3, 0))

    def test_daily_digest_keeps_local_time_across_the_spring_changeover(self):
        self.assertEqual(next_digest(datetime(2026, 3, 28, 12, 0), EVERY_DAY), datetime(2026, 3, 29, 7, 0))
        self.assertEqual(next_digest(datetime(2026, 3, 29, 12, 0), EVERY_DAY), datetime(2026, 3, 30, 7, 0))

    def test_next_digest_is_strictly_after_the_given_moment(self):
        self.assertEqual(next_digest(datetime(2026, 1, 12, 8, 0), EVERY_DAY), datetime(2026, 1, 13, 8, 0))

    def test_monday_digest_after_a_friday_runs_the_following_monday(self):
        config = {"days": ["mon"], "at": "09:00"}
        # Friday 27 March 2026, the weekend of the clock change follows
        self.assertEqual(next_digest(datetime(2026, 3, 27, 12, 0), config), datetime(2026, 3, 30, 7, 0))


if __name__ == "__main__":
    unittest.main()
