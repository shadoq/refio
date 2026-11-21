import unittest

from line_diff import diff


def nums(*ks):
    return [str(k) for k in ks]


class LineDiffTest(unittest.TestCase):
    def test_identical_and_empty_inputs_give_no_hunks(self):
        self.assertEqual(diff(["a", "b"], ["a", "b"]), [])
        self.assertEqual(diff([], []), [])

    def test_example_from_the_instructions(self):
        self.assertEqual(
            diff(["a", "b", "c", "d", "e"], ["a", "b", "X", "d", "e"]),
            ["@@ -2,3 +2,3 @@", " b", "-c", "+X", " d"],
        )

    def test_deletions_come_before_insertions_inside_a_block(self):
        self.assertEqual(
            diff(["a", "b", "c"], ["a", "x", "y", "z", "c"]),
            ["@@ -1,3 +1,5 @@", " a", "-b", "+x", "+y", "+z", " c"],
        )

    def test_swapped_lines_prefer_the_deletion_first(self):
        self.assertEqual(
            diff(["x", "y"], ["y", "x"]),
            ["@@ -1,2 +1,2 @@", "-x", " y", "+x"],
        )

    def test_equal_lines_are_matched_leftmost(self):
        self.assertEqual(
            diff(["a", "a", "a"], ["a", "a"]),
            ["@@ -2,2 +2,1 @@", " a", "-a"],
        )
        self.assertEqual(
            diff(["a", "b", "a"], ["a"]),
            ["@@ -1,3 +1,1 @@", " a", "-b", "-a"],
        )

    def test_change_on_the_first_line_has_no_leading_context(self):
        self.assertEqual(
            diff(["a", "b", "c"], ["X", "b", "c"]),
            ["@@ -1,2 +1,2 @@", "-a", "+X", " b"],
        )

    def test_change_on_the_last_line_has_no_trailing_context(self):
        self.assertEqual(
            diff(["a", "b", "c"], ["a", "b", "X"]),
            ["@@ -2,2 +2,2 @@", " b", "-c", "+X"],
        )

    def test_pure_insertion_and_pure_deletion_in_the_middle(self):
        self.assertEqual(
            diff(["a", "b", "c", "d"], ["a", "b", "X", "c", "d"]),
            ["@@ -2,2 +2,3 @@", " b", "+X", " c"],
        )
        self.assertEqual(
            diff(["a", "b", "X", "c", "d"], ["a", "b", "c", "d"]),
            ["@@ -2,3 +2,2 @@", " b", "-X", " c"],
        )

    def test_everything_inserted_into_an_empty_old_starts_at_line_zero(self):
        self.assertEqual(diff([], ["a", "b"]), ["@@ -0,0 +1,2 @@", "+a", "+b"])

    def test_everything_deleted_leaves_a_new_range_starting_at_zero(self):
        self.assertEqual(diff(["a", "b"], []), ["@@ -1,2 +0,0 @@", "-a", "-b"])

    def test_blocks_separated_by_two_unchanged_lines_share_a_hunk(self):
        old = nums(1, 2, 3, 4, 5, 6, 7, 8)
        new = ["1", "B", "3", "4", "E", "6", "7", "8"]
        self.assertEqual(
            diff(old, new),
            ["@@ -1,6 +1,6 @@", " 1", "-2", "+B", " 3", " 4", "-5", "+E", " 6"],
        )

    def test_blocks_separated_by_three_unchanged_lines_are_two_hunks(self):
        old = nums(1, 2, 3, 4, 5, 6, 7, 8)
        new = ["1", "B", "3", "4", "5", "F", "7", "8"]
        self.assertEqual(
            diff(old, new),
            [
                "@@ -1,3 +1,3 @@", " 1", "-2", "+B", " 3",
                "@@ -5,3 +5,3 @@", " 5", "-6", "+F", " 7",
            ],
        )

    def test_second_hunk_numbers_account_for_earlier_size_changes(self):
        old = nums(1, 2, 3, 4, 5, 6, 7, 8, 9)
        new = ["1", "a", "b", "c", "3", "4", "5", "6", "7", "9"]
        self.assertEqual(
            diff(old, new),
            [
                "@@ -1,3 +1,5 @@", " 1", "-2", "+a", "+b", "+c", " 3",
                "@@ -7,3 +9,2 @@", " 7", "-8", " 9",
            ],
        )

    def test_context_zero_shows_only_the_changes(self):
        self.assertEqual(
            diff(["a", "b", "c"], ["a", "X", "c"], context=0),
            ["@@ -2,1 +2,1 @@", "-b", "+X"],
        )
        self.assertEqual(
            diff(["a", "b"], ["a", "X", "b"], context=0),
            ["@@ -1,0 +2,1 @@", "+X"],
        )
        self.assertEqual(
            diff(["a", "X", "b"], ["a", "b"], context=0),
            ["@@ -2,1 +1,0 @@", "-X"],
        )

    def test_context_zero_never_merges_blocks_separated_by_an_unchanged_line(self):
        self.assertEqual(
            diff(["a", "b", "c"], ["X", "b", "Y"], context=0),
            ["@@ -1,1 +1,1 @@", "-a", "+X", "@@ -3,1 +3,1 @@", "-c", "+Y"],
        )

    def test_larger_context_merges_more_and_includes_more_lines(self):
        old = nums(1, 2, 3, 4, 5, 6, 7, 8, 9)
        new = ["1", "B", "3", "4", "5", "6", "G", "8", "9"]
        self.assertEqual(
            diff(old, new, context=2),
            ["@@ -1,9 +1,9 @@", " 1", "-2", "+B", " 3", " 4", " 5", " 6", "-7", "+G", " 8", " 9"],
        )

    def test_blank_and_whitespace_only_lines_are_real_lines(self):
        self.assertEqual(
            diff(["a", "", "b"], ["a", " ", "b"]),
            ["@@ -1,3 +1,3 @@", " a", "-", "+ ", " b"],
        )

    def test_trailing_spaces_make_lines_different(self):
        self.assertEqual(diff(["a "], ["a"]), ["@@ -1,1 +1,1 @@", "-a ", "+a"])

    def test_moved_block_is_deleted_and_reinserted(self):
        old = ["h", "A", "B", "m", "t"]
        new = ["h", "m", "A", "B", "t"]
        self.assertEqual(
            diff(old, new),
            ["@@ -1,5 +1,5 @@", " h", "+m", " A", " B", "-m", " t"],
        )

    def test_inputs_are_not_modified_and_tuples_are_accepted(self):
        old = ["a", "b"]
        new = ["a", "c"]
        diff(old, new)
        self.assertEqual((old, new), (["a", "b"], ["a", "c"]))
        self.assertEqual(diff(("a", "b"), ("a", "c")), ["@@ -1,2 +1,2 @@", " a", "-b", "+c"])

    def test_negative_context_is_rejected(self):
        with self.assertRaises(ValueError):
            diff(["a"], ["b"], context=-1)


if __name__ == "__main__":
    unittest.main()
