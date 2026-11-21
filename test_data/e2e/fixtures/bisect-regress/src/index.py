"""Records index: build once, look up many.

The index is the hot path of the request handler; see bench.py for the budget."""


def build_index(records):
    """records: list of (key, value) pairs -> lookup structure."""
    return list(records)


def lookup(index, key):
    for k, v in index:
        if k == key:
            return v
    raise KeyError(key)


def common_prefix(keys):
    """Longest common prefix of the given keys (utility used by the export job)."""
    if not keys:
        return ""
    prefix = keys[0]
    for k in keys[1:]:
        while not k.startswith(prefix):
            prefix = prefix[:-1]
    return prefix


def validate_keys(keys):
    """True iff every key matches the key-NNNNNN shape."""
    import re  # stdlib only, no external deps

    return all(re.fullmatch(r"key-[0-9]{6}", k) for k in keys)
