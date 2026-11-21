"""Quantity price tiers per SKU.

A tier is [min_quantity, unit_price_cents]. Tiers are kept sorted by min_quantity.
"""

_TIERS = {
    "widget": [[1, 1000], [10, 900], [50, 800]],
    "gadget": [[1, 2500], [5, 2300], [20, 2000]],
    "gizmo": [[1, 400], [100, 350]],
}


def get_tiers(sku):
    """Return the price tiers of a SKU. Raises KeyError for an unknown SKU."""
    return _TIERS[sku]


def unit_price(sku, quantity):
    """Unit price in cents for buying `quantity` units of the SKU."""
    price = None
    for min_qty, cents in get_tiers(sku):
        if quantity >= min_qty:
            price = cents
    if price is None:
        raise ValueError("quantity must be at least 1")
    return price
