"""Polish local time (CET/CEST) for naive UTC datetimes."""

from datetime import date, datetime, timedelta


def _last_sunday(year, month):
    day = date(year, month, 31)
    return day - timedelta(days=(day.weekday() + 1) % 7)


def warsaw_offset_hours(utc):
    """Offset of Polish local time from UTC at the given naive UTC datetime: 1 in winter, 2 in summer."""
    start = datetime.combine(_last_sunday(utc.year, 3), datetime.min.time()) + timedelta(hours=1)
    end = datetime.combine(_last_sunday(utc.year, 10), datetime.min.time()) + timedelta(hours=1)
    return 2 if start <= utc < end else 1


def to_local(utc):
    return utc + timedelta(hours=warsaw_offset_hours(utc))


def to_utc(local):
    # TODO: ambiguous local times in the autumn changeover are not handled
    for offset in (1, 2):
        utc = local - timedelta(hours=offset)
        if warsaw_offset_hours(utc) == offset:
            return utc
    # a local time that does not exist (spring gap) falls back to the winter offset
    return local - timedelta(hours=1)
