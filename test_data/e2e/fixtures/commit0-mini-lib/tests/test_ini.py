import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from tinylib import ini


class Basics(unittest.TestCase):
    def test_sections_and_both_separators(self):
        text = "[db]\nhost = localhost\nport: 5432\n[app]\nname=demo\n"
        self.assertEqual(ini.parse(text), {
            "db": {"host": "localhost", "port": "5432"},
            "app": {"name": "demo"},
        })

    def test_key_ends_at_first_separator(self):
        self.assertEqual(ini.parse("[s]\nurl = http://x/y?a=b\na:b=c\n"),
                         {"s": {"url": "http://x/y?a=b", "a": "b=c"}})

    def test_keys_before_a_section(self):
        self.assertEqual(ini.parse("top = 1\n[s]\nk = v\n"), {"": {"top": "1"}, "s": {"k": "v"}})
        self.assertEqual(ini.parse("[s]\nk = v\n"), {"s": {"k": "v"}})

    def test_empty_text_and_empty_section(self):
        self.assertEqual(ini.parse(""), {})
        self.assertEqual(ini.parse("[a]\n"), {"a": {}})

    def test_section_name_is_stripped(self):
        self.assertEqual(ini.parse("[ my sec ]\nk=v"), {"my sec": {"k": "v"}})

    def test_duplicates(self):
        text = "[a]\nk = 1\nk = 2\n[b]\nx = 1\n[a]\nj = 3\n"
        self.assertEqual(ini.parse(text), {"a": {"k": "2", "j": "3"}, "b": {"x": "1"}})

    def test_empty_value(self):
        self.assertEqual(ini.parse("[a]\nk =\n"), {"a": {"k": ""}})

    def test_crlf(self):
        self.assertEqual(ini.parse("[a]\r\nk = v\r\n"), {"a": {"k": "v"}})


class Comments(unittest.TestCase):
    def test_full_line_and_inline(self):
        text = "# c\n; c\n[a] ; trailing\nk = v # note\nm = x;y\nn = a ;b\n   # indented\n"
        self.assertEqual(ini.parse(text), {"a": {"k": "v", "m": "x;y", "n": "a"}})

    def test_quoted_values(self):
        text = '[a]\nk = "  spaced ; # kept  " # comment\nm = "x"\ne = ""\n'
        self.assertEqual(ini.parse(text), {"a": {"k": "  spaced ; # kept  ", "m": "x", "e": ""}})

    def test_text_after_closing_quote_is_an_error(self):
        with self.assertRaises(ValueError) as cm:
            ini.parse('[a]\nk = "x" y\n')
        self.assertTrue(str(cm.exception).startswith("line 2"))

    def test_unterminated_quote(self):
        with self.assertRaises(ValueError) as cm:
            ini.parse('[a]\n\nk = "x\n')
        self.assertTrue(str(cm.exception).startswith("line 3"))


class Continuation(unittest.TestCase):
    def test_joined_with_newlines(self):
        text = "[a]\npaths = one\n   two\n\tthree\nnext = 1\n"
        self.assertEqual(ini.parse(text), {"a": {"paths": "one\ntwo\nthree", "next": "1"}})

    def test_first_line_empty(self):
        self.assertEqual(ini.parse("[a]\nlist =\n  x\n  y\n"), {"a": {"list": "x\ny"}})

    def test_comment_lines_between_are_ignored(self):
        self.assertEqual(ini.parse("[a]\nk = 1\n  # skip\n  2\n"), {"a": {"k": "1\n2"}})

    def test_blank_line_ends_continuation(self):
        with self.assertRaises(ValueError) as cm:
            ini.parse("[a]\nk = 1\n\n  2\n")
        self.assertTrue(str(cm.exception).startswith("line 4"))

    def test_indented_line_after_section_header_is_an_error(self):
        with self.assertRaises(ValueError):
            ini.parse("[a]\n  k = 1\n")


class Errors(unittest.TestCase):
    def test_line_without_separator(self):
        with self.assertRaises(ValueError) as cm:
            ini.parse("[a]\nk = 1\njust text\n")
        self.assertTrue(str(cm.exception).startswith("line 3"))

    def test_bad_section_headers(self):
        for text in ("[a\nk=1", "[]\n", "[a] x\n"):
            with self.assertRaises(ValueError, msg=text):
                ini.parse(text)

    def test_empty_key(self):
        with self.assertRaises(ValueError) as cm:
            ini.parse("[a]\n= v\n")
        self.assertTrue(str(cm.exception).startswith("line 2"))


class Interpolation(unittest.TestCase):
    def test_same_section_and_other_section(self):
        text = "[paths]\nroot = /srv\nlogs = ${root}/logs\n[app]\nlog = ${paths:logs}/app.log\n"
        self.assertEqual(ini.parse(text)["app"]["log"], "/srv/logs/app.log")
        self.assertEqual(ini.parse(text)["paths"]["logs"], "/srv/logs")

    def test_forward_reference_and_chains(self):
        text = "[a]\nx = ${y}!\ny = ${z}\nz = ok\n"
        self.assertEqual(ini.parse(text)["a"], {"x": "ok!", "y": "ok", "z": "ok"})

    def test_escaped_reference(self):
        self.assertEqual(ini.parse("[a]\nk = $${not} ${v}\nv = 1\n")["a"]["k"], "${not} 1")

    def test_referenced_literal_is_not_expanded_again(self):
        text = "[a]\nlit = $${v}\nk = ${lit}\nv = 1\n"
        self.assertEqual(ini.parse(text)["a"]["k"], "${v}")

    def test_missing_reference(self):
        with self.assertRaises(KeyError) as cm:
            ini.parse("[a]\nk = ${nope}\n")
        self.assertIn("nope", str(cm.exception))
        with self.assertRaises(KeyError):
            ini.parse("[a]\nk = ${b:x}\n")

    def test_circular_reference(self):
        with self.assertRaises(ValueError):
            ini.parse("[a]\nx = ${y}\ny = ${x}\n")
        with self.assertRaises(ValueError):
            ini.parse("[a]\nx = ${x}\n")

    def test_can_be_switched_off(self):
        self.assertEqual(ini.parse("[a]\nk = ${nope}\n", interpolate=False), {"a": {"k": "${nope}"}})

    def test_quoted_value_is_interpolated_too(self):
        self.assertEqual(ini.parse('[a]\nv = 1\nk = " ${v} "\n')["a"]["k"], " 1 ")


if __name__ == "__main__":
    unittest.main()
