import contextlib
import io
import os
import tempfile
import unittest

from shelf.cli import main


class CliTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.store = os.path.join(self.dir.name, "shelf.json")

    def tearDown(self):
        self.dir.cleanup()

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                code = main(list(argv), self.store, out, err)
        except SystemExit as exc:
            code = exc.code
        return code, out.getvalue(), err.getvalue()

    def test_empty_shelf(self):
        self.assertEqual(self.run_cli("list"), (0, "The shelf is empty.\n", ""))
        self.assertEqual(self.run_cli("stats"), (0, "The shelf is empty.\n", ""))

    def test_add_and_duplicate(self):
        self.assertEqual(self.run_cli("add", "Tidewater", "Ola Brandt", "--year", "1987"),
                         (0, 'Added "Tidewater" by Ola Brandt (1987).\n', ""))
        self.assertEqual(self.run_cli("add", "Lantern Row", "Mira Quill"),
                         (0, 'Added "Lantern Row" by Mira Quill.\n', ""))
        self.assertEqual(self.run_cli("add", "Tidewater", "Ola Brandt"),
                         (1, "", 'Error: "Tidewater" by Ola Brandt is already on the shelf.\n'))

    def test_list_singular_plural_and_filter(self):
        self.run_cli("add", "Tidewater", "Ola Brandt", "--year", "1987")
        self.assertEqual(self.run_cli("list"),
                         (0, "1. Tidewater - Ola Brandt\n1 book listed.\n", ""))
        self.run_cli("add", "Lantern Row", "Mira Quill")
        self.run_cli("add", "Salt Roads", "Ola Brandt", "--year", "1991")
        self.assertEqual(self.run_cli("list"), (0, "1. Tidewater - Ola Brandt\n2. Lantern Row - Mira Quill\n"
                                                   "3. Salt Roads - Ola Brandt\n3 books listed.\n", ""))
        self.assertEqual(self.run_cli("list", "--author", "Ola Brandt"),
                         (0, "1. Tidewater - Ola Brandt\n3. Salt Roads - Ola Brandt\n2 books listed.\n", ""))
        self.assertEqual(self.run_cli("list", "--author", "Nobody"), (0, "No books by Nobody.\n", ""))

    def test_remove(self):
        self.run_cli("add", "Tidewater", "Ola Brandt")
        self.assertEqual(self.run_cli("remove", "2"),
                         (1, "", "Error: there is no book number 2.\n"))
        self.assertEqual(self.run_cli("remove", "0"),
                         (1, "", "Error: there is no book number 0.\n"))
        self.assertEqual(self.run_cli("remove", "1"), (0, 'Removed "Tidewater".\n', ""))
        self.assertEqual(self.run_cli("list"), (0, "The shelf is empty.\n", ""))

    def test_stats(self):
        self.run_cli("add", "Tidewater", "Ola Brandt")
        self.assertEqual(self.run_cli("stats"), (0, "Books: 1\nAuthors: 1\nOldest: unknown\n", ""))
        self.run_cli("add", "Salt Roads", "Ola Brandt", "--year", "1991")
        self.run_cli("add", "Lantern Row", "Mira Quill", "--year", "1975")
        self.assertEqual(self.run_cli("stats"),
                         (0, "Books: 3\nAuthors: 2\nOldest: Lantern Row (1975)\n", ""))

    def test_help_texts(self):
        code, out, _ = self.run_cli("--help")
        self.assertEqual(code, 0)
        for text in ("Manage a tiny personal library.", "Add a book to the shelf",
                     "List the books on the shelf", "Remove a book by its number",
                     "Show statistics about the shelf"):
            self.assertIn(text, out)
        code, out, _ = self.run_cli("add", "--help")
        for text in ("Title of the book", "Author of the book", "Year of publication"):
            self.assertIn(text, out)
        code, out, _ = self.run_cli("list", "--help")
        self.assertIn("Only show books by this author", out)
        code, out, _ = self.run_cli("remove", "--help")
        self.assertIn("Number shown by the list command", out)


if __name__ == "__main__":
    unittest.main()
