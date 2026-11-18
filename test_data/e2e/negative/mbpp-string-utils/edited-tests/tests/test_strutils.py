import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from strutils import rotate_words


class RotateWords(unittest.TestCase):
    def test_negative_k(self):
        self.assertEqual(rotate_words("a b c d", 3), "d a b c")


if __name__ == "__main__":
    unittest.main()
