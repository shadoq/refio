"""pagerank.utils - small helpers used by the docs pipeline."""
import calendar
import datetime as dt


def _to_date(value):
    if isinstance(value, dt.datetime):
        return value.date()
    return value


def business_days_between(start, end):
    """Number of whole business days (Mon-Fri) in the inclusive range [start, end].

    Both bounds are datetime.date (or datetime.datetime, whose time part is ignored).
    The contract in docs/contract.md is authoritative.
    """
    start = _to_date(start)
    end = _to_date(end)
    if end < start:
        return 0
    total = 0
    day = start
    one = dt.timedelta(days=1)
    while day <= end:
        if day.weekday() < 5:
            total += 1
        day += one
    return total


def month_name(month):
    """English month name for 1-12."""
    return calendar.month_name[month]
