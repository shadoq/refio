"""Seasonal percentage promotions."""
from catalog.tiers import get_tiers


def apply_seasonal(sku, percent):
    """Return the price tiers of a SKU with `percent` per cent taken off.

    The result is meant for one quote only. The catalog itself must not change.
    Prices are rounded down to whole cents.
    """
    return [[min_qty, cents * (100 - percent) // 100] for min_qty, cents in get_tiers(sku)]


def discounted_unit_price(tiers, quantity):
    """Unit price from an already discounted tier list."""
    price = None
    for min_qty, cents in tiers:
        if quantity >= min_qty:
            price = cents
    if price is None:
        raise ValueError("quantity must be at least 1")
    return price
