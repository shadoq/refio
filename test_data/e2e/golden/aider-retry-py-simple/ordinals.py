"""English ordinal suffixes."""


def ordinal(n):
    """Return n followed by its ordinal suffix, e.g. 22 -> '22nd'."""
    if isinstance(n, bool) or not isinstance(n, int):
        raise TypeError("n must be an int")
    if n < 0:
        raise ValueError("n must not be negative")
    if n % 100 in (11, 12, 13):
        suffix = "th"
    else:
        suffix = {1: "st", 2: "nd", 3: "rd"}.get(n % 10, "th")
    return f"{n}{suffix}"


def ordinal_list(numbers):
    """Return the ordinals of all numbers joined with ', ' (an empty list gives '')."""
    return ", ".join(ordinal(n) for n in numbers)
