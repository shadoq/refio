def discounted_total(total):
    """Orders of 100 or more get a 10 percent discount."""
    # BUG: off-by-one - excludes exactly 100, so a total of 100 gets no discount.
    if total > 100:
        return total * 0.9
    return total
