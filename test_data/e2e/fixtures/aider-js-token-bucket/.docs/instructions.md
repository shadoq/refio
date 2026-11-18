# Instructions

Implement a token-bucket rate limiter that works with an injected clock.

## API

`token-bucket.js` exports `TokenBucket` (CommonJS, `module.exports = { TokenBucket }`).

```js
const bucket = new TokenBucket({ capacity, refillPerSecond, clock, initial });
```

- `capacity`: the largest number of tokens the bucket can hold. A positive integer.
- `refillPerSecond`: how many tokens are added per second of clock time. A positive finite number, which may be fractional (`0.5` means one token every two seconds).
- `clock`: a function that returns the current time in milliseconds as a number. The bucket never reads the real time.
- `initial` (optional): the number of tokens at the start, from `0` to `capacity` (a number, may be fractional). The default is `capacity`, a full bucket.

Methods:

- `tryTake(n = 1)`: takes `n` tokens and returns `true` when the bucket holds at least `n`; otherwise takes nothing and returns `false`. `n` greater than `capacity` just returns `false`.
- `waitTime(n = 1)`: the number of milliseconds from now until `tryTake(n)` would succeed, as an integer rounded **up**; `0` when it would succeed now. It takes nothing. For `n` greater than `capacity` it returns `Infinity`.
- `available()`: the current number of tokens, a number that can be fractional.

## Refilling

Refilling is lazy: every method first reads the clock once and adds `elapsed * refillPerSecond / 1000` tokens, where `elapsed` is the number of milliseconds since the previous reading (the constructor takes the first reading). Tokens are kept as fractions, they are not rounded down, and a call that fails does not lose the fraction accumulated so far. The bucket never holds more than `capacity` tokens, and time spent full is not banked: after a long idle period the bucket holds `capacity` tokens and no more.

If the clock goes backwards (a reading lower than the previous one) no tokens are added and the new reading becomes the reference for the next call. Tokens are never removed because of the clock.

## Invalid arguments

- the constructor throws a `RangeError` for an invalid `capacity`, `refillPerSecond` or `initial`, and a `TypeError` when `clock` is not a function (checked in this order: capacity, refillPerSecond, clock, initial);
- `tryTake` and `waitTime` throw a `RangeError` when `n` is given and is not a positive integer (`0`, negative, fractional, `NaN`, `Infinity`, a string, `null`). Only `undefined` means the default of `1`.
