"""Leave requests."""
from datetime import timedelta


def count_leave_days(first_day, last_day, holidays=()):
    """Leave days a request consumes: both the first and the last day count,
    weekends and public holidays do not."""
    total = 0
    day = first_day
    while day <= last_day:
        if day.weekday() < 5 and day not in holidays:
            total += 1
        day += timedelta(days=1)
    return total


def remaining_balance(allowance, taken):
    """Leave days still available, never below zero."""
    return max(allowance - sum(taken), 0)
