def compute_total(prices):
    """Return the sum of every price in the list."""
    total = 0
    # BUG: off-by-one - `range(len(prices) - 1)` skips the last price.
    for i in range(len(prices) - 1):
        total += prices[i]
    return total
