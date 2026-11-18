import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from tinylib import semver
from tinylib.semver import Version


class Parse(unittest.TestCase):
    def test_plain_and_v_prefix(self):
        self.assertEqual(semver.parse("1.2.3"), Version(1, 2, 3, ()))
        self.assertEqual(semver.parse("v10.0.7"), Version(10, 0, 7, ()))

    def test_prerelease_and_build(self):
        self.assertEqual(semver.parse("1.2.3-alpha.1+build5"), Version(1, 2, 3, ("alpha", "1")))
        self.assertEqual(semver.parse("1.2.3+x"), Version(1, 2, 3, ()))

    def test_invalid(self):
        for bad in ("1.2", "1.2.3.4", "", "a.b.c", "1.2.3-", "1.2.x", " 1.2.3"):
            with self.assertRaises(ValueError, msg=bad):
                semver.parse(bad)


class Compare(unittest.TestCase):
    def test_numeric_parts(self):
        self.assertEqual(semver.compare("2.0.0", "10.0.0"), -1)
        self.assertEqual(semver.compare("1.10.0", "1.9.0"), 1)
        self.assertEqual(semver.compare("1.0.0", "1.0.0"), 0)

    def test_prerelease_is_lower_than_release(self):
        self.assertEqual(semver.compare("1.0.0", "1.0.0-rc.1"), 1)
        self.assertEqual(semver.compare("1.0.0-rc.1", "1.0.0"), -1)

    def test_prerelease_identifiers(self):
        self.assertEqual(semver.compare("1.0.0-alpha", "1.0.0-alpha.1"), -1)
        self.assertEqual(semver.compare("1.0.0-alpha.1", "1.0.0-alpha.beta"), -1)
        self.assertEqual(semver.compare("1.0.0-beta.2", "1.0.0-beta.11"), -1)
        self.assertEqual(semver.compare("1.0.0-1", "1.0.0-a"), -1)
        self.assertEqual(semver.compare("1.0.0-b", "1.0.0-a"), 1)

    def test_build_metadata_ignored(self):
        self.assertEqual(semver.compare("1.0.0+a", "1.0.0+b"), 0)


class Satisfies(unittest.TestCase):
    def test_caret(self):
        self.assertTrue(semver.satisfies("1.2.3", "^1.2.3"))
        self.assertTrue(semver.satisfies("1.9.9", "^1.2.3"))
        self.assertFalse(semver.satisfies("2.0.0", "^1.2.3"))
        self.assertFalse(semver.satisfies("1.2.2", "^1.2.3"))

    def test_caret_zero_major(self):
        self.assertTrue(semver.satisfies("0.2.9", "^0.2.3"))
        self.assertFalse(semver.satisfies("0.3.0", "^0.2.3"))
        self.assertTrue(semver.satisfies("0.0.3", "^0.0.3"))
        self.assertFalse(semver.satisfies("0.0.4", "^0.0.3"))

    def test_tilde(self):
        self.assertTrue(semver.satisfies("1.2.9", "~1.2.3"))
        self.assertFalse(semver.satisfies("1.3.0", "~1.2.3"))

    def test_operators_and_combinations(self):
        self.assertTrue(semver.satisfies("1.5.0", ">=1.0.0 <2.0.0"))
        self.assertTrue(semver.satisfies("1.5.0", ">=1.0.0, <2.0.0"))
        self.assertFalse(semver.satisfies("2.0.0", ">=1.0.0 <2.0.0"))
        self.assertTrue(semver.satisfies("1.0.0", ">=1.0.0"))
        self.assertFalse(semver.satisfies("1.0.0", ">1.0.0"))
        self.assertTrue(semver.satisfies("1.0.0", "<=1.0.0"))
        self.assertFalse(semver.satisfies("1.0.0", "<1.0.0"))
        self.assertTrue(semver.satisfies("1.2.3", "1.2.3"))
        self.assertTrue(semver.satisfies("1.2.3+meta", "=1.2.3"))
        self.assertFalse(semver.satisfies("1.2.4", "1.2.3"))

    def test_star_and_empty(self):
        self.assertTrue(semver.satisfies("9.9.9", "*"))
        self.assertTrue(semver.satisfies("9.9.9", ""))

    def test_prerelease_rule(self):
        self.assertFalse(semver.satisfies("1.3.0-beta.1", ">=1.0.0"))
        self.assertTrue(semver.satisfies("1.3.0-beta.1", ">=1.3.0-alpha"))
        self.assertFalse(semver.satisfies("1.4.0-beta.1", ">=1.3.0-alpha"))
        self.assertFalse(semver.satisfies("1.3.0-alpha", ">1.3.0-alpha"))
        self.assertTrue(semver.satisfies("1.3.0-alpha.1", ">1.3.0-alpha"))

    def test_invalid(self):
        for spec in ("!=1.0.0", "^1.2", "~x", ">>1.0.0", "latest"):
            with self.assertRaises(ValueError, msg=spec):
                semver.satisfies("1.0.0", spec)
        with self.assertRaises(ValueError):
            semver.satisfies("nope", "*")


class MaxSatisfying(unittest.TestCase):
    def test_picks_highest_by_precedence(self):
        versions = ["1.0.0", "1.10.0", "1.9.0", "2.0.0", "1.11.0-rc.1"]
        self.assertEqual(semver.max_satisfying(versions, "^1.0.0"), "1.10.0")

    def test_none_when_nothing_matches(self):
        self.assertIsNone(semver.max_satisfying(["1.0.0"], ">=2.0.0"))
        self.assertIsNone(semver.max_satisfying([], "*"))

    def test_returns_original_text(self):
        self.assertEqual(semver.max_satisfying(["v1.0.0", "v1.2.0+b7"], "^1.0.0"), "v1.2.0+b7")


if __name__ == "__main__":
    unittest.main()
