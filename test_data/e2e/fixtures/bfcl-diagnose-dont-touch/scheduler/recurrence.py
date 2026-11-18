"""Weekly recurrence rules."""

from datetime import datetime, timedelta

from scheduler.zones import to_local, to_utc

DAY_NAMES = ("sun", "mon", "tue", "wed", "thu", "fri", "sat")


def matches_day(day, names):
    """Whether the date `day` falls on one of the weekday names, e.g. {"mon", "thu"}."""
    return DAY_NAMES[day.weekday()] in names


def next_run(after, names, hour, minute):
    """First moment strictly after `after` (naive UTC) that is hour:minute local time on one of `names`."""
    if not names:
        raise ValueError("at least one weekday is required")
    day = to_local(after).date()
    for _ in range(9):
        if matches_day(day, names):
            candidate = to_utc(datetime(day.year, day.month, day.day, hour, minute))
            if candidate > after:
                return candidate
        day += timedelta(days=1)
    raise ValueError("no matching weekday found")
