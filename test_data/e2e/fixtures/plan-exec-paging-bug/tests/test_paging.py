import unittest

from app.paging import page


class PageTest(unittest.TestCase):
    def test_first_page(self):
        self.assertEqual(page([1, 2, 3, 4], 1, 2), [1, 2])
