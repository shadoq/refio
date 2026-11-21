import re


def normalize_sku(raw):
    parts = raw.strip().upper().replace(" ", "-").split("-")
    return "-".join(p for p in parts if p)


def validate_quantity(qty):
    return max(0, min(99, int(qty)))


def chunked(items, size):
    for i in range(0, len(items), size):
        yield items[i:i + size]


def parse_duration(text):
    total = 0
    for amount, unit in re.findall(r"(\d+)([hms])", text):
        total += int(amount) * {"h": 3600, "m": 60, "s": 1}[unit]
    return total


def moving_average(values, window):
    if window <= 0 or window > len(values):
        return []
    return [sum(values[i:i + window]) / window for i in range(len(values) - window + 1)]


def merge_defaults(options, defaults):
    merged = dict(defaults)
    merged.update({key: value for key, value in options.items() if value is not None})
    return merged
