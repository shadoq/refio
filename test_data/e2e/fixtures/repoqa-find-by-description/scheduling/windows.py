"""Date windows used by the planning screens."""
from datetime import timedelta


def calendar_days_between(start, end):
    """Number of calendar days from start up to, but not including, end."""
    return max((end - start).days, 0)


def span_workdays(start, end, closed_days=()):
    """Count the days in [start, end) that are neither Saturday, Sunday nor closed.

    `closed_days` is a collection of dates on which the company is closed.
    """
    count = 0
    day = start
    while day < end:
        if day.weekday() < 5 and day not in closed_days:
            count += 1
        day += timedelta(days=1)
    return count


def shift_workdays(start, amount, closed_days=()):
    """Return the date that lies `amount` open weekdays after start."""
    day = start
    left = amount
    while left > 0:
        day += timedelta(days=1)
        if day.weekday() < 5 and day not in closed_days:
            left -= 1
    return day
