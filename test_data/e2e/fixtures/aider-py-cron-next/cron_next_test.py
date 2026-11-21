import unittest
from datetime import datetime

from cron_next import next_times


def dt(y, mo, d, h=0, mi=0):
    return datetime(y, mo, d, h, mi)


class CronNextTest(unittest.TestCase):
    def test_every_minute_is_strictly_after_start_and_has_zero_seconds(self):
        out = next_times("* * * * *", datetime(2024, 1, 1, 0, 0, 30, 5), 3)
        self.assertEqual(out, [dt(2024, 1, 1, 0, 1), dt(2024, 1, 1, 0, 2), dt(2024, 1, 1, 0, 3)])

    def test_a_start_that_is_exactly_a_fire_time_is_not_returned(self):
        self.assertEqual(
            next_times("0 10 * * *", dt(2024, 3, 5, 10, 0), 2),
            [dt(2024, 3, 6, 10, 0), dt(2024, 3, 7, 10, 0)],
        )

    def test_seconds_of_start_push_the_same_minute_out(self):
        self.assertEqual(
            next_times("0 10 * * *", datetime(2024, 3, 5, 10, 0, 1), 1), [dt(2024, 3, 6, 10, 0)]
        )
        self.assertEqual(
            next_times("0 10 * * *", datetime(2024, 3, 5, 9, 59, 59), 1), [dt(2024, 3, 5, 10, 0)]
        )

    def test_n_zero_returns_nothing(self):
        self.assertEqual(next_times("* * * * *", dt(2024, 1, 1), 0), [])

    def test_star_step(self):
        self.assertEqual(
            next_times("*/15 * * * *", dt(2024, 1, 1), 5),
            [dt(2024, 1, 1, 0, 15), dt(2024, 1, 1, 0, 30), dt(2024, 1, 1, 0, 45),
             dt(2024, 1, 1, 1, 0), dt(2024, 1, 1, 1, 15)],
        )

    def test_single_value_with_step_runs_to_the_end_of_the_field(self):
        self.assertEqual(
            next_times("5/20 * * * *", dt(2024, 1, 1), 4),
            [dt(2024, 1, 1, 0, 5), dt(2024, 1, 1, 0, 25), dt(2024, 1, 1, 0, 45), dt(2024, 1, 1, 1, 5)],
        )

    def test_range_with_step_counts_from_the_range_start(self):
        self.assertEqual(
            next_times("10-30/10 * * * *", dt(2024, 1, 1), 4),
            [dt(2024, 1, 1, 0, 10), dt(2024, 1, 1, 0, 20), dt(2024, 1, 1, 0, 30), dt(2024, 1, 1, 1, 10)],
        )

    def test_lists_and_ranges_combine(self):
        self.assertEqual(
            next_times("0,30 9-11 * * *", dt(2024, 1, 1), 7),
            [dt(2024, 1, 1, 9, 0), dt(2024, 1, 1, 9, 30), dt(2024, 1, 1, 10, 0), dt(2024, 1, 1, 10, 30),
             dt(2024, 1, 1, 11, 0), dt(2024, 1, 1, 11, 30), dt(2024, 1, 2, 9, 0)],
        )

    def test_step_in_hours_with_a_minute_list_from_mid_hour(self):
        self.assertEqual(
            next_times("10,40 */6 * * *", dt(2024, 1, 1, 6, 20), 3),
            [dt(2024, 1, 1, 6, 40), dt(2024, 1, 1, 12, 10), dt(2024, 1, 1, 12, 40)],
        )

    def test_month_names_in_a_list(self):
        self.assertEqual(
            next_times("0 0 1 JAN,Jul *", dt(2024, 1, 1), 3),
            [dt(2024, 7, 1), dt(2025, 1, 1), dt(2025, 7, 1)],
        )

    def test_month_name_range_with_step(self):
        self.assertEqual(
            next_times("0 0 1 jan-dec/6 *", dt(2024, 1, 1), 3),
            [dt(2024, 7, 1), dt(2025, 1, 1), dt(2025, 7, 1)],
        )

    def test_weekday_name_range(self):
        self.assertEqual(
            next_times("30 8 * * MON-FRI", dt(2024, 1, 5, 9, 0), 3),
            [dt(2024, 1, 8, 8, 30), dt(2024, 1, 9, 8, 30), dt(2024, 1, 10, 8, 30)],
        )

    def test_weekday_name_list(self):
        self.assertEqual(
            next_times("0 12 * * sat,sun", dt(2024, 1, 1), 3),
            [dt(2024, 1, 6, 12), dt(2024, 1, 7, 12), dt(2024, 1, 13, 12)],
        )

    def test_sunday_is_both_0_and_7(self):
        expected = [dt(2024, 1, 7), dt(2024, 1, 14)]
        self.assertEqual(next_times("0 0 * * 0", dt(2024, 1, 1), 2), expected)
        self.assertEqual(next_times("0 0 * * 7", dt(2024, 1, 1), 2), expected)

    def test_weekday_range_may_end_at_7(self):
        self.assertEqual(
            next_times("0 0 * * 5-7", dt(2024, 1, 1), 4),
            [dt(2024, 1, 5), dt(2024, 1, 6), dt(2024, 1, 7), dt(2024, 1, 12)],
        )

    def test_star_in_weekday_stops_at_saturday(self):
        self.assertEqual(
            len(next_times("0 0 * * */3", dt(2024, 1, 1), 6)), 6
        )
        self.assertEqual(
            next_times("0 0 * * */3", dt(2024, 1, 1), 4),
            [dt(2024, 1, 3), dt(2024, 1, 6), dt(2024, 1, 7), dt(2024, 1, 10)],
        )

    def test_both_day_fields_restricted_means_either_matches(self):
        self.assertEqual(
            next_times("0 0 13 * 5", dt(2024, 1, 1), 4),
            [dt(2024, 1, 5), dt(2024, 1, 12), dt(2024, 1, 13), dt(2024, 1, 19)],
        )

    def test_only_day_of_month_restricted(self):
        self.assertEqual(
            next_times("0 0 13 * *", dt(2024, 1, 1), 2), [dt(2024, 1, 13), dt(2024, 2, 13)]
        )

    def test_only_day_of_week_restricted(self):
        self.assertEqual(
            next_times("0 0 * * 5", dt(2024, 1, 1), 3), [dt(2024, 1, 5), dt(2024, 1, 12), dt(2024, 1, 19)]
        )

    def test_a_star_step_day_of_month_counts_as_unrestricted_so_both_must_match(self):
        self.assertEqual(
            next_times("0 0 */2 * 5", dt(2024, 1, 1), 4),
            [dt(2024, 1, 5), dt(2024, 1, 19), dt(2024, 2, 9), dt(2024, 2, 23)],
        )

    def test_a_full_day_range_counts_as_restricted(self):
        # 1-31 is restricted, so the day of week is an alternative and every day fires.
        self.assertEqual(
            next_times("0 0 1-31 * 5", dt(2024, 1, 1), 3), [dt(2024, 1, 2), dt(2024, 1, 3), dt(2024, 1, 4)]
        )

    def test_day_31_skips_months_that_do_not_have_it(self):
        self.assertEqual(
            next_times("0 0 31 * *", dt(2024, 1, 1), 4),
            [dt(2024, 1, 31), dt(2024, 3, 31), dt(2024, 5, 31), dt(2024, 7, 31)],
        )

    def test_leap_day_waits_for_the_next_leap_year(self):
        self.assertEqual(
            next_times("0 0 29 2 *", dt(2023, 1, 1), 3),
            [dt(2024, 2, 29), dt(2028, 2, 29), dt(2032, 2, 29)],
        )

    def test_an_expression_that_never_fires_gives_an_empty_list(self):
        self.assertEqual(next_times("0 0 30 2 *", dt(2024, 1, 1), 3), [])

    def test_year_rollover(self):
        self.assertEqual(next_times("59 23 31 12 *", dt(2024, 12, 31, 23, 59), 1), [dt(2025, 12, 31, 23, 59)])

    def test_extra_whitespace_is_ignored(self):
        self.assertEqual(
            next_times("  0 \t 10   * * *  ", dt(2024, 1, 1), 1), [dt(2024, 1, 1, 10, 0)]
        )

    def test_invalid_expressions_raise_value_error(self):
        bad = [
            "", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * 32 * *",
            "* * * 0 *", "* * * 13 *", "* * * * 8", "*/0 * * * *", "5-1 * * * *", "a * * * *",
            "* * * foo *", "1,,2 * * * *", "*/ * * * *", "*/x * * * *", "-5 * * * *", "1, * * * *",
        ]
        for expression in bad:
            with self.subTest(expression=expression):
                with self.assertRaises(ValueError):
                    next_times(expression, dt(2024, 1, 1), 1)


if __name__ == "__main__":
    unittest.main()
