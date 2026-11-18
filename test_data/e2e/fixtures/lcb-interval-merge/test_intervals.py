import random
import unittest

from intervals import merge_blocks


def reference(intervals, tolerance):
    items = [(s, e, w) for s, e, w in intervals if e > s]
    n = len(items)
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(n):
        for j in range(i + 1, n):
            gap = max(items[i][0], items[j][0]) - min(items[i][1], items[j][1])
            if gap <= tolerance:
                parent[find(i)] = find(j)
    groups = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(items[i])
    out = []
    for group in groups.values():
        peak = max(sum(w for s2, e2, w in group if s2 <= s < e2) for s, _, _ in group)
        out.append((min(s for s, _, _ in group), max(e for _, e, _ in group), peak))
    return sorted(out)


class MergeBlocksTest(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(merge_blocks([]), [])

    def test_unsorted_nested_and_overlapping(self):
        data = [(10, 12, 1), (1, 6, 2), (2, 3, 5), (5, 8, 1)]
        self.assertEqual(merge_blocks(data), [(1, 8, 7), (10, 12, 1)])

    def test_touching_intervals_merge_but_are_not_concurrent(self):
        self.assertEqual(merge_blocks([(1, 3, 4), (3, 5, 6)]), [(1, 5, 6)])

    def test_tolerance_is_inclusive(self):
        data = [(0, 2, 1), (5, 6, 1)]
        self.assertEqual(merge_blocks(data, 2), [(0, 2, 1), (5, 6, 1)])
        self.assertEqual(merge_blocks(data, 3), [(0, 6, 1)])

    def test_tolerance_chains_through_a_long_block(self):
        data = [(0, 10, 1), (11, 12, 1), (13, 14, 1), (30, 31, 1)]
        self.assertEqual(merge_blocks(data, 1), [(0, 14, 1), (30, 31, 1)])

    def test_gap_is_measured_from_the_furthest_end_not_the_previous_interval(self):
        data = [(0, 100, 1), (1, 2, 1), (101, 102, 1)]
        self.assertEqual(merge_blocks(data, 1), [(0, 102, 2)])

    def test_peak_is_summed_weight_at_one_instant(self):
        data = [(0, 4, 3), (2, 6, 4), (5, 9, 10)]
        self.assertEqual(merge_blocks(data), [(0, 9, 14)])

    def test_zero_length_intervals_are_ignored_but_validated(self):
        self.assertEqual(merge_blocks([(4, 4, 9), (1, 2, 1)]), [(1, 2, 1)])
        self.assertEqual(merge_blocks([(4, 4, 9)]), [])
        with self.assertRaises(ValueError):
            merge_blocks([(4, 4, -1)])

    def test_invalid_input(self):
        for bad in ([(3, 1, 1)], [(1, 3, -2)]):
            with self.assertRaises(ValueError):
                merge_blocks(bad)
        with self.assertRaises(ValueError):
            merge_blocks([(1, 2, 1)], -1)

    def test_input_is_not_modified(self):
        data = [(5, 6, 1), (1, 2, 1)]
        merge_blocks(data)
        self.assertEqual(data, [(5, 6, 1), (1, 2, 1)])

    def test_float_coordinates_and_zero_weight(self):
        self.assertEqual(merge_blocks([(0.5, 1.5, 0), (1.5, 2.0, 0)]), [(0.5, 2.0, 0)])

    def test_random_against_reference(self):
        rng = random.Random(20240607)
        for _ in range(400):
            n = rng.randint(0, 12)
            data = []
            for _ in range(n):
                start = rng.randint(0, 30)
                data.append((start, start + rng.randint(0, 6), rng.randint(0, 5)))
            tolerance = rng.randint(0, 3)
            self.assertEqual(merge_blocks(data, tolerance), reference(data, tolerance), (data, tolerance))

    def test_large_input_is_fast(self):
        data = [(i * 2, i * 2 + 3, 1) for i in range(60000)]
        self.assertEqual(merge_blocks(data), [(0, 119999 + 2, 2)])


if __name__ == "__main__":
    unittest.main()
