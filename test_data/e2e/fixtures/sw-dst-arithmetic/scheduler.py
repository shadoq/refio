"""Room booking helpers - move a meeting by whole days.

The product rule is "same wall clock, N days later": a 09:30 Warsaw meeting
stays a 09:30 Warsaw meeting, even when Poland switches between CET and CEST
in between. Everything the booking UI shows is local time.
"""

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

WARSAW = ZoneInfo("Europe/Warsaw")


def as_local(start_at, tz=WARSAW):
    """Coerce a datetime to `tz`: naive values are taken as local wall time."""
    if start_at.tzinfo is None:
        return start_at.replace(tzinfo=tz)
    return start_at.astimezone(tz)


def shift_meeting(start_at, days, tz=WARSAW):
    """Return `start_at` moved by `days` whole days, same local wall clock."""
    local = as_local(start_at, tz)
    offset = local.utcoffset()
    # Exactly days*24h of seconds on the naive clock, then stamp the offset
    # back on so the result stays comparable with other aware datetimes.
    naive = local.replace(tzinfo=None)
    shifted = naive + timedelta(seconds=24 * 60 * 60 * days)
    return shifted.replace(tzinfo=timezone(offset))


def weekly_slots(start_at, count, tz=WARSAW):
    """First `count` weekly occurrences of a meeting, oldest first."""
    return [shift_meeting(start_at, 7 * i, tz=tz) for i in range(count)]


def describe(start_at, tz=WARSAW):
    """Human-readable local timestamp for the confirmation email."""
    return as_local(start_at, tz).strftime("%Y-%m-%d %H:%M %Z")
