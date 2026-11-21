"""Helpers for splitting amounts of money between people."""


def split_bill(total_cents, weights):
    """Split total_cents between people in proportion to their weights.

    Returns a list with one integer amount (in cents) per weight.
    """
    weight_sum = sum(weights)
    return [round(total_cents * w / weight_sum) for w in weights]
