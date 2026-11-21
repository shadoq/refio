"""Digest helpers for the notifications package."""


def digest_is_blank(text):
    """True for None, empty or whitespace-only text."""
    return text is None or not str(text).strip()


def digest_sum_by(rows, key, field):
    """Sum `field` of the rows grouped by `key`."""
    totals = {}
    for row in rows:
        totals[row[key]] = totals.get(row[key], 0) + row[field]
    return totals


def digest_sorted_desc(rows, key):
    """Sort dict rows by `key`, biggest first."""
    return sorted(rows, key=lambda r: r[key], reverse=True)
