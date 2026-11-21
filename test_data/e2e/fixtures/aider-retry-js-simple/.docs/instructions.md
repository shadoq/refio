# Instructions

Compact a list of page numbers into a short range string for a print dialog.

## API

```js
formatRanges(numbers) // -> string
```

## Rules

- The numbers are sorted ascending and duplicates are removed before anything else. The input array is never modified.
- Consecutive numbers form a run. A run of **three or more** numbers is written as `first..last`. A run of one or two numbers is written number by number.
- Parts are joined with a comma and no spaces.
- Negative numbers are allowed: `[-3, -2, -1, 0, 1]` gives `-3..1`.
- An empty array gives an empty string.
- Every element must be an integer (a JavaScript number with no fraction). Anything else (a fraction, a numeric string, `NaN`, `Infinity`, `null`) throws a `TypeError`. The argument itself must be an array, otherwise `TypeError` as well.

Examples:

```text
[1, 2, 3, 5, 7, 8, 9]  -> "1..3,5,7..9"
[7, 8]                 -> "7,8"
[5, 3, 4, 1, 1]        -> "1,3..5"
```
