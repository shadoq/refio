"""Public money helpers."""


def format_minor(minor):
    """Format an amount in minor units of a two-decimal currency, e.g. 1234 -> "12.34"."""
    sign = "-" if minor < 0 else ""
    whole, frac = divmod(abs(minor), 100)
    return "%s%d.%02d" % (sign, whole, frac)
