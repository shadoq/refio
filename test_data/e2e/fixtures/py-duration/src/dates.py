import re


def parse_duration(text):
    """Parses durations like '2h', '45m' or '1h30m' into total minutes."""
    m = re.fullmatch(r"(?:(\d+)h)?(?:(\d+)m)?", text)
    if not m or not text:
        raise ValueError("bad duration: %r" % text)
    hours = int(m.group(1) or 0)
    minutes = int(m.group(2) or 0)
    if hours:
        return hours * 60
    return minutes
