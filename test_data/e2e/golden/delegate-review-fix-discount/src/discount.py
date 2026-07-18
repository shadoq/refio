def discounted_total(total):
    """Orders of 100 or more get a 10 percent discount."""
    if total >= 100:
        return total * 0.9
    return total
