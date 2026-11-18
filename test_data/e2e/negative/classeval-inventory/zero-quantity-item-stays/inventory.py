class Inventory:
    """A small stock keeper for a shop.

    Items are identified by name. Names are normalised: surrounding spaces are
    stripped and the name is lowercased, so "  Bolt " and "bolt" are the same item.
    Prices are integers in cents. An item is only present while its quantity is
    greater than zero.
    """

    def __init__(self, low_stock_threshold=5):
        self._threshold = low_stock_threshold
        self._qty = {}
        self._price = {}

    @staticmethod
    def _norm(name):
        return name.strip().lower()

    def add_item(self, name, quantity, price_cents):
        key = self._norm(name)
        if quantity <= 0 or price_cents < 0 or not key:
            raise ValueError("invalid item")
        self._qty[key] = self._qty.get(key, 0) + quantity
        self._price[key] = price_cents
        return self._qty[key]

    def remove_item(self, name, quantity):
        key = self._norm(name)
        if key not in self._qty:
            raise KeyError(name)
        if quantity <= 0 or quantity > self._qty[key]:
            raise ValueError("invalid quantity")
        self._qty[key] -= quantity
        return self._qty[key]

    def get_quantity(self, name):
        return self._qty.get(self._norm(name), 0)

    def total_value_cents(self):
        return sum(q * self._price[k] for k, q in self._qty.items())

    def low_stock(self):
        return sorted(k for k, q in self._qty.items() if q < self._threshold)

    def restock(self, target):
        if target <= self._threshold:
            raise ValueError("target must be above the low stock threshold")
        added = {}
        for k in self.low_stock():
            added[k] = target - self._qty[k]
            self._qty[k] = target
        return added

    def search(self, prefix):
        p = prefix.lower()
        return sorted(k for k in self._qty if k.startswith(p))

    def report(self):
        lines = []
        for k in sorted(self._qty):
            p = self._price[k]
            lines.append("%s: %d x %d.%02d" % (k, self._qty[k], p // 100, p % 100))
        return lines
