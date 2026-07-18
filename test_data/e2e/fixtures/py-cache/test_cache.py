import unittest

from src.cache import LruCache


class LruCacheTest(unittest.TestCase):
    def test_put_and_get(self):
        c = LruCache(2)
        c.put("a", 1)
        self.assertEqual(c.get("a"), 1)

    def test_capacity_evicts_oldest(self):
        c = LruCache(2)
        c.put("a", 1)
        c.put("b", 2)
        c.put("c", 3)
        self.assertIsNone(c.get("a"))
        self.assertEqual(c.get("c"), 3)

    def test_get_refreshes_recency(self):
        c = LruCache(2)
        c.put("a", 1)
        c.put("b", 2)
        self.assertEqual(c.get("a"), 1)
        c.put("c", 3)
        self.assertIsNone(c.get("b"))
        self.assertEqual(c.get("a"), 1)


if __name__ == "__main__":
    unittest.main()
