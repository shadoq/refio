import unittest

from lift_scheduler import simulate


class LiftSchedulerTest(unittest.TestCase):
    def test_no_requests_no_stops(self):
        self.assertEqual(simulate([], []), [])

    def test_single_button_example_from_the_instructions(self):
        self.assertEqual(simulate([], [(0, 4)]), [(6, 4)])

    def test_travel_and_door_times_are_parameters(self):
        self.assertEqual(simulate([], [(0, 3), (0, 4)], start=1, travel=3, door=2), [(6, 3), (11, 4)])

    def test_two_buttons_ahead_are_served_in_floor_order_with_door_time_between(self):
        self.assertEqual(simulate([], [(0, 5), (0, 3)]), [(4, 3), (13, 5)])

    def test_a_request_made_before_the_car_arrives_is_served_on_the_way(self):
        self.assertEqual(simulate([], [(0, 5), (3, 3)]), [(4, 3), (13, 5)])

    def test_a_request_made_after_the_car_passed_waits_for_the_return_trip(self):
        self.assertEqual(simulate([], [(0, 5), (3, 2)]), [(8, 5), (19, 2)])

    def test_hall_calls_are_served_on_the_way_only_in_their_own_direction(self):
        calls = [(0, 4, "D"), (0, 3, "U")]
        self.assertEqual(simulate(calls, [(0, 6)]), [(4, 3), (15, 6), (24, 4)])

    def test_opposite_hall_call_is_skipped_while_requests_remain_ahead(self):
        self.assertEqual(simulate([(0, 7, "U")], [(0, 5)], start=10), [(10, 5), (19, 7)])

    def test_opposite_hall_call_at_the_turning_point_is_served(self):
        self.assertEqual(simulate([(0, 5, "D")], []), [(8, 5)])

    def test_both_directions_at_the_turning_floor_share_one_stop(self):
        self.assertEqual(simulate([(0, 5, "U"), (0, 5, "D")], []), [(8, 5)])

    def test_car_keeps_its_direction_instead_of_taking_the_nearest_request(self):
        self.assertEqual(simulate([], [(0, 3), (0, 6), (5, 2)]), [(4, 3), (15, 6), (28, 2)])

    def test_idle_car_goes_to_the_nearest_request_and_the_lower_floor_wins_a_tie(self):
        self.assertEqual(simulate([], [(0, 3), (0, 7)], start=5), [(4, 3), (17, 7)])
        self.assertEqual(simulate([], [(0, 3), (0, 4)], start=6), [(4, 4), (11, 3)])

    def test_idle_car_waits_for_a_late_request_and_reports_the_arrival_time(self):
        self.assertEqual(simulate([(150, 3, "D")], [(100, 1)]), [(100, 1), (154, 3)])

    def test_request_during_open_doors_causes_a_second_stop_on_the_same_floor(self):
        self.assertEqual(simulate([], [(0, 1), (3, 1)]), [(0, 1), (5, 1)])

    def test_two_requests_for_the_same_floor_at_the_same_time_share_one_stop(self):
        self.assertEqual(simulate([(0, 4, "U")], [(0, 4), (0, 4)]), [(6, 4)])

    def test_unserved_opposite_call_on_the_current_floor_does_not_reopen_the_doors(self):
        # Car stops at 3 going up for a button; the D call on floor 3 waits because floor 6 is still ahead.
        self.assertEqual(simulate([(0, 3, "D")], [(0, 3), (0, 6)]), [(4, 3), (15, 6), (26, 3)])

    def test_input_order_does_not_matter(self):
        a = simulate([(0, 4, "D"), (0, 3, "U")], [(0, 6)])
        b = simulate([(0, 3, "U"), (0, 4, "D")], [(0, 6)])
        self.assertEqual(a, b)

    def test_bad_direction_raises_value_error(self):
        with self.assertRaises(ValueError):
            simulate([(0, 3, "X")], [])


if __name__ == "__main__":
    unittest.main()
