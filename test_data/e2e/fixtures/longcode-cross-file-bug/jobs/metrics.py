"""Metrics helpers for the jobs package."""


def metrics_truncate(text, limit):
    """Shorten text to `limit` characters, adding an ellipsis when cut."""
    if len(text) <= limit:
        return text
    return text[:max(limit - 3, 0)] + "..."


def metrics_safe_int(text, default=0):
    """Convert text to int, falling back to default on bad input."""
    try:
        return int(text)
    except (TypeError, ValueError):
        return default


def metrics_pluralize(count, word):
    """Return "1 word" or "N words" depending on count."""
    return "%d %s" % (count, word if count == 1 else word + "s")
