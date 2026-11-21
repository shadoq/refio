"""Acceptance tests for the textutils exercise - do not modify."""
import unittest

from textutils import flatten_once, mask_email, slugify


class TestFlattenOnce(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(flatten_once([[1, 2], [3, [4]], 5]), [1, 2, 3, [4], 5])
        self.assertEqual(flatten_once([]), [])
        self.assertEqual(flatten_once(["ab", ["cd"]]), ["ab", "cd"])

    def test_flat_passthrough(self):
        self.assertEqual(flatten_once([1, 2, 3]), [1, 2, 3])

    def test_multiple_levels_one_level_only(self):
        self.assertEqual(flatten_once([[[1]], [[2], 3]]), [[1], [2], 3])

    def test_mixed_types(self):
        self.assertEqual(flatten_once([None, [0, ""], True]), [None, 0, "", True])


class TestSlugify(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(slugify("  Hello,  World! "), "hello-world")
        self.assertEqual(slugify("A -- B/C"), "a-b-c")
        self.assertEqual(slugify("ABC 123"), "abc-123")

    def test_only_punctuation_becomes_empty(self):
        self.assertEqual(slugify("---"), "")
        self.assertEqual(slugify("...?!"), "")

    def test_single_word(self):
        self.assertEqual(slugify("Already-Slug"), "already-slug")

    def test_underscores_are_separators(self):
        self.assertEqual(slugify("two_words"), "two-words")


class TestMaskEmail(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(mask_email("jdoe@example.com"), "j***@example.com")
        self.assertEqual(mask_email("a@b.co"), "a***@b.co")

    def test_long_local_part(self):
        self.assertEqual(mask_email("alice@corp.example"), "a***@corp.example")

    def test_domain_untouched(self):
        self.assertEqual(mask_email("x@sub.domain.io"), "x***@sub.domain.io")


if __name__ == "__main__":
    unittest.main()
