`src/mathx.js` has three stubbed functions that currently throw `"not implemented"`: `isPrime`,
`gcd`, and `fib`. Read `test.js` to learn the exact expected behavior, then implement all three so
the whole suite passes:

- `isPrime(n)` - true only for primes (2, 17 are prime; 1 and 15 are not).
- `gcd(a, b)` - greatest common divisor (`gcd(12, 18) === 6`).
- `fib(n)` - the n-th Fibonacci number, 0-indexed (`fib(0) === 0`, `fib(10) === 55`).

Keep the same exports. Do NOT change `test.js`. When done, `node test.js` must print `OK`.
