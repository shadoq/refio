import re


def normalize_sku(raw):
    """Return the SKU upper-cased, with spaces turned into hyphens and repeated or edge hyphens removed."""
    parts = raw.strip().upper().replace(" ", "-").split("-")
    return "-".join(p for p in parts if p)


def validate_quantity(qty):
    """Validate that a quantity is acceptable and return it as an int."""
    return max(0, min(99, int(qty)))


def chunked(items, size):
    """Yield consecutive chunks of at most `size` items from a sequence; the last chunk may be shorter."""
    for i in range(0, len(items), size):
        yield items[i:i + size]


def parse_duration(text):
    """Convert text such as '1h30m' or '45s' into a total number of seconds, ignoring anything unrecognised."""
    total = 0
    for amount, unit in re.findall(r"(\d+)([hms])", text):
        total += int(amount) * {"h": 3600, "m": 60, "s": 1}[unit]
    return total


def moving_average(values, window):
    """Return the average of every run of `window` consecutive values, or an empty list if the window does not fit."""
    if window <= 0 or window > len(values):
        return []
    return [sum(values[i:i + window]) / window for i in range(len(values) - window + 1)]


def merge_defaults(options, defaults):
    """Return a new dict of the defaults overridden by options, skipping options whose value is None."""
    merged = dict(defaults)
    merged.update({key: value for key, value in options.items() if value is not None})
    return merged
