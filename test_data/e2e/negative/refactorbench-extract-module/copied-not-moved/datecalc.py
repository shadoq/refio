from datetime import date, timedelta


def parse_iso_date(text):
    """Parse YYYY-MM-DD, ignoring surrounding spaces."""
    return date.fromisoformat(text.strip())


def days_between(start, end):
    """Calendar days from start to end (negative when end is earlier)."""
    return (parse_iso_date(end) - parse_iso_date(start)).days


def is_weekend(day):
    """True for a Saturday or Sunday date object."""
    return day.weekday() >= 5


def business_days_between(start, end):
    """Number of Monday-Friday days in the half-open range [start, end)."""
    first = parse_iso_date(start)
    last = parse_iso_date(end)
    count = 0
    current = first
    while current < last:
        if not is_weekend(current):
            count += 1
        current += timedelta(days=1)
    return count
