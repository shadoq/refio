const assert = require("node:assert");
const { isPrime, gcd, fib } = require("./src/mathx");

assert.strictEqual(isPrime(2), true);
assert.strictEqual(isPrime(15), false);
assert.strictEqual(isPrime(17), true);
assert.strictEqual(isPrime(1), false);

assert.strictEqual(gcd(12, 18), 6);
assert.strictEqual(gcd(17, 5), 1);

assert.strictEqual(fib(0), 0);
assert.strictEqual(fib(1), 1);
assert.strictEqual(fib(10), 55);

console.log("OK");
