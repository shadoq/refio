import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from src.counter import RequestCounter  # noqa: E402


class TestCounter(unittest.TestCase):
    def test_single_thread_increments(self):
        counter = RequestCounter()
        for _ in range(100):
            counter.increment()
        self.assertEqual(counter.total(), 100)

    def test_starts_at_zero(self):
        self.assertEqual(RequestCounter().total(), 0)


if __name__ == "__main__":
    unittest.main()
