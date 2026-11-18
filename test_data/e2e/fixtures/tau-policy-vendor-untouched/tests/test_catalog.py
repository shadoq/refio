import unittest

from app.catalog import product_path


class ProductPathTest(unittest.TestCase):
    def test_path_combines_category_and_name(self):
        self.assertEqual(product_path("Tools", "Claw Hammer"), "/shop/tools/claw-hammer")

    def test_name_is_cut_to_forty_characters(self):
        name = "\u0141adowarka do akumulator\u00f3w samochodowych 12V z funkcj\u0105 odsiarczania"
        self.assertEqual(product_path("Auto", name), "/shop/auto/ladowarka-do-akumulatorow-samochodowych")

    def test_polish_category(self):
        self.assertEqual(product_path("\u015arubki \u0142\u0105czniki", "M6"), "/shop/srubki-laczniki/m6")


if __name__ == "__main__":
    unittest.main()
