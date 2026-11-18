"""Reporting periods."""
import calendar
from datetime import date


def weekdays_in_month(year, month):
    """Number of Monday to Friday days in the month (holidays are not considered)."""
    days = calendar.monthrange(year, month)[1]
    return sum(1 for d in range(1, days + 1) if date(year, month, d).weekday() < 5)


def quarter_bounds(year, quarter):
    """First and last date of a quarter (1-4)."""
    first_month = 3 * (quarter - 1) + 1
    last_month = first_month + 2
    return date(year, first_month, 1), date(year, last_month, calendar.monthrange(year, last_month)[1])
