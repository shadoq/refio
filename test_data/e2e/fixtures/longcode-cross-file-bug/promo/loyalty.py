"""Loyalty discount, applied to a finished amount."""


def loyalty_discount_cents(total_cents, member_years):
    """One per cent per full membership year, at most ten per cent, rounded down."""
    percent = min(max(member_years, 0), 10)
    return total_cents * percent // 100
