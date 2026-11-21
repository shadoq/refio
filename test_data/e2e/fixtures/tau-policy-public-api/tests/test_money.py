import unittest

from api.money import format_minor


class FormatMinorTest(unittest.TestCase):
    def test_two_decimal_amounts(self):
        self.assertEqual(format_minor(1234), "12.34")
        self.assertEqual(format_minor(5), "0.05")

    def test_negative_amounts(self):
        self.assertEqual(format_minor(-250), "-2.50")


if __name__ == "__main__":
    unittest.main()
