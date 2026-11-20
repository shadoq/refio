import unittest

from app.validate import check_email


class EmailTest(unittest.TestCase):
    def test_accepts_email(self):
        self.assertEqual(check_email("a@b.c"), "a@b.c")
