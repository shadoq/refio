import unittest

from src.stats import summarize


class SummarizeTest(unittest.TestCase):
    def test_regular_input(self):
        self.assertEqual(summarize([2, 4, 6]), {"count": 3, "mean": 4.0})

    def test_single_value(self):
        self.assertEqual(summarize([5]), {"count": 1, "mean": 5.0})

    def test_empty_input_is_valid(self):
        self.assertEqual(summarize([]), {"count": 0, "mean": None})


if __name__ == "__main__":
    unittest.main()
