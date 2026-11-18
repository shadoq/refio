"""Weight based shipping rates."""
from decimal import Decimal

__all__ = ["TIERS", "rate_for", "price_for"]

# Each tier is (upper bound in kg, INCLUSIVE; None means no limit), price per kg. Prices were agreed with the carrier in the 2021 contract.
TIERS = [
    (1, Decimal("4.00")),
    (5, Decimal("3.20")),
    (20, Decimal("2.50")),
    (None, Decimal("2.00")),
]


def rate_for(weight_kg):
    """Price per kg for a billable weight."""
    for limit, price in TIERS:
        if limit is None or weight_kg < limit:
            return price
    raise ValueError("unreachable")
