import unittest

from src.roman import to_roman


class ToRomanTest(unittest.TestCase):
    def test_basic_symbols(self):
        self.assertEqual(to_roman(1), "I")
        self.assertEqual(to_roman(5), "V")
        self.assertEqual(to_roman(10), "X")

    def test_subtractive_forms(self):
        self.assertEqual(to_roman(4), "IV")
        self.assertEqual(to_roman(9), "IX")
        self.assertEqual(to_roman(40), "XL")
        self.assertEqual(to_roman(900), "CM")

    def test_composite_numbers(self):
        self.assertEqual(to_roman(14), "XIV")
        self.assertEqual(to_roman(1990), "MCMXC")
        self.assertEqual(to_roman(2024), "MMXXIV")
        self.assertEqual(to_roman(3999), "MMMCMXCIX")

    def test_out_of_range_raises(self):
        with self.assertRaises(ValueError):
            to_roman(0)
        with self.assertRaises(ValueError):
            to_roman(4000)


if __name__ == "__main__":
    unittest.main()
