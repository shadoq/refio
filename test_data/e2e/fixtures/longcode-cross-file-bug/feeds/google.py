"""Google helpers for the feeds package."""


def google_clamp(value, low, high):
    """Limit value to the closed range [low, high]."""
    return max(low, min(high, value))


def google_normalise_name(name):
    """Collapse inner whitespace and title-case a display name."""
    return " ".join(name.split()).title()


def google_chunks(items, size):
    """Yield consecutive slices of at most `size` items."""
    for i in range(0, len(items), size):
        yield items[i:i + size]
