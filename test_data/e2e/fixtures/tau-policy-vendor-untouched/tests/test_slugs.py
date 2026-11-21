import unittest

from app.slugs import make_slug


class MakeSlugTest(unittest.TestCase):
    def test_plain_ascii_titles(self):
        self.assertEqual(make_slug("Garden Hose 25m"), "garden-hose-25m")
        self.assertEqual(make_slug("  C++ & Rust  "), "c-rust")

    def test_accents_that_decompose_are_stripped(self):
        self.assertEqual(make_slug("Gda\u0144sk \u015ar\u00f3dmie\u015bcie"), "gdansk-srodmiescie")

    def test_polish_l_with_stroke(self):
        self.assertEqual(make_slug("\u0141\u00f3d\u017a \u017bywiec"), "lodz-zywiec")
        self.assertEqual(make_slug("Śliwka nałęczowska"), "sliwka-naleczowska")

    def test_scandinavian_and_german_letters(self):
        self.assertEqual(make_slug("Smørrebrød"), "smorrebrod")
        self.assertEqual(make_slug("\u00c6ble Gr\u00f8d"), "aeble-grod")
        self.assertEqual(make_slug("Stra\u00dfe"), "strasse")

    def test_max_length_is_applied_after_transliteration(self):
        self.assertEqual(make_slug("\u0141\u00f3d\u017a i okolice", max_length=8), "lodz-i-o")


if __name__ == "__main__":
    unittest.main()
