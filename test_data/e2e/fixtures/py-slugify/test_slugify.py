import unittest

from src.slugify import slugify


class SlugifyTest(unittest.TestCase):
    def test_simple_title(self):
        self.assertEqual(slugify("Hello, World!"), "hello-world")

    def test_collapses_and_trims_separators(self):
        self.assertEqual(slugify("  Already--Fine "), "already-fine")

    def test_numbers_survive(self):
        self.assertEqual(slugify("Top 10 Tips"), "top-10-tips")


if __name__ == "__main__":
    unittest.main()
