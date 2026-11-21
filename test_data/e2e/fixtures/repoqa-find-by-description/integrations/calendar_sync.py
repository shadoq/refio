"""Pulls company closing days from the shared calendar."""


def load_closed_days(fetch):
    """Return a set of dates the company is closed, using the `fetch` callable."""
    return {entry["date"] for entry in fetch() if entry.get("closed")}


def merge_calendars(*calendars):
    """Union of several closed-day sets."""
    merged = set()
    for cal in calendars:
        merged |= set(cal)
    return merged
