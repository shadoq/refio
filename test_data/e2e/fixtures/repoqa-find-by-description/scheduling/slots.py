"""Appointment slot helpers."""
from datetime import timedelta


def weekend_days_between(start, end):
    """How many Saturdays and Sundays fall in [start, end)."""
    count = 0
    day = start
    while day < end:
        if day.weekday() >= 5:
            count += 1
        day += timedelta(days=1)
    return count


def next_free_slot(busy, length_minutes, day_start=9 * 60, day_end=17 * 60):
    """First start minute of a free gap of `length_minutes` in a sorted busy list."""
    cursor = day_start
    for begin, finish in busy:
        if begin - cursor >= length_minutes:
            return cursor
        cursor = max(cursor, finish)
    if day_end - cursor >= length_minutes:
        return cursor
    return None
