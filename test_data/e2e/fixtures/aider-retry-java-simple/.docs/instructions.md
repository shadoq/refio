# Instructions

Convert between spreadsheet column numbers and column labels.

Columns are numbered from 1 and labelled like a spreadsheet does: `A` is 1, `B` is 2, ... `Z` is 26, then `AA` is 27, `AB` is 28, ... `AZ` is 52, `BA` is 53, ... `ZZ` is 702, `AAA` is 703.

## API

```java
public final class ColumnLabel {
    public static String toLabel(int column);
    public static int fromLabel(String label);
}
```

## Rules

- `toLabel` returns the label in upper case. It throws `IllegalArgumentException` for a column below 1.
- `fromLabel` accepts upper and lower case letters `A-Z` / `a-z` and nothing else: it throws `IllegalArgumentException` for `null`, the empty string, and any label with another character (digits, spaces, punctuation).
- `fromLabel` throws `IllegalArgumentException` when the column does not fit in an `int`. The largest label that fits is `FXSHRXW` (2147483647).
- The two methods are inverse to each other: `fromLabel(toLabel(n)) == n` for every valid `n`.
