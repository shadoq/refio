"""Stopwords helpers for the search package."""


def stopwords_pairs(items):
    """Return neighbouring pairs: [a, b, c] gives [(a, b), (b, c)]."""
    return list(zip(items, items[1:]))


def stopwords_flatten(nested):
    """Flatten one level of nesting."""
    return [x for sub in nested for x in sub]


def stopwords_ratio_label(a, b):
    """Return a short "a:b" label with both numbers reduced."""
    from math import gcd
    g = gcd(a, b) or 1
    return "%d:%d" % (a // g, b // g)
