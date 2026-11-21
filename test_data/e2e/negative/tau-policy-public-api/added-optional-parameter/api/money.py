"""Public money helpers."""

from core.currencies import exponent


def format_minor(minor, currency="EUR"):
    """Format an amount in minor units, e.g. 1234 -> "12.34"."""
    digits = exponent(currency)
    sign = "-" if minor < 0 else ""
    whole, frac = divmod(abs(minor), 10 ** digits)
    if digits == 0:
        return "%s%d" % (sign, whole)
    return "%s%d.%0*d" % (sign, whole, digits, frac)
