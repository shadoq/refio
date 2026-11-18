# Instructions

Implement `ChangeMaker.change(amount, denominations)` in `src/ChangeMaker.java`. It returns the fewest coins that add up to `amount`, for any set of coin denominations. Greedy "largest coin first" is not enough: with coins `1, 5, 10, 21, 25` the amount `63` is best paid with `21 + 21 + 21`, not with `25 + 25 + 10 + 1 + 1 + 1`.

## Result

- A `List<Integer>` of the coins, sorted ascending, repeated coins listed as many times as they are used. `change(15, [1, 5, 10, 25])` is `[5, 10]`.
- An amount of 0 needs no coins: the result is an empty list, whatever the denominations are.
- When several combinations use the same minimal number of coins, return the one whose ascending list is smallest when compared coin by coin from the left. For `6` with `[1, 2, 3, 4, 5]` the candidates are `[1, 5]`, `[2, 4]` and `[3, 3]`, and the answer is `[1, 5]`.

## Input

- `denominations` may come in any order and may contain duplicates; each coin can be used any number of times. The list you receive may be unmodifiable and must not be changed.
- It may be empty (then only an amount of 0 can be paid).
- Amounts up to a few hundred thousand must be handled quickly.

## Errors

Both are `IllegalArgumentException` with exactly these messages, checked in this order:

1. `amount < 0`: `Negative totals are not allowed.`
2. a denomination that is 0 or negative: `Denominations must be positive.` (checked even when the amount is 0)
3. the amount cannot be paid with the given coins: `The total N cannot be represented in the given currency.` where `N` is the amount.
