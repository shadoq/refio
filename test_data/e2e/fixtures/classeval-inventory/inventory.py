class Inventory:
    """A small stock keeper for a shop.

    Items are identified by name. Names are normalised: surrounding spaces are
    stripped and the name is lowercased, so "  Bolt " and "bolt" are the same item.
    Prices are integers in cents. An item is only present while its quantity is
    greater than zero.
    """

    def __init__(self, low_stock_threshold=5):
        """Create an empty inventory.

        An item counts as low on stock when its quantity is strictly below
        low_stock_threshold.
        """
        raise NotImplementedError

    def add_item(self, name, quantity, price_cents):
        """Add quantity units of an item and return the new total quantity of it.

        If the item already exists its quantity grows and its price is replaced by
        price_cents. Raises ValueError when quantity is not positive, when
        price_cents is negative or when the normalised name is empty.
        """
        raise NotImplementedError

    def remove_item(self, name, quantity):
        """Remove quantity units of an item and return the quantity that is left.

        When nothing is left the item disappears from the inventory. Raises
        KeyError when the item does not exist, and ValueError when quantity is not
        positive or is more than is in stock (in that case nothing changes).
        """
        raise NotImplementedError

    def get_quantity(self, name):
        """Return the quantity in stock, 0 for an unknown item."""
        raise NotImplementedError

    def total_value_cents(self):
        """Return the sum of quantity * price_cents over all items."""
        raise NotImplementedError

    def low_stock(self):
        """Return the sorted list of names of items that are low on stock."""
        raise NotImplementedError

    def restock(self, target):
        """Bring every low-stock item up to target units.

        Returns a dict that maps the name of each item that was topped up to the
        number of units added. Items that are not low on stock are untouched. When
        target is not above the low stock threshold, raises ValueError and changes
        nothing.
        """
        raise NotImplementedError

    def search(self, prefix):
        """Return the sorted names that start with prefix (case-insensitive).

        An empty prefix matches every item.
        """
        raise NotImplementedError

    def report(self):
        """Return one line per item, sorted by name, as a list of strings.

        Each line looks like "bolt: 12 x 0.35" - the name, a colon, the quantity,
        " x ", and the price in whole currency units with exactly two decimals.
        """
        raise NotImplementedError
