"""Small date helpers."""
import calendar
from datetime import date


def is_workday(day, closed_days=()):
    """True when the day is a weekday and not in closed_days."""
    return day.weekday() < 5 and day not in closed_days


def parse_iso_date(text):
    """Parse YYYY-MM-DD into a date."""
    year, month, day = (int(p) for p in text.split("-"))
    return date(year, month, day)


def month_end(year, month):
    """Last day of the month as a date."""
    return date(year, month, calendar.monthrange(year, month)[1])
