import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from tinylib import globmatch


class Match(unittest.TestCase):
    def check(self, pattern, path, expected):
        self.assertEqual(globmatch.match(pattern, path), expected, (pattern, path))

    def test_star_stays_in_a_segment(self):
        self.check("*.py", "a.py", True)
        self.check("*.py", "dir/a.py", False)
        self.check("*", "", True)
        self.check("a*c", "abbbc", True)
        self.check("a*c", "ac", True)

    def test_question_mark(self):
        self.check("?.txt", "a.txt", True)
        self.check("?.txt", "ab.txt", False)
        self.check("?.txt", ".txt", False)

    def test_double_star(self):
        self.check("**/*.py", "a.py", True)
        self.check("**/*.py", "x/y/a.py", True)
        self.check("src/**/test_*.py", "src/test_a.py", True)
        self.check("src/**/test_*.py", "src/a/b/test_a.py", True)
        self.check("src/**/test_*.py", "lib/test_a.py", False)
        self.check("src/**", "src", True)
        self.check("src/**", "src/a/b", True)
        self.check("**", "a/b/c", True)
        self.check("a/**/b", "a/b", True)

    def test_character_classes(self):
        self.check("[a-c]x", "bx", True)
        self.check("[a-c]x", "dx", False)
        self.check("[!a-c]x", "bx", False)
        self.check("[!a-c]x", "dx", True)
        self.check("[xyz]", "y", True)

    def test_unclosed_bracket_is_literal(self):
        self.check("a[", "a[", True)
        self.check("a[b", "a[b", True)
        self.check("a[b", "ab", False)

    def test_braces(self):
        self.check("*.{js,ts}", "a.ts", True)
        self.check("*.{js,ts}", "a.css", False)
        self.check("{src,lib}/*.py", "lib/x.py", True)
        self.check("{src,lib}/*.py", "bin/x.py", False)
        self.check("file.{a*,b}", "file.abc", True)

    def test_regex_characters_are_literal(self):
        self.check("a.b", "axb", False)
        self.check("a.b", "a.b", True)
        self.check("a+b", "a+b", True)
        self.check("(x)", "(x)", True)
        self.check("a|b", "a", False)
        self.check("a^b$", "a^b$", True)

    def test_hidden_names(self):
        self.check("*", ".env", False)
        self.check(".*", ".env", True)
        self.check("**/*.py", ".venv/lib/a.py", False)
        self.check("*/a", ".hidden/a", False)
        self.check(".hidden/*", ".hidden/a", True)
        self.check("**/.env", "a/b/.env", True)
        self.check("a/**", "a/.git/x", False)

    def test_whole_path_must_match(self):
        self.check("a", "a/b", False)
        self.check("a/b", "a", False)
        self.check("b", "a/b", False)


class Filter(unittest.TestCase):
    def test_keeps_order(self):
        self.assertEqual(globmatch.filter("*.py", ["z.py", "b.txt", "a.py", "d/c.py"]), ["z.py", "a.py"])

    def test_nothing_matches(self):
        self.assertEqual(globmatch.filter("*.md", ["a.py"]), [])


if __name__ == "__main__":
    unittest.main()
