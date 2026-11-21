# Instructions

Evaluate integer arithmetic expressions given as text, for a spreadsheet-like formula bar.

## API

```java
public final class Calculator {
    public static long evaluate(String expression);
}
```

`CalcException` (an unchecked exception, already provided in `CalcException.java`) is thrown for every kind of failure listed below. No other exception may escape from `evaluate`.

## Syntax

- Integer literals made of decimal digits, no sign, no fraction. A literal must fit in a `long`.
- Binary operators `+`, `-`, `*`, `/`, `%`, `^` and parentheses.
- Unary `-` and unary `+`, which can be repeated (`--3` is `3`, `1 + + 2` is `3`).
- Whitespace between tokens is ignored; it is not allowed inside a number (`1 2` is an error).

## Precedence and associativity

From the loosest to the tightest binding:

1. `+` and `-`, left associative: `10 - 4 - 3` is `3`.
2. `*`, `/` and `%`, left associative, all on the same level: `10 % 4 * 3` is `6`.
3. Unary `-` and `+`.
4. `^` (power), **right associative**: `2 ^ 3 ^ 2` is `2 ^ 9`, that is `512`. The exponent may itself have a sign: `2 ^ -1` is parsed, and then rejected, see below.

Because unary minus binds looser than `^`, `-2 ^ 2` is `-(2 ^ 2)`, that is `-4`, while `(-2) ^ 2` is `4`. The operand of a unary minus extends over a whole power: `-(2 + 3) ^ 2` is `-25`.

## Arithmetic

- `/` truncates toward zero (`-7 / 2` is `-3`) and `%` takes the sign of the left operand (`-7 % 3` is `-1`, `7 % -3` is `1`).
- Division or remainder by zero is an error.
- All results are exact: any intermediate result outside the `long` range is an error (`9223372036854775807 + 1`, `2 ^ 63`, `Long.MIN_VALUE / -1`).
- `a ^ b` with a negative exponent is an error. `x ^ 0` is `1` for every `x`, including `0 ^ 0`. `0 ^ n`, `1 ^ n` and `(-1) ^ n` never overflow, whatever `n` is.

## Errors

An empty or blank expression, a missing operand or operator, an unknown character, unbalanced parentheses, `()`, trailing text after a complete expression and `null` are all errors.
