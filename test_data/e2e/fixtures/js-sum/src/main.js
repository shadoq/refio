// BUG: the loop runs while i < n, so it stops before adding n itself (off by one).
function total(n) {
  let sum = 0;
  for (let i = 1; i < n; i++) sum += i;
  return sum;
}

const assert = require("node:assert");
assert.strictEqual(total(5), 15, `total(5) was ${total(5)}, expected 15`);
assert.strictEqual(total(1), 1, `total(1) was ${total(1)}, expected 1`);
console.log("OK");
