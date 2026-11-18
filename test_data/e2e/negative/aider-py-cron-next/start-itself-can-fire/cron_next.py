"""Next fire times of a five-field cron expression."""

from datetime import datetime, timedelta

_MONTHS = {m: i + 1 for i, m in enumerate("jan feb mar apr may jun jul aug sep oct nov dec".split())}
_DAYS = {d: i for i, d in enumerate("sun mon tue wed thu fri sat".split())}


def _value(text, lo, hi, names):
    if text.isdigit():
        number = int(text)
    elif text.lower() in names:
        number = names[text.lower()]
    else:
        raise ValueError(f"bad value: {text!r}")
    if not lo <= number <= hi:
        raise ValueError(f"value out of range: {text!r}")
    return number


def _parse_field(text, lo, hi, names=None, open_hi=None):
    names = names or {}
    open_hi = hi if open_hi is None else open_hi
    values = set()
    for item in text.split(","):
        if not item:
            raise ValueError("empty list item")
        base, slash, step_text = item.partition("/")
        step = 1
        if slash:
            if not step_text.isdigit() or int(step_text) == 0:
                raise ValueError(f"bad step: {step_text!r}")
            step = int(step_text)
        if base == "*":
            first, last = lo, open_hi
        elif "-" in base:
            left, right = base.split("-", 1)
            first = _value(left, lo, hi, names)
            last = _value(right, lo, hi, names)
            if first > last:
                raise ValueError(f"reversed range: {base!r}")
        else:
            first = _value(base, lo, hi, names)
            last = open_hi if slash else first
        values.update(range(first, last + 1, step))
    return values


def _parse(expression):
    fields = expression.split()
    if len(fields) != 5:
        raise ValueError("expected five fields")
    minutes = _parse_field(fields[0], 0, 59)
    hours = _parse_field(fields[1], 0, 23)
    doms = _parse_field(fields[2], 1, 31)
    months = _parse_field(fields[3], 1, 12, _MONTHS)
    dows = {d % 7 for d in _parse_field(fields[4], 0, 7, _DAYS, open_hi=6)}
    dom_restricted = not fields[2].startswith("*")
    dow_restricted = not fields[4].startswith("*")
    return minutes, hours, doms, months, dows, dom_restricted, dow_restricted


def next_times(expression, start, n):
    """Return the next `n` fire times strictly after `start`."""
    minutes, hours, doms, months, dows, dom_restricted, dow_restricted = _parse(expression)
    result = []
    if n <= 0:
        return result
    day = start.replace(hour=0, minute=0, second=0, microsecond=0)
    for _ in range(3660):
        if day.month in months:
            dom_ok = day.day in doms
            dow_ok = (day.weekday() + 1) % 7 in dows
            matches = (dom_ok or dow_ok) if dom_restricted and dow_restricted else (dom_ok and dow_ok)
            if matches:
                for hour in sorted(hours):
                    for minute in sorted(minutes):
                        candidate = day.replace(hour=hour, minute=minute)
                        if candidate >= start.replace(second=0, microsecond=0):
                            result.append(candidate)
                            if len(result) == n:
                                return result
        day += timedelta(days=1)
    return result
