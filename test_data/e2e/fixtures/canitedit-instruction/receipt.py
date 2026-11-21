"""Receipt printing for the till."""


def money(cents):
    """Format an amount of cents as 12.05."""
    return "%d.%02d" % (cents // 100, cents % 100)


def _row(left, right, width):
    gap = max(width - len(left) - len(right), 1)
    return left + " " * gap + right


def format_receipt(lines, currency="EUR", width=32):
    """Format a receipt.

    lines is a list of (name, quantity, unit_price_cents). Every line is printed as
    "<quantity>x <name>" with its amount on the right, followed by a rule and the
    total row. Returns the text without a trailing newline.
    """
    out = []
    total = 0
    for name, quantity, unit_cents in lines:
        amount = quantity * unit_cents
        total += amount
        out.append(_row("%dx %s" % (quantity, name), money(amount), width))
    out.append("-" * width)
    out.append(_row("TOTAL " + currency, money(total), width))
    return "\n".join(out)


def format_summary(lines):
    """One-line summary such as "3 items, 2 lines"."""
    items = sum(quantity for _, quantity, _ in lines)
    return "%d items, %d lines" % (items, len(lines))
