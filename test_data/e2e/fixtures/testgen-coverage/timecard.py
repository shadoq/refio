"""Time card calculations for hourly staff."""

DAY = 24 * 60
OVERTIME_AFTER = 40 * 60


class TimecardError(ValueError):
    """Raised for malformed times, shifts or summaries."""


def parse_time(text):
    """Return minutes since midnight for "H:MM" or "HH:MM"."""
    parts = text.strip().split(":")
    if len(parts) != 2 or not all(p.isdigit() for p in parts):
        raise TimecardError("bad time: %r" % text)
    hours, minutes = int(parts[0]), int(parts[1])
    if hours > 23 or minutes > 59:
        raise TimecardError("time out of range: %r" % text)
    return hours * 60 + minutes


def parse_shift(text):
    """Return (start, end) in minutes; an end before the start means the shift passes midnight."""
    pieces = text.split("-")
    if len(pieces) != 2:
        raise TimecardError("bad shift: %r" % text)
    start = parse_time(pieces[0])
    end = parse_time(pieces[1])
    if end == start:
        raise TimecardError("empty shift: %r" % text)
    if end < start:
        end += DAY
    return start, end


def break_minutes(duration):
    """Unpaid break for a shift of the given length in minutes."""
    if duration >= 9 * 60:
        return 60
    if duration >= 6 * 60:
        return 30
    return 0


def paid_minutes(text, unpaid_break=True):
    """Paid minutes of one shift text such as "09:00-17:30"."""
    start, end = parse_shift(text)
    duration = end - start
    if unpaid_break:
        duration -= break_minutes(duration)
    return duration


def week_summary(days):
    """Summarise a week. days maps a day name to a list of shift texts."""
    if not days:
        raise TimecardError("no days given")
    total = 0
    worked = 0
    longest = 0
    for day, shifts in days.items():
        day_total = sum(paid_minutes(s) for s in shifts)
        if day_total > 16 * 60:
            raise TimecardError("more than 16 paid hours on %s" % day)
        if day_total:
            worked += 1
        longest = max(longest, day_total)
        total += day_total
    return {
        "total_minutes": total,
        "overtime_minutes": max(total - OVERTIME_AFTER, 0),
        "days_worked": worked,
        "longest_day_minutes": longest,
    }


def format_hours(minutes):
    """Format minutes as "7h 30m", "7h" or "45m"."""
    if minutes < 0:
        raise TimecardError("negative duration")
    hours, rest = divmod(minutes, 60)
    if rest == 0:
        return "%dh" % hours
    if hours == 0:
        return "%dm" % rest
    return "%dh %dm" % (hours, rest)
