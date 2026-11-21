import random
import threading
import time
import unittest

from pairs import count_close_pairs


def brute(values, k):
    return sum(
        1
        for i in range(len(values))
        for j in range(i + 1, len(values))
        if abs(values[i] - values[j]) <= k
    )


def run_within(limit, fn, *args):
    """Run fn in a daemon thread; fail the test if it is not done within `limit` seconds."""
    box = {}

    def target():
        box["result"] = fn(*args)

    worker = threading.Thread(target=target, daemon=True)
    started = time.perf_counter()
    worker.start()
    worker.join(limit)
    elapsed = time.perf_counter() - started
    if worker.is_alive():
        raise AssertionError("still running after %.1fs, over the %.1fs limit" % (elapsed, limit))
    return box["result"]


class CorrectnessTest(unittest.TestCase):
    def test_empty_and_single(self):
        self.assertEqual(count_close_pairs([], 3), 0)
        self.assertEqual(count_close_pairs([5], 3), 0)

    def test_distance_exactly_k_counts(self):
        self.assertEqual(count_close_pairs([1, 4], 3), 1)
        self.assertEqual(count_close_pairs([1, 5], 3), 0)

    def test_duplicates_pair_up_even_with_k_zero(self):
        self.assertEqual(count_close_pairs([7, 7, 7, 2], 0), 3)

    def test_negative_values_and_unsorted_input(self):
        self.assertEqual(count_close_pairs([10, -3, 4, -1, 0], 4), 4)

    def test_matches_brute_force_on_random_small_inputs(self):
        rng = random.Random(5)
        for _ in range(200):
            values = [rng.randint(-30, 30) for _ in range(rng.randint(0, 40))]
            k = rng.randint(0, 12)
            self.assertEqual(count_close_pairs(values, k), brute(values, k), (values, k))

    def test_input_is_not_modified(self):
        values = [9, 1, 5, 3]
        count_close_pairs(values, 2)
        self.assertEqual(values, [9, 1, 5, 3])


class SpeedTest(unittest.TestCase):
    def test_two_hundred_thousand_values_within_the_time_limit(self):
        rng = random.Random(2024)
        values = [rng.randint(0, 10**9) for _ in range(200_000)]
        k = 10**8
        result = run_within(6.0, count_close_pairs, values, k)
        # the answer is far too large to enumerate pair by pair
        self.assertGreater(result, 3 * 10**9)
        self.assertLess(result, 20 * 10**9)

    def test_large_result_is_exact_on_a_structured_input(self):
        values = list(range(100_000))
        result = run_within(6.0, count_close_pairs, values, 2)
        n = len(values)
        self.assertEqual(result, (n - 1) + (n - 2))


if __name__ == "__main__":
    unittest.main()
