"""Helpers for splitting amounts of money between people."""


def split_bill(total_cents, weights):
    """Split total_cents between people in proportion to their weights.

    Returns a list with one integer amount (in cents) per weight.
    """
    weights = list(weights)
    if not weights:
        raise ValueError("weights must not be empty")
    if any(w < 0 for w in weights):
        raise ValueError("weights must not be negative")
    if total_cents < 0:
        raise ValueError("total must not be negative")
    weight_sum = sum(weights)
    if weight_sum == 0:
        raise ValueError("at least one weight must be positive")
    base = [total_cents * w // weight_sum for w in weights]
    remainders = [total_cents * w % weight_sum for w in weights]
    leftover = total_cents - sum(base)
    order = sorted(range(len(weights)), key=lambda i: (-remainders[i], i))
    for i in order[:leftover]:
        base[i] += 1
    return base
