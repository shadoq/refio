import unittest

from src.router import dispatch


class RouterTest(unittest.TestCase):
    def test_health(self):
        self.assertEqual(dispatch("/health"), (200, "healthy"))

    def test_version(self):
        self.assertEqual(dispatch("/version"), (200, "1.4.2"))

    def test_unknown_path_is_404(self):
        self.assertEqual(dispatch("/nope"), (404, "not found"))

    def test_status_endpoint(self):
        self.assertEqual(dispatch("/status"), (200, "ok"))


if __name__ == "__main__":
    unittest.main()
