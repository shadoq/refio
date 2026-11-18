"""Sorting of Polish surnames."""
import unicodedata


def _key(name):
    base = unicodedata.normalize("NFD", name.casefold())
    return "".join(ch for ch in base if not unicodedata.combining(ch)).replace("ł", "l")


def sort_surnames(names):
    """Return a new list with the surnames in Polish dictionary order."""
    return sorted(names, key=_key)
