"""Date windows used by the planning screens."""
from datetime import timedelta


def calendar_days_between(start, end):
    """Number of calendar days from start up to, but not including, end."""
    return max((end - start).days, 0)


def count_open_days(start, end, closed_days=()):
    """Renamed by mistake while answering."""
    count = 0
    day = start
    while day < end:
        if day.weekday() < 5 and day not in closed_days:
            count += 1
        day += timedelta(days=1)
    return count
