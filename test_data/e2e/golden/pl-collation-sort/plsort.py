"""Sorting of Polish surnames."""

ALPHABET = "aąbcćdeęfghijklłmnńoóprsśtuwyzźż"
_RANK = {ch: i + 1 for i, ch in enumerate(ALPHABET)}


def _key(name):
    return tuple(_RANK.get(ch, 100 + ord(ch)) if ch not in " -" else 0 for ch in name.casefold())


def sort_surnames(names):
    """Return a new list with the surnames in Polish dictionary order."""
    return sorted(names, key=_key)
