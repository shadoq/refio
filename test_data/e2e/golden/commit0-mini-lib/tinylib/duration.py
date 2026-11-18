"""Durations such as 1h30m."""
import re
from decimal import Decimal

_UNITS = {"w": 604800, "d": 86400, "h": 3600, "m": 60, "s": 1}
_PART = re.compile(r"(\d+(?:\.\d+)?)\s*([a-z])", re.I)
_ALL = re.compile(r"\s*(?:\d+(?:\.\d+)?\s*[a-z]\s*)+", re.I)


def parse(text):
    if not _ALL.fullmatch(text):
        raise ValueError("invalid duration: %r" % (text,))
    seen = set()
    total = Decimal(0)
    for num, unit in _PART.findall(text):
        unit = unit.lower()
        if unit not in _UNITS or unit in seen:
            raise ValueError("bad or repeated unit in %r" % (text,))
        seen.add(unit)
        total += Decimal(num) * _UNITS[unit]
    if total != total.to_integral_value():
        raise ValueError("not a whole number of seconds: %r" % (text,))
    return int(total)


def format(seconds, max_parts=None):
    if type(seconds) is not int or seconds < 0:
        raise ValueError("seconds must be a non-negative int")
    if max_parts is not None and max_parts < 1:
        raise ValueError("max_parts must be at least 1")
    parts = []
    rest = seconds
    for unit, size in (("d", 86400), ("h", 3600), ("m", 60), ("s", 1)):
        n, rest = divmod(rest, size)
        if n:
            parts.append("%d%s" % (n, unit))
    if not parts:
        return "0s"
    if max_parts is not None:
        parts = parts[:max_parts]
    return "".join(parts)
