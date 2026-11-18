import unittest

from confconv import convert

DEFAULT_TAIL = """\
db:
  pool: 5
  url: sqlite://memory

log:
  level: info
"""


class ConvertTests(unittest.TestCase):
    def test_basic_sections_and_defaults(self):
        src = "[server]\nhost = example.test\nport=9000\n"
        want = (
            "server:\n  host: example.test\n  port: 9000\n  debug: false\n\n" + DEFAULT_TAIL
        )
        self.assertEqual(convert(src), want)

    def test_empty_input_yields_all_defaults(self):
        want = (
            "server:\n  host: localhost\n  port: 8080\n  debug: false\n\n" + DEFAULT_TAIL
        )
        self.assertEqual(convert(""), want)

    def test_value_rules(self):
        src = (
            "[server]\n"
            "debug = Yes\n"
            "host = 'my \"quoted\" host'\n"
            "port = 80\n"
            "[db]\n"
            "url = postgres: local\n"
            "pool =\n"
            "flag = OFF\n"
            "tag = a#b\n"
            "color = #fff\n"
            "path = C:\\data\n"
            "[log]\n"
            "level = \"warn\"\n"
        )
        want = (
            "server:\n"
            "  debug: true\n"
            "  host: \"my \\\"quoted\\\" host\"\n"
            "  port: 80\n"
            "db:\n"
            "  url: \"postgres: local\"\n"
            "  pool: \"\"\n"
            "  flag: false\n"
            "  tag: \"a#b\"\n"
            "  color: \"#fff\"\n"
            "  path: C:\\data\n"
            "log:\n"
            "  level: \"warn\"\n"
        )
        self.assertEqual(convert(src), want)

    def test_comments_are_kept_and_semicolons_become_hashes(self):
        src = (
            "# global note\n"
            "; second note\n"
            "\n"
            "[server]\n"
            "# inside server\n"
            "host = a\n"
            "; also inside\n"
            "port = 1\n"
            "debug = no\n"
            "[db]\n"
            "pool = 2\n"
            "url = x\n"
            "[log]\n"
            "level = debug\n"
        )
        want = (
            "# global note\n"
            "# second note\n"
            "\n"
            "server:\n"
            "  # inside server\n"
            "  host: a\n"
            "  # also inside\n"
            "  port: 1\n"
            "  debug: false\n"
            "db:\n"
            "  pool: 2\n"
            "  url: x\n"
            "log:\n"
            "  level: debug\n"
        )
        self.assertEqual(convert(src), want)

    def test_comment_before_header_belongs_to_the_next_section(self):
        src = (
            "[server]\n"
            "host = a\n"
            "port = 1\n"
            "debug = on\n"
            "\n"
            "# trailing note of server\n"
            "\n"
            "# the database\n"
            "; settings\n"
            "[db]\n"
            "pool = 3\n"
            "[log]\n"
            "level = warn\n"
        )
        want = (
            "server:\n"
            "  host: a\n"
            "  port: 1\n"
            "  debug: true\n"
            "\n"
            "  # trailing note of server\n"
            "\n"
            "# the database\n"
            "# settings\n"
            "db:\n"
            "  pool: 3\n"
            "  url: sqlite://memory\n"
            "log:\n"
            "  level: warn\n"
        )
        self.assertEqual(convert(src), want)

    def test_missing_keys_go_before_blank_lines_and_next_header_comments(self):
        src = (
            "[server]\n"
            "host = a\n"
            "# last word on server\n"
            "\n"
            "\n"
            "# about db\n"
            "[db]\n"
            "pool = 1\n"
            "url = u\n"
            "\n"
            "[log]\n"
            "level = info\n"
        )
        want = (
            "server:\n"
            "  host: a\n"
            "  # last word on server\n"
            "  port: 8080\n"
            "  debug: false\n"
            "\n"
            "# about db\n"
            "db:\n"
            "  pool: 1\n"
            "  url: u\n"
            "\n"
            "log:\n"
            "  level: info\n"
        )
        self.assertEqual(convert(src), want)

    def test_inline_comments(self):
        src = (
            "[server]\n"
            "host = a  ; the host\n"
            "port = 80 # web\n"
            "debug = 'no' ; quoted so it stays a string\n"
            "[db]\n"
            "pool = 4;5\n"
            "url = 'x#y'   # keep\n"
            "[log]\n"
            "level = info\n"
        )
        want = (
            "server:\n"
            "  host: a  # the host\n"
            "  port: 80  # web\n"
            "  debug: \"no\"  # quoted so it stays a string\n"
            "db:\n"
            "  pool: 4;5\n"
            "  url: \"x#y\"  # keep\n"
            "log:\n"
            "  level: info\n"
        )
        self.assertEqual(convert(src), want)

    def test_unknown_sections_and_keys_survive_and_defaults_are_appended_last(self):
        src = "[feature.x]\nenabled = true\n\n\n\n[server]\nhost=h\nport=1\ndebug=0\n"
        want = (
            "feature.x:\n"
            "  enabled: true\n"
            "\n"
            "server:\n"
            "  host: h\n"
            "  port: 1\n"
            "  debug: 0\n"
            "\n" + DEFAULT_TAIL
        )
        self.assertEqual(convert(src), want)

    def test_windows_line_endings_and_surrounding_blank_lines(self):
        src = "\r\n\r\n[log]\r\nlevel = trace\r\n\r\n\r\n"
        want = (
            "log:\n"
            "  level: trace\n"
            "\n"
            "server:\n  host: localhost\n  port: 8080\n  debug: false\n"
            "\n"
            "db:\n  pool: 5\n  url: sqlite://memory\n"
        )
        self.assertEqual(convert(src), want)

    def test_errors_carry_the_line_number(self):
        cases = [
            ("host = a\n[server]\n", "line 1: setting outside a section"),
            ("[server]\nhost = a\n# c\nhost = b\n", "line 4: duplicate key 'host' in section 'server'"),
            ("[server]\nhost = a\n[db]\n[server]\n", "line 4: duplicate section 'server'"),
            ("[server]\nhost = a\nthis is not a setting\n", "line 3: malformed line"),
            ("[server]\n\n[a b]\n", "line 3: malformed line"),
            ("[server]\nmy key = 1\n", "line 2: malformed line"),
            ("[server]\nhost = 'a' junk\n", "line 2: malformed line"),
        ]
        for src, msg in cases:
            with self.assertRaises(ValueError, msg=src) as ctx:
                convert(src)
            self.assertEqual(str(ctx.exception), msg)

    def test_same_key_in_different_sections_is_fine(self):
        src = "[a]\nname = 1\n[b]\nname = 2\n"
        out = convert(src)
        self.assertIn("a:\n  name: 1\nb:\n  name: 2\n", out)


if __name__ == "__main__":
    unittest.main()
