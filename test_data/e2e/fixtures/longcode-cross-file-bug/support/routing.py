"""Routing helpers for the support package."""


def routing_dedupe(items):
    """Remove duplicates but keep the first occurrence of each item."""
    seen = set()
    out = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def routing_first(items, default=None):
    """Return the first item or the default for an empty sequence."""
    for item in items:
        return item
    return default


def routing_merge(base, override):
    """Return a new dict with override applied on top of base."""
    merged = dict(base)
    merged.update(override)
    return merged
