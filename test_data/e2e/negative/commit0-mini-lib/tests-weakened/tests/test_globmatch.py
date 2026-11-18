import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from tinylib import globmatch


class Match(unittest.TestCase):
    def test_star(self):
        self.assertTrue(globmatch.match("*.py", "a.py"))
