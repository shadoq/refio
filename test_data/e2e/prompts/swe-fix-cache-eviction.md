Issue report: LruCache evicts the wrong entry after a get.

Steps to reproduce: create `LruCache(2)`, put "a" and "b", call `get("a")`, then put "c".
Expected: "b" is evicted (it is the least recently used entry, "a" was just read).
Actual: "a" is evicted.

The cache lives in `src/cache.py`. A successful `get` must mark the key as most recently
used. Fix the root cause so `python3 -m unittest test_cache` passes. Do not edit
`test_cache.py`.
