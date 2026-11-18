# Instructions

Compute the next fire times of a five-field cron expression.

## Input

`next_times(expression, start, n)` returns a list of at most `n` `datetime.datetime` values, in increasing order. `start` is a naive `datetime` (no time zone); there is no daylight-saving handling. Every returned time has second and microsecond equal to `0`, and is strictly after `start`: if `start` is exactly a fire time it is not returned, and seconds of `start` count (`10:00:30` is after `10:00:00`). `n = 0` returns `[]`.

The search covers 3660 days from the day of `start`. When the expression fires fewer than `n` times in that period the shorter list (possibly empty) is returned, for example for `0 0 30 2 *` (there is no 30 February).

## Expression

Five fields separated by any amount of whitespace (leading and trailing whitespace is ignored): minute, hour, day of month, month, day of week.

| field        | values | names |
|--------------|--------|-------|
| minute       | 0-59   |       |
| hour         | 0-23   |       |
| day of month | 1-31   |       |
| month        | 1-12   | `jan` ... `dec` |
| day of week  | 0-7, where both 0 and 7 are Sunday | `sun` ... `sat` |

Names are three letters, case-insensitive, and can be used anywhere a number can (alone, in lists and as range ends).

Each field is a comma-separated list of items, and every item is one of:

- `*`: every value of the field (for day of week: 0 to 6);
- `a`: a single value;
- `a-b`: all values from `a` to `b` inclusive, with `a <= b` (in day of week `5-7` means Friday, Saturday, Sunday);
- `*/s`, `a-b/s`: every `s`-th value, counted from the start of the range (`*/15` in minutes is 0, 15, 30, 45; `10-30/10` is 10, 20, 30);
- `a/s`: shorthand for `a-max/s`, where `max` is the largest value of the field (`5/20` in minutes is 5, 25, 45).

The step `s` is a positive integer. Anything else raises `ValueError`: a wrong number of fields, a value out of range or an unknown name, an empty list item, a reversed range, a step of `0` or a step that is not a number.

## Day of month and day of week

When both the day-of-month field and the day-of-week field are restricted, a day fires when **either** of them matches. When at most one of them is restricted, the day must match both fields (an unrestricted field matches every day). A day field is *unrestricted* when its text starts with `*`, so `*/2` counts as unrestricted and `1-31` as restricted.

A day of month that a month does not have (31 in April, 29 in February of a common year) never matches in that month.

## Examples

```python
from datetime import datetime
next_times("*/20 9 * * mon-fri", datetime(2024, 1, 5, 9, 30), 3)
# [datetime(2024, 1, 5, 9, 40), datetime(2024, 1, 8, 9, 0), datetime(2024, 1, 8, 9, 20)]
```
