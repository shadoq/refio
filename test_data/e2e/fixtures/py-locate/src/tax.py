def apply_tax(amount, rate=0.2):
    """Return the amount including tax, rounded to two decimals."""
    return round(amount * (1 + rate), 2)
