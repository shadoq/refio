import unittest

from ordinals import ordinal, ordinal_list


class OrdinalTest(unittest.TestCase):
    def test_simple_suffixes(self):
        self.assertEqual(ordinal(1), "1st")
        self.assertEqual(ordinal(2), "2nd")
        self.assertEqual(ordinal(3), "3rd")
        self.assertEqual(ordinal(4), "4th")
        self.assertEqual(ordinal(9), "9th")

    def test_zero(self):
        self.assertEqual(ordinal(0), "0th")

    def test_tens_and_twenties(self):
        self.assertEqual(ordinal(10), "10th")
        self.assertEqual(ordinal(20), "20th")
        self.assertEqual(ordinal(21), "21st")
        self.assertEqual(ordinal(22), "22nd")
        self.assertEqual(ordinal(23), "23rd")
        self.assertEqual(ordinal(24), "24th")

    def test_eleven_twelve_thirteen_always_take_th(self):
        self.assertEqual(ordinal(11), "11th")
        self.assertEqual(ordinal(12), "12th")
        self.assertEqual(ordinal(13), "13th")

    def test_hundreds_and_larger_numbers_with_the_exception(self):
        self.assertEqual(ordinal(101), "101st")
        self.assertEqual(ordinal(102), "102nd")
        self.assertEqual(ordinal(111), "111th")
        self.assertEqual(ordinal(112), "112th")
        self.assertEqual(ordinal(113), "113th")
        self.assertEqual(ordinal(1012), "1012th")
        self.assertEqual(ordinal(1021), "1021st")
        self.assertEqual(ordinal(1000013), "1000013th")

    def test_negative_numbers_are_rejected(self):
        with self.assertRaises(ValueError):
            ordinal(-1)

    def test_non_integers_are_rejected(self):
        for bad in (1.0, "1", None, True):
            with self.subTest(bad=bad):
                with self.assertRaises(TypeError):
                    ordinal(bad)

    def test_list(self):
        self.assertEqual(ordinal_list([1, 2, 3, 11, 12]), "1st, 2nd, 3rd, 11th, 12th")
        self.assertEqual(ordinal_list([]), "")

    def test_list_rejects_bad_elements(self):
        with self.assertRaises(ValueError):
            ordinal_list([1, -2])
        with self.assertRaises(TypeError):
            ordinal_list([1, "2"])


if __name__ == "__main__":
    unittest.main()
