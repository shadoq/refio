"""Proration of subscription charges."""
from datetime import timedelta


def billable_days(start, end, holidays=()):
    """Days in [start, end) that are billed: every day except listed holidays."""
    total = 0
    day = start
    while day < end:
        if day not in holidays:
            total += 1
        day += timedelta(days=1)
    return total


def prorate_amount(monthly_cents, days_used, days_in_month):
    """Share of a monthly price for the days actually used, rounded down."""
    return monthly_cents * days_used // days_in_month
