"""Daily revenue estimate used by the dashboard."""
from catalog.tiers import get_tiers


def cheapest_unit_price(sku):
    """Lowest unit price of a SKU over all of its tiers, in cents."""
    return min(cents for _, cents in get_tiers(sku))


def revenue_estimate(sold):
    """Estimate revenue from a dict of sku -> units sold at the cheapest tier."""
    return sum(cheapest_unit_price(sku) * units for sku, units in sold.items())
