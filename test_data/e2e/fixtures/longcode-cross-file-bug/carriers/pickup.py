"""Pickup helpers for the carriers package."""


def pickup_pairs(items):
    """Return neighbouring pairs: [a, b, c] gives [(a, b), (b, c)]."""
    return list(zip(items, items[1:]))


def pickup_flatten(nested):
    """Flatten one level of nesting."""
    return [x for sub in nested for x in sub]


def pickup_ratio_label(a, b):
    """Return a short "a:b" label with both numbers reduced."""
    from math import gcd
    g = gcd(a, b) or 1
    return "%d:%d" % (a // g, b // g)
