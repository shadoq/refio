"""pagerank.utils - small helpers used by the docs pipeline."""
import calendar
import datetime as dt


def business_days_between(start, end):
    """Number of whole business days (Mon-Fri) in the inclusive range [start, end].

    Both bounds are datetime.date (or datetime.datetime, whose time part is ignored).
    The contract in docs/contract.md is authoritative.
    """
    if isinstance(start, dt.datetime):
        start = start.date()
    if isinstance(end, dt.datetime):
        end = end.date()
    if end < start:
        return 0
    return (end - start).days + 1


def month_name(month):
    """English month name for 1-12."""
    return calendar.month_name[month]
