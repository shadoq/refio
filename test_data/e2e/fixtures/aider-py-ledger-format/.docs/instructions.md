# Instructions

Print a small multi-currency ledger as fixed-width text.

## Input

`render_ledger(entries, locale="en")` receives a list of entries and a locale name. Each entry is a tuple `(date, description, currency, cents)`:

- `date` is a string such as `"2024-03-01"`; dates are only ever compared as strings;
- `description` is free text;
- `currency` is `"USD"` (symbol `$`) or `"EUR"` (symbol `€`);
- `cents` is an `int`, the amount in hundredths of the currency; negative for money going out.

An unknown currency or an unknown locale raises `ValueError`. The input list is not modified.

## Order

Entries are sorted by date, then by description, then by amount (`cents`, smallest first). Descriptions are compared as plain strings, so `"Zed"` comes before `"alpha"`. Entries that are equal on all three keys keep their input order.

## Amounts

The number is always written with exactly two decimals and with thousands separators. A locale decides the separators and where the symbol goes:

| locale | thousands | decimal | symbol |
|--------|-----------|---------|--------|
| `en`   | `,`       | `.`     | before the number: `$1,234.50` |
| `de`   | `.`       | `,`     | after the number, one space between: `1.234,50 €` |

The same symbol rule applies to both currencies, so with `de` a dollar amount reads `1.234,50 $`.

A negative amount is written in parentheses around the whole text, for example `($1,234.50)` or `(1.234,50 €)`. The sign is not written. A non-negative amount gets one trailing space instead of the closing parenthesis, so that the last digit or symbol of positive and negative amounts line up, for example `$1,234.50 `.

## Layout

Columns are separated by ` | `:

```text
Date       | Description              |         Amount
2024-03-01 | Coffee beans             |        $12.50 
```

- `Date`: left-aligned, padded to 10 characters;
- `Description`: left-aligned, padded to 24 characters. A description longer than 24 characters is cut to its first 21 characters followed by `...`; one of exactly 24 characters is left alone;
- `Amount`: right-aligned in 14 characters (the trailing space of a non-negative amount counts towards the 14). An amount text is never truncated.

The first line is the header shown above. Lines are joined with `\n`, without a trailing newline. With no entries the result is the header alone.
