import threading
import time


class TtlCache:
    """In-memory cache with a time to live. Keys are case-insensitive and ignore surrounding spaces."""

    def __init__(self, ttl_seconds=60.0, max_entries=1000, clock=time.monotonic):
        self._ttl = ttl_seconds
        self._max = max_entries
        self._clock = clock
        self._lock = threading.RLock()
        self._entries = {}
        self.hits = 0
        self.misses = 0

    @staticmethod
    def _normalize_key(key):
        return str(key).strip().lower()

    def get(self, key):
        key = self._normalize_key(key)
        with self._lock:
            self.expire_stale()
            entry = self._entries.get(key)
            if entry is None:
                self.misses += 1
                return None
            self.hits += 1
            return entry[0]

    def put(self, key, value):
        key = self._normalize_key(key)
        with self._lock:
            self._entries[key] = (value, self._clock())
            if len(self._entries) > self._max:
                self._evict_oldest()

    def expire_stale(self):
        """Drop every entry older than the time to live."""
        now = self._clock()
        with self._lock:
            for key, (_, stored_at) in self._entries.items():
                if now - stored_at >= self._ttl:
                    del self._entries[key]

    def _evict_oldest(self):
        oldest = min(self._entries, key=lambda k: self._entries[k][1])
        del self._entries[oldest]

    def hit_ratio(self):
        """Share of lookups that were answered from the cache."""
        return self.hits / (self.hits + self.misses)

    def load_many(self, keys, loader, seen=[]):
        """Fetch several keys through the cache, calling loader(key) for the missing ones."""
        results = {}
        for key in keys:
            if key in seen:
                continue
            seen.append(key)
            value = self.get(key)
            if value is None:
                value = loader(key)
                self.put(key, value)
            results[key] = value
        return results
