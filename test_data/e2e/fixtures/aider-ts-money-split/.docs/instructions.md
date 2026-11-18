# Instructions

Implement `split(amount, ratios)` in `src/split.ts`. It divides an amount of money, given as an integer number of cents, between several parties in proportion to their ratios, without losing or inventing a cent.

## Rules

- `amount` is an integer number of cents (it may be negative or zero). `ratios` is a non-empty array of positive integers. The result has one entry per ratio, in the same order, and always sums to exactly `amount`.
- Each party first receives the whole cents of its exact share, rounded towards zero. The cents that are left over are then handed out one at a time, one per party, to the parties whose exact shares had the largest fractional remainders. When remainders are equal, the party that comes first in `ratios` wins.
- A negative amount is the mirror image of the positive one: `split(-n, r)` is `split(n, r)` with every entry negated, so the same parties get the extra cent.
- The result never contains `-0`; a party that gets nothing gets plain `0`.
- The amount can be as large as `Number.MAX_SAFE_INTEGER` in absolute value, and ratios can be too. The result must still be exact, so do not rely on floating point division of large products.
- The input array is never modified.

## Errors

`split` throws `RangeError` when `amount` is not a safe integer, when `ratios` is empty, or when any ratio is not a positive safe integer (zero, negative, fractional, `NaN`, infinite).

## Examples

```text
split(100, [1, 1, 1])   -> [34, 33, 33]
split(10, [1, 2])       -> [3, 7]
split(-100, [1, 1, 1])  -> [-34, -33, -33]
```
