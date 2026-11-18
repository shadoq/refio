"""Price quotes for a basket."""
from catalog.tiers import unit_price
from promo.seasonal import apply_seasonal, discounted_unit_price


def quote(lines, promo_percent=0):
    """Quote a basket.

    lines is a list of (sku, quantity). Returns a dict with the per-line totals in
    cents (same order as the input) and the grand total in cents.
    """
    totals = []
    for sku, quantity in lines:
        if promo_percent:
            tiers = apply_seasonal(sku, promo_percent)
            unit = discounted_unit_price(tiers, quantity)
        else:
            unit = unit_price(sku, quantity)
        totals.append(unit * quantity)
    return {"lines": totals, "total": sum(totals)}
