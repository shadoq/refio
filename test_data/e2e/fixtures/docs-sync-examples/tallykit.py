"""tallykit: tiny frequency counting."""


class Tally:
    def __init__(self, items=None):
        self._counts = {}
        for item in items or ():
            self.add(item)

    def add(self, item, n=1):
        """Count `item` n more times. Returns self so calls can be chained."""
        if n < 1:
            raise ValueError("n must be positive")
        self._counts[item] = self._counts.get(item, 0) + n
        return self

    @property
    def total(self):
        return sum(self._counts.values())

    def count(self, item):
        return self._counts.get(item, 0)

    def most_common(self, k=None):
        """(item, count) pairs, highest count first; ties are ordered by str(item)."""
        ordered = sorted(self._counts.items(), key=lambda kv: (-kv[1], str(kv[0])))
        return ordered if k is None else ordered[:k]

    def ratio(self, item, ndigits=2):
        if not self.total:
            return 0.0
        return round(self.count(item) / self.total, ndigits)

    def merge(self, other):
        """Return a NEW Tally with the counts of both. Neither input changes."""
        result = Tally()
        for source in (self, other):
            for item, n in source._counts.items():
                result.add(item, n)
        return result

    def __len__(self):
        return len(self._counts)

    def __contains__(self, item):
        return item in self._counts


def parse_counts(text):
    """Parse 'a=2, b=3' into a Tally. A pair without '=' or with a bad number raises ValueError."""
    tally = Tally()
    for pair in text.split(","):
        pair = pair.strip()
        if not pair:
            continue
        name, sep, number = pair.partition("=")
        if not sep or not number.strip().isdigit():
            raise ValueError("bad pair: " + pair)
        tally.add(name.strip(), int(number))
    return tally
