import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from strutils import (compress_runs, count_vowel_groups, mask_digits,
                      rotate_words, title_case_words)


class CompressRuns(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(compress_runs("aaabccdd"), "a3bc2d2")

    def test_single_chars_have_no_count(self):
        self.assertEqual(compress_runs("abc"), "abc")

    def test_empty(self):
        self.assertEqual(compress_runs(""), "")

    def test_case_sensitive(self):
        self.assertEqual(compress_runs("aAaa"), "aAa2")

    def test_long_run(self):
        self.assertEqual(compress_runs("x" * 12), "x12")

    def test_spaces_are_characters(self):
        self.assertEqual(compress_runs("a  b"), "a 2b")


class TitleCaseWords(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(title_case_words("the lord of the rings"), "The Lord of the Rings")

    def test_first_word_always_capitalised(self):
        self.assertEqual(title_case_words("a tale of two cities"), "A Tale of Two Cities")

    def test_rest_is_lowercased(self):
        self.assertEqual(title_case_words("WAR AND PEACE"), "War and Peace")

    def test_all_small_words_set(self):
        self.assertEqual(title_case_words("x a an the of in on and or y"),
                         "X a an the of in on and or Y")

    def test_empty(self):
        self.assertEqual(title_case_words(""), "")

    def test_single_word(self):
        self.assertEqual(title_case_words("hELLO"), "Hello")


class CountVowelGroups(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(count_vowel_groups("beautiful"), 3)

    def test_case_insensitive(self):
        self.assertEqual(count_vowel_groups("AEIoux"), 1)

    def test_no_vowels(self):
        self.assertEqual(count_vowel_groups("rhythm"), 0)

    def test_empty(self):
        self.assertEqual(count_vowel_groups(""), 0)

    def test_y_is_not_a_vowel(self):
        self.assertEqual(count_vowel_groups("yay"), 1)

    def test_groups_split_by_non_letters(self):
        self.assertEqual(count_vowel_groups("a-e i"), 3)


class MaskDigits(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(mask_digits("4921-7730-1188", 4), "****-****-1188")

    def test_keep_zero_masks_all(self):
        self.assertEqual(mask_digits("a1b22", 0), "a*b**")

    def test_keep_more_than_digits(self):
        self.assertEqual(mask_digits("12", 5), "12")

    def test_no_digits(self):
        self.assertEqual(mask_digits("abc", 2), "abc")

    def test_keep_counts_digits_only(self):
        self.assertEqual(mask_digits("1 2 3 4 5", 2), "* * * 4 5")


class RotateWords(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(rotate_words("one two three four", 1), "two three four one")

    def test_k_larger_than_count(self):
        self.assertEqual(rotate_words("a b c", 4), "b c a")

    def test_negative_k(self):
        self.assertEqual(rotate_words("a b c d", -1), "d a b c")

    def test_zero(self):
        self.assertEqual(rotate_words("a b", 0), "a b")

    def test_empty_and_blank(self):
        self.assertEqual(rotate_words("", 3), "")
        self.assertEqual(rotate_words("   ", 3), "")

    def test_collapses_whitespace(self):
        self.assertEqual(rotate_words("  a   b\tc ", 1), "b c a")


if __name__ == "__main__":
    unittest.main()
