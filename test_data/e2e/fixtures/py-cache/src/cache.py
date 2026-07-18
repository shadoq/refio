class LruCache:
    """Fixed-capacity cache that evicts the least recently used entry."""

    def __init__(self, capacity):
        self.capacity = capacity
        self._data = {}

    def get(self, key):
        return self._data.get(key)

    def put(self, key, value):
        if key in self._data:
            del self._data[key]
        elif len(self._data) >= self.capacity:
            oldest = next(iter(self._data))
            del self._data[oldest]
        self._data[key] = value
