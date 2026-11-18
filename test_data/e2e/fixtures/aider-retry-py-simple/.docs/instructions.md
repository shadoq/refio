# Instructions

Format whole numbers as English ordinals for a report generator: `1st`, `2nd`, `3rd`, `4th`, and so on.

## API

```python
def ordinal(n):
    """Return n followed by its ordinal suffix, e.g. 22 -> '22nd'."""

def ordinal_list(numbers):
    """Return the ordinals of all numbers joined with ', ' (an empty list gives '')."""
```

## Rules

- The suffix depends on the last digit: `1` -> `st`, `2` -> `nd`, `3` -> `rd`, everything else -> `th`.
- Numbers ending in 11, 12 or 13 are the exception and always take `th`: `11th`, `12th`, `13th`, `111th`, `1012th`. Only the last two digits decide this; `21st`, `101st` and `1013th` follow the normal rule where it applies.
- `0` is `0th`.
- `ordinal` raises `ValueError` for a negative number and `TypeError` for anything that is not an `int` (a `bool` is not accepted either).
- `ordinal_list` applies the same rules and raises the same errors for any element.
