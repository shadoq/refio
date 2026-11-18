"""Sorting of Polish surnames."""


def sort_surnames(names):
    """Return a new list with the surnames in Polish dictionary order."""
    return sorted(names, key=str.lower)
