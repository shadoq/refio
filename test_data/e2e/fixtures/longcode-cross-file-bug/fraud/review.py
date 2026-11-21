"""Review helpers for the fraud package."""


def review_is_blank(text):
    """True for None, empty or whitespace-only text."""
    return text is None or not str(text).strip()


def review_sum_by(rows, key, field):
    """Sum `field` of the rows grouped by `key`."""
    totals = {}
    for row in rows:
        totals[row[key]] = totals.get(row[key], 0) + row[field]
    return totals


def review_sorted_desc(rows, key):
    """Sort dict rows by `key`, biggest first."""
    return sorted(rows, key=lambda r: r[key], reverse=True)
