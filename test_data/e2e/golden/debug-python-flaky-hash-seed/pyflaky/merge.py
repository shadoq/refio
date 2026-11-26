"""Combine score dictionaries from two sources."""


def merge(base, extra, floor=0):
    """Merge two score dicts and drop entries that end up below floor."""
    combined = {
        name: base.get(name, 0) + extra.get(name, 0)
        for name in set(base) | set(extra)
    }
    for name in [n for n, v in combined.items() if v < floor]:
        del combined[name]
    return combined
